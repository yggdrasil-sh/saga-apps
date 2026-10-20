package io.github.yggdrasil_sh.saga.db

import io.github.yggdrasil_sh.saga.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * Datenbanken am Server: Seiten von /rows laden (mit Zwischenspeicher fuer offline), Werte setzen, Schema speichern.
 *
 * Offline und Konflikte wie bei Seiten: Eine Wert-Aenderung gilt sofort in der App und liegt bis zur Bestaetigung des
 * Servers in einer Warteschlange auf dem Geraet (je Konto, uebersteht Neustarts). Sobald das Netz wieder da ist, wird
 * sie nachgeholt. Der Server fuehrt Werte je Eigenschaft zusammen (PATCH props) - wer zuletzt eine Eigenschaft setzt,
 * gewinnt, andere Eigenschaften derselben Zeile bleiben (wie im Web). Lehnt der Server einen Wert ab (Option geloescht,
 * kein Zugriff mehr), wird er verworfen und die Ansicht laedt den Stand des Servers. Ansichten/Spalten (Schema) tragen
 * "srev": der Server loescht so nichts, was inzwischen jemand anderes angelegt hat.
 */
class DbStore(
    private val dir: File?,
    /** Konto (Warteschlange und Zwischenspeicher gehoeren immer zu genau einem Konto) */
    private val account: () -> String,
    private val call: suspend (method: String, path: String, body: JsonElement?) -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Mutex()

    /** Noch nicht gesendete Werte: Zeile -> (Eigenschaft -> Wert; JsonNull = leeren) */
    private val _pending = MutableStateFlow<Map<Long, Map<String, JsonElement>>>(emptyMap())
    val pending: StateFlow<Map<Long, Map<String, JsonElement>>> = _pending

    /** Zaehlt Aenderungen (Werte, neue Eintraege, Schema) - offene Ansichten derselben Datenbank laden dann neu */
    val changes = MutableStateFlow(0)

    private var loadedFor: String? = null

    private fun hash(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private fun acctDir(): File? = dir?.let { File(it, hash(account())) }
    private fun pendingFile(): File? = acctDir()?.let { File(it, "pending.json") }
    private fun cacheFile(key: String): File? = acctDir()?.let { File(File(it, "rows"), hash(key) + ".json") }

    private fun ensureLoaded() {
        val acct = account()
        if (loadedFor == acct) return
        loadedFor = acct
        _pending.value = try {
            pendingFile()?.takeIf { it.exists() }?.readText()?.let { t ->
                (json.parseToJsonElement(t) as? JsonObject)?.mapNotNull { (k, v) ->
                    val id = k.toLongOrNull() ?: return@mapNotNull null
                    id to ((v as? JsonObject)?.toMap() ?: return@mapNotNull null)
                }?.toMap()
            } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persist() {
        val f = pendingFile() ?: return
        try {
            f.parentFile?.mkdirs()
            val o = JsonObject(_pending.value.mapKeys { it.key.toString() }.mapValues { JsonObject(it.value) })
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Exception) {
        }
    }

    /** Zeilen mit den noch nicht gesendeten Werten ueberlagern (offline geaendert) */
    fun overlay(rows: List<DbRow>): List<DbRow> {
        val p = _pending.value
        if (p.isEmpty()) return rows
        return rows.map { r -> p[r.id]?.entries?.fold(r) { acc, (k, v) -> DbValues.apply(acc, k, v) } ?: r }
    }

    sealed interface Loaded {
        data class Fresh(val page: DbPage) : Loaded
        /** offline: letzter bekannter Stand */
        data class Cached(val page: DbPage) : Loaded
    }

    /** Eine Seite Zeilen; die erste Seite jeder Abfrage wird fuer offline gemerkt */
    suspend fun rows(dbId: Long, query: String): Loaded {
        withContext(Dispatchers.IO) { lock.withLock { ensureLoaded() } }
        val path = "api/notes/$dbId/rows" + if (query.isEmpty()) "" else "?$query"
        val key = "$dbId?$query"
        return try {
            val text = call("GET", path, null)
            val page = withContext(Dispatchers.Default) { DbPage.parse(text) }
            if (!query.contains("offset=")) withContext(Dispatchers.IO) { writeCache(key, text) }
            Loaded.Fresh(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404 || e.code == 403) withContext(Dispatchers.IO) { cacheFile(key)?.delete() }
            throw e
        } catch (e: Exception) {
            val cached = withContext(Dispatchers.IO) { readCache(key) } ?: throw e
            Loaded.Cached(withContext(Dispatchers.Default) { DbPage.parse(cached) })
        }
    }

    private fun writeCache(key: String, text: String) {
        val f = cacheFile(key) ?: return
        try {
            f.parentFile?.mkdirs()
            f.writeText(text)
        } catch (_: Exception) {
        }
    }

    private fun readCache(key: String): String? = try {
        cacheFile(key)?.takeIf { it.exists() }?.readText()
    } catch (_: Exception) {
        null
    }

    /** Abmelden/Kontowechsel: Zwischenspeicher aller Konten weg (die Warteschlange bleibt wie Entwuerfe je Konto) */
    fun clearCache() {
        dir?.listFiles()?.forEach { a -> File(a, "rows").deleteRecursively() }
        loadedFor = null
        _pending.value = emptyMap()
    }

    enum class Saved { SENT, QUEUED }

    /** Wert setzen: sofort in der Warteschlange, dann senden. QUEUED = offline, wird nachgeholt */
    suspend fun setProp(rowId: Long, pid: String, value: JsonElement?): Saved {
        val v = value ?: JsonNull
        withContext(Dispatchers.IO) {
            lock.withLock {
                ensureLoaded()
                val m = _pending.value.toMutableMap()
                m[rowId] = (m[rowId].orEmpty() + (pid to v))
                _pending.value = m
                persist()
            }
        }
        changes.value++
        return if (flushRow(rowId)) Saved.SENT else Saved.QUEUED
    }

    /** Alle offenen Werte senden (Netz wieder da, App im Vordergrund) */
    suspend fun flush() {
        withContext(Dispatchers.IO) { lock.withLock { ensureLoaded() } }
        var any = false
        for (id in _pending.value.keys.toList()) {
            try {
                if (flushRow(id)) any = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiException) {
                any = true                                               // abgelehnt und verworfen: Ansichten laden neu
            }
        }
        if (any) changes.value++
    }

    /** true = gesendet (oder vom Server endgueltig abgelehnt und verworfen), false = offline, bleibt liegen */
    private suspend fun flushRow(rowId: Long): Boolean {
        val sent = _pending.value[rowId] ?: return true
        try {
            call("PATCH", "api/notes/$rowId", JsonObject(mapOf("props" to JsonObject(sent))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // abgemeldet / Zeitueberschreitung / gebremst / Server gestoert: spaeter noch einmal (nicht verwerfen)
            if (e.code == 401 || e.code == 408 || e.code == 429 || e.code >= 500) return false
            done(rowId, sent)                                           // abgelehnt (400/403/404/409/423): verwerfen
            throw e
        } catch (_: Exception) {
            return false
        }
        done(rowId, sent)
        return true
    }

    /** Gesendete Werte aus der Warteschlange nehmen - nur, wenn sie inzwischen nicht erneut geaendert wurden */
    private suspend fun done(rowId: Long, sent: Map<String, JsonElement>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val m = _pending.value.toMutableMap()
            val left = m[rowId].orEmpty().filter { (k, v) -> sent[k] != v }
            if (left.isEmpty()) m.remove(rowId) else m[rowId] = left
            _pending.value = m
            persist()
        }
    }

    /** Schema (Ansichten, Filter, Optionen) speichern -> Schema mit neuer srev */
    suspend fun saveSchema(dbId: Long, schema: JsonObject): JsonObject {
        val text = call("PATCH", "api/notes/$dbId", JsonObject(mapOf("schema" to schema)))
        val srev = try {
            (json.parseToJsonElement(text) as? JsonObject)?.get("srev")
        } catch (_: Exception) {
            null
        }
        changes.value++
        return if (srev is JsonPrimitive && srev !is JsonNull) JsonObject(LinkedHashMap(schema).apply { put("srev", srev) }) else schema
    }

    /** Relation bearbeiten: Eintraege der Ziel-Datenbank suchen (hoechstens 60, ohne Zwischenspeicher) */
    suspend fun search(target: Long, q: String): List<Pair<Long, String>> {
        val text = call("GET", "api/notes/$target/rows?" + DbParams.query(view = null, filters = emptyList(), sorts = listOf(DbSort("title")), q = q, group = "", limit = 60), null)
        return withContext(Dispatchers.Default) {
            val p = DbPage.parse(text)
            val needle = q.trim().lowercase()
            // alter Server ohne Parameter: selbst suchen und kuerzen
            (if (p.paged || needle.isEmpty()) p.rows else p.rows.filter { it.title.lowercase().contains(needle) }).take(60).map { it.id to it.title }
        }
    }

    fun changed() {
        changes.value++
    }
}
