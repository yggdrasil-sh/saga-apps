package io.github.yggdrasil_sh.saga

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.graph.GraphDemo
import io.github.yggdrasil_sh.saga.graph.GraphLoad
import io.github.yggdrasil_sh.saga.graph.GraphRunner
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.graph.GraphDrawStats
import io.github.yggdrasil_sh.saga.ui.graph.GraphScreenContent
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 2.5 Graph (Android): Bildschirmfotos mit neutralen Beispieldaten (hell, dunkel, Karte nach Antippen) und Messung
 * mit 2000 Knoten (Simulation, Zeichnen, Bilddauer ueber FrameMetrics). Ohne Konto/Server - GraphScreenContent mit
 * GraphDemo. Ablage: getExternalFilesDir()/shots/graph-*.png und graph-perf.txt.
 */
@RunWith(AndroidJUnit4::class)
class GraphShotsTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun launch(dark: Boolean, n: Int, select: Long? = null, focus: Long? = null, onRunner: (GraphRunner) -> Unit = {}): ActivityScenario<ComponentActivity> {
        val data = GraphDemo.generate(n)
        val sc = ActivityScenario.launch(ComponentActivity::class.java)
        sc.onActivity { a ->
            a.enableEdgeToEdge()
            val cfg = Configuration(a.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("de"))
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val lc = a.createConfigurationContext(cfg)
            a.setContent {
                CompositionLocalProvider(LocalContext provides lc, LocalConfiguration provides cfg) {
                    SagaTheme(dark = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            GraphScreenContent(
                                AppNavigator(), Chrome(showMenu = true, onMenu = {}), focus,
                                load = { GraphLoad.Ok(data) }, onOpenPage = {}, initialSelect = select, onRunner = onRunner,
                            )
                        }
                    }
                }
            }
        }
        return sc
    }

    private fun shot(name: String) {
        ins.waitForIdleSync()
        val b: Bitmap = ins.uiAutomation.takeScreenshot()
        val f = File(dir, "graph-$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 1000)
    }

    @Test
    fun shotsLightDarkCard() {
        launch(dark = false, n = 140).use { Thread.sleep(6000); shot("hell") }
        launch(dark = true, n = 140).use { Thread.sleep(6000); shot("dunkel") }
        launch(dark = false, n = 140, select = 1).use { Thread.sleep(6000); shot("karte") }
        launch(dark = true, n = 140, focus = 2, select = 2).use { Thread.sleep(6000); shot("lokal") }
    }

    @Test
    fun perf2000() = perf(2000)

    /** Vergleich: gleiche Messung mit 60 Knoten (Grundlast von Emulator und Bildschirm) */
    @Test
    fun perf60() = perf(60)

    private fun perf(nodes: Int, tag: String = "") {
        var runner: GraphRunner? = null
        val frames = CopyOnWriteArrayList<Long>()
        val phases = CopyOnWriteArrayList<LongArray>()
        val ht = HandlerThread("fm").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ -> frames.add(m.getMetric(FrameMetrics.TOTAL_DURATION)); phases.add(PH.map { m.getMetric(it) }.toLongArray()) }
        val out = StringBuilder()
        launch(dark = true, n = nodes, onRunner = { runner = it }).use { sc ->
            sc.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(ht.looper)) }
            GraphDrawStats.reset()
            val t0 = SystemClock.uptimeMillis()
            // 1) Einpendeln: Simulation (Hintergrund) + Zeichnen je Bild
            while (SystemClock.uptimeMillis() - t0 < 30_000) {
                Thread.sleep(200)
                val f = runner?.frames?.value
                if (f != null && !f.moving && f.x.size == nodes) break
            }
            val st = runner!!.stats.value
            out.append(report("einpendeln", frames)).append('\n').append(phase(phases)).append('\n')
            out.append("sim n=${st.n} tick_avg=${"%.2f".format(st.tickMsAvg)}ms ticks=${st.ticks} bis_stillstand=${st.settleMs}ms\n")
            out.append("zeichnen n=${GraphDrawStats.count} avg=${"%.2f".format(GraphDrawStats.sumMs / maxOf(1, GraphDrawStats.count))}ms max=${"%.2f".format(GraphDrawStats.maxMs)}ms\n")
            // 2) Verschieben (Ziehen ueber den Hintergrund/Knoten, 2 s) bei stehender Simulation
            frames.clear(); phases.clear(); GraphDrawStats.reset()
            Thread.sleep(500)
            val dm = ins.targetContext.resources.displayMetrics
            val x0 = dm.widthPixels * 0.06f; val y0 = dm.heightPixels * 0.93f   // unten links: Hintergrund (verschieben)
            val down = SystemClock.uptimeMillis()
            ins.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x0, y0, 0))
            for (i in 1..120) {
                val t = SystemClock.uptimeMillis()
                ins.sendPointerSync(MotionEvent.obtain(down, t, MotionEvent.ACTION_MOVE, x0 + i * 4f, y0 - i * 2f, 0))
                Thread.sleep(16)
            }
            ins.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x0 + 480f, y0 - 240f, 0))
            Thread.sleep(3000)                                          // ggf. gezogener Knoten pendelt nach
            out.append(report("ziehen", frames)).append('\n').append(phase(phases)).append('\n')
            out.append("zeichnen n=${GraphDrawStats.count} avg=${"%.2f".format(GraphDrawStats.sumMs / maxOf(1, GraphDrawStats.count))}ms max=${"%.2f".format(GraphDrawStats.maxMs)}ms\n")
            if (nodes == 2000 && tag.isEmpty()) shot("2000")
            sc.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        }
        ht.quitSafely()
        File(dir, "graph-perf-$nodes$tag.txt").writeText(out.toString())
        println("GRAPH-PERF\n$out")
        assertTrue(out.isNotEmpty())
    }

    private val PH = intArrayOf(FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION,
        FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION, FrameMetrics.GPU_DURATION)
    private val PHN = listOf("input", "anim", "layout", "draw", "sync", "issue", "swap", "gpu")

    /** Median je Phase (ms): wo die Zeit eines Bildes bleibt */
    private fun phase(ps: List<LongArray>): String {
        if (ps.isEmpty()) return "  phasen: -"
        return "  phasen p50: " + PHN.indices.joinToString(" ") { k -> val v = ps.map { it[k] / 1e6 }.sorted(); "${PHN[k]}=${"%.1f".format(v[v.size / 2])}" }
    }

    private fun report(label: String, ns: List<Long>): String {
        if (ns.isEmpty()) return "$label: keine Bilder"
        val ms = ns.map { it / 1e6 }.sorted()
        val p50 = ms[ms.size / 2]; val p95 = ms[(ms.size * 95 / 100).coerceAtMost(ms.size - 1)]
        val jank = ms.count { it > 16.7 } * 100.0 / ms.size
        return "$label: bilder=${ms.size} p50=${"%.1f".format(p50)}ms p95=${"%.1f".format(p95)}ms ueber16ms=${"%.1f".format(jank)}%"
    }
}
