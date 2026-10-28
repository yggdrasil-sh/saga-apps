"""Formatierter Text: gespeichertes Inline-HTML <-> Gtk.TextBuffer mit Tags (wie RichText.swift / EditText)."""
import datetime as _dt
import re

from gi.repository import Gdk, GLib, GObject, Gtk, Pango

from .i18n import _
from .util import NA_TEXTS, TAG_RE, decode_entities, esc, link_target, norm, time_text, weekday_day_text

FORMAT_TAGS = ("b", "i", "u", "s", "mark", "code")

# 2.5 Inline-Vertrag (P6-0): span.hk-c-*, mark.hk-bg-*, span.hk-mention[data-u], code.hk-math und jede andere Klasse an
# span/mark/code bleiben als eigenes Tag "x:<Original-Start-Tag>" erhalten und werden beim Speichern unveraendert
# geschrieben (X-Saga-Caps inline2). Darstellung: Farben wie das Web (--tc-*/--bc-*), Erwaehnung als Chip, Mathe als Code.
X_PREFIX = "x:"
KEEP_TAGS = ("span", "mark", "code")
INLINE_OK = frozenset(("b", "strong", "i", "em", "u", "s", "strike", "del", "mark", "code", "a", "br", "time", "span"))
TC = {"gray": ("#686763", "#b0afab"), "brown": ("#8a5a44", "#c9a08b"), "orange": ("#a65300", "#f0a35e"),
      "yellow": ("#7f6200", "#e6c54f"), "green": ("#0b6e60", "#4fc4a9"), "blue": ("#0b6893", "#62b4e2"),
      "purple": ("#6940a5", "#b893e6"), "pink": ("#a8186e", "#e682bb"), "red": ("#c22a2a", "#f28585")}
BC = {"gray": "#9b9a97", "brown": "#9f6b53", "orange": "#d9730d", "yellow": "#dfab01", "green": "#0f7b6c",
      "blue": "#0b6e99", "purple": "#6940a5", "pink": "#ad1a72", "red": "#e03e3e"}
MENTION_NAMES = {}          # Kennung -> Anzeigename (Seiten-/Datenbank-Antworten "people"), nie der Anmeldename
_CLASS_RE = re.compile(r'class="([^"]*)"')
_DATA_U_RE = re.compile(r'data-u="([^"]*)"')


def is_dark():
    try:
        from gi.repository import Adw
        return Adw.StyleManager.get_default().get_dark()
    except Exception:  # noqa: BLE001 - ohne Display (Tests)
        return False


def x_kind(open_tag):
    """("color", farbe) | ("bg", farbe) | ("mention", kennung) | ("math", None) | ("other", None)"""
    m = _CLASS_RE.search(open_tag)
    cls = (m.group(1) if m else "").split()
    tag = open_tag[1:].split(None, 1)[0].rstrip(">").lower()
    if tag == "span" and "hk-mention" in cls:
        u = _DATA_U_RE.search(open_tag)
        return "mention", decode_entities(u.group(1)) if u else ""
    if tag == "code" and "hk-math" in cls:
        return "math", None
    if tag == "code":
        return "code", None
    if tag == "mark" and "cdx-marker" in cls and not any(c.startswith("hk-bg-") for c in cls):
        return "marker", None
    for c in cls:
        if tag == "span" and c.startswith("hk-c-") and c[5:] in TC:
            return "color", c[5:]
        if tag == "mark" and c.startswith("hk-bg-") and c[6:] in BC:
            return "bg", c[6:]
    return "other", None


def keep_raw(tag, attrs):
    """span/mark/code mit Klasse bleiben als Original-Tag erhalten (Tags samt Klasse, auch unverstandene - inline2)"""
    m = _CLASS_RE.search(attrs or "")
    return tag in KEEP_TAGS and bool((m.group(1) if m else "").split())


def inline_safe(html):
    """Kann die App diesen Text ohne Verlust bearbeiten? (nur bekannte Inline-Tags)"""
    return all(m.group(2).lower() in INLINE_OK for m in TAG_RE.finditer(html or ""))


def _x_tag(buf, open_tag):
    name = X_PREFIX + open_tag
    tag = buf.get_tag_table().lookup(name)
    if tag:
        return tag
    kind, val = x_kind(open_tag)
    dark = is_dark()
    if kind == "color":
        tag = buf.create_tag(name, foreground_rgba=_rgba(TC[val][1 if dark else 0]))
    elif kind == "bg":
        c = _rgba(BC[val])
        c.alpha = 0.30 if dark else 0.20
        tag = buf.create_tag(name, background_rgba=c)
    elif kind == "mention":
        tag = buf.create_tag(name, foreground_rgba=_rgba("#62b4e2" if dark else "#0b6893"),
                             background_rgba=_rgba("rgba(11,110,153,0.14)"), weight=Pango.Weight.MEDIUM, editable=False)
    elif kind == "math":
        tag = buf.create_tag(name, family="monospace", background_rgba=_rgba("rgba(105,64,165,0.16)"))
    elif kind == "code":
        tag = buf.create_tag(name, family="monospace", background_rgba=_rgba("rgba(127,127,127,0.22)"))
    elif kind == "marker":
        tag = buf.create_tag(name, background_rgba=_rgba("rgba(255,204,0,0.35)"))
    else:
        tag = buf.create_tag(name)
    return tag


def _x_order(name):
    """Reihenfolge beim Schreiben: span aussen (Farbe zuerst), dann mark, dann code (wie der Vertrag)"""
    t = name[len(X_PREFIX) + 1:].split(None, 1)[0].rstrip(">").lower()
    kind = x_kind(name[len(X_PREFIX):])[0]
    return ({"span": 0, "mark": 2, "code": 3}.get(t, 1), 0 if kind == "color" else 1, name)


def _setup_tags(buf):
    t = buf.get_tag_table()
    if t.lookup("b"):
        return
    buf.create_tag("b", weight=Pango.Weight.BOLD)
    buf.create_tag("i", style=Pango.Style.ITALIC)
    buf.create_tag("u", underline=Pango.Underline.SINGLE)
    buf.create_tag("s", strikethrough=True)
    buf.create_tag("mark", background_rgba=_rgba("rgba(255,204,0,0.35)"))
    buf.create_tag("code", family="monospace", background_rgba=_rgba("rgba(127,127,127,0.22)"))
    buf.create_tag("muted", foreground_rgba=_rgba("rgba(127,127,127,0.9)"), strikethrough=True)
    # Verweis auf eine Seite ohne Zugriff (Text vom Server ersetzt): gedaempft, kein Link - das href bleibt (a:-Tag),
    # damit der Server beim Speichern den Originaltext wieder einsetzen kann
    buf.create_tag(NA_TAG, foreground_rgba=_rgba("rgba(127,127,127,0.95)"), underline=Pango.Underline.NONE)
    # "🔒 " vor einem solchen Verweis: nur Anzeige (nicht bearbeitbar, beim Speichern weggelassen - href und Text bleiben)
    buf.create_tag(DECO_TAG, foreground_rgba=_rgba("rgba(127,127,127,0.95)"), editable=False)


NA_TAG = "hk-na"
DECO_TAG = "hk-deco"
NA_LOCK = "🔒" + chr(0x2009)  # Schloss + schmales Leerzeichen vor "Seite ohne Zugriff"


def _na_on_top(buf):
    """Das Tag "ohne Zugriff" muss ueber den (spaeter angelegten) Link-Tags liegen, sonst gewinnt deren Farbe"""
    tt = buf.get_tag_table()
    t = tt.lookup(NA_TAG)
    t.set_priority(tt.get_size() - 1)
    return t


def _rgba(s):
    c = Gdk.RGBA()
    c.parse(s)
    return c


def _link_tag(buf, kind, value):
    """a:<href> bzw. t:<datetime>|r - je Ziel ein eigenes Tag"""
    name = f"{kind}:{value}"
    tag = buf.get_tag_table().lookup(name)
    if tag:
        return tag
    if kind == "a":
        return buf.create_tag(name, foreground_rgba=_rgba("#3584e4"), underline=Pango.Underline.SINGLE)
    return buf.create_tag(name, foreground_rgba=_rgba("#3584e4"))


def html_to_buffer(buf, html, base_bold=False, start=None, names=None):
    """Inline-HTML an Position start (Standard: Ende) einfuegen. names: Kennung -> Anzeigename fuer Erwaehnungen (nur in
    nicht bearbeitbarer Anzeige - beim Bearbeiten bleibt der gespeicherte Rueckfall-Text, damit sich nichts aendert)"""
    _setup_tags(buf)
    counts = {k: 0 for k in FORMAT_TAGS}
    links = []
    xs = []                                            # offene Original-Tags (span/mark/code mit Klasse)
    open_by = {"span": [], "mark": [], "code": []}     # je Name: Stapel aus "fmt" (b/mark/code) bzw. x-Tag
    pos = 0
    it = start.copy() if start else buf.get_end_iter()
    mark = buf.create_mark(None, it, False)

    def emit(raw):
        if not raw:
            return
        tags = [buf.get_tag_table().lookup(k) for k, n in counts.items() if n > 0] + list(xs)
        text = decode_entities(raw)
        mention = next((t for t in xs if x_kind(t.props.name[len(X_PREFIX):])[0] == "mention"), None)
        if mention is not None:
            u = x_kind(mention.props.name[len(X_PREFIX):])[1]
            if names and names.get(u):
                text = names[u]                        # aktueller Name statt Rueckfall-Text (P6-2)
        if links and links[-1] is not None:
            tags.append(links[-1])
            if (links[-1].props.name or "").startswith("a:/notes#") and text.strip() in NA_TEXTS:
                tags.append(_na_on_top(buf))
                deco = buf.get_tag_table().lookup(DECO_TAG)
                deco.set_priority(buf.get_tag_table().get_size() - 1)
                buf.insert_with_tags(buf.get_iter_at_mark(mark), NA_LOCK, deco)
        buf.insert_with_tags(buf.get_iter_at_mark(mark), text, *tags)

    for m in TAG_RE.finditer(html or ""):
        emit(html[pos:m.start()])
        pos = m.end()
        closing, tag, attrs = m.group(1) == "/", m.group(2).lower(), m.group(3)
        d = -1 if closing else 1
        if tag in KEEP_TAGS:
            st = open_by[tag]
            if closing:
                if st:
                    top = st.pop()
                    if top == "fmt":
                        counts[tag] = max(0, counts[tag] - 1)
                    elif top in xs:
                        xs.remove(top)
            elif keep_raw(tag, attrs):
                xt = _x_tag(buf, m.group(0))
                xs.append(xt)
                st.append(xt)
            elif tag in counts:
                counts[tag] += 1
                st.append("fmt")
            else:
                st.append(None)                        # span ohne Klasse: faellt weg (wie der Server), Text bleibt
            continue
        if tag in ("b", "strong"):
            counts["b"] += d
        elif tag in ("i", "em"):
            counts["i"] += d
        elif tag == "u":
            counts["u"] += d
        elif tag in ("s", "strike", "del"):
            counts["s"] += d
        elif tag == "br" and not closing:
            buf.insert(buf.get_iter_at_mark(mark), "\n")
        elif tag == "time":
            if closing:
                if links:
                    links.pop()
            else:
                dm = re.search(r'datetime="([0-9T:\-]{10,16})"', attrs)
                links.append(_link_tag(buf, "t", dm.group(1) + ("|r" if "hk-remind" in attrs else "")) if dm else None)
        elif tag == "a":
            if closing:
                if links:
                    links.pop()
            else:
                hm = re.search(r'href="([^"]*)"', attrs)
                href = decode_entities(hm.group(1)) if hm else ""
                links.append(_link_tag(buf, "a", href) if link_target(href) else None)
        for k in counts:
            counts[k] = max(0, counts[k])
    emit((html or "")[pos:])
    buf.delete_mark(mark)


def buffer_to_html(buf, start=None, end=None, base_bold=False):
    start = start or buf.get_start_iter()
    end = end or buf.get_end_iter()
    out = []
    ids = {}                                           # Original-Tag -> Nummer (schliessende Tags je Original markieren)
    it = start.copy()
    while it.compare(end) < 0:
        nxt = it.copy()
        if not nxt.forward_to_tag_toggle(None) or nxt.compare(end) > 0:
            nxt = end.copy()
        text = buf.get_text(it, nxt, True)
        names = [t.props.name for t in it.get_tags() if t.props.name] if text else []
        if DECO_TAG in names:                          # nur Anzeige ("🔒 " vor "Seite ohne Zugriff"): nicht speichern
            text = ""
        if text:
            o, c = "", ""
            xn = sorted((n for n in names if n.startswith(X_PREFIX)), key=_x_order)
            spans = [n for n in xn if _x_order(n)[0] < 2]
            inner = [n for n in xn if _x_order(n)[0] >= 2]
            for n in spans:                            # span aussen (auch um Verweise), wie der Vertrag
                o += n[len(X_PREFIX):]
                c = "</span\x00%d>" % ids.setdefault(n, len(ids)) + c
            link = next((n for n in names if n.startswith(("a:", "t:"))), None)
            if link and link.startswith("t:"):
                v = link[2:]
                remind = v.endswith("|r")
                dt = v[:-2] if remind else v
                o += f'<time datetime="{esc(dt)}" class="{"hk-remind" if remind else "hk-date"}">'
                c = "</time>" + c
            elif link:
                o += f'<a href="{esc(link[2:]).replace(chr(34), "&quot;")}">'
                c = "</a>" + c
            for k in FORMAT_TAGS:
                if k in names and not (k == "b" and base_bold) and not (k == "u" and link):
                    o += f"<{k}>"
                    c = f"</{k}>" + c
            for n in inner:
                o += n[len(X_PREFIX):]
                c = "</" + n[len(X_PREFIX) + 1:].split(None, 1)[0].rstrip(">").lower() + "\x00%d>" % ids.setdefault(n, len(ids)) + c
            out.append(o + esc(text).replace("\n", "<br>") + c)
        it = nxt
    html = "".join(out)
    # gleiche Original-Tags in Folge zusammenfassen (Farbe ueber Fett -> ein span), Erwaehnungen bleiben einzeln
    for t, i in ids.items():
        if x_kind(t[len(X_PREFIX):])[0] == "mention":
            continue
        op = t[len(X_PREFIX):]
        tag = op[1:].split(None, 1)[0].rstrip(">").lower()
        html = html.replace(f"</{tag}\x00{i}>" + op, "")    # nur dasselbe Original (Farbe rot gefolgt von blau bleibt getrennt)
    html = re.sub("\x00\\d+", "", html)
    for t in FORMAT_TAGS:
        html = html.replace(f"</{t}><{t}>", "")
    return html


def _x_names(buf):
    names = []
    buf.get_tag_table().foreach(lambda t: names.append(t.props.name) if (t.props.name or "").startswith(X_PREFIX) else None)
    return names


def html_to_markup(html):
    """Inline-HTML -> Pango-Markup fuer Gtk.Label (Links als <a>, Seiten als hkpage:ID)"""
    out, pos = [], 0
    for m in TAG_RE.finditer(html or ""):
        out.append(GLib.markup_escape_text(decode_entities(html[pos:m.start()])))
        pos = m.end()
        closing, tag, attrs = m.group(1) == "/", m.group(2).lower(), m.group(3)
        simple = {"b": "b", "strong": "b", "i": "i", "em": "i", "u": "u", "s": "s", "strike": "s", "del": "s", "code": "tt"}
        if tag in KEEP_TAGS and (closing or keep_raw(tag, attrs) or tag == "span"):
            if closing:
                out.append("</span>" if tag == "span" else ("</span>" if tag == "mark" else "</tt>"))
                continue
            kind, val = x_kind(m.group(0)) if keep_raw(tag, attrs) else ("other", None)
            dark = is_dark()
            if kind == "color":
                out.append(f'<span foreground="{TC[val][1 if dark else 0]}">')
            elif kind == "bg":
                out.append(f'<span background="{BC[val]}{"4d" if dark else "33"}">')
            elif kind == "mention":
                out.append('<span weight="medium" foreground="%s">' % ("#62b4e2" if dark else "#0b6893"))
            elif tag == "code":
                out.append("<tt>")
            else:
                out.append("<span>")
            continue
        if tag in simple:
            out.append(f"</{simple[tag]}>" if closing else f"<{simple[tag]}>")
        elif tag == "mark":
            out.append("</span>" if closing else '<span background="#ffcc0059">')
        elif tag == "br" and not closing:
            out.append("\n")
        elif tag == "a":
            if closing:
                out.append("</a>")
            else:
                hm = re.search(r'href="([^"]*)"', attrs)
                href = decode_entities(hm.group(1)) if hm else ""
                t = link_target(href)
                tgt = f"hkpage:{t[1]}" if t and t[0] == "page" else (href if t else "#")
                out.append(f'<a href="{GLib.markup_escape_text(tgt)}">')
        elif tag == "time":
            out.append("</span>" if closing else '<span foreground="#3584e4">')
    out.append(GLib.markup_escape_text(decode_entities((html or "")[pos:])))
    s = "".join(out)
    try:
        Pango.parse_markup(s, -1, "\0")
        return s
    except GLib.Error:
        return GLib.markup_escape_text(re.sub(r"<[^>]+>", "", html or ""))


# --- Datum aus Text (wie parseHKDate) - versteht Deutsch, Englisch und Spanisch (Russisch/Thai: Grundwoerter) ---
_WD = ["montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag"]
_WDE = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
_WDES = ["lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo"]
_MON = ["januar", "februar", "maerz", "april", "mai", "juni", "juli", "august", "september", "oktober", "november", "dezember"]
_MONE = ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"]
_MONES = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"]


def _month(w):
    if len(w) < 3:
        return None
    for names in (_MON, _MONE, _MONES):
        mi = next((i for i, x in enumerate(names) if x.startswith(w)), None)
        if mi is not None:
            return mi
    return None


def _weekday(w):
    for names in (_WD, _WDE, _WDES):
        if w in names:
            return names.index(w)
    return None


# Russisch und Thai: nur Grundwoerter (heute/morgen/uebermorgen/gestern, Wochentage). Vergleich nach norm(),
# weil norm() kyrillische und Thai-Zeichen teils zerlegt (й -> и, Thai-Vokalzeichen).
def _nset(*words):
    return {norm(w) for w in words}


_TODAY_X = _nset("сегодня", "วันนี้")
_TOMORROW_X = _nset("завтра", "พรุ่งนี้")
_AFTER_X = _nset("послезавтра", "มะรืนนี้", "มะรืน")
_YESTERDAY_X = _nset("вчера", "เมื่อวาน", "เมื่อวานนี้")
_WDX = [_nset("понедельник", "จันทร์"), _nset("вторник", "อังคาร"), _nset("среда", "среду", "พุธ"),
        _nset("четверг", "พฤหัส", "พฤหัสบดี"), _nset("пятница", "пятницу", "ศุกร์"), _nset("суббота", "субботу", "เสาร์"),
        _nset("воскресенье", "อาทิตย์")]


def _weekday_x(q):
    """'в пятницу', 'в следующий вторник', 'วันศุกร์', 'วันศุกร์หน้า' -> 0..6 oder None"""
    w = re.sub(r"^(?:во?\s+)?(?:следующ\w*\s+)?", "", q)
    for pre in (norm("วัน"),):
        w = w[len(pre):] if w.startswith(pre) else w
    for suf in (norm("หน้า"),):
        w = w[:-len(suf)].strip() if w.endswith(suf) and len(w) > len(suf) else w
    return next((i for i, names in enumerate(_WDX) if w in names), None)


def parse_date(q0):
    q = norm(q0)
    t0 = _dt.date.today()
    time = None
    m = (re.search(r"(?:^|\s)(?:(?:um|at|a las|a la|в|" + norm("เวลา") + r")\s*)?(\d{1,2})[:.](\d{2})(?:\s*(?:uhr|h))?$", q)
         or re.search(r"(?:^|\s)(?:(?:um|at|a las|a la)\s+)?(\d{1,2})\s*uhr$", q))
    if m and (":" in q or q.endswith("uhr") or q.endswith("h")):
        hh = int(m.group(1))
        mm = int(m.group(2)) if m.lastindex and m.lastindex >= 2 else 0
        if hh > 23 or mm > 59:
            return None
        time = f"{hh:02d}:{mm:02d}"
        q = q[:m.start()].strip()
    elif (m := re.search(r"(?:^|\s)(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)$", q)):
        hh, mm = int(m.group(1)), int(m.group(2) or 0)
        if not 1 <= hh <= 12 or mm > 59:
            return None
        hh = hh % 12 + (12 if m.group(3) == "pm" else 0)
        time = f"{hh:02d}:{mm:02d}"
        q = q[:m.start()].strip()
    d = None
    try:
        if q in ("", "heute", "today", "hoy") or q in _TODAY_X:
            d = t0
        elif q in ("morgen", "tomorrow", "manana") or q in _TOMORROW_X:
            d = t0 + _dt.timedelta(days=1)
        elif q in ("uebermorgen", "day after tomorrow", "pasado manana") or q in _AFTER_X:
            d = t0 + _dt.timedelta(days=2)
        elif q in ("gestern", "yesterday", "ayer") or q in _YESTERDAY_X:
            d = t0 - _dt.timedelta(days=1)
        elif (wi := _weekday_x(q)) is not None:
            d = t0 + _dt.timedelta(days=(wi - t0.weekday() + 7) % 7 or 7)
        elif (m := re.fullmatch(r"(?:in|en) (\d{1,3}) (tag|tage|tagen|woche|wochen|monat|monate|monaten|day|days|week|weeks|month|months"
                                r"|dia|dias|semana|semanas|mes|meses)", q)):
            n = int(m.group(1))
            unit = m.group(2)
            if unit.startswith(("w", "s")):
                d = t0 + _dt.timedelta(days=7 * n)
            elif unit.startswith("mo") or unit.startswith("mes"):
                mo = t0.month - 1 + n
                d = t0.replace(year=t0.year + mo // 12, month=mo % 12 + 1, day=min(t0.day, 28))
            else:
                d = t0 + _dt.timedelta(days=n)
        elif q in ("naechste woche", "next week", "proxima semana", "la proxima semana", "semana que viene", "la semana que viene"):
            d = t0 + _dt.timedelta(days=7)
        elif (m := re.fullmatch(r"(?:(?:naechsten?|kommenden?|next|el proximo|proximo|el)\s+)?([a-z]+)(?:\s+(?:que viene|proximo))?", q)):
            wi = _weekday(m.group(1))
            if wi is None:
                return None
            diff = (wi - t0.weekday() + 7) % 7 or 7
            d = t0 + _dt.timedelta(days=diff)
        elif (m := re.fullmatch(r"(\d{4})-(\d{2})-(\d{2})", q)):
            d = _dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        elif (m := re.fullmatch(r"(\d{1,2})\.(\d{1,2})\.(\d{2,4})?", q)):
            d = _dt.date(_year(m.group(3), t0), int(m.group(2)), int(m.group(1)))
        elif (m := re.fullmatch(r"(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?", q)):
            a, b = int(m.group(1)), int(m.group(2))
            if _("%m/%d/%Y").startswith("%m"):                 # Schreibweise der Sprache: Monat zuerst (en) oder Tag zuerst
                a, b = b, a
            d = _dt.date(_year(m.group(3), t0), b, a)
        elif (m := re.fullmatch(r"(\d{1,2})\.? (?:de )?([a-z]+)\.?(?: (?:de )?(\d{4}))?", q)):
            mi = _month(m.group(2))
            if mi is None:
                return None
            d = _dt.date(int(m.group(3)) if m.group(3) else t0.year, mi + 1, int(m.group(1)))
        elif (m := re.fullmatch(r"([a-z]+)\.? (\d{1,2})(?:st|nd|rd|th)?,?(?: (\d{4}))?", q)):
            mi = _month(m.group(1))
            if mi is None:
                return None
            d = _dt.date(int(m.group(3)) if m.group(3) else t0.year, mi + 1, int(m.group(2)))
        else:
            return None
    except ValueError:
        return None
    return (d.isoformat(), time)


def _year(s, t0):
    if not s:
        return t0.year
    y = int(s)
    return 2000 + y if y < 100 else y


def date_text(d):
    day = _dt.date.fromisoformat(d[0])
    s = weekday_day_text(day, year=day.year != _dt.date.today().year)
    if d[1]:
        hh, mm = (int(x) for x in d[1].split(":")[:2])
        s += ", " + time_text(_dt.datetime(day.year, day.month, day.day, hh, mm))
    return s


def rel_text(d):
    diff = (_dt.date.fromisoformat(d[0]) - _dt.date.today()).days
    r = {0: _("Today"), 1: _("Tomorrow"), 2: _("Day after tomorrow"), -1: _("Yesterday")}.get(diff)
    return (r + " · " if r else "") + date_text(d)


def find_trigger(text):
    """'@...' bzw. ':emoji' am Ende des Textes vor dem Cursor -> ('at'|'emoji', query)"""
    m = re.search(r"(?:^|[\s(])@([^\s@]{0,20}(?: [^\s@]{1,15}){0,2})$", text)
    if m:
        return ("at", m.group(1))
    m = re.search(r"(?:^|[\s(]):([a-zA-ZäöüÄÖÜß0-9_+-]{2,20})$", text)
    if m:
        return ("emoji", m.group(1))
    return None


class RichText(Gtk.TextView):
    """Ein formatiertes Textfeld fuer einen Block. Der Editor haengt sich ueber Rueckrufe an."""
    __gtype_name__ = "HKRichText"
    __gsignals__ = {"html-changed": (GObject.SignalFlags.RUN_FIRST, None, ())}

    def __init__(self, html="", css=None, placeholder="", base_bold=False, raw=False, split=True, editable=True):
        super().__init__()
        self.base_bold, self.raw, self.split = base_bold, raw, split
        self.placeholder_text = placeholder
        self.on_key = None             # (rt, keyval, state) -> bool   Editor: Return, Backspace, Pfeile, Tab
        self.on_link = None            # (kind, value) -> None
        self.loading = True
        self.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        self.set_accepts_tab(False)
        self.set_editable(editable)
        self.set_cursor_visible(editable)
        self.add_css_class("hk-rt")
        if css:
            for c in css.split():
                self.add_css_class(c)
        if raw:
            self.set_monospace(True)
        buf = self.get_buffer()
        _setup_tags(buf)
        if raw:
            buf.set_text(html or "")
        else:
            html_to_buffer(buf, html, base_bold, names=None if editable else MENTION_NAMES)
        buf.set_enable_undo(False)
        buf.set_enable_undo(True)
        self._ph = Gtk.Label(label=placeholder, xalign=0)
        self._ph.add_css_class("hk-placeholder")
        self._ph.set_can_target(False)
        self.add_overlay(self._ph, 0, 0)
        self._ph_always = True
        buf.connect("changed", self._changed)
        self.loading = False
        self._update_ph()
        fc = Gtk.EventControllerFocus()
        fc.connect("enter", lambda *_: self._update_ph())
        fc.connect("leave", lambda *_: self._update_ph())
        self.add_controller(fc)
        kc = Gtk.EventControllerKey()
        kc.set_propagation_phase(Gtk.PropagationPhase.CAPTURE)
        kc.connect("key-pressed", self._key)
        self.add_controller(kc)
        cl = Gtk.GestureClick()
        cl.connect("released", self._click)
        self.add_controller(cl)
        mo = Gtk.EventControllerMotion()
        mo.connect("motion", self._motion)
        self.add_controller(mo)

    # --- Inhalt ------------------------------------------------------------------------------
    def html(self, start=None, end=None):
        buf = self.get_buffer()
        if self.raw:
            return buf.get_text(start or buf.get_start_iter(), end or buf.get_end_iter(), True)
        return buffer_to_html(buf, start, end, self.base_bold)

    def text(self):
        buf = self.get_buffer()
        return buf.get_text(buf.get_start_iter(), buf.get_end_iter(), True)

    def set_html(self, html):
        self.loading = True
        buf = self.get_buffer()
        buf.set_text("")
        if self.raw:
            buf.set_text(html or "")
        else:
            html_to_buffer(buf, html, self.base_bold, names=None if self.get_editable() else MENTION_NAMES)
        self.loading = False
        self._update_ph()

    def split_at_cursor(self):
        """-> (html vorher, html nachher)"""
        buf = self.get_buffer()
        cur = buf.get_iter_at_mark(buf.get_insert())
        return self.html(buf.get_start_iter(), cur), self.html(cur, buf.get_end_iter())

    def cursor_offset(self):
        buf = self.get_buffer()
        return buf.get_iter_at_mark(buf.get_insert()).get_offset()

    def text_before_cursor(self):
        buf = self.get_buffer()
        cur = buf.get_iter_at_mark(buf.get_insert())
        return buf.get_text(buf.get_start_iter(), cur, True)

    def focus_at(self, where="end"):
        buf = self.get_buffer()
        if where == "start":
            it = buf.get_start_iter()
        elif isinstance(where, int):
            it = buf.get_iter_at_offset(min(where, buf.get_char_count()))
        else:
            it = buf.get_end_iter()
        buf.place_cursor(it)
        self.grab_focus()

    def set_muted(self, on):
        buf = self.get_buffer()
        buf.remove_tag_by_name("muted", buf.get_start_iter(), buf.get_end_iter())
        if on:
            buf.apply_tag_by_name("muted", buf.get_start_iter(), buf.get_end_iter())

    def set_placeholder(self, text, always=True):
        self.placeholder_text = text
        self._ph_always = always
        self._ph.set_label(text)
        self._update_ph()

    def _update_ph(self):
        empty = self.get_buffer().get_char_count() == 0
        self._ph.set_visible(empty and (self._ph_always or self.has_focus()))

    def _changed(self, buf):
        self._update_ph()
        if not self.loading:
            self.emit("html-changed")

    # --- Formatieren -------------------------------------------------------------------------
    def toggle_format(self, tag):
        buf = self.get_buffer()
        b = buf.get_selection_bounds()
        if not b:
            return
        s, e = b
        t = buf.get_tag_table().lookup(tag)
        it, all_on = s.copy(), True
        while it.compare(e) < 0:
            if not it.has_tag(t):
                all_on = False
                break
            it.forward_char()
        if all_on:
            buf.remove_tag(t, s, e)
        else:
            buf.apply_tag(t, s, e)
            if tag == "code":
                buf.remove_tag_by_name("mark", s, e)
        self.emit("html-changed")

    # --- 2.5 (P6-8): Inline-Formen setzen ----------------------------------------------------------------------------
    def _x_tags_in(self, s, e, kinds):
        out = []
        for n in _x_names(self.get_buffer()):
            if x_kind(n[len(X_PREFIX):])[0] in kinds:
                out.append(n)
        return out

    def set_inline_color(self, kind, color):
        """Text- (kind 'color') bzw. Hintergrundfarbe ('bg') der Auswahl setzen (Palette) oder entfernen (color None).
        Gespeichert als span.hk-c-* aussen / mark.hk-bg-* innen (buffer_to_html ordnet). -> True, wenn etwas geaendert"""
        buf = self.get_buffer()
        b = buf.get_selection_bounds()
        if not b or self.raw:
            return False
        s, e = b
        pal = TC if kind == "color" else BC
        if color is not None and color not in pal:
            return False
        kinds = ("color",) if kind == "color" else ("bg", "marker")
        for n in self._x_tags_in(s, e, kinds):
            buf.remove_tag_by_name(n, s, e)
        if kind == "bg":
            buf.remove_tag_by_name("mark", s, e)             # einfache Markierung (Gelb) wird durch die Farbe ersetzt
        if color is not None:
            tag = f'<span class="hk-c-{color}">' if kind == "color" else f'<mark class="hk-bg-{color}">'
            buf.apply_tag(_x_tag(buf, tag), s, e)
        self.emit("html-changed")
        return True

    def inline_colors(self):
        """-> (textfarbe|None, hintergrund|None) am Anfang der Auswahl bzw. an der Schreibmarke"""
        buf = self.get_buffer()
        b = buf.get_selection_bounds()
        it = b[0] if b else buf.get_iter_at_mark(buf.get_insert())
        fg = bgc = None
        for t in it.get_tags():
            n = t.props.name or ""
            if n.startswith(X_PREFIX):
                k, v = x_kind(n[len(X_PREFIX):])
                if k == "color":
                    fg = v
                elif k == "bg":
                    bgc = v
        return fg, bgc

    def math_range(self, it=None):
        """Inline-Mathe an der Schreibmarke: -> (start, ende) oder None"""
        buf = self.get_buffer()
        it = it or buf.get_iter_at_mark(buf.get_insert())
        tag = next((t for t in it.get_tags() if x_kind((t.props.name or "")[len(X_PREFIX):])[0] == "math"
                    and (t.props.name or "").startswith(X_PREFIX)), None)
        if tag is None:
            back = it.copy()
            if back.backward_char():
                tag = next((t for t in back.get_tags() if (t.props.name or "").startswith(X_PREFIX)
                            and x_kind(t.props.name[len(X_PREFIX):])[0] == "math"), None)
                it = back
        if tag is None:
            return None
        s, e = it.copy(), it.copy()
        if not s.starts_tag(tag):
            s.backward_to_tag_toggle(tag)
        e.forward_to_tag_toggle(tag)
        return s, e

    def set_math(self, tex, rng=None):
        """Inline-Mathe einfuegen (an der Auswahl bzw. Schreibmarke) oder ersetzen (rng = math_range()); leer = entfernen.
        Gespeichert als code.hk-math mit dem TeX-Quelltext als Text."""
        if self.raw:
            return False
        buf = self.get_buffer()
        tex = (tex or "").replace("\n", " ").strip()
        buf.begin_user_action()
        if rng is not None:
            s, e = rng
            m = buf.create_mark(None, s, True)
            buf.delete(s, e)
            at = buf.get_iter_at_mark(m)
            buf.delete_mark(m)
        else:
            b = buf.get_selection_bounds()
            if b:
                buf.delete(*b)
            at = buf.get_iter_at_mark(buf.get_insert())
        if tex:
            buf.insert_with_tags(at, tex, _x_tag(buf, '<code class="hk-math">'))
        buf.end_user_action()
        self.emit("html-changed")
        return True

    def insert_mention(self, ref, name, n_chars=0):
        """Erwaehnung einfuegen: span.hk-mention (data-u = Kennung, Text = Name) + Leerzeichen; n_chars: Ausloeser (@...)
        davor ersetzen. Der Chip ist eine Einheit (nicht bearbeitbar)."""
        if self.raw or not ref:
            return False
        buf = self.get_buffer()
        cur = buf.get_iter_at_mark(buf.get_insert())
        st = cur.copy()
        st.backward_chars(n_chars)
        buf.begin_user_action()
        buf.delete(st, cur)
        cur = buf.get_iter_at_mark(buf.get_insert())
        tag = _x_tag(buf, '<span class="hk-mention" data-u="%s">' % esc(ref).replace('"', "&quot;"))
        buf.insert_with_tags(cur, name or ref, tag)
        cur = buf.get_iter_at_mark(buf.get_insert())
        buf.insert(cur, " ")
        buf.end_user_action()
        return True

    def set_link(self, href):
        buf = self.get_buffer()
        b = buf.get_selection_bounds()
        if not b:
            return
        s, e = b
        self._remove_links(s, e)
        if href:
            buf.apply_tag(_link_tag(buf, "a", href), s, e)
        self.emit("html-changed")

    def _remove_links(self, s, e):
        buf = self.get_buffer()
        names = []
        buf.get_tag_table().foreach(lambda t: names.append(t.props.name) if (t.props.name or "").startswith(("a:", "t:")) else None)
        for n in names:
            buf.remove_tag_by_name(n, s, e)

    def replace_before_cursor(self, n_chars, text, link=None):
        """Ausloeser (@... / :...) durch Auswahl ersetzen (+ Leerzeichen)"""
        buf = self.get_buffer()
        cur = buf.get_iter_at_mark(buf.get_insert())
        st = cur.copy()
        st.backward_chars(n_chars)
        buf.begin_user_action()
        buf.delete(st, cur)
        cur = buf.get_iter_at_mark(buf.get_insert())
        if link:
            buf.insert_with_tags(cur, text, _link_tag(buf, link[0], link[1]))
        else:
            buf.insert(cur, text)
        cur = buf.get_iter_at_mark(buf.get_insert())
        buf.insert(cur, " ")
        buf.end_user_action()

    def link_at(self, it):
        """("a", href) | ("t", datum) | ("na", href) fuer einen Verweis auf eine Seite ohne Zugriff | None"""
        names = [t.props.name or "" for t in it.get_tags()]
        for n in names:
            if n.startswith("a:"):
                return ("na" if NA_TAG in names else "a", n[2:])
            if n.startswith("t:"):
                return ("t", n[2:])
        return None

    def cursor_rect(self):
        buf = self.get_buffer()
        r = self.get_iter_location(buf.get_iter_at_mark(buf.get_insert()))
        x, y = self.buffer_to_window_coords(Gtk.TextWindowType.WIDGET, r.x, r.y)
        rect = Gdk.Rectangle()
        rect.x, rect.y, rect.width, rect.height = x, y, max(r.width, 1), r.height
        return rect

    def at_first_line(self):
        """Cursor in der ersten (umbrochenen) Zeile? -> Pfeil hoch geht zum Block darueber"""
        buf = self.get_buffer()
        cur = self.get_iter_location(buf.get_iter_at_mark(buf.get_insert()))
        return cur.y <= self.get_iter_location(buf.get_start_iter()).y

    def at_last_line(self):
        buf = self.get_buffer()
        cur = self.get_iter_location(buf.get_iter_at_mark(buf.get_insert()))
        return cur.y >= self.get_iter_location(buf.get_end_iter()).y

    # --- Ereignisse --------------------------------------------------------------------------
    def _key(self, ctrl, keyval, keycode, state):
        if self.on_key:
            return bool(self.on_key(self, keyval, state))
        return False

    def _iter_at(self, x, y):
        bx, by = self.window_to_buffer_coords(Gtk.TextWindowType.WIDGET, int(x), int(y))
        ok, it = self.get_iter_at_location(bx, by)
        return it if ok else None

    def _click(self, g, n, x, y):
        if n != 1 or self.get_buffer().get_has_selection():
            return
        it = self._iter_at(x, y)
        lk = self.link_at(it) if it else None
        if lk and lk[0] in ("a", "na") and self.on_link:
            self.on_link(lk[0], lk[1])

    def _motion(self, c, x, y):
        it = self._iter_at(x, y)
        lk = self.link_at(it) if it else None
        self.set_cursor_from_name("pointer" if lk and lk[0] == "a" else "text")
