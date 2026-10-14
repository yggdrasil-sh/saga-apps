"""Veroeffentlichen: Zustand -> Anzeige, Namensschritt (need_name), oeffentlich ueber Elternseite (published_via),
Fehler am Feld, Rueckfragen, Kopfleiste, Seitenmenue, Einstellungen -> Konto, Accept-Language, eigenes Symbol."""
import io
import os
import urllib.error
import xml.etree.ElementTree as ET

import pytest
from gi.repository import Adw, Gtk

from hk_helpers import idle, pump

T0 = 1760000000.0          # fester Zeitpunkt fuer "beendet am ..."
LINUX = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


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
    pump(None, 0.2)
    return d


def _tag(d):
    return d.nav.get_visible_page().get_tag()


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _apply_shown(d):
    """Uebernehmen-Knopf im Adressfeld sichtbar (libadwaita blendet ihn ueber den Elternteil ein/aus)?"""
    pump(None, 0.1)
    bs = [w for w in _walk(d.slug) if isinstance(w, Gtk.Button) and w.get_icon_name() == "adw-entry-apply-symbolic"]
    assert bs, "kein Uebernehmen-Knopf"
    return bs[0].get_mapped()


@pytest.fixture
def auto_confirm(monkeypatch):
    """Rueckfragen (Adw.AlertDialog) beantworten: ok True (ok) / False (abbrechen); asked: (Ueberschrift, Text, Knopf)"""
    from hlidskjalf import publish
    state = {"ok": True, "asked": [], "full": []}

    def fake(parent, heading, body, label, ok, cancelled=None, destructive=True):
        state["asked"].append(heading)
        state["full"].append((heading, body, label, destructive))
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
    """Privat: Link-Karte mit kuenftiger Adresse (gedaempft, folgt dem Adressfeld), Optionen mit Ueberschrift"""
    from hlidskjalf.publish import view
    v = view(dict(BASE))
    assert v["switch_title"] == "Public" and v["switch_active"] is False and v["switch_sensitive"] is True
    assert v["link"] == "https://saga.test/@anna/projekt" and v["link_active"] is False and v["link_visible"]
    assert view(dict(BASE), " Neu ")["link"] == "https://saga.test/@anna/neu"
    assert view(dict(BASE), "")["link"].endswith("/@anna/…")
    assert not v["stop_visible"] and not v["slug_apply"] and v["address_note"] is None and not v["address_changed"]
    assert view(dict(BASE), "anders")["address_note"] is None                # privat: gilt beim Veroeffentlichen
    assert v["options_title"] == "Applies once the page is public" and v["options_visible"] and v["address_visible"]
    assert v["withdrawn"] is None and v["disabled_note"] is None and v["via"] is None and v["profile_link"] is None
    assert "@anna" in v["name_note"] and "Shown at your profile address" in v["profile_hint"]


def test_view_published_and_address_change():
    from hlidskjalf.publish import view
    st = dict(BASE, published=True, url="https://saga.test/@anna/projekt", home=True)
    v = view(st)
    assert v["switch_active"] and v["link"] == st["url"] and v["link_active"] and v["stop_visible"] and v["home"]
    assert v["slug_apply"] and not v["address_changed"] and v["address_note"] is None and v["options_title"] is None
    v2 = view(st, "projekt-neu")
    assert v2["address_changed"] and v2["address_note"] == "Takes effect only when you tap “Change address”."
    assert v2["link"] == st["url"]                                          # bis zum Uebernehmen gilt der alte Link
    assert not view(st, "  ")["address_changed"] and not view(st, " Projekt ")["address_changed"]
    # Profilseite: zweite Zeile "Profiladresse"
    assert view(dict(st, profile=True))["profile_link"] == "https://saga.test/@anna"
    assert view(dict(BASE, profile=True))["profile_link"] is None             # privat: noch keine Profiladresse


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
    """Ueber Elternseite: eigener Schalter-Text; Adresse und Optionen erst mit "Zusaetzlich ..." (= selbst oeffentlich)"""
    from hlidskjalf.publish import view
    via = {"note_id": 1, "title": "Startseite", "url": "https://saga.test/@anna/start/2"}
    v = view(dict(BASE, published_via=via))
    assert v["via"] == via and "“Startseite”" in v["via_text"]
    assert v["switch_title"] == "Also publish at its own address"
    assert not v["address_visible"] and not v["options_visible"] and not v["link_visible"]
    v = view(dict(BASE, published=True, url="u", published_via=via))            # selbst oeffentlich: kein via-Hinweis
    assert v["via"] is None and v["switch_title"] == "Public" and v["address_visible"] and v["options_visible"]


def test_display_url_breaks_only_after_slash():
    """Anzeige: Umbruch nur nach "/" (U+200B), am Bindestrich nie (U+2060); ohne diese Zeichen = die echte Adresse"""
    from hlidskjalf.publish import WJ, ZWSP, display_url
    u = "https://saga.test/@anna-b/reise-bericht/2"
    d = display_url(u)
    assert d.replace(ZWSP, "").replace(WJ, "") == u
    assert d.startswith("https://" + ZWSP + "saga.test/" + ZWSP + "@anna-" + WJ + "b/" + ZWSP)
    assert "/" + ZWSP + "/" not in d and display_url("") == "" and display_url(None) == ""


def test_header_state_and_available():
    """Kopfleiste: nur "Oeffentlich"; "ueber <Elternseite>" steht im Tooltip"""
    from hlidskjalf.publish import apply_state, available, header_state
    assert header_state({"published": False}) is None and header_state(None) is None
    hs = header_state({"published": True, "public_url": "https://saga.test/@a/x"})
    assert hs["label"] == "Public" and hs["url"].endswith("/@a/x") and "click for settings" in hs["tooltip"]
    long = "Ein sehr langer Seitentitel, der nicht gekuerzt wird"
    hs = header_state({"published": False, "published_via": {"note_id": 1, "title": long, "url": "https://saga.test/@a/x/2"}})
    assert hs["label"] == "Public" and f"“{long}”" in hs["tooltip"] and hs["via"]["note_id"] == 1
    assert available({"published": False}) and not available({"title": "alt"}) and not available({"published": False, "template": True})
    p = {"published": False}
    apply_state(p, {"published": True, "url": "https://saga.test/@a/x", "published_via": None})
    assert p == {"published": True, "public_url": "https://saga.test/@a/x", "published_via": None}


def test_need_name_and_errors(srv):
    """409 need_name: APIError behaelt die JSON-Antwort (Vorschlag), Fehlertext vom Server; Zuordnung zum Feld"""
    from hlidskjalf.api import APIError, client
    from hlidskjalf.publish import error_field, error_text, need_name, saved_text, suggested_name
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
    assert error_field(ei.value, {}, "opt") == "opt" and error_field(ei.value, {"slug": "x"}, "pub") == "slug"
    assert error_field(APIError(500, "x"), {"slug": "x"}, "pub") == "pub"
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


def test_globe_icon_packaged():
    """Eigenes Symbol saga-globe-symbolic: gueltiges SVG nur aus Flaechen (GTK faerbt Flaechen ein, keine Linien),
    im RPM (Spec) und im Flatpak (Manifest) mit installiert"""
    from hlidskjalf.publish import GLOBE, ICON_DIR
    path = os.path.join(ICON_DIR, GLOBE + ".svg")
    root = ET.parse(path).getroot()
    assert root.tag.endswith("svg") and root.get("viewBox") == "0 0 16 16"
    assert not [e for e in root.iter() if e.get("stroke") or "stroke" in (e.get("style") or "")]
    spec = open(os.path.join(LINUX, "hlidskjalf.spec"), encoding="utf-8").read()
    fp = open(os.path.join(LINUX, "flatpak", "io.github.yggdrasil_sh.Saga.yml"), encoding="utf-8").read()
    assert "hlidskjalf/icons/*.svg" in spec and "icons/saga-globe-symbolic.svg" in spec
    assert "hlidskjalf/icons/*.svg" in fp


def test_globe_icon_in_theme(win):
    """Kopfleiste und Menue nehmen das eigene Symbol (Suchpfad wie in main.py), als symbolisches Symbol eingefaerbt"""
    from gi.repository import Gdk
    from hlidskjalf.publish import GLOBE, globe_icon, open_icon
    assert globe_icon() == GLOBE and win.pub_content.get_icon_name() == GLOBE
    th = Gtk.IconTheme.get_for_display(Gdk.Display.get_default())
    ic = th.lookup_icon(GLOBE, None, 16, 1, Gtk.TextDirection.NONE, 0)
    assert ic.get_file() is not None and ic.get_file().get_path().endswith("icons/saga-globe-symbolic.svg")
    assert ic.is_symbolic()
    assert open_icon() == "adw-external-link-symbolic"


# --- Dialog ---------------------------------------------------------------------------------------------------
def test_name_step_then_publish(win, srv, auto_confirm, toasts):
    """Ohne oeffentlichen Namen: Namensschritt (natuerlich hoch, Einleitung nicht abgedunkelt, Fehler am Feld statt
    Hilfetext), dann Einstellungen; Veroeffentlichen, Option, Adresse aendern (mit Rueckfrage), Kopfleiste, Menue."""
    from hlidskjalf.publish import MAIN_HEIGHT, ZWSP
    v = _open_page(win, 1)
    assert not win.pub_btn.get_visible()
    d = _dialog(win)
    assert _tag(d) == "name" and d.name_row.get_text() == "test-autor" and d.get_content_height() < 450
    intro = [w for w in _walk(d.nav) if isinstance(w, Gtk.Label) and w.get_label().startswith("First choose")]
    assert intro and not intro[0].has_css_class("dim-label")
    assert pump(lambda: d.go_btn.get_sensitive(), 3) and d.name_status.get_label() == "✓ Available"
    assert d.name_hint.get_visible() and not d.name_row.has_css_class("error")
    d.name_row.set_text("vergeben")
    assert not d.go_btn.get_sensitive()
    assert pump(lambda: d.name_status.get_label() == "Dieser Name ist schon vergeben.", 3)
    assert not d.go_btn.get_sensitive() and d.name_status.has_css_class("error")
    assert d.name_row.has_css_class("error") and not d.name_hint.get_visible()         # Fehler ersetzt den Hilfetext
    d.name_row.set_text("X")
    assert pump(lambda: d.name_status.get_label().startswith("2–32"), 3)
    d.name_row.set_text("Anna ")
    assert d.name_url.get_label().replace(ZWSP, "") == "https://saga.test/@anna"
    assert pump(lambda: d.go_btn.get_sensitive(), 3)
    assert not d.name_row.has_css_class("error") and d.name_hint.get_visible()
    d.go_btn.emit("clicked")
    assert pump(lambda: _tag(d) == "main", 5) and srv.public_name == "anna"
    assert d.get_content_height() == MAIN_HEIGHT
    # privat: Link-Karte mit kuenftiger Adresse gedaempft, ohne Kopieren/Oeffnen; Optionen mit Ueberschrift
    assert not d.sw.get_active() and d.g_link.get_visible() and not d.g_end.get_visible()
    assert d.link_row.raw == "https://saga.test/@anna/startseite" and d.link_row.url.has_css_class("dim-label")
    assert not d.b_copy.get_visible() and not d.b_open.get_visible() and not d.slug.get_show_apply_button()
    assert d.g_opts.get_title() == "Applies once the page is public"
    d.slug.set_text("start-neu")
    assert d.link_row.raw == "https://saga.test/@anna/start-neu" and not d.l_addr.get_visible()
    d.slug.set_text("startseite")
    # Option vor dem Veroeffentlichen: nur gemerkt, geht beim Einschalten mit
    n_put = len([x for x in srv.log if x[0] == "PUT"])
    d.sw_index.set_active(True)
    idle()
    assert len([x for x in srv.log if x[0] == "PUT"]) == n_put
    d.sw.set_active(True)
    assert pump(lambda: srv.pubs.get(1, {}).get("active"), 5)
    idle()
    assert srv.pubs[1]["indexable"] is True and srv.pubs[1]["slug"] == "startseite"
    assert d.g_link.get_visible() and d.link_row.raw == "https://saga.test/@anna/startseite"
    assert not d.link_row.url.has_css_class("dim-label") and d.b_copy.get_visible() and d.b_open.get_visible()
    assert d.g_opts.get_title() == "" and d.g_end.get_visible() and d.slug.get_show_apply_button()
    assert win.pub_btn.get_visible() and win.pub_content.get_label() == "Public"
    assert v.doc.page["published"] is True and toasts[-1] == "✓ Saved"
    # Option nach dem Veroeffentlichen: sofort gespeichert
    d.sw_children.set_active(True)
    idle()
    assert srv.pubs[1]["children"] is True
    # Adresse aendern: Uebernehmen-Knopf erst bei abweichender Adresse, Hinweis darunter, Rueckfrage
    assert not _apply_shown(d) and not d.l_addr.get_visible()
    d.slug.grab_focus()                                                          # libadwaita zeigt den Knopf beim Tippen
    pump(None, 0.1)
    d.slug.set_text("mein-projekt")
    assert _apply_shown(d) and d.l_addr.get_visible()
    assert d.l_addr.get_label() == "Takes effect only when you tap “Change address”."
    d.slug.set_text("startseite")
    assert not _apply_shown(d) and not d.l_addr.get_visible()                    # wieder wie gespeichert: Knopf weg
    d.slug.set_text("mein-projekt")
    auto_confirm["ok"] = False                                                   # Abbrechen: alte Adresse bleibt
    d.slug.emit("apply")
    idle()
    assert d.slug.get_text() == "startseite" and srv.pubs[1]["slug"] == "startseite" and not _apply_shown(d)
    auto_confirm["ok"] = True
    d.slug.set_text("mein-projekt")
    d.slug.emit("apply")
    idle()
    head, body, label, destructive = auto_confirm["full"][-1]
    assert head == "Change address?" and label == "Change address" and not destructive
    assert body.replace(ZWSP, "").replace("⁠", "") == "The current link https://saga.test/@anna/startseite will stop working."
    assert srv.pubs[1]["slug"] == "mein-projekt" and toasts[-1] == "✓ New address saved"
    assert d.link_row.raw.endswith("/@anna/mein-projekt") and not _apply_shown(d) and not d.l_addr.get_visible()
    # Kopieren: echte Adresse ohne Umbruchzeichen
    copied = []
    win.copy_text = lambda s: copied.append(s)
    d.b_copy.emit("clicked")
    assert copied == ["https://saga.test/@anna/mein-projekt"] and toasts[-1] == "Link copied"
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
    """Beenden nur nach Rueckfrage ("Veroeffentlichung beenden?" / "Beenden", destruktiv); Abbrechen laesst alles,
    wie es ist; danach nur der Hinweis "beendet am" (keine zweite Meldung)."""
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
    assert auto_confirm["full"] == [("Stop publishing?", "The link will stop working.", "Stop", True)]
    assert d.sw.get_active() and srv.pubs[1]["active"]
    auto_confirm["ok"] = True
    n = len(toasts)
    d.stop_row.emit("activated")
    assert pump(lambda: not srv.pubs[1]["active"], 5)
    idle()
    assert not d.sw.get_active() and not d.g_end.get_visible() and d.link_row.url.has_css_class("dim-label")
    assert d.l_withdrawn.get_visible() and d.l_withdrawn.get_label().startswith("Stopped publishing on ")
    assert len(toasts) == n and not win.pub_btn.get_visible()
    d.force_close()


def test_profile_address_row(win, srv, toasts):
    """Profilseite: zweite Zeile "Profiladresse" (…/@name) mit Kopieren"""
    srv.public_name = "anna"
    srv.pubs[1] = {"slug": "start", "children": False, "indexable": False, "profile": False, "active": True,
                   "withdrawn_at": None, "withdrawn_by": None}
    _open_page(win, 1)
    d = _dialog(win)
    assert not d.prof_row.get_visible()
    d.sw_profile.set_active(True)
    assert pump(lambda: srv.pubs[1]["profile"], 5)
    idle()
    assert d.prof_row.get_visible() and d.prof_row.raw == "https://saga.test/@anna"
    assert d.prof_row.head.get_label() == "Profile address"
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
    assert pump(lambda: d.get_content_height() < 450, 2)                     # wieder natuerlich hoch
    d.force_close()


def test_published_via_parent(win, srv):
    """Unterseite einer mit Unterseiten veroeffentlichten Seite: Kopfleiste nur "Oeffentlich" (Elternseite im Tooltip);
    im Dialog Link als eigene Zeile mit Kopier-Symbol, "„…“ oeffnen" als eigene Zeile; Adresse/Optionen ausgeblendet"""
    srv.public_name = "anna"
    srv.pubs[1] = {"slug": "start", "children": True, "indexable": False, "profile": False, "active": True,
                   "withdrawn_at": None, "withdrawn_by": None}
    v = _open_page(win, 2)
    assert v.doc.page["published"] is False and v.doc.page["published_via"]["note_id"] == 1
    assert win.pub_btn.get_visible() and win.pub_content.get_label() == "Public"
    assert "“Startseite”" in win.pub_btn.get_tooltip_text()
    d = _dialog(win, 2)
    assert d.g_via.get_visible() and d.l_via.get_visible() and "“Startseite”" in d.l_via.get_label()
    assert d.sw.get_title() == "Also publish at its own address" and not d.sw.get_active()
    assert d.via_link.raw == "https://saga.test/@anna/start/2"
    btns = [w for w in _walk(d.via_link) if isinstance(w, Gtk.Button)]
    assert [b.get_icon_name() for b in btns] == ["edit-copy-symbolic"] and btns[0].get_tooltip_text() == "Copy link"
    assert d.via_open.get_title() == "Open “Startseite”" and d.via_open.get_activatable()
    assert any(isinstance(w, Gtk.Image) and w.get_icon_name() == "go-next-symbolic" for w in _walk(d.via_open))
    assert not d.g_addr.get_visible() and not d.g_opts.get_visible() and not d.g_link.get_visible()
    d.via_open.emit("activated")
    assert pump(lambda: win.route == ("page", 1) and win.get_visible_dialog() is None, 3)


def test_errors_at_field(win, srv, auto_confirm, toasts):
    """Fehler direkt am Feld: abgelehnte Adresse markiert das Adressfeld rot, Text darunter (kein Toast); Tippen nimmt
    die Markierung weg. Abgeschaltet: Hinweis, Schalter gesperrt, Fehler bei den Optionen."""
    srv.public_name = "anna"
    srv.pubs[3] = {"slug": "belegt", "children": False, "indexable": False, "profile": False, "active": True,
                   "withdrawn_at": None, "withdrawn_by": None}
    srv.pubs[1] = {"slug": "start", "children": False, "indexable": False, "profile": False, "active": True,
                   "withdrawn_at": None, "withdrawn_by": None}
    _open_page(win, 1)
    d = _dialog(win)
    n = len(toasts)
    d.slug.set_text("belegt")
    d.slug.emit("apply")
    idle()
    pump(None, 0.2)
    err = d.errs["slug"]
    assert err.get_visible() and err.get_label() == "Diese Adresse benutzt schon eine andere deiner Seiten."
    assert err.has_css_class("error") and d.slug.has_css_class("error") and not d.l_addr.get_visible()
    assert len(toasts) == n and srv.pubs[1]["slug"] == "start" and d.st["slug"] == "start" and d.slug.get_text() == "belegt"
    assert not d.errs["pub"].get_visible() and not d.errs["opt"].get_visible()
    d.slug.set_text("belegt2")
    assert not err.get_visible() and not d.slug.has_css_class("error") and d.l_addr.get_visible()
    # abgeschaltet (Seite noch oeffentlich): Option -> Fehler bei den Optionen, Schalter zurueck
    srv.publish_enabled = False
    d.st["enabled"] = False
    d.sw_index.set_active(True)
    idle()
    assert d.errs["opt"].get_visible() and d.errs["opt"].get_label() == "Publishing is turned off on this server."
    assert not d.sw_index.get_active() and len(toasts) == n
    d.force_close()
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
    """Einstellungen -> Konto: eigener Block, Pruefung beim Tippen (Status direkt unter dem Feld, Feld rot bei Fehler),
    live "Neue Adresse: … (bisher …)", Rueckfrage "Oeffentlichen Namen aendern?" mit oeffentlichen Seiten"""
    from hlidskjalf.publish import WJ, ZWSP, PublicNameGroup
    g = PublicNameGroup(win)
    assert pump(lambda: g.get_visible(), 3)
    assert g.row.get_text() == "" and "Set when you first publish" in g.get_description()
    kids = [w for w in _walk(g) if w in (g.row, g.status, g.newaddr)]
    assert kids == [g.row, g.status, g.newaddr]                             # Reihenfolge: Feld, Status, neue Adresse
    g.row.set_text("vergeben")
    assert pump(lambda: g.status.get_label() == "Dieser Name ist schon vergeben.", 3) and not g.btn.get_sensitive()
    assert g.row.has_css_class("error")
    assert g.newaddr.get_label().replace(ZWSP, "") == "https://saga.test/@vergeben"   # ohne bisherigen Namen
    g.row.set_text("anna")
    assert not g.row.has_css_class("error")
    assert pump(lambda: g.btn.get_sensitive(), 3)
    g.btn.emit("clicked")
    assert pump(lambda: srv.public_name == "anna", 3)
    pump(lambda: g.status.get_label() == "✓ Saved", 3)
    assert g.status.get_label() == "✓ Saved" and "https://saga.test/@anna" in g.get_description().replace(ZWSP, "")
    assert not g.newaddr.get_visible()
    assert auto_confirm["asked"] == []                     # erster Name: keine Rueckfrage
    # schon oeffentliche Seiten: Rueckfrage, alte Links gehen nicht mehr
    srv.pubs[1] = {"slug": "start", "children": False, "indexable": False, "profile": False, "active": True,
                   "withdrawn_at": None, "withdrawn_by": None}
    g2 = PublicNameGroup(win)
    assert pump(lambda: g2.get_visible() and g2.row.get_text() == "anna", 3)
    assert not g2.newaddr.get_visible()
    g2.row.set_text("anna2")
    assert g2.newaddr.get_visible()
    assert g2.newaddr.get_label().replace(ZWSP, "").replace(WJ, "") == "New address: https://saga.test/@anna2 (was https://saga.test/@anna)"
    assert pump(lambda: g2.btn.get_sensitive(), 3)
    g2.row.emit("entry-activated")
    assert pump(lambda: srv.public_name == "anna2", 3)
    assert auto_confirm["full"] == [("Change public name?", "Links to your published pages will change. Old links will stop working.",
                                     "Change", False)]
    # aelterer Server: Block bleibt unsichtbar
    srv.publish_api = False
    g3 = PublicNameGroup(win)
    pump(None, 0.5)
    assert not g3.get_visible()


def test_dialog_keyboard_and_names(win, srv):
    """Bedienung per Tastatur/Screenreader: Schalter und Felder fokussierbar, Symbol-Knoepfe mit Tooltip und Namen,
    Uebernehmen-Knopf heisst "Adresse aendern", Statuszeile angesagt"""
    srv.public_name = "anna"
    srv.pubs[1] = {"slug": "start", "children": False, "indexable": False, "profile": False, "active": True,
                   "withdrawn_at": None, "withdrawn_by": None}
    _open_page(win, 1)
    d = _dialog(win)
    for w in (d.sw, d.slug, d.sw_children, d.sw_index, d.sw_profile, d.b_copy, d.b_open):
        assert w.get_focusable() or w.get_can_focus()
    assert d.b_copy.get_tooltip_text() == "Copy link" and d.b_open.get_tooltip_text() == "Open"
    ap = [w for w in _walk(d.slug) if isinstance(w, Gtk.Button) and w.get_icon_name() == "adw-entry-apply-symbolic"]
    assert ap and ap[0].get_tooltip_text() == "Change address"
    assert isinstance(d.stop_row, Adw.PreferencesRow)
    d.force_close()
    from hlidskjalf.publish import status_label
    lb = status_label()
    assert lb.get_accessible_role() in (Gtk.AccessibleRole.STATUS, Gtk.AccessibleRole.LABEL)
