"""Bedienung ohne Maus und Touch, Teil-Neuaufbau des Editors, Rueckfragen, Darstellung."""
import json
import time

from gi.repository import Adw, Gdk, Gtk

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


def test_sidebar_is_keyboard_listbox(win, srv):
    """H5: Seitenleiste = Gtk.ListBox (navigation-sidebar), Zeilen fokussierbar und mit Namen fuer Screenreader"""
    from hlidskjalf.window import SidebarRow
    sb = win.sidebar
    assert isinstance(sb.box, Gtk.ListBox) and sb.box.has_css_class("navigation-sidebar")
    rows = [r for r in walk(sb.box) if isinstance(r, SidebarRow)]
    assert rows and all(r.get_focusable() for r in rows)
    assert rows[0].get_accessible_role() == Gtk.AccessibleRole.LIST_ITEM
    page_row = next(r for r in rows if r.page and r.page["id"] == 1)
    page_row.emit("activate")
    pump(None, 0.2)
    assert win.route == ("page", 1)
    # Rechts klappt auf (Startseite hat eine Unterseite), Links wieder zu
    tree_row = next(r for r in walk(sb.box) if isinstance(r, SidebarRow) and r.kids and r.page["id"] == 1)
    assert sb.row_key(tree_row, Gdk.KEY_Right, 0) is True and 1 in sb.expanded
    tree_row = next(r for r in walk(sb.box) if isinstance(r, SidebarRow) and r.kids and r.page["id"] == 1)
    assert sb.row_key(tree_row, Gdk.KEY_Left, 0) is True and 1 not in sb.expanded


def test_tab_leaves_text_block(win, srv):
    """M10: Tab bleibt nicht im Textblock haengen; in Listen rueckt es ein, im Code ist es ein Tabulator"""
    v = _open_page(win)
    kit = v.kit
    para = kit.by_key["p1"]
    assert kit.on_key(para, Gdk.KEY_Tab, 0) is False
    assert kit.on_key(para, Gdk.KEY_Tab, Gdk.ModifierType.CONTROL_MASK) is False
    item = kit.by_key["l2/1"]
    assert kit.on_key(item, Gdk.KEY_Tab, 0) is True       # Listenpunkt einruecken


def test_menu_key_opens_block_menu(win, srv):
    """M11: Menue-Taste und Umschalt+F10 oeffnen das Blockmenue (auch ohne sichtbare Griffe)"""
    v = _open_page(win)
    opened = []
    v.kit.block_menu = lambda parent, bid: opened.append(bid)
    para = v.kit.by_key["p1"]
    assert v.kit.on_key(para, Gdk.KEY_Menu, 0) is True
    assert v.kit.on_key(para, Gdk.KEY_F10, Gdk.ModifierType.SHIFT_MASK) is True
    assert opened == ["p1", "p1"]


def test_hidden_handles_not_in_tab_order_and_shown_on_focus(win, srv):
    """M9: Griffe nicht in der Tab-Reihenfolge; Fokus im Block zeigt sie"""
    from hlidskjalf.blocks import BlockRow
    v = _open_page(win)
    rows = [r for r in walk(v) if isinstance(r, BlockRow)]
    btns = [b for r in rows for b in walk(r.handle) if isinstance(b, Gtk.Button)]
    assert btns and not any(b.get_focusable() for b in btns)
    v.kit.by_key["p1"].grab_focus()
    pump(None, 0.2)
    row = next(r for r in rows if r.bid == "p1")
    assert row.handle.has_css_class("hk-on")


def test_visible_more_buttons(win, srv):
    """M11: sichtbare "⋮"-Knoepfe: Tabelle (Block), Datenbank-Zeilen, Aufgaben, Ansicht"""
    v = _open_page(win)
    pump(None, 1.0)
    icons = [w for w in walk(v) if isinstance(w, Gtk.MenuButton) and w.get_icon_name() == "view-more-symbolic"]
    assert len(icons) >= 3                                 # Tabelle + 2 DB-Zeilen + Ansicht
    win.show_root(("tasks",))
    pump(None, 0.5)
    assert [w for w in walk(win.current) if isinstance(w, Gtk.MenuButton) and w.get_icon_name() == "view-more-symbolic"]


def test_incremental_rebuild_keeps_untouched_blocks(win, srv):
    """H4: Enter/Struktur aendert nur betroffene Bloecke; eingebettete Datenbank wird nicht neu erzeugt/geladen"""
    from hlidskjalf.database import DatabaseView
    v = _open_page(win)
    pump(None, 1.0)
    dbv = [w for w in walk(v) if isinstance(w, DatabaseView)]
    assert len(dbv) == 1
    head = v.kit.by_key["h1"]
    srv.log.clear()
    v.editor.insert({"id": "neu1", "type": "paragraph", "data": {"text": "neu"}}, after="p1")
    pump(None, 0.3)
    assert [w for w in walk(v) if isinstance(w, DatabaseView)] == dbv          # dasselbe Objekt
    assert v.kit.by_key["h1"] is head
    assert not [x for x in srv.log if x[1].endswith("/rows")], "eingebettete Datenbank wurde neu geladen"
    # lange Seite: Enter baut nicht alles neu
    srv.notes[3]["body"] = json.dumps({"blocks": [{"id": f"b{i}", "type": "paragraph", "data": {"text": f"Absatz {i}"}} for i in range(300)]})
    v = _open_page(win, 3)
    t = time.perf_counter()
    v.editor.insert({"id": "x1", "type": "paragraph", "data": {"text": ""}}, after="b150")
    dt = time.perf_counter() - t
    print("Teil-Neuaufbau bei 300 Absaetzen:", round(dt * 1000), "ms")
    assert dt < 0.5


def test_trash_delete_asks(win, srv):
    """M12: Endgueltig loeschen fragt nach"""
    from hlidskjalf.dialogs import TrashDialog
    srv.trash.append({"id": 77, "title": "Alt"})
    d = TrashDialog(win)
    d.present(win)
    pump(lambda: [b for b in walk(d) if isinstance(b, Gtk.Button) and b.get_label() and b.has_css_class("destructive-action")
                  and b is not d.empty_btn], 3)
    btn = [b for b in walk(d) if isinstance(b, Gtk.Button) and b.has_css_class("destructive-action") and b is not d.empty_btn][0]
    btn.emit("clicked")
    pump(None, 0.3)
    assert not [x for x in srv.log if x[0] == "DELETE" and "/api/trash/" in x[1]]
    d.force_close()


def test_task_delete_undo(win, srv):
    """M12: Aufgabe loeschen mit Rueckgaengig - vor Ablauf der Frist geht nichts an den Server"""
    from hlidskjalf.store import store
    pump(lambda: store.tasks, 3)
    tid = store.tasks[0]["id"]
    store.delete_task(tid)
    assert tid not in [t["id"] for t in store.tasks]
    store.undo_delete_task(tid)
    assert tid in [t["id"] for t in store.tasks]
    idle()
    assert not [x for x in srv.log if x[0] == "DELETE"]


def test_event_color_validated(win, srv):
    """N9: Kalenderfarbe vom Server landet nicht ungeprueft im CSS"""
    from hlidskjalf.agenda import EventRow
    r = EventRow({"summary": "x", "start": "2026-10-14T10:00:00", "color": "#e01b24; } * { color: red"})
    assert r.get_first_child().__class__.__name__ == "DrawingArea"


def test_dark_plaque_class(win, srv):
    sm = Adw.StyleManager.get_default()
    sm.set_color_scheme(Adw.ColorScheme.FORCE_DARK)
    pump(None, 0.1)
    assert win.has_css_class("hk-dark")
    sm.set_color_scheme(Adw.ColorScheme.FORCE_LIGHT)
    pump(None, 0.1)
    assert not win.has_css_class("hk-dark")


def test_markup_in_errors(win, srv):
    """N1: Fehlertexte mit & und < werden maskiert (kein leerer Text)"""
    srv.fail["GET api/notes/3"] = 500
    srv.notes[3]["title"] = "A & B"
    win.show_root(("page", 3))
    v = win.current
    pump(lambda: v.doc.loaded, 5)
    pump(None, 0.2)
    sp = v.scroll.get_child()
    sp = sp.get_child() if not isinstance(sp, Adw.StatusPage) and hasattr(sp, "get_child") else sp
    assert isinstance(sp, Adw.StatusPage) and sp.get_description()


def test_shortcuts_dialog(win, srv):
    win.shortcuts()
    pump(None, 0.2)
    d = win.get_visible_dialog()
    assert d is not None
    d.force_close()


def test_login_page_has_headerbar(win, srv):
    login = win.stack.get_child_by_name("login")
    assert isinstance(login, Adw.ToolbarView)
    assert any(isinstance(w, Adw.HeaderBar) for w in walk(login))
