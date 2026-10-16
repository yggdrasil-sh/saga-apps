"""Nachgebauter Server, Teil Saga 2.1 (Welle b): Profilbilder (Feld "avatar" in Personen-Objekten, /api/avatar/...,
/api/account/avatar), "Anmeldung & Sicherheit" (/api/account/security), Chat-"Neu beginnen" mit Code der
Authenticator-App (reauth_methods), Anrufe (/api/calls/..., Live call.ring/call.state, Chat-Eintraege kind "call").
Wird von mockserver.MockServer eingebunden (vor allen anderen Pfaden, Antworten werden danach um "avatar" ergaenzt).

    srv.set_avatar("anna", png)                    # anna hat ein Bild (alle Personen-Objekte bekommen die Adresse)
    srv.security = {...} / None                    # Stand bzw. aelterer Server (404)
    mockv21.ring(srv, "anna", cid, "video")        # Anruf klingelt (Live-Ereignis call.ring)
    mockv21.call_entry(srv, cid, "missed", ...)    # Eintrag im Chat
"""
import io
import json
import secrets
import time


def init(s):
    s.avatar_api = True
    s.avatars = {}                # uid -> (bytes, version)
    s.avatar_log = []             # [(pfad, Accept)]
    s.avatar_fail = 0             # >0: so oft 404 fuer /api/avatar (z. B. Cache-Pruefung)
    s.my_avatar = {"gravatar": True, "gravatar_enabled": True, "has_email": True, "public": False, "max_mb": 5, "sso": False}
    s.avatar_uploads = []         # [bytes] (PUT /api/account/avatar)
    s.security = None             # None = Server ohne 2FA (404)
    s.reauth_methods = ["password"]
    s.reauth_code = "246810"
    s.calls_cfg = {"enabled": True, "ice_servers": [], "relay_only": False, "turn": False, "stun": True, "ring_s": 45,
                   "alive_s": 15, "max_s": 14400, "format": "saga-call/v1"}
    s.calls_api = True
    s.calls = {}                  # call_id -> {id, chat_id, media, state, reason, role, peer, created, ...}
    s.call_msgs = {}              # chat_id -> [Nachricht kind "call"]
    s.declined = []
    s.call_gets = []


def set_avatar(s, uid, data):
    v = (s.avatars.get(uid) or (b"", 0))[1] + 1
    s.avatars[uid] = (data, v)


def av_url(s, uid):
    x = s.avatars.get(uid)
    return f"/api/avatar/k{uid}?v={x[1]}" if x else None


def _png(color=(200, 120, 60), size=96, text=None):
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (size, size), color)
    d = ImageDraw.Draw(im)
    d.ellipse((size * 0.3, size * 0.18, size * 0.7, size * 0.58), fill=(250, 230, 210))
    d.ellipse((size * 0.12, size * 0.6, size * 0.88, size * 1.3), fill=(250, 230, 210))
    out = io.BytesIO()
    im.save(out, "PNG")
    return out.getvalue()


def face(color):
    return _png(color)


# --- Antworten ergaenzen (Feld "avatar" wie der echte Server) ---------------------------------------------------------
def _walk(s, o):
    if isinstance(o, dict):
        u = o.get("username")
        if isinstance(u, str) and "avatar" not in o:
            o["avatar"] = av_url(s, u)
        if o.get("type") in ("user",) and isinstance(o.get("target"), str) and "avatar" not in o:
            o["avatar"] = av_url(s, o["target"])
        for v in list(o.values()):
            _walk(s, v)
    elif isinstance(o, list):
        for v in o:
            _walk(s, v)


def decorate(s, path, reply):
    code, body, headers = reply
    if not s.avatar_api or not isinstance(body, bytes) or not body[:1] in (b"{", b"["):
        return reply
    try:
        j = json.loads(body)
    except ValueError:
        return reply
    if path == "api/account" and isinstance(j, dict):
        j["avatar"] = av_url(s, j.get("username") or s.me)
    if path == "api/chat/me" and isinstance(j, dict):
        j["reauth_methods"] = list(s.reauth_methods)
    if s.avatars:
        _walk(s, j)
    return code, json.dumps(j).encode(), headers


# --- Anrufe -----------------------------------------------------------------------------------------------------------
def ring(s, uid, chat_id, media="audio", push=True, expires=45):
    cid = secrets.token_hex(16)
    peer = {"username": uid, "name": s.names.get(uid, uid), "avatar": av_url(s, uid)}
    s.calls[cid] = {"id": cid, "chat_id": chat_id, "media": media, "state": "ringing", "reason": None, "role": "callee", "peer": peer,
                    "caller_device": "a" * 32, "callee_device": None, "created": time.time(), "answered": None, "ended": None,
                    "ring_s": 45, "expires_in": expires}
    if push:
        s.push_event("call.ring", {"call_id": cid, "chat_id": chat_id, "media": media, "from_device": "a" * 32, "ring_s": 45,
                                   "from": peer})
    return cid


def end_call(s, cid, state="ended", reason="cancelled", status="missed"):
    c = s.calls[cid]
    c["state"] = state
    c["reason"] = reason if state == "ended" else None
    ev = {"call_id": cid, "chat_id": c["chat_id"], "state": state}
    if state == "ended":
        ev.update(reason=reason, status=status)
    else:
        ev["device"] = "b" * 32
    s.push_event("call.state", ev)


def call_entry(s, chat_id, status, media="audio", frm="anna", to="test", dur=None, ago=60):
    s.chat_seq += 1
    body = json.dumps({"v": 1, "type": "call", "call": secrets.token_hex(16), "media": media, "status": status, "dur": dur,
                       "from": frm, "to": to}, separators=(",", ":"))
    m = {"seq": s.chat_seq, "id": secrets.token_hex(16), "kind": "call", "sender": {"username": frm, "name": s.names.get(frm, frm)},
         "sender_device": "a" * 32, "ts": int((time.time() - ago) * 1000), "created": time.time() - ago, "expires_at": None,
         "body_ct": body, "sig": "", "reply_to": None, "key": None, "key_for": None}
    s.call_msgs.setdefault(chat_id, []).append(m)
    ch = s.chats.get(chat_id)
    if ch is not None:
        ch["msgs"].append({"seq": m["seq"], "id": m["id"], "kind": "call", "sender": frm, "created": m["created"], "keys": {},
                           "sender_device": m["sender_device"], "ts": m["ts"], "expires_at": None, "body_ct": body, "sig": "",
                           "reply_to": None})
    return m


def _merge_calls(s, cid, q, reply):
    code, body, headers = reply
    if code != 200:
        return reply
    j = json.loads(body)
    extra = list(s.call_msgs.get(cid, []))
    if q.get("after"):
        extra = [m for m in extra if m["seq"] > int(q["after"])]
    elif q.get("before"):
        extra = [m for m in extra if m["seq"] < int(q["before"])]
    have = {m["id"] for m in j.get("messages") or []}
    j["messages"] = sorted((j.get("messages") or []) + [m for m in extra if m["id"] not in have], key=lambda m: m["seq"])
    return code, json.dumps(j).encode(), headers


# --- Einstieg ---------------------------------------------------------------------------------------------------------
def handle(s, method, path, parts, q, b, raw, rest):
    """-> Antwort oder None; rest(): die uebrigen Pfade (mockv2 + mockserver) fuer Antworten, die hier ergaenzt werden"""
    me = s.me
    if path.startswith("api/avatar/"):
        if not s.avatar_api:
            return s._reply(404, {"error": "not found"})
        s.avatar_log.append((path, q.get("s")))
        uid = path.split("/")[2][1:]
        if s.avatar_fail > 0:
            s.avatar_fail -= 1
            return s._reply(404, {"error": "not found"})
        x = s.avatars.get(uid)
        return s._reply(200, x[0]) if x else s._reply(404, {"error": "not found"})
    if path == "api/account/avatar":
        if not s.avatar_api:
            return s._reply(404, {"error": "not found"})
        if method == "PUT":
            data = bytes(raw or b"")
            if len(data) > s.my_avatar["max_mb"] * 1048576:
                return s._reply(413, {"error": "Das Bild ist zu groß."})
            try:
                from PIL import Image
                Image.open(io.BytesIO(data)).load()
            except Exception:  # noqa: BLE001
                return s._reply(415, {"error": "Das ist kein Bild."})
            s.avatar_uploads.append(data)
            set_avatar(s, me, data)
        elif method == "DELETE":
            s.avatars.pop(me, None)
        elif method == "PATCH":
            for k in ("gravatar", "public"):
                if k in b:
                    s.my_avatar[k] = bool(b[k])
        up = me in s.avatars
        info = dict(s.my_avatar, url=av_url(s, me), upload=up, source="upload" if up else None)
        return s._reply(200, info)
    if path == "api/account/security":
        if s.security is None:
            return s._reply(404, {"error": "not found"})
        return s._reply(200, s.security)
    if path == "api/chat/identity" and method == "PUT" and b.get("reset") and b.get("code") is not None:
        if str(b.get("code")) != s.reauth_code:
            return s._reply(403, {"error": "Der Code stimmt nicht.", "reauth": "password", "reauth_methods": list(s.reauth_methods)})
        b.pop("code", None)
        b["password"] = "pw"                                  # wie ein richtiges Passwort fuer mockv2
        return None
    if path.startswith("api/calls"):
        if not s.calls_api:
            return s._reply(404, {"error": "not found"})
        if path == "api/calls/config":
            return s._reply(200, s.calls_cfg)
        if path == "api/calls/active":
            return s._reply(200, {"calls": [c for c in s.calls.values() if c["state"] in ("ringing", "active")]})
        if len(parts) >= 3:
            c = s.calls.get(parts[2])
            if c is None:
                return s._reply(404, {"error": "not found"})
            if len(parts) == 3 and method == "GET":
                s.call_gets.append(parts[2])
                return s._reply(200, {"call": dict(c), "signals": []})
            if parts[3:] == ["decline"] and method == "POST":
                if c["state"] != "ringing":
                    return s._reply(409, {"ok": False, "call": c})
                s.declined.append((parts[2], b.get("reason")))
                c["state"], c["reason"] = "ended", "declined"
                return s._reply(200, {"ok": True})
        return s._reply(404, {"error": "not found"})
    if len(parts) == 4 and parts[:2] == ["api", "chats"] and parts[2].isdigit() and parts[3] == "messages" and method == "GET" \
            and q.get("calls"):
        return _merge_calls(s, int(parts[2]), q, rest())
    return None
