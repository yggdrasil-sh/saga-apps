package io.github.yggdrasil_sh.saga.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ai.AiReportLogic
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.imeDialog
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Melden …" unter einer KI-Ausgabe (2.6, Bugs-DB 2507, Apple 1.2 / Play-Richtlinie KI-Inhalte): unauffaelliger
 * Textknopf (48 dp); je Ausgabe eine Meldung, danach "Gemeldet" (bleibt fokussierbar). key = Ausgabe (gleiche Kennung =
 * schon gemeldet, auch nach Neuzeichnen).
 */
@Composable
fun AiReportButton(repo: SagaRepository, target: AiReportLogic.Target, modifier: Modifier = Modifier, key: String = target.where.wire + ":" + target.id + ":" + target.text.hashCode()) {
    if (!AiReportLogic.ok(target)) return
    var open by remember(key) { mutableStateOf(false) }
    var sent by rememberSaveable(key) { mutableStateOf(false) }
    val doneLabel = stringResource(R.string.ai_reported)
    SagaTextButton(
        onClick = { if (!sent) open = true },
        modifier = modifier.heightIn(min = 48.dp).semantics { if (sent) stateDescription = doneLabel },
    ) {
        Text(stringResource(if (sent) R.string.ai_reported else R.string.ai_report_btn), style = MaterialTheme.typography.labelLarge)
    }
    if (open) AiReportDialog(repo, target, onSent = { sent = true; open = false }) { open = false }
}

/**
 * Dialog wie im Web (HK.report): Grund, Schalter "Antwort der KI mitschicken" (an), Textfeld mit der Antwort (aenderbar,
 * hoechstens 2000 Zeichen), Hinweis, dass die Meldung an die Admins dieses Servers geht. Bleibt beim Senden offen: bei
 * einem Fehler (429, Netz) steht die Meldung da und der Text bleibt; erst nach ok schliesst er.
 */
@Composable
fun AiReportDialog(repo: SagaRepository, target: AiReportLogic.Target, onSent: () -> Unit, onDismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("spam") }
    var include by rememberSaveable { mutableStateOf(true) }
    var text by rememberSaveable { mutableStateOf(AiReportLogic.prefill(target.text)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = io.github.yggdrasil_sh.saga.ui.common.ImeDialog.properties,
        modifier = Modifier.imeDialog(),
        title = { Text(stringResource(R.string.ai_report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ai_report_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.std_report_reason), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Column(Modifier.selectableGroup()) {
                    AiReportLogic.REASONS.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(reason == r, role = Role.RadioButton) { reason = r },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reason == r, onClick = null)
                            Text(
                                stringResource(
                                    when (r) {
                                        "spam" -> R.string.std_reason_spam
                                        "abuse" -> R.string.std_reason_abuse
                                        "illegal" -> R.string.std_reason_illegal
                                        else -> R.string.std_reason_other
                                    },
                                ),
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(include, role = Role.Checkbox) { include = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = include, onCheckedChange = null)
                    Text(stringResource(R.string.ai_report_include), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                }
                if (include) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it.take(AiReportLogic.TEXT_MAX) }, minLines = 3, maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.std_report_edit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let {
                    Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        },
        confirmButton = {
            SagaTextButton(onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        repo.ai.report(target, reason, if (include) text else null)
                        repo.notify(UiText.Res(R.string.std_report_done))
                        onSent()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = repo.friendly(e)
                    } finally {
                        busy = false
                    }
                }
            }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.std_report), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { SagaTextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
    )
}
