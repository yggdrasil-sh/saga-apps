package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.data.SecureStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Private Chat-Schluessel dieses Geraets fuer ein Konto (Identitaet + Backup + Geraet) */
class LocalChatKeys(val uid: String, val ident: Identity, val dev: DeviceKeys)

@Serializable
private data class StoredKeys(
    val uid: String,
    val device_id: String,
    val enc_pk: String,
    val enc_sk: String,
    val sign_pk: String,
    val sign_sk: String,
    val ident_pk: String,
    val ident_sk: String,
    val backup_pk: String,
    val backup_sk: String,
)

@Serializable
private data class StoredPending(val keys: StoredKeys, val recovery: String)

@Serializable
private data class StoredLink(val id: String, val code: String, val expires: Double? = null, val keys: StoredKeys)

/** Gemerkter Identitaetsschluessel eines Gegenuebers (TOFU), ueberprueft = Sicherheitsnummer verglichen */
@Serializable
data class KnownIdent(val k: String, val verified: Boolean = false, val changed: Boolean = false)

/** Ergebnis der TOFU-Pruefung */
enum class TofuResult { NEW, SAME, CHANGED }

/**
 * Ablage der Chat-Schluessel - nur verschluesselt im Android Keystore (SecureStore), nie im Klartext auf der Platte,
 * nie an den Server. Je Server + Konto (scope) getrennt.
 */
class ChatStore(private val secure: SecureStore, private val scope: () -> String) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun k(name: String) = "chat_${name}:" + scope()

    fun keys(): LocalChatKeys? {
        val s = secure.get(k("keys")) ?: return null
        return try {
            val d = json.decodeFromString(StoredKeys.serializer(), s)
            LocalChatKeys(
                d.uid,
                Identity(unb64(d.ident_pk, 32)!!, unb64(d.ident_sk, 64)!!, unb64(d.backup_pk, 32)!!, unb64(d.backup_sk, 32)!!),
                DeviceKeys(d.device_id, unb64(d.enc_pk, 32)!!, unb64(d.enc_sk, 32)!!, unb64(d.sign_pk, 32)!!, unb64(d.sign_sk, 64)!!),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveKeys(k: LocalChatKeys) {
        val d = StoredKeys(
            k.uid, k.dev.id, b64(k.dev.encPk), b64(k.dev.encSk), b64(k.dev.signPk), b64(k.dev.signSk),
            b64(k.ident.identPk), b64(k.ident.identSk), b64(k.ident.backupPk), b64(k.ident.backupSk),
        )
        secure.put(k("keys"), json.encodeToString(StoredKeys.serializer(), d))
    }

    /** Geraet entfernt / Neu beginnen: Schluessel loeschen (TOFU bleibt) */
    fun clearKeys() = secure.put(k("keys"), null)

    // ---------------------------------------------------------------- Einrichtung/Kopplung in Arbeit
    // Ueberleben Prozess-Tod (App im Hintergrund beim Sichern des Schluessels bzw. beim Bestaetigen auf dem anderen
    // Geraet): derselbe Wiederherstellungsschluessel wird wieder gezeigt (BUG-022), dieselbe Kopplungsanfrage
    // weiter abgefragt statt ein Geister-Geraet zu hinterlassen (BUG-068). Wie die Schluessel nur verschluesselt.

    fun pending(): PendingSetup? = try {
        secure.get(k("pending"))?.let { s ->
            val d = json.decodeFromString(StoredPending.serializer(), s)
            PendingSetup(identOf(d.keys), devOf(d.keys), d.recovery)
        }
    } catch (_: Exception) {
        null
    }

    fun savePending(p: PendingSetup) =
        secure.put(k("pending"), json.encodeToString(StoredPending.serializer(), StoredPending(stored("", p.ident, p.dev), p.recoveryKey)))

    fun clearPending() = secure.put(k("pending"), null)

    fun link(): LinkSession? = try {
        secure.get(k("link"))?.let { s ->
            val d = json.decodeFromString(StoredLink.serializer(), s)
            LinkSession(d.id, d.code, devOf(d.keys), d.expires)
        }
    } catch (_: Exception) {
        null
    }

    fun saveLink(l: LinkSession) {
        val dev = l.dev
        val d = StoredKeys("", dev.id, b64(dev.encPk), b64(dev.encSk), b64(dev.signPk), b64(dev.signSk), "", "", "", "")
        secure.put(k("link"), json.encodeToString(StoredLink.serializer(), StoredLink(l.id, l.code, l.expires, d)))
    }

    fun clearLink() = secure.put(k("link"), null)

    private fun stored(uid: String, i: Identity, d: DeviceKeys) = StoredKeys(
        uid, d.id, b64(d.encPk), b64(d.encSk), b64(d.signPk), b64(d.signSk),
        b64(i.identPk), b64(i.identSk), b64(i.backupPk), b64(i.backupSk),
    )

    private fun identOf(d: StoredKeys) = Identity(unb64(d.ident_pk, 32)!!, unb64(d.ident_sk, 64)!!, unb64(d.backup_pk, 32)!!, unb64(d.backup_sk, 32)!!)
    private fun devOf(d: StoredKeys) = DeviceKeys(d.device_id, unb64(d.enc_pk, 32)!!, unb64(d.enc_sk, 32)!!, unb64(d.sign_pk, 32)!!, unb64(d.sign_sk, 64)!!)

    // ---------------------------------------------------------------- TOFU

    fun known(): Map<String, KnownIdent> = try {
        secure.get(k("tofu"))?.let { json.decodeFromString<Map<String, KnownIdent>>(it) } ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun saveKnown(m: Map<String, KnownIdent>) = secure.put(k("tofu"), json.encodeToString(m))

    /** Identitaetsschluessel eines Gegenuebers pruefen und merken; Wechsel -> CHANGED (Warnung, bis man ihn ansieht) */
    @Synchronized
    fun observe(uid: String, identPub: String): TofuResult {
        val m = known().toMutableMap()
        val old = m[uid]
        return when {
            old == null -> {
                m[uid] = KnownIdent(identPub)
                saveKnown(m)
                TofuResult.NEW
            }
            old.k == identPub -> TofuResult.SAME
            else -> {
                m[uid] = KnownIdent(identPub, verified = false, changed = true)
                saveKnown(m)
                TofuResult.CHANGED
            }
        }
    }

    // ---------------------------------------------------------------- TOFU v2 (2.3, je foederiertem Handle, getrennt von v1)

    fun trust2(): Map<String, Trust2> = try {
        secure.get(k("tofu2"))?.let { json.decodeFromString<Map<String, Trust2>>(it) } ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    /** Regel FedFormat.tofu anwenden und ggf. speichern -> (Ergebnis, gemerkter Stand danach) */
    @Synchronized
    fun observe2(handle: String, identPub: String, ver: Long): Pair<TofuV2, Trust2?> {
        val m = trust2().toMutableMap()
        val st = FedFormat.tofu(m[handle], identPub, ver)
        st.store?.let {
            m[handle] = it
            secure.put(k("tofu2"), json.encodeToString(m))
        }
        return st.result to (st.store ?: m[handle])
    }

    /** "Verstanden" (verified=false, Warnung weg) bzw. "Als überprüft markieren" (Sicherheitsnummer v2 verglichen) */
    @Synchronized
    fun setTrust2(handle: String, identPub: String, verified: Boolean) {
        val m = trust2().toMutableMap()
        val old = m[handle]
        m[handle] = Trust2(identPub, verified = verified, dver = if (old?.ident == identPub) old.dver else 0)
        secure.put(k("tofu2"), json.encodeToString(m))
    }

    /** Warnung gesehen (Sicherheitsnummer angezeigt) bzw. "Als überprüft markieren" */
    @Synchronized
    fun acknowledge(uid: String, verified: Boolean) {
        val m = known().toMutableMap()
        val old = m[uid] ?: return
        m[uid] = old.copy(changed = false, verified = verified || old.verified)
        saveKnown(m)
    }
}
