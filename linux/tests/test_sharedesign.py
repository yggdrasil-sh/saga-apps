"""Design-Runde "Teilen + Kommentare" (G1-G12, nur Linux, Nachtrag): Kopf fremder Seiten, Ersatzsymbol fuer nicht
darstellbare Seitensymbole, Entwurf-Banner im Adw.Banner-Aussehen, "Noch keine Seiten", gesperrte Seite mit
Datenbank-Block (kein "Neu", keine Ansichts-Einstellungen, kein "+")."""
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


def test_page_icon_fallback_when_glyph_missing(app):
    from hlidskjalf import images
    assert images.glyphs_ok("Abc") and images.glyphs_ok("")
    bad = "\U0010FFFD"                                   # privater Bereich: keine Schrift hat dafuer ein Zeichen
    assert not images.glyphs_ok(bad)
    w = images.page_icon(bad, size=20)
    assert isinstance(w, Gtk.Image) and w.has_css_class("hk-icon-fallback") and w.get_tooltip_text() == bad
    assert w.get_icon_name() in ("saga-page-symbolic", "text-x-generic-symbolic")
    db = images.page_icon(bad, size=20, fallback="🗃️")
    assert db.get_icon_name() in ("saga-database-symbolic", "x-office-spreadsheet-symbolic")
    big = images.page_icon(bad, size=64)
    assert big.get_pixel_size() == 48
    if images.glyphs_ok("📄"):                           # Farb-Emoji-Schrift da (RPM: Requires, Test-Container)
        assert isinstance(images.page_icon("📄"), Gtk.Label)


def test_foreign_page_head_under_title_and_draft_banner(win, srv):
    from hlidskjalf import page as page_mod
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt", role="view", updated_by="anna", blocks=[
        {"id": "a", "type": "paragraph", "data": {"text": "Start"}}])
    store.refresh()
    idle()
    v = _open(win, 200)
    ws = _walk(v.scroll.get_child())
    sb = next(w for w in ws if w.has_css_class("hk-sharedby"))
    meta = next(w for w in ws if isinstance(w, Gtk.Label) and (w.get_label() or "").startswith("Last changed by"))
    # G1: Etikett UNTER dem Titel, darunter "Zuletzt geaendert von …", gleiche Groesse (nicht "caption")
    assert ws.index(v.title_rt) < ws.index(sb) < ws.index(meta)
    assert sb.has_css_class("hk-pagemeta") and meta.has_css_class("hk-pagemeta") and not meta.has_css_class("caption")
    # Entwurf-Banner: Aussehen von Adw.Banner (CSS-Namen), "Verwerfen" flach statt rot
    frame = v.copy_rev.get_parent()
    assert isinstance(frame, page_mod.BannerBox) and frame.get_css_name() == "banner"
    assert v.copy_rev.get_child().get_css_name() == "widget"
    assert v.copy_discard.has_css_class("flat") and not v.copy_discard.has_css_class("destructive-action")


def test_sidebar_no_pages_yet(win, srv):
    from hlidskjalf.store import store
    srv.notes.clear()
    store.refresh()
    assert pump(lambda: "No pages yet" in [w.get_label() for w in _walk(win.sidebar.box) if isinstance(w, Gtk.Label)], 4)


def test_locked_page_hides_database_block_actions(win, srv):
    """Nachtrag: gesperrte Seite mit Datenbank-Block - kein "Neu", keine Ansichts-Einstellungen, kein "+" (Ansicht);
    nach dem Entsperren wieder da"""
    import json
    from hlidskjalf.database import DatabaseView
    from hlidskjalf.store import store
    srv.notes[3]["body"] = json.dumps({"blocks": [{"id": "d1", "type": "database", "data": {"db": 10, "view": "tabelle"}}]})
    srv.notes[3]["locked"] = True
    store.refresh()
    idle()
    v = _open(win, 3)

    def dbview():
        return next(w for w in _walk(v.scroll.get_child()) if isinstance(w, DatabaseView))

    def state():
        dv = dbview()
        ws = _walk(dv)
        news = [w for w in ws if isinstance(w, Gtk.Button) and w.get_label() == "New"
                or isinstance(w, Gtk.Label) and w.get_label() == "New" and w.get_mapped()]
        adds = [w for w in ws if isinstance(w, Gtk.MenuButton) and w.get_tooltip_text() == "Add view"]
        return dv, bool(news), bool(adds), dv.bar.settings.get_visible()

    assert pump(lambda: dbview().m.loaded, 5)
    idle()
    dv, new, add, settings = state()
    assert v.editor.locked and dv.m.host_locked and not dv.m.can_add
    assert not new and not add and not settings
    rows = len(srv.notes)
    dv.new_entry()                                       # auch auf anderem Weg nichts anlegen
    pump(None, 0.3)
    assert len(srv.notes) == rows
    v.set_locked(False)
    assert pump(lambda: not v.editor.locked and dbview().m.loaded and not dbview().m.host_locked, 5)
    idle()
    dv, new, add, settings = state()
    assert new and add and settings
