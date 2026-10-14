package io.github.yggdrasil_sh.saga.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role as SemRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.Role
import io.github.yggdrasil_sh.saga.data.ShareEntry
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dialog "Teilen" (nur Eigentuemer) als Material-3-Bottom-Sheet, Aufbau wie im Web: Suche + Rolle, Erklaerzeile,
 * eigene Gruppen als Chips, "Zugriff" mit fester Zeile "Unterseiten und Einträge kommen mit.", Freigaben mit
 * tatsaechlicher Rolle, geerbte Freigaben (nur lesend), Link kopieren.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PageShareSheet(
    model: ShareModel, scope: CoroutineScope, ownerName: String, link: String,
    onDismiss: () -> Unit, onOpenParent: (Int) -> Unit, onCopied: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<ShareEntry?>(null) }
    LaunchedEffect(model) { if (model.state == null) model.load() }
    LaunchedEffect(model.query) {
        if (ShareLogic.canSearch(model.query)) {
            delay(300)
            model.search()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.share_dialog_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val st = model.state
            when {
                st == null && model.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
                st == null -> {
                    Text(
                        stringResource(R.string.share_load_failed, model.loadError?.resolve().orEmpty()),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Button(onClick = { scope.launch { model.load() } }) { Text(stringResource(R.string.action_retry)) }
                }
                else -> {
                    if (!st.enabled) Warn(stringResource(R.string.share_disabled_warn))
                    if (!st.shareable) Warn(stringResource(R.string.share_not_shareable))
                    // Suchen + Rolle
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val err = model.searchError
                        OutlinedTextField(
                            value = model.query, onValueChange = model::editQuery, singleLine = true, enabled = st.shareable && !model.busy,
                            label = { Text(stringResource(R.string.share_search)) },
                            isError = err != null,
                            supportingText = {
                                if (err != null) Text(err.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                else Text(stringResource(ShareLogic.searchHint(st)))
                            },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                            modifier = Modifier.weight(1f),
                        )
                        RolePicker(model.newRole, stringResource(R.string.share_new_role), enabled = st.shareable, modifier = Modifier.padding(top = 8.dp)) { model.newRole = it }
                    }
                    Text(stringResource(ShareLogic.roleHelp(model.newRole)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val taken = ShareLogic.taken(st)
                    val roleName = stringResource(ShareLogic.roleName(model.newRole))
                    // Treffer
                    model.hits?.takeIf { ShareLogic.canSearch(model.query) }?.let { h ->
                        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                h.users.forEach { u ->
                                    HitRow("user", u.name.ifEmpty { u.username }, UiText.Raw(u.username), taken[ShareLogic.key("user", u.username)], roleName) {
                                        scope.launch { model.add("user", u.username, u.name.ifEmpty { u.username }) }
                                    }
                                }
                                h.groups.forEach { g ->
                                    HitRow("group", g.name, UiText.Res(R.string.share_group), taken[ShareLogic.key("group", g.name)], roleName) {
                                        scope.launch { model.add("group", g.name, g.name) }
                                    }
                                }
                            }
                        }
                    }
                    // eigene Gruppen als Chips (leeres Suchfeld)
                    if (ShareLogic.chipsVisible(st, model.query)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                            Text(
                                stringResource(R.string.share_your_groups), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp),
                            )
                            st.groups.forEach { g ->
                                val has = taken[ShareLogic.key("group", g)]
                                val desc = if (has != null) stringResource(R.string.share_has_role, stringResource(ShareLogic.roleName(has)))
                                else stringResource(R.string.share_add_as, roleName)
                                FilterChip(
                                    selected = has != null, onClick = { scope.launch { model.add("group", g, g) } }, enabled = !model.busy,
                                    label = { Text("👥 $g") }, modifier = Modifier.semantics { contentDescription = "$g · $desc" },
                                )
                            }
                        }
                    }
                    if (model.busy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                    // Zugriff
                    Text(stringResource(R.string.share_access), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.share_subpages), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PersonRow(
                        "user", if (ownerName.isNotEmpty()) stringResource(R.string.share_you_suffix, ownerName) else stringResource(R.string.share_you),
                        stringResource(R.string.share_role_owner),
                    )
                    st.shares.forEach { e -> EntryRow(e, model, scope) { confirm = e } }
                    if (st.shares.isEmpty()) {
                        Text(stringResource(R.string.share_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (st.inherited.isNotEmpty()) {
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.share_inherited), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.share_inherited_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val toParent = stringResource(R.string.share_to_parent)
                        st.inherited.forEach { x ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { PersonRow(x.type, x.name.ifEmpty { x.target }, ShareLogic.entrySub(x.type, x.target, null).resolve()) }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(stringResource(ShareLogic.roleName(Role.of(x.role))), style = MaterialTheme.typography.labelMedium)
                                    TextButton(
                                        onClick = { onOpenParent(x.noteId) },
                                        modifier = Modifier.semantics { contentDescription = "$toParent: ${x.title}" },
                                    ) { Text(stringResource(R.string.share_from, x.title), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                    model.status?.let {
                        Text(it.resolve(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    // Link zur Seite (oeffnet sie fuer alle mit Zugriff)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(link, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { copy(context, link); onCopied() }) { Text(stringResource(R.string.share_copy_link)) }
                        }
                    }
                    Text(stringResource(R.string.share_link_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    confirm?.let { e ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.share_remove_title)) },
            text = { Text(ShareLogic.removeText(e).resolve()) },
            confirmButton = {
                TextButton(onClick = { confirm = null; scope.launch { model.remove(e) } }) {
                    Text(stringResource(R.string.share_remove_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Saga", text))
}

@Composable
private fun Warn(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

/** Rund: Anfangsbuchstabe bzw. 👥 fuer Gruppen */
@Composable
private fun Avatar(type: String, name: String) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (type == "group") "👥" else name.trim().take(1).uppercase().ifEmpty { "?" },
            color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun PersonRow(type: String, name: String, sub: String, extra: String? = null) {
    Row(Modifier.heightIn(min = 48.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(type, name)
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic) }
        }
    }
}

/** Treffer der Suche: antippen fuegt mit der gewaehlten Rolle hinzu */
@Composable
private fun HitRow(type: String, name: String, sub: UiText, has: Role?, roleName: String, onClick: () -> Unit) {
    val trailing = if (has != null) stringResource(R.string.share_has) else stringResource(R.string.share_add_as, roleName)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { role = SemRole.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { PersonRow(type, name, sub.resolve()) }
        Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Eine Freigabe: Name, Zweitzeile, tatsaechliche Rolle, Rolle aendern, entfernen (48 dp) */
@Composable
private fun EntryRow(e: ShareEntry, model: ShareModel, scope: CoroutineScope, onRemove: () -> Unit) {
    val top = ShareLogic.topVia(e)
    val via = top?.let { stringResource(R.string.share_via, ShareLogic.viaLabel(it).resolve(), stringResource(ShareLogic.roleName(Role.of(it.role)))) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PersonRow(e.type, e.name.ifEmpty { e.target }, ShareLogic.entrySub(e.type, e.target, e.members).resolve(), via) }
            RolePicker(Role.of(e.role), stringResource(R.string.share_role_of, e.name)) { r -> scope.launch { model.changeRole(e, r) } }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.share_remove_for, e.name)) }
        }
        model.itemErrors[ShareLogic.key(e.type, e.target)]?.let {
            Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 48.dp).semantics { liveRegion = LiveRegionMode.Assertive })
        }
    }
}

/** Rolle waehlen: Knopf mit Menue (Lesen / Kommentieren / Bearbeiten) */
@Composable
private fun RolePicker(current: Role, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onPick: (Role) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = stringResource(ShareLogic.roleName(current))
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true }, enabled = enabled,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 8.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label: $name" },
        ) {
            Text(name, maxLines = 1)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ShareLogic.ROLES.forEach { r ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(ShareLogic.roleName(r)))
                            Text(stringResource(ShareLogic.roleHelp(r)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; if (r != current) onPick(r) },
                    modifier = Modifier.width(300.dp),
                )
            }
        }
    }
}
