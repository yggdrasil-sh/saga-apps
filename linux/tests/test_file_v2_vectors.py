"""saga-file/v2 (2.4, Videos im Chat) gegen tests/file_v2_vectors.json - unveraenderte Kopie von tests/vectors/file_v2.json
aus dem Server-Repo (tools/file_vectors_v2.py). Pflicht: Kopf, AD/Nonce, Bloecke, SHA-256, Entschluesseln, alle
reject-Faelle scheitern, Bereiche -> Bloecke stimmen."""
import hashlib
import json
import os
import struct

import pytest

from hlidskjalf import chatcrypto as C
from hlidskjalf import filev2 as F

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "file_v2_vectors.json")
V = json.load(open(PATH, encoding="utf-8"))
CS = V["cs"]


def plain(n):
    return bytes(k % 251 for k in range(n))


def build(c, tmp_path):
    """Blob ueber den Encryptor der App (aus einer Datei, blockweise) erzeugen"""
    p = tmp_path / f"plain-{c['size']}"
    p.write_bytes(plain(c["size"]))
    e = F.Encryptor(str(p), key=C.unb64(c["key"], 32), prefix=bytes.fromhex(c["prefix_hex"]))
    return b"".join(e.iter_range(0, e.total - 1)), e


def mutate(blob, op):
    b = bytearray(blob)
    k = op["op"]
    if k == "swap_blocks":
        i, j = op["i"], op["j"]
        bi, bj = F.block_off(i), F.block_off(j)
        x, y = bytes(b[bi:bi + CS + 16]), bytes(b[bj:bj + CS + 16])
        b[bi:bi + CS + 16], b[bj:bj + CS + 16] = y, x
    elif k == "drop_last_block":
        del b[F.block_off(F.blocks(op["size"]) - 1):]
    elif k == "drop_last_block_fix_size":
        n = F.blocks(op["size"])
        del b[F.block_off(n - 1):]
        b[16:24] = struct.pack(">Q", (n - 1) * CS)
    elif k == "set_size":
        b[16:24] = struct.pack(">Q", op["value"])
    elif k == "set_cs":
        b[8:12] = struct.pack(">I", op["value"])
    elif k == "set_byte":
        b[op["offset"]] = op["value"]
    elif k == "flip":
        b[op["offset"]] ^= 1
    elif k == "truncate":
        del b[op["length"]:]
    else:
        raise ValueError(k)
    return bytes(b)


def test_format_and_lp():
    assert V["format"] == "saga-file/v2" and CS == F.CS and V["header_len"] == F.HEADER and V["tag_len"] == F.TAG
    assert C.lp(*[bytes.fromhex(x) for x in V["lp_example"]["fields_hex"]]).hex() == V["lp_example"]["hex"]


@pytest.mark.parametrize("name", sorted(V["cases"]))
def test_case(name, tmp_path):
    c = V["cases"][name]
    key, prefix, n = C.unb64(c["key"], 32), bytes.fromhex(c["prefix_hex"]), c["blocks"]
    blob, e = build(c, tmp_path)
    assert F.blocks(c["size"]) == n and F.cipher_size(c["size"]) == c["blob_size"] == len(blob) == e.total
    assert blob[:48].hex() == c["header_hex"] == F.header(c["size"], prefix).hex()
    assert F.ad(0, n, CS, c["size"]).hex() == c["ad0_hex"] and F.nonce(prefix, 0).hex() == c["nonce0_hex"]
    assert F.ad(n - 1, n, CS, c["size"]).hex() == c["ad_last_hex"] and F.nonce(prefix, n - 1).hex() == c["nonce_last_hex"]
    first = blob[F.block_off(0):F.block_off(0) + F.block_len(0, c["size"])]
    last = blob[F.block_off(n - 1):]
    assert len(first) == c["block0_len"] and first[:64].hex() == c["block0_head_hex"] and first[-64:].hex() == c["block0_tail_hex"]
    assert len(last) == c["block_last_len"] and last[:64].hex() == c["block_last_head_hex"] and last[-64:].hex() == c["block_last_tail_hex"]
    assert hashlib.sha256(blob).hexdigest() == c["blob_sha256"]
    assert F.decrypt_blob(key, blob) == plain(c["size"])
    assert F.decrypt_blob(key, blob, c["size"], CS) == plain(c["size"])          # gegen die Angaben der Nachricht
    assert F.check_header(blob[:48], c["size"], CS) == prefix
    # jeder Block einzeln (wie beim Streaming: nur Kopf + Block i)
    for i in range(n):
        o = F.block_off(i)
        assert F.decrypt_block(key, prefix, i, blob[o:o + F.block_len(i, c["size"])], c["size"]) == plain(c["size"])[i * CS:(i + 1) * CS]


@pytest.mark.parametrize("r", V["reject"], ids=[r["name"] for r in V["reject"]])
def test_reject(r, tmp_path):
    c = V["cases"][r["case"]]
    blob, _e = build(c, tmp_path)
    bad = mutate(blob, r)
    key = C.unb64(c["key"], 32)
    with pytest.raises(C.CryptoError):
        F.decrypt_blob(key, bad)
    with pytest.raises(C.CryptoError):                     # auch gegen die (richtigen) Angaben der Nachricht
        F.decrypt_blob(key, bad, c["size"], CS)


@pytest.mark.parametrize("r", V["ranges"], ids=[f"{r['a']}-{r['b']}" for r in V["ranges"]])
def test_ranges(r):
    assert list(F.plain_range(r["size"], r["a"], r["b"])) == [r["first_block"], r["last_block"], r["cipher_from"], r["cipher_to"]]


def test_partial_cipher_ranges_match_whole(tmp_path):
    """Upload fortsetzen mitten im Block: beliebige Ciphertext-Bereiche ergeben dieselben Bytes wie der ganze Blob"""
    c = V["cases"]["rest_block"]
    blob, e = build(c, tmp_path)
    for a, b in ((0, 0), (0, 47), (10, 60), (47, 48), (48, 48 + CS + 15), (100, 300000), (262207, 262209),
                 (524000, len(blob) - 1), (len(blob) - 1, len(blob) - 1), (0, len(blob) + 50)):
        assert b"".join(e.iter_range(a, b)) == blob[a:b + 1], (a, b)
    # Stuecke sind hoechstens einen Block gross (konstanter Speicher)
    assert max(len(x) for x in e.iter_range(0, len(blob) - 1)) <= CS + 16
