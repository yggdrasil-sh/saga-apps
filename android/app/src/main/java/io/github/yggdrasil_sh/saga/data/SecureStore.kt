package io.github.yggdrasil_sh.saga.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Geheimnisse (Geraete-Schluessel hkd_..., App-Schluessel hk_..., PKCE-Geheimnis waehrend der Anmeldung)
 * verschluesselt ablegen: AES-256-GCM mit einem Schluessel im Android Keystore (verlaesst das Geraet nie).
 * Ist der Keystore-Schluessel weg (z. B. nach Zuruecksetzen), gelten die Werte als nicht vorhanden.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("saga_secure", Context.MODE_PRIVATE)
    private val alias = "saga_secure_v1"

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

    fun get(name: String): String? = try {
        prefs.getString(name, null)?.let { v ->
            val raw = Base64.decode(v, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).commit()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).commit()
    }

    var deviceToken: String?
        get() = get("device_token")?.takeIf { it.startsWith("hkd_") }
        set(v) = put("device_token", v)

    var appKey: String?
        get() = get("app_key")?.takeIf { it.startsWith("hk_") }
        set(v) = put("app_key", v)

    var pkceVerifier: String?
        get() = get("pkce_verifier")
        set(v) = put("pkce_verifier", v)

    fun clear() {
        prefs.edit().clear().commit()
    }
}
