"""Vorlagengalerie (Saga 2.5, SPEC-galerie §5): Logik ohne GTK - Liste lesen, gruppieren, Fehler abbilden, Anfrage bauen.

Der Dialog selbst steht in galeriedialog.py. Server: GET api/gallery?lang=, POST api/gallery/{id}/use."""
import re
import urllib.parse

from .i18n import _

ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
CATEGORIES = ("personal", "work", "team")
MAX_ITEMS = 200                              # mehr bietet kein Server an - Schutz vor riesigen Antworten


def valid_id(v):
    """Nur IDs aus [A-Za-z0-9_-] kommen in einen Pfad"""
    return isinstance(v, str) and bool(ID_RE.match(v))


def _text(v, limit=400):
    return v.strip()[:limit] if isinstance(v, str) else ""


def _num(v, default=0):
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return default
    return v


def parse_contents(v):
    out = []
    for c in v if isinstance(v, list) else []:
        if not isinstance(c, dict):
            continue
        title = _text(c.get("title"), 200)
        if not title:
            continue
        kind = c.get("kind") if c.get("kind") in ("page", "database") else "page"
        out.append({"kind": kind, "title": title, "icon": _text(c.get("icon"), 16)})
    return out


def parse_item(it):
    """ein Eintrag aus GET api/gallery -> dict oder None (ungueltig: ueberspringen). Zusaetzliche Felder stoeren nicht."""
    if not isinstance(it, dict) or not valid_id(it.get("id")):
        return None
    title = _text(it.get("title"), 200)
    if not title:
        return None
    cat = it.get("category")
    return {"id": it["id"], "title": title, "desc": _text(it.get("desc")), "icon": _text(it.get("icon"), 16),
            "category": cat if cat in CATEGORIES else "other", "order": _num(it.get("order")),
            "needs": [n for n in it.get("needs") or [] if isinstance(n, str)] if isinstance(it.get("needs"), list) else [],
            "contents": parse_contents(it.get("contents"))}


def parse_list(j):
    """Antwort von GET api/gallery -> [eintrag]; doppelte IDs nur einmal"""
    raw = j.get("items") if isinstance(j, dict) else None
    out, seen = [], set()
    for it in raw if isinstance(raw, list) else []:
        p = parse_item(it)
        if p is not None and p["id"] not in seen:
            seen.add(p["id"])
            out.append(p)
        if len(out) >= MAX_ITEMS:
            break
    return out


def category_label(cat):
    return {"personal": _("Personal"), "work": _("Work"), "team": _("Team")}.get(cat, _("Other templates"))


def group(items):
    """-> [(kategorie, ueberschrift, [eintraege nach order, dann Titel])] in der Reihenfolge persoenlich, Arbeit, Team, weitere"""
    out = []
    for cat in CATEGORIES + ("other",):
        its = sorted((i for i in items if i["category"] == cat), key=lambda i: (i["order"], i["title"].casefold()))
        if its:
            out.append((cat, category_label(cat), its))
    return out


def contents_text(contents):
    """"Enthält: 📁 Projekte, ✅ Aufgaben" - leer, wenn der Server nichts nennt"""
    if not contents:
        return ""
    names = [(c["icon"] + " " if c["icon"] else "") + c["title"] for c in contents]
    return _("Contains: {items}").format(items=", ".join(names))


def needs_text(needs):
    """Faehigkeiten (needs) lesbar: bekannte uebersetzt, unbekannte wie geliefert"""
    known = {"calendar": _("Calendar")}
    return [known.get(n, n) for n in needs]


def list_path(lang):
    lang = lang if isinstance(lang, str) and re.match(r"^[a-z]{2,3}$", lang) else "en"
    return "api/gallery?lang=" + urllib.parse.quote(lang)


def use_path(gid):
    if not valid_id(gid):
        raise ValueError("ungueltige Vorlagen-ID")
    return f"api/gallery/{gid}/use"


def parent_ok(pid, own):
    """Als Elternseite anbieten? Nur eigene Seiten mit positiver ID (keine foederierten, keine Datenbanken).
    own: dict ID -> Seiten-Eintrag (store.by_id)"""
    if isinstance(pid, bool) or not isinstance(pid, int) or pid <= 0:
        return False
    p = own.get(pid)
    return isinstance(p, dict) and p.get("kind") != "database" and not p.get("archived")


def use_body(parent, examples, own, place=None):
    """place: (2.5) Ort fuer Wetter-Widgets ohne Ort ({"place", "lat", "lon"}, siehe place_body) - None = ohne"""
    b = {"parent_id": parent if parent_ok(parent, own) else None, "examples": bool(examples)}
    if place:
        b["weather_place"] = place
    return b


# --- (2.5) Wetter-Widget ohne Ort: vor dem Anlegen nach dem Ort fragen (APP-API "weather_place", GALERIE.md) ---------------
def preview_path(gid, lang):
    """GET api/gallery/{id}?lang= - Vorschau; liefert ask_weather"""
    if not valid_id(gid):
        raise ValueError("ungueltige Vorlagen-ID")
    return f"api/gallery/{gid}" + list_path(lang)[len("api/gallery"):]


def ask_weather(j):
    """Vorschau-Antwort: True nur, wenn der Server ausdruecklich nach dem Ort fragen laesst (aeltere Server: nie)"""
    return isinstance(j, dict) and j.get("ask_weather") is True


def geocode_path(q):
    """Ortssuche GET api/widgets/geocode?q= (2-80 Zeichen) - None, wenn die Eingabe zu kurz ist"""
    q = q.strip() if isinstance(q, str) else ""
    if len(q) < 2:
        return None
    return "api/widgets/geocode?q=" + urllib.parse.quote(q[:80])


def _coord(v, lim):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and -lim <= v <= lim


def parse_places(j):
    """Antwort der Ortssuche -> [{"name", "label", "lat", "lon"}] (hoechstens 8; ungueltige Eintraege fehlen)"""
    raw = j.get("results") if isinstance(j, dict) else None
    out = []
    for r in raw if isinstance(raw, list) else []:
        if not isinstance(r, dict) or not _coord(r.get("lat"), 90) or not _coord(r.get("lon"), 180):
            continue
        name = _text(r.get("name"), 120)
        if not name:
            continue
        admin, country = _text(r.get("admin"), 120), _text(r.get("country"), 120)
        lbl = name + (", " + admin if admin and admin != name else "") + (" (" + country + ")" if country else "")
        out.append({"name": name, "label": lbl, "lat": r["lat"], "lon": r["lon"]})
        if len(out) >= 8:
            break
    return out


def place_body(p):
    """gewaehlter Ort -> weather_place fuer POST .../use"""
    return {"place": p["name"], "lat": p["lat"], "lon": p["lon"]}


def geocode_error(code):
    """Meldung, wenn die Ortssuche scheitert (wie im Web)"""
    if code == 429:
        return _("Too many requests – please wait a moment.")
    return _("Place search unavailable")


def parse_use(j):
    """Antwort von POST .../use -> (root, [ids]) oder None"""
    if not isinstance(j, dict):
        return None
    root = j.get("root")
    if isinstance(root, bool) or not isinstance(root, int) or root <= 0:
        return None
    created = [i for i in j.get("created") or [] if isinstance(i, int) and not isinstance(i, bool)] \
        if isinstance(j.get("created"), list) else []
    return root, created


def hidden_by(code):
    """Liste: 404 (aelterer Server) bzw. 403 (vom Admin abgeschaltet) -> Eintraege ausblenden"""
    return code in (403, 404)


def error_text(code, msg="", stage="use"):
    """freundliche Meldung fuer eine fehlgeschlagene Anfrage (code 0 = nicht erreichbar).
    stage "list" oder "use". Klare Server-Meldungen ({"error": "..."}) bei 400/403 werden gezeigt."""
    msg = msg.strip() if isinstance(msg, str) else ""
    if code == 0:
        return msg or _("Server not reachable")
    if code == 429:
        return _("Too many templates in a short time – please try again in a minute.")
    if code >= 500:
        return _("The server could not create the template. Nothing was created – please try again later.") if stage == "use" \
            else _("The template gallery could not be loaded. Please try again later.")
    if stage == "list" and hidden_by(code):
        return _("The template gallery is not available on this server.")
    if code == 404:
        return _("This template is no longer available.")
    if msg and code in (400, 403, 409, 413, 422):
        return msg
    if code == 403:
        return _("You are not allowed to create pages there.")
    return _("The template could not be created.") if stage == "use" else _("The template gallery could not be loaded.")


class State:
    """Ist die Galerie auf dem aktuellen Server verfuegbar? None = noch nicht gefragt, True/False = Antwort.
    Je Server gemerkt (Wechsel: wieder unbekannt)."""

    def __init__(self):
        self.server = None
        self.ok = None

    def get(self, server):
        return self.ok if server == self.server else None

    def set(self, server, ok):
        self.server, self.ok = server, ok

    def offered(self, server, server_version=None):
        """Eintrag 'Aus Vorlage ...' zeigen? Nicht, wenn die Liste 404/403 lieferte oder der Server aelter als 2.5 ist"""
        ok = self.get(server)
        if ok is not None:
            return ok
        if server_version:
            from . import version_tuple
            return version_tuple(server_version) >= (2, 5)
        return False


state = State()
