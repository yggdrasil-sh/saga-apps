package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.DbProps
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.DateFormat
import java.util.Date

// Datenbanken (Notion-Stil) zum Lesen: Zeilen auflisten und oeffnen, Eigenschaften einer Zeile anzeigen,
// eingebettete Datenbank-Bloecke. Ansichten/Filter/Bearbeiten der Eigenschaften bleiben im Browser.

/** Alle bekannten Seiten (eigene, mit mir geteilte, 2.3: auch von anderen Servern) - Zeilen einer Datenbank sind ihre Unterseiten */
@Composable
private fun knownPages(repo: SagaRepository): List<PageMeta> {
    val own by repo.pages.collectAsState()
    val sharedLocal by repo.sharedPages.collectAsState()
    val fed by repo.fedShared.collectAsState()
    return remember(own, sharedLocal, fed) {
        val shared = sharedLocal + fed
        if (shared.isEmpty()) own
        else own.mapTo(HashSet(own.size * 2)) { it.id }.let { ids -> own + shared.filter { it.id !in ids } }   // linear statt O(n·m)
    }
}

/** Zeilen einer Datenbank (limit: hoechstens so viele, darunter "Alle anzeigen") */
@Composable
fun DatabaseRows(dbId: Long, repo: SagaRepository, onOpen: (Long) -> Unit, limit: Int? = null) {
    val all = knownPages(repo)
    // 2.3: Datenbank eines anderen Servers - Zeilen von dort (GET .../rows); aeltere Gegenstelle: bekannte Seiten wie bisher
    var remoteRows by remember(dbId) { mutableStateOf<List<PageMeta>?>(null) }
    if (dbId < 0) LaunchedEffect(dbId) { remoteRows = loadRemoteRows(repo, dbId) }
    val rows = remember(all, dbId, remoteRows) { remoteRows ?: all.filter { it.parentId == dbId } }
    Column(Modifier.fillMaxWidth()) {
        Text(
            pluralStringResource(R.plurals.db_rows, rows.size, rows.size),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (rows.isEmpty()) {
            Text(stringResource(R.string.db_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val shown = if (limit != null) rows.take(limit) else rows
        shown.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PageIcon(r.icon, repo, r.isDatabase)
                Text(r.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (limit != null && rows.size > limit) {
            TextButton(onClick = { onOpen(dbId) }) { Text(stringResource(R.string.db_more, rows.size)) }
        }
    }
}

/** Eigenschaften einer Datenbank-Zeile (nur lesen): Schema der Elternseite laden, Werte aufbereiten */
@Composable
fun RowProperties(ed: PageEditor, repo: SagaRepository) {
    val props = ed.props ?: return
    val parent = ed.parentId ?: return
    var schema by remember(parent) { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(parent) {
        schema = try {
            repo.loadPage(parent)["schema"] as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null                                             // Datenbank nicht lesbar: Eigenschaften einfach weglassen
        }
    }
    val all = knownPages(repo)
    val locale = io.github.yggdrasil_sh.saga.ui.common.appLocale()
    val fmt = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val fields = remember(schema, props, all, locale) {
        // Datum und Zahlen in der Schreibweise der App-Sprache (wie Erstellt/Geaendert)
        DbProps.fields(schema, props, ed.created, ed.updatedAt,
            titleOf = { id -> all.firstOrNull { it.id == id }?.title }, time = { fmt.format(Date((it * 1000).toLong())) },
            day = DbProps.localDay(locale), number = DbProps.localNum(locale))
    }
    if (fields.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        fields.forEach { f ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(f.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 90.dp, max = 140.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val link = f.type == "url" && (f.value.startsWith("https://") || f.value.startsWith("http://"))
                Text(
                    f.value, style = MaterialTheme.typography.bodyMedium,
                    color = if (link) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier.weight(1f).then(if (link) Modifier.clickable { io.github.yggdrasil_sh.saga.ui.common.openUrl(context, f.value) } else Modifier),
                )
            }
        }
    }
}

/** Eingebettete Datenbank ({db, view, views}): Titel + erste Zeilen, antippen oeffnet */
@Composable
fun DatabaseBlock(d: JsonObject, env: BlockEnv) {
    val dbId = (d["db"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
    val all = knownPages(env.repo)
    val meta = dbId?.let { id -> all.firstOrNull { it.id == id } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(12.dp),
    ) {
        if (dbId == null || (meta == null && dbId >= 0)) {
            Text("🗃️ " + stringResource(R.string.db_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().clickable { env.onLink("/notes#$dbId") }.padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Datenbank eines anderen Servers, die (noch) nicht in "Mit mir geteilt" steht: ohne Titel, Zeilen von dort
            PageIcon(meta?.icon, env.repo, true)
            Text(meta?.title?.ifEmpty { null } ?: stringResource(if (meta == null) R.string.blocktype_database else R.string.page_untitled),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        }
        DatabaseRows(dbId, env.repo, onOpen = { env.onLink("/notes#$it") }, limit = 5)
    }
}

/** Schaltflaechen-Leiste (Notion "Buttons"): "Seite oeffnen" und "Neuer Eintrag" fuehren zur Seite bzw. Datenbank */
@Composable
fun ButtonsBlock(d: JsonObject, env: BlockEnv) {
    val buttons = (d["buttons"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { b ->
            val label = InlineHtml.decodeEntities(BlockDoc.str(b, "label")).ifEmpty { stringResource(R.string.blocktype_buttons) }
            val icon = InlineHtml.decodeEntities(BlockDoc.str(b, "icon"))
            val target = (b["target"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
            val action = BlockDoc.str(b, "action")
            val go = target?.takeIf { action == "page" || action == "db" }
            OutlinedButton(onClick = { go?.let { env.onLink("/notes#$it") } }, enabled = go != null) {
                Text((if (icon.isNotEmpty()) "$icon " else "") + label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Eingebettete Adresse (YouTube, Website): Titel + Link */
@Composable
fun EmbedBlock(d: JsonObject, env: BlockEnv) {
    val url = InlineHtml.decodeEntities(BlockDoc.str(d, "url"))
    val title = InlineHtml.plain(BlockDoc.str(d, "title"))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(enabled = url.isNotEmpty()) { env.onLink(url) }.padding(12.dp),
    ) {
        Text((if (BlockDoc.str(d, "kind") == "youtube") "▶ " else "🔗 ") + title.ifEmpty { url.ifEmpty { stringResource(R.string.blocktype_embed) } },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (url.isNotEmpty() && title.isNotEmpty()) {
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
