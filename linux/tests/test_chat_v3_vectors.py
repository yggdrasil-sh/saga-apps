"""Chat-Stufe 3 (Saga 2.5): Reaktionen, Bearbeiten und Vertrauens-Uebernahme gegen tests/vectors/chat_v3.json aus dem
Server-Repo (tools/chat_vectors_v3.py) - Pflicht: alle Pruefpunkte gruen, auch die Ablehnungs-Faelle. Die Datei wird
unveraendert als tests/chat_v3_vectors.json kopiert. Nachrichten (auch Bearbeitungen) sind saga-chat/v2."""
import base64
import json
import os

import pytest

from hlidskjalf import chatcrypto as C

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chat_v3_vectors.json")
V = json.load(open(PATH, encoding="utf-8"))
ID, DEV, MSG, RX = V["identities"], V["devices"], V["messages"], V["reactions"]


def b(s):
    return base64.b64decode(s)


def dev(n):
    d = DEV[n]
    return C.device(d["id"], bytes.fromhex(d["enc_seed_hex"]), bytes.fromhex(d["sign_seed_hex"]))


def sign_pk(n):
    return b(DEV[n]["sign_pk"])


def api_msg(m):
    return {k: m.get(k) for k in ("id", "ts", "sender_handle", "sender_device", "body_ct", "sig", "reply_to", "ai", "created",
                                  "kind", "edit_of")}


def test_format_and_limits():
    assert V["format"] == "saga-chat/v3" and C.AVAILABLE
    lim = V["limits"]
    assert (C.EMOJI_MAX, C.EDIT_WINDOW, C.EDIT_MAX) == (lim["emoji_max_bytes"], lim["edit_window_s"], lim["edit_max"])


def test_device_keys():
    for n in ("a", "b"):
        assert C.b64(dev(n)["sign_pk"]) == DEV[n]["sign_pk"]
        i = C.identity(bytes.fromhex(ID[n]["ident_seed_hex"]), bytes.fromhex(ID[n]["backup_seed_hex"]))
        assert C.b64(i["ident_pk"]) == ID[n]["ident_pk"]


@pytest.mark.parametrize("name", sorted(MSG))
def test_messages_v2(name):
    m = MSG[name]
    assert C.sig_ok_v2(m["chat_ref"], api_msg(m), sign_pk("a"))
    body = C.decrypt_body_v2(m["chat_ref"], api_msg(m), b(m["key"]))
    assert body == json.loads(m["body_json"])


@pytest.mark.parametrize("name", sorted(RX))
def test_reaction(name):
    r = RX[name]
    K = b(r["msg_key"])
    assert C.react_key(K, r["chat_ref"], r["msg_id"]).hex() == r["react_key_hex"]
    assert C.react_ad(r["chat_ref"], r["msg_id"], r["by"], r["dev"]).hex() == r["ad_hex"]
    assert C.react_sig_msg(r["chat_ref"], r["msg_id"], r["by"], r["dev"], r["ts"], r["ct"]).hex() == r["sig_msg_hex"]
    # Erzeugen (mit der Nonce des Vektors) ergibt genau ct und sig
    mine = C.encrypt_reaction(K, r["chat_ref"], r["msg_id"], r["by"], dev("b"), r["emoji"], ts=r["ts"], nonce=b(r["nonce"]))
    assert mine == {"by": r["by"], "dev": r["dev"], "ts": r["ts"], "ct": r["ct"], "sig": r["sig"]}
    assert C.verify_reaction(r["chat_ref"], r["msg_id"], r, sign_pk("b"))
    assert C.decrypt_reaction(K, r["chat_ref"], r["msg_id"], r) == r["emoji"]
    assert C.react_body(r["body_json"], r["ts"]) == r["emoji"]


def test_open_reactions_newest_wins():
    """je Person zaehlt die neueste: r0 (zurueckgenommen, neuer als r1/r2) -> keine Reaktion; ohne r0 gilt r2"""
    r1, r2, r0 = RX["r1"], RX["r2"], RX["r0"]
    K, ref, mid = b(r1["msg_key"]), r1["chat_ref"], r1["msg_id"]
    pks = {(r1["by"], r1["dev"]): sign_pk("b")}
    items = [{k: x[k] for k in ("by", "dev", "ts", "ct", "sig")} for x in (r1, r2)]
    assert C.open_reactions(K, ref, mid, items, pks) == [{"by": r2["by"], "e": r2["emoji"], "ts": r2["ts"]}]
    items.append({k: r0[k] for k in ("by", "dev", "ts", "ct", "sig")})
    assert C.open_reactions(K, ref, mid, items, pks) == []
    assert C.open_reactions(K, ref, mid, items[:1], {}) == []           # Geraet unbekannt: faellt weg


def _edit(name):
    return V["edit"]["message"] if name == "e1" else V["edits_bad"][name]


@pytest.mark.parametrize("case", V["reject"], ids=lambda c: c["case"])
def test_reject(case):
    w = case["what"]
    if w == "reaction":                                    # Signatur passt nicht mehr (Feld geaendert, fremdes Geraet ...)
        r = dict(RX[case["reaction"]])
        ref, mid, pk = r["chat_ref"], r["msg_id"], sign_pk("b")
        for k in ("by", "dev", "ts"):
            if k in case:
                r[k] = case[k]
        mid = case.get("msg_id", mid)
        ref = case.get("chat_ref", ref)
        if "sign_pk" in case:
            pk = sign_pk(case["sign_pk"])
        assert not C.verify_reaction(ref, mid, r, pk)
        assert C.open_reactions(b(r["msg_key"]), ref, mid, [r], {(r["by"], r["dev"]): pk}) == []
    elif w in ("reaction_open", "reaction_body"):          # Signatur gueltig, Inhalt laesst sich nicht oeffnen bzw. ist falsch
        r, m = case["reaction"], MSG[case["msg"]]
        K = b(m["key"])
        assert C.verify_reaction(m["chat_ref"], m["id"], r, sign_pk("b"))
        with pytest.raises(C.CryptoError):
            C.decrypt_reaction(K, m["chat_ref"], m["id"], r)
        assert C.open_reactions(K, m["chat_ref"], m["id"], [r], {(r["by"], r["dev"]): sign_pk("b")}) == []
        if w == "reaction_body":
            assert C.react_body(r["body_json"], r["ts"]) is None
    elif w == "edit":
        e, orig = _edit(case["edit"]), MSG[case["original"]]
        body = C.decrypt_body_v2(e["chat_ref"], api_msg(e), b(e["key"]))
        assert C.edit_check(api_msg(orig), api_msg(e), body) == case["reason"]
    elif w == "edit_sig":
        e = _edit(case["edit"])
        assert C.sig_ok_v2(e["chat_ref"], api_msg(e), sign_pk("a"))
        assert not C.sig_ok_v2(case["chat_ref"], api_msg(e), sign_pk("a"))
        with pytest.raises(C.CryptoError):
            C.decrypt_body_v2(case["chat_ref"], api_msg(e), b(e["key"]))
    else:
        pytest.fail("unbekannter Fall " + w)


def test_react_body_rules():
    assert C.react_body('{"e":"👍","ts":5}', 5) == "👍"
    assert C.react_body('{"e":"👍","ts":5}', 6) is None
    assert C.react_body('{"e":"👍","ts":5,"x":1}', 5) is None
    assert C.react_body('{"e":"\\u0007","ts":5}', 5) is None
    assert C.react_body(json.dumps({"e": "😀" * 9, "ts": 5}), 5) is None     # 36 Bytes
    assert C.emoji_ok("👩🏽‍💻") and not C.emoji_ok("") and not C.emoji_ok("😀" * 9)


def test_edit_valid():
    e, orig = V["edit"]["message"], MSG[V["edit"]["original"]]
    assert C.sig_ok_v2(e["chat_ref"], api_msg(e), sign_pk("a"))
    body = C.decrypt_body_v2(e["chat_ref"], api_msg(e), b(e["key"]))
    assert body == {"edit": orig["id"], "t": V["edit"]["text"]}
    assert C.edit_check(api_msg(orig), api_msg(e), body) is None


@pytest.mark.parametrize("name,why", [("eb", "other_sender"), ("ex", "edit_of_mismatch"), ("eai", "ai_original"),
                                      ("elate", "window")])
def test_edit_bad(name, why):
    e = V["edits_bad"][name]
    orig = MSG["m1"] if name == "eai" else MSG["m0"]
    pk = sign_pk("b") if e["sender_device"] == DEV["b"]["id"] else sign_pk("a")
    assert C.sig_ok_v2(e["chat_ref"], api_msg(e), pk)          # Signatur stimmt - erst die Regeln lehnen ab
    body = C.decrypt_body_v2(e["chat_ref"], api_msg(e), b(e["key"]))
    assert C.edit_check(api_msg(orig), api_msg(e), body) == why


def _sub(x):
    return {"ident_a": ID["a"]["ident_pk"], "ident_b": ID["b"]["ident_pk"]}.get(x, x)


def _st(d):
    return None if d is None else {k: _sub(v) if isinstance(v, str) else v for k, v in d.items()}


@pytest.mark.parametrize("rule", V["tofu_migrate"]["rules"], ids=lambda r: r["step"])
def test_tofu_migrate(rule):
    res, store = C.tofu_migrate(_st(rule["v1"]), _sub(rule["seen"]), rule.get("ver"))
    assert res == rule["result"]
    if "store" in rule:
        assert store == _st(rule["store"])
    else:
        assert store is None
