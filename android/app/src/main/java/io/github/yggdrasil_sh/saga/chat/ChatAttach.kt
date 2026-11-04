package io.github.yggdrasil_sh.saga.chat

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Anhaenge vor dem Senden: Groesse pruefen, bevor die Datei im Speicher liegt (BUG-005) */
object ChatAttach {
    /** Ergebnis des Einlesens einer gewaehlten Datei */
    sealed interface Read {
        class Ok(val file: PendingFile) : Read
        /** zu gross fuer diesen Chat; maxMb = hoechstens so viele MB (Hinweistext) */
        class TooBig(val maxMb: Long) : Read
        data object Failed : Read
        /** 4.11.: Datei hat 0 Byte - nie still als leerer Anhang senden */
        data object Empty : Read
        /** 2.4: Video - wird nicht eingelesen, sondern im Hintergrund vorbereitet und gesendet (VideoUploadWorker) */
        class Video(val uri: String, val name: String, val mime: String, val size: Long?) : Read
        /** 2.5: andere Datei ueber der v1-Grenze - nicht einlesen, sondern wie Videos im Hintergrund per saga-file/v2 senden */
        class Big(val uri: String, val name: String, val mime: String, val size: Long?) : Read
    }

    /** Chats ueber Servergrenzen: der Server des Gegenuebers nimmt v1 evtl. nur bis 10 MiB (Standard ATTACH_MAX_MB) */
    const val FED_V1_MAX = 10L * 1048576

    /** Weg eines Anhangs (Datei oder Video) */
    enum class Route {
        /** bis zur v1-Grenze: ein Block (POST .../files) */
        V1,
        /** saga-file/v2, Upload in Teilen im Hintergrund */
        V2,
        /** ueber der v1-Grenze, Server kann kein saga-file/v2 (limits.file2 fehlt) */
        NO_FILE2,
        /** Chat ueber Servergrenzen, nicht alle Server koennen grosse Dateien (big_files nicht true) */
        PEER_OLD,
        /** groesser als video_max */
        TOO_BIG,
    }

    /** v1-Grenze: file_max, in Chats ueber Servergrenzen hoechstens 10 MiB */
    fun v1Max(l: ChatLimits, remote: Boolean): Long = if (remote) minOf(l.fileMax, FED_V1_MAX) else l.fileMax

    /**
     * Weg fuer einen Anhang der Groesse size (Klartext). remote = Chat ueber Servergrenzen (ChatSummary.remote),
     * bigFiles = Chat-Objekt big_files (nur dort von Bedeutung). Obergrenze fuer v2 ist video_max - auch fuer Dateien.
     */
    fun route(size: Long, l: ChatLimits, remote: Boolean, bigFiles: Boolean?): Route = when {
        size <= v1Max(l, remote) -> Route.V1
        !l.file2 -> Route.NO_FILE2
        bigFiles == false || (remote && bigFiles != true) -> Route.PEER_OLD
        l.videoMax > 0 && size > l.videoMax -> Route.TOO_BIG
        else -> Route.V2
    }

    /** groesste Groesse, die in diesem Chat gesendet werden kann */
    fun maxSize(l: ChatLimits, remote: Boolean, bigFiles: Boolean?): Long {
        val v1 = v1Max(l, remote)
        val r = route(Long.MAX_VALUE, l, remote, bigFiles)
        return if (r == Route.NO_FILE2 || r == Route.PEER_OLD) v1 else maxOf(v1, l.videoMax.takeIf { it > 0 } ?: v1)
    }

    /** Zahl (MB) fuer den Hinweis "zu gross (hoechstens N MB)" zu einem Weg */
    fun limitMb(r: Route, l: ChatLimits, remote: Boolean): Long = when (r) {
        Route.TOO_BIG -> l.videoMax / 1048576
        else -> v1Max(l, remote) / 1048576
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
