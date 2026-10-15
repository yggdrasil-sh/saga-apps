package io.github.yggdrasil_sh.saga.live

/** Ein Ereignis aus einem text/event-stream (Live-Kanal, KI-Assistent) */
data class SseEvent(val id: String?, val event: String, val data: String)

/**
 * Zeilenweiser Leser fuer Server-Sent Events: "id:", "event:", "data:" (mehrere data-Zeilen mit \n verbunden),
 * Leerzeile = Ereignis fertig. Kommentarzeilen (":" ...) und "retry:" werden gemeldet bzw. ignoriert.
 */
class SseParser(private val onComment: (String) -> Unit = {}) {
    private var id: String? = null
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Eine Zeile (ohne Zeilenende) -> fertiges Ereignis oder null */
    fun feed(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            if (!hasData && event == null) {
                id = null
                return null
            }
            val e = SseEvent(id, event ?: "message", data.toString())
            id = null
            event = null
            data.setLength(0)
            hasData = false
            return e
        }
        if (line.startsWith(":")) {
            onComment(line.substring(1).trim())
            return null
        }
        val i = line.indexOf(':')
        val field = if (i < 0) line else line.substring(0, i)
        var value = if (i < 0) "" else line.substring(i + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "id" -> id = value
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}
