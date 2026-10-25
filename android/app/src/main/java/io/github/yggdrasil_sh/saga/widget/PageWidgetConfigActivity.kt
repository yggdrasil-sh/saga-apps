package io.github.yggdrasil_sh.saga.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import io.github.yggdrasil_sh.saga.BuildConfig
import io.github.yggdrasil_sh.saga.MainActivity
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.SagaApplication
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.ShotMode
import kotlinx.coroutines.launch

/** Seite fuer ein Seiten-Widget waehlen (beim Platzieren und ueber "Widget bearbeiten") */
class PageWidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        // nur fuer eigene Seiten-Widgets (die Activity ist fuer den Launcher exportiert)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        if (info == null || info.provider != ComponentName(this, PageWidgetReceiver::class.java)) {
            finish()
            return
        }
        val repo = (application as SagaApplication).repo
        setContent {
            SagaTheme {
                val demo = BuildConfig.DEBUG && ShotMode.on
                val needsLogin by repo.needsLogin.collectAsState()
                val ready by repo.ready.collectAsState()
                val pages by repo.pages.collectAsState()
                LaunchedEffect(ready) { if (ready && !repo.needsLogin.value && repo.pages.value.isEmpty()) repo.refresh() }
                ConfigScreen(
                    signedOut = !demo && ready && needsLogin,
                    loading = !demo && !ready,
                    pages = if (demo) WidgetDemo.pages else pages.filter { !it.archived && !it.template && !it.isRemote },
                    onPick = ::pick,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun pick(p: PageMeta) {
        val store = WidgetStore(this)
        store.setPageFor(widgetId, p.id)
        // sofort Titel/Symbol zeigen (Inhalt kommt mit dem Abgleich)
        if (store.page(p.id) == null) store.setPage(PageSnapshot(id = p.id, title = p.title, icon = p.icon, isDatabase = p.isDatabase, fetchedAt = 0))
        if (BuildConfig.DEBUG && ShotMode.on) store.setPage(WidgetDemo.page(p.id))
        val app = application as SagaApplication
        app.appScope.launch {
            try {
                PageWidget().updateAll(this@PageWidgetConfigActivity.applicationContext)
            } catch (_: Exception) {
            }
        }
        WidgetUpdates.schedule(this)
        WidgetUpdates.requestNow(this)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(
    signedOut: Boolean,
    loading: Boolean,
    pages: List<PageMeta>,
    onPick: (PageMeta) -> Unit,
    onOpenApp: () -> Unit,
    onCancel: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.widget_config_title)) },
            actions = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                signedOut -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.widget_config_signed_out), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onOpenApp) { Text(stringResource(R.string.widget_open_app)) }
                }
                loading -> Text(stringResource(R.string.widget_loading), Modifier.padding(24.dp))
                else -> {
                    OutlinedTextField(
                        value = q, onValueChange = { q = it }, singleLine = true,
                        label = { Text(stringResource(R.string.widget_config_search)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    val shown = remember(pages, q) {
                        pages.filter { q.isBlank() || it.title.contains(q.trim(), ignoreCase = true) }
                            .sortedWith(compareByDescending<PageMeta> { it.pinned }.thenBy { it.title.lowercase() })
                    }
                    if (shown.isEmpty()) {
                        Text(stringResource(R.string.widget_config_none), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(shown, key = { it.id }) { p ->
                            val title = p.title.ifBlank { stringResource(R.string.widget_untitled) }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onPick(p) }.padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Text(PageExcerpt.emoji(p.icon) ?: if (p.isDatabase) "🗂" else "📄", style = MaterialTheme.typography.titleMedium)
                                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
