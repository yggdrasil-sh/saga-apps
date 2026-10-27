package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// Saga 2.3: persoenliches Backup ("Mein Backup") und Nextcloud-Dateien in Seiten (docs/APP-API.md, Abschnitte
// "Persoenliches Backup (2.3)" und "Nextcloud-Dateien in Seiten (2.3)"). Aeltere Server kennen die Endpunkte nicht (404)
// -> die Einstellungen blenden die Bereiche aus.

/** Login Flow v2 (Nextcloud): state wait|ok|err|"" ; login = Adresse zum Oeffnen im Browser (nur bei wait) */
@Serializable
data class NcFlow(val state: String = "", val login: String? = null, val message: String = "")

@Serializable
data class BackupTarget(
    val kind: String = "",
    val address: String = "",
    val user: String = "",
    val folder: String = "",
    val flow: Boolean = false,
    val allowed: Boolean = true,
)

@Serializable
data class BackupKey(val short: String = "", val fp: String = "")

@Serializable
data class BackupRun(
    val started: Double? = null,
    val finished: Double? = null,
    val trigger: String = "",
    val name: String? = null,
    val ok: Boolean = false,
    val size: Long? = null,
    val step: String = "",
    val error: String = "",
)

@Serializable
data class BackupLimits(
    @SerialName("max_mb") val maxMb: Int = 0,
    @SerialName("min_h") val minH: Double = 0.0,
    @SerialName("max_schedule") val maxSchedule: String = "daily",
)

@Serializable
data class BackupPage(val id: Long = 0, val title: String = "", val icon: String = "", val parent: Long? = null, val db: Boolean = false)

@Serializable
data class PendingKey(val public: String = "")

/** GET /api/account/backup (?pages=1) - "Status anzeigen = diese Antwort" */
@Serializable
data class BackupStatus(
    val allowed: Boolean = false,
    val ready: Boolean = false,
    val running: Boolean = false,
    @SerialName("last_ok") val lastOk: Double? = null,
    @SerialName("last_attempt") val lastAttempt: Double? = null,
    val next: Double? = null,
    val target: BackupTarget? = null,
    val keys: List<BackupKey> = emptyList(),
    @SerialName("pending_key") val pendingKey: PendingKey? = null,
    val schedule: String = "off",
    @SerialName("effective_schedule") val effectiveSchedule: String = "off",
    val time: String = "03:00",
    val weekday: Int = 0,
    val keep: Int = 7,
    /** null = Server kennt keine Kategorien (dann Bereich "Was gesichert wird" ausblenden) */
    val cats: Map<String, Boolean>? = null,
    /** null = Server kennt keine Ausschluesse */
    val exclude: List<Long>? = null,
    val runs: List<BackupRun> = emptyList(),
    val limits: BackupLimits = BackupLimits(),
    val flow: NcFlow = NcFlow(),
    @SerialName("age_ok") val ageOk: Boolean = true,
    val pages: List<BackupPage>? = null,
)

/** POST /api/account/backup/key/generate - privater Schluessel NUR in dieser Antwort (nie speichern) */
@Serializable
data class GeneratedKey(val private: String = "", val public: String = "", val filename: String = "saga-backup.key")

@Serializable
data class RemoteBackup(val name: String = "", val time: Double? = null, val size: Long? = null, val complete: Boolean = false)

@Serializable
data class RemoteBackups(val items: List<RemoteBackup> = emptyList())

/** GET /api/settings/nextcloud */
@Serializable
data class NcSettings(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val me: String = "",
    val server: String? = null,
    val user: String? = null,
    val since: Double? = null,
    val used: Double? = null,
    val suggest: String? = null,
    val flow: NcFlow = NcFlow(),
)

/** Eintrag der Dateiauswahl (GET /api/nextcloud/list|search) */
@Serializable
data class NcItem(
    val fileid: String = "",
    val name: String = "",
    val path: String = "",
    val dir: Boolean = false,
    val mime: String = "",
    val size: Long? = null,
    val mtime: Double? = null,
    val preview: Boolean = false,
    val kind: String = "file",
    val inst: String = "",
)

@Serializable
data class NcItems(val items: List<NcItem> = emptyList(), val path: String? = null)

@Serializable
data class NcEntry(val name: String = "", val dir: Boolean = false, val size: Long? = null)

/** Status eines Blocks der Seite (GET /api/notes/{id}/nc) - ok=false: Karte ohne Inhalt */
@Serializable
data class NcBlockStatus(
    val fileid: String = "",
    val author: String? = null,
    val ok: Boolean = false,
    val name: String? = null,
    val size: Long? = null,
    val mtime: Double? = null,
    val mime: String? = null,
    val dir: Boolean = false,
    val preview: Boolean = false,
    val entries: List<NcEntry>? = null,
)

@Serializable
data class NcPageStatus(val enabled: Boolean = false, val items: List<NcBlockStatus> = emptyList())

/** Reine Regeln fuer "Mein Backup" (Tests) */
object BackupLogic {
    val CATS = listOf("pages", "templates", "archive", "trash", "versions", "files", "comments", "comments_others", "tasks", "bookmarks", "settings")
    val SCHEDULES = listOf("off", "weekly", "daily")
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    /** Zeitplan waehlbar? "daily" nur, wenn der Admin nicht hoechstens woechentlich erlaubt */
    fun scheduleAllowed(s: String, limits: BackupLimits): Boolean = s != "daily" || limits.maxSchedule != "weekly"

    fun timeValid(t: String): Boolean = TIME.matches(t.trim())

    fun keepValid(k: Int?): Boolean = k != null && k in 1..60

    /** letzte 6 Zeichen zur Bestaetigung (Gross-/Kleinschreibung egal, wie der Server) */
    fun last6Valid(s: String): Boolean = s.trim().length == 6

    /** Kurz abfragen, solange ein Backup laeuft oder die Nextcloud-Anmeldung wartet */
    fun shouldPoll(s: BackupStatus?): Boolean = s != null && (s.running || s.flow.state == "wait")

    /** Fehler des letzten (fertigen) Laufs - fuer den roten Hinweis unter dem Status */
    fun lastError(s: BackupStatus): String? = s.runs.firstOrNull()?.takeIf { !s.running && it.finished != null && !it.ok }?.error?.ifBlank { null }

    /** Was fehlt zum Sichern (Ziel, Schluessel) */
    fun missingTarget(s: BackupStatus): Boolean = s.target == null
    fun missingKey(s: BackupStatus): Boolean = s.keys.isEmpty()

    /** Seiten fuer die Ausschluss-Auswahl als Baum (Einrueckung), wie im Web; Eltern ausserhalb der Liste -> oberste Ebene */
    fun pageTree(pages: List<BackupPage>, maxDepth: Int = 12): List<Pair<BackupPage, Int>> {
        val ids = pages.map { it.id }.toSet()
        val kids = pages.groupBy { p -> p.parent?.takeIf { it in ids } ?: 0L }
        val out = mutableListOf<Pair<BackupPage, Int>>()
        val seen = HashSet<Long>()
        fun walk(k: Long, d: Int) {
            for (p in kids[k].orEmpty()) {
                if (!seen.add(p.id)) continue
                out += p to d
                if (d < maxDepth) walk(p.id, d + 1)
            }
        }
        walk(0L, 0)
        return out
    }

    fun settingsBody(schedule: String, time: String, weekday: Int, keep: Int): JsonObject = buildJsonObject {
        put("schedule", schedule); put("time", time.trim()); put("weekday", weekday); put("keep", keep)
    }

    fun contentBody(cats: Map<String, Boolean>, exclude: List<Long>): JsonObject = buildJsonObject {
        putJsonObject("cats") { cats.forEach { (k, v) -> if (k in CATS) put(k, v) } }
        putJsonArray("exclude") { exclude.distinct().forEach { add(JsonPrimitive(it)) } }
    }

    /** Groesse lesbar (B, KB, MB, GB, TB), wie im Web */
    fun size(n: Long?): String {
        if (n == null) return "?"
        val u = listOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$n ${u[0]}" else String.format(java.util.Locale.ROOT, "%.1f %s", v, u[i])
    }
}

/** Reine Regeln fuer Nextcloud-Bloecke (Tests) */
object NcLogic {
    private val FILEID = Regex("^[1-9][0-9]{0,19}$")
    private val HTTP = Regex("^https?://[^\\s\"<>]+$")

    fun str(d: JsonObject?, k: String): String = (d?.get(k) as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull ?: ""

    /** Name zur Anzeige (der Server speichert & als &amp;) */
    fun name(d: JsonObject?): String = InlineHtml.decodeEntities(str(d, "name"))

    fun kind(d: JsonObject?): String = str(d, "kind").ifEmpty { "file" }

    fun size(d: JsonObject?): Long? = (d?.get("size") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    /** Typ-Symbol wie im Web */
    fun icon(kind: String, mime: String?, dir: Boolean = false): String {
        if (dir || kind == "folder") return "📁"
        val m = mime.orEmpty()
        return when {
            m.startsWith("image/") || kind == "image" -> "🖼"
            m == "application/pdf" -> "📕"
            m.startsWith("video/") -> "🎞"
            m.startsWith("audio/") -> "🎵"
            Regex("spreadsheet|excel|csv").containsMatchIn(m) -> "📊"
            Regex("presentation|powerpoint").containsMatchIn(m) -> "📽"
            Regex("zip|compressed|tar").containsMatchIn(m) -> "🗜"
            else -> "📄"
        }
    }

    /** "In Nextcloud oeffnen": <inst>/f/<fileid> - nur http(s) und gueltige Datei-ID, nie auf Seiten anderer Instanzen */
    fun openUrl(d: JsonObject?, remotePage: Boolean): String? {
        if (remotePage) return null
        val inst = str(d, "inst")
        val fid = str(d, "fileid")
        if (!HTTP.matches(inst) || !FILEID.matches(fid)) return null
        return inst.trimEnd('/') + "/f/" + fid
    }

    /** Vorschau/Download ueber den Saga-Proxy (Sitzung reicht): /api/notes/{id}/nc/{fileid}?a={author}&w= bzw. &dl=1 */
    fun proxyPath(noteId: Long, d: JsonObject?, width: Int? = null, download: Boolean = false): String? {
        val fid = str(d, "fileid")
        if (!FILEID.matches(fid)) return null
        val base = "api/notes/$noteId/nc/$fid?a=" + java.net.URLEncoder.encode(str(d, "author"), "UTF-8")
        return when {
            download -> "$base&dl=1"
            width != null -> base + "&w=" + width.coerceIn(16, 2000)
            else -> base
        }
    }

    /** Status zum Block: gleiche Datei und gleiche Person (auf Seiten anderer Instanzen ist author ein Platzhalter) */
    fun statusFor(st: NcPageStatus?, d: JsonObject?, remotePage: Boolean): NcBlockStatus? {
        val fid = str(d, "fileid")
        val author = str(d, "author")
        return st?.items?.firstOrNull { it.fileid == fid && (remotePage || (it.author ?: "") == author) }
    }

    /** Daten fuer einen neuen Block aus der eigenen Nextcloud (author/sig setzt der Server; author = ich fuer die Anzeige) */
    fun newBlockData(it: NcItem, me: String): JsonObject = buildJsonObject {
        put("inst", it.inst)
        put("fileid", it.fileid)
        put("kind", if (it.kind in listOf("file", "image", "folder")) it.kind else "file")
        put("name", it.name)
        put("mime", it.mime)
        it.size?.let { s -> put("size", s) }
        it.mtime?.let { t -> put("mtime", t) }
        if (me.isNotEmpty()) put("author", me)
        put("public", false)
        put("fed", false)
    }

    /** Schluessel einer Datei auf der Seite (Person|Nextcloud|Datei) */
    fun key(d: JsonObject?): String = listOf(str(d, "author"), str(d, "inst"), str(d, "fileid")).joinToString("|")

    /** Alle Nextcloud-Bloecke (auch in Toggles/Spalten) */
    fun blocks(doc: JsonElement?): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(arr: JsonElement?) {
            (arr as? JsonArray)?.forEach { b ->
                val o = b as? JsonObject ?: return@forEach
                val d = o["data"] as? JsonObject
                if (str(o, "type") == "nextcloud" && d != null) out += d
                walk(d?.get("blocks"))
                (d?.get("cols") as? JsonArray)?.forEach { c -> walk((c as? JsonObject)?.get("blocks")) }
            }
        }
        walk((doc as? JsonObject)?.get("blocks"))
        return out
    }

    fun keys(doc: JsonElement?): Set<String> = blocks(doc).map { key(it) }.toSet()

    /** Nextcloud-Bloecke, die nicht im gespeicherten Stand stehen (neu eingefuegt/kopiert) */
    fun newKeys(doc: JsonElement?, base: Set<String>): Set<String> = keys(doc) - base

    /**
     * Speichern abgelehnt wegen Nextcloud-Bloecken (Server prueft neue Bloecke: fremde -> 403, falsche Nextcloud -> 400,
     * ohne Verknuepfung -> 409)? Nur wenn neue Bloecke im Spiel sind und es kein Bearbeitungskonflikt ist.
     */
    fun isRejection(code: Int, body: String, newKeys: Set<String>): Boolean {
        if (newKeys.isEmpty() || code !in setOf(400, 403, 409)) return false
        val err = try {
            ((kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
        } catch (_: Exception) {
            null
        }
        return !err.isNullOrBlank() && err != "conflict" && err != "forbidden" && err != "not found"
    }

    /**
     * Neu hinzugekommene Nextcloud-Bloecke anderer Personen (nicht im gespeicherten Stand) - der Server lehnt das Speichern
     * dann ab (403). Leere Menge = nichts Fremdes neu.
     */
    fun foreignNew(doc: JsonElement?, base: Set<String>, me: String): Set<String> =
        blocks(doc).filter { d -> str(d, "author").let { a -> a.isNotEmpty() && a != me } && key(d) !in base }.map { key(it) }.toSet()

    /**
     * Nextcloud-Bloecke anderer Personen entfernen (fuer eine NEUE Seite, z. B. "Als Kopie speichern" - der Server nimmt
     * dort nur eigene an). -> (Dokument, Anzahl entfernt)
     */
    fun stripForeign(doc: JsonObject, me: String): Pair<JsonObject, Int> {
        var removed = 0
        fun clean(arr: JsonElement?): JsonElement? {
            val a = arr as? JsonArray ?: return arr
            return JsonArray(a.mapNotNull { b ->
                val o = b as? JsonObject ?: return@mapNotNull b
                val d = o["data"] as? JsonObject
                if (str(o, "type") == "nextcloud" && str(d, "author").let { it.isNotEmpty() && it != me }) {
                    removed++
                    return@mapNotNull null
                }
                if (d == null) return@mapNotNull o
                var nd: JsonObject = d
                if (d["blocks"] is JsonArray) nd = JsonObject(nd + ("blocks" to clean(d["blocks"])!!))
                (d["cols"] as? JsonArray)?.let { cols ->
                    nd = JsonObject(nd + ("cols" to JsonArray(cols.map { c ->
                        val co = c as? JsonObject ?: return@map c
                        if (co["blocks"] is JsonArray) JsonObject(co + ("blocks" to clean(co["blocks"])!!)) else co
                    })))
                }
                if (nd === d) o else JsonObject(o + ("data" to nd))
            })
        }
        val b = doc["blocks"] ?: return doc to 0
        val nb = clean(b) ?: return doc to 0
        return JsonObject(doc + ("blocks" to nb)) to removed
    }
}

// ---------------------------------------------------------------- Aufrufe

private fun SagaRepository.backupOf(text: String): BackupStatus = json.decodeFromString(BackupStatus.serializer(), text)

/** null = Server kennt "Mein Backup" nicht (aelter als 2.3) */
suspend fun SagaRepository.backupStatus(pages: Boolean = false): BackupStatus? = try {
    backupOf(api.get("api/account/backup" + if (pages) "?pages=1" else ""))
} catch (e: ApiException) {
    if (e.code == 404 || e.code == 405) null else throw e
}

suspend fun SagaRepository.backupRun() { api.post("api/account/backup/run") }

/** -> Login-Adresse (Nextcloud ohne Benutzer: Login Flow v2) oder null (verbunden) */
suspend fun SagaRepository.backupConnect(url: String, user: String, password: String, folder: String): String? {
    val r = json.parseToJsonElement(api.post("api/account/backup/connect", buildJsonObject {
        put("url", url.trim()); put("user", user.trim()); put("password", password); put("folder", folder.trim())
    })) as? JsonObject
    return NcLogic.str(r, "flow").ifEmpty { null }
}

suspend fun SagaRepository.backupFlowCancel() { api.post("api/account/backup/flow/cancel") }

/** -> freier Platz im Ziel (Bytes) oder null (unbekannt) */
suspend fun SagaRepository.backupTest(): Long? =
    ((json.parseToJsonElement(api.post("api/account/backup/test")) as? JsonObject)?.get("free") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

suspend fun SagaRepository.backupDisconnect() { api.delete("api/account/backup/target") }

suspend fun SagaRepository.backupKeyGenerate(): GeneratedKey = json.decodeFromString(GeneratedKey.serializer(), api.post("api/account/backup/key/generate"))

suspend fun SagaRepository.backupKeyConfirm(last6: String) { api.post("api/account/backup/key/confirm", buildJsonObject { put("last6", last6.trim()) }) }

suspend fun SagaRepository.backupKeyDrop() { api.post("api/account/backup/key/confirm", buildJsonObject { put("drop", true) }) }

suspend fun SagaRepository.backupKeyAdd(key: String) { api.post("api/account/backup/key", buildJsonObject { put("key", key.trim()) }) }

suspend fun SagaRepository.backupKeyDelete(fp: String) { api.delete("api/account/backup/key/" + android.net.Uri.encode(fp)) }

suspend fun SagaRepository.backupSettings(body: JsonObject): BackupStatus = backupOf(api.request("PUT", "api/account/backup/settings", body))

suspend fun SagaRepository.backupRemote(): List<RemoteBackup> = json.decodeFromString(RemoteBackups.serializer(), api.get("api/account/backup/remote")).items

/** null = Server kennt Nextcloud-Dateien nicht (aelter als 2.3) */
suspend fun SagaRepository.ncSettings(): NcSettings? = try {
    json.decodeFromString(NcSettings.serializer(), api.get("api/settings/nextcloud")).also { if (it.me.isNotEmpty()) ncMeCache = it.me }
} catch (e: ApiException) {
    if (e.code == 404 || e.code == 405) null else throw e
}

/** -> Login-Adresse (Login Flow v2) */
suspend fun SagaRepository.ncConnect(url: String): String =
    NcLogic.str(json.parseToJsonElement(api.post("api/settings/nextcloud", buildJsonObject { put("url", url.trim()) })) as? JsonObject, "flow")

suspend fun SagaRepository.ncCancel() { api.post("api/settings/nextcloud/cancel") }

suspend fun SagaRepository.ncDisconnect() { api.delete("api/settings/nextcloud") }

suspend fun SagaRepository.ncList(path: String): NcItems =
    json.decodeFromString(NcItems.serializer(), api.get("api/nextcloud/list?path=" + java.net.URLEncoder.encode(path.ifEmpty { "/" }, "UTF-8")))

suspend fun SagaRepository.ncSearch(q: String): NcItems =
    json.decodeFromString(NcItems.serializer(), api.get("api/nextcloud/search?q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")))

suspend fun SagaRepository.ncPageStatus(noteId: Long): NcPageStatus = json.decodeFromString(NcPageStatus.serializer(), api.get("api/notes/$noteId/nc"))

/** 2.5 Widgets in Seiten (SPEC-widgets): Werte der Server-Widgets einer Seite - Rohtext, PageWidgets.parseResponse liest ihn */
suspend fun SagaRepository.pageWidgetValues(noteId: Long): String =
    api.get("api/notes/$noteId/widgets" + (io.github.yggdrasil_sh.saga.blocks.PageWidgets.tzParam(java.time.ZoneId.systemDefault().id)
        ?.let { "?tz=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""))
