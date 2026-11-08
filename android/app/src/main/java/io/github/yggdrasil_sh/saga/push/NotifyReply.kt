package io.github.yggdrasil_sh.saga.push

import io.github.yggdrasil_sh.saga.chat.ChatSetup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Design-Runde 5 (CH-04): "Antworten" direkt aus der Chat-Mitteilung. Hier steht die reine Logik (Tests): wann die
 * Aktion angeboten wird, wie der Text bereinigt wird, ob ein Auftrag noch gesendet werden soll, wie Fehler eingeordnet
 * werden und wie die Antwort bei einem Fehler im Chat liegen bleibt (Eingabefeld/Entwurf des Chats).
 *
 * Ablauf: NotifyReplyReceiver nimmt den Text aus der Mitteilung (RemoteInput), legt einen Auftrag verschluesselt ab
 * (SecureStore, nie im Klartext in der WorkManager-Datenbank) und reiht NotifyReplyWorker ein - der uebersteht
 * Prozesstod und sendet ueber denselben Pfad wie der Chat (ChatSession.send: v1/Stufe 3/ueber Servergrenzen).
 * Scheitert ein Versuch, liegt der Text sofort als Entwurf im Chat ("ausstehend") und der Auftrag versucht es spaeter
 * erneut, solange der Entwurf noch diesen Text enthaelt (wer ihn im Chat selbst sendet oder loescht, uebernimmt).
 */
object NotifyReply {
    const val ACTION = "io.github.yggdrasil_sh.saga.action.REPLY"
    /** Schluessel des RemoteInput-Ergebnisses */
    const val KEY_TEXT = "saga_reply_text"
    const val EXTRA_TAG = "tag"
    const val EXTRA_URL = "url"
    const val EXTRA_GROUP = "group"
    const val EXTRA_ACCOUNT = "account"
    const val EXTRA_CHAT = "chat"

    /** wie ChatOffline.draftToStore - laengere Antworten werden gekuerzt */
    const val MAX_LEN = 20_000
    /** Versuche des Auftrags (Rueckzug 30 s, verdoppelt: zusammen rund zwei Stunden) */
    const val MAX_ATTEMPTS = 8
    /** Ablage des Auftrags im SecureStore */
    const val STORE_PREFIX = "notify_reply:"

    /**
     * Antworten-Aktion fuer diese Mitteilung? -> Chat-Kennung, sonst null. Nur Chat-Mitteilungen mit Chat in der url,
     * nur mit Chat-Schluesseln auf diesem Geraet ("Chats auf diesem Geraet" eingerichtet) und nicht, wenn der Chat-Stand
     * schon sagt, dass dieses Geraet nicht senden kann (Chats aus, neues Konto/Geraet ohne Kopplung, alter Server).
     * LOADING/ERROR (Hintergrund, noch nicht geladen) zaehlen als moeglich - der Auftrag prueft es beim Senden.
     */
    fun offer(kind: String, url: String, hasKeys: Boolean, setup: ChatSetup): Long? {
        if (kind != "chat.message" && kind != "chat.mention") return null
        if (!hasKeys) return null
        if (setup == ChatSetup.OFF || setup == ChatSetup.UNSUPPORTED || setup == ChatSetup.NEW_ACCOUNT || setup == ChatSetup.NEW_DEVICE) return null
        return NotifyRoute.chatId(url)?.takeIf { it > 0 }
    }

    /** Zahl der eigenen Zeile in der MessagingStyle-Mitteilung: weder Nachricht (> 0) noch Erwaehnung (0) - NotifyUpdate laesst sie stehen */
    const val OWN_LINE = -1

    /** Zahlen je Zeile nach der eigenen Antwort: hoechstens max Zeilen, die eigene zuletzt */
    fun countsAfterReply(counts: List<Int>, max: Int): List<Int> = counts.takeLast(maxOf(0, max - 1)) + OWN_LINE

    /** Eingegebener Text: Rand weg, leer -> null, hoechstens MAX_LEN Zeichen */
    fun clean(raw: CharSequence?): String? = raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LEN)

    enum class Step { SEND, WRONG_ACCOUNT, TAKEN_OVER }

    /**
     * Vor jedem Versuch: nur fuer das Konto der Mitteilung (NotifyReadReceiver.sameAccount); liegt der Text schon im
     * Chat (parked) und steht er dort nicht mehr am Ende des Entwurfs, hat die Person ihn selbst gesendet oder geloescht.
     */
    fun before(jobAccount: String?, current: String, parked: Boolean, draft: String?, text: String): Step = when {
        !NotifyReadReceiver.sameAccount(jobAccount, current) -> Step.WRONG_ACCOUNT
        parked && !stillParked(draft, text) -> Step.TAKEN_OVER
        else -> Step.SEND
    }

    enum class Fail { RETRY, GIVE_UP }

    /**
     * Fehler einordnen: Netz (network) sowie HTTP 408/429/5xx -> spaeter erneut (bis MAX_ATTEMPTS); alles andere
     * (HTTP 4xx: kein Zugriff, Chat weg, Anfrage nicht angenommen; ohne code: Schluessel fehlen, Sicherheitsnummer
     * blockiert, Empfaenger ungueltig) -> aufgeben, der Text bleibt im Chat.
     */
    fun classify(code: Int?, network: Boolean, attempt: Int): Fail {
        if (attempt + 1 >= MAX_ATTEMPTS) return Fail.GIVE_UP
        if (network) return Fail.RETRY
        if (code == null) return Fail.GIVE_UP
        return if (code == 408 || code == 429 || code in 500..599) Fail.RETRY else Fail.GIVE_UP
    }

    /**
     * Nachbesserung (Doppelversand): Kann der gescheiterte Versuch beim Server trotzdem angekommen sein? Netzfehler nach
     * dem Verbindungsaufbau (Abbruch, Zeitueberschreitung waehrend/nach dem POST), HTTP 408 und 5xx -> unklar.
     * Klar nicht angekommen: Verbindung gar nicht erst aufgebaut (beforeRequest: kein Netz, Name/Ziel unerreichbar),
     * 429 und alle anderen Antworten des Servers.
     */
    fun unclear(code: Int?, network: Boolean, beforeRequest: Boolean): Boolean = when {
        network -> !beforeRequest
        code == null -> false
        else -> code == 408 || code in 500..599
    }

    /** Nachricht im Chat (fuer die Pruefung vor einer Wiederholung): eigene?, entschluesselter Text, Zeit des Absenders (ms) */
    data class Seen(val mine: Boolean, val text: String?, val ts: Long)

    /** Spielraum fuer die Zeit der eigenen Nachricht gegen den Beginn des ersten Versuchs (gleiche Uhr, nur Rundung) */
    const val SINCE_SLACK_MS = 2_000L

    /**
     * Vor einer Wiederholung nach einem unklaren Fehler: steht seit dem ersten Versuch ([since], ms) schon eine eigene
     * Nachricht mit genau diesem Text im Chat, ist die Antwort angekommen -> nicht noch einmal senden.
     */
    fun alreadySent(seen: List<Seen>, text: String, since: Long): Boolean {
        val t = text.trim()
        return seen.any { it.mine && it.text?.trim() == t && it.ts >= since - SINCE_SLACK_MS }
    }

    /** Text in den Entwurf des Chats legen: leer -> der Text, sonst an den bisherigen Entwurf angehaengt */
    fun park(draft: String?, text: String): String {
        val d = draft?.takeIf { it.isNotBlank() } ?: return text.take(MAX_LEN)
        if (d == text || d.endsWith("\n" + text)) return d
        return (d.trimEnd() + "\n" + text).take(MAX_LEN)
    }

    /** Liegt der Text noch (unveraendert, am Ende) im Entwurf? */
    fun stillParked(draft: String?, text: String): Boolean = draft != null && (draft == text || draft.endsWith("\n" + text))

    /** Nach dem Senden den geparkten Text wieder aus dem Entwurf nehmen (anderes bleibt stehen); leer -> null */
    fun unpark(draft: String?, text: String): String? = when {
        draft == null || draft == text -> null
        draft.endsWith("\n" + text) -> draft.removeSuffix("\n" + text).trimEnd().ifEmpty { null }
        else -> draft
    }

    /** Auftrag (verschluesselt im SecureStore abgelegt; die WorkManager-Daten tragen nur die id) */
    @Serializable
    data class Job(
        val id: String,
        val account: String,
        val chat: Long,
        val text: String,
        val tag: String,
        val url: String,
        val group: String? = null,
        val ts: Long = 0,
        /** Text liegt schon als Entwurf im Chat */
        val parked: Boolean = false,
        /** Beginn des ersten Sendeversuchs (ms) */
        val since: Long = 0,
        /** ein frueherer Versuch ist unklar gescheitert (vielleicht doch angekommen) - vor jedem weiteren pruefen */
        val unclear: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encode(j: Job): String = json.encodeToString(Job.serializer(), j)

    fun decode(s: String?): Job? = try {
        s?.let { json.decodeFromString(Job.serializer(), it) }
    } catch (_: Exception) {
        null
    }
}
