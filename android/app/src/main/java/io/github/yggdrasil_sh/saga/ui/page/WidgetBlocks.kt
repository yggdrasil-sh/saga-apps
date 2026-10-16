package io.github.yggdrasil_sh.saga.ui.page

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.data.CalEvent
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.TaskItem
import io.github.yggdrasil_sh.saga.ui.common.dayHeader
import io.github.yggdrasil_sh.saga.ui.common.eventDay
import io.github.yggdrasil_sh.saga.ui.events.EventRow
import io.github.yggdrasil_sh.saga.ui.tasks.QuickAddTask
import io.github.yggdrasil_sh.saga.ui.tasks.TaskEditDialog
import io.github.yggdrasil_sh.saga.ui.tasks.TaskRow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Bloecke aus dem Web, die Daten von Saga zeigen (Aufgaben, Termine) oder etwas ausloesen (Schaltflaechen) - wie NativePage.swift

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp),
    ) { content() }
}

/** Aufgaben-Block: offene Aufgaben + schnelle Eingabe */
@Composable
fun TasksBlock(repo: SagaRepository) {
    val tasks by repo.tasks.collectAsState()
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    LaunchedEffect(Unit) { repo.loadTasks() }
    Panel {
        QuickAddTask(repo)
        if (tasks.isEmpty()) {
            Text("Keine offenen Aufgaben 🎉", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        tasks.forEach { t -> TaskRow(t, repo) { editing = it } }
    }
    editing?.let { t -> TaskEditDialog(t, repo) { editing = null } }
}

/** Kalender-Block: Termine der naechsten 14 Tage, nach Tagen */
@Composable
fun CalendarBlock(repo: SagaRepository) {
    var events by remember { mutableStateOf<List<CalEvent>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val r = repo.events(14)
        if (r != null) events = r else failed = true
    }
    Panel {
        val list = events
        when {
            failed -> Text("Termine konnten nicht geladen werden.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            list == null -> Text("Termine werden geladen …", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            list.isEmpty() -> Text("Keine Termine in den nächsten zwei Wochen", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            else -> list.groupBy { eventDay(it.start) }.entries.sortedWith(compareBy(nullsLast()) { it.key }).forEach { (day, evs) ->
                Text(
                    day?.let { dayHeader(it) } ?: "Ohne Datum", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                evs.forEach { EventRow(it) }
            }
        }
    }
}

/** Schaltflaechen: Seite oeffnen, Eintrag in Datenbank anlegen (auch aus Vorlage), Aufgabe anlegen */
@Composable
fun ButtonsBlock(d: JsonObject, repo: SagaRepository, openPage: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val buttons = (d["buttons"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    if (buttons.isEmpty()) {
        Text("Keine Schaltflächen eingerichtet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { b ->
            Row(
                Modifier.clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        scope.launch {
                            try {
                                runButton(b, repo, openPage)
                            } catch (e: Exception) {
                                Toast.makeText(context, repo.friendly(e), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BlockDoc.str(b, "icon").takeIf { it.isNotEmpty() }?.let { Text(it, Modifier.padding(end = 6.dp)) }
                Text(BlockDoc.str(b, "label"), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private suspend fun runButton(b: JsonObject, repo: SagaRepository, openPage: (Int) -> Unit) {
    val target = BlockDoc.int(b, "target")
    when (BlockDoc.str(b, "action")) {
        "page" -> target?.let(openPage)
        "db" -> {
            val db = target ?: return
            val title = BlockDoc.str(b, "title").replace("{datum}", LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
            val tpl = BlockDoc.int(b, "tpl")
            openPage(if (tpl != null) repo.addRowFromTemplate(db, tpl, title) else repo.addRow(db, title = title))
        }
        "task" -> repo.addTask(BlockDoc.str(b, "title").ifEmpty { "Neue Aufgabe" }, null)
    }
}

/** Einbetten-Block: YouTube oder Link als Karte - Tippen oeffnet im Browser bzw. in der App */
@Composable
fun EmbedCard(d: JsonObject, onLink: (String) -> Unit) {
    val url = InlineHtml.decodeEntities(BlockDoc.str(d, "url"))
    if (!url.startsWith("http://") && !url.startsWith("https://")) return
    val title = InlineHtml.decodeEntities(BlockDoc.str(d, "title"))
    val host = try {
        java.net.URI(url).host.orEmpty().removePrefix("www.")
    } catch (_: Exception) {
        ""
    }
    val yt = url.contains("youtu.be/") || url.contains("youtube.com/")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onLink(url) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (yt) "▶️" else "🔗", Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(title.ifEmpty { if (yt) "YouTube-Video" else host.ifEmpty { url } }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (host.isNotEmpty()) Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("↗", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Anhang: Name + Groesse, Tippen oeffnet bzw. laedt herunter */
@Composable
fun AttachCard(d: JsonObject, onLink: (String) -> Unit) {
    val f = d["file"] as? JsonObject
    val name = BlockDoc.str(f, "name").ifEmpty { BlockDoc.str(d, "title") }.ifEmpty { "Datei" }
    val url = BlockDoc.str(f, "url")
    val size = (f?.get("size") as? JsonPrimitive)?.content?.toLongOrNull()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = url.isNotEmpty()) { onLink(url) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📎 $name", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        size?.let { Text(fileSize(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun fileSize(b: Long): String = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%d KB".format(b / 1_000)
    else -> "$b B"
}

/** Tabelle (nur ansehen; der Inhalt bleibt beim Speichern unveraendert) */
@Composable
fun TableView(d: JsonObject) {
    val rows = (d["content"] as? JsonArray)?.map { r -> (r as? JsonArray)?.map { InlineHtml.plain((it as? JsonPrimitive)?.content ?: "") } ?: emptyList() } ?: emptyList()
    if (rows.isEmpty()) return
    val headings = BlockDoc.bool(d, "withHeadings")
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.horizontalScroll(rememberScrollState()).border(0.5.dp, line)) {
        rows.forEachIndexed { r, row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                row.forEach { cell ->
                    Text(
                        cell, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (r == 0 && headings) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.width(140.dp).fillMaxHeight().border(0.5.dp, line).padding(8.dp),
                    )
                }
            }
        }
    }
}
