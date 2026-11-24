package io.github.yggdrasil_sh.saga.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.FirstSteps
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.theme.SagaFilledTonalButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaSpace
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.sagaSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 2.6 (Bugs-DB 2031): "Erste Schritte" - GET beim Start und bei jedem Wechsel in den Vordergrund (gedrosselt im Modell),
 * dann einmal die Frage, wenn der Server ask:true sagt und gerade nichts anderes laeuft ([blocked]: Anmeldung, Kopplung,
 * anderer Dialog, Anruf, Schublade) und der Ort passt (FirstSteps.askPlace - nicht in Chats/Einrichtung/Einstellungen).
 * Ja -> POST yes und die Seite oeffnen; Nein -> POST no. Zurueck schliesst ohne Antwort (naechster Start fragt wieder).
 */
@Composable
fun FirstStepsPrompt(repo: SagaRepository, accountKey: String, current: Dest, blocked: Boolean) {
    val model = repo.firstSteps
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(accountKey) {
        model.resetFor(accountKey)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!repo.needsLogin.value) model.check()
        }
    }
    val st by model.status.collectAsState()
    val asked by model.asked.collectAsState()
    val want = FirstSteps.askNow(st, asked, blocked, current)
    // wie im Web: erst kurz nach dem Aufbau (Fokus, andere Dialoge, Startseite) - und nur, wenn es dann noch passt
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(want) {
        if (!want) return@LaunchedEffect
        delay(600)
        show = true
    }
    if (!show || asked || !want) return               // inzwischen beantwortet/anderer Dialog: weg (kommt ggf. wieder)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun answer(yes: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val page = model.answer(yes)
                show = false
                if (yes && page != null) {
                    repo.openPageRequest.value = page
                    launch { runCatching { repo.refresh() } }       // neue Seite auch in der Seitenleiste
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.markAsked()
                show = false
                repo.notify(repo.friendly(e))
            } finally {
                busy = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { model.markAsked(); show = false },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.fs_ask_title)) },
        text = { Text(stringResource(R.string.fs_ask_text)) },
        confirmButton = { SagaTextButton(onClick = { answer(true) }, enabled = !busy) { Text(stringResource(R.string.fs_yes)) } },
        dismissButton = { SagaTextButton(onClick = { answer(false) }, enabled = !busy) { Text(stringResource(R.string.fs_no)) } },
    )
}

/**
 * Einstellungen > Info (Hilfe): Abschnitt "Erste Schritte" - mit Seite "n von all erledigt" + "Erste Schritte oeffnen",
 * ohne Seite kurzer Satz + "Erste Schritte anlegen" (beides POST yes, dann die Seite oeffnen). enabled:false oder
 * aelterer Server (404): kein Abschnitt.
 */
@Composable
fun FirstStepsSection(repo: SagaRepository, modifier: Modifier, onOpened: () -> Unit = {}) {
    val model = repo.firstSteps
    LaunchedEffect(Unit) { model.check(force = true) }
    val st by model.status.collectAsState()
    val s = st ?: return
    if (!s.enabled) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(modifier) {
        SagaWrapSectionTitle(stringResource(R.string.fs_title), Modifier.padding(top = 12.dp))
        Column(
            Modifier.sagaSegment(0, 1).padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (s.page != null) stringResource(R.string.fs_progress, s.doneCount, s.total) else stringResource(R.string.fs_none_text),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SagaFilledTonalButton(
                    enabled = !busy, modifier = Modifier.heightIn(min = SagaSpace.touch),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                model.answer(true)?.let { id ->
                                    onOpened()
                                    repo.openPageRequest.value = id
                                    launch { runCatching { repo.refresh() } }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                repo.notify(repo.friendly(e))
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(stringResource(if (s.page != null) R.string.fs_open else R.string.fs_create)) }
            }
        }
    }
}

/**
 * 2031: leerer Zustand der Termine - Kalender verbunden? Prueft nur, solange [active] (Liste leer), und bei jedem
 * Wechsel in den Vordergrund neu (nach "Kalender verbinden" im Browser zurueck in die App). null = unbekannt.
 */
@Composable
fun rememberCalendarConnected(repo: SagaRepository, active: Boolean): Boolean? {
    var ok by remember { mutableStateOf<Boolean?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { ok = repo.calendarConnected() ?: ok }
    }
    return ok
}

/** 2031: "Kalender verbinden" - die Kalender-Einstellungen im Web (Konten verbinden kann nur das Web) */
fun openCalendarSettings(context: android.content.Context, repo: SagaRepository) {
    openUrl(context, repo.absoluteUrl(EmptyStates.CALENDAR_SETTINGS))
}

/** 2031: Regeln der leeren Zustaende (testbar ohne Oberflaeche) */
object EmptyStates {
    /** Web: ?settings=calendar oeffnet die Einstellungen auf dem Reiter Kalender */
    const val CALENDAR_SETTINGS = "/?settings=calendar"

    enum class Tasks { NONE_EVER, ALL_DONE, NOT_EMPTY }

    /** Aufgabenliste: noch nie eine Aufgabe (Satz + "Aufgabe notieren") oder alles erledigt (kurzer Satz wie bisher) */
    fun tasks(open: Int, done: Int): Tasks = when {
        open > 0 -> Tasks.NOT_EMPTY
        done > 0 -> Tasks.ALL_DONE
        else -> Tasks.NONE_EVER
    }

    enum class Db { NONE, FILTERED }

    /** Datenbank-Ansicht ohne Zeilen: gar keine Eintraege (Satz + "Neuer Eintrag") oder nur durch Filter/Suche leer */
    fun db(total: Int, hasFilters: Boolean, search: String): Db =
        if (total > 0 || hasFilters || search.isNotBlank()) Db.FILTERED else Db.NONE

    enum class Events { NO_CALENDAR, EMPTY }

    /** Termine leer: ohne verbundenen Kalender dessen Hinweis + "Kalender verbinden", sonst "Neuer Termin" (unbekannt = wie bisher) */
    fun events(connected: Boolean?): Events = if (connected == false) Events.NO_CALENDAR else Events.EMPTY
}
