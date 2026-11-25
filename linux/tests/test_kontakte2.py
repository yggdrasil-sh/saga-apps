"""2.6 (Bugs-DB 2027a-7): Adressbuch in der Linux-App (contacts2, docs/APP-API.md "Kontakte" im Server-Repo) gegen den
nachgebauten Server (tests/mockv25.py): Regeln (Landesvorwahl aus der Region, tel:/mailto:, Anfrage beim Speichern, 409),
Einstellungen > Kontakte nur mit contacts2, Liste mit Suche, Detail (Saga-Zeile mit "Nachricht", Nummern/Mails), Bearbeiten
mit ver (409 anderswo geaendert: Stand uebernommen, Eingabe bleibt; 409 doppelt: "Kontakt zeigen"), Neuer Kontakt, Loeschen
mit Rueckfrage, Landesvorwahl, Live contacts.changed, "Kontakt bearbeiten …" im Kontaktmenue (nur mit contacts2)."""
from gi.repository import Adw, Gtk

from hk_helpers import idle, pump
from test_chat import _ready, _walk


# --- Regeln (ohne Netz) ---------------------------------------------------------------------------------------------------
def test_rules_region_cc_href_body():
    from hlidskjalf import contactbook as B
    assert B.region_cc("de_DE.UTF-8") == "49" and B.region_cc("th_TH") == "66" and B.region_cc("en_US.UTF-8") == "1"
    assert B.region_cc("C.UTF-8") is None and B.region_cc("") is None and B.region_cc("sr_RS@latin") is None
    assert B.cc_norm("+49") == "49" and B.cc_norm(" 0049 ") == "49" and B.cc_norm(43) == "43" and B.cc_norm("") is None
    assert B.cc_norm("1234") is False and B.cc_norm("x") is False and B.cc_norm("+0") is False
    assert B.is_national("0170 123") and not B.is_national("0049 170") and not B.is_national("+49170")
    assert B.addr_href({"kind": "e164", "value": "+491701234567"}) == "tel:+491701234567"
    assert B.addr_href({"kind": "email", "value": "anna@example.org"}) == "mailto:anna@example.org"
    assert B.addr_href({"kind": "saga", "value": "~abc"}) is None and B.addr_href({"kind": "e164", "value": "0170"}) is None
    # Neu: ohne leere Felder, doppelte einmal, cc nur bei nationaler Nummer
    rows = [("e164", " 0170 1234567 ", "mobile"), ("e164", "0170 1234567", "work"), ("email", "", "work"),
            ("email", "a@b.example", "work"), ("saga", "~x", None)]
    b = B.edit_body("  Anna  ", None, " ", rows, cc="49", new=True)
    assert b == {"own_name": "Anna", "addrs": [{"kind": "e164", "value": "0170 1234567", "type": "mobile"},
                                               {"kind": "email", "value": "a@b.example", "type": "work"}], "cc": "49"}
    assert "cc" not in B.edit_body("A", None, "", [("e164", "+49 170 1234567", None)], cc="49", new=True)
    # Bearbeiten: ver, leere Felder als null (leeren), Anrede
    p = B.edit_body("", "formal", "Sehr geehrte Frau Berg", [], ver=4)
    assert p == {"own_name": None, "form": "formal", "salutation": "Sehr geehrte Frau Berg", "addrs": [], "ver": 4}
    assert len(B.edit_body("x" * 200, None, "y" * 100, [], ver=1)["own_name"]) == 120
    assert B.edit_problem({"own_name": None, "addrs": []}, True) == "Please enter a name."
    assert B.edit_problem({"own_name": "A", "addrs": []}, True) == "Please enter at least one phone number or email address."
    assert B.edit_problem({"own_name": None, "addrs": []}, False) is None
    assert B.edit_problem({"own_name": "A", "addrs": [{"kind": "email", "value": str(i)} for i in range(11)]}, True)
    from hlidskjalf.api import APIError
    e = APIError(409, "x")
    e.data = {"error": "x", "contact": {"id": 5, "ver": 3}}
    assert B.conflict_of(e) == ("changed", {"id": 5, "ver": 3})
    e.data = {"error": "x", "contact_id": 9}
    assert B.conflict_of(e) == ("duplicate", 9)
    e.data = {"error": "x"}
    assert B.conflict_of(e) is None and B.conflict_of(APIError(400, "x")) is None
    # Anzeige: Name, Unterzeile (Saga bzw. erste Nummer/Mail), Typen, Anrede
    c = {"id": 1, "name": "", "addrs": [{"kind": "email", "value": "m@x.example", "fixed": False}], "saga": None}
    assert B.display_name(c) == "m@x.example" and B.list_sub(c) == ""
    c2 = {"id": 2, "name": "Bert", "saga": {"ref": "~bt@b.example", "remote": True, "host": "b.example"}, "addrs": []}
    assert B.list_sub(c2) == "Person on b.example"
    assert B.list_sub(dict(c2, saga=dict(c2["saga"], gone=True))) == "Server b.example not connected"
    assert B.type_label("mobile") == "Mobile" and B.type_label(None) == "" and B.form_label("formal") == "Formal"
    assert B.form_label(None) == "—"
    from hlidskjalf.chat import contact_id_of, contact_item
    assert contact_id_of({"contact_id": 7}) == 7 and contact_id_of({"contact_id": True}) is None and contact_id_of({}) is None
    assert contact_item({"ref": "~a", "name": "A", "contact_id": 3})["contact_id"] == 3


# --- gegen den Schein-Server ----------------------------------------------------------------------------------------------
def _setup(srv, book=True, chats=True):
    from hlidskjalf.contactbook import book as BK
    from hlidskjalf.live import live
    from hlidskjalf.store import store
    srv.v25 = True
    srv.extra_features = ["contacts1"] + (["contacts2"] if book else [])
    srv.contacts_on = True
    srv.book_on = book
    BK.cc, BK.cc_known = None, False
    store.check_version()
    pump(lambda: store.has("contacts1") and store.has("contacts2") == book, 4)
    if chats:
        _ready(srv)
        srv.chat_peer("anna", "Anna Beispiel")
        pump(lambda: live.connected, 5)
    from mockv25 import book_add
    a = book_add(srv, "Anna Beispiel", saga={"ref": "~anna"}, addrs=[("e164", "+491701234567", "mobile")], last_at=300,
                 form="informal", salutation="Liebe Anna")
    m = book_add(srv, "Mia Manuell", addrs=[("email", "mia@example.org", "work")], last_at=None)
    srv.book[m]["updated"] = 200
    g = book_add(srv, "Bert Gast", saga={"ref": "~bt@b.example", "remote": True, "host": "b.example"}, last_at=100)
    return a, m, g


def _rows(w, cls=Adw.ActionRow):
    return [x for x in _walk(w) if isinstance(x, cls) and x.get_mapped()]


def _settings(win, sub=True):
    from hlidskjalf.settings import SettingsDialog
    sd = SettingsDialog(win)
    sd.present(win)
    pump(None, 0.2)
    if sub:
        sd.show_page("contacts")
        pump(lambda: sd.pages["contacts"].loaded, 4)
        pump(None, 0.3)
    return sd


def _nav_rows(sd):
    return [r for r in _walk(sd.pages["settings"]) if getattr(r, "hk_sub", None) == "contacts"]


def _top(sd):
    """oberste Unterseite (Detail bzw. Bearbeiten)"""
    nv = next(x for x in _walk(sd) if isinstance(x, Adw.NavigationView))
    return nv.get_visible_page()


def test_settings_hidden_without_contacts2(win, srv):
    _setup(srv, book=False, chats=False)
    sd = _settings(win, sub=False)
    assert not sd.pages["contacts"].get_visible() and not _nav_rows(sd)[0].get_visible()
    sd.show_page("contacts")
    pump(None, 0.3)
    assert sd.sub is None and not [x for x in srv.book_log if x[0] == "GET"]
    sd.force_close()


def test_list_search_detail_message(win, srv, monkeypatch):
    a, m, g = _setup(srv)
    from hlidskjalf import contactbook as B
    from hlidskjalf.chat import chat
    sd = _settings(win, sub=False)
    assert _nav_rows(sd)[0].get_visible()
    sd.show_page("contacts")
    page = sd.pages["contacts"]
    pump(lambda: page.loaded, 4)
    pump(None, 0.3)
    titles = [r.get_title() for r in page.rows]
    assert titles == ["Anna Beispiel", "Mia Manuell", "Bert Gast"]                 # zuletzt zuerst (last_at, sonst updated)
    assert [r.get_subtitle() for r in page.rows] == ["+491701234567", "mia@example.org", "Person on b.example"]
    assert ("GET", "api/contacts/settings", {}) in srv.book_log or any(x[1] == "api/contacts/settings" for x in srv.book_log)
    # Suche geht an den Server (q), Ziffern finden Nummern
    page.search.set_text("170 123")
    pump(lambda: [r.get_title() for r in page.rows] == ["Anna Beispiel"], 4)
    page.search.set_text("zzz")
    pump(lambda: [r.get_title() for r in page.rows] == ["No matches."], 4)
    page.search.set_text("")
    pump(lambda: len(page.rows) == 3, 4)
    # Detail: Saga-Zeile mit "Nachricht", Nummer mit tel:, Anrede
    page.rows[0].emit("activated")
    pump(lambda: getattr(_top(sd), "hk_detail", None) is not None and _top(sd).hk_detail.c is not None, 4)
    det = _top(sd).hk_detail
    pump(None, 0.4)
    hrefs = [getattr(r, "hk_href", None) for r in _walk(det.page) if isinstance(r, Adw.ActionRow)]
    assert "tel:+491701234567" in hrefs
    subs = [r.get_subtitle() for r in _walk(det.page) if isinstance(r, Adw.ActionRow)]
    assert "Informal" in subs and "Liebe Anna" in subs and "Mobile" in subs
    opened = []
    monkeypatch.setattr(B, "launch_uri", lambda uri, parent=None: opened.append(uri))
    next(r for r in _walk(det.page) if getattr(r, "hk_href", None) == "tel:+491701234567").emit("activated")
    assert opened == ["tel:+491701234567"]
    sent = []
    monkeypatch.setattr(chat, "new_chat", lambda body, done, err: sent.append(body))
    det.msg_btn.emit("clicked")
    assert sent == [{"kind": "dm", "user": "~anna"}]
    sd.force_close()
    # fremde Person: {handle}
    sd = _settings(win)
    det = sd.pages["contacts"].open(g)
    pump(lambda: det.c is not None, 4)
    det.message()
    assert sent[-1] == {"kind": "dm", "handle": "~bt@b.example"}
    sd.force_close()


def test_edit_patch_ver_409_duplicate(win, srv):
    a, m, g = _setup(srv, chats=False)
    sd = _settings(win)
    det = sd.pages["contacts"].open(a)
    pump(lambda: det.c is not None and det.edit_btn.get_sensitive(), 4)
    det.edit_btn.emit("clicked")
    pump(lambda: getattr(_top(sd), "hk_editor", None) is not None, 3)
    ed = _top(sd).hk_editor
    assert ed.name.get_text() == "" and ed.form.get_selected() == 1 and ed.sal.get_text() == "Liebe Anna"
    assert ed.g_saga.get_visible() and [r.get_text() for r in ed.addr_rows["e164"]] == ["+491701234567"]
    # Name fuer mich, Sie, zweite Nummer national (Landesvorwahl aus dem Konto)
    srv.book_cc = "49"
    from hlidskjalf.contactbook import book as BK
    BK.cc, ed.cc = "49", "49"
    ed.name.set_text("Anni")
    ed.form.set_selected(2)
    r = ed.add_addr("e164", "0151 2345678", "work")
    assert r is not None
    ed.save_btn.emit("clicked")
    pump(lambda: srv.book[a]["ver"] == 2, 4)
    body = [x for x in srv.book_log if x[0] == "PATCH"][-1][2]
    assert body["ver"] == 1 and body["own_name"] == "Anni" and body["form"] == "formal" and body["cc"] == "49"
    assert [x["value"] for x in body["addrs"]] == ["+491701234567", "0151 2345678"]
    assert "saga" not in [x["kind"] for x in body["addrs"]]                       # feste Adresse schickt die App nicht
    assert [x["value"] for x in srv.book[a]["addrs"]] == ["~anna", "+491701234567", "+491512345678"]
    pump(lambda: getattr(_top(sd), "hk_detail", None) is det, 3)
    pump(lambda: det.title_lb.get_label() == "Anni", 3)
    # anderswo geaendert: 409 mit contact -> Stand uebernommen (ver), Eingabe bleibt, Hinweis; zweites Speichern klappt
    det.edit_btn.emit("clicked")
    pump(lambda: getattr(_top(sd), "hk_editor", None) is not None, 3)
    ed = _top(sd).hk_editor
    srv.book[a]["ver"] = 7
    srv.book[a]["salutation"] = "Hallo"
    ed.sal.set_text("Liebe Anni")
    ed.save()
    pump(lambda: ed.g_msg.get_visible(), 4)
    assert "changed on another device" in ed.msg.get_label() and ed.c["ver"] == 7 and ed.sal.get_text() == "Liebe Anni"
    ed.save()
    pump(lambda: srv.book[a]["ver"] == 8, 4)
    assert srv.book[a]["salutation"] == "Liebe Anni"
    # doppelte Mail -> 409 contact_id: Hinweis ohne den Wert, "Kontakt zeigen" springt zu Mia
    det.edit_btn.emit("clicked")
    pump(lambda: getattr(_top(sd), "hk_editor", None) is not None and _top(sd).hk_editor is not ed, 3)
    ed = _top(sd).hk_editor
    ed.add_addr("email", "mia@EXAMPLE.org", "home")
    ed.save()
    pump(lambda: ed.g_msg.get_visible(), 4)
    assert ed.jump.get_visible() and ed.jump_id == m and "mia@" not in ed.msg.get_label()
    ed.jump.emit("clicked")
    pump(lambda: getattr(_top(sd), "hk_detail", None) is not None and _top(sd).hk_detail.cid == m, 3)
    sd.force_close()


def test_new_contact_and_delete(win, srv):
    a, m, g = _setup(srv, chats=False)
    sd = _settings(win)
    page = sd.pages["contacts"]
    page.new_btn.emit("clicked")
    pump(lambda: getattr(_top(sd), "hk_editor", None) is not None, 3)
    ed = _top(sd).hk_editor
    assert ed.c is None and len(ed.addr_rows["e164"]) == 1 and not ed.g_saga.get_visible()
    n = len(srv.book_log)
    ed.save()                                                             # ohne Name: Hinweis, keine Anfrage
    assert ed.g_msg.get_visible() and ed.msg.get_label() == "Please enter a name." and len(srv.book_log) == n
    ed.name.set_text("Paul Post")
    ed.save()
    assert ed.msg.get_label() == "Please enter at least one phone number or email address."
    ed.addr_rows["e164"][0].set_text("0170 9998887")                     # national ohne Landesvorwahl -> 400 vom Server
    ed.cc = None
    ed.save()
    pump(lambda: ed.msg.get_label() == "Ungültige Telefonnummer.", 4)
    ed.addr_rows["e164"][0].set_text("+43 664 1234567")
    ed.add_addr("email", "paul@Example.ORG", "work")
    ed.save()
    pump(lambda: len(srv.book) == 4, 4)
    post = [x for x in srv.book_log if x[0] == "POST"][-1][2]
    assert post == {"own_name": "Paul Post", "addrs": [{"kind": "e164", "value": "+43 664 1234567", "type": "mobile"},
                                                      {"kind": "email", "value": "paul@Example.ORG", "type": "work"}]}
    new = max(srv.book)
    pump(lambda: getattr(_top(sd), "hk_detail", None) is not None and _top(sd).hk_detail.cid == new, 3)
    det = _top(sd).hk_detail
    pump(lambda: det.c is not None, 3)
    assert "mailto:paul@example.org" in [getattr(r, "hk_href", None) for r in _walk(det.page)]
    # Loeschen mit Rueckfrage
    d = det.ask_delete()
    assert isinstance(d, Adw.AlertDialog) and "Paul Post" in d.get_body() and "chat suggestions" not in d.get_body()
    d.emit("response", "cancel")
    assert new in srv.book
    d = det.ask_delete()
    d.emit("response", "delete")
    pump(lambda: new not in srv.book, 4)
    pump(lambda: getattr(_top(sd), "hk_detail", None) is None, 3)
    pump(lambda: "Paul Post" not in [r.get_title() for r in page.rows] and page.rows, 4)
    # Kontakt mit Saga-Adresse: Hinweis auf die Chat-Vorschlaege
    det = page.open(a)
    pump(lambda: det.c is not None, 3)
    assert "chat suggestions" in det.ask_delete().get_body()
    det.alert.force_close()
    sd.force_close()


def test_country_code_and_live(win, srv, monkeypatch):
    a, m, g = _setup(srv, chats=False)
    from hlidskjalf import contactbook as B
    monkeypatch.setattr(B, "region_cc", lambda loc=None: "66")
    sd = _settings(win)
    page = sd.pages["contacts"]
    pump(lambda: page.cc_hint.get_visible(), 3)
    assert page.cc_row.get_text() == "" and "+66" in page.cc_hint.get_title()
    page.cc_use.emit("clicked")
    pump(lambda: srv.book_cc == "66" and page.cc_row.get_text() == "+66", 3)
    assert page.cc_row.get_text() == "+66" and not page.cc_hint.get_visible()
    page._cc_apply("abc")
    assert srv.book_cc == "66"
    page._cc_apply("")
    pump(lambda: srv.book_cc is None, 3)
    # Live: contacts.changed von einem anderen Geraet -> Liste neu
    gets = len([x for x in srv.book_log if x[0] == "GET" and x[1] == "api/contacts"])
    from mockv25 import book_add
    book_add(srv, "Neu Live", addrs=[("email", "neu@example.org", None)], last_at=999)
    win._live_event("contacts.changed", {"id": max(srv.book)})
    pump(lambda: page.rows and page.rows[0].get_title() == "Neu Live", 4)
    assert len([x for x in srv.book_log if x[0] == "GET" and x[1] == "api/contacts"]) > gets
    # offenes Detail: geloescht -> "nicht gefunden"
    det = page.open(m)
    pump(lambda: det.c is not None, 3)
    win._live_event("contacts.changed", {"id": m, "deleted": True})
    pump(lambda: not det.edit_btn.get_sensitive(), 3)
    sd.force_close()


def test_contact_menu_edit_only_with_contacts2(win, srv, monkeypatch):
    from hlidskjalf.chatview import NewChatDialog
    from hlidskjalf.contactbook import ContactDialog
    a, m, g = _setup(srv)
    srv.contacts = [{"ref": "~anna", "name": "Anna Beispiel", "sub": "", "avatar": None, "remote": False, "ready": True,
                     "gone": False, "last_at": 30}]
    d = NewChatDialog(win)
    d.present(win)
    pump(lambda: len([r for r in _rows(d.results) if hasattr(r, "hk_contact_menu")]) == 1, 4)
    r = next(r for r in _rows(d.results) if hasattr(r, "hk_contact_menu"))
    assert r.hk_contact["contact_id"] == a
    ents = r.hk_contact_menu()
    assert [e[0] for e in ents] == ["Edit contact …", "Remove from contacts"]
    # Suche: Kontakt-Treffer mit contact_id ebenso
    d.search.set_text("ann")
    pump(lambda: any(getattr(x, "hk_contact_menu", None) for x in _rows(d.results) if x.get_title() == "Anna Beispiel"), 4)
    hit = next(x for x in _rows(d.results) if x.get_title() == "Anna Beispiel")
    assert hit.hk_contact_menu()[0][0] == "Edit contact …"
    hit.hk_contact_menu()[0][1]()
    pump(lambda: isinstance(win.get_visible_dialog(), ContactDialog), 3)
    cd = win.get_visible_dialog()
    pump(lambda: getattr(cd.navv.get_visible_page(), "hk_editor", None) is not None, 4)
    ed = cd.navv.get_visible_page().hk_editor
    assert ed.c["id"] == a and ed.sal.get_text() == "Liebe Anna"
    ed.name.set_text("Anni")
    toasts = []
    monkeypatch.setattr(win, "toast", lambda t: toasts.append(t))
    ed.save()
    pump(lambda: srv.book[a]["own_name"] == "Anni", 4)
    pump(lambda: toasts == ["Contact saved."], 3)
    idle()
    d.force_close()


def test_contact_menu_without_contacts2(win, srv):
    from hlidskjalf.chatview import NewChatDialog
    _setup(srv, book=False)
    srv.contacts = [{"ref": "~anna", "name": "Anna Beispiel", "sub": "", "avatar": None, "remote": False, "ready": True,
                     "gone": False, "last_at": 30, "contact_id": 5}]
    d = NewChatDialog(win)
    d.present(win)
    pump(lambda: len([r for r in _rows(d.results) if hasattr(r, "hk_contact_menu")]) == 1, 4)
    r = next(r for r in _rows(d.results) if hasattr(r, "hk_contact_menu"))
    assert [e[0] for e in r.hk_contact_menu()] == ["Remove from contacts"]
    d.force_close()
    assert Gtk is not None


def test_detail_live_released_after_close(win, srv):
    """(Pruefung k27lrev) geschlossenes Detail haengt nicht mehr an book "changed" (sonst laedt jedes je geoeffnete Detail bei
    jedem hello/reset bzw. contacts.changed neu)"""
    import gc
    a, m, g = _setup(srv, chats=False)
    from hlidskjalf.contactbook import book as BK
    sd = _settings(win)
    page = sd.pages["contacts"]
    for _i in range(3):
        det = page.open(a)
        pump(lambda: det.c is not None, 4)
        pump(None, 0.3)
        sd.pop_subpage()
        pump(None, 0.6)
    del det
    gc.collect()
    pump(None, 0.3)

    def gets():
        return len([x for x in srv.book_log if x[0] == "GET" and x[1].startswith(f"api/contacts/{a}")])
    n0 = gets()
    BK.emit("changed", a, False)
    pump(None, 1.0)
    assert gets() == n0
    # offenes Detail laedt weiter neu
    det = page.open(a)
    pump(lambda: det.c is not None, 4)
    pump(None, 0.3)
    n1 = gets()
    BK.emit("changed", a, False)
    pump(lambda: gets() > n1, 4)
    assert gets() == n1 + 1
    sd.force_close()


def test_editor_cc_only_when_account_cc_known(win, srv, monkeypatch):
    """(Pruefung k27lrev) Konto-Landesvorwahl noch unbekannt (Einstellungen nicht geladen): kein cc aus der Region schicken -
    sonst wuerde eine vorhandene Konto-Einstellung uebergangen"""
    _setup(srv, chats=False)
    from hlidskjalf import contactbook as B
    monkeypatch.setattr(B, "region_cc", lambda loc=None: "49")
    B.book.cc, B.book.cc_known = None, False
    ed = B.ContactEditor(B.Host(None, None, lambda t: None), None)
    ed.name.set_text("Xaver")
    ed.addr_rows["e164"][0].set_text("0170 1234567")
    assert "cc" not in ed.body()
    B.book.cc_known = True
    assert ed.body()["cc"] == "49"
