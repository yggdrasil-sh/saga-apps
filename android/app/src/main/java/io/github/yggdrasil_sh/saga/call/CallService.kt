package io.github.yggdrasil_sh.saga.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.SagaApplication

/**
 * Mitteilungen fuer Anrufe: Klingeln als Vollbild-Benachrichtigung (CallStyle.forIncomingCall, Kanal mit hoher
 * Wichtigkeit und Klingelton, wiederholt) - ohne Namen nur, solange er nicht geladen ist; laufender Anruf als
 * Vordergrund-Dienst (Typ phoneCall + Mikrofon/Kamera) mit "Auflegen".
 */
object CallNotify {
    const val CH_RING = "saga_call_ring"
    const val CH_CALL = "saga_call"
    const val ID_RING = 4101
    const val ID_CALL = 4102
    const val ACTION_DECLINE = "io.github.yggdrasil_sh.saga.call.DECLINE"
    const val ACTION_HANGUP = "io.github.yggdrasil_sh.saga.call.HANGUP"
    /** 2.4: Stumm an/aus aus dem Bild-im-Bild-Fenster (RemoteAction) */
    const val ACTION_MUTE = "io.github.yggdrasil_sh.saga.call.MUTE"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH_RING) == null) {
            val ch = NotificationChannel(CH_RING, context.getString(R.string.call_channel_ring), NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CH_CALL) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_CALL, context.getString(R.string.call_channel_ongoing), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun person(context: Context, peer: CallPeer): Person =
        Person.Builder().setName(peer.name.ifEmpty { peer.username }.ifEmpty { context.getString(R.string.app_name) }).setImportant(true).build()

    private fun activityIntent(context: Context, action: String?, code: Int): PendingIntent {
        val i = Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) i.action = action
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun broadcast(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(context, code, Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Darf die App im Vollbild klingeln (Android 14: Berechtigung USE_FULL_SCREEN_INTENT)? */
    fun canFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false

    fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showIncoming(context: Context, info: CallInfo) {
        if (!canNotify(context)) return
        channels(context)
        val video = info.media == "video"
        val name = info.peer.name.ifEmpty { info.peer.username }
        val full = activityIntent(context, null, 1)
        val n = NotificationCompat.Builder(context, CH_RING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (video) R.string.call_incoming_video else R.string.call_incoming))
            .setContentText(context.getString(if (video) R.string.call_video_from else R.string.call_from, name))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .setTimeoutAfter(((info.expiresIn ?: (info.ringS ?: 45).toDouble()) * 1000).toLong().coerceIn(5_000, 120_000))
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person(context, info.peer), broadcast(context, ACTION_DECLINE, 2), activityIntent(context, CallActivity.ACTION_ANSWER, 3),
                ).setIsVideo(video),
            )
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        try {
            NotificationManagerCompat.from(context).notify(ID_RING, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancelIncoming(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_RING)
    }

    fun ongoing(context: Context, peer: CallPeer, video: Boolean, since: Long = 0L, screen: Boolean = false): Notification {
        channels(context)
        // CallStyle zeigt den Namen der Person als Titel - der Text nennt darum die Art ("Laufender Anruf") statt
        // den Namen ein zweites Mal; dazu die Dauer als Stoppuhr. Beim Teilen: "Du teilst deinen Bildschirm".
        return NotificationCompat.Builder(context, CH_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(peer.name.ifEmpty { peer.username })
            .setContentText(context.getString(if (screen) R.string.call_screen_you else if (video) R.string.call_ongoing_video else R.string.call_ongoing))
            .setWhen(if (since > 0) since else System.currentTimeMillis())
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(activityIntent(context, null, 4))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(context, peer), broadcast(context, ACTION_HANGUP, 5)).setIsVideo(video))
            .build()
    }
}

/** Ablehnen / Auflegen aus der Mitteilung (nicht exportiert) */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = (context.applicationContext as? SagaApplication)?.repo?.calls ?: return
        when (intent.action) {
            CallNotify.ACTION_DECLINE -> calls.decline()
            CallNotify.ACTION_HANGUP -> calls.hangup()
            CallNotify.ACTION_MUTE -> calls.toggleMic()
        }
    }
}

/** Typen des Vordergrund-Dienstes (reine Regel, Unit-Tests): Reihenfolge der Versuche fuer startForeground */
object CallServiceTypes {
    /**
     * Typ "microphone" ist entscheidend: ohne ihn liefert Android (11+) im Hintergrund/bei gesperrtem Bildschirm nur
     * Stille vom Mikrofon - das Gegenueber hoert dann nichts. Darum bei Fehlschlag zuerst OHNE phoneCall versuchen
     * (phoneCall verlangt MANAGE_OWN_CALLS bzw. Telefon-App-Rolle), erst zuletzt phoneCall allein. Mit Video zusaetzlich
     * "camera" - sonst stoppt die Kamera im Hintergrund bzw. im Bild-im-Bild.
     * Die Typ-Konstanten (API 29/30) werden erst ab sdk >= 30 verwendet (Pruefung ueber den Parameter, fuer Tests).
     */
    @android.annotation.SuppressLint("InlinedApi")
    fun tries(sdk: Int, video: Boolean, mic: Boolean, cam: Boolean, screen: Boolean = false): List<Int> {
        if (screen) return screenTries(sdk, video, mic, cam)
        if (sdk < 30) return listOf(0)
        val out = ArrayList<Int>()
        var media = 0
        if (mic) media = media or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (video && cam) media = media or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        out += ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or media
        if (media != 0) {
            out += media
            if (media != ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE && mic) out += ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        out += ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        return out
    }

    /**
     * 2.4 E3 Bildschirm teilen: MediaProjection verlangt (Android 10+) einen Vordergrund-Dienst mit Typ mediaProjection;
     * Android 14+ erlaubt ihn erst NACH der Einwilligung - daher wird der Typ erst dann ergaenzt (startForeground auf der
     * laufenden Instanz), und erst danach die Aufnahme gestartet. Jeder Versuch enthaelt mediaProjection, sonst ginge
     * das Teilen ohnehin nicht.
     */
    @android.annotation.SuppressLint("InlinedApi")
    private fun screenTries(sdk: Int, video: Boolean, mic: Boolean, cam: Boolean): List<Int> {
        if (sdk < 29) return listOf(0)
        val mp = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (sdk < 30) return listOf(mp)
        val base = tries(sdk, video, mic, cam).map { it or mp }
        return (base + mp).distinct()
    }

    /** Laeuft das Teilen mit diesem Typ? (unter Android 10 gibt es den Typ nicht - dort keine Pflicht) */
    @android.annotation.SuppressLint("InlinedApi")
    fun screenOk(sdk: Int, type: Int?): Boolean =
        type != null && (sdk < 29 || (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0)
}

/**
 * Laufender Anruf als Vordergrund-Dienst (Typ phoneCall + Mikrofon, bei Video auch Kamera): Ton und Bild laufen weiter,
 * wenn die App in den Hintergrund oder ins Bild-im-Bild geht. Gestartet aus der sichtbaren Anruf-Oberflaeche (erlaubt ab
 * Android 14).
 */
class CallService : Service() {
    companion object {
        private const val EXTRA_VIDEO = "video"

        /** Dienst laeuft im Vordergrund (startForeground erfolgt) */
        @Volatile private var foreground = false
        /** Anruf endete, bevor der Dienst startForeground aufrufen konnte: danach sofort beenden */
        @Volatile private var stopWanted = false
        /** Laufende Instanz (nur Haupt-Thread), fuer den Typwechsel ohne neuen Start */
        private var instance: CallService? = null

        fun start(context: Context, video: Boolean) {
            stopWanted = false
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).putExtra(EXTRA_VIDEO, video))
            } catch (_: Exception) {                                // im Hintergrund nicht erlaubt: Anruf laeuft trotzdem
            }
        }

        /**
         * 2.4: Kamera im Anruf an/aus. Laeuft der Dienst schon, wechselt der Typ SOFORT (startForeground auf der laufenden
         * Instanz, vor dem Start der Kamera) - ein neuer startForegroundService kaeme erst spaeter an, bis dahin lief die
         * Kamera ohne Typ camera und konnte im Hintergrund stoppen. Sonst normal starten.
         */
        fun setVideo(context: Context, video: Boolean) {
            val s = instance
            if (s != null && foreground && s.promote(video) != null) return
            start(context, video)
        }

        /** Bildschirm wird geteilt (Typ mediaProjection gehoert dazu) */
        @Volatile private var screen = false
        @Volatile private var lastVideo = false

        /**
         * 2.4 E3: Bildschirm teilen an/aus. An: NACH der Einwilligung und VOR getMediaProjection aufrufen (Android 14+);
         * Ergebnis true = Dienst laeuft mit Typ mediaProjection, die Aufnahme darf starten. Aus: Typ wieder entfernen.
         */
        fun setScreen(on: Boolean): Boolean {
            val s = instance
            screen = on
            if (s == null || !foreground) {
                screen = false
                return !on
            }
            val type = s.promote(lastVideo)
            if (!on) return true
            val ok = CallServiceTypes.screenOk(Build.VERSION.SDK_INT, type)
            if (!ok) {
                screen = false
                s.promote(lastVideo)
            }
            return ok
        }

        /**
         * Beenden. Noch nicht im Vordergrund (Anruf scheiterte gleich nach dem Start)? Dann NICHT stopService - Android 14+
         * beendet die App sonst mit ForegroundServiceDidNotStartInTimeException; der Dienst beendet sich nach
         * startForeground selbst.
         */
        fun stop(context: Context) {
            if (!foreground) {
                stopWanted = true
                return
            }
            foreground = false
            screen = false
            context.stopService(Intent(context, CallService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** startForeground mit passenden Typen; Ergebnis = verwendeter Typ, null = gescheitert */
    private fun promote(video: Boolean): Int? {
        lastVideo = video
        val st = (applicationContext as? SagaApplication)?.repo?.calls?.ui?.value
        val n = CallNotify.ongoing(this, st?.peer ?: CallPeer(), video, st?.connectedAt ?: 0L, screen)
        val tries = CallServiceTypes.tries(Build.VERSION.SDK_INT, video, granted(Manifest.permission.RECORD_AUDIO), granted(Manifest.permission.CAMERA), screen)
        return tries.firstOrNull { type ->
            try {
                ServiceCompat.startForeground(this, CallNotify.ID_CALL, n, type)
                true
            } catch (e: Exception) {
                android.util.Log.w("SagaCall", "startForeground($type): ${e.message}")
                false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val st = (applicationContext as? SagaApplication)?.repo?.calls?.ui?.value
        val ok = promote(intent?.getBooleanExtra(EXTRA_VIDEO, false) == true) != null
        foreground = ok
        if (!ok || stopWanted || st == null || st.phase == CallPhase.ENDED) {
            foreground = false
            stopWanted = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App aus der Liste gewischt: auflegen statt eines "Geister-Anrufs". Nicht beim Schliessen des Anruf-Fensters
        // (eigene Aufgabe, z. B. Bild-im-Bild weggewischt) - der Anruf laeuft dann mit der Leiste "Anruf laeuft" weiter.
        if (rootIntent?.component?.className != CallActivity::class.java.name) {
            (applicationContext as? SagaApplication)?.repo?.calls?.hangup()
        }
        super.onTaskRemoved(rootIntent)
    }
}
