package io.github.yggdrasil_sh.saga.ui.today

import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.TaskItem
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent
import io.github.yggdrasil_sh.saga.ui.theme.SagaEmptyState
import io.github.yggdrasil_sh.saga.ui.theme.SagaSectionTitle
import io.github.yggdrasil_sh.saga.ui.theme.SagaSpace
import io.github.yggdrasil_sh.saga.ui.theme.sagaAppear
import io.github.yggdrasil_sh.saga.ui.theme.sagaSegment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yggdrasil_sh.saga.ui.common.longDate
import io.github.yggdrasil_sh.saga.ui.common.todayIso
import io.github.yggdrasil_sh.saga.ui.events.EventRow
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.tasks.QuickAddTask
import io.github.yggdrasil_sh.saga.ui.tasks.TaskEditDialog
import io.github.yggdrasil_sh.saga.ui.tasks.TaskRow
import kotlinx.coroutines.launch

/** Heute wie in iOS: heutige Termine + offene Aufgaben (heute, ueberfaellig, ohne Datum) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val tasks by repo.tasks.collectAsState()
    val events by repo.todayEvents.collectAsState()
    val loading by repo.loading.collectAsState()
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        repo.loadTasks()
        repo.loadTodayEvents()
    }
    val today = todayIso()
    // Widget "Neue Aufgabe": zur Eingabe springen und fokussieren
    val quickAdd by repo.focusQuickAdd.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(quickAdd) { if (quickAdd) listState.scrollToItem(3 + maxOf(1, events.size)) }
    val todayTasks = tasks.filter { it.due.isNullOrEmpty() || it.due <= today }

    Scaffold(topBar = {
        SagaTopBar(stringResource(R.string.nav_today), nav, chrome) {
            IconButton(onClick = { scope.launch { repo.refresh() } }) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
        }
    }) { pad ->
        PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { repo.refresh() } }, modifier = Modifier.padding(pad).fillMaxSize()) {
            val accent = LocalSagaAccent.current
            val maxW = SagaSpace.readingWidth
            LazyColumn(
                Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = SagaSpace.xxl),
            ) {
                // Kopf: Datum (Gold) + Gruss + kurze Zusammenfassung
                item(key = "head") {
                    Column(Modifier.widthIn(max = maxW).fillMaxWidth().padding(horizontal = SagaSpace.gutter).padding(top = SagaSpace.xs, bottom = SagaSpace.s).sagaAppear(0)) {
                        Text(longDate(), style = MaterialTheme.typography.labelLarge, color = accent.goldText)
                        Text(
                            stringResource(greetingRes()), style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = SagaSpace.xxs).semantics { heading() },
                        )
                        val res = LocalResources.current
                        val open = todayTasks.count { !it.done }
                        val summary = listOf(
                            if (events.isEmpty()) stringResource(R.string.today_no_events) else res.getQuantityString(R.plurals.today_events_count, events.size, events.size),
                            res.getQuantityString(R.plurals.today_tasks_open, open, open),
                        ).joinToString(" · ")
                        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = SagaSpace.xxs))
                    }
                }
                item(key = "ev-head") { SagaSectionTitle(stringResource(R.string.nav_events), Modifier.widthIn(max = maxW).padding(top = SagaSpace.s), count = events.size) }
                if (events.isEmpty()) {
                    item(key = "ev-empty") {
                        Box(Modifier.widthIn(max = maxW).fillMaxWidth().sagaSegment(0, 1).sagaAppear(1)) {
                            SagaEmptyState(stringResource(R.string.today_no_events), emoji = "🗓️", text = stringResource(R.string.today_events_empty_hint), compact = true)
                        }
                    }
                }
                itemsIndexed(events, key = { i, _ -> "ev-$i" }) { i, e ->
                    Box(Modifier.widthIn(max = maxW).fillMaxWidth().sagaSegment(i, events.size).padding(vertical = SagaSpace.xs).sagaAppear(1 + i)) { EventRow(e) }
                }
                item(key = "t-head") { SagaSectionTitle(stringResource(R.string.nav_tasks), Modifier.widthIn(max = maxW).padding(top = SagaSpace.m), count = todayTasks.size) }
                item(key = "t-add") { QuickAddTask(repo, Modifier.widthIn(max = maxW), defaultDue = today, focus = quickAdd, onFocused = { repo.focusQuickAdd.value = false }) }
                if (todayTasks.isEmpty()) {
                    item(key = "t-empty") {
                        Box(Modifier.widthIn(max = maxW).fillMaxWidth().padding(top = SagaSpace.s).sagaSegment(0, 1)) {
                            SagaEmptyState(
                                stringResource(if (events.isEmpty()) R.string.today_nothing_planned else R.string.today_no_tasks),
                                emoji = "☕", text = stringResource(R.string.today_tasks_empty_hint), compact = true,
                            )
                        }
                    }
                }
                itemsIndexed(todayTasks, key = { _, t -> "t-${t.id}" }) { i, t ->
                    Box(
                        Modifier.widthIn(max = maxW).fillMaxWidth().padding(top = if (i == 0) SagaSpace.s else 0.dp)
                            .sagaSegment(i, todayTasks.size).animateItem().sagaAppear(2 + i),
                    ) { TaskRow(t, repo) { editing = it } }
                }
            }
        }
    }
    editing?.let { t -> TaskEditDialog(t, repo) { editing = null } }
}

/** Gruss nach Tageszeit */
private fun greetingRes(): Int = when (java.time.LocalTime.now().hour) {
    in 5..10 -> R.string.greeting_morning
    in 11..17 -> R.string.greeting_day
    in 18..22 -> R.string.greeting_evening
    else -> R.string.greeting_night
}
