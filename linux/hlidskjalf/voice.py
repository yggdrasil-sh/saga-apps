"""Sprachnachrichten im Chat (Saga 2.5, Konzept Chat B3) - Aufnahme und Wiedergabe mit GStreamer, ohne Oberflaeche.

Format (wie Web static/chat-voice.js): Opus mono 48 kHz, 24 kbit/s VBR, 20-ms-Rahmen, Container Ogg (RFC 7845),
hoechstens 15 Minuten. Gesendet wird die Datei als saga-file/v2-Anhang mit {voice: true, dur, wf} im verschluesselten
Inhalt (chat.ChatState.send_voice); wf = 64 Werte 0-255 (Spitzen der Lautstaerke je Abschnitt, normalisiert).

  Aufnahme:   pipewiresrc bzw. pulsesrc -> audioconvert -> audioresample -> 48 kHz mono -> level (100 ms) ->
              opusenc bitrate=24000 (VBR, 20 ms, Sprache) -> oggmux -> appsink. Nur Base-/Good-Plugins.
              Tests nehmen audiotestsrc statt des Mikrofons (Quelle als Parameter bzw. HK_VOICE_SRC).
  Wiedergabe: playbin mit appsrc (Zufallszugriff: need-data/seek-data liest Klartext-Bereiche aus der entschluesselten
              Datei - chatvideo.VideoSource, Block fuer Block mit Range) -> oggdemux -> opusdec; Tempo 1x/1,5x/2x ueber
              eine Suche mit Rate (scaletempo haelt die Tonhoehe). Die Dauer kommt aus "dur" (nicht aus dem Strom).
Python bekommt dabei nur die Bytes der eigenen Aufnahme bzw. die entschluesselten Bloecke - nie den ganzen Server-Strom."""
import os
import struct
import threading
import time

from gi.repository import GLib, GObject

from . import videotools as V

RATE = 48000
WF_N = 64
MAX_S = 15 * 60
WARN_BEFORE_S = 60                  # Hinweis eine Minute vor dem Ende (14:00)
MIN_S = 1.0                         # kuerzere Aufnahmen werden verworfen
SPEEDS = (1.0, 1.5, 2.0)
LEVEL_NS = 100 * 1000 * 1000        # eine Lautstaerke je 100 ms (wie das Web)


class VoiceError(Exception):
    """Aufnahme bzw. Wiedergabe nicht moeglich (fertiger Text)"""


def fmt(s):
    """Sekunden -> "m:ss" """
    s = max(0, int(round(float(s or 0))))
    return f"{s // 60}:{s % 60:02d}"


def waveform(levels, n=WF_N):
    """Lautstaerken (RMS, linear) -> n Werte 0-255: je Abschnitt das Maximum, normalisiert, Wurzel (wie das Web)"""
    out = bytearray(n)
    cnt = len(levels)
    if not cnt:
        return bytes(out)
    v = []
    for i in range(n):
        a = i * cnt // n
        z = max(a + 1, (i + 1) * cnt // n)
        v.append(max(levels[a:min(z, cnt)] or [0.0]))
    mx = max(v)
    for i in range(n):
        out[i] = int(round(255 * (v[i] / mx) ** 0.5)) if mx > 0 else 0
    return bytes(out)


def wf_decode(b):
    """wf aus dem Anhang (Bytes) -> 64 Werte (fehlt/kaputt: Nullen)"""
    vals = list(b or b"")[:WF_N]
    return vals + [0] * (WF_N - len(vals))


# --- Ogg (RFC 3533 / 7845): nur lesen - Dauer, Kopf ---------------------------------------------------------------------
def ogg_pages(data):
    """-> [(kopf-flags, granule, serial, seq, [pakete-roh als Bytes eines Segments-Laufs])] (nur grob, fuer Tests/Dauer)"""
    out, o, n = [], 0, len(data)
    while o + 27 <= n and data[o:o + 4] == b"OggS":
        flags = data[o + 5]
        granule, serial, seq = struct.unpack_from("<qII", data, o + 6)
        segs = data[o + 26]
        lace = data[o + 27:o + 27 + segs]
        body = o + 27 + segs
        size = sum(lace)
        out.append((flags, granule, serial, seq, data[body:body + size]))
        o = body + size
    return out


def ogg_opus_info(data):
    """Ogg/Opus -> {"preskip", "rate", "channels", "dur" (s), "pages"} oder None (kein Ogg/Opus)"""
    pages = ogg_pages(data)
    if len(pages) < 2 or not pages[0][4].startswith(b"OpusHead") or len(pages[0][4]) < 19:
        return None
    head = pages[0][4]
    ch, preskip, rate = head[9], struct.unpack_from("<H", head, 10)[0], struct.unpack_from("<I", head, 12)[0]
    last = max((p[1] for p in pages if p[1] >= 0), default=0)
    return {"preskip": preskip, "rate": rate, "channels": ch, "dur": max(0, last - preskip) / RATE, "pages": len(pages),
            "tags": pages[1][4].startswith(b"OpusTags"), "eos": bool(pages[-1][0] & 4)}


# --- Quellen und Senken -------------------------------------------------------------------------------------------------
def _mic_source():
    """Mikrofon: HK_VOICE_SRC (Tests), sonst pipewiresrc, sonst pulsesrc, sonst autoaudiosrc"""
    env = os.environ.get("HK_VOICE_SRC")
    if env:
        return env
    for n in ("pipewiresrc", "pulsesrc", "autoaudiosrc"):
        if V.has(n):
            return n
    return None


def can_record():
    return V.has("opusenc", "oggmux", "appsink", "audioconvert", "audioresample") and _mic_source() is not None


def can_play():
    return V.has("playbin", "appsrc", "oggdemux", "opusdec")


def _audio_sink():
    """HK_AUDIO_SINK (Tests: z. B. "fakesink sync=true"), sonst autoaudiosink"""
    Gst = V.gst()
    desc = os.environ.get("HK_AUDIO_SINK") or "autoaudiosink"
    try:
        return Gst.parse_bin_from_description(desc, True) if " " in desc or "!" in desc else Gst.ElementFactory.make(desc, None)
    except GLib.Error:
        return None


# --- Aufnahme -----------------------------------------------------------------------------------------------------------
class Recorder:
    """Eine Aufnahme. on_tick(sekunden, pegel 0..1) und on_limit() im Hauptthread. start() -> VoiceError, wenn es nicht geht.
    stop(done, error): done({"data": bytes, "dur": s, "wf": bytes(64)}) im Hauptthread; cancel() verwirft."""

    def __init__(self, on_tick=None, on_limit=None, src=None, max_s=MAX_S, kbps=24):
        self.on_tick, self.on_limit = on_tick, on_limit
        self.src = src or _mic_source()
        self.max_s, self.kbps = max_s, kbps
        self.pipe = None
        self.levels = []
        self.secs = 0.0
        self.chunks = []
        self._lock = threading.Lock()
        self._done = None
        self._stopping = False
        self._limited = False
        self.state = "idle"             # idle | rec | stopping | done | error | cancelled
        self.error = None

    def start(self):
        Gst = V.gst()
        if not Gst or not self.src or not V.has("opusenc", "oggmux", "appsink"):
            raise VoiceError("gstreamer")
        desc = (f"{self.src} ! queue ! audioconvert ! audioresample ! audio/x-raw,rate={RATE},channels=1 ! "
                f"level name=lv interval={LEVEL_NS} post-messages=true ! "
                f"opusenc name=enc bitrate={int(self.kbps) * 1000} bitrate-type=vbr frame-size=20 audio-type=voice ! "
                "oggmux name=mux ! appsink name=out emit-signals=true sync=false")
        try:
            self.pipe = Gst.parse_launch(desc)
        except GLib.Error as e:
            raise VoiceError(str(e)) from e
        out = self.pipe.get_by_name("out")
        out.connect("new-sample", self._sample)
        bus = self.pipe.get_bus()
        bus.add_signal_watch()
        self._bus_id = bus.connect("message", self._msg)
        if self.pipe.set_state(Gst.State.PLAYING) == Gst.StateChangeReturn.FAILURE:
            self._teardown()
            raise VoiceError("start")
        self.state = "rec"
        self.t0 = time.monotonic()

    def _sample(self, sink):
        s = sink.emit("pull-sample")
        if s is not None:
            buf = s.get_buffer()
            ok, info = buf.map(V.gst().MapFlags.READ)
            if ok:
                with self._lock:
                    self.chunks.append(bytes(info.data))
                buf.unmap(info)
        return V.gst().FlowReturn.OK

    def _msg(self, _bus, msg):
        Gst = V.gst()
        t = msg.type
        if t == Gst.MessageType.ELEMENT and msg.get_structure() is not None and msg.get_structure().get_name() == "level":
            st = msg.get_structure()
            try:
                rms = st.get_value("rms")
                db = float(rms[0]) if rms is not None and len(rms) else -100.0
            except (TypeError, ValueError, IndexError):
                db = -100.0
            lin = 10 ** (max(-100.0, min(0.0, db)) / 20)
            ok, end = st.get_clock_time("endtime")
            if self.state == "rec":
                self.levels.append(lin)
                self.secs = (end / 1e9) if ok else time.monotonic() - self.t0
                if self.on_tick:
                    self.on_tick(self.secs, min(1.0, lin * 4))
                if self.secs >= self.max_s and not self._limited:
                    self._limited = True
                    if self.on_limit:
                        self.on_limit()
        elif t == Gst.MessageType.EOS:
            self._finish()
        elif t == Gst.MessageType.ERROR:
            err, _dbg = msg.parse_error()
            self.error = err.message if err else "error"
            was = self.state
            self.state = "error"
            self._teardown()
            if self._done and was in ("rec", "stopping"):
                self._done[1](VoiceError(self.error))
                self._done = None

    def stop(self, done, error):
        """Aufnahme beenden: EOS durch die Kette (letzte Pakete, Ogg-Ende), dann done(ergebnis)"""
        if self.state != "rec":
            error(VoiceError("state"))
            return
        self.state = "stopping"
        self._done = (done, error)
        # EOS an die Pipeline geht an die Quellen: letzte Pakete, Ogg-Ende (EOS-Seite), dann EOS auf dem Bus
        self.pipe.send_event(V.gst().Event.new_eos())
        GLib.timeout_add(4000, self._eos_timeout)

    def _eos_timeout(self):
        if self.state == "stopping":
            self._finish()
        return False

    def _finish(self):
        if self.state != "stopping":
            return
        self.state = "done"
        self._teardown()
        with self._lock:
            data = b"".join(self.chunks)
        info = ogg_opus_info(data)
        dur = min(float(self.max_s), info["dur"] if info else self.secs)
        cb = self._done
        self._done = None
        if cb is None:
            return
        if info is None:
            cb[1](VoiceError("empty"))
            return
        cb[0]({"data": data, "dur": dur, "wf": waveform(self.levels)})

    def cancel(self):
        if self.state in ("rec", "stopping"):
            self.state = "cancelled"
            self._done = None
            self._teardown()

    def _teardown(self):
        Gst = V.gst()
        p, self.pipe = self.pipe, None
        if p is None:
            return
        try:
            bus = p.get_bus()
            bus.disconnect(self._bus_id)
            bus.remove_signal_watch()
        except Exception:  # noqa: BLE001
            pass
        p.set_state(Gst.State.NULL)


# --- Wiedergabe ---------------------------------------------------------------------------------------------------------
class Player(GObject.Object):
    """playbin + appsrc ueber einer Klartext-Quelle mit size und iter_plain(a, b) (chatvideo.VideoSource bzw. MemorySource).
    Signale: "changed" (Zustand/Position, Hauptthread), "ended". dur = Dauer aus dem Anhang (Sekunden)."""
    __gtype_name__ = "HKVoicePlayer"
    __gsignals__ = {
        "changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "ended": (GObject.SignalFlags.RUN_FIRST, None, ()),
    }

    def __init__(self, source, dur, rate=1.0):
        super().__init__()
        Gst = V.gst()
        if not Gst or not can_play():
            raise VoiceError("gstreamer")
        self.Gst = Gst
        self.source, self.dur, self.rate = source, max(0.0, float(dur or 0)), rate
        self.off = 0
        self.playing = False
        self.pos = 0.0
        self.error = None
        self._timer = 0
        self._closed = False
        self._want_seek = None
        self._prepared = False
        self.pipe = Gst.ElementFactory.make("playbin", None)
        self.pipe.set_property("uri", "appsrc://")
        self.pipe.set_property("flags", 0x2 | 0x10)       # nur Ton, Software-Lautstaerke
        sink = _audio_sink()
        if sink is not None:
            self.pipe.set_property("audio-sink", sink)
        if Gst.ElementFactory.find("scaletempo") is not None:
            self.pipe.set_property("audio-filter", Gst.ElementFactory.make("scaletempo", None))
        self.pipe.connect("source-setup", self._setup)
        bus = self.pipe.get_bus()
        bus.add_signal_watch()
        self._bus_id = bus.connect("message", self._msg)

    # appsrc im Zufallszugriff: der Demuxer fragt Bereiche an (need-data), springt per seek-data
    def _setup(self, _pb, src):
        Gst = self.Gst
        src.set_property("stream-type", 2)                 # GST_APP_STREAM_TYPE_RANDOM_ACCESS
        src.set_property("format", Gst.Format.BYTES)
        src.set_property("size", int(self.source.size))
        src.set_property("max-bytes", 512 * 1024)
        src.connect("need-data", self._need)
        src.connect("seek-data", self._seek_data)

    def _seek_data(self, _src, offset):
        self.off = int(offset)
        return True

    def _need(self, src, length):
        Gst = self.Gst
        if self._closed:
            return
        a = self.off
        if a >= self.source.size:
            src.emit("end-of-stream")
            return
        b = min(self.source.size - 1, a + max(4096, int(length)) - 1)
        try:
            data = b"".join(self.source.iter_plain(a, b))
        except Exception as e:  # noqa: BLE001 - Netz/Schluessel: als Fehler melden
            self.error = str(e)
            src.emit("end-of-stream")
            GLib.idle_add(self._fail)
            return
        if not data:
            src.emit("end-of-stream")
            return
        buf = Gst.Buffer.new_wrapped(data)
        buf.offset = a
        self.off = a + len(data)
        src.emit("push-buffer", buf)

    def _fail(self):
        self.playing = False
        self.emit("changed")
        return False

    def _msg(self, _bus, msg):
        Gst = self.Gst
        t = msg.type
        if t == Gst.MessageType.EOS:
            self.playing = False
            self.pos = 0.0
            self._stop_timer()
            self.pipe.set_state(Gst.State.PAUSED)
            self.pipe.seek_simple(Gst.Format.TIME, Gst.SeekFlags.FLUSH, 0)
            self.emit("changed")
            self.emit("ended")
        elif t == Gst.MessageType.ERROR:
            err, _dbg = msg.parse_error()
            self.error = err.message if err else "error"
            self.playing = False
            self._stop_timer()
            self.emit("changed")
        elif t == Gst.MessageType.ASYNC_DONE:
            if not self._prepared:
                self._prepared = True
                if self._want_seek is not None or self.rate != 1.0:
                    self._apply_seek(self._want_seek if self._want_seek is not None else 0.0)
                    self._want_seek = None

    def _apply_seek(self, sec):
        Gst = self.Gst
        sec = max(0.0, min(self.dur - 0.05 if self.dur > 0.1 else sec, sec))
        self.pipe.seek(self.rate, Gst.Format.TIME, Gst.SeekFlags.FLUSH | Gst.SeekFlags.ACCURATE, Gst.SeekType.SET,
                       int(sec * Gst.SECOND), Gst.SeekType.NONE, -1)
        self.pos = sec

    def play(self):
        if self._closed:
            return
        Gst = self.Gst
        self.error = None
        if self.pipe.set_state(Gst.State.PLAYING) == Gst.StateChangeReturn.FAILURE:
            self.error = "start"
            self.emit("changed")
            return
        self.playing = True
        if not self._timer:
            self._timer = GLib.timeout_add(100, self._tick)
        self.emit("changed")

    def pause(self):
        if self._closed:
            return
        self.pipe.set_state(self.Gst.State.PAUSED)
        self.playing = False
        self._stop_timer()
        self._tick()
        self.emit("changed")

    def toggle(self):
        (self.pause if self.playing else self.play)()

    def seek(self, frac):
        """an eine Stelle springen (0..1 der Dauer)"""
        sec = max(0.0, min(1.0, float(frac))) * self.dur
        if not self._prepared:
            self._want_seek = sec
            self.pos = sec
            self.pipe.set_state(self.Gst.State.PAUSED)
        else:
            self._apply_seek(sec)
        self.emit("changed")

    def set_rate(self, r):
        self.rate = float(r)
        if self._prepared:
            self._apply_seek(self.position())

    def position(self):
        ok, p = self.pipe.query_position(self.Gst.Format.TIME) if self.pipe else (False, 0)
        if ok and p >= 0:
            self.pos = min(self.dur or p / 1e9, p / 1e9)
        return self.pos

    def _tick(self):
        if self._closed:
            self._timer = 0
            return False
        self.position()
        self.emit("changed")
        return self.playing

    def _stop_timer(self):
        if self._timer:
            GLib.source_remove(self._timer)
            self._timer = 0

    def close(self):
        if self._closed:
            return
        self._closed = True
        self._stop_timer()
        try:
            bus = self.pipe.get_bus()
            bus.disconnect(self._bus_id)
            bus.remove_signal_watch()
        except Exception:  # noqa: BLE001
            pass
        self.pipe.set_state(self.Gst.State.NULL)
        self.playing = False


class BytesSource:
    """Vorschau vor dem Senden: die eigene Aufnahme im Speicher"""

    def __init__(self, data):
        self.data, self.size, self.mime = data, len(data), "audio/ogg"

    def iter_plain(self, a, b, cancelled=None):
        b = min(b, self.size - 1)
        for x in range(a, b + 1, 64 * 1024):
            yield self.data[x:min(b + 1, x + 64 * 1024)]


def probe_duration(data, timeout=10):
    """Test/Pruefung: Ogg/Opus wirklich dekodieren (oggdemux, opusdec, fakesink) -> Dauer in Sekunden aus den dekodierten
    Samples, oder None. Blockiert (eigene Hauptschleife nicht noetig)."""
    Gst = V.gst()
    if not Gst:
        return None
    pipe = Gst.parse_launch("appsrc name=src format=bytes ! oggdemux ! opusdec ! audioconvert ! "
                            "audio/x-raw,format=S16LE,rate=48000,channels=1 ! appsink name=out sync=false emit-signals=false")
    src, out = pipe.get_by_name("src"), pipe.get_by_name("out")
    pipe.set_state(Gst.State.PLAYING)
    src.emit("push-buffer", Gst.Buffer.new_wrapped(bytes(data)))
    src.emit("end-of-stream")
    n = 0
    t_end = time.monotonic() + timeout
    while time.monotonic() < t_end:
        s = out.emit("try-pull-sample", 200 * Gst.MSECOND)
        if s is None:
            if out.get_property("eos"):
                break
            msg = pipe.get_bus().pop_filtered(Gst.MessageType.ERROR)
            if msg is not None:
                pipe.set_state(Gst.State.NULL)
                return None
            continue
        n += s.get_buffer().get_size() // 2
    pipe.set_state(Gst.State.NULL)
    return n / RATE
