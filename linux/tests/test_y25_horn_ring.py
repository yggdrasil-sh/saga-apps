"""2.5 Grundsatz Y (Entscheidung "30 Tage + Linux-Faehigkeit"): die Linux-App meldet "horn_ring" (kann einen Anruf ueber
Knoten annehmen - klingelt, das Gespraech laeuft im Browser) und klingelt einen 1:1-Anruf ueber Knoten (kind group, dm)
wie einen 1:1-Anruf: Titel "Anruf von …", "Ablehnen"/"Annehmen", Annehmen oeffnet #call-<id>-<media> (die Web-Oberflaeche
tritt dem Raum-Anruf dort bei), call.state active (angenommen auf einem anderen Geraet) beendet das Klingeln.
Vertrag: docs/APP-API.md "1:1 ueber Knoten" (horn_ring, 30-Tage-Regel)."""
import secrets

from hk_helpers import pump


def _dmhorn(srv, cid, **kw):
    """GET /api/calls/<id> beim Gegenueber eines 1:1 ueber Knoten (wie der Server: kind group, topo horn, dm, invite)"""
    call = secrets.token_hex(16)
    c = {"id": call, "chat_id": cid, "media": "video", "state": "ringing", "kind": "group", "topo": "horn", "dm": True,
         "role": "member", "invite": "ringing", "mine": [], "silent": False, "ring_s": 45, "expires_in": 40,
         "peer": {"username": "anna", "name": "Anna Beispiel"}, "from": {"username": "anna", "name": "Anna Beispiel"},
         "legs": [{"device": "c" * 32, "who": "anna", "dev_name": "Mac"}]}
    c.update(kw)
    srv.calls[call] = c
    return call


def _ev(call, cid, media="video"):
    return {"call_id": call, "chat_id": cid, "kind": "group", "dm": True, "media": media, "silent": False, "ring_s": 45,
            "from": {"username": "anna", "name": "Anna Beispiel"}}


def test_caps_horn_ring():
    from hlidskjalf.calls import device_caps
    full = {"enabled": True, "features": ["reneg", "multi", "horn"]}
    assert device_caps(full) == ["multi", "horn_ring"]
    assert "horn" not in device_caps(full)                     # keine eigene PeerConnection - nur ueber den Browser
    assert device_caps({"enabled": True, "features": ["reneg"]}) == []


def test_dm_horn_rings_like_1to1_and_opens_call(win, srv, app, monkeypatch):
    from test_v21b import _calls_setup
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    monkeypatch.setattr(M.calls.ringer, "start", lambda: None)
    call = _dmhorn(srv, cid)
    M.calls.on_live("call.invite", _ev(call, cid))
    assert pump(lambda: M.calls.ringing is not None, 4)
    r = M.calls.ringing
    assert r["dm"] is True and not r["group"] and not r["silent"]
    assert M.ring_title(dict(r, kind="group", dm=True)) != M.ring_title(dict(r, kind="group", dm=False))
    assert win.callbar.get_reveal_child() and "Anna Beispiel" in win.callbar.title.get_label()
    assert win.callbar.dec.short == M._("Decline") and win.callbar.acc_v.short == M._("Answer")
    win.callbar.acc_v.emit("clicked")
    assert opened == [f"https://saga.test/chats#call-{call}-video"], opened
    assert M.calls.ringing is None


def test_dm_horn_answered_elsewhere_stops(win, srv, app, monkeypatch):
    from test_v21b import _calls_setup
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    monkeypatch.setattr(M.calls.ringer, "start", lambda: None)
    infos = []
    M.calls.connect("info", lambda _o, t: infos.append(t))
    call = _dmhorn(srv, cid, media="audio")
    M.calls.on_live("call.invite", _ev(call, cid, "audio"))
    assert pump(lambda: M.calls.ringing is not None, 4)
    # Gruppenanruf: "active" heisst nur "zwei sind dabei" - 1:1 ueber Knoten: angenommen (anderes Geraet)
    M.calls.on_live("call.state", {"call_id": call, "kind": "group", "dm": True, "state": "active"})
    assert M.calls.ringing is None and call in M.calls.done
    assert any("another device" in t for t in infos), infos
    # Gruppenanruf (ohne dm) klingelt bei "active" weiter
    g = _dmhorn(srv, cid, dm=False, state="active", media="audio", **{"from": {"username": "anna", "name": "Anna", "chat": "Team"}})
    M.calls.on_live("call.invite", dict(_ev(g, cid, "audio"), dm=False))
    assert pump(lambda: M.calls.ringing is not None and M.calls.ringing["id"] == g, 4)
    M.calls.on_live("call.state", {"call_id": g, "kind": "group", "state": "active"})
    assert M.calls.ringing is not None and M.calls.ringing["group"]
    M.calls._timeout(g)


def test_dm_horn_push_accept_opens_call(win, srv, app, monkeypatch):
    from test_v21b import _calls_setup
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    monkeypatch.setattr(M.calls.ringer, "start", lambda: None)
    call = _dmhorn(srv, cid, media="audio")
    M.calls.on_push({"kind": "call.group", "call": call, "chat": cid, "media": "audio", "caller": "Anna", "group": True, "dm": True})
    assert pump(lambda: M.calls.ringing is not None and M.calls.ringing["id"] == call, 4)
    assert M.calls.ringing["dm"] and not M.calls.ringing["group"]
    M.calls.accept(call)
    assert opened == [f"https://saga.test/chats#call-{call}-audio"], opened
