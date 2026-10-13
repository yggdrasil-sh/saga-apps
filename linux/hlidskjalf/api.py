"""Server-Zugriff: Sitzungs-Cookie, still erneuern mit dem Geraete-Schluessel (wie die Mac-/iOS-App)."""
import base64
import hashlib
import json
import os
import secrets
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.cookies import SimpleCookie

from . import config
from .i18n import _, ui_language

COOKIE_FILE = os.path.join(config.DATA_DIR, "cookies.json")
ACCEPT_LANGUAGE = {"de": "de, en;q=0.5", "es": "es, en;q=0.5"}.get(ui_language(), "en")


class APIError(Exception):
    def __init__(self, code, msg=""):
        super().__init__(msg or f"HTTP {code}")
        self.code = code
        self.msg = msg
        self.explicit = False

    def __str__(self):
        if self.code == 401:
            return self.msg if self.explicit else _("Please sign in")
        if self.code == 0:
            return self.msg or _("Server not reachable")
        return self.msg or _("Error {code}").format(code=self.code)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_opener = urllib.request.build_opener(_NoRedirect)


class Client:
    def __init__(self):
        self.lock = threading.Lock()
        self.resume_lock = threading.Lock()
        self.cookies = {}
        self.on_need_login = None          # Rueckruf (Hintergrund-Thread!) wenn erneute Anmeldung noetig
        self._resumed_at = 0.0
        try:
            with open(COOKIE_FILE, encoding="utf-8") as f:
                self.cookies = json.load(f)
        except (OSError, ValueError):
            self.cookies = {}

    # --- Cookies --------------------------------------------------------------------------
    def _save_cookies(self):
        with self.lock:
            data = json.dumps(self.cookies)
            fd = os.open(COOKIE_FILE + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                f.write(data)
            os.replace(COOKIE_FILE + ".tmp", COOKIE_FILE)

    def _take_cookies(self, headers):
        changed = False
        for raw in headers.get_all("Set-Cookie") or []:
            c = SimpleCookie()
            try:
                c.load(raw)
            except Exception:  # noqa: BLE001
                continue
            for k, m in c.items():
                gone = m["max-age"] in ("0", "-1") or (not m.value)
                with self.lock:
                    if gone:
                        changed |= self.cookies.pop(k, None) is not None
                    elif self.cookies.get(k) != m.value:
                        self.cookies[k] = m.value
                        changed = True
        if changed:
            self._save_cookies()

    def clear_cookies(self):
        with self.lock:
            self.cookies = {}
        self._save_cookies()

    def _cookie_header(self):
        with self.lock:
            return "; ".join(f"{k}={v}" for k, v in self.cookies.items())

    # --- Anfragen --------------------------------------------------------------------------
    def url(self, path):
        if path.startswith("http"):
            return path
        return config.settings.server + "/" + path.lstrip("/")

    def _send(self, method, path, data=None, ctype=None, timeout=20, auth=True):
        if not self.url(path).startswith(("https://", "http://")):
            raise APIError(0, _("No server address set"))
        req = urllib.request.Request(self.url(path), data=data, method=method)
        req.add_header("X-Hlidskjalf", "1")
        req.add_header("User-Agent", "Hlidskjalf-Linux/1.0")
        req.add_header("Accept-Language", ACCEPT_LANGUAGE)       # Fehlermeldungen des Servers in der Sprache der App
        if ctype:
            req.add_header("Content-Type", ctype)
        if auth:
            ch = self._cookie_header()
            if ch:
                req.add_header("Cookie", ch)
        try:
            with _opener.open(req, timeout=timeout) as r:
                body = r.read()
                self._take_cookies(r.headers)
                return r.status, body
        except urllib.error.HTTPError as e:
            body = e.read() or b""
            if e.headers:
                self._take_cookies(e.headers)
            return e.code, body
        except (urllib.error.URLError, OSError, TimeoutError) as e:
            raise APIError(0, _("Server not reachable: {error}").format(error=getattr(e, "reason", e))) from e

    def request(self, method, path, body=None, raw=None, ctype=None, timeout=20):
        """JSON-Anfrage -> Bytes. 401: einmal still erneuern und wiederholen, sonst Anmeldung."""
        data = raw
        if body is not None:
            data = json.dumps(body).encode()
            ctype = "application/json"
        if not path.startswith("http") and not config.settings.server:   # noch kein Server -> Anmeldeseite
            if self.on_need_login:
                self.on_need_login()
            raise APIError(401)
        for attempt in range(2):
            code, out = self._send(method, path, data, ctype, timeout)
            if code != 401:
                break
            if attempt == 0:
                r = self.resume()
                if r == "ok":
                    continue
                if r == "offline":
                    raise APIError(0, _("Server not reachable"))
            if self.on_need_login:
                self.on_need_login()
            raise APIError(401)
        if not 200 <= code < 300:
            msg = ""
            try:
                msg = json.loads(out).get("error", "")
            except (ValueError, AttributeError):
                pass
            raise APIError(code, msg)
        return out

    def json(self, method, path, body=None, **kw):
        out = self.request(method, path, body, **kw)
        return json.loads(out) if out else None

    def get(self, path):
        return self.json("GET", path)

    # --- Geraete-Anmeldung --------------------------------------------------------------------
    def resume(self):
        """'ok' | 'rejected' | 'offline' - gleichzeitige Anfragen teilen sich einen Versuch"""
        before = self._resumed_at
        with self.resume_lock:
            if self._resumed_at > before:          # ein anderer Thread hat inzwischen erneuert
                return "ok"
            tok = config.get_device_token()
            if not tok:
                return "rejected"
            try:
                body = {"device_token": tok}
                key = config.settings.get("token") or ""
                if key.startswith("hk_"):
                    body["app_key"] = key                  # Schluessel diesem Geraet zuordnen (Einstellungen: eine Liste)
                code, _ = self._send("POST", "app/resume", json.dumps(body).encode(), "application/json", auth=False)
            except APIError:
                return "offline"
            if code in (401, 403):
                config.set_device_token(None)
                return "rejected"
            if code != 200:
                return "offline"
            self._resumed_at = time.time()
            return "ok"

    def exchange(self, code, verifier, want_app_key=False):
        body = {"code": code, "verifier": verifier, "device": config.device_name(), "app_key": want_app_key}
        st, out = self._send("POST", "app/exchange", json.dumps(body).encode(), "application/json", auth=False)
        j = {}
        try:
            j = json.loads(out)
        except ValueError:
            pass
        if st != 200:
            e = APIError(st, j.get("error") or _("Sign-in failed"))
            e.explicit = bool(j.get("error"))
            raise e
        self.adopt(j)
        return j

    def pair(self, code, want_app_key=False):
        """Anmeldung per Kopplungs-Code (QR-Code/Link aus dem Web) -> Sitzung + Geraete-Schluessel"""
        body = {"code": code, "device": config.device_name(), "app_key": want_app_key}
        st, out = self._send("POST", "app/pair", json.dumps(body).encode(), "application/json", auth=False)
        j = {}
        try:
            j = json.loads(out)
        except ValueError:
            pass
        if st != 200:
            e = APIError(st, j.get("error") or _("Sign-in failed"))
            e.explicit = bool(j.get("error"))
            raise e
        self.adopt(j)
        return j

    def ensure_device_token(self):
        """schon angemeldet, aber noch kein Geraete-Schluessel -> still nachholen"""
        if config.get_device_token():
            return
        j = self.json("POST", "app/device", {"device": config.device_name(), "app_key": False})
        self.adopt(j or {})

    def adopt(self, j):
        t = j.get("device_token")
        if t and t.startswith("hkd_"):
            config.set_device_token(t)
        k = j.get("app_key")
        if k and k.startswith("hk_") and not (config.settings.get("token") or "").startswith("hk_"):
            config.settings.set("token", k)

    def forget(self):
        tok = config.get_device_token()
        if tok:
            try:
                self._send("POST", "app/forget", json.dumps({"device_token": tok}).encode(), "application/json", timeout=8, auth=False)
            except APIError:
                pass
        config.set_device_token(None)
        config.settings.set("token", "")                   # der Server loescht den Schluessel dieses Geraets mit
        self.clear_cookies()

    # --- Dateien ----------------------------------------------------------------------------
    def upload(self, data, name, field="image", timeout=120):
        boundary = "hk-" + uuid.uuid4().hex
        safe = name.replace('"', "_")
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{safe}\"\r\n"
                f"Content-Type: application/octet-stream\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
        j = json.loads(self.request("POST", "api/files", raw=body, ctype=f"multipart/form-data; boundary={boundary}", timeout=timeout))
        f = (j or {}).get("file") or {}
        if not f.get("url"):
            raise APIError(500, (j or {}).get("error") or _("Upload failed"))
        return f

    def fetch(self, path, timeout=60):
        """Datei/Bild mit Sitzung laden (Bytes)"""
        return self.request("GET", path, timeout=timeout)


def pkce():
    def b64(d):
        return base64.urlsafe_b64encode(d).decode().rstrip("=")
    verifier = b64(secrets.token_bytes(48))
    challenge = b64(hashlib.sha256(verifier.encode()).digest())
    return verifier, challenge


client = Client()
