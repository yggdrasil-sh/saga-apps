package io.github.yggdrasil_sh.saga.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * NUR im Debug-Build (Messung im Emulator): eigene Verbindung gegen einen Testserver starten/beenden, ohne Anmeldung.
 *   adb shell am broadcast -n io.github.yggdrasil_sh.saga/.push.DebugStream -a io.github.yggdrasil_sh.saga.DEBUG_STREAM \
 *     --es server http://10.0.2.2:18300 --es token hkd_test     (ohne server: beenden)
 * Die App muss dabei im Vordergrund sein (Android erlaubt sonst keinen Start eines Vordergrund-Dienstes).
 */
class DebugStream : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val server = intent.getStringExtra("server")
        if (server.isNullOrEmpty()) {
            PushStreamService.debugTarget = null
            PushStreamService.stop(context)
            return
        }
        val target = PushService.StreamTarget(server, intent.getStringExtra("path") ?: "/api/push/stream", intent.getStringExtra("token") ?: "hkd_test")
        PushStreamService.debugTarget = { target }
        PushStreamService.start(context)
    }
}
