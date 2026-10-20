package io.github.yggdrasil_sh.saga.ui.page

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.BlockOps
import io.github.yggdrasil_sh.saga.blocks.JsonPath
import io.github.yggdrasil_sh.saga.blocks.SlashLogic
import io.github.yggdrasil_sh.saga.chat.ChatAttach
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.RemoteCaps
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.immichImport
import io.github.yggdrasil_sh.saga.data.ncSettings
import io.github.yggdrasil_sh.saga.data.newDatabase
import io.github.yggdrasil_sh.saga.data.uploadPageFile
import io.github.yggdrasil_sh.saga.ui.common.ImmichPicker
import io.github.yggdrasil_sh.saga.ui.common.IncomingImage
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.common.pageTitle
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * Saga 2.4: Bloecke einfuegen ueber "/" (im Absatz) und "+" (Werkzeugleiste) - beide fuehren hierher.
 * Ausgangspunkt ist immer ein Absatz (bei "+" ein neuer leerer Absatz hinter dem Block). Textbloecke entstehen
 * direkt; Bild, Datei, Einbetten, Verweis usw. ueber Auswahl/Dialog. Async-Schritte (Hochladen) finden den Absatz
 * ueber seine Block-ID wieder - zwischendurch verschobene Bloecke landen so nie an der falschen Stelle.
 */
@Stable
class BlockInserter internal constructor(private val ed: PageEditor) {
    /** offener Dialog: Art + Block-ID des Absatzes */
    var pending by mutableStateOf<Pair<String, String>?>(null)
    /** "+"-Auswahl offen fuer diesen Block */
    var pickerAfter by mutableStateOf<JsonPath?>(null)
    var ncConnected by mutableStateOf(false)
    var immichConnected by mutableStateOf(false)
    /** laufendes Hochladen/Anlegen (Fortschrittsbalken) */
    var busy by mutableIntStateOf(0)
    internal var launch: (String, String) -> Unit = { _, _ -> }

    private companion object {
        /** ohne eigenen Dialog: Systemauswahl bzw. sofort anlegen */
        val DIRECT = setOf("file", "page", "db_new")
    }

    val needs: Set<SlashLogic.Need>
        get() = buildSet {
            if (ed.can(RemoteCaps.UPLOAD)) add(SlashLogic.Need.UPLOAD)
            if (!ed.caps.foreign) add(SlashLogic.Need.OWNER)
            if (!ed.isRemote) add(SlashLogic.Need.OWN_SERVER)
            if (ncConnected && !ed.isRemote) add(SlashLogic.Need.NEXTCLOUD)
            if (immichConnected && ed.can(RemoteCaps.UPLOAD) && !ed.isRemote) add(SlashLogic.Need.IMMICH)
        }

    /** "/"-Auswahl auf dem Absatz an path */
    fun start(path: JsonPath, item: SlashLogic.Item) {
        val b = ed.block(path) ?: return
        val bid = BlockDoc.id(b) ?: return
        ed.setString(path + listOf("data", "text"), "")                // "/..." weg
        when (item.id) {
            in SlashLogic.TEXT_TYPES -> ed.slashConvert(path, item.id, if (item.level == 0) 2 else item.level)
            "table" -> ed.replaceBlock(path, BlockOps.table(id = bid))
            "columns" -> ed.replaceBlock(path, BlockOps.columns(item.level, id = bid))
            "tasks" -> ed.replaceBlock(path, BlockOps.tasks(id = bid))
            "calendar" -> ed.replaceBlock(path, BlockOps.calendar(id = bid))
            else -> {
                ed.active = null
                if (item.id in DIRECT) launch(item.id, bid) else pending = item.id to bid
            }
        }
    }

    /** "+": neuen Absatz hinter dem Block, dann wie "/" */
    fun insertAfter(path: JsonPath, item: SlashLogic.Item) {
        val p = ed.insertParagraphAfter(path) ?: return
        start(p, item)
    }

    fun pathOf(bid: String): JsonPath? = ed.pathOfBlock(bid)

    /** Ergebnis eines Dialogs: Absatz durch diesen Block ersetzen (ID bleibt) */
    fun replace(bid: String, nb: JsonObject) {
        val p = pathOf(bid) ?: return
        ed.replaceBlock(p, BlockOps.withId(nb, bid))
    }
}

@Composable
fun rememberBlockInserter(
    ed: PageEditor, repo: SagaRepository,
    insertImages: (List<IncomingImage>, JsonPath?) -> Unit,
    onOpenPage: (Long) -> Unit,
): BlockInserter {
    val ins = remember(ed) { BlockInserter(ed) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<String?>(null) }           // Block-ID fuer die System-Auswahl
    var cameraFile by remember { mutableStateOf<Uri?>(null) }
    val untitled = stringResource(R.string.page_untitled)
    val dbTitle = stringResource(R.string.ed24_db_new_title)

    LaunchedEffect(ed.editable, ed.isRemote) {
        if (!ed.editable || ed.isRemote) return@LaunchedEffect
        ins.ncConnected = try {
            repo.ncSettings()?.let { it.enabled && it.connected } == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        ins.immichConnected = try {
            repo.immich.connected()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun imageAt(uri: Uri?) {
        val bid = target ?: return
        target = null
        if (uri == null) return
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        insertImages(listOf(IncomingImage(uri, mime)), ins.pathOf(bid))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { imageAt(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> imageAt(if (ok) cameraFile else null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val u = cameraFile
        if (granted && u != null) camera.launch(u) else {
            target = null
            if (!granted) repo.notify(UiText.Res(R.string.ed24_camera_denied))
        }
    }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val bid = target ?: return@rememberLauncherForActivityResult
        target = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            ins.busy++
            try {
                val (name, data) = readDocument(context, uri, 200L * 1024 * 1024) ?: run {
                    repo.notify(UiText.Res(R.string.ed24_file_unreadable))
                    return@launch
                }
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val f = repo.uploadPageFile(data, name, mime, if (ed.caps.foreign || ed.isRemote) ed.id else null)
                ins.replace(bid, BlockOps.attaches(f))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(if (e is ApiException) e.text else repo.friendly(e))
            } finally {
                ins.busy--
            }
        }
    }
    ins.launch = { kind, bid ->
        target = bid
        when (kind) {
            "file" -> document.launch(arrayOf("*/*"))
            "image_gallery" -> gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            "image_camera" -> {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val f = File(dir, "photo-" + System.currentTimeMillis() + ".jpg")
                val u = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                cameraFile = u
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(u)
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }
            // Unterseite / neue Datenbank: anlegen, Absatz wird Verweis bzw. Datenbank-Block (wie Web/iOS)
            "page", "db_new" -> {
                target = null
                scope.launch {
                    ins.busy++
                    try {
                        if (kind == "page") {
                            val id = repo.newPage(ed.id)
                            ins.replace(bid, BlockOps.pageLink(id, untitled, "📄"))
                            ed.flush()
                            onOpenPage(id)
                        } else {
                            val id = repo.newDatabase(ed.id, dbTitle)
                            ins.replace(bid, BlockOps.database(id))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        repo.notify(if (e is ApiException) e.text else repo.friendly(e))
                    } finally {
                        ins.busy--
                    }
                }
            }
        }
    }
    return ins
}

/** Name + Inhalt einer gewaehlten Datei (hoechstens max Bytes) */
private suspend fun readDocument(context: Context, uri: Uri, max: Long): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
    try {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "datei"
        val data = context.contentResolver.openInputStream(uri)?.use { ChatAttach.readLimited(it, max) } ?: return@withContext null
        name to data
    } catch (_: Exception) {
        null
    }
}

/** Dialoge des Einfuegens (in PageScreen eingebunden) */
@Composable
fun BlockInserterDialogs(ins: BlockInserter, ed: PageEditor, repo: SagaRepository) {
    val scope = rememberCoroutineScope()
    ins.pickerAfter?.let { after ->
        InsertPickerDialog(ins.needs, onDismiss = { ins.pickerAfter = null }) { item ->
            ins.pickerAfter = null
            ins.insertAfter(after, item)
        }
    }
    val (kind, bid) = ins.pending ?: return
    val close = { ins.pending = null }
    when (kind) {
        "image" -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.ed24_image)) },
            text = {
                Column {
                    ChoiceRow(stringResource(R.string.ed24_from_gallery)) { close(); ins.launch("image_gallery", bid) }
                    ChoiceRow(stringResource(R.string.ed24_from_camera)) { close(); ins.launch("image_camera", bid) }
                    if (SlashLogic.Need.IMMICH in ins.needs) ChoiceRow(stringResource(R.string.ed24_immich)) { ins.pending = "immich_image" to bid }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) } },
        )
        "immich_image" -> ImmichPicker(repo, onDismiss = close) { asset ->
            close()
            scope.launch {
                ins.busy++
                try {
                    val url = repo.immichImport(asset.id, if (ed.caps.foreign) ed.id else null)
                    ins.pathOf(bid)?.let { ed.insertImage(it, url) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    repo.notify(if (e is ApiException) e.text else repo.friendly(e))
                } finally {
                    ins.busy--
                }
            }
        }
        "embed" -> EmbedDialog(onDismiss = close) { url, title ->
            close()
            ins.replace(bid, BlockOps.embed(url, title))
        }
        "nextcloud" -> NextcloudPickerDialog(repo, onDismiss = close) { data ->
            close()
            ins.replace(bid, buildJsonObject {
                put("id", JsonPrimitive(bid))
                put("type", JsonPrimitive("nextcloud"))
                put("data", data)
            })
        }
        "link", "db_link" -> {
            val own by repo.pages.collectAsState()
            val shared by repo.sharedPages.collectAsState()
            val pool = remember(own, shared, kind) {
                (own + shared).filter { !it.archived && it.id != ed.id && (kind != "db_link" || it.isDatabase) }.distinctBy { it.id }
            }
            PagePickerDialog(stringResource(if (kind == "link") R.string.ed24_link else R.string.ed24_db_link), pool, repo, onDismiss = close) { p ->
                close()
                ins.replace(bid, if (kind == "link") BlockOps.pageLink(p.id, p.title.ifEmpty { "…" }, p.icon.orEmpty()) else BlockOps.database(p.id))
            }
        }
        else -> LaunchedEffect(kind, bid) { close() }
    }
}

@Composable
private fun ChoiceRow(label: String, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 12.dp))
}

/** "+" in der Werkzeugleiste: alle Blocktypen mit Suche */
@Composable
fun InsertPickerDialog(needs: Set<SlashLogic.Need>, onDismiss: () -> Unit, onPick: (SlashLogic.Item) -> Unit) {
    var q by remember { mutableStateOf("") }
    val items = slashItems(q, needs)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.block_insert)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text(stringResource(R.string.ed24_search_blocks)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(items, key = { it.key }) { it ->
                        Text(slashLabel(it), style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(it) }.heightIn(min = 48.dp).padding(vertical = 12.dp, horizontal = 4.dp))
                    }
                    if (items.isEmpty()) item { Text(stringResource(R.string.ed24_slash_none, q), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Einbetten: Adresse (YouTube, Website, Lesezeichen) + optionaler Titel */
@Composable
fun EmbedDialog(onDismiss: () -> Unit, onDone: (String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    val norm = BlockOps.normalizeUrl(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ed24_embed)) },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.link_address_label)) },
                    isError = url.isNotBlank() && norm == null, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.ed24_title_optional)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(stringResource(R.string.ed24_embed_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { norm?.let { onDone(it, title) } }, enabled = norm != null) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Seite waehlen (Verweis, verknuepfte Datenbank) mit Suche */
@Composable
fun PagePickerDialog(title: String, pool: List<PageMeta>, repo: SagaRepository, onDismiss: () -> Unit, onPick: (PageMeta) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(pool, q) { pool.filter { q.isBlank() || it.title.contains(q.trim(), ignoreCase = true) }.sortedBy { it.title.lowercase() }.take(300) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text(stringResource(R.string.ed24_search_pages)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(p) }.heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PageIcon(p.icon, repo, p.isDatabase)
                            Text(pageTitle(p), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                    if (shown.isEmpty()) item { Text(stringResource(R.string.ed24_no_pages), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
