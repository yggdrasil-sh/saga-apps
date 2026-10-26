package io.github.yggdrasil_sh.saga.call

import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame

/**
 * Bildschirm teilen: zwischen ScreenCapturerAndroid und der VideoSource. Fehler 26.10. (zuerst auf iOS): bei stehendem
 * Bildschirm liefert das virtuelle Display der MediaProjection keine neuen Bilder, libwebrtc kodiert ein angefordertes
 * Schluesselbild aber erst mit dem naechsten Eingangsbild. Der Knoten fordert eines an, sobald ein Gegenueber den
 * Bildschirm-Platz bekommt (und nach Verlust/Stau), und verwirft bis dahin alles - das Gegenueber sah lange nichts.
 *
 * Kommt [CallScreen.REPEAT_MS] lang kein Bild, laesst [SurfaceTextureHelper.forceFrame] das letzte Bild der SurfaceTexture
 * noch einmal ausliefern (kein Kopieren, die Textur bleibt beim Helfer). Die Wiederholung traegt den Zeitstempel des
 * Originals - libwebrtc verwirft Bilder ohne aufsteigenden Zeitstempel, daher [CallScreen.nextTimestamp].
 * Alles laeuft auf dem Thread des SurfaceTextureHelper (Bilder und Zeitgeber).
 */
class ScreenRepeat(private val inner: CapturerObserver, private val surface: SurfaceTextureHelper) : CapturerObserver {
    private val handler = surface.handler
    @Volatile private var stopped = false
    private var lastNs = 0L
    private var lastTs = 0L
    private var lastSrc = 0L
    /** wiederholte Bilder (Protokoll) */
    @Volatile var repeats = 0
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            if (CallScreen.repeatDue(lastNs, System.nanoTime())) {
                lastNs = System.nanoTime()                   // auch wenn die Textur gerade belegt ist: naechster Versuch spaeter
                repeats++
                try {
                    surface.forceFrame()
                } catch (_: Exception) {
                }
            }
            handler.postDelayed(this, CallScreen.REPEAT_MS / 2)
        }
    }

    override fun onCapturerStarted(success: Boolean) {
        inner.onCapturerStarted(success)
        if (success && !stopped) handler.postDelayed(tick, CallScreen.REPEAT_MS / 2)
    }

    override fun onCapturerStopped() {
        stop()
        inner.onCapturerStopped()
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        val now = System.nanoTime()
        lastNs = now
        // Wiederholung (gleicher Zeitstempel wie das Original): Zeitpunkt jetzt, sonst der der Aufnahme
        val src = if (frame.timestampNs <= lastSrc) now else frame.timestampNs
        lastSrc = frame.timestampNs
        val ts = CallScreen.nextTimestamp(src, lastTs)
        lastTs = ts
        if (ts == frame.timestampNs) inner.onFrameCaptured(frame)
        else inner.onFrameCaptured(VideoFrame(frame.buffer, frame.rotation, ts))   // Puffer gehoert weiter dem Aufrufer
    }

    /** Vor stopCapture/dispose: keine Wiederholungen mehr */
    fun stop() {
        stopped = true
        try {
            handler.removeCallbacks(tick)
        } catch (_: Exception) {
        }
    }
}
