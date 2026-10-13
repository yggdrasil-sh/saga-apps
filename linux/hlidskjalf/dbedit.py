"""Datenbanken bearbeiten wie im Web: Ansichten, Filter, Sortierung, Spalten, Berechnungen (DatabaseEdit.swift)."""
import statistics

from gi.repository import Adw, Gtk, Pango

from .blocks import chip, flat_button, menu_popover
from .database import PROP_ICON, has_value
from .store import label, store
from .util import COLOR_NAMES, COLOR_ORDER, day_text, iso_day, new_pid, num_text, parse_day

DB_TYPES = [("text", "Text"), ("number", "Zahl"), ("select", "Auswahl"), ("multi_select", "Mehrfachauswahl"), ("date", "Datum"),
            ("checkbox", "Häkchen"), ("url", "URL"), ("created", "Erstellt"), ("edited", "Bearbeitet"), ("calc", "Berechnet"),
            ("relation", "Relation")]
CALC_OPS = [("div", "A ÷ B"), ("pct", "A in % von B"), ("mul", "A × B"), ("add", "A + B"), ("sub", "A − B")]
VIEW_TYPES = [("table", "Tabelle", "view-grid-symbolic"), ("board", "Board", "view-dual-symbolic"), ("list", "Liste", "view-list-symbolic"),
              ("gallery", "Galerie", "view-app-grid-symbolic"), ("calendar", "Kalender", "x-office-calendar-symbolic")]
CALC_NAMES = {"count": "Anzahl", "filled": "Ausgefüllt", "empty": "Leer", "pct_filled": "% ausgefüllt", "unique": "Verschiedene",
              "sum": "Summe", "avg": "Durchschnitt", "median": "Median", "min": "Minimum", "max": "Maximum", "range": "Spanne",
              "checked": "Angehakt", "unchecked": "Nicht angehakt", "pct_checked": "% angehakt", "earliest": "Frühestes", "latest": "Spätestes"}
NO_VALUE = {"is_empty", "not_empty", "checked", "unchecked", "today", "this_week", "this_month"}


def filter_ops(t):
    empty = [("is_empty", "ist leer"), ("not_empty", "ist nicht leer")]
    rel = [("today", "ist heute"), ("this_week", "ist diese Woche"), ("this_month", "ist diesen Monat")]
    if t in ("number", "calc"):
        return [("eq", "="), ("gt", ">"), ("lt", "<")] + empty
    if t == "select":
        return [("is", "ist"), ("is_not", "ist nicht")] + empty
    if t == "multi_select":
        return [("contains", "enthält"), ("not_contains", "enthält nicht")] + empty
    if t == "relation":
        return empty
    if t == "date":
        return [("eq", "am"), ("before", "vor"), ("after", "nach")] + rel + empty
    if t in ("created", "edited"):
        return [("after", "nach"), ("before", "vor"), ("eq", "am")] + rel
    if t == "checkbox":
        return [("checked", "angehakt"), ("unchecked", "nicht angehakt")]
    return [("contains", "enthält"), ("not_contains", "enthält nicht")] + empty


def dropdown(pairs, current, on_change, width=None):
    """Gtk.DropDown aus [(wert, text)]"""
    dd = Gtk.DropDown.new_from_strings([t for _, t in pairs])
    vals = [v for v, _ in pairs]
    if current in vals:
        dd.set_selected(vals.index(current))
    if width:
        dd.set_size_request(width, -1)
    dd.connect("notify::selected", lambda d, _: d.get_selected() < len(vals) and vals[d.get_selected()] != current and on_change(vals[d.get_selected()]))
    return dd


def section(title):
    lb = Gtk.Label(label=title, xalign=0, margin_top=10)
    lb.add_css_class("heading")
    return lb


class ViewBar(Gtk.Box):
    """Reiter der Ansichten, Suche, Einstellungen, "Neu ▾" mit Vorlagen"""

    def __init__(self, dv):
        super().__init__(spacing=6)
        self.dv = dv
        self.m = dv.m
        sw = Gtk.ScrolledWindow(vscrollbar_policy=Gtk.PolicyType.NEVER, hexpand=True, propagate_natural_width=True)
        self.tabs = Gtk.Box(spacing=2)
        sw.set_child(self.tabs)
        self.append(sw)
        self.search = Gtk.SearchEntry(placeholder_text="Suchen", width_chars=12)
        self.search.connect("search-changed", self._search)
        self.append(self.search)
        self.settings = Gtk.MenuButton(icon_name="emblem-system-symbolic", tooltip_text="Ansicht: Filter, Sortierung, Eigenschaften")
        self.settings.add_css_class("flat")
        self.settings.set_create_popup_func(self._settings_popup)
        self.append(self.settings)
        self.new_slot = Gtk.Box()
        self.append(self.new_slot)
        self._sig = None

    def _search(self, e):
        self.m.search = e.get_text()
        self.dv.render()

    @property
    def editable(self):
        return not self.m.locked and not self.dv.local_readonly

    def update(self):
        while (c := self.tabs.get_first_child()):
            self.tabs.remove(c)
        cur = (self.dv.view or {}).get("id")
        for v in self.dv.views:
            vid = v.get("id")
            bx = Gtk.Box(spacing=5)
            bx.append(Gtk.Image.new_from_icon_name(next((t[2] for t in VIEW_TYPES if t[0] == v.get("type")), "view-grid-symbolic")))
            bx.append(Gtk.Label(label=v.get("name") or "Ansicht"))
            b = Gtk.ToggleButton(child=bx, active=vid == cur)
            b.add_css_class("flat")
            b.add_css_class("hk-viewtab")
            b.connect("clicked", lambda *_, vid=vid: self.dv.set_view(vid))
            if self.editable:
                g = Gtk.GestureClick(button=3)
                g.connect("pressed", lambda gs, n, x, y, v=v, b=b: self._tab_menu(b, v))
                b.add_controller(g)
            self.tabs.append(b)
        if self.editable:
            add = Gtk.MenuButton(icon_name="list-add-symbolic", tooltip_text="Ansicht hinzufügen")
            add.add_css_class("flat")
            add.set_create_popup_func(lambda mb: mb.set_popover(menu_popover(None, [(t[1], (lambda t=t[0]: self._add_view(t))) for t in VIEW_TYPES])))
            self.tabs.append(add)
        self.settings.set_visible(self.editable)
        while (c := self.new_slot.get_first_child()):
            self.new_slot.remove(c)
        if self.m.templates:
            sb = Adw.SplitButton(label="Neu")
            sb.add_css_class("suggested-action")
            sb.connect("clicked", lambda *_: self.dv.new_entry())
            ents = [("Leerer Eintrag", lambda: self.dv.new_entry()), None]
            for t in self.m.templates:
                ic = t.get("icon") or ""
                name = ((ic + " ") if ic and len(ic) <= 4 else "") + (t.get("title") or "Vorlage")
                ents.append((name, (lambda tid=t["id"]: self.dv.new_entry(tid))))
            sb.set_popover(menu_popover(None, ents))
            self.new_slot.append(sb)
        else:
            nb = Gtk.Button(label="Neu")
            nb.add_css_class("suggested-action")
            nb.connect("clicked", lambda *_: self.dv.new_entry())
            self.new_slot.append(nb)

    def _add_view(self, t):
        vid = "v" + new_pid()[:7]
        self.dv.current = vid
        self.m.mutate_views(lambda vs: vs.append({"id": vid, "name": next(x[1] for x in VIEW_TYPES if x[0] == t), "type": t}))

    def _tab_menu(self, b, v):
        vid = v.get("id")

        def rename():
            dlg = Adw.AlertDialog(heading="Ansicht umbenennen")
            en = Gtk.Entry(text=v.get("name") or "")
            dlg.set_extra_child(en)
            dlg.add_response("cancel", "Abbrechen")
            dlg.add_response("ok", "Sichern")
            dlg.set_default_response("ok")
            dlg.set_response_appearance("ok", Adw.ResponseAppearance.SUGGESTED)
            dlg.connect("response", lambda d, r: r == "ok" and self.m.edit_view(vid, lambda x: x.__setitem__("name", en.get_text().strip() or "Ansicht")))
            dlg.present(self.dv.win)

        def dup():
            if len(self.dv.views) >= 10:
                return
            nid = "v" + new_pid()[:7]
            cp = dict(v, id=nid, name=f"{v.get('name') or 'Ansicht'} (Kopie)")
            self.dv.current = nid
            self.m.mutate_views(lambda vs: vs.append(cp))
        ents = [("Umbenennen", rename), ("Duplizieren", dup),
                ("Layout", [(t[1], (lambda t=t[0]: self.m.edit_view(vid, lambda x: x.__setitem__("type", t)))) for t in VIEW_TYPES])]
        if len(self.dv.views) > 1:
            ents += [None, ("Ansicht löschen", lambda: (setattr(self.dv, "current", None),
                                                         self.m.mutate_views(lambda vs: vs.__setitem__(slice(None), [x for x in vs if x.get("id") != vid]))), True)]
        menu_popover(b, ents).popup()

    def _settings_popup(self, mb):
        pop = Gtk.Popover()
        sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True, max_content_height=560)
        sw.set_size_request(420, -1)
        holder = Gtk.Box()
        sw.set_child(holder)
        pop.set_child(sw)

        def fill():
            while (c := holder.get_first_child()):
                holder.remove(c)
            v = self.dv.view
            if v:
                holder.append(ViewSettings(self.m, v.get("id"), refill))

        def refill():
            from gi.repository import GLib
            GLib.idle_add(lambda: (fill(), False)[1])
        fill()
        mb.set_popover(pop)


class ViewSettings(Gtk.Box):
    def __init__(self, m, vid, refill):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=8, margin_bottom=12, margin_start=12, margin_end=12)
        self.m, self.vid, self.refill = m, vid, refill
        v = next((x for x in m.all_views() if x.get("id") == vid), {"id": vid, "type": "table"})
        t = v.get("type") or "table"
        selects = [p for p in m.props if p.get("type") == "select"]

        def setv(change, rebuild=True):
            m.edit_view(vid, change)
            if rebuild:
                refill()

        self.append(section("Ansicht"))
        en = Gtk.Entry(text=v.get("name") or "")
        en.connect("activate", lambda e: setv(lambda d: d.__setitem__("name", e.get_text().strip() or "Ansicht"), False))
        self.append(self._row("Name", en))
        self.append(self._row("Layout", dropdown([(x[0], x[1]) for x in VIEW_TYPES], t, lambda val: setv(lambda d: d.__setitem__("type", val)))))
        if t == "board" and selects:
            self.append(self._row("Gruppieren nach", dropdown([(p["id"], p["name"]) for p in selects], v.get("group_by") or selects[0]["id"],
                                                              lambda val: setv(lambda d: d.__setitem__("group_by", val)))))
        if t in ("table", "list"):
            self.append(self._row("Gruppieren", dropdown([("", "nicht gruppieren")] + [(p["id"], p["name"]) for p in selects], v.get("group") or "",
                                                         lambda val: setv(lambda d: _set(d, "group", val)))))
        if t == "calendar":
            dates = [p for p in m.props if p.get("type") in ("date", "created", "edited")]
            if dates:
                self.append(self._row("Datum", dropdown([(p["id"], p["name"]) for p in dates], v.get("date_by") or dates[0]["id"],
                                                        lambda val: setv(lambda d: d.__setitem__("date_by", val)))))
        if t == "gallery":
            self.append(self._row("Kartengröße", dropdown([("", "Mittel"), ("s", "Klein"), ("l", "Groß")], v.get("card") or "",
                                                          lambda val: setv(lambda d: _set(d, "card", val)))))
        self.append(self._row("Einträge öffnen", dropdown([("", "Seitenansicht"), ("center", "Mitte"), ("page", "Ganze Seite")], v.get("open") or "",
                                                          lambda val: setv(lambda d: _set(d, "open", val)))))
        self.append(section("Eigenschaften"))
        hidden = v.get("hidden") or []
        for p in m.props:
            r = Gtk.Box(spacing=8)
            r.append(Gtk.Image.new_from_icon_name(PROP_ICON.get(p.get("type"), "format-justify-left-symbolic")))
            r.append(Gtk.Label(label=p.get("name") or "", xalign=0, hexpand=True))
            sw = Gtk.Switch(active=p["id"] not in hidden, valign=Gtk.Align.CENTER)

            def tog(s, _, pid=p["id"]):
                def ch(d):
                    h = [x for x in (d.get("hidden") or []) if x != pid]
                    if not s.get_active():
                        h.append(pid)
                    _set(d, "hidden", h)
                setv(ch, False)
            sw.connect("notify::active", tog)
            r.append(sw)
            self.append(r)
        self.append(section("Sortierung"))
        name_pairs = [("title", "Name")] + [(p["id"], p.get("name") or "") for p in m.props]
        for i, s in enumerate(v.get("sorts") or []):
            r = Gtk.Box(spacing=6)
            r.append(dropdown(name_pairs, s.get("pid") or "title", lambda val, i=i: setv(lambda d: _at(d, "sorts", i, "pid", val)), 170))
            r.append(dropdown([("asc", "Aufsteigend"), ("desc", "Absteigend")], s.get("dir") or "asc",
                              lambda val, i=i: setv(lambda d: _at(d, "sorts", i, "dir", val))))
            r.append(flat_button(icon="window-close-symbolic", cb=lambda i=i: setv(lambda d: _drop(d, "sorts", i))))
            self.append(r)
        if len(v.get("sorts") or []) < 3:
            self.append(flat_button(icon="list-add-symbolic", label="Sortierung hinzufügen",
                                    cb=lambda: setv(lambda d: d.__setitem__("sorts", (d.get("sorts") or []) + [{"pid": "title", "dir": "asc"}]))))
        self.append(section("Filter (alle müssen zutreffen)"))
        for i, f in enumerate(v.get("filters") or []):
            self.append(self._filter_row(i, f, name_pairs, setv))
        if len(v.get("filters") or []) < 10 and m.props:
            first = m.props[0]
            self.append(flat_button(icon="list-add-symbolic", label="Filter hinzufügen",
                                    cb=lambda: setv(lambda d: d.__setitem__("filters", (d.get("filters") or []) + [
                                        {"pid": first["id"], "op": filter_ops(first.get("type"))[0][0], "value": ""}]))))

    def _row(self, title, w):
        r = Gtk.Box(spacing=8)
        r.append(Gtk.Label(label=title, xalign=0, hexpand=True))
        r.append(w)
        return r

    def _filter_row(self, i, f, name_pairs, setv):
        m = self.m
        pid = f.get("pid") or "title"
        p = {"id": "title", "name": "Name", "type": "text"} if pid == "title" else m.prop(pid)
        ptype = (p or {}).get("type") or "text"
        op = f.get("op") or ""
        r = Gtk.Box(spacing=4)

        def set_prop(val):
            tt = "text" if val == "title" else ((m.prop(val) or {}).get("type") or "text")
            setv(lambda d: _at_full(d, "filters", i, {"pid": val, "op": filter_ops(tt)[0][0], "value": ""}))
        r.append(dropdown(name_pairs, pid, set_prop, 130))
        r.append(dropdown(filter_ops(ptype), op, lambda val: setv(lambda d: _at(d, "filters", i, "op", val))))
        if op not in NO_VALUE and p:
            if ptype in ("select", "multi_select"):
                r.append(dropdown([("", "– wählen –")] + [(o["id"], o["name"]) for o in p.get("options") or []], f.get("value") or "",
                                  lambda val: setv(lambda d: _at(d, "filters", i, "value", val)), 120))
            elif ptype in ("date", "created", "edited"):
                mb = Gtk.MenuButton(label=day_text(f.get("value")) if f.get("value") else "Datum")
                pop = Gtk.Popover()
                cal = Gtk.Calendar()

                def pick(c):
                    dt = c.get_date()
                    pop.popdown()
                    setv(lambda d: _at(d, "filters", i, "value", f"{dt.get_year():04d}-{dt.get_month():02d}-{dt.get_day_of_month():02d}"))
                cal.connect("day-selected", pick)
                pop.set_child(cal)
                mb.set_popover(pop)
                r.append(mb)
            else:
                en = Gtk.Entry(text=str(f.get("value") or ""), placeholder_text="Wert", width_chars=8)
                en.connect("activate", lambda e: setv(lambda d: _at(d, "filters", i, "value", e.get_text())))
                r.append(en)
        r.append(flat_button(icon="window-close-symbolic", cb=lambda: setv(lambda d: _drop(d, "filters", i))))
        return r


def _set(d, k, v):
    if v in ("", None, []):
        d.pop(k, None)
    else:
        d[k] = v


def _at(d, k, i, field, val):
    lst = list(d.get(k) or [])
    if i < len(lst):
        lst[i] = dict(lst[i], **{field: val})
    d[k] = lst


def _at_full(d, k, i, item):
    lst = list(d.get(k) or [])
    if i < len(lst):
        lst[i] = item
    d[k] = lst


def _drop(d, k, i):
    lst = list(d.get(k) or [])
    if i < len(lst):
        lst.pop(i)
    _set(d, k, lst)


class ColumnHead(Gtk.MenuButton):
    """Spaltenkopf: Klick oeffnet die Spalten-Einstellungen"""
    __gtype_name__ = "HKColumnHead"

    def __init__(self, m, p):
        super().__init__()
        self.m, self.p = m, p
        bx = Gtk.Box(spacing=5)
        bx.append(Gtk.Image.new_from_icon_name(PROP_ICON.get(p.get("type"), "format-justify-left-symbolic")))
        bx.append(Gtk.Label(label=p.get("name") or "", xalign=0, ellipsize=Pango.EllipsizeMode.END))
        self.set_child(bx)
        self.add_css_class("flat")
        self.add_css_class("hk-colhead")
        self.set_tooltip_text(p.get("name") or "")
        self.set_sensitive(not m.locked)
        self.set_create_popup_func(lambda mb: mb.set_popover(ColumnEditor(m, p["id"])))


class ColumnEditor(Gtk.Popover):
    def __init__(self, m, pid):
        super().__init__()
        self.m, self.pid = m, pid
        self.box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=10, margin_bottom=10, margin_start=10, margin_end=10)
        self.box.set_size_request(320, -1)
        self.set_child(self.box)
        self.fill()

    def _p(self):
        return next((x for x in self.m.schema.get("props") or [] if x.get("id") == self.pid), {})

    def edit(self, change):
        self.m.edit_prop(self.pid, change)
        from gi.repository import GLib
        GLib.idle_add(lambda: (self.fill(), False)[1])

    def fill(self):
        while (c := self.box.get_first_child()):
            self.box.remove(c)
        p = self._p()
        t = p.get("type") or "text"
        en = Gtk.Entry(text=p.get("name") or "")

        def save_name(*_):
            n = en.get_text().strip()
            if n and n != p.get("name"):
                self.m.edit_prop(self.pid, lambda d: d.__setitem__("name", n))
        en.connect("activate", save_name)
        self.connect("closed", save_name)
        self.box.append(en)

        def set_type(val):
            def ch(d):
                d["type"] = val
                if val in ("select", "multi_select") and "options" not in d:
                    d["options"] = []
                if val == "calc":
                    d.setdefault("op", "pct")
                    d["fmt"], d["show"] = "percent", "ring"
            self.edit(ch)
        types = [x for x in DB_TYPES if x[0] != "relation" or t == "relation"]
        self.box.append(_labeled("Typ", dropdown(types, t, set_type)))
        if t in ("select", "multi_select"):
            self.box.append(section("Optionen"))
            for i, o in enumerate(p.get("options") or []):
                r = Gtk.Box(spacing=6)
                r.append(chip(o.get("name") or "", o.get("color")))
                r.append(Gtk.Box(hexpand=True))
                mb = Gtk.MenuButton(icon_name="view-more-symbolic")
                mb.add_css_class("flat")

                def popup(b, i=i):
                    ents = [(COLOR_NAMES[c], (lambda c=c: self.edit(lambda d: _opt(d, i, "color", c)))) for c in COLOR_ORDER]
                    ents += [None, ("Option löschen", lambda: self.edit(lambda d: d.__setitem__("options", [x for k, x in enumerate(d.get("options") or []) if k != i])), True)]
                    b.set_popover(menu_popover(None, ents))
                mb.set_create_popup_func(popup)
                r.append(mb)
                self.box.append(r)
            ne = Gtk.Entry(placeholder_text="Neue Option")

            def add(*_):
                n = ne.get_text().strip()
                if n:
                    c = COLOR_ORDER[len(p.get("options") or []) % len(COLOR_ORDER)]
                    self.edit(lambda d: d.__setitem__("options", (d.get("options") or []) + [{"id": new_pid(), "name": n, "color": c}]))
            ne.connect("activate", add)
            self.box.append(ne)
        if t in ("number", "calc"):
            self.box.append(_labeled("Format", dropdown([("", "Zahl"), ("percent", "Prozent"), ("euro", "Euro")], p.get("fmt") or "",
                                                       lambda val: self.edit(lambda d: _set(d, "fmt", val)))))
            self.box.append(_labeled("Anzeigen als", dropdown([("", "Zahl"), ("ring", "Ring"), ("bar", "Balken")], p.get("show") or "",
                                                             lambda val: self.edit(lambda d: _set(d, "show", val)))))
        if t == "calc":
            nums = [("", "–")] + [(x["id"], x.get("name") or "") for x in self.m.props if x.get("type") == "number"]
            self.box.append(_labeled("Rechnung", dropdown(CALC_OPS, p.get("op") or "div", lambda val: self.edit(lambda d: d.__setitem__("op", val)))))
            self.box.append(_labeled("Zahl A", dropdown(nums, p.get("a") or "", lambda val: self.edit(lambda d: _set(d, "a", val)))))
            self.box.append(_labeled("Zahl B", dropdown(nums, p.get("b") or "", lambda val: self.edit(lambda d: _set(d, "b", val)))))
        dl = Gtk.Button(label="Spalte löschen", margin_top=8)
        dl.add_css_class("destructive-action")
        dl.connect("clicked", lambda *_: (self.popdown(), self.m.edit_schema(
            lambda sc: sc.__setitem__("props", [x for x in sc.get("props") or [] if x.get("id") != self.pid]))))
        self.box.append(dl)


def _opt(d, i, k, v):
    os_ = list(d.get("options") or [])
    if i < len(os_):
        os_[i] = dict(os_[i], **{k: v})
    d["options"] = os_


def _labeled(t, w):
    r = Gtk.Box(spacing=8)
    r.append(Gtk.Label(label=t, xalign=0, hexpand=True))
    r.append(w)
    return r


class AddColumnButton(Gtk.MenuButton):
    __gtype_name__ = "HKAddColumn"

    def __init__(self, m):
        super().__init__(icon_name="list-add-symbolic", tooltip_text="Spalte hinzufügen", valign=Gtk.Align.CENTER)
        self.m = m
        self.add_css_class("flat")
        self.set_create_popup_func(self._popup)

    def _popup(self, mb):
        ents = [(n, (lambda t=t: self.add(t))) for t, n in DB_TYPES if t != "relation"]
        ents.append(("Relation", [(label(d), (lambda d=d: self.add("relation", d["id"], label(d)))) for d in store.databases()] or [("Keine Datenbanken", lambda: None)]))
        mb.set_popover(menu_popover(None, ents))

    def add(self, t, target=None, tname=None):
        m = self.m
        p = {"id": new_pid(), "name": dict(DB_TYPES).get(t, "Eigenschaft"), "type": t}
        if t in ("select", "multi_select"):
            p["options"] = []
        if t == "calc":
            ns = [x for x in m.props if x.get("type") == "number"]
            p.update(name="Fortschritt", op="pct", fmt="percent", show="ring")
            if ns:
                p["a"] = ns[0]["id"]
            if len(ns) > 1:
                p["b"] = ns[1]["id"]
        if t == "relation":
            p.update(target=target, name=tname or "Relation", two_way=target != m.db_id)
        m.edit_schema(lambda sc: sc.__setitem__("props", (sc.get("props") or []) + [p]))


class CalcRow(Gtk.Box):
    """Fusszeile: Berechnung je Spalte, pro Ansicht gespeichert"""

    def __init__(self, table, m, view, cols, rows):
        super().__init__()
        self.add_css_class("hk-calcrow")
        self.m, self.view, self.rows = m, view, rows
        calcs = (view or {}).get("calcs") or {}
        for key, p in [("title", None)] + [(c["id"], c) for c in cols]:
            op = calcs.get(key)
            mb = Gtk.MenuButton()
            mb.add_css_class("flat")
            mb.add_css_class("hk-calc")
            if op:
                lb = Gtk.Label(xalign=1, ellipsize=Pango.EllipsizeMode.END)
                lb.set_markup(f'<span size="small" alpha="70%">{CALC_NAMES.get(op, op)}</span> <b>{self.value(op, p)}</b>')
            else:
                lb = Gtk.Label(label="Berechnen", xalign=1)
                lb.add_css_class("hk-calc-empty")
            mb.set_child(lb)
            mb.set_create_popup_func(lambda b, key=key, p=p: b.set_popover(menu_popover(None, [("Keine", lambda key=key: self.set(key, None))] +
                                                                                         [(CALC_NAMES[o], (lambda o=o, key=key: self.set(key, o))) for o in self.ops(p)])))
            self.append(table._cell(key, mb))

    @staticmethod
    def ops(p):
        t = "number" if p and p.get("type") == "calc" else ((p or {}).get("type") or "text")
        if t == "checkbox":
            return ["count", "checked", "unchecked", "pct_checked"]
        o = ["count", "filled", "empty", "pct_filled", "unique"]
        if t == "number":
            o += ["sum", "avg", "median", "min", "max", "range"]
        if t in ("date", "created", "edited"):
            o += ["earliest", "latest"]
        return o

    def set(self, key, op):
        vid = (self.view or {}).get("id")
        if not vid:
            return

        def ch(v):
            c = dict(v.get("calcs") or {})
            if op:
                c[key] = op
            else:
                c.pop(key, None)
            _set(v, "calcs", c)
        self.m.edit_view(vid, ch)

    def value(self, op, p):
        vals = [self.m.value(p, r) if p else r["title"] for r in self.rows]
        n = len(vals)
        filled = [v for v in vals if has_value(v) or (isinstance(v, (int, float)) and not isinstance(v, bool))]
        nums = []
        for v in vals:
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                nums.append(float(v))
            elif isinstance(v, str):
                try:
                    nums.append(float(v))
                except ValueError:
                    pass

        def fmt(x):
            x = round(x * 100) / 100
            return num_text(x, p.get("fmt")) if p and p.get("type") in ("number", "calc") else num_text(x)

        def pct(a):
            return f"{round(a / n * 100)} %" if n else "–"
        days = sorted(str(v)[:10] for v in filled if isinstance(v, str) and len(v) >= 10)
        if op == "count":
            return str(n)
        if op == "filled":
            return str(len(filled))
        if op == "empty":
            return str(n - len(filled))
        if op == "pct_filled":
            return pct(len(filled))
        if op == "unique":
            u = set()
            for v in filled:
                if isinstance(v, list):
                    u.update(map(str, v))
                else:
                    u.add(str(v))
            return str(len(u))
        if op in ("sum", "avg", "median", "min", "max", "range") and not nums:
            return "–"
        if op == "sum":
            return fmt(sum(nums))
        if op == "avg":
            return fmt(sum(nums) / len(nums))
        if op == "median":
            return fmt(statistics.median(nums))
        if op == "min":
            return fmt(min(nums))
        if op == "max":
            return fmt(max(nums))
        if op == "range":
            return fmt(max(nums) - min(nums))
        if op == "checked":
            return str(sum(1 for v in vals if v is True))
        if op == "unchecked":
            return str(sum(1 for v in vals if v is not True))
        if op == "pct_checked":
            return pct(sum(1 for v in vals if v is True))
        if op == "earliest":
            return day_text(days[0]) if days else "–"
        if op == "latest":
            return day_text(days[-1]) if days else "–"
        return ""
