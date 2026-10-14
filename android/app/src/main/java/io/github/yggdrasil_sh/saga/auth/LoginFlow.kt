package io.github.yggdrasil_sh.saga.auth

import java.net.URI
import java.util.Locale

/** Was beim Koppeln mit dem bisherigen Konto passiert (Text im Kopplungsdialog, Nachtest N1) */
sealed interface PairSwitch {
    /** nicht angemeldet: kein Hinweis */
    data object None : PairSwitch

    /** anderer Server: "Du bist gerade bei {alt} angemeldet und wirst dort abgemeldet." */
    data class OtherServer(val host: String) : PairSwitch

    /** derselbe Server: "Du wirst als „{name}“ abgemeldet ..." - account leer = Name unbekannt */
    data class SameServer(val account: String) : PairSwitch

    companion object {
        fun of(signedIn: Boolean, currentServer: String, targetServer: String, account: String): PairSwitch = when {
            !signedIn || currentServer.isBlank() -> None
            sameServer(currentServer, targetServer) -> SameServer(account.trim())
            else -> OtherServer(PairLink.displayHost(currentServer))
        }

        /** Gleiche Server-Adresse (Schema, Host ohne Gross/Klein, Port mit Standardport, Unterpfad ohne "/" am Ende) */
        fun sameServer(a: String, b: String): Boolean {
            val x = key(a) ?: return false
            return x == key(b)
        }

        private fun key(s: String): String? = try {
            val u = URI(s.trim())
            val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return null
            val host = u.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (u.port > 0) u.port else if (scheme == "https") 443 else if (scheme == "http") 80 else -1
            "$scheme://$host:$port${(u.path ?: "").trimEnd('/')}"
        } catch (_: Exception) {
            null
        }
    }
}

/** Rueckkehr aus dem Browser (saga://auth?...) - was ist passiert? (Nachtest P7) */
sealed interface BrowserReturn {
    data class Code(val code: String) : BrowserReturn

    /** im Browser abgebrochen (Server: error=cancelled) */
    data object Cancelled : BrowserReturn

    /** anderer Fehler vom Server (error=...) */
    data class Failed(val error: String) : BrowserReturn

    /** zurueck ohne Code und ohne Fehler */
    data object NoCode : BrowserReturn

    companion object {
        private val CANCELLED = setOf("cancelled", "canceled", "access_denied")

        fun of(code: String?, error: String?): BrowserReturn {
            code?.trim()?.takeIf { it.isNotEmpty() }?.let { return Code(it) }
            val e = error?.trim().orEmpty()
            return when {
                e.isEmpty() -> NoCode
                e.lowercase(Locale.ROOT) in CANCELLED -> Cancelled
                // nur Fehlerkennungen anzeigen (a-z, _), nie fremden Freitext aus dem Link
                else -> Failed(e.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it == '_' }.take(40).ifEmpty { "unknown" })
            }
        }
    }
}
