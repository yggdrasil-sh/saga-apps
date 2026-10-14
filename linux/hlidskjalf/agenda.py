"""Aufgaben und Termine wie Agenda.swift: Schnell-Eingabe, Gruppen nach Faelligkeit, Bearbeiten; externe Kalender."""
import datetime as _dt

from gi.repository import Adw, Gdk, GLib, Gtk, Pango

from .blocks import context_menu, flat_button, menu_popover, more_button
from .store import store, watch
from .i18n import _, ngettext
from .util import day_text, iso_day, parse_day, time_text, weekday_day_text

REPEAT = [(None, _("Never")), ("daily", _("Daily")), ("weekdays", _("Weekdays")), ("weekly", _("Weekly")), ("monthly", _("Monthly")),
          ("yearly", _("Yearly"))]


def _clear(box):
    while (c := box.get_first_child()):
        box.remove(c)


class TaskRow(Gtk.Box):
    def __init__(self, win, t, compact=False):
        super().__init__(spacing=10, margin_top=2, margin_bottom=2)
        self.win, self.t = win, t
        done = bool(t.get("done"))
        ck = Gtk.Button(icon_name="object-select-symbolic" if done else "radio-symbolic", valign=Gtk.Align.CENTER)
        ck.add_css_class("flat")
        ck.add_css_class("circular")
        ck.add_css_class("hk-taskcheck")
        if t.get("prio") == 1 and not done:
            ck.add_css_class("error")
        ck.set_tooltip_text(_("Reopen") if done else _("Mark done"))
        ck.connect("clicked", lambda *_: store.update_task(t["id"], {"done": False}) if done else store.complete_task(t))
        self.append(ck)
        inner = Gtk.Box(spacing=6)
        tx = Gtk.Label(label=t.get("text") or "", xalign=0, hexpand=True, wrap=True, lines=2, ellipsize=Pango.EllipsizeMode.END)
        if done:
            tx.add_css_class("dim-label")
            tx.set_markup(f"<s>{GLib.markup_escape_text(t.get('text') or '')}</s>")
        inner.append(tx)
        if t.get("repeat"):
            inner.append(Gtk.Image.new_from_icon_name("media-playlist-repeat-symbolic"))
        due = t.get("due") or ""
        if due and not done:
            dl = Gtk.Label(label=day_text(due))
            dl.add_css_class("caption")
            dl.add_css_class("error" if due < iso_day() else "dim-label")
            inner.append(dl)
        if t.get("prio") == 1:
            fl = Gtk.Image.new_from_icon_name("emblem-important-symbolic")
            fl.add_css_class("error")
            inner.append(fl)
        b = Gtk.Button(child=inner, hexpand=True)
        b.add_css_class("flat")
        b.add_css_class("hk-plain")
        b.connect("clicked", lambda *_: TaskEditor(win, t).present(win))
        self.append(b)

        def entries():
            ents = [(_("Edit …"), lambda: TaskEditor(win, t).present(win))]
            ents.append((_("Reopen"), lambda: store.update_task(t["id"], {"done": False})) if done else
                        (_("Mark done"), lambda: store.complete_task(t)))
            return ents + [None, (_("Delete"), lambda: store.delete_task(t["id"]), True)]
        mb = more_button(entries, _("Task actions"))      # sichtbar (auch ohne Maus/Rechtsklick)
        mb.add_css_class("dim-label")
        if compact:                                       # Seitenleiste: bei Maus, Fokus oder Touch
            mb.add_css_class("hk-reveal")
            self.add_css_class("hk-reveal-zone")
        self.append(mb)
        context_menu(b, lambda w: menu_popover(w, entries()).popup())


class TaskList(Gtk.Box):
    """Aufgabenliste wie im Dashboard"""
    __gtype_name__ = "HKTaskList"

    def __init__(self, win, compact=False):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=4, hexpand=True)
        self.win, self.compact = win, compact
        self.show_done = False
        add = Gtk.Box(spacing=8)
        add.append(Gtk.Image.new_from_icon_name("list-add-symbolic"))
        self.entry = Gtk.Entry(placeholder_text=_("New task …"), hexpand=True)
        self.entry.add_css_class("flat")
        self.entry.connect("activate", self._add)
        add.append(self.entry)
        self.append(add)
        self.list = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        self.append(self.list)
        watch(self, "today-changed", self.fill)
        self.fill()

    def _add(self, e):
        t = e.get_text().strip()
        e.set_text("")
        if t:
            store.add_task(t)

    def focus_entry(self):
        self.entry.grab_focus()

    def fill(self):
        _clear(self.list)
        today = iso_day()
        tomorrow = (_dt.date.today() + _dt.timedelta(days=1)).isoformat()
        all_ = store.tasks
        overdue = _("Overdue")
        groups = [(overdue, [t for t in all_ if (t.get("due") or "9") < today]), (_("Today"), [t for t in all_ if t.get("due") == today]),
                  (_("Tomorrow"), [t for t in all_ if t.get("due") == tomorrow]), (_("Later"), [t for t in all_ if (t.get("due") or "") > tomorrow]),
                  (_("No date"), [t for t in all_ if not t.get("due")])]
        groups = [g for g in groups if g[1]]
        if not all_:
            lb = Gtk.Label(label=_("All done 🎉"), xalign=0, margin_top=4)
            lb.add_css_class("dim-label")
            self.list.append(lb)
        for name, ts in groups:
            if not self.compact or len(groups) > 1:
                h = Gtk.Label(label=name, xalign=0, margin_top=6)
                h.add_css_class("caption-heading")
                h.add_css_class("error" if name == overdue else "dim-label")
                self.list.append(h)
            for t in ts:
                self.list.append(TaskRow(self.win, t))
        if store.done_tasks:
            b = flat_button(icon="pan-down-symbolic" if self.show_done else "pan-end-symbolic", label=ngettext("{n} done", "{n} done", len(store.done_tasks)).format(n=len(store.done_tasks)),
                            css="dim-label hk-small", cb=self._toggle_done)
            b.set_halign(Gtk.Align.START)
            self.list.append(b)
            if self.show_done:
                for t in store.done_tasks[: 5 if self.compact else 50]:
                    self.list.append(TaskRow(self.win, t))

    def _toggle_done(self):
        self.show_done = not self.show_done
        self.fill()


class DateButton(Gtk.MenuButton):
    """Datum (+ optional Uhrzeit) waehlen"""
    __gtype_name__ = "HKDateButton"

    def __init__(self, value=None, with_time=False, on_change=None):
        super().__init__()
        self.value = value or _dt.datetime.now().replace(second=0, microsecond=0)
        self.with_time, self.on_change = with_time, on_change
        pop = Gtk.Popover()
        bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=6, margin_bottom=6, margin_start=6, margin_end=6)
        self.cal = Gtk.Calendar()
        self.cal.select_day(GLib.DateTime.new_local(self.value.year, self.value.month, self.value.day, 0, 0, 0))
        self.cal.connect("day-selected", self._day)
        bx.append(self.cal)
        self.tb = Gtk.Box(spacing=4, halign=Gtk.Align.CENTER)
        self.hh = Gtk.SpinButton.new_with_range(0, 23, 1)
        self.mm = Gtk.SpinButton.new_with_range(0, 59, 5)
        self.hh.set_value(self.value.hour)
        self.mm.set_value(self.value.minute)
        for s in (self.hh, self.mm):
            s.set_orientation(Gtk.Orientation.VERTICAL)
            s.set_wrap(True)
            s.connect("value-changed", self._time)
        self.tb.append(self.hh)
        self.tb.append(Gtk.Label(label=":"))
        self.tb.append(self.mm)
        bx.append(self.tb)
        pop.set_child(bx)
        self.set_popover(pop)
        self.set_time(with_time)

    def set_time(self, on):
        self.with_time = on
        self.tb.set_visible(on)
        self._label()

    def _label(self):
        v = self.value
        s = weekday_day_text(v, year=True)
        self.set_label(s + (", " + time_text(v) if self.with_time else ""))

    def _day(self, c):
        d = c.get_date()
        self.value = self.value.replace(year=d.get_year(), month=d.get_month(), day=d.get_day_of_month())
        self._label()
        if self.on_change:
            self.on_change(self.value)

    def _time(self, *_):
        self.value = self.value.replace(hour=int(self.hh.get_value()), minute=int(self.mm.get_value()))
        self._label()
        if self.on_change:
            self.on_change(self.value)

    def set_value(self, v):
        self.value = v
        self.cal.select_day(GLib.DateTime.new_local(v.year, v.month, v.day, 0, 0, 0))
        self.hh.set_value(v.hour)
        self.mm.set_value(v.minute)
        self._label()


def _form_dialog(title, w=420, h=440):
    dlg = Adw.Dialog(title=title, content_width=w, content_height=h)
    tv = Adw.ToolbarView()
    hb = Adw.HeaderBar(show_end_title_buttons=False, show_start_title_buttons=False)
    tv.add_top_bar(hb)
    page = Adw.PreferencesPage()
    tv.set_content(page)
    dlg.set_child(tv)
    return dlg, hb, page


class TaskEditor(Adw.Dialog):
    def __init__(self, win, t):
        super().__init__(title=_("Task"), content_width=420, content_height=430)
        self.t = t
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar(show_end_title_buttons=False, show_start_title_buttons=False)
        cancel = Gtk.Button(label=_("Cancel"))
        cancel.connect("clicked", lambda *a: self.close())
        hb.pack_start(cancel)
        save = Gtk.Button(label=_("Save"))
        save.add_css_class("suggested-action")
        save.connect("clicked", lambda *_: self._save())
        hb.pack_end(save)
        tv.add_top_bar(hb)
        page = Adw.PreferencesPage()
        g = Adw.PreferencesGroup()
        self.text = Adw.EntryRow(title=_("Task"), text=t.get("text") or "")
        g.add(self.text)
        self.has_due = Adw.SwitchRow(title=_("Due"), active=bool(t.get("due")))
        g.add(self.has_due)
        d = parse_day(t.get("due")) or _dt.date.today()
        self.due = DateButton(_dt.datetime(d.year, d.month, d.day))
        self.due.set_valign(Gtk.Align.CENTER)
        dr = Adw.ActionRow(title=_("Date"))
        dr.add_suffix(self.due)
        g.add(dr)
        self.has_due.bind_property("active", dr, "sensitive", 2)
        self.prio = Adw.ComboRow(title=_("Priority"), model=Gtk.StringList.new([_("High"), _("Normal"), _("Low")]))
        self.prio.set_selected((t.get("prio") or 2) - 1)
        g.add(self.prio)
        self.rep = Adw.ComboRow(title=_("Repeat"), model=Gtk.StringList.new([n for _k, n in REPEAT]))
        self.rep.set_selected(next((i for i, (k, _n) in enumerate(REPEAT) if k == t.get("repeat")), 0))
        g.add(self.rep)
        page.add(g)
        g2 = Adw.PreferencesGroup()
        dl = Adw.ButtonRow(title=_("Delete task")) if hasattr(Adw, "ButtonRow") else None
        if dl:
            dl.add_css_class("destructive-action")
            dl.connect("activated", lambda *_: (store.delete_task(t["id"]), self.close()))
            g2.add(dl)
        else:
            b = Gtk.Button(label=_("Delete task"))
            b.add_css_class("destructive-action")
            b.connect("clicked", lambda *_: (store.delete_task(t["id"]), self.close()))
            g2.add(b)
        page.add(g2)
        tv.set_content(page)
        self.set_child(tv)

    def _save(self):
        txt = self.text.get_text().strip()
        if not txt:
            return
        v = self.due.value
        store.update_task(self.t["id"], {"text": txt, "due": v.date().isoformat() if self.has_due.get_active() else None,
                                         "prio": self.prio.get_selected() + 1, "repeat": REPEAT[self.rep.get_selected()][0], "today": iso_day()})
        self.close()


class EventRow(Gtk.Box):
    def __init__(self, e, show_day=False):
        super().__init__(spacing=10, margin_top=2, margin_bottom=2)
        # Kalenderfarbe vom Server: nur als gepruefte Farbe zeichnen (nie ungeprueft ins CSS)
        rgba = Gdk.RGBA()
        if not rgba.parse(str(e.get("color") or "")):
            rgba.parse("#3584e4")
        bar = Gtk.DrawingArea(content_width=4, content_height=30)
        bar.set_draw_func(_draw_bar, rgba)
        self.append(bar)
        col_ = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True)
        top = Gtk.Box(spacing=4)
        top.append(Gtk.Label(label=e.get("summary") or _("(untitled)"), xalign=0, ellipsize=Pango.EllipsizeMode.END))
        if e.get("series"):
            top.append(Gtk.Image.new_from_icon_name("media-playlist-repeat-symbolic"))
        col_.append(top)
        w = Gtk.Label(label=self._when(e, show_day), xalign=0, ellipsize=Pango.EllipsizeMode.END)
        w.add_css_class("caption")
        w.add_css_class("dim-label")
        col_.append(w)
        self.append(col_)

    @staticmethod
    def _when(e, show_day):
        if e.get("allday"):
            d = parse_day(e.get("start"))
            s = (weekday_day_text(d) + " · " if show_day and d else "") + _("all day")
        else:
            try:
                dt = _dt.datetime.fromisoformat(str(e.get("start")).replace("Z", "+00:00")).astimezone()
                s = (weekday_day_text(dt) + ", " if show_day else "") + time_text(dt)
            except ValueError:
                s = str(e.get("start"))
        if e.get("location"):
            s += " · " + e["location"]
        return s


def _draw_bar(area, cr, w, h, rgba):
    r = min(2.0, w / 2)
    cr.set_source_rgba(rgba.red, rgba.green, rgba.blue, rgba.alpha)
    cr.new_sub_path()
    cr.arc(w - r, r, r, -1.5708, 0)
    cr.arc(w - r, h - r, r, 0, 1.5708)
    cr.arc(r, h - r, r, 1.5708, 3.1416)
    cr.arc(r, r, r, 3.1416, 4.7124)
    cr.close_path()
    cr.fill()


class EventList(Gtk.Box):
    __gtype_name__ = "HKEventList"

    def __init__(self, win, days=14, compact=False):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=4, hexpand=True)
        self.win, self.days = win, days
        top = Gtk.Box(spacing=6)
        if compact:
            h = Gtk.Label(label="📅 " + ngettext("Events ({n} day)", "Events ({n} days)", days).format(n=days), xalign=0)
            h.add_css_class("heading")
            top.append(h)
        top.append(Gtk.Box(hexpand=True))
        top.append(flat_button(icon="list-add-symbolic", label=_("Event"), cb=lambda: EventEditor(win, None, self.reload).present(win)))
        self.append(top)
        self.list = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        self.append(self.list)
        watch(self, "events-changed", lambda: self.reload(True))
        c = store.cached_events(days)
        if c is not None:
            self.fill(c)
        self.reload()

    def reload(self, fresh=False):
        store.fetch_events(self.days, self.fill, fresh=fresh)

    def fill(self, evs):
        _clear(self.list)
        if not evs:
            lb = Gtk.Label(label=_("No events"), xalign=0)
            lb.add_css_class("dim-label")
            self.list.append(lb)
        for e in evs:
            b = Gtk.Button(child=EventRow(e, show_day=True))
            b.add_css_class("flat")
            b.add_css_class("hk-plain")
            b.connect("clicked", lambda *_, e=e: EventEditor(self.win, e, self.reload).present(self.win))
            self.list.append(b)


class EventEditor(Adw.Dialog):
    def __init__(self, win, ev, after=None, day=None):
        super().__init__(title=_("New event") if ev is None else _("Event"), content_width=440, content_height=560)
        self.win, self.ev, self.after = win, ev, after
        self.read_only = bool(ev and (ev.get("series") or ev.get("writable") is False))
        self.targets = []
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar(show_end_title_buttons=False, show_start_title_buttons=False)
        cancel = Gtk.Button(label=_("Close") if self.read_only else _("Cancel"))
        cancel.connect("clicked", lambda *_: self.close())
        hb.pack_start(cancel)
        if not self.read_only:
            self.save_btn = Gtk.Button(label=_("Create") if ev is None else _("Save"))
            self.save_btn.add_css_class("suggested-action")
            self.save_btn.connect("clicked", lambda *_: self._save())
            hb.pack_end(self.save_btn)
        tv.add_top_bar(hb)
        page = Adw.PreferencesPage()
        g = Adw.PreferencesGroup()
        self.title = Adw.EntryRow(title=_("Title"), text=(ev or {}).get("summary") or "")
        g.add(self.title)
        self.allday = Adw.SwitchRow(title=_("All day"), active=bool((ev or {}).get("allday")))
        g.add(self.allday)
        start, end = self._initial(ev, day)
        self.start = DateButton(start, with_time=not self.allday.get_active(), on_change=self._start_changed)
        self.end = DateButton(end, with_time=not self.allday.get_active())
        for t, w in ((_("Start"), self.start), (_("End"), self.end)):
            w.set_valign(Gtk.Align.CENTER)
            r = Adw.ActionRow(title=t)
            r.add_suffix(w)
            g.add(r)
        self.allday.connect("notify::active", lambda s, _: (self.start.set_time(not s.get_active()), self.end.set_time(not s.get_active())))
        if ev is None:
            self.cal = Adw.ComboRow(title=_("Calendar"), model=Gtk.StringList.new([_("Loading …")]))
            g.add(self.cal)
            store.cal_targets(self._targets)
        else:
            r = Adw.ActionRow(title=_("Calendar"), subtitle=GLib.markup_escape_text(ev.get("calendar") or ""))
            g.add(r)
        self.loc = Adw.EntryRow(title=_("Location"), text=(ev or {}).get("location") or "")
        g.add(self.loc)
        page.add(g)
        g3 = Adw.PreferencesGroup(title=_("Description"))
        self.desc = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR, height_request=90)
        self.desc.add_css_class("card")
        self.desc.set_left_margin(8)
        self.desc.set_top_margin(6)
        self.desc.get_buffer().set_text((ev or {}).get("description") or "")
        g3.add(self.desc)
        page.add(g3)
        self.msg = Gtk.Label(xalign=0, wrap=True, margin_start=12, margin_end=12)
        self.msg.add_css_class("dim-label")
        if self.read_only:
            self.msg.set_label("🔁 " + _("Recurring event – please edit it directly in mailcow/Nextcloud so the series is kept.")
                               if ev.get("series") else _("This calendar is read-only."))
        g4 = Adw.PreferencesGroup()
        g4.add(self.msg)
        if ev is not None and not self.read_only:
            dl = Gtk.Button(label=_("Delete event"), margin_top=8)
            dl.add_css_class("destructive-action")
            dl.connect("clicked", lambda *_: self._delete())
            g4.add(dl)
        page.add(g4)
        g.set_sensitive(not self.read_only)
        tv.set_content(page)
        self.set_child(tv)

    @staticmethod
    def _initial(ev, day):
        if ev:
            if ev.get("allday"):
                s = parse_day(ev.get("start")) or _dt.date.today()
                e = (parse_day(ev.get("end")) or s) - _dt.timedelta(days=1)
                return _dt.datetime(s.year, s.month, s.day), _dt.datetime(max(e, s).year, max(e, s).month, max(e, s).day)
            try:
                s = _dt.datetime.fromisoformat(str(ev["start"]).replace("Z", "+00:00")).astimezone().replace(tzinfo=None)
                e = _dt.datetime.fromisoformat(str(ev["end"]).replace("Z", "+00:00")).astimezone().replace(tzinfo=None)
                return s, e
            except (ValueError, KeyError):
                pass
        base = day or _dt.date.today()
        h = min(_dt.datetime.now().hour + 1, 22)
        s = _dt.datetime(base.year, base.month, base.day, h, 0)
        return s, s + _dt.timedelta(hours=1)

    def _start_changed(self, v):
        if self.end.value <= v:
            self.end.set_value(v + _dt.timedelta(hours=1))

    def _targets(self, ts):
        self.targets = ts
        self.cal.set_model(Gtk.StringList.new([t["name"] for t in ts] or ["–"]))
        if not ts:
            self.msg.set_label(_("No writable calendar connected (Settings → Calendar)."))
            if hasattr(self, "save_btn"):
                self.save_btn.set_sensitive(False)

    def _save(self):
        ev = self.ev
        b = {"summary": self.title.get_text() or _("Event"), "allday": self.allday.get_active(), "location": self.loc.get_text()}
        buf = self.desc.get_buffer()
        b["description"] = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), True)
        s, e = self.start.value, max(self.end.value, self.start.value)
        if b["allday"]:
            b["start"] = s.date().isoformat()
            b["end"] = (e.date() + _dt.timedelta(days=1)).isoformat()
        else:
            if e <= s:
                e = s + _dt.timedelta(minutes=1)
            b["start"] = s.astimezone().isoformat()
            b["end"] = e.astimezone().isoformat()
        if ev:
            b.update(account=ev.get("account") or 0, cal=ev.get("cal") or "", href=ev.get("href") or "", etag=ev.get("etag") or "", uid=ev.get("uid") or "")
        elif self.targets:
            t = self.targets[min(self.cal.get_selected(), len(self.targets) - 1)]
            b.update(account=t["account"], cal=t["cal"])
        else:
            return
        store.run("PATCH" if ev else "POST", "api/calendar/events", b, done=lambda _: self._done(),
                  error=lambda e: self.msg.set_label(str(e)))

    def _delete(self):
        ev = self.ev
        store.run("POST", "api/calendar/events/delete", {"account": ev.get("account") or 0, "cal": ev.get("cal") or "", "href": ev.get("href") or "",
                                                         "etag": ev.get("etag") or ""}, done=lambda _: self._done(), error=lambda e: self.msg.set_label(str(e)))

    def _done(self):
        store.load_today()
        store.emit("events-changed")
        if self.after:
            self.after()
        self.close()


def _screen(title, child):
    sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.AUTOMATIC, vexpand=True)
    cl = Adw.Clamp(maximum_size=760)
    bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_top=18, margin_bottom=24, margin_start=18, margin_end=18)
    h = Gtk.Label(label=title, xalign=0)
    h.add_css_class("title-1")
    bx.append(h)
    bx.append(child)
    cl.set_child(bx)
    sw.set_child(cl)
    return sw


def tasks_screen(win):
    tl = TaskList(win)
    w = _screen(_("Tasks"), tl)
    w.task_list = tl
    store.load_today()
    return w


def events_screen(win):
    return _screen(_("Events"), EventList(win, days=31))


class TodayBox(Gtk.Box):
    """Heute: Termine + faellige Aufgaben (Startseite und Seitenleiste)"""
    __gtype_name__ = "HKToday"

    def __init__(self, win, compact=False, limit_events=4, limit_tasks=5):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        self.win, self.compact, self.le, self.lt = win, compact, limit_events, limit_tasks
        watch(self, "today-changed", self.fill)
        self.fill()

    def fill(self):
        _clear(self)
        today = iso_day()
        tasks = [t for t in store.tasks if not t.get("due") or t["due"] <= today][: self.lt]
        evs = store.events[: self.le]
        if not tasks and not evs:
            lb = Gtk.Label(label=_("Nothing planned 🎉"), xalign=0, margin_start=6)
            lb.add_css_class("dim-label")
            self.append(lb)
        for e in evs:
            b = Gtk.Button(child=EventRow(e))
            b.add_css_class("flat")
            b.add_css_class("hk-plain")
            b.connect("clicked", lambda *_, e=e: EventEditor(self.win, e, store.load_today).present(self.win))
            self.append(b)
        for t in tasks:
            self.append(TaskRow(self.win, t, compact=self.compact))


def today_screen(win):
    bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
    bx.append(TodayBox(win, limit_events=50, limit_tasks=50))
    return _screen(_("Today"), bx)
