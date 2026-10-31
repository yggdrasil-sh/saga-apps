package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatPreview
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.ChatStd
import io.github.yggdrasil_sh.saga.chat.UnfurlResult
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Saga 2.5 Chat-Nachrichten (Paket C): Linkvorschau-Karten, Erwaehnungs-Auswahl, Medienuebersicht */

/** Karte der Linkvorschau in einer Nachricht; Bild = verschluesselter Anhang vom eigenen Server; Tipp oeffnet den Link */
@Composable
fun PreviewCard(p: ChatPreview, s: ChatSession) {
    val ctx = LocalContext.current
    var img by remember(p.img?.id) { mutableStateOf<ImageBitmap?>(null) }
    p.img?.takeIf { it.id.isNotEmpty() && it.k.isNotEmpty() }?.let { t ->
        LaunchedEffect(t.id) {
            img = try {
                s.thumb(ChatFile(id = t.id, k = t.k, m = t.m, w = t.w, h = t.h, s = t.s, th = t))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
    PreviewBox(p.site, p.title, p.desc, img, Modifier.padding(top = 6.dp).clickable { io.github.yggdrasil_sh.saga.ui.common.openUrl(ctx, p.url) })
}

@Composable
private fun PreviewBox(site: String, title: String, desc: String, img: ImageBitmap?, modifier: Modifier, trailing: (@Composable () -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), shape = RoundedCornerShape(10.dp), modifier = modifier.widthIn(max = 420.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (img != null) Image(img, null, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f, fill = false).padding(start = if (img != null) 8.dp else 0.dp)) {
                if (site.isNotBlank()) Text(site.take(80), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (title.isNotBlank()) Text(title.take(200), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (desc.isNotBlank()) Text(desc.take(300), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
        }
    }
}

/** Vorschau ueber dem Eingabefeld (vom Server geholt), mit ✕ zum Entfernen */
@Composable
fun ComposePreview(u: UnfurlResult, onRemove: () -> Unit) {
    val img = remember(u.image?.b64) {
        u.image?.b64?.let { b ->
            try {
                val raw = java.util.Base64.getDecoder().decode(b)
                android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    PreviewBox(u.site, u.title, u.desc, img, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.std_preview_remove)) }
    }
}

/** Auswahl bei "@…": Mitglieder des Chats (Anzeigename) */
@Composable
fun MentionPicker(people: List<ChatPerson>, onPick: (ChatPerson) -> Unit) {
    if (people.isEmpty()) return
    val hint = stringResource(R.string.std_mention_pick)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        people.forEach { p ->
            AssistChip(
                onClick = { onPick(p) },
                label = { Text(p.name.ifEmpty { "?" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { ChatAvatar(p.name, 20.dp, avatar = p.avatar) },
                modifier = Modifier.semantics { contentDescription = "$hint: ${p.name}" },
            )
        }
    }
}

/** Medien, Dateien, Links: ?has=file (Medien/Dateien) bzw. alle Nachrichten (Links), "Ältere laden" */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDialog(repo: SagaRepository, s: ChatSession, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(ChatStd.MediaTab.MEDIA) }
    val files = remember { mutableStateListOf<ChatFile>() }
    val links = remember { mutableStateListOf<String>() }
    var fBefore by remember { mutableStateOf<Long?>(null) }
    var lBefore by remember { mutableStateOf<Long?>(null) }
    var fMore by remember { mutableStateOf(true) }
    var lMore by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    suspend fun load(isLinks: Boolean) {
        busy = true
        try {
            val (ms, more) = s.mediaPage(!isLinks, if (isLinks) lBefore else fBefore)
            if (isLinks) {
                ms.asReversed().forEach { m -> ChatStd.linksIn(m.content?.text).forEach { if (it !in links) links.add(it) } }
                lBefore = ms.minOfOrNull { it.seq }
                lMore = more && ms.isNotEmpty()
            } else {
                ms.asReversed().forEach { m -> m.content?.files.orEmpty().filter { !it.unsupported }.forEach { f -> if (files.none { it.id == f.id }) files.add(f) } }
                fBefore = ms.minOfOrNull { it.seq }
                fMore = more && ms.isNotEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.notify(repo.friendly(e))
        } finally {
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        load(false)
        load(true)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.std_media_title)) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) } },
            )
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    listOf(ChatStd.MediaTab.MEDIA to R.string.std_media_tab, ChatStd.MediaTab.FILES to R.string.std_files_tab, ChatStd.MediaTab.LINKS to R.string.std_links_tab).forEach { (t, l) ->
                        Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(l)) })
                    }
                }
                val more = if (tab == ChatStd.MediaTab.LINKS) lMore else fMore
                val loadMore: () -> Unit = { scope.launch { load(tab == ChatStd.MediaTab.LINKS) } }
                val open: (ChatFile) -> Unit = { f -> scope.launch { openFile(ctx, s, f) { repo.notify(it) } } }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        ChatStd.MediaTab.MEDIA -> {
                            val items = files.filter { ChatStd.inTab(it, tab) }
                            if (items.isEmpty() && !busy) Empty()
                            LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().padding(4.dp)) {
                                items(items, key = { it.id }) { f -> Box(Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(6.dp))) { MediaTile(f, s, open) } }
                            }
                        }
                        ChatStd.MediaTab.FILES -> {
                            val items = files.filter { ChatStd.inTab(it, tab) }
                            if (items.isEmpty() && !busy) Empty()
                            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                                items(items, key = { it.id }) { f -> Attachment(f, s, open) }
                            }
                        }
                        ChatStd.MediaTab.LINKS -> {
                            if (links.isEmpty() && !busy) Empty()
                            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                items(links, key = { it }) { u ->
                                    Text(u, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth().clickable { io.github.yggdrasil_sh.saga.ui.common.openUrl(ctx, u) }.padding(vertical = 10.dp))
                                }
                            }
                        }
                    }
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                if (more) OutlinedButton(onClick = loadMore, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp)) {
                    Text(stringResource(R.string.std_load_older))
                }
            }
        }
    }
}

@Composable
private fun Empty() {
    Text(stringResource(R.string.std_media_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
}

/** Kachel: Vorschaubild (Bild/Video), Tipp oeffnet */
@Composable
private fun MediaTile(f: ChatFile, s: ChatSession, onOpen: (ChatFile) -> Unit) {
    var img by remember(f.id) { mutableStateOf(s.cachedThumb(f)) }
    if (img == null) LaunchedEffect(f.id) {
        img = try {
            s.thumb(f)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    Box(Modifier.fillMaxSize().clickable { onOpen(f) }, contentAlignment = Alignment.Center) {
        val b = img
        if (b != null) Image(b, f.n, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("🖼")
        if (f.isVideo) Text("▶", style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White)
    }
}
