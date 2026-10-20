"""Nachgebauter Server, Teil Saga 2.4: Gelesen-Bestaetigungen und Online-Status (wie saga/chat_status.py im Server-Repo).

    srv.status_api = False                  # wie ein aelterer Server (keine features, 404)
    srv.privacy["anna"] = {"receipts": False, "online": True}
    srv.peer_receipt(cid, "anna", msg)      # Anna hat bis msg gelesen (versiegelt fuer "test", Live-Ereignis)
    srv.online["anna"] = {"online": True, "last": None}
    srv.read_log / srv.presence_calls       # was die App geschickt hat
"""
import time

from hlidskjalf import chatcrypto as C


def init(s):
    import mockvideo
    mockvideo.init(s)             # 2.4 E2: Videos im Chat (saga-file/v2)
    s.status_api = True
    s.privacy = {}                # uid -> {"receipts", "online"} (fehlt = beides an)
    s.receipts = {}               # (chat_id, viewer) -> {reader: sealed}
    s.read_log = []               # Bodies von POST /api/chats/{id}/read
    s.presence_calls = []         # Bodies von POST /api/chat/presence
    s.online = {}                 # uid -> {"online", "last"}
    s.receipt_rate = False        # True: Antwort "rate"


def prefs(s, uid):
    p = s.privacy.get(uid) or {}
    return {"receipts": p.get("receipts", True), "online": p.get("online", True)}


def me_extra(s, d):
    import mockvideo
    if isinstance(d.get("limits"), dict):
        mockvideo.limits(s, d["limits"])
    if s.status_api:
        d.update(privacy=prefs(s, s.me), features=["receipts", "online"],
                 status_limits={"receipt_gap": 2.0, "presence_ttl": 90, "presence_users": 50, "last_round": 300})
    return d


def peer_receipt(s, cid, uid, m, live=True, ts=None, dev=None, forge=False):
    """Gegenueber uid bestaetigt "gelesen bis m" (versiegelt fuer den Backup-Schluessel von test) -> versiegelt"""
    import mockv2
    p = s.peers[uid]
    d = dev or p["device"]
    body = C.receipt_body(m["id"], m["seq"], ts if ts is not None else int(time.time() * 1000))
    if forge:                                               # anderer Chat signiert: Pruefung muss scheitern
        body2 = body
        sealed = C.seal_receipt(str(cid + 1000), uid, d, body2, C.unb64(s.idents[s.me]["backup_pub"], 32))
    else:
        sealed = C.seal_receipt(str(cid), uid, d, body, C.unb64(s.idents[s.me]["backup_pub"], 32))
    if not prefs(s, uid)["receipts"]:
        return sealed
    s.receipts.setdefault((cid, s.me), {})[uid] = sealed
    if live and prefs(s, s.me)["receipts"]:
        mockv2.push_event(s, "chat.receipt", {"chat_id": cid, "receipts": [{"from": uid, "sealed": sealed}]})
    return sealed


def _shared(s, me, u):
    return any(me in c["members"] and u in c["members"] for c in s.chats.values())


def handle(s, method, path, parts, q, b):
    import mockv2
    import mockvideo
    r = mockvideo.handle(s, method, path, parts, q, b)
    if r is not None:
        return r
    me = s.me
    if path == "api/chat/privacy":
        if not s.status_api:
            return s._reply(404, {"error": "not found"})
        if method == "PUT":
            if not isinstance(b, dict) or not any(k in b for k in ("receipts", "online")) or \
                    any(k in b and not isinstance(b[k], bool) for k in ("receipts", "online")):
                return s._reply(400, {"error": "ungültige Angabe"})
            old = prefs(s, me)
            new = dict(old, **{k: b[k] for k in ("receipts", "online") if k in b})
            s.privacy[me] = new
            if old["receipts"] and not new["receipts"]:          # eigene Bestaetigungen zurueckziehen
                for k in list(s.receipts):
                    s.receipts[k].pop(me, None)
            mockv2.push_event(s, "chat.privacy", new)
            return s._reply(200, new)
        return s._reply(200, prefs(s, me))
    if path == "api/chat/presence" and method == "POST":
        if not s.status_api:
            return s._reply(404, {"error": "not found"})
        s.presence_calls.append(dict(b))
        if b.get("leave"):
            return s._reply(200, {"ok": True})
        if not prefs(s, me)["online"]:
            return s._reply(200, {"enabled": False, "ttl": 90, "users": {}})
        out = {u: dict(s.online[u]) for u in b.get("users") or [] if u in s.online and _shared(s, me, u) and prefs(s, u)["online"]}
        return s._reply(200, {"enabled": True, "ttl": 90, "users": out})
    if parts[:2] == ["api", "chats"] and len(parts) == 4 and parts[2].isdigit() and s.status_api:
        cid = int(parts[2])
        ch = s.chats.get(cid)
        if ch is None or me not in ch["members"]:
            return None
        if parts[3] == "receipts" and method == "GET":
            if not prefs(s, me)["receipts"]:
                return s._reply(200, {"chat_id": cid, "enabled": False, "receipts": []})
            got = s.receipts.get((cid, me)) or {}
            return s._reply(200, {"chat_id": cid, "enabled": True,
                                  "receipts": [{"from": u, "sealed": v} for u, v in got.items()
                                               if u in ch["members"] and prefs(s, u)["receipts"]]})
        if parts[3] == "read" and method == "POST":
            s.read_log.append(dict(b))
            ch["last_read"][me] = max(ch["last_read"].get(me, 0), int(b.get("seq") or 0))
            out = [mockv2._chat_dict(s, c, me) for c in s.chats.values() if me in c["members"]]
            res = {"ok": True, "last_read": ch["last_read"][me],
                   "unread": sum(c["unread"] for c in out if not c["muted"] and not c.get("archived"))}
            if "receipt" in b:
                keys = (b.get("receipt") or {}).get("keys")
                if not isinstance(keys, dict) or not keys or len(keys) > 50 or any(
                        not k.startswith("backup:") or not isinstance(v, str) or not 112 <= len(v) <= 2048 for k, v in keys.items()):
                    res["receipt"] = "bad"
                elif s.receipt_rate:
                    res["receipt"] = "rate"
                elif not prefs(s, me)["receipts"]:
                    res["receipt"] = "off"
                else:
                    loc = {k[7:]: v for k, v in keys.items() if k[7:] in ch["members"] and k[7:] != me}
                    for u, v in loc.items():
                        if prefs(s, u)["receipts"]:
                            s.receipts.setdefault((cid, u), {})[me] = v
                    res["receipt"] = "ok" if loc else "none"
            return s._reply(200, res)
    return None
