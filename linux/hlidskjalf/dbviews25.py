"""Datenbank-Ansichten Saga 2.5: Diagramm (type "chart", Cairo, Werte vom Server) und Zeitleiste (type "timeline",
nur lesen: waagrecht scrollbar, schmal als Liste nach Start). Logik der Zeitleiste ohne GTK: timeline_items/scale."""
import datetime as _dt
import json

from gi.repository import Gtk, Pango

from . import chart as C
from . import dbvalues as dv
from .api import client
from .i18n import _
from .store import store
from .util import bg, day_text, month_text


# --- Diagramm -------------------------------------------------------------------------------------------------------
def chart_path(db_id, view, local=False):
    from urllib.parse import quote
    from .push import local_tz
    p = f"api/notes/{db_id}/chart?view={quote(str((view or {}).get('id') or ''), safe='')}&tz={quote(local_tz(), safe='')}"
    if local:                                             # eingebettete Datenbank mit eigenen Block-Ansichten
        spec = (view or {}).get("chart")
        if isinstance(spec, dict):
            p += "&chart=" + quote(json.dumps(spec, separators=(",", ":")), safe="")
        p += "&filters=" + quote(json.dumps((view or {}).get("filters") or [], separators=(",", ":")), safe="")
    return p


class ChartCanvas(Gtk.DrawingArea):
    """Zeichnet ein Chart-Ergebnis (chart.parse) mit Cairo"""
    __gtype_name__ = "HKChartCanvas"

    def __init__(self, ch=None, height=260):
        super().__init__()
        self.ch = ch
        self.set_content_height(height)
        self.set_hexpand(True)
        self.add_css_class("hk-chart")
        self.set_draw_func(self._draw)
        self.update_property([Gtk.AccessibleProperty.LABEL], [_("Chart")])

    def set_chart(self, ch):
        self.ch = ch
        self.queue_draw()

    def _draw(self, area, cr, w, h):
        col = area.get_color()
        from .richtext import is_dark
        C.draw(cr, w, h, self.ch, dark=is_dark(), fg=(col.red, col.green, col.blue))


def values_table(ch):
    """Werte zusaetzlich als Tabelle (Barrierefreiheit), aufklappbar"""
    ex = Gtk.Expander(label=_("Values as table"))
    grid = Gtk.Grid(column_spacing=16, row_spacing=2, margin_top=4)
    names = [s["label"] or _("Value") for s in ch["series"]] if len(ch["series"]) > 1 else [_("Value")]
    grid.attach(Gtk.Label(label="", xalign=0), 0, 0, 1, 1)
    for j, n in enumerate(names):
        lb = Gtk.Label(label=n, xalign=1)
        lb.add_css_class("dim-label")
        grid.attach(lb, j + 1, 0, 1, 1)
    for i, (lab, vals) in enumerate(C.table_rows(ch)):
        grid.attach(Gtk.Label(label=lab, xalign=0, ellipsize=Pango.EllipsizeMode.END, max_width_chars=30), 0, i + 1, 1, 1)
        for j, v in enumerate(vals[:len(names)]):
            lb = Gtk.Label(label=v, xalign=1)
            lb.add_css_class("numeric")
            grid.attach(lb, j + 1, i + 1, 1, 1)
    ex.set_child(grid)
    return ex


class ChartView(Gtk.Box):
    """Datenbank-Ansicht "chart": GET .../chart?view=<vid>; aeltere Server ohne chart1: Tabelle (macht DatabaseView)"""
    __gtype_name__ = "HKChartView"

    def __init__(self, win, m, view):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.win, self.m, self.view = win, m, view
        self.canvas = ChartCanvas()
        self.msg = Gtk.Label(xalign=0, wrap=True)
        self.msg.add_css_class("dim-label")
        self.append(self.msg)
        self.append(self.canvas)
        self.table = None
        self.ch = None
        self.err = None
        self.msg.set_label(_("Loading …"))
        self.load()

    def load(self):
        path = chart_path(self.m.db_id, self.view, local=self.m.local_views is not None)

        def fin(j):
            ch, err = C.parse_response(j)
            self.show(ch, err)

        def err(e):
            self.show(None, "failed")
        bg(client.get, path, done=fin, error=err)

    def show(self, ch, err):
        self.ch, self.err = ch, err
        self.canvas.set_chart(ch)
        self.canvas.set_visible(ch is not None)
        if err:
            self.msg.set_label(C.error_text(err))
        elif ch is not None and ch["truncated"]:
            self.msg.set_label(_("Showing the largest groups – the rest is under “Other”."))
        else:
            self.msg.set_label("")
        self.msg.set_visible(bool(self.msg.get_label()))
        if self.table is not None:
            self.remove(self.table)
            self.table = None
        if ch is not None and ch["cats"]:
            self.table = values_table(ch)
            self.append(self.table)


# --- Zeitleiste -----------------------------------------------------------------------------------------------------
SCALE_DAYS = {"day": 1, "week": 7, "month": 30, "quarter": 91}
PX_PER_DAY = {"day": 48, "week": 18, "month": 6, "quarter": 2.4}


def timeline_items(m, view, rows):
    """-> ([(zeile, start, ende)], ohne_termin) - Start aus date_by, Ende aus end_by (oder Zeitraum des Starts)"""
    sp = m.prop((view or {}).get("date_by")) or next((p for p in m.props if p.get("type") == "date"), None)
    ep = m.prop((view or {}).get("end_by"))
    items, none = [], []
    for r in rows:
        v = m.value(sp, r) if sp else None
        span = dv.day_span(v) if isinstance(v, str) else None
        if span is None:
            none.append(r)
            continue
        a, b = span
        if ep is not None:
            e = dv.day_span(m.value(ep, r)) if isinstance(m.value(ep, r), str) else None
            if e is not None and e[1] >= a:
                b = e[1]
        items.append((r, a, b))
    items.sort(key=lambda x: (x[1], x[2]))
    return items, none


def timeline_range(items, today=None):
    today = today or _dt.date.today()
    if not items:
        return today - _dt.timedelta(days=7), today + _dt.timedelta(days=21)
    a = min(min(x[1] for x in items), today) - _dt.timedelta(days=3)
    b = max(max(x[2] for x in items), today) + _dt.timedelta(days=3)
    return a, b


def group_by_start(items):
    """Telefon/schmal: Liste nach Start-Tag gruppiert -> [(tag, [(zeile, start, ende)])]"""
    out = []
    for it in items:
        if out and out[-1][0] == it[1]:
            out[-1][1].append(it)
        else:
            out.append((it[1], [it]))
    return out


class TimelineView(Gtk.Box):
    """Zeitleiste nur lesend: Balken je Eintrag (Klick oeffnet), Heute-Linie, Zeilen ohne Datum unten"""
    __gtype_name__ = "HKTimelineView"

    def __init__(self, win, m, view, narrow=False):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.win, self.m, self.view = win, m, view
        rows = m.rows_for(view)
        self.items, self.none = timeline_items(m, view, rows)
        self.scale = (view or {}).get("scale") if (view or {}).get("scale") in SCALE_DAYS else "week"
        if narrow:
            self._list()
        else:
            self._bars()
        if self.none:
            ex = Gtk.Expander(label=_("Without date ({n})").format(n=len(self.none)))
            bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
            for r in self.none[:200]:
                bx.append(self._btn(r, None))
            ex.set_child(bx)
            self.append(ex)

    def _open(self, r):
        self.win.open_entry(r["id"], (self.view or {}).get("open") or "side", [x[0]["id"] for x in self.items])

    def _btn(self, r, sub):
        bx = Gtk.Box(spacing=6)
        if r.get("blocked") is True:
            from .dbcells import blocked_mark
            bx.append(blocked_mark())
        bx.append(Gtk.Label(label=r["title"] or _("Untitled"), xalign=0, ellipsize=Pango.EllipsizeMode.END, hexpand=True))
        if sub:
            s = Gtk.Label(label=sub)
            s.add_css_class("caption")
            s.add_css_class("dim-label")
            bx.append(s)
        b = Gtk.Button(child=bx)
        b.add_css_class("flat")
        b.connect("clicked", lambda *_: self._open(r))
        return b

    def _list(self):
        for day, its in group_by_start(self.items):
            h = Gtk.Label(label=day_text(day.isoformat()), xalign=0)
            h.add_css_class("heading")
            self.append(h)
            for r, a, b in its:
                self.append(self._btn(r, None if a == b else "→ " + day_text(b.isoformat())))
        if not self.items:
            self.append(Gtk.Label(label=_("No entries with a date"), xalign=0, css_classes=["dim-label"]))

    def _bars(self):
        a, b = timeline_range(self.items)
        days = (b - a).days + 1
        ppd = max(PX_PER_DAY[self.scale], 760 / days)          # kurze Zeitraeume fuellen die Breite
        width = int(days * ppd) + 20
        fx = Gtk.Fixed()
        row_h = 30
        head = 36
        fx.set_size_request(width, head + row_h * max(1, len(self.items)) + 10)
        # Kopf: Monate bzw. Tage
        d = a
        while d <= b:
            x = int((d - a).days * ppd)
            mark = (self.scale == "day" or (self.scale == "week" and d.weekday() == 0)
                    or (self.scale in ("month", "quarter") and d.day == 1))
            if mark:
                txt = str(d.day) if self.scale == "day" and d.day != 1 else (
                    month_text(d) if self.scale in ("month", "quarter") else day_text(d.isoformat()))
                lb = Gtk.Label(label=txt)
                lb.add_css_class("caption")
                lb.add_css_class("dim-label")
                fx.put(lb, x + 2, 6)
                sep = Gtk.Separator(orientation=Gtk.Orientation.VERTICAL)
                sep.set_size_request(1, head + row_h * max(1, len(self.items)))
                sep.set_opacity(0.35)
                fx.put(sep, x, 0)
            d += _dt.timedelta(days=1)
        t = _dt.date.today()
        if a <= t <= b:
            now = Gtk.Box()
            now.add_css_class("hk-tl-today")
            now.set_size_request(2, head + row_h * max(1, len(self.items)))
            fx.put(now, int((t - a).days * ppd + ppd / 2), 0)
        for i, (r, s, e) in enumerate(self.items):
            x = int((s - a).days * ppd)
            w = max(int(((e - s).days + 1) * ppd), 24)
            lb = Gtk.Label(label=r["title"] or _("Untitled"), xalign=0, ellipsize=Pango.EllipsizeMode.END)
            bt = Gtk.Button(child=lb)
            bt.add_css_class("hk-timeline-bar")
            col = self.m.color(r, self.view)
            bt.add_css_class(f"hk-card-{col}" if col else "hk-tl-default")
            bt.set_size_request(max(w, 90), row_h - 6)
            bt.set_tooltip_text(f"{r['title'] or _('Untitled')} · {day_text(s.isoformat())}" + ("" if s == e else f" – {day_text(e.isoformat())}"))
            bt.connect("clicked", lambda *_, r=r: self._open(r))
            fx.put(bt, x, head + i * row_h)
        sw = Gtk.ScrolledWindow(vscrollbar_policy=Gtk.PolicyType.NEVER, hscrollbar_policy=Gtk.PolicyType.AUTOMATIC,
                                propagate_natural_height=True)
        sw.set_child(fx)
        self.append(sw)
        if t >= a:                                              # zu heute scrollen
            from gi.repository import GLib

            def go():
                adj = sw.get_hadjustment()
                adj.set_value(max(0, (t - a).days * ppd - 80))
                return False
            GLib.idle_add(go)
        if not self.items:
            self.append(Gtk.Label(label=_("No entries with a date"), xalign=0, css_classes=["dim-label"]))


def store_has_chart():
    return store.has("chart1")
