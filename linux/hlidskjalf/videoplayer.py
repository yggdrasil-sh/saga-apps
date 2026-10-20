"""Video-Wiedergabe im Chat (2.4): eigener Gtk.MediaStream ueber GStreamer playbin - Gtk.Video liefert Bedienelemente.

Bewusst nicht Gtk.MediaFile: dessen GStreamer-Backend baut einen GL-Videopfad auf und bricht ohne GLES ab (siehe Klingelton
in calls.py). Hier: gtk4paintablesink, falls vorhanden (GL oder Speicher), sonst appsink -> Gdk.MemoryTexture (ohne GL).
Quelle ist der lokale Proxy aus chatvideo.py (127.0.0.1, Token je Wiedergabe, Range -> blockweise entschluesselt), daher
kann der Player spulen, ohne dass das ganze Video geladen wird. Hoechstens ein Player gleichzeitig (nur im Dialog)."""
import threading

from gi.repository import Gdk, GLib, Gtk

from . import videotools as V


class SagaMediaStream(Gtk.MediaStream, Gdk.Paintable):
    """playbin (uri) als Gtk.MediaStream. on_error(text, codec: bool) im Hauptthread."""
    __gtype_name__ = "HKSagaMediaStream"

    def __init__(self, uri, on_error=None):
        super().__init__()
        Gst = V.gst()
        self.Gst = Gst
        self.on_error = on_error
        self.tex = None
        self.paintable = None
        self._frame = None
        self._frame_lock = threading.Lock()
        self._frame_pending = False
        self._seeking = False
        self._was_prepared = False
        self._closed = False
        self._timer = 0
        self.pipe = Gst.ElementFactory.make("playbin", None)
        self.pipe.set_property("uri", uri)
        self.pipe.set_property("flags", 0x1 | 0x2 | 0x10)  # Video, Ton, Software-Lautstaerke (keine Untertitel)
        sink = None
        if Gst.ElementFactory.find("gtk4paintablesink") is not None:
            try:
                ps = Gst.ElementFactory.make("gtk4paintablesink", None)
                self.paintable = ps.get_property("paintable")
                sink = ps
                flip = Gst.ElementFactory.make("videoflip", None)
                if flip is not None and flip.find_property("video-direction"):
                    Gst.util_set_object_arg(flip, "video-direction", "auto")    # Drehung aus den Metadaten
                    sink = Gst.Bin.new(None)
                    sink.add(flip)
                    sink.add(ps)
                    flip.link(ps)
                    sink.add_pad(Gst.GhostPad.new("sink", flip.get_static_pad("sink")))
                self.paintable.connect("invalidate-contents", lambda *_: self.invalidate_contents())
                self.paintable.connect("invalidate-size", lambda *_: self.invalidate_size())
            except (GLib.Error, TypeError, AttributeError):
                sink, self.paintable = None, None
        if sink is None:
            flip = "videoflip video-direction=auto ! " if Gst.ElementFactory.find("videoflip") else ""
            sink = Gst.parse_bin_from_description(
                f"videoconvert ! {flip}videoscale ! video/x-raw,format=BGRA,pixel-aspect-ratio=1/1 ! "
                "appsink name=s max-buffers=2 drop=true emit-signals=true", True)
            app = sink.get_by_name("s")
            app.connect("new-sample", lambda a: self._sample(a.emit("pull-sample")))
            app.connect("new-preroll", lambda a: self._sample(a.emit("pull-preroll")))
        self.pipe.set_property("video-sink", sink)
        bus = self.pipe.get_bus()
        bus.add_signal_watch()
        self._bus_id = bus.connect("message", self._msg)
        self.pipe.set_state(Gst.State.PAUSED)               # Vorladen: Dauer, Abmessungen, erstes Bild

    # --- Bilder (appsink, Streaming-Thread) ------------------------------------------------------------------------------
    def _sample(self, s):
        Gst = self.Gst
        if s is None or self._closed:
            return Gst.FlowReturn.OK
        caps = s.get_caps()
        st = caps.get_structure(0) if caps else None
        if st is None:
            return Gst.FlowReturn.OK
        okw, w = st.get_int("width")
        okh, h = st.get_int("height")
        buf = s.get_buffer()
        if not (okw and okh) or buf is None:
            return Gst.FlowReturn.OK
        data = buf.extract_dup(0, buf.get_size())
        with self._frame_lock:
            self._frame = (w, h, data)
            if self._frame_pending:
                return Gst.FlowReturn.OK
            self._frame_pending = True
        GLib.idle_add(self._show_frame)
        return Gst.FlowReturn.OK

    def _show_frame(self):
        with self._frame_lock:
            fr, self._frame, self._frame_pending = self._frame, None, False
        if fr is None or self._closed:
            return False
        w, h, data = fr
        if len(data) < w * h * 4:
            return False
        size_changed = self.tex is None or (self.tex.get_width(), self.tex.get_height()) != (w, h)
        self.tex = Gdk.MemoryTexture.new(w, h, Gdk.MemoryFormat.B8G8R8A8, GLib.Bytes.new(data), w * 4)
        if size_changed:
            self.invalidate_size()
        self.invalidate_contents()
        return False

    # --- Gdk.Paintable ---------------------------------------------------------------------------------------------------
    def do_snapshot(self, snap, w, h):
        if self.paintable is not None:
            self.paintable.snapshot(snap, w, h)
        elif self.tex is not None:
            self.tex.snapshot(snap, w, h)

    def do_get_intrinsic_width(self):
        if self.paintable is not None:
            return self.paintable.get_intrinsic_width()
        return self.tex.get_width() if self.tex is not None else 0

    def do_get_intrinsic_height(self):
        if self.paintable is not None:
            return self.paintable.get_intrinsic_height()
        return self.tex.get_height() if self.tex is not None else 0

    def do_get_intrinsic_aspect_ratio(self):
        w, h = self.do_get_intrinsic_width(), self.do_get_intrinsic_height()
        return w / h if w and h else 0.0

    # --- Gtk.MediaStream -------------------------------------------------------------------------------------------------
    def do_play(self):
        if self._closed:
            return False
        self.pipe.set_state(self.Gst.State.PLAYING)
        if not self._timer:
            self._timer = GLib.timeout_add(250, self._tick)
        return True

    def do_pause(self):
        if not self._closed:
            self.pipe.set_state(self.Gst.State.PAUSED)

    def do_seek(self, ts):
        Gst = self.Gst
        self._seeking = True
        if not self.pipe.seek_simple(Gst.Format.TIME, Gst.SeekFlags.FLUSH | Gst.SeekFlags.KEY_UNIT, int(ts) * 1000):
            self._seeking = False
            self.seek_failed()

    def do_update_audio(self, muted, volume):
        self.pipe.set_property("mute", bool(muted))
        self.pipe.set_property("volume", max(0.0, min(1.0, float(volume))))

    def _tick(self):
        if self._closed:
            self._timer = 0
            return False
        ok, pos = self.pipe.query_position(self.Gst.Format.TIME)
        if ok and pos >= 0 and not self._seeking:
            self.update(pos // 1000)
        return True

    def _prepared(self):
        Gst = self.Gst
        okd, dur = self.pipe.query_duration(Gst.Format.TIME)
        q = Gst.Query.new_seeking(Gst.Format.TIME)
        seekable = bool(self.pipe.query(q) and q.parse_seeking()[1])
        n_a = self.pipe.get_property("n-audio")
        n_v = self.pipe.get_property("n-video")
        self.stream_prepared(n_a > 0, n_v > 0, seekable or okd, dur // 1000 if okd and dur > 0 else 0)

    def _msg(self, _bus, msg):
        Gst = self.Gst
        t = msg.type
        if self._closed:
            return
        if t == Gst.MessageType.ASYNC_DONE:
            if not self._was_prepared:
                self._was_prepared = True
                self._prepared()
            if self._seeking:
                self._seeking = False
                self.seek_success()
                self._tick()
        elif t == Gst.MessageType.EOS:
            (getattr(self, "stream_ended", None) or self.ended)()
        elif t == Gst.MessageType.ERROR:
            err, _dbg = msg.parse_error()
            codec = bool(err and err.matches(Gst.StreamError.quark(), Gst.StreamError.CODEC_NOT_FOUND)) or \
                bool(err and err.matches(Gst.CoreError.quark(), Gst.CoreError.MISSING_PLUGIN))
            text = err.message if err else "error"
            self.close()
            if err is not None:
                try:
                    self.gerror(err)
                except (TypeError, AttributeError):
                    pass
            if self.on_error:
                self.on_error(text, codec)
        elif t == Gst.MessageType.ELEMENT and msg.get_structure() is not None \
                and msg.get_structure().get_name() == "missing-plugin":
            pass                                           # folgt als Fehler (CODEC_NOT_FOUND)

    def close(self):
        if self._closed:
            return
        self._closed = True
        if self._timer:
            GLib.source_remove(self._timer)
            self._timer = 0
        bus = self.pipe.get_bus()
        try:
            bus.disconnect(self._bus_id)
            bus.remove_signal_watch()
        except (TypeError, GLib.Error):
            pass
        self.pipe.set_state(self.Gst.State.NULL)
