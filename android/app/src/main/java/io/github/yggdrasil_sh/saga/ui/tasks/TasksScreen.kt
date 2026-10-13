package io.github.yggdrasil_sh.saga.ui.tasks

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import io.github.yggdrasil_sh.saga.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
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

    Scaffold(topBar = { SagaTopBar(stringResource(R.string.nav_tasks), nav, chrome) }) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; repo.loadTasks(); refreshing = false } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
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
                        Text(
                            (if (showDone) "▾ " else "▸ ") + pluralStringResource(R.plurals.tasks_completed, done.size, done.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().clickable { showDone = !showDone }.padding(16.dp),
                        )
                    }
                    if (showDone) items(done, key = { "d-${it.id}" }) { t ->
                        androidx.compose.foundation.layout.Box(Modifier.widthIn(max = 760.dp)) { TaskRow(t, repo) { editing = it } }
                    }
                }
            }
        }
    }
    editing?.let { t -> TaskEditDialog(t, repo) { editing = null } }
}
