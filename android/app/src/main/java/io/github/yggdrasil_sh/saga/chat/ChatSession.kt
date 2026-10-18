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

    private var lastSeq = 0L
    private var lastTyping = 0L

    val me: String get() = svc.me.value?.uid.orEmpty()
    val name: String get() = summary?.let { svc.displayName(it) }.orEmpty()

    private suspend inline fun <reified T> decode(s: String): T = withContext(Dispatchers.Default) { json.decodeFromString<T>(s) }

    private fun keys(): LocalChatKeys = svc.keys() ?: throw UiTextException(UiText.Res(R.string.chat_not_set_up))

    // ---------------------------------------------------------------- Laden

    suspend fun open() {
        loading = true
        try {
            loadSummary()
            val r = decode<MessagesResponse>(api.get("api/chats/$chatId/messages?device=${keys().dev.id}&limit=50&calls=1"))
            messages.clear()
            messages.addAll(decryptAll(r))
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
        val older = decryptAll(r).filter { o -> messages.none { it.id == o.id } }
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
        val newer = decryptAll(r).filter { n -> messages.none { it.id == n.id } }
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
        return withContext(Dispatchers.Default) {
            r.messages.map { m ->
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
            if (files.size > limits.filesPerMsg) throw UiTextException(UiText.Res(R.string.chat_too_many_files, limits.filesPerMsg))
            files.forEach { f ->
                if (f.data.size > limits.fileMax) throw UiTextException(UiText.Res(R.string.chat_file_too_big, limits.fileMax / 1048576))
            }
            // 2.1-c: je Bild ein Vorschaubild (eigene Datei, eigener Schluessel) - so viele, wie Datei-Kennungen erlaubt sind
            var thumbsLeft = ChatImages.thumbBudget(files.size, files.count { it.mime.startsWith("image/") }, limits.filesPerMsg, limits.fileIdsPerMsg)
            val cid = chatId.toString()
            val uploaded = files.map { f ->
                val (blob, key) = withContext(Dispatchers.Default) { svc.crypto.encryptFile(cid, f.data) }
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
                            val (tblob, tkey) = withContext(Dispatchers.Default) { svc.crypto.encryptFile(cid, t.jpeg) }
                            val tid = decode<FileUploaded>(api.postBytes("api/chats/$chatId/files", tblob)).id
                            file = file.copy(th = ChatThumb(tid, b64(tkey), "image/jpeg", t.w, t.h, t.jpeg.size.toLong()))
                            withContext(Dispatchers.IO) { svc.blobs.put(tid, tblob) }      // eigenes Bild sofort aus dem Cache
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
    var aiMayToggle by mutableStateOf<Boolean?>(null)
        private set

    /** Berechtigung fuer den Umschalter pruefen (Gruppe: Saga-Admin, Seiten-Chat: Eigentuemer der Seite) */
    suspend fun checkAiToggle() {
        val sum = summary ?: return
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
        aiMayToggle = io.github.yggdrasil_sh.saga.ai.AiChatLogic.mayToggle(sum.kind, sum.role, admin, owner)
    }

    /** PUT /api/chats/{cid}/ai {"allowed"} -> {"ai"} */
    suspend fun setAi(allowed: Boolean) {
        val o = Json.parseToJsonElement(api.request("PUT", "api/chats/$chatId/ai", buildJsonObject { put("allowed", allowed) })) as? JsonObject
        val on = (o?.get("ai") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: allowed
        summary = summary?.copy(ai = on)
    }

    /** Verlauf fuer die KI: hoechstens 12 Textnachrichten vor der Frage; eigene KI-Antworten mit dem Namen der KI */
    fun aiHistory(questionId: String, aiName: String): List<io.github.yggdrasil_sh.saga.ai.AiChatLogic.Line> =
        io.github.yggdrasil_sh.saga.ai.AiChatLogic.history(
            messages.filter { it.call == null }.map { m ->
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
            svc.repo.ai.chatAsk(chatId, io.github.yggdrasil_sh.saga.ai.AiChatLogic.question(question), questionId, aiHistory(questionId, aiName)).collect { c ->
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
            postEncrypted(keys(), ChatLogic.aiReplyContent(text, rid, aiName), questionId, "msg", emptyList(), aiReply = rid)
            behind = !quietly { loadNewer() }
        } finally {
            aiPending = null
        }
    }

    private suspend fun postEncrypted(k: LocalChatKeys, plain: String, reply: String?, kind: String, fileIds: List<String>, aiReply: String? = null): String {
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

    suspend fun leave() {
        api.delete("api/chats/$chatId/members/$me")
        svc.loadChats()
    }

    suspend fun addMember(user: String) {
        summary = decode<ChatWrap>(api.post("api/chats/$chatId/members", buildJsonObject { put("user", user) })).chat
        svc.loadChats()
    }

    // ---------------------------------------------------------------- Anhaenge

    /** Vorschaubild eines Bild-Anhangs (2.1-c): Speicher, Plattencache (Ciphertext), Server; ohne th selbst erzeugt */
    suspend fun thumb(f: ChatFile): androidx.compose.ui.graphics.ImageBitmap? =
        svc.thumbs.load(chatId, f, (svc.me.value?.limits ?: ChatLimits()).fileMax)

    fun cachedThumb(f: ChatFile): androidx.compose.ui.graphics.ImageBitmap? = svc.thumbs.cached(f)

    suspend fun download(f: ChatFile): ByteArray {
        val blob = api.getBytes("api/chat/files/${f.id}")
        return withContext(Dispatchers.Default) {
            svc.crypto.decryptFile(chatId.toString(), blob, unb64(f.k, 32) ?: throw UiTextException(UiText.Res(R.string.chat_file_broken)))
        }
    }

    // ---------------------------------------------------------------- Sicherheitsnummer

    fun safetyNumber(other: String): String? {
        val mine = svc.keys() ?: return null
        val theirs = unb64(idents[other], 32) ?: return null
        return svc.crypto.safetyNumber(me, mine.ident.identPk, other, theirs)
    }

    fun acknowledge(uid: String, verified: Boolean) {
        svc.store.acknowledge(uid, verified)
        identChanged = identChanged - uid
    }

    fun isVerified(uid: String): Boolean = svc.store.known()[uid]?.verified == true

    // ---------------------------------------------------------------- Live

    suspend fun onEvent(ev: LiveEvent) {
        if (ev.long("chat_id") != chatId) return
        when (ev.type) {
            "chat.message" -> {
                typing = typing - (ev.str("sender") ?: "")
                behind = !quietly { loadNewer() }        // Funkloch: kein Absturz (BUG-003), catchUp() holt nach
            }
            "chat.deleted" -> ev.str("id")?.let { id -> messages.removeAll { it.id == id } }
            "chat.typing" -> {
                val u = ev.obj("user")
                val user = (u?.get("username") as? JsonPrimitive)?.content ?: return
                val name = (u["name"] as? JsonPrimitive)?.content ?: user
                if (user != me) typing = typing + (user to (name to System.currentTimeMillis() + 6_000))
            }
            "chat.changed" -> {
                try {
                    loadSummary()
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
