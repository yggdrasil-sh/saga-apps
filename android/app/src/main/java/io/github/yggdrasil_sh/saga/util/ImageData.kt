package io.github.yggdrasil_sh.saga.util

/** Bildformate erkennen (ohne Android) - der Server nimmt fuer Seiten nur PNG, JPEG, GIF und WebP an */
object ImageData {
    /** MIME-Typ nach den ersten Bytes, null = kein vom Server unterstuetztes Bild */
    fun sniff(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 6 && String(b, 0, 6, Charsets.ISO_8859_1).let { it == "GIF87a" || it == "GIF89a" } -> "image/gif"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        else -> null
    }

    fun extension(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    /** Dateiname mit passender Endung (z. B. IMG_1.HEIC als JPEG -> IMG_1.jpg); leer -> "bild" */
    fun nameFor(name: String, mime: String): String {
        val base = name.substringAfterLast('/').substringBeforeLast('.').trim().ifEmpty { "bild" }.take(120)
        return base + "." + extension(mime)
    }
}

/**
 * Immich im Chat: welche Groesse(n) ueber den Immich-Proxy des Servers laden (GET /api/immich/thumb/{id}?size=...).
 * Moeglichst das Original (PNG/JPEG/GIF/WebP); andere Formate (HEIC, RAW ...) liefert der Server nicht im Original
 * (415) -> gleich die grosse Vorschau. 404/413/415 beim Original -> Vorschau.
 */
object ImmichChat {
    private val ORIGINAL_OK = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    val FALLBACK_CODES = setOf(404, 413, 415)

    fun sizes(mime: String?): List<String> = if (mime?.lowercase() in ORIGINAL_OK) listOf("original", "preview") else listOf("preview")

    fun path(id: String, size: String): String = "api/immich/thumb/$id?size=$size"

    /**
     * Groessen der Reihe nach versuchen; HTTP-Fehler aus FALLBACK_CODES -> naechste Groesse, alles andere bricht ab.
     * code(e) liefert den HTTP-Status einer Ausnahme (sonst null).
     */
    suspend fun <T> firstAvailable(sizes: List<String>, code: (Throwable) -> Int?, fetch: suspend (String) -> T): T {
        var last: Throwable? = null
        for (s in sizes) {
            try {
                return fetch(s)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (code(e) !in FALLBACK_CODES) throw e
                last = e
            }
        }
        throw last ?: IllegalStateException("keine Groesse")
    }
}
