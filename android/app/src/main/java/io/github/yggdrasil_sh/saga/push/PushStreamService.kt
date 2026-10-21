package io.github.yggdrasil_sh.saga.push

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.yggdrasil_sh.saga.MainActivity
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.SagaApplication
import io.github.yggdrasil_sh.saga.util.ServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/** Stand der eigenen Verbindung fuer die Einstellungen */
data class StreamStatus(
    val running: Boolean = false,
    val connected: Boolean = false,
    val intervalMs: Long = KeepAlive.LEVELS_MS[KeepAlive.START],
    val low: Boolean = false,
    /** seit dem Start des Dienstes: Lebenszeichen, Mitteilungen, Verbindungsaufbauten */
    val pings: Int = 0,
    val messages: Int = 0,
    val connects: Int = 0,
    val sinceMs: Long = 0,
)

/**
 * Eigene Push-Verbindung (Server 2.5) - nur, wenn KEIN UnifiedPush-Verteiler installiert ist und der Schalter
 * "Eigene Verbindung" an ist (PushService.ensureStream). Vordergrund-Dienst vom Typ specialUse mit einer
 * unauffaelligen Mitteilung (eigener Kanal, Wichtigkeit "minimal").
 *
 * Sparsam: genau EINE OkHttp-WebSocket-Verbindung, kein OkHttp-Ping (pingInterval 0 - dessen Zeitgeber laeuft im
 * Tiefschlaf ohnehin nicht). Lebenszeichen per AlarmManager.setAndAllowWhileIdle (wirkt auch im Doze, ohne Recht fuer
 * exakte Wecker) im gelernten Abstand ([KeepAlive]); Wakelock nur kurz: beim Lebenszeichen bis zur Antwort und beim
 * Empfang einer Mitteilung bis sie angezeigt ist (Anruf: bis es klingelt). Netz weg -> Verbindung zu, kein Wecker; Netz
 * wieder da -> sofort verbinden (ConnectivityManager-Callback statt Zeitgeber). Fehlschlaege: exponentielles Backoff,
 * ebenfalls per Wecker. Nutzlasten gehen durch denselben Code wie UnifiedPush (PushService.onStreamMessage).
 */
class PushStreamService : Service() {
    companion object {
        const val CHANNEL = "saga_push_stream"
        const val NOTIF_ID = 4201
        const val ACTION_ALARM = "io.github.yggdrasil_sh.saga.push.STREAM_ALARM"
        private const val PONG_TIMEOUT_MS = 20_000L
        private const val WAKE_MS = 15_000L
        /** so lange muss eine Verbindung gestanden haben, damit ihr Tod als "ruhend gestorben" zaehlt */
        private const val MIN_IDLE_DEATH_MS = 40_000L
        private const val NET_GRACE_MS = 10_000L

        val status = MutableStateFlow(StreamStatus())

        /** nur Debug-Build (src/debug, Messung im Emulator): Ziel ohne Anmeldung; in Release-Builds immer null */
        @Volatile var debugTarget: (() -> PushService.StreamTarget?)? = null

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PushStreamService::class.java))
            } catch (_: Exception) {
                // Android 12+: aus dem Hintergrund nicht startbar (z. B. ohne Ausnahme) - naechster App-Start/Neustart holt es nach
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushStreamService::class.java))
        }

        fun channel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, context.getString(R.string.push_stream_channel), NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false)
                ch.description = context.getString(R.string.push_stream_channel_desc)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: SagaApplication
    private lateinit var prefs: android.content.SharedPreferences
    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var gen = 0                        // Nummer der aktuellen Verbindung (alte Rueckrufe ignorieren)
    private var connected = false
    private var connecting = false
    private var openedAt = 0L
    private var lastRx = 0L                    // zuletzt etwas vom Server bekommen (elapsedRealtime)
    private var fails = 0
    private var stopped = false
    private var online = false
    private var netKey = "none"
    private var netChangedAt = 0L
    private var pingOut = false
    private var keep = KeepAlive()
    private val seen = ArrayDeque<Long>()        // zuletzt erhaltene Kennungen (Doppelte nach Wiederaufbau)
    private var wake: PowerManager.WakeLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var pkgRx: BroadcastReceiver? = null
    private val pongTimeout = Runnable { onPongTimeout() }

    /** nur Debug-Build: Ablauf ins Log (Messung im Emulator) - nie Inhalte */
    private fun dbg(s: String) {
        if (io.github.yggdrasil_sh.saga.BuildConfig.DEBUG) android.util.Log.d("SagaPushStream", s)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as SagaApplication
        prefs = getSharedPreferences("saga_push_stream", Context.MODE_PRIVATE)
        channel(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        } catch (_: Exception) {
            stopSelf()
            return
        }
        wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "saga:push-stream")?.apply {
            setReferenceCounted(false)
        }
        status.value = StreamStatus(running = true, sinceMs = System.currentTimeMillis())
        watchPackages()
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ALARM) onAlarm()
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        cancelAlarm()
        main.removeCallbacks(pongTimeout)
        netCb?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        pkgRx?.let { runCatching { unregisterReceiver(it) } }
        closeWs(1000)
        releaseWake()
        status.value = StreamStatus()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Mitteilung (unauffaellig)

    private fun notification(): Notification {
        val open = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, NOTIF_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.push_stream_notif_title))
            .setContentText(getString(R.string.push_stream_notif_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    // ---------------------------------------------------------------- Netz und Verteiler

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val key = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                    else -> "other"
                }
                val up = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                main.post { onNetwork(up, key) }
            }

            override fun onLost(network: Network) {
                main.post { onNetwork(false, "none") }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb, main)
            netCb = cb
        } catch (_: Exception) {
            online = true                     // ohne Callback: einfach verbinden
            connect()
        }
    }

    private fun onNetwork(up: Boolean, key: String) {
        if (stopped) return
        val changed = up != online || (up && key != netKey)
        if (!changed) return
        netChangedAt = SystemClock.elapsedRealtime()
        online = up
        if (key != netKey) {
            saveKeep()
            netKey = key
            keep = KeepAlive.decode(prefs.getString("keep_$key", null))
        }
        if (!up) {
            // offline: Verbindung zu, keine Wecker (kein Aufwachen fuer nichts)
            closeWs(1001)
            cancelAlarm()
            publish()
            return
        }
        fails = 0
        closeWs(1001)                         // anderes Netz: alte Verbindung haengt am alten Netz
        connect()
    }

    /** Wird ein Verteiler installiert, schaltet die App um und dieser Dienst endet */
    private fun watchPackages() {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (UnifiedPush.distributors(context).isNotEmpty()) app.appScope.launch { app.repo.push.ensure() }
            }
        }
        val f = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        pkgRx = rx
    }

    // ---------------------------------------------------------------- Verbindung

    private fun connect() {
        if (stopped || connecting || connected || !online) return
        connecting = true
        val g = ++gen
        app.appScope.launch {
            app.repo.awaitReady()
            val target = debugTarget?.invoke() ?: app.repo.push.streamTarget()
            main.post { if (g == gen) open(g, target) else connecting = false }
        }
    }

    private fun open(g: Int, target: PushService.StreamTarget?) {
        if (stopped) return
        if (target == null) {                 // abgemeldet / kein Abo mehr: PushService entscheidet neu
            connecting = false
            app.appScope.launch { app.repo.push.ensure() }
            return
        }
        val c = client ?: app.repo.api.authClient.newBuilder()
            .pingInterval(0, TimeUnit.MILLISECONDS)          // keine eigenen Pings von OkHttp (siehe oben)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build().also { client = it }
        val req = Request.Builder().url(ServerUrl.absolute(target.server, target.path.trimStart('/')))
            .header("Authorization", "Bearer ${target.token}")
            .build()
        status.value = status.value.copy(connects = status.value.connects + 1)
        ws = c.newWebSocket(req, Listener(g))
    }

    private fun closeWs(code: Int) {
        gen++
        main.removeCallbacks(pongTimeout)
        pingOut = false
        ws?.let { runCatching { it.close(code, null) }; runCatching { it.cancel() } }
        ws = null
        connected = false
        connecting = false
    }

    private inner class Listener(val g: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                if (g != gen) return@post
                connected = true
                connecting = false
                openedAt = SystemClock.elapsedRealtime()
                lastRx = openedAt
                fails = 0
                scheduleAlarm(keep.alarmDelay)
                dbg("open, Abstand ${keep.intervalMs / 1000} s")
                publish()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (g != gen) return
            lastRx = SystemClock.elapsedRealtime()
            when (val f = StreamFrames.parse(bytes.toByteArray())) {
                is StreamFrames.In.Pong -> main.post { if (g == gen) onPong() }
                is StreamFrames.In.Message -> onPush(webSocket, f)
                else -> {}
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            dbg("closing $code")
            webSocket.close(1000, null)
            main.post { if (g == gen) onEnded(code, false) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            dbg("failure ${t.javaClass.simpleName}: ${t.message} (${response?.code})")
            // Protokollfehler (Server/Proxy liefert Unsinn) ist kein Tod der ruhenden Verbindung
            val idle = t !is java.net.ProtocolException
            main.post { if (g == gen) onEnded(response?.code?.let { if (it == 401 || it == 403) 4401 else -it } ?: -1, true, idle) }
        }
    }

    /** Mitteilung (OkHttp-Lesefaden): bestaetigen, kurz wach bleiben, wie UnifiedPush verarbeiten */
    private fun onPush(webSocket: WebSocket, f: StreamFrames.In.Message) {
        dbg("message ${f.id} (${f.body.size} B)")
        acquireWake()
        webSocket.send(StreamFrames.ack(listOf(f.id)).toByteString())
        val dup = synchronized(seen) {
            if (f.id in seen) true else {
                seen.addLast(f.id)
                while (seen.size > 64) seen.removeFirst()
                false
            }
        }
        main.post {
            status.value = status.value.copy(messages = status.value.messages + 1)
            if (connected) scheduleAlarm(keep.alarmDelay)       // Verkehr = Lebenszeichen: naechstes spaeter
        }
        if (dup) {
            releaseWake()
            return
        }
        app.appScope.launch {
            try {
                val work = app.repo.push.onStreamMessage(f.body)
                if (work != null) withTimeoutOrNull(9_000) { work.join() }        // Anruf: bis es klingelt
            } finally {
                releaseWake()
            }
        }
    }

    private fun onEnded(code: Int, failure: Boolean, idleKind: Boolean = true) {
        if (stopped) return
        val wasOpen = connected
        val lived = if (wasOpen) SystemClock.elapsedRealtime() - openedAt else 0
        ws = null
        connected = false
        connecting = false
        main.removeCallbacks(pongTimeout)
        val now = SystemClock.elapsedRealtime()
        val netCalm = now - netChangedAt > NET_GRACE_MS
        // als "ruhend gestorben" zaehlt nur ein Abbruch nach laengerer Stille (mind. halber Abstand), ohne Netzwechsel
        if (StreamDeath.idleDeath(failure && idleKind, wasOpen, online, netCalm, lived, now - lastRx, keep.intervalMs)) {
            keep.onSilentDeath(System.currentTimeMillis())       // NAT/Proxy hat die ruhende Verbindung gekappt
            saveKeep()
        }
        pingOut = false
        releaseWake()
        publish()
        when (StreamClose.after(if (failure) -1 else code)) {
            StreamAfter.STOP_AUTH, StreamAfter.STOP_REPLACED -> {
                cancelAlarm()
                app.appScope.launch { app.repo.push.onStreamStopped(code) }
                return
            }
            StreamAfter.RESUBSCRIBE -> {
                app.appScope.launch { app.repo.push.onStreamStopped(code) }
                fails++
                retry()
            }
            StreamAfter.RECONNECT_LATER -> {
                fails = maxOf(fails + 1, 5)
                retry()
            }
            StreamAfter.RECONNECT -> {
                if (code == 1012) scheduleAlarm(StreamBackoff.restart(Math.random()))
                else if (wasOpen && lived >= MIN_IDLE_DEATH_MS) connect()   // lief eine Weile: gleich wieder
                else {
                    fails++
                    retry()
                }
            }
        }
    }

    private fun retry() {
        if (!online) return                   // der Netz-Callback verbindet, sobald es wieder Netz gibt
        scheduleAlarm(StreamBackoff.delay(fails, Math.random()))
    }

    // ---------------------------------------------------------------- Lebenszeichen

    private fun onAlarm() {
        if (stopped) return
        if (!connected) {
            connect()
            return
        }
        if (pingOut) return
        acquireWake()                          // bis die Antwort da ist (meist < 1 s), hoechstens WAKE_MS
        pingOut = true
        val sent = ws?.send(StreamFrames.ping.toByteString()) == true
        if (!sent) {
            onEnded(-1, true)
            return
        }
        status.value = status.value.copy(pings = status.value.pings + 1)
        dbg("ping")
        main.postDelayed(pongTimeout, PONG_TIMEOUT_MS)
    }

    private fun onPong() {
        dbg("pong")
        main.removeCallbacks(pongTimeout)
        if (pingOut) {
            pingOut = false
            keep.onPong(System.currentTimeMillis())
            saveKeep()
        }
        scheduleAlarm(keep.alarmDelay)
        releaseWake()
        publish()
    }

    private fun onPongTimeout() {
        if (!pingOut) return
        // keine Antwort: die ruhende Verbindung ist tot (NAT-Eintrag weg) -> eine Stufe zurueck, neu verbinden
        val w = ws
        closeWs(1001)
        w?.cancel()
        if (online && SystemClock.elapsedRealtime() - netChangedAt > NET_GRACE_MS) {
            keep.onSilentDeath(System.currentTimeMillis())
            saveKeep()
        }
        releaseWake()
        publish()
        connect()
    }

    private fun alarmPi(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, PushStreamService::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleAlarm(delayMs: Long) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        // inexakt, erlaubt im Doze, ohne SCHEDULE_EXACT_ALARM; ELAPSED_REALTIME_WAKEUP zaehlt auch im Tiefschlaf
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, alarmPi())
    }

    private fun cancelAlarm() {
        getSystemService(AlarmManager::class.java)?.cancel(alarmPi())
    }

    private fun acquireWake() {
        runCatching { wake?.acquire(WAKE_MS) }
    }

    private fun releaseWake() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
    }

    private fun saveKeep() {
        if (netKey != "none") prefs.edit().putString("keep_$netKey", keep.encode()).apply()
    }

    private fun publish() {
        status.value = status.value.copy(running = true, connected = connected, intervalMs = keep.intervalMs, low = keep.low)
    }
}

/** Nach dem Neustart des Geraets bzw. einem App-Update: eigene Verbindung wieder aufnehmen (falls sie lief) */
class PushStreamBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? SagaApplication ?: return
        val pending = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    app.repo.awaitReady()
                    if (!app.repo.needsLogin.value) app.repo.push.ensure()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
