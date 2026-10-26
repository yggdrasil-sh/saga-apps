"""Seite gestalten (2.5, SPEC-gestalten): Regel 0 (Rundreise), Darstellung der neuen Felder, Rueckfall-Karte, Bedienung."""
import json

from gi.repository import Gtk

from hk_helpers import idle, pump

# Beispiel-Seite mit allen neuen Feldern und Fremdem, das erhalten bleiben muss
SAMPLE = [
    {"id": "p1", "type": "paragraph", "data": {"text": "Erster Absatz", "zukunft": {"x": 1}},
     "tunes": {"hkstyle": {"c": "red"}, "fremd": {"a": [1, 2]}}},
    {"id": "h1", "type": "header", "data": {"text": "Titel", "level": 2}, "tunes": {"hkstyle": {"c": "blue", "bg": True}}},
    {"id": "c1", "type": "columns", "data": {"cols": [
        {"w": 0.7, "blocks": [{"id": "c1a", "type": "paragraph", "data": {"text": "Links"}, "tunes": {"hkstyle": {"c": "green"}}}]},
        {"w": 0.3, "blocks": [{"id": "c1b", "type": "paragraph", "data": {"text": "Rechts"}}], "neu": "bleibt"}]}},
    {"id": "i1", "type": "image", "data": {"file": {"url": "https://saga.test/files/x.png"}, "caption": "", "width": 40,
                                           "align": "right", "stretched": False}},
    {"id": "e1", "type": "embed", "data": {"url": "https://www.youtube.com/watch?v=abcdefghijk", "kind": "youtube", "width": 60,
                                           "align": "left"}},
    {"id": "t1", "type": "toggle", "data": {"title": "Aufklapp-Ueberschrift", "level": 1, "open": True,
                                            "blocks": [{"id": "t1a", "type": "paragraph", "data": {"text": "innen"},
                                                        "tunes": {"hkstyle": {"c": "purple"}}}]}},
    {"id": "u1", "type": "zukunftsblock", "data": {"irgendwas": [1, {"b": 2}], "text": "nicht anzeigbar"}, "tunes": {"x": 1}},
]


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def _page(srv, nid=3, blocks=None, **fields):
    srv.notes[nid].update(body=json.dumps({"blocks": json.loads(json.dumps(blocks or SAMPLE))}), format="blocks", **fields)


def _open(win, nid=3):
    win.show_root(("page", nid))
    v = win.current
    pump(lambda: v.doc.fetched and v.kit.order, 6)
    pump(None, 0.4)
    return v


def _row(v, bid):
    return next(r for r in v.kit.rows() if r.bid == bid)


# --- Logik (ohne GTK) ------------------------------------------------------------------------------
def test_logic_limits():
    from hlidskjalf import gestalten as g
    assert g.col_weights([{"w": 0.7}, {"w": 0.3}]) == [0.7, 0.3]
    assert g.col_weights([{"w": 2}, {"w": 2}]) is None                      # gleich breit = wie bisher
    assert g.col_weights([{"w": 1}, {}]) is None                            # eine Spalte ohne w -> alle gleich
    assert g.col_weights([{"w": 1}, {"w": True}]) is None
    assert g.col_weights([{"w": -1}, {"w": 1}]) is None
    ws = g.col_weights([{"w": 0.98}, {"w": 0.01}, {"w": 0.01}])           # Mindestbreite 0,15
    assert abs(sum(ws) - 1) < 1e-6 and min(ws) >= 0.15 - 1e-9
    assert g.media_layout({"type": "image", "data": {"width": 5}}) == (0.1, "center")
    assert g.media_layout({"type": "image", "data": {"width": 250, "align": "left"}}) == (None, "left")
    assert g.media_layout({"type": "image", "data": {"width": 30}}, narrow=True) == (0.5, "center")
    assert g.media_layout({"type": "image", "data": {"width": 30, "stretched": True}}) == (None, None)
    assert g.media_layout({"type": "image", "data": {"width": "40", "align": "oben"}}) == (None, None)
    assert g.hkstyle({"type": "paragraph", "tunes": {"hkstyle": {"c": "red"}}}) == ("red", False)
    assert g.hkstyle({"type": "paragraph", "tunes": {"hkstyle": {"c": "#ff0000"}}}) is None    # nur Palette
    assert g.hkstyle({"type": "code", "tunes": {"hkstyle": {"c": "red"}}}) is None             # nicht erlaubter Typ
    assert g.style_css({"type": "list", "tunes": {"hkstyle": {"c": "blue", "bg": True}}}) == "hk-bg-blue"
    assert [g.toggle_level({"level": x}) for x in (None, 0, 2, 9, -1, "2")] == [0, 0, 2, 3, 0, 0]
    assert g.page_css({"font": "serif", "small": True}) == ["hk-font-serif", "hk-small-text"]
    assert g.page_css({"font": "comic", "small": 1}) == []
    assert g.block_url("https://s/notes#5", 5, "ab") == "https://s/notes#5:ab"
    assert g.block_url("https://s/notes#-7", -7, "ab") == "https://s/notes#-7"            # Foederation: ohne Anker
    b = {"type": "paragraph", "tunes": {"fremd": 1}}
    g.set_hkstyle(b, "pink", True)
    assert b["tunes"] == {"fremd": 1, "hkstyle": {"c": "pink", "bg": True}}
    g.set_hkstyle(b, None)
    assert b["tunes"] == {"fremd": 1}


# --- Regel 0: Rundreise ------------------------------------------------------------------------------
def test_roundtrip_keeps_unknown_data_and_tunes(win, srv):
    """laden -> einen anderen Block aendern -> speichern: unbekannte data-Schluessel, tunes und unbekannte Bloecke bleiben"""
    _page(srv)
    v = _open(win)
    rt = v.kit.by_key["h1"]
    rt.get_buffer().set_text("Titel geaendert")
    v.editor.flush()
    assert pump(lambda: "Titel geaendert" in srv.notes[3]["body"], 6)
    idle()
    got = json.loads(srv.notes[3]["body"])["blocks"]
    want = json.loads(json.dumps(SAMPLE))
    want[1]["data"]["text"] = "Titel geaendert"
    assert got == want


def test_convert_keeps_tunes(win, srv):
    """Umwandeln (Absatz -> Zitat) behaelt die Farbe und fremde Tunes; in Code (keine Farbe) nur die fremden"""
    _page(srv)
    v = _open(win)
    v.editor.convert("p1", "quote")
    assert v.editor.block("p1")["tunes"] == {"hkstyle": {"c": "red"}, "fremd": {"a": [1, 2]}}
    v.editor.convert("p1", "code")
    assert v.editor.block("p1")["tunes"] == {"fremd": {"a": [1, 2]}}


def test_clipboard_keeps_tunes():
    from hlidskjalf import blockclip
    data = blockclip.payload([SAMPLE[0]], "https://saga.test", "test")
    (b, _hk), = blockclip.parse(data)
    assert b["tunes"] == SAMPLE[0]["tunes"] and b["id"] != "p1"


# --- Darstellung ----------------------------------------------------------------------------------------
def test_render_new_fields(win, srv):
    from hlidskjalf.blocks import WeightBox
    _page(srv)
    win.set_default_size(1300, 850)
    v = _open(win)
    pump(None, 0.5)
    # G3: Farbe als CSS-Klasse am Inhalt (auch verschachtelt)
    assert v.kit.by_key["p1"].has_css_class("hk-fg-red")
    col = v.kit.by_key["p1"].get_color()                                    # Farbe kommt wirklich an (CSS greift)
    assert col.red > 0.6 and col.green < 0.45, col.to_string()
    assert v.kit.by_key["h1"].has_css_class("hk-bg-blue")
    assert v.kit.by_key["c1a"].has_css_class("hk-fg-green")
    assert v.kit.by_key["t1a"].has_css_class("hk-fg-purple")
    # G10: Toggle-Ueberschrift in Ueberschriftengroesse
    assert v.kit.by_key["t1"].has_css_class("hk-h1")
    # G1: Spaltengewichte bei breitem Fenster
    cols = _row(v, "c1")
    wb = next(w for w in walk(cols) if isinstance(w, WeightBox) and len(w.weights) == 2)
    left, right = wb.get_first_child(), wb.get_last_child()
    assert left.get_width() > 1.8 * right.get_width(), (left.get_width(), right.get_width())
    # G2: Bild 40 %, rechts; Player 60 %, links
    img = _row(v, "i1")
    sized = next(w for w in walk(img) if isinstance(w, WeightBox))
    inner = sized.get_first_child()
    assert sized.weights == [0.4]
    ok, b = inner.compute_bounds(sized)
    assert ok and abs(b.get_width() - 0.4 * sized.get_width()) < 3
    assert b.get_x() + b.get_width() > sized.get_width() - 3                 # rechtsbuendig
    emb = _row(v, "e1")
    sized = next(w for w in walk(emb) if isinstance(w, WeightBox))
    ok, b = sized.get_first_child().compute_bounds(sized)
    assert sized.weights == [0.6] and b.get_x() < 2


def test_render_page_font_and_small(win, srv):
    _page(srv, font="serif", small=True)
    v = _open(win)
    outer = v.scroll.get_child()
    if isinstance(outer, Gtk.Viewport):
        outer = outer.get_child()
    assert outer.has_css_class("hk-font-serif") and outer.has_css_class("hk-small-text")
    _page(srv, font="mono", small=False)
    v.doc.load(force=True)
    pump(lambda: "hk-font-mono" in _outer(v).get_css_classes(), 4)
    assert not _outer(v).has_css_class("hk-small-text")


def _outer(v):
    o = v.scroll.get_child()
    return o.get_child() if isinstance(o, Gtk.Viewport) else o


def test_narrow_window_stacks_columns(win, srv):
    """schmal: Spalten untereinander wie bisher (Gewichte nur bei ausreichender Breite)"""
    from hlidskjalf.blocks import WeightBox
    _page(srv)
    v = _open(win)
    v.get_width = lambda: 600                                                # Breite der Seite unter 700 px
    v.rebuild()
    pump(None, 0.2)
    cols = _row(v, "c1")
    assert not [w for w in walk(cols) if isinstance(w, WeightBox) and len(w.weights) == 2]


# --- Rueckfall-Karte --------------------------------------------------------------------------------------
def test_fallback_card(win, srv):
    _page(srv)
    v = _open(win)
    row = _row(v, "u1")
    labels = [w.get_label() for w in walk(row) if isinstance(w, Gtk.Label)]
    assert "This block can’t be shown in the app yet." in labels or "Dieser Block lässt sich in der App noch nicht anzeigen." in labels
    assert not any(lb and lb.startswith("[") for lb in labels)
    opened = []
    win.open_uri = opened.append
    btn = next(w for w in walk(row) if isinstance(w, Gtk.Button) and w.has_css_class("hk-fallback-open"))
    btn.emit("clicked")
    assert opened == ["https://saga.test/notes#3:u1"]


# --- Bearbeiten -----------------------------------------------------------------------------------------
def test_block_menu_color(win, srv):
    """Block-Menue -> Farbe -> Hintergrund Gelb: tunes.hkstyle gesetzt, fremde Tunes bleiben, gespeichert"""
    _page(srv)
    v = _open(win)
    ents = v.kit._color_entries(v.editor.block("p1"))
    bg = next(e for e in ents if e and e[0] == ents[-1][0])[1]
    next(e for e in bg if e[0].endswith(("Yellow", "Gelb")))[1]()
    pump(None, 0.2)
    assert v.editor.block("p1")["tunes"] == {"hkstyle": {"c": "yellow", "bg": True}, "fremd": {"a": [1, 2]}}
    assert v.kit.by_key["p1"].has_css_class("hk-bg-yellow")
    ents = v.kit._color_entries(v.editor.block("p1"))
    ents[0][1]()                                                             # Standard
    v.editor.flush()
    assert pump(lambda: json.loads(srv.notes[3]["body"])["blocks"][0]["tunes"] == {"fremd": {"a": [1, 2]}}, 6)
    idle()


def test_page_menu_font_small(win, srv):
    """Seitenmenue (Server ab 2.5 liefert font/small): Schrift und kleiner Text -> PATCH wie "Volle Breite" """
    from hlidskjalf import page as pagemod
    _page(srv, small=False)
    v = _open(win)
    seen = []
    orig = pagemod.menu_popover
    pagemod.menu_popover = lambda parent, ents: (seen.append(ents), orig(parent, ents))[1]
    try:
        v.page_menu(v)
    finally:
        pagemod.menu_popover = orig
    ents = [e for e in seen[0] if e]
    font = next(e for e in ents if isinstance(e[1], list) and any(x[0].endswith("Serif") for x in e[1]))[1]
    next(x for x in font if x[0].endswith("Serif"))[1]()
    assert pump(lambda: srv.notes[3].get("font") == "serif", 4)
    pump(lambda: _outer(win.current).has_css_class("hk-font-serif"), 3)
    assert _outer(win.current).has_css_class("hk-font-serif")
    small = next(e for e in ents if not isinstance(e[1], list) and e[0].endswith(("Small text", "Kleiner Text")))
    small[1]()
    assert pump(lambda: srv.notes[3].get("small") is True, 4)
    next(x for x in font if x[0].endswith(("Default", "Standard")))[1]()
    assert pump(lambda: "font" not in srv.notes[3], 4)
    idle()


def test_page_menu_without_server_support(win, srv):
    """aelterer Server (ohne font/small in GET): keine Eintraege"""
    from hlidskjalf import page as pagemod
    _page(srv)
    srv.notes[3].pop("small", None)
    srv.notes[3].pop("font", None)
    v = _open(win)
    seen = []
    orig = pagemod.menu_popover
    pagemod.menu_popover = lambda parent, ents: (seen.append(ents), orig(parent, ents))[1]
    try:
        v.page_menu(v)
    finally:
        pagemod.menu_popover = orig
    assert not any(e and isinstance(e[1], list) and any(x[0].endswith("Serif") for x in e[1]) for e in seen[0])


def test_shots_light_dark(win, srv):
    """Bildschirmfotos zur Sichtpruefung (HK_GEST_SHOTS=<ordner>), hell und dunkel"""
    import os
    out = os.environ.get("HK_GEST_SHOTS")
    if not out:
        return
    from gi.repository import Adw
    from hlidskjalf.testing import shot
    _page(srv, font="serif")
    win.set_default_size(1300, 1100)
    for scheme, name in ((Adw.ColorScheme.FORCE_LIGHT, "hell"), (Adw.ColorScheme.FORCE_DARK, "dunkel")):
        Adw.StyleManager.get_default().set_color_scheme(scheme)
        _open(win)
        pump(None, 1.0)
        shot(win, os.path.join(out, f"gestalten-{name}.png"))
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.DEFAULT)
