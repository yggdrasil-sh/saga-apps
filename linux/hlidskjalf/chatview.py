"""Chats (Server 1.5) - Oberflaeche: Liste und Unterhaltung im Hauptbereich (breit nebeneinander, schmal nacheinander),
Einrichtung mit Wiederherstellungsschluessel, neues Geraet (Kopplung/Wiederherstellung/Neu beginnen), Bestaetigen neuer
Geraete, Sicherheitsnummer (+ QR-Code), Mitglieder und Einstellungen eines Chats, "Chats & Geraete" in den Einstellungen.
Nachrichtentexte werden nie als Markup interpretiert (escape; nur eigene Links fuer Adressen und [[note:ID]])."""
import datetime as _dt
import re

from gi.repository import Adw, Gdk, Gio, GLib, Gtk, Pango

from . import chatcrypto as C
from . import config
from .api import APIError, client
from .blocks import flat_button, menu_popover
from .chat import (NOTE_RX, RETENTIONS, ChatError, SafetyChanged, chat, fmt_recovery, keys_persistent, retention_label, safety_for,
                   safety_qr_text, trust_accept, trust_get, trust_verified)
from .i18n import _, ngettext
from .util import bg, date_short, esc, human_size, rel_time, time_text, weekday_day_text

URL_RX = re.compile(r"https?://[^\s<>\"']+[^\s<>\"'.,;:!?)\]]")


def chat_icon():
    from .publish import _icon
    return _icon("saga-chat-symbolic", "chat-message-new-symbolic", "mail-message-new-symbolic")


def send_icon():
    from .publish import _icon
    return _icon("saga-send-symbolic", "mail-send-symbolic", "go-up-symbolic")


def watch_chat(widget, signal, cb):
    """Signal von chat nur, solange das Widget sichtbar ist"""
    st = {"h": None}

    def on_map(*_):
        if st["h"] is None:
            st["h"] = chat.connect(signal, lambda *a: cb(*a[1:]))

    def on_unmap(*_):
        if st["h"] is not None:
            chat.disconnect(st["h"])
            st["h"] = None
    widget.connect("map", on_map)
    widget.connect("unmap", on_unmap)


def code_lines(code):
    """Kopplungscode in zwei Zeilen (4 x 5 Ziffern passen sonst nicht ins schmale Fenster)"""
    parts = (code or "").split()
    if len(parts) <= 2:
        return code or ""
    half = (len(parts) + 1) // 2
    return " ".join(parts[:half]) + "\n" + " ".join(parts[half:])


def _lbl(text="", css=(), xalign=0, wrap=True, **kw):
    lb = Gtk.Label(label=text, xalign=xalign, wrap=wrap, **kw)
    if wrap:
        lb.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
    for c in css:
        lb.add_css_class(c)
    return lb


def _button(label, css=(), cb=None, **kw):
    b = Gtk.Button(**kw) if label is None else Gtk.Button(label=label, **kw)
    for c in css:
        b.add_css_class(c)
    if cb:
        b.connect("clicked", lambda *_: cb())
    return b


def _toast(win, text):
    if win is not None and text:
        win.toast(text)


def text_markup(text, notes):
    """Nachrichtentext -> Pango-Markup: alles escaped; Adressen und [[note:ID]] als Links (Seite ohne Zugriff: kein Link)"""
    out, pos = [], 0
    rx = re.compile(r"\[\[note:(\d+)\]\]|https?://[^\s<>\"']+[^\s<>\"'.,;:!?)\]]")
    for m in rx.finditer(text):
        out.append(esc(text[pos:m.start()]))
        if m.group(1):
            nid = int(m.group(1))
            n = notes.get(nid)
            if n:
                t = (n.get("icon") + " " if n.get("icon") and not n["icon"].startswith("/") else "📄 ") + (n.get("title") or _("Untitled"))
                out.append(f'<a href="saga-note:{nid}">{esc(t)}</a>')
            elif nid in notes:
                out.append("<i>" + esc(_("Page (no access)")) + "</i>")
            else:
                out.append("📄 …")
        else:
            u = m.group(0)
            out.append(f'<a href="{esc(u)}">{esc(u)}</a>')
        pos = m.end()
    out.append(esc(text[pos:]))
    return "".join(out)


def snippet(m, n=80):
    if m is None:
        return _("Message no longer available")
    if m.get("state") == "undecryptable":
        return _("Can't be decrypted")
    t = NOTE_RX.sub("📄", m.get("text") or "").strip()
    if not t and m.get("files"):
        t = "📎 " + (m["files"][0].get("n") or _("File"))
    t = " ".join(t.split())
    return t if len(t) <= n else t[:n - 1] + "…"


def _day_label(ts):
    d = _dt.datetime.fromtimestamp(float(ts or 0))
    today = _dt.date.today()
    if d.date() == today:
        return _("Today")
    if d.date() == today - _dt.timedelta(days=1):
        return _("Yesterday")
    return weekday_day_text(d, year=d.year != today.year)


def _time(ts):
    return time_text(_dt.datetime.fromtimestamp(float(ts or 0)))


# --- Bildschirm "Chats" ------------------------------------------------------------------------------------------
class ChatsScreen(Gtk.Box):
    """Route ("chats",) = Liste (breit: daneben ein Platzhalter); ("chat", id) = Unterhaltung (breit: Liste links)"""
    __gtype_name__ = "HKChatsScreen"
    NARROW = 720

    def __init__(self, win, cid=None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, vexpand=True, hexpand=True)
        self.win, self.cid = win, cid
        self.conv = None
        self.narrow = False
        self.stack = Gtk.Stack(transition_type=Gtk.StackTransitionType.CROSSFADE, vexpand=True)
        self.append(self.stack)
        self.status_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, vexpand=True)
        self.stack.add_named(self.status_box, "status")
        self.main = Gtk.Box(vexpand=True)
        self.list = ChatList(self)
        self.list.set_size_request(300, -1)
        self.list.set_hexpand(False)                         # breit: feste Liste, die Unterhaltung bekommt den Rest
        self.sep = Gtk.Separator(orientation=Gtk.Orientation.VERTICAL)
        self.slot = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, vexpand=True)
        self.main.append(self.list)
        self.main.append(self.sep)
        self.main.append(self.slot)
        # schmal: nur Liste bzw. nur die Unterhaltung (GNOME: Adw.BreakpointBin, Zurueck fuehrt zur Liste)
        bpb = Adw.BreakpointBin(width_request=320, height_request=300, child=self.main)
        bp = Adw.Breakpoint.new(Adw.BreakpointCondition.parse(f"max-width: {self.NARROW}sp"))
        hide = self.list if cid is not None else self.slot
        bp.add_setter(hide, "visible", False)
        bp.add_setter(self.sep, "visible", False)
        bp.add_setter(self.list, "hexpand", True)
        bp.connect("apply", lambda *_: setattr(self, "narrow", True))
        bp.connect("unapply", lambda *_: setattr(self, "narrow", False))
        bpb.add_breakpoint(bp)
        self.stack.add_named(bpb, "main")
        watch_chat(self, "changed", lambda: self.render())
        watch_chat(self, "chat-changed", lambda cid: cid == self.cid and self._header())
        watch_chat(self, "typing", lambda cid: cid == self.cid and self._header())
        self.connect("unmap", lambda *_: setattr(chat, "open_chat", None))
        self._state = None
        self.render(force=True)
        if chat.status in ("unknown",) or chat.status == "ready" and not chat.chats:
            chat.refresh()

    # --- Aufbau ------------------------------------------------------------------------------------------------
    def render(self, force=False):
        st = chat.status
        if st != "ready":
            if force or self._state != st:
                self._state = st
                self._status(st)
                self.stack.set_visible_child_name("status")
            self._header()
            return
        if self._state != "ready" or force:
            self._state = "ready"
            self.stack.set_visible_child_name("main")
            self._conversation()
        self.list.fill()
        self._header()

    def _conversation(self):
        while (c := self.slot.get_first_child()):
            self.slot.remove(c)
        self.conv = None
        if self.cid is not None:
            self.conv = Conversation(self, self.cid)
            self.slot.append(self.conv)
            chat.open_chat = self.cid
        else:
            st = Adw.StatusPage(icon_name=chat_icon(), title=_("Chats"),
                                description=_("Choose a chat or start a new conversation. Messages are end-to-end encrypted."))
            st.set_vexpand(True)
            nb = _button(_("New conversation"), ("pill", "suggested-action"), self.new_chat, halign=Gtk.Align.CENTER)
            st.set_child(nb)
            self.slot.append(st)

    def _status(self, st):
        while (c := self.status_box.get_first_child()):
            self.status_box.remove(c)
        if st == "unknown":
            sp = Adw.Spinner() if hasattr(Adw, "Spinner") else Gtk.Spinner(spinning=True)
            sp.set_size_request(32, 32)
            sp.set_valign(Gtk.Align.CENTER)
            sp.set_vexpand(True)
            self.status_box.append(sp)
            return
        page = Adw.StatusPage(icon_name=chat_icon(), vexpand=True)
        clamp = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        if st == "off":
            page.set_title(_("Chats"))
            page.set_description(_("Chats are turned off on this server."))
        elif st == "nocrypto":
            page.set_title(_("Chats"))
            page.set_description(_("Chats need the encryption library PyNaCl (package python3-pynacl)."))
        elif st == "setup":
            page.set_title(_("Set up chats"))
            page.set_description(
                _("Saga chats are end-to-end encrypted: messages and attachments are encrypted and decrypted on your devices. "
                  "The server – including the admin – only sees encrypted data.") + "\n\n" +
                _("You'll get a recovery key in a moment. With it you can bring your chats to a new device when no other device "
                  "is left. Without it your messages are lost – the admin can't restore them either."))
            clamp.append(_button(_("Create keys"), ("pill", "suggested-action"), lambda: SetupDialog(self.win).present(self.win),
                                 halign=Gtk.Align.CENTER))
        else:                                                        # "device": eingerichtet, Schluessel fehlen hier
            page.set_title(_("Chats on this device"))
            page.set_description(_("Your chats are end-to-end encrypted. For this device to read them it needs your keys – choose a way:"))
            lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
            lb.add_css_class("boxed-list")
            for title, sub, icon, cb in ((_("Confirm with another device"), _("Confirm a code on a device where your chats already work."),
                                          "phone-symbolic", lambda: LinkDialog(self.win).present(self.win)),
                                         (_("Enter recovery key"), _("The key you got during setup."), "dialog-password-symbolic",
                                          lambda: RecoveryDialog(self.win).present(self.win))):
                r = Adw.ActionRow(title=title, subtitle=sub, activatable=True)
                r.add_prefix(Gtk.Image.new_from_icon_name(icon))
                r.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
                r.connect("activated", lambda *_, cb=cb: cb())
                lb.append(r)
            clamp.append(lb)
            again = _button(None, ("flat",), lambda: ask_start_over(self.win), halign=Gtk.Align.CENTER,
                            child=_lbl(_("Neither available any more? Start over …"), xalign=0.5, justify=Gtk.Justification.CENTER))
            clamp.append(again)
        if st in ("setup", "device") and not keys_persistent():
            clamp.append(_lbl(_("No keyring available – the chat keys are only kept until Saga is closed."), ("caption", "dim-label"),
                              xalign=0.5, justify=Gtk.Justification.CENTER))
        if clamp.get_first_child() is not None:
            page.set_child(Adw.Clamp(maximum_size=440, child=clamp))
        self.status_box.append(page)

    def _header(self):
        if self.win.current is self:
            self.win.update_header()

    def header(self):
        """-> (titel, untertitel) fuer die Kopfleiste des Fensters"""
        c = chat.chat(self.cid) if self.cid is not None else None
        if c is None:
            return _("Chats"), ""
        names = chat.typing_names(self.cid)
        if names:
            return chat.title(c), _("{names} is typing …").format(names=", ".join(names))
        if c.get("kind") == "dm":
            return chat.title(c), _("End-to-end encrypted")
        n = int(c.get("member_count") or len(c.get("members") or []))
        return chat.title(c), chat.kind_label(c) + " · " + ngettext("{n} person", "{n} people", n).format(n=n)

    def has_menu(self):
        return self.conv is not None and chat.chat(self.cid) is not None

    def page_menu(self, parent):
        if self.conv is not None:
            self.conv.menu(parent)

    def new_chat(self):
        NewChatDialog(self.win).present(self.win)

    def open(self, cid):
        self.win.show_route(("chat", cid))


class ChatList(Gtk.Box):
    __gtype_name__ = "HKChatList"

    def __init__(self, screen):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.screen = screen
        bar = Gtk.Box(spacing=6, margin_start=8, margin_end=8, margin_top=8, margin_bottom=6)
        self.search = Gtk.SearchEntry(placeholder_text=_("Search"), hexpand=True)
        self.search.connect("search-changed", lambda *_: self.fill())
        bar.append(self.search)
        nb = Gtk.Button(icon_name="list-add-symbolic", tooltip_text=_("New conversation"))
        nb.update_property([Gtk.AccessibleProperty.LABEL], [_("New conversation")])
        nb.connect("clicked", lambda *_: screen.new_chat())
        bar.append(nb)
        self.append(bar)
        sw = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.box = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.box.add_css_class("navigation-sidebar")
        self.box.update_property([Gtk.AccessibleProperty.LABEL], [_("Chats")])
        self.box.connect("row-activated", lambda lb, row: getattr(row, "cid", None) is not None and screen.open(row.cid))
        sw.set_child(self.box)
        self.append(sw)
        self.sig = None

    def fill(self):
        q = self.search.get_text().strip().lower()
        lst = [c for c in chat.chats if not q or q in chat.title(c).lower()]
        sig = (q, self.screen.cid, tuple((c.get("id"), chat.title(c), c.get("unread"), c.get("muted"), c.get("last_at")) for c in lst))
        if sig == self.sig:
            return
        self.sig = sig
        self.box.remove_all()
        if not lst:
            lb = _lbl(_("No matches.") if q else _("No chats yet."), ("dim-label",), margin_start=10, margin_top=10)
            self.box.append(Gtk.ListBoxRow(activatable=False, selectable=False, child=lb))
            return
        for c in lst:
            self.box.append(self._row(c))

    def _row(self, c):
        row = Gtk.ListBoxRow()
        row.cid = c["id"]
        if c["id"] == self.screen.cid:
            row.add_css_class("hk-active")
        box = Gtk.Box(spacing=10, margin_top=4, margin_bottom=4)
        title = chat.title(c)
        av = Adw.Avatar(size=36, text=title, show_initials=c.get("kind") in ("dm", "free"))
        if c.get("kind") == "group":
            from .sharing import people_icon
            av.set_icon_name(people_icon())
        elif c.get("kind") == "page":
            from .publish import _icon
            av.set_icon_name(_icon("saga-page-symbolic", "text-x-generic-symbolic"))
        box.append(av)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, valign=Gtk.Align.CENTER)
        t = Gtk.Label(label=title, xalign=0, ellipsize=Pango.EllipsizeMode.END)
        un = int(c.get("unread") or 0)
        if un:
            t.add_css_class("heading")
        col.append(t)
        n = int(c.get("member_count") or 0)
        sub = chat.kind_label(c) + ("" if c.get("kind") == "dm" else " · " + ngettext("{n} person", "{n} people", n).format(n=n))
        s = Gtk.Label(label=sub, xalign=0, ellipsize=Pango.EllipsizeMode.END)
        s.add_css_class("caption")
        s.add_css_class("dim-label")
        col.append(s)
        box.append(col)
        right = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, valign=Gtk.Align.CENTER)
        tm = Gtk.Label(label=_short_when(c.get("last_at")), xalign=1)
        tm.add_css_class("caption")
        tm.add_css_class("dim-label")
        right.append(tm)
        bx = Gtk.Box(spacing=4, halign=Gtk.Align.END)
        if c.get("muted"):
            mi = Gtk.Image.new_from_icon_name("audio-volume-muted-symbolic")
            mi.add_css_class("dim-label")
            mi.set_tooltip_text(_("Muted"))
            bx.append(mi)
        if un:
            b = Gtk.Label(label=str(un))
            b.add_css_class("hk-badge")
            b.add_css_class("hk-badge-accent")
            bx.append(b)
        right.append(bx)
        box.append(right)
        row.set_child(box)
        acc = title + " – " + sub + (" – " + ngettext("{n} unread", "{n} unread", un).format(n=un) if un else "")
        row.update_property([Gtk.AccessibleProperty.LABEL], [acc])
        return row


def _short_when(ts):
    if not ts:
        return ""
    d = _dt.datetime.fromtimestamp(float(ts))
    if d.date() == _dt.date.today():
        return time_text(d)
    return date_short(ts)


# --- Unterhaltung -------------------------------------------------------------------------------------------------
class Conversation(Gtk.Box):
    __gtype_name__ = "HKConversation"

    def __init__(self, screen, cid):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, vexpand=True, hexpand=True)
        self.screen, self.win, self.cid = screen, screen.win, cid
        self.reply = None
        self.files = []              # [(name, mime, bytes)]
        self.mentions = {}           # "@Titel" -> Seiten-ID
        self.query = ""
        self.sig = None
        self.at_bottom = True
        self.images = {}             # Datei-ID -> Gdk.Texture (entschluesselt, nur im Speicher)
        # Hinweise oben: Sicherheitsnummer geaendert / nicht eingerichtet
        self.warn_rev = Gtk.Revealer(transition_type=Gtk.RevealerTransitionType.SLIDE_DOWN)
        from .page import BannerBox, BannerRow
        wb = BannerRow(spacing=8)
        wb.add_css_class("hk-draft-banner")
        self.warn_label = _lbl("", hexpand=True, margin_start=6)
        wb.append(self.warn_label)
        self.warn_btn = _button(_("Safety number"), ("suggested-action",), self._warn_safety, valign=Gtk.Align.CENTER)
        wb.append(self.warn_btn)
        self.warn_ok = _button(_("Got it"), ("flat",), self._warn_ok, valign=Gtk.Align.CENTER)
        wb.append(self.warn_ok)
        self.warn_rev.set_child(wb)
        frame = BannerBox()
        frame.append(self.warn_rev)
        self.append(frame)
        self.warn_uid = None
        # Suche (nur lokal)
        self.search_bar = Gtk.SearchBar()
        se = Gtk.SearchEntry(placeholder_text=_("Search in this chat"), hexpand=True)
        se.connect("search-changed", lambda e: self._search(e.get_text()))
        self.search_count = _lbl("", ("dim-label", "caption"), wrap=False)
        sb = Gtk.Box(spacing=8)
        sb.append(se)
        sb.append(self.search_count)
        self.search_bar.set_child(sb)
        self.search_bar.connect_entry(se)
        self.append(self.search_bar)
        # Nachrichten
        self.scroll = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        clamp = Adw.Clamp(maximum_size=860, tightening_threshold=600)
        self.msgs = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_start=12, margin_end=12, margin_top=10,
                            margin_bottom=10)
        self.msgs.update_property([Gtk.AccessibleProperty.LABEL], [_("Messages")])
        clamp.set_child(self.msgs)
        self.scroll.set_child(clamp)
        self.scroll.get_vadjustment().connect("value-changed", self._scrolled)
        self.append(self.scroll)
        self.typing_label = _lbl("", ("caption", "dim-label"), margin_start=16, margin_bottom=2, visible=False)
        self.append(self.typing_label)
        self.append(self._composer())
        watch_chat(self, "messages", lambda cid: cid == self.cid and self.render())
        watch_chat(self, "typing", lambda cid: cid == self.cid and self._typing())
        watch_chat(self, "chat-changed", lambda cid: cid == self.cid and self._chat_changed())
        self.connect("map", lambda *_: self._mapped())
        self.loaded = False
        self.render()
        if not chat.cache.get(cid, {}).get("loaded"):
            chat.load_messages(cid, done=self._first_load)
        else:
            chat.load_messages(cid)
            self._first_load()
        if chat.chat(cid) is None:
            chat.load_chat(cid, error=self._load_error)

    def _load_error(self, e):
        if isinstance(e, APIError) and e.code == 404:
            _toast(self.win, _("This chat doesn't exist (any more) or you're no longer part of it."))
            self.win.show_root(("chats",))

    def _first_load(self):
        self.loaded = True
        self.at_bottom = True
        self._to_bottom()
        self._mark_read()
        self._check_trust()
        GLib.idle_add(lambda: (self.entry.grab_focus(), False)[1])

    def _mapped(self):
        chat.open_chat = self.cid

    def _chat_changed(self):
        self._check_trust()
        self.screen._header()

    # --- Vertrauen ---------------------------------------------------------------------------------------------
    def _check_trust(self):
        """Sicherheitsnummer eines Mitglieds geaendert (nicht bestaetigt) -> Hinweis mit "Sicherheitsnummer" / "Verstanden"""
        c = chat.chat(self.cid) or {}
        me = chat.uid
        changed = None
        for m in c.get("members") or []:
            u = m.get("username")
            if u and u != me and trust_get(me, u).get("pending"):
                changed = m
                break
        self.warn_uid = changed.get("username") if changed else None
        if changed:
            nm = changed.get("name") or changed.get("username")
            self.warn_label.set_label(_("{name}'s safety number has changed – e.g. after “Start over” or with a new account. "
                                        "Check it before you write anything confidential.").format(name=nm))
        self.warn_rev.set_reveal_child(changed is not None)

    def _warn_ok(self):
        if self.warn_uid:
            trust_accept(chat.uid, self.warn_uid)
        self._check_trust()

    def _warn_safety(self):
        if self.warn_uid:
            open_safety(self.win, self.cid, self.warn_uid)

    # --- Nachrichten ---------------------------------------------------------------------------------------------
    def _scrolled(self, adj):
        self.at_bottom = adj.get_value() + adj.get_page_size() >= adj.get_upper() - 40

    def _to_bottom(self):
        def go():
            adj = self.scroll.get_vadjustment()
            adj.set_value(adj.get_upper() - adj.get_page_size())
            return False
        GLib.idle_add(go)
        GLib.timeout_add(120, go)

    def render(self):
        c = chat.cache.get(self.cid) or {}
        msgs = c.get("msgs") or []
        q = self.query.lower()
        shown = [m for m in msgs if not q or q in (m.get("text") or "").lower()]
        sig = (tuple((m["id"], m.get("state")) for m in shown), c.get("more"), q, len(chat.notes), len(self.images),
               c.get("error"), self.loaded)
        if sig == self.sig:
            return
        stick = self.at_bottom
        self.sig = sig
        while (w := self.msgs.get_first_child()):
            self.msgs.remove(w)
        if c.get("more") and not q:
            b = _button(_("Load older messages"), ("flat",), self._older, halign=Gtk.Align.CENTER)
            self.msgs.append(b)
        elif not q:
            info = Gtk.Box(spacing=6, halign=Gtk.Align.CENTER, margin_top=6, margin_bottom=10)
            from .sharing import lock_icon
            info.append(Gtk.Image.new_from_icon_name(lock_icon()))
            t = _("Messages in this chat are end-to-end encrypted.")
            ch = chat.chat(self.cid) or {}
            if ch.get("kind") != "dm":
                t += " " + _("New members only see messages from when they joined.")
            info.append(_lbl(t, ("caption", "dim-label"), max_width_chars=60, justify=Gtk.Justification.CENTER))
            info.add_css_class("hk-e2e-note")
            self.msgs.append(info)
        if c.get("error") and not msgs:
            self.msgs.append(_lbl(_("Messages not loaded: {err}").format(err=c["error"]), ("error",), xalign=0.5))
        by_id = {m["id"]: m for m in msgs}
        group = (chat.chat(self.cid) or {}).get("kind") != "dm"
        prev = None
        for m in shown:
            day = _day_label(m.get("created") or (m.get("ts") or 0) / 1000)
            if prev is None or _day_label(prev.get("created") or (prev.get("ts") or 0) / 1000) != day:
                d = _lbl(day, ("caption", "dim-label", "hk-day"), xalign=0.5, wrap=False, margin_top=8, margin_bottom=4)
                self.msgs.append(d)
            first = prev is None or (prev.get("sender") or {}).get("username") != (m.get("sender") or {}).get("username") or \
                (m.get("created") or 0) - (prev.get("created") or 0) > 600
            self.msgs.append(self._bubble(m, by_id, group, first))
            prev = m
        if q:
            self.search_count.set_label(ngettext("{n} match", "{n} matches", len(shown)).format(n=len(shown)))
        if stick or not self.loaded:
            self._to_bottom()
        if self.loaded and self.win.is_active():
            self._mark_read()

    def _older(self):
        adj = self.scroll.get_vadjustment()
        before = adj.get_upper() - adj.get_value()
        self.at_bottom = False

        def keep():
            def go():
                adj.set_value(max(0, adj.get_upper() - before))
                return False
            GLib.idle_add(go)
        chat.load_messages(self.cid, older=True, done=keep)

    def _mark_read(self):
        if chat.open_chat == self.cid:
            chat.mark_read(self.cid)

    def _search(self, q):
        self.query = q.strip()
        self.search_count.set_label("")
        self.render()

    def toggle_search(self):
        self.search_bar.set_search_mode(not self.search_bar.get_search_mode())

    def _typing(self):
        names = chat.typing_names(self.cid)
        self.typing_label.set_label(_("{names} is typing …").format(names=", ".join(names)) if names else "")
        self.typing_label.set_visible(bool(names))
        self.screen._header()

    def _bubble(self, m, by_id, group, first):
        mine = m.get("mine")
        row = Gtk.Box(spacing=6, halign=Gtk.Align.END if mine else Gtk.Align.START, margin_top=6 if first else 0)
        sender = m.get("sender") or {}
        sname = sender.get("name") or _("Deleted account")
        if group and not mine:
            if first:
                av = Adw.Avatar(size=28, text=sname, show_initials=True, valign=Gtk.Align.END)
                row.append(av)
            else:
                row.append(Gtk.Box(width_request=28))
        b = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3)
        b.add_css_class("hk-bubble")
        if mine:
            b.add_css_class("hk-bubble-me")
        st = m.get("state")
        if st != "ok":
            b.add_css_class("hk-bubble-warn")
        if group and not mine and first:
            nl = _lbl(sname, ("caption-heading", "hk-bubble-name"), wrap=False)
            b.append(nl)
        if m.get("reply_to"):
            q = by_id.get(m["reply_to"])
            qb = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            qb.add_css_class("hk-quote")
            qn = ((q or {}).get("sender") or {}).get("name") or ""
            if qn:
                qb.append(_lbl(qn, ("caption-heading",), wrap=False))
            qb.append(_lbl(snippet(q), ("caption",), max_width_chars=50, lines=2, ellipsize=Pango.EllipsizeMode.END))
            b.append(qb)
        acc = [sname if not mine else _("you")]
        if st == "undecryptable":
            lb = _lbl(_("Can't be decrypted"), ("hk-italic", "dim-label"))
            lb.set_tooltip_text(_("This message can't be decrypted on this device."))
            b.append(lb)
            acc.append(_("Can't be decrypted"))
        else:
            text = m.get("text") or ""
            if text.strip():
                lb = Gtk.Label(xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, selectable=True, max_width_chars=60)
                lb.set_markup(text_markup(text, chat.notes))
                lb.connect("activate-link", self._link)
                lb.add_css_class("hk-bubble-text")
                b.append(lb)
                acc.append(NOTE_RX.sub("📄", text))
            for f in m.get("files") or []:
                b.append(self._file(f))
                acc.append(f.get("n") or _("File"))
        meta = Gtk.Box(spacing=4, halign=Gtk.Align.END)
        if st == "unverified":
            w = _lbl("⚠ " + _("Authenticity not confirmed"), ("caption", "hk-warn-text"), wrap=False)
            w.set_tooltip_text(_("Sender not confirmed: signature or device key doesn't match (e.g. after “Start over”)."))
            meta.append(w)
            acc.append(_("Authenticity not confirmed"))
        tm = _lbl(_time(m.get("created") or (m.get("ts") or 0) / 1000), ("caption", "dim-label"), wrap=False)
        meta.append(tm)
        b.append(meta)
        row.append(b)
        b.update_property([Gtk.AccessibleProperty.LABEL], [" – ".join(acc)])
        b.set_focusable(True)
        gc = Gtk.GestureClick(button=3)
        gc.connect("pressed", lambda g, n, x, y: self._msg_menu(b, m))
        b.add_controller(gc)
        lp = Gtk.GestureLongPress()
        lp.connect("pressed", lambda g, x, y: self._msg_menu(b, m))
        b.add_controller(lp)
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", lambda c, kv, code, s: (kv == Gdk.KEY_Menu or (kv == Gdk.KEY_F10 and s & Gdk.ModifierType.SHIFT_MASK))
                   and (self._msg_menu(b, m), True)[1])
        b.add_controller(kc)
        return row

    def _link(self, label, uri):
        if uri.startswith("saga-note:"):
            self.win.show_page(int(uri.split(":", 1)[1]))
        else:
            self.win.open_uri(uri)
        return True

    def _msg_menu(self, w, m):
        ents = []
        if m.get("state") != "undecryptable":
            ents.append((_("Reply"), lambda: self.set_reply(m)))
            if m.get("text"):
                ents.append((_("Copy text"), lambda: (self.win.copy_text(m["text"]), _toast(self.win, _("Copied.")))))
        if m.get("mine"):
            ents += [None, (_("Delete for everyone"), lambda: self._ask_delete(m), True)]
        if ents:
            menu_popover(w, ents).popup()

    def _ask_delete(self, m):
        from .publish import confirm
        confirm(self.win, _("Delete message for everyone?"),
                _("The message and its attachments will be deleted from the server. Anyone who already read it may have a copy."),
                _("Delete"), lambda: chat.delete_message(self.cid, m["id"], error=lambda e: _toast(self.win, str(e))))

    # --- Anhaenge ------------------------------------------------------------------------------------------------------
    def _file(self, f):
        mime = f.get("m") or ""
        name = f.get("n") or _("File")
        if mime.startswith("image/") and mime != "image/svg+xml":
            tex = self.images.get(f["id"])
            if tex is not None:
                pic = Gtk.Picture.new_for_paintable(tex)
                pic.set_can_shrink(True)
                pic.set_content_fit(Gtk.ContentFit.CONTAIN)
                w, h = tex.get_width(), tex.get_height()
                sc = min(1.0, 240 / max(1, w), 280 / max(1, h))          # 240: passt auch ins schmale Fenster
                pic.set_size_request(int(w * sc), int(h * sc))
                pic.add_css_class("hk-bubble-img")
                btn = Gtk.Button(child=pic, tooltip_text=name)
                btn.add_css_class("flat")
                btn.add_css_class("hk-img-btn")
                btn.connect("clicked", lambda *_: self._save_file(f))
                return btn
            if f["id"] not in self.images:
                self.images[f["id"]] = None
                bg(self._load_image, f, done=lambda tex: self._image_loaded(f, tex), error=lambda e: self._image_loaded(f, False),
                   media=True)
            elif self.images[f["id"]] is False:
                return _lbl("🖼 " + name + " – " + _("not loaded"), ("caption", "dim-label"))
            sp = Gtk.Box(width_request=200, height_request=120)
            sp.add_css_class("hk-img-wait")
            return sp
        btn = Gtk.Button()
        bx = Gtk.Box(spacing=8)
        bx.append(Gtk.Image.new_from_icon_name("mail-attachment-symbolic"))
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        col.append(_lbl(name, wrap=False, ellipsize=Pango.EllipsizeMode.MIDDLE, max_width_chars=36))
        col.append(_lbl(human_size(int(f.get("s") or 0)), ("caption", "dim-label"), wrap=False))
        bx.append(col)
        btn.set_child(bx)
        btn.add_css_class("hk-file-btn")
        btn.set_tooltip_text(_("Save attachment"))
        btn.connect("clicked", lambda *_: self._save_file(f))
        return btn

    def _load_image(self, f):
        data = chat.fetch_file(self.cid, f)
        return Gdk.Texture.new_from_bytes(GLib.Bytes.new(data))

    def _image_loaded(self, f, tex):
        self.images[f["id"]] = tex if tex else False
        self.sig = None
        self.render()

    def _save_file(self, f):
        name = re.sub(r"[/\\\x00-\x1f]", "-", f.get("n") or "datei").strip(". ") or "datei"
        fd = Gtk.FileDialog(title=_("Save attachment"), initial_name=name)

        def chosen(d, res):
            try:
                gf = d.save_finish(res)
            except GLib.Error:
                return
            if gf is None:
                return

            def work():
                data = chat.fetch_file(self.cid, f)
                gf.replace_contents(data, None, False, Gio.FileCreateFlags.REPLACE_DESTINATION, None)
                return gf
            bg(work, done=lambda g: _toast(self.win, _("Saved: {name}").format(name=g.get_basename() or name)),
               error=lambda e: _toast(self.win, _("Attachment not loaded: {err}").format(err=e)), media=True)
        fd.save(self.win, None, chosen)

    # --- Eingabe -----------------------------------------------------------------------------------------------------
    def _composer(self):
        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        outer.add_css_class("hk-composer-bar")
        self.reply_bar = Gtk.Box(spacing=8, visible=False, margin_start=12, margin_end=12, margin_top=6)
        self.reply_bar.add_css_class("hk-quote")
        self.reply_label = _lbl("", ("caption",), hexpand=True, lines=2, ellipsize=Pango.EllipsizeMode.END)
        self.reply_bar.append(self.reply_label)
        x = flat_button(icon="window-close-symbolic", tooltip=_("Cancel reply"), cb=lambda: self.set_reply(None))
        self.reply_bar.append(x)
        outer.append(self.reply_bar)
        self.files_bar = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, visible=False, margin_start=12, margin_end=12,
                                     max_children_per_line=6, row_spacing=4, column_spacing=4)
        outer.append(self.files_bar)
        row = Gtk.Box(spacing=6, margin_start=10, margin_end=10, margin_top=6, margin_bottom=10)
        att = Gtk.Button(icon_name="mail-attachment-symbolic", tooltip_text=_("Attach file"), valign=Gtk.Align.END)
        att.add_css_class("flat")
        att.update_property([Gtk.AccessibleProperty.LABEL], [_("Attach file")])
        att.connect("clicked", lambda *_: self._pick_files())
        row.append(att)
        at = Gtk.MenuButton(label="@", tooltip_text=_("Link a page"), valign=Gtk.Align.END)
        at.add_css_class("flat")
        at.set_popover(self._page_popover())
        row.append(at)
        self.at_btn = at
        sw = Gtk.ScrolledWindow(hexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True,
                                max_content_height=160)
        sw.add_css_class("hk-composer")
        self.entry = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR, accepts_tab=False, top_margin=8, bottom_margin=8,
                                  left_margin=10, right_margin=10)
        self.entry.update_property([Gtk.AccessibleProperty.LABEL], [_("Message …")])
        self.placeholder = _lbl(_("Message …"), ("dim-label",), wrap=False, can_target=False, margin_start=11, valign=Gtk.Align.CENTER)
        ov = Gtk.Overlay()
        ov.set_child(self.entry)
        ov.add_overlay(self.placeholder)
        sw.set_child(ov)
        buf = self.entry.get_buffer()
        buf.connect("changed", self._changed)
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", self._key)
        self.entry.add_controller(kc)
        row.append(sw)
        self.send_btn = Gtk.Button(icon_name=send_icon(), tooltip_text=_("Send"), valign=Gtk.Align.END)
        self.send_btn.add_css_class("suggested-action")
        self.send_btn.add_css_class("circular")
        self.send_btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Send")])
        self.send_btn.connect("clicked", lambda *_: self.send())
        row.append(self.send_btn)
        outer.append(row)
        self._changed(buf)
        return outer

    def _changed(self, buf):
        empty = buf.get_char_count() == 0
        self.placeholder.set_visible(empty)
        self.send_btn.set_sensitive(not empty or bool(self.files))
        if not empty:
            chat.send_typing(self.cid)

    def _key(self, c, kv, code, state):
        if kv in (Gdk.KEY_Return, Gdk.KEY_KP_Enter) and not state & (Gdk.ModifierType.SHIFT_MASK | Gdk.ModifierType.CONTROL_MASK):
            self.send()
            return True
        if kv == Gdk.KEY_Escape and self.reply is not None:
            self.set_reply(None)
            return True
        return False

    def set_reply(self, m):
        self.reply = m
        if m is not None:
            nm = (m.get("sender") or {}).get("name") or ""
            self.reply_label.set_label(_("Reply to {name}").format(name=nm) + "\n" + snippet(m))
        self.reply_bar.set_visible(m is not None)
        self.entry.grab_focus()

    def _pick_files(self):
        fd = Gtk.FileDialog(title=_("Attach file"))

        def done(d, res):
            try:
                files = d.open_multiple_finish(res)
            except GLib.Error:
                return
            fl = [files.get_item(i) for i in range(files.get_n_items())]
            self.add_files(fl)
        fd.open_multiple(self.win, None, done)

    def add_files(self, gfiles):
        lim = chat.limits or {}
        fmax = int(lim.get("file_max") or 25 * 1048576)
        nmax = int(lim.get("files_per_msg") or 10)

        def work():
            out = []
            for g in gfiles:
                info = g.query_info("standard::size,standard::content-type,standard::display-name", Gio.FileQueryInfoFlags.NONE, None)
                name = info.get_display_name() or g.get_basename() or "datei"
                if info.get_size() > fmax:
                    out.append((name, None, _("“{name}” is too large (at most {n} MB).").format(name=name, n=fmax // 1048576)))
                    continue
                mime = Gio.content_type_get_mime_type(info.get_content_type() or "") or "application/octet-stream"
                out.append((name, mime, g.load_bytes(None)[0].get_data()))
            return out

        def fin(lst):
            for name, mime, data in lst:
                if mime is None:
                    _toast(self.win, data)
                elif len(self.files) < nmax:
                    self.files.append((name, mime, data))
            self._files_bar()
        bg(work, done=fin, error=lambda e: _toast(self.win, str(e)), media=True)

    def _files_bar(self):
        self.files_bar.remove_all()
        for i, (name, _mime, data) in enumerate(self.files):
            chip = Gtk.Box(spacing=4)
            chip.add_css_class("hk-tag")
            chip.append(_lbl("📎 " + name + " · " + human_size(len(data)), wrap=False, ellipsize=Pango.EllipsizeMode.MIDDLE,
                             max_width_chars=28))
            chip.append(flat_button(icon="window-close-symbolic", css="hk-tiny", tooltip=_("Remove"),
                                    cb=lambda i=i: (self.files.pop(i), self._files_bar())))
            self.files_bar.append(chip)
        self.files_bar.set_visible(bool(self.files))
        self._changed(self.entry.get_buffer())

    def _page_popover(self):
        pop = Gtk.Popover()
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=8, margin_bottom=8, margin_start=8, margin_end=8)
        se = Gtk.SearchEntry(placeholder_text=_("Search pages"))
        box.append(se)
        lst = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lst.add_css_class("boxed-list")
        box.append(lst)
        box.set_size_request(300, -1)
        pop.set_child(box)
        state = {"q": None}

        def show(pages):
            lst.remove_all()
            for p in pages[:10]:
                r = Adw.ActionRow(title=GLib.markup_escape_text((p.get("icon") + " " if p.get("icon") and not p["icon"].startswith("/") else "📄 ")
                                                               + (p.get("title") or _("Untitled"))), activatable=True)
                r.connect("activated", lambda *_, p=p: (pop.popdown(), self._insert_page(p)))
                lst.append(r)
            lst.set_visible(bool(pages))

        def search(*_):
            q = se.get_text().strip()
            state["q"] = q
            bg(client.get, "api/chat/pages?q=" + GLib.Uri.escape_string(q, None, True),
               done=lambda j: state["q"] == q and show((j or {}).get("pages") or []), error=lambda e: show([]))
        se.connect("search-changed", search)
        pop.connect("show", lambda *_: (se.set_text(""), search(), se.grab_focus()))
        return pop

    def _insert_page(self, p):
        t = "@" + (p.get("title") or _("Untitled"))
        self.mentions[t] = int(p["id"])
        chat.notes[int(p["id"])] = {"title": p.get("title"), "icon": p.get("icon")}
        buf = self.entry.get_buffer()
        buf.insert_at_cursor(t + " ")
        self.entry.grab_focus()

    def send(self):
        buf = self.entry.get_buffer()
        text = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False).strip()
        files = list(self.files)
        if not text and not files:
            return
        notes = []
        for t, nid in sorted(self.mentions.items(), key=lambda x: -len(x[0])):
            if t in text:
                text = text.replace(t, f"[[note:{nid}]]")
                notes.append(nid)
        reply = self.reply["id"] if self.reply else None
        cid = self.cid
        self.send_btn.set_sensitive(False)
        self.entry.set_sensitive(False)

        def work():
            fobjs = [chat.upload_file(cid, data, name, mime) for name, mime, data in files]
            body = {"v": 1, "type": "text", "text": text}
            if fobjs:
                body["files"] = fobjs
            if notes:
                body["notes"] = notes
            return chat.send(cid, body, reply_to=reply, file_ids=[f["id"] for f in fobjs])

        def fin(r):
            _resp, missing = r
            buf.set_text("")
            self.files, self.mentions = [], {}
            self._files_bar()
            self.set_reply(None)
            self.entry.set_sensitive(True)
            self.entry.grab_focus()
            self.at_bottom = True
            chat.load_messages(cid)
            chat._list_soon()
            if missing:
                _toast(self.win, _("{names} hasn't set up chats yet and can't read this message.").format(names=", ".join(missing)))

        def err(e):
            self.entry.set_sensitive(True)
            self._changed(buf)
            if isinstance(e, SafetyChanged):
                self._check_trust()
            _toast(self.win, _("Not sent: {err}").format(err=e))
        bg(work, done=fin, error=err)

    # --- Menue ---------------------------------------------------------------------------------------------------------
    def menu(self, parent):
        c = chat.chat(self.cid)
        if not c:
            return
        ents = []
        peer = chat.peer(c)
        if c.get("kind") == "dm" and peer:
            ents.append((_("Safety number"), lambda: open_safety(self.win, self.cid, peer.get("username"))))
        ents.append((_("Members") + " …", lambda: ChatInfoDialog(self.win, self.cid).present(self.win)))
        if c.get("kind") == "page" and c.get("note_id"):
            ents.append((_("Open page"), lambda: self.win.show_page(int(c["note_id"]))))
        ents.append((_("Search in this chat"), self.toggle_search))
        ents.append(None)
        ents.append((_("Unmute") if c.get("muted") else _("Mute"),
                     lambda: chat.patch(self.cid, {"muted": not c.get("muted")}, error=lambda e: _toast(self.win, str(e)))))
        cur = int(c.get("retention_s") or 0)
        sub = [(("✓ " if s == cur else "") + retention_label(s),
                lambda s=s: chat.patch(self.cid, {"retention_s": s}, error=lambda e: _toast(self.win, str(e))))
               for s in (chat.limits.get("retentions") or RETENTIONS)]
        ents.append((_("Delete messages automatically after"), sub))
        if c.get("kind") == "free":
            ents.append(None)
            ents.append((_("Rename …"), lambda: rename_group(self.win, self.cid)))
            ents.append((_("Leave group"), lambda: leave_group(self.win, self.cid), True))
        menu_popover(parent, ents).popup()


# --- Dialoge: Chat -------------------------------------------------------------------------------------------------
def open_safety(win, cid, uid):
    """Sicherheitsnummer mit einem Mitglied (Schluessel aus den Empfaengern dieses Chats)"""
    def work():
        rc = chat.recipients(cid, fresh=True)
        return next((r for r in rc if r.get("username") == uid), None)

    def fin(r):
        if r is None:
            return
        SafetyDialog(win, r).present(win)
    bg(work, done=fin, error=lambda e: _toast(win, str(e)))


class SafetyDialog(Adw.Dialog):
    def __init__(self, win, r):
        name = r.get("name") or r.get("username")
        super().__init__(title=_("Safety number"), content_width=440)
        self.win, self.r = win, r
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin_top=12, margin_bottom=18, margin_start=18, margin_end=18)
        box.append(_lbl(_("Safety number with {name}").format(name=name), ("title-3",), xalign=0.5, justify=Gtk.Justification.CENTER))
        if not r.get("ready") or not chat.keys:
            box.append(_lbl(_("{name} hasn't set up chats yet.").format(name=name), ("dim-label",), xalign=0.5))
        else:
            num = safety_for(chat.uid, chat.keys["ident_pk"], r["username"], r.get("ident_pub"))
            blocks = (num or "").split()
            lines = [" ".join(blocks[i:i + 4]) for i in range(0, len(blocks), 4)]
            nl = _lbl("\n".join(lines), ("hk-safety", "monospace"), xalign=0.5, wrap=False, selectable=True,
                      justify=Gtk.Justification.CENTER)
            nl.update_property([Gtk.AccessibleProperty.LABEL], [_("Safety number") + ": " + " ".join(blocks)])
            box.append(nl)
            if num:
                from . import qr
                q = qr.widget(safety_qr_text(num), 200)
                q.add_css_class("hk-qr")
                q.update_property([Gtk.AccessibleProperty.LABEL], [_("QR code of the safety number")])
                box.append(q)
            box.append(_lbl(_("Compare this number with {name} – in person, by phone or on their device. If it matches, nobody is "
                              "reading along. It's the same on both sides.").format(name=name), ("dim-label",), xalign=0.5,
                            justify=Gtk.Justification.CENTER))
            t = trust_get(chat.uid, r["username"])
            done = t.get("verified") and t.get("ident_pub") == r.get("ident_pub")
            self.vb = _button(("✓ " + _("verified")) if done else _("Mark as verified"), ("pill",) if done else ("pill", "suggested-action"),
                              self._verify, halign=Gtk.Align.CENTER)
            self.vb.set_sensitive(not done)
            box.append(self.vb)
        tv.set_content(box)
        self.set_child(tv)

    def _verify(self):
        trust_verified(chat.uid, self.r["username"], self.r.get("ident_pub"))
        self.vb.set_label("✓ " + _("verified"))
        self.vb.remove_css_class("suggested-action")
        self.vb.set_sensitive(False)
        _toast(self.win, _("Marked as verified."))
        chat.emit("chat-changed", chat.open_chat or 0)


def rename_group(win, cid):
    d = Adw.AlertDialog(heading=_("Rename group"), body=_("The name is encrypted like a message."))
    en = Gtk.Entry(placeholder_text=_("Group name"), text=chat.title(chat.chat(cid)) if (chat.chat(cid) or {}).get("_title") else "")
    d.set_extra_child(en)
    d.add_response("cancel", _("Cancel"))
    d.add_response("ok", _("Save"))
    d.set_response_appearance("ok", Adw.ResponseAppearance.SUGGESTED)
    d.set_default_response("ok")
    d.set_close_response("cancel")

    def resp(dd, r):
        name = en.get_text().strip()[:100]
        if r != "ok" or not name:
            return
        bg(chat.send, cid, {"v": 1, "type": "title", "title": name}, None, "title",
           done=lambda _r: chat.load_chat(cid), error=lambda e: _toast(win, _("Not sent: {err}").format(err=e)))
    d.connect("response", resp)
    en.connect("activate", lambda *_: d.response("ok") if hasattr(d, "response") else None)
    d.present(win)


def leave_group(win, cid):
    from .publish import confirm

    def go():
        def fin(_r):
            chat.chats = [c for c in chat.chats if c.get("id") != cid]
            chat.emit("changed")
            win.show_root(("chats",))
        bg(client.request, "DELETE", f"api/chats/{cid}/members/{GLib.Uri.escape_string(chat.uid, None, True)}", done=fin,
           error=lambda e: _toast(win, str(e)))
    confirm(win, _("Leave group?"), _("You won't receive new messages any more."), _("Leave"), go)


class ChatInfoDialog(Adw.Dialog):
    """Mitglieder (mit Sicherheitsnummer), bei freien Gruppen hinzufuegen/entfernen (Admin)"""

    def __init__(self, win, cid):
        super().__init__(title=_("Members"), content_width=460, content_height=560)
        self.win, self.cid = win, cid
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        self.page = Adw.PreferencesPage()
        tv.set_content(self.page)
        self.set_child(tv)
        self.group = None
        self.add_group = None
        self.rc = {}
        self.fill()
        bg(chat.recipients, cid, True, done=self._rc, error=lambda e: None)

    def _rc(self, rc):
        self.rc = {r.get("username"): r for r in rc or []}
        self.fill()

    def fill(self):
        for g in (self.group, self.add_group):
            if g is not None:
                self.page.remove(g)
        c = chat.chat(self.cid) or {}
        kind = c.get("kind")
        desc = {"group": _("All members of the group “{g}” – changes with the group.").format(g=c.get("group") or ""),
                "page": _("Everyone who can see the page – changes with its shares.")}.get(kind, "")
        self.group = Adw.PreferencesGroup(title=GLib.markup_escape_text(chat.title(c)), description=GLib.markup_escape_text(desc))
        admin = c.get("role") == "admin" and kind == "free"
        me = chat.uid
        for m in c.get("members") or []:
            u = m.get("username")
            r = self.rc.get(u) or {}
            sub = []
            if m.get("me") or u == me:
                sub.append(_("you"))
            t = trust_get(me, u)
            if t.get("verified") and r.get("ident_pub") and t.get("ident_pub") == r.get("ident_pub"):
                sub.append("✓ " + _("verified"))
            if r and not r.get("ready"):
                sub.append(_("hasn't set up chats yet"))
            row = Adw.ActionRow(title=GLib.markup_escape_text(m.get("name") or u or ""), subtitle=GLib.markup_escape_text(" · ".join(sub)))
            row.add_prefix(Adw.Avatar(size=32, text=m.get("name") or u or "?", show_initials=True))
            if u != me and r.get("ready"):
                row.add_suffix(_button(_("Safety number"), ("flat",), lambda u=u: open_safety(self.win, self.cid, u), valign=Gtk.Align.CENTER))
            if admin and u != me:
                rm = Gtk.Button(icon_name="user-trash-symbolic", tooltip_text=_("Remove"), valign=Gtk.Align.CENTER)
                rm.add_css_class("flat")
                rm.connect("clicked", lambda *_, u=u: self._remove(u))
                row.add_suffix(rm)
            self.group.add(row)
        self.page.add(self.group)
        self.add_group = None
        if admin:
            self.add_group = Adw.PreferencesGroup(title=_("Add person"))
            en = Adw.EntryRow(title=_("Add person (3+ characters)"))
            self.add_group.add(en)
            self.hits = []

            def search(*_):
                q = en.get_text().strip()
                for h in self.hits:
                    self.add_group.remove(h)
                self.hits = []
                if len(q) < 3:
                    return
                bg(client.get, "api/chat/people?q=" + GLib.Uri.escape_string(q, None, True), done=lambda j: show(q, j), error=lambda e: None)

            def show(q, j):
                if en.get_text().strip() != q:
                    return
                have = {m.get("username") for m in c.get("members") or []}
                for p in ((j or {}).get("users") or [])[:8]:
                    if p.get("username") in have:
                        continue
                    r = Adw.ActionRow(title=GLib.markup_escape_text(p.get("name") or p["username"]),
                                      subtitle="" if p.get("ready") else _("hasn't set up chats yet"), activatable=True)
                    r.add_suffix(Gtk.Image.new_from_icon_name("list-add-symbolic"))
                    r.connect("activated", lambda *_, u=p["username"]: self._add(u))
                    self.add_group.add(r)
                    self.hits.append(r)
            en.connect("changed", search)
            self.page.add(self.add_group)

    def _add(self, u):
        def fin(j):
            ch = (j or {}).get("chat")
            if isinstance(ch, dict):
                chat._merge(ch)
            bg(chat.recipients, self.cid, True, done=self._rc, error=lambda e: None)
        bg(client.json, "POST", f"api/chats/{self.cid}/members", {"user": u}, done=fin, error=lambda e: self.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e)))))

    def _remove(self, u):
        def fin(_r):
            chat.load_chat(self.cid, done=lambda ch: self.fill())
        bg(client.request, "DELETE", f"api/chats/{self.cid}/members/{GLib.Uri.escape_string(u, None, True)}", done=fin,
           error=lambda e: self.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e)))))


class NewChatDialog(Adw.Dialog):
    """Neue Unterhaltung: Person (Direkt-Chat), Gruppe (Gruppen-Chat) oder freie Gruppe mit mehreren Personen"""

    def __init__(self, win):
        super().__init__(title=_("New conversation"), content_width=460, content_height=560)
        self.win = win
        self.multi = False
        self.picked = {}                 # username -> name (freie Gruppe)
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        tv.add_top_bar(hb)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_top=6, margin_bottom=12, margin_start=12, margin_end=12)
        self.search = Gtk.SearchEntry(placeholder_text=_("Search person or group (3+ characters)"))
        self.search.connect("search-changed", lambda *_: self._search())
        box.append(self.search)
        self.group_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, visible=False)
        self.group_box.append(_lbl(_("Custom group: selected people; the name is encrypted."), ("dim-label", "caption")))
        lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lb.add_css_class("boxed-list")
        self.gname = Adw.EntryRow(title=_("Group name"))
        lb.append(self.gname)
        self.group_box.append(lb)
        self.chips = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=4, row_spacing=4, column_spacing=4)
        self.group_box.append(self.chips)
        self.create = _button(_("Create group"), ("suggested-action", "pill"), self._create_free, halign=Gtk.Align.CENTER)
        self.group_box.append(self.create)
        box.append(self.group_box)
        sw = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.results = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.results.add_css_class("boxed-list")
        self.results.set_valign(Gtk.Align.START)
        sw.set_child(self.results)
        box.append(sw)
        self.multi_btn = _button(_("+ Group with several people …"), ("flat",), self._toggle_multi, halign=Gtk.Align.START)
        box.append(self.multi_btn)
        tv.set_content(box)
        self.set_child(tv)
        self._update()
        self.q = ""
        GLib.idle_add(lambda: (self.search.grab_focus(), False)[1])

    def _toggle_multi(self):
        self.multi = not self.multi
        self.multi_btn.set_visible(not self.multi)
        self.group_box.set_visible(self.multi)
        self._update()

    def _update(self):
        while (c := self.chips.get_first_child()):
            self.chips.remove(c)
        for u, n in self.picked.items():
            chip = Gtk.Box(spacing=4)
            chip.add_css_class("hk-tag")
            chip.append(_lbl(n, wrap=False))
            chip.append(flat_button(icon="window-close-symbolic", css="hk-tiny", tooltip=_("Remove"),
                                    cb=lambda u=u: (self.picked.pop(u, None), self._update())))
            self.chips.append(chip)
        self.create.set_sensitive(bool(self.picked))

    def _search(self):
        q = self.search.get_text().strip()
        self.q = q
        self.results.remove_all()
        if len(q) < 3:
            return
        bg(client.get, "api/chat/people?q=" + GLib.Uri.escape_string(q, None, True), done=lambda j: self._show(q, j),
           error=lambda e: self._show(q, {"error": str(e)}))

    def _show(self, q, j):
        if q != self.q:
            return
        self.results.remove_all()
        users = [u for u in (j or {}).get("users") or [] if isinstance(u, dict)]
        groups = [g for g in (j or {}).get("groups") or [] if isinstance(g, dict)] if not self.multi else []
        for u in users:
            r = Adw.ActionRow(title=GLib.markup_escape_text(u.get("name") or u["username"]),
                              subtitle=GLib.markup_escape_text(u["username"] + ("" if u.get("ready") else " · " + _("hasn't set up chats yet"))),
                              activatable=True)
            r.add_prefix(Adw.Avatar(size=32, text=u.get("name") or u["username"], show_initials=True))
            r.connect("activated", lambda *_, u=u: self._pick_user(u))
            self.results.append(r)
        for g in groups:
            from .sharing import people_icon
            r = Adw.ActionRow(title=GLib.markup_escape_text(g.get("name") or ""), subtitle=_("Group chat for everyone in the group"),
                              activatable=True)
            av = Adw.Avatar(size=32, text=g.get("name") or "", show_initials=False)
            av.set_icon_name(people_icon())
            r.add_prefix(av)
            r.connect("activated", lambda *_, g=g: self._open({"kind": "group", "group": g["name"]}))
            self.results.append(r)
        if not users and not groups:
            self.results.append(Adw.ActionRow(title=GLib.markup_escape_text((j or {}).get("error") or _("No matches."))))

    def _pick_user(self, u):
        if self.multi:
            self.picked[u["username"]] = u.get("name") or u["username"]
            self.search.set_text("")
            self._update()
            return
        self._open({"kind": "dm", "user": u["username"]})

    def _open(self, body, then=None):
        def done(ch):
            self.close()
            if then:
                then(ch)
            if ch.get("id") is not None:
                self.win.show_route(("chat", ch["id"]))
        chat.new_chat(body, done, lambda e: self.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e)))))

    def _create_free(self):
        name = self.gname.get_text().strip()[:100]

        def titled(ch):
            if name and ch.get("id") is not None:
                cid = ch["id"]
                bg(chat.send, cid, {"v": 1, "type": "title", "title": name}, None, "title", done=lambda _r: chat.load_chat(cid),
                   error=lambda e: _toast(self.win, _("Not sent: {err}").format(err=e)))
        self._open({"kind": "free", "members": sorted(self.picked)}, then=titled)


def open_page_chat(win, nid):
    """Seiten-Chat: alle, die die Seite sehen duerfen (idempotent)"""
    if not chat.ready:
        win.show_root(("chats",))
        return
    chat.new_chat({"kind": "page", "note_id": int(nid)}, lambda ch: ch.get("id") is not None and win.show_route(("chat", ch["id"])),
                  lambda e: _toast(win, str(e)))


# --- Einrichtung, neues Geraet ---------------------------------------------------------------------------------
class RecoveryKeyPages:
    """Zwei Seiten (Adw.NavigationPage): Schluessel anzeigen (kopieren, als Datei speichern) und letzte vier Zeichen
    bestaetigen. on_confirmed(rk, page) erledigt den Rest (Hintergrund)."""

    def __init__(self, dlg, nav, rk, title, ok_label, on_confirmed):
        self.dlg, self.nav, self.rk, self.on_confirmed = dlg, nav, rk, on_confirmed
        # 1) anzeigen
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        b = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin_top=12, margin_bottom=18, margin_start=18, margin_end=18)
        b.append(_lbl(_("Write it down or store it somewhere safe (e.g. in a password manager). Saga only shows it now."), ("dim-label",)))
        kl = _lbl(fmt_recovery(rk), ("hk-recovery", "monospace"), xalign=0.5, selectable=True, justify=Gtk.Justification.CENTER)
        kl.update_property([Gtk.AccessibleProperty.LABEL], [_("Recovery key") + ": " + fmt_recovery(rk)])
        b.append(kl)
        bb = Gtk.Box(spacing=8, halign=Gtk.Align.CENTER)
        bb.append(_button(_("Copy"), ("pill",), self._copy))
        bb.append(_button(_("Save as file"), ("pill",), self._save))
        b.append(bb)
        b.append(_button(_("Continue"), ("pill", "suggested-action"), lambda: self.nav.push(self.p2), halign=Gtk.Align.CENTER))
        tv.set_content(b)
        self.p1 = Adw.NavigationPage(title=title, child=tv, tag="key")
        # 2) bestaetigen
        tv2 = Adw.ToolbarView()
        tv2.add_top_bar(Adw.HeaderBar())
        b2 = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin_top=12, margin_bottom=18, margin_start=18, margin_end=18)
        b2.append(_lbl(_("To confirm, enter the last four characters of your recovery key."), ("dim-label",)))
        lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lb.add_css_class("boxed-list")
        self.last4 = Adw.EntryRow(title=_("Last four characters"))
        self.last4.connect("entry-activated", lambda *_: self._confirm())
        lb.append(self.last4)
        b2.append(lb)
        self.msg = _lbl("", ("dim-label",))
        b2.append(self.msg)
        self.ok = _button(ok_label, ("pill", "suggested-action"), self._confirm, halign=Gtk.Align.CENTER)
        b2.append(self.ok)
        tv2.set_content(b2)
        self.p2 = Adw.NavigationPage(title=_("Confirm recovery key"), child=tv2, tag="confirm")

    def _copy(self):
        Gdk.Display.get_default().get_clipboard().set(fmt_recovery(self.rk))
        self.dlg.add_toast(Adw.Toast(title=_("Copied.")))

    def _save(self):
        fd = Gtk.FileDialog(title=_("Save as file"), initial_name="saga-recovery-key.txt")
        text = "\n".join([_("Saga – recovery key for chats"), _("Account: {name}").format(name=chat.uid or ""),
                          config.settings.server, "", fmt_recovery(self.rk), ""])

        def done(d, res):
            try:
                f = d.save_finish(res)
            except GLib.Error:
                return
            if f is not None:
                f.replace_contents(text.encode(), None, False, Gio.FileCreateFlags.PRIVATE | Gio.FileCreateFlags.REPLACE_DESTINATION, None)
                self.dlg.add_toast(Adw.Toast(title=GLib.markup_escape_text(_("Saved: {name}").format(name=f.get_basename()))))
        fd.save(self.dlg.get_root() if isinstance(self.dlg.get_root(), Gtk.Window) else None, None, done)

    def _confirm(self):
        n = C.norm_recovery(self.rk) or ""
        if (self.last4.get_text().strip().upper().replace("0", "O").replace("1", "I").replace("8", "B")) != n[-4:]:
            self.msg.set_label(_("That doesn't match – please enter exactly the last four characters."))
            self.msg.add_css_class("error")
            return
        self.msg.remove_css_class("error")
        self.msg.set_label(_("Securing keys …"))
        self.ok.set_sensitive(False)
        self.on_confirmed(self.rk, self)

    def failed(self, text):
        self.ok.set_sensitive(True)
        self.msg.set_label(text)
        self.msg.add_css_class("error")


class SetupDialog(Adw.Dialog):
    """Chats einrichten bzw. neu beginnen (reset): Erklaerung -> Wiederherstellungsschluessel -> bestaetigen -> fertig"""

    def __init__(self, win, reset=False):
        super().__init__(title=_("Start chats over") if reset else _("Set up chats"), content_width=480, content_height=560)
        self.win, self.reset = win, reset
        self.nav = Adw.NavigationView()
        self.toasts = Adw.ToastOverlay(child=self.nav)
        self.set_child(self.toasts)
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        b = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin_top=12, margin_bottom=18, margin_start=18, margin_end=18)
        b.append(_lbl(_("Saga chats are end-to-end encrypted: messages and attachments are encrypted and decrypted on your devices. "
                        "The server – including the admin – only sees encrypted data.")))
        b.append(_lbl(_("You'll get a recovery key in a moment. With it you can bring your chats to a new device when no other device "
                        "is left. Without it your messages are lost – the admin can't restore them either."), ("dim-label",)))
        if reset:
            w = _lbl(_("Note: starting over creates new keys. You won't be able to read earlier messages, and your contacts will see a "
                       "changed safety number."), ("hk-warn-text",))
            b.append(w)
        self.pw_box = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, visible=False)
        self.pw_box.add_css_class("boxed-list")
        self.pw = Adw.PasswordEntryRow(title=_("For your security: your password"))
        self.pw_box.append(self.pw)
        b.append(self.pw_box)
        self.relogin = _lbl(_("For your security, please sign in again first. You then have 5 minutes to start over."), ("hk-warn-text",),
                            visible=False)
        b.append(self.relogin)
        self.relogin_btn = _button(_("Sign in again"), ("pill",), self._relogin, halign=Gtk.Align.CENTER, visible=False)
        b.append(self.relogin_btn)
        self.err = _lbl("", ("error",), visible=False)
        b.append(self.err)
        b.append(_button(_("Create keys"), ("pill", "suggested-action"), self._create, halign=Gtk.Align.CENTER))
        tv.set_content(b)
        self.nav.add(Adw.NavigationPage(title=self.get_title(), child=tv, tag="intro"))
        if reset:
            self._need((chat.me or {}).get("reauth"))

    def add_toast(self, t):
        self.toasts.add_toast(t)

    def _need(self, need):
        self.pw_box.set_visible(need == "password")
        self.relogin.set_visible(need == "login")
        self.relogin_btn.set_visible(need == "login")

    def _relogin(self):
        self.close()
        self.win.start_login(config.settings.server)

    def _create(self):
        self.err.set_visible(False)
        rk = C.recovery_key()
        self.keys_pages = RecoveryKeyPages(self, self.nav, rk, _("Your recovery key"), _("Set up"), self._go)
        self.nav.push(self.keys_pages.p1)

    def _go(self, rk, pages):
        pw = self.pw.get_text() if self.reset else None

        def fin(k):
            chat.adopt_keys(k)
            self.close()
            _toast(self.win, _("Chats are set up."))

        def err(e):
            if isinstance(e, ChatError) and e.code == 403 and isinstance(e.data, dict) and e.data.get("reauth"):
                self._need(e.data["reauth"])
                self.nav.pop_to_tag("intro")
                self.err.set_label(str(e))
                self.err.set_visible(True)
                pages.failed("")
                return
            pages.failed(str(e))
        bg(chat.setup, rk, pw, self.reset, done=fin, error=err)


def ask_start_over(win):
    from .publish import confirm
    confirm(win, _("Start chats over?"),
            _("New keys: you won't be able to read earlier messages, your other devices will be signed out and your contacts will "
              "see a changed safety number."), _("Start over"), lambda: SetupDialog(win, reset=True).present(win))


class LinkDialog(Adw.Dialog):
    """Neues Geraet: Anfrage an ein vorhandenes Geraet, Code gross anzeigen, alle 3 s nachfragen (live: sofort)"""

    def __init__(self, win):
        super().__init__(title=_("Confirm with another device"), content_width=460)
        self.win = win
        self.lw = None
        self.src = 0
        self.busy = False
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        b = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin_top=12, margin_bottom=18, margin_start=18, margin_end=18)
        self.intro = _lbl(_("Sending request …"), ("dim-label",))
        b.append(self.intro)
        self.code = _lbl("", ("hk-linkcode", "monospace"), xalign=0.5, wrap=False, selectable=True, justify=Gtk.Justification.CENTER)
        b.append(self.code)
        self.note = _lbl("", ("caption", "dim-label"), xalign=0.5)
        b.append(self.note)
        b.append(_button(_("Cancel"), ("pill",), self.close, halign=Gtk.Align.CENTER))
        tv.set_content(b)
        self.set_child(tv)
        self.connect("closed", lambda *_: self._closed())
        self.h = chat.connect("link-done", lambda *_: self._poll())
        bg(chat.link_start, done=self._started, error=lambda e: self._fail(str(e)))

    def _started(self, lw):
        self.lw = chat.link_wait = lw
        self.intro.set_label(_("On a device where your chats already work, open “Chats”. This request appears there – confirm it if "
                               "the same code is shown:"))
        self.code.set_label(code_lines(lw["code"]))
        self.code.update_property([Gtk.AccessibleProperty.LABEL], [lw["code"]])
        self.note.set_label(_("The request is valid for 10 minutes."))
        self.src = GLib.timeout_add_seconds(3, lambda: (self._poll(), True)[1])

    def _poll(self):
        if self.lw is None or self.busy:
            return

        def fin(r):
            self.busy = False
            st, k = r
            if st == "approved":
                self.lw = None
                chat.link_wait = None
                chat.adopt_keys(k)
                _toast(self.win, _("This device is now set up for chats."))
                self.close()
            elif st in ("expired", "gone"):
                self._fail(_("The request has expired or was declined."))

        def err(e):
            self.busy = False
            if isinstance(e, ChatError):
                self._fail(str(e))
        self.busy = True
        bg(chat.link_poll, self.lw, done=fin, error=err)

    def _fail(self, text):
        if self.src:
            GLib.source_remove(self.src)
            self.src = 0
        self.lw = chat.link_wait = None
        self.intro.set_label(text)
        self.code.set_label("")
        self.note.set_label("")

    def _closed(self):
        if self.src:
            GLib.source_remove(self.src)
            self.src = 0
        chat.disconnect(self.h)
        if self.lw is not None:
            chat.link_cancel(self.lw)
            chat.link_wait = None


class RecoveryDialog(Adw.Dialog):
    def __init__(self, win):
        super().__init__(title=_("Enter recovery key"), content_width=460)
        self.win = win
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        b = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin_top=12, margin_bottom=18, margin_start=18, margin_end=18)
        b.append(_lbl(_("The key you got during setup."), ("dim-label",)))
        lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lb.add_css_class("boxed-list")
        self.entry = Adw.EntryRow(title=_("Recovery key"))
        self.entry.connect("entry-activated", lambda *_: self._go())
        lb.append(self.entry)
        b.append(lb)
        self.msg = _lbl("", ("dim-label",))
        b.append(self.msg)
        self.btn = _button(_("Unlock"), ("pill", "suggested-action"), self._go, halign=Gtk.Align.CENTER)
        b.append(self.btn)
        tv.set_content(b)
        self.set_child(tv)

    def _go(self):
        rk = self.entry.get_text()
        if not C.norm_recovery(rk):
            self.msg.set_label(_("A recovery key has 32 characters (A–Z, 2–7)."))
            return
        self.msg.set_label(_("Checking …"))
        self.btn.set_sensitive(False)

        def fin(k):
            chat.adopt_keys(k)
            _toast(self.win, _("This device is now set up for chats."))
            self.close()

        def err(e):
            self.btn.set_sensitive(True)
            self.msg.set_label(str(e))
        bg(chat.unlock, rk, done=fin, error=err)


def approve_link(win, ln):
    """Vorhandenes Geraet: neues Geraet bestaetigen (Code vergleichen!)"""
    code = chat.link_code_for(ln)
    if not code:
        return
    d = Adw.AlertDialog(heading=_("Confirm new device"),
                        body=_("“{name}” wants to access your chats.").format(name=ln.get("name") or _("Unknown device")) + "\n\n" +
                        _("Only confirm if the new device shows exactly this code:"))
    cl = _lbl(code_lines(code), ("hk-linkcode", "monospace"), xalign=0.5, wrap=False, justify=Gtk.Justification.CENTER)
    cl.update_property([Gtk.AccessibleProperty.LABEL], [code])
    d.set_extra_child(cl)
    d.add_response("no", _("Decline"))
    d.add_response("ok", _("Code matches – confirm"))
    d.set_response_appearance("ok", Adw.ResponseAppearance.SUGGESTED)
    d.set_response_appearance("no", Adw.ResponseAppearance.DESTRUCTIVE)
    d.set_default_response("no")
    d.set_close_response("close")

    def resp(dd, r):
        if r == "ok":
            bg(chat.link_approve, ln, done=lambda _r: (_toast(win, _("Device confirmed.")), chat.refresh()), error=lambda e: _toast(win, str(e)))
        elif r == "no":
            chat.link_decline(ln)
    d.connect("response", resp)
    d.present(win)
    return d


# --- Einstellungen: Chats & Geraete --------------------------------------------------------------------------------
class ChatDevicesPage(Adw.PreferencesPage):
    def __init__(self, dlg):
        super().__init__(title=_("Chats & devices"), icon_name=chat_icon())
        self.dlg = dlg
        self.groups = []
        watch_chat(self, "changed", lambda: self.fill())
        self.fill()
        chat.refresh()

    def fill(self):
        for g in self.groups:
            self.remove(g)
        self.groups = []
        me = chat.me or {}
        g = Adw.PreferencesGroup(title=_("Devices with access to your chats"))
        if chat.status == "off":
            g.set_description(_("Chats are turned off on this server."))
        elif chat.status == "setup":
            g.set_description(_("Chats are not set up yet – open “Chats” in the sidebar."))
        for d in me.get("devices") or []:
            if d.get("revoked"):
                continue
            this = d.get("id") == chat.device_id
            sub = ([_("this device")] if this else []) + [_("added {when}").format(when=date_short(d.get("created")))]
            if d.get("last_seen"):
                sub.append(_("last seen {when}").format(when=rel_time(d.get("last_seen"))))
            r = Adw.ActionRow(title=GLib.markup_escape_text(d.get("name") or "?"), subtitle=GLib.markup_escape_text(" · ".join(sub)))
            r.add_prefix(Gtk.Image.new_from_icon_name("computer-symbolic" if this else "phone-symbolic"))
            rm = _button(_("Remove from this device") if this else _("Remove"), ("destructive-action",), lambda d=d, this=this: self._remove(d, this),
                         valign=Gtk.Align.CENTER)
            r.add_suffix(rm)
            g.add(r)
        self.add(g)
        self.groups.append(g)
        if chat.ready:
            g2 = Adw.PreferencesGroup(title=_("Recovery key"),
                                      description=_("Lost it or someone may have seen it? Create a new one – the old one stops working. "
                                                    "Your keys and messages stay the same."))
            from .publish import button_row
            g2.add(button_row(_("Create new recovery key"), self._new_key))
            self.add(g2)
            self.groups.append(g2)
        g3 = Adw.PreferencesGroup(title=_("Security"),
                                  description=_("Private keys never leave this device unencrypted. The server only holds public keys, "
                                                "encrypted messages and your key bundle encrypted with the recovery key. Compare safety "
                                                "numbers to make sure nobody is in between."))
        if not keys_persistent():
            g3.add(Adw.ActionRow(title=_("No keyring available – the chat keys are only kept until Saga is closed.")))
        self.add(g3)
        self.groups.append(g3)

    def _remove(self, d, this):
        from .publish import confirm
        body = _("The keys will be deleted from this device. Afterwards you'll need another device or the recovery key.") if this else \
            _("The device will no longer receive new messages and can't fetch existing ones.")
        confirm(self.dlg, _("Remove device?"), body, _("Remove"),
                lambda: chat.remove_device(d["id"], error=lambda e: self.dlg.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e))))))

    def _new_key(self):
        from .publish import confirm
        confirm(self.dlg, _("Create a new recovery key?"), _("The previous key will stop working."), _("Create"),
                lambda: NewKeyDialog(self.dlg.win).present(self.dlg.win), destructive=False)


class NewKeyDialog(Adw.Dialog):
    def __init__(self, win):
        super().__init__(title=_("New recovery key"), content_width=480, content_height=500)
        self.win = win
        self.nav = Adw.NavigationView()
        self.toasts = Adw.ToastOverlay(child=self.nav)
        self.set_child(self.toasts)
        self.pages = RecoveryKeyPages(self, self.nav, C.recovery_key(), _("New recovery key"), _("Apply"), self._go)
        self.nav.add(self.pages.p1)

    def add_toast(self, t):
        self.toasts.add_toast(t)

    def _go(self, rk, pages):
        bg(chat.new_recovery, rk, done=lambda _r: (self.close(), _toast(self.win, _("The new recovery key is valid from now on."))),
           error=lambda e: pages.failed(str(e)))
