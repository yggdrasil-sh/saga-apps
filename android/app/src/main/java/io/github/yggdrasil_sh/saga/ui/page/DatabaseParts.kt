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
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbQuery
import io.github.yggdrasil_sh.saga.db.DbRow
import io.github.yggdrasil_sh.saga.db.DbSchema
import io.github.yggdrasil_sh.saga.db.DbValues
import io.github.yggdrasil_sh.saga.db.DbViews
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.database.PropRow
import io.github.yggdrasil_sh.saga.ui.database.PropValueEditor
import io.github.yggdrasil_sh.saga.ui.database.rememberDbFormat
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Datenbanken (Notion-Stil): eingebettete Datenbank-Bloecke (Vorschau, antippen oeffnet die Datenbank) und
// Eigenschaften einer Zeile (bearbeitbar). Die Datenbank als Seite (Ansichten, Filter ...) steht in ui/database.

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

/**
 * Eigenschaften einer Datenbank-Zeile (Eintrag als Seite geoeffnet): 2.4 bearbeitbar wie im Web - alle Typen des
 * Servers. Werte gelten sofort, offline landen sie in der Warteschlange (DbStore) und werden nachgeholt.
 */
@Composable
fun RowProperties(ed: PageEditor, repo: SagaRepository) {
    val props = ed.props ?: return
    val parent = ed.parentId ?: return
    var schema by remember(parent) { mutableStateOf<DbSchema?>(null) }
    LaunchedEffect(parent) {
        schema = try {
            (repo.loadPage(parent)["schema"] as? JsonObject)?.let(::DbSchema)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null                                             // Datenbank nicht lesbar: Eigenschaften einfach weglassen
        }
    }
    val s = schema ?: return
    val all = knownPages(repo)
    val pending by repo.dbStore.pending.collectAsState()
    // eigene Aenderungen sofort zeigen (bis die Seite neu geladen wird), offline geaenderte Werte darueber
    var local by remember(props) { mutableStateOf(props) }
    val row = remember(local, pending, ed.created, ed.updatedAt) {
        repo.dbStore.overlay(listOf(DbRow(ed.id, ed.title, props = local, created = ed.created ?: 0.0, updated = ed.updatedAt ?: 0.0))).first()
    }
    val fmt = rememberDbFormat { id -> all.firstOrNull { it.id == id }?.title }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val editable = ed.share.role.canEdit && !ed.isDatabase
    var editing by remember { mutableStateOf<DbProp?>(null) }
    val q = remember(s) { DbQuery(s) }
    val shown = s.props.filter { p -> editable && p.editable || !DbQuery.isEmpty(q.value(p, row)) }
    if (shown.isEmpty()) return

    fun set(p: DbProp, v: JsonElement?) {
        local = DbValues.apply(row, p.id, v).props                    // sofort sichtbar
        scope.launch {
            try {
                if (repo.dbStore.setProp(ed.id, p.id, v) == io.github.yggdrasil_sh.saga.db.DbStore.Saved.QUEUED) {
                    repo.notify(UiText.Res(R.string.dbv_saved_offline))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(UiText.Res(R.string.dbv_not_saved, if (e is io.github.yggdrasil_sh.saga.data.ApiException) e.text else repo.friendly(e)))
                ed.load()
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        shown.forEach { p ->
            val v = q.value(p, row)
            PropRow(p, v, fmt, editable, onEdit = { editing = p }, onToggle = { set(p, DbValues.checkbox(!DbQuery.truthy(v))) },
                onLink = { io.github.yggdrasil_sh.saga.ui.common.openUrl(context, it) })
        }
    }
    editing?.let { p ->
        PropValueEditor(
            p, row.props[p.id], onSet = { set(p, it) }, onDismiss = { editing = null },
            onNewOption = { name ->
                scope.launch {
                    try {
                        val (raw, oid) = DbViews.addOption(s.raw, p.id, name)
                        schema = DbSchema(repo.dbStore.saveSchema(parent, raw))
                        set(p, if (p.type == "multi_select") DbValues.toggle(row.props[p.id], oid) else JsonPrimitive(oid))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        repo.notify(UiText.Res(R.string.dbv_not_saved, if (e is io.github.yggdrasil_sh.saga.data.ApiException) e.text else repo.friendly(e)))
                    }
                }
            },
            relationRows = p.target?.let { t -> { query: String -> repo.dbStore.search(t, query) } },
        )
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
        // 2.5: Titel im Block ausgeblendet (hide_title, wie Web/iOS) - die Eintraege oeffnen sich weiter
        if ((d["hide_title"] as? JsonPrimitive)?.content != "true") Row(
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

/**
 * Schaltflaechen-Leiste (wie Web/iOS): "Seite oeffnen" fuehrt zur Seite, "Neuer Eintrag" legt einen Eintrag in der
 * Datenbank an (leer oder aus der gewaehlten Vorlage, Titel mit {datum}) und oeffnet ihn, "Aufgabe" legt eine Aufgabe an.
 * Waehrend eine Aktion laeuft, ist der Knopf gesperrt (sonst legt doppeltes Tippen zwei Eintraege an).
 * Auf gesperrten Seiten bedienbar, nur mit Lese-/Kommentarrecht nicht.
 */
@Composable
fun ButtonsBlock(d: JsonObject, env: BlockEnv) {
    val buttons = (d["buttons"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf<Int?>(null) }
    var problem by remember { mutableStateOf<UiText?>(null) }
    val canAct = env.editor.share.role.canEdit
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons.forEachIndexed { i, b ->
                val label = InlineHtml.decodeEntities(BlockDoc.str(b, "label")).ifEmpty { stringResource(R.string.blocktype_buttons) }
                val icon = InlineHtml.decodeEntities(BlockDoc.str(b, "icon"))
                val target = (b["target"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
                val tpl = (b["tpl"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
                val action = BlockDoc.str(b, "action")
                val usable = when (action) {
                    "page", "db" -> target != null
                    "task" -> true
                    else -> false
                } && (action == "page" || canAct)
                val taskDefault = stringResource(R.string.button_task_default)
                OutlinedButton(
                    onClick = {
                        if (busy != null) return@OutlinedButton
                        when (action) {
                            "page" -> target?.let { env.onLink("/notes#$it") }
                            "db", "task" -> {
                                busy = i
                                problem = null
                                scope.launch {
                                    try {
                                        val raw = InlineHtml.decodeEntities(BlockDoc.str(b, "title"))
                                        if (action == "task") {
                                            env.repo.addTask(raw.ifBlank { taskDefault }, null)
                                        } else {
                                            val today = android.text.format.DateFormat.getDateFormat(ctx).format(java.util.Date())
                                            var title = raw
                                            for (k in listOf("{datum}", "{date}", "{fecha}")) title = title.replace(k, today)
                                            val id = env.repo.buttonEntry(target!!, title, tpl, env.editor.id)
                                            env.onLink("/notes#$id")
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        problem = env.repo.friendly(e)
                                    } finally {
                                        kotlinx.coroutines.delay(800)      // waehrend die neue Seite hereinfaehrt: kein zweites Mal
                                        busy = null
                                    }
                                }
                            }
                        }
                    },
                    enabled = usable && busy != i,
                ) {
                    Text((if (icon.isNotEmpty()) "$icon " else "") + label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        problem?.let { p ->
            Text(stringResource(R.string.button_entry_failed, p.resolve()), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
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
