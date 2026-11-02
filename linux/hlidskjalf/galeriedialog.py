"""Vorlagengalerie (Saga 2.5): einfache Auswahl - Liste nach Kategorie, Bestaetigung mit "Mit Beispielen", Anlegen.
Logik (Parsing, Fehler, Anfrage) in galerie.py."""
from gi.repository import Adw, GLib, Gtk, Pango

from . import config, galerie
from .api import APIError, client
from .i18n import _, ui_language
from .store import label, store
from .util import bg


def probe(win):
    """Im Hintergrund fragen, ob der Server die Galerie anbietet (einmal je Server) - danach Eintraege zeigen/ausblenden"""
    srv = config.settings.server
    if not srv or galerie.state.get(srv) is not None:
        return

    def fin(j):
        if srv == config.settings.server:
            galerie.state.set(srv, True)
            win.gallery_changed()

    def err(e):
        code = getattr(e, "code", 0)
        if srv == config.settings.server and isinstance(e, APIError) and galerie.hidden_by(code):
            galerie.state.set(srv, False)
            win.gallery_changed()
    bg(client.get, galerie.list_path(ui_language()), done=fin, error=err)


def offered():
    return galerie.state.offered(config.settings.server, store.server_version)


def _emoji(text):
    lb = Gtk.Label(label=text or "📄", width_chars=2)
    lb.add_css_class("title-2")
    return lb


class GalleryDialog(Adw.Dialog):
    """parent: vorgeschlagene Elternseite (Seitenmenue "Unterseite aus Vorlage ...") oder None (Neu-Menue: aktuelle eigene
    Seite als Schalter angeboten, Standard aus)"""

    def __init__(self, win, parent=None):
        super().__init__(title=_("From template"), content_width=520, content_height=600)
        self.win = win
        self.parent_given = parent is not None
        cand = parent if parent is not None else win.page_id()
        self.parent = cand if galerie.parent_ok(cand, store.by_id) else None
        self.items = []
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        self.stack = Gtk.Stack()
        sp = Gtk.Spinner(spinning=True, width_request=32, height_request=32, halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER)
        self.stack.add_named(sp, "load")
        self.empty = Adw.StatusPage(icon_name="view-app-grid-symbolic", title=_("No templates available"))
        self.stack.add_named(self.empty, "empty")
        self.page = Adw.PreferencesPage()
        self.stack.add_named(self.page, "list")
        self.stack.set_visible_child_name("load")
        tv.set_content(self.stack)
        self.set_child(tv)
        self.srv = config.settings.server
        bg(client.get, galerie.list_path(ui_language()), done=self.fill, error=self.failed)

    def fill(self, j):
        if self.srv != config.settings.server:
            return
        galerie.state.set(self.srv, True)
        self.items = galerie.parse_list(j)
        groups = galerie.group(self.items)
        if not groups:
            self.stack.set_visible_child_name("empty")
            return
        self.rows = {}
        for cat, head, its in groups:
            g = Adw.PreferencesGroup(title=head)
            for it in its:
                r = Adw.ActionRow(title=it["title"], subtitle=it["desc"], use_markup=False, activatable=True)
                r.set_subtitle_lines(3)
                r.add_prefix(_emoji(it["icon"]))
                r.add_suffix(Gtk.Image(icon_name="go-next-symbolic"))
                r.update_property([Gtk.AccessibleProperty.DESCRIPTION], [it["desc"]])
                r.connect("activated", lambda _r, it=it: self.confirm(it))
                g.add(r)
                self.rows[it["id"]] = r
            self.page.add(g)
        self.stack.set_visible_child_name("list")

    def failed(self, e):
        code = e.code if isinstance(e, APIError) else 0
        self.close()
        if isinstance(e, APIError) and code == 401:
            return
        if galerie.hidden_by(code) and self.srv == config.settings.server:
            galerie.state.set(self.srv, False)
            self.win.gallery_changed()
        self.win.toast(galerie.error_text(code, getattr(e, "msg", ""), "list"))

    def confirm(self, it):
        d = Adw.AlertDialog(heading=(it["icon"] + " " if it["icon"] else "") + it["title"], body=it["desc"])
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        txt = galerie.contents_text(it["contents"])
        if txt:
            lb = Gtk.Label(label=txt, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, justify=Gtk.Justification.CENTER)
            lb.add_css_class("dim-label")
            box.append(lb)
        if it["needs"]:
            lb = Gtk.Label(label=_("Some parts need features that may not be set up here: {needs}").format(needs=", ".join(galerie.needs_text(it["needs"]))),
                           wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, justify=Gtk.Justification.CENTER)
            lb.add_css_class("caption")
            lb.add_css_class("dim-label")
            box.append(lb)
        lst = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lst.add_css_class("boxed-list")
        ex = Adw.SwitchRow(title=_("With examples"), subtitle=_("Sample entries to try things out – delete them any time."), active=True)
        lst.append(ex)
        under = None
        if self.parent is not None:
            under = Adw.SwitchRow(title=_("As subpage of “{title}”").format(title=label(store.by_id.get(self.parent))),
                                  use_markup=False, active=self.parent_given)
            lst.append(under)
        box.append(lst)
        d.set_extra_child(box)
        d.add_response("cancel", _("Cancel"))
        d.add_response("use", _("Use template"))
        d.set_response_appearance("use", Adw.ResponseAppearance.SUGGESTED)
        d.set_default_response("use")
        d.set_close_response("cancel")
        self.confirm_dialog, self.examples_row, self.under_row = d, ex, under

        def resp(_d, r):
            if r == "use":
                self.prepare(it, ex.get_active(), self.parent if under is not None and under.get_active() else None)
        d.connect("response", resp)
        d.present(self)

    def prepare(self, it, examples, parent):
        """(2.5) Vorschau fragen: hat die Vorlage ein Wetter-Widget ohne Ort (ask_weather), erst den Ort waehlen lassen
        ("Ueberspringen" = ohne Ort). Scheitert die Vorschau (aelterer Server), wie bisher ohne Ort anlegen."""
        srv = self.srv
        self.busy = True

        def fin(j):
            self.busy = False
            if srv != config.settings.server:
                return
            if galerie.ask_weather(j):
                self.place_dialog = WeatherPlaceDialog(lambda place: self.use(it, examples, parent, place))
                self.place_dialog.present(self)
            else:
                self.use(it, examples, parent)

        def err(e):
            self.busy = False
            if isinstance(e, APIError) and e.code == 401:
                return
            if srv == config.settings.server:
                self.use(it, examples, parent)
        bg(client.get, galerie.preview_path(it["id"], ui_language()), done=fin, error=err)

    def use(self, it, examples, parent, place=None):
        body = galerie.use_body(parent, examples, store.by_id, place)
        win, srv = self.win, self.srv
        self.close()

        def fin(j):
            if srv != config.settings.server:
                return
            r = galerie.parse_use(j)
            if r is None:
                win.toast(galerie.error_text(500, "", "use"))
                store.refresh()
                return
            root = r[0]
            win.toast(_("Template “{title}” created").format(title=it["title"]))
            store.refresh(done=lambda: win.show_page(root, root=body["parent_id"] is None))

        def err(e):
            code = e.code if isinstance(e, APIError) else 0
            if isinstance(e, APIError) and code == 401:
                return
            win.toast(galerie.error_text(code, getattr(e, "msg", ""), "use"))
            if code == 404 and srv == config.settings.server:   # Vorlage weg oder Galerie inzwischen abgeschaltet: neu fragen
                galerie.state.set(srv, None)
                probe(win)
        bg(client.json, "POST", galerie.use_path(it["id"]), body, done=fin, error=err)


class WeatherPlaceDialog(Adw.Dialog):
    """(2.5) Ort fuer das Wetter-Widget der Vorlage: Ortssuche des Servers (GET api/widgets/geocode), Treffer waehlen legt
    mit diesem Ort an, "Ueberspringen" ohne Ort. Schliessen bricht ab (nichts wird angelegt)."""

    def __init__(self, done):
        super().__init__(title=_("Place for the weather"), content_width=420, content_height=460)
        self.done = done
        self.places = []
        self.rows = []
        self.searching = None
        self._t = 0
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin_start=18, margin_end=18, margin_top=6,
                      margin_bottom=18)
        lb = Gtk.Label(label=_("The template contains a weather widget. For which place?"), wrap=True, xalign=0)
        box.append(lb)
        self.entry = Gtk.SearchEntry(placeholder_text=_("Search place …"), hexpand=True)
        self.entry.update_property([Gtk.AccessibleProperty.LABEL], [_("Search place …")])
        self.entry.connect("activate", lambda *_: self.search())
        self.entry.connect("search-changed", lambda *_: self._soon())
        box.append(self.entry)
        self.status = Gtk.Label(xalign=0, wrap=True, visible=False)
        self.status.add_css_class("dim-label")
        box.append(self.status)
        self.list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, visible=False, valign=Gtk.Align.START)
        self.list.add_css_class("boxed-list")
        sc = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True)
        sc.set_child(self.list)
        box.append(sc)
        self.skip = Gtk.Button(label=_("Skip"), halign=Gtk.Align.CENTER,
                               tooltip_text=_("Create the template without a place – set it in the widget later"))
        self.skip.add_css_class("pill")
        self.skip.connect("clicked", lambda *_: self.pick(None))
        box.append(self.skip)
        tv.set_content(box)
        self.set_child(tv)
        self.set_focus(self.entry)
        self.connect("closed", lambda *_: self._stop())

    def _stop(self):
        if self._t:
            GLib.source_remove(self._t)
            self._t = 0

    def _soon(self):
        """beim Tippen (gebremst - die Ortssuche erlaubt 30 Anfragen je Minute)"""
        self._stop()

        def go():
            self._t = 0
            self.search()
            return False
        self._t = GLib.timeout_add(700, go)

    def _say(self, text):
        self.status.set_label(text or "")
        self.status.set_visible(bool(text))

    def search(self):
        self._stop()
        path = galerie.geocode_path(self.entry.get_text())
        if path is None or path == self.searching:
            return
        self.searching = path
        self._say(_("Searching …"))

        def fin(j):
            if self.searching != path:
                return
            self.show_places(galerie.parse_places(j))

        def err(e):
            if self.searching != path:
                return
            self.searching = None
            self.show_places([])
            self._say(galerie.geocode_error(e.code if isinstance(e, APIError) else 0))
        bg(client.get, path, done=fin, error=err)

    def show_places(self, places):
        while (ch := self.list.get_first_child()) is not None:
            self.list.remove(ch)
        self.places, self.rows = places, []
        for p in places:
            r = Adw.ActionRow(title=p["label"], use_markup=False, activatable=True)
            r.add_prefix(Gtk.Image(icon_name="mark-location-symbolic"))
            r.connect("activated", lambda _r, p=p: self.pick(p))
            self.list.append(r)
            self.rows.append(r)
        self.list.set_visible(bool(places))
        self._say("" if places else _("No place found"))

    def pick(self, p):
        """Ort gewaehlt (bzw. None = Ueberspringen): anlegen, Dialog zu"""
        if self.done is None:
            return
        done, self.done = self.done, None
        self.skip.set_sensitive(False)
        self.list.set_sensitive(False)
        self.close()
        done(galerie.place_body(p) if p else None)
