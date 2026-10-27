"""Gemeinsame Helfer: Hintergrund-Arbeit, Datum, Inline-HTML, Farben."""
import datetime as _dt
import html as _html
import locale
import random
import re
import string
import sys
import traceback
import unicodedata
from concurrent.futures import ThreadPoolExecutor

from gi.repository import GLib

from .i18n import _, ui_language

# Getrennte Pools: Server-Anfragen (Speichern, Listen) warten nie hinter Bildern, Downloads oder Uploads
_pool = ThreadPoolExecutor(max_workers=8, thread_name_prefix="hk-api")
_media = ThreadPoolExecutor(max_workers=4, thread_name_prefix="hk-media")


def bg(fn, *args, done=None, error=None, media=False):
    """fn(*args) im Hintergrund; done(result) bzw. error(exc) danach im Hauptthread.
    media=True: eigener Pool fuer Bilder, Downloads und Uploads."""
    def run():
        try:
            r = fn(*args)
        except Exception as e:  # noqa: BLE001
            if error is None:
                traceback.print_exc(file=sys.stderr)
            GLib.idle_add(_call, error, e)
            return
        GLib.idle_add(_call, done, r)
    (_media if media else _pool).submit(run)


# --- Schreib-Warteschlangen: je Schluessel (z. B. ("note", 12)) hoechstens ein Zugriff unterwegs, Reihenfolge bleibt ---
_lanes = {}              # Schluessel -> [Eintrag, ...]; der erste laeuft gerade


def bg_serial(key, fn, *args, done=None, error=None, coalesce=None):
    """Wie bg, aber der Reihe nach je key (nur aus dem Hauptthread aufrufen). coalesce: gleich benannte, noch
    wartende Eintraege werden durch den neuesten ersetzt (z. B. ganzes Schema) - deren Rueckrufe laufen trotzdem."""
    lane = _lanes.setdefault(key, [])
    if coalesce is not None and len(lane) > 1 and lane[-1]["co"] == coalesce:
        it = lane[-1]
        it["fn"], it["args"] = fn, args
        it["done"].append(done)
        it["error"].append(error)
        return
    lane.append({"fn": fn, "args": args, "done": [done], "error": [error], "co": coalesce})
    if len(lane) == 1:
        _lane_next(key)


def _lane_next(key):
    lane = _lanes.get(key)
    if not lane:
        _lanes.pop(key, None)
        return
    it = lane[0]

    def fin(r, ok):
        lane.pop(0)
        if lane:
            _lane_next(key)
        elif _lanes.get(key) is lane:
            _lanes.pop(key, None)
        cbs = [cb for cb in (it["done"] if ok else it["error"]) if cb is not None]
        if not ok and not cbs:
            print("hk: Schreibzugriff fehlgeschlagen:", r, file=sys.stderr)
        for cb in cbs:
            _call(cb, r)
    bg(it["fn"], *it["args"], done=lambda r: fin(r, True), error=lambda e: fin(e, False))


def lane_busy(key):
    return bool(_lanes.get(key))


def writes_pending():
    """Anzahl wartender oder laufender Schreibzugriffe (Schliessen wartet darauf)"""
    return sum(len(v) for v in _lanes.values())


class Debounce:
    """Rueckruf erst, wenn ms lang nichts mehr angestossen wurde (Hauptthread)"""

    def __init__(self, ms, fn):
        self.ms, self.fn, self.src = ms, fn, 0

    def __call__(self, *_):
        if self.src:
            GLib.source_remove(self.src)
        self.src = GLib.timeout_add(self.ms, self._fire)

    def _fire(self):
        self.src = 0
        self.fn()
        return False

    def flush(self):
        if self.src:
            GLib.source_remove(self.src)
            self._fire()


def watch_obj(widget, obj, signal, cb):
    """Signal eines langlebigen Objekts nur verbinden, solange das Widget sichtbar ist (GTK4 raeumt sonst nicht auf)"""
    st = {"h": None}

    def on_map(*_):
        if st["h"] is None:
            st["h"] = obj.connect(signal, lambda *a: cb(*a[1:]))

    def on_unmap(*_):
        if st["h"] is not None:
            obj.disconnect(st["h"])
            st["h"] = None
    widget.connect("map", on_map)
    widget.connect("unmap", on_unmap)
    if widget.get_mapped():
        on_map()


def placeholder(row, text):
    """Platzhalter im Eingabefeld einer Adw.EntryRow (sichtbar, solange leer und ausgewaehlt)"""
    try:
        row.get_delegate().set_placeholder_text(text)
    except (AttributeError, TypeError):
        pass


def _call(cb, v):
    if cb:
        try:
            cb(v)
        except Exception:  # noqa: BLE001
            traceback.print_exc(file=sys.stderr)
    return False


def later(ms, fn, *a):
    """einmalig nach ms Millisekunden im Hauptthread"""
    def f():
        fn(*a)
        return False
    return GLib.timeout_add(ms, f)


def new_block_id():
    return "".join(random.choice(string.ascii_letters + string.digits) for _ in range(10))


def new_pid():
    return "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(8))


# --- Datum (Formate je Sprache ueber gettext, Namen von Monaten/Wochentagen aus der Systemsprache) ------------
def today():
    return _dt.date.today()


def iso_day(d=None):
    return (d or _dt.date.today()).isoformat()


def parse_day(s):
    try:
        return _dt.date.fromisoformat(str(s)[:10])
    except (ValueError, TypeError):
        return None


# Monats- und Wochentagsnamen je Sprache der Oberflaeche (wie glibc): gebraucht, wenn die Datumssprache des Systems
# (LC_TIME) nicht zur Oberflaeche passt - in der App gewaehlte Sprache, fehlende Locale -> sonst "25. Oct"
_NAMES = {
    "en": ("Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec",
           "January February March April May June July August September October November December",
           "Mon Tue Wed Thu Fri Sat Sun", "Monday Tuesday Wednesday Thursday Friday Saturday Sunday", None),
    "de": ("Jan Feb Mär Apr Mai Jun Jul Aug Sep Okt Nov Dez",
           "Januar Februar März April Mai Juni Juli August September Oktober November Dezember",
           "Mo Di Mi Do Fr Sa So", "Montag Dienstag Mittwoch Donnerstag Freitag Samstag Sonntag", None),
    "es": ("ene feb mar abr may jun jul ago sep oct nov dic",
           "enero febrero marzo abril mayo junio julio agosto septiembre octubre noviembre diciembre",
           "lun mar mié jue vie sáb dom", "lunes martes miércoles jueves viernes sábado domingo", None),
    "ru": ("янв фев мар апр мая июн июл авг сен окт ноя дек",
           "января февраля марта апреля мая июня июля августа сентября октября ноября декабря",
           "Пн Вт Ср Чт Пт Сб Вс", "Понедельник Вторник Среда Четверг Пятница Суббота Воскресенье",
           "Январь Февраль Март Апрель Май Июнь Июль Август Сентябрь Октябрь Ноябрь Декабрь"),
    "th": ("ม.ค. ก.พ. มี.ค. เม.ย. พ.ค. มิ.ย. ก.ค. ส.ค. ก.ย. ต.ค. พ.ย. ธ.ค.",
           "มกราคม กุมภาพันธ์ มีนาคม เมษายน พฤษภาคม มิถุนายน กรกฎาคม สิงหาคม กันยายน ตุลาคม พฤศจิกายน ธันวาคม",
           "จ. อ. พ. พฤ. ศ. ส. อา.", "จันทร์ อังคาร พุธ พฤหัสบดี ศุกร์ เสาร์ อาทิตย์", None),
}


def _time_lang():
    try:
        loc = locale.getlocale(locale.LC_TIME)[0] or ""
    except (ValueError, TypeError):
        loc = ""
    return loc.split("_")[0].split("-")[0].lower() or "en"


def _own_names(fmt, d, lang):
    """%b %B %OB %a %A durch die Namen der Sprache ersetzen (vor GLib.DateTime.format)"""
    mon, mon_full, wd, wd_full, mon_nom = (x.split(" ") if x else None for x in _NAMES[lang])
    m, w = d.month - 1, d.weekday()
    rep = {"%OB": (mon_nom or mon_full)[m], "%B": mon_full[m], "%b": mon[m], "%h": mon[m], "%a": wd[w], "%A": wd_full[w]}
    out, i = [], 0
    while i < len(fmt):
        for k in ("%%", "%OB", "%B", "%b", "%h", "%a", "%A"):
            if fmt.startswith(k, i):
                out.append("%%" if k == "%%" else rep[k].replace("%", "%%"))
                i += len(k)
                break
        else:
            out.append(fmt[i])
            i += 1
    return "".join(out)


def fmt_date(d, fmt, lang=None):
    """date/datetime mit GLib.DateTime.format; Namen (%a, %b, %B) in der Sprache der Oberflaeche"""
    lang = lang or ui_language()
    if lang in _NAMES and lang != _time_lang():
        fmt = _own_names(fmt, d, lang)
    if isinstance(d, _dt.datetime):
        g = GLib.DateTime.new_local(d.year, d.month, d.day, d.hour, d.minute, d.second)
    else:
        g = GLib.DateTime.new_local(d.year, d.month, d.day, 0, 0, 0)
    return (g.format(fmt) if g else None) or d.isoformat()


def time_text(d):
    # Translators: time of day (GLib.DateTime.format), e.g. 14:30 - use %I:%M %p for a 12-hour clock
    return fmt_date(d, _("%H:%M"))


def weekday_names():
    """abgekuerzte Wochentage Mo..So in der Systemsprache"""
    return [fmt_date(_dt.date(2024, 1, 1) + _dt.timedelta(days=i), "%a") for i in range(7)]


def month_text(d):
    # Translators: month and year above a calendar (GLib.DateTime.format), e.g. "October 2026"
    return fmt_date(d, _("%B %Y"))


def day_text(s):
    """'2026-10-12' -> 'Oct 12' (anderes Jahr: 'Oct 12, 2025')"""
    d = parse_day(s)
    if not d:
        return str(s or "")
    if d.year == _dt.date.today().year:
        # Translators: short date in the current year (GLib.DateTime.format), e.g. "Oct 12"
        return fmt_date(d, _("%b %-d"))
    # Translators: short date in another year (GLib.DateTime.format), e.g. "Oct 12, 2025"
    return fmt_date(d, _("%b %-d, %Y"))


def weekday_day_text(d, year=False):
    """date/datetime -> 'Mon, Oct 12' (mit year: 'Mon, Oct 12, 2026')"""
    if year:
        # Translators: weekday and date with year (GLib.DateTime.format), e.g. "Mon, Oct 12, 2026"
        return fmt_date(d, _("%a, %b %-d, %Y"))
    # Translators: weekday and date (GLib.DateTime.format), e.g. "Mon, Oct 12"
    return fmt_date(d, _("%a, %b %-d"))


def date_num(d):
    """date -> '10/12/2026' (de: '12.10.2026')"""
    # Translators: numeric date (GLib.DateTime.format), e.g. "10/12/2026"
    return fmt_date(d, _("%m/%d/%Y"))


def date_short(ts):
    if not ts:
        return "–"
    return date_num(_dt.datetime.fromtimestamp(float(ts)))


def date_time_text(ts):
    if not ts:
        return ""
    d = _dt.datetime.fromtimestamp(float(ts))
    # Translators: date and time (GLib.DateTime.format), e.g. "Oct 12, 2026, 14:30"
    return fmt_date(d, _("%b %-d, %Y, %H:%M"))


def rel_time(ts, now=None):
    """Zeitstempel -> "vor 5 Minuten" (wie Intl.RelativeTimeFormat im Web); aelter als ein Monat: Datum"""
    import time as _time
    from .i18n import ngettext
    if not ts:
        return ""
    sec = max(0, (now if now is not None else _time.time()) - float(ts))
    if sec < 10:
        return _("now")
    if sec < 60:
        n = int(sec)
        return ngettext("{n} second ago", "{n} seconds ago", n).format(n=n)
    if sec < 3600:
        n = max(1, int(round(sec / 60)))
        return ngettext("{n} minute ago", "{n} minutes ago", n).format(n=n)
    if sec < 86400:
        n = max(1, int(round(sec / 3600)))
        return ngettext("{n} hour ago", "{n} hours ago", n).format(n=n)
    if sec < 86400 * 7:
        n = max(1, int(round(sec / 86400)))
        return ngettext("{n} day ago", "{n} days ago", n).format(n=n)
    if sec < 86400 * 30:
        n = max(1, int(round(sec / (86400 * 7))))
        return ngettext("{n} week ago", "{n} weeks ago", n).format(n=n)
    return date_time_text(ts)


def edited_text(ts):
    if not ts:
        return ""
    d = _dt.datetime.fromtimestamp(float(ts))
    if d.date() == _dt.date.today():
        return _("Edited today, {time}").format(time=time_text(d))
    return _("Edited {date}").format(date=date_time_text(ts))


# --- Zahlen (Dezimal- und Tausendertrennzeichen der Systemsprache) ------------------------------
def _num(x, maxfrac=2):
    s = locale.format_string(f"%.{maxfrac}f", x, grouping=True)
    dp = locale.localeconv().get("decimal_point") or "."
    if maxfrac and dp in s:
        s = s.rstrip("0").rstrip(dp)
    return s


def count_text(n, lang=None):
    """ganze Zahl mit Tausendertrennung der Oberflaechensprache (wie Intl.NumberFormat): en/th 2,000 - de 2.000 -
    es 2000 bzw. 20.000 (erst ab 5 Stellen) - ru 2 000"""
    lang = lang or ui_language()
    n = int(n)
    s = f"{abs(n):,}"
    if lang == "es" and abs(n) < 10000:
        s = str(abs(n))
    sep = {"de": ".", "es": ".", "ru": " "}.get(lang, ",")
    return ("-" if n < 0 else "") + s.replace(",", sep)


def num_text(n, fmt=None, show=None):
    if n is None:
        return ""
    if fmt in ("percent", "euro"):
        # wie Web und Widgets: Prozent ganzzahlig (auch > 100 %), Euro immer 2 Stellen - Schreibweise der Oberflaechensprache
        from .pagewidgets import fmt_value
        return fmt_value(n, "pct" if fmt == "percent" else "eur")
    if float(n).is_integer():
        return _num(n, 0)
    return _num(n, 4)


def parse_num(txt):
    """Eingabe in der Schreibweise der Systemsprache -> float (ValueError, wenn keine Zahl)"""
    t = (txt or "").strip().replace(" ", "").replace("\xa0", "").replace(" ", "")
    dp = locale.localeconv().get("decimal_point") or "."
    if dp == ",":
        t = t.replace(".", "").replace(",", ".")
    else:
        t = t.replace(",", "")
    return float(t)


def norm(s):
    """wie hkNorm: klein, Umlaute ausgeschrieben, andere Akzente entfernt (á -> a, ñ -> n), Leerraum vereinheitlicht"""
    s = (s or "").lower().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
    if not s.isascii():
        s = "".join(c for c in unicodedata.normalize("NFKD", s) if not unicodedata.combining(c))
    return " ".join(s.split())


# --- Inline-HTML (b/i/u/s/mark/code/a/time/br) -----------------------------------------
TAG_RE = re.compile(r"<(/?)([a-zA-Z]+)([^>]*)>")


def decode_entities(s):
    if "&" not in (s or ""):
        return s or ""
    return _html.unescape(s).replace("\xa0", " ")


def esc(s):
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def plain(html):
    """Inline-HTML -> reiner Text"""
    s = re.sub(r"<br\s*/?>", "\n", html or "", flags=re.I)
    s = TAG_RE.sub("", s)
    return decode_entities(s)


# Verweis auf eine Seite, die ich nicht sehen darf: der Server (ab 1.3) ersetzt den Text in der Sprache der Anfrage
NA_TEXTS = frozenset({"Seite ohne Zugriff", "Page without access", "Página sin acceso"})


def link_target(href):
    """'/notes#12' -> ('page', 12) (Seiten anderer Saga-Instanzen: negative IDs); http(s)/mailto -> ('url', href); sonst None"""
    m = re.fullmatch(r"/notes#(-?\d+)(?::.*)?", href or "")
    if m:
        return ("page", int(m.group(1)))
    if (href or "").startswith(("http://", "https://", "mailto:")):
        return ("url", href)
    return None


def md_to_html(md):
    s = esc(md)
    s = re.sub(r"\[([^\]\n]+)\]\(((?:https?://|mailto:|/notes#)[^)\s\"]+)\)", r'<a href="\2">\1</a>', s)
    s = re.sub(r"`([^`\n]+)`", r"<code>\1</code>", s)
    s = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", s)
    s = re.sub(r"~~(.+?)~~", r"<s>\1</s>", s)
    s = re.sub(r"==(.+?)==", r"<mark>\1</mark>", s)
    s = re.sub(r"\+\+(.+?)\+\+", r"<u>\1</u>", s)
    s = re.sub(r"(?<![\w])_(.+?)_(?![\w])", r"<i>\1</i>", s)
    return s.replace("\n", "<br>")


# --- Farben der Auswahl-Optionen (wie im Web) -------------------------------------------
COLORS = {
    "gray": "#8e8e93", "brown": "#a2845e", "orange": "#ff9500", "yellow": "#ffcc00", "green": "#34c759",
    "blue": "#007aff", "purple": "#af52de", "pink": "#ff2d55", "red": "#ff3b30",
}
COLOR_NAMES = {"gray": _("Gray"), "brown": _("Brown"), "orange": _("Orange"), "yellow": _("Yellow"), "green": _("Green"),
               "blue": _("Blue"), "purple": _("Purple"), "pink": _("Pink"), "red": _("Red")}
COLOR_ORDER = ["gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red"]


def color_hex(name):
    return COLORS.get(name or "", COLORS["gray"])


def human_size(n):
    """Dateigroesse in der Sprache der App (wie GLib.format_size: Dezimal-Einheiten, eine Nachkommastelle) - GLib nimmt
    die Systemsprache bzw. hat keine Kataloge ("44 bytes" in einer deutschen App, Testbericht 032)"""
    from .i18n import ngettext, ui_language
    n = max(0, int(n or 0))
    if n < 1000:
        return ngettext("{n} byte", "{n} bytes", n).format(n=n)
    units = (_("{size} kB"), _("{size} MB"), _("{size} GB"), _("{size} TB"))
    v, i = n / 1000.0, 0
    while v >= 999.95 and i < len(units) - 1:
        v, i = v / 1000.0, i + 1
    s = f"{v:.1f}"
    if ui_language() in ("de", "es", "ru"):
        s = s.replace(".", ",")
    return units[i].format(size=s)
