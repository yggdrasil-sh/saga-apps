"""Graph der Seiten (2.5): Logik ohne GTK - Aufbereitung, Simulation (Barnes-Hut), Kamera, Treffer, Hervorhebung"""
import math
import time

from hlidskjalf import graphsim as G

D = {"nodes": [[1, "Start", "📘", "page", 1, None], [2, "Kind", "", "page", 1, 1], [3, "Tabelle", "", "database", 1, None],
               [4, "Zeile", "", "row", 1, 3], [5, "Geteilt", "", "page", 0, None], [6, "Allein", "", "page", 1, None],
               [7, "", "", "page", 1, None]],
     "edges": [[1, 2, "c"], [3, 4, "c"], [1, 5, "l"], [4, 1, "r"], [2, 2, "l"], [1, 99, "l"], [1, 5, "l"]],
     "tags": [["todo", [1, 4, 42]]], "truncated": True}


def test_build_basics():
    g = G.build(D)
    assert [n.id for n in g.nodes] == [1, 2, 3, 4, 5, 6, 7]
    assert len(g.edges) == 4                               # Schleife, unbekanntes Ziel und doppelte Kante fallen weg
    by = {n.id: n for n in g.nodes}
    assert by[1].deg == 3 and by[6].deg == 0
    assert by[5].own is False and by[3].kind == "database" and by[4].kind == "row"
    assert by[7].title == "Untitled"
    assert by[1].tags == ["todo"] and by[4].tags == ["todo"]
    assert g.truncated is True
    assert g.kinds() == {"page", "database", "row", "shared"}
    assert by[1].label() == "📘 Start"


def test_filters_tags_orphans():
    g = G.build(D, {"child": False, "rel": False})
    assert {e.k for e in g.edges} == {"l"}
    g = G.build(D, {"orphans": False})
    assert 6 not in {n.id for n in g.nodes} and 7 not in {n.id for n in g.nodes}
    g = G.build(D, {"tags": True})
    tag = [n for n in g.nodes if n.tag]
    assert len(tag) == 1 and tag[0].title == "#todo" and tag[0].deg == 2
    assert sum(1 for e in g.edges if e.k == "t") == 2


def test_local_depth():
    g1 = G.build(D, focus=2, depth=1)
    assert {n.id for n in g1.nodes} == {1, 2}
    g2 = G.build(D, focus=2, depth=2)
    assert {n.id for n in g2.nodes} == {1, 2, 4, 5}
    assert G.build(D, focus=6).nodes[0].id == 6           # Waise: lokal trotzdem sichtbar
    assert G.build(D, focus=12345).missing


def test_invalid_input():
    assert not G.valid(None) and not G.valid({"nodes": []}) and G.valid({"nodes": [], "edges": []})
    g = G.build({"nodes": [None, [1], [1, "a", "", "weird", 1, None], [1, "dup", "", "page", 1, None]],
                 "edges": [None, [1], [1, 1, "l"]], "tags": [None, ["x", "nolist"]]})
    assert len(g.nodes) == 1 and g.nodes[0].kind == "page" and not g.edges


def test_rows_and_search():
    g = G.build(D)
    r = G.rows(g)
    assert r[0][0].id == 1 and r[0][1] == 3                 # meist verbundene zuerst
    assert [n.id for n in G.matches(g, "  ZEI ")] == [4]
    assert G.matches(g, "") == []


def test_barnes_hut_close_to_exact():
    """Barnes-Hut weicht nur wenig von der genauen Abstossung ab (Summe der Kraefte)"""
    g = G.build(G.demo(600))
    s = G.Sim()
    s.set(g)
    for _ in range(30):
        s.tick()
    xs, ys = [n.x for n in g.nodes], [n.y for n in g.nodes]
    n = len(xs)
    fx, fy, ex, ey = [0.0] * n, [0.0] * n, [0.0] * n, [0.0] * n
    G.repel(xs, ys, 100.0, fx, fy, bh=True)
    G.repel(xs, ys, 100.0, ex, ey, bh=False)
    cos = sum(fx[i] * ex[i] + fy[i] * ey[i] for i in range(n)) / \
        (math.sqrt(sum(a * a + b * b for a, b in zip(fx, fy))) * math.sqrt(sum(a * a + b * b for a, b in zip(ex, ey))))
    assert cos > 0.95


def test_sim_cools_down_and_stops():
    g = G.build(D)
    s = G.Sim()
    s.set(g)
    k = s.settle()
    assert 0 < k <= 600 and not s.running and s.alpha == 0
    xs = [(n.x, n.y) for n in g.nodes]
    assert all(math.isfinite(x) and math.isfinite(y) for x, y in xs)
    # verbundene Knoten liegen naeher beieinander als der Rest im Mittel
    by = {n.id: n for n in g.nodes}
    d12 = math.hypot(by[1].x - by[2].x, by[1].y - by[2].y)
    d16 = math.hypot(by[1].x - by[6].x, by[1].y - by[6].y)
    assert d12 < d16
    s.tick()                                               # nach dem Stillstand: alpha 0 -> keine Bewegung
    assert [(n.x, n.y) for n in g.nodes] == xs


def test_sim_keep_positions_and_drag():
    g = G.build(D)
    s = G.Sim()
    s.set(g)
    s.settle()
    pos = {n.id: (n.x, n.y) for n in g.nodes}
    g2 = G.build(D, {"rel": False})
    s.set(g2, keep_from=g.nodes)
    assert {n.id: (n.x, n.y) for n in g2.nodes} == pos and s.alpha == 0.5
    d = g2.nodes[0]
    d.x, d.y = 500.0, 500.0
    s.drag = d
    for _ in range(5):
        s.tick()
    assert (d.x, d.y) == (500.0, 500.0)                     # gezogener Knoten bleibt unter dem Zeiger


def test_scene_hit_zoom_fit():
    g = G.build(D)
    s = G.Sim()
    s.set(g)
    s.settle()
    sc = G.Scene()
    sc.set(g)
    sc.resize(800, 600)
    sc.fit(instant=True)
    for n in g.nodes:                                      # alles im Bild
        x, y = sc.to_screen(n.x, n.y)
        assert 0 <= x <= 800 and 0 <= y <= 600
    n = g.nodes[0]
    x, y = sc.to_screen(n.x, n.y)
    assert sc.at(x, y) is n
    assert sc.at(x + 9, y) is n or sc.rad(n) * sc.k > 5       # Mindest-Trefferflaeche 10 px
    one = G.Scene()                                        # weit heraus: Treffer trotzdem mind. 10 px bzw. 22 px (Touch)
    g1 = G.build({"nodes": [[1, "A", "", "page", 1, None]], "edges": []})
    one.set(g1)
    one.resize(800, 600)
    one.k = 0.05
    x, y = one.to_screen(0, 0)
    assert one.at(x + 9.5, y) is g1.nodes[0] and one.at(x + 11, y) is None
    assert one.at(x + 21, y, G.HIT_TOUCH) is g1.nodes[0] and one.at(x + 23, y, G.HIT_TOUCH) is None
    k0 = sc.k
    wx, wy = sc.to_world(100, 100)
    sc.zoom_at(100, 100, 2.0)
    assert abs(sc.k - 2 * k0) < 1e-9
    wx2, wy2 = sc.to_world(100, 100)                       # Punkt unter dem Zeiger bleibt stehen
    assert abs(wx - wx2) < 1e-9 and abs(wy - wy2) < 1e-9
    sc.zoom_at(0, 0, 1e9)
    assert sc.k == 8.0


def test_camera_animation_and_reduced():
    sc = G.Scene()
    sc.resize(400, 300)
    sc.cam_to((2.0, 10.0, 20.0))
    t = 0
    while sc.cam_step(1 / 60):
        t += 1
    assert 10 <= t <= 20 and (sc.k, sc.tx, sc.ty) == (2.0, 10.0, 20.0)
    sc.reduced = True
    sc.cam_to((1.0, 0.0, 0.0))
    assert sc.cam is None and sc.k == 1.0


def test_highlight_transition_180ms():
    g = G.build(D)
    sc = G.Scene()
    sc.set(g)
    by = {n.id: n for n in g.nodes}
    sc.hover = by[1]
    steps = 0
    while sc.animate(1 / 60):
        steps += 1
    assert 9 <= steps <= 12                                # ~180 ms bei 60 Bildern/s
    assert by[1].e == 1 and by[2].e == 1 and by[5].e == 1 and by[6].e == 0 and by[1].g == 1 and sc.dim == 1
    assert sc.label_alpha(by[2]) == 1 and sc.label_alpha(by[6]) < 1
    sc.hover = None
    sc.reduced = True
    assert not sc.animate(1 / 60) and by[1].e == 0 and sc.dim == 0   # ohne Animationen sofort


def test_keyboard_step():
    g = G.build({"nodes": [[1, "M", "", "page", 1, None], [2, "R", "", "page", 1, None], [3, "L", "", "page", 1, None],
                           [4, "U", "", "page", 1, None]], "edges": [[1, 2, "l"], [1, 3, "l"], [1, 4, "l"]]})
    sc = G.Scene()
    sc.set(g)
    pos = {1: (0, 0), 2: (100, 0), 3: (-100, 0), 4: (0, -100)}
    for n in g.nodes:
        n.x, n.y = pos[n.id]
    first = sc.step(1, 0)
    assert first.id == 1                                   # ohne Auswahl: meist verbundener Knoten
    sc.sel = first
    assert sc.step(1, 0).id == 2 and sc.step(-1, 0).id == 3 and sc.step(0, -1).id == 4
    sc.sel = g.nodes[1]
    assert sc.step(1, 0) is g.nodes[1]                     # nichts weiter rechts: bleibt


def test_performance_2000():
    """2000 Knoten: ein Simulationsschritt bleibt unter 150 ms (reines Python, Messung im Bericht)"""
    g = G.build(G.demo(2000))
    s = G.Sim()
    s.set(g)
    s.tick()
    t0 = time.perf_counter()
    for _ in range(3):
        s.tick()
    per = (time.perf_counter() - t0) / 3
    assert per < 0.15, per
    sc = G.Scene()
    sc.set(g)
    sc.resize(1200, 800)
    sc.fit(instant=True)
    t0 = time.perf_counter()
    for i in range(50):
        sc.at(600 + i, 400)
    assert (time.perf_counter() - t0) / 50 < 0.01          # Hover-Treffer < 10 ms


def test_place_labels_no_overlap():
    # Rangfolge: erste Box gewinnt, ueberlappende entfallen, freie bleiben
    boxes = [(0, 0, 50, 12), (30, 5, 80, 17), (100, 0, 150, 12), (0, 40, 50, 52), (148, 2, 200, 14)]
    assert G.place_labels(boxes) == [True, False, True, True, False]
    # ueber Zellgrenzen hinweg (Raster 64 px)
    assert G.place_labels([(60, 60, 70, 70), (66, 66, 140, 80)]) == [True, False]
    assert G.place_labels([]) == []


def test_count_and_month_names_follow_ui_language():
    import datetime
    from hlidskjalf import util
    assert util.count_text(2000, "de") == "2.000" and util.count_text(2000, "en") == "2,000"
    assert util.count_text(2000, "es") == "2000" and util.count_text(20000, "es") == "20.000"
    assert util.count_text(2000, "ru") == "2 000" and util.count_text(7, "th") == "7"
    d = datetime.date(2026, 10, 25)
    assert util.fmt_date(d, "%-d. %b", lang="de") == "25. Okt"
    assert util.fmt_date(d, "%-d %b", lang="es") == "25 oct"
    assert util.fmt_date(d, "%a, %-d %b", lang="ru") == "Вс, 25 окт"
    assert util.fmt_date(d, "%OB %Y", lang="ru") == "Октябрь 2026"
    assert util.fmt_date(d, "%B de %Y", lang="es") == "octubre de 2026"
    assert util.fmt_date(d, "100%% %b", lang="en") == "100% Oct"
