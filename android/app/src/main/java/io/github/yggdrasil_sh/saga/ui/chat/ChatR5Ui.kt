package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import io.github.yggdrasil_sh.saga.chat.ListPreviewLogic
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.settings.SettingsCategoryRow
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton

/** CH-07: Einstellungen > Chats - "Vorschau in der Chat-Liste" mit aktuellem Wert; Auswahl als Radio-Gruppe */
@Composable
fun ListPreviewSettingRow(repo: SagaRepository, modifier: Modifier = Modifier) {
    val mode by repo.chat.listPreviewMode.collectAsState()
    var open by remember { mutableStateOf(false) }
    SettingsCategoryRow(SagaIcons.message, stringResource(R.string.r5_pv_setting), modifier, summary = stringResource(listPreviewModeLabel(mode))) { open = true }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.r5_pv_setting)) },
        text = {
            Column(Modifier.selectableGroup()) {
                ListPreviewLogic.Mode.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = m == mode, role = Role.RadioButton) { repo.chat.setListPreviewMode(m); open = false },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m == mode, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(listPreviewModeLabel(m)))
                            Text(
                                stringResource(if (m == ListPreviewLogic.Mode.TEXT) R.string.r5_pv_mode_text_hint else R.string.r5_pv_mode_kind_hint),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { SagaTextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) } },
    )
}

fun listPreviewModeLabel(m: ListPreviewLogic.Mode): Int = when (m) {
    ListPreviewLogic.Mode.TEXT -> R.string.r5_pv_mode_text
    ListPreviewLogic.Mode.KIND -> R.string.r5_pv_mode_kind
}
