package io.github.yggdrasil_sh.saga.call

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import androidx.annotation.RequiresApi
import org.webrtc.audio.JavaAudioDeviceModule
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Systemton beim Bildschirm-Teilen (E3): Android 10+ liefert den Ton anderer Apps ueber AudioPlaybackCapture (gleiche
 * MediaProjection wie das Bild). Er wird in die VORHANDENE Tonspur gemischt - keine dritte m-Zeile. Dazu ersetzt der
 * Mischer fuer die Dauer des Teilens die Mikrofon-Aufnahme der libwebrtc-Tonaufnahme (WebRtcAudioRecord.audioRecord,
 * je Durchlauf neu gelesen) durch eine Huelle, die das Mikrofon liest und den Systemton dazumischt. Stumm schaltet dann
 * nur das Mikrofon. Geht etwas nicht (aeltere Android-Version, andere libwebrtc, keine Erlaubnis): ohne Ton teilen.
 * Erfasst werden nur Apps, die das erlauben (Medien, Spiele); der Anrufton selbst (Sprache) nie - kein Echo.
 */
class ScreenAudioMixer {
    @Volatile var micMuted = false
    @Volatile private var sys: AudioRecord? = null
    private var input: Any? = null
    private var field: Field? = null
    private var original: AudioRecord? = null
    private var wrapper: AudioRecord? = null
    private var lock: Any? = null

    /** Sperre der libwebrtc-Aufnahme (M150: audioRecordStateLock); aeltere Fassungen ohne: eigenes Objekt */
    private fun lockOf(inp: Any): Any = try {
        inp.javaClass.getDeclaredField("audioRecordStateLock").apply { isAccessible = true }.get(inp) ?: this
    } catch (_: Throwable) {
        this
    }
    private var micArr = ShortArray(0)
    private var sysArr = ShortArray(0)

    val active: Boolean get() = wrapper != null

    /** Mischen beginnen; true = Systemton laeuft mit */
    @SuppressLint("MissingPermission")                     // RECORD_AUDIO hat der Anruf (Aufrufer prueft)
    @RequiresApi(29)
    fun start(adm: JavaAudioDeviceModule, projection: MediaProjection): Boolean {
        if (wrapper != null) return true
        var rec: AudioRecord? = null                        // Systemton-Aufnahme: bei Fehler wieder freigeben (nie verwaist weiter aufnehmen)
        return try {
            val inp = JavaAudioDeviceModule::class.java.getField("audioInput").get(adm) ?: return false
            val f = inp.javaClass.getDeclaredField("audioRecord").apply { isAccessible = true }
            val lk = lockOf(inp)
            val mic = synchronized(lk) { f.get(inp) as? AudioRecord } ?: return false
            // nur waehrend laufender Aufnahme (M150 startet sie verzoegert im Aufnahme-Thread)
            if (mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) return false
            if (mic is MixingAudioRecord) return false
            val mask = if (mic.channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val min = AudioRecord.getMinBufferSize(mic.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            rec = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(mic.sampleRate).setChannelMask(mask).build())
                .setBufferSizeInBytes(min * 2)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            rec.startRecording()
            val w = MixingAudioRecord(mic, this)
            // Tausch unter der Sperre der Aufnahme (M150: audioRecordStateLock), nur wenn dort noch dasselbe Mikrofon steht
            val swapped = synchronized(lk) {
                if (f.get(inp) === mic) {
                    f.set(inp, w)
                    true
                } else false
            }
            if (!swapped) {
                rec.stop()
                rec.release()
                return false
            }
            sys = rec
            input = inp
            field = f
            lock = lk
            original = mic
            wrapper = w
            true
        } catch (e: Throwable) {
            Log.w("SagaCall", "screen audio: ${e.message}")
            if (rec != null && rec !== sys) {
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
                rec.release()
            }
            stop()
            false
        }
    }

    /** Mischen beenden: Mikrofon-Aufnahme zurueck, Systemton-Aufnahme freigeben */
    fun stop() {
        try {
            val f = field
            val inp = input
            if (f != null && inp != null) synchronized(lock ?: this) { if (f.get(inp) === wrapper) f.set(inp, original) }
        } catch (_: Throwable) {
        }
        lock = null
        val s = sys
        sys = null
        wrapper = null
        original = null
        field = null
        input = null
        micMuted = false
        try {
            s?.stop()
        } catch (_: Exception) {
        }
        s?.release()
    }

    /** Aufnahme-Thread von libwebrtc: n Bytes PCM16 im Puffer (ab Index 0) */
    internal fun mix(buf: ByteBuffer, n: Int) {
        val shorts = n / 2
        if (shorts <= 0) return
        val s = sys
        if (s == null && !micMuted) return
        if (micArr.size < shorts) {
            micArr = ShortArray(shorts)
            sysArr = ShortArray(shorts)
        }
        val got = try {
            s?.read(sysArr, 0, shorts, AudioRecord.READ_NON_BLOCKING) ?: 0
        } catch (_: Exception) {
            0
        }
        val d = buf.duplicate()
        d.clear()
        val sb = d.order(ByteOrder.nativeOrder()).asShortBuffer()
        sb.get(micArr, 0, shorts)
        CallScreen.mixPcm16(micArr, shorts, sysArr, maxOf(0, got), micMuted)
        sb.position(0)
        sb.put(micArr, 0, shorts)
    }
}

/**
 * TT 61: Lesen mit Schutz. libwebrtc (webrtc-sdk M150) beendet seinen Aufnahme-Thread beim ersten Lesefehler -3
 * (ERROR_INVALID_OPERATION) endgueltig - danach kommt fuer den Rest des Anrufs kein Ton mehr an, auch nach dem Teilen.
 * Daher: gestoppte Aufnahme einmal neu starten; geht es trotzdem nicht, Stille liefern (nie einen Fehler).
 */
internal interface PcmSource {
    fun read(buf: ByteBuffer, size: Int, mode: Int?): Int
    /** initialisiert, aber nicht (mehr) aufnehmend */
    fun stopped(): Boolean
    fun restart()
}

internal object MicGuard {
    const val ERROR_INVALID_OPERATION = -3
    /** Pause, wenn statt Ton Stille geliefert wird (ein 10-ms-Block), damit der Thread nicht leer dreht */
    const val SILENCE_PAUSE_MS = 10L

    fun read(src: PcmSource, buf: ByteBuffer, size: Int, mode: Int?, onData: (Int) -> Unit, pause: () -> Unit = { Thread.sleep(SILENCE_PAUSE_MS) }): Int {
        var n = src.read(buf, size, mode)
        if (n == ERROR_INVALID_OPERATION && src.stopped()) {
            try {
                src.restart()
            } catch (_: Exception) {
            }
            n = src.read(buf, size, mode)
        }
        if (n < 0) {
            val d = buf.duplicate()
            d.clear()
            val len = minOf(size, d.capacity())
            for (i in 0 until len) d.put(i, 0)
            pause()
            return len
        }
        if (n > 0) onData(n)
        return n
    }
}

/**
 * Huelle um die Mikrofon-Aufnahme von libwebrtc: liest das Mikrofon und mischt den Systemton dazu; alles andere geht an
 * die echte Aufnahme. Die eigene (geerbte) Aufnahme wird sofort freigegeben und nie benutzt.
 *
 * TT 61: AudioRecord.release() ruft intern stop() - virtuell, also das ueberschriebene stop() dieser Huelle. Frueher
 * stoppte so schon das Freigeben im Konstruktor das ECHTE Mikrofon: der naechste Lesezugriff lieferte -3, libwebrtc
 * beendete seinen Aufnahme-Thread, und es kam bis zum Ende des Anrufs kein Ton mehr an. Waehrend der eigenen Freigabe
 * gehen stop()/release() daher nicht ans Mikrofon.
 */
@SuppressLint("MissingPermission")
private class MixingAudioRecord(private val mic: AudioRecord, private val mixer: ScreenAudioMixer) : AudioRecord(
    MediaRecorder.AudioSource.DEFAULT, mic.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    AudioRecord.getMinBufferSize(mic.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096),
) {
    /** true, solange der Konstruktor die eigene (nie benutzte) Aufnahme freigibt */
    @Volatile private var own = true

    private val src = object : PcmSource {
        override fun read(buf: ByteBuffer, size: Int, mode: Int?): Int = if (mode == null) mic.read(buf, size) else mic.read(buf, size, mode)
        override fun stopped(): Boolean = mic.state == AudioRecord.STATE_INITIALIZED && mic.recordingState != AudioRecord.RECORDSTATE_RECORDING
        override fun restart() = mic.startRecording()
    }

    init {
        try {
            super.release()
        } catch (_: Exception) {
        }
        own = false
    }

    override fun read(audioBuffer: ByteBuffer, sizeInBytes: Int): Int =
        MicGuard.read(src, audioBuffer, sizeInBytes, null, { n -> mixer.mix(audioBuffer, n) })

    override fun read(audioBuffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int =
        MicGuard.read(src, audioBuffer, sizeInBytes, readMode, { n -> mixer.mix(audioBuffer, n) })

    override fun getTimestamp(outTimestamp: AudioTimestamp, timebase: Int): Int = mic.getTimestamp(outTimestamp, timebase)
    override fun stop() {
        if (!own) mic.stop()
    }
    override fun release() {
        if (!own) mic.release()
    }
    override fun getRecordingState(): Int = if (own) super.getRecordingState() else mic.recordingState
    override fun getState(): Int = if (own) super.getState() else mic.state
    override fun getSampleRate(): Int = mic.sampleRate
    override fun getChannelCount(): Int = mic.channelCount
    override fun getAudioFormat(): Int = mic.audioFormat
    override fun getAudioSessionId(): Int = mic.audioSessionId
    override fun getBufferSizeInFrames(): Int = mic.bufferSizeInFrames
    override fun setPreferredDevice(deviceInfo: android.media.AudioDeviceInfo?): Boolean = mic.setPreferredDevice(deviceInfo)
    override fun getRoutedDevice(): android.media.AudioDeviceInfo? = mic.routedDevice
}
