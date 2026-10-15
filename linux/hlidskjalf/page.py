"""Eine Seite nativ: Titelbild, Symbol, Titel, Tags, Eigenschaften, Bloecke, Inhaltsverzeichnis, Rueckverweise."""
from gi.repository import Adw, Gdk, Gio, GLib, Gtk, Pango

from . import images
from .api import client
from .blocks import BlockKit, HoverManager, SuggestPopover, flat_button, menu_popover
from .config import settings
from .editor import PageDoc, PageEditor
from .i18n import _, ngettext, pgettext
from .publish import available as publish_available
from .richtext import RichText
from .sharing import RANK, allowed, can_comment, is_owner, meta_lines, role_of, shared_by_box
from .store import is_db, label, store, watch
from .util import bg, date_time_text, edited_text, new_block_id, plain

GUTTER = 60
CSLOT = 36              # Breite des Platzes fuer die Kommentar-Zahl rechts am Block
COLLAPSE_COMMENTS = 820  # schmaler: Kommentar-Seitenleiste legt sich ueber die Seite


class BannerBox(Gtk.Box):
    """Rahmen mit dem CSS-Namen von Adw.Banner ("banner") - fuer einen Hinweis mit zwei Knoepfen im Banner-Aussehen"""
    __gtype_name__ = "HKBannerBox"


class BannerRow(Gtk.Box):
    """Innere Zeile (CSS-Name "widget" wie in Adw.Banner: banner > revealer > widget)"""
    __gtype_name__ = "HKBannerRow"


for _cls, _name in ((BannerBox, "banner"), (BannerRow, "widget")):
    try:
        _cls.set_css_name(_name)
    except (AttributeError, TypeError):            # aeltere PyGObject: dann nur die eigene Klasse hk-draft-banner
        pass


class CoverView(Gtk.Overlay):
    """Titelbild volle Breite: Aendern / Neu positionieren (ziehen) / Hoehe / Entfernen"""

    def __init__(self, view):
        super().__init__()
        self.view, self.doc = view, view.doc
        self.repos = False
        self.pos = float(self.doc.page.get("cover_pos") or 50)
        self.img = images.ImageView(self.doc.page.get("cover"), mode="cover", w=2000, valign=self.pos)
        self.img.set_hexpand(True)
        self.set_child(self.img)
        self._height()
        self.bar = Gtk.Box(spacing=0, halign=Gtk.Align.END, valign=Gtk.Align.START, margin_top=10, margin_end=10)
        self.bar.add_css_class("hk-cover-bar")
        self.bar.add_css_class("hk-reveal")
        self.add_css_class("hk-reveal-zone")
        self.add_overlay(self.bar)
        self._normal_bar()
        ro = view.editor.locked or view.remote      # gesperrt, nur lesen, andere Saga-Instanz: Titelbild nicht aendern
        self.bar.set_visible(not ro)
        dr = Gtk.GestureDrag()
        dr.connect("drag-begin", lambda g, x, y: setattr(self, "_start", self.pos))
        dr.connect("drag-update", self._drag)
        self.img.add_controller(dr)
        # Griff unten: Hoehe ziehen
        grip = Gtk.Box(halign=Gtk.Align.CENTER, valign=Gtk.Align.END, margin_bottom=6)
        grip.add_css_class("hk-cover-grip")
        grip.set_size_request(56, 8)
        grip.set_cursor_from_name("ns-resize")
        grip.set_tooltip_text(_("Drag to change the cover image height"))
        gd = Gtk.GestureDrag()
        gd.connect("drag-begin", lambda g, x, y: setattr(self, "_h0", int(settings.get("cover_height", 200))))
        # beim Ziehen nur im Speicher, auf die Platte erst nach dem Loslassen (set_soon)
        gd.connect("drag-update", lambda g, x, y: (settings.set_soon("cover_height", int(min(600, max(100, self._h0 + y)))), self._height()))
        grip.add_controller(gd)
        grip.add_css_class("hk-reveal")
        self.add_overlay(grip)
        self.grip = grip
        grip.set_visible(not ro)

    def _height(self):
        self.img.set_size_request(-1, int(settings.get("cover_height", 200)))

    def _clear_bar(self):
        while (c := self.bar.get_first_child()):
            self.bar.remove(c)

    def _normal_bar(self):
        self._clear_bar()
        ch = Gtk.MenuButton(label=_("Change"))
        ch.add_css_class("flat")
        ch.set_create_popup_func(lambda mb: mb.set_popover(menu_popover(None, [
            (_("File …"), lambda: self.view.pick_cover()), (_("From Immich …"), lambda: self.view.pick_cover_immich())])))
        self.bar.append(ch)
        self.bar.append(flat_button(label=_("Reposition"), cb=self._start_repos))
        hb = Gtk.MenuButton(label=_("Height"))
        hb.add_css_class("flat")
        pop = Gtk.Popover()
        bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=10, margin_bottom=10, margin_start=10, margin_end=10)
        bx.append(Gtk.Label(label=_("Height of the cover image"), xalign=0, css_classes=["heading"]))
        sc = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 100, 600, 10)
        sc.set_value(settings.get("cover_height", 200))
        sc.set_size_request(240, -1)
        sc.connect("value-changed", lambda s: (settings.set_soon("cover_height", int(s.get_value())), self._height()))
        bx.append(sc)
        bx.append(Gtk.Label(label=_("Applies to all pages on this computer."), xalign=0, css_classes=["caption", "dim-label"]))
        pop.set_child(bx)
        hb.set_popover(pop)
        self.bar.append(hb)
        self.bar.append(flat_button(label=_("Remove"), cb=lambda: self.view.save_fields({"cover": None})))

    def _start_repos(self):
        self.repos = True
        self.bar.add_css_class("hk-on")
        self._clear_bar()
        self.bar.append(Gtk.Label(label=_("Drag the image to position it"), margin_start=8, margin_end=8))
        sv = Gtk.Button(label=_("Save"))
        sv.add_css_class("suggested-action")
        sv.connect("clicked", lambda *_: self._end_repos(True))
        self.bar.append(sv)
        self.bar.append(flat_button(label=_("Cancel"), cb=lambda: self._end_repos(False)))
        self.set_cursor_from_name("ns-resize")

    def _end_repos(self, save):
        self.repos = False
        self.bar.remove_css_class("hk-on")
        self.set_cursor_from_name(None)
        if save:
            self.view.save_fields({"cover_pos": self.pos})
        else:
            self.pos = float(self.doc.page.get("cover_pos") or 50)
            self.img.set_valign_pct(self.pos)
        self._normal_bar()

    def _drag(self, g, x, y):
        if self.repos and not self.view.editor.locked:
            self.pos = min(100.0, max(0.0, self._start - y / 2))
            self.img.set_valign_pct(self.pos)


class IconPicker(Adw.Dialog):
    def __init__(self, view):
        super().__init__(title=_("Icon"), content_width=420, content_height=480)
        self.view = view
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin_top=12, margin_bottom=12, margin_start=12, margin_end=12)
        row = Gtk.Box(spacing=8)
        ch = Gtk.MenuButton(label="😀 " + _("Choose emoji …"))
        em = Gtk.EmojiChooser()
        em.connect("emoji-picked", lambda c, e: self._set(e))
        ch.set_popover(em)
        row.append(ch)
        row.append(flat_button(icon="image-x-generic-symbolic", label=_("Image"), cb=self._image))
        row.append(flat_button(icon="camera-photo-symbolic", label="Immich", cb=self._immich))
        box.append(row)
        from .emoji import ICON_EMOJI
        fb = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=8, min_children_per_line=8, homogeneous=True)
        for e in _graphemes(ICON_EMOJI):
            b = Gtk.Button(label=e)
            b.add_css_class("flat")
            b.add_css_class("hk-emoji-btn")
            b.connect("clicked", lambda *_, e=e: self._set(e))
            fb.append(b)
        sw = Gtk.ScrolledWindow(vexpand=True)
        sw.set_child(fb)
        box.append(sw)
        if view.doc.page.get("icon"):
            rm = Gtk.Button(label=_("Remove icon"))
            rm.add_css_class("destructive-action")
            rm.connect("clicked", lambda *_: self._set(""))
            box.append(rm)
        tv.set_content(box)
        self.set_child(tv)

    def _set(self, v):
        self.view.save_fields({"icon": v})
        self.close()

    def _image(self):
        self.view.choose_image_file(lambda url: self._set(url))

    def _immich(self):
        from .dialogs import ImmichPicker
        ImmichPicker(self.view.win, self._set).present(self.view.win)


def _wrap_labels(w):
    """Knopftexte eines Adw.AlertDialog umbrechen (libadwaita kuerzt sie sonst mit "…")"""
    c = w.get_first_child()
    while c is not None:
        if isinstance(c, Gtk.Label) and isinstance(c.get_parent(), Gtk.Button):
            c.set_ellipsize(Pango.EllipsizeMode.NONE)
            c.set_wrap(True)
            c.set_justify(Gtk.Justification.CENTER)
        else:
            _wrap_labels(c)
        c = c.get_next_sibling()


def _graphemes(s):
    out = []
    for ch in s:
        if out and (ch in "️‍" or out[-1].endswith("‍")):
            out[-1] += ch
        else:
            out.append(ch)
    return out


class PageView(Gtk.Box):
    """Seite als native Ansicht (Hauptbereich oder Peek)"""
    __gtype_name__ = "HKPageView"

    def __init__(self, win, pid, in_peek=False, on_close=None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.win, self.id, self.in_peek, self.on_close = win, pid, in_peek, on_close
        self.doc = PageDoc(pid)
        self.editor = PageEditor(self.doc, self)
        self.kit = BlockKit(self)
        self.hover = HoverManager()
        self.suggest = SuggestPopover()
        self.toggle_state = {}
        self.title_rt = None
        self.header_changed = None              # Rueckruf: Fensterleiste aktualisieren
        self._built_once = False
        self.blocks_box = None
        self.doc.on_loaded = lambda d: self.rebuild()
        self.doc.on_error = self._error
        self.doc.on_saved = lambda: (self.header_changed and self.header_changed(), self.update_banners())
        self.doc.on_conflict = self._conflict
        self.doc.on_notice = self._error
        self.doc.on_title = lambda t: self.rebuild()
        self.doc.on_copies = lambda: self.update_banners()
        self.doc.on_lost = self._lost
        self._conflict_dlg = None
        self._lost_dlg = None
        # Kommentare (1.3): Zahl je Block (offene Unterhaltungen), Bloecke mit ungelesenen, hervorgehobener Block
        self.cm_counts, self.cm_unread, self.cm_block = {}, set(), None
        self.cm_loaded = False
        self.ai_marked = set()                  # KI-Assistent: markierte Bloecke ("Verbessern", "Uebersetzen")
        self.presence = None                    # Anwesenheit (live.Presence), nur Hauptansicht, nur Seiten dieser Instanz
        if in_peek:
            self.append(self._peek_header())
            self.append(Gtk.Separator())
        self.append(self._banners())
        self.overlay = Gtk.Overlay(vexpand=True)
        # AUTOMATIC: breite Bloecke (Spalten, Tabellen) erzwingen keine Mindestbreite des Fensters, sondern scrollen
        self.scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.AUTOMATIC, vexpand=True)
        self.overlay.set_child(self.scroll)
        self.cpanel = self.csplit = None
        if in_peek:
            self.append(self.overlay)
        else:
            # Kommentare als Seitenleiste rechts (GNOME: Adw.OverlaySplitView); schmal legt sie sich ueber die Seite
            from .comments import CommentsPanel
            self.cpanel = CommentsPanel(self)
            self.csplit = Adw.OverlaySplitView(sidebar_position=Gtk.PackType.END, show_sidebar=False, pin_sidebar=True,
                                               min_sidebar_width=300, max_sidebar_width=420, sidebar_width_fraction=0.34,
                                               vexpand=True)
            self.csplit.set_content(self.overlay)
            self.csplit.set_sidebar(self.cpanel)
            self.csplit.connect("notify::show-sidebar", self._comments_toggled)
            self.append(self.csplit)
        self.spinner = Adw.Spinner() if hasattr(Adw, "Spinner") else Gtk.Spinner(spinning=True)
        self.spinner.set_size_request(32, 32)
        self.spinner.set_halign(Gtk.Align.CENTER)
        self.spinner.set_valign(Gtk.Align.CENTER)
        self.overlay.add_overlay(self.spinner)
        self.toc = None
        kc = Gtk.EventControllerKey()
        kc.set_propagation_phase(Gtk.PropagationPhase.CAPTURE)
        kc.connect("key-pressed", self._page_key)
        self.add_controller(kc)
        dt = Gtk.DropTarget.new(Gdk.FileList, Gdk.DragAction.COPY)
        dt.connect("drop", self._drop_files)
        self.overlay.add_controller(dt)
        watch(self, "pages-changed", self._pages_changed)
        self.connect("unmap", lambda *_: self.suggest.hide())
        self.connect("unmap", lambda *_: self.editor.flush())
        self.connect("unmap", lambda *_: self._stop_presence())
        self.doc.load()
        store.visited(pid)

    def _pages_changed(self):
        if self.header_changed:
            self.header_changed()

    @property
    def foreign(self):
        """Seite eines anderen Kontos (Teilen)"""
        return self.doc.foreign

    @property
    def upload_note(self):
        """Uploads in eine fremde Seite: Datei gehoert dem Eigentuemer (Kopfzeile X-Saga-Note)"""
        return self.id if self.doc.foreign else None

    def _error(self, msg):
        self.win.toast(msg)

    # --- Kopf im Peek ------------------------------------------------------------------------
    def _peek_header(self):
        bar = Gtk.Box(spacing=6, margin_start=10, margin_end=10, margin_top=6, margin_bottom=6)
        bar.append(flat_button(icon="window-close-symbolic", tooltip=_("Close (Esc)"), cb=lambda: self.on_close and self.on_close()))
        bar.append(flat_button(icon="view-fullscreen-symbolic", tooltip=_("Open as full page"),
                               cb=lambda: (self.on_close and self.on_close(), self.win.show_page(self.id))))
        lst = self.win.peek_list
        if len(lst) > 1:
            i = lst.index(self.id) if self.id in lst else -1
            up = flat_button(icon="go-up-symbolic", tooltip=_("Previous entry (Ctrl+Shift+K)"), cb=lambda: self.win.peek_step(-1))
            dn = flat_button(icon="go-down-symbolic", tooltip=_("Next entry (Ctrl+Shift+J)"), cb=lambda: self.win.peek_step(1))
            up.set_sensitive(i > 0)
            dn.set_sensitive(0 <= i < len(lst) - 1)
            bar.append(up)
            bar.append(dn)
        self.peek_title = Gtk.Label(label="", xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.END)
        self.peek_title.add_css_class("heading")
        self.peek_title.add_css_class("dim-label")
        bar.append(self.peek_title)
        self.peek_star = flat_button(icon="non-starred-symbolic", tooltip=_("Add to favorites"), cb=self.toggle_fav)
        bar.append(self.peek_star)
        mb = flat_button(icon="view-more-symbolic", tooltip=_("Page menu"))
        mb.connect("clicked", lambda b: self.page_menu(b))
        bar.append(mb)
        return bar

    # --- Aufbau ------------------------------------------------------------------------------
    def rebuild(self):
        doc = self.doc
        self.spinner.set_visible(not doc.loaded)
        if not doc.loaded:
            return
        if self.no_access:
            # Teilen: Seite gibt es nicht (mehr) oder ist nicht (mehr) mit mir geteilt - wie im Web/iOS/Android:
            # Schloss, Titel, Text, "Erneut versuchen" und "Zu „Mit mir geteilt“"; Kopfzeile ohne Titel der Seite
            from .sharing import lock_icon
            box = Adw.StatusPage(icon_name=lock_icon(), title=_("No access to this page"),
                                 description=GLib.markup_escape_text(
                                     _("The page doesn’t exist (anymore), or it is not (or no longer) shared with you.")))
            box.add_css_class("hk-noaccess")
            btns = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, halign=Gtk.Align.CENTER)
            again = Gtk.Button(label=_("Try again"))
            again.add_css_class("pill")
            again.add_css_class("suggested-action")
            again.connect("clicked", lambda *_: (self.spinner.set_visible(True), store.refresh(), self.doc.load(force=True)))
            btns.append(again)
            go = Gtk.Button(label=_("Go to “Shared with me”"))
            go.add_css_class("pill")
            go.connect("clicked", lambda *_: self.win.show_shared())
            btns.append(go)
            box.set_child(btns)
            self.scroll.set_child(box)
            self.update_banners()
            if self.header_changed:
                self.header_changed()
            return
        if not doc.fetched:                                  # Laden fehlgeschlagen: nichts Bearbeitbares zeigen
            box = Adw.StatusPage(icon_name="dialog-warning-symbolic", title=_("Page could not be loaded"),
                                 description=GLib.markup_escape_text(doc.error or ""))
            again = Gtk.Button(label=_("Try again"), halign=Gtk.Align.CENTER)
            again.add_css_class("pill")
            again.add_css_class("suggested-action")
            again.connect("clicked", lambda *_: (self.spinner.set_visible(True), self.doc.load(force=True)))
            if self.id < 0 and doc.error_code == 403:          # Foederation: oeffentlicher Name fehlt -> Einstellungen
                bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, halign=Gtk.Align.CENTER)
                st = Gtk.Button(label=_("Settings"))
                st.add_css_class("pill")
                st.connect("clicked", lambda *_: self.win.settings_dialog(page="account"))
                bx.append(again)
                bx.append(st)
                box.set_child(bx)
            else:
                box.set_child(again)
            self.scroll.set_child(box)
            return
        self.editor.locked = bool(doc.page.get("locked")) or doc.read_only     # gesperrt oder nur Lesen/Kommentieren
        if not self.cm_loaded:                               # vor dem ersten Laden der Kommentare: Zahlen vom Server
            self.cm_counts = dict(doc.page.get("comment_counts") or {})
        self.blocks_box = None
        vadj = self.scroll.get_vadjustment().get_value()
        self.hover.reset()
        self.kit.reset()
        self.suggest.hide()
        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        if doc.page.get("cover"):
            self.cover = CoverView(self)
            outer.append(self.cover)
        wide = bool(doc.page.get("wide"))
        clamp = Adw.Clamp(maximum_size=100000 if wide else 760 + 2 * GUTTER, tightening_threshold=600)
        # rechter Rand: Platz fuer die Kommentar-Zahl am Block (BlockRow.cslot, 36 px) - zusammen wie bisher GUTTER
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_start=8,
                      margin_end=GUTTER if self.cpanel is None else GUTTER - CSLOT, margin_top=12, margin_bottom=40)
        col.append(self._icon_row())
        trow = Gtk.Box()
        trow.append(Gtk.Box(width_request=44))
        self.title_rt = RichText(doc.title_raw, css="hk-title", placeholder=_("Untitled"), raw=True, editable=not self.editor.locked)
        self.title_rt.set_monospace(False)
        self.title_rt.set_hexpand(True)
        self.title_rt.on_key = self._title_key
        self.title_rt.connect("html-changed", self._title_changed)
        trow.append(self.title_rt)
        col.append(trow)
        ind = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_start=44)
        # fremde Seite, unter dem Titel: "Geteilt von … · Du kannst …", darunter "Zuletzt geaendert von … / Angelegt
        # von …" - beides gleich gross (Sekundaertext, gut lesbar)
        info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        sb = shared_by_box(doc.page)
        if sb is not None:
            info.append(sb)
        if self.remote:                                      # Foederation: Seite liegt auf einem anderen Server
            from .sharing import remote_info
            rl = Gtk.Label(label=remote_info(doc.page), xalign=0, wrap=True)
            rl.add_css_class("hk-pagemeta")
            rl.add_css_class("dim-label")
            info.append(rl)
        meta = meta_lines(doc.page)
        if meta:
            ml = Gtk.Label(label=" · ".join(meta), xalign=0, wrap=True)
            ml.add_css_class("hk-pagemeta")
            ml.add_css_class("dim-label")
            ml.set_tooltip_text(date_time_text(doc.page.get("updated")))
            info.append(ml)
        if info.get_first_child():
            ind.append(info)
        if doc.page.get("locked") and allowed(doc.page, "lock"):
            lk = Gtk.Button(label="🔒 " + _("Locked – click to unlock"), halign=Gtk.Align.START)
            lk.add_css_class("pill")
            lk.add_css_class("hk-small")
            lk.connect("clicked", lambda *_: self.set_locked(False))
            ind.append(lk)
        elif doc.page.get("locked") and not doc.read_only:    # Bearbeiten, aber vom Eigentuemer gesperrt
            lk = Gtk.Label(label=_("🔒 The owner locked this page."), xalign=0)
            lk.add_css_class("dim-label")
            lk.add_css_class("hk-small")
            ind.append(lk)
        if not doc.is_database and (not self.editor.locked or doc.page.get("tags")) and not self.remote:
            ind.append(self._tags_row())
        if doc.parent_schema is not None:
            from .database import EditableProps
            ep = EditableProps(self, doc.parent_schema)
            ep.set_sensitive(not doc.read_only)               # Eintrag einer fremden Datenbank nur lesen
            ind.append(ep)
        if ind.get_first_child():
            col.append(ind)
        if doc.error:
            er = Gtk.Label(label=doc.error, xalign=0, wrap=True, margin_start=44)
            er.add_css_class("warning")
            col.append(er)
        if doc.is_database:
            from .database import DatabaseView
            dv = DatabaseView(self.win, self.id)
            dv.set_margin_start(44)
            col.append(dv)
        else:
            self.blocks_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            self.blocks_box.append(self.kit.block_list(doc.root))
            col.append(self.blocks_box)
            filler = Gtk.Box(height_request=160)
            g = Gtk.GestureClick()
            g.connect("released", lambda *_: (not self.editor.locked) and self.editor.continue_writing())
            filler.add_controller(g)
            filler.set_cursor_from_name("text")
            col.append(filler)
            col.append(self._backlinks())
        clamp.set_child(col)
        outer.append(clamp)
        self.scroll.set_child(outer)
        self._update_toc()
        if self.header_changed:
            self.header_changed()
        if self.in_peek:
            self.peek_title.set_label(doc.title)
            self.peek_star.set_icon_name("starred-symbolic" if doc.page.get("pinned") else "non-starred-symbolic")
            self.peek_star.set_visible(allowed(doc.page, "favorite"))
        GLib.idle_add(lambda: (self.scroll.get_vadjustment().set_value(vadj), False)[1])
        self._relayout_soon()
        self.apply_focus()
        self.update_banners()
        self.update_bubbles()
        self._start_presence()
        if not self._built_once and self.cpanel is not None and (doc.page.get("comments_open") or 0) > 0:
            self.cpanel.reload()                              # Zahlen und ungelesen-Punkte am Block
        if not self._built_once:
            self._built_once = True
            if not doc.title_raw and not doc.is_database and len(doc.root) <= 1:
                GLib.idle_add(lambda: (self.title_rt.focus_at("end"), False)[1])

    def rebuild_blocks(self):
        """Nach Enter, Loeschen, Verschieben ...: nur die Blockliste neu - unveraenderte Bloecke (samt eingebetteter
        Datenbanken und Termine) werden wiederverwendet, Kopf, Tags und Rueckverweise bleiben stehen."""
        doc = self.doc
        if self.blocks_box is None or not doc.loaded or not doc.fetched or doc.is_database or \
                (bool(doc.page.get("locked")) or doc.read_only) != self.editor.locked:
            self.rebuild()
            return
        vadj = self.scroll.get_vadjustment().get_value()
        self.hover.reset()
        self.kit.reset()
        self.suggest.hide()
        lst = self.kit.block_list(doc.root)
        old = self.blocks_box.get_first_child()
        if old is not None:
            self.blocks_box.remove(old)
        self.blocks_box.append(lst)
        self.update_bubbles()
        self._update_toc()
        GLib.idle_add(lambda: (self.scroll.get_vadjustment().set_value(vadj), False)[1])
        self._relayout_soon()
        self.apply_focus()

    def _conflict(self, doc, server_page):
        """Die Seite wurde woanders geaendert, waehrend hier ungespeicherte Eingaben liegen: nichts verwerfen, fragen.
        Texte wie iOS/Android (Glossar "Einheitliche Dialoge"). Schliessen/Esc = noch nicht entscheiden: nichts wird
        ueberschrieben, der Hinweis bleibt oben an der Seite (auch bei Datenbanken - nie still "meine")."""
        if self._conflict_dlg is not None or doc.conflict is None:
            return
        if doc.conflict_by:
            self._conflict_person(doc, doc.conflict_by)
            return
        dlg = Adw.AlertDialog(heading=_("Page changed elsewhere"),
                              body=_("This page was changed on another device in the meantime. Your changes won't be lost."))
        dlg.add_response("mine", _("Keep my version"))
        dlg.add_response("theirs", _("Load server version (save mine as a copy)"))
        dlg.set_response_appearance("mine", Adw.ResponseAppearance.SUGGESTED)
        if hasattr(dlg, "set_prefer_wide_layout"):
            dlg.set_prefer_wide_layout(True)
        dlg.set_close_response("later")
        dlg.set_default_response("mine")
        self._conflict_dlg = dlg

        def resp(d, r):
            self._conflict_dlg = None
            doc.resolve(r)
            self.update_banners()

        def closed(d):                                       # ohne Antwort geschlossen: wie "spaeter"
            if self._conflict_dlg is d:
                self._conflict_dlg = None
                self.update_banners()
        dlg.connect("response", resp)
        dlg.connect("closed", closed)
        dlg.present(self.win)
        _wrap_labels(dlg)                                    # lange Knopftexte (Glossar) umbrechen statt kuerzen
        self.update_banners()

    def _conflict_person(self, doc, by):
        """Eine andere Person hat gespeichert (Teilen): Hauptknopf "Neue Version laden" - der eigene, nicht gespeicherte
        Text bleibt danach zum Kopieren sichtbar (und als lokale Kopie im Entwurf-Banner); "Meine Version behalten" nur
        mit Rueckfrage, die andere Fassung sichert der Server als Version. Esc = spaeter (Hinweis bleibt an der Seite)."""
        from .sharing import conflict_texts
        tx = conflict_texts(by)
        dlg = Adw.AlertDialog(heading=tx["heading"], body=tx["body"])
        dlg.add_response("mine", tx["keep"])
        dlg.add_response("reload", tx["reload"])
        dlg.set_response_appearance("reload", Adw.ResponseAppearance.SUGGESTED)
        dlg.set_close_response("later")
        dlg.set_default_response("reload")
        self._conflict_dlg = dlg

        def resp(d, r):
            self._conflict_dlg = None
            if r == "reload":
                self._load_new_version(doc)
            elif r == "mine":
                self._ask_overwrite(doc, tx)
            self.update_banners()

        def closed(d):
            if self._conflict_dlg is d:
                self._conflict_dlg = None
                self.update_banners()
        dlg.connect("response", resp)
        dlg.connect("closed", closed)
        dlg.present(self.win)
        _wrap_labels(dlg)
        self.update_banners()

    def _load_new_version(self, doc):
        from .sharing import TextDialog, draft_texts
        text = doc.text_now()
        doc.resolve("theirs")                                # eigene Fassung vorher als lokale Kopie
        if doc.conflict is None and text:
            title, intro = draft_texts()
            TextDialog(title, intro, text).present(self.win)

    def _ask_overwrite(self, doc, tx):
        from .publish import confirm
        confirm(self.win, tx["ask_heading"], tx["ask_body"], tx["ask_ok"], lambda: (doc.resolve("mine"), self.update_banners()),
                cancelled=self.update_banners)

    # --- Zugriff verloren (Freigabe entzogen, nur noch Lesen) ---------------------------------------------------
    def _lost(self, doc, code, text):
        from .sharing import TextDialog, lost_texts
        if self._lost_dlg is not None:
            return
        title, intro = lost_texts(code)

        def closed():
            self._lost_dlg = None
        self._lost_dlg = TextDialog(title, intro, text, on_closed=closed)
        self._lost_dlg.present(self.win)
        # Seite zeigt sofort den neuen Zustand (hinter dem Dialog): 403 schreibgeschuetzt, 404 "Kein Zugriff" - ohne
        # widerspruechliche Hinweise (Etikett "Du kannst bearbeiten", Entwurf "Wiederherstellen")
        if code == 403 and doc.fetched and RANK.get(role_of(doc.page), 0) >= RANK["edit"] and not is_owner(doc.page):
            doc.page["role"] = "view"                         # vorlaeufig, bis die Seite neu geladen ist
        self.rebuild()
        store.refresh()
        self.doc.load(force=True)                             # 403: tatsaechliche Rolle; 404: "Kein Zugriff"

    @property
    def no_access(self):
        """Ansicht "Kein Zugriff auf diese Seite": 404 beim Laden (Teilen) oder Freigabe beim Speichern entzogen"""
        doc = self.doc
        if not doc.loaded:
            return False
        if doc.lost == 404:
            return True
        return not doc.fetched and doc.error_code == 404 and bool(store.share_api or store.is_foreign(self.id))

    @property
    def remote(self):
        """Seite einer anderen Saga-Instanz (Foederation, negative ID)"""
        from .sharing import is_remote
        return self.id < 0 or is_remote(self.doc.page)

    # --- Live: Anwesenheit, fremde Aenderungen, Kommentare -------------------------------------------------------------
    def _start_presence(self):
        if self.presence is not None or self.in_peek or self.remote or not self.doc.fetched or self.no_access:
            return
        from .live import Presence
        from .sharing import can_edit
        self.presence = Presence(self.id, lambda: can_edit(self.doc.page) and not self.editor.locked,
                                 lambda: self.win.is_active() and self.win.current is self, self._presence_users,
                                 lambda rev, by: self.live_changed({"id": self.id, "rev": rev, "by": by}), lambda: self.doc.rev)
        self.presence.start()

    def _stop_presence(self):
        if self.presence is not None:
            self.presence.stop()
            self.presence = None
        if self.win.current is self:
            self.win.set_presence([])

    def _presence_users(self, users):
        if self.win.current is self:
            self.win.set_presence(users)

    def on_typed(self):
        if self.presence is not None:
            self.presence.typed()

    def window_active(self):
        if self.presence is not None:
            self.presence.beat()

    def live_presence(self, users):
        if self.presence is not None:
            self.presence.live_update(users)

    def live_changed(self, d):
        """note.changed (live) bzw. neuere Revision aus der Anwesenheit: ohne eigene ungespeicherte Eingaben still neu laden
        ("Aktualisiert – geaendert von …"), sonst Hinweis "{name} hat diese Seite geaendert" mit "Neu laden"."""
        doc = self.doc
        if not doc.fetched or self.no_access:
            return
        if d.get("gone"):
            doc.load(force=True)
            return
        rev = d.get("rev")
        if not isinstance(rev, int) or rev <= (doc.rev or 0):
            return
        by = d.get("by") if isinstance(d.get("by"), dict) else {}
        name = by.get("name") or by.get("username") or ""
        if doc.saving:                                       # eigenes Speichern laeuft: danach neu pruefen
            GLib.timeout_add(1500, lambda: (self.live_changed(d), False)[1])
            return
        if not doc.dirty and doc.conflict is None:
            old = self.doc.on_loaded

            def loaded(dd):
                self.doc.on_loaded = old
                if old:
                    old(dd)
                if name:
                    self.win.toast(_("Updated – changed by {name}").format(name=name))
            self.doc.on_loaded = loaded
            doc.load(force=True)
            return
        self.changed_banner.set_title(GLib.markup_escape_text(_("{name} changed this page").format(name=name or _("Someone"))))
        self.changed_banner.set_revealed(True)

    def _reload_from_banner(self):
        self.changed_banner.set_revealed(False)
        self.editor.flush()
        self.doc.load(force=True)                            # eigene Eingaben: der Konflikt-Dialog entscheidet

    def live_comments(self, counts):
        p = self.doc.page
        if not self.doc.fetched:
            return
        p["comments_open"] = int(counts.get("open") or 0)
        p["comments_unread"] = int(counts.get("unread") or 0)
        if isinstance(counts.get("blocks"), dict):
            p["comment_counts"] = dict(counts["blocks"])
            if not self.cm_loaded:
                self.cm_counts = dict(counts["blocks"])
        if self.cpanel is not None and (self.comments_open or self.cm_loaded):
            self.cpanel.reload()
        self.update_bubbles()
        if self.header_changed:
            self.header_changed()

    def live_reset(self):
        if self.doc.fetched and not self.doc.dirty and self.doc.conflict is None:
            self.doc.load(force=True)

    # --- KI-Assistent ----------------------------------------------------------------------------------------------
    def ai_on(self):
        from .assistant import ai
        return ai.enabled and not self.remote and self.doc.fetched

    def ai_toggle(self, bid):
        if bid in self.ai_marked:
            self.ai_marked.discard(bid)
        else:
            self.ai_marked.add(bid)
        for r in self.kit.rows():
            (r.add_css_class if r.bid in self.ai_marked else r.remove_css_class)("hk-ai-marked")

    def editor_order(self):
        return [b.get("id") for b in self.editor.flat_blocks() if isinstance(b, dict)]

    def top_block(self, bid):
        """oberster Block, der bid enthaelt (bid selbst, wenn er oben steht)"""
        for b in self.doc.root:
            if b.get("id") == bid:
                return bid
            if any(x.get("id") == bid for x in _walk_blocks(b)):
                return b.get("id")
        return bid

    # --- Kommentare ------------------------------------------------------------------------------------------------
    @property
    def comments_open(self):
        return self.csplit is not None and self.csplit.get_show_sidebar()

    def show_comments(self, block=None):
        """Seitenleiste "Kommentare": alle Unterhaltungen bzw. nur die eines Blocks"""
        if self.cpanel is None:                               # Peek: die ganze Seite oeffnen
            if self.on_close:
                self.on_close()
            self.win.show_page(self.id)
            cur = self.win.current
            if isinstance(cur, PageView) and cur.id == self.id:
                GLib.idle_add(lambda: (cur.show_comments(block), False)[1])
            return
        if not self.doc.fetched:
            return
        self.mark_comment_block(block)
        self.cpanel.open(block)
        self.csplit.set_show_sidebar(True)

    def hide_comments(self):
        if self.csplit is not None:
            self.csplit.set_show_sidebar(False)

    def toggle_comments(self):
        if self.comments_open and not self.cpanel.s.block:
            self.hide_comments()
        else:
            self.show_comments()

    def _comments_toggled(self, *_):
        if not self.csplit.get_show_sidebar():
            self.cpanel.closed()
            self.mark_comment_block(None)
        if self.header_changed:
            self.header_changed()

    def comments_changed(self, state):
        """Unterhaltungen neu geladen bzw. gesehen/erledigt: Zahlen am Block, Kopfleiste, Punkt in der Seitenleiste"""
        from .comments import block_counts
        self.cm_loaded = True
        self.cm_counts, self.cm_unread = block_counts(state.threads)
        p = self.doc.page
        p["comments_open"] = len(state.open())
        p["comments_unread"] = state.unread_open()
        p["comments_resolved"] = state.nres
        p["comment_counts"] = dict(self.cm_counts)
        if not p["comments_unread"]:
            store.set_unread(self.id, False)
        self.update_bubbles()
        if self.header_changed:
            self.header_changed()

    def mark_comment_block(self, bid):
        self.cm_block = bid
        for r in self.kit.rows():
            (r.add_css_class if r.bid == bid and bid else r.remove_css_class)("hk-cm-focus")

    def update_bubbles(self):
        """Kleine Zahl am rechten Rand der Bloecke mit offenen Unterhaltungen; sonst (beim Ueberfahren) 💬+"""
        for r in self.kit.rows():                              # KI-Assistent: markierte Bloecke
            (r.add_css_class if r.bid in self.ai_marked else r.remove_css_class)("hk-ai-marked")
        if self.cpanel is None or not self.doc.fetched:
            return
        can = can_comment(self.doc.page) and "role" in self.doc.page      # aeltere Server (ohne 'role'): keine Kommentare
        for r in self.kit.rows():
            if r.cslot is not None:
                r.set_comments(self.cm_counts.get(r.bid, 0), r.bid in self.cm_unread, can)
        self.mark_comment_block(self.cm_block)

    def block_snippet(self, bid):
        """Kurzer Text eines Blocks fuer die Kommentarliste (None: Block gibt es nicht mehr)"""
        b = self.editor.block(bid)
        if b is None:
            return None
        from .sharing import blocks_text
        s = " ".join(blocks_text([b]).split())
        if not s:
            s = _("Image") if b.get("type") == "image" else _("Block")
        return s if len(s) <= 70 else s[:69] + "…"

    def show_block(self, bid):
        """Zum Block springen und ihn kurz hervorheben"""
        row = next((r for r in self.kit.rows() if r.bid == bid), None)
        if row is None:
            return False
        ok, pt = row.compute_point(self.scroll.get_child(), _point(0, 0))
        if ok:
            self.scroll.get_vadjustment().set_value(max(0, pt.y - 80))
        row.add_css_class("hk-flash")
        GLib.timeout_add(1800, lambda: (row.remove_css_class("hk-flash"), False)[1])
        return True

    # --- Hinweise oben an der Seite: offener Konflikt, lokale Kopien -------------------------------------------------
    def _banners(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.conflict_banner = Adw.Banner(title=_("Page changed elsewhere"), button_label=_("Choose version"), revealed=False)
        self.conflict_banner.connect("button-clicked", lambda *_: self._conflict(self.doc, self.doc.conflict))
        box.append(self.conflict_banner)
        # Live (1.4): jemand anderes hat gespeichert, hier gibt es ungespeicherte Eingaben -> Hinweis + "Neu laden"
        self.changed_banner = Adw.Banner(title="", button_label=_("Reload"), revealed=False)
        self.changed_banner.connect("button-clicked", lambda *_: self._reload_from_banner())
        box.append(self.changed_banner)
        # Entwurf-Banner: zwei Knoepfe (Wiederherstellen / Verwerfen) - Adw.Banner kann nur einen; gezeichnet wie
        # Adw.Banner (CSS-Namen banner > revealer > widget: Farben von libadwaita, hell wie dunkel), "Verwerfen" flach
        self.copy_rev = Gtk.Revealer(transition_type=Gtk.RevealerTransitionType.SLIDE_DOWN)
        bar = BannerRow(spacing=8)
        bar.add_css_class("hk-draft-banner")
        self.copy_label = Gtk.Label(xalign=0, hexpand=True, wrap=True, margin_start=6)
        bar.append(self.copy_label)
        self.copy_restore = Gtk.Button(label=pgettext("draft", "Restore"), valign=Gtk.Align.CENTER)
        self.copy_restore.add_css_class("suggested-action")
        self.copy_restore.connect("clicked", lambda *_: self._copy_action(True))
        self.copy_discard = Gtk.Button(label=pgettext("draft", "Discard"), valign=Gtk.Align.CENTER)
        self.copy_discard.add_css_class("flat")
        self.copy_discard.connect("clicked", lambda *_: self._copy_action(False))
        bar.append(self.copy_restore)
        bar.append(self.copy_discard)
        self.copy_rev.set_child(bar)
        frame = BannerBox()
        frame.append(self.copy_rev)
        box.append(frame)
        self.copy_shown = None
        return box

    def update_banners(self):
        doc = self.doc
        if doc.conflict is not None and doc.conflict_by:     # andere Person: Wortlaut wie im Web
            self.conflict_banner.set_title(GLib.markup_escape_text(
                _("⚠ {name} just changed this page. Your latest changes are not saved yet.").format(name=doc.conflict_by)))
        else:
            self.conflict_banner.set_title(GLib.markup_escape_text(_("Page changed elsewhere")))
        gone = self.no_access
        self.conflict_banner.set_revealed(doc.conflict is not None and self._conflict_dlg is None and not gone)
        # lokale Kopie: "Wiederherstellen" nur, wenn ich (noch) bearbeiten darf - bei Nur-Lesen bleibt "Verwerfen"
        # (der Text stand im Dialog zum Kopieren); ohne Zugriff kein Hinweis
        cps = doc.local_copies() if doc.fetched and not gone else []
        self.copy_shown = cps[0][0] if cps else None
        if cps:
            d = cps[0][1]
            self.copy_label.set_label(_("Unsaved changes from {time}").format(time=date_time_text(d.get("time"))))
        self.copy_restore.set_visible(not doc.read_only)
        self.copy_rev.set_reveal_child(bool(cps))

    def _copy_action(self, restore):
        p = self.copy_shown
        if not p:
            return
        if restore:
            self.editor.set_focus(None)
            self.doc.restore_copy(p)
        else:
            self.doc.discard_copy(p)
        self.update_banners()

    def apply_focus(self):
        key, where = self.editor.focus, self.editor.focus_where
        if not key:
            return
        rt = self.kit.by_key.get(key)
        if rt is None and "/" not in key:
            rt = next((r for k, r in self.kit.order if k.startswith(key + "/")), None)
        if rt is not None:
            GLib.idle_add(lambda: (rt.focus_at(where), False)[1])

    def blur(self):
        self.editor.focus = None
        self.win.set_focus(None)

    def focus_title(self):
        if self.title_rt:
            self.title_rt.focus_at("end")

    def _title_key(self, rt, kv, state):
        if kv in (Gdk.KEY_Return, Gdk.KEY_KP_Enter, Gdk.KEY_Down) and not state & Gdk.ModifierType.SHIFT_MASK:
            if kv == Gdk.KEY_Down and not rt.at_last_line():
                return False
            if self.kit.order:
                self.kit.order[0][1].focus_at("start")
            elif not self.doc.is_database:
                self.editor.continue_writing()
            return True
        return False

    def _title_changed(self, rt):
        t = rt.text().replace("\n", " ")
        if t != self.doc.title_raw:
            self.doc.page["title"] = t
            self.editor.text_changed()
            if self.in_peek:
                self.peek_title.set_label(t or _("Untitled"))
            if self.header_changed:
                self.header_changed()

    def _icon_row(self):
        doc = self.doc
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_start=44)
        ic = doc.page.get("icon")
        if ic:
            w = images.page_icon(ic, size=78 if images.is_file_url(ic) else 64)
            if not images.is_file_url(ic):
                w.add_css_class("hk-page-emoji")
            if self.editor.locked or self.remote:             # gesperrt, nur lesen, andere Instanz: Symbol nur zeigen
                b = Gtk.Box(halign=Gtk.Align.START, margin_start=4)
                b.append(w)
            else:
                b = Gtk.Button(child=w, halign=Gtk.Align.START)
                b.add_css_class("flat")
                b.connect("clicked", lambda *_: IconPicker(self).present(self.win))
            if doc.page.get("cover"):
                b.add_css_class("hk-icon-over-cover")
            box.append(b)
        if not ic or not doc.page.get("cover"):
            row = Gtk.Box(spacing=12)
            row.add_css_class("hk-add-row")
            if not ic:
                row.append(flat_button(icon="face-smile-symbolic", label=_("Add icon"), css="dim-label",
                                       cb=lambda: IconPicker(self).present(self.win)))
            if not doc.page.get("cover"):
                mb = Gtk.MenuButton()
                mb.set_child(Gtk.Label(label="🖼  " + _("Add cover image")))
                mb.add_css_class("flat")
                mb.add_css_class("dim-label")
                mb.set_create_popup_func(lambda m: m.set_popover(menu_popover(None, [
                    (_("File …"), self.pick_cover), (_("From Immich …"), self.pick_cover_immich)])))
                row.append(mb)
            row.add_css_class("hk-reveal")
            box.add_css_class("hk-reveal-zone")
            if self.editor.locked or self.remote:
                row.set_visible(False)
            box.append(row)
            if not ic and not doc.page.get("cover"):
                box.set_margin_top(14)
        return box

    def _tags_row(self):
        tags = list(self.doc.page.get("tags") or [])
        fb = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, row_spacing=6, column_spacing=6, max_children_per_line=30)
        for t in tags:
            c = Gtk.Box(spacing=3)
            c.add_css_class("hk-tag")
            c.append(Gtk.Label(label="#" + t))
            if not self.editor.locked:
                x = flat_button(icon="window-close-symbolic", css="hk-tiny", cb=lambda t=t: self._set_tags([x for x in tags if x != t]))
                c.append(x)
            fb.append(c)
        if not self.editor.locked:
            add = Gtk.MenuButton(child=Gtk.Label(label="+ " + _("Tag")))
            add.add_css_class("flat")
            add.add_css_class("dim-label")
            add.add_css_class("hk-small")
            pop = Gtk.Popover()
            bx = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, margin_top=6, margin_bottom=6, margin_start=6, margin_end=6)
            en = Gtk.Entry(placeholder_text=_("Tag"))
            bx.append(en)
            sug = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            bx.append(sug)

            def fill(*_):
                while (c := sug.get_first_child()):
                    sug.remove(c)
                q = en.get_text().lower()
                for s in [k for k in store.all_tags() if k not in tags and (not q or q in k.lower())][:6]:
                    sug.append(flat_button(label="#" + s, cb=lambda s=s: (pop.popdown(), self._set_tags(tags + [s]))))
            en.connect("changed", fill)

            def submit(*_):
                t = en.get_text().strip().replace("#", "")[:30]
                pop.popdown()
                if t and t not in tags:
                    self._set_tags(tags + [t])
            en.connect("activate", submit)
            pop.connect("show", lambda *_: (en.set_text(""), fill(), en.grab_focus()))
            pop.set_child(bx)
            add.set_popover(pop)
            fb.append(add)
        return fb

    def _set_tags(self, new):
        self.doc.page["tags"] = new
        store.patch(self.id, {"tags": new}, refresh=True)
        self.rebuild()

    def _backlinks(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_start=44)

        def fin(lst):
            if not lst:
                return
            box.append(Gtk.Separator(margin_top=8, margin_bottom=8))
            h = Gtk.Label(label=_("Linked from"), xalign=0)
            h.add_css_class("caption")
            h.add_css_class("dim-label")
            box.append(h)
            for p in lst:
                r = Gtk.Box(spacing=6)
                r.append(images.page_icon(p.get("icon"), fallback="🗃️" if is_db(p) else "📄"))
                r.append(Gtk.Label(label=label(p), xalign=0))
                b = Gtk.Button(child=r, halign=Gtk.Align.START)
                b.add_css_class("flat")
                b.connect("clicked", lambda *_, i=p["id"]: self.win.show_page(i))
                box.append(b)
        bg(client.get, f"api/notes/{self.id}/backlinks", done=fin, error=lambda e: None)
        return box

    # --- Inhaltsverzeichnis --------------------------------------------------------------------
    def _update_toc(self):
        if self.toc is not None:
            self.overlay.remove_overlay(self.toc)
            self.toc = None
        if self.in_peek or self.doc.is_database:
            return
        items = []

        def walk(a):
            for b in a:
                d = b.get("data") or {}
                if b.get("type") == "header":
                    t = plain(d.get("text", "")).strip()
                    if t:
                        items.append((b["id"], t, d.get("level") or 2))
                for c in d.get("cols") or []:
                    walk(c.get("blocks") or [])
        walk(self.doc.root)
        if len(items) < 3:
            return
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, halign=Gtk.Align.END, valign=Gtk.Align.START, margin_top=70, margin_end=16)
        box.add_css_class("hk-toc")
        hd = Gtk.Label(label=_("Contents"), xalign=0)
        hd.add_css_class("caption-heading")
        hd.add_css_class("dim-label")
        box.append(hd)
        for bid, t, lvl in items:
            b = Gtk.Button(child=Gtk.Label(label=t, xalign=0, ellipsize=Pango.EllipsizeMode.END, max_width_chars=24, margin_start=(lvl - 1) * 10))
            b.add_css_class("flat")
            b.add_css_class("hk-small")
            b.connect("clicked", lambda *_, k=bid: self.scroll_to(k))
            box.append(b)
        box.set_size_request(200, -1)
        self.toc = box
        self.overlay.add_overlay(box)
        self.overlay.set_measure_overlay(box, False)
        box.set_visible(self.get_width() > 1180)

    def _relayout_soon(self):
        """Gtk.TextView meldet seine Hoehe fuer die Breite der LETZTEN Zuteilung - nach Aufbau/Breitenwechsel nachmessen"""
        def go():
            for _k, rt in self.kit.order:
                rt.queue_resize()
            if self.title_rt:
                self.title_rt.queue_resize()
            return False
        GLib.timeout_add(30, go)
        GLib.timeout_add(250, go)

    def do_size_allocate(self, w, h, b):
        Gtk.Box.do_size_allocate(self, w, h, b)
        if abs(w - getattr(self, "_last_w", 0)) > 1:
            self._last_w = w
            self._relayout_soon()
        if self.csplit is not None:
            narrow = w < COLLAPSE_COMMENTS
            if self.csplit.get_collapsed() != narrow:
                GLib.idle_add(lambda: (self.csplit.set_collapsed(narrow), False)[1])
        if self.toc is not None:
            side = 400 if self.comments_open and not self.csplit.get_collapsed() else 0
            vis = w - side > 1180
            if self.toc.get_visible() != vis:
                GLib.idle_add(lambda: (self.toc is not None and self.toc.set_visible(vis), False)[1])

    def scroll_to(self, key):
        rt = self.kit.by_key.get(key)
        if rt is None:
            return
        ok, pt = rt.compute_point(self.scroll.get_child(), _point(0, 0))
        if ok:
            self.scroll.get_vadjustment().set_value(max(0, pt.y - 20))

    # --- Menue + Felder ------------------------------------------------------------------------
    def save_fields(self, fields):
        for k, v in fields.items():
            if v is None:
                self.doc.page.pop(k, None)
            else:
                self.doc.page[k] = v
        store.patch(self.id, fields)
        self.rebuild()

    def toggle_fav(self):
        on = not self.doc.page.get("pinned")
        self.doc.page["pinned"] = on
        store.patch(self.id, {"pinned": on})
        if self.in_peek:
            self.peek_star.set_icon_name("starred-symbolic" if on else "non-starred-symbolic")
        if self.header_changed:
            self.header_changed()

    def set_locked(self, on):
        def go():
            self.doc.page["locked"] = on
            store.patch(self.id, {"locked": on})
            self.rebuild()
        if on:
            self.editor.flush(go)
        else:
            go()

    def page_menu(self, parent):
        """Seitenmenue. Fremde Seiten (Teilen): nur, was die Rolle darf - Loeschen, Verschieben, Archiv, Veroeffentlichen,
        Teilen, Versionen, Vorlage, Favorit, Sperren, Duplizieren und Startseite bleiben beim Eigentuemer."""
        from .sharing import shared_by_tooltip
        p, ed, win = self.doc.page, self.editor, self.win
        home = store.home_page == self.id
        arch = bool(p.get("archived"))
        locked = bool(p.get("locked"))
        ok = lambda a: allowed(p, a)  # noqa: E731
        info = shared_by_tooltip(p) if not ok("share") and isinstance(p.get("owner"), dict) else ""
        n_open, n_res = int(p.get("comments_open") or 0), int(p.get("comments_resolved") or 0)
        cm_label = _("Comments") + (f" ({n_open})" if n_open else "") + \
            (" · " + ngettext("{n} resolved", "{n} resolved", n_res).format(n=n_res) if n_res else "")
        ents = [(info or edited_text(p.get("updated")) or "–", lambda: None), None]
        if ok("wide"):
            ents.append((("✓ " if p.get("wide") else "") + _("Full width"), lambda: self.save_fields({"wide": not p.get("wide")})))
        if ok("home"):
            ents.append((_("Remove as home page") if home else _("Set as home page"), lambda: store.set_home(None if home else self.id)))
        if "role" in p and self.doc.fetched:                     # Server kennt Teilen + Kommentare (ab 1.3)
            ents.append((cm_label, lambda: self.show_comments()))
            if ok("share"):
                ents.append((("✓ " if p.get("shared") else "") + _("Share …"), lambda: win.share_dialog(self.id)))
        from .chat import chat
        if self.doc.fetched and ok("chat") and "role" in p and (p.get("shared") or self.foreign) and \
                chat.status in ("setup", "device", "ready"):          # Seiten-Chat (1.5): nur geteilte Seiten dieser Instanz
            from .chatview import open_page_chat
            ents.append((_("Page chat"), lambda: open_page_chat(win, self.id)))
        if self.ai_on() and ok("ai"):                                  # KI-Assistent (1.6)
            ents.append((_("AI assistant"), [(_("Summarize"), lambda: win.assistant("page", "summarize")),
                                             (_("Suggest tasks"), lambda: win.assistant("page", "tasks")),
                                             (_("Improve selected text"), lambda: win.assistant("page", "improve")),
                                             (_("Ask your pages"), lambda: win.assistant("ask"))]))
        ents.append(None)
        if ok("move"):
            ents.append((_("Move to …"), lambda: win.move_dialog(self.id)))
        if ok("versions"):
            ents.append((_("Version history …"), lambda: ed.flush(lambda: win.versions_dialog(self.id, lambda: self.doc.load(force=True)))))
        if ok("export"):                                         # Seiten anderer Saga-Instanzen: kein Export
            ents.append((_("Export"), [(_("As Markdown (.md)"), lambda: ed.flush(lambda: win.export_page(self.id, self.doc.title, False)))] +
                         ([(_("With subpages (.zip)"), lambda: ed.flush(lambda: win.export_page(self.id, self.doc.title, True)))]
                          if store.has_children(self.id) or store.shared_children(self.id) else [])))
        if ok("template"):
            ents.append((("✓ " if p.get("template") else "") + _("As template"),
                         lambda: (p.__setitem__("template", not p.get("template")), store.patch(self.id, {"template": p["template"]}, refresh=True))))
        if ok("duplicate"):
            ents.append((_("Duplicate"), lambda: store.duplicate(self.id, done=lambda n: win.show_page(n))))
        ents += [(_("Copy link"), lambda: win.copy_text(store.page_url(self.id))),
                 (_("Open in browser"), lambda: win.open_uri(store.page_url(self.id)))]
        if ok("publish") and self.doc.fetched and publish_available(p):
            ents.append((("✓ " if p.get("published") else "") + _("Publish …"), lambda: ed.flush(lambda: win.publish_dialog(self.id))))
        ents.append((_("Reload"), lambda: ed.flush(lambda: self.doc.load(force=True))))
        tail = []
        if ok("lock"):
            tail.append((_("Unlock page") if locked else _("Lock page"), lambda: self.set_locked(not locked)))
        if ok("archive"):
            tail.append((_("Restore from archive") if arch else _("Archive"),
                         lambda: ed.flush(lambda: (store.set_archived(self.id, not arch), p.__setitem__("archived", not arch)))))
        if ok("trash"):
            tail.append((_("Move to trash"), lambda: ed.flush(lambda: store.trash(
                self.id, done=lambda: self.on_close() if self.in_peek and self.on_close else win.go_back_or_home())), True))
        if tail:
            ents += [None] + tail
        menu_popover(parent, ents).popup()

    # --- Rueckrufe der Bloecke ------------------------------------------------------------------
    def open_link(self, kind, value):
        from .util import link_target
        if kind == "na":                                      # Verweis auf eine Seite ohne Zugriff: kein Link
            self.win.toast(_("Page without access"))
            return
        if kind != "a":
            return
        t = link_target(value)
        if not t:
            return
        if t[0] == "page":
            self.editor.flush()
            self.win.show_page(t[1])
        else:
            self.win.open_uri(t[1])

    def open_page(self, pid, mode=""):
        self.editor.flush()
        self.win.open_entry(pid, mode)

    def ask_link(self, rt):
        dlg = Adw.AlertDialog(heading=_("Link"), body=_("Address or page reference (/notes#12)"))
        en = Gtk.Entry(placeholder_text=_("https://… or /notes#12"))
        dlg.set_extra_child(en)
        dlg.add_response("cancel", _("Cancel"))
        dlg.add_response("remove", _("Remove link"))
        dlg.add_response("set", _("Set"))
        dlg.set_response_appearance("remove", Adw.ResponseAppearance.DESTRUCTIVE)
        dlg.set_response_appearance("set", Adw.ResponseAppearance.SUGGESTED)
        dlg.set_default_response("set")
        en.connect("activate", lambda *_: (dlg.close(), rt.set_link(en.get_text().strip())))

        def resp(d, r):
            if r == "set":
                rt.set_link(en.get_text().strip())
            elif r == "remove":
                rt.set_link(None)
        dlg.connect("response", resp)
        dlg.present(self.win)

    def remote_blocked(self):
        """Seite einer anderen Saga-Instanz: keine Uploads (Bilder, Dateien) - Hinweis statt Dateiauswahl"""
        if self.remote:
            self.win.toast(_("Not available for pages from other Saga instances."))
            return True
        return False

    def choose_image_file(self, cb, multiple=False):
        if self.remote_blocked():
            return
        fd = Gtk.FileDialog(title=_("Choose image"))
        f = Gtk.FileFilter(name=_("Images"))
        f.add_mime_type("image/*")
        st = Gio.ListStore.new(Gtk.FileFilter)
        st.append(f)
        fd.set_filters(st)

        def up(file):
            def work():
                data = file.load_bytes(None)[0].get_data()
                return self.win.upload_image(data, file.get_basename(), note=self.upload_note)
            bg(work, done=cb, error=lambda e: self.win.toast(_("Image not uploaded: {error}").format(error=e)), media=True)

        if multiple:                                            # cb bekommt die Liste (Reihenfolge wie gewaehlt)
            def done(d, res):
                try:
                    files = d.open_multiple_finish(res)
                except GLib.Error:
                    return
                fl = [files.get_item(i) for i in range(files.get_n_items())]

                def work():
                    out = []
                    for file in fl:
                        try:
                            out.append(self.win.upload_image(file.load_bytes(None)[0].get_data(), file.get_basename(), note=self.upload_note))
                        except Exception as e:  # noqa: BLE001
                            GLib.idle_add(lambda e=e: (self.win.toast(_("Image not uploaded: {error}").format(error=e)), False)[1])
                    return out
                bg(work, done=cb, media=True)
            fd.open_multiple(self.win, None, done)
        else:
            def done1(d, res):
                try:
                    up(d.open_finish(res))
                except GLib.Error:
                    return
            fd.open(self.win, None, done1)

    def pick_image(self, after=None):
        self.choose_image_file(lambda urls: self._insert_images(urls, after or self.editor.focused_block), multiple=True)

    def _insert_images(self, urls, after):
        for url in urls:                                        # der Reihe nach: jedes Bild unter das vorige
            after = self.editor.insert_image_url(url, after=after)

    def pick_immich(self, after=None):
        if self.remote_blocked():
            return
        from .dialogs import ImmichPicker
        ImmichPicker(self.win, lambda url: self.editor.insert_image_url(url, after=after)).present(self.win)

    def pick_cover(self):
        self.choose_image_file(lambda url: self.save_fields({"cover": url, "cover_pos": 50}))

    def pick_cover_immich(self):
        from .dialogs import ImmichPicker
        ImmichPicker(self.win, lambda url: self.save_fields({"cover": url, "cover_pos": 50})).present(self.win)

    def config_buttons(self, bid):
        from .dialogs import ButtonsConfig
        b = self.editor.block(bid)
        if b:
            ButtonsConfig(b.setdefault("data", {}), self.editor.changed).present(self.win)

    def ask_embed(self, bid):
        dlg = Adw.AlertDialog(heading=_("Embed"), body=_("YouTube video or any link (shown as a card)"))
        en = Gtk.Entry(placeholder_text="https://…")
        dlg.set_extra_child(en)
        dlg.add_response("cancel", _("Cancel"))
        dlg.add_response("ok", _("Embed"))
        dlg.set_response_appearance("ok", Adw.ResponseAppearance.SUGGESTED)
        dlg.set_default_response("ok")

        def resp(d, r):
            u = en.get_text().strip()
            if r == "ok" and u.startswith(("http://", "https://")):
                rr = self.editor.locate(bid)
                if rr:
                    rr[0][rr[1]] = {"id": bid, "type": "embed", "data": {"url": u}}
                    self.editor.changed()
        dlg.connect("response", resp)
        dlg.present(self.win)

    def pick_file(self, bid):
        if self.remote_blocked():
            return
        fd = Gtk.FileDialog(title=_("Attach file"))

        def done(d, res):
            try:
                f = d.open_finish(res)
            except GLib.Error:
                return

            def work():
                data = f.load_bytes(None)[0].get_data()
                return client.upload(data, f.get_basename(), field="file", note=self.upload_note)

            def fin(info):
                rr = self.editor.locate(bid)
                if rr:
                    rr[0][rr[1]] = {"id": bid, "type": "attaches", "data": {"file": info, "title": info.get("name") or f.get_basename()}}
                    self.editor.changed()
            bg(work, done=fin, error=lambda e: self.win.toast(str(e)), media=True)
        fd.open(self.win, None, done)

    def link_database(self, bid):
        from .dialogs import PagePicker
        if self.doc.foreign:                                  # fremde Seite: nur geteilte Datenbanken desselben Eigentuemers
            own = (self.doc.page.get("owner") or {}).get("username")
            dbs = [p for p in store.shared if is_db(p) and (p.get("owner") or {}).get("username") == own]
        else:
            dbs = [p for p in store.pages if is_db(p)]
        PagePicker(self.win, _("Link database"), dbs, lambda pid: self.editor.link_database(pid, bid)).present(self.win)

    def run_button(self, cfg):
        self.win.run_button(cfg)

    # --- Tastatur / Einfuegen / Ziehen ---------------------------------------------------------------
    def _page_key(self, ctrl, kv, code, state):
        foc = self.win.get_focus() if self.win is not None else None
        if self.cpanel is not None and foc is not None and foc.is_ancestor(self.cpanel):
            return False                                      # Eingabe im Kommentarfeld: nichts in die Seite einfuegen
        if kv in (Gdk.KEY_v, Gdk.KEY_V) and state & Gdk.ModifierType.CONTROL_MASK and not self.editor.locked and not self.remote:
            cb = self.get_clipboard()
            fm = cb.get_formats()
            if fm.contain_gtype(Gdk.Texture) and not fm.contain_mime_type("text/plain") and not fm.contain_mime_type("text/plain;charset=utf-8"):
                after = self.editor.focused_block

                def got(c, res):
                    try:
                        tex = c.read_texture_finish(res)
                    except GLib.Error:
                        return
                    if tex is None:
                        return
                    data = tex.save_to_png_bytes().get_data()
                    bg(self.win.upload_image, data, "bild.png", self.upload_note, done=lambda url: self.editor.insert_image_url(url, after=after),
                       error=lambda e: self.win.toast(_("Image not uploaded: {error}").format(error=e)), media=True)
                cb.read_texture_async(None, got)
                return True
        return False

    def _drop_files(self, t, value, x, y):
        if self.editor.locked or self.doc.is_database or self.remote_blocked():
            return False
        files = list(value.get_files()) if hasattr(value, "get_files") else []
        after = self.editor.focused_block

        def work():                                             # nacheinander hochladen: Reihenfolge bleibt wie abgelegt
            out = []
            for f in files:
                name = f.get_basename() or "datei"
                is_img = name.lower().rsplit(".", 1)[-1] in ("png", "jpg", "jpeg", "gif", "webp", "heic")
                try:
                    data = f.load_bytes(None)[0].get_data()
                    out.append(("img", self.win.upload_image(data, name, note=self.upload_note), name) if is_img else
                               ("file", client.upload(data, name, field="file", note=self.upload_note), name))
                except Exception as e:  # noqa: BLE001
                    GLib.idle_add(lambda e=e: (self.win.toast(str(e)), False)[1])
            return out

        def fin(results):
            a = after
            for kind, val, name in results:
                if kind == "img":
                    a = self.editor.insert_image_url(val, after=a) or a
                else:
                    a = self.editor.insert({"id": new_block_id(), "type": "attaches",
                                            "data": {"file": val, "title": val.get("name") or name}}, after=a)
        if files:
            bg(work, done=fin, media=True)
        return True


def _walk_blocks(b):
    out = []
    d = b.get("data") or {}
    for x in d.get("blocks") or []:
        if isinstance(x, dict):
            out += [x] + _walk_blocks(x)
    for c in d.get("cols") or []:
        for x in (c.get("blocks") or []) if isinstance(c, dict) else []:
            if isinstance(x, dict):
                out += [x] + _walk_blocks(x)
    return out


def _point(x, y):
    from gi.repository import Graphene
    p = Graphene.Point()
    p.x, p.y = x, y
    return p
