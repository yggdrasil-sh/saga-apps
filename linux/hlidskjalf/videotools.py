"""GStreamer-Hilfen fuer Videos im Chat (2.4): Metadaten (Discoverer), Poster (320 px, JPEG <= 30 KB) und optional
"optimiert 720p" (H.264 mit x264enc/openh264enc, AAC, MP4 mit moov vorn). Alles optional: fehlt GStreamer oder ein
Element, gibt es Metadaten/Poster nicht bzw. nur das Original - gesendet wird trotzdem.
Laeuft im Hintergrund-Thread (eigene Pipelines, Bus per timed_pop - keine Hauptschleife noetig)."""
import io
import os
import threading

from gi.repository import GLib

POSTER_EDGE = 320
POSTER_BYTES = 30 * 1024
OPT_LONG, OPT_SHORT = 1280, 720
OPT_VBR = 2500                        # kbit/s
OPT_ABR = 128000                      # bit/s

_gst = None
_lock = threading.Lock()


def gst():
    """Gst-Modul (initialisiert) oder None"""
    global _gst
    with _lock:
        if _gst is None:
            try:
                import gi
                gi.require_version("Gst", "1.0")
                from gi.repository import Gst
                Gst.init(None)
                _gst = Gst
            except (ImportError, ValueError, GLib.Error):
                _gst = False
    return _gst or None


def has(*names):
    Gst = gst()
    return bool(Gst) and all(Gst.ElementFactory.find(n) is not None for n in names)


def _first(*names):
    Gst = gst()
    for n in names:
        if Gst and Gst.ElementFactory.find(n) is not None:
            return n
    return None


def h264_encoder():
    return _first("x264enc", "openh264enc")


def aac_encoder():
    return _first("avenc_aac", "fdkaacenc", "voaacenc")


def can_optimize():
    """Verkleinern moeglich? (H.264- und AAC-Encoder, mp4mux, decodebin)"""
    return bool(h264_encoder() and aac_encoder() and has("decodebin", "videoconvert", "videoscale", "mp4mux", "h264parse",
                                                            "audioconvert", "audioresample", "filesrc", "filesink"))


def can_play():
    """Abspielen moeglich? (playbin, souphttpsrc fuer den lokalen Proxy, appsink)"""
    return has("playbin", "souphttpsrc", "appsink", "videoconvert")


def _uri(path):
    return GLib.filename_to_uri(os.path.abspath(path), None)


# --- Metadaten ----------------------------------------------------------------------------------------------------------
_CODECS = {"video/x-h264": "avc1", "video/x-h265": "hvc1", "video/x-vp9": "vp09", "video/x-vp8": "vp8", "video/x-av1": "av01"}


def probe(path, timeout=15):
    """-> {"dur", "w", "h", "rot", "vc", "video": bool, "par": (n, d)} oder {} (kein GStreamer/kein lesbares Video)"""
    Gst = gst()
    if not Gst:
        return {}
    try:
        import gi
        gi.require_version("GstPbutils", "1.0")
        from gi.repository import GstPbutils
        d = GstPbutils.Discoverer.new(timeout * Gst.SECOND)
        info = d.discover_uri(_uri(path))
    except (ImportError, ValueError, GLib.Error):
        return {}
    out = {"video": False}
    dur = info.get_duration()
    if dur and dur != Gst.CLOCK_TIME_NONE:
        out["dur"] = round(dur / Gst.SECOND, 1)
    vs = info.get_video_streams()
    if vs:
        v = vs[0]
        out.update(video=True, w=v.get_width(), h=v.get_height(), par=(v.get_par_num() or 1, v.get_par_denom() or 1))
        caps = v.get_caps()
        if caps and caps.get_size():
            vc = _CODECS.get(caps.get_structure(0).get_name())
            if vc:
                out["vc"] = vc
        rot = 0
        tags = v.get_tags() or info.get_tags()
        if tags:
            ok, o = tags.get_string("image-orientation")
            if ok and o and o.startswith("rotate-"):
                try:
                    rot = int(o[7:]) % 360
                except ValueError:
                    rot = 0
        out["rot"] = rot if rot in (0, 90, 180, 270) else 0
    return out


def _fit(w, h, edge):
    sc = min(1.0, edge / max(1, w), edge / max(1, h))
    return max(2, int(round(w * sc / 2)) * 2), max(2, int(round(h * sc / 2)) * 2)


def poster(path, meta=None, timeout=15):
    """Bild bei 1 s (bzw. 10 % der Dauer), laengste Seite 320 px, JPEG <= 30 KB -> (bytes, w, h) oder None"""
    Gst = gst()
    meta = meta if meta is not None else probe(path)
    if not Gst or not meta.get("video") or not has("playbin", "appsink", "videoconvert", "videoscale"):
        return None
    w, h = meta.get("w") or 0, meta.get("h") or 0
    pn, pd = meta.get("par") or (1, 1)
    w = int(w * pn / max(1, pd))
    if meta.get("rot") in (90, 270):
        w, h = h, w
    if not w or not h:
        return None
    tw, th = _fit(w, h, POSTER_EDGE)
    flip = "videoflip video-direction=auto ! " if has("videoflip") else ""
    try:
        sink = Gst.parse_bin_from_description(
            f"videoconvert ! {flip}videoscale ! video/x-raw,format=RGBx,width={tw},height={th},pixel-aspect-ratio=1/1 ! "
            "appsink name=s sync=false max-buffers=1 drop=true", True)
    except GLib.Error:
        return None
    pipe = Gst.ElementFactory.make("playbin", None)
    pipe.set_property("uri", _uri(path))
    pipe.set_property("flags", 0x1)                         # nur Video (kein Ton, keine Untertitel)
    pipe.set_property("video-sink", sink)
    fake = Gst.ElementFactory.make("fakesink", None)
    if fake is not None:
        pipe.set_property("audio-sink", fake)
    app = sink.get_by_name("s")
    try:
        pipe.set_state(Gst.State.PAUSED)
        ok, _st, _p = pipe.get_state(timeout * Gst.SECOND)
        if ok == Gst.StateChangeReturn.FAILURE:
            return None
        dur = meta.get("dur") or 0
        t = min(1.0, dur * 0.1) if dur else 0
        if t > 0:
            pipe.seek_simple(Gst.Format.TIME, Gst.SeekFlags.FLUSH | Gst.SeekFlags.ACCURATE, int(t * Gst.SECOND))
            pipe.get_state(timeout * Gst.SECOND)
        sample = app.emit("try-pull-preroll", timeout * Gst.SECOND)
        if sample is None:
            return None
        buf = sample.get_buffer()
        ok, mi = buf.map(Gst.MapFlags.READ)
        if not ok:
            return None
        try:
            raw = bytes(mi.data)
        finally:
            buf.unmap(mi)
    finally:
        pipe.set_state(Gst.State.NULL)
    try:
        from PIL import Image
    except ImportError:
        return None
    if len(raw) < tw * th * 4:
        return None
    im = Image.frombuffer("RGB", (tw, th), raw, "raw", "RGBX", tw * 4, 1)
    data = None
    for q in (70, 55, 40, 30):
        out = io.BytesIO()
        im.save(out, "JPEG", quality=q, optimize=True)
        data = out.getvalue()
        if len(data) <= POSTER_BYTES:
            break
    return data, tw, th


# --- optimiert 720p ------------------------------------------------------------------------------------------------------
def optimize(src, dst, meta=None, progress=None, cancelled=None):
    """src -> dst (MP4, H.264 bis 1280x720, ~2,5 Mbit/s, AAC 128 kbit/s, moov vorn). progress(0..1). -> True;
    Fehler: RuntimeError; abgebrochen: filev2.Cancelled (dst wird geloescht)"""
    from .filev2 import Cancelled
    Gst = gst()
    venc, aenc = h264_encoder(), aac_encoder()
    if not can_optimize():
        raise RuntimeError("no encoder")
    meta = meta if meta is not None else probe(src)
    w, h = meta.get("w") or 1280, meta.get("h") or 720
    pn, pd = meta.get("par") or (1, 1)
    w = int(w * pn / max(1, pd))
    if meta.get("rot") in (90, 270):
        w, h = h, w
    sc = min(1.0, OPT_LONG / max(w, h), OPT_SHORT / max(1, min(w, h)))
    tw, th = max(2, int(w * sc / 2) * 2), max(2, int(h * sc / 2) * 2)
    if venc == "x264enc":
        ve = f"x264enc bitrate={OPT_VBR} speed-preset=veryfast key-int-max=60"
    else:
        ve = f"openh264enc bitrate={OPT_VBR * 1000} rate-control=bitrate"
    flip = "videoflip video-direction=auto ! " if has("videoflip") else ""
    ae = f"{aenc} bitrate={OPT_ABR}"
    pipe = Gst.Pipeline.new("hk-opt")
    try:
        fsrc = Gst.ElementFactory.make("filesrc", None)
        dec = Gst.ElementFactory.make("decodebin", None)
        vbin = Gst.parse_bin_from_description(
            f"queue ! videoconvert ! {flip}videoscale ! video/x-raw,width={tw},height={th},pixel-aspect-ratio=1/1 ! "
            f"{ve} ! video/x-h264,profile=main ! h264parse", True)
        abin = Gst.parse_bin_from_description(f"queue ! audioconvert ! audioresample ! {ae}" + (" ! aacparse" if has("aacparse") else ""),
                                              True)
        mux = Gst.ElementFactory.make("mp4mux", None)
        sink = Gst.ElementFactory.make("filesink", None)
    except GLib.Error as e:
        raise RuntimeError(str(e)) from e
    if mux.find_property("faststart"):
        mux.set_property("faststart", True)
    sink.set_property("location", dst)
    fsrc.set_property("location", src)
    for el in (fsrc, dec, mux, sink):
        pipe.add(el)
    fsrc.link(dec)
    mux.link(sink)
    linked = {"v": False, "a": False}
    lock = threading.Lock()

    def pad_added(_d, pad):
        caps = pad.get_current_caps() or pad.query_caps(None)
        name = caps.get_structure(0).get_name() if caps and caps.get_size() else ""
        kind = "v" if name.startswith("video/x-raw") else "a" if name.startswith("audio/x-raw") else None
        with lock:
            if kind is None or linked[kind]:
                return
            linked[kind] = True
        b = vbin if kind == "v" else abin
        pipe.add(b)
        tmpl = "video_%u" if kind == "v" else "audio_%u"
        mp = mux.request_pad_simple(tmpl) if hasattr(mux, "request_pad_simple") else mux.get_request_pad(tmpl)
        b.get_static_pad("src").link(mp)
        b.sync_state_with_parent()
        pad.link(b.get_static_pad("sink"))
    dec.connect("pad-added", pad_added)
    bus = pipe.get_bus()
    ok = False
    try:
        if pipe.set_state(Gst.State.PLAYING) == Gst.StateChangeReturn.FAILURE:
            raise RuntimeError("pipeline")
        dur = int((meta.get("dur") or 0) * Gst.SECOND)
        while True:
            if cancelled and cancelled():
                raise Cancelled()
            msg = bus.timed_pop_filtered(250 * Gst.MSECOND, Gst.MessageType.EOS | Gst.MessageType.ERROR)
            if msg is None:
                if progress and dur:
                    okp, pos = pipe.query_position(Gst.Format.TIME)
                    if okp and pos >= 0:
                        progress(min(1.0, pos / dur))
                continue
            if msg.type == Gst.MessageType.ERROR:
                err, _dbg = msg.parse_error()
                raise RuntimeError(err.message if err else "error")
            ok = True
            break
    finally:
        pipe.set_state(Gst.State.NULL)
        if not ok:
            try:
                os.remove(dst)
            except OSError:
                pass
    if progress:
        progress(1.0)
    return ok
