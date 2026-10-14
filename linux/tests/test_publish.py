"""Veroeffentlichen: Zustand -> Anzeige, Namensschritt (need_name), oeffentlich ueber Elternseite (published_via),
Fehlerfaelle, Kopfleiste, Seitenmenue, Einstellungen -> Konto, Accept-Language."""
import io
import urllib.error

import pytest
from gi.repository import Adw

from hk_helpers import idle, pump

T0 = 1760000000.0          # fester Zeitpunkt fuer "beendet am ..."


def _open_page(win, nid=1):
    win.show_root(("page", nid))
    v = win.current
    pump(lambda: v.doc.fetched, 6)
    pump(None, 0.4)                                      # Antwort des Servers nach dem Zwischenspeicher
    idle()
    return v


def _dialog(win, nid=1):
    from hlidskjalf.publish import PublishDialog, open_publish
    open_publish(win, nid)
    pump(lambda: isinstance(win.get_visible_dialog(), PublishDialog), 5)
    d = win.get_visible_dialog()
    assert isinstance(d, PublishDialog)
    return d


def _tag(d):
    return d.nav.get_visible_page().get_tag()


@pytest.fixture
def auto_confirm(monkeypatch):
    """Rueckfragen (Adw.AlertDialog) beantworten: answers[0] = True (ok) / False (abbrechen); asked: Ueberschriften"""
    from hlidskjalf import publish
    state = {"ok": True, "asked": []}

    def fake(parent, heading, body, label, ok, cancelled=None):
        state["asked"].append(heading)
        if state["ok"]:
            ok()
        elif cancelled:
            cancelled()
    monkeypatch.setattr(publish, "confirm", fake)
    return state


@pytest.fixture
def toasts(monkeypatch):
    from hlidskjalf import publish
    out = []
    monkeypatch.setattr(publish.PublishDialog, "toast", lambda self, t: t and out.append(str(t)))
    return out


# --- Logik ohne GTK ------------------------------------------------------------------------------------------
BASE = {"enabled": True, "name": "anna", "published": False, "home": False, "url": None, "profile_url": "https://saga.test/@anna",
        "published_via": None, "withdrawn_at": None, "withdrawn_by": None, "slug": "projekt", "children": False,
        "indexable": False, "profile": False, "profile_other": None}


def test_view_not_published():
    from hlidskjalf.publish import view
    v = view(dict(BASE))
    assert v["switch_title"] == "Public" and v["switch_active"] is False and v["switch_sensitive"] is True
    assert v["link"] is None and not v["stop_visible"] and not v["slug_button_visible"]
    assert v["address_note"] == "https://saga.test/@anna/projekt"           # Vorschau, folgt dem Adressfeld
    assert view(dict(BASE), " Neu ")["address_note"] == "https://saga.test/@anna/neu"
    assert view(dict(BASE), "")["address_note"].endswith("/@anna/…")
    assert v["withdrawn"] is None and v["disabled_note"] is None and v["via"] is None
    assert "@anna" in v["name_note"] and "Shown at your profile address" in v["profile_hint"]


def test_view_published_and_address_change():
    from hlidskjalf.publish import view
    st = dict(BASE, published=True, url="https://saga.test/@anna/projekt", home=True)
    v = view(st)
    assert v["switch_active"] and v["link"] == st["url"] and v["stop_visible"] and v["home"]
    assert v["slug_button_visible"] and not v["slug_button_sensitive"] and not v["address_changed"]
    assert v["address_note"] == "https://saga.test/@anna/projekt"
    v2 = view(st, "projekt-neu")
    assert v2["slug_button_sensitive"] and v2["address_changed"] and v2["address_note"] == "Only applied with “Change address”."
    assert not view(st, "  ")["slug_button_sensitive"]


def test_view_withdrawn_disabled_profile():
    from hlidskjalf.publish import view
    v = view(dict(BASE, withdrawn_at=T0, withdrawn_by="user"))
    assert v["withdrawn"].startswith("Stopped publishing on ") and not v["withdrawn_admin"]
    v = view(dict(BASE, withdrawn_at=T0, withdrawn_by="admin"))
    assert v["withdrawn"].startswith("Withdrawn by the admin on ") and v["withdrawn_admin"]
    v = view(dict(BASE, enabled=False))
    assert v["disabled_note"] and v["switch_sensitive"] is False
    assert view(dict(BASE, enabled=False, published=True, url="u"))["switch_sensitive"] is True   # beenden geht immer
    v = view(dict(BASE, profile_other={"note_id": 9, "title": "Über mich"}))
    assert v["profile_hint"] == "Replaces your current profile page “Über mich”."
    # veroeffentlicht: kein Hinweis "beendet am"
    assert view(dict(BASE, published=True, url="u", withdrawn_at=T0, withdrawn_by="user"))["withdrawn"] is None


def test_view_published_via():
    from hlidskjalf.publish import view
    via = {"note_id": 1, "title": "Startseite", "url": "https://saga.test/@anna/start/2"}
    v = view(dict(BASE, published_via=via))
    assert v["via"] == via and "“Startseite”" in v["via_text"]
    assert v["switch_title"] == "Also publish at its own address"
    v = view(dict(BASE, published=True, url="u", published_via=via))            # selbst oeffentlich: kein via-Hinweis
    assert v["via"] is None and v["switch_title"] == "Public"


def test_header_state_and_available():
    from hlidskjalf.publish import apply_state, available, header_state
    assert header_state({"published": False}) is None and header_state(None) is None
    hs = header_state({"published": True, "public_url": "https://saga.test/@a/x"})
    assert hs["label"] == "Public" and hs["url"].endswith("/@a/x") and "click for settings" in hs["tooltip"]
    long = "Ein sehr langer Seitentitel, der gekuerzt wird"
    hs = header_state({"published": False, "published_via": {"note_id": 1, "title": long, "url": "https://saga.test/@a/x/2"}})
    assert hs["label"].startswith("Public (via “Ein sehr") and "…" in hs["label"] and long in hs["tooltip"]
    assert available({"published": False}) and not available({"title": "alt"}) and not available({"published": False, "template": True})
    p = {"published": False}
    apply_state(p, {"published": True, "url": "https://saga.test/@a/x", "published_via": None})
    assert p == {"published": True, "public_url": "https://saga.test/@a/x", "published_via": None}


def test_need_name_and_errors(srv):
    """409 need_name: APIError behaelt die JSON-Antwort (Vorschlag), Fehlertext vom Server"""
    from hlidskjalf.api import APIError, client
    from hlidskjalf.publish import error_text, need_name, saved_text, suggested_name
    assert need_name({"name": None}) and not need_name({"name": "anna"})
    with pytest.raises(APIError) as ei:
        client.json("PUT", "api/notes/1/publish", {})
    e = ei.value
    assert e.code == 409 and need_name(e) and suggested_name(e) == "test-autor"
    assert str(e) == "Bitte zuerst einen öffentlichen Namen wählen."
    srv.public_name = "anna"
    srv.notes[3]["template"] = True
    with pytest.raises(APIError) as ei:
        client.json("PUT", "api/notes/3/publish", {})
    assert ei.value.code == 400 and not need_name(ei.value) and "Vorlagen" in str(ei.value)
    srv.publish_api = False
    with pytest.raises(APIError) as ei:
        client.get("api/notes/1/publish")
    assert error_text(ei.value) == "This server doesn't support publishing yet."
    assert saved_text({"replaced_profile": {"title": "Alt"}}).endswith("“Alt”.") and saved_text({}) == "✓ Saved"


def test_accept_language_header(monkeypatch):
    """Fehlertexte des Servers in der App-Sprache: jede Anfrage traegt Accept-Language (und X-Saga)"""
    from hlidskjalf import api
    seen = []

    class Opener:
        def open(self, req, timeout=None):
            seen.append((req.get_header("Accept-language"), req.get_header("X-saga")))
            raise urllib.error.HTTPError(req.full_url, 404, "nf", None, io.BytesIO(b"{}"))
    monkeypatch.setattr(api, "_opener", Opener())
    code, _ = api.Client._send(api.client, "GET", "api/version", base="https://saga.test", auth=False)
    assert code == 404 and seen == [(api.ACCEPT_LANGUAGE, "1")] and api.ACCEPT_LANGUAGE


# --- Dialog ---------------------------------------------------------------------------------------------------
def test_name_step_then_publish(win, srv, auto_confirm, toasts):
    """Ohne oeffentlichen Namen: Namensschritt mit Vorschlag und Pruefung beim Tippen, dann Einstellungen; Veroeffentlichen,
    Option, Adresse aendern (mit Rueckfrage), Kopfleiste und Seitenmenue folgen."""
    v = _open_page(win, 1)
    assert not win.pub_btn.get_visible()
    d = _dialog(win)
    assert _tag(d) == "name" and d.name_row.get_text() == "test-autor"
    assert pump(lambda: d.go_btn.get_sensitive(), 3) and d.name_status.get_label() == "✓ Available"
    d.name_row.set_text("vergeben")
    assert not d.go_btn.get_sensitive()
    assert pump(lambda: d.name_status.get_label() == "Dieser Name ist schon vergeben.", 3)
    assert not d.go_btn.get_sensitive() and d.name_status.has_css_class("error")
    d.name_row.set_text("X")
    assert pump(lambda: d.name_status.get_label().startswith("2–32"), 3)
    d.name_row.set_text("Anna ")
    assert d.name_url.get_label() == "https://saga.test/@anna"
    assert pump(lambda: d.go_btn.get_sensitive(), 3)
    d.go_btn.emit("clicked")
    assert pump(lambda: _tag(d) == "main", 5) and srv.public_name == "anna"
    assert not d.sw.get_active() and not d.g_link.get_visible() and not d.slug_btn.get_visible()
    assert d.l_addr.get_label() == "https://saga.test/@anna/startseite"
    # Option vor dem Veroeffentlichen: nur gemerkt, geht beim Einschalten mit
    n_put = len([x for x in srv.log if x[0] == "PUT"])
    d.sw_index.set_active(True)
    idle()
    assert len([x for x in srv.log if x[0] == "PUT"]) == n_put
    d.sw.set_active(True)
    assert pump(lambda: srv.pubs.get(1, {}).get("active"), 5)
    idle()
    assert srv.pubs[1]["indexable"] is True and srv.pubs[1]["slug"] == "startseite"
    assert d.g_link.get_visible() and "https://saga.test/@anna/startseite" in d.link_row.get_subtitle()
    assert win.pub_btn.get_visible() and win.pub_content.get_label() == "Public"
    assert v.doc.page["published"] is True and toasts[-1] == "✓ Saved"
    # Option nach dem Veroeffentlichen: sofort gespeichert
    d.sw_children.set_active(True)
    idle()
    assert srv.pubs[1]["children"] is True
    # Adresse aendern: Knopf erst bei geaenderter Adresse, mit Rueckfrage
    assert d.slug_btn.get_visible() and not d.slug_btn.get_sensitive()
    d.slug.set_text("mein-projekt")
    assert d.slug_btn.get_sensitive() and d.l_addr.get_label() == "Only applied with “Change address”."
    d.slug_btn.emit("clicked")
    idle()
    assert auto_confirm["asked"][-1] == "Change address?"
    assert srv.pubs[1]["slug"] == "mein-projekt" and toasts[-1] == "✓ New address saved"
    assert d.link_row.get_subtitle().endswith("/@anna/mein-projekt") and not d.slug_btn.get_sensitive()
    # Seitenmenue: Eintrag mit Haken
    from hlidskjalf import page as page_mod
    seen = []
    orig = page_mod.menu_popover
    page_mod.menu_popover = lambda parent, ents: (seen.append(ents), orig(parent, ents))[1]
    try:
        v.page_menu(win.menu_btn)
    finally:
        page_mod.menu_popover = orig
    labels = [e[0] for e in seen[0] if e]
    assert "✓ Publish …" in labels
    d.force_close()


def test_stop_publishing_asks(win, srv, auto_confirm, toasts):
    """Beenden nur nach Rueckfrage; Abbrechen laesst alles, wie es ist; danach Hinweis "beendet am"."""
    srv.public_name = "anna"
    srv.pubs[1] = {"slug": "start", "children": False, "indexable": False, "profile": False, "active": True,
                   "withdrawn_at": None, "withdrawn_by": None}
    _open_page(win, 1)
    assert win.pub_btn.get_visible()
    d = _dialog(win)
    assert _tag(d) == "main" and d.sw.get_active() and d.g_end.get_visible()
    auto_confirm["ok"] = False
    d.sw.set_active(False)
    idle()
    assert auto_confirm["asked"] == ["Stop publishing?"] and d.sw.get_active() and srv.pubs[1]["active"]
    auto_confirm["ok"] = True
    d.stop_row.emit("activated")
    assert pump(lambda: not srv.pubs[1]["active"], 5)
    idle()
    assert not d.sw.get_active() and not d.g_end.get_visible() and not d.g_link.get_visible()
    assert d.l_withdrawn.get_visible() and d.l_withdrawn.get_label().startswith("Stopped publishing on ")
    assert toasts[-1] == "No longer published." and not win.pub_btn.get_visible()
    d.force_close()


def test_need_name_during_put_returns_to_name_step(win, srv, toasts):
    """Name inzwischen weg (anderes Geraet): 409 need_name beim Speichern -> Namensschritt mit Vorschlag"""
    srv.public_name = "anna"
    _open_page(win, 3)
    d = _dialog(win, 3)
    assert _tag(d) == "main"
    srv.public_name = None
    d.sw.set_active(True)
    assert pump(lambda: _tag(d) == "name", 5)
    assert d.name_row.get_text() == "test-autor" and not srv.pubs
    d.force_close()


def test_published_via_parent(win, srv):
    """Unterseite einer mit Unterseiten veroeffentlichten Seite: Hinweis in Kopfleiste und Dialog, eigener Schalter-Text"""
    srv.public_name = "anna"
    srv.pubs[1] = {"slug": "start", "children": True, "indexable": False, "profile": False, "active": True,
                   "withdrawn_at": None, "withdrawn_by": None}
    v = _open_page(win, 2)
    assert v.doc.page["published"] is False and v.doc.page["published_via"]["note_id"] == 1
    assert win.pub_btn.get_visible() and win.pub_content.get_label() == "Public (via “Startseite”)"
    d = _dialog(win, 2)
    assert d.g_via.get_visible() and d.l_via.get_visible() and "“Startseite”" in d.l_via.get_label()
    assert d.sw.get_title() == "Also publish at its own address" and not d.sw.get_active()
    assert d.via_row.get_subtitle() == "https://saga.test/@anna/start/2"
    d._open_via()
    assert pump(lambda: win.route == ("page", 1) and win.get_visible_dialog() is None, 3)


def test_errors_shown_and_state_kept(win, srv, auto_confirm, toasts):
    """Fehler vom Server (Adresse belegt) als Meldung; Zustand bleibt; abgeschaltet: Hinweis, Schalter gesperrt"""
    srv.public_name = "anna"
    srv.pubs[3] = {"slug": "belegt", "children": False, "indexable": False, "profile": False, "active": True,
                   "withdrawn_at": None, "withdrawn_by": None}
    srv.pubs[1] = {"slug": "start", "children": False, "indexable": False, "profile": False, "active": True,
                   "withdrawn_at": None, "withdrawn_by": None}
    _open_page(win, 1)
    d = _dialog(win)
    d.slug.set_text("belegt")
    d.slug.emit("entry-activated")
    idle()
    assert toasts[-1] == "Diese Adresse benutzt schon eine andere deiner Seiten."
    assert srv.pubs[1]["slug"] == "start" and d.st["slug"] == "start" and d.slug_btn.get_sensitive()
    d.force_close()
    srv.publish_enabled = False
    srv.pubs.pop(1)
    d = _dialog(win)
    assert d.l_disabled.get_visible() and "turned off publishing" in d.l_disabled.get_label()
    assert not d.sw.get_sensitive()
    d.force_close()


def test_older_server_and_templates(win, srv, monkeypatch):
    """Aeltere Server (404, keine Felder): kein Menuepunkt, kein Hinweis; Vorlagen: kein Menuepunkt"""
    from hlidskjalf.publish import available, open_publish
    srv.publish_api = False
    v = _open_page(win, 1)
    assert not available(v.doc.page) and not win.pub_btn.get_visible()
    assert not [a for a in win.palette_actions() if a[1] == "Publish …"]
    shown = []
    monkeypatch.setattr(win, "toast", lambda m: shown.append(m))
    open_publish(win, 1)
    assert pump(lambda: shown, 3) and shown[0] == "This server doesn't support publishing yet."
    srv.publish_api = True
    srv.notes[3]["template"] = True
    v = _open_page(win, 3)
    assert "published" in v.doc.page and not available(v.doc.page)
    srv.notes[3]["template"] = False
    v = _open_page(win, 3)
    assert [a for a in win.palette_actions() if a[1] == "Publish …"]


def test_settings_public_name(win, srv, auto_confirm):
    """Einstellungen -> Konto: eigener Block, Pruefung beim Tippen, Rueckfrage beim Aendern mit oeffentlichen Seiten"""
    from hlidskjalf.publish import PublicNameGroup
    g = PublicNameGroup(win)
    assert pump(lambda: g.get_visible(), 3)
    assert g.row.get_text() == "" and "Set when you first publish" in g.get_description()
    g.row.set_text("vergeben")
    assert pump(lambda: g.status.get_label() == "Dieser Name ist schon vergeben.", 3) and not g.btn.get_sensitive()
    g.row.set_text("anna")
    assert pump(lambda: g.btn.get_sensitive(), 3)
    g.btn.emit("clicked")
    assert pump(lambda: srv.public_name == "anna", 3)
    pump(lambda: g.status.get_label() == "✓ Saved", 3)
    assert g.status.get_label() == "✓ Saved" and "https://saga.test/@anna" in g.get_description()
    assert auto_confirm["asked"] == []                     # erster Name: keine Rueckfrage
    # schon oeffentliche Seiten: Rueckfrage, alte Links gehen nicht mehr
    srv.pubs[1] = {"slug": "start", "children": False, "indexable": False, "profile": False, "active": True,
                   "withdrawn_at": None, "withdrawn_by": None}
    g2 = PublicNameGroup(win)
    assert pump(lambda: g2.get_visible() and g2.row.get_text() == "anna", 3)
    g2.row.set_text("anna2")
    assert pump(lambda: g2.btn.get_sensitive(), 3)
    g2.row.emit("entry-activated")
    assert pump(lambda: srv.public_name == "anna2", 3)
    assert auto_confirm["asked"] == ["Change your public name?"]
    # aelterer Server: Block bleibt unsichtbar
    srv.publish_api = False
    g3 = PublicNameGroup(win)
    pump(None, 0.5)
    assert not g3.get_visible()


def test_dialog_keyboard_and_names(win, srv):
    """Bedienung per Tastatur/Screenreader: Schalter und Felder fokussierbar, Knoepfe mit Text, Statuszeile angesagt"""
    from gi.repository import Gtk
    srv.public_name = "anna"
    _open_page(win, 1)
    d = _dialog(win)
    for w in (d.sw, d.slug, d.sw_children, d.sw_index, d.sw_profile):
        assert w.get_focusable() or w.get_can_focus()
    for b in (d.slug_btn,):
        assert b.get_label()
    assert isinstance(d.stop_row, Adw.PreferencesRow)
    d.force_close()
    from hlidskjalf.publish import status_label
    lb = status_label()
    assert lb.get_accessible_role() in (Gtk.AccessibleRole.STATUS, Gtk.AccessibleRole.LABEL)
