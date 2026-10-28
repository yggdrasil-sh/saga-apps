"""Saga 2.5 - App-Runde Etappe 1 (SPEC-apps-25-e1): Formeln/Rollups, Personen, Dateien, Status, Datum mit Uhrzeit,
relative Filter, Unteraufgaben, Tabellen-Block, Inline-Vertrag (Erhalt beim Zurueckschreiben), Diagramme, Zeitleiste,
Widgets Etappe 2. Logik gegen die Testvektoren des Servers (tests/vectors25), Oberflaeche gegen den Schein-Server."""
import datetime as dt
import json
import os
from zoneinfo import ZoneInfo

from gi.repository import Gtk

from hk_helpers import idle, pump

VEC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vectors25")
BERLIN = ZoneInfo("Europe/Berlin")
NY = ZoneInfo("America/New_York")


def _vec(name):
    with open(os.path.join(VEC, name), encoding="utf-8") as f:
        return json.load(f)


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def labels(w):
    return [x.get_label() for x in walk(w) if isinstance(x, Gtk.Label)]


# --- Formeln / Rollups ------------------------------------------------------------------------------------------------
def test_formula_vectors_display():
    """Werte je rtype aus tests/vectors/formula.json anzeigen; Fehler als "#Fehler" mit Erklaerung"""
    from hlidskjalf import dbvalues as dv
    d = _vec("formula.json")
    n = 0
    for c in d["cases"]:
        if "error" in c or "runtime" in c:                 # Fehler: "#Fehler" + Erklaerung (Code bekannt)
            code = c.get("error") or c.get("runtime")
            assert code in dv.FX_ERRORS and dv.fx_error_text(code) != dv.fx_error_text("zzz")
            continue
        if c.get("value") is None:                            # leer = Schluessel fehlt
            assert dv.fx_text({"rtype": c.get("rtype")}, None) == ""
            continue
        p = {"id": "f", "type": "formula", "rtype": c.get("rtype"), "fmt": None}
        txt = dv.fx_text(p, c["value"])
        if c.get("rtype") == "list":
            assert txt == ", ".join(str(x) for x in c["value"])
        elif c.get("rtype") == "checkbox":
            assert txt == ("✓" if c["value"] is True else "")
        elif c.get("rtype") == "text":
            assert txt == c["value"]
        elif c.get("rtype") == "number":
            assert txt != "" and "e" not in txt.lower().replace("ê", "")
        n += 1
    assert n > 60
    assert dv.eff_type({"type": "rollup", "rtype": "date"}) == "date"
    assert dv.eff_type({"type": "formula"}) == "text"
    assert dv.fx_error({"fx_err": {"f": "num"}}, "f") == "num" and dv.fx_error({}, "f") is None


def test_patch_answer_calc_values_and_never_write_computed():
    from hlidskjalf import dbvalues as dv
    row = {"props": {"a": 1, "f": 9, "wer": ["~x"]}, "fx_err": {"g": "num"}}
    ppl = dv.apply_patch_answer(row, {"calc": {"f": None, "g": 4}, "fx_err": {}, "values": {"wer": ["~abcdefghijklmnop"], "anh": []},
                                      "people": {"~abcdefghijklmnop": {"name": "Anna"}}})
    assert row["props"] == {"a": 1, "g": 4, "wer": ["~abcdefghijklmnop"]} and "fx_err" not in row
    assert ppl == {"~abcdefghijklmnop": {"name": "Anna"}}
    sc = {"props": [{"id": "f", "type": "formula"}, {"id": "r", "type": "rollup"}, {"id": "a", "type": "number"}]}
    assert dv.strip_computed(sc, {"f": 1, "r": 2, "a": 3}) == {"a": 3}


# --- Status / Gruppen -------------------------------------------------------------------------------------------------
STATUS = {"id": "st", "name": "Status", "type": "select", "status": True, "options": [
    {"id": "neu", "name": "Neu", "color": "gray", "group": "todo"}, {"id": "lauf", "name": "Läuft", "color": "blue", "group": "doing"},
    {"id": "warte", "name": "Wartet", "color": "yellow", "group": "doing"}, {"id": "fertig", "name": "Fertig", "color": "green", "group": "done"},
    {"id": "alt", "name": "Alt", "color": "red"}]}


def test_status_groups_and_moves():
    from hlidskjalf import dbvalues as dv
    assert dv.status_label(STATUS, STATUS["options"][0]) == "○ Neu"
    assert dv.status_label(STATUS, STATUS["options"][1]) == "◐ Läuft"
    assert dv.status_label(STATUS, STATUS["options"][3]) == "● Fertig"
    assert dv.option_group(STATUS, "alt") == "todo"                     # ohne group: todo
    assert [g for g, _o in dv.grouped_options(STATUS)] == ["todo", "doing", "done"]
    assert dv.done_option(STATUS) == "fertig"
    assert dv.move_to_group(STATUS, "warte", "doing") is None          # schon in der Gruppe: nichts aendern
    assert dv.move_to_group(STATUS, "neu", "doing") == "lauf"           # erste Option der Gruppe
    assert dv.match_status_group(STATUS, "warte", "open") and not dv.match_status_group(STATUS, "fertig", "open")
    assert not dv.match_status_group(STATUS, None, "todo")              # leer passt nie
    assert dv.match_status_group(STATUS, None, "quatsch")               # unbekannter Wert zaehlt nicht
    plain = dict(STATUS, status=False)
    assert dv.status_label(plain, plain["options"][0]) == "Neu"


def test_value_groups_like_server():
    from hlidskjalf import dbvalues as dv
    rows = [{"v": x} for x in ("Mittwoch", None, "Montag", "Sonntag", "Montag", "")]
    gs = dv.value_groups(rows, "text", lambda r: r["v"])
    assert [k for k, _r in gs] == [None, "Montag", "Mittwoch", "Sonntag"] and len(gs[0][1]) == 2
    gs = dv.value_groups([{"v": 3}, {"v": 1.5}, {"v": None}], "number", lambda r: r["v"])
    assert [k for k, _r in gs] == [None, 1.5, 3]
    gs = dv.value_groups([{"v": True}, {"v": None}], "checkbox", lambda r: r["v"])
    assert [(k, len(r)) for k, r in gs] == [(None, 0), (False, 1), (True, 1)]
    ppl = {"~b": {"name": "Bert"}, "~a": {"name": "Anna"}}
    gs = dv.person_groups([{"v": ["~b"]}, {"v": ["~a", "~b"]}, {"v": []}], lambda r: r["v"], ppl)
    assert [k for k, _r in gs] == [None, "~a,~b", "~b"]


# --- Datum mit Uhrzeit / Zeitraum ---------------------------------------------------------------------------------------
def test_datetime_parse_display_and_compose():
    from hlidskjalf import dbvalues as dv
    day = lambda d: d.isoformat()                               # noqa: E731
    tm = lambda h, m: "%02d:%02d" % (h, m)                     # noqa: E731
    assert dv.date_text("2026-10-27", BERLIN, day, tm) == "2026-10-27"
    assert dv.date_text("2026-10-27T14:30+01:00", BERLIN, day, tm) == "2026-10-27 14:30"
    assert dv.date_text("2026-10-27T14:30+01:00", NY, day, tm) == "2026-10-27 09:30"             # Zone des Betrachters
    assert dv.date_text("2026-10-27T14:30+01:00/2026-10-27T16:00+01:00", BERLIN, day, tm) == "2026-10-27 14:30 – 16:00"
    assert dv.date_text("2026-10-27/2026-10-29", BERLIN, day, tm) == "2026-10-27 – 2026-10-29"
    assert dv.original_text("2026-10-27T14:30+01:00", NY) == "2026-10-27T14:30+01:00"
    assert dv.original_text("2026-10-27T14:30+01:00", BERLIN) is None
    # Bangkok: 23:30+02:00 am 27. ist dort der 28.
    assert dv.day_span("2026-10-27T23:30+02:00", ZoneInfo("Asia/Bangkok")) == (dt.date(2026, 10, 28),) * 2
    assert dv.sort_key("2026-10-27", BERLIN) < dv.sort_key("2026-10-27T08:00+01:00", BERLIN)
    d = dt.date(2026, 10, 27)
    assert dv.compose(d, (14, 30), viewer=BERLIN) == "2026-10-27T14:30+01:00"
    assert dv.compose(dt.date(2026, 7, 1), (9, 0), viewer=BERLIN) == "2026-07-01T09:00+02:00"      # Sommerzeit
    assert dv.compose(d, (9, 0), d, (11, 0), viewer=BERLIN) == "2026-10-27T09:00+01:00/2026-10-27T11:00+01:00"
    assert dv.compose(d, None, dt.date(2026, 10, 29)) == "2026-10-27/2026-10-29"
    # Uhrzeit/Ende bewusst entfernt (gleicher Tag): Tag/Tag - nur den Tag zu schicken behielte die alte Uhrzeit
    assert dv.compose(d, old="2026-10-27T14:30+01:00") == "2026-10-27/2026-10-27"
    assert dv.compose(d, old="2026-10-27/2026-10-29") == "2026-10-27/2026-10-27"
    assert dv.compose(dt.date(2026, 10, 28), old="2026-10-27T14:30+01:00") == "2026-10-28"       # anderer Tag: nur der Tag
    assert dv.compose(d, old="2026-10-27") == "2026-10-27"
    assert dv.compose(d, (14, 30), full=False) == "2026-10-27"                                     # ohne datetime1: nur der Tag
    assert dv.valid_range(d, (10, 0), d, (9, 0)) is False and dv.valid_range(d, None, d, None)


def test_relative_filters_server_vectors():
    """Auszug aus tests/dbquery_vectors.json: relative Datumsfilter genau wie Server und Web"""
    from hlidskjalf import dbvalues as dv
    d = _vec("relfilter.json")
    assert len(d["cases"]) >= 50
    for c in d["cases"]:
        tz, today, f = ZoneInfo(c["tz"]), dt.date.fromisoformat(c["today"]), c["filter"]
        got = [int(i) for i, v in c["values"].items() if dv.match_day_filter(dv.day_span(v, tz), f["op"], f.get("value"), today)]
        assert len(got) == c["matched"] and set(c["ids"]) <= set(got), (f, c["tz"], c["today"])


def test_model_filters_sorts_with_new_types():
    from hlidskjalf.database import DBModel
    m = DBModel(10)
    m.today = dt.date(2026, 10, 28)
    m.props = [STATUS, {"id": "wer", "name": "Wer", "type": "person"}, {"id": "d", "name": "Datum", "type": "date"},
               {"id": "f", "name": "Summe", "type": "formula", "rtype": "number"}, {"id": "l", "name": "Tags", "type": "rollup", "rtype": "list"}]
    m.people = {"~me": {"name": "Ich", "me": True}, "~b": {"name": "Bert"}}
    m.rows = [{"id": 1, "title": "a", "props": {"st": "neu", "wer": ["~b"], "d": "2026-10-27T23:30+01:00", "f": 3, "l": ["rot"]}},
              {"id": 2, "title": "b", "props": {"st": "fertig", "wer": ["~me"], "d": "2026-10-29", "f": 1.5, "l": []}},
              {"id": 3, "title": "c", "props": {"st": "warte", "d": "2026-10-20/2026-10-30"}}]
    for r in m.rows:
        r.update(created=0, updated=0)

    def ids(*fs, sorts=None):
        return [r["id"] for r in m.rows_for({"filters": list(fs), "sorts": sorts or []})]
    assert ids({"pid": "st", "op": "status_group", "value": "open"}) == [1, 3]
    assert ids({"pid": "wer", "op": "me"}) == [2]
    assert ids({"pid": "wer", "op": "contains", "value": "~b"}) == [1]
    assert ids({"pid": "d", "op": "tomorrow"}) == [2, 3]              # Zeitraum beruehrt das Fenster
    assert ids({"pid": "d", "op": "next_days", "value": "3"}) == [2, 3]
    assert ids({"pid": "d", "op": "next_days", "value": "0"}) == [1, 2, 3]   # unvollstaendig: zaehlt nicht
    assert ids({"pid": "f", "op": "gt", "value": "2"}) == [1]
    assert ids({"pid": "l", "op": "contains", "value": "ro"}) == [1]
    assert ids(sorts=[{"pid": "wer", "dir": "asc"}]) == [1, 2, 3]       # Bert, Ich, leer zuletzt
    assert ids(sorts=[{"pid": "f", "dir": "desc"}]) == [1, 2, 3]


def test_subtasks_nest_like_server():
    from hlidskjalf import dbvalues as dv
    rows = [{"id": i, "props": p} for i, p in ((1, {}), (2, {"par": [1]}), (3, {"par": [2]}), (4, {}), (5, {"par": [5]}))]
    get = lambda r, pid: r["props"].get(pid)                      # noqa: E731
    out = dv.nest(rows, rows, {"parent": "par", "mode": "nested"}, get)
    assert [(r["id"], d) for r, d in out] == [(1, 0), (2, 1), (3, 2), (4, 0), (5, 0)]
    out = dv.nest(rows, [rows[2]], {"parent": "par", "mode": "nested"}, get)   # Treffer tief: Eltern-Kette bleibt
    assert [(r["id"], d) for r, d in out] == [(1, 0), (2, 1), (3, 2)]
    out = dv.nest(rows, rows, {"parent": "par", "mode": "parents_only"}, get)
    assert [r["id"] for r, _d in out] == [1, 4, 5]


# --- Personen / Dateien ------------------------------------------------------------------------------------------------
def test_people_and_files_logic():
    from hlidskjalf import dbvalues as dv
    ppl = {"~a": {"name": "Anna", "avatar": "/api/avatar/x", "me": True}, "~g": {"name": "x", "gone": True}}
    assert dv.person_label("~a", ppl) == "Anna (me)" or dv.person_label("~a", ppl).startswith("Anna")
    assert dv.person_info("~g", ppl)["gone"] and dv.person_info("~unbekannt", ppl)["gone"]
    assert dv.person_info("~a", ppl)["avatar"] == "/api/avatar/x"
    assert dv.person_info("~a", {"~a": {"name": "A", "avatar": "https://boese.example/x"}})["avatar"] is None
    assert dv.initials("Anna Berg") == "AB"
    fs = dv.file_items([{"url": "/files/abc", "name": "a.pdf", "size": 2048, "mime": "application/pdf"}, {"url": "javascript:x"}, "x"])
    assert len(fs) == 1 and fs[0]["name"] == "a.pdf" and dv.size_text(2048) == "2 KB"
    assert dv.files_for_patch(fs) == [{"url": "/files/abc", "name": "a.pdf"}]


# --- Inline-Vertrag ------------------------------------------------------------------------------------------------------
def test_inline_vectors_roundtrip():
    """gespeichertes HTML (sanitize-Ergebnis) -> Puffer -> HTML unveraendert (Farben, Erwaehnung, Inline-Mathe, Klassen)"""
    from hlidskjalf import richtext as R
    d = _vec("editor_inline.json")
    for name, _inp, out in d["sanitize"]:
        b = Gtk.TextBuffer()
        R.html_to_buffer(b, out)
        assert R.buffer_to_html(b) == out, name
    b = Gtk.TextBuffer()
    R.html_to_buffer(b, '<b>fett <span class="hk-c-red">rot</span></b> <span class="hk-mention" data-u="~abcdefghijklmnop">Anna</span>')
    b.insert(b.get_end_iter(), " neu")                               # Text am Ende anhaengen: Formen bleiben
    assert R.buffer_to_html(b) == '<b>fett </b><span class="hk-c-red"><b>rot</b></span> ' \
                                  '<span class="hk-mention" data-u="~abcdefghijklmnop">Anna</span> neu'
    assert R.inline_safe('<span class="hk-c-red">x</span><code class="hk-math">y</code>')
    assert not R.inline_safe('<sup>2</sup>')
    assert R.x_kind('<span class="hk-mention" data-u="@bob@b.example">')[1] == "@bob@b.example"


def test_inline_markup_colors():
    from hlidskjalf import richtext as R
    m = R.html_to_markup('<span class="hk-c-red">rot</span> <mark class="hk-bg-blue">b</mark> <code class="hk-math">x</code>')
    assert 'foreground="#c22a2a"' in m and "background=" in m and "<tt>x</tt>" in m


def test_table_block_fields():
    from hlidskjalf import gestalten as g
    d = {"colWidths": [50, 30, 20], "headCol": True, "colors": {"r": {"1": "blue"}, "c": {"2": "green"}, "x": {"2,1": "red"}}}
    assert g.table_widths(d, 3) == [0.5, 0.3, 0.2] and g.table_widths(d, 4) is None
    assert g.table_widths({"colWidths": [95, 5]}, 2) is None
    assert g.table_color(d, 1, 0) == "blue" and g.table_color(d, 0, 2) == "green" and g.table_color(d, 2, 1) == "red"
    assert g.table_color(d, 1, 2) == "blue"                       # Zeile vor Spalte
    assert g.table_color({"colors": {"r": {"1": "lila"}}}, 1, 0) is None
    assert g.table_head_col(d) and g.cols_per_row(6, 900) == 4 and g.cols_per_row(6, 1400) == 6 and g.cols_per_row(3, 300) == 3
    assert g.col_weights([{"w": 1}] * 5 + [{"w": 2}]) is not None and g.col_weights([{"w": 1}] * 7) is None


# --- Diagramme ------------------------------------------------------------------------------------------------------------
def test_chart_ticks_vectors():
    from hlidskjalf import chart as C
    for c in _vec("chart_ticks.json")["cases"]:
        assert C.nice_ticks(c["lo"], c["hi"]) == c["want"], c


def test_chart_parse_and_colors():
    from hlidskjalf import chart as C
    ch = C.parse({"type": "bar", "x_kind": "cat", "cats": [{"key": "h", "label": "Hoch", "color": "red"}, {"key": None, "label": ""},
                                                            {"key": "__other__", "label": ""}],
                  "series": [{"key": "", "label": "", "color": None, "values": [2, None, "x"]}], "total": 6, "fmt": "eur", "goal": 10})
    assert [c["label"] for c in ch["cats"]] == ["Hoch", "Leer", "Andere"] or [c["label"] for c in ch["cats"]][0] == "Hoch"
    assert ch["series"][0]["values"] == [2.0, None, None] and ch["fmt"] == "eur" and ch["goal"] == 10
    assert C.series_color(ch, 0, 0) == "red" and C.series_color(ch, 0, 1) == "blue"
    assert C.value_range(ch) == (0.0, 10.0)
    assert C.parse({"type": "zukunft", "cats": []})["type"] == "bar"
    assert C.parse_response({"chart": None, "error": "unsupported"}) == (None, "unsupported")
    st = C.parse({"type": "bar", "stack": True, "cats": [{"key": "a"}, {"key": "b"}],
                  "series": [{"key": "x", "values": [3, -1]}, {"key": "y", "values": [4, -2]}]})
    assert C.value_range(st) == (-3.0, 7.0)
    import cairo
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, 400, 260)
    for t in C.TYPES:
        C.draw(cairo.Context(surf), 400, 260, dict(st, type=t, total=7.0))


# --- Oberflaeche gegen den Schein-Server -------------------------------------------------------------------------------
def _setup_db(srv):
    srv.v25 = True
    srv.notes[10]["schema"] = {"props": [
        STATUS, {"id": "wer", "name": "Zuständig", "type": "person", "multi": True},
        {"id": "anh", "name": "Anhänge", "type": "files", "max": 3},
        {"id": "d", "name": "Termin", "type": "date", "time": True},
        {"id": "sum", "name": "Summe", "type": "formula", "rtype": "number", "fmt": "euro", "expr": "prop(\"x\") * 2"},
        {"id": "tg", "name": "Projekt-Tags", "type": "rollup", "rtype": "list", "rel": "r", "target": "title", "fn": "show"},
        {"id": "zk", "name": "Zukunft", "type": "zeitreise", "x": 1}],
        "views": [{"id": "tab", "name": "Tabelle", "type": "table", "wrap_cols": ["title"], "freeze": 1},
                  {"id": "brd", "name": "Board", "type": "board", "group_by": "st", "group_status": True,
                   "colors": [{"pid": "st", "op": "status_group", "value": "done", "color": "green"}]},
                  {"id": "cal", "name": "Woche", "type": "calendar", "date_by": "d", "range": "week"},
                  {"id": "ch", "name": "Diagramm", "type": "chart", "chart": {"type": "bar", "x": "st", "status": True}},
                  {"id": "tl", "name": "Zeitleiste", "type": "timeline", "date_by": "d"},
                  {"id": "lst", "name": "Liste", "type": "list"}]}
    srv.v25_people = {"~aaaaaaaaaaaaaaaa": {"name": "Anna Berg", "avatar": None, "me": True},
                      "~bbbbbbbbbbbbbbbb": {"name": "Bert", "avatar": None},
                      "~gggggggggggggggg": {"name": "", "gone": True}}
    today = dt.date.today()
    now = srv.notes[11]["created"]
    srv.notes[13] = {"id": 13, "title": "Zeile C", "format": "blocks", "body": srv.notes[11]["body"], "rev": 1, "parent_id": 10,
                     "updated": now + 1, "created": now + 1, "props": {}}
    rows = sorted((n for n in srv.notes.values() if n.get("parent_id") == 10), key=lambda n: n["id"])
    vals = [{"st": "neu", "wer": ["~aaaaaaaaaaaaaaaa"], "d": today.isoformat() + "T09:30+01:00",
             "anh": [{"url": "/files/aaaaaaaaaaaaaaaaaaaaaaaa", "name": "Plan.pdf", "size": 2048, "mime": "application/pdf"}]},
            {"st": "lauf", "wer": ["~bbbbbbbbbbbbbbbb", "~gggggggggggggggg"], "d": f"{today.isoformat()}/{(today + dt.timedelta(days=2)).isoformat()}"},
            {"st": "fertig"}]
    for n, v in zip(rows, vals + [{}] * 10):
        n["props"] = dict(n.get("props") or {}, **v)
        srv.v25_calc[n["id"]] = {"sum": 12.5, "tg": ["Alpha", "Beta"]}
    if rows:
        srv.v25_fx_err[rows[-1]["id"]] = {"sum": "num"}
        srv.v25_row_extra[rows[0]["id"]] = {"blocked": True}
    srv.v25_chart[(10, "ch")] = {"chart": {"type": "bar", "x_kind": "cat", "cats": [
        {"key": "todo", "label": "Zu erledigen", "color": "gray"}, {"key": "doing", "label": "In Arbeit", "color": "blue"},
        {"key": "done", "label": "Erledigt", "color": "green"}], "series": [{"key": "", "label": "", "color": None, "values": [1, 1, 1]}],
        "total": 3, "fmt": "num", "other": 0, "truncated": False, "rows": 3, "as_of": "2026-10-28T08:00:00Z"}, "view": "ch", "error": None}
    return rows


def _open_db(win, srv, vid=None):
    from hlidskjalf.database import DatabaseView
    from hlidskjalf.store import store
    store.check_version()
    pump(lambda: store.has("chart1"), 5)
    store.refresh()
    idle()
    win.show_root(("page", 10))
    v = win.current
    assert pump(lambda: any(isinstance(x, DatabaseView) and x.m.loaded for x in walk(v)), 6)
    dv_ = next(x for x in walk(v) if isinstance(x, DatabaseView))
    pump(lambda: dv_.m.rows, 6)
    if vid:
        dv_.set_view(vid)
    pump(None, 0.5)
    return dv_


def test_db_table_shows_new_types(win, srv):
    _setup_db(srv)
    dv_ = _open_db(win, srv, "tab")
    ls = labels(dv_)
    assert any("Anna Berg" in (x or "") for x in ls)                      # Name, nie Anmeldename
    assert any(x == "Gelöschte Person" or x == "Deleted person" for x in ls)
    assert any("12,50" in (x or "") or "12.50" in (x or "") for x in ls)   # Formel (Euro)
    assert any(x in ("#Error", "#Fehler") for x in ls)
    assert any(x == "⛔" for x in ls)
    assert any(x == "Plan.pdf" for x in ls)
    assert any((x or "").startswith("○") for x in ls)                      # Status-Symbol
    q = [x for x in srv.log if x[1].split("?")[0].endswith("/api/notes/10/rows")]
    assert q and "tz=" in q[-1][1]                                         # tz immer mitschicken


def test_db_set_status_keeps_computed_out(win, srv):
    rows = _setup_db(srv)
    dv_ = _open_db(win, srv, "tab")
    dv_.m.set(rows[0]["id"], "sum", 99)                                    # berechnet: nie senden
    dv_.m.set(rows[0]["id"], "st", "fertig")
    idle()
    sent = [x[2] for x in srv.log if x[0] == "PATCH" and x[1].endswith(f"/api/notes/{rows[0]['id']}")]
    assert sent == [{"props": {"st": "fertig"}}]
    r = next(x for x in dv_.m.rows if x["id"] == rows[0]["id"])
    assert r["props"]["sum"] == 12.5                                       # calc aus der PATCH-Antwort


def test_db_board_status_groups_drag(win, srv):
    rows = _setup_db(srv)
    dv_ = _open_db(win, srv, "brd")
    b = next(x for x in walk(dv_) if type(x).__name__ == "BoardView")
    heads = [x for x in labels(b) if x and x[0] in "○◐●"]
    assert heads[:3] == ["○ To do", "◐ In progress", "● Done"] or len(heads) >= 3
    assert any(getattr(x, "hk_color", None) == "green" for x in walk(b))  # Kartenfarbe
    g = dv_.m.prop("st")
    b.drop_to(rows[0]["id"], g, "doing", "status")                        # erste Option der Gruppe
    b.drop_to(rows[1]["id"], g, "doing", "status")                        # schon in der Gruppe: nichts
    idle()
    sent = [(x[1].rsplit("/", 1)[1], x[2]) for x in srv.log if x[0] == "PATCH" and "/api/notes/" in x[1]]
    assert (str(rows[0]["id"]), {"props": {"st": "lauf"}}) in sent and not any(i == str(rows[1]["id"]) for i, _b in sent)


def test_db_week_chart_timeline_list(win, srv):
    rows = _setup_db(srv)
    dv_ = _open_db(win, srv, "cal")
    assert any(type(x).__name__ == "CalendarView" and x.week for x in walk(dv_))
    assert any((x or "").endswith(rows[0]["title"]) and (x or "")[:1].isdigit() for x in labels(dv_))   # Uhrzeit vor dem Namen
    dv_.set_view("ch")
    cv = next(x for x in walk(dv_) if type(x).__name__ == "ChartView")
    pump(lambda: cv.ch is not None, 5)
    assert cv.ch["cats"][2]["label"] == "Erledigt" and cv.table is not None
    dv_.set_view("tl")
    tl = next(x for x in walk(dv_) if type(x).__name__ == "TimelineView")
    assert len(tl.items) == 2 and tl.none
    dv_.set_view("lst")
    assert any(x == "⛔" for x in labels(dv_))


def test_view_tabs_not_cut_off(win, srv):
    """Viele Ansichten: der letzte Reiter wird nicht abgeschnitten ("Zeitleist") - Werkzeuge rutschen in eine eigene Zeile"""
    win.set_default_size(1300, 950)
    _setup_db(srv)
    dv_ = _open_db(win, srv, "tl")
    pump(None, 0.8)
    assert sum(1 for x in walk(dv_.bar.tabs) if "hk-viewtab" in x.get_css_classes()) == 6
    sw = dv_.bar.tab_scroll
    nat = dv_.bar.tabs.measure(Gtk.Orientation.HORIZONTAL, -1)[1]
    assert sw.get_width() >= nat, (sw.get_width(), nat)


def test_person_picker_and_file_upload(win, srv, tmp_path):
    rows = _setup_db(srv)
    dv_ = _open_db(win, srv, "tab")
    from hlidskjalf.dbcells import PersonPicker, upload_file
    got = []
    pp = PersonPicker(10, dv_.m.prop("wer"), [], dv_.m.people, got.append)
    pump(lambda: pp.found, 5)
    assert {x["ref"] for x in pp.found} == {"~aaaaaaaaaaaaaaaa", "~bbbbbbbbbbbbbbbb"}
    q = [x[1] for x in srv.log if "/people" in x[1]]
    assert q and "/api/notes/10/people?q=" in q[-1]
    pp._toggle("~bbbbbbbbbbbbbbbb", True)
    pp.emit("closed")
    assert got == [["~bbbbbbbbbbbbbbbb"]]
    f = tmp_path / "notiz.txt"
    f.write_text("hallo")
    res = []
    upload_file(str(f), rows[0]["id"], done=res.append, error=res.append)
    pump(lambda: res, 5)
    assert res and res[0]["url"].startswith("/files/")
    hd = [h for h in srv.headers_log if "api/files" in h[1]]
    assert hd and hd[-1][2].get("X-Saga-Note") == str(rows[0]["id"])


def test_older_server_without_features(win, srv):
    """ohne features: alles wie bisher (kein tz an /rows, Datum ohne Uhrzeit-Picker)"""
    from hlidskjalf.store import store
    store.features = frozenset()
    from hlidskjalf.database import rows_path
    assert rows_path(10) == "api/notes/10/rows"
    from hlidskjalf import pagewidgets as pw
    old = pw.FEATURES
    pw.FEATURES = frozenset()
    try:
        assert not pw.Spec({"id": "w", "type": "widget", "data": {"kind": "weather"}}).known
    finally:
        pw.FEATURES = old


# --- Seite: Inline-Erhalt, Tabelle, Mathe, synced, 6 Spalten, Widgets Etappe 2 ------------------------------------------
PAGE = [
    {"id": "p1", "type": "paragraph", "data": {"text": 'Hallo <span class="hk-c-red">rot</span> und '
                                               '<span class="hk-mention" data-u="~aaaaaaaaaaaaaaaa">Anna</span> '
                                               '<code class="hk-math">x^2</code>'}},
    {"id": "p2", "type": "paragraph", "data": {"text": "mit <sup>hoch</sup> unbekannt"}},
    {"id": "t1", "type": "table", "data": {"withHeadings": True, "headCol": True, "colWidths": [60, 40], "zukunft": 1,
                                           "colors": {"r": {"1": "blue"}, "x": {"2,1": "red"}},
                                           "content": [["<b>Kopf</b>", "B"], ["x", "<i>y</i>"], ["z", "w"]]}},
    {"id": "m1", "type": "math", "data": {"tex": "\\frac{a}{b}", "render": {"png": "/files/aaaaaaaaaaaaaaaaaaaaaaaa", "h": "0000000000000000",
                                                                           "w": 31, "hgt": 57, "stale": True}}},
    {"id": "g1", "type": "code", "data": {"code": "graph TD; A-->B", "language": "mermaid", "view": "preview",
                                          "render": {"png": "/files/bbbbbbbbbbbbbbbbbbbbbbbb", "h": "c18237e0a535bdb7", "w": 168, "hgt": 138}}},
    {"id": "s1", "type": "synced", "data": {"sid": "AbCdEfGhIjKlMnOp", "src": {"note": 4, "role": "owner"},
                                            "blocks": [{"id": "s1a", "type": "paragraph", "data": {"text": "aus dem Original"}}]}},
    {"id": "c6", "type": "columns", "data": {"cols": [{"blocks": [{"id": f"c6{i}", "type": "paragraph", "data": {"text": f"Spalte {i}"}}]}
                                                      for i in range(6)]}},
    {"id": "w1", "type": "widget", "data": {"kind": "weather", "title": "Wetter", "cfg": {"place": "Hamburg", "lat": 53.55, "lon": 9.99}}},
    {"id": "w2", "type": "widget", "data": {"kind": "habit", "cfg": {"db": 10, "habits": ["sport"], "range": 7}}},
    {"id": "w3", "type": "widget", "data": {"kind": "upcoming", "cfg": {}}},
    {"id": "w4", "type": "widget", "data": {"kind": "chart", "cfg": {"db": 10, "type": "donut", "x": "st"}}},
]


def _page_setup(srv):
    srv.v25 = True
    srv.notes[3].update(body=json.dumps({"blocks": json.loads(json.dumps(PAGE))}), format="blocks")
    today = dt.date.today()
    days = [(today - dt.timedelta(days=6 - i)).isoformat() for i in range(7)]
    base = {"value": None, "max": None, "text": None, "fmt": None, "as_of": "2026-10-28T08:00:00Z", "error": None}
    srv.widget_values = {
        "w1": dict(base, kind="weather", value=12.3, data={"now": {"t": 12.3, "code": 61, "wind": 14}, "units": "metric", "stale": False,
                                                           "place": "Hamburg", "days": [{"d": d, "min": 6, "max": 13, "code": 3, "rain": 70}
                                                                                        for d in [today.isoformat()] + days[:5]]}),
        "w2": dict(base, kind="habit", data={"days": days, "today": days[-1], "can_check": True, "goal": 5,
                                             "habits": [{"pid": "sport", "name": "Sport", "done": [False, True, True, False, True, True, False],
                                                         "streak": 0, "pct": 57, "week": 3}]}),
        "w3": dict(base, kind="upcoming", data={"from": today.isoformat(), "days": 7, "more": 2, "errors": 0, "items": [
            {"k": "task", "title": "Steuer", "start": (today - dt.timedelta(days=1)).isoformat(), "allday": True, "overdue": True},
            {"k": "event", "title": "Zahnarzt", "start": today.isoformat() + "T14:00:00+01:00", "allday": False, "cal": "Privat"},
            {"k": "row", "title": "Abgabe", "start": (today + dt.timedelta(days=1)).isoformat(), "allday": True, "note": 10}]}),
        "w4": dict(base, kind="chart", value=3, data={"type": "donut", "x_kind": "cat", "cats": [
            {"key": "todo", "label": "Zu erledigen", "color": "gray"}, {"key": "done", "label": "Erledigt", "color": "green"}],
            "series": [{"key": "", "label": "", "color": None, "values": [2, 1]}], "total": 3, "fmt": "num"}),
    }


def _open_page(win):
    from hlidskjalf.store import store
    store.check_version()
    pump(lambda: store.has("chart1"), 5)
    store.refresh()
    idle()
    win.show_root(("page", 3))
    v = win.current
    pump(lambda: v.doc.fetched and v.kit.order, 6)
    pump(lambda: v.wvalues.state == "ok", 5)
    pump(None, 0.6)
    return v


def test_page_render_and_roundtrip(win, srv):
    _page_setup(srv)
    v = _open_page(win)
    rt = v.kit.by_key["p1"]
    assert rt.get_editable()
    assert not v.kit.by_key["p2"].get_editable()                      # unbekannte Form: nur im Web bearbeiten
    ls = labels(v)
    assert any("Preview outdated" in (x or "") or "Vorschau veraltet" in (x or "") for x in ls)
    assert any(getattr(x, "hk_render", None) == "/files/bbbbbbbbbbbbbbbbbbbbbbbb" for x in walk(v))
    assert any("Synced block" in (x or "") or "Synchronisiert" in (x or "") for x in ls)
    assert sum(1 for x in walk(v) if getattr(x, "hk_color", None) in ("blue", "red")) >= 2   # Tabellenfarben
    assert any(x == "Weather data: Open-Meteo.com" or "Open-Meteo" in (x or "") for x in ls)
    assert any(x in ("Overdue", "Überfällig") for x in ls)
    # Absatz bearbeiten: Inline-Formen bleiben, Tabelle/Mathe/synced/Widgets unveraendert
    buf = rt.get_buffer()
    buf.insert(buf.get_end_iter(), " Ende")
    v.editor.flush()
    assert pump(lambda: "Ende" in srv.notes[3]["body"], 6)
    idle()
    got = json.loads(srv.notes[3]["body"])["blocks"]
    want = json.loads(json.dumps(PAGE))
    want[0]["data"]["text"] += " Ende"
    assert got == want
    caps = [h[2].get("X-Saga-Caps") for h in srv.headers_log if h[0] == "PATCH" and h[1].endswith("/api/notes/3")]
    assert caps and caps[-1] == "inline2,cols6"


def test_table_cell_edit_keeps_fields(win, srv):
    _page_setup(srv)
    v = _open_page(win)
    tv = v.kit.cells.get(next(k for k in v.kit.cells if str(k).startswith("t1")))
    tv.get_buffer().set_text("neu")
    v.editor.flush()
    assert pump(lambda: "neu" in srv.notes[3]["body"], 6)
    idle()
    t = json.loads(srv.notes[3]["body"])["blocks"][2]
    want = json.loads(json.dumps(PAGE[2]))
    want["data"]["content"][0][0] = "neu"
    assert t == want                                                  # headCol/colWidths/colors/zukunft + Zellen-HTML bleiben


def test_habit_toggle_posts(win, srv):
    _page_setup(srv)
    v = _open_page(win)
    pump(lambda: any(getattr(x, "hk_habit", None) for x in walk(v)), 5)
    cell = next(x for x in walk(v) if getattr(x, "hk_habit", None) and not x.get_active())
    cell.set_active(True)
    pump(lambda: srv.v25_habit, 5)
    nid, bid, body = srv.v25_habit[-1]
    assert nid == 3 and bid == "w2" and body["pid"] == "sport" and body["on"] is True and len(body["day"]) == 10


def test_shots_e1(win, srv):
    """Bildschirmfotos (HK_E1_SHOTS=<ordner>): Datenbank-Ansichten und Seite, hell und dunkel"""
    out = os.environ.get("HK_E1_SHOTS")
    if not out:
        return
    from gi.repository import Adw
    from hlidskjalf.testing import shot
    os.makedirs(out, exist_ok=True)
    win.set_default_size(1300, 950)
    rows = _setup_db(srv)
    _page_setup(srv)
    for scheme, name in ((Adw.ColorScheme.FORCE_LIGHT, "hell"), (Adw.ColorScheme.FORCE_DARK, "dunkel")):
        Adw.StyleManager.get_default().set_color_scheme(scheme)
        if len(rows) >= 3:
            for vid in ("tab", "brd", "cal", "ch", "tl"):
                dv_ = _open_db(win, srv, vid)
                pump(None, 0.8)
                shot(win, os.path.join(out, f"e1-db-{vid}-{name}.png"))
        v = _open_page(win)
        pump(None, 1.2)
        shot(win, os.path.join(out, f"e1-seite-{name}.png"))
        adj = v.scroll.get_vadjustment()
        adj.set_value(adj.get_upper())
        pump(None, 0.8)
        shot(win, os.path.join(out, f"e1-seite-unten-{name}.png"))
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.DEFAULT)
    del dv_
