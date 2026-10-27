"""Vorlagengalerie (Saga 2.5): einfache Auswahl - Liste nach Kategorie, Bestaetigung mit "Mit Beispielen", Anlegen.
Logik (Parsing, Fehler, Anfrage) in galerie.py."""
from gi.repository import Adw, Gtk, Pango

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
                self.use(it, ex.get_active(), self.parent if under is not None and under.get_active() else None)
        d.connect("response", resp)
        d.present(self)

    def use(self, it, examples, parent):
        body = galerie.use_body(parent, examples, store.by_id)
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
