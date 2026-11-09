"""Widget "Unterseiten" (Saga 2.5, subpages1): Logik (cfg, Daten bereinigen, Bezeichnungen), Anzeige als Liste und Galerie
mit dem Schein-Server, Oeffnen (Seite, Datenbank, fremde Instanz), Leerzustand und Anlegen, Rundreise (Regel 0),
Neuladen bei Aenderungen am Seitenbaum, Bildschirmfotos (HK_SUB_SHOTS=<ordner>)."""
import io
import json
import os

from gi.repository import Gtk

import pytest

from hk_helpers import idle, pump


@pytest.fixture(autouse=True)
def _features_reset():
    """Faehigkeiten des Servers nach jedem Test vergessen (Modul-Zustand, sonst sehen ihn spaetere Testdateien)"""
    yield
    from hlidskjalf import pagewidgets as pw
    pw.FEATURES = None

TITLES = ["Der Nebelturm", "Salz und Asche", "Die Kartenmacherin von Yl", "Mondfähre", "Unter gläsernen Dächern – eine sehr lange Chronik "
          "der sieben Häfen", "Rabenpost", "Das stille Archiv", "Windleser"]
COLORS = [(196, 86, 62), (58, 110, 165), (92, 142, 96), (176, 132, 52), (122, 88, 160), (60, 140, 150), (170, 74, 110), (96, 104, 120)]


def _block(view="gallery", image="content", size="m", **extra):
    d = {"v": 1, "kind": "subpages", "size": size, "cfg": {"view": view, "image": image}}
    d.update(extra)
    return {"id": "sp1", "type": "widget", "data": d}


def _picture(color, w=240, h=360, n=0):
    """Platzhalterbild: Farbflaeche mit hellerem Streifen (kein echtes Bild)"""
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (w, h), color)
    dr = ImageDraw.Draw(im)
    light = tuple(min(255, c + 46) for c in color)
    dr.rectangle((w * 0.12, h * (0.16 + 0.04 * (n % 3)), w * 0.88, h * (0.22 + 0.04 * (n % 3))), fill=light)
    dr.rectangle((w * 0.12, h * 0.72, w * 0.5, h * 0.75), fill=light)
    out = io.BytesIO()
    im.save(out, "PNG")
    return out.getvalue()


def _setup(srv, block=None, kids=8, pictures=True, blocks=None):
    """Seite 3 mit dem Widget und kids Unterseiten (20 ..), die 2. ist eine Datenbank, die 4. ohne Bild"""
    srv.v25 = True
    srv.uploads = getattr(srv, "uploads", {})
    body = blocks or [{"id": "p0", "type": "paragraph", "data": {"text": "Leseliste"}}, block or _block(),
                      {"id": "p1", "type": "paragraph", "data": {"text": "Ende"}}]
    srv.notes[3].update(body=json.dumps({"blocks": body}), format="blocks")
    for i in range(kids):
        nid = 20 + i
        t = TITLES[i % len(TITLES)] + ("" if i < len(TITLES) else f" {i}")
        n = {"id": nid, "title": t, "format": "blocks", "body": json.dumps({"blocks": []}), "rev": 1, "parent_id": 3,
             "icon": ["📖", "", "🗺️", "🌙", "", "🐦", "", "🍃"][i % 8], "updated": srv.notes[3]["updated"], "created": srv.notes[3]["created"]}
        if i == 1:
            n.update(kind="database", icon="", schema={"props": [], "views": [{"id": "t", "name": "Tabelle", "type": "table"}]})
        if pictures and i != 3:
            n["sp_img"] = f"/files/{i:024x}"
            n["sp_cover"] = f"/covers/bild{i}.jpg"
            srv.uploads[n["sp_img"]] = _picture(COLORS[i % 8], n=i)
            srv.uploads[n["sp_cover"]] = _picture(COLORS[i % 8], 480, 270, n=i)
        srv.notes[nid] = n


def _open(win, nid=3):
    from hlidskjalf.store import store
    store.check_version()
    pump(lambda: store.has("subpages1"), 5)
    store.refresh()
    idle()
    win.show_root(("page", nid))
    v = win.current
    pump(lambda: v.doc.fetched and v.kit.order, 6)
    pump(lambda: v.wvalues.state == "ok", 5)
    pump(None, 0.5)
    return v


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def _card(v, bid="sp1"):
    from hlidskjalf.pwcard import WidgetCard
    return next(w for w in walk(v) if isinstance(w, WidgetCard) and w.spec.id == bid)


def _items(card):
    return [w for w in walk(card) if isinstance(w, (Gtk.Button, Gtk.ListBoxRow)) and getattr(w, "hk_note", None) is not None]


def _go(item):
    """wie Klick bzw. Eingabetaste auf Karte (Knopf) oder Zeile"""
    if isinstance(item, Gtk.Button):
        item.emit("clicked")
    else:
        item.activate()


def _texts(card):
    return [w.get_label() for w in walk(card) if isinstance(w, Gtk.Label) and w.get_visible() and w.get_label()]


# --- Logik (ohne GTK) --------------------------------------------------------------------------------
def test_spec_cfg_and_feature():
    from hlidskjalf import pagewidgets as pw
    old = pw.FEATURES
    try:
        pw.FEATURES = frozenset()
        s = pw.Spec(_block())
        assert not s.known and not s.stage2 and not pw.needs_server([_block()])        # aelterer Server: Ersatzkarte, kein Abruf
        pw.FEATURES = frozenset(["subpages1"])
        assert s.known and s.stage2 and s.server_value and pw.needs_server([_block()])
        assert s.style == "plain" and pw.Spec(_block(style="card")).style == "card" and pw.Spec(_block(style="x")).style == "plain"
        assert pw.Spec({"data": {"kind": "weather"}}).style == "card"                  # andere Arten wie bisher
    finally:
        pw.FEATURES = old
    assert pw.subpages_cfg({"view": "gallery", "image": "content"}) == {"view": "gallery", "image": "content"}
    assert pw.subpages_cfg({"view": "kacheln", "image": "gross"}) == {"view": "list", "image": "cover"}
    assert pw.subpages_cfg(None) == {"view": "list", "image": "cover"} and pw.subpages_cfg({"image": "none"})["image"] == "none"
    for image in ("cover", "content", "none"):
        w = pw.SUB_WIDTH[image]
        assert w["s"] < w["m"] < w["l"]
    assert pw.SUB_RATIO["content"] == 2 / 3 and abs(pw.SUB_RATIO["cover"] - 16 / 9) < 1e-9
    assert pw.kind_label("subpages") in ("Subpages", "Unterseiten")


def test_data_is_cleaned():
    from hlidskjalf import pagewidgets as pw
    d = pw.subpages_data({"items": [
        {"id": 812, "title": "Der Nebelturm &amp; mehr", "icon": "📖", "img": "/files/0123456789abcdef01234567", "kind": "page"},
        {"id": 813, "title": "", "icon": "", "img": None, "kind": "database"},
        {"id": -77, "title": "Fern", "icon": "/api/fed/files/-77/abc123", "img": "/api/fed/files/-77/0123456789abcdef", "kind": "neu"},
        {"id": 814, "title": "Titelbild", "img": "/covers/berge-01.jpg"},
        {"id": 815, "title": "Fremd", "img": "https://boese.example/x.png", "icon": "/etc/passwd"},
        {"id": 816, "title": "Pfad", "img": "/files/../api/account"},
        {"id": True, "title": "bool"}, {"id": "9", "title": "text"}, {"id": 0, "title": "null"}, "kaputt", None,
        {"id": 817, "title": 5, "icon": 7, "img": 3}],
        "total": 9, "more": 3, "can_add": True})
    ids = [i["id"] for i in d["items"]]
    assert ids == [812, 813, -77, 814, 815, 816, 817]
    assert d["items"][0] == {"id": 812, "title": "Der Nebelturm & mehr", "icon": "📖", "img": "/files/0123456789abcdef01234567",
                             "kind": "page"}
    assert d["items"][1]["kind"] == "database" and d["items"][1]["title"] == "" and d["items"][1]["img"] is None
    assert d["items"][2]["kind"] == "page" and d["items"][2]["img"].startswith("/api/fed/files/-77/")
    assert d["items"][2]["icon"] == "/api/fed/files/-77/abc123"
    assert d["items"][3]["img"] == "/covers/berge-01.jpg"
    assert d["items"][4]["img"] is None and d["items"][4]["icon"] == "" and d["items"][5]["img"] is None      # nie fremde Adressen
    assert d["items"][6] == {"id": 817, "title": "", "icon": "", "img": None, "kind": "page"}
    assert (d["total"], d["more"], d["can_add"]) == (9, 3, True)
    e = pw.subpages_data({"items": "x", "total": -1, "more": True, "can_add": "ja"})
    assert e == {"items": [], "total": 0, "more": 0, "can_add": False} and pw.subpages_data(None) == e
    big = pw.subpages_data({"items": [{"id": i, "title": str(i)} for i in range(1, 400)]})
    assert len(big["items"]) == pw.SUB_MAX and big["total"] == pw.SUB_MAX
    assert len(pw.subpages_data({"items": [{"id": 1, "title": "x" * 500}]})["items"][0]["title"]) == 200


def test_labels():
    from hlidskjalf import pagewidgets as pw
    assert pw.subpage_label({"title": "Der Nebelturm", "kind": "page"}) in ("Der Nebelturm, page", "Der Nebelturm, Seite")
    assert pw.subpage_label({"title": "Leseliste", "kind": "database"}) in ("Leseliste, database", "Leseliste, Datenbank")
    assert pw.subpage_title({"title": ""}) in ("Untitled", "Unbenannt")
    assert pw.subpages_more(0) == "" and "3" in pw.subpages_more(3)


def test_grid_columns(win):
    """Raster: so viele Spalten wie mit der Wunschbreite passen, mindestens 2, Karten gleich breit"""
    from hlidskjalf.pwsubpages import Grid
    g = Grid(152, col_gap=12)
    assert g.columns(668) == (4, 158) and g.columns(340) == (2, 164) and g.columns(200) == (2, 94)
    assert g.columns(152)[0] == 2 and g.columns(0) == (2, 1) and g.columns(1500)[0] == 9
    assert Grid(288).columns(668) == (2, 328)


def test_parse_keeps_data_and_unsupported_kinds():
    from hlidskjalf import pagewidgets as pw
    r = {"widgets": {"sp1": {"kind": "subpages", "value": 2, "error": None, "data": {"items": [{"id": 1, "title": "A"}], "total": 2}},
                     "sp2": {"kind": "subpages", "value": None, "error": "no_access", "data": {"items": [{"id": 1}]}}}}
    st, vals = pw.parse_response(r)
    assert st == "ok" and vals["sp1"]["data"]["total"] == 2 and vals["sp2"]["data"] is None and vals["sp2"]["error"] == "no_access"
    assert pw.unsupported_kinds({"widgets": {}, "unsupported_kinds": ["subpages", 5]}) == frozenset(["subpages"])
    assert pw.unsupported_kinds({"widgets": {}}) == frozenset() and pw.unsupported_kinds(None) == frozenset()
    assert pw.unsupported_kinds({"unsupported_kinds": "subpages"}) == frozenset()


# --- Anzeige --------------------------------------------------------------------------------------------
def test_gallery_shows_cards_and_loads_pictures(win, srv):
    _setup(srv)
    v = _open(win)
    card = _card(v)
    its = _items(card)
    assert [i.hk_note for i in its] == list(range(20, 28)) and all(isinstance(i, Gtk.Button) for i in its)
    assert card.has_css_class("hk-widget-plain") and not card.title_lb.get_visible()          # ohne Titel: keine Kopfzeile
    from hlidskjalf.pwsubpages import Grid, Thumb
    grid = its[0].get_parent()
    assert isinstance(grid, Grid) and grid.min_cols == 2
    assert all(i.get_focusable() for i in its)
    assert len({i.get_width() for i in its}) == 1                                             # alle Karten gleich breit
    thumbs = [w for w in walk(card) if isinstance(w, Thumb)]
    assert len(thumbs) == 8
    pump(lambda: sum(t.state == "image" for t in thumbs) == 7, 6)
    assert [t.state for t in thumbs] == ["image"] * 3 + ["placeholder"] + ["image"] * 4       # 4. Unterseite ohne Bild
    t0 = thumbs[0]
    assert t0.get_width() >= 72 and abs(t0.get_height() - t0.get_width() * 1.5) <= 1.5        # content: 2:3 Hochformat
    got = [p for p in srv.file_log if p.startswith("files/")]
    assert len(got) == 7 and all(p.endswith("?w=400") for p in got)                           # Vorschaubilder, je Bild einmal
    labels = [w for w in walk(its[4]) if isinstance(w, Gtk.Label)]
    assert labels[-1].get_lines() == 2 and labels[-1].get_ellipsize().value_nick == "end" and labels[-1].get_label() == TITLES[4]
    assert its[4].get_tooltip_text() == TITLES[4]
    # Titel in Textfarbe (keine Akzentklasse), Platzhalter ohne Symbol in der Titelzeile (steht gross in der Flaeche)
    assert not any(c.startswith("hk-wacc") for c in card.get_css_classes())
    assert len([w for w in walk(its[3]) if w.has_css_class("hk-sp-ph")]) == 1


def test_gallery_cover_ratio_none_and_sizes(win, srv):
    _setup(srv, _block(image="cover", size="l", title="Regal"))
    v = _open(win)
    card = _card(v)
    from hlidskjalf.pwsubpages import Thumb
    thumbs = [w for w in walk(card) if isinstance(w, Thumb)]
    pump(lambda: sum(t.state == "image" for t in thumbs) == 7, 6)
    t0 = thumbs[0]
    assert abs(t0.get_height() - t0.get_width() * 9 / 16) <= 1.5                              # cover: 16:9
    assert any(p.startswith("covers/") for p in srv.file_log) and not any("?w=" in p for p in srv.file_log if p.startswith("covers/"))
    assert card.title_lb.get_visible() and card.title_lb.get_label() == "Regal"
    wide_l = t0.get_width()
    srv.notes[3]["body"] = json.dumps({"blocks": [_block(image="none", size="s")]})
    win.show_root(("page", 1))
    pump(None, 0.2)
    v = _open(win)
    card = _card(v)
    assert not [w for w in walk(card) if isinstance(w, Thumb)] and len(_items(card)) == 8     # none: keine Bildflaeche
    assert not any(p for p in srv.file_log if p.startswith("files/"))
    assert _items(card)[0].get_width() < wide_l                                                # s schmaler als l


def test_gallery_two_columns_when_narrow(win, srv):
    _setup(srv, _block(size="l"))
    win.set_default_size(420, 800)
    v = _open(win)
    pump(None, 0.5)
    its = _items(_card(v))
    from gi.repository import Graphene
    p0, p1, p2 = (i.compute_point(v, Graphene.Point().init(0, 0))[1] for i in its[:3])
    assert v.get_width() < 520, v.get_width()                                                  # Fenster ist wirklich schmal
    assert abs(p0.y - p1.y) < 2 and p1.x > p0.x                                                # mindestens zwei nebeneinander
    assert p2.y > p0.y and abs(p2.x - p0.x) < 2                                                # dritte Karte in der naechsten Zeile
    assert its[1].get_width() < 200                                                            # schmaler als die Wunschbreite (l)
    assert p2.y >= p0.y + its[0].get_height()                                                  # Zeilen ueberlappen nicht
    # Fenster nachtraeglich breiter und wieder schmal: Hoehe folgt der Breite
    grid = its[0].get_parent()
    assert grid.get_height() == grid.measure(Gtk.Orientation.VERTICAL, grid.get_width())[1]
    win.set_default_size(1180, 800)
    pump(None, 0.6)
    its = _items(_card(v))
    grid = its[0].get_parent()
    assert grid.columns(grid.get_width())[0] == 3 and its[3].get_width() == its[0].get_width()     # breit: drei Spalten
    r0, r3 = (i.compute_point(v, Graphene.Point().init(0, 0))[1] for i in (its[0], its[3]))
    assert r3.y >= r0.y + its[0].get_height() and abs(r3.x - r0.x) < 2
    win.set_default_size(420, 800)
    pump(None, 0.6)
    its = _items(_card(v))                                  # die Seite baut ihre Bloecke bei neuer Breite ggf. neu
    grid = its[0].get_parent()
    assert grid.get_height() == grid.measure(Gtk.Orientation.VERTICAL, grid.get_width())[1]
    q0, q2 = (i.compute_point(v, Graphene.Point().init(0, 0))[1] for i in (its[0], its[2]))
    assert q2.y >= q0.y + its[0].get_height()


def test_list_rows_and_more(win, srv):
    srv.subpages_limit = 5
    _setup(srv, _block(view="list", size="m"))
    v = _open(win)
    card = _card(v)
    its = _items(card)
    assert [i.hk_note for i in its] == [20, 21, 22, 23, 24] and all(isinstance(i, Gtk.ListBoxRow) for i in its)
    assert all(i.get_focusable() and i.get_activatable() for i in its)
    tx = _texts(card)
    assert TITLES[0] in tx and any("3" in t and t.startswith("+") for t in tx)                 # "+ 3 weitere"
    from hlidskjalf.pwsubpages import Thumb
    assert not [w for w in walk(card) if isinstance(w, Thumb)] and not srv.file_log            # Liste: keine Bilder


def test_open_page_database_and_remote(win, srv):
    _setup(srv, _block(view="list"))
    v = _open(win)
    opened = []
    orig = win.show_page
    win.show_page = lambda pid, root=False: opened.append(pid)
    try:
        its = _items(_card(v))
        _go(its[0])                                         # Eingabe/Klick auf die Zeile
        _go(its[1])                                         # Datenbank
        assert opened == [20, 21]
        srv.notes[3]["body"] = json.dumps({"blocks": [_block()]})
        srv.widget_values = {"sp1": {"kind": "subpages", "value": 1, "error": None, "data": {
            "items": [{"id": -4711, "title": "Fern", "icon": "", "img": "/api/fed/files/-4711/00ff00ff00ff00ff", "kind": "page"}],
            "total": 1, "more": 0, "can_add": False}}}
        win.show_root(("page", 1))                          # Seite neu oeffnen: jetzt als Galerie
        pump(None, 0.2)
        v = _open(win)
        pump(lambda: [i.hk_note for i in _items(_card(v))] == [-4711], 5)
        _go(_items(_card(v))[0])                            # Karte der Galerie, Seite anderer Saga-Instanzen (negative ID)
        assert opened == [20, 21, -4711]
        pump(lambda: any(p.startswith("api/fed/files/-4711/") for p in srv.file_log), 4)
        assert any(p == "api/fed/files/-4711/00ff00ff00ff00ff?w=400" for p in srv.file_log)
    finally:
        win.show_page = orig
    its = _items(_card(v))
    assert its and its[0].hk_note == -4711
    # echte Navigation: Unterseite oeffnet sich in der App
    srv.widget_values = {}
    v.load_widgets()
    pump(lambda: len(_items(_card(v))) == 8, 5)
    _go(_items(_card(v))[0])
    pump(lambda: win.route == ("page", 20), 5)
    assert win.route == ("page", 20)


def test_empty_state_and_add(win, srv):
    _setup(srv, kids=0)
    v = _open(win)
    card = _card(v)
    tx = _texts(card)
    assert any(t in ("No subpages yet", "Noch keine Unterseiten") for t in tx) and not _items(card)
    btn = next(w for w in walk(card) if isinstance(w, Gtk.Button) and w.has_css_class("hk-sp-new"))
    before = set(srv.notes)
    btn.emit("clicked")
    pump(lambda: len(set(srv.notes) - before) == 1, 5)
    new = (set(srv.notes) - before).pop()
    assert srv.notes[new]["parent_id"] == 3
    pump(lambda: win.route == ("page", new), 5)
    assert win.route == ("page", new)


def test_empty_without_right_has_no_button(win, srv):
    srv.subpages_can_add = False
    _setup(srv, kids=0)
    v = _open(win)
    card = _card(v)
    assert not [w for w in walk(card) if isinstance(w, Gtk.Button)]
    assert any(t in ("Pages placed below this page appear here.", "Hier erscheinen die Seiten, die unter dieser Seite liegen.")
               for t in _texts(card))


def test_states_error_old_server_and_origin(win, srv):
    # Fehler vom Server
    _setup(srv)
    srv.widget_values = {"sp1": {"kind": "subpages", "value": None, "error": "no_access", "data": None}}
    v = _open(win)
    card = _card(v)
    assert card.notice_lb.get_visible() and not _items(card)
    # Ursprungsserver ohne wsubpages1: Art fehlt, unsupported_kinds nennt sie
    srv.widget_values = {}
    srv.unsupported_kinds = ["subpages"]
    v.load_widgets()
    pump(lambda: "subpages" in v.wvalues.nokinds, 5)
    pump(None, 0.2)
    assert card.notice_lb.get_label() in ("Widget only available on the originating server", "Widget nur auf dem Ursprungsserver")
    srv.unsupported_kinds = []
    v.load_widgets()
    pump(lambda: len(_items(card)) == 8, 5)
    assert not card.notice_lb.get_visible() and v.wvalues.nokinds == frozenset()


def test_old_server_shows_fallback_card(win, srv):
    """Server ohne subpages1: Ersatzkarte wie bisher, kein Abruf"""
    _setup(srv)
    srv.v25 = False
    from hlidskjalf.store import store
    store.check_version()
    idle()
    win.show_root(("page", 3))
    v = win.current
    pump(lambda: v.doc.fetched and v.kit.order, 6)
    pump(None, 0.4)
    card = _card(v)
    assert card.has_css_class("hk-widget-newer") and not _items(card)
    assert card.notice_lb.get_label() in ("Widget – a newer app version is needed", "Widget – neuere Version nötig")


def test_roundtrip_block_unchanged(win, srv):
    """Regel 0: der Block (auch unbekannte Felder) geht beim Speichern unveraendert zurueck"""
    blk = _block(title="Leseliste", accent="blue", zukunft={"x": [1, 2]})
    blk["data"]["cfg"]["neu"] = True
    blk["tunes"] = {"fremd": 1}
    _setup(srv, blocks=[{"id": "p0", "type": "paragraph", "data": {"text": "Alt"}}, json.loads(json.dumps(blk))])
    v = _open(win)
    assert v.doc.fetched
    row = next(r for r in v.kit.rows() if r.bid == "p0")
    tv = next(w for w in walk(row) if isinstance(w, Gtk.TextView))
    tv.get_buffer().set_text("Neu")
    pump(None, 0.3)
    v.editor.flush()
    idle()
    pump(lambda: "Neu" in srv.notes[3]["body"], 6)
    saved = json.loads(srv.notes[3]["body"])["blocks"]
    assert next(b for b in saved if b["id"] == "sp1") == blk


def test_reloads_when_tree_changes(win, srv):
    _setup(srv, _block(view="list"), kids=2)
    v = _open(win)
    card = _card(v)
    assert len(_items(card)) == 2
    from hlidskjalf.store import store
    srv.notes[40] = dict(srv.notes[20], id=40, title="Neu im Baum")
    store.refresh()                                         # Seitenbaum aendert sich -> pages-changed
    pump(lambda: len(_items(card)) == 3, 6)
    assert [i.hk_note for i in _items(card)] == [20, 21, 40]
    srv.notes[40]["title"] = "Umbenannt"
    store.refresh()
    pump(lambda: "Umbenannt" in _texts(card), 6)
    assert "Umbenannt" in _texts(card)


def test_keyboard_focus_per_card(win, srv):
    _setup(srv)
    v = _open(win)
    its = _items(_card(v))
    assert its[2].grab_focus()
    pump(None, 0.2)
    assert win.get_focus() is its[2]


# --- Bildschirmfotos ---------------------------------------------------------------------------------------
def test_shots_subpages(win, srv):
    """Bildschirmfotos zur Sichtpruefung (HK_SUB_SHOTS=<ordner>, HK_SUB_PREFIX=<vorsilbe>): Galerie und Liste, hell und dunkel"""
    out = os.environ.get("HK_SUB_SHOTS")
    if not out:
        return
    from gi.repository import Adw
    from hlidskjalf.testing import shot
    pre = os.environ.get("HK_SUB_PREFIX", "")
    os.makedirs(out, exist_ok=True)
    win.set_default_size(1180, 980)
    head = [{"id": "h0", "type": "header", "data": {"text": "Leseliste", "level": 2}},
            {"id": "p0", "type": "paragraph", "data": {"text": "Was gerade auf dem Stapel liegt."}}]
    views = [("galerie-hochformat", _block()), ("galerie-titelbild", _block(image="cover")),
             ("galerie-ohne-bild", _block(image="none")), ("liste", _block(view="list")),
             ("galerie-klein-karte", _block(size="s", style="card", title="Regal"))]
    for scheme, name in ((Adw.ColorScheme.FORCE_LIGHT, "hell"), (Adw.ColorScheme.FORCE_DARK, "dunkel")):
        Adw.StyleManager.get_default().set_color_scheme(scheme)
        for label, blk in views:
            _setup(srv, blocks=head + [blk, {"id": "p1", "type": "paragraph", "data": {"text": "Ende der Seite"}}])
            win.show_root(("page", 1))
            pump(None, 0.2)
            v = _open(win)
            pump(None, 1.2)
            shot(win, os.path.join(out, f"{pre}unterseiten-{label}-{name}.png"))
    # schmal (2 Spalten), Fokus auf einer Karte, Leerzustand
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT)
    _setup(srv, blocks=head + [_block()])
    win.show_root(("page", 1))
    v = _open(win)
    its = _items(_card(v)) if pre == "" else []
    if its:
        its[1].grab_focus()
        win.set_focus_visible(True)
    pump(None, 1.0)
    shot(win, os.path.join(out, f"{pre}unterseiten-galerie-fokus-hell.png"))
    if pre == "":                                           # schmales Fenster: zwei Spalten
        win.set_default_size(430, 900)
        pump(None, 1.0)
        shot(win, os.path.join(out, "unterseiten-galerie-schmal-hell.png"))
        win.set_default_size(1180, 980)
        pump(None, 0.5)
    for k in [k for k in srv.notes if 20 <= k < 40]:
        del srv.notes[k]
    _setup(srv, blocks=head + [_block()], kids=0)
    win.show_root(("page", 1))
    v = _open(win)
    pump(None, 0.8)
    shot(win, os.path.join(out, f"{pre}unterseiten-leer-hell.png"))
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.DEFAULT)
