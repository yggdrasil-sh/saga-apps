"""Datenbanken nativ: Modell + Filter/Sortierung wie im Web (DBQuery.swift), Ansichten, Eigenschafts-Editoren."""
import copy
import datetime as _dt
import math

from gi.repository import Gdk, GLib, GObject, Gtk, Pango

from . import images
from .api import client
from .blocks import chip, context_menu, flat_button, menu_popover, more_button
from .i18n import _
from .store import label, store, watch
from .util import bg, day_text, iso_day, lane_busy, month_text, num_text, parse_day, parse_num, weekday_names

PROP_ICON = {"text": "format-justify-left-symbolic", "number": "accessories-calculator-symbolic", "select": "go-down-symbolic",
             "multi_select": "view-list-bullet-symbolic", "checkbox": "checkbox-checked-symbolic", "date": "x-office-calendar-symbolic",
             "url": "insert-link-symbolic", "email": "mail-unread-symbolic", "phone": "call-start-symbolic", "relation": "send-to-symbolic",
             "created": "document-open-recent-symbolic", "edited": "document-open-recent-symbolic", "calc": "accessories-calculator-symbolic"}


def has_value(v):
    if v is None:
        return False
    if isinstance(v, str):
        return v != ""
    if isinstance(v, list):
        return bool(v)
    if isinstance(v, bool):
        return v
    return True


def option(p, oid):
    return next((o for o in p.get("options") or [] if o.get("id") == oid), None)


def calc_value(p, props):
    def num(k):
        if not k or k not in props or props[k] is None:
            return None
        try:
            return float(props[k])
        except (TypeError, ValueError):
            return None
    x, y, op = num(p.get("a")), num(p.get("b")), p.get("op") or "div"
    if op in ("add", "sub"):
        if x is None and y is None:
            return None
        x, y = x or 0.0, y or 0.0
    if x is None or y is None:
        return None
    if op in ("div", "pct"):
        if y == 0:
            return None
        v = x / y * (100 if op == "pct" else 1)
    elif op == "mul":
        v = x * y
    elif op == "add":
        v = x + y
    else:
        v = x - y
    return round(v * 10000) / 10000


def rel_date(op, v):
    d = None
    if isinstance(v, str) and len(v) >= 10:
        d = parse_day(v)
    elif isinstance(v, (int, float)) and not isinstance(v, bool):
        d = _dt.date.fromtimestamp(v)
    if not d:
        return False
    t = _dt.date.today()
    if op == "this_month":
        return (d.year, d.month) == (t.year, t.month)
    if op == "this_week":
        return d.isocalendar()[:2] == t.isocalendar()[:2]
    return d == t


class DBModel(GObject.Object):
    __gsignals__ = {"changed": (GObject.SignalFlags.RUN_FIRST, None, ())}

    def __init__(self, db_id):
        super().__init__()
        self.db_id = db_id
        self.schema = {}
        self.props, self.views, self.rows, self.templates = [], [], [], []
        self.loaded = False
        self.error = None
        self.search = ""
        self.local_views = None
        self.on_local_views = None
        self.role = "owner"                # Teilen (1.3): Rolle fuer diese Datenbank (Antwort von .../rows)
        self.host_locked = False           # Datenbank-Block auf einer gesperrten (bzw. nur lesbaren) Seite

    @property
    def readonly(self):
        """Fremde Datenbank mit Lesen/Kommentieren: keine Eintraege anlegen oder aendern"""
        return self.role in ("view", "comment")

    @property
    def locked(self):
        return bool(self.schema.get("locked")) or self.readonly or self.host_locked

    @property
    def can_add(self):
        """Eintraege anlegen ("Neu", "+"): nicht bei Nur-Lesen und nicht im Block einer gesperrten Seite"""
        return not self.readonly and not self.host_locked

    def load(self):
        if not self.loaded:
            c = store.cached(f"api/notes/{self.db_id}/rows")
            if isinstance(c, dict):
                self._apply(c)
                self.loaded = True
                self.emit("changed")

        def fin(j):
            if lane_busy(("note", self.db_id)):
                return                                    # Schema wird gerade gespeichert: danach kommt ein neueres Laden
            self._apply(j)
            self.loaded = True
            self.emit("changed")

        def err(e):
            self.loaded = True
            self.error = str(e)
            self.emit("changed")
        bg(store.get_cached, f"api/notes/{self.db_id}/rows", done=fin, error=err)

    def _apply(self, j):
        r = j.get("role")
        self.role = r if r in ("owner", "edit", "comment", "view") else "owner"
        self.schema = j.get("schema") or {}
        self.props = self.schema.get("props") or []
        self.views = self.schema.get("views") or []
        self.rows = []
        for r in j.get("rows") or []:
            self.rows.append({"id": r.get("id"), "title": r.get("title") or "", "icon": r.get("icon"),
                              "cover": r.get("preview") or r.get("cover") or None, "props": r.get("props") or {},
                              "created": r.get("created") or 0, "updated": r.get("updated") or 0})
        self.templates = j.get("templates") or []

    def prop(self, pid):
        return next((p for p in self.props if p.get("id") == pid), None)

    def all_views(self):
        return self.local_views if self.local_views is not None else self.views

    # --- Werte, Filter, Sortierung ----------------------------------------------------------
    def value(self, p, r):
        t = p.get("type")
        if t == "calc":
            return calc_value(p, r["props"])
        if t == "created":
            return _dt.date.fromtimestamp(r["created"]).isoformat() if r["created"] else None
        if t == "edited":
            return _dt.date.fromtimestamp(r["updated"]).isoformat() if r["updated"] else None
        v = r["props"].get(p.get("id"))
        if t == "number" and v is not None and not isinstance(v, bool):
            try:
                return float(v)
            except (TypeError, ValueError):
                return None
        return v

    def _match(self, r, f):
        pid, op = f.get("pid"), f.get("op") or ""
        p = {"id": "title", "type": "text"} if pid == "title" else self.prop(pid)
        if p is None:
            return True
        v = r["title"] if pid == "title" else self.value(p, r)
        empty = not has_value(v) if not isinstance(v, (int, float)) or isinstance(v, bool) else False
        if op == "is_empty":
            return empty
        if op == "not_empty":
            return not empty
        if op == "checked":
            return bool(v)
        if op == "unchecked":
            return not bool(v)
        if op in ("today", "this_week", "this_month"):
            return not empty and rel_date(op, v)
        fv = f.get("value")
        if fv is None or str(fv) == "":
            return True
        fv = str(fv)
        arr = [str(x) for x in v] if isinstance(v, list) else None
        s = v if isinstance(v, str) else ("" if v is None else str(v))
        num = v if isinstance(v, (int, float)) and not isinstance(v, bool) else None
        try:
            fnum = float(fv)
        except ValueError:
            fnum = None
        if op == "contains":
            return fv in arr if arr is not None else fv.lower() in s.lower()
        if op == "not_contains":
            return fv not in arr if arr is not None else fv.lower() not in s.lower()
        if op == "is":
            return s == fv
        if op == "is_not":
            return s != fv
        if op == "eq":
            return (num is not None and fnum is not None and num == fnum) if p.get("type") in ("number", "calc") else s == fv
        if op == "gt":
            return num is not None and fnum is not None and num > fnum
        if op == "lt":
            return num is not None and fnum is not None and num < fnum
        if op == "before":
            return s != "" and s < fv
        if op == "after":
            return s != "" and s > fv
        return True

    def _sort_key(self, p, r):
        if p is None:
            return r["title"].lower()
        v = self.value(p, r)
        if p.get("type") == "select" and isinstance(v, str):
            o = option(p, v)
            return f"{(p.get('options') or []).index(o):04d}" if o else None
        if isinstance(v, bool):
            return "1" if v else "0"
        if isinstance(v, (int, float)):
            return f"{v + 1e12:020.4f}"
        if isinstance(v, str):
            return v.lower() or None
        return None

    def rows_for(self, view):
        filters = (view or {}).get("filters") or []
        out = [r for r in self.rows if all(self._match(r, f) for f in filters)]
        keys = []
        for s in (view or {}).get("sorts") or []:
            pid = s.get("pid")
            p = None if pid == "title" else self.prop(pid)
            if pid != "title" and p is None:
                continue
            keys.append((p, -1 if s.get("dir") == "desc" else 1))
        if keys:
            import functools

            def cmp(a, b):
                for p, d in keys:
                    x, y = self._sort_key(p, a), self._sort_key(p, b)
                    if x == y:
                        continue
                    if x is None:
                        return 1
                    if y is None:
                        return -1
                    return (-1 if x < y else 1) * d
                return 0
            out.sort(key=functools.cmp_to_key(cmp))
        q = self.search.strip().lower()
        if q:
            def hit(r):
                if q in r["title"].lower():
                    return True
                for p in self.props:
                    v = self.value(p, r)
                    if isinstance(v, str):
                        o = option(p, v)
                        if q in ((o or {}).get("name") or v).lower():
                            return True
                    elif isinstance(v, list):
                        if any(q in ((option(p, x) or {}).get("name") or "").lower() for x in v):
                            return True
                return False
            out = [r for r in out if hit(r)]
        return out

    def color(self, r, view):
        for c in (view or {}).get("colors") or []:
            if self._match(r, c):
                return c.get("color")
        return None

    def defaults(self, view):
        d = {}
        for f in (view or {}).get("filters") or []:
            if f.get("op") not in ("is", "eq", "checked"):
                continue
            pid = f.get("pid")
            p = self.prop(pid) if pid != "title" else None
            if not p:
                continue
            if f.get("op") == "checked":
                d[pid] = True
            elif f.get("value"):
                d[pid] = [f["value"]] if p.get("type") == "multi_select" else f["value"]
        return d

    # --- Aendern ------------------------------------------------------------------------------
    def set(self, row_id, pid, value):
        if self.readonly:                  # nur lesen (1.3): Haken/Karte/Zelle nichts senden, Anzeige zuruecksetzen
            self.emit("changed")
            return
        r = next((x for x in self.rows if x["id"] == row_id), None)
        if r is not None:
            if value is None:
                r["props"].pop(pid, None)
            else:
                r["props"][pid] = value
        self.emit("changed")
        # Warteschlange je Eintrag: schnelles An/Aus kommt in der richtigen Reihenfolge an
        store.run("PATCH", f"api/notes/{row_id}", {"props": {pid: value}}, data_changed=False, key=("note", row_id),
                  done=lambda _: (store.drop_cache(f"api/notes/{row_id}"), store.emit("row-changed", int(row_id))),
                  error=lambda e: (store.emit("error", str(e)), self.load()))

    def rename(self, row_id, title):
        """Titel mit Revision speichern (ueber den offenen Peek bzw. die aktuelle Revision) - kein 409 im Peek danach"""
        if self.readonly:
            self.emit("changed")
            return
        r = next((x for x in self.rows if x["id"] == row_id), None)
        if r is not None:
            r["title"] = title
        store.rename(row_id, title)

    def add(self, props=None, done=None):
        if not self.can_add:               # nur lesen bzw. Seite gesperrt: keine Eintraege anlegen
            return
        b = {"parent_id": self.db_id, "title": ""}
        if props:
            b["props"] = props

        def fin(r):
            self.load()
            store.page_added(r["id"], self.db_id)           # neuer Eintrag lokal in die Seitenliste (nicht alles neu laden)
            if done:
                done(r["id"])
        store.run("POST", "api/notes", b, done=fin)

    def new_from_template(self, tid, done=None):
        if not self.can_add:
            return

        def fin(r):
            self.load()
            store.refresh()
            store.changed_data()
            if done:
                done(r["id"])
        store.run("POST", f"api/notes/{tid}/duplicate", {"parent_id": self.db_id}, done=fin)

    def edit_schema(self, change):
        sc = copy.deepcopy(self.schema)
        sc.setdefault("props", [])
        change(sc)
        self.schema = sc
        self.props = sc.get("props") or []
        self.views = sc.get("views") or []
        self.emit("changed")
        # ganzes Schema in der Warteschlange der Datenbank; noch wartende aeltere Fassungen werden ersetzt (coalesce),
        # damit nie ein veraltetes Schema ein neueres ueberholt
        store.run("PATCH", f"api/notes/{self.db_id}", {"schema": copy.deepcopy(sc)}, key=("note", self.db_id), coalesce="schema",
                  done=lambda r: (self._take_srev(r), self.load(), store.changed_data()),
                  error=lambda e: (store.emit("error", str(e)), self.load()))

    def _take_srev(self, r):
        """Server (BUG-001): PATCH liefert die neue Schema-Revision - fuer die naechste Aenderung uebernehmen, damit der
        Server erkennt, welche Spalten wir kennen (fremde, neuere bleiben sonst ohnehin erhalten)"""
        if isinstance(r, dict) and isinstance(r.get("srev"), int) and isinstance(self.schema, dict):
            self.schema["srev"] = r["srev"]

    def edit_prop(self, pid, change):
        def ch(sc):
            for p in sc.get("props") or []:
                if p.get("id") == pid:
                    change(p)
        self.edit_schema(ch)

    def mutate_views(self, change):
        if self.local_views is not None:
            lv = [dict(v) for v in self.local_views]
            change(lv)
            self.local_views = lv[:10]
            if self.on_local_views:
                self.on_local_views(self.local_views)
            self.emit("changed")
            return

        def ch(sc):
            vs = sc.get("views") or [{"id": "tabelle", "name": _("Table"), "type": "table"}]
            change(vs)
            sc["views"] = vs[:10]
        self.edit_schema(ch)

    def edit_view(self, vid, change):
        def ch(vs):
            for v in vs:
                if v.get("id") == vid:
                    change(v)
        self.mutate_views(ch)


# --- Anzeige-Bausteine ----------------------------------------------------------------------
class Ring(Gtk.DrawingArea):
    def __init__(self, value):
        super().__init__()
        self.value = max(0.0, min(1.0, value))
        self.set_content_width(14)
        self.set_content_height(14)
        self.set_draw_func(self._draw)

    def _draw(self, area, cr, w, h):
        r = min(w, h) / 2 - 1.5
        cr.set_line_width(2)
        cr.set_source_rgba(0.5, 0.5, 0.5, 0.35)
        cr.arc(w / 2, h / 2, r, 0, 2 * math.pi)
        cr.stroke()
        cr.set_source_rgb(0.2, 0.78, 0.35)
        cr.arc(w / 2, h / 2, r, -math.pi / 2, -math.pi / 2 + 2 * math.pi * self.value)
        cr.stroke()


def value_widget(p, v, limit=None):
    """kompakte Anzeige (Chip, Datum, Zahl) - None wenn leer"""
    t = p.get("type")
    if t == "select":
        o = option(p, v)
        return chip(o["name"], o.get("color")) if o else None
    if t == "multi_select":
        box = Gtk.Box(spacing=3)
        opts = [option(p, x) for x in v or []]
        opts = [o for o in opts if o]
        for o in opts[:limit] if limit else opts:
            box.append(chip(o["name"], o.get("color")))
        if limit and len(opts) > limit:
            box.append(chip(f"+{len(opts) - limit}", None))
        return box if box.get_first_child() else None
    if t in ("date", "created", "edited"):
        if isinstance(v, str) and v:
            lb = Gtk.Label(label=day_text(v))
            lb.add_css_class("caption")
            lb.add_css_class("dim-label")
            return lb
        return None
    if t in ("number", "calc"):
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            box = Gtk.Box(spacing=5)
            lb = Gtk.Label(label=num_text(v, p.get("fmt"), p.get("show")))
            lb.add_css_class("caption")
            lb.add_css_class("numeric")
            box.append(lb)
            if p.get("fmt") == "percent" and p.get("show") == "ring":
                box.append(Ring(v / 100))
            elif p.get("fmt") == "percent" and p.get("show") == "bar":
                lv = Gtk.LevelBar(min_value=0, max_value=1, value=max(0, min(1, v / 100)), valign=Gtk.Align.CENTER)
                lv.set_size_request(60, -1)
                box.append(lv)
            return box
        return None
    if t == "checkbox":
        return Gtk.Image.new_from_icon_name("checkbox-checked-symbolic") if v else None
    return None


def prop_editor(p, v, setv, model=None, compact=False):
    """Eigenschaft direkt bearbeiten (Tabelle + Eintragsseite)"""
    t = p.get("type")
    if t == "calc":
        w = value_widget(p, v)
        return w or _leer("–")
    if t in ("created", "edited"):
        lb = Gtk.Label(label=day_text(v) if isinstance(v, str) else "", xalign=0)
        lb.add_css_class("dim-label")
        return lb
    if t == "checkbox":
        cb = Gtk.CheckButton(active=bool(v))
        cb.connect("toggled", lambda c: setv(c.get_active()))
        return cb
    if t == "select":
        mb = Gtk.MenuButton()
        mb.add_css_class("flat")
        mb.add_css_class("hk-cellbtn")
        o = option(p, v)
        mb.set_child(chip(o["name"], o.get("color")) if o else _leer())

        def popup(m):
            ents = [(op_["name"], (lambda i=op_["id"]: setv(i))) for op_ in p.get("options") or []]
            if o:
                ents += [None, (_("Clear"), lambda: setv(None), True)]
            if model is not None:
                ents += [None, (_("New option …"), lambda: _new_option(m.get_parent() or m, model, p, lambda oid: setv(oid)))]
            m.set_popover(menu_popover(None, ents or [(_("No options"), lambda: None)]))
        mb.set_create_popup_func(popup)
        return mb
    if t == "multi_select":
        ids = [x for x in (v or []) if isinstance(x, str)]
        mb = Gtk.MenuButton()
        mb.add_css_class("flat")
        mb.add_css_class("hk-cellbtn")
        fb = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, row_spacing=3, column_spacing=3, max_children_per_line=12)
        for x in ids:
            o = option(p, x)
            if o:
                fb.append(chip(o["name"], o.get("color")))
        mb.set_child(fb if ids else _leer())
        pop = Gtk.Popover()
        bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_top=6, margin_bottom=6, margin_start=6, margin_end=6)
        cur = list(ids)
        for op_ in p.get("options") or []:
            c = Gtk.CheckButton(active=op_["id"] in cur)
            c.set_child(chip(op_["name"], op_.get("color")))

            def tg(c, oid=op_["id"]):
                if c.get_active() and oid not in cur:
                    cur.append(oid)
                elif not c.get_active() and oid in cur:
                    cur.remove(oid)
            c.connect("toggled", tg)
            bx.append(c)
        if not p.get("options"):
            bx.append(Gtk.Label(label=_("No options")))
        pop.set_child(bx)
        pop.connect("closed", lambda *_: cur != ids and setv(list(cur)))
        mb.set_popover(pop)
        return mb
    if t == "date":
        mb = Gtk.MenuButton()
        mb.add_css_class("flat")
        mb.add_css_class("hk-cellbtn")
        mb.set_child(Gtk.Label(label=day_text(v), xalign=0) if v else _leer())
        pop = Gtk.Popover()
        bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=6, margin_bottom=6, margin_start=6, margin_end=6)
        cal = Gtk.Calendar()
        d = parse_day(v)
        if d:
            cal.select_day(GLib.DateTime.new_local(d.year, d.month, d.day, 0, 0, 0))

        def pick(c):
            dt = c.get_date()
            pop.popdown()
            setv(f"{dt.get_year():04d}-{dt.get_month():02d}-{dt.get_day_of_month():02d}")
        cal.connect("day-selected", pick)
        bx.append(cal)
        row = Gtk.Box(spacing=6, homogeneous=True)
        row.append(flat_button(label=_("Today"), cb=lambda: (pop.popdown(), setv(iso_day()))))
        if v:
            row.append(flat_button(label=_("Clear"), css="error", cb=lambda: (pop.popdown(), setv(None))))
        bx.append(row)
        pop.set_child(bx)
        mb.set_popover(pop)
        return mb
    if t == "relation":
        ids = [int(x) for x in (v or []) if isinstance(x, (int, float))]
        mb = Gtk.MenuButton()
        mb.add_css_class("flat")
        mb.add_css_class("hk-cellbtn")
        if ids:
            fb = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, row_spacing=3, column_spacing=3, max_children_per_line=8)
            for i in ids:
                fb.append(chip("↗ " + label(store.meta(i)), None))       # auch geteilte Seiten und andere Server (2.3)
            mb.set_child(fb)
        else:
            mb.set_child(_leer())
        mb.set_create_popup_func(lambda m: m.set_popover(_relation_popover(m, p, ids, setv)))
        return mb
    # text, number, url, email, phone
    en = Gtk.Entry(placeholder_text=_("Empty"), hexpand=True)
    en.add_css_class("flat")
    en.add_css_class("hk-cell-entry")
    if t == "number":
        en.set_input_purpose(Gtk.InputPurpose.NUMBER)
        cur = num_text(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else ""
    else:
        cur = "" if v is None else str(v)
    en.set_text(cur)
    state = {"last": cur}

    def commit(*_):
        txt = en.get_text().strip()
        if txt == state["last"]:
            return
        state["last"] = txt
        if t == "number":
            if not txt:
                setv(None)
                return
            try:
                setv(parse_num(txt))
            except ValueError:
                en.set_text(cur)
        else:
            setv(txt or None)
    en.connect("activate", commit)
    fc = Gtk.EventControllerFocus()
    fc.connect("leave", commit)
    en.add_controller(fc)
    if t == "url" and v:
        box = Gtk.Box(spacing=2)
        box.append(en)
        box.append(flat_button(icon="adw-external-link-symbolic", tooltip=_("Open"), css="hk-tiny",
                               cb=lambda: Gtk.UriLauncher.new(str(v)).launch(en.get_root(), None, None)))
        return box
    return en


def _leer(t=None):
    lb = Gtk.Label(label=t or _("Empty"), xalign=0)
    lb.add_css_class("hk-empty")
    return lb


def _new_option(parent, model, p, then):
    from .util import new_pid, COLOR_ORDER
    pop = Gtk.Popover()
    en = Gtk.Entry(placeholder_text=_("New option"), margin_top=6, margin_bottom=6, margin_start=6, margin_end=6)

    def go(*_):
        n = en.get_text().strip()
        pop.popdown()
        if not n:
            return
        oid = new_pid()
        col = COLOR_ORDER[len(p.get("options") or []) % len(COLOR_ORDER)]
        model.edit_prop(p["id"], lambda d: d.__setitem__("options", (d.get("options") or []) + [{"id": oid, "name": n, "color": col}]))
        then(oid)
    en.connect("activate", go)
    pop.set_child(en)
    pop.set_parent(parent)
    pop.connect("closed", lambda q: GLib.idle_add(lambda: (q.unparent(), False)[1]))
    pop.popup()
    en.grab_focus()


def _relation_popover(parent, p, ids, setv):
    pop = Gtk.Popover()
    bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, margin_top=6, margin_bottom=6, margin_start=6, margin_end=6)
    se = Gtk.SearchEntry(placeholder_text=_("Search entries"))
    bx.append(se)
    lst = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
    sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True, max_content_height=320)
    sw.set_child(lst)
    sw.set_size_request(280, -1)
    bx.append(sw)
    cur = list(ids)
    rows = []

    def fill(*a):
        while (c := lst.get_first_child()):
            lst.remove(c)
        q = se.get_text().lower()
        for r in rows:
            if q and q not in (r.get("title") or "").lower():
                continue
            c = Gtk.CheckButton(label=r.get("title") or _("Untitled"), active=r["id"] in cur)

            def tg(c, rid=r["id"]):
                if c.get_active() and rid not in cur:
                    cur.append(rid)
                elif not c.get_active() and rid in cur:
                    cur.remove(rid)
            c.connect("toggled", tg)
            lst.append(c)
    se.connect("search-changed", fill)
    tgt = p.get("target")
    if tgt:
        def got(j):
            rows.extend(j.get("rows") or [])
            fill()
        bg(client.get, f"api/notes/{tgt}/rows", done=got, error=lambda e: None)
    pop.set_child(bx)
    pop.connect("closed", lambda *_: sorted(cur) != sorted(ids) and setv(list(cur)))
    return pop


class RowMenuMixin:
    def row_entries(self, r):
        rows = self.rows
        if (self.m.db_id or 0) < 0:                        # Datenbank eines anderen Servers (2.3): nicht duplizieren/loeschen
            return [(_("Open"), lambda: self.open(r, rows)), (_("Copy link"), lambda: self.win.copy_text(store.page_url(r["id"])))]
        return [(_("Open"), lambda: self.open(r, rows)),
                (_("Duplicate"), lambda: store.duplicate(r["id"], done=lambda n: self.m.load())),
                (_("Copy link"), lambda: self.win.copy_text(store.page_url(r["id"]))), None,
                (_("Move to trash"), lambda: store.trash(r["id"], done=self.m.load), True)]

    def row_menu(self, widget, r):
        menu_popover(widget, self.row_entries(r)).popup()

    def attach_menu(self, widget, r):
        """Rechtsklick, langes Druecken (Touch) und Menue-Taste/Umschalt+F10"""
        context_menu(widget, lambda w: self.row_menu(w, r))

    def more(self, r):
        """sichtbarer "⋮"-Knopf fuer das Zeilen-Menue"""
        return more_button(lambda: self.row_entries(r), _("Entry actions"))

    def open(self, r, rows=None):
        self.win.open_entry(r["id"], (self.view or {}).get("open") or "side", [x["id"] for x in (rows or self.rows)])

    def new_row(self, props=None):
        self.m.add(props if props is not None else self.m.defaults(self.view),
                   done=lambda i: self.win.open_entry(i, (self.view or {}).get("open") or "side"))


def _color_css(w, color):
    if color:
        w.add_css_class(f"hk-row-{color}")


# --- Tabelle ---------------------------------------------------------------------------------
class TableView(Gtk.ScrolledWindow, RowMenuMixin):
    __gtype_name__ = "HKTableView"
    PAGE = 100

    def __init__(self, win, m, view):
        super().__init__(vscrollbar_policy=Gtk.PolicyType.NEVER, hscrollbar_policy=Gtk.PolicyType.AUTOMATIC, propagate_natural_height=True)
        self.win, self.m, self.view = win, m, view
        self.vid = (view or {}).get("id")
        hidden = set((view or {}).get("hidden") or [])
        order = (view or {}).get("order") or []
        vis = [p for p in m.props if p.get("id") not in hidden]
        self.cols = [p for i in order for p in vis if p.get("id") == i] + [p for p in vis if p.get("id") not in order]
        self.rows = m.rows_for(view)
        self.shown = self.PAGE
        self.cells = {}            # Spalten-Schluessel -> [Widgets]
        self.avail = 0
        self.drag_w = {}
        self.box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.box.add_css_class("hk-dbtable")
        self.set_child(self.box)
        self._build()

    def _cell(self, key, w):
        self.cells.setdefault(key, []).append(w)
        return w

    def widths(self):
        saved = (self.view or {}).get("widths") or {}
        out = {"title": self.drag_w.get("title") or float(saved.get("title") or 260)}
        for p in self.cols:
            out[p["id"]] = self.drag_w.get(p["id"]) or float(saved.get(p["id"]) or 170)
        if self.drag_w or self.avail <= 0:
            return out
        avail = self.avail - (0 if self.m.locked else 40)
        total = sum(out.values())

        def shrink(keys, minw):
            nonlocal total
            over = total - avail
            room = sum(max(0, out[k] - minw) for k in keys)
            if over <= 0 or room <= 0:
                return
            f = min(1, over / room)
            for k in keys:
                dlt = math.floor(max(0, out[k] - minw) * f)
                out[k] -= dlt
                total -= dlt
        shrink([p["id"] for p in self.cols], 90)
        shrink(["title"], 150)
        if total < avail:
            out["title"] += avail - total
        return out

    def apply_widths(self):
        w = self.widths()
        for k, ws in self.cells.items():
            for x in ws:
                x.set_size_request(int(w.get(k, 170)), -1)

    def do_size_allocate(self, width, height, baseline):
        Gtk.ScrolledWindow.do_size_allocate(self, width, height, baseline)
        if abs(width - self.avail) > 2:
            self.avail = width
            GLib.idle_add(lambda: (self.apply_widths(), False)[1])

    def _build(self):
        m = self.m
        head = Gtk.Box()
        head.add_css_class("hk-dbhead")
        t = Gtk.Box(spacing=5, margin_start=8, margin_end=8, margin_top=6, margin_bottom=6)
        t.append(Gtk.Label(label="Aa", css_classes=["dim-label"]))
        t.append(Gtk.Label(label=_("Name"), css_classes=["dim-label"], xalign=0))
        head.append(self._with_resizer(self._cell("title", t), "title"))
        for p in self.cols:
            from .dbedit import ColumnHead
            ch = ColumnHead(m, p)
            ch.add_css_class("hk-colsep")
            head.append(self._with_resizer(self._cell(p["id"], ch), p["id"]))
        if not m.locked:
            from .dbedit import AddColumnButton
            head.append(AddColumnButton(m))
        self.box.append(head)
        groups = self._groups()
        for gname, gcolor, rows in groups:
            if gname is not None:
                gh = Gtk.Box(spacing=8, margin_top=10, margin_bottom=4, margin_start=6)
                gh.append(chip(gname, gcolor))
                gh.append(Gtk.Label(label=str(len(rows)), css_classes=["dim-label", "caption"]))
                self.box.append(gh)
            for r in rows[: self.shown]:
                self.box.append(self._row(r))
        if len(self.rows) > self.shown and len(groups) == 1:
            more = flat_button(label=_("Load {n} more").format(n=len(self.rows) - self.shown), css="dim-label",
                               cb=lambda: self._more())
            more.set_halign(Gtk.Align.START)
            self.box.append(more)
        if not self.rows:
            e = Gtk.Label(label=_("No entries"), xalign=0, margin_start=8, margin_top=8, margin_bottom=8)
            e.add_css_class("dim-label")
            self.box.append(e)
        from .dbedit import CalcRow
        self.box.append(CalcRow(self, m, self.view, self.cols, self.rows))
        if m.can_add:
            nb = flat_button(icon="list-add-symbolic", label=_("New"), css="dim-label", cb=self.new_row)
            nb.set_halign(Gtk.Align.START)
            self.box.append(nb)
        self.apply_widths()

    def _more(self):
        self.shown += self.PAGE
        while (c := self.box.get_first_child()):
            self.box.remove(c)
        self.cells = {}
        self._build()

    def _groups(self):
        gid = (self.view or {}).get("group")
        g = self.m.prop(gid) if gid else None
        if not g or g.get("type") != "select":
            return [(None, None, self.rows)]
        out = []
        for o in g.get("options") or []:
            rs = [r for r in self.rows if r["props"].get(g["id"]) == o["id"]]
            if rs:
                out.append((o["name"], o.get("color"), rs))
        rest = [r for r in self.rows if not option(g, r["props"].get(g["id"]))]
        if rest:
            out.append((_("Without {name}").format(name=g.get("name", "")), None, rest))
        return out or [(None, None, [])]

    def _with_resizer(self, w, key):
        if self.m.locked or not self.vid:
            return w
        ov = Gtk.Overlay()
        ov.set_child(w)
        h = Gtk.Box(halign=Gtk.Align.END)
        h.set_size_request(6, -1)
        h.set_cursor_from_name("col-resize")
        g = Gtk.GestureDrag()

        def begin(gs, x, y):
            self._w0 = self.widths().get(key, 170)

        def upd(gs, x, y):
            self.drag_w[key] = max(60, min(800, self._w0 + x))
            self.apply_widths()

        def end(gs, x, y):
            nw = int(self.drag_w.get(key, self._w0))
            self.drag_w = {}
            self.m.edit_view(self.vid, lambda v: v.__setitem__("widths", {**(v.get("widths") or {}), key: nw}))
        g.connect("drag-begin", begin)
        g.connect("drag-update", upd)
        g.connect("drag-end", end)
        h.add_controller(g)
        ov.add_overlay(h)
        return ov

    def _row(self, r):
        m = self.m
        row = Gtk.Box()
        row.add_css_class("hk-dbrow")
        _color_css(row, m.color(r, self.view))
        tc = Gtk.Box(spacing=6, margin_start=8, margin_end=4)
        if r.get("icon"):
            tc.append(images.page_icon(r["icon"]))
        en = Gtk.Entry(text=r["title"], placeholder_text=_("Untitled"), hexpand=True)
        en.add_css_class("flat")
        en.add_css_class("hk-title-entry")
        en.set_sensitive(not m.locked)

        def commit(*_, en=en, r=r):
            t = en.get_text().replace("\n", " ").strip()
            if t != r["title"]:
                m.rename(r["id"], t)
        en.connect("activate", commit)
        fc = Gtk.EventControllerFocus()
        fc.connect("leave", commit)
        en.add_controller(fc)
        tc.append(en)
        ob = flat_button(icon="adw-external-link-symbolic", tooltip=_("Open"), css="hk-tiny", cb=lambda r=r: self.open(r))
        tc.append(ob)
        tc.append(self.more(r))
        row.append(self._cell("title", tc))
        for p in self.cols:
            v = m.value(p, r)
            c = Gtk.Box(margin_start=6, margin_end=6)
            c.add_css_class("hk-colsep")
            ed = prop_editor(p, v, lambda nv, r=r, p=p: m.set(r["id"], p["id"], nv), model=m)
            ed.set_valign(Gtk.Align.CENTER)
            c.append(ed)
            row.append(self._cell(p["id"], c))
        self.attach_menu(row, r)
        return row


# --- Liste ----------------------------------------------------------------------------------
class ListView(Gtk.Box, RowMenuMixin):
    __gtype_name__ = "HKDBList"

    def __init__(self, win, m, view):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.win, self.m, self.view = win, m, view
        hidden = set((view or {}).get("hidden") or [])
        self.rows = m.rows_for(view)
        check = next((p for p in m.props if p.get("type") == "checkbox" and p["id"] not in hidden), None)
        chips = [p for p in m.props if p.get("type") in ("select", "multi_select", "date", "number", "calc") and p["id"] not in hidden][:3]
        for r in self.rows[:300]:
            row = Gtk.Box(spacing=10, margin_top=4, margin_bottom=4)
            row.add_css_class("hk-listrow")
            _color_css(row, m.color(r, view))
            if check:
                cb = Gtk.CheckButton(active=bool(r["props"].get(check["id"])))
                cb.connect("toggled", lambda c, r=r: m.set(r["id"], check["id"], c.get_active()))
                row.append(cb)
            else:
                row.append(images.page_icon(r.get("icon")))
            inner = Gtk.Box(spacing=8)
            if check and r.get("icon"):
                inner.append(images.page_icon(r["icon"]))
            t = Gtk.Label(label=r["title"] or _("Untitled"), xalign=0, hexpand=True, wrap=True, natural_wrap_mode=Gtk.NaturalWrapMode.WORD)
            if not r["title"]:
                t.add_css_class("dim-label")
            inner.append(t)
            for p in chips:                                   # kompakt wie Notion: Mehrfachauswahl hoechstens 2 + "+N"
                w = value_widget(p, m.value(p, r), limit=2)
                if w:
                    w.set_valign(Gtk.Align.CENTER)
                    inner.append(w)
            b = Gtk.Button(child=inner, hexpand=True)
            b.add_css_class("flat")
            b.add_css_class("hk-plain")
            b.connect("clicked", lambda *_, r=r: self.open(r))
            row.append(b)
            row.append(self.more(r))
            self.attach_menu(b, r)
            self.append(row)
            self.append(Gtk.Separator())
        if not self.rows:
            e = Gtk.Label(label=_("No entries"), xalign=0, margin_top=8)
            e.add_css_class("dim-label")
            self.append(e)


# --- Karten (Board, Galerie) ----------------------------------------------------------------
def card(win, m, r, view, cover=True):
    hidden = set((view or {}).get("hidden") or [])
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
    box.add_css_class("hk-cardview")
    _color_css(box, m.color(r, view))
    if cover and r.get("cover"):
        im = images.ImageView(r["cover"], mode="cover", w=640)
        im.set_size_request(-1, 110)
        box.append(im)
    inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=8, margin_bottom=8, margin_start=10, margin_end=10)
    tr = Gtk.Box(spacing=6)
    if r.get("icon"):
        tr.append(images.page_icon(r["icon"]))
    tl = Gtk.Label(label=r["title"] or _("Untitled"), xalign=0, wrap=True, lines=2, ellipsize=Pango.EllipsizeMode.END)
    tl.add_css_class("hk-card-title")
    tr.append(tl)
    inner.append(tr)
    fb = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, row_spacing=3, column_spacing=3, max_children_per_line=10)
    for p in [p for p in m.props if p["id"] not in hidden and p.get("type") in ("select", "multi_select", "date", "checkbox", "number", "calc")][:4]:
        w = value_widget(p, m.value(p, r))
        if w:
            fb.append(w)
    if fb.get_first_child():
        inner.append(fb)
    box.append(inner)
    return box


class BoardView(Gtk.ScrolledWindow, RowMenuMixin):
    __gtype_name__ = "HKBoard"

    def __init__(self, win, m, view):
        super().__init__(vscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True)
        self.win, self.m, self.view = win, m, view
        self.rows = m.rows_for(view)
        g = m.prop((view or {}).get("group_by")) or next((p for p in m.props if p.get("type") == "select"), None)
        hb = Gtk.Box(spacing=12)
        self.set_child(hb)
        if not g:
            hb.append(Gtk.Label(label=_("A board needs a select property"), css_classes=["dim-label"]))
            return
        cols = [(o["id"], o["name"], o.get("color")) for o in g.get("options") or []] + [(None, _("Without {name}").format(name=g.get("name", "")), None)]
        for oid, name, color in cols:
            items = [r for r in self.rows if (r["props"].get(g["id"]) or None) == oid]
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
            col.add_css_class("hk-boardcol")
            col.add_css_class(f"hk-col-{color or 'gray'}")
            col.set_size_request(240, -1)
            h = Gtk.Box(spacing=6)
            h.append(chip(name, color))
            h.append(Gtk.Label(label=str(len(items)), css_classes=["dim-label", "caption"]))
            col.append(h)
            for r in items:
                c = card(win, m, r, view, cover=False)
                b = Gtk.Button(child=c)
                b.add_css_class("flat")
                b.add_css_class("hk-cardbtn")
                b.connect("clicked", lambda *_, r=r: self.open(r))
                ds = Gtk.DragSource(actions=Gdk.DragAction.MOVE)
                ds.connect("prepare", lambda *_, r=r: Gdk.ContentProvider.new_for_value(f"hkrow:{r['id']}"))
                b.add_controller(ds)
                self.attach_menu(b, r)
                col.append(b)
            if self.m.can_add:
                col.append(flat_button(icon="list-add-symbolic", label=_("New"), css="dim-label",
                                       cb=lambda oid=oid: self.new_row({g["id"]: oid} if oid else {})))
            dt = Gtk.DropTarget.new(GObject.TYPE_STRING, Gdk.DragAction.MOVE)

            def drop(t, val, x, y, oid=oid):
                if isinstance(val, str) and val.startswith("hkrow:"):
                    rid = int(val[6:])
                    GLib.idle_add(lambda: (m.set(rid, g["id"], oid), False)[1])
                    return True
                return False
            dt.connect("drop", drop)
            col.add_controller(dt)
            hb.append(col)


class GalleryView(Gtk.FlowBox, RowMenuMixin):
    __gtype_name__ = "HKGallery"

    def __init__(self, win, m, view):
        super().__init__(selection_mode=Gtk.SelectionMode.NONE, row_spacing=12, column_spacing=12, homogeneous=True,
                         min_children_per_line=1, max_children_per_line=8)
        self.win, self.m, self.view = win, m, view
        self.rows = m.rows_for(view)
        size = {"s": 150, "l": 280}.get((view or {}).get("card"), 200)
        for r in self.rows[:300]:
            b = Gtk.Button(child=card(win, m, r, view))
            b.add_css_class("flat")
            b.add_css_class("hk-cardbtn")
            b.set_size_request(size, -1)
            b.connect("clicked", lambda *_, r=r: self.open(r))
            self.attach_menu(b, r)
            self.append(b)


class CalendarView(Gtk.Box, RowMenuMixin):
    __gtype_name__ = "HKDBCalendar"

    def __init__(self, win, m, view, month=None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.win, self.m, self.view = win, m, view
        self.rows = m.rows_for(view)
        t = _dt.date.today()
        self.month = month or _dt.date(t.year, t.month, 1)
        self.dp = m.prop((view or {}).get("date_by")) or next((p for p in m.props if p.get("type") == "date"), None)
        self._build()

    def _build(self):
        while (c := self.get_first_child()):
            self.remove(c)
        mth = self.month
        bar = Gtk.Box(spacing=6)
        bar.append(flat_button(icon="go-previous-symbolic", cb=lambda: self._shift(-1)))
        ml = Gtk.Label(label=month_text(mth), width_chars=16)
        ml.add_css_class("heading")
        bar.append(ml)
        bar.append(flat_button(icon="go-next-symbolic", cb=lambda: self._shift(1)))
        bar.append(Gtk.Box(hexpand=True))
        bar.append(flat_button(label=_("Today"), cb=lambda: self._today()))
        self.append(bar)
        if not self.dp:
            self.append(Gtk.Label(label=_("A calendar needs a date property"), css_classes=["dim-label"]))
            return
        grid = Gtk.Grid(column_homogeneous=True, row_spacing=2, column_spacing=2)
        for i, n in enumerate(weekday_names()):
            grid.attach(Gtk.Label(label=n, css_classes=["caption", "dim-label"]), i, 0, 1, 1)
        lead = mth.weekday()
        nxt = _dt.date(mth.year + (mth.month == 12), mth.month % 12 + 1, 1)
        days = (nxt - mth).days
        today = _dt.date.today()
        for k in range(days):
            d = mth + _dt.timedelta(days=k)
            pos = lead + k
            cell = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
            cell.add_css_class("hk-calcell")
            cell.set_size_request(-1, 76)
            top = Gtk.Box()
            dl = Gtk.Label(label=str(d.day), xalign=0, hexpand=True)
            dl.add_css_class("caption")
            if d == today:
                dl.add_css_class("accent")
                dl.add_css_class("heading")
            else:
                dl.add_css_class("dim-label")
            top.append(dl)
            ds = d.isoformat()
            if self.dp.get("type") not in ("created", "edited") and self.m.can_add:
                top.append(flat_button(icon="list-add-symbolic", css="hk-tiny dim-label", cb=lambda ds=ds: self.new_row({self.dp["id"]: ds})))
            cell.append(top)
            items = [r for r in self.rows if str(self.m.value(self.dp, r) or "").startswith(ds)]
            for r in items[:3]:
                b = Gtk.Button(child=Gtk.Label(label=r["title"] or _("Untitled"), xalign=0, ellipsize=Pango.EllipsizeMode.END))
                b.add_css_class("hk-calitem")
                b.connect("clicked", lambda *_, r=r: self.open(r))
                self.attach_menu(b, r)
                cell.append(b)
            if len(items) > 3:
                cell.append(Gtk.Label(label=f"+{len(items) - 3}", xalign=0, css_classes=["caption", "dim-label"]))
            grid.attach(cell, pos % 7, 1 + pos // 7, 1, 1)
        self.append(grid)

    def _shift(self, d):
        y, mo = self.month.year, self.month.month + d
        if mo == 0:
            y, mo = y - 1, 12
        elif mo == 13:
            y, mo = y + 1, 1
        self.month = _dt.date(y, mo, 1)
        self._build()

    def _today(self):
        t = _dt.date.today()
        self.month = _dt.date(t.year, t.month, 1)
        self._build()


# --- Datenbank-Ansicht (Seite oder Block) ---------------------------------------------------
class DatabaseView(Gtk.Box):
    __gtype_name__ = "HKDatabaseView"

    def __init__(self, win, db_id, view_id=None, local_views=None, embedded=False, on_local_views=None, host_locked=False):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.win, self.db_id, self.embedded = win, db_id, embedded
        self.m = DBModel(db_id)
        # Block auf einer gesperrten Seite: kein "Neu", keine Ansichts-Einstellungen, kein "+" (Ansicht) - nur ansehen,
        # Eintraege oeffnen, suchen und zwischen den Ansichten wechseln
        self.m.host_locked = bool(host_locked)
        if local_views and on_local_views:
            self.m.local_views = list(local_views)
            self.m.on_local_views = on_local_views
        self.local_readonly = bool(local_views) and on_local_views is None
        self.current = view_id
        self.cal_month = None
        if embedded:
            self.append(self._header())
        from .dbedit import ViewBar
        self.bar = ViewBar(self)
        self.bar.set_narrow(bool(getattr(win, "very_narrow", False)))   # Breite folgt in do_size_allocate
        self.append(self.bar)
        self.content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.append(self.content)
        self.err = Gtk.Label(xalign=0, wrap=True, visible=False)
        self.err.add_css_class("warning")
        self.append(self.err)
        self.m.connect("changed", lambda *_: self.render())
        watch(self, "data-changed", self.m.load)
        self._narrow = None
        self._narrow_src = 0
        self.m.load()

    NARROW = 560

    def do_size_allocate(self, width, height, baseline):
        """Breite ueber die Grenze geaendert (Fenster verkleinert/vergroessert): Tabelle <-> Liste (BUG-082)"""
        Gtk.Box.do_size_allocate(self, width, height, baseline)
        narrow = 0 < width < self.NARROW
        if self.bar.narrow != narrow:
            GLib.idle_add(lambda n=narrow: (self.bar.set_narrow(n), False)[1])
        if self._narrow is not None and narrow != self._narrow and not self._narrow_src:
            def later():
                self._narrow_src = 0
                if ((self.view or {}).get("type") or "table") == "table":
                    self.render()
                return False
            self._narrow_src = GLib.idle_add(later)
        self._narrow = narrow

    def _header(self):
        p = store.meta(self.db_id)
        row = Gtk.Box(spacing=8)
        inner = Gtk.Box(spacing=6)
        inner.append(images.page_icon((p or {}).get("icon"), fallback="🗃️"))
        inner.append(Gtk.Label(label=label(p) if p else _("Database"), css_classes=["heading"]))
        b = Gtk.Button(child=inner)
        b.add_css_class("flat")
        b.add_css_class("hk-dbname")
        b.connect("clicked", lambda *_: self.win.show_page(self.db_id))
        row.append(b)
        row.append(Gtk.Box(hexpand=True))
        row.append(flat_button(icon="view-fullscreen-symbolic", tooltip=_("Open as full page"), css="dim-label",
                               cb=lambda: self.win.show_page(self.db_id)))
        return row

    @property
    def views(self):
        return self.m.all_views() if not self.local_readonly else self.m.views

    @property
    def view(self):
        vs = self.views
        return next((v for v in vs if v.get("id") == self.current), vs[0] if vs else None)

    def set_view(self, vid):
        self.current = vid
        self.render()

    def render(self):
        self.bar.update()
        while (c := self.content.get_first_child()):
            self.content.remove(c)
        if self.m.error and not self.m.rows:
            self.err.set_label(self.m.error)
            self.err.set_visible(True)
        else:
            self.err.set_visible(False)
        if not self.m.loaded:
            sp = Gtk.Spinner(spinning=True, halign=Gtk.Align.CENTER)
            self.content.append(sp)
            return
        v = self.view
        t = (v or {}).get("type") or "table"
        narrow = 0 < self.get_width() < self.NARROW
        if t == "board":
            w = BoardView(self.win, self.m, v)
        elif t == "gallery":
            w = GalleryView(self.win, self.m, v)
        elif t == "calendar":
            w = CalendarView(self.win, self.m, v)
        elif t == "list" or narrow:
            w = ListView(self.win, self.m, v)
        else:
            w = TableView(self.win, self.m, v)
        self.content.append(w)

    def new_entry(self, template=None):
        v = self.view
        mode = (v or {}).get("open") or "side"
        if template:
            self.m.new_from_template(template, done=lambda i: self.win.open_entry(i, mode))
        else:
            self.m.add(self.m.defaults(v), done=lambda i: self.win.open_entry(i, mode))


class EditableProps(Gtk.Grid):
    """Eigenschaften eines Datenbank-Eintrags (auf seiner Seite)"""
    __gtype_name__ = "HKEditableProps"

    def __init__(self, view, schema):
        super().__init__(row_spacing=2, column_spacing=8)
        self.view = view
        doc = view.doc
        for i, p in enumerate(schema.get("props") or []):
            lb = Gtk.Box(spacing=6)
            lb.append(Gtk.Image.new_from_icon_name(PROP_ICON.get(p.get("type"), "format-justify-left-symbolic")))
            n = Gtk.Label(label=p.get("name") or "", xalign=0, ellipsize=Pango.EllipsizeMode.END)
            lb.append(n)
            lb.add_css_class("dim-label")
            lb.set_size_request(160, 32)
            self.attach(lb, 0, i, 1, 1)
            t = p.get("type")
            if t == "created":
                v = _iso(doc.page.get("created"))
            elif t == "edited":
                v = _iso(doc.page.get("updated"))
            elif t == "calc":
                v = calc_value(p, doc.props)
            else:
                v = doc.props.get(p.get("id"))
                if t == "number" and v is not None:
                    try:
                        v = float(v)
                    except (TypeError, ValueError):
                        v = None
            ed = prop_editor(p, v, lambda nv, p=p: self._save(p, nv))
            ed.set_hexpand(True)
            ed.set_halign(Gtk.Align.START if t != "text" else Gtk.Align.FILL)
            self.attach(ed, 1, i, 1, 1)

    def _save(self, p, v):
        doc = self.view.doc
        props = dict(doc.props)
        props[p["id"]] = v
        doc.page["props"] = props
        store.run("PATCH", f"api/notes/{doc.id}", {"props": {p["id"]: v}}, data_changed=True, key=("note", doc.id),
                  done=lambda _: store.drop_cache(f"api/notes/{doc.id}"))
        GLib.idle_add(lambda: (self.view.rebuild(), False)[1])


def _iso(ts):
    return _dt.date.fromtimestamp(ts).isoformat() if ts else None
