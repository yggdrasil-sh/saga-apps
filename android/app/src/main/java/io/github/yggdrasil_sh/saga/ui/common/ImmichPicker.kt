package io.github.yggdrasil_sh.saga.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.ImmichAlbum
import io.github.yggdrasil_sh.saga.data.ImmichAsset
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Foto aus Immich waehlen (wie die Immich-Auswahl der Seiten in Web/iOS): Zuletzt (mit Inhaltssuche), Alben.
 * Vorschaubilder kommen ueber den Server (/api/immich/thumb/{id}, mit Sitzung). onPick bekommt das Foto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmichPicker(repo: SagaRepository, onDismiss: () -> Unit, onPick: (ImmichAsset) -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }                 // 0 = Zuletzt, 1 = Alben
    var album by remember { mutableStateOf<ImmichAlbum?>(null) }
    var query by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<ImmichAsset>() }
    val albums = remember { mutableStateListOf<ImmichAlbum>() }
    var next by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.immich_failed)
    val none = stringResource(R.string.immich_none)
    val noAlbums = stringResource(R.string.immich_no_albums)

    suspend fun loadItems(reset: Boolean) {
        if (reset) {
            items.clear()
            next = null
        }
        loading = true
        info = null
        try {
            val r = repo.immich.assets(if (reset) 1 else (next ?: 1), album?.id, query)
            items.addAll(r.items.filter { it.id.isNotEmpty() })
            next = r.next
            if (items.isEmpty()) info = none
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            info = failed
        } finally {
            loading = false
        }
    }

    suspend fun loadAlbums() {
        loading = true
        info = null
        try {
            albums.clear()
            albums.addAll(repo.immich.albums().filter { it.id.isNotEmpty() })
            if (albums.isEmpty()) info = noAlbums
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            info = failed
        } finally {
            loading = false
        }
    }

    LaunchedEffect(tab, album) {
        if (tab == 1 && album == null) loadAlbums() else loadItems(true)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(album?.name ?: stringResource(R.string.immich_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (album != null) {
                            IconButton(onClick = { album = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.immich_albums)) }
                        } else {
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) }
                        }
                    },
                )
                if (album == null) {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { query = ""; tab = 0 }, text = { Text(stringResource(R.string.immich_recent)) })
                        Tab(selected = tab == 1, onClick = { query = ""; tab = 1 }, text = { Text(stringResource(R.string.immich_albums)) })
                    }
                }
                val showAlbums = tab == 1 && album == null
                if (!showAlbums) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.immich_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { scope.launch { loadItems(true) } }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                info?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    if (showAlbums) {
                        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                            items(albums, key = { it.id }) { a ->
                                Box(
                                    Modifier.padding(4.dp).aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { album = a },
                                    contentAlignment = Alignment.BottomStart,
                                ) {
                                    a.thumb?.let { t ->
                                        AsyncImage(repo.absoluteUrl(repo.immich.thumbPath(t)), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    }
                                    Column(
                                        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                                            .padding(8.dp),
                                    ) {
                                        Text(a.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(pluralStringResource(R.plurals.immich_photos_n, a.count, a.count), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                            items(items, key = { it.id }) { a ->
                                Box(
                                    Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { onPick(a) },
                                    contentAlignment = Alignment.BottomEnd,
                                ) {
                                    AsyncImage(repo.absoluteUrl(repo.immich.thumbPath(a.id)), a.name.ifEmpty { null }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    if (a.isGif) {
                                        Text(
                                            "GIF", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(4.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                                        )
                                    }
                                }
                            }
                            if (next != null && !loading) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        OutlinedButton(onClick = { scope.launch { loadItems(false) } }, modifier = Modifier.padding(8.dp)) {
                                            Text(stringResource(R.string.immich_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (loading) CircularProgressIndicator(Modifier.padding(top = 48.dp))
                }
            }
        }
    }
}
