package io.github.yggdrasil_sh.saga.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * fix27 E2 - eigene Startseite (Kontoeinstellung start_page, GET/PATCH /api/account). Fehlt das Feld (aelterer Server),
 * gibt es keinen Menue-Eintrag und keinen Sprung beim Start. Foederierte (negative) IDs gibt es in Etappe 1 nicht.
 */
object StartPage {
    data class Info(val supported: Boolean, val id: Long?)

    fun parse(account: String): Info = try {
        val o = Json.parseToJsonElement(account) as? JsonObject
        if (o == null || !o.containsKey("start_page")) Info(false, null)
        else Info(true, (o["start_page"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 })
    } catch (_: Exception) {
        Info(false, null)
    }

    fun body(id: Long?): JsonObject = buildJsonObject { if (id != null) put("start_page", id) else put("start_page", JsonNull) }

    /** Kann diese Seite Startseite werden? (eigene oder geteilte Seite dieses Servers, keine fremde Instanz) */
    fun eligible(pageId: Long): Boolean = pageId > 0

    /**
     * Beim Kaltstart oeffnen? Nur einmal je Start (nicht nach Wiederherstellung), nur wenn noch nichts anderes geoeffnet
     * wurde (Benachrichtigung, Link, Teilen) - der Stapel steht dann noch auf "Heute".
     */
    fun openOnColdStart(id: Long?, alreadyChecked: Boolean, onlyHome: Boolean): Boolean =
        id != null && id > 0 && !alreadyChecked && onlyHome
}

/**
 * fix27 E4 - Eintrag eben aus einer Eintrags-Vorlage angelegt: Server antwortet beim Duplizieren mit `fresh`
 * (Schluessel) und `placeholder` (Vorlagenname). Titel bleibt leer, die App zeigt den Vorlagennamen als Platzhalter.
 * Wird der Eintrag ohne jede Aenderung geschlossen, verwirft die App ihn (POST /api/notes/{id}/discard {"fresh"}) -
 * endgueltig, der Server prueft selbst noch einmal, ob er unveraendert ist (sonst 409, Eintrag bleibt).
 */
data class FreshEntry(val id: Long, val token: String, val placeholder: String)

object FreshEntries {
    fun parse(response: String): FreshEntry? = try {
        val o = Json.parseToJsonElement(response) as? JsonObject
        val id = (o?.get("id") as? JsonPrimitive)?.longOrNull
        val tok = (o?.get("fresh") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (id == null || tok.isNullOrEmpty()) null
        else FreshEntry(id, tok, (o["placeholder"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    } catch (_: Exception) {
        null
    }

    fun discardBody(f: FreshEntry): JsonObject = buildJsonObject { put("fresh", f.token) }

    /**
     * Beim Schliessen verwerfen? Nur mit Schluessel, Titel leer, keine Eingabe im Editor, nichts ungespeichert und die
     * Seite liegt nicht mehr im Navigationsstapel (nur ueberdeckt = noch offen).
     */
    fun shouldDiscard(fresh: FreshEntry?, title: String, edited: Boolean, dirty: Boolean, stillOpen: Boolean): Boolean =
        fresh != null && title.isBlank() && !edited && !dirty && !stillOpen
}
