package io.github.yggdrasil_sh.saga.ui.chat

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatImages
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.video.VideoLogic
import io.github.yggdrasil_sh.saga.chat.video.VideoSave
import io.github.yggdrasil_sh.saga.chat.video.VideoSources
import io.github.yggdrasil_sh.saga.chat.video.VideoUploadWorker
import io.github.yggdrasil_sh.saga.chat.video.Videos
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Ein zu sendendes Video im Eingabebereich ("optimiert 720p" Standard, Original waehlbar) */
@Stable
class PendingVideo(val uri: String, val name: String, val mime: String, val size: Long?) {
    var optimize by mutableStateOf(true)
}

/** gemeinsamer Player der offenen Unterhaltung (nie ein Player je Zelle) */
val LocalVideoHost = compositionLocalOf<VideoPlayerHost?> { null }

/**
 * Ein ExoPlayer fuer die ganze Unterhaltung (docs/APP-API.md: "ein gemeinsamer Player"). Er entsteht erst beim Antippen
 * und wird freigegeben, sobald das Video aus dem Bild gescrollt wird, der Vollbild-Dialog schliesst oder die
 * Unterhaltung verlassen wird. v2 streamt blockweise ueber SagaFileDataSource, v1 (bis 10 MB) aus dem Speicher.
 */
@Stable
@OptIn(UnstableApi::class)
class VideoPlayerHost(private val context: Context, private val repo: SagaRepository, private val scope: CoroutineScope) {
    var active by mutableStateOf<ChatFile?>(null)
        private set
    var fullscreen by mutableStateOf(false)
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    var saving by mutableStateOf<Float?>(null)
        private set
    /** Ergebnis des letzten Speicherns fuer die Anzeige im Vollbild (die Snackbar liegt hinter dem Dialog) */
    var saveResult by mutableStateOf<Boolean?>(null)
    private var session: ChatSession? = null

    val sources by lazy { VideoSources(context.applicationContext, repo.api, repo.chat.crypto) }

    private fun item(f: ChatFile): MediaItem = MediaItem.Builder()
        .setUri("saga-video://" + Uri.encode(f.id))
        .setMimeType(if (f.m == "video/mp4" || f.m.isEmpty()) MimeTypes.VIDEO_MP4 else f.m)
        .build()

    fun play(s: ChatSession, f: ChatFile, full: Boolean = false) {
        release()
        session = s
        active = f
        fullscreen = full
        failed = false
        // Puffer 15-30 s (Konzept 2.6): nur so viel im Voraus laden
        val p = ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 30_000, 1_000, 2_000).build())
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        })
        player = p
        if (f.isV2) {
            p.setMediaSource(ProgressiveMediaSource.Factory(sources.factory(f)).createMediaSource(item(f)))
            p.prepare()
            p.play()
        } else {
            scope.launch {
                try {
                    val plain = s.download(f)
                    if (player === p) {
                        p.setMediaSource(ProgressiveMediaSource.Factory(sources.factoryV1(plain)).createMediaSource(item(f)))
                        p.prepare()
                        p.play()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (player === p) failed = true
                }
            }
        }
    }

    fun pause() {
        player?.pause()
    }

    fun release() {
        player?.release()
        player = null
        active = null
        fullscreen = false
    }

    /** Zelle aus dem Bild gescrollt: Player freigeben (ausser im Vollbild) */
    fun releaseIf(id: String) {
        if (active?.id == id && !fullscreen) release()
    }

    /** Speichern als Strom: Android 10+ in die Galerie (Filme/Saga), sonst in das gewaehlte Ziel */
    suspend fun save(s: ChatSession, f: ChatFile, target: Uri?) {
        if (saving != null) return
        saving = 0f
        saveResult = null
        try {
            val plain = if (f.isV2) null else s.download(f)
            val write: (OutputStream) -> Unit = { out ->
                if (plain != null) out.write(plain) else sources.decryptTo(f, out) { p -> scope.launch { saving = p } }
            }
            withContext(Dispatchers.IO) {
                when {
                    target != null -> VideoSave.toUri(context, target, write)
                    Build.VERSION.SDK_INT >= 29 -> VideoSave.toGallery(context, f.n, f.m, write)
                    else -> throw java.io.IOException("target")
                }
            }
            saveResult = true
            repo.notify(UiText.Res(R.string.video_saved))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SagaVideo", "Speichern fehlgeschlagen: " + e.javaClass.simpleName)
            saveResult = false
            repo.notify(UiText.Res(R.string.video_save_failed))
        } finally {
            saving = null
        }
    }
}

/** Anzeigegroesse (dp) aus w/h/Drehung bzw. Poster */
private fun videoBox(f: ChatFile): Pair<Int, Int> {
    val w = f.w
    val h = f.h
    val shown = if (w != null && h != null) VideoLogic.shown(w, h, f.rot ?: 0) else f.th?.let { it.w to it.h }
    return shown?.let { ChatImages.displaySize(it.first, it.second) } ?: (240 to 135)
}

/**
 * Video im Verlauf: nur das Poster (lazy, wie Vorschaubilder) mit Dauer und Abspiel-Symbol. Antippen spielt im
 * gemeinsamen Player an Ort und Stelle; Vollbild ueber den Knopf. Wegscrollen gibt den Player frei.
 */
@Composable
fun VideoAttachment(f: ChatFile, s: ChatSession) {
    val host = LocalVideoHost.current ?: return
    val dims = videoBox(f)
    var img by remember(f.id) { mutableStateOf<ImageBitmap?>(if (f.th != null) s.cachedThumb(f) else null) }
    if (img == null && f.th != null) {
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
    val playing = host.active?.id == f.id && !host.fullscreen
    DisposableEffect(f.id) { onDispose { host.releaseIf(f.id) } }
    val desc = stringResource(R.string.video_play, f.n)
    Box(
        Modifier.padding(vertical = 4.dp).size(dims.first.dp, dims.second.dp).clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .clickable(enabled = !playing) { host.play(s, f) }
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        val p = host.player
        if (playing && p != null && !host.failed) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = true } },
                update = { it.player = p },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = { host.fullscreen = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp).background(Color(0x66000000), CircleShape),
            ) { Icon(painterResource(R.drawable.ic_fullscreen), stringResource(R.string.video_fullscreen), tint = Color.White) }
        } else {
            img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (playing && host.failed) {
                Text(stringResource(R.string.video_failed), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
            } else {
                Box(Modifier.size(48.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            if (f.dur != null) {
                Text(
                    VideoLogic.durText(f.dur), color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Vollbild mit Speichern (Galerie bzw. Android 8/9 "Speichern unter") */
@OptIn(UnstableApi::class)
@Composable
fun VideoFullscreen(host: VideoPlayerHost, s: ChatSession) {
    val f = host.active ?: return
    val p = host.player ?: return
    val scope = rememberCoroutineScope()
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.m.ifEmpty { "video/mp4" })) { uri ->
        if (uri != null) scope.launch { host.save(s, f, uri) }
    }
    Dialog(onDismissRequest = { host.release() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Statusleiste/Aussparung/Navigationsleiste liegen ueber dem Dialog (decorFitsSystemWindows = false):
        // Kopfzeile und Bedienelemente mit safeDrawing einruecken, sonst sind X/Speichern nicht antippbar (TT 2.4 P-01)
        Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = true } },
                update = { it.player = p },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            Row(Modifier.fillMaxWidth().background(Color(0x66000000), RoundedCornerShape(12.dp)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { host.release() }) { Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = Color.White) }
                Text(f.n, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val sv = host.saving
                if (sv != null) {
                    CircularProgressIndicator(progress = { sv }, color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    IconButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 29) scope.launch { host.save(s, f, null) } else saveAs.launch(f.n)
                    }) { Icon(painterResource(R.drawable.ic_save_alt), stringResource(R.string.video_save), tint = Color.White) }
                }
            }
            if (host.failed) {
                Text(stringResource(R.string.video_failed), color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            host.saveResult?.let { ok ->
                LaunchedEffect(ok) {
                    kotlinx.coroutines.delay(3000)
                    host.saveResult = null
                }
                Text(
                    stringResource(if (ok) R.string.video_saved else R.string.video_save_failed), color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Gewaehlte Videos im Eingabebereich: entfernen, "optimiert 720p" / Original umschalten */
@Composable
fun PendingVideoChips(videos: MutableList<PendingVideo>) {
    if (videos.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        videos.forEach { v ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InputChip(
                    selected = false, onClick = { videos.remove(v) },
                    label = { Text("🎬 " + v.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                    trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.action_delete), Modifier.size(16.dp)) },
                )
                FilterChip(selected = v.optimize, onClick = { v.optimize = true }, label = { Text(stringResource(R.string.video_opt_720)) })
                FilterChip(selected = !v.optimize, onClick = { v.optimize = false }, label = { Text(stringResource(R.string.video_opt_original)) })
            }
        }
    }
}

/**
 * Laufende Video-Uploads dieses Chats (WorkManager): Name, Fortschritt, Abbrechen. Fertig -> Verlauf nachladen,
 * Fehler -> Hinweis (je Auftrag einmal).
 */
@Composable
fun VideoUploadsBar(repo: SagaRepository, chatId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val wm = remember { WorkManager.getInstance(context.applicationContext) }
    val infos by remember(chatId) { wm.getWorkInfosByTagFlow(Videos.tagChat(chatId)) }.collectAsState(initial = emptyList())
    LaunchedEffect(chatId) { try { Videos.sweep(context.applicationContext, repo) } catch (e: CancellationException) { throw e } catch (_: Exception) {} }
    LaunchedEffect(infos) {
        for (w in infos) {
            if (!w.state.isFinished || !Videos.reported.add(w.id)) continue
            if (!Videos.seenRunning.contains(w.id)) continue          // schon vor dem Oeffnen fertig: nicht erneut melden
            when (w.state) {
                WorkInfo.State.SUCCEEDED -> onDone()
                WorkInfo.State.FAILED -> {
                    val res = w.outputData.getInt(VideoUploadWorker.KEY_ERROR, R.string.video_send_failed)
                    val arg = w.outputData.getLong(VideoUploadWorker.KEY_ERROR_ARG, -1L)
                    repo.notify(if (arg >= 0) UiText.Res(res, arg) else UiText.Res(res))
                }
                else -> {}
            }
        }
        infos.filter { !it.state.isFinished }.forEach { Videos.seenRunning.add(it.id) }
    }
    val running = infos.filter { !it.state.isFinished }
    if (running.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        running.forEach { w ->
            val p = w.progress.getFloat(VideoUploadWorker.KEY_PROGRESS, 0f)
            val prep = w.progress.getInt(VideoUploadWorker.KEY_PHASE, VideoUploadWorker.PHASE_PREPARE) == VideoUploadWorker.PHASE_PREPARE
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(
                            stringResource(
                                when {
                                    // lief schon und wartet jetzt (Netz weg/Wiederholung): nicht "wird vorbereitet" (TT 2.4 P-21)
                                    w.state != WorkInfo.State.RUNNING && Videos.seenRunning.contains(w.id) &&
                                        w.progress.getInt(VideoUploadWorker.KEY_PHASE, -1) != VideoUploadWorker.PHASE_PREPARE -> R.string.video_waiting_network
                                    w.state == WorkInfo.State.ENQUEUED && w.runAttemptCount > 0 -> R.string.video_waiting_network
                                    prep -> R.string.video_preparing
                                    else -> R.string.video_uploading
                                },
                            ) + " · " + Videos.nameOf(w),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (w.state == WorkInfo.State.RUNNING && p > 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    TextButton(onClick = { Videos.cancel(context.applicationContext, w.id) }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}
