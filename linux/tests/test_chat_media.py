"""2.5 Medien im Chat, Paket 1 (Linux): Bildansicht mit Zoom/Verschieben/Blaettern ("n / m"), Antwort-Zitat und
Antwortleiste mit Vorschaubild ("Photo"/"Video"/Dateiname), "Speichern unter …" und "Bild kopieren" (Gdk.Texture in der
Zwischenablage), Weiterleiten aus dem Nachrichtenmenue - auch saga-file/v2 und Sprachnachrichten."""
import os

from gi.repository import Gdk, GLib, Gtk

from hk_helpers import idle, pump
from test_v21c import _conv, _img, _peer_image, _walk


def _menu(monkeypatch, conv, m):
    got = []

    class _Pop:
        def __getattr__(self, _n):
            return lambda *a, **k: None

        def get_child(self):
            raise AttributeError

    monkeypatch.setattr("hlidskjalf.chatview.menu_popover", lambda parent, ents: got.append(ents) or _Pop())
    monkeypatch.setattr("hlidskjalf.chatx3.point_at", lambda *a, **k: None)
    monkeypatch.setattr("hlidskjalf.chatview.can_react", lambda *a, **k: False)
    conv._msg_menu(conv, m)
    return {e[0]: e for e in got[-1] if e}


def _clip_texture(widget):
    """Textur aus der Zwischenablage lesen (oder None)"""
    cb = widget.get_clipboard()
    out = {}

    def done(c, res):
        try:
            out["t"] = c.read_texture_finish(res)
        except GLib.Error:
            out["t"] = None
    cb.read_texture_async(None, done)
    pump(lambda: "t" in out, 4)
    return out.get("t")


def _images_chat(win, srv, n=3):
    from hlidskjalf.chat import chat
    from test_chat import _ready
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    fs = [_peer_image(srv, cid, _img(1400, 1000, fmt="JPEG", color=(40 * i, 90, 160)), f"bild{i}", name=f"bild{i}.jpg")
          for i in range(n)]
    srv.peer_send(cid, "anna", "nur Text")
    win.set_default_size(900, 700)
    cid, conv = _conv(win, srv, cid)
    pump(lambda: len((chat.cache.get(cid) or {}).get("msgs") or []) == n + 1, 4)
    return chat, cid, conv, fs


# --- Bildansicht ---------------------------------------------------------------------------------------------------------
def test_viewer_zoom_pan_and_browse(win, srv):
    from hlidskjalf.chatview import ImageViewer, chat_images
    chat, cid, conv, fs = _images_chat(win, srv)
    assert [f["id"] for f in chat_images(conv)] == ["bild0", "bild1", "bild2"]          # Textnachricht zaehlt nicht
    d = ImageViewer(conv, fs[1])
    d.present(win)
    pump(lambda: d.stack.get_visible_child_name() == "img", 6)
    pump(None, 0.3)
    idle()
    v = d.view
    assert d.wtitle.get_subtitle() == "2 / 3" and d.wtitle.get_title() == "bild1.jpg" and v.dec.w == 1400
    assert d.prev_btn.get_sensitive() and d.next_btn.get_sensitive() and d.copy_btn.get_sensitive()
    # eingepasst: kleiner als 100 %, nicht verschiebbar
    W, H = v.get_width(), v.get_height()
    assert W > 0 and H > 0 and v.fit and v.cur_scale() == min(1.0, W / 1400, H / 1000)
    assert not v.pan(50, 50) and not d.zout.get_sensitive()
    # Zoomen: um einen Punkt (der Bildpunkt darunter bleibt stehen), 100 %, Verschieben mit Grenzen
    fs0 = v.fit_scale()
    at = (W * 0.25, H * 0.25)

    def img_pt(p):
        s = v.cur_scale()
        ox, oy = (0, 0) if v.fit else (v.ox, v.oy)
        return ((p[0] - (W - 1400 * s) / 2 - ox) / s, (p[1] - (H - 1000 * s) / 2 - oy) / s)
    before = img_pt(at)
    v.zoom_in(at)
    assert not v.fit and abs(v.cur_scale() - fs0 * v.STEP) < 1e-6
    after = img_pt(at)
    assert abs(before[0] - after[0]) < 0.5 and abs(before[1] - after[1]) < 0.5
    assert d.zout.get_sensitive() and d.zpct.get_label() == f"{round(fs0 * v.STEP * 100)} %"
    v.zoom_to(1.0)
    assert d.zpct.get_label() == "100 %"
    assert v.pan(-100000, -100000)
    assert v.ox == -(1400 - W) / 2 and v.oy == -(1000 - H) / 2                      # nicht ueber den Rand hinaus
    v.zoom_to(50)
    assert v.cur_scale() == v.MAX and not d.zin.get_sensitive()
    v.zoom_out()
    assert v.cur_scale() < v.MAX
    for _i in range(40):                                                            # nie kleiner als eingepasst
        v.zoom_out()
    assert v.fit and v.ox == 0 and v.oy == 0
    # Doppelklick: eingepasst <-> 100 %
    v.toggle((W / 2, H / 2))
    assert not v.fit and v.cur_scale() == 1.0
    v.toggle()
    assert v.fit
    # Tasten: +, -, 0, 1
    d._key(None, Gdk.KEY_plus, 0, 0)
    assert not v.fit
    d._key(None, Gdk.KEY_0, 0, 0)
    assert v.fit
    d._key(None, Gdk.KEY_1, 0, 0)
    assert v.cur_scale() == 1.0
    # Blaettern: Pfeiltasten und Knoepfe, Grenzen, neues Bild wieder eingepasst
    assert d._key(None, Gdk.KEY_Right, 0, 0)
    assert d.wtitle.get_subtitle() == "3 / 3" and d.f["id"] == "bild2" and not d.next_btn.get_sensitive()
    pump(lambda: d.stack.get_visible_child_name() == "img" and d.data is not None, 6)
    assert v.fit and not d.step(1)
    d.prev_btn.emit("clicked")
    d.prev_btn.emit("clicked")
    assert d.wtitle.get_subtitle() == "1 / 3" and not d.prev_btn.get_sensitive()
    # spaetes Ergebnis eines vorigen Bildes wird verworfen
    g = d.gen
    d._data(b"kein Bild", g - 1)
    d._fail("alt", g - 1)
    pump(lambda: d.stack.get_visible_child_name() == "img", 6)
    assert d.f["id"] == "bild0" and d.stack.get_visible_child_name() == "img"
    d.force_close()
    # Bild, das nicht (mehr) im geladenen Verlauf ist (Medienuebersicht): nur dieses, ohne Blaettern
    d = ImageViewer(conv, dict(fs[0], id="anderes"))
    assert len(d.items) == 1 and not d.prev_btn.get_visible() and d.wtitle.get_subtitle() == ""
    d.force_close()


def test_viewer_and_menu_copy_image_save_as(win, srv, monkeypatch):
    from hlidskjalf.chatview import ImageViewer
    chat, cid, conv, fs = _images_chat(win, srv, n=1)
    d = ImageViewer(conv, fs[0])
    d.present(win)
    pump(lambda: d.stack.get_visible_child_name() == "img" and d.copy_btn.get_sensitive(), 6)
    win.get_clipboard().set("vorher")
    d._key(None, Gdk.KEY_c, 0, Gdk.ModifierType.CONTROL_MASK)
    pump(lambda: d.get_clipboard().get_formats().contain_gtype(Gdk.Texture), 5)
    t = _clip_texture(d)
    assert t is not None and (t.get_width(), t.get_height()) == (1400, 1000)       # Original, nicht das Vorschaubild
    saved = []
    monkeypatch.setattr(conv, "_save_file", lambda f, **k: saved.append(f["id"]))
    d.save_btn.emit("clicked")
    d._key(None, Gdk.KEY_s, 0, Gdk.ModifierType.CONTROL_MASK)
    assert saved == ["bild0", "bild0"] and d.save_btn.get_tooltip_text() == "Save as …"
    d.force_close()
    # Nachrichtenmenue der Bildnachricht
    m = next(x for x in chat.cache[cid]["msgs"] if x.get("files"))
    ents = _menu(monkeypatch, conv, m)
    assert "Copy image" in ents and "Save as …" in ents and "Forward …" in ents
    win.get_clipboard().set("vorher")
    pump(lambda: not win.get_clipboard().get_formats().contain_gtype(Gdk.Texture), 3)
    ents["Copy image"][1]()
    pump(lambda: win.get_clipboard().get_formats().contain_gtype(Gdk.Texture), 5)
    t = _clip_texture(win)
    assert t is not None and t.get_width() == 1400
    ents["Save as …"][1]()
    assert saved[-1] == "bild0"
    # Textnachricht: weder kopieren noch speichern; Datei (kein Bild): nur Speichern unter
    tm = next(x for x in chat.cache[cid]["msgs"] if not x.get("files"))
    ents = _menu(monkeypatch, conv, tm)
    assert "Copy image" not in ents and "Save as …" not in ents
    ents = _menu(monkeypatch, conv, dict(m, files=[dict(m["files"][0], m="application/pdf", n="a.pdf")]))
    assert "Copy image" not in ents and "Save as …" in ents


def test_texture_from_bytes_png_jpeg_webp():
    from hlidskjalf import images
    for fmt in ("PNG", "JPEG", "WEBP"):
        t = images.texture_from_bytes(_img(30, 20, fmt=fmt))
        assert (t.get_width(), t.get_height()) == (30, 20), fmt


def test_texture_from_bytes_large_png_is_bounded():
    """Pruefer: ein Bild ueber max_px geht nicht ungebremst an GDK, sondern verkleinert ueber Pillow"""
    from hlidskjalf import images
    t = images.texture_from_bytes(_img(300, 100, fmt="PNG"), max_px=120)
    assert (t.get_width(), t.get_height()) == (120, 40)


def test_forward_voice_not_to_peer_without_v2(monkeypatch):
    """Pruefer: Sprachnachricht (immer v2) nicht an ein Gegenueber ohne saga-file/v2 (big_files False) bzw. Server ohne file2"""
    import pytest
    from hlidskjalf import chatstdview as SV
    from hlidskjalf.chat import ChatError, chat
    copied = []
    monkeypatch.setattr(SV.chatvideo, "copy_v2", lambda *a, **k: copied.append(1) or "neu")
    voice = {"id": "v", "k": "A" * 43, "voice": True, "x": 2, "s": 100, "m": "audio/ogg"}
    monkeypatch.setattr(chat, "limits", {"file2": True})
    monkeypatch.setattr(chat, "chat", lambda cid: {"id": cid, "v": 2, "big_files": False})
    with pytest.raises(ChatError):
        SV.fwd_v2(1, 2, voice)
    monkeypatch.setattr(chat, "chat", lambda cid: {"id": cid, "v": 2})
    monkeypatch.setattr(chat, "limits", {})
    with pytest.raises(ChatError):
        SV.fwd_v2(1, 2, voice)
    assert not copied
    monkeypatch.setattr(chat, "limits", {"file2": True})
    e, ids = SV.fwd_v2(1, 2, voice)
    assert copied and ids == ["neu"] and e["voice"] is True


def test_load_local_animated():
    import io

    from PIL import Image

    from hlidskjalf import images
    frames = [Image.new("RGB", (20, 10), (i * 60, 0, 0)) for i in range(3)]
    b = io.BytesIO()
    frames[0].save(b, "GIF", save_all=True, append_images=frames[1:], duration=50, loop=0)
    got = []
    images.load_local(b.getvalue(), got.append, animated=True)
    pump(lambda: got, 4)
    assert got[0].animated and len(got[0].textures) == 3
    got.clear()
    images.load_local(b.getvalue(), got.append)
    pump(lambda: got, 4)
    assert not got[0].animated


# --- Antwort mit Vorschaubild ------------------------------------------------------------------------------------------------
def test_reply_media_labels():
    from hlidskjalf.chatview import ChatImage, reply_media
    th = {"id": "t", "k": "x" * 43, "m": "image/jpeg", "w": 32, "h": 18}
    w, lab = reply_media(1, {"text": "", "files": [{"id": "v", "m": "video/mp4", "x": 2, "th": th}]})
    assert isinstance(w, ChatImage) and lab == "Video" and (w.bw, w.bh) == (36, 36)
    w, lab = reply_media(1, {"text": "", "files": [{"id": "v", "m": "video/mp4"}]})          # ohne Poster: Symbol
    assert isinstance(w, Gtk.Image) and lab == "Video"
    w, lab = reply_media(1, {"text": "", "files": [{"id": "a", "voice": True, "x": 2, "m": "audio/ogg"}]})
    assert isinstance(w, Gtk.Image) and lab == "Voice message"
    w, lab = reply_media(1, {"text": "", "files": [{"id": "d", "n": "Plan.pdf", "m": "application/pdf"}]})
    assert isinstance(w, Gtk.Image) and lab == "Plan.pdf"
    w, lab = reply_media(1, {"text": "Hallo", "files": [{"id": "d", "n": "Plan.pdf", "m": "application/pdf"}]})
    assert w is None and lab is None                                                    # mit Text: wie bisher
    assert reply_media(1, {"text": "x"}) == (None, None) and reply_media(1, None) == (None, None)
    assert reply_media(1, {"state": "undecryptable", "files": [{"id": "d"}]}) == (None, None)


def test_reply_bar_and_quote_thumbnail(win, srv):
    from hlidskjalf.chatview import ChatImage
    chat, cid, conv, fs = _images_chat(win, srv, n=1)
    m = next(x for x in chat.cache[cid]["msgs"] if x.get("files"))
    conv.set_reply(m)
    kids = [w for w in _walk(conv.reply_thumb) if isinstance(w, ChatImage)]
    assert len(kids) == 1 and kids[0].f["id"] == "bild0" and "Photo" in conv.reply_label.get_label()
    pump(lambda: kids[0].state == "ok", 5)
    assert kids[0].tex is not None and kids[0].tex.get_width() <= 320                 # Vorschaubild, nie das Original
    conv.set_reply(None)
    assert conv.reply_thumb.get_first_child() is None
    tm = next(x for x in chat.cache[cid]["msgs"] if not x.get("files"))
    conv.set_reply(tm)
    assert conv.reply_thumb.get_first_child() is None and "nur Text" in conv.reply_label.get_label()
    # Antwort senden: Zitat in der Blase mit Vorschaubild und "Photo"
    conv.set_reply(m)
    conv.entry.get_buffer().set_text("Schoenes Bild")
    conv.send()
    pump(lambda: any(x.get("mine") and x.get("reply_to") for x in chat.cache[cid]["msgs"]), 6)
    idle()
    pump(lambda: any(w.has_css_class("hk-quote-thumb") for w in _walk(conv) if isinstance(w, ChatImage)), 4)
    q = [w for w in _walk(conv) if isinstance(w, ChatImage) and w.has_css_class("hk-quote-thumb")]
    assert len(q) == 1 and q[0].f["id"] == "bild0"
    quote = q[0].get_parent()
    assert quote.has_css_class("hk-quote")
    labels = [w.get_label() for w in _walk(quote) if isinstance(w, Gtk.Label)]
    assert "Photo" in labels and "Anna Beispiel" in labels


# --- Weiterleiten: auch saga-file/v2 und Sprachnachrichten ------------------------------------------------------------------
def test_forward_single_menu_v2_and_voice(win, srv, tmp_path, monkeypatch):
    from hlidskjalf import chatvideo as V
    from hlidskjalf import filev2 as F
    from hlidskjalf.chat import chat
    from hlidskjalf.chatstdview import ForwardDialog
    from test_video import _chat, _file
    a = _chat(srv)
    srv.chat_peer("bert", "Bert")
    b = srv.new_chat("dm", ["test", "bert"])
    chat.load_chats()
    pump(lambda: chat.chat(b) is not None, 4)
    big = _file(tmp_path, 2 * F.CS + 10, "gross.pdf")
    pending = []
    out, ids = chat.upload_attachments(a, [("gross.pdf", "application/pdf", V.LocalFile(big, os.path.getsize(big)))], pending=pending)
    assert out[0]["x"] == 2
    jpeg = _img(32, 18, fmt="JPEG")                                                  # Vorschaubild/Poster (v1) dazu
    te = chat.upload_file(a, jpeg, "thumb.jpg", "image/jpeg")
    out[0]["th"] = {"id": te["id"], "k": te["k"], "m": "image/jpeg", "w": 32, "h": 18, "s": len(jpeg)}
    chat.send(a, {"v": 1, "type": "text", "text": "Datei", "files": out}, file_ids=ids + [te["id"]])
    for p in pending:
        V.pending_drop(p)
    ogg = open(os.path.join(os.path.dirname(__file__), "voice_web.ogg"), "rb").read()
    chat.send_voice(a, ogg, 2.5, bytes(range(64)))
    win.show_route(("chat", a))
    conv = win.current.conv
    pump(lambda: conv.loaded and len(chat.cache[a]["msgs"]) == 2, 6)
    mf, mv = chat.cache[a]["msgs"]
    assert mv["files"][0]["voice"] and mv["files"][0]["x"] == 2
    # Einzelmenue: "Weiterleiten …" oeffnet den Dialog der Mehrfachauswahl mit genau dieser Nachricht
    shown = []
    monkeypatch.setattr(ForwardDialog, "present", lambda self, w: shown.append(self))
    ents = _menu(monkeypatch, conv, mv)
    ents["Forward …"][1]()
    assert len(shown) == 1 and [x["id"] for x in shown[0].msgs] == [mv["id"]]
    fd = ForwardDialog(conv, [mf, mv])
    fd.go(chat.chat(b))
    pump(lambda: len([x for x in srv.chats[b]["msgs"] if x["kind"] == "msg"]) == 2, 10)
    chat.load_messages(b)
    pump(lambda: len((chat.cache.get(b) or {}).get("msgs") or []) == 2, 5)
    nf, nv = chat.cache[b]["msgs"]
    f0, f1 = nf["files"][0], nv["files"][0]
    assert nf["text"] == "Datei" and "not forwarded" not in nf["text"] and nv["text"] == ""
    assert f0["x"] == 2 and f0["id"] != mf["files"][0]["id"] and f0["n"] == "gross.pdf" and f0["s"] == os.path.getsize(big)
    assert f1["voice"] is True and f1["dur"] == 2.5 and f1["wf"] == mv["files"][0]["wf"] and f1["id"] != mv["files"][0]["id"]
    th = f0["th"]
    assert th["id"] != te["id"] and (th["w"], th["h"], th["s"]) == (32, 18, len(jpeg))
    from hlidskjalf import chatcrypto as C
    from hlidskjalf import chatmedia
    assert chatmedia.decrypt(b, chatmedia.get_blob(th["id"], cid=b), C.unb64(th["k"], 32)) == jpeg   # neu verschluesselt fuer b
    buf = bytearray()
    chat.save_file(b, f0, buf.extend)
    assert bytes(buf) == open(big, "rb").read()
    buf = bytearray()
    chat.save_file(b, f1, buf.extend)
    assert bytes(buf) == ogg


def test_forward_small_v2_goes_as_v1(win, srv, tmp_path):
    """v2-Anhang, der fuer das Ziel in v1 passt (send_plan "v1"): entschluesselt als normaler v1-Anhang weitergeleitet;
    Metadaten und Poster bleiben, kein copy_v2"""
    from hlidskjalf import chatvideo as V
    from hlidskjalf import filev2 as F
    from hlidskjalf.chat import chat
    from hlidskjalf.chatstdview import ForwardDialog
    from test_video import _chat, _file
    a = _chat(srv)
    srv.chat_peer("bert", "Bert")
    b = srv.new_chat("dm", ["test", "bert"])
    chat.load_chats()
    pump(lambda: chat.chat(b) is not None, 4)
    big = _file(tmp_path, 2 * F.CS + 10, "clip.mp4")
    out, ids = chat.upload_attachments(a, [("clip.mp4", "video/mp4", V.LocalFile(big, os.path.getsize(big)))])
    assert out[0]["x"] == 2
    jpeg = _img(32, 18, fmt="JPEG")
    te = chat.upload_file(a, jpeg, "thumb.jpg", "image/jpeg")
    out[0].update({"m": "video/mp4", "dur": 3.0, "w": 640, "h": 360,
                   "th": {"id": te["id"], "k": te["k"], "m": "image/jpeg", "w": 32, "h": 18, "s": len(jpeg)}})
    chat.send(a, {"v": 1, "type": "text", "text": "", "files": out}, file_ids=ids + [te["id"]])
    win.show_route(("chat", a))
    conv = win.current.conv
    pump(lambda: conv.loaded and len(chat.cache[a]["msgs"]) == 1, 6)
    # Ziel nimmt die Groesse als v1 (hoehere v1-Grenze)
    srv.file_max = 4 * 1024 * 1024
    chat.refresh()
    pump(lambda: (chat.limits or {}).get("file_max") == srv.file_max, 4)
    copied = []
    real = V.copy_v2
    V.copy_v2 = lambda *a_, **k: copied.append(1) or real(*a_, **k)
    try:
        ForwardDialog(conv, chat.cache[a]["msgs"]).go(chat.chat(b))
        pump(lambda: len([x for x in srv.chats[b]["msgs"] if x["kind"] == "msg"]) == 1, 10)
    finally:
        V.copy_v2 = real
    assert not copied
    chat.load_messages(b)
    pump(lambda: len((chat.cache.get(b) or {}).get("msgs") or []) == 1, 5)
    f = chat.cache[b]["msgs"][0]["files"][0]
    assert "x" not in f and "cs" not in f and f["s"] == os.path.getsize(big) and f["n"] == "clip.mp4"
    assert (f["dur"], f["w"], f["h"]) == (3.0, 640, 360) and f["th"]["id"] != te["id"]
    assert chat.fetch_file(b, f) == open(big, "rb").read()
    assert "not forwarded" not in chat.cache[b]["msgs"][0]["text"]
