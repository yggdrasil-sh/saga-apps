package io.github.yggdrasil_sh.saga.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Dateien (Chat-Anhaenge, Nextcloud-Dateien) mit einer anderen App oeffnen bzw. teilen.
 *
 * Oeffnen ist ein schlichtes ACTION_VIEW ohne Chooser und ohne festes Paket: Android zeigt dann seine Auswahl mit
 * "Nur diesmal / Immer" bzw. nimmt direkt die als Standard gemerkte App. Ein Intent.createChooser kennt kein "Immer"
 * (die Auswahl kam jedes Mal). Der Chooser bleibt nur fuers Teilen (ACTION_SEND) - und als Ausweg, wenn keine App
 * den Typ anzeigen kann (BUG-108: z. B. "In Dateien speichern").
 */
object OpenWith {
    const val FALLBACK = "application/octet-stream"
    private val MIME_RE = Regex("""^[a-z0-9][a-z0-9.+_-]*/[a-z0-9][a-z0-9.+_-]*$""")

    /** Was gestartet wird - reine Daten, damit die Regeln ohne Android pruefbar sind */
    data class Plan(val action: String, val type: String, val flags: Int, val chooser: Boolean, val title: String? = null)

    /**
     * MIME-Typ fuer den Intent: klein, ohne Parameter (";charset=…"); leer, kaputt oder octet-stream -> nach der
     * Dateiendung raten (guess, in der App MimeTypeMap), sonst octet-stream.
     */
    fun mime(m: String?, name: String = "", guess: (String) -> String? = { null }): String {
        val t = m?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (MIME_RE.matches(t) && t != FALLBACK) return t
        val ext = name.substringAfterLast('.', "").lowercase()
        val g = if (ext.isNotEmpty() && ext.length <= 10) guess(ext)?.lowercase() else null
        return if (g != null && MIME_RE.matches(g)) g else FALLBACK
    }

    fun viewPlan(type: String): Plan =
        Plan(Intent.ACTION_VIEW, type, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK, chooser = false)

    fun sharePlan(type: String, title: String?): Plan =
        Plan(Intent.ACTION_SEND, type, Intent.FLAG_GRANT_READ_URI_PERMISSION, chooser = true, title = title)

    fun intent(p: Plan, uri: Uri): Intent {
        val i = Intent(p.action).addFlags(p.flags)
        if (p.action == Intent.ACTION_SEND) i.setType(p.type).putExtra(Intent.EXTRA_STREAM, uri) else i.setDataAndType(uri, p.type)
        return if (p.chooser) Intent.createChooser(i, p.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else i
    }

    private fun guessFromSystem(ext: String): String? = try {
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    } catch (_: Exception) {
        null
    }

    /** Oeffnen (Standard-App bzw. Systemauswahl mit "Immer"); findet sich keine App: Teilen-Auswahl */
    fun open(context: Context, uri: Uri, mime: String?, name: String) {
        val type = mime(mime, name, ::guessFromSystem)
        try {
            context.startActivity(intent(viewPlan(type), uri))
        } catch (_: ActivityNotFoundException) {
            share(context, uri, type, name)
        }
    }

    /** Teilen bzw. speichern ueber die Systemauswahl */
    fun share(context: Context, uri: Uri, mime: String?, name: String) {
        context.startActivity(intent(sharePlan(mime(mime, name, ::guessFromSystem), name), uri))
    }
}
