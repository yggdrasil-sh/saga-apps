package io.github.yggdrasil_sh.saga.util

/**
 * Links in Freitext erkennen (Chat, KI-Antwort, Kommentare) - ohne Android, Unit-Tests.
 * Nur http(s): kein javascript:, intent:, file: usw. Geoeffnet wird immer ueber ui.common.openUrl (Standard-Browser).
 */
object Links {
    data class Part(val text: String, val url: String? = null)

    private val URL_RE = Regex("""\bhttps?://[^\s<>"']+""")
    private val TRAIL = Regex("""[).,;:!?\]]+$""")

    /** Text in Stuecke zerlegen; url != null = antippbarer Link (Satzzeichen am Ende gehoeren nicht dazu) */
    fun split(text: String): List<Part> {
        val out = ArrayList<Part>()
        var last = 0
        var m = URL_RE.find(text, 0)
        while (m != null) {
            val u = m.value.replace(TRAIL, "")
            if (m.range.first > last) out.add(Part(text.substring(last, m.range.first)))
            if (u.isNotEmpty()) out.add(Part(u, u))
            last = m.range.first + u.length
            m = if (last < text.length) URL_RE.find(text, maxOf(last, m.range.first + 1)) else null
        }
        if (last < text.length) out.add(Part(text.substring(last)))
        return out
    }
}
