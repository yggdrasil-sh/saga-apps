package io.github.yggdrasil_sh.saga.chat.video

import io.github.yggdrasil_sh.saga.chat.ChatThumb
import io.github.yggdrasil_sh.saga.data.SecureStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ein zu sendendes Video (eigene Nachricht). Liegt bis zum Senden im geschuetzten Speicher (SecureStore, Keystore):
 * Quelle, vorbereitete Datei, Metadaten, Poster - und fuer saga-file/v2 K, prefix und Upload-Kennung, damit ein
 * Upload nach Netzabbruch oder App-Neustart weiterlaufen kann (docs/APP-API.md, "Fortsetzen"). Nach dem Senden geloescht.
 */
@Serializable
data class VideoJob(
    val id: String,
    val chatId: Long,
    /** Quelle (content://…) */
    val uri: String,
    val name: String,
    val mime: String,
    /** "optimiert 720p" (Standard) oder Original */
    val optimize: Boolean = true,
    /** angelegt (ms) - frische Auftraege raeumt Videos.sweep nicht weg */
    val created: Long = 0,
    /** vorbereitete Datei (optimiert) im App-Speicher; null = Quelle direkt */
    val file: String? = null,
    /** Klartext-Groesse der zu sendenden Datei; null = noch nicht vorbereitet */
    val size: Long? = null,
    val dur: Double? = null,
    val w: Int? = null,
    val h: Int? = null,
    val rot: Int? = null,
    val fs: Boolean? = null,
    /** Poster (v1-Anhang, eigener Schluessel) - schon hochgeladen */
    val th: ChatThumb? = null,
    /** Poster vorbereitet, aber nicht moeglich (kein Bild) */
    val noPoster: Boolean = false,
    /** saga-file/v2: Schluessel (B64), prefix (Hex), Upload-Kennung und -Adresse */
    val key: String? = null,
    val prefix: String? = null,
    val upload: String? = null,
    val url: String? = null,
    /** fertig hochgeladen: Datei-Kennung, Format (1/2), Schluessel (B64) */
    val fileId: String? = null,
    val x: Int? = null,
)

/** Ablage der Auftraege (verschluesselt im Keystore-Speicher) + Liste der Kennungen fuers Aufraeumen */
class VideoJobs(private val secure: SecureStore) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun k(id: String) = "video_job_$id"

    @Synchronized
    fun get(id: String): VideoJob? = secure.get(k(id))?.let {
        try {
            json.decodeFromString<VideoJob>(it)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun put(j: VideoJob) {
        secure.put(k(j.id), json.encodeToString(VideoJob.serializer(), j))
        val ids = ids()
        if (j.id !in ids) secure.put(INDEX, (ids + j.id).joinToString(","))
    }

    @Synchronized
    fun remove(id: String) {
        secure.put(k(id), null)
        secure.put(INDEX, (ids() - id).joinToString(",").ifEmpty { null })
    }

    @Synchronized
    fun ids(): List<String> = secure.get(INDEX)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    @Synchronized
    fun all(): List<VideoJob> = ids().mapNotNull { get(it) }

    companion object {
        private const val INDEX = "video_jobs"
    }
}
