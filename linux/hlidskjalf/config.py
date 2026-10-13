"""Einstellungen (~/.config/hlidskjalf), Zwischenspeicher (~/.cache/hlidskjalf), Geraete-Schluessel (Schluesselbund)."""
import json
import os
import socket

from gi.repository import GLib

from . import APP_ID

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


class Settings:
    def __init__(self):
        try:
            with open(_SETTINGS, encoding="utf-8") as f:
                self.d = json.load(f)
        except (OSError, ValueError):
            self.d = {}

    def get(self, k, default=None):
        return self.d.get(k, default)

    def set(self, k, v):
        if v is None:
            self.d.pop(k, None)
        else:
            self.d[k] = v
        tmp = _SETTINGS + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.d, f, ensure_ascii=False, indent=1)
        os.replace(tmp, _SETTINGS)

    @property
    def server(self):
        s = (self.d.get("server") or DEFAULT_SERVER).rstrip("/")
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


def device_name():
    h = GLib.get_host_name() or socket.gethostname() or "Linux"
    return h.split(".")[0] + " (Linux)"


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


def _attrs():
    return {"server": settings.server}


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


def get_device_token():
    srv = settings.server
    if Secret and srv:
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
                    set_device_token(v)
                    Secret.password_clear_sync(schema, {"server": s}, None)
                    return v
        except GLib.Error:
            pass
    try:
        with open(_FALLBACK, encoding="utf-8") as f:
            v = f.read().strip()
            return v if v.startswith("hkd_") else None
    except OSError:
        return None


def set_device_token(v):
    if Secret:
        for schema in (_SCHEMA,):
            try:
                Secret.password_clear_sync(schema, _attrs(), None)
            except GLib.Error:
                pass
    try:
        os.remove(_FALLBACK)
    except OSError:
        pass
    if not v:
        return
    if Secret:
        try:
            if Secret.password_store_sync(_SCHEMA, _attrs(), Secret.COLLECTION_DEFAULT, "Saga – Anmeldung dieses Geräts", v, None):
                return
        except GLib.Error:
            pass
    fd = os.open(_FALLBACK, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(v)
