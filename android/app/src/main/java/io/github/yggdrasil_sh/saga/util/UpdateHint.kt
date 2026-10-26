package io.github.yggdrasil_sh.saga.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI

/**
 * Update-Hinweis in Einstellungen -> Info (alle Apps gleich, wie ios/App/UpdateHintLogic.swift) - reine Logik ohne
 * Netz/Android. Quelle: https://raw.githubusercontent.com/<repo>/appcast/<kanal>.json (CI, Zweig appcast,
 * tools/update-info.py). Verglichen wird nur im eigenen Kanal: x.y.z-rc.N -> testing.json, sonst stable.json.
 * Entwickler-Labels (kein x.y.z(-rc.N)?, z. B. "-debug") -> keine Pruefung. Tests: UpdateHintTest.
 */
object UpdateHint {
    const val REPO = "yggdrasil-sh/saga-apps"
    /** Automatische Pruefung beim App-Start hoechstens so oft */
    const val START_INTERVAL_MS = 12 * 3600 * 1000L
    /** Beim Oeffnen von Info nicht oefter als so (sonst jedes Antippen eine Anfrage) */
    const val INFO_INTERVAL_MS = 15 * 60 * 1000L
    /** Groesste angenommene Antwort (die Datei hat wenige hundert Byte) */
    const val MAX_BYTES = 64 * 1024
    const val TIMEOUT_MS = 10_000L

    private val VERSION_RE = Regex("^([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})(?:-rc\\.([0-9]{1,9}))?$")
    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------ Versionen

    data class Version(val major: Int, val minor: Int, val patch: Int, val rc: Int?) : Comparable<Version> {
        val prerelease: Boolean get() = rc != null

        /** x.y.z numerisch; gleiche x.y.z: Release > jede -rc.N; -rc.N numerisch */
        override fun compareTo(other: Version): Int {
            compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
            compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
            compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
            return when {
                rc == null && other.rc == null -> 0
                rc == null -> 1
                other.rc == null -> -1
                else -> rc.compareTo(other.rc)
            }
        }

        companion object {
            fun parse(s: String?): Version? {
                val m = VERSION_RE.matchEntire(s?.trim() ?: return null) ?: return null
                val g = m.groupValues
                return Version(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].takeIf { it.isNotEmpty() }?.toInt())
            }
        }
    }

    /** Kanal der eigenen Version ("testing" / "stable"); null: Entwickler-Label -> keine Pruefung */
    fun channel(label: String?): String? = Version.parse(label)?.let { if (it.prerelease) "testing" else "stable" }

    /** Neuer als die eigene Version (beide gueltig, sonst false) */
    fun isNewer(remote: String?, local: String?): Boolean {
        val r = Version.parse(remote) ?: return false
        val l = Version.parse(local) ?: return false
        return l < r
    }

    fun feedUrl(label: String?, repo: String = REPO): String? =
        channel(label)?.let { "https://raw.githubusercontent.com/$repo/appcast/$it.json" }

    // ------------------------------------------------------------------ Datei (format 1)

    data class Release(val version: String, val page: String?, val assets: Map<String, String>)

    /** Nur https-Links auf github.com (keine Benutzerangaben, kein anderer Port) */
    fun safeLink(v: Any?): String? {
        val s = (v as? String)?.trim() ?: return null
        if (s.isEmpty() || s.length >= 2048) return null
        val u = try {
            URI(s)
        } catch (_: Exception) {
            return null
        }
        if (u.scheme?.lowercase() != "https" || u.host?.lowercase() != "github.com") return null
        if (u.rawUserInfo != null || u.port != -1) return null
        return s
    }

    private fun str(o: JsonObject?, k: String): String? = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** Datei lesen: format 1, passender Kanal, gueltige Version im selben Kanal; unsichere Links fallen weg */
    fun parse(bytes: ByteArray, expected: String): Release? {
        if (bytes.size > MAX_BYTES) return null
        val o = try {
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val format = (o["format"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (format != 1) return null
        val v = str(o, "version")?.trim() ?: return null
        if (channel(v) != expected) return null
        val ch = str(o, "channel")
        if (ch != null && ch != expected) return null
        val assets = LinkedHashMap<String, String>()
        (o["assets"] as? JsonObject)?.forEach { (k, x) -> safeLink((x as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)?.let { assets[k] = it } }
        return Release(v, safeLink(str(o, "page")), assets)
    }

    // ------------------------------------------------------------------ Schaltflaeche, Status

    /** Was "Update" auf Android oeffnet: die APK, sonst die Release-Seite; null = nur Hinweis */
    fun action(r: Release): String? = r.assets["android"] ?: r.page

    enum class Button { CHECK, CHECKING, UPDATE }

    /** Eine Schaltflaeche: "Nach Updates suchen" / "Pruefe …" / "Update" (nur wenn es einen Link gibt) */
    fun button(checking: Boolean, available: Release?): Button = when {
        checking -> Button.CHECKING
        available != null && action(available) != null -> Button.UPDATE
        else -> Button.CHECK
    }

    sealed interface Status {
        data object None : Status
        data class Available(val version: String) : Status
        data object UpToDate : Status
        data object Failed : Status
    }

    /** Statuszeile ueber der Schaltflaeche; "fehlgeschlagen" nur nach manueller Pruefung (nur sie setzt failed) */
    fun status(available: Release?, failed: Boolean, checked: Long?): Status = when {
        available != null -> Status.Available(available.version)
        failed -> Status.Failed
        checked == null -> Status.None
        else -> Status.UpToDate
    }

    /** Bekanntes Update aus dem Zwischenspeicher: nur neuer und nur im eigenen Kanal */
    fun available(r: Release?, label: String?): Release? {
        if (r == null) return null
        val ch = channel(label) ?: return null
        return r.takeIf { channel(it.version) == ch && isNewer(it.version, label) }
    }

    /** Faellig: nie geprueft, Uhr zurueckgestellt oder aelter als das Intervall */
    fun due(last: Long?, now: Long, interval: Long): Boolean {
        if (last == null) return true
        val age = now - last
        return age < 0 || age >= interval
    }

    // ------------------------------------------------------------------ Zwischenspeicher (SharedPreferences, Text)

    data class Cache(val checked: Long, val release: Release?)

    fun encode(c: Cache): String = buildJsonObject {
        put("checked", c.checked)
        c.release?.let { r ->
            put("release", buildJsonObject {
                put("version", r.version)
                r.page?.let { put("page", it) }
                put("assets", buildJsonObject { r.assets.forEach { (k, v) -> put(k, v) } })
            })
        }
    }.toString()

    fun decode(s: String?): Cache? {
        val o = try {
            json.parseToJsonElement(s ?: return null) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val checked = (o["checked"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val r = o["release"] as? JsonObject
        val release = r?.let { x ->
            val v = str(x, "version")?.takeIf { Version.parse(it) != null } ?: return@let null
            val assets = LinkedHashMap<String, String>()
            (x["assets"] as? JsonObject)?.forEach { (k, e) -> safeLink((e as? JsonPrimitive)?.contentOrNull)?.let { assets[k] = it } }
            Release(v, safeLink(str(x, "page")), assets)
        }
        return Cache(checked, release)
    }
}
