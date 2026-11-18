"""Saga-Siegel (2.5, Bugs-DB 2156) in der Linux-App: Auswahl im Symbol-Dialog (Bereich "Saga-Siegel").
Vertrag docs/APP-API.md "Saga-Siegel als Seitensymbol (2.5)": 36 Motive in drei Farben, Pfad /siegel/<farbe>/<motiv>.svg,
Liste GET api/siegel (Namen in der Sprache der Anfrage), nur wenn der Server "siegel1" in /api/version meldet (und bei
Seiten anderer Instanzen deren Server "siegel1" in remote.caps). Anzeige ueber images.page_icon (PNG mit ?w=).
Wie im Web: die zuletzt gewaehlte Farbe wird gemerkt; ist das aktuelle Symbol ein Siegel, ist dessen Farbe vorgewaehlt."""
import re

from gi.repository import Gtk

from . import config, images
from .api import client
from .i18n import _, ui_language
from .util import bg

LIST_PATH = "api/siegel"
_ID = re.compile(r"[a-z]{2,20}")
SETTING = "siegel_color"                      # zuletzt gewaehlte Farbe (wie hk-siegel-farbe im Web)
TILE = 44                                     # Kachel im Raster (px)
MAX_MOTIFS = 64

_cache = {}                                   # (Server, Sprache) -> Liste (parse)


def offered(store, page_can=None):
    """Auswahl anbieten? Eigener Server meldet siegel1; page_can(cap): Server der Seite (andere Instanz, remote.caps)"""
    return store.has("siegel1") and (page_can is None or page_can("siegel1"))


def path_of(color, motif):
    return f"/siegel/{color}/{motif}.svg"


def _entries(v, limit):
    out, seen = [], set()
    for x in v if isinstance(v, list) else []:
        if not isinstance(x, dict) or not isinstance(x.get("id"), str) or not _ID.fullmatch(x["id"]) or x["id"] in seen:
            continue
        lb = x.get("label") if isinstance(x.get("label"), str) and x.get("label").strip() else x["id"]
        seen.add(x["id"])
        out.append({"id": x["id"], "label": lb.strip()[:60]})
        if len(out) >= limit:
            break
    return out


def parse(j):
    """GET api/siegel -> {"colors": [{id, label}], "motifs": [{id, label}]} oder None (kaputt/leer). Nur Farben/Motive,
    deren Pfad ein gueltiges Siegel ist (images.is_siegel) - nie fremde Adressen aus der Antwort uebernehmen."""
    if not isinstance(j, dict):
        return None
    colors = [c for c in _entries(j.get("colors"), 8) if images.is_siegel(path_of(c["id"], "rabe"))]
    motifs = _entries(j.get("motifs"), MAX_MOTIFS)
    if not colors or not motifs:
        return None
    return {"colors": colors, "motifs": motifs}


def label_of(listing, path):
    """'Rabe (Weinrot)' fuer ein Siegel aus der Liste, sonst ''"""
    p = images.siegel_parts(path)
    if not p or not listing:
        return ""
    c = next((x["label"] for x in listing["colors"] if x["id"] == p[0]), p[0])
    m = next((x["label"] for x in listing["motifs"] if x["id"] == p[1]), p[1])
    return f"{m} ({c})"


def start_color(listing, current):
    """Vorgewaehlte Farbe: die des aktuellen Siegels, sonst die zuletzt gewaehlte, sonst die erste"""
    ids = [c["id"] for c in listing["colors"]]
    p = images.siegel_parts(current)
    if p and p[0] in ids:
        return p[0]
    last = config.settings.get(SETTING)
    return last if last in ids else ids[0]


def matches(motif, q):
    """Suche nach Name oder id (Gross/Klein egal)"""
    q = (q or "").strip().lower()
    return not q or q in (motif["label"] + " " + motif["id"]).lower()


def _key():
    return (config.settings.server, ui_language())


def load(done):
    """done(Liste|None) im Hauptthread - einmal je Server und Sprache geladen, danach aus dem Speicher"""
    k = _key()
    if k in _cache:
        done(_cache[k])
        return

    def fin(j):
        lst = parse(j)
        if lst is not None:
            _cache[k] = lst
        done(lst)
    bg(client.get, LIST_PATH, done=fin, error=lambda e: done(None))


def _a11y_label(w, text):
    try:
        w.update_property([Gtk.AccessibleProperty.LABEL], [text])
    except (TypeError, AttributeError):
        pass


class SiegelPane(Gtk.Box):
    """Bereich "Saga-Siegel": Farbwahl (3 Knoepfe, eine Gruppe), Suche, Raster der Motive. on_pick(pfad)."""
    __gtype_name__ = "HKSiegelPane"

    def __init__(self, current, on_pick):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        self.current, self.on_pick = current, on_pick
        self.listing, self.color, self.tiles = None, None, []
        self.status = Gtk.Label(label=_("Loading …"), css_classes=["dim-label"], vexpand=True, valign=Gtk.Align.CENTER)
        self.append(self.status)
        load(self._loaded)

    def _loaded(self, lst):
        if lst is None:
            self.status.set_label(_("Saga seals could not be loaded."))
            return
        self.remove(self.status)
        self.listing = lst
        self.color = start_color(lst, self.current)
        colors = Gtk.Box(css_classes=["linked"], halign=Gtk.Align.CENTER, accessible_role=Gtk.AccessibleRole.GROUP)
        _a11y_label(colors, _("Seal color"))
        self.cbtns, first = {}, None
        for c in lst["colors"]:
            b = Gtk.ToggleButton(label=c["label"], css_classes=["hk-siegel-c", "hk-siegel-c-" + c["id"]])
            if first is None:
                first = b
            else:
                b.set_group(first)
            b.set_active(c["id"] == self.color)
            b.connect("toggled", lambda b, cid=c["id"]: b.get_active() and self._color(cid))
            colors.append(b)
            self.cbtns[c["id"]] = b
        self.append(colors)
        self.search = Gtk.SearchEntry(placeholder_text=_("Search seals …"))
        _a11y_label(self.search, _("Search seals …"))
        self.search.connect("search-changed", lambda *_: self._filter())
        self.search.connect("activate", lambda *_: self._first())
        self.append(self.search)
        self.grid = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=12, min_children_per_line=4,
                                homogeneous=True, row_spacing=4, column_spacing=4, activate_on_single_click=True,
                                valign=Gtk.Align.START)
        self.grid.add_css_class("hk-siegel-grid")
        _a11y_label(self.grid, _("Saga seals"))
        self.grid.connect("child-activated", lambda g, ch: self._pick(ch.hk_motif))
        for m in lst["motifs"]:
            ch = Gtk.FlowBoxChild()
            ch.hk_motif = m
            ch.add_css_class("hk-siegel-tile")
            self.grid.append(ch)
            self.tiles.append(ch)
        self.grid.set_filter_func(lambda ch: matches(ch.hk_motif, self.search.get_text()))
        sw = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        sw.set_child(self.grid)
        self.append(sw)
        self.none = Gtk.Label(label=_("No seal found."), css_classes=["dim-label"], visible=False)
        self.append(self.none)
        self._draw()

    def _color(self, cid):
        if cid != self.color:
            self.color = cid
            config.settings.set(SETTING, cid)
            self._draw()

    def _draw(self):
        cl = next(c["label"] for c in self.listing["colors"] if c["id"] == self.color)
        for ch in self.tiles:
            m = ch.hk_motif
            path = path_of(self.color, m["id"])
            v = images.page_icon(path, size=TILE - 4)
            ch.set_child(v)
            name = f"{m['label']} ({cl})"
            ch.set_tooltip_text(name)
            _a11y_label(ch, _("Seal {name}").format(name=name))
            if path == self.current:
                ch.add_css_class("hk-on")
            else:
                ch.remove_css_class("hk-on")

    def _filter(self):
        self.grid.invalidate_filter()
        self.none.set_visible(not any(matches(ch.hk_motif, self.search.get_text()) for ch in self.tiles))

    def _first(self):
        m = next((ch.hk_motif for ch in self.tiles if matches(ch.hk_motif, self.search.get_text())), None)
        if m:
            self._pick(m)

    def _pick(self, m):
        config.settings.set(SETTING, self.color)
        self.on_pick(path_of(self.color, m["id"]))
