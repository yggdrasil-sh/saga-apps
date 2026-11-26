package io.github.yggdrasil_sh.saga.push

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.yggdrasil_sh.saga.SagaApplication
import io.github.yggdrasil_sh.saga.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * 2.6 (Bugs-DB 2270, K-C): Knopf an einer Auftrags-Mitteilung (approve, undo, later, quiet) - ohne App-Start.
 * POST /api/ai/jobs/{id}/act {action, atok, pv} mit dem Geraete-Schluessel hkd_ (docs/APP-API-KI.md Abschnitt 12.3).
 * Reaktion je Code (Vektoren tokens.client): 200/404 Mitteilung weg; 409/410/Netz (nach einer Wiederholung) neutraler
 * Hinweis "Bitte in Saga ansehen" -> /saga?tab=jobs&job=<id>; 403 "App oeffnen" - aus einem Empfaenger darf Android 12+
 * keine Activity starten (Notification-Trampolin), daher derselbe Hinweis, Antippen oeffnet Saga.
 * approve/undo bei gesperrtem Geraet (KeyguardManager.isDeviceLocked, z. B. Android < 12 ohne setAuthenticationRequired):
 * nichts senden, Hinweis. Nicht exportiert: nur die eigene PendingIntent (unveraenderlich) erreicht ihn.
 */
class JobActReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "io.github.yggdrasil_sh.saga.JOB_ACT"
        const val EXTRA_ACTION = "a"
        const val EXTRA_TAG = "tag"
        const val EXTRA_JOB = "job"
        const val EXTRA_PV = "pv"
        const val EXTRA_TOKEN = "atok"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_CHANNEL = "channel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as? SagaApplication ?: return
        val a = intent.getStringExtra(EXTRA_ACTION) ?: return
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: NotifyChannels.JOBS_FEEDBACK
        val job = intent.getLongExtra(EXTRA_JOB, 0L).takeIf { it > 0 }
        val pv = if (intent.hasExtra(EXTRA_PV)) intent.getIntExtra(EXTRA_PV, 0) else null
        // Mitteilung eines anderen (frueheren) Kontos/Servers: nichts senden, nur wegnehmen
        if (!NotifyReadReceiver.sameAccount(intent.getStringExtra(EXTRA_ACCOUNT), app.repo.push.accountTag())) {
            Notifier.cancel(context, tag)
            return
        }
        val locked = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        val body = JobNotifyLogic.actBody(a, intent.getStringExtra(EXTRA_TOKEN), pv)
        if (job == null || body == null || (a in JobNotifyLogic.AUTH && locked)) {
            JobNotify.hint(context, tag, channel, job)
            return
        }
        val pending = goAsync()
        app.appScope.launch {
            try {
                var status: Int? = null
                for (attempt in 0..1) {
                    status = withTimeoutOrNull(12_000) { post(app, job, body) }
                    if (!JobNotifyLogic.retry(status, attempt)) break
                }
                when (JobNotifyLogic.outcome(status)) {
                    JobNotifyLogic.Outcome.REMOVE -> Notifier.cancel(context, tag)
                    else -> JobNotify.hint(context, tag, channel, job)
                }
                app.repo.live.loadNotificationCount()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                JobNotify.hint(context, tag, channel, job)
            } finally {
                pending.finish()
            }
        }
    }

    /** Status der Antwort; null = Netzfehler. Ohne Geraete-Schluessel fuer diesen Server: 403 */
    private suspend fun post(app: SagaApplication, job: Long, body: kotlinx.serialization.json.JsonObject): Int? {
        val repo = app.repo
        val tok = withContext(Dispatchers.IO) { repo.secure.deviceTokenFor(repo.server.value) } ?: return 403
        return try {
            repo.api.request("POST", "api/ai/jobs/$job/act", body, bearer = tok)
            200
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            e.code
        } catch (_: IOException) {
            null
        }
    }
}
