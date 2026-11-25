"""2.6 (Bugs-DB 2029-8) Eigener Status in der Linux-App gegen den nachgebauten Server (tests/mockstatus.py): Regeln
(Anzeige, Body, Sammelmeldung), Stand mit ver und 409, Erreichbarkeit/Ausnahmen, Status der Partner im Presence-Abo (auch
mit eigenem Online-Status aus), Live chat.status, Rueckfrage vor dem Anruf, Anklopfen (waiting1, kein Klingeln, Ablehnen =
besetzt, Beenden und annehmen erst nach bestaetigtem Ende), stille Leiste (call.quiet), Eintrag "Verpasster Anruf (Nicht stören)"."""
import json
import os
import time

from gi.repository import Adw

import mockv21
from hk_helpers import idle, pump
from hlidskjalf import calls as CL
from hlidskjalf import chatcrypto as C
from hlidskjalf import chatstatus as S

NOW = 1795600000.0


# --- Regeln (ohne Netz) ---------------------------------------------------------------------------------------------------
def test_rules_view_shown_peer_text():
    v = {"state": "dnd", "until": NOW + 3600, "text": "Im Termin", "source": "manual", "ver": 3, "effective": "dnd", "why": "manual"}
    assert S.view_ok(v) and not S.view_ok(dict(v, state="away")) and not S.view_ok(dict(v, ver=True))
    assert S.take_view(None, v) and S.take_view(v, dict(v, ver=3)) and not S.take_view(v, dict(v, ver=2))
    s = S.shown(v, now=NOW)
    assert s["state"] == "dnd" and s["hint"].startswith("until ")
    q = dict(v, state="available", until=None, effective="dnd", why="quiet")
    assert S.shown(q)["hint"] == "Quiet hours – counts as Do not disturb"
    assert S.shown(q, "07:00")["hint"] == "Quiet hours until 07:00 – counts as Do not disturb"
    c = dict(v, state="busy", until=None, effective="in_call", why="call")
    assert S.shown(c) == {"state": "in_call", "hint": "underneath: Busy"}
    f = dict(v, until=None, source="focus")
    assert S.shown(f)["hint"] == "from the device (Focus)"
    # Status anderer: Verfuegbar/unbekannt zeigt nichts, Text gekuerzt, in_call ohne Ende
    assert S.peer_status({"s": "available"}) is None and S.peer_status({"s": "away"}) is None and S.peer_status(None) is None
    assert S.peer_status({"s": "in_call", "until": NOW}) == {"s": "in_call"}
    assert S.peer_text({"s": "busy"}) == "Busy"
    assert S.peer_text({"s": "dnd", "text": "x" * 200}, True) == "Do not disturb · " + "x" * 80
    assert S.peer_text({"s": "dnd", "until": NOW + 60, "text": "T"}, False, NOW).startswith("Do not disturb until ")


def test_rules_body_digest_features():
    v = {"state": "busy", "until": NOW + 7200, "ver": 7}
    b = S.status_body("dnd", "h1", "  Im   Termin ", v, NOW)
    assert b == {"state": "dnd", "text": "Im Termin", "source": "manual", "dur": 3600, "ver": 7}
    assert S.status_body("dnd", "keep", "", v, NOW)["until"] == NOW + 7200
    assert "dur" not in S.status_body("dnd", "off", "", v, NOW) and "until" not in S.status_body("dnd", "off", "", v, NOW)
    t7 = S.status_body("busy", "t7", "", v, NOW)["until"]
    assert time.localtime(t7).tm_hour == 7 and t7 > NOW
    a = S.status_body("available", "h1", "", v, NOW)
    assert "dur" not in a and a["text"] is None
    assert len(S.status_body("busy", "off", "y" * 120, None)["text"]) == 80 and "ver" not in S.status_body("busy", "off", "", None)
    assert S.keep_until(v, NOW) and not S.keep_until(dict(v, until=NOW + 30), NOW) and not S.keep_until(dict(v, state="available"), NOW)
    # Sammelmeldung: nur Zahlen, Push-Form (digest) und Live-Form
    assert S.digest_text({"messages": 4, "calls": 1, "other": 0}) == "While “Do not disturb” was on: 4 messages, 1 missed call"
    assert S.digest_text({"digest": {"messages": 0, "calls": 2, "other": 1}, "body": "x"}) == \
        "While “Do not disturb” was on: 2 missed calls, 1 other notification"
    assert S.digest_text({"messages": 0}) == "" and S.digest_text(None) == ""
    # Faehigkeit und Abo: mit userstatus1 auch bei eigenem Online-Status aus
    me = {"features": ["online", "userstatus1"], "privacy": {"online": False}}
    dm = {"kind": "dm", "peer": {"username": "anna"}}
    assert S.has_userstatus(me) and S.presence_on(me) and S.presence_user(me, dm) == "anna"
    assert not S.presence_on({"features": ["online"], "privacy": {"online": False}})
    assert S.exc_same({"type": "user", "ref": "~a"}, {"type": "user", "ref": "~a", "name": "A"})
    assert S.exc_out({"type": "user", "ref": "~a", "name": "A"}) == {"type": "user", "ref": "~a"}


def test_rules_calls_caps_mode_entry():
    cfg = {"enabled": True, "features": ["reneg", "multi", "waiting1"]}
    assert CL.device_caps(cfg) == ["multi", "horn_ring", "waiting1"]
    assert CL.device_caps(dict(cfg, features=["reneg", "multi"])) == ["multi", "horn_ring"]   # Server ohne Anklopfen
    assert CL.device_caps(dict(cfg, enabled=False)) == []
    base = {"id": "a" * 32, "state": "ringing", "role": "callee"}
    assert CL.ring_mode(base) == "ring" and CL.ring_mode(dict(base, waiting=True)) == "wait"
    assert CL.ring_mode(dict(base, quiet=True)) == "quiet" and CL.ring_mode(dict(base, role="caller")) is None
    horn = {"id": "b" * 32, "kind": "group", "dm": True, "state": "ringing", "invite": "ringing", "quiet": True}
    assert CL.ring_mode(horn) == "quiet"
    grp = {"id": "c" * 32, "kind": "group", "state": "active", "invite": "ringing", "quiet": True, "silent": True}
    assert CL.ring_mode(grp) == "ring"                       # Gruppe: stille Einladung wie bisher (Leiste ohne Ton)
    lst = [{"id": "d" * 32, "state": "active", "role": "caller"}, {"id": "e" * 32, "state": "ringing", "role": "callee"},
           {"id": "f" * 32, "state": "active", "kind": "group"}, {"id": "1" * 32, "state": "active", "kind": "group", "mine": ["x"]}]
    assert [x["id"] for x in CL.own_running(lst)] == ["d" * 32, "1" * 32]
    assert CL.own_running(lst, "d" * 32)[0]["id"] == "1" * 32
    w = CL.hold_view({"mode": "wait", "peer": {"name": "Bruno"}, "run": "d" * 32})
    assert w["title"] == "Bruno is calling (call waiting)" and w["decline"] and w["take"] and not w["accept"]
    q = CL.hold_view({"mode": "quiet", "peer": {"name": "Alex"}, "why": "dnd", "elsewhere": False})
    assert q["label"] == "Call from Alex – silent (Do not disturb)" and q["accept"] and q["hide"] and not q["decline"]
    assert not CL.hold_view({"mode": "quiet", "peer": {"name": "Alex"}, "elsewhere": True})["accept"]
    assert "quiet hours" in CL.hold_view({"mode": "quiet", "peer": {"name": "A"}, "why": "quiet", "elsewhere": False})["sub"]
    # Eintrag im Chat: hold nur fuer die angerufene Person
    m = {"kind": "call", "body_ct": json.dumps({"type": "call", "status": "missed", "from": "anna", "to": "test", "hold": "dnd"})}
    k = CL.parse_entry(m)
    assert k["hold"] == "dnd" and CL.entry_text(k, "test") == "Missed call (Do not disturb)"
    assert CL.entry_text(dict(k, media="video"), "test") == "Missed video call (Do not disturb)"
    assert CL.entry_text(dict(k, hold="knock"), "test") == "Missed call (you were on another call)"
    assert CL.entry_text(k, "anna") == "Call – not answered"           # anrufende Seite: wie bisher
    assert CL.parse_entry(dict(m, body_ct=json.dumps({"type": "call", "status": "missed", "hold": "x"})))["hold"] is None


# --- gegen den Schein-Server ----------------------------------------------------------------------------------------------
def _ready(srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.userstatus import ustatus
    srv.ustatus_api = True
    ustatus.reset()
    chat._pres_src.clear()
    chat.refresh()
    pump(lambda: chat.status == "setup", 5)
    chat.adopt_keys(chat.setup(C.recovery_key()))
    pump(lambda: chat.status == "ready" and chat.me and chat.me.get("identity"), 5)
    idle()


def test_own_status_ver_409_prefs(win, srv):
    from hlidskjalf.userstatus import ustatus
    _ready(srv)
    ustatus.load()
    assert pump(lambda: ustatus.ok, 4) and ustatus.view["state"] == "available" and not win.acct_dot
    got = []
    ustatus.set(S.status_body("dnd", "h1", "Im Kundentermin", ustatus.view), lambda v, c: got.append(c), got.append)
    assert pump(lambda: got, 4) and got == [False]
    put = srv.ustatus_log[-1][2]
    assert put["ver"] == 1 and put["dur"] == 3600 and put["source"] == "manual" and put["text"] == "Im Kundentermin"
    assert ustatus.view["state"] == "dnd" and ustatus.dnd()
    pump(None, 0.2)
    assert win.acct_dot is not None and "Do not disturb" in win.acct_btn.get_tooltip_text()
    # anderes Geraet aendert: altes ver -> 409 -> Ansicht still uebernommen
    srv.uview.update(state="busy", until=None, text=None)
    srv.uview["ver"] += 5
    got.clear()
    ustatus.set({"state": "available", "ver": 1}, lambda v, c: got.append(c), got.append)
    assert pump(lambda: got, 4) and got == [True] and ustatus.view["state"] == "busy" and ustatus.view["ver"] == srv.uview["ver"]
    # Live: aelterer Stand wird ignoriert, neuerer uebernommen
    ustatus.on_live("status.changed", dict(ustatus.view, state="dnd", ver=1))
    assert ustatus.view["state"] == "busy"
    ustatus.on_live("status.changed", dict(ustatus.view, state="dnd", ver=ustatus.view["ver"] + 1))
    assert ustatus.view["state"] == "dnd"
    # Erreichbarkeit: nur genannte Einstellungen, Ausnahmen ersetzen die Liste
    got.clear()
    ustatus.prefs({"in_call": "busy", "exceptions": [{"type": "group", "name": "team"}]}, lambda v, c: got.append(c), got.append)
    assert pump(lambda: got, 4)
    assert srv.ustatus_log[-1][2] == {"in_call": "busy", "exceptions": [{"type": "group", "name": "team"}]}
    assert ustatus.view["prefs"]["in_call"] == "busy" and ustatus.view["exceptions"] == [{"type": "group", "name": "team"}]
    # Sammelmeldung -> Toast im Fenster (nur Zahlen)
    toasts = []
    h = ustatus.connect("digest", lambda _u, t: toasts.append(t))
    win._live_event("status.digest", {"messages": 2, "calls": 0, "other": 0, "since": 1, "until": 2})
    ustatus.disconnect(h)
    assert toasts == ["While “Do not disturb” was on: 2 messages"]


def test_status_dialog_and_reach_page(win, srv):
    from hlidskjalf.statusview import ReachPage, StatusDialog
    from hlidskjalf.userstatus import ustatus
    _ready(srv)
    ustatus.load()
    assert pump(lambda: ustatus.ok, 4)
    d = StatusDialog(win)
    d.present(win)
    pump(None, 0.2)
    d.st_checks["busy"].set_active(True)
    d.dur_checks["t7"][0].set_active(True)
    d.text.set_text("z" * 90)
    assert len(d.text.get_text()) == 80                     # max_length
    d.save()
    assert pump(lambda: ustatus.view["state"] == "busy", 4)
    b = srv.ustatus_log[-1][2]
    assert b["state"] == "busy" and "until" in b and len(b["text"]) == 80 and b["ver"] == 1
    d.force_close()
    # wieder oeffnen: "wie bisher" steht oben, Verfuegbar sperrt die Dauer
    d = StatusDialog(win)
    d.present(win)
    pump(None, 0.2)
    assert "keep" in d.dur_checks and d.dur_checks["keep"][0].get_active()
    d.st_checks["available"].set_active(True)
    assert not d.g_dur.get_sensitive()
    d.force_close()
    # Erreichbarkeit: Schalter speichert sofort
    from hlidskjalf.settings import SettingsDialog
    sd = SettingsDialog(win)
    sd.present(win)
    page = sd.pages["reach"]
    assert isinstance(page, ReachPage)
    assert pump(lambda: page.get_visible(), 3)
    sd.show_page("reach")
    pump(None, 0.2)

    def rows(w):
        out, c = [w], w.get_first_child()
        while c:
            out += rows(c)
            c = c.get_next_sibling()
        return out
    sw = next(r for r in rows(page) if isinstance(r, Adw.SwitchRow) and getattr(r, "hk_key", "") == "mention_break")
    sw.set_active(True)
    assert pump(lambda: srv.ustatus_log[-1][2] == {"mention_break": True}, 3)
    cb = next(r for r in rows(page) if isinstance(r, Adw.ComboRow) and getattr(r, "hk_key", "") == "busy")
    cb.set_selected(1)
    assert pump(lambda: srv.ustatus_log[-1][2] == {"busy": "silent"}, 3)
    page.exc_add({"type": "user", "ref": "~" + "anna" * 4, "name": "Anna Beispiel"})
    assert pump(lambda: srv.ustatus_log[-1][2] == {"exceptions": [{"type": "user", "ref": "~" + "anna" * 4}]}, 3)
    sd.force_close()


def test_peer_status_presence_and_call_question(win, srv, monkeypatch):
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.peer_status["anna"] = {"s": "dnd", "until": NOW, "text": "Im Kundentermin"}
    srv.privacy["test"] = {"receipts": True, "online": False}               # eigener Online-Status aus
    chat.me["privacy"] = {"receipts": True, "online": False}
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    chat.presence_watch("t", lambda: ["anna"])
    assert pump(lambda: chat.ustat.get("anna"), 4)
    assert chat.peer_status(chat.chat(cid))["s"] == "dnd" and chat.pres == {}
    # Live: anderer Zustand, dann null = entfernen
    chat.on_live("chat.status", {"username": "anna", "status": {"s": "in_call"}})
    assert chat.ustat["anna"] == {"s": "in_call"}
    chat.on_live("chat.status", {"username": "bert", "status": {"s": "dnd"}})   # nicht abonniert
    assert "bert" not in chat.ustat
    chat.on_live("chat.status", {"username": "anna", "status": None})
    assert "anna" not in chat.ustat
    chat.on_live("chat.status", {"username": "anna", "status": {"s": "dnd", "text": "Im Kundentermin"}})
    # Chat-Kopf und Rueckfrage vor dem Anruf
    opened = []
    monkeypatch.setattr(CL, "open_in_browser", lambda u, p=None: opened.append(u))
    monkeypatch.setattr(CL.calls, "config", {"enabled": True})
    win.show_route(("chat", cid))
    pump(None, 0.4)
    sub = win.wtitle.get_subtitle()                          # Abo-Antwort nach dem Oeffnen: Stand des Servers (mit Ende)
    assert sub.startswith("Do not disturb until ") and " · Im Kundentermin" in sub
    win.call_current()
    pump(None, 0.2)
    dlg = win.get_visible_dialog()
    assert isinstance(dlg, Adw.AlertDialog) and not opened
    dlg.emit("response", "call")
    assert opened == [f"https://saga.test/chats#c{cid}"]
    dlg.force_close()
    chat.on_live("chat.status", {"username": "anna", "status": {"s": "in_call"}})
    toasts = []
    monkeypatch.setattr(win, "toast", lambda t: toasts.append(t))
    win.call_current()
    assert toasts[0] == "Anna Beispiel is on a call right now – your call will knock." and len(opened) == 2
    chat.presence_watch("t", None)


def _calls(win, srv, app, monkeypatch):
    os.environ["HK_NO_RING"] = "1"
    CL.calls.reset()
    CL.calls.app = app
    monkeypatch.setattr(app, "send_notification", lambda i, n: None)
    monkeypatch.setattr(app, "withdraw_notification", lambda i: None)
    opened = []
    monkeypatch.setattr(CL, "open_in_browser", lambda u, p=None: opened.append(u))
    _ready(srv)
    srv.calls_waiting = True
    srv.calls_cfg = dict(srv.calls_cfg, features=["reneg", "multi", "waiting1"])
    srv.chat_peer("anna", "Anna Beispiel")
    srv.chat_peer("bert", "Bert Beispiel")
    return opened, srv.new_chat("dm", ["test", "anna"]), srv.new_chat("dm", ["test", "bert"])


def _running(srv, chat_id):
    import secrets
    run = secrets.token_hex(16)
    srv.calls[run] = {"id": run, "chat_id": chat_id, "media": "audio", "state": "active", "reason": None, "role": "caller",
                      "peer": {"username": "anna", "name": "Anna Beispiel"}, "mine": ["b" * 32], "kind": "dm"}
    return run


def test_caps_report_waiting1(win, srv, app, monkeypatch):
    _calls(win, srv, app, monkeypatch)
    CL.calls.load_config()
    assert pump(lambda: srv.caps_log, 4)
    assert "waiting1" in srv.caps_log[-1]["caps"] and len(srv.caps_log[-1]["caps"]) <= 16


def test_knock_decline_and_take(win, srv, app, monkeypatch):
    opened, a, b = _calls(win, srv, app, monkeypatch)
    run = _running(srv, a)
    k1 = mockv21.ring(srv, "bert", b, push=False)
    srv.calls[k1]["waiting"] = True
    CL.calls.on_live("call.ring", {"call_id": k1, "waiting": True})
    assert pump(lambda: k1 in CL.calls.holds and CL.calls.holds[k1].get("run") == run, 4)
    assert CL.calls.ringing is None and not win.callbar.get_reveal_child()
    assert win.holdbar.get_reveal_child() and win.holdbar.title.get_label() == "Bert Beispiel is calling (call waiting)"
    assert win.holdbar.dec.get_visible() and win.holdbar.take.get_visible() and not win.holdbar.acc.get_visible()
    win.holdbar.dec.emit("clicked")
    assert pump(lambda: srv.declined == [(k1, "busy")], 3) and not CL.calls.holds
    # Beenden und annehmen: erst /end, dann annehmen (Browser)
    k2 = mockv21.ring(srv, "bert", b, push=False)
    srv.calls[k2]["waiting"] = True
    CL.calls.on_live("call.ring", {"call_id": k2})
    assert pump(lambda: (CL.calls.holds.get(k2) or {}).get("run") == run, 4)
    win.holdbar.take.emit("clicked")
    assert pump(lambda: opened, 3)
    assert srv.ended and srv.ended[0][0] == run and opened == [f"https://saga.test/chats#call-{k2}-audio"]
    # Ende nicht bestaetigt: nicht annehmen, Meldung, der wartende Anruf klingelt normal
    run2 = _running(srv, a)
    srv.end_fail = True
    k3 = mockv21.ring(srv, "bert", b, push=False)
    srv.calls[k3]["waiting"] = True
    infos = []
    h = CL.calls.connect("info", lambda o, t: infos.append(t))
    CL.calls.on_live("call.ring", {"call_id": k3})
    assert pump(lambda: (CL.calls.holds.get(k3) or {}).get("run") == run2, 4)
    CL.calls.hold_take(k3, win)
    assert pump(lambda: CL.calls.ringing is not None, 4)
    CL.calls.disconnect(h)
    assert CL.calls.ringing["id"] == k3 and len(opened) == 1 and infos[0].startswith("The current call could not be ended")
    CL.calls.decline(k3)


def test_knock_rings_after_own_call_ends(win, srv, app, monkeypatch):
    opened, a, b = _calls(win, srv, app, monkeypatch)
    run = _running(srv, a)
    k = mockv21.ring(srv, "bert", b, push=False)
    srv.calls[k]["waiting"] = True
    CL.calls.on_live("call.ring", {"call_id": k})
    assert pump(lambda: k in CL.calls.holds, 4)
    srv.calls[run]["state"] = "ended"
    CL.calls.check_active()
    assert pump(lambda: CL.calls.ringing is not None and CL.calls.ringing["id"] == k, 4) and k not in CL.calls.holds
    CL.calls.decline(k)


def test_quiet_bar(win, srv, app, monkeypatch):
    opened, a, b = _calls(win, srv, app, monkeypatch)
    q = mockv21.ring(srv, "anna", a, push=False)
    srv.calls[q]["quiet"] = True
    CL.calls.on_live("call.quiet", {"call_id": q, "chat_id": a, "kind": "dm", "why": "quiet", "from": {"name": "Anna Beispiel"}})
    assert pump(lambda: (CL.calls.holds.get(q) or {}).get("elsewhere") is False, 4)
    assert CL.calls.ringing is None and win.holdbar.get_reveal_child()
    assert win.holdbar.acc.get_visible() and not win.holdbar.dec.get_visible() and "quiet hours" in win.holdbar.sub.get_label()
    win.holdbar.acc.emit("clicked")
    assert opened == [f"https://saga.test/chats#call-{q}-audio"] and not CL.calls.holds
    # eigenes Gespraech laeuft woanders: kein "Annehmen"; Ausblenden lehnt nicht ab
    _running(srv, b)
    q2 = mockv21.ring(srv, "anna", a, push=False)
    srv.calls[q2]["quiet"] = True
    CL.calls.on_live("call.quiet", {"call_id": q2, "kind": "dm", "why": "dnd"})
    assert pump(lambda: (CL.calls.holds.get(q2) or {}).get("elsewhere") is True, 4)
    assert not win.holdbar.acc.get_visible() and win.holdbar.hide_b.get_visible()
    assert "Do not disturb" in win.holdbar.sub.get_label() and win.holdbar.note.get_label()
    win.holdbar.hide_b.emit("clicked")
    assert not CL.calls.holds and not srv.declined and not win.holdbar.get_reveal_child()
    # Gruppenanruf mit quiet: kein call.quiet-Weg (stille Einladung)
    CL.calls.on_live("call.quiet", {"call_id": "9" * 32, "kind": "group"})
    pump(None, 0.2)
    assert "9" * 32 not in srv.call_gets
    # Ende-Ereignis raeumt die Leiste
    q3 = mockv21.ring(srv, "anna", a, push=False)
    srv.calls[q3]["quiet"] = True
    CL.calls.on_live("call.quiet", {"call_id": q3, "kind": "dm"})
    assert pump(lambda: q3 in CL.calls.holds and CL.calls.holds[q3].get("elsewhere") is not None, 4)
    CL.calls.on_live("call.state", {"call_id": q3, "state": "ended"})
    assert q3 not in CL.calls.holds


def test_group_invite_quiet_is_silent(win, srv, app, monkeypatch):
    import secrets
    opened, a, b = _calls(win, srv, app, monkeypatch)
    g = secrets.token_hex(16)
    srv.calls[g] = {"id": g, "chat_id": a, "kind": "group", "media": "audio", "state": "active", "invite": "ringing", "quiet": True,
                    "silent": False, "from": {"username": "anna", "name": "Anna Beispiel"}, "ring_s": 45}
    CL.calls.on_live("call.invite", {"call_id": g, "chat_id": a, "kind": "group", "quiet": True})
    assert pump(lambda: CL.calls.ringing is not None, 4)
    assert CL.calls.ringing["silent"] and not CL.calls.holds
    CL.calls.decline(g)
