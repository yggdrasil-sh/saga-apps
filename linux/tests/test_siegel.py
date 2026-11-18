"""Saga-Siegel als Seitensymbol (2.5, Bugs-DB 2156): Anzeige als PNG (?w=), Auswahl im Symbol-Dialog (nur mit siegel1),
dazu "/Seite" bzw. "/Datenbank" mit link: false (Bug 2151). Schein-Server: tests/mocksiegel.py."""
import json

from gi.repository import Gtk

from hk_helpers import idle, pump

RABE = "/siegel/smaragd/rabe.svg"
HORN = "/siegel/smaragd/horn.svg"          # nur hier (Zwischenspeicher: sonst kein Abruf)


# --- Logik (ohne Fenster) --------------------------------------------------------------------------
def test_paths_and_sizes():
    from hlidskjalf import images
    assert images.is_siegel(RABE) and images.siegel_parts(RABE) == ("smaragd", "rabe")
    for bad in ("/siegel/", "/siegel/blau/rabe.svg", "/siegel/smaragd/Rabe.svg", "/siegel/smaragd/rabe.png", RABE + "?w=64",
                " " + RABE, "/siegel/smaragd/../rabe.svg", "https://x" + RABE, None, 5):
        assert not images.is_siegel(bad) and images.siegel_parts(bad) is None
    assert images.is_file_url(RABE) and not images.is_file_url("/siegel/")
    assert [images.siegel_px(w) for w in (None, 0, 20, 64, 65, 128, 160, 320, 5000)] == [256, 256, 64, 64, 128, 128, 256, 256, 256]
    assert images.sized(RABE, None) == RABE + "?w=256"           # nie SVG laden
    assert images.sized(RABE, 40) == RABE + "?w=64"
    assert images.sized("/files/a", 160) == "/files/a?w=160"     # eigene Bilder wie bisher
    assert images.sized("/files/a", None) == "/files/a"


def test_parse_listing():
    from hlidskjalf import siegel
    import mocksiegel
    lst = siegel.parse(mocksiegel.listing())
    assert [c["id"] for c in lst["colors"]] == ["weinrot", "smaragd", "elfenbein"]
    assert len(lst["motifs"]) == 36 and lst["motifs"][1] == {"id": "rabe", "label": "Rabe"}
    j = {"colors": [{"id": "weinrot", "label": " Weinrot "}, {"id": "blau", "label": "Blau"}, {"id": "weinrot"}, "x",
                    {"id": "../x", "label": "y"}],
         "motifs": [{"id": "rabe"}, {"id": "Rabe", "label": "gross"}, {"id": "a"}, {"id": "katze", "label": 5}, None],
         "path": "https://fremd.example/{color}/{motif}.svg"}
    lst = siegel.parse(j)
    assert lst["colors"] == [{"id": "weinrot", "label": "Weinrot"}]               # nur gueltige Siegel-Farben
    assert lst["motifs"] == [{"id": "rabe", "label": "rabe"}, {"id": "katze", "label": "katze"}]
    for bad in (None, [], {}, {"colors": [], "motifs": [{"id": "rabe"}]}, {"colors": [{"id": "smaragd"}], "motifs": []}, "x"):
        assert siegel.parse(bad) is None
    full = siegel.parse(mocksiegel.listing())
    assert siegel.label_of(full, RABE) == "Rabe (Smaragd)"
    assert siegel.label_of(full, "/siegel/weinrot/teetasse.svg") == "Teetasse (Weinrot)"
    assert siegel.label_of(full, "📄") == "" and siegel.label_of(None, RABE) == ""
    assert siegel.matches({"id": "schluessel", "label": "Schlüssel"}, "SCHLÜ") and siegel.matches({"id": "rabe", "label": "Rabe"}, "")
    assert not siegel.matches({"id": "rabe", "label": "Rabe"}, "katze")


def test_start_color_and_offered(srv):
    from hlidskjalf import config, siegel
    from hlidskjalf.sharing import remote_can
    from hlidskjalf.store import store
    lst = siegel.parse(__import__("mocksiegel").listing())
    assert siegel.start_color(lst, None) == "weinrot"
    config.settings.set(siegel.SETTING, "elfenbein")
    assert siegel.start_color(lst, "📄") == "elfenbein"                  # zuletzt gewaehlt
    assert siegel.start_color(lst, RABE) == "smaragd"                    # Farbe des aktuellen Siegels
    config.settings.set(siegel.SETTING, "lila")
    assert siegel.start_color(lst, None) == "weinrot"
    store.features = frozenset()
    assert not siegel.offered(store)                                     # aelterer Server: keine Auswahl
    store.features = frozenset({"siegel1"})
    assert siegel.offered(store)
    rem = {"id": -5, "remote": {"caps": ["db", "upload"]}}
    assert not siegel.offered(store, lambda c: remote_can(rem, c))      # fremder Server ohne siegel1
    rem["remote"]["caps"].append("siegel1")
    assert siegel.offered(store, lambda c: remote_can(rem, c))


# --- Anzeige ------------------------------------------------------------------------------------------
def test_page_icon_widgets(srv, app):
    from hlidskjalf import images
    v = images.page_icon(RABE, size=20)
    assert isinstance(v, images.ImageView) and v.wparam == 64 and v.has_css_class("hk-siegel")
    assert images.page_icon(RABE, size=78).wparam == 256
    # von aelteren Servern auf 8 Zeichen gekuerzt ("/siegel/") bzw. unbekannte Pfade: Ersatzsymbol statt Pfad als Text
    for odd in ("/siegel/", "/irgendwas/x.svg"):
        w = images.page_icon(odd, size=20)
        assert not (isinstance(w, Gtk.Label) and w.get_label().startswith("/"))
    assert images.page_icon("📝", size=20).get_label() == "📝"


def _open(win, nid):
    win.show_page(nid)
    pump(lambda: getattr(win.current, "id", None) == nid and win.current.doc.fetched, 6)
    pump(None, 0.3)
    return win.current


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def test_header_and_sidebar_show_png(win, srv):
    from hlidskjalf import images
    from hlidskjalf.store import store
    v = _open(win, 3)
    v.save_fields({"icon": HORN})
    idle()
    assert pump(lambda: any(isinstance(w, images.ImageView) and w.path == HORN for w in _walk(win.current)), 4)
    assert store.by_id[3]["icon"] == HORN
    # Seitenkopf und Seitenleiste (Baum): beide als Bild
    assert pump(lambda: sum(1 for w in _walk(win) if isinstance(w, images.ImageView) and w.path == HORN) >= 2, 4)
    assert pump(lambda: any(x.startswith("siegel/smaragd/horn.svg?w=") for x in srv.file_log), 4)
    assert all("?w=" in x for x in srv.file_log if x.startswith("siegel/"))        # nie das SVG
    patches = [b for m, u, b, c in srv.log if m == "PATCH" and u.endswith("/api/notes/3")]
    assert patches and patches[-1].get("icon") == HORN


# --- Auswahl ------------------------------------------------------------------------------------------
def _picker(win, v):
    from hlidskjalf.page import IconPicker
    d = IconPicker(v)
    d.present(win)
    pump(None, 0.2)
    return d


def test_picker_without_siegel1(win, srv):
    from hlidskjalf.store import store
    store.features = frozenset()
    v = _open(win, 3)
    d = _picker(win, v)
    assert d.stack is None
    assert not srv.siegel_log                                            # aelterer Server: nie gefragt
    d.force_close()


def test_picker_pick_seal(win, srv):
    from hlidskjalf import config, siegel
    from hlidskjalf.store import store
    siegel._cache.clear()
    store.features = frozenset({"siegel1"})
    v = _open(win, 3)
    v.save_fields({"icon": RABE})
    idle()
    v = win.current
    d = _picker(win, v)
    assert d.stack is not None and d.stack.get_visible_child_name() == "siegel"       # aktuelles Symbol ist ein Siegel
    pane = d.stack.get_child_by_name("siegel")
    assert pump(lambda: len(pane.tiles) == 36, 4)
    assert pane.color == "smaragd" and pane.cbtns["smaragd"].get_active()
    on = [t for t in pane.tiles if t.has_css_class("hk-on")]
    assert len(on) == 1 and on[0].hk_motif["id"] == "rabe" and on[0].get_tooltip_text() == "Rabe (Smaragd)"
    pane.cbtns["weinrot"].set_active(True)
    idle()
    assert pane.color == "weinrot" and config.settings.get(siegel.SETTING) == "weinrot"
    assert pane.tiles[1].get_tooltip_text() == "Rabe (Weinrot)" and not any(t.has_css_class("hk-on") for t in pane.tiles)
    pane.search.set_text("tee")
    pump(lambda: pane.none.get_visible() is False, 1)
    pane.search.set_text("gibtsnicht")
    assert pump(lambda: pane.none.get_visible(), 2)
    pane.search.set_text("tee")
    assert pump(lambda: not pane.none.get_visible(), 2)
    pane.search.emit("activate")                                          # Eingabe: erster Treffer
    idle()
    assert pump(lambda: srv.notes[3].get("icon") == "/siegel/weinrot/teetasse.svg", 4)
    pump(None, 0.2)
    assert win.get_visible_dialog() is None
    # zweites Oeffnen: Liste aus dem Speicher (einmal je Server und Sprache)
    n = len(srv.siegel_log)
    d = _picker(win, win.current)
    pane = d.stack.get_child_by_name("siegel")
    assert pump(lambda: len(pane.tiles) == 36, 2) and len(srv.siegel_log) == n
    assert pane.color == "weinrot"
    pane.grid.emit("child-activated", pane.tiles[0])                    # Kachel antippen
    assert pump(lambda: srv.notes[3].get("icon") == "/siegel/weinrot/yggdrasil.svg", 4)


def test_picker_list_fails(win, srv):
    from hlidskjalf import siegel
    from hlidskjalf.store import store
    siegel._cache.clear()
    store.features = frozenset({"siegel1"})
    srv.siegel_api = False
    v = _open(win, 3)
    d = _picker(win, v)
    assert d.stack.get_visible_child_name() == "emoji"                  # Emoji-Symbol: Emoji-Bereich zuerst
    pane = d.stack.get_child_by_name("siegel")
    assert pump(lambda: pane.status.get_label() == "Saga seals could not be loaded.", 4)
    d.force_close()


# --- "/Seite", "/Datenbank": Verweis setzt die App, nicht der Server (Bug 2151) --------------------------
def test_slash_page_and_db_send_link_false(win, srv):
    v = _open(win, 3)
    bid = v.doc.root[0]["id"]
    v.editor.slash_apply(bid, "page", 0)
    assert pump(lambda: any(m == "POST" and u.endswith("/api/notes") and isinstance(b, dict) and b.get("parent_id") == 3
                            for m, u, b, c in srv.log), 4)
    posts = [b for m, u, b, c in srv.log if m == "POST" and u.endswith("/api/notes") and isinstance(b, dict)]
    assert posts[-1]["link"] is False and posts[-1]["title"] == ""
    pump(None, 0.5)
    v = _open(win, 3)
    v.doc.root.append({"id": "nz1", "type": "paragraph", "data": {"text": ""}})
    v.editor.slash_apply("nz1", "db_new", 0)
    assert pump(lambda: any(m == "POST" and u.endswith("/api/notes") and isinstance(b, dict) and b.get("kind") == "database"
                            for m, u, b, c in srv.log), 4)
    db = [b for m, u, b, c in srv.log if m == "POST" and u.endswith("/api/notes") and isinstance(b, dict) and b.get("kind") == "database"]
    assert db[-1]["link"] is False
    json.dumps(db[-1])
