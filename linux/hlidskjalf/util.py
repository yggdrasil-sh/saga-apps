"""Gemeinsame Helfer: Hintergrund-Arbeit, Datum, Inline-HTML, Farben."""
import datetime as _dt
import html as _html
import random
import re
import string
import sys
import traceback
from concurrent.futures import ThreadPoolExecutor

from gi.repository import GLib

_pool = ThreadPoolExecutor(max_workers=8, thread_name_prefix="hk")


def bg(fn, *args, done=None, error=None):
    """fn(*args) im Hintergrund; done(result) bzw. error(exc) danach im Hauptthread."""
    def run():
        try:
            r = fn(*args)
        except Exception as e:  # noqa: BLE001
            if error is None:
                traceback.print_exc(file=sys.stderr)
            GLib.idle_add(_call, error, e)
            return
        GLib.idle_add(_call, done, r)
    _pool.submit(run)


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


# --- Datum -----------------------------------------------------------------------------
MONTHS = ["Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sept.", "Okt.", "Nov.", "Dez."]
MONTHS_LONG = ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"]
WEEKDAYS = ["Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa.", "So."]


def today():
    return _dt.date.today()


def iso_day(d=None):
    return (d or _dt.date.today()).isoformat()


def parse_day(s):
    try:
        return _dt.date.fromisoformat(str(s)[:10])
    except (ValueError, TypeError):
        return None


def day_text(s):
    """'2026-10-12' -> '12. Okt.' (anderes Jahr: '12. Okt. 2025')"""
    d = parse_day(s)
    if not d:
        return str(s or "")
    t = f"{d.day}. {MONTHS[d.month - 1]}"
    return t if d.year == _dt.date.today().year else f"{t} {d.year}"


def date_short(ts):
    if not ts:
        return "–"
    d = _dt.datetime.fromtimestamp(float(ts))
    return d.strftime("%d.%m.%Y")


def date_time_text(ts):
    if not ts:
        return ""
    d = _dt.datetime.fromtimestamp(float(ts))
    return f"{d.day}. {MONTHS[d.month - 1]} {d.year}, {d:%H:%M}"


def edited_text(ts):
    if not ts:
        return ""
    d = _dt.datetime.fromtimestamp(float(ts))
    if d.date() == _dt.date.today():
        return f"Bearbeitet heute, {d:%H:%M}"
    return "Bearbeitet " + date_time_text(ts)


def num_text(n, fmt=None, show=None):
    def de(x, maxfrac=2):
        s = f"{x:,.{maxfrac}f}".rstrip("0").rstrip(".") if maxfrac else f"{x:,.0f}"
        return s.replace(",", "X").replace(".", ",").replace("X", ".")
    if n is None:
        return ""
    if fmt == "percent" and show in ("ring", "bar"):
        return de(round(n), 0) + " %"
    if fmt == "percent":
        return de(n) + " %"
    if fmt == "euro":
        s = f"{n:,.2f}".replace(",", "X").replace(".", ",").replace("X", ".")
        return s + " €"
    if float(n).is_integer():
        return de(n, 0)
    return de(n, 4)


def norm(s):
    """wie hkNorm: klein, Umlaute ausgeschrieben, Leerraum vereinheitlicht"""
    s = (s or "").lower().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
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


def link_target(href):
    """'/notes#12' -> ('page', 12); http(s)/mailto -> ('url', href); sonst None"""
    m = re.fullmatch(r"/notes#(\d+)(?::.*)?", href or "")
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
COLOR_NAMES = {"gray": "Grau", "brown": "Braun", "orange": "Orange", "yellow": "Gelb", "green": "Grün", "blue": "Blau",
               "purple": "Lila", "pink": "Rosa", "red": "Rot"}
COLOR_ORDER = ["gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red"]


def color_hex(name):
    return COLORS.get(name or "", COLORS["gray"])


def human_size(n):
    n = float(n or 0)
    for unit in ("Bytes", "KB", "MB", "GB"):
        if n < 1000 or unit == "GB":
            return (f"{n:.0f} {unit}" if unit == "Bytes" else f"{n:.1f} {unit}").replace(".", ",")
        n /= 1000
    return ""
