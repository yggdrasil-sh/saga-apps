package io.github.yggdrasil_sh.saga.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (S256) fuer die Anmeldung im Browser: /app/login?challenge=... -> saga://auth?code=...
 * -> POST /app/exchange {code, verifier}. Der Server erwartet challenge mit genau 43 Zeichen
 * (base64url ohne '=' von SHA-256) und einen verifier mit 43..128 Zeichen.
 */
object Pkce {
    data class Pair(val verifier: String, val challenge: String)

    private fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun create(random: SecureRandom = SecureRandom()): Pair {
        val bytes = ByteArray(48).also { random.nextBytes(it) }
        val verifier = b64url(bytes)                    // 64 Zeichen
        return Pair(verifier, challenge(verifier))
    }

    fun challenge(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
