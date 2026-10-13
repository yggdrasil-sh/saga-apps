package io.github.yggdrasil_sh.saga.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
                nav.canGoBack -> IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                chrome.showMenu -> IconButton(onClick = chrome.onMenu) { Icon(Icons.Filled.Menu, "Seitenleiste") }
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
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (ic.startsWith("/files/")) {
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

/** Link im Browser oeffnen (Custom Tab, sonst beliebiger Browser) */
fun openUrl(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Kein Browser gefunden", Toast.LENGTH_SHORT).show()
        }
    }
}

// ---------------------------------------------------------------- Datum/Zeit (deutsch)

private val de = Locale.GERMAN

fun todayIso(): String = LocalDate.now().toString()

fun longDate(d: LocalDate = LocalDate.now()): String = d.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", de))

/** Faelligkeit relativ: heute, morgen, gestern, sonst "Mo., 3. Nov." */
fun dueText(iso: String): String {
    val d = try {
        LocalDate.parse(iso.take(10))
    } catch (_: Exception) {
        return iso
    }
    val t = LocalDate.now()
    return when (d) {
        t -> "heute"
        t.plusDays(1) -> "morgen"
        t.minusDays(1) -> "gestern"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == t.year) "EE, d. MMM" else "d. MMM yyyy", de))
    }
}

fun dayHeader(d: LocalDate): String {
    val t = LocalDate.now()
    val base = d.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", de))
    return when (d) {
        t -> "Heute · $base"
        t.plusDays(1) -> "Morgen · $base"
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

fun eventTime(start: String, end: String, allday: Boolean): String {
    if (allday) return "ganztägig"
    val f = DateTimeFormatter.ofPattern("HH:mm", de)
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

fun epochDay(v: Double?): String = v?.let {
    java.time.Instant.ofEpochMilli((it * 1000).toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("d.M.yyyy", de))
} ?: "–"

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
