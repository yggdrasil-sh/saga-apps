"""Widget "Unterseiten" (Saga 2.5, subpages1): direkte Unterseiten als Liste oder Galerie - Inhalt einer Widget-Karte.
Daten kommen fertig vom Server (W.data, pagewidgets.subpages_data); hier nur Anzeige und Oeffnen.
Galerie: eigenes umbrechendes Raster (Grid: so viele Spalten wie mit der Wunschbreite passen, mindestens 2, alle gleich
breit - Gtk.FlowBox rechnet die Hoehe falsch, sobald die Mindest-Spaltenzahl greift), je Karte ein Knopf mit Bildflaeche
(cover 16:9, content 2:3, none ohne) und darunter Symbol + Titel (hoechstens 2 Zeilen). Bilder laedt images.load im Hintergrund, erst wenn die Karte sichtbar
wird. Liste: Gtk.ListBox, je Zeile Symbol + Titel. Fokus je Karte/Zeile, Eingabe oeffnet die Seite."""
from gi.repository import Graphene, Gsk, Gtk, Pango

from . import pagewidgets as pw
from .i18n import _

THUMB_MIN = 72                      # kleinste Kartenbreite (schmales Fenster: trotzdem 2 Spalten)
PH_ICON = {"s": 40, "m": 52, "l": 64}


class Thumb(Gtk.Widget):
    """Bildflaeche mit festem Seitenverhaeltnis (Hoehe aus der Breite). Bild fuellt die Flaeche; ohne Bild (oder wenn es
    sich nicht laden laesst) ruhige Flaeche mit grossem Seitensymbol."""
    __gtype_name__ = "HKSubThumb"

    def __init__(self, ratio, natw, img=None, w=None, icon="", fallback="📄", icon_px=52):
        super().__init__()
        self.ratio, self.natw = ratio, natw
        self.img, self.wparam = img, w
        self._icon, self._fallback, self._icon_px = icon, fallback, icon_px
        self.child = None
        self.state = "none"                             # none | loading | image | placeholder
        self.set_overflow(Gtk.Overflow.HIDDEN)
        self.add_css_class("hk-sp-pv")
        self.set_accessible_role(Gtk.AccessibleRole.PRESENTATION) if hasattr(self, "set_accessible_role") else None
        if not img:
            self._placeholder()
        self.connect("destroy", lambda *_: self._set_child(None))

    def _set_child(self, c):
        if self.child is not None:
            self.child.unparent()
        self.child = c
        if c is not None:
            c.set_parent(self)
        self.queue_allocate()

    def _placeholder(self):
        from .images import page_icon
        ic = page_icon(self._icon, size=self._icon_px, fallback=self._fallback)
        ic.set_halign(Gtk.Align.CENTER)
        ic.set_valign(Gtk.Align.CENTER)
        ic.add_css_class("hk-sp-ph")
        self.state = "placeholder"
        self._set_child(ic)

    def _loaded(self, dec):
        if self.state == "image" and dec is None:
            return
        if dec is None:
            self._placeholder()
            return
        from .images import ImageView
        if self.state == "image" and self.child is not None:
            self.child.set_decoded(dec)                  # Animation: zweiter Aufruf mit allen Bildern
            return
        v = ImageView(mode="cover", valign=50.0)
        v.set_decoded(dec)
        self.state = "image"
        self._set_child(v)

    def do_map(self):
        Gtk.Widget.do_map(self)
        if self.img and self.state == "none":            # erst laden, wenn die Karte sichtbar wird; nie im Hauptfaden
            from .images import load
            self.state = "loading"
            load(self.img, self._loaded, w=self.wparam, max_px=800)

    def do_get_request_mode(self):
        return Gtk.SizeRequestMode.HEIGHT_FOR_WIDTH

    def do_measure(self, orientation, for_size):
        if orientation == Gtk.Orientation.HORIZONTAL:
            return THUMB_MIN, max(THUMB_MIN, self.natw), -1, -1
        w = for_size if for_size >= 0 else THUMB_MIN
        h = max(1, int(round(w / self.ratio)))
        if for_size < 0:
            return h, max(h, int(round(self.natw / self.ratio))), -1, -1
        return h, h, -1, -1

    def do_size_allocate(self, w, h, baseline):
        c = self.child
        if c is None:
            return
        c.measure(Gtk.Orientation.HORIZONTAL, -1)
        if self.state == "image":
            c.allocate(w, h, -1, None)
            return
        cw = min(w, max(c.measure(Gtk.Orientation.HORIZONTAL, -1)[1], 1))
        ch = min(h, max(c.measure(Gtk.Orientation.VERTICAL, cw)[1], 1))
        tr = Gsk.Transform().translate(Graphene.Point().init((w - cw) / 2, (h - ch) / 2))
        c.allocate(cw, ch, -1, tr)

    def do_snapshot(self, snap):
        if self.child is not None:
            self.snapshot_child(self.child, snap)


class Grid(Gtk.Widget):
    """Umbrechendes Raster gleich breiter Karten (wie CSS grid auto-fill minmax(natw, 1fr)): Spalten = was mit der
    Wunschbreite natw passt, mindestens min_cols; die Hoehe folgt aus der Breite, je Zeile die hoechste Karte."""
    __gtype_name__ = "HKSubGrid"

    def __init__(self, natw, min_cols=2, col_gap=12, row_gap=14):
        super().__init__()
        self.natw, self.min_cols, self.col_gap, self.row_gap = natw, min_cols, col_gap, row_gap
        self.items = []
        self.set_hexpand(True)
        self.connect("destroy", lambda *_: self._drop())

    def _drop(self):
        for c in self.items:
            c.unparent()
        self.items = []

    def add(self, child):
        self.items.append(child)
        child.set_parent(self)
        self.queue_resize()

    def columns(self, width):
        """-> (Spalten, Kartenbreite) fuer eine Gesamtbreite"""
        n = max(self.min_cols, (max(0, width) + self.col_gap) // (self.natw + self.col_gap))
        return n, max(1, (width - (n - 1) * self.col_gap) // n)

    def _rows(self, width):
        """-> (Kartenbreite, [Zeilenhoehe])"""
        n, iw = self.columns(width)
        rows = []
        for i in range(0, len(self.items), n):
            rows.append(max(max(c.measure(Gtk.Orientation.VERTICAL, iw)[:2]) for c in self.items[i:i + n]))
        return n, iw, rows

    def _min_item(self):
        return max([c.measure(Gtk.Orientation.HORIZONTAL, -1)[0] for c in self.items] or [0])

    def do_get_request_mode(self):
        return Gtk.SizeRequestMode.HEIGHT_FOR_WIDTH

    def do_measure(self, orientation, for_size):
        mn = self.min_cols * self._min_item() + (self.min_cols - 1) * self.col_gap
        if orientation == Gtk.Orientation.HORIZONTAL:
            n = max(self.min_cols, min(len(self.items), 4))
            return mn, max(mn, n * self.natw + (n - 1) * self.col_gap), -1, -1
        _n, _iw, rows = self._rows(for_size if for_size >= 0 else mn)
        h = sum(rows) + max(0, len(rows) - 1) * self.row_gap
        return h, h, -1, -1

    def do_size_allocate(self, w, h, baseline):
        n, iw, rows = self._rows(w)
        rtl = self.get_direction() == Gtk.TextDirection.RTL
        y = 0
        for r, rh in enumerate(rows):
            for k, c in enumerate(self.items[r * n:(r + 1) * n]):
                x = k * (iw + self.col_gap)
                if rtl:
                    x = w - x - iw
                c.allocate(iw, rh, -1, Gsk.Transform().translate(Graphene.Point().init(x, y)))
            y += rh + self.row_gap


def _fallback(item):
    return "🗃️" if item.get("kind") == "database" else "📄"


def _icon(item, size):
    from .images import page_icon
    ic = page_icon(item.get("icon"), size=size, fallback=_fallback(item))
    ic.set_valign(Gtk.Align.START)
    return ic


def _label(w, text):
    try:
        w.update_property([Gtk.AccessibleProperty.LABEL], [text])
    except (TypeError, AttributeError):
        pass


def _gallery(spec, cfg, items, open_note):
    image = cfg["image"]
    natw = pw.SUB_WIDTH[image][spec.size]
    grid = Grid(natw)
    grid.add_css_class("hk-sp-grid")
    grid.add_css_class("hk-sp-i-" + image)
    grid.set_accessible_role(Gtk.AccessibleRole.GROUP) if hasattr(grid, "set_accessible_role") else None
    _label(grid, spec.title or _("Subpages"))
    for it in items:
        title = pw.subpage_title(it)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        box.add_css_class("hk-sp-card")
        with_icon = True
        if image != "none":
            box.append(Thumb(pw.SUB_RATIO[image], natw - 8, img=it["img"], w=pw.SUB_THUMB_W[image], icon=it["icon"],
                             fallback=_fallback(it), icon_px=PH_ICON[spec.size]))
            with_icon = bool(it["img"])                  # der Platzhalter zeigt das Symbol schon gross
        row = Gtk.Box(spacing=5, valign=Gtk.Align.START)
        row.add_css_class("hk-sp-row")
        if with_icon:
            row.append(_icon(it, 18))
        lb = Gtk.Label(label=title, xalign=0, yalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, lines=2,
                       ellipsize=Pango.EllipsizeMode.END, hexpand=True, width_chars=4, max_width_chars=6)
        lb.add_css_class("hk-sp-t")
        row.append(lb)
        box.append(row)
        b = Gtk.Button(child=box)                        # Fokus je Karte, Eingabe/Leertaste/Klick oeffnet
        b.add_css_class("flat")
        b.add_css_class("hk-sp-it")
        b.set_tooltip_text(title)
        b.hk_note = it["id"]
        _label(b, pw.subpage_label(it))
        b.connect("clicked", lambda bt: open_note(bt.hk_note))
        grid.add(b)
    return grid


def _list(spec, items, open_note):
    lst = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, activate_on_single_click=True)
    lst.add_css_class("hk-sp-list")
    _label(lst, spec.title or _("Subpages"))
    px = {"s": 18, "m": 20, "l": 26}[spec.size]
    for it in items:
        title = pw.subpage_title(it)
        row = Gtk.Box(spacing=8)
        ic = _icon(it, px)
        ic.set_valign(Gtk.Align.CENTER)
        row.append(ic)
        lb = Gtk.Label(label=title, xalign=0, ellipsize=Pango.EllipsizeMode.END, hexpand=True)
        lb.add_css_class("hk-sp-t")
        row.append(lb)
        r = Gtk.ListBoxRow(child=row, activatable=True, focusable=True)
        r.add_css_class("hk-sp-it")
        r.set_tooltip_text(title)
        r.hk_note = it["id"]
        _label(r, pw.subpage_label(it))
        lst.append(r)
    lst.connect("row-activated", lambda _l, r: open_note(r.hk_note))
    return lst


def subpages_box(spec, data, open_note, add=None):
    """spec: pagewidgets.Spec, data: W.data. open_note(id) oeffnet eine Unterseite; add() legt eine an (oder None)."""
    cfg = pw.subpages_cfg(spec.cfg)
    d = pw.subpages_data(data)
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
    box.add_css_class("hk-sp")
    box.hk_subpages = d
    if not d["items"]:
        em = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, halign=Gtk.Align.START)
        em.add_css_class("hk-sp-empty")
        t = Gtk.Label(label=_("No subpages yet"), xalign=0, wrap=True)
        t.add_css_class("dim-label")
        em.append(t)
        if d["can_add"] and add is not None:
            b = Gtk.Button(label=_("Add subpage"), halign=Gtk.Align.START)
            b.add_css_class("hk-sp-new")
            b.connect("clicked", lambda *_: add())
            em.append(b)
        else:
            h = Gtk.Label(label=_("Pages placed below this page appear here."), xalign=0, wrap=True)
            h.add_css_class("caption")
            h.add_css_class("dim-label")
            em.append(h)
        box.append(em)
        return box
    box.append(_gallery(spec, cfg, d["items"], open_note) if cfg["view"] == "gallery" else _list(spec, d["items"], open_note))
    if d["more"] > 0:
        m = Gtk.Label(label=pw.subpages_more(d["more"]), xalign=0)
        m.add_css_class("caption")
        m.add_css_class("dim-label")
        m.add_css_class("hk-sp-more")
        box.append(m)
    return box
