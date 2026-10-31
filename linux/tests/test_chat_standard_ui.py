"""2.5 Chat-Standard - Oberflaeche gegen den nachgebauten Server (tests/mockstd.py): Gruppeninfo (Bild, Beschreibung,
Rechte, Seiten-Chat-Mitglieder mit Rolle, Entfernen nach removable, Sperren), Unterhaltung (can_send-Hinweis, Pins,
Admin-Loeschen mit Grabstein, gesperrte Nachrichten eingeklappt, Melden), Chatliste (Stumm mit Dauer/notify, Anheften,
ungelesen, ausblenden), Nachrichten (Formatierung, Erwaehnungen, Linkvorschau, Medien), Entwuerfe, Mehrfachauswahl."""
import time

from gi.repository import Adw, Gtk

from hk_helpers import idle, pump
from test_chat import _ready, _walk
from test_gruppeninfo_kontakte import _NoPop, _labels, _rows, _titles, _toasts

STD = ["chatadm1", "contacts1", "pagechatmembers1", "groupinfo2", "moddel1", "msgpin1", "block2", "report1", "mute2", "chatpin1",
       "markunread1", "chathide1", "chatmention1", "chatfmt1", "unfurl1", "chatmedia1"]


def _setup(srv, feats=STD):
    from hlidskjalf.chat import chat
    from hlidskjalf.live import live
    from hlidskjalf.store import store
    srv.v25 = True
    srv.extra_features = list(feats)
    srv.chatadm = "chatadm1" in feats
    srv.contacts_on = "contacts1" in feats
    srv.chatstd = True
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


def _open(win, cid):
    from hlidskjalf.chatview import ChatsScreen
    win.show_route(("chat", cid))
    pump(lambda: isinstance(win.current, ChatsScreen) and win.current.conv is not None and win.current.conv.loaded, 5)
    idle()
    return win.current.conv


class _Pop(_NoPop):
    def set_pointing_to(self, r):
        pass

    def connect(self, *a):
        pass

    def get_child(self):
        raise AttributeError


def _menu_capture(monkeypatch, mod="hlidskjalf.chatview"):
    got = []
    monkeypatch.setattr(mod + ".menu_popover", lambda parent, ents: got.append(ents) or _Pop())
    return got


def _texts(ents):
    return [e[0] for e in ents if e]


def _find(ents, text):
    return next(e for e in ents if e and e[0] == text)


# --- Gruppeninfo -----------------------------------------------------------------------------------------------------
def test_group_desc_avatar_perms(win, srv, monkeypatch):
    chat = _setup(srv)
    from hlidskjalf import chatstdview as SV
    from hlidskjalf.chatview import GroupInfoDialog
    g = srv.new_chat("free", ["test", "anna"], admin="test")
    _load(chat, g)
    d = GroupInfoDialog(win, g)
    d.present(win)
    pump(None, 0.3)
    toasts = _toasts(d, monkeypatch)
    assert "No description." in _labels(d) and "Group permissions" in [g0.get_title() for g0 in d.std_groups]
    sw = [r for r in _rows(d, Adw.SwitchRow)]
    assert [r.get_title() for r in sw] == ["Only admins can send messages",
                                           "Only admins can change name, picture and description and pin messages"]
    assert all(r.get_sensitive() for r in sw)
    # Beschreibung setzen -> verschluesselte Steuer-Nachricht kind 'desc', danach entschluesselt aus desc_msg
    SV.set_group_desc(g, "Worum es geht <b>", done=lambda: None, error=lambda e: toasts.append(str(e)))
    pump(lambda: srv.chats[g].get("desc_seq") and chat.chat(g).get("desc_msg"), 4)
    chat.chat(g).pop("_desc", None)
    chat._grp_info([chat.chat(g)])
    assert chat.chat(g)["_desc"] == "Worum es geht <b>"
    pump(lambda: "Worum es geht <b>" in _labels(d), 3)
    assert ("SEND", g, {"kind": "desc", "mentions": None, "files": None}) in srv.std_log
    # Gruppenbild: zuschneiden (256x256), hochladen, kind 'avatar' mit files
    import io

    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (400, 200), (200, 30, 30)).save(buf, "PNG")
    got = {}
    SV.set_group_avatar(g, buf.getvalue(), done=lambda f: got.setdefault("f", f), error=lambda e: got.setdefault("e", e))
    pump(lambda: got, 5)
    assert "f" in got and got["f"]["w"] == 256
    av_send = [x for x in srv.std_log if x[0] == "SEND" and x[2]["kind"] == "avatar"]
    assert av_send and av_send[-1][2]["files"] == [got["f"]["id"]]
    pump(lambda: chat.chat(g).get("avatar_msg"), 3)
    chat.chat(g).pop("_avf", None)
    chat._grp_info([chat.chat(g)])
    assert chat.chat(g)["_avf"]["id"] == got["f"]["id"]
    tex = {}
    SV.group_texture(chat.chat(g), lambda t: tex.setdefault("t", t))
    pump(lambda: tex, 5)
    assert tex["t"].get_width() <= 320
    # Rechte umschalten -> PATCH perm_send, Systemzeile/Hinweis
    sw = [r for r in _rows(d, Adw.SwitchRow)]
    sw[0].set_active(True)
    pump(lambda: srv.chats[g].get("perm_send") == "admins", 3)
    assert ("PATCH", g, {"perm_send": "admins"}) in srv.std_log
    d.force_close()


def test_group_info_member_without_info_rights(win, srv):
    chat = _setup(srv)
    from hlidskjalf.chatview import GroupInfoDialog
    g = srv.new_chat("free", ["anna", "test"], admin="anna")
    srv.chats[g]["perm_info"] = "admins"
    srv.chats[g]["perm_send"] = "admins"
    _load(chat, g)
    c = chat.chat(g)
    assert c["can_send"] is False and c["can_edit_info"] is False and c["can_perms"] is False
    d = GroupInfoDialog(win, g)
    d.present(win)
    pump(None, 0.3)
    sw = _rows(d, Adw.SwitchRow)
    assert len(sw) == 2 and not any(r.get_sensitive() for r in sw) and all(r.get_active() for r in sw)
    assert "Only admins change name, picture and description." in _labels(d)
    assert not any(isinstance(w, Gtk.Button) and w.has_css_class("hk-gi-avb") for w in _walk(d))
    d.force_close()
    conv = _open(win, g)
    assert not conv.composer_box.get_visible() and conv.state_box.get_visible()
    assert "🔒 Only admins can send messages in this group." in _labels(conv.state_box)


def test_older_server_hides_everything(win, srv, monkeypatch):
    chat = _setup(srv, feats=["chatadm1", "contacts1"])
    from hlidskjalf.chatview import GroupInfoDialog
    g = srv.new_chat("free", ["test", "anna"], admin="test")
    srv.peer_send(g, "anna", "*fett* <@~anna>")
    _load(chat, g)
    d = GroupInfoDialog(win, g)
    d.present(win)
    pump(None, 0.3)
    assert d.std_groups == [] and "Media, files, links" not in _titles(d) and "Mute" in _titles(d)
    d.force_close()
    conv = _open(win, g)
    pump(lambda: conv.rows_by_id, 3)
    assert any("*fett*" in (lb.get_label() or "") for lb in _walk(conv.msgs) if isinstance(lb, Gtk.Label))
    got = _menu_capture(monkeypatch)
    conv.menu(win.menu_btn)
    t = _texts(got[-1])
    assert "Mute" in t and "Pin" not in t and "Media, files, links" not in t and "Hide for me" not in t


def test_page_chat_members(win, srv, monkeypatch):
    chat = _setup(srv)
    from hlidskjalf.chatview import GroupInfoDialog
    nid = 1
    pc = srv.new_chat("page", ["test", "anna", "bert"], note_id=nid)
    srv.page_via[(pc, "bert")] = "group"
    _load(chat, pc)
    d = GroupInfoDialog(win, pc)
    d.present(win)
    pump(None, 0.3)
    _toasts(d, monkeypatch)
    assert "Adding people shares this page with them. Removing revokes the share." in _labels(d)
    assert "Add member" in _labels(d) and d.role_row is not None
    rows = {r.get_title(): r for r in _rows(d.members_group)}
    assert "page_remove" in rows["Anna Beispiel"].hk_actions
    assert "page_off" in rows["Bert Beispiel"].hk_actions and rows["Bert Beispiel"].hk_why == "Access through a group – remove them there."
    d._toggle_add()
    assert "The page will be shared with this person." in _labels(d)
    d.role_row.set_selected(2)
    srv.chat_peer("cora", "Cora Beispiel")
    d._add("~cora")
    pump(lambda: getattr(srv, "member_adds", None), 3)
    assert srv.member_adds[-1] == {"user": "~cora", "role": "edit"}
    d._ask_remove_page(next(m for m in chat.chat(pc)["members"] if m["username"] == "anna"))
    pump(lambda: isinstance(win.get_visible_dialog(), Adw.AlertDialog), 2)
    ad = win.get_visible_dialog()
    assert ad.get_heading() == "Remove Anna Beispiel from the page chat?"
    ad.emit("response", "ok")
    ad.force_close()
    pump(lambda: "anna" not in srv.chats[pc]["members"], 3)
    d.force_close()


def test_block_member_and_collapse(win, srv, monkeypatch):
    chat = _setup(srv)
    from hlidskjalf.chatview import GroupInfoDialog
    g = srv.new_chat("free", ["test", "anna", "bert"], admin="test")
    srv.peer_send(g, "anna", "Hallo von Anna")
    _load(chat, g)
    d = GroupInfoDialog(win, g)
    d.present(win)
    pump(None, 0.3)
    rows = {r.get_title(): r for r in _rows(d.members_group)}
    assert "block" in rows["Anna Beispiel"].hk_actions
    from hlidskjalf import chatstdview as SV
    SV.block_person(d, win, "~anna", "Anna Beispiel", lambda t: None)
    pump(lambda: isinstance(win.get_visible_dialog(), Adw.AlertDialog), 2)
    ad = win.get_visible_dialog()
    assert ad.get_heading() == "Block Anna Beispiel?"
    ad.emit("response", "ok")
    ad.force_close()
    pump(lambda: srv.blocks and any(m.get("blocked") for m in chat.chat(g)["members"]), 4)
    assert ("POST", "api/chat/blocks", {"target": "~anna"}) in srv.std_log
    d.force_close()
    conv = _open(win, g)
    pump(lambda: any("Message from a blocked person" in (x or "") for x in _labels(conv.msgs)), 3)
    assert not any("Hallo von Anna" in (x or "") for x in _labels(conv.msgs))
    btn = next(w for w in _walk(conv.msgs) if isinstance(w, Gtk.Button) and w.get_label() == "Show")
    btn.emit("clicked")
    pump(lambda: any("Hallo von Anna" in (x or "") for x in _labels(conv.msgs)), 3)


# --- Unterhaltung: Pins, Admin-Loeschen, Melden ----------------------------------------------------------------------
def test_pins_moddel_report(win, srv, monkeypatch):
    chat = _setup(srv)
    g = srv.new_chat("free", ["test", "anna"], admin="test")
    srv.peer_send(g, "anna", "Erste Nachricht")
    srv.peer_send(g, "anna", "Zweite Nachricht")
    _load(chat, g)
    conv = _open(win, g)
    pump(lambda: len((chat.cache.get(g) or {}).get("msgs") or []) == 2, 3)
    m1, m2 = chat.cache[g]["msgs"]
    got = _menu_capture(monkeypatch)
    w = conv.rows_by_id[m1["id"]]
    monkeypatch.setattr("hlidskjalf.chatx3.point_at", lambda *a: None)
    try:
        conv._msg_menu(w, m1)
    except AttributeError:
        pass
    t = _texts(got[-1])
    assert "Pin" in t and "Report …" in t and "Delete (admin)" in t and "Block Anna Beispiel" in t
    conv.pin_msg(m1["id"], True)
    pump(lambda: chat.chat(g).get("pins") == [m1["id"]], 3)
    pump(lambda: conv.pin_bar.get_visible(), 3)
    assert "Erste Nachricht" in conv.pin_label.get_label() and conv.pin_label.get_label().startswith("Pinned")
    conv.pin_msg(m2["id"], True)
    pump(lambda: len(chat.chat(g).get("pins") or []) == 2, 3)
    assert conv.pin_label.get_label().startswith("Pinned (1/2)")
    # Admin loescht m2 -> Grabstein, Pin weg
    chat.mod_delete(g, m2["id"])
    pump(lambda: (chat._cached(g, m2["id"]) or {}).get("state") == "tomb", 3)
    pump(lambda: any("deleted by an admin" in (x or "") for x in _labels(conv.msgs)), 3)
    assert chat.chat(g)["pins"] == [m1["id"]]
    # nach Neuladen mit tombs=1 bleibt der Grabstein
    chat.cache.pop(g, None)
    chat.load_messages(g)
    pump(lambda: (chat._cached(g, m2["id"]) or {}).get("state") == "tomb", 3)
    # Melden
    from hlidskjalf.chatstdview import ReportDialog
    rd = ReportDialog(win, chat.chat(g), [m1])
    rd.present(win)
    b = rd.tv.get_buffer()
    assert b.get_text(b.get_start_iter(), b.get_end_iter(), False) == "Anna Beispiel: Erste Nachricht"
    rd.reason.set_selected(1)
    rd._go()
    pump(lambda: srv.reports, 3)
    assert srv.reports[-1] == {"chat": g, "msgs": [m1["id"]], "reason": "abuse", "text": "Anna Beispiel: Erste Nachricht"}
    rd2 = ReportDialog(win, chat.chat(g), [m1])
    rd2.present(win)
    rd2.inc.set_active(False)
    rd2._go()
    pump(lambda: len(srv.reports) == 2, 3)
    assert srv.reports[-1]["text"] == ""


# --- Chatliste (Paket A) ---------------------------------------------------------------------------------------------
def test_list_mute_pin_unread_hide(win, srv, monkeypatch):
    chat = _setup(srv)
    from hlidskjalf import chatstd as K
    from hlidskjalf.chatview import ChatList, ChatsScreen
    srv.groups["team"] = ["test", "anna"]
    a = srv.new_chat("dm", ["test", "anna"])
    gg = srv.new_chat("group", ["test", "anna"], group="team")
    _load(chat, a, gg)
    win.show_route(("chats",))
    pump(lambda: isinstance(win.current, ChatsScreen), 3)
    lst = next(w for w in _walk(win.current) if isinstance(w, ChatList))
    got = _menu_capture(monkeypatch)
    row = next(r for r in _walk(lst.box) if getattr(r, "cid", None) == a)
    lst._menu(row, chat.chat(a))
    ents = got[-1]
    t = _texts(ents)
    assert "Mute" in t and "Pin" in t and "Mark as unread" in t and "Hide for me" not in t     # Direkt-Chat: nicht ausblendbar
    sub = _find(ents, "Mute")[1]
    st = _texts(sub)
    assert st[:4] == ["1 hour", "8 hours", "1 week", "Forever"] and "✓ Notifications: All messages" in st
    # 8 Stunden
    sub[1][1]()
    pump(lambda: (chat.chat(a) or {}).get("muted_until", 0) > time.time(), 3)
    assert K.mute_label(chat.chat(a)).startswith("Muted until ")
    pump(lambda: any(getattr(r, "cid", None) == a for r in _walk(lst.box)), 2)
    # Nur Erwaehnungen
    lst._menu(row, chat.chat(a))
    _find(got[-1], K.mute_label(chat.chat(a)))[1][-2][1]()
    pump(lambda: chat.chat(a).get("notify") == "mentions", 3)
    # Anheften -> oben mit Nadel
    _find(got[-1], "Pin")[1]()
    pump(lambda: chat.chat(a).get("pinned") and chat.chats[0]["id"] == a, 3)
    pump(lambda: any(isinstance(w, Gtk.Label) and w.get_label() == "📌" for w in _walk(lst.box)), 3)
    # als ungelesen -> Punkt
    lst._menu(row, chat.chat(a))
    _find(got[-1], "Mark as unread")[1]()
    pump(lambda: chat.chat(a).get("unread_mark"), 3)
    pump(lambda: any(isinstance(w, Gtk.Widget) and w.has_css_class("hk-unread-dot") for w in _walk(lst.box)), 3)
    # Ausblenden der Gruppe -> Bereich "Ausgeblendet (1)"
    rowg = next(r for r in _walk(lst.box) if getattr(r, "cid", None) == gg)
    lst._menu(rowg, chat.chat(gg))
    _find(got[-1], "Hide for me")[1]()
    pump(lambda: chat.chat(gg) is None and chat.hidden_count == 1, 3)
    pump(lambda: "Hidden (1)" in _labels(lst.box), 3)
    head = next(r for r in _walk(lst.box) if getattr(r, "hid_toggle", False))
    lst._activated(head)
    pump(lambda: any(getattr(r, "cid", None) == gg for r in _walk(lst.box)), 3)
    lst._menu(next(r for r in _walk(lst.box) if getattr(r, "cid", None) == gg), next(c for c in lst.hidden if c["id"] == gg))
    _find(got[-1], "Show again")[1]()
    pump(lambda: chat.chat(gg) is not None and chat.hidden_count == 0, 3)


def test_chat_pin_limit_409(win, srv):
    chat = _setup(srv)
    ids = []
    for i in range(6):
        srv.chat_peer(f"p{i}", f"Person {i}")
        ids.append(srv.new_chat("dm", ["test", f"p{i}"]))
    _load(chat, *ids)
    errs = []
    for cid in ids:
        box = {}
        chat.patch(cid, {"pinned": True}, done=lambda c, b=box: b.setdefault("ok", 1), error=lambda e, b=box: b.setdefault("e", e))
        pump(lambda: box, 3)
        if "e" in box:
            errs.append(str(box["e"]))
    assert errs == ["Höchstens 5 Chats anheften."]


# --- Nachrichten (Paket C) -------------------------------------------------------------------------------------------
def test_format_mention_render(win, srv):
    chat = _setup(srv)
    g = srv.new_chat("free", ["test", "anna"], admin="test")
    srv.peer_send(g, "anna", "*fett* `<b>x</b>` <@~test> und <@~anna> und <@~zzzzzzzzzzzzzzzz>")
    _load(chat, g)
    conv = _open(win, g)
    pump(lambda: conv.rows_by_id, 3)
    lb = next(w for w in _walk(conv.msgs) if isinstance(w, Gtk.Label) and w.has_css_class("hk-bubble-text"))
    assert lb.get_text().startswith("fett <b>x</b> @") and "@Anna Beispiel" in lb.get_text() and "@Unknown" in lb.get_text()
    assert lb.get_attributes() is not None


def test_mentions_sent_and_preview(win, srv, monkeypatch):
    chat = _setup(srv)
    g = srv.new_chat("free", ["test", "anna"], admin="test")
    srv.unfurl["https://news.example/a"] = {"url": "https://news.example/a", "title": "Ein Artikel <b>", "desc": "Kurz", "site": "News"}
    _load(chat, g)
    conv = _open(win, g)
    conv.entry.get_buffer().set_text("@An")
    pump(lambda: conv.people_pop is not None and conv.people_items, 3)
    assert [p["name"] for p in conv.people_items] == ["Anna Beispiel"]
    conv._people_pick(conv.people_items[0])
    buf = conv.entry.get_buffer()
    assert buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False) == "@Anna Beispiel "
    buf.insert(buf.get_end_iter(), "siehe https://news.example/a")
    pump(lambda: conv.unf_data is not None, 4)
    assert conv.unf_box.get_visible() and "Ein Artikel <b>" in _labels(conv.unf_box)
    conv.send()
    pump(lambda: any(x[0] == "SEND" and x[2]["mentions"] for x in srv.std_log), 4)
    sent = [x for x in srv.std_log if x[0] == "SEND"][-1]
    assert sent[2]["mentions"] == ["~anna"]
    pump(lambda: any(m.get("mine") for m in (chat.cache.get(g) or {}).get("msgs") or []), 4)
    mine = [m for m in chat.cache[g]["msgs"] if m.get("mine")][-1]
    assert mine["text"] == "<@~anna> siehe https://news.example/a"
    assert mine["body"]["preview"] == {"url": "https://news.example/a", "title": "Ein Artikel <b>", "desc": "Kurz", "site": "News"}
    pump(lambda: any(w.has_css_class("hk-unf-msg") for w in _walk(conv.msgs) if isinstance(w, Gtk.Widget)), 3)
    # Vorschau entfernen: beim naechsten Senden keine
    buf.set_text("noch https://news.example/a")
    pump(lambda: conv.unf_data is not None, 4)
    conv._unf_close()
    conv.send()
    pump(lambda: len([m for m in chat.cache[g]["msgs"] if m.get("mine")]) == 2, 4)
    assert "preview" not in [m for m in chat.cache[g]["msgs"] if m.get("mine")][-1]["body"]
    # Nutzereinstellung aus: keine Anfrage
    from hlidskjalf import config
    config.settings.set("chat_unfurl", False)
    n = len([x for x in srv.std_log if x[1] == "api/chat/unfurl"])
    buf.set_text("https://news.example/a")
    pump(None, 1.0)
    assert len([x for x in srv.std_log if x[1] == "api/chat/unfurl"]) == n
    config.settings.set("chat_unfurl", None)


def test_media_overview(win, srv):
    chat = _setup(srv)
    g = srv.new_chat("free", ["test", "anna"], admin="test")
    srv.peer_send(g, "anna", "Link https://x.example/1")
    _load(chat, g)
    conv = _open(win, g)
    import io

    from PIL import Image
    b = io.BytesIO()
    Image.new("RGB", (40, 30), (0, 99, 0)).save(b, "PNG")
    conv.files = [("bild.png", "image/png", b.getvalue()), ("doc.pdf", "application/pdf", b"%PDF-1.4 x")]
    conv.entry.get_buffer().set_text("Anhang")
    conv.send()
    pump(lambda: any(m.get("files") for m in (chat.cache.get(g) or {}).get("msgs") or []), 5)
    from hlidskjalf.chatstdview import MediaDialog
    md = MediaDialog(conv, g)
    md.present(win)
    pump(lambda: not md.loading, 5)
    it = md.items()
    assert [f["n"] for f, _m in it["media"]] == ["bild.png"] and [f["n"] for f, _m in it["files"]] == ["doc.pdf"]
    assert [u for u, _m in it["links"]] == ["https://x.example/1"]
    q = [x for x in srv.log if "has=file" in x[1]]
    assert q
    md.force_close()


# --- Entwuerfe und Mehrfachauswahl ------------------------------------------------------------------------------------
def test_drafts_per_chat(win, srv):
    chat = _setup(srv)
    from hlidskjalf import chatdrafts
    a = srv.new_chat("dm", ["test", "anna"])
    b = srv.new_chat("dm", ["test", "bert"])
    _load(chat, a, b)
    conv = _open(win, a)
    conv.entry.get_buffer().set_text("Entwurf fuer Anna")
    conv._draft_save()
    assert chatdrafts.load(a)["t"] == "Entwurf fuer Anna"
    conv2 = _open(win, b)
    buf = conv2.entry.get_buffer()
    assert buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False) == ""
    conv = _open(win, a)
    buf = conv.entry.get_buffer()
    assert buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False) == "Entwurf fuer Anna"
    conv.send()
    pump(lambda: chatdrafts.load(a) is None, 4)
    chatdrafts.save(b, "noch da")
    chatdrafts.clear_account()
    assert chatdrafts.load(b) is None


def test_multi_select_hide_forward(win, srv, monkeypatch):
    chat = _setup(srv)
    a = srv.new_chat("dm", ["test", "anna"])
    b = srv.new_chat("dm", ["test", "bert"])
    srv.peer_send(a, "anna", "Eins")
    srv.peer_send(a, "anna", "Zwei")
    srv.peer_send(a, "anna", "Drei")
    _load(chat, a, b)
    conv = _open(win, a)
    pump(lambda: len(chat.cache[a]["msgs"]) == 3, 3)
    m1, m2, m3 = chat.cache[a]["msgs"]
    conv.sel_start(m1["id"])
    pump(lambda: conv.sel_bar.get_visible(), 2)
    conv._sel_toggle(m2["id"], True)
    assert conv.sel == {m1["id"], m2["id"]} and "2 selected" in conv.sel_label.get_label()
    # Weiterleiten an Bert
    from hlidskjalf.chatstdview import ForwardDialog
    fd = ForwardDialog(conv, conv._sel_msgs())
    assert any(getattr(r, "hk_cid", None) == b for r in _walk(fd.lb))
    fd.go(chat.chat(b))
    pump(lambda: len([m for m in srv.chats[b]["msgs"] if m["kind"] == "msg"]) == 2, 5)
    # Fuer mich loeschen
    conv._sel_hide(confirm=False)
    pump(lambda: len(chat.cache[a]["msgs"]) == 1, 4)
    assert ("POST", f"api/chats/{a}/hide", {"ids": [m1["id"], m2["id"]]}) in srv.std_log
    assert conv.sel is None and not conv.sel_bar.get_visible()
    chat.cache.pop(a, None)
    chat.load_messages(a)
    pump(lambda: (chat.cache.get(a) or {}).get("loaded"), 3)
    assert [m["id"] for m in chat.cache[a]["msgs"]] == [m3["id"]]
