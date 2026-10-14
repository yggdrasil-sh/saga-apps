package io.github.yggdrasil_sh.saga.auth

import io.github.yggdrasil_sh.saga.util.ServerUrl
import java.net.URI
import java.net.URLDecoder

/** Ziel einer QR-Kopplung: Server-Adresse + einmaliger Kopplungs-Code */
data class PairTarget(val server: String, val code: String)

/**
 * Kopplungs-Link aus dem QR-Code erkennen (wie Store.parsePairLink in iOS):
 *  - https://server/app/pair?c=CODE  (auch unter einem Unterpfad: https://host/saga/app/pair?c=...)
 *  - saga://pair?u=https://server&c=CODE  (aeltere Apps: hlidskjalf://pair?...)
 */
object PairLink {
    private val CODE_RE = Regex("[A-Za-z0-9_-]{20,64}")
    private val APP_SCHEMES = setOf("saga", "hlidskjalf")

    fun parse(text: String): PairTarget? {
        val uri = try {
            URI(text.trim())
        } catch (_: Exception) {
            return null
        }
        val q = query(uri.rawQuery)
        val code = q["c"] ?: return null
        if (!CODE_RE.matches(code)) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if ((scheme == "https" || scheme == "http") && !uri.host.isNullOrEmpty()) {
            val path = (uri.path ?: "").trimEnd('/')
            if (!path.endsWith("/app/pair")) return null
            val base = path.removeSuffix("/app/pair")
            val port = if (uri.port > 0) ":${uri.port}" else ""
            return PairTarget("$scheme://${uri.host}$port$base", code)
        }
        if (scheme in APP_SCHEMES && uri.host == "pair") {
            val srv = q["u"] ?: return null
            val su = try {
                URI(srv)
            } catch (_: Exception) {
                return null
            }
            val ss = su.scheme?.lowercase()
            if ((ss != "https" && ss != "http") || su.host.isNullOrEmpty()) return null
            return PairTarget(srv.trim().trimEnd('/'), code)
        }
        return null
    }

    /**
     * Darf an dieses Ziel gekoppelt werden? Nur https; http nur fuer den lokalen Testserver und nur, wenn
     * allowLocalHttp (Debug-Build). Liefert das Ziel mit vereinheitlichter Server-Adresse, sonst null.
     */
    fun check(t: PairTarget, allowLocalHttp: Boolean): PairTarget? {
        if (!t.server.contains("://")) return null              // kein stilles https:// davor - Links muessen vollstaendig sein
        val s = ServerUrl.normalize(t.server, allowLocalHttp) ?: return null
        if (!CODE_RE.matches(t.code)) return null
        return PairTarget(s, t.code)
    }

    /** Host (mit Port) fuer die Anzeige im Bestaetigungsdialog */
    fun displayHost(server: String): String = try {
        val u = URI(server)
        (u.host ?: server) + (if (u.port > 0) ":${u.port}" else "") + (u.rawPath?.trimEnd('/') ?: "")
    } catch (_: Exception) {
        server
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val k = URLDecoder.decode(part.substring(0, i), "UTF-8")
            val v = URLDecoder.decode(part.substring(i + 1), "UTF-8")
            out.putIfAbsent(k, v)
        }
        return out
    }
}
