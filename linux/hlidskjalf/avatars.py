"""Profilbilder (Server ab 2.1). Personen-Objekte tragen ein Feld "avatar" (relative Adresse /api/avatar/<schluessel>?v=<n>
oder null). Bilder kommen nur von Saga (nie direkt von Gravatar oder dem Anmeldedienst), mit der Sitzung der App.
Anzeige rund per Adw.Avatar (custom-image); die Initialen bleiben Platzhalter und Rueckfall (kein Bild, 404, offline).

Zwischenspeicher: im Speicher (Textur je Server, Adresse, Stufe) und auf der Platte (CACHE_DIR/avatars, einen Tag - v
aendert sich mit jedem neuen Bild). 404 = kein Bild: nicht wiederholen, bis sich die Adresse aendert. Ohne WebP-Lader
(webp-pixbuf-loader) fragt die App kein WebP an, der Server liefert dann PNG/JPEG."""
import hashlib
import os
import time

from gi.repository import Adw, GLib

from . import config
from .api import APIError, client
from .util import bg

TIERS = (64, 128, 256)          # Stufen des Servers (s=)
DISK_TTL = 86400                # wie Cache-Control max-age der versionierten Adresse
DISK_MAX_AGE = 30 * 86400       # aeltere Dateien raeumt prune() weg
_DIR = os.path.join(config.CACHE_DIR, "avatars")

_mem = {}                       # (server, adresse, stufe) -> Gdk.Texture
_missing = set()                # (server, adresse, stufe): 404 - kein Bild
_waiting = {}                   # (server, adresse, stufe) -> [rueckruf(textur|None)]
_webp = None


def tier(px):
    """kleinste Stufe >= px (Anzeigegroesse x Skalierung)"""
    return next((t for t in TIERS if t >= px), TIERS[-1])


def valid(url):
    """nur relative Adressen des eigenen Servers (wie geliefert, nie selbst zusammengebaut)"""
    ok = isinstance(url, str) and url.startswith(("/api/avatar/", "/api/ai/avatar?")) and len(url) < 400 and "\n" not in url
    return url if ok else None


def _has_webp():
    global _webp
    if _webp is None:
        try:
            from gi.repository import GdkPixbuf
            _webp = any(f.get_name() == "webp" for f in GdkPixbuf.Pixbuf.get_formats())
        except (ImportError, ValueError, AttributeError):
            _webp = False
    return _webp


def _path(url, t):
    return f"{url}{'&' if '?' in url else '?'}s={t}"


def _disk(srv, url, t, etag=None):
    h = hashlib.sha256(f"{srv}|{url}|{t}".encode() + (f"|{etag}".encode() if etag else b"")).hexdigest()[:40]
    return os.path.join(_DIR, h)


def fetch(srv, url, t, etag=None):
    """Hintergrund: Bild-Bytes (Platte, sonst Server). APIError(404) = kein Bild. Offline: abgelaufene Datei geht auch.
    etag (Profilbild der KI, avatar_etag aus der Persona): Kennung des Bildinhalts - die Datei dazu gilt, solange sie gleich
    bleibt (kein Ablauf nach einem Tag)."""
    f = _disk(srv, url, t, etag)
    old = None
    try:
        st = os.stat(f)
        with open(f, "rb") as fh:
            old = fh.read()
        if old and (etag or time.time() - st.st_mtime < DISK_TTL):
            return old
    except OSError:
        pass
    if config.origin(config.settings.server) != srv:
        raise APIError(-1)
    hd = {"Accept": "image/webp,image/png,image/jpeg;q=0.9"} if _has_webp() else {"Accept": "image/png,image/jpeg"}
    try:
        data = client.request("GET", _path(url, t), timeout=20, headers=hd)
    except APIError as e:
        if e.code == 0 and old:
            return old
        raise
    if not data:
        raise APIError(404)
    try:
        os.makedirs(_DIR, mode=0o700, exist_ok=True)
        tmp = f + ".tmp"
        with open(tmp, "wb") as fh:
            fh.write(data)
        os.replace(tmp, f)
    except OSError:
        pass
    return data


def _texture(data):
    from gi.repository import Gdk
    try:
        return Gdk.Texture.new_from_bytes(GLib.Bytes.new(data))
    except GLib.Error:
        return None


def _want(key, cb):
    lst = _waiting.get(key)
    if lst is not None:
        lst.append(cb)
        return
    _waiting[key] = [cb]
    srv, url, t = key[:3]
    etag = key[3] if len(key) > 3 else None

    def done(data):
        tex = _texture(data) if data else None
        if tex is not None:
            _mem[key] = tex
        else:
            _missing.add(key)
        for c in _waiting.pop(key, []):
            c(tex)

    def err(e):
        if isinstance(e, APIError) and e.code in (403, 404, 410):
            _missing.add(key)                    # kein Bild (bzw. nicht sichtbar): nicht wiederholen
        for c in _waiting.pop(key, []):
            c(None)
    bg(fetch, srv, url, t, etag, done=done, error=err, media=True)


def attach(av, url, size=None, etag=None):
    """Bild zur Adresse in ein Adw.Avatar legen (Initialen bzw. Symbol bleiben bis dahin bzw. bei Fehlern).
    etag: Kennung des Bildinhalts (Persona der KI) - Teil des Zwischenspeicher-Schluessels"""
    url = valid(url)
    av._hk_avatar = url
    srv = config.origin(config.settings.server)
    if not url or not srv:
        av.set_custom_image(None)
        return
    scale = max(2, av.get_scale_factor() or 1)                # schon vor dem Zeichnen scharf fuer HiDPI
    key = (srv, url, tier((size or av.get_size()) * scale)) + ((str(etag)[:80],) if etag else ())
    tex = _mem.get(key)
    if tex is not None:
        av.set_custom_image(tex)
        return
    av.set_custom_image(None)
    if key in _missing:
        return
    # starke Referenz bis zur Antwort (kurz): ein schwacher Verweis auf die Python-Huelle reicht nicht - PyGObject legt
    # sie neu an, sobald niemand mehr in Python auf das Widget zeigt (und damit auch das Merkmal _hk_avatar)
    def apply(tx):
        if tx is not None and getattr(av, "_hk_avatar", None) == url:
            av.set_custom_image(tx)
    _want(key, apply)


def person(p, size=32, text=None, **kw):
    """Adw.Avatar fuer ein Personen-Objekt {name, username, avatar}: Initialen + Bild (falls vorhanden)"""
    p = p if isinstance(p, dict) else {}
    name = text or p.get("name") or p.get("username") or "?"
    av = Adw.Avatar(size=size, text=name, show_initials=True, **kw)
    attach(av, p.get("avatar"), size)
    return av


def forget(url=None):
    """Nach eigenem Hochladen/Entfernen: Speicher (und die Platte zur Adresse) vergessen"""
    for k in [k for k in _mem if url is None or k[1] == url]:
        _mem.pop(k, None)
    for k in [k for k in _missing if url is None or k[1] == url]:
        _missing.discard(k)


def reset():
    """anderer Server / abgemeldet"""
    _mem.clear()
    _missing.clear()


def prune(max_age=DISK_MAX_AGE):
    """Hintergrund: alte Dateien loeschen"""
    try:
        now = time.time()
        for n in os.listdir(_DIR):
            p = os.path.join(_DIR, n)
            try:
                if now - os.stat(p).st_mtime > max_age:
                    os.remove(p)
            except OSError:
                pass
    except OSError:
        pass
