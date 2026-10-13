"""Bloecke als Widgets + Tastatur wie Notion (Enter teilt, Ruecktaste verbindet, Pfeile springen, Tab rueckt ein)."""
from gi.repository import Gdk, GLib, GObject, Gtk, Pango

from . import images
from .config import settings
from .editor import CONVERTIBLE, SLASH_ITEMS, make
from .emoji import EMOJI
from .i18n import _
from .richtext import RichText, date_text, find_trigger, html_to_markup, parse_date, rel_text
from .store import is_db, label, store
from .util import color_hex, decode_entities, human_size, norm, plain

TEXT_FIELD = {"paragraph": "text", "header": "text", "quote": "text", "callout": "text"}
HEADER_CSS = {1: "hk-h1", 2: "hk-h2", 3: "hk-h3", 4: "hk-h4"}
MD_SHORTCUTS = {"# ": ("header", 1), "## ": ("header", 2), "### ": ("header", 3), "#### ": ("header", 4),
                "- ": ("unordered", 0), "* ": ("unordered", 0), "1. ": ("ordered", 0), "[] ": ("checklist", 0),
                "[ ] ": ("checklist", 0), "> ": ("quote", 0), "```": ("code", 0), "---": ("delimiter", 0)}
CTRL, SHIFT, ALT = Gdk.ModifierType.CONTROL_MASK, Gdk.ModifierType.SHIFT_MASK, Gdk.ModifierType.ALT_MASK


def flat_button(label=None, icon=None, tooltip=None, css=None, cb=None):
    b = Gtk.Button()
    if icon and label:
        bx = Gtk.Box(spacing=6)
        bx.append(Gtk.Image.new_from_icon_name(icon))
        bx.append(Gtk.Label(label=label, xalign=0))
        b.set_child(bx)
    elif icon:
        b.set_icon_name(icon)
    else:
        b.set_label(label or "")
    b.add_css_class("flat")
    if css:
        for c in css.split():
            b.add_css_class(c)
    if tooltip:
        b.set_tooltip_text(tooltip)
    if cb:
        b.connect("clicked", lambda *_: cb())
    return b


def chip(text, color=None):
    lb = Gtk.Label(label=text)
    lb.add_css_class("hk-chip")
    lb.add_css_class(f"hk-c-{color or 'gray'}")
    return lb


def menu_popover(parent, entries):
    """Einfaches Menue: entries = [(Text, Rueckruf, destruktiv?) | None (Trenner) | (Text, [Unter-Eintraege])]"""
    pop = Gtk.Popover()
    pop.add_css_class("menu")
    stack = Gtk.Stack(transition_type=Gtk.StackTransitionType.SLIDE_LEFT_RIGHT, vhomogeneous=False, hhomogeneous=False, interpolate_size=True)

    def page(items, name, back=False):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        if back:
            b = flat_button(icon="go-previous-symbolic", label=_("Back"), cb=lambda: stack.set_visible_child_name("main"))
            box.append(b)
            box.append(Gtk.Separator())
        for e in items:
            if e is None:
                box.append(Gtk.Separator(margin_top=3, margin_bottom=3))
                continue
            text, act = e[0], e[1]
            if isinstance(act, list):
                sub = f"sub{len(stack.get_pages())}"
                page(act, sub, back=True)
                bx = Gtk.Box(spacing=6)
                bx.append(Gtk.Label(label=text, xalign=0, hexpand=True))
                bx.append(Gtk.Image.new_from_icon_name("go-next-symbolic"))
                btn = Gtk.Button(child=bx)
                btn.add_css_class("flat")
                btn.connect("clicked", lambda *_, s=sub: stack.set_visible_child_name(s))
            else:
                btn = Gtk.Button(child=Gtk.Label(label=text, xalign=0))
                btn.add_css_class("flat")
                if len(e) > 2 and e[2]:
                    btn.add_css_class("error")

                def run(*_, a=act):
                    pop.popdown()
                    GLib.idle_add(lambda: (a(), False)[1])
                btn.connect("clicked", run)
            box.append(btn)
        stack.add_named(box, name)
    page(entries, "main")
    stack.set_visible_child_name("main")
    pop.set_child(stack)
    if parent is not None:                    # freies Menue: nach dem Schliessen abhaengen; im MenuButton: parent=None
        pop.set_parent(parent)
        pop.connect("closed", lambda p: GLib.idle_add(lambda: (p.unparent(), False)[1]))
    return pop


class HoverManager:
    """Griffe (+ ⋮⋮) nur am innersten Block unter der Maus"""

    def __init__(self):
        self.rows = []
        self.active = None

    def enter(self, row):
        if row not in self.rows:
            self.rows.append(row)
        self._update()

    def leave(self, row):
        if row in self.rows:
            self.rows.remove(row)
        GLib.timeout_add(250, self._update)

    def _update(self):
        best = max(self.rows, key=lambda r: r.depth) if self.rows else None
        if best is not self.active:
            if self.active is not None:
                self.active.show_handle(False)
            self.active = best
            if best is not None:
                best.show_handle(True)
        return False

    def reset(self):
        self.rows, self.active = [], None


class SuggestPopover:
    """Auswahlliste fuer "/", "@" und ":" - bleibt offen, waehrend man weitertippt"""

    def __init__(self):
        self.pop = None
        self.items = []
        self.sel = 0
        self.on_pick = None
        self.rt = None
        self.kind = None
        self.closed_for = None

    @property
    def visible(self):
        return self.pop is not None and self.pop.get_visible()

    def show(self, rt, kind, items, on_pick):
        """items: [(Symbol/Emoji, Titel, Hinweis, Wert)]"""
        if not items:
            self.hide()
            return
        if self.pop is None or self.rt is not rt:
            self.hide()
            self.pop = Gtk.Popover(autohide=False, has_arrow=False, position=Gtk.PositionType.BOTTOM)
            self.pop.set_can_focus(False)
            self.pop.add_css_class("hk-suggest")
            self.pop.set_parent(rt)
            self.rt = rt
        self.kind, self.items, self.on_pick = kind, items, on_pick
        self.sel = min(self.sel, len(items) - 1)
        lb = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        for i, (ic, title, hint, val) in enumerate(items[:12]):
            row = Gtk.Box(spacing=8)
            if ic and (ic.endswith("-symbolic")):
                row.append(Gtk.Image.new_from_icon_name(ic))
            else:
                row.append(Gtk.Label(label=ic or "", width_chars=2))
            t = Gtk.Label(label=title, xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.END, max_width_chars=36)
            row.append(t)
            if hint:
                h = Gtk.Label(label=hint)
                h.add_css_class("dim-label")
                h.add_css_class("caption")
                row.append(h)
            b = Gtk.Button(child=row)
            b.add_css_class("flat")
            b.set_can_focus(False)
            if i == self.sel:
                b.add_css_class("hk-sel")
            b.connect("clicked", lambda *_, v=val: self._pick(v))
            lb.append(b)
        sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True, max_content_height=340)
        sw.set_child(lb)
        sw.set_size_request(300, -1)
        self.pop.set_child(sw)
        self.pop.set_pointing_to(rt.cursor_rect())
        self.pop.popup()

    def move(self, d):
        if self.items:
            self.sel = (self.sel + d) % min(len(self.items), 12)
            self.show(self.rt, self.kind, self.items, self.on_pick)

    def pick_current(self):
        if self.items:
            self._pick(self.items[min(self.sel, len(self.items) - 1)][3])

    def _pick(self, v):
        cb = self.on_pick
        self.hide()
        if cb:
            cb(v)

    def hide(self):
        if self.pop is not None:
            self.pop.popdown()
            self.pop.unparent()
        self.pop, self.rt, self.items, self.sel = None, None, [], 0


class BlockRow(Gtk.Box):
    """Ein Block mit Griffen links (Maus drueber) und Ablage-Ziel fuer Ziehen"""
    __gtype_name__ = "HKBlockRow"

    def __init__(self, kit, block, depth):
        super().__init__(spacing=0)
        self.kit, self.block, self.depth = kit, block, depth
        self.bid = block.get("id")
        self.add_css_class("hk-block-row")
        self.handle = Gtk.Box(spacing=0, valign=Gtk.Align.START)
        self.handle.add_css_class("hk-handle")
        self.handle.set_size_request(44, -1)
        if not kit.view.editor.locked:
            plus = flat_button(icon="list-add-symbolic", tooltip=_("Insert block below"), css="hk-tiny",
                               cb=lambda: kit.editor.insert(make("paragraph"), after=self.bid))
            grip = Gtk.Button(icon_name="view-app-grid-symbolic", tooltip_text=_("Drag to move · Click for the menu"))
            grip.add_css_class("flat")
            grip.add_css_class("hk-tiny")
            grip.connect("clicked", lambda b: kit.block_menu(b, self.bid))
            ds = Gtk.DragSource(actions=Gdk.DragAction.MOVE)
            ds.connect("prepare", lambda *_: Gdk.ContentProvider.new_for_value("hkblock:" + self.bid))
            grip.add_controller(ds)
            self.handle.append(plus)
            self.handle.append(grip)
        self.handle.set_opacity(0)
        self.append(self.handle)
        content = kit.build(block, depth)
        content.set_hexpand(True)
        self.append(content)
        mo = Gtk.EventControllerMotion()
        mo.connect("enter", lambda *_: kit.view.hover.enter(self))
        mo.connect("leave", lambda *_: kit.view.hover.leave(self))
        self.add_controller(mo)
        if not kit.view.editor.locked:
            dt = Gtk.DropTarget.new(GObject.TYPE_STRING, Gdk.DragAction.MOVE)
            dt.connect("motion", self._motion)
            dt.connect("leave", lambda *_: self._mark(None))
            dt.connect("drop", self._drop)
            self.add_controller(dt)

    def show_handle(self, on):
        self.handle.set_opacity(1 if on else 0)

    def _mark(self, where):
        self.remove_css_class("hk-drop-top")
        self.remove_css_class("hk-drop-bottom")
        if where:
            self.add_css_class("hk-drop-" + where)

    def _motion(self, t, x, y):
        self._mark("top" if y < self.get_height() / 2 else "bottom")
        return Gdk.DragAction.MOVE

    def _drop(self, t, value, x, y):
        self._mark(None)
        if isinstance(value, str) and value.startswith("hkblock:"):
            src = value[8:]
            if src != self.bid:
                GLib.idle_add(lambda: (self.kit.editor.move_to(src, self.bid, y < self.get_height() / 2), False)[1])
                return True
        return False


class BlockKit:
    """Baut die Block-Widgets einer Seite und behandelt die Tastatur"""

    def __init__(self, view):
        self.view = view
        self.editor = view.editor
        self.order = []          # Fokus-Reihenfolge: [(key, RichText)]
        self.by_key = {}

    def reset(self):
        self.order, self.by_key = [], {}

    def register(self, key, rt):
        self.order.append((key, rt))
        self.by_key[key] = rt

    # --- Aufbau -------------------------------------------------------------------------------
    def block_list(self, arr, depth=0):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        for b in arr:
            if isinstance(b, dict):
                box.append(BlockRow(self, b, depth))
        return box

    def _rt(self, key, block, field, html, css="hk-p", placeholder="", base_bold=False, raw=False, item=None):
        rt = RichText(html, css=css, placeholder=placeholder, base_bold=base_bold, raw=raw, editable=not self.editor.locked)
        rt.set_hexpand(True)
        rt.hk_key, rt.hk_block, rt.hk_field, rt.hk_item = key, block, field, item
        rt.on_key = self.on_key
        rt.on_link = self.view.open_link
        rt.connect("html-changed", self.on_changed)
        fc = Gtk.EventControllerFocus()
        fc.connect("enter", lambda *_: self._focused(rt))
        rt.add_controller(fc)
        self.register(key, rt)
        return rt

    def _focused(self, rt):
        self.editor.focus = rt.hk_key
        if self.view.suggest.rt is not None and self.view.suggest.rt is not rt:
            self.view.suggest.hide()

    def build(self, b, depth):
        t = b.get("type") or ""
        d = b.get("data")
        if not isinstance(d, dict):
            d = b["data"] = {}
        bid = b.get("id")
        if t == "paragraph":
            rt = self._rt(bid, b, "text", d.get("text", ""), placeholder=_("Type something or “/” for blocks …"))
            rt.set_placeholder(rt.placeholder_text, always=False)
            return rt
        if t == "header":
            lvl = d.get("level") or 2
            rt = self._rt(bid, b, "text", d.get("text", ""), css=HEADER_CSS.get(lvl, "hk-h4"), placeholder=_("Heading {n}").format(n=lvl), base_bold=True)
            return rt
        if t == "list":
            return self._list(b, d.get("items") or [], d.get("style") or "unordered", [])
        if t == "quote":
            box = Gtk.Box(spacing=10)
            box.add_css_class("hk-quote")
            box.append(self._rt(bid, b, "text", d.get("text", ""), css="hk-p hk-italic", placeholder=_("Quote")))
            return box
        if t == "callout":
            box = Gtk.Box(spacing=10)
            box.add_css_class("hk-callout")
            em = Gtk.MenuButton(child=Gtk.Label(label=d.get("emoji") or "💡"), valign=Gtk.Align.START)
            em.add_css_class("flat")
            ch = Gtk.EmojiChooser()
            ch.connect("emoji-picked", lambda c, e: (d.__setitem__("emoji", e), em.get_child().set_label(e), self.editor.text_changed()))
            em.set_popover(ch)
            em.set_sensitive(not self.editor.locked)
            box.append(em)
            rt = self._rt(bid, b, "text", d.get("text", ""), placeholder=_("Callout"))
            rt.set_hexpand(True)
            box.append(rt)
            return box
        if t == "code":
            ov = Gtk.Overlay()
            rt = self._rt(bid, b, "code", d.get("code", ""), css="hk-code", placeholder=_("Code"), raw=True)
            ov.set_child(rt)
            if d.get("language"):
                lg = Gtk.Label(label=d["language"], halign=Gtk.Align.END, valign=Gtk.Align.START)
                lg.add_css_class("hk-code-lang")
                ov.add_overlay(lg)
            return ov
        if t == "delimiter":
            s = Gtk.Separator()
            s.set_margin_top(10)
            s.set_margin_bottom(10)
            return s
        if t == "table":
            return self._table(b, d)
        if t == "image":
            return self._image(b, d)
        if t == "attaches":
            return self._attach(d)
        if t == "toggle":
            return self._toggle(b, d, depth)
        if t == "columns":
            return self._columns(b, d, depth)
        if t == "database" and d.get("db"):
            from .database import DatabaseView

            def save_views(vs):
                d["views"] = vs
                self.editor.text_changed()
            return DatabaseView(self.view.win, d["db"], view_id=d.get("view"), local_views=d.get("views"), embedded=True,
                                on_local_views=save_views)
        if t == "tasks":
            from .agenda import TaskList
            fr = Gtk.Box()
            fr.add_css_class("hk-card-box")
            fr.append(TaskList(self.view.win, compact=True))
            return fr
        if t == "calendar":
            from .agenda import EventList
            fr = Gtk.Box()
            fr.add_css_class("hk-card-box")
            fr.append(EventList(self.view.win, days=14, compact=True))
            return fr
        if t == "buttons":
            return self._buttons(b, d)
        if t == "embed":
            return self._embed(d)
        lb = Gtk.Label(label=f"[{t}]", xalign=0)
        lb.add_css_class("dim-label")
        return lb

    def _list(self, b, items, style, path):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        if path:
            box.set_margin_start(22)
        for i, it in enumerate(items):
            if not isinstance(it, dict):
                continue
            p = path + [i]
            key = b["id"] + "/" + "/".join(map(str, p))
            row = Gtk.Box(spacing=8)
            checked = bool((it.get("meta") or {}).get("checked"))
            if style == "checklist":
                cb = Gtk.CheckButton(active=checked, valign=Gtk.Align.START)
                cb.add_css_class("hk-check")
                cb.set_sensitive(not self.editor.locked)

                def toggled(c, it=it):
                    it.setdefault("meta", {})["checked"] = c.get_active()
                    self.editor.changed()
                cb.connect("toggled", toggled)
                holder = Gtk.Box(valign=Gtk.Align.START)          # eigene Box: sonst rechnet GTK die Zeilenhoehe mit 0
                holder.append(cb)
                row.append(holder)
            else:
                mk = Gtk.Label(label=f"{i + 1}." if style == "ordered" else "•", valign=Gtk.Align.START, xalign=1)
                mk.add_css_class("hk-marker")
                mk.set_size_request(18, -1)
                row.append(mk)
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, hexpand=True)
            rt = self._rt(key, b, "content", it.get("content", ""), placeholder=_("List item"), item=it)
            rt.set_placeholder(_("List item"), always=False)
            if checked:
                rt.set_muted(True)
            col.append(rt)
            sub = it.get("items") or []
            if sub:
                col.append(self._list(b, sub, style, p))
            row.append(col)
            box.append(row)
        return box

    def _toggle(self, b, d, depth):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        head = Gtk.Box(spacing=4)
        opened = self.view.toggle_state.get(b["id"], bool(d.get("open")))
        arrow = Gtk.Button(icon_name="pan-down-symbolic" if opened else "pan-end-symbolic", valign=Gtk.Align.START)
        arrow.add_css_class("flat")
        arrow.add_css_class("hk-tiny")
        head.append(arrow)
        rt = self._rt(b["id"], b, "title", d.get("title", ""), css="hk-p hk-bold", placeholder=_("Toggle"), base_bold=True)
        rt.set_hexpand(True)
        head.append(rt)
        box.append(head)
        rev = Gtk.Revealer(reveal_child=opened, transition_type=Gtk.RevealerTransitionType.SLIDE_DOWN)
        inner = d.setdefault("blocks", [])
        if inner:
            il = self.block_list(inner, depth + 1)
        else:
            il = flat_button(label=_("Empty toggle – click to write"), css="dim-label",
                             cb=lambda: self.editor.continue_writing(inner))
        il.set_margin_start(24)
        rev.set_child(il)
        box.append(rev)

        def flip(*_):
            on = not rev.get_reveal_child()
            self.view.toggle_state[b["id"]] = on
            rev.set_reveal_child(on)
            arrow.set_icon_name("pan-down-symbolic" if on else "pan-end-symbolic")
        arrow.connect("clicked", flip)
        return box

    def _columns(self, b, d, depth):
        cols = [c.setdefault("blocks", []) for c in d.get("cols") or [] if isinstance(c, dict)]
        narrow = self.view.get_width() and self.view.get_width() < 700
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL if narrow else Gtk.Orientation.HORIZONTAL, spacing=24, homogeneous=not narrow)
        for c in cols:
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True)
            col.append(self.block_list(c, depth + 1))
            if not self.editor.locked:
                add = flat_button(icon="list-add-symbolic", label=_("Block"), css="dim-label hk-col-add",
                                  cb=lambda c=c: self.editor.continue_in_column(c))
                add.set_halign(Gtk.Align.START)
                add.set_opacity(0)
                mo = Gtk.EventControllerMotion()
                mo.connect("enter", lambda *_, a=add: a.set_opacity(1))
                mo.connect("leave", lambda *_, a=add: a.set_opacity(0))
                col.add_controller(mo)
                col.append(add)
            box.append(col)
        return box

    def _image(self, b, d):
        url = (d.get("file") or {}).get("url")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        if url:
            img = images.ImageView(url, mode="fit", w=2000, max_height=900, radius=8)
            img.set_halign(Gtk.Align.START)
            box.append(img)
        if d.get("caption"):
            cap = Gtk.Label(label=plain(d["caption"]), xalign=0, wrap=True)
            cap.add_css_class("caption")
            cap.add_css_class("dim-label")
            box.append(cap)
        return box

    def _attach(self, d):
        f = d.get("file") or {}
        box = Gtk.Box(spacing=10)
        box.add_css_class("hk-card-box")
        box.append(Gtk.Image.new_from_icon_name("mail-attachment-symbolic"))
        box.append(Gtk.Label(label=f.get("name") or d.get("title") or _("File"), xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.MIDDLE))
        if f.get("size"):
            sz = Gtk.Label(label=human_size(f["size"]))
            sz.add_css_class("dim-label")
            box.append(sz)
        if f.get("url"):
            box.append(flat_button(icon="folder-download-symbolic", tooltip=_("Download and open"),
                                   cb=lambda: self.view.win.download_open(f["url"], f.get("name") or "datei")))
        return box

    def _embed(self, d):
        url = decode_entities(d.get("url") or "")
        title = decode_entities(d.get("title") or "")
        if not url.startswith(("http://", "https://")):
            return Gtk.Box()
        yt = "youtu.be/" in url or "youtube.com/" in url
        host = GLib.Uri.parse(url, GLib.UriFlags.NONE).get_host() if url else ""
        host = (host or "").replace("www.", "")
        row = Gtk.Box(spacing=12)
        ic = Gtk.Image.new_from_icon_name("media-playback-start-symbolic" if yt else "web-browser-symbolic")
        ic.set_pixel_size(24)
        if yt:
            ic.add_css_class("error")
        row.append(ic)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        t = Gtk.Label(label=title or (_("YouTube video") if yt else host), xalign=0, ellipsize=Pango.EllipsizeMode.END)
        t.add_css_class("heading")
        col.append(t)
        h = Gtk.Label(label=host, xalign=0)
        h.add_css_class("caption")
        h.add_css_class("dim-label")
        col.append(h)
        col.set_hexpand(True)
        row.append(col)
        row.append(Gtk.Image.new_from_icon_name("adw-external-link-symbolic"))
        btn = Gtk.Button(child=row)
        btn.add_css_class("hk-card-box")
        btn.add_css_class("flat")
        btn.connect("clicked", lambda *_: self.view.win.open_uri(url))
        return btn

    def _buttons(self, b, d):
        box = Gtk.Box(spacing=6)
        for cfg in d.get("buttons") or []:
            lab = ((cfg.get("icon") + " ") if cfg.get("icon") else "") + (cfg.get("label") or "")
            bt = Gtk.Button(label=lab)
            bt.add_css_class("hk-action-btn")
            bt.connect("clicked", lambda *_, c=cfg: self.view.run_button(c))
            box.append(bt)
        if not self.editor.locked:
            box.append(flat_button(icon="emblem-system-symbolic", tooltip=_("Set up buttons"), css="dim-label",
                                   cb=lambda: self.view.config_buttons(b["id"])))
        return box

    def _table(self, b, d):
        rows = [[decode_entities(plain(c)) for c in r] for r in (d.get("content") or [["", ""], ["", ""]])] or [["", ""], ["", ""]]
        heads = bool(d.get("withHeadings"))
        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        sw = Gtk.ScrolledWindow(vscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True)
        grid = Gtk.Grid(halign=Gtk.Align.START)
        grid.add_css_class("hk-table-grid")
        sw.set_child(grid)
        outer.append(sw)

        def save():
            d["content"] = [[c.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") for c in r] for r in rows]
            self.editor.text_changed()

        def rebuild():
            self.editor.changed()

        for r, row in enumerate(rows):
            for c, val in enumerate(row):
                tv = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR, editable=not self.editor.locked)
                tv.get_buffer().set_text(val)
                tv.add_css_class("hk-cell")
                if r == 0 and heads:
                    tv.add_css_class("hk-cell-head")
                tv.set_size_request(130, -1)

                def ch(buf, r=r, c=c):
                    rows[r][c] = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), True)
                    save()
                tv.get_buffer().connect("changed", ch)
                if not self.editor.locked:
                    g = Gtk.GestureClick(button=3)

                    def menu(gs, n, x, y, r=r, c=c, tv=tv):
                        ents = [(_("Insert row above"), lambda: (rows.insert(r, [""] * len(rows[r])), save(), rebuild())),
                                (_("Insert row below"), lambda: (rows.insert(r + 1, [""] * len(rows[r])), save(), rebuild())),
                                (_("Insert column left"), lambda: ([x.insert(c, "") for x in rows], save(), rebuild())),
                                (_("Insert column right"), lambda: ([x.insert(c + 1, "") for x in rows], save(), rebuild())), None]
                        if len(rows) > 1:
                            ents.append((_("Delete row"), lambda: (rows.pop(r), save(), rebuild()), True))
                        if len(rows[0]) > 1:
                            ents.append((_("Delete column"), lambda: ([x.pop(c) for x in rows if c < len(x)], save(), rebuild()), True))
                        menu_popover(tv, ents).popup()
                    g.connect("pressed", menu)
                    tv.add_controller(g)
                grid.attach(tv, c, r, 1, 1)
        if not self.editor.locked:
            bar = Gtk.Box(spacing=12)
            bar.append(flat_button(icon="list-add-symbolic", label=_("Row"), css="dim-label",
                                   cb=lambda: (rows.append([""] * max(1, len(rows[0]) if rows else 2)), save(), rebuild())))
            bar.append(flat_button(icon="list-add-symbolic", label=_("Column"), css="dim-label",
                                   cb=lambda: ([x.append("") for x in rows], save(), rebuild())))
            hc = Gtk.CheckButton(label=_("Header row"), active=heads)
            hc.connect("toggled", lambda c: (d.__setitem__("withHeadings", c.get_active()), rebuild()))
            bar.append(hc)
            outer.append(bar)
        return outer

    # --- Block-Menue (⋮⋮) ---------------------------------------------------------------------------
    def block_menu(self, parent, bid):
        ed = self.editor
        conv = [(it[0], (lambda t=it[1], l=it[2]: ed.convert(bid, t, l or 2))) for it in CONVERTIBLE]
        ents = [(_("Turn into"), conv),
                (_("Copy link to block"), lambda: self.view.win.copy_text(f"{settings.server}/notes#{ed.doc.id}:{bid}"))]
        c = ed.column_of(bid)
        if c:
            others = [(_("Column {n}").format(n=ci + 1), (lambda ci=ci: ed.move_into_column(bid, c[0], ci))) for ci in range(ed.col_count(c[0])) if ci != c[3]]
            if others:
                ents.append((_("To another column"), others))
            ents.append((_("Take out of the columns"), lambda: ed.move_out_of_columns(bid)))
        else:
            above = ed.columns_above(bid)
            if above is not None:
                if ed.is_columns(bid):
                    ents.append((_("Merge with the columns above"), lambda: ed.merge_into_above(bid)))
                else:
                    ents.append((_("Move into the columns above"),
                                 [(_("Column {n}").format(n=ci + 1), (lambda ci=ci: ed.move_into_column(bid, above, ci))) for ci in range(ed.col_count(above))]))
        if ed.columns_below(bid) is not None and ed.is_columns(bid):
            ents.append((_("Merge with the columns below"), lambda: ed.merge_columns(bid)))
        ents += [(_("Duplicate"), lambda: ed.duplicate(bid)), (_("Move up"), lambda: ed.move(bid, -1)),
                 (_("Move down"), lambda: ed.move(bid, 1)), None, (_("Delete"), lambda: ed.delete(bid), True)]
        menu_popover(parent, ents).popup()

    # --- Tippen -----------------------------------------------------------------------------------
    def on_changed(self, rt):
        b, field = rt.hk_block, rt.hk_field
        html = rt.html()
        if rt.hk_item is not None:
            rt.hk_item["content"] = html
        else:
            b.setdefault("data", {})[field] = html
        self.editor.text_changed()
        if rt.raw:
            return
        text = rt.text()
        # Markdown-Kuerzel am Anfang eines Absatzes ("# ", "- ", "[] ", "```" ...)
        if b.get("type") == "paragraph" and text in MD_SHORTCUTS:
            t, lvl = MD_SHORTCUTS[text]
            b["data"]["text"] = ""
            GLib.idle_add(lambda: (self.editor.slash_apply(b["id"], t, lvl), False)[1])
            return
        self._suggest(rt, text)

    def _suggest(self, rt, text):
        sg = self.view.suggest
        if rt.hk_block.get("type") == "paragraph" and rt.hk_item is None and text.startswith("/") and " " not in text \
                and "\n" not in text and len(text) <= 20 and sg.closed_for != text:
            q = norm(text[1:])
            items = [(it[3], it[0], "", (it[1], it[2])) for it in SLASH_ITEMS if not q or q in norm(it[0]) or q in it[4]]
            if not items:
                items = [("", _("No block type for “{query}”").format(query=text[1:]), "", None)]
            sg.show(rt, "slash", items, lambda v: v and self.editor.slash_apply(rt.hk_block["id"], v[0], v[1]))
            return
        before = rt.text_before_cursor()
        trig = find_trigger(before)
        if trig and sg.closed_for != before:
            items = self._mention_items(trig)
            n = len(trig[1]) + 1

            def pick(v, rt=rt, n=n):
                txt, link = v
                rt.replace_before_cursor(n, txt, link)
            sg.show(rt, trig[0], items, pick)
            return
        if sg.rt is rt:
            sg.hide()

    def _mention_items(self, trig):
        kind, q = trig
        out = []
        if kind == "emoji":
            nq = norm(q)

            def rank(k):
                w = k.split()
                if w and w[0] == nq:
                    return 0
                if nq in w:
                    return 1
                if (" " + k).find(" " + nq) >= 0:
                    return 2
                return 3 if nq in k else 9
            hits = sorted(((rank(w), i, e, w) for i, (e, w) in enumerate(EMOJI)), key=lambda x: (x[0], x[1]))
            return [(e, w.split()[0] if w else "", "", (e, None)) for r, i, e, w in hits if r < 9][:8]

        def date_items(d):
            dt = d[0] + (("T" + d[1]) if d[1] else "")
            out.append(("📅", rel_text(d), _("Date"), (date_text(d), ("t", dt))))
            out.append(("⏰", _("Reminder: {date}").format(date=rel_text(d)), _("Reminder"), ("⏰ " + date_text(d), ("t", dt + "|r"))))
        if not q:
            for w in ("heute", "morgen"):
                d = parse_date(w)
                out.append(("📅", rel_text(d), _("Date"), (date_text(d), ("t", d[0]))))
            d = parse_date("morgen 9:00")
            out.append(("⏰", _("Reminder: {date}").format(date=rel_text(d)), _("Reminder"), ("⏰ " + date_text(d), ("t", d[0] + "T09:00|r"))))
        else:
            d = parse_date(q)
            if d:
                date_items(d)
        nq = norm(q)
        pages = [p for p in store.pages if not nq or nq in norm(label(p))][: (4 if not q else 6)]
        for p in pages:
            ic = p.get("icon") or ""
            ic = ic if ic and len(ic) <= 4 else ("🗃️" if is_db(p) else "📄")
            out.append((ic, label(p), _("Page"), (label(p), ("a", f"/notes#{p['id']}"))))
        return out

    def on_key(self, rt, kv, state):
        sg = self.view.suggest
        ctrl, shift, alt = bool(state & CTRL), bool(state & SHIFT), bool(state & ALT)
        if sg.visible and sg.rt is rt:
            if kv == Gdk.KEY_Up:
                sg.move(-1)
                return True
            if kv == Gdk.KEY_Down:
                sg.move(1)
                return True
            if kv in (Gdk.KEY_Return, Gdk.KEY_KP_Enter, Gdk.KEY_Tab):
                sg.pick_current()
                return True
            if kv == Gdk.KEY_Escape:
                sg.closed_for = rt.text_before_cursor() if sg.kind != "slash" else rt.text()
                sg.hide()
                return True
        ed, b = self.editor, rt.hk_block
        bid = b.get("id")
        # Formatieren
        if ctrl and not alt:
            fmt = {Gdk.KEY_b: "b", Gdk.KEY_i: "i", Gdk.KEY_u: "u", Gdk.KEY_e: "code"}
            if not shift and kv in fmt and not rt.raw:
                rt.toggle_format(fmt[kv])
                return True
            if shift and kv in (Gdk.KEY_X, Gdk.KEY_x) and not rt.raw:
                rt.toggle_format("s")
                return True
            if shift and kv in (Gdk.KEY_H, Gdk.KEY_h) and not rt.raw:
                rt.toggle_format("mark")
                return True
            if not shift and kv == Gdk.KEY_k and not rt.raw and rt.get_buffer().get_has_selection():
                self.view.ask_link(rt)                         # wie Notion: Strg+K mit Auswahl = Link, sonst Befehlspalette
                return True
            if shift and kv == Gdk.KEY_Up:
                ed.move(bid, -1)
                return True
            if shift and kv == Gdk.KEY_Down:
                ed.move(bid, 1)
                return True
            if not shift and kv == Gdk.KEY_d:
                ed.duplicate(bid)
                return True
            if kv in (Gdk.KEY_Return, Gdk.KEY_KP_Enter):
                ed.insert(make("paragraph"), after=bid)
                return True
        if ctrl and alt:
            conv = {Gdk.KEY_0: ("paragraph", 0), Gdk.KEY_1: ("header", 1), Gdk.KEY_2: ("header", 2), Gdk.KEY_3: ("header", 3),
                    Gdk.KEY_4: ("checklist", 0), Gdk.KEY_5: ("unordered", 0), Gdk.KEY_6: ("ordered", 0), Gdk.KEY_7: ("toggle", 0),
                    Gdk.KEY_8: ("code", 0)}
            if kv in conv:
                ed.convert(bid, conv[kv][0], conv[kv][1] or 2)
                return True
        if kv == Gdk.KEY_Escape:
            sg.hide()
            self.view.blur()
            return True
        if kv in (Gdk.KEY_Return, Gdk.KEY_KP_Enter) and not shift and not ctrl:
            return self._return(rt)
        if kv == Gdk.KEY_BackSpace and not rt.get_buffer().get_has_selection() and rt.cursor_offset() == 0:
            return self._backspace(rt)
        if kv == Gdk.KEY_Tab and rt.hk_item is not None:
            self._indent(rt, -1 if shift else 1)
            return True
        if kv == Gdk.KEY_ISO_Left_Tab and rt.hk_item is not None:
            self._indent(rt, -1)
            return True
        if kv == Gdk.KEY_Tab:
            return True
        if kv == Gdk.KEY_Up and not shift and rt.at_first_line():
            return self._jump(rt, -1, "end")
        if kv == Gdk.KEY_Down and not shift and rt.at_last_line():
            return self._jump(rt, 1, "start")
        if kv == Gdk.KEY_Left and not shift and rt.cursor_offset() == 0:
            return self._jump(rt, -1, "end")
        if kv == Gdk.KEY_Right and not shift and rt.cursor_offset() == rt.get_buffer().get_char_count():
            return self._jump(rt, 1, "start")
        return False

    def _jump(self, rt, d, where):
        keys = [k for k, _ in self.order]
        try:
            i = keys.index(rt.hk_key)
        except ValueError:
            return False
        j = i + d
        if 0 <= j < len(keys):
            self.order[j][1].focus_at(where)
            return True
        if j < 0:
            self.view.focus_title()
            return True
        return False

    def _return(self, rt):
        ed, b = self.editor, rt.hk_block
        bid, t = b["id"], b.get("type")
        if rt.raw:
            return False                                      # Code: Zeilenumbruch
        before, after = rt.split_at_cursor()
        if rt.hk_item is not None:
            return self._return_list(rt, before, after)
        if t == "toggle":
            b["data"]["title"] = before
            self.view.toggle_state[bid] = True
            inner = b["data"].setdefault("blocks", [])
            if not inner or after:
                p = make("paragraph", after)
                inner.insert(0, p)
            ed.set_focus(inner[0]["id"], "start")
            ed.changed()
            return True
        rt.hk_block["data"][rt.hk_field] = before
        ed.insert(make("paragraph", after), after=bid)
        return True

    def _return_list(self, rt, before, after):
        ed, b = self.editor, rt.hk_block
        path = [int(x) for x in rt.hk_key.split("/")[1:]]
        it, siblings, i = ed.item_at(b, path)
        if it is None:
            return True
        style = b["data"].get("style")
        if not before and not after and not it.get("items"):
            if len(path) > 1:
                self._indent(rt, -1)
                return True
            siblings.pop(i)
            if not siblings:
                ed.convert(b["id"], "paragraph")
            else:
                p = make("paragraph")
                if i < len(siblings):                         # mitten in der Liste: Liste teilen
                    rest = siblings[i:]
                    del siblings[i:]
                    r = ed.locate(b["id"])
                    nb = make(style if style in ("checklist", "ordered") else "unordered")
                    nb["data"]["items"] = rest
                    r[0].insert(r[1] + 1, nb)
                ed.insert(p, after=b["id"])
            return True
        it["content"] = before
        ni = {"content": after, "meta": {"checked": False} if style == "checklist" else {}, "items": []}
        siblings.insert(i + 1, ni)
        ed.set_focus(b["id"] + "/" + "/".join(map(str, path[:-1] + [i + 1])), "start")
        ed.changed()
        return True

    def _indent(self, rt, d):
        ed, b = self.editor, rt.hk_block
        path = [int(x) for x in rt.hk_key.split("/")[1:]]
        it, siblings, i = ed.item_at(b, path)
        if it is None:
            return
        off = rt.cursor_offset()
        if d > 0:
            if i == 0:
                return
            siblings.pop(i)
            prev = siblings[i - 1]
            prev.setdefault("items", []).append(it)
            newp = path[:-1] + [i - 1, len(prev["items"]) - 1]
        else:
            if len(path) < 2:
                return
            parent, psib, pi = ed.item_at(b, path[:-1])
            siblings.pop(i)
            it.setdefault("items", []).extend(siblings[i:])   # folgende Geschwister werden Kinder
            del siblings[i:]
            psib.insert(pi + 1, it)
            newp = path[:-2] + [pi + 1]
        ed.set_focus(b["id"] + "/" + "/".join(map(str, newp)), off)
        ed.changed()

    def _backspace(self, rt):
        ed, b = self.editor, rt.hk_block
        t = b.get("type")
        if rt.hk_item is not None:
            path = [int(x) for x in rt.hk_key.split("/")[1:]]
            if len(path) > 1:
                self._indent(rt, -1)
                return True
            it, siblings, i = ed.item_at(b, path)
            if i == 0:                                         # erster Punkt -> Absatz vor der Liste
                siblings.pop(0)
                r = ed.locate(b["id"])
                p = make("paragraph", it.get("content", ""))
                if siblings:
                    r[0].insert(r[1], p)
                else:
                    r[0][r[1]] = p
                ed.set_focus(p["id"], "start")
                ed.changed()
                return True
            prev = siblings[i - 1]
            plen = len(plain(prev.get("content", "")))
            prev["content"] = (prev.get("content") or "") + (it.get("content") or "")
            prev.setdefault("items", []).extend(it.get("items") or [])
            siblings.pop(i)
            ed.set_focus(b["id"] + "/" + "/".join(map(str, path[:-1] + [i - 1])), plen)
            ed.changed()
            return True
        if t in ("header", "quote", "callout"):
            ed.convert(b["id"], "paragraph")
            ed.set_focus(b["id"], "start")
            self.view.apply_focus()
            return True
        if t == "toggle":
            inner = b["data"].get("blocks") or []
            if not inner or (len(inner) == 1 and not (inner[0].get("data") or {}).get("text")):
                ed.convert(b["id"], "paragraph")
                ed.set_focus(b["id"], "start")
                self.view.apply_focus()
            return True
        if t != "paragraph":
            return False
        keys = [k for k, _ in self.order]
        i = keys.index(rt.hk_key) if rt.hk_key in keys else -1
        if i <= 0:
            return True
        pk, prt = self.order[i - 1]
        if prt.raw:
            if not rt.text():
                ed.delete(b["id"])
                ed.set_focus(pk, "end")
                self.view.apply_focus()
            else:
                prt.focus_at("end")
            return True
        plen = prt.get_buffer().get_char_count()
        merged = prt.html() + rt.html()
        if prt.hk_item is not None:
            prt.hk_item["content"] = merged
        else:
            prt.hk_block.setdefault("data", {})[prt.hk_field] = merged
        r = ed.locate(b["id"])
        if r:
            r[0].pop(r[1])
        ed.set_focus(pk, plen)
        ed.changed()
        return True
