package io.github.yggdrasil_sh.saga.ui.tasks

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import io.github.yggdrasil_sh.saga.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Aufgaben wie im Dashboard: Schnell-Eingabe, Gruppen nach Faelligkeit, erledigte eingeklappt */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val tasks by repo.tasks.collectAsState()
    val done by repo.doneTasks.collectAsState()
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    var showDone by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { repo.loadTasks() }

    val today = LocalDate.now().toString()
    val tomorrow = LocalDate.now().plusDays(1).toString()
    val groups = listOf(
        stringResource(R.string.tasks_overdue) to tasks.filter { !it.due.isNullOrEmpty() && it.due < today },
        stringResource(R.string.tasks_today) to tasks.filter { it.due == today },
        stringResource(R.string.tasks_tomorrow) to tasks.filter { it.due == tomorrow },
        stringResource(R.string.tasks_later) to tasks.filter { !it.due.isNullOrEmpty() && it.due > tomorrow },
        stringResource(R.string.no_date) to tasks.filter { it.due.isNullOrEmpty() },
    ).filter { it.second.isNotEmpty() }

    // 2.5 E2 (tasksrc1): zusammengefuehrte Liste aus Inbox und Aufgabenquellen - Reihenfolge vom Server
    val merged by repo.taskList.collectAsState()
    var manage by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        SagaTopBar(stringResource(R.string.nav_tasks), nav, chrome) {
            if (merged != null) {
                androidx.compose.material3.TextButton(onClick = { manage = true }) { Text(stringResource(R.string.e2_task_sources)) }
            }
        }
    }) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; repo.loadTasks(); refreshing = false } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            val ml = merged
            if (ml != null) LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                item { QuickAddTask(repo, Modifier.widthIn(max = 760.dp), targets = io.github.yggdrasil_sh.saga.data.TaskSourcesLogic.targets(ml.sources)) }
                if (ml.items.isEmpty()) {
                    item { Text(stringResource(R.string.tasks_all_done), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                }
                val bySrc = ml.sources.associateBy { it.db }
                items(ml.items, key = { it.key }) { e ->
                    androidx.compose.foundation.layout.Box(Modifier.widthIn(max = 760.dp)) {
                        SourceTaskRow(e, e.src?.let { bySrc[it] }, ml.people, repo) { x ->
                            if (x.isRow) nav.push(io.github.yggdrasil_sh.saga.ui.nav.Dest.Page(x.id))
                            else editing = tasks.firstOrNull { it.id.toLong() == x.id } ?: TaskItem(x.id.toInt(), x.text, false, x.due, x.prio, x.repeat)
                        }
                    }
                }
                if (done.isNotEmpty()) {
                    item(key = "done-h") {
                        Row(
                            Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { showDone = !showDone }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (showDone) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(pluralStringResource(R.plurals.tasks_completed, done.size, done.size), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    if (showDone) items(done, key = { "d-${it.id}" }) { t ->
                        androidx.compose.foundation.layout.Box(Modifier.widthIn(max = 760.dp)) { TaskRow(t, repo) { editing = it } }
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                item { QuickAddTask(repo, Modifier.widthIn(max = 760.dp)) }
                if (tasks.isEmpty()) {
                    item { Text(stringResource(R.string.tasks_all_done), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                }
                groups.forEach { (name, list) ->
                    item(key = "h-$name") {
                        SectionHeader(name, Modifier.widthIn(max = 760.dp))
                    }
                    items(list, key = { "t-${it.id}" }) { t ->
                        androidx.compose.foundation.layout.Box(Modifier.widthIn(max = 760.dp)) { TaskRow(t, repo) { editing = it } }
                    }
                }
                if (done.isNotEmpty()) {
                    item(key = "done-h") {
                        val state = stringResource(if (showDone) R.string.state_expanded else R.string.state_collapsed)
                        Row(
                            Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { showDone = !showDone }
                                .semantics { stateDescription = state }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (showDone) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                pluralStringResource(R.plurals.tasks_completed, done.size, done.size),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    if (showDone) items(done, key = { "d-${it.id}" }) { t ->
                        androidx.compose.foundation.layout.Box(Modifier.widthIn(max = 760.dp)) { TaskRow(t, repo) { editing = it } }
                    }
                }
            }
        }
    }
    editing?.let { t -> TaskEditDialog(t, repo) { editing = null } }
    if (manage) TaskSourcesDialog(repo, onOpen = { nav.push(io.github.yggdrasil_sh.saga.ui.nav.Dest.Page(it)) }) { manage = false }
}
