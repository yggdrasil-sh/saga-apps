package io.github.yggdrasil_sh.saga.call

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 2.5 (L8/L9) "Person hinzufuegen" im laufenden Anruf: Gruppenanruf (L8, Feature `call_add`, Anruf-Objekt `can_add`/
 * `add_page`) und 1:1 ueber Knoten -> neue Gruppe (L9, Feature `call_move`, `can_move`/`move_block`/`move_hint`).
 * Vertrag: Server docs/APP-API.md "Anrufe: Personen hinzufuegen" und "1:1 → Konferenz". Reine Regeln ohne Android
 * (Unit-Tests: CallAddTest) - wann der Knopf da ist, Auswahl (wer schon im Anruf ist, fehlt), Antwort und Fehler von
 * POST /api/calls/{id}/add, Umzug in den neuen Chat, Chat-Eintrag "In Gruppenanruf verschoben".
 */
object CallAdd {
    const val FEATURE_ADD = "call_add"
    const val FEATURE_MOVE = "call_move"
    /** Hinweis im Feld (Ergebnis bzw. Fehler) so lange zeigen */
    const val NOTE_MS = 6_000L
    /** Kontakte ohne Eingabe (wie Web: GET /api/chat/contacts?limit=30) */
    const val CONTACTS = 30

    /** Was das Anruf-Objekt zum Hinzufuegen sagt (fehlt alles: aelterer Server) */
    data class Flags(
        val canAdd: Boolean = false,
        val addPage: String? = null,
        val canMove: Boolean = false,
        val moveBlock: String? = null,
        val moveHint: String? = null,
    )

    fun flags(k: CallInfo): Flags = Flags(k.canAdd, k.addPage?.takeIf { it.isNotBlank() }, k.canMove, k.moveBlock, k.moveHint?.takeIf { it.isNotBlank() })

    /** Knopf "Person hinzufuegen": nichts, Auswahl (Gruppe L8 bzw. Umzug L9) oder nur ein Hinweis (1:1 direkt/foederiert) */
    enum class Mode { NONE, ADD, MOVE, HINT }

    /**
     * kind/dm: Raum des Anrufs (`group` + dm = 1:1 ueber Knoten; `dm` = direkter 1:1 ohne Raum). companion: Begleitgeraet
     * (holt niemanden dazu). Wie static/calls.js addOk/moveOk: im 1:1 ueber Knoten zieht "Person hinzufuegen" um, solange
     * `can_move` bzw. `move_block: "ringing"` (das Objekt kann noch vom Klingeln stammen - der Server prueft frisch).
     */
    fun mode(features: Set<String>, kind: String, dm: Boolean, f: Flags, companion: Boolean = false): Mode {
        if (companion) return Mode.NONE
        val move = FEATURE_MOVE in features
        return when {
            kind == CallRoom.KIND_GROUP && !dm -> if (FEATURE_ADD in features && f.canAdd) Mode.ADD else Mode.NONE
            kind == CallRoom.KIND_GROUP -> when {
                !move -> Mode.NONE
                f.canMove || f.moveBlock == "ringing" -> Mode.MOVE
                f.moveBlock == "direct" || f.moveBlock == "fed" -> Mode.HINT
                else -> Mode.NONE
            }
            // direkter 1:1 (kein Raum): Knopf zeigt nur, warum es nicht geht
            else -> if (move) Mode.HINT else Mode.NONE
        }
    }

    /** Hinweis fuer Mode.HINT: Text des Servers (move_hint), sonst fester Text */
    fun hint(f: Flags): UiText = f.moveHint?.let { UiText.Raw(it) } ?: UiText.Res(R.string.add_direct_hint)

    /** Plaetze: belegt = Zahl der legs (jedes Geraet, auch Begleitgeraete); limit 0 = unbekannt (nie voll) */
    data class Seats(val used: Int, val limit: Int) {
        val left: Int get() = if (limit > 0) maxOf(0, limit - used) else 99
        val full: Boolean get() = limit > 0 && used >= limit
    }

    fun seats(legs: List<CallLeg>, limit: Int): Seats = Seats(legs.size, limit)

    /** Hinweis oben im Feld (wie Web addOpen.draw): voll > Umzug > Seite > allgemein */
    fun fieldHint(mode: Mode, seats: Seats, addPage: String?): UiText = when {
        seats.full -> UiText.Res(R.string.add_full)
        mode == Mode.MOVE -> UiText.Res(R.string.add_move_hint)
        addPage != null -> UiText.Res(R.string.add_page_hint, addPage)
        else -> UiText.Res(R.string.add_hint)
    }

    /** Zeile der Auswahl: ref = Kennung fuer `uids` (Kontakt `ref`, Benutzername bzw. Handle aus der Suche) */
    data class Pick(
        val ref: String,
        val name: String,
        val avatar: String? = null,
        val sub: String? = null,
        val remoteHost: String? = null,
        val gone: Boolean = false,
        /** Mitglied ohne Geraet im Anruf: "im Chat – klingelt erneut" */
        val away: Boolean = false,
    )

    /** Kennungen, unter denen jemand im Anruf ist (ref bzw. Handle bzw. who - wie Web inCall) */
    fun inCall(legs: List<CallLeg>): Set<String> = legs.flatMap { listOfNotNull(it.ref, it.handle, it.who) }.filter { it.isNotEmpty() }.toSet()

    /** Mitglieder ohne Geraet im Anruf ("Nicht dabei") */
    fun away(inv: List<CallInvite>?): Set<String> = inv.orEmpty().flatMap { listOfNotNull(it.ref, it.handle, it.who) }.filter { it.isNotEmpty() }.toSet()

    /** Wer schon im Anruf ist, fehlt; doppelte ref nur einmal; Mitglieder ohne Geraet markiert */
    fun filter(items: List<Pick>, inCall: Set<String>, away: Set<String>): List<Pick> =
        items.filter { it.ref.isNotEmpty() && it.ref !in inCall }.distinctBy { it.ref }.map { if (it.ref in away) it.copy(away = true) else it }

    /** Kontakt (GET /api/chat/contacts) -> Zeile; fremde Personen: "Person auf {host}" */
    fun fromContact(k: io.github.yggdrasil_sh.saga.chat.ChatContact): Pick = Pick(
        ref = k.ref, name = k.name, avatar = k.avatar, sub = if (k.remote) null else k.sub.ifBlank { null },
        remoteHost = if (k.remote) (k.host ?: k.sub).ifBlank { null } else null, gone = k.gone || k.ref.isEmpty(),
    )

    /** Treffer der Personensuche (users[].username = Such-Handle, remote[].handle) -> Zeilen */
    fun fromSearch(r: io.github.yggdrasil_sh.saga.chat.PeopleResult): List<Pick> =
        r.users.filter { !it.me }.map { Pick(it.username, it.name, it.avatar, sub = it.sub?.ifBlank { null }) } +
            r.remote.map { Pick(it.handle, it.label.ifBlank { it.name }, it.avatar, remoteHost = it.host.ifBlank { null }) }

    /** Zeile waehlbar? (voll: alle deaktiviert; Kontakt unerreichbar `gone`) */
    fun pickable(p: Pick, seats: Seats, busy: Boolean): Boolean = !seats.full && !p.gone && !busy

    // ------------------------------------------------------------ POST /api/calls/{id}/add

    @Serializable
    data class Failed(val who: String = "", val error: String = "", val kind: String = "")

    @Serializable
    data class MovedTo(@SerialName("from_chat") val fromChat: Long = 0, @SerialName("chat_id") val chatId: Long = 0)

    @Serializable
    data class SeatsDto(val used: Int = 0, val limit: Int = 0, val left: Int = 0, val topo: String? = null)

    /** Antwort 200 */
    @Serializable
    data class Reply(
        val ok: Boolean = false,
        val n: Int = 0,
        val added: List<String> = emptyList(),
        val failed: List<Failed> = emptyList(),
        val pending: List<String> = emptyList(),
        val request: List<String> = emptyList(),
        val invites: List<CallInvite>? = null,
        @SerialName("invites_more") val invitesMore: Int = 0,
        val seats: SeatsDto? = null,
        val rv: Double? = null,
        val now: Double? = null,
        /** L9: Anruf haengt jetzt am neuen Chat */
        val moved: MovedTo? = null,
        /** L9: Anruf-Objekt nach dem Umzug */
        val call: CallInfo? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    fun parse(body: String): Reply = json.decodeFromString(Reply.serializer(), body)

    /** Hinweis nach 200 (wie Web addPick): erster Fehler (Text des Servers) > pending > request > niemand neu > "wird angerufen" */
    fun outcome(r: Reply, name: String): UiText {
        r.failed.firstOrNull()?.let { f -> return if (f.error.isNotBlank()) UiText.Raw(f.error) else UiText.Res(R.string.add_failed) }
        return when {
            r.pending.isNotEmpty() -> UiText.Res(R.string.add_pending, name)
            r.request.isNotEmpty() -> UiText.Res(R.string.add_request, name)
            r.n <= 0 -> UiText.Res(R.string.add_already, name)
            else -> UiText.Res(R.string.inv_calling_one, name)
        }
    }

    /** Fehler von /add */
    sealed interface Err {
        /** 409 page_confirm: Rueckfrage "Seite teilen?" mit dem Text des Servers, dann mit confirm_page wiederholen */
        data class PageConfirm(val text: UiText) : Err
        /** Hinweis (429 fester Text, sonst Text des Servers bzw. allgemein); ended = Anruf vorbei */
        data class Note(val text: UiText, val ended: Boolean = false, val full: Boolean = false) : Err
    }

    private fun obj(body: String?): JsonObject? = try {
        body?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) as? JsonObject }
    } catch (_: Exception) {
        null
    }

    private fun flag(o: JsonObject?, k: String): Boolean = (o?.get(k) as? JsonPrimitive)?.booleanOrNull == true

    fun error(code: Int?, body: String?, confirmed: Boolean, server: UiText? = null): Err {
        val o = obj(body)
        val own = (o?.get("error") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) }
        val text = own ?: server
        if (code == 409 && flag(o, "page_confirm") && !confirmed) return Err.PageConfirm(text ?: UiText.Res(R.string.add_failed))
        if (code == 429) return Err.Note(UiText.Res(R.string.inv_busy))
        if (code != null && code in 400..499 && text != null) return Err.Note(text, ended = flag(o, "ended"), full = flag(o, "full"))
        // 502 mit failed (Vertrag: "alle neuen Personen gescheitert" - Text der ersten, z. B. anderer Server nicht erreichbar)
        if (code == 502 && own != null) return Err.Note(own)
        return Err.Note(UiText.Res(R.string.add_failed))
    }

    /** Body: {uids: [ref], confirm_page?: true} */
    fun body(ref: String, confirm: Boolean): JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("uids", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(ref))))
        if (confirm) put("confirm_page", JsonPrimitive(true))
    }

    // ------------------------------------------------------------ L9: Umzug in den neuen Chat

    /**
     * Ist der Anruf umgezogen? Antwort `moved` bzw. Ereignis `call.move` (to = chat_id) - oder ein Anruf-Objekt (Poll,
     * Ereignis verpasst) desselben Anrufs, das nicht mehr `dm` ist und an einem anderen Chat haengt. Schon umgezogen
     * (movedTo == to) bzw. kein 1:1-Raum: nichts zu tun.
     */
    fun moveTarget(dm: Boolean, chatId: Long, movedTo: Long?, to: Long?): Long? {
        if (to == null || to <= 0 || to == chatId || to == movedTo) return null
        return if (dm || movedTo != null) to else null
    }

    fun moveFromInfo(dm: Boolean, chatId: Long, movedTo: Long?, k: CallInfo): Long? =
        if (k.kind == CallRoom.KIND_GROUP && !k.dm && k.state != "ended") moveTarget(dm, chatId, movedTo, k.chatId) else null

    /** Raum-Zustand, den der Umzug aendert (Medien, Beine und Schluessel bleiben unangetastet) */
    data class Room(val kind: String, val dm: Boolean, val chatId: Long, val movedTo: Long? = null, val title: String = "")

    /** Zustand nach dem Umzug nach `to` (null = nichts zu tun): Gruppenanruf ohne dm am neuen Chat, Titel = Gruppe */
    fun move(r: Room, to: Long?, title: String): Room? {
        val t = moveTarget(r.dm, r.chatId, r.movedTo, to) ?: return null
        return Room(CallRoom.KIND_GROUP, false, t, t, title)
    }

    /** Name der neuen Gruppe: aus dem Anruf-Objekt (`from.chat`), sonst Chatliste, sonst "Gruppenanruf" (leer) */
    fun movedTitle(fromObj: String?, chatList: String?): String = fromObj?.takeIf { it.isNotBlank() } ?: chatList.orEmpty()

    // ------------------------------------------------------------ Chat-Eintrag im alten 1:1

    /** "Zur Gruppe": nur gueltige Chat-Nummer (wie Web /^[1-9][0-9]{0,17}$/) */
    fun movedChat(e: CallEntry): Long? = e.moved?.takeIf { e.status == "ended" && it in 1..999_999_999_999_999_999L }
}
