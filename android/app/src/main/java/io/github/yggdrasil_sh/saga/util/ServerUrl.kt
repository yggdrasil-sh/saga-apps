package io.github.yggdrasil_sh.saga.util

import java.net.URI

/** Server-Adresse aus einer Eingabe pruefen und vereinheitlichen (https, ohne '/' am Ende). */
object ServerUrl {
    /** Nur fuer Debug-Builds: lokaler Testserver im Emulator (http://10.0.2.2:PORT) */
    private val LOCAL_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    fun isLocalHttp(url: String): Boolean = try {
        val u = URI(url)
        u.scheme?.lowercase() == "http" && u.host in LOCAL_HOSTS
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
