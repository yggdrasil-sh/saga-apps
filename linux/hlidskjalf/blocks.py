"""Bloecke als Widgets + Tastatur wie Notion (Enter teilt, Ruecktaste verbindet, Pfeile springen, Tab rueckt ein)."""
import json
import re

from gi.repository import Gdk, GLib, GObject, Graphene, Gtk, Pango

from . import images
from .config import settings
from .editor import CONVERTIBLE, SLASH_ITEMS, cell_key, make
from .emoji import EMOJI
from .i18n import _
from .richtext import RichText, date_text, find_trigger, parse_date, rel_text
from .store import is_db, label, store
from .util import decode_entities, esc, human_size, norm, plain

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


def is_menu_key(kv, state):
    """Kontextmenue per Tastatur: Menue-Taste oder Umschalt+F10"""
    return kv == Gdk.KEY_Menu or (kv == Gdk.KEY_F10 and bool(state & Gdk.ModifierType.SHIFT_MASK))


def context_menu(widget, open_menu, long_press=True):
    """Kontextmenue an widget: Rechtsklick, langes Druecken (Touch) und Menue-Taste/Umschalt+F10.
    open_menu(widget) oeffnet das Menue."""
    g = Gtk.GestureClick(button=3)
    g.connect("pressed", lambda *_: open_menu(widget))
    widget.add_controller(g)
    if long_press:
        lp = Gtk.GestureLongPress(touch_only=True)
        lp.connect("pressed", lambda *_: open_menu(widget))
        widget.add_controller(lp)
    kc = Gtk.EventControllerKey()
    kc.connect("key-pressed", lambda c, kv, code, st: (open_menu(widget), True)[1] if is_menu_key(kv, st) else False)
    widget.add_controller(kc)


def more_button(get_entries, tooltip=None):
    """Sichtbarer "⋮"-Knopf fuer ein Menue (auch ohne Maus und Rechtsklick erreichbar)"""
    mb = Gtk.MenuButton(icon_name="view-more-symbolic", valign=Gtk.Align.CENTER, tooltip_text=tooltip or _("More"))
    mb.add_css_class("flat")
    mb.add_css_class("hk-tiny")
    mb.set_create_popup_func(lambda m: m.set_popover(menu_popover(None, get_entries())))
    mb.update_property([Gtk.AccessibleProperty.LABEL], [tooltip or _("More")])
    return mb


def menu_popover(parent, entries):
    """Einfaches Menue: entries = [(Text, Rueckruf, destruktiv?) | None (Trenner) | (Text, [Unter-Eintraege])]

    Der Rueckruf laeuft erst, wenn das Menue zu und (freies Menue) abgehaengt ist: Viele Eintraege (Loeschen, Duplizieren,
    Umwandeln) bauen den Block samt dem Widget neu, an dem das Menue haengt. Ein noch angehaengtes Popover an einem
    zerstoerten Eltern-Widget liess GTK abstuerzen (SIGSEGV in gdk_surface_request_motion, Blockmenue per Tastatur)."""
    pop = Gtk.Popover()
    pending = []

    def closed(p):
        def later():
            if parent is not None and p.get_parent() is not None:
                p.unparent()
            acts = pending[:]
            pending.clear()
            for a in acts:
                a()
            return False
        GLib.idle_add(later)
    pop.connect("closed", closed)
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
                    if pending:
                        return                                # schon gewaehlt, Menue geht gerade zu
                    pending.append(a)
                    if pop.get_visible():
                        pop.popdown()                         # -> "closed" -> abhaengen, dann a()
                    else:
                        closed(pop)
                btn.connect("clicked", run)
            box.append(btn)
        stack.add_named(box, name)
    page(entries, "main")
    stack.set_visible_child_name("main")
    pop.set_child(stack)
    if parent is not None:                    # freies Menue: nach dem Schliessen abhaengen ("closed"); im MenuButton: parent=None
        anchor_popover(pop, parent)
    return pop


def anchor_popover(pop, widget, anchor=None):
    """Popover an widget zeigen, aber nie an ein Textfeld haengen: Ein Gtk.TextView verwaltet fremde Kinder nicht; wurde
    es nach dem Schliessen des Menues neu aufgebaut (Block loeschen/duplizieren/verschieben per Menuetaste), stuerzte GTK
    ab. Das Popover haengt dann am naechsten Vorfahren (bzw. anchor) und zeigt auf die Schreibmarke im Textfeld."""
    if anchor is None:
        anchor = widget
        while isinstance(anchor, Gtk.TextView) and anchor.get_parent() is not None:
            anchor = anchor.get_parent()
    pop.set_parent(anchor)
    if anchor is widget:
        return
    x, y, w, h = 0, 0, widget.get_width(), widget.get_height()
    if isinstance(widget, Gtk.TextView):
        buf = widget.get_buffer()
        strong, _weak = widget.get_cursor_locations(buf.get_iter_at_mark(buf.get_insert()))
        x, y = widget.buffer_to_window_coords(Gtk.TextWindowType.WIDGET, strong.x, strong.y)
        w, h = max(1, strong.width), max(1, strong.height)
    ok, pt = widget.compute_point(anchor, Graphene.Point().init(x, y))
    if ok:
        pop.set_pointing_to(Gdk.Rectangle(x=int(pt.x), y=int(pt.y), width=int(w), height=int(h)))


class HoverManager:
    """Griffe (+ ⋮⋮) am innersten Block unter der Maus - und am Block mit dem Fokus (Tastatur, Touch)"""

    def __init__(self):
        self.rows = []
        self.active = None
        self.focused = None

    def _show(self, row):
        if row is not None:
            row.show_handle(row is self.active or row is self.focused)

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
            old, self.active = self.active, best
            self._show(old)
            self._show(best)
        return False

    def focus(self, row):
        if row is not self.focused:
            old, self.focused = self.focused, row
            self._show(old)
            self._show(row)

    def reset(self):
        self.rows, self.active, self.focused = [], None, None


NOTE_A_RE = re.compile(r'(📄 )?<a href="/notes#(\d+)">(.*?)</a>', re.S)


def _link_label(p, doc_icon):
    ic = p.get("icon") or ""
    ic = ic if ic and len(ic) <= 4 and not ic.startswith("/") and not doc_icon else ""
    return (ic + " " if ic else "") + label(p)


def fresh_links(html):
    """Verweise auf eigene Seiten zeigen den aktuellen Titel (wie im Web decorateLinks) - z. B. nach dem Umbenennen
    einer Unterseite, deren Verweis noch "Unbenannt" hiess. Nur die Anzeige; gespeichert wird es mit der naechsten Eingabe."""
    if not html or "/notes#" not in html:
        return html

    def sub(m):
        p = store.by_id.get(int(m.group(2)))
        if p is None:
            return m.group(0)
        return f'{m.group(1) or ""}<a href="/notes#{m.group(2)}">{esc(_link_label(p, bool(m.group(1))))}</a>'
    return NOTE_A_RE.sub(sub, html)


def linked_titles(blocks):
    """(Seite, aktueller Titel) aller Verweise auf eigene Seiten in den Bloecken - aendert sich das, neu zeichnen"""
    try:
        j = json.dumps(blocks, ensure_ascii=False)
    except (TypeError, ValueError):
        return ()
    out = []
    for m in re.finditer(r'/notes#(\d+)', j):
        p = store.by_id.get(int(m.group(1)))
        if p is not None:
            out.append((p["id"], label(p), p.get("icon") or ""))
    return tuple(sorted(set(out)))


def slash_matches(query, skip=()):
    """"/"-Menue: alle Blocktypen (ohne Suchwort in fester Reihenfolge), sonst nach Treffer geordnet - Name vor
    Stichwort, wie im Web (dort zaehlt der Name): "/tabelle" -> Tabelle (nicht Datenbank), "/kalender" -> Kalender.
    -> [(Symbol, Titel, Hinweis, (Blocktyp, Stufe))]"""
    q = norm(query)

    def rank(it):
        if not q:
            return 0
        t, kw = norm(it[0]), it[4].split()
        if t == q:
            return 0
        if t.startswith(q):
            return 1
        if any(w.startswith(q) for w in t.split()):
            return 2
        if q in t:
            return 3
        if q in kw:
            return 4
        if any(w.startswith(q) for w in kw):
            return 5
        return 6 if q in it[4] else None
    hits = [(rank(it), n, it) for n, it in enumerate(SLASH_ITEMS) if it[1] not in skip]
    return [(it[3], it[0], "", (it[1], it[2])) for r, n, it in sorted((h for h in hits if h[0] is not None), key=lambda h: h[:2])]


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
        self.list_box = self.scroller = None

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
        sel_btn = None
        for i, (ic, title, hint, val) in enumerate(items):         # alle Eintraege, die Liste rollt (frueher nur 12)
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
                sel_btn = b
            b.connect("clicked", lambda *_, v=val: self._pick(v))
            lb.append(b)
        old_sw = self.pop.get_child()
        top = old_sw.get_vadjustment().get_value() if isinstance(old_sw, Gtk.ScrolledWindow) else 0
        sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True, max_content_height=340)
        sw.set_child(lb)
        sw.set_size_request(300, -1)
        self.pop.set_child(sw)
        self.pop.set_pointing_to(rt.cursor_rect())
        self.pop.popup()
        self.list_box, self.scroller = lb, sw

        def keep_visible(n=0):
            """Rollstand behalten und die Auswahl (Pfeiltasten) sichtbar halten"""
            if self.scroller is not sw:
                return False
            adj = sw.get_vadjustment()
            if sel_btn is None or adj.get_page_size() <= 0:
                if n < 5:
                    GLib.timeout_add(30, keep_visible, n + 1)
                return False
            ok, r = sel_btn.compute_bounds(lb)
            if not ok:
                return False
            y0, y1, page = r.get_y(), r.get_y() + r.get_height(), adj.get_page_size()
            v = min(max(top, y1 - page), y0) if y1 - y0 < page else y0
            adj.set_value(max(0, min(v, adj.get_upper() - page)))
            return False
        GLib.idle_add(keep_visible)

    def move(self, d):
        if self.items:
            self.sel = (self.sel + d) % len(self.items)
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
        self.list_box = self.scroller = None


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
        self.handle.set_size_request(getattr(kit.view, "gut", 44), -1)     # schmales Fenster: 26 (PageView.gut)
        if not kit.view.editor.locked:
            plus = flat_button(icon="list-add-symbolic", tooltip=_("Insert block below"), css="hk-tiny",
                               cb=lambda: kit.editor.insert(make("paragraph"), after=self.bid))
            grip = Gtk.Button(icon_name="view-app-grid-symbolic", tooltip_text=_("Drag to move · Click for the menu (keyboard: Menu key or Shift+F10)"))
            grip.add_css_class("flat")
            grip.add_css_class("hk-tiny")
            grip.connect("clicked", lambda b: kit.block_menu(b, self.bid))
            ds = Gtk.DragSource(actions=Gdk.DragAction.MOVE)
            ds.connect("prepare", lambda *_: Gdk.ContentProvider.new_for_value("hkblock:" + self.bid))
            grip.add_controller(ds)
            for b in (plus, grip):
                b.set_focusable(False)                       # Tastatur: Menue-Taste/Umschalt+F10 im Block
            if not getattr(kit.view, "narrow", False):        # schmal: nur der Griff ("+" per Enter bzw. Menue)
                self.handle.append(plus)
            self.handle.append(grip)
        self.append(self.handle)
        content = kit.build(block, depth)
        content.set_hexpand(True)
        self.append(content)
        # Kommentare (1.3): rechts ein schmaler Platz fuer die Zahl offener Unterhaltungen bzw. 💬+ beim Ueberfahren
        self.cslot = None
        self._cm_sig = None
        if depth == 0 and getattr(kit.view, "cpanel", None) is not None:
            self.cslot = Gtk.Box(width_request=getattr(kit.view, "cslot_w", 36), valign=Gtk.Align.START, halign=Gtk.Align.END)
            self.cslot.add_css_class("hk-cslot")
            self.append(self.cslot)
        mo = Gtk.EventControllerMotion()
        mo.connect("enter", lambda *_: kit.view.hover.enter(self))
        mo.connect("leave", lambda *_: kit.view.hover.leave(self))
        self.add_controller(mo)
        tp = Gtk.GestureClick(touch_only=True)                 # Touch: kein Ueberfahren - Antippen zeigt die Griffe
        tp.set_propagation_phase(Gtk.PropagationPhase.CAPTURE)
        tp.connect("pressed", lambda *_: self._touched())
        self.add_controller(tp)
        if not kit.view.editor.locked:
            dt = Gtk.DropTarget.new(GObject.TYPE_STRING, Gdk.DragAction.MOVE)
            dt.connect("motion", self._motion)
            dt.connect("leave", lambda *_: self._mark(None))
            dt.connect("drop", self._drop)
            self.add_controller(dt)

    def show_handle(self, on):
        (self.handle.add_css_class if on else self.handle.remove_css_class)("hk-on")
        if self.cslot is not None:
            (self.cslot.add_css_class if on else self.cslot.remove_css_class)("hk-on")

    def set_comments(self, n, unread, can):
        """Zahl offener Unterhaltungen am Block (nur wenn > 0); sonst - wer kommentieren darf - 💬+ beim Ueberfahren"""
        sig = (n, bool(unread), bool(can))
        if self.cslot is None or sig == self._cm_sig:
            return
        self._cm_sig = sig
        while (c := self.cslot.get_first_child()):
            self.cslot.remove(c)
        from .comments import bubble_tooltip
        from .sharing import comment_icon
        view = self.kit.view
        if n:
            bx = Gtk.Box(spacing=2)
            ic = Gtk.Image.new_from_icon_name(comment_icon())
            ic.set_pixel_size(12)
            bx.append(ic)
            bx.append(Gtk.Label(label=str(n)))
            b = Gtk.Button(child=bx, valign=Gtk.Align.START, tooltip_text=bubble_tooltip(n))
            b.add_css_class("hk-cm-bubble")
            if unread:
                b.add_css_class("hk-unread")
            b.update_property([Gtk.AccessibleProperty.LABEL], [bubble_tooltip(n)])
        elif can:
            bx = Gtk.Box(spacing=0)
            ic = Gtk.Image.new_from_icon_name(comment_icon())
            ic.set_pixel_size(12)
            bx.append(ic)
            bx.append(Gtk.Label(label="+"))
            b = Gtk.Button(child=bx, valign=Gtk.Align.START, tooltip_text=_("Comment"))
            b.add_css_class("flat")
            b.add_css_class("hk-cm-add")
            b.update_property([Gtk.AccessibleProperty.LABEL], [_("Comment")])
        else:
            return
        b.connect("clicked", lambda *_: view.show_comments(self.bid))
        self.cslot.append(b)

    def _touched(self):
        # CAPTURE laeuft von aussen nach innen: der innerste angetippte Block setzt sich zuletzt durch
        self.kit.view.hover.focus(self)

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
        self.cache = {}          # Block-ID -> BlockRow des letzten Aufbaus (zum Wiederverwenden)
        self._built = []
        self.cells = {}          # Fokus-Schluessel (editor.cell_key) -> Tabellenzelle (Gtk.TextView)
        self._cell_regs = []

    def reset(self):
        self.order, self.by_key = [], {}
        self.cells, self._cell_regs = {}, []
        self.cache = {r.bid: r for r in self._built}
        self._built = []

    def register(self, key, rt):
        self.order.append((key, rt))
        self.by_key[key] = rt

    def register_cell(self, key, tv):
        self.cells[key] = tv
        self._cell_regs.append((key, tv))

    def rows(self):
        """alle Blockzeilen des letzten Aufbaus (auch verschachtelte)"""
        return list(self._built)

    def _owner_only(self, what):
        """Aufgaben, Kalender, Immich auf fremden Seiten: neutraler Platzhalter (nie Daten des Eigentuemers);
        der Block bleibt beim Speichern unveraendert"""
        lb = Gtk.Label(label=what + " · " + _("Only visible to the owner"), xalign=0, wrap=True)
        lb.add_css_class("dim-label")
        box = Gtk.Box()
        box.add_css_class("hk-card-box")
        box.add_css_class("hk-owner-only")
        box.append(lb)
        return box

    # --- Aufbau -------------------------------------------------------------------------------
    def _sig(self, b, depth):
        """Stand eines Blocks: unveraendert (gleiches Objekt, gleicher Inhalt) -> Widget wiederverwenden"""
        ids = []

        def walk(x):
            if isinstance(x, dict):
                ids.append(id(x))
                for v in x.values():
                    walk(v)
            elif isinstance(x, list):
                ids.append(id(x))
                for v in x:
                    walk(v)
        walk(b)
        try:
            j = json.dumps(b, sort_keys=True, ensure_ascii=False, default=str)
        except (TypeError, ValueError):
            j = None
        extra = (depth, self.editor.locked, linked_titles(b) if j and "/notes#" in j else (), getattr(self.view, "gut", 44))
        t = b.get("type")
        if t == "columns":
            extra += (bool(self.view.get_width() and self.view.get_width() < 700),)
        elif t == "toggle":
            extra += (self.view.toggle_state.get(b.get("id")),)
        return (j, tuple(ids), extra)

    def _reuse(self, b, depth):
        row = self.cache.pop(b.get("id"), None)
        if row is None or row.block is not b or row.depth != depth or row.sig != self._sig(b, depth):
            return None
        par = row.get_parent()
        if par is not None:
            par.remove(row)
        row.show_handle(False)
        for k, rt in row.regs:
            self.register(k, rt)
        for k, tv in getattr(row, "cregs", ()):
            self.register_cell(k, tv)
        self._built.extend(row.sub)
        return row

    def block_list(self, arr, depth=0):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        for b in arr:
            if not isinstance(b, dict):
                continue
            row = self._reuse(b, depth)
            if row is None:
                n0, o0, c0 = len(self._built), len(self.order), len(self._cell_regs)
                row = BlockRow(self, b, depth)
                row.sub = self._built[n0:]
                row.regs = self.order[o0:]
                row.cregs = self._cell_regs[c0:]
                row.sig = self._sig(b, depth)
            box.append(row)
            self._built.append(row)
        return box

    def synced(self, w):
        """Eingabe kam aus dem Widget selbst: es zeigt den neuen Stand -> Blockzeilen darueber bleiben wiederverwendbar"""
        p = w
        while p is not None:
            if isinstance(p, BlockRow) and getattr(p, "sig", None) is not None:
                p.sig = self._sig(p.block, p.depth)
            p = p.get_parent()

    def _rt(self, key, block, field, html, css="hk-p", placeholder="", base_bold=False, raw=False, item=None):
        if not raw:
            html = fresh_links(html)                          # Seitenverweise mit aktuellem Titel
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
        p = rt.get_parent()
        while p is not None and not isinstance(p, BlockRow):
            p = p.get_parent()
        self.view.hover.focus(p)

    def _cell_focused(self, tv):
        """Tabellenzelle: Fokus merken - nach dem Neuaufbau (Zeile/Spalte dazu) geht es in derselben Zelle weiter"""
        self.editor.focus, self.editor.focus_where = tv.hk_key, "end"
        if self.view.suggest.rt is not None:
            self.view.suggest.hide()
        p = tv.get_parent()
        while p is not None and not isinstance(p, BlockRow):
            p = p.get_parent()
        self.view.hover.focus(p)

    def _sel_focused(self, w):
        self.editor.focus = w.hk_sel_bid
        self.editor.focus_where = "end"
        if self.view.suggest.rt is not None:
            self.view.suggest.hide()
        p = w.get_parent()
        while p is not None and not isinstance(p, BlockRow):
            p = p.get_parent()
        self.view.hover.focus(p)

    def build(self, b, depth):
        t = b.get("type") or ""
        d = b.get("data")
        if not isinstance(d, dict):
            d = b["data"] = {}
        bid = b.get("id")
        foreign = bool(getattr(self.view, "foreign", False))
        if t == "paragraph":
            # schreibgeschuetzt (gesperrt, nur Lesen/Kommentieren): kein Editor-Platzhalter
            ph = "" if self.editor.locked else _("Type something or “/” for blocks …")
            rt = self._rt(bid, b, "text", d.get("text", ""), placeholder=ph)
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
            ch.connect("emoji-picked", lambda c, e: (d.__setitem__("emoji", e), em.get_child().set_label(e), self.synced(em),
                                                     self.editor.text_changed()))
            em.set_popover(ch)
            em.set_sensitive(not self.editor.locked)
            box.append(em)
            rt = self._rt(bid, b, "text", d.get("text", ""), placeholder=_("Callout"))
            rt.set_hexpand(True)
            rt.set_margin_top(6)                              # erste Zeile mittig zum Emoji-Knopf (Testbericht 022)
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
        if t in ("tasks", "calendar", "immich") and foreign:
            return self._owner_only({"tasks": "✓ " + _("Tasks"), "calendar": "🗓 " + _("Calendar"), "immich": "📷 Immich"}[t])
        if t == "database" and foreign and (not d.get("db") or not store.visible(d.get("db"))):
            lb = Gtk.Label(label="🗃️ " + (_("Page without access") if d.get("db") else _("No database selected")), xalign=0)
            lb.add_css_class("dim-label")
            box = Gtk.Box()
            box.add_css_class("hk-card-box")
            box.add_css_class("hk-owner-only")
            box.append(lb)
            return box
        if t == "database" and d.get("db"):
            from .database import DatabaseView

            holder = []

            def save_views(vs):
                d["views"] = vs
                if holder:
                    self.synced(holder[0])                     # die Ansicht zeigt den neuen Stand schon
                self.editor.text_changed()
            dv = DatabaseView(self.view.win, d["db"], view_id=d.get("view"), local_views=d.get("views"), embedded=True,
                              on_local_views=save_views, host_locked=self.editor.locked)
            holder.append(dv)
            return dv
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
        if t == "nextcloud":                                    # Nextcloud-Datei (2.3): Karte; Daten bleiben unveraendert
            from .nextcloud import block_widget
            return block_widget(self, b, d)
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
                add.add_css_class("hk-reveal")
                col.add_css_class("hk-reveal-zone")
                col.append(add)
            box.append(col)
        return box

    def _image(self, b, d):
        url = (d.get("file") or {}).get("url")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        # Anklicken waehlt den Bildblock aus (Rahmen): Strg+C/Strg+X/Entf wirken dann auf den ganzen Block (2.1-c)
        box.hk_sel_bid = b.get("id")
        box.set_focusable(True)
        box.set_halign(Gtk.Align.START)                       # Auswahlrahmen eng um das Bild
        box.add_css_class("hk-img-block")
        box.update_property([Gtk.AccessibleProperty.LABEL], [_("Image block")])
        gc = Gtk.GestureClick()
        gc.connect("pressed", lambda *_: box.grab_focus())
        box.add_controller(gc)
        fc = Gtk.EventControllerFocus()
        fc.connect("enter", lambda *_: self._sel_focused(box))
        box.add_controller(fc)
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
            bt.connect("clicked", lambda b_, c=cfg: self._press(b_, c))
            box.append(bt)
        if not self.editor.locked:
            box.append(flat_button(icon="emblem-system-symbolic", tooltip=_("Set up buttons"), css="dim-label",
                                   cb=lambda: self.view.config_buttons(b["id"])))
        return box

    def _press(self, bt, cfg):
        """Schaltflaeche: sofort Rueckmeldung (Fortschritt im Knopf) und gesperrt, solange die Aktion laeuft"""
        if getattr(bt, "_hk_busy", False):
            return
        lab = bt.get_label()

        def busy(on):
            bt._hk_busy = on
            bt.set_sensitive(not on)
            if on:
                row = Gtk.Box(spacing=6, halign=Gtk.Align.CENTER)
                sp = Gtk.Spinner(spinning=True)
                row.append(sp)
                row.append(Gtk.Label(label=lab))
                bt.set_child(row)
            else:
                bt.set_label(lab)
        self.view.run_button(cfg, busy=busy)

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

        cur = {"cell": (0, 0)}                                 # zuletzt fokussierte Zelle (fuer "⋮" und die Menue-Taste)

        def cell_entries(r, c):
            ents = [(_("Insert row above"), lambda: (rows.insert(r, [""] * len(rows[r])), save(), rebuild())),
                    (_("Insert row below"), lambda: (rows.insert(r + 1, [""] * len(rows[r])), save(), rebuild())),
                    (_("Insert column left"), lambda: ([x.insert(c, "") for x in rows], save(), rebuild())),
                    (_("Insert column right"), lambda: ([x.insert(c + 1, "") for x in rows], save(), rebuild())), None]
            if len(rows) > 1:
                ents.append((_("Delete row"), lambda: (rows.pop(r), save(), rebuild()), True))
            if len(rows[0]) > 1:
                ents.append((_("Delete column"), lambda: ([x.pop(c) for x in rows if c < len(x)], save(), rebuild()), True))
            return ents

        for r, row in enumerate(rows):
            for c, val in enumerate(row):
                tv = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR, editable=not self.editor.locked, accepts_tab=False)
                tv.get_buffer().set_text(val)
                tv.add_css_class("hk-cell")
                if r == 0 and heads:
                    tv.add_css_class("hk-cell-head")
                tv.set_size_request(130, -1)
                tv.update_property([Gtk.AccessibleProperty.LABEL], [_("Row {row}, column {col}").format(row=r + 1, col=c + 1)])

                def ch(buf, r=r, c=c, tv=tv):
                    rows[r][c] = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), True)
                    save()
                    self.synced(tv)
                tv.get_buffer().connect("changed", ch)
                fc = Gtk.EventControllerFocus()
                fc.connect("enter", lambda *_, r=r, c=c, tv=tv: (cur.__setitem__("cell", (r, c)), self._cell_focused(tv)))
                tv.add_controller(fc)
                tv.hk_key = cell_key(b.get("id"), r, c)
                self.register_cell(tv.hk_key, tv)
                if not self.editor.locked:
                    context_menu(tv, lambda w, r=r, c=c: menu_popover(w, cell_entries(r, c)).popup())
                grid.attach(tv, c, r, 1, 1)
        if not self.editor.locked:
            # Schmal: bricht um (FlowBox) - sonst waere die Seite breiter als das Fenster (Testbericht 180). Volle Breite
            # (halign FILL): mit START bekam die FlowBox nur ihre Mindestbreite (ein Knopf je Zeile), aber die Hoehe fuer
            # eine Zeile - die Knoepfe lagen ueber den folgenden Bloecken (N8). Breit: eine einfache Zeile links.
            if getattr(self.view, "narrow", False):
                bar = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, column_spacing=12, row_spacing=2, homogeneous=False,
                                  max_children_per_line=4, min_children_per_line=1, halign=Gtk.Align.FILL)
            else:
                bar = Gtk.Box(spacing=12, halign=Gtk.Align.START)
            bar.add_css_class("hk-table-bar")
            bar.append(flat_button(icon="list-add-symbolic", label=_("Row"), css="dim-label",
                                   cb=lambda: (rows.append([""] * max(1, len(rows[0]) if rows else 2)), save(), rebuild())))
            bar.append(flat_button(icon="list-add-symbolic", label=_("Column"), css="dim-label",
                                   cb=lambda: ([x.append("") for x in rows], save(), rebuild())))
            hc = Gtk.CheckButton(label=_("Header row"), active=heads)
            hc.connect("toggled", lambda c: (d.__setitem__("withHeadings", c.get_active()), rebuild()))
            bar.append(hc)
            bar.append(more_button(lambda: cell_entries(*[min(v, len(rows if i == 0 else rows[0]) - 1) for i, v in enumerate(cur["cell"])]),
                                   _("Row and column of the selected cell")))
            c = bar.get_first_child() if isinstance(bar, Gtk.FlowBox) else None
            while c is not None:                              # FlowBoxChild: nicht anwaehlbar, Tab geht zum Knopf
                c.set_focusable(False)
                c = c.get_next_sibling()
            outer.append(bar)
        return outer

    # --- Block-Menue (⋮⋮) ---------------------------------------------------------------------------
    def readonly_menu(self, parent, bid):
        """Menue-Taste in einer schreibgeschuetzten Seite: Link zum Block, Kommentieren"""
        ed = self.editor
        ents = [(_("Copy link to block"), lambda: self.view.win.copy_text(f"{settings.server}/notes#{ed.doc.id}:{bid}"))]
        if hasattr(self.view, "copy_block"):
            ents.insert(0, (_("Copy block"), lambda: self.view.copy_block(bid)))
        if getattr(self.view, "cpanel", None) is not None and "role" in ed.doc.page:
            ents.append((_("Comment"), lambda: self.view.show_comments(bid)))
        ents += self._ai_entries(bid)
        menu_popover(parent, ents).popup()

    def _ai_entries(self, bid):
        """KI-Assistent: Block fuer "Verbessern"/"Uebersetzen" markieren (nur wenn der Assistent an ist)"""
        v = self.view
        if not getattr(v, "ai_on", lambda: False)():
            return []
        on = bid in v.ai_marked
        return [(("✓ " if on else "") + _("Mark for the assistant"), lambda: v.ai_toggle(bid))]

    def block_menu(self, parent, bid):
        ed = self.editor
        conv = [(it[0], (lambda t=it[1], l=it[2]: ed.convert(bid, t, l or 2))) for it in CONVERTIBLE]
        ents = [(_("Turn into"), conv),
                (_("Copy link to block"), lambda: self.view.win.copy_text(f"{settings.server}/notes#{ed.doc.id}:{bid}"))]
        if getattr(self.view, "cpanel", None) is not None and "role" in ed.doc.page:     # Server ab 1.3
            ents.append((_("Comment"), lambda: self.view.show_comments(bid)))
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
        ents += self._ai_entries(bid)
        if hasattr(self.view, "copy_block"):
            ents += [(_("Copy block"), lambda: self.view.copy_block(bid)), (_("Cut block"), lambda: self.view.copy_block(bid, cut=True))]
        ents += [(_("Duplicate"), lambda: ed.duplicate(bid)), (_("Move up"), lambda: ed.move(bid, -1)),
                 (_("Move down"), lambda: ed.move(bid, 1)), None, (_("Delete"), lambda: ed.delete(bid, undo=True), True)]
        menu_popover(parent, ents).popup()

    # --- Tippen -----------------------------------------------------------------------------------
    def on_changed(self, rt):
        b, field = rt.hk_block, rt.hk_field
        html = rt.html()
        if rt.hk_item is not None:
            rt.hk_item["content"] = html
        else:
            b.setdefault("data", {})[field] = html
        self.synced(rt)
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
        if rt.hk_block.get("type") == "paragraph" and rt.hk_item is None and text.startswith("/") and text.count(" ") <= 1 \
                and not text.startswith("/ ") and "\n" not in text and len(text) <= 24 and sg.closed_for != text:
            # fremde Seite: Aufgaben, Kalender und Immich gehoeren dem Konto - dort nicht anbieten
            skip = ("tasks", "calendar", "immich_image") if getattr(self.view, "foreign", False) else ()
            from .nextcloud import supported as nc_ok
            if not nc_ok() or not hasattr(self.view, "pick_nextcloud"):    # Nextcloud-Datei: Server ab 2.3
                skip += ("nextcloud",)
            items = slash_matches(text[1:], skip)
            if not items and " " in text:                     # "/ueberschrift 2" ja, "/x und weiter" ist normaler Text
                sg.hide()
                return
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
        if ed.locked:
            # gesperrt bzw. nur Lesen/Kommentieren: keine Formatierung, kein Teilen/Verschieben/Umwandeln von Bloecken;
            # Pfeile, Esc, Kopieren und die Befehlspalette bleiben
            if is_menu_key(kv, state):
                self.readonly_menu(rt, bid)
                return True
            fmt_keys = (Gdk.KEY_b, Gdk.KEY_i, Gdk.KEY_u, Gdk.KEY_e, Gdk.KEY_X, Gdk.KEY_x, Gdk.KEY_H, Gdk.KEY_h, Gdk.KEY_Up,
                        Gdk.KEY_Down, Gdk.KEY_d, Gdk.KEY_Return, Gdk.KEY_KP_Enter)
            if (ctrl and alt) or (ctrl and kv in fmt_keys) or \
                    (not ctrl and kv in (Gdk.KEY_Return, Gdk.KEY_KP_Enter, Gdk.KEY_BackSpace, Gdk.KEY_Tab, Gdk.KEY_ISO_Left_Tab)):
                return True
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
        if is_menu_key(kv, state):
            self.block_menu(rt, bid)
            return True
        if kv == Gdk.KEY_Tab and rt.hk_item is not None and not ctrl:
            self._indent(rt, -1 if shift else 1)
            return True
        if kv == Gdk.KEY_ISO_Left_Tab and rt.hk_item is not None and not ctrl:
            self._indent(rt, -1)
            return True
        if (kv == Gdk.KEY_ISO_Left_Tab or (kv == Gdk.KEY_Tab and shift)) and rt.hk_item is None and not ctrl and not rt.raw \
                and ed.toggle_of(bid):
            return ed.out_of_toggle(bid)                      # Umschalt+Tab: aus dem Toggle heraus
        if kv == Gdk.KEY_Tab and rt.raw and not ctrl and not shift:
            rt.get_buffer().insert_at_cursor("\t")          # Code: Tabulator; Strg+Tab verlaesst den Block
            return True
        if kv in (Gdk.KEY_Tab, Gdk.KEY_ISO_Left_Tab):
            return False                                      # sonst: Fokus zum naechsten/vorigen Element
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
        if t == "paragraph" and not before and not after:
            r = ed.toggle_of(bid)                             # leere letzte Zeile im Toggle: Enter fuehrt heraus (wie Notion)
            if r and r[1] and r[1][-1].get("id") == bid:
                return ed.out_of_toggle(bid, drop_empty=True)
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
