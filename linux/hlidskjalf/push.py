"""Mitteilungen (Push, Server ab 1.7) fuer den Linux-Desktop.

Begruendung: Auf dem Linux-Desktop gibt es keinen System-Push-Dienst wie APNs/FCM. Deshalb zwei Wege:
1. Solange Saga laeuft, kommen Benachrichtigungen ueber den Live-Kanal und erscheinen als GNOME-Mitteilung, wenn das
   Fenster nicht im Vordergrund ist (notifications.show_desktop) - ohne Server-Abo, ohne Dritte.
2. Damit Mitteilungen auch bei geschlossenem Saga ankommen: UnifiedPush ueber D-Bus (Spezifikation DBUS 0.3,
   org.unifiedpush.Distributor2, Rueckfall Distributor1). Ein Verteiler (z. B. KUnifiedPush mit ntfy) liefert eine
   Adresse; die meldet die App beim Server an (Typ "unifiedpush", ohne Web-Push-Schluessel: der Server schickt dann
   nie Namen). Der Verteiler weckt Saga per D-Bus-Aktivierung (data/io.github.yggdrasil_sh.Saga.service,
   --gapplication-service) und Saga zeigt die Mitteilung. Mitteilungen mit gleichem "tag" ersetzen sich (gleiche ID
   wie bei Weg 1 - keine doppelten Meldungen).
Ohne Verteiler ist der Schalter ausgegraut (Hinweis), Weg 1 gilt trotzdem. Kein Google, keine weiteren Bibliotheken."""
import json
import uuid

from gi.repository import Adw, Gio, GLib, GObject, Gtk

from . import APP_ID, config
from .api import APIError, client
from .config import settings
from .i18n import _, pgettext
from .util import bg, rel_time

OBJ = "/org/unifiedpush/Connector"
DIST_OBJ = "/org/unifiedpush/Distributor"
DIST_PREFIX = "org.unifiedpush.Distributor."
_XML = """<node>
  <interface name="org.unifiedpush.Connector2">
    <method name="Message"><arg type="a{sv}" name="args" direction="in"/><arg type="a{sv}" name="ret" direction="out"/></method>
    <method name="NewEndpoint"><arg type="a{sv}" name="args" direction="in"/><arg type="a{sv}" name="ret" direction="out"/></method>
    <method name="Unregistered"><arg type="a{sv}" name="args" direction="in"/><arg type="a{sv}" name="ret" direction="out"/></method>
  </interface>
  <interface name="org.unifiedpush.Connector1">
    <method name="Message"><arg type="s" name="token" direction="in"/><arg type="ay" name="message" direction="in"/>
      <arg type="s" name="messageIdentifier" direction="in"/></method>
    <method name="NewEndpoint"><arg type="s" name="token" direction="in"/><arg type="s" name="endpoint" direction="in"/></method>
    <method name="Unregistered"><arg type="s" name="token" direction="in"/></method>
  </interface>
</node>"""


def _state():
    """je Server: {token, distributor, endpoint, sub} (nur Kennungen, keine Geheimnisse)"""
    d = settings.get("unifiedpush") or {}
    return dict(d.get(config.origin(settings.server)) or {}) if isinstance(d, dict) else {}


def _save_state(st):
    d = dict(settings.get("unifiedpush") or {})
    srv = config.origin(settings.server)
    if st:
        d[srv] = st
    else:
        d.pop(srv, None)
    settings.set("unifiedpush", d)


class UnifiedPush(GObject.Object):
    __gsignals__ = {"changed": (GObject.SignalFlags.RUN_FIRST, None, ())}

    def __init__(self):
        super().__init__()
        self.app = None
        self.conn = None
        self.regs = []
        self.last_error = None
        self.busy = False

    # --- D-Bus -------------------------------------------------------------------------------------------------
    def export(self, app):
        """Connector-Objekt auf der Sitzungsverbindung der App anmelden (in do_startup)"""
        self.app = app
        conn = app.get_dbus_connection()
        if conn is None or self.regs:
            return
        self.conn = conn
        info = Gio.DBusNodeInfo.new_for_xml(_XML)
        for iface in info.interfaces:
            try:
                self.regs.append(conn.register_object(OBJ, iface, self._call, None, None))
            except GLib.Error:
                pass

    def _call(self, conn, sender, path, iface, method, params, inv):
        try:
            if iface.endswith("Connector2"):
                a = params.unpack()[0] if params.n_children() else {}
                token = a.get("token") or ""
                if method == "Message":
                    self.message(token, bytes(a.get("message") or b""))
                    out = {"id": GLib.Variant("s", a["id"])} if a.get("id") else {}
                elif method == "NewEndpoint":
                    self.new_endpoint(token, a.get("endpoint") or "")
                    out = {}
                else:
                    self.unregistered(token)
                    out = {}
                inv.return_value(GLib.Variant("(a{sv})", (out,)))
                return
            p = params.unpack()
            if method == "Message":
                self.message(p[0], bytes(p[1]))
            elif method == "NewEndpoint":
                self.new_endpoint(p[0], p[1])
            else:
                self.unregistered(p[0])
            inv.return_value(None)
        except Exception as e:  # noqa: BLE001
            inv.return_dbus_error("org.unifiedpush.Error", str(e))

    def distributors(self, done):
        """Verteiler auf dem Sitzungs-Bus (laufend oder per D-Bus startbar) -> done([namen])"""
        conn = self.conn or Gio.bus_get_sync(Gio.BusType.SESSION, None)
        found = set()
        left = {"n": 2}

        def got(c, res, _u=None):
            try:
                names = c.call_finish(res).unpack()[0]
                found.update(n for n in names if n.startswith(DIST_PREFIX))
            except GLib.Error:
                pass
            left["n"] -= 1
            if left["n"] == 0:
                done(sorted(found))
        for m in ("ListNames", "ListActivatableNames"):
            conn.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", m, None,
                      GLib.VariantType.new("(as)"), Gio.DBusCallFlags.NONE, 3000, None, got)

    def register(self, distributor, vapid=None, done=None):
        """Bei einem Verteiler anmelden; die Adresse kommt spaeter ueber NewEndpoint. done(ok, grund)"""
        conn = self.conn or Gio.bus_get_sync(Gio.BusType.SESSION, None)
        st = _state()
        token = st.get("token") or uuid.uuid4().hex
        st.update(token=token, distributor=distributor)
        _save_state(st)
        a = {"service": GLib.Variant("s", APP_ID), "token": GLib.Variant("s", token), "description": GLib.Variant("s", "Saga")}
        if vapid:
            a["vapid"] = GLib.Variant("s", vapid)

        def v2(c, res, _u=None):
            try:
                out = c.call_finish(res).unpack()[0]
                ok = out.get("success") == "REGISTRATION_SUCCEEDED"
                if done:
                    done(ok, out.get("reason") or "")
            except GLib.Error as e:
                if "UnknownMethod" in str(e) or "UnknownInterface" in str(e):
                    conn.call(distributor, DIST_OBJ, "org.unifiedpush.Distributor1", "Register",
                              GLib.Variant("(sss)", (APP_ID, token, "Saga")), GLib.VariantType.new("(ss)"),
                              Gio.DBusCallFlags.NONE, 10000, None, v1)
                elif done:
                    done(False, e.message)

        def v1(c, res, _u=None):
            try:
                r, why = c.call_finish(res).unpack()
                if done:
                    done(r in ("REGISTRATION_SUCCEEDED", "NEW_ENDPOINT"), why)
            except GLib.Error as e:
                if done:
                    done(False, e.message)
        conn.call(distributor, DIST_OBJ, "org.unifiedpush.Distributor2", "Register", GLib.Variant("(a{sv})", (a,)),
                  GLib.VariantType.new("(a{sv})"), Gio.DBusCallFlags.NONE, 10000, None, v2)

    def unregister(self):
        st = _state()
        conn = self.conn
        if conn is not None and st.get("distributor") and st.get("token"):
            conn.call(st["distributor"], DIST_OBJ, "org.unifiedpush.Distributor2", "Unregister",
                      GLib.Variant("(a{sv})", ({"token": GLib.Variant("s", st["token"])},)), None, Gio.DBusCallFlags.NONE, 5000, None,
                      lambda c, r, u=None: _finish_quiet(c, r))
        if st.get("sub"):
            bg(client.request, "DELETE", f"api/push/subscriptions/{st['sub']}", error=lambda e: None)
        _save_state({})
        self.emit("changed")

    # --- Rueckrufe vom Verteiler ------------------------------------------------------------------------------------
    def new_endpoint(self, token, endpoint):
        st = _state()
        if not token or token != st.get("token") or not endpoint:
            return
        if endpoint == st.get("endpoint") and st.get("sub"):
            return
        old = st.get("sub")
        st["endpoint"] = endpoint
        _save_state(st)
        body = {"type": "unifiedpush", "endpoint": endpoint, "name": config.device_name()}

        def fin(j):
            st2 = _state()
            if st2.get("token") == token:
                st2["sub"] = (j or {}).get("id")
                _save_state(st2)
            self.last_error = None
            self.emit("changed")

        def err(e):
            if isinstance(e, APIError) and 400 <= e.code < 500 and _state().get("token") == token:
                # Server lehnt die Adresse ab: nicht "an" bleiben (Schalter zeigte an, kein Geraet am Server) -
                # beim Verteiler abmelden, der Fehler steht unter dem Schalter (BUG-078)
                self.unregister()
            self.last_error = str(e)
            self.emit("changed")
        if old:
            bg(client.request, "DELETE", f"api/push/subscriptions/{old}", error=lambda e: None)
        bg(client.json, "POST", "api/push/subscriptions", body, done=fin, error=err)

    def unregistered(self, token):
        st = _state()
        if token and token == st.get("token"):
            if st.get("sub"):
                bg(client.request, "DELETE", f"api/push/subscriptions/{st['sub']}", error=lambda e: None)
            _save_state({})
            self.emit("changed")

    def message(self, token, raw):
        """Mitteilung vom Server (Klartext-JSON ohne Namen bzw. Inhalte): {title, body, url, tag, kind, ts}"""
        if not token or token != _state().get("token"):
            return
        try:
            d = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return
        if not isinstance(d, dict):
            return
        app = self.app or Gio.Application.get_default()
        win = getattr(app, "win", None)
        if win is not None and win.is_active():
            from .live import live
            if live.connected:                                       # sichtbar und live verbunden: steht schon in der Glocke
                return
        if d.get("kind") in ("call", "call.group"):                  # Anruf (2.4 auch Gruppe): klingelt ueber calls, kein Doppel
            from .calls import calls
            if calls.on_push(d):
                return
        n = Gio.Notification.new(str(d.get("title") or "Saga")[:200])
        if d.get("body"):
            n.set_body(str(d["body"])[:500])
        url = d.get("url")
        if isinstance(url, str) and url.startswith("/"):
            n.set_default_action_and_target("app.open-url", GLib.Variant("s", url[:300]))
        tag = str(d.get("tag") or "") or None
        if app is not None:
            app.hold()                                           # im Dienst-Modus: Zeitgeber fuers Beenden neu starten
            try:
                app.send_notification(tag, n)
            finally:
                app.release()


def _finish_quiet(c, r):
    try:
        c.call_finish(r)
    except GLib.Error:
        pass


up = UnifiedPush()


def subscribed():
    st = _state()
    return bool(st.get("token") and st.get("distributor"))


# --- Einstellungen -----------------------------------------------------------------------------------------------
CATS = [("share", lambda: _("Sharing: someone shares a page with me or changes my role")),
        ("comment", lambda: _("Comments and replies")),
        ("chat", lambda: _("Chat messages")),
        ("publish", lambda: _("Publishing: an admin withdraws something")),
        ("call", lambda: _("Calls: ringing, missed calls")),                # Server ab 2.1 (sonst fehlt "call" in cats)
        ("admin", lambda: _("Admin: backup failed")),                       # Server ab 2.3, nur Admin-Konten
        ("tasks", lambda: _("Tasks due today or overdue"))]                 # 2.5 (taskdue1): taegliche Zusammenfassung


class PushPage(Adw.PreferencesPage):
    """Einstellungen -> Mitteilungen"""

    def __init__(self, dlg):
        from .notifications import bell_icon
        super().__init__(title=pgettext("settings tab", "Push"), icon_name=bell_icon())     # kurz: Reiter passen auch schmal
        self.dlg = dlg
        self.data = None
        self.dists = None
        self.groups = []
        self._loading = False
        self.set_description(_("Push notifications to your phone or computer – even when Saga is closed. Content of pages, comments and "
                               "chats is never included."))
        self.h = up.connect("changed", lambda *_: self.load())
        self.connect("unrealize", lambda *_: self._drop())
        self.connect("realize", lambda *_: self._watch())        # Unterseite erneut geoeffnet (Einstellungen 2.5)
        self.load()
        up.distributors(self._dists)

    def _watch(self):
        if not self.h:
            self.h = up.connect("changed", lambda *_: self.load())
            self.load()

    def _drop(self):
        if self.h:
            up.disconnect(self.h)
            self.h = 0

    def _dists(self, lst):
        self.dists = lst
        self.fill()

    def load(self):
        if self._loading:
            return
        self._loading = True

        def fin(j):
            self._loading = False
            self.data = j if isinstance(j, dict) else {}
            self.fill()

        def err(e):
            self._loading = False
            self.data = {"_error": e}
            self.fill()
        bg(client.get, "api/push", done=fin, error=err)

    def _clear(self):
        for g in self.groups:
            self.remove(g)
        self.groups = []

    def _add(self, g):
        self.add(g)
        self.groups.append(g)

    def fill(self):
        self._clear()
        d = self.data
        if d is None:
            return
        if "_error" in d:
            e = d["_error"]
            g = Adw.PreferencesGroup()
            txt = _("This server doesn't support push notifications yet.") if isinstance(e, APIError) and e.code == 404 else \
                _("Not loaded: {err}").format(err=e)
            g.add(Adw.ActionRow(title=GLib.markup_escape_text(txt)))
            self._add(g)
            return
        srv = d.get("server") or {}
        st = d.get("settings") or {}
        # Dieses Geraet
        g = Adw.PreferencesGroup(title=_("This device"),
                                 description=_("While Saga is open, notifications appear directly (live connection). For push "
                                               "notifications while Saga is closed, Saga uses UnifiedPush – e.g. KUnifiedPush with ntfy."))
        sw = Adw.SwitchRow(title=_("Push notifications on this device"))
        on = subscribed()
        server_on = bool(srv.get("enabled"))
        sw.set_active(on and server_on and bool(srv.get("unifiedpush")))   # Schalter und Zustandstext widersprechen sich nie
        if not server_on or not srv.get("unifiedpush"):
            sw.set_sensitive(False)
            sw.set_subtitle(_("Push notifications are turned off on this server."))
        elif self.dists is not None and not self.dists and not on:
            sw.set_sensitive(False)
            sw.set_subtitle(_("No UnifiedPush distributor found on this computer. Install one (e.g. KUnifiedPush with ntfy) – until "
                              "then notifications only come while Saga is open."))
        elif up.last_error:
            sw.set_subtitle(GLib.markup_escape_text(up.last_error))
        elif on and not _state().get("sub"):
            sw.set_subtitle(_("One moment …"))
        elif on:
            sw.set_subtitle(_("✓ On – this device receives push notifications."))
        sw.connect("notify::active", lambda r, _p: self._toggle(r.get_active(), srv))
        g.add(sw)
        mine = next((x for x in d.get("devices") or [] if x.get("this_device")), None) or \
            next((x for x in d.get("devices") or [] if x.get("id") == _state().get("sub")), None)
        if mine:
            t = Adw.ActionRow(title=_("Test"), subtitle=_("Send a test notification to this device"))
            t.add_suffix(self._test_btn(mine, t))
            g.add(t)
        self._add(g)
        # Worueber? (gilt fuer alle Geraete des Kontos) - ausgegraut, solange Mitteilungen aus oder nirgends eingerichtet sind
        any_dev = any(isinstance(x, dict) for x in d.get("devices") or [])
        usable = server_on and (any_dev or (on and srv.get("unifiedpush")))
        g2 = Adw.PreferencesGroup(title=_("About what?"))
        if not usable:
            g2.set_description(_("Push notifications are turned off on this server.") if not server_on else
                               _("Turn on push notifications on a device first."))
        cats = st.get("cats") or {}
        shown = d.get("categories") if isinstance(d.get("categories"), list) else None   # Server ab 2.3: "admin" nur fuer Admins
        for key, text in CATS:
            if key not in cats or (shown is not None and key not in shown):
                continue
            r = Adw.SwitchRow(title=text(), active=bool(cats.get(key)))
            r.connect("notify::active", lambda rr, _p, key=key: self._put({"cats": {key: rr.get_active()}}))
            g2.add(r)
        nm = Adw.SwitchRow(title=_("Show names (e.g. “Jonas shared a page with you”)"), active=bool(st.get("names")),
                           subtitle=_("Not for UnifiedPush without encryption and ntfy – there the server never sends names."))
        nm.connect("notify::active", lambda rr, _p: self._put({"names": rr.get_active()}))
        g2.add(nm)
        g2.set_sensitive(usable)
        self._add(g2)
        # Ruhezeiten
        q = st.get("quiet") or {}
        g3 = Adw.PreferencesGroup(title=_("Quiet hours"))
        qs = Adw.SwitchRow(title=_("Quiet hours"), subtitle=_("No push notifications during this time (the bell in Saga shows everything)"),
                           active=bool(q.get("on")))
        g3.add(qs)
        rows = {}
        for key, title in (("from", _("Quiet hours from")), ("to", _("Quiet hours until"))):
            r = Adw.EntryRow(title=title, text=q.get(key) or ("22:00" if key == "from" else "07:00"), show_apply_button=True)
            r.connect("apply", lambda rr, key=key: self._time(rr, key))
            r.set_sensitive(bool(q.get("on")))
            g3.add(r)
            rows[key] = r

        def quiet(rr, _p):
            for x in rows.values():
                x.set_sensitive(rr.get_active())
            self._put({"quiet": {"on": rr.get_active(), "from": rows["from"].get_text().strip(), "to": rows["to"].get_text().strip(),
                                 "tz": local_tz()}})
        qs.connect("notify::active", quiet)
        g3.set_sensitive(usable)
        self._add(g3)
        # Geraete
        g4 = Adw.PreferencesGroup(title=_("Devices"))
        devs = [x for x in d.get("devices") or [] if isinstance(x, dict)]
        if not devs:
            g4.set_description(_("No device yet. Turn on push notifications in this browser, add ntfy or sign in with an app."))
        for x in devs:
            g4.add(self._dev_row(x, x is mine))
        self._add(g4)

    def _dev_row(self, x, this):
        sub = []
        if this:
            sub.append(_("this device"))
        for k in ("where", "app"):
            if x.get(k):
                sub.append(str(x[k]))
        if x.get("last_error"):
            sub.append(_("Last error: {err}").format(err=x["last_error"]))
        elif x.get("last_ok"):
            sub.append(_("Last delivered {when}").format(when=rel_time(x["last_ok"])))
        else:
            sub.append(_("Nothing delivered yet"))
        if x.get("encrypted") is False:
            sub.append(_("without names (server sees the text)"))
        r = Adw.ActionRow(title=GLib.markup_escape_text(x.get("name") or x.get("type") or "?"),
                          subtitle=GLib.markup_escape_text(" · ".join(sub)))
        r.add_suffix(self._test_btn(x, r))
        rm = Gtk.Button(icon_name="user-trash-symbolic", tooltip_text=_("Remove"), valign=Gtk.Align.CENTER)
        rm.add_css_class("flat")
        rm.update_property([Gtk.AccessibleProperty.LABEL], [_("Remove")])
        rm.connect("clicked", lambda *_: self._remove(x, this))
        r.add_suffix(rm)
        return r

    def _test_btn(self, x, row):
        b = Gtk.Button(label=_("Test"), valign=Gtk.Align.CENTER, tooltip_text=_("Send a test notification to this device"))

        def go(*_a):
            row.set_subtitle(_("Sending …"))

            def fin(j):
                j = j or {}
                row.set_subtitle(_("✓ Delivered") if j.get("result") == "ok" else
                                 GLib.markup_escape_text(_("Not delivered: {err}").format(err=j.get("error") or j.get("result") or "?")))
            bg(client.json, "POST", f"api/push/subscriptions/{x['id']}/test", {}, done=fin,
               error=lambda e: row.set_subtitle(GLib.markup_escape_text(_("Not delivered: {err}").format(err=e))))
        b.connect("clicked", go)
        return b

    def _remove(self, x, this):
        from .publish import confirm

        def go():
            if this and subscribed():
                up.unregister()
                return
            bg(client.request, "DELETE", f"api/push/subscriptions/{x['id']}", done=lambda _r: self.load(),
               error=lambda e: self.dlg.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e)))))
        confirm(self.dlg, _("Remove device?"), _("“{name}” will no longer receive push notifications.").format(name=x.get("name") or "?"),
                _("Remove"), go)

    def _toggle(self, on, srv):
        if on == subscribed():
            return
        if not on:
            up.unregister()
            return
        dists = self.dists or []
        if not dists:
            self.fill()
            return

        def done(ok, why):
            if not ok:
                up.last_error = _("Not allowed.") + (f" ({why})" if why else "")
                _save_state({})
            self.load()
        up.last_error = None
        up.register(dists[0], srv.get("vapid_public"), done)
        self.fill()

    def _put(self, body):
        bg(client.json, "PUT", "api/push/settings", body, done=lambda j: setattr(self, "data", j) if isinstance(j, dict) else None,
           error=lambda e: self.dlg.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e)))))

    def _time(self, row, key):
        t = row.get_text().strip()
        import re
        if not re.fullmatch(r"([01]?\d|2[0-3]):[0-5]\d", t):
            self.dlg.add_toast(Adw.Toast(title=_("Please enter a time as HH:MM.")))
            return
        if len(t) == 4:
            t = "0" + t
            row.set_text(t)
        self._put({"quiet": {key: t, "tz": local_tz()}})


def local_tz():
    try:
        tz = GLib.TimeZone.new_local().get_identifier()
    except (AttributeError, TypeError):
        tz = None
    if not tz or "/" not in tz:
        try:
            import os
            p = os.path.realpath("/etc/localtime")
            if "zoneinfo/" in p:
                tz = p.split("zoneinfo/", 1)[1]
        except OSError:
            pass
    return tz or "UTC"
