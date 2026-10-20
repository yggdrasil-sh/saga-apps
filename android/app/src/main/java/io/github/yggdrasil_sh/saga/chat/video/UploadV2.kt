package io.github.yggdrasil_sh.saga.chat.video

import io.github.yggdrasil_sh.saga.chat.FileV2Writer
import io.github.yggdrasil_sh.saga.data.ApiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.OutputStream

/** Upload saga-file/v2 in Teilen (docs/APP-API.md "Upload (fortsetzbar)") - Zugang zum Server, fuer Tests austauschbar */
interface UploadHttp {
    /** POST /api/chats/{cid}/uploads {size, fmt:2} -> Kennung; Fehler -> UploadRejected (code, Text) */
    suspend fun create(chatId: Long, size: Long): Created

    /** PUT url mit Content-Range from..until-1/total; der Rumpf wird von write erzeugt (Datenstrom) */
    suspend fun put(url: String, from: Long, until: Long, total: Long, write: (OutputStream) -> Unit): PutResult

    /** HEAD url -> Stand (204) bzw. code 404 */
    suspend fun head(url: String): HeadResult

    suspend fun delete(id: String)

    data class Created(val upload: String, val offset: Long, val partMax: Long, val url: String)
    data class PutResult(val code: Int, val offset: Long? = null, val done: Boolean = false, val id: String? = null, val error: String? = null)
    data class HeadResult(val code: Int, val offset: Long = 0, val length: Long = 0, val done: Boolean = false)
}

/** Upload ist beim Server nicht mehr da (abgelaufen, abgebrochen, verworfen) -> neu beginnen bzw. aufgeben */
class UploadGone : Exception("upload gone")

/** Server lehnt ab (400/409/413/…): code + Text des Servers */
class UploadRejected(val code: Int, val error: String?) : Exception("upload $code")

/**
 * Teile fortlaufend senden, ab offset (HEAD nach Abbruch/Neustart). 409 {offset} -> dort weiter; Netzfehler, 408, 429
 * und 5xx -> warten (retryWait), Stand per HEAD holen, weiter; nach maxFails Fehlern in Folge aufgeben (der Worker
 * versucht es spaeter erneut). Gibt die Datei-Kennung zurueck (= Upload-Kennung).
 */
class V2Uploader(
    private val http: UploadHttp,
    private val writer: FileV2Writer,
    private val partMax: Long,
    private val maxFails: Int = 6,
    private val retryWait: suspend (Int) -> Unit = { n -> kotlinx.coroutines.delay(minOf(15_000L, 500L shl minOf(n, 5))) },
) {
    val total: Long get() = writer.blobSize

    suspend fun resumeOffset(url: String, uploadId: String): Long? {
        val h = http.head(url)
        if (h.code == 404) throw UploadGone()
        if (h.code !in 200..299) return null
        if (h.length != 0L && h.length != total) throw UploadGone()
        return if (h.done) total else h.offset
    }

    suspend fun upload(uploadId: String, url: String, start: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        var off = start
        var fails = 0
        onProgress(off, total)
        suspend fun recover(cause: Exception) {
            fails++
            if (fails > maxFails) throw cause
            retryWait(fails)
            val o = try {
                resumeOffset(url, uploadId)
            } catch (e: IOException) {
                null
            }
            if (o != null) off = o
        }
        while (true) {
            if (off >= total) {
                // alles gesendet, aber die Antwort fehlt (Abbruch nach dem letzten Teil): Stand pruefen
                val h = http.head(url)
                if (h.code == 404) throw UploadGone()
                if (h.done) {
                    onProgress(total, total)
                    return uploadId
                }
                off = h.offset
                if (off >= total) throw UploadRejected(400, null)
                continue
            }
            val to = minOf(off + partMax, total)
            val r = try {
                val from = off
                http.put(url, from, to, total) { out -> writer.write(from, to, out) }
            } catch (e: IOException) {
                recover(e)
                continue
            }
            when {
                r.code in 200..299 -> {
                    fails = 0
                    if (r.done) {
                        onProgress(total, total)
                        return r.id?.ifEmpty { null } ?: uploadId
                    }
                    off = r.offset ?: to
                    onProgress(off, total)
                }
                r.code == 409 && r.offset != null -> off = r.offset
                r.code == 404 -> throw UploadGone()
                r.code == 408 || r.code == 429 || r.code >= 500 -> recover(UploadRejected(r.code, r.error))
                else -> throw UploadRejected(r.code, r.error)
            }
        }
    }
}

/** Echter Zugang ueber ApiClient (Sitzung, X-Saga) - Rumpf als Datenstrom (kein Teil liegt ganz im Speicher) */
class ApiUploadHttp(private val api: ApiClient) : UploadHttp {
    private fun obj(t: String): JsonObject? = try {
        Json.parseToJsonElement(t) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

    override suspend fun create(chatId: Long, size: Long): UploadHttp.Created {
        val body = buildJsonObject { put("size", size); put("fmt", 2) }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val r = api.raw("POST", "api/chats/$chatId/uploads", body)
        val o = obj(r.text)
        if (r.code !in 200..299) throw UploadRejected(r.code, o?.str("error"))
        val id = o?.str("upload") ?: throw UploadRejected(r.code, null)
        return UploadHttp.Created(id, o.num("offset") ?: 0, o.num("part_max") ?: (8L shl 20), o.str("url") ?: "/api/chat/uploads/$id")
    }

    private fun path(url: String) = url.trimStart('/')

    override suspend fun put(url: String, from: Long, until: Long, total: Long, write: (OutputStream) -> Unit): UploadHttp.PutResult {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = until - from
            override fun writeTo(sink: BufferedSink) = write(sink.outputStream())
        }
        val r = api.raw("PUT", path(url), body, mapOf("Content-Range" to "bytes $from-${until - 1}/$total"))
        val o = obj(r.text)
        val off = o?.num("offset") ?: r.headers["Upload-Offset"]?.toLongOrNull()
        val done = (o?.get("done") as? JsonPrimitive)?.booleanOrNull == true
        return UploadHttp.PutResult(r.code, off, done, o?.str("id"), o?.str("error"))
    }

    override suspend fun head(url: String): UploadHttp.HeadResult {
        val r = api.raw("HEAD", path(url))
        return UploadHttp.HeadResult(
            r.code, r.headers["Upload-Offset"]?.toLongOrNull() ?: 0, r.headers["Upload-Length"]?.toLongOrNull() ?: 0,
            r.headers["Upload-Done"] == "1",
        )
    }

    override suspend fun delete(id: String) {
        try {
            api.raw("DELETE", "api/chat/uploads/$id")
        } catch (_: IOException) {
        }
    }
}
