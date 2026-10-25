package io.github.yggdrasil_sh.saga.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.yggdrasil_sh.saga.BuildConfig
import io.github.yggdrasil_sh.saga.SagaApplication
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.TaskItem
import io.github.yggdrasil_sh.saga.data.TasksResponse
import io.github.yggdrasil_sh.saga.util.ShotMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Daten fuer die Widgets - ueber denselben API-Code und dieselbe Anmeldung wie die App (SagaRepository/ApiClient:
 * Sitzung, stille Erneuerung mit dem Geraete-Schluessel aus dem SecureStore). Kein eigener Schluessel, nichts im Klartext.
 */
object WidgetSync {
    /** Aufgaben (offen) und Termine fuer heute/morgen holen; Fehler -> alter Stand bleibt, Zeitpunkt des Fehlers merken */
    suspend fun refreshToday(repo: SagaRepository, store: WidgetStore) {
        val today = LocalDate.now()
        try {
            val open = repo.json.decodeFromString(TasksResponse.serializer(), repo.api.get("api/tasks")).open
            val evs = repo.events(2)
            val old = store.today()
            store.setToday(
                TodaySnapshot(
                    day = today.toString(),
                    fetchedAt = System.currentTimeMillis(),
                    tasks = TodayLogic.forSnapshot(open, today.plusDays(1)),
                    // Kalender nicht lesbar: bisherige Termine behalten (wenn vom selben Tag), Hinweis im Widget
                    events = evs?.map { WEvent(it.summary, it.start, it.end, it.allday, it.color, it.location) }
                        ?: old?.events?.takeIf { old.day == today.toString() }.orEmpty(),
                    eventsFailed = evs == null,
                ),
            )
            store.doneFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            store.setTodayFailed(System.currentTimeMillis())
        }
    }

    /** Titel, Symbol und Anfang des Inhalts einer Seite */
    suspend fun refreshPage(repo: SagaRepository, store: WidgetStore, id: Long) {
        try {
            val p = repo.loadPage(id)
            val body = (p["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            store.setPage(
                PageSnapshot(
                    id = id,
                    title = BlockDoc.str(p, "title"),
                    icon = BlockDoc.str(p, "icon").ifEmpty { null },
                    excerpt = PageExcerpt.fromBody(body, BlockDoc.str(p, "format")),
                    isDatabase = BlockDoc.str(p, "kind") == "database",
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404 || e.code == 403 || e.code == 410) {
                store.setPage(PageSnapshot(id = id, title = store.page(id)?.title.orEmpty(), missing = true, fetchedAt = System.currentTimeMillis()))
            } else {
                store.setPageFailed(id, System.currentTimeMillis())
            }
        } catch (_: Exception) {
            store.setPageFailed(id, System.currentTimeMillis())
        }
    }

    /** Alles aktualisieren, was ein Widget auf dem Homescreen braucht */
    suspend fun refreshAll(context: Context) {
        val store = WidgetStore(context)
        val has = WidgetUpdates.present(context)
        if (!has.today && !has.page) {                        // letztes Widget entfernt: periodischen Abgleich beenden
            WidgetUpdates.cancel(context)
            return
        }
        if (BuildConfig.DEBUG && ShotMode.on) {                 // nur Debug: neutrale Beispieldaten fuer Bildschirmfotos
            WidgetDemo.fill(store)
        } else {
            val repo = (context.applicationContext as SagaApplication).repo
            repo.awaitReady()
            if (repo.needsLogin.value) {
                if (!store.signedOut || store.today() != null) store.clearContent(signedOut = true)
            } else {
                if (store.signedOut) store.signedOut = false
                if (has.today) refreshToday(repo, store)
                if (has.page) store.configuredPages().forEach { refreshPage(repo, store, it) }
            }
        }
        WidgetUpdates.redraw(context)
    }
}

/** Planung: periodisch (30 min, nur mit Netz) und sofort nach Aenderungen in der App */
object WidgetUpdates {
    private const val PERIODIC = "saga-widgets"
    private const val NOW = "saga-widgets-now"

    data class Present(val today: Boolean, val page: Boolean)

    suspend fun present(context: Context): Present {
        val m = GlanceAppWidgetManager(context)
        return try {
            Present(m.getGlanceIds(TodayWidget::class.java).isNotEmpty(), m.getGlanceIds(PageWidget::class.java).isNotEmpty())
        } catch (_: Exception) {
            Present(false, false)
        }
    }

    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Periodischer Abgleich - Android legt ihn mit anderen Hintergrundarbeiten zusammen (Akku) */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES, 10, TimeUnit.MINUTES).setConstraints(net).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    /** Einmal bald aktualisieren (ersetzt einen noch wartenden Auftrag; mehrere Aenderungen -> ein Abruf) */
    fun requestNow(context: Context, delayMs: Long = 0) {
        val req = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setConstraints(net)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * Aenderungen in der App (Aufgaben, Termine, Seitenbaum, An-/Abmelden) -> Widgets bald neu laden. Gebuendelt
     * (3 s), und nur wenn ueberhaupt ein Widget auf dem Homescreen liegt.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    suspend fun followApp(context: Context, repo: SagaRepository) {
        kotlinx.coroutines.flow.merge(
            repo.tasks.drop(1).map { },
            repo.todayEvents.drop(1).map { },
            repo.pages.drop(1).map { },
            repo.needsLogin.drop(1).map { },
        ).debounce(3_000).collect {
            val has = present(context)
            if (has.today || has.page) requestNow(context)
        }
    }

    /** Aufgabe im Widget abgehakt: ausblenden, Widget neu zeichnen, Server ueber WorkManager (wartet auf Netz) */
    suspend fun markDone(context: Context, taskId: Int) {
        val store = WidgetStore(context)
        store.hide(taskId)
        store.doneFailed = false
        TodayWidget().updateAll(context)
        val req = OneTimeWorkRequestBuilder<TaskDoneWorker>().setConstraints(net)
            .setInputData(Data.Builder().putInt(TaskDoneWorker.KEY_ID, taskId).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("saga-widget-done-$taskId", ExistingWorkPolicy.KEEP, req)
    }

    suspend fun redraw(context: Context) {
        try {
            TodayWidget().updateAll(context)
            PageWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetSync.refreshAll(applicationContext)
        return Result.success()
    }
}

/** Abhaken an den Server - dieselbe Logik wie in der App (PATCH api/tasks/{id}, Folgeaufgabe bei Wiederholung) */
class TaskDoneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getInt(KEY_ID, 0).takeIf { it != 0 } ?: return Result.failure()
        val ctx = applicationContext
        val store = WidgetStore(ctx)
        if (BuildConfig.DEBUG && ShotMode.on) return Result.success()
        val repo = (ctx as SagaApplication).repo
        repo.awaitReady()
        if (repo.needsLogin.value) {
            store.unhide(id)
            return Result.failure()
        }
        return try {
            repo.setTaskDone(TaskItem(id = id), true)         // aktualisiert auch die Aufgaben der App
            WidgetSync.refreshToday(repo, store)
            store.unhide(id)
            WidgetUpdates.redraw(ctx)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 404: Aufgabe gibt es nicht mehr (woanders geloescht) - einfach neu laden; sonst wieder zeigen + Hinweis
            store.unhide(id)
            if (e.code != 404) store.doneFailed = true
            WidgetSync.refreshToday(repo, store)
            WidgetUpdates.redraw(ctx)
            Result.failure()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else {
                store.unhide(id)
                store.doneFailed = true
                WidgetUpdates.redraw(ctx)
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_ID = "task"
    }
}
