"""Einstellungen (~/.config/hlidskjalf), Zwischenspeicher (~/.cache/hlidskjalf), Geraete-Schluessel (Schluesselbund)."""
import json
import os
import re
import socket
import tempfile
import threading
import urllib.parse

from gi.repository import GLib

from . import APP_ID
from .i18n import _

# Kein fester Server: Adresse beim ersten Start eingeben oder per Kopplungslink anmelden
DEFAULT_SERVER = ""
# Fruehere Adressen desselben Servers -> automatisch auf die neue (Daten und Anmeldung bleiben gleich),
# z. B. {"https://alt.example.org": "https://saga.example.org"}
MOVED_SERVERS = {}

# Ordner haengen nicht von der App-ID ab (bleiben bei einem Wechsel der ID erhalten)
CONFIG_DIR = os.path.join(GLib.get_user_config_dir(), "hlidskjalf")
CACHE_DIR = os.path.join(GLib.get_user_cache_dir(), "hlidskjalf")
DATA_DIR = os.path.join(GLib.get_user_data_dir(), "hlidskjalf")
for _d in (CONFIG_DIR, CACHE_DIR, os.path.join(CACHE_DIR, "files"), DATA_DIR):
    os.makedirs(_d, mode=0o700, exist_ok=True)

_SETTINGS = os.path.join(CONFIG_DIR, "settings.json")


def atomic_write(path, data, mode=0o600):
    """Datei ersetzen ueber eine eindeutige Zwischendatei (gleichzeitige Schreiber stoeren sich nicht)"""
    d = os.path.dirname(path)
    fd, tmp = tempfile.mkstemp(dir=d, prefix=".tmp-")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data if isinstance(data, bytes) else data.encode("utf-8"))
        os.chmod(tmp, mode)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.remove(tmp)
        except OSError:
            pass
        raise


def origin(url):
    """'https://Saga.Example.org:443/x' -> 'https://saga.example.org' (Schema, Host, Port - ohne Pfad)"""
    try:
        u = urllib.parse.urlsplit((url or "").strip())
    except ValueError:
        return ""
    if u.scheme not in ("https", "http") or not u.hostname:
        return ""
    host = u.hostname.lower()
    if ":" in host:
        host = f"[{host}]"
    try:
        port = u.port
    except ValueError:
        return ""
    if port and not ((u.scheme == "https" and port == 443) or (u.scheme == "http" and port == 80)):
        host += f":{port}"
    return f"{u.scheme}://{host}"


def debug_mode():
    return bool(os.environ.get("HK_TEST") or os.environ.get("HK_DEBUG"))


def allowed_server(url):
    """Nur https - http nur fuer localhost im Test-/Debug-Modus (HK_TEST bzw. HK_DEBUG)"""
    o = origin(url)
    if o.startswith("https://"):
        return True
    host = urllib.parse.urlsplit(o).hostname or ""
    return o.startswith("http://") and host in ("localhost", "127.0.0.1", "::1") and debug_mode()


class Settings:
    """settings.json - aus jedem Thread nutzbar (Sperre); set_soon fasst schnelle Aenderungen (Ziehen) zusammen"""

    def __init__(self):
        self.lock = threading.RLock()
        self._soon = 0
        try:
            with open(_SETTINGS, encoding="utf-8") as f:
                self.d = json.load(f)
        except (OSError, ValueError):
            self.d = {}

    def get(self, k, default=None):
        with self.lock:
            return self.d.get(k, default)

    def _put(self, k, v):
        if v is None:
            self.d.pop(k, None)
        else:
            self.d[k] = v

    def set(self, k, v):
        with self.lock:
            self._put(k, v)
            self._write()

    def set_soon(self, k, v):
        """Wert sofort im Speicher, auf die Platte erst nach einer halben Sekunde Ruhe"""
        with self.lock:
            self._put(k, v)
            if not self._soon:
                self._soon = GLib.timeout_add(500, self._flush_soon)

    def _flush_soon(self):
        with self.lock:
            self._soon = 0
            self._write()
        return False

    def flush(self):
        with self.lock:
            if self._soon:
                GLib.source_remove(self._soon)
                self._soon = 0
                self._write()

    def _write(self):
        try:
            atomic_write(_SETTINGS, json.dumps(self.d, ensure_ascii=False, indent=1))
        except OSError:
            pass

    @property
    def server(self):
        s = (self.get("server") or DEFAULT_SERVER).rstrip("/")
        if not s:                                         # nichts eingetragen, aber schon angemeldet -> dessen Server
            s = _server_from_keyring()
            if s:
                self.set("server", s)
        if s in MOVED_SERVERS:
            s = MOVED_SERVERS[s]
            self.set("server", s)
        return s

    @server.setter
    def server(self, v):
        self.set("server", (v or "").strip().rstrip("/") or None)


settings = Settings()


# --- App-Schluessel (hk_..., Widgets/Teilen): je Server gespeichert, geht nie an einen anderen Server ----------
def app_key(server=None):
    srv = origin(server or settings.server)
    keys = settings.get("app_keys") or {}
    return keys.get(srv) or "" if srv else ""


def set_app_key(v, server=None):
    srv = origin(server or settings.server)
    if not srv:
        return
    with settings.lock:
        keys = dict(settings.get("app_keys") or {})
        if v:
            keys[srv] = v
        else:
            keys.pop(srv, None)
        settings.set("app_keys", keys or None)


def _migrate_app_key():
    """frueher: settings["token"] ohne Server -> dem eingetragenen Server zuordnen"""
    old = settings.get("token")
    if old is None:
        return
    srv = origin(settings.get("server") or "")
    if srv and isinstance(old, str) and old.startswith("hk_") and not app_key(srv):
        set_app_key(old, srv)
    settings.set("token", None)


_migrate_app_key()


# --- Konto je Server (Benutzername aus /api/account): Entwuerfe sind je Server UND Konto getrennt ----------------
def account(server=None):
    """Angemeldetes Konto an diesem Server - None, solange unbekannt (abgemeldet, neu angemeldet, noch nicht geladen)"""
    srv = origin(server or settings.server)
    return (settings.get("accounts") or {}).get(srv) if srv else None


def set_account(name, server=None):
    srv = origin(server or settings.server)
    if not srv:
        return
    with settings.lock:
        accs = dict(settings.get("accounts") or {})
        if name:
            accs[srv] = name
        else:
            accs.pop(srv, None)
        settings.set("accounts", accs or None)


def _pretty_hostname():
    """hostnamectl: PRETTY_HOSTNAME aus /etc/machine-info (z. B. "Annas Laptop")"""
    try:
        with open("/etc/machine-info", encoding="utf-8") as f:
            for line in f:
                if line.startswith("PRETTY_HOSTNAME="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
    except OSError:
        pass
    return ""


def _os_name():
    try:
        with open("/etc/os-release", encoding="utf-8") as f:
            for line in f:
                if line.startswith("NAME="):
                    return line.split("=", 1)[1].strip().strip('"')
    except OSError:
        pass
    return ""


def device_name():
    """Geraetename fuer den Server: "<Rechnername> (Linux)"; Rechnername aus hostnamectl (huebsch) bzw. Hostname -
    technische Namen (Container-Kennung, localhost) durch den Namen des Systems ersetzen"""
    h = _pretty_hostname() or (GLib.get_host_name() or socket.gethostname() or "").split(".")[0]
    if not h or h.lower() in ("localhost", "localhost-live", "fedora") or re.fullmatch(r"[0-9a-f]{12,64}", h):
        osn = _os_name()
        return osn if "linux" in osn.lower() else (osn + " (Linux)" if osn else "Linux")
    return h + " (Linux)"


# --- Geraete-Schluessel: libsecret (GNOME-Schluesselbund), sonst Datei mit 0600 -----------
_FALLBACK = os.path.join(DATA_DIR, "device_token")
try:
    import gi
    gi.require_version("Secret", "1")
    from gi.repository import Secret
    _SCHEMA = Secret.Schema.new(APP_ID + ".Device", Secret.SchemaFlags.NONE,
                                {"server": Secret.SchemaAttributeType.STRING})
except (ImportError, ValueError):
    Secret = None
    _SCHEMA = None
if os.environ.get("HK_NO_KEYRING"):                       # Tests: nie den Schluesselbund des Rechners anfassen
    Secret = None


def _attrs(server=None):
    return {"server": (server or settings.server).rstrip("/")}


_searched = False


def _server_from_keyring():
    """Keine Server-Adresse eingetragen (frueher galt eine feste Vorgabe, die nicht gespeichert wurde), aber ein
    Geraete-Schluessel liegt im Schluesselbund -> dessen Server uebernehmen (der zuletzt geaenderte gewinnt)."""
    global _searched
    if _searched or not Secret:
        return ""
    _searched = True
    best = None
    for schema in (_SCHEMA,):
        try:
            items = Secret.password_search_sync(schema, {}, Secret.SearchFlags.ALL, None) or []
        except (GLib.Error, AttributeError, TypeError):
            continue
        for it in items:
            srv = ((it.get_attributes() or {}).get("server") or "").rstrip("/")
            if srv.startswith(("https://", "http://")) and (best is None or it.get_modified() > best[0]):
                best = (it.get_modified(), srv)
    return best[1] if best else ""


def _lookup(schema, server):
    v = Secret.password_lookup_sync(schema, {"server": server}, None)
    return v if v and v.startswith("hkd_") else None


_fb_lock = threading.Lock()


def _fallback_read():
    """Ersatzdatei ohne Schluesselbund: {"<origin>": "hkd_..."} (frueher: nur ein Schluessel ohne Server)"""
    try:
        with open(_FALLBACK, encoding="utf-8") as f:
            raw = f.read().strip()
    except OSError:
        return {}
    if raw.startswith("hkd_"):                            # altes Format: gehoerte zum eingetragenen Server
        srv = origin(settings.get("server") or "")
        return {srv: raw} if srv else {}
    try:
        d = json.loads(raw)
    except ValueError:
        return {}
    return {k: v for k, v in d.items() if isinstance(v, str) and v.startswith("hkd_")} if isinstance(d, dict) else {}


def _fallback_write(d):
    if not d:
        try:
            os.remove(_FALLBACK)
        except OSError:
            pass
        return
    atomic_write(_FALLBACK, json.dumps(d))


def get_device_token(server=None):
    """Geraete-Schluessel fuer diesen Server (Standard: eingetragener Server) - gehoert immer genau zu einem Server"""
    srv = (server or settings.server).rstrip("/")
    if not srv:
        return None
    if Secret:
        try:
            v = _lookup(_SCHEMA, srv)
            if v:
                return v
            # Uebernahme: unter einer frueheren Server-Adresse gespeichert
            # -> unter der aktuellen Adresse speichern, alten Eintrag loeschen
            olds = [o for o, n in MOVED_SERVERS.items() if n == srv]
            for schema, s in [(_SCHEMA, o) for o in olds]:
                v = _lookup(schema, s)
                if v:
                    set_device_token(v, srv)
                    Secret.password_clear_sync(schema, {"server": s}, None)
                    return v
        except GLib.Error:
            pass
    with _fb_lock:
        return _fallback_read().get(origin(srv))


def set_device_token(v, server=None):
    srv = (server or settings.server).rstrip("/")
    if not srv:
        return
    if Secret:
        try:
            Secret.password_clear_sync(_SCHEMA, _attrs(srv), None)
        except GLib.Error:
            pass
    with _fb_lock:
        d = _fallback_read()
        d.pop(origin(srv), None)
        _fallback_write(d)
    if not v:
        return
    if Secret:
        try:
            if Secret.password_store_sync(_SCHEMA, _attrs(srv), Secret.COLLECTION_DEFAULT, _("Saga – sign-in of this device"), v, None):
                return
        except GLib.Error:
            pass
    with _fb_lock:
        d = _fallback_read()
        d[origin(srv)] = v
        _fallback_write(d)


def keyring_available():
    return Secret is not None
