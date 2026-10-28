"""Diagramme (Saga 2.5, chart1): Ergebnis von GET /api/notes/{db}/chart bzw. Widget-Daten lesen und mit Cairo zeichnen.
Logik (parse, nice_ticks, Farben) ohne GTK testbar; ChartView ist eine Gtk.DrawingArea plus Werte-Tabelle.
Vertrag: docs/APP-API.md "Diagramme" (Achsen: tests/chart_ticks.json des Servers)."""
import math

from .i18n import _

TYPES = ("bar", "hbar", "line", "area", "pie", "donut", "number")
FMTS = ("num", "pct", "eur")
SERIES_ORDER = ("blue", "orange", "green", "purple", "pink", "yellow", "red", "brown")
# Palette wie das Web (--tc-*): (hell, dunkel)
TC = {"gray": ("#686763", "#b0afab"), "brown": ("#8a5a44", "#c9a08b"), "orange": ("#a65300", "#f0a35e"),
      "yellow": ("#7f6200", "#e6c54f"), "green": ("#0b6e60", "#4fc4a9"), "blue": ("#0b6893", "#62b4e2"),
      "purple": ("#6940a5", "#b893e6"), "pink": ("#a8186e", "#e682bb"), "red": ("#c22a2a", "#f28585")}


def nice_ticks(lo, hi, n=5):
    """Schoene Achsenwerte (1, 2, 2.5, 5 x 10^k) von <= lo bis >= hi (wie saga/chartsvg.py nice_ticks)"""
    if lo == hi:
        hi = lo + 1 if lo >= 0 else lo
        lo = min(0, lo)
        if lo == hi:
            hi = lo + 1
    span = hi - lo
    raw = span / max(1, n)
    mag = 10 ** math.floor(math.log10(raw))
    step = next(m * mag for m in (1, 2, 2.5, 5, 10) if m * mag >= raw - 1e-12)
    a = math.floor(lo / step + 1e-9) * step
    b = math.ceil(hi / step - 1e-9) * step
    out, x = [], a
    while x <= b + step / 2 and len(out) < 50:
        v = round(x, 10)
        out.append(int(v) if float(v).is_integer() else v)
        x += step
    return out


def _num(v):
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return None
    v = float(v)
    return v if math.isfinite(v) else None


def parse(obj):
    """Chart-Ergebnis -> dict mit festen Schluesseln oder None (kaputt). Unbekannter Typ -> "bar"."""
    if not isinstance(obj, dict):
        return None
    t = obj.get("type") if obj.get("type") in TYPES else "bar"
    cats = []
    for c in obj.get("cats") or []:
        if not isinstance(c, dict):
            continue
        key = c.get("key")
        lab = c.get("label") if isinstance(c.get("label"), str) else ("" if key is None else str(key))
        if key is None and not lab:
            lab = _("Empty")
        if key == "__other__" and not lab:
            lab = _("Other")
        cats.append({"key": key, "label": lab, "color": c.get("color") if c.get("color") in TC else None,
                     "start": c.get("start") if isinstance(c.get("start"), str) else None})
    series = []
    for s in obj.get("series") or []:
        if not isinstance(s, dict):
            continue
        vals = [_num(v) for v in (s.get("values") or [])][:len(cats)]
        vals += [None] * (len(cats) - len(vals))
        series.append({"key": s.get("key") if isinstance(s.get("key"), str) else "", "label": s.get("label") if isinstance(s.get("label"), str) else "",
                       "color": s.get("color") if s.get("color") in TC else None, "values": vals})
    if t != "number" and not series:
        series = [{"key": "", "label": "", "color": None, "values": [None] * len(cats)}]
    return {"type": t, "x_kind": obj.get("x_kind") if obj.get("x_kind") in ("cat", "date") else "cat", "bucket": obj.get("bucket"),
            "cats": cats, "series": series, "total": _num(obj.get("total")), "fmt": obj.get("fmt") if obj.get("fmt") in FMTS else "num",
            "other": obj.get("other") if isinstance(obj.get("other"), int) else 0, "truncated": obj.get("truncated") is True,
            "rows": obj.get("rows") if isinstance(obj.get("rows"), int) else None, "goal": _num(obj.get("goal")),
            "stack": obj.get("stack") is True, "cumulative": obj.get("cumulative") is True, "as_of": obj.get("as_of")}


def parse_response(obj):
    """Antwort von GET .../chart -> (chart | None, fehler | None)"""
    if not isinstance(obj, dict):
        return None, "failed"
    if obj.get("error"):
        return None, obj["error"] if isinstance(obj["error"], str) else "failed"
    ch = parse(obj.get("chart"))
    return (ch, None) if ch is not None else (None, "bad_cfg")


def error_text(err):
    return {"bad_cfg": _("Chart is not set up correctly – edit it on the web"), "unsupported": _("Chart only available on the originating server"),
            "no_access": _("No access to the data source"), "gone": _("The data source was deleted"),
            "too_big": _("Data source too large for a chart")}.get(err, _("Chart couldn’t be loaded"))


def series_color(ch, si, ci=None):
    """Farbname: Kategorie-Farbe (Kreis/eine Reihe), sonst Reihen-Farbe, sonst Reihenfolge blue, orange, ..."""
    single = len(ch["series"]) <= 1
    if ci is not None and (single or ch["type"] in ("pie", "donut")):
        c = ch["cats"][ci]["color"] if ci < len(ch["cats"]) else None
        if c:
            return c
        if ch["type"] in ("pie", "donut"):
            return SERIES_ORDER[ci % len(SERIES_ORDER)]
    s = ch["series"][si] if si < len(ch["series"]) else {}
    return s.get("color") or SERIES_ORDER[si % len(SERIES_ORDER)]


def hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def value_range(ch):
    """(min, max) der gezeichneten Werte (gestapelt: Summen; kumulativ liefert der Server schon)"""
    vals = []
    if ch["stack"] and len(ch["series"]) > 1 and ch["type"] in ("bar", "hbar", "area"):
        for i in range(len(ch["cats"])):
            pos = sum(v for s in ch["series"] for v in [s["values"][i]] if v is not None and v > 0)
            neg = sum(v for s in ch["series"] for v in [s["values"][i]] if v is not None and v < 0)
            vals += [pos, neg]
    else:
        vals = [v for s in ch["series"] for v in s["values"] if v is not None]
    if ch["goal"] is not None:
        vals.append(ch["goal"])
    if not vals:
        return 0.0, 0.0
    return min(0.0, min(vals)), max(0.0, max(vals))


def fmt(v, f, lang=None):
    if v is None:
        return "–"
    from .pagewidgets import fmt_value
    return fmt_value(v, f or "num", lang)


def table_rows(ch, lang=None):
    """Werte als Tabelle (Barrierefreiheit): [(kategorie, [werte als Text je Reihe])]"""
    return [(c["label"], [fmt(s["values"][i], ch["fmt"], lang) for s in ch["series"]]) for i, c in enumerate(ch["cats"])]


# --- Zeichnen (Cairo) ------------------------------------------------------------------------------------------------
def draw(cr, w, h, ch, dark=False, fg=(0.2, 0.2, 0.2), lang=None):
    """Diagramm in die Flaeche w x h zeichnen. fg: Vordergrundfarbe (Achsen, Text)."""
    if ch is None:
        return
    t = ch["type"]
    if t == "number":
        _draw_number(cr, w, h, ch, fg, lang)
        return
    if not ch["cats"]:
        _text(cr, _("No data"), w / 2, h / 2, fg, 13, "c")
        return
    if t in ("pie", "donut"):
        _draw_pie(cr, w, h, ch, dark, fg, lang)
        return
    _draw_xy(cr, w, h, ch, dark, fg, lang)


def _set(cr, name, dark, alpha=1.0):
    r, g, b = hex_rgb(TC[name][1 if dark else 0])
    cr.set_source_rgba(r, g, b, alpha)


def _text(cr, s, x, y, fg, size=11, align="l", alpha=0.85, maxw=None):
    cr.select_font_face("Sans")
    cr.set_font_size(size)
    while maxw and len(s) > 3 and cr.text_extents(s).x_advance > maxw:
        s = s[:-2] + "…" if not s.endswith("…") else s[:-2] + "…"
    ext = cr.text_extents(s)
    dx = {"l": 0, "c": -ext.x_advance / 2, "r": -ext.x_advance}[align]
    cr.set_source_rgba(fg[0], fg[1], fg[2], alpha)
    cr.move_to(x + dx, y + size * 0.35)
    cr.show_text(s)


def _draw_number(cr, w, h, ch, fg, lang):
    _text(cr, fmt(ch["total"], ch["fmt"], lang), w / 2, h / 2 - 6, fg, 34, "c", 0.95)
    if ch["goal"] is not None:
        _text(cr, _("Goal: {value}").format(value=fmt(ch["goal"], ch["fmt"], lang)), w / 2, h / 2 + 26, fg, 12, "c", 0.6)


def _draw_pie(cr, w, h, ch, dark, fg, lang):
    vals = [max(0.0, v or 0.0) for v in ch["series"][0]["values"]]
    tot = sum(vals)
    legend_w = min(220, w * 0.4)
    cx, cy = (w - legend_w) / 2, h / 2
    r = max(10, min(cx, cy) - 10)
    a0 = -math.pi / 2
    if tot <= 0:
        cr.set_source_rgba(fg[0], fg[1], fg[2], 0.12)
        cr.arc(cx, cy, r, 0, 2 * math.pi)
        cr.fill()
    for i, v in enumerate(vals):
        if v <= 0:
            continue
        a1 = a0 + v / tot * 2 * math.pi
        _set(cr, series_color(ch, 0, i), dark)
        cr.move_to(cx, cy)
        cr.arc(cx, cy, r, a0, a1)
        cr.close_path()
        cr.fill()
        a0 = a1
    if ch["type"] == "donut":
        import cairo
        cr.set_operator(cairo.OPERATOR_CLEAR)
        cr.arc(cx, cy, r * 0.58, 0, 2 * math.pi)
        cr.fill()
        cr.set_operator(cairo.OPERATOR_OVER)
        if ch["total"] is not None:
            _text(cr, fmt(ch["total"], ch["fmt"], lang), cx, cy, fg, 15, "c", 0.95)
    y = 16
    for i, c in enumerate(ch["cats"][:14]):
        _set(cr, series_color(ch, 0, i), dark)
        cr.rectangle(w - legend_w + 4, y - 5, 10, 10)
        cr.fill()
        v = ch["series"][0]["values"][i]
        pct = f" ({round(v / tot * 100)} %)" if tot > 0 and v else ""
        _text(cr, f"{c['label']} · {fmt(v, ch['fmt'], lang)}{pct}", w - legend_w + 20, y, fg, 11, "l", 0.85, legend_w - 24)
        y += 18


def _draw_xy(cr, w, h, ch, dark, fg, lang):
    t = ch["type"]
    horiz = t == "hbar"
    lo, hi = value_range(ch)
    ticks = nice_ticks(lo, hi)
    vmin, vmax = ticks[0], ticks[-1]
    if vmax == vmin:
        vmax = vmin + 1
    n = len(ch["cats"])
    multi = len(ch["series"]) > 1
    legend_h = 20 if multi else 0
    left = 120 if horiz else 56
    bottom = 22 if horiz else 34
    top = 10 + legend_h
    right = 12
    pw_, ph_ = max(10, w - left - right), max(10, h - top - bottom)

    def vpos(v):
        f = (v - vmin) / (vmax - vmin)
        return left + f * pw_ if horiz else top + ph_ - f * ph_

    # Gitter + Achsenwerte
    cr.set_line_width(1)
    for tv in ticks:
        p = vpos(tv)
        cr.set_source_rgba(fg[0], fg[1], fg[2], 0.10 if tv != 0 else 0.35)
        if horiz:
            cr.move_to(p + 0.5, top)
            cr.line_to(p + 0.5, top + ph_)
        else:
            cr.move_to(left, p + 0.5)
            cr.line_to(left + pw_, p + 0.5)
        cr.stroke()
        lab = fmt(tv, ch["fmt"], lang)
        if horiz:
            _text(cr, lab, p, top + ph_ + 12, fg, 10, "c", 0.6)
        else:
            _text(cr, lab, left - 6, p, fg, 10, "r", 0.6)
    band = (ph_ if horiz else pw_) / max(1, n)
    # Kategorien beschriften (bei vielen nur jede k-te)
    every = max(1, int(math.ceil(n / max(1, (ph_ if horiz else pw_) / (18 if horiz else 60)))))
    for i, c in enumerate(ch["cats"]):
        if i % every:
            continue
        mid = (top if horiz else left) + band * (i + 0.5)
        if horiz:
            _text(cr, c["label"], left - 6, mid, fg, 10, "r", 0.75, left - 10)
        else:
            _text(cr, c["label"], mid, top + ph_ + 14, fg, 10, "c", 0.75, band * every - 4)
    zero = vpos(max(vmin, min(0, vmax)))
    ns = len(ch["series"])
    if t in ("bar", "hbar"):
        stack = ch["stack"] and multi
        for i in range(n):
            base_pos, base_neg = 0.0, 0.0
            for si, s in enumerate(ch["series"]):
                v = s["values"][i]
                if v is None:
                    continue
                _set(cr, series_color(ch, si, i), dark, 0.9)
                if stack:
                    a = base_pos if v >= 0 else base_neg
                    b = a + v
                    if v >= 0:
                        base_pos = b
                    else:
                        base_neg = b
                    off, thick = band * 0.18, band * 0.64
                else:
                    a, b = 0.0, v
                    thick = band * 0.7 / ns
                    off = band * 0.15 + thick * si
                p1, p2 = vpos(a), vpos(b)
                start = (top if horiz else left) + band * i + off
                if horiz:
                    cr.rectangle(min(p1, p2), start, abs(p2 - p1), max(1, thick - 1))
                else:
                    cr.rectangle(start, min(p1, p2), max(1, thick - 1), abs(p2 - p1))
                cr.fill()
    else:
        acc = [0.0] * n
        for si, s in enumerate(ch["series"]):
            pts = []
            for i, v in enumerate(s["values"]):
                if v is None:
                    pts.append(None)
                    continue
                base = acc[i] if (ch["stack"] and t == "area" and multi) else 0.0
                pts.append((left + band * (i + 0.5), vpos(base + v), vpos(base)))
                if ch["stack"] and t == "area" and multi:
                    acc[i] += v
            col = series_color(ch, si)
            segs, cur = [], []
            for p in pts:
                if p is None:
                    if cur:
                        segs.append(cur)
                    cur = []
                else:
                    cur.append(p)
            if cur:
                segs.append(cur)
            for seg in segs:
                if t == "area":
                    _set(cr, col, dark, 0.22)
                    cr.move_to(seg[0][0], seg[0][2])
                    for x, y, _b in seg:
                        cr.line_to(x, y)
                    for x, _y, b in reversed(seg):
                        cr.line_to(x, b)
                    cr.close_path()
                    cr.fill()
                _set(cr, col, dark)
                cr.set_line_width(2.2)
                cr.move_to(seg[0][0], seg[0][1])
                for x, y, _b in seg[1:]:
                    cr.line_to(x, y)
                cr.stroke()
                if len(seg) == 1 or n <= 24:
                    for x, y, _b in seg:
                        cr.arc(x, y, 2.6, 0, 2 * math.pi)
                        cr.fill()
    if ch["goal"] is not None:                           # Ziellinie
        g = vpos(ch["goal"])
        _set(cr, "red", dark, 0.8)
        cr.set_line_width(1.5)
        cr.set_dash([5, 4])
        if horiz:
            cr.move_to(g, top)
            cr.line_to(g, top + ph_)
        else:
            cr.move_to(left, g)
            cr.line_to(left + pw_, g)
        cr.stroke()
        cr.set_dash([])
    del zero
    if multi:                                            # Legende oben
        x = left
        for si, s in enumerate(ch["series"]):
            _set(cr, series_color(ch, si), dark)
            cr.rectangle(x, 6, 10, 10)
            cr.fill()
            lab = s["label"] or (_("Empty") if s["key"] in ("", None) and si else _("Other") if s["key"] == "__other__" else s["key"])
            _text(cr, lab, x + 14, 11, fg, 11, "l", 0.85, 140)
            cr.select_font_face("Sans")
            cr.set_font_size(11)
            x += 14 + min(140, cr.text_extents(lab).x_advance) + 16
            if x > w - 60:
                break
