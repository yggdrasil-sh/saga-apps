package io.github.yggdrasil_sh.saga.graph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Positionen fuer ein Bild (Kopie - die Simulation rechnet derweil weiter). moving = Simulation laeuft noch. */
class GraphFrame(val x: FloatArray, val y: FloatArray, val version: Int, val moving: Boolean)

/** Messwerte (Leistung): mittlere Dauer eines Schritts, Schritte und Zeit bis zum Stillstand */
data class SimStats(val n: Int = 0, val tickMsAvg: Double = 0.0, val ticks: Int = 0, val settleMs: Long = 0, val prerunTicks: Int = 0)

/**
 * Fuehrt die ForceSim ausserhalb des Main-Threads aus (Dispatchers.Default): bis zu 2 Schritte je ~16 ms, dann eine
 * Kopie der Positionen nach [frames]. Steht die Simulation (alpha < ALPHA_MIN), wartet die Coroutine ohne CPU-Last,
 * bis heat() oder pin() sie wieder weckt - kein Dauerlauf. reduced = reduzierte Bewegung: sofort fertig rechnen,
 * nur ein Bild.
 */
class GraphRunner(private val scope: CoroutineScope, private val dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val _frames = MutableStateFlow<GraphFrame?>(null)
    val frames: StateFlow<GraphFrame?> = _frames
    private val _stats = MutableStateFlow(SimStats())
    val stats: StateFlow<SimStats> = _stats

    private var job: Job? = null
    private var wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var sim: ForceSim? = null
    @Volatile private var heatReq = 0f
    @Volatile var reduced = false

    /** Neue Daten: Simulation neu aufsetzen; prev = Lage der bisherigen Knoten (Filter umschalten ohne Sprung) */
    fun start(g: GraphModel, prev: Map<Long, FloatArray>? = null, reducedMotion: Boolean = reduced) {
        reduced = reducedMotion
        job?.cancel()
        wake = Channel(Channel.CONFLATED)
        val ch = wake
        job = scope.launch(dispatcher) {
            val s = ForceSim(g.n, g.es, g.et, g.deg)
            s.seed(g.ids, prev, g.focus)
            if (prev != null && prev.isNotEmpty()) s.cool(0.5f)          // bekannte Lage: nur nachjustieren
            sim = s
            var version = 0
            val t0 = System.nanoTime()
            var tickNs = 0L
            var tickCount = 0
            fun publish(moving: Boolean) { _frames.value = GraphFrame(s.x.copyOf(), s.y.copyOf(), ++version, moving) }
            fun stats(settled: Boolean) {
                _stats.value = SimStats(g.n, if (tickCount > 0) tickNs / 1e6 / tickCount else 0.0, s.ticks,
                    if (settled) (System.nanoTime() - t0) / 1_000_000 else 0, 0)
            }
            if (reduced) {
                val a = System.nanoTime(); val c = s.runToRest(); tickNs += System.nanoTime() - a; tickCount += c
            } else {
                // Vorlauf ohne Bild (max. ~60 ms), damit das erste Bild schon nach Graph aussieht
                val a = System.nanoTime()
                while (System.nanoTime() - a < 60_000_000 && s.alpha > 0.05f) { s.tick(); tickCount++ }
                tickNs += System.nanoTime() - a
            }
            publish(!s.settled)
            stats(s.settled)
            while (isActive) {
                val h = heatReq
                if (h > 0f) { heatReq = 0f; s.heat(h) }
                if (s.settled) {
                    publish(false)
                    stats(true)
                    ch.receive()                                       // schlafen bis heat()/pin()
                    continue
                }
                val f0 = System.nanoTime()
                if (reduced && s.pinned < 0) {
                    val c = s.runToRest(); tickCount += c
                } else {
                    s.tick(); s.tick(); tickCount += 2
                }
                val dt = System.nanoTime() - f0
                tickNs += dt
                publish(true)
                val wait = 16L - dt / 1_000_000
                if (wait > 0) delay(wait)
            }
        }
    }

    fun heat(a: Float) { heatReq = maxOf(heatReq, a); wake.trySend(Unit) }

    /** Knoten ziehen (Weltkoordinaten) */
    fun pin(i: Int, x: Float, y: Float) { sim?.pin(i, x, y); wake.trySend(Unit) }

    fun unpin() { sim?.unpin(); heat(0.1f) }

    fun stop() { job?.cancel(); job = null }

    /** aktuelle Lage je id (fuer start(prev=...)) */
    fun positions(g: GraphModel): Map<Long, FloatArray> {
        val f = _frames.value ?: return emptyMap()
        if (f.x.size != g.n) return emptyMap()
        val m = HashMap<Long, FloatArray>(g.n * 2)
        for (i in 0 until g.n) m[g.ids[i]] = floatArrayOf(f.x[i], f.y[i])
        return m
    }
}
