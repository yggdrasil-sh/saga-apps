"""v2.1: Bilder/Dateien aus der Zwischenablage (Strg+V) im Chat (Anhang mit Vorschau) und auf Seiten (Bild-/Dateiblock),
Text-Einfuegen unveraendert; Immich im Chat (Proxy ?size=original|preview, verkleinern, E2E wie jeder Anhang - nie /import)."""
import io
import json
import os
import tempfile

from gi.repository import Gdk, Gio, GLib, GObject, Gtk

from hk_helpers import idle, pump
from hlidskjalf import clipimg


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _png(w=320, h=200, noise=False):
    from PIL import Image
    if noise:
        im = Image.frombytes("RGB", (w, h), os.urandom(w * h * 3))
    else:
        im = Image.new("RGB", (w, h), (120, 170, 110))
    out = io.BytesIO()
    im.save(out, "PNG")
    return out.getvalue()


def _set_tex(cb, data):
    """wie ein Bildschirmfoto-Programm: Inhalt als Gdk.Texture (lokal sonst GdkMemoryTexture/GdkGLTexture)"""
    tex = Gdk.Texture.new_from_bytes(GLib.Bytes.new(data))
    cb.set_content(Gdk.ContentProvider.new_for_value(GObject.Value(Gdk.Texture, tex)))


def _formats(gtypes=(), mimes=()):
    b = Gdk.ContentFormatsBuilder.new()
    for g in gtypes:
        b.add_gtype(g)
    for m in mimes:
        b.add_mime_type(m)
    return b.to_formats()


def _set_files(cb, paths):
    fl = Gdk.FileList.new_from_list([Gio.File.new_for_path(p) for p in paths])
    v = GObject.Value(Gdk.FileList, fl)
    cb.set_content(Gdk.ContentProvider.new_for_value(v))


def _tmpfiles():
    d = tempfile.mkdtemp(prefix="hk-clip-")
    p1, p2 = os.path.join(d, "foto.png"), os.path.join(d, "notiz.txt")
    with open(p1, "wb") as f:
        f.write(_png(64, 48))
    with open(p2, "w") as f:
        f.write("Hallo Datei\n")
    return p1, p2


# --- Bausteine ------------------------------------------------------------------------------------------------------
def test_clip_kind_and_shrink():
    assert clipimg.clip_kind(_formats(mimes=["text/plain;charset=utf-8"])) is None
    assert clipimg.clip_kind(_formats([Gdk.Texture], ["image/png"])) == "image"
    assert clipimg.clip_kind(_formats([Gdk.MemoryTexture])) == "image"                 # Bild aus der eigenen App
    assert clipimg.clip_kind(_formats(mimes=["image/png"])) == "image"                 # fremdes Programm (nur MIME)
    assert clipimg.clip_kind(_formats(mimes=["text/uri-list", "text/plain"])) == "files"   # Dateimanager
    assert clipimg.clip_kind(_formats([Gdk.Texture], ["image/png", "text/plain"])) is None      # Office: Text gewinnt
    assert clipimg.clip_kind(_formats([Gdk.FileList], ["text/uri-list"])) == "files"
    assert clipimg.clip_kind(_formats([Gdk.FileList], ["text/uri-list", "text/x-moz-url"])) is None  # Browser-Link
    small = _png(40, 30)
    assert clipimg.shrink(small, 10 ** 6) == (small, "image/png")
    big = _png(1600, 1200, noise=True)
    assert len(big) > 3 * 10 ** 6
    data, mime = clipimg.shrink(big, 400_000)
    assert mime == "image/jpeg" and len(data) <= 400_000 and clipimg.sniff_mime(data) == "image/jpeg"
    from PIL import Image
    bmp = io.BytesIO()
    Image.new("RGBA", (50, 40), (1, 2, 3, 128)).save(bmp, "BMP")
    data, mime = clipimg.shrink(bmp.getvalue(), 10 ** 6)                      # unbekanntes Format -> JPEG
    assert mime == "image/jpeg" and clipimg.with_ext("IMG_0001.HEIC", mime) == "IMG_0001.jpg"


def test_immich_for_chat_sizes_and_fallback(srv):
    import mockv2
    orig, prev = _png(900, 600), _png(300, 200)
    mockv2.immich_add(srv, "a1", "strand.png", "image/png", orig, prev)
    mockv2.immich_add(srv, "a2", "IMG_7.HEIC", "image/heic", None, prev)
    mockv2.immich_add(srv, "a3", "gross.png", "image/png", _png(1400, 1000, noise=True), prev)
    srv.log.clear()
    name, mime, data = clipimg.immich_for_chat({"id": "a1", "name": "strand.png", "mime": "image/png"}, 25 * 1048576)
    assert (name, mime, data) == ("strand.png", "image/png", orig)
    name, mime, data = clipimg.immich_for_chat({"id": "a2", "name": "IMG_7.HEIC", "mime": "image/heic"}, 25 * 1048576)
    assert data == prev and name == "IMG_7.png"                                  # HEIC: gleich die grosse Vorschau
    srv.immich["a2"]["mime"] = "image/jpeg"                                       # Original 415 -> Vorschau
    _n, _m, data = clipimg.immich_for_chat({"id": "a2", "name": "x.jpg", "mime": "image/jpeg"}, 25 * 1048576)
    assert data == prev
    _n, mime, data = clipimg.immich_for_chat({"id": "a3", "name": "gross.png", "mime": "image/png"}, 300_000)
    assert mime == "image/jpeg" and len(data) <= 300_000
    urls = [u for m, u, b, c in srv.log]
    assert any("size=original" in u for u in urls) and any("size=preview" in u for u in urls)
    assert not any("immich/import" in u for u in urls)


# --- Chat ---------------------------------------------------------------------------------------------------------------
def _conv(win, srv):
    from hlidskjalf.chat import chat
    from test_chat import _ready
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    chat.load_chats()
    pump(lambda: chat.chat(cid), 4)
    win.show_route(("chat", cid))
    conv = win.current.conv
    pump(lambda: conv.loaded, 5)
    return cid, conv


def test_chat_paste_image_files_text(win, srv):
    from hlidskjalf import images
    cid, conv = _conv(win, srv)
    cb = conv.entry.get_clipboard()
    # Text: nicht abgefangen (das Eingabefeld fuegt wie gewohnt ein)
    cb.set("nur Text")
    assert conv.paste(cb) is False and conv.files == []
    # Bild (Textur, z. B. Bildschirmfoto): Anhang mit Vorschau
    _set_tex(cb, _png(200, 120))
    assert conv.paste(cb) is True
    pump(lambda: len(conv.files) == 1, 4)
    name, mime, data = conv.files[0]
    assert mime == "image/png" and data.startswith(b"\x89PNG") and name.endswith(".png")
    pump(None, 0.3)
    assert conv.files_bar.get_visible() and any(isinstance(w, images.ImageView) for w in _walk(conv.files_bar))
    # Dateien aus dem Dateimanager: Bild + Textdatei
    p1, p2 = _tmpfiles()
    _set_files(cb, [p1, p2])
    assert conv.paste(cb) is True
    pump(lambda: len(conv.files) == 3, 4)
    assert [f[0] for f in conv.files[1:]] == ["foto.png", "notiz.txt"] and conv.files[2][1] == "text/plain"
    # nur entfernte Adressen statt Dateien: kein Anhang (zurueck zum normalen Einfuegen)
    fl = Gdk.FileList.new_from_list([Gio.File.new_for_uri("https://example.org/bild.png")])
    cb.set_content(Gdk.ContentProvider.new_for_value(GObject.Value(Gdk.FileList, fl)))
    assert conv.paste(cb) is True
    pump(None, 0.5)
    assert len(conv.files) == 3
    # Senden: Anhaenge nur verschluesselt beim Server
    conv.send()
    pump(lambda: any(m["sender"] == "test" for m in srv.chats[cid]["msgs"]), 6)
    idle()
    blobs = [v[2] for v in srv.cfiles.values()]
    assert len(blobs) == 5 and not any(data in b or b"Hallo Datei" in b for b in blobs)      # 3 Anhaenge + 2 Vorschaubilder (2.1-c)
    assert conv.files == []


def test_chat_attach_from_immich(win, srv):
    import mockv2
    from hlidskjalf.dialogs import ImmichPicker
    cid, conv = _conv(win, srv)
    # nicht verbunden: kein "Aus Immich"
    got = []
    clipimg.forget_immich()
    clipimg.immich_ready(got.append)
    pump(lambda: got, 3)
    assert got == [False]
    orig = _png(640, 400)
    mockv2.immich_add(srv, "a1", "strand.png", "image/png", orig, _png(200, 120))
    clipimg.forget_immich()
    conv._attach_menu()
    pump(lambda: any(isinstance(w, Gtk.Label) and w.get_label() == "From Immich …" for w in _walk(conv.att_btn)), 4)
    pop = next(w for w in _walk(conv.att_btn) if isinstance(w, Gtk.Popover))
    pop.popdown()
    # Auswahl wie auf Seiten, aber Laden ueber den Proxy statt Import
    conv._pick_immich()
    pump(lambda: isinstance(win.get_visible_dialog(), ImmichPicker), 3)
    d = win.get_visible_dialog()
    pump(lambda: getattr(d, "grid", None) is not None and d.grid.get_first_child() is not None, 5)
    assert d.grid.get_first_child() is not None
    d._choose({"id": "a1", "name": "strand.png", "mime": "image/png"})
    pump(lambda: len(conv.files) == 1, 5)
    assert conv.files[0] == ("strand.png", "image/png", orig)
    assert not any("immich/import" in u for m, u, b, c in srv.log)
    conv.send()
    pump(lambda: any(m["sender"] == "test" for m in srv.chats[cid]["msgs"]), 6)
    idle()
    blobs = [v[2] for v in srv.cfiles.values()]                       # Original + Vorschaubild (2.1-c)
    assert len(blobs) == 2 and not any(orig in b for b in blobs) and len(blobs[0]) == len(orig) + 40


# --- Seiten ---------------------------------------------------------------------------------------------------------
def _types(srv, nid=1):
    return [b["type"] for b in json.loads(srv.notes[nid]["body"])["blocks"]]


def test_page_paste_image_and_files(win, srv):
    from test_ui import _open_page
    v = _open_page(win, 3)
    v.editor.set_focus(v.doc.root[0]["id"])
    cb = v.get_clipboard()
    cb.set("Text bleibt Text")
    assert v.paste(cb) is False
    n0 = len(_types(srv, 3))
    _set_tex(cb, _png(120, 80))
    assert v.paste(cb) is True
    pump(lambda: "image" in [b.get("type") for b in v.doc.root], 5)
    idle()
    pump(lambda: "image" in _types(srv, 3), 5)
    assert _types(srv, 3).count("image") == 1 and len(_types(srv, 3)) == n0 + 1
    p1, p2 = _tmpfiles()
    _set_files(cb, [p1, p2])
    assert v.paste(cb) is True
    pump(lambda: _types(srv, 3).count("image") == 2 and "attaches" in _types(srv, 3), 6)
    idle()
    t = _types(srv, 3)
    assert t.count("image") == 2 and t.count("attaches") == 1
    # schreibgeschuetzt (gesperrt): nichts abfangen
    v.editor.locked = True
    _set_tex(cb, _png(50, 50))
    assert v._page_key(None, Gdk.KEY_v, 0, Gdk.ModifierType.CONTROL_MASK) is False
    v.editor.locked = False
