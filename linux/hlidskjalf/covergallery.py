"""Titelbild-Galerie (Saga 2.5, Server GET api/covers, docs/APP-API.md "Titelbild-Galerie"): Logik ohne GTK.

Liste lesen und pruefen, nach Gruppen ordnen, Auswahl setzen (cover + empfohlene cover_pos), Zufall wie im Web (nicht das
jetzige), "Ueber dieses Bild", Menue-Eintraege. Wie Android data/CoverGallery.kt und iOS CoverGalleryLogic.swift.
Der Dialog steht in coverdialog.py. Aeltere Server (404) bzw. abgeschaltet (403): Galerie nicht anbieten."""
import math
import random as _random
import re
import threading

LIST_PATH = "api/covers"
THUMB_W = 384                                 # Vorschaubreite, wenn der Server keine "thumb"-Adresse schickt (wie das Web)
MAX_ITEMS = 1000                              # Schutz vor riesigen Antworten
PATH_RE = re.compile(r"^/covers/([a-z0-9][a-z0-9-]{0,63})\.jpg$")      # wie der Server (saga/covers.py PATH_RE)
ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
WEB_RE = re.compile(r"^https?://[^\s<>\"']+$", re.IGNORECASE)


def id_of(url):
    """ID eines Galerie-Pfads (/covers/<id>.jpg) oder None"""
    m = PATH_RE.match(url) if isinstance(url, str) else None
    return m.group(1) if m else None


def is_gallery_path(url):
    return id_of(url) is not None


def valid_id(v):
    return isinstance(v, str) and bool(ID_RE.match(v))


def thumb(raw, url):
    """Vorschauadresse des Servers, wenn sie zum Bild gehoert (gleicher Pfad mit Abfrage), sonst ?w=384"""
    if isinstance(raw, str) and (raw == url or raw.startswith(url + "?")):
        return raw
    return f"{url}?w={THUMB_W}"


def offered_code(code):
    """True = Galerie da (2xx), False = aelterer Server (404) bzw. abgeschaltet (403), None = spaeter wieder fragen"""
    if isinstance(code, int) and 200 <= code < 300:
        return True
    if code in (403, 404):
        return False
    return None


def _text(v, limit=300):
    return v.strip()[:limit] if isinstance(v, str) else ""


def _pos(v):
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return 50.0
    v = float(v)
    return min(100.0, max(0.0, v)) if math.isfinite(v) else 50.0


def _web(v):
    s = _text(v, 2000)
    return s if WEB_RE.match(s) else ""


def parse(j):
    """GET api/covers -> {"covers": [...], "groups": [(id, name)], "source", "license"} oder None (keine lesbare Antwort).
    Eintraege ohne gueltigen Pfad fallen weg, doppelte zaehlen einmal; zusaetzliche Felder werden ignoriert."""
    if not isinstance(j, dict) or not isinstance(j.get("covers"), list):
        return None
    seen, out = set(), []
    for c in j["covers"][:MAX_ITEMS]:
        if not isinstance(c, dict):
            continue
        url = _text(c.get("url"), 200)
        cid = id_of(url)
        if cid is None or cid in seen:
            continue
        seen.add(cid)
        out.append({
            "id": cid, "url": url, "thumb": thumb(_text(c.get("thumb"), 300) or None, url),
            "title": _text(c.get("title")) or cid, "artist": _text(c.get("artist")), "date": _text(c.get("date"), 60),
            "source_url": _web(c.get("source_url")), "source": _text(c.get("source")), "group": _text(c.get("group"), 64),
            "pos": _pos(c.get("pos")), "builtin": c.get("builtin") is True,
        })
    groups = []
    for g in j.get("groups") if isinstance(j.get("groups"), list) else []:
        if not isinstance(g, dict):
            continue
        gid = _text(g.get("id"), 64)
        if all(x[0] != gid for x in groups):
            groups.append((gid, _text(g.get("label"), 120)))
    return {"covers": out, "groups": groups, "source": _text(j.get("source")), "license": _text(j.get("license"))}


def credit(c):
    """"Kuenstler, Datum" wie im Web"""
    return ", ".join(x for x in (c.get("artist"), c.get("date")) if x)


def label(c):
    """Zugaenglicher Name und Tooltip wie im Web: "Titel – Kuenstler, Datum" """
    cr = credit(c)
    return f"{c['title']} – {cr}" if cr else c["title"]


def choosable(listing, remote):
    """Fremde Instanz (remote): nur mitgelieferte Bilder (gibt es auf jedem Saga-Server gleich, Uploads des Admins nicht)"""
    covers = (listing or {}).get("covers") or []
    return [c for c in covers if c["builtin"]] if remote else list(covers)


def grouped(listing, remote, other):
    """[(id, Name, [Bilder])] in der Reihenfolge des Servers; Gruppen, die der Server nicht nennt, dahinter (Name = ID),
    Bilder ohne Gruppe zuletzt unter other ("Weitere"). Leere Gruppen fallen weg."""
    items = choosable(listing, remote)
    order = list((listing or {}).get("groups") or [])
    for c in items:
        if all(g[0] != c["group"] for g in order):
            order.append((c["group"], c["group"]))
    empty = [g for g in order if g[0] == ""]
    order = [g for g in order if g[0] != ""] + empty[:1]
    out = []
    for gid, name in order:
        its = [c for c in items if c["group"] == gid]
        if its:
            out.append((gid, name or gid or other, its))
    return out


def fields(c, current):
    """Felder fuer PATCH api/notes/{id}: Pfad und empfohlene Position; None = schon das Titelbild (nichts speichern)"""
    if not c or c["url"] == current:
        return None
    return {"cover": c["url"], "cover_pos": c["pos"]}


def pick_random(listing, remote, current, roll=None):
    """"Zufaellig" wie im Web: ein anderes als das jetzige. roll(n) -> Index 0..n-1 (Tests: fest)"""
    items = [c for c in choosable(listing, remote) if c["url"] != current]
    if not items:
        return None
    i = (roll or _random.randrange)(len(items))
    return items[min(len(items) - 1, max(0, int(i)))]


def about(listing, current):
    """"Ueber dieses Bild": ("image", Eintrag) | ("gone", None) = Galerie-Pfad, aber nicht (mehr) zur Auswahl |
    ("none", None) = kein Galerie-Bild (Menuepunkt nicht anbieten)"""
    if not is_gallery_path(current):
        return ("none", None)
    for c in (listing or {}).get("covers") or []:
        if c["url"] == current:
            return ("image", c)
    return ("gone", None)


def menu_keys(current, offered, locked=False, remote=False, immich=True, listing=None):
    """Eintraege des Titelbild-Menues (Reihenfolge wie Web/Apps). current: jetziges Titelbild oder None ("Titelbild
    hinzufuegen"). Gesperrte Seiten: keine Galerie, kein Zufall (Server lehnt mit 423 ab). "Zufaellig" nur, wenn es ein
    anderes Bild gibt. Neu positionieren/Hoehe/Entfernen sind auf dem Schreibtisch eigene Knoepfe neben dem Menue."""
    keys = []
    if offered and not locked:
        keys.append("gallery")
        if listing is None or pick_random(listing, remote, current, roll=lambda n: 0) is not None:
            keys.append("random")
    keys.append("file")
    if immich and not remote:                                     # Immich gehoert dem eigenen Konto
        keys.append("immich")
    if current and offered and about(listing, current)[0] != "none":
        keys.append("about")
    return keys


class CoverState:
    """Je Server: Galerie angeboten? (404/403 -> Eintraege ausblenden) und zuletzt geladene Liste (je Sprache)"""

    def __init__(self):
        self.lock = threading.Lock()
        self.lists = {}                      # (server, sprache) -> Liste
        self.unsupported = set()             # Server ohne Galerie
        self.asking = set()                  # (server, sprache): Anfrage laeuft

    def clear(self):
        with self.lock:
            self.lists.clear()
            self.unsupported.clear()
            self.asking.clear()

    def cached(self, server, lang):
        with self.lock:
            return self.lists.get((server, lang))

    def offered(self, server, lang):
        """True (Liste da), False (Server ohne Galerie), None (unbekannt - noch fragen)"""
        with self.lock:
            if not server or server in self.unsupported:
                return False if server else None
            return True if (server, lang) in self.lists else None

    def record(self, server, lang, code, listing=None):
        """Antwort uebernehmen (code wie offered_code); unlesbare 2xx-Antwort aendert nichts"""
        on = offered_code(code)
        with self.lock:
            self.asking.discard((server, lang))
            if on is True and listing is not None:
                self.lists[(server, lang)] = listing
                self.unsupported.discard(server)
            elif on is False:
                for k in [k for k in self.lists if k[0] == server]:
                    del self.lists[k]
                self.unsupported.add(server)

    def begin(self, server, lang):
        """True = jetzt fragen (noch keine Anfrage unterwegs, Stand unbekannt)"""
        with self.lock:
            if not server or server in self.unsupported or (server, lang) in self.lists or (server, lang) in self.asking:
                return False
            self.asking.add((server, lang))
            return True

    def failed(self, server, lang):
        with self.lock:
            self.asking.discard((server, lang))


state = CoverState()
