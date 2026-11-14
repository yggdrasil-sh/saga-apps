"""2.5 (L7, Auftrag kl-linux, Ziel 4): erneutes Klingeln im selben Gruppenanruf klingelt in der Linux-App wieder.
Vertrag: docs/APP-API.md "Anrufe: Erneut klingeln und Nicht dabei" - Restdauer invite_since + ring_s - now, ein hier
schon erledigter Anruf klingelt bei neuem call.invite/Push/Abfrage wieder (invite "ringing", kein eigenes Geraet,
nicht beendet); aeltere Server ohne invite_since wie bisher. Dazu: Leiste "Anruf laeuft" (L6) passt sich an."""
import secrets
import time

from hk_helpers import pump

SKEW = 1000.0                      # Server-Uhr geht vor: Abbildung nur ueber now


def _srv_now():
    return time.time() + SKEW


def _group(srv, cid, **kw):
    call = secrets.token_hex(16)
    c = {"id": call, "chat_id": cid, "media": "audio", "state": "active", "kind": "group", "role": "member",
         "invite": "ringing", "invite_since": _srv_now() - 2, "now": _srv_now(), "mine": [], "silent": False,
         "ring_s": 45, "expires_in": None, "joinable": True, "takeover": False,
         "from": {"username": "anna", "name": "Anna Beispiel", "chat": "Team"},
         "legs": [{"device": "c" * 32, "who": "anna", "dev_name": "Mac"}, {"device": "d" * 32, "who": "bert"}]}
    c.update(kw)
    srv.calls[call] = c
    return call


def _again(srv, call, ago=1.0):
    """Server: erneut geklingelt (/invite) - invite_since neu"""
    srv.calls[call].update(invite="ringing", invite_since=_srv_now() - ago, now=_srv_now())


def _ev(call, cid):
    return {"call_id": call, "chat_id": cid, "kind": "group", "media": "audio", "silent": False, "ring_s": 45,
            "from": {"username": "anna", "name": "Anna Beispiel", "chat": "Team"}}


def test_ring_left_and_rings_again():
    from hlidskjalf import calls as M
    g = {"id": "a" * 32, "kind": "group", "state": "active", "invite": "ringing", "mine": [], "ring_s": 45}
    assert M.ring_left(dict(g, invite_since=1000.0, now=1030.0)) == 15.0              # ab invite_since, nicht created
    assert M.ring_left(dict(g, invite_since=1000.0, now=1044.0)) == 5.0               # mindestens 5 s
    assert M.ring_left(dict(g, expires_in=12)) == 12.0                                # aelterer Server: wie bisher
    assert M.ring_left(dict(g, expires_in=0)) == 45.0
    assert M.ring_left({"ring_s": None}) == float(M.RING_FALLBACK_S)
    assert M.ring_left(dict(g, kind="dm", role="callee", invite_since=1000.0, now=1030.0, expires_in=7)) == 7.0
    # erledigt um t=100 (lokal); Server-Uhr = lokal + 900
    c = dict(g, invite_since=1005.0, now=1010.0)
    assert M.rings_again(c, 100.0, t=110.0)                    # Klingeln begann (1005) nach dem Erledigen (1000)
    assert not M.rings_again(dict(c, invite_since=990.0), 100.0, t=110.0)     # dasselbe Klingeln (nachgeholt)
    assert not M.rings_again(dict(c, invite_since=None), 100.0, t=110.0)      # aelterer Server
    assert not M.rings_again(dict(c, invite="missed"), 100.0, t=110.0)
    assert not M.rings_again(dict(c, invite="declined"), 100.0, t=110.0)
    assert not M.rings_again(dict(c, mine=["x" * 32]), 100.0, t=110.0)
    assert not M.rings_again(dict(c, state="ended"), 100.0, t=110.0)
    assert not M.rings_again(dict(c, now=True), 100.0, t=110.0)


def test_group_call_rings_again_live_push_and_poll(win, srv, app, monkeypatch):
    from test_v21b import _calls_setup
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    started = []
    monkeypatch.setattr(M.calls.ringer, "start", lambda: started.append(1))
    call = _group(srv, cid)
    M.calls.on_live("call.invite", _ev(call, cid))
    assert pump(lambda: M.calls.ringing is not None, 4)
    r = M.calls.ringing
    assert 40 < r["until"] - time.monotonic() <= 43.5          # Restdauer ab invite_since (2 s her), Uhrabstand egal
    # Klingeln vorbei (Zeitgeber) -> erledigt; Server: missed
    M.calls._timeout(call)
    assert M.calls.ringing is None and call in M.calls.done
    srv.calls[call].update(invite="missed", invite_since=None)
    # nachgeholtes altes call.invite: klingelt nicht
    n = len(srv.call_gets)
    M.calls.on_live("call.invite", _ev(call, cid))
    assert pump(lambda: len(srv.call_gets) > n, 4)
    pump(None, 0.3)
    assert M.calls.ringing is None
    # dasselbe Klingeln noch "ringing" (z. B. nach Annehmen, Browser noch nicht dabei): klingelt nicht
    srv.calls[call].update(invite="ringing", invite_since=_srv_now() - 30, now=_srv_now())
    n = len(srv.call_gets)
    M.calls.on_live("call.invite", _ev(call, cid))
    assert pump(lambda: len(srv.call_gets) > n, 4)
    pump(None, 0.3)
    assert M.calls.ringing is None
    # erneut geklingelt (live): klingelt wieder, Restdauer ab dem neuen invite_since
    _again(srv, call, ago=0.5)
    started.clear()
    M.calls.on_live("call.invite", _ev(call, cid))
    assert pump(lambda: M.calls.ringing is not None and M.calls.ringing["id"] == call, 4)
    assert 42 < M.calls.ringing["until"] - time.monotonic() <= 45 and started
    assert [i for i, x in notes.sent].count("call-" + call) >= 2
    # abgelehnt -> erneut geklingelt per Push call.group
    M.calls.decline(call)
    assert M.calls.ringing is None
    pump(None, 0.3)                                            # Gruppe: Server antwortet 409 (still)
    _again(srv, call)
    srv.calls[call]["state"] = "active"
    M.calls.on_push({"kind": "call.group", "call": call, "chat": cid, "media": "audio", "caller": "Anna", "group": True})
    assert pump(lambda: M.calls.ringing is not None and M.calls.ringing["id"] == call, 4)
    M.calls._timeout(call)
    # Abfrage (Start/Aufwachen, GET /api/calls/active): erneut geklingelt -> klingelt
    _again(srv, call)
    M.calls.check_active()
    assert pump(lambda: M.calls.ringing is not None and M.calls.ringing["id"] == call, 4)
    M.calls._timeout(call)
    # eigenes Geraet schon dabei bzw. Anruf beendet: klingelt nicht wieder
    for upd in ({"mine": ["x" * 32]}, {"state": "ended"}):
        _again(srv, call)
        srv.calls[call].update(dict({"state": "active", "mine": []}, **upd))
        n = len(srv.call_gets)
        M.calls.on_live("call.invite", _ev(call, cid))
        assert pump(lambda: len(srv.call_gets) > n, 4)
        pump(None, 0.3)
        assert M.calls.ringing is None


def test_old_server_without_invite_since_unchanged(win, srv, app, monkeypatch):
    from test_v21b import _calls_setup
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    call = _group(srv, cid, invite_since=None, now=None, state="ringing", expires_in=20)
    srv.calls[call].pop("invite_since")
    srv.calls[call].pop("now")
    M.calls.on_live("call.invite", _ev(call, cid))
    assert pump(lambda: M.calls.ringing is not None, 4)
    assert 18 < M.calls.ringing["until"] - time.monotonic() <= 20.5      # expires_in wie bisher
    M.calls._timeout(call)
    n = len(srv.call_gets)
    M.calls.on_live("call.invite", _ev(call, cid))                       # aelterer Server: invite bleibt "ringing"
    assert pump(lambda: len(srv.call_gets) > n, 4)
    pump(None, 0.3)
    assert M.calls.ringing is None and call in M.calls.done


def test_ringing_extended_and_runbar(win, srv, app, monkeypatch):
    from test_v21b import _calls_setup
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    started = []
    monkeypatch.setattr(M.calls.ringer, "start", lambda: started.append(1))
    # grosse Gruppe: still gestartet, laeuft schon 40 s
    call = _group(srv, cid, silent=True, invite_since=_srv_now() - 40)
    M.calls.on_live("call.invite", dict(_ev(call, cid), silent=True))
    assert pump(lambda: M.calls.ringing is not None, 4)
    assert M.calls.ringing["silent"] and not started and M.calls.ringing["until"] - time.monotonic() <= 5.5
    # gezielt erneut geklingelt, waehrend es hier noch klingelt: Dauer neu, jetzt laut
    srv.calls[call]["silent"] = False
    _again(srv, call)
    M.calls.on_live("call.invite", _ev(call, cid))
    assert pump(lambda: M.calls.ringing["until"] - time.monotonic() > 40, 4)
    assert not M.calls.ringing["silent"] and started
    # Leiste "Anruf laeuft" (L6): nicht, solange es hier klingelt; nach dem Klingeln "Beitreten"
    bar = win.runbar
    M.calls.check_active()
    pump(None, 0.5)
    assert not bar.get_reveal_child()
    M.calls._timeout(call)
    srv.calls[call].update(invite="missed", invite_since=None)
    assert pump(lambda: bar.get_reveal_child() and bar.cid == call, 4)
    assert [b.short for b in bar.buttons] == ["Join (in browser)"]
    # erneut geklingelt: eigene Klingel-Leiste statt "Anruf laeuft", danach wieder zurueck
    _again(srv, call)
    M.calls.on_live("call.invite", _ev(call, cid))
    assert pump(lambda: M.calls.ringing is not None and not bar.get_reveal_child(), 4)
    assert win.callbar.get_reveal_child()
    M.calls._timeout(call)
    srv.calls[call].update(invite="missed", invite_since=None)
    assert pump(lambda: bar.get_reveal_child() and bar.cid == call, 4)
    srv.calls[call]["state"] = "ended"
    M.calls.on_live("call.state", {"call_id": call, "state": "ended", "kind": "group"})
    assert pump(lambda: not bar.get_reveal_child(), 4)
