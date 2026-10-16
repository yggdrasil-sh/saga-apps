"""Anruf-Signale (saga-call/v1) gegen tests/call_vectors.json aus dem Server-Repo (tools/call_vectors.py) - Pflicht: alle
Pruefpunkte gruen, das gefaelschte Signal (fuer ein anderes Zielgeraet signiert) muss scheitern. Die Datei wird
unveraendert aus ~/src/saga/tests/ kopiert."""
import json
import os

from hlidskjalf import chatcrypto as C

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "call_vectors.json")
V = json.load(open(PATH, encoding="utf-8"))
A, B, CID = V["caller"], V["callee"], V["call_id"]


def dev(x):
    return C.device(x["device"], bytes.fromhex(x["enc_seed_hex"]), bytes.fromhex(x["sign_seed_hex"]))


def test_format_and_keys():
    assert V["format"] == "saga-call/v1"
    for x in (A, B):
        d = dev(x)
        assert C.b64(d["enc_pk"]) == x["enc_pk"] and C.b64(d["sign_pk"]) == x["sign_pk"]


def test_signal_msg_sig_inner():
    o = V["offer"]
    msg = C.signal_msg(CID, A["uid"], A["device"], B["uid"], B["device"], o["body"])
    assert msg.hex() == o["signal_msg_hex"]
    sig = C.sign(msg, dev(A)["sign_sk"])                   # Ed25519 ist deterministisch
    assert C.b64(sig) == o["sig"]
    assert C.signal_inner(o["body"], sig) == o["inner"]


def test_fingerprints():
    o = V["offer"]
    body = json.loads(o["body"])
    assert C.sdp_fingerprints(body["sdp"]) == o["fingerprints"] == body["fp"]


def _open(sealed, to=B):
    d = dev(to)
    return C.open_signal(sealed, d["enc_pk"], d["enc_sk"])


def test_open_offer_and_ice():
    for k in ("offer", "ice"):
        body, sig = _open(V[k]["sealed"])
        assert body == V[k]["body"] and C.b64(sig) == V[k]["sig"]
        assert C.verify_signal(CID, A["uid"], A["device"], B["uid"], B["device"], body, sig, C.unb64(A["sign_pk"]))


def test_forged_rejected():
    f = V["forged"]
    body, sig = _open(f["sealed"])
    assert body == f["body"]
    assert not C.verify_signal(CID, A["uid"], A["device"], B["uid"], B["device"], body, sig, C.unb64(A["sign_pk"]))
    assert C.verify_signal(CID, A["uid"], A["device"], B["uid"], f["to_device_signed"], body, sig, C.unb64(A["sign_pk"]))


def test_wrong_call_or_recipient_rejected():
    body, sig = _open(V["offer"]["sealed"])
    pk = C.unb64(A["sign_pk"])
    assert not C.verify_signal("0" * 32, A["uid"], A["device"], B["uid"], B["device"], body, sig, pk)
    assert not C.verify_signal(CID, A["uid"], A["device"], "mallory", B["device"], body, sig, pk)
    try:
        _open(V["offer"]["sealed"], to=A)                  # nicht fuer dieses Geraet versiegelt
        raise AssertionError("haette scheitern muessen")
    except C.CryptoError:
        pass


def test_roundtrip():
    body = json.dumps({"t": "state", "mic": True, "cam": False, "screen": False}, separators=(",", ":"))
    sealed = C.seal_signal(CID, A["uid"], dev(A), B["uid"], B["device"], dev(B)["enc_pk"], body)
    b2, sig = _open(sealed)
    assert b2 == body
    assert C.verify_signal(CID, A["uid"], A["device"], B["uid"], B["device"], b2, sig, dev(A)["sign_pk"])
