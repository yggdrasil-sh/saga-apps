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

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
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

    fun ongoing(context: Context, peer: CallPeer, video: Boolean, since: Long = 0L): Notification {
        channels(context)
        // CallStyle zeigt den Namen der Person als Titel - der Text nennt darum die Art ("Laufender Anruf") statt
        // den Namen ein zweites Mal; dazu die Dauer als Stoppuhr.
        return NotificationCompat.Builder(context, CH_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(peer.name.ifEmpty { peer.username })
            .setContentText(context.getString(if (video) R.string.call_ongoing_video else R.string.call_ongoing))
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
        }
    }
}

/**
 * Laufender Anruf als Vordergrund-Dienst (Typ phoneCall + Mikrofon, bei Video auch Kamera): Ton und Bild laufen weiter,
 * wenn die App in den Hintergrund geht. Gestartet aus der sichtbaren Anruf-Oberflaeche (erlaubt ab Android 14).
 */
class CallService : Service() {
    companion object {
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, video: Boolean) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).putExtra(EXTRA_VIDEO, video))
            } catch (_: Exception) {                                // im Hintergrund nicht erlaubt: Anruf laeuft trotzdem
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val calls = (applicationContext as? SagaApplication)?.repo?.calls
        val st = calls?.ui?.value
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val n = CallNotify.ongoing(this, st?.peer ?: CallPeer(), video, st?.connectedAt ?: 0L)
        // Typ "microphone" ist entscheidend: ohne ihn liefert Android (11+) im Hintergrund/bei gesperrtem Bildschirm nur
        // Stille vom Mikrofon - das Gegenueber hoert dann nichts. Darum bei Fehlschlag zuerst OHNE phoneCall versuchen
        // (phoneCall verlangt MANAGE_OWN_CALLS bzw. Telefon-App-Rolle), erst zuletzt phoneCall allein.
        val tries = ArrayList<Int>()
        if (Build.VERSION.SDK_INT >= 30) {
            var media = 0
            if (granted(Manifest.permission.RECORD_AUDIO)) media = media or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video && granted(Manifest.permission.CAMERA)) media = media or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            tries += ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or media
            if (media != 0) {
                tries += media
                if (media != ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE && granted(Manifest.permission.RECORD_AUDIO)) {
                    tries += ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            tries += ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else tries += 0
        val ok = tries.any { type ->
            try {
                ServiceCompat.startForeground(this, CallNotify.ID_CALL, n, type)
                true
            } catch (e: Exception) {
                android.util.Log.w("SagaCall", "startForeground($type): ${e.message}")
                false
            }
        }
        if (!ok) stopSelf()
        if (st == null || st.phase == CallPhase.ENDED) stopSelf()
        return START_NOT_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App aus der Liste gewischt: auflegen statt eines "Geister-Anrufs"
        (applicationContext as? SagaApplication)?.repo?.calls?.hangup()
        super.onTaskRemoved(rootIntent)
    }
}
