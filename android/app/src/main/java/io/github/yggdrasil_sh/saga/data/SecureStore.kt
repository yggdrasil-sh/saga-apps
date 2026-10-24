package io.github.yggdrasil_sh.saga.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Schluessel-Ablage (fuer Tests austauschbar) */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String?)
    fun clear()
    /** Namen aller abgelegten Werte (A10: kontobezogene Chat-Eintraege beim Abmelden finden) */
    fun names(): Set<String> = emptySet()
}

/** Nur im Speicher (Unit-Tests) */
class MemorySecretStore : SecretStore {
    private val m = ConcurrentHashMap<String, String>()
    override fun get(name: String): String? = m[name]
    override fun put(name: String, value: String?) {
        if (value == null) m.remove(name) else m[name] = value
    }
    override fun clear() = m.clear()
    override fun names(): Set<String> = m.keys.toSet()
}

/**
 * Geheimnisse (Geraete-Schluessel hkd_..., App-Schluessel hk_..., PKCE-Geheimnis waehrend der Anmeldung)
 * verschluesselt ablegen: AES-256-GCM mit einem Schluessel im Android Keystore (verlaesst das Geraet nie).
 * Ist der Keystore-Schluessel weg (z. B. nach Zuruecksetzen), gelten die Werte als nicht vorhanden.
 * Entschluesselte Werte bleiben im Speicher (Keystore nur beim ersten Lesen bzw. beim Schreiben);
 * Schreiben ueber apply() - nicht blockierend.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("saga_secure", Context.MODE_PRIVATE)
    private val alias = "saga_secure_v1"
    private val cache = ConcurrentHashMap<String, String>()
    private val absent: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    override fun get(name: String): String? {
        cache[name]?.let { return it }
        if (name in absent) return null
        val v = try {
            prefs.getString(name, null)?.let { v ->
                val raw = Base64.decode(v, Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
                String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
        if (v == null) absent.add(name) else cache[name] = v
        return v
    }

    override fun put(name: String, value: String?) {
        if (value == null) {
            cache.remove(name)
            absent.add(name)
            prefs.edit { remove(name) }
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        cache[name] = value
        absent.remove(name)
        prefs.edit { putString(name, Base64.encodeToString(out, Base64.NO_WRAP)) }
    }

    override fun clear() {
        cache.clear()
        absent.clear()
        prefs.edit { clear() }
    }

    override fun names(): Set<String> = try {
        prefs.all.keys.toSet()
    } catch (_: Exception) {
        emptySet()
    }
}

/**
 * Zugangsdaten der App. Geraete- und App-Schluessel gehoeren immer zu genau einem Server (tokenServer):
 * Erneuern und Abmelden gehen nur an diesen Server, nie an einen anderen (Schutz vor untergeschobenen Kopplungslinks).
 */
class SecureStore(private val store: SecretStore) {
    constructor(context: Context) : this(KeystoreSecretStore(context))

    fun get(name: String): String? = store.get(name)
    fun put(name: String, value: String?) = store.put(name, value)

    /**
     * A10: alle Chat-Eintraege (Schluessel, TOFU, Lesestand, Einrichtung/Kopplung in Arbeit) eines Kontos loeschen -
     * scope wie in ChatStore ("<server>|<konto>"); andere Konten bleiben unberuehrt. -> Anzahl geloeschter Eintraege
     */
    fun clearChat(scope: String): Int {
        if (scope.isEmpty() || scope == "|") return 0
        val doomed = store.names().filter { it.startsWith("chat_") && it.endsWith(":$scope") }
        doomed.forEach { store.put(it, null) }
        return doomed.size
    }

    var deviceToken: String?
        get() = get("device_token")?.takeIf { it.startsWith("hkd_") }
        set(v) = put("device_token", v)

    var appKey: String?
        get() = get("app_key")?.takeIf { it.startsWith("hk_") }
        set(v) = put("app_key", v)

    /** Server, fuer den device_token und app_key ausgestellt wurden */
    var tokenServer: String?
        get() = get("token_server")
        set(v) = put("token_server", v)

    var pkceVerifier: String?
        get() = get("pkce_verifier")
        set(v) = put("pkce_verifier", v)

    /** Server der laufenden Browser-Anmeldung (wird erst nach Erfolg uebernommen) */
    var pkceServer: String?
        get() = get("pkce_server")
        set(v) = put("pkce_server", v)

    /** Geraete-Schluessel nur, wenn er zu diesem Server gehoert */
    fun deviceTokenFor(server: String): String? = deviceToken?.takeIf { server.isNotEmpty() && tokenServer == server }

    fun appKeyFor(server: String): String? = appKey?.takeIf { server.isNotEmpty() && tokenServer == server }

    fun setCredentials(server: String, device: String?, app: String?) {
        tokenServer = server
        deviceToken = device
        appKey = app
    }

    fun clearCredentials() {
        deviceToken = null
        appKey = null
        tokenServer = null
    }

    fun clear() = store.clear()
}
