"""Einstellungen -> "Mein Backup" (Server ab 2.3, docs/APP-API.md "Persoenliches Backup"): eigenen Workspace verschluesselt
in die eigene Nextcloud (Login Flow v2) oder ein WebDAV-Ziel sichern. Status, "Jetzt sichern", Ziel, Schluessel (eigener
oeffentlicher Schluessel oder vom Server erzeugt - der private Schluessel wird nur einmal gezeigt und nie gespeichert),
Inhalt (Kategorien, ausgeschlossene Seitenbaeume - nur wenn der Server sie liefert), Zeitplan/Aufbewahrung.
Einspielen: im Web (Hinweis + Knopf). Ohne die Funktion auf dem Server (404) bleibt die Seite unsichtbar."""
import urllib.parse

from gi.repository import Adw, Gio, GLib, Gtk

from .api import APIError, client
from .config import settings
from .i18n import _, pgettext
from .util import bg, date_time_text, human_size, placeholder


def cats():
    return [("pages", _("Pages and subpages (including databases with their entries)")), ("templates", _("Templates")),
            ("archive", pgettext("backup category", "Archive")), ("trash", _("Trash")), ("versions", _("Versions")), ("files", _("Own files and images")),
            ("comments", _("Own comments on own pages")), ("comments_others", _("Comments by other people on my pages")),
            ("tasks", _("Tasks")), ("bookmarks", _("Bookmarks")), ("settings", _("Profile and settings"))]


def weekdays():
    return [_("Monday"), _("Tuesday"), _("Wednesday"), _("Thursday"), _("Friday"), _("Saturday"), _("Sunday")]


def size_text(n):
    return human_size(n) if isinstance(n, (int, float)) and not isinstance(n, bool) else "?"


def when(ts):
    return date_time_text(ts) if ts else "–"


def _btn(label, css=None, cb=None):
    b = Gtk.Button(label=label, valign=Gtk.Align.CENTER)
    if css:
        b.add_css_class(css)
    if cb:
        b.connect("clicked", lambda *_: cb())
    return b


def _esc(s):
    return GLib.markup_escape_text(str(s or ""))


class BackupPage(Adw.PreferencesPage):
    """Einstellungen -> Mein Backup"""

    def __init__(self, dlg):
        super().__init__(title=_("My backup"), icon_name="drive-harddisk-symbolic")
        self.dlg = dlg
        self.S = None
        self.groups = []
        self.timer = 0
        self.key_dialog = None
        self.set_description(_("Back up your own workspace encrypted to your own Nextcloud or another WebDAV target. Only what "
                               "belongs to you is included – what others share with you stays out. Readable only with your "
                               "private key, even without Saga."))
        self.set_visible(False)                           # erst, wenn der Server die Funktion kennt
        self.connect("unrealize", lambda *_: self._stop())
        self.load()

    # --- Laden ----------------------------------------------------------------------------------------------------------
    def _stop(self):
        if self.timer:
            GLib.source_remove(self.timer)
            self.timer = 0

    def _poll(self, ms=3000):
        self._stop()

        def tick():
            self.timer = 0
            if self.get_root() is not None:
                self.load(quiet=True)
            return False
        self.timer = GLib.timeout_add(ms, tick)

    def load(self, quiet=False):
        def fin(s):
            if not isinstance(s, dict) or "allowed" not in s:
                self.set_visible(False)
                return
            self.set_visible(True)
            if getattr(self.dlg, "want_page", None) is self:
                self.dlg.want_page = None
                self.dlg.set_visible_page(self)
            old = self.S
            self.S = s
            # Abfrage waehrend eines Laufs/einer Anmeldung: nur Status und Ziel neu (Eingaben weiter unten bleiben)
            if quiet and old is not None and old.get("allowed") == s.get("allowed") and self.groups:
                self._refresh_top()
            else:
                self.fill()
            if s.get("running") or (s.get("flow") or {}).get("state") == "wait":
                self._poll()

        def err(e):
            if isinstance(e, APIError) and e.code in (404, 405):
                self.set_visible(False)                   # alter Server (vor 2.3)
                return
            if self.S is None:
                self.set_visible(True)
                self._clear()
                g = Adw.PreferencesGroup()
                g.add(Adw.ActionRow(title=_esc(_("Not loaded: {err}").format(err=e))))
                self._add(g)
        bg(client.get, "api/account/backup?pages=1", done=fin, error=err)

    def _clear(self):
        for g in self.groups:
            self.remove(g)
        self.groups = []

    def _add(self, g):
        self.add(g)
        self.groups.append(g)
        return g

    def fill(self):
        self._clear()
        s = self.S
        if not s.get("allowed"):
            g = Adw.PreferencesGroup()
            r = Adw.ActionRow(title=_("Personal backups are turned off on this server."))
            r.add_css_class("error")
            g.add(r)
            self._add(g)
            return
        self.g_status = self._add(Adw.PreferencesGroup(title=_("Status")))
        self.g_target = self._add(Adw.PreferencesGroup(title=_("Target")))
        self._status_part(self.g_status)
        self._target_part(self.g_target)
        self._add(self._key_part())
        if isinstance(s.get("cats"), dict):
            self._add(self._content_part())
        self._add(self._plan_part())
        self._add(self._restore_part())

    def _refresh_top(self):
        """nur Status und Ziel neu aufbauen"""
        for name, part in (("g_status", self._status_part), ("g_target", self._target_part)):
            old = getattr(self, name, None)
            if old is None or old not in self.groups:
                self.fill()
                return
            g = Adw.PreferencesGroup(title=old.get_title())
            part(g)
            i = self.groups.index(old)
            # neu einfuegen an derselben Stelle: alle Gruppen dahinter kurz heraus und wieder hinein
            rest = self.groups[i:]
            for x in rest:
                self.remove(x)
            rest[0] = g
            for x in rest:
                self.add(x)
            self.groups[i:] = rest
            setattr(self, name, g)

    def toast(self, txt):
        self.dlg.add_toast(Adw.Toast(title=_esc(txt)))

    # --- Status + Jetzt sichern ----------------------------------------------------------------------------------------
    def _status_part(self, g):
        s = self.S
        g.add(Adw.ActionRow(title=_("Last successful backup"), subtitle=when(s.get("last_ok")) if s.get("last_ok") else _("none yet")))
        g.add(Adw.ActionRow(title=_("Next run"), subtitle=when(s.get("next")) if s.get("next") else _("Schedule off")))
        runs = [r for r in s.get("runs") or [] if isinstance(r, dict)]
        last = runs[0] if runs else None
        if s.get("running") and last:
            r = Adw.ActionRow(title="⏳ " + _("Backup running:") + " " + _esc(last.get("step") or "…"))
            r.add_suffix(Gtk.Spinner(spinning=True, valign=Gtk.Align.CENTER))
            g.add(r)
        elif last and last.get("finished") and not last.get("ok"):
            r = Adw.ActionRow(title="✗ " + _esc(last.get("error") or _("Backup failed")))
            r.set_title_lines(0)
            r.add_css_class("error")
            g.add(r)
        self.run_row = Adw.ActionRow(title=_("Manual backup"))
        if not s.get("ready"):
            self.run_row.set_subtitle(_("To back up, Saga still needs a target and at least one public key."))
        go = _btn(_("Back up now"), "suggested-action")
        go.set_sensitive(bool(s.get("ready")) and not s.get("running"))
        self.run_btn = go

        def run():
            go.set_sensitive(False)
            self.run_row.set_subtitle(_("Started …"))

            def err(e):
                go.set_sensitive(True)
                self.run_row.set_subtitle(_esc(e))
            bg(client.request, "POST", "api/account/backup/run", done=lambda _r: GLib.timeout_add(1200, lambda: (self.load(quiet=True), False)[1]),
               error=err)
        go.connect("clicked", lambda *_: run())
        self.run_row.add_suffix(go)
        g.add(self.run_row)
        if runs:
            ex = Adw.ExpanderRow(title=_("Recent runs"))
            for r in runs[:5]:
                if r.get("ok"):
                    txt = "✓ " + size_text(r.get("size"))
                elif r.get("finished"):
                    txt = "✗ " + str(r.get("error") or "")
                else:
                    txt = "⏳ " + str(r.get("step") or "…")
                row = Adw.ActionRow(title=_esc(when(r.get("started"))), subtitle=_esc(txt))
                row.set_subtitle_lines(0)
                ex.add_row(row)
            g.add(ex)
        rem = Adw.ExpanderRow(title=_("Backups in the target"), subtitle=_("Download encrypted backups"))
        rem.set_sensitive(bool(s.get("target")))
        rem.loaded = False

        def expanded(row, _p):
            if row.get_expanded() and not row.loaded:
                row.loaded = True
                self._remote(row)
        rem.connect("notify::expanded", expanded)
        g.add(rem)

    def _remote(self, ex):
        wait = Adw.ActionRow(title=_("Loading …"))
        ex.add_row(wait)

        def fin(d):
            ex.remove(wait)
            items = [x for x in (d or {}).get("items") or [] if isinstance(x, dict)]
            if not items:
                ex.add_row(Adw.ActionRow(title=_("No backups in the target yet.")))
            for x in items:
                sub = size_text(x.get("size")) + ("" if x.get("complete") else " · " + _("incomplete"))
                r = Adw.ActionRow(title=_esc(x.get("name")), subtitle=_esc(sub))
                r.set_title_selectable(True)
                if x.get("complete") and x.get("name"):
                    r.add_suffix(_btn(_("Download"), None, lambda n=x["name"]: self.dlg.win.download_open(
                        "/api/account/backup/remote/" + urllib.parse.quote(n, safe=""), n)))
                ex.add_row(r)

        def err(e):
            wait.set_title(_esc(e))
        bg(client.get, "api/account/backup/remote", done=fin, error=err)

    # --- Ziel --------------------------------------------------------------------------------------------------------------
    def _target_part(self, g):
        s = self.S
        fl = s.get("flow") if isinstance(s.get("flow"), dict) else {}
        if fl.get("state") == "wait" and fl.get("login"):
            r = Adw.ActionRow(title=_("Nextcloud found"),
                              subtitle=_("Sign in there and allow access – Saga gets its own app password (Saga never sees your password)."))
            r.set_subtitle_lines(0)
            r.add_suffix(_btn(_("Sign in to Nextcloud"), "suggested-action", lambda: self.dlg.win.open_uri(fl["login"])))
            r.add_suffix(_btn(_("Cancel"), None, lambda: bg(client.request, "POST", "api/account/backup/flow/cancel",
                                                          done=lambda _r: self.load(), error=lambda e: self.load())))
            g.add(r)
            return
        if fl.get("message"):
            r = Adw.ActionRow(title=_esc(fl["message"]))
            r.set_title_lines(0)
            r.add_css_class("error")
            g.add(r)
        elif fl.get("state") == "ok":
            g.add(Adw.ActionRow(title="✓ " + _("Nextcloud connected.")))
        t = s.get("target") if isinstance(s.get("target"), dict) else None
        if t:
            parts = ["Nextcloud" if t.get("kind") == "nextcloud" else "WebDAV", str(t.get("address") or "")]
            if t.get("user"):
                parts.append(str(t["user"]))
            sub = _("Folder “{name}”").format(name=t.get("folder") or "") + (" · " + _("App password (login flow)") if t.get("flow") else "")
            r = Adw.ActionRow(title=_esc(" · ".join(parts)), subtitle=_esc(sub))
            r.set_title_lines(0)
            test = _btn(_("Check connection"))
            r.add_suffix(test)
            r.add_suffix(_btn(_("Disconnect"), "destructive-action", self._disconnect))
            g.add(r)
            if t.get("allowed") is False:
                w = Adw.ActionRow(title=_("The target is not (or no longer) allowed – please connect again."))
                w.add_css_class("error")
                g.add(w)

            def check():
                r.set_subtitle(_("Checking …"))

                def fin(d):
                    free = (d or {}).get("free")
                    r.set_subtitle(_esc(_("Connection OK. Free in the target: {free}.").format(
                        free=_("unknown") if free is None else size_text(free))))

                def err(e):
                    r.set_subtitle(_esc(e))
                bg(client.json, "POST", "api/account/backup/test", {}, done=fin, error=err)
            test.connect("clicked", lambda *_: check())
            self.test_row = r
            return
        g.set_description(_("Nextcloud: just enter the address – you then sign in in Nextcloud. Other WebDAV: address of the "
                            "folder plus user and password. Only https addresses on the internet (internal servers only if "
                            "the admin allows them)."))
        url = Adw.EntryRow(title=_("Address"))
        placeholder(url, "https://cloud.example.org")
        user = Adw.EntryRow(title=_("User (only for WebDAV)"))
        pw = Adw.PasswordEntryRow(title=_("Password (only for WebDAV)"))
        folder = Adw.EntryRow(title=_("Folder"), text="Saga-Backup")
        st = Adw.ActionRow(title=_("Connect"))
        go = _btn(_("Connect"), "suggested-action")

        def connect():
            st.set_subtitle(_("Checking …"))
            go.set_sensitive(False)

            def fin(d):
                pw.set_text("")
                if isinstance(d, dict) and d.get("flow"):
                    self.dlg.win.open_uri(d["flow"])
                self.load()

            def err(e):
                go.set_sensitive(True)
                st.set_subtitle(_esc(e))
            bg(client.json, "POST", "api/account/backup/connect", {"url": url.get_text().strip(), "user": user.get_text().strip(),
                                                                  "password": pw.get_text(), "folder": folder.get_text().strip()},
               done=fin, error=err)
        go.connect("clicked", lambda *_: connect())
        st.add_suffix(go)
        for w in (url, user, pw, folder, st):
            g.add(w)
        self.target_form = (url, user, pw, folder, go)      # fuer Tests

    def _disconnect(self):
        d = Adw.AlertDialog(heading=_("Disconnect target?"),
                            body=_("Existing backups in the target are kept. An app password from the Nextcloud sign-in is revoked."))
        d.add_response("cancel", _("Cancel"))
        d.add_response("ok", _("Disconnect"))
        d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)
        d.set_close_response("cancel")
        d.connect("response", lambda dd, r: r == "ok" and bg(client.request, "DELETE", "api/account/backup/target",
                                                             done=lambda _r: self.load(), error=lambda e: self.toast(str(e))))
        d.present(self.dlg)
        self.ask = d

    # --- Schluessel ----------------------------------------------------------------------------------------------------------
    def _key_part(self):
        s = self.S
        g = Adw.PreferencesGroup(title=_("Encryption"))
        keys = [k for k in s.get("keys") or [] if isinstance(k, dict)]
        if not keys:
            g.set_description(_("No key yet – without a key Saga doesn’t back up."))
        for k in keys:
            r = Adw.ActionRow(title=_esc(k.get("short")), subtitle=_esc(k.get("fp")))
            r.set_subtitle_selectable(True)
            r.add_suffix(_btn(_("Remove"), "destructive-action", lambda k=k: self._drop_key(k)))
            g.add(r)
        gen = Adw.ActionRow(title=_("Generate key pair"), subtitle=_("The private key is shown only once and never stored by Saga."))
        gb = _btn(_("Generate"))
        gb.set_sensitive(bool(s.get("age_ok", True)))
        gb.connect("clicked", lambda *_: self._generate(gb))
        gen.add_suffix(gb)
        g.add(gen)
        pub = Adw.EntryRow(title=_("Add your own public key (age1… or ssh-ed25519 …)"), show_apply_button=True)

        def add(row):
            txt = row.get_text().strip()
            if not txt:
                return

            def fin(_d):
                row.set_text("")
                self.load()
            bg(client.json, "POST", "api/account/backup/key", {"key": txt}, done=fin, error=lambda e: self.toast(str(e)))
        pub.connect("apply", add)
        g.add(pub)
        return g

    def _drop_key(self, k):
        d = Adw.AlertDialog(heading=_("Remove key?"),
                            body=_("New backups can then no longer be read with it. Older backups stay readable with it."))
        d.add_response("cancel", _("Cancel"))
        d.add_response("ok", _("Remove"))
        d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)
        d.set_close_response("cancel")
        d.connect("response", lambda dd, r: r == "ok" and bg(client.request, "DELETE", "api/account/backup/key/" +
                                                             urllib.parse.quote(str(k.get("fp") or ""), safe=""),
                                                             done=lambda _r: self.load(), error=lambda e: self.toast(str(e))))
        d.present(self.dlg)

    def _generate(self, btn):
        btn.set_sensitive(False)

        def fin(d):
            btn.set_sensitive(True)
            if isinstance(d, dict) and d.get("private"):
                self.key_dialog = NewKeyDialog(self, d)
                self.key_dialog.present(self.dlg)

        def err(e):
            btn.set_sensitive(True)
            self.toast(str(e))
        bg(client.json, "POST", "api/account/backup/key/generate", {}, done=fin, error=err)

    # --- Inhalt ---------------------------------------------------------------------------------------------------------------
    def _content_part(self):
        s = self.S
        g = Adw.PreferencesGroup(title=_("What is backed up"),
                                 description=_("Never included: pages and files of other people (also “Shared with me”) and chats – "
                                               "they are end-to-end encrypted and belong to everyone in them."))
        cur = s.get("cats") or {}
        self.cat_rows = {}
        for key, label in cats():
            if key not in cur:
                continue
            r = Adw.SwitchRow(title=label, active=bool(cur.get(key)))
            if key == "comments_others":
                r.set_subtitle(_("Off by default – the comments belong to the people who wrote them."))
            g.add(r)
            self.cat_rows[key] = r
        ex = None
        pages = [p for p in s.get("pages") or [] if isinstance(p, dict) and p.get("id") is not None]
        if isinstance(s.get("exclude"), list) and "pages" in s:     # Seitenbaeume ausschliessen (nur wenn der Server sie liefert)
            ex = [int(x) for x in s["exclude"] if isinstance(x, int) or str(x).isdigit()]
            self.excluded = ex
            self._ex_group(g, pages)
        msg = Adw.ActionRow(title=_("Save selection"))
        sv = _btn(_("Save"), "suggested-action")

        def save():
            body = {"cats": {k: r.get_active() for k, r in self.cat_rows.items()}}
            if ex is not None:
                body["exclude"] = list(self.excluded)
            msg.set_subtitle(_("Saving …"))

            def fin(d):
                if isinstance(d, dict) and "allowed" in d:
                    d.setdefault("pages", s.get("pages"))
                    self.S = d
                msg.set_subtitle("✓ " + _("Saved."))
            bg(client.json, "PUT", "api/account/backup/settings", body, done=fin, error=lambda e: msg.set_subtitle(_esc(e)))
        sv.connect("clicked", lambda *_: save())
        msg.add_suffix(sv)
        g.add(msg)
        self.content_save = sv
        return g

    def _ex_group(self, g, pages):
        by = {p["id"]: p for p in pages}
        kids = {}
        for p in pages:
            kids.setdefault(p.get("parent") if p.get("parent") in by else 0, []).append(p)
        order = []

        def walk(k, depth):
            for p in kids.get(k, []):
                order.append((p, depth))
                if depth < 12:
                    walk(p["id"], depth + 1)
        walk(0, 0)

        def title(p):
            return ((p.get("icon") + " ") if p.get("icon") else "") + (p.get("title") or _("Untitled"))
        ex = Adw.ExpanderRow(title=_("Don’t back up these pages"))
        holder = []

        def draw():
            for r in holder:
                ex.remove(r)
            holder.clear()
            ex.set_subtitle(_("None – all own pages are backed up.") if not self.excluded else
                            ", ".join(title(by[i]) if i in by else f"#{i}" for i in self.excluded))
            for i, pid in enumerate(list(self.excluded)):
                r = Adw.ActionRow(title=_esc((title(by[pid]) if pid in by else f"#{pid}") + " " + _("(with subpages)")))
                rm = Gtk.Button(icon_name="window-close-symbolic", valign=Gtk.Align.CENTER, tooltip_text=_("Remove"))
                rm.add_css_class("flat")
                rm.update_property([Gtk.AccessibleProperty.LABEL], [_("Remove")])
                rm.connect("clicked", lambda *_a, pid=pid: (self.excluded.remove(pid), draw()))
                r.add_suffix(rm)
                ex.add_row(r)
                holder.append(r)
            names = [_("– Choose page –")] + ["    " * d + title(p) for p, d in order]
            pick = Adw.ComboRow(title=_("Exclude"), model=Gtk.StringList.new(names))

            def chosen(row, _p):
                k = row.get_selected()
                if 0 < k <= len(order):
                    pid = order[k - 1][0]["id"]
                    if pid not in self.excluded:
                        self.excluded.append(pid)
                    GLib.idle_add(lambda: (draw(), False)[1])
            pick.connect("notify::selected", chosen)
            ex.add_row(pick)
            holder.append(pick)
            self.ex_pick = pick
        draw()
        g.add(ex)
        self.ex_row = ex

    # --- Zeitplan -----------------------------------------------------------------------------------------------------------
    def _plan_part(self):
        s = self.S
        lim = s.get("limits") if isinstance(s.get("limits"), dict) else {}
        g = Adw.PreferencesGroup(title=_("Schedule and retention"))
        if lim.get("max_mb"):
            g.set_description(_("The newest backups stay in the target, Saga deletes older ones. At most {mb} MB per backup.").format(mb=lim["max_mb"]))
        opts = [("off", _("off")), ("weekly", _("weekly")), ("daily", _("daily"))]
        if lim.get("max_schedule") == "weekly" and s.get("schedule") != "daily":
            opts = opts[:2]                                     # auf diesem Server hoechstens woechentlich
        keys = [o[0] for o in opts]
        sch = Adw.ComboRow(title=_("Back up automatically"), model=Gtk.StringList.new([o[1] for o in opts]))
        sch.set_selected(keys.index(s.get("schedule")) if s.get("schedule") in keys else 0)
        tm = Adw.EntryRow(title=_("Time (hh:mm)"), text=str(s.get("time") or "02:40"))
        wd = Adw.ComboRow(title=_("Weekday"), model=Gtk.StringList.new(weekdays()))
        try:
            wd.set_selected(int(s.get("weekday") or 0) % 7)
        except (TypeError, ValueError):
            wd.set_selected(0)
        keep = Adw.SpinRow.new_with_range(1, 60, 1)
        keep.set_title(_("Keep backups"))
        try:
            keep.set_value(int(s.get("keep") or 7))
        except (TypeError, ValueError):
            keep.set_value(7)

        def vis(*_a):
            k = keys[sch.get_selected()] if sch.get_selected() < len(keys) else "off"
            wd.set_visible(k == "weekly")
            tm.set_visible(k != "off")
        sch.connect("notify::selected", vis)
        vis()
        msg = Adw.ActionRow(title=_("Save schedule"))
        sv = _btn(_("Save"), "suggested-action")

        def save():
            body = {"schedule": keys[sch.get_selected()], "time": tm.get_text().strip(), "weekday": int(wd.get_selected()),
                    "keep": int(keep.get_value())}
            msg.set_subtitle(_("Saving …"))

            def fin(d):
                if isinstance(d, dict) and "allowed" in d:
                    d.setdefault("pages", s.get("pages"))
                    self.S = d
                    self._refresh_top()                       # "Naechster Lauf" stimmt sofort
                msg.set_subtitle("✓ " + _("Saved."))
            bg(client.json, "PUT", "api/account/backup/settings", body, done=fin, error=lambda e: msg.set_subtitle(_esc(e)))
        sv.connect("clicked", lambda *_: save())
        msg.add_suffix(sv)
        for w in (sch, tm, wd, keep, msg):
            g.add(w)
        self.plan = (sch, tm, wd, keep, sv, msg)                   # fuer Tests
        return g

    # --- Einspielen: im Web --------------------------------------------------------------------------------------------------
    def _restore_part(self):
        g = Adw.PreferencesGroup(title=_("Restore backup"))
        r = Adw.ActionRow(title=_("Restore in the web"),
                          subtitle=_("Restoring a personal backup into your account (as new pages) works in Saga in the browser: "
                                     "Settings → My backup → Restore backup."))
        r.set_subtitle_lines(0)
        r.add_suffix(_btn(_("Open in browser"), None, lambda: self.dlg.win.open_uri(settings.server.rstrip("/") + "/?settings=backup")))
        g.add(r)
        return g


class NewKeyDialog(Adw.Dialog):
    """Neu erzeugter privater Schluessel: nur hier zu sehen (kopieren oder als Datei sichern), dann mit den letzten 6 Zeichen
    bestaetigen. Saga speichert ihn nicht; beim Schliessen ohne Bestaetigung wird der Vorgang verworfen."""

    def __init__(self, page, d):
        super().__init__(title=_("New private key"), content_width=560, content_height=500)
        self.page = page
        self.done = False
        self.private = str(d.get("private") or "")
        self.filename = str(d.get("filename") or "saga-backup.key")
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_top=12, margin_bottom=12, margin_start=14, margin_end=14)
        lb = Gtk.Label(label=_("New private key – shown only now and not stored by Saga:"), xalign=0, wrap=True)
        lb.add_css_class("heading")
        box.append(lb)
        self.view = Gtk.TextView(editable=False, monospace=True, wrap_mode=Gtk.WrapMode.CHAR, cursor_visible=False)
        self.view.add_css_class("card")
        self.view.set_top_margin(8)
        self.view.set_bottom_margin(8)
        self.view.set_left_margin(8)
        self.view.set_right_margin(8)
        self.view.get_buffer().set_text(self.private)
        self.view.update_property([Gtk.AccessibleProperty.LABEL], [_("Private key")])
        box.append(self.view)
        bar = Gtk.Box(spacing=6)
        cp = Gtk.Button(label=_("Copy"))
        cp.connect("clicked", lambda *_: self._copy())
        bar.append(cp)
        sv = Gtk.Button(label=_("Save as file …"))
        sv.connect("clicked", lambda *_: self._save())
        bar.append(sv)
        box.append(bar)
        hint = Gtk.Label(label=_("Keep it in a safe place (password manager, printout) – not on this server. Without it your "
                                 "backups cannot be restored."), xalign=0, wrap=True)
        hint.add_css_class("dim-label")
        box.append(hint)
        lst = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lst.add_css_class("boxed-list")
        self.last = Adw.EntryRow(title=_("To confirm: the last 6 characters of the private key"))
        self.last.connect("entry-activated", lambda *_: self._confirm())
        lst.append(self.last)
        box.append(lst)
        self.msg = Gtk.Label(xalign=0, wrap=True)
        self.msg.add_css_class("error")
        box.append(self.msg)
        b2 = Gtk.Box(spacing=6, halign=Gtk.Align.END)
        drop = Gtk.Button(label=_("Discard"))
        drop.connect("clicked", lambda *_: self.close())
        b2.append(drop)
        self.ok = Gtk.Button(label=_("Key is stored safely – add it"))
        self.ok.add_css_class("suggested-action")
        self.ok.connect("clicked", lambda *_: self._confirm())
        b2.append(self.ok)
        box.append(b2)
        sc = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True)
        sc.set_child(box)
        tv.set_content(sc)
        self.set_child(tv)
        self.connect("closed", lambda *_: self._closed())

    def _wipe(self):
        self.private = ""
        self.view.get_buffer().set_text("")

    def _closed(self):
        if not self.done:
            bg(client.json, "POST", "api/account/backup/key/confirm", {"drop": True}, error=lambda e: None)
        self._wipe()

    def _copy(self):
        self.page.dlg.win.copy_text(self.private)
        self.page.toast(_("Copied"))

    def _save(self):
        fd = Gtk.FileDialog(title=_("Save private key"), initial_name=self.filename)
        data = self.private

        def chosen(d, res):
            try:
                f = d.save_finish(res)
            except GLib.Error:
                return
            if f is None:
                return
            try:
                f.replace_contents(data.encode(), None, False, Gio.FileCreateFlags.PRIVATE | Gio.FileCreateFlags.REPLACE_DESTINATION, None)
                self.page.toast(_("Saved: {name}").format(name=f.get_basename() or self.filename))
            except GLib.Error as e:
                self.msg.set_label(str(e.message))
        fd.save(self.page.dlg.win, None, chosen)

    def _confirm(self):
        last = self.last.get_text().strip()
        if not last:
            return
        self.ok.set_sensitive(False)

        def fin(_d):
            self.done = True
            self._wipe()
            self.close()
            self.page.load()

        def err(e):
            self.ok.set_sensitive(True)
            self.msg.set_label(str(e))
        bg(client.json, "POST", "api/account/backup/key/confirm", {"last6": last}, done=fin, error=err)

