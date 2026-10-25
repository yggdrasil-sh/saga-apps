package io.github.yggdrasil_sh.saga.ui.page

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.yggdrasil_sh.saga.ui.theme.sagaSegment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatX3
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.IncomingImage
import io.github.yggdrasil_sh.saga.ui.common.prepareImage
import io.github.yggdrasil_sh.saga.ui.common.readImage
import io.github.yggdrasil_sh.saga.util.ImageData
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Seitenkopf wie iOS/Web (Notion): Titelbild ueber die volle Breite, Symbol halb darueber, "Symbol/Titelbild hinzufuegen"

/** Hoehe des Titelbilds (wie iOS 190 pt) */
internal val COVER_HEIGHT = 190.dp

/** Ueber den seitlichen Rand der Seitenspalte hinaus bis an den Bildschirmrand */
internal fun Modifier.fullBleed(pad: Dp) = layout { m, c ->
    val extra = pad.roundToPx() * 2
    val w = if (c.hasBoundedWidth) c.maxWidth + extra else c.maxWidth
    val p = m.measure(c.copy(minWidth = w, maxWidth = w))
    layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

/** Halb ueber das Titelbild ziehen, ohne darunter Platz freizulassen */
internal fun Modifier.pullUp(by: Dp) = layout { m, c ->
    val p = m.measure(c)
    val d = by.roundToPx().coerceAtMost(p.height)
    layout(p.width, p.height - d) { p.place(0, -d) }
}

/** Bildadresse in Anzeigebreite (der Server rechnet ?w= beim ersten Abruf) */
internal fun sizedImage(url: String, w: Int): String =
    if ((url.startsWith("/files/") || url.startsWith("/api/fed/files/")) && !url.contains("?")) "$url?w=$w" else url

/** Wie HeaderLogic.tap (iOS): nur Lesen, fremde Instanz, nicht geladen = nichts; gesperrt = Hinweis */
internal fun headerEditable(ed: PageEditor): Boolean = ed.titleEditable && !ed.isRemote

/** Bild waehlen, verkleinern (wie Bilder im Editor), hochladen -> Adresse an onUrl */
@Composable
internal fun rememberHeaderImagePick(ed: PageEditor, repo: SagaRepository, onBusy: (Boolean) -> Unit, onUrl: suspend (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        scope.launch {
            onBusy(true)
            try {
                val max = repo.chat.me.value?.limits?.fileMax ?: io.github.yggdrasil_sh.saga.chat.ChatLimits().fileMax
                val prepared = readImage(context, IncomingImage(uri, mime), 4 * max)?.let { prepareImage(it, max) }
                if (prepared == null) {
                    repo.notify(UiText.Res(R.string.image_unsupported))
                } else {
                    val (data, m) = prepared
                    onUrl(repo.uploadImage(data, ImageData.nameFor("bild", m), m, if (ed.caps.foreign) ed.id else null))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(if (e is ApiException) e.text else UiText.Res(R.string.image_upload_failed))
            } finally {
                onBusy(false)
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
internal fun PageCover(ed: PageEditor, repo: SagaRepository, url: String, pad: Dp) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // wie iOS: "Neu positionieren" - Bild senkrecht ziehen, dann Speichern/Abbrechen
    var repositioning by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(ed.coverPos) }
    val editable = headerEditable(ed)
    val pick = rememberHeaderImagePick(ed, repo, { busy = it }) { u ->
        ed.setHeader(buildJsonObject { put("cover", u); put("cover_pos", 50.0) })
    }
    val desc = stringResource(R.string.page_cover)
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        Modifier.fullBleed(pad).height(COVER_HEIGHT).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (repositioning) Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures { change, dy ->
                        change.consume()
                        pos = (pos - with(density) { dy.toDp().value } / 2.0).coerceIn(0.0, 100.0)
                    }
                } else Modifier.clickable(enabled = editable || ed.locked, onClickLabel = desc, role = Role.Button) {
                    if (ed.locked) repo.notify(UiText.Res(R.string.page_header_locked)) else menu = true
                },
            ),
    ) {
        AsyncImage(
            model = repo.absoluteUrl(sizedImage(url, 1600)), contentDescription = desc, contentScale = ContentScale.Crop,
            alignment = BiasAlignment(0f, ((if (repositioning) pos else ed.coverPos) / 50.0 - 1.0).toFloat()), modifier = Modifier.fillMaxSize(),
        )
        // weicher Uebergang ins Blatt: unten blendet das Bild in den Hintergrund aus (Symbol und Titel sitzen ruhig)
        if (!repositioning) Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.45f).align(Alignment.BottomCenter).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.85f)),
                ),
            ),
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        if (repositioning) Row(
            Modifier.align(Alignment.BottomCenter).padding(10.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.page_cover_drag), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = { repositioning = false }) { Text(stringResource(R.string.action_cancel)) }
            androidx.compose.material3.FilledTonalButton(onClick = {
                val p = pos; repositioning = false
                repo.launchAction { ed.setHeader(buildJsonObject { put("cover_pos", Math.round(p * 10) / 10.0) }) }
            }) { Text(stringResource(R.string.page_cover_save)) }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(end = pad)) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.page_cover_change)) }, onClick = { menu = false; pick() })
                DropdownMenuItem(text = { Text(stringResource(R.string.page_cover_reposition)) }, onClick = { menu = false; pos = ed.coverPos; repositioning = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.page_cover_remove)) }, onClick = {
                    menu = false
                    repo.launchAction { ed.setHeader(buildJsonObject { put("cover", JsonNull) }) }
                })
            }
        }
    }
}

/** Fehlt Symbol oder Titelbild: dezent anbieten (wie iOS) */
@Composable
internal fun HeaderAddRow(ed: PageEditor, repo: SagaRepository, onIcon: () -> Unit) {
    if (!headerEditable(ed) || (ed.icon.isNotEmpty() && ed.cover != null)) return
    var busy by remember { mutableStateOf(false) }
    val pick = rememberHeaderImagePick(ed, repo, { busy = it }) { u ->
        ed.setHeader(buildJsonObject { put("cover", u); put("cover_pos", 50.0) })
    }
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ed.icon.isEmpty()) TextButton(onClick = onIcon) {
            Icon(painterResource(R.drawable.ic_emoji), null, Modifier.size(18.dp), tint = c)
            Text(stringResource(R.string.page_icon_add), color = c, modifier = Modifier.padding(start = 6.dp))
        }
        if (ed.cover == null) TextButton(onClick = pick, enabled = !busy) {
            Icon(painterResource(R.drawable.ic_image), null, Modifier.size(18.dp), tint = c)
            Text(stringResource(R.string.page_cover_add), color = c, modifier = Modifier.padding(start = 6.dp))
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

/** Symbol waehlen: Emoji, eigenes Bild, zufaellig, entfernen (wie IconPicker auf iOS) */
@Composable
internal fun IconPickerDialog(ed: PageEditor, repo: SagaRepository, onDismiss: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    val set: (String) -> Unit = { v ->
        onDismiss()
        repo.launchAction { ed.setHeader(buildJsonObject { put("icon", v) }) }
    }
    val pick = rememberHeaderImagePick(ed, repo, { busy = it }) { u ->
        ed.setHeader(buildJsonObject { put("icon", u) }); onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.page_icon)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = pick, enabled = !busy) {
                        Icon(painterResource(R.drawable.ic_image), null, Modifier.size(18.dp)); Text(stringResource(R.string.page_icon_image), Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = { set(RANDOM.random()) }, enabled = !busy) { Text(stringResource(R.string.page_icon_random)) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(
                    factory = { ctx ->
                        androidx.emoji2.emojipicker.EmojiPickerView(ctx).apply {
                            emojiGridColumns = 8
                            setOnEmojiPickedListener { item -> if (ChatX3.emojiOk(item.emoji)) set(item.emoji) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = {
            if (ed.icon.isNotEmpty()) TextButton(onClick = { set("") }) { Text(stringResource(R.string.page_icon_remove)) }
        },
    )
}

private val RANDOM = "📄📝📚📖✅🗓️💡⭐🔥🎯📌🗂️📦📊💰🛒🏠💼🧠🛠️🔑🧪🎨🎮🎵🎬📷✈️🚀❤️😀😎🤖🐱🐶🦊🌙☀️🌈🍀⚡❄️🌍🍕☕🎁🧩💻📱⚙️"
    .let { s -> buildList { var i = 0; while (i < s.length) { val n = Character.charCount(s.codePointAt(i)); var j = i + n
        while (j < s.length && (s[j] == '️' || s[j] == '‍')) j += if (s[j] == '‍') 1 + Character.charCount(s.codePointAt(j + 1)) else 1
        add(s.substring(i, j)); i = j } } }

/** Tags unter dem Titel (wie iOS TagsRow): Chips "#tag" mit Entfernen, "+ Tag" mit Vorschlaegen aus allen Seiten */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TagsRow(ed: PageEditor, repo: SagaRepository) {
    val editable = headerEditable(ed)
    if (ed.tags.isEmpty() && !editable) return
    var adding by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val pages by repo.pages.collectAsState()
    val known = remember(pages) { pages.flatMap { it.tags }.toSortedSet().toList() }
    val set: (List<String>) -> Unit = { n -> repo.launchAction { ed.setTags(n) } }
    val add: () -> Unit = {
        val t = text.trim().replace("#", "").take(30)
        text = ""; adding = false
        if (t.isNotEmpty() && t !in ed.tags) set(ed.tags + t)
    }
    val small = MaterialTheme.typography.labelMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ed.tags.forEach { t ->
            val accent = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current
            Row(
                Modifier.background(accent.goldSoft, androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .padding(start = 10.dp, end = if (editable) 2.dp else 10.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#$t", style = small, color = accent.onGoldSoft)
                if (editable) {
                    val rm = stringResource(R.string.page_tag_remove)
                    Box(
                        Modifier.size(24.dp).clickable(onClickLabel = rm, role = Role.Button) { set(ed.tags - t) }
                            .androidx_semantics(rm),
                        contentAlignment = Alignment.Center,
                    ) { Text("×", style = small, color = muted) }
                }
            }
        }
        if (editable) {
            if (adding) {
                val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.compose.foundation.text.BasicTextField(
                    value = text, onValueChange = { text = it.replace("\n", "") }, singleLine = true,
                    textStyle = small.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }),
                    modifier = Modifier.width(120.dp).focusRequester(fr).padding(vertical = 4.dp),
                    decorationBox = { inner -> Box { if (text.isEmpty()) Text(stringResource(R.string.page_tag_hint), style = small, color = muted); inner() } },
                )
                androidx.compose.runtime.LaunchedEffect(Unit) { fr.requestFocus() }
                val q = text.lowercase()
                known.filter { it !in ed.tags && (q.isEmpty() || it.lowercase().contains(q)) }.take(6).forEach { s ->
                    Text("#$s", style = small, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { text = s; add() }.padding(horizontal = 4.dp, vertical = 4.dp))
                }
            } else {
                Text(stringResource(R.string.page_tag_add), style = small, color = muted.copy(alpha = 0.7f),
                    modifier = Modifier.clickable(role = Role.Button) { adding = true }.padding(vertical = 4.dp))
            }
        }
    }
}

private fun Modifier.androidx_semantics(label: String) = this.then(
    Modifier.semantics { contentDescription = label },
)

/** "Verlinkt von" am Seitenende (wie iOS BacklinksView) */
@Composable
internal fun BacklinksList(ed: PageEditor, repo: SagaRepository, onOpen: (Long) -> Unit) {
    if (ed.isRemote || !ed.fetched) return
    var list by remember(ed.id) { mutableStateOf<List<io.github.yggdrasil_sh.saga.data.PageMeta>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(ed.id, ed.updatedAt) {
        list = try { repo.backlinks(ed.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { list }
    }
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.page_backlinks), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEachIndexed { i, p ->
            // Kacheln einer segmentierten Liste, 48 dp hoch (vorher 6 dp Abstand = zu kleine Trefferflaeche)
            Row(
                Modifier.fillMaxWidth().padding(top = if (i == 0) 6.dp else 0.dp)
                    .sagaSegment(i, list.size, horizontal = 0.dp).clickable { onOpen(p.id) }.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                io.github.yggdrasil_sh.saga.ui.common.PageIcon(p.icon, repo, p.kind == "database")
                Text(p.title.ifEmpty { stringResource(R.string.page_untitled) }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
