package io.github.yggdrasil_sh.saga.ui.common

import androidx.compose.ui.platform.LocalLocale
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Rahmen-Infos fuer die Titelleiste: Menue-Knopf (Seitenleiste) auf kleinen Bildschirmen */
data class Chrome(val showMenu: Boolean, val onMenu: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SagaTopBar(title: String, nav: AppNavigator, chrome: Chrome, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            when {
                nav.canGoBack -> IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                chrome.showMenu -> IconButton(onClick = chrome.onMenu) { Icon(Icons.Filled.Menu, stringResource(R.string.action_sidebar)) }
            }
        },
        actions = actions,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** Emoji oder eigenes Bild (/files/...) als Seiten-Symbol */
@Composable
fun PageIcon(icon: String?, repo: SagaRepository, isDatabase: Boolean = false, size: Dp = 22.dp) {
    val ic = icon?.trim().orEmpty()
    // Mindestgroesse statt fester Groesse: Emoji waechst mit der Schriftgroesse mit und wird nicht abgeschnitten
    Box(Modifier.defaultMinSize(size, size), contentAlignment = Alignment.Center) {
        if (ic.startsWith("/files/") || ic.startsWith("/api/fed/files/")) {
            AsyncImage(
                model = repo.absoluteUrl(if (ic.contains("?")) ic else "$ic?w=160"), contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Text(ic.ifEmpty { if (isDatabase) "🗃️" else "📄" }, fontSize = (size.value * 0.72f).sp, maxLines = 1)
        }
    }
}

/** Unterstuetzt der Standard-Browser private ("ephemere") Custom Tabs ohne gemeinsame Cookies? */
fun ephemeralTabsSupported(context: Context): Boolean = try {
    val pkg = androidx.browser.customtabs.CustomTabsClient.getPackageName(context, null)
    pkg != null && androidx.browser.customtabs.CustomTabsClient.isEphemeralBrowsingSupported(context, pkg)
} catch (_: Throwable) {
    false
}

/**
 * Link im Browser oeffnen (Custom Tab, sonst beliebiger Browser). ephemeral: privater Tab ohne die Browser-Sitzung
 * (Kontowechsel bei der Anmeldung), falls der Browser das kann.
 */
fun openUrl(context: Context, url: String, ephemeral: Boolean = false) {
    try {
        val b = CustomTabsIntent.Builder().setShowTitle(true)
        if (ephemeral && ephemeralTabsSupported(context)) b.setEphemeralBrowsingEnabled(true)
        b.build().launchUrl(context, url.toUri())
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.no_browser), Toast.LENGTH_SHORT).show()
        }
    }
}

/** Seitentitel fuer die Anzeige (leer -> "Unbenannt" in der App-Sprache) */
@Composable
fun pageTitle(p: PageMeta): String = p.title.ifEmpty { stringResource(R.string.page_untitled) }

// ---------------------------------------------------------------- Datum/Zeit (in der App-Sprache)

fun todayIso(): String = LocalDate.now().toString()

/** Sprache der Oberflaeche (folgt auch der Per-App-Sprache ab Android 13) */
@Composable
fun appLocale(): Locale = LocalLocale.current.platformLocale

/** Formatierer aus einem Skelett (z. B. "EEEEdMMMM"): Reihenfolge und Satzzeichen nach Sprache */
private fun skeleton(locale: Locale, skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)

/** "Mittwoch, 14. Oktober" / "Wednesday, October 14" / "miércoles, 14 de octubre" */
@Composable
fun longDate(d: LocalDate = LocalDate.now()): String = d.format(skeleton(appLocale(), "EEEEdMMMM"))

/** Faelligkeit relativ: heute, morgen, gestern, sonst kurzes Datum ("Mo., 3. Nov.") */
@Composable
fun dueText(iso: String): String {
    val d = try {
        LocalDate.parse(iso.take(10))
    } catch (_: Exception) {
        return iso
    }
    val t = LocalDate.now()
    return when (d) {
        t -> stringResource(R.string.date_today)
        t.plusDays(1) -> stringResource(R.string.date_tomorrow)
        t.minusDays(1) -> stringResource(R.string.date_yesterday)
        else -> d.format(skeleton(appLocale(), if (d.year == t.year) "EEEdMMM" else "yMMMd"))
    }
}

@Composable
fun dayHeader(d: LocalDate): String {
    val t = LocalDate.now()
    val base = longDate(d)
    return when (d) {
        t -> stringResource(R.string.day_header_today, base)
        t.plusDays(1) -> stringResource(R.string.day_header_tomorrow, base)
        else -> base
    }
}

/** Startdatum eines Termins (ganztaegig: Datum, sonst Zeitpunkt in lokaler Zeit) */
fun eventDay(start: String): LocalDate? = try {
    if (start.length <= 10) LocalDate.parse(start) else OffsetDateTime.parse(start).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
} catch (_: Exception) {
    try {
        LocalDate.parse(start.take(10))
    } catch (_: Exception) {
        null
    }
}

/** Uhrzeit(en) eines Termins: 24-/12-Stunden-Format nach Systemeinstellung, Schreibweise nach Sprache */
@Composable
fun eventTime(start: String, end: String, allday: Boolean): String {
    if (allday) return stringResource(R.string.event_all_day)
    val h24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val f = skeleton(appLocale(), if (h24) "Hm" else "hm")
    return try {
        val s = OffsetDateTime.parse(start).atZoneSameInstant(ZoneId.systemDefault())
        val e = try {
            OffsetDateTime.parse(end).atZoneSameInstant(ZoneId.systemDefault())
        } catch (_: Exception) {
            null
        }
        s.format(f) + (e?.let { "–" + it.format(f) } ?: "")
    } catch (_: Exception) {
        start
    }
}

/** Datum aus Sekunden seit 1970 (kurz, nach Sprache: 14.10.2026 / Oct 14, 2026 / 14 oct 2026) */
@Composable
fun epochDay(v: Double?): String {
    val locale = appLocale()
    return v?.let {
        java.time.Instant.ofEpochMilli((it * 1000).toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    } ?: "–"
}

/** "jetzt" / "vor 5 Minuten" / "5 minutes ago" / "hace 5 minutos" (Sekunden seit 1970; in der App-Sprache) */
@Composable
fun relativeTime(epochSec: Double): String {
    val locale = appLocale()
    val d = System.currentTimeMillis() / 1000.0 - epochSec
    val f = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale))
    val last = RelativeDateTimeFormatter.Direction.LAST
    return when {
        d < 10 -> f.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        d < 60 -> f.format(d.toInt().toDouble(), last, RelativeDateTimeFormatter.RelativeUnit.SECONDS)
        d < 3600 -> f.format((d / 60).toInt().toDouble(), last, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        d < 86400 -> f.format((d / 3600).toInt().toDouble(), last, RelativeDateTimeFormatter.RelativeUnit.HOURS)
        d < 7 * 86400 -> f.format((d / 86400).toInt().toDouble(), last, RelativeDateTimeFormatter.RelativeUnit.DAYS)
        else -> epochDay(epochSec)
    }
}

/** Farbe aus "#rrggbb" */
fun hexColor(s: String?, fallback: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    val h = s?.trim()?.removePrefix("#") ?: return fallback
    val v = h.toLongOrNull(16) ?: return fallback
    return when (h.length) {
        6 -> androidx.compose.ui.graphics.Color(0xFF000000 or v)
        8 -> androidx.compose.ui.graphics.Color(v)
        else -> fallback
    }
}

@Composable
fun InfoRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
