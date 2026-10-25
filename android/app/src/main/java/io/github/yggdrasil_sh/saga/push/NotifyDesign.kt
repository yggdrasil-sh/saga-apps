package io.github.yggdrasil_sh.saga.push

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import io.github.yggdrasil_sh.saga.ui.chat.ChatDesign

/**
 * Design 2: Mitteilungen im Saga-Stil - Akzentfarbe (Gold, dunkel genug fuer helle Mitteilungsflaechen) und
 * Profilkreis mit Initialen in derselben Tonfarbe wie in der App (Anrufe).
 */
object NotifyDesign {
    /** Akzent fuer kleines Symbol und App-Namen (Saga-Gold fuer Text, >= 4,5:1 auf Weiss) */
    const val ACCENT: Int = 0xFF7A5A12.toInt()

    /** Runder Profilkreis mit Initialen (hell: Mitteilungen liegen meist auf heller Flaeche) */
    fun initials(name: String, px: Int = 192): Bitmap {
        val tone = ChatDesign.tone(name, dark = false)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = tone.bg.toArgb()
        c.drawCircle(px / 2f, px / 2f, px / 2f, p)
        val text = ChatDesign.initials(name)
        p.color = tone.fg.toArgb()
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = px * (if (text.length > 1) 0.38f else 0.46f)
        val y = px / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(text, px / 2f, y, p)
        return bmp
    }
}
