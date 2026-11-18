"""Titelbild-Galerie (2.5, Bugs-DB 2117): Logik ohne GTK (covergallery.py) und Menue/Dialog mit dem Schein-Server."""
import pytest
from gi.repository import Gtk

from hk_helpers import idle, pump

ALL = ["db", "upload", "presence"]


def _listing():
    from hlidskjalf import covergallery as cg
    import mockcovers

    class S:
        pass
    s = S()
    mockcovers.init(s)
    return cg.parse({"covers": s.covers, "groups": s.cover_groups, "source": " The Met ", "license": "CC0"})


# --- Logik (ohne GTK) ------------------------------------------------------------------------------
def test_parse_tolerant():
    from hlidskjalf import covergallery as cg
    j = {"covers": [
        {"url": "/covers/a-1.jpg", "thumb": "/covers/a-1.jpg?w=384", "title": " A ", "artist": "K", "date": "1830", "pos": 140,
         "source_url": "https://example.org/a", "source": "Met", "group": "g1", "builtin": True, "neu": [1]},
        {"url": "/covers/a-1.jpg", "title": "doppelt"},
        {"url": "/covers/../x.jpg"}, {"url": "/covers/A.jpg"}, {"url": "/files/x.jpg"}, {"url": "https://x/covers/b.jpg"},
        {"url": 5}, None, "x", [1],
        {"url": "/covers/b.jpg", "thumb": "https://fremd.example/b.jpg", "pos": "oben", "source_url": "javascript:alert(1)",
         "builtin": "true"},
        {"url": "/covers/c.jpg", "pos": float("nan"), "builtin": 1},
        {"url": "/covers/d.jpg", "pos": -5, "thumb": "/covers/d.jpg"},
    ], "groups": [{"id": "g1", "label": "Gruppe 1"}, {"id": "g1", "label": "doppelt"}, "x", {"label": "ohne"}], "extra": 1}
    lst = cg.parse(j)
    assert [c["id"] for c in lst["covers"]] == ["a-1", "b", "c", "d"]
    a, b, c, d = lst["covers"]
    assert a["title"] == "A" and a["pos"] == 100.0 and a["builtin"] is True and a["source_url"] == "https://example.org/a"
    assert a["thumb"] == "/covers/a-1.jpg?w=384"
    assert b["thumb"] == "/covers/b.jpg?w=384" and b["pos"] == 50.0 and b["source_url"] == "" and b["builtin"] is False
    assert b["title"] == "b"                                       # ohne Titel: ID
    assert c["pos"] == 50.0 and c["builtin"] is False                # nur echtes true zaehlt
    assert d["pos"] == 0.0 and d["thumb"] == "/covers/d.jpg"
    assert lst["groups"] == [("g1", "Gruppe 1"), ("", "ohne")]
    for bad in (None, [], {}, {"covers": None}, {"covers": {"a": 1}}, "x"):
        assert cg.parse(bad) is None
    assert cg.parse({"covers": []}) == {"covers": [], "groups": [], "source": "", "license": ""}


def test_paths_and_codes():
    from hlidskjalf import covergallery as cg
    assert cg.id_of("/covers/met-wave.jpg") == "met-wave"
    for bad in ("/covers/met-wave.jpg?w=3", "/covers/-a.jpg", "/files/a.jpg", "covers/a.jpg", None, 5, "/covers/a.png"):
        assert cg.id_of(bad) is None and not cg.is_gallery_path(bad)
    assert cg.offered_code(200) is True
    assert cg.offered_code(404) is False and cg.offered_code(403) is False
    for c in (0, 401, 423, 500, 502, None):
        assert cg.offered_code(c) is None


def test_labels():
    from hlidskjalf import covergallery as cg
    lst = _listing()
    wave = lst["covers"][0]
    assert cg.credit(wave) == "Katsushika Hokusai, ca. 1830–32"
    assert cg.label(wave) == "Under the Wave off Kanagawa – Katsushika Hokusai, ca. 1830–32"
    fjord = next(c for c in lst["covers"] if c["id"] == "saga-fjord")
    assert cg.credit(fjord) == "" and cg.label(fjord) == "Fjord im Nebel"
    assert lst["source"] == "The Met"


def test_groups_order_and_remote():
    from hlidskjalf import covergallery as cg
    lst = _listing()
    g = cg.grouped(lst, False, "Weitere")
    assert [(x[0], x[1]) for x in g] == [("nordic", "Nordisch"), ("japan", "Japan"), ("", "Weitere")]
    assert [c["id"] for c in g[0][2]] == ["saga-fjord", "saga-aurora"]
    assert [c["id"] for c in g[2][2]] == ["eigen-1"]
    r = cg.grouped(lst, True, "Weitere")                        # fremde Instanz: nur mitgelieferte Bilder
    assert [x[0] for x in r] == ["nordic", "japan"]
    assert all(c["builtin"] for x in r for c in x[2])
    # Gruppe, die der Server nicht nennt: hinter die genannten (Name = ID); leere Gruppen fallen weg
    lst2 = dict(lst, groups=[("leer", "Leer"), ("japan", "Japan")])
    assert [(x[0], x[1]) for x in cg.grouped(lst2, False, "W")] == [("japan", "Japan"), ("nordic", "nordic"), ("", "W")]
    assert cg.grouped(None, False, "W") == []


def test_random_not_current():
    from hlidskjalf import covergallery as cg
    lst = _listing()
    cur = "/covers/met-wave.jpg"
    seen = {cg.pick_random(lst, False, cur, roll=lambda n, i=i: i)["url"] for i in range(5)}
    assert cur not in seen and len(seen) == 4
    assert cg.pick_random(lst, False, cur, roll=lambda n: 99)["url"] == "/covers/eigen-1.jpg"       # Index begrenzt
    assert all(cg.pick_random(lst, True, None)["builtin"] for _ in range(20))
    one = dict(lst, covers=[lst["covers"][0]])
    assert cg.pick_random(one, False, cur) is None                 # nur das jetzige: nichts
    assert cg.pick_random(one, False, None)["url"] == cur
    assert cg.pick_random(None, False, None) is None
    assert cg.fields(lst["covers"][0], None) == {"cover": cur, "cover_pos": 40.0}
    assert cg.fields(lst["covers"][0], cur) is None


def test_about_text():
    from hlidskjalf import covergallery as cg
    lst = _listing()
    assert cg.about(lst, "/covers/met-wave.jpg") == ("image", lst["covers"][0])
    assert cg.about(lst, "/covers/weg.jpg") == ("gone", None)       # ausgeblendet/entfernt
    assert cg.about(None, "/covers/met-wave.jpg") == ("gone", None)
    for cur in ("/files/abc", None, "", "https://x/covers/a.jpg"):
        assert cg.about(lst, cur) == ("none", None)


def test_menu_keys():
    from hlidskjalf import covergallery as cg
    lst = _listing()
    assert cg.menu_keys(None, True, listing=lst) == ["gallery", "random", "file", "immich"]
    assert cg.menu_keys("/covers/met-wave.jpg", True, listing=lst) == ["gallery", "random", "file", "immich", "about"]
    assert cg.menu_keys("/files/x", True, listing=lst) == ["gallery", "random", "file", "immich"]
    assert cg.menu_keys("/covers/met-wave.jpg", False, listing=None) == ["file", "immich"]       # 404: nichts anbieten
    assert cg.menu_keys(None, True, locked=True, listing=lst) == ["file", "immich"]              # gesperrt: Server sagt 423
    assert cg.menu_keys("/covers/met-wave.jpg", True, remote=True, listing=lst) == ["gallery", "random", "file", "about"]
    one = dict(lst, covers=[lst["covers"][0]])
    assert cg.menu_keys("/covers/met-wave.jpg", True, listing=one) == ["gallery", "file", "immich", "about"]


def test_state():
    from hlidskjalf import covergallery as cg
    st = cg.CoverState()
    assert st.offered("https://a", "de") is None and st.offered("", "de") is None
    assert st.begin("https://a", "de") and not st.begin("https://a", "de")          # einmal fragen
    st.record("https://a", "de", 500)                                             # spaeter wieder
    assert st.offered("https://a", "de") is None and st.begin("https://a", "de")
    st.record("https://a", "de", 200, {"covers": []})
    assert st.offered("https://a", "de") is True and st.offered("https://a", "en") is None
    assert not st.begin("https://a", "de")
    st.record("https://a", "en", 200, None)                                       # unlesbar: nichts aendern
    assert st.offered("https://a", "en") is None
    st.record("https://a", "en", 404)
    assert st.offered("https://a", "de") is False and st.cached("https://a", "de") is None
    assert not st.begin("https://a", "de")
    st.record("https://a", "de", 200, {"covers": []})                              # Server aktualisiert
    assert st.offered("https://a", "de") is True
    st.clear()
    assert st.offered("https://a", "de") is None


# --- Menue und Dialog mit dem Schein-Server -------------------------------------------------------
def _open(win, nid):
    win.show_page(nid)
    pump(lambda: getattr(win.current, "id", None) == nid and win.current.doc.fetched, 6)
    pump(None, 0.3)
    return win.current


def _probed(srv):
    """Das Fenster hat beim Start schon gefragt (Schein-Server Standard 404) - mit der Einstellung des Tests neu fragen"""
    from hlidskjalf import coverdialog
    coverdialog.cg.state.clear()
    n = len(srv.covers_log)
    coverdialog.probe()
    return pump(lambda: len(srv.covers_log) > n and coverdialog.cg.state.offered(coverdialog._srv(), coverdialog.ui_language()) is not None, 4)


def _labels(entries):
    return [e[0] if e else None for e in entries]


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _dialog(win):
    pump(None, 0.2)
    d = win.get_visible_dialog()
    from hlidskjalf.coverdialog import CoverGalleryDialog
    assert isinstance(d, CoverGalleryDialog), d
    pump(lambda: d.stack.get_visible_child_name() in ("list", "empty", "failed"), 4)
    return d


def test_menu_with_gallery(win, srv):
    from hlidskjalf.page import cover_menu
    srv.covers_api = True
    v = _open(win, 3)
    assert _probed(srv)
    n0 = len(srv.covers_log)
    assert _labels(cover_menu(v, None)) == ["Gallery …", "Random", "File …", "From Immich …"]
    v.save_fields({"cover": "/covers/met-wave.jpg", "cover_pos": 40})
    idle()
    v = win.current
    assert v.cover is not None
    assert _labels(cover_menu(v, v.doc.page["cover"])) == ["Gallery …", "Random", "File …", "From Immich …", None, "About this image"]
    assert _labels(cover_menu(v, "/files/eigenes")) == ["Gallery …", "Random", "File …", "From Immich …"]
    assert len(srv.covers_log) == n0                                    # einmal je Server und Sprache


@pytest.mark.parametrize("code", [404, 403])
def test_menu_without_gallery(win, srv, code):
    from hlidskjalf.page import cover_menu
    srv.covers_api = code
    v = _open(win, 3)
    assert _probed(srv)
    assert _labels(cover_menu(v, None)) == ["File …", "From Immich …"]
    assert _labels(cover_menu(v, "/covers/met-wave.jpg")) == ["File …", "From Immich …"]
    from hlidskjalf import coverdialog
    n0 = len(srv.covers_log)
    coverdialog.probe()
    pump(None, 0.3)
    assert len(srv.covers_log) == n0                                    # nicht immer wieder fragen


def test_locked_page_no_gallery(win, srv):
    from hlidskjalf.page import cover_menu
    srv.covers_api = True
    srv.notes[3]["locked"] = True
    v = _open(win, 3)
    assert _probed(srv)
    assert v.editor.locked
    labels = _labels(cover_menu(v, "/covers/met-wave.jpg"))
    assert "Gallery …" not in labels and "Random" not in labels
    from hlidskjalf import coverdialog
    before = len([x for x in srv.log if x[0] == "PATCH"])
    coverdialog.set_cover(v, _listing()["covers"][1])                    # auch direkt: nichts senden
    idle()
    assert len([x for x in srv.log if x[0] == "PATCH"]) == before


def test_dialog_groups_and_choose(win, srv):
    srv.covers_api = True
    v = _open(win, 3)
    assert _probed(srv)
    from hlidskjalf.coverdialog import CoverGalleryDialog
    CoverGalleryDialog(v).present(win)
    d = _dialog(win)
    assert d.stack.get_visible_child_name() == "list"
    heads = [x.get_label() for x in _walk(d.scroll) if isinstance(x, Gtk.Label) and x.has_css_class("heading")]
    assert heads == ["Nordisch", "Japan", "More"]
    assert list(d.tiles) == ["saga-fjord", "saga-aurora", "met-wave", "met-fuji", "eigen-1"]
    wave = d.tiles["met-wave"]
    assert wave.get_tooltip_text() == "Under the Wave off Kanagawa – Katsushika Hokusai, ca. 1830–32"
    hint = [x.get_label() for x in _walk(d.scroll) if isinstance(x, Gtk.Label) and x.has_css_class("caption")]
    assert ("Bundled artworks: public domain, The Metropolitan Museum of Art (Open Access). The Saga images are AI-generated."
            in hint)
    pump(lambda: any(p.startswith("covers/met-wave.jpg?w=384") for p in srv.file_log), 4)       # Vorschau ueber thumb
    d.flows[1].emit("child-activated", wave)
    pump(lambda: srv.notes[3].get("cover") == "/covers/met-wave.jpg", 4)
    idle()
    assert srv.notes[3]["cover"] == "/covers/met-wave.jpg" and srv.notes[3]["cover_pos"] == 40
    assert win.get_visible_dialog() is None
    # erneut: jetziges Bild markiert, "Zufaellig" waehlt ein anderes
    v = win.current
    CoverGalleryDialog(v).present(win)
    d = _dialog(win)
    assert d.tiles["met-wave"].has_css_class("hk-on") and not d.tiles["met-fuji"].has_css_class("hk-on")
    d.random_btn.emit("clicked")
    pump(lambda: srv.notes[3].get("cover") != "/covers/met-wave.jpg", 4)
    idle()
    assert srv.notes[3]["cover"].startswith("/covers/") and srv.notes[3]["cover"] != "/covers/met-wave.jpg"


def test_random_from_menu_and_about(win, srv):
    srv.covers_api = True
    srv.notes[3]["cover"] = "/covers/met-fuji.jpg"
    v = _open(win, 3)
    assert _probed(srv)
    from hlidskjalf import coverdialog
    dl = coverdialog.about_dialog(v)
    assert dl.get_heading() == "About this image"
    assert "Fine Wind, Clear Morning" in dl.get_body() and "The Metropolitan Museum of Art" in dl.get_body()
    assert dl.has_response("source") and dl.get_response_label("source") == "View source ↗"
    coverdialog.random_cover(v)
    pump(lambda: srv.notes[3].get("cover") != "/covers/met-fuji.jpg", 4)
    idle()
    c = srv.notes[3]["cover"]
    assert c != "/covers/met-fuji.jpg" and c.startswith("/covers/")
    exp = next(x for x in srv.covers if x.get("url") == c)
    assert srv.notes[3]["cover_pos"] == exp["pos"]
    # nicht mehr in der Auswahl
    v = win.current
    v.doc.page["cover"] = "/covers/weg.jpg"
    dl = coverdialog.about_dialog(v)
    assert dl.get_body() == "An image from the cover gallery (no longer offered)." and not dl.has_response("source")
    # eigenes Bild ohne Quelle
    v.doc.page["cover"] = "/covers/eigen-1.jpg"
    dl = coverdialog.about_dialog(v)
    assert "Hausbild" in dl.get_body() and not dl.has_response("source")        # javascript: nie als Quelle


def test_remote_only_builtin(win, srv):
    from hlidskjalf.page import cover_menu
    from hlidskjalf.store import store
    srv.covers_api = True
    srv.add_fed(-2049, "Projekt Nord", caps=ALL)
    store.load_fed_shared()
    pump(lambda: store.meta(-2049) is not None, 5)
    v = _open(win, -2049)
    assert v.remote
    assert _probed(srv)
    assert _labels(cover_menu(v, None)) == ["Gallery …", "Random", "File …"]       # kein Immich auf fremden Servern
    from hlidskjalf.coverdialog import CoverGalleryDialog
    CoverGalleryDialog(v).present(win)
    d = _dialog(win)
    assert list(d.tiles) == ["saga-fjord", "saga-aurora", "met-wave", "met-fuji"]  # eigenes Bild des Admins fehlt
    d.flows[0].emit("child-activated", d.tiles["saga-aurora"])
    pump(lambda: srv.fed[-2049].get("cover") == "/covers/saga-aurora.jpg", 4)
    idle()
    assert srv.fed[-2049]["cover"] == "/covers/saga-aurora.jpg" and srv.fed[-2049]["cover_pos"] == 30


def test_dialog_404_closes_and_errors(win, srv):
    srv.covers_api = True
    v = _open(win, 3)
    assert _probed(srv)
    from hlidskjalf import coverdialog
    from hlidskjalf.coverdialog import CoverGalleryDialog
    coverdialog.cg.state.clear()
    srv.fail["GET api/covers"] = 500                                    # Fehler ohne Liste: Hinweis mit "Erneut"
    CoverGalleryDialog(v).present(win)
    d = _dialog(win)
    assert d.stack.get_visible_child_name() == "failed" and not d.random_btn.get_sensitive()
    del srv.fail["GET api/covers"]
    srv.covers = []
    d.failed.get_child().emit("clicked")
    pump(lambda: d.stack.get_visible_child_name() == "empty", 4)
    assert d.stack.get_visible_child_name() == "empty"
    d.close()
    assert pump(lambda: win.get_visible_dialog() is None, 3)
    srv.covers_api = 404                                                # Server zurueckgestuft: schliessen, nicht mehr anbieten
    d2 = CoverGalleryDialog(v)
    d2.present(win)
    pump(lambda: win.get_visible_dialog() is None, 4)
    assert win.get_visible_dialog() is None, (win.get_visible_dialog() is d2, d2.stack.get_visible_child_name())
    assert coverdialog.offered() is False


# --- Pruefung (pruef-galerie-linux): Randfaelle Lebenszyklus, Sperre, 423 -------------------------------------------
def test_user_closes_before_404(win, srv, capfd):
    """Dialog geschlossen, bevor die 404 ankommt: kein GTK-Fehler, kein haengender Dialog, Galerie danach aus"""
    srv.covers_api = True
    v = _open(win, 3)
    assert _probed(srv)
    from hlidskjalf import coverdialog
    from hlidskjalf.coverdialog import CoverGalleryDialog
    srv.covers_api = 404
    srv.delay["GET api/covers"] = 1.0
    d = CoverGalleryDialog(v)
    d.present(win)
    pump(None, 0.4)
    d.close()
    assert pump(lambda: win.get_visible_dialog() is None, 3)
    capfd.readouterr()
    pump(None, 2.0)
    idle()
    err = capfd.readouterr().err
    assert win.get_visible_dialog() is None
    assert coverdialog.offered() is False
    assert "CRITICAL" not in err and "Traceback" not in err, err


def test_locked_while_dialog_open(win, srv):
    """Sperre kommt an, waehrend der Dialog offen ist: die Auswahl speichert nichts"""
    srv.covers_api = True
    v = _open(win, 3)
    assert _probed(srv)
    from hlidskjalf.coverdialog import CoverGalleryDialog
    before = srv.notes[3].get("cover")
    CoverGalleryDialog(v).present(win)
    d = _dialog(win)
    srv.log.clear()
    v.editor.locked = True
    d.flows[0].emit("child-activated", d.tiles["saga-fjord"])
    pump(None, 1.0)
    idle()
    assert srv.notes[3].get("cover") == before
    assert not [x for x in srv.log if x[0] == "PATCH" and "api/notes/3" in x[1]]


def test_patch_423_message(win, srv):
    """Server lehnt das Titelbild ab (gesperrt, Server ab Bug 2124: 423): Meldung des Servers als Hinweis, nicht gespeichert"""
    srv.covers_api = True
    v = _open(win, 3)
    assert _probed(srv)
    toasts = []
    win.toast = toasts.append
    srv.fail["PATCH api/notes/3"] = 423
    from hlidskjalf.coverdialog import CoverGalleryDialog
    CoverGalleryDialog(v).present(win)
    d = _dialog(win)
    d.flows[0].emit("child-activated", d.tiles["saga-fjord"])
    assert pump(lambda: toasts, 4)
    assert toasts == ["mock 423"]
    assert srv.notes[3].get("cover") != "/covers/saga-fjord.jpg"
