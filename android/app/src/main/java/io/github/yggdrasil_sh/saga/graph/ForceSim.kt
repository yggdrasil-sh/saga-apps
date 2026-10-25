package io.github.yggdrasil_sh.saga.graph

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Kraefte wie im Web (Standardwerte von static/graph.js: repel 0.6, spring 0.5, dist 0.5, center 0.5) */
data class ForceParams(val repel: Float = 0.6f, val spring: Float = 0.5f, val dist: Float = 0.5f, val center: Float = 0.5f)

/**
 * Kraefte-Simulation in Weltkoordinaten (1 Einheit ~ 1 dp bei Zoom 1): Abstossung (ab BH_MIN Knoten Barnes-Hut,
 * theta 0.9, sonst paarweise), Federn an Kanten, Zug zur Mitte. alpha kuehlt je Schritt ab (x 0.985); unter
 * ALPHA_MIN steht sie still - kein Dauerlauf. Nicht threadsicher: ein Thread rechnet (GraphRunner), die Anzeige
 * bekommt Kopien der Positionen. Gezogener Knoten: pin() von beliebigem Thread (wird beim naechsten Schritt uebernommen).
 */
class ForceSim(
    val n: Int,
    private val es: IntArray,
    private val et: IntArray,
    private val deg: IntArray,
    var params: ForceParams = ForceParams(),
) {
    val x = FloatArray(n)
    val y = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    var alpha = 1f
        private set
    var ticks = 0
        private set

    @Volatile private var pinIdx = -1
    @Volatile private var pinX = 0f
    @Volatile private var pinY = 0f

    val settled: Boolean get() = alpha <= ALPHA_MIN && pinIdx < 0

    /** Startlage: Sonnenblumen-Spirale (gleichmaessig, deterministisch); prev = Lage aus einer frueheren Ansicht */
    fun seed(ids: LongArray? = null, prev: Map<Long, FloatArray>? = null, focus: Int = -1) {
        val r = sqrt(n + 1f) * 18f
        for (i in 0 until n) {
            val p = if (ids != null && prev != null) prev[ids[i]] else null
            if (p != null) { x[i] = p[0]; y[i] = p[1] } else {
                val a = i * 2.399963f
                val rr = r * sqrt((i + 0.5f) / n)
                x[i] = cos(a) * rr; y[i] = sin(a) * rr
            }
            vx[i] = 0f; vy[i] = 0f
        }
        if (focus in 0 until n && (prev == null || ids == null || prev[ids[focus]] == null)) { x[focus] = 0f; y[focus] = 0f }
        alpha = 1f
    }

    fun heat(a: Float) { if (a > alpha) alpha = a }
    fun cool(a: Float) { alpha = a }

    fun pin(i: Int, px: Float, py: Float) { pinX = px; pinY = py; pinIdx = i }
    fun unpin() { pinIdx = -1 }
    val pinned: Int get() = pinIdx

    fun tick() {
        if (n == 0) return
        val p = params
        val pi = pinIdx
        if (pi in 0 until n) { x[pi] = pinX; y[pi] = pinY; if (alpha < 0.3f) alpha = 0.3f }   // Ziehen: Nachbarn folgen
        val a = alpha
        val f0 = (30f + p.repel * 600f) * a
        val spring = 0.02f + p.spring * 0.25f
        val len = 20f + p.dist * 120f
        val cen = 0.002f + p.center * 0.03f
        if (n >= BH_MIN) {
            tree.build(x, y, n)
            for (i in 0 until n) tree.repel(i, x[i], y[i], f0, vx, vy)
        } else {
            for (i in 0 until n) for (j in i + 1 until n) {
                val dx = x[j] - x[i]; val dy = y[j] - y[i]
                val d2 = dx * dx + dy * dy + 0.01f
                if (d2 > 1e6f) continue
                val f = f0 / d2
                vx[i] -= dx * f; vy[i] -= dy * f; vx[j] += dx * f; vy[j] += dy * f
            }
        }
        for (k in es.indices) {
            val s = es[k]; val t = et[k]
            val dx = x[t] - x[s]; val dy = y[t] - y[s]
            val d = sqrt(dx * dx + dy * dy).let { if (it == 0f) 1f else it }
            val f = (d - len) / d * spring * a
            val bs = deg[s].toFloat() / (deg[s] + deg[t])
            vx[t] -= dx * f * bs; vy[t] -= dy * f * bs
            vx[s] += dx * f * (1 - bs); vy[s] += dy * f * (1 - bs)
        }
        for (i in 0 until n) {
            vx[i] -= x[i] * cen * a; vy[i] -= y[i] * cen * a
            if (i == pi) { vx[i] = 0f; vy[i] = 0f; continue }
            vx[i] = (vx[i] * 0.6f).coerceIn(-50f, 50f)
            vy[i] = (vy[i] * 0.6f).coerceIn(-50f, 50f)
            x[i] += vx[i]; y[i] += vy[i]
        }
        alpha *= DECAY
        ticks++
    }

    /** ohne Bewegung (reduzierte Bewegung, Tests): fertig rechnen */
    fun runToRest(maxTicks: Int = 1000): Int {
        var c = 0
        while (alpha > ALPHA_MIN && c < maxTicks) { tick(); c++ }
        return c
    }

    /** mittlere Geschwindigkeit (Konvergenz-Pruefung) */
    fun meanSpeed(): Float {
        if (n == 0) return 0f
        var s = 0.0
        for (i in 0 until n) s += sqrt((vx[i] * vx[i] + vy[i] * vy[i]).toDouble())
        return (s / n).toFloat()
    }

    private val tree = QuadTree(n)

    companion object {
        const val ALPHA_MIN = 0.002f
        const val DECAY = 0.985f
        /** ab hier Barnes-Hut statt paarweise (O(n log n) statt O(n²)) */
        const val BH_MIN = 300
    }
}

/**
 * Barnes-Hut-Quadtree in Feldern (keine Objekte je Schritt - wichtig fuer 2000+ Knoten). Zelle: Ecke, Kantenlaenge,
 * Masse, Schwerpunkt, 4 Kinder; Blatt mit genau einem Koerper (body >= 0). Tiefe begrenzt (gleiche Lage).
 */
internal class QuadTree(cap: Int) {
    private var size = 0
    private var cx0 = FloatArray(0); private var cy0 = FloatArray(0); private var cs = FloatArray(0)
    private var mass = FloatArray(0); private var mx = FloatArray(0); private var my = FloatArray(0)
    private var kids = IntArray(0); private var body = IntArray(0)
    private val stack = IntArray(64 * 4 + 16)

    init { grow(maxOf(16, cap * 3)) }

    private fun grow(c: Int) {
        cx0 = cx0.copyOf(c); cy0 = cy0.copyOf(c); cs = cs.copyOf(c)
        mass = mass.copyOf(c); mx = mx.copyOf(c); my = my.copyOf(c)
        kids = kids.copyOf(c * 4); body = body.copyOf(c)
    }

    private fun cell(x: Float, y: Float, s: Float): Int {
        if (size == body.size) grow(body.size * 2)
        val c = size++
        cx0[c] = x; cy0[c] = y; cs[c] = s; mass[c] = 0f; mx[c] = 0f; my[c] = 0f; body[c] = -1
        kids[c * 4] = -1; kids[c * 4 + 1] = -1; kids[c * 4 + 2] = -1; kids[c * 4 + 3] = -1
        return c
    }

    private var px = FloatArray(0); private var py = FloatArray(0)

    fun build(x: FloatArray, y: FloatArray, n: Int) {
        px = x; py = y; size = 0
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until n) { if (x[i] < x0) x0 = x[i]; if (y[i] < y0) y0 = y[i]; if (x[i] > x1) x1 = x[i]; if (y[i] > y1) y1 = y[i] }
        val root = cell(x0, y0, maxOf(x1 - x0, y1 - y0) + 1f)
        for (i in 0 until n) insert(root, i)
    }

    private fun leaf(c: Int) = kids[c * 4] < 0 && kids[c * 4 + 1] < 0 && kids[c * 4 + 2] < 0 && kids[c * 4 + 3] < 0

    private fun insert(root: Int, i: Int) {
        var c = root
        var d = 0
        while (true) {
            if (leaf(c) && body[c] < 0 && mass[c] == 0f) {              // leer
                body[c] = i; mass[c] = 1f; mx[c] = px[i]; my[c] = py[i]; return
            }
            if (leaf(c)) {
                if (d > 40) { add(c, i); return }                       // gleiche Lage: nur Masse
                val o = body[c]
                if (o >= 0) {
                    body[c] = -1
                    val q = quadrant(c, px[o], py[o])
                    val k = child(c, q)
                    body[k] = o; mass[k] = 1f; mx[k] = px[o]; my[k] = py[o]
                }
            }
            add(c, i)
            c = child(c, quadrant(c, px[i], py[i]))
            d++
            if (leaf(c) && body[c] < 0 && mass[c] == 0f) { body[c] = i; mass[c] = 1f; mx[c] = px[i]; my[c] = py[i]; return }
        }
    }

    private fun add(c: Int, i: Int) {
        val m = mass[c]
        mx[c] = (mx[c] * m + px[i]) / (m + 1f); my[c] = (my[c] * m + py[i]) / (m + 1f); mass[c] = m + 1f
    }

    private fun quadrant(c: Int, x: Float, y: Float): Int {
        val h = cs[c] / 2f
        return (if (x >= cx0[c] + h) 1 else 0) + (if (y >= cy0[c] + h) 2 else 0)
    }

    private fun child(c: Int, q: Int): Int {
        val k = kids[c * 4 + q]
        if (k >= 0) return k
        val h = cs[c] / 2f
        val nc = cell(cx0[c] + (if (q and 1 != 0) h else 0f), cy0[c] + (if (q and 2 != 0) h else 0f), h)
        kids[c * 4 + q] = nc
        return nc
    }

    fun repel(i: Int, x: Float, y: Float, f0: Float, vx: FloatArray, vy: FloatArray) {
        if (size == 0) return
        var sp = 0
        stack[sp++] = 0
        var ax = 0f; var ay = 0f
        while (sp > 0) {
            val c = stack[--sp]
            val m = mass[c]
            if (m == 0f) continue
            val lf = leaf(c)
            if (lf && body[c] == i) continue
            val dx = mx[c] - x; val dy = my[c] - y
            val d2 = dx * dx + dy * dy + 0.01f
            if (lf || cs[c] * cs[c] / d2 < 0.81f) {
                if (d2 > 1e6f) continue
                val f = f0 * m / d2
                ax -= dx * f; ay -= dy * f
                continue
            }
            if (sp + 4 > stack.size) { ax -= dx * f0 * m / d2; ay -= dy * f0 * m / d2; continue }
            for (q in 0 until 4) { val k = kids[c * 4 + q]; if (k >= 0) stack[sp++] = k }
        }
        vx[i] += ax; vy[i] += ay
    }
}

/** Kamera: Bildschirm = Welt * k + t (Pixel). */
data class Camera(val k: Float = 1f, val tx: Float = 0f, val ty: Float = 0f) {
    fun toWorldX(sx: Float) = (sx - tx) / k
    fun toWorldY(sy: Float) = (sy - ty) / k

    /** Zoom um einen Bildschirmpunkt (Finger-Mitte), begrenzt */
    fun zoomAt(sx: Float, sy: Float, f: Float, minK: Float, maxK: Float): Camera {
        val nk = (k * f).coerceIn(minK, maxK)
        val r = nk / k
        return Camera(nk, sx - (sx - tx) * r, sy - (sy - ty) * r)
    }

    fun pan(dx: Float, dy: Float) = copy(tx = tx + dx, ty = ty + dy)

    companion object {
        /** Alles einpassen (w/h in Pixeln, pad in Pixeln, density fuer Grenzen in dp) */
        fun fit(x: FloatArray, y: FloatArray, n: Int, w: Float, h: Float, pad: Float, maxK: Float): Camera {
            if (n == 0 || w <= 0f || h <= 0f) return Camera(1f, w / 2, h / 2)
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (i in 0 until n) { if (x[i] < x0) x0 = x[i]; if (y[i] < y0) y0 = y[i]; if (x[i] > x1) x1 = x[i]; if (y[i] > y1) y1 = y[i] }
            val k = minOf((w - 2 * pad).coerceAtLeast(1f) / (x1 - x0 + 1f), (h - 2 * pad).coerceAtLeast(1f) / (y1 - y0 + 1f), maxK)
            return Camera(k, w / 2 - (x0 + x1) / 2 * k, h / 2 - (y0 + y1) / 2 * k)
        }
    }
}

object GraphHit {
    /** Radius in Weltkoordinaten nach Anzahl der Verbindungen (wie im Web) */
    fun radius(deg: Int, tag: Boolean = false): Float = (2.5f + sqrt(deg.toFloat()) * 1.6f) * (if (tag) 0.85f else 1f)

    /**
     * Knoten unter einem Bildschirmpunkt: grosszuegig - mindestens slopPx um die Mitte (Touch 22 dp, Maus 10 dp), auch
     * wenn der Punkt herausgezoomt winzig ist; bei Ueberlappung gewinnt der Knoten, dessen Rand am naechsten ist.
     * Gibt -1 zurueck, wenn nichts getroffen ist.
     */
    fun at(x: FloatArray, y: FloatArray, r: FloatArray, n: Int, cam: Camera, sx: Float, sy: Float, slopPx: Float): Int {
        val wx = cam.toWorldX(sx); val wy = cam.toWorldY(sy)
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in 0 until n) {
            val dx = x[i] - wx; val dy = y[i] - wy
            val d = sqrt(dx * dx + dy * dy) * cam.k
            val rs = r[i] * cam.k
            val lim = maxOf(rs + 4f, slopPx)
            if (d < lim && d - rs < bd) { bd = d - rs; best = i }
        }
        return best
    }
}
