package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Grenze der Chat-Geraete erreicht (2.5): hoechstens [max], schon [count] */
data class DeviceLimit(val max: Int, val count: Int)

/** Koppeln/Wiederherstellen/Bestaetigen scheiterte an der Geraetegrenze - die Oberflaeche zeigt Meldung und Weg zu "Chats & Geraete" */
class DeviceLimitException(val limit: DeviceLimit) : UiTextException(ChatDeviceLimit.text(limit))

/**
 * 2.5 (docs/APP-API-CHAT.md Abschnitt 10): Grenze der Chat-Geraete beim Koppeln. Der Server meldet sie als
 * 409 {error, code: "device_limit", max, count} (Anfrage, Wiederherstellung, Bestaetigung), beim Abfragen der Anfrage als
 * {state: "failed", code, max, count, error} und in GET /api/chat/me als limits.devices_count. Aeltere Server: nichts davon -
 * dann bleibt alles wie bisher. Rein (Tests).
 */
object ChatDeviceLimit {
    const val CODE = "device_limit"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Meldung in der App-Sprache ("Du hast schon {max} Chat-Geraete …") */
    fun text(l: DeviceLimit): UiText = UiText.Plural(R.plurals.r5_chat_device_limit, l.max)

    /** Antwort-Inhalt eines Fehlers -> Grenze, wenn code "device_limit" */
    fun parse(body: String?): DeviceLimit? {
        if (body.isNullOrBlank()) return null
        val o = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        if ((o["code"] as? JsonPrimitive)?.contentOrNull != CODE) return null
        val max = (o["max"] as? JsonPrimitive)?.intOrNull ?: return null
        val count = (o["count"] as? JsonPrimitive)?.intOrNull ?: max
        return DeviceLimit(max, count)
    }

    /** Fehler einer Anfrage -> Grenze (409 mit code "device_limit") oder null */
    fun of(e: Throwable): DeviceLimit? = when (e) {
        is DeviceLimitException -> e.limit
        is ApiException -> if (e.code == 409) parse(e.body) else null
        else -> null
    }

    /** Schon an der Grenze (vor dem Koppeln)? Nur mit limits.devices_count (aeltere Server: null) */
    fun atLimit(l: ChatLimits?): DeviceLimit? {
        val n = l?.devicesCount ?: return null
        return if (l.devicesMax > 0 && n >= l.devicesMax) DeviceLimit(l.devicesMax, n) else null
    }

    /** Zustand einer Kopplungsanfrage beim neuen Geraet */
    sealed interface Poll {
        data object Pending : Poll
        data object Approved : Poll
        data object Expired : Poll
        data object Gone : Poll
        data class Limit(val limit: DeviceLimit) : Poll
        /** gescheitert aus anderem Grund bzw. unbekannter Zustand (nicht weiter warten) */
        data object Failed : Poll
    }

    fun poll(s: LinkState, fallbackMax: Int = 10): Poll = when (s.state) {
        "pending" -> Poll.Pending
        "approved" -> Poll.Approved
        "expired" -> Poll.Expired
        "gone" -> Poll.Gone
        "failed" -> if (s.code == CODE) {
            val max = s.max ?: fallbackMax
            Poll.Limit(DeviceLimit(max, s.count ?: max))
        } else {
            Poll.Failed
        }
        else -> Poll.Failed
    }

    /** Ein Fehler beim Anlegen/Bestaetigen als Grenze weiterreichen (sonst unveraendert) */
    inline fun <T> mapLimit(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw of(e)?.let { DeviceLimitException(it) } ?: e
    }
}
