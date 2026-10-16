"""Saga 2.1 (Welle b) gegen den nachgebauten Server: Profilbilder (laden, Zwischenspeicher, 404, Personen in Chat/Glocke/
Teilen, eigenes Bild mit Zuschnitt), "Anmeldung & Sicherheit" (Stand, Browser), Chat "Neu beginnen" mit Code der
Authenticator-App, Anrufe (Klingeln ueber Live und Push ohne Doppel, Ablehnen, Annehmen im Browser, anderswo angenommen,
verpasste Anrufe im Chat), Benachrichtigung "Verpasster Anruf"."""
import io
import os

from gi.repository import Gtk

import mockv21
from hk_helpers import idle, pump
from hlidskjalf import chatcrypto as C

RED, BLUE = (200, 60, 60), (60, 90, 200)


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label)]


def _ready():
    from hlidskjalf.chat import chat
    chat.refresh()
    pump(lambda: chat.status == "setup", 5)
    chat.adopt_keys(chat.setup(C.recovery_key()))
    pump(lambda: chat.status == "ready" and chat.me and chat.me.get("identity"), 5)
    idle()


# --- Profilbilder ---------------------------------------------------------------------------------------------------
def test_avatar_load_cache_and_404(srv):
    from hlidskjalf import avatars
    from hlidskjalf.config import settings
    avatars.reset()
    mockv21.set_avatar(srv, "anna", mockv21.face(RED))
    url = mockv21.av_url(srv, "anna")
    assert url.startswith("/api/avatar/") and "v=1" in url
    av = avatars.person({"name": "Anna Beispiel", "username": "anna", "avatar": url}, 32)
    assert av.get_text() == "Anna Beispiel" and av.get_show_initials()
    assert pump(lambda: av.get_custom_image() is not None, 4)
    assert srv.avatar_log[-1] == (url.split("?")[0].lstrip("/"), "64")             # 32 px x 2 -> Stufe 64
    hd = [h for m, u, h in srv.headers_log if "/api/avatar/" in u]
    assert hd and ("webp" in hd[-1].get("Accept", "")) == avatars._has_webp()
    n = len(srv.avatar_log)
    av2 = avatars.person({"name": "Anna", "avatar": url}, 32)          # Speicher: kein neuer Abruf
    assert av2.get_custom_image() is not None and len(srv.avatar_log) == n
    avatars.reset()                                                     # Platte: auch ohne Speicher kein Abruf (1 Tag)
    av3 = avatars.person({"name": "Anna", "avatar": url}, 32)
    assert pump(lambda: av3.get_custom_image() is not None, 4) and len(srv.avatar_log) == n
    # neues Bild -> neue Version -> neuer Abruf
    mockv21.set_avatar(srv, "anna", mockv21.face(BLUE))
    av4 = avatars.person({"name": "Anna", "avatar": mockv21.av_url(srv, "anna")}, 32)
    assert pump(lambda: av4.get_custom_image() is not None, 4) and len(srv.avatar_log) == n + 1
    # 404: Initialen, nicht wiederholen; fremde/ungueltige Adressen gar nicht laden
    av5 = avatars.person({"name": "Bert", "avatar": "/api/avatar/kbert?v=1"}, 32)
    pump(lambda: len(srv.avatar_log) > n + 1, 3)
    pump(None, 0.2)
    assert av5.get_custom_image() is None and av5.get_show_initials()
    m = len(srv.avatar_log)
    avatars.person({"name": "Bert", "avatar": "/api/avatar/kbert?v=1"}, 32)
    pump(None, 0.3)
    assert len(srv.avatar_log) == m
    for bad in ("https://gravatar.com/avatar/x", "//evil/x", None, 5):
        a = avatars.person({"name": "X", "avatar": bad}, 32)
        assert a.get_custom_image() is None
    pump(None, 0.2)
    assert len(srv.avatar_log) == m and settings.server


def test_avatars_in_chat_notifications_presence(win, srv):
    from hlidskjalf import avatars
    from hlidskjalf.chat import chat
    from hlidskjalf.notifications import NotificationList, notifications
    avatars.reset()
    mockv21.set_avatar(srv, "anna", mockv21.face(RED))
    _ready()
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    assert chat.peer(chat.chat(cid))["avatar"] == mockv21.av_url(srv, "anna")
    win.show_root(("chats",))
    pump(None, 0.3)
    avs = [w for w in _walk(win.current.list) if w.__class__.__name__ == "Avatar"]
    assert avs and pump(lambda: any(a.get_custom_image() is not None for a in avs), 4)
    # Glocke: Bild der handelnden Person
    srv.add_notif("comment.new", actor={"name": "Anna Beispiel", "username": "anna"})
    notifications.load()
    pump(lambda: notifications.items, 4)
    assert notifications.items[0]["actor"]["avatar"]
    nl = NotificationList(win)
    navs = [w for w in _walk(nl) if w.__class__.__name__ == "Avatar"]
    assert pump(lambda: any(a.get_custom_image() is not None for a in navs), 4)
    # Anwesenheit
    win.set_presence([{"username": "anna", "name": "Anna Beispiel", "avatar": mockv21.av_url(srv, "anna")}])
    pavs = [w for w in _walk(win.presence_box) if w.__class__.__name__ == "Avatar"]
    assert len(pavs) == 1 and pump(lambda: pavs[0].get_custom_image() is not None, 3)
    # eigenes Konto in der Seitenleiste (ohne eigenes Bild: Initialen)
    win.load_account()
    assert pump(lambda: win.acct_btn.get_visible(), 3) and win.acct_av.get_text() == "Test"


def test_share_rows_carry_avatar():
    from hlidskjalf.sharing import avatar, hit_rows, share_rows
    st = {"shares": [{"type": "user", "target": "anna", "name": "Anna", "role": "edit", "avatar": "/api/avatar/ka?v=1"}]}
    assert share_rows(st)[0]["avatar"] == "/api/avatar/ka?v=1"
    assert hit_rows({"users": [{"username": "bert", "name": "Bert", "avatar": "/api/avatar/kb?v=2"}]}, st, "view")[0]["avatar"] == \
        "/api/avatar/kb?v=2"
    g = avatar("group", "team", url="/api/avatar/x?v=1")                # Gruppen: Symbol, nie ein Bild
    assert not g.get_show_initials() and g.get_custom_image() is None


def test_prepare_exif_and_crop(tmp_path=None):
    from PIL import Image

    from hlidskjalf.profile import crop_bytes, prepare
    im = Image.new("RGB", (300, 200), RED)
    exif = Image.Exif()
    exif[0x0112] = 6                                                    # 90 Grad drehen
    buf = io.BytesIO()
    im.save(buf, "JPEG", exif=exif)
    p = prepare(buf.getvalue())
    assert p.size == (200, 300) and p.mode == "RGB"
    data, ctype = crop_bytes(p, (0, 50, 200))
    out = Image.open(io.BytesIO(data))
    assert ctype == "image/jpeg" and out.size == (512, 512)              # kleiner Ausschnitt -> 512
    big = Image.new("RGBA", (3000, 2000), (0, 0, 0, 0))
    data, ctype = crop_bytes(big, (2000, 0, 2000))                       # ragt hinaus -> eingepasst; transparent -> PNG
    out = Image.open(io.BytesIO(data))
    assert ctype == "image/png" and out.size == (1024, 1024)
    try:
        prepare(b"kein bild")
        raise AssertionError("haette scheitern muessen")
    except ValueError:
        pass


def test_avatar_group_upload_remove(win, srv):
    from PIL import Image

    from hlidskjalf.profile import AvatarGroup
    from hlidskjalf.settings import SettingsDialog
    win.load_account()
    pump(lambda: win.account_name(), 3)
    dlg = SettingsDialog(win)
    dlg.present(win)
    g = AvatarGroup(dlg)
    assert pump(lambda: g.get_visible(), 4)
    assert g.src.get_label() == "No picture – your initials are shown." and not g.rm.get_visible()
    assert g.grav.get_visible() and g.grav.get_active()
    # Hochladen (ohne Dateiauswahl): Zuschnitt -> PUT Rohinhalt
    im = Image.new("RGB", (800, 600), BLUE)

    class CD:
        closed = False

        def close(self):
            CD.closed = True

        def failed(self, t):
            raise AssertionError(t)
    g._upload(CD(), im, (100, 0, 600))
    assert pump(lambda: CD.closed, 4)
    assert len(srv.avatar_uploads) == 1 and Image.open(io.BytesIO(srv.avatar_uploads[0])).size == (600, 600)
    assert pump(lambda: g.rm.get_visible(), 3) and g.src.get_label() == "Shown: your own picture"
    assert pump(lambda: g.av.get_custom_image() is not None, 4)
    assert pump(lambda: (win.me or {}).get("avatar"), 3)                  # Kopfleiste folgt
    # Gravatar aus -> PATCH; Fehler -> Schalter springt zurueck
    g.grav.set_active(False)
    pump(lambda: srv.my_avatar["gravatar"] is False, 3)
    srv.fail["PATCH api/account/avatar"] = 500
    g.pub.set_active(True)
    assert pump(lambda: not g.pub.get_active(), 3) and g.err.get_visible()
    srv.fail.clear()
    g._remove()
    assert pump(lambda: not g.rm.get_visible(), 3) and "test" not in srv.avatars
    dlg.force_close()


def test_avatar_group_hidden_on_old_server(win, srv):
    from hlidskjalf.profile import AvatarGroup
    from hlidskjalf.security import SecurityGroup
    from hlidskjalf.settings import SettingsDialog
    srv.avatar_api = False
    dlg = SettingsDialog(win)
    dlg.present(win)
    g, s = AvatarGroup(dlg), SecurityGroup(dlg)
    pump(None, 0.5)
    assert not g.get_visible() and not s.get_visible()
    dlg.force_close()


# --- Anmeldung & Sicherheit ----------------------------------------------------------------------------------------
def test_security_status_lines():
    from hlidskjalf.security import status_lines
    t, sub, w = status_lines({"supported": False})
    assert "SSO" in t and w is None
    t, sub, w = status_lines({"supported": True, "enabled": False, "required": True, "passkeys": [], "totp": False})
    assert t == "Second factor is off." and "admin" in w and sub == "No second factor set up."
    t, sub, w = status_lines({"supported": True, "enabled": True, "passkeys": [{"id": 1}, {"id": 2}], "totp": True, "recovery_left": 2})
    assert t.startswith("✓") and sub == "2 passkeys · Authenticator app · 2 recovery codes left" and "recovery" in w
    t, sub, w = status_lines({"supported": True, "enabled": True, "passkeys": [], "totp": True, "recovery_left": 9})
    assert w is None and sub == "Authenticator app · 9 recovery codes left"


def test_security_group_and_browser(win, srv, monkeypatch):
    from hlidskjalf.security import SecurityGroup
    from hlidskjalf.settings import SettingsDialog
    srv.security = {"supported": True, "enabled": True, "required": False, "passkeys": [{"id": 1, "name": "Laptop"}], "totp": True,
                    "recovery_left": 8, "methods": ["password", "passkey", "totp"]}
    opened = []
    monkeypatch.setattr(win, "open_uri", lambda u: opened.append(u))
    dlg = SettingsDialog(win)
    dlg.present(win)
    g = SecurityGroup(dlg)
    assert pump(lambda: g.get_visible(), 4)
    assert g.state.get_title().startswith("✓") and "1 passkey" in g.state.get_subtitle() and not g.warn.get_visible()
    g.manage.emit("activated")
    assert opened == ["https://saga.test/security"]
    srv.security = dict(srv.security, enabled=False, required=True, passkeys=[], totp=False)
    g.load()
    assert pump(lambda: g.warn.get_visible(), 3)
    dlg.force_close()


def test_login_page_mentions_second_factor(win):
    win.stack.set_visible_child_name("login")
    assert any("two-factor" in (t or "") for t in _labels(win.stack.get_child_by_name("login")))


# --- Chat: Neu beginnen mit Code -----------------------------------------------------------------------------------------
def test_start_over_with_totp_code(win, srv):
    from hlidskjalf.chat import ChatError, chat
    from hlidskjalf.chatview import SetupDialog
    _ready()
    srv.reauth_need = "password"
    srv.reauth_methods = ["password", "totp"]
    chat.refresh()
    pump(lambda: (chat.me or {}).get("reauth_methods") == ["password", "totp"], 4)
    d = SetupDialog(win, reset=True)
    d.present(win)
    assert d.pw_box.get_visible() and "authenticator" in d.pw.get_title()
    d.pw.set_text("246 810")
    assert d.secret() == (None, "246810")
    d.pw.set_text("geheim123")
    assert d.secret() == ("geheim123", None)
    # falscher Code -> 403 mit reauth_methods; richtiger Code -> neu begonnen
    try:
        chat.setup(C.recovery_key(), None, True, "000000")
        raise AssertionError("haette scheitern muessen")
    except ChatError as e:
        assert e.code == 403 and e.data.get("reauth_methods") == ["password", "totp"]
    old = srv.idents["test"]["ident_pub"]
    k = chat.setup(C.recovery_key(), None, True, "246810")
    assert srv.idents["test"]["ident_pub"] == C.b64(k["ident_pk"]) != old
    # nur Passkey: Hinweis + Browser statt Eingabefeld
    d._need("password", ["passkey"])
    assert not d.pw_box.get_visible() and d.passkey_note.get_visible() and d.passkey_btn.get_visible()
    d._need("login", [])
    assert d.relogin.get_visible() and not d.pw_box.get_visible()
    d.force_close()


# --- Anrufe ---------------------------------------------------------------------------------------------------------------
class _Notes:
    def __init__(self, app, monkeypatch):
        self.sent, self.gone = [], []
        monkeypatch.setattr(app, "send_notification", lambda i, n: self.sent.append((i, n)))
        monkeypatch.setattr(app, "withdraw_notification", lambda i: self.gone.append(i))


def _calls_setup(win, srv, app, monkeypatch):
    from hlidskjalf import calls as M
    os.environ["HK_NO_RING"] = "1"
    M.calls.reset()
    M.calls.app = app
    notes = _Notes(app, monkeypatch)
    opened = []
    monkeypatch.setattr(M, "open_in_browser", lambda u, p=None: opened.append(u))
    _ready()
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    return M, notes, opened, cid


def test_call_rings_once_live_and_push(win, srv, app, monkeypatch):
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    call = mockv21.ring(srv, "anna", cid, "video", push=False)
    ev = {"call_id": call, "chat_id": cid, "media": "video", "from": {"username": "anna", "name": "Anna Beispiel"}}
    win._live_event("call.ring", ev)
    win._live_event("call.ring", ev)                                       # doppelt (nachgeholt)
    M.calls.on_push({"kind": "call", "call": call, "chat": cid, "media": "video"})   # Push dazu
    assert pump(lambda: M.calls.ringing is not None, 4)
    pump(None, 0.3)
    assert srv.call_gets.count(call) == 1
    assert [i for i, n in notes.sent] == ["call-" + call]
    assert win.callbar.get_reveal_child() and "Anna Beispiel" in win.callbar.title.get_label()
    assert win.callbar.acc_v.get_visible()                                 # Video: "Mit Video annehmen" + "Nur Ton"
    # Annehmen -> Browser mit #call-<id>-video, Klingeln aus, Mitteilung weg
    win.callbar.acc_v.emit("clicked")
    assert opened == [f"https://saga.test/chats#call-{call}-video"]
    assert M.calls.ringing is None and not win.callbar.get_reveal_child() and notes.gone == ["call-" + call]
    # spaeter noch einmal (Last-Event-ID, verspaeteter Push): klingelt nicht wieder
    win._live_event("call.ring", ev)
    M.calls.on_push({"kind": "call", "call": call})
    pump(None, 0.3)
    assert M.calls.ringing is None and len(notes.sent) == 1


def test_call_decline_and_answered_elsewhere(win, srv, app, monkeypatch):
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    c1 = mockv21.ring(srv, "anna", cid, "audio", push=False)
    M.calls.on_live("call.ring", {"call_id": c1})
    assert pump(lambda: M.calls.ringing is not None, 4)
    assert not win.callbar.acc_v.get_visible() and win.callbar.acc_a.get_child().get_label() == "Answer"
    win.callbar.dec.emit("clicked")
    assert pump(lambda: srv.declined == [(c1, "declined")], 3) and M.calls.ringing is None
    # anderswo angenommen: Leiste weg, Hinweis
    c2 = mockv21.ring(srv, "anna", cid, "audio", push=False)
    M.calls.on_live("call.ring", {"call_id": c2})
    assert pump(lambda: M.calls.ringing is not None, 4)
    toasts = []
    monkeypatch.setattr(win, "toast", lambda t: toasts.append(t))
    M.calls.on_live("call.state", {"call_id": c2, "state": "active", "device": "b" * 32})
    assert M.calls.ringing is None and toasts == ["The call was answered on another device."]
    # schon beendet, bevor geprueft wurde: kein Klingeln
    c3 = mockv21.ring(srv, "anna", cid, "audio", push=False)
    srv.calls[c3]["state"] = "ended"
    M.calls.on_live("call.ring", {"call_id": c3})
    pump(None, 0.4)
    assert M.calls.ringing is None and c3 in M.calls.done
    # Benachrichtigung-Aktionen (Knoepfe der Desktop-Mitteilung)
    c4 = mockv21.ring(srv, "anna", cid, "audio", push=False)
    M.calls.on_live("call.ring", {"call_id": c4})
    assert pump(lambda: M.calls.ringing is not None, 4)
    from gi.repository import GLib
    app.activate_action("call-accept", GLib.Variant("s", c4))
    assert opened[-1] == f"https://saga.test/chats#call-{c4}-audio" and M.calls.ringing is None


def test_call_active_on_start_and_push_fallback(win, srv, app, monkeypatch):
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    c1 = mockv21.ring(srv, "anna", cid, "audio", push=False)
    M.calls.check_active()                                                 # App startet, waehrend es klingelt
    assert pump(lambda: M.calls.ringing is not None and M.calls.ringing["id"] == c1, 4)
    M.calls.decline(c1)
    pump(lambda: srv.declined, 3)
    # Pruefen scheitert (offline): wenigstens die Mitteilung des Servers
    srv.fail["GET api/calls/" + "f" * 32] = 0
    M.calls.on_push({"kind": "call", "call": "f" * 32, "title": "Saga", "body": "Eingehender Anruf", "url": "/chats#call-" + "f" * 32})
    assert pump(lambda: any(i == "call-" + "f" * 32 for i, n in notes.sent), 4)
    assert M.calls.ringing is None


def test_call_button_and_entries_in_chat(win, srv, app, monkeypatch):
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    from hlidskjalf.chat import chat
    M.calls.load_config()
    pump(lambda: M.calls.enabled, 3)
    srv.peer_send(cid, "anna", "Hallo")
    mockv21.call_entry(srv, cid, "missed", "audio", frm="anna", to="test")
    mockv21.call_entry(srv, cid, "ended", "video", frm="test", to="anna", dur=125)
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    win.show_route(("chat", cid))
    assert pump(lambda: chat.cache.get(cid, {}).get("loaded"), 4)
    msgs = chat.cache[cid]["msgs"]
    kinds = [m["state"] for m in msgs]
    assert kinds.count("call") == 2 and "ok" in kinds
    pump(None, 0.3)
    labels = _labels(win.current.conv.msgs)
    assert "Missed call" in labels and "Video call · 2:05" in labels
    assert win.call_btn.get_visible()
    win.call_btn.emit("clicked")
    assert opened == [f"https://saga.test/chats#c{cid}"]
    # Anrufe am Server aus: kein Knopf
    srv.calls_cfg["enabled"] = False
    M.calls.load_config(lambda on: win.update_header())
    assert pump(lambda: not win.call_btn.get_visible(), 3)


def test_entry_texts_and_missed_notification():
    from hlidskjalf.calls import duration, entry_missed, entry_text, parse_entry
    from hlidskjalf.notifications import item_text, kind_label
    k = parse_entry({"kind": "call", "body_ct": '{"v":1,"type":"call","call":"x","media":"audio","status":"busy","dur":null,'
                                                '"from":"anna","to":"test"}'})
    assert entry_text(k, "test") == "Missed call (you were on another call)" and entry_missed(k, "test")
    assert entry_text(k, "anna") == "Busy" and not entry_missed(k, "anna")
    assert entry_text(dict(k, status="declined", media="video"), "test") == "Video call declined"
    assert entry_text(dict(k, status="missed"), "anna") == "Call – not answered"
    assert entry_text(dict(k, status="failed"), "anna") == "Call – no connection"
    assert duration(3725) == "1:02:05" and duration(59) == "0:59"
    assert parse_entry({"kind": "call", "body_ct": "kaputt"}) is None and parse_entry({"kind": "msg"}) is None
    assert item_text({"kind": "call.missed", "actor": {"name": "Anna"}}) == "Missed call from Anna"
    assert kind_label("call.missed") == "Missed calls"


def test_spec_recommends_ringtone_and_ships_modules():
    spec = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "hlidskjalf.spec")
    text = open(spec, encoding="utf-8").read()
    assert "Recommends:     gstreamer1-plugins-good" in text           # WAV-Klingelton (wavparse); sonst still
    for m in ("avatars", "profile", "security", "calls"):
        assert f"hlidskjalf/{m}.py" in text


def test_ringer_starts_and_stops_without_crash(monkeypatch):
    """Klingelton ueber GStreamer (playbin, nur Ton): startet ohne Absturz (auch ohne Audio-Geraet) und laesst sich stoppen"""
    from hlidskjalf import calls as M
    monkeypatch.delenv("HK_NO_RING", raising=False)
    monkeypatch.setattr(M, "_quiet", lambda: False)
    r = M.Ringer()
    r.start()
    pump(None, 0.5)
    r.stop()
    assert r.pipe is None
    import wave
    with wave.open(M._tone_file()) as w:
        assert w.getnchannels() == 1 and w.getframerate() == 22050 and w.getnframes() > 22050
