package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.Gestalten

// Saga 2.5 "Seite gestalten" (SPEC-gestalten) - Compose-Teil; die Logik liegt in blocks/Gestalten.kt

/** G9: Typografie der Seite mit Schrift (Serif/Mono) und kleinem Text (0,875 x). Code-Bloecke setzen Mono selbst. */
fun pageTypography(base: Typography, font: String?, small: Boolean): Typography {
    if (font == null && !small) return base
    val family = when (font) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        else -> null
    }
    fun TextUnit.k(): TextUnit = if (small && this != TextUnit.Unspecified) this * Gestalten.SMALL_SCALE else this
    fun TextStyle.f(): TextStyle = copy(
        fontFamily = family ?: fontFamily, fontSize = fontSize.k(), lineHeight = lineHeight.k(),
    )
    return base.copy(
        displayLarge = base.displayLarge.f(), displayMedium = base.displayMedium.f(), displaySmall = base.displaySmall.f(),
        headlineLarge = base.headlineLarge.f(), headlineMedium = base.headlineMedium.f(), headlineSmall = base.headlineSmall.f(),
        titleLarge = base.titleLarge.f(), titleMedium = base.titleMedium.f(), titleSmall = base.titleSmall.f(),
        bodyLarge = base.bodyLarge.f(), bodyMedium = base.bodyMedium.f(), bodySmall = base.bodySmall.f(),
        labelLarge = base.labelLarge.f(), labelMedium = base.labelMedium.f(), labelSmall = base.labelSmall.f(),
    )
}

@Composable
fun paletteName(c: String): String = stringResource(
    when (c) {
        "brown" -> R.string.gest_c_brown
        "orange" -> R.string.gest_c_orange
        "yellow" -> R.string.gest_c_yellow
        "green" -> R.string.gest_c_green
        "blue" -> R.string.gest_c_blue
        "purple" -> R.string.gest_c_purple
        "pink" -> R.string.gest_c_pink
        "red" -> R.string.gest_c_red
        else -> R.string.gest_c_gray
    },
)

/** Kleiner Farbpunkt im Menue (Text: farbiges "A", Hintergrund: Flaeche) */
@Composable
private fun Swatch(c: String?, bg: Boolean) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val line = MaterialTheme.colorScheme.outlineVariant
    if (c == null) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, line, CircleShape))
    } else if (bg) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(Gestalten.bgArgb(c, dark))))
    } else {
        Text("A", color = Color(Gestalten.textArgb(c, dark)), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
    }
}

/** G3: Farbmenue (Standard, 9 Textfarben, 9 Hintergruende) wie das Web-Block-Menue */
@Composable
fun BlockColorMenu(expanded: Boolean, current: Gestalten.Style?, onDismiss: () -> Unit, onPick: (String?, Boolean) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.gest_color_default)) }, leadingIcon = { Swatch(null, false) },
            trailingIcon = if (current == null) ({ Text("✓") }) else null,
            onClick = { onPick(null, false) },
        )
        HorizontalDivider()
        Gestalten.PALETTE.forEach { c ->
            DropdownMenuItem(
                text = { Text(paletteName(c)) }, leadingIcon = { Swatch(c, false) },
                trailingIcon = if (current == Gestalten.Style(c, false)) ({ Text("✓") }) else null,
                onClick = { onPick(c, false) },
            )
        }
        HorizontalDivider()
        Gestalten.PALETTE.forEach { c ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gest_color_bg, paletteName(c))) }, leadingIcon = { Swatch(c, true) },
                trailingIcon = if (current == Gestalten.Style(c, true)) ({ Text("✓") }) else null,
                onClick = { onPick(c, true) },
            )
        }
    }
}

/** G9: Untermenue "Schrift" im Seitenmenue (Standard/Serif/Mono + kleiner Text) */
@Composable
fun PageStyleMenu(expanded: Boolean, font: String?, small: Boolean, onDismiss: () -> Unit, onFont: (String?) -> Unit, onSmall: (Boolean) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf(null to R.string.gest_font_default, "serif" to R.string.gest_font_serif, "mono" to R.string.gest_font_mono).forEach { (f, label) ->
            val fam = when (f) {
                "serif" -> FontFamily.Serif
                "mono" -> FontFamily.Monospace
                else -> null
            }
            DropdownMenuItem(
                text = { Text(stringResource(label), fontFamily = fam) },
                trailingIcon = if (font == f) ({ Text("✓") }) else null,
                onClick = { onFont(f) },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.gest_small)) },
            trailingIcon = if (small) ({ Text("✓") }) else null,
            onClick = { onSmall(!small) },
        )
    }
}
