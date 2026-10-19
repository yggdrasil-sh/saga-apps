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
)

/** 2.1: laufende Frage an die KI (nur auf diesem Geraet sichtbar, bis die Antwort als Nachricht gesendet ist) */
@Stable
class AiPending(val questionId: String) {
    var text by mutableStateOf("")
}

/** Ergebnis des Oeffnens einer Nachricht */
class Opened(val content: ChatContent?, val verified: Boolean)

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
        val sender = m.senderHandle ?: return Opened(null, false)
        val ai = m.ai?.takeIf { it.hashOnly }?.sha256
        val c = svc.fed.crypto
        val content = try {
            val key = if (m.keyFor == "backup") svc.crypto.openKey(m.key, k.ident.backupPk, k.ident.backupSk) else svc.crypto.openKey(m.key, k.dev.encPk, k.dev.encSk)
            json.decodeFromString(ChatContent.serializer(), c.decrypt(chatRef, m.id, sender, m.senderDevice, m.ts, m.replyTo, ai, m.bodyCt, key))
        } catch (_: Exception) {
            null
        }
        val f = fk[sender]
        val d = f?.keys?.takeIf { f.trust != TofuV2.REJECT }?.device(m.senderDevice)
        val verified = d != null && c.verifyMessage(chatRef, m.id, sender, m.senderDevice, m.ts, m.replyTo, ai, m.bodyCt, m.sig, unb64(d.signPub, 32))
        return Opened(content, verified)
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
            resolveNotes()
            markRead()
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
    }

    /** Nachladen ist gescheitert (Live-Ereignis/nach dem Senden) - catchUp() versucht es erneut */
    var behind by mutableStateOf(false)
        private set

    /** Liegengebliebenes Nachladen wiederholen (regelmaessig aus der Ansicht), nie mit Ausnahme */
    suspend fun catchUp() {
        if (behind) behind = !quietly { loadNewer() }
    }

    suspend fun loadNewer() {
        val after = messages.lastOrNull()?.seq ?: lastSeq
        val r = decode<MessagesResponse>(api.get("api/chats/$chatId/messages?device=${keys().dev.id}&after=$after&limit=200&calls=1"))
        val newer = ChatDeleteLogic.keepAfter(decryptAll(r), summary?.clearedSeq ?: 0) { it.seq }.filter { n -> messages.none { it.id == n.id } }
        // Systemzeile "ab jetzt ueber Servergrenzen" in einem noch als v1 geladenen Chat (E5): Stand holen und neu oeffnen
        if (!isFed && newer.any { it.sys?.type == "fed" }) {
            loadSummary()
            if (isFed) {
                open()
                return
            }
        }
        messages.addAll(newer)
        lastSeq = maxOf(lastSeq, r.lastSeq)
        if (newer.isNotEmpty()) {
            resolveNotes()
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
                            // umgestellter Seiten-Chat (E5): Nachrichten ohne sender_handle stammen aus der Zeit davor -
                            // weiter mit v1 entschluesseln und pruefen (chat_id, Absender-Konto, Geraete aus devices/idents)
                            val legacy = m.senderHandle.isNullOrEmpty()
                            val o = if (legacy) opener.open(chatId, m, r.devices, r.idents) else fo.open(ref, m)
                            if (legacy) o.content?.files?.forEach { f -> legacyFiles.add(f.id); f.th?.id?.let { legacyFiles.add(it) } }
                            ChatMessage(
                                m.seq, m.id, m.sender, m.senderDevice, m.ts, m.expiresAt, m.replyTo, o.content, o.verified, mine,
                                ai = ChatLogic.aiCheck(o.content, m.ai), fstate = m.fstate, senderHandle = m.senderHandle,
                            )
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

    /** Identitaetsschluessel aller Mitglieder (Sicherheitsnummer, TOFU) */
    suspend fun loadIdents() {
        if (isFed) {
            quietly { loadFedKeys(false) }
            return
        }
        val mem = summary?.members?.map { it.username }.orEmpty().filter { it.isNotEmpty() }
        if (mem.isEmpty()) return
        try {
            val o = Json.parseToJsonElement(api.get("api/chat/keys?users=" + mem.joinToString(","))) as? JsonObject ?: return
            val users = o["users"] as? JsonObject ?: return
            val out = HashMap(idents)
            val changed = HashSet(identChanged)
            for ((u, v) in users) {
                val ip = ((v as? JsonObject)?.get("ident_pub") as? JsonPrimitive)?.content ?: continue
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
        try {
            val o = Json.parseToJsonElement(api.post("api/chats/$chatId/read", buildJsonObject { put("seq", seq) })) as? JsonObject
            (o?.get("unread") as? JsonPrimitive)?.content?.toIntOrNull()?.let { svc.repo.live.chatUnread.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
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
                putJsonObject("keys") { enc.keys.forEach { (r, v) -> put(r, v) } }
                put("files", buildJsonArray { fileIds.forEach { add(JsonPrimitive(it)) } })
            }
            try {
                val o = Json.parseToJsonElement(api.post("api/chats/$chatId/messages", body)) as? JsonObject
                lastPending = (o?.get("pending") as? JsonPrimitive)?.content == "true"
                if (lastPending) svc.repo.notify(UiText.Res(R.string.fed_pending_sent))
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
                f.copy(id = reupload(f.id), th = th)
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
                }
                behind = !quietly { loadNewer() }        // Funkloch: kein Absturz (BUG-003), catchUp() holt nach
            }
            "chat.deleted" -> ev.str("id")?.let { id -> messages.removeAll { it.id == id } }
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
