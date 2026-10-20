package io.github.yggdrasil_sh.saga.ui.events

import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.data.CalEvent
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.SectionHeader
import io.github.yggdrasil_sh.saga.ui.common.dayHeader
import io.github.yggdrasil_sh.saga.ui.common.eventDay
import io.github.yggdrasil_sh.saga.ui.common.eventTime
import io.github.yggdrasil_sh.saga.ui.common.hexColor
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import kotlinx.coroutines.launch

/** Termin-Zeile: Farbbalken des Kalenders, Titel, Zeit und Ort */
@Composable
fun EventRow(e: CalEvent) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(hexColor(e.color, MaterialTheme.colorScheme.tertiary)))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(e.summary.ifEmpty { stringResource(R.string.events_untitled) } + if (e.series == true) " 🔁" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(eventTime(e.start, e.end, e.allday), e.location?.takeIf { it.isNotBlank() }, e.calendar?.takeIf { it.isNotBlank() })
            Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Termine der naechsten 31 Tage, nach Tagen gruppiert; 2.4: antippen bearbeitet, "Neuer Termin" legt an */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    var events by remember { mutableStateOf<List<CalEvent>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load(fresh: Boolean) {
        val r = repo.events(31, fresh)
        if (r != null) {
            events = r
            failed = false
        } else failed = true
    }
    LaunchedEffect(Unit) { load(false) }
    // 2.4: Termine anlegen/bearbeiten/loeschen (CalDAV ueber den Server, wie Web/iOS). edit: null = zu, Pair(null, Tag) = neu
    var edit by remember { mutableStateOf<Pair<CalEvent?, java.time.LocalDate>?>(null) }

    Scaffold(
        topBar = { SagaTopBar(stringResource(R.string.nav_events), nav, chrome) },
        floatingActionButton = {
            io.github.yggdrasil_sh.saga.ui.common.LabeledFab(stringResource(R.string.ev_add), { edit = null to java.time.LocalDate.now() })
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; load(true); refreshing = false } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            val byDay = (events ?: emptyList()).groupBy { eventDay(it.start) }.entries.sortedWith(compareBy(nullsLast()) { it.key })
            LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (failed) item { Text(stringResource(R.string.events_load_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                if (events?.isEmpty() == true) item { Text(stringResource(R.string.events_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                byDay.forEach { (day, list) ->
                    item(key = "d-$day") { SectionHeader(day?.let { dayHeader(it) } ?: stringResource(R.string.no_date), Modifier.widthIn(max = 760.dp)) }
                    itemsIndexed(list, key = { i, _ -> "e-$day-$i" }) { _, e ->
                        Box(Modifier.widthIn(max = 760.dp).clickable { edit = e to (day ?: java.time.LocalDate.now()) }) { EventRow(e) }
                    }
                }
                item(key = "fab-space") { Box(Modifier.height(88.dp)) }
            }
        }
    }
    edit?.let { (e, day) ->
        EventDialog(repo, e, day, onDismiss = { edit = null }, onChanged = { scope.launch { load(true) } })
    }
}
