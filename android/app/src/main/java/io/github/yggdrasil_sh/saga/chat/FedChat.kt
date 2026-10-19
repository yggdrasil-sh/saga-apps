package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Schluessel eines Handles nach eigener Pruefung + TOFU (keys = null: unbekannt bzw. ungueltig) */
class FedKeyState(val keys: FedKeys?, val trust: TofuV2, val stale: Boolean = false, val stored: Trust2? = null) {
    /** "✓ überprüft": gemerkter Schluessel ist dieser und als ueberprueft markiert */
    val verified: Boolean get() = trust == TofuV2.TRUST && stored?.verified == true && stored.ident == keys?.identPub
}

@Serializable
private data class FedKeysResponse(val keys: Map<String, FedKeyEntry?> = emptyMap())

/**
 * Saga 2.3 - Chats ueber Servergrenzen (saga-chat/v2, docs/APP-API-FED.md): eigene Zertifikate v2 hochladen (nur dieses
 * Geraet kennt ident_sk), Schluessel anderer Personen ueber den eigenen Server holen und SELBST pruefen (Zertifikate,
 * signierte Geraete-Liste, TOFU je Handle), Nachrichtenanfragen und Sperrliste. Lokale Chats (v1) beruehrt das nicht.
 */
class FedChat(private val svc: ChatService) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private val api get() = svc.repo.api
    val crypto: FedCrypto by lazy { FedCrypto(svc.crypto) }

    /** Stand v2 des eigenen Kontos (null = noch nicht geholt bzw. Server kennt v2 nicht) */
    val me = MutableStateFlow<FedMe?>(null)

    /** Server kann saga-chat/v2 (2.3): null = unbekannt */
    val supported = MutableStateFlow<Boolean?>(null)
    private val lock = Mutex()

    fun reset() {
        me.value = null
        supported.value = null
    }

    val myHandle: String? get() = me.value?.handle

    /**
     * Zertifikate v2 pruefen und fehlende signieren (still im Hintergrund, nie mit Ausnahme): Handle-Zertifikat, dieses
     * Geraet, andere eigene Geraete nur mit gueltigem v1-Zertifikat, danach die signierte Liste der aktiven Geraete.
     */
    suspend fun certs() {
        val k = svc.keys() ?: return
        lock.withLock {
            try {
                var v = json.decodeFromString(FedMe.serializer(), api.get("api/chat/v2/me"))
                supported.value = true
                me.value = v
                val h = v.handle ?: return
                if (!v.need) return
                if (!v.certOk || v.missing.isNotEmpty()) {
                    val m = json.decodeFromString(ChatMe.serializer(), api.get("api/chat/me?device=${k.dev.id}"))
                    val body = withContext(Dispatchers.Default) {
                        val devs = LinkedHashMap<String, String>()
                        if (k.dev.id in v.missing) devs[k.dev.id] = b64(crypto.deviceSig(h, k.ident.identSk, k.dev.id, k.dev.encPk, k.dev.signPk))
                        for (d in m.devices) {
                            if (d.revoked || d.id == k.dev.id || d.id !in v.missing) continue
                            // nur Geraete, deren v1-Zertifikat zum eigenen Identitaetsschluessel passt (keine untergeschobenen)
                            if (!svc.crypto.verifyDevice(k.uid, k.ident.identPk, d.id, d.encPub, d.signPub, d.sig)) continue
                            val e = unb64(d.encPub, 32) ?: continue
                            val s = unb64(d.signPub, 32) ?: continue
                            devs[d.id] = b64(crypto.deviceSig(h, k.ident.identSk, d.id, e, s))
                        }
                        buildJsonObject {
                            if (!v.certOk) put("handle_sig", b64(crypto.handleSig(h, k.ident)))
                            if (devs.isNotEmpty()) putJsonObject("devices") { devs.forEach { (id, sig) -> put(id, sig) } }
                        }
                    }
                    if (body.isNotEmpty()) {
                        v = json.decodeFromString(FedMe.serializer(), api.request("PUT", "api/chat/v2/cert", body))
                        me.value = v
                    }
                }
                if (v.certOk && !v.deviceSetOk && v.deviceSetWant.isNotEmpty()) {
                    val ds = withContext(Dispatchers.Default) { crypto.deviceSetSig(h, k.ident.identSk, (v.deviceSet?.ver ?: 0) + 1, v.deviceSetWant) }
                    val body = buildJsonObject {
                        putJsonObject("device_set") {
                            put("ver", ds.ver)
                            putJsonArray("ids") { ds.ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                            put("sig", ds.sig)
                        }
                    }
                    me.value = json.decodeFromString(FedMe.serializer(), api.request("PUT", "api/chat/v2/cert", body))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == 404) supported.value = false            // Server vor 2.3
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Schluessel zu Handles (ueber den eigenen Server), selbst geprueft, TOFU angewandt. Eigene Schluessel muessen zu diesem
     * Geraet passen (sonst REJECT). Fehler beim Abruf -> Ausnahme.
     */
    suspend fun keys(handles: Collection<String>, fresh: Boolean = false): Map<String, FedKeyState> {
        val hs = handles.filter { it.isNotEmpty() }.distinct()
        if (hs.isEmpty()) return emptyMap()
        val local = svc.keys()
        val mine = myHandle
        val out = LinkedHashMap<String, FedKeyState>()
        for (part in hs.chunked(20)) {
            val q = java.net.URLEncoder.encode(part.joinToString(","), "UTF-8")
            val r = json.decodeFromString(FedKeysResponse.serializer(), api.get("api/chat/v2/keys?handles=$q" + if (fresh) "&fresh=1" else ""))
            withContext(Dispatchers.Default) {
                for (h in part) {
                    val e = r.keys[h]
                    if (e == null) {
                        out[h] = FedKeyState(null, TofuV2.MISSING)
                        continue
                    }
                    val k = crypto.checkKeys(h, e)
                    if (k == null) {
                        out[h] = FedKeyState(null, TofuV2.REJECT)
                        continue
                    }
                    if (h == mine) {
                        out[h] = FedKeyState(k, if (local != null && k.identPk.contentEquals(local.ident.identPk)) TofuV2.TRUST else TofuV2.REJECT)
                        continue
                    }
                    val (t, st) = withContext(Dispatchers.IO) { svc.store.observe2(h, k.identPub, k.ver) }
                    out[h] = FedKeyState(k, t, e.stale, st)
                }
            }
        }
        return out
    }

    /** "Verstanden" (verified=false) bzw. "Als überprüft markieren" (Sicherheitsnummer v2 verglichen) */
    suspend fun verify(handle: String, identPub: String, verified: Boolean) = withContext(Dispatchers.IO) { svc.store.setTrust2(handle, identPub, verified) }

    /** Sicherheitsnummer v2 mit einer Person eines anderen Servers */
    fun safetyNumber(myHandle: String, theirHandle: String, theirIdent: ByteArray): String? {
        val k = svc.keys() ?: return null
        return crypto.safetyNumber(myHandle, k.ident.identPk, theirHandle, theirIdent)
    }

    // ---------------------------------------------------------------- Nachrichtenanfragen, Sperrliste

    /** POST /api/chats/{id}/request {"action": accept|decline|block, "scope": person|instance} */
    suspend fun answer(chatId: Long, action: String, scope: String = "person") {
        api.post("api/chats/$chatId/request", buildJsonObject {
            put("action", action)
            put("scope", scope)
        })
        svc.loadChats()
    }

    suspend fun blocks(): List<ChatBlock> = json.decodeFromString(ChatBlocks.serializer(), api.get("api/chat/blocks")).blocks

    suspend fun unblock(target: String) {
        api.delete("api/chat/blocks?target=" + java.net.URLEncoder.encode(target, "UTF-8"))
    }

    /** Direkt-Chat mit einer Person eines anderen Servers (Handle aus der Adressbuch-Suche) */
    suspend fun createDm(handle: String): ChatSummary = svc.create(buildJsonObject {
        put("kind", "dm")
        put("handle", handle)
        svc.deviceId?.let { put("device", it) }
    })

    /**
     * 2.3 (E5): freie Gruppe mit Personen anderer Server (Handles aus der Adressbuch-Suche) und Konten hier - Heim ist
     * dieser Server; bei den anderen erscheint die Gruppe als Nachrichtenanfrage.
     */
    suspend fun createGroup(members: List<String>, handles: List<String>): ChatSummary = svc.create(buildJsonObject {
        put("kind", "free")
        putJsonArray("members") { members.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        putJsonArray("handles") { handles.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        svc.deviceId?.let { put("device", it) }
    })

    companion object {
        /** Live-Ereignis chat.keys.changed -> (handle, own) */
        fun keysChanged(data: JsonObject?): Pair<String?, Boolean> {
            val h = (data?.get("handle") as? kotlinx.serialization.json.JsonPrimitive)?.content
            val own = (data?.get("own") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            return h to own
        }
    }
}
