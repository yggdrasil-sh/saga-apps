package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yggdrasil_sh.saga.ui.common.AvatarOverlay

/**
 * Design 2 (Chats, Anrufe): gemeinsame Bausteine der Chat- und Anrufbildschirme - Profilkreise in ruhigen Tonfarben
 * (aus dem Namen, immer gleich je Person), Blasenformen fuer zusammenhaengende Nachrichten und "Bewegung reduziert".
 */
object ChatDesign {
    /** Tonpaar fuer Profilkreise: Flaeche und Schrift (Kontrast >= 4,5:1, im Test geprueft) */
    data class Tone(val bg: Color, val fg: Color)

    /** Gold (Saga), Petrol, Blau, Rose, Gruen, Violett - hell */
    val LIGHT_TONES = listOf(
        Tone(Color(0xFFF3E2BC), Color(0xFF261A00)),
        Tone(Color(0xFFC8EBE6), Color(0xFF00201C)),
        Tone(Color(0xFFCBE6FF), Color(0xFF001E30)),
        Tone(Color(0xFFFFD9DE), Color(0xFF3B0712)),
        Tone(Color(0xFFD4EDC4), Color(0xFF0F2004)),
        Tone(Color(0xFFE6DEFF), Color(0xFF1D1442)),
    )

    /** dieselben Farbtoene fuer das Nachtblau */
    val DARK_TONES = listOf(
        Tone(Color(0xFF5A4410), Color(0xFFF3E2BC)),
        Tone(Color(0xFF1F4D47), Color(0xFFC8EBE6)),
        Tone(Color(0xFF1E4B6A), Color(0xFFCBE6FF)),
        Tone(Color(0xFF6B2B36), Color(0xFFFFD9DE)),
        Tone(Color(0xFF2F4A22), Color(0xFFD4EDC4)),
        Tone(Color(0xFF433A6B), Color(0xFFE6DEFF)),
    )

    /** Stabiler Index aus dem Namen (Gross-/Kleinschreibung und Leerraum egal) */
    fun toneIndex(name: String): Int {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return 0
        var h = 0
        for (c in key) h = h * 31 + c.code
        return Math.floorMod(h, LIGHT_TONES.size)
    }

    /** Namensfarben in Gruppen (gleicher Farbton wie der Profilkreis), lesbar auf den Blasen (>= 4,5:1, Test) */
    val LIGHT_NAMES = listOf(Color(0xFF7A5A12), Color(0xFF00695F), Color(0xFF2B5C86), Color(0xFF9A2E46), Color(0xFF39661D), Color(0xFF5A4A98))
    val DARK_NAMES = listOf(Color(0xFFE8C26E), Color(0xFF7FD6CB), Color(0xFF9CC9EC), Color(0xFFFFB1BE), Color(0xFFA9D691), Color(0xFFC9BEFF))

    fun nameColor(name: String, scheme: androidx.compose.material3.ColorScheme): Color =
        (if (scheme.surface.luminance() < 0.3f) DARK_NAMES else LIGHT_NAMES)[toneIndex(name)]

    fun tone(name: String, dark: Boolean): Tone = (if (dark) DARK_TONES else LIGHT_TONES)[toneIndex(name)]

    /** Initialen: erster Buchstabe von bis zu zwei Woertern ("Mila Berg" -> "MB"), sonst "?" */
    fun initials(name: String): String {
        val words = name.trim().split(' ', ' ', '-', '_', '.').filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        if (words.isEmpty()) return name.trim().firstOrNull()?.uppercase() ?: "?"
        return words.take(2).joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() }
    }

    /**
     * Ecken einer Blase in einer Folge von Nachrichten derselben Person: aussen rund (20 dp), zur Nachbarblase hin
     * eng (6 dp), die letzte der Folge mit kleinem "Zipfel" (4 dp) zur Seite der Person.
     */
    fun bubbleShape(mine: Boolean, first: Boolean, last: Boolean): RoundedCornerShape {
        val r = io.github.yggdrasil_sh.saga.ui.theme.SagaRadius.l
        val near = io.github.yggdrasil_sh.saga.ui.theme.SagaRadius.xs
        val tail = 4.dp
        return if (mine) RoundedCornerShape(topStart = r, topEnd = if (first) r else near, bottomEnd = if (last) tail else near, bottomStart = r)
        else RoundedCornerShape(topStart = if (first) r else near, topEnd = r, bottomEnd = r, bottomStart = if (last) tail else near)
    }

    /** Zeit in der Chatliste: heute die Uhrzeit, in den letzten 6 Tagen der Wochentag (kurz), sonst das Datum */
    fun listTime(ms: Long, now: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
                 locale: java.util.Locale = java.util.Locale.getDefault()): String {
        val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val d = t.toLocalDate()
        return when {
            d == today || d.isAfter(today) -> t.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale))
            !d.isBefore(today.minusDays(6)) -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
            else -> t.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT).withLocale(locale))
        }
    }

    /** Zwei Nachrichten gehoeren zu einer Folge: gleiche Seite/Person, hoechstens 5 Minuten auseinander */
    fun sameRun(aSender: String?, aMine: Boolean, aTs: Long, bSender: String?, bMine: Boolean, bTs: Long): Boolean =
        aMine == bMine && (aMine || aSender == bSender) && kotlin.math.abs(aTs - bTs) <= 5 * 60_000L
}

/** "Animationen entfernen" (Bedienungshilfen) bzw. Animationsfaktor 0: keine Dauer-Animationen (aus SagaTheme) */
@Composable
fun reducedMotion(): Boolean = io.github.yggdrasil_sh.saga.ui.theme.LocalReducedMotion.current

/**
 * Profilkreis fuer Chats und Anrufe: Initialen in der Tonfarbe der Person, Profilbild (2.1) darueber, oder ein Symbol
 * (Gruppe, Seite) bzw. Emoji. Fuer Bildschirmleser ohne eigene Beschriftung (der Name steht daneben).
 */
@Composable
fun ChatAvatar(name: String, size: Dp, modifier: Modifier = Modifier, avatar: String? = null, icon: Int? = null, symbol: String? = null) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.3f
    val tone = ChatDesign.tone(name, dark)
    Box(modifier.size(size).clip(CircleShape).background(tone.bg), contentAlignment = Alignment.Center) {
        when {
            icon != null -> Icon(painterResource(icon), null, Modifier.size(size * 0.5f), tint = tone.fg)
            symbol != null -> Text(symbol, fontSize = (size.value * 0.42f).sp)
            else -> {
                val ini = ChatDesign.initials(name)
                Text(
                    ini, color = tone.fg, fontWeight = FontWeight.SemiBold,
                    // Groesse an den Kreis gebunden (nicht an die Systemschrift): zwei Buchstaben passen immer hinein
                    fontSize = (size.value * (if (ini.length > 1) 0.36f else 0.42f)).sp / androidx.compose.ui.platform.LocalDensity.current.fontScale,
                    maxLines = 1,
                )
                if (avatar != null) AvatarOverlay(avatar, size)
            }
        }
    }
}
