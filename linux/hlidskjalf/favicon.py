"""Saga 2.5: Favicons nur fuer Lesezeichen, Rueckfall auf dem Geraet.

Reihenfolge: erst `icon_url` ueber den eigenen Server; klappt das nicht (404/503/Netzfehler), der Reihe nach
`icon_direct` (der Server liefert es nur bei Lesezeichen) direkt von der Seite wie ein Browser-Tab - hoechstens 5 s,
hoechstens 512 KB, nur Rasterbilder (PNG/ICO/JPEG/GIF/WebP, kein SVG), ohne Cookies und Anmeldedaten; sonst
Anfangsbuchstabe. Fehlt `icon_direct` (aelterer Server, Dienste), bleibt alles wie bisher. Ergebnis lokal im
Zwischenspeicher (CACHE_DIR/favicons, 7 Tage, Fehlversuch 1 Stunde); nichts geht zum Server.
Die Logik (Reihenfolge, Pruefungen, Limits, Cache-Entscheidung) ist rein und wird in tests/test_favicon.py geprueft.
"""
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from . import config

TIMEOUT = 5.0
MAX_BYTES = 512 * 1024
MAX_DIRECT = 3
MAX_REDIRECTS = 4
OK_TTL = 7 * 86400
FAIL_TTL = 3600
MAX_PIXELS = 2048 * 2048         # kleine Datei, riesiges Bild (Dekompressionsbombe): nicht dekodieren
_ACCEPT = "image/png,image/x-icon,image/vnd.microsoft.icon,image/jpeg,image/gif,image/webp,*/*;q=0.1"


def _origin_parts(url):
    try:
        u = urllib.parse.urlsplit(url.strip())
        scheme = (u.scheme or "").lower()
        host = (u.hostname or "").lower()
        port = u.port or (443 if scheme == "https" else 80)
    except (ValueError, AttributeError):
        return None
    if scheme not in ("http", "https") or not host:
        return None
    return scheme, host, port, u


def _same_port(s1, p1, s2, p2):
    """gleicher Port - oder beide auf dem Standardport ihres Schemas (http-Lesezeichen, Favicon per https)"""
    std = {"http": 80, "https": 443}
    return p1 == p2 or (p1 == std[s1] and p2 == std[s2])


def direct_candidates(lst, bookmark_url):
    """Erlaubte direkte Adressen: absolut, nur http/https (bei einem https-Lesezeichen nur https), Host und Port wie das
    Lesezeichen, keine Anmeldedaten in der Adresse, ohne Doppelte, hoechstens 3. Fehlt die Liste: leer (wie bisher)."""
    if not isinstance(lst, list) or not lst or not isinstance(bookmark_url, str):
        return []
    b = _origin_parts(bookmark_url)
    if not b:
        return []
    out = []
    for raw in lst[:MAX_DIRECT]:
        if not isinstance(raw, str):
            continue
        c = _origin_parts(raw)
        if not c:
            continue
        scheme, host, port, u = c
        if b[0] == "https" and scheme != "https":
            continue
        if host != b[1] or not _same_port(scheme, port, b[0], b[2]) or u.username is not None or u.password is not None:
            continue
        if raw not in out:
            out.append(raw)
    return out


def plan(server_path, direct):
    """Reihenfolge der Versuche: [("server", pfad), ("direct", url), ...]"""
    return ([("server", server_path)] if server_path else []) + [("direct", u) for u in direct]


def first(steps, fetch):
    """Erstes Ergebnis in Planreihenfolge -> (schritt, daten) oder None"""
    for s in steps:
        d = fetch(s)
        if d is not None:
            return s, d
    return None


def redirect_allowed(frm, to):
    """Weiterleitung folgen? Nur http/https, nie von https auf http"""
    t = _origin_parts(to) if isinstance(to, str) else None
    f = _origin_parts(frm)
    return bool(t and f) and not (f[0] == "https" and t[0] == "http")


def is_raster(d):
    """Nur Rasterbilder nach Dateianfang: PNG, ICO, JPEG, GIF, WebP. SVG/HTML/sonstiges nie."""
    if not isinstance(d, (bytes, bytearray)) or len(d) < 4:
        return False
    return (d.startswith(b"\x89PNG") or d.startswith(b"\x00\x00\x01\x00") or d.startswith(b"\xff\xd8\xff")
            or d.startswith(b"GIF8") or (d.startswith(b"RIFF") and d[8:12] == b"WEBP"))


def reject_early(status, ctype, length):
    """Schon die Kopfzeilen verraten Fehler/Uebergroesse/SVG -> gleich abbrechen"""
    if status != 200:
        return True
    if length is not None and length > MAX_BYTES:
        return True
    ct = (ctype or "").lower()
    return "svg" in ct or ct.startswith("text/")


def pixels_ok(width, height):
    """Bildmasse vor dem Dekodieren pruefen"""
    return width > 0 and height > 0 and width * height <= MAX_PIXELS


def accept(status, ctype, length, data):
    return (not reject_early(status, ctype, length)) and bool(data) and len(data) <= MAX_BYTES and is_raster(data)


def cache_decision(ok, saved_at, now):
    """'use' (Treffer < 7 Tage), 'missing' (Fehlversuch < 1 h) oder 'reload'; Zeit in der Zukunft -> 'reload'"""
    age = now - saved_at
    if age < 0:
        return "reload"
    if ok:
        return "use" if age < OK_TTL else "reload"
    return "missing" if age < FAIL_TTL else "reload"


def cache_key(urls):
    """Schluessel je Kandidatenliste (FNV-1a 64 wie iOS/Android)"""
    h = 0xcbf29ce484222325
    for b in "\n".join(urls).encode():
        h = ((h ^ b) * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return f"{h:016x}"


# --- Netz (eigener Opener: keine Cookies, keine Anmeldung, Weiterleitungen selbst geprueft) -------------------------

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_opener = urllib.request.build_opener(_NoRedirect)    # ohne HTTPCookieProcessor und ohne Auth-Handler


def http_get(url, timeout=TIMEOUT, max_bytes=MAX_BYTES):
    """Rohabruf -> (status, content_type, laenge|None, daten|None). Ganze Zeit (auch ueber Weiterleitungen) <= timeout,
    Daten nur bis max_bytes (+1 zum Erkennen von Uebergroesse). Fehler -> (0, None, None, None)."""
    deadline = time.monotonic() + timeout
    for _hop in range(MAX_REDIRECTS + 1):
        left = deadline - time.monotonic()
        if left <= 0:
            return 0, None, None, None
        req = urllib.request.Request(url, method="GET", headers={"Accept": _ACCEPT, "User-Agent": "Mozilla/5.0 (compatible; Saga-Linux)"})
        try:
            resp = _opener.open(req, timeout=left)
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303, 307, 308):
                nxt = urllib.parse.urljoin(url, e.headers.get("Location") or "")
                e.close()
                if not e.headers.get("Location") or not redirect_allowed(url, nxt):
                    return e.code, None, None, None
                url = nxt
                continue
            e.close()
            return e.code, e.headers.get("Content-Type") if e.headers else None, None, None
        except (urllib.error.URLError, OSError, ValueError, TimeoutError):
            return 0, None, None, None
        with resp:
            status = resp.status
            ctype = resp.headers.get("Content-Type")
            try:
                length = int(resp.headers.get("Content-Length")) if resp.headers.get("Content-Length") else None
            except ValueError:
                length = None
            if reject_early(status, ctype, length):
                return status, ctype, length, None
            buf = bytearray()
            try:
                while len(buf) <= max_bytes:
                    if time.monotonic() > deadline:
                        return 0, None, None, None
                    chunk = resp.read1(min(65536, max_bytes + 1 - len(buf)))
                    if not chunk:
                        break
                    buf += chunk
            except (OSError, ValueError, TimeoutError):
                return 0, None, None, None
            return status, ctype, length, bytes(buf)
    return 0, None, None, None


_get = http_get          # Tests ersetzen das (tests/mockserver.py)


def fetch_direct(url):
    """Ein direkter Versuch -> Bilddaten oder None"""
    try:
        status, ctype, length, data = _get(url)
    except Exception:  # noqa: BLE001 - nie die Oberflaeche stoeren
        return None
    return data if accept(status, ctype, length, data) else None


# --- Zwischenspeicher ------------------------------------------------------------------------------------------------

_mem = {}
_lock = threading.Lock()


def _dir():
    return os.path.join(config.CACHE_DIR, "favicons")


def forget_memory():
    with _lock:
        _mem.clear()


def load_blocking(urls, decodes, now=None):
    """Hintergrund-Thread: erstes brauchbare Bild der Kandidaten (aus dem Zwischenspeicher oder direkt) oder None.
    decodes(daten) -> bool prueft, ob sich das Bild wirklich oeffnen laesst."""
    if not urls:
        return None
    key = cache_key(urls)
    with _lock:
        if key in _mem:
            return _mem[key]
    now = time.time() if now is None else now
    d = _dir()
    img, miss = os.path.join(d, key + ".img"), os.path.join(d, key + ".miss")
    try:
        if cache_decision(True, os.path.getmtime(img), now) == "use":
            with open(img, "rb") as f:
                data = f.read()
            if decodes(data):
                with _lock:
                    _mem[key] = data
                return data
    except OSError:
        pass
    try:
        if cache_decision(False, os.path.getmtime(miss), now) == "missing":
            return None
    except OSError:
        pass
    for u in urls:
        data = fetch_direct(u)
        if data is not None and decodes(data):
            try:
                os.makedirs(d, mode=0o700, exist_ok=True)
                config.atomic_write(img, data)
                if os.path.exists(miss):
                    os.remove(miss)
            except OSError:
                pass
            with _lock:
                _mem[key] = data
            return data
    try:
        os.makedirs(d, mode=0o700, exist_ok=True)
        config.atomic_write(miss, b"")
        if os.path.exists(img):
            os.remove(img)
    except OSError:
        pass
    return None
