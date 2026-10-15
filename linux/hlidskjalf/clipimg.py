"""Bilder und Dateien aus der Zwischenablage (Strg+V) und Immich-Bilder fuer Chat-Anhaenge.

Zwischenablage: Dateilisten (Dateimanager, text/uri-list), Bilder (image/png, Texturen). Reiner Text bleibt beim normalen
Einfuegen des Eingabefelds - hier wird nur abgefangen, was ein Bild oder eine Datei ist.
Immich im Chat: das Bild kommt ueber den Immich-Proxy des Servers (/api/immich/thumb/<id>?size=...), wird hier ggf. verkleinert
und danach wie jeder Chat-Anhang Ende-zu-Ende verschluesselt hochgeladen (nie /api/immich/import: das legt eine
unverschluesselte Datei an)."""
import io
import os
import time

from gi.repository import Gdk, GLib, GObject

from .api import APIError, client
from .i18n import _

IMAGE_EXT = ("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "avif", "bmp", "tif", "tiff")
_TEXT = ("text/plain", "text/plain;charset=utf-8", "UTF8_STRING", "STRING", "TEXT")
_WEB = ("text/html", "text/x-moz-url", "_NETSCAPE_URL")   # Browser: Link/Bild kopiert -> Text bzw. Bild, keine Datei


def sniff_mime(data):
    """Bildformat am Dateianfang -> MIME oder None"""
    h = bytes(data[:12])
    if h.startswith(b"\x89PNG"):
        return "image/png"
    if h.startswith(b"\xff\xd8"):
        return "image/jpeg"
    if h.startswith(b"GIF8"):
        return "image/gif"
    if h[:4] == b"RIFF" and h[8:12] == b"WEBP":
        return "image/webp"
    return None


EXT = {"image/png": "png", "image/jpeg": "jpg", "image/gif": "gif", "image/webp": "webp"}


def with_ext(name, mime):
    """Dateiname passend zum (evtl. umgewandelten) Format"""
    base = os.path.splitext(os.path.basename(name or ""))[0].strip() or "image"
    return base + "." + EXT.get(mime, "jpg")


def shrink(data, limit):
    """Bild so verkleinern/als JPEG speichern, dass es hoechstens limit Bytes hat. -> (bytes, mime)
    GIF und kleine Bilder bleiben unveraendert; unbekannte Formate (HEIC & Co.) werden JPEG."""
    mime = sniff_mime(data)
    if mime and len(data) <= limit:
        return bytes(data), mime
    from PIL import Image, ImageOps
    im = Image.open(io.BytesIO(data))
    try:
        im = ImageOps.exif_transpose(im)
    except Exception:  # noqa: BLE001 - kaputte EXIF-Daten: Bild trotzdem nehmen
        pass
    if im.mode not in ("RGB", "L"):
        bgc = Image.new("RGB", im.size, (255, 255, 255))
        im = im.convert("RGBA")
        bgc.paste(im, mask=im.split()[-1])
        im = bgc
    edge = max(im.size)
    for q, cap in ((88, 4096), (85, 2560), (82, 1920), (80, 1440), (75, 1024), (70, 720)):
        cur = im
        if edge > cap:
            f = cap / edge
            cur = im.resize((max(1, int(im.width * f)), max(1, int(im.height * f))), Image.LANCZOS)
        out = io.BytesIO()
        cur.save(out, "JPEG", quality=q, optimize=True)
        if out.tell() <= limit:
            return out.getvalue(), "image/jpeg"
    raise APIError(413, _("Image too large."))


def pasted_name(mime="image/png"):
    return time.strftime(_("Image") + "-%Y%m%d-%H%M%S.") + EXT.get(mime, "png")


# --- Zwischenablage ---------------------------------------------------------------------------------------------------
def _has_type(formats, base):
    return any(GObject.type_is_a(t, base) for t in (formats.get_gtypes() or []))


def clip_kind(formats):
    """'files' (Dateien aus dem Dateimanager), 'image' (Bilddaten) oder None (Text: normal einfuegen).
    Fremde Programme liefern MIME-Typen (GTK ergaenzt die lesbaren Typen), die eigene App Typen wie GdkMemoryTexture."""
    mimes = formats.get_mime_types() or []
    if (_has_type(formats, Gdk.FileList) or "text/uri-list" in mimes) and not any(m in mimes for m in _WEB):
        return "files"
    if (_has_type(formats, Gdk.Texture) or any(m.startswith("image/") for m in mimes)) and not any(m in mimes for m in _TEXT):
        return "image"
    return None


def is_ctrl_v(kv, state):
    return kv in (Gdk.KEY_v, Gdk.KEY_V) and bool(state & Gdk.ModifierType.CONTROL_MASK) \
        and not state & (Gdk.ModifierType.ALT_MASK | Gdk.ModifierType.SHIFT_MASK)


def read_paste(clipboard, on_files, on_image, fallback):
    """Zwischenablage lesen, wenn sie Dateien oder ein Bild enthaelt. -> True, wenn abgefangen (dann kommt genau einer der
    Rueckrufe: on_files([Gio.File]), on_image(png_bytes) oder fallback() - z. B. nur entfernte Adressen statt Dateien)."""
    kind = clip_kind(clipboard.get_formats())
    if kind == "files":
        def got_files(c, res):
            try:
                v = c.read_value_finish(res)
                files = [f for f in (v.get_files() if v is not None else []) if f.get_path()]
            except GLib.Error:
                files = []
            on_files(files) if files else fallback()
        clipboard.read_value_async(Gdk.FileList, GLib.PRIORITY_DEFAULT, None, got_files)
        return True
    if kind == "image":
        def got_tex(c, res):
            try:
                tex = c.read_texture_finish(res)
            except GLib.Error:
                tex = None
            on_image(tex.save_to_png_bytes().get_data()) if tex is not None else fallback()
        clipboard.read_texture_async(None, got_tex)
        return True
    return False


def file_is_image(f):
    return (f.get_basename() or "").lower().rsplit(".", 1)[-1] in IMAGE_EXT


# --- Immich -> Chat ---------------------------------------------------------------------------------------------------
_immich = {"ok": None, "t": 0.0}


def immich_ready(cb):
    """cb(True), wenn Immich fuer dieses Konto verbunden ist (gleiche Pruefung wie beim Bild-Einfuegen auf Seiten).
    Ergebnis eine Minute zwischengespeichert."""
    from .util import bg
    if _immich["ok"] is not None and time.monotonic() - _immich["t"] < 60:
        cb(_immich["ok"])
        return

    def fin(j):
        ok = bool(isinstance(j, dict) and j.get("connected") and j.get("available") is not False)
        _immich.update(ok=ok, t=time.monotonic())
        cb(ok)
    bg(client.get, "api/settings/immich", done=fin, error=lambda e: cb(False))


def forget_immich():
    _immich.update(ok=None, t=0.0)


def immich_for_chat(item, limit):
    """Hintergrund-Thread: Immich-Bild in moeglichst voller Groesse laden -> (Name, MIME, Bytes) <= limit.
    Original nur fuer Formate, die jeder Client zeigt; sonst bzw. bei 404/413/415 die grosse Vorschau."""
    aid = str(item.get("id") or "")
    if not aid:
        raise APIError(404)
    path = "api/immich/thumb/" + GLib.Uri.escape_string(aid, None, True)
    sizes = (["original"] if (item.get("mime") or "") in EXT else []) + ["preview"]
    data = None
    for i, sz in enumerate(sizes):
        try:
            data = client.fetch(path + "?size=" + sz, timeout=120)
            break
        except APIError as e:
            if i + 1 < len(sizes) and e.code in (404, 413, 415):
                continue
            raise
    data, mime = shrink(data, limit)
    return with_ext(item.get("name") or "immich", mime), mime, data
