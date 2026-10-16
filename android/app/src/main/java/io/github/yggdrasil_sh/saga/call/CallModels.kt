package io.github.yggdrasil_sh.saga.call

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// Antwortformen von saga/calls.py (Server 2.1, Format saga-call/v1)

@Serializable
data class IceServerInfo(
    /** Server liefert eine Liste; eine einzelne Adresse wird ebenfalls angenommen */
    val urls: JsonElement? = null,
    val username: String? = null,
    val credential: String? = null,
) {
    val urlList: List<String>
        get() = when (val u = urls) {
            is JsonArray -> u.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> listOf(u.content)
            else -> emptyList()
        }
}

/** GET /api/calls/config - vor jedem Anruf neu holen (TURN-Zugangsdaten laufen ab) */
@Serializable
data class CallConfig(
    val enabled: Boolean = false,
    @SerialName("ice_servers") val iceServers: List<IceServerInfo> = emptyList(),
    @SerialName("relay_only") val relayOnly: Boolean = false,
    val turn: JsonElement? = null,
    @SerialName("ring_s") val ringS: Int = 45,
    @SerialName("alive_s") val aliveS: Int = 15,
    @SerialName("max_s") val maxS: Int = 0,
    val format: String = "",
) {
    /** TURN eingetragen? (Server: true/false bzw. Adresse) */
    val hasTurn: Boolean
        get() = when (val t = turn) {
            is JsonPrimitive -> t.booleanOrNull ?: t.content.isNotBlank()
            null -> false
            else -> true
        }
}

@Serializable
data class CallPeer(val username: String = "", val name: String = "", val avatar: String? = null)

/** Anruf aus Sicht des Abfragenden (calls._dict) */
@Serializable
data class CallInfo(
    val id: String = "",
    @SerialName("chat_id") val chatId: Long = 0,
    val media: String = "audio",
    val state: String = "",
    val reason: String? = null,
    val role: String = "",
    val peer: CallPeer = CallPeer(),
    @SerialName("caller_device") val callerDevice: String? = null,
    @SerialName("callee_device") val calleeDevice: String? = null,
    @SerialName("ring_s") val ringS: Int? = null,
    @SerialName("expires_in") val expiresIn: Double? = null,
)

@Serializable
data class CallSignalDto(
    val seq: Long = 0,
    @SerialName("from_device") val fromDevice: String = "",
    @SerialName("to_device") val toDevice: String = "",
    val sealed: String = "",
)

@Serializable
data class CallState(val call: CallInfo = CallInfo(), val signals: List<CallSignalDto> = emptyList())

@Serializable
data class CallWrap(val ok: Boolean = false, val call: CallInfo? = null)

@Serializable
data class CallsActive(val calls: List<CallInfo> = emptyList())

@Serializable
data class CallAlive(val state: String = "", val reason: String? = null)

/** Eintrag im Chat (kind "call", body_ct = Klartext-JSON mit Metadaten, die der Server ohnehin kennt) */
@Serializable
data class CallEntry(
    val v: Int = 1,
    val type: String = "call",
    val call: String = "",
    val media: String = "audio",
    val status: String = "",
    val dur: Int? = null,
    val from: String = "",
    val to: String = "",
)

/** Reine Regeln fuer Anrufe (Unit-Tests) */
object CallLogic {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private val ID_RE = Regex("^[0-9a-f]{32}$")

    fun validId(id: String?): Boolean = id != null && ID_RE.matches(id)

    fun parseEntry(bodyCt: String): CallEntry? = try {
        json.decodeFromString(CallEntry.serializer(), bodyCt).takeIf { it.type == "call" }
    } catch (_: Exception) {
        null
    }

    /** Dauer "m:ss" bzw. "h:mm:ss" */
    fun duration(sec: Long): String {
        val s = maxOf(0, sec)
        val h = s / 3600
        val m = s % 3600 / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
    }

    /** Text eines Chat-Eintrags (wie static/chat.js callText): me = eigenes Konto */
    fun entryText(e: CallEntry, me: String): UiText {
        val out = e.from == me
        val video = e.media == "video"
        val what = UiText.Res(if (video) R.string.call_video else R.string.call_entry_audio)
        return when (e.status) {
            "ended" -> UiText.Res(R.string.call_entry_ended, what, duration((e.dur ?: 0).toLong()))
            "declined" -> UiText.Res(R.string.call_entry_declined, what)
            "busy" -> if (out) UiText.Res(R.string.call_entry_busy) else UiText.Res(R.string.call_entry_busy_missed)
            "failed" -> UiText.Res(R.string.call_entry_failed, what)
            else -> if (out) UiText.Res(R.string.call_entry_not_answered, what)
            else UiText.Res(if (video) R.string.call_entry_missed_video else R.string.call_entry_missed)
        }
    }

    /** Eingehend verpasst (rot, "Zurückrufen") */
    fun missed(e: CallEntry, me: String): Boolean = e.from != me && (e.status == "missed" || e.status == "busy")

    /** Meldung, wenn das Gegenueber/der Server den Anruf beendet (wie calls.js remoteEnded) */
    fun endText(reason: String?, caller: Boolean, peer: String, connectedFor: Long?): UiText = when (reason) {
        "declined" -> if (caller) UiText.Res(R.string.call_declined_by, peer) else UiText.Res(R.string.call_declined)
        "busy" -> UiText.Res(R.string.call_busy_peer, peer)
        "timeout" -> if (caller) UiText.Res(R.string.call_no_answer, peer) else UiText.Res(R.string.call_missed)
        "cancelled" -> UiText.Res(R.string.call_hung_up_by, peer)
        "lost" -> UiText.Res(R.string.call_lost)
        "failed" -> UiText.Res(R.string.call_failed)
        else -> if (connectedFor != null) UiText.Res(R.string.call_ended_dur, duration(connectedFor)) else UiText.Res(R.string.call_ended)
    }

    /** Verbindungsqualitaet aus Verlust (Anteil) und RTT (s): "good" | "mid" | "bad" | "" */
    fun quality(loss: Double, rtt: Double?, received: Long): String = when {
        rtt == null && received <= 0 -> ""
        loss > 0.08 || (rtt ?: 0.0) > 0.6 -> "bad"
        loss > 0.02 || (rtt ?: 0.0) > 0.3 -> "mid"
        else -> "good"
    }

    /**
     * Ziele fuer ein Signal: vor dem Annehmen alle aktiven Geraete des Gegenuebers, danach nur das annehmende
     * (Anrufer) bzw. das anrufende Geraet (Angerufene).
     */
    fun targets(peerDevice: String?, peerDevices: Collection<String>): List<String> =
        if (peerDevice != null) listOf(peerDevice) else peerDevices.toList()

    /** ICE-Kandidaten gebuendelt: hoechstens 40 je Signal */
    fun <T> iceBatches(list: List<T>, max: Int = 40): List<List<T>> = list.chunked(max)
}
