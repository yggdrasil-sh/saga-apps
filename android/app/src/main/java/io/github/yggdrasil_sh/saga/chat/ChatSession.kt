package io.github.yggdrasil_sh.saga.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.live.LiveEvent
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Entschluesselte Nachricht fuer die Anzeige */
data class ChatMessage(
    val seq: Long,
    val id: String,
    val sender: ChatPerson?,
    val senderDevice: String,
    val ts: Long,
    val expiresAt: Double?,
    val replyTo: String?,
    /** null = nicht entschluesselbar */
    val content: ChatContent?,
    /** Geraet + Zertifikat + Signatur gueltig; false -> "Echtheit nicht bestätigt" (ausgegraut, nicht verworfen) */
    val verified: Boolean,
    val mine: Boolean,
    /** 2.1: Anruf-Eintrag (kind "call", Klartext-Metadaten vom Server) statt einer Nachricht */
    val call: io.github.yggdrasil_sh.saga.call.CallEntry? = null,
    /** 2.1: KI-Antwort? OK = Beleg passt (Profilbild + Name der KI), BAD = Warnung */
    val ai: io.github.yggdrasil_sh.saga.ai.AiReplyCheck = io.github.yggdrasil_sh.saga.ai.AiReplyCheck.NONE,
    /** 2.3: Systemzeile (kind 'sys', z. B. KI ein-/ausgeschaltet) - Klartext-Metadaten vom Heim */
    val sys: ChatSys? = null,
    /** 2.3: Zustellstand in Chats ueber Servergrenzen: "pending" (wird zugestellt) | "failed" (nicht zugestellt) | null */
    val fstate: String? = null,
    val senderHandle: String? = null,
    /** 2.4: Position in Chats ueber Servergrenzen (Reihenfolge des Heims) - fuer die Gelesen-Haken */
    val fseq: Long? = null,
    /** Serverzeit (s) - 2.5: Fenster fuer Bearbeitungen */
    val created: Double? = null,
    /** 2.5: Nachricht im alten Format (v1) in einem Chat der Stufe 3 bzw. ueber Servergrenzen (Altbestand) */
    val legacy: Boolean = false,
    /** 2.5: zuletzt bearbeitet (Serverzeit, s) bzw. null; wie oft */
    val editedAt: Double? = null,
    val editN: Int = 0,
)

/** 2.1: laufende Frage an die KI (nur auf diesem Geraet sichtbar, bis die Antwort als Nachricht gesendet ist) */
@Stable
class AiPending(val questionId: String) {
    var text by mutableStateOf("")
}

/** Ergebnis des Oeffnens einer Nachricht (key: Schluessel K der Nachricht, nur v2 - fuer Reaktionen) */
class Opened(val content: ChatContent?, val verified: Boolean, val key: ByteArray? = null)

/** Geoeffnete v2-Nachricht im Rohformat (Klartext), z. B. eine Bearbeitung {"edit", "t"} */
class OpenedRaw(val plain: String?, val verified: Boolean, val key: ByteArray?)

/**
 * Nachricht oeffnen und pruefen: Schluessel (Geraet bzw. Backup) entsiegeln, entschluesseln; Absender-Geraet muss zum
 * Absender gehoeren, sein Zertifikat gegen den Identitaetsschluessel und die Signatur gueltig sein.
 */
class MessageOpener(private val svc: ChatService, private val k: LocalChatKeys) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    fun open(chatId: Long, m: ApiMessage, devices: Map<String, ChatDevicePub>, idents: Map<String, String>): Opened {
        val c = svc.crypto
        val sender = m.sender?.username ?: return Opened(null, false)
        val cid = chatId.toString()
        val content = try {
            val key = if (m.keyFor == "backup") c.openKey(m.key, k.ident.backupPk, k.ident.backupSk) else c.openKey(m.key, k.dev.encPk, k.dev.encSk)
            json.decodeFromString(ChatContent.serializer(), c.decrypt(cid, m.id, sender, m.senderDevice, m.ts, m.replyTo, m.bodyCt, key))
        } catch (_: Exception) {
            null
        }
        val dev = devices[m.senderDevice]
        val identPk = unb64(idents[sender], 32)
        val verified = dev != null && dev.uid == sender && identPk != null &&
            c.verifyDevice(sender, identPk, m.senderDevice, dev.encPub, dev.signPub, dev.sig) &&
            c.verifyMessage(cid, m.id, sender, m.senderDevice, m.ts, m.replyTo, m.bodyCt, m.sig, unb64(dev.signPub, 32))
        return Opened(content, verified)
    }
}

/**
 * 2.3 (saga-chat/v2): Nachricht eines Chats ueber Servergrenzen oeffnen. Echt nur, wenn das Absender-Geraet in den SELBST
 * geprueften Schluesseln des Absender-Handles steht (Zertifikat v2, TOFU nicht abgelehnt) und die Signatur v2 passt.
 */
class FedOpener(private val svc: ChatService, private val k: LocalChatKeys, private val fk: Map<String, FedKeyState>) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    fun open(chatRef: String, m: ApiMessage): Opened {
        val r = openRaw(chatRef, m)
        val content = try {
            r.plain?.let { json.decodeFromString(ChatContent.serializer(), it) }
        } catch (_: Exception) {
            null
        }
        return Opened(content, r.verified, r.key.takeIf { content != null })
    }

    /** Schluessel entsiegeln, entschluesseln, Signatur v2 pruefen - Klartext roh (Bearbeitungen haben eigenen Inhalt) */
    fun openRaw(chatRef: String, m: ApiMessage): OpenedRaw {
        val sender = m.senderHandle ?: return OpenedRaw(null, false, null)
        val ai = m.ai?.takeIf { it.hashOnly }?.sha256
        val c = svc.fed.crypto
        var key: ByteArray? = null
        val plain = try {
            key = if (m.keyFor == "backup") svc.crypto.openKey(m.key, k.ident.backupPk, k.ident.backupSk) else svc.crypto.openKey(m.key, k.dev.encPk, k.dev.encSk)
            c.decrypt(chatRef, m.id, sender, m.senderDevice, m.ts, m.replyTo, ai, m.bodyCt, key)
        } catch (_: Exception) {
            key = null
            null
        }
        val f = fk[sender]
        val d = f?.keys?.takeIf { f.trust != TofuV2.REJECT }?.device(m.senderDevice)
        val verified = d != null && c.verifyMessage(chatRef, m.id, sender, m.senderDevice, m.ts, m.replyTo, ai, m.bodyCt, m.sig, unb64(d.signPub, 32))
        return OpenedRaw(plain, verified, key)
    }
}

/**
 * Hintergrund-Arbeit ohne Ausnahme nach aussen (Live-Ereignisse, Nachladen nach dem Senden): ein Netzfehler darf weder
 * den Prozess beenden (Ausnahme in LaunchedEffect) noch eine schon gesendete Nachricht als "nicht gesendet" melden.
 * Abbruch (CancellationException) wird weitergereicht. -> false bei Fehler
 */
suspend fun quietly(block: suspend () -> Unit): Boolean = try {
    block()
    true
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

/** Anhang vor dem Senden (Klartext bleibt auf dem Geraet) */
class PendingFile(val name: String, val mime: String, val data: ByteArray)

/**
 * Eine offene Unterhaltung: Nachrichten laden/entschluesseln, senden (versiegelt fuer alle Empfaenger-Geraete und
 * Backup-Schluessel), loeschen, gelesen, "tippt", Einstellungen des Chats. Zustand fuer Compose.
 */
@Stable
class ChatSession(val chatId: Long, private val svc: ChatService) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private val api get() = svc.repo.api

    var summary by mutableStateOf<ChatSummary?>(null)
        private set
    val messages = mutableStateListOf<ChatMessage>()
    var more by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiText?>(null)
    var replyTo by mutableStateOf<ChatMessage?>(null)
    /** "{name} tippt …": Konto -> (Name, bis wann in ms) */
    var typing by mutableStateOf<Map<String, Pair<String, Long>>>(emptyMap())
        private set
    /** Gegenueber mit geaendertem Identitaetsschluessel (Warnung "Sicherheitsnummer geändert") */
    var identChanged by mutableStateOf<Set<String>>(emptySet())
        private set
    /** zuletzt bekannte Identitaetsschluessel der Mitglieder (Sicherheitsnummer) */
    var idents by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** aufgeloeste Seitenverweise: id -> Titel (null = kein Zugriff) */
    var notes by mutableStateOf<Map<Long, ChatNoteRef?>>(emptyMap())
        private set
    var notFound by mutableStateOf(false)
        private set
    /** 2.3: Schluessel der Mitglieder eines Chats ueber Servergrenzen (selbst geprueft, TOFU) - Handle -> Stand */
    var fk by mutableStateOf<Map<String, FedKeyState>>(emptyMap())
        private set
    /** 2.3: Chat ueber Servergrenzen (saga-chat/v2) */
    val isFed: Boolean get() = summary?.isFed == true
    /** Kennung fuer die Verschluesselung: v1 chat_id, v2 chat_ref */
    private val cryptoId: String get() = summary?.cryptoId ?: chatId.toString()

    private var lastSeq = 0L
    private var lastTyping = 0L
    /** 2.3: eigene Nachrichten, die das Heim schon bei POST bestaetigt hat (FedDelivery) */
    private val confirmed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    /** 2.3: letzter Abgleich wartender eigener Nachrichten (ms) */
    private var lastDeliveryCheck = 0L
    /** 2.3: Anhaenge aus v1-Nachrichten eines umgestellten Seiten-Chats (AD mit chat_id statt chat_ref) */
    private val legacyFiles: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** Kennung fuer einen Anhang: v1-Anhaenge eines umgestellten Chats weiter mit der chat_id */
    private fun cryptoIdFor(f: ChatFile): String = if (isFed && f.id in legacyFiles) chatId.toString() else cryptoId

    val me: String get() = svc.me.value?.uid.orEmpty()
    val name: String get() = summary?.let { svc.displayName(it) }.orEmpty()

    private suspend inline fun <reified T> decode(s: String): T = withContext(Dispatchers.Default) { json.decodeFromString<T>(s) }

    private fun keys(): LocalChatKeys = svc.keys() ?: throw UiTextException(UiText.Res(R.string.chat_not_set_up))

    // ---------------------------------------------------------------- Laden

    suspend fun open() {
        loading = true
        try {
            loadSummary()
            // v2: Schluessel der Absender (selbst geprueft) vor dem Entschluesseln - sonst stuende kurz "Echtheit nicht bestaetigt"
            if (isFed) quietly { loadFedKeys(false) }
            val r = decode<MessagesResponse>(api.get("api/chats/$chatId/messages?device=${keys().dev.id}&limit=50&calls=1"))
            messages.clear()
            messages.addAll(ChatDeleteLogic.keepAfter(decryptAll(r), summary?.clearedSeq ?: 0) { it.seq })
            more = r.more
            lastSeq = maxOf(r.lastSeq, messages.maxOfOrNull { it.seq } ?: 0)
            loadIdents()
            quietly { openReactions(messages.map { it.id }) }
            resolveNotes()
            // 2.4: zuletzt selbst bestaetigte Position und bekannte Lesezeiten (sonst springt "Gelesen" bei jedem Oeffnen)
            withContext(Dispatchers.IO) {
                sentN = maxOf(sentN, svc.store.receiptSent(chatId))
                if (readHist.isEmpty()) readHist = svc.store.receiptHistory(chatId)
            }
            markRead()
            quietly { loadReceipts() }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404) notFound = true else error = svc.repo.friendly(e)
        } catch (e: Exception) {
            error = svc.repo.friendly(e)
        } finally {
            loading = false
        }
    }

    suspend fun loadSummary() {
        summary = decode<ChatWrap>(api.get("api/chats/$chatId?device=${keys().dev.id}")).chat
    }

    suspend fun loadOlder() {
        val first = messages.firstOrNull()?.seq ?: return
        val r = decode<MessagesResponse>(api.get("api/chats/$chatId/messages?device=${keys().dev.id}&before=$first&limit=50&calls=1"))
        val older = ChatDeleteLogic.keepAfter(decryptAll(r), summary?.clearedSeq ?: 0) { it.seq }.filter { o -> messages.none { it.id == o.id } }
        messages.addAll(0, older)
        more = r.more
        resolveNotes()
        quietly { openReactions(older.map { it.id }) }
    }

    /** Nachladen ist gescheitert (Live-Ereignis/nach dem Senden) - catchUp() versucht es erneut */
    var behind by mutableStateOf(false)
        private set

    /** Liegengebliebenes Nachladen wiederholen (regelmaessig aus der Ansicht), nie mit Ausnahme */
    suspend fun catchUp(foreground: Boolean = true) {
        if (behind) behind = !quietly { loadNewer() }
        else if (foreground && FedDelivery.due(messages, lastDeliveryCheck, System.currentTimeMillis())) quietly { checkDelivery() }
    }

    /**
     * 2.3: Stand wartender eigener Nachrichten beim Server nachsehen (ab der aeltesten) - Server bis 2.3.0 melden die
     * sofortige Bestaetigung des Heims nicht als Ereignis; so heilen auch schon haengende "wird zugestellt" von selbst.
     */
    private suspend fun checkDelivery() {
        val from = FedDelivery.oldestPending(messages) ?: return
        lastDeliveryCheck = System.currentTimeMillis()
        val r = decode<MessagesResponse>(api.get("api/chats/$chatId/messages?device=${keys().dev.id}&after=${from - 1}&limit=200"))
        val server = r.messages.associate { it.id to it.fstate }
        // after=0 liefert die neuesten statt ab Anfang: dann nichts als verschwunden werten
        val gone = if (from <= 1) emptySet() else FedDelivery.gone(messages.toList(), server, r.more, r.messages.maxOfOrNull { it.seq } ?: 0L)
        if (gone.isNotEmpty()) messages.removeAll { it.id in gone }
        applyDelivery(server)
    }

    /** Zustellstand bekannter Nachrichten uebernehmen (Serverliste id -> fstate, dazu per POST bestaetigte) */
    private fun applyDelivery(server: Map<String, String?>) {
        for ((i, m) in FedDelivery.apply(messages.toList(), server, confirmed.toSet())) {
            if (i < messages.size && messages[i].id == m.id) messages[i] = m
        }
    }

    suspend fun loadNewer() {
        if (FedDelivery.due(messages, lastDeliveryCheck, System.currentTimeMillis())) quietly { checkDelivery() }
        val after = messages.lastOrNull()?.seq ?: lastSeq
        val r = decode<MessagesResponse>(api.get("api/chats/$chatId/messages?device=${keys().dev.id}&after=$after&limit=200&calls=1"))
        val newer = ChatDeleteLogic.keepAfter(decryptAll(r), summary?.clearedSeq ?: 0) { it.seq }.filter { n -> messages.none { it.id == n.id } }
        // Systemzeile "ab jetzt ueber Servergrenzen" (E5) bzw. "Stufe 3" (2.5) in einem noch als v1 geladenen Chat: Stand
        // holen und neu oeffnen
        if (!isFed && newer.any { it.sys?.type == "fed" || it.sys?.type == "crypto" }) {
            loadSummary()
            if (isFed) {
                open()
                return
            }
        }
        messages.addAll(newer)
        lastSeq = maxOf(lastSeq, r.lastSeq)
        if (newer.isNotEmpty()) {
            if (newer.any { it.fstate == FedDelivery.PENDING }) applyDelivery(emptyMap())     // per POST schon bestaetigt
            resolveNotes()
            quietly { openReactions(newer.map { it.id }) }
            markRead()
        }
    }

    private suspend fun decryptAll(r: MessagesResponse): List<ChatMessage> {
        val k = keys()
        val me = me
        // TOFU: Identitaetsschluessel der Absender merken, Wechsel melden
        val changed = HashSet(identChanged)
        withContext(Dispatchers.IO) {
            for ((uid, ip) in r.idents) if (uid != me && svc.store.observe(uid, ip) == TofuResult.CHANGED) changed.add(uid)
        }
        identChanged = changed
        idents = idents + r.idents
        val opener = MessageOpener(svc, k)
        val sum = summary
        if (sum != null && sum.isFed) {
            val fo = FedOpener(svc, k, fk)
            val ref = sum.ref!!
            return withContext(Dispatchers.Default) {
                r.messages.map { m ->
                    val mine = m.sender?.username == me
                    when (m.kind) {
                        "call" -> ChatMessage(m.seq, m.id, m.sender, m.senderDevice, m.ts, m.expiresAt, null, null, true, mine,
                            // v2: Anrufer steht als Prinzipal drin - eigener Anruf (Absender = ich) zaehlt als "von mir"
                            io.github.yggdrasil_sh.saga.call.CallLogic.parseEntry(m.bodyCt)?.let { e -> if (mine) e.copy(from = me) else e },
                            senderHandle = m.senderHandle)
                        "sys" -> ChatMessage(m.seq, m.id, m.sender, m.senderDevice, m.ts, m.expiresAt, null, null, true, false,
                            sys = ChatLogic.sys(m.bodyCt), senderHandle = m.senderHandle)
                        else -> {
                            // umgestellter Seiten-Chat (E5) bzw. auf Stufe 3 umgestellter Chat (2.5): Nachrichten ohne
                            // sender_handle bzw. bis v3.seq stammen aus der Zeit davor - weiter mit v1 entschluesseln und pruefen
                            // (chat_id, Absender-Konto, Geraete aus devices/idents)
                            val legacy = m.senderHandle.isNullOrEmpty() || (sum.v3Seq > 0 && m.seq <= sum.v3Seq)
                            val o = if (legacy) opener.open(chatId, m, r.devices, r.idents) else fo.open(ref, m)
                            if (legacy) o.content?.files?.forEach { f -> legacyFiles.add(f.id); f.th?.id?.let { legacyFiles.add(it) } }
                            if (!legacy && o.key != null) {
                                msgKeys[m.id] = o.key
                                rxRaw[m.id] = ChatX3.newest(m.rx)
                            }
                            val base = ChatMessage(
                                m.seq, m.id, m.sender, m.senderDevice, m.ts, m.expiresAt, m.replyTo, o.content, o.verified, mine,
                                ai = ChatLogic.aiCheck(o.content, m.ai), fstate = m.fstate, senderHandle = m.senderHandle, fseq = m.fseq,
                                created = m.created, legacy = legacy, editN = m.editN,
                            )
                            if (legacy) base else applyEdit(fo, ref, base, m)
                        }
                    }
                }
            }
        }
        return withContext(Dispatchers.Default) {
            r.messages.map { m ->
                // Systemzeile (2.3): Klartext vom Server - auch, falls der Chat gerade erst umgestellt wurde
                if (m.kind == "sys") {
                    return@map ChatMessage(m.seq, m.id, m.sender, m.senderDevice, m.ts, m.expiresAt, null, null, true, false,
                        sys = ChatLogic.sys(m.bodyCt), senderHandle = m.senderHandle)
                }
                if (m.kind == "call") {
                    // Anruf-Eintrag: nicht verschluesselt, nur Metadaten (wer/wann/wie lange) - nichts zu pruefen
                    val e = io.github.yggdrasil_sh.saga.call.CallLogic.parseEntry(m.bodyCt)
                    return@map ChatMessage(m.seq, m.id, m.sender, m.senderDevice, m.ts, m.expiresAt, null, null, true, e?.from == me, e)
                }
                val o = opener.open(chatId, m, r.devices, r.idents)
                ChatMessage(
                    m.seq, m.id, m.sender, m.senderDevice, m.ts, m.expiresAt, m.replyTo, o.content, o.verified, m.sender?.username == me,
                    ai = ChatLogic.aiCheck(o.content, m.ai),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 2.5: Reaktionen und Bearbeiten (Konzept B1/B2)

    /** Schluessel K geoeffneter v2-Nachrichten (Reaktionen leiten ihren Schluessel davon ab) */
    private val msgKeys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    /** Reaktionen je Nachricht, wie vom Server (ungeprueft) */
    private val rxRaw = java.util.concurrent.ConcurrentHashMap<String, List<Reaction>>()
    /** gepruefte und geoeffnete Reaktionen je Nachricht */
    var reactions by mutableStateOf<Map<String, List<OpenedReaction>>>(emptyMap())
        private set
    /** eigene Reaktion wartet aufs Senden (entprellt) - so lange zeigt die Ansicht die eigene Wahl */
    private val rxSend = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    /** Nachricht, die gerade im Eingabefeld bearbeitet wird */
    var editing by mutableStateOf<ChatMessage?>(null)

    /** Reaktionen/Bearbeiten/Sprachnachrichten hier moeglich (x3_ok, angenommen, beschreibbar) */
    val x3: Boolean get() = ChatX3.chatOk(summary)
    val x3Limits: X3Limits get() = svc.me.value?.x3Limits ?: X3Limits()
    val canReactHere: Boolean get() = x3 && ChatX3.has(svc.me.value, "react")
    val canVoiceHere: Boolean get() = x3 && ChatX3.has(svc.me.value, "voice")
    val myHandle: String get() = summary?.myHandle ?: svc.fed.myHandle.orEmpty()

    fun canReact(m: ChatMessage): Boolean =
        canReactHere && m.content != null && m.verified && !m.legacy && m.fstate == null && m.call == null && m.sys == null && msgKeys.containsKey(m.id)

    fun chipsOf(m: ChatMessage): List<ReactionChip> = ChatX3.chips(reactions[m.id].orEmpty(), myHandle)

    fun myReaction(m: ChatMessage): String? = ChatX3.mine(reactions[m.id].orEmpty(), myHandle)

    /** Name zu einem Handle ("du", Mitglied, sonst ehemaliges Mitglied = null) */
    fun nameOfHandle(h: String): String? = summary?.members?.firstOrNull { it.handle == h || it.username == h }?.let { ChatLogic.shown(it) }

    fun canEdit(m: ChatMessage): Boolean {
        if (!x3 || !ChatX3.has(svc.me.value, "edit")) return false
        val c = m.content ?: return false
        if (m.call != null || m.sys != null) return false
        return ChatX3.canEdit(
            mine = m.mine && !m.legacy && !m.senderHandle.isNullOrEmpty() && m.senderHandle == myHandle, hasText = c.text.isNotEmpty(),
            ai = m.ai != io.github.yggdrasil_sh.saga.ai.AiReplyCheck.NONE || c.ai != null, voice = c.files.any { it.isVoice },
            pending = m.fstate != null, created = m.created ?: (m.ts / 1000.0), editN = m.editN, now = System.currentTimeMillis() / 1000.0, l = x3Limits,
        )
    }

    /** Bearbeitung am Original (GET .../messages: edit) pruefen und anwenden - sonst bleibt das Original */
    private fun applyEdit(fo: FedOpener, ref: String, cm: ChatMessage, m: ApiMessage): ChatMessage {
        val e = m.edit ?: return cm
        val x = fo.openRaw(ref, e)
        if (!x.verified || x.plain == null) return cm
        val body = try {
            Json.parseToJsonElement(x.plain) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val rej = ChatX3.editCheck(cm.id, m.senderHandle, m.ai != null, m.created ?: 0.0, e.editOf ?: m.id, e.senderHandle, e.created ?: 0.0, body, x3Limits.editWindow)
        val t = ChatX3.editText(body)
        val c = cm.content
        if (rej != null || t == null || c == null) return cm
        return cm.copy(content = c.copy(text = t), editedAt = m.editedAt ?: e.created)
    }

    /** Reaktionen der Nachrichten mids pruefen und oeffnen (Hintergrund); eigene, noch nicht gesendete bleiben stehen */
    suspend fun openReactions(mids: Collection<String>) {
        val ref = summary?.ref ?: return
        val jobs = mids.mapNotNull { mid ->
            val items = rxRaw[mid].orEmpty()
            val key = msgKeys[mid] ?: return@mapNotNull null
            ReactOpen.Job(mid, key, items)
        }
        if (jobs.isEmpty()) return
        val pks = HashMap<String, ByteArray>()
        for ((h, st) in fk) {
            if (st.trust == TofuV2.REJECT) continue
            for (d in st.keys?.devices.orEmpty()) if (!d.revoked) unb64(d.signPub, 32)?.let { pks["$h|${d.id}"] = it }
        }
        val res = withContext(Dispatchers.Default) { ReactOpen.run(svc.react, jobs, pks, ref) }
        var next = reactions
        for ((mid, list) in res) if (!rxSend.containsKey(mid)) next = next + (mid to list)
        if (next != reactions) reactions = next
    }

    /** Reaktion setzen bzw. zuruecknehmen (gleiches Emoji nochmal): sofort zeigen, nach 500 ms nur die letzte Wahl senden */
    fun react(scope: kotlinx.coroutines.CoroutineScope, m: ChatMessage, picked: String) {
        if (!canReact(m) || !ChatX3.emojiOk(picked)) return
        val e = ChatX3.toggle(myReaction(m), picked)
        val me = myHandle
        val mid = m.id
        reactions = reactions + (mid to ChatX3.applyLocal(reactions[mid].orEmpty(), me, e, System.currentTimeMillis()))
        if (e.isNotEmpty()) svc.rememberEmoji(e)
        rxSend.remove(mid)?.cancel()
        rxSend[mid] = scope.launch {
            kotlinx.coroutines.delay(ChatX3.DEBOUNCE_MS)
            sendReaction(mid, e)
        }
    }

    private suspend fun sendReaction(mid: String, e: String) {
        try {
            val k = keys()
            val ref = summary?.ref ?: return
            val key = msgKeys[mid] ?: return
            val r = withContext(Dispatchers.Default) { svc.react.encrypt(key, ref, mid, myHandle, k.dev.id, k.dev.signSk, e, System.currentTimeMillis()) }
            val body = buildJsonObject {
                put("dev", r.dev)
                put("ts", r.ts)
                put("ct", r.ct)
                put("sig", r.sig)
            }
            try {
                api.request("PUT", "api/chats/$chatId/messages/$mid/reaction", body)
                rxRaw[mid] = ChatX3.merge(rxRaw[mid].orEmpty(), listOf(r))
            } catch (x: CancellationException) {
                throw x
            } catch (x: Exception) {
                svc.repo.notify(UiText.Res(R.string.x3_react_failed, svc.repo.friendly(x)))
            }
        } catch (x: CancellationException) {
            throw x
        } catch (_: Exception) {
        } finally {
            rxSend.remove(mid)
        }
        quietly { openReactions(listOf(mid)) }               // Stand des Servers (bzw. vor dem Fehler) zeigen
    }

    /** Live chat.react: gebuendelte Eintraege bzw. nur Hinweis (more) -> nachladen */
    private suspend fun liveReactions(ev: LiveEvent) {
        if (ev.bool("more")) {
            val known = messages.map { it.id }.toHashSet()
            val mids = (ev.data["mids"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }.filter { it in known }.take(100)
            if (mids.isEmpty()) return
            val o = Json.parseToJsonElement(api.get("api/chats/$chatId/reactions?mids=" + mids.joinToString(","))) as? JsonObject ?: return
            val all = o["reactions"] as? JsonObject ?: return
            for ((mid, v) in all) {
                rxRaw[mid] = ChatX3.newest(try {
                    json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Reaction.serializer()), v)
                } catch (_: Exception) {
                    emptyList()
                })
            }
            openReactions(all.keys)
            return
        }
        val items = (ev.data["items"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        val per = HashMap<String, MutableList<Reaction>>()
        for (x in items) {
            val o = x as? JsonObject ?: continue
            val mid = (o["id"] as? JsonPrimitive)?.content ?: continue
            val r = try {
                json.decodeFromJsonElement(Reaction.serializer(), o)
            } catch (_: Exception) {
                continue
            }
            per.getOrPut(mid) { ArrayList() }.add(r)
        }
        for ((mid, list) in per) rxRaw[mid] = ChatX3.merge(rxRaw[mid].orEmpty(), list)
        openReactions(per.keys)
    }

    /** Bearbeitung senden (normale v2-Nachricht kind "edit" mit edit_of); Fehler -> Ausnahme */
    suspend fun sendEdit(m: ChatMessage, text: String) {
        val t = text.trim()
        if (!canEdit(m) || t.isEmpty()) throw UiTextException(UiText.Res(R.string.x3_edit_not_possible))
        if (t == m.content?.text) return
        val k = keys()
        sending = true
        try {
            postV2(k, ChatX3.editBody(m.id, t), null, emptyList(), null, null, "edit", editOf = m.id)
            val i = messages.indexOfFirst { it.id == m.id }
            if (i >= 0) messages[i] = messages[i].let { x ->
                x.copy(content = x.content?.copy(text = t), editedAt = System.currentTimeMillis() / 1000.0, editN = x.editN + 1)
            }
        } finally {
            sending = false
        }
    }

    /** Live chat.edited: Original mit der neuesten Bearbeitung nachladen */
    private suspend fun liveEdited(ev: LiveEvent) {
        val id = ev.str("id") ?: return
        val seq = messages.firstOrNull { it.id == id }?.seq ?: return
        val r = decode<MessagesResponse>(api.get("api/chats/$chatId/messages?device=${keys().dev.id}&after=${seq - 1}&limit=1&calls=1"))
        val got = decryptAll(r).firstOrNull { it.id == id } ?: return
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = got
        if (editing?.id == id) editing = got
    }

    // ---------------------------------------------------------------- 2.5: Sprachnachrichten (Konzept B3)

    /** naechste Sprachnachricht (zeitlich) nach der mit dieser Datei - startet nach dem Ende automatisch */
    fun nextVoice(fileId: String): ChatFile? {
        val all = messages.toList()
        val i = all.indexOfFirst { m -> m.content?.files?.any { it.id == fileId } == true }
        if (i < 0) return null
        for (k in i + 1 until all.size) all[k].content?.files?.firstOrNull { it.isVoice && !it.unsupported }?.let { return it }
        return null
    }

    fun watchVoiceCalls() = svc.watchCallsForVoice()

    /** Aufnahme verschluesseln, hochladen (saga-file/v2, sonst v1) und als eigene Nachricht senden */
    suspend fun sendVoice(v: io.github.yggdrasil_sh.saga.chat.voice.VoiceResult) {
        val k = keys()
        val lim = svc.me.value?.limits ?: ChatLimits()
        if (v.ogg.size > x3Limits.voiceMaxBytes) throw UiTextException(UiText.Res(R.string.voice_too_big))
        sending = true
        try {
            val cid = cryptoId
            val file = if (lim.file2) {
                val key = svc.crypto.keygen()
                val prefix = svc.crypto.random(16)
                val src = BytesPlainSource(v.ogg)
                val writer = FileV2Writer(svc.crypto, key, prefix, src, FileV2.CS)
                val http = io.github.yggdrasil_sh.saga.chat.video.ApiUploadHttp(api)
                val c = http.create(chatId, writer.blobSize)
                val part = minOf(lim.uploadPart.takeIf { it > 0 } ?: (8L shl 20), 8L shl 20).coerceAtLeast(64L * 1024)
                val id = try {
                    withContext(Dispatchers.IO) { io.github.yggdrasil_sh.saga.chat.video.V2Uploader(http, writer, part).upload(c.upload, c.url, c.offset) }
                } catch (e: Exception) {
                    withContext(kotlinx.coroutines.NonCancellable) { quietly { http.delete(c.upload) } }
                    throw e
                }
                io.github.yggdrasil_sh.saga.chat.voice.VoiceLogic.file(id, b64(key), v.ogg.size.toLong(), true, FileV2.CS, v.dur, v.wf)
            } else {
                if (v.ogg.size > lim.fileMax) throw UiTextException(UiText.Res(R.string.voice_too_big))
                val (blob, key) = withContext(Dispatchers.Default) { svc.encryptFileAny(cid, v.ogg) }
                val id = decode<FileUploaded>(api.postBytes("api/chats/$chatId/files", blob)).id
                withContext(Dispatchers.IO) { svc.blobs.put(ChatBlobCache.key(chatId, id), blob) }
                io.github.yggdrasil_sh.saga.chat.voice.VoiceLogic.file(id, b64(key), v.ogg.size.toLong(), false, 0, v.dur, v.wf)
            }
            postEncrypted(k, ChatLogic.content("", listOf(file), emptyList()), null, "msg", listOf(file.id))
            behind = !quietly { loadNewer() }
        } finally {
            sending = false
        }
    }

    /** Identitaetsschluessel aller Mitglieder (Sicherheitsnummer, TOFU) */
    suspend fun loadIdents() {
        if (isFed) {
            quietly { loadFedKeys(false) }
            return
        }
        val mem = summary?.members?.map { it.username }.orEmpty().filter { it.isNotEmpty() }
        if (mem.isEmpty()) return
        try {
            // nur Mitglieder (Server 2.4: Schluessel nur fuer Personen mit gemeinsamem Chat, Antwort unter der angefragten Kennung)
            val q = mem.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") }
            val o = Json.parseToJsonElement(api.get("api/chat/keys?users=$q")) as? JsonObject ?: return
            val out = HashMap(idents)
            val changed = HashSet(identChanged)
            for (u in mem) {
                val ip = (ChatLogic.keyEntry(o, u)?.get("ident_pub") as? JsonPrimitive)?.content ?: continue
                out[u] = ip
                if (u != me && withContext(Dispatchers.IO) { svc.store.observe(u, ip) } == TofuResult.CHANGED) changed.add(u)
            }
            idents = out
            identChanged = changed
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** 2.3: Handles aller Mitglieder (auch der eigene) */
    private fun handles(): List<String> =
        (summary?.members.orEmpty().mapNotNull { it.handle } + listOfNotNull(summary?.myHandle)).filter { it.isNotEmpty() }.distinct()

    /** 2.3: Schluessel der Mitglieder holen und selbst pruefen (TOFU) -> Stand fuer Kopf, Warnungen, Senden */
    suspend fun loadFedKeys(fresh: Boolean): Map<String, FedKeyState> {
        val r = svc.fed.keys(handles(), fresh)
        fk = r
        return r
    }

    /** 2.3: Personen mit Warnung/Blockade/ungueltigen Schluesseln (Mitglied -> Stand) - Senden und Anrufen gesperrt */
    val fedIssues: List<Pair<ChatPerson, FedKeyState>>
        get() = if (!isFed) emptyList() else summary?.members.orEmpty().filter { !it.me && !it.handle.isNullOrEmpty() }.mapNotNull { m ->
            fk[m.handle]?.takeIf { it.trust == TofuV2.WARN || it.trust == TofuV2.BLOCK || it.trust == TofuV2.REJECT }?.let { m to it }
        }

    private suspend fun resolveNotes() {
        val want = messages.flatMap { m -> (m.content?.notes.orEmpty() + ChatFormat.noteRefs(m.content?.text.orEmpty())) }
            .filter { it > 0 && it !in notes }.distinct()
        if (want.isEmpty()) return
        try {
            val got = svc.noteTitles(want)
            notes = notes + want.associateWith { got[it] }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun markRead() {
        val seq = messages.lastOrNull()?.seq ?: return
        // 2.4: Gelesen-Bestaetigung zusammen mit dem Lese-Zeiger - nur sichtbar gewesene Nachrichten, App im Vordergrund,
        // hoechstens eine je Chat und receipt_gap (sonst holt receiptTick() sie nach)
        val want = receiptWant()?.takeIf { ReceiptLogic.wait(System.currentTimeMillis(), receiptAt, receiptGapMs) == 0L }
        val keys = want?.let {
            try {
                receiptKeys(it.first, it.second)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null                                                    // Schluessel nicht ladbar: spaeter erneut
            }
        }
        if (want != null) receiptAt = System.currentTimeMillis()
        try {
            val body = buildJsonObject {
                put("seq", seq)
                if (!keys.isNullOrEmpty()) putJsonObject("receipt") { putJsonObject("keys") { keys.forEach { (r, v) -> put(r, v) } } }
            }
            val o = Json.parseToJsonElement(api.post("api/chats/$chatId/read", body)) as? JsonObject
            (o?.get("unread") as? JsonPrimitive)?.content?.toIntOrNull()?.let { svc.repo.live.chatUnread.value = it }
            if (want != null && keys != null && keys.isEmpty()) setSent(want.second)   // niemand zu benachrichtigen
            if (!keys.isNullOrEmpty() && want != null) when ((o?.get("receipt") as? JsonPrimitive)?.content) {
                "ok", "none", "bad", null -> setSent(want.second)
                "off" -> svc.applyPrivacy((svc.me.value?.privacy ?: ChatPrivacy()).copy(receipts = false))
                "rate" -> receiptAt = System.currentTimeMillis() + 3_000                   // spaeter erneut (nach ~5 s)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- Gelesen-Bestaetigungen (2.4)

    /** gepruefte Bestaetigungen der anderen: Benutzername (bzw. Prinzipal) -> gelesen bis */
    var reads by mutableStateOf<Map<String, ReadMark>>(emptyMap())
        private set
    /** alle bekannten Bestaetigungen je Person (Lesezeit einer Nachricht = erste, die sie abdeckt), auf dem Geraet gemerkt */
    private var readHist by mutableStateOf<Map<String, List<ReadMark>>>(emptyMap())
    /** zuletzt gemeldete sichtbare Eintraege (Nachrichten-IDs) - neu ausgewertet, wenn Nachrichten nachkommen/sich aendern */
    private var visible: Set<String> = emptySet()

    /** neue Bestaetigungen uebernehmen und in der Lesezeit-Historie festhalten */
    private suspend fun applyReads(next: Map<String, ReadMark>) {
        reads = next
        var hist = readHist
        for ((who, mark) in next) {
            val old = hist[who].orEmpty()
            val merged = ReceiptHistory.merge(old, mark)
            if (merged != old) hist = hist + (who to merged)
        }
        if (hist != readHist) {
            readHist = hist
            withContext(Dispatchers.IO) { svc.store.saveReceiptHistory(chatId, hist) }
        }
    }

    private suspend fun setSent(n: Long) {
        if (n <= sentN) return
        sentN = n
        withContext(Dispatchers.IO) { svc.store.saveReceiptSent(chatId, n) }
    }
    /** App im Vordergrund und dieser Chat sichtbar (aus der Ansicht, Lebenszyklus RESUMED) */
    var foreground = false
    /** Position der neuesten Nachricht anderer, die auf dem Bildschirm zu sehen war */
    private var seenN = 0L
    /** zuletzt bestaetigte Position */
    private var sentN = 0L
    private var receiptAt = 0L
    /** v1: Empfaenger (Backup-Schluessel, Geraete) - fuer Versiegeln und Pruefen */
    private var rc: List<Recipient>? = null
    private val receiptGapMs: Long get() = ((svc.me.value?.statusLimits?.receiptGap ?: 2.0) * 1000).toLong()

    /** Server kann Gelesen-Bestaetigungen (Haken ✓ zeigen) */
    val receiptsFeature: Boolean get() = ReceiptLogic.has(svc.me.value, "receipts")
    /** in diesem Chat senden und anzeigen (gegenseitig) */
    val receiptsOn: Boolean get() = ReceiptLogic.enabled(svc.me.value, summary)

    fun tickOf(m: ChatMessage): Tick = ReceiptLogic.tick(m, receiptsFeature, receiptsOn, isFed, summary?.members.orEmpty(), reads, messages)

    /** Leser einer Nachricht; die Zeit ist die der ERSTEN Bestaetigung, die sie abdeckt (aus der Bestaetigung, nicht die lokale Uhr) */
    fun readersOf(m: ChatMessage): List<Pair<ChatPerson, ReadMark>> =
        if (!receiptsOn) emptyList() else ReceiptLogic.readers(m, isFed, summary?.members.orEmpty(), reads, messages).map { (p, r) ->
            val n = ReceiptLogic.ord(m, isFed)
            val ts = readHist[p.username]?.let { ReceiptHistory.readAt(it, n) }
            p to (if (ts != null) r.copy(ts = ts) else r)
        }

    /** sichtbare Eintraege der Liste (Schluessel = Nachrichten-IDs), nur im Vordergrund gemeldet */
    fun seen(ids: Collection<String>) {
        if (!foreground || ids.isEmpty()) return
        visible = ids.toHashSet()
        updateSeen()
    }

    /**
     * Sichtbares neu auswerten: Bild/Video/Datei, deren Zeile schon zu sehen ist, aber deren Position erst spaeter
     * ankommt (Nachladen, fseq), zaehlen sonst nie als gesehen - der Absender bliebe bei ✓ (TT 2.4 P-06)
     */
    private fun updateSeen() {
        if (!foreground || visible.isEmpty()) return
        val n = messages.filter { it.id in visible && ReceiptLogic.fromOthers(it) }.maxOfOrNull { ReceiptLogic.ord(it, isFed) } ?: return
        if (n > seenN) seenN = n
    }

    private fun receiptWant(): Pair<String, Long>? {
        if (!foreground || !receiptsOn || svc.keys() == null) return null
        updateSeen()
        return ReceiptLogic.want(messages, isFed, seenN, sentN)
    }

    /** Bestaetigung faellig (sichtbar, neu, Abstand eingehalten) - aus der Ansicht im Sekundentakt */
    fun receiptDue(): Boolean = receiptWant() != null && ReceiptLogic.wait(System.currentTimeMillis(), receiptAt, receiptGapMs) == 0L

    private suspend fun recipients(fresh: Boolean = false): List<Recipient> {
        rc?.takeIf { !fresh }?.let { return it }
        val r = decode<RecipientsResponse>(api.get("api/chats/$chatId/recipients")).recipients
        rc = r
        return r
    }

    /** je anderem Mitglied mit (gepruefen) Schluesseln: "backup:<name bzw. handle>" -> versiegelte Bestaetigung (hoechstens 50) */
    private suspend fun receiptKeys(id: String, n: Long): Map<String, String> {
        val k = keys()
        val sum = summary ?: return emptyMap()
        val body = ReceiptLogic.body(id, n, System.currentTimeMillis())
        val rcpt = svc.receipts
        val out = LinkedHashMap<String, String>()
        if (sum.isFed) {
            val reader = sum.myHandle ?: svc.fed.myHandle ?: return emptyMap()
            val got = if (fk.isEmpty()) loadFedKeys(false) else fk
            withContext(Dispatchers.Default) {
                for (m in sum.members) {
                    val h = m.handle
                    if (m.me || h.isNullOrEmpty() || h == reader) continue
                    val st = got[h] ?: continue
                    val ks = st.keys ?: continue
                    if (st.trust == TofuV2.REJECT || st.trust == TofuV2.BLOCK) continue
                    out["backup:$h"] = rcpt.seal(sum.ref!!, reader, k.dev, body, true, ks.backupPk)
                }
            }
        } else {
            val list = recipients()
            val known = withContext(Dispatchers.IO) { svc.store.known() }
            withContext(Dispatchers.Default) {
                for (r in list) {
                    val ip = r.identPub
                    if (!r.ready || r.username == me || ip == null) continue
                    val kn = known[r.username]
                    if (kn != null && (kn.k != ip || kn.changed)) continue          // Sicherheitsnummer geaendert: nichts schicken
                    if (!svc.crypto.verifyBackup(r.username, unb64(ip, 32), r.backupPub, r.backupSig)) continue
                    val pk = unb64(r.backupPub, 32) ?: continue
                    out["backup:" + r.username] = rcpt.seal(chatId.toString(), me, k.dev, body, false, pk)
                }
            }
        }
        return out.entries.take(50).associate { it.key to it.value }
    }

    /** Beim Oeffnen bzw. nach "more"/Einstellungswechsel: neueste Bestaetigung je anderem Mitglied holen und pruefen */
    suspend fun loadReceipts() {
        if (!receiptsOn) {
            reads = emptyMap()
            return
        }
        if (isFed) {
            if (fk.isEmpty()) quietly { loadFedKeys(false) }
        } else {
            quietly { recipients(fresh = true) }
        }
        val o = Json.parseToJsonElement(api.get("api/chats/$chatId/receipts")) as? JsonObject ?: return
        if ((o["enabled"] as? JsonPrimitive)?.content == "false") {
            reads = emptyMap()
            return
        }
        applyReads(takeReceipts(reads, ReceiptLogic.parseList(o["receipts"])))
    }

    /** Liste oeffnen und pruefen (Geraet gehoert zur Person, Signatur passt); nicht pruefbar -> ignorieren */
    private suspend fun takeReceipts(base: Map<String, ReadMark>, list: List<SealedReceipt>): Map<String, ReadMark> {
        val k = svc.keys() ?: return base
        val sum = summary ?: return base
        val rcpt = svc.receipts
        val people = rc.orEmpty()
        val keysV2 = fk
        return withContext(Dispatchers.Default) {
            val out = HashMap(base)
            for (x in list) {
                try {
                    val r = rcpt.open(x.sealed, k.ident.backupPk, k.ident.backupSk)
                    val signPk = if (sum.isFed) {
                        val h = x.handle ?: continue
                        if (sum.members.none { it.username == x.from && it.handle == h && !it.me }) continue
                        val st = keysV2[h]?.takeIf { it.trust != TofuV2.REJECT } ?: continue
                        unb64(st.keys?.device(r.dev)?.signPub, 32)
                    } else {
                        val p = people.firstOrNull { it.username == x.from && it.ready } ?: continue
                        val d = p.devices.firstOrNull { it.id == r.dev } ?: continue
                        if (!svc.crypto.verifyDevice(p.username, unb64(p.identPub, 32), d.id, d.encPub, d.signPub, d.sig)) continue
                        unb64(d.signPub, 32)
                    }
                    val reader = if (sum.isFed) x.handle!! else x.from
                    if (!rcpt.verify(sum.cryptoId, reader, r, signPk, sum.isFed)) continue
                    val mark = ReceiptLogic.parseBody(r.body) ?: continue
                    if (ReceiptLogic.take(out[x.from], mark)) out[x.from] = mark
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            out
        }
    }

    // ---------------------------------------------------------------- Senden

    /**
     * Text (+ Anhaenge, Seitenverweise) senden. Vorher jedes Empfaenger-Geraet (Zertifikat) und jeden Backup-Schluessel
     * gegen ident_pub pruefen - ungueltig -> nicht senden. 409 {recipients} -> neu versiegeln und einmal erneut senden.
     */
    suspend fun send(text: String, files: List<PendingFile> = emptyList(), noteIds: List<Long> = emptyList()): String {
        val k = keys()
        sending = true
        try {
            val limits = svc.me.value?.limits ?: ChatLimits()
            if (files.size > limits.filesPerMsg) throw UiTextException(UiText.Plural(R.plurals.chat_too_many_files, limits.filesPerMsg))
            files.forEach { f ->
                if (f.data.size > limits.fileMax) throw UiTextException(UiText.Res(R.string.chat_file_too_big, limits.fileMax / 1048576))
            }
            // 2.1-c: je Bild ein Vorschaubild (eigene Datei, eigener Schluessel) - so viele, wie Datei-Kennungen erlaubt sind
            var thumbsLeft = ChatImages.thumbBudget(files.size, files.count { it.mime.startsWith("image/") }, limits.filesPerMsg, limits.fileIdsPerMsg)
            val cid = cryptoId                                          // v1 chat_id, v2 chat_ref
            val uploaded = files.map { f ->
                val (blob, key) = withContext(Dispatchers.Default) { svc.encryptFileAny(cid, f.data) }
                val id = decode<FileUploaded>(api.postBytes("api/chats/$chatId/files", blob)).id
                var file = ChatFile(id, b64(key), f.name, f.mime, f.data.size.toLong())
                if (f.mime.startsWith("image/")) {
                    val t = withContext(Dispatchers.Default) {
                        try {
                            ChatImages.makeThumb(f.data)
                        } catch (_: Throwable) {
                            null                                 // Format unbekannt/zu gross: ohne Vorschaubild senden
                        }
                    }
                    if (t != null) {
                        file = file.copy(w = t.origW, h = t.origH)
                        if (thumbsLeft > 0) {
                            thumbsLeft--
                            val (tblob, tkey) = withContext(Dispatchers.Default) { svc.encryptFileAny(cid, t.jpeg) }
                            val tid = decode<FileUploaded>(api.postBytes("api/chats/$chatId/files", tblob)).id
                            file = file.copy(th = ChatThumb(tid, b64(tkey), "image/jpeg", t.w, t.h, t.jpeg.size.toLong()))
                            withContext(Dispatchers.IO) { svc.blobs.put(ChatBlobCache.key(chatId, tid), tblob) }      // eigenes Bild sofort aus dem Cache
                        }
                    }
                }
                file
            }
            val notesAll = (noteIds + ChatFormat.noteRefs(text)).distinct()
            val plain = ChatLogic.content(text, uploaded, notesAll)
            // alle Kennungen (Original + Vorschaubild), sonst raeumt der Server das Vorschaubild als verwaist weg
            val id = postEncrypted(k, plain, replyTo?.id, "msg", ChatLogic.sendFileIds(uploaded))
            replyTo = null
            // gesendet ist gesendet: scheitert nur das Nachladen, holt catchUp() es nach (BUG-066, kein Doppelversand)
            behind = !quietly { loadNewer() }
            return id
        } finally {
            sending = false
        }
    }

    // ---------------------------------------------------------------- KI im Chat (2.1, nur auf Zuruf)

    /** laufende Frage an die KI (Anzeige "… denkt nach" bzw. mitlaufender Text) */
    var aiPending by mutableStateOf<AiPending?>(null)
        private set

    /** darf dieses Konto die KI hier erlauben/verbieten (null = noch nicht geprueft) */
    val aiMayToggle: Boolean?
        get() = summary?.let { sum ->
            // Direkt-Chat/freie Gruppe: sofort aus der Zusammenfassung; Gruppe/Seiten-Chat: nach checkAiToggle()
            if (sum.kind == "dm" || sum.kind == "free") io.github.yggdrasil_sh.saga.ai.AiChatLogic.mayToggle(sum.kind, sum.role, null, null)
            else aiToggleChecked
        }
    private var aiToggleChecked by mutableStateOf<Boolean?>(null)

    /** Berechtigung fuer den Umschalter pruefen (Gruppe: Saga-Admin, Seiten-Chat: Eigentuemer der Seite) */
    suspend fun checkAiToggle() {
        val sum = summary ?: return
        if (sum.kind == "dm" || sum.kind == "free") return
        val admin = if (sum.kind == "group") svc.repo.ai.isAdmin() else null
        val owner = if (sum.kind == "page" && sum.noteId != null) try {
            val p = svc.repo.loadPage(sum.noteId)
            val role = (p["role"] as? JsonPrimitive)?.content
            role == null || role == "owner"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } else null
        aiToggleChecked = io.github.yggdrasil_sh.saga.ai.AiChatLogic.mayToggle(sum.kind, sum.role, admin, owner)
    }

    /** PUT /api/chats/{cid}/ai {"allowed"} -> {"ai"} */
    suspend fun setAi(allowed: Boolean) {
        val o = Json.parseToJsonElement(api.request("PUT", "api/chats/$chatId/ai", buildJsonObject { put("allowed", allowed) })) as? JsonObject
        val on = (o?.get("ai") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: allowed
        summary = summary?.copy(ai = on)
    }

    /** Verlauf fuer die KI: hoechstens 12 Textnachrichten vor der Frage; eigene KI-Antworten mit dem Namen der KI */
    fun aiHistory(questionId: String, aiName: String): List<io.github.yggdrasil_sh.saga.ai.AiChatLogic.Line> =
        aiHistoryOf(messages.toList(), questionId, aiName)

    private fun aiHistoryOf(msgs: List<ChatMessage>, questionId: String, aiName: String): List<io.github.yggdrasil_sh.saga.ai.AiChatLogic.Line> =
        io.github.yggdrasil_sh.saga.ai.AiChatLogic.history(
            msgs.filter { it.call == null }.map { m ->
                val name = if (m.ai == io.github.yggdrasil_sh.saga.ai.AiReplyCheck.OK) aiName else m.sender?.name?.ifBlank { null } ?: m.sender?.username.orEmpty()
                Triple(m.id, name, m.content?.text)
            },
            questionId,
        )

    /**
     * Frage an die KI (die Nachricht mit @Name ist schon normal verschluesselt gesendet): Frage + Verlauf an den Server,
     * Antwort mitlaufend zeigen, dann als normale verschluesselte Nachricht an alle senden (mit ai_reply-Beleg).
     * Fehler -> UiTextException (Aufrufer meldet).
     */
    suspend fun askAi(question: String, questionId: String, aiName: String) {
        if (aiPending != null) return
        val p = AiPending(questionId)
        aiPending = p
        try {
            var done: io.github.yggdrasil_sh.saga.ai.AiDone? = null
            var err: UiText? = null
            // Verlauf abseits des Main-Threads zusammenstellen; der Strom selbst liest auf Dispatchers.IO (AiService.stream)
            val snapshot = messages.toList()
            val history = withContext(Dispatchers.Default) { aiHistoryOf(snapshot, questionId, aiName) }
            svc.repo.ai.chatAsk(chatId, io.github.yggdrasil_sh.saga.ai.AiChatLogic.question(question), questionId, history).collect { c ->
                when (c) {
                    is io.github.yggdrasil_sh.saga.ai.AiChunk.Delta -> p.text += c.text
                    io.github.yggdrasil_sh.saga.ai.AiChunk.Reset -> p.text = ""
                    is io.github.yggdrasil_sh.saga.ai.AiChunk.Done -> done = c.done
                    is io.github.yggdrasil_sh.saga.ai.AiChunk.Failed -> err = c.error
                    is io.github.yggdrasil_sh.saga.ai.AiChunk.Sources -> {}
                }
            }
            err?.let { throw UiTextException(it) }
            val d = done
            val text = d?.text?.trim().orEmpty()
            val rid = d?.replyId
            if (d == null || text.isEmpty() || rid.isNullOrEmpty()) throw UiTextException(UiText.Res(R.string.chat_ai_no_answer))
            d.quotaLeft?.let { q -> svc.repo.ai.status.value?.let { st -> svc.repo.ai.status.value = st.copy(quotaLeft = q) } }
            postEncrypted(keys(), ChatLogic.aiReplyContent(text, rid, aiName), questionId, "msg", emptyList(), aiReply = rid,
                aiHash = d.replySha?.ifEmpty { null } ?: FedFormat.aiHash(text))
            behind = !quietly { loadNewer() }
        } finally {
            aiPending = null
        }
    }

    private suspend fun postEncrypted(
        k: LocalChatKeys, plain: String, reply: String?, kind: String, fileIds: List<String>, aiReply: String? = null, aiHash: String? = null,
    ): String {
        if (isFed) return postV2(k, plain, reply, fileIds, aiReply, aiHash, kind)
        var rc = decode<RecipientsResponse>(api.get("api/chats/$chatId/recipients")).recipients
        for (attempt in 0..1) {
            val enc = withContext(Dispatchers.Default) {
                val keys = recipientKeys(rc) ?: throw UiTextException(UiText.Res(R.string.chat_bad_recipient))
                svc.crypto.encrypt(chatId.toString(), k.uid, k.dev, plain, keys, replyTo = reply)
            }
            val body = buildJsonObject {
                put("id", enc.id)
                put("ts", enc.ts)
                put("sender_device", k.dev.id)
                put("body_ct", enc.bodyCt)
                put("sig", enc.sig)
                if (reply != null) put("reply_to", reply)
                if (kind != "msg") put("kind", kind)
                putJsonObject("keys") { enc.keys.forEach { (r, v) -> put(r, v) } }
                put("files", buildJsonArray { fileIds.forEach { add(JsonPrimitive(it)) } })
                // 2.1: Beleg der KI-Antwort (nicht signiert, nur Verweis; der Server prueft Chat/Konto/Alter/einmalig)
                if (aiReply != null) put("ai_reply", aiReply)
            }
            try {
                api.post("api/chats/$chatId/messages", body)
                return enc.id
            } catch (e: ApiException) {
                // 2.5: Chat wurde gerade auf Stufe 3 umgestellt - Stand holen und im Format v2 senden
                if (e.code == 409 && e.body.contains("\"chat_changed\"")) {
                    loadSummary()
                    quietly { loadFedKeys(true) }
                    if (isFed) return postV2(k, plain, reply, fileIds, aiReply, aiHash, kind)
                    throw e
                }
                if (e.code != 409 || attempt == 1) throw e
                val o = try {
                    json.decodeFromString(RecipientsResponse.serializer(), e.body)
                } catch (_: Exception) {
                    throw e
                }
                if (o.recipients.isEmpty()) throw e
                rc = o.recipients                            // Mitglieder/Geraete geaendert: neu versiegeln
            }
        }
        throw IllegalStateException("unreachable")
    }

    /** 2.3: letzte Sendung wartet beim eigenen Server (anderer Server gerade nicht erreichbar) */
    var lastPending by mutableStateOf(false)
        private set

    /**
     * 2.3 Senden in Chats ueber Servergrenzen (saga-chat/v2): Empfaenger = aktive Geraete + backup:<handle> aus den SELBST
     * geprueften Schluesseln aller Mitglieder; Warnung/Blockade/ungueltig -> nicht senden. 409 {handles} -> Schluessel
     * frisch holen und einmal neu versiegeln; 403 (Geraet noch ohne Zertifikat v2) -> Zertifikate nachziehen, einmal neu.
     */
    private suspend fun postV2(
        k: LocalChatKeys, plain: String, reply: String?, fileIds: List<String>, aiReply: String?, aiHash: String?, kind: String = "msg",
        editOf: String? = null,
    ): String {
        val sum = summary ?: throw UiTextException(UiText.Res(R.string.error_generic))
        val ref = sum.ref ?: throw UiTextException(UiText.Res(R.string.error_generic))
        var fresh = false
        var certsTried = false
        for (attempt in 0..2) {
            // Mitglieder koennen sich geaendert haben (Gruppe/Seiten-Chat, E5): nach 409 erst die aktuelle Liste, dann Schluessel
            if (fresh) quietly { loadSummary() }
            val hs = handles()
            val got = loadFedKeys(fresh)
            fedBlock(got)?.let { throw it }
            val myHandle = sum.myHandle ?: svc.fed.myHandle
            val mine = myHandle?.let { got[it] }
            if (myHandle == null || mine?.keys == null || mine.trust != TofuV2.TRUST) {
                if (!certsTried) {
                    certsTried = true
                    svc.fed.certs()
                    fresh = true
                    continue
                }
                throw UiTextException(UiText.Res(R.string.fed_keys_not_ready))
            }
            val enc = withContext(Dispatchers.Default) {
                val ents = hs.mapNotNull { got[it]?.keys }
                svc.fed.crypto.encrypt(ref, myHandle, k.dev, plain, FedFormat.recipients(ents), reply, aiHash)
            }
            val body = buildJsonObject {
                put("v", 2)
                put("id", enc.id)
                put("ts", enc.ts)
                put("sender_device", k.dev.id)
                put("body_ct", enc.bodyCt)
                put("sig", enc.sig)
                if (reply != null) put("reply_to", reply)
                // Gruppenname (E5): wie v1 als verschluesselte Nachricht kind 'title', in v2 mit denselben Feldern
                if (kind != "msg") put("kind", kind)
                if (aiHash != null) put("ai", aiHash)
                if (aiReply != null) put("ai_reply", aiReply)
                // 2.5: Bearbeitung - Original im Klartext (der Server prueft Absender, Fenster und Anzahl)
                if (editOf != null) put("edit_of", editOf)
                putJsonObject("keys") { enc.keys.forEach { (r, v) -> put(r, v) } }
                put("files", buildJsonArray { fileIds.forEach { add(JsonPrimitive(it)) } })
            }
            try {
                val o = Json.parseToJsonElement(api.post("api/chats/$chatId/messages", body)) as? JsonObject
                lastPending = (o?.get("pending") as? JsonPrimitive)?.content == "true"
                if (lastPending) svc.repo.notify(UiText.Res(R.string.fed_pending_sent))
                if (FedDelivery.confirmedByPost(lastPending)) {
                    // vom Heim bestaetigt: eine schon (ueber das Live-Ereignis) mit ⏳ geladene Kopie gleich als zugestellt zeigen
                    confirmed.add(enc.id)
                    applyDelivery(emptyMap())
                }
                return enc.id
            } catch (e: ApiException) {
                val o = try {
                    Json.parseToJsonElement(e.body) as? JsonObject
                } catch (_: Exception) {
                    null
                }
                if (e.code == 409 && o?.containsKey("handles") == true && !fresh) {
                    fresh = true                                     // Mitglieder/Geraete geaendert: frisch holen, neu versiegeln
                    continue
                }
                if (e.code == 403 && !certsTried) {
                    certsTried = true                                // Geraet ohne Zertifikat v2: nachziehen und einmal neu
                    svc.fed.certs()
                    fresh = true
                    continue
                }
                throw e
            }
        }
        throw UiTextException(UiText.Res(R.string.fed_keys_not_ready))
    }

    /** Warnung/Blockade/ungueltig bei einem Mitglied -> Fehler mit Namen (sonst null) */
    private fun fedBlock(got: Map<String, FedKeyState>): UiTextException? {
        val bad = summary?.members.orEmpty().filter { m ->
            !m.me && m.handle != null && got[m.handle]?.trust.let { it == TofuV2.WARN || it == TofuV2.BLOCK || it == TofuV2.REJECT }
        }
        if (bad.isEmpty()) return null
        return UiTextException(UiText.Res(R.string.chat_safety_send_blocked, bad.joinToString(", ") { it.name.ifEmpty { it.label ?: it.username } }))
    }

    /** 2.3: nicht zugestellte eigene Nachricht neu verschluesseln und senden, die alte loeschen */
    suspend fun resend(m: ChatMessage) {
        val c = m.content ?: return
        val k = keys()
        sending = true
        try {
            // Anhaenge bleiben beim Server an die alte Nachricht gebunden (dieselben IDs -> 400 "Anhang nicht gefunden"):
            // denselben Ciphertext (gleicher Chat, gleicher Schluessel) als neue Dateien hochladen
            val files = c.files.map { f ->
                val th = f.th?.let { t -> if (t.id.isEmpty()) t else t.copy(id = reupload(t.id)) }
                f.copy(id = if (f.isV2) reuploadV2(f) else reupload(f.id), th = th)
            }
            postEncrypted(k, ChatLogic.content(c.text, files, c.notes), m.replyTo, "msg", ChatLogic.sendFileIds(files))
            quietly { api.delete("api/chats/$chatId/messages/${m.id}") }
            messages.removeAll { it.id == m.id }
            behind = !quietly { loadNewer() }
        } finally {
            sending = false
        }
    }

    /** Ciphertext eines Anhangs (Plattencache, sonst Server) als neue Datei hochladen -> neue Datei-ID */
    private suspend fun reupload(fid: String): String {
        val blob = withContext(Dispatchers.IO) { svc.blobs.get(ChatBlobCache.key(chatId, fid)) } ?: api.getBytes("api/chat/files/$fid")
        val id = decode<FileUploaded>(api.postBytes("api/chats/$chatId/files", blob)).id
        withContext(Dispatchers.IO) { svc.blobs.put(ChatBlobCache.key(chatId, id), blob) }
        return id
    }

    /**
     * 2.4: grosses Video (saga-file/v2) neu hochladen - Ciphertext in Teilen vom Server holen (Range) und als neuen
     * Upload senden (gleicher Schluessel; das Format haengt nicht am Chat). Nie die ganze Datei im Speicher.
     */
    private suspend fun reuploadV2(f: ChatFile): String {
        val total = FileV2.cipherSize(f.s, f.cs ?: FileV2.CS)
        val http = io.github.yggdrasil_sh.saga.chat.video.ApiUploadHttp(api)
        val c = http.create(chatId, total)
        val part = minOf(c.partMax, svc.me.value?.limits?.uploadPart ?: c.partMax).coerceAtLeast(64L * 1024)
        var off = 0L
        try {
            while (off < total) {
                val want = minOf(off + part, total) - 1
                val r = api.raw("GET", "api/chat/files/${f.id}", headers = mapOf("Range" to "bytes=$off-$want"))
                if (r.code != 206) throw ApiException(r.code, UiText.Res(R.string.chat_file_broken), r.text)
                // kuerzere Antwort moeglich (Anhang eines anderen Servers): nur das Gelieferte senden, Rest neu anfordern
                val got = r.bytes.takeIf { it.size.toLong() <= want - off + 1 } ?: r.bytes.copyOf((want - off + 1).toInt())
                if (got.isEmpty()) throw ApiException(r.code, UiText.Res(R.string.chat_file_broken), "")
                val p = http.put(c.url, off, off + got.size, total) { it.write(got) }
                when {
                    p.code in 200..299 && p.done -> return p.id?.ifEmpty { null } ?: c.upload
                    p.code in 200..299 -> off = p.offset ?: (off + got.size)
                    p.code == 409 && p.offset != null -> off = p.offset
                    else -> throw ApiException(p.code, p.error?.let { UiText.Raw(it) } ?: UiText.Res(R.string.chat_file_broken), "")
                }
            }
            return c.upload
        } catch (e: Exception) {
            withContext(kotlinx.coroutines.NonCancellable) { http.delete(c.upload) }
            throw e
        }
    }

    /** 2.4: Kennung fuer v1-Anhaenge dieses Chats (Poster, kleine Videos): v1 chat_id, v2 chat_ref */
    val fileCryptoId: String get() = cryptoId

    /** 2.4: fertig hochgeladene Anhaenge (Video + Poster) als eigene Nachricht senden (Video-Upload im Hintergrund) */
    suspend fun sendFiles(files: List<ChatFile>): String {
        val k = keys()
        return postEncrypted(k, ChatLogic.content("", files, emptyList()), null, "msg", ChatLogic.sendFileIds(files))
    }

    // ---------------------------------------------------------------- Nachrichtenanfragen (2.3)

    /** Annehmen | Ablehnen | Sperren (Person oder ganzer Server); Ablehnen/Sperren schliesst die Unterhaltung */
    suspend fun answerRequest(action: String, scope: String = "person") {
        svc.fed.answer(chatId, action, scope)
        if (action == "accept") {
            loadSummary()
            behind = !quietly { loadNewer() }
        } else {
            closed = true
        }
    }

    /** 2.3: "Verstanden" (Warnung bestaetigen) bzw. "Als überprüft markieren" fuer eine Person eines anderen Servers */
    suspend fun trustFed(p: ChatPerson, verified: Boolean) {
        val h = p.handle ?: return
        val k = fk[h]?.keys ?: return
        svc.fed.verify(h, k.identPub, verified)
        quietly { loadFedKeys(false) }
    }

    private fun recipientKeys(rc: List<Recipient>): Map<String, ByteArray>? {
        val c = svc.crypto
        val mine = svc.keys()
        val changed = HashSet<String>()
        for (r in rc) {
            if (!r.ready || r.identPub == null) continue
            if (r.username == me) {
                // eigene Identitaet auf dem Server muss zu den Schluesseln dieses Geraets passen
                if (mine == null || unb64(r.identPub, 32)?.contentEquals(mine.ident.identPk) != true) return null
                continue
            }
            // TOFU: geaenderte Sicherheitsnummer blockiert das Senden, bis man sie angesehen hat
            if (svc.store.observe(r.username, r.identPub) == TofuResult.CHANGED || svc.store.known()[r.username]?.changed == true) changed.add(r.username)
        }
        if (changed.isNotEmpty()) {
            identChanged = identChanged + changed
            val uid = changed.first()
            val who = summary?.members?.firstOrNull { it.username == uid }?.name?.ifEmpty { null } ?: uid
            throw UiTextException(UiText.Res(R.string.chat_safety_changed, who))
        }
        return ChatLogic.recipientKeys(
            rc,
            verifyDevice = { r, d -> c.verifyDevice(r.username, unb64(r.identPub, 32), d.id, d.encPub, d.signPub, d.sig) },
            verifyBackup = { r -> c.verifyBackup(r.username, unb64(r.identPub, 32), r.backupPub, r.backupSig) },
        )
    }

    suspend fun delete(m: ChatMessage) {
        api.delete("api/chats/$chatId/messages/${m.id}")
        messages.removeAll { it.id == m.id }
    }

    /** "tippt" hoechstens alle 2,5 s */
    suspend fun typingNow() {
        val now = System.currentTimeMillis()
        if (now - lastTyping < 2_500) return
        lastTyping = now
        try {
            api.post("api/chats/$chatId/typing")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun expireTyping() {
        val now = System.currentTimeMillis()
        if (typing.values.any { it.second < now }) typing = typing.filterValues { it.second >= now }
    }

    // ---------------------------------------------------------------- Einstellungen des Chats

    suspend fun setMuted(muted: Boolean) {
        summary = decode<ChatWrap>(api.patch("api/chats/$chatId", buildJsonObject { put("muted", muted) })).chat
        svc.loadChats()
    }

    suspend fun setRetention(seconds: Long) {
        summary = decode<ChatWrap>(api.patch("api/chats/$chatId", buildJsonObject { put("retention_s", seconds) })).chat
    }

    /** Freie Gruppe umbenennen: Titel als verschluesselte Nachricht (kind "title") */
    suspend fun rename(title: String) {
        postEncrypted(keys(), ChatLogic.titleContent(title.trim()), null, "title", emptyList())
        svc.loadChats()
        loadSummary()
    }

    // ---------------------------------------------------------------- Archiv und Loeschen (2.1)

    /** Server kann Archiv/Loeschen (sonst Menue-Eintraege ausblenden) */
    val canArchive: Boolean get() = ChatDeleteLogic.supported(summary)

    /** Chat wurde fuer alle geloescht bzw. (Direkt-Chat) fuer mich geleert: Ansicht schliessen */
    var closed by mutableStateOf(false)
        private set

    /** hochgezaehlt, wenn der Entwurf (Eingabe, Anhaenge) verworfen werden soll */
    var draftReset by mutableStateOf(0)
        private set

    suspend fun setArchived(archived: Boolean) {
        summary = svc.archive(chatId, archived)
    }

    /** "Chat löschen …" -> Ergebnis; danach ist der lokale Zwischenspeicher dieses Chats geleert */
    suspend fun deleteChat(forAll: Boolean, leave: Boolean): ChatDeleteResult {
        val sum = summary
        val r = svc.deleteChat(chatId, forAll, leave && sum?.kind == "free" && !forAll)
        wipe(Long.MAX_VALUE, draft = true)                  // die Ansicht schliesst danach; nichts Geloeschtes bleibt
        return r
    }

    /** Lokale Nachrichten bis upTo verwerfen (inkl. Antwort-Bezug), optional den Entwurf */
    fun wipe(upTo: Long, draft: Boolean) {
        messages.removeAll { it.seq <= upTo }
        if (replyTo?.let { it.seq <= upTo } == true) replyTo = null
        if (messages.isEmpty()) more = false
        if (draft) draftReset++
    }

    suspend fun leave() {
        api.delete("api/chats/$chatId/members/$me")
        svc.loadChats()
    }

    suspend fun addMember(user: String) = addMemberBody(buildJsonObject { put("user", user) })

    /** 2.3 (E5): Person eines anderen Servers zur Gruppe hinzufuegen (Handle aus der Adressbuch-Suche; Aenderung ueber das Heim) */
    suspend fun addMemberHandle(handle: String) = addMemberBody(buildJsonObject { put("handle", handle) })

    /**
     * Mitglied entfernen (nur Admins einer freien Gruppe; `who` = members[].username, bei Gruppen ueber Servergrenzen
     * laeuft die Aenderung ueber das Heim). Danach Stand neu holen - die Person bekommt keine neuen Nachrichten mehr.
     */
    suspend fun removeMember(who: String) {
        if (who.isEmpty() || who == me) return
        api.delete("api/chats/$chatId/members/" + android.net.Uri.encode(who, "~.-_"))
        loadSummary()
        svc.loadChats()
    }

    private suspend fun addMemberBody(body: JsonObject) {
        val text = api.post("api/chats/$chatId/members", body)
        // Gruppen ueber Servergrenzen: Antwort ggf. nur {ok} (Heim ist ein anderer Server) -> Stand neu holen
        val c = try {
            decode<ChatWrap>(text).chat
        } catch (_: Exception) {
            null
        }
        if (c != null) summary = c else loadSummary()
        svc.loadChats()
        // neue Mitglieder sollen den Gruppennamen sehen: ihn erneut (fuer alle jetzigen Mitglieder) senden
        if (isFed && summary?.kind == "free") svc.freeTitles.value[chatId]?.takeIf { it.isNotBlank() }?.let { t ->
            quietly { postEncrypted(keys(), ChatLogic.titleContent(t), null, "title", emptyList()) }
        }
    }

    // ---------------------------------------------------------------- Anhaenge

    /** Vorschaubild eines Bild-Anhangs (2.1-c): Speicher, Plattencache (Ciphertext), Server; ohne th selbst erzeugt */
    suspend fun thumb(f: ChatFile): androidx.compose.ui.graphics.ImageBitmap? =
        svc.thumbs.load(chatId, f, (svc.me.value?.limits ?: ChatLimits()).fileMax, cryptoIdFor(f))

    fun cachedThumb(f: ChatFile): androidx.compose.ui.graphics.ImageBitmap? = svc.thumbs.cached(chatId, f)

    suspend fun download(f: ChatFile): ByteArray {
        val blob = api.getBytes("api/chat/files/${f.id}")
        return withContext(Dispatchers.Default) {
            svc.decryptFileAny(cryptoIdFor(f), blob, unb64(f.k, 32) ?: throw UiTextException(UiText.Res(R.string.chat_file_broken)))
        }
    }

    // ---------------------------------------------------------------- Sicherheitsnummer

    fun safetyNumber(other: String): String? {
        if (isFed) {
            val p = summary?.members?.firstOrNull { it.username == other } ?: return null
            val h = p.handle ?: return null
            val my = summary?.myHandle ?: svc.fed.myHandle ?: return null
            val k = fk[h]?.keys ?: return null
            return svc.fed.safetyNumber(my, h, k.identPk)
        }
        val mine = svc.keys() ?: return null
        val theirs = unb64(idents[other], 32) ?: return null
        return svc.crypto.safetyNumber(me, mine.ident.identPk, other, theirs)
    }

    fun acknowledge(uid: String, verified: Boolean) {
        if (isFed) {
            // v2: Ansehen allein bestaetigt nichts ("Verstanden" im Hinweis); "Als überprüft markieren" speichert den Schluessel
            val p = summary?.members?.firstOrNull { it.username == uid } ?: return
            val h = p.handle ?: return
            val k = fk[h]?.keys ?: return
            if (verified) {
                svc.store.setTrust2(h, k.identPub, true)
                fk = fk + (h to FedKeyState(k, TofuV2.TRUST, fk[h]?.stale == true, Trust2(k.identPub, verified = true, dver = k.ver)))
            }
            return
        }
        svc.store.acknowledge(uid, verified)
        identChanged = identChanged - uid
    }

    /** 2.5: Vertrauen aus dem v1-Stand uebernommen (gleicher Schluessel, neue Darstellung der Sicherheitsnummer) */
    fun isMigrated(uid: String): Boolean {
        if (!isFed) return false
        val h = summary?.members?.firstOrNull { it.username == uid }?.handle ?: return false
        val st = fk[h] ?: return false
        return st.stored?.migrated == true && st.stored.ident == st.keys?.identPub
    }

    fun isVerified(uid: String): Boolean {
        if (isFed) {
            val h = summary?.members?.firstOrNull { it.username == uid }?.handle ?: return false
            return fk[h]?.verified == true
        }
        return svc.store.known()[uid]?.verified == true
    }

    // ---------------------------------------------------------------- Live

    suspend fun onEvent(ev: LiveEvent) {
        // 2.3: Schluessel einer Person dieses Chats geaendert -> frisch holen und TOFU pruefen (Ereignis ohne chat_id)
        if (ev.type == "chat.keys.changed") {
            val (h, own) = FedChat.keysChanged(ev.data)
            if (isFed && (own || (h != null && h in handles()))) quietly { loadFedKeys(true) }
            return
        }
        if (ev.long("chat_id") != chatId) return
        when (ev.type) {
            "chat.message" -> {
                typing = typing - (ev.str("sender") ?: "")
                // 2.3: eigene wartende Nachricht jetzt zugestellt bzw. abgewiesen
                ev.str("id")?.let { id ->
                    val i = messages.indexOfFirst { it.id == id }
                    if (i >= 0 && messages[i].fstate != null) messages[i] = messages[i].copy(fstate = if (ev.bool("failed")) "failed" else null)
                    // 2.4: Position (fseq) der zugestellten eigenen Nachricht - fuer die Gelesen-Haken
                    val f = ev.long("fseq")
                    if (i >= 0 && f != null && messages[i].fseq == null) messages[i] = messages[i].copy(fseq = f)
                }
                behind = !quietly { loadNewer() }        // Funkloch: kein Absturz (BUG-003), catchUp() holt nach
            }
            "chat.deleted" -> ev.str("id")?.let { id ->
                messages.removeAll { it.id == id }
                if (editing?.id == id) editing = null
            }
            // 2.5: Reaktionen (gebuendelt, je Chat hoechstens einmal je Sekunde) und Bearbeitungen
            "chat.react" -> liveReactions(ev)
            "chat.edited" -> liveEdited(ev)
            // 2.4: Gelesen-Bestaetigungen (gebuendelt; grosse Gruppen nur Hinweis "more" -> abrufen)
            "chat.receipt" -> if (receiptsOn) {
                if (ev.bool("more")) quietly { loadReceipts() }
                else applyReads(takeReceipts(reads, ReceiptLogic.parseList(ev.data["receipts"])))
            }
            "chat.typing" -> {
                val u = ev.obj("user")
                val user = (u?.get("username") as? JsonPrimitive)?.content ?: return
                val name = (u["name"] as? JsonPrimitive)?.content ?: user
                if (user != me) typing = typing + (user to (name to System.currentTimeMillis() + 6_000))
            }
            "chat.removed", "chat.cleared" -> {
                val w = ChatDeleteLogic.parse(ev.type, ev.data, me) ?: return
                if (ChatDeleteLogic.closes(w, summary?.kind)) {
                    wipe(Long.MAX_VALUE, draft = true)
                    closed = true
                    return
                }
                val c = w as ChatWipe.Cleared
                // "Für alle": alles weg, eigener Entwurf bleibt; "nur für mich" (anderes Geraet): auch der Entwurf
                wipe(c.upTo, draft = !c.forAll)
                quietly { loadSummary() }
            }
            "chat.keys.changed" -> {}
            "chat.changed" -> {
                rc = null                                       // Mitglieder/Geraete koennen sich geaendert haben
                try {
                    val wasFed = isFed
                    loadSummary()
                    // Seiten-Chat ueber Servergrenzen umgestellt (E5, v1 -> v2): Verlauf mit der neuen Kennung neu oeffnen
                    // (Altnachrichten weiter v1, neue v2) - sonst blieben schon geladene Nachrichten falsch entschluesselt
                    if (!wasFed && isFed) {
                        open()
                        return
                    }
                    loadIdents()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.code == 404) notFound = true
                } catch (_: Exception) {
                }
            }
        }
    }
}
