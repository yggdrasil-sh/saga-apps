package io.github.yggdrasil_sh.saga.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.AppLanguage

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Gewaehlte App-Sprache als Zustand; folgt auch Aenderungen in den Android-Einstellungen (Konfigurationswechsel) */
@Composable
private fun rememberAppLanguage(): Pair<String, (String) -> Unit> {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    var chosen by remember(config) { mutableStateOf(AppLanguage.current(context)) }
    return chosen to { tag: String ->
        chosen = tag
        context.activity()?.let { AppLanguage.set(it, tag) }
    }
}

@Composable
private fun optionLabel(tag: String): String = if (tag.isEmpty()) stringResource(R.string.language_auto) else AppLanguage.nativeName(tag)

/** Einstellungen: Zeile "Sprache" mit aktueller Wahl; Antippen oeffnet die Auswahl */
@Composable
fun LanguageRow(modifier: Modifier = Modifier) {
    val (chosen, choose) = rememberAppLanguage()
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.language)) },
        supportingContent = { Text(optionLabel(chosen)) },
        leadingContent = { Icon(painterResource(R.drawable.ic_language), contentDescription = null) },
        modifier = modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            icon = { Icon(painterResource(R.drawable.ic_language), contentDescription = null) },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    AppLanguage.OPTIONS.forEach { tag ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = tag == chosen, role = Role.RadioButton) { open = false; if (tag != chosen) choose(tag) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == chosen, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(optionLabel(tag))
                        }
                    }
                    Text(
                        stringResource(R.string.language_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

/** Anmeldebildschirm: kleiner Umschalter (Globus + Sprache in ihrer eigenen Schreibweise) - vor dem Anmelden auf fremden Geraeten */
@Composable
fun LanguageMenuButton(modifier: Modifier = Modifier) {
    val (chosen, choose) = rememberAppLanguage()
    var open by remember { mutableStateOf(false) }
    val shown = AppLanguage.normalize(chosen).ifEmpty { AppLanguage.normalize(stringResource(R.string.app_language)) }
    Box(modifier) {
        val desc = stringResource(R.string.language_change)
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "$desc: ${AppLanguage.nativeName(shown)}" }) {
            Icon(painterResource(R.drawable.ic_language), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(AppLanguage.nativeName(shown) + " ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppLanguage.OPTIONS.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(optionLabel(tag)) },
                    leadingIcon = { RadioButton(selected = tag == chosen, onClick = null) },
                    onClick = { open = false; if (tag != chosen) choose(tag) },
                )
            }
        }
    }
}
