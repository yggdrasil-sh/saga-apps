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
    private var micArr = ShortArray(0)
    private var sysArr = ShortArray(0)

    val active: Boolean get() = wrapper != null

    /** Mischen beginnen; true = Systemton laeuft mit */
    @SuppressLint("MissingPermission")                     // RECORD_AUDIO hat der Anruf (Aufrufer prueft)
    @RequiresApi(29)
    fun start(adm: JavaAudioDeviceModule, projection: MediaProjection): Boolean {
        if (wrapper != null) return true
        return try {
            val inp = JavaAudioDeviceModule::class.java.getField("audioInput").get(adm) ?: return false
            val f = inp.javaClass.getDeclaredField("audioRecord").apply { isAccessible = true }
            val mic = f.get(inp) as? AudioRecord ?: return false
            if (mic is MixingAudioRecord) return false
            val mask = if (mic.channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val min = AudioRecord.getMinBufferSize(mic.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            val rec = AudioRecord.Builder()
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
            sys = rec
            input = inp
            field = f
            original = mic
            wrapper = w
            f.set(inp, w)
            true
        } catch (e: Throwable) {
            Log.w("SagaCall", "screen audio: ${e.message}")
            stop()
            false
        }
    }

    /** Mischen beenden: Mikrofon-Aufnahme zurueck, Systemton-Aufnahme freigeben */
    fun stop() {
        try {
            val f = field
            val inp = input
            if (f != null && inp != null && f.get(inp) === wrapper) f.set(inp, original)
        } catch (_: Throwable) {
        }
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
 * Huelle um die Mikrofon-Aufnahme von libwebrtc: liest das Mikrofon und mischt den Systemton dazu; alles andere geht an
 * die echte Aufnahme. Die eigene (geerbte) Aufnahme wird sofort freigegeben und nie benutzt.
 */
@SuppressLint("MissingPermission")
private class MixingAudioRecord(private val mic: AudioRecord, private val mixer: ScreenAudioMixer) : AudioRecord(
    MediaRecorder.AudioSource.DEFAULT, mic.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    AudioRecord.getMinBufferSize(mic.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096),
) {
    init {
        try {
            super.release()
        } catch (_: Exception) {
        }
    }

    override fun read(audioBuffer: ByteBuffer, sizeInBytes: Int): Int {
        val n = mic.read(audioBuffer, sizeInBytes)
        if (n > 0) mixer.mix(audioBuffer, n)
        return n
    }

    override fun read(audioBuffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
        val n = mic.read(audioBuffer, sizeInBytes, readMode)
        if (n > 0) mixer.mix(audioBuffer, n)
        return n
    }

    override fun getTimestamp(outTimestamp: AudioTimestamp, timebase: Int): Int = mic.getTimestamp(outTimestamp, timebase)
    override fun stop() = mic.stop()
    override fun release() = mic.release()
    override fun getRecordingState(): Int = mic.recordingState
    override fun getState(): Int = mic.state
    override fun getSampleRate(): Int = mic.sampleRate
    override fun getChannelCount(): Int = mic.channelCount
    override fun getAudioFormat(): Int = mic.audioFormat
    override fun getAudioSessionId(): Int = mic.audioSessionId
    override fun getBufferSizeInFrames(): Int = mic.bufferSizeInFrames
    override fun setPreferredDevice(deviceInfo: android.media.AudioDeviceInfo?): Boolean = mic.setPreferredDevice(deviceInfo)
    override fun getRoutedDevice(): android.media.AudioDeviceInfo? = mic.routedDevice
}
