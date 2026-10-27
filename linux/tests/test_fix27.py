"""Fix-Runde 27.10. (Saga 2.5, SPEC-fix27): #10 Formate, #11 Anlege-Schaltflaechen bei Lesen/Kommentieren,
Relationsfilter ohne Verknuepfung (wie Server 82e26d6)."""
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
