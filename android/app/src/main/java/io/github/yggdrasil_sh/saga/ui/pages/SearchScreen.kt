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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent
import io.github.yggdrasil_sh.saga.ui.theme.SagaEmptyState
import io.github.yggdrasil_sh.saga.ui.theme.SagaIconTile
import io.github.yggdrasil_sh.saga.ui.theme.SagaSectionTitle
import io.github.yggdrasil_sh.saga.ui.theme.SagaSpace
import io.github.yggdrasil_sh.saga.ui.theme.sagaAppear
import io.github.yggdrasil_sh.saga.ui.theme.sagaSegment
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
    val pages by repo.pages.collectAsState()
    val recentIds by repo.settings.recent.collectAsState()
    val recent = remember(pages, recentIds) { recentIds.mapNotNull { id -> pages.firstOrNull { it.id == id } }.take(5) }
    val maxW = SagaSpace.readingWidth
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.nav_search), nav, chrome) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = SagaSpace.xxl),
        ) {
            item(key = "field") {
                // Suchfeld als Pille (wie die Material-Suchleiste), mit Loeschen-Knopf
                TextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_placeholder)) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (q.isNotEmpty()) IconButton(onClick = { q = ""; runCatching { focus.requestFocus() } }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.action_clear_search))
                        }
                    },
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    modifier = Modifier.widthIn(max = maxW).fillMaxWidth().padding(horizontal = SagaSpace.gutter, vertical = SagaSpace.s)
                        .heightIn(min = 56.dp).focusRequester(focus),
                )
            }
            error?.let { item(key = "err") { Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (q.trim().length < 2) {
                item(key = "hint") {
                    SagaEmptyState(
                        stringResource(R.string.search_empty_title), emoji = "🔎", text = stringResource(R.string.search_empty_hint),
                        compact = recent.isNotEmpty(), modifier = Modifier.sagaAppear(0),
                    )
                }
                if (recent.isNotEmpty()) {
                    item(key = "rec-head") { SagaSectionTitle(stringResource(R.string.sidebar_recent), Modifier.widthIn(max = maxW)) }
                    itemsIndexed(recent, key = { _, p -> "rec-${p.id}" }) { i, p ->
                        ResultTile(p, repo, "", i, recent.size, Modifier.widthIn(max = maxW).sagaAppear(1 + i)) { nav.push(Dest.Page(p.id)) }
                    }
                }
            } else if (searched && results.isEmpty() && error == null) {
                item(key = "none") {
                    SagaEmptyState(stringResource(R.string.search_no_hits_title), emoji = "🫥", text = stringResource(R.string.search_no_hits_hint))
                }
            }
            if (q.trim().length >= 2) itemsIndexed(results, key = { _, p -> p.id }) { i, p ->
                ResultTile(p, repo, q.trim(), i, results.size, Modifier.widthIn(max = maxW).animateItem()) { nav.push(Dest.Page(p.id)) }
            }
        }
    }
}

/** Treffer als Kachel einer segmentierten Liste: Symbol-Kachel, Titel, Ausschnitt mit hervorgehobenem Suchwort */
@Composable
private fun ResultTile(p: PageMeta, repo: SagaRepository, q: String, index: Int, count: Int, modifier: Modifier, onOpen: () -> Unit) {
    val accent = LocalSagaAccent.current
    Row(
        modifier.fillMaxWidth().sagaSegment(index, count).clickable(onClick = onOpen).heightIn(min = 56.dp)
            .padding(horizontal = SagaSpace.m, vertical = SagaSpace.s + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SagaIconTile(size = 40.dp) { PageIcon(p.icon, repo, p.isDatabase) }
        Column(Modifier.padding(start = SagaSpace.m).weight(1f)) {
            Text(highlight(pageTitle(p), q, accent.goldText), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.foreign) {
                Row(verticalAlignment = Alignment.CenterVertically) {         // G9: einfarbiges Symbol statt 👥
                    androidx.compose.material3.Icon(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_group), null,
                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.shared_by, p.owner?.name.orEmpty()), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            p.snippet?.takeIf { it.isNotBlank() }?.let {
                Text(highlight(it, q, accent.goldText), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Suchwort im Text fett und in Gold (ohne Gross-/Kleinschreibung zu beachten) */
private fun highlight(text: String, q: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    if (q.length < 2) return@buildAnnotatedString
    var i = text.indexOf(q, ignoreCase = true)
    var n = 0
    while (i >= 0 && n < 20) {
        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color), i, i + q.length)
        i = text.indexOf(q, i + q.length, ignoreCase = true); n++
    }
}
