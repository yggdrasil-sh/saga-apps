"""Schein-Server: Saga 2.6 "Eigener Status" (Bugs-DB 2029, docs/APP-API.md "Eigener Status" bis "Anrufe und eigener
Status") - nur mit s.ustatus_api = True. GET/PUT /api/account/status (ver, 409), PUT .../prefs (exceptions ersetzt die
Liste), userstatus1 in GET /api/chat/me, status im Presence-Abo (s.peer_status), waiting1 in GET /api/calls/config
(s.calls_waiting), PUT /api/calls/caps (s.caps_log), POST /api/calls/{id}/end. Einfache Regeln - genau: der echte Server.

    srv.ustatus_api = True
    srv.peer_status["anna"] = {"s": "dnd", "until": ..., "text": "Im Kundentermin"}
    status_set(srv, state="dnd", until=..., text="...")     # eigener Status (ohne Live-Ereignis)
"""
import copy
import time

PREFS = {"quiet_dnd": True, "quiet_digest": False, "in_call": "knock", "show_call": True, "busy": "show", "repeat": True,
         "repeat_s": 180, "mention_break": False}
LIMITS = {"text": 80, "exceptions": 100, "repeat_s": [30, 3600], "until_max": 365 * 86400, "dur_min": 60}


def init(s):
    s.ustatus_api = False
    s.uview = {"state": "available", "until": None, "text": None, "source": "manual", "since": time.time(), "updated": time.time(),
               "ver": 1, "effective": "available", "why": "none", "quiet": False, "in_call": False, "prefs": dict(PREFS),
               "exceptions": [], "limits": dict(LIMITS)}
    s.ustatus_log = []            # [(methode, pfad, body)]
    s.peer_status = {}            # Benutzername -> {s, until?, text?}
    s.calls_waiting = False       # Server kann Anklopfen (waiting1 in /api/calls/config)
    s.caps_log = []               # Bodies von PUT /api/calls/caps
    s.ended = []                  # (call_id, body) von POST /api/calls/{id}/end
    s.end_fail = False            # True: /end antwortet 500 (Ende nicht bestaetigt)


def _eff(v):
    if v["state"] == "dnd":
        return "dnd", "manual"
    if v.get("quiet") and v["prefs"].get("quiet_dnd"):
        return "dnd", "quiet"
    if v.get("in_call"):
        return "in_call", "call"
    if v["state"] == "busy":
        return "busy", "manual"
    return "available", "none"


def view(s):
    v = copy.deepcopy(s.uview)
    if v["until"] and v["until"] <= time.time():
        v.update(state="available", until=None, text=None, source="system")
    v["effective"], v["why"] = _eff(v)
    v["now"] = time.time()
    return v


def status_set(s, **kw):
    s.uview.update(kw)
    s.uview["ver"] += 1
    s.uview["updated"] = time.time()


def me_features(s, d):
    if getattr(s, "ustatus_api", False) and isinstance(d.get("features"), list):
        d["features"] = d["features"] + ["userstatus1"]
    return d


def handle(s, method, path, parts, q, b):
    """-> Antwort oder None"""
    if not getattr(s, "ustatus_api", False):
        return None
    if path == "api/account/status":
        if method == "GET":
            return s._reply(200, view(s))
        if method != "PUT":
            return s._reply(405, {"error": "method"})
        s.ustatus_log.append((method, path, dict(b)))
        if "ver" in b and b["ver"] != s.uview["ver"]:
            return s._reply(409, dict(view(s), error="Der Status wurde inzwischen geändert."))
        st = b.get("state")
        if st not in ("available", "busy", "dnd"):
            return s._reply(400, {"error": "ungültiger Zustand"})
        src = b.get("source") if b.get("source") in ("manual", "focus") else "manual"
        txt = b.get("text") if src == "manual" and isinstance(b.get("text"), str) else None
        if txt is not None and len(txt) > 80:
            return s._reply(400, {"error": "Text zu lang"})
        until = None
        if st != "available":
            if isinstance(b.get("dur"), (int, float)):
                until = time.time() + b["dur"]
            elif isinstance(b.get("until"), (int, float)):
                until = float(b["until"])
        status_set(s, state=st, until=until, text=txt or None, source=src, since=time.time())
        return s._reply(200, view(s))
    if path == "api/account/status/prefs" and method == "PUT":
        s.ustatus_log.append((method, path, dict(b)))
        for k, v in b.items():
            if k in PREFS:
                s.uview["prefs"][k] = v
        if "exceptions" in b:
            ex = b["exceptions"] if isinstance(b["exceptions"], list) else []
            if len(ex) > 100:
                return s._reply(400, {"error": "zu viele Ausnahmen"})
            from mockstd import ref_of
            by_ref = {ref_of(u): n for u, n in s.names.items()}
            s.uview["exceptions"] = [dict(x, name=x.get("name") or by_ref.get(x.get("ref"), x.get("ref"))) if x.get("type") == "user"
                                     else dict(x) for x in ex]
        status_set(s)
        return s._reply(200, view(s))
    if path == "api/chat/presence" and method == "POST":
        import json
        import mockv24
        code, body, h = mockv24.handle(s, method, path, parts, q, b)
        if code != 200 or b.get("leave"):
            return code, body, h
        j = json.loads(body)
        j["status"] = {u: dict(x) for u, x in s.peer_status.items() if u in (b.get("users") or [])}
        return code, json.dumps(j).encode(), h
    if path == "api/calls/config" and getattr(s, "calls_api", True):
        cfg = dict(s.calls_cfg)
        f = list(cfg.get("features") or [])
        if s.calls_waiting and "waiting1" not in f:
            f.append("waiting1")
        cfg["features"] = f
        return s._reply(200, cfg)
    if path == "api/calls/caps" and method == "PUT":
        s.caps_log.append(dict(b))
        return s._reply(200, {"ok": True})
    if len(parts) == 4 and parts[:2] == ["api", "calls"] and parts[3] == "end" and method == "POST":
        c = s.calls.get(parts[2])
        if c is None:
            return s._reply(404, {"error": "not found"})
        if s.end_fail:
            return s._reply(500, {"error": "mock 500"})
        s.ended.append((parts[2], dict(b)))
        c["state"], c["reason"] = "ended", "hangup"
        s.push_event("call.state", {"call_id": parts[2], "chat_id": c["chat_id"], "state": "ended", "reason": "hangup", "status": "ended"})
        return s._reply(200, {"ok": True})
    return None
