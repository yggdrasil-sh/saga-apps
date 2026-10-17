"""2.1-c: Chat-Bilder (Vorschaubild beim Senden, nur Vorschaubild im Verlauf, Original beim Oeffnen, Laden nur fuer
sichtbare Zeilen, Speicher-LRU + Platten-Zwischenspeicher nur mit Ciphertext) und Bildbloecke kopieren/ausschneiden/
einfuegen (Strg+C/Strg+X/Strg+V, gleiche und andere Seite, fremde Seite = neu hochladen, als Bild fuer andere Programme)."""
import io
import json
import os

from gi.repository import Gdk, Gtk

from hk_helpers import idle, pump
from hlidskjalf import blockclip, chatmedia
from hlidskjalf import chatcrypto as C


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _img(w, h, fmt="PNG", color=(90, 140, 200), exif_orient=None, alpha=False):
    from PIL import Image
    im = Image.new("RGBA" if alpha else "RGB", (w, h), color + ((128,) if alpha else ()))
    out = io.BytesIO()
    if exif_orient:
        ex = Image.Exif()
        ex[0x0112] = exif_orient
        ex[0x010F] = "Kamera-Hersteller"
        im.save(out, "JPEG", exif=ex.tobytes())
    else:
        im.save(out, fmt)
    return out.getvalue()


# --- Vorschaubild -----------------------------------------------------------------------------------------------------
def test_make_thumb_sizes_orientation_metadata():
    t, (w, h), (tw, th) = chatmedia.make_thumb(_img(1200, 800, alpha=True))
    assert (w, h) == (1200, 800) and (tw, th) == (320, 213) and t.startswith(b"\xff\xd8")
    t, (w, h), (tw, th) = chatmedia.make_thumb(_img(600, 400, exif_orient=6))       # hochkant fotografiert
    assert (w, h) == (400, 600) and (tw, th) == (213, 320)
    assert b"Exif" not in t and b"Kamera" not in t                                  # ohne Metadaten
    t, wh, twh = chatmedia.make_thumb(_img(100, 50))                                  # klein: nicht vergroessern
    assert wh == (100, 50) and twh == (100, 50)
    assert chatmedia.make_thumb(b"kein Bild") is None
    assert chatmedia.box_size((4000, 3000)) == (240, 180) and chatmedia.box_size((1000, 4000)) == (70, 280)
    assert chatmedia.box_size(None) == (200, 150)
    f = {"id": "a", "m": "image/jpeg", "w": "4000", "h": 3000, "th": {"id": "t", "k": "x", "w": 320, "h": 240}}
    assert chatmedia.size_of(f) == (4000, 3000) and chatmedia.thumb_ref(f)["id"] == "t"
    assert chatmedia.size_of({"th": {"w": 320, "h": 240}}) == (320, 240) and chatmedia.size_of({"w": -1}) is None
    assert chatmedia.thumb_ref({"th": {"id": 5}}) is None and not chatmedia.is_image({"m": "image/svg+xml"})


def _conv(win, srv, cid=None):
    from hlidskjalf.chat import chat
    from test_chat import _ready
    if cid is None:
        _ready(srv)
        srv.chat_peer("anna", "Anna Beispiel")
        cid = srv.new_chat("dm", ["test", "anna"])
    chat.load_chats()
    pump(lambda: chat.chat(cid), 4)
    win.show_route(("chat", cid))
    conv = win.current.conv
    pump(lambda: conv.loaded, 5)
    return cid, conv


def _peer_image(srv, cid, data, fid, thumb=True, name="foto.jpg"):
    """Bildnachricht von anna (wie ein 2.1-c-Client: mit w/h/th - oder wie ein alter ohne)"""
    blob, key = C.encrypt_file(cid, data)
    srv.cfiles[fid] = (cid, "anna", blob, None)
    f = {"id": fid, "k": C.b64(key), "n": name, "m": "image/jpeg", "s": len(data)}
    if thumb:
        tb, (w, h), (tw, th) = chatmedia.make_thumb(data)
        tblob, tkey = C.encrypt_file(cid, tb)
        srv.cfiles[fid + "t"] = (cid, "anna", tblob, None)
        f.update({"w": w, "h": h, "th": {"id": fid + "t", "k": C.b64(tkey), "m": "image/jpeg", "w": tw, "h": th, "s": len(tb)}})
    srv.peer_send(cid, "anna", "", files=[f])
    return f


def _gets(srv):
    return [u.rsplit("/", 1)[-1] for m, u, b, c in srv.log if m == "GET" and "/api/chat/files/" in u]


def test_send_adds_thumbnail_and_size(win, srv):
    from hlidskjalf.chat import chat
    cid, conv = _conv(win, srv)
    big = _img(1600, 1200, fmt="JPEG")
    conv._add_ready([("bild.jpg", "image/jpeg", big), ("notiz.txt", "text/plain", b"Hallo")])
    conv.send()
    pump(lambda: any(m["sender"] == "test" for m in srv.chats[cid]["msgs"]), 6)
    idle()
    pump(lambda: any(m.get("mine") for m in chat.cache[cid]["msgs"]), 4)
    m = [x for x in chat.cache[cid]["msgs"] if x.get("mine")][-1]
    img, txt = m["files"]
    assert (img["w"], img["h"]) == (1600, 1200) and "th" not in txt and "w" not in txt
    th = img["th"]
    assert th["m"] == "image/jpeg" and (th["w"], th["h"]) == (320, 240) and set(th) == {"id", "k", "m", "w", "h", "s"}
    plain = C.decrypt_file(cid, srv.cfiles[th["id"]][2], C.unb64(th["k"], 32))
    assert plain.startswith(b"\xff\xd8") and len(plain) == th["s"]
    # Server-Zuordnung: Vorschaubild gehoert zur Nachricht (Aufraeumen, "fuer alle loeschen")
    assert srv.cfiles[th["id"]][3] == srv.chats[cid]["msgs"][-1]["seq"]
    # Grenze je Nachricht: kein Platz -> ohne Vorschaubild (aber mit w/h)
    chat.limits = dict(chat.limits or {}, files_per_msg=2)
    out, ids = chat.upload_attachments(cid, [("a.jpg", "image/jpeg", big), ("b.jpg", "image/jpeg", big)])
    assert len(ids) == 2 and all("th" not in e and e["w"] == 1600 for e in out)
    chat.limits["files_per_msg"] = 3
    out, ids = chat.upload_attachments(cid, [("a.jpg", "image/jpeg", big), ("b.jpg", "image/jpeg", big)])
    assert len(ids) == 3 and "th" in out[0] and "th" not in out[1]


def test_timeline_lazy_thumbs_cache_and_viewer(win, srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import ChatImage, ImageViewer
    from test_chat import _ready
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    photo = _img(1400, 1000, fmt="JPEG", color=(200, 120, 60))
    fs = [_peer_image(srv, cid, photo, f"img{i:03d}") for i in range(40)]
    old = _peer_image(srv, cid, _img(900, 600, fmt="JPEG"), "alt001", thumb=False)
    win.set_default_size(900, 700)
    cid, conv = _conv(win, srv, cid)
    pump(lambda: any(w.state == "ok" for w in conv.img_widgets), 6)
    pump(None, 1.0)
    idle()
    ws = conv.img_widgets
    assert len(ws) == 41 and all(isinstance(w, ChatImage) for w in ws)
    # Platzhalter in der richtigen Groesse schon vor dem Laden; nur Vorschaubilder geladen, nur die sichtbaren (+ Rand)
    assert all((w.bw, w.bh) == (240, 171) for w in ws[:40])
    got = _gets(srv)
    assert got and all(g.endswith("t") or g == "alt001" for g in got)                # keine Originale (ausser alter Nachricht)
    assert len(got) < 30                                                             # nicht alle 40
    assert ws[-1].state == "ok" and ws[0].state == "idle"
    # alte Nachricht ohne "th": Original geladen, Vorschaubild erzeugt und verschluesselt zwischengespeichert
    oldw = ws[-1]
    assert (oldw.bw, oldw.bh) == (240, 160) and oldw.tex.get_width() <= 640
    cache = os.path.join(chatmedia._DIR)
    names = os.listdir(cache)
    assert any(n.endswith("alt001.th") for n in names) and not any(n.endswith("-alt001") for n in names)
    jpeg_heads = [open(os.path.join(cache, n), "rb").read()[:3] for n in names]
    assert b"\xff\xd8\xff" not in jpeg_heads                                         # nur Ciphertext auf der Platte
    # nach oben scrollen: weitere Vorschaubilder; Wegscrollen gibt Texturen frei
    adj = conv.scroll.get_vadjustment()
    adj.set_value(0)
    pump(lambda: ws[0].state == "ok", 6)
    pump(None, 0.5)
    assert ws[0].tex is not None and ws[-1].state == "idle" and ws[-1].tex is None
    n = len(_gets(srv))
    adj.set_value(adj.get_upper())                                                     # zurueck: aus Speicher/Platte
    pump(lambda: ws[-1].state == "ok", 6)
    assert len(_gets(srv)) == n
    # Speicher leer (z. B. andere Unterhaltung) -> von der Platte, nicht vom Server
    chatmedia._mem.clear()
    chatmedia._mem_cost.clear()
    chatmedia._mem_size = 0
    ws[-1].drop()
    conv._update_visible()
    pump(lambda: ws[-1].state == "ok", 6)
    assert len(_gets(srv)) == n
    # Antippen: Original laden und anzeigen
    btn = ws[-2].get_parent()
    assert isinstance(btn, Gtk.Button)
    btn.emit("clicked")
    pump(lambda: isinstance(win.get_visible_dialog(), ImageViewer), 3)
    d = win.get_visible_dialog()
    pump(lambda: d.stack.get_visible_child_name() == "img", 6)
    assert d.view.dec.w == 1400 and fs[-1]["id"] in _gets(srv)
    d.force_close()
    # Abmelden/Serverwechsel/"Neu beginnen": Zwischenspeicher leer
    assert os.listdir(cache) and chatmedia.mem_usage()[1] > 0
    chat.reset()
    assert not os.path.exists(cache) and chatmedia.mem_usage() == (0, 0)
    assert old["id"] == "alt001"


def test_blob_cache_prune_limit(srv):
    import time
    for i in range(6):
        chatmedia.put_blob(f"p{i}", os.urandom(1000))
        p = chatmedia._path(f"p{i}")
        os.utime(p, (time.time() - 100 + i, time.time() - 100 + i))
    assert chatmedia.disk_usage() >= 6000
    assert chatmedia.prune(3500) == 3
    left = sorted(n.split("-", 1)[1] for n in os.listdir(chatmedia._DIR))
    assert left == ["p3", "p4", "p5"]                                                  # die zuletzt benutzten bleiben
    chatmedia.clear()


def test_request_cancel_and_lifo():
    """wartende Auftraege: neueste zuerst, abbrechen verwirft (Sperre gehalten: laufende Arbeiter greifen nicht zu)"""
    started = chatmedia._threads[:]
    if not started:
        chatmedia._threads.append(object())                                           # keine Arbeiter starten
    try:
        with chatmedia._jobs_lock:
            chatmedia._jobs.clear()
            cb1, cb2 = (lambda t, wh: None), (lambda t, wh: None)
            chatmedia.request(1, {"id": "a"}, cb1)
            chatmedia.request(1, {"id": "b"}, cb2)
            assert list(chatmedia._jobs) == ["a", "b"]
            chatmedia.bump("a")
            assert list(chatmedia._jobs) == ["b", "a"]
            chatmedia.cancel("a", cb1)
            assert list(chatmedia._jobs) == ["b"]
            chatmedia.cancel("b", cb1)                                                 # fremder Rueckruf: bleibt
            assert list(chatmedia._jobs) == ["b"]
            chatmedia._jobs.clear()
    finally:
        chatmedia._threads[:] = started


# --- Bloecke kopieren / einfuegen ----------------------------------------------------------------------------------------
def test_blockclip_format():
    blocks = [{"id": "b1", "type": "image", "data": {"file": {"url": "/files/x1"}, "caption": ""}},
              {"id": "b2", "type": "paragraph", "data": {"text": "Hallo <b>Welt</b>"}}]
    raw = blockclip.payload(blocks, "https://s.example", "")
    arr = json.loads(raw)
    assert arr[0]["tool"] == "image" and arr[0]["data"]["file"]["url"] == "/files/x1" and arr[0]["hk"]["owner"] == ""
    got = blockclip.parse(raw)
    assert [b["type"] for b, hk in got] == ["image", "paragraph"] and all(b["id"] not in ("b1", "b2") for b, hk in got)
    # Web-Editor (Editor.js): {"id", "tool", "data"} ohne hk; Unbekanntes faellt weg
    web = json.dumps([{"id": "w", "tool": "image", "data": {"file": {"url": "/files/y"}}}, {"tool": "evil", "data": {}},
                      {"tool": "paragraph", "data": "kaputt"}])
    got = blockclip.parse(web.encode())
    assert len(got) == 1 and got[0][1] == {}
    assert blockclip.parse(b"\xff") == [] and blockclip.parse(b"{}") == []
    assert blockclip.reuse_ok({}, "s", "") and not blockclip.reuse_ok({}, "s", "anna")
    assert blockclip.reuse_ok({"server": "s", "owner": ""}, "s", "") and not blockclip.reuse_ok({"server": "t", "owner": ""}, "s", "")
    assert not blockclip.reuse_ok({"server": "s", "owner": "anna"}, "s", "")
    assert blockclip.block_text(blocks[1]) == "Hallo Welt" and blockclip.block_text(blocks[0]) == ""
    lst = {"type": "list", "data": {"items": [{"content": "a", "items": [{"content": "b", "items": []}]}]}}
    assert blockclip.block_text(lst) == "- a\n  - b"


def _read_mime(cb, mime):
    from gi.repository import Gio
    out = []

    def got(c, r):
        stream = c.read_finish(r)[0]
        o = Gio.MemoryOutputStream.new_resizable()
        o.splice_async(stream, Gio.OutputStreamSpliceFlags.CLOSE_SOURCE | Gio.OutputStreamSpliceFlags.CLOSE_TARGET, 0, None,
                       lambda o, r2: (o.splice_finish(r2), out.append(o.steal_as_bytes().get_data())))
    cb.read_async([mime], 0, None, got)
    pump(lambda: out, 5)
    return out[0]


def test_copy_cut_paste_image_block(win, srv):
    from hlidskjalf.store import store
    from test_sharing import _open
    from test_ui import _open_page
    png = _img(300, 200)
    url = win.upload_image(png, "bild.png")
    v = _open_page(win, 3)
    bid = v.editor.insert_image_url(url, after=v.doc.root[0]["id"])
    pump(lambda: any(getattr(w, "hk_sel_bid", None) == bid for w in _walk(v)), 4)
    box = next(w for w in _walk(v) if getattr(w, "hk_sel_bid", None) == bid)
    pump(lambda: box.get_first_child() is not None and box.get_first_child().dec is not None, 5)
    # Anklicken/Fokus waehlt den Block, Strg+C kopiert Block + Bild
    assert box.get_focusable()
    box.grab_focus()
    idle()
    assert v.editor.focused_block == bid
    assert v._page_key(None, Gdk.KEY_c, 0, Gdk.ModifierType.CONTROL_MASK) is True
    cb = v.get_clipboard()
    fm = cb.get_formats()
    assert fm.contain_mime_type(blockclip.MIME) and fm.contain_gtype(Gdk.Texture)
    assert not any(m.startswith("text/plain") for m in fm.get_mime_types() or [])         # Chat: als Bild einfuegen
    arr = json.loads(_read_mime(cb, blockclip.MIME))
    assert arr[0]["tool"] == "image" and arr[0]["data"]["file"]["url"] == url
    png_out = _read_mime(cb, "image/png")                                                 # andere Programme: Bild
    assert png_out.startswith(b"\x89PNG")
    # Strg+V in derselben Seite: neuer Block, gleiche Datei (kein neuer Upload)
    n_up = len(getattr(srv, "uploads", {}))
    assert v.paste(cb) is True
    pump(lambda: sum(1 for b in v.doc.root if b["type"] == "image") == 2, 4)
    imgs = [b for b in v.doc.root if b["type"] == "image"]
    assert imgs[0]["id"] != imgs[1]["id"] and imgs[1]["data"]["file"]["url"] == url and len(srv.uploads) == n_up
    # Strg+X: Block weg, Zwischenablage hat ihn
    box = next(w for w in _walk(v) if getattr(w, "hk_sel_bid", None) == bid)
    box.grab_focus()
    idle()
    assert v._page_key(None, Gdk.KEY_x, 0, Gdk.ModifierType.CONTROL_MASK) is True
    idle()
    assert bid not in [b["id"] for b in v.doc.root]
    # andere eigene Seite: gleiche Datei
    v2 = _open_page(win, 1)
    v2.editor.set_focus(v2.doc.root[0]["id"])
    assert v2.paste(v2.get_clipboard()) is True
    pump(lambda: any(b["type"] == "image" for b in v2.doc.root), 4)
    assert next(b for b in v2.doc.root if b["type"] == "image")["data"]["file"]["url"] == url and len(srv.uploads) == n_up
    # fremde Seite (Rolle Bearbeiten): neu hochladen, Datei gehoert dem Eigentuemer (X-Saga-Note)
    srv.add_foreign(200, "Projekt Garten", role="edit", blocks=[{"id": "x0", "type": "paragraph", "data": {"text": "Hallo"}}])
    store.refresh()
    idle()
    v3 = _open(win, 200)
    v3.editor.set_focus("x0")
    assert v3.paste(v3.get_clipboard()) is True
    pump(lambda: any(b["type"] == "image" for b in v3.doc.root), 5)
    nb = next(b for b in v3.doc.root if b["type"] == "image")
    assert nb["data"]["file"]["url"] != url and len(srv.uploads) == n_up + 1
    assert any(h[2] == {"X-Saga-Note": "200"} for h in srv.headers_log)
    # Bild aus dem Block auch im Chat einfuegbar (Bild, nicht Text)
    from hlidskjalf import clipimg
    assert clipimg.clip_kind(v3.get_clipboard().get_formats()) == "image"
