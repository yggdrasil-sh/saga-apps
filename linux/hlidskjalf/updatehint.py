"""Update-Hinweis (Einstellungen -> Info) - reine Logik ohne GTK, wie ios/App/UpdateHintLogic.swift.

Quelle: https://raw.githubusercontent.com/<repo>/appcast/<kanal>.json (CI, Zweig appcast, tools/update-info.py). Verglichen
wird nur im eigenen Kanal: Vorabversion x.y.z-rc.N -> testing.json, sonst stable.json. Entwickler-Labels (kein
x.y.z(-rc.N)?) -> keine Pruefung. Tests: tests/test_updatehint.py
"""
import json
import re
import urllib.request

REPO = "yggdrasil-sh/saga-apps"
START_INTERVAL = 12 * 3600          # automatische Pruefung beim App-Start hoechstens so oft
INFO_INTERVAL = 15 * 60             # beim Oeffnen von Info nicht oefter als so
MAX_BYTES = 64 * 1024               # groesste angenommene Antwort (die Datei hat wenige hundert Byte)
TIMEOUT = 10                        # Sekunden

_VER = re.compile(r"^([0-9]{1,9})\.([0-9]{1,9})\.([0-9]{1,9})(?:-rc\.([0-9]{1,9}))?$")


# --- Versionen ---------------------------------------------------------------------------------------------------------
def parse_version(s):
    """'2.5.0' -> (2, 5, 0, None), '2.5.0-rc.3' -> (2, 5, 0, 3); sonst None"""
    m = _VER.match(str(s or "").strip()) if isinstance(s, str) else None
    if not m:
        return None
    return int(m.group(1)), int(m.group(2)), int(m.group(3)), (int(m.group(4)) if m.group(4) is not None else None)


def _key(v):
    """Sortierschluessel: x.y.z numerisch; gleiche x.y.z: Release > jede -rc.N; -rc.N numerisch"""
    return v[0], v[1], v[2], (1, 0) if v[3] is None else (0, v[3])


def channel(label):
    """Kanal der eigenen Version ('testing' / 'stable'); None: Entwickler-Label -> keine Pruefung"""
    v = parse_version(label)
    if v is None:
        return None
    return "testing" if v[3] is not None else "stable"


def is_newer(remote, local):
    """remote neuer als local (beide gueltig, sonst False)"""
    r, l = parse_version(remote), parse_version(local)
    if r is None or l is None:
        return False
    return _key(l) < _key(r)


def feed_url(label, repo=REPO):
    ch = channel(label)
    if ch is None or not re.match(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", repo or "") or ".." in repo:
        return None
    return f"https://raw.githubusercontent.com/{repo}/appcast/{ch}.json"


# --- Datei (format 1) --------------------------------------------------------------------------------------------------
def safe_link(v):
    """Nur https-Links auf github.com (keine Benutzerangaben, kein anderer Port); sonst None"""
    if not isinstance(v, str) or len(v) >= 2048 or any(c.isspace() for c in v):
        return None
    from urllib.parse import urlsplit
    try:
        u = urlsplit(v)
        port = u.port
    except ValueError:
        return None
    if u.scheme.lower() != "https" or (u.hostname or "").lower() != "github.com" or u.username is not None \
            or u.password is not None or port is not None or "@" in u.netloc:
        return None
    return v


def parse(data, expected):
    """Antwort lesen: format 1, passender Kanal, gueltige Version im selben Kanal; unsichere Links fallen weg.
    -> {"version", "page", "assets"} oder None"""
    if isinstance(data, (bytes, bytearray)):
        if len(data) > MAX_BYTES:
            return None
        try:
            data = data.decode("utf-8")
        except UnicodeDecodeError:
            return None
    try:
        j = json.loads(data)
    except (TypeError, ValueError):
        return None
    if not isinstance(j, dict) or j.get("format") != 1 or isinstance(j.get("format"), bool):
        return None
    v = j.get("version")
    if not isinstance(v, str) or channel(v.strip()) != expected:
        return None
    if "channel" in j and j.get("channel") != expected:
        return None
    assets = {}
    raw = j.get("assets") if isinstance(j.get("assets"), dict) else {}
    for k, x in raw.items():
        u = safe_link(x)
        if isinstance(k, str) and u:
            assets[k] = u
    return {"version": v.strip(), "page": safe_link(j.get("page")), "assets": assets}


# --- Schaltflaeche und Statuszeile ---------------------------------------------------------------------------------------
def action(release):
    """Was "Update" unter Linux oeffnet: die Release-Seite (Flatpak und RPM unterscheiden sich); None: nur Hinweis"""
    return (release or {}).get("page") or None


def button(checking, available):
    """Eine Schaltflaeche: 'check' ("Nach Updates suchen") / 'checking' ("Pruefe …") / 'update' (nur mit Aktion)"""
    if checking:
        return "checking"
    if available and action(available):
        return "update"
    return "check"


def status(available, failed, checked):
    """Statuszeile: ('available', version) / ('failed', None) / ('uptodate', None) / ('none', None).
    "fehlgeschlagen" nur nach manueller Pruefung (nur die setzt failed)."""
    if available:
        return "available", available["version"]
    if failed:
        return "failed", None
    return ("none", None) if checked is None else ("uptodate", None)


def available(release, label):
    """Bekanntes Update aus dem Zwischenspeicher: nur neuer und nur im eigenen Kanal"""
    ch = channel(label)
    if not isinstance(release, dict) or ch is None or channel(release.get("version")) != ch:
        return None
    return release if is_newer(release.get("version"), label) else None


def due(last, now, interval):
    """Faellig: nie geprueft, Uhr zurueckgestellt oder aelter als das Intervall"""
    if last is None:
        return True
    age = now - last
    return age < 0 or age >= interval


# --- Zwischenspeicher (settings.json, Schluessel "update_hint") ---------------------------------------------------------
def encode(checked, release):
    return {"checked": float(checked), "release": dict(release) if release else None}


def decode(d):
    """-> (checked, release) oder (None, None); kaputte Eintraege zaehlen als nie geprueft"""
    if not isinstance(d, dict):
        return None, None
    c = d.get("checked")
    if isinstance(c, bool) or not isinstance(c, (int, float)):
        return None, None
    r = d.get("release")
    if r is not None:
        ch = channel(r.get("version")) if isinstance(r, dict) else None
        r = parse(json.dumps({"format": 1, "version": r.get("version"), "page": r.get("page"),
                              "assets": r.get("assets")}), ch) if ch else None
    return float(c), r


# --- Abruf (im Hintergrund-Thread aufrufen) ------------------------------------------------------------------------------
class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None                                   # keine Weiterleitung: dann ist die Antwort kein 200 -> Fehler


def fetch(url, expected, opener=None):
    """Datei holen: nur die Datei - keine Cookies, keine Kennungen, kein Konto, 10 s Zeitlimit. -> Release (parse) oder
    Ausnahme. Eigener Opener ohne Cookie-Verarbeitung (der Saga-Client mit Sitzung wird hier nie benutzt)."""
    if not url or not url.startswith("https://raw.githubusercontent.com/"):
        raise ValueError("unexpected update URL")
    op = opener or urllib.request.build_opener(_NoRedirect)
    req = urllib.request.Request(url, headers={"Accept": "application/json", "Cache-Control": "no-cache"})
    with op.open(req, timeout=TIMEOUT) as res:
        if getattr(res, "status", 200) != 200:
            raise ValueError(f"HTTP {res.status}")
        data = res.read(MAX_BYTES + 1)
    r = parse(data, expected)
    if r is None:
        raise ValueError("unexpected update info")
    return r
