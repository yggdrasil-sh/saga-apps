package io.github.yggdrasil_sh.saga.graph

/** Filter wie im Web (Einstellungen der Graph-Ansicht) */
data class GraphFilter(
    val links: Boolean = true,
    val children: Boolean = true,
    val relations: Boolean = true,
    /** Tags als eigene Knoten */
    val tags: Boolean = false,
    /** Knoten ohne Kanten (Waisen) */
    val orphans: Boolean = true,
)

/**
 * Graph fuer die Anzeige in Feldern (schnell fuer 2000+ Knoten): Index 0..n-1 je Knoten, Kanten als Index-Paare.
 * Tag-Knoten haben kind = TAG und eine kuenstliche, negative id (TAG_ID_BASE - i).
 */
class GraphModel(
    val ids: LongArray,
    val titles: Array<String>,
    val icons: Array<String>,
    val kinds: Array<NodeKind>,
    val own: BooleanArray,
    val es: IntArray,
    val et: IntArray,
    val ek: CharArray,
    /** Index der offenen Seite (lokaler Graph bzw. Markierung), -1 = keine */
    val focus: Int,
    /** lokal: die Seite ist gar nicht im Graph (archiviert, Vorlage, fremder Server) */
    val missing: Boolean = false,
) {
    val n: Int get() = ids.size
    val m: Int get() = es.size
    val deg = IntArray(ids.size).also { d -> for (i in es.indices) { d[es[i]]++; d[et[i]]++ } }
    /** Nachbarn je Knoten (ohne Doppelte) */
    val adj: Array<IntArray> = run {
        val sets = Array(ids.size) { LinkedHashSet<Int>() }
        for (i in es.indices) { sets[es[i]].add(et[i]); sets[et[i]].add(es[i]) }
        Array(ids.size) { sets[it].toIntArray() }
    }
    private val index: Map<Long, Int> = HashMap<Long, Int>(ids.size * 2).also { m -> ids.forEachIndexed { i, id -> m[id] = i } }
    fun indexOf(id: Long): Int = index[id] ?: -1
    fun isTag(i: Int) = kinds[i] == NodeKind.TAG

    companion object {
        const val TAG_ID_BASE = -4_000_000_000_000L
        val EMPTY = GraphModel(LongArray(0), emptyArray(), emptyArray(), emptyArray(), BooleanArray(0), IntArray(0), IntArray(0), CharArray(0), -1)
    }
}

object GraphBuild {
    /**
     * Wie build() in static/graph.js: Kanten nach Filter, Tag-Knoten, lokal = Nachbarschaft (BFS) bis depth ab focus,
     * Waisen nur im ganzen Graph ausblendbar. untitled = Ersatz fuer leere Titel.
     */
    fun build(d: GraphData, f: GraphFilter, focus: Long? = null, depth: Int = 1, untitled: String = ""): GraphModel {
        val ids = ArrayList<Long>(d.nodes.size + d.tags.size)
        val titles = ArrayList<String>()
        val icons = ArrayList<String>()
        val kinds = ArrayList<NodeKind>()
        val own = ArrayList<Boolean>()
        val at = HashMap<Long, Int>(d.nodes.size * 2)
        for (nd in d.nodes) {
            at[nd.id] = ids.size
            ids.add(nd.id); titles.add(nd.title.ifBlank { untitled }); icons.add(nd.icon); kinds.add(nd.kind); own.add(nd.own)
        }
        val es = ArrayList<Int>(d.edges.size)
        val et = ArrayList<Int>(d.edges.size)
        val ek = ArrayList<Char>(d.edges.size)
        val seenE = HashSet<Long>(d.edges.size * 2)
        fun add(a: Int, b: Int, k: Char) {
            if (a == b) return
            val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
            if (!seenE.add((lo shl 32) or hi)) return                 // eine Linie je Paar (Verweis + Unterseite = eine)
            es.add(a); et.add(b); ek.add(k)
        }
        for (e in d.edges) {
            val on = when (e.kind) { 'l' -> f.links; 'c' -> f.children; 'r' -> f.relations; else -> false }
            if (!on) continue
            val a = at[e.a] ?: continue
            val b = at[e.b] ?: continue
            add(a, b, e.kind)
        }
        if (f.tags) {
            d.tags.forEachIndexed { ti, (name, members) ->
                val idx = members.mapNotNull { at[it] }
                if (idx.isEmpty()) return@forEachIndexed
                val t = ids.size
                ids.add(GraphModel.TAG_ID_BASE - ti); titles.add("#$name"); icons.add(""); kinds.add(NodeKind.TAG); own.add(true)
                idx.forEach { add(it, t, 't') }
            }
        }
        var keep: BooleanArray? = null
        var missing = false
        val fi = focus?.let { at[it] }
        if (focus != null) {
            if (fi == null) {
                missing = true
                keep = BooleanArray(ids.size)
            } else {
                val adj = Array(ids.size) { ArrayList<Int>() }
                for (i in es.indices) { adj[es[i]].add(et[i]); adj[et[i]].add(es[i]) }
                val dist = IntArray(ids.size) { -1 }
                dist[fi] = 0
                val q = ArrayDeque<Int>().apply { add(fi) }
                while (q.isNotEmpty()) {
                    val x = q.removeFirst()
                    if (dist[x] >= depth) continue
                    for (y in adj[x]) if (dist[y] < 0) { dist[y] = dist[x] + 1; q.add(y) }
                }
                keep = BooleanArray(ids.size) { dist[it] >= 0 }
            }
        } else if (!f.orphans) {
            val deg = IntArray(ids.size)
            for (i in es.indices) { deg[es[i]]++; deg[et[i]]++ }
            keep = BooleanArray(ids.size) { deg[it] > 0 }
        }
        if (keep == null) {
            return GraphModel(
                ids.toLongArray(), titles.toTypedArray(), icons.toTypedArray(), kinds.toTypedArray(), own.toBooleanArray(),
                es.toIntArray(), et.toIntArray(), ek.toCharArray(), fi ?: -1, missing,
            )
        }
        val map = IntArray(ids.size) { -1 }
        var c = 0
        for (i in ids.indices) if (keep[i]) map[i] = c++
        val pick = { i: Int -> keep[i] }
        val nIds = LongArray(c); val nT = arrayOfNulls<String>(c); val nI = arrayOfNulls<String>(c)
        val nK = arrayOfNulls<NodeKind>(c); val nO = BooleanArray(c)
        for (i in ids.indices) if (pick(i)) { val j = map[i]; nIds[j] = ids[i]; nT[j] = titles[i]; nI[j] = icons[i]; nK[j] = kinds[i]; nO[j] = own[i] }
        val ra = ArrayList<Int>(); val rb = ArrayList<Int>(); val rk = ArrayList<Char>()
        for (i in es.indices) {
            val a = map[es[i]]; val b = map[et[i]]
            if (a >= 0 && b >= 0) { ra.add(a); rb.add(b); rk.add(ek[i]) }
        }
        @Suppress("UNCHECKED_CAST")
        return GraphModel(
            nIds, nT as Array<String>, nI as Array<String>, nK as Array<NodeKind>, nO,
            ra.toIntArray(), rb.toIntArray(), rk.toCharArray(), fi?.let { map[it] } ?: -1, missing,
        )
    }

    /** Suche: Treffer im Titel (ohne Gross/klein), in Reihenfolge der Knoten */
    fun search(g: GraphModel, q: String): IntArray {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return IntArray(0)
        val out = ArrayList<Int>()
        for (i in 0 until g.n) if (g.titles[i].lowercase().contains(s)) out.add(i)
        return out.toIntArray()
    }

    /** Listenansicht (TalkBack): nach Anzahl der Verbindungen, dann Titel */
    fun listOrder(g: GraphModel): IntArray =
        (0 until g.n).sortedWith(compareByDescending<Int> { g.deg[it] }.thenBy { g.titles[it].lowercase() }).toIntArray()
}
