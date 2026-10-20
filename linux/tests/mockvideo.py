"""Nachgebauter Server, Teil Saga 2.4 E2: Videos im Chat (saga-file/v2) wie saga/chat_files.py im Server-Repo und
docs/APP-API.md ("Videos im Chat"): fortsetzbarer Upload in Teilen (POST .../uploads, PUT mit Content-Range, HEAD,
DELETE), Abruf mit Range und Medien-Token. v2-Blobs liegen als Datei auf der Platte (nie ganz im Speicher).

    srv.file_max = 256 * 1024         # limits.file_max (sonst 25 MB wie mockv2)
    srv.file2 = False                 # wie ein 2.3-Server (kein limits.file2, 404 auf die neuen Wege)
    srv.video_max = 50 * MiB          # Grenzen (limits)
    srv.put_fail = [("drop", n)]      # naechster PUT: nach n Bytes Verbindung weg (Netzabbruch)
    srv.put_fail = [("code", 503)]    # naechster PUT: Antwort 503
    srv.range_max = 1 << 20           # Antworten auf Range hoechstens so lang (wie ohne saga-media bei Foederation)
    srv.media_log                     # (Methode, Pfad, Kopfzeilen) aller Medien-Anfragen
"""
import json
import os
import re
import secrets
import tempfile
import time
import urllib.parse

MiB = 1024 * 1024
HEADER = 48


class DiskBlob:
    """v2-Blob auf der Platte (s.cfiles[fid][2])"""

    def __init__(self, path):
        self.path = path

    def __len__(self):
        return os.path.getsize(self.path)


def init(s):
    s.file2 = True
    s.video_max = 500 * MiB
    s.upload_part = 8 * MiB
    s.quota = 4096 * MiB
    s.uploads = {}                     # id -> {"cid", "uid", "size", "received", "path", "created", "busy"}
    s.media_tokens = {}                # token -> (fid, uid, expires)
    s.media_log = []
    s.put_fail = []
    s.range_max = None
    s.token_api = True
    s.v2dir = tempfile.mkdtemp(prefix="hk-mockv2-")
    s.put_bytes = 0                    # angenommene Bytes (alle PUTs)
    s.file_max = None                  # limits.file_max ueberschreiben (Tests mit kleinen Videos)


def limits(s, lim):
    if s.file_max:
        lim["file_max"] = s.file_max
    if s.file2:
        lim.update(file_max=lim.get("file_max") or 10 * MiB, file2=True, video_max=s.video_max, upload_part=s.upload_part,
                   file2_cs=262144, uploads_max=2, quota=s.quota, used=0)
    return lim


def _blob_ok(size):
    """Groesse passt zu einem v2-Blob?"""
    if size < HEADER + 16:
        return False
    for n in range(max(1, (size - HEADER) // (262144 + 16)), (size - HEADER) // (262144 + 16) + 3):
        plain = size - HEADER - 16 * n
        if plain >= 0 and max(1, -(-plain // 262144)) == n:
            return True
    return False


def handle(s, method, path, parts, q, b):
    """JSON-Wege (ueber client.json): Upload anlegen, Token"""
    me = s.me
    if not s.file2:
        return None
    if parts[:2] == ["api", "chats"] and len(parts) == 4 and parts[3] == "uploads" and method == "POST":
        cid = int(parts[2])
        ch = s.chats.get(cid)
        if ch is None or me not in ch["members"]:
            return s._reply(404, {"error": "not found"})
        if ch.get("big_files") is False:
            return s._reply(409, {"error": "Der Server des Gegenübers kann große Videos noch nicht."})
        size = int(b.get("size") or 0)
        if b.get("fmt") != 2 or not _blob_ok(size):
            return s._reply(400, {"error": "Größe passt zu keinem v2-Blob."})
        if size > s.video_max + 48 + 16 * (s.video_max // 262144 + 1):
            return s._reply(413, {"error": "Video zu groß."})
        if sum(1 for u in s.uploads.values() if u["uid"] == me) >= 2:
            return s._reply(429, {"error": "Zu viele Uploads gleichzeitig."})
        uid = secrets.token_urlsafe(18)
        p = os.path.join(s.v2dir, uid)
        open(p, "wb").close()
        s.uploads[uid] = {"cid": cid, "uid": me, "size": size, "received": 0, "path": p, "created": time.time()}
        return s._reply(200, {"upload": uid, "offset": 0, "size": size, "part_max": s.upload_part, "expires": time.time() + 86400,
                              "url": f"/api/chat/uploads/{uid}"})
    if parts[:3] == ["api", "chat", "uploads"] and len(parts) == 4 and method == "DELETE":
        u = s.uploads.pop(parts[3], None)
        if u:
            os.remove(u["path"])
        return s._reply(200 if u else 404, {"ok": bool(u)})
    if parts[:3] == ["api", "chat", "files"] and len(parts) == 5 and parts[4] == "token" and method == "POST":
        if not s.token_api:
            return s._reply(404, {"error": "not found"})
        f = s.cfiles.get(parts[3])
        if not f or me not in s.chats[f[0]]["members"]:
            return s._reply(404, {"error": "not found"})
        tok = "m1." + secrets.token_urlsafe(24)
        s.media_tokens[tok] = (parts[3], me, time.time() + 900)
        return s._reply(200, {"token": tok, "expires": time.time() + 900, "ttl": 900})
    return None


def _j(code, obj, headers=None):
    return code, dict(headers or {}), json.dumps(obj).encode()


def media(s, method, url, data, headers):
    """client._media_send: -> (status, Kopfzeilen {klein}, Bytes)"""
    u = urllib.parse.urlsplit(url)
    path = u.path.strip("/")
    parts = path.split("/")
    hd = {k.lower(): v for k, v in headers.items()}
    s.media_log.append((method, path, hd))
    base = f"{u.scheme}://{u.netloc}"
    want = s.sessions.get(base)
    who = None
    tok = hd.get("x-saga-media-token")
    if tok:
        t = s.media_tokens.get(tok)
        if not t or t[2] < time.time():
            return _j(401, {"error": "token"})
        who = t[1]
    elif want and f"hk_session={want}" in (hd.get("cookie") or ""):
        who = s.me
    if who is None:
        return _j(401, {"error": "unauthorized"})
    if parts[:3] == ["api", "chat", "uploads"] and len(parts) == 4 and s.file2:
        up = s.uploads.get(parts[3])
        if method == "DELETE":
            if up:
                s.uploads.pop(parts[3])
                os.remove(up["path"])
            return _j(200 if up else 404, {"ok": bool(up)})
        if up is None or up["uid"] != who:
            done = s.cfiles.get(parts[3])
            if method == "HEAD" and done and isinstance(done[2], DiskBlob):
                n = len(done[2])
                return 204, {"upload-offset": str(n), "upload-length": str(n), "upload-done": "1"}, b""
            return _j(404, {"error": "Upload unbekannt"})
        if method == "HEAD":
            return 204, {"upload-offset": str(up["received"]), "upload-length": str(up["size"])}, b""
        if method == "PUT":
            m = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", hd.get("content-range") or "")
            if not m or int(m.group(3)) != up["size"]:
                return _j(400, {"error": "Content-Range"})
            a, z = int(m.group(1)), int(m.group(2))
            if z - a + 1 > s.upload_part:
                return _j(413, {"error": "Teil zu groß"})
            if a != up["received"]:
                return _j(409, {"error": "falsche Stelle", "offset": up["received"]}, {"upload-offset": str(up["received"])})
            fail = s.put_fail.pop(0) if s.put_fail else None
            if fail and fail[0] == "code":
                return _j(fail[1], {"error": "mock"})
            got = 0
            with open(up["path"], "ab") as f:
                for piece in ([data] if isinstance(data, (bytes, bytearray)) else data):
                    if fail and fail[0] == "drop" and got + len(piece) > fail[1]:
                        piece = piece[:fail[1] - got]
                        f.write(piece)
                        got += len(piece)
                        up["received"] += got
                        s.put_bytes += got
                        raise OSError("mock: Verbindung weg")
                    f.write(piece)
                    got += len(piece)
            if got != z - a + 1:
                return _j(400, {"error": "unvollständiger Teil"})
            up["received"] += got
            s.put_bytes += got
            if up["received"] < up["size"]:
                return _j(200, {"offset": up["received"], "done": False}, {"upload-offset": str(up["received"])})
            with open(up["path"], "rb") as f:
                head = f.read(HEADER)
            if head[:5] != b"SGF2\x02":
                s.uploads.pop(parts[3])
                os.remove(up["path"])
                return _j(400, {"error": "falscher Kopf"})
            s.uploads.pop(parts[3])
            s.cfiles[parts[3]] = (up["cid"], who, DiskBlob(up["path"]), None)
            return _j(200, {"id": parts[3], "size": up["size"], "fmt": 2, "offset": up["size"], "done": True})
    if parts[:3] == ["api", "chat", "files"] and len(parts) == 4 and method == "GET":
        f = s.cfiles.get(parts[3])
        if tok and s.media_tokens[tok][0] != parts[3]:
            return _j(401, {"error": "token"})
        if not f or who not in s.chats[f[0]]["members"]:
            return _j(404, {"error": "not found"})
        blob = f[2]
        size = len(blob)
        rg = hd.get("range")
        if not rg:
            if isinstance(blob, DiskBlob):
                with open(blob.path, "rb") as fh:
                    return 200, {"content-length": str(size)}, fh.read()
            return 200, {}, bytes(blob)
        m = re.fullmatch(r"bytes=(\d*)-(\d*)", rg)
        if not m:
            return _j(416, {"error": "range"})
        if not m.group(1):
            a, z = max(0, size - int(m.group(2))), size - 1
        else:
            a = int(m.group(1))
            z = min(int(m.group(2)) if m.group(2) else size - 1, size - 1)
        if a >= size or z < a:
            return 416, {"content-range": f"bytes */{size}"}, b""
        if s.range_max:
            z = min(z, a + s.range_max - 1)
        if isinstance(blob, DiskBlob):
            with open(blob.path, "rb") as fh:
                fh.seek(a)
                out = fh.read(z - a + 1)
        else:
            out = bytes(blob[a:z + 1])
        return 206, {"content-range": f"bytes {a}-{z}/{size}", "accept-ranges": "bytes", "etag": f'"{parts[3]}"'}, out
    return _j(404, {"error": "not found"})
