package io.github.yggdrasil_sh.saga.call

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 2.5 Geraete-Uebergabe hin und her (Server-Feature `handover2`, APP-API "Geraete-Uebergabe hin und her"): reine Regeln
 * ohne WebRTC (Unit-Test HandoverTest), gleich wie static/calls.js (leaveBody, hoCheck, takeOver, hornModIn).
 * - bedingtes /leave mit `by`/`gen` (Rueckfall des neuen Geraets, Abgabe des alten) -> `{ok, stale}` statt Rauswurf
 * - Rueckfall nach `state.ready`: ueber Knoten 3 s, im Mesh (nur mit handover2) nach `handover_s`/2
 * - `handover_busy`, Wiedereintritt (join role handover eines Geraets, das noch in der Liste steht), `takeover` (voll)
 * - `call.mod {action: mute, why: handover}` -> still als Begleitgeraet weiter
 * - Begleitgeraet ueber Knoten: promote -> Ton-Bein neu -> ready; scheitert es: promote zuruecknehmen
 * Ohne `handover2` bleibt alles wie bisher (unbedingtes /leave, kein Mesh-Rueckfall, kein promote).
 */
object Handover {
    const val FEATURE = "handover2"
    const val BUSY = "handover_busy"
    const val WHY = "handover"
    const val REASON = "handover"
    /** Rueckfall ueber einen Knoten (keine Direktverbindung zum alten Geraet) */
    const val HORN_FALLBACK_MS = 3000L
    /** Ton-Bein nach promote: so lange auf `connected` warten */
    const val REAUDIO_MS = 8000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun on(features: Set<String>): Boolean = FEATURE in features

    /** Beitrittsgeneration eines Endpunkts aus der Teilnehmerliste (> 0), sonst null */
    fun genOf(legs: List<CallLeg>, device: String): Long? = legs.firstOrNull { it.device == device }?.gen?.takeIf { it > 0 }

    /**
     * Body fuer POST /api/calls/{id}/leave. Mit handover2 bedingt: `gen` = Generation des Beins, das gehen soll (fehlt sie
     * in der Liste, ohne), `by` = eigenes Geraet, das uebernommen hat. Ohne handover2 wie bisher (unbedingt).
     */
    fun leaveBody(device: String, reason: String? = null, by: String? = null, gen: Long? = null, v2: Boolean): JsonObject = buildJsonObject {
        put("device", device)
        if (reason != null) put("reason", reason)
        if (v2 && by != null && by.isNotEmpty() && by != device) put("by", by)
        if (v2 && gen != null && gen > 0) put("gen", gen)
    }

    enum class Left { OK, STALE, ALREADY }

    /** Antwort auf /leave: `stale` = galt nicht mehr (Geraet inzwischen wieder beigetreten bzw. `by` nicht mehr aktiv) */
    fun leaveResult(text: String?): Left {
        val o = try {
            json.parseToJsonElement(text.orEmpty()) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Left.OK
        fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull == true
        return when {
            b("stale") -> Left.STALE
            b("already") -> Left.ALREADY
            else -> Left.OK
        }
    }

    /**
     * Rueckfall des neuen Geraets nach `state.ready` (falls das alte die Nachricht nicht bekommt): ueber einen Knoten nach
     * 3 s (wie bisher), im Mesh nur mit handover2 nach `handover_s`/2; ohne handover2 im Mesh keiner (altes Verhalten).
     */
    fun fallbackMs(horn: Boolean, v2: Boolean, handoverS: Int): Long? = when {
        horn -> HORN_FALLBACK_MS
        v2 -> handoverS.coerceIn(5, 60) * 500L
        else -> null
    }

    /**
     * Dieses Geraet fehlt in der neuen Teilnehmerliste: fuehrt jetzt ein anderes eigenes Geraet (Rolle != companion), das
     * neu ist bzw. vorher Begleitgeraet oder Uebernahme (`handover`) war? Dann wurde dieses Geraet abgeloest. Ein
     * Begleitgeraet, das Begleitgeraet bleibt, bzw. ein Hauptgeraet, das Hauptgeraet bleibt, heisst nur: kurz aus der
     * Liste gefallen (Bughunt R09).
     */
    fun leader(before: List<CallLeg>, now: List<CallLeg>, me: String, myDevice: String): CallLeg? = now.firstOrNull { x ->
        x.who == me && x.device != myDevice && x.role != CallRoom.ROLE_COMPANION &&
            before.firstOrNull { it.device == x.device }.let { it == null || it.role == CallRoom.ROLE_COMPANION || it.role == CallRoom.ROLE_HANDOVER }
    }

    /** Beitritt trotz `joinable: false`: "Hierher uebernehmen" bei vollem Anruf (`takeover`, E6) - Hinzufuegen nicht */
    fun joinAllowed(joinable: Boolean, takeover: Boolean, role: String): Boolean =
        joinable || (role == CallRoom.ROLE_HANDOVER && takeover)

    /** call.mod an dieses Geraet: ein anderes eigenes Geraet fuehrt (liegengebliebene Uebernahme) -> still als Begleitgeraet */
    fun companionMute(action: String?, device: String?, why: String?, myDevice: String): Boolean =
        action == "mute" && device == myDevice && why == WHY

    /** Begleitgeraet -> Hauptgeraet: ueber einen Knoten mit handover2 erst promote (Ton-Recht), im Mesh nicht noetig */
    fun usePromote(horn: Boolean, v2: Boolean): Boolean = horn && v2

    /** Ton-Bein, das nach promote neu aufgebaut wird: bei UDP `both`, sonst `audio` */
    fun audioRole(roles: Collection<String>): String? = when {
        "both" in roles -> "both"
        "audio" in roles -> "audio"
        else -> null
    }

    /**
     * Ablauf am Begleitgeraet ueber Knoten ("Hierher uebernehmen" im Anruf): promote -> Ton-Bein neu -> ready. Merkt, ob
     * der Server wirklich befoerdert hat (`already` = war schon Hauptgeraet), damit ein Fehler genau das zuruecknimmt.
     */
    class Promote(val rep: String) {
        /** promote laeuft bzw. Ton-Bein wird ersetzt: hoCheck wartet */
        var busy = true
            private set
        var promoted = false
            private set
        var done = false
            private set

        /** Antwort auf promote */
        fun promotedNow(already: Boolean) {
            promoted = !already
        }

        /** Ton-Bein steht: jetzt darf hoCheck `ready` schicken */
        fun ready() {
            busy = false
        }

        /** Fehler bzw. `handover_s` abgelaufen: true = `promote {device: rep}` schicken (Befoerderung zuruecknehmen) */
        fun undo(): Boolean {
            if (done) return false
            done = true
            busy = false
            val p = promoted
            promoted = false
            return p
        }

        /** Uebernahme fertig (ready geschickt): nichts mehr zuruecknehmen */
        fun finish() {
            done = true
            busy = false
        }
    }
}
