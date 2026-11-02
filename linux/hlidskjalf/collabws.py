"""Live (2.5, P3 Stufe 2): schlanker WebSocket-Client (RFC 6455) und Rahmenformat von WS /api/collab
(docs/APP-API-COLLAB.md Abschnitt 2). Nur Python-Standardbibliothek (socket, ssl) - keine weitere Abhaengigkeit im RPM/
Flatpak. Ohne GTK und ohne pycrdt (testbar).

Rahmen: [art u8][note_id int64 big-endian] + Teile (varuint-Laenge ++ Bytes); Teile sind JSON oder Yjs-Binaerdaten."""
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import threading
import urllib.parse

KIND = dict(hello=1, sub=2, unsub=3, sync1=4, sync2=5, update=6, aw=7, reject=8, bye=9, error=10, ping=11, pong=12, ack=13)
NAME = {v: k for k, v in KIND.items()}
MAX_FRAME = 1 << 21                 # Server: hoechstens 256 KB je Rahmen; Lesen grosszuegiger (Vollzustand bei reject)
_GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


# --- Rahmenformat ----------------------------------------------------------------------------------------------------
def varuint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | 0x80 if n else b)
        if not n:
            return bytes(out)


def read_varuint(b, i):
    n = s = 0
    while True:
        if i >= len(b):
            raise ValueError("varuint")
        x = b[i]
        i += 1
        n |= (x & 0x7F) << s
        s += 7
        if not x & 0x80:
            return n, i
        if s > 63:
            raise ValueError("varuint")


def encode(kind, nid, *parts):
    """kind: Name ("sub") oder Zahl; Teile: bytes (roh) oder JSON-Wert (auch None -> "null")"""
    k = KIND[kind] if isinstance(kind, str) else int(kind)
    out = bytearray([k]) + struct.pack(">q", int(nid))
    for p in parts:
        if not isinstance(p, (bytes, bytearray)):
            p = json.dumps(p, separators=(",", ":"), ensure_ascii=False).encode()
        out += varuint(len(p)) + p
    return bytes(out)


def decode(b):
    """-> (name, note_id, [teile als bytes]); ValueError bei kaputtem Rahmen"""
    if len(b) < 9:
        raise ValueError("kurz")
    kind, nid = b[0], struct.unpack(">q", bytes(b[1:9]))[0]
    parts, i = [], 9
    while i < len(b):
        n, i = read_varuint(b, i)
        if i + n > len(b):
            raise ValueError("laenge")
        parts.append(bytes(b[i:i + n]))
        i += n
    return NAME.get(kind, kind), nid, parts


def js(p):
    """Teil -> JSON-Wert (None bei leer/kaputt)"""
    try:
        return json.loads(p.decode("utf-8")) if p else None
    except (ValueError, UnicodeDecodeError):
        return None


def state_vector(sv):
    """Yjs-Zustandsvektor (encodeStateVector) -> {client: clock}"""
    out, i = {}, 0
    n, i = read_varuint(sv, i)
    for _ in range(n):
        c, i = read_varuint(sv, i)
        k, i = read_varuint(sv, i)
        out[c] = k
    return out


# --- WebSocket --------------------------------------------------------------------------------------------------------
class Closed(Exception):
    def __init__(self, code=1006, reason=""):
        super().__init__(f"closed {code}")
        self.code, self.reason = code, reason


class WebSocket:
    """Blockierender Client: connect(), send_binary() (thread-sicher), recv() -> bytes (Binaerrahmen; Ping/Pong/Close
    erledigt er selbst; Textrahmen werden ignoriert), close(). Fehler: Closed(code)."""

    def __init__(self, url, headers=None, timeout=15, ssl_context=None):
        self.url, self.headers, self.timeout = url, dict(headers or {}), timeout
        self.ssl_context = ssl_context
        self.sock = None
        self.status = None
        self._wlock = threading.Lock()
        self._buf = b""
        self.closed = False

    def connect(self):
        u = urllib.parse.urlsplit(self.url)
        secure = u.scheme in ("wss", "https")
        host, port = u.hostname, u.port or (443 if secure else 80)
        raw = socket.create_connection((host, port), timeout=self.timeout)
        try:
            if secure:
                ctx = self.ssl_context or ssl.create_default_context()
                raw = ctx.wrap_socket(raw, server_hostname=host)
            key = base64.b64encode(os.urandom(16)).decode()
            path = (u.path or "/") + (("?" + u.query) if u.query else "")
            hostport = host if (port == (443 if secure else 80)) else f"{host}:{port}"
            if ":" in host and not host.startswith("["):
                hostport = f"[{host}]" + (f":{port}" if port != (443 if secure else 80) else "")
            lines = [f"GET {path} HTTP/1.1", f"Host: {hostport}", "Upgrade: websocket", "Connection: Upgrade",
                     f"Sec-WebSocket-Key: {key}", "Sec-WebSocket-Version: 13"]
            for k, v in self.headers.items():
                if "\r" in str(v) or "\n" in str(v):
                    continue
                lines.append(f"{k}: {v}")
            raw.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1"))
            head = b""
            while b"\r\n\r\n" not in head:
                c = raw.recv(4096)
                if not c:
                    raise Closed(1006, "handshake")
                head += c
                if len(head) > 65536:
                    raise Closed(1002, "handshake")
            head, self._buf = head.split(b"\r\n\r\n", 1)
            hl = head.decode("latin-1").split("\r\n")
            try:
                self.status = int(hl[0].split()[1])
            except (IndexError, ValueError):
                raise Closed(1002, "status") from None
            if self.status != 101:
                raise Closed(1006, f"http {self.status}")
            hd = {}
            for line in hl[1:]:
                k, _s, v = line.partition(":")
                hd[k.strip().lower()] = v.strip()
            want = base64.b64encode(hashlib.sha1(key.encode() + _GUID).digest()).decode()
            if hd.get("sec-websocket-accept") != want:
                raise Closed(1002, "accept")
        except BaseException:
            try:
                raw.close()
            except OSError:
                pass
            raise
        raw.settimeout(None)
        self.sock = raw
        return self

    # --- Senden ---
    def _send_frame(self, op, data=b""):
        if self.sock is None or self.closed and op != 8:
            raise Closed(1006, "closed")
        n = len(data)
        head = bytearray([0x80 | op])
        if n < 126:
            head.append(0x80 | n)
        elif n < 65536:
            head.append(0x80 | 126)
            head += struct.pack(">H", n)
        else:
            head.append(0x80 | 127)
            head += struct.pack(">Q", n)
        mask = os.urandom(4)
        head += mask
        body = bytes(b ^ mask[i & 3] for i, b in enumerate(data)) if n < 4096 else _mask_fast(data, mask)
        with self._wlock:
            try:
                self.sock.sendall(bytes(head) + body)
            except OSError as e:
                raise Closed(1006, str(e)) from e

    def send_binary(self, data):
        self._send_frame(2, data)

    def close(self, code=1000):
        if self.sock is None:
            return
        if not self.closed:
            self.closed = True
            try:
                self._send_frame(8, struct.pack(">H", code))
            except Closed:
                pass
        self.shutdown()

    def shutdown(self):
        s, self.sock = self.sock, None
        self.closed = True
        if s is None:
            return
        try:
            s.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            s.close()
        except OSError:
            pass

    # --- Empfangen ---
    def _read(self, n):
        while len(self._buf) < n:
            s = self.sock
            if s is None:
                raise Closed(1006, "closed")
            try:
                c = s.recv(max(65536, n - len(self._buf)))
            except OSError as e:
                raise Closed(1006, str(e)) from e
            if not c:
                raise Closed(1006, "eof")
            self._buf += c
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def recv(self):
        msg, mop = b"", None
        while True:
            b0, b1 = self._read(2)
            fin, op = b0 & 0x80, b0 & 0x0F
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read(8))[0]
            if n > MAX_FRAME:
                raise Closed(1009, "gross")
            mask = self._read(4) if b1 & 0x80 else None
            data = self._read(n) if n else b""
            if mask:
                data = _mask_fast(data, mask)
            if op == 8:                                   # Schliessen: Code des Servers
                code = struct.unpack(">H", data[:2])[0] if len(data) >= 2 else 1005
                if not self.closed:
                    self.closed = True
                    try:
                        self._send_frame(8, data[:2])
                    except Closed:
                        pass
                self.shutdown()
                raise Closed(code, data[2:].decode("utf-8", "replace"))
            if op == 9:                                   # Ping -> Pong
                try:
                    self._send_frame(10, data)
                except Closed:
                    pass
                continue
            if op == 10:
                continue
            if op in (1, 2):
                mop, msg = op, data
            elif op == 0:
                msg += data
                if len(msg) > MAX_FRAME:
                    raise Closed(1009, "gross")
            else:
                continue
            if fin:
                if mop == 2:
                    return msg
                msg, mop = b"", None                       # Textrahmen: nicht vorgesehen, ignorieren


def _mask_fast(data, mask):
    if not data:
        return b""
    m = (mask * (len(data) // 4 + 1))[:len(data)]
    return (int.from_bytes(data, "big") ^ int.from_bytes(m, "big")).to_bytes(len(data), "big")
