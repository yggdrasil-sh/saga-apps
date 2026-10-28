package io.github.yggdrasil_sh.saga.ui.database

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.db.DbRow
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.serialization.json.JsonObject

/**
 * 2.5 P7 Teil B: eingebettete Datenbank als echte Ansicht - alle Ansichtsarten umschaltbar (Block-Ansichten zuerst),
 * Filter/Sortierung der Ansicht vom Server, Werte setzen, Board ziehen/verschieben. Nicht-lazy (steckt in einem Item
 * der Seiten-LazyColumn), daher hoechstens [DbViewState.Embed.pageSize] Zeilen, darunter "Alle anzeigen".
 * Ansichten des Blocks bearbeiten: im Web (P7-E6).
 */
@Composable
fun EmbeddedDatabase(dbId: Long, data: JsonObject, pageId: Long, blockId: String, repo: SagaRepository, onOpen: (Long) -> Unit, hideTabs: Boolean) {
    val st = rememberEmbeddedDbState(dbId, repo, data, pageId, blockId)
    CompositionLocalProvider(LocalDbRepo provides repo) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (st.noAccess) {
                    Text("🗃️ " + stringResource(R.string.db_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Column
                }
                val s = st.schema
                if (s == null) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                val v = st.view ?: return@Column
                if (!hideTabs && st.views.size > 1) ViewPills(st.views, v.id) { st.selectView(it) }
                if (st.offline) Text(stringResource(R.string.dbv_offline_cached), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                when (v.type) {
                    "board" -> DbBoard(st, repo, onOpen, Modifier.height(if (wide) 480.dp else 420.dp))
                    "calendar" -> DbCalendar(st, repo, onOpen, wide)
                    "chart" -> DbChartView(st, repo, wide)
                    "timeline" -> DbTimeline(st, repo, onOpen, wide)
                    "gallery" -> {
                        val cols = if (wide) 4 else 2
                        st.rows.chunked(cols).forEach { chunk ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                chunk.forEach { r -> Box(Modifier.weight(1f)) { GalleryCard(st, repo, r, v, onOpen) } }
                                repeat(cols - chunk.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    "list" -> withGroups(st, v).forEach { e ->
                        when (e) {
                            is DbRow -> ListRow(st, repo, e, v, onOpen)
                            is GroupHead -> GroupHeader(st, e)
                        }
                    }
                    else -> {
                        TableHead(st, v, wide)
                        withGroups(st, v).forEach { e ->
                            when (e) {
                                is DbRow -> TableRow(st, repo, e, v, wide, onOpen)
                                is GroupHead -> GroupHeader(st, e)
                            }
                        }
                    }
                }
                if (st.loaded && st.rows.isEmpty() && v.type in setOf("table", "list", "gallery")) {
                    Text(stringResource(R.string.dbv_empty_view), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
                st.error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (st.hasMore && v.type in setOf("table", "list", "gallery")) {
                        TextButton(onClick = { onOpen(dbId) }) { Text(stringResource(R.string.db_more, st.matched)) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onOpen(dbId) }) { Text(stringResource(R.string.e1_open_full)) }
                }
            }
        }
        DatabaseDialogs(st, repo)
    }
}
