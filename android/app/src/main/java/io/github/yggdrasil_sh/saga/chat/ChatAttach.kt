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
