"""Nachgebauter Saga-2.3-Server, Teil Foederation (Chats ueber Servergrenzen, docs/APP-API-FED.md) fuer die Tests: eigener
Handle mit Zertifikaten v2 (geprueft wie saga/chat_v2.py), Personen anderer Server (eigene Schluessel, signierte
Geraete-Liste), Adressbuch-Suche, Direkt-Chats v2 (Anfrage, Annehmen/Ablehnen/Sperren, Sperrliste), Senden mit exakter
Empfaengerpruefung (409 mit handles), Heim nicht erreichbar (pending), abgewiesen (409), Systemzeilen.
Einschalten: mockfed.enable(srv) - ohne das verhaelt sich der Schein-Server wie bisher (2.2)."""
import json
import secrets
import time

import mockv2
from hlidskjalf import chatcrypto as C

HOST = "saga.test"


def _h(seed):
    a = "abcdefghijklmnopqrstuvwxyz234567"
    return "~" + "".join(a[(seed * 7 + i * 5) % 32] for i in range(16))


def enable(s):
    """Schein-Server kann Foederation 2.3; eigener Handle fuer das Testkonto"""
    s.server_version = "2.3.0"
    s.fed = {"me": _h(1) + "@" + HOST, "certs": {"handle_sig": None, "devices": {}, "dset": None}, "remote": {}, "blocks": [],
             "offline": False, "reject": None, "seen_headers": []}
    if not getattr(mockv2, "_fed_wrapped", False):
        orig = mockv2._chat_dict

        def chat_dict(s2, ch, me, device=None):
            d = orig(s2, ch, me, device)
            return _fed_fields(s2, ch, me, d) if ch.get("fed") else d
        mockv2._chat_dict = chat_dict
        orig_handle = mockv2.handle

        def handle(s2, method, path, parts, q, b, raw):
            if getattr(s2, "fed", None):
                r = _api(s2, method, path, parts, q, b, raw)
                if r is not None:
                    return r
            return orig_handle(s2, method, path, parts, q, b, raw)
        mockv2.handle = handle
        mockv2._fed_wrapped = True


def disable(s):
    s.fed = None


def remote_person(s, name, host="b.example", seed=None, public=None):
    """Person eines anderen Servers mit Identitaet, einem Geraet, Zertifikaten v2 und signierter Geraete-Liste -> Handle"""
    seed = seed if seed is not None else len(s.fed["remote"]) + 2
    h = _h(seed) + "@" + host
    p = {"handle": h, "name": name, "host": host, "public": public, "ver": 0, "devs": []}
    s.fed["remote"][h] = p
    new_keys(s, h)
    return h


def new_keys(s, h, keep_ident=False):
    """"Neu beginnen" bzw. neues Geraet einer fremden Person: neue Schluessel, neue signierte Geraete-Liste"""
    p = s.fed["remote"][h]
    if not keep_ident:
        p["ident"] = C.identity()
        p["devs"] = []
    d = C.device()
    p["devs"].append(d)
    p["dev"] = d
    p["ver"] += 1
    return p


def _remote_entry(p, ids=None, ver=None):
    i = p["ident"]
    ids = ids if ids is not None else [d["id"] for d in p["devs"]]
    return {"v": 2, "handle": p["handle"], "ident_pub": C.b64(i["ident_pk"]), "backup_pub": C.b64(i["backup_pk"]),
            "sig": C.b64(C.handle_sig(p["handle"], i["ident_sk"], i["ident_pk"], i["backup_pk"])),
            "dset": C.devices_sig(p["handle"], i["ident_sk"], ver or p["ver"], ids),
            "devices": [{"id": d["id"], "enc_pub": C.b64(d["enc_pk"]), "sign_pub": C.b64(d["sign_pk"]), "revoked": False,
                         "fed_sig": C.b64(C.device_sig_v2(p["handle"], i["ident_sk"], d))} for d in p["devs"]],
            "source": "remote", "fetched": time.time()}


def _my_devices(s):
    return {i: d for i, d in s.cdevices.items() if d["uid"] == s.me and not d["revoked"]}


def _my_entry(s):
    me = s.me
    idn = s.idents.get(me)
    c = s.fed["certs"]
    if not idn or not c["handle_sig"]:
        return None
    devs = [{"id": i, "enc_pub": d["enc_pub"], "sign_pub": d["sign_pub"], "fed_sig": c["devices"][i], "revoked": False}
            for i, d in _my_devices(s).items() if i in c["devices"]]
    e = {"v": 2, "handle": s.fed["me"], "ident_pub": idn["ident_pub"], "backup_pub": idn["backup_pub"], "sig": c["handle_sig"],
         "devices": devs, "source": "local", "fetched": time.time()}
    if c["dset"]:
        e["dset"] = c["dset"]
    return e


def _v2me(s):
    me = s.me
    idn = s.idents.get(me)
    c = s.fed["certs"]
    h = s.fed["me"]
    cert_ok = bool(idn and c["handle_sig"] and C.verify_b64(c["handle_sig"], C.handle_msg(h, C.unb64(idn["ident_pub"], 32),
                                                                                          C.unb64(idn["backup_pub"], 32)), idn["ident_pub"]))
    devs = _my_devices(s)
    missing = [i for i in devs if i not in c["devices"]]
    want = sorted(devs)
    ds = c["dset"]
    ds_ok = bool(ds and sorted(ds["ids"]) == want)
    return {"handle": h, "host": HOST, "identity": bool(idn), "cert_ok": cert_ok, "missing": missing,
            "devices": [{"id": i, "fed_sig": c["devices"].get(i), "ok": i in c["devices"]} for i in devs],
            "need": bool(idn) and (not cert_ok or bool(missing) or not ds_ok), "device_set": ds, "device_set_ok": ds_ok,
            "device_set_want": want, "federation": True}


def new_fed_chat(s, h, request=False, home_self=True, kind="dm", extra=None):
    """Chat v2 mit einer fremden Person (request: ich bin angefragt); kind 'free'/'page' (2.3, Abschnitt 9) mit extra
    (z. B. invited_by, weitere members) - Felder ueberschreiben die Antwort"""
    cid = mockv2.new_chat(s, kind, [s.me, h])
    ch = s.chats[cid]
    ch["fed"] = {"ref": secrets.token_hex(16) + "@" + (HOST if home_self else s.fed["remote"][h]["host"]), "peer": h,
                 "request": request, "readonly": None, "home_self": home_self, "extra": extra or {}}
    return cid


def _fed_fields(s, ch, me, d):
    f = ch["fed"]
    p = s.fed["remote"][f["peer"]]
    lbl = p["name"] + " · " + p["host"]
    d.update(v=2, fed=True, ref=f["ref"], home=HOST if f["home_self"] else p["host"], home_self=f["home_self"], my_handle=s.fed["me"],
             state="request" if f["request"] else "ok", request=bool(f["request"]), readonly=bool(f["readonly"]),
             readonly_reason=f["readonly"], ai=False, name=p["name"])
    d["peer"] = {"username": "@" + p["handle"], "handle": p["handle"], "name": p["name"], "label": lbl, "host": p["host"], "avatar": None}
    d["members"] = [{"username": me, "name": s.names.get(me, me), "me": True, "handle": s.fed["me"], "host": HOST},
                    {"username": "@" + p["handle"], "name": p["name"], "handle": p["handle"], "host": p["host"], "remote": True,
                     "label": lbl}]
    if ch.get("kind", "dm") != "dm":                       # Gruppe/Seiten-Chat: kein Gegenueber
        d.pop("peer", None)
        d["name"] = None
        d["member_count"] = len(d["members"])
    d.update(f.get("extra") or {})
    return d


def _want(s, ch):
    p = s.fed["remote"][ch["fed"]["peer"]]
    w = {"backup:" + s.fed["me"], "backup:" + p["handle"]}
    w |= {f"dev:{i}@{HOST}" for i in _my_devices(s) if i in s.fed["certs"]["devices"]}
    w |= {f"dev:{d['id']}@{p['host']}" for d in p["devs"]}
    return w


def peer_send_v2(s, cid, text, reply_to=None, body=None, forge=False, ai=None):
    """Nachricht der fremden Person (echte Verschluesselung v2 an alle aktuellen Empfaenger)"""
    ch = s.chats[cid]
    p = s.fed["remote"][ch["fed"]["peer"]]
    ents = [C.check_keys_v2(p["handle"], _remote_entry(p))]
    mine = _my_entry(s)
    if mine:
        ents.append(C.check_keys_v2(s.fed["me"], mine))
    body = body or {"v": 1, "type": "text", "text": text}
    enc = C.encrypt_v2(ch["fed"]["ref"], p["handle"], p["dev"], body, C.recipients_v2([e for e in ents if e]), reply_to=reply_to, ai=ai)
    if forge:
        enc["sig"] = C.b64(bytes(64))
    return _store(s, cid, "@" + p["handle"], p["handle"], enc)


def _store(s, cid, uid, handle, enc, kind="msg", fstate=None):
    m = mockv2._store_msg(s, cid, uid, enc, kind)
    ch = s.chats[cid]
    ch["fseq"] = ch.get("fseq", 0) + (0 if fstate == "pending" else 1)
    m.update(sender_handle=handle, fseq=None if fstate == "pending" else ch["fseq"], fstate=fstate, ai=enc.get("ai"))
    return m


def add_sys(s, cid, data, by=None):
    enc = {"id": secrets.token_hex(16), "sender_device": "", "ts": int(time.time() * 1000), "body_ct": json.dumps(dict(data, v=1)),
           "sig": "", "keys": {}}
    m = mockv2._store_msg(s, cid, by or s.me, enc, "sys")
    m.update(sender_handle=None, fseq=None, fstate=None, ai=None)
    return m


def _msg(s, m, me, device):
    keys = m["keys"]
    dk = f"dev:{device}@{HOST}"
    key, via = (keys.get(dk), "device") if dk in keys else (keys.get("backup:" + s.fed["me"]), "backup")
    return {"seq": m["seq"], "id": m["id"], "kind": m["kind"], "sender": {"username": m["sender"], "name": s.names.get(m["sender"], m["sender"])},
            "sender_device": m["sender_device"], "ts": m["ts"], "created": m["created"], "expires_at": None, "body_ct": m["body_ct"],
            "sig": m["sig"], "reply_to": m["reply_to"], "key": key, "key_for": via if key else None, "fseq": m.get("fseq"),
            "sender_handle": m.get("sender_handle"), "ai": m.get("ai"), "fstate": m.get("fstate")}


def _people(s, q):
    qq = (q or "").strip().lower()
    out = []
    for h, p in s.fed["remote"].items():
        names = [p["name"].lower()] + ([p["public"].lower()] if p.get("public") else [])
        hit = qq in names if len(qq) < 3 else any(qq in n for n in names) or qq == (p.get("public") or "") + "@" + p["host"]
        if hit:
            out.append({"type": "remote", "name": p["name"], "host": p["host"], "label": p["name"] + " · " + p["host"], "handle": h,
                        "target": "@" + (p["public"] + "@" + p["host"] if p.get("public") else h),
                        "pub": p["public"] + "@" + p["host"] if p.get("public") else None, "avatar": None})
    return out


def _api(s, method, path, parts, q, b, raw):
    me = s.me
    if path == "api/version":
        return s._reply(200, {"version": "2.3.0", "min_app": "1.0.0"})
    if path == "api/chat/v2/me":
        return s._reply(200, _v2me(s))
    if path == "api/chat/v2/cert" and method == "PUT":
        idn = s.idents.get(me)
        h = s.fed["me"]
        c = s.fed["certs"]
        if b.get("handle_sig"):
            if not C.verify_b64(b["handle_sig"], C.handle_msg(h, C.unb64(idn["ident_pub"], 32), C.unb64(idn["backup_pub"], 32)), idn["ident_pub"]):
                return s._reply(403, {"error": "Ungültige Signatur."})
            c["handle_sig"] = b["handle_sig"]
        for i, sig in (b.get("devices") or {}).items():
            d = s.cdevices.get(i)
            if not d or not C.verify_b64(sig, C.device_msg_v2(h, i, C.unb64(d["enc_pub"], 32), C.unb64(d["sign_pub"], 32)), idn["ident_pub"]):
                return s._reply(403, {"error": "Ungültige Signatur."})
            c["devices"][i] = sig
        ds = b.get("device_set")
        if ds:
            st = _v2me(s)
            if sorted(ds.get("ids") or []) != st["device_set_want"] or (c["dset"] and ds.get("ver", 0) <= c["dset"]["ver"]):
                return s._reply(409, {"error": "Geräte-Liste passt nicht."})
            if not C.verify_devices(h, C.unb64(idn["ident_pub"], 32), ds):
                return s._reply(403, {"error": "Ungültige Signatur."})
            c["dset"] = ds
        return s._reply(200, _v2me(s))
    if path == "api/chat/v2/keys":
        out = {}
        for h in (q.get("handles") or "").split(","):
            if h == s.fed["me"]:
                out[h] = _my_entry(s)
            elif h in s.fed["remote"]:
                out[h] = _remote_entry(s.fed["remote"][h])
            else:
                out[h] = None
        return s._reply(200, {"keys": out})
    if path == "api/chat/people":
        qq = (q.get("q") or "").lower()
        users = [{"username": u, "name": n, "ready": u in s.idents} for u, n in s.names.items()
                 if len(qq) >= 3 and u != me and not u.startswith("@") and (u.startswith(qq) or qq in n.lower())]
        return s._reply(200, {"users": users, "groups": [{"name": g} for g, m in s.groups.items() if me in m and len(qq) >= 3 and qq in g],
                              "remote": _people(s, q.get("q"))})
    if path == "api/chat/blocks" and method == "GET":
        return s._reply(200, {"blocks": list(s.fed["blocks"])})
    if path == "api/chat/blocks" and method == "DELETE":
        s.fed["blocks"] = [x for x in s.fed["blocks"] if x["target"] != q.get("target")]
        return s._reply(200, {"ok": True})
    if path == "api/account/federation":
        if method == "PUT":
            s.fed["directory"] = bool(b.get("directory"))
        return s._reply(200, {"directory": s.fed.get("directory", True), "federation": True, "peers": 1, "handle": s.fed["me"]})
    if path == "api/chats" and method == "POST" and b.get("handle"):
        h = b["handle"]
        if h not in s.fed["remote"]:
            return s._reply(404, {"error": "Person nicht gefunden."})
        ex = next((c for c in s.chats.values() if (c.get("fed") or {}).get("peer") == h), None)
        cid = ex["id"] if ex else new_fed_chat(s, h)
        return s._reply(200, {"chat": mockv2._chat_dict(s, s.chats[cid], me, b.get("device"))})
    if parts[:2] == ["api", "chats"] and len(parts) >= 4 and parts[2].isdigit():
        ch = s.chats.get(int(parts[2]))
        if not ch or not ch.get("fed"):
            return None
        cid, rest, f = ch["id"], parts[3:], ch["fed"]
        if rest == ["request"] and method == "POST":
            a = b.get("action")
            p = s.fed["remote"][f["peer"]]
            if a == "accept":
                f["request"] = False
            elif a in ("decline", "block"):
                mockv2._pref(ch, me)["hidden"] = True
                if a == "block":
                    inst = b.get("scope") == "instance"
                    s.fed["blocks"].append({"target": p["host"] if inst else p["handle"], "kind": "instance" if inst else "person",
                                            "name": p["name"], "host": p["host"], "label": p["name"] + " · " + p["host"], "created": time.time()})
            return s._reply(200, {"chat": mockv2._chat_dict(s, ch, me)})
        if rest == ["messages"] and method == "GET":
            dev = q.get("device") or "-"
            msgs = [m for m in ch["msgs"] if m["kind"] in ("msg", "sys", "call") and (m["kind"] != "msg" or f"dev:{dev}@{HOST}" in m["keys"]
                                                                                      or "backup:" + s.fed["me"] in m["keys"])]
            if q.get("after"):
                msgs = [m for m in msgs if m["seq"] > int(q["after"])]
            return s._reply(200, {"chat_id": cid, "messages": [_msg(s, m, me, dev) for m in msgs], "more": False, "devices": {}, "idents": {},
                                  "last_read": ch["last_read"].get(me, 0), "last_seq": max([m["seq"] for m in ch["msgs"]] or [0])})
        if rest == ["messages"] and method == "POST":
            s.fed["seen_headers"].append(b.get("v"))
            if b.get("v") != 2:
                return s._reply(400, {"error": "Bitte App aktualisieren."})
            if f["readonly"]:
                return s._reply(403, {"error": f["readonly"]})
            d = s.cdevices.get(b.get("sender_device"))
            if not d or d["uid"] != me or b.get("sender_device") not in s.fed["certs"]["devices"]:
                return s._reply(403, {"error": "Gerät noch ohne Zertifikat v2."})
            m = {"id": b["id"], "ts": b["ts"], "sender_handle": s.fed["me"], "sender_device": b["sender_device"], "body_ct": b["body_ct"],
                 "sig": b["sig"], "reply_to": b.get("reply_to"), "ai": b.get("ai")}
            if not C.sig_ok_v2(f["ref"], m, C.unb64(d["sign_pub"], 32)):
                return s._reply(400, {"error": "Ungültige Signatur."})
            if set(b.get("keys") or {}) != _want(s, ch):
                return s._reply(409, {"error": "Die Mitglieder oder ihre Geräte haben sich geändert.", "handles": [s.fed["me"], f["peer"]]})
            if s.fed["reject"]:
                return s._reply(409, {"error": s.fed["reject"]})
            f["request"] = False                                    # wer schreibt, nimmt an
            pend = s.fed["offline"]
            sm = _store(s, cid, me, s.fed["me"], dict(b, sig=b["sig"]), b.get("kind") or "msg", "pending" if pend else None)
            out = {"ok": True, "seq": sm["seq"], "fseq": sm["fseq"], "created": sm["created"]}
            if pend:
                out["pending"] = True
            return s._reply(200, out)
    return None
