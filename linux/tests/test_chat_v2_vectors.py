"""Chat-Krypto saga-chat/v2 (Saga 2.3, Chats ueber Servergrenzen) gegen tests/vectors/chat_v2.json aus dem Server-Repo
(tools/chat_vectors_v2.py) - Pflicht: alle Pruefpunkte gruen, auch die Ablehnungs-Faelle und die TOFU-Regeln. Die Datei
wird unveraendert als tests/chat_v2_vectors.json kopiert. Die v1-Vektoren (test_chat_vectors.py) bleiben gueltig."""
import base64
import hashlib
import json
import os

import pytest

from hlidskjalf import chatcrypto as C

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chat_v2_vectors.json")
V = json.load(open(PATH, encoding="utf-8"))
I, HC, D, DS, R, M, F, S = V["identity"], V["handle_cert"], V["device"], V["device_set"], V["recipient"], V["messages"], V["file"], V["safety"]
H = I["handle"]


def ident():
    return C.identity(bytes.fromhex(I["ident_seed_hex"]), bytes.fromhex(I["backup_seed_hex"]))


def dev():
    return C.device(D["id"], bytes.fromhex(D["enc_seed_hex"]), bytes.fromhex(D["sign_seed_hex"]))


def keys_entry(handle=H, sig=None, fed_sig=None, dset=None, revoked=False):
    """Eintrag wie GET /api/chat/v2/keys ihn liefert"""
    e = {"v": 2, "handle": handle, "ident_pub": I["ident_pk"], "backup_pub": I["backup_pk"], "sig": sig or HC["sig"],
         "devices": [{"id": D["id"], "enc_pub": D["enc_pk"], "sign_pub": D["sign_pk"], "fed_sig": fed_sig or D["fed_sig"], "revoked": revoked}]}
    if dset is not None:
        e["dset"] = dset
    return e


def api_msg(m, **over):
    d = {"id": m["id"], "ts": m["ts"], "sender_handle": m["sender_handle"], "sender_device": m["sender_device"],
         "body_ct": m["body_ct"], "sig": m["sig"], "reply_to": m["reply_to"], "ai": m["ai"]}
    d.update(over)
    return d


def test_format():
    assert V["format"] == "saga-chat/v2" and C.AVAILABLE


def test_lp():
    assert C.lp(*V["lp"]["fields"]).hex() == V["lp"]["hex"]


def test_keys_from_seeds():
    k, d = ident(), dev()
    assert C.b64(k["ident_pk"]) == I["ident_pk"] and C.b64(k["ident_sk"]) == I["ident_sk"]
    assert C.b64(k["backup_pk"]) == I["backup_pk"] and C.b64(k["backup_sk"]) == I["backup_sk"]
    assert C.b64(d["enc_pk"]) == D["enc_pk"] and C.b64(d["enc_sk"]) == D["enc_sk"]
    assert C.b64(d["sign_pk"]) == D["sign_pk"] and C.b64(d["sign_sk"]) == D["sign_sk"]


def test_handle_cert():
    k = ident()
    assert C.handle_msg(H, k["ident_pk"], k["backup_pk"]).hex() == HC["msg_hex"]
    assert C.b64(C.handle_sig(H, k["ident_sk"], k["ident_pk"], k["backup_pk"])) == HC["sig"]


def test_device_cert():
    k, d = ident(), dev()
    assert C.device_msg_v2(H, d["id"], d["enc_pk"], d["sign_pk"]).hex() == D["msg_hex"]
    assert C.b64(C.device_sig_v2(H, k["ident_sk"], d)) == D["fed_sig"]


def test_device_set():
    k = ident()
    assert C.devices_msg(DS["handle"], DS["ver"], DS["ids"]).hex() == DS["msg_hex"]
    assert C.devices_sig(DS["handle"], k["ident_sk"], DS["ver"], DS["ids"])["sig"] == DS["sig"]
    assert C.verify_devices(DS["handle"], k["ident_pk"], {"ver": DS["ver"], "ids": DS["ids"], "sig": DS["sig"]})
    # Reihenfolge der IDs egal (sortiert signiert)
    assert C.verify_devices(DS["handle"], k["ident_pk"], {"ver": DS["ver"], "ids": list(reversed(DS["ids"])), "sig": DS["sig"]})


def test_check_keys_entry():
    dset = {"ver": DS["ver"], "ids": DS["ids"], "sig": DS["sig"]}
    k = C.check_keys_v2(H, keys_entry(dset=dset))
    assert k and k["ver"] == DS["ver"] and k["signed"] and C.b64(k["ident_pk"]) == I["ident_pk"]
    assert [d["id"] for d in k["devices"]] == [D["id"]] and not k["devices"][0]["revoked"]
    # Geraet nicht in der signierten Liste -> widerrufen, auch wenn der Server es als aktiv meldet
    other = C.devices_sig(H, ident()["ident_sk"], 4, ["ffffffffffffffffffffffffffffffff"])
    k2 = C.check_keys_v2(H, keys_entry(dset=other))
    assert k2["devices"][0]["revoked"] and k2["ver"] == 4
    # ohne gueltige Liste (Altbestand): revoked vom Server
    assert C.check_keys_v2(H, keys_entry(revoked=True))["devices"][0]["revoked"]
    assert C.check_keys_v2(H, keys_entry(dset={"ver": 9, "ids": DS["ids"], "sig": DS["sig"]}))["ver"] == 0
    # doppelte Geraete-IDs: Eintrag verwerfen (unklar, welcher Schluessel gilt - wie iOS/Android)
    dup = keys_entry(dset=dset)
    dup["devices"] = dup["devices"] * 2
    assert C.check_keys_v2(H, dup) is None
    # Empfaenger: Geraet mit Host aus dem Handle + Backup
    assert set(C.recipients_v2([k])) == {f"dev:{D['id']}@a.example", "backup:" + H}
    assert set(C.recipients_v2([k2])) == {"backup:" + H}


@pytest.mark.parametrize("name", ["m1", "m2"])
def test_message(name):
    m = M[name]
    d = dev()
    key, nonce = C.unb64(m["key"], 32), C.unb64(m["nonce"], 24)
    assert C.msg_ad_v2(m["chat_ref"], m["id"], m["sender_handle"], m["sender_device"], m["ts"], m["reply_to"], m["ai"]).hex() == m["ad_hex"]
    enc = C.encrypt_v2(m["chat_ref"], m["sender_handle"], d, None, {}, reply_to=m["reply_to"], ai=m["ai"], mid=m["id"], ts=m["ts"],
                       key=key, nonce=nonce, plain=m["body_json"])
    assert enc["body_ct"] == m["body_ct"]
    assert C.sig_msg_v2(m["chat_ref"], m["id"], m["sender_handle"], m["sender_device"], m["ts"], m["reply_to"], m["ai"],
                        m["body_ct"]).hex() == m["sig_msg_hex"]
    assert enc["sig"] == m["sig"]
    assert C.sig_ok_v2(m["chat_ref"], api_msg(m), d["sign_pk"])
    assert C.decrypt_body_v2(m["chat_ref"], api_msg(m), key) == json.loads(m["body_json"])
    if m["ai"]:                                            # ai = SHA-256 des Antworttexts (mitsigniert)
        assert hashlib.sha256(json.loads(m["body_json"])["text"].strip().encode()).hexdigest() == m["ai"]


def test_sealed_recipients_open():
    """crypto_box_seal ist zufaellig: die Vektoren muessen sich mit den Empfaenger-Schluesseln oeffnen lassen"""
    ri = C.identity(bytes.fromhex(R["identity"]["ident_seed_hex"]), bytes.fromhex(R["identity"]["backup_seed_hex"]))
    rd = C.device(R["device"]["id"], bytes.fromhex(R["device"]["enc_seed_hex"]), bytes.fromhex(R["device"]["sign_seed_hex"]))
    assert C.b64(ri["ident_pk"]) == R["identity"]["ident_pk"] and C.b64(ri["backup_pk"]) == R["identity"]["backup_pk"]
    assert C.b64(rd["enc_pk"]) == R["device"]["enc_pk"]
    rh = R["identity"]["handle"]
    want = C.unb64(M[R["message"]]["key"], 32)
    dev_id = f"dev:{rd['id']}@{C.handle_host(rh)}"
    assert sorted(R["ids"]) == sorted(R["sealed"]) and dev_id in R["sealed"] and "backup:" + rh in R["sealed"]
    assert C.unseal(R["sealed"][dev_id], rd["enc_pk"], rd["enc_sk"]) == want
    assert C.unseal(R["sealed"]["backup:" + rh], ri["backup_pk"], ri["backup_sk"]) == want
    m = M[R["message"]]
    am = api_msg(m, key=R["sealed"][dev_id])
    assert C.decrypt_v2(m["chat_ref"], am, rd["enc_pk"], rd["enc_sk"]) == json.loads(m["body_json"])
    # eigene Versiegelung oeffnet sich genauso
    enc = C.encrypt_v2(m["chat_ref"], m["sender_handle"], dev(), {"x": 1}, C.recipients_v2([{
        "handle": rh, "backup_pk": ri["backup_pk"], "devices": [{"id": rd["id"], "enc_pub": C.b64(rd["enc_pk"]), "revoked": False}]}]))
    assert C.unseal(enc["keys"][dev_id], rd["enc_pk"], rd["enc_sk"]) == enc["_key"]


def test_file():
    key, nonce = C.unb64(F["key"], 32), C.unb64(F["nonce"], 24)
    plain = base64.b64decode(F["plain_b64"])
    assert C.file_ad_v2(F["chat_ref"]).hex() == F["ad_hex"]
    blob, _k = C.encrypt_file_v2(F["chat_ref"], plain, key=key, nonce=nonce)
    assert C.b64(blob) == F["blob"]
    assert C.decrypt_file_v2(F["chat_ref"], C.unb64(F["blob"]), key) == plain
    with pytest.raises(C.CryptoError):
        C.decrypt_file_v2("ffffffffffffffffffffffffffffffff@a.example", C.unb64(F["blob"]), key)
    with pytest.raises(C.CryptoError):                     # v1-Datei-AD passt nicht
        C.decrypt_file(F["chat_ref"], C.unb64(F["blob"]), key)


def test_safety_number():
    n = C.safety_number_v2(S["handle_a"], C.unb64(S["ident_a"], 32), S["handle_b"], C.unb64(S["ident_b"], 32))
    assert n == S["number"]
    assert C.safety_number_v2(S["handle_b"], C.unb64(S["ident_b"], 32), S["handle_a"], C.unb64(S["ident_a"], 32)) == n


@pytest.mark.parametrize("case", V["reject"], ids=lambda c: c["case"])
def test_reject(case):
    w = case["what"]
    k = ident()
    if w == "handle_cert":
        assert C.check_keys_v2(case["handle"], keys_entry(handle=case["handle"], sig=case["sig"])) is None
    elif w == "device_cert":
        e = keys_entry(handle=H, fed_sig=case["fed_sig"]) if case["handle"] == H else None
        if e is not None:                                  # gleicher Handle, falsches Zertifikat (v1 statt v2): Geraet faellt weg
            assert C.check_keys_v2(H, e)["devices"] == []
        ok = C.verify(C.unb64(case["fed_sig"], 64), C.device_msg_v2(case["handle"], D["id"], C.unb64(D["enc_pk"], 32),
                                                                   C.unb64(D["sign_pk"], 32)), k["ident_pk"])
        assert not ok
    elif w == "message":
        m = M[case["message"]]
        over = {x: case[x] for x in ("sender_handle", "ai", "ts") if x in case}
        ref = case.get("chat_ref", m["chat_ref"])
        am = api_msg(m, **over)
        assert not C.sig_ok_v2(ref, am, dev()["sign_pk"])
        with pytest.raises(C.CryptoError):
            C.decrypt_body_v2(ref, am, C.unb64(m["key"], 32))
    elif w == "device_set":
        assert not C.verify_devices(case["handle"], k["ident_pk"], {"ver": case["ver"], "ids": case["ids"], "sig": case["sig"]})
    else:
        pytest.fail("unbekannter Fall " + w)


def _sub(v):
    return {"ident_a": "A", "ident_b": "B"}.get(v, v)


def _st(d):
    return None if d is None else {k: _sub(x) for k, x in d.items()}


@pytest.mark.parametrize("rule", V["tofu"]["rules"], ids=lambda r: r["step"])
def test_tofu(rule):
    if rule["seen"].startswith("invalid"):                 # ungueltiges Zertifikat: gar nicht erst bis TOFU
        bad = keys_entry(handle="~qrstuvwxyz234567@b.example:8443", sig=HC["sig"])
        assert C.check_keys_v2("~qrstuvwxyz234567@b.example:8443", bad) is None
        assert rule["result"] == "reject"
        return
    res, store = C.tofu_v2(_st(rule["stored"]), _sub(rule["seen"]), rule.get("ver"))
    assert res == rule["result"]
    if "store" in rule:
        assert store == _st(rule["store"])
    elif res in ("reject", "block"):
        assert store is None
