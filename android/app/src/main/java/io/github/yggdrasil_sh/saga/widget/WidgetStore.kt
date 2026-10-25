package io.github.yggdrasil_sh.saga.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Zuletzt bekannte Widget-Daten (app-privat, kein Backup: allowBackup=false). Enthaelt KEINE Zugangsdaten - das Widget
 * laedt ueber die Sitzung der App (ApiClient, Geraete-Schluessel im SecureStore). Beim Abmelden geleert.
 *
 * version: zaehlt jede Aenderung - die Glance-Ansichten lesen sie als State und zeigen Neues sofort.
 */
class WidgetStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    var signedOut: Boolean
        get() = prefs.getBoolean(K_SIGNED_OUT, false)
        set(v) = write { putBoolean(K_SIGNED_OUT, v) }

    fun today(): TodaySnapshot? = read(K_TODAY) { json.decodeFromString(TodaySnapshot.serializer(), it) }
    fun setToday(s: TodaySnapshot) = write { putString(K_TODAY, json.encodeToString(TodaySnapshot.serializer(), s)); remove(K_TODAY_FAILED) }

    fun todayFailedAt(): Long? = prefs.getLong(K_TODAY_FAILED, 0L).takeIf { it > 0 }
    fun setTodayFailed(at: Long) = write { putLong(K_TODAY_FAILED, at) }

    /** Abhaken im Widget ist fehlgeschlagen (Hinweis bis zum naechsten erfolgreichen Abruf) */
    var doneFailed: Boolean
        get() = prefs.getBoolean(K_DONE_FAILED, false)
        set(v) = write { putBoolean(K_DONE_FAILED, v) }

    // ---- gerade abgehakt (sofort ausblenden, bis der Server es bestaetigt hat; laeuft nach HIDE_MS ab)
    fun hidden(now: Long = System.currentTimeMillis()): Set<Int> =
        prefs.getStringSet(K_HIDDEN, emptySet()).orEmpty().mapNotNull { e ->
            val (id, ts) = e.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
            id.takeIf { now - ts < HIDE_MS }
        }.toSet()

    fun hide(id: Int, now: Long = System.currentTimeMillis()) = write {
        val keep = prefs.getStringSet(K_HIDDEN, emptySet()).orEmpty().filter { !it.startsWith("$id:") && (it.substringAfter(':').toLongOrNull() ?: 0) > now - HIDE_MS }
        putStringSet(K_HIDDEN, (keep + "$id:$now").toSet())
    }

    fun unhide(id: Int) = write { putStringSet(K_HIDDEN, prefs.getStringSet(K_HIDDEN, emptySet()).orEmpty().filter { !it.startsWith("$id:") }.toSet()) }

    // ---- Seiten-Widgets: gewaehlte Seite je Widget und Stand je Seite
    fun pageFor(widgetId: Int): Long? = prefs.getLong(K_CFG + widgetId, 0L).takeIf { it != 0L }
    fun setPageFor(widgetId: Int, pageId: Long) = write { putLong(K_CFG + widgetId, pageId) }
    fun removeWidget(widgetId: Int) = write { remove(K_CFG + widgetId) }
    fun configuredPages(): Set<Long> = prefs.all.filterKeys { it.startsWith(K_CFG) }.values.mapNotNull { (it as? Long)?.takeIf { v -> v != 0L } }.toSet()

    fun page(id: Long): PageSnapshot? = read(K_PAGE + id) { json.decodeFromString(PageSnapshot.serializer(), it) }
    fun setPage(s: PageSnapshot) = write { putString(K_PAGE + s.id, json.encodeToString(PageSnapshot.serializer(), s)); remove(K_PAGE_FAILED + s.id) }
    fun pageFailedAt(id: Long): Long? = prefs.getLong(K_PAGE_FAILED + id, 0L).takeIf { it > 0 }
    fun setPageFailed(id: Long, at: Long) = write { putLong(K_PAGE_FAILED + id, at) }

    /**
     * Abmelden / Kontowechsel: alle Inhalte (Aufgaben, Termine, Seitentexte) weg. Die Seiten-Auswahl je Widget (nur
     * IDs) bleibt: nach erneuter Anmeldung laedt das Widget wieder; ein anderes Konto bekommt die Seite nicht (404).
     */
    fun clearContent(signedOut: Boolean) = write {
        prefs.all.keys.filter { it == K_TODAY || it == K_TODAY_FAILED || it == K_HIDDEN || it == K_DONE_FAILED || it.startsWith(K_PAGE) || it.startsWith(K_PAGE_FAILED) }
            .forEach { remove(it) }
        putBoolean(K_SIGNED_OUT, signedOut)
    }

    private inline fun <T> read(key: String, f: (String) -> T): T? = prefs.getString(key, null)?.let {
        try {
            f(it)
        } catch (_: Exception) {
            null
        }
    }

    private inline fun write(crossinline block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(commit = true) { block() }
        _version.value = _version.value + 1
    }

    companion object {
        const val PREFS = "saga_widgets"
        const val HIDE_MS = 6 * 60 * 60 * 1000L          // wartet der Auftrag auf Netz, bleibt die Aufgabe so lange weg
        private const val K_SIGNED_OUT = "signed_out"
        private const val K_TODAY = "today"
        private const val K_TODAY_FAILED = "today_failed"
        private const val K_DONE_FAILED = "done_failed"
        private const val K_HIDDEN = "hidden"
        private const val K_CFG = "cfg_"
        private const val K_PAGE = "page_"
        private const val K_PAGE_FAILED = "pfail_"

        private val _version = MutableStateFlow(0)
        val version: StateFlow<Int> = _version
    }
}
