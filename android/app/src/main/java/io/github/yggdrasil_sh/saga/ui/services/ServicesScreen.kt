package io.github.yggdrasil_sh.saga.ui.services

import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.ServiceItem
import io.github.yggdrasil_sh.saga.data.StdCategory
import androidx.compose.ui.platform.LocalResources
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.openUrl
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import java.text.Normalizer

/** Dienste (Kacheln wie im Web), nach Kategorie, mit Suche */
@Composable
fun ServicesScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    var list by remember { mutableStateOf<List<ServiceItem>?>(null) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var q by remember { mutableStateOf("") }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        try {
            list = repo.services()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = repo.friendly(e)
        }
    }
    // Standard-Kategorien ("Lesezeichen", "Dienste") in der App-Sprache statt der Konto-Sprache des Servers
    val res = LocalResources.current
    val labeled = (list ?: emptyList()).map { it to StdCategory.display(it.category) { id -> res.getString(id) } }
    val shown = labeled.filter { (s, cat) -> q.isBlank() || norm("${s.name} ${s.host} $cat ${s.category}").contains(norm(q)) }
    val cats = shown.groupBy({ it.second }, { it.first }).toSortedMap(String.CASE_INSENSITIVE_ORDER)

    Scaffold(topBar = { SagaTopBar(stringResource(R.string.nav_services), nav, chrome) }) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(170.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text(stringResource(R.string.services_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth().padding(4.dp),
                )
            }
            error?.let { e -> item(span = { GridItemSpan(maxLineSpan) }) { Text(e.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) } }
            if (list != null && shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.services_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            }
            cats.forEach { (cat, items) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "c-$cat") {
                    Text(cat.ifEmpty { stringResource(R.string.services_other) }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp))
                }
                itemsIndexed(items, key = { i, s -> "s-$cat-$i-${s.url}" }) { _, s ->
                    Row(
                        Modifier.padding(4.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { if (s.url.isNotBlank()) openUrl(context, repo.absoluteUrl(s.url)) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ServiceIcon(s, repo)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(s.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(s.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Symbol ueber den eigenen Server (/api/services/icon/<name>), sonst Anfangsbuchstabe wie im Web */
@Composable
private fun ServiceIcon(s: ServiceItem, repo: SagaRepository) {
    val letter: @Composable () -> Unit = {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text(s.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (s.icon.isBlank() || !Regex("[a-z0-9][a-z0-9-]{0,63}").matches(s.icon)) {
        letter()
    } else {
        // Dienst-Symbole sind oft dunkel auf transparent: im Dunkelmodus auf heller Plakette, sonst unsichtbar
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
        SubcomposeAsyncImage(
            model = repo.absoluteUrl("api/services/icon/${s.icon}"), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.size(32.dp).then(
                if (dark) Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFFF1EDE4)).padding(3.dp) else Modifier
            ),
            error = { letter() }, loading = { letter() },
        )
    }
}

private fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
