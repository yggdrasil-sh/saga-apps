"""Eine Seite nativ: Titelbild, Symbol, Titel, Tags, Eigenschaften, Bloecke, Inhaltsverzeichnis, Rueckverweise."""
from gi.repository import Adw, Gdk, Gio, GLib, Gtk, Pango

from . import images
from .api import client
from .blocks import BlockKit, HoverManager, SuggestPopover, chip, flat_button, menu_popover
from .config import settings
from .editor import PageDoc, PageEditor, make
from .i18n import _
from .richtext import RichText
from .store import is_db, label, store, watch
from .util import bg, edited_text, new_block_id, plain

GUTTER = 60


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
        self.add_overlay(self.bar)
        self._normal_bar()
        self.bar.set_opacity(0)
        mo = Gtk.EventControllerMotion()
        mo.connect("enter", lambda *_: self.bar.set_opacity(1))
        mo.connect("leave", lambda *_: (not self.repos) and self.bar.set_opacity(0))
        self.add_controller(mo)
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
        gd.connect("drag-update", lambda g, x, y: (settings.set("cover_height", int(min(600, max(100, self._h0 + y)))), self._height()))
        grip.add_controller(gd)
        self.add_overlay(grip)
        self.grip = grip
        grip.set_opacity(0)
        mo.connect("enter", lambda *_: grip.set_opacity(1))
        mo.connect("leave", lambda *_: grip.set_opacity(0))

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
        sc.connect("value-changed", lambda s: (settings.set("cover_height", int(s.get_value())), self._height()))
        bx.append(sc)
        bx.append(Gtk.Label(label=_("Applies to all pages on this computer."), xalign=0, css_classes=["caption", "dim-label"]))
        pop.set_child(bx)
        hb.set_popover(pop)
        self.bar.append(hb)
        self.bar.append(flat_button(label=_("Remove"), cb=lambda: self.view.save_fields({"cover": None})))

    def _start_repos(self):
        self.repos = True
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
        self.set_cursor_from_name(None)
        if save:
            self.view.save_fields({"cover_pos": self.pos})
        else:
            self.pos = float(self.doc.page.get("cover_pos") or 50)
            self.img.set_valign_pct(self.pos)
        self._normal_bar()

    def _drag(self, g, x, y):
        if self.repos:
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
        self.doc.on_loaded = lambda d: self.rebuild()
        self.doc.on_error = self._error
        self.doc.on_saved = lambda: self.header_changed and self.header_changed()
        if in_peek:
            self.append(self._peek_header())
            self.append(Gtk.Separator())
        self.overlay = Gtk.Overlay(vexpand=True)
        self.scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, vexpand=True)
        self.overlay.set_child(self.scroll)
        self.append(self.overlay)
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
        self.doc.load()
        store.visited(pid)

    def _pages_changed(self):
        if self.header_changed:
            self.header_changed()

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
        if not doc.fetched:                                  # Laden fehlgeschlagen: nichts Bearbeitbares zeigen
            box = Adw.StatusPage(icon_name="dialog-warning-symbolic", title=_("Page could not be loaded"),
                                 description=doc.error or "")
            again = Gtk.Button(label=_("Try again"), halign=Gtk.Align.CENTER)
            again.add_css_class("pill")
            again.add_css_class("suggested-action")
            again.connect("clicked", lambda *_: (self.spinner.set_visible(True), self.doc.load(force=True)))
            box.set_child(again)
            self.scroll.set_child(box)
            return
        self.editor.locked = bool(doc.page.get("locked"))
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
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_start=8, margin_end=GUTTER, margin_top=12, margin_bottom=40)
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
        if self.editor.locked:
            lk = Gtk.Button(label="🔒 " + _("Locked – click to unlock"), halign=Gtk.Align.START)
            lk.add_css_class("pill")
            lk.add_css_class("hk-small")
            lk.connect("clicked", lambda *_: self.set_locked(False))
            ind.append(lk)
        if not doc.is_database:
            ind.append(self._tags_row())
        if doc.parent_schema is not None:
            from .database import EditableProps
            ind.append(EditableProps(self, doc.parent_schema))
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
            col.append(self.kit.block_list(doc.root))
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
        GLib.idle_add(lambda: (self.scroll.get_vadjustment().set_value(vadj), False)[1])
        self._relayout_soon()
        self.apply_focus()
        if not self._built_once:
            self._built_once = True
            if not doc.title_raw and not doc.is_database and len(doc.root) <= 1:
                GLib.idle_add(lambda: (self.title_rt.focus_at("end"), False)[1])

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
            w = images.page_icon(ic, size=78 if ic.startswith("/files/") else 64)
            if not ic.startswith("/files/"):
                w.add_css_class("hk-page-emoji")
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
            row.set_opacity(0)
            if self.editor.locked:
                row.set_visible(False)
            mo = Gtk.EventControllerMotion()
            mo.connect("enter", lambda *_: row.set_opacity(1))
            mo.connect("leave", lambda *_: row.set_opacity(0))
            box.add_controller(mo)
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
        if self.toc is not None:
            vis = w > 1180
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
        p, ed, win = self.doc.page, self.editor, self.win
        home = store.home_page == self.id
        arch = bool(p.get("archived"))
        ents = [(edited_text(p.get("updated")) or "–", lambda: None), None,
                (("✓ " if p.get("wide") else "") + _("Full width"), lambda: self.save_fields({"wide": not p.get("wide")})),
                (_("Remove as home page") if home else _("Set as home page"), lambda: store.set_home(None if home else self.id)),
                None,
                (_("Move to …"), lambda: win.move_dialog(self.id)),
                (_("Version history …"), lambda: ed.flush(lambda: win.versions_dialog(self.id, lambda: self.doc.load(force=True)))),
                (_("Export"), [(_("As Markdown (.md)"), lambda: ed.flush(lambda: win.export_page(self.id, self.doc.title, False)))] +
                 ([(_("With subpages (.zip)"), lambda: ed.flush(lambda: win.export_page(self.id, self.doc.title, True)))] if store.has_children(self.id) else [])),
                (("✓ " if p.get("template") else "") + _("As template"), lambda: (p.__setitem__("template", not p.get("template")),
                                                                          store.patch(self.id, {"template": p["template"]}, refresh=True))),
                (_("Duplicate"), lambda: store.duplicate(self.id, done=lambda n: win.show_page(n))),
                (_("Copy link"), lambda: win.copy_text(store.page_url(self.id))),
                (_("Open in browser"), lambda: win.open_uri(store.page_url(self.id))),
                (_("Reload"), lambda: ed.flush(lambda: self.doc.load(force=True))),
                None,
                (_("Unlock page") if ed.locked else _("Lock page"), lambda: self.set_locked(not ed.locked)),
                (_("Restore from archive") if arch else _("Archive"), lambda: ed.flush(lambda: (store.set_archived(self.id, not arch), p.__setitem__("archived", not arch)))),
                (_("Move to trash"), lambda: ed.flush(lambda: store.trash(self.id, done=lambda: self.on_close() if self.in_peek and self.on_close else win.go_back_or_home())), True)]
        menu_popover(parent, ents).popup()

    # --- Rueckrufe der Bloecke ------------------------------------------------------------------
    def open_link(self, kind, value):
        from .util import link_target
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

    def choose_image_file(self, cb, multiple=False):
        fd = Gtk.FileDialog(title=_("Choose image"))
        f = Gtk.FileFilter(name=_("Images"))
        f.add_mime_type("image/*")
        st = Gio.ListStore.new(Gtk.FileFilter)
        st.append(f)
        fd.set_filters(st)

        def up(file):
            def work():
                data = file.load_bytes(None)[0].get_data()
                return self.win.upload_image(data, file.get_basename())
            bg(work, done=cb, error=lambda e: self.win.toast(_("Image not uploaded: {error}").format(error=e)))

        if multiple:
            def done(d, res):
                try:
                    files = d.open_multiple_finish(res)
                except GLib.Error:
                    return
                for i in range(files.get_n_items()):
                    up(files.get_item(i))
            fd.open_multiple(self.win, None, done)
        else:
            def done1(d, res):
                try:
                    up(d.open_finish(res))
                except GLib.Error:
                    return
            fd.open(self.win, None, done1)

    def pick_image(self, after=None):
        self.choose_image_file(lambda url: self.editor.insert_image_url(url, after=after or self.editor.focused_block), multiple=True)

    def pick_immich(self, after=None):
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
        fd = Gtk.FileDialog(title=_("Attach file"))

        def done(d, res):
            try:
                f = d.open_finish(res)
            except GLib.Error:
                return

            def work():
                data = f.load_bytes(None)[0].get_data()
                return client.upload(data, f.get_basename(), field="file")

            def fin(info):
                rr = self.editor.locate(bid)
                if rr:
                    rr[0][rr[1]] = {"id": bid, "type": "attaches", "data": {"file": info, "title": info.get("name") or f.get_basename()}}
                    self.editor.changed()
            bg(work, done=fin, error=lambda e: self.win.toast(str(e)))
        fd.open(self.win, None, done)

    def link_database(self, bid):
        from .dialogs import PagePicker
        PagePicker(self.win, _("Link database"), [p for p in store.pages if is_db(p)],
                   lambda pid: self.editor.link_database(pid, bid)).present(self.win)

    def run_button(self, cfg):
        self.win.run_button(cfg)

    # --- Tastatur / Einfuegen / Ziehen ---------------------------------------------------------------
    def _page_key(self, ctrl, kv, code, state):
        if kv in (Gdk.KEY_v, Gdk.KEY_V) and state & Gdk.ModifierType.CONTROL_MASK and not self.editor.locked:
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
                    bg(self.win.upload_image, data, "bild.png", done=lambda url: self.editor.insert_image_url(url, after=after),
                       error=lambda e: self.win.toast(_("Image not uploaded: {error}").format(error=e)))
                cb.read_texture_async(None, got)
                return True
        return False

    def _drop_files(self, t, value, x, y):
        if self.editor.locked or self.doc.is_database:
            return False
        files = value.get_files() if hasattr(value, "get_files") else []
        after = self.editor.focused_block
        for f in files:
            name = f.get_basename() or "datei"
            is_img = name.lower().rsplit(".", 1)[-1] in ("png", "jpg", "jpeg", "gif", "webp", "heic")

            def work(f=f, name=name, is_img=is_img):
                data = f.load_bytes(None)[0].get_data()
                if is_img:
                    return ("img", self.win.upload_image(data, name))
                return ("file", client.upload(data, name, field="file"))

            def fin(r, name=name):
                if r[0] == "img":
                    self.editor.insert_image_url(r[1], after=after)
                else:
                    self.editor.insert({"id": new_block_id(), "type": "attaches",
                                        "data": {"file": r[1], "title": r[1].get("name") or name}}, after=after)
            bg(work, done=fin, error=lambda e: self.win.toast(str(e)))
        return True


def _point(x, y):
    from gi.repository import Graphene
    p = Graphene.Point()
    p.x, p.y = x, y
    return p
