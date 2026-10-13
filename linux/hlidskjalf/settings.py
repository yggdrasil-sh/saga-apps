"""Einstellungen wie in der Mac-App: Server, Darstellung, Immich, Kalender (CalDAV), App-Schluessel, angemeldete Apps."""
from gi.repository import Adw, Gdk, GLib, Gtk

from .api import client
from .config import settings
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
        super().__init__(title="Einstellungen", search_enabled=False)
        self.win = win
        page = Adw.PreferencesPage(title="Allgemein", icon_name="emblem-system-symbolic")
        g = Adw.PreferencesGroup(title="Server")
        self.server = Adw.EntryRow(title="Adresse", text=settings.server, show_apply_button=True)
        placeholder(self.server, "https://saga.example.org")
        self.server.connect("apply", self._apply_server)
        g.add(self.server)
        from . import VERSION
        about = Adw.ActionRow(title="Version", subtitle=f"App: Saga {VERSION} · Server: " + (f"Saga {store.server_version}" if store.server_version else "–"))
        if store.app_too_old:
            about.set_subtitle(about.get_subtitle() + f" · App zu alt (ab {store.min_app}), bitte aktualisieren")
        g.add(about)
        self.status = Adw.ActionRow(title="Verbindung testen")
        self.status.add_suffix(_btn("Testen", cb=self._test))
        g.add(self.status)
        page.add(g)
        g2 = Adw.PreferencesGroup(title="Darstellung")
        ch = Adw.SpinRow.new_with_range(100, 600, 10)
        ch.set_title("Höhe der Titelbilder")
        ch.set_subtitle("Gilt für alle Seiten auf diesem Rechner. Auch per Ziehen am Bild änderbar.")
        ch.set_value(settings.get("cover_height", 200))
        ch.connect("notify::value", lambda r, _: settings.set("cover_height", int(r.get_value())))
        g2.add(ch)
        page.add(g2)
        g3 = Adw.PreferencesGroup()
        out = Adw.ButtonRow(title="Abmelden") if hasattr(Adw, "ButtonRow") else Adw.ActionRow(title="Abmelden", activatable=True)
        out.add_css_class("destructive-action")
        out.connect("activated", lambda *_: self._logout())
        g3.add(out)
        g3.set_description("Löscht die Anmeldung und den Zwischenspeicher auf diesem Gerät.")
        page.add(g3)
        self.add(page)
        acc = Adw.PreferencesPage(title="Konto", icon_name="avatar-default-symbolic")
        acc.add(ImmichGroup(self))
        acc.add(CalendarGroup(self))
        acc.add(AppsGroup(self))
        self.add(acc)

    def _apply_server(self, row):
        u = row.get_text().strip().rstrip("/")
        if u.startswith("https://") and "." in u:
            if u != settings.server:
                client.clear_cookies()                    # Sitzung gehoert zum alten Server
                store.server_version = store.min_app = None
            settings.server = u
            self.add_toast(Adw.Toast(title="Server gespeichert – bitte neu anmelden, falls nötig"))
            store.refresh()
        else:
            self.add_toast(Adw.Toast(title="Bitte eine https-Adresse eingeben"))

    def _test(self):
        self.status.set_subtitle("Prüfe …")

        def fin(j):
            n = len(j.get("events") or []) if isinstance(j, dict) else 0
            self.status.set_subtitle(f"✓ Verbunden – {len(store.pages)} Seiten, heute {n} Termine")
        bg(client.get, store.ev_path(1), done=fin, error=lambda e: self.status.set_subtitle("✗ " + str(e)))

    def _logout(self):
        d = Adw.AlertDialog(heading="Abmelden?", body="Die Anmeldung und der Zwischenspeicher auf diesem Gerät werden gelöscht.")
        d.add_response("cancel", "Abbrechen")
        d.add_response("ok", "Abmelden")
        d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)

        def resp(dd, r):
            if r == "ok":
                self.close()
                store.logout()
        d.connect("response", resp)
        d.present(self)


class ImmichGroup(Adw.PreferencesGroup):
    def __init__(self, dlg):
        super().__init__(title="📷 Immich")
        self.dlg = dlg
        self.rows = []
        self.load()

    def load(self):
        bg(client.get, "api/settings/immich", done=self.fill, error=lambda e: None)

    def fill(self, info):
        _clear_group(self, self.rows)
        if info.get("connected"):
            host = (info.get("url") or "").replace("https://", "").replace("http://", "")
            r = Adw.ActionRow(title="✅ Verbunden" + (f" als {info['name']}" if info.get("name") else ""), subtitle=host)
            r.add_suffix(_btn("Trennen", "destructive-action", lambda: bg(client.request, "DELETE", "api/settings/immich", done=lambda _: self.load())))
            self.add(r)
            self.rows.append(r)
            return
        self.set_description("In Immich unter Kontoeinstellungen → API-Schlüssel einen Schlüssel mit album.read, asset.read, asset.view, "
                             "asset.download und user.read anlegen und hier einfügen.")
        url = Adw.EntryRow(title="Immich-Adresse", text=info.get("suggest") or "")
        key = Adw.PasswordEntryRow(title="API-Schlüssel")
        st = Adw.ActionRow(title="Verbinden")

        def go():
            st.set_subtitle("Prüfe …")
            bg(client.json, "POST", "api/settings/immich", {"url": url.get_text().strip(), "apikey": key.get_text().strip()},
               done=lambda _: self.load(), error=lambda e: st.set_subtitle(str(e)))
        st.add_suffix(_btn("Verbinden", "suggested-action", go))
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
                lb = Gtk.Label(label="nur lesen")
                lb.add_css_class("dim-label")
                r.append(lb)
            self.append(r)

    def dicts(self):
        return [{"href": c.get("href"), "name": c.get("name"), "color": c.get("color") or "#4184e4", "show": c.get("show", True) is not False} for c in self.cals]


class CalendarGroup(Adw.PreferencesGroup):
    def __init__(self, dlg):
        super().__init__(title="📅 Kalender", description="Externe Kalender (CalDAV) wie mailcow oder Nextcloud verbinden. "
                                                         "Tipp: In mailcow ein eigenes App-Passwort nur für „DAV“ anlegen.")
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
            r = Adw.ActionRow(title="Noch kein Kalender verbunden.")
            self._add(r)
        for a in accs:
            self._add(self._account(a))
        add = Adw.ActionRow(title="Kalender verbinden")
        add.add_suffix(_btn("+ Verbinden", cb=lambda: self._new()))
        self._add(add)

    def _add(self, w):
        self.add(w)
        self.rows.append(w)

    def _account(self, a):
        aid = a.get("id")
        host = GLib.Uri.parse(a.get("base_url") or "https://x", GLib.UriFlags.NONE).get_host() if a.get("base_url") else ""
        ex = Adw.ExpanderRow(title=a.get("label") or a.get("username") or "Kalender", subtitle=f"{a.get('username') or ''} · {host} · Passwort gespeichert ✓")
        ce = CalEntries(a.get("calendars") or [])
        holder = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=8, margin_bottom=8, margin_start=12, margin_end=12)
        holder.append(ce)
        st = Gtk.Label(xalign=0, wrap=True)
        st.add_css_class("dim-label")
        bar = Gtk.Box(spacing=6)

        def run(method, path, body, msg):
            st.set_label("…")
            bg(client.json, method, path, body, done=lambda _: (st.set_label(msg), store.emit("events-changed")), error=lambda e: st.set_label(str(e)))
        bar.append(_btn("Auswahl speichern", cb=lambda: run("PATCH", f"api/settings/calendars/{aid}", {"calendars": ce.dicts()}, "✓ Gespeichert")))

        def reread():
            st.set_label("…")

            def fin(d):
                found = d.get("calendars") or []
                by = {c["href"]: c for c in ce.cals}
                merged = [by.get(f["href"]) or dict(f, show=False) for f in found]
                a["calendars"] = merged
                self.fill_again()
                st.set_label(f"{len(found)} Kalender – Auswahl speichern nicht vergessen.")
            bg(client.json, "POST", "api/settings/calendars/test", {"id": aid}, done=fin, error=lambda e: st.set_label(str(e)))
        bar.append(_btn("Neu einlesen", cb=reread))
        pw = Gtk.PasswordEntry(placeholder_text="Neues App-Passwort", show_peek_icon=True, hexpand=True)
        bar.append(_btn("Entfernen", "destructive-action", lambda: self._remove(aid)))
        holder.append(bar)
        pr = Gtk.Box(spacing=6)
        pr.append(pw)
        pr.append(_btn("Passwort prüfen & speichern", cb=lambda: run("PATCH", f"api/settings/calendars/{aid}", {"password": pw.get_text()}, "✓ Neues Passwort gespeichert")))
        holder.append(pr)
        holder.append(st)
        ex.add_row(holder)
        return ex

    def fill_again(self):
        self.load()

    def _remove(self, aid):
        d = Adw.AlertDialog(heading="Kalender-Zugang entfernen?", body="Die Termine im Kalender selbst bleiben erhalten.")
        d.add_response("cancel", "Abbrechen")
        d.add_response("ok", "Entfernen")
        d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)
        d.connect("response", lambda dd, r: r == "ok" and bg(client.request, "DELETE", f"api/settings/calendars/{aid}", done=lambda _: self.load()))
        d.present(self.dlg)

    def _new(self):
        NewCalendar(self).present(self.dlg)


class NewCalendar(Adw.Dialog):
    def __init__(self, group):
        super().__init__(title="Kalender verbinden", content_width=480, content_height=600)
        self.group = group
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        page = Adw.PreferencesPage()
        g = Adw.PreferencesGroup()
        kinds = [("mailcow", "mailcow (SOGo)"), ("nextcloud", "Nextcloud"), ("other", "Andere CalDAV-Adresse")]
        self.kind = Adw.ComboRow(title="Anbieter", model=Gtk.StringList.new([k[1] for k in kinds]))
        allowed = group.allowed
        mc = next((a for a in allowed if a.startswith("mail.")), "mail.example.org")   # Vorgabe aus den erlaubten Servern
        self.url = Adw.EntryRow(title="CalDAV-Adresse", text=f"https://{mc}/SOGo/dav/")

        def kind_changed(r, _):
            k = kinds[r.get_selected()][0]
            nc = next((a for a in allowed if not a.startswith("mail.")), "HOST")
            self.url.set_text(f"https://{mc}/SOGo/dav/" if k == "mailcow" else f"https://{nc}/remote.php/dav/" if k == "nextcloud" else "https://")
        self.kind.connect("notify::selected", kind_changed)
        self.user = Adw.EntryRow(title="Benutzer (z. B. name@example.org)")
        self.pw = Adw.PasswordEntryRow(title="App-Passwort")
        self.label = Adw.EntryRow(title="Bezeichnung (optional, z. B. „Privat“)")
        for r in (self.kind, self.url, self.user, self.pw, self.label):
            g.add(r)
        g.set_description("Erlaubte Server: " + (", ".join(allowed) if allowed else "–"))
        page.add(g)
        g2 = Adw.PreferencesGroup()
        self.box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.msg = Gtk.Label(xalign=0, wrap=True)
        tb = Gtk.Button(label="Verbindung testen", halign=Gtk.Align.START)
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
        self.msg.set_label("Verbinde …")

        def fin(d):
            cals = d.get("calendars") or []
            self.msg.set_label(f"✓ Verbunden – {len(cals)} Kalender gefunden. Auswählen und speichern:")
            while (c := self.cal_holder.get_first_child()):
                self.cal_holder.remove(c)
            self.entries = CalEntries(cals)
            self.cal_holder.append(self.entries)
            sv = Gtk.Button(label="Verbinden & speichern", halign=Gtk.Align.START)
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
        super().__init__(title="📱 Apps &amp; Geräte",
                         description="Angemeldete Apps bleiben angemeldet. Zu jeder gehört ein kleiner Schlüssel für Widget, Teilen und "
                                     "Kurzbefehle. Verlorenes Gerät → abmelden, dann ist beides weg.")
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
            self._add(Adw.ActionRow(title="Keine App angemeldet."))
        for k in devs:
            sub = f"angemeldet {date_short(k.get('created'))} · zuletzt {date_short(k.get('last_used'))}"
            if k.get("key"):
                sub += " · mit Widget-Schlüssel"
            r = Adw.ActionRow(title=k.get("name") or "", subtitle=sub)
            r.add_suffix(_btn("Abmelden", "destructive-action", lambda k=k: self._drop(k)))
            self._add(r)
        more = Adw.ExpanderRow(title="Weitere Schlüssel" + (f" ({len(keys)})" if keys else ""),
                               subtitle="Von Hand erzeugt, z. B. für Kurzbefehle oder Skripte")
        more.set_expanded(bool(self.fresh))
        if not keys:
            more.add_row(Adw.ActionRow(title="Keine."))
        for k in keys:
            used = f"zuletzt {date_short(k['last_used'])}" if k.get("last_used") else "noch nie benutzt"
            r = Adw.ActionRow(title=k.get("name") or "", subtitle=f"{k.get('prefix') or ''}… · erstellt {date_short(k.get('created'))} · {used}")
            r.add_suffix(_btn("Widerrufen", "destructive-action", lambda k=k: self._revoke(k)))
            more.add_row(r)
        name = Adw.EntryRow(title="Name, z. B. „Kurzbefehle“", show_apply_button=True)

        def create(row):
            def fin(d):
                self.fresh = d
                self.load()
            bg(client.json, "POST", "api/settings/tokens", {"name": row.get_text().strip()}, done=fin, error=lambda e: None)
        name.connect("apply", create)
        more.add_row(name)
        if self.fresh:
            f = self.fresh
            r = Adw.ActionRow(title=f"🔑 Neuer Schlüssel für „{f.get('name')}“ – nur jetzt sichtbar!", subtitle=f.get("token"))
            r.set_subtitle_selectable(True)
            r.add_suffix(_btn("Kopieren", cb=lambda: self.dlg.win.copy_text(f.get("token"))))
            more.add_row(r)
            self.fresh = None
        self._add(more)

    def _add(self, w):
        self.add(w)
        self.rows.append(w)

    def _ask(self, heading, body, label, cb):
        d = Adw.AlertDialog(heading=heading, body=body)
        d.add_response("cancel", "Abbrechen")
        d.add_response("ok", label)
        d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)
        d.connect("response", lambda dd, r: r == "ok" and cb())
        d.present(self.dlg)

    def _revoke(self, k):
        self._ask("Schlüssel widerrufen?", "Wer ihn benutzt, kann dann nichts mehr ablegen.", "Widerrufen",
                  lambda: bg(client.request, "DELETE", f"api/settings/tokens/{k['id']}", done=lambda _: self.load()))

    def _drop(self, k):
        self._ask("Dieses Gerät abmelden?", "Die App muss sich neu anmelden; Widget und Teilen verlieren den Zugang.", "Abmelden",
                  lambda: bg(client.request, "DELETE", f"api/settings/devices/{k['id']}", done=lambda _: self.load()))
