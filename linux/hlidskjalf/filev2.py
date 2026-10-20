"""Datei-Format saga-file/v2 (Saga 2.4, Videos im Chat): blockweise verschluesselt, wahlfreier Zugriff.

Wie saga/file_v2.py im Server und docs/APP-API.md ("Videos im Chat: saga-file/v2"); Testvektoren tests/file_v2_vectors.json
(Kopie von tests/vectors/file_v2.json des Servers).

  Blob     = Kopf(48 B) || Block_0 || ... || Block_{N-1}
  Kopf     = "SGF2" || u8 2 || u8 0 || u16 0 || u32 cs || u32 0 || u64 s || prefix(16 B) || 8 x 0x00   (big-endian)
  Block_i  = crypto_aead_xchacha20poly1305_ietf_encrypt(plain[i*cs .. (i+1)*cs), AD_i, nonce_i, K)   (Laenge <= cs + 16)
  nonce_i  = prefix || u64_be(i)
  AD_i     = LP("saga-file/v2", u64_be(i), u64_be(N), u32_be(cs), u64_be(s), u8(i == N-1))
  N        = max(1, ceil(s / cs))       Blob-Groesse = 48 + s + 16 * N

Nur PyNaCl (libsodium). Die Verschluesselung ist deterministisch (K, prefix, i): ein Upload laesst sich so an jeder
Stelle fortsetzen, ohne den Anfang noch einmal zu lesen. Alles hier arbeitet blockweise - nie die ganze Datei im Speicher."""
import os
import struct

from .chatcrypto import CryptoError, keygen, lp, nb, rnd

MAGIC = b"SGF2"
VERSION = 2
HEADER = 48
TAG = 16
CS = 256 * 1024                      # Blockgroesse in 2.4 (fest)
CS_MIN, CS_MAX = 64 * 1024, 1024 * 1024
LABEL = "saga-file/v2"
_HEAD = struct.Struct(">4sBBHIIQ24s")


class Cancelled(Exception):
    """vom Nutzer abgebrochen (bewusst kein OSError: Netzfehler werden wiederholt, Abbrechen nicht)"""


class FileChanged(ValueError):
    """die Datei wurde waehrend des Hochladens veraendert"""


def blocks(size, cs=CS):
    """Anzahl Bloecke N (eine leere Datei hat einen Block: nur das Tag)"""
    return max(1, -(-int(size) // int(cs)))


def cipher_size(size, cs=CS):
    return HEADER + int(size) + TAG * blocks(size, cs)


def block_off(i, cs=CS):
    """Ciphertext-Anfang von Block i im Blob"""
    return HEADER + int(i) * (int(cs) + TAG)


def block_len(i, size, cs=CS):
    """Ciphertext-Laenge von Block i"""
    n = blocks(size, cs)
    if not 0 <= i < n:
        raise ValueError("block")
    return min(cs, int(size) - i * cs) + TAG if i == n - 1 else cs + TAG


def plain_range(size, a, b, cs=CS):
    """Klartext-Bereich a..b (einschliesslich) -> (erster Block, letzter Block, Ciphertext von, Ciphertext bis)"""
    size, a, b = int(size), int(a), int(b)
    if size <= 0 or not 0 <= a <= b < size:
        raise ValueError("range")
    i, j = a // cs, b // cs
    end = min(block_off(j + 1, cs), cipher_size(size, cs)) - 1
    return i, j, block_off(i, cs), end


def cipher_blocks(a, b, size, cs=CS):
    """Ciphertext-Bereich a..b (Teil eines Uploads) -> Bloecke i..j, die ihn beruehren (ohne Kopf: None, None)"""
    a = max(int(a), HEADER)
    if a > b:
        return None, None
    n = blocks(size, cs)
    return min(n - 1, (a - HEADER) // (cs + TAG)), min(n - 1, (int(b) - HEADER) // (cs + TAG))


def header(size, prefix, cs=CS):
    if len(prefix) != 16:
        raise ValueError("prefix")
    return _HEAD.pack(MAGIC, VERSION, 0, 0, int(cs), 0, int(size), bytes(prefix) + b"\0" * 8)


def parse_header(raw):
    """48 Bytes -> {"cs", "size", "n", "prefix"}; falscher Kopf -> CryptoError"""
    if not isinstance(raw, (bytes, bytearray, memoryview)) or len(raw) < HEADER:
        raise CryptoError("header")
    magic, ver, flags, r16, cs, r32, size, np_ = _HEAD.unpack(bytes(raw[:HEADER]))
    if magic != MAGIC or ver != VERSION or flags or r16 or r32 or np_[16:] != b"\0" * 8:
        raise CryptoError("header")
    if not CS_MIN <= cs <= CS_MAX or cs & (cs - 1) or size >= 1 << 50:
        raise CryptoError("header")
    return {"cs": cs, "size": size, "n": blocks(size, cs), "prefix": np_[:16]}


def nonce(prefix, i):
    return bytes(prefix) + struct.pack(">Q", int(i))


def ad(i, n, cs, size):
    return lp(LABEL, struct.pack(">Q", int(i)), struct.pack(">Q", int(n)), struct.pack(">I", int(cs)), struct.pack(">Q", int(size)),
              b"\x01" if i == n - 1 else b"\x00")


def encrypt_block(key, prefix, i, plain, size, cs=CS):
    n = blocks(size, cs)
    want = block_len(i, size, cs) - TAG
    if len(plain) != want:
        raise ValueError("block size")
    return nb.crypto_aead_xchacha20poly1305_ietf_encrypt(bytes(plain), ad(i, n, cs, size), nonce(prefix, i), key)


def decrypt_block(key, prefix, i, ct, size, cs=CS):
    """Block i entschluesseln (Tag, Nummer, N, cs, s und Endmarke werden geprueft) -> Klartext oder CryptoError"""
    n = blocks(size, cs)
    if not 0 <= i < n or len(ct) != block_len(i, size, cs):
        raise CryptoError("block")
    try:
        return nb.crypto_aead_xchacha20poly1305_ietf_decrypt(bytes(ct), ad(i, n, cs, size), nonce(prefix, i), key)
    except Exception as e:  # noqa: BLE001 - nie Klartext eines falschen Blocks
        raise CryptoError("block") from e


def check_header(raw, size, cs):
    """Kopf vom Server gegen die Angaben der (Ende-zu-Ende-verschluesselten) Nachricht pruefen -> prefix"""
    h = parse_header(raw)
    if h["size"] != int(size) or h["cs"] != int(cs):
        raise CryptoError("header")
    return h["prefix"]


def decrypt_blob(key, blob, size=None, cs=None):
    """ganzer Blob (nur fuer Tests und kleine Dateien) -> Klartext"""
    h = parse_header(blob)
    size = h["size"] if size is None else size
    cs = h["cs"] if cs is None else cs
    prefix = check_header(blob, size, cs)
    if len(blob) != cipher_size(size, cs):
        raise CryptoError("length")
    out = bytearray()
    for i in range(blocks(size, cs)):
        o = block_off(i, cs)
        out += decrypt_block(key, prefix, i, blob[o:o + block_len(i, size, cs)], size, cs)
    return bytes(out)


class Encryptor:
    """Datei (Pfad) -> Ciphertext-Bytes eines beliebigen Bereichs, blockweise und deterministisch.
    key/prefix: fortgesetzter Upload (aus dem geschuetzten Speicher), sonst neu."""

    def __init__(self, path, key=None, prefix=None, cs=CS, size=None):
        self.path = path
        self.size = os.path.getsize(path) if size is None else int(size)
        self.cs = cs
        self.key = key or keygen()
        self.prefix = prefix or rnd(16)
        self.head = header(self.size, self.prefix, cs)
        self.total = cipher_size(self.size, cs)
        self.n = blocks(self.size, cs)

    def iter_range(self, a, b, cancelled=None):
        """Ciphertext a..b (einschliesslich) als Folge von Stuecken (je hoechstens ein Block) - konstanter Speicher"""
        a, b = int(a), min(int(b), self.total - 1)
        if a < HEADER:
            yield self.head[a:min(HEADER, b + 1)]
        i, j = cipher_blocks(a, b, self.size, self.cs)
        if i is None:
            return
        with open(self.path, "rb") as f:
            for k in range(i, j + 1):
                if cancelled and cancelled():
                    raise Cancelled()
                f.seek(k * self.cs)
                want = block_len(k, self.size, self.cs) - TAG
                plain = f.read(want)
                if len(plain) != want:
                    raise FileChanged()
                ct = encrypt_block(self.key, self.prefix, k, plain, self.size, self.cs)
                base = block_off(k, self.cs)
                lo, hi = max(a, base) - base, min(b, base + len(ct) - 1) - base
                yield ct[lo:hi + 1] if (lo, hi + 1) != (0, len(ct)) else ct
