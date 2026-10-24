package io.github.yggdrasil_sh.saga.util

import android.content.Context
import io.github.yggdrasil_sh.saga.R

/**
 * Bughunt D01: Personen ohne Anzeigenamen nie als rohe Kennung ("~s545sgsgkkyhspqz") zeigen, sondern lesbar:
 * "Unbekannte Person (spqz)" bzw. bei Personen anderer Server "Person auf b.example (spqz)" - die letzten 4 Zeichen der
 * Kennung unterscheiden stabil. Server ab 2.5 liefern Namen schon so (saga/core.unnamed); das hier faengt aeltere Server
 * und Stellen ab, an denen nur die Kennung (username/handle) da ist.
 */
object PersonName {
    private val RAW = Regex("^@?~([a-z2-7]{16})(?:@([^@\\s/]+))?$")

    /** Ohne Namen: Unterscheider (letzte 4 Zeichen) und ggf. Server */
    data class Unnamed(val tag: String, val host: String?)

    /** Kein brauchbarer Name (leer oder selbst eine Kennung) und eine Kennung da -> Unnamed, sonst null */
    fun unnamed(name: String?, username: String? = null, host: String? = null): Unnamed? {
        val n = name?.trim().orEmpty()
        val m = RAW.matchEntire(n) ?: if (n.isEmpty()) RAW.matchEntire(username?.trim().orEmpty()) else null
        m ?: return null
        val h = m.groupValues[2].ifEmpty { host?.trim().orEmpty() }
        return Unnamed(m.groupValues[1].takeLast(4), h.ifEmpty { null })
    }

    /** App-Kontext fuer Texte ausserhalb von Compose (SagaApplication.onCreate) */
    @Volatile var ctx: Context? = null

    fun format(c: Context, u: Unnamed): String =
        if (u.host != null) c.getString(R.string.person_on_host, u.host, u.tag) else c.getString(R.string.person_unknown, u.tag)

    /** Name zum Zeigen: Name, sonst lesbarer Ersatz statt Kennung, sonst username */
    fun shown(name: String?, username: String? = null, host: String? = null, c: Context? = ctx): String {
        val u = unnamed(name, username, host)
        if (u != null && c != null) return format(c, u)
        return name?.trim()?.takeIf { it.isNotEmpty() } ?: username.orEmpty()
    }
}
