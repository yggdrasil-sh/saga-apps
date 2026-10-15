package io.github.yggdrasil_sh.saga.chat

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.live.LiveEvent
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Zustand der Chats auf diesem Geraet */
enum class ChatSetup {
    LOADING,
    /** Server kennt keine Chats (aelter als 1.5) */
    UNSUPPORTED,
    /** Admin hat Chats abgeschaltet */
    OFF,
    /** Konto hat noch keine Chat-Schluessel: einrichten (Wiederherstellungsschluessel) */
    NEW_ACCOUNT,
    /** Konto hat Schluessel, dieses Geraet nicht: koppeln oder Wiederherstellungsschluessel eingeben */
    NEW_DEVICE,
    READY,
    ERROR,
}

/** Einrichtung in Arbeit: Schluessel + Wiederherstellungsschluessel (noch nicht hochgeladen, erst nach Bestaetigung) */
class PendingSetup(val ident: Identity, val dev: DeviceKeys, val recoveryKey: String)

/** Neues Geraet wartet auf Bestaetigung durch ein vorhandenes */
class LinkSession(val id: String, val code: String, val dev: DeviceKeys, val expires: Double?)

/** Neu beginnen braucht eine frische Anmeldung: Passwort (Feld) oder neu anmelden (SSO) */
class ReauthNeeded(val kind: String, message: UiText) : UiTextException(message)

/** Reine Hilfen fuer Anzeige und Pruefung (Tests) */
object ChatLogic {
    /** Name eines Chats: dm = Gegenueber, group = Gruppe, page = Seitentitel, free = Titel aus der Titel-Nachricht oder Mitglieder */
    fun displayName(c: ChatSummary, freeTitle: String?): String = when {
        c.kind == "free" && !freeTitle.isNullOrBlank() -> freeTitle
        !c.name.isNullOrBlank() -> c.name
        else -> c.members.filter { !it.me }.joinToString(", ") { it.name.ifEmpty { it.username } }
    }

    /** Empfaenger fuer die Versiegelung: jedes Geraet + "backup:<uid>" je Mitglied mit Schluesseln; ungueltige Zertifikate -> Fehler */
    fun recipientKeys(rc: List<Recipient>, verifyDevice: (Recipient, RecipientDevice) -> Boolean, verifyBackup: (Recipient) -> Boolean): Map<String, ByteArray>? {
        val out = LinkedHashMap<String, ByteArray>()
        for (r in rc) {
            if (!r.ready) continue
            if (!verifyBackup(r)) return null
            out["backup:" + r.username] = unb64(r.backupPub, 32) ?: return null
            for (d in r.devices) {
                if (!verifyDevice(r, d)) return null
                out[d.id] = unb64(d.encPub, 32) ?: return null
            }
        }
        return out
    }

    /** Inhalt einer Textnachricht als JSON-Text (unbekannte Felder liest die Gegenseite nicht) */
    fun content(text: String, files: List<ChatFile>, notes: List<Long>): String = buildJsonObject {
        put("v", 1)
        put("type", "text")
        put("text", text)
        if (files.isNotEmpty()) put("files", buildJsonArray {
            files.forEach { f ->
                add(buildJsonObject { put("id", f.id); put("k", f.k); put("n", f.n); put("m", f.m); put("s", f.s) })
            }
        })
        if (notes.isNotEmpty()) put("notes", buildJsonArray { notes.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    fun titleContent(title: String): String = buildJsonObject {
        put("v", 1)
        put("type", "title")
        put("title", title)
    }.toString()
}

/**
 * Chats mit Ende-zu-Ende-Verschluesselung (Server 1.5/2.0): Einrichtung, Kopplung neuer Geraete, Wiederherstellung,
 * Geraete, Chat-Liste. Private Schluessel nur im Keystore (ChatStore), nie an den Server.
 */
class ChatService(val repo: SagaRepository, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    /** libsodium erst bei Bedarf laden */
    val crypto: ChatCrypto by lazy { ChatCrypto(LazySodiumAndroid(SodiumAndroid())) }
    val store = ChatStore(repo.secure) { repo.settings.server.value + "|" + repo.settings.accountKey.value }

    val state = MutableStateFlow(ChatSetup.LOADING)
    val me = MutableStateFlow<ChatMe?>(null)
    val chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chatsLoaded = MutableStateFlow(false)
    val error = MutableStateFlow<UiText?>(null)
    /** Titel freier Gruppen (entschluesselt) */
    val freeTitles = MutableStateFlow<Map<Long, String>>(emptyMap())
    /** offene Kopplungsanfragen neuer Geraete (zum Bestaetigen auf diesem Geraet) */
    val pendingLinks = MutableStateFlow<List<LinkRequest>>(emptyList())

    @Volatile private var cached: LocalChatKeys? = null
    private val lock = Mutex()

    private suspend inline fun <reified T> decode(s: String): T = withContext(Dispatchers.Default) { json.decodeFromString<T>(s) }

    fun keys(): LocalChatKeys? = cached ?: store.keys()?.also { cached = it }

    val deviceId: String? get() = keys()?.dev?.id

    init {
        scope.launch { repo.live.events.collect { onEvent(it) } }
    }

    /** Abmelden/Kontowechsel */
    fun reset() {
        cached = null
        state.value = ChatSetup.LOADING
        me.value = null
        chats.value = emptyList()
        chatsLoaded.value = false
        freeTitles.value = emptyMap()
        pendingLinks.value = emptyList()
    }

    // ---------------------------------------------------------------- Stand

    suspend fun refresh() = lock.withLock {
        if (repo.needsLogin.value) return@withLock
        try {
            cached = null
            val local = withContext(Dispatchers.IO) { keys() }
            val m = decode<ChatMe>(repo.api.get("api/chat/me" + (local?.dev?.id?.let { "?device=$it" } ?: "")))
            me.value = m
            error.value = null
            state.value = when {
                !m.enabled -> ChatSetup.OFF
                m.identity == null -> ChatSetup.NEW_ACCOUNT
                local == null || local.uid != m.uid || b64(local.ident.identPk) != m.identity.identPub ||
                    m.devices.none { it.id == local.dev.id && !it.revoked } -> ChatSetup.NEW_DEVICE
                else -> ChatSetup.READY
            }
            if (state.value == ChatSetup.READY && m.links > 0) loadLinksLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404) state.value = ChatSetup.UNSUPPORTED
            else if (e.code == 403) state.value = ChatSetup.OFF
            else {
                error.value = repo.friendly(e)
                if (state.value == ChatSetup.LOADING) state.value = ChatSetup.ERROR
            }
        } catch (e: Exception) {
            error.value = repo.friendly(e)
            if (state.value == ChatSetup.LOADING) state.value = ChatSetup.ERROR
        }
    }

    suspend fun loadChats() {
        if (state.value != ChatSetup.READY) return
        try {
            val r = decode<ChatList>(repo.api.get("api/chats" + (deviceId?.let { "?device=$it" } ?: "")))
            chats.value = r.chats
            chatsLoaded.value = true
            repo.live.chatUnread.value = r.unread
            decryptTitles(r.chats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = repo.friendly(e)
        }
    }

    private suspend fun decryptTitles(list: List<ChatSummary>) {
        val k = keys() ?: return
        val out = HashMap(freeTitles.value)
        withContext(Dispatchers.Default) {
            for (c in list) {
                val m = c.titleMsg ?: continue
                val dec = MessageOpener(this@ChatService, k).open(c.id, m, m.device?.let { mapOf(m.senderDevice to it) }.orEmpty(),
                    m.identPub?.let { ip -> mapOf((m.sender?.username ?: "") to ip) }.orEmpty())
                dec.content?.title?.takeIf { it.isNotBlank() && dec.verified }?.let { out[c.id] = it }
            }
        }
        freeTitles.value = out
    }

    fun displayName(c: ChatSummary): String = ChatLogic.displayName(c, freeTitles.value[c.id])

    // ---------------------------------------------------------------- Einrichtung

    /** Schritt 1: Schluessel und Wiederherstellungsschluessel erzeugen (noch nichts hochladen) */
    suspend fun prepareSetup(): PendingSetup = withContext(Dispatchers.Default) {
        PendingSetup(crypto.identity(), crypto.device(), crypto.recoveryKey())
    }

    /**
     * Schritt 2 (nach Bestaetigung der letzten 4 Zeichen): hochladen. reset = "Neu beginnen" (alte Geraete abgemeldet).
     * 403 {reauth} -> ReauthNeeded (Passwort bzw. neu anmelden).
     */
    suspend fun completeSetup(p: PendingSetup, reset: Boolean = false, password: String? = null) {
        val m = me.value ?: throw UiTextException(UiText.Res(R.string.error_generic))
        val uid = m.uid
        val body = withContext(Dispatchers.Default) {
            val bundle = crypto.sealBundle(uid, p.recoveryKey, p.ident)
            buildJsonObject {
                put("ident_pub", b64(p.ident.identPk))
                put("backup_pub", b64(p.ident.backupPk))
                put("backup_sig", b64(crypto.backupSig(uid, p.ident)))
                put("bundle", bundle)
                putJsonObject("device") {
                    put("id", p.dev.id)
                    put("name", repo.deviceName)
                    put("enc_pub", b64(p.dev.encPk))
                    put("sign_pub", b64(p.dev.signPk))
                    put("sig", b64(crypto.deviceSig(uid, p.ident.identSk, p.dev)))
                }
                if (reset) put("reset", true)
                if (!password.isNullOrEmpty()) put("password", password)
            }
        }
        try {
            repo.api.request("PUT", "api/chat/identity", body)
        } catch (e: ApiException) {
            if (e.code == 403) {
                val o = try {
                    Json.parseToJsonElement(e.body) as? JsonObject
                } catch (_: Exception) {
                    null
                }
                val kind = (o?.get("reauth") as? JsonPrimitive)?.content
                if (kind != null) throw ReauthNeeded(kind, e.text)
            }
            throw e
        }
        withContext(Dispatchers.IO) {
            store.saveKeys(LocalChatKeys(uid, p.ident, p.dev))
            cached = null
        }
        refresh()
        loadChats()
    }

    /** Neues Geraet: mit dem Wiederherstellungsschluessel das Buendel oeffnen und dieses Geraet anmelden */
    suspend fun recover(rk: String) {
        ChatFormat.normRecovery(rk) ?: throw UiTextException(UiText.Res(R.string.chat_recovery_invalid))
        refresh()
        val m = me.value ?: throw UiTextException(UiText.Res(R.string.error_generic))
        val idn = m.identity ?: throw UiTextException(UiText.Res(R.string.error_generic))
        val (ident, dev) = withContext(Dispatchers.Default) {
            val ident = try {
                crypto.openBundle(m.uid, rk, idn.bundle)
            } catch (_: BundleFormatException) {
                throw UiTextException(UiText.Res(R.string.chat_bundle_format))
            } catch (_: Exception) {
                throw UiTextException(UiText.Res(R.string.chat_recovery_wrong))
            }
            if (b64(ident.identPk) != idn.identPub) throw UiTextException(UiText.Res(R.string.chat_recovery_wrong))
            ident to crypto.device()
        }
        val body = buildJsonObject {
            put("id", dev.id)
            put("name", repo.deviceName)
            put("enc_pub", b64(dev.encPk))
            put("sign_pub", b64(dev.signPk))
            put("sig", b64(crypto.deviceSig(m.uid, ident.identSk, dev)))
        }
        repo.api.post("api/chat/devices", body)
        withContext(Dispatchers.IO) {
            store.saveKeys(LocalChatKeys(m.uid, ident, dev))
            cached = null
        }
        refresh()
        loadChats()
    }

    // ---------------------------------------------------------------- Kopplung: neues Geraet

    suspend fun startLink(): LinkSession {
        val m = me.value ?: throw UiTextException(UiText.Res(R.string.error_generic))
        val dev = withContext(Dispatchers.Default) { crypto.device() }
        val r = decode<LinkCreated>(repo.api.post("api/chat/link", buildJsonObject {
            put("id", dev.id)
            put("name", repo.deviceName)
            put("enc_pub", b64(dev.encPk))
            put("sign_pub", b64(dev.signPk))
        }))
        val code = withContext(Dispatchers.Default) { crypto.linkCode(m.uid, dev.id, dev.encPk, dev.signPk) }
        return LinkSession(r.id, code, dev, r.expires)
    }

    /** -> "pending" | "approved" (Schluessel uebernommen) | "expired" | "gone" */
    suspend fun pollLink(ls: LinkSession): String {
        val r = decode<LinkState>(repo.api.get("api/chat/link/${ls.id}"))
        if (r.state != "approved") return r.state
        val m = me.value ?: throw UiTextException(UiText.Res(R.string.error_generic))
        val ident = withContext(Dispatchers.Default) {
            val pt = crypto.sealOpen(unb64(r.sealed) ?: throw UiTextException(UiText.Res(R.string.chat_link_failed)), ls.dev.encPk, ls.dev.encSk)
            crypto.parsePlain(String(pt, Charsets.UTF_8))
        }
        // Buendel muss zur oeffentlichen Identitaet des Kontos passen (sonst hat jemand anderes bestaetigt)
        if (m.identity?.identPub != b64(ident.identPk)) throw UiTextException(UiText.Res(R.string.chat_link_failed))
        withContext(Dispatchers.IO) {
            store.saveKeys(LocalChatKeys(m.uid, ident, ls.dev))
            cached = null
        }
        refresh()
        loadChats()
        return "approved"
    }

    suspend fun cancelLink(ls: LinkSession) {
        try {
            repo.api.delete("api/chat/link/${ls.id}")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- Kopplung: dieses Geraet bestaetigt

    suspend fun loadLinks() = lock.withLock { loadLinksLocked() }

    private suspend fun loadLinksLocked() {
        if (keys() == null) return
        try {
            pendingLinks.value = decode<LinkList>(repo.api.get("api/chat/link")).links
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun linkCode(l: LinkRequest): String? {
        val m = me.value ?: return null
        val e = unb64(l.encPub, 32) ?: return null
        val s = unb64(l.signPub, 32) ?: return null
        return crypto.linkCode(m.uid, l.deviceId, e, s)
    }

    /** "Code stimmt – bestätigen": Geraete-Zertifikat signieren, Buendel an das neue Geraet versiegeln */
    suspend fun approve(l: LinkRequest) {
        val k = keys() ?: throw UiTextException(UiText.Res(R.string.error_generic))
        val body = withContext(Dispatchers.Default) {
            val e = unb64(l.encPub, 32) ?: throw UiTextException(UiText.Res(R.string.chat_link_failed))
            val s = unb64(l.signPub, 32) ?: throw UiTextException(UiText.Res(R.string.chat_link_failed))
            val sig = crypto.sign(crypto.deviceMsg(k.uid, l.deviceId, e, s), k.ident.identSk)
            val sealed = crypto.seal(crypto.bundlePlain(k.ident.identSk, k.ident.backupSk).toByteArray(Charsets.UTF_8), e)
            buildJsonObject {
                put("sig", b64(sig))
                put("sealed", b64(sealed))
            }
        }
        repo.api.post("api/chat/link/${l.id}", body)
        pendingLinks.value = pendingLinks.value.filter { it.id != l.id }
        refresh()
    }

    suspend fun reject(l: LinkRequest) {
        repo.api.delete("api/chat/link/${l.id}")
        pendingLinks.value = pendingLinks.value.filter { it.id != l.id }
    }

    // ---------------------------------------------------------------- Geraete, Wiederherstellungsschluessel

    suspend fun removeDevice(id: String) {
        repo.api.delete("api/chat/devices/$id")
        refresh()
    }

    /** Neuer Wiederherstellungsschluessel: erst anzeigen, nach Bestaetigung hochladen (commitRecovery) */
    fun newRecoveryKey(): String = crypto.recoveryKey()

    suspend fun commitRecovery(rk: String) {
        val k = keys() ?: throw UiTextException(UiText.Res(R.string.error_generic))
        val body = withContext(Dispatchers.Default) {
            val bundle = crypto.sealBundle(k.uid, rk, k.ident)
            buildJsonObject {
                put("bundle", bundle)
                put("sig", b64(crypto.bundleUpdateSig(k.uid, bundle, k.ident.identSk)))
            }
        }
        repo.api.request("PUT", "api/chat/bundle", body)
    }

    // ---------------------------------------------------------------- Chats anlegen, Personen, Seiten

    suspend fun people(q: String): PeopleResult =
        if (q.trim().length < 3) PeopleResult() else decode(repo.api.get("api/chat/people?q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")))

    suspend fun create(body: JsonObject): ChatSummary {
        val c = decode<ChatWrap>(repo.api.post("api/chats", body)).chat
        loadChats()
        return c
    }

    suspend fun createDm(user: String) = create(buildJsonObject { put("kind", "dm"); put("user", user) })
    suspend fun createGroup(group: String) = create(buildJsonObject { put("kind", "group"); put("group", group) })
    suspend fun createPage(noteId: Long) = create(buildJsonObject { put("kind", "page"); put("note_id", noteId) })
    suspend fun createFree(members: List<String>) = create(buildJsonObject {
        put("kind", "free")
        put("members", buildJsonArray { members.forEach { add(JsonPrimitive(it)) } })
    })

    suspend fun pages(q: String): List<ChatNoteRef> = decode<ChatPages>(repo.api.get("api/chat/pages?q=" + java.net.URLEncoder.encode(q, "UTF-8"))).pages

    suspend fun noteTitles(ids: Collection<Long>): Map<Long, ChatNoteRef> {
        if (ids.isEmpty()) return emptyMap()
        val r = decode<ChatNotes>(repo.api.get("api/chat/notes?ids=" + ids.joinToString(",")))
        return r.notes.mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }.toMap()
    }

    // ---------------------------------------------------------------- Live

    private suspend fun onEvent(ev: LiveEvent) {
        when (ev.type) {
            "chat.link" -> if (state.value == ChatSetup.READY && !ev.bool("approved") && !ev.bool("gone")) loadLinks()
                else if (ev.bool("gone") || ev.bool("approved")) pendingLinks.value = pendingLinks.value.filter { it.id != ev.str("id") }
            "chat.device" -> if (ev.bool("revoked") && ev.str("id") == deviceId) {
                // dieses Geraet wurde entfernt: Schluessel loeschen, Einrichtung "neues Geraet"
                withContext(Dispatchers.IO) { store.clearKeys() }
                cached = null
                refresh()
            }
            "chat.message", "chat.changed", "chat.deleted", "chat.read" -> if (chatsLoaded.value) loadChats()
            "reset" -> {
                refresh()
                loadChats()
            }
        }
    }
}
