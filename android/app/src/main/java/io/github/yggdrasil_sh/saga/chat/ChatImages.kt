package io.github.yggdrasil_sh.saga.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Vorschaubilder fuer Chat-Bilder (2.1-c, docs: chat-thumbs-spec): laengste Seite 320 px (nie hochskalieren),
 * JPEG Qualitaet 70, ohne Metadaten (Bitmap.compress schreibt keine), EXIF-Drehung angewendet, Transparenz auf Weiss,
 * GIF: erstes Bild. Nur im Hintergrund aufrufen.
 */
object ChatImages {
    const val THUMB_MAX = 320
    const val QUALITY = 70
    /** Anzeige im Verlauf: hoechstens so gross (dp), Seitenverhaeltnis aus w/h */
    const val SHOW_MAX = 280

    class Thumb(val jpeg: ByteArray, val w: Int, val h: Int, val origW: Int, val origH: Int)

    // ---------------------------------------------------------------- reine Rechnungen (Unit-Tests)

    /** Groesse des Vorschaubilds: laengste Seite max, Seitenverhaeltnis bleibt, nie groesser als das Original */
    fun thumbSize(w: Int, h: Int, maxSide: Int = THUMB_MAX): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        val longest = max(w, h)
        if (longest <= maxSide) return w to h
        val f = maxSide.toDouble() / longest
        return max(1, (w * f).roundToInt()) to max(1, (h * f).roundToInt())
    }

    /** inSampleSize (Zweierpotenz), damit das dekodierte Bild noch mindestens target px auf der laengsten Seite hat */
    fun sampleSize(w: Int, h: Int, target: Int = THUMB_MAX): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= target) s *= 2
        return s
    }

    /** Linkvorschau ueber dem Eingabefeld: laengste Seite hoechstens so viele Pixel (pt2 A3) */
    const val PREVIEW_MAX = 1024
    /** Quellbilder ueber so vielen Pixeln gar nicht erst dekodieren (Unsinns-Masse; echte Kamerafotos bis 200 MP bleiben) */
    const val SOURCE_MAX_PIXELS = 500_000_000L

    /**
     * inSampleSize (Zweierpotenz), damit die laengste Seite nach dem Dekodieren hoechstens maxSide px hat (aufgerundet
     * gerechnet: nie groesser als die Grenze). 0 = unbrauchbare Masse (<= 0) oder Quelle zu gross -> nicht dekodieren.
     */
    fun boundedSample(w: Int, h: Int, maxSide: Int): Int {
        if (w <= 0 || h <= 0 || maxSide <= 0 || w.toLong() * h > SOURCE_MAX_PIXELS) return 0
        val longest = max(w, h).toLong()
        var s = 1L
        while ((longest + s - 1) / s > maxSide) s *= 2
        return s.toInt()
    }

    /** Zielmasse, falls der Dekoder trotz inSampleSize groesser liefert: in maxSide einpassen (null = passt schon) */
    fun clampSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int>? = if (max(w, h) <= maxSide) null else thumbSize(w, h, maxSide)

    /** Anzeigegroesse (dp) im Verlauf aus Pixelmassen: in SHOW_MAX x SHOW_MAX einpassen, nie unter 48 */
    fun displaySize(w: Int?, h: Int?, maxSide: Int = SHOW_MAX): Pair<Int, Int>? {
        if (w == null || h == null || w <= 0 || h <= 0) return null
        val f = minOf(1.0, maxSide.toDouble() / max(w, h))
        return max(48, (w * f).roundToInt()) to max(48, (h * f).roundToInt())
    }

    /**
     * Wie viele Bilder einer Nachricht ein Vorschaubild bekommen: jedes braucht eine zusaetzliche Datei-Kennung.
     * Aelterer Server ohne file_ids_per_msg: hoechstens files_per_msg Kennungen insgesamt.
     */
    fun thumbBudget(files: Int, images: Int, filesPerMsg: Int, fileIdsPerMsg: Int?): Int {
        val ids = fileIdsPerMsg ?: filesPerMsg
        return (ids - files).coerceIn(0, images)
    }

    // ---------------------------------------------------------------- Bitmap (Android)

    private fun orientation(data: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun swapsSides(o: Int) = o == ExifInterface.ORIENTATION_ROTATE_90 || o == ExifInterface.ORIENTATION_ROTATE_270 ||
        o == ExifInterface.ORIENTATION_TRANSPOSE || o == ExifInterface.ORIENTATION_TRANSVERSE

    /** Vorschaubild erzeugen; null = Format unbekannt/kaputt (dann ohne th senden bzw. Datei-Symbol zeigen) */
    /** 2.5 (groupinfo2): Gruppenbild - quadratisch (Mitte) zugeschnitten, side x side, JPEG (EXIF-Drehung beachtet) */
    fun squareJpeg(data: ByteArray, side: Int = ChatStd.AVATAR_PX): ByteArray? {
        val t = makeThumb(data, side * 4) ?: return null
        val src = BitmapFactory.decodeByteArray(t.jpeg, 0, t.jpeg.size) ?: return null
        val (x, y, e) = ChatStd.squareCrop(src.width, src.height)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        try {
            Canvas(out).drawBitmap(src, android.graphics.Rect(x, y, x + e, y + e), android.graphics.Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            return bos.toByteArray()
        } finally {
            src.recycle()
            out.recycle()
        }
    }

    fun makeThumb(data: ByteArray, maxSide: Int = THUMB_MAX): Thumb? = try {
        makeThumbOrThrow(data, maxSide)
    } catch (_: OutOfMemoryError) {
        null                                                 // pt2 A3: Speicher knapp -> ohne Vorschaubild
    }

    private fun makeThumbOrThrow(data: ByteArray, maxSide: Int): Thumb? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, b)
        if (b.outWidth <= 0 || b.outHeight <= 0 || b.outWidth.toLong() * b.outHeight > SOURCE_MAX_PIXELS) return null
        val o = orientation(data)
        val (ow, oh) = if (swapsSides(o)) b.outHeight to b.outWidth else b.outWidth to b.outHeight
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(b.outWidth, b.outHeight, maxSide) }
        val src = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
        val (tw, th) = thumbSize(ow, oh, maxSide)
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(out)
            c.drawColor(Color.WHITE)                         // Transparenz -> Weiss (JPEG)
            val m = Matrix()
            // Quelle (ungedreht) auf Zielgroesse bringen, dann EXIF-Drehung um die Mitte anwenden
            val sw = if (swapsSides(o)) th else tw
            val sh = if (swapsSides(o)) tw else th
            m.postScale(sw.toFloat() / src.width, sh.toFloat() / src.height)
            m.postTranslate(-sw / 2f, -sh / 2f)
            when (o) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            }
            m.postTranslate(tw / 2f, th / 2f)
            c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, QUALITY, bos)
            return Thumb(bos.toByteArray(), tw, th, ow, oh)
        } finally {
            src.recycle()
            out.recycle()
        }
    }

    /** Vorschaubild (klein) dekodieren; groessere Bilder (alte Nachrichten ohne th) direkt verkleinert */
    fun decodeSmall(data: ByteArray, maxSide: Int = THUMB_MAX * 2): Bitmap? = decodeBounded(data, maxSide * 2)

    /**
     * Bild aus fremden Bytes dekodieren, laengste Seite hoechstens maxSide px (pt2 A3): erst nur die Masse lesen, dann mit
     * inSampleSize; liefert der Dekoder doch mehr, wird verkleinert. Kaputt, zu gross oder Speicher knapp -> null, nie eine
     * Ausnahme (auch kein OutOfMemoryError). So kommt nie eine Bitmap ueber der Grenze in die Oberflaeche.
     */
    /**
     * 2.5 Medien 1 (Bildbetrachter): wie [decodeBounded], dazu die EXIF-Drehung angewendet (unveraendert gesendete Fotos
     * tragen sie noch). Drehen scheitert (Speicher) -> ungedreht statt gar nicht.
     */
    fun decodeOriented(data: ByteArray, maxSide: Int): Bitmap? {
        val src = decodeBounded(data, maxSide) ?: return null
        val o = orientation(data)
        if (o == ExifInterface.ORIENTATION_NORMAL || o == ExifInterface.ORIENTATION_UNDEFINED) return src
        return try {
            val m = Matrix()
            when (o) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                else -> return src
            }
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (out !== src) src.recycle()
            out
        } catch (_: Throwable) {
            src
        }
    }

    fun decodeBounded(data: ByteArray, maxSide: Int): Bitmap? = try {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, b)
        val sample = boundedSample(b.outWidth, b.outHeight, maxSide)
        if (sample <= 0) {
            null
        } else {
            val src = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            val fit = src?.let { clampSize(it.width, it.height, maxSide) }
            if (src != null && fit != null) {
                val out = Bitmap.createScaledBitmap(src, fit.first, fit.second, true)
                if (out !== src) src.recycle()
                out
            } else {
                src
            }
        }
    } catch (_: Throwable) {
        null
    }
}
