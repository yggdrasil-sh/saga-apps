package io.github.yggdrasil_sh.saga.util

/**
 * Welche Links die App an andere Apps weitergibt (pt2 A1) - ohne Android, Unit-Tests. Links aus Nachrichten,
 * Linkvorschauen, KI-Antworten und Server-Antworten sind fremde Daten: nur http(s) mit Host und mailto gehen an
 * ACTION_VIEW. Alles andere (tel:, geo:, market:, intent:, file:, content:, javascript:, saga://pair ...) wird nicht
 * geoeffnet, sondern nur kopiert. Ausnahme (Entscheidung 3.11.): tel: aus DB-Telefonzellen (allowPhone/phoneOk). Seiten-/Chat-Ziele der App laufen ueber die eigene Navigation, nie ueber openUrl.
 */
object LinkPolicy {
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    fun scheme(url: String): String? = SCHEME.find(url.trim())?.groupValues?.get(1)?.lowercase()

    private val TEL = Regex("^tel:\\+?[0-9]{3,32}$")

    /** Nur fuer Telefonnummern, die die App selbst aus einer DB-Zelle vom Typ Telefon baut (DbValues.safeLink):
     *  streng tel:+Ziffern, nichts anderes. Fremde tel:-Links (Nachrichten, Vorschauen, KI) bleiben gesperrt. */
    fun phoneOk(url: String): Boolean = TEL.matches(url)

    fun externalOk(url: String, allowPhone: Boolean = false): Boolean {
        if (allowPhone && phoneOk(url)) return true
        val u = url.trim()
        if (u.isEmpty() || u.length > 8192 || u.any { it.isISOControl() || it.isWhitespace() }) return false
        return when (scheme(u)) {
            "http", "https" -> {
                val rest = u.substringAfter(':')
                if (!rest.startsWith("//")) return false
                val auth = rest.removePrefix("//").substringBefore('/').substringBefore('?').substringBefore('#')
                auth.substringAfterLast('@').isNotEmpty()
            }
            "mailto" -> u.length > "mailto:".length
            else -> false
        }
    }
}
