"""2.5: Gruppeninfo (chatadm1) und Kontakte (contacts1) - docs/APP-API-CHAT.md 6 und 7 im Server-Repo - gegen den
nachgebauten Server: Rechte aus can_*, Systemzeile 'admin', Mitglieder sortiert mit "Du"/"Admin" und Handle (nie
Anmeldename), Admin vergeben/entziehen (PATCH .../members/{who}), Fehler 409 mit Server-Meldung, Entfernen mit Rueckfrage,
group/page nur lesend, Kopf anklicken, Menue "Gruppeninfo …"; Kontaktliste ohne Eingabe (zuletzt zuerst, gone
ausgegraut), Auswahl lokal {user: ~x} / fremd {handle}, "Aus Kontakten entfernen", Live chat.contacts, aeltere Server."""
from gi.repository import Adw, Gtk

from hk_helpers import idle, pump
from test_chat import _ready, _walk


def _setup(srv, feats=("chatadm1", "contacts1")):
    from hlidskjalf.chat import chat
    from hlidskjalf.live import live
    from hlidskjalf.store import store
    srv.v25 = True
    srv.extra_features = list(feats)
    srv.chatadm = "chatadm1" in feats
    srv.contacts_on = "contacts1" in feats
    store.check_version()
    pump(lambda: all(store.has(f) for f in feats), 4)
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    srv.chat_peer("bert", "Bert Beispiel")
    pump(lambda: live.connected, 5)
    return chat


def _load(chat, *cids):
    chat.load_chats()
    pump(lambda: all(chat.chat(c) for c in cids), 4)
    idle()


def _titles(w):
    return [x.get_title() for x in _walk(w) if isinstance(x, Adw.PreferencesRow)]


def _rows(w, cls=Adw.ActionRow):
    return [x for x in _walk(w) if isinstance(x, cls)]


def _labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label)]


class _NoPop:
    def popup(self):
        pass


def _toasts(dlg, monkeypatch):
    got = []
    monkeypatch.setattr(dlg, "_toast", lambda t: t and got.append(str(t)))
    return got


# --- reine Logik ------------------------------------------------------------------------------------------------
def test_rights_from_can_fields():
    from hlidskjalf.chat import gi_actions, gi_rights
    free = {"kind": "free", "role": "admin", "can_manage": True, "can_admin": True, "can_rename": True,
            "members": [{"username": "test", "me": True, "admin": True}, {"username": "anna", "admin": False},
                        {"username": "bert", "admin": True}]}
    assert gi_rights(free) == {"manage": True, "admin": True, "rename": True}
    me, anna, bert = free["members"]
    assert gi_actions(free, anna) == ["admin_on", "remove"]
    assert gi_actions(free, bert) == ["admin_off", "remove"]
    assert gi_actions(free, me) == ["admin_off"]             # sich selbst entziehen (solange ein anderer Admin bleibt)
    # Heim zu alt: Verwalten ja, Admin nein; schreibgeschuetzt: nichts
    old_home = dict(free, can_admin=False)
    assert gi_rights(old_home) == {"manage": True, "admin": False, "rename": True} and gi_actions(old_home, anna) == ["remove"]
    ro = dict(free, can_manage=False, can_admin=False, can_rename=False)
    assert gi_rights(ro) == {"manage": False, "admin": False, "rename": False} and gi_actions(ro, anna) == []
    member = dict(free, role="member", can_manage=False, can_admin=False)
    assert gi_actions(member, anna) == [] and gi_rights(member)["rename"]
    # aelterer Server ohne can_*: Admin = role, Admin-Vergabe nie
    legacy = {"kind": "free", "role": "admin", "members": free["members"]}
    assert gi_rights(legacy) == {"manage": True, "admin": False, "rename": True}
    for k in ("group", "page", "dm"):
        assert gi_rights({"kind": k, "can_manage": True, "can_admin": True}) == {"manage": False, "admin": False, "rename": False}


def test_admin_sys_text():
    from hlidskjalf.chat import sys_text
    me = "~me@a.example"
    anna = {"name": "Anna", "handle": "~an@a.example"}
    bert = {"name": "Bert", "handle": "~bt@b.example"}
    mine = {"name": "Ich", "handle": me}
    t = lambda **k: sys_text(dict(type="admin", **k), me)   # noqa: E731
    assert t(target=anna, on=True, by=bert) == "Bert (b.example) made Anna an admin."
    assert t(target=anna, on=False, by=bert) == "Bert (b.example) removed Anna as admin."
    assert t(target=anna, on=True, by=mine) == "You made Anna an admin."
    assert t(target=anna, on=False, by=mine) == "You removed Anna as admin."
    assert t(target=anna, on=True, by=anna) == "Anna is now an admin."
    assert t(target=anna, on=False, by=anna) == "Anna is no longer an admin."
    assert t(target=mine, on=True, by=mine) == "You are now an admin."
    assert t(target=mine, on=False, by=mine) == "You are no longer an admin."
    assert t(target=anna, on=True, by=None) == "Anna is now an admin."              # ohne Urheber
    assert t(target=bert, on=False, by=None) == "Bert (b.example) is no longer an admin."
    assert t(target=mine, on=True, by=bert).startswith("Bert (b.example) made ") and "Ich" not in t(target=mine, on=True, by=bert)


def test_member_sub_and_order_never_login():
    from hlidskjalf.chat import gi_members, gi_sub
    c = {"kind": "free", "v": 3, "my_handle": "~me@a.example",
         "members": [{"username": "zora-login", "name": "Zora", "ref": "~zr", "handle": "~zr@a.example"},
                     {"username": "anna-login", "name": "Anna", "ref": "~an", "admin": True},
                     {"username": "ich-login", "name": "Ich", "me": True, "ref": "ich-login"},
                     {"username": "~bt@b.example", "name": "Bert", "handle": "~bt@b.example", "host": "b.example"}]}
    order = [m["name"] for m in gi_members(c)]
    assert order == ["Ich", "Anna", "Bert", "Zora"]
    subs = {m["name"]: gi_sub(c, m) for m in c["members"]}
    assert subs["Zora"] == "~zr" and subs["Anna"] == "~an" and subs["Bert"] == "~bt@b.example"
    assert not any("login" in s for s in subs.values())
    fed = dict(c, v=2)
    assert gi_sub(fed, c["members"][3]) == "on b.example · ~bt"


def test_contact_items_and_order():
    from hlidskjalf.chat import contact_item, contact_keep_for, contacts_sorted
    ks = contacts_sorted([{"ref": "~a", "name": "Alt", "last_at": 10}, {"ref": "~n", "name": "Neu", "last_at": 30},
                          {"name": "ohne ref"}, "kaputt", {"ref": "~bt@b.example", "name": "Bert", "remote": True, "host": "b.example",
                                                           "last_at": 20}])
    assert [k["name"] for k in ks] == ["Neu", "Bert", "Alt"]
    assert contact_item(ks[1])["sub"] == "Person on b.example"
    assert contact_item({"ref": "~x", "name": "X", "gone": True})["sub"] == "Account no longer active"
    assert contact_item({"ref": "~x@c", "name": "X", "remote": True, "host": "c", "gone": True})["sub"] == "Server c not connected"
    assert contact_item({"ref": "~x", "name": "X", "sub": "Gleichnamig", "ready": False})["sub"] == "Gleichnamig · hasn't set up chats yet"
    keep = contact_keep_for({"v": 1, "members": [{"username": "anna", "ref": "~a"}]})
    assert [k["name"] for k in ks if keep(k)] == ["Neu"]          # schon dabei bzw. fremd in Chat v1: nicht anbieten
    keep3 = contact_keep_for({"v": 3, "members": []})
    assert len([k for k in ks if keep3(k)]) == 3


# --- Oberflaeche --------------------------------------------------------------------------------------------------
def test_group_info_menu_header_and_admin(win, srv, monkeypatch):
    chat = _setup(srv)
    from hlidskjalf.chatview import ChatsScreen, GroupInfoDialog
    g = srv.new_chat("free", ["test", "anna", "bert"], admin="test")
    _load(chat, g)
    win.show_route(("chat", g))
    pump(lambda: isinstance(win.current, ChatsScreen) and win.current.conv is not None, 4)
    idle()
    # Kopf anklicken -> Gruppeninfo (Tooltip "Chat info")
    assert win.wtitle.get_tooltip_text() == "Chat info"
    win.current.title_clicked()
    pump(lambda: isinstance(win.get_visible_dialog(), GroupInfoDialog), 3)
    d = win.get_visible_dialog()
    assert isinstance(d, GroupInfoDialog) and d.get_title() == "Group info"
    toasts = _toasts(d, monkeypatch)
    names = [r.get_title() for r in _rows(d.members_group)]
    assert names == ["Test", "Anna Beispiel", "Bert Beispiel"] or names[0] in ("test", "Test")
    labels = _labels(d)
    assert "You" in labels and "Admin" in labels and "Add member" in labels
    assert "Leave group" in _titles(d) and "Mute" in _titles(d)
    rows = {r.get_title(): r for r in _rows(d.members_group)}
    assert rows["Anna Beispiel"].hk_actions == ["admin_on", "remove"]
    assert "anna" not in [r.get_subtitle() for r in rows.values()]           # nie der Anmeldename
    assert rows["Anna Beispiel"].get_subtitle() == "~anna"
    # Admin vergeben -> PATCH {admin:true}, neue Liste aus der Antwort
    d._admin(next(m for m in chat.chat(g)["members"] if m["username"] == "anna"), True)
    pump(lambda: srv.admin_log and any(m.get("admin") for m in chat.chat(g)["members"] if m["username"] == "anna"), 4)
    assert srv.admin_log[-1] == (g, "anna", {"admin": True})
    pump(lambda: "admin_off" in {r.get_title(): r for r in _rows(d.members_group)}["Anna Beispiel"].hk_actions, 3)
    assert toasts and toasts[-1] == "Anna Beispiel is now an admin."
    # letzter Admin: 409 mit Server-Meldung
    srv.admin_fail = (409, "Das ist der letzte Admin der Gruppe – erst jemand anderen zum Admin machen.")
    d._admin(next(m for m in chat.chat(g)["members"] if m["username"] == "test"), False)
    pump(lambda: len(toasts) >= 2, 3)
    assert toasts[-1].startswith("Das ist der letzte Admin")
    srv.admin_fail = None
    # Entfernen mit Rueckfrage
    d._ask_remove(next(m for m in chat.chat(g)["members"] if m["username"] == "bert"))
    pump(lambda: isinstance(win.get_visible_dialog(), Adw.AlertDialog), 2)
    ad = win.get_visible_dialog()
    assert ad.get_heading() == "Remove Bert Beispiel from the group?"
    ad.emit("response", "ok")
    ad.force_close()
    pump(lambda: "bert" not in srv.chats[g]["members"] and len(chat.chat(g)["members"]) == 2, 4)
    pump(lambda: len(_rows(d.members_group)) == 2, 3)
    assert toasts[-1] == "Bert Beispiel was removed."
    # Live: chat.changed (z. B. Admin auf einem anderen Geraet entzogen) zeichnet neu
    srv.chats[g]["admins"].discard("anna")
    srv.push_event("chat.changed", {"chat_id": g})
    pump(lambda: "admin_on" in getattr({r.get_title(): r for r in _rows(d.members_group)}.get("Anna Beispiel"), "hk_actions", []), 4)
    assert "admin_on" in {r.get_title(): r for r in _rows(d.members_group)}["Anna Beispiel"].hk_actions
    d.force_close()
    # Menue: "Gruppeninfo …" statt "Mitglieder …"
    pop_entries = []
    monkeypatch.setattr("hlidskjalf.chatview.menu_popover", lambda parent, ents: pop_entries.append(ents) or _NoPop())
    win.current.conv.menu(win.menu_btn)
    texts = [e[0] for e in pop_entries[-1] if e]
    assert "Group info …" in texts and "Members …" not in texts and "Rename …" in texts


def test_group_info_member_without_rights_and_readonly_kinds(win, srv):
    chat = _setup(srv)
    from hlidskjalf.chatview import GroupInfoDialog
    g = srv.new_chat("free", ["anna", "test", "bert"], admin="anna")
    srv.groups["team"] = ["test", "anna"]
    gg = srv.new_chat("group", ["test", "anna"], group="team")
    _load(chat, g, gg)
    d = GroupInfoDialog(win, g)
    d.present(win)
    pump(None, 0.3)
    rows = {r.get_title(): r for r in _rows(d.members_group)}
    assert all(getattr(r, "hk_actions", []) == [] for r in rows.values())
    assert "Add member" not in _labels(d) and d.add_group is None
    assert d.name_row is not None                        # jedes Mitglied darf umbenennen (can_rename)
    d.force_close()
    d2 = GroupInfoDialog(win, gg)
    d2.present(win)
    pump(None, 0.3)
    assert "Here, members come from the group or from the page's shares." in _labels(d2)
    assert d2.name_row is None and "Leave group" not in _titles(d2)
    d2.force_close()


def test_admin_sys_row_in_history(win, srv):
    chat = _setup(srv)
    from hlidskjalf.chatview import Conversation
    g = srv.new_chat("free", ["test", "anna"], admin="test")
    _load(chat, g)
    conv = Conversation.__new__(Conversation)
    conv.cid = g
    box = Conversation._sys_row(conv, {"sys": {"type": "admin", "target": {"name": "Anna", "handle": "~an@x"}, "on": True,
                                               "by": {"name": "Bert", "handle": "~bt@x"}}, "created": 0})
    assert "Bert (x) made Anna (x) an admin." in _labels(box)          # Herkunft, da mein Handle unbekannt


def test_contacts_new_chat_pick_remove_live(win, srv, monkeypatch):
    chat = _setup(srv)
    from hlidskjalf.chatview import NewChatDialog
    srv.contacts = [{"ref": "~anna", "name": "Anna Beispiel", "sub": "", "avatar": None, "remote": False, "ready": True, "gone": False, "last_at": 30},
                    {"ref": "~bt@b.example", "name": "Bert Gast", "sub": "b.example", "host": "b.example", "avatar": None, "remote": True,
                     "ready": True, "gone": False, "last_at": 20},
                    {"ref": "~weg", "name": "Weg Person", "sub": "", "remote": False, "ready": True, "gone": True, "last_at": 10}]
    d = NewChatDialog(win)
    d.present(win)
    pump(lambda: len(_rows(d.results)) == 3, 4)
    assert d.ct_head.get_visible() and d.ct_head.get_label() == "Contacts"
    assert srv.contacts_log[0] == ("GET", {"limit": "30"})
    rows = _rows(d.results)
    assert [r.get_title() for r in rows] == ["Anna Beispiel", "Bert Gast", "Weg Person"]
    assert rows[1].get_subtitle() == "Person on b.example"
    assert not rows[2].get_activatable() and rows[2].has_css_class("hk-gone") and rows[2].get_subtitle() == "Account no longer active"
    # Eingabe: Suche (Kontakte vorne, contact:true) - Ueberschrift weg
    d.search.set_text("ann")
    pump(lambda: not d.ct_head.get_visible() and any(r.get_title() == "Anna Beispiel" for r in _rows(d.results)), 4)
    hit = next(r for r in _rows(d.results) if r.get_title() == "Anna Beispiel")
    assert hasattr(hit, "hk_contact_drop")               # Menue "Aus Kontakten entfernen" auch bei Treffern
    d.search.set_text("")
    pump(lambda: len(_rows(d.results)) == 3, 4)
    # Aus Kontakten entfernen
    toasts = []
    d.contacts.toaster = lambda t: toasts.append(t)
    rows = _rows(d.results)
    # Zeilen nehmen den toaster beim Bau mit - neu laden, damit der Test die Meldung sieht
    d.contacts.show(d._pick_contact, still=lambda: not d.q)
    pump(lambda: len(_rows(d.results)) == 3 and _rows(d.results)[0] is not rows[0], 4)
    _rows(d.results)[0].hk_contact_drop()
    pump(lambda: len(_rows(d.results)) == 2, 4)
    assert ("DELETE", "~anna") in srv.contacts_log and toasts == ["Anna Beispiel removed from contacts."]
    # Live von einem anderen Geraet: chat.contacts {ref, hidden} -> Liste neu
    srv.contacts[1]["hidden"] = True
    srv.push_event("chat.contacts", {"ref": "~bt@b.example", "hidden": True})
    pump(lambda: [r.get_title() for r in _rows(d.results)] == ["Weg Person"], 5)
    # Auswahl eines lokalen Kontakts -> Direkt-Chat mit {user: ~anna}
    srv.contacts[0]["hidden"] = False
    d.contacts.show(d._pick_contact, still=lambda: not d.q)
    pump(lambda: any(r.get_title() == "Anna Beispiel" for r in _rows(d.results)), 4)
    sent = []
    monkeypatch.setattr(chat, "new_chat", lambda body, done, err: sent.append(body))
    next(r for r in _rows(d.results) if r.get_title() == "Anna Beispiel").emit("activated")
    assert sent[-1] == {"kind": "dm", "user": "~anna"}
    d._pick_contact({"ref": "~bt@b.example", "name": "Bert Gast", "remote": True, "host": "b.example"})
    assert sent[-1] == {"kind": "dm", "handle": "~bt@b.example"}
    d.force_close()


def test_add_member_contacts_filter_and_pick(win, srv):
    chat = _setup(srv)
    from hlidskjalf.chatview import GroupInfoDialog
    srv.chat_peer("cora", "Cora Beispiel")
    srv.contacts = [{"ref": "~anna", "name": "Anna Beispiel", "remote": False, "ready": True, "gone": False, "last_at": 30},
                    {"ref": "~cora", "name": "Cora Beispiel", "remote": False, "ready": True, "gone": False, "last_at": 25},
                    {"ref": "~bt@b.example", "name": "Bert Gast", "host": "b.example", "remote": True, "ready": True, "gone": False, "last_at": 20}]
    g = srv.new_chat("free", ["test", "anna"], admin="test")
    _load(chat, g)
    d = GroupInfoDialog(win, g)
    d.present(win)
    pump(None, 0.3)
    assert not d.add_box.get_visible()
    d._toggle_add()
    # Anna ist schon dabei, Bert (anderer Server) nur in Gruppen im Format v2/v3
    pump(lambda: [r.get_title() for r in _rows(d.add_res)] == ["Cora Beispiel"], 4)
    assert d.ct_head.get_visible()
    _rows(d.add_res)[0].emit("activated")
    pump(lambda: "cora" in srv.chats[g]["members"], 4)
    assert srv.member_adds[-1] == {"user": "~cora"}
    pump(lambda: len(_rows(d.members_group)) == 3, 4)
    assert d.add_group.get_parent() is not None           # Suchbereich bleibt nach dem Neuzeichnen
    d.force_close()


def test_older_server_unchanged(win, srv, monkeypatch):
    chat = _setup(srv, feats=())
    from hlidskjalf.chatview import ChatInfoDialog, ChatsScreen, GroupInfoDialog, NewChatDialog
    g = srv.new_chat("free", ["test", "anna"], admin="test")
    _load(chat, g)
    win.show_route(("chat", g))
    pump(lambda: isinstance(win.current, ChatsScreen) and win.current.conv is not None, 4)
    idle()
    assert win.wtitle.get_tooltip_text() is None
    win.current.title_clicked()
    pump(None, 0.3)
    assert win.get_visible_dialog() is None
    pop_entries = []
    monkeypatch.setattr("hlidskjalf.chatview.menu_popover", lambda parent, ents: pop_entries.append(ents) or _NoPop())
    win.current.conv.menu(win.menu_btn)
    texts = [e[0] for e in pop_entries[-1] if e]
    assert "Members …" in texts and "Group info …" not in texts
    win.current.conv.open_info()
    pump(lambda: win.get_visible_dialog() is not None, 2)
    assert type(win.get_visible_dialog()) is ChatInfoDialog and not isinstance(win.get_visible_dialog(), GroupInfoDialog)
    win.get_visible_dialog().force_close()
    d = NewChatDialog(win)
    d.present(win)
    pump(None, 0.5)
    assert not d.ct_head.get_visible() and _rows(d.results) == [] and srv.contacts_log == []
    d.force_close()
