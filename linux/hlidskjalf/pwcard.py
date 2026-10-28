"""Widgets in Seiten (2.5): Karte fuer einen Block "widget" (BlockKit.build). Logik in pagewidgets.py.
Uhr und Countdown ticken lokal per GLib-Timer, nur solange die Karte sichtbar ist; Server-Werte kommen aus
PageView.wvalues (GET api/notes/{nid}/widgets) und werden per redraw() eingesetzt."""
import math
import random
import weakref

from gi.repository import GLib, Gtk, Pango

from . import pagewidgets as pw
from .i18n import _

ANALOG_PX = {"s": 64, "m": 96, "l": 132}


def register(view, card):
    s = getattr(view, "wcards", None)
    if s is None:
        s = view.wcards = weakref.WeakSet()
    s.add(card)


def redraw_all(view):
    for c in list(getattr(view, "wcards", None) or ()):
        if c.get_root() is not None:
            c.redraw()


class WidgetCard(Gtk.Box):
    __gtype_name__ = "HKWidgetCard"

    def __init__(self, kit, b):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        self.kit, self.b, self.view = kit, b, kit.view
        self.spec = pw.Spec(b)
        self._timer = None
        self._seed = random.random()                         # quote "open": je Oeffnen zufaellig, beim Neuzeichnen stabil
        self.add_css_class("hk-widget")
        self.add_css_class("hk-widget-" + self.spec.size)
        if self.spec.style == "card":
            self.add_css_class("hk-card-box")
            self.add_css_class("hk-widget-card")
        else:
            self.add_css_class("hk-widget-plain")
        if self.spec.accent:
            self.add_css_class("hk-wacc-" + self.spec.accent)
        if not self.spec.known:
            self.add_css_class("hk-widget-newer")
        self.title_lb = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.title_lb.add_css_class("hk-w-title")
        self.append(self.title_lb)
        row = Gtk.Box(spacing=14)
        self.analog = None
        if self.spec.kind == "clock" and pw.clock_cfg(self.spec.cfg)["analog"]:
            px = ANALOG_PX[self.spec.size]
            self.analog = Gtk.DrawingArea(content_width=px, content_height=px, valign=Gtk.Align.CENTER)
            self.analog.add_css_class("hk-w-analog")
            self.analog.set_draw_func(self._draw_analog)
            row.append(self.analog)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True, valign=Gtk.Align.CENTER)
        self.main_lb = Gtk.Label(xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, selectable=False)
        self.main_lb.add_css_class("hk-w-main")
        col.append(self.main_lb)
        self.bar = Gtk.ProgressBar(hexpand=True)
        self.bar.add_css_class("hk-w-bar")
        col.append(self.bar)
        self.sub_lb = Gtk.Label(xalign=0, wrap=True)
        self.sub_lb.add_css_class("hk-w-sub")
        self.sub_lb.add_css_class("dim-label")
        col.append(self.sub_lb)
        self.notice_lb = Gtk.Label(xalign=0, wrap=True)
        self.notice_lb.add_css_class("hk-w-notice")
        col.append(self.notice_lb)
        row.append(col)
        self.append(row)
        self.extra = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)      # Etappe 2: Wetter, Gewohnheiten, Anstehendes, Diagramm
        self.append(self.extra)
        self._extra_key = None
        self.stale_lb = Gtk.Label(xalign=0, wrap=True, visible=False)
        self.stale_lb.add_css_class("caption")
        self.stale_lb.add_css_class("dim-label")
        self.append(self.stale_lb)
        register(self.view, self)
        self.connect("map", lambda *_: (self.redraw(), self._arm()))
        self.connect("unmap", lambda *_: self._disarm())
        self.redraw()

    # --- Inhalt -------------------------------------------------------------------------------------
    def present(self):
        return pw.present(self.spec, getattr(self.view, "wvalues", None), rnd=random.Random(self._seed))

    def redraw(self):
        if self.spec.stage2:
            self._redraw2()
            return
        p = self.present()
        self.shown = p
        title = p["title"] or (pw.kind_label(self.spec.kind) if self.spec.known else "")
        self.title_lb.set_label(title)
        self.title_lb.set_visible(bool(title))
        self.main_lb.set_label(p["main"] or "")
        self.main_lb.set_visible(p["main"] is not None)
        self.main_lb.remove_css_class("hk-w-quote")
        if p["quote"]:
            self.main_lb.add_css_class("hk-w-quote")
        self.sub_lb.set_label(p["sub"] or "")
        self.sub_lb.set_visible(bool(p["sub"]))
        self.bar.set_visible(p["fraction"] is not None)
        if p["fraction"] is not None:
            self.bar.set_fraction(p["fraction"])
        self.notice_lb.set_label(p["notice"] or "")
        self.notice_lb.set_visible(bool(p["notice"]))
        (self.notice_lb.add_css_class if p["error"] else self.notice_lb.remove_css_class)("hk-w-error")
        (self.notice_lb.remove_css_class if p["error"] else self.notice_lb.add_css_class)("dim-label")
        vals = getattr(self.view, "wvalues", None)
        stale = bool(vals is not None and vals.stale and self.spec.known and self.spec.server_value and p["main"])
        self.stale_lb.set_label(pw.state_text("failed") if stale else "")
        self.stale_lb.set_visible(stale)
        if self.analog is not None:
            self.analog.queue_draw()

    def _redraw2(self):
        """Wetter/Gewohnheiten/Anstehendes/Diagramm: Inhalt aus W.data (vom Server)"""
        from . import pwcard2
        from . import pagewidgets as pwl
        vals = getattr(self.view, "wvalues", None)
        w = vals.get(self.spec.id) if vals is not None and self.spec.id else None
        title = self.spec.title or pwl.kind_label(self.spec.kind)
        self.title_lb.set_label(title)
        self.title_lb.set_visible(bool(title))
        for x in (self.main_lb, self.sub_lb, self.bar):
            x.set_visible(False)
        notice = None
        if w is not None and w.get("error"):
            notice = pwl.error_text(w["error"])
        elif w is None or w.get("data") is None:
            st = vals.state if vals is not None else "none"
            notice = _("Widget only available on the originating server") if (vals is not None and vals.no2) else pwl._missing(st, w)
        self.notice_lb.set_label(notice or "")
        self.notice_lb.set_visible(bool(notice))
        key = repr((w or {}).get("data")) + repr(notice)
        if key == self._extra_key:
            return
        self._extra_key = key
        while (c := self.extra.get_first_child()):
            self.extra.remove(c)
        if notice:
            return
        d = w["data"]
        k = self.spec.kind
        if k == "weather":
            self.extra.append(pwcard2.weather_box(d))
        elif k == "habit":
            self.extra.append(pwcard2.habit_box(d, self._habit))           # can_check entscheidet der Server
        elif k == "upcoming":
            self.extra.append(pwcard2.upcoming_box(d, open_note=lambda n: self.view.win.show_page(n)))
        elif k == "chart":
            self.extra.append(pwcard2.chart_box(d))

    def _habit(self, pid, day, on):
        """Abhaken: POST .../widgets/{bid}/habit -> neuer Stand des Widgets"""
        from urllib.parse import quote
        from .api import client
        from .push import local_tz
        from .util import bg
        from . import pagewidgets as pwl
        nid = self.view.id
        path = f"api/notes/{nid}/widgets/{quote(self.spec.id, safe='')}/habit?tz={quote(local_tz(), safe='')}"

        def fin(r):
            nw = (r or {}).get("widget")
            vals = getattr(self.view, "wvalues", None)
            if isinstance(nw, dict) and vals is not None:
                vals.values[self.spec.id] = pwl.parse_value(nw)
            self._extra_key = None
            self.redraw()

        def err(e):
            self._extra_key = None
            self.redraw()
            self.view.win.toast(_("Couldn’t save: {error}").format(error=e))
        bg(client.json, "POST", path, {"pid": pid, "day": day, "on": bool(on)}, done=fin, error=err)

    # --- Ticken (nur Uhr und Countdown, nur sichtbar) -----------------------------------------------------
    def ticking(self):
        return self.spec.kind in ("clock", "countdown")

    def _arm(self):
        self._disarm()
        if not self.ticking():
            return
        sec = self.spec.kind == "clock" and (pw.clock_cfg(self.spec.cfg)["sec"] or self.analog is not None)
        ms = pw.next_tick_ms(GLib.get_real_time() // 1000, sec)
        self._timer = GLib.timeout_add(max(20, ms + 5), self._tick)

    def _disarm(self):
        if self._timer is not None:
            GLib.source_remove(self._timer)
            self._timer = None

    def _tick(self):
        self._timer = None
        if self.get_mapped():
            self.redraw()
            self._arm()
        return False

    # --- Zeigeruhr -------------------------------------------------------------------------------------------
    def _draw_analog(self, area, cr, w, h):
        c = pw.clock_cfg(self.spec.cfg)
        t = pw.clock_now(c)
        col = area.get_color()
        r = min(w, h) / 2.0 - 2
        cx, cy = w / 2.0, h / 2.0
        cr.set_source_rgba(col.red, col.green, col.blue, 0.08)
        cr.arc(cx, cy, r, 0, 2 * math.pi)
        cr.fill()
        cr.set_source_rgba(col.red, col.green, col.blue, 0.55)
        cr.set_line_width(max(1.0, r / 40))
        cr.arc(cx, cy, r, 0, 2 * math.pi)
        cr.stroke()
        for i in range(12):                                  # Stundenstriche
            a = i * math.pi / 6
            inner = r * (0.80 if i % 3 == 0 else 0.87)
            cr.move_to(cx + math.sin(a) * inner, cy - math.cos(a) * inner)
            cr.line_to(cx + math.sin(a) * r * 0.95, cy - math.cos(a) * r * 0.95)
        cr.stroke()
        ha, ma, sa = pw.hand_angles(t)
        cr.set_line_cap(1)                                    # cairo.LINE_CAP_ROUND
        for ang, ln, wd, alpha in ((ha, 0.5, r / 11, 0.95), (ma, 0.75, r / 16, 0.9)):
            a = math.radians(ang)
            cr.set_source_rgba(col.red, col.green, col.blue, alpha)
            cr.set_line_width(max(1.5, wd))
            cr.move_to(cx, cy)
            cr.line_to(cx + math.sin(a) * r * ln, cy - math.cos(a) * r * ln)
            cr.stroke()
        if c["sec"]:
            a = math.radians(sa)
            cr.set_source_rgba(0.88, 0.24, 0.24, 0.95)
            cr.set_line_width(max(1.0, r / 40))
            cr.move_to(cx, cy)
            cr.line_to(cx + math.sin(a) * r * 0.85, cy - math.cos(a) * r * 0.85)
            cr.stroke()
        cr.set_source_rgba(col.red, col.green, col.blue, 0.95)
        cr.arc(cx, cy, max(2.0, r / 18), 0, 2 * math.pi)
        cr.fill()
