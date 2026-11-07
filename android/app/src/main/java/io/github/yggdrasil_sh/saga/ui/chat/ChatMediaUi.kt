package io.github.yggdrasil_sh.saga.ui.chat

import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatImages
import io.github.yggdrasil_sh.saga.chat.ChatMedia
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.video.ImageSave
import io.github.yggdrasil_sh.saga.chat.video.VideoSave
import io.github.yggdrasil_sh.saga.chat.video.VideoSources
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream

// ---------------------------------------------------------------- Bild in der Galerie speichern

/** Stand des Speicherns (der Betrachter zeigt das Ergebnis selbst - die Snackbar liegt hinter dem Dialog) */
@Stable
internal class ImageSaveState {
    var busy by mutableStateOf(false)
    var result by mutableStateOf<Boolean?>(null)
}

/** "Speichern unter" (Android 8/9) mit dem Typ des Bilds: Eingabe (Name, MIME) */
private class CreateTypedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second.ifEmpty { "image/jpeg" }).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Klartext eines Anhangs als Strom schreiben: v1 ganz geladen, saga-file/v2 blockweise */
private suspend fun plainWriter(repo: SagaRepository, s: ChatSession, f: ChatFile, context: Context): (OutputStream) -> Unit {
    if (!f.isV2) {
        val plain = s.download(f)
        return { out -> out.write(plain) }
    }
    val sources = VideoSources(context.applicationContext, repo.api, repo.chat.crypto)
    return { out -> sources.decryptTo(f, out) {} }
}

private suspend fun saveImage(context: Context, repo: SagaRepository, s: ChatSession, f: ChatFile, target: Uri?, st: ImageSaveState) {
    if (st.busy) return
    st.busy = true
    st.result = null
    try {
        val write = plainWriter(repo, s, f, context)
        withContext(Dispatchers.IO) {
            when {
                target != null -> VideoSave.toUri(context, target, write)
                Build.VERSION.SDK_INT >= 29 -> ImageSave.toGallery(context, f.n, f.m, write)
                else -> throw java.io.IOException("target")
            }
        }
        st.result = true
        repo.notify(UiText.Res(R.string.media_saved))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("SagaMedia", "Speichern fehlgeschlagen: " + e.javaClass.simpleName)
        st.result = false
        repo.notify(UiText.Res(R.string.media_save_failed))
    } finally {
        st.busy = false
    }
}

/** Bild speichern: Android 10+ in die Galerie (Bilder/Saga), Android 8/9 "Speichern unter" - Betrachter und Langdruck-Menue */
@Composable
internal fun rememberImageSaver(repo: SagaRepository, s: ChatSession, st: ImageSaveState = remember { ImageSaveState() }): (ChatFile) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ChatFile?>(null) }
    val saveAs = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        val f = pending
        pending = null
        if (uri != null && f != null) scope.launch { saveImage(context, repo, s, f, uri, st) }
    }
    return { f ->
        if (Build.VERSION.SDK_INT >= 29) scope.launch { saveImage(context, repo, s, f, null, st) } else {
            pending = f
            saveAs.launch(f.n to f.m)
        }
    }
}

// ---------------------------------------------------------------- Vorschaubild im Zitat / in der Antwortleiste

/** Kleines Vorschaubild eines zitierten Bilds/Videos (nur das Vorschaubild, nie das Original) */
@Composable
internal fun QuoteThumb(f: ChatFile, s: ChatSession, size: Dp = 36.dp) {
    var img by remember(f.id) { mutableStateOf(s.cachedThumb(f)) }
    if (img == null) {
        LaunchedEffect(f.id) {
            img = try {
                s.thumb(f)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(SagaRadius.s)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

// ---------------------------------------------------------------- Bildbetrachter

/** Zoom und Verschiebung einer Seite; Masse (Feld, Bild) setzt die Ansicht, Gesten lesen sie aktuell */
@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)
    var boxW = 0f
    var boxH = 0f
    var imgW = 0
    var imgH = 0

    /** Zoom um den Punkt (fx, fy) relativ zur Feldmitte, dann verschieben; Grenzen: Bildraender bleiben im Feld */
    fun transform(zoom: Float, fx: Float, fy: Float, panX: Float, panY: Float) {
        val ns = ChatMedia.clampZoom(scale * zoom)
        val (cw, ch) = ChatMedia.fitted(imgW, imgH, boxW, boxH)
        val nx = ChatMedia.zoomAround(ox, fx, scale, ns) + panX
        val ny = ChatMedia.zoomAround(oy, fy, scale, ns) + panY
        scale = ns
        ox = ChatMedia.clampPan(nx, cw, boxW, ns)
        oy = ChatMedia.clampPan(ny, ch, boxH, ns)
    }

    fun reset() {
        scale = 1f
        ox = 0f
        oy = 0f
    }

    /** Doppeltippen: gezoomt -> ganz; sonst auf ZOOM_DOUBLE um die angetippte Stelle */
    fun doubleTap(fx: Float, fy: Float) {
        if (ChatMedia.zoomed(scale)) reset() else transform(ChatMedia.ZOOM_DOUBLE / scale, fx, fy, 0f, 0f)
    }
}

/** Zuletzt geladene Originale (wenige, Speicher) - Zurueckblaettern laedt nicht neu */
private class ViewerCache(private val max: Int = 2) {
    private val map = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > max
    }

    @Synchronized fun get(id: String): ImageBitmap? = map[id]

    @Synchronized fun put(id: String, b: ImageBitmap) {
        map[id] = b
    }
}

/** Original laden und begrenzt dekodieren (EXIF-Drehung); zu gross bzw. unlesbar -> null */
private suspend fun loadViewerImage(context: Context, repo: SagaRepository, s: ChatSession, f: ChatFile): ImageBitmap? {
    val data = if (f.isV2) {
        if (f.s <= 0 || f.s > ChatMedia.V2_VIEW_MAX) return null
        withContext(Dispatchers.IO) {
            val bos = ByteArrayOutputStream(f.s.toInt())
            VideoSources(context.applicationContext, repo.api, repo.chat.crypto).decryptTo(f, bos) {}
            bos.toByteArray()
        }
    } else {
        s.download(f)
    }
    return withContext(Dispatchers.Default) { ChatImages.decodeOriented(data, ChatMedia.VIEW_MAX_SIDE)?.asImageBitmap() }
}

/**
 * Bildbetrachter in der App (2.5 Medien 1): Vollbild, alle Bilder des Chats (Verlaufsreihenfolge) zum Blaettern, Start beim
 * angetippten; Zoomen mit zwei Fingern bzw. Doppeltippen, Verschieben in Grenzen. Gezoomt blaettert der Betrachter nicht
 * (Verschieben hat Vorrang). Oben: Schliessen, "n / m", Teilen, In Galerie speichern. Zurueck schliesst.
 */
@Composable
internal fun ImageViewer(repo: SagaRepository, s: ChatSession, items: List<ChatMedia.Item>, start: Int, onClose: () -> Unit) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.size - 1)) { items.size }
    val zooms = remember(items) { List(items.size) { ZoomState() } }
    val cache = remember { ViewerCache() }
    val saveSt = remember { ImageSaveState() }
    val save = rememberImageSaver(repo, s, saveSt)
    // nur die sichtbare Seite behaelt ihren Zoom
    LaunchedEffect(pager.settledPage) { zooms.forEachIndexed { i, z -> if (i != pager.settledPage) z.reset() } }
    val current = items[pager.currentPage.coerceIn(0, items.size - 1)].file
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                // gezoomt: kein Blaettern - Verschieben hat Vorrang
                userScrollEnabled = !ChatMedia.zoomed(zooms[pager.currentPage.coerceIn(0, items.size - 1)].scale),
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ViewerPage(repo, s, items[page].file, zooms[page], cache, context)
            }
            // Kopfzeile unter Statusleiste/Aussparung (decorFitsSystemWindows = false), wie im Video-Vollbild
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(4.dp).background(MediaScrim.copy(alpha = 0.4f), RoundedCornerShape(SagaRadius.md)).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { SagaIcon(SagaIcons.x, stringResource(R.string.action_close), tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            ChatMedia.counter(pager.currentPage, items.size), color = Color.White, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Text(current.n, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { scope.launch { openFile(context, s, current, share = true) { repo.notify(it) } } }) {
                        SagaIcon(SagaIcons.share, stringResource(R.string.publish_share), tint = Color.White)
                    }
                    if (saveSt.busy) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp) }
                    } else {
                        IconButton(onClick = { save(current) }) {
                            SagaIcon(SagaIcons.download, stringResource(R.string.media_save_gallery), tint = Color.White)
                        }
                    }
                }
                saveSt.result?.let { ok ->
                    LaunchedEffect(ok) {
                        delay(3000)
                        saveSt.result = null
                    }
                    Text(
                        stringResource(if (ok) R.string.media_saved else R.string.media_save_failed), color = Color.White,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)
                            .background(MediaScrim.copy(alpha = 0.8f), RoundedCornerShape(SagaRadius.s)).padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/** Eine Seite des Betrachters: Vorschaubild sofort, Original nachgeladen; Gesten fuer Zoom/Verschieben/Doppeltippen */
@Composable
private fun ViewerPage(repo: SagaRepository, s: ChatSession, f: ChatFile, zs: ZoomState, cache: ViewerCache, context: Context) {
    val scope = rememberCoroutineScope()
    var thumb by remember(f.id) { mutableStateOf(s.cachedThumb(f)) }
    var full by remember(f.id) { mutableStateOf(cache.get(f.id)) }
    var failed by remember(f.id) { mutableStateOf(false) }
    LaunchedEffect(f.id) {
        if (full != null) return@LaunchedEffect
        if (thumb == null) thumb = try {
            s.thumb(f)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val b = try {
            loadViewerImage(context, repo, s, f)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (b != null) cache.put(f.id, b)
        full = b
        failed = b == null
    }
    val img = full ?: thumb
    zs.imgW = img?.width ?: f.w ?: 0
    zs.imgH = img?.height ?: f.h ?: 0
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { zs.boxW = it.width.toFloat(); zs.boxH = it.height.toFloat() }
            // zwei Finger zoomen; gezoomt verschiebt auch ein Finger (dann verbraucht - der Pager blaettert nicht)
            .pointerInput(zs) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed > 1 || ChatMedia.zoomed(zs.scale)) {
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val c = ev.calculateCentroid(useCurrent = true)
                            val fx = if (c == Offset.Unspecified) 0f else c.x - zs.boxW / 2f
                            val fy = if (c == Offset.Unspecified) 0f else c.y - zs.boxH / 2f
                            if (zoom != 1f || pan != Offset.Zero) zs.transform(zoom, fx, fy, pan.x, pan.y)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            .pointerInput(zs) {
                detectTapGestures(onDoubleTap = { p -> zs.doubleTap(p.x - zs.boxW / 2f, p.y - zs.boxH / 2f) })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) {
            Image(
                img, f.n, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zs.scale
                    scaleY = zs.scale
                    translationX = zs.ox
                    translationY = zs.oy
                },
            )
        }
        if (full == null && !failed) {
            CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 2.dp)
        }
        if (failed) {
            Column(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
                    .background(MediaScrim.copy(alpha = 0.8f), RoundedCornerShape(SagaRadius.s)).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.media_load_failed), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                // Ausweg: mit einer anderen App oeffnen (wie bisher)
                TextButton(onClick = { scope.launch { openFile(context, s, f) { repo.notify(it) } } }) {
                    Text(stringResource(R.string.media_open_with), color = Color.White)
                }
            }
        }
    }
}
