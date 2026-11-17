package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.CoverAbout
import io.github.yggdrasil_sh.saga.data.CoverGallery
import io.github.yggdrasil_sh.saga.data.CoverImage
import io.github.yggdrasil_sh.saga.data.CoverListing
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.util.UiText

// 2.5 Titelbild-Galerie (wie im Web: Titelbild -> "Galerie …" / "Zufällig" / "Über dieses Bild"). Liste GET api/covers
// (einmal je Server, beim Oeffnen frisch), Setzen PATCH cover + empfohlene cover_pos. Aelterer Server (404): Eintraege
// ausgeblendet. Logik in data/CoverGallery.kt.

/** Bietet der Server die Titelbild-Galerie an? (Liste fuer Server/Sprache geladen; einmal je Server im Hintergrund geprueft) */
@Composable
internal fun rememberCoverGalleryOffered(repo: SagaRepository): Boolean {
    val server by repo.server.collectAsState()
    val lists by repo.coverGallery.lists.collectAsState()
    val unsupported by repo.coverGallery.unsupported.collectAsState()
    LaunchedEffect(server) {
        if (server.isNotEmpty() && server !in repo.coverGallery.unsupported.value && repo.coverGalleryCached() == null) repo.coverGalleryList()
    }
    return server.isNotEmpty() && server !in unsupported && lists.containsKey(repo.coverGallery.key(server, repo.galleryLang()))
}

/** Galerie-Bild als Titelbild: sofort zeigen und speichern; schon gesetzt -> nichts speichern */
internal fun setGalleryCover(ed: PageEditor, repo: SagaRepository, c: CoverImage) {
    val f = CoverGallery.fields(c, ed.cover) ?: return
    repo.launchAction(onError = { repo.notify(repo.friendly(it)) }) { ed.setHeader(f) }
}

/** "Zufällig" wie im Web: ein anderes sichtbares Galerie-Bild samt empfohlener Position */
internal fun randomGalleryCover(ed: PageEditor, repo: SagaRepository, listing: CoverListing? = null) {
    repo.launchAction(onError = { repo.notify(repo.friendly(it)) }) {
        val l = listing ?: repo.coverGalleryCached() ?: repo.coverGalleryList() ?: return@launchAction
        val c = CoverGallery.random(l, ed.isRemote, ed.cover)
        val f = c?.let { CoverGallery.fields(it, ed.cover) }
        if (f == null) repo.notify(UiText.Res(R.string.cover_gallery_empty)) else ed.setHeader(f)
    }
}

/**
 * Auswahl nach Gruppen (Raster). [onPhoto]: eigenes Bild hochladen (beim Hinzufuegen eines Titelbilds, damit dieser
 * Weg ueber die Galerie erreichbar bleibt). [preview]: nur fuer Bilder (Debug) - Liste fest, nichts laden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoverGallerySheet(
    ed: PageEditor, repo: SagaRepository, onDismiss: () -> Unit, onPhoto: (() -> Unit)? = null,
    preview: CoverListing? = null, onPick: ((CoverImage) -> Unit)? = null,
) {
    var listing by remember { mutableStateOf(preview ?: repo.coverGalleryCached()) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(reload) {
        if (preview != null) return@LaunchedEffect
        failed = false
        val l = repo.coverGalleryList()
        if (l != null) listing = l
        else if (repo.server.value in repo.coverGallery.unsupported.value) onDismiss()       // aelterer Server: nichts anbieten
        else failed = listing == null
    }
    val choose: (CoverImage) -> Unit = { c ->
        onDismiss()
        if (onPick != null) onPick(c) else setGalleryCover(ed, repo, c)
    }
    val other = stringResource(R.string.cover_gallery_other)
    val big = LocalDensity.current.fontScale >= 1.5f
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(R.string.cover_gallery_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 4.dp).semantics { heading() },
            )
            val l = listing
            val groups = remember(l, other) { l?.let { CoverGallery.grouped(it, ed.isRemote, other) } }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SagaTextButton(
                    onClick = { l?.let { CoverGallery.random(it, ed.isRemote, ed.cover) }?.let(choose) },
                    enabled = !groups.isNullOrEmpty(), modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    SagaIcon(SagaIcons.refresh, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.cover_random), Modifier.padding(start = 6.dp))
                }
                if (onPhoto != null) SagaTextButton(onClick = { onDismiss(); onPhoto() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    SagaIcon(SagaIcons.upload, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.cover_upload), Modifier.padding(start = 6.dp))
                }
            }
            when {
                groups == null && failed -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.cover_gallery_failed), color = MaterialTheme.colorScheme.error)
                    SagaButton(onClick = { reload++ }) { Text(stringResource(R.string.action_retry)) }
                }
                groups == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                groups.isEmpty() -> Text(
                    stringResource(R.string.cover_gallery_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyVerticalGrid(
                    GridCells.Adaptive(if (big) 260.dp else 150.dp), Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (g in groups) {
                        item(key = "h-" + g.id, span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                g.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
                            )
                        }
                        items(g.covers, key = { "c-" + it.id }) { c -> CoverTile(repo, c, on = ed.cover == c.url, big = big) { choose(c) } }
                    }
                    if (l != null && l.source.isNotEmpty()) item(key = "src", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.cover_gallery_source, l.source), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverTile(repo: SagaRepository, c: CoverImage, on: Boolean, big: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(SagaRadius.md)
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.clip(shape).background(cs.surfaceContainerLow)
            .border(if (on) 3.dp else 1.dp, if (on) cs.primary else cs.outlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            // TalkBack: eine Einheit "Titel – Kuenstler, Datum", ausgewaehlt wie im Web markiert
            .semantics(mergeDescendants = true) { contentDescription = c.label; selected = on },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(7f / 4f).background(cs.surfaceContainerHigh)) {
            AsyncImage(
                model = repo.absoluteUrl(c.thumb), contentDescription = null, contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, (c.pos / 50.0 - 1.0).toFloat()), modifier = Modifier.matchParentSize(),
            )
            if (on) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(cs.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { SagaIcon(SagaIcons.check, null, Modifier.size(16.dp), tint = cs.onPrimary) }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = if (big) 3 else 1, overflow = TextOverflow.Ellipsis)
            if (c.credit.isNotEmpty()) Text(
                c.credit, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                maxLines = if (big) 2 else 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Über dieses Bild" wie im Web: Titel, Nachweis, Quelle; nicht mehr in der Auswahl -> Hinweis */
@Composable
internal fun CoverAboutDialog(about: CoverAbout, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cover_about)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (about) {
                    is CoverAbout.Image -> {
                        val c = about.cover
                        Text(c.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        if (c.credit.isNotEmpty()) Text(c.credit)
                        if (c.source.isNotEmpty()) Text(c.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (c.sourceUrl.isNotEmpty()) SagaTextButton(onClick = { io.github.yggdrasil_sh.saga.ui.common.openUrl(context, c.sourceUrl) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.cover_source_link))
                        }
                    }
                    else -> Text(stringResource(R.string.cover_about_gone), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}
