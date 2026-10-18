package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Antwort von DELETE /api/chats/{cid}?scope=me|all (docs/APP-API-CHAT.md, Server 2.1) */
@Serializable
data class ChatDeleteResult(
    val ok: Boolean = false,
    val scope: String = "me",
    val removed: Boolean = false,
    @SerialName("cleared_seq") val clearedSeq: Long = 0,
    val archived: Boolean = false,
    val hidden: Boolean = false,
    val deleted: Int = 0,
)

/** Live-Ereignis, nach dem lokale Daten eines Chats weg muessen (chat.removed / chat.cleared) */
sealed class ChatWipe {
    abstract val chatId: Long

    /** Chat fuer alle geloescht: schliessen, aus Liste und Zwischenspeicher entfernen */
    data class Removed(override val chatId: Long, val byName: String?, val byMe: Boolean) : ChatWipe()

    /**
     * Verlauf geloescht: scope "me" (anderes eigenes Geraet, bis upTo) bzw. "all" (alles, upTo = Long.MAX_VALUE).
     * Direkt-Chat + "me": schliessen.
     */
    data class Cleared(override val chatId: Long, val forAll: Boolean, val upTo: Long, val byName: String?, val byMe: Boolean) : ChatWipe()
}

/** Reine Regeln fuer Archiv und Loeschen (Tests) */
object ChatDeleteLogic {
    /** Server kann Archiv/Loeschen (2.1): liefert das Feld archived; aeltere Server -> Funktionen ausblenden */
    fun supported(c: ChatSummary?): Boolean = c?.archived != null

    fun supported(list: List<ChatSummary>): Boolean = list.any { it.archived != null }

    /** Liste teilen: (aktive, archivierte), Reihenfolge des Servers bleibt */
    fun split(list: List<ChatSummary>): Pair<List<ChatSummary>, List<ChatSummary>> = list.partition { it.archived != true }

    /** Zaehler am Bereich "Archiviert": ungelesene Nachrichten der archivierten, nicht stummgeschalteten Chats */
    fun archivedUnread(list: List<ChatSummary>): Int = list.filter { it.archived == true && !it.muted }.sumOf { it.unread }

    /** Ungelesen gesamt ohne Archiv und Stummgeschaltete (wie der Server; Rueckfall, falls nur die Liste da ist) */
    fun unreadTotal(list: List<ChatSummary>): Int = list.filter { it.archived != true && !it.muted }.sumOf { it.unread }

    /** Lokale Nachrichten nach "Verlauf geloescht bis" */
    fun <T> keepAfter(list: List<T>, upTo: Long, seq: (T) -> Long): List<T> = if (upTo <= 0) list else list.filter { seq(it) > upTo }

    /** Tag der System-Mitteilungen eines Chats (saga/push.py: "chat-" + id) */
    fun notificationTag(chatId: Long) = "chat-$chatId"

    /** Praefix der Plattencache-Eintraege eines Chats (ChatBlobCache) */
    fun blobPrefix(chatId: Long) = "c${chatId}_"

    /** Was "Für alle" bewirkt: "chat" | "history" | null (nicht erlaubt) - unbekannte Werte wie null */
    fun forAll(c: ChatSummary): String? = c.deleteForAll?.takeIf { it == "chat" || it == "history" }

    /** Begruendung, wenn "Für alle" nicht erlaubt ist (null = erlaubt) */
    fun forAllReason(c: ChatSummary): Int? = if (forAll(c) != null) null else when (c.kind) {
        "group" -> R.string.chatdel_reason_group
        "page" -> R.string.chatdel_reason_page
        "free" -> R.string.chatdel_reason_free
        else -> R.string.chatdel_reason_other
    }

    /** "Gruppe außerdem verlassen" anbieten: nur freie Gruppen (bei "Für alle" ist die Gruppe ohnehin weg) */
    fun offerLeave(c: ChatSummary, forAll: Boolean): Boolean = c.kind == "free" && !forAll

    /** Erklaerung zur gewaehlten Option */
    fun explain(c: ChatSummary, forAll: Boolean): Int = when {
        !forAll && c.kind == "dm" -> R.string.chatdel_me_dm
        !forAll -> R.string.chatdel_me_other
        forAll(c) == "history" -> R.string.chatdel_all_history
        c.kind == "dm" -> R.string.chatdel_all_dm
        else -> R.string.chatdel_all_free
    }

    /** Hinweis nach dem Loeschen */
    fun doneText(c: ChatSummary, forAll: Boolean, left: Boolean): Int = when {
        forAll && forAll(c) == "history" -> R.string.chatdel_done_all_history
        forAll -> R.string.chatdel_done_all
        left -> R.string.chatdel_done_left
        c.kind == "dm" -> R.string.chatdel_done_me
        else -> R.string.chatdel_done_me_archived
    }

    /** chat.removed / chat.cleared lesen (andere Ereignisse -> null); me = eigener Benutzername */
    fun parse(type: String, data: JsonObject, me: String): ChatWipe? {
        val cid = (data["chat_id"] as? JsonPrimitive)?.longOrNull ?: return null
        val by = data["by"] as? JsonObject
        val byUser = (by?.get("username") as? JsonPrimitive)?.contentOrNull
        val byName = ((by?.get("name") as? JsonPrimitive)?.contentOrNull)?.ifBlank { null } ?: byUser
        val byMe = byUser != null && byUser == me
        return when (type) {
            "chat.removed" -> ChatWipe.Removed(cid, byName, byMe)
            "chat.cleared" -> {
                val scope = (data["scope"] as? JsonPrimitive)?.contentOrNull ?: "me"
                if (scope == "all") ChatWipe.Cleared(cid, true, Long.MAX_VALUE, byName, byMe)
                else ChatWipe.Cleared(cid, false, (data["cleared_seq"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE, null, true)
            }
            else -> null
        }
    }

    /** chat.changed {chat_id, archived}: archived (null = anderes Feld geaendert) */
    fun archivedOf(data: JsonObject): Boolean? = (data["archived"] as? JsonPrimitive)?.booleanOrNull

    /** Hinweis fuer andere: "<Name> hat den Chat/Verlauf gelöscht." (nie fuer eigene Aktionen) */
    fun noticeFor(w: ChatWipe): Int? = when (w) {
        is ChatWipe.Removed -> if (w.byMe) null else R.string.chatdel_by_chat
        is ChatWipe.Cleared -> if (w.byMe || !w.forAll) null else R.string.chatdel_by_history
    }

    /** Offene Unterhaltung schliessen? Chat weg bzw. Direkt-Chat fuer mich geleert (verschwindet aus der Liste) */
    fun closes(w: ChatWipe, kind: String?): Boolean = when (w) {
        is ChatWipe.Removed -> true
        is ChatWipe.Cleared -> !w.forAll && kind == "dm"
    }
}
