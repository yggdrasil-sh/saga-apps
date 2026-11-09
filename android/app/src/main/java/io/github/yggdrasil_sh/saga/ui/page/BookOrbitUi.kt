package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.BookOrbit
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaProgressTrack
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton

// Saga 2.5 BookOrbit (bookorbit1): Inhalt des Widgets "Lesen" und der Heute-Karte. Logik in blocks/BookOrbit.kt.
// Cover laufen ueber den Saga-Server (Coil mit der App-Sitzung, Zwischenspeicher wie andere Bilder).

/**
 * Leseliste, Leseziel oder Lesestraehne. [onSettings]: Weg zu Einstellungen -> BookOrbit (Hinweis ohne Verbindung).
 * [limit]: hoechstens so viele Buecher (Heute).
 */
@Composable
fun ReadingContent(r: BookOrbit.Reading, repo: SagaRepository, accent: Color, onSettings: () -> Unit, compact: Boolean = false, limit: Int? = null, big: @Composable () -> androidx.compose.ui.text.TextStyle = { MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold) }) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val c = BookOrbit.content(r, limit)) {
            is BookOrbit.Content.Hint -> ReadingHint(c.state, onSettings)
            is BookOrbit.Content.Books -> {
                c.books.forEach { b -> ReadingBookRow(b, repo, accent, compact) }
                if (c.more > 0) Text(stringResource(R.string.bo_more, c.more), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
            BookOrbit.Content.NoBooks -> Text(stringResource(R.string.bo_no_book), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            is BookOrbit.Content.GoalC -> ReadingGoal(c.goal, accent, big)
            BookOrbit.Content.NoGoal -> Text(stringResource(R.string.bo_no_goal), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            is BookOrbit.Content.StreakC -> ReadingStreak(c.streak, accent, big)
            BookOrbit.Content.NoStreak -> Text(stringResource(R.string.bo_no_streak), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        if (r.stale && r.state == BookOrbit.State.OK) Text(stringResource(R.string.bo_stale), style = MaterialTheme.typography.labelSmall, color = cs.tertiary)
    }
}

/** Ohne Verbindung / abgelaufen / nicht erreichbar: freundlicher Hinweis, ggf. mit "Einstellungen oeffnen" */
@Composable
fun ReadingHint(state: BookOrbit.State, onSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SagaIcon(if (state == BookOrbit.State.UNAVAILABLE) SagaIcons.cloudOff else SagaIcons.info, null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp).size(18.dp))
            Text(
                stringResource(
                    when (state) {
                        BookOrbit.State.EXPIRED -> R.string.bo_hint_expired
                        BookOrbit.State.UNAVAILABLE -> R.string.bo_hint_unavailable
                        else -> R.string.bo_hint_none
                    },
                ),
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
        }
        if (BookOrbit.offersSettings(state)) {
            SagaTextButton(onClick = onSettings) { Text(stringResource(R.string.bo_open_settings)) }
        }
    }
}

/** Ein Buch: Cover (oder Platzhalter), Titel, Autor:innen, Fortschritt; fuer TalkBack eine Zeile */
@Composable
fun ReadingBookRow(b: BookOrbit.Book, repo: SagaRepository, accent: Color, compact: Boolean) {
    val cs = MaterialTheme.colorScheme
    val title = b.title.ifEmpty { stringResource(R.string.bo_untitled) }
    val desc = listOf(title, b.authorLine).filter { it.isNotEmpty() }.joinToString(", ") + ", " + stringResource(R.string.bo_progress_a11y, b.percent)
    // Cover waechst mit der Schriftgroesse (wie Dynamic Type)
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val w = (if (compact) 32.dp else 40.dp) * scale
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(w, w * 1.5f).clip(RoundedCornerShape(SagaRadius.xs)).background(cs.surfaceContainerHighest)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(SagaRadius.xs)),
            contentAlignment = Alignment.Center,
        ) {
            Text("📖", style = MaterialTheme.typography.bodyMedium)
            if (b.cover != null) {
                coil3.compose.AsyncImage(
                    model = repo.absoluteUrl(b.cover), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
            if (b.authorLine.isNotEmpty()) Text(b.authorLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(progress = { (b.progress / 100.0).toFloat() }, color = accent, trackColor = SagaProgressTrack, modifier = Modifier.weight(1f))
                Text("${b.percent} %", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReadingGoal(g: BookOrbit.Goal, accent: Color, big: @Composable () -> androidx.compose.ui.text.TextStyle) {
    val cs = MaterialTheme.colorScheme
    // Ohne Jahr eigene Texte, sonst bliebe "Leseziel : ..." stehen
    val year = g.year?.toString()
    val f = g.fraction
    if (g.goal != null && f != null) {
        Text("${g.done} / ${g.goal}", style = big(), color = accent)
        LinearProgressIndicator(progress = { f }, color = accent, trackColor = SagaProgressTrack, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        Text(if (year != null) stringResource(R.string.bo_goal_of, year, g.done, g.goal) else stringResource(R.string.bo_goal_of_noyear, g.done, g.goal), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    } else {
        Text(g.done.toString(), style = big(), color = accent)
        Text(if (year != null) stringResource(R.string.bo_goal_none, year) else stringResource(R.string.bo_goal_none_noyear), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ReadingStreak(s: BookOrbit.Streak, accent: Color, big: @Composable () -> androidx.compose.ui.text.TextStyle) {
    val cs = MaterialTheme.colorScheme
    Text(pluralStringResource(R.plurals.bo_days, s.current, s.current), style = big(), color = accent)
    if (s.last7.isNotEmpty()) {
        val desc = stringResource(R.string.bo_last7, s.daysRead)
        Row(Modifier.semantics { contentDescription = desc }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            s.last7.forEach { on ->
                Box(
                    Modifier.size(12.dp).clip(CircleShape).background(if (on) accent else cs.surfaceContainerHighest)
                        .border(1.dp, if (on) accent else cs.outline, CircleShape),
                )
            }
        }
    }
    Text(pluralStringResource(R.plurals.bo_streak_longest, s.longest, s.longest), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
}
