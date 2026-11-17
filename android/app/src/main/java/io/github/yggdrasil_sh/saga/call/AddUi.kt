package io.github.yggdrasil_sh.saga.call

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.SagaApplication
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// 2.5 (L8/L9) "Person hinzufuegen" im laufenden Anruf: Knopf in der Teilnehmerliste bzw. im Blatt "Mehr", Auswahl aus
// Kontakten/Personensuche (wer schon im Anruf ist, fehlt), Rueckfrage "Seite teilen?", Hinweis im direkten 1:1.
// Regeln: CallAdd (AddLogic.kt), Ablauf: CallManager.addPerson/addAnswer/moveIn.

/** Rueckfrage im Seiten-Chat (409 page_confirm): Text des Servers */
data class CallAddAsk(val pick: CallAdd.Pick, val text: UiText)

/** Zustand fuer die Oberflaeche (CallUiState.add) */
data class CallAddUi(
    val mode: CallAdd.Mode,
    val seats: CallAdd.Seats,
    val page: String? = null,
    /** Hinweis oben im Feld bzw. (HINT) der ganze Hinweis */
    val hint: UiText,
    val inCall: Set<String> = emptySet(),
    val away: Set<String> = emptySet(),
    val busy: Boolean = false,
    val note: UiText? = null,
    val ask: CallAddAsk? = null,
)

/** Knopf "Person hinzufuegen" (Symbol Person mit Plus) oben in der Teilnehmerliste */
@Composable
fun AddPersonButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.add_person)
    SagaTextButton(onClick = onClick, modifier = modifier.semantics { contentDescription = label }) {
        Icon(painterResource(SagaIcons.userPlus), null, Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 6.dp), maxLines = 2)
    }
}

/**
 * Auswahl: Suchfeld (Fokus), ohne Eingabe die Kontakte, mit Eingabe die Personensuche; wer schon im Anruf ist, fehlt;
 * Mitglieder ohne Geraet im Anruf "im Chat – klingelt erneut". Voll: Hinweis, Zeilen deaktiviert. Zurueck schliesst.
 */
@Composable
fun AddPersonDialog(
    add: CallAddUi, onPick: (CallAdd.Pick) -> Unit,
    /** Zeilen zur Eingabe ("" = Kontakte); Standard: Server (Kontakte bzw. Personensuche) - Bildschirmfotos setzen Beispiele */
    load: (suspend (String) -> List<CallAdd.Pick>)? = null,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as? SagaApplication)?.repo
    var q by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<CallAdd.Pick>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: IllegalStateException) {} }
    LaunchedEffect(q) {
        val v = q.trim()
        if (v.isNotEmpty()) delay(250)
        items = try {
            when {
                load != null -> load(v)
                repo == null -> emptyList()
                v.isEmpty() -> repo.chat.contacts(CallAdd.CONTACTS).map { CallAdd.fromContact(it) }
                else -> CallAdd.fromSearch(repo.chat.people(v))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        loaded = true
    }
    val shown = CallAdd.filter(items, add.inCall, add.away)
    val title = stringResource(R.string.add_person)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    add.hint.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                val searchCd = stringResource(R.string.add_search_cd)
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true, label = { Text(stringResource(R.string.add_search)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = searchCd },
                )
                if (q.isBlank() && shown.isNotEmpty()) Text(
                    stringResource(R.string.ct_contacts), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() },
                )
                if (shown.isEmpty() && loaded) Text(
                    stringResource(if (q.isBlank()) R.string.add_no_contacts else R.string.add_none_found),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(shown, key = { it.ref }) { p ->
                        AddPickRow(p, CallAdd.pickable(p, add.seats, add.busy)) {
                            onPick(p)
                            q = ""
                        }
                        HorizontalDivider()
                    }
                }
                val note = add.note?.resolve()
                Box(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (note != null) Text(
                        note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun AddPickRow(p: CallAdd.Pick, enabled: Boolean, onClick: () -> Unit) {
    val name = p.name.ifEmpty { "?" }
    val sub = when {
        p.away -> stringResource(R.string.add_away)
        p.remoteHost != null -> stringResource(R.string.ct_person_on, p.remoteHost)
        else -> p.sub
    }
    val cd = CallAdd.rowDescription(stringResource(R.string.add_pick_cd, name), sub)   // F4: Unterzeile mitlesen
    val col = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = cd },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.clearAndSetSemantics {}) { Avatar(name, size = 32, avatar = p.avatar) }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = col, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Rueckfrage "Seite teilen?" (Text des Servers, "Die Seite wird dafuer mit Leserecht geteilt.") */
@Composable
fun AddPageAskDialog(ask: CallAddAsk, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(stringResource(R.string.add_page_title), modifier = Modifier.semantics { heading() }) },
        text = {
            Column {
                Text(ask.text.resolve(), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.add_page_sub), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        dismissButton = { SagaTextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { SagaTextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.add_page_go)) } },
    )
}

/** Direkter 1:1 (bzw. foederiert): warum hier niemand dazukommen kann (move_hint des Servers) */
@Composable
fun AddHintDialog(text: UiText, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_person), modifier = Modifier.semantics { heading() }) },
        text = { Text(text.resolve(), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Kurzer Hinweis oben im Anrufbildschirm (z. B. "Neue Gruppe – der Anruf läuft dort ohne Unterbrechung weiter.") */
@Composable
fun CallToast(text: UiText, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(SagaRadius.md), modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text.resolve(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}
