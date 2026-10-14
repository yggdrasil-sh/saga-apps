package io.github.yggdrasil_sh.saga.ui.pages

import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.common.pageTitle
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

/** Volltextsuche in Titel, Tags und Inhalt (POST /api/notes/search), ab 2 Zeichen, nach kurzer Tipp-Pause */
@Composable
fun SearchScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PageMeta>>(emptyList()) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var searched by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(q) {
        delay(250)
        if (q.trim().length < 2) {
            results = emptyList()
            searched = false
            error = null
            return@LaunchedEffect
        }
        try {
            results = repo.search(q)
            error = null
        } catch (e: CancellationException) {
            throw e                                          // schneller getippt: neuer Suchlauf, kein Fehler
        } catch (e: Exception) {
            error = repo.friendly(e)
        }
        searched = true
    }
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.nav_search), nav, chrome) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_placeholder)) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(16.dp).focusRequester(focus),
                )
            }
            error?.let { item { Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (searched && results.isEmpty() && error == null) {
                item { Text(stringResource(R.string.search_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
            items(results, key = { it.id }) { p ->
                Row(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth().clickable { nav.push(Dest.Page(p.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    PageIcon(p.icon, repo, p.isDatabase)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(pageTitle(p), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        p.snippet?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
