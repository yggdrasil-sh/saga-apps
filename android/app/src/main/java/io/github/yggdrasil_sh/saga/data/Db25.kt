package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.db.DbFiles
import io.github.yggdrasil_sh.saga.db.DbPerson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// 2.5 Etappe 1: Server-Aufrufe fuer Personen (person1), Dateien (file1) und Gewohnheiten (habit1).

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

/** Personen mit Zugriff auf die Datenbank: GET /api/notes/{db}/people?q= (hoechstens 20, nie Anmeldenamen) */
suspend fun SagaRepository.dbPeople(db: Long, q: String): List<DbPerson> {
    val text = api.get("api/notes/$db/people?q=" + java.net.URLEncoder.encode(q.take(80), "UTF-8"))
    return parsePeopleSearch(text).also { l -> rememberPeople(l.associateBy { it.ref }) }
}

internal fun parsePeopleSearch(text: String): List<DbPerson> {
    val o = runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
    return (o["people"] as? JsonArray).orEmpty().mapNotNull { e ->
        val x = e as? JsonObject ?: return@mapNotNull null
        val ref = (x["ref"] as? JsonPrimitive)?.content?.ifBlank { null } ?: return@mapNotNull null
        DbPerson(ref, (x["name"] as? JsonPrimitive)?.content.orEmpty(), (x["avatar"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            (x["me"] as? JsonPrimitive)?.booleanOrNull == true, (x["remote"] as? JsonPrimitive)?.booleanOrNull == true)
    }
}

/** Datei fuer eine Zeile hochladen (POST /api/files?note=<zeile>, Feld "file") - gehoert dem Eigentuemer der Datenbank */
suspend fun SagaRepository.uploadRowFile(row: Long, ctx: android.content.Context, uri: android.net.Uri): DbFiles.File? {
    val (bytes, name, mime) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        var name = "datei"
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val b = cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        Triple(b, name, mime)
    }
    if (bytes.isEmpty()) return null
    val field = if (mime.startsWith("image/")) "image" else "file"
    val text = api.postMultipart("api/files?note=$row", field, name, mime, bytes)
    return DbFiles.fromUpload(text, name, mime)
}

/** Gewohnheit abhaken: POST /api/notes/{seite}/widgets/{bid}/habit -> neuer Stand (Rohtext) */
suspend fun SagaRepository.habitCheck(page: Long, blockId: String, body: JsonObject): String =
    api.post("api/notes/$page/widgets/" + java.net.URLEncoder.encode(blockId, "UTF-8") + "/habit" +
        (io.github.yggdrasil_sh.saga.blocks.PageWidgets.tzParam(java.time.ZoneId.systemDefault().id)?.let { "?tz=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""), body)
