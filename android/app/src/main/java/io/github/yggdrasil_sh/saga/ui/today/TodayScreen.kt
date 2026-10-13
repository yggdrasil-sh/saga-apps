package io.github.yggdrasil_sh.saga.ui.today

import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.yggdrasil_sh.saga.ui.common.SectionHeader
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
    val todayTasks = tasks.filter { it.due.isNullOrEmpty() || it.due <= today }

    Scaffold(topBar = {
        SagaTopBar(stringResource(R.string.nav_today), nav, chrome) {
            IconButton(onClick = { scope.launch { repo.refresh() } }) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
        }
    }) { pad ->
        PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { repo.refresh() } }, modifier = Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    Text(
                        longDate(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item { SectionHeader(stringResource(R.string.nav_events), Modifier.widthIn(max = 760.dp)) }
                if (events.isEmpty()) {
                    item { Hint(stringResource(R.string.today_no_events)) }
                }
                itemsIndexed(events, key = { i, _ -> "ev-$i" }) { _, e -> Box(Modifier.widthIn(max = 760.dp)) { EventRow(e) } }
                item { SectionHeader(stringResource(R.string.nav_tasks), Modifier.widthIn(max = 760.dp)) }
                item { QuickAddTask(repo, Modifier.widthIn(max = 760.dp), defaultDue = today) }
                if (todayTasks.isEmpty()) {
                    item { Hint(stringResource(if (events.isEmpty()) R.string.today_nothing_planned else R.string.today_no_tasks)) }
                }
                items(todayTasks, key = { "t-${it.id}" }) { t -> Box(Modifier.widthIn(max = 760.dp)) { TaskRow(t, repo) { editing = it } } }
            }
        }
    }
    editing?.let { t -> TaskEditDialog(t, repo) { editing = null } }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
