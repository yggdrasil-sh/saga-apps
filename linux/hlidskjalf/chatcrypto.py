"""Chat-Kryptografie Format v1 (Server 1.5) mit PyNaCl = libsodium - wie tests/chatlib.py und static/chat-crypto.js
im Server. Nur libsodium-Primitive: crypto_sign (Ed25519), crypto_box_seal (X25519), crypto_pwhash (Argon2id13),
crypto_aead_xchacha20poly1305_ietf, crypto_generichash (BLAKE2b). Nichts selbst gebaut; Zufall nur randombytes_buf.
Geprueft gegen tests/chat_vectors.json (tests/test_chat_vectors.py)."""
import base64
import json
import struct
import time

V = "saga-chat/v1/"
OPS, MEM = 2, 67108864
A32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

try:
    import nacl.bindings as nb
    import nacl.hash
    import nacl.pwhash
    import nacl.utils
    from nacl.encoding import RawEncoder
    AVAILABLE = True
except ImportError:                       # ohne PyNaCl: Chats nicht verfuegbar (die App laeuft sonst weiter)
    nb = None
    AVAILABLE = False


class CryptoError(Exception):
    pass


def rnd(n):
    return nacl.utils.random(n)


def keygen():
    """crypto_aead_xchacha20poly1305_ietf_keygen (libsodium: randombytes_buf mit KEYBYTES; PyNaCl bietet keygen nicht an)"""
    return rnd(nb.crypto_aead_xchacha20poly1305_ietf_KEYBYTES)


def b64(b):
    return base64.b64encode(b).decode()


def unb64(s, n=None):
    try:
        r = base64.b64decode(s or "", validate=True)
    except (ValueError, TypeError) as e:
        raise CryptoError("base64") from e
    if n is not None and len(r) != n:
        raise CryptoError("length")
    return r


def lp(*fields):
    """LP(f1..fn) = je Feld uint32_be(laenge) || bytes (Strings UTF-8)"""
    out = b""
    for f in fields:
        b = f.encode("utf-8") if isinstance(f, str) else bytes(f)
        out += struct.pack(">I", len(b)) + b
    return out


def gh(data, n=32):
    return nacl.hash.blake2b(data, digest_size=n, encoder=RawEncoder)


def hexid():
    return rnd(16).hex()


def sign(msg, sk):
    return nb.crypto_sign(msg, sk)[:64]


def verify(sig, msg, pk):
    try:
        nb.crypto_sign_open(bytes(sig) + msg, bytes(pk))
        return True
    except Exception:  # noqa: BLE001 - BadSignatureError, ValueError (Laenge)
        return False


def verify_b64(sig_b64, msg, pk_b64):
    try:
        return verify(unb64(sig_b64, 64), msg, unb64(pk_b64, 32))
    except CryptoError:
        return False


# --- Schluessel -------------------------------------------------------------------------------------------
def identity(ident_seed=None, backup_seed=None):
    ipk, isk = nb.crypto_sign_seed_keypair(ident_seed) if ident_seed else nb.crypto_sign_keypair()
    bpk, bsk = nb.crypto_box_seed_keypair(backup_seed) if backup_seed else nb.crypto_box_keypair()
    return {"ident_pk": ipk, "ident_sk": isk, "backup_pk": bpk, "backup_sk": bsk}


def device(did=None, enc_seed=None, sign_seed=None):
    epk, esk = nb.crypto_box_seed_keypair(enc_seed) if enc_seed else nb.crypto_box_keypair()
    spk, ssk = nb.crypto_sign_seed_keypair(sign_seed) if sign_seed else nb.crypto_sign_keypair()
    return {"id": did or hexid(), "enc_pk": epk, "enc_sk": esk, "sign_pk": spk, "sign_sk": ssk}


def backup_msg(uid, backup_pk):
    return lp(V + "backup", uid, backup_pk)


def device_msg(uid, did, enc_pk, sign_pk):
    return lp(V + "device", uid, did, enc_pk, sign_pk)


def device_cert(uid, ident_sk, dev):
    return b64(sign(device_msg(uid, dev["id"], dev["enc_pk"], dev["sign_pk"]), ident_sk))


def device_api(uid, ident_sk, dev, name):
    """Geraet fuer die API ({id, name, enc_pub, sign_pub, sig}) - Zertifikat vom Identitaetsschluessel"""
    return {"id": dev["id"], "name": name, "enc_pub": b64(dev["enc_pk"]), "sign_pub": b64(dev["sign_pk"]),
            "sig": device_cert(uid, ident_sk, dev)}


def device_ok(uid, ident_pub_b64, d):
    """Geraete-Zertifikat {id, enc_pub, sign_pub, sig} gegen den Identitaetsschluessel pruefen"""
    try:
        return verify(unb64(d.get("sig"), 64), device_msg(uid, d["id"], unb64(d["enc_pub"], 32), unb64(d["sign_pub"], 32)),
                      unb64(ident_pub_b64, 32))
    except (CryptoError, KeyError, TypeError):
        return False


def backup_ok(uid, ident_pub_b64, backup_pub_b64, backup_sig_b64):
    try:
        return verify(unb64(backup_sig_b64, 64), backup_msg(uid, unb64(backup_pub_b64, 32)), unb64(ident_pub_b64, 32))
    except CryptoError:
        return False


# --- Wiederherstellungsschluessel und Buendel -----------------------------------------------------------------
def base32(data):
    bits = val = 0
    out = ""
    for x in data:
        val = (val << 8) | x
        bits += 8
        while bits >= 5:
            out += A32[(val >> (bits - 5)) & 31]
            bits -= 5
    if bits:
        out += A32[(val << (5 - bits)) & 31]
    return out


def norm_recovery(s):
    """Eingabe normalisieren: Grossbuchstaben, Leerzeichen/'-' weg, 0->O, 1->I, 8->B; None wenn kein gueltiger Schluessel"""
    r = "".join(str(s or "").upper().split()).replace("-", "").replace("0", "O").replace("1", "I").replace("8", "B")
    return r if len(r) == 32 and all(ch in A32 for ch in r) else None


def recovery_key(raw=None):
    """20 Zufallsbytes -> Base32 (32 Zeichen), Anzeige 8x4 mit '-'"""
    k = base32(raw or rnd(20))
    return "-".join(k[i:i + 4] for i in range(0, 32, 4))


def recovery_kdf(rk, salt, ops=OPS, mem=MEM):
    n = norm_recovery(rk)
    if not n:
        raise CryptoError("recovery key")
    return nacl.pwhash.argon2id.kdf(32, n.encode("ascii"), salt, opslimit=ops, memlimit=mem)


def bundle_plain(ident_sk, backup_sk):
    return json.dumps({"v": 1, "ident_sk": b64(ident_sk), "backup_sk": b64(backup_sk)}, separators=(",", ":"))


def seal_bundle(uid, rk, ident, salt=None, nonce=None, ops=OPS, mem=MEM, plain=None):
    salt, nonce = salt or rnd(16), nonce or rnd(24)
    key = recovery_kdf(rk, salt, ops, mem)
    ct = nb.crypto_aead_xchacha20poly1305_ietf_encrypt((plain or bundle_plain(ident["ident_sk"], ident["backup_sk"])).encode(),
                                                       lp(V + "bundle", uid), nonce, key)
    return json.dumps({"v": 1, "kdf": "argon2id13", "ops": ops, "mem": mem, "salt": b64(salt), "nonce": b64(nonce), "ct": b64(ct)},
                      separators=(",", ":"))


def parse_plain(pt):
    try:
        d = json.loads(pt)
        isk, bsk = unb64(d["ident_sk"], 64), unb64(d["backup_sk"], 32)
    except (ValueError, KeyError, TypeError) as e:
        raise CryptoError("bundle") from e
    return {"ident_sk": isk, "ident_pk": isk[32:], "backup_sk": bsk, "backup_pk": nb.crypto_scalarmult_base(bsk)}


OPS_MAX, MEM_MAX = 10, 256 * 1024 * 1024                 # Argon2id: mehr wird nie geoeffnet (sonst "Unbekanntes Schluesselformat")


def open_bundle(uid, rk, bundle):
    """Buendel mit dem Wiederherstellungsschluessel oeffnen (ops/mem aus dem Buendel) -> Schluessel; CryptoError sonst"""
    try:
        d = json.loads(bundle) if isinstance(bundle, str) else bundle
        ops, mem = int(d["ops"]), int(d["mem"])
        salt, nonce, ct = unb64(d["salt"], 16), unb64(d["nonce"], 24), unb64(d["ct"])
    except (ValueError, KeyError, TypeError) as e:
        raise CryptoError("bundle") from e
    if not (1 <= ops <= OPS_MAX and 8192 <= mem <= MEM_MAX):     # Grenzen: kein Speicher-Angriff ueber das Buendel
        raise CryptoError("kdf")
    key = recovery_kdf(rk, salt, ops, mem)
    try:
        pt = nb.crypto_aead_xchacha20poly1305_ietf_decrypt(ct, lp(V + "bundle", uid), nonce, key)
    except Exception as e:  # noqa: BLE001 - CryptoError von PyNaCl
        raise CryptoError("wrong key") from e
    return parse_plain(pt)


def bundle_update_sig(uid, bundle_json, ident_sk):
    return b64(sign(lp(V + "bundle-update", uid, bundle_json), ident_sk))


def seal(data, pk):
    return b64(nb.crypto_box_seal(data, pk))


def unseal(sealed_b64, pk, sk):
    try:
        return nb.crypto_box_seal_open(unb64(sealed_b64), pk, sk)
    except Exception as e:  # noqa: BLE001
        raise CryptoError("unseal") from e


# --- Nachrichten ----------------------------------------------------------------------------------------------
def msg_ad(chat_id, mid, sender, dev_id, ts, reply_to):
    return lp(V + "msg", str(chat_id), mid, sender, dev_id, str(ts), reply_to or "")


def sig_msg(chat_id, mid, sender, dev_id, ts, reply_to, body_ct):
    return lp(V + "sig", str(chat_id), mid, sender, dev_id, str(ts), reply_to or "", gh(unb64(body_ct)))


def encrypt(chat_id, sender, dev, body, recipients, reply_to=None, mid=None, ts=None, key=None, nonce=None, plain=None):
    """recipients: {empfaenger-id: x25519_pk} -> Body fuer POST /api/chats/{id}/messages (ohne files/kind)"""
    mid = mid or hexid()
    ts = int(time.time() * 1000) if ts is None else ts
    key = key if key is not None else keygen()
    nonce = nonce or rnd(24)
    pt = (plain if plain is not None else json.dumps(body, ensure_ascii=False, separators=(",", ":"))).encode()
    ct = nb.crypto_aead_xchacha20poly1305_ietf_encrypt(pt, msg_ad(chat_id, mid, sender, dev["id"], ts, reply_to), nonce, key)
    body_ct = b64(nonce + ct)
    return {"id": mid, "ts": ts, "sender_device": dev["id"], "body_ct": body_ct, "reply_to": reply_to,
            "sig": b64(sign(sig_msg(chat_id, mid, sender, dev["id"], ts, reply_to, body_ct), dev["sign_sk"])),
            "keys": {r: seal(key, pk) for r, pk in recipients.items()}, "_key": key}


def reseal(key, recipients):
    return {r: seal(key, pk) for r, pk in recipients.items()}


def decrypt(chat_id, m, pk, sk):
    """Nachricht der API ({id, ts, sender:{username}, sender_device, body_ct, reply_to, key}) -> Inhalt (dict)"""
    key = unseal(m["key"], pk, sk)
    raw = unb64(m["body_ct"])
    if len(raw) < 24 + 16:
        raise CryptoError("short")
    try:
        pt = nb.crypto_aead_xchacha20poly1305_ietf_decrypt(raw[24:], msg_ad(chat_id, m["id"], (m.get("sender") or {}).get("username") or "",
                                                                            m["sender_device"], m["ts"], m.get("reply_to")), raw[:24], key)
        return json.loads(pt)
    except Exception as e:  # noqa: BLE001
        raise CryptoError("decrypt") from e


def sig_ok(chat_id, m, sign_pub_b64):
    try:
        return verify(unb64(m.get("sig"), 64), sig_msg(chat_id, m["id"], (m.get("sender") or {}).get("username") or "", m["sender_device"],
                                                        m["ts"], m.get("reply_to"), m["body_ct"]), unb64(sign_pub_b64, 32))
    except (CryptoError, KeyError, TypeError):
        return False


def encrypt_file(chat_id, data, key=None, nonce=None):
    key, nonce = key or keygen(), nonce or rnd(24)
    return nonce + nb.crypto_aead_xchacha20poly1305_ietf_encrypt(data, lp(V + "file", str(chat_id)), nonce, key), key


def decrypt_file(chat_id, blob, key):
    if len(blob) < 24 + 16:
        raise CryptoError("short")
    try:
        return nb.crypto_aead_xchacha20poly1305_ietf_decrypt(blob[24:], lp(V + "file", str(chat_id)), blob[:24], key)
    except Exception as e:  # noqa: BLE001
        raise CryptoError("file") from e


def _digits(h, blocks):
    return " ".join("%05d" % (int.from_bytes(h[i * 5:i * 5 + 5], "big") % 100000) for i in range(blocks))


def link_code(uid, did, enc_pk, sign_pk):
    """Kopplungs-Code: 20 Ziffern (4 Bloecke) aus generichash32(LP("saga-chat/v1/link", ...))"""
    return _digits(gh(lp(V + "link", uid, did, enc_pk, sign_pk)), 4)


def safety_number(uid_a, ident_a, uid_b, ident_b):
    """Sicherheitsnummer: 60 Ziffern (12 Bloecke), auf beiden Seiten gleich (uid-Reihenfolge nach UTF-8-Bytes)"""
    a, b = (uid_a.encode(), bytes(ident_a)), (uid_b.encode(), bytes(ident_b))
    if a[0] > b[0]:
        a, b = b, a
    return _digits(gh(lp(V + "safety", a[0], a[1], b[0], b[1]), 64), 12)


# --- Anrufe (saga-call/v1, Server 2.1) ----------------------------------------------------------------------------
# Die Linux-App nimmt Anrufe (v1) im Browser an; Signale kann sie trotzdem pruefen/erzeugen (Grundlage fuer einen
# spaeteren eigenen Anruf mit GStreamer webrtcbin). Geprueft gegen tests/call_vectors.json (tests/test_call_vectors.py).
VC = "saga-call/v1/"


def signal_msg(call_id, from_uid, from_device, to_uid, to_device, body):
    """LP("saga-call/v1/signal", call_id, from_uid, from_device, to_uid, to_device, body)"""
    return lp(VC + "signal", call_id, from_uid, from_device, to_uid, to_device, body)


def signal_inner(body, sig):
    return json.dumps({"v": 1, "body": body, "sig": b64(sig)}, separators=(",", ":"))


def seal_signal(call_id, from_uid, dev, to_uid, to_device, to_pk, body):
    """Signal (JSON-Text body) mit dem Geraete-Signaturschluessel signieren und fuer das Zielgeraet versiegeln -> B64"""
    sig = sign(signal_msg(call_id, from_uid, dev["id"], to_uid, to_device, body), dev["sign_sk"])
    return b64(nb.crypto_box_seal(signal_inner(body, sig).encode(), bytes(to_pk)))


def open_signal(sealed_b64, pk, sk):
    """Versiegeltes Signal mit dem eigenen Geraet oeffnen -> (body, sig). CryptoError bei kaputtem Inhalt."""
    raw = unseal(sealed_b64, pk, sk)
    try:
        d = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as e:
        raise CryptoError("signal") from e
    if not isinstance(d, dict) or d.get("v") != 1 or not isinstance(d.get("body"), str):
        raise CryptoError("signal version")
    return d["body"], unb64(d.get("sig"), 64)


def verify_signal(call_id, from_uid, from_device, to_uid, to_device, body, sig, sign_pk):
    """Signatur pruefen - mit MEINEM uid/Geraet als Ziel (verhindert Umleiten an andere Geraete/Anrufe)"""
    return verify(sig, signal_msg(call_id, from_uid, from_device, to_uid, to_device, body), sign_pk)


def sdp_fingerprints(sdp):
    """sortierte Liste "<alg-klein> <HEX-GROSS>" aus allen a=fingerprint-Zeilen einer SDP"""
    import re
    return sorted({m.group(1).lower() + " " + m.group(2).upper()
                   for m in re.finditer(r"^a=fingerprint:(\S+) ([0-9A-Fa-f:]+)\s*$", str(sdp or ""), re.M)})
