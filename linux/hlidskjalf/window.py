"""Hauptfenster: Seitenleiste (Baum, Heute, Favoriten), Navigation, Peek, Anmeldung, Tastenkuerzel."""
import datetime as _dt
import io
import os

from gi.repository import Adw, Gdk, Gio, GLib, GObject, Gtk, Pango

from . import APP_ID, PROJECT_URL, images
from .api import APIError, client, pkce
from .blocks import flat_button, menu_popover
from .config import device_name, get_device_token, settings
from .page import PageView
from .store import is_db, label, store, watch
from .util import bg, norm, placeholder


class SidebarRow(Gtk.Box):
    """Zeile der Seitenleiste (Seite oder fester Eintrag)"""

    def __init__(self, win, title, icon_widget=None, key=None, depth=0, page=None, badge=None, expander=None):
        super().__init__(spacing=6, margin_start=6 + depth * 14, margin_end=6)
        self.win, self.key, self.page = win, key, page
        self.add_css_class("hk-srow")
        if expander is not None:
            self.append(expander)
        elif depth or page is not None:
            self.append(Gtk.Box(width_request=22))
        if icon_widget is not None:
            self.append(icon_widget)
        self.lbl = Gtk.Label(label=title, xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.END)
        self.append(self.lbl)
        if badge:
            b = Gtk.Label(label=str(badge))
            b.add_css_class("hk-badge")
            self.append(b)
        g = Gtk.GestureClick()
        g.connect("released", self._click)
        self.add_controller(g)
        if page is not None:
            r = Gtk.GestureClick(button=3)
            r.connect("pressed", lambda *_: win.page_actions(self, page))
            self.add_controller(r)
            ds = Gtk.DragSource(actions=Gdk.DragAction.MOVE)
            ds.connect("prepare", lambda *_: Gdk.ContentProvider.new_for_value(f"hkpage:{page['id']}"))
            self.add_controller(ds)
            if not page.get("archived"):
                dt = Gtk.DropTarget.new(GObject.TYPE_STRING, Gdk.DragAction.MOVE)
                dt.connect("enter", lambda *_: (self.add_css_class("hk-drop-into"), Gdk.DragAction.MOVE)[1])
                dt.connect("leave", lambda *_: self.remove_css_class("hk-drop-into"))
                dt.connect("drop", lambda t, v, x, y: (self.remove_css_class("hk-drop-into"), win.drop_pages(v, page["id"]))[1])
                self.add_controller(dt)

    def _click(self, g, n, x, y):
        if self.key is not None:
            self.win.show_root(self.key)


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
        self.search = Gtk.SearchEntry(placeholder_text="Seiten durchsuchen", margin_start=8, margin_end=8, margin_bottom=6)
        self.search.connect("search-changed", self._search)
        self.search.connect("stop-search", lambda *_: self.search.set_text(""))
        self.append(self.search)
        sw = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=1, margin_bottom=12)
        sw.set_child(self.box)
        self.append(sw)
        watch(self, "pages-changed", lambda: self.fill(force=False))
        watch(self, "today-changed", self._today_changed)
        self.fill(force=True)

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
        return (tuple((p["id"], p.get("title"), p.get("icon"), p.get("parent_id"), p.get("pinned"), p.get("kind")) for p in store.pages),
                tuple(p["id"] for p in store.archived), tuple(store.recent[:5]), len(store.tasks), self.query, len(self.results),
                tuple(sorted(self.expanded)), self.show_archive)

    def _today_changed(self):
        self.fill(force=False)                       # nur neu, wenn sich die Anzahl der Aufgaben geaendert hat

    def header(self, text, drop=False):
        lb = Gtk.Label(label=text, xalign=0, margin_start=12, margin_top=12, margin_bottom=2)
        lb.add_css_class("caption-heading")
        lb.add_css_class("dim-label")
        if drop:
            dt = Gtk.DropTarget.new(GObject.TYPE_STRING, Gdk.DragAction.MOVE)
            dt.connect("drop", lambda t, v, x, y: self.win.drop_pages(v, None))
            lb.add_controller(dt)
            lb.set_tooltip_text("Hierher ziehen: auf die oberste Ebene")
        self.box.append(lb)

    def fill(self, force=False):
        sig = self._sig()
        if not force and sig == self.sig:
            self.win.mark_active()
            return
        self.sig = sig
        while (c := self.box.get_first_child()):
            self.box.remove(c)
        self.rows = {}
        w = self.win
        if self.query:
            self.header("Treffer")
            n = norm(self.query)
            by_name = [p for p in store.pages if n in norm(label(p))]
            ids = {p["id"] for p in by_name}
            hits = by_name + [r for r in self.results if r["id"] not in ids]
            if not hits:
                lb = Gtk.Label(label="Keine Treffer", xalign=0, margin_start=12)
                lb.add_css_class("dim-label")
                self.box.append(lb)
            for p in hits[:60]:
                self._page_row(p, 0, flat=True, snippet=p.get("snippet"))
            return
        for key, title, icon, badge in (("tasks", "Aufgaben", "object-select-symbolic", len(store.tasks) or None),
                                        ("events", "Termine", "x-office-calendar-symbolic", None),
                                        ("services", "Dienste", "view-app-grid-symbolic", None)):
            r = SidebarRow(w, title, Gtk.Image.new_from_icon_name(icon), key=(key,), badge=badge)
            self.rows[(key,)] = r
            self.box.append(r)
        self.rows["tasks_badge"] = True
        self.header("Heute")
        from .agenda import TodayBox
        tb = TodayBox(w, compact=True)
        tb.set_margin_start(6)
        tb.set_margin_end(6)
        self.box.append(tb)
        if store.favorites:
            self.header("Favoriten")
            for p in store.favorites:
                self._page_row(p, 0, flat=True)
        rec = [store.by_id[i] for i in store.recent if i in store.by_id and not store.by_id[i].get("archived")][:5]
        if rec:
            self.header("Zuletzt")
            for p in rec:
                self._page_row(p, 0, flat=True)
        self.header("Seiten", drop=True)
        self._tree(None, 0)
        if store.archived:
            ex = flat_button(icon="pan-down-symbolic" if self.show_archive else "pan-end-symbolic", label=f"📦 Archiv ({len(store.archived)})",
                             css="dim-label hk-plain", cb=self._toggle_archive)
            ex.set_halign(Gtk.Align.START)
            ex.set_margin_top(8)
            self.box.append(ex)
            if self.show_archive:
                for p in store.archived:
                    self._page_row(p, 1, flat=True)
        tr = flat_button(icon="user-trash-symbolic", label="Papierkorb", css="dim-label hk-plain", cb=w.trash_dialog)
        tr.set_halign(Gtk.Align.START)
        tr.set_margin_top(8)
        self.box.append(tr)
        w.mark_active()

    def _toggle_archive(self):
        self.show_archive = not self.show_archive
        self.fill(force=True)

    def _tree(self, parent, depth):
        for p in store.children(parent):
            kids = store.has_children(p["id"])
            self._page_row(p, depth, kids=kids)
            if kids and p["id"] in self.expanded:
                self._tree(p["id"], depth + 1)

    def _page_row(self, p, depth, kids=False, flat=False, snippet=None):
        exp = None
        if kids and not flat:
            on = p["id"] in self.expanded
            exp = Gtk.Button(icon_name="pan-down-symbolic" if on else "pan-end-symbolic", valign=Gtk.Align.CENTER)
            exp.add_css_class("flat")
            exp.add_css_class("hk-tiny")
            exp.connect("clicked", lambda *_, i=p["id"]: self.toggle(i))
        ic = images.page_icon(p.get("icon"), size=20, fallback="🗃️" if is_db(p) else "📄")
        r = SidebarRow(self.win, label(p), ic, key=("page", p["id"]), depth=depth, page=p, expander=exp)
        if snippet:
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            col.append(r)
            s = Gtk.Label(label=snippet, xalign=0, wrap=True, lines=2, ellipsize=Pango.EllipsizeMode.END, margin_start=40, margin_end=8)
            s.add_css_class("caption")
            s.add_css_class("dim-label")
            col.append(s)
            self.box.append(col)
        else:
            self.box.append(r)
        self.rows.setdefault(("page", p["id"]), r)

    def toggle(self, pid):
        if pid in self.expanded:
            self.expanded.discard(pid)
        else:
            self.expanded.add(pid)
        settings.set("expanded", sorted(self.expanded))
        self.fill(force=True)

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


class MainWindow(Adw.ApplicationWindow):
    __gtype_name__ = "HKMainWindow"

    def __init__(self, app):
        super().__init__(application=app, title="Saga")
        self.set_default_size(settings.get("win_w", 1280), settings.get("win_h", 820))
        if settings.get("win_max"):
            self.maximize()
        self.history, self.fwd = [], []
        self.route = None
        self.current = None
        self.peek = None
        self.peek_list = []
        self.verifier = None
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
        self.connect("notify::is-active", lambda *_: self.is_active() and self._activated())
        self.connect("close-request", self._closing)
        GLib.timeout_add_seconds(60, self._tick)
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", self._key)
        self.add_controller(kc)
        if not client.cookies and not get_device_token():
            self.stack.set_visible_child_name("login")
        else:
            self.stack.set_visible_child_name("main")
        home = store.home_page
        if home and home in store.by_id:
            self.show_root(("page", home))
        else:
            self.show_root(("today",))
        store.refresh(done=self._after_first_refresh)

    def _after_first_refresh(self):
        home = store.home_page
        if home and store.pages and home not in store.by_id:
            store.set_home(None)

    def _closing(self, *_):
        if self.current is not None and isinstance(self.current, PageView):
            self.current.editor.flush()
        if self.peek is not None:
            self.peek[1].editor.flush()
        w, h = self.get_default_size()
        settings.set("win_w", w)
        settings.set("win_h", h)
        settings.set("win_max", self.is_maximized())
        return False

    def _version_banner(self):
        if store.app_too_old:
            self.old_banner.set_title(f"Diese App ist zu alt für den Server (ab {store.min_app}) – bitte aktualisieren.")
        self.old_banner.set_revealed(store.app_too_old)

    def _activated(self):
        if not store.needs_login:
            store.refresh()

    def _tick(self):
        if self.is_active() and not store.needs_login:
            store.refresh()
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
        new = flat_button(icon="document-new-symbolic", tooltip="Neue Seite (Strg+N)", cb=self.new_page)
        hb.pack_start(new)
        menu = Gtk.MenuButton(icon_name="open-menu-symbolic", tooltip_text="Menü")
        m = Gio.Menu()
        m.append("Befehlspalette", "win.palette")
        m.append("Papierkorb", "win.trash")
        m.append("Aktualisieren", "win.refresh")
        m.append("Einstellungen", "win.settings")
        m.append("Tastenkürzel", "win.shortcuts")
        m.append("Über Saga", "win.about")
        menu.set_menu_model(m)
        hb.pack_end(menu)
        sb.add_top_bar(hb)
        self.sidebar = Sidebar(self)
        sb.set_content(self.sidebar)
        self.split.set_sidebar(Adw.NavigationPage(title="Saga", child=sb))
        ct = Adw.ToolbarView()
        self.chb = Adw.HeaderBar()
        # Knopf in der Inhalts-Kopfleiste: bleibt sichtbar, auch wenn die Seitenleiste eingeklappt ist
        self.sb_btn = Gtk.ToggleButton(icon_name="sidebar-show-symbolic", tooltip_text="Seitenleiste ein-/ausblenden (F9)")
        self.split.bind_property("show-sidebar", self.sb_btn, "active", GObject.BindingFlags.BIDIRECTIONAL | GObject.BindingFlags.SYNC_CREATE)
        self.chb.pack_start(self.sb_btn)
        self.back_btn = flat_button(icon="go-previous-symbolic", tooltip="Zurück (Alt+←)", cb=self.go_back)
        self.fwd_btn = flat_button(icon="go-next-symbolic", tooltip="Vor (Alt+→)", cb=self.go_forward)
        self.chb.pack_start(self.back_btn)
        self.chb.pack_start(self.fwd_btn)
        self.wtitle = Adw.WindowTitle(title="")
        self.chb.set_title_widget(self.wtitle)
        self.menu_btn = flat_button(icon="view-more-symbolic", tooltip="Seitenmenü")
        self.menu_btn.connect("clicked", lambda b: isinstance(self.current, PageView) and self.current.page_menu(b))
        self.star_btn = flat_button(icon="non-starred-symbolic", tooltip="Zu Favoriten", cb=lambda: isinstance(self.current, PageView) and self.current.toggle_fav())
        self.chb.pack_end(self.menu_btn)
        self.chb.pack_end(self.star_btn)
        ct.add_top_bar(self.chb)
        self.old_banner = Adw.Banner(title="", revealed=False)      # Server ist neuer, als diese App kann
        ct.add_top_bar(self.old_banner)
        store.connect("version-changed", lambda *_: self._version_banner())
        self.overlay = Gtk.Overlay()
        self.slot = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, vexpand=True, hexpand=True)
        self.overlay.set_child(self.slot)
        ct.set_content(self.overlay)
        self.split.set_content(Adw.NavigationPage(title="Seite", child=ct))
        bp = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 700sp"))
        bp.add_setter(self.split, "collapsed", True)
        self.add_breakpoint(bp)
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
        sp = Adw.StatusPage(title="Saga", description="Anmelden mit Passkey, Sicherheitsschlüssel oder Passwort.\nDie Anmeldung öffnet sich im Browser.")
        sp.set_icon_name(APP_ID)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, halign=Gtk.Align.CENTER)
        lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, width_request=360)
        lb.add_css_class("boxed-list")
        self.login_server = Adw.EntryRow(title="Server-Adresse", text=settings.server)
        placeholder(self.login_server, "https://saga.example.org")
        self.login_server.connect("entry-activated", lambda *_: self.start_login())
        lb.append(self.login_server)
        box.append(lb)
        b = Gtk.Button(label="Anmelden")
        b.add_css_class("suggested-action")
        b.add_css_class("pill")
        b.connect("clicked", lambda *_: self.start_login())
        box.append(b)
        self.login_msg = Gtk.Label(wrap=True, justify=Gtk.Justification.CENTER)
        self.login_msg.add_css_class("dim-label")
        if not settings.server:
            self.login_msg.set_label("Server-Adresse eingeben oder per Kopplungslink anmelden")
        box.append(self.login_msg)
        px = Gtk.Expander(label="Mit Kopplungslink anmelden (QR-Code aus dem Web)")
        pb = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=6)
        pb.append(Gtk.Label(label="Im Browser: 👤 → Einstellungen → „Apps & Geräte“ → „App per QR-Code anmelden“. "
                                  "Den Link unter dem QR-Code (…/app/pair?c=…) hier einfügen:", wrap=True, xalign=0))
        pe = Gtk.Entry(placeholder_text="https://…/app/pair?c=…")
        pe.connect("activate", lambda e: self.pair_link(e.get_text().strip()))
        pb.append(pe)
        px.set_child(pb)
        box.append(px)
        ex = Gtk.Expander(label="Browser leitet nicht zurück?")
        eb = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=6)
        eb.append(Gtk.Label(label="Adresse aus der Fehlermeldung des Browsers (hlidskjalf://auth?code=…) hier einfügen:", wrap=True, xalign=0))
        en = Gtk.Entry(placeholder_text="hlidskjalf://auth?code=…")
        en.connect("activate", lambda e: self.finish_login(e.get_text().strip()))
        eb.append(en)
        ex.set_child(eb)
        box.append(ex)
        sp.set_child(box)
        return sp

    # --- Anmeldung -------------------------------------------------------------------------
    def _use_server(self):
        s = self.login_server.get_text().strip().rstrip("/")
        if s and "://" not in s:
            s = "https://" + s
        if not s.startswith("https://") or len(s) < 12:
            self.login_msg.set_label("Bitte eine https-Adresse eingeben, z. B. https://saga.example.org")
            return False
        if s != settings.server:
            settings.server = s
            client.clear_cookies()
        self.login_server.set_text(s)
        return True

    def pair_link(self, text):
        """https://server/app/pair?c=CODE  oder  <app>://pair?u=https://server&c=CODE"""
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
        if len(code) < 20 or not srv.startswith(("https://", "http://")):
            self.login_msg.set_label("Das ist kein gültiger Kopplungslink.")
            return
        if srv != settings.server:
            settings.server = srv
            client.clear_cookies()
        self.login_server.set_text(srv)
        self.login_msg.set_label("Melde an …")

        def fin(j):
            self.login_msg.set_label("")
            store.logged_in()
            self.present()
            self.toast(f"Angemeldet als {j.get('name') or ''} – bleibt auf „{device_name()}“ angemeldet")
        bg(client.pair, code, not (settings.get("token") or "").startswith("hk_"), done=fin, error=lambda e: self.login_msg.set_label(str(e)))

    def start_login(self):
        if not self._use_server():
            return
        self.verifier, ch = pkce()
        url = f"{settings.server}/app/login?challenge={ch}"
        self.login_msg.set_label("Bitte im Browser anmelden …")
        self.open_uri(url)

    def finish_login(self, uri):
        try:
            u = GLib.Uri.parse(uri, GLib.UriFlags.NONE)
            params = GLib.Uri.parse_params(u.get_query() or "", -1, "&", GLib.UriParamsFlags.NONE)
            code = params.get("code")
        except GLib.Error:
            code = None
        if not code or not self.verifier:
            self.login_msg.set_label("Anmeldung bitte hier in der App starten.")
            return
        self.login_msg.set_label("Melde an …")
        v, self.verifier = self.verifier, None

        def fin(j):
            self.login_msg.set_label("")
            store.logged_in()
            self.present()
            self.toast(f"Angemeldet als {j.get('name') or ''} – bleibt auf „{device_name()}“ angemeldet")

        def err(e):
            self.login_msg.set_label(str(e))
        bg(client.exchange, code, v, not (settings.get("token") or "").startswith("hk_"), done=fin, error=err)

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
        from .agenda import today_screen
        return today_screen(self)

    def _show(self, route):
        if isinstance(self.current, PageView):
            self.current.editor.flush()
        self.close_peek()
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
        if len(self.history) > 1:
            self.go_back()
        else:
            self.show_root(("today",))

    def mark_active(self):
        rows = getattr(getattr(self, "sidebar", None), "rows", {})
        for k, r in rows.items():
            if isinstance(r, Gtk.Widget):
                (r.add_css_class if k == self.route else r.remove_css_class)("hk-active")

    def update_header(self):
        self.back_btn.set_sensitive(len(self.history) > 1)
        self.fwd_btn.set_sensitive(bool(self.fwd))
        is_page = isinstance(self.current, PageView)
        self.menu_btn.set_visible(is_page)
        self.star_btn.set_visible(is_page)
        if is_page:
            d = self.current.doc
            self.wtitle.set_title(d.title if d.loaded else label(store.by_id.get(d.id)))
            self.wtitle.set_subtitle(store.path_text(d.id))
            fav = bool(d.page.get("pinned") if d.loaded else (store.by_id.get(d.id) or {}).get("pinned"))
            self.star_btn.set_icon_name("starred-symbolic" if fav else "non-starred-symbolic")
            self.star_btn.set_tooltip_text("Aus Favoriten entfernen" if fav else "Zu Favoriten")
            self.set_title(f"{d.title} – Saga")
        else:
            t = {"today": "Heute", "tasks": "Aufgaben", "events": "Termine", "services": "Dienste"}.get((self.route or ("today",))[0], "")
            self.wtitle.set_title(t)
            self.wtitle.set_subtitle("")
            self.set_title("Saga")

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
            dlg.connect("closed", lambda *_: (v.editor.flush(), self._peek_closed(dlg)))
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
        grip.add_controller(g)
        panel.append(grip)
        v.set_size_request(settings.get("peek_width", 560), -1)
        panel.append(v)
        self.overlay.add_overlay(panel)
        self.peek = ("side", v, panel)

    def _peek_width(self, w):
        settings.set("peek_width", w)
        if self.peek and self.peek[0] == "side":
            self.peek[1].set_size_request(w, -1)

    def _peek_closed(self, dlg):
        if self.peek and self.peek[2] is dlg:
            self.peek = None
            store.emit("data-changed")

    def close_peek(self):
        if not self.peek:
            return
        kind, v, w = self.peek
        self.peek = None
        v.editor.flush()
        if kind == "side":
            self.overlay.remove_overlay(w)
        else:
            w.close()
        store.emit("data-changed")

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
        a = [("document-new-symbolic", "Neue Seite", self.new_page), ("weather-clear-symbolic", "Heute", lambda: self.show_root(("today",))),
             ("object-select-symbolic", "Aufgaben", lambda: self.show_root(("tasks",))),
             ("x-office-calendar-symbolic", "Termine", lambda: self.show_root(("events",))),
             ("user-trash-symbolic", "Papierkorb", self.trash_dialog), ("view-app-grid-symbolic", "Dienste", lambda: self.show_root(("services",))),
             ("view-refresh-symbolic", "Aktualisieren", lambda: store.refresh()), ("go-previous-symbolic", "Zurück", self.go_back),
             ("go-next-symbolic", "Vor", self.go_forward), ("system-search-symbolic", "In der Seitenleiste suchen", self.search_sidebar),
             ("sidebar-show-symbolic", "Seitenleiste ein-/ausblenden", self.toggle_sidebar),
             ("emblem-system-symbolic", "Einstellungen", self.settings_dialog)]
        if self.page_id():
            a.append(("insert-link-symbolic", "Link zur Seite kopieren", self.copy_link))
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

    def copy_link(self):
        pid = self.page_id()
        if pid:
            self.copy_text(store.page_url(pid))
            self.toast("Link kopiert")

    def settings_dialog(self):
        from .settings import SettingsDialog
        SettingsDialog(self).present(self)

    def trash_dialog(self):
        from .dialogs import TrashDialog
        TrashDialog(self).present(self)

    def move_dialog(self, pid):
        from .dialogs import move_dialog
        move_dialog(self, pid)

    def versions_dialog(self, pid, restored):
        from .dialogs import VersionsDialog
        VersionsDialog(self, pid, restored).present(self)

    def shortcuts(self):
        txt = ("Strg+K / Strg+P – Befehlspalette (Strg+K mit Auswahl: Link)\nStrg+N – Neue Seite\nAlt+← / Alt+→ – Zurück / Vor\n"
               "Strg+R – Aktualisieren\nStrg+, – Einstellungen\nStrg+Umschalt+L – Link zur Seite kopieren\nStrg+Umschalt+F – Seitenleiste durchsuchen\n"
               "F9 / Strg+\\ – Seitenleiste ein-/ausblenden\nStrg+1/2/3 – Heute / Aufgaben / Termine\nEsc – Peek schließen\nStrg+Umschalt+J/K – Im Peek blättern\n\n"
               "Im Text:\nStrg+B/I/U – Fett, kursiv, unterstrichen\nStrg+Umschalt+X – Durchgestrichen\nStrg+Umschalt+H – Markieren\nStrg+E – Code\n"
               "/ – Blocktypen   @ – Datum, Erinnerung, Seite   : – Emoji\n# ## ### - * 1. [] > ``` --- am Zeilenanfang – Blocktyp\n"
               "Strg+Alt+0…8 – Text, Überschrift 1–3, Checkliste, Aufzählung, Nummeriert, Toggle, Code\n"
               "Strg+Umschalt+↑/↓ – Block verschieben\nStrg+D – Block duplizieren\nStrg+Enter – Neuer Block darunter\nTab / Umschalt+Tab – Listenpunkt ein-/ausrücken")
        d = Adw.AlertDialog(heading="Tastenkürzel", body=txt)
        d.add_response("ok", "OK")
        d.present(self)

    def about(self):
        from . import VERSION
        a = Adw.AboutDialog(application_name="Saga", application_icon=APP_ID, version=VERSION,
                            developer_name="Saga-Projekt", comments="Notizen, Datenbanken, Aufgaben und Termine – selbst gehostet.",
                            website=PROJECT_URL, issue_url=PROJECT_URL + "/issues", license_type=Gtk.License.AGPL_3_0_ONLY)
        a.present(self)

    # --- Seitenleiste: Aktionen --------------------------------------------------------------------
    def page_actions(self, widget, p):
        pid = p["id"]
        if p.get("archived"):
            ents = [("Zurückholen", lambda: store.set_archived(pid, False))]
        else:
            ents = [("Aus Favoriten entfernen" if p.get("pinned") else "Zu Favoriten", lambda: store.patch(pid, {"pinned": not p.get("pinned")}))]
            if not is_db(p):
                ents.append(("Neue Unterseite", lambda: store.new_page(pid, done=lambda n: self.show_page(n))))
            ents += [("Verschieben nach …", lambda: self.move_dialog(pid)), ("Duplizieren", lambda: store.duplicate(pid, done=lambda n: self.show_page(n))),
                     ("Link kopieren", lambda: self.copy_text(store.page_url(pid))),
                     ("Archivieren", lambda: (self._leave_if(pid), store.set_archived(pid, True))), None,
                     ("In den Papierkorb", lambda: (self._leave_if(pid), store.trash(pid)), True)]
        menu_popover(widget, ents).popup()

    def _leave_if(self, pid):
        if self.route == ("page", pid):
            self.show_root(("today",))

    def drop_pages(self, value, parent):
        if not isinstance(value, str) or not value.startswith("hkpage:"):
            return False
        pid = int(value[7:])
        if pid == parent or (parent is not None and parent in store.descendants(pid)):
            return False
        store.move(pid, parent, error=lambda e: self.toast(str(e)))
        return True

    def run_button(self, b):
        tgt = b.get("target")
        act = b.get("action")
        mode = b.get("open") or ""
        if act == "page" and tgt:
            self.open_entry(tgt, mode)
        elif act == "db" and tgt:
            title = (b.get("title") or "").replace("{datum}", _dt.date.today().strftime("%d.%m.%Y"))
            tpl = b.get("tpl")
            body = {"parent_id": tgt}
            if title or not tpl:
                body["title"] = title
            path = f"api/notes/{tpl}/duplicate" if tpl else "api/notes"
            store.run("POST", path, body, done=lambda r: (store.refresh(), store.emit("data-changed"), self.open_entry(r["id"], mode)))
        elif act == "task":
            store.add_task(b.get("title") or "Neue Aufgabe")
            self.toast("Aufgabe angelegt")

    # --- Hilfen ----------------------------------------------------------------------------
    def toast(self, msg):
        if msg:
            self.toasts.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(msg)), timeout=4))

    def _undo_toast(self, pid, title):
        t = Adw.Toast(title=GLib.markup_escape_text(f"„{title}“ in den Papierkorb verschoben"), button_label="Rückgängig", timeout=6)
        t.connect("button-clicked", lambda *_: store.restore(pid))
        self.toasts.add_toast(t)

    def copy_text(self, s):
        Gdk.Display.get_default().get_clipboard().set(s)

    def open_uri(self, uri):
        Gtk.UriLauncher.new(uri).launch(self, None, None)

    def upload_image(self, data, name="bild.png"):
        """Hintergrund-Thread: Bild hochladen (HEIC & Co. -> JPEG), -> /files/..."""
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
        return client.upload(data, name)["url"]

    def download_open(self, url, name):
        def work():
            data = client.fetch(url.lstrip("/"), timeout=300)
            d = GLib.get_user_special_dir(GLib.UserDirectory.DIRECTORY_DOWNLOAD) or os.path.expanduser("~")
            base, ext = os.path.splitext(name.replace("/", "-"))
            p, n = os.path.join(d, base + ext), 1
            while os.path.exists(p):
                p = os.path.join(d, f"{base} ({n}){ext}")
                n += 1
            with open(p, "wb") as f:
                f.write(data)
            return p

        def fin(p):
            self.toast(f"Gespeichert: {os.path.basename(p)}")
            Gtk.FileLauncher.new(Gio.File.new_for_path(p)).launch(self, None, None)
        self.toast("Lade herunter …")
        bg(work, done=fin, error=lambda e: self.toast(str(e)))

    def export_page(self, pid, title, with_sub):
        base = (title or "Seite").replace("/", "-")
        fd = Gtk.FileDialog(title="Exportieren", initial_name=base + (".zip" if with_sub else ".md"))

        def done(d, res):
            try:
                f = d.save_finish(res)
            except GLib.Error:
                return

            def work():
                data = client.request("GET", f"api/notes/{pid}/export" + ("?sub=1" if with_sub else ""), timeout=120)
                f.replace_contents(data, None, False, Gio.FileCreateFlags.NONE, None)
            bg(work, done=lambda _: self.toast("Exportiert"), error=lambda e: self.toast("Export fehlgeschlagen: " + str(e)))
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
        if host == "pair":                                     # QR-Code-Seite im Browser: "In der App öffnen"
            self.pair_link(uri)
            return
        arg = (u.get_path() or "").strip("/")
        if host == "page" and arg.isdigit():
            self.show_page(int(arg), root=True)
        elif host in ("tasks", "events", "services"):
            self.show_root((host,))
        elif host == "new-task":                              # Schnellaktion im Dock: Aufgabe gleich eintippen
            self.show_root(("tasks",))
            tl = getattr(self.current, "task_list", None)
            if tl:
                GLib.timeout_add(300, lambda: (tl.focus_entry(), False)[1])
        elif host == "new":
            params = GLib.Uri.parse_params(u.get_query() or "", -1, "&", GLib.UriParamsFlags.NONE)
            p = params.get("parent")
            self.new_page(int(p) if p and p.isdigit() else None)
        else:
            self.show_root(("today",))
