package io.github.yggdrasil_sh.saga.call

import android.Manifest
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(io.github.yggdrasil_sh.saga.util.AppLanguage.wrap(newBase))

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

    /** 2.4: Bild-im-Bild aktiv (Oberflaeche nur noch Fernbild) */
    private var inPip by mutableStateOf(false)
    /** Seitenverhaeltnis des Fernbilds (aus dem vorhandenen Renderer, keine zweite Instanz) */
    private var remoteAspect by mutableStateOf<Float?>(null)
    private val calls: CallManager get() = (application as SagaApplication).repo.calls

    private fun pipSupported(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(st: CallUiState?): PictureInPictureParams {
        val (w, h) = CallPip.ratio(remoteAspect)
        val mic = st?.mic != false
        val muteLabel = getString(if (mic) R.string.call_mute else R.string.call_unmute)
        val hangLabel = getString(R.string.call_hangup)
        val actions = listOf(
            RemoteAction(
                Icon.createWithResource(this, if (mic) R.drawable.ic_mic else R.drawable.ic_mic_off), muteLabel, muteLabel,
                CallNotify.broadcast(this, CallNotify.ACTION_MUTE, 6),
            ),
            RemoteAction(Icon.createWithResource(this, R.drawable.ic_call_end), hangLabel, hangLabel, CallNotify.broadcast(this, CallNotify.ACTION_HANGUP, 7)),
        )
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(w, h)).setActions(actions)
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: beim Verlassen (Startbildschirm, App-Wechsel) automatisch ins PiP, solange Video aktiv ist.
            // Videoinhalt: kein nahtloses Skalieren (empfohlen fuer Video, vermeidet Flackern)
            b.setAutoEnterEnabled(CallPip.eligible(st))
            b.setSeamlessResizeEnabled(false)
        }
        return b.build()
    }

    /** PiP-Parameter aktualisieren (Seitenverhaeltnis, Stumm-Knopf, Auto-Enter) */
    private fun updatePip(st: CallUiState?) {
        if (!pipSupported()) return
        try {
            setPictureInPictureParams(pipParams(st))
        } catch (_: Exception) {
        }
    }

    /** Ins Bild-im-Bild wechseln, wenn Video aktiv ist; false = nicht moeglich (dann wie bisher minimieren) */
    private fun enterPip(): Boolean {
        val st = calls.ui.value
        if (!pipSupported() || !CallPip.eligible(st) || isInPictureInPictureMode) return false
        return try {
            enterPictureInPictureMode(pipParams(st))
        } catch (_: Exception) {
            false
        }
    }

    /** Zurueck/Minimieren: mit Video ins PiP, sonst in den Hintergrund (Anrufleiste wie in 2.3.1) */
    private fun minimizeOrPip() {
        if (!enterPip()) minimize()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 8-11: kein Auto-Enter - beim Verlassen (Home/App-Wechsel) selbst ins PiP
        if (Build.VERSION.SDK_INT < 31) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        calls.setView(pip = isInPictureInPictureMode, visible = true)
    }

    override fun onStart() {
        super.onStart()
        calls.setView(pip = isInPictureInPictureMode, visible = true)
    }

    override fun onStop() {
        super.onStop()
        // Nicht sichtbar (minimiert, PiP weggewischt, Bildschirm aus): Gegenueber darf sein Video verkleinern
        calls.setView(pip = false, visible = false)
    }

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
        if (intent?.action == ACTION_ANSWER) answerRequest++
        setContent {
            SagaTheme {
                val st by calls.ui.collectAsState()
                LaunchedEffect(st == null) { if (st == null) finish() }
                // PiP-Parameter aktuell halten: Seitenverhaeltnis des Fernbilds, Stumm-Knopf, Auto-Enter nur mit Video
                val eligible = CallPip.eligible(st)
                LaunchedEffect(eligible, st?.mic, remoteAspect) { updatePip(calls.ui.value) }
                // Zurueck (Taste/Geste) minimiert nur: Anruf, Medien und Vordergrund-Dienst laufen weiter; zurueck geht
                // es ueber die Leiste "Anruf laeuft", den Hinweis im Chat oder die Mitteilung (gleiche Activity, keine
                // zweite PeerConnection). Ein beendeter Anruf wird geschlossen.
                // 2.4: mit aktivem Video geht es ins Bild-im-Bild statt nur in den Hintergrund.
                BackHandler {
                    if (CallReturn.backMinimizes(calls.ui.value)) minimizeOrPip() else {
                        calls.dismiss()
                        finish()
                    }
                }
                st?.let { CallScreen(calls, it, answerRequest, ::minimizeOrPip, pip = inPip, onAspect = { a -> remoteAspect = a }) }
            }
        }
    }

    /**
     * Anrufbildschirm in den Hintergrund (eigene Aufgabe, siehe taskAffinity): darunter erscheint wieder die App bzw.
     * der Startbildschirm. Die Activity bleibt erhalten (ohne Video; mit Video siehe minimizeOrPip).
     */
    private fun minimize() {
        if (!moveTaskToBack(true)) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_ANSWER) answerRequest++
    }
}

@Composable
private fun CallScreen(
    calls: CallManager, st: CallUiState, answerRequest: Int, onMinimize: () -> Unit,
    pip: Boolean = false, onAspect: (Float?) -> Unit = {},
) {
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
    // 2.4 E3: Bildschirm teilen - Einwilligung des Systems (MediaProjection), davor einmal der Datenschutz-Hinweis
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == android.app.Activity.RESULT_OK && data != null) calls.startScreen(data)
    }
    var shareHint by remember { mutableStateOf(false) }
    val pickScreen: () -> Unit = {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        if (mpm != null) try {
            // Android 14+: Auswahl ganzer Bildschirm oder eine einzelne App (Standard des Systems)
            shareLauncher.launch(
                if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
                else mpm.createScreenCaptureIntent(),
            )
        } catch (_: Exception) {
            (context.applicationContext as SagaApplication).repo.notify(io.github.yggdrasil_sh.saga.util.UiText.Res(R.string.call_screen_failed))
        }
    }
    val onShare: () -> Unit = {
        when {
            st.sharing -> calls.stopScreen()
            ScreenHint.off(context) -> pickScreen()
            else -> shareHint = true
        }
    }
    if (shareHint) ScreenHintDialog(onDismiss = { shareHint = false }) { dontShow ->
        shareHint = false
        if (dontShow) ScreenHint.setOff(context)
        pickScreen()
    }
    var camDenied by remember { mutableStateOf(false) }      // Hinweis, wenn die Kamera im Anruf verweigert wurde
    LaunchedEffect(camDenied) { if (camDenied) { delay(6000); camDenied = false } }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) calls.toggleCam() else camDenied = true
    }
    val onCam = {
        if (!st.cam && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            camPerm.launch(Manifest.permission.CAMERA)
        } else calls.toggleCam()
    }
    LaunchedEffect(answerRequest) {
        if (answerRequest > 0 && st.phase == CallPhase.INCOMING) answer(st.video)
    }

    val dark = Color(0xFF101114)
    var infoHeight by remember { mutableIntStateOf(0) }
    val showVideo = st.remoteVideo && st.phase == CallPhase.CONNECTED
    Box(Modifier.fillMaxSize().background(if (showVideo || pip) Color.Black else MaterialTheme.colorScheme.surface)) {
        if (st.phase != CallPhase.INCOMING && st.phase != CallPhase.ENDED) {
            // Bild des Gegenuebers eingepasst (ganzes Bild, Rand schwarz): SurfaceViewRenderer schneidet bei fester
            // Groesse auf das Seitenverhaeltnis der Ansicht zu - daher die Ansicht selbst auf das Videoformat begrenzen.
            // Bild-im-Bild nutzt DIESELBE Ansicht (gleiche Stelle im Compose-Baum -> kein zweiter Renderer).
            var aspect by remember { mutableStateOf<Float?>(null) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VideoView(
                    calls, remote = true, visible = showVideo,
                    modifier = aspect?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
                    onAspect = {
                        aspect = it
                        onAspect(it)
                    },
                )
            }
        }
        if (pip) {
            // Bild-im-Bild: nur Fernbild bzw. Name, keine Chat-Inhalte und keine Knoepfe (die kommen als RemoteActions)
            val name = st.peer.name.ifEmpty { st.peer.username }
            val pipText = if (st.phase == CallPhase.ENDED) st.message?.resolve() ?: name else name
            val pipLabel = stringResource(R.string.call_pip_video, name)
            Box(
                Modifier.fillMaxSize().semantics { contentDescription = pipLabel },
                contentAlignment = if (showVideo) Alignment.BottomStart else Alignment.Center,
            ) {
                if (!showVideo || st.phase == CallPhase.ENDED) Text(
                    pipText, color = Color.White, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(6.dp),
                )
            }
        } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.onSizeChanged { infoHeight = it.height }, horizontalAlignment = Alignment.CenterHorizontally) {
                InfoLine(st, onDark = showVideo)
                ShareBar(calls, st)
            }
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
                camDenied -> stringResource(R.string.call_cam_denied)
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
            Controls(calls, st, ::answer, onCam, onShare)
        }
        if (st.remoteSmall && st.phase == CallPhase.CONNECTED) {
            // E3: Kamera des Gegenuebers klein neben seinem Bildschirm (bzw. getauscht) - Tippen tauscht die Ansicht
            val top = with(LocalDensity.current) { infoHeight.toDp() } + 24.dp
            val label = stringResource(R.string.call_screen_small, st.peer.name.ifEmpty { st.peer.username })
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = maxOf(top, 56.dp), start = 16.dp), contentAlignment = Alignment.TopStart) {
                Box(Modifier.width(120.dp).height(160.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                    VideoView(calls, remote = true, visible = true, modifier = Modifier.fillMaxSize(), sink = calls.remoteSink2, overlay = true)
                    // Tippflaeche ueber dem SurfaceView (der selbst keine Beruehrungen auswertet)
                    Box(
                        Modifier.matchParentSize()
                            .clickable(onClickLabel = stringResource(R.string.call_screen_swap)) { calls.swapView() }
                            .semantics { contentDescription = label },
                    )
                }
            }
        }
        if (st.cam && st.phase != CallPhase.ENDED && st.phase != CallPhase.INCOMING) {
            // Eigene Vorschau unter der Status-Zeile (die bei grosser Schrift mitwaechst)
            val top = with(LocalDensity.current) { infoHeight.toDp() } + 24.dp
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = maxOf(top, 56.dp), end = 16.dp), contentAlignment = Alignment.TopEnd) {
                VideoView(calls, remote = false, visible = true, modifier = Modifier.width(108.dp).height(156.dp).clip(RoundedCornerShape(12.dp)))
            }
        }
        if (st.phase != CallPhase.INCOMING && st.phase != CallPhase.ENDED) {
            // Minimieren (wie Zurueck): zurueck in die App, der Anruf laeuft weiter
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(4.dp), contentAlignment = Alignment.TopStart) {
                IconButton(onClick = onMinimize) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown, stringResource(R.string.call_minimize),
                        tint = if (showVideo) Color.White else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun InfoLine(st: CallUiState, onDark: Boolean, modifier: Modifier = Modifier) {
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
    Surface(color = if (onDark) Color(0x99101114) else Color.Transparent, shape = RoundedCornerShape(12.dp), modifier = modifier) {
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
private fun Controls(calls: CallManager, st: CallUiState, answer: (Boolean) -> Unit, onCam: () -> Unit, onShare: () -> Unit) {
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
            // 2.4 E3: "Bildschirm teilen" nur im verbundenen Anruf; bei sechs Knoepfen etwas kleiner (schmale Telefone)
            val share = st.phase == CallPhase.CONNECTED
            val compact = share && st.cam
            RoundButton(
                if (st.mic) R.drawable.ic_mic else R.drawable.ic_mic_off, stringResource(if (st.mic) R.string.call_mute else R.string.call_unmute),
                if (st.mic) tc else MaterialTheme.colorScheme.inverseSurface, if (st.mic) tf else MaterialTheme.colorScheme.inverseOnSurface,
                compact = compact,
            ) { calls.toggleMic() }
            RoundButton(
                if (st.cam) R.drawable.ic_videocam else R.drawable.ic_videocam_off, stringResource(if (st.cam) R.string.call_cam_off else R.string.call_cam_on),
                if (st.cam) tc else MaterialTheme.colorScheme.inverseSurface, if (st.cam) tf else MaterialTheme.colorScheme.inverseOnSurface,
                compact = compact,
            ) { onCam() }
            if (st.cam) RoundButton(R.drawable.ic_cameraswitch, stringResource(R.string.call_switch_camera), tc, tf, compact = compact) { calls.switchCamera() }
            if (share) RoundButton(
                R.drawable.ic_screen_share, stringResource(if (st.sharing) R.string.call_screen_stop else R.string.call_screen_share),
                if (st.sharing) SHARE_RED else tc, if (st.sharing) Color.White else tf, selected = st.sharing, compact = compact,
            ) { onShare() }
            RoundButton(
                R.drawable.ic_volume_up, stringResource(R.string.call_speaker),
                if (st.speaker) MaterialTheme.colorScheme.primary else tc, if (st.speaker) MaterialTheme.colorScheme.onPrimary else tf,
                selected = st.speaker, compact = compact,
            ) { calls.toggleSpeaker() }
            RoundButton(R.drawable.ic_call_end, stringResource(R.string.call_hangup), MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, compact = compact) { calls.hangup() }
        }
    }
}

private val CALL_GREEN = Color(0xFF1E8E3E)
/** Rote Leiste/Knopf beim Bildschirm-Teilen */
private val SHARE_RED = Color(0xFFC62828)

/**
 * 2.4 E3: Leiste beim Teilen - "Du teilst deinen Bildschirm" mit "Beenden" (und Hinweis, wenn die Kamera ueber eine
 * langsame Verbindung pausiert) bzw. "<Name> teilt den Bildschirm" mit "Ansicht tauschen", wenn die Kamera daneben laeuft.
 */
@Composable
private fun ShareBar(calls: CallManager, st: CallUiState) {
    if (st.phase != CallPhase.CONNECTED || (!st.sharing && !st.peerSharing)) return
    Surface(color = SHARE_RED, contentColor = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val text = if (st.sharing) stringResource(R.string.call_screen_you)
                else stringResource(R.string.call_screen_peer, st.peer.name.ifEmpty { st.peer.username })
                Icon(painterResource(R.drawable.ic_screen_share), null, Modifier.size(16.dp))
                Text(
                    text, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (st.sharing) TextButton(onClick = { calls.stopScreen() }) {
                    Text(stringResource(R.string.call_screen_stop), color = Color.White)
                } else if (st.remoteSmall) TextButton(onClick = { calls.swapView() }) {
                    Text(stringResource(R.string.call_screen_swap), color = Color.White)
                }
            }
            if (st.sharing && st.camPaused) Text(stringResource(R.string.call_screen_cam_paused), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** "Nicht mehr anzeigen" fuer den Hinweis vor dem ersten Teilen (je Geraet) */
object ScreenHint {
    private const val PREFS = "saga_call"
    private const val KEY = "screen_hint_off"

    fun off(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setOff(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY, true) }
    }
}

/** Datenschutz-Hinweis vor dem Teilen: alles in der Auswahl ist sichtbar; nichts wird aufgezeichnet */
@Composable
private fun ScreenHintDialog(onDismiss: () -> Unit, onContinue: (dontShow: Boolean) -> Unit) {
    var dontShow by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_screen_share), null) },
        title = { Text(stringResource(R.string.call_screen_hint_title)) },
        text = {
            Column {
                Text(stringResource(R.string.call_screen_hint))
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).toggleable(value = dontShow, role = Role.Checkbox) { dontShow = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontShow, onCheckedChange = null)
                    Text(stringResource(R.string.call_screen_hint_off), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onContinue(dontShow) }) { Text(stringResource(R.string.call_screen_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RoundButton(
    icon: Int, label: String, bg: Color, fg: Color, big: Boolean = false, selected: Boolean? = null, compact: Boolean = false,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = bg, contentColor = fg),
        modifier = Modifier.size(if (big) 72.dp else if (compact) 52.dp else 60.dp).semantics {
            contentDescription = label
            if (selected != null) this.selected = selected
        },
        shape = CircleShape,
    ) {
        Icon(painterResource(icon), null, Modifier.size(if (big) 34.dp else if (compact) 24.dp else 28.dp))
    }
}

/** WebRTC-Bild (SurfaceViewRenderer) - haengt sich an die ProxySink des Managers und loest sich beim Verlassen */
@Composable
private fun VideoView(
    calls: CallManager, remote: Boolean, visible: Boolean, modifier: Modifier, onAspect: ((Float?) -> Unit)? = null,
    sink: ProxySink = if (remote) calls.remoteSink else calls.localSink, overlay: Boolean = !remote,
) {
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val aspectCb = androidx.compose.runtime.rememberUpdatedState(onAspect)
    val events = remember {
        object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {}
            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                val a = CallLogic.videoAspect(videoWidth, videoHeight, rotation)
                view?.post { aspectCb.value?.invoke(a) }       // kommt vom Render-Thread
            }
        }
    }
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
                init(calls.egl.eglBaseContext, if (remote) events else null)
                setScalingType(if (remote) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (!remote) setMirror(true)
                if (overlay) setZOrderMediaOverlay(true)        // kleine Bilder ueber dem grossen
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
