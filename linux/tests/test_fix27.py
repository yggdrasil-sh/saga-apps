"""Fix-Runde 27.10. (Saga 2.5, SPEC-fix27): #10 Formate, #11 Anlege-Schaltflaechen bei Lesen/Kommentieren,
Relationsfilter ohne Verknuepfung (wie Server 82e26d6)."""
import json

from gi.repository import Gtk

from hk_helpers import idle, pump


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _open(win, nid):
    win.show_root(("page", nid))
    v = win.current
    pump(lambda: v.doc.fetched, 6)
    pump(None, 0.4)
    idle()
    return v


# --- #10 Formate ------------------------------------------------------------------------------------------------
def test_percent_integer_and_currency_two_places():
    from hlidskjalf import pagewidgets as pw
    f = pw.fmt_value
    assert f(41.0377, "pct", "de") == "41 %" and f(41.0377, "pct", "en") == "41%"
    assert f(42.5, "pct", "en") == "43%" and f(119.2, "pct", "de") == "119 %" and f(-2.5, "pct", "en") == "-3%"
    assert f(1430.5, "eur", "de") == "1.430,50 €" and f(1430.5, "eur", "en") == "€1,430.50"
    assert f(3, "eur", "ru") == "3,00 €" and f(1200, "eur", "es") == "1200,00 €"


def test_num_text_uses_shared_formats():
    """Datenbank-Zellen (calc/number mit fmt): gleiche Regeln wie die Widgets, Sprache der Oberflaeche"""
    from hlidskjalf import pagewidgets as pw
    from hlidskjalf.util import num_text
    for v in (41.0377, 99.5, 119.0, 0):
        assert num_text(v, "percent") == pw.fmt_value(v, "pct") == num_text(v, "percent", "ring")
    assert num_text(1430.5, "euro") == pw.fmt_value(1430.5, "eur")
    assert "." not in num_text(41.0377, "percent") and "," not in num_text(41.0377, "percent")
    assert num_text(2.5) in ("2.5", "2,5")                       # Zahl ohne Format unveraendert


def test_progress_over_100_shows_real_value():
    """Fortschritt 1.430,5 von 1.200: Balken voll, Zahl echt (119 %), nicht gedeckelt"""
    from hlidskjalf import pagewidgets as pw
    spec = pw.Spec({"id": "w1", "data": {"kind": "progress", "title": "Budget", "cfg": {"src": "fixed", "value": 1430.5, "max": 1200}}})
    out = pw.present(spec, pw.Values(), lang="de")
    assert out["fraction"] == 1.0 and out["main"] == "119\u00a0%"
    spec = pw.Spec({"id": "w2", "data": {"kind": "progress", "cfg": {"src": "fixed", "value": 1, "max": 3}}})
    assert pw.present(spec, pw.Values(), lang="en")["main"] == "33%"


# --- Relationsfilter ------------------------------------------------------------------------------------------------
def test_relation_filter_rows_without_relation():
    """fehlende Relation = leere Liste bei enthaelt/enthaelt nicht (kein Textvergleich, kein Abbruch)"""
    from hlidskjalf.database import DBModel
    m = DBModel(10)
    m.props = [{"id": "pr", "name": "Projekt", "type": "relation", "db": 20}]
    m.rows = [{"id": 1, "title": "mit", "props": {"pr": [7, 12]}, "created": 0, "updated": 0},
              {"id": 2, "title": "ohne", "props": {}, "created": 0, "updated": 0},
              {"id": 3, "title": "null", "props": {"pr": None}, "created": 0, "updated": 0},
              {"id": 4, "title": "kaputt", "props": {"pr": "127"}, "created": 0, "updated": 0}]

    def ids(op, val="12"):
        return [r["id"] for r in m.rows_for({"filters": [{"pid": "pr", "op": op, "value": val}]})]
    assert ids("contains") == [1]
    assert ids("not_contains") == [2, 3, 4]
    assert ids("contains", "7") == [1] and ids("contains", 12) == [1]
    assert ids("is_empty") == [2, 3] and ids("not_empty") == [1, 4]


# --- #11 Lesen/Kommentieren: ausgegraut mit Hinweis ------------------------------------------------------------------
def test_blocked_hints_logic():
    from hlidskjalf.entries import blocked_button_hint, blocked_new_hint
    assert blocked_button_hint("db", True) and blocked_button_hint("db", False) is None
    assert blocked_button_hint("page", True) is None and blocked_button_hint("task", True) is None
    assert blocked_new_hint(True) and blocked_new_hint(False) is None


def _buttons(v):
    return [w for w in _walk(v.scroll.get_child()) if isinstance(w, Gtk.Button) and w.has_css_class("hk-action-btn")]


def test_button_block_greyed_for_viewers(win, srv):
    from hlidskjalf.store import store
    btns = {"buttons": [{"label": "Neue Aufgabe", "icon": "", "action": "db", "target": 201},
                        {"label": "Öffnen", "icon": "", "action": "page", "target": 201}]}
    srv.add_foreign(200, "Projekt", role="view", blocks=[{"id": "b1", "type": "buttons", "data": btns}])
    srv.add_foreign(210, "Team", role="edit", blocks=[{"id": "b2", "type": "buttons", "data": btns}])
    store.refresh()
    idle()
    v = _open(win, 200)
    new, op = _buttons(v)
    assert not new.get_sensitive() and "only view" in new.get_tooltip_text() and op.get_sensitive()
    n0 = len(srv.foreign) + len(srv.notes)
    new.emit("clicked")                                    # ausgegraut: auch ein Signal legt nichts an
    pump(None, 0.3)
    assert len(srv.foreign) + len(srv.notes) == n0
    v = _open(win, 210)
    assert all(b.get_sensitive() for b in _buttons(v))


def test_database_new_greyed_for_viewers(win, srv):
    from hlidskjalf.store import store
    sc = {"props": [{"id": "st", "name": "Status", "type": "text"}], "views": [{"id": "t", "name": "Tabelle", "type": "table"}]}
    srv.add_foreign(300, "Fremde DB", role="comment", kind="database", schema=sc, body="")
    srv.add_foreign(301, "Zeile", parent_id=300, props={"st": "x"})
    store.refresh()
    idle()
    v = _open(win, 300)
    from hlidskjalf.database import DatabaseView
    assert pump(lambda: any(isinstance(w, DatabaseView) and w.m.loaded for w in _walk(v)), 5)
    idle()
    dv = next(w for w in _walk(v) if isinstance(w, DatabaseView))
    assert dv.m.readonly and not dv.m.can_add
    head = [w for w in _walk(dv.bar.new_slot) if isinstance(w, Gtk.Button)]
    assert head and all(not b.get_sensitive() and b.get_tooltip_text() for b in head)
    news = [w for w in _walk(dv) if getattr(w, "hk_blocked", None)]
    assert len(news) >= 2 and all(not w.get_sensitive() for w in news)


# --- E1 Eintrags-Vorlagen fuer Bearbeitende -------------------------------------------------------------------------
def _foreign_db(srv, role):
    sc = {"props": [{"id": "st", "name": "Status", "type": "text"}], "views": [{"id": "t", "name": "Tabelle", "type": "table"}]}
    srv.add_foreign(400, "Team-DB", role=role, kind="database", schema=sc, body="")
    srv.add_foreign(401, "Zeile", parent_id=400, props={"st": "x"})
    srv.add_foreign(402, "Neues Buch", parent_id=400, template=True, props={"st": "neu"},
                    blocks=[{"id": "t1", "type": "paragraph", "data": {"text": "Aus der Vorlage"}}])


def _dbview(win, v):
    from hlidskjalf.database import DatabaseView
    assert pump(lambda: any(isinstance(w, DatabaseView) and w.m.loaded for w in _walk(v)), 5)
    idle()
    return next(w for w in _walk(v) if isinstance(w, DatabaseView))


def test_e1_editor_sees_templates_and_creates_from_one(win, srv):
    from gi.repository import Adw
    from hlidskjalf.store import store
    _foreign_db(srv, "edit")
    store.refresh()
    idle()
    v = _open(win, 400)
    dv = _dbview(win, v)
    assert [t["id"] for t in dv.m.templates] == [402] and [r["id"] for r in dv.m.rows] == [401]
    sb = next(w for w in _walk(dv.bar.new_slot) if isinstance(w, Adw.SplitButton))
    assert sb.get_sensitive()
    # Vorlagen verwalten bleibt beim Eigentuemer: kein Eintrag im Menue
    labels = [w.get_label() for w in _walk(sb.get_popover()) if isinstance(w, Gtk.Label)] if sb.get_popover() else []
    assert not any("Manage templates" in (x or "") for x in labels)
    dv.new_entry(402)
    assert pump(lambda: len([n for n in srv.foreign.values() if n.get("parent_id") == 400 and not n.get("template")]) == 2, 5)
    new = next(i for i, n in srv.foreign.items() if n.get("parent_id") == 400 and i not in (401, 402))
    assert srv.foreign[new]["title"] == "" and srv.foreign[new]["props"] == {"st": "neu"}
    assert pump(lambda: new in store.fresh, 3) and store.fresh[new]["placeholder"] == "Neues Buch"
    ok = [e for e in srv.log if e[0] == "POST" and "/api/notes/402/duplicate" in e[1]]
    assert ok and ok[-1][2] == {"parent_id": 400}


def test_e1_viewer_gets_no_templates(win, srv):
    from hlidskjalf.store import store
    _foreign_db(srv, "view")
    store.refresh()
    idle()
    dv = _dbview(win, _open(win, 400))
    assert dv.m.templates == [] and dv.m.readonly


# --- E4 unveraendert geschlossen -> endgueltig weg ------------------------------------------------------------------
def test_e4_logic():
    from hlidskjalf.entries import discard_ok, fresh_info
    assert fresh_info({"id": 5, "fresh": "abcdefgh12", "placeholder": " Neues Buch "}) == {"tok": "abcdefgh12", "placeholder": "Neues Buch", "title": ""}
    assert fresh_info({"id": 5}) is None and fresh_info({"fresh": "kurz"}) is None and fresh_info(None) is None
    f = {"tok": "abcdefgh12", "placeholder": ""}
    assert discard_ok(f, True, "", 0, False, False)
    assert not discard_ok(f, True, "Titel", 0, False, False) and not discard_ok(f, True, "", 1, False, False)
    assert not discard_ok(f, False, "", 0, False, False) and not discard_ok(None, True, "", 0, False, False)
    assert not discard_ok(f, True, "", 0, True, False) and not discard_ok(f, True, "", 0, False, True)
    assert not discard_ok(f, True, "  ", 0, False, False, read_only=True)
    t = fresh_info({"fresh": "abcdefgh12"}, " Tagebuch 27.10.2026 ")      # Schaltflaeche mit Titel: unveraendert -> weg (wie Web)
    assert discard_ok(t, True, "Tagebuch 27.10.2026", 0, False, False)
    assert not discard_ok(t, True, "", 0, False, False) and not discard_ok(t, True, "Tagebuch", 0, False, False)


def _own_tpl_db(srv):
    srv.notes[20] = {"id": 20, "title": "Buecher", "kind": "database", "format": "blocks", "body": "", "rev": 1, "parent_id": None,
                     "icon": "", "updated": 1.0, "created": 1.0,
                     "schema": {"props": [{"id": "st", "name": "Status", "type": "text"}],
                                "views": [{"id": "t", "name": "Tabelle", "type": "table", "open": "side"}]}}
    srv.notes[21] = {"id": 21, "title": "Neues Buch", "format": "blocks", "body": json.dumps({"blocks": [
        {"id": "x1", "type": "paragraph", "data": {"text": "Notizen"}}]}), "rev": 1, "parent_id": 20, "icon": "",
        "updated": 1.0, "created": 1.0, "template": True, "props": {"st": "lesen"}}


def test_e4_untouched_entry_is_discarded_on_close(win, srv):
    from hlidskjalf.store import store
    _own_tpl_db(srv)
    store.refresh()
    idle()
    dv = _dbview(win, _open(win, 20))
    dv.new_entry(21)
    assert pump(lambda: win.peek is not None and win.peek[1].doc.fetched, 6)
    pv = win.peek[1]
    new = pv.id
    assert srv.notes[new]["title"] == "" and new in store.fresh
    assert pv.title_rt.placeholder_text == "Neues Buch"
    pump(None, 0.3)
    win.close_peek()
    assert pump(lambda: new in srv.discarded, 5) and new not in srv.notes and not any(t.get("id") == new for t in srv.trash)
    assert new not in store.fresh


def test_e4_changed_entry_stays(win, srv):
    from hlidskjalf.store import store
    _own_tpl_db(srv)
    store.refresh()
    idle()
    dv = _dbview(win, _open(win, 20))
    dv.new_entry(21)
    assert pump(lambda: win.peek is not None and win.peek[1].doc.fetched, 6)
    pv = win.peek[1]
    new = pv.id
    pv.doc.set_title("Mein Buch")                      # Titel getippt
    win.close_peek()
    idle()
    pump(None, 0.3)
    assert new in srv.notes and srv.notes[new]["title"] == "Mein Buch" and new not in srv.discarded and new not in store.fresh
    # Eigenschaft geaendert (anderer Weg als der Editor): bleibt ebenfalls
    dv.new_entry(21)
    assert pump(lambda: win.peek is not None and win.peek[1].id not in (new,) and win.peek[1].doc.fetched, 6)
    other = win.peek[1].id
    store.run("PATCH", f"api/notes/{other}", {"props": {"st": "fertig"}})
    idle()
    win.close_peek()
    pump(None, 0.5)
    assert other in srv.notes and other not in srv.discarded


def test_e4_server_refuses_changed_entry(win, srv):
    """Server-Netz: unbemerkt geaendert (z. B. anderes Geraet) -> 409, Eintrag bleibt, keine Meldung"""
    from hlidskjalf.store import store
    _own_tpl_db(srv)
    store.refresh()
    idle()
    dv = _dbview(win, _open(win, 20))
    dv.new_entry(21)
    assert pump(lambda: win.peek is not None and win.peek[1].doc.fetched, 6)
    new = win.peek[1].id
    srv.notes[new]["props"]["st"] = "anderswo"
    win.close_peek()
    assert pump(lambda: any(e[0] == "POST" and e[1].endswith(f"/api/notes/{new}/discard") for e in srv.log), 4)
    idle()
    assert new in srv.notes and new not in srv.discarded


# --- E2 Startseite als Kontoeinstellung ------------------------------------------------------------------------------
def test_e2_logic():
    from hlidskjalf.entries import account_start, can_set_home, start_switch
    assert account_start({"username": "x"}) == (False, None) and account_start(None) == (False, None)
    assert account_start({"start_page": 7}) == (True, 7) and account_start({"start_page": None}) == (True, None)
    assert account_start({"start_page": True}) == (True, None) and account_start({"start_page": -3}) == (True, None)
    assert can_set_home(True, False, 5, False) and not can_set_home(False, False, 5, False) and can_set_home(False, False, 5, True)
    assert not can_set_home(True, True, -5, True) and not can_set_home(True, False, -5, True)
    vis = {3, 7}.__contains__
    assert start_switch(7, ("today",), True, vis) == ("page", 7) and start_switch(7, ("page", 7), True, vis) is None
    assert start_switch(7, ("today",), False, vis) is None and start_switch(9, ("today",), True, vis) is None
    assert start_switch(None, ("page", 3), True, vis) == ("today",)


def _new_win(app):
    from hlidskjalf.window import MainWindow
    w = MainWindow(app)
    w.present()
    pump(None, 0.3)
    idle()
    return w


def _close_win(w):
    w._close_ok = True
    for k in ("_live_event", "_session_started", "_session_ended", "_session_catch_up"):
        setattr(w, k, lambda *a, **kw: None)
    w.destroy()
    pump(None, 0.1)


def test_e2_cold_start_opens_account_start_page(app, srv):
    from hlidskjalf.store import store
    srv.start_api, srv.start_page = True, 3
    w = _new_win(app)
    try:
        assert pump(lambda: w.route == ("page", 3), 6) and store.server_home and store.home_page == 3
    finally:
        _close_win(w)


def test_e2_shared_page_menu_sets_and_resets(win, srv):
    from hlidskjalf.store import store
    srv.start_api = True
    srv.add_foreign(500, "Geteilt", role="view")
    store.refresh()
    idle()
    win.load_account()
    assert pump(lambda: store.server_home, 4)
    v = _open(win, 500)
    store.set_home(500)
    assert pump(lambda: srv.start_page == 500, 4) and store.home_page == 500
    store.set_home(None)
    assert pump(lambda: srv.start_page is None, 4) and store.home_page is None
    srv.start_api = False                                      # aelterer Server: nur lokal, kein PATCH
    store.server_home = False
    n = sum(1 for e in srv.log if e[0] == "PATCH" and e[1].endswith("/api/account"))
    store.set_home(3)
    pump(None, 0.3)
    assert store.home_page == 3 and n == sum(1 for e in srv.log if e[0] == "PATCH" and e[1].endswith("/api/account"))
    assert v is not None
