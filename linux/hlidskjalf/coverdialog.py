"""Titelbild-Galerie (Saga 2.5) in der Linux-App: Dialog "Titelbild-Galerie", "Zufaellig", "Ueber dieses Bild".
Wie Web/iOS/Android: Liste GET api/covers (einmal je Server und Sprache im Hintergrund, beim Oeffnen frisch), Setzen per
PATCH cover + empfohlene cover_pos. Aelterer Server (404) bzw. abgeschaltet (403): Eintraege ausgeblendet.
Logik (Parsing, Gruppen, Zufall, Menue) in covergallery.py."""
from gi.repository import Adw, GLib, Gtk, Pango

from . import config, covergallery as cg, images
from .api import APIError, client
from .i18n import _, pgettext, ui_language
from .util import bg

TILE_W, TILE_H = 168, 96                       # Vorschau 7:4 wie Web/Android


def _srv():
    return config.settings.server


def probe(after=None):
    """Im Hintergrund fragen, ob der Server die Galerie anbietet (einmal je Server und Sprache). after(): danach"""
    srv, lang = _srv(), ui_language()
    if not cg.state.begin(srv, lang):
        return

    def fin(j):
        listing = cg.parse(j)
        if listing is None:
            cg.state.failed(srv, lang)
        else:
            cg.state.record(srv, lang, 200, listing)
        if after and srv == _srv():
            after()

    def err(e):
        if isinstance(e, APIError):
            cg.state.record(srv, lang, getattr(e, "code", 0))
        else:
            cg.state.failed(srv, lang)
        if after and srv == _srv():
            after()
    bg(client.get, cg.LIST_PATH, done=fin, error=err)


def offered():
    return cg.state.offered(_srv(), ui_language()) is True


def listing():
    return cg.state.cached(_srv(), ui_language())


def menu_entries(view, current):
    """Eintraege fuer menu_popover (Titelbild-Leiste "Aendern" bzw. "Titelbild hinzufuegen")"""
    keys = cg.menu_keys(current, offered(), locked=view.editor.locked, remote=view.remote, listing=listing())
    acts = {
        "gallery": (_("Gallery …"), lambda: CoverGalleryDialog(view).present(view.win)),
        "random": (_("Random"), lambda: random_cover(view)),
        "file": (_("File …"), view.pick_cover),
        "immich": (_("From Immich …"), view.pick_cover_immich),
        "about": (_("About this image"), lambda: show_about(view)),
    }
    out = []
    for k in keys:
        if k == "about":
            out.append(None)                         # Trenner vor "Ueber dieses Bild"
        out.append(acts[k])
    return out


def set_cover(view, c):
    """Galerie-Bild als Titelbild: sofort zeigen und speichern; schon gesetzt -> nichts tun"""
    if view.editor.locked:
        return
    f = cg.fields(c, view.doc.page.get("cover"))
    if f:
        view.save_fields(f)


def random_cover(view):
    """"Zufaellig": ein anderes Galerie-Bild samt empfohlener Position"""
    c = cg.pick_random(listing(), view.remote, view.doc.page.get("cover"))
    if c is None:
        view.win.toast(_("No images in the gallery."))
        return
    set_cover(view, c)


def about_dialog(view):
    """Adw.AlertDialog "Ueber dieses Bild": Titel, Kuenstler/Datum, Nachweis, "Zur Quelle"; nicht mehr in der Auswahl -> Hinweis"""
    kind, c = cg.about(listing(), view.doc.page.get("cover"))
    d = Adw.AlertDialog(heading=_("About this image"))
    if kind == "image":
        lines = [f"<b>{GLib.markup_escape_text(c['title'])}</b>"]
        for t in (cg.credit(c), c["source"]):
            if t:
                lines.append(GLib.markup_escape_text(t))
        d.set_body_use_markup(True)
        d.set_body("\n".join(lines))
    else:
        d.set_body(_("An image from the cover gallery (no longer offered)."))
    if kind == "image" and c["source_url"]:
        d.add_response("source", _("View source ↗"))
        d.connect("response", lambda _d, r: view.win.open_uri(c["source_url"]) if r == "source" else None)
    d.add_response("close", _("Close"))
    d.set_default_response("close")
    d.set_close_response("close")
    return d


def show_about(view):
    about_dialog(view).present(view.win)


class CoverGalleryDialog(Adw.Dialog):
    """Auswahl nach Gruppen: Knopf "Zufaellig" oben, je Gruppe ein Abschnitt mit Vorschaubildern (Ausschnitt nach pos),
    Hinweis zur Herkunft unten. Auswahl setzt das Titelbild und schliesst."""

    def __init__(self, view):
        super().__init__(title=_("Cover gallery"), content_width=760, content_height=640)
        self.view = view
        self.srv, self.lang = _srv(), ui_language()
        self.tiles = {}                              # id -> FlowBoxChild
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        self.random_btn = Gtk.Button(child=Adw.ButtonContent(icon_name="media-playlist-shuffle-symbolic", label=_("Random")))
        self.random_btn.set_tooltip_text(_("Random"))
        self.random_btn.connect("clicked", lambda *_: self._random())
        hb.pack_start(self.random_btn)
        tv.add_top_bar(hb)
        self.stack = Gtk.Stack()
        sp = Gtk.Spinner(spinning=True, width_request=32, height_request=32, halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER)
        self.stack.add_named(sp, "load")
        self.empty = Adw.StatusPage(icon_name="image-x-generic-symbolic", title=_("No images in the gallery."))
        self.stack.add_named(self.empty, "empty")
        retry = Gtk.Button(label=_("Try again"), halign=Gtk.Align.CENTER)
        retry.add_css_class("pill")
        retry.connect("clicked", lambda *_: self._load())
        self.failed = Adw.StatusPage(icon_name="dialog-warning-symbolic", title=_("The gallery could not be loaded."), child=retry)
        self.stack.add_named(self.failed, "failed")
        self.scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, vexpand=True)
        self.stack.add_named(self.scroll, "list")
        tv.set_content(self.stack)
        self.set_child(tv)
        cached = cg.state.cached(self.srv, self.lang)
        if cached is not None:
            self.fill(cached)
        else:
            self.stack.set_visible_child_name("load")
            self.random_btn.set_sensitive(False)
        self._load()

    # --- Laden --------------------------------------------------------------------------------------------
    def _load(self):
        if self.stack.get_visible_child_name() == "failed":
            self.stack.set_visible_child_name("load")
        bg(client.get, cg.LIST_PATH, done=self._loaded, error=self._error)

    def _loaded(self, j):
        if self.srv != _srv():
            return
        listing = cg.parse(j)
        if listing is None:
            return self._error(None)
        cg.state.record(self.srv, self.lang, 200, listing)
        self.fill(listing)

    def _error(self, e):
        if self.srv != _srv():
            return
        code = getattr(e, "code", 0) if isinstance(e, APIError) else 0
        if cg.offered_code(code) is False:            # aelterer Server bzw. abgeschaltet: nichts anbieten
            cg.state.record(self.srv, self.lang, code)
            self.view.win.toast(_("The gallery could not be loaded."))
            self._close_now()
            return
        if cg.state.cached(self.srv, self.lang) is None:
            self.stack.set_visible_child_name("failed")

    def _close_now(self, tries=10):
        """schliessen - waehrend der Einblende-Animation uebergeht libadwaita das Schliessen, dann kurz spaeter nochmal"""
        self.force_close()
        if tries > 0 and self.view.win.get_visible_dialog() is self:
            GLib.timeout_add(100, lambda: self._close_now(tries - 1) and False)

    # --- Anzeige ------------------------------------------------------------------------------------------
    def fill(self, listing):
        self.listing = listing
        groups = cg.grouped(listing, self.view.remote, pgettext("cover gallery", "More"))
        self.random_btn.set_sensitive(bool(groups))
        if not groups:
            self.stack.set_visible_child_name("empty")
            return
        current = self.view.doc.page.get("cover")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=12, margin_bottom=18, margin_start=18, margin_end=18)
        self.tiles = {}
        self.flows = []
        for gid, name, items in groups:
            h = Gtk.Label(label=name, xalign=0, margin_top=10, css_classes=["heading"], accessible_role=Gtk.AccessibleRole.HEADING)
            box.append(h)
            fb = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, homogeneous=True, min_children_per_line=2,
                             max_children_per_line=8, row_spacing=10, column_spacing=10, activate_on_single_click=True)
            fb.update_property([Gtk.AccessibleProperty.LABEL], [name])
            fb.connect("child-activated", lambda _fb, ch: self._choose(ch.cover))
            for c in items:
                ch = self._tile(c, c["url"] == current)
                fb.append(ch)
                self.tiles[c["id"]] = ch
            box.append(fb)
            self.flows.append(fb)
        if listing.get("source"):
            box.append(Gtk.Label(label=_("Bundled artworks: public domain, {src}. The Saga images are AI-generated.").format(
                src=listing["source"]), xalign=0, wrap=True, margin_top=14, css_classes=["caption", "dim-label"]))
        self.scroll.set_child(box)
        self.stack.set_visible_child_name("list")

    def _tile(self, c, on):
        ch = Gtk.FlowBoxChild()
        ch.cover = c
        ch.add_css_class("hk-cover-tile")
        if on:
            ch.add_css_class("hk-on")
        lab = cg.label(c)
        ch.update_property([Gtk.AccessibleProperty.LABEL], [lab])
        ch.set_tooltip_text(lab)
        v = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        ov = Gtk.Overlay()
        img = images.ImageView(c["thumb"], mode="cover", valign=c["pos"], radius=8)
        img.set_size_request(TILE_W, TILE_H)
        img.add_css_class("hk-cover-thumb")
        ov.set_child(img)
        if on:
            mark = Gtk.Image(icon_name="object-select-symbolic", halign=Gtk.Align.END, valign=Gtk.Align.START,
                             margin_top=6, margin_end=6, css_classes=["hk-cover-check"])
            ov.add_overlay(mark)
        v.append(ov)
        t = Gtk.Label(label=c["title"], xalign=0, ellipsize=Pango.EllipsizeMode.END, max_width_chars=18, margin_start=4, margin_end=4)
        v.append(t)
        cr = cg.credit(c)
        if cr:
            v.append(Gtk.Label(label=cr, xalign=0, ellipsize=Pango.EllipsizeMode.END, max_width_chars=18, margin_start=4,
                               margin_end=4, margin_bottom=4, css_classes=["caption", "dim-label"]))
        ch.set_child(v)
        return ch

    # --- Auswahl ------------------------------------------------------------------------------------------
    def _choose(self, c):
        self.close()
        set_cover(self.view, c)

    def _random(self):
        c = cg.pick_random(getattr(self, "listing", None), self.view.remote, self.view.doc.page.get("cover"))
        if c is not None:
            self._choose(c)
