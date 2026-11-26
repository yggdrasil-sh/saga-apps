package io.github.yggdrasil_sh.saga.ui.saga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ai.AiConsent
import io.github.yggdrasil_sh.saga.ai.AiPersonaLogic
import io.github.yggdrasil_sh.saga.ai.AiStatus
import io.github.yggdrasil_sh.saga.ai.SagaLogic
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.ai.AiAvatar
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Bauteile des Orts "Saga" (Konzept UX §0): B1 KI-Kopf, B6 Leiste, ⓘ-Blatt, KI-Karte (filled tonal) und die
 * Kennenlernen-Karte (K19). Texte zum Host kommen immer fertig vom Server (status.intro / endpoint).
 */

/** Name der Persona fuer Saetze ("{name} denkt nach …"); ohne Persona der Standard des Servers bzw. "Saga" */
@Composable
fun sagaName(st: AiStatus?): String =
    AiPersonaLogic.effective(st)?.name ?: st?.persona?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.saga_nav)

/** Kennzeichnung "{Persona} · KI" (K11): style.label vom Server, sonst gebildet; Standardname ohne doppelte Marke */
@Composable
fun sagaLabel(st: AiStatus?): String {
    SagaLogic.label(st)?.let { return it }
    val p = AiPersonaLogic.effective(st)
    return if (p != null && p.customName) stringResource(R.string.saga_ki_label, p.name) else sagaName(st)
}

/** B1 KI-Kopf: Bild · "Marin · KI" · rechts Zustandswort/Uhrzeit. TalkBack: "Marin · KI, 14:12" */
@Composable
fun SagaHead(st: AiStatus?, size: Int = 24, extra: String? = null, modifier: Modifier = Modifier) {
    val label = sagaLabel(st)
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = if (extra.isNullOrBlank()) label else "$label, $extra"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AiAvatar(AiPersonaLogic.effective(st), size)
        Text(
            label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
        )
        if (!extra.isNullOrBlank()) {
            Text(
                extra, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** KI-Karte: Material 3 "filled tonal" (secondaryContainer = Goldflaeche), Text darauf onSecondaryContainer */
@Composable
fun SagaCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

/** ⓘ-Details als Bottom Sheet (M3: Fachwoerter nur hier) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(title: String, lines: List<String>, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            SagaTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.saga_close))
            }
        }
    }
}

/** ⓘ-Knopf (48 dp) mit Blatt */
@Composable
fun InfoButton(lines: List<String>, title: String = stringResource(R.string.saga_what_sent)) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.saga_info)
    IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "$label: $title" }) {
        Text("ⓘ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    if (open) InfoSheet(title, lines) { open = false }
}

/**
 * B6 Leiste "Was du hier schreibst, liest die KI auf {host}" mit ⓘ - fest oben im Bereich, einzeilig, nicht
 * wegklickbar, antippbar (oeffnet dieselben Details).
 */
@Composable
fun SagaBar(st: AiStatus?, modifier: Modifier = Modifier) {
    val intro = st?.intro ?: return
    if (intro.bar.isBlank()) return
    var open by remember { mutableStateOf(false) }
    val title = stringResource(R.string.saga_what_sent)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = title) { open = true }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                intro.bar, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            )
            Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                Text("ⓘ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (open) InfoSheet(title, intro.details) { open = false }
}

/**
 * Kennenlernen-Karte (K19): drei Saetze des Servers, Host-Hinweis mit ⓘ, ggf. Zusatzsatz des Admins und Ideen-Frage.
 * Knoepfe untereinander (Handy): "Ja, mit Ideen" · "Ja, ohne Ideen" (ohne ai_ideas: "Ja, einverstanden") · "Jetzt nicht";
 * darunter "Mehr einstellen". Einwilligung mit Fassung und Host der gezeigten Karte; 409 -> Status neu, Karte neu.
 */
@Composable
fun IntroCard(
    repo: SagaRepository, st: AiStatus, modifier: Modifier = Modifier,
    onDone: (AiConsent) -> Unit = {}, onLater: () -> Unit = {}, onMore: (() -> Unit)? = null,
) {
    val intro = st.intro ?: return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    fun go(a: SagaLogic.IntroAnswer) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val c = repo.saga.consent(a, intro)
                if (a == SagaLogic.IntroAnswer.LATER) onLater() else onDone(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = repo.friendly(e)
                if (e is ApiException && e.code == 409) repo.ai.loadStatus()     // andere Fassung/anderer Host: Karte neu
            } finally {
                busy = false
            }
        }
    }
    SagaCard(modifier) {
        SagaHead(st, 36)
        intro.lines.forEachIndexed { i, l ->
            if (i == 1) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(l, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    InfoButton(intro.details)
                }
                intro.note?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(l, style = MaterialTheme.typography.bodyLarge)
            }
        }
        intro.question?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
        val answers = SagaLogic.introAnswers(intro)
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            answers.forEachIndexed { i, a ->
                val text = stringResource(
                    when (a) {
                        SagaLogic.IntroAnswer.IDEAS -> R.string.saga_yes_ideas
                        SagaLogic.IntroAnswer.NO_IDEAS -> R.string.saga_yes_no_ideas
                        SagaLogic.IntroAnswer.AGREE -> R.string.saga_yes_agree
                        SagaLogic.IntroAnswer.LATER -> R.string.saga_not_now
                    },
                )
                val m = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                when {
                    a == SagaLogic.IntroAnswer.LATER -> SagaTextButton(onClick = { go(a) }, enabled = !busy, modifier = m.padding(top = 8.dp)) { Text(text) }
                    i == 0 -> SagaButton(onClick = { go(a) }, enabled = !busy, modifier = m) { Text(text) }
                    else -> SagaOutlinedButton(onClick = { go(a) }, enabled = !busy, modifier = m) { Text(text) }
                }
            }
        }
        error?.let {
            Text(
                it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (onMore != null) {
            SagaTextButton(onClick = onMore, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_more_settings)) }
        }
    }
}

/** Merkt sich je Prozess, dass die Karte auf "Heute" beantwortet wurde (Bestaetigung bleibt sichtbar bis zum Wechsel) */
private object TodayIntroMemory {
    var confirmed by mutableStateOf<String?>(null)
}

/**
 * "Heute": Kennenlernen-Karte einmal bis zur Antwort (Zustand none bzw. stale nach Wechsel des Endpunkts). Nach
 * "Jetzt nicht" zeigt "Heute" sie nicht mehr (der Ort "Saga" zeigt sie bei jedem Oeffnen); nach "Ja" ein kurzer Hinweis.
 */
@Composable
fun SagaTodayIntro(repo: SagaRepository, nav: AppNavigator, modifier: Modifier = Modifier) {
    val st by repo.ai.status.collectAsState()
    val server by repo.server.collectAsState()
    val account by repo.account.collectAsState()
    val key = "$server|$account"
    val s = st ?: return
    if (TodayIntroMemory.confirmed == key && SagaLogic.consentOk(s)) {
        SagaCard(modifier) {
            SagaHead(s, 24)
            Text(stringResource(R.string.saga_intro_done), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SagaButton(onClick = { TodayIntroMemory.confirmed = null; nav.top(Dest.Saga) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.saga_go))
                }
                SagaTextButton(onClick = { TodayIntroMemory.confirmed = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.saga_close))
                }
            }
        }
        return
    }
    if (!SagaLogic.introOnToday(s)) return
    IntroCard(
        repo, s, modifier,
        onDone = { TodayIntroMemory.confirmed = key },
        onMore = { nav.push(Dest.SettingsSaga) },
    )
}
