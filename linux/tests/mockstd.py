"""Schein-Server: Saga 2.5 Chat-Standard (docs/APP-API-CHAT.md 6a, 8, 9) - nur mit s.chatstd = True und den Features in
s.extra_features. Felder perm_*/can_*/pins/desc_msg/avatar_msg, Seiten-Chat-Mitglieder (via/removable, role), Steuer-
Nachrichten desc/avatar, Grabsteine (tombs=1), Pins, Sperren, Melden, Alltag-Felder (muted_until, notify, pinned,
unread_mark, hidden), mentions beim Senden, Unfurl, has=file, POST .../hide. Einfache Regeln - genau: der echte Server."""
import time

import mockv2


def init(s):
    s.chatstd = False
    s.std_log = []             # [(methode, pfad, body)]
    s.blocks = []              # [{target, name, local}]
    s.reports = []
    s.unfurl = {}              # url -> Antwort
    s.page_via = {}            # (cid, username) -> via
    s.hidden_msgs = {}         # uid -> {msg_id}


def _p(ch, me):
    return ch.setdefault("std", {}).setdefault(me, {"muted_until": 0, "notify": "all", "pinned": False, "pinned_at": 0,
                                                     "unread_mark": False, "hidden": False})


def chat_fields(s, ch, me, d, device=None):
    """GET /api/chats…: Felder des Chat-Standards ergaenzen"""
    if not getattr(s, "chatstd", False):
        return d
    free = ch["kind"] == "free"
    adm = mockv2.admins(ch) if free else set()
    me_admin = me in adm
    ch.setdefault("perm_info", "all")
    ch.setdefault("perm_send", "all")
    ch.setdefault("pins", [])
    d.update(perm_info=ch["perm_info"] if free else "all", perm_send=ch["perm_send"] if free else "all",
             can_send=not (free and ch["perm_send"] == "admins" and not me_admin),
             can_edit_info=free and (ch["perm_info"] == "all" or me_admin), can_info2=free and (ch["perm_info"] == "all" or me_admin),
             can_perms=free and me_admin, can_pin=not (free and ch["perm_info"] == "admins" and not me_admin),
             can_moderate=(free and me_admin) or (ch["kind"] == "page" and ch.get("note_id") in s.notes), pins=list(ch["pins"]))
    for key in ("desc_seq", "avatar_seq"):
        seq = ch.get(key)
        tm = next((m for m in ch["msgs"] if m["seq"] == seq), None) if seq else None
        if tm and (device in tm["keys"] or "backup:" + me in tm["keys"]):
            devs, ids = mockv2._senders(s, [tm])
            d[key.replace("_seq", "_msg")] = dict(mockv2._msg_api(s, tm, me, device or "-"), device=devs.get(tm["sender_device"]),
                                                  ident_pub=ids.get(tm["sender"]))
    blocked = {b["target"] for b in s.blocks}
    for x in d["members"]:
        x["blocked"] = ("~" + x["username"]) in blocked or x["username"] in blocked
        x.setdefault("ref", x["username"] if x.get("me") else "~" + x["username"])
    if ch["kind"] == "page":
        owner = ch.get("note_id") in s.notes
        d["can_manage"] = owner
        d["can_rename"] = d["can_admin"] = False
        if owner:
            for x in d["members"]:
                via = "owner" if x["me"] else s.page_via.get((ch["id"], x["username"]), "share")
                x["via"] = via
                x["removable"] = via == "share"
    if ch["kind"] == "dm":
        other = next((x for x in d["members"] if not x["me"]), None)
        if other and other["blocked"]:
            d["blocked"] = True
            d["can_send"] = False
    p = _p(ch, me)
    mu = p["muted_until"]
    if mu > 0 and mu < time.time():
        mu = p["muted_until"] = 0
    d.update(muted=mu != 0, muted_until=mu, notify=p["notify"], pinned=p["pinned"], pinned_at=p["pinned_at"],
             unread_mark=p["unread_mark"], hidden=p["hidden"], can_hide=ch["kind"] in ("group", "page"),
             me_ref="~" + me if not ch.get("fed") else None)
    if p["unread_mark"]:
        d["unread"] = max(1, d.get("unread") or 0)
    return d


def list_filter(s, me, chats, q):
    """GET /api/chats: angeheftete oben, ausgeblendete weg (hidden=1: nur sie) -> (liste, hidden_count)"""
    if not getattr(s, "chatstd", False):
        return chats, 0
    hid = [c for c in chats if _p(c, me)["hidden"]]
    if q.get("hidden"):
        return hid, len(hid)
    rest = [c for c in chats if not _p(c, me)["hidden"]]
    pin = sorted([c for c in rest if _p(c, me)["pinned"]], key=lambda c: -_p(c, me)["pinned_at"])
    return pin + [c for c in rest if not _p(c, me)["pinned"]], len(hid)


def patch(s, ch, me, b):
    """PATCH /api/chats/{cid} - Felder des Chat-Standards; -> Fehlerantwort oder None"""
    if not getattr(s, "chatstd", False):
        return None
    s.std_log.append(("PATCH", ch["id"], dict(b)))
    p = _p(ch, me)
    for key in ("perm_info", "perm_send"):
        if key in b:
            if ch["kind"] != "free":
                return s._reply(400, {"error": "Nur in freien Gruppen."})
            if me not in mockv2.admins(ch):
                return s._reply(403, {"error": "Nur Admins ändern die Rechte."})
            if b[key] not in ("all", "admins"):
                return s._reply(400, {"error": "ungültig"})
            ch[key] = b[key]
    if "muted_until" in b:
        v = b["muted_until"]
        p["muted_until"] = 0 if (v != -1 and v < time.time()) else int(v)
    if "notify" in b:
        if b["notify"] not in ("all", "mentions", "none"):
            return s._reply(400, {"error": "ungültig"})
        p["notify"] = b["notify"]
    if "pinned" in b:
        if b["pinned"] and not p["pinned"]:
            n = sum(1 for c in s.chats.values() if me in c["members"] and _p(c, me)["pinned"])
            if n >= 5:
                return s._reply(409, {"error": "Höchstens 5 Chats anheften."})
            p["pinned_at"] = time.time()
        p["pinned"] = bool(b["pinned"])
    if "unread_mark" in b:
        p["unread_mark"] = bool(b["unread_mark"])
    if "hidden" in b:
        if ch["kind"] not in ("group", "page"):
            return s._reply(400, {"error": "Nur Gruppen- und Seiten-Chats lassen sich ausblenden."})
        p["hidden"] = bool(b["hidden"])
    if "muted" in b and "muted_until" not in b:
        p["muted_until"] = -1 if b["muted"] else 0
    return None


def on_read(s, ch, me):
    if getattr(s, "chatstd", False):
        _p(ch, me)["unread_mark"] = False


def msgs_filter(s, ch, me, q, msgs):
    """GET .../messages: has=file, ausgeblendete weg"""
    if not getattr(s, "chatstd", False):
        return msgs
    hid = s.hidden_msgs.get(me) or set()
    msgs = [m for m in msgs if m["id"] not in hid]
    if q.get("has") == "file":
        msgs = [m for m in msgs if m.get("files")]
    return msgs


def tombs(s, ch, q):
    if not getattr(s, "chatstd", False) or not q.get("tombs"):
        return []
    return [m for m in ch.get("tombs") or []]


def handle(s, method, path, parts, q, b):
    """eigene Endpunkte -> Antwort oder None"""
    if not getattr(s, "chatstd", False):
        return None
    me = s.me
    if path == "api/chat/blocks":
        s.std_log.append((method, path, dict(b or {}) if method == "POST" else dict(q)))
        if method == "GET":
            return s._reply(200, {"blocks": [dict(x, kind="person", label=x.get("name"), host="saga.test") for x in s.blocks]})
        if method == "POST":
            tg = b.get("target")
            if not isinstance(tg, str) or not tg.startswith("~"):
                return s._reply(400, {"error": "ungültig"})
            if all(x["target"] != tg for x in s.blocks):
                s.blocks.append({"target": tg, "name": s.names.get(tg[1:].split("@")[0], "?"), "local": True})
            mockv2.push_event(s, "chat.blocks", {"target": tg, "blocked": True})
            return s._reply(200, {"ok": True, "target": tg})
        if method == "DELETE":
            s.blocks = [x for x in s.blocks if x["target"] != q.get("target")]
            return s._reply(200, {"ok": True})
    if path == "api/chat/report" and method == "POST":
        s.reports.append(dict(b))
        return s._reply(200, {"ok": True, "id": len(s.reports)})
    if path == "api/chat/unfurl":
        s.std_log.append(("GET", path, dict(q)))
        if "unfurl1" not in (getattr(s, "extra_features", []) or []):
            return s._reply(403, {"error": "aus"})
        return s._reply(200, s.unfurl.get(q.get("url"), {}))
    if parts[:2] == ["api", "chats"] and len(parts) >= 4 and parts[2].isdigit():
        cid = int(parts[2])
        ch = s.chats.get(cid)
        if ch is None or me not in ch["members"]:
            return None
        rest = parts[3:]
        if rest[0] == "pins" and len(rest) == 2:
            s.std_log.append((method, path, None))
            pins = ch.setdefault("pins", [])
            if method == "POST":
                if rest[1] not in pins:
                    if len(pins) >= 3:
                        return s._reply(409, {"error": "Höchstens 3 angeheftete Nachrichten – erst eine lösen."})
                    pins.append(rest[1])
            else:
                ch["pins"] = pins = [x for x in pins if x != rest[1]]
            mockv2.push_event(s, "chat.changed", {"chat_id": cid})
            return s._reply(200, {"ok": True, "pins": list(pins)})
        if rest == ["hide"] and method == "POST":
            s.std_log.append((method, path, dict(b)))
            got = [m["id"] for m in ch["msgs"] if m["id"] in set(b.get("ids") or [])]
            s.hidden_msgs.setdefault(me, set()).update(got)
            return s._reply(200, {"hidden": len(got)})
        if len(rest) == 2 and rest[0] == "messages" and method == "DELETE":
            m = next((x for x in ch["msgs"] if x["id"] == rest[1]), None)
            if m and m["sender"] != me:
                d = chat_fields(s, ch, me, {"members": [], "id": cid})
                if not d.get("can_moderate"):
                    return s._reply(403, {"error": "Nur eigene Nachrichten lassen sich löschen."})
                ch["msgs"].remove(m)
                ch.setdefault("tombs", []).append({"id": m["id"], "seq": m["seq"], "kind": "tomb", "by_admin": True,
                                                   "sender": {"username": m["sender"], "name": s.names.get(m["sender"])},
                                                   "created": m["created"], "ts": m["ts"]})
                ch["pins"] = [x for x in ch.get("pins") or [] if x != m["id"]]
                mockv2.push_event(s, "chat.deleted", {"chat_id": cid, "id": m["id"], "seq": m["seq"], "by_admin": True})
                return s._reply(200, {"ok": True, "by_admin": True})
        if rest == ["members"] and method == "POST" and ch["kind"] == "page":
            s.member_adds = getattr(s, "member_adds", []) + [dict(b)]
            u = (b.get("user") or "")[1:] if (b.get("user") or "").startswith("~") else b.get("user")
            if ch.get("note_id") not in s.notes:
                return s._reply(403, {"error": "Personen hinzufügen kann nur, wem die Seite gehört."})
            if u and u not in ch["members"]:
                ch["members"].append(u)
            return s._reply(200, {"chat": mockv2._chat_dict(s, ch, me)})
        if len(rest) == 2 and rest[0] == "members" and method == "DELETE" and ch["kind"] == "page":
            who = rest[1][1:] if rest[1].startswith("~") else rest[1]
            if s.page_via.get((cid, who), "share") != "share":
                return s._reply(409, {"error": "{} hat Zugriff über die Gruppe „Team“ – dort entfernen.".format(s.names.get(who, who))})
            ch["members"] = [x for x in ch["members"] if x != who]
            return s._reply(200, {"ok": True})
    return None


def on_send(s, ch, b, kind):
    """POST .../messages: mentions pruefen, Steuer-Nachrichten desc/avatar merken, perm_send durchsetzen -> Fehler | None"""
    if not getattr(s, "chatstd", False):
        return None
    s.std_log.append(("SEND", ch["id"], {k: b.get(k) for k in ("kind", "mentions", "files")}))
    if "mentions" in b and not isinstance(b["mentions"], list):
        return s._reply(400, {"error": "mentions"})
    if kind in ("desc", "avatar"):
        if ch["kind"] != "free":
            return s._reply(400, {"error": "Nur in freien Gruppen."})
        if ch.get("perm_info") == "admins" and s.me not in mockv2.admins(ch):
            return s._reply(403, {"error": "Nur Admins ändern Infos."})
    if kind == "msg" and ch["kind"] == "free" and ch.get("perm_send") == "admins" and s.me not in mockv2.admins(ch):
        return s._reply(403, {"error": "In dieser Gruppe dürfen nur Admins schreiben."})
    return None


def after_store(s, ch, m, kind):
    if not getattr(s, "chatstd", False):
        return
    if kind in ("desc", "avatar"):
        key = kind + "_seq"
        ch["msgs"] = [x for x in ch["msgs"] if x["seq"] != ch.get(key)]
        ch[key] = m["seq"]
