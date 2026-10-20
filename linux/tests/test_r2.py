"""Testrunde 2.4, Runde 2 (Linux): Funde N1-N11."""
from gi.repository import Gdk, Gtk

from hk_helpers import idle, pump


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def _open_page(win, nid=1):
    win.show_root(("page", nid))
    v = win.current
    pump(lambda: v.doc.fetched and v.kit.order, 6)
    pump(None, 0.3)
    return v


def _menu_button(pop, text):
    return next(b for b in walk(pop) if isinstance(b, Gtk.Button) and isinstance(b.get_child(), Gtk.Label)
                and b.get_child().get_label() == text)


def _open_key_menu(v, bid, key=Gdk.KEY_Menu, state=0):
    """Blockmenue wie per Tastatur oeffnen (echtes Popover) -> Popover"""
    from hlidskjalf import blocks
    made, orig = [], blocks.menu_popover

    def spy(parent, entries):
        made.append(orig(parent, entries))
        return made[-1]
    blocks.menu_popover = spy
    try:
        rt = v.kit.by_key[bid]
        rt.grab_focus()
        pump(None, 0.2)
        assert v.kit.on_key(rt, key, state) is True
        pump(None, 0.3)
    finally:
        blocks.menu_popover = orig
    assert made and made[0].get_visible(), "Blockmenue nicht offen"
    assert not isinstance(made[0].get_parent(), Gtk.TextView)      # nie am Textfeld (Absturz beim Neuaufbau)
    return made[0]


def test_n1_block_menu_keyboard_delete_no_crash(win, srv):
    """N1: Menuetaste -> Loeschen: kein Absturz, Block weg und gespeichert, Menue haengt nicht am geloeschten Textfeld"""
    from hlidskjalf.i18n import _
    v = _open_page(win)
    bid = "p1"
    pop = _open_key_menu(v, bid)
    _menu_button(pop, _("Delete")).emit("clicked")
    pump(lambda: bid not in v.kit.by_key, 3)
    pump(None, 0.5)
    assert bid not in v.kit.by_key
    assert pop.get_parent() is None                     # abgehaengt, bevor der Block verschwand
    idle()
    assert not any(b.get("id") == bid for b in v.doc.root)
    # Block loeschen ueber die Tastatur: Hinweis mit "Rueckgaengig" wie ueber den Griff
    t = getattr(v, "last_undo_toast", None)
    assert t is not None
    t.emit("button-clicked")
    pump(lambda: any(b.get("id") == bid for b in v.doc.root), 3)
    assert any(b.get("id") == bid for b in v.doc.root)


def test_n1_block_menu_keyboard_duplicate(win, srv):
    """N1: Umschalt+F10 -> Duplizieren ueber die Tastatur"""
    from hlidskjalf.i18n import _
    v = _open_page(win)
    n = len(v.doc.root)
    pop = _open_key_menu(v, "p1", Gdk.KEY_F10, Gdk.ModifierType.SHIFT_MASK)
    _menu_button(pop, _("Duplicate")).emit("clicked")
    pump(lambda: len(v.doc.root) == n + 1, 3)
    pump(None, 0.5)
    assert len(v.doc.root) == n + 1
    assert pop.get_parent() is None


def _shadowed_gettext(path):
    """Funktionen/Lambdas mit einem Parameter "_" (auch *_), die darin _("...") aufrufen -> [(Zeile, Name)]"""
    import ast
    tree = ast.parse(open(path, encoding="utf-8").read())
    out = []

    def params(fn):
        a = fn.args
        names = [x.arg for x in a.posonlyargs + a.args + a.kwonlyargs]
        names += [x.arg for x in (a.vararg, a.kwarg) if x is not None]
        return names

    def calls_gettext(node, top):
        for ch in ast.iter_child_nodes(node):
            if isinstance(ch, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)) and ch is not top and "_" in params(ch):
                continue                                        # eigener Parameter "_": wird dort selbst geprueft
            if isinstance(ch, ast.Call) and isinstance(ch.func, ast.Name) and ch.func.id == "_":
                return True
            if calls_gettext(ch, top):
                return True
        return False

    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)) and "_" in params(node):
            if calls_gettext(node, node):
                out.append((node.lineno, getattr(node, "name", "lambda")))
    return out


def test_n6_no_parameter_shadows_gettext():
    """N6: "def fill(*_)" verdeckte gettext _ -> TypeError im Leerzustand der Dienste; nirgends mehr"""
    import glob
    import os
    here = os.path.dirname(os.path.abspath(__file__))
    bad = {}
    for p in sorted(glob.glob(os.path.join(here, "..", "hlidskjalf", "*.py"))):
        hits = _shadowed_gettext(p)
        if hits:
            bad[os.path.basename(p)] = hits
    assert not bad, bad


def test_n6_services_empty_state(win, srv):
    """N6: Dienste ohne Eintraege bzw. ohne Treffer zeigen einen Hinweis statt einer Ausnahme"""
    from hlidskjalf.dialogs import services_screen
    from hlidskjalf.i18n import _
    srv.services = []
    sw = services_screen(win)
    labels = lambda: [w.get_label() for w in walk(sw) if isinstance(w, Gtk.Label)]   # noqa: E731
    msg = _("No services yet. Services and bookmarks are added in the web interface.")
    assert pump(lambda: msg in labels(), 4), labels()
    se = next(w for w in walk(sw) if isinstance(w, Gtk.SearchEntry))
    se.set_text("xyz")
    se.emit("search-changed")
    pump(None, 0.2)
    assert _("No service matches “{query}”.").format(query="xyz") in labels()


def test_n4_new_page_focuses_title(win, srv):
    """N4: "Neue Seite" (Knopf behaelt zuerst den Fokus) -> Schreibmarke im Titel, auch wenn der Titel erst spaeter im
    Fenster haengt"""
    from hlidskjalf.store import store
    from hlidskjalf.window import SidebarRow
    store.trashed_ids.clear()                               # frueherer Test: Seite 100 im Papierkorb -> neue Seite 100 gesperrt
    btn = next(b for b in walk(win.sidebar) if isinstance(b, Gtk.Button) and b.get_focusable())
    btn.grab_focus()
    win.new_page()
    # Seitenleiste baut nach dem Anlegen neu auf und setzt den Fokus auf eine Zeile (wie im vollen Testlauf beobachtet)
    pump(lambda: getattr(win.current, "title_rt", None) is not None, 5)
    row = next(r for r in walk(win.sidebar) if isinstance(r, SidebarRow) and r.get_focusable())
    row.grab_focus()
    assert pump(lambda: getattr(win.current, "title_rt", None) is not None and win.get_focus() is win.current.title_rt, 5), \
        (win.route, win.get_focus(), [type(x).__name__ for x in [win.get_focus().get_parent(), win.get_focus().get_parent().get_parent()]] if win.get_focus() else None)
    pump(None, 0.5)
    assert win.get_focus() is win.current.title_rt          # bleibt dort


def test_n8_table_bar_does_not_overlap(win, srv):
    """N8: die Tabellenleiste (+ Zeile, + Spalte, Kopfzeile, ⋮) bekommt die Hoehe, die sie braucht - breit eine Zeile, schmal
    umgebrochen - und liegt nicht ueber dem folgenden Block"""
    from hlidskjalf.editor import make
    v = _open_page(win)
    v.editor.insert(make("table"), after="p1")
    pump(None, 0.8)
    for narrow in (False, True):
        if narrow:
            win.set_default_size(370, 760)
            pump(lambda: win.very_narrow, 3)
            v = win.current
            pump(lambda: v.narrow, 3)
            pump(None, 0.8)
        bar = next(w for w in walk(v) if w.has_css_class("hk-table-bar"))
        pump(lambda: bar.get_width() > 0, 2)
        need = bar.measure(Gtk.Orientation.VERTICAL, bar.get_width())[0]
        assert bar.get_height() >= need, (narrow, bar.get_height(), need, bar.get_width())
        if not narrow:
            assert bar.get_height() < 70, bar.get_height()  # eine Zeile, nicht vier untereinander
        ok, r = bar.compute_bounds(v)
        nxt = bar.get_parent().get_parent()                 # Tabellenblock: was danach kommt, beginnt unter der Leiste
        for ch in walk(bar):
            if isinstance(ch, (Gtk.Button, Gtk.CheckButton)) and ch.get_mapped():
                okc, rc = ch.compute_bounds(bar)
                assert okc and rc.get_y() + rc.get_height() <= bar.get_height() + 1
        assert ok and nxt is not None
    win.set_default_size(1280, 820)
    pump(lambda: not win.very_narrow, 3)


def _peek_props(win):
    from hlidskjalf.database import EditableProps
    v = win.peek[1]
    pump(lambda: any(isinstance(w, EditableProps) for w in walk(v)), 5)
    return v, next(w for w in walk(v) if isinstance(w, EditableProps))


def test_n9_peek_shows_set_properties(win, srv):
    """N9: Status und Datum gesetzt (im Peek selbst bzw. in der Tabelle hinter dem Peek) -> gespeichert und im offenen
    Eintrag sofort angezeigt (nicht weiter "Leer")"""
    from hlidskjalf.database import DatabaseView
    from hlidskjalf.i18n import _
    win.show_root(("page", 10))
    pump(lambda: any(isinstance(w, DatabaseView) for w in walk(win.current)), 5)
    dv = next(w for w in walk(win.current) if isinstance(w, DatabaseView))
    pump(lambda: dv.m.rows, 5)
    win.open_entry(12, "side", [11, 12])
    v, ep = _peek_props(win)

    def shown(row):
        return [w.get_label() for w in walk(ep.get_child_at(1, row)) if isinstance(w, Gtk.Label)]
    assert _("Empty") in shown(1)                          # Datum noch leer
    # 1) in der Tabelle (wie im Testbericht: Zelle hinter dem Peek)
    dv.m.set(12, "dt", "2026-10-20")
    dv.m.set(12, "st", "o1")
    pump(lambda: _peek_props(win)[1] is not ep, 4)
    idle()
    pump(None, 0.5)
    assert srv.notes[12]["props"].get("dt") == "2026-10-20" and srv.notes[12]["props"].get("st") == "o1"
    v, ep = _peek_props(win)
    assert _("Empty") not in shown(1), shown(1)
    assert "Offen" in shown(0), shown(0)
    # 2) im Peek selbst
    ep._save(next(p for p in v.doc.parent_schema["props"] if p["id"] == "st"), "o2")
    pump(None, 0.5)
    idle()
    pump(None, 0.5)
    v, ep = _peek_props(win)
    assert "Fertig" in shown(0) and srv.notes[12]["props"].get("st") == "o2"
    # 3) andere Person aendert die Zeile (live: note.changed mit row)
    srv.notes[12]["props"]["st"] = "o1"
    win._live_event("note.changed", {"id": 10, "row": 12, "rev": None})
    pump(lambda: "Offen" in shown(0) if _peek_props(win)[1] is ep else True, 3)
    pump(None, 0.6)
    v, ep = _peek_props(win)
    assert "Offen" in shown(0)

def test_n3_own_fseq_from_post_live_and_list(srv):
    """N3: eigene Nachricht in einem v2-Chat (andere Instanz = Heim): fseq kommt aus der POST-Antwort, dem Live-Ereignis
    chat.message bzw. einer spaeteren Serverliste an die schon geladene Kopie -> die Bestaetigung (n = fseq) gibt ✓✓"""
    from hlidskjalf import chatstatus as S
    from hlidskjalf.chat import chat, fed_take_fseq
    me = {"features": ["receipts", "online"], "privacy": {"receipts": True, "online": True}}
    c = {"id": 77, "v": 2, "kind": "dm", "members": [{"username": "test", "me": True}, {"username": "@~bert@b.test", "name": "Bert"}],
         "peer": {"username": "@~bert@b.test"}}
    rc = {"@~bert@b.test": {"n": 4, "ts": 1}}
    m1 = {"id": "a" * 32, "seq": 9, "mine": True, "state": "ok", "fseq": None, "fstate": None}
    m2 = {"id": "b" * 32, "seq": 10, "mine": True, "state": "ok", "fseq": None, "fstate": None}
    m3 = {"id": "c" * 32, "seq": 11, "mine": True, "state": "ok", "fseq": None, "fstate": None}
    assert S.tick(me, c, m1, rc) == ("sent", 0, False)       # ohne fseq: nie ✓✓ (Fehlerbild)
    chat.cache[77] = {"msgs": [m1, m2, m3], "loaded": False}
    # Live-Ereignis meldet die Zustellung - die Kopie ist nicht (mehr) als wartend markiert
    chat.on_live("chat.message", {"chat_id": 77, "id": m1["id"], "fseq": 3})
    assert m1["fseq"] == 3
    # Antwort von POST .../messages
    chat._post_fseq(77, m2["id"], 4)
    assert m2["fseq"] == 4
    # spaetere Serverliste (load_messages / Zustell-Abgleich)
    assert fed_take_fseq(chat.cache[77]["msgs"], {m3["id"]: 5, "x": 1}) is True
    assert m3["fseq"] == 5
    assert fed_take_fseq(chat.cache[77]["msgs"], {m3["id"]: None}) is False
    assert S.tick(me, c, m1, rc) == ("read", 1, True)
    assert S.tick(me, c, m2, rc) == ("read", 1, True)
    assert S.tick(me, c, m3, rc) == ("sent", 0, False)       # n=4 < 5: noch nicht gelesen
    chat.cache.pop(77, None)


def test_n5_report_caps_multi(srv):
    """N5: Linux meldet PUT /api/calls/caps {"caps": ["multi"]} (Gruppenanrufe: Einladung + Klingeln, Gespraech im Browser) -
    nur wenn der Server reneg und multi kann, einmal je Geraet; keine Faehigkeiten einer eigenen PeerConnection"""
    from hlidskjalf.calls import calls, device_caps
    from hlidskjalf.chat import chat
    full = {"enabled": True, "features": ["reneg", "pip_hint", "screen2", "multi", "group"]}
    assert device_caps(full) == ["multi"]
    assert device_caps({"enabled": True, "features": ["reneg", "pip_hint"]}) == []         # Server ohne Raum (E0-E3)
    assert device_caps({"enabled": True, "features": ["multi"]}) == []                     # ohne reneg
    assert device_caps({"enabled": False, "features": ["reneg", "multi"]}) == []
    assert device_caps(None) == [] and device_caps({}) == []
    st, keys = chat.status, chat.keys
    dev = "ab" * 16
    try:
        calls.reset()
        chat.status, chat.keys = "ready", {"device": {"id": dev}}
        calls.config = {"enabled": True, "features": ["reneg", "pip_hint"]}
        calls.report_caps()
        pump(None, 0.3)
        assert not [u for m, u, b, c in srv.log if "api/calls/caps" in u]
        calls.config = full
        n0 = len(srv.log)
        calls.report_caps()
        calls.report_caps()                                   # nur einmal
        pump(lambda: any("api/calls/caps" in u for m, u, b, c in srv.log[n0:]), 3)
        sent = [(m, b) for m, u, b, c in srv.log[n0:] if "api/calls/caps" in u]
        assert sent == [("PUT", {"device": dev, "caps": ["multi"]})], sent
    finally:
        chat.status, chat.keys = st, keys
        calls.reset()


def test_n2_same_name_people_distinguishable():
    """N2: zwei lokale "Anna Berg" - Teilen-Suche, Zugriff-Liste und Chat zeigen ein Merkmal (sub vom Server bzw. Handle
    "~…"), nie den Anmeldenamen (aeltere Server liefern ihn als username)"""
    from hlidskjalf.sharing import hit_rows, person_mark, share_rows
    d = {"users": [{"username": "~aaaa1111bbbb2222", "name": "Anna Berg", "sub": "~aaaa1111bbbb2222"},
                   {"username": "~cccc3333dddd4444", "name": "Anna Berg", "sub": "Anna B. (Design)"}]}
    rows = hit_rows(d, {}, "view")
    assert rows[0]["sub"] != rows[1]["sub"]
    assert rows[0]["sub"].endswith("~aaaa1111bbbb2222") and rows[1]["sub"].endswith("Anna B. (Design)")
    # Server ohne "sub": Handle aus username; aelterer Server (Anmeldename): nichts davon zeigen
    assert hit_rows({"users": [{"username": "~eeee5555ffff6666", "name": "Anna Berg"}]}, {}, "view")[0]["sub"].endswith("~eeee5555ffff6666")
    old = hit_rows({"users": [{"username": "fb-anna", "name": "Anna Berg"}]}, {}, "view")[0]["sub"]
    assert "fb-anna" not in old
    lst = share_rows({"shares": [{"type": "user", "target": "~aaaa1111bbbb2222", "name": "Anna Berg", "role": "view"},
                                 {"type": "user", "target": "~cccc3333dddd4444", "name": "Anna Berg", "role": "edit"},
                                 {"type": "user", "target": "fb-anna", "name": "Anna Berg", "role": "edit"}]})
    assert lst[0]["sub"] != lst[1]["sub"] and "fb-anna" not in lst[2]["sub"]
    assert person_mark({"username": "fb-anna", "ref": "~x1"}) == "~x1"          # Mitglieder: ref vor username
    assert person_mark({"username": "fb-anna"}) == "" and person_mark(None) == ""


def test_n10_you_nominative_in_member_list():
    """N10: eigene Zeile der Mitgliederliste "du" (nicht "dir"), eigener Kontext je Sprache; "Erledigt von dir" bleibt"""
    import os
    import re
    here = os.path.dirname(os.path.abspath(__file__))
    want = {"de": "du", "es": "tú", "ru": "ты", "th": "คุณ"}
    for lang, w in want.items():
        po = open(os.path.join(here, "..", "po", f"{lang}.po"), encoding="utf-8").read()
        m = re.search(r'msgctxt "subject"\nmsgid "you"\nmsgstr "([^"]*)"', po)
        assert m and m.group(1) == w, (lang, m and m.group(1))
    src = open(os.path.join(here, "..", "hlidskjalf", "chatview.py"), encoding="utf-8").read()
    assert 'sub.append(_("you"))' not in src and 'sub = [_("you")]' not in src


def _png(w, h):
    import io
    from PIL import Image
    b = io.BytesIO()
    Image.new("RGB", (w, h), (40, 110, 180)).save(b, "PNG")
    return b.getvalue()


def test_n11_narrow_image_no_empty_space(win, srv):
    """N11: 370 px - ein Bildblock (Seite schon mit Bild geladen) ist so hoch wie das Bild bei der echten Breite, kein grosser
    Leerraum darueber (GTK behielt die Hoehe der natuerlichen Breite)"""
    import json
    from hlidskjalf import images
    srv.uploads = getattr(srv, "uploads", {})
    srv.uploads["/files/r2bild"] = _png(800, 450)
    body = json.loads(srv.notes[1]["body"])
    body["blocks"].insert(1, {"id": "img1", "type": "image", "data": {"file": {"url": "/files/r2bild"}}})
    srv.notes[1]["body"] = json.dumps(body)
    win.set_default_size(370, 760)
    pump(lambda: win.very_narrow, 3)
    v = _open_page(win)
    pump(lambda: any(isinstance(w, images.ImageView) and w.dec for w in walk(v)), 5)
    pump(None, 0.8)
    img = next(w for w in walk(v) if isinstance(w, images.ImageView) and w.dec)
    W, H = img.get_width(), img.get_height()
    assert 0 < W <= 340 and abs(H - W * 450 / 800) <= 3, (W, H)
    win.set_default_size(1280, 820)
    pump(lambda: not win.very_narrow, 3)


def test_n11_narrow_device_row_and_db_margin(win, srv, monkeypatch):
    """N11: 370 px - Geraete-Zeile mit Symbol-Knopf statt langem Text (Unterzeile bricht nicht Wort fuer Wort um);
    Datenbank-Seite mit rechtem Rand ("Neu" nicht am Fensterrand)"""
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import ChatDevicesPage
    from hlidskjalf.database import DatabaseView
    from hlidskjalf.i18n import _

    class Win:
        very_narrow = True

    class Dlg:
        win = Win()

        def add_toast(self, t):
            pass
    monkeypatch.setattr(chat, "refresh", lambda *a, **k: None)
    monkeypatch.setattr(chat, "me", {"devices": [{"id": "d1", "name": "vm (Linux)", "created": 1792450000}]})
    page = ChatDevicesPage(Dlg())
    btns = [b for b in walk(page) if isinstance(b, Gtk.Button) and b.has_css_class("destructive-action")]
    assert btns and btns[0].get_icon_name() == "user-trash-symbolic" and btns[0].get_tooltip_text() == _("Remove")
    Dlg.win.very_narrow = False
    page = ChatDevicesPage(Dlg())
    btns = [b for b in walk(page) if isinstance(b, Gtk.Button) and b.has_css_class("destructive-action")]
    assert btns and btns[0].get_icon_name() is None
    win.set_default_size(370, 760)
    pump(lambda: win.very_narrow, 3)
    win.show_root(("page", 10))
    pump(lambda: any(isinstance(w, DatabaseView) for w in walk(win.current)), 5)
    pump(lambda: win.current.narrow, 3)
    pump(None, 0.5)
    dv = next(w for w in walk(win.current) if isinstance(w, DatabaseView))
    assert dv.get_margin_end() >= 8
    win.set_default_size(1280, 820)
    pump(lambda: not win.very_narrow, 3)
