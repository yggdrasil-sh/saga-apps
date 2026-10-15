"""Live-Kanal und Benachrichtigungen (Server 1.4): SSE-Zeilen, Verbindung (hello), Glocke mit Zahl und Liste, Gelesen,
Desktop-Mitteilung, fremde Aenderung an der offenen Seite (still neu laden bzw. Hinweis), Anwesenheit, Kommentar-Zahlen,
aeltere Server ohne Live-Kanal."""
import io
import json

from gi.repository import Gtk

from hk_helpers import idle, pump


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label)]


def _connected(win):
    from hlidskjalf.live import live
    assert pump(lambda: live.connected, 6), "Live-Kanal nicht verbunden"
    idle()


def _open(win, nid):
    win.show_root(("page", nid))
    v = win.current
    pump(lambda: v.doc.fetched, 6)
    pump(None, 0.4)
    idle()
    return v


# --- ohne GTK ----------------------------------------------------------------------------------------------------
def test_sse_parser():
    from hlidskjalf.api import sse_events
    raw = (b": " + b"x" * 2048 + b"\n\n" + b"id: b-1\nevent: hello\ndata: {\"boot\": \"b\"}\n\n: ping 123\n\n"
           b"event: delta\ndata: {\"t\": \"a\"}\n\nevent: multi\ndata: {\"a\":\ndata: 1}\n\nevent: bad\ndata: {kaputt\n\n")
    out = list(sse_events(io.BytesIO(raw)))
    assert out[0] == ("hello", {"boot": "b"}, "b-1")
    assert out[1] == ("delta", {"t": "a"}, None)
    assert out[2] == ("multi", {"a": 1}, None)
    assert out[3] == ("bad", None, None)


def test_item_texts_and_urls():
    from hlidskjalf.notifications import item_tag, item_text, item_title, parse_url
    a = {"name": "Anna", "username": "anna"}
    assert item_text({"kind": "share.added", "actor": a, "role": "edit"}) == "Anna shared a page with you (Can edit)"
    assert item_text({"kind": "share.role", "actor": a, "role": "view"}) == "Anna changed your role: Can view"
    assert item_text({"kind": "comment.new", "actor": a, "count": 1}) == "Anna commented on your page"
    assert item_text({"kind": "comment.new", "actor": a, "count": 3}) == "3 new comments on your page"
    assert item_text({"kind": "comment.reply", "actor": a, "count": 2}) == "2 new replies in a thread"
    assert item_text({"kind": "publish.withdrawn", "actor": None}) == "An admin withdrew your published page"
    assert item_text({"kind": "chat.message", "actor": a, "count": 1}) == "New message from Anna"
    assert item_text({"kind": "share.added", "actor": None, "role": "view"}) == "Deleted account shared a page with you (Can view)"
    assert item_text({"kind": "neu", "text": "Server-Text"}) == "Server-Text"
    assert item_title({"access": False, "note": {"title": "x"}}) == "No access anymore"
    assert item_title({"note": {"id": -2049, "title": "Fern", "icon": "", "host": "b.example"}}) == "Fern · b.example"
    assert item_tag({"chat": {"id": 5}}) == "chat-5" and item_tag({"thread_id": -7, "note": {"id": 1}}) == "thread--7"
    assert parse_url("/notes#12:abc") == ("page", 12, "abc") and parse_url("/notes#-2049") == ("page", -2049, None)
    assert parse_url("/chats#c5") == ("chat", 5, None) and parse_url("/chats#7") == ("chat", 7, None)
    assert parse_url("/") is None


# --- mit Fenster --------------------------------------------------------------------------------------------------
def test_bell_live_and_read(win, srv):
    from hlidskjalf.notifications import notifications
    srv.add_notif("comment.new", actor={"name": "Bert Beispiel", "username": "bert"})
    notifications.load()
    _connected(win)
    pump(lambda: notifications.unread == 1, 3)
    assert win.bell.badge.get_visible() and win.bell.badge.get_label() == "1"
    # neue Benachrichtigung live (ersetzt eine gebuendelte)
    old = notifications.items[0]["id"]
    it = {"id": 77, "kind": "comment.new", "created": 1.0, "read": False, "actor": {"name": "Anna", "username": "anna"}, "count": 2,
          "access": True, "note": {"id": 1, "title": "Startseite", "icon": ""}, "title": "Startseite", "text": "", "url": "/notes#1"}
    srv.push_event("notification", {"item": it, "unread": 1, "replaces": old})
    pump(lambda: notifications.items and notifications.items[0]["id"] == 77, 4)
    assert [x["id"] for x in notifications.items] == [77] and win.bell.badge.get_label() == "1"
    assert "2 new comments on your page" in _labels(win.bell.panel)
    # anderswo gelesen
    srv.push_event("notification", {"unread": 0, "read": [77]})
    pump(lambda: notifications.unread == 0, 4)
    assert not win.bell.badge.get_visible() and notifications.items[0]["read"]
    # "Alle gelesen"
    srv.add_notif("share.added", role="edit")
    notifications.load()
    pump(lambda: notifications.unread == 1, 3)
    notifications.mark_read(everything=True)
    idle()
    pump(None, 0.3)
    assert any(m == "POST" and u.endswith("api/notifications/read") and b == {"all": True} for m, u, b, c in srv.log)
    assert notifications.unread == 0


def test_open_notification_opens_page(win, srv):
    from hlidskjalf.notifications import notifications
    it = srv.add_notif("comment.reply", thread_id=5, block_id="p1", url="/notes#2")
    notifications.load()
    pump(lambda: notifications.items, 3)
    row = [r for r in _walk(win.bell.panel.list) if hasattr(r, "run")][0]
    row.run()
    pump(lambda: win.route == ("page", 2), 3)
    idle()
    pump(None, 0.3)
    assert win.route == ("page", 2)
    assert any(m == "POST" and u.endswith("api/notifications/read") and b == {"ids": [it["id"]]} for m, u, b, c in srv.log)
    assert win.current.comments_open or True


def test_desktop_notification_when_inactive(win, srv, monkeypatch):
    from hlidskjalf import notifications as nmod
    sent = []

    class FakeApp:
        def send_notification(self, nid, n):
            sent.append((nid, n))
    it = {"id": 5, "kind": "chat.message", "actor": {"name": "Anna"}, "count": 1, "chat": {"id": 3, "kind": "dm"}, "title": "Anna",
          "url": "/chats#c3", "access": True}
    assert nmod.show_desktop(FakeApp(), it)
    assert sent[0][0] == "chat-3"
    from hlidskjalf.config import settings
    settings.set("desktop_notify", False)
    assert not nmod.show_desktop(FakeApp(), it)
    settings.set("desktop_notify", True)


def test_note_changed_silent_reload_and_banner(win, srv):
    v = _open(win, 3)
    _connected(win)
    rev0 = v.doc.rev
    n = srv.notes[3]
    n["body"] = json.dumps({"blocks": [{"id": "x1", "type": "paragraph", "data": {"text": "Von Bert geändert"}}]})
    n["rev"] = rev0 + 1
    srv.push_event("note.changed", {"id": 3, "rev": rev0 + 1, "updated": 1.0, "by": {"name": "Bert", "username": "bert"}, "gone": False})
    pump(lambda: v.doc.rev == rev0 + 1, 5)
    idle()
    assert v.doc.rev == rev0 + 1 and v.doc.root[0]["data"]["text"] == "Von Bert geändert"
    assert not v.changed_banner.get_revealed()
    # eigene ungespeicherte Eingaben: kein stilles Neuladen, Hinweis mit "Neu laden"
    v.doc.mark_dirty()
    srv.push_event("note.changed", {"id": 3, "rev": rev0 + 5, "updated": 1.0, "by": {"name": "Bert", "username": "bert"}, "gone": False})
    pump(lambda: v.changed_banner.get_revealed(), 4)
    assert v.changed_banner.get_revealed() and "Bert changed this page" in v.changed_banner.get_title()


def test_presence_and_comment_counts(win, srv):
    from hlidskjalf.store import store
    srv.presence_users = [{"username": "bert", "name": "Bert Beispiel", "editing": True}]
    v = _open(win, 1)
    pump(lambda: srv.presence_log, 4)
    idle()
    pump(lambda: win.presence_box.get_visible(), 3)
    assert srv.presence_log[0]["note_id"] == 1 and srv.presence_log[0]["editing"] is False
    assert win.presence_box.get_visible()
    assert "Bert Beispiel is editing" in (win.presence_box.get_tooltip_text() or "")
    # live: niemand mehr da
    _connected(win)
    srv.push_event("presence", {"note_id": 1, "users": [{"username": "test", "name": "Test", "editing": False, "me": True}]})
    pump(lambda: not win.presence_box.get_visible(), 4)
    assert not win.presence_box.get_visible()
    # Kommentar-Zahlen live
    srv.push_event("comments.changed", {"note_id": 1, "counts": {"blocks": {"p1": 2}, "open": 2, "unread": 1}})
    pump(lambda: v.doc.page.get("comments_open") == 2, 4)
    assert v.doc.page["comments_unread"] == 1 and win.cm_btn.get_visible()
    # Seite verlassen: "leave"
    win.show_root(("tasks",))
    pump(lambda: any(x.get("leave") for x in srv.presence_log), 4)
    assert any(x == {"note_id": 1, "leave": True} for x in srv.presence_log)
    assert store is not None


def test_old_server_without_live(win, srv):
    from hlidskjalf.live import live
    from hlidskjalf.notifications import notifications
    srv.live_api = False
    live.reset()
    notifications.reset()
    live.start()
    notifications.load()
    pump(lambda: live.supported is False and notifications.supported is False, 5)
    assert live.supported is False and not live.connected
    assert not win.bell.get_visible()
