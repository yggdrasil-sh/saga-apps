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

    /** Warnung gesehen (Sicherheitsnummer angezeigt) bzw. "Als überprüft markieren" */
    @Synchronized
    fun acknowledge(uid: String, verified: Boolean) {
        val m = known().toMutableMap()
        val old = m[uid] ?: return
        m[uid] = old.copy(changed = false, verified = verified || old.verified)
        saveKnown(m)
    }
}
