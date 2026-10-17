"""Regressionstests Bughunt Runde 2 (Linux): Start ohne Netz, Vorschaubilder je Nachricht (file_ids_per_msg),
Klingel-Leiste (Name lesbar), Profilbilder der Gegenueber, HEIC nur mit Dekoder, Bildblock-Hoehe, Mindestbreite,
Gravatar ohne E-Mail, Immich ohne Namen, Push-Mitteilung ohne Netz."""
import io
import time

from gi.repository import GLib, Gtk

import mockv21
from hk_helpers import idle, pump

from test_v21b import _calls_setup, _ready, _walk
from test_v21c import _conv, _img


# --- [S2] Start ohne Netz -------------------------------------------------------------------------------------------
def test_offline_start_catches_up(app, srv):
    from hlidskjalf.assistant import ai
    from hlidskjalf.calls import calls
    from hlidskjalf.notifications import notifications
    from hlidskjalf.window import MainWindow
    calls.reset()
    srv.ai_status = {"enabled": True, "notice": "x"}
    off = ["GET api/ai/status", "GET api/calls/config", "GET api/account", "GET api/notifications"]
    for k in off:
        srv.fail[k] = 0                                  # Server (noch) nicht erreichbar
    w = MainWindow(app)
    w.present()
    try:
        pump(lambda: all(any(m == "GET" and k.split()[1] in u for m, u, b, c in srv.log) for k in off), 4)
        pump(None, 0.3)
        assert not ai.known and calls.config is None and not getattr(w, "me", None) and not notifications.loaded
        for k in off:
            srv.fail.pop(k)
        # gleich nach dem Start: erst nach den Anfragen vom Sitzungsbeginn nachholen (keine Doppel)
        n0 = len(srv.log)
        w._live_event("hello", {})
        pump(None, 0.2)
        assert not any("api/ai/status" in u for m, u, b, c in srv.log[n0:]) and w._catch_up_soon
        GLib.source_remove(w._catch_up_soon)
        w._catch_up_soon = 0
        w._session_t -= 60                               # spaeter: Live-Kanal steht -> alles Fehlende laden
        w._live_event("hello", {})
        assert pump(lambda: ai.known and ai.enabled and calls.config is not None and (w.me or {}).get("name")
                    and notifications.loaded, 5)
        assert w.acct_btn.get_visible()
        # schon alles da: nichts doppelt
        n1 = len(srv.log)
        w._session_catch_up()
        pump(None, 0.3)
        assert not [u for m, u, b, c in srv.log[n1:] if "api/ai/status" in u or "api/calls/config" in u or u.endswith("api/account")]
    finally:
        w._close_ok = True
        w.destroy()
        pump(None, 0.1)


# --- [S3] Vorschaubilder: file_ids_per_msg ----------------------------------------------------------------------------
def test_thumbs_use_file_ids_per_msg(win, srv):
    from hlidskjalf.chat import chat
    cid, conv = _conv(win, srv)
    big = _img(900, 600, fmt="JPEG")
    files = [(f"b{i}.jpg", "image/jpeg", big) for i in range(10)]
    chat.limits = dict(chat.limits or {}, files_per_msg=10, file_ids_per_msg=20)
    out, ids = chat.upload_attachments(cid, files)
    assert len(ids) == 20 and all("th" in e for e in out)
    # aelterer Server ohne file_ids_per_msg: Rueckfall auf files_per_msg
    chat.limits.pop("file_ids_per_msg")
    out, ids = chat.upload_attachments(cid, files[:6])
    assert len(ids) == 10 and sum("th" in e for e in out) == 4


# --- [S3] Klingel-Leiste: Name lesbar -----------------------------------------------------------------------------
def test_callbar_shows_caller_name(win, srv, app, monkeypatch):
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    srv.names["anna"] = "Annabelle Beispielname"
    call = mockv21.ring(srv, "anna", cid, "video", push=False)
    M.calls.on_live("call.ring", {"call_id": call})
    assert pump(lambda: M.calls.ringing is not None, 4)
    bar = win.callbar
    assert bar.title.get_label() == "Annabelle Beispielname" and bar.kind.get_label() == "Incoming video call"
    assert bar.acc_v.get_child().get_label() == "Answer" and bar.acc_v.get_tooltip_text() == "Answer with video"
    assert bar.acc_a.get_child().get_label() == "Audio only" and bar.acc_a.get_tooltip_text() == "Answer with audio only"
    # schmal (nur Symbole): Name bekommt trotzdem Platz
    bar.set_compact(True)
    assert bar.acc_v.get_child().get_label() == "" and bar.acc_a.get_tooltip_text() == "Answer with audio only"
    box = bar.get_child()
    mw, nw, _a, _b = box.measure(Gtk.Orientation.HORIZONTAL, -1)
    assert mw <= 340                                  # passt ins kleinste Fenster (360 px)
    tm = bar.title.measure(Gtk.Orientation.HORIZONTAL, -1)[0]
    lay = bar.title.create_pango_layout("Annabelle B")
    assert tm >= lay.get_pixel_size()[0] - 2          # gut ein Dutzend Zeichen des Namens sind immer sichtbar
    bar.set_compact(False)
    assert bar.acc_v.get_child().get_label() == "Answer"
    M.calls.decline(call)


# --- [S4] Push-Mitteilung ohne Netz: Knoepfe, Klick nimmt nicht an ----------------------------------------------------
def test_push_fallback_has_buttons(win, srv, app, monkeypatch):
    from hlidskjalf import calls as M

    class FakeN:
        made = []

        def __init__(self, title):
            self.title, self.buttons, self.default = title, [], None
            FakeN.made.append(self)

        @classmethod
        def new(cls, title):
            return cls(title)

        def set_body(self, b):
            self.body = b

        def set_priority(self, p):
            pass

        def set_category(self, c):
            self.category = c

        def set_default_action_and_target(self, a, v):
            self.default = (a, v.get_string())

        def add_button_with_target(self, label, a, v):
            self.buttons.append((label, a, v.get_string()))

    class FakeGio:
        Notification = FakeN
        NotificationPriority = M.Gio.NotificationPriority
        Application = M.Gio.Application
    sent = []
    monkeypatch.setattr(M, "Gio", FakeGio)
    monkeypatch.setattr(app, "send_notification", lambda i, n: sent.append((i, n)))
    M.calls.app = app
    cid = "e" * 32
    M.calls._push[cid] = {"title": "Saga", "body": "Videoanruf", "media": "video", "url": "/chats#call-" + cid + "-video"}
    M.calls._push_fallback(cid)
    n = FakeN.made[-1]
    assert sent and n.default == ("app.call-show", cid) and n.category == "call.incoming"
    assert [(a, t) for _l, a, t in n.buttons] == [("app.call-decline", cid), ("app.call-accept", cid)]
    opened = []
    monkeypatch.setattr(M, "open_in_browser", lambda u, p=None: opened.append(u))
    M.calls.accept(cid)
    assert opened == [f"https://saga.test/chats#call-{cid}-video"]      # Art aus der Push-Mitteilung


# --- [S4] Profilbild des Gegenuebers ----------------------------------------------------------------------------------
def test_peer_avatar_change_revalidates(win, srv, monkeypatch):
    from hlidskjalf import avatars
    from hlidskjalf.chat import chat
    avatars.reset()
    mockv21.set_avatar(srv, "anna", mockv21.face((200, 60, 60)))
    _ready()
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    win.show_root(("chats",))
    assert pump(lambda: chat.chat(cid) is not None, 4)
    v1 = chat.peer(chat.chat(cid))["avatar"]
    mockv21.set_avatar(srv, "anna", mockv21.face((60, 90, 200)))         # anderswo geaendert, kein Live-Ereignis
    chat.revalidate(60)
    pump(None, 0.3)
    assert chat.peer(chat.chat(cid))["avatar"] == v1                     # frisch geladen: nicht gleich wieder
    chat._chats_at -= 30
    win.show_root(("today",))
    win.show_root(("chats",))                                            # Chats wieder geoeffnet
    assert pump(lambda: chat.peer(chat.chat(cid))["avatar"] == mockv21.av_url(srv, "anna") != v1, 4)
    avs = [w for w in _walk(win.current.list) if w.__class__.__name__ == "Avatar"]
    assert any(getattr(a, "_hk_avatar", None) == mockv21.av_url(srv, "anna") for a in avs)
    srv.avatars.pop("anna")                                              # entfernt -> Initialen
    chat._chats_at -= 300
    monkeypatch.setattr(win, "is_active", lambda: True)
    win._tick()                                                          # Takt, waehrend die Chats offen sind
    assert pump(lambda: not chat.peer(chat.chat(cid)).get("avatar"), 4)
