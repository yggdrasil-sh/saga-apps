"""Datenbank-Zellen Saga 2.5 (GTK): Formel/Rollup (nur lesen), Personen (Anzeige + Auswahl), Dateien (Anzeige, Oeffnen,
Hochladen), Datum mit Uhrzeit/Zeitraum (Picker), Status mit Gruppen. Logik in dbvalues.py."""
import datetime as _dt
import os

from gi.repository import Adw, GLib, Gtk, Pango

from . import dbvalues as dv
from .api import client
from .blocks import chip, flat_button
from .i18n import _
from .store import store
from .util import bg


def _caption(text, dim=True):
    lb = Gtk.Label(label=text, xalign=0, ellipsize=Pango.EllipsizeMode.END)
    lb.add_css_class("caption")
    if dim:
        lb.add_css_class("dim-label")
    return lb


# --- Formel / Rollup ------------------------------------------------------------------------------------------------
def fx_widget(p, v, err=None, compact=True):
    """Wert einer Formel/eines Rollups (nur lesen) - "#Fehler" mit Erklaerung im Tooltip"""
    hint = _("Calculated – edit the formula on the web")
    if err:
        lb = Gtk.Label(label=_("#Error"), xalign=0)
        lb.add_css_class("hk-fx-err")
        lb.add_css_class("caption")
        lb.set_tooltip_text(dv.fx_error_text(err))
        lb.hk_fx_err = err
        return lb
    rt = p.get("rtype")
    if v is None or v == "" or v == []:
        return None
    if rt == "checkbox":
        im = Gtk.Image.new_from_icon_name("checkbox-checked-symbolic" if v is True else "checkbox-symbolic")
        im.set_tooltip_text(hint)
        im.set_opacity(0.7)
        return im
    if rt == "list" and isinstance(v, list):
        box = Gtk.Box(spacing=3)
        items = [str(x) for x in v]
        for x in items[:3] if compact else items:
            box.append(chip(x, None))
        if compact and len(items) > 3:
            box.append(chip(f"+{len(items) - 3}", None))
        box.set_tooltip_text(hint)
        return box
    if rt == "number" and isinstance(v, (int, float)) and not isinstance(v, bool):
        from .database import value_widget
        w = value_widget(dict(p, type="number"), float(v))
        if w is not None:
            w.set_tooltip_text(hint)
        return w
    lb = Gtk.Label(label=dv.fx_text(p, v), xalign=0, ellipsize=Pango.EllipsizeMode.END if compact else Pango.EllipsizeMode.NONE,
                   wrap=not compact)
    lb.add_css_class("caption")
    lb.set_tooltip_text(hint)
    return lb


# --- Personen -------------------------------------------------------------------------------------------------------
def person_chip(ref, people, small=True):
    info = dv.person_info(ref, people)
    box = Gtk.Box(spacing=4)
    box.add_css_class("hk-person")
    av = Adw.Avatar(size=18 if small else 24, text=info["name"], show_initials=True)
    if info["avatar"]:
        from . import avatars
        avatars.attach(av, info["avatar"], 24)
    box.append(av)
    lb = Gtk.Label(label=dv.person_label(ref, people), ellipsize=Pango.EllipsizeMode.END)
    lb.add_css_class("caption")
    box.append(lb)
    if info["gone"]:
        box.add_css_class("dim-label")
        box.add_css_class("hk-person-gone")
    box.set_tooltip_text(info["name"])
    box.hk_person = ref
    return box


def people_widget(v, people, limit=None):
    refs = dv.person_refs(v)
    if not refs:
        return None
    box = Gtk.Box(spacing=4)
    for r in refs[:limit] if limit else refs:
        box.append(person_chip(r, people))
    if limit and len(refs) > limit:
        box.append(chip(f"+{len(refs) - limit}", None))
    return box


class PersonPicker(Gtk.Popover):
    """Auswahl ueber GET /api/notes/{db}/people?q= (nur Personen mit Zugriff); Ergebnis: Liste von Kennungen"""
    __gtype_name__ = "HKPersonPicker"

    def __init__(self, db_id, p, refs, people, setv):
        super().__init__()
        self.db_id, self.p, self.setv = db_id, p, setv
        self.multi = p.get("multi") is not False
        self.cur = list(refs)
        self.orig = list(refs)
        self.people = dict(people or {})
        self.found = []
        self._seq = 0
        bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, margin_top=6, margin_bottom=6, margin_start=6, margin_end=6)
        self.se = Gtk.SearchEntry(placeholder_text=_("Search people"))
        self.se.connect("search-changed", lambda *_: self.search())
        bx.append(self.se)
        self.lst = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True, max_content_height=320)
        sw.set_child(self.lst)
        sw.set_size_request(280, -1)
        bx.append(sw)
        self.msg = _caption("")
        bx.append(self.msg)
        self.set_child(bx)
        self.connect("closed", lambda *_: self.cur != self.orig and self.setv(list(self.cur) or None))
        self.fill()
        self.search()

    def search(self):
        self._seq += 1
        seq = self._seq
        from urllib.parse import quote
        q = self.se.get_text().strip()
        path = f"api/notes/{self.db_id}/people?q={quote(q, safe='')}"

        def fin(j):
            if seq != self._seq:
                return
            self.found = [x for x in (j or {}).get("people") or [] if isinstance(x, dict) and isinstance(x.get("ref"), str)]
            for x in self.found:
                self.people.setdefault(x["ref"], {"name": x.get("name"), "avatar": x.get("avatar"), "me": x.get("me") is True,
                                                  "remote": x.get("remote") is True})
            self.msg.set_label(_("More people – refine the search") if (j or {}).get("more") else "")
            self.fill()

        def err(e):
            if seq == self._seq:
                self.msg.set_label(_("People couldn’t be loaded"))
        bg(client.get, path, done=fin, error=err)

    def fill(self):
        while (c := self.lst.get_first_child()):
            self.lst.remove(c)
        shown = list(self.cur) + [x["ref"] for x in self.found if x["ref"] not in self.cur]
        group = None
        for ref in shown:
            row = Gtk.Box(spacing=6)
            if self.multi:
                b = Gtk.CheckButton(active=ref in self.cur)
                b.connect("toggled", lambda c, ref=ref: self._toggle(ref, c.get_active()))
            else:
                b = Gtk.CheckButton(active=ref in self.cur, group=group)
                group = group or b
                b.connect("toggled", lambda c, ref=ref: c.get_active() and self._one(ref))
            b.set_child(person_chip(ref, self.people, small=False))
            row.append(b)
            self.lst.append(row)
        if self.cur:
            self.lst.append(flat_button(label=_("Clear"), css="dim-label", cb=lambda: (self.cur.clear(), self.popdown())))
        if not shown:
            self.lst.append(_caption(_("No people found")))

    def _toggle(self, ref, on):
        if on and ref not in self.cur:
            self.cur.append(ref)
        elif not on and ref in self.cur:
            self.cur.remove(ref)

    def _one(self, ref):
        self.cur = [ref]
        self.popdown()


def person_editor(p, v, setv, model=None):
    mb = Gtk.MenuButton()
    mb.add_css_class("flat")
    mb.add_css_class("hk-cellbtn")
    people = getattr(model, "people", None) or {}
    w = people_widget(v, people, limit=3)
    from .database import _leer
    mb.set_child(w or _leer())
    db_id = getattr(model, "db_id", None)
    if db_id is None or getattr(model, "locked", False):
        mb.set_sensitive(w is not None)
        mb.set_tooltip_text(", ".join(dv.people_names(v, people)) or None)
        return mb
    mb.set_create_popup_func(lambda m: m.set_popover(PersonPicker(db_id, p, dv.person_refs(v), people, setv)))
    return mb


# --- Dateien --------------------------------------------------------------------------------------------------------
def file_chip(win, f):
    b = Gtk.Button()
    b.add_css_class("flat")
    b.add_css_class("hk-file-chip")
    bx = Gtk.Box(spacing=4)
    bx.append(Gtk.Image.new_from_icon_name("image-x-generic-symbolic" if f.get("image") else "mail-attachment-symbolic"))
    lb = Gtk.Label(label=f["name"], ellipsize=Pango.EllipsizeMode.MIDDLE, max_width_chars=22)
    lb.add_css_class("caption")
    bx.append(lb)
    b.set_child(bx)
    b.set_tooltip_text(" · ".join(x for x in (f["name"], dv.size_text(f.get("size"))) if x))
    b.hk_file = f["url"]
    if win is not None:
        b.connect("clicked", lambda *_: win.download_open(f["url"], f["name"]))
    return b


def files_widget(v, win=None, limit=None):
    items = dv.file_items(v)
    if not items:
        return None
    box = Gtk.Box(spacing=3)
    for f in items[:limit] if limit else items:
        box.append(file_chip(win, f))
    if limit and len(items) > limit:
        box.append(chip(f"+{len(items) - limit}", None))
    return box


def upload_file(path, row_id, done, error):
    """Hintergrund: Datei hochladen (POST /api/files?note=<zeile>, gehoert dem Eigentuemer der Datenbank) -> {url, name}"""
    name = os.path.basename(path) or "datei"

    def work():
        with open(path, "rb") as fh:
            data = fh.read()
        f = client.upload(data, name, field="file", note=row_id)
        return {"url": f["url"], "name": f.get("name") or name}
    bg(work, done=done, error=error, media=True)


def files_editor(p, v, setv, row_id=None, win=None, locked=False):
    """Dateien anzeigen/oeffnen, hinzufuegen (Upload + PATCH der ganzen Liste), entfernen"""
    items = dv.file_items(v)
    box = Gtk.Box(spacing=3)
    for f in items:
        box.append(file_chip(win, f))
    if locked or row_id is None:
        if not items:
            from .database import _leer
            box.append(_leer())
        return box
    mx = p.get("max") if isinstance(p.get("max"), int) and 1 <= p.get("max") <= 20 else 10
    mb = Gtk.MenuButton(icon_name="list-add-symbolic" if not items else "view-more-symbolic", tooltip_text=_("Files"))
    mb.add_css_class("flat")
    mb.add_css_class("hk-tiny")

    def popup(m):
        pop = Gtk.Popover()
        bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, margin_top=6, margin_bottom=6, margin_start=6, margin_end=6)
        for i, f in enumerate(items):
            r = Gtk.Box(spacing=6)
            r.append(Gtk.Label(label=f["name"], xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.MIDDLE, max_width_chars=28))
            r.append(flat_button(icon="window-close-symbolic", tooltip=_("Remove"), css="hk-tiny",
                                 cb=lambda i=i: (pop.popdown(), setv(dv.files_for_patch([x for k, x in enumerate(items) if k != i]) or None))))
            bx.append(r)
        add = flat_button(icon="list-add-symbolic", label=_("Add file …"), cb=lambda: (pop.popdown(), choose()))
        add.set_sensitive(len(items) < mx)
        if len(items) >= mx:
            add.set_tooltip_text(_("At most {n} files").format(n=mx))
        bx.append(add)
        pop.set_child(bx)
        m.set_popover(pop)

    def choose():
        fd = Gtk.FileDialog(title=_("Add file"))

        def chosen(d, res):
            try:
                gf = d.open_finish(res)
            except GLib.Error:
                return
            path = gf.get_path() if gf is not None else None
            if not path:
                return
            if win is not None:
                win.toast(_("Uploading “{name}” …").format(name=os.path.basename(path)))
            upload_file(path, row_id, done=lambda f: setv(dv.files_for_patch(items) + [f]),
                        error=lambda e: win is not None and win.toast(_("Upload failed: {error}").format(error=e)))
        fd.open(box.get_root() if isinstance(box.get_root(), Gtk.Window) else None, None, chosen)
    mb.set_create_popup_func(popup)
    box.append(mb)
    return box


# --- Datum mit Uhrzeit / Zeitraum -----------------------------------------------------------------------------------
class DatePicker(Gtk.Box):
    """Kalender + optional Uhrzeit (Stunden/Minuten) und Ende (zweiter Kalender). full=False (Server ohne datetime1):
    nur Tag. apply(value) beim Uebernehmen."""
    __gtype_name__ = "HKDatePicker"

    def __init__(self, p, v, apply, full=True):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=6, margin_bottom=6, margin_start=6, margin_end=6)
        self.apply_cb, self.old, self.full = apply, v, full
        pr = dv.parse_date(v) if isinstance(v, str) else None
        viewer = dv.viewer_tz()
        today = _dt.date.today()
        s_day, s_time = pr[0].local(viewer) if pr else (today, None)
        e_day, e_time = pr[1].local(viewer) if pr and pr[1] else (None, None)
        self.cal = Gtk.Calendar()
        self._select(self.cal, s_day)
        self.append(self.cal)
        self.time_sw = Gtk.Switch(active=bool(s_time) or (not pr and p.get("time") is True), valign=Gtk.Align.CENTER)
        self._srow, self.h, self.m = self._time_row(_("Time"), s_time or (9, 0), self.time_sw)
        self.end_sw = Gtk.Switch(active=e_day is not None or (not pr and p.get("range") is True), valign=Gtk.Align.CENTER)
        er = Gtk.Box(spacing=8)
        er.append(Gtk.Label(label=_("End date"), xalign=0, hexpand=True))
        er.append(self.end_sw)
        self.ecal = Gtk.Calendar()
        self._select(self.ecal, e_day or s_day)
        eh = e_time or ((s_time[0] + 1, s_time[1]) if s_time and s_time[0] < 23 else (17, 0))
        self._erow, self.eh, self.em = self._time_row(_("End time"), eh, None)
        if full:
            self.append(self._srow)
            self.append(er)
            self.append(self.ecal)
            self.append(self._erow)
            self.end_sw.connect("notify::active", lambda *_: self._sync())
            self.time_sw.connect("notify::active", lambda *_: self._sync())
        self.err = _caption("")
        self.err.add_css_class("error")
        self.append(self.err)
        btns = Gtk.Box(spacing=6, homogeneous=True)
        btns.append(flat_button(label=_("Today"), cb=lambda: self._select(self.cal, _dt.date.today())))
        if v:
            btns.append(flat_button(label=_("Clear"), css="error", cb=lambda: self.apply_cb(None)))
        ok = Gtk.Button(label=_("Apply"))
        ok.add_css_class("suggested-action")
        ok.connect("clicked", lambda *_: self.commit())
        btns.append(ok)
        self.append(btns)
        self._sync()

    def _time_row(self, title, hm, sw):
        r = Gtk.Box(spacing=6)
        r.append(Gtk.Label(label=title, xalign=0, hexpand=True))
        h = Gtk.SpinButton.new_with_range(0, 23, 1)
        m = Gtk.SpinButton.new_with_range(0, 59, 5)
        for x, val in ((h, hm[0]), (m, hm[1])):
            x.set_value(val)
            x.set_wrap(True)
            x.set_numeric(True)
            x.set_width_chars(2)
            x.connect("output", self._two_digits)
        h.update_property([Gtk.AccessibleProperty.LABEL], [_("Hour")])
        m.update_property([Gtk.AccessibleProperty.LABEL], [_("Minute")])
        r.append(h)
        r.append(Gtk.Label(label=":"))
        r.append(m)
        if sw is not None:
            r.append(sw)
        return r, h, m

    @staticmethod
    def _two_digits(sb):
        sb.set_text("%02d" % int(sb.get_value()))
        return True

    @staticmethod
    def _select(cal, d):
        cal.select_day(GLib.DateTime.new_local(d.year, d.month, d.day, 0, 0, 0))

    @staticmethod
    def _day(cal):
        g = cal.get_date()
        return _dt.date(g.get_year(), g.get_month(), g.get_day_of_month())

    def _sync(self):
        if not self.full:
            return
        on_t, on_e = self.time_sw.get_active(), self.end_sw.get_active()
        self.h.set_sensitive(on_t)
        self.m.set_sensitive(on_t)
        self.ecal.set_visible(on_e)
        self._erow.set_visible(on_e and on_t)

    def value(self):
        sd = self._day(self.cal)
        st = (int(self.h.get_value()), int(self.m.get_value())) if self.full and self.time_sw.get_active() else None
        ed = et = None
        if self.full and self.end_sw.get_active():
            ed = self._day(self.ecal)
            et = (int(self.eh.get_value()), int(self.em.get_value())) if st else None
            if not dv.valid_range(sd, st, ed, et):
                return False
        return dv.compose(sd, st, ed, et, old=self.old, full=self.full)

    def commit(self):
        v = self.value()
        if v is False:
            self.err.set_label(_("The end is before the start."))
            return
        self.apply_cb(v)


def date_editor(p, v, setv):
    """Datum (mit Uhrzeit/Zeitraum, wenn der Server datetime1 meldet) - Anzeige in der Zeitzone des Geraets"""
    from .database import _leer
    mb = Gtk.MenuButton()
    mb.add_css_class("flat")
    mb.add_css_class("hk-cellbtn")
    if v:
        lb = Gtk.Label(label=dv.date_text(v), xalign=0)
        orig = dv.original_text(v)
        if orig:
            lb.set_tooltip_text(_("Entered as {value}").format(value=orig))
        mb.set_child(lb)
    else:
        mb.set_child(_leer())
    full = store.has("datetime1")

    def popup(m):
        pop = Gtk.Popover()

        def done(val):
            pop.popdown()
            if val != v:
                setv(val)
        pop.set_child(DatePicker(p, v, done, full=full))
        m.set_popover(pop)
    mb.set_create_popup_func(popup)
    return mb


def date_label(v):
    if not isinstance(v, str) or not v:
        return None
    lb = Gtk.Label(label=dv.date_text(v))
    lb.add_css_class("caption")
    lb.add_css_class("dim-label")
    return lb


# --- Status ---------------------------------------------------------------------------------------------------------
def status_menu_entries(p, cur, setv):
    """Auswahl nach Gruppen (Zu erledigen / In Arbeit / Erledigt), Werte bleiben Options-IDs"""
    ents = []
    for g, opts in dv.grouped_options(p):
        if g is not None:
            if ents:
                ents.append(None)
            ents.append((dv.group_name(g), lambda: None))
        for o in opts:
            ents.append((dv.status_label(p, o), (lambda i=o.get("id"): setv(i))))
    return ents


def blocked_mark():
    lb = Gtk.Label(label="⛔")
    lb.set_tooltip_text(_("Blocked – waiting for another entry"))
    lb.add_css_class("hk-blocked")
    lb.update_property([Gtk.AccessibleProperty.LABEL], [_("Blocked")])
    return lb


def open_in_web_hint(text=None):
    lb = _caption(text or _("Edit on the web"))
    return lb

