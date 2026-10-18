"""Benachrichtigungen (Server ab 1.4, "Glocke"): Liste mit Ungelesen-Zahl, live ueber den Live-Kanal, als gelesen
markieren, Einstellungen je Art (Einstellungen -> Konto). Solange Saga offen, aber nicht im Vordergrund ist, erscheinen
neue Benachrichtigungen zusaetzlich als Desktop-Mitteilung (GNOME), ohne Inhalte von Seiten, Kommentaren oder Chats.
Begriffe: "Benachrichtigungen" = Glocke in Saga, "Mitteilungen" = Push (push.py)."""
import re

from gi.repository import Adw, Gio, GLib, GObject, Gtk, Pango

from .api import APIError, client
from .config import settings
from .i18n import _, ngettext, pgettext
from .util import bg, rel_time, watch_obj


def role_label(r):
    return {"view": pgettext("role", "Can view"), "comment": pgettext("role", "Can comment"),
            "edit": pgettext("role", "Can edit")}.get(r or "", r or "")


def kind_label(kind):
    """Schalter in den Einstellungen (Wortlaut wie im Web)"""
    return {"share.added": _("Someone shares a page with me"),
            "share.role": _("My role on a shared page changes"),
            "comment.new": _("New comments on my pages"),
            "comment.reply": _("Replies in threads I'm part of"),
            "comment.mention": _("Someone mentions me"),
            "publish.withdrawn": _("An admin withdraws one of my published pages"),
            "chat.message": _("New chat messages (without content; never for muted chats)"),
            "call.missed": _("Missed calls")}.get(kind, kind)


def item_text(it):
    """Satz zur Benachrichtigung in der Sprache der App (Glossar); unbekannte Arten: Text des Servers"""
    k = it.get("kind") or ""
    actor = it.get("actor") if isinstance(it.get("actor"), dict) else None
    name = (actor or {}).get("name") or _("Deleted account")
    n = int(it.get("count") or 1)
    if k == "share.added":
        return _("{name} shared a page with you ({role})").format(name=name, role=role_label(it.get("role")))
    if k == "share.role":
        return _("{name} changed your role: {role}").format(name=name, role=role_label(it.get("role")))
    if k == "comment.new":
        if n <= 1:
            return _("{name} commented on your page").format(name=name)
        return ngettext("{n} new comment on your page", "{n} new comments on your page", n).format(n=n)
    if k == "comment.reply":
        if n <= 1:
            return _("{name} replied in a thread").format(name=name)
        return ngettext("{n} new reply in a thread", "{n} new replies in a thread", n).format(n=n)
    if k == "comment.mention":
        return _("{name} mentioned you").format(name=name)
    if k == "publish.withdrawn":
        return _("An admin withdrew your published page")
    if k == "chat.message":
        if n <= 1:
            return _("New message from {name}").format(name=name)
        return ngettext("{n} new message", "{n} new messages", n).format(n=n)
    if k == "call.missed":
        return _("Missed call from {name}").format(name=name)
    return it.get("text") or ""


def item_title(it):
    if it.get("access") is False:
        return _("No access anymore")
    if it.get("kind") in ("chat.message", "call.missed"):
        return it.get("title") or _("Chat")
    note = it.get("note") if isinstance(it.get("note"), dict) else None
    if note:
        t = note.get("title") or _("Untitled")
        icon = note.get("icon") or ""
        if icon and not icon.startswith("/"):
            t = icon + " " + t
        if note.get("host"):                                   # Seite einer anderen Saga-Instanz
            t += " · " + note["host"]
        return t
    return it.get("title") or ""


def item_tag(it):
    """Gruppierung/Ersetzen wie bei den Mitteilungen: note-<id> | thread-<id> | chat-<id>"""
    ch = it.get("chat") if isinstance(it.get("chat"), dict) else None
    if ch and ch.get("id") is not None:
        return f"chat-{ch['id']}"
    if it.get("thread_id") is not None:
        return f"thread-{it['thread_id']}"
    note = it.get("note") if isinstance(it.get("note"), dict) else None
    if note and note.get("id") is not None:
        return f"note-{note['id']}"
    return f"n-{it.get('id')}"


def parse_url(url):
    """'/notes#12:block' -> ('page', 12, 'block'); '/notes#-2049' -> ('page', -2049, None); '/chats#c5' bzw. '/chats#5' ->
    ('chat', 5, None); sonst None"""
    m = re.fullmatch(r"/notes#(-?\d+)(?::(.+))?", url or "")
    if m:
        return "page", int(m.group(1)), m.group(2)
    m = re.fullmatch(r"/chats#c?(\d+)", url or "")
    if m:
        return "chat", int(m.group(1)), None
    if url in ("/chats", "/chats#"):
        return "chats", None, None
    return None


class Notifications(GObject.Object):
    """Liste + Zaehler (Haupt-Thread). Laden: GET /api/notifications; live: Ereignis "notification"."""
    __gsignals__ = {"changed": (GObject.SignalFlags.RUN_FIRST, None, ())}

    def __init__(self):
        super().__init__()
        self.reset()

    def reset(self):
        self.items, self.unread, self.more, self.next = [], 0, False, None
        self.supported = None
        self.loaded = False
        self._loading = False

    def load(self, older=False, done=None):
        if self._loading or self.supported is False:
            return
        self._loading = True
        path = "api/notifications?limit=30" + (f"&before={self.next}" if older and self.next else "")

        def fin(j):
            self._loading = False
            j = j if isinstance(j, dict) else {}
            self.supported = True
            lst = [x for x in j.get("items") or [] if isinstance(x, dict)]
            if older:
                have = {x.get("id") for x in self.items}
                self.items += [x for x in lst if x.get("id") not in have]
            else:
                self.items = lst
            self.unread = int(j.get("unread") or 0)
            self.more, self.next = bool(j.get("more")), j.get("next")
            self.loaded = True
            self.emit("changed")
            if done:
                done()

        def err(e):
            self._loading = False
            if isinstance(e, APIError) and e.code == 404:
                self.supported = False
                self.emit("changed")
        bg(client.get, path, done=fin, error=err)

    def count(self):
        """nur die Zahl (limit=0) - z. B. nach dem Aufwachen"""
        if self.supported is False:
            return

        def fin(j):
            if isinstance(j, dict):
                self.supported = True
                n = int(j.get("unread") or 0)
                if n != self.unread:
                    self.unread = n
                    self.load()
        bg(client.get, "api/notifications?limit=0", done=fin,
           error=lambda e: setattr(self, "supported", False) if isinstance(e, APIError) and e.code == 404 else None)

    def mark_read(self, ids=None, everything=False):
        body = {"all": True} if everything else {"ids": list(ids or [])}
        if not everything and not body["ids"]:
            return
        for it in self.items:
            if everything or it.get("id") in body["ids"]:
                it["read"] = True
        if everything:
            self.unread = 0
        else:
            self.unread = max(0, self.unread - len(body["ids"]))
        self.emit("changed")

        def fin(j):
            if isinstance(j, dict) and "unread" in j:
                self.unread = int(j.get("unread") or 0)
                self.emit("changed")
        bg(client.json, "POST", "api/notifications/read", body, done=fin, error=lambda e: None)

    def drop_chat(self, cid):
        """Chat geloescht/geleert (2.1): seine Benachrichtigungen aus der Glocke nehmen (der Server hat sie entfernt
        bzw. gelesen) und die Zahl neu holen"""
        keep = [x for x in self.items if not (isinstance(x.get("chat"), dict) and x["chat"].get("id") == cid)]
        if len(keep) == len(self.items):
            return
        self.items = keep
        self.emit("changed")
        self.count()

    def on_event(self, data):
        """Live: {item, unread, replaces} (neu) bzw. {unread, read: [ids] | "all"} (anderswo gelesen) -> neue Benachrichtigung oder None"""
        if not isinstance(data, dict):
            return None
        self.supported = True
        new = None
        it = data.get("item")
        if isinstance(it, dict):
            rep = data.get("replaces")
            self.items = [x for x in self.items if x.get("id") not in (rep, it.get("id"))]
            self.items.insert(0, it)
            new = it
        rd = data.get("read")
        if rd == "all":
            for x in self.items:
                x["read"] = True
        elif isinstance(rd, list):
            s = set(rd)
            for x in self.items:
                if x.get("id") in s:
                    x["read"] = True
        if "unread" in data:
            self.unread = int(data.get("unread") or 0)
        self.emit("changed")
        return new


notifications = Notifications()


# --- Desktop-Mitteilung (Saga offen, aber nicht im Vordergrund) ---------------------------------------------------
def desktop_enabled():
    return settings.get("desktop_notify", True) is not False


def show_desktop(app, it):
    """Neue Benachrichtigung als GNOME-Mitteilung: Satz + Titel der Seite bzw. des Chats (wie in der Glocke), Klick oeffnet sie"""
    if app is None or not desktop_enabled() or not isinstance(it, dict):
        return False
    n = Gio.Notification.new(item_text(it) or "Saga")
    body = item_title(it) if it.get("kind") not in ("chat.message", "call.missed") else ""
    if body:
        n.set_body(body)
    url = it.get("url") or ("/chats#c%s" % it["chat"]["id"] if isinstance(it.get("chat"), dict) else "")
    if url and it.get("access") is not False:
        n.set_default_action_and_target("app.open-url", GLib.Variant("s", url))
    n.set_category({"chat.message": "im.received", "call.missed": "call.unanswered"}.get(it.get("kind"), "presence"))
    try:
        app.send_notification(item_tag(it), n)
    except GLib.Error:
        return False
    return True


# --- Oberflaeche: Glocke mit Zahl + Liste ----------------------------------------------------------------------
def bell_icon():
    from .publish import _icon
    return _icon("saga-bell-symbolic", "preferences-system-notifications-symbolic", "dialog-information-symbolic")


class BellButton(Gtk.MenuButton):
    """Glocke in der Kopfleiste der Seitenleiste: Zahl der ungelesenen, Klick -> Liste (Popover)"""
    __gtype_name__ = "HKBellButton"

    def __init__(self, win):
        super().__init__()
        self.win = win
        self.add_css_class("flat")
        ov = Gtk.Overlay()
        ov.set_child(Gtk.Image.new_from_icon_name(bell_icon()))
        self.badge = Gtk.Label(halign=Gtk.Align.END, valign=Gtk.Align.START, visible=False, can_target=False)
        self.badge.add_css_class("hk-bell-badge")
        ov.add_overlay(self.badge)
        self.set_child(ov)
        self.pop = Gtk.Popover()
        self.pop.add_css_class("hk-notif-pop")
        self.panel = NotificationList(win, close=self.pop.popdown)
        self.pop.set_child(self.panel)
        self.set_popover(self.pop)
        self.pop.connect("show", lambda *_: (self.panel.fill(), notifications.load()))
        watch_obj(self, notifications, "changed", lambda: (self.update(), self.panel.fill()))
        self.update()

    def update(self):
        n = notifications.unread
        self.set_visible(notifications.supported is not False)
        self.badge.set_label(str(n) if n < 100 else "99+")
        self.badge.set_visible(n > 0)
        tip = _("Notifications") + (" – " + ngettext("{n} unread", "{n} unread", n).format(n=n) if n else "")
        self.set_tooltip_text(tip)
        self.update_property([Gtk.AccessibleProperty.LABEL], [tip])


class NotificationList(Gtk.Box):
    __gtype_name__ = "HKNotificationList"

    def __init__(self, win, close=None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.win, self.close = win, close
        self.set_size_request(360, -1)
        head = Gtk.Box(spacing=6, margin_start=12, margin_end=6, margin_top=6, margin_bottom=6)
        t = Gtk.Label(label=_("Notifications"), xalign=0, hexpand=True)
        t.add_css_class("heading")
        head.append(t)
        self.all_btn = Gtk.Button(label=_("Mark all as read"))
        self.all_btn.add_css_class("flat")
        self.all_btn.connect("clicked", lambda *_: notifications.mark_read(everything=True))
        head.append(self.all_btn)
        gear = Gtk.Button(icon_name="emblem-system-symbolic", tooltip_text=_("Notification settings"))
        gear.add_css_class("flat")
        gear.connect("clicked", lambda *_: (self.close and self.close(), win.settings_dialog(page="account")))
        head.append(gear)
        self.append(head)
        self.append(Gtk.Separator())
        self.sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True, max_content_height=460)
        self.list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.list.add_css_class("hk-notif-list")
        self.list.update_property([Gtk.AccessibleProperty.LABEL], [_("Notifications")])
        self.list.connect("row-activated", lambda lb, row: getattr(row, "run", lambda: None)())
        self.sw.set_child(self.list)
        self.append(self.sw)
        self.fill()

    def fill(self):
        self.list.remove_all()
        self.all_btn.set_sensitive(notifications.unread > 0)
        if not notifications.items:
            st = Adw.StatusPage(icon_name=bell_icon(), title=_("No notifications"),
                                description=_("This is where you'll see when someone shares something with you or comments on your pages."))
            st.add_css_class("compact")
            row = Gtk.ListBoxRow(activatable=False, selectable=False, child=st)
            self.list.append(row)
            return
        for it in notifications.items:
            self.list.append(self._row(it))
        if notifications.more:
            b = Gtk.Button(label=_("Load older"), halign=Gtk.Align.CENTER, margin_top=6, margin_bottom=6)
            b.add_css_class("flat")
            b.connect("clicked", lambda *_: notifications.load(older=True))
            self.list.append(Gtk.ListBoxRow(activatable=False, selectable=False, child=b))

    def _row(self, it):
        row = Gtk.ListBoxRow(activatable=it.get("access") is not False)
        row.add_css_class("hk-notif")
        if not it.get("read"):
            row.add_css_class("hk-notif-unread")
        box = Gtk.Box(spacing=10, margin_start=10, margin_end=10, margin_top=8, margin_bottom=8)
        actor = it.get("actor") if isinstance(it.get("actor"), dict) else None
        av = Adw.Avatar(size=32, text=(actor or {}).get("name") or "Saga", show_initials=actor is not None, valign=Gtk.Align.START)
        if actor is None:
            av.set_icon_name(bell_icon())
        else:
            from . import avatars
            avatars.attach(av, actor.get("avatar"), 32)          # Profilbild (2.1), sonst Initialen
        box.append(av)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True)
        txt = item_text(it)
        t = Gtk.Label(label=txt, xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, max_width_chars=40)
        if not it.get("read"):
            t.add_css_class("heading")
        col.append(t)
        sub = item_title(it)
        if sub:
            s = Gtk.Label(label=sub, xalign=0, ellipsize=Pango.EllipsizeMode.END, max_width_chars=40)
            s.add_css_class("dim-label")
            if it.get("access") is False:
                s.add_css_class("hk-italic")
            col.append(s)
        if it.get("excerpt"):
            # Translators: quoted excerpt of a comment (typographic quotes of your language)
            ex = Gtk.Label(label=_("“{text}”").format(text=it["excerpt"]), xalign=0, wrap=True,
                           lines=2, ellipsize=Pango.EllipsizeMode.END, max_width_chars=40)
            ex.add_css_class("caption")
            ex.add_css_class("hk-notif-excerpt")
            col.append(ex)
        tm = Gtk.Label(label=rel_time(it.get("created")), xalign=0)
        tm.add_css_class("caption")
        tm.add_css_class("dim-label")
        col.append(tm)
        box.append(col)
        if not it.get("read"):
            dot = Gtk.Label(label="●", valign=Gtk.Align.CENTER, tooltip_text=_("Unread"))
            dot.add_css_class("hk-unread-dot")
            box.append(dot)
        row.set_child(box)
        acc = txt + (" – " + sub if sub else "") + ("" if it.get("read") else " – " + _("Unread"))
        row.update_property([Gtk.AccessibleProperty.LABEL], [acc])

        def run():
            if self.close:
                self.close()
            if not it.get("read"):
                notifications.mark_read([it.get("id")])
            self.win.open_notification(it)
        row.run = run
        return row


class NotificationSettingsGroup(Adw.PreferencesGroup):
    """Einstellungen -> Konto -> Benachrichtigungen: ein Schalter je Art (GET/PUT /api/notifications/settings)"""

    def __init__(self, dlg):
        super().__init__(title=_("Notifications"),
                         description=_("Shown in Saga under the bell – instantly while Saga is open."))
        self.dlg = dlg
        self.rows = []
        self._reverting = set()                # Schalter, die gerade nach einem Fehler zurueckgestellt werden
        self.set_visible(False)
        bg(client.get, "api/notifications/settings", done=self.fill, error=lambda e: None)

    def fill(self, j):
        kinds = [k for k in (j or {}).get("kinds") or [] if isinstance(k, dict) and k.get("kind")]
        for r in self.rows:
            self.remove(r)
        self.rows = []
        for k in kinds:
            sw = Adw.SwitchRow(title=kind_label(k["kind"]), active=bool(k.get("on")))
            sw.connect("notify::active", lambda r, _p, kind=k["kind"]: self._set(kind, r))
            self.add(sw)
            self.rows.append(sw)
        dn = Adw.SwitchRow(title=_("Desktop notifications while Saga is open"),
                           subtitle=_("Without page, comment or chat content."), active=desktop_enabled())
        dn.connect("notify::active", lambda r, _p: settings.set("desktop_notify", r.get_active()))
        self.add(dn)
        self.rows.append(dn)
        self.set_visible(bool(kinds))

    def _set(self, kind, row):
        """Thema an/aus; scheitert das Speichern, springt der Schalter zurueck (er zeigt nie einen Stand, den der Server
        nicht hat, BUG-078)"""
        if row in self._reverting:
            return
        on = row.get_active()

        def err(e):
            self._reverting.add(row)
            row.set_active(not on)
            self._reverting.discard(row)
            self.dlg.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e))))
        bg(client.json, "PUT", "api/notifications/settings", {kind: on}, error=err)
