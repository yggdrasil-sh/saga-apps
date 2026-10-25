package io.github.yggdrasil_sh.saga.graph

import kotlin.random.Random

/**
 * Neutrale Beispieldaten (Tests, Bildschirmfotos, Leistungsmessung): Themen-Seiten mit Unterseiten, Verweisen
 * kreuz und quer, einer Datenbank mit Eintraegen und Relationen, einigen freigegebenen Seiten und Tags.
 * Deterministisch (seed).
 */
object GraphDemo {
    private val topics = listOf("Projekte", "Rezepte", "Reisen", "Bücher", "Garten", "Notizen", "Lernen", "Haushalt", "Ideen", "Musik", "Sport", "Arbeit")
    private val words = listOf("Plan", "Liste", "Entwurf", "Übersicht", "Sammlung", "Protokoll", "Skizze", "Fragen", "Ziele", "Rückblick", "Termine", "Vorlage")

    fun generate(n: Int, seed: Int = 7): GraphData {
        val rnd = Random(seed)
        val nodes = ArrayList<GNode>(n)
        val edges = ArrayList<GEdge>()
        val hubs = topics.take(minOf(topics.size, maxOf(3, n / 12)))
        var id = 1L
        val hubIds = hubs.map { t -> GNode(id++, t, "", NodeKind.PAGE, true, null).also { nodes.add(it) }.id }
        val db = if (n >= 30) GNode(id++, "Leseliste", "", NodeKind.DATABASE, true, null).also { nodes.add(it) }.id else null
        while (nodes.size < n) {
            val h = rnd.nextInt(hubIds.size)
            val r = rnd.nextFloat()
            val i = nodes.size
            when {
                db != null && r < 0.12f -> {
                    val nd = GNode(id++, "Eintrag ${i}", "", NodeKind.ROW, true, db); nodes.add(nd); edges.add(GEdge(db, nd.id, 'c'))
                }
                r < 0.55f -> {
                    val nd = GNode(id++, "${hubs[h]} – ${words[rnd.nextInt(words.size)]} $i", "", NodeKind.PAGE, rnd.nextFloat() > 0.08f, hubIds[h])
                    nodes.add(nd); edges.add(GEdge(hubIds[h], nd.id, 'c'))
                }
                r < 0.97f -> {
                    val nd = GNode(id++, "${words[rnd.nextInt(words.size)]} $i", "", NodeKind.PAGE, rnd.nextFloat() > 0.1f, null)
                    nodes.add(nd); edges.add(GEdge(nd.id, hubIds[h], 'l'))
                }
                else -> nodes.add(GNode(id++, "Lose Notiz $i", "", NodeKind.PAGE, true, null))   // Waise
            }
        }
        // Verweise kreuz und quer (lokal gehaeuft, damit Cluster entstehen)
        val extra = n / 2
        repeat(extra) {
            val a = nodes[rnd.nextInt(nodes.size)].id
            val b = (a + rnd.nextInt(-25, 26)).coerceIn(1L, id - 1)
            if (a != b) edges.add(GEdge(a, b, 'l'))
        }
        // Relationen zwischen Eintraegen der Datenbank
        val rows = nodes.filter { it.kind == NodeKind.ROW }
        for (k in 0 until rows.size / 3) {
            val a = rows[rnd.nextInt(rows.size)].id; val b = rows[rnd.nextInt(rows.size)].id
            if (a != b) edges.add(GEdge(minOf(a, b), maxOf(a, b), 'r'))
        }
        val tagNames = listOf("wichtig", "später", "entwurf")
        val tags = tagNames.mapIndexed { ti, t -> t to nodes.filterIndexed { i, _ -> i % (7 + ti * 4) == ti }.map { it.id } }
        return GraphData(nodes.take(n), edges, tags, truncated = false)
    }
}
