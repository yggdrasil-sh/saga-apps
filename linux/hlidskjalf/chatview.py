"""Chats (Server 1.5) - Oberflaeche: Liste und Unterhaltung im Hauptbereich (breit nebeneinander, schmal nacheinander),
Einrichtung mit Wiederherstellungsschluessel, neues Geraet (Kopplung/Wiederherstellung/Neu beginnen), Bestaetigen neuer
Geraete, Sicherheitsnummer (+ QR-Code), Mitglieder und Einstellungen eines Chats, "Chats & Geraete" in den Einstellungen.
Nachrichtentexte werden nie als Markup interpretiert (escape; nur eigene Links fuer Adressen und [[note:ID]])."""
import datetime as _dt
import re
import threading

from gi.repository import Adw, Gdk, Gio, GLib, GObject, Graphene, Gsk, Gtk, Pango

from . import chatcrypto as C
from . import chatstatus as S
from . import aichat, avatars, chatfiles, chatmedia, chatvideo, clipimg, config, images
from .api import APIError, account_body, account_name, client
from .blocks import flat_button, menu_popover
from . import chatx3, voice
from .chat import (FED_CHECK_EVERY_S, NOTE_RX, RETENTIONS, ChatError, FedNotDelivered, SafetyChanged, chat, fmt_recovery, is_fed,
                   is_remote, can_edit, can_react, has_feature, rx_groups, voice_file, x3_limits, x3_ok,
                   keys_persistent, recovery_lines, remote_label, retention_label, fed_guide_url, fed_hint_show, fed_hint_texts, safety_for,
                   safety_qr_text, sys_text, chat_home_hint, trust_accept, trust_get, trust_verified,
                   contact_item, contact_keep_for, gi_actions, gi_handle, gi_members, gi_mine, gi_rights, gi_sub, server_has)
from . import chatdrafts
from . import chatstd as K
from . import chatstdview as SV
from .i18n import _, ngettext, pgettext, ui_language
from .sharing import person_mark, same_host
from .util import Debounce, bg, date_short, esc, human_size, rel_time, time_text, weekday_day_text

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


def shown(widget):
    """Widget wirklich zu sehen: abgebildet und das Fenster nicht minimiert bzw. verdeckt (GTK ab 4.12: suspended)"""
    if not widget.get_mapped():
        return False
    root = widget.get_root()
    return root is not None and not (hasattr(root, "is_suspended") and root.is_suspended())


def watch_presence(widget, fn):
    """(2.4) Online-Status: solange das Widget zu sehen ist, die Personen aus fn() abonnieren (chat.presence_watch)"""
    key = ("presence", id(widget))
    st = {"h": None}

    def on_map(*_):
        chat.presence_watch(key, lambda: fn() if shown(widget) else [])
        root = widget.get_root()
        if st["h"] is None and root is not None and hasattr(root, "is_suspended"):
            st["h"] = (root, root.connect("notify::suspended", lambda *_: chat._pres_soon()))

    def on_unmap(*_):
        chat.presence_watch(key, None)
        if st["h"] is not None:
            st["h"][0].disconnect(st["h"][1])
            st["h"] = None
    widget.connect("map", on_map)
    widget.connect("unmap", on_unmap)
    if widget.get_mapped():
        on_map()


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
        f0 = m["files"][0]
        t = ("🎬 " if chatvideo.is_video(f0) else "📎 ") + (f0.get("n") or _("File"))
    t = " ".join(t.split())
    return t if len(t) <= n else t[:n - 1] + "…"


def reply_media(cid, m, size=36):
    """(2.5) Antwort-Zitat und Antwortleiste: erster Anhang der Nachricht -> (Vorschau-Widget | None, Beschriftung | None).
    Vorschau: kleines Bild (Foto, Video-Poster), sonst ein Symbol, wenn die Nachricht keinen Text hat. Beschriftung nur
    ohne Text: "Photo" / "Video" / "Voice message" / Dateiname."""
    if not isinstance(m, dict) or m.get("state") == "undecryptable":
        return None, None
    f = next((x for x in m.get("files") or [] if isinstance(x, dict)), None)
    if f is None:
        return None, None
    notext = not (m.get("text") or "").strip()
    vid = chatvideo.is_video(f)
    if f.get("voice"):
        lab, icon = _("Voice message"), "audio-x-generic-symbolic"
    elif vid:
        lab, icon = _("Video"), "video-x-generic-symbolic"
    elif chatmedia.is_image(f):
        lab, icon = _("Photo"), "image-x-generic-symbolic"
    else:
        lab, icon = f.get("n") or _("File"), "mail-attachment-symbolic"
    w = None
    if f.get("id") and ((chatmedia.is_image(f) and chatvideo.fmt(f) == 1) or (vid and chatmedia.thumb_ref(f))):
        w = ChatImage(cid, f, poster=True)
        w.bw = w.bh = size
        w.add_css_class("hk-quote-thumb")
        w.set_valign(Gtk.Align.CENTER)
        w.want()
    elif notext:
        w = Gtk.Image(icon_name=icon, pixel_size=20, valign=Gtk.Align.CENTER)
        w.add_css_class("dim-label")
        w.add_css_class("hk-quote-icon")
    return w, (lab if notext else None)


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
        watch_chat(self, "presence", lambda: (self._header(), self.list.fill() if chat.status == "ready" else None))
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
        if c.get("kind") == "dm" and is_remote(c):           # 2.3: Person eines anderen Servers - Herkunft, Vertrauen, Stand
            p = chat.peer(c) or {}
            parts = [_("on {host}").format(host=p.get("host") or c.get("home") or "?")]
            probs = dict((m.get("handle"), t) for m, t in chat.fed_problems(c))
            if chat.fed_verified(p.get("handle")):
                parts.append("✓ " + _("verified"))
            elif probs.get(p.get("handle")) in ("block", "reject"):
                parts.append("⛔ " + _("blocked"))
            if c.get("request"):
                parts.append(_("Request"))
            if c.get("readonly"):
                parts.append(_("read-only"))
            on = S.presence_text(chat.pres.get(S.presence_user(chat.me, c)))     # (2.4) online / zuletzt online
            return chat.title(c), " · ".join(([on] if on else []) + parts) + arch
        if c.get("kind") == "dm":
            on = S.presence_text(chat.pres.get(S.presence_user(chat.me, c)))
            sub = on or _("End-to-end encrypted")
            if is_fed(c):                                # 2.5 Stufe 3 (lokal): Vertrauen ueber den Handle
                p = chat.peer(c) or {}
                probs = dict((m.get("handle"), t) for m, t in chat.fed_problems(c))
                if chat.fed_verified(p.get("handle")):
                    sub += " · ✓ " + _("verified")
                elif probs.get(p.get("handle")) in ("block", "reject"):
                    sub += " · ⛔ " + _("blocked")
            return chat.title(c), sub + arch
        n = int(c.get("member_count") or len(c.get("members") or []))
        sub = chat.kind_label(c) + " · " + ngettext("{n} person", "{n} people", n).format(n=n)
        if is_remote(c):                                 # 2.3: Gruppe/Seiten-Chat ueber Servergrenzen - Heim, Stand
            sub += ("" if c.get("home_self") else " · " + _("on {host}").format(host=c.get("home") or "?")) + \
                (" · " + _("Request") if c.get("request") else "") + (" · " + _("read-only") if c.get("readonly") else "")
        return chat.title(c), sub + arch

    def has_menu(self):
        return self.conv is not None and chat.chat(self.cid) is not None

    def title_clicked(self):
        """Klick auf den Chat-Kopf (Titel der Kopfleiste, 2.5) -> Gruppeninfo bzw. Mitglieder (nur mit chatadm1)"""
        if self.has_menu() and server_has("chatadm1"):
            self.conv.open_info()

    def title_tip(self):
        return _("Chat info") if self.has_menu() and server_has("chatadm1") else None

    def call_chat(self):
        """Direkt-Chat mit eingeschalteten Anrufen -> Chat-ID (Knopf "Anrufen" in der Kopfleiste), sonst None"""
        from .calls import calls
        c = chat.chat(self.cid) if self.cid is not None and self.conv is not None else None
        if is_fed(c) and (c.get("request") or c.get("readonly")):      # 2.3: erst nach Annahme, nicht schreibgeschuetzt
            return None
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
        watch_presence(self, self._presence_users)

    def _presence_users(self):
        """(2.4) Online-Status: Gegenueber der sichtbaren Direkt-Chats (die ersten der Liste, ohne Archiv und Anfragen)"""
        out = []
        for c in chat.chats:
            u = None if c.get("archived") else S.presence_user(chat.me, c)
            if u and u not in out:
                out.append(u)
        return out[:S.PRESENCE_LIST]

    arch_open = False            # Bereich "Archiviert" aufgeklappt (fuer die Sitzung, ueber Neuaufbauten hinweg)

    def _activated(self, row):
        if getattr(row, "hid_toggle", False):
            ChatList.hid_open = not ChatList.hid_open
            if ChatList.hid_open:
                ChatList.hidden = []
            self.fill()
            for r in _walk_rows(self.box):
                if getattr(r, "hid_toggle", False):
                    r.grab_focus()
            return
        if getattr(row, "arch_toggle", False):
            ChatList.arch_open = not ChatList.arch_open
            self.fill()
            for r in _walk_rows(self.box):                  # Fokus bleibt auf dem Kopf des Bereichs
                if getattr(r, "arch_toggle", False):
                    r.grab_focus()
            return
        if getattr(row, "cid", None) is not None:
            self.screen.open(row.cid)

    hid_open = False             # (2.5) Bereich "Ausgeblendet" aufgeklappt
    hidden = []                  # (2.5) zuletzt geladene ausgeblendete Chats (GET /api/chats?hidden=1)

    def _hidden_load(self):
        if getattr(self, "_hid_loading", False):
            return
        self._hid_loading = True

        def fin(lst):
            self._hid_loading = False
            ChatList.hidden = lst
            self.sig = None
            self.fill()

        def err(_e):
            self._hid_loading = False
        chat.load_hidden(fin, err)

    def fill(self):
        q = self.search.get_text().strip().lower()
        lst = [c for c in K.list_split(chat.chats) if not q or q in chat.title(c).lower()]
        reqs = [c for c in lst if is_fed(c) and c.get("request")]     # (2.3) Nachrichtenanfragen: eigener Bereich oben
        lst = [c for c in lst if not (is_fed(c) and c.get("request"))]
        act = [c for c in lst if not c.get("archived")]
        arch = [c for c in lst if c.get("archived")]       # (2.1) eigener Bereich unten, bei einer Suche aufgeklappt
        show_arch = ChatList.arch_open or bool(q)
        n_hid = int(chat.hidden_count or 0) if K.has(K.CHATHIDE) else 0
        show_hid = n_hid and (ChatList.hid_open or bool(q))
        if show_hid and not ChatList.hidden:
            self._hidden_load()
        hid = [c for c in ChatList.hidden if not q or q in chat.title(c).lower()] if show_hid else []
        sig = (q, self.screen.cid, show_arch, n_hid, bool(show_hid), tuple(c.get("id") for c in hid),
               tuple((c.get("pinned"), c.get("unread_mark"), c.get("muted_until"), c.get("notify"), c.get("_avf", {}) and
                      (c.get("_avf") or {}).get("id"), chatdrafts.has(c.get("id"))) for c in lst),
               tuple((c.get("id"), chat.title(c), c.get("unread"), c.get("muted"), c.get("last_at"),
                                                     c.get("archived"), (chat.peer(c) or {}).get("avatar") if c.get("kind") == "dm" else None,
                                                     c.get("request"), c.get("readonly"),
                                                     bool((chat.pres.get(S.presence_user(chat.me, c)) or {}).get("online")))
                                                    for c in reqs + lst))
        if sig == self.sig:
            return
        self.sig = sig
        self.box.remove_all()
        if reqs:
            hd = _lbl(_("Message requests ({n})").format(n=len(reqs)), ("heading",), wrap=False, margin_start=6, margin_top=6, margin_bottom=2)
            hrow = Gtk.ListBoxRow(activatable=False, selectable=False, child=hd)
            hrow.add_css_class("hk-req-head")
            self.box.append(hrow)
            for c in reqs:
                self.box.append(self._row(c))
        if not act and not arch and not reqs:
            lb = _lbl(_("No matches.") if q else _("No chats yet."), ("dim-label",), margin_start=10, margin_top=10)
            self.box.append(Gtk.ListBoxRow(activatable=False, selectable=False, child=lb))
        for c in act:
            self.box.append(self._row(c))
        if arch:
            self.box.append(self._arch_head(arch, show_arch))
            if show_arch:
                for c in arch:
                    self.box.append(self._row(c))
        if n_hid and not (q and not hid):                  # (2.5, chathide1) "Ausgeblendet (n)" am Ende
            self.box.append(self._hid_head(n_hid, bool(show_hid)))
            for c in hid:
                r = self._row(c)
                r.add_css_class("hk-hidden-chat")
                self.box.append(r)

    def _hid_head(self, n, shown):
        row = Gtk.ListBoxRow()
        row.hid_toggle = True
        row.add_css_class("hk-arch-head")
        box = Gtk.Box(spacing=8, margin_top=6, margin_bottom=6, margin_start=4)
        box.append(Gtk.Image.new_from_icon_name("pan-down-symbolic" if shown else "pan-end-symbolic"))
        box.append(_lbl(_("Hidden ({n})").format(n=n), ("heading",), hexpand=True, wrap=False))
        row.set_child(box)
        row.update_property([Gtk.AccessibleProperty.LABEL], [_("Hidden ({n})").format(n=n)])
        from .window import set_expanded_state
        set_expanded_state(row, shown)
        return row

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
        ents = [(_("Open"), lambda: self.screen.open(cid))] + SV.mute_entries(win, c) + SV.list_entries(win, c, self.screen)
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
        av = Adw.Avatar(size=36, text=avatars.initials_text(title), show_initials=c.get("kind") in ("dm", "free"))
        if c.get("kind") == "dm":
            avatars.attach(av, (chat.peer(c) or {}).get("avatar"), 36)     # Profilbild des Gegenuebers (2.1)
        elif c.get("_avf"):                              # (2.5, groupinfo2) Gruppenbild
            SV.attach_group(av, c)
        if c.get("kind") == "group":
            from .sharing import people_icon
            av.set_icon_name(people_icon())
        elif c.get("kind") == "page":
            from .publish import _icon
            av.set_icon_name(_icon("saga-page-symbolic", "text-x-generic-symbolic"))
        online = bool((chat.pres.get(S.presence_user(chat.me, c)) or {}).get("online"))
        if online:                                       # (2.4) gruener Punkt am Bild: Gegenueber ist online
            ov = Gtk.Overlay(child=av, valign=Gtk.Align.CENTER)
            dot = Gtk.Box(halign=Gtk.Align.END, valign=Gtk.Align.END, width_request=12, height_request=12)
            dot.add_css_class("hk-online-dot")
            ov.add_overlay(dot)
            box.append(ov)
        else:
            box.append(av)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, valign=Gtk.Align.CENTER)
        t = Gtk.Label(label=title, xalign=0, ellipsize=Pango.EllipsizeMode.END, hexpand=True)
        dot = K.unread_dot(c) and K.has(K.MARKUNREAD)      # (2.5) als ungelesen markiert: Punkt statt Zahl
        un = 0 if dot else int(c.get("unread") or 0)
        if un or dot:
            t.add_css_class("heading")
        if c.get("pinned") and K.has(K.CHATPIN):          # (2.5, chatpin1) angeheftet: Nadel vor dem Namen
            tb = Gtk.Box(spacing=4)
            pi = Gtk.Label(label="📌", tooltip_text=_("Pinned"))
            pi.add_css_class("hk-chat-pin")
            tb.append(pi)
            tb.append(t)
            col.append(tb)
        else:
            col.append(t)
        n = int(c.get("member_count") or 0)
        if is_remote(c) and c.get("kind") == "dm":       # 2.3: Anfrage bzw. Direkt-Chat · Server der Person
            sub = (_("Request") if c.get("request") else chat.kind_label(c)) + " · " + ((chat.peer(c) or {}).get("host") or c.get("home") or "?") \
                + (" · " + _("read-only") if c.get("readonly") else "")
        elif is_remote(c):                               # Gruppe/Seiten-Chat ueber Servergrenzen: Art · Personen · Heim
            sub = (_("Request") if c.get("request") else chat.kind_label(c)) + " · " + ngettext("{n} person", "{n} people", n).format(n=n) \
                + ("" if c.get("home_self") else " · " + (c.get("home") or "?")) + (" · " + _("read-only") if c.get("readonly") else "")
        else:
            sub = chat.kind_label(c) + ("" if c.get("kind") == "dm" else " · " + ngettext("{n} person", "{n} people", n).format(n=n))
        mute_now = K.muted_now(c) if K.has(K.MUTE2) else bool(c.get("muted"))
        if K.has(K.MUTE2) and (mute_now or c.get("notify") in ("mentions", "none")):
            sub += " · " + K.mute_label(c)                 # (2.5) "Stumm bis …" / "Nur Erwaehnungen"
        s = Gtk.Label(label=sub, xalign=0, ellipsize=Pango.EllipsizeMode.END)
        s.add_css_class("caption")
        s.add_css_class("dim-label")
        col.append(s)
        has_draft = c["id"] != self.screen.cid and chatdrafts.has(c["id"])
        if has_draft:                                    # (2.5) Entwurf (nur auf diesem Geraet)
            dl = Gtk.Label(label=_("Draft"), xalign=0)
            dl.add_css_class("caption")
            dl.add_css_class("hk-draft-mark")
            col.append(dl)
        box.append(col)
        right = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, valign=Gtk.Align.CENTER)
        tm = Gtk.Label(label=_short_when(c.get("last_at")), xalign=1)
        tm.add_css_class("caption")
        tm.add_css_class("dim-label")
        right.append(tm)
        bx = Gtk.Box(spacing=4, halign=Gtk.Align.END)
        if mute_now:
            mi = Gtk.Image.new_from_icon_name("audio-volume-muted-symbolic")
            mi.add_css_class("dim-label")
            mi.set_tooltip_text(K.mute_label(c) if K.has(K.MUTE2) else _("Muted"))
            bx.append(mi)
        if un:
            b = Gtk.Label(label=str(un))
            b.add_css_class("hk-badge")
            b.add_css_class("hk-badge-accent")
            bx.append(b)
        elif dot:
            d0 = Gtk.Box(width_request=10, height_request=10, valign=Gtk.Align.CENTER)
            d0.add_css_class("hk-unread-dot")
            d0.set_tooltip_text(_("Marked as unread"))
            bx.append(d0)
        right.append(bx)
        box.append(right)
        row.set_child(box)
        acc = title + " – " + sub + (" – " + _("online") if online else "") + \
            (" – " + ngettext("{n} unread", "{n} unread", un).format(n=un) if un else "") + \
            (" – " + _("Marked as unread") if dot else "") + (" – " + _("Pinned") if c.get("pinned") and K.has(K.CHATPIN) else "") + \
            (" – " + _("Draft") if has_draft else "")
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


def _x3sig(m):
    """(2.5) was eine Blase zusaetzlich neu zeichnen laesst: Bearbeitung (Text, Zeitpunkt) und Reaktionen"""
    if not m:
        return None
    return (m.get("text") if m.get("edited_at") else None, m.get("edited_at"))


def _rxsig(m):
    """(2.5) Reaktionen einer Nachricht - aendern nur die Chips an Ort und Stelle (Conversation._update_rx), nicht die Blase"""
    return tuple(sorted((x.get("by") or "", x.get("e") or "") for x in (m or {}).get("rx") or []))


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
    if m.get("state") == "sys":
        return ("\x00sys", m.get("id"))
    return ("\x00ai", u) if m.get("ai_state") == "ok" else u                  # je Fragender ein eigener Kopf ("gefragt von")


# --- Unterhaltung -------------------------------------------------------------------------------------------------
class Conversation(Gtk.Box):
    __gtype_name__ = "HKConversation"

    def __init__(self, screen, cid):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, vexpand=True, hexpand=True)
        self.screen, self.win, self.cid = screen, screen.win, cid
        self.reply = None
        self.files = []              # [(name, mime, bytes | chatvideo.LocalFile/LocalVideo)]
        self._sending = None         # threading.Event waehrend des Sendens (Abbrechen)
        self.mentions = {}           # "@Titel" -> Seiten-ID
        self.query = ""
        self.sig = None
        self.at_bottom = True
        self.bubbles = {}            # Schluessel (ID, Zustand, Darstellung) -> fertige Zeile (wiederverwendet beim Neuzeichnen)
        self.img_widgets = []        # ChatImage aller angezeigten Zeilen, in Reihenfolge
        self._imgs_building = None
        self._vis_pending = 0
        self._pinned = 0
        self.rows_by_id = {}         # (2.4) Nachrichten-ID -> Zeile (sichtbar? fuer Gelesen-Bestaetigungen)
        self.ticks = {}              # (2.4) eigene Nachricht -> (Platz fuer die Haken, Nachricht, Zustand)
        self._rc_soon = Debounce(300, self._mark_read)
        self._active_h = None
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
        self.warn_v2 = None          # (2.3) (Mitglied, trust) bei Chats ueber Servergrenzen
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
        # (2.5, msgpin1) angeheftete Nachrichten: Leiste oben, Klick springt hin (mehrere: der Reihe nach)
        self.pin_bar = Gtk.Box(spacing=6, visible=False, margin_start=12, margin_end=12, margin_top=4)
        self.pin_bar.add_css_class("hk-pin-bar")
        self.pin_go = Gtk.Button(hexpand=True)
        self.pin_go.add_css_class("flat")
        self.pin_go.connect("clicked", lambda *_: self._pin_jump())
        self.pin_label = _lbl("", ("caption",), lines=2, ellipsize=Pango.EllipsizeMode.END)
        pb = Gtk.Box(spacing=6)
        pb.append(Gtk.Label(label="📌"))
        pb.append(self.pin_label)
        self.pin_go.set_child(pb)
        self.pin_bar.append(self.pin_go)
        self.pin_x = flat_button(icon="window-close-symbolic", tooltip=_("Unpin"), cb=self._pin_drop)
        self.pin_bar.append(self.pin_x)
        self.pin_i = 0
        self.append(self.pin_bar)
        self.unblocked = set()        # (2.5, block2) eingeklappte Nachrichten, die ich trotzdem sehen will
        self.sel = None               # (2.5) Mehrfachauswahl: Menge der Nachrichten-IDs (None = aus)
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
        # (2.3) Nachrichtenanfrage (Annehmen/Ablehnen/Sperren) bzw. schreibgeschuetzt: statt des Eingabefelds
        self.state_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, visible=False, margin_start=12, margin_end=12,
                                 margin_top=8, margin_bottom=12)
        self.state_box.add_css_class("hk-chat-state")
        self.append(self.state_box)
        self._state_sig = None
        self.editing = None           # (2.5) Nachricht, die gerade bearbeitet wird
        self.rec_bar = None           # (2.5) Aufnahme-Leiste einer Sprachnachricht
        self.voice_views = {}         # (2.5) Datei-ID -> Spieler (bleibt ueber das Neuzeichnen erhalten)
        self._menu_pop = None
        # (2.5) Mehrfachauswahl: Leiste statt Eingabefeld (Anzahl, Weiterleiten, Fuer mich loeschen, Abbrechen)
        self.sel_bar = Gtk.Box(spacing=6, visible=False, margin_start=12, margin_end=12, margin_top=6, margin_bottom=10)
        self.sel_bar.add_css_class("hk-sel-bar")
        self.sel_label = _lbl("", ("heading",), hexpand=True, wrap=False, valign=Gtk.Align.CENTER)
        self.sel_bar.append(self.sel_label)
        self.sel_fwd = _button(_("Forward …"), (), self._sel_forward, valign=Gtk.Align.CENTER)
        self.sel_bar.append(self.sel_fwd)
        self.sel_del = _button(_("Delete for me"), ("destructive-action",), lambda: self._sel_hide(), valign=Gtk.Align.CENTER)
        self.sel_bar.append(self.sel_del)
        self.sel_bar.append(_button(_("Cancel"), ("flat",), self.sel_end, valign=Gtk.Align.CENTER))
        self.append(self.sel_bar)
        self.composer_box = self._composer()
        self.append(self.composer_box)
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", lambda _c, kv, _code, _st: (kv == Gdk.KEY_Escape and self.sel is not None) and (self.sel_end(), True)[1])
        self.add_controller(kc)
        self._draft_load()                                 # (2.5) Entwurf dieses Chats (nur auf diesem Geraet)
        self.connect("unmap", lambda *_: self._draft_save())
        watch_chat(self, "messages", lambda cid: cid == self.cid and self.render())
        watch_chat(self, "typing", lambda cid: cid == self.cid and self._typing())
        watch_chat(self, "chat-changed", lambda cid: cid == self.cid and self._chat_changed())
        watch_chat(self, "ai-pending", lambda cid: cid == self.cid and self._pending_changed())
        watch_chat(self, "chat-gone", lambda cid, how, _n: cid == self.cid and self._wiped())
        watch_chat(self, "receipts", lambda cid: cid == self.cid and self._update_ticks())     # (2.4)
        watch_chat(self, "reactions", lambda cid: cid == self.cid and self._update_rx())       # (2.5)
        watch_chat(self, "changed", lambda: self._unread_check())       # (2.4) Liste neu: offener Chat zaehlt nicht
        watch_presence(self, lambda: [u for u in [S.presence_user(chat.me, chat.chat(self.cid))] if u])
        from .assistant import ai
        watch_chat(self, "changed", lambda: self._ai_changed(), obj=ai)
        self.connect("map", lambda *_: self._mapped())
        self.connect("map", self._vis_soon)
        self._dcheck_src = 0                              # (2.3) Abgleich wartender eigener Nachrichten, solange offen
        self.connect("unmap", lambda *_: self._dcheck_stop())
        self.connect("unmap", lambda *_: self._active_watch(False))
        self.connect("unmap", lambda *_: self._voice_unmap())
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
        self._state()

    def _voice_unmap(self):
        """Unterhaltung nicht mehr sichtbar: Wiedergabe anhalten, laufende Aufnahme verwerfen"""
        for vv in self.voice_views.values():
            vv.pause()
        if self.rec_bar is not None and self.rec_bar.state in ("wait", "rec", "stop"):
            self.rec_bar.cancel()

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
        chat.load_receipts(self.cid)                      # (2.4) wer hat was gelesen
        c = chat.chat(self.cid)
        if chat.ready and is_fed(c):                      # 2.3: Schluessel frisch holen, selbst pruefen, TOFU
            bg(chat.fed_load, c, done=lambda _r: (self._check_trust(), self.screen._header()), error=lambda e: None)
        elif chat.ready:                                  # Sicherheitsnummern gleich beim Oeffnen pruefen (BUG-076)
            bg(chat.verify_recipients, self.cid, done=lambda _r: self._check_trust(), error=lambda e: None)
        GLib.idle_add(lambda: (self.entry.grab_focus(), False)[1])

    def _mapped(self):
        chat.open_chat = self.cid
        self._active_watch(True)
        if not self._dcheck_src:
            self._dcheck_src = GLib.timeout_add_seconds(FED_CHECK_EVERY_S, self._dcheck_tick)

    def _active_watch(self, on):
        """(2.4) Fenster wird wieder aktiv: jetzt Sichtbares bestaetigen"""
        if self._active_h is not None:
            self._active_h[0].disconnect(self._active_h[1])
            self._active_h = None
        if on and self.win is not None:
            self._active_h = (self.win, self.win.connect("notify::is-active", lambda w, _p: w.is_active() and self._rc_soon()))

    def _dcheck_tick(self):
        """(2.3) alle 15 s: wartet eine eigene Nachricht ("wird zugestellt"), ihren Stand beim Server nachsehen (Server bis
        2.3.0 melden die sofortige Bestaetigung des Heims nicht) - ohne wartende Nachricht geht keine Anfrage raus"""
        chat.check_delivery(self.cid)
        return True

    def _dcheck_stop(self):
        if self._dcheck_src:
            GLib.source_remove(self._dcheck_src)
            self._dcheck_src = 0

    def _wiped(self):
        """(2.1) Verlauf geleert bzw. Chat geloescht: Entwurf (Text, Antwort, Anhaenge) verwerfen, Ansicht neu"""
        self.voice_stop_all()
        self.voice_views = {}
        self.editing = None
        self.edit_bar.set_visible(False)
        self.reply = None
        self.reply_bar.set_visible(False)
        self.files = []
        self._files_bar()
        self.entry.get_buffer().set_text("")
        self.mentions, self.people = {}, {}
        chatdrafts.drop(self.cid)
        self.sig = None
        self.render()

    def _chat_changed(self):
        self._check_trust()
        self._state()
        self._pins()
        self._mic_update()
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
        self.warn_v2 = None
        if is_fed(c):
            self._check_trust_v2(c)
            return
        self.warn_btn.set_visible(True)
        self.warn_ok.set_visible(True)
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

    def _check_trust_v2(self, c):
        """2.3: TOFU fuer Personen anderer Server - Warnung (Schluessel gewechselt), Blockade ("ueberprueft" und gewechselt),
        ungueltig (Zertifikat/Geraete-Liste). Senden und Anrufen gehen erst nach Pruefen bzw. "Verstanden" wieder."""
        self.warn_uid = None
        probs = chat.fed_problems(c)
        if probs:
            m, t = probs[0]
            self.warn_v2 = (m, t)
            nm = m.get("name") or "?"
            if t == "warn":
                txt = _("{name}'s safety number has changed – e.g. after “Start over” or with a new account. "
                        "Check it before you write anything confidential.").format(name=nm)
            elif t == "block":
                txt = _("You had marked {name} as verified – now the server reports a different key. Sending is blocked until you "
                        "compare the safety number again.").format(name=nm)
            else:
                txt = _("{name}'s keys are invalid (certificate or device list) – sending is blocked.").format(name=nm)
            self.warn_label.set_label(("⚠ " if t == "warn" else "⛔ ") + txt)
            self.warn_btn.set_visible(t != "reject")
            self.warn_ok.set_visible(t == "warn")
        self.warn_rev.set_reveal_child(bool(probs))

    def _warn_ok(self):
        if self.warn_v2:
            m, _t = self.warn_v2
            f = chat.fk.get(m.get("handle")) or {}
            if f.get("keys"):
                chat.fed_verify(m["handle"], f["keys"]["ident_pub"], False)
            self._check_trust()
            self.screen._header()
            return
        if self.warn_uid:
            trust_accept(chat.uid, self.warn_uid)
        self._check_trust()

    def _warn_safety(self):
        if self.warn_v2:
            open_safety_v2(self.win, self.cid, self.warn_v2[0])
            return
        if self.warn_uid:
            open_safety(self.win, self.cid, self.warn_uid)

    # --- Nachrichtenanfrage / schreibgeschuetzt (2.3) ----------------------------------------------------------------
    def _state(self):
        c = chat.chat(self.cid) or {}
        fed = is_fed(c)
        req = fed and bool(c.get("request"))
        ro = fed and bool(c.get("readonly")) and not req
        dm = c.get("kind") == "dm"
        p = (chat.peer(c) if dm else c.get("invited_by")) or {}      # Gruppe (2.3): wer eingeladen hat
        p = p if isinstance(p, dict) else {}
        mute = not req and not ro and not K.rights(c)["send"]      # (2.5) nur Admins schreiben / gesperrte Person
        sig = (req, ro, c.get("readonly_reason"), p.get("name"), p.get("host"), dm, mute, bool(c.get("blocked")))
        self.composer_box.set_visible(not (req or ro or mute) and self.sel is None)
        if sig == self._state_sig:
            return
        self._state_sig = sig
        while (w := self.state_box.get_first_child()):
            self.state_box.remove(w)
        self.state_box.set_visible(req or ro or mute)
        if mute:
            self.state_box.append(_lbl(K.send_block_text(c), ("dim-label", "hk-perm-note")))
            return
        if ro:
            self.state_box.append(_lbl("🔒 " + (c.get("readonly_reason") or _("This conversation is read-only.")), ("dim-label",)))
            return
        if not req:
            return
        name, host = p.get("name") or "?", p.get("host") or (c.get("home") if not dm else None) or "?"
        if dm:
            self.state_box.append(_lbl(_("{name} ({host}) wants to write to you. Until you accept, you get no notifications and no calls – "
                                         "and {name} doesn't learn whether you've read the messages.").format(name=name, host=host)))
        else:
            self.state_box.append(_lbl(_("{name} ({host}) invited you to this group. Until you accept, you get no notifications.").format(
                name=name, host=host)))
        bar = Gtk.Box(spacing=6)                           # ohne "pill": passt auch schmal (360 px) nebeneinander
        bar.append(_button(_("Accept"), ("suggested-action",), lambda: self._answer("accept")))
        bar.append(_button(_("Decline"), (), lambda: self._answer("decline")))
        blk = Gtk.Button(label=_("Block …"))
        blk.add_css_class("destructive-action")
        blk.connect("clicked", lambda b: menu_popover(b, [
            (_("Block {name}").format(name=name), lambda: self._answer("block", "person"), True),
            (_("Block the whole server {host}").format(host=host), lambda: self._answer("block", "instance"), True)]).popup())
        bar.append(blk)
        self.state_box.append(bar)

    def _answer(self, action, scope="person"):
        win, cid = self.win, self.cid

        def done(_j):
            if action == "accept":
                _toast(win, _("Request accepted."))
            else:
                _toast(win, _("Blocked – no more requests from there.") if action == "block" else _("Request declined."))
                win.show_root(("chats",))
        chat.answer_request(cid, action, scope, done=done, error=lambda e: _toast(win, str(e)))

    # --- Nachrichten ---------------------------------------------------------------------------------------------
    def _scrolled(self, adj):
        self.at_bottom = adj.get_value() + adj.get_page_size() >= adj.get_upper() - 40
        self._vis_soon()
        if self.loaded:
            self._rc_soon()                               # (2.4) neue Nachricht ins Bild gescrollt: bestaetigen

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
        x3 = x3_ok(chat.chat(self.cid))
        cc = chat.chat(self.cid) or {}
        std = (tuple(sorted(self.unblocked)), tuple(x.get("handle") or x.get("username") for x in cc.get("members") or []
                                                    if isinstance(x, dict) and x.get("blocked")),
               None if self.sel is None else tuple(sorted(self.sel)), cc.get("me_ref"), K.has(K.FMT), K.has(K.MENTION))
        sig = (tuple((m["id"], m.get("state"), m.get("ai_state"), m.get("fstate"), _x3sig(m)) for m in shown), c.get("more"), q,
               len(chat.notes), c.get("error"), self.loaded, cleared, x3, std)
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
        rows, ticks = {}, {}
        for m in shown:
            day = _day_label(m.get("created") or (m.get("ts") or 0) / 1000)
            if prev is None or _day_label(prev.get("created") or (prev.get("ts") or 0) / 1000) != day:
                d = _lbl(day, ("caption", "dim-label", "hk-day"), xalign=0.5, wrap=False, margin_top=8, margin_bottom=4)
                self.msgs.append(d)
            first = prev is None or _who(prev) != _who(m) or (m.get("created") or 0) - (prev.get("created") or 0) > 600
            key = (m["id"], m.get("state"), m.get("ai_state"), first, group, bool(m.get("reply_to") and m["reply_to"] in by_id),
                   len(chat.notes), m.get("fstate"), _x3sig(m), x3,
                   _x3sig(by_id.get(m["reply_to"])) if m.get("reply_to") else None, std,
                   None if self.sel is None else m["id"] in self.sel)
            row = old.pop(key, None) or keep.get(key)
            if row is None:
                self._imgs_building = []
                row = self._bubble(m, by_id, group, first)
                row.hk_imgs = self._imgs_building
                self._imgs_building = None
            keep[key] = row
            imgs += row.hk_imgs
            self.msgs.append(row)
            rows[m["id"]] = row
            slot = getattr(row, "hk_tick", None)
            if slot is not None:
                old_t = self.ticks.get(m["id"])
                ticks[m["id"]] = (slot, m, old_t[2] if old_t and old_t[0] is slot else None)
            prev = m
        for row in old.values():                          # nicht mehr angezeigt: wartende Bilder verwerfen
            for w in row.hk_imgs:
                w.drop()
        if self.pending_row is not None and not q:        # laufende KI-Antwort bleibt unten
            self.msgs.append(self.pending_row)
        self.bubbles, self.img_widgets = keep, imgs
        self.rows_by_id, self.ticks = rows, ticks
        self._update_ticks()
        self._update_rx()
        self._pins()
        self._vis_soon()
        if q:
            self.search_count.set_label(ngettext("{n} match", "{n} matches", len(shown)).format(n=len(shown)))
        if stick or not self.loaded:
            self._to_bottom()
        if self.loaded and self.win.is_active():
            self._mark_read()
            # (2.4) die neue Zeile hat jetzt noch keinen Platz (_receipt_visible waere falsch): nach dem Anordnen und
            # dem Rollen nach unten (_to_bottom, 120 ms) noch einmal - sonst kaeme die Bestaetigung erst beim Rollen
            self._rc_soon()

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
        if chat.open_chat == self.cid and self.loaded:
            chat.mark_read(self.cid, visible=self._receipt_visible)

    def _unread_check(self):
        """(2.4) Die Chat-Liste kam (Live-Ereignis) nach dem Gelesen-Melden an und zaehlt diesen offenen, sichtbaren Chat
        noch als ungelesen (Zaehler, Glocke): gleich noch einmal als gelesen melden"""
        ch = chat.chat(self.cid) or {}
        if ch.get("unread") and self.loaded and chat.open_chat == self.cid and self.win is not None and self.win.is_active() \
                and shown(self):
            self._rc_soon()

    # --- Gelesen-Bestaetigungen (2.4) ----------------------------------------------------------------------------------
    def _receipt_visible(self):
        """Bestaetigen nur, was wirklich zu sehen ist: Fenster aktiv, Unterhaltung offen, Zeile der Nachricht im Bild"""
        if self.win is None or not self.win.is_active() or not shown(self) or chat.open_chat != self.cid:
            return False
        w = chat.receipt_want(self.cid)
        row = self.rows_by_id.get((w or {}).get("id"))
        if row is None:
            return False
        ok, r = row.compute_bounds(self.clamp)
        if not ok or row.get_height() <= 0:
            return False
        adj = self.scroll.get_vadjustment()
        y0 = r.get_y() - adj.get_value()
        return y0 + r.get_height() > 0 and y0 < (adj.get_page_size() or 0)

    def _update_ticks(self):
        """Haken eigener Nachrichten an Ort und Stelle aktualisieren (ohne die Blasen neu zu bauen)"""
        c = chat.chat(self.cid) or {}
        rc = chat.rcpts.get(self.cid) or {}
        for mid, (slot, m, cur) in list(self.ticks.items()):
            t = S.tick(chat.me, c, m, rc)
            if t == cur:
                continue
            self.ticks[mid] = (slot, m, t)
            while (w := slot.get_first_child()):
                slot.remove(w)
            if isinstance(t, tuple):
                slot.append(self._tick_widget(c, m, t))
            slot.set_visible(isinstance(t, tuple))

    def _tick_widget(self, c, m, t):
        group = c.get("kind") != "dm"
        mark, text = S.tick_text(t, group)
        label = mark + (f" {t[1]}" if group and t[0] == "read" and not t[2] else "")
        if group and t[0] == "read":                     # Gruppen: Tippen zeigt "Gelesen von …"
            w = Gtk.Button(label=label, valign=Gtk.Align.CENTER)
            w.add_css_class("flat")
            w.add_css_class("hk-tick-btn")
            w.connect("clicked", lambda *_: self.read_by(m))
        else:
            w = Gtk.Label(label=label)
            w.add_css_class("caption")
        w.add_css_class("hk-tick")
        if t[0] == "read":
            w.add_css_class("hk-tick-read")
        w.set_tooltip_text(text)
        w.update_property([Gtk.AccessibleProperty.LABEL], [text])
        return w

    def read_by(self, m):
        """Gruppe: "Gelesen von" - Name und Zeit je Person, neueste zuerst"""
        c = chat.chat(self.cid) or {}
        rd = sorted(S.readers(c, m, chat.rcpts.get(self.cid) or {}) if S.rc_ok(chat.me, c) else [],
                    key=lambda r: -(r.get("ts") or 0))
        d = Adw.AlertDialog(heading=_("Read by"), body=_("People who turned off read receipts don't appear here."))
        lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lb.add_css_class("boxed-list")
        if not rd:
            lb.append(Adw.ActionRow(title=_("Nobody has read it yet.")))
        for r in rd:
            ts = r.get("ts")
            sub = (_day_label(ts / 1000) + " " + _time(ts / 1000)) if ts else ""
            lb.append(Adw.ActionRow(title=GLib.markup_escape_text(r["name"]), subtitle=GLib.markup_escape_text(sub)))
        d.set_extra_child(lb)
        d.add_response("close", _("Close"))
        d.present(self.win)
        return d

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

    def _sys_row(self, m):
        """Systemzeile (2.3): mittig, z. B. "Anna hat die KI in diesem Chat eingeschaltet." oder "Bert (b.example) hat
        Dora (c.example) hinzugefuegt." """
        k = m.get("sys") or {}
        c = chat.chat(self.cid) or {}
        text = sys_text(k, c.get("my_handle"))          # KI an/aus, Mitglieder (mit Herkunft), jetzt ueber Servergrenzen
        box = Gtk.Box(spacing=8, halign=Gtk.Align.CENTER, margin_top=6, margin_bottom=6)
        box.add_css_class("hk-call-entry")
        box.add_css_class("hk-sys-entry")
        box.append(_lbl(text, ("hk-call-text",), wrap=True, justify=Gtk.Justification.CENTER))
        tm = _lbl(_time(m.get("created") or (m.get("ts") or 0) / 1000), ("caption", "dim-label"), wrap=False)
        box.append(tm)
        box.update_property([Gtk.AccessibleProperty.LABEL], [text + " – " + tm.get_label()])
        box.set_focusable(True)
        return box

    def _tomb_row(self, m):
        """(2.5, moddel1) Grabstein: "Nachricht von {name} – von einem Admin geloescht" """
        who = (m.get("sender") or {}).get("name")
        text = "🗑 " + (_("Message from {name} – deleted by an admin").format(name=who) if who else _("Deleted by an admin"))
        box = Gtk.Box(spacing=8, halign=Gtk.Align.CENTER, margin_top=4, margin_bottom=4)
        box.add_css_class("hk-call-entry")
        box.add_css_class("hk-tomb")
        box.append(_lbl(text, ("hk-call-text", "dim-label"), justify=Gtk.Justification.CENTER))
        tm = _lbl(_time(m.get("created") or (m.get("ts") or 0) / 1000), ("caption", "dim-label"), wrap=False)
        box.append(tm)
        box.update_property([Gtk.AccessibleProperty.LABEL], [text + " – " + tm.get_label()])
        box.set_focusable(True)
        return box

    def _blocked_row(self, m):
        """(2.5, block2) Nachricht einer gesperrten Person in einer Gruppe: eingeklappt, "Anzeigen" """
        box = Gtk.Box(spacing=8, halign=Gtk.Align.CENTER, margin_top=4, margin_bottom=4)
        box.add_css_class("hk-call-entry")
        box.add_css_class("hk-blockedmsg")
        box.append(_lbl("⛔ " + _("Message from a blocked person"), ("hk-call-text", "dim-label")))
        b = _button(_("Show"), ("flat",), lambda: (self.unblocked.add(m["id"]), self.render()), valign=Gtk.Align.CENTER)
        box.append(b)
        return box

    def _bubble(self, m, by_id, group, first):
        if m.get("state") == "call":
            return self._call_row(m)
        if m.get("state") == "sys":
            return self._sys_row(m)
        if m.get("state") == "tomb":
            return self._tomb_row(m)
        c0 = chat.chat(self.cid) or {}
        if K.has(K.BLOCK2) and m["id"] not in self.unblocked and K.grp_blocked(c0, m):
            return self._blocked_row(m)
        row = self._bubble0(m, by_id, group, first)
        if self.sel is None or m.get("state") not in ("ok", "unverified"):
            return row
        # (2.5) Mehrfachauswahl: Haken vor der Nachricht
        outer = Gtk.Box(spacing=6)
        cb = Gtk.CheckButton(active=m["id"] in self.sel, valign=Gtk.Align.CENTER)
        cb.update_property([Gtk.AccessibleProperty.LABEL], [_("Select message")])
        cb.connect("toggled", lambda b: self._sel_toggle(m["id"], b.get_active()))
        outer.append(cb)
        row.set_hexpand(True)
        outer.append(row)
        for a in ("hk_tick", "hk_wrap", "hk_b", "hk_acc", "hk_rxsig"):
            if hasattr(row, a):
                setattr(outer, a, getattr(row, a))
        return outer

    def _bubble0(self, m, by_id, group, first):
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
                av = Adw.Avatar(size=28, text=avatars.initials_text(sname), show_initials=True, valign=Gtk.Align.END)
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
            qthumb, qlabel = reply_media(self.cid, q)
            qb.append(_lbl(qlabel or snippet(q), ("caption",), max_width_chars=50, lines=2, ellipsize=Pango.EllipsizeMode.END))
            if qthumb is not None:                       # (2.5) Zitat mit Vorschaubild bzw. Symbol des Anhangs
                qb.remove_css_class("hk-quote")
                qb.set_valign(Gtk.Align.CENTER)
                qr = Gtk.Box(spacing=8)
                qr.add_css_class("hk-quote")
                qr.append(qb)
                qr.append(qthumb)
                b.append(qr)
            else:
                b.append(qb)
        acc = [sname if not mine else pgettext("subject", "you")]
        if is_ai:
            acc.append(asker)
        if st == "undecryptable":
            lb = _lbl(_("Can't be decrypted"), ("hk-italic", "dim-label"))
            lb.set_tooltip_text(_("This message can't be decrypted on this device."))
            b.append(lb)
            acc.append(_("Can't be decrypted"))
        else:
            text = m.get("text") or ""
            cm = chat.chat(self.cid) or {}
            if text.strip():
                lb = Gtk.Label(xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, selectable=True, max_width_chars=60)
                # (2.5) Formatierung (chatfmt1) und Erwaehnungen (chatmention1) - Pango-Markup, alles escaped
                if K.has(K.FMT) or K.has(K.MENTION):
                    lb.set_markup(K.message_markup(text, chat.notes, cm, (chat.me or {}).get("name"), fmt=K.has(K.FMT),
                                                   mention=K.has(K.MENTION)))
                else:
                    lb.set_markup(text_markup(text, chat.notes))
                lb.connect("activate-link", self._link)
                lb.add_css_class("hk-bubble-text")
                b.append(lb)
                plain = K.plain_mentions(text, cm, (chat.me or {}).get("name")) if K.has(K.MENTION) else text
                acc.append(NOTE_RX.sub("📄", K.strip(plain) if K.has(K.FMT) else plain))
                if K.has(K.MENTION) and K.mentions_me(cm, text):
                    b.add_css_class("hk-mention-me")
            pv = K.preview_of(m.get("body")) if K.has(K.UNFURL) else None
            if pv:                                       # (2.5, unfurl1) Vorschau aus der Nachricht (nichts nachladen)
                b.append(self._preview_card(pv))
                acc.append(pv.get("title") or pv["url"])
            for f in m.get("files") or []:
                b.append(self._file(f))
                acc.append(_("Voice message") + " " + voice.fmt(f.get("dur")) if f.get("voice") else f.get("n") or _("File"))
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
        if m.get("fstate") == "pending":                 # (2.3) wartet beim eigenen Server auf den anderen Server
            pe = _lbl("⏳", ("caption",), wrap=False)
            pe.set_tooltip_text(_("Will be delivered as soon as the other server is reachable."))
            meta.append(pe)
            acc.append(_("Will be delivered as soon as the other server is reachable."))
        elif m.get("fstate") == "failed":
            fl = _lbl("⚠ " + _("not delivered"), ("caption", "hk-warn-text"), wrap=False)
            fl.set_tooltip_text(_("Not delivered – choose “Send again” in the message menu."))
            meta.append(fl)
            b.add_css_class("hk-bubble-warn")
            acc.append(_("not delivered"))
        if m.get("edited_at"):                           # (2.5) bearbeitet - Zeitpunkt im Tooltip
            ed = _lbl(_("edited"), ("caption", "dim-label", "hk-edited"), wrap=False)
            et = float(m["edited_at"])
            ed.set_tooltip_text(_("Edited: {time}").format(time=_day_label(et) + " " + _time(et)))
            meta.append(ed)
            acc.append(_("edited"))
        tm = _lbl(_time(m.get("created") or (m.get("ts") or 0) / 1000), ("caption", "dim-label"), wrap=False)
        meta.append(tm)
        if mine:                                         # (2.4) ✓ zugestellt, ✓✓ gelesen - _update_ticks fuellt ihn
            row.hk_tick = Gtk.Box(visible=False)
            meta.append(row.hk_tick)
        b.append(meta)
        c = chat.chat(self.cid) or {}
        if m.get("rx") or can_react(c, m):               # (2.5) Platz fuer die Reaktionen unter der Blase
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, halign=Gtk.Align.END if mine else Gtk.Align.START)
            col.append(b)
            wrap = Gtk.Box(halign=Gtk.Align.END if mine else Gtk.Align.START, visible=False)   # FlowBox direkt mit halign lief ueber
            col.append(wrap)
            row.append(col)
            row.hk_wrap, row.hk_b, row.hk_acc, row.hk_rxsig = wrap, b, acc, None
            self._fill_rx(row, m)
        else:
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

    def _preview_card(self, pv, closable=None):
        """Karte der Linkvorschau: Bild (verschluesselter Anhang), Seite, Titel, Beschreibung; Klick oeffnet die Adresse"""
        card = Gtk.Box(spacing=8)
        card.add_css_class("hk-unf-card")
        if pv.get("img"):
            pic = Gtk.Picture(content_fit=Gtk.ContentFit.COVER, width_request=64, height_request=64, valign=Gtk.Align.START)
            chatmedia.request(self.cid, dict(pv["img"], n="preview.jpg"), lambda t, _wh: t is not None and pic.set_paintable(t))
            card.append(pic)
        elif pv.get("tex") is not None:
            card.append(Gtk.Picture(paintable=pv["tex"], content_fit=Gtk.ContentFit.COVER, width_request=64, height_request=64,
                                    valign=Gtk.Align.START))
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True)
        if pv.get("site"):
            col.append(_lbl(pv["site"], ("caption", "dim-label"), wrap=False, ellipsize=Pango.EllipsizeMode.END))
        if pv.get("title"):
            col.append(_lbl(pv["title"], ("heading",), lines=2, ellipsize=Pango.EllipsizeMode.END))
        if pv.get("desc"):
            col.append(_lbl(pv["desc"], ("caption",), lines=3, ellipsize=Pango.EllipsizeMode.END))
        card.append(col)
        if closable is not None:
            x = flat_button(icon="window-close-symbolic", tooltip=_("Remove preview"), cb=closable)
            x.set_valign(Gtk.Align.START)
            card.append(x)
            return card
        btn = Gtk.Button(child=card, tooltip_text=pv["url"])
        btn.add_css_class("flat")
        btn.add_css_class("hk-unf-msg")
        btn.connect("clicked", lambda *_: self.win.open_uri(pv["url"]))
        return btn

    def _pins(self):
        """(2.5, msgpin1) Leiste der angehefteten Nachrichten (neueste zuerst, Klick: hinspringen, dann die naechste)"""
        c = chat.chat(self.cid) or {}
        ids = [x for x in c.get("pins") or [] if isinstance(x, str)] if K.has(K.MSGPIN) else []
        self.pin_bar.set_visible(bool(ids))
        if not ids:
            return
        i = min(self.pin_i, len(ids) - 1)
        mid = ids[len(ids) - 1 - i]
        m = next((x for x in (chat.cache.get(self.cid) or {}).get("msgs") or [] if x.get("id") == mid), None)
        head = _("Pinned ({i}/{n})").format(i=i + 1, n=len(ids)) if len(ids) > 1 else _("Pinned")
        if m is not None:
            t = K.strip(K.plain_mentions(snippet(m), c, (chat.me or {}).get("name")))
            body = (((m.get("sender") or {}).get("name") or "") + ": " if (m.get("sender") or {}).get("name") else "") + t
        else:
            body = _("Older message")
        self.pin_label.set_label(head + "\n" + body)
        self.pin_mid = mid
        self.pin_x.set_visible(K.rights(c)["pin"])

    def _pin_jump(self):
        c = chat.chat(self.cid) or {}
        ids = c.get("pins") or []
        row = self.rows_by_id.get(getattr(self, "pin_mid", None))
        if row is not None:
            ok, rect = row.compute_bounds(self.msgs)
            if ok:
                adj = self.scroll.get_vadjustment()
                adj.set_value(max(0, rect.get_y() - adj.get_page_size() / 3))
            row.add_css_class("hk-flash")
            GLib.timeout_add(1200, lambda: (row.remove_css_class("hk-flash"), False)[1])
        else:
            _toast(self.win, _("The message isn't loaded yet – scroll further up."))
        if len(ids) > 1:
            self.pin_i = (self.pin_i + 1) % len(ids)
            self._pins()

    def _pin_drop(self):
        mid = getattr(self, "pin_mid", None)
        if mid:
            self.pin_msg(mid, False)

    def pin_msg(self, mid, on):
        self.pin_i = 0
        chat.pin_msg(self.cid, mid, on, done=lambda _p: _toast(self.win, _("Pinned.") if on else _("Unpinned.")),
                     error=lambda e: _toast(self.win, str(e)))

    def _fill_rx(self, row, m):
        """Chips einer Zeile neu (nur der Bereich unter der Blase; die Blase bleibt - siehe _update_rx)"""
        sig = _rxsig(m)
        if sig == row.hk_rxsig:
            return
        row.hk_rxsig = sig
        c = chat.chat(self.cid) or {}
        wrap = row.hk_wrap
        while (w := wrap.get_first_child()):
            wrap.remove(w)
        chips = chatx3.reaction_chips(self, c, m) if m.get("rx") else None
        if chips is not None:
            wrap.append(chips)
        wrap.set_visible(chips is not None)
        acc = list(row.hk_acc)
        for e, who, _mn in rx_groups(m.get("rx"), c.get("my_handle")):
            acc.append(_("{emoji} from {names}").format(emoji=e, names=", ".join(chatx3.rx_name(c, h) for h in who)))
        row.hk_b.update_property([Gtk.AccessibleProperty.LABEL], [" – ".join(acc)])

    def _update_rx(self):
        """(2.5) Reaktionen geaendert: nur die Chips der betroffenen Zeilen. Die Blasen neu zu bauen, gleich nachdem ein Menue
        (Schnellleiste) geschlossen wurde, liess GTK 4.14 abstuerzen (gdk_surface_request_motion, Testrunde 2.5)."""
        for m in (chat.cache.get(self.cid) or {}).get("msgs") or []:
            row = self.rows_by_id.get(m.get("id"))
            if row is None:
                continue
            if getattr(row, "hk_wrap", None) is None:
                if m.get("rx"):                          # (selten) Zeile ohne Platz fuer Chips: dann doch neu zeichnen
                    self.sig = None
                    self.bubbles = {k: v for k, v in self.bubbles.items() if v is not row}
                    GLib.idle_add(lambda: (self.render(), False)[1])
                    return
                continue
            self._fill_rx(row, m)

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
        c0 = chat.chat(self.cid) or {}
        if m.get("state") != "undecryptable":
            ents.append((_("Reply"), lambda: self.set_reply(m)))
            if can_edit(c0, m, lim=x3_limits(chat.me)):   # (2.5) eigene Nachricht bearbeiten (24 h, hoechstens 20-mal)
                ents.append((_("Edit"), lambda: self.edit_start(m)))
            if m.get("rx"):
                ents.append((_("Reactions …"), lambda: chatx3.reactions_dialog(self.win, chat.chat(self.cid) or {}, m)))
            if m.get("text"):
                ents.append((_("Copy text"), lambda: (self.win.copy_text(m["text"]), _toast(self.win, _("Copied.")))))
            fs = [f for f in m.get("files") or [] if isinstance(f, dict) and f.get("id") and chatvideo.fmt(f) <= 2]
            if len(fs) == 1:                             # (2.5) ein Anhang: Speichern unter, Bild kopieren
                f1 = fs[0]
                if chatmedia.is_image(f1) and chatvideo.fmt(f1) == 1:
                    ents.append((_("Copy image"), lambda: self.copy_image(f1)))
                if int(f1.get("s") or 0) > 0:
                    ents.append((_("Save as …"), lambda: self._save_file(f1)))
        if m.get("fstate") == "failed" and m.get("mine") and isinstance(m.get("body"), dict):     # (2.3)
            ents.append((_("Send again"), lambda: chat.resend(self.cid, m, error=lambda e: _toast(self.win, _("Not sent: {err}").format(err=e)))))
        c = chat.chat(self.cid) or {}
        if m.get("mine") and c.get("kind") != "dm" and isinstance(S.tick(chat.me, c, m, chat.rcpts.get(self.cid)), tuple) \
                and S.rc_ok(chat.me, c):                 # (2.4) Gruppe: wer hat gelesen
            ents.append((_("Read by …"), lambda: self.read_by(m)))
        std = K.rights(c)
        if m.get("state") in ("ok", "unverified") and not m.get("fstate"):    # (2.5) Anheften, Auswahl, Weiterleiten
            if std["pin"]:
                pinned = m.get("id") in (c.get("pins") or [])
                ents.append((_("Unpin") if pinned else _("Pin"), lambda: self.pin_msg(m["id"], not pinned)))
            ents.append((_("Forward …"), lambda: SV.ForwardDialog(self, [m]).present(self.win)))
            ents.append((_("Select"), lambda: self.sel_start(m["id"])))
        if not m.get("mine") and m.get("sender") and m.get("state") != "undecryptable":
            tg = K.block_target(c, m)
            if std["block"] and tg and c.get("kind") != "dm" and not K.grp_blocked(c, m) and not is_remote(c):
                nm = (m.get("sender") or {}).get("name") or "?"
                ents.append((_("Block {name}").format(name=nm), lambda: SV.block_person(self.win, self.win, tg, nm), True))
            if std["report"]:
                ents.append((_("Report …"), lambda: SV.ReportDialog(self.win, c, [m]).present(self.win)))
        if m.get("mine"):
            ents += [None, (_("Delete for everyone"), lambda: self._ask_delete(m), True)]
        elif std["moderate"] and m.get("sender"):        # (2.5, moddel1) Admin loescht fremde Nachricht fuer alle
            ents += [None, (_("Delete (admin)"), lambda: self._ask_mod_delete(m), True)]
        if not ents:
            return None
        # (2.5) Das Menue haengt an der Unterhaltung (dauerhaft), nicht an der Blase: die wird bei Reaktionen und Bearbeitungen
        # neu gebaut - ein Popover an der alten Blase liess GTK beim naechsten Menue abstuerzen (gdk_surface_request_motion)
        pop = menu_popover(None, ents)
        chatx3.point_at(pop, w, self)
        pop.connect("closed", lambda p: GLib.idle_add(lambda: (p.get_parent() is not None and p.unparent(), False)[1]))
        if can_react(c0, m):                             # (2.5) Schnellleiste oben im Menue
            mine = c0.get("my_handle")
            cur = next((x.get("e") for x in m.get("rx") or [] if x.get("by") == mine), "")

            def pick(e):                                  # erst nach dem Schliessen (die Blase wird danach neu gebaut)
                pop.popdown()
                GLib.timeout_add(80, lambda: (self.react(m, "" if e == cur else e), False)[1])

            def more():
                pop.popdown()
                GLib.timeout_add(80, lambda: (w.get_root() is not None and chatx3.emoji_chooser(
                    w, lambda e: self.react(m, "" if e == cur else e), host=self), False)[1])
            main = pop.get_child().get_child_by_name("main")
            main.prepend(Gtk.Separator(margin_top=3, margin_bottom=3))
            main.prepend(chatx3.quick_bar(cur, pick, more))
        pop.popup()
        self._menu_pop = pop
        return pop

    # --- Reaktionen und Bearbeiten (2.5) -------------------------------------------------------------------------------
    def toast(self, text):
        _toast(self.win, text)

    def react(self, m, e):
        self._focus_safe()
        chat.react(self.cid, m.get("id"), e, error=self.toast)

    def _focus_safe(self):
        """Fokus vor dem Neubau der Blasen ins Eingabefeld: steht er (nach dem Schliessen eines Menues) auf einem Widget, das
        gleich ersetzt wird, stuerzte GTK 4.14 beim naechsten Menue ab (gdk_surface_request_motion, Testrunde 2.5)"""
        f = self.win.get_focus() if self.win is not None else None
        while f is not None and f is not self.msgs and not isinstance(f, Gtk.Popover):
            f = f.get_parent()
        if f is self.msgs or isinstance(f, Gtk.Popover):
            self.entry.grab_focus()

    def edit_start(self, m):
        """Bearbeiten im Eingabefeld: Leiste "Bearbeiten · Abbrechen" ueber dem Feld, Text der Nachricht"""
        if not can_edit(chat.chat(self.cid), m, lim=x3_limits(chat.me)):
            return
        self.set_reply(None)
        self.editing = m
        self.edit_label.set_label(_("Edit message") + "\n" + snippet(m))
        self.edit_bar.set_visible(True)
        buf = self.entry.get_buffer()
        buf.set_text(m.get("text") or "")
        buf.place_cursor(buf.get_end_iter())
        self._mic_update()
        self.entry.grab_focus()

    def edit_cancel(self, clear=True):
        self.editing = None
        self.edit_bar.set_visible(False)
        if clear:
            self.entry.get_buffer().set_text("")
        self._mic_update()
        self.entry.grab_focus()

    def _send_edit(self, text):
        m = self.editing
        if text == (m.get("text") or "") or not text.strip():
            self.edit_cancel()
            return
        for t, nid in sorted(self.mentions.items(), key=lambda x: -len(x[0])):
            if t in text:
                text = text.replace(t, f"[[note:{nid}]]")
        self.send_btn.set_sensitive(False)
        self.entry.set_sensitive(False)

        def done():
            self.entry.set_sensitive(True)
            self.mentions = {}
            self.edit_cancel()

        def err(e):
            self.entry.set_sensitive(True)
            self._changed(self.entry.get_buffer())
            self.toast(_("Not edited: {err}").format(err=e))
        chat.send_edit(self.cid, m["id"], text, done=done, error=err)

    # --- Sprachnachrichten (2.5) ---------------------------------------------------------------------------------------
    def _voice_ok(self):
        c = chat.chat(self.cid)
        return x3_ok(c) and has_feature(chat.me, "voice") and voice.can_record()

    def _mic_update(self):
        if not hasattr(self, "mic_btn"):
            return
        buf = self.entry.get_buffer()
        self.mic_btn.set_visible(self._voice_ok() and buf.get_char_count() == 0 and not self.files and self.editing is None
                                 and self.rec_bar is None)
        self.send_btn.set_visible(not self.mic_btn.get_visible())

    def record_start(self):
        if self.rec_bar is not None or not self._voice_ok():
            return
        lim = x3_limits(chat.me)
        self.rec_bar = chatx3.RecordBar(self, max_s=lim["voice_max_s"], kbps=lim["voice_kbps"])
        self.input_row.set_visible(False)
        self.composer_box.append(self.rec_bar)
        self.rec_bar.start()

    def record_ended(self, text):
        """Aufnahme-Leiste weg (abgebrochen, gesendet, zu kurz, Fehler) - text: Hinweis"""
        if self.rec_bar is not None:
            if self.rec_bar.get_parent() is not None:
                self.composer_box.remove(self.rec_bar)
            self.rec_bar = None
        self.input_row.set_visible(True)
        self._mic_update()
        if text:
            self.toast(text)
        self.entry.grab_focus()

    def send_voice(self, res):
        cid, reply = self.cid, self.reply["id"] if self.reply else None
        bar = self.rec_bar

        def work():
            return chat.send_voice(cid, res["data"], res["dur"], res["wf"], reply_to=reply,
                                   progress=lambda a, t: GLib.idle_add(lambda: (bar.progress(a / max(1, t)), False)[1]))

        def fin(_r):
            if bar is self.rec_bar:
                bar.sent(True)
            self.set_reply(None)
            self.at_bottom = True
            chat.load_messages(cid)
            chat._list_soon()

        def err(e):
            if bar is self.rec_bar:
                bar.sent(False, _("Not sent: {err}").format(err=e))
        bg(work, done=fin, error=err)

    def _voice_view(self, f):
        """Spieler einer Sprachnachricht - einer je Anhang, ueber das Neuzeichnen hinweg (laufende Wiedergabe bleibt)"""
        vv = self.voice_views.get(f["id"])
        if vv is not None:
            par = vv.get_parent()
            if par is not None:
                par.remove(vv)
            return vv
        cid = self.cid
        vv = chatx3.VoiceView(f, lambda: chatvideo.VideoSource(cid, f), on_ended=self._voice_next)
        self.voice_views[f["id"]] = vv
        return vv

    def _voice_next(self, vv):
        """nach dem Ende die naechste Sprachnachricht im Verlauf abspielen"""
        msgs = (chat.cache.get(self.cid) or {}).get("msgs") or []
        ids = [vf["id"] for vf in (voice_file(m) for m in msgs) if vf]
        try:
            i = ids.index(vv.f["id"])
        except ValueError:
            return
        if i + 1 < len(ids):
            nxt = self.voice_views.get(ids[i + 1])
            if nxt is not None and nxt.get_parent() is not None:
                nxt.play()

    def voice_stop_all(self):
        for vv in self.voice_views.values():
            vv.stop()

    # --- Mehrfachauswahl (2.5): Weiterleiten, Fuer mich loeschen ------------------------------------------------------
    def sel_start(self, mid=None):
        self.sel = {mid} if mid else set()
        self._sel_draw()
        self.render()

    def sel_end(self):
        self.sel = None
        self._sel_draw()
        self.render()

    def _sel_toggle(self, mid, on):
        if self.sel is None:
            return
        (self.sel.add if on else self.sel.discard)(mid)
        self._sel_draw()

    def _sel_msgs(self):
        ids = self.sel or set()
        return [m for m in (chat.cache.get(self.cid) or {}).get("msgs") or [] if m.get("id") in ids]

    def _sel_draw(self):
        on = self.sel is not None
        self.sel_bar.set_visible(on)
        if on:
            self.composer_box.set_visible(False)
        else:
            self._state_sig = None
            self._state()
        n = len(self.sel or ())
        self.sel_label.set_label(ngettext("{n} selected", "{n} selected", n).format(n=n))
        self.sel_fwd.set_sensitive(0 < n <= K.FWD_MAX)
        self.sel_del.set_sensitive(n > 0)

    def _sel_forward(self):
        lst = self._sel_msgs()
        if len(lst) > K.FWD_MAX:
            _toast(self.win, _("Forward at most {n} messages at once.").format(n=K.FWD_MAX))
            return
        if lst:
            SV.ForwardDialog(self, lst, done=self.sel_end).present(self.win)

    def _sel_hide(self, confirm=True):
        """"Fuer mich loeschen": POST .../hide (alle meine Geraete); aelterer Server: nur hier ausblenden"""
        lst = self._sel_msgs()
        if not lst:
            return
        ids = [m["id"] for m in lst]
        win, n = self.win, len(ids)

        def go():
            def done(all_dev):
                self.sel_end()
                _toast(win, ngettext("{n} message deleted for you.", "{n} messages deleted for you.", n).format(n=n) if all_dev else
                       ngettext("{n} message hidden on this device only – the server can't do that yet.",
                                "{n} messages hidden on this device only – the server can't do that yet.", n).format(n=n))
            chat.hide_msgs(self.cid, ids, done=done, error=lambda e: _toast(win, str(e)))
        if not confirm:
            go()
            return
        from .publish import confirm as ask
        ask(self.win, _("Delete for you?"), _("“Delete for me” hides the messages on all your devices – the others still see them."),
            _("Delete for me"), go)

    def _ask_mod_delete(self, m):
        from .publish import confirm
        name = (m.get("sender") or {}).get("name") or "?"
        confirm(self.win, _("Delete message for everyone?"),
                _("As an admin you delete {name}'s message for everyone. The chat keeps the note “Deleted by an admin”.").format(name=name),
                _("Delete"), lambda: chat.mod_delete(self.cid, m["id"], error=lambda e: _toast(self.win, str(e))))

    def _ask_delete(self, m):
        from .publish import confirm
        confirm(self.win, _("Delete message for everyone?"),
                _("The message and its attachments will be deleted from the server. Anyone who already read it may have a copy."),
                _("Delete"), lambda: chat.delete_message(self.cid, m["id"], error=lambda e: _toast(self.win, str(e))))

    # --- Anhaenge ------------------------------------------------------------------------------------------------------
    def _file(self, f):
        name = f.get("n") or _("File")
        x = chatvideo.fmt(f)
        if x > 2:                                          # neueres Format: nicht still scheitern
            btn = Gtk.Button(sensitive=False)
            bx = Gtk.Box(spacing=8)
            bx.append(Gtk.Image.new_from_icon_name("mail-attachment-symbolic"))
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            col.append(_lbl(name, wrap=False, ellipsize=Pango.EllipsizeMode.MIDDLE, max_width_chars=36))
            col.append(_lbl(_("Open with a newer Saga version"), ("caption", "dim-label"), wrap=False))
            bx.append(col)
            btn.set_child(bx)
            btn.add_css_class("hk-file-btn")
            return btn
        if f.get("voice") and x == 2 and voice.can_play():  # (2.5) Sprachnachricht: Wellenform-Spieler
            return self._voice_view(f)
        if chatvideo.is_video(f):
            return self._video(f)
        if chatmedia.is_image(f) and x == 1:
            img = ChatImage(self.cid, f)
            if self._imgs_building is not None:
                self._imgs_building.append(img)
            btn = Gtk.Button(child=img, tooltip_text=name, halign=Gtk.Align.START)
            btn.add_css_class("flat")
            btn.add_css_class("hk-img-btn")
            btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Open image: {name}").format(name=name)])
            btn.connect("clicked", lambda *_: ImageViewer(self, f).present(self.win))
            return btn
        size = int(f.get("s") or 0)
        btn = Gtk.Button()
        bx = Gtk.Box(spacing=8)
        bx.append(Gtk.Image.new_from_icon_name("mail-attachment-symbolic"))
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        col.append(_lbl(name, wrap=False, ellipsize=Pango.EllipsizeMode.MIDDLE, max_width_chars=36))
        col.append(_lbl(human_size(size) if size else _("empty file"), ("caption", "dim-label"), wrap=False))
        bx.append(col)
        btn.set_child(bx)
        btn.add_css_class("hk-file-btn")
        # 4.11.: Antippen oeffnet den Anhang (wie iOS/Android); Speichern ueber den Knopf daneben
        btn.set_tooltip_text(_("Open attachment"))
        btn.set_sensitive(size > 0)
        btn.connect("clicked", lambda *_: self._open_file(f))
        save = Gtk.Button(icon_name="document-save-symbolic", tooltip_text=_("Save attachment"), valign=Gtk.Align.CENTER, sensitive=size > 0)
        save.add_css_class("flat")
        save.update_property([Gtk.AccessibleProperty.LABEL], [_("Save attachment")])
        save.connect("clicked", lambda *_: self._save_file(f))
        row = Gtk.Box(spacing=2, halign=Gtk.Align.START)
        row.append(btn)
        row.append(save)
        return row

    def copy_image(self, f):
        """(2.5) Bild-Anhang (Original) als Gdk.Texture in die Zwischenablage"""
        win = self.win
        bg(chatmedia.original, self.cid, f, done=lambda d: copy_image_data(win, d, lambda t: _toast(win, t)),
           error=lambda e: _toast(win, _("Image not copied: {err}").format(err=e)), media=True)

    def _open_file(self, f):
        """Oeffnen (4.11.): entschluesselt in einen privaten Ordner ($XDG_RUNTIME_DIR, nur fuer mich lesbar) und mit dem
        Standardprogramm oeffnen - Dokumente direkt, alles andere mit Rueckfrage (nie still ein Skript/Programm starten).
        Die Kopie wird nach chatfiles.KEEP_S geloescht."""
        cid = self.cid

        def work():
            path = chatfiles.private_path(f.get("n") or "datei")
            with open(path, "wb") as out:
                try:
                    chat.save_file(cid, f, out.write, lambda n, total: None)
                except BaseException:
                    out.close()
                    chatfiles.remove(path)
                    raise
            return path

        def fin(path):
            GLib.timeout_add_seconds(chatfiles.KEEP_S, lambda: (chatfiles.remove(path), False)[1])
            fl = Gtk.FileLauncher.new(Gio.File.new_for_path(path))
            fl.set_always_ask(not chatfiles.direct_open(f.get("m") or ""))
            fl.launch(self.win, None, None)

        bg(work, done=fin, error=lambda e: _toast(self.win, _("Attachment not loaded: {err}").format(err=e)), media=True)

    def _video(self, f):
        """Video (2.4) im Verlauf: nur das Poster (lazy wie Vorschaubilder), Dauer und Abspiel-Symbol - nie ein Player"""
        name = f.get("n") or _("Video")
        img = ChatImage(self.cid, f, wh=chatvideo.display_size(f), poster=chatmedia.thumb_ref(f) is not None)
        if self._imgs_building is not None:
            self._imgs_building.append(img)
        ov = Gtk.Overlay(child=img)
        play = Gtk.Image(icon_name="media-playback-start-symbolic", pixel_size=22, halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER)
        play.add_css_class("hk-video-play")
        ov.add_overlay(play)
        d = chatvideo.dur_text(f.get("dur")) if f.get("dur") else ""
        if d:
            dl = Gtk.Label(label=d, halign=Gtk.Align.END, valign=Gtk.Align.END, margin_end=6, margin_bottom=6)
            dl.add_css_class("hk-video-dur")
            ov.add_overlay(dl)
        btn = Gtk.Button(child=ov, halign=Gtk.Align.START, tooltip_text=f"{name} · {human_size(int(f.get('s') or 0))}")
        btn.add_css_class("flat")
        btn.add_css_class("hk-img-btn")
        btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Play video: {name}").format(name=name)])
        btn.connect("clicked", lambda *_: VideoViewer(self, f).present(self.win))
        return btn

    def _save_file(self, f, progress=None, done=None):
        """Speichern: Gtk.FileDialog, entschluesselt als Strom (v2 Block fuer Block, nie ganz im Speicher)"""
        name = re.sub(r"[/\\\x00-\x1f]", "-", f.get("n") or "datei").strip(". ") or "datei"
        fd = Gtk.FileDialog(title=_("Save attachment"), initial_name=name)
        cid = self.cid

        def chosen(d, res):
            try:
                gf = d.save_finish(res)
            except GLib.Error:
                return
            if gf is None:
                return

            def prog(n, total):
                if progress:
                    GLib.idle_add(progress, n / max(1, total))

            def work():
                out = gf.replace(None, False, Gio.FileCreateFlags.REPLACE_DESTINATION, None)
                try:
                    chat.save_file(cid, f, lambda b: out.write_all(b, None), prog)
                except BaseException:
                    c = Gio.Cancellable()
                    c.cancel()                             # abgebrochen: Ziel bleibt unveraendert (keine halbe Datei)
                    try:
                        out.close(c)
                    except GLib.Error:
                        pass
                    raise
                out.close(None)
                return gf

            def fin(g):
                _toast(self.win, _("Saved: {name}").format(name=g.get_basename() or name))
                if done:
                    done(True)

            def err(e):
                _toast(self.win, _("Attachment not loaded: {err}").format(err=e))
                if done:
                    done(False)
            bg(work, done=fin, error=err, media=True)
        fd.save(self.win, None, chosen)

    # --- Eingabe -----------------------------------------------------------------------------------------------------
    def _composer(self):
        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        outer.add_css_class("hk-composer-bar")
        self.reply_bar = Gtk.Box(spacing=8, visible=False, margin_start=12, margin_end=12, margin_top=6)
        self.reply_bar.add_css_class("hk-quote")
        self.reply_label = _lbl("", ("caption",), hexpand=True, lines=2, ellipsize=Pango.EllipsizeMode.END)
        self.reply_bar.append(self.reply_label)
        self.reply_thumb = Gtk.Box(valign=Gtk.Align.CENTER)          # (2.5) Vorschaubild des Anhangs
        self.reply_bar.append(self.reply_thumb)
        x = flat_button(icon="window-close-symbolic", tooltip=_("Cancel reply"), cb=lambda: self.set_reply(None))
        self.reply_bar.append(x)
        outer.append(self.reply_bar)
        # (2.5) Bearbeiten: Leiste "Nachricht bearbeiten · Abbrechen" ueber dem Eingabefeld
        self.edit_bar = Gtk.Box(spacing=8, visible=False, margin_start=12, margin_end=12, margin_top=6)
        self.edit_bar.add_css_class("hk-quote")
        self.edit_bar.add_css_class("hk-edit-bar")
        self.edit_bar.append(Gtk.Image.new_from_icon_name("document-edit-symbolic"))
        self.edit_label = _lbl("", ("caption",), hexpand=True, lines=2, ellipsize=Pango.EllipsizeMode.END)
        self.edit_bar.append(self.edit_label)
        eb = Gtk.Button(label=_("Cancel"), valign=Gtk.Align.CENTER)
        eb.add_css_class("flat")
        eb.connect("clicked", lambda *_: self.edit_cancel())
        self.edit_bar.append(eb)
        outer.append(self.edit_bar)
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
        # (2.5, chatmention1) "@" im Eingabefeld: Mitglieder des Chats (Anzeigename) - Tab/Klick uebernimmt den ersten
        self.people_pop = None
        self.people_items = []
        self.people = {}             # eingefuegtes "@Name" -> Kennung (Token beim Senden)
        self.people_box = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, visible=False, margin_start=12, margin_end=12,
                                      max_children_per_line=6, row_spacing=2, column_spacing=4)
        self.people_box.add_css_class("hk-people-pick")
        self.people_box.update_property([Gtk.AccessibleProperty.LABEL], [_("Mention a person")])
        outer.append(self.people_box)
        # (2.5, unfurl1) Linkvorschau ueber dem Eingabefeld (mit ✕)
        self.unf_box = Gtk.Box(visible=False, margin_start=12, margin_end=12, margin_top=4)
        outer.append(self.unf_box)
        self.unf_url = self.unf_data = None
        self.unf_off = set()
        self.unf_req = 0
        self._unf_soon = Debounce(600, self._unf_check)
        self._draft_soon = Debounce(400, self._draft_save)
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
        # (2.5) Sprachnachricht: Mikrofon statt Senden, solange das Feld leer ist (Klick startet, Klick stoppt)
        self.mic_btn = Gtk.Button(icon_name="audio-input-microphone-symbolic", tooltip_text=_("Record voice message"),
                                  valign=Gtk.Align.END, visible=False)
        self.mic_btn.add_css_class("circular")
        self.mic_btn.add_css_class("hk-mic")
        self.mic_btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Record voice message")])
        self.mic_btn.connect("clicked", lambda *_: self.record_start())
        row.append(self.mic_btn)
        self.input_row = row
        outer.append(row)
        self._changed(buf)
        return outer

    def _changed(self, buf):
        empty = buf.get_char_count() == 0
        self.placeholder.set_visible(empty)
        self.send_btn.set_sensitive(not empty or bool(self.files))
        if not empty and self.editing is None:
            chat.send_typing(self.cid)
        self._ai_typing(buf)
        self._mic_update()
        if not hasattr(self, "people_box"):
            return
        self._people_typing(buf)
        if self.editing is None:
            self._unf_soon()
            if not getattr(self, "_draft_loading", False):
                self._draft_soon()

    # --- Erwaehnungen (2.5, chatmention1) ------------------------------------------------------------------------------
    def _people_typing(self, buf):
        c = chat.chat(self.cid) or {}
        items = []
        if K.has(K.MENTION) and self.editing is None:
            it = buf.get_iter_at_mark(buf.get_insert())
            before = buf.get_text(buf.get_start_iter(), it, False)
            m = re.search(r"(?:^|\s)@([^\s@]{0,30})$", before)
            if m:
                items = K.people_for(c, m.group(1))
                self.people_q = m.group(1)
        self.people_items = items
        self.people_pop = items or None
        while (w := self.people_box.get_first_child()):
            self.people_box.remove(w)
        for p in items:
            b = Gtk.Button()
            b.add_css_class("flat")
            bx = Gtk.Box(spacing=6)
            av = Adw.Avatar(size=20, text=avatars.initials_text(p["name"]), show_initials=True)
            avatars.attach(av, p.get("avatar"), 20)
            bx.append(av)
            bx.append(_lbl("@" + p["name"], (), wrap=False))
            b.set_child(bx)
            b.connect("clicked", lambda *_, p=p: self._people_pick(p))
            self.people_box.append(b)
        self.people_box.set_visible(bool(items))

    def _people_pick(self, p):
        buf = self.entry.get_buffer()
        it = buf.get_iter_at_mark(buf.get_insert())
        q = getattr(self, "people_q", "") or ""
        start = buf.get_iter_at_offset(it.get_offset() - len(q) - 1)
        buf.delete(start, it)
        label = "@" + p["name"]
        buf.insert_at_cursor(label + " ")
        self.people[label] = p["ref"]
        self.people_items = []
        self.people_pop = None
        self.people_box.set_visible(False)
        self.entry.grab_focus()
        return True

    # --- Linkvorschau (2.5, unfurl1) -------------------------------------------------------------------------------------
    def _unf_check(self):
        buf = self.entry.get_buffer()
        text = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)
        u = K.first_url(text) if K.unfurl_enabled() and self.editing is None else None
        if u == self.unf_url:
            return
        self.unf_url, self.unf_data = u, None
        self._unf_draw()
        if not u or u in self.unf_off:
            return
        self.unf_req += 1
        my = self.unf_req

        def got(d):
            if my != self.unf_req or self.unf_url != u:
                return
            self.unf_data = d if d and (d.get("title") or d.get("desc")) else None
            self._unf_draw()
        chat.unfurl(u, got, error=lambda e: None)

    def _unf_draw(self):
        while (w := self.unf_box.get_first_child()):
            self.unf_box.remove(w)
        d = self.unf_data
        self.unf_box.set_visible(d is not None)
        if d is None:
            return
        pv = K.preview_body(dict(d, url=self.unf_url)) or {}
        img = d.get("image") if isinstance(d.get("image"), dict) else None
        if img and img.get("b64"):
            try:
                import base64
                pv["tex"] = Gdk.Texture.new_from_bytes(GLib.Bytes.new(base64.b64decode(img["b64"])))
            except (GLib.Error, ValueError):
                pass
        card = self._preview_card(pv, closable=self._unf_close)
        card.set_hexpand(True)
        self.unf_box.append(card)

    def _unf_close(self):
        if self.unf_url:
            self.unf_off.add(self.unf_url)
        self.unf_data = None
        self._unf_draw()
        self.entry.grab_focus()

    def _unf_for(self, text):
        """Vorschau fuer den gesendeten Text (nur, wenn sie zur ersten Adresse passt und nicht entfernt wurde)"""
        u = K.first_url(text)
        if u and self.unf_data and self.unf_url == u and u not in self.unf_off:
            return dict(self.unf_data, url=u)
        return None

    def _unf_reset(self):
        self.unf_url = self.unf_data = None
        self.unf_off = set()
        self.unf_req += 1
        self._unf_draw()

    # --- Entwuerfe (2.5, nur auf diesem Geraet) ------------------------------------------------------------------------
    def _draft_save(self):
        if self.editing is not None or getattr(self, "_draft_loading", False):
            return
        buf = self.entry.get_buffer()
        t = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)
        if chatdrafts.save(self.cid, t, self.people):
            chat.emit("changed")

    def _draft_load(self):
        d = chatdrafts.load(self.cid)
        if not d:
            return
        self._draft_loading = True
        try:
            self.entry.get_buffer().set_text(d["t"])
            self.people = dict(d["p"])
        finally:
            self._draft_loading = False

    def _key(self, c, kv, code, state):
        if kv in (Gdk.KEY_Tab, Gdk.KEY_ISO_Left_Tab) and self.people_items and not state & Gdk.ModifierType.SHIFT_MASK:
            return self._people_pick(self.people_items[0])
        if kv == Gdk.KEY_Escape and self.people_items:
            self.people_items = []
            self.people_box.set_visible(False)
            return True
        if kv in (Gdk.KEY_Tab, Gdk.KEY_ISO_Left_Tab) and self.ai_pref and not state & Gdk.ModifierType.SHIFT_MASK:
            return self._ai_apply()
        if kv in (Gdk.KEY_Return, Gdk.KEY_KP_Enter) and not state & (Gdk.ModifierType.SHIFT_MASK | Gdk.ModifierType.CONTROL_MASK):
            self.send()
            return True
        if clipimg.is_ctrl_v(kv, state) and self.entry.get_sensitive():
            return self.paste()
        if kv == Gdk.KEY_Escape and self.editing is not None:
            self.edit_cancel()
            return True
        if kv == Gdk.KEY_Escape and self.reply is not None:
            self.set_reply(None)
            return True
        if kv == Gdk.KEY_Up and self.editing is None and self.entry.get_buffer().get_char_count() == 0 \
                and not state & (Gdk.ModifierType.SHIFT_MASK | Gdk.ModifierType.CONTROL_MASK | Gdk.ModifierType.ALT_MASK):
            c = chat.chat(self.cid)                       # (2.5) Pfeil hoch im leeren Feld: letzte eigene Nachricht bearbeiten
            lim = x3_limits(chat.me)
            last = next((m for m in reversed((chat.cache.get(self.cid) or {}).get("msgs") or []) if can_edit(c, m, lim=lim)), None)
            if last is not None:
                self.edit_start(last)
                return True
        return False

    def set_reply(self, m):
        if m is not None and self.editing is not None:
            self.edit_cancel()
        self.reply = m
        while (c := self.reply_thumb.get_first_child()) is not None:
            self.reply_thumb.remove(c)
        if m is not None:
            nm = (m.get("sender") or {}).get("name") or ""
            th, lab = reply_media(self.cid, m)
            self.reply_label.set_label(_("Reply to {name}").format(name=nm) + "\n" + (lab or snippet(m)))
            if th is not None:
                self.reply_thumb.append(th)
            self.reply_thumb.set_visible(th is not None)
        self.reply_bar.set_visible(m is not None)
        self.entry.grab_focus()

    def paste(self, clipboard=None):
        """Strg+V: Bild oder Dateien aus der Zwischenablage als Anhang; Text wie gewohnt ins Eingabefeld. -> abgefangen?"""
        cb = clipboard or self.entry.get_clipboard()
        return clipimg.read_paste(cb, self.add_files, self.add_image,
                                  lambda: self.entry.activate_action("clipboard.paste", None))

    def _v1_max(self):
        """v1-Grenze dieses Chats (4.11.): file_max, ueber Servergrenzen hoechstens 10 MiB"""
        return chatfiles.v1_max(chat.limits, is_fed(chat.chat(self.cid)))

    def add_image(self, data, name=None, mime="image/png"):
        """Bilddaten (Zwischenablage) als Anhang - zu gross: verkleinern"""
        fmax = self._v1_max()

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
        fmax = self._v1_max()
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
        """Dateien (Auswahl, Zwischenablage) als Anhaenge; zu grosse Bilder werden verkleinert, andere grosse Dateien gehen
        (4.11.) als saga-file/v2 vom Pfad (nie ganz im Speicher), sonst abgelehnt"""
        lim = chat.limits or {}
        ch = chat.chat(self.cid)
        fed = is_fed(ch)
        fmax = chatfiles.v1_max(lim, fed)                 # v1-Grenze (ueber Servergrenzen hoechstens 10 MiB)
        big = ch.get("big_files") if isinstance(ch, dict) else None

        def work():
            from . import videotools
            out = []
            for g in gfiles:
                info = g.query_info("standard::size,standard::content-type,standard::display-name", Gio.FileQueryInfoFlags.NONE, None)
                name = info.get_display_name() or g.get_basename() or "datei"
                mime = Gio.content_type_get_mime_type(info.get_content_type() or "") or "application/octet-stream"
                if mime.startswith("video/") and g.get_path():
                    # Video (2.4): nur Pfad merken - bis file_max v1, darueber saga-file/v2; zu gross: nur verkleinert
                    size = info.get_size()
                    try:
                        chatvideo.plan(name, size, lim, ch, fed)
                        out.append((name, mime, chatvideo.LocalVideo(g.get_path(), size)))
                    except chatvideo.VideoError as e:
                        if videotools.can_optimize() and size > fmax and (lim.get("file2") or size < 8 * fmax):
                            out.append((name, mime, chatvideo.LocalVideo(g.get_path(), size, optimize=True, forced=True)))
                        else:
                            out.append((name, None, str(e)))
                    continue
                size = info.get_size()
                how, n = chatfiles.send_plan(size, lim, fed, big)
                if how == "empty":                             # 4.11.: leere Datei nie still als Anhang senden
                    out.append((name, None, _("“{name}” is empty (0 bytes) and was not attached.").format(name=name)))
                    continue
                if how != "v1":
                    if (mime.startswith("image/") or clipimg.file_is_image(g)) and (size <= 8 * fmax or how != "v2"):
                        try:                                    # Bild: wie bisher verkleinert als v1
                            data, m = clipimg.shrink(g.load_bytes(None)[0].get_data(), fmax)
                            out.append((clipimg.with_ext(name, m), m, data))
                            continue
                        except Exception:  # noqa: BLE001 - nicht verkleinerbar: wie jede grosse Datei
                            pass
                    if how == "v2" and g.get_path():           # 4.11.: grosse Datei als saga-file/v2 (nur Pfad merken)
                        out.append((name, mime, chatvideo.LocalFile(g.get_path(), size)))
                    elif how == "peer":
                        out.append((name, None, _("The other person's server can't take large files yet (at most {n} MB).").format(n=n)))
                    else:
                        out.append((name, None, _("“{name}” is too large (at most {n} MB).").format(
                            name=name, n=n or max(1, fmax // 1048576))))
                    continue
                data = g.load_bytes(None)[0].get_data()
                if not data:
                    out.append((name, None, _("“{name}” is empty (0 bytes) and was not attached.").format(name=name)))
                    continue
                out.append((name, mime, data))
            return out

        def fin(lst):
            for name, mime, data in lst:
                if mime is None:
                    _toast(self.win, data)
            self._add_ready([x for x in lst if x[1] is not None])
        bg(work, done=fin, error=lambda e: _toast(self.win, str(e)), media=True)

    def _files_bar(self):
        from . import videotools
        self.files_bar.remove_all()
        sending = bool(self._sending)
        for i, (name, mime, data) in enumerate(self.files):
            chip = Gtk.Box(spacing=6)
            chip.add_css_class("hk-tag")
            if isinstance(data, chatvideo.LocalFile):          # Video (2.4)/grosse Datei (4.11.): Pfad, Groesse, Fortschritt
                vid = isinstance(data, chatvideo.LocalVideo)
                label = ("🎬 " if vid else "📎 ") + name + " · " + human_size(len(data))
                chip.append(_lbl(label, wrap=False, ellipsize=Pango.EllipsizeMode.MIDDLE, max_width_chars=28))
                if vid and videotools.can_optimize() and not sending:
                    cb = Gtk.CheckButton(label=_("Optimized 720p"), active=data.optimize, sensitive=not data.forced)
                    cb.set_tooltip_text(_("Make the video smaller before sending (H.264, 1280×720). Otherwise the original is sent."))
                    cb.connect("toggled", lambda b, v=data: setattr(v, "optimize", b.get_active()))
                    chip.append(cb)
                if data.status:
                    st = _lbl(data.status, ("caption", "dim-label"), wrap=False)
                    st.add_css_class("hk-upload-status")
                    chip.append(st)
            elif (mime or "").startswith("image/"):              # Vorschau des Bildes (nur im Speicher)
                pv = images.ImageView(mode="cover", radius=6)
                pv.set_size_request(40, 40)
                pv.set_valign(Gtk.Align.CENTER)
                images.load_local(data, lambda d, pv=pv: d is not None and pv.set_decoded(d), max_px=96)
                chip.append(pv)
                chip.append(_lbl(name + " · " + human_size(len(data)), wrap=False, ellipsize=Pango.EllipsizeMode.MIDDLE, max_width_chars=28))
            else:
                chip.append(_lbl("📎 " + name + " · " + human_size(len(data)), wrap=False, ellipsize=Pango.EllipsizeMode.MIDDLE,
                                 max_width_chars=28))
            if not sending:
                chip.append(flat_button(icon="window-close-symbolic", css="hk-tiny", tooltip=_("Remove"),
                                        cb=lambda i=i: (self.files.pop(i), self._files_bar())))
            self.files_bar.append(chip)
        if sending and any(isinstance(d, chatvideo.LocalFile) for _n, _m, d in self.files):
            self.files_bar.append(_button(_("Cancel upload"), ("destructive-action",), cb=self._cancel_upload))
        self.files_bar.set_visible(bool(self.files))
        self._changed(self.entry.get_buffer())

    def _cancel_upload(self):
        if self._sending:
            self._sending.set()

    def _upload_status(self, i, text):
        """Fortschritt eines Anhangs (Hauptthread)"""
        if 0 <= i < len(self.files) and isinstance(self.files[i][2], chatvideo.LocalFile):
            self.files[i][2].status = text
            ch = self.files_bar.get_child_at_index(i)
            box = ch.get_child() if ch else None
            last = box.get_last_child() if box else None
            if isinstance(last, Gtk.Label) and last.has_css_class("hk-upload-status"):
                last.set_label(text)
            else:
                self._files_bar()
        return False

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
        if self.editing is not None:                       # (2.5) Bearbeitung senden
            self._send_edit(text)
            return
        files = list(self.files)
        if not text and not files:
            return
        raw = text                                         # wie geschrieben (fuer die Frage an die KI)
        notes = []
        for t, nid in sorted(self.mentions.items(), key=lambda x: -len(x[0])):
            if t in text:
                text = text.replace(t, f"[[note:{nid}]]")
                notes.append(nid)
        refs = []
        if K.has(K.MENTION):                               # (2.5) "@Name" -> <@kennung>, Kennungen unverschluesselt dazu
            text, refs = K.apply_people(text, self.people)
        unf = self._unf_for(text) if K.unfurl_enabled() else None
        reply = self.reply["id"] if self.reply else None
        cid = self.cid
        self.send_btn.set_sensitive(False)
        self.entry.set_sensitive(False)
        stop = threading.Event()
        self._sending = stop
        pending = []
        if any(isinstance(d, chatvideo.LocalFile) for _n, _m, d in files):
            self._files_bar()

        def work():
            fobjs, fids = chat.upload_attachments(cid, files, progress=lambda i, t: GLib.idle_add(self._upload_status, i, t),
                                                  cancelled=stop.is_set, pending=pending)
            body = {"v": 1, "type": "text", "text": text}
            if fobjs:
                body["files"] = fobjs
            if notes:
                body["notes"] = notes
            if unf:                                        # (2.5) Linkvorschau verschluesselt in der Nachricht
                pv = K.preview_body(unf)
                if pv:
                    img = unf.get("image") if isinstance(unf.get("image"), dict) else None
                    if img and img.get("b64"):
                        try:
                            pv["img"] = chat.upload_preview_img(cid, img)
                            fids = list(fids) + [pv["img"]["id"]]
                        except Exception:  # noqa: BLE001 - ohne Bild senden
                            pass
                    body["preview"] = pv
            r = chat.send(cid, body, reply_to=reply, file_ids=fids, extra={"mentions": refs} if refs else None)
            for p in pending:                              # gesendet: Fortsetzungsdaten (K, Praefix) vergessen
                chatvideo.pending_drop(p)
            return r

        def done_sending():
            self._sending = None
            for _n, _m, d in self.files:
                if isinstance(d, chatvideo.LocalFile):
                    d.status = ""

        def fin(r):
            done_sending()
            resp, missing = r
            buf.set_text("")
            self.files, self.mentions, self.people = [], {}, {}
            chatdrafts.drop(cid)
            self._unf_reset()
            self._files_bar()
            self.set_reply(None)
            self.entry.set_sensitive(True)
            self.entry.grab_focus()
            self.at_bottom = True
            chat.load_messages(cid)
            chat._list_soon()
            if (resp or {}).get("pending"):              # (2.3) Heim gerade nicht erreichbar: wartet beim eigenen Server
                _toast(self.win, _("The other server isn't reachable right now – the message will be delivered as soon as it's back."))
            elif missing:
                _toast(self.win, _("{names} hasn't set up chats yet and can't read this message.").format(names=", ".join(missing)))
            self._ask_ai(cid, raw, (resp or {}).get("id"))

        def err(e):
            done_sending()
            self._files_bar()
            self.entry.set_sensitive(True)
            self._changed(buf)
            if isinstance(e, chatvideo.Cancelled):
                _toast(self.win, _("Upload cancelled."))
                return
            if isinstance(e, SafetyChanged):
                self._check_trust()
            if isinstance(e, FedNotDelivered):            # (2.3) vom Heim abgewiesen (z. B. gesperrt)
                _toast(self.win, _("Not delivered: {err}").format(err=e))
                return
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
    def open_info(self):
        """Chat-Kopf angeklickt bzw. "Gruppeninfo …" (2.5): Gruppen -> Gruppeninfo, Direkt-Chats wie bisher "Mitglieder" """
        c = chat.chat(self.cid)
        if not c:
            return
        if c.get("kind") != "dm" and server_has("chatadm1"):
            GroupInfoDialog(self.win, self.cid).present(self.win)
        else:
            ChatInfoDialog(self.win, self.cid).present(self.win)

    def menu(self, parent):
        c = chat.chat(self.cid)
        if not c:
            return
        ents = []
        peer = chat.peer(c)
        if c.get("kind") == "dm" and peer:
            if self.screen.call_chat():
                ents.append((_("Call in browser"), self.win.call_current))
            if is_fed(c):
                ents.append((_("Safety number"), lambda: open_safety_v2(self.win, self.cid, peer)))
            else:
                ents.append((_("Safety number"), lambda: open_safety(self.win, self.cid, peer.get("username"))))
        if c.get("kind") != "dm" and server_has("chatadm1"):        # 2.5: Gruppeninfo ersetzt "Mitglieder" bei Gruppen
            ents.append((_("Group info …"), self.open_info))
        else:
            ents.append((_("Members") + " …", lambda: ChatInfoDialog(self.win, self.cid).present(self.win)))
        from .assistant import ai, ai_name
        if ai.chat_allowed and aichat.may_toggle(c):     # KI im Chat (2.1): erlauben / nicht mehr erlauben
            ents.append(((_("Stop allowing {name} here") if c.get("ai") else _("Allow {name} in this chat")).format(
                name=ai_name()), self._ai_toggle))
        if c.get("kind") == "page" and c.get("note_id"):
            ents.append((_("Open page"), lambda: self.win.show_page(int(c["note_id"]))))
        ents.append((_("Search in this chat"), self.toggle_search))
        if K.has(K.MEDIA):                               # (2.5, chatmedia1)
            ents.append((_("Media, files, links"), lambda: SV.MediaDialog(self, self.cid).present(self.win)))
        ents.append(None)
        ents += SV.mute_entries(self.win, c)               # (2.5, mute2) Dauer und Mitteilungen; sonst wie bisher
        ents += SV.list_entries(self.win, c, self.screen)  # (2.5) Anheften, ungelesen, ausblenden
        pt = K.dm_peer_target(c) if K.has(K.BLOCK2) and not is_remote(c) else None
        if pt:                                             # (2.5, block2) Direkt-Chat: sperren / entsperren
            if pt[2]:
                ents.append((_("Unblock"), lambda: SV.unblock_person(self.win, pt[0])))
            else:
                ents.append((_("Block {name}").format(name=pt[1]), lambda: SV.block_person(self.win, self.win, pt[0], pt[1]), True))
        if K.has(K.REPORT) and c.get("kind") == "dm":     # (2.5, report1) die letzten fremden Nachrichten
            last = [m for m in (chat.cache.get(self.cid) or {}).get("msgs") or []
                    if not m.get("mine") and m.get("state") in ("ok", "unverified")][-5:]
            if last:
                ents.append((_("Report …"), lambda: SV.ReportDialog(self.win, c, last).present(self.win)))
        cur = int(c.get("retention_s") or 0)
        sub = [(("✓ " if s == cur else "") + retention_label(s),
                lambda s=s: chat.patch(self.cid, {"retention_s": s}, error=lambda e: _toast(self.win, str(e))))
               for s in (chat.limits.get("retentions") or RETENTIONS)]
        ents.append((_("Delete messages automatically after"), sub))
        if c.get("kind") == "free":
            ents.append(None)
            if gi_rights(c)["rename"]:                     # 2.5: can_rename (schreibgeschuetzt: nein)
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

    def __init__(self, cid, f, wh=None, poster=True):
        super().__init__()
        self.cid, self.f = cid, f
        self.tex = None
        self.state = "idle" if poster else "none"          # idle | wait | ok | fail | none (Video ohne Poster: nichts laden)
        self.bw, self.bh = chatmedia.box_size(wh or chatmedia.size_of(f) or ((1280, 720) if not poster or chatvideo.is_video(f) else None))
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
        elif self.state in ("fail", "none"):
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
        if chatmedia.size_of(self.f) is None and not chatvideo.is_video(self.f):   # alte Nachricht ohne w/h: Groesse jetzt bekannt
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
            if self.state in ("fail", "none"):
                c = Gdk.RGBA()
                c.parse("rgba(127,127,127,0.5)")
                lay = self.create_pango_layout("🎬" if chatvideo.is_video(self.f) else "🖼")
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


def chat_images(conv):
    """(2.5) alle Bilder der geladenen Nachrichten der Unterhaltung, aelteste zuerst (Blaettern in der Bildansicht) -> [f]"""
    c = chat.chat(conv.cid) or {}
    out = []
    for m in (chat.cache.get(conv.cid) or {}).get("msgs") or []:
        if m.get("state") not in ("ok", "unverified"):
            continue
        if K.has(K.BLOCK2) and m.get("id") not in getattr(conv, "unblocked", ()) and K.grp_blocked(c, m):
            continue
        for f in m.get("files") or []:
            if isinstance(f, dict) and f.get("id") and chatmedia.is_image(f) and chatvideo.fmt(f) == 1:
                out.append(f)
    return out


class ImageViewer(Adw.Dialog):
    """Original eines Chat-Bildes (erst beim Oeffnen geladen, Entschluesseln und Dekodieren im Hintergrund).
    (2.5) Zoomen (Strg+Mausrad, Geste, +/-/0, Knoepfe, Doppelklick Einpassen/100 %), Verschieben, Blaettern durch alle
    Bilder des Chats (Pfeiltasten, Knoepfe, "n / m"), Speichern unter und Bild kopieren (Strg+S, Strg+C)."""
    def __init__(self, conv, f, items=None):
        super().__init__(title=f.get("n") or _("Image"), content_width=960, content_height=720)
        self.conv, self.f = conv, f
        self.items = list(items) if items is not None else chat_images(conv)
        self.idx = next((i for i, x in enumerate(self.items) if x.get("id") == f.get("id")), None)
        if self.idx is None:
            self.items, self.idx = [f], 0
        self.data, self.gen = None, 0
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        self.wtitle = Adw.WindowTitle(title=self.get_title())
        hb.set_title_widget(self.wtitle)
        self.save_btn = Gtk.Button(icon_name="document-save-symbolic", tooltip_text=_("Save as …"))
        self.save_btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Save as …")])
        self.save_btn.connect("clicked", lambda *_: self.save())
        hb.pack_start(self.save_btn)
        self.copy_btn = Gtk.Button(icon_name="edit-copy-symbolic", tooltip_text=_("Copy image"), sensitive=False)
        self.copy_btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Copy image")])
        self.copy_btn.connect("clicked", lambda *_: self.copy())
        hb.pack_start(self.copy_btn)
        self.zin = Gtk.Button(icon_name="zoom-in-symbolic", tooltip_text=_("Zoom in"))
        self.zin.update_property([Gtk.AccessibleProperty.LABEL], [_("Zoom in")])
        self.zin.connect("clicked", lambda *_: self.view.zoom_in())
        self.zpct = Gtk.Button(label="100 %", tooltip_text=_("Fit to view"))
        self.zpct.add_css_class("flat")
        self.zpct.connect("clicked", lambda *_: self.view.toggle())
        self.zout = Gtk.Button(icon_name="zoom-out-symbolic", tooltip_text=_("Zoom out"))
        self.zout.update_property([Gtk.AccessibleProperty.LABEL], [_("Zoom out")])
        self.zout.connect("clicked", lambda *_: self.view.zoom_out())
        zb = Gtk.Box(spacing=2)
        for w in (self.zout, self.zpct, self.zin):
            w.add_css_class("flat")
            zb.append(w)
        hb.pack_end(zb)
        tv.add_top_bar(hb)
        self.stack = Gtk.Stack(vexpand=True, hexpand=True)
        sp = Adw.Spinner() if hasattr(Adw, "Spinner") else Gtk.Spinner(spinning=True)
        sp.set_size_request(32, 32)
        sp.set_halign(Gtk.Align.CENTER)
        sp.set_valign(Gtk.Align.CENTER)
        self.stack.add_named(sp, "wait")
        self.view = images.ZoomView()
        self.view.on_change = self._zoom_changed
        self.stack.add_named(self.view, "img")
        self.err = _lbl("", ("dim-label",), xalign=0.5, valign=Gtk.Align.CENTER)
        self.stack.add_named(self.err, "err")
        ov = Gtk.Overlay(child=self.stack)
        self.prev_btn = self._nav("go-previous-symbolic", _("Previous image"), Gtk.Align.START, -1)
        self.next_btn = self._nav("go-next-symbolic", _("Next image"), Gtk.Align.END, 1)
        ov.add_overlay(self.prev_btn)
        ov.add_overlay(self.next_btn)
        tv.set_content(ov)
        self.toasts = Adw.ToastOverlay(child=tv)
        self.set_child(self.toasts)
        kc = Gtk.EventControllerKey()
        kc.set_propagation_phase(Gtk.PropagationPhase.CAPTURE)
        kc.connect("key-pressed", self._key)
        self.add_controller(kc)
        self.show(self.idx)

    def _nav(self, icon, tip, align, step):
        b = Gtk.Button(icon_name=icon, tooltip_text=tip, halign=align, valign=Gtk.Align.CENTER)
        b.update_property([Gtk.AccessibleProperty.LABEL], [tip])
        for c in ("circular", "osd", "hk-viewer-nav"):
            b.add_css_class(c)
        b.connect("clicked", lambda *_: self.step(step))
        return b

    def add_toast(self, t):
        self.toasts.add_toast(t)

    def toast(self, text):
        self.toasts.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(text)), timeout=3))

    def counter(self):
        return f"{self.idx + 1} / {len(self.items)}"

    def show(self, i):
        """Bild i der Liste laden und zeigen (spaete Ergebnisse eines vorigen Bildes werden verworfen)"""
        self.idx = max(0, min(len(self.items) - 1, i))
        self.f = f = self.items[self.idx]
        self.gen += 1
        self.data = None
        name = f.get("n") or _("Image")
        self.set_title(name)
        self.wtitle.set_title(name)
        self.wtitle.set_subtitle(self.counter() if len(self.items) > 1 else "")
        many = len(self.items) > 1
        self.prev_btn.set_visible(many)
        self.next_btn.set_visible(many)
        self.prev_btn.set_sensitive(self.idx > 0)
        self.next_btn.set_sensitive(self.idx < len(self.items) - 1)
        self.copy_btn.set_sensitive(False)
        self.stack.set_visible_child_name("wait")
        g = self.gen
        bg(chatmedia.original, self.conv.cid, f, done=lambda d: self._data(d, g), error=lambda e: self._fail(e, g), media=True)

    def step(self, d):
        j = self.idx + d
        if 0 <= j < len(self.items) and j != self.idx:
            self.show(j)
            return True
        return False

    def _key(self, _c, kv, _code, state):
        ctrl = state & Gdk.ModifierType.CONTROL_MASK
        if ctrl and kv in (Gdk.KEY_c, Gdk.KEY_C):
            self.copy()
            return True
        if ctrl and kv in (Gdk.KEY_s, Gdk.KEY_S):
            self.save()
            return True
        if state & (Gdk.ModifierType.CONTROL_MASK | Gdk.ModifierType.ALT_MASK):
            return False
        if kv in (Gdk.KEY_Left, Gdk.KEY_KP_Left, Gdk.KEY_Page_Up):
            self.step(-1)
            return True
        if kv in (Gdk.KEY_Right, Gdk.KEY_KP_Right, Gdk.KEY_Page_Down):
            self.step(1)
            return True
        if kv in (Gdk.KEY_plus, Gdk.KEY_KP_Add, Gdk.KEY_equal):
            self.view.zoom_in()
            return True
        if kv in (Gdk.KEY_minus, Gdk.KEY_KP_Subtract):
            self.view.zoom_out()
            return True
        if kv in (Gdk.KEY_0, Gdk.KEY_KP_0):
            self.view.reset_fit()
            return True
        if kv in (Gdk.KEY_1, Gdk.KEY_KP_1):
            self.view.zoom_to(1.0)
            return True
        return False

    def _zoom_changed(self):
        v = self.view
        ok = v.dec is not None
        s = v.cur_scale()
        self.zpct.set_label(f"{round(s * 100)} %")
        self.zpct.set_tooltip_text(_("Original size") if v.fit else _("Fit to view"))
        self.zin.set_sensitive(ok and s < v.MAX - 1e-6)
        self.zout.set_sensitive(ok and not v.fit)
        self.zpct.set_sensitive(ok)

    def _data(self, data, g):
        if g != self.gen:
            return
        self.data = data
        images.load_local(data, lambda dec: self._dec(dec, g), max_px=4096, animated=True)

    def _dec(self, dec, g=None):
        if g is not None and g != self.gen:
            return
        if dec is None:
            self._fail(None, g)
            return
        self.view.set_decoded(dec)
        self.stack.set_visible_child_name("img")
        self.copy_btn.set_sensitive(True)
        self._zoom_changed()
        GLib.idle_add(lambda: (self.view.grab_focus(), False)[1])

    def _fail(self, e, g=None):
        if g is not None and g != self.gen:
            return
        self.err.set_label(_("Attachment not loaded: {err}").format(err=e or _("not loaded")))
        self.stack.set_visible_child_name("err")

    def save(self):
        self.conv._save_file(self.f)

    def copy(self):
        """Bild in die Zwischenablage (Gdk.Texture, volle Groesse aus dem Original)"""
        if self.data is None:
            return
        copy_image_data(self, self.data, self.toast)


def copy_image_data(widget, data, toast):
    """Hintergrund dekodieren, dann als Gdk.Texture in die Zwischenablage; toast(Text)"""
    def fin(tex):
        # (set_texture fehlt in PyGObject) - GDK bietet die Textur anderen Programmen auch als image/png an
        widget.get_clipboard().set_content(Gdk.ContentProvider.new_for_value(GObject.Value(Gdk.Texture, tex)))
        toast(_("Image copied."))
    bg(images.texture_from_bytes, data, done=fin, error=lambda e: toast(_("Image not copied: {err}").format(err=e)), media=True)


class VideoViewer(Adw.Dialog):
    """Video abspielen (2.4): erst beim Oeffnen; ein Player (GStreamer) ueber den lokalen Proxy (Range, blockweise
    entschluesselt, Spulen). v1 (bis file_max) wird ganz geladen. Ohne GStreamer/Codec: Hinweis "Herunterladen und extern
    abspielen" mit Speichern-Knopf."""
    def __init__(self, conv, f):
        super().__init__(title=f.get("n") or _("Video"), content_width=960, content_height=640)
        self.conv, self.f = conv, f
        self.stream, self.tok = None, None
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        self.save_btn = Gtk.Button(icon_name="document-save-symbolic", tooltip_text=_("Save attachment"))
        self.save_btn.connect("clicked", lambda *_: self._save())
        hb.pack_start(self.save_btn)
        self.prog = _lbl("", ("caption", "dim-label"), wrap=False)
        hb.pack_start(self.prog)
        tv.add_top_bar(hb)
        self.stack = Gtk.Stack()
        sp = Adw.Spinner() if hasattr(Adw, "Spinner") else Gtk.Spinner(spinning=True)
        sp.set_size_request(32, 32)
        sp.set_halign(Gtk.Align.CENTER)
        sp.set_valign(Gtk.Align.CENTER)
        self.stack.add_named(sp, "wait")
        self.video = Gtk.Video(autoplay=True, vexpand=True, hexpand=True)
        self.stack.add_named(self.video, "play")
        self.err = Adw.StatusPage(icon_name="video-x-generic-symbolic")
        b = Gtk.Button(label=_("Save …"), halign=Gtk.Align.CENTER)
        b.add_css_class("pill")
        b.add_css_class("suggested-action")
        b.connect("clicked", lambda *_: self._save())
        self.err.set_child(b)
        self.stack.add_named(self.err, "err")
        tv.set_content(self.stack)
        self.set_child(tv)
        self.connect("closed", lambda *_: self._stop())
        self._start()

    def _start(self):
        from . import videotools
        if not videotools.can_play():
            self._fail(_("Videos can't be played here (GStreamer is missing)."), hint=True)
            return
        if chatvideo.fmt(self.f) == 2:
            try:
                self._play(chatvideo.VideoSource(self.conv.cid, self.f))
            except (C.CryptoError, ValueError) as e:
                self._fail(_("Attachment not loaded: {err}").format(err=e))
            return
        mime = self.f.get("m") or "video/mp4"
        bg(chatmedia.original, self.conv.cid, self.f, done=lambda d: self._play(chatvideo.MemorySource(d, mime)),
           error=lambda e: self._fail(_("Attachment not loaded: {err}").format(err=e)), media=True)

    def _play(self, src):
        if self.stream is False:                           # inzwischen geschlossen
            return
        from .videoplayer import SagaMediaStream
        url, self.tok = chatvideo.proxy.register(src)
        try:
            self.stream = SagaMediaStream(url, on_error=self._play_error)
        except (GLib.Error, TypeError, AttributeError) as e:
            self._fail(str(e), hint=True)
            return
        self.video.set_media_stream(self.stream)
        self.stack.set_visible_child_name("play")

    def _play_error(self, text, codec):
        """Player-Fehler (Codec fehlt, unbekanntes Format ...): Hinweis "Herunterladen und extern abspielen" """
        self._fail(_("This video can't be played here."), hint=True, detail=None if codec else text)

    def _fail(self, text, hint=False, detail=None):
        self._stop(keep=True)
        self.err.set_title(text)
        desc = _("Download it and play it in another app.") if hint else ""
        if detail:
            desc += "\n<small>" + GLib.markup_escape_text(detail) + "</small>"
        self.err.set_description(desc)
        self.stack.set_visible_child_name("err")

    def _save(self):
        self.save_btn.set_sensitive(False)
        self.conv._save_file(self.f, progress=lambda x: self.prog.set_label(f"{int(x * 100)} %"),
                             done=lambda ok: (self.save_btn.set_sensitive(True), self.prog.set_label("")))

    def _stop(self, keep=False):
        st, self.stream = self.stream, (None if keep else False)
        if st:
            self.video.set_media_stream(None)
            st.close()
        if self.tok:
            chatvideo.proxy.unregister(self.tok)
            self.tok = None


def open_safety(win, cid, uid):
    """Sicherheitsnummer mit einem Mitglied (Schluessel aus den Empfaengern dieses Chats)"""
    c = chat.chat(cid)
    if is_fed(c):                                        # 2.3: Sicherheitsnummer v2 (Handles)
        m = next((x for x in c.get("members") or [] if isinstance(x, dict) and uid in (x.get("username"), x.get("handle"))), None)
        if m is not None:
            open_safety_v2(win, cid, m)
        return

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


def open_safety_v2(win, cid, m):
    """2.3: Sicherheitsnummer v2 mit einer Person (Schluessel frisch ueber den eigenen Server, selbst geprueft)"""
    h = (m or {}).get("handle")
    if not h:
        return
    bg(chat.fed_keys, [h], done=lambda fk: SafetyDialogV2(win, cid, m, fk.get(h) or {}).present(win), error=lambda e: _toast(win, str(e)))


def _safety_number_box(box, name, num):
    blocks = (num or "").split()
    lines = [" ".join(blocks[i:i + 4]) for i in range(0, len(blocks), 4)]
    nl = _lbl("\n".join(lines), ("hk-safety", "monospace"), xalign=0.5, wrap=False, selectable=True, justify=Gtk.Justification.CENTER)
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


class SafetyDialogV2(Adw.Dialog):
    """Sicherheitsnummer v2 (Chat ueber Servergrenzen): beide (Handle, Identitaetsschluessel); "Als ueberprueft markieren"
    hebt auch eine Warnung oder Blockade auf (die Person hat verglichen)"""

    def __init__(self, win, cid, m, f):
        name = m.get("name") or "?"
        super().__init__(title=_("Safety number"), content_width=440)
        self.win, self.cid, self.m, self.f = win, cid, m, f
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin_top=12, margin_bottom=18, margin_start=18, margin_end=18)
        box.append(_lbl(_("Safety number with {name}").format(name=name), ("title-3",), xalign=0.5, justify=Gtk.Justification.CENTER))
        c = chat.chat(cid) or {}
        my = c.get("my_handle") or chat.my_handle
        keys = f.get("keys")
        if not keys or not chat.keys or not my:
            box.append(_lbl(_("{name} hasn't set up chats yet.").format(name=name), ("dim-label",), xalign=0.5))
        else:
            _safety_number_box(box, name, C.safety_number_v2(my, chat.keys["ident_pk"], m["handle"], keys["ident_pk"]))
            if is_remote(c):
                box.append(_lbl(_("{name} is on {host} – the number belongs to this chat across servers.").format(
                    name=name, host=m.get("host") or C.handle_host(m["handle"])), ("caption", "dim-label"), xalign=0.5,
                    justify=Gtk.Justification.CENTER))
            if (f.get("stored") or {}).get("migrated"):  # (2.5) Vertrauen aus v1 uebernommen (gleicher Schluessel)
                ml = _lbl(_("New presentation, same key: the number looks different than before, but belongs to the same key you "
                            "already knew."), ("caption", "hk-migrated"), xalign=0.5, justify=Gtk.Justification.CENTER)
                box.append(ml)
            done = chat.fed_verified(m["handle"])
            self.vb = _button(("✓ " + _("verified")) if done else _("Mark as verified"), ("pill",) if done else ("pill", "suggested-action"),
                              self._verify, halign=Gtk.Align.CENTER)
            self.vb.set_sensitive(not done)
            box.append(self.vb)
        tv.set_content(box)
        self.set_child(tv)

    def _verify(self):
        chat.fed_verify(self.m["handle"], self.f["keys"]["ident_pub"], True)
        self.vb.set_label("✓ " + _("verified"))
        self.vb.remove_css_class("suggested-action")
        self.vb.set_sensitive(False)
        _toast(self.win, _("Marked as verified."))
        chat.emit("chat-changed", self.cid)


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
        c = chat.chat(cid) or {}
        me = next((m.get("username") for m in c.get("members") or [] if isinstance(m, dict) and m.get("username") and
                   (m.get("me") or (m.get("handle") and m.get("handle") == c.get("my_handle")))), None) if is_fed(c) else None
        who = me or chat.uid                            # 2.3: members[].username von mir (Gruppe ueber Servergrenzen)
        bg(client.request, "DELETE", f"api/chats/{cid}/members/{GLib.Uri.escape_string(who, None, True)}", done=fin,
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
        if is_fed(chat.chat(cid)):                       # 2.3: Schluessel ueber Servergrenzen (selbst geprueft)
            bg(chat.fed_load, chat.chat(cid), done=lambda _r: self.fill(), error=lambda e: None)
        else:
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
        home = chat_home_hint(c)                          # 2.3: wo der Chat liegt (Heim)
        desc = (desc + "\n" + home).strip() if home else desc
        self.group = Adw.PreferencesGroup(title=GLib.markup_escape_text(chat.title(c)), description=GLib.markup_escape_text(desc))
        admin = c.get("role") == "admin" and kind == "free"
        me = chat.uid
        fed = is_fed(c)
        for m in c.get("members") or []:
            u = m.get("username")
            if fed:                                       # 2.3: Herkunft, Vertrauen v2, Sicherheitsnummer v2
                self.group.add(self._fed_row(c, m, admin))
                continue
            r = self.rc.get(u) or {}
            sub = []
            if m.get("me") or u == me:
                sub.append(pgettext("subject", "you"))    # Mitgliederliste: "du" (nicht "dir", N10)
            elif person_mark(m):
                sub.append(person_mark(m))                # N2: Gleichnamige unterscheiden (Handle bzw. oeffentlicher Name)
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
            from .sharing import search_min
            en = Adw.EntryRow(title=_("Add person") if search_min() == 1 else _("Add person (3+ characters)"))
            self.add_group.add(en)
            self.hits = []

            def search(*_):
                q = en.get_text().strip()
                for h in self.hits:
                    self.add_group.remove(h)
                self.hits = []
                if len(q) < search_min():                  # Server ab 2.3: 1-2 Zeichen nur genaue Treffer
                    return
                bg(client.get, "api/chat/people?q=" + GLib.Uri.escape_string(q, None, True), done=lambda j: show(q, j), error=lambda e: None)
            search_soon = Debounce(250, search)

            def show(q, j):
                if en.get_text().strip() != q:
                    return
                from .sharing import is_member
                have_h = {m.get("handle") for m in c.get("members") or [] if m.get("handle")}
                if fed_hint_show(q, len((j or {}).get("users") or [])):
                    h = fed_hint()
                    self.add_group.add(h)
                    self.hits.append(h)
                for p in ((j or {}).get("users") or [])[:8]:
                    if is_member(p.get("username"), c.get("members")):   # 2.4 (W4): Handle <-> members[].ref
                        continue
                    sub = [x for x in (person_mark(p), "" if p.get("ready") else _("hasn't set up chats yet")) if x]  # N2
                    r = Adw.ActionRow(title=GLib.markup_escape_text(p.get("name") or p["username"]),
                                      subtitle=GLib.markup_escape_text(" · ".join(sub)), activatable=True)
                    r.add_prefix(avatars.person(p, 32, text=p.get("name") or p["username"]))
                    r.add_suffix(Gtk.Image.new_from_icon_name("list-add-symbolic"))
                    r.connect("activated", lambda *_, u=p["username"]: self._add(u))
                    self.add_group.add(r)
                    self.hits.append(r)
                rem = [x for x in (j or {}).get("remote") or [] if isinstance(x, dict) and x.get("handle") and x["handle"] not in have_h]
                for p in rem[:5]:                          # 2.3: Adressbuch anderer Server - per Handle (ueber das Heim)
                    r = Adw.ActionRow(title=GLib.markup_escape_text(remote_label(p)),
                                      subtitle=GLib.markup_escape_text(_("Person on {host}").format(host=p.get("host") or "?")), activatable=True)
                    r.add_prefix(avatars.person(p, 32, text=p.get("name") or "?"))
                    r.add_suffix(Gtk.Image.new_from_icon_name("list-add-symbolic"))
                    r.add_css_class("hk-remote-hit")
                    r.connect("activated", lambda *_, h=p["handle"]: self._add(None, handle=h))
                    self.add_group.add(r)
                    self.hits.append(r)
            en.connect("changed", lambda *_: search_soon())
            self.page.add(self.add_group)

    def _fed_row(self, c, m, admin=False):
        h = m.get("handle")
        mine = m.get("me") or (h and h == c.get("my_handle"))
        sub = [pgettext("subject", "you")] if mine else []          # "du" (nicht "dir", N10)
        if m.get("host") and not mine:
            if same_host(m["host"]):                                # Person auf meinem Server (N10): nicht "auf localhost:8197"
                sub.append(_("Person on this server"))
            else:
                sub.append(_("on {host}").format(host=m["host"]))
        if not mine and person_mark(m) and not str(person_mark(m)).startswith("@"):
            sub.append(person_mark(m))                              # N2: Gleichnamige unterscheiden
        f = chat.fk.get(h) or {}
        if not mine and chat.fed_verified(h):
            sub.append("✓ " + _("verified"))
        elif not mine and f.get("trust") in ("block", "reject"):
            sub.append("⛔ " + _("blocked"))
        elif not mine and f.get("trust") == "missing":
            sub.append(_("hasn't set up chats yet"))
        row = Adw.ActionRow(title=GLib.markup_escape_text(m.get("name") or "?"), subtitle=GLib.markup_escape_text(" · ".join(sub)))
        row.add_prefix(avatars.person(m, 32, text=m.get("name") or "?"))
        if not mine and h and f.get("keys"):
            row.add_suffix(_button(_("Safety number"), ("flat",), lambda m=m: open_safety_v2(self.win, self.cid, m), valign=Gtk.Align.CENTER))
        if admin and not mine and m.get("username"):     # Gruppe ueber Servergrenzen (2.3): Admins entfernen (ueber das Heim)
            rm = Gtk.Button(icon_name="user-trash-symbolic", tooltip_text=_("Remove"), valign=Gtk.Align.CENTER)
            rm.add_css_class("flat")
            rm.connect("clicked", lambda *_, u=m["username"]: self._remove(u))
            row.add_suffix(rm)
        return row

    def _add(self, u, handle=None, role=None):
        """Person hinzufuegen: Konto hier ({user}) bzw. Person eines anderen Servers ({handle}, 2.3, laeuft ueber das Heim).
        Gruppe ueber Servergrenzen: danach den Namen neu senden (die neue Person kann den alten nicht lesen).
        Seiten-Chat (2.5): role = Rolle der neuen Freigabe (view/comment/edit)."""
        def fin(j):
            ch = (j or {}).get("chat")
            if isinstance(ch, dict):
                chat._merge(ch)
            if is_fed(chat.chat(self.cid) or ch):
                chat.load_chat(self.cid, done=lambda ch2: (self.fill(), bg(chat.fed_load, ch2 or chat.chat(self.cid), done=lambda _r: self.fill(),
                                                                          error=lambda e: None), self._resend_title()))
            else:
                bg(chat.recipients, self.cid, True, done=self._rc, error=lambda e: None)
        body = {"handle": handle} if handle else {"user": u}
        if role:
            body["role"] = role
        bg(client.json, "POST", f"api/chats/{self.cid}/members", body, done=fin, error=lambda e: self.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e)))))

    def _resend_title(self):
        c = chat.chat(self.cid) or {}
        t = c.get("_title")
        if c.get("kind") == "free" and t:
            bg(chat.send, self.cid, {"v": 1, "type": "title", "title": t}, None, "title", done=lambda _r: None, error=lambda e: None)

    def _remove(self, u):
        def fin(_r):
            chat.load_chat(self.cid, done=lambda ch: self.fill())     # Gruppe ueber Servergrenzen: neue Liste vom Heim
        bg(client.request, "DELETE", f"api/chats/{self.cid}/members/{GLib.Uri.escape_string(u, None, True)}", done=fin,
           error=lambda e: self.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e)))))


# --- Kontakte (2.5, contacts1) -----------------------------------------------------------------------------------
def _more_button(tooltip, entries_fn):
    """flacher Knopf "⋯" mit Menue (entries_fn() -> Eintraege fuer menu_popover)"""
    b = Gtk.Button(icon_name="view-more-symbolic", tooltip_text=tooltip, valign=Gtk.Align.CENTER)
    b.add_css_class("flat")
    b.update_property([Gtk.AccessibleProperty.LABEL], [tooltip])
    b.connect("clicked", lambda bb: menu_popover(bb, entries_fn()).popup())
    return b


def contact_menu(row, ref, name, toaster, removed=None):
    """Menue "Aus Kontakten entfernen" an einer Zeile: Knopf "⋯" und Rechtsklick (Kontextmenue)"""
    def drop():
        def fin():
            toaster(_("{name} removed from contacts.").format(name=name))
            if row.get_parent() is not None and isinstance(row.get_parent(), Gtk.ListBox):
                row.get_parent().remove(row)
            if removed:
                removed(ref)
        chat.drop_contact(ref, done=fin, error=lambda e: toaster(str(e)))
    ents = lambda: [(_("Remove from contacts"), drop, True)]      # noqa: E731
    row.add_suffix(_more_button(_("Options for {name}").format(name=name), ents))
    gc = Gtk.GestureClick(button=Gdk.BUTTON_SECONDARY)
    gc.connect("pressed", lambda g, *_: (g.set_state(Gtk.EventSequenceState.CLAIMED), menu_popover(row, ents()).popup()))
    row.add_controller(gc)
    row.hk_contact_drop = drop                             # Tests: wie "Aus Kontakten entfernen"
    return row


def contact_row(k, pick, toaster, removed=None):
    """Zeile eines Kontakts (GET /api/chat/contacts): Avatar, Name, Unterzeile; gone ausgegraut und nicht waehlbar"""
    it = contact_item(k)
    r = Adw.ActionRow(title=GLib.markup_escape_text(it["name"]), subtitle=GLib.markup_escape_text(it["sub"]), activatable=not it["gone"])
    r.add_prefix(avatars.person(it, 32, text=it["name"]))
    r.add_css_class("hk-contact")
    if it["remote"]:
        r.add_css_class("hk-remote-hit")
    if it["gone"]:
        r.add_css_class("hk-gone")
        r.set_tooltip_text(_("Currently unreachable"))
    else:
        r.connect("activated", lambda *_: pick(it))
    r.hk_contact = it
    return contact_menu(r, it["ref"], it["name"], toaster, removed)


class ContactList:
    """Ueberschrift "Kontakte" + Zeilen in einer Gtk.ListBox (ohne Eingabe in "Neue Unterhaltung" bzw. "Mitglied hinzufuegen")"""

    def __init__(self, head, lb, toaster):
        self.head, self.lb, self.toaster = head, lb, toaster
        self.gen = 0

    def show(self, pick, keep=None, still=lambda: True):
        self.gen += 1
        gen = self.gen

        def fin(ks):
            if gen != self.gen or not still():
                return
            ks = [k for k in ks if keep is None or keep(k)]
            self.lb.remove_all()
            self.head.set_visible(bool(ks))
            for k in ks:
                self.lb.append(contact_row(k, pick, self.toaster, removed=lambda _r: self.head.set_visible(self.lb.get_first_child() is not None)))
        chat.load_contacts(fin)

    def hide(self):
        self.gen += 1
        self.head.set_visible(False)


# --- Gruppeninfo (2.5, chatadm1) ---------------------------------------------------------------------------------
class GroupInfoDialog(ChatInfoDialog):
    """Gruppeninfo (Klick auf den Chat-Kopf bzw. "Gruppeninfo …"): Name (bearbeitbar bei can_rename), Mitglieder mit Avatar,
    Name, Handle, "Admin"/"Du"; bei can_manage Hinzufuegen (Kontakte/Personensuche) und je Mitglied Menue (Admin
    vergeben/entziehen bei can_admin, Aus Gruppe entfernen mit Rueckfrage, Sicherheitsnummer); unten Stummschalten,
    Archivieren, Gruppe verlassen. group/page: nur lesend mit Hinweis. Live: chat.changed zeichnet neu."""
    __gtype_name__ = "HKGroupInfoDialog"
    head_group = members_group = foot_group = add_box = name_row = None
    std_groups = ()

    def __init__(self, win, cid):
        super().__init__(win, cid)
        self.set_title(_("Group info"))
        self.set_content_height(640)
        self.connect("map", lambda *_: GLib.idle_add(lambda: (self.set_focus(None), False)[1]))   # Name nicht markiert
        watch_chat(self, "chat-changed", lambda c: c == self.cid and self._live())
        watch_chat(self, "contacts", lambda: self._contacts_live())
        watch_chat(self, "changed", lambda: chat.chat(self.cid) is None and self.close())     # verlassen bzw. geloescht

    def _toast(self, text):
        if text:
            self.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(text))))

    def _live(self):
        if chat.chat(self.cid) is None:
            self.close()
            return
        self.fill()

    def _contacts_live(self):
        if self.add_box is not None and self.add_box.get_visible() and not self.add_en.get_text().strip():
            self._add_contacts()

    def fill(self):
        for g in (self.head_group, self.members_group, self.add_group, self.foot_group):
            if g is not None and g.get_parent() is not None:
                self.page.remove(g)
        for g in self.std_groups:
            if g.get_parent() is not None:
                self.page.remove(g)
        self.std_groups = []
        c = chat.chat(self.cid) or {}
        kind = c.get("kind")
        rights = gi_rights(c)
        std = K.rights(c)                                  # (2.5 Chat-Standard) nur mit den Features des Servers
        can_add = K.can_add_members(c)
        n = int(c.get("member_count") or len(c.get("members") or []))
        # Kopf: Avatar (Gruppenbild), Name, Art
        self.head_group = Adw.PreferencesGroup()
        hb = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, halign=Gtk.Align.CENTER, margin_bottom=6)
        hb.append(SV.head_avatar(self, c, std))
        self.title_lbl = _lbl(chat.title(c), ("title-2",), xalign=0.5, justify=Gtk.Justification.CENTER)
        hb.append(self.title_lbl)
        kl = chat.kind_label(c) + (" „" + str(c.get("group")) + "“" if kind == "group" and c.get("group") else "")
        hb.append(_lbl(kl + " · " + ngettext("{n} member", "{n} members", n).format(n=n), ("dim-label",), xalign=0.5,
                       justify=Gtk.Justification.CENTER))
        self.head_group.add(hb)
        if kind == "free" and rights["rename"]:
            self.name_row = Adw.EntryRow(title=_("Group name"), show_apply_button=True, text=c.get("_title") or "")
            self.name_row.connect("apply", lambda *_: self._rename())
            lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
            lb.add_css_class("boxed-list")
            lb.append(self.name_row)
            self.head_group.add(lb)
            self.head_group.add(_lbl(_("The name is encrypted like a message."), ("dim-label", "caption"), margin_top=6))
        else:
            self.name_row = None
        notes = []
        if kind in ("group", "page") and server_has(K.PAGE_MEMBERS):
            notes.append(K.members_hint(c))
        elif kind in ("group", "page"):
            notes.append(_("Here, members come from the group or from the page's shares."))
        home = chat_home_hint(c)
        if home:
            notes.append(home)
        if c.get("readonly"):
            notes.append("🔒 " + (c.get("readonly_reason") or _("read-only")))
        for t in notes:
            nl = _lbl(t, ("dim-label", "caption", "hk-gi-note"), margin_top=6)
            self.head_group.add(nl)
        self.page.add(self.head_group)
        if kind == "free":                                 # (2.5, groupinfo2) Beschreibung, Gruppenrechte
            for g in (SV.desc_group(self, c, std), SV.perms_group(self, c, std)):
                if g is not None:
                    self.page.add(g)
                    self.std_groups.append(g)
            if not std["edit_info"] and c.get("perm_info") == "admins" and server_has(K.GROUPINFO2):
                self.head_group.add(_lbl(_("Only admins change name, picture and description."), ("dim-label", "caption", "hk-gi-note"),
                                         margin_top=6))
        # Mitglieder
        self.members_group = Adw.PreferencesGroup(title=GLib.markup_escape_text(ngettext("{n} member", "{n} members", n).format(n=n)))
        if can_add:
            add = _button(None, ("flat",), self._toggle_add, valign=Gtk.Align.CENTER,
                          child=Adw.ButtonContent(icon_name="list-add-symbolic", label=_("Add member")))
            self.add_btn = add
            self.members_group.set_header_suffix(add)
        fed = is_fed(c)
        for m in gi_members(c):
            self.members_group.add(self._member_row(c, m, fed))
        self.page.add(self.members_group)
        # Mitglied hinzufuegen (bleibt beim Neuzeichnen samt Eingabe erhalten)
        if can_add:
            if self.add_group is None:
                self._make_add(page=kind == "page")
            self.page.add(self.add_group)
        else:
            self.add_group = None
        # unten: Stummschalten (Dauer, Mitteilungen), Medien, Archivieren, Verlassen
        from .publish import button_row
        self.foot_group = Adw.PreferencesGroup()
        if std["mute2"]:
            mr = button_row(K.mute_label(c), lambda: menu_popover(self.mute_row, SV.mute_entries(
                self.win, chat.chat(self.cid) or c, self._toast)[0][1]).popup())
            self.mute_row = mr
            self.foot_group.add(mr)
        else:
            self.foot_group.add(button_row(_("Unmute") if c.get("muted") else _("Mute"), self._mute))
        if std["media"]:
            self.foot_group.add(button_row(_("Media, files, links"), lambda: SV.MediaDialog(self.win, self.cid).present(self)))
        if chat.can_archive(c):
            self.foot_group.add(button_row(_("Unarchive") if c.get("archived") else _("Archive"), self._archive))
        if kind == "free":
            self.foot_group.add(button_row(_("Leave group"), lambda: leave_group(self.win, self.cid), destructive=True))
        self.page.add(self.foot_group)

    def _member_row(self, c, m, fed):
        mine = gi_mine(c, m)
        name = m.get("name") or "?"
        row = Adw.ActionRow(title=GLib.markup_escape_text(name), subtitle=GLib.markup_escape_text(gi_sub(c, m)))
        row.add_prefix(avatars.person(m, 32, text=name))
        row.add_css_class("hk-gi-member")
        if mine:
            b = _lbl(_("You"), ("hk-badge", "hk-gi-me"), wrap=False, valign=Gtk.Align.CENTER)
            row.add_suffix(b)
        if m.get("admin"):
            b = _lbl(_("Admin"), ("hk-badge", "hk-badge-accent", "hk-gi-admin"), wrap=False, valign=Gtk.Align.CENTER)
            row.add_suffix(b)
        ents = []
        if not mine:
            if fed and m.get("handle") and (chat.fk.get(m["handle"]) or {}).get("keys"):
                ents.append((_("Safety number"), lambda m=m: open_safety_v2(self.win, self.cid, m)))
            elif not fed and (self.rc.get(m.get("username")) or {}).get("ready"):
                ents.append((_("Safety number"), lambda u=m.get("username"): open_safety(self.win, self.cid, u)))
        acts = gi_actions(c, m)
        if "admin_on" in acts:
            ents.append((_("Make admin"), lambda m=m: self._admin(m, True)))
        if "admin_off" in acts:
            ents.append((_("Remove as admin"), lambda m=m: self._admin(m, False)))
        if "remove" in acts:
            ents.append((_("Remove from group"), lambda m=m: self._ask_remove(m), True))
        pa = K.page_member_action(c, m)                    # (2.5, pagechatmembers1) Seiten-Chat: Freigabe entziehen
        if pa and pa[0] == "remove":
            acts = acts + ["page_remove"]
            ents.append((_("Remove from chat"), lambda m=m: self._ask_remove_page(m), True))
        elif pa:
            acts = acts + ["page_off"]
            row.hk_why = pa[1]
            ents.append((_("Remove from chat") + " – " + pa[1], None))
        tg = gi_handle(m)
        if K.has(K.BLOCK2) and not mine and tg and not is_remote(c):     # (2.5, block2) Konten dieses Servers
            if m.get("blocked"):
                acts = acts + ["unblock"]
                ents.append((_("Unblock"), lambda tg=tg: SV.unblock_person(self.win, tg, self._toast)))
            else:
                acts = acts + ["block"]
                ents.append((_("Block {name}").format(name=name), lambda tg=tg, nm=name: SV.block_person(self, self.win, tg, nm, self._toast),
                             True))
        if m.get("blocked"):
            b = _lbl(_("Blocked"), ("hk-badge", "hk-gi-blocked"), wrap=False, valign=Gtk.Align.CENTER)
            row.add_suffix(b)
        row.hk_actions = acts
        if ents:
            row.add_suffix(_more_button(_("Actions for {name}").format(name=name), lambda e=ents: e))
        return row

    # --- Aktionen ---
    def _rename(self):
        name = (self.name_row.get_text() if self.name_row else "").strip()[:100]
        c = chat.chat(self.cid) or {}
        if not name or name == c.get("_title"):
            return

        def done(_r):
            cc = chat.chat(self.cid)
            if cc is not None:
                cc["_title"] = name
            chat.load_chat(self.cid)
            chat.emit("changed")
        bg(chat.send, self.cid, {"v": 1, "type": "title", "title": name}, None, "title", done=done,
           error=lambda e: self._toast(_("Not sent: {err}").format(err=e)))

    def _admin(self, m, on):
        name = _("You") if gi_mine(chat.chat(self.cid), m) else (m.get("name") or "?")
        chat.set_admin(self.cid, m.get("username"), on,
                       done=lambda _ch: self._toast(_("{name} is now an admin.").format(name=name) if on else _("Admin role removed.")),
                       error=lambda e: self._toast(e))

    def _ask_remove(self, m):
        from .publish import confirm
        name = m.get("name") or "?"

        def go():
            def fin(_r):
                self._toast(_("{name} was removed.").format(name=name))
                chat.load_chat(self.cid)
            bg(client.request, "DELETE", f"api/chats/{self.cid}/members/{GLib.Uri.escape_string(m['username'], None, True)}", done=fin,
               error=lambda e: self._toast(e))
        confirm(self, _("Remove {name} from the group?").format(name=name),
                _("{name} will no longer receive new messages from this group.").format(name=name), _("Remove"), go)

    def _ask_remove_page(self, m):
        """(2.5) Seiten-Chat: direkte Freigabe entziehen (DELETE .../members/{who}); 409 nennt, woher der Zugriff kommt"""
        from .publish import confirm
        name = m.get("name") or "?"

        def go():
            def fin(_r):
                self._toast(_("{name} was removed.").format(name=name))
                chat.load_chat(self.cid)
            bg(client.request, "DELETE", f"api/chats/{self.cid}/members/{GLib.Uri.escape_string(m['username'], None, True)}", done=fin,
               error=lambda e: self._toast(e))
        confirm(self, _("Remove {name} from the page chat?").format(name=name),
                _("{name}'s access to the page will be revoked.").format(name=name), _("Remove"), go)

    def _mute(self):
        c = chat.chat(self.cid) or {}
        chat.patch(self.cid, {"muted": not c.get("muted")}, error=lambda e: self._toast(e))

    def _archive(self):
        c = chat.chat(self.cid) or {}
        on = not c.get("archived")
        chat.set_archived(self.cid, on, done=lambda _ch: chat.load_chat(self.cid), error=lambda e: self._toast(e))

    # --- Mitglied hinzufuegen ---
    def _make_add(self, page=False):
        from .sharing import search_min
        self.add_group = Adw.PreferencesGroup(title=_("Add member"))
        self.add_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, visible=False)
        self.role_row = None
        if page:                                           # (2.5, pagechatmembers1) Hinzufuegen teilt die Seite
            self.add_box.append(_lbl(_("The page will be shared with this person."), ("dim-label", "hk-gi-phint")))
            lbr = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
            lbr.add_css_class("boxed-list")
            labels = K.page_role_labels()
            self.role_row = Adw.ComboRow(title=_("Role on the page"), model=Gtk.StringList.new([labels[r] for r in K.PAGE_ROLES]))
            lbr.append(self.role_row)
            self.add_box.append(lbr)
        lb0 = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lb0.add_css_class("boxed-list")
        self.add_en = Adw.EntryRow(title=_("Search person") if search_min() == 1 else _("Add person (3+ characters)"))
        lb0.append(self.add_en)
        self.add_box.append(lb0)
        self.add_info = _lbl("", ("dim-label", "caption"), visible=False)
        self.add_box.append(self.add_info)
        self.ct_head = _lbl(_("Contacts"), ("heading",), visible=False, margin_top=4)
        self.add_box.append(self.ct_head)
        self.add_res = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.add_res.add_css_class("boxed-list")
        self.add_box.append(self.add_res)
        self.add_group.add(self.add_box)
        self.contacts = ContactList(self.ct_head, self.add_res, self._toast)
        self.add_q = ""
        soon = Debounce(250, self._add_search)
        self.add_en.connect("changed", lambda *_: soon())

    def _toggle_add(self):
        if self.add_box is None:
            return
        on = not self.add_box.get_visible()
        self.add_box.set_visible(on)
        if on:
            self._add_search()
            self.add_en.grab_focus()

    def _pick(self, it):
        self._add(None, handle=it["ref"]) if it.get("remote") else self._add(it["ref"])

    def _add_contacts(self):
        c = chat.chat(self.cid) or {}
        self.add_res.remove_all()
        if server_has("contacts1"):
            self.contacts.show(self._pick, keep=contact_keep_for(c), still=lambda: not self.add_en.get_text().strip())
        else:
            self.contacts.hide()

    def _add_search(self):
        from .sharing import is_member, search_min
        q = self.add_en.get_text().strip()
        self.add_q = q
        self.add_info.set_visible(False)
        if not q:
            self._add_contacts()
            return
        self.contacts.hide()
        self.add_res.remove_all()
        if len(q) < search_min():
            return
        c = chat.chat(self.cid) or {}

        def show(j):
            if self.add_en.get_text().strip() != q:
                return
            self.add_res.remove_all()
            j = j or {}
            users = [u for u in j.get("users") or [] if isinstance(u, dict) and u.get("username") and not is_member(u["username"], c.get("members"))]
            have = {m.get("handle") for m in c.get("members") or [] if isinstance(m, dict) and m.get("handle")}
            rem = [u for u in j.get("remote") or [] if isinstance(u, dict) and u.get("handle") and u["handle"] not in have]
            if not is_fed(c) and rem:
                self.add_info.set_label(_("People on other Saga servers can only be added to new groups – please create a new group."))
                self.add_info.set_visible(True)
                rem = []
            if not users and not rem and fed_hint_show(q, 0):
                self.add_res.append(Gtk.ListBoxRow(activatable=False, selectable=False, child=fed_hint()))
            for u in users[:8]:
                sub = [x for x in (person_mark(u), "" if u.get("ready", True) else _("hasn't set up chats yet")) if x]
                r = Adw.ActionRow(title=GLib.markup_escape_text(u.get("name") or "?"), subtitle=GLib.markup_escape_text(" · ".join(sub)), activatable=True)
                r.add_prefix(avatars.person(u, 32, text=u.get("name") or "?"))
                r.connect("activated", lambda *_, u=u["username"]: self._add(u))
                if u.get("contact"):
                    contact_menu(r, u["username"], u.get("name") or "?", self._toast)
                self.add_res.append(r)
            for u in rem[:5]:
                r = Adw.ActionRow(title=GLib.markup_escape_text(remote_label(u)),
                                  subtitle=GLib.markup_escape_text(_("Person on {host}").format(host=u.get("host") or "?")), activatable=True)
                r.add_prefix(avatars.person(u, 32, text=u.get("name") or "?"))
                r.add_css_class("hk-remote-hit")
                r.connect("activated", lambda *_, h=u["handle"]: self._add(None, handle=h))
                if u.get("contact"):
                    contact_menu(r, u["handle"], remote_label(u), self._toast)
                self.add_res.append(r)
        bg(client.get, "api/chat/people?q=" + GLib.Uri.escape_string(q, None, True), done=show, error=lambda e: self._toast(e))

    def _add(self, u, handle=None):
        role = K.PAGE_ROLES[self.role_row.get_selected()] if getattr(self, "role_row", None) is not None else None
        super()._add(u, handle, role=role)
        if self.add_box is not None:
            self.add_en.set_text("")


class NewChatDialog(Adw.Dialog):
    """Neue Unterhaltung: Person (Direkt-Chat), Gruppe (Gruppen-Chat) oder freie Gruppe mit mehreren Personen"""

    def __init__(self, win):
        super().__init__(title=_("New conversation"), content_width=460, content_height=560)
        self.win = win
        self.multi = False
        self.picked = {}                 # username bzw. Handle (andere Server, 2.3) -> name (freie Gruppe)
        self.picked_remote = set()       # davon Handles (Personen anderer Server)
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        tv.add_top_bar(hb)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_top=6, margin_bottom=12, margin_start=12, margin_end=12)
        from .sharing import search_min
        self.search = Gtk.SearchEntry(placeholder_text=_("Search person or group") if search_min() == 1 else
                                      _("Search person or group (3+ characters)"))       # GTK entprellt (Server: 60/min)
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
        rbox = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.ct_head = _lbl(_("Contacts"), ("heading",), visible=False, margin_start=2)    # 2.5: ohne Eingabe die Kontakte
        rbox.append(self.ct_head)
        rbox.append(self.results)
        sw.set_child(rbox)
        box.append(sw)
        self.multi_btn = _button(_("+ Group with several people …"), ("flat",), self._toggle_multi, halign=Gtk.Align.START)
        box.append(self.multi_btn)
        tv.set_content(box)
        self.toasts = Adw.ToastOverlay(child=tv)           # Fehler vom Server im Dialog melden (BUG-075)
        self.set_child(self.toasts)
        self._update()
        self.q = ""
        self.contacts = ContactList(self.ct_head, self.results, lambda t: t and self.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(t)))))
        self._search()
        watch_chat(self, "contacts", lambda: not self.q and self._search())
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
                                    cb=lambda u=u: (self.picked.pop(u, None), self.picked_remote.discard(u), self._update())))
            self.chips.append(chip)
        self.create.set_sensitive(bool(self.picked))

    def _search(self):
        q = self.search.get_text().strip()
        self.q = q
        self.results.remove_all()
        from .sharing import search_min
        if not q and server_has("contacts1"):              # 2.5: Kontakte (zuletzt zuerst)
            self.contacts.show(self._pick_contact, still=lambda: not self.q)
            return
        self.contacts.hide()
        if len(q) < search_min():                          # Server ab 2.3: ab 1 Zeichen (bei 1-2 nur genaue Treffer), sonst ab 3
            return
        bg(client.get, "api/chat/people?q=" + GLib.Uri.escape_string(q, None, True), done=lambda j: self._show(q, j),
           error=lambda e: self._show(q, {"error": str(e)}))

    def _show(self, q, j):
        if q != self.q:
            return
        self.results.remove_all()
        users = [u for u in (j or {}).get("users") or [] if isinstance(u, dict)]
        groups = [g for g in (j or {}).get("groups") or [] if isinstance(g, dict)] if not self.multi else []
        remote = [u for u in (j or {}).get("remote") or [] if isinstance(u, dict) and u.get("handle")]     # 2.3: andere Server
        for u in users:
            sub = [x for x in (person_mark(u), "" if u.get("ready") else _("hasn't set up chats yet")) if x]   # N2: Handle
            r = Adw.ActionRow(title=GLib.markup_escape_text(u.get("name") or u["username"]),
                              subtitle=GLib.markup_escape_text(" · ".join(sub)),     # 2.4 (W4): nie den Anmeldenamen
                              activatable=True)
            r.add_prefix(avatars.person(u, 32, text=u.get("name") or u["username"]))
            r.connect("activated", lambda *_, u=u: self._pick_user(u))
            if u.get("contact"):                           # 2.5: Kontakte stehen vorne - Menue "Aus Kontakten entfernen"
                contact_menu(r, u["username"], u.get("name") or "?", self.contacts.toaster)
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
        for u in remote:                                   # Direkt-Chat ueber Servergrenzen: "Name · host" mit Profilbild
            r = Adw.ActionRow(title=GLib.markup_escape_text(remote_label(u)),
                              subtitle=GLib.markup_escape_text(_("Person on {host} · first message as a request").format(host=u.get("host") or "?")),
                              activatable=True)
            r.add_prefix(avatars.person(u, 32, text=u.get("name") or "?"))
            r.add_css_class("hk-remote-hit")
            r.connect("activated", lambda *_, u=u: self._pick_remote(u))
            if u.get("contact"):
                contact_menu(r, u["handle"], remote_label(u), self.contacts.toaster)
            self.results.append(r)
        if not users and not groups and not remote:
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

    def _pick_contact(self, it):
        """Kontakt gewaehlt: lokal ref = Such-Handle ({user}), fremd ref = '~x@host' ({handle})"""
        if it.get("remote"):
            self._pick_remote({"handle": it["ref"], "name": it["name"], "host": it.get("host"), "label": it["name"] + (" · " + it["host"] if it.get("host") else "")})
        else:
            self._pick_user({"username": it["ref"], "name": it["name"]})
        if self.multi:
            self._search()

    def _pick_remote(self, u):
        if self.multi:                                     # 2.3: Gruppe mit Personen anderer Server (Heim = dieser Server)
            self.picked[u["handle"]] = remote_label(u)
            self.picked_remote.add(u["handle"])
            self.search.set_text("")
            self._update()
            return
        self._open({"kind": "dm", "handle": u["handle"]})

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
        loc = sorted(u for u in self.picked if u not in self.picked_remote)
        rem = sorted(self.picked_remote)
        self._open(dict({"kind": "free", "members": loc}, **({"handles": rem} if rem else {})), then=titled)


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
        # Bughunt D01: Anzeigename gleich mit abfragen (optional; nur Konten, deren Namen Saga verwaltet)
        self.acc = None
        self.name_box = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, visible=False)
        self.name_box.add_css_class("boxed-list")
        self.name_row = Adw.EntryRow(title=_("Display name"))
        self.name_box.append(self.name_row)
        b.append(self.name_box)
        self.name_hint = _lbl(_("Optional – everyone you chat with can see it."), ("dim-label", "caption"), visible=False)
        b.append(self.name_hint)
        if not reset:
            bg(client.get, "api/account", done=self._acc, error=lambda e: None)
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

    def _acc(self, j):
        self.acc = account_name(j)
        if self.acc and self.acc[1]:
            self.name_row.set_text(self.acc[0])
            self.name_box.set_visible(True)
            self.name_hint.set_visible(True)

    def _save_name(self):
        """Anzeigename speichern, wenn geaendert - im Hintergrund, die Einrichtung wartet nicht (Name ist optional)"""
        if not self.acc or not self.acc[1]:
            return
        v = self.name_row.get_text().strip()
        if v == self.acc[0]:
            return
        self.acc = (v, self.acc[1], self.acc[2])
        bg(client.json, "PATCH", "api/account", account_body(v, self.acc[2]), done=lambda _r: None,
           error=lambda e: self.add_toast(Adw.Toast(title=GLib.markup_escape_text(_("Display name not saved: {err}").format(err=e)))))

    def _create(self):
        self._save_name()
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
        self._fed = {}               # (2.3) einmal je Seite geholt: "blocks" (Liste), "account" (Antwort) - Neuaufbau ohne Anfrage
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
            label = _("Remove from this device") if this else _("Remove")
            if getattr(getattr(self.dlg, "win", None), "very_narrow", False):
                # 370 px (N11): Knopf mit langem Text liess der Unterzeile kaum Platz ("angemeld-et") - nur das Symbol
                rm = Gtk.Button(icon_name="user-trash-symbolic", tooltip_text=label, valign=Gtk.Align.CENTER)
                rm.add_css_class("destructive-action")
                rm.update_property([Gtk.AccessibleProperty.LABEL], [label])
                rm.connect("clicked", lambda *_, d=d, this=this: self._remove(d, this))
            else:
                rm = _button(label, ("destructive-action",), lambda d=d, this=this: self._remove(d, this), valign=Gtk.Align.CENTER)
            r.add_suffix(rm)
            g.add(r)
        self.add(g)
        self.groups.append(g)
        self._privacy_group()                                # (2.4) gut auffindbar gleich nach den Geraeten
        if chat.status == "ready" and K.has(K.UNFURL):        # (2.5) Linkvorschau beim Schreiben (nur dieses Geraet)
            gu = Adw.PreferencesGroup()
            ru = Adw.SwitchRow(title=_("Link previews"), active=config.settings.get("chat_unfurl") is not False,
                               subtitle=_("When you type a link, your server fetches a preview and it is sent encrypted with the "
                                          "message. Recipients load nothing."))
            ru.connect("notify::active", lambda r, _p: config.settings.set("chat_unfurl", None if r.get_active() else False))
            gu.add(ru)
            self.add(gu)
            self.groups.append(gu)
        if chat.ready:
            g2 = Adw.PreferencesGroup(title=_("Recovery key"),
                                      description=_("Lost it or someone may have seen it? Create a new one – the old one stops working. "
                                                    "Your keys and messages stay the same."))
            from .publish import button_row
            g2.add(button_row(_("Create new recovery key"), self._new_key))
            self.add(g2)
            self.groups.append(g2)
            self._blocks_group()
        self._directory_group()
        g3 = Adw.PreferencesGroup(title=_("Security"),
                                  description=_("Private keys never leave this device unencrypted. The server only holds public keys, "
                                                "encrypted messages and your key bundle encrypted with the recovery key. Compare safety "
                                                "numbers to make sure nobody is in between."))
        self.add(g3)
        self.groups.append(g3)

    def _blocks_group(self):
        """(2.3) Gesperrt: Personen bzw. ganze Server anderer Saga-Instanzen - von dort kommen keine Anfragen mehr.
        Aeltere Server (ohne /api/chat/blocks): Bereich bleibt weg."""
        g = Adw.PreferencesGroup(title=_("Blocked people") if K.has(K.BLOCK2) else _("Blocked (other Saga servers)"), visible=False)
        self.add(g)
        self.groups.append(g)

        def show(lst):
            self._fed["blocks"] = lst
            if g not in self.groups:
                return
            g.set_visible(True)
            if not lst:
                g.add(Adw.ActionRow(title=_("Nobody blocked.")))
                return
            for b in lst:
                inst = b.get("kind") == "instance"
                r = Adw.ActionRow(title=GLib.markup_escape_text(str((b.get("host") if inst else b.get("label") or b.get("name")) or "?")),
                                  subtitle=_("whole server") if inst else _("Person on this server") if b.get("local") else _("Person"))
                r.add_suffix(_button(_("Unblock"), (), lambda b=b, r=r: self._unblock(g, b, r), valign=Gtk.Align.CENTER))
                g.add(r)
        watch_chat(g, "blocks", lambda: (self._fed.pop("blocks", None), self.fill()))     # (2.5) Live chat.blocks
        if "blocks" in self._fed:
            show(self._fed["blocks"])
        elif not self._fed.get("blocks_off"):                 # aeltere Server: einmal fragen, dann nicht mehr
            chat.load_blocks(show, error=lambda e: self._fed.__setitem__("blocks_off", True))

    def _privacy_group(self):
        """(2.4) Datenschutz: Gelesen-Bestaetigungen und Online-Status - getrennt, gegenseitig. Nur, wenn der Server es kann
        (features in GET /api/chat/me); aeltere Server: Bereich bleibt weg."""
        feats = S.features(chat.me)
        if chat.status != "ready" or not {"receipts", "online"} & feats:
            return
        g = Adw.PreferencesGroup(title=_("Privacy"))
        self.add(g)
        self.groups.append(g)
        p = S.privacy(chat.me)
        for field, title, sub in (
                ("receipts", _("Send read receipts"),
                 _("Off: Others don't see whether you've read their messages – and you don't see it for them either.")),
                ("online", _("Show online status"),
                 _("Off: Your chat partners don't see whether or when you were last online – and you don't see it for them "
                   "either."))):
            if field not in feats:
                continue
            row = Adw.SwitchRow(title=title, subtitle=sub, active=p[field])
            row.hk_field = field
            row.connect("notify::active", self._privacy_changed)
            g.add(row)

    def _privacy_changed(self, row, _p):
        on = row.get_active()
        if S.privacy(chat.me).get(row.hk_field) == on:     # Neuaufbau bzw. Rueckstellung nach einem Fehler
            return
        row.set_sensitive(False)

        def done(_j):
            row.set_sensitive(True)

        def err(e):
            row.set_sensitive(True)
            row.set_active(not on)
            self.dlg.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e))))
        chat.set_privacy(row.hk_field, on, done=done, error=err)

    def _directory_group(self):
        """(2.3) "In Adressbuechern verbundener Server erscheinen" - nur, wenn dieser Server mit anderen verbunden ist
        (GET/PUT /api/account/federation; aeltere Server: Bereich bleibt weg)"""
        g = Adw.PreferencesGroup(title=_("Connected Saga servers"), visible=False,
                                 description=_("People on connected Saga servers can find you by your display name and start a chat – "
                                               "the first message arrives as a request."))
        self.add(g)
        self.groups.append(g)
        row = Adw.SwitchRow(title=_("Appear in the address books of connected servers"))
        g.add(row)
        st = {"busy": False}

        def show(j):
            self._fed["account"] = j
            if g not in self.groups or not isinstance(j, dict) or not j.get("federation"):
                return
            st["busy"] = True
            row.set_active(bool(j.get("directory", True)))
            st["busy"] = False
            g.set_visible(True)

        def changed(r, _p):
            if st["busy"]:
                return
            on = r.get_active()

            def err(e):
                st["busy"] = True
                r.set_active(not on)
                st["busy"] = False
                self.dlg.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e))))
            if isinstance(self._fed.get("account"), dict):
                self._fed["account"]["directory"] = on
            bg(client.json, "PUT", "api/account/federation", {"directory": on}, error=err)
        row.connect("notify::active", changed)
        if "account" in self._fed:
            show(self._fed["account"])
        else:
            bg(client.get, "api/account/federation", done=show, error=lambda e: self._fed.setdefault("account", None))

    def _unblock(self, g, b, r):
        def done(_r):
            self._fed["blocks"] = [x for x in self._fed.get("blocks") or [] if x is not b]
            if r.get_parent() is not None:
                g.remove(r)
            self.dlg.add_toast(Adw.Toast(title=_("Unblocked.")))
        chat.unblock(b.get("target"), done=done, error=lambda e: self.dlg.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e)))))

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
