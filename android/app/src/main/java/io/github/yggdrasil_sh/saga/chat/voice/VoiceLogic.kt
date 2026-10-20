package io.github.yggdrasil_sh.saga.chat.voice

import io.github.yggdrasil_sh.saga.chat.ChatFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Sprachnachrichten (Saga 2.5, Konzept Chat B3): reine Logik (JVM-Tests) - Wellenform wie static/chat-voice.js, Grenzen
 * (Hinweis 14:00, Stopp 15:00, unter 1 s verwerfen), Gesten (halten, nach oben sperren, nach links verwerfen), Tempo,
 * Anhang im verschluesselten Inhalt. Gegenstueck zu VoiceLogic in ios/App/ChatX3Logic.swift.
 */
object VoiceLogic {
    const val WAVE_N = 64
    const val MIN_S = 1.0
    /** Pegel je 100 ms (wie im Browser: Takt der Wellenform) */
    const val LEVEL_MS = 100
    const val FILE_NAME = "Sprachnachricht.ogg"
    /** Aufnahme ab Android 10 (MediaCodec-Opus-Encoder); Android 8/9 nur Abspielen (Entscheidung Sven, Frage 4) */
    const val RECORD_MIN_SDK = 29
    val SPEEDS = listOf(1f, 1.5f, 2f)

    /** Hinweis eine Minute vor dem Ende */
    fun warnAt(maxS: Double): Double = max(0.0, maxS - 60)

    fun nextSpeed(s: Float): Float = SPEEDS[(SPEEDS.indexOf(s).coerceAtLeast(0) + 1) % SPEEDS.size]

    /** "1×", "1,5×", "2×" (Dezimalzeichen der Sprache) */
    fun speedLabel(s: Float, decimal: Char = ','): String = if (s == 1.5f) "1${decimal}5×" else "${s.roundToInt()}×"

    /** RMS eines Abschnitts 16-Bit-PCM (0..1) */
    fun rms(pcm: ShortArray, from: Int = 0, until: Int = pcm.size): Float {
        if (until <= from) return 0f
        var s = 0.0
        for (i in from until until) {
            val x = pcm[i] / 32768.0
            s += x * x
        }
        return sqrt(s / (until - from)).toFloat()
    }

    /** Wellenform wie static/chat-voice.js: Spitzen je Abschnitt aus den Pegeln (RMS je 100 ms), 0-255, Wurzel-normalisiert */
    fun waveform(levels: List<Float>): ByteArray {
        val n = levels.size
        val out = ByteArray(WAVE_N)
        if (n == 0) return out
        val v = DoubleArray(WAVE_N)
        var mx = 0.0                                          // in Double wie im Browser (gleiche Rundung)
        for (i in 0 until WAVE_N) {
            val a = i * n / WAVE_N
            val z = max(a + 1, (i + 1) * n / WAVE_N)
            var m = 0.0
            for (k in a until min(z, n)) m = max(m, levels[k].toDouble())
            v[i] = m
            mx = max(mx, m)
        }
        for (j in 0 until WAVE_N) out[j] = (if (mx > 0) min(255L, Math.round(255 * sqrt(v[j] / mx))) else 0L).toByte()
        return out
    }

    /** wf aus der Nachricht (B64, 64 Bytes) -> Balken 0..255; fehlt/kaputt: flach */
    fun bars(b64: String?): IntArray {
        val d = try {
            b64?.let { java.util.Base64.getDecoder().decode(it) }
        } catch (_: Exception) {
            null
        } ?: ByteArray(0)
        return IntArray(WAVE_N) { if (it < d.size) d[it].toInt() and 0xff else 0 }
    }

    /** Wie viele Balken sind "gehoert" (eingefaerbt) */
    fun played(position: Double, duration: Double): Int {
        if (duration <= 0) return 0
        return (min(1.0, max(0.0, position / duration)) * WAVE_N).roundToInt()
    }

    /** Tippen auf die Wellenform (0..1 der Breite) -> Sekunde */
    fun seek(fraction: Double, duration: Double): Double = max(0.0, min(duration - 0.05, fraction * duration))

    /** m:ss */
    fun fmt(s: Double): String {
        val t = (if (s.isFinite()) max(0.0, s) else 0.0).roundToInt()
        return "%d:%02d".format(t / 60, t % 60)
    }

    /** Ablauf der Aufnahme je Takt: weiter, Hinweis (einmal), Stopp an der Grenze */
    enum class Tick { RUN, WARN, STOP }

    fun tick(secs: Double, maxS: Double, warned: Boolean): Tick = when {
        secs >= maxS -> Tick.STOP
        !warned && secs >= warnAt(maxS) -> Tick.WARN
        else -> Tick.RUN
    }

    /** Ergebnis nach dem Stopp: zu kurz -> verwerfen */
    fun keep(duration: Double): Boolean = duration >= MIN_S

    /** Halten zum Aufnehmen: nach oben schieben sperrt, nach links verwirft (Abstaende in dp) */
    enum class Gesture { HOLD, LOCK, CANCEL }
    const val LOCK_DP = 70.0
    const val CANCEL_DP = 110.0

    fun gesture(dx: Double, dy: Double): Gesture = when {
        dx <= -CANCEL_DP && abs(dx) > abs(dy) -> Gesture.CANCEL
        dy <= -LOCK_DP && abs(dy) >= abs(dx) -> Gesture.LOCK
        else -> Gesture.HOLD
    }

    /** Anhang im verschluesselten Inhalt (saga-file/v2 bzw. v1) mit voice/dur/wf */
    fun file(id: String, key: String, size: Long, v2: Boolean, cs: Int, dur: Double, wf: ByteArray): ChatFile = ChatFile(
        id = id, k = key, n = FILE_NAME, m = OggOpus.MIME, s = size, x = if (v2) 2 else null, cs = if (v2) cs else null,
        dur = Math.round(dur * 10) / 10.0, voice = true, wf = java.util.Base64.getEncoder().encodeToString(wf),
    )
}
