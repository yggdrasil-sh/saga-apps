"""Seite gestalten (Saga 2.5, Vertrag SPEC-gestalten): Felder lesen und begrenzen - reine Logik ohne GTK (tests/test_gestalten.py).

Alles ist optional: fehlt ein Feld oder ist es ungueltig, gilt das bisherige Verhalten. Die Werte werden nur gelesen;
beim Speichern bleibt der Block unveraendert (Regel 0: unbekannte data-Schluessel und block.tunes zurueckschreiben).
- G1 columns.data.cols[i].w: relative Gewichte (nur wenn alle Spalten ein gueltiges w haben), je Spalte min. 0,15
- G2 image/embed data.width (10-100 %) und data.align (left|center|right); image.data.stretched gewinnt
- G3 block.tunes.hkstyle = {"c": <Palette>, "bg": bool} fuer Text-Bloecke
- G9 Seite: font ("serif" | "mono" | null) und small (bool)
- G10 toggle.data.level 0-3 (1-3: Titel in Ueberschriftengroesse)"""
import math

PALETTE = ("gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red")
STYLE_TYPES = frozenset(("paragraph", "header", "list", "quote", "toggle", "callout"))   # Checkliste = list
MIN_COL = 0.15
ALIGNS = ("left", "center", "right")
FONTS = ("serif", "mono")


def _num(v):
    """Zahl (kein bool, endlich) oder None"""
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return None
    v = float(v)
    return v if math.isfinite(v) else None


def col_weights(cols):
    """Anteile der Spalten (Summe 1) oder None (= alle gleich breit). Wie der Server: alle Spalten brauchen ein w > 0,
    normiert, je Spalte mindestens MIN_COL (fremde/alte Server liefern evtl. Unbegrenztes)."""
    if not isinstance(cols, list) or not (2 <= len(cols) <= 4):
        return None
    ws = []
    for c in cols:
        w = _num(c.get("w")) if isinstance(c, dict) else None
        if w is None or w <= 0:
            return None
        ws.append(w)
    total = sum(ws)
    ws = [w / total for w in ws]
    # Mindestbreite: zu schmale Spalten anheben, den Rest anteilig verkleinern (wiederholt, bis alles passt)
    for _i in range(len(ws)):
        low = [i for i, w in enumerate(ws) if w < MIN_COL - 1e-9]
        if not low:
            break
        rest = [i for i in range(len(ws)) if i not in low]
        free = 1.0 - MIN_COL * len(low)
        rs = sum(ws[i] for i in rest)
        for i in low:
            ws[i] = MIN_COL
        for i in rest:
            ws[i] = ws[i] / rs * free if rs > 0 else free / len(rest)
    if all(abs(w - ws[0]) < 1e-6 for w in ws):
        return None                                   # gleich breit: wie bisher
    return ws


def media_layout(block, narrow=False):
    """(anteil, ausrichtung) fuer Bild bzw. Player-Embed: anteil 0,1-1,0 oder None (= wie bisher), ausrichtung
    'left'|'center'|'right' oder None (nichts angegeben). Telefon/schmal: mindestens 50 %."""
    if not isinstance(block, dict):
        return None, None
    d = block.get("data") if isinstance(block.get("data"), dict) else {}
    if block.get("type") == "image" and d.get("stretched") is True:
        return None, None                             # volle Breite gewinnt
    al = d.get("align") if d.get("align") in ALIGNS else None
    w = _num(d.get("width"))
    if w is None:
        return None, al
    w = max(10, min(100, int(round(w))))
    if narrow:
        w = max(50, w)
    if w >= 100:
        return None, al
    return w / 100.0, al or "center"


def hkstyle(block):
    """(farbe, hintergrund?) aus block.tunes.hkstyle oder None - nur Palette, nur erlaubte Typen"""
    if not isinstance(block, dict) or block.get("type") not in STYLE_TYPES:
        return None
    t = block.get("tunes")
    s = t.get("hkstyle") if isinstance(t, dict) else None
    if not isinstance(s, dict) or s.get("c") not in PALETTE:
        return None
    return s["c"], s.get("bg") is True


def set_hkstyle(block, color, bg=False):
    """Farbe setzen (color None = Standard). Andere tunes-Schluessel bleiben unveraendert."""
    t = block.get("tunes")
    if color is None:
        if isinstance(t, dict):
            t.pop("hkstyle", None)
            if not t:
                block.pop("tunes", None)
        return
    if color not in PALETTE:
        return
    if not isinstance(t, dict):
        t = block["tunes"] = {}
    t["hkstyle"] = {"c": color, "bg": True} if bg else {"c": color}


def style_css(block):
    """CSS-Klasse fuer die Block-Farbe ('hk-fg-red' bzw. 'hk-bg-red') oder None"""
    s = hkstyle(block)
    if s is None:
        return None
    return ("hk-bg-" if s[1] else "hk-fg-") + s[0]


def toggle_level(data):
    """0 (normaler Toggle) oder 1-3 (Toggle-Ueberschrift)"""
    v = _num((data or {}).get("level")) if isinstance(data, dict) else None
    if v is None:
        return 0
    return max(0, min(3, int(v)))


def page_font(page):
    """'serif' | 'mono' | None (Standard)"""
    f = (page or {}).get("font") if isinstance(page, dict) else None
    return f if f in FONTS else None


def page_small(page):
    return isinstance(page, dict) and page.get("small") is True


def page_css(page):
    """CSS-Klassen fuer die Seite (Schrift, kleiner Text)"""
    out = []
    f = page_font(page)
    if f:
        out.append("hk-font-" + f)
    if page_small(page):
        out.append("hk-small-text")
    return out


def design_supported(page):
    """Server kennt Schrift/kleinen Text (liefert die Felder in GET) - sonst gibt es im Seitenmenue nichts umzuschalten"""
    return isinstance(page, dict) and ("small" in page or "font" in page)


def block_url(page_url, page_id, bid):
    """Adresse der Seite im Web, mit Block-Anker (wie 'Link zum Block kopieren'), wenn die Seite auf diesem Server liegt"""
    if bid and isinstance(page_id, int) and not isinstance(page_id, bool) and page_id > 0:
        return f"{page_url}:{bid}"
    return page_url
