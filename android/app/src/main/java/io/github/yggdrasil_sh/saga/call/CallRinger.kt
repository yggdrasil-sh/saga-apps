package io.github.yggdrasil_sh.saga.call

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Die App klingelt selbst (2.5, siehe RingLogic): Standard-Klingelton in Schleife (Ringtone.isLooping ab Android 9,
 * davor MediaPlayer) mit USAGE_NOTIFICATION_RINGTONE - Ruftonlautstaerke, Stumm/Vibration und Bitte nicht stoeren
 * gelten - plus wiederholte Vibration. Ein Anruf = ein Ringer; stop() ist jederzeit sicher (auch doppelt).
 * Alles auf dem Haupt-Thread (CallManager).
 */
object CallRinger {
    private const val TAG = "SagaRing"

    /** Anruf, fuer den es gerade klingelt (null = still) */
    @Volatile var ringingId: String? = null
        private set
    private var ringtone: Ringtone? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    /** Fuer die Pruefung im Emulator (nur Debug-Ausgabe): was tatsaechlich lief */
    @Volatile var lastMode: RingLogic.Mode? = null
        private set

    /** Ton wartet auf den Klingel-Dienst (Android 17 schaltet Hintergrund-Wiedergabe ohne Vordergrund-Dienst stumm) */
    private var pendingSound = false

    /** Ton und Vibration nach Geraet (ohne zu starten) */
    fun mode(context: Context): RingLogic.Mode = currentMode(context.applicationContext)

    /**
     * Klingeln beginnen. sound = RingLogic.sound(...): NOW sofort, AFTER_SERVICE erst in serviceUp(), NONE gar nicht
     * (dann klingelt der Ersatzkanal der Mitteilung). Vibration beginnt immer sofort (nach Geraet).
     */
    fun start(context: Context, id: String, sound: RingLogic.Sound = RingLogic.Sound.NOW) {
        if (!RingLogic.shouldStart(ringingId, id)) return
        stopMedia()                                           // vorheriges Klingeln - NICHT den eben gestarteten Dienst
        ringingId = id
        val app = context.applicationContext
        val mode = currentMode(app)
        lastMode = mode
        Log.i(TAG, "start sound=${mode.sound}/$sound vibrate=${mode.vibrate}")
        // CPU wach halten (Zeitlimit, Zustandsabfrage, Stopp) - hoechstens so lange wie das laengste Klingeln
        try {
            wake = app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "saga:ring")?.apply {
                setReferenceCounted(false)
                acquire(RingLogic.MAX_RING_MS)
            }
        } catch (_: Exception) {
        }
        if (mode.sound && sound == RingLogic.Sound.NOW) startSound(app)
        pendingSound = mode.sound && sound == RingLogic.Sound.AFTER_SERVICE
        if (mode.vibrate) startVibration(app)
    }

    /** Klingel-Dienst laeuft im Vordergrund: jetzt den Ton starten (nicht stummgeschaltet) */
    fun serviceUp(context: Context) {
        if (ringingId == null || !pendingSound) return
        pendingSound = false
        val id = ringingId
        // kurz warten, bis der Prozesszustand (Vordergrund-Dienst) beim Audio-System angekommen ist
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (ringingId == id && ringtone == null && player == null) {
                Log.i(TAG, "sound after service")
                startSound(context.applicationContext)
            }
        }, 400)
    }

    /** Klingeln ganz beenden: Ton, Vibration, Wachhalten und Klingel-Dienst */
    fun stop(context: Context) {
        if (ringingId != null) Log.i(TAG, "stop")
        stopMedia()
        CallRingService.stop(context.applicationContext)
    }

    private fun stopMedia() {
        ringingId = null
        pendingSound = false
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (_: Exception) {
        }
        wake = null
    }

    private fun currentMode(c: Context): RingLogic.Mode {
        val am = c.getSystemService(AudioManager::class.java)
        val nm = c.getSystemService(NotificationManager::class.java)
        val ringer = am?.ringerMode ?: RingLogic.RINGER_NORMAL
        val filter = nm?.currentInterruptionFilter ?: RingLogic.FILTER_UNKNOWN
        // "Nur wichtige": Anrufe von allen erlaubt? (Saga-Anrufer stehen nicht im Adressbuch)
        val anyone = try {
            val p = nm?.notificationPolicy
            p != null && (p.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS) != 0 &&
                p.priorityCallSenders == NotificationManager.Policy.PRIORITY_SENDERS_ANY
        } catch (_: Exception) {
            false
        }
        val m = RingLogic.mode(ringer, filter, anyone)
        // Ruftonlautstaerke 0 = kein Ton (Vibration bleibt nach Geraet)
        val vol = try { am?.getStreamVolume(AudioManager.STREAM_RING) ?: 1 } catch (_: Exception) { 1 }
        return if (vol <= 0) m.copy(sound = false) else m
    }

    private val audioAttrs: AudioAttributes
        get() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    private fun startSound(c: Context) {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val r = RingtoneManager.getRingtone(c, uri) ?: RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                if (r != null) {
                    r.audioAttributes = audioAttrs
                    r.isLooping = true
                    r.play()
                    ringtone = r
                    return
                }
            }
            val p = MediaPlayer()
            p.setAudioAttributes(audioAttrs)
            p.setDataSource(c, uri)
            p.isLooping = true
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) {
            Log.w(TAG, "sound: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration(c: Context) {
        val v = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else c.getSystemService(Vibrator::class.java)
        if (v == null || !v.hasVibrator()) return
        try {
            val effect = VibrationEffect.createWaveform(RingLogic.PATTERN, 0)          // wiederholt ab Index 0
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            else v.vibrate(effect, audioAttrs)
            vibrator = v
        } catch (e: Exception) {
            Log.w(TAG, "vibrate: ${e.message}")
        }
    }
}

/**
 * Vordergrund-Dienst NUR fuer das Klingeln (Typ phoneCall, Benachrichtigung = die eingehende CallStyle-Mitteilung):
 * haelt den Prozess am Leben, bis angenommen, abgelehnt oder das Klingeln vorbei ist - auch auf dem UnifiedPush-Weg,
 * wo sonst nach dem Empfaenger nichts mehr laeuft (eingefrorener Prozess: kein Zeitlimit, kein Stopp).
 * phoneCall verlangt FOREGROUND_SERVICE_PHONE_CALL und MANAGE_OWN_CALLS (beide schon da), keine Laufzeit-Berechtigung.
 * Aus dem Hintergrund darf Android 12+ ihn nur starten, wenn die App schon einen Vordergrund-Dienst hat (eigene
 * Verbindung), sichtbar ist (Vollbild-Anruf geoeffnet) oder von der Akku-Optimierung ausgenommen ist - sonst haelt
 * der Push-Empfaenger den Prozess (CallManager.holdWhileRinging, hoechstens 55 s).
 */
class CallRingService : Service() {
    companion object {
        @Volatile var notification: Notification? = null
        /** startForeground erfolgt */
        @Volatile var running = false
            private set
        @Volatile private var stopWanted = false
        @Volatile private var starting = false

        /** Starten (Ergebnis false: im Hintergrund nicht erlaubt) */
        fun start(context: Context, n: Notification): Boolean {
            notification = n
            stopWanted = false
            if (running || starting) return true
            return try {
                starting = true
                ContextCompat.startForegroundService(context, Intent(context, CallRingService::class.java))
                true
            } catch (e: Exception) {                              // ForegroundServiceStartNotAllowedException u. a.
                starting = false
                Log.i("SagaRing", "ring service not allowed: ${e.javaClass.simpleName}")
                false
            }
        }

        /** Anrufbildschirm sichtbar: jetzt darf der Dienst starten, falls es aus dem Hintergrund nicht ging */
        fun ensure(context: Context) {
            val n = notification ?: return
            if (CallRinger.ringingId != null && !running && !starting) start(context, n)
        }

        fun stop(context: Context) {
            notification = null
            if (starting && !running) {                            // nicht vor startForeground beenden (Android 14+)
                stopWanted = true
                return
            }
            if (!running) return
            running = false
            context.stopService(Intent(context, CallRingService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starting = false
        val n = notification ?: CallNotify.placeholder(this)
        val ok = try {
            ServiceCompat.startForeground(this, CallNotify.ID_RING, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0)
            true
        } catch (e: Exception) {
            Log.w("SagaRing", "startForeground: ${e.message}")
            false
        }
        running = ok
        if (ok) CallRinger.serviceUp(this)
        // startForeground ging nicht: Mitteilung wenigstens normal einstellen (sonst gaebe es keine)
        if (!ok) notification?.let { CallNotify.post(this, it) }
        if (!ok || stopWanted || notification == null || CallRinger.ringingId == null) {
            stopWanted = false
            running = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App aus der Liste gewischt, waehrend es klingelt: still aufhoeren (nicht ablehnen)
        if (CallRinger.ringingId != null) (applicationContext as? io.github.yggdrasil_sh.saga.SagaApplication)?.repo?.calls?.ringEndedBySystem()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        // Dienst endet (System, App-Ende): nicht weiterklingeln
        if (CallRinger.ringingId != null) {
            (applicationContext as? io.github.yggdrasil_sh.saga.SagaApplication)?.repo?.calls?.ringEndedBySystem()
        }
        super.onDestroy()
    }
}
