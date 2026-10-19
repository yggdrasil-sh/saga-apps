package io.github.yggdrasil_sh.saga.ui.page

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.RemoteCaps
import io.github.yggdrasil_sh.saga.data.Role
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.DateFormat
import java.util.Date

// Versionsverlauf und Export einer Seite (wie Web/iOS/Linux): eigene Seiten und - je nach dem, was ihr Server kann
// (remote.caps, Saga 2.3) - Seiten anderer Saga-Server. Datenbank-Zeilen fremder Datenbanken (GET .../rows).

/** Reine Regeln (Unit-Tests): wer sieht Versionsverlauf/Export, Dateiname */
object PageToolsLogic {
    /**
     * Versionsverlauf: Eigentuemer immer; Rolle Bearbeiten ab Server 2.3 (vorher nur der Eigentuemer); Seiten anderer
     * Server nur mit Faehigkeit "versions" und Rolle Bearbeiten. Datenbanken haben keinen Inhalt mit Versionen.
     */
    fun canVersions(role: Role, remote: Boolean, caps: Set<String>, server23: Boolean, database: Boolean): Boolean = when {
        database -> false
        remote -> role.canEdit && RemoteCaps.VERSIONS in caps
        role.isOwner -> true
        else -> role.canEdit && server23
    }

    /** Export (Markdown/ZIP): fuer alle mit Zugriff; Seiten anderer Server nur mit Faehigkeit "export" */
    fun canExport(remote: Boolean, caps: Set<String>): Boolean = RemoteCaps.has(remote, caps, RemoteCaps.EXPORT)

    /** Dateiname fuer den Export: Titel ohne Schraegstriche (leer: "Seite"), .md bzw. .zip */
    fun exportName(title: String, fallback: String, zip: Boolean): String {
        val base = title.trim().replace(Regex("[/\\\\:*?\"<>|]"), "-").take(80).ifBlank { fallback }
        return base + if (zip) ".zip" else ".md"
    }

    /** Unterseiten vorhanden? (eigene, mit mir geteilte und Seiten anderer Server) */
    fun hasChildren(id: Long, pages: List<PageMeta>): Boolean = pages.any { it.parentId == id }
}

@Serializable
data class PageVersion(
    val id: Long = 0,
    val title: String = "",
    val created: Double = 0.0,
    val size: Long = 0,
    val by: String? = null,
    val author: io.github.yggdrasil_sh.saga.data.Person? = null,
)

@Serializable
private data class VersionText(val title: String = "", val created: Double = 0.0, val text: String = "")

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

/**
 * Versionsverlauf: Liste (neueste zuerst), antippen zeigt den Stand, "Diese Version wiederherstellen" (der aktuelle Stand
 * bleibt als Version erhalten). Vorher werden eigene Eingaben gespeichert; danach laedt die Seite neu.
 */
@Composable
fun VersionsDialog(ed: PageEditor, repo: SagaRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<PageVersion>?>(null) }
    var err by remember { mutableStateOf<UiText?>(null) }
    var sel by remember { mutableStateOf<PageVersion?>(null) }
    var preview by remember { mutableStateOf<VersionText?>(null) }
    var busy by remember { mutableStateOf(false) }
    val locale = appLocale()
    val fmt = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    LaunchedEffect(ed.id) {
        try {
            val text = repo.api.get("api/notes/${ed.id}/versions")
            list = withContext(Dispatchers.Default) { json.decodeFromString<List<PageVersion>>(text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = repo.friendly(e)
            list = emptyList()
        }
    }
    LaunchedEffect(sel) {
        preview = null
        val v = sel ?: return@LaunchedEffect
        try {
            val text = repo.api.get("api/notes/${ed.id}/versions/${v.id}")
            preview = withContext(Dispatchers.Default) { json.decodeFromString<VersionText>(text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = repo.friendly(e)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.versions_title)) },
        text = {
            Column {
                err?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val l = list
                val p = preview
                when {
                    l == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    sel != null -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        if (p == null) {
                            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        } else {
                            Text(p.title.ifEmpty { stringResource(R.string.page_untitled) }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(fmt.format(Date((p.created * 1000).toLong())), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(p.text.ifEmpty { stringResource(R.string.versions_empty_text) }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    l.isEmpty() -> Text(stringResource(R.string.versions_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(l, key = { it.id }) { v ->
                            val who = when {
                                v.by == "me" -> stringResource(R.string.versions_by_me)
                                v.author?.name?.isNotBlank() == true -> stringResource(R.string.versions_by, v.author.name)
                                else -> null
                            }
                            ListItem(
                                headlineContent = { Text(fmt.format(Date((v.created * 1000).toLong()))) },
                                supportingContent = { Text(listOfNotNull(v.title.ifEmpty { null }, who).joinToString(" · ")) },
                                colors = ListItemDefaults.colors(),
                                modifier = Modifier.fillMaxWidth().clickable { sel = v },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val v = sel
            if (v != null) {
                TextButton(enabled = !busy && preview != null, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            ed.saveAndWait()
                            repo.api.post("api/notes/${ed.id}/versions/${v.id}/restore")
                            ed.load()
                            repo.notify(UiText.Res(R.string.versions_restored))
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            err = repo.friendly(e)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.versions_restore)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
        dismissButton = if (sel != null) ({ TextButton(onClick = { sel = null }) { Text(stringResource(R.string.versions_back)) } }) else null,
    )
}

/**
 * Export als Datei (Speichern-Dialog des Systems): Markdown bzw. mit Unterseiten als ZIP (GET .../export[?sub=1]).
 * -> Funktion (zip) zum Starten.
 */
@Composable
fun rememberPageExport(repo: SagaRepository, id: Long, title: () -> String): (Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingZip by remember { mutableStateOf(false) }
    val fallback = stringResource(R.string.page_untitled)
    val save: (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) {
            val zip = pendingZip
            scope.launch {
                try {
                    val data = repo.api.getBytes("api/notes/$id/export" + if (zip) "?sub=1" else "")
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) } ?: throw java.io.IOException("no stream")
                    }
                    repo.notify(UiText.Res(R.string.export_done))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    repo.notify(UiText.Res(R.string.export_failed, repo.friendly(e)))
                }
            }
        }
    }
    val md = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), save)
    val zipL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), save)
    return { zip ->
        pendingZip = zip
        val name = PageToolsLogic.exportName(title(), fallback, zip)
        if (zip) zipL.launch(name) else md.launch(name)
    }
}

/**
 * 2.3: Zeilen einer Datenbank eines anderen Servers (GET /api/notes/{id}/rows, Faehigkeit "db") - der Server der Seite
 * rechnet; IDs negativ. null = nicht ladbar (aelterer Server: 404) -> bekannte Seiten wie bisher.
 */
suspend fun loadRemoteRows(repo: SagaRepository, dbId: Long): List<PageMeta>? = try {
    val text = repo.api.get("api/notes/$dbId/rows")
    withContext(Dispatchers.Default) { RemoteRowsLogic.parse(text, dbId) }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

object RemoteRowsLogic {
    /** {schema, rows:[{id, title, icon, props, ...}]} -> Seiten (Eltern = Datenbank) */
    fun parse(text: String, dbId: Long): List<PageMeta> {
        val o = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val rows = o["rows"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return rows.mapNotNull { r ->
            val x = r as? JsonObject ?: return@mapNotNull null
            val id = (x["id"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: return@mapNotNull null
            PageMeta(
                id = id,
                title = (x["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                icon = (x["icon"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.ifEmpty { null },
                parentId = dbId,
            )
        }
    }
}
