"""Server-Zugriff: Sitzungs-Cookie, still erneuern mit dem Geraete-Schluessel (wie die Mac-/iOS-App)."""
import base64
import gzip
import hashlib
import json
import os
import secrets
import threading
import time
import urllib.error
import urllib.request
import uuid
import zlib
from http.cookies import SimpleCookie

from . import config
from .i18n import _, ui_language

COOKIE_FILE = os.path.join(config.DATA_DIR, "cookies.json")
ACCEPT_LANGUAGE = {"de": "de, en;q=0.5", "es": "es, en;q=0.5", "ru": "ru, en;q=0.5", "th": "th, en;q=0.5"}.get(ui_language(), "en")


SWITCHED = -1      # APIError.code: inzwischen anderer Server/anderes Konto - nichts gesendet (Entwurf bleibt)


def _decoded(body, headers):
    """Antwort mit Content-Encoding: gzip entpacken (Anfragen senden Accept-Encoding: gzip; urllib entpackt nicht selbst)"""
    enc = ((headers.get("Content-Encoding") if headers is not None else None) or "").strip().lower()
    if enc == "gzip" and body:
        return gzip.decompress(body)
    return body


def _unreachable(e):
    """Verbindungsfehler -> Text fuer die Oberflaeche: ohne rohe Systemmeldung ("[Errno 111] Connection refused",
    englisch in jeder Sprache, BUG-113); nur Zertifikatsfehler behalten ihre Einzelheit (die braucht man zum Beheben)"""
    import ssl
    r = getattr(e, "reason", e)
    if isinstance(r, (ssl.SSLError, ssl.CertificateError)):
        return _("Server not reachable: {error}").format(error=r)
    return _("Server not reachable")


class APIError(Exception):
    def __init__(self, code, msg=""):
        super().__init__(msg or f"HTTP {code}")
        self.code = code
        self.msg = msg
        self.explicit = False
        self.data = None            # JSON-Antwort des Servers bei Fehlern (z. B. need_name, suggested_name)

    def __str__(self):
        if self.code == 401:
            return self.msg if self.explicit else _("Please sign in")
        if self.code == 0:
            return self.msg or _("Server not reachable")
        return self.msg or _("Error {code}").format(code=self.code)


class Cancelled(Exception):
    """vom Nutzer abgebrochen (Download)"""


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_opener = urllib.request.build_opener(_NoRedirect)


class Client:
    """Sitzungs-Cookies gehoeren genau einem Server (cookie_server) und gehen nie an eine andere Adresse."""

    def __init__(self):
        self.lock = threading.Lock()
        self.resume_lock = threading.Lock()
        self.cookies = {}
        self.cookie_server = ""
        self.on_need_login = None          # Rueckruf (Hintergrund-Thread!) wenn erneute Anmeldung noetig
        self._resumed_at = 0.0
        try:
            with open(COOKIE_FILE, encoding="utf-8") as f:
                d = json.load(f)
        except (OSError, ValueError):
            d = {}
        if isinstance(d, dict) and isinstance(d.get("cookies"), dict):
            self.cookie_server, self.cookies = d.get("server") or "", dict(d["cookies"])
        elif isinstance(d, dict) and d:                    # altes Format: nur Cookies -> gehoerten zum eingetragenen Server
            self.cookie_server, self.cookies = config.origin(config.settings.get("server") or ""), dict(d)

    # --- Cookies --------------------------------------------------------------------------
    def _save_cookies(self):
        with self.lock:
            data = json.dumps({"server": self.cookie_server, "cookies": self.cookies})
        try:
            config.atomic_write(COOKIE_FILE, data)
        except OSError:
            pass

    @staticmethod
    def _parse_cookies(headers):
        out = []
        for raw in headers.get_all("Set-Cookie") or []:
            c = SimpleCookie()
            try:
                c.load(raw)
            except Exception:  # noqa: BLE001
                continue
            for k, m in c.items():
                out.append((k, None if (m["max-age"] in ("0", "-1") or not m.value) else m.value))
        return out

    def take_cookies_for(self, url, headers, jar=None):
        """Set-Cookie einer Antwort von url: in jar (Anmeldung an einem neuen Server) oder in die Sitzung -
        aber nur, wenn url zum eingetragenen Server gehoert."""
        pairs = self._parse_cookies(headers)
        if not pairs:
            return
        if jar is not None:
            for k, v in pairs:
                if v is None:
                    jar.pop(k, None)
                else:
                    jar[k] = v
            return
        o = config.origin(url)
        changed = False
        with self.lock:
            if o != self.cookie_server:
                if o != config.origin(config.settings.server):
                    return                                # fremde Adresse: nichts uebernehmen
                self.cookie_server, self.cookies, changed = o, {}, True
            for k, v in pairs:
                if v is None:
                    changed |= self.cookies.pop(k, None) is not None
                elif self.cookies.get(k) != v:
                    self.cookies[k] = v
                    changed = True
        if changed:
            self._save_cookies()

    def cookie_header_for(self, url, jar=None):
        if jar is not None:
            return "; ".join(f"{k}={v}" for k, v in jar.items())
        o = config.origin(url)
        with self.lock:
            if not o or o != self.cookie_server or o != config.origin(config.settings.server):
                return ""
            return "; ".join(f"{k}={v}" for k, v in self.cookies.items())

    def set_cookies(self, server, cookies):
        with self.lock:
            self.cookie_server, self.cookies = config.origin(server), dict(cookies or {})
        self._save_cookies()

    def clear_cookies(self):
        with self.lock:
            self.cookies = {}
        self._save_cookies()

    @property
    def has_session(self):
        with self.lock:
            return bool(self.cookies) and self.cookie_server == config.origin(config.settings.server)

    # --- Anfragen --------------------------------------------------------------------------
    def url(self, path, base=None):
        """Adresse fuer einen Pfad. Absolute Adressen nur auf demselben Server (Sitzungs-Cookie bleibt dort)."""
        b = (base or config.settings.server).rstrip("/")
        if path.startswith(("http://", "https://")):
            if config.origin(path) != config.origin(b):
                raise APIError(0, _("This address belongs to another server."))
            return path
        return b + "/" + path.lstrip("/")

    def _send(self, method, path, data=None, ctype=None, timeout=20, auth=True, base=None, jar=None, headers=None):
        url = self.url(path, base)
        if not url.startswith(("https://", "http://")):
            raise APIError(0, _("No server address set"))
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("X-Saga", "1")                            # Schutz vor Cross-Site-Anfragen (Server ab 1.2)
        req.add_header("X-Hlidskjalf", "1")                      # ... und fuer aeltere Server
        req.add_header("User-Agent", "Hlidskjalf-Linux/1.0")
        req.add_header("Accept-Language", ACCEPT_LANGUAGE)       # Fehlermeldungen des Servers in der Sprache der App
        req.add_header("Accept-Encoding", "gzip")                # grosse JSON-Listen komprimiert (falls der Server es kann)
        if ctype:
            req.add_header("Content-Type", ctype)
        for k, v in (headers or {}).items():                     # z. B. X-Saga-Note (Upload in eine fremde Seite)
            req.add_header(k, v)
        if auth:
            ch = self.cookie_header_for(url, jar)
            if ch:
                req.add_header("Cookie", ch)
        try:
            with _opener.open(req, timeout=timeout) as r:
                body = _decoded(r.read(), r.headers)
                self.take_cookies_for(url, r.headers, jar)
                return r.status, body
        except urllib.error.HTTPError as e:
            body = _decoded(e.read() or b"", e.headers)
            if e.headers:
                self.take_cookies_for(url, e.headers, jar)
            return e.code, body
        except (urllib.error.URLError, OSError, TimeoutError, EOFError, zlib.error) as e:
            raise APIError(0, _unreachable(e)) from e

    def request(self, method, path, body=None, raw=None, ctype=None, timeout=20, server=None, account=None, headers=None):
        """JSON-Anfrage -> Bytes. 401: einmal still erneuern und wiederholen, sonst Anmeldung.
        server/account: nur senden, solange noch genau dieser Server (und dieses Konto) eingetragen ist - sonst
        APIError(SWITCHED), ohne etwas zu senden (z. B. Seite des alten Servers nach einem Wechsel).
        headers: zusaetzliche Kopfzeilen (nur wenn noetig - die Tests ersetzen _send)."""
        data = raw
        if body is not None:
            data = json.dumps(body).encode()
            ctype = "application/json"
        if not path.startswith("http") and not config.settings.server:   # noch kein Server -> Anmeldeseite
            if self.on_need_login:
                self.on_need_login()
            raise APIError(401)

        def check():
            if server is not None and config.origin(server) != config.origin(config.settings.server):
                raise APIError(SWITCHED)
            if account is not None and config.account(server) != account:
                raise APIError(SWITCHED)
        for attempt in range(2):
            check()
            code, out = self._send(method, path, data, ctype, timeout, headers=headers) if headers else \
                self._send(method, path, data, ctype, timeout)
            if code != 401:
                break
            if attempt == 0:
                r = self.resume()
                if r == "ok":
                    continue
                if r == "offline":
                    raise APIError(0, _("Server not reachable"))
            check()                                    # inzwischen gewechselt: keine Anmeldung verlangen
            if self.on_need_login:
                self.on_need_login()
            raise APIError(401)
        if not 200 <= code < 300:
            data = None
            try:
                data = json.loads(out)
            except ValueError:
                pass
            data = data if isinstance(data, dict) else None
            e = APIError(code, str((data or {}).get("error") or ""))
            e.data = data
            raise e
        return out

    def json(self, method, path, body=None, **kw):
        out = self.request(method, path, body, **kw)
        return json.loads(out) if out else None

    def get(self, path):
        return self.json("GET", path)

    # --- Geraete-Anmeldung --------------------------------------------------------------------
    def resume(self):
        """'ok' | 'rejected' | 'offline' - gleichzeitige Anfragen teilen sich einen Versuch.
        Geraete- und App-Schluessel gehoeren zum eingetragenen Server und gehen nur an genau diesen."""
        before = self._resumed_at
        with self.resume_lock:
            if self._resumed_at > before:          # ein anderer Thread hat inzwischen erneuert
                return "ok"
            srv = config.settings.server
            tok = config.get_device_token(srv)
            if not tok or not srv:
                return "rejected"
            try:
                body = {"device_token": tok}
                key = config.app_key(srv)
                if key.startswith("hk_"):
                    body["app_key"] = key                  # Schluessel diesem Geraet zuordnen (Einstellungen: eine Liste)
                code, _ = self._send("POST", "app/resume", json.dumps(body).encode(), "application/json", auth=False, base=srv)
            except APIError:
                return "offline"
            if code in (401, 403):
                config.set_device_token(None, srv)
                return "rejected"
            if code != 200:
                return "offline"
            self._resumed_at = time.time()
            return "ok"

    def _login_post(self, path, body, server, jar):
        st, out = self._send("POST", path, json.dumps(body).encode(), "application/json", base=server, jar=jar)
        j = {}
        try:
            j = json.loads(out)
        except ValueError:
            pass
        if st != 200 or not isinstance(j, dict):
            e = APIError(st, (j.get("error") if isinstance(j, dict) else "") or _("Sign-in failed"))
            e.explicit = bool(isinstance(j, dict) and j.get("error"))
            raise e
        return j

    def exchange(self, code, verifier, server, want_app_key=False):
        """Anmeldung aus dem Browser (PKCE) an server. Uebernimmt noch nichts: -> (Antwort, Cookies); danach adopt_login."""
        jar = {}
        body = {"code": code, "verifier": verifier, "device": config.device_name(), "app_key": want_app_key}
        return self._login_post("app/exchange", body, server, jar), jar

    def pair(self, code, server, want_app_key=False):
        """Anmeldung per Kopplungs-Code (QR-Code/Link aus dem Web) an server -> (Antwort, Cookies); danach adopt_login."""
        jar = {}
        body = {"code": code, "device": config.device_name(), "app_key": want_app_key}
        return self._login_post("app/pair", body, server, jar), jar

    def adopt_login(self, server, j, jar):
        """Erfolgreiche Anmeldung uebernehmen: Server, Sitzung, Geraete- und App-Schluessel - alles an server gebunden"""
        server = config.origin(server) or server
        config.settings.server = server
        self.set_cookies(server, jar)
        self.adopt(j, server)
        self._resumed_at = time.time()

    def ensure_device_token(self):
        """schon angemeldet, aber noch kein Geraete-Schluessel -> still nachholen"""
        srv = config.settings.server
        if not srv or config.get_device_token(srv):
            return
        j = self.json("POST", "app/device", {"device": config.device_name(), "app_key": False})
        if srv == config.settings.server:
            self.adopt(j or {}, srv)

    def adopt(self, j, server=None):
        srv = server or config.settings.server
        t = j.get("device_token")
        if t and t.startswith("hkd_"):
            config.set_device_token(t, srv)
        k = j.get("app_key")
        if k and k.startswith("hk_") and not config.app_key(srv).startswith("hk_"):
            config.set_app_key(k, srv)

    def forget_server(self, srv, tok=None, switching=False):
        """Dieses Geraet bei srv abmelden (/app/forget, nur dorthin geht dessen Schluessel) und Geraete- und
        App-Schluessel von srv hier loeschen. Ist srv nicht erreichbar, wird trotzdem lokal geloescht (blockiert nichts).
        switching: Serverwechsel - ist srv inzwischen wieder eingetragen (schnell zurueckgewechselt), bleiben dessen
        neue Schluessel hier stehen."""
        srv = config.origin(srv) or srv
        if not srv:
            return
        tok = tok or config.get_device_token(srv)
        if tok:
            try:
                self._send("POST", "app/forget", json.dumps({"device_token": tok}).encode(), "application/json", timeout=8,
                           auth=False, base=srv)
            except APIError:
                pass
        if switching and config.origin(config.settings.server) == srv:
            return
        config.set_device_token(None, srv)
        config.set_app_key(None, srv)                       # der Server loescht den Schluessel dieses Geraets mit

    def forget(self):
        srv = config.settings.server
        if srv:
            self.forget_server(srv)
        self.clear_cookies()

    # --- Dateien ----------------------------------------------------------------------------
    def download(self, path, write, cancelled=None, progress=None, timeout=60):
        """Datei stueckweise laden (nicht ganz in den Speicher): write(bytes) je Stueck. -> Anzahl Bytes.
        cancelled() -> True bricht ab (Cancelled); progress(geladen, gesamt|0)."""
        for attempt in range(2):
            url = self.url(path)
            req = urllib.request.Request(url, method="GET")
            req.add_header("X-Saga", "1")
            req.add_header("X-Hlidskjalf", "1")
            req.add_header("User-Agent", "Hlidskjalf-Linux/1.0")
            req.add_header("Accept-Language", ACCEPT_LANGUAGE)
            ch = self.cookie_header_for(url)
            if ch:
                req.add_header("Cookie", ch)
            try:
                r = _opener.open(req, timeout=timeout)
            except urllib.error.HTTPError as e:
                if e.code == 401 and attempt == 0 and self.resume() == "ok":
                    continue
                raise APIError(e.code) from e
            except (urllib.error.URLError, OSError, TimeoutError) as e:
                raise APIError(0, _unreachable(e)) from e
            with r:
                total = int(r.headers.get("Content-Length") or 0)
                got = 0
                while True:
                    if cancelled and cancelled():
                        raise Cancelled()
                    chunk = r.read(256 * 1024)
                    if not chunk:
                        break
                    write(chunk)
                    got += len(chunk)
                    if progress:
                        progress(got, total)
            return got
        raise APIError(401)

    def upload(self, data, name, field="image", timeout=120, note=None):
        """Datei hochladen. note: ID einer fremden Seite (Rolle 'Bearbeiten') - die Datei gehoert dann deren
        Eigentuemer (Kopfzeile X-Saga-Note, Server ab 1.3)."""
        boundary = "hk-" + uuid.uuid4().hex
        safe = "".join("_" if c in '"\r\n\\' or ord(c) < 32 else c for c in (name or "datei"))[:200]
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{safe}\"\r\n"
                f"Content-Type: application/octet-stream\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
        hd = {"X-Saga-Note": str(int(note))} if note else None
        j = json.loads(self.request("POST", "api/files", raw=body, ctype=f"multipart/form-data; boundary={boundary}", timeout=timeout,
                                    headers=hd))
        f = (j or {}).get("file") or {}
        if not f.get("url"):
            raise APIError(500, (j or {}).get("error") or _("Upload failed"))
        return f

    def fetch(self, path, timeout=60):
        """Datei/Bild mit Sitzung laden (Bytes)"""
        return self.request("GET", path, timeout=timeout)

    # --- Ereignis-Stroeme (text/event-stream): Live-Kanal, KI-Assistent ------------------------------------
    def _open_stream(self, method, url, data, headers, timeout):
        """-> (status, Antwort mit readline()/close()) - die Tests ersetzen diese Methode"""
        req = urllib.request.Request(url, data=data, method=method)
        for k, v in headers.items():
            req.add_header(k, v)
        try:
            r = _opener.open(req, timeout=timeout)
            return r.status, r
        except urllib.error.HTTPError as e:
            return e.code, e
        except (urllib.error.URLError, OSError, TimeoutError) as e:
            raise APIError(0, _unreachable(e)) from e

    def stream(self, method, path, body=None, headers=None, timeout=75):
        """SSE-Anfrage (Sitzung + X-Saga) -> offene Antwort (Zeilen mit readline). Fehler vor dem Strom: APIError mit
        der Meldung des Servers (JSON {error}); 401 einmal still erneuern. timeout = Lesezeit ohne jede Zeile (Ping)."""
        data = json.dumps(body).encode() if body is not None else None
        for attempt in range(2):
            url = self.url(path)
            hd = {"X-Saga": "1", "X-Hlidskjalf": "1", "User-Agent": "Hlidskjalf-Linux/1.0", "Accept-Language": ACCEPT_LANGUAGE,
                  "Accept": "text/event-stream", "Cache-Control": "no-cache"}
            if data is not None:
                hd["Content-Type"] = "application/json"
            hd.update(headers or {})
            ch = self.cookie_header_for(url)
            if ch:
                hd["Cookie"] = ch
            code, r = self._open_stream(method, url, data, hd, timeout)
            if code == 401 and attempt == 0:
                _close(r)
                res = self.resume()
                if res == "ok":
                    continue
                if res == "offline":
                    raise APIError(0, _("Server not reachable"))
                if self.on_need_login:
                    self.on_need_login()
                raise APIError(401)
            if not 200 <= code < 300:
                out = b""
                try:
                    out = r.read() or b""
                except (OSError, AttributeError, ValueError):
                    pass
                _close(r)
                try:
                    j = json.loads(out)
                except ValueError:
                    j = None
                j = j if isinstance(j, dict) else None
                e = APIError(code, str((j or {}).get("error") or ""))
                e.data = j
                if code == 401 and self.on_need_login:
                    self.on_need_login()
                raise e
            hdrs = getattr(r, "headers", None)
            if hdrs is not None and hasattr(hdrs, "get_all"):
                self.take_cookies_for(url, hdrs)
            return r
        raise APIError(401)


def _close(r):
    try:
        r.close()
    except (OSError, AttributeError):
        pass


def sse_events(resp, stop=None, on_line=None):
    """Zeilen eines text/event-stream -> (event, data, id). Kommentarzeilen (': ping') und Leerlauf ignorieren;
    on_line() bei JEDER Zeile (Lebenszeichen). stop() -> True beendet. Kaputtes JSON: data = None."""
    ev, buf, eid = None, [], None
    while True:
        if stop and stop():
            return
        line = resp.readline()
        if not line:
            return
        if on_line:
            on_line()
        line = line.decode("utf-8", "replace") if isinstance(line, bytes) else line
        line = line.rstrip("\r\n")
        if not line:
            if ev is not None or buf:
                raw = "\n".join(buf)
                try:
                    data = json.loads(raw) if raw else {}
                except ValueError:
                    data = None
                yield ev or "message", data, eid
            ev, buf, eid = None, [], None
            continue
        if line.startswith(":"):
            continue
        k, _sep, v = line.partition(":")
        v = v[1:] if v.startswith(" ") else v
        if k == "event":
            ev = v
        elif k == "data":
            buf.append(v)
        elif k == "id":
            eid = v


def pkce():
    def b64(d):
        return base64.urlsafe_b64encode(d).decode().rstrip("=")
    verifier = b64(secrets.token_bytes(48))
    challenge = b64(hashlib.sha256(verifier.encode()).digest())
    return verifier, challenge


client = Client()
