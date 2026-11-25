package io.github.yggdrasil_sh.saga.chat

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 2.6 (Bugs-DB 2029-7): Schalter "System-Nicht-stoeren uebernehmen" (Standard aus, je Geraet). Ist er an, meldet die App
 * jede Aenderung des Unterbrechungsfilters (NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) als Fokus-Status:
 * NUR {state: "dnd"|"available", source: "focus"} (UserStatusLogic.focusBody) - nie Name oder Text des Modus (RT-29).
 * Ein Fokus-Ende hebt einen selbst gesetzten Status nicht auf (der Server antwortet kept: true).
 * Das System liefert den Broadcast nur an zur Laufzeit angemeldete Empfaenger: ohne laufenden Prozess gleicht die App
 * beim naechsten Start (bzw. beim Einschalten) mit dem aktuellen Filter ab.
 */
object StatusFocus {
    private const val PREFS = "saga_ui"
    private const val KEY = "status_focus"
    private val lock = Mutex()
    /** zuletzt gemeldet (null = noch nichts in diesem Prozess) */
    @Volatile private var sent: Boolean? = null
    @Volatile private var registered = false

    fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(ctx: Context, repo: SagaRepository, scope: CoroutineScope, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        sent = null
        if (on) {
            register(ctx.applicationContext, repo, scope)
            scope.launch { sync(ctx.applicationContext, repo, force = true) }
        }
    }

    /** Filter des Systems jetzt (RingLogic.FILTER_*) */
    fun filter(ctx: Context): Int = try {
        ctx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: 0
    } catch (_: Exception) {
        0
    }

    /** Beim App-Start: Empfaenger anmelden und - sobald der eigene Status geladen ist - abgleichen */
    fun start(ctx: Context, repo: SagaRepository, scope: CoroutineScope) {
        register(ctx.applicationContext, repo, scope)
        if (!enabled(ctx)) return
        scope.launch {
            repo.chat.status.view.filterNotNull().first()
            sync(ctx.applicationContext, repo, force = false)
        }
    }

    private fun register(app: Context, repo: SagaRepository, scope: CoroutineScope) {
        if (registered) return
        registered = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED || !enabled(c)) return
                val pending = goAsync()
                scope.launch {
                    try {
                        sync(c.applicationContext, repo, force = false)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(app, r, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Aktuellen Filter melden (gleicher Stand wie zuletzt -> nichts, ausser force) */
    suspend fun sync(ctx: Context, repo: SagaRepository, force: Boolean) = lock.withLock {
        if (!enabled(ctx) || !repo.chat.hasUserStatus()) return@withLock
        val dnd = UserStatusLogic.focusDnd(filter(ctx)) ?: return@withLock
        if (!force && sent == dnd) return@withLock
        try {
            repo.chat.status.focus(dnd)
            sent = dnd
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // offline: beim naechsten Wechsel bzw. Start erneut
        }
    }
}
