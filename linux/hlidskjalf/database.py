"""Datenbanken nativ: Modell + Filter/Sortierung wie im Web (DBQuery.swift), Ansichten, Eigenschafts-Editoren."""
import copy
import datetime as _dt
import math

from gi.repository import Gdk, GLib, GObject, Gtk, Pango

from . import dbvalues as dv
from . import images
from .api import client
from .blocks import chip, context_menu, flat_button, menu_popover, more_button
from .i18n import _
from .store import label, store, watch
from .entries import blocked_new_hint
from .util import bg, day_text, iso_day, lane_busy, month_text, num_text, parse_day, parse_num, weekday_names

PROP_ICON = {"text": "format-justify-left-symbolic", "number": "accessories-calculator-symbolic", "select": "go-down-symbolic",
             "multi_select": "view-list-bullet-symbolic", "checkbox": "checkbox-checked-symbolic", "date": "x-office-calendar-symbolic",
             "url": "insert-link-symbolic", "email": "mail-unread-symbolic", "phone": "call-start-symbolic", "relation": "send-to-symbolic",
             "created": "document-open-recent-symbolic", "edited": "document-open-recent-symbolic", "calc": "accessories-calculator-symbolic",
             "formula": "accessories-calculator-symbolic", "rollup": "view-list-symbolic", "person": "avatar-default-symbolic",
             "files": "mail-attachment-symbolic"}


def rows_path(db_id):
    """GET .../rows - mit der Zeitzone des Geraets, wenn der Server 2.5-Faehigkeiten meldet (Formeln today()/now(),
    Datum mit Uhrzeit); aeltere Server: unveraendert ohne Parameter (Antwort wie immer)"""
    if not store.features:
        return f"api/notes/{db_id}/rows"
    from urllib.parse import quote
    from .push import local_tz
    return f"api/notes/{db_id}/rows?tz={quote(local_tz(), safe='')}"


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
        self.show_title = self.show_tabs = True
        self.on_head = None                # 2.5: eingebetteter Block - Kopf ein-/ausblenden (k "title"/"tabs", an)
        self.people = {}                   # 2.5 (person1): Kennung -> {name, avatar, me, remote, gone}
        self.today = None                  # Tests: fester Tag fuer relative Datumsfilter

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
            c = store.cached(rows_path(self.db_id))
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
        bg(store.get_cached, rows_path(self.db_id), done=fin, error=err)

    def _apply(self, j):
        r = j.get("role")
        self.role = r if r in ("owner", "edit", "comment", "view") else "owner"
        self.schema = j.get("schema") or {}
        self.props = self.schema.get("props") or []
        self.views = self.schema.get("views") or []
        self.rows = []
        for r in j.get("rows") or []:
            x = {"id": r.get("id"), "title": r.get("title") or "", "icon": r.get("icon"),
                 "cover": r.get("preview") or r.get("cover") or None, "props": r.get("props") or {},
                 "created": r.get("created") or 0, "updated": r.get("updated") or 0}
            for k in ("fx_err", "blocked", "depth", "sub_of"):        # 2.5: nur gelesen, nie gespeichert
                if k in r:
                    x[k] = r[k]
            self.rows.append(x)
        self.people = j.get("people") if isinstance(j.get("people"), dict) else {}
        from .richtext import MENTION_NAMES                       # Erwaehnungen zeigen den aktuellen Namen (nur Anzeige)
        for k, v in self.people.items():
            if isinstance(v, dict) and isinstance(v.get("name"), str) and not v.get("gone"):
                MENTION_NAMES[k] = v["name"]
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
        if t in dv.FX and p.get("rtype") == "number" and v is not None and not isinstance(v, bool):
            return v if isinstance(v, (int, float)) else None
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
        et = dv.eff_type(p)
        empty = not has_value(v) if not isinstance(v, (int, float)) or isinstance(v, bool) else False
        if op == "is_empty":
            return empty
        if op == "not_empty":
            return not empty
        if op == "checked":
            return bool(v)
        if op == "unchecked":
            return not bool(v)
        if op == "status_group":                   # 2.5 status1: Gruppe der Option (open = alles ausser done)
            return dv.match_status_group(p, v, f.get("value"))
        if op == "me":                             # 2.5 person1: mir zugewiesen
            return any(dv.is_me(x, self.people) for x in dv.person_refs(v))
        if op in dv.DAY_OPS:                       # Datum (mit Uhrzeit/Zeitraum) als Kalendertage des Geraets
            return dv.match_day_filter(self._span(p, v), op, f.get("value"), self.today or _dt.date.today())
        fv = f.get("value")
        if fv is None or str(fv) == "":
            return True
        fv = str(fv)
        arr = [str(x) for x in v] if isinstance(v, list) else None
        if arr is None and et in ("relation", "list", "person") and op in ("contains", "not_contains"):
            arr = []                       # Relation ohne Verknuepfung = leere Liste (wie Server/Web seit 2.5, kein Textvergleich)
        if et == "list" and arr is not None and op in ("contains", "not_contains"):
            hit = any(fv.lower() in x.lower() for x in arr)        # Formel/Rollup-Liste: wie Relation (Text enthaelt)
            return hit if op == "contains" else not hit
        s = v if isinstance(v, str) else ("" if v is None else str(v))
        num = v if isinstance(v, (int, float)) and not isinstance(v, bool) else None
        try:
            fnum = float(fv)
        except ValueError:
            fnum = None
        if et == "date" and op in ("eq", "before", "after"):
            sp = self._span(p, v)
            d = dv.parse_part(fv)
            if sp is None or d is None:
                return False
            if op == "eq":
                return sp[0] <= d.day <= sp[1]
            return sp[0] < d.day if op == "before" else sp[1] > d.day
        if op == "contains":
            return fv in arr if arr is not None else fv.lower() in s.lower()
        if op == "not_contains":
            return fv not in arr if arr is not None else fv.lower() not in s.lower()
        if op == "is":
            return s == fv
        if op == "is_not":
            return s != fv
        if op == "eq":
            return (num is not None and fnum is not None and num == fnum) if et in ("number", "calc") else s == fv
        if op == "gt":
            return num is not None and fnum is not None and num > fnum
        if op == "lt":
            return num is not None and fnum is not None and num < fnum
        if op == "before":
            return s != "" and s < fv
        if op == "after":
            return s != "" and s > fv
        return True

    def _span(self, p, v):
        """(erster, letzter Tag) eines Datumswerts in der Zeitzone des Geraets"""
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            d = _dt.date.fromtimestamp(v)
            return d, d
        return dv.day_span(v) if isinstance(v, str) else None

    def _sort_key(self, p, r):
        if p is None:
            return r["title"].lower()
        v = self.value(p, r)
        et = dv.eff_type(p)
        if p.get("type") == "select" and isinstance(v, str):
            o = option(p, v)
            return f"{(p.get('options') or []).index(o):04d}" if o else None
        if et == "date" and isinstance(v, str):
            return dv.sort_key(v)
        if et == "person":                         # nach dem Namen der ersten Person
            refs = dv.person_refs(v)
            return dv.person_info(refs[0], self.people)["name"].lower() if refs else None
        if et in ("list", "relation", "multi_select", "files"):
            return f"{len(v):06d}" if isinstance(v, list) and v else None
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
                        if p.get("type") == "person":
                            if any(q in n.lower() for n in dv.people_names(v, self.people)):
                                return True
                        elif any(q in ((option(p, x) or {}).get("name") or (x if isinstance(x, str) and dv.is_fx(p) else "")).lower()
                                 for x in v):
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
        if dv.is_fx(self.prop(pid)):       # berechnete Werte nie zurueckschreiben
            return
        r = next((x for x in self.rows if x["id"] == row_id), None)
        if r is not None:
            if value is None:
                r["props"].pop(pid, None)
            else:
                r["props"][pid] = value
        self.emit("changed")

        def done(ans):
            store.drop_cache(f"api/notes/{row_id}")
            row = next((x for x in self.rows if x["id"] == row_id), None)
            if row is not None and isinstance(ans, dict) and any(k in ans for k in ("calc", "values", "fx_err")):
                ppl = dv.apply_patch_answer(row, ans)          # 2.5: Formeln/Rollups und Personen/Dateien sofort
                if ppl:
                    self.people = dv.merge_people(self.people, ppl)
                self.emit("changed")
            store.emit("row-changed", int(row_id))
        # Warteschlange je Eintrag: schnelles An/Aus kommt in der richtigen Reihenfolge an
        store.run("PATCH", f"api/notes/{row_id}", {"props": {pid: value}}, data_changed=False, key=("note", row_id),
                  done=done, error=lambda e: (store.emit("error", str(e)), self.load()))

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
            store.note_fresh(r.get("id"), r)          # E4: leerer Titel + Platzhalter, unveraendert geschlossen -> weg
            self.load()
            store.refresh()
            store.changed_data()
            if done:
                done(r["id"])
        store.run("POST", f"api/notes/{tid}/duplicate", {"parent_id": self.db_id}, done=fin)

    # --- 2.5 Vorlagen verwalten ------------------------------------------------------------------------------------
    def default_template(self):
        tid = self.schema.get("default_tpl")
        return next((t for t in self.templates if t.get("id") == tid), None) if tid else None

    def set_default_template(self, tid):
        self.edit_schema(lambda sc: sc.__setitem__("default_tpl", tid))      # None -> null hebt ihn auf

    def _tpl(self, method, path, body=None, done=None):
        def fin(r):
            self.load()
            store.refresh()
            store.changed_data()
            if done:
                done(r)
        store.run(method, path, body if body is not None else {}, done=fin, error=lambda e: store.emit("error", str(e)))

    def rename_template(self, t, title):
        if title.strip() != (t.get("title") or ""):
            self._tpl("PATCH", f"api/notes/{t['id']}", {"title": title.strip()})

    def duplicate_template(self, t, title):
        self._tpl("POST", f"api/notes/{t['id']}/duplicate", {"parent_id": self.db_id, "template": True, "title": title})

    def untemplate(self, t):
        if (self.default_template() or {}).get("id") == t["id"]:
            self.set_default_template(None)
        self._tpl("PATCH", f"api/notes/{t['id']}", {"template": False})

    def trash_template(self, t):
        if (self.default_template() or {}).get("id") == t["id"]:
            self.set_default_template(None)
        store.run("DELETE", f"api/notes/{t['id']}", done=lambda r: (self.load(), store.refresh(), store.changed_data()),
                  error=lambda e: store.emit("error", str(e)))

    def new_template(self, title, done=None):
        def made(r):
            self._tpl("PATCH", f"api/notes/{r['id']}", {"template": True}, done=lambda _r: done and done(r["id"]))
        store.run("POST", "api/notes", {"parent_id": self.db_id, "title": title}, done=made, error=lambda e: store.emit("error", str(e)))

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


def value_widget(p, v, limit=None, m=None, r=None, win=None):
    """kompakte Anzeige (Chip, Datum, Zahl) - None wenn leer. m/r: Modell und Zeile (Personen, Formelfehler)"""
    from . import dbcells
    t = p.get("type")
    if t in dv.FX:
        return dbcells.fx_widget(p, v, dv.fx_error(r, p.get("id")))
    if t == "person":
        return dbcells.people_widget(v, getattr(m, "people", None), limit=limit or 3)
    if t == "files":
        return dbcells.files_widget(v, win, limit=limit or 2)
    if t == "date" and isinstance(v, str):
        return dbcells.date_label(v)
    if t == "select":
        o = option(p, v)
        return chip(dv.status_label(p, o), o.get("color")) if o else None
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


def prop_editor(p, v, setv, model=None, compact=False, row=None, win=None):
    """Eigenschaft direkt bearbeiten (Tabelle + Eintragsseite). row: Zeile (id, fx_err) fuer Formeln und Dateien"""
    from . import dbcells
    t = p.get("type")
    if t in dv.FX:                                         # 2.5: berechnet - nur anzeigen, bearbeiten im Web
        w = dbcells.fx_widget(p, v, dv.fx_error(row, p.get("id")), compact=compact)
        return w or _leer("–")
    if t == "person":
        return dbcells.person_editor(p, v, setv, model)
    if t == "files":
        return dbcells.files_editor(p, v, setv, row_id=(row or {}).get("id"), win=win,
                                    locked=bool(getattr(model, "locked", False)) or row is None)
    if t == "date" and (store.features or dv.has_time(v) or dv.is_range(v)):
        return dbcells.date_editor(p, v, setv)
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
        mb.set_child(chip(dv.status_label(p, o), o.get("color")) if o else _leer())

        def popup(m):
            if dv.is_status(p):                    # 2.5: nach Gruppen (Zu erledigen / In Arbeit / Erledigt)
                from .dbcells import status_menu_entries
                ents = status_menu_entries(p, v, setv)
            else:
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


def _blocked(btn, m):
    """#11: Lesen/Kommentieren - Anlege-Schaltflaeche ausgegraut mit Hinweis (nicht ausgeblendet)"""
    hint = blocked_new_hint(m.readonly)
    if hint:
        btn.set_sensitive(False)
        btn.set_tooltip_text(hint)
        btn.hk_blocked = hint
    return btn


def _color_css(w, color):
    if color:
        w.add_css_class(f"hk-row-{color}")


def _wrap_labels(w):
    """Zeilenumbruch in einer Zelle (view.wrap / wrap_cols): Labels darin umbrechen statt abschneiden"""
    stack = [w]
    while stack:
        x = stack.pop()
        if isinstance(x, Gtk.Label):
            x.set_wrap(True)
            x.set_ellipsize(Pango.EllipsizeMode.NONE)
        c = x.get_first_child()
        while c is not None:
            stack.append(c)
            c = c.get_next_sibling()


def group_label(m, g, key):
    """Anzeige einer Gruppe (Formel/Rollup-Wert, Personen-Kombination)"""
    t = g.get("type")
    if t == "person":
        return ", ".join(dv.person_label(x, m.people) for x in key.split(","))
    rt = g.get("rtype")
    if rt == "checkbox":
        return _("Checked") if key is True else _("Unchecked")
    return dv.fx_text(g, key) or str(key)


def groups_for(m, view, rows, gid, board=False):
    """Gruppen einer Ansicht -> [(name | None, farbe, zeilen, schluessel, art)] oder None (keine Gruppierung).
    art: "option" (Wert = Options-ID, ziehbar), "status" (Gruppe todo/doing/done, ziehen setzt die erste Option),
    "ro" (Formel/Rollup/Personen - nur lesen). Tabelle/Liste: leere Gruppen weg; Board: leere Spalten bleiben."""
    g = m.prop(gid) if gid else None
    if not dv.groupable(g):
        return None
    get = (lambda r: m.value(g, r)) if dv.is_fx(g) else (lambda r: r["props"].get(g["id"]))
    without = _("Without {name}").format(name=g.get("name", ""))
    out = []
    if dv.is_status(g) and (view or {}).get("group_status") is True:
        parts = dv.status_groups(g, rows, get)
        for key, rs in parts[1:]:
            out.append((f"{dv.STATUS_ICON[key]} {dv.group_name(key)}", {"todo": "gray", "doing": "blue", "done": "green"}[key], rs, key, "status"))
        out.append((without, None, parts[0][1], None, "status"))
    elif g.get("type") == "select":
        for o in g.get("options") or []:
            rs = [r for r in rows if get(r) == o["id"]]
            out.append((dv.status_label(g, o), o.get("color"), rs, o["id"], "option"))
        out.append((without, None, [r for r in rows if not option(g, get(r))], None, "option"))
    else:
        parts = dv.person_groups(rows, get, m.people) if g.get("type") == "person" else dv.value_groups(rows, g.get("rtype"), get)
        for key, rs in parts[1:]:
            out.append((group_label(m, g, key), None, rs, key, "ro"))
        out.append((without, None, parts[0][1], None, "ro"))
    if board:
        return [x for x in out if x[2] or x[3] is not None]
    return [x for x in out if x[2]]


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
        for gname, gcolor, rows, *_x in groups:
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
        if m.can_add or m.readonly:
            nb = flat_button(icon="list-add-symbolic", label=_("New"), css="dim-label", cb=self.new_row)
            nb.set_halign(Gtk.Align.START)
            _blocked(nb, m)
            self.box.append(nb)
        self.apply_widths()

    def _more(self):
        self.shown += self.PAGE
        while (c := self.box.get_first_child()):
            self.box.remove(c)
        self.cells = {}
        self._build()

    def _groups(self):
        return groups_for(self.m, self.view, self.rows, (self.view or {}).get("group"), board=False) or [(None, None, self.rows)]

    def _wraps(self, key):
        v = self.view or {}
        return v.get("wrap") is True or key in (v.get("wrap_cols") or [])

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
        if r.get("blocked") is True:                        # 2.5 subtasks1: wartet auf einen anderen Eintrag
            from .dbcells import blocked_mark
            tc.append(blocked_mark())
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
            ed = prop_editor(p, v, lambda nv, r=r, p=p: m.set(r["id"], p["id"], nv), model=m, compact=True, row=r, win=self.win)
            ed.set_valign(Gtk.Align.CENTER)
            if self._wraps(p["id"]):
                _wrap_labels(ed)
            c.append(ed)
            row.append(self._cell(p["id"], c))
        self.attach_menu(row, r)
        return row


# --- Liste ----------------------------------------------------------------------------------
class ListView(Gtk.Box, RowMenuMixin):
    __gtype_name__ = "HKDBList"

    def nested(self):
        """(zeile, tiefe): Unteraufgaben (view.sub) - Tiefe vom Server (depth) oder lokal wie der Server geschachtelt"""
        sub = (self.view or {}).get("sub")
        if isinstance(sub, dict) and sub.get("mode") in ("nested", "parents_only") and self.m.prop(sub.get("parent")):
            return dv.nest(self.m.rows, self.rows, sub, lambda r, pid: r["props"].get(pid))
        return [(r, r.get("depth") if isinstance(r.get("depth"), int) else 0) for r in self.rows]

    def __init__(self, win, m, view):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.win, self.m, self.view = win, m, view
        hidden = set((view or {}).get("hidden") or [])
        self.rows = m.rows_for(view)
        check = next((p for p in m.props if p.get("type") == "checkbox" and p["id"] not in hidden), None)
        status = None if check else next((p for p in m.props if dv.is_status(p) and p["id"] not in hidden), None)
        chips = [p for p in m.props if p.get("type") in ("select", "multi_select", "date", "number", "calc", "formula", "rollup", "person", "files")
                 and p["id"] not in hidden and p is not status][:3]
        for r, depth in self.nested()[:300]:
            row = Gtk.Box(spacing=10, margin_top=4, margin_bottom=4)
            row.add_css_class("hk-listrow")
            if depth:                                          # 2.5 subtasks1: Unteraufgaben eingerueckt
                row.set_margin_start(min(depth, 6) * 22)
                row.add_css_class("hk-subrow")
            _color_css(row, m.color(r, view))
            if check:
                cb = Gtk.CheckButton(active=bool(r["props"].get(check["id"])))
                cb.connect("toggled", lambda c, r=r: m.set(r["id"], check["id"], c.get_active()))
                row.append(cb)
            elif status is not None:                           # 2.5 status1: Abhaken setzt die erste Option der Gruppe done
                cur = r["props"].get(status["id"])
                cb = Gtk.CheckButton(active=dv.option_group(status, cur) == "done")
                cb.set_tooltip_text(status.get("name") or "")

                def tg(c, r=r):
                    oid = dv.done_option(status) if c.get_active() else dv.first_option_of_group(status, "todo")
                    if oid is not None and oid != r["props"].get(status["id"]):
                        m.set(r["id"], status["id"], oid)
                cb.connect("toggled", tg)
                row.append(cb)
            else:
                row.append(images.page_icon(r.get("icon")))
            inner = Gtk.Box(spacing=8)
            if r.get("blocked") is True:
                from .dbcells import blocked_mark
                inner.append(blocked_mark())
            if (check or status is not None) and r.get("icon"):
                inner.append(images.page_icon(r["icon"]))
            t = Gtk.Label(label=r["title"] or _("Untitled"), xalign=0, hexpand=True, wrap=True, natural_wrap_mode=Gtk.NaturalWrapMode.WORD)
            if not r["title"]:
                t.add_css_class("dim-label")
            inner.append(t)
            for p in chips:                                   # kompakt wie Notion: Mehrfachauswahl hoechstens 2 + "+N"
                w = value_widget(p, m.value(p, r), limit=2, m=m, r=r, win=win)
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
    col = m.color(r, view)
    if col:                                                # 2.5: Kartenfarbe (view.colors) - Flaeche + Streifen oben
        box.add_css_class(f"hk-card-{col}")
        box.hk_color = col
    if cover and r.get("cover"):
        im = images.ImageView(r["cover"], mode="cover", w=640)
        im.set_size_request(-1, 110)
        box.append(im)
    inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=8, margin_bottom=8, margin_start=10, margin_end=10)
    tr = Gtk.Box(spacing=6)
    if r.get("blocked") is True:
        from .dbcells import blocked_mark
        tr.append(blocked_mark())
    if r.get("icon"):
        tr.append(images.page_icon(r["icon"]))
    tl = Gtk.Label(label=r["title"] or _("Untitled"), xalign=0, wrap=True, lines=2, ellipsize=Pango.EllipsizeMode.END)
    tl.add_css_class("hk-card-title")
    tr.append(tl)
    inner.append(tr)
    fb = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, row_spacing=3, column_spacing=3, max_children_per_line=10)
    kinds = ("select", "multi_select", "date", "checkbox", "number", "calc", "formula", "rollup", "person", "files")
    for p in [p for p in m.props if p["id"] not in hidden and p.get("type") in kinds][:4]:
        w = value_widget(p, m.value(p, r), m=m, r=r, win=win)
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
        g = m.prop((view or {}).get("group_by"))
        if not dv.groupable(g):
            g = next((p for p in m.props if p.get("type") == "select"), None)
        hb = Gtk.Box(spacing=12)
        self.set_child(hb)
        if not g:
            hb.append(Gtk.Label(label=_("A board needs a select property"), css_classes=["dim-label"]))
            return
        cols = groups_for(m, view, self.rows, g["id"], board=True) or []
        for name, color, items, key, kind in cols:
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
                if kind != "ro":                                 # Formel/Rollup/Personen: nur lesend (nicht ziehen)
                    ds = Gtk.DragSource(actions=Gdk.DragAction.MOVE)
                    ds.connect("prepare", lambda *_, r=r: Gdk.ContentProvider.new_for_value(f"hkrow:{r['id']}"))
                    b.add_controller(ds)
                self.attach_menu(b, r)
                col.append(b)
            if (self.m.can_add or self.m.readonly) and kind != "ro":
                init = key if kind == "option" else (dv.first_option_of_group(g, key) if key else None)
                col.append(_blocked(flat_button(icon="list-add-symbolic", label=_("New"), css="dim-label",
                                                cb=lambda init=init: self.new_row({g["id"]: init} if init else {})), self.m))
            if kind != "ro":
                dt = Gtk.DropTarget.new(GObject.TYPE_STRING, Gdk.DragAction.MOVE)

                def drop(t, val, x, y, key=key, kind=kind):
                    if isinstance(val, str) and val.startswith("hkrow:"):
                        rid = int(val[6:])
                        GLib.idle_add(lambda: (self.drop_to(rid, g, key, kind), False)[1])
                        return True
                    return False
                dt.connect("drop", drop)
                col.add_controller(dt)
            hb.append(col)

    def drop_to(self, rid, g, key, kind):
        """Karte in eine Spalte gezogen: Option setzen bzw. (Status-Gruppe) erste Option der Gruppe, wenn sie noch nicht
        in einer Option dieser Gruppe liegt"""
        r = next((x for x in self.m.rows if x["id"] == rid), None)
        cur = (r or {}).get("props", {}).get(g["id"])
        if kind == "status":
            if key is None:
                if cur is not None:
                    self.m.set(rid, g["id"], None)
                return
            nv = dv.move_to_group(g, cur, key)
            if nv is not None:
                self.m.set(rid, g["id"], nv)
            return
        if cur != key:
            self.m.set(rid, g["id"], key)


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


def cal_items(m, dp, rows, day):
    """Eintraege eines Tages: (zeile, uhrzeit-text | None) - Zeitraeume auf jedem Tag (hoechstens 62), Uhrzeit vorn"""
    out = []
    for r in rows:
        v = m.value(dp, r)
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            v = _dt.date.fromtimestamp(v).isoformat()
        if not isinstance(v, str) or not v:
            continue
        sp = dv.day_span(v)
        if sp is None:
            continue
        a, b = sp
        if (b - a).days > 61:
            b = a + _dt.timedelta(days=61)
        if not (a <= day <= b):
            continue
        pr = dv.parse_date(v)
        t = None
        if day == a and pr and pr[0].time:
            from .util import time_text
            hm = pr[0].local(dv.viewer_tz())[1]
            t = time_text(_dt.datetime(2000, 1, 1, hm[0], hm[1]))
        out.append((r, t, (pr[0].key(dv.viewer_tz()) if pr else v)))
    out.sort(key=lambda x: (x[1] is not None, x[2]))           # ganztaegig zuerst, dann nach Uhrzeit
    return [(r, t) for r, t, _k in out]


class CalendarView(Gtk.Box, RowMenuMixin):
    __gtype_name__ = "HKDBCalendar"

    def __init__(self, win, m, view, month=None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.win, self.m, self.view = win, m, view
        self.rows = m.rows_for(view)
        t = _dt.date.today()
        self.week = (view or {}).get("range") == "week"         # 2.5 tableopts1: Wochenansicht Mo-So
        self.month = month or _dt.date(t.year, t.month, 1)
        self.week_start = t - _dt.timedelta(days=t.weekday())
        self.dp = m.prop((view or {}).get("date_by")) or next((p for p in m.props if p.get("type") == "date"), None)
        self._build()

    def _item(self, r, t):
        lb = Gtk.Label(label=((t + " ") if t else "") + (r["title"] or _("Untitled")), xalign=0, ellipsize=Pango.EllipsizeMode.END)
        b = Gtk.Button(child=lb)
        b.add_css_class("hk-calitem")
        _color_css(b, self.m.color(r, self.view))
        b.connect("clicked", lambda *_, r=r: self.open(r))
        self.attach_menu(b, r)
        return b

    def _build(self):
        while (c := self.get_first_child()):
            self.remove(c)
        bar = Gtk.Box(spacing=6)
        bar.append(flat_button(icon="go-previous-symbolic", cb=lambda: self._shift(-1)))
        if self.week:
            a, b = self.week_start, self.week_start + _dt.timedelta(days=6)
            title = f"{day_text(a.isoformat())} – {day_text(b.isoformat())}"
        else:
            title = month_text(self.month)
        ml = Gtk.Label(label=title, width_chars=16)
        ml.add_css_class("heading")
        bar.append(ml)
        bar.append(flat_button(icon="go-next-symbolic", cb=lambda: self._shift(1)))
        bar.append(Gtk.Box(hexpand=True))
        bar.append(flat_button(label=_("Today"), cb=lambda: self._today()))
        self.append(bar)
        if not self.dp:
            self.append(Gtk.Label(label=_("A calendar needs a date property"), css_classes=["dim-label"]))
            return
        if self.week:
            self._build_week()
            return
        mth = self.month
        grid = Gtk.Grid(column_homogeneous=True, row_spacing=2, column_spacing=2)
        for i, n in enumerate(weekday_names()):
            grid.attach(Gtk.Label(label=n, css_classes=["caption", "dim-label"]), i, 0, 1, 1)
        lead = mth.weekday()
        nxt = _dt.date(mth.year + (mth.month == 12), mth.month % 12 + 1, 1)
        days = (nxt - mth).days
        for k in range(days):
            d = mth + _dt.timedelta(days=k)
            pos = lead + k
            grid.attach(self._cell(d, 3, 76), pos % 7, 1 + pos // 7, 1, 1)
        self.append(grid)

    def _cell(self, d, limit, height):
        cell = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        cell.add_css_class("hk-calcell")
        cell.set_size_request(-1, height)
        top = Gtk.Box()
        dl = Gtk.Label(label=str(d.day), xalign=0, hexpand=True)
        dl.add_css_class("caption")
        if d == _dt.date.today():
            dl.add_css_class("accent")
            dl.add_css_class("heading")
        else:
            dl.add_css_class("dim-label")
        top.append(dl)
        ds = d.isoformat()
        if self.dp.get("type") not in ("created", "edited", "formula", "rollup") and self.m.can_add:
            top.append(flat_button(icon="list-add-symbolic", css="hk-tiny dim-label", cb=lambda ds=ds: self.new_row({self.dp["id"]: ds})))
        cell.append(top)
        items = cal_items(self.m, self.dp, self.rows, d)
        for r, t in items[:limit]:
            cell.append(self._item(r, t))
        if len(items) > limit:
            cell.append(Gtk.Label(label=f"+{len(items) - limit}", xalign=0, css_classes=["caption", "dim-label"]))
        return cell

    def _build_week(self):
        narrow = 0 < self.get_width() < 560 or bool(getattr(self.win, "very_narrow", False))
        if narrow:                                        # schmal: Tagesliste
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
            for k in range(7):
                d = self.week_start + _dt.timedelta(days=k)
                h = Gtk.Label(label=weekday_names()[k] + " " + day_text(d.isoformat()), xalign=0)
                h.add_css_class("heading" if d == _dt.date.today() else "dim-label")
                box.append(h)
                items = cal_items(self.m, self.dp, self.rows, d)
                for r, t in items:
                    box.append(self._item(r, t))
                if not items:
                    box.append(Gtk.Label(label="–", xalign=0, css_classes=["dim-label", "caption"]))
            self.append(box)
            return
        grid = Gtk.Grid(column_homogeneous=True, row_spacing=2, column_spacing=2)
        grid.add_css_class("hk-calweek")
        for k in range(7):
            d = self.week_start + _dt.timedelta(days=k)
            grid.attach(Gtk.Label(label=weekday_names()[k], css_classes=["caption", "dim-label"]), k, 0, 1, 1)
            grid.attach(self._cell(d, 20, 220), k, 1, 1, 1)
        self.append(grid)

    def _shift(self, d):
        if self.week:
            self.week_start += _dt.timedelta(days=7 * d)
            self._build()
            return
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
        self.week_start = t - _dt.timedelta(days=t.weekday())
        self._build()


# --- Datenbank-Ansicht (Seite oder Block) ---------------------------------------------------
class DatabaseView(Gtk.Box):
    __gtype_name__ = "HKDatabaseView"

    def __init__(self, win, db_id, view_id=None, local_views=None, embedded=False, on_local_views=None, host_locked=False,
                 show_title=True, show_tabs=True, on_head=None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.win, self.db_id, self.embedded = win, db_id, embedded
        self.m = DBModel(db_id)
        # 2.5: eingebetteter Block - Datenbanktitel/Ansichts-Reiter ausblendbar (hide_title/hide_tabs im Block)
        self.m.show_title, self.m.show_tabs = bool(show_title), bool(show_tabs)
        self.m.on_head = (lambda k, on: (on_head(k, on), self.apply_head())) if (embedded and on_head) else None
        # Block auf einer gesperrten Seite: kein "Neu", keine Ansichts-Einstellungen, kein "+" (Ansicht) - nur ansehen,
        # Eintraege oeffnen, suchen und zwischen den Ansichten wechseln
        self.m.host_locked = bool(host_locked)
        if local_views and on_local_views:
            self.m.local_views = list(local_views)
            self.m.on_local_views = on_local_views
        self.local_readonly = bool(local_views) and on_local_views is None
        self.current = view_id
        self.cal_month = None
        self.head = self._header() if embedded else None
        if self.head is not None:
            self.append(self.head)
            self.head.set_visible(self.m.show_title)
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

    def apply_head(self):
        if self.head is not None:
            self.head.set_visible(self.m.show_title)
        self.bar.update()

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
        if t == "chart" and store.has("chart1"):             # 2.5: Diagramm (Server rechnet, Cairo zeichnet)
            from .dbviews25 import ChartView
            w = ChartView(self.win, self.m, v)
        elif t == "timeline":                               # 2.5: Zeitleiste nur lesend
            from .dbviews25 import TimelineView
            w = TimelineView(self.win, self.m, v, narrow=narrow)
        elif t == "board":
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

    def new_entry(self, template=None, blank=False):
        v = self.view
        mode = (v or {}).get("open") or "side"
        if template is None and not blank:                 # "Neu": Standardvorlage (2.5), wenn gesetzt
            dt = self.m.default_template()
            template = dt["id"] if dt else None
        if template:
            self.m.new_from_template(template, done=lambda i: self.win.open_entry(i, mode))
        else:
            self.m.add(self.m.defaults(v), done=lambda i: self.win.open_entry(i, mode))


class _RowModel:
    """kleines Modell fuer die Eintragsseite: Personen-Verzeichnis, Datenbank (Personen-Auswahl), gesperrt"""
    def __init__(self, db_id, people, locked):
        self.db_id, self.people, self.locked = db_id, people, locked


class EditableProps(Gtk.Grid):
    """Eigenschaften eines Datenbank-Eintrags (auf seiner Seite)"""
    __gtype_name__ = "HKEditableProps"

    def __init__(self, view, schema):
        super().__init__(row_spacing=2, column_spacing=8)
        self.view = view
        doc = view.doc
        page = doc.page if isinstance(doc.page, dict) else {}
        people = page.get("people") if isinstance(page.get("people"), dict) else {}
        locked = bool(getattr(doc, "read_only", False))
        rm = _RowModel(page.get("parent_id"), people, locked)
        row = {"id": doc.id, "fx_err": page.get("fx_err")}
        i = 0
        if page.get("blocked") is True:                     # 2.5 subtasks1
            from .dbcells import blocked_mark
            bx = Gtk.Box(spacing=6)
            bx.append(blocked_mark())
            bx.append(Gtk.Label(label=_("Blocked – waiting for another entry"), xalign=0, css_classes=["dim-label"]))
            self.attach(bx, 0, i, 2, 1)
            i += 1
        for p in schema.get("props") or []:
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
            ed = prop_editor(p, v, lambda nv, p=p: self._save(p, nv), model=rm, row=row, win=getattr(view, "win", None))
            ed.set_hexpand(True)
            ed.set_halign(Gtk.Align.START if t != "text" else Gtk.Align.FILL)
            if dv.is_fx(p):
                ed.set_tooltip_text(_("Calculated – edit the formula on the web"))
            self.attach(ed, 1, i, 1, 1)
            i += 1

    def _save(self, p, v):
        if dv.is_fx(p):                                       # berechnet: nie zurueckschreiben
            return
        doc = self.view.doc
        props = dict(doc.props)
        props[p["id"]] = v
        doc.page["props"] = props

        def done(ans):
            store.drop_cache(f"api/notes/{doc.id}")
            if isinstance(ans, dict) and any(k in ans for k in ("calc", "values", "fx_err", "people")):
                row = {"props": dict(doc.page.get("props") or {}), "fx_err": doc.page.get("fx_err")}
                ppl = dv.apply_patch_answer(row, ans)
                doc.page["props"] = row["props"]
                if row.get("fx_err"):
                    doc.page["fx_err"] = row["fx_err"]
                else:
                    doc.page.pop("fx_err", None)
                if ppl:
                    doc.page["people"] = dv.merge_people(doc.page.get("people"), ppl)
                GLib.idle_add(lambda: (self.view.rebuild(), False)[1])
        store.run("PATCH", f"api/notes/{doc.id}", {"props": {p["id"]: v}}, data_changed=True, key=("note", doc.id), done=done)
        GLib.idle_add(lambda: (self.view.rebuild(), False)[1])


def _iso(ts):
    return _dt.date.fromtimestamp(ts).isoformat() if ts else None
