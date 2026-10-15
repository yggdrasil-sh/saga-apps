package io.github.yggdrasil_sh.saga.chat

import java.io.File
import java.util.UUID

/**
 * Entschluesselte Chat-Anhaenge zum Oeffnen mit einer anderen App (BUG-023). Klartext liegt nur kurz im Cache:
 * je Anhang ein eigener Zufallsordner (gleichnamige Anhaenge ueberschreiben sich nicht), nach KEEP_MS wird die
 * Freigabe (FileProvider) zurueckgenommen und die Datei geloescht; App-Start und Abmelden raeumen alles weg
 * (auch nach Prozess-Tod liegen gebliebenes). Bilder im Chat werden nur im Speicher entschluesselt.
 */
object ChatFiles {
    const val DIR = "chat-files"
    /** so lange darf die andere App die Datei lesen */
    const val KEEP_MS = 2 * 60_000L

    fun dir(cache: File) = File(cache, DIR)

    fun safeName(n: String): String = n.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ').ifEmpty { "datei" }.take(100)

    /** Klartext in eigenen Zufallsordner schreiben -> Datei */
    fun write(cache: File, name: String, data: ByteArray): File {
        val d = File(dir(cache), UUID.randomUUID().toString()).apply { mkdirs() }
        return File(d, safeName(name)).apply { writeBytes(data) }
    }

    /** Ordner einer geschriebenen Datei loeschen */
    fun remove(f: File) {
        f.parentFile?.takeIf { it.parentFile?.name == DIR }?.deleteRecursively() ?: f.delete()
    }

    /** Alles loeschen (App-Start, Abmelden) */
    fun purge(cache: File) {
        dir(cache).deleteRecursively()
    }

    /** Abgelaufene loeschen (aelter als keepMs) */
    fun purgeOlderThan(cache: File, now: Long = System.currentTimeMillis(), keepMs: Long = KEEP_MS) {
        dir(cache).listFiles()?.forEach { d -> if (now - d.lastModified() > keepMs) d.deleteRecursively() }
    }
}
