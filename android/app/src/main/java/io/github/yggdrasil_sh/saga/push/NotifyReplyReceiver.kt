package io.github.yggdrasil_sh.saga.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.SagaApplication
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.ChatSetup
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Design-Runde 5 (CH-04): Aktion "Antworten" (RemoteInput) an einer Chat-Mitteilung - ohne App-Start. Nimmt den Text,
 * prueft das Konto (wie "Als gelesen markieren"), legt den Auftrag verschluesselt ab und reiht NotifyReplyWorker ein
 * (WorkManager: uebersteht Prozesstod, Rueckzug bei Netzfehlern). Nicht exportiert: nur die eigene PendingIntent
 * (explizit, wegen RemoteInput veraenderlich) erreicht ihn.
 */
class NotifyReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotifyReply.ACTION) return
        val app = context.applicationContext as? SagaApplication ?: return
        val tag = intent.getStringExtra(NotifyReply.EXTRA_TAG) ?: return
        val url = intent.getStringExtra(NotifyReply.EXTRA_URL).orEmpty()
        val chat = intent.getLongExtra(NotifyReply.EXTRA_CHAT, 0L)
        val account = intent.getStringExtra(NotifyReply.EXTRA_ACCOUNT)
        val text = NotifyReply.clean(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotifyReply.KEY_TEXT))
        // Mitteilung eines anderen (frueheren) Kontos/Servers: nichts senden, nur wegnehmen
        if (!NotifyReadReceiver.sameAccount(account, app.repo.push.accountTag())) {
            Notifier.cancel(context, tag)
            return
        }
        if (text == null || chat <= 0L) {
            Notifier.replied(context, tag, url, null, Notifier.ReplyState.NONE)          // nur Ladekreis beenden
            return
        }
        val job = NotifyReply.Job(
            UUID.randomUUID().toString(), account.orEmpty(), chat, text, tag, url,
            intent.getStringExtra(NotifyReply.EXTRA_GROUP), System.currentTimeMillis(),
        )
        try {
            app.repo.secure.put(NotifyReply.STORE_PREFIX + job.id, NotifyReply.encode(job))
            NotifyReplyWorker.enqueue(context, job.id)
        } catch (_: Exception) {
            // Ablage/Einreihen unmoeglich: Text in den Chat legen, damit nichts verloren geht
            try {
                val store = app.repo.chat.store
                store.saveDraft(chat, NotifyReply.park(store.draft(chat), text))
            } catch (_: Exception) {
            }
            Notifier.replied(context, tag, url, null, Notifier.ReplyState.FAILED)
        }
    }
}

/**
 * Sendet eine Antwort aus der Mitteilung ueber denselben Weg wie der Chat (ChatSession.send - versiegelt fuer alle
 * Empfaenger-Geraete, v1/Stufe 3/ueber Servergrenzen) und markiert danach gelesen. Erster Fehler: Text liegt als
 * Entwurf im Chat, Mitteilung zeigt eine kurze Fehlerzeile; Netzfehler/5xx: spaeter erneut (NotifyReply.classify).
 */
class NotifyReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val jobId = params.inputData.getString(KEY_JOB).orEmpty()

    /** nur fuer beschleunigte Arbeit vor Android 12 (Vordergrund-Arbeit): leise "Wird gesendet …" */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        NotifyChannels.ensure(ctx)
        val n = NotificationCompat.Builder(ctx, NotifyChannels.CHATS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NotifyDesign.ACCENT)
            .setContentTitle(ctx.getString(R.string.r5n_reply_sending))
            .setSilent(true).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val nid = NOTE_ID
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(nid, n)
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? SagaApplication ?: return Result.failure()
        val repo = app.repo
        val key = NotifyReply.STORE_PREFIX + jobId
        var job = NotifyReply.decode(repo.secure.get(key)) ?: return Result.failure()
        val ctx = applicationContext
        val store = repo.chat.store
        when (NotifyReply.before(job.account, repo.push.accountTag(), job.parked, store.draft(job.chat), job.text)) {
            NotifyReply.Step.WRONG_ACCOUNT -> {
                // Konto inzwischen gewechselt: nicht unter einem anderen Konto senden
                repo.secure.put(key, null)
                Notifier.replied(ctx, job.tag, job.url, null, Notifier.ReplyState.FAILED)
                return Result.failure()
            }
            NotifyReply.Step.TAKEN_OVER -> {
                // im Chat selbst gesendet oder verworfen
                repo.secure.put(key, null)
                return Result.success()
            }
            NotifyReply.Step.SEND -> Unit
        }
        try {
            send(repo, job)
        } catch (e: CancellationException) {
            throw e                                                     // vom System gestoppt: WorkManager startet neu
        } catch (e: Exception) {
            val fail = NotifyReply.classify((e as? ApiException)?.code, e is IOException, runAttemptCount)
            val first = !job.parked
            if (first) {
                try {
                    store.saveDraft(job.chat, NotifyReply.park(store.draft(job.chat), job.text))
                    job = job.copy(parked = true)
                    repo.secure.put(key, NotifyReply.encode(job))
                } catch (_: Exception) {
                }
            }
            if (fail == NotifyReply.Fail.RETRY) {
                if (first) Notifier.replied(ctx, job.tag, job.url, null, Notifier.ReplyState.RETRY)
                return Result.retry()
            }
            repo.secure.put(key, null)
            Notifier.replied(ctx, job.tag, job.url, null, Notifier.ReplyState.FAILED)
            return Result.failure()
        }
        repo.secure.put(key, null)
        if (job.parked) {
            try {
                store.saveDraft(job.chat, NotifyReply.unpark(store.draft(job.chat), job.text))
            } catch (_: Exception) {
            }
        }
        Notifier.replied(ctx, job.tag, job.url, job.text, Notifier.ReplyState.SENT)
        // Antworten heisst gelesen (wie "Als gelesen markieren"); der Chat selbst setzt den Lese-Zeiger beim Nachladen
        try {
            withTimeoutOrNull(9_000) { NotifyReadReceiver.markRead(app, job.group, job.url) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return Result.success()
    }

    /** wie im Chat: Stand pruefen, Unterhaltung (Zusammenfassung) laden, senden */
    private suspend fun send(repo: SagaRepository, job: NotifyReply.Job) {
        if (repo.needsLogin.value) throw UiTextException(UiText.Res(R.string.chat_not_set_up))
        val svc = repo.chat
        if (svc.me.value == null || svc.state.value != ChatSetup.READY) svc.refresh()
        when (svc.state.value) {
            ChatSetup.READY -> Unit
            ChatSetup.LOADING, ChatSetup.ERROR -> throw IOException("chat/me")              // Netz: spaeter erneut
            else -> throw UiTextException(UiText.Res(R.string.chat_not_set_up))
        }
        withContext(Dispatchers.Main) {
            val s = ChatSession(job.chat, svc)
            s.loadSummary()
            s.send(job.text)
        }
    }

    companion object {
        const val KEY_JOB = "job"
        private const val NOTE_ID = 0x5a6b
        private const val TAG = "notify_reply"

        fun enqueue(ctx: Context, id: String) {
            val req = OneTimeWorkRequestBuilder<NotifyReplyWorker>()
                .setInputData(workDataOf(KEY_JOB to id))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(TAG + ":" + id, ExistingWorkPolicy.KEEP, req)
        }
    }
}
