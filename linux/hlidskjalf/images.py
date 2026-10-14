"""Bilder vom Server (mit Sitzung): Platte + Speicher-Zwischenspeicher, Pillow dekodiert (WebP/GIF/...), eigenes Widget."""
import io
import os
import re
import threading
import time
from collections import OrderedDict

import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Graphene", "1.0")
gi.require_version("Gsk", "4.0")
from gi.repository import Gdk, GLib, Graphene, Gsk, Gtk  # noqa: E402

from . import config  # noqa: E402
from .api import client  # noqa: E402
from .i18n import _  # noqa: E402
from .util import bg  # noqa: E402

try:
    from PIL import Image, ImageOps
except ImportError:  # pragma: no cover
    Image = None

_MEM = OrderedDict()            # key -> Decoded
_MEM_LIMIT = 300 * 1024 * 1024  # Speicher-Zwischenspeicher insgesamt
_ENTRY_LIMIT = 64 * 1024 * 1024  # hoechstens so viel je Bild (Animationen: danach keine weiteren Einzelbilder)
_DISK_LIMIT = 400 * 1024 * 1024  # Platten-Zwischenspeicher: aelteste Dateien gehen zuerst
_DISK_MAX_AGE = 60 * 86400
_mem_size = 0
_inflight = {}
_disk_lock = threading.Lock()


class Decoded:
    def __init__(self, textures, durations, w, h, cost):
        self.textures = textures
        self.durations = durations
        self.w, self.h = w, h
        self.cost = cost

    @property
    def ratio(self):
        return self.w / self.h if self.h else 1.0

    @property
    def animated(self):
        return len(self.textures) > 1


def sized(path, w):
    if w and path.startswith("/files/") and "?" not in path:
        return f"{path}?w={w}"
    return path


def _disk(path):
    return os.path.join(config.CACHE_DIR, "files", re.sub(r"[^A-Za-z0-9._-]", "_", path)[-180:])


def fetch_bytes(path):
    """Hintergrund-Thread: Bytes aus dem Platten-Zwischenspeicher oder vom Server"""
    p = _disk(path)
    try:
        with open(p, "rb") as f:
            return f.read()
    except OSError:
        pass
    data = client.fetch(path.lstrip("/"))
    try:
        config.atomic_write(p, data)
    except OSError:
        pass
    return data


def prune_disk_cache(limit=None, max_age=None):
    """Platten-Zwischenspeicher begrenzen: zu alte Dateien weg, dann die am laengsten ungenutzten bis unter die Grenze"""
    limit = _DISK_LIMIT if limit is None else limit
    max_age = _DISK_MAX_AGE if max_age is None else max_age
    d = os.path.join(config.CACHE_DIR, "files")
    with _disk_lock:
        try:
            entries = [e for e in os.scandir(d) if e.is_file(follow_symlinks=False)]
        except OSError:
            return 0
        now = time.time()
        files = []
        for e in entries:
            try:
                st = e.stat(follow_symlinks=False)
            except OSError:
                continue
            files.append((max(st.st_atime, st.st_mtime), st.st_size, e.path))
        files.sort()
        total = sum(f[1] for f in files)
        removed = 0
        for used, size, path in files:
            if total <= limit and now - used <= max_age:
                continue
            try:
                os.remove(path)
                total -= size
                removed += 1
            except OSError:
                pass
        return removed


def _open(data):
    if Image is None:
        raise RuntimeError(_("python3-pillow is missing"))
    return Image.open(io.BytesIO(data))


def _frame(im, i, lim):  # -> (rohe Pixel, Breite, Hoehe, Dauer in ms)
    im.seek(i)
    fr = im.convert("RGBA")
    if max(fr.size) > lim:
        fr.thumbnail((lim, lim))
    return (fr.tobytes(), fr.width, fr.height, max(int(im.info.get("duration", 100) or 100), 20))


def _decode_static(im, max_px):
    try:
        im = ImageOps.exif_transpose(im)
    except Exception:  # noqa: BLE001
        pass
    im.draft("RGB", (max_px, max_px))
    fr = im.convert("RGBA")
    if max(fr.size) > max_px:
        fr.thumbnail((max_px, max_px))
    return [(fr.tobytes(), fr.width, fr.height, 0)]


ANIM_PX = 720                    # animierte Bilder kleiner halten: 50 Bilder x 1400 px waeren ~220 MB


def _make(frames):
    tex, durs, cost = [], [], 0
    for raw, w, h, d in frames:
        t = Gdk.MemoryTexture.new(w, h, Gdk.MemoryFormat.R8G8B8A8, GLib.Bytes.new(raw), w * 4)
        tex.append(t)
        durs.append(d)
        cost += len(raw)
    w, h = frames[0][1], frames[0][2]
    return Decoded(tex, durs, w, h, cost)


def _remember(key, dec):
    global _mem_size
    if dec.cost > _ENTRY_LIMIT:
        return                                            # zu gross fuer den Zwischenspeicher (wird nur angezeigt)
    old = _MEM.pop(key, None)
    if old is not None:
        _mem_size -= old.cost
    _MEM[key] = dec
    _mem_size += dec.cost
    while _mem_size > _MEM_LIMIT and _MEM:
        _, old = _MEM.popitem(last=False)
        _mem_size -= old.cost


def load(path, cb, w=None, max_px=1600):
    """cb(Decoded|None) im Hauptthread - bei Animationen zweimal: erst das erste Bild, dann die ganze Animation.
    Gleiche Anfragen werden zusammengelegt."""
    key = sized(path, w)
    if key in _MEM:
        _MEM.move_to_end(key)
        cb(_MEM[key])
        return
    if key in _inflight:
        _inflight[key]["cbs"].append(cb)
        if _inflight[key]["first"] is not None:
            cb(_inflight[key]["first"])
        return
    st = {"cbs": [cb], "first": None}
    _inflight[key] = st

    def first(frames):                                    # Hauptthread: erstes Bild sofort zeigen
        st["first"] = _make(frames)
        for c in list(st["cbs"]):
            c(st["first"])
        return False

    def work():
        im = _open(fetch_bytes(key))
        n = getattr(im, "n_frames", 1)
        if n <= 1:
            return _decode_static(im, max_px)
        lim = min(max_px, ANIM_PX)
        frames = [_frame(im, 0, lim)]
        GLib.idle_add(first, list(frames))
        cost = len(frames[0][0])
        for i in range(1, min(n, 300)):
            fr = _frame(im, i, lim)
            cost += len(fr[0])
            if cost > _ENTRY_LIMIT:                       # lange/grosse Animation: nach der Grenze aufhoeren
                break
            frames.append(fr)
        return frames

    def fin(frames):
        dec = _make(frames) if frames else None
        if dec:
            _remember(key, dec)
        for c in _inflight.pop(key, {"cbs": []})["cbs"]:
            c(dec)

    def err(e):
        for c in _inflight.pop(key, {"cbs": []})["cbs"]:
            c(None)
    bg(work, done=fin, error=err, media=True)


def load_local(data, cb, max_px=1600):
    bg(lambda: _decode_static(_open(data), max_px), done=lambda fr: cb(_make(fr)), error=lambda e: cb(None), media=True)


class ImageView(Gtk.Widget):
    """Bild (auch animiert). mode 'fit' (ganzes Bild, Hoehe aus Breite) oder 'cover' (Flaeche fuellen, valign 0..100)."""
    __gtype_name__ = "HKImageView"

    def __init__(self, path=None, mode="fit", w=None, max_height=900, valign=50.0, radius=0, natural=None):
        super().__init__()
        self.mode, self.wparam, self.max_height, self.valign = mode, w, max_height, valign
        self.natural = natural
        self.dec = None
        self.frame = 0
        self._timer = 0
        self.path = None
        self.set_overflow(Gtk.Overflow.HIDDEN)
        if radius:
            self.add_css_class(f"hk-r{radius}")
        if path:
            self.set_path(path)

    def set_path(self, path):
        if path == self.path:
            return
        self.path = path
        self.dec = None
        self._stop()
        self.queue_resize()
        if not path:
            return
        mypath = path
        load(path, lambda d: self._loaded(mypath, d), w=self.wparam)

    def set_decoded(self, dec):
        self.path = None
        self._loaded(None, dec)

    def _loaded(self, path, dec):
        if path is not None and path != self.path:
            return
        if dec is None and self.dec is not None:
            return                                        # erstes Bild behalten, falls der Rest scheitert
        self._stop()
        self.dec, self.frame = dec, 0
        self.queue_resize()
        self.queue_draw()
        if self.get_mapped():
            self._start()

    def set_valign_pct(self, v):
        self.valign = v
        self.queue_draw()

    # --- Animation -----------------------------------------------------------------------
    def _start(self):
        if self.dec and self.dec.animated and not self._timer:
            self._timer = GLib.timeout_add(self.dec.durations[self.frame], self._tick)

    def _stop(self):
        if self._timer:
            GLib.source_remove(self._timer)
            self._timer = 0

    def _tick(self):
        self._timer = 0
        if not self.dec or not self.dec.animated:
            return False
        self.frame = (self.frame + 1) % len(self.dec.textures)
        self.queue_draw()
        self._timer = GLib.timeout_add(self.dec.durations[self.frame], self._tick)
        return False

    def do_map(self):
        Gtk.Widget.do_map(self)
        self._start()

    def do_unmap(self):
        self._stop()
        Gtk.Widget.do_unmap(self)

    # --- Groesse + Zeichnen ------------------------------------------------------------------
    def do_get_request_mode(self):
        return Gtk.SizeRequestMode.HEIGHT_FOR_WIDTH if self.mode == "fit" else Gtk.SizeRequestMode.CONSTANT_SIZE

    def do_measure(self, orientation, for_size):
        if self.mode != "fit":
            return 0, 0, -1, -1
        ratio = self.dec.ratio if self.dec else 16 / 9
        natw = self.natural or (self.dec.w if self.dec else 400)
        if orientation == Gtk.Orientation.HORIZONTAL:
            return 0, int(natw), -1, -1
        w = for_size if for_size > 0 else natw
        h = int(min(self.max_height, w / ratio)) if ratio else 0
        if not self.dec:
            h = min(h, 160)
        return h, h, -1, -1

    def do_snapshot(self, snap):
        W, H = self.get_width(), self.get_height()
        if W <= 0 or H <= 0:
            return
        if not self.dec:
            c = Gdk.RGBA()
            c.parse("rgba(127,127,127,0.12)")
            snap.append_color(c, Graphene.Rect().init(0, 0, W, H))
            return
        tex = self.dec.textures[self.frame % len(self.dec.textures)]
        r = self.dec.ratio
        if self.mode == "cover":
            if W / H > r:
                sw, sh = W, W / r
            else:
                sw, sh = H * r, H
            x = (W - sw) / 2
            y = -(sh - H) * max(0.0, min(100.0, self.valign)) / 100.0
        else:
            sw, sh = W, W / r
            if sh > H:
                sh, sw = H, H * r
            x, y = (W - sw) / 2 if self.get_halign() == Gtk.Align.CENTER else 0, (H - sh) / 2
        snap.push_clip(Graphene.Rect().init(0, 0, W, H))
        snap.append_scaled_texture(tex, Gsk.ScalingFilter.TRILINEAR, Graphene.Rect().init(x, y, sw, sh))
        snap.pop()


def page_icon(icon, size=22, fallback="📄"):
    """Seiten-Symbol: Emoji oder eigenes Bild (/files/...)"""
    if icon and icon.startswith("/files/"):
        v = ImageView(icon, mode="cover", w=160 if size <= 40 else 320, radius=4 if size < 40 else 10)
        v.set_size_request(size, size)
        v.set_valign(Gtk.Align.CENTER)
        v.set_halign(Gtk.Align.CENTER)
        return v
    lb = Gtk.Label(label=icon or fallback)
    if size > 30:
        lb.add_css_class("hk-icon-big")
    lb.set_size_request(size, size)
    return lb
