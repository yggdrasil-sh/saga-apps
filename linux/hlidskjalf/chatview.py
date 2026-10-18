"""Chats (Server 1.5) - Oberflaeche: Liste und Unterhaltung im Hauptbereich (breit nebeneinander, schmal nacheinander),
Einrichtung mit Wiederherstellungsschluessel, neues Geraet (Kopplung/Wiederherstellung/Neu beginnen), Bestaetigen neuer
Geraete, Sicherheitsnummer (+ QR-Code), Mitglieder und Einstellungen eines Chats, "Chats & Geraete" in den Einstellungen.
Nachrichtentexte werden nie als Markup interpretiert (escape; nur eigene Links fuer Adressen und [[note:ID]])."""
import datetime as _dt
import re

from gi.repository import Adw, Gdk, Gio, GLib, Graphene, Gsk, Gtk, Pango

from . import chatcrypto as C
from . import aichat, avatars, chatmedia, clipimg, config, images
from .api import APIError, client
from .blocks import flat_button, menu_popover
from .chat import (NOTE_RX, RETENTIONS, ChatError, SafetyChanged, chat, fmt_recovery, keys_persistent, recovery_lines, retention_label,
                   fed_guide_url, fed_hint_show, fed_hint_texts, safety_for, safety_qr_text, trust_accept, trust_get, trust_verified)
from .i18n import _, ngettext, pgettext, ui_language
from .util import bg, date_short, esc, human_size, rel_time, time_text, weekday_day_text

URL_RX = re.compile(r"https?://[^\s<>\"']+[^\s<>\"'.,;:!?)\]]")


def chat_icon():
    from .publish import _icon
    return _icon("saga-chat-symbolic", "chat-message-new-symbolic", "mail-message-new-symbolic")


def send_icon():
    from .publish import _icon
    return _icon("saga-send-symbolic", "mail-send-symbolic", "go-up-symbolic")


def watch_chat(widget, signal, cb, obj=None):
    """Signal von chat (bzw. obj) nur, solange das Widget sichtbar ist"""
    st = {"h": None}
    src = chat if obj is None else obj

    def on_map(*_):
        if st["h"] is None:
            st["h"] = src.connect(signal, lambda *a: cb(*a[1:]))

    def on_unmap(*_):
        if st["h"] is not None:
            src.disconnect(st["h"])
            st["h"] = None
    widget.connect("map", on_map)
    widget.connect("unmap", on_unmap)


def code_halves(code):
    """Kopplungscode "12345 67890 12345 67890" -> ["12345 67890", "12345 67890"] (zwei Haelften, je 2 Gruppen)"""
    parts = (code or "").split()
    if len(parts) <= 2:
        return [" ".join(parts)] if parts else []
    half = (len(parts) + 1) // 2
    return [" ".join(parts[:half]), " ".join(parts[half:])]


class CodeView(Gtk.FlowBox):
    """Kopplungscode, Monospace: in EINER Zeile, nur wenn sie nicht passt zwei Zeilen zu je 2 Gruppen"""
    __gtype_name__ = "HKCodeView"

    def __init__(self, code=""):
        super().__init__(selection_mode=Gtk.SelectionMode.NONE, min_children_per_line=1, max_children_per_line=2,
                         homogeneous=True, halign=Gtk.Align.CENTER, column_spacing=0, row_spacing=2)
        self.add_css_class("hk-codeview")
        self.set_code(code)

    def set_code(self, code):
        self.code = code or ""
        self.remove_all()
        for h in code_halves(code):
            lb = Gtk.Label(label=h, xalign=0.5)
            lb.add_css_class("hk-linkcode")
            lb.add_css_class("monospace")
            self.append(lb)
        c = self.get_first_child()
        while c is not None:
            c.set_focusable(False)
            c = c.get_next_sibling()
        self.update_property([Gtk.AccessibleProperty.LABEL], [self.code])

    def text(self):
        """sichtbare Zeilen (fuer Tests): Kinder je Zeile"""
        rows = {}
        c = self.get_first_child()
        while c is not None:
            rows.setdefault(c.get_allocation().y, []).append(c.get_child().get_label())
            c = c.get_next_sibling()
        return [" ".join(v) for _k, v in sorted(rows.items())]


def _lbl(text="", css=(), xalign=0, wrap=True, **kw):
    lb = Gtk.Label(label=text, xalign=xalign, wrap=wrap, **kw)
    if wrap:
        lb.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
    for c in css:
        lb.add_css_class(c)
    return lb


def fed_hint():
    """Hinweis statt "Keine Treffer" bei name@server oder E-Mail: Text + Link zur Anleitung (Foederation)"""
    text, link = fed_hint_texts()
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, margin_top=8, margin_bottom=8, margin_start=12, margin_end=12)
    box.add_css_class("hk-fed-hint")
    box.append(_lbl(text, ("dim-label",)))
    box.append(Gtk.LinkButton(uri=fed_guide_url(ui_language()), label=link, halign=Gtk.Align.START))
    return box


def warn_label(text):
    """wichtige Warnung: Symbol + Text fett in Vordergrundfarbe (nicht grau)"""
    bx = Gtk.Box(spacing=8)
    ic = Gtk.Image.new_from_icon_name("dialog-warning-symbolic")
    ic.add_css_class("hk-warn-text")
    ic.set_valign(Gtk.Align.START)
    bx.append(ic)
    bx.append(_lbl(text, ("heading",), hexpand=True))
    bx.add_css_class("hk-warn-box")
    return bx


def keyring_help(win):
    d = Adw.AlertDialog(heading=_("No keyring available"),
                        body=_("Saga keeps the chat keys of this device in the system keyring (Secret Service). Install and unlock "
                               "a keyring – e.g. GNOME Keyring (package gnome-keyring) or KWallet – and start Saga again. Until "
                               "then the keys are only kept until Saga is closed; after that, this device needs the recovery key "
                               "or another device again."))
    d.add_response("close", _("Close"))
    d.present(win)


def keyring_banner(win):
    """Hinweis oben: ohne Schluesselbund gelten die Chat-Schluessel nur bis zum Beenden - mit naechstem Schritt"""
    b = Adw.Banner(title=_("No keyring available – the chat keys are only kept until Saga is closed."), revealed=True,
                   button_label=_("What to do?"))
    b.connect("button-clicked", lambda *_: keyring_help(win))
    return b


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
    if m.get("state") == "call":
        from .calls import entry_text
        return "📞 " + entry_text(m.get("call") or {}, chat.uid)
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
        watch_chat(self, "chat-gone", self._chat_gone)
        self.connect("unmap", lambda *_: setattr(chat, "open_chat", None))
        self._state = None
        self.render(force=True)
        if chat.status in ("unknown",) or chat.status == "ready" and not chat.chats:
            chat.refresh()
        else:
            chat.revalidate(20)                    # Chats wieder geoeffnet: neue Profilbilder/Namen der Gegenueber

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
                  "is left."))
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
                                          lambda: RecoveryDialog(self.win).present(self.win)),
                                         (_("Start over"), _("New keys – older messages can't be read afterwards."),
                                          "view-refresh-symbolic", lambda: ask_start_over(self.win))):
                r = Adw.ActionRow(title=title, subtitle=sub, activatable=True)
                r.add_prefix(Gtk.Image.new_from_icon_name(icon))
                r.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
                r.connect("activated", lambda *_, cb=cb: cb())
                lb.append(r)
            clamp.append(lb)
        if st == "setup":
            clamp.prepend(warn_label(_("Without it your messages are lost – the admin can't restore them either.")))
        if clamp.get_first_child() is not None:
            page.set_child(Adw.Clamp(maximum_size=440, child=clamp))
        if st in ("setup", "device") and not keys_persistent():
            self.status_box.append(keyring_banner(self.win))
        self.status_box.append(page)

    def _chat_gone(self, cid, how, _name):
        """(2.1) offener Chat fuer alle geloescht bzw. Direkt-Chat fuer mich geloescht: Unterhaltung schliessen"""
        if cid == self.cid and how in ("removed", "hidden"):
            GLib.idle_add(lambda: (self.win.show_root(("chats",)), False)[1])

    def _header(self):
        if self.win.current is self:
            self.win.update_header()

    def header(self):
        """-> (titel, untertitel) fuer die Kopfleiste des Fensters"""
        c = chat.chat(self.cid) if self.cid is not None else None
        if c is None:
            return _("Chats"), ""
        arch = " · " + _("Archived") if c.get("archived") else ""
        if c.get("kind") == "dm":
            return chat.title(c), _("End-to-end encrypted") + arch
        n = int(c.get("member_count") or len(c.get("members") or []))
        return chat.title(c), chat.kind_label(c) + " · " + ngettext("{n} person", "{n} people", n).format(n=n) + arch

    def has_menu(self):
        return self.conv is not None and chat.chat(self.cid) is not None

    def call_chat(self):
        """Direkt-Chat mit eingeschalteten Anrufen -> Chat-ID (Knopf "Anrufen" in der Kopfleiste), sonst None"""
        from .calls import calls
        c = chat.chat(self.cid) if self.cid is not None and self.conv is not None else None
        return self.cid if c and c.get("kind") == "dm" and calls.enabled else None

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
        self.box.connect("row-activated", lambda lb, row: self._activated(row))
        sw.set_child(self.box)
        self.append(sw)
        self.sig = None

    arch_open = False            # Bereich "Archiviert" aufgeklappt (fuer die Sitzung, ueber Neuaufbauten hinweg)

    def _activated(self, row):
        if getattr(row, "arch_toggle", False):
            ChatList.arch_open = not ChatList.arch_open
            self.fill()
            for r in _walk_rows(self.box):                  # Fokus bleibt auf dem Kopf des Bereichs
                if getattr(r, "arch_toggle", False):
                    r.grab_focus()
            return
        if getattr(row, "cid", None) is not None:
            self.screen.open(row.cid)

    def fill(self):
        q = self.search.get_text().strip().lower()
        lst = [c for c in chat.chats if not q or q in chat.title(c).lower()]
        act = [c for c in lst if not c.get("archived")]
        arch = [c for c in lst if c.get("archived")]       # (2.1) eigener Bereich unten, bei einer Suche aufgeklappt
        show_arch = ChatList.arch_open or bool(q)
        sig = (q, self.screen.cid, show_arch, tuple((c.get("id"), chat.title(c), c.get("unread"), c.get("muted"), c.get("last_at"),
                                                     c.get("archived"), (chat.peer(c) or {}).get("avatar") if c.get("kind") == "dm" else None)
                                                    for c in lst))
        if sig == self.sig:
            return
        self.sig = sig
        self.box.remove_all()
        if not act and not arch:
            lb = _lbl(_("No matches.") if q else _("No chats yet."), ("dim-label",), margin_start=10, margin_top=10)
            self.box.append(Gtk.ListBoxRow(activatable=False, selectable=False, child=lb))
        for c in act:
            self.box.append(self._row(c))
        if arch:
            self.box.append(self._arch_head(arch, show_arch))
            if show_arch:
                for c in arch:
                    self.box.append(self._row(c))

    def _arch_head(self, arch, shown):
        """Kopf des Bereichs "Archiviert (n)": aufklappen/zuklappen, Ungelesen dort als eigene Zahl"""
        row = Gtk.ListBoxRow()
        row.arch_toggle = True
        row.add_css_class("hk-arch-head")
        box = Gtk.Box(spacing=8, margin_top=6, margin_bottom=6, margin_start=4)
        box.append(Gtk.Image.new_from_icon_name("pan-down-symbolic" if shown else "pan-end-symbolic"))
        t = _lbl(_("Archived ({n})").format(n=len(arch)), ("heading",), hexpand=True, wrap=False)
        box.append(t)
        un = sum(int(c.get("unread") or 0) for c in arch)
        if un:
            b = Gtk.Label(label=str(un))
            b.add_css_class("hk-badge")
            box.append(b)
        row.set_child(box)
        acc = _("Archived ({n})").format(n=len(arch)) + (" – " + ngettext("{n} unread", "{n} unread", un).format(n=un) if un else "")
        row.update_property([Gtk.AccessibleProperty.LABEL], [acc])
        from .window import set_expanded_state
        set_expanded_state(row, shown)
        return row

    def _menu(self, row, c, x=None, y=None):
        """Kontextmenue eines Chats (Rechtsklick, Langdruck, Menue-Taste/Umschalt+F10): Oeffnen, Stumm, Archiv, Loeschen"""
        win = self.screen.win
        cid = c["id"]
        ents = [(_("Open"), lambda: self.screen.open(cid)),
                (_("Unmute") if c.get("muted") else _("Mute"),
                 lambda: chat.patch(cid, {"muted": not c.get("muted")}, done=lambda _c: chat.load_chats(),
                                    error=lambda e: _toast(win, str(e))))]
        ents += manage_entries(win, c)
        pop = menu_popover(self.box, ents)
        ok, pt = row.compute_point(self.box, Graphene.Point().init(x if x is not None else 24, y if y is not None else row.get_height() / 2))
        if ok:
            r = Gdk.Rectangle()
            r.x, r.y, r.width, r.height = int(pt.x), int(pt.y), 1, 1
            pop.set_pointing_to(r)
        pop.popup()
        return pop

    def _row(self, c):
        row = Gtk.ListBoxRow()
        row.cid = c["id"]
        if c["id"] == self.screen.cid:
            row.add_css_class("hk-active")
        box = Gtk.Box(spacing=10, margin_top=4, margin_bottom=4)
        title = chat.title(c)
        av = Adw.Avatar(size=36, text=title, show_initials=c.get("kind") in ("dm", "free"))
        if c.get("kind") == "dm":
            avatars.attach(av, (chat.peer(c) or {}).get("avatar"), 36)     # Profilbild des Gegenuebers (2.1)
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
        # Kontextmenue (2.1): Rechtsklick, Langdruck (Touch), Menue-Taste bzw. Umschalt+F10
        rc = Gtk.GestureClick(button=Gdk.BUTTON_SECONDARY)

        def on_right(g, _n, x, y):
            g.set_state(Gtk.EventSequenceState.CLAIMED)
            self._menu(row, c, x, y)
        rc.connect("pressed", on_right)
        row.add_controller(rc)
        lp = Gtk.GestureLongPress(touch_only=True)

        def longp(g, x, y):
            g.set_state(Gtk.EventSequenceState.CLAIMED)
            self._menu(row, c, x, y)
        lp.connect("pressed", longp)
        row.add_controller(lp)
        kc = Gtk.EventControllerKey()

        def key(_k, kv, _code, state):
            if kv == Gdk.KEY_Menu or (kv == Gdk.KEY_F10 and state & Gdk.ModifierType.SHIFT_MASK):
                self._menu(row, c)
                return True
            return False
        kc.connect("key-pressed", key)
        row.add_controller(kc)
        return row


def _walk_rows(lb):
    r = lb.get_first_child()
    while r is not None:
        yield r
        r = r.get_next_sibling()


def _short_when(ts):
    if not ts:
        return ""
    d = _dt.datetime.fromtimestamp(float(ts))
    if d.date() == _dt.date.today():
        return time_text(d)
    return date_short(ts)


def _who(m):
    """Absender fuer das Gruppieren von Blasen: bestaetigte KI-Antworten zaehlen als eigener Absender"""
    u = (m.get("sender") or {}).get("username")
    return ("\x00ai", u) if m.get("ai_state") == "ok" else u                  # je Fragender ein eigener Kopf ("gefragt von")


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
        self.bubbles = {}            # Schluessel (ID, Zustand, Darstellung) -> fertige Zeile (wiederverwendet beim Neuzeichnen)
        self.img_widgets = []        # ChatImage aller angezeigten Zeilen, in Reihenfolge
        self._imgs_building = None
        self._vis_pending = 0
        self._pinned = 0
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
        # KI im Chat (2.1): sichtbarer Hinweis, solange die KI hier erlaubt ist - was an die KI geht
        self.ai_note = Gtk.Box(spacing=8, visible=False, margin_start=12, margin_end=12, margin_top=6)
        self.ai_note.add_css_class("hk-ai-note")
        self.append(self.ai_note)
        self._ai_sig = None
        self.pending_row = None
        from .assistant import ai
        p = ai.persona() or {}
        self._persona_sig = (p.get("name"), p.get("avatar"), p.get("avatar_etag"))   # KI-Antworten neu zeichnen, wenn sie sich aendert
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
        self.clamp = clamp
        self.scroll.get_vadjustment().connect("value-changed", self._scrolled)
        self.scroll.get_vadjustment().connect("changed", self._vis_soon)
        self.append(self.scroll)
        self.typing_label = _lbl("", ("caption", "dim-label"), margin_start=16, margin_bottom=2, visible=False)
        self.append(self.typing_label)
        self.append(self._composer())
        watch_chat(self, "messages", lambda cid: cid == self.cid and self.render())
        watch_chat(self, "typing", lambda cid: cid == self.cid and self._typing())
        watch_chat(self, "chat-changed", lambda cid: cid == self.cid and self._chat_changed())
        watch_chat(self, "ai-pending", lambda cid: cid == self.cid and self._pending_changed())
        watch_chat(self, "chat-gone", lambda cid, how, _n: cid == self.cid and self._wiped())
        from .assistant import ai
        watch_chat(self, "changed", lambda: self._ai_changed(), obj=ai)
        self.connect("map", lambda *_: self._mapped())
        self.connect("map", self._vis_soon)
        self.loaded = False
        self.render()
        if not chat.cache.get(cid, {}).get("loaded"):
            chat.load_messages(cid, done=self._first_load)
        else:
            chat.load_messages(cid)
            self._first_load()
        if chat.chat(cid) is None:
            chat.load_chat(cid, error=self._load_error)
        self._ai_note()
        self._pending_changed()

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
        if chat.ready:                                    # Sicherheitsnummern gleich beim Oeffnen pruefen (BUG-076)
            bg(chat.verify_recipients, self.cid, done=lambda _r: self._check_trust(), error=lambda e: None)
        GLib.idle_add(lambda: (self.entry.grab_focus(), False)[1])

    def _mapped(self):
        chat.open_chat = self.cid

    def _wiped(self):
        """(2.1) Verlauf geleert bzw. Chat geloescht: Entwurf (Text, Antwort, Anhaenge) verwerfen, Ansicht neu"""
        self.reply = None
        self.reply_bar.set_visible(False)
        self.files = []
        self._files_bar()
        self.entry.get_buffer().set_text("")
        self.mentions = {}
        self.sig = None
        self.render()

    def _chat_changed(self):
        self._check_trust()
        self.screen._header()
        self._ai_note()
        self._ai_typing()
        self.render()                                     # z. B. neue Grenze cleared_seq (2.1)

    # --- KI im Chat (2.1): nur auf Zuruf ---------------------------------------------------------------------------
    def _ai_on(self):
        return chat.ai_on(chat.chat(self.cid))

    def _ai_changed(self):
        """Persona oder Schalter geaendert (ai.persona.changed): Hinweis, Vorschlag und KI-Antworten neu zeichnen"""
        from .assistant import ai
        self._ai_note()
        self._ai_typing()
        p = ai.persona() or {}
        psig = (p.get("name"), p.get("avatar"), p.get("avatar_etag"))
        if psig == self._persona_sig:
            return
        self._persona_sig = psig
        for row in self.bubbles.values():                 # KI-Antworten mit neuem Namen/Bild neu zeichnen
            for w in row.hk_imgs:
                w.drop()
        self.sig = None
        self.bubbles = {}
        self.render()

    def _ai_note(self):
        """Hinweis oben: KI hier erlaubt, nur auf Zuruf - was im Klartext an wen geht"""
        from .assistant import ai, ai_avatar, ai_name
        c = chat.chat(self.cid) or {}
        on = bool(c.get("ai")) and (ai.chat_allowed or (ai.known and not ai.enabled))
        p = ai.persona() or {}
        sig = (on, ai_name(), p.get("avatar"), p.get("avatar_etag"), ai.status.get("host"), ai.enabled)
        if sig == self._ai_sig:
            return
        self._ai_sig = sig
        while (w := self.ai_note.get_first_child()):
            self.ai_note.remove(w)
        self.ai_note.set_visible(on)
        if not on:
            return
        self.ai_note.append(ai_avatar(20, valign=Gtk.Align.START))
        if ai.enabled:
            t = _("{name} is allowed in this chat – only when mentioned: messages with @{name} and up to {n} messages before go to "
                  "the AI ({host}) in plain text. Everything else stays end-to-end encrypted.").format(
                name=ai_name(), n=aichat.CTX_MAX, host=ai.status.get("host") or "?")
        else:                                          # Assistent fuer mich aus: trotzdem sagen, was andere tun koennen
            t = _("{name} is allowed in this chat – only when mentioned: messages with @{name} and up to {n} messages before go to "
                  "the AI in plain text. Everything else stays end-to-end encrypted.").format(name=ai_name(), n=aichat.CTX_MAX)
        self.ai_note.append(_lbl(t, ("caption",), hexpand=True))

    def _ai_toggle(self):
        from .assistant import ai_name
        c = chat.chat(self.cid) or {}
        on = not c.get("ai")
        name = ai_name()

        def go():
            chat.set_ai(self.cid, on, done=lambda: _toast(self.win, (_("{name} is now allowed in this chat.") if on else
                                                                       _("{name} is no longer allowed here.")).format(name=name)),
                        error=lambda e: _toast(self.win, str(e)))
        if not on:
            go()
            return
        from .assistant import ai
        from .publish import confirm
        confirm(self.win, _("Allow {name} in this chat?").format(name=name),
                _("Everyone in the chat can then ask {name} with @{name}. Only that message and up to {n} messages before go to "
                  "the AI ({host}) in plain text – the server doesn't store them. All members see a notice.").format(
                    name=name, n=aichat.CTX_MAX, host=ai.status.get("host") or "?"),
                _("Allow"), go, destructive=False)

    def _ai_typing(self, buf=None):
        """Beim Schreiben: "@Ma…" -> Vorschlag der KI; Nachricht mit @Name -> Hinweis, dass sie an die KI geht"""
        if not hasattr(self, "ai_hint"):
            return
        from .assistant import ai, ai_avatar, ai_name
        buf = buf or self.entry.get_buffer()
        on = self._ai_on()
        name = ai_name()
        text = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False) if on else ""
        before = buf.get_text(buf.get_start_iter(), buf.get_iter_at_mark(buf.get_insert()), False) if on else ""
        pref = aichat.typed_prefix(before) if on else None
        self.ai_pref = pref if pref and aichat.suggest(pref[1], name) else None
        self.ai_suggest.set_visible(self.ai_pref is not None)
        if self.ai_pref is not None and getattr(self, "_sugg_name", None) != name:
            self._sugg_name = name
            bx = Gtk.Box(spacing=6)
            bx.append(ai_avatar(20, valign=Gtk.Align.CENTER))
            bx.append(_lbl("@" + name, ("heading",), wrap=False))
            bx.append(_lbl(_("Ask the AI"), ("caption", "dim-label"), wrap=False))
            self.ai_suggest.set_child(bx)
            self.ai_suggest.set_tooltip_text(_("Ask {name}").format(name=name) + " (Tab)")
        show = on and aichat.mentions(text, name)
        if show and getattr(self, "_hint_name", None) != (name, ai.status.get("host")):
            self._hint_name = (name, ai.status.get("host"))
            while (w := self.ai_hint.get_first_child()):
                self.ai_hint.remove(w)
            self.ai_hint.append(ai_avatar(16, valign=Gtk.Align.CENTER))
            self.ai_hint.append(_lbl(_("Goes to {name}: this message and up to {n} before, in plain text to {host}.").format(
                name=name, n=aichat.CTX_MAX, host=ai.status.get("host") or "?"), ("caption",), hexpand=True))
        self.ai_hint.set_visible(show)

    def _ai_apply(self):
        """Vorschlag uebernehmen: "@Ma" -> "@Marin " (mit Leerzeichen)"""
        from .assistant import ai_name
        pref = getattr(self, "ai_pref", None)
        if not pref:
            return False
        buf = self.entry.get_buffer()
        it = buf.get_iter_at_mark(buf.get_insert())
        start = buf.get_iter_at_offset(it.get_offset() - len(pref[1]) - 1)
        buf.delete(start, it)
        buf.insert_at_cursor("@" + ai_name() + " ")
        self.entry.grab_focus()
        return True

    def _insert_ai(self):
        """@-Menue: KI als erster Vorschlag -> "@Marin " an der Schreibmarke"""
        from .assistant import ai_name
        buf = self.entry.get_buffer()
        it = buf.get_iter_at_mark(buf.get_insert())
        before = buf.get_text(buf.get_start_iter(), it, False)
        buf.insert_at_cursor(("" if not before or before[-1].isspace() else " ") + "@" + ai_name() + " ")
        self.entry.grab_focus()

    def _pending_changed(self):
        """Laufende Antwort der KI (dieses Geraet hat gefragt): "Marin denkt nach …", dann mitlaufender Text"""
        from .assistant import ai_head, thinking_text
        p = chat.ai_pending.get(self.cid)
        if p is None:
            if self.pending_row is not None:
                if self.pending_row.get_parent() is not None:
                    self.msgs.remove(self.pending_row)
                self.pending_row = None
            return
        if self.pending_row is None:
            row = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3, halign=Gtk.Align.START, margin_top=6)
            row.append(ai_head(20))
            b = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            b.add_css_class("hk-bubble")
            b.add_css_class("hk-bubble-ai")
            self.pending_label = Gtk.Label(xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, max_width_chars=60, selectable=True)
            self.pending_label.add_css_class("hk-bubble-text")
            b.append(self.pending_label)
            row.append(b)
            self.pending_row = row
        if self.pending_row.get_parent() is None:
            self.msgs.append(self.pending_row)
        txt = p.get("text") or ""
        self.pending_label.set_label(txt or thinking_text())
        (self.pending_label.remove_css_class if txt else self.pending_label.add_css_class)("dim-label")
        if self.at_bottom:
            self._to_bottom()

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
        self._vis_soon()

    # --- Bilder nur fuer sichtbare Zeilen (2.1-c) ---------------------------------------------------------------------
    def _vis_soon(self, *_a):
        if not self._vis_pending:
            self._vis_pending = GLib.timeout_add(40, self._update_visible)

    def _update_visible(self):
        """sichtbar (+ eine Bildschirmhoehe davor/danach): Vorschaubild laden; weit weg (> 4 Hoehen): Textur freigeben"""
        self._vis_pending = 0
        if self.get_root() is None or not self.loaded or self._pinned:
            return False
        adj = self.scroll.get_vadjustment()
        top, page = adj.get_value(), adj.get_page_size() or 600
        near, inview, missed = [], [], False
        for w in self.img_widgets:
            # Lage im Inhalt (haengt nicht davon ab, ob nach dem Scrollen schon neu angeordnet wurde) minus Scrollstand
            ok, r = w.compute_bounds(self.clamp)
            if not ok or w.get_height() <= 0:
                missed = True                             # noch nicht angeordnet (langsamer Rechner): gleich noch einmal
                continue
            y0 = r.get_y() - top
            y1 = y0 + r.get_height()
            if y1 >= 0 and y0 <= page:
                inview.append(w)
            elif y1 > -page and y0 < 2 * page:
                near.append(w)
            elif y1 < -4 * page or y0 > 5 * page:
                w.drop()
        for w in near:
            w.want()
        for w in reversed(inview):                        # zuletzt angefragt = zuerst geladen (sichtbare zuerst)
            w.want(bump=True)
        if missed and self.get_mapped():
            self._vis_soon()
        return False

    def _to_bottom(self):
        def go(last=False):
            adj = self.scroll.get_vadjustment()
            adj.set_value(adj.get_upper() - adj.get_page_size())
            if last:
                self._pinned -= 1
                self._vis_soon()
            return False
        self._pinned += 1                                 # bis zum Ende nach unten: keine Bilder fuer Zwischenstaende laden
        GLib.idle_add(go)
        GLib.timeout_add(120, go, True)

    def render(self):
        c = chat.cache.get(self.cid) or {}
        msgs = c.get("msgs") or []
        q = self.query.lower()
        shown = [m for m in msgs if not q or q in (m.get("text") or "").lower()]
        # (2.1) Verlauf geloescht (fuer mich oder fuer alle): Hinweis am Anfang statt "nicht lesbar"
        cleared = bool(int((chat.chat(self.cid) or {}).get("cleared_seq") or 0) or c.get("floor"))
        sig = (tuple((m["id"], m.get("state"), m.get("ai_state")) for m in shown), c.get("more"), q, len(chat.notes), c.get("error"),
               self.loaded, cleared)
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
            if cleared:
                cl = _lbl(_("The earlier history was deleted."), ("caption", "dim-label", "hk-cleared"), xalign=0.5,
                          justify=Gtk.Justification.CENTER, margin_bottom=8)
                self.msgs.append(cl)
        if c.get("error") and not msgs:
            self.msgs.append(_lbl(_("Messages not loaded: {err}").format(err=c["error"]), ("error",), xalign=0.5))
        by_id = {m["id"]: m for m in msgs}
        group = (chat.chat(self.cid) or {}).get("kind") != "dm"
        prev = None
        old, keep, imgs = self.bubbles, {}, []
        for m in shown:
            day = _day_label(m.get("created") or (m.get("ts") or 0) / 1000)
            if prev is None or _day_label(prev.get("created") or (prev.get("ts") or 0) / 1000) != day:
                d = _lbl(day, ("caption", "dim-label", "hk-day"), xalign=0.5, wrap=False, margin_top=8, margin_bottom=4)
                self.msgs.append(d)
            first = prev is None or _who(prev) != _who(m) or (m.get("created") or 0) - (prev.get("created") or 0) > 600
            key = (m["id"], m.get("state"), m.get("ai_state"), first, group, bool(m.get("reply_to") and m["reply_to"] in by_id),
                   len(chat.notes))
            row = old.pop(key, None) or keep.get(key)
            if row is None:
                self._imgs_building = []
                row = self._bubble(m, by_id, group, first)
                row.hk_imgs = self._imgs_building
                self._imgs_building = None
            keep[key] = row
            imgs += row.hk_imgs
            self.msgs.append(row)
            prev = m
        for row in old.values():                          # nicht mehr angezeigt: wartende Bilder verwerfen
            for w in row.hk_imgs:
                w.drop()
        if self.pending_row is not None and not q:        # laufende KI-Antwort bleibt unten
            self.msgs.append(self.pending_row)
        self.bubbles, self.img_widgets = keep, imgs
        self._vis_soon()
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

    def _call_row(self, m):
        """Anruf-Eintrag (2.1, nur Metadaten): mittig, Symbol + Text wie im Web; verpasst = hervorgehoben"""
        from .calls import entry_missed, entry_text
        k = m.get("call") or {}
        me = chat.uid
        missed = entry_missed(k, me)
        box = Gtk.Box(spacing=8, halign=Gtk.Align.CENTER, margin_top=6, margin_bottom=6)
        box.add_css_class("hk-call-entry")
        if missed:
            box.add_css_class("hk-call-missed")
        icon = "call-missed-symbolic" if missed else ("camera-video-symbolic" if k.get("media") == "video" else "call-start-symbolic")
        img = Gtk.Image.new_from_icon_name(icon)
        box.append(img)
        text = entry_text(k, me)
        box.append(_lbl(text, ("hk-call-text",), wrap=False))
        tm = _lbl(_time(m.get("created") or (m.get("ts") or 0) / 1000), ("caption", "dim-label"), wrap=False)
        box.append(tm)
        box.update_property([Gtk.AccessibleProperty.LABEL], [text + " – " + tm.get_label()])
        box.set_focusable(True)
        return box

    def _bubble(self, m, by_id, group, first):
        if m.get("state") == "call":
            return self._call_row(m)
        is_ai = m.get("ai_state") == "ok"                 # KI-Antwort mit passendem Beleg (2.1): Bild + Name der KI
        mine = m.get("mine") and not is_ai
        row = Gtk.Box(spacing=6, halign=Gtk.Align.END if mine else Gtk.Align.START, margin_top=6 if first else 0)
        sender = m.get("sender") or {}
        sname = sender.get("name") or _("Deleted account")
        if is_ai:
            from .assistant import ai_avatar, ai_name
            asker = _("asked by you") if m.get("mine") else _("asked by {name}").format(name=sname)
            sname = ai_name()
            row.append(ai_avatar(28, valign=Gtk.Align.END) if first else Gtk.Box(width_request=28))
        elif group and not mine:
            if first:
                av = Adw.Avatar(size=28, text=sname, show_initials=True, valign=Gtk.Align.END)
                avatars.attach(av, sender.get("avatar"), 28)
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
        if is_ai:
            b.add_css_class("hk-bubble-ai")
            if first:
                hb = Gtk.Box(spacing=6)
                hb.append(_lbl(sname, ("caption-heading", "hk-bubble-name"), wrap=False))
                hb.append(_lbl(asker, ("caption", "dim-label"), wrap=False, ellipsize=Pango.EllipsizeMode.END))
                b.append(hb)
        elif group and not mine and first:
            nl = _lbl(sname, ("caption-heading", "hk-bubble-name"), wrap=False)
            b.append(nl)
        if m.get("reply_to"):
            q = by_id.get(m["reply_to"])
            qb = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            qb.add_css_class("hk-quote")
            qn = ((q or {}).get("sender") or {}).get("name") or ""
            if (q or {}).get("ai_state") == "ok":
                from .assistant import ai_name
                qn = ai_name()
            if qn:
                qb.append(_lbl(qn, ("caption-heading",), wrap=False))
            qb.append(_lbl(snippet(q), ("caption",), max_width_chars=50, lines=2, ellipsize=Pango.EllipsizeMode.END))
            b.append(qb)
        acc = [sname if not mine else _("you")]
        if is_ai:
            acc.append(asker)
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
        if m.get("ai_state") == "bad":                   # gibt sich als KI-Antwort aus, Beleg fehlt oder passt nicht
            wl = Gtk.Box(spacing=4)
            ic = Gtk.Label(label="⚠")
            ic.add_css_class("hk-warn-text")
            wl.append(ic)
            wl.append(_lbl(_("Presented as an AI reply, but not confirmed"), ("caption",)))
            wl.set_tooltip_text(_("This message claims to be an answer from the AI, but the server's receipt doesn't match. It "
                                  "comes from {name}.").format(name=sname))
            b.append(wl)
            b.add_css_class("hk-bubble-warn")
            acc.append(_("Presented as an AI reply, but not confirmed"))
        meta = Gtk.Box(spacing=4, halign=Gtk.Align.END)
        if st == "unverified":
            meta.append(self._unverified_button(m))
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

    def _unverified_button(self, m):
        """Hinweis "⚠ Echtheit nicht bestaetigt" (Symbol orange, Text in Vordergrundfarbe) - oeffnet die Erklaerung"""
        bx = Gtk.Box(spacing=4)
        ic = Gtk.Label(label="⚠")
        ic.add_css_class("hk-warn-text")
        bx.append(ic)
        bx.append(Gtk.Label(label=_("Authenticity not confirmed")))
        b = Gtk.Button(child=bx, tooltip_text=_("What does this mean?"))
        b.add_css_class("flat")
        b.add_css_class("hk-unv")
        b.add_css_class("caption")
        b.connect("clicked", lambda *_: unverified_info(self.win, self.cid, (m.get("sender") or {}).get("username")))
        return b

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
        name = f.get("n") or _("File")
        if chatmedia.is_image(f):
            img = ChatImage(self.cid, f)
            if self._imgs_building is not None:
                self._imgs_building.append(img)
            btn = Gtk.Button(child=img, tooltip_text=name, halign=Gtk.Align.START)
            btn.add_css_class("flat")
            btn.add_css_class("hk-img-btn")
            btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Open image: {name}").format(name=name)])
            btn.connect("clicked", lambda *_: ImageViewer(self, f).present(self.win))
            return btn
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
        # KI im Chat (2.1): Vorschlag "@Marin" beim Tippen von "@", Hinweis bei einer Nachricht an die KI
        self.ai_pref = None
        self.ai_suggest = Gtk.Button(visible=False, halign=Gtk.Align.START, margin_start=12)
        self.ai_suggest.add_css_class("flat")
        self.ai_suggest.add_css_class("hk-ai-suggest")
        self.ai_suggest.connect("clicked", lambda *_: self._ai_apply())
        outer.append(self.ai_suggest)
        self.ai_hint = Gtk.Box(spacing=6, visible=False, margin_start=14, margin_end=12, margin_top=4)
        self.ai_hint.add_css_class("hk-ai-hint")
        outer.append(self.ai_hint)
        row = Gtk.Box(spacing=6, margin_start=10, margin_end=10, margin_top=6, margin_bottom=10)
        att = Gtk.Button(icon_name="mail-attachment-symbolic", tooltip_text=_("Attach file"), valign=Gtk.Align.END)
        att.add_css_class("flat")
        att.update_property([Gtk.AccessibleProperty.LABEL], [_("Attach file")])
        att.connect("clicked", lambda *_: self._attach_menu())
        row.append(att)
        self.att_btn = att
        self.immich_ok = False                        # "Aus Immich" nur, wenn Immich fuer dieses Konto verbunden ist
        clipimg.immich_ready(lambda ok: setattr(self, "immich_ok", ok))      # vorab fragen: das Menue kommt dann sofort
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
        buf.connect("notify::cursor-position", lambda b, _p: self._ai_typing(b))     # KI-Vorschlag folgt der Schreibmarke
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
        self._ai_typing(buf)

    def _key(self, c, kv, code, state):
        if kv in (Gdk.KEY_Tab, Gdk.KEY_ISO_Left_Tab) and self.ai_pref and not state & Gdk.ModifierType.SHIFT_MASK:
            return self._ai_apply()
        if kv in (Gdk.KEY_Return, Gdk.KEY_KP_Enter) and not state & (Gdk.ModifierType.SHIFT_MASK | Gdk.ModifierType.CONTROL_MASK):
            self.send()
            return True
        if clipimg.is_ctrl_v(kv, state) and self.entry.get_sensitive():
            return self.paste()
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

    def paste(self, clipboard=None):
        """Strg+V: Bild oder Dateien aus der Zwischenablage als Anhang; Text wie gewohnt ins Eingabefeld. -> abgefangen?"""
        cb = clipboard or self.entry.get_clipboard()
        return clipimg.read_paste(cb, self.add_files, self.add_image,
                                  lambda: self.entry.activate_action("clipboard.paste", None))

    def add_image(self, data, name=None, mime="image/png"):
        """Bilddaten (Zwischenablage) als Anhang - zu gross: verkleinern"""
        fmax = int((chat.limits or {}).get("file_max") or 25 * 1048576)

        def work():
            d, m = clipimg.shrink(data, fmax)
            return name or clipimg.pasted_name(m), m, d
        bg(work, done=lambda r: self._add_ready([r]), error=lambda e: _toast(self.win, str(e)), media=True)

    def _attach_menu(self):
        def show(ok):
            self.immich_ok = ok
            if not ok:
                self._pick_files()
            elif self.att_btn.get_root() is not None:
                menu_popover(self.att_btn, [(_("File …"), self._pick_files), (_("From Immich …"), self._pick_immich)]).popup()
        clipimg.immich_ready(show)

    def _pick_immich(self):
        from .dialogs import ImmichPicker
        fmax = int((chat.limits or {}).get("file_max") or 25 * 1048576)
        ImmichPicker(self.win, lambda r: self._add_ready([r]), fetch=lambda it: clipimg.immich_for_chat(it, fmax)).present(self.win)

    def _add_ready(self, lst):
        nmax = int((chat.limits or {}).get("files_per_msg") or 10)
        for name, mime, data in lst:
            if len(self.files) >= nmax:
                _toast(self.win, ngettext("At most {n} file per message.", "At most {n} files per message.", nmax).format(n=nmax))
                break
            self.files.append((name, mime, data))
        self._files_bar()
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
        """Dateien (Auswahl, Zwischenablage) als Anhaenge; zu grosse Bilder werden verkleinert, andere abgelehnt"""
        lim = chat.limits or {}
        fmax = int(lim.get("file_max") or 25 * 1048576)

        def work():
            out = []
            for g in gfiles:
                info = g.query_info("standard::size,standard::content-type,standard::display-name", Gio.FileQueryInfoFlags.NONE, None)
                name = info.get_display_name() or g.get_basename() or "datei"
                mime = Gio.content_type_get_mime_type(info.get_content_type() or "") or "application/octet-stream"
                if info.get_size() > fmax:
                    if mime.startswith("image/") or clipimg.file_is_image(g):
                        try:
                            data, m = clipimg.shrink(g.load_bytes(None)[0].get_data(), fmax)
                            out.append((clipimg.with_ext(name, m), m, data))
                            continue
                        except Exception:  # noqa: BLE001 - nicht lesbar: wie jede zu grosse Datei melden
                            pass
                    out.append((name, None, _("“{name}” is too large (at most {n} MB).").format(name=name, n=fmax // 1048576)))
                    continue
                out.append((name, mime, g.load_bytes(None)[0].get_data()))
            return out

        def fin(lst):
            for name, mime, data in lst:
                if mime is None:
                    _toast(self.win, data)
            self._add_ready([x for x in lst if x[1] is not None])
        bg(work, done=fin, error=lambda e: _toast(self.win, str(e)), media=True)

    def _files_bar(self):
        self.files_bar.remove_all()
        for i, (name, mime, data) in enumerate(self.files):
            chip = Gtk.Box(spacing=6)
            chip.add_css_class("hk-tag")
            if (mime or "").startswith("image/"):              # Vorschau des Bildes (nur im Speicher)
                pv = images.ImageView(mode="cover", radius=6)
                pv.set_size_request(40, 40)
                pv.set_valign(Gtk.Align.CENTER)
                images.load_local(data, lambda d, pv=pv: d is not None and pv.set_decoded(d), max_px=96)
                chip.append(pv)
                label = name + " · " + human_size(len(data))
            else:
                label = "📎 " + name + " · " + human_size(len(data))
            chip.append(_lbl(label, wrap=False, ellipsize=Pango.EllipsizeMode.MIDDLE, max_width_chars=28))
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
            if self._ai_on():                              # KI (2.1): erster Vorschlag
                from .assistant import ai_avatar, ai_name
                r = Adw.ActionRow(title=GLib.markup_escape_text("@" + ai_name()), subtitle=_("Ask the AI"), activatable=True)
                r.add_prefix(ai_avatar(24, valign=Gtk.Align.CENTER))
                r.connect("activated", lambda *_: (pop.popdown(), self._insert_ai()))
                lst.append(r)
            for p in pages[:10]:
                r = Adw.ActionRow(title=GLib.markup_escape_text((p.get("icon") + " " if p.get("icon") and not p["icon"].startswith("/") else "📄 ")
                                                               + (p.get("title") or _("Untitled"))), activatable=True)
                r.connect("activated", lambda *_, p=p: (pop.popdown(), self._insert_page(p)))
                lst.append(r)
            lst.set_visible(bool(pages) or self._ai_on())

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
        raw = text                                         # wie geschrieben (fuer die Frage an die KI)
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
            fobjs, fids = chat.upload_attachments(cid, files)
            body = {"v": 1, "type": "text", "text": text}
            if fobjs:
                body["files"] = fobjs
            if notes:
                body["notes"] = notes
            return chat.send(cid, body, reply_to=reply, file_ids=fids)

        def fin(r):
            resp, missing = r
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
            self._ask_ai(cid, raw, (resp or {}).get("id"))

        def err(e):
            self.entry.set_sensitive(True)
            self._changed(buf)
            if isinstance(e, SafetyChanged):
                self._check_trust()
            _toast(self.win, _("Not sent: {err}").format(err=e))
        bg(work, done=fin, error=err)

    def _ask_ai(self, cid, raw, qid):
        """Nachricht mit @Name in einem Chat mit erlaubter KI: Frage stellen (nach dem normalen, verschluesselten Senden)"""
        from .assistant import ai_name
        name = ai_name()
        if not qid or not chat.ai_on(chat.chat(cid)) or not aichat.mentions(raw, name):
            return
        if not chat.ask_ai(cid, raw, qid, on_error=lambda e: _toast(self.win, _("{name} couldn't reply: {err}").format(name=name, err=e))):
            _toast(self.win, _("{name} is still answering – please wait a moment.").format(name=name))

    # --- Menue ---------------------------------------------------------------------------------------------------------
    def menu(self, parent):
        c = chat.chat(self.cid)
        if not c:
            return
        ents = []
        peer = chat.peer(c)
        if c.get("kind") == "dm" and peer:
            if self.screen.call_chat():
                ents.append((_("Call in browser"), self.win.call_current))
            ents.append((_("Safety number"), lambda: open_safety(self.win, self.cid, peer.get("username"))))
        ents.append((_("Members") + " …", lambda: ChatInfoDialog(self.win, self.cid).present(self.win)))
        from .assistant import ai, ai_name
        if ai.chat_allowed and aichat.may_toggle(c):     # KI im Chat (2.1): erlauben / nicht mehr erlauben
            ents.append(((_("Stop allowing {name} here") if c.get("ai") else _("Allow {name} in this chat")).format(
                name=ai_name()), self._ai_toggle))
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
        ents += manage_entries(self.win, c)                # (2.1) Archivieren, Chat loeschen …
        menu_popover(parent, ents).popup()


# --- Archivieren und Loeschen (2.1) -------------------------------------------------------------------------------
def manage_entries(win, c):
    """Menue-Eintraege "Archivieren"/"Aus Archiv holen" und "Chat loeschen …" - nur, wenn der Server sie kennt"""
    out = []
    if chat.can_archive(c):
        on = not c.get("archived")
        out.append((_("Archive") if on else _("Unarchive"), lambda: archive_chat(win, c["id"], on)))
    if chat.can_delete(c):
        out.append((_("Delete chat …"), lambda: DeleteChatDialog(win, c["id"]).present(win), True))
    return [None] + out if out else []


def archive_chat(win, cid, on):
    def done(_ch):
        _toast(win, _("Chat archived – it comes back with the next message.") if on else _("Chat unarchived."))
    chat.set_archived(cid, on, done=done, error=lambda e: _toast(win, str(e)))


def gone_text(how, name):
    """Hinweis, wenn jemand anderes einen Chat bzw. dessen Verlauf fuer alle geloescht hat (Live)"""
    if not name:
        return ""
    if how == "removed":
        return _("{name} deleted the chat.").format(name=name)
    return _("{name} deleted the history.").format(name=name)


class DeleteChatDialog(Adw.AlertDialog):
    """"Chat loeschen …": Nur fuer mich (jede Chat-Art) oder Fuer alle (je nach delete_for_all, sonst ausgegraut mit
    Begruendung); freie Gruppe: "Gruppe ausserdem verlassen"; deutliche Warnung"""
    __gtype_name__ = "HKDeleteChatDialog"

    def __init__(self, win, cid):
        c = chat.chat(cid) or {}
        name = chat.title(c)
        super().__init__(heading=_("Delete “{name}”?").format(name=name))
        self.win, self.cid, self.c = win, cid, c
        kind, allow = c.get("kind"), c.get("delete_for_all")
        self.allow = allow if allow in ("chat", "history") else None
        peer = (chat.peer(c) or {}).get("name") or name
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        box.append(_lbl(_("Delete for whom?"), ("heading",)))
        lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lb.add_css_class("boxed-list")
        lb.update_property([Gtk.AccessibleProperty.LABEL], [_("Delete for whom?")])
        me_text = (_("The history disappears only for you, on all your devices. {name} keeps it. The chat reappears as soon as a "
                     "new message arrives.").format(name=peer) if kind == "dm" else
                   _("The history disappears only for you, on all your devices; the chat is archived. The others keep it."))
        self.r_me = Gtk.CheckButton(active=True, valign=Gtk.Align.CENTER)
        self.row_me = self._row(_("Just for me"), me_text, self.r_me)
        lb.append(self.row_me)
        self.leave = None
        if kind == "free":
            self.leave = Gtk.CheckButton(valign=Gtk.Align.CENTER)
            self.row_leave = Adw.ActionRow(title=GLib.markup_escape_text(_("Also leave the group")), activatable_widget=self.leave)
            self.row_leave.add_prefix(self.leave)
            self.row_leave.add_css_class("hk-del-sub")
            lb.append(self.row_leave)
        if self.allow == "history":
            all_text = _("All messages and attachments are deleted for all members. The chat itself stays as long as the group "
                         "or page exists.")
        elif kind == "dm":
            all_text = _("The chat is deleted for {name} and you – with all messages, attachments and calls.").format(name=peer)
        else:
            all_text = _("The group is deleted for all members – with all messages, attachments and calls.")
        why = {"free": _("Only admins of this group can delete it for everyone."),
               "group": _("Only Saga admins can delete the history for everyone."),
               "page": _("Only the page owner can delete the history for everyone.")}.get(kind, "")
        self.r_all = Gtk.CheckButton(group=self.r_me, valign=Gtk.Align.CENTER)
        self.row_all = self._row(_("For everyone"), all_text if self.allow else why, self.r_all)
        if not self.allow:
            self.row_all.set_sensitive(False)              # ausgegraut, Begruendung im Untertitel
        lb.append(self.row_all)
        box.append(lb)
        box.append(warn_label(_("This can't be undone. Anyone who has already seen messages may have copies of them.")))
        self.set_extra_child(box)
        self.add_response("cancel", _("Cancel"))
        self.add_response("delete", _("Delete for me"))
        self.set_response_appearance("delete", Adw.ResponseAppearance.DESTRUCTIVE)
        self.set_default_response("cancel")
        self.set_close_response("cancel")
        self.r_me.connect("toggled", lambda *_: self._sync())
        self.r_all.connect("toggled", lambda *_: self._sync())
        self.connect("response", lambda _d, r: r == "delete" and self._go())
        self._sync()

    @staticmethod
    def _row(title, sub, check):
        r = Adw.ActionRow(title=GLib.markup_escape_text(title), subtitle=GLib.markup_escape_text(sub), activatable_widget=check)
        r.add_prefix(check)
        return r

    @property
    def scope(self):
        return "all" if self.allow and self.r_all.get_active() else "me"

    def _sync(self):
        if self.leave is not None:
            self.row_leave.set_sensitive(self.scope == "me")
        if self.scope == "all":
            label = _("Delete history for everyone") if self.allow == "history" else _("Delete for everyone")
        else:
            label = _("Delete for me")
        self.set_response_label("delete", label)

    def _go(self):
        scope, win, kind = self.scope, self.win, self.c.get("kind")
        quit_ = scope == "me" and self.leave is not None and self.leave.get_active()

        def done(r):
            if r.get("left"):
                t = _("History deleted, group left.")
            elif scope == "all":
                t = _("Chat deleted for everyone.") if r.get("removed") else _("History deleted for everyone.")
            else:
                t = _("Chat deleted for you.") if kind == "dm" else _("History deleted for you, chat archived.")
            _toast(win, t)
        chat.delete_chat(self.cid, scope, leave=quit_, done=done, error=lambda e: _toast(win, str(e)))


# --- Dialoge: Chat -------------------------------------------------------------------------------------------------
class ChatImage(Gtk.Widget):
    """Bild im Verlauf: Platzhalter in der richtigen Groesse (w/h aus der Nachricht), Vorschaubild erst wenn sichtbar"""
    __gtype_name__ = "HKChatImage"

    def __init__(self, cid, f):
        super().__init__()
        self.cid, self.f = cid, f
        self.tex = None
        self.state = "idle"                                # idle | wait | ok | fail
        self.bw, self.bh = chatmedia.box_size(chatmedia.size_of(f))
        self._cb = self._got                               # feste Referenz (cancel vergleicht mit "is")
        self.set_overflow(Gtk.Overflow.HIDDEN)
        self.add_css_class("hk-bubble-img")
        self.add_css_class("hk-img-wait")

    def want(self, bump=False):
        if self.state == "wait" and bump:
            chatmedia.bump(self.f["id"])
        if self.state != "idle":
            return
        self.state = "wait"
        chatmedia.request(self.cid, self.f, self._cb)

    def drop(self):
        if self.state == "wait":
            chatmedia.cancel(self.f["id"], self._cb)
        elif self.state == "ok":
            self.tex = None
            self.add_css_class("hk-img-wait")
            self.queue_draw()
        elif self.state == "fail":
            return
        self.state = "idle"

    def _got(self, tex, wh):
        if self.state != "wait":
            return
        if tex is None:
            self.state = "fail"
            self.remove_css_class("hk-img-wait")
            self.add_css_class("hk-img-fail")
            self.set_tooltip_text(_("not loaded"))
            self.queue_draw()
            return
        self.tex, self.state = tex, "ok"
        self.remove_css_class("hk-img-wait")
        if chatmedia.size_of(self.f) is None:              # alte Nachricht ohne w/h: Groesse jetzt bekannt
            bw, bh = chatmedia.box_size(wh or (tex.get_width(), tex.get_height()))
            if (bw, bh) != (self.bw, self.bh):
                self.bw, self.bh = bw, bh
                self.queue_resize()
        self.queue_draw()

    def do_get_request_mode(self):
        return Gtk.SizeRequestMode.CONSTANT_SIZE

    def do_measure(self, orientation, for_size):
        v = self.bw if orientation == Gtk.Orientation.HORIZONTAL else self.bh
        return v, v, -1, -1

    def do_snapshot(self, snap):
        W, H = self.get_width(), self.get_height()
        if W <= 0 or H <= 0:
            return
        if self.tex is None:
            if self.state == "fail":
                c = Gdk.RGBA()
                c.parse("rgba(127,127,127,0.5)")
                lay = self.create_pango_layout("🖼")
                lw, lh = lay.get_pixel_size()
                snap.save()
                p = Graphene.Point()
                p.x, p.y = (W - lw) / 2, (H - lh) / 2
                snap.translate(p)
                snap.append_layout(lay, c)
                snap.restore()
            return
        tw, th = self.tex.get_width(), self.tex.get_height()
        sc = max(W / max(1, tw), H / max(1, th))           # fuellen (Seitenverhaeltnis ist gleich, Rundung egal)
        sw, sh = tw * sc, th * sc
        snap.push_clip(Graphene.Rect().init(0, 0, W, H))
        snap.append_scaled_texture(self.tex, Gsk.ScalingFilter.LINEAR, Graphene.Rect().init((W - sw) / 2, (H - sh) / 2, sw, sh))
        snap.pop()


class ImageViewer(Adw.Dialog):
    """Original eines Chat-Bildes (erst beim Oeffnen geladen, Entschluesseln und Dekodieren im Hintergrund)"""
    def __init__(self, conv, f):
        super().__init__(title=f.get("n") or _("Image"), content_width=960, content_height=720)
        self.conv, self.f = conv, f
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        save = Gtk.Button(icon_name="document-save-symbolic", tooltip_text=_("Save attachment"))
        save.connect("clicked", lambda *_: conv._save_file(f))
        hb.pack_start(save)
        tv.add_top_bar(hb)
        self.stack = Gtk.Stack()
        sp = Adw.Spinner() if hasattr(Adw, "Spinner") else Gtk.Spinner(spinning=True)
        sp.set_size_request(32, 32)
        sp.set_halign(Gtk.Align.CENTER)
        sp.set_valign(Gtk.Align.CENTER)
        self.stack.add_named(sp, "wait")
        self.view = images.ImageView(mode="fit", max_height=640)
        self.view.set_halign(Gtk.Align.CENTER)
        self.view.set_valign(Gtk.Align.CENTER)
        sc = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, vexpand=True, hexpand=True)
        box = Gtk.Box(margin_start=12, margin_end=12, margin_top=12, margin_bottom=12, valign=Gtk.Align.CENTER, halign=Gtk.Align.CENTER)
        box.append(self.view)
        sc.set_child(box)
        self.stack.add_named(sc, "img")
        self.err = _lbl("", ("dim-label",), xalign=0.5, valign=Gtk.Align.CENTER)
        self.stack.add_named(self.err, "err")
        tv.set_content(self.stack)
        self.set_child(tv)
        bg(chatmedia.original, conv.cid, f, done=self._data, error=self._fail, media=True)

    def _data(self, data):
        images.load_local(data, self._dec, max_px=2560)

    def _dec(self, dec):
        if dec is None:
            self._fail(None)
            return
        self.view.natural = dec.w
        self.view.set_decoded(dec)
        self.stack.set_visible_child_name("img")

    def _fail(self, e):
        self.err.set_label(_("Attachment not loaded: {err}").format(err=e or _("not loaded")))
        self.stack.set_visible_child_name("err")


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
    en.connect("activate", lambda *_: (resp(d, "ok"), d.force_close()))   # Enter speichert (BUG-116)
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
        self.toasts = Adw.ToastOverlay(child=tv)           # Fehlermeldungen im Dialog (Adw.Dialog hat kein add_toast)
        self.set_child(self.toasts)
        self.group = None
        self.add_group = None
        self.rc = {}
        self.fill()
        bg(chat.recipients, cid, True, done=self._rc, error=lambda e: None)

    def add_toast(self, t):
        self.toasts.add_toast(t)

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
            row.add_prefix(avatars.person(m, 32, text=m.get("name") or u or "?"))
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
                if fed_hint_show(q, len((j or {}).get("users") or [])):
                    h = fed_hint()
                    self.add_group.add(h)
                    self.hits.append(h)
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
        self.search.connect("stop-search", lambda *_: self.close())    # Esc schliesst (die Suchzeile verschluckt es sonst, BUG-077)
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
        self.toasts = Adw.ToastOverlay(child=tv)           # Fehler vom Server im Dialog melden (BUG-075)
        self.set_child(self.toasts)
        self._update()
        self.q = ""
        GLib.idle_add(lambda: (self.search.grab_focus(), False)[1])

    def add_toast(self, t):
        self.toasts.add_toast(t)

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
            r.add_prefix(avatars.person(u, 32, text=u.get("name") or u["username"]))
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
            if not (j or {}).get("error") and fed_hint_show(q, 0):      # name@server/E-Mail: Hinweis statt "Keine Treffer"
                row = Gtk.ListBoxRow(activatable=False, selectable=False, child=fed_hint())
                self.results.append(row)
            else:
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
        kl = _lbl(recovery_lines(rk), ("hk-recovery", "monospace"), xalign=0.5, wrap=False, selectable=True,
                  justify=Gtk.Justification.CENTER)
        kl.update_property([Gtk.AccessibleProperty.LABEL], [_("Recovery key") + ": " + fmt_recovery(rk)])
        b.append(kl)
        bb = Gtk.Box(spacing=8, halign=Gtk.Align.CENTER)
        bb.append(_button(_("Copy"), ("pill",), self._copy))
        bb.append(_button(_("Save as file"), ("pill",), self._save))
        b.append(bb)
        b.append(warn_label(_("Without it your messages are lost – the admin can't restore them either.")))
        self.stored = Gtk.CheckButton(label=_("I've stored the key safely"))
        b.append(self.stored)
        self.cont = _button(_("Continue"), ("pill", "suggested-action"), lambda: self.nav.push(self.p2), halign=Gtk.Align.CENTER,
                            sensitive=False)
        self.stored.connect("toggled", lambda c: self.cont.set_sensitive(c.get_active()))
        b.append(self.cont)
        sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True, child=b)
        tv.set_content(sw)
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
                        "is left.")))
        b.append(warn_label(_("Without it your messages are lost – the admin can't restore them either.")))
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
        # nur Passkey (kein Passwort, keine Authenticator-App): Passkeys gehen in der App nicht - "Neu beginnen" im Browser
        self.passkey_note = _lbl(_("Your account confirms with a passkey only – that works in the browser. Start over there under "
                                   "Chats."), ("hk-warn-text",), visible=False)
        b.append(self.passkey_note)
        self.passkey_btn = _button(_("Open chats in browser"), ("pill",), self._in_browser, halign=Gtk.Align.CENTER, visible=False)
        b.append(self.passkey_btn)
        self.methods = []
        self.err = _lbl("", ("error",), visible=False)
        b.append(self.err)
        b.append(_button(_("Create keys"), ("pill", "suggested-action"), self._create, halign=Gtk.Align.CENTER))
        tv.set_content(b)
        self.nav.add(Adw.NavigationPage(title=self.get_title(), child=tv, tag="intro"))
        if reset:
            self._need((chat.me or {}).get("reauth"), (chat.me or {}).get("reauth_methods"))

    def add_toast(self, t):
        self.toasts.add_toast(t)

    def _need(self, need, methods=None):
        """Bestaetigung vor "Neu beginnen" (2.1): Passwort und/oder 6-stelliger Code der Authenticator-App im selben Feld;
        Passkey nur im Browser; SSO: neu anmelden"""
        if isinstance(methods, list):
            self.methods = [m for m in methods if isinstance(m, str)]
        m = self.methods
        typed = need == "password" and (not m or "password" in m or "totp" in m)
        only_passkey = need == "password" and not typed
        if "password" in m and "totp" in m:
            title = _("For your security: your password or a code from the authenticator app")
        elif "totp" in m and "password" not in m:
            title = _("For your security: a code from the authenticator app")
        else:
            title = _("For your security: your password")
        self.pw.set_title(title)
        self.pw_box.set_visible(typed)
        self.passkey_note.set_visible(only_passkey)
        self.passkey_btn.set_visible(only_passkey)
        self.relogin.set_visible(need == "login")
        self.relogin_btn.set_visible(need == "login")

    def secret(self):
        """-> (passwort, code): 6 Ziffern gelten als Code, wenn die Authenticator-App eingerichtet ist (wie im Web)"""
        s = self.pw.get_text()
        digits = re.sub(r"\s", "", s)
        if "totp" in self.methods and re.fullmatch(r"\d{6}", digits):
            return None, digits
        return s or None, None

    def _relogin(self):
        self.close()
        self.win.start_login(config.settings.server)

    def _in_browser(self):
        from .calls import open_in_browser
        self.close()
        open_in_browser(config.settings.server.rstrip("/") + "/chats", self.win)

    def _create(self):
        self.err.set_visible(False)
        rk = C.recovery_key()
        self.keys_pages = RecoveryKeyPages(self, self.nav, rk, _("Your recovery key"), _("Set up"), self._go)
        self.nav.push(self.keys_pages.p1)

    def _go(self, rk, pages):
        pw, code = self.secret() if self.reset else (None, None)

        def fin(k):
            chat.adopt_keys(k)
            self.close()
            _toast(self.win, _("Chats are set up."))

        def err(e):
            if isinstance(e, ChatError) and e.code == 403 and isinstance(e.data, dict) and e.data.get("reauth"):
                self._need(e.data["reauth"], e.data.get("reauth_methods"))
                self.nav.pop_to_tag("intro")
                self.err.set_label(str(e))
                self.err.set_visible(True)
                pages.failed("")
                return
            pages.failed(str(e))
        bg(chat.setup, rk, pw, self.reset, code, done=fin, error=err)


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
        self.code = CodeView()
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
        self.code.set_code(lw["code"])
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
        self.code.set_code("")
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


def unverified_info(win, cid, username):
    """Erklaerung zu "Echtheit nicht bestaetigt" mit naechstem Schritt (Sicherheitsnummer pruefen)"""
    d = Adw.AlertDialog(heading=_("Authenticity not confirmed"),
                        body=_("This message couldn't be matched to a confirmed device of the sender – e.g. because the device has "
                               "since been removed. If in doubt, compare the safety number."))
    d.add_response("close", _("Close"))
    if username:
        d.add_response("safety", _("Check safety number"))
        d.set_response_appearance("safety", Adw.ResponseAppearance.SUGGESTED)
        d.set_default_response("safety")
    d.set_close_response("close")
    d.connect("response", lambda _d, r: r == "safety" and open_safety(win, cid, username))
    d.present(win)
    return d


def approve_link(win, ln):
    """Vorhandenes Geraet: neues Geraet bestaetigen (Code vergleichen!)"""
    code = chat.link_code_for(ln)
    if not code:
        return
    d = Adw.AlertDialog(heading=_("Confirm new device"),
                        body=_("“{name}” wants to access your chats.").format(name=ln.get("name") or _("Unknown device")) + "\n\n" +
                        _("Only confirm if the new device shows exactly this code:"))
    d.set_extra_child(CodeView(code))
    if hasattr(d, "set_prefer_wide_layout"):                    # breit: Code in einer Zeile (schmal: zwei Zeilen)
        d.set_prefer_wide_layout(True)
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
        else:                                            # Esc/Schliessen = spaeter: Anfrage nach einer Minute erneut
            chat.link_later(ln)                          # zeigen (das neue Geraet wartet sonst ohne Hinweis, BUG-080)
    d.connect("response", resp)
    d.present(win)
    return d


# --- Einstellungen: Chats & Geraete --------------------------------------------------------------------------------
class ChatDevicesPage(Adw.PreferencesPage):
    def __init__(self, dlg):
        super().__init__(title=pgettext("settings tab", "Chats"), icon_name=chat_icon())    # kurz: Reiter passen auch schmal
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
        if not keys_persistent():                            # Hinweis mit naechstem Schritt ganz oben
            if hasattr(self, "set_banner"):
                if self.get_banner() is None:
                    self.set_banner(keyring_banner(self.dlg.win))
            else:
                g0 = Adw.PreferencesGroup()
                r0 = Adw.ActionRow(title=_("No keyring available – the chat keys are only kept until Saga is closed."))
                r0.add_suffix(_button(_("What to do?"), (), lambda: keyring_help(self.dlg.win), valign=Gtk.Align.CENTER))
                g0.add(r0)
                self.add(g0)
                self.groups.append(g0)
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
