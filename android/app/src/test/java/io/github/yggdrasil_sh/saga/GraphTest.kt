package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.graph.Camera
import io.github.yggdrasil_sh.saga.graph.ForceSim
import io.github.yggdrasil_sh.saga.graph.GEdge
import io.github.yggdrasil_sh.saga.graph.GNode
import io.github.yggdrasil_sh.saga.graph.GraphBuild
import io.github.yggdrasil_sh.saga.graph.GraphData
import io.github.yggdrasil_sh.saga.graph.GraphDemo
import io.github.yggdrasil_sh.saga.graph.GraphFilter
import io.github.yggdrasil_sh.saga.graph.GraphHit
import io.github.yggdrasil_sh.saga.graph.GraphJson
import io.github.yggdrasil_sh.saga.graph.GraphLoad
import io.github.yggdrasil_sh.saga.graph.GraphLoader
import io.github.yggdrasil_sh.saga.graph.GraphRunner
import io.github.yggdrasil_sh.saga.graph.NodeKind
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import kotlin.math.sqrt

/** 2.5 Graph der Seiten: Simulation, Aufbau (Filter, lokal), Trefferflaeche, JSON und Laden */
class GraphTest {
    private fun page(id: Long, t: String = "S$id", kind: NodeKind = NodeKind.PAGE, own: Boolean = true, parent: Long? = null) = GNode(id, t, "", kind, own, parent)

    /** Kette 1-2-3-4-5, 2 hat Unterseite 6, Relation 7-8, Waise 9, Tag "x" auf 1 und 9 */
    private val small = GraphData(
        nodes = (1L..5L).map { page(it) } + page(6, parent = 2) + page(7, kind = NodeKind.ROW) + page(8, kind = NodeKind.ROW) + page(9, "Waise"),
        edges = listOf(GEdge(1, 2, 'l'), GEdge(2, 3, 'l'), GEdge(3, 4, 'l'), GEdge(4, 5, 'l'), GEdge(2, 6, 'c'), GEdge(7, 8, 'r')),
        tags = listOf("x" to listOf(1L, 9L)),
        truncated = false,
    )

    // ------------------------------------------------------------------ Simulation

    private fun settle(d: GraphData): Pair<io.github.yggdrasil_sh.saga.graph.GraphModel, ForceSim> {
        val g = GraphBuild.build(d, GraphFilter())
        val s = ForceSim(g.n, g.es, g.et, g.deg)
        s.seed(g.ids)
        s.runToRest()
        return g to s
    }

    @Test
    fun simulationConvergesAndStops() {
        for (n in listOf(60, 600)) {                                    // paarweise und Barnes-Hut
            val (g, s) = settle(GraphDemo.generate(n))
            assertTrue("steht still (n=$n)", s.settled)
            assertTrue("kein Dauerlauf: ${s.ticks} Schritte", s.ticks in 300..1000)
            assertTrue("zur Ruhe gekommen: ${s.meanSpeed()}", s.meanSpeed() < 0.5f)
            for (i in 0 until g.n) assertTrue("endlich", s.x[i].isFinite() && s.y[i].isFinite())
            // verbundene Knoten liegen naeher beieinander als beliebige Paare
            var edge = 0.0
            for (e in 0 until g.m) edge += dist(s, g.es[e], g.et[e])
            edge /= g.m
            var any = 0.0; var c = 0
            for (i in 0 until g.n step 3) for (j in i + 1 until g.n step 7) { any += dist(s, i, j); c++ }
            any /= c
            assertTrue("Kanten kuerzer ($edge) als Durchschnitt ($any), n=$n", edge < any * 0.6)
            // keine zwei Knoten exakt aufeinander (Abstossung wirkt)
            var close = 0
            for (i in 0 until minOf(g.n, 200)) for (j in i + 1 until minOf(g.n, 200)) if (dist(s, i, j) < 0.5) close++
            assertTrue("kaum Ueberlagerung: $close", close < 3)
        }
    }

    @Test
    fun barnesHutMatchesPairwise() {
        // gleiche Startlage, einmal knapp unter, einmal ueber der Schwelle: Layout-Groesse vergleichbar
        val d = GraphDemo.generate(ForceSim.BH_MIN)
        val g = GraphBuild.build(d, GraphFilter())
        val bh = ForceSim(g.n, g.es, g.et, g.deg).apply { seed(); runToRest() }
        val sub = GraphBuild.build(GraphDemo.generate(ForceSim.BH_MIN - 1), GraphFilter())
        val pw = ForceSim(sub.n, sub.es, sub.et, sub.deg).apply { seed(); runToRest() }
        val r1 = spread(bh); val r2 = spread(pw)
        assertTrue("Ausdehnung BH $r1 ~ paarweise $r2", r1 / r2 in 0.75..1.33)
    }

    @Test
    fun simulationIsDeterministicAndPinWorks() {
        val g = GraphBuild.build(small, GraphFilter())
        val a = ForceSim(g.n, g.es, g.et, g.deg).apply { seed(); runToRest() }
        val b = ForceSim(g.n, g.es, g.et, g.deg).apply { seed(); runToRest() }
        assertArrayEquals(a.x, b.x, 0f)
        // gezogener Knoten bleibt am Finger, Simulation laeuft dabei weiter (alpha >= 0.3)
        a.pin(0, 500f, 500f)
        repeat(30) { a.tick() }
        assertEquals(500f, a.x[0], 0f)
        assertFalse(a.settled)
        a.unpin()
        a.runToRest()
        assertTrue(a.settled)
    }

    @Test
    fun runnerSleepsWhenSettled() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val r = GraphRunner(scope)
        val g = GraphBuild.build(GraphDemo.generate(200), GraphFilter())
        r.start(g, reducedMotion = false)
        val last = withTimeout(20_000) { r.frames.first { it != null && !it.moving } }!!
        val v = last.version
        Thread.sleep(300)
        assertEquals("keine weiteren Bilder im Stillstand", v, r.frames.value!!.version)
        r.heat(0.3f)
        withTimeout(10_000) { r.frames.first { it!!.version > v } }
        // reduzierte Bewegung: gleich fertig
        r.start(g, reducedMotion = true)
        val f = withTimeout(10_000) { r.frames.first { it != null && it.x.size == g.n && !it.moving } }!!
        assertTrue(f.version <= 2)
        r.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }

    /** Leistung (JVM, nur grob; der Wert steht in der Ausgabe): 2000 Knoten. Schranke grosszuegig, weil CI/Build-Rechner
     *  stark ausgelastet sein koennen - auf einem freien Rechner liegt ein Schritt bei ~5 ms. */
    @Test
    fun perf2000() {
        val g = GraphBuild.build(GraphDemo.generate(2000), GraphFilter())
        val s = ForceSim(g.n, g.es, g.et, g.deg).apply { seed() }
        repeat(20) { s.tick() }                                          // Aufwaermen (JIT)
        val t0 = System.nanoTime()
        repeat(100) { s.tick() }
        val ms = (System.nanoTime() - t0) / 1e6 / 100
        println("GRAPH-PERF JVM n=${g.n} m=${g.m} tick=${"%.2f".format(ms)} ms")
        assertTrue("Schritt $ms ms", ms < 100)
    }

    private fun dist(s: ForceSim, i: Int, j: Int): Double { val dx = s.x[i] - s.x[j]; val dy = s.y[i] - s.y[j]; return sqrt((dx * dx + dy * dy).toDouble()) }
    private fun spread(s: ForceSim): Double { var r = 0.0; for (i in 0 until s.n) r += sqrt((s.x[i] * s.x[i] + s.y[i] * s.y[i]).toDouble()); return r / s.n }

    // ------------------------------------------------------------------ Aufbau: Nachbarschaft und Filter

    @Test
    fun localNeighbourhoodDepth() {
        val d1 = GraphBuild.build(small, GraphFilter(), focus = 3, depth = 1)
        assertEquals(setOf(2L, 3L, 4L), d1.ids.toSet())
        assertEquals(3L, d1.ids[d1.focus])
        val d2 = GraphBuild.build(small, GraphFilter(), focus = 3, depth = 2)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), d2.ids.toSet())
        // Unterseiten aus -> 6 faellt aus der Nachbarschaft
        val noChild = GraphBuild.build(small, GraphFilter(children = false), focus = 3, depth = 2)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), noChild.ids.toSet())
        // lokal einer Waise: nur sie selbst; unbekannte Seite: missing
        val alone = GraphBuild.build(small, GraphFilter(), focus = 9, depth = 2)
        assertEquals(1, alone.n); assertEquals(0, alone.focus)
        val miss = GraphBuild.build(small, GraphFilter(), focus = 999)
        assertTrue(miss.missing); assertEquals(0, miss.n)
    }

    @Test
    fun filters() {
        val all = GraphBuild.build(small, GraphFilter())
        assertEquals(9, all.n); assertEquals(6, all.m)
        assertEquals(5, GraphBuild.build(small, GraphFilter(children = false)).m)
        assertEquals(5, GraphBuild.build(small, GraphFilter(relations = false)).m)
        assertEquals(2, GraphBuild.build(small, GraphFilter(links = false)).m)
        // Waisen aus: 9 faellt weg; ohne Relationen auch 7 und 8
        val noOrph = GraphBuild.build(small, GraphFilter(orphans = false))
        assertEquals(-1, noOrph.indexOf(9)); assertEquals(8, noOrph.n)
        assertEquals(6, GraphBuild.build(small, GraphFilter(orphans = false, relations = false)).n)
        // Tags als Knoten: ein Tag-Knoten "#x" mit 2 Kanten; 9 ist dann keine Waise mehr
        val tags = GraphBuild.build(small, GraphFilter(tags = true, orphans = false))
        val t = (0 until tags.n).first { tags.isTag(it) }
        assertEquals("#x", tags.titles[t]); assertEquals(2, tags.deg[t])
        assertTrue(tags.indexOf(9) >= 0)
        // Grad und Nachbarn
        assertEquals(3, all.deg[all.indexOf(2)])
        assertEquals(setOf(1L, 3L, 6L), all.adj[all.indexOf(2)].map { all.ids[it] }.toSet())
        // doppelte Kante (Verweis + Unterseite zwischen denselben Seiten) = eine Linie
        val dup = GraphData(listOf(page(1), page(2, parent = 1)), listOf(GEdge(1, 2, 'c'), GEdge(1, 2, 'l'), GEdge(2, 1, 'l')), emptyList(), false)
        assertEquals(1, GraphBuild.build(dup, GraphFilter()).m)
    }

    @Test
    fun searchAndListOrder() {
        val g = GraphBuild.build(small, GraphFilter())
        assertArrayEquals(intArrayOf(g.indexOf(9)), GraphBuild.search(g, "  waI "))
        assertEquals(0, GraphBuild.search(g, "").size)
        assertEquals(0, GraphBuild.search(g, "nichts").size)
        assertEquals(2L, g.ids[GraphBuild.listOrder(g)[0]])               // meiste Verbindungen zuerst
        // leerer Titel -> Ersatz
        val u = GraphBuild.build(GraphData(listOf(page(1, "")), emptyList(), emptyList(), false), GraphFilter(), untitled = "Unbenannt")
        assertEquals("Unbenannt", u.titles[0])
    }

    // ------------------------------------------------------------------ Trefferflaeche

    @Test
    fun hitArea() {
        val x = floatArrayOf(0f, 100f); val y = floatArrayOf(0f, 0f)
        val r = floatArrayOf(GraphHit.radius(0), GraphHit.radius(9))
        val cam = Camera(1f, 0f, 0f)
        // Mitte trifft; 20 px daneben trifft nur mit Touch-Flaeche (22), nicht mit Maus (10)
        assertEquals(0, GraphHit.at(x, y, r, 2, cam, 0f, 0f, 22f))
        assertEquals(0, GraphHit.at(x, y, r, 2, cam, 20f, 0f, 22f))
        assertEquals(-1, GraphHit.at(x, y, r, 2, cam, 20f, 0f, 10f))
        assertEquals(-1, GraphHit.at(x, y, r, 2, cam, 50f, 0f, 22f))
        // weit herausgezoomt (Punkt winzig): Trefferflaeche bleibt in Bildschirm-Pixeln gleich gross
        val far = Camera(0.05f, 300f, 300f)
        val xf = floatArrayOf(0f, 2000f)                                 // zweiter Knoten 100 px weiter rechts
        assertEquals(0, GraphHit.at(xf, y, r, 2, far, 300f + 21f, 300f, 22f))
        assertEquals(-1, GraphHit.at(xf, y, r, 2, far, 300f + 40f, 300f, 22f))
        // dicht beieinander: der naechste Rand gewinnt
        val x2 = floatArrayOf(0f, 12f); val y2 = floatArrayOf(0f, 0f)
        assertEquals(1, GraphHit.at(x2, y2, r, 2, Camera(), 9f, 0f, 22f))
        // groesser bei mehr Verbindungen
        assertTrue(GraphHit.radius(16) > GraphHit.radius(1))
    }

    @Test
    fun cameraZoomKeepsPointAndFits() {
        val c = Camera(2f, 10f, 20f)
        val wx = c.toWorldX(100f); val wy = c.toWorldY(50f)
        val z = c.zoomAt(100f, 50f, 1.7f, 0.1f, 10f)
        assertEquals(wx, z.toWorldX(100f), 1e-3f); assertEquals(wy, z.toWorldY(50f), 1e-3f)
        assertEquals(10f, c.zoomAt(0f, 0f, 100f, 0.1f, 10f).k, 0f)
        val f = Camera.fit(floatArrayOf(-100f, 100f), floatArrayOf(-50f, 50f), 2, 1000f, 500f, 50f, 10f)
        assertEquals(500f, f.tx + 0f * f.k, 1e-3f)
        assertTrue(f.k * 201f <= 900f + 1e-3f)
    }

    // ------------------------------------------------------------------ JSON und Laden

    @Test
    fun jsonParsing() {
        val txt = """{"nodes":[[1,"Start","🌱","page",1,null],[2,"Leseliste","","database",1,null],[3,"Buch","","row",0,2],
            [4,"",null,"page",true,1],["kaputt"],[1,"doppelt","","page",1,null]],
            "edges":[[1,2,"l"],[2,3,"c"],[3,3,"l"],[1,99,"l"],[1,4,"x"],[3,4,"r"]],
            "tags":[["wichtig",[1,3,77]],["leer",[]]],"truncated":true,"neu":1}"""
        val d = GraphJson.parse(txt)
        assertEquals(listOf(1L, 2L, 3L, 4L), d.nodes.map { it.id })
        assertEquals("🌱", d.nodes[0].icon)
        assertEquals(NodeKind.DATABASE, d.nodes[1].kind)
        assertEquals(NodeKind.ROW, d.nodes[2].kind)
        assertFalse(d.nodes[2].own); assertEquals(2L, d.nodes[2].parent)
        assertTrue(d.nodes[3].own); assertEquals("", d.nodes[3].icon)
        assertEquals(listOf(GEdge(1, 2, 'l'), GEdge(2, 3, 'c'), GEdge(3, 4, 'r')), d.edges)   // Schleife, Unbekannte, falsche Art raus
        assertEquals(listOf("wichtig" to listOf(1L, 3L)), d.tags)
        assertTrue(d.truncated)
        val empty = GraphJson.parse("""{"nodes":[],"edges":[],"tags":[],"truncated":false}""")
        assertTrue(empty.nodes.isEmpty())
        assertTrue(runCatching { GraphJson.parse("[1,2]") }.isFailure)
    }

    @Test
    fun loadStates() = runBlocking {
        assertEquals(GraphLoad.Unsupported, GraphLoader.load { throw ApiException(404, UiText.Raw("not found")) })
        assertEquals(GraphLoad.Offline, GraphLoader.load { throw ConnectException("x") })
        assertEquals(GraphLoad.Offline, GraphLoader.load { throw IOException("x") })
        val f = GraphLoader.load { throw ApiException(500, UiText.Raw("kaputt")) }
        assertTrue(f is GraphLoad.Failed && (f.text as UiText.Raw).text == "kaputt")
        assertTrue(GraphLoader.load { "kein json" } is GraphLoad.Failed)
        val ok = GraphLoader.load { """{"nodes":[[5,"A","","page",1,null]],"edges":[],"tags":[],"truncated":false}""" }
        assertEquals(5L, (ok as GraphLoad.Ok).data.nodes.single().id)
    }
}
