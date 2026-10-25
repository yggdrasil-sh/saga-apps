"""Graph der Seiten (2.5, wie Obsidian) - Logik ohne GTK: Daten aufbereiten (Filter, Tag-Knoten, lokaler Graph),
Kraefte-Simulation (Abstossung mit Barnes-Hut ab ~300 Knoten, Federn an Kanten, Zug zur Mitte, Abkuehlen bis Stillstand),
Kamera (Zoom, Verschieben, Einpassen), Treffer, Hervorhebung mit weichem Uebergang und Tastatur-Navigation.
Vorbild: static/graph.js im Web. Reines Python (keine neue Abhaengigkeit), damit es ohne Bildschirm testbar ist.

Daten: GET api/graph -> {nodes: [[id, titel, symbol, art, eigen, eltern]], edges: [[a, b, art]], tags: [[name, [ids]]],
truncated}. Knotenart page | database | row, Kantenart l = Verweis, c = Unterseite, r = Relation (t = Tag, nur hier)."""
import math
import time

DEFAULTS = {"link": True, "child": True, "rel": True, "tags": False, "orphans": True, "depth": 1}
DUR = 0.18                      # s fuer Hervorheben/Abblenden (~180 ms wie im Web)
BH_MIN = 100                     # ab so vielen Knoten Barnes-Hut (Web: ~300; in Python lohnt es sich frueher)
THETA2 = 0.81                   # Barnes-Hut: Zelle als ein Koerper, wenn (Groesse / Abstand)^2 < THETA2
THETA2_BIG = 1.44               # ab 1000 Knoten grober (theta 1.2): reines Python, ~1/3 schneller
LEAF = 8                        # hoechstens so viele Knoten je Blatt des Quadtree
FAR2 = float("inf")             # keine Reichweitengrenze: die Grenze des Webs (1000) gibt hier Ringe im Layout
HIT_MOUSE, HIT_TOUCH = 10, 22   # Mindest-Trefferflaeche in Pixeln


def ease(x):
    return x * x * (3 - 2 * x)


class Node:
    __slots__ = ("id", "title", "icon", "kind", "own", "parent", "deg", "tags", "tag", "x", "y", "vx", "vy", "e", "g")

    def __init__(self, nid, title, icon="", kind="page", own=True, parent=None, tag=False):
        self.id, self.title, self.icon, self.kind, self.own, self.parent = nid, title, icon, kind, own, parent
        self.deg, self.tags, self.tag = 0, [], tag
        self.x = self.y = self.vx = self.vy = 0.0
        self.e = self.g = 0.0          # e: gehoert zur Hervorhebung (0..1), g: waechst (ueberfahren, 0..1)

    def label(self):
        ic = self.icon if self.icon and len(self.icon) <= 4 else ""
        return (ic + " " if ic else "") + self.title

    def __repr__(self):
        return f"Node({self.id!r}, {self.title!r})"


class Edge:
    __slots__ = ("s", "t", "k", "e")

    def __init__(self, s, t, k):
        self.s, self.t, self.k, self.e = s, t, k, 0.0


class Graph:
    def __init__(self, nodes=(), edges=(), missing=False, truncated=False):
        self.nodes, self.edges, self.missing, self.truncated = list(nodes), list(edges), missing, truncated

    def kinds(self):
        """welche Arten vorkommen (fuer die Legende): page/database/row/tag/shared"""
        has = set()
        for n in self.nodes:
            has.add("tag" if n.tag else n.kind)
            if not n.own:
                has.add("shared")
        return has


def valid(d):
    """Antwort von api/graph pruefen (alte oder kaputte Antworten nicht zeichnen)"""
    return isinstance(d, dict) and isinstance(d.get("nodes"), list) and isinstance(d.get("edges"), list)


def build(d, prefs=None, focus=None, depth=1, untitled="Untitled"):
    """Graph fuer die Anzeige: Kantenfilter, Tags als Knoten, Waisen, lokal (Breitensuche ab focus bis depth)."""
    p = dict(DEFAULTS)
    p.update(prefs or {})
    by_id, nodes, edges = {}, [], []
    for n in d.get("nodes") or []:
        if not isinstance(n, (list, tuple)) or len(n) < 4 or n[0] in by_id:
            continue
        o = Node(n[0], str(n[1] or "") or untitled, str(n[2] or ""), n[3] if n[3] in ("page", "database", "row") else "page",
                 bool(n[4]) if len(n) > 4 else True, n[5] if len(n) > 5 else None)
        by_id[o.id] = o
        nodes.append(o)
    seen_e = set()

    def add(a, b, k):
        if a in by_id and b in by_id and a != b and (a, b, k) not in seen_e:
            seen_e.add((a, b, k))
            edges.append(Edge(by_id[a], by_id[b], k))
    on = {"l": p["link"], "c": p["child"], "r": p["rel"]}
    for e in d.get("edges") or []:
        if isinstance(e, (list, tuple)) and len(e) >= 3 and on.get(e[2]):
            add(e[0], e[1], e[2])
    for tg in d.get("tags") or []:
        if not isinstance(tg, (list, tuple)) or len(tg) < 2 or not isinstance(tg[1], list):
            continue
        name, ids = str(tg[0]), [i for i in tg[1] if i in by_id]
        for i in ids:
            by_id[i].tags.append(name)
        if not p["tags"] or not ids:
            continue
        key = "tag:" + name
        tn = Node(key, "#" + name, kind="tag", tag=True)
        by_id[key] = tn
        nodes.append(tn)
        for i in ids:
            add(i, key, "t")
    if focus is not None:                                  # lokal: nur Nachbarn bis zur gewaehlten Tiefe
        if focus not in by_id:
            return Graph(missing=True, truncated=bool(d.get("truncated")))
        adj = {}
        for e in edges:
            adj.setdefault(e.s.id, []).append(e.t.id)
            adj.setdefault(e.t.id, []).append(e.s.id)
        dist, q, qi = {focus: 0}, [focus], 0
        while qi < len(q):
            x = q[qi]
            qi += 1
            if dist[x] >= max(1, depth):
                continue
            for y in adj.get(x, ()):
                if y not in dist:
                    dist[y] = dist[x] + 1
                    q.append(y)
        nodes = [n for n in nodes if n.id in dist]
        edges = [e for e in edges if e.s.id in dist and e.t.id in dist]
    for e in edges:
        e.s.deg += 1
        e.t.deg += 1
    if not p["orphans"] and focus is None:
        nodes = [n for n in nodes if n.deg > 0]
    return Graph(nodes, edges, truncated=bool(d.get("truncated")))


def neighbors(g):
    """id -> Menge der Nachbar-ids"""
    adj = {}
    for e in g.edges:
        adj.setdefault(e.s.id, set()).add(e.t.id)
        adj.setdefault(e.t.id, set()).add(e.s.id)
    return adj


def rows(g, adj=None):
    """Listenansicht (Screenreader): (Knoten, Anzahl Verbindungen), meist verbundene zuerst, dann nach Titel"""
    adj = adj if adj is not None else neighbors(g)
    return sorted(((n, len(adj.get(n.id, ()))) for n in g.nodes), key=lambda r: (-r[1], r[0].title.lower(), str(r[0].id)))


def matches(g, query):
    """Suche: Knoten, deren Titel den Text enthaelt (ohne Gross/Klein), in Reihenfolge des Graphen"""
    q = (query or "").strip().lower()
    return [n for n in g.nodes if q in n.title.lower()] if q else []


# --- Abstossung ------------------------------------------------------------------------------------------------------
def _quadtree(xs, ys):
    """Quadtree als flache Listen: je Zelle Schwerpunkt (cx, cy), Masse m, Groesse^2, Kinder bzw. Blatt-Knoten.
    Von oben nach unten aufgeteilt (Listen statt Einfuegen einzeln) - in Python deutlich schneller."""
    cx, cy, cm, cs2, kids, leaf = [], [], [], [], [], []
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    s0 = max(x1 - x0, y1 - y0) + 1.0
    stack = [(list(range(len(xs))), x0, y0, s0, -1, 0)]   # (Knoten, x, y, Groesse, Elternzelle, Tiefe)
    while stack:
        idx, x, y, s, par, depth = stack.pop()
        c = len(cm)
        n = len(idx)
        sx = sy = 0.0
        for i in idx:
            sx += xs[i]
            sy += ys[i]
        cx.append(sx / n)
        cy.append(sy / n)
        cm.append(n)
        cs2.append(s * s)
        if par >= 0:
            kids[par].append(c)
        if n <= LEAF or depth > 30:
            kids.append(None)
            leaf.append(idx)
            continue
        kids.append([])
        leaf.append(None)
        h = s / 2
        mx, my = x + h, y + h
        q0, q1, q2, q3 = [], [], [], []
        for i in idx:
            if xs[i] < mx:
                (q0 if ys[i] < my else q2).append(i)
            else:
                (q1 if ys[i] < my else q3).append(i)
        for q, qx, qy in ((q0, x, y), (q1, mx, y), (q2, x, my), (q3, mx, my)):
            if q:
                stack.append((q, qx, qy, h, c, depth + 1))
    return cx, cy, cm, cs2, kids, leaf


def repel(xs, ys, f0, fx, fy, bh=None):
    """Abstossung auf fx/fy addieren (Kraft ~ 1/Abstand). bh: Barnes-Hut erzwingen (True) / aus (False) / automatisch."""
    n = len(xs)
    if n < 2:
        return
    if bh is None:
        bh = n >= BH_MIN
    if not bh:
        for i in range(n):
            xi, yi = xs[i], ys[i]
            ax = ay = 0.0
            for j in range(n):
                if j == i:
                    continue
                dx, dy = xs[j] - xi, ys[j] - yi
                d2 = dx * dx + dy * dy + 0.01
                if d2 > FAR2:
                    continue
                f = f0 / d2
                ax -= dx * f
                ay -= dy * f
            fx[i] += ax
            fy[i] += ay
        return
    cx, cy, cm, cs2, kids, leaf = _quadtree(xs, ys)
    th2 = THETA2 if n < 1000 else THETA2_BIG
    for i in range(n):
        xi, yi = xs[i], ys[i]
        ax = ay = 0.0
        st = [0]
        pop, push = st.pop, st.append
        while st:
            c = pop()
            dx, dy = cx[c] - xi, cy[c] - yi
            d2 = dx * dx + dy * dy + 0.01
            lf = leaf[c]
            if lf is None:
                if cs2[c] < th2 * d2:                   # weit weg: ganze Zelle als ein Koerper
                    if d2 <= FAR2:
                        f = f0 * cm[c] / d2
                        ax -= dx * f
                        ay -= dy * f
                    continue
                for k in kids[c]:
                    push(k)
                continue
            if len(lf) > 1 and cs2[c] < th2 * d2 and i not in lf:
                if d2 <= FAR2:
                    f = f0 * cm[c] / d2
                    ax -= dx * f
                    ay -= dy * f
                continue
            for j in lf:                                   # Blatt in der Naehe: genau
                if j == i:
                    continue
                dx, dy = xs[j] - xi, ys[j] - yi
                d2 = dx * dx + dy * dy + 0.01
                if d2 > FAR2:
                    continue
                f = f0 / d2
                ax -= dx * f
                ay -= dy * f
        fx[i] += ax
        fy[i] += ay


class Sim:
    """Kraefte-Simulation. alpha kuehlt je Schritt ab; unter ALPHA_MIN steht sie still (kein Dauerlauf)."""
    ALPHA_MIN = 0.002

    def __init__(self, repel_=0.6, spring=0.5, dist=0.5, center=0.5):
        self.nodes, self.edges = [], []
        self.alpha = 0.0
        self.drag = None                                   # gezogener Knoten: bleibt, wo der Zeiger ist
        self.f0 = 30 + repel_ * 600
        self.spring = 0.02 + spring * 0.25
        self.len = 20 + dist * 120
        self.cen = 0.002 + center * 0.03
        self.ticks = 0

    def decay(self):
        """grosse Graphen kuehlen schneller ab (reines Python: weniger Schritte bis zum Stillstand)"""
        n = len(self.nodes)
        return 0.985 if n <= 500 else 0.975 if n <= 1500 else 0.965

    def set(self, g, keep_from=None, focus=None):
        """Knoten setzen; bekannte behalten ihren Platz (keep_from: alte Knoten), neue auf eine Spirale"""
        old = {n.id: n for n in (keep_from or ())}
        cnt = len(g.nodes)
        R = math.sqrt(cnt + 1) * 18
        for i, n in enumerate(g.nodes):
            o = old.get(n.id)
            if o is not None:
                n.x, n.y, n.vx, n.vy, n.e, n.g = o.x, o.y, o.vx, o.vy, o.e, o.g
            else:
                a, rr = i * 2.399963, R * math.sqrt((i + 0.5) / cnt)
                n.x, n.y, n.vx, n.vy = math.cos(a) * rr, math.sin(a) * rr, 0.0, 0.0
                if n.id == focus:
                    n.x = n.y = 0.0
        self.nodes, self.edges = g.nodes, g.edges
        self.alpha = 0.5 if old else 1.0

    def heat(self, a):
        self.alpha = max(self.alpha, a)

    @property
    def running(self):
        return self.alpha > self.ALPHA_MIN and bool(self.nodes)

    def tick(self):
        nodes, a = self.nodes, self.alpha
        n = len(nodes)
        if not n or a <= 0:
            return
        xs = [nd.x for nd in nodes]
        ys = [nd.y for nd in nodes]
        fx = [0.0] * n
        fy = [0.0] * n
        repel(xs, ys, self.f0 * a, fx, fy)
        for i, nd in enumerate(nodes):
            nd.vx += fx[i]
            nd.vy += fy[i]
        spring, ln = self.spring * a, self.len
        for e in self.edges:
            s, t = e.s, e.t
            dx, dy = t.x - s.x, t.y - s.y
            d = math.sqrt(dx * dx + dy * dy) or 1.0
            f = (d - ln) / d * spring
            bs = s.deg / (s.deg + t.deg)
            t.vx -= dx * f * bs
            t.vy -= dy * f * bs
            s.vx += dx * f * (1 - bs)
            s.vy += dy * f * (1 - bs)
        cen, drag = self.cen * a, self.drag
        for nd in nodes:
            nd.vx -= nd.x * cen
            nd.vy -= nd.y * cen
            if nd is drag:
                nd.vx = nd.vy = 0.0
                continue
            vx, vy = nd.vx * 0.6, nd.vy * 0.6
            v2 = vx * vx + vy * vy
            if v2 > 2500.0:                                # hoechstens 50 je Schritt (Betrag, sonst Ecken im Layout)
                f = 50.0 / math.sqrt(v2)
                vx *= f
                vy *= f
            nd.vx, nd.vy = vx, vy
            nd.x += vx
            nd.y += vy
        self.alpha *= self.decay()
        self.ticks += 1

    def run_for(self, seconds, min_alpha=None):
        """so viele Schritte, wie in die Zeit passen (Vorlauf ohne Bild, je Bild ein Zeitbudget)"""
        t0, lim = time.perf_counter(), self.ALPHA_MIN if min_alpha is None else min_alpha
        k = 0
        while self.alpha > lim and self.nodes:
            self.tick()
            k += 1
            if time.perf_counter() - t0 >= seconds:
                break
        return k

    def settle(self, max_ticks=600):
        """ohne Bewegung (reduzierte Animationen): fertig rechnen"""
        k = 0
        while self.running and k < max_ticks:
            self.tick()
            k += 1
        self.stop()
        return k

    def stop(self):
        """Stillstand: keine Restbewegung mehr"""
        self.alpha = 0.0
        for nd in self.nodes:
            nd.vx = nd.vy = 0.0


def place_labels(boxes, cell=64.0, pad=2.0):
    """Beschriftungen ohne Ueberlappung: boxes [(x0, y0, x1, y1)] in Rangfolge (wichtigste zuerst, z. B. mehr
    Verbindungen bzw. ueberfahren); eine Beschriftung, die eine schon gesetzte beruehrt, entfaellt. -> [bool] je Box.
    Raster aus Zellen (cell px), damit auch einige hundert Beschriftungen je Bild schnell bleiben."""
    grid, out = {}, []
    for x0, y0, x1, y1 in boxes:
        x0, y0, x1, y1 = x0 - pad, y0 - pad, x1 + pad, y1 + pad
        cs = [(i, j) for i in range(int(math.floor(x0 / cell)), int(math.floor(x1 / cell)) + 1)
              for j in range(int(math.floor(y0 / cell)), int(math.floor(y1 / cell)) + 1)]
        hit = False
        for c in cs:
            for a0, b0, a1, b1 in grid.get(c, ()):
                if x0 < a1 and a0 < x1 and y0 < b1 and b0 < y1:
                    hit = True
                    break
            if hit:
                break
        out.append(not hit)
        if not hit:
            for c in cs:
                grid.setdefault(c, []).append((x0, y0, x1, y1))
    return out


class Scene:
    """Kamera, Hervorhebung, Treffer und Tastatur - Bildschirmkoordinaten = Welt * k + (tx, ty)"""

    def __init__(self):
        self.w = self.h = 0
        self.k, self.tx, self.ty = 1.0, 0.0, 0.0
        self.nodes, self.edges, self.adj = [], [], {}
        self.hover = self.sel = None
        self.match = None                                  # Suche: Menge von ids oder None
        self.focus = None                                  # offene Seite (lokal: Mitte, sonst markiert)
        self.dim = 0.0
        self.size = 1.0
        self.cam = None
        self.reduced = False

    def set(self, g):
        self.nodes, self.edges, self.adj = g.nodes, g.edges, neighbors(g)
        ids = {n.id: n for n in g.nodes}
        self.hover = ids.get(self.hover.id) if self.hover else None
        self.sel = ids.get(self.sel.id) if self.sel else None
        if self.match is not None:
            self.match = {i for i in self.match if i in ids}

    def by_id(self, nid):
        for n in self.nodes:
            if n.id == nid:
                return n
        return None

    def resize(self, w, h):
        if not w or not h:
            return
        if not self.w:
            self.tx, self.ty = w / 2, h / 2
        else:
            self.tx += (w - self.w) / 2
            self.ty += (h - self.h) / 2
        self.w, self.h = w, h

    # Groessen: Knoten nach Anzahl der Verbindungen, ueberfahren +35 % und mindestens 5 px auf dem Bildschirm
    def rad(self, n):
        return max((2.5 + math.sqrt(n.deg) * 1.6) * self.size * (0.85 if n.tag else 1), 2.5 / self.k)

    def rr(self, n):
        g, r = ease(n.g), self.rad(n)
        return max(r * (1 + 0.35 * g), (2.5 + 2.5 * g) / self.k) if g else r

    def to_screen(self, x, y):
        return x * self.k + self.tx, y * self.k + self.ty

    def to_world(self, sx, sy):
        return (sx - self.tx) / self.k, (sy - self.ty) / self.k

    def at(self, sx, sy, slop=HIT_MOUSE):
        """Knoten unter dem Zeiger: mindestens slop Pixel um den Knoten, der naechste Rand gewinnt"""
        x, y = self.to_world(sx, sy)
        k, best, bd = self.k, None, float("inf")
        for n in self.nodes:
            dx, dy = n.x - x, n.y - y
            d = math.sqrt(dx * dx + dy * dy) * k
            rs = self.rr(n) * k
            if d < max(rs + 4, slop) and d - rs < bd:
                bd, best = d - rs, n
        return best

    def zoom_at(self, sx, sy, f, lo=0.04, hi=8.0):
        self.cam = None
        k = min(hi, max(lo, self.k * f))
        f = k / self.k
        self.tx = sx - (sx - self.tx) * f
        self.ty = sy - (sy - self.ty) * f
        self.k = k

    def pan(self, dx, dy):
        self.cam = None
        self.tx += dx
        self.ty += dy

    def fit_target(self, nodes=None):
        ns = nodes if nodes is not None else self.nodes
        if not ns or not self.w:
            return None
        x0, x1 = min(n.x for n in ns), max(n.x for n in ns)
        y0, y1 = min(n.y for n in ns), max(n.y for n in ns)
        pad = min(90, min(self.w, self.h) * 0.12)
        pad_x = pad + min(50, self.w * 0.08)              # seitlich Platz fuer Beschriftungen
        k = max(0.04, min((self.w - 2 * pad_x) / (x1 - x0 + 1), (self.h - 2 * pad) / (y1 - y0 + 1), 2.0))
        return k, self.w / 2 - (x0 + x1) / 2 * k, self.h / 2 - (y0 + y1) / 2 * k

    def cam_to(self, tgt, instant=False):
        if tgt is None:
            return
        if instant or self.reduced or not self.w:
            self.cam = None
            self.k, self.tx, self.ty = tgt
            return
        self.cam = {"f": (self.k, self.tx, self.ty), "t": tgt, "p": 0.0}

    def fit(self, instant=False):
        self.cam_to(self.fit_target(), instant)

    def center_on(self, n, zoom=None, instant=False):
        k = zoom or self.k
        self.cam_to((k, self.w / 2 - n.x * k, self.h / 2 - n.y * k), instant)

    def reveal(self, n, margin=60):
        sx, sy = self.to_screen(n.x, n.y)
        if sx < margin or sy < margin or sx > self.w - margin or sy > self.h - margin:
            self.center_on(n, max(self.k, 0.6))

    def cam_step(self, dt):
        """Kamerafahrt (260 ms, Zoom logarithmisch gemischt); True, solange sie laeuft"""
        c = self.cam
        if not c:
            return False
        c["p"] = min(1.0, c["p"] + dt / 0.26)
        a = ease(c["p"])
        (fk, ftx, fty), (tk, ttx, tty) = c["f"], c["t"]
        k = math.exp(math.log(fk) + (math.log(tk) - math.log(fk)) * a)
        wx, wy = self.w / 2, self.h / 2
        cx0, cy0, cx1, cy1 = (wx - ftx) / fk, (wy - fty) / fk, (wx - ttx) / tk, (wy - tty) / tk
        cx, cy = cx0 + (cx1 - cx0) * a, cy0 + (cy1 - cy0) * a
        self.k, self.tx, self.ty = k, wx - cx * k, wy - cy * k
        if c["p"] >= 1:
            self.cam = None
        return True

    def animate(self, dt):
        """Hervorhebung weich nachfuehren (DUR); True, solange sich noch etwas aendert"""
        s = 1.0 if self.reduced else min(1.0, dt / DUR)
        busy = [False]
        hov = self.hover or self.sel
        adj = self.adj.get(hov.id, ()) if hov else ()
        m = self.match

        def to(cur, tgt):
            if cur == tgt:
                return cur
            d = tgt - cur
            if -s <= d <= s:
                return tgt
            busy[0] = True
            return cur + (s if d > 0 else -s)
        self.dim = to(self.dim, 1.0 if (hov or m) else 0.0)
        for n in self.nodes:
            if hov:
                tg = 1.0 if (n is hov or n.id in adj) else 0.0
            else:
                tg = 1.0 if (m and n.id in m) else 0.0
            n.e = to(n.e, tg)
            n.g = to(n.g, 1.0 if n is hov else 0.0)
        for e in self.edges:
            e.e = to(e.e, 1.0 if hov and (e.s is hov or e.t is hov) else 0.0)
        return busy[0]

    def step(self, dx, dy):
        """Pfeiltasten: zum naechsten Knoten in dieser Richtung; ohne Auswahl zuerst offene Seite bzw. meist verbundener"""
        cur = self.sel or self.hover
        if cur is None:
            cur = (self.by_id(self.focus) if self.focus is not None else None) or \
                (max(self.nodes, key=lambda n: n.deg) if self.nodes else None)
            return cur
        best, bs = None, float("inf")
        for n in self.nodes:
            if n is cur:
                continue
            vx, vy = n.x - cur.x, n.y - cur.y
            d = math.sqrt(vx * vx + vy * vy) or 1.0
            cos = (vx * dx + vy * dy) / d
            if cos < 0.35:
                continue
            sc = d * (2.2 - cos * 1.2)
            if sc < bs:
                bs, best = sc, n
        return best or cur

    def zoom_alpha(self, n, thr=1.76, few=False):
        """Sichtbarkeit der Beschriftung nur nach Zoom (mehr Verbindungen = frueher); wenige Knoten: immer"""
        if few:
            return 1.0
        z = self.k * (1 + math.sqrt(n.deg) * 0.3)
        return max(0.0, min(1.0, (z - thr * 0.72) / (thr * 0.28)))

    def label_alpha(self, n, thr=1.76, few=False):
        """Beschriftung: weich je Zoom (mehr Verbindungen = frueher), hervorgehobene immer"""
        dim_a = 1 - 0.85 * ease(self.dim)
        za = self.zoom_alpha(n, thr, few)
        la = max(za * dim_a, ease(n.e))
        if self.match is not None and n.id not in self.match:
            la = min(la, za * dim_a)
        return la


def demo(n=2000, seed=7):
    """Neutrale Testdaten im Format von api/graph (Benchmark, Tests, Bildschirmfotos): Baum aus Unterseiten mit einigen
    Knotenpunkten, Verweise bevorzugt auf schon gut verbundene Seiten, ein paar Datenbanken mit Zeilen und Relationen"""
    import random
    rnd = random.Random(seed)
    words = ["Notes", "Plan", "Ideas", "Project", "Meeting", "Recipe", "Garden", "Travel", "Books", "Budget", "Review",
             "Draft", "Archive", "Research", "Tasks", "Journal", "Music", "Health", "Home", "Learning"]
    nodes, edges, pop = [], [], []
    for i in range(1, n + 1):
        kind = "database" if i % 97 == 0 else "page"
        parent = None
        if i > 8 and rnd.random() < 0.7:
            parent = rnd.choice(pop) if pop and rnd.random() < 0.6 else rnd.randint(1, i - 1)
        nodes.append([i, f"{words[i % len(words)]} {i}", "", kind, 0 if i % 13 == 0 else 1, parent])
        pop.append(i)
        if parent:
            pop.append(parent)
    by = {r[0]: r for r in nodes}
    for r in nodes:
        if r[5] is not None:
            if by[r[5]][3] == "database":
                r[3] = "row"
            edges.append([r[5], r[0], "c"])
            pop.append(r[5])
    for i in range(1, n + 1):
        for _ in range(rnd.choice((0, 0, 1, 1, 2))):
            j = rnd.choice(pop)
            if j != i:
                edges.append([i, j, "l"])
    rows_ = [r[0] for r in nodes if r[3] == "row"]
    for a in rows_[: len(rows_) // 2]:
        edges.append([a, rnd.choice(rows_), "r"])
    tags = [["todo", [i for i in range(1, n + 1, 37)]], ["work", [i for i in range(5, n + 1, 53)]]]
    return {"nodes": nodes, "edges": [e for e in edges if e[0] != e[1]], "tags": tags, "truncated": False}
