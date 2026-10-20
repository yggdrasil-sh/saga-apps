package io.github.yggdrasil_sh.saga.util

import java.net.URI

/** Server-Adresse aus einer Eingabe pruefen und vereinheitlichen (https, ohne '/' am Ende). */
object ServerUrl {
    /** Nur fuer Debug-Builds: lokaler Testserver im Emulator (http://10.0.2.2:PORT) */
    private val LOCAL_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    /**
     * Nur fuer Debug-Builds: http zu einem Testserver im privaten Netz (TT 2.4 P-07) - Emulator/localhost, IPv4
     * 10/8, 172.16/12, 192.168/16, 169.254/16, IPv6 fc00::/7 und fe80::/10 sowie *.local. Release: nie (allowLocalHttp).
     */
    fun isPrivateHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (h.isEmpty()) return false
        if (h in LOCAL_HOSTS || h == "::1" || h.endsWith(".local")) return true
        val v4 = h.split('.')
        if (v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } }) {
            val o = v4.map { it.toInt() }
            if (o.any { it > 255 }) return false
            return o[0] == 10 || o[0] == 127 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) ||
                (o[0] == 169 && o[1] == 254)
        }
        if (h.contains(':') && h.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '%' || it == '.' }) {
            val first = h.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
            return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
        }
        return false
    }

    fun isLocalHttp(url: String): Boolean = try {
        val u = URI(url)
        u.scheme?.lowercase() == "http" && isPrivateHost(u.host)
    } catch (_: Exception) {
        false
    }

    /** null = ungueltig (kein https, kein Host). allowLocalHttp: Debug-Build mit lokalem Testserver */
    fun normalize(input: String, allowLocalHttp: Boolean = false): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        s = s.trimEnd('/')
        val u = try {
            URI(s)
        } catch (_: Exception) {
            return null
        }
        if (u.host.isNullOrEmpty()) return null
        if (u.scheme?.lowercase() != "https" && !(allowLocalHttp && isLocalHttp(s))) return null
        if (u.rawQuery != null || u.rawFragment != null) return null
        return s
    }

    /** Pfad (z. B. /files/abc) oder volle Adresse -> volle Adresse auf dem Server */
    fun absolute(server: String, pathOrUrl: String): String =
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) pathOrUrl
        else server.trimEnd('/') + "/" + pathOrUrl.trimStart('/')
}
