package io.github.yggdrasil_sh.saga.call

/**
 * ICE-Server aus der Serverantwort pruefen (pt2 A4) - ohne Android, Unit-Tests.
 *
 * Regel: nur stun:/stuns:/turn:/turns: in strenger Form (host[:port][?transport=udp|tcp]). Nie Loopback, Link-Local,
 * unspezifizierte, Multicast-/reservierte Adressen oder "localhost"; Zahlen-Hosts nur als normale IPv4 (kein
 * "2130706433", "0x7f.1"). Private Adressen (RFC 1918, 100.64/10, fc00::/7) und *.local nur, wenn der Saga-Server
 * selbst im lokalen Netz liegt (serverLocal: Adresse privat bzw. Name loest privat auf) - Heimnetz-Betrieb mit TURN
 * im LAN geht so weiter, ein Server im Internet kann die App aber nicht auf Geraete im Heimnetz zeigen lassen.
 * Namen werden nicht aufgeloest (das macht WebRTC selbst); sie gelten als oeffentlich.
 */
object IceFilter {
    enum class Kind { PUBLIC, PRIVATE, BLOCKED }

    private val URL = Regex("""^(stuns?|turns?):(\[[0-9A-Fa-f:.%]{2,60}]|[A-Za-z0-9.-]{1,253})(:[0-9]{1,5})?(\?transport=(udp|tcp))?$""", RegexOption.IGNORE_CASE)
    private val HEXLABEL = Regex("""^0x[0-9a-f]*$""")

    /** Host einer ICE-Adresse oder null (Form ungueltig) */
    fun hostOf(url: String): String? {
        val m = URL.matchEntire(url.trim()) ?: return null
        val port = m.groupValues[3].removePrefix(":")
        if (port.isNotEmpty() && port.toInt() !in 1..65535) return null
        return m.groupValues[2].trim('[', ']')
    }

    fun classify(host: String): Kind {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return Kind.BLOCKED
        if (h == "localhost" || h.endsWith(".localhost") || h == "localhost.localdomain" || h == "ip6-localhost") return Kind.BLOCKED
        if (h.contains(':')) return v6(h)
        v4(h)?.let { return it }
        // keine normale IPv4, aber als Kurzform aufloesbar (127.1, 2130706433, 0x7f.1, 0177.0.0.1)
        if (h.all { it.isDigit() || it == '.' } || h.split('.').any { HEXLABEL.matches(it) }) return Kind.BLOCKED
        if (h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return Kind.PRIVATE
        return Kind.PUBLIC
    }

    private fun v4(h: String): Kind? {
        val p = h.split('.')
        if (p.size != 4 || p.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) || (it.length > 1 && it[0] == '0') }) return null
        val n = p.map { it.toInt() }
        if (n.any { it > 255 }) return null
        return v4kind(n[0], n[1])
    }

    private fun v4kind(a: Int, b: Int): Kind = when {
        a == 0 || a == 127 || (a == 169 && b == 254) || a >= 224 -> Kind.BLOCKED     // unspez., Loopback, Link-Local, Multicast/reserviert
        a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127) -> Kind.PRIVATE
        else -> Kind.PUBLIC
    }

    private fun v6(h: String): Kind {
        val addr = h.substringBefore('%')
        if (!addr.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }) return Kind.BLOCKED
        val g = expand(addr) ?: return Kind.BLOCKED
        if (g.all { it == 0 }) return Kind.BLOCKED                                      // ::
        if (g.take(7).all { it == 0 } && g[7] == 1) return Kind.BLOCKED                  // ::1
        if (g.take(5).all { it == 0 } && (g[5] == 0xffff || g[5] == 0)) {                 // ::ffff:a.b.c.d bzw. ::a.b.c.d
            return v4kind(g[6] shr 8, g[6] and 0xff)
        }
        val first = g[0]
        return when {
            first and 0xffc0 == 0xfe80 -> Kind.BLOCKED                                   // fe80::/10 Link-Local
            first and 0xff00 == 0xff00 -> Kind.BLOCKED                                   // Multicast
            first and 0xfe00 == 0xfc00 -> Kind.PRIVATE                                   // fc00::/7 ULA
            first and 0xffc0 == 0xfec0 -> Kind.PRIVATE                                   // fec0::/10 (alt, site-local)
            else -> Kind.PUBLIC
        }
    }

    /** IPv6 in 8 Gruppen (eingebettete IPv4 am Ende erlaubt); null = ungueltig */
    private fun expand(a: String): List<Int>? {
        var s = a
        var v4: List<Int>? = null
        val lastColon = s.lastIndexOf(':')
        if (lastColon < 0) return null
        if (s.substring(lastColon + 1).contains('.')) {
            val q = s.substring(lastColon + 1).split('.')
            if (q.size != 4) return null
            val n = q.map { x -> x.toIntOrNull()?.takeIf { it in 0..255 && x.length in 1..3 } ?: return null }
            v4 = listOf((n[0] shl 8) or n[1], (n[2] shl 8) or n[3])
            s = s.substring(0, lastColon + 1) + "0:0"
        }
        val parts = s.split("::")
        if (parts.size > 2) return null
        fun groups(x: String): List<Int>? =
            if (x.isEmpty()) emptyList() else x.split(':').map { g -> if (g.isEmpty() || g.length > 4) return null else g.toIntOrNull(16) ?: return null }
        val head = groups(parts[0]) ?: return null
        val all = if (parts.size == 2) {
            val rest = groups(parts[1]) ?: return null
            val fill = 8 - head.size - rest.size
            if (fill < 1) return null
            head + List(fill) { 0 } + rest
        } else head
        if (all.size != 8) return null
        return if (v4 != null) all.take(6) + v4 else all
    }

    fun allowed(url: String, serverLocal: Boolean): Boolean {
        val h = hostOf(url) ?: return false
        return when (classify(h)) {
            Kind.PUBLIC -> true
            Kind.PRIVATE -> serverLocal
            Kind.BLOCKED -> false
        }
    }

    /** Adressen eines ICE-Servers filtern (Reihenfolge bleibt, hoechstens 8) */
    fun filter(urls: List<String>, serverLocal: Boolean): List<String> = urls.filter { allowed(it, serverLocal) }.take(8)
}
