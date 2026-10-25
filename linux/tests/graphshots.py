# Graph der Seiten (2.5): Bildschirmfotos mit neutralen Demo-Daten und Messung bei 2000 Knoten (HK_OUT, HK_SHOT_PREFIX)
#   im Container: sh tests/run-container.sh app:graphshots.py
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockserver  # noqa: E402

from gi.repository import Adw  # noqa: E402

from hlidskjalf import graphsim, graphview  # noqa: E402
from hlidskjalf.i18n import _  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
S = {}


def shoot(g, name):
    g["shot"](os.path.join(g["out"], f"{P}graph-linux-{name}.png"))


def check(cond, msg):
    print(("OK " if cond else "FEHLER ") + msg, flush=True)


def scr(g):
    return g["win"].current


def start(g):
    S["srv"] = mockserver.install()
    S["srv"].graph = graphsim.demo(140, seed=3)
    S["srv"].version = "2.5.0"
    g["win"].set_default_size(1200, 780)
    g["store"].logged_in()
    g["win"].show_root(("page", 1))


def open_graph(g):
    acts = [a[1] for a in g["win"].palette_actions()]
    check(_("Graph") in acts, "Befehlsliste hat den Graph")
    rows = g["win"].sidebar.rows
    check(("graph",) in rows, "Seitenleiste hat den Eintrag Graph")
    g["win"].show_root(("graph",))


def all_light(g):
    s = scr(g)
    check(isinstance(s, graphview.GraphScreen), "Graph-Ansicht offen")
    check(s.stack.get_visible_child_name() == "graph", "Zeichenflaeche sichtbar")
    check(s.canvas.scene.focus == 1, "zuletzt offene Seite markiert")
    s.canvas.settled_now()
    check(not s.canvas.sim.running, "Simulation steht still")


def shot_all(g):
    shoot(g, "01-alle-hell")
    s = scr(g)
    hub = max(s.g.nodes, key=lambda n: n.deg)
    S["hub"] = hub
    s.canvas.scene.hover = hub
    s.canvas.kick()


def shot_hover(g):
    s = scr(g)
    check(S["hub"].e == 1 and s.canvas.scene.dim == 1, "Hervorhebung nach ~180 ms fertig")
    shoot(g, "02-hover")
    s.canvas.scene.hover = None
    s.search.set_text("Garden")


def shot_search(g):
    s = scr(g)
    check(len(s.matches) > 0 and s.canvas.scene.match, "Suche findet Treffer")
    shoot(g, "03-suche")
    s.search.set_text("")
    g["win"].show_route(("graph", "local", S["hub"].id))   # wie "Lokaler Graph" im Seitenmenue


def local_depth(g):
    s = scr(g)
    check(s.mode == "local" and s.page_id == S["hub"].id, "lokaler Graph der Seite")
    s.depth.set_selected(1)


def shot_local(g):
    s = scr(g)
    check(s.mode == "local" and s.depth.get_visible(), "lokaler Graph mit Tiefe")
    s.canvas.settled_now()
    ids = {n.id for n in s.g.nodes}
    check(S["hub"].id in ids and len(ids) < 140, f"lokal: {len(ids)} Knoten um die Seite")


def shot_local2(g):
    s = scr(g)
    shoot(g, "04-lokal-tiefe2")
    n = [x for x in s.g.nodes if x.id != S["hub"].id][0]
    s.canvas.select(n, "touch")


def shot_card(g):
    s = scr(g)
    check(s.card.get_visible() and s.card_o.get_label() == _("Open"), "Karte mit Oeffnen")
    shoot(g, "05-karte")
    s.canvas.select(None)
    s.list_btn.set_active(True)


def shot_list(g):
    s = scr(g)
    check(s.stack.get_visible_child_name() == "list", "Listenansicht sichtbar")
    rows = 0
    r = s.listbox.get_first_child()
    while r is not None:
        rows += 1
        r = r.get_next_sibling()
    check(rows == len(s.g.nodes), f"Liste hat alle {rows} Knoten")
    shoot(g, "06-liste")
    s.list_btn.set_active(False)
    s.b_all.set_active(True)
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)


def shot_dark(g):
    scr(g).canvas.settled_now()


def shot_dark2(g):
    shoot(g, "07-alle-dunkel")
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.DEFAULT)
    graphview._cache["data"] = None
    S["srv"].graph = graphsim.demo(2000)
    S["t_load"] = time.perf_counter()
    scr(g).load(force=True)


def wait_big(g):
    s = scr(g)
    if "t_shown" not in S and s.stack.get_visible_child_name() == "graph":
        S["t_shown"] = time.perf_counter()


def _frames(g, n, fn, ms=16):
    """n Bedienschritte im Abstand von ms; Bildtakt mitschreiben"""
    s = scr(g)
    S["frames"] = []

    gen = S["gen"] = S.get("gen", 0) + 1
    fr = S["frames"]

    def tick(_w, clock):
        if S["gen"] != gen or not S["rec"]:
            return False
        fr.append(clock.get_frame_time() / 1e6)
        return True
    S["rec"] = True
    s.canvas.add_tick_callback(tick)
    s.canvas.frame_ms.clear()
    for i in range(n):
        g["GLib"].timeout_add(ms * i, lambda i=i: (fn(s, i), False)[-1])


def _report(what):
    S["rec"] = False
    fr = S["frames"]
    dts = sorted(b - a for a, b in zip(fr, fr[1:]))
    if len(dts) > 3:
        print("MESSUNG %s: %.1f Bilder/s, Bildabstand Median %.0f ms, 95%% %.0f ms" % (
            what, len(dts) / (fr[-1] - fr[0]), dts[len(dts) // 2] * 1000, dts[int(len(dts) * 0.95)] * 1000), flush=True)


def perf_zoom(g):
    s = scr(g)
    check(len(s.g.nodes) == 2000 and "t_shown" in S, "2000 Knoten geladen und gezeigt")
    if "t_shown" in S:
        print("MESSUNG 2000 Knoten, %d Kanten: Laden + Layout bis zum Bild %.1f s" % (len(s.g.edges), S["t_shown"] - S["t_load"]),
              flush=True)
    check(not s.canvas.sim.running and s.canvas._tick == 0, "nach dem Layout kein Dauerlauf, Bildtakt aus")
    _frames(g, 60, lambda s, i: (s.canvas.scene.zoom_at(600, 380, 1.02 if i < 30 else 0.98), s.canvas._after_zoom()))


def perf_hover(g):
    _report("Zoomen (Mausrad) bei 2000 Knoten")
    s = scr(g)
    ns = sorted(s.g.nodes, key=lambda n: -n.deg)[:60]

    def hv(s, i):
        s.canvas._set_hover(ns[i])
    _frames(g, 60, hv, 40)


def perf_pan(g):
    _report("Hover (Hervorheben wechselt alle 40 ms) bei 2000 Knoten")
    s = scr(g)
    s.canvas._set_hover(None)

    def pan(s, i):
        s.canvas.scene.pan(4, 2)
        s.canvas.touched()
        s.canvas.queue_draw()
    _frames(g, 60, pan)


def perf_end(g):
    _report("Verschieben bei 2000 Knoten")
    s = scr(g)
    c = s.canvas
    P = c._palette()
    t0 = time.perf_counter()
    for _i in range(3):
        c._render_base(P, c.scene.k, c.scene.tx, c.scene.ty, c.scene.w, c.scene.h)
    print("MESSUNG Grundebene neu (nach Ruhe/Layout) bei 2000 Knoten: %.0f ms" % ((time.perf_counter() - t0) / 3 * 1000), flush=True)
    t0 = time.perf_counter()
    for i in range(100):
        c.scene.at(300 + i, 300)
    print("MESSUNG Hover-Treffer bei 2000 Knoten: %.2f ms" % ((time.perf_counter() - t0) * 10), flush=True)
    c.fit()


def shot_big(g):
    shoot(g, "08-2000-knoten")
    graphview._cache["data"] = None
    S["srv"].graph = False
    scr(g).load(force=True)


def shot_old(g):
    s = scr(g)
    check(s.status.get_title() == _("Requires server 2.5"), "alter Server: Hinweis")
    check(("graph",) not in g["win"].sidebar.rows, "alter Server: Eintrag ausgeblendet")
    shoot(g, "09-alter-server")
    g["store"].graph_api = None
    S["srv"].graph = None
    S["srv"].fail["GET api/graph"] = 0
    s.load(force=True)


def shot_offline(g):
    s = scr(g)
    check(s.stack.get_visible_child_name() == "msg", "offline: Hinweis")
    shoot(g, "10-offline")
    S["srv"].fail.pop("GET api/graph", None)
    s.load(force=True)


def open_page(g):
    s = scr(g)
    n = [x for x in s.g.nodes if x.id == 2][0]
    s.open_node(n)


def opened(g):
    check(g["win"].route == ("page", 2), "Oeffnen zeigt die Seite")
    g["win"].go_back()


def back(g):
    check(isinstance(scr(g), graphview.GraphScreen), "Zurueck fuehrt zum Graph")
    g["win"].show_page(1)


def page_menu(g):
    import hlidskjalf.page as pg
    got = []
    orig = pg.menu_popover

    class _Pop:
        def popup(self):
            pass
    pg.menu_popover = lambda parent, ents: (got.extend(e[0] for e in ents if e), _Pop())[-1]
    try:
        g["win"].current.page_menu(None)
    finally:
        pg.menu_popover = orig
    check(_("Local graph") in got, "Seitenmenue hat Lokaler Graph")
    g["win"].show_route(("graph", "local", 1))
    g["win"].set_default_size(360, 700)


def shot_narrow(g):
    s = scr(g)
    check(s.narrow and s.bar2.get_visible(), "schmal: Kopf zweizeilig")
    s.canvas.settled_now()


def shot_narrow2(g):
    check(g["win"].get_width() <= 380, "Fenster schmal (%d px)" % g["win"].get_width())
    shoot(g, "11-schmal-360")


steps = [(1500, start), (1500, open_graph), (1500, all_light), (500, shot_all), (400, shot_hover), (900, shot_search),
         (900, local_depth), (900, shot_local), (500, shot_local2), (500, shot_card), (600, shot_list), (900, shot_dark),
         (500, shot_dark2)] + [(500, wait_big)] * 30 + [
         (500, perf_zoom), (2500, perf_hover), (3500, perf_pan), (2500, perf_end), (1500, shot_big), (1500, shot_old),
         (1200, shot_offline), (1500, open_page), (1200, opened), (1200, back), (1200, page_menu), (1500, shot_narrow),
         (500, shot_narrow2)]
