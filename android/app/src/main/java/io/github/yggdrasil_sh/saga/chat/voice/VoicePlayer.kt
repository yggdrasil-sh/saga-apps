package io.github.yggdrasil_sh.saga.chat.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFile
import kotlinx.coroutines.launch

/**
 * Wiedergabe von Sprachnachrichten (Saga 2.5, Konzept B3): ein ExoPlayer fuer die ganze App (Ogg-Extractor, Opus-Decoder
 * des Systems), Quelle wie bei Videos ueber SagaFileDataSource (saga-file/v2, Range, Entschluesseln je Block) bzw. den
 * entschluesselten v1-Anhang/die Vorschau im Speicher. Audio-Fokus (Anruf/andere App -> Pause), "laut -> leise" beim
 * Abziehen des Kopfhoerers, Tempo 1×/1,5×/2× (gemerkt), danach die naechste Sprachnachricht. Laeuft im Hintergrund weiter
 * (Vordergrund-Dienst Typ mediaPlayback, nur solange abgespielt wird).
 */
@OptIn(UnstableApi::class)
object VoicePlayer {
    /** Was gerade spielt bzw. pausiert ist (Datei-Kennung) */
    var current by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var failed by mutableStateOf<String?>(null)
        private set
    /** wird geladen (vor dem ersten Ton) */
    var loading by mutableStateOf(false)
        private set
    var speed by mutableFloatStateOf(1f)
        private set

    private var app: Context? = null
    private var player: ExoPlayer? = null
    private var next: ((String) -> Item?)? = null

    /** Quelle einer Sprachnachricht: v2 als DataSource-Fabrik, v1/Vorschau als Bytes (lazy geladen) */
    class Item(val id: String, val file: ChatFile, val factory: DataSource.Factory? = null, val bytes: (suspend () -> ByteArray)? = null)

    private fun prefs(c: Context) = c.getSharedPreferences("saga_voice", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        speed = prefs(context).getFloat("speed", 1f).takeIf { it in VoiceLogic.SPEEDS } ?: 1f
    }

    private fun player(c: Context): ExoPlayer = player ?: ExoPlayer.Builder(c)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                    VoicePlaybackService.update(c, isPlaying || p.playWhenReady)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    loading = state == Player.STATE_BUFFERING
                    if (state != Player.STATE_ENDED) return
                    val id = current ?: return
                    val nx = next?.invoke(id)
                    p.seekTo(0)
                    p.pause()
                    if (nx != null) start(c, nx, next) else VoicePlaybackService.update(c, false)
                }

                override fun onPlayerError(error: PlaybackException) {
                    loading = false
                    failed = current
                    VoicePlaybackService.update(c, false)
                }
            })
            player = p
        }

    private var pending: kotlinx.coroutines.Job? = null

    /**
     * Abspielen (bzw. an derselben Stelle weiter); nextOf: naechste Sprachnachricht nach dieser (oder null). Laeuft gerade
     * eine andere, haelt sie an.
     */
    fun start(context: Context, item: Item, nextOf: ((String) -> Item?)? = null) {
        init(context)
        val c = app ?: return
        val p = player(c)
        next = nextOf
        failed = null
        if (current == item.id && p.mediaItemCount > 0) {
            p.playbackParameters = PlaybackParameters(speed)
            p.play()
            VoicePlaybackService.update(c, true)
            return
        }
        pending?.cancel()
        p.stop()
        p.clearMediaItems()
        current = item.id
        loading = true
        val mi = MediaItem.Builder().setUri(Uri.parse("saga-voice://" + Uri.encode(item.id))).setMimeType(MimeTypes.AUDIO_OGG).build()
        val f = item.factory
        if (f != null) {
            p.setMediaSource(ProgressiveMediaSource.Factory(f).createMediaSource(mi))
            p.playbackParameters = PlaybackParameters(speed)
            p.prepare()
            p.play()
            VoicePlaybackService.update(c, true)
            return
        }
        val load = item.bytes ?: return
        VoicePlaybackService.update(c, true)
        pending = scope.launch {
            try {
                val bytes = load()
                if (current != item.id) return@launch
                p.setMediaSource(ProgressiveMediaSource.Factory { ByteArrayDataSource(bytes) }.createMediaSource(mi))
                p.playbackParameters = PlaybackParameters(speed)
                p.prepare()
                p.play()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = item.id
                loading = false
                VoicePlaybackService.update(c, false)
            }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    fun pause() {
        player?.pause()
    }

    /** Position (s) der aktuellen Sprachnachricht bzw. 0 */
    fun position(id: String): Double = if (id == current) (player?.currentPosition ?: 0L) / 1000.0 else 0.0

    /** Springen (nur in der aktuellen; sonst erst starten) */
    fun seek(id: String, seconds: Double) {
        if (id != current) return
        player?.seekTo((seconds * 1000).toLong())
    }

    fun cycleSpeed(context: Context) {
        init(context)
        speed = VoiceLogic.nextSpeed(speed)
        prefs(context).edit().putFloat("speed", speed).apply()
        player?.playbackParameters = PlaybackParameters(speed)
    }

    /** Anruf beginnt (Saga-Anruf; Telefonate pausieren ueber den Audio-Fokus) */
    fun onCall() = pause()

    /** Datei weg (Chat geloescht, abgemeldet): anhalten und freigeben */
    fun stopIf(id: String?) {
        if (id != null && id != current) return
        pending?.cancel()
        player?.stop()
        player?.clearMediaItems()
        current = null
        playing = false
        app?.let { VoicePlaybackService.update(it, false) }
    }
}

/** Vordergrund-Dienst (Typ mediaPlayback) nur waehrend der Wiedergabe - damit sie im Hintergrund weiterlaeuft */
class VoicePlaybackService : Service() {
    companion object {
        private const val CHANNEL = "saga_voice"
        private const val ID = 4711
        private var on = false

        fun update(c: Context, play: Boolean) {
            if (play == on) return
            on = play
            val i = Intent(c, VoicePlaybackService::class.java)
            try {
                if (play) androidx.core.content.ContextCompat.startForegroundService(c, i) else c.stopService(i)
            } catch (_: Exception) {
                on = false                                    // z. B. aus dem Hintergrund nicht erlaubt: ohne Dienst weiter
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.voice_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(io.github.yggdrasil_sh.saga.push.NotifyDesign.ACCENT)
            .setContentTitle(getString(R.string.voice_playing))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        try {
            ServiceCompat.startForeground(this, ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        on = false
        super.onDestroy()
    }
}
