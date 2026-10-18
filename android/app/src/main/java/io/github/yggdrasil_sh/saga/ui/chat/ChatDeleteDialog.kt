package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatDeleteLogic
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.UiText

/**
 * "Chat löschen …" (2.1): Nur für mich / Für alle (je nach delete_for_all, sonst ausgegraut mit Begruendung),
 * bei freien Gruppen "Gruppe außerdem verlassen", deutliche Warnung. perform(forAll, leave) loescht; danach Hinweis
 * und onDone (Unterhaltung schliessen).
 */
@Composable
fun DeleteChatDialog(
    repo: SagaRepository,
    chat: ChatSummary,
    name: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    perform: suspend (forAll: Boolean, leave: Boolean) -> Unit,
) {
    val allMode = ChatDeleteLogic.forAll(chat)
    var forAll by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val peer = chat.peer?.let { p -> p.name.ifBlank { p.username } }?.ifBlank { null } ?: name.ifBlank { stringResource(R.string.chatdel_this_person) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.chatdel_title, name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.chatdel_for_whom), style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup().padding(top = 4.dp)) {
                    Choice(stringResource(R.string.chatdel_just_me), null, selected = !forAll, enabled = !busy) { forAll = false }
                    Choice(
                        stringResource(if (allMode == "history") R.string.chatdel_for_all_history else R.string.chatdel_for_all),
                        ChatDeleteLogic.forAllReason(chat)?.let { stringResource(it) },
                        selected = forAll, enabled = allMode != null && !busy,
                    ) { forAll = true }
                }
                if (ChatDeleteLogic.offerLeave(chat, forAll)) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = leave, enabled = !busy, role = Role.Checkbox) { leave = it }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = leave, onCheckedChange = null, enabled = !busy)
                        Text(stringResource(R.string.chatdel_also_leave), Modifier.padding(start = 12.dp))
                    }
                }
                val explain = ChatDeleteLogic.explain(chat, forAll)
                Text(
                    if (explain == R.string.chatdel_me_dm || explain == R.string.chatdel_all_dm) stringResource(explain, peer) else stringResource(explain),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp),
                )
                if (!forAll) {
                    Text(stringResource(R.string.chatdel_recovery_note), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    stringResource(R.string.chatdel_warning), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                val all = forAll
                val lv = leave && ChatDeleteLogic.offerLeave(chat, all)
                repo.launchAction(onError = { busy = false }) {
                    perform(all, lv)
                    repo.notify(UiText.Res(ChatDeleteLogic.doneText(chat, all, lv)))
                    onDone()
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(
                        when {
                            !forAll -> R.string.chatdel_confirm_me
                            allMode == "history" -> R.string.chatdel_for_all_history
                            else -> R.string.chatdel_confirm_all
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Choice(label: String, reason: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
