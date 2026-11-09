"""Widgets in Seiten (Saga 2.5, SPEC-widgets Abschnitt 4): Block "widget" nur anzeigen - reine Logik ohne GTK
(tests/test_seiten_widgets.py). Name "pagewidgets", weil "Widgets" in Saga schon die Home-Bildschirm-Widgets sind.

Block: {"id", "type": "widget", "data": {"v": 1, "kind", "size": s|m|l, "style": card|plain, "accent", "title", "cfg"}}
- clock und countdown rechnet die App selbst, progress (src db), metric und quote liefert der Server:
  GET api/notes/{nid}/widgets?tz=<Zone> -> {"widgets": {"<block_id>": W}} bzw. {"widgets": {}, "unsupported": true}
  W = {"kind", "value", "max", "text", "fmt", "as_of", "error": null|no_access|gone|bad_cfg|too_big}
- Der Block wird hier nie veraendert (Regel 0); alles wird nur gelesen."""
import datetime as _dt
import html
import math
import random
import re
from zoneinfo import ZoneInfo

from .i18n import _, ngettext, ui_language

KINDS = ("clock", "countdown", "progress", "metric", "quote")
KINDS2 = {"weather": "weather1", "habit": "habit1", "upcoming": "upcoming1", "chart": "chart1",    # Etappe 2: Art -> Faehigkeit
          "subpages": "subpages1"}                # Unterseiten als Liste/Galerie (2.5)
FEATURES = None             # Faehigkeiten des Servers (Menge) - None: unbekannt (Etappe-2-Arten dann nicht anzeigen)
SIZES = ("s", "m", "l")
FMTS = ("num", "pct", "eur")
ERRORS = ("no_access", "gone", "bad_cfg", "too_big", "disabled", "unavailable")
PALETTE = ("gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red")
MAX_DEPTH = 8


def _num(v):
    """Zahl (kein bool, endlich) oder None"""
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return None
    try:
        v = float(v)
    except OverflowError:                           # riesige ganze Zahl (unbereinigte Daten)
        return None
    return v if math.isfinite(v) else None


def _str(d, k):
    v = d.get(k) if isinstance(d, dict) else None
    return v if isinstance(v, str) else ""


class Spec:
    """gelesener Widget-Block; fremde/fehlende Werte -> Standard (m, card, keine Farbe)"""
    __slots__ = ("id", "kind", "size", "style", "accent", "title", "cfg")

    def __init__(self, block):
        b = block if isinstance(block, dict) else {}
        d = b.get("data") if isinstance(b.get("data"), dict) else {}
        self.id = b.get("id") if isinstance(b.get("id"), str) else None
        self.kind = _str(d, "kind")
        self.size = d.get("size") if d.get("size") in SIZES else "m"
        self.style = "plain" if d.get("style") == "plain" else "card"
        if self.kind == "subpages" and d.get("style") != "card":       # Unterseiten: ohne Angabe rahmenlos (wie im Web)
            self.style = "plain"
        self.accent = d.get("accent") if d.get("accent") in PALETTE else None
        self.title = html.unescape(_str(d, "title")).strip()[:80]
        self.cfg = d.get("cfg") if isinstance(d.get("cfg"), dict) else {}

    @property
    def known(self):
        if self.kind in KINDS2:                      # 2.5 Paket 4: nur mit passendem Server
            return FEATURES is not None and KINDS2[self.kind] in FEATURES
        return self.kind in KINDS

    @property
    def stage2(self):
        return self.kind in KINDS2 and self.known

    @property
    def server_value(self):
        """rechnet der Server den Wert? (quote immer - fixed nur als Rueckfall lokal)"""
        if self.kind in ("metric", "quote") or (self.kind in KINDS2 and self.known):
            return True
        return self.kind == "progress" and _str(self.cfg, "src") == "db"


def collect(blocks, _depth=0):
    """alle Widget-Bloecke (auch in Spalten und Toggles), Reihenfolge wie im Dokument"""
    out = []
    if not isinstance(blocks, list) or _depth > MAX_DEPTH:
        return out
    for b in blocks:
        if not isinstance(b, dict):
            continue
        d = b.get("data") if isinstance(b.get("data"), dict) else {}
        t = b.get("type")
        if t == "widget":
            out.append(b)
        elif t == "toggle":
            out += collect(d.get("blocks"), _depth + 1)
        elif t == "columns":
            for c in d.get("cols") or []:
                if isinstance(c, dict):
                    out += collect(c.get("blocks"), _depth + 1)
    return out


def needs_server(blocks):
    """Seite enthaelt Widgets, deren Wert der Server rechnet (sonst kein Abruf noetig)"""
    return any(Spec(b).known and Spec(b).server_value for b in collect(blocks))


# --- Werte vom Server -------------------------------------------------------------------------------
def parse_value(o):
    """W -> dict mit festen Schluesseln (falsche Typen -> None)"""
    o = o if isinstance(o, dict) else {}
    e = o.get("error")
    err = None if e in (None, "") else (e if e in ERRORS else "other")
    t = o.get("text")
    out = {"kind": _str(o, "kind"), "value": _num(o.get("value")), "max": _num(o.get("max")),
            "text": t if isinstance(t, str) else None, "fmt": o.get("fmt") if o.get("fmt") in FMTS else None,
            "as_of": _str(o, "as_of") or None, "error": err}
    if _str(o, "kind") in KINDS2:                   # Etappe 2: strukturierte Daten je Art
        out["data"] = o.get("data") if isinstance(o.get("data"), dict) and err is None else None
    return out


def parse_response(obj):
    """Antwort von GET .../widgets -> ("ok", {bid: W}) | ("unsupported", {}) | ("failed", {}) bei kaputter Antwort"""
    if not isinstance(obj, dict):
        return "failed", {}
    if obj.get("unsupported") is True:
        return "unsupported", {}
    if obj.get("unsupported2") is True and isinstance(obj.get("widgets"), dict):     # Gegenstelle ohne widgets2
        w = {k: parse_value(v) for k, v in obj["widgets"].items() if isinstance(k, str) and isinstance(v, dict)}
        return "ok2", w
    w = obj.get("widgets")
    if not isinstance(w, dict):
        return "ok", {}
    return "ok", {k: parse_value(v) for k, v in w.items() if isinstance(k, str) and isinstance(v, dict)}


def unsupported_kinds(obj):
    """Arten, die der Server einer fremden Seite noch nicht kennt ("unsupported_kinds": ["subpages"]) -> Menge"""
    u = obj.get("unsupported_kinds") if isinstance(obj, dict) else None
    return frozenset(x for x in u if isinstance(x, str)) if isinstance(u, list) else frozenset()


def error_state(code):
    """HTTP-Fehler beim Abruf: 404 = aelterer Server, 0 = nicht erreichbar, sonst Fehler"""
    if code == 404:
        return "old"
    if code == 0:
        return "offline"
    return "failed"


class Values:
    """Stand der Werte einer Seite. state: none (noch nichts) | ok | old | unsupported | offline | failed.
    Ein Fehler beim Neuladen verdraengt vorhandene Werte nicht (stale=True)."""

    def __init__(self):
        self.state, self.values, self.stale, self.no2 = "none", {}, False, False
        self.nokinds = frozenset()                  # Arten, die der Ursprungsserver nicht rechnet (unsupported_kinds)

    def apply(self, state, values=None):
        if state == "ok2":                          # Etappe-2-Arten fehlen (Ursprungsserver ohne widgets2)
            self.state, self.values, self.stale, self.no2 = "ok", dict(values or {}), False, True
            return True
        if state in ("ok", "unsupported"):
            self.state, self.values, self.stale, self.no2 = state, dict(values or {}), False, False
            return True
        if self.state == "ok":                      # Fehler beim Neuladen: alte Werte bleiben
            self.stale = True
            return False
        self.state, self.values, self.stale = state, {}, False
        return True

    def get(self, bid):
        return self.values.get(bid)


# --- Zahlenformat --------------------------------------------------------------------------------------
_SEP = {"de": (",", "."), "es": (",", "."), "ru": (",", " ")}       # (Dezimal, Tausender); en/th: (".", ",")
_NBSP = " "


def _group(int_part, lang):
    sep = _SEP.get(lang, (".", ","))[1]
    if lang == "es" and len(int_part) <= 4:      # wie Intl.NumberFormat (es): erst ab 5 Stellen gruppiert
        return int_part
    s = f"{int(int_part):,}"
    return s.replace(",", sep)


def number(v, frac=2, lang=None, fixed=False):
    """Zahl in der Schreibweise der Sprache; bis zu frac Nachkommastellen (fixed: genau frac)"""
    lang = lang or ui_language()
    dp = _SEP.get(lang, (".", ","))[0]
    neg = v < 0
    s = f"{abs(v):.{frac}f}"
    ip, _x, fp = s.partition(".")
    if not fixed:
        fp = fp.rstrip("0")
    out = _group(ip, lang) + (dp + fp if fp else "")
    if neg and out.strip("0" + dp) != "":
        out = "-" + out
    return out


def round_half(v):
    """kaufmaennisch runden (wie Intl.NumberFormat/Math.round fuer positive Werte): 42.5 -> 43, -2.5 -> -3"""
    r = math.floor(abs(v) + 0.5)
    return -r if v < 0 else r


def fmt_value(v, fmt, lang=None):
    """num: bis 2 Nachkommastellen; pct: Wert ist schon Prozent, ganzzahlig (42.7 -> "43 %", 119 bleibt 119);
    eur: immer 2 Stellen mit Euro-Zeichen in der Sprache der Person"""
    lang = lang or ui_language()
    if v is None:
        return "–"
    if fmt == "pct":
        n = number(round_half(v), 0, lang)
        return n + (_NBSP + "%" if lang in _SEP else "%")
    if fmt == "eur":
        n = number(v, 2, lang, fixed=True)
        if lang in _SEP:
            return n + _NBSP + "€"
        return ("-€" + n[1:]) if n.startswith("-") else "€" + n
    return number(v, 2, lang)


# --- Uhr (lokal) ------------------------------------------------------------------------------------------
def zone(tz, viewer=None):
    """ZoneInfo aus cfg.tz; "" oder ungueltig -> Zone des Betrachters (viewer: tzinfo, Standard: System)"""
    if isinstance(tz, str) and tz.strip() and re.fullmatch(r"[A-Za-z0-9_+\-/]{1,64}", tz.strip()) and ".." not in tz:
        try:
            return ZoneInfo(tz.strip())
        except (ValueError, OSError, KeyError):
            pass
    return viewer if viewer is not None else _dt.datetime.now().astimezone().tzinfo


def zone_label(tz):
    """'America/New_York' -> 'New York'; leer bei Betrachter-Zone oder ungueltiger Zone"""
    if not isinstance(tz, str) or not tz.strip():
        return ""
    z = zone(tz, viewer=False)
    if z is False:
        return ""
    return tz.strip().rsplit("/", 1)[-1].replace("_", " ")


def clock_cfg(cfg):
    cfg = cfg if isinstance(cfg, dict) else {}
    return {"tz": _str(cfg, "tz"), "h24": cfg.get("h24") is not False, "sec": cfg.get("sec") is True,
            "analog": cfg.get("analog") is True}


def clock_now(c, now=None, viewer=None):
    """aktuelle Zeit (aware) in der Zone der Uhr; now: aware datetime (Tests)"""
    now = now or _dt.datetime.now(_dt.timezone.utc)
    return now.astimezone(zone(c["tz"], viewer))


def clock_text(t, c):
    """t: datetime in der Zone der Uhr"""
    if c["h24"]:
        s = f"{t.hour:02d}:{t.minute:02d}"
        return s + (f":{t.second:02d}" if c["sec"] else "")
    h = t.hour % 12 or 12
    s = f"{h}:{t.minute:02d}" + (f":{t.second:02d}" if c["sec"] else "")
    return s + " " + (_("AM") if t.hour < 12 else _("PM"))


def hand_angles(t):
    """Zeigerwinkel in Grad (0 = 12 Uhr, im Uhrzeigersinn): Stunde, Minute, Sekunde"""
    s = t.second
    m = t.minute + s / 60.0
    h = (t.hour % 12) + m / 60.0
    return h * 30.0, m * 6.0, s * 6.0


def next_tick_ms(now_ms, sec):
    """Millisekunden bis zur naechsten vollen Sekunde bzw. Minute"""
    step = 1000 if sec else 60000
    return step - int(now_ms) % step


# --- Countdown (lokal) -------------------------------------------------------------------------------------
def countdown_cfg(cfg):
    cfg = cfg if isinstance(cfg, dict) else {}
    try:
        date = _dt.date.fromisoformat(_str(cfg, "date")) if re.fullmatch(r"\d{4}-\d{2}-\d{2}", _str(cfg, "date")) else None
    except ValueError:
        date = None
    tm = None
    if re.fullmatch(r"\d{2}:\d{2}", _str(cfg, "time")):
        hh, mm = int(cfg["time"][:2]), int(cfg["time"][3:])
        if hh < 24 and mm < 60:
            tm = _dt.time(hh, mm)
    return {"date": date, "time": tm, "since": cfg.get("dir") == "since", "auto": cfg.get("unit") == "auto"}


def countdown(c, now):
    """now: aware datetime des Betrachters. -> dict(days|None, hours, minutes, done, today) oder None (kein Datum).
    done: until erreicht bzw. since liegt noch in der Zukunft."""
    date = c["date"]
    if date is None:
        return None
    today = now.date()
    if c["time"] is None:                          # nur Datum: Kalendertage
        d = (today - date).days if c["since"] else (date - today).days
        return {"days": max(0, d), "hours": 0, "minutes": 0, "done": d < 0, "today": d == 0}
    target = _dt.datetime.combine(date, c["time"]).replace(tzinfo=now.tzinfo)
    diff = (now - target) if c["since"] else (target - now)
    secs = diff.total_seconds()
    if secs <= 0:
        return {"days": None if c["auto"] else 0, "hours": 0, "minutes": 0, "done": True, "today": False}
    total_min = int(secs // 60)
    days = total_min // 1440
    if not c["auto"] or days >= 1:
        return {"days": days, "hours": 0, "minutes": 0, "done": False, "today": days == 0 and date == today}
    return {"days": None, "hours": total_min // 60, "minutes": total_min % 60, "done": False, "today": False}


def countdown_text(r, since):
    if r is None:
        return _("Not set up")
    if r["done"]:
        return _("Not started yet") if since else _("Reached")
    if r["today"]:
        return _("Today")
    if r["days"] is None:
        if since:
            return _("{h} h {m} min ago").format(h=r["hours"], m=r["minutes"])
        return _("in {h} h {m} min").format(h=r["hours"], m=r["minutes"])
    n = r["days"]
    if since:
        return ngettext("{n} day ago", "{n} days ago", n).format(n=n)
    return ngettext("in {n} day", "in {n} days", n).format(n=n)


# --- Fortschritt -------------------------------------------------------------------------------------------
def progress(spec, w):
    """(wert, max) oder None: fixed lokal (max > 0), db aus dem Server-Wert (max vom Server oder cfg.goal)"""
    cfg = spec.cfg
    if _str(cfg, "src") == "db":
        if not w or w.get("value") is None:
            return None
        mx = w.get("max") if w.get("max") is not None else _num(cfg.get("goal"))
        return (w["value"], mx) if mx is not None and mx > 0 else None
    v, mx = _num(cfg.get("value")), _num(cfg.get("max"))
    if v is None or mx is None or mx <= 0:
        return None
    return v, mx


def fraction(v, mx):
    return max(0.0, min(1.0, v / mx)) if mx and mx > 0 else 0.0


# --- Zitat --------------------------------------------------------------------------------------------------
def quote_items(cfg):
    items = cfg.get("items") if isinstance(cfg, dict) and isinstance(cfg.get("items"), list) else []
    out = [x.strip()[:300] for x in items if isinstance(x, str) and x.strip()]
    return out[:20]


def fnv1a(s):
    """32-Bit-FNV-1a ueber UTF-8 (wie Android) - stabil auf allen Geraeten"""
    h = 0x811C9DC5
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def quote_index(n, pick, date, bid, rnd=None):
    """daily: deterministisch aus (Datum des Betrachters, Block-ID); open: zufaellig; -1 = keine Zitate"""
    if n <= 0:
        return -1
    if pick == "open":
        return (rnd or random).randrange(n)
    return fnv1a(date.isoformat() + ":" + (bid or "")) % n


# --- Unterseiten (2.5, subpages1): Liste | Galerie ----------------------------------------------------------
SUB_MAX = 200
# Mindestbreite einer Karte in px je Bildart und Groesse (wie im Web: --sp-w in rem * 16)
SUB_WIDTH = {"cover": {"s": 160, "m": 216, "l": 288}, "content": {"s": 116, "m": 152, "l": 200},
             "none": {"s": 136, "m": 176, "l": 224}}
SUB_RATIO = {"cover": 16 / 9, "content": 2 / 3}                 # Breite / Hoehe der Bildflaeche
SUB_THUMB_W = {"cover": 640, "content": 400}                    # ?w= fuer Vorschaubilder
_SUB_IMG = re.compile(r"/(?:files/[A-Za-z0-9._-]{1,80}|covers/[A-Za-z0-9._-]{1,80}\.jpg|"
                      r"api/fed/files/-?[0-9]{1,18}/[A-Za-z0-9._-]{1,80})")


def subpages_cfg(cfg):
    """cfg -> {"view": list|gallery, "image": cover|content|none}; Unbekanntes -> list bzw. cover"""
    cfg = cfg if isinstance(cfg, dict) else {}
    return {"view": "gallery" if cfg.get("view") == "gallery" else "list",
            "image": cfg.get("image") if cfg.get("image") in ("content", "none") else "cover"}


def _sub_path(v):
    """Bildpfad dieses Servers (/files/..., /covers/....jpg, /api/fed/files/...) oder None - nie fremde Adressen"""
    return v if isinstance(v, str) and ".." not in v and _SUB_IMG.fullmatch(v) else None


def subpages_data(data):
    """W.data -> {"items": [{"id", "title", "icon", "img", "kind"}], "total", "more", "can_add"} (falsche Typen fallen weg).
    id: ganze Zahl ungleich 0 (negativ = Seite anderer Saga-Instanzen); icon: Emoji oder Bildpfad; title leer = unbenannt."""
    data = data if isinstance(data, dict) else {}
    items = []
    for it in data.get("items") if isinstance(data.get("items"), list) else []:
        if not isinstance(it, dict) or isinstance(it.get("id"), bool) or not isinstance(it.get("id"), int) or it["id"] == 0:
            continue
        ic = it.get("icon") if isinstance(it.get("icon"), str) else ""
        if ic.startswith("/"):
            ic = _sub_path(ic) or ""
        items.append({"id": it["id"], "title": html.unescape(_str(it, "title")).strip()[:200], "icon": ic[:16] if not ic.startswith("/") else ic,
                      "img": _sub_path(it.get("img")), "kind": "database" if it.get("kind") == "database" else "page"})
        if len(items) >= SUB_MAX:
            break

    def cnt(k):
        v = data.get(k)
        return v if isinstance(v, int) and not isinstance(v, bool) and v >= 0 else 0
    return {"items": items, "total": max(cnt("total"), len(items)), "more": cnt("more"), "can_add": data.get("can_add") is True}


def subpage_title(item):
    return item.get("title") or _("Untitled")


def subpage_label(item):
    """Bezeichnung fuer Screenreader: Titel, Seite bzw. Titel, Datenbank"""
    if item.get("kind") == "database":
        return _("{title}, database").format(title=subpage_title(item))
    return _("{title}, page").format(title=subpage_title(item))


def subpages_more(n):
    return _("+ {n} more").format(n=n) if n > 0 else ""


# --- Darstellung ----------------------------------------------------------------------------------------------
def kind_label(kind):
    return {"clock": _("Clock"), "countdown": _("Countdown"), "progress": _("Progress"), "metric": _("Metric"),
            "quote": _("Quote"), "weather": _("Weather"), "habit": _("Habits"), "upcoming": _("Upcoming"),
            "chart": _("Chart"), "subpages": _("Subpages")}.get(kind, _("Widget"))


def error_text(err):
    return {"disabled": _("Weather is turned off on this server"), "unavailable": _("Weather data not reachable right now"),
            "no_access": _("No access to the data source"), "gone": _("The data source was deleted"),
            "bad_cfg": _("Widget is not set up correctly – edit it on the web"),
            "too_big": _("Data source too large for this widget")}.get(err, _("Value not available"))


def state_text(state):
    """Hinweis, wenn keine Werte da sind (state != ok)"""
    return {"old": _("Values need Saga server 2.5"), "unsupported": _("Widget only available on the originating server"),
            "offline": _("Server not reachable"), "failed": _("Values couldn’t be loaded"),
            "none": _("Loading …")}.get(state, _("Values couldn’t be loaded"))


def present(spec, values, now=None, viewer=None, lang=None, rnd=None):
    """Was die Karte zeigt (ohne GTK): dict mit
    title, kind, main (grosse Zeile oder None), sub (Unterzeile oder None), fraction (Balken 0..1 oder None),
    notice (Hinweis statt Wert oder None), error (Hinweis ist ein Fehler), quote (main ist ein Zitat), clock (cfg der Uhr).
    values: Values; now: aware datetime (Tests); viewer: tzinfo des Betrachters."""
    lang = lang or ui_language()
    now = now or _dt.datetime.now(_dt.timezone.utc)
    viewer = viewer if viewer is not None else _dt.datetime.now().astimezone().tzinfo
    out = {"title": spec.title, "kind": spec.kind, "main": None, "sub": None, "fraction": None, "notice": None,
           "error": False, "quote": False, "clock": None}
    if not spec.known:
        out["notice"] = _("Widget – a newer app version is needed")
        return out
    k = spec.kind
    if k == "clock":
        c = clock_cfg(spec.cfg)
        t = clock_now(c, now, viewer)
        out.update(clock=c, main=clock_text(t, c), sub=zone_label(c["tz"]) or None)
        return out
    if k == "countdown":
        c = countdown_cfg(spec.cfg)
        r = countdown(c, now.astimezone(viewer))
        if r is None:
            out.update(notice=error_text("bad_cfg"), error=True)
            return out
        out["main"] = countdown_text(r, c["since"])
        return out
    w = values.get(spec.id) if values is not None and spec.server_value and spec.id else None
    st = values.state if values is not None else "none"
    if w and w.get("error"):
        out.update(notice=error_text(w["error"]), error=True)
        return out
    if k == "quote":
        txt = (w.get("text") or "").strip() if w else ""
        if not txt and _str(spec.cfg, "src") != "db":       # Rueckfall: festes Zitat lokal nach pick
            items = quote_items(spec.cfg)
            i = quote_index(len(items), _str(spec.cfg, "pick"), now.astimezone(viewer).date(), spec.id, rnd)
            txt = items[i] if i >= 0 else ""
            if not items:
                out["notice"] = _("No quotes yet")
                return out
        if txt:
            out.update(main=txt, quote=True)
            return out
        out["notice"] = _missing(st, w)
        return out
    if k == "progress":
        p = progress(spec, w)
        if p is None:
            if _str(spec.cfg, "src") != "db":
                out.update(notice=error_text("bad_cfg"), error=True)
            else:
                out["notice"] = _missing(st, w)
            return out
        v, mx = p
        fr = fraction(v, mx)      # Balken hoechstens voll, die Zahl zeigt den echten Wert (z. B. 119 %)
        out.update(fraction=fr, main=fmt_value(v / mx * 100, "pct", lang),
                   sub=_("{value} of {max}").format(value=number(v, 2, lang), max=number(mx, 2, lang)))
        return out
    # metric
    if not w or w.get("value") is None:
        out["notice"] = _missing(st, w) if not w else _("No value")
        return out
    fmt = w.get("fmt") or (spec.cfg.get("fmt") if spec.cfg.get("fmt") in FMTS else "num")
    out["main"] = fmt_value(w["value"], fmt, lang)
    goal = _num(spec.cfg.get("goal"))
    if goal is not None and goal > 0:
        out["sub"] = _("Goal: {value}").format(value=fmt_value(goal, fmt, lang))
        out["fraction"] = fraction(w["value"], goal)
    return out


def _missing(state, w):
    """kein Wert fuer ein Server-Widget: Hinweis je nach Stand"""
    if state == "ok" and w is None:
        return _("Value not available yet – it appears after saving")
    return state_text(state)
