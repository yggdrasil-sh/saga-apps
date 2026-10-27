"""Hauptfenster: Seitenleiste (Baum, Heute, Favoriten), Navigation, Peek, Anmeldung, Tastenkuerzel."""
import datetime as _dt
import io
import os
import time

from gi.repository import Adw, Gdk, Gio, GLib, GObject, Gtk, Pango

from . import APP_ID, PROJECT_URL, avatars, config, images
from .api import APIError, Cancelled, client, pkce
from .blocks import context_menu, flat_button, menu_popover
from .config import device_name, get_device_token, settings
from .i18n import _, ngettext
from .graphview import available as graph_available
from .page import PageView
from .publish import available as publish_available, globe_icon, header_state, open_publish
from .entries import start_switch
from .store import is_db, label, store, watch
from .util import bg, date_num, norm, placeholder


def set_expanded_state(w, on):
    """Screenreader: auf-/zugeklappt (der Zustand erwartet einen int-Wert: 0/1, -1 = unbestimmt)"""
    v = GObject.Value(GObject.TYPE_INT, 1 if on else 0)
    w.update_state([Gtk.AccessibleState.EXPANDED], [v])


class SidebarRow(Gtk.ListBoxRow):
    """Zeile der Seitenleiste (Seite, fester Eintrag oder Aktion): per Tastatur (Pfeile, Enter, Menue-Taste,
    Umschalt+F10, Links/Rechts zum Zu-/Aufklappen), Maus, Touch (langes Druecken) und Screenreader bedienbar"""
    __gtype_name__ = "HKSidebarRow"

    def __init__(self, win, title, icon_widget=None, key=None, depth=0, page=None, badge=None, expander=None, action=None,
                 snippet=None, kids=False, expanded=False):
        super().__init__(activatable=True, selectable=False)
        self.win, self.key, self.page, self.action, self.kids = win, key, page, action, kids
        self.add_css_class("hk-srow")
        box = Gtk.Box(spacing=6, margin_start=depth * 14)
        if expander is not None:
            box.append(expander)
        elif depth or page is not None:
            box.append(Gtk.Box(width_request=22))
        if icon_widget is not None:
            box.append(icon_widget)
        self.lbl = Gtk.Label(label=title, xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.END)
        box.append(self.lbl)
        if badge:
            b = Gtk.Label(label=str(badge))
            b.add_css_class("hk-badge")
            box.append(b)
        name = title + (f" ({badge})" if badge else "")
        foreign = page is not None and store.is_foreign(page.get("id"))
        if page is not None and page.get("unread"):            # neue Kommentare von anderen: kleiner Punkt
            dot = Gtk.Label(label="●", valign=Gtk.Align.CENTER, tooltip_text=_("New comments"))
            dot.add_css_class("hk-unread-dot")
            box.append(dot)
            name += " – " + _("New comments")
        if page is not None and not foreign and page.get("shared"):     # eigene, geteilte Seite: dezentes Personen-Symbol
            from .sharing import people_icon
            si = Gtk.Image.new_from_icon_name(people_icon())
            si.add_css_class("dim-label")
            si.set_tooltip_text(_("Shared"))
            box.append(si)
            name += " – " + _("Shared")
        if page is not None and foreign:
            from .sharing import shared_by_tooltip
            tt = shared_by_tooltip(page) if page.get("root") else ""
            if tt:
                self.set_tooltip_text(tt)
        if page is not None:
            more = Gtk.Button(icon_name="view-more-symbolic", tooltip_text=_("Page actions"), valign=Gtk.Align.CENTER)
            more.add_css_class("flat")
            more.add_css_class("hk-tiny")
            more.add_css_class("hk-reveal")
            more.set_focusable(False)                          # Tastatur: Menue-Taste/Umschalt+F10 auf der Zeile
            more.connect("clicked", lambda b: win.page_actions(b, page))
            box.append(more)
            context_menu(self, lambda w: win.page_actions(w, page))
            if not foreign:                                    # fremde Seiten verschiebt nur der Eigentuemer
                ds = Gtk.DragSource(actions=Gdk.DragAction.MOVE)
                ds.connect("prepare", lambda *_: Gdk.ContentProvider.new_for_value(f"hkpage:{page['id']}"))
                self.add_controller(ds)
            if not page.get("archived") and not foreign:
                dt = Gtk.DropTarget.new(GObject.TYPE_STRING, Gdk.DragAction.MOVE)
                dt.connect("enter", lambda *_: (self.add_css_class("hk-drop-into"), Gdk.DragAction.MOVE)[1])
                dt.connect("leave", lambda *_: self.remove_css_class("hk-drop-into"))
                dt.connect("drop", lambda t, v, x, y: (self.remove_css_class("hk-drop-into"), win.drop_pages(v, page["id"]))[1])
                self.add_controller(dt)
        if snippet:
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            col.append(box)
            s = Gtk.Label(label=snippet, xalign=0, wrap=True, lines=2, ellipsize=Pango.EllipsizeMode.END,
                          margin_start=34 + depth * 14)
            s.add_css_class("caption")
            s.add_css_class("dim-label")
            col.append(s)
            self.set_child(col)
        else:
            self.set_child(box)
        self.update_property([Gtk.AccessibleProperty.LABEL], [name])
        if kids:
            set_expanded_state(self, expanded)

    def run(self):
        if self.action is not None:
            self.action()
        elif self.key is not None:
            self.win.show_root(self.key)


def _section_row(text, drop_cb=None, badge=None, badge_tip=None):
    """Ueberschrift in der Seitenleiste (nicht auswaehlbar, wird beim Navigieren mit den Pfeiltasten uebersprungen)"""
    lb = Gtk.Label(label=text, xalign=0, margin_start=6, margin_top=10, margin_bottom=2)
    lb.add_css_class("caption-heading")
    lb.add_css_class("dim-label")
    child = lb
    if badge:                                                  # z. B. Seiten mit neuen Kommentaren
        child = Gtk.Box(spacing=6)
        child.append(lb)
        b = Gtk.Label(label=str(badge), valign=Gtk.Align.END, margin_bottom=2, tooltip_text=badge_tip)
        b.add_css_class("hk-badge")
        b.add_css_class("hk-badge-accent")
        child.append(b)
    row = Gtk.ListBoxRow(activatable=False, selectable=False, focusable=False, child=child)
    row.add_css_class("hk-shead")
    row.update_property([Gtk.AccessibleProperty.LABEL], [text + (f" – {badge_tip}" if badge and badge_tip else "")])
    if drop_cb is not None:
        dt = Gtk.DropTarget.new(GObject.TYPE_STRING, Gdk.DragAction.MOVE)
        dt.connect("drop", lambda t, v, x, y: drop_cb(v))
        row.add_controller(dt)
        row.set_tooltip_text(_("Drop here to move to the top level"))
    return row


def _chat_sig():
    """(Chats in der Seitenleiste zeigen?, Ungelesen) - ab Server 1.5, solange der Server Chats nicht abgeschaltet hat"""
    from .chat import chat
    return (chat.shown, chat.unread)


class Sidebar(Gtk.Box):
    __gtype_name__ = "HKSidebar"

    def __init__(self, win):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.win = win
        self.rows = {}
        self.sig = None
        self.query = ""
        self.results = []
        self.expanded = set(settings.get("expanded", []))
        self.show_archive = False
        self.search = Gtk.SearchEntry(placeholder_text=_("Search pages"), margin_start=8, margin_end=8, margin_bottom=6)
        self.search.connect("search-changed", self._search)
        self.search.connect("stop-search", lambda *_: self.search.set_text(""))
        self.append(self.search)
        sw = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.box = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, margin_bottom=12)
        self.box.add_css_class("navigation-sidebar")
        self.box.update_property([Gtk.AccessibleProperty.LABEL], [_("Pages")])
        self.box.connect("row-activated", lambda lb, row: isinstance(row, SidebarRow) and row.run())
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", self._key)
        self.box.add_controller(kc)
        sw.set_child(self.box)
        self.append(sw)
        watch(self, "pages-changed", lambda: self.fill(force=False))
        watch(self, "today-changed", self._today_changed)
        from .chatview import watch_chat
        watch_chat(self, "changed", lambda: self.fill(force=False))
        self.fill(force=True)

    def _key(self, c, kv, code, state):
        return self.row_key(self.box.get_focus_child(), kv, state)

    def row_key(self, row, kv, state):
        """Links/Rechts (bzw. +/-): Seite zu-/aufklappen wie in einer Baumansicht"""
        if not isinstance(row, SidebarRow) or row.page is None or state & (Gdk.ModifierType.CONTROL_MASK | Gdk.ModifierType.ALT_MASK):
            return False
        pid = row.page["id"]
        if kv in (Gdk.KEY_Right, Gdk.KEY_plus, Gdk.KEY_KP_Add) and row.kids and pid not in self.expanded:
            self.toggle(pid)
            return True
        if kv in (Gdk.KEY_Left, Gdk.KEY_minus, Gdk.KEY_KP_Subtract) and pid in self.expanded:
            self.toggle(pid)
            return True
        return False

    def _search(self, e):
        self.query = e.get_text().strip()
        if len(self.query) >= 2:
            q = self.query

            def got(r):
                if q == self.query:
                    self.results = r or []
                    self.fill(force=True)
            store.search(q, got)
        self.fill(force=True)

    def _sig(self):
        return (tuple((p["id"], p.get("title"), p.get("icon"), p.get("parent_id"), p.get("pinned"), p.get("kind"), p.get("shared"),
                       p.get("unread")) for p in store.pages),
                tuple((p["id"], p.get("title"), p.get("icon"), p.get("parent_id"), p.get("kind"), p.get("root"), p.get("unread"),
                       p.get("role"), (p.get("owner") or {}).get("name")) for p in store.shared), store.share_off,
                tuple(p["id"] for p in store.archived), tuple(store.recent[:5]), len(store.tasks), self.query, len(self.results),
                tuple(sorted(self.expanded)), self.show_archive, tuple(store.fed_unreachable), _chat_sig(), graph_available())

    def _today_changed(self):
        self.fill(force=False)                       # nur neu, wenn sich die Anzahl der Aufgaben geaendert hat

    def header(self, text, drop=False):
        self.box.append(_section_row(text, (lambda v: self.win.drop_pages(v, None)) if drop else None))

    def _focused_key(self):
        """Zeile mit dem Tastaturfokus (nach dem Neuaufbau wieder dorthin) - nur wenn der Fokus wirklich in der Liste ist:
        get_focus_child() bleibt sonst auf der zuletzt fokussierten Zeile stehen, und der Neuaufbau nach "Neue Seite"
        holte den Fokus aus dem Titel der neuen Seite zurueck in die Seitenleiste (N4)"""
        foc = self.win.get_focus() if self.win is not None else None
        if foc is None or not (foc is self.box or foc.is_ancestor(self.box)):
            return None
        row = self.box.get_focus_child()
        return getattr(row, "key", None) if row is not None else None

    def fill(self, force=False):
        sig = self._sig()
        if not force and sig == self.sig:
            self.win.mark_active()
            return
        self.sig = sig
        refocus = self._focused_key()
        self.box.remove_all()
        self.rows = {}
        w = self.win
        if self.query:
            self.header(_("Results"))
            n = norm(self.query)
            by_name = [p for p in store.pages + store.shared if n in norm(label(p))]
            ids = {p["id"] for p in by_name}
            hits = by_name + [r for r in self.results if r["id"] not in ids]
            if not hits:
                lb = Gtk.Label(label=_("No results"), xalign=0, margin_start=6)
                lb.add_css_class("dim-label")
                self.box.append(Gtk.ListBoxRow(activatable=False, selectable=False, child=lb))
            for p in hits[:60]:
                self._page_row(p, 0, flat=True, snippet=p.get("snippet"))
            return
        for key, title, icon, badge in (("tasks", _("Tasks"), "object-select-symbolic", len(store.tasks) or None),
                                        ("events", _("Events"), "x-office-calendar-symbolic", None),
                                        ("services", _("Services"), "view-app-grid-symbolic", None),
                                        ("graph", _("Graph"), "saga-graph-symbolic", None)):
            if key == "graph" and not graph_available():          # 2.5: Graph der Seiten, nur wenn der Server ihn kennt
                continue
            r = SidebarRow(w, title, Gtk.Image.new_from_icon_name(icon), key=(key,), badge=badge)
            self.rows[(key,)] = r
            self.box.append(r)
        if _chat_sig()[0]:                                     # Chats (1.5): mit Ungelesen-Zahl
            from .chat import chat
            from .chatview import chat_icon
            r = SidebarRow(w, _("Chats"), Gtk.Image.new_from_icon_name(chat_icon()), key=("chats",), badge=chat.unread or None)
            self.rows[("chats",)] = r
            self.box.append(r)
        self.rows["tasks_badge"] = True
        self.header(_("Today"))
        from .agenda import TodayBox
        tb = TodayBox(w, compact=True)
        self.box.append(Gtk.ListBoxRow(activatable=False, selectable=False, focusable=False, child=tb))
        if store.favorites:
            self.header(_("Favorites"))
            for p in store.favorites:
                self._page_row(p, 0, flat=True)
        rec = [store.meta(i) for i in store.recent if store.meta(i) is not None and not store.meta(i).get("archived")][:5]
        if rec:
            self.header(_("Recent"))
            for p in rec:
                self._page_row(p, 0, flat=True)
        self.header(_("Pages"), drop=True)
        if store.children(None):
            self._tree(None, 0)
        else:                                                  # keine eigenen Seiten: Hinweis statt leerer Ueberschrift
            lb = Gtk.Label(label=_("No pages yet"), xalign=0, margin_start=6)
            lb.add_css_class("dim-label")
            self.box.append(Gtk.ListBoxRow(activatable=False, selectable=False, child=lb))
        roots = store.shared_roots()
        if roots or store.share_off or store.fed_unreachable:                           # Teilen (1.3): "Mit mir geteilt"
            n = store.shared_unread()
            self.box.append(_section_row(_("Shared with me"), badge=n or None,
                                         badge_tip=ngettext("{n} page with new comments", "{n} pages with new comments", n).format(n=n)))
            if store.share_off:
                lb = Gtk.Label(label=_("Sharing is currently turned off on this server – pages shared with you are not available."),
                               xalign=0, wrap=True, margin_start=6, margin_end=6)
                lb.add_css_class("caption")
                lb.add_css_class("dim-label")
                self.box.append(Gtk.ListBoxRow(activatable=False, selectable=False, child=lb))
            for p in roots:
                self._shared_tree(p, 0)
        for host in store.fed_unreachable:                       # Foederation: Server gerade nicht erreichbar (kein Fehler)
            lb = Gtk.Label(label=_("{host} can't be reached right now").format(host=host), xalign=0, wrap=True, margin_start=6,
                           margin_end=6)
            lb.add_css_class("caption")
            lb.add_css_class("dim-label")
            self.box.append(Gtk.ListBoxRow(activatable=False, selectable=False, child=lb))
        if store.archived:
            ic = Gtk.Image.new_from_icon_name("pan-down-symbolic" if self.show_archive else "pan-end-symbolic")
            ar = SidebarRow(w, "📦 " + _("Archive ({count})").format(count=len(store.archived)), ic, action=self._toggle_archive)
            ar.add_css_class("dim-label")
            ar.set_margin_top(8)
            set_expanded_state(ar, self.show_archive)
            self.box.append(ar)
            if self.show_archive:
                for p in store.archived:
                    self._page_row(p, 1, flat=True)
        tr = SidebarRow(w, _("Trash"), Gtk.Image.new_from_icon_name("user-trash-symbolic"), action=w.trash_dialog)
        tr.add_css_class("dim-label")
        tr.set_margin_top(8)
        self.box.append(tr)
        w.mark_active()
        if refocus is not None and refocus in self.rows and isinstance(self.rows[refocus], Gtk.Widget):
            self.rows[refocus].grab_focus()

    def _toggle_archive(self):
        self.show_archive = not self.show_archive
        self.fill(force=True)

    def _tree(self, parent, depth):
        for p in store.children(parent):
            kids = store.has_children(p["id"])
            self._page_row(p, depth, kids=kids)
            if kids and p["id"] in self.expanded:
                self._tree(p["id"], depth + 1)

    def _shared_tree(self, p, depth):
        """Mit mir geteilte Seite (oberste mit dem Namen des Eigentuemers klein darunter) und ihre Unterseiten"""
        kids = store.shared_children(p["id"])
        owner = (p.get("owner") or {}).get("name") if p.get("root") else None
        self._page_row(p, depth, kids=bool(kids), snippet=owner)
        if kids and p["id"] in self.expanded:
            for k in kids:
                self._shared_tree(k, depth + 1)

    def _page_row(self, p, depth, kids=False, flat=False, snippet=None):
        exp = None
        on = p["id"] in self.expanded
        if kids and not flat:
            exp = Gtk.Button(icon_name="pan-down-symbolic" if on else "pan-end-symbolic", valign=Gtk.Align.CENTER,
                             tooltip_text=_("Collapse") if on else _("Expand"))
            exp.add_css_class("flat")
            exp.add_css_class("hk-tiny")
            exp.set_focusable(False)                           # Tastatur: Links/Rechts auf der Zeile
            exp.connect("clicked", lambda *_, i=p["id"]: self.toggle(i))
        ic = images.page_icon(p.get("icon"), size=20, fallback="🗃️" if is_db(p) else "📄")
        r = SidebarRow(self.win, label(p), ic, key=("page", p["id"]), depth=depth, page=p, expander=exp, snippet=snippet,
                       kids=kids and not flat, expanded=on)
        self.box.append(r)
        self.rows.setdefault(("page", p["id"]), r)

    def toggle(self, pid):
        if pid in self.expanded:
            self.expanded.discard(pid)
        else:
            self.expanded.add(pid)
        settings.set("expanded", sorted(self.expanded))
        self.fill(force=True)
        r = self.rows.get(("page", pid))
        if isinstance(r, Gtk.Widget):
            r.grab_focus()

    def focus_shared(self):
        """Abschnitt "Mit mir geteilt": erste geteilte Seite fokussieren (die Liste rollt dorthin)"""
        for p in store.shared_roots():
            r = self.rows.get(("page", p["id"]))
            if isinstance(r, Gtk.Widget):
                r.grab_focus()
                return True
        return False

    def reveal(self, pid):
        """Pfad zur Seite aufklappen"""
        changed = False
        for p in store.path(pid):
            if p["id"] not in self.expanded and not is_db(p):
                self.expanded.add(p["id"])
                changed = True
        if changed:
            settings.set("expanded", sorted(self.expanded))
            self.fill(force=True)

    def reset(self):
        """anderer Server: aufgeklappte Seiten und Suche zuruecksetzen"""
        self.expanded = set()
        self.query, self.results = "", []
        self.search.set_text("")
        self.fill(force=True)


def accel_label(accel):
    """Tastenkuerzel in der Sprache der App, wie in den Tooltips ("Strg+K", "Alt+←") - Gtk.accelerator_get_label nimmt
    die Sprache von GTK (ohne dessen Kataloge englisch: "Ctrl", "Alt+Left", Testbericht 196)"""
    names = {"slash": "/", "at": "@", "colon": ":", "comma": ",", "backslash": "\\", "question": "?", "Up": "↑", "Down": "↓",
             "Left": "←", "Right": "→", "Return": _("Enter"), "Escape": _("Esc"), "Menu": _("Menu key"), "Tab": _("Tab")}
    mods = []
    rest = accel
    for tag, mod in (("<Control>", _("Ctrl")), ("<Shift>", _("Shift")), ("<Alt>", _("Alt"))):
        if tag in rest:
            mods.append(mod)
            rest = rest.replace(tag, "")
    key = names.get(rest, rest.upper() if len(rest) == 1 else rest)
    return "+".join(mods + [key])


class MainWindow(Adw.ApplicationWindow):
    __gtype_name__ = "HKMainWindow"

    def __init__(self, app):
        super().__init__(application=app, title="Saga")
        self.set_default_size(settings.get("win_w", 1280), settings.get("win_h", 820))
        self.connect("map", lambda *_: GLib.idle_add(self._fit_min_width))
        if settings.get("win_max"):
            self.maximize()
        self.history, self.fwd = [], []
        self.route = None
        self.current = None
        self.peek = None
        self.peek_list = []
        self.verifier = None
        self.pending_server = None          # Anmeldung im Browser laeuft fuer diesen Server (erst nach Erfolg uebernehmen)
        self._close_ok = False
        self._close_waiting = False
        self.toasts = Adw.ToastOverlay()
        self.stack = Gtk.Stack(transition_type=Gtk.StackTransitionType.CROSSFADE)
        self.toasts.set_child(self.stack)
        self.set_content(self.toasts)
        self.stack.add_named(self._login_page(), "login")
        self.stack.add_named(self._main(), "main")
        self._actions()
        store.connect("need-login", lambda *_: self.stack.set_visible_child_name("login"))
        store.connect("logged-in", lambda *_: self.stack.set_visible_child_name("main"))
        store.connect("error", lambda s, m: self.toast(m))
        store.connect("undo", lambda s, pid, t: self._undo_toast(pid, t))
        store.connect("task-deleted", lambda s, tid, t: self._task_undo_toast(tid, t))
        store.connect("server-changed", lambda *_: self._server_changed())
        from .chat import chat
        from .live import live
        from .assistant import ai
        hs = [(live, live.connect("event", lambda o, t, d: self._live_event(t, d))),
              (ai, ai.connect("changed", lambda *_: self.update_header())),          # Persona/Schalter der KI (2.1)
              (chat, chat.connect("link-request", lambda o, ln: self._link_request(ln))),
              (chat, chat.connect("chat-gone", lambda o, cid, how, name: self._chat_gone(how, name))),     # (2.1)
              (store, store.connect("logged-in", lambda *_: self._session_started())),
              (store, store.connect("need-login", lambda *_: self._session_ended()))]
        self.connect("destroy", lambda *_: [o.disconnect(h) for o, h in hs])
        self.connect("notify::is-active", lambda *_: self.is_active() and self._activated())
        self.connect("close-request", self._closing)
        GLib.timeout_add_seconds(60, self._tick)
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", self._key)
        self.add_controller(kc)
        # Touch: Bedienelemente, die sonst erst beim Ueberfahren mit der Maus erscheinen, dauerhaft zeigen
        lg = Gtk.EventControllerLegacy()
        lg.set_propagation_phase(Gtk.PropagationPhase.CAPTURE)
        lg.connect("event", self._input_kind)
        self.add_controller(lg)
        sm = Adw.StyleManager.get_default()                 # Dunkelmodus als Klasse: helle Plakette hinter Dienst-Symbolen
        sm.connect("notify::dark", lambda m, _p: self._dark(m.get_dark()))
        self._dark(sm.get_dark())
        if not client.has_session and not get_device_token():
            self.stack.set_visible_child_name("login")
        else:
            self.stack.set_visible_child_name("main")
        home = store.home_page
        if home and store.visible(home):                    # zuletzt bekannte Startseite (E2: auch geteilte) sofort
            self.show_root(("page", home))
        else:
            self.show_root(("today",))
        self._start_route = self.route                     # E2: Startseite vom Server, solange noch nichts anderes offen ist
        store.refresh(done=self._after_first_refresh)
        bg(images.prune_disk_cache, error=lambda e: None, media=True)      # Bilder-Zwischenspeicher begrenzen
        bg(avatars.prune, error=lambda e: None, media=True)                # alte Profilbilder

    def _after_first_refresh(self):
        home = store.home_page
        if home and store.pages and not store.visible(home):
            store.set_home(None, local_only=True)          # weg bzw. ohne Zugriff: wie keine (Server rechnet selbst so)
        if not store.needs_login:
            self._session_started()

    def _narrow_views(self):
        """sehr schmal an/aus: offene Seite mit schmalen Raendern neu aufbauen (Seite, Datenbank-Leiste)"""
        def go():
            v = self.current
            if isinstance(v, PageView) or hasattr(v, "check_narrow"):    # Seite bzw. Graph (Kopf zweizeilig)
                v.check_narrow()
            if getattr(self, "_hdr_presence", None):
                self.set_presence(self._hdr_presence)
            return False
        GLib.idle_add(go)

    def _fit_min_width(self):
        """Ohne Compositor (X11, Klasse "solid-csd") liegt der Fensterrand innerhalb der Mindestbreite (360 px von
        libadwaita): der Inhalt waere nur ~350 px breit, Dialoge als Bottom-Sheet brauchen aber 360 -> Rand dazugeben"""
        surf = self.get_surface()
        if surf is None or not self.has_css_class("solid-csd"):
            return False
        extra = surf.get_width() - self.get_width()
        req_w, req_h = self.get_size_request()
        if 0 < extra <= 40 and req_w < 360 + extra:
            self.set_size_request(360 + extra, req_h)
        return False

    def _flush_all(self):
        """alle offenen Seiten sofort speichern"""
        if isinstance(self.current, PageView):
            self.current.editor.flush()
        if self.peek is not None:
            self.peek[1].editor.flush()
        for d in list(store.docs):
            if d.dirty and not d.saving:
                d.save()

    def flush_then(self, cb, timeout=15):
        """alles sofort speichern und cb() aufrufen, sobald nichts mehr unterwegs ist (hoechstens timeout Sekunden;
        was dann noch fehlt, bleibt als Entwurf beim bisherigen Server/Konto)"""
        self._flush_all()
        t0 = time.monotonic()

        def check():
            if store.pending_work() and time.monotonic() - t0 < timeout:
                return True
            cb()
            return False
        GLib.timeout_add(100, check)

    def _remember_size(self):
        w, h = self.get_default_size()
        settings.set("win_w", w)
        settings.set("win_h", h)
        settings.set("win_max", self.is_maximized())
        settings.flush()

    def _closing(self, *_args):
        """Schliessen wartet, bis alles gespeichert ist (die App bleibt so lange am Leben: hold/release).
        Klappt das nicht (offline, Konflikt), wird gefragt - die Eingaben liegen dann als Entwurf auf der Platte."""
        if self._close_ok:
            self._remember_size()
            return False
        if self._close_waiting:
            return True
        self._flush_all()
        store.flush_task_deletes()
        if not store.pending_work() and not store.unsaved_docs():
            self._remember_size()
            return False
        self._close_waiting = True
        app = self.get_application()
        app.hold()
        self.toast(_("Saving changes …"))
        t0 = time.monotonic()

        def check():
            if store.pending_work() and time.monotonic() - t0 < 20:
                return True
            app.release()
            self._close_waiting = False
            if store.unsaved_docs() or store.pending_work():
                self._ask_close_unsaved()
            else:
                self._close_ok = True
                self.close()
            return False
        GLib.timeout_add(100, check)
        return True

    def _ask_close_unsaved(self):
        big = [d for d in store.unsaved_docs() if getattr(d, "too_large", False)]
        if big:                                              # 413: "beim naechsten Oeffnen" hilft nicht (BUG-081)
            body = _("“{title}” is too large to save. Your changes are kept on this device, but they can only be saved once "
                     "the page is shorter or split up.").format(title=big[0].title_raw or _("Untitled"))
        else:
            body = _("Some changes have not reached the server yet (no connection or a conflict). "
                     "They are kept on this device and restored the next time you open the page.")
        d = Adw.AlertDialog(heading=_("Not everything could be saved"), body=body)
        d.add_response("stay", _("Keep open"))
        d.add_response("close", _("Close anyway"))
        d.set_response_appearance("close", Adw.ResponseAppearance.DESTRUCTIVE)
        d.set_default_response("stay")
        d.set_close_response("stay")

        def resp(dd, r):
            if r == "close":
                self._close_ok = True
                self.close()
        d.connect("response", resp)
        d.present(self)

    def _input_kind(self, c, ev=None):
        ev = ev or c.get_current_event()
        if ev is None:
            return False
        t = ev.get_event_type()
        if t in (Gdk.EventType.TOUCH_BEGIN, Gdk.EventType.TOUCH_UPDATE):
            if not self.has_css_class("hk-touch"):
                self.add_css_class("hk-touch")
        elif t in (Gdk.EventType.MOTION_NOTIFY, Gdk.EventType.BUTTON_PRESS) and self.has_css_class("hk-touch"):
            dev = ev.get_device()
            src = dev.get_source() if dev is not None else None
            if src in (Gdk.InputSource.MOUSE, Gdk.InputSource.TOUCHPAD, Gdk.InputSource.TRACKPOINT) and not ev.get_pointer_emulated():
                self.remove_css_class("hk-touch")
        return False

    def _dark(self, on):
        (self.add_css_class if on else self.remove_css_class)("hk-dark")

    def _server_changed(self):
        self._session_ended()
        self.history, self.fwd = [], []
        self.close_peek()
        if hasattr(self, "sidebar"):
            self.sidebar.reset()
        self.show_root(("today",))

    def _version_banner(self):
        if store.app_too_old:
            self.old_banner.set_title(_("This app is too old for the server (version {version} or later needed) – please update.").format(version=store.min_app))
        self.old_banner.set_revealed(store.app_too_old)

    def _activated(self):
        if not store.needs_login:
            store.refresh_if_stale(20)                     # Fokuswechsel: nicht jedes Mal alles neu laden
            from .chat import chat
            from .live import live
            from .notifications import notifications
            live.poke()
            if not live.connected:                         # ohne Live-Kanal: Zahlen beim Zurueckkommen neu
                notifications.count()
                chat.load_unread()
            if isinstance(self.current, PageView):
                self.current.window_active()

    def _tick(self):
        if self.is_active() and not store.needs_login:
            from .chat import chat
            from .live import live
            from .notifications import notifications
            # Seitenbaum jede Minute; mit Live-Kanal erst nach dem ersten notes.tree alle 5 min (refresh_due). Die kleinen
            # Listen (Mit mir geteilt, andere Server, Heute, Glocke, Chats) dabei weiter jede Minute.
            if store.refresh_due(live.connected):
                store.refresh()
            else:
                store.refresh_light()
            notifications.count()
            chat.load_unread()
            self._session_catch_up()
            if type(self.current).__name__ == "ChatsScreen":
                chat.revalidate(120)                       # Profilbilder der Gegenueber (kein Live-Ereignis)
        return True

    # --- Aufbau ----------------------------------------------------------------------------
    def _main(self):
        # OverlaySplitView statt NavigationSplitView: nur so laesst sich die Seitenleiste auch im breiten Fenster
        # ausblenden. pin_sidebar: show-sidebar aendert sich nie von selbst, das Ein-/Ausklappen beim Wechsel
        # breit/schmal steuert _collapsed_changed (damit der gemerkte Zustand nicht ueberschrieben wird).
        self.split = Adw.OverlaySplitView(min_sidebar_width=230, max_sidebar_width=380, sidebar_width_fraction=0.22, pin_sidebar=True)
        sb = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        hb.set_title_widget(Adw.WindowTitle(title="Saga"))
        new = flat_button(icon="document-new-symbolic", tooltip=_("New page (Ctrl+N)"), cb=self.new_page)
        hb.pack_start(new)
        # 2.5: mit Vorlagengalerie wird daraus ein Knopf mit Menue (Neue Seite / Aus Vorlage ...)
        nm = Gio.Menu()
        nm.append(_("New page"), "win.new-page")
        nm.append(_("From template …"), "win.gallery")
        self.new_split = Adw.SplitButton(icon_name="document-new-symbolic", tooltip_text=_("New page (Ctrl+N)"), menu_model=nm,
                                         dropdown_tooltip=_("More ways to create"), visible=False)
        self.new_split.add_css_class("flat")
        self.new_split.connect("clicked", lambda *_: self.new_page())
        hb.pack_start(self.new_split)
        self.new_btn = new
        menu = Gtk.MenuButton(icon_name="open-menu-symbolic", tooltip_text=_("Menu"))
        m = Gio.Menu()
        m.append(_("Command palette"), "win.palette")
        m.append(_("Trash"), "win.trash")
        m.append(_("Refresh"), "win.refresh")
        m.append(_("Settings"), "win.settings")
        m.append(_("Keyboard shortcuts"), "win.shortcuts")
        m.append(_("About Saga"), "win.about")
        menu.set_menu_model(m)
        hb.pack_end(menu)
        from .notifications import BellButton
        self.bell = BellButton(self)                        # Benachrichtigungen (1.4): Glocke mit Zahl
        hb.pack_end(self.bell)
        # eigenes Konto (2.1: mit Profilbild) -> Einstellungen, Seite Konto
        self.acct_av = Adw.Avatar(size=24, text="?", show_initials=True)
        self.acct_btn = Gtk.Button(child=self.acct_av, visible=False, valign=Gtk.Align.CENTER)
        self.acct_btn.add_css_class("flat")
        self.acct_btn.add_css_class("hk-acct-btn")
        self.acct_btn.connect("clicked", lambda *_: self.settings_dialog(page="account"))
        hb.pack_end(self.acct_btn)
        sb.add_top_bar(hb)
        self.sidebar = Sidebar(self)
        sb.set_content(self.sidebar)
        self.split.set_sidebar(Adw.NavigationPage(title="Saga", child=sb))
        ct = Adw.ToolbarView()
        self.chb = Adw.HeaderBar()
        # Knopf in der Inhalts-Kopfleiste: bleibt sichtbar, auch wenn die Seitenleiste eingeklappt ist
        self.sb_btn = Gtk.ToggleButton(icon_name="sidebar-show-symbolic", tooltip_text=_("Show/hide sidebar (F9)"))
        self.split.bind_property("show-sidebar", self.sb_btn, "active", GObject.BindingFlags.BIDIRECTIONAL | GObject.BindingFlags.SYNC_CREATE)
        self.chb.pack_start(self.sb_btn)
        self.back_btn = flat_button(icon="go-previous-symbolic", tooltip=_("Back (Alt+←)"), cb=self.go_back)
        self.fwd_btn = flat_button(icon="go-next-symbolic", tooltip=_("Forward (Alt+→)"), cb=self.go_forward)
        self.chb.pack_start(self.back_btn)
        self.chb.pack_start(self.fwd_btn)
        self.wtitle = Adw.WindowTitle(title="")
        self.chb.set_title_widget(self.wtitle)
        self.menu_btn = flat_button(icon="view-more-symbolic", tooltip=_("Page menu"))
        self.menu_btn.connect("clicked", lambda b: hasattr(self.current, "page_menu") and self.current.page_menu(b))
        self.star_btn = flat_button(icon="non-starred-symbolic", tooltip=_("Add to favorites"), cb=lambda: isinstance(self.current, PageView) and self.current.toggle_fav())
        self.chb.pack_end(self.menu_btn)
        self.chb.pack_end(self.star_btn)
        # Hinweis "Oeffentlich" (Seite veroeffentlicht bzw. ueber eine Elternseite oeffentlich) - oeffnet den Dialog.
        # Eigenes Symbol saga-globe-symbolic (hlidskjalf/icons), Beschriftung nur "Oeffentlich", "ueber ..." im Tooltip
        self.pub_content = Adw.ButtonContent(icon_name=globe_icon(), label=_("Public"))
        if hasattr(self.pub_content, "set_can_shrink"):
            self.pub_content.set_can_shrink(True)
        self.pub_btn = Gtk.Button(child=self.pub_content, visible=False)
        self.pub_btn.add_css_class("flat")
        self.pub_btn.connect("clicked", lambda *_: isinstance(self.current, PageView) and self.publish_dialog(self.current.id))
        self.chb.pack_end(self.pub_btn)
        # Teilen (nur Eigentuemer) und Kommentare ("💬 3": offene Unterhaltungen, Punkt bei ungelesenen)
        from .sharing import comment_icon, people_icon
        self.share_btn = Gtk.Button(icon_name=people_icon(), visible=False)
        self.share_btn.add_css_class("flat")
        self.share_btn.connect("clicked", lambda *_: isinstance(self.current, PageView) and self.share_dialog(self.current.id))
        self.chb.pack_end(self.share_btn)
        self.cm_content = Adw.ButtonContent(icon_name=comment_icon(), label="")
        self.cm_btn = Gtk.Button(child=self.cm_content, visible=False)
        self.cm_btn.add_css_class("flat")
        self.cm_btn.connect("clicked", lambda *_: isinstance(self.current, PageView) and self.current.toggle_comments())
        self.chb.pack_end(self.cm_btn)
        # KI-Assistent (1.6): "✦ Assistent" (nur wenn der Server ihn fuer mich anbietet); Server ab 2.1: Profilbild und
        # "Frage Marin" (Persona)
        from .assistant import assistant_icon
        self.ai_content = Gtk.Box(spacing=6)
        self.ai_av = Gtk.Image.new_from_icon_name(assistant_icon())
        self.ai_content.append(self.ai_av)
        self.ai_label = Gtk.Label(label=_("Assistant"), ellipsize=Pango.EllipsizeMode.END)
        self.ai_content.append(self.ai_label)
        self._ai_sig = None
        self.ai_btn = Gtk.Button(child=self.ai_content, visible=False, tooltip_text=_("AI assistant"))
        self.ai_btn.add_css_class("flat")
        self.ai_btn.connect("clicked", lambda *_: self.assistant())
        self.chb.pack_end(self.ai_btn)
        # Anrufen (2.1, Direkt-Chat): oeffnet den Chat im Browser, dort "Anruf"/"Videoanruf" (Linux v1 ohne eigenes WebRTC)
        self.call_btn = flat_button(icon="call-start-symbolic", tooltip=_("Call – opens the chat in your browser"),
                                    cb=lambda: self.call_current())
        self.call_btn.set_visible(False)
        self.chb.pack_end(self.call_btn)
        # Anwesenheit (1.4): wer ist gerade auf dieser Seite (Anfangsbuchstaben)
        self.presence_box = Gtk.Box(spacing=0, visible=False, valign=Gtk.Align.CENTER)
        self.presence_box.add_css_class("hk-presence")
        self.chb.pack_end(self.presence_box)
        ct.add_top_bar(self.chb)
        self.old_banner = Adw.Banner(title="", revealed=False)      # Server ist neuer, als diese App kann
        ct.add_top_bar(self.old_banner)
        from .calls import CallBar
        self.callbar = CallBar(self)                                # eingehender Anruf (2.1): Ablehnen / Annehmen
        ct.add_top_bar(self.callbar)
        store.connect("version-changed", lambda *_: self._version_banner())
        store.connect("version-changed", lambda *_: self.sidebar.fill(force=False))     # 2.5: Eintrag "Graph" je Server
        store.connect("version-changed", lambda *_: self._gallery_probe())               # 2.5: Vorlagengalerie je Server
        self.overlay = Gtk.Overlay()
        self.slot = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, vexpand=True, hexpand=True)
        self.overlay.set_child(self.slot)
        ct.set_content(self.overlay)
        self.split.set_content(Adw.NavigationPage(title=_("Page"), child=ct))
        # unter ~1000sp wird die Seitenleiste zum Overlay (sonst bleibt fuer Seiten und Dienste zu wenig Breite)
        bp = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 1000sp"))
        bp.add_setter(self.split, "collapsed", True)
        self.add_breakpoint(bp)
        # sehr schmal (Telefon-Breite): Nebensachen aus der Kopfleiste nehmen (Vor, Favorit, Teilen, Veroeffentlicht, Assistent;
        # Anwesenheit hoechstens 2 Kreise) - alles bleibt ueber das Seitenmenue bzw. die Befehlspalette erreichbar
        self.very_narrow = False
        bp2 = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 560sp"))
        bp2.add_setter(self.split, "collapsed", True)        # es gilt immer nur ein Breakpoint: Einklappen wiederholen
        bp2.connect("apply", lambda *_: (setattr(self, "very_narrow", True), self.update_header(), self.callbar.set_compact(True),
                                         self._narrow_views()))
        bp2.connect("unapply", lambda *_: (setattr(self, "very_narrow", False), self.update_header(), self.callbar.set_compact(False),
                                           self._narrow_views()))
        self.add_breakpoint(bp2)
        self.split.set_show_sidebar(bool(settings.get("sidebar_shown", True)))
        self.split.connect("notify::show-sidebar", self._sidebar_changed)
        self.split.connect("notify::collapsed", self._collapsed_changed)
        return self.split

    def _sidebar_changed(self, *_):
        # nur im breiten Fenster merken - im schmalen liegt die Seitenleiste als Overlay ueber dem Inhalt
        if not self.split.get_collapsed():
            settings.set("sidebar_shown", self.split.get_show_sidebar())

    def _collapsed_changed(self, *_):
        if self.split.get_collapsed():
            self.split.set_show_sidebar(False)                 # schmal: Inhalt zeigen, Seitenleiste per Knopf/F9
        else:
            self.split.set_show_sidebar(bool(settings.get("sidebar_shown", True)))

    def toggle_sidebar(self):
        self.split.set_show_sidebar(not self.split.get_show_sidebar())

    def search_sidebar(self):
        self.split.set_show_sidebar(True)
        self.sidebar.search.grab_focus()

    def _login_page(self):
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()                                   # Schliessen, Verschieben, Menue auch vor der Anmeldung
        hb.set_title_widget(Adw.WindowTitle(title=""))
        menu = Gtk.MenuButton(icon_name="open-menu-symbolic", tooltip_text=_("Menu"))
        m = Gio.Menu()
        m.append(_("Keyboard shortcuts"), "win.shortcuts")
        m.append(_("About Saga"), "win.about")
        m.append(_("Quit"), "app.quit")
        menu.set_menu_model(m)
        hb.pack_end(menu)
        tv.add_top_bar(hb)
        sp = Adw.StatusPage(title="Saga", description=_("Sign in with a passkey, security key or password.\nSign-in opens in your browser."))
        sp.set_icon_name(APP_ID)
        self.login_status = sp
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, halign=Gtk.Align.CENTER)
        lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, width_request=320)
        lb.add_css_class("boxed-list")
        self.login_server = Adw.EntryRow(title=_("Server address"), text=settings.server)
        placeholder(self.login_server, "https://saga.example.org")
        self.login_server.connect("entry-activated", lambda *_: self.start_login())
        lb.append(self.login_server)
        box.append(lb)
        b = Gtk.Button(label=_("Sign in"))
        b.add_css_class("suggested-action")
        b.add_css_class("pill")
        b.connect("clicked", lambda *_: self.start_login())
        box.append(b)
        self.login_msg = Gtk.Label(wrap=True, justify=Gtk.Justification.CENTER)
        self.login_msg.add_css_class("dim-label")
        if not settings.server:
            self.login_msg.set_label(_("Enter the server address or sign in with a pairing link"))
        box.append(self.login_msg)
        tfa = Gtk.Label(label=_("With two-factor sign-in, the browser then asks for your passkey or a code from your authenticator app."),
                        wrap=True, justify=Gtk.Justification.CENTER, max_width_chars=48)
        tfa.add_css_class("dim-label")
        tfa.add_css_class("caption")
        box.append(tfa)
        px = Gtk.Expander(label_widget=Gtk.Label(label=_("Sign in with a pairing link (QR code from the web)"), wrap=True, xalign=0))
        pb = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=6)
        pb.append(Gtk.Label(label=_("In the browser: 👤 → Settings → “Apps & devices” → “Sign in with QR code”. "
                                    "Paste the link below the QR code (…/app/pair?c=…) here:"), wrap=True, xalign=0))
        pe = Gtk.Entry(placeholder_text="https://…/app/pair?c=…")
        pe.connect("activate", lambda e: self.pair_link(e.get_text().strip()))
        pb.append(pe)
        px.set_child(pb)
        box.append(px)
        ex = Gtk.Expander(label_widget=Gtk.Label(label=_("Browser doesn't return to the app?"), wrap=True, xalign=0))
        eb = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=6)
        eb.append(Gtk.Label(label=_("Paste the address from the browser's error message (hlidskjalf://auth?code=…) here:"), wrap=True, xalign=0))
        en = Gtk.Entry(placeholder_text="hlidskjalf://auth?code=…")
        en.connect("activate", lambda e: self.finish_login(e.get_text().strip()))
        eb.append(en)
        ex.set_child(eb)
        box.append(ex)
        sp.set_child(box)
        tv.set_content(sp)
        return tv

    # --- Anmeldung -------------------------------------------------------------------------
    # Ein Server wird erst uebernommen, wenn die Anmeldung dort geklappt hat. Erst dann wechseln Sitzung, Zwischenspeicher
    # und Schluessel; Geraete- und App-Schluessel sind an ihren Server gebunden und gehen nie an einen anderen.
    def _login_note(self, text):
        self.login_msg.set_label(text)
        if self.stack.get_visible_child_name() != "login" and text:
            self.toast(text)

    def _entered_server(self):
        s = self.login_server.get_text().strip().rstrip("/")
        if s and "://" not in s:
            s = "https://" + s
        if not config.allowed_server(s) or len(s) < 12:
            self._login_note(_("Please enter an https address, e.g. https://saga.example.org"))
            return None
        s = config.origin(s)
        self.login_server.set_text(s)
        return s

    @staticmethod
    def _host(srv):
        """Host fuer Rueckfragen - mit Port, wenn einer angegeben ist (sonst "localhost" und "localhost" bei zwei Servern)"""
        if not srv:
            return ""
        u = GLib.Uri.parse(srv, GLib.UriFlags.NONE)
        return u.get_host() + (f":{u.get_port()}" if u.get_port() > 0 else "")

    def pair_link(self, text, external=False):
        """https://server/app/pair?c=CODE  oder  <app>://pair?u=https://server&c=CODE.
        external: kam als Link von aussen (Browser, andere App) - dann immer erst fragen."""
        try:
            u = GLib.Uri.parse(text, GLib.UriFlags.NONE)
            q = GLib.Uri.parse_params(u.get_query() or "", -1, "&", GLib.UriParamsFlags.NONE)
        except GLib.Error:
            u, q = None, {}
        code = q.get("c") or ""
        if u and u.get_scheme() in ("https", "http") and u.get_path() == "/app/pair":
            port = u.get_port()
            srv = f"{u.get_scheme()}://{u.get_host()}" + (f":{port}" if port and port > 0 else "")
        elif u and u.get_host() == "pair":
            srv = (q.get("u") or "").rstrip("/")
        else:
            srv = ""
        if len(code) < 20 or not config.origin(srv):
            self._login_note(_("This is not a valid pairing link."))
            return
        if not config.allowed_server(srv):
            self._login_note(_("Pairing links must use https."))
            return
        srv = config.origin(srv)
        cur = config.origin(settings.server)
        signed_in = client.has_session or bool(get_device_token())
        if external or (cur and srv != cur) or signed_in:  # angemeldet: auch am selben Server fragen (anderes Konto?)
            self._confirm_pair(srv, code, switching=bool(cur) and signed_in, cur=cur)
        else:
            self._do_pair(srv, code)

    def _confirm_pair(self, srv, code, switching, cur):
        """Kopplung bestaetigen - Texte wie iOS/Android (Glossar "Einheitliche Dialoge")"""
        host = self._host(srv)
        body = _("Saga will sign in to {host}.").format(host=host) + "\n\n" + \
            _("Only continue if you created this QR code or link yourself in Saga (Settings → Apps & devices).")
        if switching:
            body += "\n\n" + _("You're currently signed in to {alt} and will be signed out there.").format(alt=self._host(cur))
        d = Adw.AlertDialog(heading=_("Pair with this server?"), body=body)
        d.add_response("cancel", _("Cancel"))
        d.add_response("pair", _("Pair"))
        d.set_response_appearance("pair", Adw.ResponseAppearance.DESTRUCTIVE if switching else Adw.ResponseAppearance.SUGGESTED)
        d.set_default_response("cancel")
        d.set_close_response("cancel")
        d.connect("response", lambda dd, r: r == "pair" and self._do_pair(srv, code))
        self.present()
        d.present(self)

    def _do_pair(self, srv, code):
        self._login_note(_("Signing in …"))
        self._flush_all()
        want = not config.app_key(srv).startswith("hk_")
        bg(client.pair, code, srv, want, done=lambda r: self._signed_in(srv, *r), error=lambda e: self._login_note(str(e)))

    def _signed_in(self, srv, j, jar, waited=False):
        """Anmeldung hat geklappt: erst jetzt Server wechseln (alter Zwischenspeicher, Zuletzt, Startseite weg; das Geraet
        wird beim alten Server abgemeldet). Vorher noch laufende Speicherungen zum alten Server abwarten."""
        if not waited and config.origin(srv) != config.origin(settings.server) and store.pending_work():
            self.flush_then(lambda: self._signed_in(srv, j, jar, True))
            return
        switched = store.switch_server(srv)
        if not switched:
            store.new_session(srv)                       # gleicher Server, evtl. anderes Konto: Konto neu ermitteln
            # Inhalte des bisherigen Kontos nicht stehen lassen (offene Seite, Zwischenspeicher, Zuletzt) - BUG-031.
            # Ungespeicherte Eingaben bleiben als Entwurf beim bisherigen Konto (new_session).
            self._flush_all()
            store.forget_account_data()
        client.adopt_login(srv, j, jar)
        self.login_server.set_text(srv)
        self.login_msg.set_label("")
        store.logged_in()
        self.close_peek()
        self.show_root(("today",))                       # auch am selben Server: keine Seite des vorigen Kontos offen
        self.present()
        self.toast(_("Signed in as {name} – stays signed in on “{device}”").format(name=j.get("name") or "", device=device_name()))

    def start_login(self, server=None):
        if server:                                        # erneut anmelden (z. B. "Neu beginnen" bei Chats)
            self.login_server.set_text(server)
        srv = self._entered_server()
        if not srv:
            return
        self.pending_server = srv
        self.verifier, ch = pkce()
        self._login_note(_("Please sign in in your browser …"))
        self.open_uri(f"{srv}/app/login?challenge={ch}")

    def finish_login(self, uri):
        try:
            u = GLib.Uri.parse(uri, GLib.UriFlags.NONE)
            params = GLib.Uri.parse_params(u.get_query() or "", -1, "&", GLib.UriParamsFlags.NONE)
            code = params.get("code")
            if params.get("error") == "cancelled":          # im Browser abgebrochen: still nichts tun
                self.verifier = None
                self._login_note("")
                return
        except GLib.Error:
            code = None
        srv = self.pending_server
        if not code or not self.verifier or not srv:
            self._login_note(_("Please start signing in here in the app."))
            return
        self._login_note(_("Signing in …"))
        v, self.verifier = self.verifier, None
        want = not config.app_key(srv).startswith("hk_")
        bg(client.exchange, code, v, srv, want, done=lambda r: self._signed_in(srv, *r), error=lambda e: self._login_note(str(e)))

    # --- Navigation -------------------------------------------------------------------------
    def _make(self, route):
        k = route[0]
        if k == "page":
            v = PageView(self, route[1])
            v.header_changed = self.update_header
            return v
        if k == "tasks":
            from .agenda import tasks_screen
            return tasks_screen(self)
        if k == "events":
            from .agenda import events_screen
            return events_screen(self)
        if k == "services":
            from .dialogs import services_screen
            return services_screen(self)
        if k == "graph":                                        # 2.5: ("graph", "all"|"local", Seite)
            from .graphview import GraphScreen
            return GraphScreen(self, route[1] if len(route) > 1 else "all",
                               route[2] if len(route) > 2 else getattr(self, "last_page_id", None))
        if k in ("chats", "chat"):
            from .chatview import ChatsScreen
            return ChatsScreen(self, route[1] if k == "chat" and len(route) > 1 else None)
        from .agenda import today_screen
        return today_screen(self)

    def _show(self, route):
        if isinstance(self.current, PageView):
            if route == ("page", self.current.id):
                self.current.keep_fresh = True             # dieselbe Seite neu aufbauen: nicht verwerfen (E4)
            self.current.leave()
        self.close_peek()
        if route[0] == "page":
            self.last_page_id = route[1]                        # Graph: zuletzt offene Seite markieren / lokal zeigen
        self.route = route
        while (c := self.slot.get_first_child()):
            self.slot.remove(c)
        self.current = self._make(route)
        self.slot.append(self.current)
        self.update_header()
        self.mark_active()
        if self.split.get_collapsed():
            self.split.set_show_sidebar(False)                 # schmal: nach der Auswahl das Overlay schliessen
        if route[0] == "page":
            self.sidebar.reveal(route[1])

    def show_root(self, route):
        self.history, self.fwd = [route], []
        self._show(route)

    def show_page(self, pid, root=False):
        if root:
            self.show_root(("page", pid))
            return
        r = ("page", pid)
        if self.route == r:
            self.close_peek()
            cur = getattr(self, "current", None)
            doc = getattr(cur, "doc", None)
            if doc is not None and doc.loaded and not getattr(doc, "fetched", True):   # Laden war fehlgeschlagen -> neu versuchen
                cur.spinner.set_visible(True)
                doc.load(force=True)
            return
        self.history.append(r)
        self.fwd = []
        self._show(r)

    def show_route(self, route):
        """Bereich zeigen und im Verlauf merken (Zurueck fuehrt dorthin zurueck)"""
        if self.route == route:
            return
        if route[0] == "chat" and self.route and self.route[0] in ("chats", "chat"):
            if self.history and self.history[-1][0] == "chat":   # Chat zu Chat: ersetzen statt stapeln
                self.history[-1] = route
                self.fwd = []
                self._show(route)
                return
        self.history.append(route)
        self.fwd = []
        self._show(route)

    def show_shared(self):
        """Knopf "Zu „Mit mir geteilt“" (Ansicht "Kein Zugriff"): Seitenleiste zeigen, Liste neu laden, dort die mit mir
        geteilten Seiten fokussieren"""
        self.split.set_show_sidebar(True)
        store.refresh()
        GLib.idle_add(lambda: (self.sidebar.focus_shared(), False)[1])

    def go_back(self):
        if self.peek:
            self.close_peek()
            return
        if len(self.history) > 1:
            self.fwd.append(self.history.pop())
            self._show(self.history[-1])

    def go_forward(self):
        if self.fwd:
            r = self.fwd.pop()
            self.history.append(r)
            self._show(r)

    def go_back_or_home(self):
        """nach "In den Papierkorb": zurueck - aber nie auf eine Seite, die mit in den Papierkorb ging (Unterseite)"""
        gone = lambda r: bool(r) and r[0] == "page" and r[1] in store.trashed_ids        # noqa: E731
        self.fwd = [r for r in self.fwd if not gone(r)]
        while len(self.history) > 1 and gone(self.history[-2]):
            self.history.pop(-2)
        if len(self.history) > 1:
            self.go_back()
        else:
            self.show_root(("today",))

    def mark_active(self):
        rows = getattr(getattr(self, "sidebar", None), "rows", {})
        cur = ("chats",) if self.route and self.route[0] == "chat" else ("graph",) if self.route and self.route[0] == "graph" \
            else self.route
        for k, r in rows.items():
            if isinstance(r, Gtk.Widget):
                (r.add_css_class if k == cur else r.remove_css_class)("hk-active")

    def update_header(self):
        self._update_header()
        n = getattr(self, "very_narrow", False)
        from .assistant import ask_label
        self.ai_label.set_label("" if n else ask_label())
        self.ai_label.set_visible(not n)
        if n:
            for w in (self.fwd_btn, self.star_btn, self.share_btn, self.pub_btn, self.ai_btn):   # Assistent: im Seitenmenue
                w.set_visible(False)
            self.cm_content.set_label("")                    # nur das Symbol (Zahl im Tooltip/Screenreader): sonst ist
                                                             # die Kopfleiste bei 360 px zu breit (BUG-082)
        else:
            self.fwd_btn.set_visible(True)

    def _update_header(self):
        self.back_btn.set_sensitive(len(self.history) > 1)
        self.fwd_btn.set_sensitive(bool(self.fwd))
        is_page = isinstance(self.current, PageView)
        self.menu_btn.set_visible(is_page)
        self.star_btn.set_visible(is_page)
        self.call_btn.set_visible(False)
        if is_page:
            from .comments import count_tooltip
            from .sharing import allowed, can_comment, header_button
            d = self.current.doc
            gone = self.current.no_access                      # "Kein Zugriff": Kopfzeile ohne Titel/Symbol der Seite
            p = d.page if d.loaded and d.fetched and not gone else {}
            self.menu_btn.set_visible(not gone)
            if gone:
                self.wtitle.set_title("")
                self.wtitle.set_subtitle("")
            else:
                self.wtitle.set_title(d.title if d.loaded else label(store.meta(d.id)))
                self.wtitle.set_subtitle(store.path_text(d.id))
            fav = bool(d.page.get("pinned") if d.loaded else (store.by_id.get(d.id) or {}).get("pinned"))
            self.star_btn.set_icon_name("starred-symbolic" if fav else "non-starred-symbolic")
            self.star_btn.set_tooltip_text(_("Remove from favorites") if fav else _("Add to favorites"))
            self.star_btn.set_visible(allowed(p or store.meta(d.id), "favorite") and not store.is_foreign(d.id) and not gone)
            self.set_title(_("No access") + " – Saga" if gone else f"{d.title} – Saga")
            hs = header_state(d.page) if d.loaded and d.fetched and not gone else None
            self.pub_btn.set_visible(hs is not None)
            if hs:
                self.pub_content.set_label(hs["label"])
                self.pub_btn.set_tooltip_text(hs["tooltip"])
            sh = header_button(p) if store.share_api is not False else None
            self.share_btn.set_visible(sh is not None)
            if sh:
                self.share_btn.set_tooltip_text(sh["tooltip"])
                self.share_btn.update_property([Gtk.AccessibleProperty.LABEL], [sh["tooltip"]])
                (self.share_btn.add_css_class if sh["on"] else self.share_btn.remove_css_class)("hk-shared-on")
            from .assistant import ai
            from .sharing import is_remote
            self.ai_btn.set_visible(ai.enabled and bool(p) and not is_remote(p))
            self._ai_button()
            n, un = int(p.get("comments_open") or 0), int(p.get("comments_unread") or 0)
            # andere Instanz: Kommentar-Knopf immer, wenn die Rolle Kommentieren erlaubt (der Hinweis verspricht es)
            can_cm = is_remote(p) and can_comment(p)
            self.cm_btn.set_visible("role" in p and (n > 0 or self.current.comments_open or can_cm))
            self.cm_content.set_label(str(n) if n else "")
            tt = count_tooltip(n, un)
            self.cm_btn.set_tooltip_text(tt)
            self.cm_btn.update_property([Gtk.AccessibleProperty.LABEL], [tt])
            (self.cm_btn.add_css_class if un else self.cm_btn.remove_css_class)("hk-unread")
        else:
            self.pub_btn.set_visible(False)
            self.share_btn.set_visible(False)
            self.cm_btn.set_visible(False)
            self.ai_btn.set_visible(False)
            self.set_presence([])
            if hasattr(self.current, "header"):                 # Chats: Name des Chats, "tippt …" bzw. Art
                t, sub = self.current.header()
                self.wtitle.set_title(t)
                self.wtitle.set_subtitle(sub)
                self.menu_btn.set_visible(self.current.has_menu())
                self.call_btn.set_visible(bool(getattr(self.current, "call_chat", lambda: None)()))
                self.set_title(f"{t} – Saga")
                return
            t = {"today": _("Today"), "tasks": _("Tasks"), "events": _("Events"), "services": _("Services")}.get((self.route or ("today",))[0], "")
            self.wtitle.set_title(t)
            self.wtitle.set_subtitle("")
            self.set_title("Saga")

    # --- Live-Kanal, Benachrichtigungen, Chats, Assistent (Server 1.4 - 2.0) ------------------------------------
    def _session_started(self):
        """angemeldet (bzw. Start mit Sitzung): Live-Kanal, Glocke, Chats, Assistent"""
        from .assistant import ai
        from .chat import chat
        from .live import live
        from .notifications import notifications
        from .calls import calls
        self._session_t = time.monotonic()
        live.start()
        notifications.load()
        chat.refresh()
        ai.refresh(lambda: self.update_header())
        calls.load_config(lambda on: self.update_header())
        calls.check_active()                               # klingelt es gerade schon (App eben gestartet)?
        self.load_account()

    def _session_catch_up(self):
        """Start ohne Netz: was beim Sitzungsbeginn nicht geladen werden konnte (Assistent, Anrufe, Konto, Glocke, Chats),
        nachholen, sobald der Server wieder antwortet (Live-Kanal "hello", Takt). Bereits Geladenes bleibt unberuehrt."""
        if store.needs_login or not (client.has_session or get_device_token()):
            return
        wait = getattr(self, "_session_t", 0) + 10 - time.monotonic()
        if wait > 0:                                       # Anfragen vom Sitzungsbeginn laufen evtl. noch: danach pruefen
            if not getattr(self, "_catch_up_soon", 0):
                def later():
                    self._catch_up_soon = 0
                    self._session_catch_up()
                    return False
                self._catch_up_soon = GLib.timeout_add(int(wait * 1000) + 50, later)
            return
        from .assistant import ai
        from .calls import calls
        from .chat import chat
        from .notifications import notifications
        if not ai.known:
            ai.refresh(lambda: self.update_header())
        if calls.config is None:
            calls.load_config(lambda on: self.update_header())
        if not getattr(self, "me", None):
            self.load_account()
        if not notifications.loaded and notifications.supported is not False:
            notifications.load()
        if chat.status == "unknown":
            chat.refresh()

    def call_current(self):
        """Direkt-Chat: Anrufen im Browser (Web-Oberflaeche mit WebRTC)"""
        cid = getattr(self.current, "call_chat", lambda: None)()
        if not cid:
            return
        from .calls import chat_url, open_in_browser
        open_in_browser(chat_url(cid), self)
        self.toast(_("The chat opens in your browser – start the call there."))

    def _session_ended(self):
        from .assistant import ai
        from .calls import calls
        from .chat import chat
        from .live import live
        from .notifications import notifications
        live.reset()
        notifications.reset()
        notifications.emit("changed")
        chat.reset()
        ai.reset()
        calls.reset()
        avatars.reset()
        self.me = {}
        self._account_button()
        self.set_presence([])

    # --- eigenes Konto (Name, Profilbild) ---------------------------------------------------------------------
    def load_account(self):
        srv = settings.server

        def fin(j):
            if srv == settings.server and isinstance(j, dict):
                self.me = j
                self._account_button()
                known, start = store.account_loaded(j)
                if known:
                    self._apply_start(start)
        bg(client.get, "api/account", done=fin, error=lambda e: None)

    def _apply_start(self, start):
        """E2: Startseite des Kontos (Server 2.5) beim Kaltstart oeffnen - nur, solange die Startansicht noch offen ist"""
        first = getattr(self, "_start_route", None)
        self._start_route = None                           # nur einmal je Start
        if first is None:
            return
        still = self.route == first and len(self.history) == 1 and not self.peek
        to = start_switch(start, first, still, store.visible)
        if to is not None:
            self.show_root(to)

    def account_name(self):
        m = getattr(self, "me", None) or {}
        return m.get("name") or m.get("username") or ""

    def account_changed(self):
        """eigenes Profilbild geaendert (Einstellungen): Konto neu laden, Kopfleiste und offene Ansichten neu zeichnen"""
        self.load_account()

    def _account_button(self):
        b = getattr(self, "acct_btn", None)
        if b is None:
            return
        m = getattr(self, "me", None) or {}
        name = self.account_name()
        b.set_visible(bool(name))
        self.acct_av.set_text(avatars.initials_text(name or "?"))
        avatars.attach(self.acct_av, m.get("avatar"), 24)
        tip = _("Account: {name}").format(name=name) if name else _("Account")
        b.set_tooltip_text(tip)
        b.update_property([Gtk.AccessibleProperty.LABEL], [tip])

    def _views(self):
        out = [self.current] if isinstance(self.current, PageView) else []
        if self.peek:
            out.append(self.peek[1])
        return out

    def _live_event(self, typ, d):
        from .chat import chat
        from .notifications import notifications, show_desktop
        if typ == "notification":
            new = notifications.on_event(d)
            if new is not None and not new.get("read") and not self.is_active():
                show_desktop(self.get_application(), new)
            return
        if typ.startswith("chat."):
            chat.on_live(typ, d)
            return
        if typ == "avatar.changed":                        # Profilbild einer Person (Server 2.1): Chats, eigenes Konto
            chat.on_live(typ, d)
            if isinstance(d, dict) and d.get("user") and d.get("user") == (getattr(self, "me", None) or {}).get("username"):
                self.load_account()
            return
        if typ in ("ai.persona.changed", "reset"):            # KI (2.1): Name/Bild/Schalter geaendert -> neu abfragen
            from .assistant import ai
            ai.on_live(typ, d)
            if typ != "reset":
                return
        if typ.startswith("call.") or typ in ("hello", "reset"):    # Anrufe: klingeln, anderswo angenommen/beendet
            from .calls import calls
            calls.on_live(typ, d)
            if typ.startswith("call."):
                return
        if typ == "hello":
            self._session_catch_up()                       # Start ohne Netz: Fehlendes jetzt laden
            if not store.needs_login:                      # wieder verbunden (Netzwechsel, Aufwachen): Verpasstes holen
                store.refresh_if_stale(store.POLL_S)
            return
        if typ == "reset":
            store.drop_tree_cursor()                       # Verpasstes nicht nachholbar: Seitenbaum ganz laden
            store.refresh()
            notifications.load()
            chat.on_live(typ, d)
            for v in self._views():
                v.live_reset()
            return
        if typ == "shared.changed":
            store.refresh()
            return
        if typ == "notes.tree":                            # (2.2) Seiten im Baum anderswo geaendert: gezielt nachladen
            store.on_tree_event(d)
            return
        nid = d.get("id") if typ == "note.changed" else d.get("note_id")
        if typ == "note.changed" and d.get("row") is not None:
            store.changed_data()                           # Zeile einer Datenbank (BUG-049): Tabellen und offener Eintrag (N9)
        if typ == "comments.changed":
            counts = d.get("counts") if isinstance(d.get("counts"), dict) else {}
            store.set_unread(nid, int(counts.get("unread") or 0) > 0)
        for v in self._views():
            if v.id != nid:
                continue
            if typ == "note.changed":
                v.live_changed(d)
            elif typ == "comments.changed":
                v.live_comments(d.get("counts") or {})
            elif typ == "presence":
                v.live_presence(d.get("users") or [])

    def set_presence(self, users):
        """Kopfleiste: Anfangsbuchstaben der anderen Personen auf dieser Seite (bearbeitet gerade: farbiger Ring)"""
        box = self.presence_box
        self._hdr_presence = users
        while (c := box.get_first_child()):
            box.remove(c)
        others = [u for u in users or [] if isinstance(u, dict) and not u.get("me")]
        box.set_visible(bool(others))
        if not others:
            return
        tips = []
        mx = 1 if getattr(self, "very_narrow", False) else 4         # sehr schmal: ein Kreis, Rest als +n (360 px)
        for u in others[:mx]:
            name = u.get("name") or u.get("username") or "?"
            tip = (_("{name} is editing") if u.get("editing") else _("{name} is also here")).format(name=name)
            tips.append(tip)
            a = Adw.Avatar(size=24, text=avatars.initials_text(name), show_initials=True, tooltip_text=tip)
            avatars.attach(a, u.get("avatar"), 24)                  # Profilbild (2.1), sonst Initialen
            a.add_css_class("hk-pres-av")
            if u.get("editing"):
                a.add_css_class("hk-pres-editing")
            box.append(a)
        if len(others) > mx:
            more = Gtk.Label(label=f"+{len(others) - mx}")
            more.add_css_class("caption")
            box.append(more)
            tips += [(_("{name} is editing") if u.get("editing") else _("{name} is also here")).format(name=u.get("name") or "")
                     for u in others[mx:]]
        acc = _("On this page right now") + ": " + "; ".join(tips)
        box.set_tooltip_text("\n".join(tips))
        box.update_property([Gtk.AccessibleProperty.LABEL], [acc])

    def open_notification(self, it):
        """Benachrichtigung angeklickt: Seite (ggf. mit Unterhaltung/Block) bzw. Chat oeffnen"""
        if it.get("access") is False:
            return
        ch = it.get("chat") if isinstance(it.get("chat"), dict) else None
        if it.get("kind") == "chat.message" and ch and ch.get("id") is not None:
            self.show_route(("chat", int(ch["id"])))
            self.present()
            return
        if it.get("kind") == "backup.failed":                 # Admin-Bereich (eigener Port): im Browser
            u = it.get("url") or ""
            if u.startswith(("https://", "http://")):
                self.open_uri(u)
            return
        self.open_url(it.get("url") or "", thread=it.get("thread_id"), block=it.get("block_id"))

    def open_url(self, url, thread=None, block=None, mark=False):
        """'/notes#12:block' bzw. '/chats#c5' (Mitteilungen, Benachrichtigungen) zeigen"""
        from .notifications import notifications, parse_url
        t = parse_url(url)
        self.present()
        if mark:                                          # Mitteilung angeklickt: passende Benachrichtigung gelesen
            ids = [x.get("id") for x in notifications.items if not x.get("read") and x.get("url") == url]
            if ids:
                notifications.mark_read(ids)
        if t is None:
            return
        kind, nid, blk = t
        if kind == "settings":                                # "/?settings=backup" (Mein Backup fehlgeschlagen)
            self.settings_dialog(page={"backup": "backup", "nextcloud": "settings"}.get(nid, "account"))
            return
        if kind == "chat":
            self.show_route(("chat", nid))
            return
        if kind == "chats":
            self.show_route(("chats",))
            return
        self.show_page(nid)
        cur = self.current
        if isinstance(cur, PageView) and cur.id == nid and (thread is not None or block or blk):
            b = block or blk

            def go():
                if cur.doc.fetched and "role" in cur.doc.page:
                    cur.show_comments(b or None)
                    if b:
                        cur.show_block(b)
                    return False
                return not cur.doc.loaded
            GLib.timeout_add(150, go)

    def _ai_button(self):
        """Kopfleisten-Knopf des Assistenten: Server ab 2.1 mit Profilbild und "Frage Marin", sonst "✦ Assistent" """
        from .assistant import ai, ai_avatar, ask_label, assistant_icon
        p = ai.persona()
        sig = (p.get("name"), p.get("avatar"), p.get("avatar_etag")) if p else None
        if sig == self._ai_sig:
            return
        self._ai_sig = sig
        self.ai_content.remove(self.ai_av)
        self.ai_av = ai_avatar(18, valign=Gtk.Align.CENTER) if p else Gtk.Image.new_from_icon_name(assistant_icon())
        self.ai_content.prepend(self.ai_av)
        self.ai_label.set_label(ask_label())
        self.ai_btn.set_tooltip_text(ask_label() if p else _("AI assistant"))

    def assistant(self, tab=None, action=None):
        from .assistant import AssistantDialog, ai
        if not ai.enabled:
            return
        v = None
        if self.peek:
            v = self.peek[1]
        elif isinstance(self.current, PageView):
            v = self.current
        if v is not None and not v.doc.fetched:
            v = None
        AssistantDialog(self, v, tab=tab or ("page" if v is not None and action else "ask"), action=action).present(self)

    def _link_request(self, ln):
        from .chatview import approve_link
        approve_link(self, ln)

    # --- Peek --------------------------------------------------------------------------------
    def open_entry(self, pid, mode="side", lst=None):
        self.peek_list = list(lst or [])
        if mode in ("side", "left"):
            self.open_peek(pid, center=False)
        elif mode == "center":
            self.open_peek(pid, center=True)
        else:
            self.close_peek()
            self.show_page(pid)

    def open_peek(self, pid, center=False):
        self.close_peek()
        v = PageView(self, pid, in_peek=True, on_close=self.close_peek)
        if center:
            dlg = Adw.Dialog(content_width=900, content_height=760)
            dlg.set_child(v)
            dlg.connect("closed", lambda *_: (v.leave(), self._peek_closed(dlg)))
            self.peek = ("center", v, dlg)
            dlg.present(self)
            return
        panel = Gtk.Box(halign=Gtk.Align.END)
        panel.add_css_class("hk-peek")
        grip = Gtk.Box()
        grip.add_css_class("hk-peek-grip")
        grip.set_size_request(6, -1)
        grip.set_cursor_from_name("col-resize")
        g = Gtk.GestureDrag()
        g.connect("drag-begin", lambda *_: setattr(self, "_pw0", settings.get("peek_width", 560)))
        g.connect("drag-update", lambda gs, x, y: self._peek_width(int(min(1000, max(360, self._pw0 - x)))))
        g.connect("drag-end", lambda *_: settings.flush())
        grip.add_controller(g)
        panel.append(grip)
        v.set_size_request(settings.get("peek_width", 560), -1)
        panel.append(v)
        self.overlay.add_overlay(panel)
        self.peek = ("side", v, panel)

    def _peek_width(self, w):
        settings.set_soon("peek_width", w)               # beim Ziehen nicht bei jeder Mausbewegung auf die Platte
        if self.peek and self.peek[0] == "side":
            self.peek[1].set_size_request(w, -1)

    def _peek_closed(self, dlg):
        if self.peek and self.peek[2] is dlg:
            self.peek = None

    def close_peek(self):
        if not self.peek:
            return
        kind, v, w = self.peek
        self.peek = None
        v.leave()
        if kind == "side":
            self.overlay.remove_overlay(w)
        else:
            w.close()

    def peek_step(self, d):
        if not self.peek or not self.peek_list:
            return
        pid = self.peek[1].id
        if pid in self.peek_list:
            i = self.peek_list.index(pid) + d
            if 0 <= i < len(self.peek_list):
                center = self.peek[0] == "center"
                self.open_peek(self.peek_list[i], center=center)

    # --- Aktionen ------------------------------------------------------------------------------
    def _actions(self):
        app = self.get_application()
        acts = {
            "palette": (self.palette, ["<Control>k", "<Control>p"]),
            "new-page": (self.new_page, ["<Control>n"]),
            "gallery": (self.gallery_dialog, []),
            "back": (self.go_back, ["<Alt>Left"]),
            "forward": (self.go_forward, ["<Alt>Right"]),
            "refresh": (lambda: store.refresh(), ["<Control>r", "F5"]),
            "settings": (self.settings_dialog, ["<Control>comma"]),
            "copy-link": (self.copy_link, ["<Control><Shift>l"]),
            "search": (self.search_sidebar, ["<Control><Shift>f"]),
            "toggle-sidebar": (self.toggle_sidebar, ["F9", "<Control>backslash"]),
            "peek-next": (lambda: self.peek_step(1), ["<Control><Shift>j"]),
            "peek-prev": (lambda: self.peek_step(-1), ["<Control><Shift>k"]),
            "today": (lambda: self.show_root(("today",)), ["<Control>1"]),
            "tasks": (lambda: self.show_root(("tasks",)), ["<Control>2"]),
            "events": (lambda: self.show_root(("events",)), ["<Control>3"]),
            "trash": (self.trash_dialog, []),
            "shortcuts": (self.shortcuts, ["<Control>question"]),
            "about": (self.about, []),
        }
        for name, (fn, accels) in acts.items():
            a = Gio.SimpleAction.new(name, None)
            a.connect("activate", lambda *_, fn=fn: fn())
            self.add_action(a)
            if accels:
                app.set_accels_for_action("win." + name, accels)

    def _key(self, c, kv, code, state):
        if kv == Gdk.KEY_Escape and self.peek and self.peek[0] == "side":
            self.close_peek()
            return True
        return False

    def palette_actions(self):
        a = [("document-new-symbolic", _("New page"), self.new_page),
             *([("document-new-symbolic", _("From template …"), self.gallery_dialog)] if self._gallery_offered() else []),
             ("weather-clear-symbolic", _("Today"), lambda: self.show_root(("today",))),
             ("object-select-symbolic", _("Tasks"), lambda: self.show_root(("tasks",))),
             ("x-office-calendar-symbolic", _("Events"), lambda: self.show_root(("events",))),
             ("user-trash-symbolic", _("Trash"), self.trash_dialog), ("view-app-grid-symbolic", _("Services"), lambda: self.show_root(("services",))),
             *([("saga-graph-symbolic", _("Graph"), lambda: self.show_route(("graph", "all", self.page_id())))] if graph_available() else []),
             ("view-refresh-symbolic", _("Refresh"), lambda: store.refresh()), ("go-previous-symbolic", _("Back"), self.go_back),
             ("go-next-symbolic", _("Forward"), self.go_forward), ("system-search-symbolic", _("Search in the sidebar"), self.search_sidebar),
             ("sidebar-show-symbolic", _("Show/hide sidebar"), self.toggle_sidebar),
             ("emblem-system-symbolic", _("Settings"), self.settings_dialog)]
        from .chat import chat
        if chat.status in ("setup", "device", "ready"):
            from .chatview import chat_icon
            a.append((chat_icon(), _("Chats"), lambda: self.show_route(("chats",))))
        from .notifications import bell_icon, notifications
        if notifications.supported is not False:
            a.append((bell_icon(), _("Notifications"), lambda: (self.split.set_show_sidebar(True), self.bell.popup())))
        from .assistant import ai, ask_label, assistant_icon
        if ai.enabled:
            a.append((assistant_icon(), ask_label() if ai.persona() else _("AI assistant"), lambda: self.assistant()))
        if self.page_id():
            from .sharing import allowed, comment_icon, people_icon
            a.append(("insert-link-symbolic", _("Copy link to page"), self.copy_link))
            pid = self.page_id()
            if graph_available() and pid > 0:
                a.append(("saga-graph-symbolic", _("Local graph"), lambda: self.show_route(("graph", "local", pid))))
            doc = self.peek[1].doc if self.peek else self.current.doc
            if doc.fetched and publish_available(doc.page) and allowed(doc.page, "publish"):
                a.append((globe_icon(), _("Publish …"), lambda: self.publish_dialog(doc.id)))
            if doc.fetched and "role" in doc.page:
                if allowed(doc.page, "share"):
                    a.append((people_icon(), _("Share …"), lambda: self.share_dialog(doc.id)))
                view = self.peek[1] if self.peek else self.current
                a.append((comment_icon(), _("Comments"), lambda: view.show_comments()))
        return a

    def page_id(self):
        if self.peek:
            return self.peek[1].id
        if isinstance(self.current, PageView):
            return self.current.id
        return None

    def palette(self):
        from .dialogs import CommandPalette
        CommandPalette(self, self.palette_actions()).present(self)

    def new_page(self, parent=None):
        store.new_page(parent, done=lambda pid: self.show_page(pid, root=parent is None))

    # --- Vorlagengalerie (2.5) ---------------------------------------------------------------
    def _gallery_offered(self):
        from .galeriedialog import offered
        return offered()

    def _gallery_probe(self):
        from .galeriedialog import probe
        probe(self)
        self.gallery_changed()

    def gallery_changed(self):
        """Neu-Knopf mit/ohne Menue "Aus Vorlage ..." (404/403 bzw. aelterer Server: ausgeblendet)"""
        on = self._gallery_offered()
        self.new_split.set_visible(on)
        self.new_btn.set_visible(not on)

    def gallery_dialog(self, parent=None):
        if not self._gallery_offered():
            return
        from .galeriedialog import GalleryDialog
        GalleryDialog(self, parent).present(self)

    def copy_link(self):
        pid = self.page_id()
        if pid:
            self.copy_text(store.page_url(pid))
            self.toast(_("Link copied"))

    def publish_dialog(self, pid):
        open_publish(self, pid)

    def share_dialog(self, pid):
        from .sharing import open_share
        open_share(self, pid, on_change=self.update_header)

    def settings_dialog(self, page=None):
        from .settings import SettingsDialog
        d = SettingsDialog(self)
        d.present(self)
        if page:
            d.show_page(page)

    def trash_dialog(self):
        from .dialogs import TrashDialog
        TrashDialog(self).present(self)

    def move_dialog(self, pid):
        from .dialogs import move_dialog
        move_dialog(self, pid)

    def versions_dialog(self, pid, restored, can_restore=True):
        from .dialogs import VersionsDialog
        VersionsDialog(self, pid, restored, can_restore=can_restore).present(self)

    SHORTCUTS = [
        (_("General"), [(_("Command palette"), "<Control>k <Control>p"), (_("New page"), "<Control>n"),
                        (_("Back / Forward"), "<Alt>Left <Alt>Right"), (_("Refresh"), "<Control>r"), (_("Settings"), "<Control>comma"),
                        (_("Copy link to page"), "<Control><Shift>l"), (_("Search the sidebar"), "<Control><Shift>f"),
                        (_("Show/hide sidebar"), "F9 <Control>backslash"), (_("Today / Tasks / Events"), "<Control>1 <Control>2 <Control>3"),
                        (_("Close peek"), "Escape"), (_("Browse in peek"), "<Control><Shift>j <Control><Shift>k"),
                        (_("Context menu"), "Menu <Shift>F10"), (_("Keyboard shortcuts"), "<Control>question"), (_("Quit"), "<Control>q")]),
        (_("Text"), [(_("Bold, italic, underline"), "<Control>b <Control>i <Control>u"), (_("Strikethrough"), "<Control><Shift>x"),
                     (_("Highlight"), "<Control><Shift>h"), (_("Code"), "<Control>e"), (_("Link (with a selection)"), "<Control>k"),
                     (_("Undo / redo typing"), "<Control>z <Control><Shift>z"),
                     (_("Block types"), "slash"), (_("Date, reminder, page"), "at"), (_("Emoji"), "colon")]),
        (_("Blocks"), [(_("Text, Heading 1–3"), "<Control><Alt>0 <Control><Alt>1 <Control><Alt>2 <Control><Alt>3"),
                       (_("Checklist, bulleted list, numbered list"), "<Control><Alt>4 <Control><Alt>5 <Control><Alt>6"),
                       (_("Toggle, code"), "<Control><Alt>7 <Control><Alt>8"), (_("Move block"), "<Control><Shift>Up <Control><Shift>Down"),
                       (_("Duplicate block"), "<Control>d"), (_("New block below"), "<Control>Return"),
                       (_("Indent/outdent list item"), "Tab <Shift>Tab"), (_("Block menu"), "Menu <Shift>F10")]),
    ]

    def shortcuts(self):
        if hasattr(Adw, "ShortcutsDialog"):                 # libadwaita >= 1.8
            d = Adw.ShortcutsDialog()
            for title, items in self.SHORTCUTS:
                sec = Adw.ShortcutsSection(title=title)
                for name, accel in items:
                    sec.add(Adw.ShortcutsItem.new(name, accel))
                d.add(sec)
            d.present(self)
            return
        lines = []
        for title, items in self.SHORTCUTS:
            lines.append(title + ":")
            for name, accel in items:
                keys = " / ".join(accel_label(a) for a in accel.split())
                lines.append(f"{keys} – {name}")
            lines.append("")
        d = Adw.AlertDialog(heading=_("Keyboard shortcuts"), body="\n".join(lines).strip())
        d.add_response("ok", _("OK"))
        d.present(self)

    def about(self, parent=None):
        from . import version_label
        a = Adw.AboutDialog(application_name="Saga", application_icon=APP_ID, version=version_label(),
                            developer_name=_("Saga project"), comments=_("Notes, databases, tasks and events – self-hosted."),
                            translator_credits=_("translator-credits"),
                            website=PROJECT_URL, issue_url=PROJECT_URL + "/issues", license_type=Gtk.License.AGPL_3_0_ONLY)
        a.present(parent or self)

    # --- Seitenleiste: Aktionen --------------------------------------------------------------------
    def page_actions(self, widget, p):
        pid = p["id"]
        if store.is_foreign(pid):                              # mit mir geteilt: nur, was die Rolle darf
            from .sharing import allowed
            ents = []
            if allowed(p, "subpage") and not is_db(p):         # Seiten anderer Server (2.3): nur mit 'subpages'
                ents.append((_("New subpage"), lambda: store.new_page(pid, done=lambda n: self.show_page(n))))
            if allowed(p, "move"):                             # Bearbeiter (2.3): innerhalb des geteilten Bereichs
                ents.append((_("Move to …"), lambda: self.move_dialog(pid)))
            ents.append((_("Copy link"), lambda: self.copy_text(store.page_url(pid))))
            menu_popover(widget, ents).popup()
            return
        if p.get("archived"):
            ents = [(_("Restore"), lambda: store.set_archived(pid, False))]
        else:
            ents = [(_("Remove from favorites") if p.get("pinned") else _("Add to favorites"), lambda: store.patch(pid, {"pinned": not p.get("pinned")}))]
            if not is_db(p):
                ents.append((_("New subpage"), lambda: store.new_page(pid, done=lambda n: self.show_page(n))))
                if self._gallery_offered() and pid > 0:
                    ents.append((_("New subpage from template …"), lambda: self.gallery_dialog(pid)))
            ents += [(_("Move to …"), lambda: self.move_dialog(pid)), (_("Duplicate"), lambda: store.duplicate(pid, done=lambda n: self.show_page(n))),
                     (_("Copy link"), lambda: self.copy_text(store.page_url(pid))),
                     (_("Archive"), lambda: (self._leave_if(pid), store.set_archived(pid, True))), None,
                     (_("Move to trash"), lambda: (self._leave_if(pid), store.trash(pid)), True)]
        menu_popover(widget, ents).popup()

    def _leave_if(self, pid):
        if self.route and self.route[0] == "page" and (self.route[1] == pid or self.route[1] in store.descendants(pid)):
            self.show_root(("today",))                    # auch eine offene Unterseite geht mit in den Papierkorb

    def drop_pages(self, value, parent):
        if not isinstance(value, str) or not value.startswith("hkpage:"):
            return False
        pid = int(value[7:])
        if pid == parent or (parent is not None and parent in store.descendants(pid)):
            return False
        store.move(pid, parent, error=lambda e: self.toast(str(e)))
        return True

    def run_button(self, b, busy=None, via=None):
        """Schaltflaechen-Block. busy(on): Knopf sperren/Fortschritt zeigen, solange "Neuer Eintrag" laeuft - mehrfaches
        Klicken legt nichts doppelt an (der Server kennt keinen Idempotenz-Schluessel)."""
        tgt = b.get("target")
        act = b.get("action")
        mode = b.get("open") or ""
        if act == "page" and tgt:
            self.open_entry(tgt, mode)
        elif act == "db" and tgt:
            title = (b.get("title") or "").replace("{datum}", date_num(_dt.date.today()))
            tpl = b.get("tpl")
            body = {"parent_id": tgt}
            if title or not tpl:
                body["title"] = title
            if tpl and isinstance(via, int) and not isinstance(via, bool) and via != 0:
                body["via"] = via          # geteilte Seite (auch eines anderen Servers, negativ): Server prueft die Schaltflaeche dort
            path = f"api/notes/{tpl}/duplicate" if tpl else "api/notes"
            if busy:
                busy(True)

            def release():
                if busy:
                    GLib.timeout_add(800, lambda: (busy(False), False)[1])    # waehrend der Eintrag aufgeht: kein zweites Mal

            def fin(r):
                if tpl:
                    store.note_fresh(r.get("id"), r, body.get("title", ""))   # E4 (auch mit Titel, wie Web): aus Vorlage in eine Datenbank -> Platzhalter, ggf. verwerfen
                    store.refresh()                        # Vorlage kann Unterseiten mitbringen: Liste vom Server
                else:
                    store.page_added(r["id"], tgt)         # neuer Eintrag lokal in die Seitenliste
                store.changed_data()
                self.open_entry(r["id"], mode)
                release()

            def err(e):
                release()
                if not (isinstance(e, APIError) and e.code == 401):
                    self.toast(str(e))
            store.run("POST", path, body, done=fin, error=err)
        elif act == "task":
            store.add_task(b.get("title") or _("New task"))
            self.toast(_("Task created"))
        elif act in ("page", "db"):                       # Ziel fehlt (nie gewaehlt bzw. geloescht): sagen statt nichts tun
            self.toast(_("This button has no target yet – choose one in the button settings."))
        else:
            self.toast(_("This button has no action yet – choose one in the button settings."))

    # --- Hilfen ----------------------------------------------------------------------------
    def _chat_gone(self, how, name):
        """(2.1) jemand anderes hat einen Chat bzw. dessen Verlauf fuer alle geloescht: Hinweis mit Namen"""
        from .chatview import gone_text
        self.toast(gone_text(how, name))

    def toast(self, msg):
        if msg:
            self.toasts.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(msg)), timeout=4))

    def _undo_toast(self, pid, title):
        t = Adw.Toast(title=GLib.markup_escape_text(_("“{title}” moved to trash").format(title=title)), button_label=_("Undo"), timeout=6)
        t.connect("button-clicked", lambda *_: store.restore(pid))
        self.toasts.add_toast(t)

    def _task_undo_toast(self, tid, text):
        t = Adw.Toast(title=GLib.markup_escape_text(_("Task “{title}” deleted").format(title=text)), button_label=_("Undo"),
                      timeout=store.UNDO_SECONDS)
        t.connect("button-clicked", lambda *_: store.undo_delete_task(tid))
        self.toasts.add_toast(t)

    def copy_text(self, s):
        Gdk.Display.get_default().get_clipboard().set(s)

    def open_uri(self, uri):
        """im Standardbrowser oeffnen; geht keiner auf (kein Browser, Portal fehlt): Adresse zum Kopieren anbieten"""
        lau = Gtk.UriLauncher.new(uri)

        def fin(src, res):
            try:
                src.launch_finish(res)
            except GLib.Error:
                self.copy_text(uri)
                self.toast(_("No browser could be opened. The address was copied – paste it into a browser."))
                if self.stack.get_visible_child_name() == "login":
                    self.login_msg.set_label(_("No browser could be opened. Open this address in a browser: {url}").format(url=uri))
        lau.launch(self, None, fin)

    def upload_image(self, data, name="bild.png", note=None):
        """Hintergrund-Thread: Bild hochladen (HEIC & Co. -> JPEG), -> /files/...
        note: ID einer fremden Seite (Teilen, Rolle Bearbeiten) - die Datei gehoert deren Eigentuemer."""
        head = data[:12]
        known = head.startswith(b"\x89PNG") or head.startswith(b"\xff\xd8") or head.startswith(b"GIF") or head[8:12] == b"WEBP"
        if head.startswith(b"\xff\xd8"):
            name = "bild.jpg"
        elif head.startswith(b"GIF"):
            name = "bild.gif"
        elif head.startswith(b"\x89PNG"):
            name = "bild.png"
        if not known:
            from PIL import Image
            im = Image.open(io.BytesIO(data)).convert("RGB")
            out = io.BytesIO()
            im.save(out, "JPEG", quality=85)
            data, name = out.getvalue(), "bild.jpg"
        return client.upload(data, name, note=note)["url"]

    def download_open(self, url, name):
        """Anhang speichern: Ort waehlen (Portal), stueckweise auf die Platte schreiben, abbrechbar; Oeffnen nur auf Wunsch"""
        safe = os.path.basename((name or "").replace("/", "-").replace("\\", "-")).strip(". ") or "datei"
        fd = Gtk.FileDialog(title=_("Save attachment"), initial_name=safe)

        def chosen(d, res):
            try:
                f = d.save_finish(res)
            except GLib.Error:
                return
            if f is not None:
                self._download_to(url, f, safe)
        fd.save(self, None, chosen)

    def _download_to(self, url, gfile, name):
        state = {"cancel": False}
        toast = Adw.Toast(title=GLib.markup_escape_text(_("Downloading “{name}” …").format(name=name)), button_label=_("Cancel"), timeout=0)
        toast.connect("button-clicked", lambda *_: state.__setitem__("cancel", True))
        self.toasts.add_toast(toast)

        def work():
            out = gfile.replace(None, False, Gio.FileCreateFlags.REPLACE_DESTINATION, None)
            ok = False
            try:
                client.download(url.lstrip("/"), lambda b: out.write_all(b, None), cancelled=lambda: state["cancel"])
                ok = True
            finally:
                out.close(None)
                if not ok:
                    try:
                        gfile.delete(None)
                    except GLib.Error:
                        pass
            return gfile

        def fin(f):
            toast.dismiss()
            t = Adw.Toast(title=GLib.markup_escape_text(_("Saved: {name}").format(name=f.get_basename() or name)), button_label=_("Open"), timeout=8)

            def open_it(*_):
                fl = Gtk.FileLauncher.new(f)
                fl.set_always_ask(True)                        # nie still mit dem Standardprogramm oeffnen
                fl.launch(self, None, None)
            t.connect("button-clicked", open_it)
            self.toasts.add_toast(t)

        def err(e):
            toast.dismiss()
            if not isinstance(e, Cancelled):
                self.toast(_("Download failed: {error}").format(error=e))
        bg(work, done=fin, error=err, media=True)

    def export_page(self, pid, title, with_sub):
        base = (title or _("Page")).replace("/", "-")
        fd = Gtk.FileDialog(title=_("Export"), initial_name=base + (".zip" if with_sub else ".md"))

        def done(d, res):
            try:
                f = d.save_finish(res)
            except GLib.Error:
                return

            def work():
                data = client.request("GET", f"api/notes/{pid}/export" + ("?sub=1" if with_sub else ""), timeout=120)
                f.replace_contents(data, None, False, Gio.FileCreateFlags.NONE, None)
            bg(work, done=lambda r: self.toast(_("Exported")), error=lambda e: self.toast(_("Export failed: {error}").format(error=e)))
        fd.save(self, None, done)

    def open_link(self, uri):
        """hlidskjalf://auth?code=…  ·  hlidskjalf://page/12 · today · tasks · events · new?parent=12"""
        try:
            u = GLib.Uri.parse(uri, GLib.UriFlags.NONE)
        except GLib.Error:
            return
        host = u.get_host() or ""
        if host == "auth":
            self.finish_login(uri)
            return
        if host == "pair":                                     # QR-Code-Seite im Browser: "In der App öffnen" - immer nachfragen
            self.pair_link(uri, external=True)
            return
        arg = (u.get_path() or "").strip("/")
        if host == "page" and arg.isdigit():
            self.show_page(int(arg), root=True)
        elif host in ("tasks", "events", "services", "chats"):
            self.show_root((host,))
        elif host == "new-task":                              # Schnellaktion im Dock: Aufgabe gleich eintippen
            self.show_root(("tasks",))
            tl = getattr(self.current, "task_list", None)
            if tl:
                GLib.timeout_add(300, lambda: (tl.focus_entry(), False)[1])
        elif host == "new":                                   # von einer Webseite aus: nur mit Rueckfrage
            params = GLib.Uri.parse_params(u.get_query() or "", -1, "&", GLib.UriParamsFlags.NONE)
            p = params.get("parent")
            parent = int(p) if p and p.isdigit() else None
            d = Adw.AlertDialog(heading=_("Create a new page?"),
                                body=_("A link wants to create a new page in Saga."))
            d.add_response("cancel", _("Cancel"))
            d.add_response("ok", _("Create page"))
            d.set_response_appearance("ok", Adw.ResponseAppearance.SUGGESTED)
            d.set_default_response("ok")
            d.set_close_response("cancel")
            d.connect("response", lambda dd, r: r == "ok" and self.new_page(parent))
            d.present(self)
        else:
            self.show_root(("today",))
