"""Videos im Chat (Saga 2.4, Etappe E2): saga-file/v2 senden, streamen und speichern - immer blockweise.

Vertrag: docs/APP-API.md des Servers, Abschnitt "Videos im Chat: saga-file/v2"; Format: filev2.py.

Senden (Hintergrund):
  * bis limits.file_max wie bisher als v1 (ein Block, POST /api/chats/{id}/files) - dazu Poster, Dauer, Abmessungen
  * groesser: nur saga-file/v2 mit fortsetzbarem Upload (POST .../uploads, PUT in Teilen <= upload_part, HEAD zum
    Fortsetzen, DELETE zum Abbrechen). Die Teile werden beim Senden aus der Datei erzeugt (je Block lesen, verschluesseln,
    senden) - die Datei liegt nie ganz im Speicher. K, Praefix, Pfad und Upload-Kennung stehen bis zum Senden der
    Nachricht im geschuetzten Speicher (Schluesselbund, sonst Datei 0600): erneutes Senden derselben Datei setzt fort.
  * Server ohne limits.file2 (2.3) bzw. Gegenueber ohne chatfile2 (Chat.big_files == False): nur bis file_max.
Wiedergabe: VideoSource holt Ciphertext blockweise (Range, Medien-Token) ueber einen Platten-Zwischenspeicher (nur
  Ciphertext, LRU, Standard 512 MB, getrennt vom Bild-Zwischenspeicher) und entschluesselt je Block. Der Player (GStreamer,
  videoplayer.py) liest ueber einen lokalen Proxy auf 127.0.0.1 (zufaelliger Port, 128-Bit-Token je Wiedergabe) mit
  Range-Anfragen - so kann er spulen. Klartext kommt nie auf die Platte (ausser beim ausdruecklichen Speichern).
Speichern: Gtk.FileDialog, entschluesselt als Datenstrom (Block fuer Block)."""
import collections
import hashlib
import http.server
import json
import os
import re
import secrets
import shutil
import socketserver
import tempfile
import threading
import time
import urllib.parse

from . import chatcrypto as C
from . import config
from . import filev2 as F
from .api import APIError, client
from .i18n import _

MiB = 1024 * 1024
PART_MAX = 8 * MiB                    # hoechstens so viel je PUT (Server: limits.upload_part)
CHUNK_BLOCKS = 16                     # Bloecke je Abruf und je Datei im Zwischenspeicher (4 MiB)
CACHE_MB = 512                        # Standard, einstellbar (settings "video_cache_mb")
PENDING_TTL = 24 * 3600               # Teil-Uploads verfallen beim Server nach 24 h
RETRIES = 8
_DIR = os.path.join(config.CACHE_DIR, "chat-video")
_TMP = os.path.join(config.CACHE_DIR, "chat-video-tmp")      # "optimiert": verkleinerte Kopie bis zum Senden (0600)


class VideoError(Exception):
    pass


Cancelled = F.Cancelled


class LocalVideo:
    """Video als Anhang im Eingabefeld: nur Pfad und Groesse (nie ganz im Speicher). optimize: "optimiert 720p"."""

    def __init__(self, path, size, optimize=False, forced=False):
        self.path, self.size = path, int(size)
        self.optimize = optimize
        self.forced = forced                 # zu gross fuer das Original: nur verkleinert sendbar
        self.status = ""                     # Fortschritt beim Senden

    def __len__(self):
        return self.size


def is_video(f):
    m = (f.get("m") or "") if isinstance(f, dict) else ""
    return m.startswith("video/")


def fmt(f):
    """Format eines Anhangs: 1 (v1), 2 (saga-file/v2) oder groesser (unbekannt)"""
    try:
        x = int(f.get("x") or 1)
    except (TypeError, ValueError):
        return 99
    return x if x >= 1 else 99


def dur_text(s):
    try:
        s = max(0, int(round(float(s))))
    except (TypeError, ValueError):
        return ""
    h, m, x = s // 3600, s % 3600 // 60, s % 60
    return f"{h}:{m:02d}:{x:02d}" if h else f"{m}:{x:02d}"


def display_size(f):
    """(w, h) wie angezeigt (Drehung beruecksichtigt) oder None"""
    try:
        w, h = int(f.get("w") or 0), int(f.get("h") or 0)
    except (TypeError, ValueError):
        return None
    if not (0 < w <= 100000 and 0 < h <= 100000):
        return None
    return (h, w) if int(f.get("rot") or 0) in (90, 270) else (w, h)


# --- Grenzen ---------------------------------------------------------------------------------------------------------------
def plan(name, size, limits, ch=None, fed=False):
    """Wie wird ein Video dieser Groesse gesendet? -> "v1" | "v2"; sonst VideoError mit Hinweis"""
    lim = limits or {}
    fmax = int(lim.get("file_max") or 10 * MiB)
    if size <= fmax:
        return "v1"
    n = max(1, fmax // MiB)
    if not lim.get("file2"):
        raise VideoError(_("“{name}” is too large (at most {n} MB). This server can't take larger videos yet.").format(name=name, n=n))
    if fed and isinstance(ch, dict) and ch.get("big_files") is False:
        raise VideoError(_("The other person's server can't take large videos yet (at most {n} MB).").format(n=n))
    vmax = int(lim.get("video_max") or 0)
    if vmax and size > vmax:
        raise VideoError(_("“{name}” is too large (at most {n} MB).").format(name=name, n=max(1, vmax // MiB)))
    return "v2"


def max_video(limits, ch=None, fed=False):
    """groesste sendbare Videogroesse (Klartext)"""
    lim = limits or {}
    fmax = int(lim.get("file_max") or 10 * MiB)
    if not lim.get("file2") or (fed and isinstance(ch, dict) and ch.get("big_files") is False):
        return fmax
    return max(fmax, int(lim.get("video_max") or 0) or fmax)


def mp4_faststart(path):
    """moov vor mdat? (nur die obersten Boxen lesen) -> True/False, None wenn kein MP4/MOV"""
    try:
        with open(path, "rb") as f:
            pos, end = 0, os.fstat(f.fileno()).st_size
            for _i in range(64):
                if pos + 8 > end:
                    return None
                f.seek(pos)
                hd = f.read(16)
                n, typ = int.from_bytes(hd[:4], "big"), hd[4:8]
                if n == 1 and len(hd) >= 16:
                    n = int.from_bytes(hd[8:16], "big")
                elif n == 0:
                    n = end - pos
                if typ == b"moov":
                    return True
                if typ == b"mdat":
                    return False
                if n < 8:
                    return None
                pos += n
    except OSError:
        return None
    return None


# --- Geschuetzter Speicher fuer laufende Uploads (K, Praefix, Pfad, Kennung) ------------------------------------------------
_PFILE = os.path.join(config.DATA_DIR, "chat-uploads.json")
_plock = threading.Lock()


def _secret():
    if os.environ.get("HK_NO_KEYRING") or not getattr(config, "Secret", None):
        return None, None
    try:
        S = config.Secret
        return S, S.Schema.new(config.APP_ID + ".Upload", S.SchemaFlags.NONE,
                               {"server": S.SchemaAttributeType.STRING, "upload": S.SchemaAttributeType.STRING})
    except Exception:  # noqa: BLE001
        return None, None


def _srv():
    return config.origin(config.settings.server or "") or ""


def _pending_all():
    """-> {upload: Eintrag} fuer den eingetragenen Server (abgelaufene fallen weg)"""
    srv, now, out = _srv(), time.time(), {}
    S, schema = _secret()
    if S is not None:
        try:
            for it in S.password_search_sync(schema, {"server": srv}, S.SearchFlags.ALL | S.SearchFlags.UNLOCK | S.SearchFlags.LOAD_SECRETS,
                                             None) or []:
                try:
                    e = json.loads(it.get_secret().get_text() or "")
                except (ValueError, AttributeError):
                    continue
                if isinstance(e, dict) and e.get("upload"):
                    out[e["upload"]] = e
        except Exception:  # noqa: BLE001 - Schluesselbund gesperrt/weg: Datei
            pass
    with _plock:
        try:
            with open(_PFILE, encoding="utf-8") as f:
                d = json.load(f)
        except (OSError, ValueError):
            d = {}
    for k, e in (d.get(srv) or {}).items() if isinstance(d, dict) else ():
        if isinstance(e, dict):
            out.setdefault(k, e)
    return {k: e for k, e in out.items() if now - float(e.get("created") or 0) < PENDING_TTL}


def pending_put(e):
    e = dict(e, server=_srv())
    S, schema = _secret()
    if S is not None:
        try:
            if S.password_store_sync(schema, {"server": e["server"], "upload": e["upload"]}, S.COLLECTION_DEFAULT,
                                     _("Saga – video upload in progress"), json.dumps(e), None):
                return
        except Exception:  # noqa: BLE001
            pass
    with _plock:
        try:
            with open(_PFILE, encoding="utf-8") as f:
                d = json.load(f)
        except (OSError, ValueError):
            d = {}
        d = d if isinstance(d, dict) else {}
        d.setdefault(e["server"], {})[e["upload"]] = e
        config.atomic_write(_PFILE, json.dumps(d))


def pending_drop(upload):
    if not upload:
        return
    srv = _srv()
    S, schema = _secret()
    if S is not None:
        try:
            S.password_clear_sync(schema, {"server": srv, "upload": upload}, None)
        except Exception:  # noqa: BLE001
            pass
    with _plock:
        try:
            with open(_PFILE, encoding="utf-8") as f:
                d = json.load(f)
        except (OSError, ValueError):
            return
        if isinstance(d, dict) and upload in (d.get(srv) or {}):
            d[srv].pop(upload, None)
            if not d[srv]:
                d.pop(srv)
            if d:
                config.atomic_write(_PFILE, json.dumps(d))
            else:
                try:
                    os.remove(_PFILE)
                except OSError:
                    pass


def pending_clear():
    """Abmelden, "Neu beginnen": alle laufenden Uploads vergessen (Schluessel weg)"""
    for k in list(_pending_all()):
        pending_drop(k)
    with _plock:
        try:
            os.remove(_PFILE)
        except OSError:
            pass


def _pending_find(cid, path, size, mtime):
    for e in _pending_all().values():
        if e.get("cid") == cid and e.get("path") == path and e.get("size") == size and abs(float(e.get("mtime") or 0) - mtime) < 0.01:
            return e
    return None


# --- Upload saga-file/v2 ------------------------------------------------------------------------------------------------
def _jerr(out):
    try:
        j = json.loads(out or b"{}")
    except (ValueError, UnicodeDecodeError):
        j = {}
    return j if isinstance(j, dict) else {}


def _sleep(sec, cancelled):
    end = time.monotonic() + sec
    while time.monotonic() < end:
        if cancelled and cancelled():
            raise Cancelled()
        time.sleep(min(0.2, max(0.0, end - time.monotonic())))


def _create(cid, total):
    try:
        return client.json("POST", f"api/chats/{cid}/uploads", {"size": total, "fmt": 2})
    except APIError as e:
        if e.code == 409:
            raise VideoError(e.msg or _("The other person's server can't take large videos yet.")) from e
        if e.code == 429:
            raise VideoError(e.msg or _("Too many uploads at once – please wait a moment.")) from e
        raise


def _head(url):
    """-> (offset, fertig) oder None (Upload unbekannt/abgelaufen)"""
    code, h, _out = client.media("HEAD", url, timeout=30)
    if code == 404:
        return None
    if code >= 400 or code == 0:
        raise APIError(code)
    total = int(h.get("upload-length") or 0)
    off = int(h.get("upload-offset") or 0)
    return off, h.get("upload-done") == "1" or (total and off >= total)


def _send_parts(url, total, off, part, make, progress=None, cancelled=None):
    """Teile ab off senden (make(a, b) -> Folge von Stuecken). -> Antwort des letzten Teils (done)"""
    fails = 0
    while True:
        if cancelled and cancelled():
            raise Cancelled()
        if off >= total:                                    # alles da (Antwort verpasst)
            st = _head(url)
            if st and st[1]:
                return {"done": True}
            raise VideoError(_("Upload failed."))
        to = min(total, off + part) - 1
        hd = {"Content-Range": f"bytes {off}-{to}/{total}", "Content-Type": "application/octet-stream", "Content-Length": str(to - off + 1)}
        try:
            code, h, out = client.media("PUT", url, body=lambda a=off, b=to: make(a, b), headers=hd, timeout=300)
        except APIError as e:                               # Netz weg: warten, Stand holen
            code, h, out = 0, {}, str(e).encode()
        j = _jerr(out)
        if code == 200:
            fails = 0
            if j.get("done"):
                if progress:
                    progress(total, total)
                return j
            off = int(j.get("offset") or h.get("upload-offset") or to + 1)
            if progress:
                progress(off, total)
            continue
        if code == 409 and isinstance(j.get("offset"), int):
            off = j["offset"]
            continue
        if code == 404:
            raise VideoError(_("Upload cancelled."))
        if code in (400, 403, 413):
            raise VideoError(j.get("error") or _("Upload failed."))
        fails += 1                                          # 0/408/429/5xx: spaeter weiter
        if fails > RETRIES:
            raise VideoError(_("Upload cancelled – no connection."))
        _sleep(min(15.0, 0.5 * 2 ** fails), cancelled)
        try:
            st = _head(url)
        except APIError:
            continue
        if st is None:
            raise VideoError(_("Upload cancelled."))
        if st[1]:
            return {"done": True}
        off = st[0]


def upload(cid, path, progress=None, cancelled=None, limits=None):
    """Hintergrund: Datei als saga-file/v2 hochladen (fortsetzbar). progress(gesendet, gesamt) aus diesem Thread.
    -> {"id", "k" (B64), "cs", "s", "pending"}; nach dem Senden der Nachricht pending_drop(pending) aufrufen."""
    st = os.stat(path)
    size, mtime = st.st_size, st.st_mtime
    e = _pending_find(cid, path, size, mtime)
    enc, url, off = None, None, 0
    if e:
        try:
            enc = F.Encryptor(path, key=C.unb64(e["k"], 32), prefix=bytes.fromhex(e["p"]), size=size)
            url = e["url"]
        except (C.CryptoError, ValueError, KeyError):
            hs = None
        else:
            hs = _head(url)                                 # Netz weg: APIError, die Fortsetzungsdaten bleiben
        if hs is None:
            pending_drop(e.get("upload"))
            e = enc = None
        elif hs[1]:
            if progress:
                progress(enc.total, enc.total)
            return {"id": e["upload"], "k": e["k"], "cs": enc.cs, "s": size, "pending": e["upload"]}
        else:
            off = hs[0]
    if e is None:
        enc = F.Encryptor(path, size=size)
        d = _create(cid, enc.total)
        upid = d.get("upload") or d.get("id")
        if not upid:
            raise VideoError(_("Upload failed."))
        url = d.get("url") or f"api/chat/uploads/{upid}"
        if not url.startswith("/api/") and not url.startswith("api/"):
            url = f"api/chat/uploads/{upid}"               # nie eine fremde Adresse
        off = int(d.get("offset") or 0)
        e = {"upload": upid, "url": url.lstrip("/"), "cid": cid, "path": path, "size": size, "mtime": mtime,
             "k": C.b64(enc.key), "p": enc.prefix.hex(), "created": time.time(), "part": int(d.get("part_max") or PART_MAX)}
        pending_put(e)
    lim = limits or {}
    part = max(64 * 1024, min(int(lim.get("upload_part") or PART_MAX), int(e.get("part") or PART_MAX), PART_MAX))
    try:
        _send_parts(e["url"], enc.total, off, part, lambda a, b: enc.iter_range(a, b, cancelled), progress, cancelled)
    except Cancelled:
        abort(e["upload"])
        raise
    except F.FileChanged as ex:
        abort(e["upload"])
        raise VideoError(_("The file was changed while uploading.")) from ex
    except VideoError:
        st2 = None
        try:
            st2 = _head(e["url"])
        except APIError:
            pass
        if st2 is None:                                     # beim Server weg: nicht mehr fortsetzbar
            pending_drop(e["upload"])
        raise
    return {"id": e["upload"], "k": e["k"], "cs": enc.cs, "s": size, "pending": e["upload"]}


def abort(upload_id):
    """Upload abbrechen (DELETE) und vergessen"""
    pending_drop(upload_id)
    try:
        client.media("DELETE", f"api/chat/uploads/{urllib.parse.quote(upload_id)}", timeout=15)
    except APIError:
        pass


def copy_v2(cid, f, cancelled=None):
    """"Erneut senden" (2.3): den Ciphertext eines v2-Anhangs als neuen Upload senden (gleicher Schluessel) - als Strom
    in Teilen von 4 MiB vom Server zum Server, nie ganz im Speicher. -> neue Datei-Kennung"""
    size, cs = int(f.get("s") or 0), int(f.get("cs") or F.CS)
    total = F.cipher_size(size, cs)
    d = _create(cid, total)
    upid = d.get("upload") or d.get("id")
    url = f"api/chat/uploads/{upid}"
    r = Remote(f["id"])
    try:
        _send_parts(url, total, int(d.get("offset") or 0), min(int(d.get("part_max") or PART_MAX), 4 * MiB),
                    lambda a, b: [r.get(a, b)], cancelled=cancelled)
    except Exception:
        abort(upid)
        raise
    return upid


# --- Abruf mit Range und Medien-Token ---------------------------------------------------------------------------------------
class Remote:
    """Ciphertext-Bereiche einer Datei vom Server (Range). Medien-Token je Wiedergabe (bei 401 neu); kennt der Server
    keine Token (404), gilt die Sitzung."""

    def __init__(self, fid):
        self.fid = fid
        self.path = f"api/chat/files/{urllib.parse.quote(fid)}"
        self.tok, self.exp = None, 0.0
        self.lock = threading.Lock()
        self.requests = 0

    def token(self, fresh=False):
        with self.lock:
            if self.tok is False:
                return None
            if self.tok and not fresh and time.time() < self.exp - 30:
                return self.tok
            try:
                j = client.json("POST", self.path + "/token") or {}
            except APIError as e:
                if e.code in (404, 405):
                    self.tok = False
                    return None
                raise
            self.tok = j.get("token") or False
            self.exp = time.time() + float(j.get("ttl") or 900)
            return self.tok or None

    def get(self, a, b):
        """Bytes a..b (einschliesslich); kuerzere Antworten werden nachgeholt"""
        out, pos, fresh = [], int(a), False
        while pos <= b:
            tok = self.token(fresh)
            code, h, body = client.media("GET", self.path, headers={"Range": f"bytes={pos}-{b}"}, token=tok, timeout=60)
            self.requests += 1
            if code == 401 and tok and not fresh:
                fresh = True
                continue
            fresh = False
            if code == 206:
                m = re.match(r"bytes (\d+)-(\d+)/(\d+|\*)", h.get("content-range") or "")
                if not m or int(m.group(1)) != pos or not body:
                    raise APIError(502, _("Unexpected answer from the server"))
                body = body[:b - pos + 1]
            elif code == 200:                               # Server ohne Range: zuschneiden
                body = body[pos:b + 1]
                if not body:
                    raise APIError(416)
            else:
                raise APIError(code, _jerr(body).get("error") or "")
            out.append(body)
            pos += len(body)
        return out[0] if len(out) == 1 else b"".join(out)


# --- Platten-Zwischenspeicher (nur Ciphertext, LRU) ------------------------------------------------------------------------
_clock = threading.Lock()
_ctotal = None
_cgen = {}                           # Chat -> Zaehler (purge_chat): spaete Schreibvorgaenge verwerfen
stats = {"hits": 0, "misses": 0, "pruned": 0}


def cache_limit():
    try:
        mb = int(config.settings.get("video_cache_mb") or CACHE_MB)
    except (TypeError, ValueError):
        mb = CACHE_MB
    return max(16, mb) * MiB


def _safe(fid):
    return re.sub(r"[^A-Za-z0-9_-]", "_", fid)[:120]


def _sk():
    return hashlib.sha256((config.settings.server or "").encode()).hexdigest()[:10]


def _cpath(cid, fid, part):
    return os.path.join(_DIR, f"{_sk()}-c{int(cid)}-{_safe(fid)}.{part}")


def cache_read(cid, fid, part, off=0, n=None):
    p = _cpath(cid, fid, part)
    try:
        with open(p, "rb") as f:
            if off:
                f.seek(off)
            data = f.read() if n is None else f.read(n)
        os.utime(p)
        stats["hits"] += 1
        return data
    except OSError:
        return None


def cache_write(cid, fid, part, data, gen=None):
    global _ctotal
    if gen is not None and _cgen.get(cid, 0) != gen:
        return
    p = _cpath(cid, fid, part)
    try:
        os.makedirs(_DIR, mode=0o700, exist_ok=True)
        config.atomic_write(p, data)
    except OSError:
        return
    with _clock:
        if _ctotal is not None:
            _ctotal += len(data)
        over = _ctotal is None or _ctotal > cache_limit()
    if over:
        cache_prune()


def cache_prune(limit=None):
    """bis unter die Grenze kuerzen (am laengsten ungenutzte zuerst) -> Anzahl entfernter Dateien"""
    global _ctotal
    limit = cache_limit() if limit is None else limit
    with _clock:
        try:
            ents = [e for e in os.scandir(_DIR) if e.is_file(follow_symlinks=False)]
        except OSError:
            _ctotal = 0
            return 0
        files = []
        for e in ents:
            try:
                st = e.stat(follow_symlinks=False)
            except OSError:
                continue
            files.append((st.st_mtime, st.st_size, e.path))
        files.sort()
        total, n = sum(f[1] for f in files), 0
        for _t, size, path in files:
            if total <= limit:
                break
            try:
                os.remove(path)
                total -= size
                n += 1
            except OSError:
                pass
        _ctotal = total
        stats["pruned"] += n
        return n


def cache_usage():
    try:
        return sum(e.stat().st_size for e in os.scandir(_DIR) if e.is_file(follow_symlinks=False))
    except OSError:
        return 0


def cache_clear():
    global _ctotal
    with _clock:
        shutil.rmtree(_DIR, ignore_errors=True)
        shutil.rmtree(_TMP, ignore_errors=True)
        _ctotal = 0


def purge_chat(cid, keep=()):
    """Chat geloescht/geleert: Zwischenspeicher dieses Chats entfernen (ausser Datei-IDs in keep)"""
    global _ctotal
    _cgen[cid] = _cgen.get(cid, 0) + 1
    pre = f"{_sk()}-c{int(cid)}-"
    safe_keep = {_safe(f) for f in keep}
    n = 0
    with _clock:
        try:
            ents = [e for e in os.scandir(_DIR) if e.is_file(follow_symlinks=False) and e.name.startswith(pre)]
        except OSError:
            ents = []
        for e in ents:
            if e.name[len(pre):].rsplit(".", 1)[0] in safe_keep:
                continue
            try:
                os.remove(e.path)
                n += 1
            except OSError:
                pass
        _ctotal = None
    return n


def tmp_path(suffix=".mp4"):
    """private Datei fuer "optimiert" (0600) - nach dem Senden loeschen"""
    os.makedirs(_TMP, mode=0o700, exist_ok=True)
    fd, p = tempfile.mkstemp(prefix="opt-", suffix=suffix, dir=_TMP)
    os.close(fd)
    os.chmod(p, 0o600)
    return p


# --- Quelle: Klartext-Bereiche einer v2-Datei, blockweise --------------------------------------------------------------------
class VideoSource:
    """Klartext eines saga-file/v2-Anhangs (Schluessel und Angaben aus der Nachricht). Thread-sicher.
    cache=False (Speichern): Zwischenspeicher nur lesen - ein 500-MB-Download verdraengt keine anderen Videos."""

    MEM_BLOCKS = 8

    def __init__(self, cid, f, cache=True):
        self.cid, self.fid = cid, f["id"]
        self.key = C.unb64(f.get("k"), 32)
        self.size = int(f.get("s") or 0)
        self.cs = int(f.get("cs") or F.CS)
        if not F.CS_MIN <= self.cs <= F.CS_MAX or self.cs & (self.cs - 1) or self.size < 0:
            raise C.CryptoError("cs")
        self.n = F.blocks(self.size, self.cs)
        self.total = F.cipher_size(self.size, self.cs)
        self.mime = f.get("m") if re.fullmatch(r"(video|audio)/[\w.+-]{1,60}", f.get("m") or "") else "video/mp4"
        self.cache = cache
        self.remote = Remote(self.fid)
        self.gen = _cgen.get(cid, 0)
        self._prefix = None
        self._mem = collections.OrderedDict()
        self._lock = threading.Lock()
        self._chunk_locks = {}
        self._last = None

    def prefix(self):
        if self._prefix is None:
            raw = cache_read(self.cid, self.fid, "h")
            if raw is None or len(raw) != F.HEADER:
                raw = self.remote.get(0, F.HEADER - 1)
                stats["misses"] += 1
                F.check_header(raw, self.size, self.cs)
                if self.cache:
                    cache_write(self.cid, self.fid, "h", raw, self.gen)
            self._prefix = F.check_header(raw, self.size, self.cs)
        return self._prefix

    def _chunk_range(self, k):
        a = F.block_off(k * CHUNK_BLOCKS, self.cs)
        return a, min(F.block_off((k + 1) * CHUNK_BLOCKS, self.cs), self.total) - 1

    def cipher_block(self, i):
        k = i // CHUNK_BLOCKS
        a, b = self._chunk_range(k)
        off, n = F.block_off(i, self.cs) - a, F.block_len(i, self.size, self.cs)
        last = self._last
        if last is not None and last[0] == k:               # zuletzt geholter Teil (Speichern ohne Zwischenspeicher)
            return last[1][off:off + n]
        data = cache_read(self.cid, self.fid, k, off, n)
        if data is not None and len(data) == n:
            return data
        with self._lock:
            lk = self._chunk_locks.setdefault(k, threading.Lock())
        with lk:                                            # gleichzeitige Leser: nur ein Abruf je Teil
            data = cache_read(self.cid, self.fid, k, off, n)
            if data is not None and len(data) == n:
                return data
            raw = self.remote.get(a, b)
            stats["misses"] += 1
            if len(raw) != b - a + 1:
                raise APIError(502, _("Unexpected answer from the server"))
            if self.cache:
                cache_write(self.cid, self.fid, k, raw, self.gen)
            else:
                self._last = (k, raw)                       # hoechstens ein Teil (4 MiB) im Speicher
            return raw[off:off + n]

    def block(self, i):
        with self._lock:
            p = self._mem.get(i)
            if p is not None:
                self._mem.move_to_end(i)
                return p
        p = F.decrypt_block(self.key, self.prefix(), i, self.cipher_block(i), self.size, self.cs)
        with self._lock:
            self._mem[i] = p
            while len(self._mem) > self.MEM_BLOCKS:
                self._mem.popitem(last=False)
        return p

    def iter_plain(self, a, b, cancelled=None):
        """Klartext a..b (einschliesslich) als Stuecke (je hoechstens ein Block)"""
        if self.size == 0 or a > b:
            return
        b = min(b, self.size - 1)
        for i in range(a // self.cs, b // self.cs + 1):
            if cancelled and cancelled():
                raise Cancelled()
            blk = self.block(i)
            base = i * self.cs
            lo, hi = max(a, base) - base, min(b, base + len(blk) - 1) - base
            yield blk if (lo, hi + 1) == (0, len(blk)) else blk[lo:hi + 1]


class MemorySource:
    """kleines v1-Video (bis file_max, schon entschluesselt) fuer denselben Player-Weg"""

    def __init__(self, data, mime):
        self.data, self.size = data, len(data)
        self.mime = mime if re.fullmatch(r"(video|audio)/[\w.+-]{1,60}", mime or "") else "video/mp4"

    def iter_plain(self, a, b, cancelled=None):
        b = min(b, self.size - 1)
        for x in range(a, b + 1, 256 * 1024):
            if cancelled and cancelled():
                raise Cancelled()
            yield self.data[x:min(b + 1, x + 256 * 1024)]


def save(cid, f, write, progress=None, cancelled=None):
    """Hintergrund: v2-Anhang entschluesselt als Strom schreiben (write(bytes) je Block). -> Bytes"""
    src = VideoSource(cid, f, cache=False)
    done = 0
    for piece in src.iter_plain(0, src.size - 1, cancelled):
        write(piece)
        done += len(piece)
        if progress:
            progress(done, src.size)
    return done


# --- Lokaler Proxy fuer den Player (127.0.0.1, zufaelliger Port, Token je Wiedergabe) -------------------------------------
_RANGE = re.compile(r"^bytes=(\d*)-(\d*)$")


def parse_range(hv, size):
    """Range-Kopf -> (a, b) oder None (ganze Datei) oder "bad" (416). Mehrere Bereiche: der erste."""
    if not hv:
        return None
    m = _RANGE.match(hv.split(",")[0].strip())
    if not m or (not m.group(1) and not m.group(2)):
        return "bad"
    if not m.group(1):                                      # bytes=-n (Ende)
        n = int(m.group(2))
        if n <= 0 or size == 0:
            return "bad"
        return max(0, size - n), size - 1
    a = int(m.group(1))
    b = int(m.group(2)) if m.group(2) else size - 1
    if a >= size or b < a:
        return "bad"
    return a, min(b, size - 1)


class _Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "saga-media"
    sys_version = ""

    def log_message(self, *a):
        pass

    def _src(self):
        host = (self.headers.get("Host") or "").split(":")[0]
        if host not in ("127.0.0.1", "localhost"):
            return None
        m = re.match(r"^/m/([A-Za-z0-9_-]{20,64})(?:/[^?]*)?(?:\?.*)?$", self.path)
        return proxy.sources.get(m.group(1)) if m else None

    def _head(self, code, n, extra=None, mime=None):
        self.send_response(code)
        self.send_header("Content-Type", mime or "application/octet-stream")
        self.send_header("Content-Length", str(n))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()

    def do_HEAD(self):
        self._serve(False)

    def do_GET(self):
        self._serve(True)

    def _serve(self, body):
        src = self._src()
        if src is None:
            self._head(404, 0)
            return
        size = src.size
        r = parse_range(self.headers.get("Range"), size)
        if r == "bad":
            self._head(416, 0, {"Content-Range": f"bytes */{size}"})
            return
        if r is None:
            a, b, code, extra = 0, size - 1, 200, {}
        else:
            a, b = r
            code, extra = 206, {"Content-Range": f"bytes {a}-{b}/{size}"}
        self._head(code, max(0, b - a + 1), extra, src.mime)
        if not body or size == 0:
            return
        gone = proxy.gone_flag(src)
        try:
            for piece in src.iter_plain(a, b, gone):
                self.wfile.write(piece)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, TimeoutError, Cancelled):
            self.close_connection = True                    # Player hat gespult oder ist zu
        except Exception as e:  # noqa: BLE001 - Laden/Entschluesseln gescheitert: Verbindung abbrechen (nie falsche Bytes)
            proxy.errors.append(e)
            self.close_connection = True


class _Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def handle_error(self, request, client_address):
        pass


class Proxy:
    """Ein lokaler HTTP-Server nur auf 127.0.0.1 (zufaelliger Port), nur fuer registrierte Wiedergaben (Token)."""

    def __init__(self):
        self.srv = None
        self.sources = {}
        self.errors = []
        self.lock = threading.Lock()

    def _start(self):
        with self.lock:
            if self.srv is None:
                self.srv = _Server(("127.0.0.1", 0), _Handler)
                threading.Thread(target=self.srv.serve_forever, kwargs={"poll_interval": 0.5}, name="hk-media-proxy",
                                 daemon=True).start()
        return self.srv.server_address[1]

    def register(self, src):
        """-> (Adresse, Token)"""
        port = self._start()
        tok = secrets.token_urlsafe(16)                     # 128 Bit
        self.sources[tok] = src
        ext = {"video/webm": "webm", "video/quicktime": "mov", "video/x-matroska": "mkv"}.get(src.mime, "mp4")
        return f"http://127.0.0.1:{port}/m/{tok}/video.{ext}", tok

    def unregister(self, tok):
        self.sources.pop(tok, None)

    def gone_flag(self, src):
        return lambda: not any(s is src for s in list(self.sources.values()))

    def clear(self):
        self.sources.clear()

    def stop(self):
        self.clear()
        with self.lock:
            if self.srv is not None:
                self.srv.shutdown()
                self.srv.server_close()
                self.srv = None


proxy = Proxy()


def clear():
    """Abmelden, Serverwechsel, "Neu beginnen": Wiedergaben beenden, Zwischenspeicher und laufende Uploads vergessen"""
    proxy.clear()
    cache_clear()
    pending_clear()
