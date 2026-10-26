package io.github.yggdrasil_sh.saga.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.yggdrasil_sh.saga.SagaApplication
import kotlinx.coroutines.launch

/**
 * NUR im Debug-Build (Pruefung im Emulator, ohne Server): eingehenden Anruf mit Beispieldaten klingeln lassen bzw.
 * ablehnen - wie der Push-Weg (Empfaenger mit goAsync, App darf geschlossen sein).
 *   adb shell am broadcast -n io.github.yggdrasil_sh.saga.debug/io.github.yggdrasil_sh.saga.call.DebugRing \
 *     -a io.github.yggdrasil_sh.saga.DEBUG_RING --es op ring|decline|status [--ei ring_s 30]
 */
class DebugRing : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SagaApplication ?: return
        val calls = app.repo.calls
        when (intent.getStringExtra("op")) {
            "decline" -> calls.decline()
            "status" -> resultData = "ringing=${CallRinger.ringingId != null} service=${CallRingService.running} mode=${CallRinger.lastMode}"
            else -> {
                val pending = goAsync()
                val at = System.currentTimeMillis()
                val fg = (intent.flags and Intent.FLAG_RECEIVER_FOREGROUND) != 0
                app.appScope.launch {
                    try {
                        calls.debugRing(intent.getIntExtra("ring_s", 30))
                        calls.holdWhileRinging(at, fg)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
