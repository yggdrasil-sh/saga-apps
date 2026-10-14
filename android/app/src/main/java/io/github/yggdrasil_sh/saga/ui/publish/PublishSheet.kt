package io.github.yggdrasil_sh.saga.ui.publish

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.common.openUrl
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Eingabe fuer Namen und Adressen: klein, ohne Autokorrektur/Grossschreibung */
private val SLUG_KEYS = KeyboardOptions(
    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
)

/**
 * Dialog "Veroeffentlichen" als Material-3-Bottom-Sheet. scope = Bereich der Seite (Anfragen laufen weiter,
 * wenn das Sheet waehrenddessen geschlossen wird).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishSheet(model: PublishModel, scope: CoroutineScope, onDismiss: () -> Unit, onOpenPage: (Int) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmStop by remember { mutableStateOf(false) }
    var confirmSlug by remember { mutableStateOf(false) }
    LaunchedEffect(model) { if (model.state == null) model.load() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_public), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.publish_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            val st = model.state
            when {
                st == null && model.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
                st == null -> {
                    Text(
                        stringResource(R.string.publish_load_failed, model.loadError?.resolve().orEmpty()),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (!model.unsupported) Button(onClick = { scope.launch { model.load() } }) { Text(stringResource(R.string.action_retry)) }
                }
                model.step == PublishStep.NAME -> NameStep(model, scope)
                else -> MainStep(model, scope, onOpenPage, onStop = { confirmStop = true }, onChangeSlug = { confirmSlug = true })
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            text = { Text(stringResource(R.string.publish_stop_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; scope.launch { model.stop() } }) {
                    Text(stringResource(R.string.publish_stop), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmSlug) {
        AlertDialog(
            onDismissRequest = { confirmSlug = false },
            text = { Text(stringResource(R.string.publish_address_confirm, model.state?.url.orEmpty())) },
            confirmButton = {
                TextButton(onClick = { confirmSlug = false; scope.launch { model.changeSlug() } }) { Text(stringResource(R.string.publish_change_address)) }
            },
            dismissButton = { TextButton(onClick = { confirmSlug = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Schritt 1: oeffentlichen Namen waehlen (Vorschlag, Pruefung beim Tippen) */
@Composable
private fun ColumnScope.NameStep(model: PublishModel, scope: CoroutineScope) {
    LaunchedEffect(model.nameInput) {
        if (model.nameStatus == NameStatus.Checking) {
            delay(250)
            model.checkName()
        }
    }
    Text(stringResource(R.string.publish_name_intro), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = model.nameInput, onValueChange = model::editName, singleLine = true, enabled = !model.busy,
        label = { Text(stringResource(R.string.publish_public_name)) },
        isError = model.nameStatus is NameStatus.Bad,
        keyboardOptions = SLUG_KEYS,
        keyboardActions = KeyboardActions(onDone = { if (model.canContinue) scope.launch { model.confirmName() } }),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(model.namePreview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    NameStatusText(model.nameStatus)
    Text(stringResource(R.string.publish_name_rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(onClick = { scope.launch { model.confirmName() } }, enabled = model.canContinue, modifier = Modifier.align(Alignment.End)) {
        Text(stringResource(R.string.publish_continue))
    }
}

/** "✓ Frei" / Meldung des Servers / "Wird geprüft …" - TalkBack sagt Aenderungen an */
@Composable
internal fun NameStatusText(s: NameStatus) {
    val (text, color) = when (s) {
        NameStatus.Idle -> return
        NameStatus.Checking -> stringResource(R.string.publish_name_checking) to MaterialTheme.colorScheme.onSurfaceVariant
        NameStatus.Free -> stringResource(R.string.publish_name_free) to MaterialTheme.colorScheme.primary
        NameStatus.Saved -> stringResource(R.string.publish_saved) to MaterialTheme.colorScheme.primary
        is NameStatus.Bad -> s.text.resolve() to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** Schritt 2: Schalter, Adresse, Optionen, Link */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.MainStep(model: PublishModel, scope: CoroutineScope, onOpenPage: (Int) -> Unit, onStop: () -> Unit, onChangeSlug: () -> Unit) {
    val v = model.view ?: return
    val context = LocalContext.current
    val busy = model.busy

    if (v.disabledWarning) {
        Text(stringResource(R.string.publish_disabled_admin), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    v.via?.let { via ->
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val title = via.title.ifEmpty { stringResource(R.string.page_untitled) }
                Text(stringResource(R.string.publish_via_text, title), color = MaterialTheme.colorScheme.onSecondaryContainer)
                SelectionContainer { Text(via.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { copyLink(context, via.url); model.linkCopied() }) { Text(stringResource(R.string.publish_copy_link)) }
                    TextButton(onClick = { onOpenPage(via.noteId) }) { Text(stringResource(R.string.publish_open_title, title)) }
                }
                Text(stringResource(R.string.publish_via_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
    v.withdrawnAt?.let { at ->
        val ms = (at * 1000).toLong()
        val when_ = remember(ms) { DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR) }
        Text(
            stringResource(if (v.withdrawnByAdmin) R.string.publish_withdrawn_admin else R.string.publish_withdrawn_user, when_),
            style = MaterialTheme.typography.bodySmall,
            color = if (v.withdrawnByAdmin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Hauptschalter: an = veroeffentlichen, aus = beenden (mit Rueckfrage)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
            value = v.switchOn, enabled = v.switchEnabled && !busy, role = Role.Switch,
            onValueChange = { on -> if (on) scope.launch { model.setPublic(true) } else onStop() },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(v.switchLabel), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.publish_public_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = v.switchOn, onCheckedChange = null, enabled = v.switchEnabled && !busy)
    }

    // Adresse: Aenderung bei veroeffentlichter Seite nur ueber den eigenen Knopf (mit Rueckfrage)
    OutlinedTextField(
        value = model.slugInput, onValueChange = model::editSlug, singleLine = true, enabled = !busy,
        label = { Text(stringResource(R.string.publish_address)) },
        prefix = { Text("/@${v.name}/", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        keyboardOptions = SLUG_KEYS,
        keyboardActions = KeyboardActions(onDone = { if (v.changeAddressEnabled) onChangeSlug() }),
        modifier = Modifier.fillMaxWidth(),
    )
    when {
        v.addressPending -> Text(stringResource(R.string.publish_address_pending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        v.preview != null -> Text(v.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    if (v.showChangeAddress) {
        OutlinedButton(onClick = onChangeSlug, enabled = v.changeAddressEnabled && !busy, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.publish_change_address))
        }
    }

    OptionRow(stringResource(R.string.publish_children), stringResource(R.string.publish_children_hint), model.children, !busy) {
        scope.launch { model.setOption("children", it) }
    }
    OptionRow(stringResource(R.string.publish_indexable), stringResource(R.string.publish_indexable_hint), model.indexable, !busy) {
        scope.launch { model.setOption("indexable", it) }
    }
    OptionRow(stringResource(R.string.publish_profile), v.profileHint.resolve(), model.profile, !busy) {
        scope.launch { model.setOption("profile", it) }
    }

    v.link?.let { url ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { copyLink(context, url); model.linkCopied() }) { Text(stringResource(R.string.publish_copy_link)) }
                    OutlinedButton(onClick = { openUrl(context, url) }) { Text(stringResource(R.string.publish_open)) }
                    OutlinedButton(onClick = { shareLink(context, url) }) { Text(stringResource(R.string.publish_share)) }
                }
            }
        }
    }
    if (v.home) Text(stringResource(R.string.publish_home), style = MaterialTheme.typography.bodyMedium)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    model.status?.let { s ->
        Text(
            s.text.resolve(), style = MaterialTheme.typography.bodyMedium,
            color = when (s.kind) {
                StatusKind.OK -> MaterialTheme.colorScheme.primary
                StatusKind.ERROR -> MaterialTheme.colorScheme.error
                StatusKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Text(stringResource(R.string.publish_name_footer, v.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (v.showStop) {
        OutlinedButton(
            onClick = onStop, enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.publish_stop)) }
    }
}

/** Option mit Erklaerung: ganze Zeile antippbar (mind. 48 dp), TalkBack liest Name + Zustand */
@Composable
private fun OptionRow(label: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun copyLink(context: Context, url: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Saga", url))
}

/** Android-Teilen-Menue (ACTION_SEND, Text = Link) */
fun shareLink(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.publish_share)))
    } catch (_: ActivityNotFoundException) {
    }
}
