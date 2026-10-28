"""Widgets Etappe 2 (Saga 2.5, Paket 4): Wetter, Gewohnheiten (abhaken), Anstehendes, Diagramm - Inhalt einer Karte.
Daten kommen fertig vom Server (W.data); hier nur Anzeige. Logik ohne GTK: weather_icon, upcoming_groups."""
import datetime as _dt

from gi.repository import Gtk, Pango

from .i18n import _

# WMO-Code -> (ab code, Adwaita-Symbol, Text) - groesster Eintrag <= code (gemeinsame Tabelle APP-API)
WMO = [(0, "weather-clear-symbolic", "clear"), (1, "weather-few-clouds-symbolic", "mainly clear"),
       (2, "weather-few-clouds-symbolic", "partly cloudy"), (3, "weather-overcast-symbolic", "overcast"),
       (45, "weather-fog-symbolic", "fog"), (51, "weather-showers-scattered-symbolic", "drizzle"),
       (61, "weather-showers-symbolic", "rain"), (66, "weather-showers-symbolic", "freezing rain"),
       (71, "weather-snow-symbolic", "snow"), (77, "weather-snow-symbolic", "snow grains"),
       (80, "weather-showers-scattered-symbolic", "rain showers"), (85, "weather-snow-symbolic", "snow showers"),
       (95, "weather-storm-symbolic", "thunderstorm")]


def weather_icon(code):
    """WMO-Code -> Adwaita-Symbolname (unbekannt/ungueltig: bedeckt)"""
    if isinstance(code, bool) or not isinstance(code, (int, float)):
        return "weather-overcast-symbolic"
    icon = WMO[0][1]
    for start, name, _t in WMO:
        if code >= start:
            icon = name
    return icon


def weather_text(code):
    names = {"clear": _("Clear"), "mainly clear": _("Mainly clear"), "partly cloudy": _("Partly cloudy"), "overcast": _("Overcast"),
             "fog": _("Fog"), "drizzle": _("Drizzle"), "rain": _("Rain"), "freezing rain": _("Freezing rain"), "snow": _("Snow"),
             "snow grains": _("Snow grains"), "rain showers": _("Rain showers"), "snow showers": _("Snow showers"),
             "thunderstorm": _("Thunderstorm")}
    if isinstance(code, bool) or not isinstance(code, (int, float)):
        return ""
    key = WMO[0][2]
    for start, _n, t in WMO:
        if code >= start:
            key = t
    return names[key]


def _deg(v, units):
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return "–"
    return f"{round(v)}°" + ("F" if units == "imperial" else "")


def upcoming_groups(items, today=None):
    """Eintraege nach Tag: [("overdue"|"today"|"tomorrow"|datum, [item])] - Reihenfolge wie vom Server"""
    today = today or _dt.date.today()
    out = []
    for it in items if isinstance(items, list) else []:
        if not isinstance(it, dict):
            continue
        try:
            d = _dt.date.fromisoformat(str(it.get("start") or "")[:10])
        except ValueError:
            continue
        if it.get("overdue") is True or d < today:
            key = "overdue"
        elif d == today:
            key = "today"
        elif d == today + _dt.timedelta(days=1):
            key = "tomorrow"
        else:
            key = d.isoformat()
        if out and out[-1][0] == key:
            out[-1][1].append(it)
        else:
            out.append((key, [it]))
    return out


def _cap(t, dim=True):
    lb = Gtk.Label(label=t, xalign=0, wrap=True)
    lb.add_css_class("caption")
    if dim:
        lb.add_css_class("dim-label")
    return lb


def weather_box(data):
    from .util import fmt_date
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
    units = data.get("units")
    now = data.get("now") if isinstance(data.get("now"), dict) else {}
    top = Gtk.Box(spacing=10)
    ic = Gtk.Image.new_from_icon_name(weather_icon(now.get("code")))
    ic.set_pixel_size(40)
    top.append(ic)
    col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
    t = Gtk.Label(label=_deg(now.get("t"), units), xalign=0)
    t.add_css_class("hk-wx-now")
    col.append(t)
    wind = now.get("wind")
    sub = weather_text(now.get("code"))
    if isinstance(wind, (int, float)) and not isinstance(wind, bool):
        sub += " · " + _("Wind {v} {unit}").format(v=round(wind), unit="mph" if units == "imperial" else "km/h")
    col.append(_cap(sub))
    top.append(col)
    if isinstance(data.get("place"), str) and data["place"]:
        pl = Gtk.Label(label=data["place"], xalign=1, hexpand=True, valign=Gtk.Align.START)
        pl.add_css_class("dim-label")
        top.append(pl)
    box.append(top)
    days = [d for d in data.get("days") or [] if isinstance(d, dict)]
    if len(days) > 1:
        row = Gtk.Box(spacing=4, homogeneous=True)
        for d in days[1:8]:
            c = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
            try:
                dd = _dt.date.fromisoformat(str(d.get("d"))[:10])
                c.append(_cap(fmt_date(dd, "%a")))
            except ValueError:
                c.append(_cap(""))
            im = Gtk.Image.new_from_icon_name(weather_icon(d.get("code")))
            im.set_tooltip_text(weather_text(d.get("code")))
            c.append(im)
            c.append(_cap(f"{_deg(d.get('max'), units)} / {_deg(d.get('min'), units)}", dim=False))
            if isinstance(d.get("rain"), (int, float)) and not isinstance(d.get("rain"), bool):
                c.append(_cap(f"💧 {round(d['rain'])} %"))
            for x in (c.get_first_child(),):
                x.set_xalign(0.5)
            row.append(c)
        box.append(row)
    if data.get("stale") is True:
        box.append(_cap(_("Last known weather – the service is not answering right now")))
    box.append(_cap(_("Weather data: Open-Meteo.com")))           # Pflicht (CC BY 4.0)
    return box


def habit_box(data, on_toggle=None):
    """Raster: Zeile je Gewohnheit, Spalte je Tag; Abhaken nur bei can_check"""
    from .util import fmt_date
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
    days = [str(d) for d in data.get("days") or []]
    can = data.get("can_check") is True and on_toggle is not None
    grid = Gtk.Grid(column_spacing=3, row_spacing=3)
    show = days[-14:]
    off = len(days) - len(show)
    for j, d in enumerate(show):
        try:
            lab = fmt_date(_dt.date.fromisoformat(d), "%a")[:2]
        except ValueError:
            lab = ""
        lb = _cap(lab)
        lb.set_xalign(0.5)
        grid.attach(lb, j + 1, 0, 1, 1)
    habits = [h for h in data.get("habits") or [] if isinstance(h, dict)]
    for i, h in enumerate(habits):
        nm = Gtk.Label(label=h.get("name") or "", xalign=0, ellipsize=Pango.EllipsizeMode.END, max_width_chars=16)
        grid.attach(nm, 0, i + 1, 1, 1)
        done = h.get("done") if isinstance(h.get("done"), list) else []
        for j, d in enumerate(show):
            on = bool(done[off + j]) if off + j < len(done) else False
            b = Gtk.ToggleButton(active=on)
            b.add_css_class("hk-habit-cell")
            if on:
                b.add_css_class("hk-on")
            b.set_tooltip_text(f"{h.get('name') or ''} · {d}")
            b.set_sensitive(can)
            b.hk_habit = (h.get("pid"), d)
            if can:
                b.connect("toggled", lambda bt, pid=h.get("pid"), d=d: on_toggle(pid, d, bt.get_active()))
            grid.attach(b, j + 1, i + 1, 1, 1)
        st = []
        if isinstance(h.get("streak"), int):
            st.append(_("{n} in a row").format(n=h["streak"]))
        if isinstance(h.get("pct"), int):
            st.append(f"{h['pct']} %")
        grid.attach(_cap(" · ".join(st)), len(show) + 1, i + 1, 1, 1)
    box.append(grid)
    if not habits:
        box.append(_cap(_("No habits selected")))
    return box


def upcoming_box(data, open_note=None):
    from .util import day_text, time_text
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
    groups = upcoming_groups(data.get("items"))
    for key, its in groups:
        title = {"overdue": _("Overdue"), "today": _("Today"), "tomorrow": _("Tomorrow")}.get(key) or day_text(key)
        h = Gtk.Label(label=title, xalign=0)
        h.add_css_class("hk-up-day")
        if key == "overdue":
            h.add_css_class("hk-up-over")
        box.append(h)
        for it in its:
            r = Gtk.Box(spacing=6)
            ic = {"event": "x-office-calendar-symbolic", "task": "checkbox-symbolic", "row": "saga-database-symbolic"}.get(it.get("k"),
                                                                                                                        "x-office-calendar-symbolic")
            r.append(Gtk.Image.new_from_icon_name(ic))
            when = ""
            if it.get("allday") is not True and isinstance(it.get("start"), str) and len(it["start"]) >= 16:
                try:
                    dtv = _dt.datetime.fromisoformat(it["start"]).astimezone()
                    when = time_text(dtv) + " "
                except ValueError:
                    when = ""
            lb = Gtk.Label(label=when + (it.get("title") or _("Untitled")), xalign=0, ellipsize=Pango.EllipsizeMode.END, hexpand=True)
            r.append(lb)
            if isinstance(it.get("cal"), str) and it["cal"]:
                r.append(_cap(it["cal"]))
            if isinstance(it.get("note"), int) and open_note is not None:
                b = Gtk.Button(child=r)
                b.add_css_class("flat")
                b.connect("clicked", lambda *_, n=it["note"]: open_note(n))
                box.append(b)
            else:
                box.append(r)
    if not groups:
        box.append(_cap(_("Nothing coming up")))
    more = data.get("more")
    if isinstance(more, int) and more > 0:
        box.append(_cap(_("{n} more").format(n=more)))
    if isinstance(data.get("errors"), int) and data["errors"] > 0:
        box.append(_cap(_("Some sources couldn’t be reached")))
    return box


def chart_box(data):
    from . import chart as C
    from .dbviews25 import ChartCanvas
    ch = C.parse(data)
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
    if ch is None:
        box.append(_cap(C.error_text("bad_cfg")))
        return box
    cv = ChartCanvas(ch, height=200 if ch["type"] != "number" else 110)
    box.append(cv)
    return box
