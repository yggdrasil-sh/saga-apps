package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Saga 2.5 BookOrbit (`bookorbit1`, Phase 1 nur lesen): reine Logik ohne Compose (Tests: BookOrbitTest).
 * Widget "Lesen" (`kind: "reading"`, data aus GET /api/notes/{nid}/widgets), Karte "Lesen" unter Heute
 * (GET /api/bookorbit/reading) und Einstellungen (GET /api/bookorbit/status). Die App spricht nie selbst mit
 * BookOrbit - auch die Cover kommen ueber den Saga-Server (Coil mit der App-Sitzung).
 */
object BookOrbit {
    const val FEATURE = "bookorbit1"
    /** Heute: hoechstens so viele Buecher (wie im Web) */
    const val TODAY_LIMIT = 3

    enum class State { OK, NONE, EXPIRED, UNAVAILABLE }
    enum class Show { CURRENT, GOAL, STREAK }

    data class Book(val id: Long, val title: String, val authors: List<String>, val progress: Double, val cover: String?, val format: String?) {
        val percent: Int get() = Math.round(progress).toInt()
        /** hoechstens zwei Namen, wie im Web */
        val authorLine: String get() = authors.take(2).joinToString(", ")
    }

    /** goal = null: in BookOrbit kein Leseziel gesetzt */
    data class Goal(val goal: Int?, val done: Int, val year: Int?) {
        val fraction: Float? get() = goal?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
    }

    /** last7: aeltester Tag zuerst */
    data class Streak(val current: Int, val longest: Int, val last7: List<Boolean>) {
        val daysRead: Int get() = last7.count { it }
    }

    data class Reading(
        val state: State, val show: Show = Show.CURRENT, val stale: Boolean = false, val name: String? = null,
        val books: List<Book> = emptyList(), val more: Int = 0, val goal: Goal? = null, val streak: Streak? = null,
    )

    private fun str(o: JsonObject?, k: String): String? = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun int(e: JsonElement?): Int? = num(e)?.takeIf { kotlin.math.abs(it) < 1e9 }?.toInt()
    private fun bool(o: JsonObject?, k: String): Boolean = (o?.get(k) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

    fun state(s: String?): State = when (s) {
        "ok" -> State.OK
        "none" -> State.NONE
        "expired" -> State.EXPIRED
        else -> State.UNAVAILABLE                     // unbekannt: freundlicher Hinweis statt leerer Karte
    }

    /** Widget-`data` bzw. Antwort von GET /api/bookorbit/reading; null: keine Daten (Fehler-Widget) */
    fun reading(d: JsonObject?): Reading? {
        d ?: return null
        val show = when (str(d, "show")) { "goal" -> Show.GOAL; "streak" -> Show.STREAK; else -> Show.CURRENT }
        val books = (d["books"] as? JsonArray).orEmpty().mapNotNull { book(it as? JsonObject) }
        val goal = (d["goal"] as? JsonObject)?.let { g -> Goal(int(g["goal"])?.takeIf { it > 0 }, maxOf(0, int(g["done"]) ?: 0), int(g["year"])) }
        val streak = (d["streak"] as? JsonObject)?.let { s ->
            Streak(maxOf(0, int(s["current"]) ?: 0), maxOf(0, int(s["longest"]) ?: 0),
                (s["last7"] as? JsonArray).orEmpty().take(7).map { (it as? JsonPrimitive)?.booleanOrNull == true })
        }
        return Reading(state(str(d, "state")), show, bool(d, "stale"), str(d, "name")?.ifEmpty { null }, books,
            maxOf(0, int(d["more"]) ?: 0), goal, streak)
    }

    fun parseReading(text: String): Reading? = try {
        reading(Json.parseToJsonElement(text) as? JsonObject)
    } catch (_: Exception) {
        null
    }

    fun book(x: JsonObject?): Book? {
        x ?: return null
        val id = num(x["id"])?.toLong() ?: return null
        val p = num(x["progress"]) ?: 0.0
        return Book(
            id, str(x, "title").orEmpty().trim(),
            (x["authors"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p0 -> p0.isString }?.content?.ifEmpty { null } },
            p.coerceIn(0.0, 100.0), coverPath(str(x, "cover")), str(x, "format"),
        )
    }

    private val COVER = Regex("""/api/bookorbit/cover/[0-9]{1,18}""")

    /** Cover nur ueber den Saga-Server und nur genau diese Form (keine fremden Adressen) */
    fun coverPath(s: String?): String? = s?.takeIf { COVER.matches(it) }

    /** Was die Karte zeigt */
    sealed interface Content {
        data class Hint(val state: State) : Content
        data class Books(val books: List<Book>, val more: Int) : Content
        data object NoBooks : Content
        data class GoalC(val goal: Goal) : Content
        data object NoGoal : Content
        data class StreakC(val streak: Streak) : Content
        data object NoStreak : Content
    }

    /** limit: hoechstens so viele Buecher (Heute); der Rest zaehlt zu "und n weitere" */
    fun content(r: Reading, limit: Int? = null): Content {
        if (r.state != State.OK) return Content.Hint(r.state)
        return when (r.show) {
            Show.CURRENT -> {
                if (r.books.isEmpty()) Content.NoBooks
                else {
                    val shown = limit?.let { r.books.take(it) } ?: r.books
                    Content.Books(shown, r.more + (r.books.size - shown.size))
                }
            }
            Show.GOAL -> r.goal?.let { Content.GoalC(it) } ?: Content.NoGoal
            Show.STREAK -> r.streak?.let { Content.StreakC(it) } ?: Content.NoStreak
        }
    }

    /** Hinweis mit Knopf "Einstellungen oeffnen"? (nicht erreichbar: Neu-Anmelden hilft nicht) */
    fun offersSettings(s: State): Boolean = s == State.NONE || s == State.EXPIRED

    /** Heute-Karte nur mit Verbindung (none -> ausgeblendet, wie im Web) */
    fun todayVisible(r: Reading?): Boolean = r != null && r.state != State.NONE

    // ---------------------------------------------------------------- Einstellungen

    data class Status(
        val available: Boolean, val connected: Boolean, val state: State, val name: String?, val url: String?,
        val since: Double?, val suggest: String?,
    ) {
        val host: String get() = url.orEmpty().removePrefix("https://").removePrefix("http://")
        /** Formular zeigen: nicht verbunden oder abgelaufen (neu anmelden) - wie im Web */
        val needsLogin: Boolean get() = available && (!connected || state == State.EXPIRED)
    }

    fun status(text: String): Status? {
        val d = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val avail = (d["available"] as? JsonPrimitive)?.booleanOrNull != false
        val st = str(d, "state")
        return Status(avail, bool(d, "connected"), if (st == null) State.NONE else state(st), str(d, "name")?.ifEmpty { null },
            str(d, "url")?.ifEmpty { null }, num(d["since"]), str(d, "suggest")?.ifEmpty { null })
    }

    /** Body fuer POST /api/bookorbit/connect (Adresse/Name ohne Leerraum, Passwort unveraendert) */
    fun connectBody(url: String, username: String, password: String): JsonObject = buildJsonObject {
        put("url", url.trim()); put("username", username.trim()); put("password", password)
    }

    fun canConnect(url: String, username: String, password: String): Boolean = url.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
}
