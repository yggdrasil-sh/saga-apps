"""Einstellungen wie in der Mac-App: Server, Darstellung, Immich, Kalender (CalDAV), App-Schluessel, angemeldete Apps."""
from gi.repository import Adw, Gdk, GLib, Gtk

from . import config
from .api import client
from .config import settings
from .i18n import _, ngettext
from .store import store
from .util import bg, date_short, placeholder


def _clear_group(g, rows):
    for r in rows:
        g.remove(r)
    rows.clear()


def _btn(label, css=None, cb=None):
    b = Gtk.Button(label=label, valign=Gtk.Align.CENTER)
    if css:
        b.add_css_class(css)
    if cb:
        b.connect("clicked", lambda *_: cb())
    return b


class SettingsDialog(Adw.PreferencesDialog):
    def __init__(self, win):
        super().__init__(title=_("Settings"), search_enabled=False)
        if (Adw.get_major_version(), Adw.get_minor_version()) < (1, 7):
            # libadwaita 1.6 (AlmaLinux 10): bei 640 px kuerzt die Reiterleiste "Mitteilungen" - etwas breiter
            self.set_content_width(720)
        self.win = win
        page = Adw.PreferencesPage(title=_("General"), icon_name="emblem-system-symbolic")
        g = Adw.PreferencesGroup(title=_("Server"))
        self.server = Adw.EntryRow(title=_("Address"), text=settings.server, show_apply_button=True)
        placeholder(self.server, "https://saga.example.org")
        self.server.connect("apply", self._apply_server)
        g.add(self.server)
        from . import VERSION
        srv = f"Saga {store.server_version}" if store.server_version else "–"
        about = Adw.ActionRow(title=_("Version"), subtitle=_("App: Saga {version} · Server: {server}").format(version=VERSION, server=srv))
        if store.app_too_old:
            about.set_subtitle(about.get_subtitle() + " · " + _("app too old (version {version} or later needed), please update").format(version=store.min_app))
        g.add(about)
        self.status = Adw.ActionRow(title=_("Test connection"))
        self.status.add_suffix(_btn(_("Test"), cb=self._test))
        g.add(self.status)
        page.add(g)
        g2 = Adw.PreferencesGroup(title=_("Appearance"))
        ch = Adw.SpinRow.new_with_range(100, 600, 10)
        ch.set_title(_("Cover image height"))
        ch.set_subtitle(_("Applies to all pages on this computer. You can also drag the image to change it."))
        ch.set_value(settings.get("cover_height", 200))
        ch.connect("notify::value", lambda r, _: settings.set("cover_height", int(r.get_value())))
        g2.add(ch)
        page.add(g2)
        page.add(self._language_group())
        g3 = Adw.PreferencesGroup()
        out = Adw.ButtonRow(title=_("Sign out")) if hasattr(Adw, "ButtonRow") else Adw.ActionRow(title=_("Sign out"), activatable=True)
        out.add_css_class("destructive-action")
        out.connect("activated", lambda *_: self._logout())
        g3.add(out)
        g3.set_description(_("Removes the sign-in and the cache on this device."))
        page.add(g3)
        self.add(page)
        acc = Adw.PreferencesPage(title=_("Account"), icon_name="avatar-default-symbolic")
        from .profile import AvatarGroup
        acc.add(AvatarGroup(self))                 # Profilbild (Server ab 2.1); sonst unsichtbar
        from .security import SecurityGroup
        acc.add(SecurityGroup(self))               # Anmeldung & Sicherheit (2FA, Server ab 2.1): Stand + Browser
        from .publish import PublicNameGroup
        acc.add(PublicNameGroup(self))             # oeffentlicher Name (Veroeffentlichen); aeltere Server: unsichtbar
        from .notifications import NotificationSettingsGroup
        acc.add(NotificationSettingsGroup(self))   # Benachrichtigungen je Art (Server ab 1.4); sonst unsichtbar
        acc.add(ImmichGroup(self))
        acc.add(CalendarGroup(self))
        acc.add(AppsGroup(self))
        self.add(acc)
        self.pages = {"general": page, "account": acc}
        from .push import PushPage                 # Mitteilungen (Server ab 1.7)
        self.pages["push"] = PushPage(self)
        self.add(self.pages["push"])
        from .chat import chat
        if chat.status in ("setup", "device", "ready"):
            from .chatview import ChatDevicesPage  # Chats & Geraete (Server ab 1.5)
            self.pages["chats"] = ChatDevicesPage(self)
            self.add(self.pages["chats"])

    # Sprache der App (settings.json "language"; leer = Systemsprache). Gilt ab dem naechsten Start (i18n.py),
    # auch fuer Accept-Language der Anfragen an den Server.
    LANG_CODES = ("", "de", "en", "es", "ru", "th")

    def _language_group(self):
        g = Adw.PreferencesGroup(title=_("Language"))
        names = [_("Automatic"), "Deutsch", "English", "Español", "Русский", "ไทย"]   # Sprachnamen in der eigenen Sprache
        self.lang = Adw.ComboRow(title=_("App language"), model=Gtk.StringList.new(names))
        cur = settings.get("language") or ""
        self.lang.set_selected(self.LANG_CODES.index(cur) if cur in self.LANG_CODES else 0)
        self._lang_row_subtitle()
        self.lang.connect("notify::selected", self._language_changed)
        g.add(self.lang)
        return g

    def _lang_row_subtitle(self):
        from . import i18n
        want = settings.get("language") or ""
        if want != i18n.CHOSEN:
            self.lang.set_subtitle(_("Takes effect after restarting Saga"))
        else:
            self.lang.set_subtitle(_("“Automatic” follows the system language"))

    def _language_changed(self, row, _p):
        i = row.get_selected()
        code = self.LANG_CODES[i] if 0 <= i < len(self.LANG_CODES) else ""
        if code == (settings.get("language") or ""):
            return
        settings.set("language", code or None)
        self._lang_row_subtitle()
        from . import i18n
        if code == i18n.CHOSEN:                       # zurueck auf die laufende Sprache: kein Neustart noetig
            return
        d = Adw.AlertDialog(heading=_("Restart Saga to change the language"),
                            body=_("The new language is used the next time Saga starts. Unsaved changes are saved first."))
        d.add_response("later", _("Later"))
        d.add_response("restart", _("Restart now"))
        d.set_response_appearance("restart", Adw.ResponseAppearance.SUGGESTED)
        d.set_default_response("restart")
        d.set_close_response("later")

        def resp(dd, r):
            if r == "restart":
                self.force_close()
                self.win.get_application().restart()
        d.connect("response", resp)
        d.present(self)
        self.lang_dialog = d                          # fuer Tests

    def show_page(self, name):
        p = self.pages.get(name)
        if p is not None:
            self.set_visible_page(p)

    def _apply_server(self, row):
        u = row.get_text().strip().rstrip("/")
        if u and "://" not in u:
            u = "https://" + u
        if not config.allowed_server(u) or "." not in u and "localhost" not in u:
            self.add_toast(Adw.Toast(title=_("Please enter an https address")))
            return
        u = config.origin(u)
        if u == config.origin(settings.server):
            row.set_text(settings.server)
            return
        host = GLib.Uri.parse(u, GLib.UriFlags.NONE).get_host()
        d = Adw.AlertDialog(heading=_("Switch to {host}?").format(host=host),
                            body=_("This device is signed out of the current server, and its session, cache, recent pages and "
                                   "home page are removed from this device. Unsaved changes are saved first. Then sign in to the "
                                   "new server."))
        d.add_response("cancel", _("Cancel"))
        d.add_response("ok", _("Switch server"))
        d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)
        d.set_default_response("cancel")
        d.set_close_response("cancel")

        def resp(dd, r):
            if r != "ok":
                row.set_text(settings.server)
                return
            win = self.win
            self.close()

            def switch():
                store.switch_server(u)                    # Sitzung, Zwischenspeicher, Zuletzt, Startseite weg; beim alten abmelden
                win.login_server.set_text(u)
                store.refresh()                           # ohne Sitzung dort: Anmeldung
            win.flush_then(switch)                        # erst zum alten Server speichern (Rest bleibt dort als Entwurf)
        d.connect("response", resp)
        d.present(self)

    def _test(self):
        self.status.set_subtitle(_("Checking …"))

        def fin(j):
            n = len(j.get("events") or []) if isinstance(j, dict) else 0
            np = len(store.pages)
            self.status.set_subtitle("✓ " + _("Connected – {pages}, {events} today").format(
                pages=ngettext("{n} page", "{n} pages", np).format(n=np), events=ngettext("{n} event", "{n} events", n).format(n=n)))
        bg(client.get, store.ev_path(1), done=fin, error=lambda e: self.status.set_subtitle("✗ " + GLib.markup_escape_text(str(e))))

    def _logout(self):
        d = Adw.AlertDialog(heading=_("Sign out?"), body=_("The sign-in and the cache on this device will be removed."))
        d.add_response("cancel", _("Cancel"))
        d.add_response("ok", _("Sign out"))
        d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)

        def resp(dd, r):
            if r == "ok":
                self.close()
                self.win._flush_all()                     # Ungespeichertes zuerst senden (sonst bleibt es als Entwurf)
                store.logout()
        d.connect("response", resp)
        d.present(self)


class ImmichGroup(Adw.PreferencesGroup):
    def __init__(self, dlg):
        super().__init__(title="Immich")
        self.dlg = dlg
        self.rows = []
        self.load()

    def load(self):
        from .clipimg import forget_immich
        forget_immich()                                # Chat fragt beim naechsten Anhaengen neu ("Aus Immich")
        bg(client.get, "api/settings/immich", done=self.fill, error=lambda e: None)

    def fill(self, info):
        _clear_group(self, self.rows)
        if info.get("connected"):
            host = (info.get("url") or "").replace("https://", "").replace("http://", "")
            who = _("Connected as {name}").format(name=GLib.markup_escape_text(info["name"])) if info.get("name") else _("Connected")
            r = Adw.ActionRow(title="✅ " + who, subtitle=GLib.markup_escape_text(host))
            r.add_suffix(_btn(_("Disconnect"), "destructive-action", lambda: bg(client.request, "DELETE", "api/settings/immich", done=lambda _: self.load())))
            self.add(r)
            self.rows.append(r)
            return
        self.set_description(_("In Immich, create a key with album.read, asset.read, asset.view, asset.download and user.read "
                               "under Account Settings → API Keys and paste it here."))
        url = Adw.EntryRow(title=_("Immich address"), text=info.get("suggest") or "")
        key = Adw.PasswordEntryRow(title=_("API key"))
        st = Adw.ActionRow(title=_("Connect"))

        def go():
            st.set_subtitle(_("Checking …"))
            bg(client.json, "POST", "api/settings/immich", {"url": url.get_text().strip(), "apikey": key.get_text().strip()},
               done=lambda _: self.load(), error=lambda e: st.set_subtitle(GLib.markup_escape_text(str(e))))
        st.add_suffix(_btn(_("Connect"), "suggested-action", go))
        for r in (url, key, st):
            self.add(r)
            self.rows.append(r)


def _rgba(hexs):
    c = Gdk.RGBA()
    if not c.parse(hexs or "#4184e4"):
        c.parse("#4184e4")
    return c


def _hex(c):
    return "#%02x%02x%02x" % (int(c.red * 255), int(c.green * 255), int(c.blue * 255))


class CalEntries(Gtk.Box):
    """Kalender eines Zugangs: anzeigen, Farbe, Name"""

    def __init__(self, cals):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        self.cals = [dict(c) for c in cals]
        for c in self.cals:
            r = Gtk.Box(spacing=8)
            sw = Gtk.CheckButton(active=c.get("show", True) is not False)
            sw.connect("toggled", lambda s, c=c: c.__setitem__("show", s.get_active()))
            r.append(sw)
            cb = Gtk.ColorDialogButton(dialog=Gtk.ColorDialog(with_alpha=False), rgba=_rgba(c.get("color")))
            cb.connect("notify::rgba", lambda b, _, c=c: c.__setitem__("color", _hex(b.get_rgba())))
            r.append(cb)
            en = Gtk.Entry(text=c.get("name") or "", hexpand=True)
            en.connect("changed", lambda e, c=c: c.__setitem__("name", e.get_text().strip()))
            r.append(en)
            if c.get("writable") is False:
                lb = Gtk.Label(label=_("read-only"))
                lb.add_css_class("dim-label")
                r.append(lb)
            self.append(r)

    def dicts(self):
        return [{"href": c.get("href"), "name": c.get("name"), "color": c.get("color") or "#4184e4", "show": c.get("show", True) is not False} for c in self.cals]


class CalendarGroup(Adw.PreferencesGroup):
    def __init__(self, dlg):
        super().__init__(title=_("Calendar"), description=_("Connect external calendars (CalDAV) such as mailcow or Nextcloud. "
                                                                     "Tip: in mailcow, create a separate app password just for “DAV”."))
        self.dlg = dlg
        self.rows = []
        self.allowed = []
        self.load()

    def load(self):
        bg(client.get, "api/settings/calendars", done=self.fill, error=lambda e: None)

    def fill(self, j):
        _clear_group(self, self.rows)
        self.allowed = j.get("allowed") or []
        accs = j.get("accounts") or []
        if not accs:
            r = Adw.ActionRow(title=_("No calendar connected yet."))
            self._add(r)
        for a in accs:
            self._add(self._account(a))
        add = Adw.ActionRow(title=_("Connect calendar"))
        add.add_suffix(_btn("+ " + _("Connect"), cb=lambda: self._new()))
        self._add(add)

    def _add(self, w):
        self.add(w)
        self.rows.append(w)

    def _account(self, a):
        aid = a.get("id")
        host = GLib.Uri.parse(a.get("base_url") or "https://x", GLib.UriFlags.NONE).get_host() if a.get("base_url") else ""
        ex = Adw.ExpanderRow(title=GLib.markup_escape_text(a.get("label") or a.get("username") or _("Calendar")),
                             subtitle=GLib.markup_escape_text(f"{a.get('username') or ''} · {host} · ") + _("password saved") + " ✓")
        ce = CalEntries(a.get("calendars") or [])
        holder = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=8, margin_bottom=8, margin_start=12, margin_end=12)
        holder.append(ce)
        st = Gtk.Label(xalign=0, wrap=True)
        st.add_css_class("dim-label")
        bar = Gtk.Box(spacing=6)

        def run(method, path, body, msg):
            st.set_label("…")
            bg(client.json, method, path, body, done=lambda _: (st.set_label(msg), store.emit("events-changed")), error=lambda e: st.set_label(str(e)))
        bar.append(_btn(_("Save selection"), cb=lambda: run("PATCH", f"api/settings/calendars/{aid}", {"calendars": ce.dicts()}, "✓ " + _("Saved"))))

        def reread():
            st.set_label("…")

            def fin(d):
                found = d.get("calendars") or []
                by = {c["href"]: c for c in ce.cals}
                merged = [by.get(f["href"]) or dict(f, show=False) for f in found]
                a["calendars"] = merged
                self.fill_again()
                st.set_label(ngettext("{n} calendar – remember to save the selection.", "{n} calendars – remember to save the selection.",
                                      len(found)).format(n=len(found)))
            bg(client.json, "POST", "api/settings/calendars/test", {"id": aid}, done=fin, error=lambda e: st.set_label(str(e)))
        bar.append(_btn(_("Rescan"), cb=reread))
        pw = Gtk.PasswordEntry(placeholder_text=_("New app password"), show_peek_icon=True, hexpand=True)
        bar.append(_btn(_("Remove"), "destructive-action", lambda: self._remove(aid)))
        holder.append(bar)
        pr = Gtk.Box(spacing=6)
        pr.append(pw)
        pr.append(_btn(_("Check & save password"), cb=lambda: run("PATCH", f"api/settings/calendars/{aid}", {"password": pw.get_text()},
                                                                    "✓ " + _("New password saved"))))
        holder.append(pr)
        holder.append(st)
        ex.add_row(holder)
        return ex

    def fill_again(self):
        self.load()

    def _remove(self, aid):
        d = Adw.AlertDialog(heading=_("Remove calendar account?"), body=_("The events in the calendar itself are kept."))
        d.add_response("cancel", _("Cancel"))
        d.add_response("ok", _("Remove"))
        d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)
        d.connect("response", lambda dd, r: r == "ok" and bg(client.request, "DELETE", f"api/settings/calendars/{aid}", done=lambda _: self.load()))
        d.present(self.dlg)

    def _new(self):
        NewCalendar(self).present(self.dlg)


class NewCalendar(Adw.Dialog):
    def __init__(self, group):
        super().__init__(title=_("Connect calendar"), content_width=480, content_height=600)
        self.group = group
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        page = Adw.PreferencesPage()
        g = Adw.PreferencesGroup()
        kinds = [("mailcow", "mailcow (SOGo)"), ("nextcloud", "Nextcloud"), ("other", _("Other CalDAV address"))]
        self.kind = Adw.ComboRow(title=_("Provider"), model=Gtk.StringList.new([k[1] for k in kinds]))
        allowed = group.allowed
        mc = next((a for a in allowed if a.startswith("mail.")), "mail.example.org")   # Vorgabe aus den erlaubten Servern
        self.url = Adw.EntryRow(title=_("CalDAV address"), text=f"https://{mc}/SOGo/dav/")

        def kind_changed(r, _):
            k = kinds[r.get_selected()][0]
            nc = next((a for a in allowed if not a.startswith("mail.")), "HOST")
            self.url.set_text(f"https://{mc}/SOGo/dav/" if k == "mailcow" else f"https://{nc}/remote.php/dav/" if k == "nextcloud" else "https://")
        self.kind.connect("notify::selected", kind_changed)
        self.user = Adw.EntryRow(title=_("User (e.g. name@example.org)"))
        self.pw = Adw.PasswordEntryRow(title=_("App password"))
        self.label = Adw.EntryRow(title=_("Label (optional, e.g. “Personal”)"))
        for r in (self.kind, self.url, self.user, self.pw, self.label):
            g.add(r)
        g.set_description(_("Allowed servers: {list}").format(list=GLib.markup_escape_text(", ".join(allowed)) if allowed else "–"))
        page.add(g)
        g2 = Adw.PreferencesGroup()
        self.box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.msg = Gtk.Label(xalign=0, wrap=True)
        tb = Gtk.Button(label=_("Test connection"), halign=Gtk.Align.START)
        tb.connect("clicked", lambda *_: self._test())
        self.box.append(tb)
        self.box.append(self.msg)
        self.cal_holder = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.box.append(self.cal_holder)
        g2.add(self.box)
        page.add(g2)
        tv.set_content(page)
        self.set_child(tv)
        self.entries = None

    def _test(self):
        self.msg.set_label(_("Connecting …"))

        def fin(d):
            cals = d.get("calendars") or []
            self.msg.set_label("✓ " + ngettext("Connected – {n} calendar found. Select and save:",
                                                "Connected – {n} calendars found. Select and save:", len(cals)).format(n=len(cals)))
            while (c := self.cal_holder.get_first_child()):
                self.cal_holder.remove(c)
            self.entries = CalEntries(cals)
            self.cal_holder.append(self.entries)
            sv = Gtk.Button(label=_("Connect & save"), halign=Gtk.Align.START)
            sv.add_css_class("suggested-action")
            sv.connect("clicked", lambda *_: self._save())
            self.cal_holder.append(sv)
        bg(client.json, "POST", "api/settings/calendars/test", {"base_url": self.url.get_text().strip(), "username": self.user.get_text().strip(),
                                                               "password": self.pw.get_text()}, done=fin, error=lambda e: self.msg.set_label(str(e)))

    def _save(self):
        b = {"base_url": self.url.get_text().strip(), "username": self.user.get_text().strip(), "password": self.pw.get_text(),
             "label": self.label.get_text().strip(), "calendars": self.entries.dicts() if self.entries else []}

        def fin(_):
            store.emit("events-changed")
            store.load_today()
            self.group.load()
            self.close()
        bg(client.json, "POST", "api/settings/calendars", b, done=fin, error=lambda e: self.msg.set_label(str(e)))


class AppsGroup(Adw.PreferencesGroup):
    """Eine Liste: angemeldete Geraete (mit ihrem Widget-/Teilen-Schluessel); von Hand erzeugte Schluessel eingeklappt."""

    def __init__(self, dlg):
        # Translators: the title is markup - write "&" as "&amp;"
        super().__init__(title=_("Apps &amp; devices"),
                         description=_("Signed-in apps stay signed in. Each one has a small key for widgets, sharing and "
                                       "shortcuts. Lost a device? Sign it out and both are gone."))
        self.dlg = dlg
        self.rows = []
        self.fresh = None
        self.load()

    def load(self):
        bg(client.get, "api/settings/apps", done=self.fill, error=lambda e: None)

    def fill(self, d):
        _clear_group(self, self.rows)
        d = d or {}
        devs, keys = d.get("devices") or [], d.get("keys") or []
        if not devs:
            self._add(Adw.ActionRow(title=_("No app signed in.")))
        for k in devs:
            sub = _("signed in {date} · last used {last}").format(date=date_short(k.get("created")), last=date_short(k.get("last_used")))
            if k.get("key"):
                sub += " · " + _("with widget key")
            r = Adw.ActionRow(title=GLib.markup_escape_text(k.get("name") or ""), subtitle=sub)
            r.add_suffix(_btn(_("Sign out"), "destructive-action", lambda k=k: self._drop(k)))
            self._add(r)
        more = Adw.ExpanderRow(title=_("More keys") + (f" ({len(keys)})" if keys else ""),
                               subtitle=_("Created manually, e.g. for shortcuts or scripts"))
        more.set_expanded(bool(self.fresh))
        if not keys:
            more.add_row(Adw.ActionRow(title=_("None.")))
        for k in keys:
            used = _("last used {date}").format(date=date_short(k["last_used"])) if k.get("last_used") else _("never used")
            r = Adw.ActionRow(title=GLib.markup_escape_text(k.get("name") or ""),
                              subtitle=GLib.markup_escape_text(f"{k.get('prefix') or ''}… · ") + _("created {date}").format(date=date_short(k.get("created"))) + f" · {used}")
            r.add_suffix(_btn(_("Revoke"), "destructive-action", lambda k=k: self._revoke(k)))
            more.add_row(r)
        name = Adw.EntryRow(title=_("Name, e.g. “Shortcuts”"), show_apply_button=True)

        def create(row):
            def fin(d):
                self.fresh = d
                self.load()
            bg(client.json, "POST", "api/settings/tokens", {"name": row.get_text().strip()}, done=fin, error=lambda e: None)
        name.connect("apply", create)
        more.add_row(name)
        if self.fresh:
            f = self.fresh
            r = Adw.ActionRow(title="🔑 " + _("New key for “{name}” – visible only now!").format(name=GLib.markup_escape_text(f.get("name") or "")),
                              subtitle=f.get("token"))
            r.set_subtitle_selectable(True)
            r.add_suffix(_btn(_("Copy"), cb=lambda: self.dlg.win.copy_text(f.get("token"))))
            more.add_row(r)
            self.fresh = None
        self._add(more)

    def _add(self, w):
        self.add(w)
        self.rows.append(w)

    def _ask(self, heading, body, label, cb):
        d = Adw.AlertDialog(heading=heading, body=body)
        d.add_response("cancel", _("Cancel"))
        d.add_response("ok", label)
        d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)
        d.connect("response", lambda dd, r: r == "ok" and cb())
        d.present(self.dlg)

    def _revoke(self, k):
        self._ask(_("Revoke key?"), _("Whoever uses it can no longer save anything."), _("Revoke"),
                  lambda: bg(client.request, "DELETE", f"api/settings/tokens/{k['id']}", done=lambda _: self.load()))

    def _drop(self, k):
        self._ask(_("Sign out this device?"), _("The app has to sign in again; widget and sharing lose access."), _("Sign out"),
                  lambda: bg(client.request, "DELETE", f"api/settings/devices/{k['id']}", done=lambda _: self.load()))
