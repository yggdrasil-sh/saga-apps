"""Widgets in Seiten (2.5, SPEC-widgets Abschnitt 4): Logik (Countdown, Formate, Parsing, Fehlertexte, Darstellung),
Rundreise (Regel 0), Anzeige in der App mit dem Schein-Server (Spalten, Toggles, Fehlerzustaende, Neuladen)."""
import datetime as dt
import json
import random
from zoneinfo import ZoneInfo

from gi.repository import Gtk

from hk_helpers import idle, pump

BERLIN = ZoneInfo("Europe/Berlin")

# Beispiel-Seite: alle Arten, verschachtelt in Spalten und Toggle, plus unbekannte Art und Fremdes, das bleiben muss
SAMPLE = [
    {"id": "w_clock", "type": "widget", "data": {"v": 1, "kind": "clock", "size": "l", "style": "card", "accent": "blue",
                                                 "title": "Tokio", "cfg": {"tz": "Asia/Tokyo", "h24": True, "sec": True,
                                                                           "analog": True}}},
    {"id": "w_cd", "type": "widget", "data": {"v": 1, "kind": "countdown", "title": "Urlaub",
                                              "cfg": {"date": "2099-01-01", "time": "", "dir": "until", "unit": "days"},
                                              "zukunft": {"x": 1}}, "tunes": {"fremd": [1]}},
    {"id": "c1", "type": "columns", "data": {"cols": [
        {"blocks": [{"id": "w_metric", "type": "widget", "data": {"v": 1, "kind": "metric", "size": "s", "accent": "green",
                                                                  "title": "Summe", "cfg": {"db": 10, "view": "", "agg": "sum",
                                                                                            "prop": "nr", "fmt": "eur"}}}]},
        {"blocks": [{"id": "w_prog", "type": "widget", "data": {"v": 1, "kind": "progress", "style": "plain",
                                                                "title": "Erledigt", "cfg": {"src": "db", "db": 10, "view": "",
                                                                                             "mode": "checked", "prop": "ok"}}}]}]}},
    {"id": "t1", "type": "toggle", "data": {"title": "Mehr", "open": True, "blocks": [
        {"id": "w_quote", "type": "widget", "data": {"v": 1, "kind": "quote", "title": "Zitat",
                                                     "cfg": {"src": "fixed", "items": ["Carpe diem", "Nulla dies sine linea"],
                                                             "pick": "daily"}}},
        {"id": "w_fix", "type": "widget", "data": {"v": 1, "kind": "progress", "cfg": {"src": "fixed", "value": 3, "max": 4}}}]}},
    {"id": "w_new", "type": "widget", "data": {"v": 2, "kind": "weather", "cfg": {"city": "X"}}},
    {"id": "p1", "type": "paragraph", "data": {"text": "Ende"}},
]


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def _page(srv, nid=3, blocks=None):
    srv.notes[nid].update(body=json.dumps({"blocks": json.loads(json.dumps(blocks or SAMPLE))}), format="blocks")


def _open(win, nid=3):
    win.show_root(("page", nid))
    v = win.current
    pump(lambda: v.doc.fetched and v.kit.order, 6)
    pump(None, 0.4)
    return v


def _card(v, bid):
    from hlidskjalf.pwcard import WidgetCard
    row = next(r for r in v.kit.rows() if r.bid == bid)
    return next(w for w in walk(row) if isinstance(w, WidgetCard))


def _texts(card):
    return [w.get_label() for w in walk(card) if isinstance(w, Gtk.Label) and w.get_visible() and w.get_label()]


def _spec(i):
    from hlidskjalf import pagewidgets as pw
    return pw.Spec(i)


# --- Logik (ohne GTK) ----------------------------------------------------------------------------------
def test_spec_defaults_and_collect():
    from hlidskjalf import pagewidgets as pw
    s = pw.Spec({"id": "a", "type": "widget", "data": {"kind": "clock", "size": "xl", "style": "rund", "accent": "#fff",
                                                      "title": "  T &amp; U " + "x" * 100}})
    assert (s.size, s.style, s.accent) == ("m", "card", None)
    assert s.title.startswith("T & U") and len(s.title) == 80
    assert pw.Spec({"data": "kaputt"}).cfg == {} and not pw.Spec({}).known
    ids = [b["id"] for b in pw.collect(SAMPLE)]
    assert ids == ["w_clock", "w_cd", "w_metric", "w_prog", "w_quote", "w_fix", "w_new"]
    assert pw.needs_server(SAMPLE)
    assert not pw.needs_server([SAMPLE[0], SAMPLE[1]])                         # nur Uhr/Countdown: kein Abruf
    assert [pw.Spec(b).server_value for b in pw.collect(SAMPLE)] == [False, False, True, True, True, False, False]


def test_parse_response_and_states():
    from hlidskjalf import pagewidgets as pw
    st, vals = pw.parse_response({"widgets": {
        "a": {"kind": "metric", "value": 12.5, "max": None, "text": None, "fmt": "eur", "as_of": "2026-10-27T10:00:00Z", "error": None},
        "b": {"kind": "metric", "value": "12", "fmt": "geld", "error": "no_access"},
        "c": {"kind": "quote", "value": True, "text": 5, "error": "neu"},
        "d": "kaputt"}})
    assert st == "ok" and set(vals) == {"a", "b", "c"}
    assert vals["a"] == {"kind": "metric", "value": 12.5, "max": None, "text": None, "fmt": "eur",
                         "as_of": "2026-10-27T10:00:00Z", "error": None}
    assert vals["b"]["value"] is None and vals["b"]["fmt"] is None and vals["b"]["error"] == "no_access"
    assert vals["c"]["value"] is None and vals["c"]["text"] is None and vals["c"]["error"] == "other"
    assert pw.parse_response({"widgets": {}, "unsupported": True}) == ("unsupported", {})
    assert pw.parse_response([1]) == ("failed", {})
    assert pw.parse_response({}) == ("ok", {})
    assert [pw.error_state(c) for c in (404, 0, 500, 403)] == ["old", "offline", "failed", "failed"]
    # Fehler beim Neuladen verdraengt vorhandene Werte nicht
    v = pw.Values()
    assert v.apply("offline") and v.state == "offline"
    v.apply("ok", {"a": vals["a"]})
    assert not v.apply("failed") and v.state == "ok" and v.get("a") and v.stale
    v.apply("ok", {})
    assert not v.stale and v.get("a") is None


def test_number_formats():
    from hlidskjalf import pagewidgets as pw
    f = pw.fmt_value
    assert f(1234.5, "num", "en") == "1,234.5" and f(1234.5, "num", "de") == "1.234,5"
    assert f(1234567, "num", "ru") == "1 234 567" and f(1234, "num", "es") == "1234" and f(12345, "num", "es") == "12.345"
    assert f(2, "num", "de") == "2" and f(0.5, "num", "en") == "0.5" and f(1 / 3, "num", "en") == "0.33"
    assert f(42, "pct", "en") == "42%" and f(42.75, "pct", "de") == "43\u00a0%"            # Fix-Runde 27.10.: Prozent ganzzahlig
    assert f(1234.5, "eur", "de") == "1.234,50 €" and f(1234.5, "eur", "en") == "€1,234.50"
    assert f(-3, "eur", "en") == "-€3.00" and f(-0.001, "num", "de") == "0"
    assert f(None, "num", "de") == "–" and f(7, "unbekannt", "th") == "7"


def test_clock_logic():
    from hlidskjalf import pagewidgets as pw
    now = dt.datetime(2026, 10, 27, 13, 5, 9, tzinfo=dt.timezone.utc)
    c = pw.clock_cfg({"tz": "Asia/Tokyo", "h24": True, "sec": True})
    assert pw.clock_text(pw.clock_now(c, now, BERLIN), c) == "22:05:09"
    c = pw.clock_cfg({"tz": "", "h24": False})                                 # Betrachter-Zone, 12 Stunden
    t = pw.clock_now(c, now, BERLIN)
    assert pw.clock_text(t, c).startswith("2:05 ") and not c["sec"]
    c = pw.clock_cfg({"tz": "Mond/Krater"})                                    # ungueltig -> Betrachter
    assert pw.clock_now(c, now, BERLIN).hour == 14 and c["h24"]
    assert pw.zone_label("America/New_York") == "New York" and pw.zone_label("") == "" and pw.zone_label("../etc") == ""
    assert pw.hand_angles(dt.datetime(2026, 1, 1, 15, 30, 0)) == (105.0, 180.0, 0.0)
    assert pw.next_tick_ms(10_250, True) == 750 and pw.next_tick_ms(61_000, False) == 59_000


def test_countdown_logic():
    from hlidskjalf import pagewidgets as pw
    now = dt.datetime(2026, 10, 27, 22, 30, tzinfo=BERLIN)
    cd = pw.countdown_cfg
    r = pw.countdown(cd({"date": "2026-10-30", "dir": "until"}), now)
    assert r["days"] == 3 and not r["done"] and pw.countdown_text(r, False) in ("in 3 days", "in 3 Tagen")
    r = pw.countdown(cd({"date": "2026-10-27"}), now)
    assert r["today"]
    r = pw.countdown(cd({"date": "2026-10-20"}), now)
    assert r["done"] and r["days"] == 0
    r = pw.countdown(cd({"date": "2026-10-20", "dir": "since"}), now)
    assert r["days"] == 7 and not r["done"]
    r = pw.countdown(cd({"date": "2026-11-01", "dir": "since"}), now)           # Start noch in der Zukunft
    assert r["done"]
    r = pw.countdown(cd({"date": "2026-10-28", "time": "01:15", "unit": "auto"}), now)
    assert (r["days"], r["hours"], r["minutes"]) == (None, 2, 45)
    r = pw.countdown(cd({"date": "2026-10-28", "time": "01:15", "unit": "days"}), now)
    assert r["days"] == 0 and not r["done"]
    r = pw.countdown(cd({"date": "2026-10-30", "time": "23:00", "unit": "auto"}), now)
    assert r["days"] == 3
    r = pw.countdown(cd({"date": "2026-10-27", "time": "20:00", "unit": "auto", "dir": "since"}), now)
    assert (r["hours"], r["minutes"]) == (2, 30)
    r = pw.countdown(cd({"date": "2026-10-27", "time": "20:00"}), now)
    assert r["done"]
    assert pw.countdown(cd({"date": "2026-13-01"}), now) is None and pw.countdown(cd({}), now) is None
    assert cd({"date": "2026-10-27", "time": "25:00"})["time"] is None


def test_progress_and_quote_logic():
    from hlidskjalf import pagewidgets as pw
    fx = _spec({"id": "p", "data": {"kind": "progress", "cfg": {"src": "fixed", "value": 5, "max": 4}}})
    assert pw.progress(fx, None) == (5.0, 4.0) and pw.fraction(5, 4) == 1.0
    assert pw.progress(_spec({"data": {"kind": "progress", "cfg": {"src": "fixed", "value": 1, "max": 0}}}), None) is None
    db = _spec({"id": "p", "data": {"kind": "progress", "cfg": {"src": "db", "goal": 10}}})
    assert pw.progress(db, {"value": 4.0, "max": None}) == (4.0, 10.0) and pw.progress(db, None) is None
    assert pw.progress(db, {"value": 4.0, "max": 8.0}) == (4.0, 8.0)
    big = _spec({"id": "p", "data": {"kind": "progress", "cfg": {"src": "fixed", "value": 10 ** 400, "max": float("nan")}}})
    assert pw.progress(big, None) is None and pw.parse_value({"value": 10 ** 400})["value"] is None   # kein OverflowError
    assert pw.quote_items({"items": ["  a ", "", 3, "b" * 400] + ["c"] * 30})[:3] == ["a", "b" * 300, "c"]
    assert len(pw.quote_items({"items": ["c"] * 30})) == 20
    assert pw.fnv1a("") == 0x811C9DC5 and pw.fnv1a("a") == 0xE40C292C          # Testvektoren FNV-1a 32
    d = dt.date(2026, 10, 27)
    i = pw.quote_index(7, "daily", d, "blk")
    assert i == pw.quote_index(7, "daily", d, "blk") == pw.fnv1a("2026-10-27:blk") % 7
    assert pw.quote_index(0, "daily", d, "x") == -1
    assert pw.quote_index(5, "open", d, "x", random.Random(1)) == random.Random(1).randrange(5)


def test_error_texts():
    from hlidskjalf import pagewidgets as pw
    texts = {e: pw.error_text(e) for e in ("no_access", "gone", "bad_cfg", "too_big", "other")}
    assert len(set(texts.values())) == 5 and all(texts.values())
    assert all(pw.state_text(s) for s in ("old", "unsupported", "offline", "failed", "none"))
    assert len({pw.state_text(s) for s in ("old", "unsupported", "offline", "failed")}) == 4


def test_present_sample():
    """Darstellung mit Beispiel-JSON (alle Arten, Fehler, unbekannte Art)"""
    from hlidskjalf import pagewidgets as pw
    now = dt.datetime(2026, 10, 27, 13, 5, 9, tzinfo=dt.timezone.utc)
    vals = pw.Values()
    vals.apply(*pw.parse_response({"widgets": {
        "w_metric": {"kind": "metric", "value": 8, "fmt": "eur", "error": None},
        "w_prog": {"kind": "progress", "value": 1, "max": 2, "error": None},
        "w_quote": {"kind": "quote", "text": "Vom Server", "error": None}}}))
    specs = {b["id"]: pw.Spec(b) for b in pw.collect(SAMPLE)}

    def p(bid, v=vals):
        return pw.present(specs[bid], v, now=now, viewer=BERLIN, lang="de")
    c = p("w_clock")
    assert c["main"] == "22:05:09" and c["sub"] == "Tokyo" and c["clock"]["analog"] and c["title"] == "Tokio"
    assert p("w_cd")["main"] and not p("w_cd")["notice"]
    assert p("w_metric")["main"] == "8,00 €"
    pr = p("w_prog")
    assert pr["fraction"] == 0.5 and pr["main"] == "50 %" and "1" in pr["sub"] and "2" in pr["sub"]
    assert p("w_quote")["main"] == "Vom Server" and p("w_quote")["quote"]
    assert p("w_fix")["fraction"] == 0.75
    n = p("w_new")
    assert n["notice"] and n["main"] is None and n["title"] == ""
    # ohne Server-Werte: Zitat lokal nach pick (Rueckfall), Kennzahl mit Hinweis
    old = pw.Values()
    old.apply("old")
    q = pw.present(specs["w_quote"], old, now=now, viewer=BERLIN)
    assert q["main"] in ("Carpe diem", "Nulla dies sine linea")
    assert pw.present(specs["w_metric"], old, now=now, viewer=BERLIN)["notice"] == pw.state_text("old")
    # Fehler eines einzelnen Widgets
    e = pw.Values()
    e.apply("ok", {"w_metric": pw.parse_value({"kind": "metric", "error": "too_big"})})
    r = pw.present(specs["w_metric"], e, now=now, viewer=BERLIN)
    assert r["error"] and r["notice"] == pw.error_text("too_big")
    # Wert fehlt in der Antwort (z. B. Seite noch nicht gespeichert)
    m = pw.present(specs["w_metric"], pw.Values(), now=now, viewer=BERLIN)
    assert m["notice"] == pw.state_text("none")
    k = pw.Values()
    k.apply("ok", {})
    assert pw.present(specs["w_metric"], k, now=now, viewer=BERLIN)["notice"] not in (pw.state_text("old"), None)
    bad = pw.Spec({"id": "x", "data": {"kind": "countdown", "cfg": {"date": "morgen"}}})
    assert pw.present(bad, None, now=now, viewer=BERLIN)["error"]


# --- Regel 0: Rundreise ------------------------------------------------------------------------------------
def test_roundtrip_keeps_widgets(win, srv):
    """laden -> anderen Block aendern -> speichern: Widget-Bloecke (auch verschachtelt, unbekannte Art) unveraendert"""
    _page(srv)
    v = _open(win)
    rt = v.kit.by_key["p1"]
    rt.get_buffer().set_text("Ende geaendert")
    v.editor.flush()
    assert pump(lambda: "Ende geaendert" in srv.notes[3]["body"], 6)
    idle()
    got = json.loads(srv.notes[3]["body"])["blocks"]
    want = json.loads(json.dumps(SAMPLE))
    want[-1]["data"]["text"] = "Ende geaendert"
    assert got == want


def test_clipboard_keeps_widget():
    from hlidskjalf import blockclip
    data = blockclip.payload([SAMPLE[1]], "https://saga.test", "test")
    (b, _hk), = blockclip.parse(data)
    assert b["type"] == "widget" and b["data"] == SAMPLE[1]["data"] and b["tunes"] == SAMPLE[1]["tunes"]


# --- Anzeige in der App ---------------------------------------------------------------------------------------
def test_render_all_kinds(win, srv):
    from hlidskjalf.pwcard import WidgetCard
    srv.notes[12]["props"]["ok"] = True
    _page(srv)
    v = _open(win)
    assert pump(lambda: v.wvalues.state == "ok", 4)
    pump(None, 0.2)
    gets = [u for m, u, *_r in srv.log if m == "GET" and "/widgets" in u]
    assert gets and "tz=" in gets[0]
    cards = [w for w in walk(v.scroll) if isinstance(w, WidgetCard)]
    assert len(cards) == 7                                                   # auch in Spalten und im Toggle
    assert not [w for w in walk(v.scroll) if isinstance(w, Gtk.Widget) and w.has_css_class("hk-fallback")]
    clock = _card(v, "w_clock")
    assert clock.analog is not None and clock.has_css_class("hk-wacc-blue") and clock.has_css_class("hk-widget-l")
    assert clock._timer is not None                                           # tickt, solange sichtbar
    metric = _card(v, "w_metric")
    assert any("8" in t and "€" in t for t in _texts(metric)), _texts(metric)  # 3 + 5 aus der Datenbank
    prog = _card(v, "w_prog")
    assert prog.has_css_class("hk-widget-plain") and prog.bar.get_visible() and abs(prog.bar.get_fraction() - 0.5) < 1e-6
    assert _card(v, "w_quote").shown["main"] == "Carpe diem"                  # Server-Text (Mock: erstes Zitat)
    assert abs(_card(v, "w_fix").bar.get_fraction() - 0.75) < 1e-6
    newer = _card(v, "w_new")
    assert newer.shown["notice"] in _texts(newer)
    # Uhr tickt: Anzeige aendert sich ohne Zutun
    first = clock.main_lb.get_label()
    pump(lambda: clock.main_lb.get_label() != first, 2.5)
    assert clock.main_lb.get_label() != first
    # unsichtbar: Timer weg
    win.show_root(("page", 1))
    pump(None, 0.3)
    assert clock._timer is None


def test_states_old_server_unsupported_offline(win, srv):
    from hlidskjalf import pagewidgets as pw
    srv.widgets_api = False
    _page(srv)
    v = _open(win)
    assert pump(lambda: v.wvalues.state == "old", 4)
    pump(None, 0.1)
    assert pw.state_text("old") in _texts(_card(v, "w_metric"))
    assert _card(v, "w_clock").shown["main"]                                   # lokale Arten unberuehrt
    srv.widgets_api = "unsupported"
    v.window_active()
    assert pump(lambda: v.wvalues.state == "unsupported", 4)
    pump(None, 0.1)
    assert pw.state_text("unsupported") in _texts(_card(v, "w_prog"))


def test_reload_error_keeps_values(win, srv):
    """Fokus zurueck -> neu laden; schlaegt das fehl, bleiben die Werte stehen (mit Hinweis)"""
    _page(srv)
    v = _open(win)
    assert pump(lambda: v.wvalues.state == "ok", 4)
    pump(None, 0.1)
    m = _card(v, "w_metric")
    before = m.shown["main"]
    assert before and "€" in before
    srv.fail["GET api/notes/3/widgets"] = 0
    v.window_active()
    assert pump(lambda: v.wvalues.stale, 4)
    pump(None, 0.1)
    assert m.shown["main"] == before and m.stale_lb.get_visible()
    del srv.fail["GET api/notes/3/widgets"]
    srv.widget_values["w_metric"] = {"kind": "metric", "value": 99, "fmt": "num", "error": None}
    v.window_active()
    assert pump(lambda: not v.wvalues.stale and m.shown["main"] == "99", 4)
    assert not m.stale_lb.get_visible()
    srv.widget_values["w_metric"] = {"kind": "metric", "value": None, "fmt": None, "error": "no_access"}
    v.window_active()
    from hlidskjalf import pagewidgets as pw
    assert pump(lambda: m.shown["notice"] == pw.error_text("no_access"), 4)


def test_no_fetch_without_server_widgets(win, srv):
    _page(srv, blocks=[SAMPLE[0], SAMPLE[1]])
    v = _open(win)
    pump(None, 0.3)
    assert not [u for m, u, *_r in srv.log if "/widgets" in u]
    assert v.wvalues.state == "none"


def test_block_menu_edit_on_web(win, srv):
    from hlidskjalf import blocks as blocksmod
    _page(srv)
    v = _open(win)
    seen = []
    orig = blocksmod.menu_popover

    class _P:
        def popup(self):
            pass
    blocksmod.menu_popover = lambda parent, ents: (seen.append(ents), _P())[1]
    try:
        v.kit.block_menu(v, "w_metric")
        v.kit.block_menu(v, "p1")
    finally:
        blocksmod.menu_popover = orig
    w_ents = [e for e in seen[0] if e]
    assert w_ents[0][0] in ("Edit on the web", "Im Web bearbeiten")
    assert not any(e[0] in ("Turn into", "Umwandeln in") for e in w_ents)
    opened = []
    win.open_uri = opened.append
    w_ents[0][1]()
    assert opened == ["https://saga.test/notes#3:w_metric"]
    assert not any(e and e[0] in ("Edit on the web", "Im Web bearbeiten") for e in seen[1])


def test_shots_light_dark(win, srv):
    """Bildschirmfotos zur Sichtpruefung (HK_WID_SHOTS=<ordner>), hell und dunkel"""
    import os
    out = os.environ.get("HK_WID_SHOTS")
    if not out:
        return
    from gi.repository import Adw
    from hlidskjalf.testing import shot
    srv.notes[12]["props"]["ok"] = True
    _page(srv)
    win.set_default_size(1300, 1100)
    for scheme, name in ((Adw.ColorScheme.FORCE_LIGHT, "hell"), (Adw.ColorScheme.FORCE_DARK, "dunkel")):
        Adw.StyleManager.get_default().set_color_scheme(scheme)
        v = _open(win)
        pump(lambda: v.wvalues.state == "ok", 4)
        pump(None, 1.0)
        shot(win, os.path.join(out, f"widgets-{name}.png"))
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.DEFAULT)
