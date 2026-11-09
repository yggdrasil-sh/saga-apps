package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.PageWidgets
import io.github.yggdrasil_sh.saga.blocks.Subpages
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaSpace
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton

// Saga 2.5 Widget-Art `subpages` ("Unterseiten"): Liste oder Galerie der direkten Unterseiten - Logik in blocks/Subpages.kt

/** Kartenbild: (Adresse ab "/", Flaeche, Rueckfall bei Ladefehler) - austauschbar fuer Bildschirmfotos ohne Server */
typealias SubpageImageSlot = @Composable (url: String, modifier: Modifier, fallback: @Composable () -> Unit) -> Unit

/** Bild ueber den Bild-Lader der App (eigener Server mit Sitzung); schlaegt das Laden fehl, bleibt der Platzhalter */
@Composable
private fun SubpageImage(repo: SagaRepository, url: String, modifier: Modifier, fallback: @Composable () -> Unit) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) fallback()
    else AsyncImage(
        model = repo.absoluteUrl(url), contentDescription = null, contentScale = ContentScale.Crop,
        onError = { failed = true }, modifier = modifier,
    )
}

/**
 * Widget im Seiten-Leser/-Editor: Werte aus GET /api/notes/{id}/widgets (W.data). Tippen oeffnet die Unterseite wie die
 * uebrigen Seitenverweise (auch Datenbanken und negative IDs foederierter Seiten). Aendern sich die Unterseiten im
 * Seitenbaum der App (angelegt, umbenannt, verschoben, geloescht), wird neu geholt.
 */
@Composable
internal fun SubpagesWidget(s: PageWidgets.Spec, widthDp: Float, v: PageWidgets.Value?, env: BlockEnv) {
    val d = remember(v?.data) { Subpages.data(v?.data) }
    if (d == null) {
        Text(stringResource(R.string.wd_no_values), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val cfg = remember(s.cfg) { Subpages.cfg(s.cfg) }
    val ed = env.editor
    val pages by env.repo.pages.collectAsState()
    val treeKey = remember(pages, ed.id) {
        pages.filter { it.parentId == ed.id && !it.archived && !it.template }.joinToString("|") { "${it.id}:${it.title}:${it.icon.orEmpty()}" }
    }
    var seenKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(treeKey) {
        if (seenKey != null && seenKey != treeKey) env.widgets.ensure(force = true)
        seenKey = treeKey
    }
    var adding by remember { mutableStateOf(false) }
    // Anlegen nur auf dem eigenen Server (foederiert meldet der Server can_add ohnehin false)
    val onAdd: (() -> Unit)? = if (!d.canAdd || ed.isRemote) null else {
        {
            if (!adding) {
                adding = true
                env.repo.launchAction(onError = { adding = false }) {
                    val id = env.repo.newPage(ed.id)
                    adding = false
                    ed.flush()
                    env.widgets.ensure(force = true)
                    env.onLink(Subpages.href(id))
                }
            }
        }
    }
    SubpagesContent(d, cfg, s.size, widthDp, env.repo, onOpen = { env.onLink(Subpages.href(it)) }, onAdd = onAdd, adding = adding)
}

/** Reine Darstellung (ohne Editor/Netz ausser dem Bild-Lader) - auch fuer Bildschirmfotos mit Beispieldaten */
@Composable
fun SubpagesContent(
    data: Subpages.Data, cfg: Subpages.Cfg, size: String, widthDp: Float, repo: SagaRepository,
    onOpen: (Long) -> Unit, onAdd: (() -> Unit)?, adding: Boolean = false,
    image: SubpageImageSlot = { url, m, fb -> SubpageImage(repo, url, m, fb) },
) {
    val cs = MaterialTheme.colorScheme
    if (data.items.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SagaIcon(SagaIcons.file, null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp).size(20.dp))
            Text(stringResource(R.string.sp_empty), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = cs.onSurface)
            if (onAdd != null) SagaOutlinedButton(onClick = onAdd, enabled = !adding, modifier = Modifier.padding(top = 2.dp)) { Text(stringResource(R.string.sp_add)) }
            else Text(stringResource(R.string.sp_empty_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        return
    }
    var limit by rememberSaveable { mutableIntStateOf(Subpages.PAGE) }
    val shown = if (data.items.size > limit) data.items.subList(0, limit) else data.items
    Column(Modifier.fillMaxWidth()) {
        if (cfg.gallery) {
            val cols = Subpages.columns(widthDp, size, cfg.image, LocalDensity.current.fontScale)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                shown.chunked(cols).forEach { row ->
                    // ohne Bildflaeche (umrandete Karten): alle Karten einer Zeile gleich hoch
                    val even = cfg.image == "none"
                    Row(
                        Modifier.fillMaxWidth().then(if (even) Modifier.height(IntrinsicSize.Min) else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(Subpages.GAP.dp), verticalAlignment = Alignment.Top,
                    ) {
                        row.forEach { x -> SubpageCard(x, cfg, size, repo, image, Modifier.weight(1f).then(if (even) Modifier.fillMaxHeight() else Modifier)) { onOpen(x.id) } }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            shown.forEach { x -> SubpageRow(x, size, repo) { onOpen(x.id) } }
        }
        if (data.items.size > limit) {
            SagaTextButton(onClick = { limit += Subpages.PAGE }) { Text(stringResource(R.string.sp_show_more)) }
        } else if (data.more > 0) {
            Text(
                stringResource(R.string.wd_more, data.more), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp),
            )
        }
    }
}

/** TalkBack: "Titel, Seite" bzw. "Titel, Datenbank" - die Karte/Zeile ist ein Element */
@Composable
private fun subpageLabel(x: Subpages.Item): String =
    stringResource(if (x.database) R.string.sp_a11y_db else R.string.sp_a11y_page, x.title.ifEmpty { stringResource(R.string.page_untitled) })

/** Karte der Galerie: Bild oben (2:3 bzw. 16:9), darunter Symbol + Titel (hoechstens 2 Zeilen); ohne Bildflaeche umrandet */
@Composable
private fun SubpageCard(
    x: Subpages.Item, cfg: Subpages.Cfg, size: String, repo: SagaRepository, image: SubpageImageSlot, modifier: Modifier, onOpen: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(SagaRadius.md)
    val label = subpageLabel(x)
    val aspect = Subpages.aspect(cfg.image)
    val frame = cs.outlineVariant.copy(alpha = 0.75f)
    val titleStyle = (if (size == "l") MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium).copy(fontWeight = FontWeight.Medium)
    Box(
        modifier.heightIn(min = SagaSpace.touch).clip(shape)
            .then(if (aspect == null) Modifier.background(cs.surfaceContainerLow).border(1.dp, frame, shape) else Modifier)
            .clickable(onClickLabel = stringResource(R.string.action_open), role = Role.Button, onClick = onOpen),
    ) {
        Column(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label }
                .then(if (aspect == null) Modifier.padding(horizontal = 12.dp, vertical = 11.dp) else Modifier),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val big: Dp = when (size) { "s" -> 36.dp; "l" -> 52.dp; else -> 44.dp }
            val placeholder: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PageIcon(x.icon, repo, x.database, size = big) }
            }
            if (aspect != null) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(aspect).clip(shape)
                        .background(cs.onSurface.copy(alpha = 0.05f).compositeOver(cs.surfaceContainerLow)).border(1.dp, frame, shape),
                ) {
                    val img = x.img
                    if (img != null) image(Subpages.sized(img, Subpages.thumbWidth(cfg.image)), Modifier.fillMaxSize(), placeholder) else placeholder()
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (aspect == null) 0.dp else 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top,
            ) {
                // Platzhalter zeigt das Symbol schon gross (wie im Web); laedt ein Bild nicht, bleibt das kleine Symbol
                if (!Subpages.placeholder(cfg, x)) PageIcon(x.icon, repo, x.database, size = if (aspect == null) 22.dp else 18.dp)
                Text(
                    x.title.ifEmpty { stringResource(R.string.page_untitled) }, style = titleStyle, color = cs.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Zeile der Liste: Symbol + Titel wie die uebrigen Seitenverweise, Tippflaeche mindestens 48 dp */
@Composable
private fun SubpageRow(x: Subpages.Item, size: String, repo: SagaRepository, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val label = subpageLabel(x)
    val t = MaterialTheme.typography
    val style = (when (size) { "s" -> t.bodyMedium; "l" -> t.titleMedium; else -> t.bodyLarge }).copy(fontWeight = FontWeight.Medium)
    Box(
        Modifier.fillMaxWidth().heightIn(min = if (size == "l") 52.dp else SagaSpace.touch).clip(RoundedCornerShape(SagaRadius.s))
            .clickable(onClickLabel = stringResource(R.string.action_open), role = Role.Button, onClick = onOpen),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label }.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            PageIcon(x.icon, repo, x.database, size = when (size) { "s" -> 20.dp; "l" -> 26.dp; else -> 22.dp })
            Text(
                x.title.ifEmpty { stringResource(R.string.page_untitled) }, style = style, color = cs.onSurface,
                maxLines = if (size == "l") 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
    }
}
