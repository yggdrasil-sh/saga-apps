package io.github.yggdrasil_sh.saga.call

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.chat.ChatAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Meeting-Ansicht (MEETING-SPEC.md): eine Buehne fuer 1:1, Mesh und Knoten - Personenkacheln ueber `who`, Bildschirm
// gross und ganz (contain, kein Kamera-Bild-im-Bild), Streifen, Galerie mit Seiten, Hinweis-Chip, Zoom, ausblendende
// Leisten. Die Bilder liefert der Aufrufer (echte Senken bzw. Demo-Flaechen).

/** Sprechring und Kachelflaechen */
internal val MEET_SPEAK = Color(0xFF56D364)
private val MEET_TILE = Color(0xFF202227)
private val MEET_BAR = Color(0xB3101114)            // 70 % Schwarz

/**
 * Zustand, der nur fuer diesen Anruf gilt (Pin, Vollbild, ausgeblendete Hinweise) bzw. je Geraet gespeichert wird
 * (Layout, eigenes Bild, Streifen) - gespeichert ueber [MeetPrefs].
 */
@Stable
class MeetState(pref: Meeting.Pref = Meeting.Pref.AUTO, selfSize: Int = 1, stripCollapsed: Boolean = false, showSelf: Boolean = true, showNoVideo: Boolean = true) {
    var pref by mutableStateOf(pref)
    /** nur fuer diesen Anruf (Galerie-Hinweis "Anzeigen"): Vorrang vor pref, Layout-Menue loescht es */
    var callPref by mutableStateOf<Meeting.Pref?>(null)
    val effPref: Meeting.Pref get() = callPref ?: pref
    var pin by mutableStateOf<String?>(null)
    var fullscreen by mutableStateOf(false)
    var selfSize by mutableIntStateOf(selfSize)
    var stripCollapsed by mutableStateOf(stripCollapsed)
    var showSelf by mutableStateOf(showSelf)
    var showNoVideo by mutableStateOf(showNoVideo)
    /** Bildschirmkacheln, deren Hinweis-Chip weggeklickt wurde */
    val chipOff = mutableStateListOf<String>()
    /** Galerie: Wechsel auf Praesentation fuer diesen Anruf angeboten (Hinweis "… teilt – Anzeigen") */
    var galleryHint by mutableStateOf<String?>(null)
    val hinted = mutableStateListOf<String>()
    /** Hinweis "Angeheftete Ansicht beendet" */
    var pinEnded by mutableLongStateOf(0L)
}

/** Speicher je Geraet (SharedPreferences "call_ui"; Schluessel nach MEETING-SPEC §2.6) */
internal object MeetPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("call_ui", Context.MODE_PRIVATE)
    fun load(ctx: Context, form: String): MeetState = p(ctx).let {
        MeetState(
            Meeting.Pref.of(it.getString("call_layout", null)), it.getInt("call_self_$form", 1).coerceIn(0, 2),
            it.getBoolean("call_strip", false), !it.getBoolean("self_hidden_all", false), it.getBoolean("call_show_novideo", true),
        )
    }
    fun save(ctx: Context, form: String, s: MeetState) {
        p(ctx).edit().putString("call_layout", s.pref.key).putInt("call_self_$form", s.selfSize).putBoolean("call_strip", s.stripCollapsed)
            .putBoolean("self_hidden_all", !s.showSelf).putBoolean("call_show_novideo", s.showNoVideo).apply()
    }
}

// ---------------------------------------------------------------- Bild (TextureView: Zoom und Ueberlagerungen moeglich)

/**
 * WebRTC-Bild in einer TextureView (statt SurfaceView): folgt Compose-Transformationen (Pinch-Zoom) und der normalen
 * Zeichenreihenfolge (Chips/Leisten liegen sicher darueber). Zeichnet das Bild auf das Seitenverhaeltnis der Ansicht
 * zugeschnitten; fuer "contain" bekommt die Ansicht selbst das Videoformat.
 */
internal class TextureVideoView(ctx: Context, egl: EglBase.Context, mirror: Boolean) : TextureView(ctx), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("meet")
    @Volatile var onAspect: ((Float?) -> Unit)? = null
    private var fw = 0
    private var fh = 0

    init {
        renderer.init(egl, EglBase.CONFIG_PLAIN, GlRectDrawer())
        renderer.setMirror(mirror)
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        renderer.createEglSurface(st)
        if (h > 0) renderer.setLayoutAspectRatio(w.toFloat() / h)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        if (h > 0) renderer.setLayoutAspectRatio(w.toFloat() / h)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        val l = CountDownLatch(1)
        renderer.releaseEglSurface { l.countDown() }
        try { l.await(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onFrame(frame: VideoFrame) {
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != fw || h != fh) {
            fw = w
            fh = h
            post { onAspect?.invoke(CallLogic.videoAspect(w, h, 0)) }
        }
        renderer.onFrame(frame)
    }

    fun release() = renderer.release()
}

/** Bild an einer Senke; fit = ganz sichtbar (contain), sonst fuellend (cover) */
@Composable
internal fun MeetVideo(calls: CallManager, sink: ProxySink, mirror: Boolean, fit: Boolean, modifier: Modifier, onAspect: ((Float?) -> Unit)? = null) {
    var aspect by remember { mutableStateOf<Float?>(null) }
    val cb = rememberUpdatedState(onAspect)
    var view by remember { mutableStateOf<TextureVideoView?>(null) }
    DisposableEffect(sink) {
        view?.let { sink.target = it }
        onDispose {
            view?.let { v -> if (sink.target === v) sink.target = null }
        }
    }
    DisposableEffect(Unit) { onDispose { view?.release() } }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                TextureVideoView(ctx, calls.egl.eglBaseContext, mirror).also { v ->
                    v.onAspect = { a -> aspect = a; cb.value?.invoke(a) }
                    sink.target = v
                    view = v
                }
            },
            modifier = if (fit) aspect?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize() else Modifier.fillMaxSize(),
        )
    }
}

// ---------------------------------------------------------------- Beschriftungen

@Composable
internal fun meetLabel(t: Meeting.Tile): String = when {
    t.me -> stringResource(R.string.room_you)
    t.screen && t.devName.isNotEmpty() -> stringResource(R.string.meet_screen_of_dev, t.name, t.devName)
    t.screen -> stringResource(R.string.meet_screen_of, t.name)
    t.kind == Meeting.Kind.CAM2 -> stringResource(R.string.meet_cam2, t.name, t.devName)
    else -> t.name
}

@Composable
private fun meetDesc(t: Meeting.Tile, speaking: Boolean, pinned: Boolean): String = listOfNotNull(
    meetLabel(t),
    stringResource(R.string.room_speaking).takeIf { speaking && !t.screen },
    stringResource(R.string.room_muted).takeIf { !t.mic && !t.screen },
    if (t.screen) null else stringResource(if (t.video) R.string.meet_cam_on else R.string.meet_no_cam),
    stringResource(R.string.meet_pinned).takeIf { pinned },
).joinToString(", ")

/** Namensleiste: Stumm, Sprecher-Symbol (Farbe nicht allein), Pin, Name (kuerzt mit …) */
@Composable
private fun NameBar(t: Meeting.Tile, speaking: Boolean, pinned: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.background(MEET_BAR, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t.screen) Icon(painterResource(R.drawable.ic_screen_share), null, Modifier.size(14.dp), tint = Color.White)
        else if (!t.mic) Icon(painterResource(R.drawable.ic_mic_off), null, Modifier.size(14.dp), tint = Color.White)
        else if (speaking) Icon(painterResource(R.drawable.ic_volume_up), null, Modifier.size(14.dp), tint = MEET_SPEAK)
        if (pinned) Icon(painterResource(R.drawable.ic_push_pin), null, Modifier.padding(start = 2.dp).size(14.dp), tint = Color.White)
        Text(
            meetLabel(t), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}

// ---------------------------------------------------------------- Kachel

/**
 * Kachel im Raster bzw. Streifen: Bild (cover) oder Avatar in der Personenfarbe, Sprechring, Namensleiste. Tippen =
 * onTap (Streifen: auf die Buehne), langes Druecken = Kachelmenue (anheften/loesen, Vollbild).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MeetTile(
    t: Meeting.Tile, showVideo: Boolean, speaking: Boolean, pinned: Boolean, modifier: Modifier,
    tileVideo: @Composable (Meeting.Tile, Boolean, Modifier) -> Unit,
    onTap: (() -> Unit)?, onPin: (String?) -> Unit, onFullscreen: (() -> Unit)?, small: Boolean = false,
) {
    val shape = RoundedCornerShape(if (small) 10.dp else 14.dp)
    val tint = io.github.yggdrasil_sh.saga.ui.chat.ChatDesign.tone(t.name, dark = true).bg
    var menu by remember { mutableStateOf(false) }
    val desc = meetDesc(t, speaking, pinned)
    Box(
        modifier.clip(shape)
            .background(if (t.screen) Color.Black else if (showVideo && t.video) MEET_TILE else tint.copy(alpha = 0.45f).compositeOver(MEET_TILE))
            .then(if (speaking && !t.screen) Modifier.border(3.dp, MEET_SPEAK, shape) else Modifier)
            .combinedClickable(
                onClickLabel = if (t.me) null else stringResource(if (pinned) R.string.meet_unpin else R.string.meet_pin),
                onClick = { onTap?.invoke() }, onLongClick = if (t.me) null else ({ menu = true }),
            )
            .semantics(mergeDescendants = true) { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        when {
            showVideo && t.video -> tileVideo(t, t.screen, Modifier.fillMaxSize())
            t.screen -> Icon(painterResource(R.drawable.ic_screen_share), null, Modifier.size(if (small) 24.dp else 40.dp), tint = Color.White.copy(alpha = 0.7f))
            else -> ChatAvatar(t.name, if (small) 36.dp else 64.dp, avatar = t.avatar)
        }
        NameBar(t, speaking, pinned, Modifier.align(Alignment.BottomStart).padding(if (small) 4.dp else 8.dp).widthIn(max = 600.dp))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (pinned) R.string.meet_unpin else R.string.meet_pin)) },
                onClick = { menu = false; onPin(if (pinned) null else t.id) },
            )
            if (t.screen && onFullscreen != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.meet_fullscreen)) }, onClick = { menu = false; onFullscreen() },
            )
        }
    }
}

private fun Color.compositeOver(bg: Color): Color = androidx.compose.ui.graphics.Color(
    red = red * alpha + bg.red * (1 - alpha), green = green * alpha + bg.green * (1 - alpha), blue = blue * alpha + bg.blue * (1 - alpha), alpha = 1f,
)

// ---------------------------------------------------------------- Buehne

/**
 * Buehne: Bildschirm schwarz und ganz (contain, Radius 0), Pinch-Zoom 1x..4x mit Verschieben, Doppeltippen = Vollbild;
 * Person: Bild ganz bzw. Avatar. Namensleiste und Hinweis-Chip unten links blenden mit den Leisten aus.
 */
@Composable
private fun StageView(
    t: Meeting.Tile, speaking: Boolean, pinned: Boolean, controls: Boolean, ms: MeetState, modifier: Modifier,
    tileVideo: @Composable (Meeting.Tile, Boolean, Modifier) -> Unit, chipBottom: Dp,
) {
    var scale by remember(t.id) { mutableFloatStateOf(1f) }
    var off by remember(t.id) { mutableStateOf(Offset.Zero) }
    var zoomShown by remember { mutableLongStateOf(0L) }
    val stageCd = stringResource(R.string.meet_stage_cd, meetLabel(t))
    BoxWithConstraints(
        modifier.background(Color.Black).clip(RoundedCornerShape(0.dp)).semantics { paneTitle = stageCd },
        contentAlignment = Alignment.Center,
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (t.screen) Modifier.pointerInput(t.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val s = Meeting.zoom(scale * zoom)
                            scale = s
                            off = Offset(Meeting.pan(off.x + pan.x, s, wPx), Meeting.pan(off.y + pan.y, s, hPx))
                            zoomShown = System.currentTimeMillis()
                        }
                    } else Modifier,
                )
                .pointerInput(t.id) { detectTapGestures(onDoubleTap = { if (t.screen) ms.fullscreen = !ms.fullscreen }) }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y },
            contentAlignment = Alignment.Center,
        ) {
            when {
                t.screen && t.screenNa -> Text(
                    stringResource(R.string.meet_screen_na, t.name), color = Color.White, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )
                t.video -> tileVideo(t, true, Modifier.fillMaxSize())
                else -> ChatAvatar(t.name, 120.dp, avatar = t.avatar)
            }
        }
        if (speaking && !t.screen) Box(Modifier.fillMaxSize().border(3.dp, MEET_SPEAK))
        // Hinweis-Chip "Anna teilt den Bildschirm" bzw. Namensleiste der Buehne (blenden mit den Leisten aus)
        val chip = t.screen && t.id !in ms.chipOff
        AnimatedVisibility(
            controls, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = chipBottom + 12.dp),
            enter = fadeIn(tween(200)), exit = fadeOut(tween(200)),
        ) {
            // hoechstens die halbe Buehne, auf schmalen Buehnen mind. 260 dp (hoechstens 90 %)
            val maxW = with(LocalDensity.current) { maxOf(wPx * 0.5f, minOf(260.dp.toPx(), wPx * 0.9f)).toDp() }
            if (chip) ShareChip(t, Modifier.widthIn(max = maxW)) { ms.chipOff.add(t.id) }
            else NameBar(t, speaking, pinned)
        }
        // Zoom-Anzeige "200 %" 2 s mit Zuruecksetzen
        LaunchedEffect(zoomShown) { if (zoomShown > 0) { delay(2000); zoomShown = 0 } }
        if (zoomShown > 0 && scale > 1.01f) Surface(
            color = MEET_BAR, contentColor = Color.White, shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = chipBottom + 52.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.meet_zoom, (scale * 100).toInt()), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { scale = 1f; off = Offset.Zero }) { Text(stringResource(R.string.meet_zoom_reset), color = Color.White) }
            }
        }
    }
}

/** §5.5: Chip unten links in der Buehne (70 % Schwarz, 32 dp hoch, Name gekuerzt) mit × (48 dp Trefferflaeche) */
@Composable
private fun ShareChip(t: Meeting.Tile, modifier: Modifier, onClose: () -> Unit) {
    val nm = HornLogic.shortName(t.name, 14)
    val text = if (t.devName.isNotEmpty()) stringResource(R.string.meet_sharing_chip, "$nm (${t.devName})") else stringResource(R.string.meet_sharing_chip, nm)
    Row(
        modifier.height(32.dp).background(MEET_BAR, RoundedCornerShape(16.dp)).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_screen_share), null, Modifier.size(16.dp), tint = Color.White)
        Text(
            text, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
        )
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp).padding(8.dp)) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.meet_chip_close), tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

// ---------------------------------------------------------------- Ebene: Buehne + Streifen bzw. Galerie

/**
 * Die ganze Meeting-Flaeche (fuellt das Fenster; Kopf und Knoepfe liegen darueber und blenden aus).
 * insetTop/insetBottom: Hoehe von Kopf/Knoepfen, solange sichtbar (eigenes Bild und unterer Streifen weichen aus).
 */
@Composable
internal fun MeetingStage(
    model: Meeting.Model, ms: MeetState, speaker: String?, controls: Boolean, reducedMotion: Boolean, modifier: Modifier,
    insetTop: Dp, insetBottom: Dp,
    tileVideo: @Composable (Meeting.Tile, Boolean, Modifier) -> Unit,
    selfVideo: @Composable (Modifier, (Float?) -> Unit) -> Unit,
    phone: Boolean,
    insetEnd: Dp = 0.dp,
    /** Geraete, deren Kamera gerade mit Bild gezeigt wird (Mesh: `want` der anderen auf off) */
    onShown: (Set<String>) -> Unit = {},
    /** Chip "+ 3 ohne Video": Teilnehmerliste oeffnen */
    onPeople: (() -> Unit)? = null,
) {
    // §3 Kacheln ohne Video ausgeblendet: wer spricht, erscheint waehrend des Sprechens + 5 s
    val spokeAt = remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
    var tick by remember { mutableIntStateOf(0) }
    val now = System.currentTimeMillis().also { @Suppress("UNUSED_EXPRESSION") tick }
    model.people.filter { it.speaking }.forEach { spokeAt[it.who] = now }
    LaunchedEffect(spokeAt.values.maxOrNull()) { delay(5100); tick++ }
    val keep: (Meeting.Tile) -> Boolean = { it.video || it.me || it.speaking || (spokeAt[it.who]?.let { t -> now - t < 5000 } == true) }
    val m = if (ms.showNoVideo) model else model.copy(people = model.people.filter(keep))
    val noVideo = if (ms.showNoVideo) 0 else model.people.count { !keep(it) }
    val view = Meeting.pickLayout(ms.effPref, m, reducedMotion)
    // Pin faellt weg, wenn die Quelle endet
    LaunchedEffect(ms.pin, m.all.map { it.id }) {
        val p = ms.pin
        if (p != null && m.all.none { it.id == p }) {
            ms.pin = null
            ms.pinEnded = System.currentTimeMillis()
        }
    }
    val anim = if (reducedMotion) snap<Dp>() else tween(200)
    val bottom by animateDpAsState(if (controls) insetBottom else 0.dp, anim, label = "bottom")
    val end by animateDpAsState(if (controls) insetEnd else 0.dp, anim, label = "end")
    val speakingWho = m.people.filter { it.speaking }.map { it.who }.toSet()
    BoxWithConstraints(modifier.background(Color.Black)) {
        val w = maxWidth.value
        val h = maxHeight.value
        when (view) {
            Meeting.View.GALLERY -> Box(Modifier.fillMaxSize().padding(end = end)) {
                Gallery(m, ms, speaker, controls, w - end.value, h, bottom, insetTop, tileVideo, speakingWho, onShown)
            }
            else -> {
                val st = Meeting.stage(view, m, ms.pin, speaker)
                // Android: eigenes Bild schwebt nur im 1:1 (DUO); in Praesentation/Sprecher steht es im Streifen und
                // ueberdeckt so nie den geteilten Bildschirm (§7 letzter Punkt)
                val floats = view == Meeting.View.DUO
                val strip = st.strip.filter { !(it.me && floats) } + (if (!floats && st.strip.none { it.me }) listOfNotNull(m.self) else emptyList())
                val sp = Meeting.strip(w, h)
                val showStrip = view != Meeting.View.DUO && strip.isNotEmpty() && !ms.fullscreen
                val right = sp.side == Meeting.Side.RIGHT
                val shown = (listOfNotNull(st.stage?.takeIf { !it.screen }?.device) +
                    (if (showStrip && !ms.stripCollapsed) Meeting.videoSlots(strip, sp.videos, ms.pin, null, emptyMap()).let { v -> strip.filter { it.id in v && !it.screen }.map { it.device } } else emptyList())).toSet()
                LaunchedEffect(shown) { onShown(shown) }
                val stage: @Composable (Modifier) -> Unit = { mod ->
                    val t = st.stage
                    if (t != null) StageView(
                        t, t.who in speakingWho && view != Meeting.View.DUO, ms.pin == t.id, controls, ms, mod, tileVideo,
                        chipBottom = if (right || !showStrip) bottom else 0.dp,
                    ) else Box(mod)
                }
                if (!showStrip) stage(Modifier.fillMaxSize())
                else if (right) Row(Modifier.fillMaxSize()) {
                    stage(Modifier.weight(1f).fillMaxHeight())
                    Strip(strip, ms, sp, true, Modifier.fillMaxHeight().padding(top = insetTop.coerceAtMost(48.dp), bottom = bottom, end = end), tileVideo, speakingWho)
                }
                else Column(Modifier.fillMaxSize()) {
                    stage(Modifier.weight(1f).fillMaxWidth())
                    Strip(strip, ms, sp, false, Modifier.fillMaxWidth().padding(bottom = bottom), tileVideo, speakingWho)
                }
                if (floats && ms.showSelf) m.self?.let { self ->
                    SelfFloat(self, ms, w, h, insetTop, bottom, phone, selfVideo)
                }
            }
        }
        // "+ 3 ohne Video" am Ende von Raster/Streifen (oeffnet die Teilnehmerliste)
        if (noVideo > 0) {
            val label = stringResource(R.string.meet_more_novideo, noVideo)
            Surface(
                color = MEET_BAR, contentColor = Color.White, shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp + end, bottom = bottom + 12.dp)
                    .then(if (onPeople != null) Modifier.clip(RoundedCornerShape(50)).clickable(role = androidx.compose.ui.semantics.Role.Button) { onPeople() } else Modifier),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        val ended = ms.pinEnded
        LaunchedEffect(ended) { if (ended > 0) { delay(3000); ms.pinEnded = 0 } }
        if (ended > 0) Surface(
            color = MEET_BAR, contentColor = Color.White, shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(stringResource(R.string.meet_pin_ended), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** Eigenes Bild schwebend: Seitenverhaeltnis der Kamera, S/M/L per Doppeltippen, Ecken, runde Ecken 14 dp */
@Composable
private fun SelfFloat(
    self: Meeting.Tile, ms: MeetState, w: Float, h: Float, top: Dp, bottom: Dp, phone: Boolean,
    selfVideo: @Composable (Modifier, (Float?) -> Unit) -> Unit,
) {
    var aspect by remember { mutableStateOf<Float?>(null) }
    val avail = h - top.value - bottom.value
    val (pw, ph) = Meeting.selfPipSize(aspect, if (phone) 9f / 16f else 16f / 9f, w, avail, Meeting.SIZE_FACTORS[ms.selfSize])
    val sizeLabel = stringResource(R.string.meet_self_size)
    SelfPip(pw.dp, ph.dp, top = top + 8.dp, bottom = bottom + 8.dp, side = 16.dp, onDoubleTap = { ms.selfSize = (ms.selfSize + 1) % 3 }, sizeLabel = sizeLabel) { mod ->
        Box(mod.border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))) {
            if (self.video) selfVideo(Modifier.fillMaxSize()) { a -> aspect = a }
            else Box(Modifier.fillMaxSize().background(MEET_TILE), contentAlignment = Alignment.Center) { ChatAvatar(self.name.ifEmpty { "?" }, 40.dp, avatar = self.avatar) }
            Text(
                stringResource(R.string.room_you), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(MEET_BAR, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** Streifen rechts (senkrecht scrollbar) bzw. unten (waagrecht, letzte Kachel angeschnitten); einklappbar */
@Composable
private fun Strip(
    tiles: List<Meeting.Tile>, ms: MeetState, sp: Meeting.Strip, right: Boolean, modifier: Modifier,
    tileVideo: @Composable (Meeting.Tile, Boolean, Modifier) -> Unit, speakingWho: Set<String>,
) {
    val vids = Meeting.videoSlots(tiles, sp.videos, ms.pin, null, emptyMap())
    val handleCd = stringResource(if (ms.stripCollapsed) R.string.meet_strip_expand else R.string.meet_strip_collapse)
    val partCd = stringResource(R.string.meet_participants_cd, tiles.size)
    val size = sp.size.dp
    val handle: @Composable () -> Unit = {
        IconButton(onClick = { ms.stripCollapsed = !ms.stripCollapsed }, modifier = Modifier.size(48.dp).semantics { contentDescription = handleCd }) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(MEET_BAR), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_chevron_right), null, tint = Color.White,
                    modifier = Modifier.size(18.dp).graphicsLayer {
                        rotationZ = when {
                            right && !ms.stripCollapsed -> 0f
                            right -> 180f
                            !ms.stripCollapsed -> 90f
                            else -> 270f
                        }
                    },
                )
            }
        }
    }
    val item: @Composable (Meeting.Tile, Modifier) -> Unit = { t, mod ->
        MeetTile(
            t, t.id in vids, t.who in speakingWho && !t.screen, ms.pin == t.id, mod, tileVideo,
            onTap = { if (!t.me) ms.pin = if (ms.pin == t.id) null else t.id }, onPin = { ms.pin = it }, onFullscreen = null, small = true,
        )
    }
    // eingeklappt: nur der Griff und eine 8-dp-Leiste in Sprechring-Farbe
    val bar: @Composable (Modifier) -> Unit = { mod -> Box(mod.background(if (speakingWho.isNotEmpty()) MEET_SPEAK else Color(0xFF3A3D44))) }
    if (right) Column(modifier.width(if (ms.stripCollapsed) 48.dp else size), horizontalAlignment = Alignment.CenterHorizontally) {
        handle()
        if (ms.stripCollapsed) bar(Modifier.width(8.dp).weight(1f))
        else LazyColumn(
            Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = partCd }, verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 6.dp, end = 6.dp, bottom = 6.dp),
        ) {
            itemsIndexed(tiles, key = { _, t -> t.id }) { _, t -> item(t, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
        }
    } else Row(modifier.height(if (ms.stripCollapsed) 48.dp else size), verticalAlignment = Alignment.CenterVertically) {
        handle()
        if (ms.stripCollapsed) bar(Modifier.height(8.dp).weight(1f))
        else LazyRow(
            Modifier.fillMaxHeight().weight(1f).semantics { contentDescription = partCd }, horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 6.dp, end = 6.dp),
        ) {
            itemsIndexed(tiles, key = { _, t -> t.id }) { _, t -> item(t, Modifier.fillMaxHeight().aspectRatio(16f / 9f)) }
        }
    }
}

/** Galerie: gleich grosse 16:9-Kacheln, letzte Reihe mittig; Seiten mit Pfeilen, Punkten und Wischen */
@Composable
private fun Gallery(
    m: Meeting.Model, ms: MeetState, speaker: String?, controls: Boolean, w: Float, h: Float, bottom: Dp, top: Dp,
    tileVideo: @Composable (Meeting.Tile, Boolean, Modifier) -> Unit, speakingWho: Set<String>, onShown: (Set<String>) -> Unit,
) {
    val order = Meeting.galleryOrder(m, ms.pin, emptyMap(), 0L)
    val g = Meeting.grid(w, h, order.size)
    val pages = Meeting.pages(order, g.perPage)
    val pager = rememberPagerState(pageCount = { pages.size })
    val curList = pages.getOrElse(pager.currentPage) { emptyList() }
    val shown = Meeting.videoSlots(curList, g.videos, ms.pin, speaker, emptyMap()).let { v -> curList.filter { it.id in v && !it.screen }.map { it.device } }.toSet()
    LaunchedEffect(shown) { onShown(shown) }
    val scope = rememberCoroutineScope()
    val gap = 8f
    val areaTop = top.value.coerceAtMost(56f)
    val areaH = h - areaTop - maxOf(bottom.value, 24f) - 8f
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { pi ->
            val list = pages.getOrElse(pi) { emptyList() }
            val pg = Meeting.grid(w, h, list.size)
            val cols = pg.cols.coerceAtMost(maxOf(1, list.size))
            val rows = maxOf(1, (list.size + cols - 1) / cols)
            val cellW = (w - 16f - gap * (cols - 1)) / cols
            val cellH = (areaH - gap * (rows - 1)) / rows
            val tw = minOf(cellW, cellH * 16f / 9f)
            val th = tw * 9f / 16f
            val vids = Meeting.videoSlots(list, g.videos, ms.pin, speaker, emptyMap())
            val cd = stringResource(R.string.meet_participants_cd, order.size)
            Column(
                Modifier.fillMaxSize().padding(top = areaTop.dp, bottom = maxOf(bottom.value, 24f).dp).semantics { contentDescription = cd },
                verticalArrangement = Arrangement.spacedBy(gap.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                list.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                        row.forEach { t ->
                            androidx.compose.runtime.key(t.id) {
                                MeetTile(
                                    t, t.id in vids, t.who in speakingWho && !t.screen, ms.pin == t.id, Modifier.width(tw.dp).height(th.dp), tileVideo,
                                    onTap = null, onPin = { ms.pin = it }, onFullscreen = null,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (pages.size > 1) {
            val prev = stringResource(R.string.meet_page_prev)
            val next = stringResource(R.string.meet_page_next)
            AnimatedVisibility(controls && pager.currentPage > 0, Modifier.align(Alignment.CenterStart), enter = fadeIn(), exit = fadeOut()) {
                PageArrow(R.drawable.ic_chevron_right, 180f, prev) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
            }
            AnimatedVisibility(controls && pager.currentPage < pages.size - 1, Modifier.align(Alignment.CenterEnd), enter = fadeIn(), exit = fadeOut()) {
                PageArrow(R.drawable.ic_chevron_right, 0f, next) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
            }
            val pageCd = stringResource(R.string.meet_page, pager.currentPage + 1, pages.size)
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = maxOf(bottom.value, 0f).dp + 6.dp).semantics(mergeDescendants = true) { contentDescription = pageCd },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(pages.size) { i ->
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.35f)))
                }
            }
        }
    }
}

@Composable
private fun PageArrow(icon: Int, rot: Float, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(4.dp).size(48.dp).clip(CircleShape).background(Color(0x80101114))) {
        Icon(painterResource(icon), label, tint = Color.White, modifier = Modifier.graphicsLayer { rotationZ = rot })
    }
}

// ---------------------------------------------------------------- Leisten ausblenden

/**
 * Wurzel der Meeting-Flaeche: jede Beruehrung setzt den Leerlauf zurueck; sind die Leisten aus, holt das erste Tippen sie
 * nur zurueck (wird verbraucht, loest nichts aus).
 */
internal fun Modifier.meetActivity(hidden: () -> Boolean, onActivity: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val wasHidden = hidden()
        onActivity()
        if (wasHidden) {
            down.consume()
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                e.changes.forEach { it.consume() }
                if (e.changes.all { !it.pressed }) break
            }
        } else {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                onActivity()
                if (e.changes.all { !it.pressed }) break
            }
        }
    }
}

/** Kopf-Chip: eigene Begleitgeraete ("iPhone, Galaxy verbunden") bzw. Personenzahl */
@Composable
internal fun MeetCompanions(names: List<String>, onDark: Boolean) {
    if (names.isEmpty()) return
    Surface(color = if (onDark) Color(0x66101114) else MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(50), modifier = Modifier.padding(top = 6.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_devices), null, Modifier.size(16.dp), tint = if (onDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.meet_companions, names.joinToString(", ")), color = if (onDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
internal fun meetPersons(n: Int): String = pluralStringResource(R.plurals.meet_persons, n, n)

/**
 * Layout-Knopf (vier Quadrate) mit Menue: Auto/Galerie/Sprecher/Praesentation (nur wenn jemand teilt), Kacheln ohne
 * Video zeigen, eigenes Bild zeigen. Screenreader: "Layout, Auto".
 */
@Composable
internal fun MeetLayoutButton(ms: MeetState, share: Boolean, col: Dp, bg: Color, fg: Color, onMenu: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val names = mapOf(
        Meeting.Pref.AUTO to stringResource(R.string.meet_layout_auto), Meeting.Pref.GALLERY to stringResource(R.string.meet_layout_gallery),
        Meeting.Pref.SPEAKER to stringResource(R.string.meet_layout_speaker), Meeting.Pref.PRESENTATION to stringResource(R.string.meet_layout_presentation),
    )
    val cur = ms.effPref
    Box {
        RoundButton(
            R.drawable.ic_grid_view, stringResource(R.string.meet_layout), bg, fg, compact = true, col = col, selected = open,
            cd = stringResource(R.string.meet_layout_cd, names[cur].orEmpty()),
        ) {
            open = true; onMenu(true)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false; onMenu(false) }) {
            for (p in Meeting.Pref.entries) {
                val enabled = p != Meeting.Pref.PRESENTATION || share
                DropdownMenuItem(
                    text = { Text(names[p].orEmpty()) }, enabled = enabled,
                    leadingIcon = { androidx.compose.material3.RadioButton(selected = cur == p, onClick = null, enabled = enabled) },
                    onClick = { ms.pref = p; ms.callPref = null; open = false; onMenu(false) },
                )
            }
            androidx.compose.material3.HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.meet_show_novideo)) },
                leadingIcon = { androidx.compose.material3.Checkbox(checked = ms.showNoVideo, onCheckedChange = null) },
                onClick = { ms.showNoVideo = !ms.showNoVideo },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.meet_show_self)) },
                leadingIcon = { androidx.compose.material3.Checkbox(checked = ms.showSelf, onCheckedChange = null) },
                onClick = { ms.showSelf = !ms.showSelf },
            )
        }
    }
}

/**
 * Ganze Meeting-Oberflaeche: Buehne fuellt das Fenster, Kopf (oben) und Knopfleiste (unten) liegen darueber und blenden
 * nach 4 s aus (hidden); pinned (eigene Teilen-Leiste) und notices (wichtige Meldungen) bleiben immer sichtbar.
 * Ausgeblendet bzw. im Vollbild: Systemleisten immersiv (wischen holt sie kurz zurueck).
 */
@Composable
internal fun MeetingLayer(
    model: Meeting.Model, ms: MeetState, speaker: String?, hidden: Boolean, reducedMotion: Boolean, phone: Boolean,
    onActivity: () -> Unit,
    /** Handy quer: Knopfleiste rechts senkrecht, Streifen links davon */
    sideControls: Boolean = false,
    onShown: (Set<String>) -> Unit = {},
    onPeople: (() -> Unit)? = null,
    tileVideo: @Composable (Meeting.Tile, Boolean, Modifier) -> Unit,
    selfVideo: @Composable (Modifier, (Float?) -> Unit) -> Unit,
    header: @Composable () -> Unit,
    pinned: @Composable () -> Unit,
    notices: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    val d = LocalDensity.current
    var topH by remember { mutableIntStateOf(0) }
    var botH by remember { mutableIntStateOf(0) }
    var endW by remember { mutableIntStateOf(0) }
    val hiddenNow = rememberUpdatedState(hidden)
    val view = androidx.compose.ui.platform.LocalView.current
    val immersive = hidden || ms.fullscreen
    LaunchedEffect(immersive) {
        val w = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val c = androidx.core.view.WindowCompat.getInsetsController(w, view)
        if (immersive) {
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(Unit) {
        onDispose {
            val w = (view.context as? android.app.Activity)?.window ?: return@onDispose
            androidx.core.view.WindowCompat.getInsetsController(w, view).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    val enter = if (reducedMotion) fadeIn(snap()) else fadeIn(tween(200))
    val exit = if (reducedMotion) fadeOut(snap()) else fadeOut(tween(200))
    Box(Modifier.fillMaxSize().background(Color.Black).meetActivity({ hiddenNow.value }, onActivity)) {
        MeetingStage(
            model, ms, speaker, controls = !hidden, reducedMotion = reducedMotion, modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout),
            insetTop = with(d) { topH.toDp() }, insetBottom = if (sideControls) 0.dp else with(d) { botH.toDp() },
            insetEnd = if (sideControls) with(d) { endW.toDp() } else 0.dp, tileVideo = tileVideo, selfVideo = selfVideo, phone = phone,
            onShown = onShown, onPeople = onPeople,
        )
        AnimatedVisibility(!hidden && !ms.fullscreen, Modifier.align(Alignment.TopCenter).fillMaxWidth(), enter = enter, exit = exit) {
            Box(
                Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                    .onSizeChanged { topH = it.height },
            ) { header() }
        }
        Box(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(start = 8.dp, top = 4.dp)) { pinned() }
        if (sideControls) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { notices() }
            AnimatedVisibility(!hidden && !ms.fullscreen, Modifier.align(Alignment.CenterEnd).fillMaxHeight(), enter = enter, exit = exit) {
                Box(Modifier.fillMaxHeight().onSizeChanged { endW = it.width }) { controls() }
            }
        } else Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(bottom = 8.dp)) { notices() }
            AnimatedVisibility(!hidden && !ms.fullscreen, enter = enter, exit = exit) {
                Box(Modifier.fillMaxWidth().onSizeChanged { botH = it.height }) { controls() }
            }
        }
    }
}
