"""Datenbank-Werte Saga 2.5 ohne GTK (testbar): Formeln/Rollups (rtype, fx_err), Datum mit Uhrzeit/Zeitraum (datetime1),
Status mit Gruppen (status1), relative Datumsfilter (relfilter1), Personen (person1) und Dateien (file1).
Vertrag: docs/APP-API.md des Servers ("Formeln und Rollups", "Paket 5", "Personen und Dateien")."""
import datetime as _dt
import re

from .i18n import _

FX = ("formula", "rollup")
STATUS_GROUPS = ("todo", "doing", "done")
STATUS_ICON = {"todo": "○", "doing": "◐", "done": "●"}
FX_ERRORS = ("syntax", "type", "num", "len", "budget", "time", "arg", "ref", "date", "cycle")
REL_OPS = ("yesterday", "tomorrow", "last_week", "next_week", "last_month", "next_month", "this_year", "overdue",
           "next_days", "past_days", "on_or_before", "on_or_after")
DAY_OPS = ("today", "this_week", "this_month") + REL_OPS


def is_fx(p):
    return isinstance(p, dict) and p.get("type") in FX


def eff_type(p):
    """Typ fuer Anzeige/Filter/Sortierung: Formel/Rollup verhalten sich wie ihr rtype (list wie Mehrfachauswahl-Text)"""
    t = (p or {}).get("type")
    if t in FX:
        return {"number": "number", "text": "text", "checkbox": "checkbox", "date": "date", "list": "list"}.get(p.get("rtype"), "text")
    return t


def fx_error_text(code):
    """Erklaerung fuer "#Fehler" (Tooltip)"""
    return {"syntax": _("The formula has a syntax error"), "type": _("Wrong type in the formula"),
            "num": _("Calculation not possible (e.g. division by zero)"), "len": _("Result too long"),
            "budget": _("Formula too complex"), "time": _("Not calculated in time"), "arg": _("Wrong arguments"),
            "ref": _("A property used in the formula was deleted"), "date": _("Invalid date"),
            "cycle": _("Formulas refer to each other")}.get(code, _("The formula could not be calculated"))


def fx_error(r, pid):
    """Fehlercode einer Formel-/Rollup-Zelle (row["fx_err"]) oder None"""
    e = (r or {}).get("fx_err")
    return e.get(pid) if isinstance(e, dict) and isinstance(e.get(pid), str) else None


def apply_patch_answer(row, answer):
    """PATCH-Antwort einer Zeile einarbeiten: calc (Formeln/Rollups, null = leer), fx_err, values (Personen/Dateien wie
    gespeichert). row: dict mit "props" (wird geaendert). -> neue people (dict) oder None"""
    if not isinstance(answer, dict) or not isinstance(row, dict):
        return None
    props = row.setdefault("props", {})
    for k, v in (answer.get("calc") or {}).items() if isinstance(answer.get("calc"), dict) else ():
        if v is None:
            props.pop(k, None)
        else:
            props[k] = v
    if isinstance(answer.get("fx_err"), dict):
        if answer["fx_err"]:
            row["fx_err"] = dict(answer["fx_err"])
        else:
            row.pop("fx_err", None)
    for k, v in (answer.get("values") or {}).items() if isinstance(answer.get("values"), dict) else ():
        if v in (None, []):
            props.pop(k, None)
        else:
            props[k] = v
    return answer.get("people") if isinstance(answer.get("people"), dict) else None


def strip_computed(schema, props):
    """Berechnete Werte (Formel, Rollup) nie zurueckschreiben"""
    fx = {p.get("id") for p in (schema or {}).get("props") or [] if is_fx(p)}
    return {k: v for k, v in (props or {}).items() if k not in fx}


# --- Status ---------------------------------------------------------------------------------------------------------
def is_status(p):
    return isinstance(p, dict) and p.get("type") == "select" and p.get("status") is True


def option_group(p, oid):
    o = next((o for o in (p or {}).get("options") or [] if o.get("id") == oid), None)
    if o is None:
        return None
    g = o.get("group")
    return g if g in STATUS_GROUPS else "todo"


def status_label(p, o):
    """"○ Offen" bei Status-Auswahl, sonst der Name"""
    name = (o or {}).get("name") or ""
    if not is_status(p):
        return name
    g = o.get("group") if o.get("group") in STATUS_GROUPS else "todo"
    return f"{STATUS_ICON[g]} {name}"


def group_name(g):
    return {"todo": _("To do"), "doing": _("In progress"), "done": _("Done")}.get(g, g or "")


def grouped_options(p):
    """Optionen nach Gruppen (Zu erledigen / In Arbeit / Erledigt) -> [(gruppe, [option, ...])]; ohne Status: [(None, alle)]"""
    opts = [o for o in (p or {}).get("options") or [] if isinstance(o, dict)]
    if not is_status(p):
        return [(None, opts)]
    out = []
    for g in STATUS_GROUPS:
        sub = [o for o in opts if (o.get("group") if o.get("group") in STATUS_GROUPS else "todo") == g]
        if sub:
            out.append((g, sub))
    return out


def first_option_of_group(p, g):
    return next((o.get("id") for o in (p or {}).get("options") or [] if option_group(p, o.get("id")) == g), None)


def move_to_group(p, cur, g):
    """Karte in eine Gruppen-Spalte: erste Option der Gruppe; liegt sie schon in der Gruppe: unveraendert (None)"""
    if cur is not None and option_group(p, cur) == g:
        return None
    return first_option_of_group(p, g)


def done_option(p):
    """Abhaken: erste Option der Gruppe done"""
    return first_option_of_group(p, "done")


# --- Datum mit Uhrzeit / Zeitraum -----------------------------------------------------------------------------------
_PART = re.compile(r"^(\d{4}-\d{2}-\d{2})(?:T(\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$")


def _tz_of(s):
    if s is None:
        return None
    if s == "Z":
        return _dt.timezone.utc
    sign = -1 if s[0] == "-" else 1
    hh, mm = int(s[1:3]), int(s[-2:])
    return _dt.timezone(sign * _dt.timedelta(hours=hh, minutes=mm))


class Part:
    """ein Zeitpunkt: day (date), time ((h, m) oder None), tz (tzinfo oder None = Ortszeit ohne Offset, z. B. Formel)"""
    __slots__ = ("day", "time", "tz")

    def __init__(self, day, time=None, tz=None):
        self.day, self.time, self.tz = day, time, tz

    def local(self, viewer):
        """(Tag, (h, m) | None) in der Zeitzone des Betrachters"""
        if self.time is None or self.tz is None:
            return self.day, self.time
        d = _dt.datetime(self.day.year, self.day.month, self.day.day, self.time[0], self.time[1], tzinfo=self.tz).astimezone(viewer)
        return d.date(), (d.hour, d.minute)

    def key(self, viewer):
        d, t = self.local(viewer)
        return d.isoformat() + ("T%02d:%02d" % t if t else "")


def parse_part(s):
    m = _PART.match((s or "").strip())
    if not m:
        return None
    try:
        day = _dt.date.fromisoformat(m.group(1))
    except ValueError:
        return None
    if m.group(2) is None:
        return Part(day)
    h, mi = int(m.group(2)), int(m.group(3))
    if h > 23 or mi > 59:
        return None
    return Part(day, (h, mi), _tz_of(m.group(4)))


def parse_date(v):
    """Datumswert -> (start Part, end Part | None) oder None. Formen: Tag, Zeitpunkt mit Offset, Zeitraum a/b."""
    if not isinstance(v, str) or len(v) < 10:
        return None
    if "/" in v:
        a, b = v.split("/", 1)
        pa, pb = parse_part(a), parse_part(b)
        if pa is None:
            return None
        return pa, pb
    p = parse_part(v)
    return (p, None) if p else None


def viewer_tz():
    return _dt.datetime.now().astimezone().tzinfo


def day_span(v, viewer=None):
    """(erster Tag, letzter Tag) in der Zeitzone des Betrachters oder None"""
    pr = parse_date(v)
    if pr is None:
        return None
    viewer = viewer or viewer_tz()
    a = pr[0].local(viewer)[0]
    b = pr[1].local(viewer)[0] if pr[1] else a
    return a, max(a, b)


def sort_key(v, viewer=None):
    """Sortierung nach Start (lokal YYYY-MM-DDTHH:MM, reiner Tag = Tagesbeginn)"""
    pr = parse_date(v)
    if pr is None:
        return None
    return pr[0].key(viewer or viewer_tz())


def has_time(v):
    pr = parse_date(v)
    return bool(pr and (pr[0].time or (pr[1] and pr[1].time)))


def is_range(v):
    pr = parse_date(v)
    return bool(pr and pr[1] is not None)


def date_text(v, viewer=None, day_fmt=None, time_fmt=None):
    """Anzeige in der Zeitzone des Betrachters: "27. Okt. 14:30", Zeitraum "27. Okt. 14:30 – 16:00" (gleicher Tag)
    bzw. "27. Okt. – 29. Okt.". day_fmt/time_fmt: Formatierer (Tests), sonst util.day_text/time_text."""
    pr = parse_date(v)
    if pr is None:
        return str(v or "")
    if day_fmt is None or time_fmt is None:
        from .util import day_text, time_text
        day_fmt = day_fmt or (lambda d: day_text(d.isoformat()))
        time_fmt = time_fmt or (lambda h, m: time_text(_dt.datetime(2000, 1, 1, h, m)))
    viewer = viewer or viewer_tz()
    d1, t1 = pr[0].local(viewer)
    s = day_fmt(d1) + (" " + time_fmt(*t1) if t1 else "")
    if pr[1] is None:
        return s
    d2, t2 = pr[1].local(viewer)
    if d2 == d1 and t2:
        return s + " – " + time_fmt(*t2)
    if d2 == d1 and not t2:
        return s
    return s + " – " + day_fmt(d2) + (" " + time_fmt(*t2) if t2 else "")


def original_text(v, viewer=None):
    """Originalzeit, wenn die Zone des Eintrags anders war (Tooltip) - sonst None"""
    pr = parse_date(v)
    if pr is None or pr[0].time is None or pr[0].tz is None:
        return None
    viewer = viewer or viewer_tz()
    ref = _dt.datetime(pr[0].day.year, pr[0].day.month, pr[0].day.day, pr[0].time[0], pr[0].time[1], tzinfo=pr[0].tz)
    if ref.utcoffset() == ref.astimezone(viewer).utcoffset():
        return None
    return v


def offset_text(off):
    """timedelta -> "+01:00" """
    mins = int(off.total_seconds() // 60)
    sign = "-" if mins < 0 else "+"
    mins = abs(mins)
    return "%s%02d:%02d" % (sign, mins // 60, mins % 60)


def compose_part(day, time=None, viewer=None):
    """Ortszeit des Geraets -> "YYYY-MM-DD" bzw. "YYYY-MM-DDTHH:MM+hh:mm" (mit dem Offset des Geraets an diesem Tag)"""
    if time is None:
        return day.isoformat()
    loc = _dt.datetime(day.year, day.month, day.day, time[0], time[1])
    try:
        # ohne Zone: Ortszeit des Geraets mit dessen Regeln (Sommerzeit am jeweiligen Tag)
        off = loc.astimezone().utcoffset() if viewer is None else loc.replace(tzinfo=viewer).utcoffset()
    except (ValueError, OverflowError, OSError):
        off = None
    if off is None:
        off = _dt.timedelta(0)
    return "%sT%02d:%02d%s" % (day.isoformat(), time[0], time[1], offset_text(off))


def compose(start_day, start_time=None, end_day=None, end_time=None, old=None, full=True, viewer=None):
    """Wert zum Speichern. full=False (Server ohne datetime1): nur der Tag (Server behaelt Uhrzeit/Ende bei gleichem Tag).
    Uhrzeit/Ende bewusst entfernt (old hatte sie, gleicher Starttag): "Tag/Tag" - nur den Tag zu schicken behielte sie."""
    if start_day is None:
        return None
    if not full:
        return start_day.isoformat()
    a = compose_part(start_day, start_time, viewer)
    if end_day is not None:
        b = compose_part(end_day, end_time, viewer)
        if b != a:
            return a + "/" + b
    if start_time is None and end_day is None and old is not None:
        po = parse_date(old)
        if po and (po[0].time or po[1]) and po[0].day == start_day:
            d = start_day.isoformat()
            return d + "/" + d
    return a


def valid_range(start_day, start_time, end_day, end_time):
    """Ende nicht vor Beginn (beides Ortszeit des Geraets; Ende ohne Uhrzeit = ganzer Tag)"""
    if end_day is None:
        return True
    return (end_day, end_time or (23, 59)) >= (start_day, start_time or (0, 0))


# --- Datumsfilter (Fenster in Tagen) --------------------------------------------------------------------------------
def _week_start(d):
    return d - _dt.timedelta(days=d.weekday())


def _month_bounds(y, m):
    a = _dt.date(y, m, 1)
    b = _dt.date(y + (m == 12), m % 12 + 1, 1) - _dt.timedelta(days=1)
    return a, b


def _stichtag(val, today):
    s = str(val or "").strip()
    if s == "today":
        return today
    m = re.fullmatch(r"today([+-])(\d{1,4})", s)
    if m:
        n = int(m.group(2))
        return today + _dt.timedelta(days=n if m.group(1) == "+" else -n)
    try:
        return _dt.date.fromisoformat(s) if len(s) == 10 else None
    except ValueError:
        return None


def _count(val):
    try:
        n = int(str(val).strip())
    except (TypeError, ValueError):
        return None
    return n if 1 <= n <= 366 else None


def date_window(op, val, today):
    """-> ("win", a, b) | ("before", d) | ("after", d) | ("overdue",) | None (Filter zaehlt nicht)"""
    t = today
    if op == "today":
        return "win", t, t
    if op == "yesterday":
        d = t - _dt.timedelta(days=1)
        return "win", d, d
    if op == "tomorrow":
        d = t + _dt.timedelta(days=1)
        return "win", d, d
    if op in ("this_week", "last_week", "next_week"):
        a = _week_start(t) + _dt.timedelta(days={"this_week": 0, "last_week": -7, "next_week": 7}[op])
        return "win", a, a + _dt.timedelta(days=6)
    if op == "this_month":
        return ("win",) + _month_bounds(t.year, t.month)
    if op == "last_month":
        y, m = (t.year - 1, 12) if t.month == 1 else (t.year, t.month - 1)
        return ("win",) + _month_bounds(y, m)
    if op == "next_month":
        y, m = (t.year + 1, 1) if t.month == 12 else (t.year, t.month + 1)
        return ("win",) + _month_bounds(y, m)
    if op == "this_year":
        return "win", _dt.date(t.year, 1, 1), _dt.date(t.year, 12, 31)
    if op == "overdue":
        return ("overdue",)
    if op in ("next_days", "past_days"):
        n = _count(val)
        if n is None:
            return None
        if op == "next_days":
            return "win", t, t + _dt.timedelta(days=n - 1)
        return "win", t - _dt.timedelta(days=n - 1), t
    if op in ("on_or_before", "on_or_after"):
        d = _stichtag(val, t)
        if d is None:
            return None
        return ("before" if op == "on_or_before" else "after"), d
    return None


def match_day_filter(span, op, val, today):
    """span (erster, letzter Tag) oder None (leer). -> True/False; Filter, die nicht zaehlen: True (auch leere Zeilen)"""
    w = date_window(op, val, today)
    if w is None:
        return True
    if span is None:
        return False
    a, b = span
    if w[0] == "win":
        return a <= w[2] and b >= w[1]
    if w[0] == "before":
        return a <= w[1]
    if w[0] == "after":
        return b >= w[1]
    return b < today                                       # overdue: Ende vor heute


def match_status_group(p, oid, val):
    if val not in ("todo", "doing", "done", "open"):
        return True
    g = option_group(p, oid) if isinstance(oid, str) else None
    if g is None:
        return False
    return g != "done" if val == "open" else g == val


# --- Personen -------------------------------------------------------------------------------------------------------
def person_refs(v):
    return [x for x in v if isinstance(x, str) and x] if isinstance(v, list) else []


def person_info(ref, people):
    """-> {"name", "avatar", "me", "remote", "gone"} - nie der Anmeldename. Unbekannt = geloeschte Person."""
    e = (people or {}).get(ref) if isinstance(people, dict) else None
    if not isinstance(e, dict) or e.get("gone"):
        return {"name": _("Deleted person"), "avatar": None, "me": False, "remote": False, "gone": True}
    name = e.get("name") if isinstance(e.get("name"), str) and e.get("name").strip() else _("Unknown person")
    av = e.get("avatar") if isinstance(e.get("avatar"), str) and e.get("avatar").startswith("/") else None
    return {"name": name, "avatar": av, "me": e.get("me") is True, "remote": e.get("remote") is True, "gone": False}


def person_label(ref, people):
    i = person_info(ref, people)
    return _("{name} (me)").format(name=i["name"]) if i["me"] else i["name"]


def initials(name):
    parts = [p for p in re.split(r"\s+", (name or "").strip()) if p]
    if not parts:
        return "?"
    return (parts[0][0] + (parts[-1][0] if len(parts) > 1 else "")).upper()


def is_me(ref, people):
    e = (people or {}).get(ref) if isinstance(people, dict) else None
    return isinstance(e, dict) and e.get("me") is True


def merge_people(base, extra):
    out = dict(base or {})
    for k, v in (extra or {}).items():
        if isinstance(k, str) and isinstance(v, dict):
            out[k] = v
    return out


def people_names(v, people):
    return [person_info(r, people)["name"] for r in person_refs(v)]


# --- Dateien --------------------------------------------------------------------------------------------------------
def file_items(v):
    """[{url, name, size, mime, image}] - nur gueltige Eintraege (url beginnt mit /)"""
    out = []
    for f in v if isinstance(v, list) else []:
        if isinstance(f, dict) and isinstance(f.get("url"), str) and f["url"].startswith("/"):
            out.append({"url": f["url"], "name": f.get("name") if isinstance(f.get("name"), str) and f.get("name") else f["url"].rsplit("/", 1)[-1],
                        "size": f.get("size") if isinstance(f.get("size"), int) else None,
                        "mime": f.get("mime") if isinstance(f.get("mime"), str) else None, "image": f.get("image") is True})
    return out


def files_for_patch(items):
    """Gespeichert werden nur url und name - die ganze Liste schicken"""
    return [{"url": f["url"], "name": f["name"]} for f in items]


def size_text(n):
    if not isinstance(n, int):
        return ""
    for unit, k in (("GB", 1 << 30), ("MB", 1 << 20), ("KB", 1 << 10)):
        if n >= k:
            return f"{n / k:.1f} {unit}".replace(".0 ", " ")
    return f"{n} B"


# --- Formel-Werte anzeigen ------------------------------------------------------------------------------------------
def fx_text(p, v):
    """Formel/Rollup-Wert als Text (Tabellenzelle ohne eigene Darstellung)"""
    rt = (p or {}).get("rtype")
    if v is None:
        return ""
    if rt == "checkbox":
        return "✓" if v is True else ""
    if rt == "list":
        return ", ".join(str(x) for x in v) if isinstance(v, list) else str(v)
    if rt == "date":
        return date_text(v) if isinstance(v, str) else str(v)
    if rt == "number" and isinstance(v, (int, float)) and not isinstance(v, bool):
        from .util import num_text
        return num_text(v, p.get("fmt"), p.get("show"))
    return str(v)


# --- Gruppen (wie saga/dbquery.py groups / static/pages.js) ---------------------------------------------------------
_WEEKDAYS = (("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"), ("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"),
             ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"), ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
             ("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"), ("lun", "mar", "mié", "jue", "vie", "sáb", "dom"),
             ("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"), ("пн", "вт", "ср", "чт", "пт", "сб", "вс"),
             ("วันจันทร์", "วันอังคาร", "วันพุธ", "วันพฤหัสบดี", "วันศุกร์", "วันเสาร์", "วันอาทิตย์"), ("จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.", "อา."))
WEEKDAY_INDEX = {}
for _names in _WEEKDAYS:
    for _i, _n in enumerate(_names):
        WEEKDAY_INDEX.setdefault(_n, _i)


def _u16(s):
    return (s or "").encode("utf-16-be")


def groupable(p):
    if not isinstance(p, dict):
        return False
    t = p.get("type")
    return t in ("select", "person") or (t in FX and p.get("rtype") in ("number", "text", "checkbox", "date"))


def _js_str(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    return str(v)


def value_groups(rows, rtype, get):
    """Formel/Rollup: Gruppen = vorkommende Werte -> [(None, leere), (wert, zeilen), ...] (Reihenfolge wie der Server)"""
    empty, order, buckets = [], [], {}
    for r in rows:
        v = get(r)
        if rtype == "checkbox":
            v = v is True
        elif v is None or v == "" or isinstance(v, (list, dict)) and not v:
            empty.append(r)
            continue
        k = (type(v).__name__ if not isinstance(v, (int, float)) or isinstance(v, bool) else "num", _js_str(v))
        if k not in buckets:
            buckets[k] = (v, [])
            order.append(k)
        buckets[k][1].append(r)
    vals = [buckets[k][0] for k in order]
    if rtype == "number":
        def key(v):
            return (0, float(v)) if isinstance(v, (int, float)) and not isinstance(v, bool) else (1, 0.0)
    elif rtype == "checkbox":
        def key(v):
            return (0, 1 if v is True else 0)
    elif vals and all(isinstance(v, str) and v in WEEKDAY_INDEX for v in vals):
        def key(v):
            return (0, WEEKDAY_INDEX[v])
    else:
        def key(v):
            return (0, _u16(_js_str(v)))
    idx = sorted(range(len(order)), key=lambda i: key(vals[i]))
    return [(None, empty)] + [(vals[i], buckets[order[i]][1]) for i in idx]


def person_groups(rows, get, people):
    """je Kombination von Personen -> [(None, ohne), (kennungen mit ",", zeilen), ...] nach den Namen"""
    empty, order, buckets = [], [], {}
    for r in rows:
        refs = person_refs(get(r))
        if not refs:
            empty.append(r)
            continue
        k = ",".join(refs)
        if k not in buckets:
            buckets[k] = (", ".join(person_info(x, people)["name"] for x in refs).lower(), [])
            order.append(k)
        buckets[k][1].append(r)
    order.sort(key=lambda k: (_u16(buckets[k][0]), _u16(k)))
    return [(None, empty)] + [(k, buckets[k][1]) for k in order]


def status_groups(p, rows, get):
    """Ansicht group_status: [(None, ohne), ("todo", ...), ("doing", ...), ("done", ...)]"""
    out = [(None, [r for r in rows if option_group(p, get(r)) is None])]
    for g in STATUS_GROUPS:
        out.append((g, [r for r in rows if option_group(p, get(r)) == g]))
    return out


# --- Unteraufgaben (view.sub, wie saga/dbquery.py nest) -------------------------------------------------------------
SUB_DEPTH = 10


def nest(all_rows, shown, sub, get):
    """Kinder direkt hinter ihren Eltern; Eltern-Kette von Treffern bleibt sichtbar. -> [(zeile, tiefe)].
    get(zeile, pid) -> Wert. parents_only: nur Zeilen ohne (vorhandene) Eltern. Kreise/zu tief: als Wurzel."""
    pid = (sub or {}).get("parent")
    mode = (sub or {}).get("mode")
    by_id = {r.get("id"): r for r in all_rows}

    def parent_of(r):
        v = get(r, pid)
        if isinstance(v, list) and v and isinstance(v[0], (int, float)) and not isinstance(v[0], bool) and v[0] in by_id \
                and v[0] != r.get("id"):
            return v[0]
        return None
    if mode == "parents_only":
        return [(r, 0) for r in shown if parent_of(r) is None]
    if mode != "nested" or not pid:
        return [(r, 0) for r in shown]
    keep, order = {}, {}
    for i, r in enumerate(shown):
        order[r.get("id")] = i
        keep[r.get("id")] = r
    for r in shown:
        cur, n = parent_of(r), 0
        while cur is not None and n < SUB_DEPTH:
            if cur not in keep:
                keep[cur] = by_id[cur]
            cur, n = parent_of(by_id[cur]), n + 1
    base = len(shown)
    rank = {rid: order.get(rid, base + k) for k, rid in enumerate(r.get("id") for r in all_rows)}
    kids, roots = {}, []
    for rid, r in keep.items():
        par = parent_of(r)
        seen, cur = {rid}, par
        while cur is not None and cur not in seen and len(seen) <= SUB_DEPTH:
            seen.add(cur)
            cur = parent_of(by_id[cur]) if cur in keep else None
        if par is None or par not in keep or cur is not None:
            roots.append(rid)
        else:
            kids.setdefault(par, []).append(rid)
    out = []

    def walk(rid, depth):
        out.append((keep[rid], depth))
        for k in sorted(kids.get(rid, []), key=rank.__getitem__):
            walk(k, depth + 1)
    for rid in sorted(roots, key=rank.__getitem__):
        walk(rid, 0)
    return out
