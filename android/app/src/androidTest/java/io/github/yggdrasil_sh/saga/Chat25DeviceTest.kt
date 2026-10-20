package io.github.yggdrasil_sh.saga

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.chat.voice.OggOpus
import io.github.yggdrasil_sh.saga.chat.voice.PcmSource
import io.github.yggdrasil_sh.saga.chat.voice.VoiceRecording
import io.github.yggdrasil_sh.saga.ui.chat.X3PreviewContent
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Saga 2.5 Sprachnachrichten auf dem Geraet (nur eigenes Test-AVD, nie mit Mikrofon): synthetische Quelle (Sinus mit
 * Lautstaerke-Huellkurve) -> MediaCodec-Opus -> eigener Ogg-Muxer; danach mit dem Ogg-Parser des Systems
 * (MediaExtractor) und mit ExoPlayer (wie in der App) gelesen. Dazu Bildschirmfotos der Chat-Grundlagen (hell/dunkel).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class Chat25DeviceTest {
    /** Sinus 440 Hz, Lautstaerke steigt und faellt (fuer eine sichtbare Wellenform) - nie das Mikrofon */
    private class SinePcm(seconds: Double) : PcmSource {
        override val rate = 48_000
        private val total = (seconds * rate).toLong()
        private var at = 0L
        override fun start() {}
        override fun read(buf: ShortArray, off: Int, n: Int): Int {
            if (at >= total) return -1
            val k = minOf(n.toLong(), total - at).toInt()
            for (i in 0 until k) {
                val t = (at + i).toDouble() / rate
                val env = 0.15 + 0.8 * kotlin.math.abs(sin(t * 1.3))
                buf[off + i] = (sin(2 * PI * 440 * t) * env * 20_000).toInt().toShort()
            }
            at += k
            return k
        }
        override fun close() {}
    }

    private fun record(seconds: Double): io.github.yggdrasil_sh.saga.chat.voice.VoiceResult {
        val r = VoiceRecording(SinePcm(seconds), 24, 900.0)
        r.start()
        val t0 = System.currentTimeMillis()
        while (r.secs < seconds - 0.001 && System.currentTimeMillis() - t0 < 30_000) Thread.sleep(20)
        return r.stop()
    }

    @Test
    fun opusOggFromSyntheticSource() {
        val res = record(5.0)
        val p = OggOpus.demux(res.ogg)                     // Pruefsummen, Folge, Kopf
        assertEquals(1, p.channels)
        assertEquals(48_000, p.rate)
        assertTrue("pre-skip vom Encoder ${p.preskip}", p.preskip in 1..2000)
        assertTrue(p.eos)
        // 20-ms-Rahmen, Granule = pre-skip + Samples, Dauer passt
        assertTrue("Rahmen", p.packets.all { OggOpus.samples(it) == 960 })
        assertEquals(p.packets.sumOf { OggOpus.samples(it).toLong() }, p.lastGranule)
        assertEquals(5.0, res.dur, 0.05)
        assertEquals(res.dur, p.duration, 1e-6)
        // 24 kbit/s: etwa 3 KB je Sekunde (VBR, Ogg-Kopf)
        val kbps = res.ogg.size * 8 / 1000.0 / res.dur
        assertTrue("Bitrate $kbps", kbps in 12.0..40.0)
        assertTrue("Wellenform", res.wf.count { (it.toInt() and 0xff) > 30 } > 40)
        // Ogg-Parser des Systems: Opus, Dauer
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(ctx.cacheDir, "c25.ogg").apply { writeBytes(res.ogg) }
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        val fmt = ex.getTrackFormat(0)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS, fmt.getString(MediaFormat.KEY_MIME))
        val durUs = fmt.getLong(MediaFormat.KEY_DURATION)
        ex.release()
        assertEquals(5.0, durUs / 1e6, 0.06)
        // ExoPlayer (wie VoicePlayer): Ogg-Extractor + Opus-Decoder, Dauer
        val latch = CountDownLatch(1)
        var dur = C.TIME_UNSET
        var err: Exception? = null
        Handler(Looper.getMainLooper()).post {
            val pl = ExoPlayer.Builder(ctx).build()
            pl.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        dur = pl.duration
                        pl.release()
                        latch.countDown()
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    err = error
                    pl.release()
                    latch.countDown()
                }
            })
            val mi = MediaItem.Builder().setUri("saga-voice://test").setMimeType(MimeTypes.AUDIO_OGG).build()
            pl.setMediaSource(ProgressiveMediaSource.Factory { ByteArrayDataSource(res.ogg) }.createMediaSource(mi))
            pl.volume = 0f
            pl.prepare()
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        err?.let { throw it }
        assertEquals(5000.0, dur.toDouble(), 60.0)
        f.delete()
    }

    @Test
    fun underOneSecondIsShort() {
        val res = record(0.6)
        assertTrue(res.dur < 1.0)
        assertTrue(!io.github.yggdrasil_sh.saga.chat.voice.VoiceLogic.keep(res.dur))
    }

    @Test
    fun screenshots() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        for (dark in listOf(false, true)) {
            ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
                sc.onActivity { a -> a.setContent { SagaTheme(dark = dark) { X3PreviewContent() } } }
                Thread.sleep(1500)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                sc.onActivity { a ->
                    val v = a.window.decorView.findViewById<android.view.View>(android.R.id.content)
                    val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                    v.draw(android.graphics.Canvas(b))
                    File(dir, if (dark) "chat25-dunkel.png" else "chat25-hell.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
        assertTrue(File(dir, "chat25-hell.png").length() > 1000)
    }
}
