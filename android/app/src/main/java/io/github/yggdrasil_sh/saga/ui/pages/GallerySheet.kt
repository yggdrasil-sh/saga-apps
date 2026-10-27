package io.github.yggdrasil_sh.saga.ui.pages

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.Gallery
import io.github.yggdrasil_sh.saga.data.GalleryItem
import io.github.yggdrasil_sh.saga.data.GalleryLoad
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.pageTitle
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 2.5: "Aus Vorlage ..." (SPEC-galerie §5) - Liste der Galerie-Vorlagen gruppiert nach Kategorie, Antippen ->
 * Bestaetigung mit "Mit Beispielen" (und, falls eine Seite offen ist, "Unter ... anlegen") -> anlegen und Root-Seite oeffnen.
 * parent: feste Elternseite (Langdruck "Aus Vorlage ..." an einer Seite); current: offene Seite, die als Elternseite
 * angeboten wird (Kopf-Menue "Neu").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GallerySheet(
    repo: SagaRepository,
    parent: PageMeta?,
    current: PageMeta?,
    onDismiss: () -> Unit,
    onCreated: (Long) -> Unit,
) {
    val server = repo.settings.server.value
    val lang = remember { repo.galleryLang() }
    var items by remember { mutableStateOf(repo.gallery.cached(server, lang)) }
    var loadError by remember { mutableStateOf<UiText?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf<GalleryItem?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(reload) {
        loadError = null
        when (val r = repo.galleryList()) {
            is GalleryLoad.Ok -> items = r.items
            GalleryLoad.Unsupported -> {
                repo.notify(UiText.Res(R.string.gal_unsupported))
                onDismiss()
            }
            is GalleryLoad.Failed -> if (items == null) loadError = r.text
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(R.string.gal_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 4.dp).semantics { heading() },
            )
            Text(
                if (parent != null) stringResource(R.string.gal_sub_under, pageTitle(parent)) else stringResource(R.string.gal_sub),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val list = items
            when {
                list == null && loadError == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                list == null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.gal_load_failed, loadError?.resolve().orEmpty()), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Button(onClick = { reload++ }) { Text(stringResource(R.string.action_retry)) }
                }
                list.isEmpty() -> Text(
                    stringResource(R.string.gal_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> {
                    val groups = remember(list) { Gallery.groups(list) }
                    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                        for (g in groups) {
                            item(key = "h-" + g.category) {
                                Text(
                                    stringResource(categoryLabel(g.category)), style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
                                )
                            }
                            items(g.items, key = { "i-" + it.id }) { t -> GalleryRow(t) { chosen = t } }
                        }
                    }
                }
            }
        }
    }

    chosen?.let { t ->
        GalleryConfirm(
            repo, t, parent = parent, current = current,
            onCancel = { chosen = null },
            onGone = { chosen = null; reload++ },
            onCreated = { id ->
                chosen = null
                repo.notify(UiText.Res(R.string.gal_created))
                onCreated(id)
            },
        )
    }
}

private fun categoryLabel(c: String): Int = when (c) {
    "personal" -> R.string.gal_cat_personal
    "work" -> R.string.gal_cat_work
    "team" -> R.string.gal_cat_team
    else -> R.string.gal_cat_other
}

@Composable
private fun GalleryRow(t: GalleryItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(t.icon.ifEmpty { "📄" }, fontSize = 22.sp, maxLines = 1)
            }
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.desc.isNotBlank()) {
                Text(
                    t.desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GalleryConfirm(
    repo: SagaRepository, t: GalleryItem, parent: PageMeta?, current: PageMeta?,
    onCancel: () -> Unit, onGone: () -> Unit, onCreated: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var examples by remember { mutableStateOf(true) }
    // offene Seite als Elternseite nur anbieten (Standard: oberste Ebene); feste Elternseite aus dem Langdruck-Menue
    var underCurrent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        icon = { Text(t.icon.ifEmpty { "📄" }, fontSize = 28.sp) },
        title = { Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.desc.isNotBlank()) Text(t.desc, style = MaterialTheme.typography.bodyMedium)
                if (t.contents.isNotEmpty()) {
                    Text(
                        stringResource(R.string.gal_contains, t.contents.joinToString(", ") { (it.icon + " " + it.title).trim() }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (t.needs.isNotEmpty()) {
                    Text(stringResource(R.string.gal_needs_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SwitchRow(stringResource(R.string.gal_examples), examples, !busy) { examples = it }
                if (parent != null) {
                    Text(
                        stringResource(R.string.gal_sub_under, pageTitle(parent)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                } else if (current != null) {
                    SwitchRow(stringResource(R.string.gal_under_current, pageTitle(current)), underCurrent, !busy) { underCurrent = it }
                }
                error?.let {
                    Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                error = null
                val p = parent?.id ?: current?.id?.takeIf { underCurrent }
                scope.launch {
                    try {
                        onCreated(repo.galleryUse(t.id, p, examples))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is io.github.yggdrasil_sh.saga.data.ApiException && e.code == 404) {
                            repo.notify(UiText.Res(R.string.gal_error_gone))
                            onGone()
                        } else {
                            error = Gallery.friendlyUse(e, repo::friendly)
                        }
                    } finally {
                        busy = false
                    }
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.gal_use))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .semantics { toggleableState = ToggleableState(checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
