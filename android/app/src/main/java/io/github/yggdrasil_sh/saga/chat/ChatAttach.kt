package io.github.yggdrasil_sh.saga.chat

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Anhaenge vor dem Senden: Groesse pruefen, bevor die Datei im Speicher liegt (BUG-005) */
object ChatAttach {
    /** Ergebnis des Einlesens einer gewaehlten Datei */
    sealed interface Read {
        class Ok(val file: PendingFile) : Read
        /** groesser als das Server-Limit (file_max) */
        data object TooBig : Read
        data object Failed : Read
        /** 2.4: Video - wird nicht eingelesen, sondern im Hintergrund vorbereitet und gesendet (VideoUploadWorker) */
        class Video(val uri: String, val name: String, val mime: String, val size: Long?) : Read
    }

    /** Name ohne Aussagekraft: nur Ziffern (Foto-Auswahl: "1000000020.mp4"), leer oder der Platzhalter "datei" */
    fun meaningless(name: String): Boolean {
        val base = name.substringBeforeLast('.').trim()
        return base.isEmpty() || base == "datei" || base.all { it.isDigit() }
    }

    private val EXT = mapOf(
        "video/mp4" to "mp4", "video/quicktime" to "mov", "video/webm" to "webm", "video/3gpp" to "3gp", "video/x-matroska" to "mkv",
        "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif", "image/heic" to "heic",
    )

    /**
     * Sinnvoller Dateiname fuer Anhaenge ohne echten Namen (TT 2.4 P-22): Art, Datum/Uhrzeit und Chat, z. B.
     * "VID_20261020_1130_Nora-Vogel.mp4". Echte Namen ("urlaub.mp4") bleiben unveraendert.
     */
    fun niceName(name: String, mime: String, chat: String, now: java.time.LocalDateTime): String {
        if (!meaningless(name)) return name
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
            ?: EXT[mime.lowercase()] ?: mime.substringAfter('/', "").takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
        val kind = when {
            mime.startsWith("video/") -> "VID"
            mime.startsWith("image/") -> "IMG"
            else -> "DATEI"
        }
        val stamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
        val who = chat.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(30)
        val base = listOf(kind, stamp, who).filter { it.isNotEmpty() }.joinToString("_")
        return if (ext != null) "$base.$ext" else base
    }

    /** Angegebene Groesse (OpenableColumns.SIZE) schon ueber dem Limit? Unbekannt (null/negativ) -> beim Lesen pruefen */
    fun tooBig(declared: Long?, max: Long): Boolean = declared != null && declared > max

    /**
     * Hoechstens max Bytes lesen; mehr -> null. Liest in Stuecken, nie die ganze Datei auf einmal
     * (600-MB-Video fuehrte zu OutOfMemoryError).
     */
    fun readLimited(input: InputStream, max: Long): ByteArray? {
        val out = ByteArrayOutputStream(minOf(max, 64L * 1024).toInt().coerceAtLeast(32))
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
