"""(2.6, Bugs-DB 2029-8) Eigener Status - Oberflaeche: Abzeichen, Dialog "Mein Status", Kontomenue, Einstellungen
"Erreichbarkeit" (Unterseite) und die Rueckfrage vor dem Anruf. Stand und Server-Zugriff: userstatus.py, Regeln:
chatstatus.py. Web-Vorbild static/userstatus.js, static/push.js (reachCard, excCard), static/chat.js (Hinweis vor dem Anruf)."""
from gi.repository import Adw, GLib, Gtk, Pango

from . import chatstatus as S
from .api import client
from .i18n import _, pgettext
from .userstatus import ustatus
from .util import bg

_DURS = ("h1", "t7", "off")


def status_badge(state, size=12, **kw):
    """Punkt wie im Web: Beschaeftigt gelb, Nicht stoeren rot mit weissem Strich, Im Anruf blauer Ring, Verfuegbar gruen"""
    kw.setdefault("hexpand", False)
    kw.setdefault("vexpand", False)
    b = Gtk.Box(width_request=size, height_request=size, **kw)
    b.add_css_class("hk-us-dot")
    b.add_css_class("hk-us-" + (state if state in S.PEER_STATES else "available"))
    if state == "dnd":
        bar = Gtk.Box(halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER, width_request=max(4, size - 6), height_request=2,
                      margin_start=3, margin_end=3)
        bar.add_css_class("hk-us-bar")
        b.append(bar)
    elif state == "in_call":
        inner = Gtk.Box(halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER, width_request=max(4, size - 8),
                        height_request=max(4, size - 8), margin_start=4, margin_end=4)
        inner.add_css_class("hk-us-inner")
        b.append(inner)
    b.set_tooltip_text(S.state_name(state) or None)
    return b


def status_label(prefix=True):
    """"Status: Nicht stören · bis 14:30" fuer Tooltip/Screenreader des Kontoknopfs, "" ohne Status"""
    s = ustatus.shown()
    if not s:
        return ""
    txt = S.state_name(s["state"]) + (", " + s["hint"] if s["hint"] else "")
    return _("Status: {state}").format(state=txt) if prefix else txt


# --- Kontomenue: Status-Zeile + Konto ------------------------------------------------------------------------------
class AccountPopover(Gtk.Popover):
    """Klick auf das eigene Profilbild (mit userstatus1): Status (Punkt, Zustand, Zusatz) -> Dialog, darunter Konto"""

    def __init__(self, win):
        super().__init__(has_arrow=True)
        self.win = win
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_top=4, margin_bottom=4, margin_start=4, margin_end=4)
        self.st_btn = Gtk.Button()
        self.st_btn.add_css_class("flat")
        row = Gtk.Box(spacing=10)
        self.dot_slot = Gtk.Box(valign=Gtk.Align.CENTER)
        row.append(self.dot_slot)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.st_name = Gtk.Label(xalign=0)
        self.st_hint = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END, max_width_chars=34)
        self.st_hint.add_css_class("caption")
        self.st_hint.add_css_class("dim-label")
        col.append(self.st_name)
        col.append(self.st_hint)
        row.append(col)
        self.st_btn.set_child(row)
        self.st_btn.connect("clicked", lambda *_: (self.popdown(), StatusDialog(self.win).present(self.win)))
        box.append(self.st_btn)
        box.append(Gtk.Separator(margin_top=2, margin_bottom=2))
        acc = Gtk.Button(label=_("Account"))
        acc.add_css_class("flat")
        acc.get_child().set_xalign(0)
        acc.connect("clicked", lambda *_: (self.popdown(), self.win.settings_dialog(page="account")))
        box.append(acc)
        self.set_child(box)
        self.connect("show", lambda *_: self.fill())
        self.fill()

    def fill(self):
        s = ustatus.shown() or {"state": "available", "hint": ""}
        while (ch := self.dot_slot.get_first_child()) is not None:
            self.dot_slot.remove(ch)
        self.dot_slot.append(status_badge(s["state"], 12))
        self.st_name.set_label(S.state_name(s["state"]))
        self.st_hint.set_label(s["hint"] or _("change"))
        lab = _("Status: {state}").format(state=S.state_name(s["state"]) + (", " + s["hint"] if s["hint"] else "")) + " – " + _("change")
        self.st_btn.update_property([Gtk.AccessibleProperty.LABEL], [lab])
        self.st_btn.set_tooltip_text(lab)


# --- Dialog "Mein Status" --------------------------------------------------------------------------------------------
class StatusDialog(Adw.Dialog):
    """Zustand (Verfuegbar / Beschaeftigt / Nicht stoeren), Dauer (1 Stunde, bis morgen 7:00, bis ich es ausschalte;
    ein gesetztes Ende "wie bisher"), eigener Text <= 80. 409 (anderswo geaendert): Ansicht still uebernehmen, neu zeigen."""

    def __init__(self, win):
        super().__init__(title=_("My status"), content_width=440, follows_content_size=True)
        self.win = win
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar(show_end_title_buttons=False, show_start_title_buttons=False)
        cancel = Gtk.Button(label=_("Cancel"))
        cancel.connect("clicked", lambda *_: self.close())
        hb.pack_start(cancel)
        self.save_btn = Gtk.Button(label=_("Save"))
        self.save_btn.add_css_class("suggested-action")
        self.save_btn.connect("clicked", lambda *_: self.save())
        hb.pack_end(self.save_btn)
        tv.add_top_bar(hb)
        self.page = Adw.PreferencesPage()
        tv.set_content(self.page)
        self.set_child(tv)
        self.set_default_widget(self.save_btn)
        self._h = ustatus.connect("changed", lambda *_: self.sync())          # Ablauf, Ruhezeit, anderes Geraet
        self.connect("closed", lambda *_: ustatus.disconnect(self._h))
        self.build()

    def build(self):
        while (g := getattr(self, "_groups", None)):
            self.page.remove(g.pop())
        self._groups = []
        v = ustatus.view or {}
        self.info = Gtk.Label(xalign=0, wrap=True, margin_bottom=4)
        self.info.add_css_class("dim-label")
        g0 = Adw.PreferencesGroup()
        g0.add(self.info)
        g1 = Adw.PreferencesGroup(title=_("Status"))
        self.st_checks = {}
        first = None
        for s in S.STATES:
            ck = Gtk.CheckButton(active=v.get("state") == s, valign=Gtk.Align.CENTER)
            if first is None:
                first = ck
            else:
                ck.set_group(first)
            r = Adw.ActionRow(title=S.state_name(s), activatable_widget=ck)
            r.add_prefix(ck)
            r.add_suffix(status_badge(s, 12, valign=Gtk.Align.CENTER, halign=Gtk.Align.CENTER))
            ck.connect("toggled", lambda *_: self.sync())
            g1.add(r)
            self.st_checks[s] = ck
        g2 = Adw.PreferencesGroup(title=_("Duration"))
        self.dur_checks = {}
        self.keep = S.keep_until(v, ustatus.now())
        t7 = S.tomorrow7()[1]
        durs = ([("keep", _("Until {when} (as before)").format(when=S.when_text(v["until"], ustatus.now())))] if self.keep else []) + \
            [("h1", _("1 hour")), ("t7", t7), ("off", _("Until I turn it off"))]
        first = None
        for i, (k, lab) in enumerate(durs):
            ck = Gtk.CheckButton(active=(i == 0) if self.keep else k == "off", valign=Gtk.Align.CENTER)
            if first is None:
                first = ck
            else:
                ck.set_group(first)
            r = Adw.ActionRow(title=lab, activatable_widget=ck)
            r.add_prefix(ck)
            g2.add(r)
            self.dur_checks[k] = (ck, r)
        self.g_dur = g2
        g3 = Adw.PreferencesGroup()
        mx = int(((v.get("limits") or {}).get("text")) or S.TEXT_MAX)
        self.mx = mx
        self.text = Adw.EntryRow(title=_("Custom text (optional)"), max_length=mx)
        if v.get("source") == "manual" and isinstance(v.get("text"), str):
            self.text.set_text(v["text"])
        self.text.connect("changed", lambda *_: self.count())
        self.text.connect("entry-activated", lambda *_: self.save())
        g3.add(self.text)
        self.cnt = Gtk.Label(xalign=1, margin_top=4)
        self.cnt.add_css_class("caption")
        self.cnt.add_css_class("dim-label")
        g3.add(self.cnt)
        self.err = Gtk.Label(xalign=0, wrap=True, visible=False, margin_top=6)
        self.err.add_css_class("error")
        g3.add(self.err)
        more = Gtk.Button(label=_("Availability …"), halign=Gtk.Align.START, margin_top=10)
        more.add_css_class("flat")
        more.connect("clicked", lambda *_: (self.close(), self.win.settings_dialog(page="reach")))
        g3.add(more)
        for g in (g0, g1, g2, g3):
            self.page.add(g)
            self._groups.append(g)
        self.count()
        self.sync()
        ck = self.st_checks.get(v.get("state")) or self.st_checks["available"]
        GLib.idle_add(lambda: (ck.grab_focus(), False)[1])

    def _cur(self, d):
        return next((k for k, x in d.items() if (x[0] if isinstance(x, tuple) else x).get_active()), None)

    def count(self):
        n = len(self.text.get_text())
        self.cnt.set_label(_("{n} of {max} characters").format(n=n, max=self.mx))
        (self.cnt.add_css_class if n >= self.mx else self.cnt.remove_css_class)("warning")

    def sync(self):
        """Verfuegbar: keine Dauer; oben ehrlich, was gerade wirkt (Ruhezeit, Im Anruf, Ablauf)"""
        avail = self._cur(self.st_checks) == "available"
        self.g_dur.set_sensitive(not avail)
        s = ustatus.shown()
        if s:
            self.info.set_label(_("Now: {state}").format(state=S.state_name(s["state"])) + (" · " + s["hint"] if s["hint"] else ""))

    def save(self):
        st, dur = self._cur(self.st_checks), self._cur(self.dur_checks)
        body = S.status_body(st, dur, self.text.get_text(), ustatus.view, ustatus.now())
        self.save_btn.set_sensitive(False)
        self.err.set_visible(False)

        def fin(_v, conflict):
            self.save_btn.set_sensitive(True)
            if conflict:                                  # anderswo geaendert: still den neuen Stand zeigen
                self.build()
                return
            self.close()

        def err(e):
            self.save_btn.set_sensitive(True)
            self.err.set_label(str(e))
            self.err.set_visible(True)
        ustatus.set(body, fin, err)


# --- Einstellungen "Erreichbarkeit" (Unterseite) ---------------------------------------------------------------------
class ReachPage(Adw.PreferencesPage):
    """prefs (quiet_dnd, quiet_digest, mention_break, in_call, show_call, busy, repeat + repeat_s) und Ausnahmen (Personen,
    eigene Gruppen; hoechstens 100). Jede Aenderung speichert sofort (PUT .../prefs), Fehler setzt den Schalter zurueck."""

    def __init__(self, dlg):
        super().__init__(title=_("Availability"), visible=False)
        self.dlg = dlg
        self._groups = []
        self._last = None
        self._busy = False
        self._h = ustatus.connect("changed", lambda *_: self.fill())
        self.connect("destroy", lambda *_: ustatus.disconnect(self._h))
        if ustatus.ok:
            self.fill()
        elif ustatus.available():
            ustatus.load()

    def _key(self, v):
        import json
        return json.dumps([v.get("prefs"), v.get("exceptions")], sort_keys=True)

    def fill(self):
        v = ustatus.view if ustatus.ok else None
        self.set_visible(v is not None)
        if v is None or self._busy:
            return
        k = self._key(v)
        if k == self._last:
            return
        self._last = k
        for g in self._groups:
            self.remove(g)
        self._groups = []
        P, L = v.get("prefs") or {}, v.get("limits") or {}
        rs = L.get("repeat_s") if isinstance(L.get("repeat_s"), list) and len(L["repeat_s"]) == 2 else list(S.REPEAT_S)
        g0 = Adw.PreferencesGroup(description=_("How others can reach you while you’re set to “Busy” or “Do not disturb”, during "
                                                "quiet hours and while you’re on a call. You set your status in the user menu."))
        self.status_lb = Gtk.Label(xalign=0, wrap=True)
        self.status_lb.add_css_class("dim-label")
        g0.add(self.status_lb)
        g1 = Adw.PreferencesGroup()
        g1.add(self._sw("quiet_dnd", P, _("Quiet hours count as “Do not disturb”"),
                        _("During the quiet hours of your notifications the same applies as with “Do not disturb”: exceptions and "
                          "repeated calls get through.")))
        g1.add(self._sw("quiet_digest", P, _("Summary after quiet hours too"),
                        _("After “Do not disturb” you get one notice with the number of missed messages and calls – with this "
                          "switch also at the end of quiet hours.")))
        g1.add(self._sw("mention_break", P, _("Mentions get through"),
                        _("Anyone who mentions you in a chat or on a page reaches you even with “Do not disturb”.")))
        g2 = Adw.PreferencesGroup()
        g2.add(self._combo("in_call", P, _("Calls while you’re on a call"), _("Call waiting: a notice in the ongoing call, without ringing."),
                           [("knock", _("Let them knock")), ("busy", _("Report busy"))]))
        g2.add(self._sw("show_call", P, _("Others see “On a call”"), _("Only chat partners, and only if you both show your online status.")))
        g2.add(self._combo("busy", P, _("When “Busy”"), _("“Busy” shows others that you’re short on time right now."),
                           [("show", _("Notifications as usual")), ("silent", _("Notifications without sound"))]))
        n = P.get("repeat_s") if isinstance(P.get("repeat_s"), int) else 180
        rep = self._sw("repeat", P, _("Repeated calls get through"),
                       _("If the same person calls again within {n} seconds, it rings despite “Do not disturb”.").format(n=n))
        g2.add(rep)
        self.rep_row = rep
        sec = Adw.SpinRow.new_with_range(rs[0], rs[1], 10)
        sec.set_title(_("Period in seconds ({min}–{max})").format(min=rs[0], max=rs[1]))
        sec.set_value(n)
        sec.set_sensitive(bool(P.get("repeat")))
        self.repeat_row = sec

        def sec_changed(r, _p):
            val = int(round(r.get_value()))
            if val == ((ustatus.view or {}).get("prefs") or {}).get("repeat_s"):
                return
            self._save({"repeat_s": val}, lambda: r.set_value(((ustatus.view or {}).get("prefs") or {}).get("repeat_s") or 180))
        sec.connect("notify::value", sec_changed)
        g2.add(sec)
        self.g_exc = Adw.PreferencesGroup(title=_("Exceptions"),
                                          description=_("These people and groups reach you even with “Do not disturb” and during "
                                                        "quiet hours – with calls and notifications."))
        self._exc_fill(v)
        for g in (g0, g1, g2, self.g_exc):
            self.add(g)
            self._groups.append(g)
        self.status_lb.set_label(status_label() or "")
        self.status_lb.set_visible(bool(self.status_lb.get_label()))

    def _save(self, body, undo=None):
        self._busy = True

        def fin(v, conflict):
            self._busy = False
            if conflict or "exceptions" in body or not S.view_ok(v):
                self._last = None                       # Liste neu (bzw. anderswo geaendert: alles neu)
                self.fill()
                return
            self._last = self._key(v)                   # Schalter stehen schon richtig: nicht neu bauen (Fokus bleibt)
            if "repeat_s" in body and getattr(self, "rep_row", None) is not None:
                n = (v.get("prefs") or {}).get("repeat_s")
                self.rep_row.set_subtitle(_("If the same person calls again within {n} seconds, it rings despite “Do not "
                                            "disturb”.").format(n=n))

        def err(e):
            self._busy = False
            if undo:
                undo()
            self.dlg.add_toast(Adw.Toast(title=str(e))) if hasattr(self.dlg, "add_toast") else None
        ustatus.prefs(body, fin, err)

    def _sw(self, k, P, title, sub):
        r = Adw.SwitchRow(title=title, subtitle=sub, active=bool(P.get(k)))
        r.hk_key = k

        def changed(row, _p):
            if row.get_active() == bool(((ustatus.view or {}).get("prefs") or {}).get(k)):
                return
            if k == "repeat" and getattr(self, "repeat_row", None) is not None:
                self.repeat_row.set_sensitive(row.get_active())
            self._save({k: row.get_active()}, lambda: row.set_active(bool(((ustatus.view or {}).get("prefs") or {}).get(k))))
        r.connect("notify::active", changed)
        return r

    def _combo(self, k, P, title, sub, opts):
        r = Adw.ComboRow(title=title, subtitle=sub, model=Gtk.StringList.new([x[1] for x in opts]))
        r.hk_key = k
        keys = [x[0] for x in opts]
        r.set_selected(keys.index(P.get(k)) if P.get(k) in keys else 0)

        def changed(row, _p):
            val = keys[row.get_selected()] if row.get_selected() < len(keys) else keys[0]
            cur = ((ustatus.view or {}).get("prefs") or {}).get(k)
            if val == cur:
                return
            self._save({k: val}, lambda: row.set_selected(keys.index(cur) if cur in keys else 0))
        r.connect("notify::selected", changed)
        return r

    # --- Ausnahmen -------------------------------------------------------------------------------------------------
    def _exc_fill(self, v):
        g = self.g_exc
        lst = [x for x in (v.get("exceptions") or []) if isinstance(x, dict)]
        mx = int(((v.get("limits") or {}).get("exceptions")) or S.EXC_MAX)
        if not lst:
            r = Adw.ActionRow(title=_("No exceptions yet."))
            r.add_css_class("dim-label")
            g.add(r)
        for x in lst:
            grp = x.get("type") == "group"
            nm = x.get("name") or x.get("ref") or "?"
            r = Adw.ActionRow(title=GLib.markup_escape_text(nm), subtitle=_("Group") if grp else "")
            r.add_prefix(Gtk.Image.new_from_icon_name("system-users-symbolic" if grp else "avatar-default-symbolic"))
            rm = Gtk.Button(icon_name="user-trash-symbolic", valign=Gtk.Align.CENTER, tooltip_text=_("Remove “{name}”").format(name=nm))
            rm.add_css_class("flat")
            rm.update_property([Gtk.AccessibleProperty.LABEL], [_("Remove “{name}”").format(name=nm)])
            rm.connect("clicked", lambda _b, x=x: self._exc_put([y for y in self._exc_cur() if not S.exc_same(y, x)]))
            r.add_suffix(rm)
            g.add(r)
        add = Adw.ActionRow(title=_("Add exception: search for a person or group"), activatable=len(lst) < mx,
                            subtitle=_("{n} of {max}").format(n=len(lst), max=mx))
        add.add_prefix(Gtk.Image.new_from_icon_name("list-add-symbolic"))
        add.set_sensitive(len(lst) < mx)
        add.connect("activated", lambda *_: ExceptionPicker(self).present(self.get_root()))
        g.add(add)

    @staticmethod
    def _exc_cur():
        return [x for x in ((ustatus.view or {}).get("exceptions") or []) if isinstance(x, dict)]

    def _exc_put(self, nxt):
        self._save({"exceptions": [S.exc_out(x) for x in nxt][:S.EXC_MAX]})

    def exc_add(self, x):
        cur = self._exc_cur()                               # immer der aktuelle Stand (anderes Geraet hat vielleicht geaendert)
        if any(S.exc_same(y, x) for y in cur):
            return
        self._exc_put(cur + [x])


class ExceptionPicker(Adw.Dialog):
    """Person oder eigene Gruppe suchen (GET /api/share/targets wie beim Teilen) -> Ausnahme hinzufuegen"""

    def __init__(self, page):
        super().__init__(title=_("Exceptions"), content_width=400, content_height=440)
        self.page = page
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_start=12, margin_end=12, margin_top=6, margin_bottom=12)
        self.q = Gtk.SearchEntry(placeholder_text=_("Search for a person or group …"))
        self.q.update_property([Gtk.AccessibleProperty.LABEL], [_("Add exception: search for a person or group")])
        self.q.connect("search-changed", lambda *_: self.search())
        box.append(self.q)
        sw = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.lb.add_css_class("boxed-list")
        self.lb.connect("row-activated", lambda _l, r: self.pick(r))
        sw.set_child(self.lb)
        box.append(sw)
        tv.set_content(box)
        self.set_child(tv)
        self._seq = 0
        GLib.idle_add(lambda: (self.q.grab_focus(), False)[1])

    def search(self):
        import re
        import urllib.parse
        s = self.q.get_text().strip()
        self._seq += 1
        my = self._seq
        self.lb.remove_all()
        if not s:
            return

        def fin(d):
            if my != self._seq:
                return
            d = d if isinstance(d, dict) else {}
            for u in d.get("users") or []:
                if isinstance(u, dict) and u.get("username"):
                    self._opt(u.get("name") or u["username"], u.get("sub") or "", {"type": "user", "ref": u["username"], "name": u.get("name")})
            for gr in d.get("groups") or []:
                if isinstance(gr, dict) and gr.get("name"):
                    self._opt(gr["name"], _("Group"), {"type": "group", "name": gr["name"]})
            for u in d.get("remote") or []:
                r = isinstance(u, dict) and (u.get("target") or u.get("handle"))
                if r and u.get("type", "user") == "user" and re.fullmatch(r"~[a-z2-7]{16}(@[a-z0-9.:\-]{1,260})?", r):
                    self._opt(u.get("label") or u.get("name") or r, u.get("host") or "", {"type": "user", "ref": r, "name": u.get("label") or u.get("name")})
            if self.lb.get_first_child() is None:
                if re.fullmatch(r"~[a-z2-7]{16}(@[a-z0-9.:\-]{1,260})?", s):
                    self._opt(s, "", {"type": "user", "ref": s})
                else:
                    r = Gtk.ListBoxRow(activatable=False, child=Gtk.Label(label=_("No matches."), margin_top=8, margin_bottom=8))
                    self.lb.append(r)

        GLib.timeout_add(250, lambda: (my == self._seq and bg(client.get, "api/share/targets?q=" + urllib.parse.quote(s), done=fin,
                                                                 error=lambda e: fin({})), False)[1])

    def _opt(self, title, sub, x):
        r = Adw.ActionRow(title=GLib.markup_escape_text(title), subtitle=GLib.markup_escape_text(sub or ""), activatable=True)
        r.hk_exc = x
        self.lb.append(r)

    def pick(self, row):
        x = getattr(row, "hk_exc", None)
        if x:
            self.page.exc_add(x)
            self.close()


# --- Vor dem Anruf ---------------------------------------------------------------------------------------------------
def before_call(win, c, name, go):
    """Gegenueber auf "Nicht stoeren": Rueckfrage "Trotzdem anrufen?"; "Im Anruf": Hinweis "klopft an" und weiter;
    sonst direkt go(). Vertrag 2029-4 / Web static/chat.js."""
    from .chat import chat
    st = chat.peer_status(c)
    if not st:
        go()
        return
    if st["s"] == "in_call":
        win.toast(_("{name} is on a call right now – your call will knock.").format(name=name))
        go()
        return
    if st["s"] != "dnd":
        go()
        return
    d = Adw.AlertDialog(heading=S.state_name("dnd"), body=_("{name} doesn’t want to be disturbed right now. Call anyway?").format(name=name))
    d.add_response("cancel", _("Cancel"))
    d.add_response("call", pgettext("verb", "Call"))
    d.set_response_appearance("call", Adw.ResponseAppearance.SUGGESTED)
    d.set_default_response("cancel")
    d.set_close_response("cancel")
    d.connect("response", lambda _d, r: r == "call" and go())
    d.present(win)
