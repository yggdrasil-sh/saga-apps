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
        if (intent.action != ACTION) return
        val app = context.applicationContext as? SagaApplication ?: return
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val group = intent.getStringExtra(EXTRA_GROUP)
        val url = intent.getStringExtra(EXTRA_URL)
        Notifier.cancel(context, tag)
        val pending = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(9_000) {
                    if (app.repo.needsLogin.value) return@withTimeoutOrNull
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
    }
}
