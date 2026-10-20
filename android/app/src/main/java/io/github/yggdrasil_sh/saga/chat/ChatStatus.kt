package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.chat.ChatFormat.V
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.lp
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Saga 2.4: Gelesen-Bestaetigungen und Online-Status ("wie WhatsApp") nach docs/APP-API.md, Abschnitt
// "Gelesen-Bestätigungen und Online-Status (2.4)". Standard an, je Konto getrennt abschaltbar, gegenseitig. Eine
// Bestaetigung ist vom Geraet signiert und je anderem Mitglied fuer dessen Backup-Schluessel versiegelt (der Server sieht
// nur, DASS es eine gibt). Aeltere Server (ohne features/privacy in /api/chat/me): alles ausgeblendet.
// Geprueft mit tests/vectors/receipts.json (Unit-Test ReceiptsTest).

/** Eigene Einstellungen (GET/PUT /api/chat/privacy, Feld privacy in /api/chat/me) */
@Serializable
data class ChatPrivacy(val receipts: Boolean = true, val online: Boolean = true)

/** Grenzen des Servers (status_limits in /api/chat/me) */
@Serializable
data class StatusLimits(
    @SerialName("receipt_gap") val receiptGap: Double = 2.0,
    @SerialName("presence_ttl") val presenceTtl: Int = 90,
    @SerialName("presence_users") val presenceUsers: Int = 50,
    @SerialName("last_round") val lastRound: Int = 300,
)

/** Eine versiegelte Bestaetigung aus GET /api/chats/{id}/receipts bzw. chat.receipt */
data class SealedReceipt(val from: String, val handle: String?, val sealed: String)

/** Geoeffnete Bestaetigung (noch ungeprueft) */
class OpenedReceipt(val dev: String, val body: String, val sig: ByteArray)

/** Gepruefte Bestaetigung einer Person: gelesen bis Nachricht id (Position n), um ts (ms) */
data class ReadMark(val n: Long, val id: String, val ts: Long)

/** Haken an einer eigenen Nachricht */
enum class TickKind { NONE, PENDING, SENT, READ }

/** Haken + Anzahl Lesender (Gruppen) + "von allen gelesen" */
data class Tick(val kind: TickKind, val readers: Int = 0, val all: Boolean = false)

/** Online-Status einer Person: online oder "zuletzt online" (Unix-Sekunden, 5-Minuten-Raster; null = unbekannt) */
data class Presence(val online: Boolean, val last: Double?)

/** Kryptografie der Bestaetigungen - nur libsodium-Primitive ueber ChatCrypto (sign_detached, box_seal) */
class ReceiptCrypto(private val c: ChatCrypto) {
    /** LP("saga-chat/v1/receipt", chat_id, benutzername, geraet, body) bzw. v2 mit chat_ref und Handle */
    fun msg(chat: String, reader: String, dev: String, body: String, v2: Boolean): ByteArray =
        lp((if (v2) FedFormat.V2 else V) + "receipt", chat, reader, dev, body)

    fun sign(chat: String, reader: String, dev: DeviceKeys, body: String, v2: Boolean): ByteArray = c.sign(msg(chat, reader, dev.id, body, v2), dev.signSk)

    /** inner = {"v":1,"dev":…,"body":…,"sig":…} -> crypto_box_seal fuer den Backup-Schluessel des Gegenuebers */
    fun seal(chat: String, reader: String, dev: DeviceKeys, body: String, v2: Boolean, toPk: ByteArray): String {
        val inner = buildJsonObject {
            put("v", 1)
            put("dev", dev.id)
            put("body", body)
            put("sig", b64(sign(chat, reader, dev, body, v2)))
        }.toString()
        return b64(c.seal(inner.toByteArray(Charsets.UTF_8), toPk))
    }

    fun open(sealed: String, pk: ByteArray, sk: ByteArray): OpenedReceipt {
        val raw = unb64(sealed) ?: throw ChatCryptoException("receipt")
        val o = Json.parseToJsonElement(String(c.sealOpen(raw, pk, sk), Charsets.UTF_8)) as? JsonObject ?: throw ChatCryptoException("receipt")
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((o["v"] as? JsonPrimitive)?.longOrNull != 1L) throw ChatCryptoException("receipt version")
        val dev = s("dev") ?: throw ChatCryptoException("receipt")
        val body = s("body") ?: throw ChatCryptoException("receipt")
        val sig = unb64(s("sig"), 64) ?: throw ChatCryptoException("receipt")
        return OpenedReceipt(dev, body, sig)
    }

    fun verify(chat: String, reader: String, r: OpenedReceipt, signPk: ByteArray?, v2: Boolean): Boolean =
        c.verify(r.sig, msg(chat, reader, r.dev, r.body, v2), signPk)
}

/** Reine Regeln (Tests): wann senden, was anzeigen, Gegenseitigkeit */
object ReceiptLogic {
    /** body = genau dieser JSON-Text wird signiert: {"id":"<32 hex>","n":<int>,"ts":<ms>} */
    fun body(id: String, n: Long, ts: Long): String = buildJsonObject {
        put("id", id)
        put("n", n)
        put("ts", ts)
    }.toString()

    /** body lesen -> ReadMark (null = Form falsch) */
    fun parseBody(body: String): ReadMark? = try {
        val o = Json.parseToJsonElement(body) as? JsonObject
        val n = (o?.get("n") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val id = (o?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val ts = (o?.get("ts") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L
        if (n == null || n <= 0 || id == null) null else ReadMark(n, id, ts)
    } catch (_: Exception) {
        null
    }

    /** Server kann es (2.4) */
    fun has(me: ChatMe?, feature: String): Boolean = me?.features?.contains(feature) == true

    /** eigene Einstellung (Server ohne Feld: Funktion gibt es nicht) */
    fun privacy(me: ChatMe?): ChatPrivacy = me?.privacy ?: ChatPrivacy(receipts = false, online = false)

    /**
     * Bestaetigungen in diesem Chat senden UND anzeigen (gegenseitig): Server kann es, eigene Einstellung an, keine
     * Nachrichtenanfrage, nicht schreibgeschuetzt, anderer Server kann es (receipts_ok != false).
     */
    fun enabled(me: ChatMe?, c: ChatSummary?): Boolean =
        c != null && has(me, "receipts") && privacy(me).receipts && !c.request && !c.readonly && c.receiptsOk != false

    /** Online-Status dieses Direkt-Chats zeigen/abonnieren */
    fun onlineFor(me: ChatMe?, c: ChatSummary?): String? {
        if (c == null || c.kind != "dm" || c.request || !has(me, "online") || !privacy(me).online || c.onlineOk == false) return null
        return c.peer?.username?.ifEmpty { null }
    }

    /** Position einer Nachricht: lokal seq, ueber Servergrenzen fseq (Reihenfolge des Heims) */
    fun ord(m: ChatMessage, fed: Boolean): Long = if (fed) m.fseq ?: 0 else m.seq

    /** zaehlt fuer Bestaetigungen: echte Nachricht (kein Anruf/Systemzeile), nicht von mir (KI-Antworten in meinem Namen schon) */
    fun fromOthers(m: ChatMessage): Boolean =
        m.sys == null && m.call == null && !(m.mine && m.ai != io.github.yggdrasil_sh.saga.ai.AiReplyCheck.OK)

    /**
     * Was bestaetigen? Neueste Nachricht mit Position, die nicht von mir ist UND sichtbar war (seen = Position der neuesten
     * gesehenen) und noch nicht bestaetigt (sent). -> (id, n) oder null
     */
    fun want(msgs: List<ChatMessage>, fed: Boolean, seen: Long, sent: Long): Pair<String, Long>? {
        for (i in msgs.indices.reversed()) {
            val m = msgs[i]
            val n = ord(m, fed)
            if (n <= 0 || !fromOthers(m) || n > seen) continue
            return if (n > sent) m.id to n else null
        }
        return null
    }

    /** hoechstens eine je Chat und receipt_gap (Standard 2 s): wie lange noch warten (0 = jetzt) */
    fun wait(now: Long, lastAt: Long, gapMs: Long): Long = (lastAt + gapMs - now).coerceAtLeast(0)

    /**
     * neue Bestaetigung uebernehmen: je Person nur das groesste n. Gleiches n erneut (z. B. erneut geoeffnet): die
     * fruehere Zeit behalten - sonst springt "Gelesen" bei jedem Oeffnen weiter (TT 2.4 P-06)
     */
    fun take(old: ReadMark?, new: ReadMark): Boolean =
        old == null || new.n > old.n || (new.n == old.n && new.ts > 0 && (old.ts <= 0 || new.ts < old.ts))

    /** Wer hat m gelesen? (ohne mich) */
    fun readers(
        m: ChatMessage, fed: Boolean, members: List<ChatPerson>, reads: Map<String, ReadMark>, order: List<ChatMessage>? = null,
    ): List<Pair<ChatPerson, ReadMark>> {
        val n = ord(m, fed)
        if (n <= 0) {
            // eigene Nachricht ohne Position (fseq kam nicht an, z. B. Bild/Video: Zustellung schneller als das Einfuegen):
            // gelesen, wenn die Bestaetigung genau diese oder eine spaetere Nachricht im Verlauf nennt (TT 2.4 P-06)
            val list = order ?: return emptyList()
            val at = list.indexOfFirst { it.id == m.id }
            if (at < 0) return emptyList()
            return members.filter { !it.me }.mapNotNull { p ->
                reads[p.username]?.takeIf { r -> list.indexOfFirst { it.id == r.id } >= at }?.let { p to it }
            }
        }
        return members.filter { !it.me }.mapNotNull { p -> reads[p.username]?.takeIf { it.n >= n }?.let { p to it } }
    }

    /**
     * Haken an einer eigenen Nachricht: ⏳ wird zugestellt (fstate pending) -> ✓ zugestellt -> ✓✓ gelesen. Direkt-Chat:
     * ✓✓, sobald das Gegenueber bestaetigt hat; Gruppe: ✓✓ "von allen" bzw. mit Zaehler. Server ohne die Funktion: nichts.
     */
    fun tick(
        m: ChatMessage, feature: Boolean, enabled: Boolean, fed: Boolean, members: List<ChatPerson>, reads: Map<String, ReadMark>,
        order: List<ChatMessage>? = null,
    ): Tick {
        if (!feature || !m.mine || m.sys != null || m.call != null || m.ai == io.github.yggdrasil_sh.saga.ai.AiReplyCheck.OK) return Tick(TickKind.NONE)
        if (m.fstate == "pending") return Tick(TickKind.PENDING)
        if (m.fstate == "failed") return Tick(TickKind.NONE)
        if (!enabled) return Tick(TickKind.SENT)
        val others = members.count { !it.me }
        val r = readers(m, fed, members, reads, order).size
        return if (r == 0) Tick(TickKind.SENT) else Tick(TickKind.READ, r, others in 1..r)
    }

    /** Liste [{from, handle?, sealed}] aus GET …/receipts bzw. chat.receipt (Unvollstaendiges ignorieren) */
    fun parseList(a: kotlinx.serialization.json.JsonElement?): List<SealedReceipt> =
        (a as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val from = (o["from"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val sealed = (o["sealed"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            SealedReceipt(from, (o["handle"] as? JsonPrimitive)?.contentOrNull, sealed)
        }
}

/** Reine Regeln fuer den Online-Status (Tests) */
object PresenceLogic {
    /** Antwort von POST /api/chat/presence -> (enabled, Personen) */
    fun parse(o: JsonObject): Pair<Boolean, Map<String, Presence>> {
        val enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull != false
        val users = (o["users"] as? JsonObject).orEmpty().mapNotNull { (u, v) ->
            val p = v as? JsonObject ?: return@mapNotNull null
            u to Presence((p["online"] as? JsonPrimitive)?.booleanOrNull == true, (p["last"] as? JsonPrimitive)?.doubleOrNull)
        }.toMap()
        return enabled to users
    }

    /** Live chat.presence -> neuer Stand der Person (null = entfernen: online false ohne last = abgeschaltet) */
    fun event(online: Boolean, last: Double?): Presence? = if (online || last != null) Presence(online, last) else null

    /**
     * "zuletzt online" anzeigen: der Server rundet auf 5 Minuten ab - in der ersten Stunde im 5-Minuten-Raster
     * (mindestens "vor 5 Minuten"), danach wie relativeTime. -> Zeitpunkt (Sekunden), der angezeigt wird
     */
    fun shownLast(nowSec: Double, last: Double, round: Int = 300): Double {
        val ago = nowSec - last
        if (ago >= 3600) return last
        val r = round.coerceAtLeast(60)
        val steps = maxOf(1L, (ago / r).toLong())
        return nowSec - steps * r
    }

    /** Personen anderer Server (Prinzipal "@~x@host") ohne Antwort: der eigene Server fragt dort erst nach */
    fun missingRemote(users: List<String>, got: Map<String, Presence>): Boolean = users.any { it.startsWith("@") && it !in got }
}

/**
 * Online-Abo (POST /api/chat/presence): nur, was SICHTBAR ist - jede Ansicht (Chatliste, offener Direkt-Chat) meldet
 * sich mit show() an und mit hide() ab (Lebenszyklus STARTED/STOPPED: im Hintergrund kein Abo, Akku). Solange etwas
 * sichtbar ist: sofort (gebuendelt nach 400 ms), dann alle 60 s erneuern (Server-ttl 90 s); fehlen Personen anderer
 * Server, eine Minute lang alle 10 s. Nichts mehr sichtbar: Abo abmelden ("leave"), Stand verwerfen.
 */
class PresenceHub(
    private val scope: CoroutineScope,
    private val post: suspend (JsonObject) -> String,
    /** Abo erlaubt (Server kann "online", eigene Einstellung an) */
    private val allowed: () -> Boolean,
    /** Server meldet enabled: false (Einstellung anderswo abgeschaltet) */
    private val onDisabled: () -> Unit = {},
    private val maxUsers: () -> Int = { 50 },
) {
    companion object {
        const val DEBOUNCE = 400L
        const val RENEW = 60_000L
        const val RETRY = 10_000L
        const val RETRIES = 6
    }

    val state = MutableStateFlow<Map<String, Presence>>(emptyMap())
    private val wants = LinkedHashMap<String, List<String>>()
    private var job: Job? = null
    private var posted: List<String> = emptyList()

    /** aktuell abonnierte Personen (Tests) */
    val users: List<String> get() = synchronized(wants) { wants.values.flatten().distinct().take(maxUsers()) }

    val active: Boolean get() = job?.isActive == true

    /** Ansicht owner zeigt users (ersetzt die vorige Liste dieser Ansicht) */
    fun show(owner: String, list: List<String>) {
        val changed = synchronized(wants) {
            val old = wants[owner]
            if (list.isEmpty()) wants.remove(owner) else wants[owner] = list
            old != list.ifEmpty { null }
        }
        if (changed || !active) restart()
    }

    fun hide(owner: String) {
        val had = synchronized(wants) { wants.remove(owner) != null }
        if (had) restart()
    }

    /** Einstellung geaendert / Abmelden: neu beginnen */
    fun refresh() = restart()

    fun clearAll() {
        synchronized(wants) { wants.clear() }
        job?.cancel()
        job = null
        posted = emptyList()
        state.value = emptyMap()
    }

    /** Live chat.presence */
    fun onEvent(username: String, online: Boolean, last: Double?) {
        if (!active) return
        val p = PresenceLogic.event(online, last)
        state.value = if (p == null) state.value - username else state.value + (username to p)
    }

    private fun restart() {
        job?.cancel()
        val list = users
        if (list.isEmpty() || !allowed()) {
            val gone = posted
            posted = emptyList()
            state.value = emptyMap()
            job = null
            if (gone.isNotEmpty()) scope.launch { quietly { post(body(gone, leave = true)) } }
            return
        }
        job = scope.launch {
            delay(DEBOUNCE)
            loop()
        }
    }

    private fun body(list: List<String>, leave: Boolean) = buildJsonObject {
        put("users", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } })
        if (leave) put("leave", true)
    }

    private suspend fun loop() {
        var miss = 0
        while (true) {
            val list = users
            if (list.isEmpty() || !allowed()) {
                state.value = emptyMap()
                return
            }
            var wait = RENEW
            val r = try {
                Json.parseToJsonElement(post(body(list, leave = false))) as? JsonObject
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            posted = list
            if (r != null) {
                val (enabled, got) = PresenceLogic.parse(r)
                if (!enabled) {
                    state.value = emptyMap()
                    onDisabled()
                    return
                }
                state.value = got
                miss = if (PresenceLogic.missingRemote(list, got)) miss + 1 else 0
                if (miss in 1..RETRIES) wait = RETRY
            }
            delay(wait)
        }
    }
}
