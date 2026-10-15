"""Chat-Krypto (Format v1) gegen tests/chat_vectors.json aus dem Server-Repo (tools/chat_vectors.py) - Pflicht: alle
Pruefpunkte gruen. Die Datei wird unveraendert aus ~/src/saga/tests/ kopiert."""
import json
import os

import pytest

from hlidskjalf import chatcrypto as C

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chat_vectors.json")
V = json.load(open(PATH, encoding="utf-8"))
I, D, R, M, F, LK, S = V["identity"], V["device"], V["recovery"], V["message"], V["file"], V["link"], V["safety"]
UID = I["uid"]


def ident():
    return C.identity(bytes.fromhex(I["ident_seed_hex"]), bytes.fromhex(I["backup_seed_hex"]))


def dev():
    return C.device(D["id"], bytes.fromhex(D["enc_seed_hex"]), bytes.fromhex(D["sign_seed_hex"]))


def test_format():
    assert V["format"] == "saga-chat/v1" and C.AVAILABLE


def test_lp():
    assert C.lp(*V["lp"]["fields"]).hex() == V["lp"]["hex"]


def test_base32():
    assert C.base32(bytes.fromhex(V["base32"]["bytes_hex"])) == V["base32"]["text"]


def test_identity_keys():
    k = ident()
    assert C.b64(k["ident_pk"]) == I["ident_pk"] and C.b64(k["ident_sk"]) == I["ident_sk"]
    assert C.b64(k["backup_pk"]) == I["backup_pk"] and C.b64(k["backup_sk"]) == I["backup_sk"]


def test_backup_sig():
    k = ident()
    assert C.backup_msg(UID, k["backup_pk"]).hex() == I["backup_msg_hex"]
    assert C.b64(C.sign(bytes.fromhex(I["backup_msg_hex"]), k["ident_sk"])) == I["backup_sig"]
    assert C.backup_ok(UID, I["ident_pk"], I["backup_pk"], I["backup_sig"])
    assert not C.backup_ok("bert", I["ident_pk"], I["backup_pk"], I["backup_sig"])


def test_device_keys():
    d = dev()
    assert C.b64(d["enc_pk"]) == D["enc_pk"] and C.b64(d["enc_sk"]) == D["enc_sk"]
    assert C.b64(d["sign_pk"]) == D["sign_pk"] and C.b64(d["sign_sk"]) == D["sign_sk"]


def test_device_cert():
    k, d = ident(), dev()
    assert C.device_msg(UID, D["id"], d["enc_pk"], d["sign_pk"]).hex() == D["msg_hex"]
    assert C.b64(C.sign(bytes.fromhex(D["msg_hex"]), k["ident_sk"])) == D["sig"]
    assert C.device_cert(UID, k["ident_sk"], d) == D["sig"]
    api = {"id": D["id"], "enc_pub": D["enc_pk"], "sign_pub": D["sign_pk"], "sig": D["sig"]}
    assert C.device_ok(UID, I["ident_pk"], api)
    assert not C.device_ok(UID, I["ident_pk"], dict(api, id="f" * 32))


def test_recovery_normalize():
    assert C.norm_recovery(R["key"]) == R["normalized"] == C.norm_recovery(R["input_variant"])
    assert C.norm_recovery("zu kurz") is None


def test_recovery_kdf():
    assert C.recovery_kdf(R["key"], C.unb64(R["salt"]), R["ops"], R["mem"]).hex() == R["kdf_hex"]


def test_bundle_seal():
    assert C.lp(C.V + "bundle", UID).hex() == R["ad_hex"]
    assert C.seal_bundle(UID, R["key"], ident(), salt=C.unb64(R["salt"]), nonce=C.unb64(R["nonce"]), plain=R["plain"]) == R["bundle"]
    assert C.bundle_plain(C.unb64(I["ident_sk"]), C.unb64(I["backup_sk"])) == R["plain"]


def test_bundle_open():
    k = ident()
    ob = C.open_bundle(UID, R["input_variant"], R["bundle"])
    assert ob["ident_pk"] == k["ident_pk"] and ob["backup_pk"] == k["backup_pk"] and ob["ident_sk"] == k["ident_sk"]
    with pytest.raises(C.CryptoError):
        C.open_bundle(UID, C.recovery_key(), R["bundle"])          # falscher Schluessel
    with pytest.raises(C.CryptoError):
        C.open_bundle("bert", R["key"], R["bundle"])               # anderes Konto (AD)


def test_message_ad():
    assert C.msg_ad(M["chat_id"], M["id"], M["sender"], M["device"], M["ts"], M["reply_to"]).hex() == M["ad_hex"]


def test_message_encrypt_and_sig():
    enc = C.encrypt(M["chat_id"], M["sender"], dev(), None, {}, reply_to=M["reply_to"], mid=M["id"], ts=M["ts"],
                    key=C.unb64(M["key"]), nonce=C.unb64(M["nonce"]), plain=M["body_json"])
    assert enc["body_ct"] == M["body_ct"]
    assert enc["sig"] == M["sig"]
    assert C.sig_msg(M["chat_id"], M["id"], M["sender"], M["device"], M["ts"], M["reply_to"], M["body_ct"]).hex() == M["sig_msg_hex"]


def _api(key):
    return {"id": M["id"], "ts": M["ts"], "sender": {"username": M["sender"]}, "sender_device": M["device"], "body_ct": M["body_ct"],
            "reply_to": M["reply_to"], "sig": M["sig"], "key": key}


def test_message_open_device():
    d = dev()
    assert C.decrypt(M["chat_id"], _api(M["sealed_device"]), d["enc_pk"], d["enc_sk"]) == json.loads(M["body_json"])
    assert C.sig_ok(M["chat_id"], _api(M["sealed_device"]), D["sign_pk"])
    assert not C.sig_ok("8", _api(M["sealed_device"]), D["sign_pk"])


def test_message_open_backup():
    k = ident()
    assert C.decrypt(M["chat_id"], _api(M["sealed_backup"]), k["backup_pk"], k["backup_sk"]) == json.loads(M["body_json"])
    with pytest.raises(C.CryptoError):                            # anderer Chat (AD) -> nicht entschluesselbar
        C.decrypt("8", _api(M["sealed_backup"]), k["backup_pk"], k["backup_sk"])


def test_file():
    blob, _k = C.encrypt_file(F["chat_id"], C.unb64(F["plain_b64"]), C.unb64(F["key"]), C.unb64(F["nonce"]))
    assert C.b64(blob) == F["blob"]
    assert C.decrypt_file(F["chat_id"], C.unb64(F["blob"]), C.unb64(F["key"])) == C.unb64(F["plain_b64"])


def test_link_code():
    assert C.link_code(LK["uid"], LK["device_id"], C.unb64(LK["enc_pk"]), C.unb64(LK["sign_pk"])) == LK["code"]
    assert len(LK["code"].replace(" ", "")) == 20


def test_link_sealed_bundle():
    d = dev()
    assert C.unseal(LK["sealed_bundle"], d["enc_pk"], d["enc_sk"]).decode() == R["plain"]


def test_safety_number():
    a, b = C.unb64(S["ident_a"]), C.unb64(S["ident_b"])
    assert C.safety_number(S["uid_a"], a, S["uid_b"], b) == S["number"] == C.safety_number(S["uid_b"], b, S["uid_a"], a)
    assert len(S["number"].replace(" ", "")) == 60


def test_roundtrip_fresh_keys():
    """Eigene Schluessel (Zufall): Einrichtung, Buendel, Nachricht an Geraet + Backup, Signatur"""
    k, d = C.identity(), C.device()
    rk = C.recovery_key()
    assert len(rk) == 39 and C.norm_recovery(rk)
    b = C.seal_bundle("test", rk, k, ops=1, mem=8192)
    assert C.open_bundle("test", rk.lower(), b)["ident_pk"] == k["ident_pk"]
    m = C.encrypt(5, "test", d, {"v": 1, "type": "text", "text": "Hallo 👋"}, {d["id"]: d["enc_pk"], "backup:test": k["backup_pk"]})
    api = dict(m, sender={"username": "test"}, key=m["keys"][d["id"]])
    assert C.decrypt(5, api, d["enc_pk"], d["enc_sk"])["text"] == "Hallo 👋"
    assert C.sig_ok(5, api, C.b64(d["sign_pk"]))
