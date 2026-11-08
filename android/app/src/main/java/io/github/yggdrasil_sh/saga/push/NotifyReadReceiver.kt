package io.github.yggdrasil_sh.saga.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.yggdrasil_sh.saga.SagaApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Design-Runde 4 (CH-04): Aktion "Als gelesen markieren" an einer System-Mitteilung - ohne App-Start. Entfernt die
 * Mitteilung sofort und markiert auf dem Server die ganze Gespraechseinheit (POST /api/notifications/read {groups}) sowie
 * passende einzelne Eintraege ueber die url (chat.mention, Seiten ohne Buendelung; alte Server ohne groups).
 * Nicht exportiert: nur die eigene (unveraenderliche) PendingIntent erreicht ihn.
 */
class NotifyReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Nachbesserung (Pruefer offen #5): Mitteilung weggewischt -> Sammel-Mitteilung neu zaehlen
        if (intent.action == ACTION_DISMISSED) {
            Notifier.refreshSummaries(context)
            return
        }
        if (intent.action != ACTION) return
        val app = context.applicationContext as? SagaApplication ?: return
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val group = intent.getStringExtra(EXTRA_GROUP)
        val url = intent.getStringExtra(EXTRA_URL)
        Notifier.cancel(context, tag)
        // Nachbesserung (Pruefer offen #7): Mitteilung eines anderen (frueheren) Kontos/Servers - nur wegnehmen, nichts senden
        if (!sameAccount(intent.getStringExtra(EXTRA_ACCOUNT), app.repo.push.accountTag())) return
        val pending = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(9_000) { markRead(app, group, url) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "io.github.yggdrasil_sh.saga.action.MARK_READ"
        const val EXTRA_TAG = "tag"
        const val EXTRA_GROUP = "group"
        const val EXTRA_URL = "url"
        /** Kurze Kennung von Server + Konto (Account.tag, kein Geheimnis) */
        const val EXTRA_ACCOUNT = "account"
        const val ACTION_DISMISSED = "io.github.yggdrasil_sh.saga.action.NOTIFY_DISMISSED"

        /**
         * Gespraechseinheit (group) bzw. passende Eintraege (url) auf dem Server als gelesen markieren - auch nach einer
         * Antwort aus der Mitteilung (NotifyReplyWorker). Fehler still (die Glocke zieht spaeter nach).
         */
        suspend fun markRead(app: SagaApplication, group: String?, url: String?) {
            if (app.repo.needsLogin.value) return
            val live = app.repo.live
            if (group != null) {
                try {
                    live.markRead(emptyList(), listOf(group))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            if (url != null) live.markReadForUrl(url)
        }

        /** Aktion gilt nur fuer das Konto, fuer das die Mitteilung gezeigt wurde; ohne Kennung (aeltere Mitteilung) nicht. Rein (Tests). */
        fun sameAccount(fromIntent: String?, current: String): Boolean = !fromIntent.isNullOrEmpty() && fromIntent == current
    }
}
