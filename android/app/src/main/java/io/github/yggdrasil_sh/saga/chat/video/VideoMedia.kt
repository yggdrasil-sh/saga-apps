package io.github.yggdrasil_sh.saga.chat.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.graphics.scale
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Metadaten eines Videos (MediaMetadataRetriever): Dauer, Abmessungen, Drehung, Bitrate */
class VideoInfo(val durMs: Long, val w: Int, val h: Int, val rot: Int, val bitrate: Long?)

/** Poster: JPEG, laengste Seite <= 320 px, <= 30 KB */
class Poster(val jpeg: ByteArray, val w: Int, val h: Int)

object VideoMedia {
    private fun MediaMetadataRetriever.int(k: Int): Int? = extractMetadata(k)?.trim()?.toIntOrNull()
    private fun MediaMetadataRetriever.long(k: Int): Long? = extractMetadata(k)?.trim()?.toLongOrNull()

    /** Metadaten und Poster in einem Durchgang (nur im Hintergrund aufrufen) */
    fun probe(context: Context, uri: Uri, withPoster: Boolean = true): Pair<VideoInfo, Poster?>? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.long(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0L
            val w = r.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0
            val h = r.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0
            val rot = VideoLogic.rotation(r.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
            val br = r.long(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val info = VideoInfo(dur, w, h, rot, br)
            val poster = if (withPoster) try {
                poster(r, info)
            } catch (_: Throwable) {
                null
            } else null
            return info to poster
        } catch (_: Exception) {
            return null
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun poster(r: MediaMetadataRetriever, info: VideoInfo): Poster? {
        val t = VideoLogic.posterTimeUs(info.durMs)
        val (sw, sh) = VideoLogic.shown(info.w, info.h, info.rot)
        val f = if (sw > 0 && sh > 0) minOf(1.0, VideoLogic.POSTER_MAX.toDouble() / maxOf(sw, sh)) else 1.0
        val tw = maxOf(1, (sw * f).toInt())
        val th = maxOf(1, (sh * f).toInt())
        // getScaledFrameAtTime dekodiert gleich klein (Android 8.1+); das Bild ist bereits gedreht
        val frame: Bitmap = (if (Build.VERSION.SDK_INT >= 27 && sw > 0) {
            r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
        } else {
            r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }) ?: return null
        var bmp = frame
        try {
            if (maxOf(bmp.width, bmp.height) > VideoLogic.POSTER_MAX) {
                val g = VideoLogic.POSTER_MAX.toDouble() / maxOf(bmp.width, bmp.height)
                bmp = frame.scale(maxOf(1, (frame.width * g).toInt()), maxOf(1, (frame.height * g).toInt()))
            }
            // Qualitaet senken, bis <= 30 KB; notfalls kleiner
            var cur = bmp
            while (true) {
                for (q in intArrayOf(70, 60, 50, 40, 30)) {
                    val out = ByteArrayOutputStream()
                    cur.compress(Bitmap.CompressFormat.JPEG, q, out)
                    if (out.size() <= VideoLogic.POSTER_BYTES) return Poster(out.toByteArray(), cur.width, cur.height)
                }
                if (cur.width < 64) return null
                val next = cur.scale(cur.width * 3 / 4, maxOf(1, cur.height * 3 / 4))
                if (cur !== bmp) cur.recycle()
                cur = next
            }
        } finally {
            if (bmp !== frame) bmp.recycle()
            frame.recycle()
        }
    }

    /** hoechstens ein Export gleichzeitig (Akku, Waerme) */
    private val exportLock = Mutex()

    /** Geraet zu heiss? (ab "schwer" - dann mit dem Export warten) */
    private fun hot(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }

    /**
     * "optimiert 720p" mit Media3 Transformer (Hardware-Encoder, H.264, ~2,5 Mbit/s, AAC): Ergebnis nach out.
     * onProgress 0..1. Abbruch der Coroutine bricht den Export ab. Fehler -> Ausnahme (Aufrufer sendet dann das Original).
     */
    @OptIn(UnstableApi::class)
    suspend fun optimize(context: Context, uri: Uri, target: Pair<Int, Int>, out: File, onProgress: (Float) -> Unit) = exportLock.withLock {
        while (hot(context)) delay(10_000)
        out.parentFile?.mkdirs()
        out.delete()
        val done = CompletableDeferred<Unit>()
        coroutineScope {
            val transformer = withContext(Dispatchers.Main) {
                val enc = DefaultEncoderFactory.Builder(context.applicationContext)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VideoLogic.OPT_BITRATE).build())
                    .build()
                val t = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(enc)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.complete(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            done.completeExceptionally(exportException)
                        }
                    })
                    .build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(target.first, target.second, Presentation.LAYOUT_SCALE_TO_FIT))))
                    .build()
                t.start(item, out.absolutePath)
                t
            }
            val poll = launch(Dispatchers.Main) {
                val ph = ProgressHolder()
                while (true) {
                    if (transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(ph.progress / 100f)
                    delay(500)
                }
            }
            try {
                done.await()
            } catch (e: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { transformer.cancel() }
                out.delete()
                throw e
            } catch (e: Exception) {
                out.delete()
                throw e
            } finally {
                poll.cancel()
            }
        }
    }
}
