"""Vorlagengalerie (2.5, SPEC-galerie §5): Parsing, Gruppierung, Fehlerabbildung, Anfrage, Dialog mit dem Schein-Server."""
import pytest

from hk_helpers import idle, pump

SERVER = "https://saga.test"


# --- Logik (ohne GTK) ------------------------------------------------------------------------------
def test_parse_list_tolerant():
    from hlidskjalf import galerie as g
    j = {"items": [
        {"id": "a-1", "title": " Titel ", "desc": "D", "icon": "✅", "category": "work", "order": 5, "neu": [1],
         "needs": ["calendar", 3], "contents": [{"kind": "database", "title": "DB", "icon": "📁", "x": 1}, {"title": ""}, "x",
                                               {"kind": "seltsam", "title": "S"}]},
        {"id": "../x", "title": "Pfad"}, {"id": "a b", "title": "Leer"}, {"id": "", "title": "leer"}, {"id": 5, "title": "Zahl"},
        {"id": "ohne"}, {"id": "t", "title": 7}, None, "x", [1],
        {"id": "a-1", "title": "Doppelt"},
        {"id": "b_2", "title": "B", "category": "zukunft", "order": "3", "needs": "calendar", "contents": {"x": 1}},
    ], "neu": True}
    items = g.parse_list(j)
    assert [i["id"] for i in items] == ["a-1", "b_2"]
    a, b = items
    assert a["title"] == "Titel" and a["category"] == "work" and a["order"] == 5 and a["needs"] == ["calendar"]
    assert a["contents"] == [{"kind": "database", "title": "DB", "icon": "📁"}, {"kind": "page", "title": "S", "icon": ""}]
    assert b["category"] == "other" and b["order"] == 0 and b["needs"] == [] and b["contents"] == [] and b["desc"] == ""
    for bad in (None, [], {"items": None}, {"items": {"a": 1}}, "x", {}):
        assert g.parse_list(bad) == []


def test_valid_id_and_paths():
    from hlidskjalf import galerie as g
    assert g.valid_id("aufgaben-projekte") and g.valid_id("A_1")
    for bad in ("", "a/b", "../x", "a b", "ä", "a?x", None, 3, "x" * 65):
        assert not g.valid_id(bad)
        with pytest.raises(ValueError):
            g.use_path(bad)
    assert g.use_path("wiki") == "api/gallery/wiki/use"
    assert g.list_path("de") == "api/gallery?lang=de"
    assert g.list_path("th") == "api/gallery?lang=th"
    assert g.list_path("x&y=1") == "api/gallery?lang=en"


def test_group_order():
    from hlidskjalf import galerie as g
    items = g.parse_list({"items": [
        {"id": "t", "title": "Team", "category": "team", "order": 1},
        {"id": "w2", "title": "Zwei", "category": "work", "order": 20},
        {"id": "w1", "title": "Eins", "category": "work", "order": 10},
        {"id": "o", "title": "Anderes", "category": "neu"},
        {"id": "p2", "title": "b", "category": "personal", "order": 5},
        {"id": "p1", "title": "A", "category": "personal", "order": 5},
    ]})
    gr = g.group(items)
    assert [c for c, _h, _i in gr] == ["personal", "work", "team", "other"]
    assert [[i["id"] for i in its] for _c, _h, its in gr] == [["p1", "p2"], ["w1", "w2"], ["t"], ["o"]]
    assert [h for _c, h, _i in gr] == ["Personal", "Work", "Team", "Other templates"]
    assert g.group([]) == []


def test_contents_text():
    from hlidskjalf import galerie as g
    assert g.contents_text([]) == ""
    t = g.contents_text([{"kind": "page", "title": "Start", "icon": "✅"}, {"kind": "database", "title": "Aufgaben", "icon": ""}])
    assert t == "Contains: ✅ Start, Aufgaben"


def test_use_body_parent_rules():
    from hlidskjalf import galerie as g
    own = {3: {"id": 3}, 10: {"id": 10, "kind": "database"}, 4: {"id": 4, "archived": True}}
    assert g.use_body(3, True, own) == {"parent_id": 3, "examples": True}
    assert g.use_body(None, False, own) == {"parent_id": None, "examples": False}
    for bad in (-3, 0, 10, 4, 99, True, "3"):                 # foederiert, Datenbank, archiviert, fremd/unbekannt
        assert g.use_body(bad, 1, own) == {"parent_id": None, "examples": True}


def test_parse_use():
    from hlidskjalf import galerie as g
    assert g.parse_use({"root": 7, "created": [7, 8, "x", True], "neu": 1}) == (7, [7, 8])
    assert g.parse_use({"root": 7}) == (7, [])
    for bad in (None, [], {}, {"root": "7"}, {"root": True}, {"root": 0}, {"root": -2}):
        assert g.parse_use(bad) is None


def test_error_mapping():
    from hlidskjalf import galerie as g
    assert g.hidden_by(404) and g.hidden_by(403) and not g.hidden_by(400) and not g.hidden_by(500)
    assert "not available" in g.error_text(404, "not found", "list")
    assert "not available" in g.error_text(403, "", "list")
    assert g.error_text(400, "Unter dieser Seite geht das nicht.") == "Unter dieser Seite geht das nicht."
    assert g.error_text(403, "Dafür fehlt dir die Berechtigung.") == "Dafür fehlt dir die Berechtigung."
    assert g.error_text(403, "") == "You are not allowed to create pages there."
    assert "Too many" in g.error_text(429, "rate limited")
    assert "Nothing was created" in g.error_text(500, "Traceback ...")       # keine Server-Interna zeigen
    assert "could not be loaded" in g.error_text(502, "", "list")
    assert g.error_text(404, "not found") == "This template is no longer available."
    assert g.error_text(0, "") == "Server not reachable"
    assert g.error_text(418, "") == "The template could not be created."


def test_state_offered():
    from hlidskjalf.galerie import State
    s = State()
    assert not s.offered(SERVER)                       # unbekannt, Version unbekannt: erst fragen
    assert not s.offered(SERVER, "2.4.3")
    assert s.offered(SERVER, "2.5.0")
    s.set(SERVER, False)
    assert not s.offered(SERVER, "2.5.0")
    assert s.get("https://anders.test") is None and s.offered("https://anders.test", "2.6.0")
    s.set(SERVER, True)
    assert s.offered(SERVER, "1.0.0")


# --- Fenster und Dialog ------------------------------------------------------------------------
def _probed(win):
    from hlidskjalf.galerie import state
    pump(lambda: state.get(SERVER) is not None, 6)
    pump(None, 0.2)
    return state.get(SERVER)


def _dialog(win):
    from hlidskjalf.galeriedialog import GalleryDialog
    pump(lambda: isinstance(win.get_visible_dialog(), GalleryDialog), 3)
    d = win.get_visible_dialog()
    assert isinstance(d, GalleryDialog)
    pump(lambda: d.stack.get_visible_child_name() != "load", 6)
    return d


def _confirm(d):
    pump(lambda: getattr(d, "confirm_dialog", None) is not None, 3)
    pump(None, 0.2)
    return d.confirm_dialog


def test_entries_shown_when_available(win, srv):
    assert _probed(win) is True
    assert srv.gallery_lang == "en"
    assert win.new_split.get_visible() and not win.new_btn.get_visible()
    assert [a for a in win.palette_actions() if a[1] == "From template …"]


@pytest.mark.parametrize("code", [404, 403])
def test_entries_hidden_on_404_403(win, srv, code):
    srv.gallery_api = code
    from hlidskjalf.galerie import state
    state.set(None, None)
    win._gallery_probe()
    assert _probed(win) is False
    assert not win.new_split.get_visible() and win.new_btn.get_visible()
    assert not [a for a in win.palette_actions() if a[1] == "From template …"]
    n = len(srv.log)
    win.gallery_dialog()                              # Aktion ohne Galerie: nichts passiert
    pump(None, 0.3)
    assert win.get_visible_dialog() is None and len(srv.log) == n


def test_dialog_list_and_use(win, srv):
    assert _probed(win)
    win.gallery_dialog()
    d = _dialog(win)
    assert d.stack.get_visible_child_name() == "list"
    assert list(d.rows) == ["mein-tag", "leseliste", "aufgaben-projekte", "wiki"]   # persoenlich, Arbeit, Team; ungueltige fehlen
    assert d.rows["aufgaben-projekte"].get_title() == "Aufgaben & Projekte"           # kein Markup: & bleibt &
    d.rows["aufgaben-projekte"].emit("activated")
    c = _confirm(d)
    assert d.examples_row.get_active() and d.under_row is None                      # Heute-Ansicht: keine Elternseite
    c.emit("response", "use")
    pump(lambda: srv.gallery_used, 3)
    assert srv.gallery_used == [("aufgaben-projekte", {"parent_id": None, "examples": True})]
    pump(lambda: win.route == ("page", 100), 6)
    idle()
    assert win.route == ("page", 100)
    from hlidskjalf.store import store
    assert 100 in store.by_id and 101 in store.by_id                                # Seitenbaum neu geladen


def test_use_without_examples_under_current_page(win, srv):
    assert _probed(win)
    win.show_page(3)
    pump(lambda: win.page_id() == 3, 3)
    win.gallery_dialog()
    d = _dialog(win)
    d.rows["leseliste"].emit("activated")
    _c = _confirm(d)
    assert d.under_row is not None and not d.under_row.get_active()               # Neu-Menue: Unterseite nur auf Wunsch
    d.under_row.set_active(True)
    d.examples_row.set_active(False)
    _c.emit("response", "use")
    pump(lambda: srv.gallery_used, 3)
    assert srv.gallery_used == [("leseliste", {"parent_id": 3, "examples": False})]
    idle()
    assert srv.notes[100]["parent_id"] == 3 and 102 not in srv.notes


def test_sidebar_menu_subpage_from_template(win, srv):
    assert _probed(win)
    win.gallery_dialog(1)                              # wie Seitenmenue "Unterseite aus Vorlage ..."
    d = _dialog(win)
    d.rows["wiki"].emit("activated")
    c = _confirm(d)
    assert d.under_row.get_active()
    c.emit("response", "use")
    pump(lambda: srv.gallery_used, 3)
    assert srv.gallery_used[0][1]["parent_id"] == 1


def test_no_federated_or_database_parent(win, srv):
    assert _probed(win)
    from hlidskjalf.galeriedialog import GalleryDialog
    for pid in (-5, 10):                                # foederiert (negativ), Datenbank
        d = GalleryDialog(win, pid)
        assert d.parent is None
        d.close()
    idle()


def test_use_errors_as_toast(win, srv):
    assert _probed(win)
    toasts = []
    win.toast = toasts.append
    for code, want in ((429, "Too many"), (400, "Unter dieser Seite geht das nicht."), (500, "Nothing was created")):
        srv.gallery_used.clear()
        srv.gallery_use_fail = code
        win.gallery_dialog()
        d = _dialog(win)
        d.rows["wiki"].emit("activated")
        _confirm(d).emit("response", "use")
        pump(lambda: toasts, 3)
        assert toasts and want in toasts[-1], (code, toasts)
        toasts.clear()
        pump(lambda: win.get_visible_dialog() is None, 2)
    assert 100 not in srv.notes


def test_list_errors(win, srv):
    assert _probed(win)
    toasts = []
    win.toast = toasts.append
    srv.fail["GET api/gallery"] = 500
    win.gallery_dialog()
    pump(lambda: toasts, 4)
    assert "could not be loaded" in toasts[0]
    assert win.new_split.get_visible()                  # Serverfehler: Eintrag bleibt
    del srv.fail["GET api/gallery"]
    toasts.clear()
    srv.gallery_api = 404                               # Server inzwischen ohne Galerie: ausblenden
    win.gallery_dialog()
    pump(lambda: toasts, 4)
    assert "not available" in toasts[0]
    pump(lambda: win.new_btn.get_visible(), 2)
    assert not win.new_split.get_visible()


def test_empty_list(win, srv):
    assert _probed(win)
    srv.gallery = [{"id": "x/y", "title": "kaputt"}]
    win.gallery_dialog()
    d = _dialog(win)
    assert d.stack.get_visible_child_name() == "empty"
    d.close()
