package io.github.yggdrasil_sh.saga.chat

import java.io.ByteArrayOutputStream

/**
 * Chat-Format v1 (saga-chat/v1): reine Hilfen ohne Kryptografie - Felder verketten, Base32, Wiederherstellungsschluessel
 * normalisieren, Ziffernbloecke (Kopplungs-Code, Sicherheitsnummer). Gegenstueck zu static/chat-crypto.js und
 * tests/chatlib.py im Server; alles Kryptografische macht libsodium (ChatCrypto).
 */
object ChatFormat {
    const val V = "saga-chat/v1/"
    private const val A32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** LP(f1..fn) = je Feld uint32_be(laenge) || bytes (Strings UTF-8) */
    fun lp(vararg fields: Any): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in fields) {
            val b = when (f) {
                is ByteArray -> f
                is String -> f.toByteArray(Charsets.UTF_8)
                else -> f.toString().toByteArray(Charsets.UTF_8)
            }
            val n = b.size
            out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
            out.write(b)
        }
        return out.toByteArray()
    }

    /** Base32 (RFC 4648, ohne Padding) */
    fun base32(data: ByteArray): String {
        var bits = 0
        var v = 0
        val sb = StringBuilder()
        for (x in data) {
            v = (v shl 8) or (x.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(A32[(v ushr (bits - 5)) and 31])
                bits -= 5
            }
            v = v and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(A32[(v shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** Eingabe normalisieren: Grossbuchstaben, Leerzeichen/"-" weg, 0->O, 1->I, 8->B; null = ungueltig */
    fun normRecovery(s: String): String? {
        val r = s.uppercase().filterNot { it.isWhitespace() || it == '-' }.replace('0', 'O').replace('1', 'I').replace('8', 'B')
        return r.takeIf { it.length == 32 && it.all { c -> c in A32 } }
    }

    /** Anzeige 8 x 4 mit "-" */
    fun formatRecovery(s: String): String = (normRecovery(s) ?: s).chunked(4).joinToString("-")

    /** n Bloecke a 5 Bytes -> BE-Zahl mod 100000 -> "01234 56789 ..." */
    fun digitBlocks(h: ByteArray, blocks: Int): String = (0 until blocks).joinToString(" ") { i ->
        var v = 0L
        for (j in 0 until 5) v = v * 256 + (h[i * 5 + j].toLong() and 0xff)
        (v % 100000).toString().padStart(5, '0')
    }

    /** UTF-8-Bytes vergleichen (Reihenfolge der Sicherheitsnummer, uidA < uidB) */
    fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Standard-Base64 mit Padding (RFC 4648 §4) - java.util.Base64 (auch auf Android ab API 26) */
    fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)

    /** null bei ungueltigem Base64 oder falscher Laenge */
    fun unb64(s: String?, n: Int? = null): ByteArray? {
        if (s == null) return null
        val r = try {
            java.util.Base64.getDecoder().decode(s)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (n != null && r.size != n) null else r
    }

    /** Seitenverweise [[note:<id>]] im Text */
    val NOTE_REF = Regex("\\[\\[note:(\\d{1,18})]]")

    fun noteRefs(text: String): List<Long> = NOTE_REF.findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }.distinct().toList()

    /** QR-Inhalt der Sicherheitsnummer */
    fun safetyQr(number: String): String = "saga-safety:1:" + number.filter { it.isDigit() }

    // ---------------------------------------------------------------- Anzeige (auf allen Plattformen gleich)

    private fun groups(s: String) = s.split(' ', '\n').filter { it.isNotEmpty() }

    /** Sicherheitsnummer (12 Gruppen a 5 Ziffern): 3 Zeilen x 4 Gruppen - Menschen vergleichen zeilenweise */
    fun safetyRows(number: String): List<List<String>> = groups(number).chunked(4)

    /** Kopplungs-Code (4 Gruppen a 5 Ziffern): eine Zeile; nur wenn sie nicht passt 2 Zeilen a 2 Gruppen */
    fun linkCodeRows(code: String, oneLine: Boolean): List<List<String>> = groups(code).chunked(if (oneLine) 4 else 2)

    /** Wiederherstellungsschluessel (8 Gruppen a 4 Zeichen): 2 Zeilen a 4 Gruppen, nie mitten in einer Gruppe umbrechen */
    fun recoveryRows(key: String): List<String> = formatRecovery(key).split('-').chunked(4).map { it.joinToString("-") }

    /** Grenzen fuer das Oeffnen des Schluessel-Buendels (Argon2id13): mehr wuerde schwache Geraete lahmlegen */
    const val BUNDLE_OPS_MAX = 10L
    const val BUNDLE_MEM_MAX = 256L shl 20
    const val BUNDLE_MEM_MIN = 8L shl 20

    fun bundleKdfOk(ops: Long, mem: Long): Boolean = ops in 1..BUNDLE_OPS_MAX && mem in BUNDLE_MEM_MIN..BUNDLE_MEM_MAX

    /** Kopf des Buendels pruefen ({"v":1,"kdf":"argon2id13","ops","mem",…}) -> (ops, mem); sonst BundleFormatException */
    fun bundleKdf(bundle: String): Pair<Long, Long> {
        val d = try {
            kotlinx.serialization.json.Json.parseToJsonElement(bundle) as? kotlinx.serialization.json.JsonObject
        } catch (_: Exception) {
            null
        } ?: throw BundleFormatException()
        fun prim(k: String) = d[k] as? kotlinx.serialization.json.JsonPrimitive
        if (prim("v")?.content != "1" || prim("kdf")?.content != "argon2id13") throw BundleFormatException()
        val ops = prim("ops")?.content?.toLongOrNull() ?: throw BundleFormatException()
        val mem = prim("mem")?.content?.toLongOrNull() ?: throw BundleFormatException()
        if (!bundleKdfOk(ops, mem)) throw BundleFormatException()
        return ops to mem
    }
}

/** Menschlicher Geraetename fuer neue Registrierungen (Chats, Mitteilungen, Anmeldung) */
object DeviceNames {
    private val TECH = Regex("^[a-z0-9]+(_[a-z0-9]+)+$")

    /** [setName] = Settings.Global.DEVICE_NAME (kann "sdk_gphone64_arm64" o. ae. sein) */
    fun pretty(setName: String?, manufacturer: String, model: String): String {
        val n = setName?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (n.isNotEmpty() && !TECH.matches(n) && !n.equals(model, true)) return n.take(60)
        val m = model.trim()
        if (m.startsWith("sdk_") || m.contains("emulator", true) || m.startsWith("google_sdk")) return "Android Emulator"
        val maker = manufacturer.trim().replaceFirstChar { it.titlecase() }
        val nice = m.replace('_', ' ')
        val full = if (maker.isEmpty() || nice.startsWith(maker, true)) nice else "$maker $nice"
        return full.replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "Android" }
    }
}
