"""Formatierter Text: gespeichertes Inline-HTML <-> Gtk.TextBuffer mit Tags (wie RichText.swift / EditText)."""
import datetime as _dt
import re

from gi.repository import Gdk, GLib, GObject, Gtk, Pango

from .i18n import _
from .util import TAG_RE, decode_entities, esc, link_target, norm, time_text, weekday_day_text

FORMAT_TAGS = ("b", "i", "u", "s", "mark", "code")


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


def html_to_buffer(buf, html, base_bold=False, start=None):
    """Inline-HTML an Position start (Standard: Ende) einfuegen"""
    _setup_tags(buf)
    counts = {k: 0 for k in FORMAT_TAGS}
    links = []
    pos = 0
    it = start.copy() if start else buf.get_end_iter()
    mark = buf.create_mark(None, it, False)

    def emit(raw):
        if not raw:
            return
        tags = [buf.get_tag_table().lookup(k) for k, n in counts.items() if n > 0]
        if links and links[-1] is not None:
            tags.append(links[-1])
        buf.insert_with_tags(buf.get_iter_at_mark(mark), decode_entities(raw), *tags)

    for m in TAG_RE.finditer(html or ""):
        emit(html[pos:m.start()])
        pos = m.end()
        closing, tag, attrs = m.group(1) == "/", m.group(2).lower(), m.group(3)
        d = -1 if closing else 1
        if tag in ("b", "strong"):
            counts["b"] += d
        elif tag in ("i", "em"):
            counts["i"] += d
        elif tag == "u":
            counts["u"] += d
        elif tag in ("s", "strike", "del"):
            counts["s"] += d
        elif tag in ("mark", "code"):
            counts[tag] += d
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
    it = start.copy()
    while it.compare(end) < 0:
        nxt = it.copy()
        if not nxt.forward_to_tag_toggle(None) or nxt.compare(end) > 0:
            nxt = end.copy()
        text = buf.get_text(it, nxt, True)
        if text:
            names = [t.props.name for t in it.get_tags() if t.props.name]
            o, c = "", ""
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
            out.append(o + esc(text).replace("\n", "<br>") + c)
        it = nxt
    html = "".join(out)
    for t in FORMAT_TAGS:
        html = html.replace(f"</{t}><{t}>", "")
    return html


def html_to_markup(html):
    """Inline-HTML -> Pango-Markup fuer Gtk.Label (Links als <a>, Seiten als hkpage:ID)"""
    out, pos = [], 0
    for m in TAG_RE.finditer(html or ""):
        out.append(GLib.markup_escape_text(decode_entities(html[pos:m.start()])))
        pos = m.end()
        closing, tag, attrs = m.group(1) == "/", m.group(2).lower(), m.group(3)
        simple = {"b": "b", "strong": "b", "i": "i", "em": "i", "u": "u", "s": "s", "strike": "s", "del": "s", "code": "tt"}
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


# --- Datum aus Text (wie parseHKDate) - versteht Deutsch, Englisch und Spanisch -----------------------
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


def parse_date(q0):
    q = norm(q0)
    t0 = _dt.date.today()
    time = None
    m = (re.search(r"(?:^|\s)(?:(?:um|at|a las|a la)\s+)?(\d{1,2})[:.](\d{2})(?:\s*(?:uhr|h))?$", q)
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
        if q in ("", "heute", "today", "hoy"):
            d = t0
        elif q in ("morgen", "tomorrow", "manana"):
            d = t0 + _dt.timedelta(days=1)
        elif q in ("uebermorgen", "day after tomorrow", "pasado manana"):
            d = t0 + _dt.timedelta(days=2)
        elif q in ("gestern", "yesterday", "ayer"):
            d = t0 - _dt.timedelta(days=1)
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
            html_to_buffer(buf, html, base_bold)
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
            html_to_buffer(buf, html, self.base_bold)
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
        for t in it.get_tags():
            n = t.props.name or ""
            if n.startswith("a:"):
                return ("a", n[2:])
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
        if lk and lk[0] == "a" and self.on_link:
            self.on_link(lk[0], lk[1])

    def _motion(self, c, x, y):
        it = self._iter_at(x, y)
        lk = self.link_at(it) if it else None
        self.set_cursor_from_name("pointer" if lk and lk[0] == "a" else "text")
