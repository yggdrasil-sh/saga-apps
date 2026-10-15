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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.Publish
import io.github.yggdrasil_sh.saga.ui.common.openUrl
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Eingabe fuer Namen und Adressen: klein, ohne Autokorrektur/Grossschreibung */
internal val SLUG_KEYS = KeyboardOptions(
    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
)

/**
 * Dialog "Veroeffentlichen" als Material-3-Bottom-Sheet. scope = Bereich der Seite (Anfragen laufen weiter,
 * wenn das Sheet waehrenddessen geschlossen wird).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishSheet(model: PublishModel, scope: CoroutineScope, onDismiss: () -> Unit, onOpenPage: (Long) -> Unit) {
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

    // Rueckfragen einheitlich (alle Plattformen): Titel, ein Satz, Knopf + Abbrechen
    if (confirmStop) {
        ConfirmDialog(
            title = stringResource(R.string.publish_stop_title),
            text = stringResource(R.string.publish_stop_text),
            confirm = stringResource(R.string.publish_stop_button), destructive = true,
            onConfirm = { confirmStop = false; scope.launch { model.stop() } },
            onDismiss = { confirmStop = false },
        )
    }
    if (confirmSlug) {
        ConfirmDialog(
            title = stringResource(R.string.publish_address_title),
            text = stringResource(R.string.publish_address_confirm, Publish.breakable(model.state?.url.orEmpty())),
            confirm = stringResource(R.string.publish_change_address),
            onConfirm = { confirmSlug = false; scope.launch { model.changeSlug() } },
            onDismiss = { confirmSlug = false },
        )
    }
}

/** Material-3-Rueckfrage mit Titel (auch Einstellungen -> Oeffentlicher Name) */
@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Schritt 1: oeffentlichen Namen waehlen (Vorschlag, Pruefung beim Tippen; Fehler ersetzt den Hilfetext am Feld) */
@Composable
private fun ColumnScope.NameStep(model: PublishModel, scope: CoroutineScope) {
    LaunchedEffect(model.nameInput) {
        if (model.nameStatus == NameStatus.Checking) {
            delay(250)
            model.checkName()
        }
    }
    val bad = model.nameStatus as? NameStatus.Bad
    Text(stringResource(R.string.publish_name_intro), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = model.nameInput, onValueChange = model::editName, singleLine = true, enabled = !model.busy,
        label = { Text(stringResource(R.string.publish_public_name)) },
        isError = bad != null,
        supportingText = {
            if (bad != null) FieldError(bad.text.resolve())
            else Text(stringResource(R.string.publish_name_rules))
        },
        keyboardOptions = SLUG_KEYS,
        keyboardActions = KeyboardActions(onDone = { if (model.canContinue) scope.launch { model.confirmName() } }),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(Publish.breakable(model.namePreview), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    if (bad == null) NameStatusText(model.nameStatus)
    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(onClick = { scope.launch { model.confirmName() } }, enabled = model.canContinue, modifier = Modifier.align(Alignment.End)) {
        Text(stringResource(R.string.publish_continue))
    }
}

/** Fehlertext unter einem Feld (supportingText) - TalkBack sagt ihn an */
@Composable
internal fun FieldError(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** "✓ Frei" / Meldung des Servers / "Wird geprüft …" - TalkBack sagt Aenderungen an */
@Composable
internal fun NameStatusText(s: NameStatus, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val (text, color) = when (s) {
        NameStatus.Idle -> return
        NameStatus.Checking -> stringResource(R.string.publish_name_checking) to MaterialTheme.colorScheme.onSurfaceVariant
        NameStatus.Free -> stringResource(R.string.publish_name_free) to MaterialTheme.colorScheme.primary
        NameStatus.Saved -> stringResource(R.string.publish_saved) to MaterialTheme.colorScheme.primary
        is NameStatus.Bad -> s.text.resolve() to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = style, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** Schritt 2 in fester Reihenfolge: 1 Schalter, 2 Link-Karte, 3 Adresse, 4 Optionen, 5 "Veröffentlichung beenden" */
@Composable
private fun ColumnScope.MainStep(model: PublishModel, scope: CoroutineScope, onOpenPage: (Long) -> Unit, onStop: () -> Unit, onChangeSlug: () -> Unit) {
    val v = model.view ?: return
    val context = LocalContext.current
    val busy = model.busy

    if (v.disabledWarning) {
        Text(stringResource(R.string.publish_disabled_admin), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    v.via?.let { via -> ViaCard(via.title, via.url, onCopy = { copyLink(context, via.url); model.linkCopied() }, onOpen = { onOpenPage(via.noteId) }) }
    v.withdrawnAt?.let { at ->
        val ms = (at * 1000).toLong()
        val when_ = remember(ms) { DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR) }
        Text(
            stringResource(if (v.withdrawnByAdmin) R.string.publish_withdrawn_admin else R.string.publish_withdrawn_user, when_),
            style = MaterialTheme.typography.bodySmall,
            color = if (v.withdrawnByAdmin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 1 Hauptschalter: an = veroeffentlichen, aus = beenden (mit Rueckfrage)
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

    // 2 Link-Karte: oeffentlich aktiv, privat die kuenftige Adresse gedaempft
    v.linkUrl?.let { url ->
        LinkCard(
            url, v.linkActive, v.profileAddress,
            onCopy = { u -> copyLink(context, u); model.linkCopied() },
            onOpen = { openUrl(context, url) },
            onShare = { shareLink(context, url) },
        )
    }

    if (v.showSettings) {
        // 3 Adresse: Aenderung bei veroeffentlichter Seite nur ueber den eigenen Knopf (mit Rueckfrage)
        val slugError = model.slugError
        OutlinedTextField(
            value = model.slugInput, onValueChange = model::editSlug, singleLine = true, enabled = !busy,
            label = { Text(stringResource(R.string.publish_address)) },
            prefix = { Text("/@${v.name}/", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            isError = slugError != null,
            supportingText = when {
                slugError != null -> ({ FieldError(slugError.resolve()) })
                v.showChangeAddress -> ({ Text(stringResource(R.string.publish_address_pending)) })
                else -> null
            },
            keyboardOptions = SLUG_KEYS,
            keyboardActions = KeyboardActions(onDone = { if (v.changeAddressEnabled) onChangeSlug() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (v.showChangeAddress) {
            OutlinedButton(onClick = onChangeSlug, enabled = v.changeAddressEnabled && !busy, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.publish_change_address))
            }
        }

        // 4 Optionen (Schalter); solange privat mit Ueberschrift "Gilt, sobald die Seite öffentlich ist"
        Column {
            if (v.optionsPending) {
                Text(
                    stringResource(R.string.publish_options_pending), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp).semantics { heading() },
                )
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
    // 5 Beenden
    if (v.showStop) {
        OutlinedButton(
            onClick = onStop, enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.publish_stop)) }
    }
}

/** Oeffentlich ueber eine Elternseite: deren Link, Kopieren und Oeffnen */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViaCard(title: String, url: String, onCopy: () -> Unit, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val t = title.ifEmpty { stringResource(R.string.page_untitled) }
            Text(stringResource(R.string.publish_via_text, t), color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(Publish.breakable(url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.publish_copy_link)) }
                TextButton(onClick = onOpen) { Text(stringResource(R.string.publish_open_title, t)) }
            }
            Text(stringResource(R.string.publish_via_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/**
 * Link-Karte: aktiv = Link mit Kopieren, Oeffnen, Teilen (+ Profiladresse mit Kopieren); inaktiv = kuenftige
 * Adresse gedaempft, ohne Knoepfe. Angezeigt mit Umbruch nur nach "/", kopiert wird die Original-Adresse.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkCard(url: String, active: Boolean, profile: String?, onCopy: (String) -> Unit, onOpen: () -> Unit, onShare: () -> Unit) {
    // durchgehend getoente Flaeche; privat nur die Schrift gedaempft (Kontrast bleibt lesbar)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                Publish.breakable(url), style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { onCopy(url) }) { Text(stringResource(R.string.publish_copy_link)) }
                    OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.publish_open)) }
                    OutlinedButton(onClick = onShare) { Text(stringResource(R.string.publish_share)) }
                }
            }
            if (profile != null) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.publish_profile_address), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Publish.breakable(profile), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { onCopy(profile) }) { Text(stringResource(R.string.publish_copy_link)) }
                }
            }
        }
    }
}

/** Option mit Erklaerung und Schalter: ganze Zeile antippbar (mind. 48 dp), TalkBack liest Name + Zustand */
@Composable
private fun OptionRow(label: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
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
