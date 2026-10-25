"""Graph der Seiten (2.5, wie Obsidian): Ansicht mit Gtk.DrawingArea und Cairo, Logik in graphsim.py.
Einstieg: "Graph" in der Seitenleiste bzw. der Befehlsliste (alle Seiten), "Lokaler Graph" im Seitenmenue (Nachbarn der
Seite, Tiefe 1-2). Oben Umschalter "Alle Seiten | Lokal", Suche, Filter, Einpassen und "Liste" - die Liste ist die
gleichwertige Ansicht fuer Orca (Knoten mit Anzahl der Verbindungen). Bedienung: Mausrad/Strg+Wischen/Pinch zoomt,
Ziehen des Hintergrunds verschiebt, Knoten lassen sich ziehen, Ueberfahren hebt Knoten und Nachbarn hervor (~180 ms),
Doppelklick (bzw. Enter) oeffnet, einfacher Klick/Antippen waehlt aus und zeigt eine Karte mit "Oeffnen".
Die Simulation kuehlt ab und steht dann still: der Bildtakt (add_tick_callback) laeuft nur, solange sich etwas bewegt.
Ohne Animationen (gtk-enable-animations aus) erscheint sofort das fertige Layout."""
import math
import time

import cairo
from gi.repository import Adw, Gdk, GLib, GObject, Gtk, Pango, PangoCairo

from . import config, graphsim, version_tuple
from .api import APIError, client
from .i18n import _, ngettext, pgettext
from .store import store
from .util import bg, count_text

PREF_KEY = "graph"
BIG = 400                                              # ab so vielen Knoten: Layout im Hintergrund, Knoten ohne Nachziehen
_cache = {"data": None, "t": 0.0}                      # Antwort von api/graph (30 s wiederverwenden wie im Web)

# Farben wie im Web (static/style.css, Abschnitt 2.5 Graph): (dunkel, hell)
COLORS = {"page": ("#9aa6b8", "#6a7383"), "database": ("#b39ddb", "#7b5cc0"), "row": ("#7fb0e0", "#3d72ad"),
          "tag": ("#6cc48a", "#2e8049"), "line": ("#8b97a8", "#5d6878"),
          "acc": ("#c9a45c", "#7a5a12")}                      # Saga-Gold (Web: --accent), nicht die Systemfarbe
LINE_A = (0.32, 0.30)


def available():
    """Einstieg zeigen? Nicht bei Servern vor 2.5 (Version bekannt) bzw. wenn api/graph 404 lieferte"""
    if store.graph_api is False:
        return False
    v = store.server_version
    return not v or version_tuple(v) >= (2, 5)


def kind_text(n):
    if n.tag:
        return _("Tag")
    t = {"database": _("Database"), "row": _("Database entry")}.get(n.kind, _("Page"))
    return t if n.own else _("{kind}, shared with you").format(kind=t)


def conn_text(c):
    return ngettext("{n} connection", "{n} connections", c).format(n=count_text(c))


def pages_text(c):
    return ngettext("{n} page", "{n} pages", c).format(n=count_text(c))


def _rgb(hexs):
    c = Gdk.RGBA()
    c.parse(hexs)
    return c.red, c.green, c.blue


def _prefs():
    p = dict(graphsim.DEFAULTS)
    p["list"] = False
    v = config.settings.get(PREF_KEY)
    if isinstance(v, dict):
        p.update({k: v[k] for k in p if k in v and isinstance(v[k], type(p[k]))})
    p["depth"] = 2 if p["depth"] == 2 else 1
    return p


def reduced_motion():
    s = Gtk.Settings.get_default()
    return bool(s) and not s.get_property("gtk-enable-animations")


class GraphCanvas(Gtk.DrawingArea):
    """Zeichenflaeche: Kamera/Treffer/Hervorhebung in graphsim.Scene, Kraefte in graphsim.Sim"""
    __gtype_name__ = "HKGraphCanvas"

    def __init__(self, on_open, on_select):
        super().__init__(hexpand=True, vexpand=True, focusable=True, accessible_role=Gtk.AccessibleRole.IMG)
        self.on_open, self.on_select = on_open, on_select
        self.scene, self.sim = graphsim.Scene(), graphsim.Sim()
        self.auto_fit = False
        self._tick = 0
        self._last = 0.0
        self.mp = None                                     # Mauszeiger (fuer Hover nach Zoom/Bewegung)
        self.dr = None                                     # laufendes Ziehen
        self.pinch = None
        self.moved = False
        self._layouts = {}
        self._base = None                                  # zwischengespeicherte Grundebene (siehe _draw)
        self._gen = 0                                      # zaehlt neue Graphen (Grundebene ungueltig)
        self._interact = 0.0
        self._redraw_src = 0
        self.lifted = None                                 # gezogener Knoten grosser Graphen (nicht in der Grundebene)
        self.frame_ms = []                                 # Messung: Dauer der letzten Bilder (Zeichnen)
        self.set_draw_func(self._draw)
        self.set_cursor_from_name("grab")
        self._gestures()

    # --- Daten --------------------------------------------------------------------------------------------------
    def set_graph(self, g, keep=False, focus=None, placed=False):
        """placed: Positionen schon fertig berechnet (Hintergrund, ohne Animationen)"""
        sc, sim = self.scene, self.sim
        sc.reduced = reduced_motion()
        if placed:
            sim.nodes, sim.edges = g.nodes, g.edges
            sim.stop()
        else:
            sim.set(g, keep_from=sim.nodes if keep else None, focus=focus)
        sc.focus = focus
        sc.set(g)
        self._layouts = {}
        self._gen += 1
        if placed:
            self.auto_fit = False
            sc.fit(instant=True)
        elif not keep:
            if sc.reduced:
                sim.settle()
            else:
                sim.run_for(0.06, min_alpha=0.05)            # Vorlauf ohne Bild, dann sofort einpassen (kein Sprung)
            self.auto_fit = not sc.reduced
            sc.fit(instant=True)
        elif sc.reduced:
            sim.settle()
        self.kick()

    def settled_now(self):
        """fertig rechnen und einpassen (reduzierte Bewegung, Bildschirmfotos)"""
        self.sim.settle()
        self.auto_fit = False
        self.scene.fit(instant=True)
        self.scene.animate(1e9)
        self.queue_draw()

    # --- Bildtakt: nur solange sich etwas bewegt ------------------------------------------------------------------
    def kick(self):
        self.queue_draw()
        if not self._tick and self.get_mapped():
            self._last = 0.0
            self._tick = self.add_tick_callback(self._on_tick)

    def do_map(self):
        Gtk.DrawingArea.do_map(self)
        self.kick()

    def do_unmap(self):
        if self._tick:
            self.remove_tick_callback(self._tick)
            self._tick = 0
        Gtk.DrawingArea.do_unmap(self)

    def _on_tick(self, _w, clock):
        t = clock.get_frame_time() / 1e6
        dt = min(0.064, max(0.0, t - self._last)) if self._last else 1 / 60
        self._last = t
        sc, sim = self.scene, self.sim
        dragn = self.dr is not None and self.dr.get("node") is not None and self.dr["moved"]
        if dragn and sim.alpha < 0.3:
            sim.alpha = 0.3                                # Ziehen: Nachbarn folgen
        run = sim.running and not (sc.reduced and not dragn)
        if run:
            t0 = time.perf_counter()
            sim.tick()                                     # mindestens ein Schritt, kleine Graphen zwei (wie im Web)
            if time.perf_counter() - t0 < 0.006:
                sim.tick()
        cam = sc.cam_step(dt)
        if cam:
            self.touched()                                 # Kamerafahrt: Vorschau aus der Grundebene
        if self.auto_fit and run and not cam:              # waehrend des Einpendelns folgt die Kamera dem Graph
            tg = sc.fit_target()
            if tg and sim.alpha > 0.03:
                f = min(1.0, dt / 0.12) * 0.5
                sc.k += (tg[0] - sc.k) * f
                sc.tx += (tg[1] - sc.tx) * f
                sc.ty += (tg[2] - sc.ty) * f
            else:
                self.auto_fit = False
                sc.fit()
                cam = True
        elif self.auto_fit and not run:
            self.auto_fit = False
            sc.fit()
            cam = True
        if self.mp and not self.dr and (run or cam):
            self._hover_at(*self.mp)
        busy = sc.animate(dt)
        self.queue_draw()
        if run or cam or busy or sim.running and dragn:
            return GLib.SOURCE_CONTINUE
        self._tick = 0
        return GLib.SOURCE_REMOVE

    # --- Zeichnen --------------------------------------------------------------------------------------------------
    def _palette(self):
        dark = Adw.StyleManager.get_default().get_dark()
        i = 0 if dark else 1
        fg = self.get_color()
        return {"dark": dark, "fg": (fg.red, fg.green, fg.blue), "bg": (0.12, 0.12, 0.12) if dark else (1.0, 1.0, 1.0),
                "line_a": LINE_A[i], **{k: _rgb(v[i]) for k, v in COLORS.items()}}

    def color(self, P, n):
        return P["tag"] if n.tag else P.get(n.kind, P["page"])

    @staticmethod
    def _shape(cr, n, x, y, r):
        """Knoten: Kreis fuer alle Arten wie im Web (Art an der Farbe und in der Legende, Seiten/Datenbanken/Eintraege
        sind auf allen Plattformen gleich geformt)"""
        cr.new_sub_path()
        cr.arc(x, y, r, 0, 2 * math.pi)

    def _draw(self, _a, cr, w, h):
        """Grundebene (Kanten, Knoten, Beschriftungen im Ruhezustand) als Bild zwischenspeichern: Verschieben und Zoomen
        verwenden es weiter (Vorschau), neu gerechnet wird erst, wenn die Bedienung ~120 ms ruht oder sich etwas bewegt.
        Darueber jedes Bild nur, was hervorgehoben ist - so bleiben auch 2000 Knoten fluessig."""
        t0 = time.perf_counter()
        sc = self.scene
        if (w, h) != (sc.w, sc.h):
            first = not sc.w
            sc.resize(w, h)
            if first:
                sc.fit(instant=True)
        if not sc.nodes:
            return
        P = self._palette()
        k, tx, ty = sc.k, sc.tx, sc.ty
        b = self._base
        same = b is not None and b["v"] == (self.sim.ticks, self._gen, P["dark"], w, h, self.get_scale_factor())
        m = b["m"] if b else 0
        moved = same and (abs(b["tx"] - tx) > m * 0.9 or abs(b["ty"] - ty) > m * 0.9)
        idle = time.monotonic() - self._interact >= 0.12
        if not same or ((b["k"] != k or moved) and idle):
            b = self._base = self._render_base(P, k, tx, ty, w, h)
        elif b["k"] != k or b["tx"] != tx or b["ty"] != ty:
            self._later_redraw()                           # Vorschau aus dem alten Bild, genau nach dem Ruhen
        D = graphsim.ease(sc.dim)
        s = k / b["k"]
        cr.save()
        cr.translate(tx - (b["tx"] + b["m"]) * s, ty - (b["ty"] + b["m"]) * s)
        cr.scale(s, s)
        cr.set_source_surface(b["surf"], 0, 0)
        if s != 1:
            cr.get_source().set_filter(1)                  # cairo.FILTER_FAST
        cr.paint_with_alpha(1 - 0.82 * D)
        cr.restore()
        self._draw_overlay(cr, P, w, h, D)
        self.frame_ms.append((time.perf_counter() - t0) * 1000)
        del self.frame_ms[:-120]

    def _later_redraw(self):
        if not self._redraw_src:
            def go():
                self._redraw_src = 0
                self.queue_draw()
                return False
            self._redraw_src = GLib.timeout_add(130, go)

    def touched(self):
        """Person bewegt die Ansicht: Grundebene erst nach dem Ruhen neu rechnen"""
        self._interact = time.monotonic()

    def _render_base(self, P, k, tx, ty, w, h):
        sf = self.get_scale_factor()
        m = int(max(w, h) * 0.25)                          # Rand: kurzes Verschieben braucht kein neues Bild
        W, H = w + 2 * m, h + 2 * m
        surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, W * sf, H * sf)
        surf.set_device_scale(sf, sf)
        cr = cairo.Context(surf)
        sc = self.scene
        ox, oy = tx + m, ty + m
        lift = self.lifted
        x0, y0, x1, y1 = -10, -10, W + 10, H + 10
        lw = 0.9 * min(1.6, max(0.6, math.sqrt(k)))
        cr.set_line_width(lw)
        lr, lg, lb = P["line"]
        base = P["line_a"]
        for kinds, dash, fa in ((("l", "c"), None, 1.0), (("r",), (3.0, 3.0), 1.0), (("t",), None, 0.6)):
            cr.set_dash(dash or ())
            cr.set_source_rgba(lr, lg, lb, base * fa)
            for e in sc.edges:                             # einzeln: ein riesiger Pfad ist in Cairo viel langsamer
                if e.k not in kinds or e.s is lift or e.t is lift:
                    continue
                ax, ay, bx, by = e.s.x * k + ox, e.s.y * k + oy, e.t.x * k + ox, e.t.y * k + oy
                if (ax < x0 and bx < x0) or (ax > x1 and bx > x1) or (ay < y0 and by < y0) or (ay > y1 and by > y1):
                    continue
                cr.move_to(ax, ay)
                cr.line_to(bx, by)
                cr.stroke()
        cr.set_dash(())
        batches, labels = {}, []
        few = len(sc.nodes) <= 40
        for n in sc.nodes:
            r = sc.rad(n) * k
            x, y = n.x * k + ox, n.y * k + oy
            if n is lift or x + r < x0 or x - r > x1 or y + r < y0 or y - r > y1:
                continue
            batches.setdefault((n.tag, n.kind, n.own), []).append((n, x, y, r))
            za = sc.zoom_alpha(n, few=few)                 # Grundebene: Ruhezustand ohne Hervorhebung
            if za > 0.03:
                labels.append((n, x, y, za, r))
        for (_tag, _kind, own), lst in batches.items():
            for n, x, y, r in lst:
                self._shape(cr, n, x, y, r)
            c = self.color(P, lst[0][0])
            if own:
                cr.set_source_rgba(*c, 1)
                cr.fill()
            else:                                          # freigegeben: blasser gefuellt mit Rand
                cr.set_source_rgba(*c, 0.35)
                cr.fill_preserve()
                cr.set_line_width(1.4)
                cr.set_source_rgba(*c, 1)
                cr.stroke()
        # ohne Ueberlappung: bei Kollision gewinnt der Knoten mit mehr Verbindungen, die uebrigen kommen beim Hineinzoomen
        labels.sort(key=lambda it: -it[0].deg)
        labels = labels[:450]
        keep = graphsim.place_labels([self._lbox(cr, n, x, y + r + 3, False) for n, x, y, _la, r in labels])
        for (n, x, y, la, r), ok in zip(labels, keep):
            if ok:
                self._label(cr, P, n, x, y + r + 3, la, False)
        surf.flush()
        return {"surf": surf, "k": k, "tx": tx, "ty": ty, "m": m,
                "v": (self.sim.ticks, self._gen, P["dark"], w, h, sf)}

    def _lbox(self, cr, n, x, y, big):
        tw, th = self._layout(cr, n, big).get_pixel_size()
        return (x - tw / 2, y, x + tw / 2, y + th)

    def _label(self, cr, P, n, x, y, la, big):
        lay = self._layout(cr, n, big)
        tw, _th = lay.get_pixel_size()
        cr.move_to(x - tw / 2, y)
        PangoCairo.layout_path(cr, lay)
        cr.set_line_width(3)
        cr.set_line_join(1)
        cr.set_source_rgba(*P["bg"], la * 0.9)
        cr.stroke()
        cr.move_to(x - tw / 2, y)
        cr.set_source_rgba(*P["fg"], la)
        PangoCairo.show_layout(cr, lay)

    def _draw_overlay(self, cr, P, w, h, D):
        sc = self.scene
        k, tx, ty = sc.k, sc.tx, sc.ty
        x0, y0, x1, y1 = -40, -40, w + 40, h + 40
        lw = 0.9 * min(1.6, max(0.6, math.sqrt(k)))
        ar, ag, ab = P["acc"]
        lift = self.lifted
        if lift is not None:                               # gezogener Knoten grosser Graphen: Kanten hier
            cr.set_line_width(lw)
            cr.set_source_rgba(*P["line"], P["line_a"] * (1 - 0.8 * D))
            for e in sc.edges:
                if e.s is lift or e.t is lift:
                    cr.move_to(e.s.x * k + tx, e.s.y * k + ty)
                    cr.line_to(e.t.x * k + tx, e.t.y * k + ty)
                    cr.stroke()
        if D > 0:
            for e in sc.edges:
                if e.e <= 0:
                    continue
                a = graphsim.ease(e.e)
                cr.set_dash((3.0, 3.0) if e.k == "r" else ())
                cr.set_line_width(lw * (1 + 0.8 * a))
                cr.move_to(e.s.x * k + tx, e.s.y * k + ty)
                cr.line_to(e.t.x * k + tx, e.t.y * k + ty)
                cr.set_source_rgba(ar, ag, ab, 0.9 * a)
                cr.stroke()
            cr.set_dash(())
        dim_a = 1 - 0.85 * D
        few = len(sc.nodes) <= 40
        labels = []
        for n in sc.nodes:
            if not (n.e > 0 or n.g > 0 or n.id == sc.focus or n is lift):
                continue
            x, y = n.x * k + tx, n.y * k + ty
            r = sc.rr(n) * k
            if x + r < x0 or x - r > x1 or y + r < y0 or y - r > y1:
                continue
            a, g = graphsim.ease(n.e), graphsim.ease(n.g)
            cur = n.id == sc.focus
            al = dim_a + (1 - dim_a) * a
            self._shape(cr, n, x, y, r)
            c = P["acc"] if cur else self.color(P, n)
            cr.set_source_rgba(*c, al * (1 if n.own else 0.35))
            if n.own:
                cr.fill()
            else:
                cr.fill_preserve()
                cr.set_line_width(1.4)
                cr.set_source_rgba(*c, al)
                cr.stroke()
            if g > 0 and not cur:
                self._shape(cr, n, x, y, r)
                cr.set_source_rgba(ar, ag, ab, g)
                cr.fill()
            if cur:                                        # offene Seite: Ring mit Abstand
                cr.new_sub_path()
                cr.arc(x, y, r + 3.5, 0, 2 * math.pi)
                cr.set_line_width(1.6)
                cr.set_source_rgba(ar, ag, ab, max(dim_a, a))
                cr.stroke()
            if a > 0:
                la = sc.label_alpha(n, few=few)
                if la > 0.03:
                    labels.append((n, x, y + r + 3, la))
        # Rangfolge: ueberfahrener bzw. ausgewaehlter Knoten, offene Seite, dann mehr Verbindungen; was sich mit einer
        # wichtigeren Beschriftung ueberschneidet, entfaellt (erscheint beim Hineinzoomen)
        labels.sort(key=lambda it: (it[0] is not sc.hover and it[0] is not sc.sel, it[0].id != sc.focus, -it[0].e, -it[0].deg))
        labels = labels[:200]
        keep = graphsim.place_labels([self._lbox(cr, n, x, y, n is sc.hover or n is sc.sel) for n, x, y, _la in labels])
        labels = [it for it, ok in zip(labels, keep) if ok]
        labels.reverse()                                   # wichtigste zuletzt (oben)
        for n, x, y, la in labels:
            big = n is sc.hover or n is sc.sel
            if big:                                        # kleine Beschriftung der Grundebene darunter abdecken
                lay = self._layout(cr, n, False)
                tw, th = lay.get_pixel_size()
                y0 = n.y * k + ty + sc.rad(n) * k + 3
                cr.rectangle(x - tw / 2 - 2, y0 - 1, tw + 4, th + 2)
                cr.set_source_rgba(*P["bg"], graphsim.ease(n.e))
                cr.fill()
            self._label(cr, P, n, x, y, la, big)

    def _layout(self, cr, n, big):
        key = (n.id, big)
        lay = self._layouts.get(key)
        if lay is None:
            lay = PangoCairo.create_layout(cr)
            t = n.label()
            if len(t) > 40 and not big:
                t = t[:39] + "…"
            lay.set_text(t, -1)
            fd = Pango.FontDescription.from_string("Sans Bold 10" if big else "Sans 9")
            lay.set_font_description(fd)
            self._layouts[key] = lay
        else:
            PangoCairo.update_layout(cr, lay)
        return lay

    # --- Zeiger, Gesten, Tastatur -----------------------------------------------------------------------------------
    def _gestures(self):
        drag = Gtk.GestureDrag()
        drag.connect("drag-begin", self._drag_begin)
        drag.connect("drag-update", self._drag_update)
        drag.connect("drag-end", self._drag_end)
        self.add_controller(drag)
        click = Gtk.GestureClick()
        click.connect("released", self._released)
        self.add_controller(click)
        zoom = Gtk.GestureZoom()
        zoom.connect("begin", self._zoom_begin)
        zoom.connect("scale-changed", self._zoom_changed)
        zoom.connect("end", lambda *_: setattr(self, "pinch", None))
        self.add_controller(zoom)
        scroll = Gtk.EventControllerScroll(flags=Gtk.EventControllerScrollFlags.BOTH_AXES)
        scroll.connect("scroll", self._scroll)
        self.add_controller(scroll)
        motion = Gtk.EventControllerMotion()
        motion.connect("motion", self._motion)
        motion.connect("leave", self._leave)
        self.add_controller(motion)
        key = Gtk.EventControllerKey()
        key.connect("key-pressed", self._key)
        self.add_controller(key)

    @staticmethod
    def _touch(g):
        d = g.get_device()
        return bool(d) and d.get_source() == Gdk.InputSource.TOUCHSCREEN

    def _drag_begin(self, g, x, y):
        self.grab_focus()
        if self.pinch:
            self.dr = None
            return
        touch = self._touch(g)
        n = self.scene.at(x, y, graphsim.HIT_TOUCH if touch else graphsim.HIT_MOUSE)
        self.dr = {"node": n, "x": x, "y": y, "tx": self.scene.tx, "ty": self.scene.ty, "moved": False, "touch": touch}
        self.moved = False
        if n is not None and not touch:
            self._set_hover(n)
        self.set_cursor_from_name("grabbing")

    def _drag_update(self, _g, ox, oy):
        dr = self.dr
        if dr is None or self.pinch:
            return
        if not dr["moved"] and abs(ox) + abs(oy) > (8 if dr["touch"] else 4):
            dr["moved"] = self.moved = True
            self._user()
        if not dr["moved"]:
            return
        sc = self.scene
        if dr["node"] is not None:
            n = dr["node"]
            n.x, n.y = sc.to_world(dr["x"] + ox, dr["y"] + oy)
            n.vx = n.vy = 0.0
            if len(sc.nodes) > BIG:                        # gross: nur der Knoten wandert (ueber der Grundebene)
                if self.lifted is not n:
                    self.lifted = n
                    self._gen += 1
                self.queue_draw()
                return
            self.sim.drag = n
            self.sim.heat(0.3)
            self.kick()
        else:
            sc.cam = None
            sc.tx, sc.ty = dr["tx"] + ox, dr["ty"] + oy
            self.touched()
            self.queue_draw()

    def _drag_end(self, _g, _ox, _oy):
        dr, self.dr = self.dr, None
        self.sim.drag = None
        if self.lifted is not None:
            self.lifted = None
            self._gen += 1
            self.queue_draw()
        if dr and dr["node"] is not None and dr["moved"] and self.scene.reduced:
            self.sim.settle(120)
            self.queue_draw()
        self.set_cursor_from_name("pointer" if self.scene.hover else "grab")

    def _released(self, g, n_press, x, y):
        if self.moved:
            self.moved = False
            return
        touch = self._touch(g)
        n = self.scene.at(x, y, graphsim.HIT_TOUCH if touch else graphsim.HIT_MOUSE)
        if n_press >= 2 and n is not None:
            self.on_open(n)
            return
        self.select(n, "touch" if touch else "pointer")

    def _zoom_begin(self, g, _seq):
        self.dr = None
        self._user()
        self.pinch = self.scene.k

    def _zoom_changed(self, g, scale):
        if self.pinch is None:
            return
        ok, cx, cy = g.get_bounding_box_center()
        if not ok:
            cx, cy = self.scene.w / 2, self.scene.h / 2
        self.scene.zoom_at(cx, cy, self.pinch * scale / self.scene.k)
        self._after_zoom()

    def _scroll(self, c, dx, dy):
        self._user()
        sc = self.scene
        unit = c.get_unit() if hasattr(c, "get_unit") else Gdk.ScrollUnit.WHEEL
        ctrl = bool(c.get_current_event_state() & Gdk.ModifierType.CONTROL_MASK)
        x, y = self.mp or (sc.w / 2, sc.h / 2)
        if unit == Gdk.ScrollUnit.SURFACE and not ctrl:    # Touchpad: zwei Finger verschieben (Pinch/Strg zoomt)
            sc.pan(-dx, -dy)
        elif unit == Gdk.ScrollUnit.SURFACE:
            sc.zoom_at(x, y, math.exp(-dy * 0.01))
        else:                                              # Mausrad: sanft zoomen
            sc.zoom_at(x, y, math.exp(-dy * 0.18))
        self._after_zoom()
        return True

    def _after_zoom(self):
        self.touched()
        if self.mp:
            self._hover_at(*self.mp)
        self.queue_draw()

    def _motion(self, _c, x, y):
        self.mp = (x, y)
        if self.dr is None:
            self._hover_at(x, y)

    def _leave(self, _c):
        self.mp = None
        if self.dr is None:
            self._set_hover(None)

    def _hover_at(self, x, y):
        self._set_hover(self.scene.at(x, y))

    def _set_hover(self, n):
        if n is self.scene.hover:
            return
        self.scene.hover = n
        if self.dr is None:
            self.set_cursor_from_name("pointer" if n else "grab")
        self.set_tooltip_text(n.label() if n else None)
        self.kick()

    def select(self, n, src=None):
        if n is self.scene.sel:
            return
        self.scene.sel = n
        self.on_select(n, src)
        self.kick()

    def _user(self):
        self.auto_fit = False
        self.scene.cam = None

    def zoom_center(self, f):
        self._user()
        self.scene.zoom_at(self.scene.w / 2, self.scene.h / 2, f)
        self.touched()
        self.queue_draw()

    def fit(self):
        self._user()
        self.scene.fit()
        self.kick()

    def _key(self, _c, kv, _code, state):
        sc = self.scene
        dirs = {Gdk.KEY_Left: (-1, 0), Gdk.KEY_Right: (1, 0), Gdk.KEY_Up: (0, -1), Gdk.KEY_Down: (0, 1)}
        if kv in dirs:
            n = sc.step(*dirs[kv])
            if n is not None:
                self.select(n, "key")
                sc.reveal(n)
                self.kick()
            return True
        if kv in (Gdk.KEY_Return, Gdk.KEY_KP_Enter, Gdk.KEY_space):
            n = sc.sel or sc.hover
            if n is not None:
                self.on_open(n)
            return n is not None
        if kv in (Gdk.KEY_plus, Gdk.KEY_equal, Gdk.KEY_KP_Add):
            self.zoom_center(1.25)
            return True
        if kv in (Gdk.KEY_minus, Gdk.KEY_KP_Subtract):
            self.zoom_center(0.8)
            return True
        if kv in (Gdk.KEY_0, Gdk.KEY_KP_0):
            self.fit()
            return True
        if kv == Gdk.KEY_Escape and sc.sel is not None:
            self.select(None)
            return True
        return False


class GraphScreen(Gtk.Box):
    """Bereich "Graph" im Hauptfenster: Kopf (Umschalter, Tiefe, Suche, Filter, Liste, Einpassen), Zeichenflaeche bzw.
    Liste, Zustaende (Laden, leer, Fehler, offline, alter Server)"""

    def __init__(self, win, mode="all", page_id=None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.win = win
        self.page_id = page_id
        self.mode = "local" if mode == "local" and page_id is not None else "all"
        self.P = _prefs()
        self.g = None
        self._listed = None                                # Liste wird erst gefuellt, wenn sie gezeigt wird
        self.matches = []
        self._ready = False                                # Umschalter erst nach dem Aufbau beachten
        self.add_css_class("hk-graph")
        # Breite des Bereichs (nicht des Fensters) entscheidet ueber den Kopf: breit | kompakt (Suche als Lupe) |
        # schmal (zwei Zeilen, Tiefe kurz) - so laeuft er in keiner Sprache ueber und die Fensterknoepfe bleiben sichtbar
        root = self.root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.bin = Adw.BreakpointBin(child=root, width_request=280, height_request=200, hexpand=True, vexpand=True)
        self._bps = {}
        for name, cond in (("compact", "max-width: 820sp"), ("narrow", "max-width: 600sp")):   # zuletzt hinzugefuegt gewinnt
            bp = self._bps[name] = Adw.Breakpoint.new(Adw.BreakpointCondition.parse(cond))
            self.bin.add_breakpoint(bp)
        self.bin.connect("notify::current-breakpoint", lambda *_: self.check_narrow())
        self.append(self.bin)
        self._head()
        self.stack = Gtk.Stack(transition_type=Gtk.StackTransitionType.CROSSFADE, vexpand=True)
        root.append(self.stack)
        self.status = Adw.StatusPage()
        self.stack.add_named(self.status, "msg")
        self.canvas = GraphCanvas(self.open_node, self._selected)
        ov = Gtk.Overlay(child=self.canvas)
        ov.add_css_class("view")
        self.card = self._card()
        ov.add_overlay(self.card)
        # Zaehlzeile: einzeilig ueber der Zeichenflaeche (im Kopf wurde sie je nach Sprache abgeschnitten)
        self.stat = Gtk.Label(xalign=0, halign=Gtk.Align.START, valign=Gtk.Align.START, margin_start=12, margin_top=10,
                              single_line_mode=True, can_target=True)
        self.stat.add_css_class("caption")
        self.stat.add_css_class("hk-graph-chip")
        ov.add_overlay(self.stat)
        self.legend = Gtk.FlowBox(halign=Gtk.Align.START, valign=Gtk.Align.END, margin_start=12, margin_bottom=10,
                                  margin_end=12, can_target=False, selection_mode=Gtk.SelectionMode.NONE,
                                  column_spacing=12, row_spacing=2, max_children_per_line=6)   # schmal: umbrechen
        self.legend.add_css_class("caption")
        self.legend.add_css_class("hk-graph-chip")
        self.legend.update_property([Gtk.AccessibleProperty.LABEL], [_("Legend")])
        ov.add_overlay(self.legend)
        self.hint = Gtk.Label(wrap=True, justify=Gtk.Justification.CENTER, halign=Gtk.Align.CENTER, valign=Gtk.Align.START,
                              margin_top=44, margin_start=24, margin_end=24, visible=False, can_target=False)
        self.hint.add_css_class("dim-label")
        ov.add_overlay(self.hint)
        self.stack.add_named(ov, "graph")
        self.listbox = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, margin_start=12, margin_end=12, margin_top=12,
                                   margin_bottom=12)
        self.listbox.add_css_class("boxed-list")
        self.listbox.update_property([Gtk.AccessibleProperty.LABEL], [_("Pages in the graph")])
        self.listbox.connect("row-activated", lambda _lb, row: self.open_node(row.node))
        clamp = Adw.Clamp(child=self.listbox, maximum_size=760)
        self.stack.add_named(Gtk.ScrolledWindow(child=clamp, hscrollbar_policy=Gtk.PolicyType.NEVER), "list")
        self._paint_mode()
        self._ready = True
        self.load()

    # --- Kopf ---------------------------------------------------------------------------------------------------------
    def _head(self):
        self.bar = Gtk.Box(spacing=6, margin_start=10, margin_end=10, margin_top=6, margin_bottom=6)
        self.bar.add_css_class("toolbar")
        self.bar2 = Gtk.Box(spacing=6, margin_start=10, margin_end=10, margin_bottom=6, visible=False)
        self.bar2.add_css_class("toolbar")
        seg = self.seg = Gtk.Box()
        seg.add_css_class("linked")
        seg.update_property([Gtk.AccessibleProperty.LABEL], [_("View")])
        self.b_all = Gtk.ToggleButton(label=_("All pages"))
        self.b_loc = Gtk.ToggleButton(label=_("Local"), group=self.b_all)
        self.b_loc.set_tooltip_text(_("The open page and its neighbors"))
        self.b_all.connect("toggled", lambda b: b.get_active() and self.show_mode("all"))
        self.b_loc.connect("toggled", lambda b: b.get_active() and self.show_mode("local"))
        seg.append(self.b_all)
        seg.append(self.b_loc)
        # Tiefe: im Knopf "Tiefe 2" bzw. schmal nur "2" (Pfeil vom DropDown), in der Liste immer ausgeschrieben
        self.depth = Gtk.DropDown.new_from_strings([_("Depth 1"), _("Depth 2")])
        self.depth.set_tooltip_text(_("How many steps away neighbors may be"))
        self.depth.set_selected(self.P["depth"] - 1)
        self.depth.connect("notify::selected", self._depth_changed)
        self._depth_short = False
        self.depth.set_factory(self._depth_factory())
        self.depth.set_list_factory(self._depth_factory(full=True))
        self._depth_a11y()
        self.spacer = Gtk.Box(hexpand=True)
        self.search = Gtk.SearchEntry(placeholder_text=_("Search pages in the graph"), width_chars=18)
        self.search.update_property([Gtk.AccessibleProperty.LABEL], [_("Search pages in the graph")])
        self.search.connect("search-changed", lambda *_: self.apply_search())
        self.search.connect("activate", self._search_enter)
        self.search.connect("stop-search", lambda *_: self.searchbar.set_search_mode(False))
        # kompakt/schmal: Lupe im Kopf, Suche in eigener Zeile darunter (Gtk.SearchBar)
        self.search_btn = Gtk.ToggleButton(icon_name="system-search-symbolic", tooltip_text=_("Search pages in the graph"))
        self.search_btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Search pages in the graph")])
        self.search_clamp = Adw.Clamp(maximum_size=560, hexpand=True)
        self.searchbar = Gtk.SearchBar(show_close_button=True, child=self.search_clamp)
        self.searchbar.bind_property("search-mode-enabled", self.search_btn, "active",
                                     GObject.BindingFlags.BIDIRECTIONAL | GObject.BindingFlags.SYNC_CREATE)
        self.searchbar.connect("notify::search-mode-enabled", self._searchbar_toggled)
        self.filter_btn = Gtk.MenuButton(icon_name="saga-filter-symbolic", tooltip_text=_("Filters"))
        self.filter_btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Filters")])
        self.filter_btn.set_popover(self._filters())
        self.list_btn = Gtk.ToggleButton(icon_name="view-list-symbolic", tooltip_text=_("Show as list"),
                                         active=self.P["list"])
        self.list_btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Show as list")])
        self.list_btn.connect("toggled", self._list_toggled)
        self.fit_btn = Gtk.Button(icon_name="zoom-fit-best-symbolic", tooltip_text=_("Fit to view"))
        self.fit_btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Fit to view")])
        self.fit_btn.connect("clicked", lambda *_: self.canvas.fit())
        self.root.append(self.bar)
        self.root.append(self.bar2)
        self.root.append(self.searchbar)
        self.root.append(Gtk.Separator())
        self.level = None
        self.check_narrow()

    def _depth_factory(self, full=False):
        f = Gtk.SignalListItemFactory()
        f.connect("setup", lambda _f, it: it.set_child(Gtk.Label(xalign=0)))

        def bind(_f, it):
            t = it.get_item().get_string()
            it.get_child().set_label(t.rsplit(" ", 1)[-1] if self._depth_short and not full else t)
        f.connect("bind", bind)
        return f

    def _depth_a11y(self):
        it = self.depth.get_selected_item()
        if it is not None:                                 # Orca liest "Tiefe 2", auch wenn der Knopf nur "2" zeigt
            self.depth.update_property([Gtk.AccessibleProperty.LABEL], [it.get_string()])

    def _searchbar_toggled(self, sb, _p):
        if sb.get_search_mode():
            self.search.grab_focus()
        elif self.level != "wide" and self.search.get_text():
            self.search.set_text("")

    def _filters(self):
        pop = Gtk.Popover()
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, margin_start=6, margin_end=6, margin_top=6,
                      margin_bottom=6)
        self._checks = {}
        for key, label in (("link", _("Links in text")), ("child", _("Subpages")), ("rel", _("Database relations")),
                           ("tags", _("Tags as nodes")), ("orphans", _("Pages without connections"))):
            cb = Gtk.CheckButton(label=label, active=self.P[key])
            cb.connect("toggled", lambda b, key=key: self._set_pref(key, b.get_active()))
            self._checks[key] = cb
            box.append(cb)
        box.append(Gtk.Separator(margin_top=4, margin_bottom=4))
        for label, fn in ((_("Rearrange"), lambda: self.rebuild(False)), (_("Reload"), lambda: self.load(force=True))):
            b = Gtk.Button(label=label)
            b.add_css_class("flat")
            b.connect("clicked", lambda _b, fn=fn: (pop.popdown(), fn()))
            box.append(b)
        pop.set_child(box)
        return pop

    def _card(self):
        card = Gtk.Box(spacing=12, halign=Gtk.Align.CENTER, valign=Gtk.Align.END, margin_bottom=16, visible=False)
        card.add_css_class("card")
        card.add_css_class("hk-graph-card")
        tx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, margin_start=14, margin_top=8, margin_bottom=8)
        self.card_t = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END, max_width_chars=40)
        self.card_t.add_css_class("heading")
        self.card_s = Gtk.Label(xalign=0)
        self.card_s.add_css_class("caption")
        self.card_s.add_css_class("dim-label")
        tx.append(self.card_t)
        tx.append(self.card_s)
        card.append(tx)
        self.card_o = Gtk.Button(label=_("Open"), valign=Gtk.Align.CENTER, margin_end=10)
        self.card_o.add_css_class("hk-gold")
        self.card_o.add_css_class("pill")
        self.card_o.connect("clicked", lambda *_: self.open_node(self.canvas.scene.sel))
        card.append(self.card_o)
        return card

    def header(self):
        return (_("Graph"), _("Local graph") if self.mode == "local" else _("All pages"))

    def check_narrow(self):
        """Kopf je Breite des Bereichs (Breakpoints am BreakpointBin): breit | kompakt | schmal"""
        bp = self.bin.get_current_breakpoint()
        level = next((k for k, v in self._bps.items() if v == bp), "wide")
        if level == self.level:
            return
        self.level = level
        q = self.search.get_text()
        for w in (self.seg, self.depth, self.spacer, self.search, self.search_btn, self.filter_btn, self.list_btn,
                  self.fit_btn):
            if w.get_parent() is self.search_clamp:
                self.search_clamp.set_child(None)
            elif w.get_parent() is not None:
                w.get_parent().remove(w)
        narrow = level == "narrow"
        self.seg.set_homogeneous(narrow)
        self.seg.set_hexpand(narrow)
        if level == "wide":
            order = [(self.bar, self.seg), (self.bar, self.depth), (self.bar, self.spacer), (self.bar, self.search)]
        else:
            order = [(self.bar, self.seg)] + ([] if narrow else [(self.bar, self.depth), (self.bar, self.spacer)]) + \
                    ([(self.bar2, self.depth), (self.bar2, self.spacer)] if narrow else []) + \
                    [(self.bar2 if narrow else self.bar, self.search_btn)]
        tail = self.bar2 if narrow else self.bar
        order += [(tail, self.filter_btn), (tail, self.list_btn), (tail, self.fit_btn)]
        for box, w in order:
            box.append(w)
        self.bar2.set_visible(narrow)
        if level == "wide":
            self.searchbar.set_search_mode(False)
            self.search.set_hexpand(False)
            self.search.set_width_chars(18)
        else:
            self.search.set_hexpand(True)
            self.search.set_width_chars(8)
            self.search_clamp.set_child(self.search)
            self.searchbar.connect_entry(self.search)
            if q:
                self.searchbar.set_search_mode(True)
        if self.search.get_text() != q:
            self.search.set_text(q)
        if hasattr(self, "legend"):
            self._legend_cols()
        if self._depth_short != narrow:                    # "Profundidad 2" -> "2" (neu binden)
            self._depth_short = narrow
            self.depth.set_factory(self._depth_factory())

    def has_menu(self):
        return False

    def _save(self):
        config.settings.set(PREF_KEY, {k: self.P[k] for k in ("link", "child", "rel", "tags", "orphans", "depth", "list")})

    def _set_pref(self, key, v):
        self.P[key] = v
        self._save()
        self.rebuild(True)

    def _depth_changed(self, dd, _p):
        self.P["depth"] = int(dd.get_selected()) + 1
        self._depth_a11y()
        self._save()
        if self.mode == "local":
            self.rebuild(True)
            self.canvas.auto_fit = not self.canvas.scene.reduced
            self.canvas.sim.heat(0.6)
            self.canvas.kick()

    def _list_toggled(self, b):
        self.P["list"] = b.get_active()
        self._save()
        if self.P["list"] and self.g is not None and self._listed is not self.g:
            self._fill_list(self.g)
            self.apply_search(center=False)
        if self.stack.get_visible_child_name() != "msg":
            self.stack.set_visible_child_name("list" if self.P["list"] else "graph")

    def _paint_mode(self):
        self.b_loc.set_sensitive(self.page_id is not None)
        (self.b_loc if self.mode == "local" else self.b_all).set_active(True)
        self.depth.set_visible(self.mode == "local")

    def show_mode(self, m):
        m = "local" if m == "local" and self.page_id is not None else "all"
        if not self._ready or (m == self.mode and self.g is not None):
            return
        self.mode = m
        self._paint_mode()
        self.canvas.select(None)
        self.win.update_header()
        if _cache["data"] is not None:
            self.rebuild(False)

    # --- Daten und Zustaende ------------------------------------------------------------------------------------------
    def _msg(self, icon, title, desc=None, btn=None, fn=None, spin=False, old=False):
        st = self.status
        st.set_icon_name(icon)
        st.set_title(title)
        st.set_description(GLib.markup_escape_text(desc) if desc else None)
        child = None
        if spin:
            child = Adw.Spinner() if hasattr(Adw, "Spinner") else Gtk.Spinner(spinning=True, width_request=32,
                                                                                height_request=32)
        elif btn:
            child = Gtk.Button(label=btn, halign=Gtk.Align.CENTER)
            child.add_css_class("pill")
            child.add_css_class("hk-gold")
            child.connect("clicked", lambda *_: fn())
        st.set_child(child)
        self.stack.set_visible_child_name("msg")
        self.legend.set_visible(False)
        self._tools(not old)

    def _tools(self, on):
        """Werkzeugleiste: beim alten Server ausgegraut (es gibt nichts zu filtern oder zu suchen)"""
        for w in (self.seg, self.depth, self.search, self.search_btn, self.filter_btn, self.list_btn, self.fit_btn):
            w.set_sensitive(on)
        if on:
            self.b_loc.set_sensitive(self.page_id is not None)
        else:
            self.searchbar.set_search_mode(False)

    def load(self, force=False):
        d = _cache["data"]
        if d is not None and not force and time.monotonic() - _cache["t"] < 30:
            self.rebuild(False)
            return
        self.stat.set_label("")
        self._msg(None, _("Loading graph …"), spin=True)
        bg(client.get, "api/graph", done=self._loaded, error=self._failed)

    def _loaded(self, d):
        if not graphsim.valid(d):
            self._failed(APIError(500))
            return
        store.graph_api = True
        _cache["data"], _cache["t"] = d, time.monotonic()
        self.rebuild(False)

    def _failed(self, e):
        code = getattr(e, "code", None)
        if code == 404:                                    # Server vor 2.5: Einstieg ausblenden
            store.graph_api = False
            sb = getattr(self.win, "sidebar", None)
            if sb is not None:
                sb.fill(force=True)
            self._msg("software-update-available-symbolic", _("Requires server 2.5"),
                      _("Ask your server admin to update to Saga 2.5."), old=True)
        elif code == 0:
            self._msg("network-offline-symbolic", _("You're offline"), _("The graph needs a connection to the server."),
                      _("Try again"), lambda: self.load(force=True))
        else:
            self._msg("dialog-warning-symbolic", _("The graph couldn't be loaded."), str(e), _("Try again"),
                      lambda: self.load(force=True))

    def _title_of(self, nid):
        for n in (_cache["data"] or {}).get("nodes") or []:
            if n and n[0] == nid:
                return str(n[1] or "") or _("Untitled")
        return ""

    def rebuild(self, keep):
        d = _cache["data"]
        if d is None:
            return
        loc = self.mode == "local"
        g = graphsim.build(d, self.P, focus=self.page_id if loc else None, depth=self.P["depth"], untitled=_("Untitled"))
        self.g = g
        nm, ed = len(g.nodes), len(g.edges)
        stat = pages_text(nm) + " · " + conn_text(ed)          # ohne Seitennamen (steht in der Kopfzeile des Fensters)
        if g.truncated and not loc:
            stat += " · " + _("truncated")
            self.stat.set_tooltip_text(_("Only the most recently edited pages are shown."))
        else:
            self.stat.set_tooltip_text(None)
        self.stat.set_label(stat)
        self.hint.set_visible(False)
        if not d.get("nodes"):
            self._msg("view-dual-symbolic", _("No pages yet"),
                      _("Create pages and link them to each other – here you'll see how everything is connected."))
            return
        if loc and g.missing:
            self._msg("dialog-information-symbolic", _("This page is not in the graph"),
                      _("Archived pages, templates and pages from other servers don't appear here."), _("All pages"),
                      lambda: self.b_all.set_active(True))
            return
        if not nm:
            self._msg("edit-find-symbolic", _("No pages match the filters"), None, _("Reset filters"), self.reset_filters)
            return
        if loc and nm == 1:
            self.hint.set_label(_("“{name}” isn't connected to any other page yet").format(name=self._title_of(self.page_id)))
            self.hint.set_visible(True)
        if nm > BIG or (nm > 60 and reduced_motion()):
            # grosser Graph (bzw. ohne Animationen): Layout im Hintergrund fertig rechnen und dann still zeigen -
            # in reinem Python waere das Einpendeln als Animation zaeh (jedes Bild alle Kanten neu)
            self._msg(None, _("Loading graph …"), spin=True)
            focus = self.page_id if loc else None
            old = list(self.canvas.sim.nodes) if keep else None

            def work():
                s = graphsim.Sim()
                s.set(g, keep_from=old, focus=focus)
                s.settle()
                return g
            bg(work, done=lambda g2: g2 is self.g and self._present(g2, False, placed=True))
            return
        self._present(g, keep)

    def _present(self, g, keep, placed=False):
        loc, nm, ed = self.mode == "local", len(g.nodes), len(g.edges)
        self.canvas.set_graph(g, keep=keep, focus=self.page_id, placed=placed)
        if loc:
            desc = ngettext("Local graph of “{name}”: {n} connected page", "Local graph of “{name}”: {n} connected pages",
                            max(0, nm - 1)).format(name=self._title_of(self.page_id), n=count_text(max(0, nm - 1)))
        else:
            desc = _("Graph with {pages} and {edges}.").format(pages=pages_text(nm), edges=conn_text(ed))
        self.canvas.update_property([Gtk.AccessibleProperty.LABEL, Gtk.AccessibleProperty.DESCRIPTION],
                                    [desc, _("Arrow keys select a page, Enter opens it, plus and minus zoom, 0 fits. "
                                             "The list view shows the same pages.")])
        self._legend(g)
        self._listed = None
        if self.P["list"]:
            self._fill_list(g)
        self.stack.set_visible_child_name("list" if self.P["list"] else "graph")
        self.apply_search(center=False)

    def reset_filters(self):
        for k in ("link", "child", "rel", "tags", "orphans"):
            self.P[k] = graphsim.DEFAULTS[k]
            self._checks[k].set_active(self.P[k])
        self._save()
        self.rebuild(True)

    def _legend(self, g):
        """Legende wie im Web (drawLegend): Seite, Datenbank, Datenbank-Eintrag, Tag, offene Seite - alles Kreise;
        dazu "Mit dir geteilt", weil freigegebene Knoten hier blass mit Rand gezeichnet werden"""
        self.legend.remove_all()
        has = g.kinds()
        if self.page_id is not None and any(n.id == self.page_id for n in g.nodes):
            has = has | {"cur"}
        items = [("page", _("Page")), ("database", _("Database")), ("row", _("Database entry")), ("tag", _("Tag")),
                 ("cur", pgettext("graph legend", "Open page")), ("shared", _("Shared with you"))]
        shown = 0
        for key, text in items:
            if key not in has:
                continue
            shown += 1
            dot = Gtk.DrawingArea(content_width=14, content_height=14, valign=Gtk.Align.CENTER)

            def paint(_a, cr, w, h, key=key):
                P = self.canvas._palette()                 # beim Zeichnen: folgt hell/dunkel
                x, y = w / 2, h / 2
                cr.new_sub_path()
                cr.arc(x, y, 4.5 if key != "cur" else 3.5, 0, 2 * math.pi)
                if key == "shared":
                    cr.set_source_rgba(*P["page"], 0.35)
                    cr.fill_preserve()
                    cr.set_line_width(1.4)
                    cr.set_source_rgba(*P["page"], 1)
                    cr.stroke()
                    return
                cr.set_source_rgba(*P["acc" if key == "cur" else key], 1)
                cr.fill()
                if key == "cur":                           # offene Seite: Ring mit Abstand wie im Graph
                    cr.new_sub_path()
                    cr.arc(x, y, 6, 0, 2 * math.pi)
                    cr.set_line_width(1.4)
                    cr.stroke()
            dot.set_draw_func(paint)
            b = Gtk.Box(spacing=5)
            b.append(dot)
            b.append(Gtk.Label(label=text))
            self.legend.append(b)
        self._legend_n = shown
        self._legend_cols()
        self._legend_on = shown > 1
        self.legend.set_visible(self._legend_on and not self.card.get_visible())

    def _legend_cols(self):
        """breit eine Zeile wie im Web, kompakt drei, schmal zwei Eintraege je Zeile (die FlowBox bekommt im Overlay nur
        ihre Mindestbreite)"""
        n = max(1, getattr(self, "_legend_n", 1))
        self.legend.set_max_children_per_line(n)
        self.legend.set_min_children_per_line(min(n, {"narrow": 2, "compact": 3}.get(self.level, n)))

    def _fill_list(self, g):
        """Listenansicht: gleiche Knoten, meist verbundene zuerst - fuer Orca und Tastatur"""
        self._listed = g
        self.listbox.remove_all()
        for n, c in graphsim.rows(g, self.canvas.scene.adj)[:3000]:
            row = Adw.ActionRow(title=n.label(), subtitle=kind_text(n) + " · " + conn_text(c), activatable=True)
            row.set_use_markup(False)
            row.node = n
            row.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
            self.listbox.append(row)

    # --- Suche, Auswahl, Oeffnen -----------------------------------------------------------------------------------------
    def apply_search(self, center=True):
        if self.g is None:
            return None
        q = self.search.get_text()
        sc = self.canvas.scene
        self.matches = graphsim.matches(self.g, q)
        if not q.strip():
            sc.match = None
            self.search.remove_css_class("error")
        else:
            sc.match = {n.id for n in self.matches}
            (self.search.remove_css_class if self.matches else self.search.add_css_class)("error")
            if self.matches and center:
                self.canvas._user()
                sc.center_on(self.matches[0], max(sc.k, 1.0))
            msg = ngettext("{n} match", "{n} matches", len(self.matches)).format(n=len(self.matches)) if self.matches \
                else _("No matches")
            self.search.set_tooltip_text(msg)
            # ansagen ueber den Bereich: das Suchfeld wechselt je nach Breite den Platz (Ansage am umgehaengten Feld
            # stuerzt in GTK 4.14 ab)
            if hasattr(self, "announce") and self.get_mapped():
                self.announce(msg, Gtk.AccessibleAnnouncementPriority.MEDIUM)
        q2 = q.strip().lower()
        row = self.listbox.get_first_child()
        while row is not None:                             # Liste: nur Treffer zeigen
            row.set_visible(not q2 or q2 in row.node.title.lower())
            row = row.get_next_sibling()
        self.canvas.kick()
        return self.matches[0] if self.matches else None

    def _search_enter(self, *_a):
        n = self.apply_search()
        if n is not None:
            self.open_node(n)

    def _selected(self, n, src):
        self.card.set_visible(n is not None)
        self.legend.set_visible(n is None and getattr(self, "_legend_on", False))   # wie im Web: Karte statt Legende
        if n is None:
            return
        c = len(self.canvas.scene.adj.get(n.id, ()))
        self.card_t.set_label(n.label())
        self.card_s.set_label(kind_text(n) + " · " + conn_text(c))
        self.card_o.set_label(_("Filter") if n.tag else _("Open"))
        if src == "key" and hasattr(self.canvas, "announce"):
            self.canvas.announce(f"{n.label()}, {kind_text(n)}, {conn_text(c)}", Gtk.AccessibleAnnouncementPriority.MEDIUM)

    def open_node(self, n):
        if n is None:
            return
        if n.tag:                                          # Tag: als Suche filtern statt oeffnen
            self.search.set_text(n.title)
            return
        self.win.show_page(n.id)
