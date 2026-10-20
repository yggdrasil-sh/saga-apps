"""Nachgebauter Saga-2.5-Server, Teil Chat-Grundlagen (saga/chat_x3.py) auf dem Foederations-Schein (mockfed.py): Chats der
Stufe 3 (v 3, x3_ok), features chat3/react/edit/voice mit x3_limits, Reaktionen (PUT .../reaction, rx in GET .../messages,
Signatur geprueft wie der Server), Bearbeitungen (POST kind edit -> am Original als edit/edited_at/edit_n, Regeln wie der
Server: nur der Absender, hoechstens 20), Sprachnachrichten (Uploads saga-file/v2, Range).
Einschalten: mockx3.enable(srv) - danach mockfed.new_fed_chat(..., extra=mockx3.V3) fuer einen Chat der Stufe 3."""
import json
import secrets
import time

import mockfed
import mockv2
from hlidskjalf import chatcrypto as C

V3 = {"v": 3, "x3_ok": True, "home_self": True}
LIMITS = {"edit_window": 86400, "edit_max": 20, "emoji_max": 32, "react_ct_max": 400, "react_rate": 120, "voice_max_s": 900,
          "voice_kbps": 24, "voice_max_bytes": 4194304}


def enable(s):
    mockfed.enable(s)
    s.x3 = {"react_log": [], "edit_log": [], "uploads": {}}
    if getattr(mockv2, "_x3_wrapped", False):
        return
    orig = mockv2.handle

    def handle(s2, method, path, parts, q, b, raw):
        if getattr(s2, "x3", None) is not None:
            r = _api(s2, method, path, parts, q, b, raw)
            if r is not None:
                return r
        r = orig(s2, method, path, parts, q, b, raw)
        if getattr(s2, "x3", None) is not None and path == "api/chat/me" and r and r[0] == 200:
            d = json.loads(r[1])
            d["features"] = list(d.get("features") or []) + ["chat3", "react", "edit", "voice"]
            d["x3_limits"] = dict(LIMITS)
            d.setdefault("limits", {})["file2"] = True
            return s2._reply(200, d)
        if getattr(s2, "x3", None) is not None and path == "api/chats" and method == "GET" and r and r[0] == 200:
            return r
        return r
    mockv2.handle = handle
    mockv2._x3_wrapped = True
    om = mockfed._msg

    def msg(s2, m, me, device):
        d = om(s2, m, me, device)
        if getattr(s2, "x3", None) is None:
            return d
        if m.get("rx"):
            d["rx"] = [dict(x) for x in m["rx"].values()]
        if m.get("edit"):
            e = m["edit"]
            d["edit"] = om(s2, e, me, device)
            d["edit"].update(edit_of=m["id"], kind="edit")
            d["edited_at"], d["edit_n"] = m["edited_at"], m["edit_n"]
        return d
    mockfed._msg = msg


def _sign_pk(s, handle, dev):
    if handle == s.fed["me"]:
        d = s.cdevices.get(dev)
        return C.unb64(d["sign_pub"], 32) if d else None
    p = s.fed["remote"].get(handle)
    return bytes(p["dev"]["sign_pk"]) if p and p["dev"]["id"] == dev else None


def _find(ch, mid):
    return next((m for m in ch["msgs"] if m["id"] == mid and m["kind"] == "msg"), None)


def _api(s, method, path, parts, q, b, raw):
    if parts[:2] != ["api", "chats"] or len(parts) < 4 or not parts[2].isdigit():
        return None
    ch = s.chats.get(int(parts[2]))
    if not ch or not ch.get("fed"):
        return None
    f, rest = ch["fed"], parts[3:]
    if len(rest) == 3 and rest[0] == "messages" and rest[2] == "reaction" and method == "PUT":
        m = _find(ch, rest[1])
        if m is None:
            return s._reply(404, {"error": "Nachricht nicht gefunden."})
        r = {"by": s.fed["me"], "dev": b.get("dev"), "ts": b.get("ts"), "ct": b.get("ct"), "sig": b.get("sig")}
        pk = _sign_pk(s, r["by"], r["dev"])
        if pk is None:
            return s._reply(403, {"error": "fremdes Geraet"})
        if not C.verify_reaction(f["ref"], m["id"], r, pk):
            return s._reply(400, {"error": "Signatur"})
        old = (m.setdefault("rx", {})).get(r["by"])
        if old is None or old["ts"] <= r["ts"]:
            m["rx"][r["by"]] = r
        s.x3["react_log"].append(dict(r, id=m["id"]))
        return s._reply(200, {"ok": True})
    if rest == ["reactions"] and method == "GET":
        mids = (q.get("mids") or "").split(",")
        return s._reply(200, {"reactions": {mid: list((_find(ch, mid) or {}).get("rx", {}).values()) for mid in mids if _find(ch, mid)}})
    if rest == ["messages"] and method == "POST" and b.get("kind") == "edit":
        o = _find(ch, b.get("edit_of") or "")
        if o is None:
            return s._reply(404, {"error": "Nachricht nicht gefunden."})
        if o.get("sender_handle") != s.fed["me"] or o.get("ai") or int(o.get("edit_n") or 0) >= LIMITS["edit_max"] \
                or time.time() - o["created"] > LIMITS["edit_window"] or b.get("files") or b.get("reply_to"):
            return s._reply(403, {"error": "Nicht erlaubt."})
        d = s.cdevices.get(b.get("sender_device"))
        e = {"id": b["id"], "ts": b["ts"], "sender_handle": s.fed["me"], "sender_device": b["sender_device"], "body_ct": b["body_ct"],
             "sig": b["sig"], "reply_to": None, "ai": None}
        if not d or not C.sig_ok_v2(f["ref"], e, C.unb64(d["sign_pub"], 32)):
            return s._reply(400, {"error": "Ungültige Signatur."})
        if set(b.get("keys") or {}) != mockfed._want(s, ch):
            return s._reply(409, {"error": "Die Mitglieder oder ihre Geräte haben sich geändert.", "handles": [s.fed["me"], f["peer"]]})
        s.chat_seq += 1
        e.update(seq=s.chat_seq, kind="edit", sender=s.me, keys=dict(b["keys"]), created=time.time(), fseq=None, fstate=None)
        o["edit"], o["edited_at"], o["edit_n"] = e, time.time(), int(o.get("edit_n") or 0) + 1
        s.x3["edit_log"].append(e)
        return s._reply(200, {"ok": True, "seq": e["seq"]})
    return None


def peer_key(s, ch, m):
    """Nachrichtenschluessel K einer Nachricht aus Sicht der fremden Person (ihr Geraet)"""
    p = s.fed["remote"][ch["fed"]["peer"]]
    sealed = m["keys"].get(f"dev:{p['dev']['id']}@{p['host']}")
    return C.unseal(sealed, p["dev"]["enc_pk"], p["dev"]["enc_sk"])


def peer_react(s, cid, mid, emoji, ts=None, forge=False):
    """Reaktion der fremden Person (wie ihr Server sie weiterreicht) -> Eintrag"""
    ch = s.chats[cid]
    m = _find(ch, mid)
    p = s.fed["remote"][ch["fed"]["peer"]]
    dev = p["dev"] if not forge else dict(p["dev"], sign_sk=C.device()["sign_sk"])
    r = C.encrypt_reaction(peer_key(s, ch, m), ch["fed"]["ref"], mid, p["handle"], dev, emoji, ts=ts)
    m.setdefault("rx", {})[p["handle"]] = r
    return r


def peer_edit(s, cid, mid, text):
    """Bearbeitung der fremden Person an ihrer Nachricht (v2 kind edit) -> Bearbeitung"""
    ch = s.chats[cid]
    o = _find(ch, mid)
    p = s.fed["remote"][ch["fed"]["peer"]]
    ents = [C.check_keys_v2(p["handle"], mockfed._remote_entry(p))]
    mine = mockfed._my_entry(s)
    if mine:
        ents.append(C.check_keys_v2(s.fed["me"], mine))
    enc = C.encrypt_v2(ch["fed"]["ref"], p["handle"], p["dev"], {"edit": mid, "t": text}, C.recipients_v2([e for e in ents if e]))
    enc.pop("_key", None)
    s.chat_seq += 1
    e = {"id": enc["id"], "ts": enc["ts"], "sender_handle": p["handle"], "sender_device": p["dev"]["id"], "body_ct": enc["body_ct"],
         "sig": enc["sig"], "reply_to": None, "ai": None, "seq": s.chat_seq, "kind": "edit", "sender": "@" + p["handle"],
         "keys": enc["keys"], "created": time.time(), "fseq": None, "fstate": None}
    o["edit"], o["edited_at"], o["edit_n"] = e, time.time(), int(o.get("edit_n") or 0) + 1
    return e


def peer_voice(s, cid, data, dur, wf):
    """Sprachnachricht der fremden Person: Datei saga-file/v2 beim Schein-Server, Anhang {voice, dur, wf}"""
    from hlidskjalf import filev2 as F
    import io
    import os
    import tempfile
    fd, path = tempfile.mkstemp(suffix=".ogg")
    os.write(fd, data)
    os.close(fd)
    enc = F.Encryptor(path, size=len(data))
    blob = io.BytesIO()
    for piece in enc.iter_range(0, enc.total - 1):
        blob.write(piece)
    os.remove(path)
    fid = secrets.token_hex(16)
    s.cfiles[fid] = (cid, "@" + s.chats[cid]["fed"]["peer"], blob.getvalue(), None)
    body = {"v": 1, "type": "text", "text": "", "files": [{"id": fid, "k": C.b64(enc.key), "n": "voice.ogg", "m": "audio/ogg; codecs=opus",
                                                            "s": len(data), "x": 2, "cs": enc.cs, "voice": True, "dur": dur,
                                                            "wf": C.b64(bytes(wf))}]}
    return mockfed.peer_send_v2(s, cid, "", body=body), fid
