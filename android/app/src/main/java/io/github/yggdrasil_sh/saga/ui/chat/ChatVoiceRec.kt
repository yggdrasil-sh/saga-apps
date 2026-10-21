package io.github.yggdrasil_sh.saga.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.X3Limits
import io.github.yggdrasil_sh.saga.chat.voice.MicSource
import io.github.yggdrasil_sh.saga.chat.voice.VoiceLogic
import io.github.yggdrasil_sh.saga.chat.voice.VoiceRecording
import io.github.yggdrasil_sh.saga.chat.voice.VoiceResult
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Zustand der Aufnahme einer Sprachnachricht (Konzept B3, mobil): halten nimmt auf, nach oben schieben sperrt, nach
 * links schieben verwirft; danach Vorschau mit Abspielen, Verwerfen, Senden. Hinweis bei 14:00, Stopp bei 15:00, unter
 * 1 s verworfen. Das Mikrofon laeuft nur zwischen Druecken und Loslassen bzw. bis "Stopp".
 */
@Stable
class VoiceRecUi(private val scope: CoroutineScope, private val notify: (UiText) -> Unit) {
    enum class Phase { IDLE, HOLD, LOCKED, PREVIEW }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var secs by mutableDoubleStateOf(0.0)
        private set
    var levels by mutableStateOf<List<Float>>(emptyList())
        private set
    var warned by mutableStateOf(false)
        private set
    var result by mutableStateOf<VoiceResult?>(null)
        private set
    private var rec: VoiceRecording? = null
    private var stopping = false
    /** Start im Hintergrund - Stopp/Verwerfen warten darauf (sonst bliebe das Mikrofon offen) */
    private var startJob: kotlinx.coroutines.Job? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    val busy: Boolean get() = phase != Phase.IDLE

    fun start(limits: X3Limits) {
        if (phase != Phase.IDLE || Build.VERSION.SDK_INT < VoiceLogic.RECORD_MIN_SDK) return
        secs = 0.0
        levels = emptyList()
        warned = false
        result = null
        stopping = false
        phase = Phase.HOLD
        io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer.pause()
        val r = VoiceRecording(
            MicSource(), limits.voiceKbps, limits.voiceMaxS,
            onTick = { t, lv ->
                main.post {
                    secs = t
                    levels = (levels + lv).takeLast(200)
                    when (VoiceLogic.tick(t, limits.voiceMaxS, warned)) {
                        VoiceLogic.Tick.WARN -> {
                            warned = true
                            notify(UiText.Res(R.string.voice_warn_minute))
                        }
                        else -> {}
                    }
                }
            },
            onLimit = {
                main.post {
                    notify(UiText.Res(R.string.voice_limit_stop))
                    stop()
                }
            },
        )
        rec = r
        startJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { r.start() }
            } catch (e: Exception) {
                if (rec === r) rec = null
                phase = Phase.IDLE
                notify(UiText.Res(R.string.voice_record_failed, e.message ?: e.javaClass.simpleName))
                throw kotlinx.coroutines.CancellationException("start failed")
            }
        }
    }

    fun lock() {
        if (phase == Phase.HOLD) phase = Phase.LOCKED
    }

    /** Loslassen bzw. "Stopp": Ergebnis in die Vorschau (zu kurz: verwerfen) */
    fun stop() {
        val r = rec ?: return
        if (stopping) return
        stopping = true
        rec = null
        val started = startJob
        scope.launch {
            started?.join()
            if (started?.isCancelled == true) return@launch
            val res = try {
                withContext(Dispatchers.IO) { r.stop() }
            } catch (e: Exception) {
                notify(UiText.Res(R.string.voice_record_failed, e.message ?: e.javaClass.simpleName))
                null
            }
            if (res == null || !VoiceLogic.keep(res.dur)) {
                if (res != null) notify(UiText.Res(R.string.voice_too_short))
                phase = Phase.IDLE
                return@launch
            }
            result = res
            phase = Phase.PREVIEW
        }
    }

    /** Verwerfen (waehrend der Aufnahme oder in der Vorschau) - nichts verlaesst das Geraet */
    fun cancel() {
        val started = startJob
        rec?.let { r ->
            scope.launch {
                started?.join()
                if (started?.isCancelled != true) withContext(Dispatchers.IO) { r.cancel() }
            }
        }
        rec = null
        result = null
        io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer.stopIf(PREVIEW_ID)
        phase = Phase.IDLE
    }

    /** Vorschau gesendet bzw. uebernommen */
    fun take(): VoiceResult? {
        val r = result
        result = null
        io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer.stopIf(PREVIEW_ID)
        phase = Phase.IDLE
        return r
    }

    companion object {
        const val PREVIEW_ID = "voice-preview"
    }
}

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Mikrofon-Knopf neben dem Eingabefeld: gedrueckt halten nimmt auf (Geste: nach oben sperren, nach links verwerfen).
 * Android 8/9: nur Hinweis (dort gibt es keinen Opus-Encoder; Abspielen geht). Ohne Mikrofon-Recht: Abfrage zur
 * Laufzeit wie in CallActivity; nach "nie wieder" ein Hinweis mit Link zu den Einstellungen.
 */
@Composable
fun VoiceMicButton(rec: VoiceRecUi, limits: X3Limits, notify: (UiText) -> Unit, onDenied: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val prefs = remember { context.getSharedPreferences("saga_voice", Context.MODE_PRIVATE) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        prefs.edit().putBoolean("mic_asked", true).apply()
        if (ok) notify(UiText.Res(R.string.voice_hold_hint))
        else if (context.activity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) onDenied()
        else notify(UiText.Res(R.string.voice_mic_not_allowed))
    }
    val lim by rememberUpdatedState(limits)
    val supported = Build.VERSION.SDK_INT >= VoiceLogic.RECORD_MIN_SDK
    val label = stringResource(R.string.voice_record)
    val sc = MaterialTheme.colorScheme
    val active = rec.phase == VoiceRecUi.Phase.HOLD
    Box(
        // TT 52: Rand-Gesten (Zurueck) am Knopf ausschliessen; unten haelt SafeBottom ihn aus dem Bereich der Home-Geste
        Modifier.size(48.dp).systemGestureExclusion().clip(CircleShape).background(if (active) sc.errorContainer else Color.Transparent)
            .semantics {
                contentDescription = label
                onClick { notify(UiText.Res(if (supported) R.string.voice_hold_hint else R.string.voice_android_old)); true }
            }
            .pointerInput(supported) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!supported) {
                        notify(UiText.Res(R.string.voice_android_old))
                        return@awaitEachGesture
                    }
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        val act = context.activity()
                        val asked = prefs.getBoolean("mic_asked", false)
                        if (asked && act != null && !act.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) onDenied()
                        else perm.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }
                    if (rec.phase != VoiceRecUi.Phase.IDLE) return@awaitEachGesture
                    down.consume()
                    rec.start(lim)
                    val t0 = System.currentTimeMillis()
                    var dx = 0f
                    var dy = 0f
                    var gesture = VoiceLogic.Gesture.HOLD
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        val d = ch.positionChange()
                        dx += d.x
                        dy += d.y
                        ch.consume()
                        gesture = with(density) { VoiceLogic.gesture(dx.toDp().value.toDouble(), dy.toDp().value.toDouble()) }
                        if (gesture == VoiceLogic.Gesture.LOCK) {
                            rec.lock()
                            break
                        }
                        if (gesture == VoiceLogic.Gesture.CANCEL) {
                            rec.cancel()
                            break
                        }
                        if (!ch.pressed) break
                    }
                    if (gesture == VoiceLogic.Gesture.HOLD && rec.phase == VoiceRecUi.Phase.HOLD) {
                        // kurzes Antippen: nur der Hinweis (verworfen), sonst Vorschau
                        if (System.currentTimeMillis() - t0 < 350) {
                            rec.cancel()
                            notify(UiText.Res(R.string.voice_hold_hint))
                        } else rec.stop()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_mic), null,
            tint = when {
                active -> sc.onErrorContainer
                supported -> sc.primary
                else -> sc.outline
            },
        )
    }
}

/** Vorschau-Leiste einer fertigen Aufnahme (Abspielen ueber den gemeinsamen Player, Verwerfen, Senden) */
@Composable
fun VoicePreview(rec: VoiceRecUi, onSend: (VoiceResult) -> Unit) {
    val r = rec.result ?: return
    val context = LocalContext.current
    val bars = remember(r) { IntArray(VoiceLogic.WAVE_N) { r.wf[it].toInt() and 0xff } }
    val id = VoiceRecUi.PREVIEW_ID
    val active = io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer.current == id
    val playing = active && io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer.playing
    var pos by remember { mutableDoubleStateOf(0.0) }
    androidx.compose.runtime.LaunchedEffect(active, playing) {
        while (active) {
            pos = io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer.position(id)
            if (!playing) break
            kotlinx.coroutines.delay(100)
        }
        if (!active) pos = 0.0
    }
    VoicePreviewBar(
        bars, r.dur, pos, playing, io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer.speed,
        onPlay = {
            val vp = io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer
            if (playing) vp.pause()
            else vp.start(context, io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer.Item(id, io.github.yggdrasil_sh.saga.chat.ChatFile(id = id, m = io.github.yggdrasil_sh.saga.chat.voice.OggOpus.MIME, voice = true), bytes = { r.ogg }))
        },
        onDiscard = { rec.cancel() },
        onSend = { rec.take()?.let(onSend) },
        onSpeed = { io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer.cycleSpeed(context) },
    )
}
