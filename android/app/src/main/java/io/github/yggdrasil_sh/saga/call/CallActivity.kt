package io.github.yggdrasil_sh.saga.call

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.SagaApplication
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Anruf-Oberflaeche (Vollbild, auch auf dem Sperrbildschirm): Klingeln mit Annehmen/Ablehnen, laufender Anruf mit
 * Video, Stumm, Kamera, Lautsprecher, Auflegen, Hinweis "Ende-zu-Ende verschluesselt" (nur nach Fingerabdruck-Pruefung),
 * Weg (direkt/Relais) und Qualitaet.
 */
class CallActivity : ComponentActivity() {
    companion object {
        const val ACTION_ANSWER = "io.github.yggdrasil_sh.saga.call.ANSWER"

        fun open(context: Context) {
            try {
                context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (_: Exception) {
            }
        }
    }

    private var answerRequest by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val calls = (application as SagaApplication).repo.calls
        if (intent?.action == ACTION_ANSWER) answerRequest++
        setContent {
            SagaTheme {
                val st by calls.ui.collectAsState()
                LaunchedEffect(st == null) { if (st == null) finish() }
                st?.let { CallScreen(calls, it, answerRequest) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_ANSWER) answerRequest++
    }
}

@Composable
private fun CallScreen(calls: CallManager, st: CallUiState, answerRequest: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var answerVideo by remember { mutableStateOf<Boolean?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val v = answerVideo ?: return@rememberLauncherForActivityResult
        answerVideo = null
        if (res[Manifest.permission.RECORD_AUDIO] == false &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            calls.decline()
            (context.applicationContext as SagaApplication).repo.notify(io.github.yggdrasil_sh.saga.util.UiText.Res(R.string.call_mic_denied))
            return@rememberLauncherForActivityResult
        }
        calls.accept(v && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    fun answer(video: Boolean) {
        val need = CallPermissions.missing(context, video)
        if (need.isEmpty()) calls.accept(video) else {
            answerVideo = video
            perms.launch(need.toTypedArray())
        }
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) calls.toggleCam() }
    val onCam = {
        if (!st.cam && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            camPerm.launch(Manifest.permission.CAMERA)
        } else calls.toggleCam()
    }
    LaunchedEffect(answerRequest) {
        if (answerRequest > 0 && st.phase == CallPhase.INCOMING) answer(st.video)
    }

    val dark = Color(0xFF101114)
    val showVideo = st.remoteVideo && st.phase == CallPhase.CONNECTED
    Box(Modifier.fillMaxSize().background(if (showVideo) Color.Black else MaterialTheme.colorScheme.surface)) {
        if (st.phase != CallPhase.INCOMING && st.phase != CallPhase.ENDED) {
            VideoView(calls, remote = true, visible = showVideo, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            InfoLine(st, onDark = showVideo)
            Spacer(Modifier.weight(1f))
            if (!showVideo) {
                Avatar(st.peer.name.ifEmpty { st.peer.username }, size = 112, avatar = st.peer.avatar)
                Text(
                    st.peer.name.ifEmpty { st.peer.username }, style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 16.dp).semantics { heading() }, textAlign = TextAlign.Center,
                )
            }
            val status = when {
                st.phase == CallPhase.INCOMING -> stringResource(if (st.video) R.string.call_incoming_video else R.string.call_incoming)
                st.message != null -> st.message.resolve()
                st.phase == CallPhase.CONNECTED && !st.remoteMic -> stringResource(R.string.call_peer_muted, st.peer.name)
                else -> null
            }
            if (status != null) {
                Surface(
                    color = if (showVideo) dark.copy(alpha = 0.6f) else Color.Transparent, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        status, textAlign = TextAlign.Center,
                        color = when {
                            st.error -> MaterialTheme.colorScheme.error
                            showVideo -> Color.White
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Controls(calls, st, ::answer, onCam)
        }
        if (st.cam && st.phase != CallPhase.ENDED && st.phase != CallPhase.INCOMING) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp, end = 16.dp), contentAlignment = Alignment.TopEnd) {
                VideoView(calls, remote = false, visible = true, modifier = Modifier.width(108.dp).height(156.dp).clip(RoundedCornerShape(12.dp)))
            }
        }
    }
}

@Composable
private fun InfoLine(st: CallUiState, onDark: Boolean) {
    if (st.phase != CallPhase.CONNECTED && st.phase != CallPhase.CONNECTING && st.phase != CallPhase.RINGING) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val fg = if (onDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val parts = buildList {
        if (st.connectedAt > 0) add(CallLogic.duration((now - st.connectedAt) / 1000))
        if (st.path == "relay") add(stringResource(R.string.call_relay)) else if (st.path == "direct") add(stringResource(R.string.call_direct))
        when (st.quality) {
            "good" -> add(stringResource(R.string.call_q_good))
            "mid" -> add(stringResource(R.string.call_q_mid))
            "bad" -> add(stringResource(R.string.call_q_bad))
        }
        // Ton-Diagnose (getStats): kommen Audio-Pakete des Gegenuebers an?
        if (st.phase == CallPhase.CONNECTED) when (st.audioIn) {
            "ok" -> add(stringResource(R.string.call_audio_ok))
            "none" -> add(stringResource(R.string.call_audio_none))
        }
    }
    Surface(color = if (onDark) Color(0x99101114) else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val e2e = stringResource(if (st.verified) R.string.call_e2e else R.string.call_encrypted)
            val info = stringResource(R.string.call_e2e_info)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {
                if (st.verified) contentDescription = "$e2e. $info"
            }) {
                Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = if (st.verified) MaterialTheme.colorScheme.primary.takeUnless { onDark } ?: Color(0xFF8BE28B) else fg)
                Text((if (st.verified) "$e2e ✓" else e2e), color = fg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = fg, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Controls(calls: CallManager, st: CallUiState, answer: (Boolean) -> Unit, onCam: () -> Unit) {
    when (st.phase) {
        CallPhase.INCOMING -> Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundButton(R.drawable.ic_call_end, stringResource(R.string.call_decline), MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError) { calls.decline() }
            if (st.video) {
                RoundButton(R.drawable.ic_call, stringResource(R.string.call_answer_audio), CALL_GREEN, Color.White) { answer(false) }
                RoundButton(R.drawable.ic_videocam, stringResource(R.string.call_answer_video), CALL_GREEN, Color.White, big = true) { answer(true) }
            } else {
                RoundButton(R.drawable.ic_call, stringResource(R.string.call_answer), CALL_GREEN, Color.White, big = true) { answer(false) }
            }
        }
        CallPhase.ENDED -> TextButton(onClick = { calls.dismiss() }, modifier = Modifier.padding(bottom = 24.dp)) { Text(stringResource(R.string.action_close)) }
        else -> Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val tc = MaterialTheme.colorScheme.secondaryContainer
            val tf = MaterialTheme.colorScheme.onSecondaryContainer
            RoundButton(
                if (st.mic) R.drawable.ic_mic else R.drawable.ic_mic_off, stringResource(if (st.mic) R.string.call_mute else R.string.call_unmute),
                if (st.mic) tc else MaterialTheme.colorScheme.inverseSurface, if (st.mic) tf else MaterialTheme.colorScheme.inverseOnSurface,
            ) { calls.toggleMic() }
            RoundButton(
                if (st.cam) R.drawable.ic_videocam else R.drawable.ic_videocam_off, stringResource(if (st.cam) R.string.call_cam_off else R.string.call_cam_on),
                if (st.cam) tc else MaterialTheme.colorScheme.inverseSurface, if (st.cam) tf else MaterialTheme.colorScheme.inverseOnSurface,
            ) { onCam() }
            if (st.cam) RoundButton(R.drawable.ic_cameraswitch, stringResource(R.string.call_switch_camera), tc, tf) { calls.switchCamera() }
            RoundButton(
                R.drawable.ic_volume_up, stringResource(R.string.call_speaker),
                if (st.speaker) MaterialTheme.colorScheme.primary else tc, if (st.speaker) MaterialTheme.colorScheme.onPrimary else tf,
                selected = st.speaker,
            ) { calls.toggleSpeaker() }
            RoundButton(R.drawable.ic_call_end, stringResource(R.string.call_hangup), MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError) { calls.hangup() }
        }
    }
}

private val CALL_GREEN = Color(0xFF1E8E3E)

@Composable
private fun RoundButton(icon: Int, label: String, bg: Color, fg: Color, big: Boolean = false, selected: Boolean? = null, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = bg, contentColor = fg),
        modifier = Modifier.size(if (big) 72.dp else 60.dp).semantics {
            contentDescription = label
            if (selected != null) this.selected = selected
        },
        shape = CircleShape,
    ) {
        Icon(painterResource(icon), null, Modifier.size(if (big) 34.dp else 28.dp))
    }
}

/** WebRTC-Bild (SurfaceViewRenderer) - haengt sich an die ProxySink des Managers und loest sich beim Verlassen */
@Composable
private fun VideoView(calls: CallManager, remote: Boolean, visible: Boolean, modifier: Modifier) {
    val sink = if (remote) calls.remoteSink else calls.localSink
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            view?.let { v ->
                if (sink.target === v) sink.target = null
                v.release()
            }
        }
    }
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(calls.egl.eglBaseContext, null)
                setScalingType(if (remote) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (!remote) {
                    setMirror(true)
                    setZOrderMediaOverlay(true)
                }
                sink.target = this
                view = this
            }
        },
        update = { it.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE },
        modifier = modifier,
    )
}

/** Berechtigungen fuer Anrufe */
object CallPermissions {
    fun missing(context: Context, video: Boolean): List<String> = buildList {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
        if (video && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
