package io.github.yggdrasil_sh.saga.chat.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Aufnahme von Sprachnachrichten (Saga 2.5, Konzept B3): PCM 48 kHz mono -> MediaCodec-Opus (24 kbit/s, 20-ms-Rahmen)
 * -> eigener Ogg-Muxer (OggOpus, wie im Web). Bewusst nicht MediaRecorder OGG/OPUS: dessen OggWriter (AOSP
 * libstagefright) schreibt pre-skip 0 in OpusHead (RFC 7845 verlangt die Verzoegerung des Encoders) und bildet die Granule
 * aus Zeitstempeln statt aus den Samples der Pakete; ausserdem gibt es dort kein PCM fuer die Wellenform (nur
 * getMaxAmplitude). Hier zaehlen die Samples der Pakete (TOC), die Dauer ist exakt, die Wellenform ist dieselbe wie im
 * Browser (RMS je 100 ms).
 *
 * Die Quelle ist austauschbar (PcmSource): Mikrofon in der App, synthetische bzw. Datei-Quelle im Test (nie das Mikrofon).
 */
interface PcmSource : Closeable {
    val rate: Int

    fun start()

    /** bis zu n Samples nach buf ab off (blockiert bei Mikrofon); -1 = Ende */
    fun read(buf: ShortArray, off: Int, n: Int): Int
}

/** Mikrofon (Echo-/Rauschunterdrueckung und AGC, wenn das Geraet sie hat) - nur mit RECORD_AUDIO */
class MicSource : PcmSource {
    override val rate = OggOpus.RATE
    private var rec: AudioRecord? = null
    private val fx = ArrayList<android.media.audiofx.AudioEffect>()

    @SuppressLint("MissingPermission")
    override fun start() {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("AudioRecord")
        }
        val sid = r.audioSessionId
        try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sid)?.also { it.enabled = true; fx.add(it) }
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sid)?.also { it.enabled = true; fx.add(it) }
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sid)?.also { it.enabled = true; fx.add(it) }
        } catch (_: Exception) {
        }
        r.startRecording()
        rec = r
    }

    override fun read(buf: ShortArray, off: Int, n: Int): Int = rec?.read(buf, off, n)?.takeIf { it >= 0 } ?: -1

    override fun close() {
        fx.forEach { try { it.release() } catch (_: Exception) {} }
        fx.clear()
        rec?.let { r ->
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        rec = null
    }
}

/** Fertige Aufnahme: Ogg-Datei, Dauer (s, aus den Samples), Wellenform (64 Werte) */
class VoiceResult(val ogg: ByteArray, val dur: Double, val wf: ByteArray, val packets: Int, val preskip: Int)

/** MediaCodec-Opus-Encoder (ab Android 10): PCM rein, Opus-Pakete raus */
@RequiresApi(29)
class OpusEncoder(kbps: Int) : Closeable {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    val packets = ArrayList<ByteArray>()
    var preskip: Int? = null
        private set
    private val info = MediaCodec.BufferInfo()
    private var inSamples = 0L
    private var eosOut = false

    init {
        fun fmt(vbr: Boolean) = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OggOpus.RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 960 * 2 * 10)
            if (vbr) setInteger(MediaFormat.KEY_BITRATE_MODE, android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        try {
            codec.configure(fmt(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Exception) {
            codec.reset()
            codec.configure(fmt(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        codec.start()
    }

    /** n Samples einspeisen (blockiert kurz, bis der Encoder Platz hat) */
    fun feed(pcm: ShortArray, n: Int) {
        var off = 0
        while (off < n) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i < 0) {
                drain(false)
                continue
            }
            val b = codec.getInputBuffer(i)!!
            b.clear()
            val k = minOf(n - off, b.remaining() / 2)
            for (j in 0 until k) b.putShort(pcm[off + j])
            codec.queueInputBuffer(i, 0, k * 2, inSamples * 1_000_000 / OggOpus.RATE, 0)
            inSamples += k
            off += k
            drain(false)
        }
    }

    /** Ende melden und alles abholen */
    fun finish() {
        while (true) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                codec.queueInputBuffer(i, 0, 0, inSamples * 1_000_000 / OggOpus.RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(false)
        }
        var guard = 0
        while (!eosOut && guard++ < 2000) drain(true)
    }

    private fun drain(wait: Boolean) {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (wait) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    if (preskip == null) f.getByteBuffer("csd-0")?.let { bb -> preskip = OggOpus.preskipOf(ByteArray(bb.remaining()).also { bb.duplicate().get(it) }) }
                }
                o >= 0 -> {
                    val b = codec.getOutputBuffer(o)!!
                    b.position(info.offset)
                    b.limit(info.offset + info.size)
                    val data = ByteArray(info.size).also { b.get(it) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        if (preskip == null) preskip = OggOpus.preskipOf(data)
                    } else if (data.isNotEmpty()) {
                        packets.add(data)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        eosOut = true
                        return
                    }
                }
                else -> return
            }
        }
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }
}

/**
 * Laufende Aufnahme (eigener Thread): liest je 100 ms PCM, merkt den Pegel (RMS), speist den Encoder. stop() -> Ergebnis;
 * cancel() verwirft. onTick(Sekunden, Pegel 0..1) kommt aus dem Aufnahme-Thread.
 */
@RequiresApi(29)
class VoiceRecording(
    private val source: PcmSource,
    private val kbps: Int = 24,
    private val maxS: Double = 900.0,
    private val onTick: (Double, Float) -> Unit = { _, _ -> },
    private val onLimit: () -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val levels = ArrayList<Float>()
    private var samples = 0L
    private var thread: Thread? = null
    private var error: Throwable? = null
    private var enc: OpusEncoder? = null

    val secs: Double get() = samples / OggOpus.RATE.toDouble()

    fun start() {
        val e = OpusEncoder(kbps)
        enc = e
        try {
            source.start()
        } catch (x: Exception) {
            e.close()
            throw x
        }
        running.set(true)
        thread = Thread({ loop(e) }, "saga-voice-rec").apply { start() }
    }

    private fun loop(e: OpusEncoder) {
        val win = OggOpus.RATE * VoiceLogic.LEVEL_MS / 1000
        val buf = ShortArray(win)
        try {
            while (running.get()) {
                var got = 0
                var idle = 0
                while (got < win && running.get()) {
                    val n = source.read(buf, got, win - got)
                    if (n < 0) {
                        running.set(false)
                        break
                    }
                    if (n == 0 && ++idle > 200) throw IllegalStateException("no audio")
                    got += n
                }
                if (got == 0) break
                // Pegel und Encoder (ein Durchgang je 100 ms)
                levels.add(VoiceLogic.rms(buf, 0, got))
                e.feed(buf, got)
                samples += got
                onTick(secs, minOf(1f, levels.last() * 4))
                if (secs >= maxS) {
                    running.set(false)
                    onLimit()
                }
            }
        } catch (t: Throwable) {
            error = t
            running.set(false)
        }
    }

    /** Aufnahme beenden -> Ergebnis (blockiert kurz, im Hintergrund aufrufen) */
    fun stop(): VoiceResult {
        running.set(false)
        thread?.join(3_000)
        source.close()
        val e = enc ?: throw IllegalStateException("not started")
        try {
            error?.let { throw it }
            e.finish()
            val pre = e.preskip ?: 312
            val pk = e.packets.toList()
            val n = pk.sumOf { OggOpus.samples(it).toLong() }
            val dur = minOf(maxS, maxOf(0L, n - pre) / OggOpus.RATE.toDouble())
            return VoiceResult(OggOpus.mux(pk, pre), dur, VoiceLogic.waveform(levels.toList()), pk.size, pre)
        } finally {
            e.close()
        }
    }

    fun cancel() {
        cancelled.set(true)
        running.set(false)
        thread?.join(3_000)
        source.close()
        enc?.close()
        enc = null
    }
}
