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


class SourceRow(Gtk.Box):
    """2.5 (tasksrc1): Eintrag der gemeinsamen Liste - Inbox-Aufgabe oder Datenbank-Zeile mit Quelle (Symbol/Titel),
    Datum/Uhrzeit/Zeitraum, Prioritaet, Zugewiesene (Namen aus people, nie Anmeldenamen). edit: false -> Haekchen grau."""
    __gtype_name__ = "HKSourceRow"

    def __init__(self, win, it, compact=False):
        super().__init__(spacing=10, margin_top=2, margin_bottom=2)
        self.win, self.it = win, it
        if it.get("kind") != "row":
            t = next((x for x in store.tasks + store.done_tasks if x.get("id") == it.get("id")), None) or dict(it)
            self.task = t
        done = bool(it.get("done"))
        ro = it.get("kind") == "row" and it.get("edit") is False
        ck = Gtk.Button(icon_name="object-select-symbolic" if done else "radio-symbolic", valign=Gtk.Align.CENTER)
        ck.add_css_class("flat")
        ck.add_css_class("circular")
        ck.add_css_class("hk-taskcheck")
        if it.get("prio") == 1 and not done:
            ck.add_css_class("error")
        ck.set_tooltip_text(_("View only") if ro else (_("Reopen") if done else _("Mark done")))
        ck.set_sensitive(not ro)
        ck.connect("clicked", lambda *_: self.toggle())
        self.check = ck
        self.append(ck)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0, hexpand=True)
        inner = Gtk.Box(spacing=6)
        text = it.get("text") or ""
        tx = Gtk.Label(label=text, xalign=0, hexpand=True, wrap=True, lines=2, ellipsize=Pango.EllipsizeMode.END)
        if done:
            tx.add_css_class("dim-label")
            tx.set_markup(f"<s>{GLib.markup_escape_text(text)}</s>")
        inner.append(tx)
        if it.get("repeat"):
            inner.append(Gtk.Image.new_from_icon_name("media-playlist-repeat-symbolic"))
        from .tasksrc import when_text
        wt = when_text(it)
        if wt and not done:
            dl = Gtk.Label(label=wt)
            dl.add_css_class("caption")
            dl.add_css_class("error" if it.get("overdue") else "dim-label")
            inner.append(dl)
        if it.get("prio") == 1:
            fl = Gtk.Image.new_from_icon_name("emblem-important-symbolic")
            fl.add_css_class("error")
            inner.append(fl)
        col.append(inner)
        meta = Gtk.Box(spacing=6)
        if it.get("kind") == "row":
            src = next((x for x in store.task_sources if x.get("db") == it.get("src")), {})
            ic = src.get("icon") if isinstance(src.get("icon"), str) and len(src.get("icon") or "") <= 4 else ""
            sl = Gtk.Label(label=((ic or "🗃️") + " " + (src.get("title") or _("Database"))).strip(), xalign=0,
                           ellipsize=Pango.EllipsizeMode.END)
            sl.add_css_class("caption")
            sl.add_css_class("dim-label")
            sl.add_css_class("hk-task-src")
            meta.append(sl)
        from . import dbvalues as dv
        from .avatars import person as person_avatar
        refs = [r for r in it.get("assignees") or [] if isinstance(r, str)]
        for ref in refs[:3]:
            info = dv.person_info(ref, store.task_people)
            av = person_avatar({"name": info["name"], "avatar": info["avatar"]}, size=18)
            av.set_tooltip_text(dv.person_label(ref, store.task_people))
            meta.append(av)
        if len(refs) == 1:
            nl = Gtk.Label(label=dv.person_label(refs[0], store.task_people), xalign=0)
            nl.add_css_class("caption")
            nl.add_css_class("dim-label")
            meta.append(nl)
        elif len(refs) > 3:
            meta.append(Gtk.Label(label=f"+{len(refs) - 3}", css_classes=["caption", "dim-label"]))
        if meta.get_first_child():
            col.append(meta)
        b = Gtk.Button(child=col, hexpand=True)
        b.add_css_class("flat")
        b.add_css_class("hk-plain")
        b.connect("clicked", lambda *_: self.open())
        self.append(b)
        if it.get("kind") != "row":
            def entries():
                t = self.task
                ents = [(_("Edit …"), lambda: TaskEditor(win, t).present(win))]
                ents.append((_("Reopen"), lambda: self.toggle()) if done else (_("Mark done"), lambda: self.toggle()))
                return ents + [None, (_("Delete"), lambda: store.delete_task(t["id"]), True)]
        else:
            def entries():
                ents = [(_("Open"), lambda: self.open())]
                if not ro:
                    ents.append((_("Reopen"), lambda: self.toggle()) if done else (_("Mark done"), lambda: self.toggle()))
                return ents
        mb = more_button(entries, _("Task actions"))
        mb.add_css_class("dim-label")
        if compact:
            mb.add_css_class("hk-reveal")
            self.add_css_class("hk-reveal-zone")
        self.append(mb)
        context_menu(b, lambda w: menu_popover(w, entries()).popup())

    def open(self):
        if self.it.get("kind") == "row":
            self.win.show_page(self.it["id"])
        else:
            TaskEditor(self.win, self.task).present(self.win)

    def toggle(self):
        it = self.it
        done = not bool(it.get("done"))
        win = self.win
        text = it.get("text") or ""

        def undo_offer(prev):
            t = Adw.Toast(title=GLib.markup_escape_text(_("“{title}” done").format(title=text)), button_label=_("Undo"), timeout=6)
            t.connect("button-clicked", lambda *_: store.set_item_done(it, False, prev))
            win.toasts.add_toast(t)
        if it.get("kind") == "row":
            store.set_item_done(it, done, undo=undo_offer if done else None)
        else:
            store.set_item_done(it, done)
            if done and self.task.get("repeat"):
                pass


class TaskTarget(Gtk.MenuButton):
    """Ziel fuer neue Aufgaben: Inbox oder eine Aufgabenquelle (2.5, tasksrc1)"""
    __gtype_name__ = "HKTaskTarget"

    def __init__(self):
        super().__init__(valign=Gtk.Align.CENTER, tooltip_text=_("Add to"))
        self.add_css_class("flat")
        self.add_css_class("hk-small")
        self.src = None
        self.set_create_popup_func(lambda mb: mb.set_popover(menu_popover(None, self._entries())))
        self.update()

    def _entries(self):
        from .tasksrc import targets
        ents = [(("✓ " if self.src is None else "") + _("Inbox"), lambda: self.choose(None))]
        for s in targets(store.task_sources):
            ic = s.get("icon") if isinstance(s.get("icon"), str) and len(s.get("icon") or "") <= 4 else "🗃️"
            ents.append((("✓ " if self.src == s["db"] else "") + f"{ic} {s.get('title') or _('Database')}",
                         (lambda db=s["db"]: self.choose(db))))
        return ents

    def choose(self, db):
        self.src = db
        self.update()

    def update(self):
        from .tasksrc import targets
        ts = targets(store.task_sources)
        if self.src is not None and not any(s["db"] == self.src for s in ts):
            self.src = None
        s = next((x for x in ts if x["db"] == self.src), None)
        self.set_label(_("Inbox") if s is None else (s.get("title") or _("Database")))
        self.set_visible(store.task_items is not None and bool(ts))


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
        self.target = TaskTarget()                       # 2.5: Ziel (Inbox oder Quelle)
        add.append(self.target)
        if not compact:
            sb = flat_button(icon="emblem-system-symbolic", tooltip=_("Task sources"), cb=lambda: SourcesDialog(win).present(win))
            sb.add_css_class("dim-label")
            self.src_btn = sb
            add.append(sb)
        self.append(add)
        self.list = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        self.append(self.list)
        watch(self, "today-changed", self.fill)
        self.fill()

    def _add(self, e):
        t = e.get_text().strip()
        e.set_text("")
        if t:
            if store.task_items is not None and self.target.src is not None:
                store.add_task_to(t, self.target.src)
            else:
                store.add_task(t)

    def focus_entry(self, src=None):
        if src is not None:
            self.target.choose(src)
        self.entry.grab_focus()

    def fill(self):
        _clear(self.list)
        if hasattr(self, "target"):
            self.target.update()
        if hasattr(self, "src_btn"):
            self.src_btn.set_visible(store.task_items is not None)
        if store.task_items is not None:                # 2.5: Server-Reihenfolge (Inbox + Quellen), keine eigene Mischlogik
            return self._fill_items()
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

    def _fill_items(self):
        its = [x for x in store.task_items if not x.get("done") or store.tqueue.d.get(("r:" if x.get("kind") == "row" else "t:") + str(x["id"]))]
        its = [x for x in its if not (x.get("kind") == "task" and x.get("id") in store._task_deletes)]
        if not its:
            lb = Gtk.Label(label=_("All done 🎉"), xalign=0, margin_top=4)
            lb.add_css_class("dim-label")
            self.list.append(lb)
        overdue = [x for x in its if x.get("overdue")]
        rest = [x for x in its if not x.get("overdue")]
        for name, group in ((_("Overdue"), overdue), ("", rest)):
            if not group:
                continue
            if name and (not self.compact or rest):
                h = Gtk.Label(label=name, xalign=0, margin_top=6)
                h.add_css_class("caption-heading")
                h.add_css_class("error")
                self.list.append(h)
            elif not name and overdue:
                h = Gtk.Label(label=_("Open"), xalign=0, margin_top=6)
                h.add_css_class("caption-heading")
                h.add_css_class("dim-label")
                self.list.append(h)
            for it in group[: 5 if self.compact else None]:
                self.list.append(SourceRow(self.win, it, compact=self.compact))
        if store.done_tasks:
            b = flat_button(icon="pan-down-symbolic" if self.show_done else "pan-end-symbolic",
                            label=ngettext("{n} done", "{n} done", len(store.done_tasks)).format(n=len(store.done_tasks)),
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
            self.msg.set_label(_("No writable calendar connected (Settings → Account → Calendar)."))
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
        if store.task_items is not None:                # 2.5: mit Quellen (Server-Reihenfolge), heute faellig/ueberfaellig/ohne Datum
            items = [x for x in store.task_items if not x.get("done") and (not x.get("due") or x["due"] <= today or x.get("overdue"))
                     and not (x.get("kind") == "task" and x.get("id") in store._task_deletes)][: self.lt]
            tasks = []
        else:
            items = []
            tasks = [t for t in store.tasks if not t.get("due") or t["due"] <= today][: self.lt]
        evs = store.events[: self.le]
        if not tasks and not evs and not items:
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
        for it in items:
            self.append(SourceRow(self.win, it, compact=self.compact))


def today_screen(win):
    bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
    bx.append(TodayBox(win, limit_events=50, limit_tasks=50))
    return _screen(_("Today"), bx)


class SourcesDialog(Adw.Dialog):
    """Aufgabenquellen verwalten (2.5, tasksrc1): abonnierte Datenbanken, je Quelle "alle offenen" / "nur mir zugewiesen",
    Abo entfernen. Zuordnen (welche Eigenschaft "erledigt" ist) nur im Web."""
    __gtype_name__ = "HKSourcesDialog"

    def __init__(self, win):
        super().__init__(title=_("Task sources"), content_width=460, content_height=480)
        self.win = win
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        self.page = Adw.PreferencesPage()
        tv.set_content(self.page)
        self.set_child(tv)
        self.group = None
        self.reload()

    def reload(self):
        from .api import client
        from .util import bg
        bg(client.get, "api/tasks/sources", done=self.fill, error=lambda e: self.fill(None, str(e)))

    def fill(self, r, error=None):
        if self.group is not None:
            self.page.remove(self.group)
        g = Adw.PreferencesGroup(description=_("Open entries of these databases appear in your task list. To add a database, "
                                               "open it and choose “Use as task source”."))
        self.group = g
        self.page.add(g)
        if error:
            g.add(Adw.ActionRow(title=GLib.markup_escape_text(error)))
            return
        srcs = [s for s in (r or {}).get("sources") or [] if isinstance(s, dict) and isinstance(s.get("db"), int)]
        if not srcs:
            g.add(Adw.ActionRow(title=_("No task sources yet")))
        for s in srcs:
            ic = s.get("icon") if isinstance(s.get("icon"), str) and len(s.get("icon") or "") <= 4 else "🗃️"
            sub = []
            if s.get("inactive"):
                sub.append(_("Database deleted or archived"))
            elif s.get("unmapped") or not s.get("map"):
                sub.append(_("Not set up as a task source – set it up on the web"))
            elif isinstance(s.get("count"), int):
                sub.append(ngettext("{n} open", "{n} open", s["count"]).format(n=s["count"]))
            row = Adw.ActionRow(title=GLib.markup_escape_text(f"{ic} {s.get('title') or _('Database')}"),
                                subtitle=GLib.markup_escape_text(" · ".join(sub)))
            row.hk_db = s["db"]
            if s.get("map") and not s.get("inactive") and (s.get("map") or {}).get("assignee"):   # ohne Personen: nur "alle"
                dd = Gtk.DropDown.new_from_strings([_("All open"), _("Assigned to me")])
                dd.set_valign(Gtk.Align.CENTER)
                dd.set_selected(1 if s.get("scope") == "assigned" else 0)
                dd.connect("notify::selected", lambda d, _p, db=s["db"]: self.set_scope(db, "assigned" if d.get_selected() == 1 else "all"))
                row.hk_scope = dd
                row.add_suffix(dd)
            rm = Gtk.Button(icon_name="user-trash-symbolic", valign=Gtk.Align.CENTER, tooltip_text=_("Remove task source"))
            rm.add_css_class("flat")
            rm.connect("clicked", lambda *_, db=s["db"]: self.remove(db))
            row.hk_remove = rm
            row.add_suffix(rm)
            g.add(row)
        if isinstance((r or {}).get("max"), int):
            g.set_header_suffix(Gtk.Label(label=f"{len(srcs)}/{r['max']}", css_classes=["dim-label", "caption"]))

    def set_scope(self, db, scope):
        store.run("PUT", f"api/notes/{db}/tasksource", {"scope": scope}, done=lambda r: store.load_today())

    def remove(self, db):
        store.run("DELETE", f"api/notes/{db}/tasksource", None, done=lambda r: (self.reload(), store.load_today()),
                  error=lambda e: (self.reload(), store.load_today()))


def use_as_source(win, db):
    """DB-Ansicht "Als Aufgabenquelle verwenden": abonnieren nur, wenn die Zuordnung (map) gesetzt ist - sonst Hinweis
    "im Web einrichten" (das Zuordnen der Eigenschaften gibt es nur im Web)"""
    from .api import client
    from .util import bg

    def fin(r):
        if not isinstance(r, dict) or not r.get("map"):
            dlg = Adw.AlertDialog(heading=_("Set up on the web"),
                                  body=_("This database is not set up as a task source yet. Choose which property means "
                                         "“done” on the web (database menu → “Use as task source”)."))
            dlg.add_response("ok", _("OK"))
            dlg.add_response("web", _("Open in browser"))
            dlg.connect("response", lambda d, rr: rr == "web" and win.open_uri(store.page_url(db)))
            dlg.present(win)
            return
        if r.get("scope"):
            win.toast(_("Already a task source"))
            return
        store.run("PUT", f"api/notes/{db}/tasksource", {"subscribe": True},
                  done=lambda x: (win.toast(_("Added to your tasks")), store.load_today()))
    bg(client.get, f"api/notes/{db}/tasksource", done=fin, error=lambda e: win.toast(str(e)))


class DigestGroup(Adw.PreferencesGroup):
    """Einstellungen: taegliche Zusammenfassung "heute faellig / ueberfaellig" (2.5, taskdue1) - Schalter + Uhrzeit
    (GET/PUT /api/tasks/digest). Unsichtbar ohne taskdue1."""
    __gtype_name__ = "HKDigestGroup"

    def __init__(self, dlg):
        super().__init__(title=_("Tasks"))
        self.dlg = dlg
        self.set_visible(False)
        self.sw = Adw.SwitchRow(title=_("Daily summary"), subtitle=_("Once a day if something is due today or overdue"))
        self.tm = Adw.EntryRow(title=_("Time"), show_apply_button=True)
        self.add(self.sw)
        self.add(self.tm)
        self._busy = False
        if store.has("taskdue1"):
            from .api import client
            from .util import bg
            bg(client.get, "api/tasks/digest", done=self.fill, error=lambda e: None)

    def fill(self, d):
        if not isinstance(d, dict):
            return
        self._busy = True
        self.sw.set_active(bool(d.get("on")))
        self.tm.set_text(d.get("time") or "07:30")
        self.tm.set_sensitive(bool(d.get("on")))
        self._busy = False
        if not getattr(self, "_wired", False):
            self._wired = True
            self.sw.connect("notify::active", lambda r, _p: self._put({"on": r.get_active()}))
            self.tm.connect("apply", lambda r: self._time(r))
        self.set_visible(True)

    def _time(self, r):
        import re
        t = r.get_text().strip()
        m = re.fullmatch(r"(\d{1,2})[:.](\d{2})", t)
        if not m or int(m.group(1)) > 23 or int(m.group(2)) > 59:
            self.dlg.add_toast(Adw.Toast(title=GLib.markup_escape_text(_("Please enter a time like 07:30."))))
            return
        self._put({"time": f"{int(m.group(1)):02d}:{m.group(2)}"})

    def _put(self, b):
        if self._busy:
            return
        self.tm.set_sensitive(self.sw.get_active())
        from .api import client
        from .util import bg
        bg(client.json, "PUT", "api/tasks/digest", b, done=self.fill,
           error=lambda e: self.dlg.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e)))))
