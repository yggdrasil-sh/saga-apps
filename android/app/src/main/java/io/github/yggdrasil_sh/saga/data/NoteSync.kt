package io.github.yggdrasil_sh.saga.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URLEncoder

/*
 * Seitenbaum per Abgleich (Leistung 2.2, Phase 2b): statt jedes Mal den ganzen Baum (GET api/notes) nur das seit dem
 * letzten Stand Geaenderte holen (GET api/notes?since=<cursor>). Vertrag: docs/APP-API-LIVE.md "Seitenbaum abgleichen"
 * (Server-Repo); gleiche Logik wie iOS (NoteSyncLogic.swift) und Linux (notesync.py).
 * - ?delta=1 -> {cursor, full: true, notes, removed: []}; ?since=c -> {cursor, full: false, notes (mit "after"), removed}
 * - full: true jederzeit moeglich -> Liste ersetzen; cursor: null -> beim naechsten Mal wieder delta=1
 * - Antwort ist ein Array -> aelterer Server ohne Abgleich: wie bisher die ganze Liste
 */

/** Stand des Abgleichs. Gilt je Server UND Konto (nicht je Geraet): nach Abmelden/Kontowechsel/Serverwechsel verwerfen. */
@Serializable
data class NoteCursor(
    /** undurchsichtiger Stand vom Server (nicht zerlegen) */
    val value: String,
    val server: String,
    val account: String,
    /** Zeitpunkt der letzten ganzen Liste (ms, Wanduhr) - Sicherheitsnetz: spaetestens nach einem Tag wieder ganz */
    @SerialName("full_at") val fullAt: Long,
) {
    /** Nur fuer genau diesen Server und dieses Konto benutzen (unbekanntes Konto: nie) */
    fun usable(server: String?, account: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (value.isEmpty() || server.isNullOrEmpty() || account.isNullOrEmpty()) return false
        if (server != this.server || account != this.account) return false
        val age = nowMs - fullAt
        return age >= 0 && age < NoteSync.FULL_EVERY_MS
    }
}

object NoteSync {
    /** Sicherheitsnetz: einmal am Tag die ganze Liste (der Server liefert full: true ohnehin, wenn der Stand nicht passt) */
    const val FULL_EVERY_MS = 24L * 3600 * 1000

    /** Anfrage: mit Stand nur Geaendertes, sonst den ganzen Baum mit Stand (aeltere Server ignorieren beides) */
    fun path(since: String?): String =
        if (since.isNullOrEmpty()) "api/notes?delta=1" else "api/notes?since=" + URLEncoder.encode(since, "UTF-8").replace("+", "%20")

    /** Wohin eine Seite einer Abgleich-Antwort gehoert */
    sealed interface After {
        /** "after" fehlt (Stand weicht ab) */
        data object Missing : After
        /** null = ganz vorn */
        data object First : After
        data class Id(val id: Long) : After
    }

    class DeltaNote(val page: PageMeta, val after: After)

    sealed interface Response {
        /** aelterer Server: ganze Liste (Array) */
        class Legacy(val pages: List<PageMeta>) : Response
        /** Abgleich: cursor null = beim naechsten Mal wieder delta=1 */
        class Delta(val cursor: String?, val full: Boolean, val notes: List<DeltaNote>, val removed: List<Long>) : Response
    }

    fun parse(json: Json, text: String): Response {
        val el = json.parseToJsonElement(text)
        if (el is JsonArray) return Response.Legacy(json.decodeFromJsonElement(ListSerializer(PageMeta.serializer()), el))
        val o = el as? JsonObject ?: throw IllegalArgumentException("unexpected answer from api/notes")
        val arr = o["notes"] as? JsonArray ?: throw IllegalArgumentException("unexpected answer from api/notes")
        val notes = arr.mapNotNull { n ->
            val no = n as? JsonObject ?: return@mapNotNull null
            if (no["id"] == null || no["id"] is JsonNull) return@mapNotNull null
            val a = no["after"]
            val after = when {
                !no.containsKey("after") -> After.Missing
                a is JsonNull -> After.First
                else -> (a as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }?.let { After.Id(it) } ?: After.Missing
            }
            // "after" gehoert nicht zur Seite (PageMeta kennt es nicht)
            DeltaNote(json.decodeFromJsonElement(PageMeta.serializer(), JsonObject(no - "after")), after)
        }
        val removed = (o["removed"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() }
        val c = o["cursor"] as? JsonPrimitive
        val cur = when {
            c == null || c is JsonNull -> null
            c.isString -> c.content
            else -> c.longOrNull?.toString() ?: c.content           // Zahl als Stand
        }?.takeIf { it.isNotEmpty() }
        return Response.Delta(cur, (o["full"] as? JsonPrimitive)?.booleanOrNull == true, notes, removed)
    }

    /**
     * Abgleich einarbeiten (full: false): removed- und notes-IDs entfernen, notes der Reihe nach hinter ihr "after"
     * (bzw. an den Anfang) einsetzen. consistent false: ein "after" war unbekannt (Stand weicht ab) - die Seite steht
     * dann am Ende, beim naechsten Mal die ganze Liste holen.
     */
    fun merge(base: List<PageMeta>, notes: List<DeltaNote>, removed: List<Long>): Pair<List<PageMeta>, Boolean> {
        if (notes.isEmpty() && removed.isEmpty()) return base to true
        val drop = HashSet<Long>(removed.size + notes.size * 2)
        drop.addAll(removed)
        for (n in notes) drop.add(n.page.id)
        val follow = HashMap<Long?, MutableList<PageMeta>>()        // after -> einzusetzende Seiten; null = ganz vorn
        var consistent = true
        val orphans = ArrayList<PageMeta>()
        for (n in notes) {
            when (val a = n.after) {
                After.First -> follow.getOrPut(null) { ArrayList() }.add(n.page)
                is After.Id -> follow.getOrPut(a.id) { ArrayList() }.add(n.page)
                After.Missing -> { consistent = false; orphans.add(n.page) }
            }
        }
        val out = ArrayList<PageMeta>(base.size + notes.size)
        // ohne Rekursion: lange Ketten neuer Seiten (jede hinter der vorigen) sprengen sonst den Stack
        fun emit(key: Long?) {
            val f = follow.remove(key) ?: return
            val stack = ArrayDeque<PageMeta>(f.reversed())
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                out.add(p)
                follow.remove(p.id)?.let { g -> for (i in g.indices.reversed()) stack.addLast(g[i]) }
            }
        }
        emit(null)
        for (p in base) {
            if (p.id in drop) continue
            out.add(p)
            emit(p.id)
        }
        if (follow.isNotEmpty()) {                                  // "after" unbekannt: ans Ende, Stand weicht ab
            consistent = false
            val rest = follow.values.flatten().map { it.id }.toSet()
            for (n in notes) if (n.page.id in rest) out.add(n.page)
        }
        out.addAll(orphans)
        return out to consistent
    }

    /** Ergebnis einer Antwort gegen die bisherige Liste */
    data class Outcome(
        /** neue ganze Liste (null: unveraendert) */
        val pages: List<PageMeta>?,
        /** neuer Stand (null: beim naechsten Mal ganz laden; auch bei alten Servern) */
        val cursor: String?,
        /** ganze Liste erhalten (Array oder full: true) */
        val full: Boolean,
        /** Abgleich passte nicht (unbekanntes "after"): gleich noch einmal ganz holen */
        val refetch: Boolean,
        /** aelterer Server (Antwort war ein Array) */
        val legacy: Boolean = false,
    )

    /** sentSince: Anfrage hatte einen Stand. Ohne Stand ist jede Antwort die ganze Liste (auch wenn full fehlt). */
    fun outcome(r: Response, base: List<PageMeta>, sentSince: Boolean): Outcome = when (r) {
        is Response.Legacy -> Outcome(r.pages, null, full = true, refetch = false, legacy = true)
        is Response.Delta -> when {
            r.full || !sentSince -> Outcome(r.notes.map { it.page }, r.cursor, full = true, refetch = false)
            r.notes.isEmpty() && r.removed.isEmpty() -> Outcome(null, r.cursor, full = false, refetch = false)
            else -> {
                val (pages, ok) = merge(base, r.notes, r.removed)
                Outcome(pages, if (ok) r.cursor else null, full = false, refetch = !ok)
            }
        }
    }

    /** Zwischenspeicher der Seitenliste: die eingearbeitete Liste im Format von GET api/notes (Array) */
    fun encodeList(json: Json, pages: List<PageMeta>): String = json.encodeToString(ListSerializer(PageMeta.serializer()), pages)
}

/**
 * Seitenbaum-Abgleich mit Zwischenspeicher (Liste + Stand-Datei in dir). base = Liste genau wie der Server sie zum
 * Stand cursor kennt (Grundlage fuers Einarbeiten, nie lokal veraendert); beide werden nur zusammen ersetzt. Netz
 * ueber fetch (GET path -> Text), Aufrufe nacheinander (SagaRepository: refreshLock).
 */
class TreeSync(private val json: Json, private val dir: File?, private val fetch: suspend (String) -> String) {
    @Volatile var base: List<PageMeta> = emptyList()
        private set
    @Volatile var cursor: NoteCursor? = null
        private set

    /** Zeichen der Antworten von api/notes (Messung/Tests) */
    @Volatile var bytes = 0L

    /** Erhoeht bei jedem Verwerfen: ein danach fertig werdender Abruf uebernimmt nichts mehr */
    @Volatile private var gen = 0L

    private val listFile get() = dir?.let { File(it, "notes.json") }
    private val cursorFile get() = dir?.let { File(it, "cursor.json") }

    /** Stand verwerfen (Live "reset", notes.tree full: true): naechster Abgleich holt den ganzen Baum */
    fun dropCursor() {
        gen++
        cursor = null
        try { cursorFile?.delete() } catch (_: Exception) {}
    }

    /** Alles verwerfen (Abmelden, Kontowechsel, Serverwechsel): Speicher und Dateien */
    fun clear() {
        dropCursor()
        base = emptyList()
        try { listFile?.delete() } catch (_: Exception) {}
    }

    /** Start: Zwischenspeicher nur, wenn er zu genau diesem Server und Konto gehoert -> Liste (sonst null) */
    fun restore(server: String, account: String): List<PageMeta>? {
        if (server.isEmpty() || account.isEmpty()) return null
        return try {
            val c = cursorFile?.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(NoteCursor.serializer(), it) } ?: return null
            if (c.server != server || c.account != account) return null
            val l = listFile?.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(ListSerializer(PageMeta.serializer()), it) } ?: return null
            base = l
            cursor = c
            l
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Seitenbaum holen -> ganze Liste. Mit gueltigem Stand nur Geaendertes (?since=), sonst ganz mit Stand (?delta=1);
     * aeltere Server liefern die Liste (kein Stand). Unbekanntes "after" oder abgelehnter Stand: gleich ganz holen.
     * current(): Server und Konto, die gerade gelten (geaendert -> Ergebnis nicht uebernehmen).
     */
    suspend fun load(server: String, account: String, current: () -> Pair<String, String> = { server to account }): List<PageMeta> {
        val g = gen
        val b = base
        val cur = cursor
        var use = cur?.takeIf { it.usable(server, account) }?.value
        var o: NoteSync.Outcome? = null
        for (attempt in 0 until 3) {
            val text = try {
                fetch(NoteSync.path(use))
            } catch (e: ApiException) {
                if (use != null && e.code in REJECTED) { use = null; continue }
                throw e
            }
            bytes += text.length
            val r = NoteSync.outcome(NoteSync.parse(json, text), b, use != null)
            if (r.refetch && use != null) { use = null; continue }
            o = r
            break
        }
        val out = o ?: throw IllegalStateException("api/notes")
        val nbase = out.pages ?: b
        val ncur = if (out.cursor != null && account.isNotEmpty() && server.isNotEmpty())
            NoteCursor(out.cursor, server, account, if (out.full) System.currentTimeMillis() else (cur?.fullAt ?: 0L))
        else null
        if (g == gen && current() == (server to account)) {
            base = nbase
            cursor = ncur
            if (out.pages != null || ncur != cur) write(if (out.pages != null) nbase else null, ncur)
        }
        return nbase
    }

    /** Erst den Stand loeschen, dann die Liste, dann den Stand schreiben - nie ein Stand zu einer fremden Liste */
    private fun write(list: List<PageMeta>?, c: NoteCursor?) {
        val d = dir ?: return
        try {
            cursorFile?.delete()
            if (c == null) { listFile?.delete(); return }           // ohne Stand (alter Server) kein Zwischenspeicher
            d.mkdirs()
            if (list != null || listFile?.isFile != true) atomic(listFile!!, NoteSync.encodeList(json, list ?: base))
            atomic(cursorFile!!, json.encodeToString(NoteCursor.serializer(), c))
        } catch (_: Exception) {
            try { cursorFile?.delete() } catch (_: Exception) {}
        }
    }

    private fun atomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("rename") }
    }

    companion object {
        /** Stand vom Server abgelehnt -> ganz holen */
        private val REJECTED = setOf(400, 410, 422)
    }
}
