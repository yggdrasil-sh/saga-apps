"""Gelesen-Bestaetigungen (Saga 2.4) gegen tests/vectors/receipts.json aus dem Server-Repo (tools/receipt_vectors.py) -
Pflicht: alle Faelle gruen, auch die Ablehnungen. Die Datei wird unveraendert als tests/receipt_vectors.json kopiert."""
import json
import os

import nacl.bindings as nb
import pytest

from hlidskjalf import chatcrypto as C

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "receipt_vectors.json")
V = json.load(open(PATH, encoding="utf-8"))


def _dev():
    pk, sk = nb.crypto_sign_seed_keypair(bytes.fromhex(V["device"]["sign_seed_hex"]))
    return {"id": V["device"]["id"], "sign_pk": pk, "sign_sk": sk}


def _viewer():
    return nb.crypto_box_seed_keypair(bytes.fromhex(V["viewer"]["backup_seed_hex"]))


def test_keys_from_seeds():
    assert V["format"] == "saga-receipt/v1"
    assert C.b64(_dev()["sign_pk"]) == V["device"]["sign_pk"]
    assert C.b64(_viewer()[0]) == V["viewer"]["backup_pk"]


@pytest.mark.parametrize("case", V["cases"], ids=lambda c: c["name"])
def test_case(case):
    d = _dev()
    msg = C.receipt_msg(case["chat"], case["reader"], d["id"], case["body"], case["v2"])
    assert msg.hex() == case["msg_hex"]
    sig = C.sign(msg, d["sign_sk"])                                   # Ed25519 ist deterministisch
    assert C.b64(sig) == case["sig"]
    pk, sk = _viewer()
    dev, body, sig2 = C.open_receipt(case["sealed"], pk, sk)
    assert (dev, body, C.b64(sig2)) == (d["id"], case["body"], case["sig"])
    assert C.verify_receipt(case["chat"], case["reader"], dev, body, sig2, d["sign_pk"], case["v2"])
    b = C.parse_receipt_body(body)
    assert b and b["n"] == json.loads(case["body"])["n"] and b["id"] == json.loads(case["body"])["id"]
    # eigenes Siegel (zufaellig) laesst sich mit demselben Schluessel oeffnen und pruefen
    mine = C.seal_receipt(case["chat"], case["reader"], d, case["body"], pk, case["v2"])
    dev3, body3, sig3 = C.open_receipt(mine, pk, sk)
    assert (dev3, body3, sig3) == (d["id"], case["body"], sig)


def test_body_format():
    """genau der Text der Vektoren (kompaktes JSON, Reihenfolge id, n, ts)"""
    c = V["cases"][0]
    j = json.loads(c["body"])
    assert C.receipt_body(j["id"], j["n"], j["ts"]) == c["body"]


@pytest.mark.parametrize("r", V["reject"], ids=lambda r: r["why"])
def test_reject(r):
    assert not C.verify_receipt(r["chat"], r["reader"], V["device"]["id"], r["body"], C.unb64(r["sig"], 64), _dev()["sign_pk"], r["v2"])


def test_open_garbage():
    pk, sk = _viewer()
    with pytest.raises(C.CryptoError):
        C.open_receipt("AAAA", pk, sk)
    bad = C.b64(nb.crypto_box_seal(json.dumps({"v": 2, "dev": "x", "body": "{}", "sig": ""}).encode(), pk))
    with pytest.raises(C.CryptoError):
        C.open_receipt(bad, pk, sk)
    assert C.parse_receipt_body('{"id":"a","n":-1}') is None and C.parse_receipt_body("x") is None
    assert C.parse_receipt_body('{"id":"a","n":true}') is None
