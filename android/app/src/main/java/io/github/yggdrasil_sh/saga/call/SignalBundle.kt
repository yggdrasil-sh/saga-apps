package io.github.yggdrasil_sh.saga.call

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 2.5 (Fund 9.11.) Raum-Signal-Buendel (POST /api/calls/{id}/signal mit Zielliste): neuere Server verwerfen veraltete
 * Ziele einzeln ({ok, n, dropped}) - dann Teilnehmerliste neu holen (hoechstens alle 2 s); aeltere lehnen das ganze
 * Buendel mit 400 ab - fkey/state dann nach der naechsten Liste an die verbliebenen Ziele erneut senden
 * (APP-API "Uebergabe"). Rein, ohne Android (Unit-Tests).
 */
object SignalBundle {
    const val PULL_MS = 2000L

    /** Nachsende-Runden nach 400 je Aenderung der Liste - lehnt der Server ein Ziel der Liste dauerhaft ab: Abbruch */
    const val RETRY_MAX = 3

    sealed class Reply {
        data class Ok(val dropped: List<String>) : Reply()
        object Rejected : Reply()
        object Gone : Reply()
        object Other : Reply()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun obj(s: String?): JsonObject? = try {
        s?.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it) as? JsonObject }
    } catch (_: Exception) {
        null
    }

    /** Antwort auf ein Buendel: code null = Netzfehler */
    fun reply(code: Int?, body: String?): Reply = when {
        code == null -> Reply.Other
        code in 200..299 -> Reply.Ok(
            (obj(body)?.get("dropped") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { d -> d.isNotEmpty() } },
        )
        code == 400 -> Reply.Rejected
        code == 409 -> Reply.Gone
        else -> Reply.Other
    }

    /** Art eines Signal-Klartexts ("t"); null = kein JSON */
    fun type(body: String): String? = (obj(body)?.get("t") as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Nach einem 400 erneut senden? Nur Schluessel und Zustand */
    fun resendable(bodies: Map<String, String>): Map<String, String> = bodies.filterValues { type(it) == "fkey" || type(it) == "state" }

    /** Auf die aktuelle Liste beschraenken (gegangene Geraete fallen weg, nie an sich selbst) */
    fun targets(bodies: Map<String, String>, live: Set<String>, here: String): Map<String, String> =
        bodies.filterKeys { it in live && it != here }

    /** Liste neu holen erlaubt? (last = letzter Abruf, 0 = noch nie) */
    fun pullDue(last: Long, now: Long): Boolean = last == 0L || now - last >= PULL_MS
}
