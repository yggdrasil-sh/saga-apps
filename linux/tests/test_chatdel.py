"""2.1: Chats archivieren und loeschen (docs/APP-API-CHAT.md im Server-Repo) gegen den nachgebauten Server:
Archivieren/Aus Archiv holen (PATCH archived), Bereich "Archiviert (n)", Ungelesen ohne Archiv, neue Nachricht holt aus
dem Archiv (stumm + archiviert bleibt), Kontextmenue und Chat-Menue, Dialog "Chat loeschen …" (Nur fuer mich / Fuer alle
je nach delete_for_all, ausgegraut mit Begruendung, Gruppe ausserdem verlassen), lokaler Zwischenspeicher wirklich leer
(Nachrichten, Anhaenge, Vorschaubilder, Entwurf, Benachrichtigungen), Live chat.removed / chat.cleared / chat.changed,
aeltere Server ohne die Funktionen."""
import os

from gi.repository import Gtk

import mockv2
from hk_helpers import idle, pump
from hlidskjalf import chatmedia
from test_chat import _labels, _ready, _walk
from test_v21c import _img

from hlidskjalf.chatview import _walk_rows  # noqa: E402


def _setup(win, srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.live import live
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    srv.chat_peer("bert", "Bert Beispiel")
    pump(lambda: live.connected, 5)
    return chat


def _load(chat, *cids):
    chat.load_chats()
    pump(lambda: all(chat.chat(c) for c in cids), 4)
    idle()


def _toasts(win, monkeypatch):
    got = []
    monkeypatch.setattr(win, "toast", lambda msg: msg and got.append(str(msg)), raising=False)
    return got


def _menu_labels(pop):
    return [x.get_label() for x in _walk(pop) if isinstance(x, Gtk.Label)]


def test_archive_section_unread_and_revive(win, srv, monkeypatch):
    chat = _setup(win, srv)
    from hlidskjalf.chatview import ChatList, ChatsScreen, archive_chat
    toasts = _toasts(win, monkeypatch)
    a = srv.new_chat("dm", ["test", "anna"])
    b = srv.new_chat("dm", ["test", "bert"])
    srv.peer_send(a, "anna", "Hallo von Anna")
    srv.peer_send(b, "bert", "Hallo von Bert")
    srv.peer_send(b, "bert", "Noch was")
    _load(chat, a, b)
    assert chat.unread == 3 and chat.can_archive(chat.chat(a)) and chat.can_delete(chat.chat(a))
    ChatList.arch_open = False
    win.show_root(("chats",))
    pump(None, 0.3)
    assert isinstance(win.current, ChatsScreen)
    archive_chat(win, b, True)
    pump(lambda: chat.chat(b) and chat.chat(b).get("archived") and chat.unread == 1, 5)
    assert chat.unread == 1                                        # Ungelesen ohne Archiv (Seitenleiste)
    assert "Chat archived – it comes back with the next message." in toasts
    lst = win.current.list
    pump(lambda: "Archived (1)" in _labels(lst), 3)
    labels = _labels(lst)
    assert "Archived (1)" in labels and "Bert Beispiel" not in labels and "Anna Beispiel" in labels
    assert "2" in labels                                           # Ungelesen am Bereich "Archiviert"
    head = [r for r in _walk_rows(lst.box) if getattr(r, "arch_toggle", False)][0]
    lst._activated(head)                                           # aufklappen
    assert "Bert Beispiel" in _labels(lst) and ChatList.arch_open
    # anderes eigenes Geraet holt ihn zurueck: Live chat.changed {archived}
    mockv2._pref(srv.chats[b], "test")["archived"] = None
    srv.push_event("chat.changed", {"chat_id": b, "archived": False})
    pump(lambda: not chat.chat(b).get("archived"), 5)
    pump(lambda: chat.unread == 3, 4)
    assert chat.unread == 3
    # neue Nachricht holt aus dem Archiv - stumm + archiviert bleibt archiviert
    archive_chat(win, b, True)
    pump(lambda: chat.chat(b).get("archived"), 4)
    m = srv.peer_send(b, "bert", "Wieder da?")
    srv.push_event("chat.message", {"chat_id": b, "seq": m["seq"], "id": m["id"], "sender": "bert"})
    pump(lambda: not chat.chat(b).get("archived"), 5)
    assert not chat.chat(b).get("archived")
    srv.chats[a]["muted"].add("test")
    archive_chat(win, a, True)
    pump(lambda: chat.chat(a).get("archived"), 4)
    m = srv.peer_send(a, "anna", "Stumm")
    srv.push_event("chat.message", {"chat_id": a, "seq": m["seq"], "id": m["id"], "sender": "anna"})
    pump(None, 1.0)
    assert chat.chat(a).get("archived")
    archive_chat(win, a, False)
    pump(lambda: not chat.chat(a).get("archived"), 4)
    assert "Chat unarchived." in toasts
    ChatList.arch_open = False


def test_menus_offer_archive_and_delete(win, srv, monkeypatch):
    chat = _setup(win, srv)
    a = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(a, "anna", "Hallo")
    _load(chat, a)
    win.show_route(("chat", a))
    pump(lambda: win.current.conv and win.current.conv.loaded, 5)
    scr = win.current
    rows = [r for r in _walk_rows(scr.list.box) if getattr(r, "cid", None) == a]
    pop = scr.list._menu(rows[0], chat.chat(a), 10, 10)
    labels = _menu_labels(pop)
    assert labels[:2] == ["Open", "Mute"] and "Archive" in labels and "Delete chat …" in labels
    pop.popdown()
    from hlidskjalf import chatview
    got = []
    orig = chatview.menu_popover
    monkeypatch.setattr(chatview, "menu_popover", lambda parent, ents: (got.append(ents), orig(parent, ents))[1])
    scr.conv.menu(scr.conv)
    idle()
    names = [e[0] for e in got[0] if e]
    assert names[-2:] == ["Archive", "Delete chat …"] and got[0][-1][2] is True     # Loeschen rot (destruktiv)
    # Kopfzeile zeigt "Archiviert"
    chat.chat(a)["archived"] = True
    assert scr.header()[1].endswith("· Archived")


def test_delete_dialog_choices(win, srv):
    chat = _setup(win, srv)
    from hlidskjalf.chatview import DeleteChatDialog
    dm = srv.new_chat("dm", ["test", "anna"])
    grp = srv.new_chat("group", ["test", "bert"], group="team")
    free_admin = srv.new_chat("free", ["test", "anna", "bert"], admin="test")
    free = srv.new_chat("free", ["anna", "test", "bert"], admin="anna")
    page = srv.new_chat("page", ["test", "anna"], note_id=1)
    _load(chat, dm, grp, free_admin, free, page)
    d = DeleteChatDialog(win, dm)
    subs = [d.row_me.get_subtitle(), d.row_all.get_subtitle()]
    assert d.row_all.get_sensitive() and d.leave is None and d.get_heading() == "Delete “Anna Beispiel”?"
    assert "Anna Beispiel keeps it" in subs[0] and "deleted for Anna Beispiel and you" in subs[1]
    assert d.get_response_label("delete") == "Delete for me"
    d.r_all.set_active(True)
    assert d.scope == "all" and d.get_response_label("delete") == "Delete for everyone"
    assert any("This can't be undone." in x for x in _labels(d.get_extra_child()))
    d = DeleteChatDialog(win, grp)                                  # Gruppen-Chat, kein Saga-Admin: ausgegraut + Begruendung
    assert not d.row_all.get_sensitive() and d.row_all.get_subtitle() == "Only Saga admins can delete the history for everyone."
    d.r_all.set_active(True)
    assert d.scope == "me"
    srv.saga_admins.add("test")
    _load(chat, grp)
    chat.load_chat(grp)
    pump(lambda: chat.chat(grp).get("delete_for_all") == "history", 4)
    d = DeleteChatDialog(win, grp)
    d.r_all.set_active(True)
    assert d.row_all.get_sensitive() and d.get_response_label("delete") == "Delete history for everyone"
    d = DeleteChatDialog(win, free)                                 # freie Gruppe, kein Admin
    assert d.leave is not None and not d.row_all.get_sensitive()
    assert d.row_all.get_subtitle() == "Only admins of this group can delete it for everyone."
    d = DeleteChatDialog(win, free_admin)
    assert d.row_all.get_sensitive() and "The group is deleted for all members" in d.row_all.get_subtitle()
    d.r_all.set_active(True)
    assert not d.row_leave.get_sensitive()                          # "Gruppe ausserdem verlassen" nur mit "Nur fuer mich"
    d = DeleteChatDialog(win, page)                                 # Eigentuemer der Seite
    assert d.row_all.get_sensitive() and "The chat itself stays" in d.row_all.get_subtitle()


def test_delete_for_me_dm_purges_everything(win, srv, monkeypatch):
    chat = _setup(win, srv)
    from hlidskjalf.chatview import DeleteChatDialog
    from hlidskjalf.notifications import notifications
    toasts = _toasts(win, monkeypatch)
    a = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(a, "anna", "Geheim 1")
    _load(chat, a)
    win.show_route(("chat", a))
    conv = win.current.conv
    pump(lambda: conv.loaded, 5)
    # eigenes Bild senden: Original + Vorschaubild liegen verschluesselt im Platten-Zwischenspeicher (mit Chat-Kennung)
    conv._add_ready([("bild.jpg", "image/jpeg", _img(800, 600, fmt="JPEG"))])
    conv.send()
    pump(lambda: any(m["sender"] == "test" for m in srv.chats[a]["msgs"]), 6)
    pump(lambda: any(m.get("mine") and m.get("files") for m in chat.cache[a]["msgs"]), 5)
    pump(lambda: any(w.state == "ok" for w in conv.img_widgets), 6)
    f = [m for m in chat.cache[a]["msgs"] if m.get("mine")][-1]["files"][0]
    fids = {f["id"], f["th"]["id"]}
    names = os.listdir(chatmedia._DIR)
    assert any(n.endswith(f"-c{a}-" + f["id"]) for n in names) and chatmedia.mem_usage()[1] >= 1
    # Benachrichtigung zu diesem Chat, Entwurf im Eingabefeld
    srv.add_notif("chat.message", chat={"id": a, "kind": "dm"})
    n2 = srv.add_notif("comment.new")
    notifications.load()
    pump(lambda: len(notifications.items) == 2, 4)
    conv.entry.get_buffer().set_text("Entwurf")
    d = DeleteChatDialog(win, a)
    d._go()
    pump(lambda: chat.chat(a) is None and a not in chat.cache, 5)
    assert chat.chat(a) is None and a not in chat.cache
    pump(lambda: win.current is not None and getattr(win.current, "cid", 0) is None, 4)
    assert getattr(win.current, "cid", 0) is None                   # Unterhaltung geschlossen
    left = os.listdir(chatmedia._DIR) if os.path.isdir(chatmedia._DIR) else []
    assert not any(fid in n for n in left for fid in fids)          # Anhang + Vorschaubild von der Platte
    assert not any(k in fids for k in chatmedia._mem)               # und aus dem Speicher
    pump(lambda: [x["id"] for x in notifications.items] == [n2["id"]], 4)
    assert [x["id"] for x in notifications.items] == [n2["id"]]
    assert "Chat deleted for you." in toasts
    # Server: fuer mich ausgeblendet, Schluessel weg; die andere Person behaelt alles
    p = mockv2._pref(srv.chats[a], "test")
    assert p["hidden"] and p["cleared"] > 0 and srv.chats[a]["msgs"]
    assert not any(k == "backup:test" or (srv.cdevices.get(k) or {}).get("uid") == "test" for m in srv.chats[a]["msgs"] for k in m["keys"])
    chat.load_chats()
    pump(None, 0.5)
    assert chat.chat(a) is None                                     # bleibt weg, bis etwas Neues kommt
    m = srv.peer_send(a, "anna", "Neu")
    srv.push_event("chat.message", {"chat_id": a, "seq": m["seq"], "id": m["id"], "sender": "anna"})
    pump(lambda: chat.chat(a) is not None, 5)
    chat.load_messages(a)
    pump(lambda: chat.cache.get(a, {}).get("loaded"), 4)
    assert [x["text"] for x in chat.cache[a]["msgs"]] == ["Neu"]    # leer bis auf das Neue


def test_delete_for_me_group_archives_and_leave(win, srv, monkeypatch):
    chat = _setup(win, srv)
    from hlidskjalf.chatview import DeleteChatDialog
    toasts = _toasts(win, monkeypatch)
    g = srv.new_chat("free", ["anna", "test", "bert"], admin="anna")
    srv.peer_send(g, "anna", "Alt 1")
    srv.peer_send(g, "bert", "Alt 2")
    _load(chat, g)
    win.show_route(("chat", g))
    conv = win.current.conv
    pump(lambda: conv.loaded and len(chat.cache[g]["msgs"]) == 2, 5)
    conv.entry.get_buffer().set_text("halb geschrieben")
    d = DeleteChatDialog(win, g)
    d._go()
    pump(lambda: chat.chat(g) and chat.chat(g).get("archived") and not chat.cache[g]["msgs"], 5)
    pump(lambda: "The earlier history was deleted." in _labels(conv), 4)
    labels = _labels(conv)
    assert "The earlier history was deleted." in labels and "Alt 1" not in labels
    assert conv.entry.get_buffer().get_char_count() == 0            # Entwurf verworfen
    assert getattr(win.current, "cid", None) == g                   # Chat bleibt offen (archiviert)
    assert "History deleted for you, chat archived." in toasts
    # neue Nachricht danach ist sichtbar, die alten bleiben weg
    m = srv.peer_send(g, "bert", "Neu nach dem Loeschen")
    srv.push_event("chat.message", {"chat_id": g, "seq": m["seq"], "id": m["id"], "sender": "bert"})
    pump(lambda: "Neu nach dem Loeschen" in _labels(conv), 5)
    assert [x["text"] for x in chat.cache[g]["msgs"]] == ["Neu nach dem Loeschen"]
    # nochmal, diesmal mit "Gruppe ausserdem verlassen"
    d = DeleteChatDialog(win, g)
    d.leave.set_active(True)
    d._go()
    pump(lambda: chat.chat(g) is None, 5)
    assert "test" not in srv.chats[g]["members"] and "History deleted, group left." in toasts
    pump(lambda: getattr(win.current, "cid", 0) is None, 4)


def test_delete_for_all_and_403(win, srv, monkeypatch):
    chat = _setup(win, srv)
    toasts = _toasts(win, monkeypatch)
    a = srv.new_chat("dm", ["test", "anna"])
    grp = srv.new_chat("group", ["test", "bert"], group="team")
    srv.peer_send(a, "anna", "x")
    srv.peer_send(grp, "bert", "y")
    _load(chat, a, grp)
    done = []
    chat.delete_chat(grp, "all", done=done.append, error=lambda e: done.append(e))
    pump(lambda: done, 4)
    assert getattr(done[0], "code", None) == 403 and srv.chats[grp]["msgs"]   # ohne Recht
    chat.delete_chat(a, "all", done=done.append)
    pump(lambda: len(done) == 2, 4)
    assert done[1]["removed"] and a not in srv.chats and chat.chat(a) is None
    pump(None, 0.5)
    assert not [t for t in toasts if "deleted the chat" in t]       # eigener Loeschvorgang: kein "<Name> hat ..."
    srv.saga_admins.add("test")
    chat.delete_chat(grp, "all", done=done.append)
    pump(lambda: len(done) == 3, 4)
    assert done[2].get("deleted") == 1 and not srv.chats[grp]["msgs"] and chat.chat(grp) is not None
    pump(lambda: int(chat.chat(grp).get("cleared_seq") or 0) > 0, 4)


def test_live_removed_and_cleared_by_others(win, srv, monkeypatch):
    chat = _setup(win, srv)
    toasts = _toasts(win, monkeypatch)
    a = srv.new_chat("dm", ["test", "anna"])
    grp = srv.new_chat("group", ["test", "bert"], group="team")
    srv.peer_send(a, "anna", "Bis gleich")
    srv.peer_send(grp, "bert", "Altes Team-Thema")
    _load(chat, a, grp)
    # Verlauf fuer alle geloescht (bert ist Saga-Admin) - offener Chat wird geleert, Hinweis mit Namen
    win.show_route(("chat", grp))
    conv = win.current.conv
    pump(lambda: conv.loaded and "Altes Team-Thema" in _labels(conv), 5)
    srv.saga_admins.add("bert")
    srv.me = "bert"
    mockv2.delete_chat(srv, srv.chats[grp], "bert", "all")
    srv.me = "test"
    pump(lambda: "Bert Beispiel deleted the history." in toasts, 5)
    pump(lambda: "The earlier history was deleted." in _labels(conv), 5)
    assert "Altes Team-Thema" not in _labels(conv) and not chat.cache[grp]["msgs"]
    assert getattr(win.current, "cid", None) == grp
    # Chat fuer alle geloescht - offener Chat schliesst, Hinweis
    win.show_route(("chat", a))
    conv = win.current.conv
    pump(lambda: conv.loaded and "Bis gleich" in _labels(conv), 5)
    srv.me = "anna"
    mockv2.delete_chat(srv, srv.chats[a], "anna", "all")
    srv.me = "test"
    pump(lambda: "Anna Beispiel deleted the chat." in toasts, 5)
    pump(lambda: getattr(win.current, "cid", 0) is None, 4)
    assert chat.chat(a) is None and a not in chat.cache


def test_live_cleared_from_my_other_device(win, srv):
    chat = _setup(win, srv)
    a = srv.new_chat("dm", ["test", "anna"])
    g = srv.new_chat("free", ["test", "anna"], admin="test")
    srv.peer_send(a, "anna", "Direkt")
    srv.peer_send(g, "anna", "Gruppe")
    _load(chat, a, g)
    for c in (a, g):
        chat.load_messages(c)
    pump(lambda: all(chat.cache.get(c, {}).get("loaded") for c in (a, g)), 4)
    chatmedia.put_blob("vonA", b"x" * 10, a)
    mockv2.delete_chat(srv, srv.chats[a], "test", "me")             # "anderes Geraet": nur das Ereignis kommt an
    mockv2.delete_chat(srv, srv.chats[g], "test", "me")
    pump(lambda: chat.chat(a) is None and chat.chat(g) and chat.chat(g).get("archived"), 5)
    assert a not in chat.cache and not chat.cache[g]["msgs"]
    assert not os.path.exists(chatmedia._path("vonA", "", a))


def test_purge_chat_keeps_newer_and_legacy(srv):
    chatmedia.put_blob("alt", b"1", 7)
    chatmedia.put_blob("neu", b"2", 7)
    chatmedia.put_blob("anders", b"3", 8)
    chatmedia.put_blob("legacy", b"4")                              # aeltere Version ohne Chat-Kennung
    assert chatmedia.get_blob("legacy", cid=7) == b"4"              # wird weiter gelesen
    n = chatmedia.purge_chat(7, fids={"legacy"}, keep={"neu"})
    assert n == 2
    assert not os.path.exists(chatmedia._path("alt", "", 7)) and os.path.exists(chatmedia._path("neu", "", 7))
    assert os.path.exists(chatmedia._path("anders", "", 8)) and not os.path.exists(chatmedia._path("legacy"))
    chatmedia.clear()


def test_old_server_hides_archive_and_delete(win, srv):
    srv.chat_manage_api = False
    chat = _setup(win, srv)
    from hlidskjalf.chatview import manage_entries
    a = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(a, "anna", "Hallo")
    _load(chat, a)
    c = chat.chat(a)
    assert not chat.can_archive(c) and not chat.can_delete(c) and manage_entries(win, c) == []
    win.show_root(("chats",))
    pump(None, 0.3)
    lst = win.current.list
    rows = [r for r in _walk_rows(lst.box) if getattr(r, "cid", None) == a]
    labels = _menu_labels(lst._menu(rows[0], c, 5, 5))
    assert "Archive" not in labels and "Delete chat …" not in labels and "Open" in labels
    assert not [x for x in _labels(lst) if x.startswith("Archived")]
