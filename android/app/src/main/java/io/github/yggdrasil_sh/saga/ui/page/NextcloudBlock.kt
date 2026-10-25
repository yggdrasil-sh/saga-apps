package io.github.yggdrasil_sh.saga.ui.page

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFiles
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.BackupLogic
import io.github.yggdrasil_sh.saga.data.NcItem
import io.github.yggdrasil_sh.saga.data.NcLogic
import io.github.yggdrasil_sh.saga.data.NcPageStatus
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.ncList
import io.github.yggdrasil_sh.saga.data.ncPageStatus
import io.github.yggdrasil_sh.saga.data.ncSearch
import io.github.yggdrasil_sh.saga.data.ncSettings
import io.github.yggdrasil_sh.saga.ui.common.epochDay
import io.github.yggdrasil_sh.saga.ui.common.openUrl
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Status der Nextcloud-Bloecke einer Seite (GET /api/notes/{id}/nc), einmal je Seite geholt und eine Minute gemerkt -
 * wie im Web. refresh() nach dem Einfuegen eines neuen Blocks (der Server kennt ihn erst nach dem Speichern).
 */
class NcPageCache(private val noteId: Long, private val repo: SagaRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Deferred<NcPageStatus?>? = null
    private var at = 0L
    var generation by mutableIntStateOf(0)
        private set

    suspend fun status(): NcPageStatus? {
        val now = System.currentTimeMillis()
        val j = job?.takeIf { now - at < 60_000 } ?: scope.async {
            try {
                repo.ncPageStatus(noteId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }.also { job = it; at = now }
        return j.await()
    }

    fun refresh() {
        job = null
        generation++
    }
}

/** Karte fuer einen Nextcloud-Block: Typ-Symbol, Name, Groesse, Vorschau (Bild) bzw. erste Eintraege (Ordner), Aktionen */
@Composable
fun NextcloudCard(d: JsonObject, env: BlockEnv) {
    val ed = env.editor
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote = ed.isRemote
    val noteId = ed.id
    val kind = NcLogic.kind(d)
    val fresh = NcLogic.str(d, "sig").isEmpty() && !remote          // eben eingefuegt: Server kennt ihn erst nach dem Speichern
    var tries by remember(d) { mutableIntStateOf(0) }
    var st by remember(d) { mutableStateOf<io.github.yggdrasil_sh.saga.data.NcBlockStatus?>(null) }
    var loaded by remember(d) { mutableStateOf(false) }
    var failed by remember(d) { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    val cache = env.nc
    LaunchedEffect(d, cache.generation, tries) {
        val all = cache.status()
        failed = all == null
        st = NcLogic.statusFor(all, d, remote)
        loaded = true
        if ((st == null || st?.ok != true) && fresh && tries < 8) {
            delay(1500)
            cache.refresh()
            tries++
        }
    }
    val name = (st?.name?.takeIf { it.isNotBlank() } ?: NcLogic.name(d)).ifEmpty { stringResource(R.string.nc_file) }
    val ok = st?.ok == true
    val dir = st?.dir ?: (kind == "folder")
    val retrying = fresh && tries < 8 && !ok
    val meta = listOfNotNull(
        if (dir) stringResource(R.string.nc_folder) else (st?.size ?: NcLogic.size(d))?.let { BackupLogic.size(it) },
        st?.mtime?.takeIf { ok }?.let { epochDay(it) },
        "Nextcloud",
    ).joinToString(" · ")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(NcLogic.icon(kind, st?.mime ?: NcLogic.str(d, "mime"), dir), style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            !loaded || retrying -> Text(stringResource(R.string.nc_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !ok -> Text(
                stringResource(if (remote) R.string.nc_remote_only else R.string.nc_unavailable),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dir -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val es = st?.entries.orEmpty()
                if (es.isEmpty()) Text(stringResource(R.string.nc_empty_folder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                es.forEach { e -> Text((if (e.dir) "📁 " else "📄 ") + e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            kind == "image" || st?.preview == true -> NcLogic.proxyPath(noteId, d, width = if (kind == "image") 1280 else 320)?.let { p ->
                AsyncImage(
                    model = env.repo.absoluteUrl(p), contentDescription = name, contentScale = ContentScale.Fit,
                    modifier = (if (kind == "image") Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 900.dp) else Modifier.size(160.dp))
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }
        if (ok || !loaded) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!dir && ok) {
                OutlinedButton(onClick = {
                    if (downloading) return@OutlinedButton
                    downloading = true
                    scope.launch {
                        val err = ncDownload(context, env.repo, noteId, d, name, st?.mime ?: NcLogic.str(d, "mime"), st?.size ?: NcLogic.size(d))
                        downloading = false
                        if (err != null) env.repo.notify(err)
                    }
                }, enabled = !downloading) {
                    if (downloading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.nc_download))
                }
            }
            NcLogic.openUrl(d, remote)?.let { u ->
                OutlinedButton(onClick = { openUrl(context, u) }) { Text(stringResource(R.string.nc_open) + " ↗") }
            }
        }
    }
}

/** Datei ueber den Saga-Proxy laden und mit einer anderen App oeffnen bzw. teilen/speichern -> Fehlertext oder null */
private suspend fun ncDownload(context: Context, repo: SagaRepository, noteId: Long, d: JsonObject, name: String, mime: String, size: Long?): UiText? {
    if (size != null && size > NC_DOWNLOAD_MAX) return UiText.Res(R.string.nc_too_large)
    val path = NcLogic.proxyPath(noteId, d, download = true) ?: return UiText.Res(R.string.nc_unavailable)
    return try {
        val data = repo.api.getBytes(path)
        val app = context.applicationContext
        val (out, uri) = withContext(Dispatchers.IO) {
            ChatFiles.purgeOlderThan(app.cacheDir)
            val out = ChatFiles.write(app.cacheDir, name, data)
            out to FileProvider.getUriForFile(app, app.packageName + ".files", out)
        }
        ncCleanup.launch {
            delay(ChatFiles.KEEP_MS)
            try {
                app.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            ChatFiles.remove(out)
        }
        // schlichtes ACTION_VIEW: Android bietet "Nur diesmal / Immer" an; ohne passende App die Teilen-Auswahl
        io.github.yggdrasil_sh.saga.util.OpenWith.open(context, uri, mime, name)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.code == 404) UiText.Res(R.string.nc_unavailable) else repo.friendly(e)
    } catch (e: Exception) {
        repo.friendly(e)
    }
}

private const val NC_DOWNLOAD_MAX = 200L * 1024 * 1024
private val ncCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Datei aus der EIGENEN Nextcloud waehlen (Zuletzt, Ordner, Suche; Server-API /api/nextcloud/...). onPick bekommt die
 * Daten fuer einen neuen Block (author = ich; author/sig setzt endgueltig der Server beim Speichern).
 */
@Composable
fun NextcloudPickerDialog(repo: SagaRepository, onDismiss: () -> Unit, onPick: (JsonObject) -> Unit) {
    var me by remember { mutableStateOf<String?>(null) }
    var notConnected by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("recent") }
    var path by remember { mutableStateOf("/") }
    var q by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<NcItem>?>(null) }
    var error by remember { mutableStateOf<UiText?>(null) }
    LaunchedEffect(Unit) {
        try {
            val s = repo.ncSettings()
            me = s?.me.orEmpty()
            notConnected = s == null || !s.connected || !s.enabled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = repo.friendly(e)
        }
    }
    LaunchedEffect(mode, path, q, notConnected, me) {
        if (me == null || notConnected) return@LaunchedEffect
        items = null
        if (q.isNotBlank()) delay(300)
        try {
            items = if (q.isNotBlank() || mode == "recent") repo.ncSearch(q).items else repo.ncList(path).items
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = repo.friendly(e)
            items = emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("☁ " + stringResource(R.string.nc_pick_title)) },
        text = {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notConnected) {
                    Text(stringResource(R.string.nc_pick_not_connected), style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "recent" && q.isBlank(), onClick = { mode = "recent"; q = "" }, label = { Text(stringResource(R.string.nc_recent)) })
                    FilterChip(selected = mode == "dirs" && q.isBlank(), onClick = { mode = "dirs"; q = "" }, label = { Text(stringResource(R.string.nc_folders)) })
                }
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, label = { Text(stringResource(R.string.nc_search)) }, modifier = Modifier.fillMaxWidth())
                if (mode == "dirs" && q.isBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (path != "/") TextButton(onClick = { path = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }) { Text("‹ " + stringResource(R.string.nc_up)) }
                        Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val list = items
                if (list == null) CircularProgressIndicator(Modifier.padding(8.dp))
                else if (list.isEmpty() && error == null) Text(stringResource(R.string.nc_no_files), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list, key = { it.fileid + "|" + it.path }) { it ->
                        val browse = it.dir && mode == "dirs" && q.isBlank()
                        ListItem(
                            leadingContent = { Text(NcLogic.icon(it.kind, it.mime, it.dir), style = MaterialTheme.typography.titleLarge) },
                            headlineContent = { Text(it.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(listOfNotNull(if (it.dir) stringResource(R.string.nc_folder) else BackupLogic.size(it.size), it.mtime?.let { t -> epochDay(t) }).joinToString(" · "))
                            },
                            trailingContent = if (browse) { { TextButton(onClick = { onPick(NcLogic.newBlockData(it, me.orEmpty())) }) { Text(stringResource(R.string.nc_insert)) } } } else null,
                            modifier = Modifier.clickable {
                                if (browse) path = it.path.ifEmpty { "/" + it.name }
                                else onPick(NcLogic.newBlockData(it, me.orEmpty()))
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
