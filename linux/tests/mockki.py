"""Nachgebauter Server, Teil Saga 2.1 (KI-Persona und "KI im Chat - nur auf Zuruf", Vertrag docs/APP-API-KI.md):
GET /api/ai/persona, /api/ai/avatar, persona/chat in /api/ai/status, Feld "ai" der Chats, PUT /api/chats/{cid}/ai,
POST /api/chats/{cid}/ai (Strom mit Beleg), Senden mit ai_reply und Feld "ai" in der Nachrichtenliste.
Wird von mockserver/mockv2 eingebunden.

    mockki.enable(srv, name="Marin", avatar=png, chat=True)  # Persona + KI in Chats; ohne Aufruf: aelterer Server
    srv.chats[cid]["ai"] = True                              # KI in diesem Chat erlaubt
    srv.ki_reply = ["Der Zug ", "fährt um 9."]               # Strom-Teile der Antwort
    mockki.peer_reply(srv, cid, "anna", "Text", ok=False)    # (gefaelschte) KI-Antwort eines Gegenuebers
"""
import hashlib
import json
import re
import secrets
import time

CTX_MAX = 12


def init(s):
    s.ai_persona = None           # None = Server ohne Persona (404 fuer /api/ai/persona)
    s.ai_avatar_png = None
    s.ki_reply = ["Der Zug ", "fährt um 9:12 Uhr ab Gleis 3."]
    s.ki_log = []                 # Anfragen an POST /api/chats/{cid}/ai
    s.ki_receipts = {}            # beleg-id -> {chat, uid, sha256, q_msg, used}
    s.ki_toggles = []
    s.ki_error = None             # (status, meldung): naechste Frage scheitert


def enable(s, name="Marin", avatar=None, chat=True, custom=True):
    s.ai_avatar_png = avatar
    s.ai_persona = {"name": name, "custom_name": custom, "avatar": "/api/ai/avatar?v=1" if avatar else None,
                    "avatar_etag": hashlib.sha256(avatar).hexdigest()[:16] if avatar else None}
    s.ai_status = dict(s.ai_status, enabled=True, host="ki.example", notice="Inhalte, die du dem Assistenten gibst, gehen an ki.example.",
                       quota=50, quota_left=40, persona=dict(s.ai_persona), chat=bool(chat))


def sha(text):
    return hashlib.sha256(str(text).strip().encode("utf-8")).hexdigest()


def mention(name, text):
    return re.search(r"(?:^|(?<=[\s(\[\"']))@" + re.escape(name) + r"(?!\w)", text or "", re.I) is not None


def chat_fields(s, ch, d):
    if s.ai_persona is not None:
        d["ai"] = bool(ch.get("ai"))
    return d


def msg_fields(s, m, d):
    if m.get("ai"):
        d["ai"] = dict(m["ai"])
    return d


def handle(s, method, path, parts, q, b):
    """einfache Anfragen (ohne Strom) -> Antwort oder None"""
    if path == "api/ai/persona":
        if s.ai_persona is None:
            return s._reply(404, {"error": "not found"})
        return s._reply(200, dict(s.ai_persona, enabled=bool(s.ai_status.get("enabled"))))
    if path == "api/ai/avatar":
        if s.ai_persona is None or not s.ai_avatar_png:
            return s._reply(404, {"error": "not found"})
        return s._reply(200, s.ai_avatar_png)
    if parts[:2] == ["api", "chats"] and len(parts) == 4 and parts[2].isdigit() and parts[3] == "ai" and method == "PUT":
        if s.ai_persona is None:
            return s._reply(404, {"error": "not found"})
        ch = s.chats.get(int(parts[2]))
        if ch is None or s.me not in ch["members"]:
            return s._reply(404, {"error": "not found"})
        if b.get("allowed") and not s.ai_status.get("chat"):
            return s._reply(403, {"error": "KI in Chats ist auf diesem Server nicht freigeschaltet."})
        if ch["kind"] == "free" and ch.get("admin") != s.me:
            return s._reply(403, {"error": "Dafür fehlt dir die Berechtigung."})
        ch["ai"] = bool(b.get("allowed"))
        s.ki_toggles.append((ch["id"], ch["ai"]))
        return s._reply(200, {"ai": ch["ai"]})
    return None


def on_send(s, cid, b):
    """POST messages mit ai_reply: Beleg pruefen -> (Fehler-Antwort | None, Feld "ai" der Nachricht | None)"""
    ref = b.get("ai_reply")
    if not ref:
        return None, None
    r = s.ki_receipts.get(ref)
    if not r or r["used"] or r["chat"] != cid or r["uid"] != s.me or time.time() - r["created"] > 3600:
        return s._reply(409, {"error": "Dieser KI-Beleg ist ungültig oder schon verwendet."}), None
    r["used"] = True
    return None, {"ref": ref, "sha256": r["sha256"], "by": s.me, "q_msg": r["q_msg"]}


def stream(s, path, b, sse, fake_stream):
    """POST /api/chats/{cid}/ai -> (status, FakeStream)"""
    cid = int(path.split("/")[2])
    s.ki_log.append((cid, b))
    ch = s.chats.get(cid)
    err = lambda code, msg: (code, fake_stream([json.dumps({"error": msg}).encode()]))  # noqa: E731
    if ch is None or s.me not in ch["members"]:
        return err(404, "not found")
    if not s.ai_status.get("enabled") or not s.ai_status.get("chat") or not ch.get("ai"):
        return err(403, "Die KI ist in diesem Chat nicht erlaubt.")
    hist = b.get("history")
    if not isinstance(hist, list) or len(hist) > CTX_MAX or not mention(s.ai_persona["name"], b.get("q")):
        return err(400, "ungültige Angabe")
    if s.ki_error:
        code, msg = s.ki_error
        s.ki_error = None
        return err(code, msg)
    lines = [b": ping\n", b"\n"]
    for t in s.ki_reply:
        lines += sse("delta", {"t": t})
    text = "".join(s.ki_reply).strip()
    rid = secrets.token_hex(16)
    s.ki_receipts[rid] = {"chat": cid, "uid": s.me, "sha256": sha(text), "q_msg": b.get("msg_id"), "used": False, "created": time.time()}
    lines += sse("done", {"text": text, "reply": {"id": rid, "sha256": sha(text)}, "persona": {"name": s.ai_persona["name"]},
                          "quota_left": 39})
    return 200, fake_stream(lines)


def peer_reply(s, cid, uid, text, ok=True, q_msg=None, meta=True):
    """KI-Antwort, die ein Gegenueber sendet: ok=False -> Hash passt nicht (gefaelscht); meta=False -> ohne Beleg"""
    import mockv2
    rid = secrets.token_hex(16)
    body = {"v": 1, "type": "text", "text": text, "ai": {"reply": rid, "name": s.ai_persona["name"]}}
    m = mockv2.peer_send(s, cid, uid, text, body=body)
    if meta:
        m["ai"] = {"ref": rid, "sha256": sha(text if ok else text + " (anders)"), "by": uid, "q_msg": q_msg}
    return m
