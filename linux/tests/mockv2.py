"""Nachgebauter Server, Teil Saga 2.0: Live-Kanal (SSE), Benachrichtigungen, Anwesenheit, Chat (E2E - der Server prueft wie
saga/chat.py Signaturen und Empfaenger, sieht aber nur Ciphertext), Mitteilungen (Push), KI-Assistent (Strom),
Foederation (Seiten mit negativen IDs). Wird von mockserver.MockServer eingebunden.

    srv.push_event("notification", {...})        # Live-Ereignis an die App
    srv.chat_peer("anna")                         # Gegenueber mit Chat-Schluesseln (Identitaet + Geraet)
    srv.peer_send(cid, "anna", "Hallo")           # Nachricht von anna (echt verschluesselt an alle Empfaenger)
    srv.ai_reply = ["Antwort ", "mit Quelle [1]."]   # Strom-Teile fuer den Assistenten
"""
import json
import queue
import secrets
import time

from hlidskjalf import chatcrypto as C


def init(s):
    # Live + Benachrichtigungen
    s.events = queue.Queue()
    s.live_api = True
    s.streams = 0
    s.notifs = []                 # neueste zuerst
    s.notif_kinds = {k: True for k in ("share.added", "share.role", "comment.new", "comment.reply", "publish.withdrawn", "chat.message")}
    s.presence_log = []
    s.presence_users = []         # andere Personen auf der Seite (fuer /api/presence)
    s.presence_rev = {}           # note_id -> rev, die /api/presence meldet
    # Chat
    s.chat_enabled = True
    s.chat_api = True
    s.idents = {}                 # uid -> {ident_pub, backup_pub, backup_sig, bundle}
    s.cdevices = {}               # id -> {uid, name, enc_pub, sign_pub, sig, created, last_seen, revoked}
    s.links = {}                  # id -> {...}
    s.chats = {}                  # id -> {id, kind, members, group, note_id, created, retention_s, muted:set, last_read:{}, msgs:[]}
    s.cfiles = {}                 # id -> (chat, uid, bytes, seq)
    s.peers = {}                  # Gegenueber im Test: uid -> {ident, device}
    s.reauth_need = None
    s.chat_seq = 0
    # Push
    s.push_api = True
    s.push_server = {"enabled": True, "webpush": True, "vapid_public": "BDummy", "ntfy": True, "unifiedpush": True, "apns": False,
                     "apns_bundle": None}
    s.push_settings = {"cats": {"share": True, "comment": True, "chat": True, "publish": True}, "names": False,
                       "quiet": {"on": False, "from": "22:00", "to": "07:00", "tz": "Europe/Berlin"}}
    s.push_subs = []
    # KI
    s.ai_status = {"enabled": False}
    s.ai_reply = ["Die Seite beschreibt den Plan ", "für den Garten [1]."]
    s.ai_sources = [{"n": 1, "id": 1, "title": "Startseite"}]
    s.ai_done = {"quota_left": 17}
    s.ai_log = []
    s.ai_history = {"history": False, "items": []}
    # Foederation
    s.fed = {}                    # negative id -> Seite (mit remote)
    s.fed_unreachable = []
    s.fed_api = True


# --- Live --------------------------------------------------------------------------------------------------------
class FakeStream:
    """Antwort mit readline(): Zeilen aus einer Warteschlange (Live) bzw. einer festen Liste (KI)"""

    def __init__(self, lines=None, q=None, srv=None):
        self.lines = list(lines or [])
        self.q = q
        self.srv = srv
        self.closed = False
        self.fp = None

    def readline(self):
        if self.closed:
            return b""
        if self.q is None:
            if not self.lines:
                return b""
            time.sleep(0.01)
            return self.lines.pop(0)
        while not self.closed:
            try:
                x = self.q.get(timeout=0.2)
            except queue.Empty:
                continue
            if x is None:
                return b""
            if self.closed:                          # inzwischen geschlossen: Zeile fuer die neue Verbindung lassen
                self.q.put(x)
                return b""
            return x
        return b""

    def read(self):
        return b""

    def close(self):
        if not self.closed and self.q is not None and self.srv is not None:
            self.srv.streams -= 1
        self.closed = True


def _sse(ev, data, eid=None):
    out = ""
    if eid:
        out += f"id: {eid}\n"
    out += f"event: {ev}\ndata: {json.dumps(data)}\n\n"
    return [x.encode() + b"\n" for x in out.split("\n")[:-1]]


def push_event(s, ev, data):
    s.chat_seq += 1
    for line in _sse(ev, data, f"boot-{s.chat_seq}"):
        s.events.put(line)


def open_stream(s, method, url, data, headers):
    path = url.split("://", 1)[1].split("/", 1)[1] if "://" in url else url
    path, _sep, qs = path.partition("?")
    s.log.append((method, url, json.loads(data) if data else None, headers.get("Cookie")))
    if path == "api/events":
        if not s.live_api:
            return 404, FakeStream([json.dumps({"error": "not found"}).encode()])
        s.streams += 1
        st = FakeStream(q=s.events, srv=s)
        for line in _sse("hello", {"boot": "boot", "ping": 25, "presence_ttl": 45}):
            s.events.put(line)
        return 200, st
    if path.startswith("api/ai/"):
        b = json.loads(data or b"{}")
        s.ai_log.append((path, b))
        if not s.ai_status.get("enabled"):
            return 403, FakeStream([json.dumps({"error": "Der Assistent ist für dich nicht eingeschaltet."}).encode()])
        lines = [b": " + b"x" * 64 + b"\n", b"\n"]
        lines += _sse("sources", {"sources": s.ai_sources if path == "api/ai/ask" else []})
        for t in s.ai_reply:
            lines += _sse("delta", {"t": t})
        done = dict(s.ai_done)
        if path.startswith("api/ai/page/"):
            act = b.get("action")
            if act == "tasks":
                done["tasks"] = ["Hochbeet bauen", "Erde bestellen"]
            if act in ("improve", "translate"):
                done["blocks"] = [{"id": x, "type": "paragraph", "replaceable": True} for x in b.get("blocks") or []]
                done["segments"] = [f"Verbessert {i + 1}" for i in range(len(b.get("blocks") or []))]
        lines += _sse("done", done)
        return 200, FakeStream(lines)
    return 404, FakeStream([b'{"error": "not found"}'])


# --- Chat-Hilfen -----------------------------------------------------------------------------------------------------
def chat_peer(s, uid, name=None, ready=True):
    """Gegenueber mit Identitaet und einem Geraet anlegen (wie dessen App es taete)"""
    if name:
        s.names[uid] = name
    if not ready:
        return None
    for d in s.cdevices.values():                            # neu begonnen: alte Geraete abgemeldet (wie der Server)
        if d["uid"] == uid and not d["revoked"]:
            d["revoked"] = time.time()
    ident, dev = C.identity(), C.device()
    s.idents[uid] = {"ident_pub": C.b64(ident["ident_pk"]), "backup_pub": C.b64(ident["backup_pk"]),
                     "backup_sig": C.b64(C.sign(C.backup_msg(uid, ident["backup_pk"]), ident["ident_sk"])),
                     "bundle": C.seal_bundle(uid, C.recovery_key(), ident, ops=1, mem=8192)}
    api = C.device_api(uid, ident["ident_sk"], dev, f"Gerät von {uid}")
    s.cdevices[dev["id"]] = dict(api, uid=uid, created=time.time() - 86400, last_seen=time.time() - 60, revoked=None)
    s.peers[uid] = {"ident": ident, "device": dev}
    return s.peers[uid]


def new_chat(s, kind, members, **kw):
    cid = max(s.chats or [0]) + 1
    s.chats[cid] = {"id": cid, "kind": kind, "members": list(members), "created": time.time() - 3600, "retention_s": 0,
                    "muted": set(), "last_read": {}, "msgs": [], "group": kw.get("group"), "note_id": kw.get("note_id"),
                    "admin": kw.get("admin") or (members[0] if kind == "free" else None), "title_seq": None}
    return cid


def _recipients(s, mem):
    out, want = [], set()
    for u in mem:
        e = {"username": u, "name": s.names.get(u, u), "ready": u in s.idents}
        if u in s.idents:
            i = s.idents[u]
            devs = [dict(id=d, **{k: x[k] for k in ("enc_pub", "sign_pub", "sig")}) for d, x in s.cdevices.items()
                    if x["uid"] == u and not x["revoked"]]
            e.update(ident_pub=i["ident_pub"], backup_pub=i["backup_pub"], backup_sig=i["backup_sig"], devices=devs)
            want.add("backup:" + u)
            want.update(d["id"] for d in devs)
        out.append(e)
    return out, want


def peer_send(s, cid, uid, text, reply_to=None, files=None, forge=False, kind="msg", body=None):
    """Nachricht eines Gegenuebers (echte Verschluesselung an alle aktuellen Empfaenger)"""
    p = s.peers[uid]
    ch = s.chats[cid]
    rc, _want = _recipients(s, ch["members"])
    rmap = {}
    for r in rc:
        if r["ready"]:
            rmap["backup:" + r["username"]] = C.unb64(r["backup_pub"])
            for d in r["devices"]:
                rmap[d["id"]] = C.unb64(d["enc_pub"])
    body = body or {"v": 1, "type": "text", "text": text}
    if files:
        body["files"] = files
    enc = C.encrypt(cid, uid, p["device"], body, rmap, reply_to=reply_to, ts=int(time.time() * 1000))
    if forge:                                                # Signatur kaputt: "Echtheit nicht bestaetigt"
        enc["sig"] = C.b64(bytes(64))
    return _store_msg(s, cid, uid, enc, kind)


def _store_msg(s, cid, uid, enc, kind="msg", files=None):
    ch = s.chats[cid]
    s.chat_seq += 1
    seq = s.chat_seq
    m = {"seq": seq, "id": enc["id"], "kind": kind, "sender": uid, "sender_device": enc["sender_device"], "ts": enc["ts"],
         "created": time.time(), "expires_at": None, "body_ct": enc["body_ct"], "sig": enc["sig"], "reply_to": enc.get("reply_to"),
         "keys": dict(enc["keys"])}
    if kind == "title":
        ch["msgs"] = [x for x in ch["msgs"] if x["seq"] != ch.get("title_seq")]
        ch["title_seq"] = seq
    ch["msgs"].append(m)
    for f in files or []:
        if f in s.cfiles:
            c, u, data, _ = s.cfiles[f]
            s.cfiles[f] = (c, u, data, seq)
    if kind == "msg":
        ch["last_read"][uid] = seq
    return m


def _senders(s, msgs):
    devs, ids = {}, {}
    for m in msgs:
        d = s.cdevices.get(m["sender_device"])
        if d:
            devs[m["sender_device"]] = {"uid": d["uid"], "enc_pub": d["enc_pub"], "sign_pub": d["sign_pub"], "sig": d["sig"],
                                        "revoked": bool(d["revoked"])}
            if d["uid"] in s.idents:
                ids[d["uid"]] = s.idents[d["uid"]]["ident_pub"]
    return devs, ids


def _msg_api(s, m, me, device):
    key, via = (m["keys"].get(device), "device") if device in m["keys"] else (m["keys"].get("backup:" + me), "backup")
    return {"seq": m["seq"], "id": m["id"], "kind": m["kind"], "sender": {"username": m["sender"], "name": s.names.get(m["sender"], m["sender"])},
            "sender_device": m["sender_device"], "ts": m["ts"], "created": m["created"], "expires_at": m["expires_at"],
            "body_ct": m["body_ct"], "sig": m["sig"], "reply_to": m["reply_to"], "key": key, "key_for": via}


def _chat_dict(s, ch, me, device=None):
    nm = lambda u: s.names.get(u, u)  # noqa: E731
    msgs = [m for m in ch["msgs"] if m["kind"] == "msg"]
    lr = ch["last_read"].get(me, 0)
    d = {"id": ch["id"], "kind": ch["kind"], "group": ch.get("group"), "note_id": ch.get("note_id"), "created": ch["created"],
         "last_at": msgs[-1]["created"] if msgs else ch["created"], "last_seq": msgs[-1]["seq"] if msgs else 0,
         "retention_s": ch["retention_s"], "muted": me in ch["muted"], "role": "admin" if ch.get("admin") == me else "member",
         "last_read": lr, "unread": sum(1 for m in msgs if m["seq"] > lr and m["sender"] != me),
         "members": [{"username": u, "name": nm(u), "me": u == me} for u in ch["members"]], "member_count": len(ch["members"])}
    if ch["kind"] == "dm":
        other = next(u for u in ch["members"] if u != me)
        d["name"] = nm(other)
        d["peer"] = {"username": other, "name": nm(other)}
    elif ch["kind"] == "group":
        d["name"] = ch["group"]
    elif ch["kind"] == "page":
        d["name"] = (s.notes.get(ch["note_id"]) or s.foreign.get(ch["note_id"]) or {}).get("title") or "Seite"
    else:
        d["name"] = None
        if ch.get("title_seq"):
            tm = next((m for m in ch["msgs"] if m["seq"] == ch["title_seq"]), None)
            if tm and (device in tm["keys"] or "backup:" + me in tm["keys"]):
                devs, ids = _senders(s, [tm])
                d["title_msg"] = dict(_msg_api(s, tm, me, device), device=devs.get(tm["sender_device"]), ident_pub=ids.get(tm["sender"]))
    return d


def _chat_api(s, method, path, parts, q, b, raw):
    me = s.me
    if path == "api/chat/me":
        dev = q.get("device") or ""
        devs = [{"id": i, "name": d["name"], "created": d["created"], "last_seen": d["last_seen"], "revoked": bool(d["revoked"]),
                 "current": i == dev, "enc_pub": d["enc_pub"], "sign_pub": d["sign_pub"], "sig": d["sig"]}
                for i, d in s.cdevices.items() if d["uid"] == me]
        return s._reply(200, {"enabled": s.chat_enabled, "uid": me, "name": s.names.get(me, me), "identity": s.idents.get(me),
                              "devices": devs, "links": sum(1 for x in s.links.values() if not x.get("approved")),
                              "reauth": s.reauth_need,
                              "limits": {"msg_max": 65536, "file_max": 25 * 1048576, "members_max": 100, "devices_max": 10,
                                         "files_per_msg": 10, "retention_max": 0,
                                         "retentions": [0, 3600, 86400, 604800, 2592000, 7776000, 31536000]}})
    if path == "api/chat/identity" and method == "PUT":
        if me in s.idents and not b.get("reset"):
            return s._reply(409, {"error": "Chats sind für dieses Konto schon eingerichtet."})
        if me in s.idents and b.get("reset") and s.reauth_need and not (s.reauth_need == "password" and b.get("password") == "pw"):
            return s._reply(403, {"error": "Zum Neubeginnen bitte dein Passwort eingeben.", "reauth": s.reauth_need})
        if not C.backup_ok(me, b["ident_pub"], b["backup_pub"], b["backup_sig"]):
            return s._reply(400, {"error": "Ungültige Signatur des Backup-Schlüssels."})
        d = b["device"]
        if not C.device_ok(me, b["ident_pub"], d):
            return s._reply(400, {"error": "Ungültige Signatur des Geräts."})
        if me in s.idents:
            for x in s.cdevices.values():
                if x["uid"] == me:
                    x["revoked"] = time.time()
        s.idents[me] = {k: b[k] for k in ("ident_pub", "backup_pub", "backup_sig", "bundle")}
        s.cdevices[d["id"]] = dict(d, uid=me, created=time.time(), last_seen=time.time(), revoked=None)
        return s._reply(200, {"ok": True, "identity": s.idents[me]})
    if path == "api/chat/bundle" and method == "PUT":
        i = s.idents.get(me)
        if not C.verify_b64(b.get("sig"), C.lp(C.V + "bundle-update", me, b.get("bundle") or ""), i["ident_pub"]):
            return s._reply(403, {"error": "Ungültige Signatur."})
        i["bundle"] = b["bundle"]
        return s._reply(200, {"ok": True})
    if path == "api/chat/devices" and method == "POST":
        i = s.idents.get(me)
        if not i or not C.device_ok(me, i["ident_pub"], b):
            return s._reply(400, {"error": "Ungültige Signatur des Geräts."})
        s.cdevices[b["id"]] = dict(b, uid=me, created=time.time(), last_seen=time.time(), revoked=None)
        return s._reply(200, {"ok": True, "id": b["id"]})
    if parts[:3] == ["api", "chat", "devices"] and len(parts) == 4 and method == "DELETE":
        d = s.cdevices.get(parts[3])
        if not d or d["uid"] != me:
            return s._reply(404, {"error": "not found"})
        d["revoked"] = time.time()
        return s._reply(200, {"ok": True})
    if path == "api/chat/link" and method == "POST":
        lid = secrets.token_hex(16)
        s.links[lid] = dict(b, device_id=b["id"], id=lid, created=time.time(), expires=time.time() + 600)
        return s._reply(200, {"id": lid, "expires": s.links[lid]["expires"]})
    if path == "api/chat/link" and method == "GET":
        return s._reply(200, {"links": [{k: x[k] for k in ("id", "device_id", "name", "enc_pub", "sign_pub", "created", "expires")}
                                        for x in s.links.values() if not x.get("approved")]})
    if parts[:3] == ["api", "chat", "link"] and len(parts) == 4:
        x = s.links.get(parts[3])
        if method == "GET":
            if not x:
                return s._reply(200, {"state": "gone"})
            if x.get("approved"):
                s.links.pop(parts[3], None)
                return s._reply(200, {"state": "approved", "sealed": x["sealed"]})
            return s._reply(200, {"state": "pending", "expires": x["expires"]})
        if method == "POST":
            i = s.idents.get(me)
            d = {"id": x["device_id"], "enc_pub": x["enc_pub"], "sign_pub": x["sign_pub"], "sig": b.get("sig")}
            if not C.device_ok(me, i["ident_pub"], d):
                return s._reply(400, {"error": "Ungültige Signatur des Geräts."})
            s.cdevices[x["device_id"]] = dict(d, name=x.get("name"), uid=me, created=time.time(), last_seen=time.time(), revoked=None)
            x.update(approved=time.time(), sealed=b["sealed"])
            return s._reply(200, {"ok": True})
        if method == "DELETE":
            s.links.pop(parts[3], None)
            return s._reply(200, {"ok": True, "deleted": 1})
    if path == "api/chat/keys":
        out = {}
        for u in (q.get("users") or "").split(","):
            rc, _w = _recipients(s, [u])
            out[u] = rc[0] if rc and rc[0]["ready"] else None
        return s._reply(200, {"users": out})
    if path == "api/chat/people":
        qq = (q.get("q") or "").lower()
        if len(qq) < 3:
            return s._reply(200, {"users": [], "groups": []})
        users = [{"username": u, "name": n, "ready": u in s.idents} for u, n in s.names.items()
                 if u != me and (u.startswith(qq) or qq in n.lower())]
        return s._reply(200, {"users": users, "groups": [{"name": g} for g, m in s.groups.items() if me in m and qq in g]})
    if path == "api/chat/notes":
        out = {}
        for x in (q.get("ids") or "").split(","):
            if x.isdigit() and int(x) in s.notes:
                n = s.notes[int(x)]
                out[x] = {"id": n["id"], "title": n.get("title") or "Unbenannt", "icon": n.get("icon") or ""}
        return s._reply(200, {"notes": out})
    if path == "api/chat/pages":
        qq = (q.get("q") or "").lower()
        return s._reply(200, {"pages": [{"id": n["id"], "title": n.get("title"), "icon": n.get("icon") or ""}
                                        for n in s.notes.values() if qq in (n.get("title") or "").lower()][:10]})
    if path == "api/chats" and method == "GET":
        lst = [c for c in s.chats.values() if me in c["members"]]
        lst.sort(key=lambda c: -(c["msgs"][-1]["created"] if c["msgs"] else c["created"]))
        out = [_chat_dict(s, c, me, q.get("device")) for c in lst]
        return s._reply(200, {"chats": out, "unread": sum(c["unread"] for c in out if not c["muted"])})
    if path == "api/chats/unread":
        out = [_chat_dict(s, c, me) for c in s.chats.values() if me in c["members"]]
        return s._reply(200, {"enabled": s.chat_enabled, "unread": sum(c["unread"] for c in out if not c["muted"])})
    if path == "api/chats" and method == "POST":
        k = b.get("kind")
        if k == "dm":
            ex = next((c for c in s.chats.values() if c["kind"] == "dm" and set(c["members"]) == {me, b["user"]}), None)
            cid = ex["id"] if ex else new_chat(s, "dm", [me, b["user"]])
        elif k == "group":
            ex = next((c for c in s.chats.values() if c["kind"] == "group" and c["group"] == b["group"]), None)
            cid = ex["id"] if ex else new_chat(s, "group", list(s.groups.get(b["group"], [me])), group=b["group"])
        elif k == "page":
            ex = next((c for c in s.chats.values() if c["kind"] == "page" and c["note_id"] == b["note_id"]), None)
            cid = ex["id"] if ex else new_chat(s, "page", [me] + [x["target"] for x in s.shares.get(b["note_id"], []) if x["type"] == "user"],
                                               note_id=b["note_id"])
        else:
            cid = new_chat(s, "free", [me] + list(b.get("members") or []), admin=me)
        return s._reply(200, {"chat": _chat_dict(s, s.chats[cid], me, b.get("device"))})
    if parts[:2] == ["api", "chats"] and len(parts) >= 3 and parts[2].isdigit():
        cid = int(parts[2])
        ch = s.chats.get(cid)
        if ch is None or me not in ch["members"]:
            return s._reply(404, {"error": "not found"})
        rest = parts[3:]
        if not rest and method == "GET":
            return s._reply(200, {"chat": _chat_dict(s, ch, me, q.get("device"))})
        if not rest and method == "PATCH":
            if "muted" in b:
                (ch["muted"].add if b["muted"] else ch["muted"].discard)(me)
            if "retention_s" in b:
                ch["retention_s"] = b["retention_s"]
            return s._reply(200, {"chat": _chat_dict(s, ch, me)})
        if rest == ["recipients"]:
            rc, _w = _recipients(s, ch["members"])
            return s._reply(200, {"chat_id": cid, "recipients": rc})
        if rest == ["messages"] and method == "GET":
            dev = q.get("device") or "-"
            msgs = [m for m in ch["msgs"] if m["kind"] == "msg" and (dev in m["keys"] or "backup:" + me in m["keys"])]
            if q.get("after"):
                msgs = [m for m in msgs if m["seq"] > int(q["after"])]
            elif q.get("before"):
                msgs = [m for m in msgs if m["seq"] < int(q["before"])]
            lim = int(q.get("limit") or 50)
            more = len(msgs) > lim
            msgs = msgs[-lim:] if not q.get("after") else msgs[:lim]
            devs, ids = _senders(s, msgs)
            return s._reply(200, {"chat_id": cid, "messages": [_msg_api(s, m, me, dev) for m in msgs], "more": more, "devices": devs,
                                  "idents": ids, "last_read": ch["last_read"].get(me, 0),
                                  "last_seq": max([m["seq"] for m in ch["msgs"]] or [0])})
        if rest == ["messages"] and method == "POST":
            d = s.cdevices.get(b.get("sender_device"))
            if not d or d["uid"] != me or d["revoked"]:
                return s._reply(403, {"error": "Dieses Gerät ist für Chats nicht (mehr) angemeldet."})
            if not C.verify_b64(b.get("sig"), C.sig_msg(cid, b["id"], me, b["sender_device"], b["ts"], b.get("reply_to"), b["body_ct"]),
                                d["sign_pub"]):
                return s._reply(400, {"error": "Ungültige Signatur."})
            rc, want = _recipients(s, ch["members"])
            if set(b.get("keys") or {}) != want:
                return s._reply(409, {"error": "Die Mitglieder oder ihre Geräte haben sich geändert – bitte erneut senden.", "recipients": rc})
            m = _store_msg(s, cid, me, b, b.get("kind") or "msg", b.get("files"))
            return s._reply(200, {"ok": True, "seq": m["seq"], "created": m["created"], "expires_at": None})
        if len(rest) == 2 and rest[0] == "messages" and method == "DELETE":
            m = next((x for x in ch["msgs"] if x["id"] == rest[1]), None)
            if not m:
                return s._reply(404, {"error": "not found"})
            if m["sender"] != me:
                return s._reply(403, {"error": "Nur eigene Nachrichten lassen sich löschen."})
            ch["msgs"].remove(m)
            return s._reply(200, {"ok": True})
        if rest == ["read"]:
            ch["last_read"][me] = max(ch["last_read"].get(me, 0), int(b.get("seq") or 0))
            out = [_chat_dict(s, c, me) for c in s.chats.values() if me in c["members"]]
            return s._reply(200, {"ok": True, "last_read": ch["last_read"][me], "unread": sum(c["unread"] for c in out if not c["muted"])})
        if rest == ["typing"]:
            return s._reply(200, {"ok": True})
        if rest == ["members"] and method == "POST":
            if ch["kind"] != "free" or ch.get("admin") != me:
                return s._reply(403, {"error": "Dafür fehlt dir die Berechtigung."})
            ch["members"].append(b["user"])
            return s._reply(200, {"chat": _chat_dict(s, ch, me)})
        if len(rest) == 2 and rest[0] == "members" and method == "DELETE":
            ch["members"] = [u for u in ch["members"] if u != rest[1]]
            return s._reply(200, {"ok": True})
        if rest == ["files"] and method == "POST":
            fid = secrets.token_urlsafe(18)
            s.cfiles[fid] = (cid, me, bytes(raw or b""), None)
            return s._reply(200, {"id": fid, "size": len(raw or b"")})
    if parts[:3] == ["api", "chat", "files"] and len(parts) == 4:
        f = s.cfiles.get(parts[3])
        if not f:
            return s._reply(404, {"error": "not found"})
        return s._reply(200, f[2])
    return None


# --- Einstieg ------------------------------------------------------------------------------------------------------
def handle(s, method, path, parts, q, b, raw):
    me = s.me
    if path == "api/notifications" and method == "GET":
        if not s.live_api:
            return s._reply(404, {"error": "not found"})
        lim = int(q.get("limit") or 30)
        items = list(s.notifs)
        if q.get("before"):
            items = [x for x in items if x["id"] < int(q["before"])]
        unread = sum(1 for x in s.notifs if not x.get("read"))
        page = items[:lim] if lim else []
        return s._reply(200, {"items": page, "unread": unread, "more": len(items) > lim and lim > 0,
                              "next": page[-1]["id"] if page and len(items) > lim else None})
    if path == "api/notifications/read":
        ids = set(b.get("ids") or [])
        for x in s.notifs:
            if b.get("all") or x["id"] in ids:
                x["read"] = True
        return s._reply(200, {"ok": True, "unread": sum(1 for x in s.notifs if not x.get("read"))})
    if path == "api/notifications/settings":
        if method == "PUT":
            s.notif_kinds.update({k: bool(v) for k, v in b.items()})
        return s._reply(200, {"kinds": [{"kind": k, "on": v} for k, v in s.notif_kinds.items()]})
    if path == "api/presence":
        s.presence_log.append(dict(b))
        if not s.live_api:
            return s._reply(404, {"error": "not found"})
        if b.get("leave"):
            return s._reply(200, {"ok": True})
        nid = b.get("note_id")
        n = s.notes.get(nid) or s.foreign.get(nid)
        if n is None:
            return s._reply(404, {"error": "not found"})
        users = [dict(u, me=False) for u in s.presence_users] + [{"username": me, "name": s.names.get(me), "editing": bool(b.get("editing")),
                                                                  "me": True}]
        return s._reply(200, {"users": users, "rev": s.presence_rev.get(nid, n.get("rev")), "updated": n.get("updated"), "by": None,
                              "ttl": 45, "live": True})
    # Chat
    if (path.startswith("api/chat/") or path == "api/chat" or path.startswith("api/chats")):
        if not s.chat_api:
            return s._reply(404, {"error": "not found"})
        r = _chat_api(s, method, path, parts, q, b, raw)
        if r is not None:
            return r
    # Push
    if path.startswith("api/push"):
        if not s.push_api:
            return s._reply(404, {"error": "not found"})
        if path == "api/push":
            return s._reply(200, _push_state(s))
        if path == "api/push/settings":
            for k, v in b.items():
                if isinstance(v, dict):
                    s.push_settings.setdefault(k, {}).update(v)
                else:
                    s.push_settings[k] = v
            return s._reply(200, _push_state(s))
        if path == "api/push/subscriptions" and method == "POST":
            sid = len(s.push_subs) + 1
            s.push_subs.append(dict(b, id=sid, this_device=True, created=time.time(), last_ok=None, last_error=None, fails=0,
                                    encrypted=bool(b.get("keys")), where="Linux", app="Saga"))
            return s._reply(201, {"id": sid, "device": s.push_subs[-1], "active": True})
        if parts[:3] == ["api", "push", "subscriptions"] and len(parts) >= 4:
            sid = int(parts[3])
            if len(parts) == 5 and parts[4] == "test":
                return s._reply(200, {"ok": True, "result": "ok", "error": None})
            if method == "DELETE":
                s.push_subs = [x for x in s.push_subs if x["id"] != sid]
                return s._reply(200, {"ok": True})
    # KI (ohne Strom)
    if path == "api/ai/status":
        return s._reply(200, s.ai_status)
    if path == "api/ai/history":
        if method == "DELETE":
            s.ai_history["items"] = []
            return s._reply(200, {"ok": True})
        return s._reply(200, s.ai_history)
    if path == "api/ai/settings":
        s.ai_history["history"] = bool(b.get("history"))
        if not s.ai_history["history"]:
            s.ai_history["items"] = []
        return s._reply(200, {"ok": True, "history": s.ai_history["history"]})
    # Foederation
    if path == "api/fed/shared":
        if not s.fed_api:
            return s._reply(404, {"error": "not found"})
        return s._reply(200, {"items": [_fed_meta(n) for n in s.fed.values()], "unreachable": s.fed_unreachable})
    if len(parts) >= 3 and parts[:2] == ["api", "notes"] and parts[2].startswith("-"):
        nid = int(parts[2])
        n = s.fed.get(nid)
        rest = parts[3:]
        if n is None:
            return s._reply(404, {"error": "not found"})
        if n.get("_down"):
            return s._reply(502, {"error": "Die andere Saga-Instanz ist gerade nicht erreichbar."})
        if not rest and method == "GET":
            return s._reply(200, {k: v for k, v in n.items() if not k.startswith("_")})
        if not rest and method == "PATCH":
            bad = [k for k in b if k not in ("title", "body", "format", "rev", "overwrite")]
            if bad:
                return s._reply(403, {"error": "Bei Seiten anderer Saga-Instanzen lassen sich nur Titel und Inhalt ändern."})
            if b.get("rev") is not None and b["rev"] != n["rev"]:
                return s._reply(409, {"error": "conflict", "rev": n["rev"], "by": "Anna Eigner (anna@b.example)"})
            for k in ("title", "body"):
                if k in b:
                    n[k] = b[k]
            n["rev"] += 1
            return s._reply(200, {"ok": True, "rev": n["rev"], "updated": time.time()})
        if rest == ["comments"] and method == "GET":
            return s._reply(200, {"role": n["role"], "can_comment": n["role"] in ("comment", "edit"), "is_owner": False,
                                  "threads": n.get("_threads", []), "resolved_count": 0, "max": 2000})
        if rest in (["backlinks"],):
            return s._reply(404, {"error": "Für Seiten anderer Saga-Instanzen nicht verfügbar."})
        return s._reply(404, {"error": "Für Seiten anderer Saga-Instanzen nicht verfügbar."})
    if path == "api/share/targets" and "@" in (q.get("q") or ""):
        qq = q.get("q").lstrip("@")
        rem = [{"type": "remote", "target": "@" + qq, "name": f"Bert ({qq})", "host": qq.split("@")[-1]}] if qq.endswith("b.example") else []
        return s._reply(200, {"users": [], "groups": [], "remote": rem})
    return None


def _push_state(s):
    return {"server": s.push_server, "settings": s.push_settings, "categories": ["share", "comment", "chat", "publish"],
            "kinds": {"chat": ["chat.message"]}, "devices": s.push_subs, "limit": 20}


def add_fed(s, nid, title, host="b.example", owner="Anna Eigner", role="edit", blocks=None, parent_id=None):
    """Seite einer anderen Saga-Instanz (negative ID)"""
    handle_ = f"anna@{host}"
    n = {"id": nid, "title": title, "icon": "🌍", "format": "blocks", "rev": 3, "parent_id": parent_id, "kind": None, "role": role,
         "updated": time.time() - 600, "created": time.time() - 86400,
         "body": json.dumps({"blocks": blocks or [{"id": "r1", "type": "paragraph", "data": {"text": "Text auf dem anderen Server."}}]}),
         "owner": {"name": f"{owner} ({handle_})", "username": "@" + handle_, "me": False, "remote": True, "handle": handle_},
         "updated_by": {"name": f"{owner} ({handle_})", "username": "@" + handle_, "me": False, "remote": True, "handle": handle_},
         "remote": {"peer": 1, "host": host, "name": host}, "comment_counts": {}, "comments_open": 0, "comments_unread": 0,
         "shared": True}
    s.fed[nid] = n
    return n


def _fed_meta(n):
    return {"id": n["id"], "title": n["title"], "icon": n.get("icon") or "", "parent_id": n.get("parent_id"), "kind": n.get("kind"),
            "role": n["role"], "root": n.get("parent_id") is None, "unread": False, "updated": n["updated"], "tags": [], "shared": True,
            "pinned": False, "owner": n["owner"], "remote": n["remote"]}

