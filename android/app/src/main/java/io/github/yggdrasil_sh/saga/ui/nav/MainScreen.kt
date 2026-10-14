package io.github.yggdrasil_sh.saga.ui.nav

import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import io.github.yggdrasil_sh.saga.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import io.github.yggdrasil_sh.saga.ui.login.PairConfirmDialog
import io.github.yggdrasil_sh.saga.ui.share.ShareDialog
import io.github.yggdrasil_sh.saga.util.UiText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.events.EventsScreen
import io.github.yggdrasil_sh.saga.ui.login.LoginScreen
import io.github.yggdrasil_sh.saga.ui.page.PageScreen
import io.github.yggdrasil_sh.saga.ui.pages.SearchScreen
import io.github.yggdrasil_sh.saga.ui.services.ServicesScreen
import io.github.yggdrasil_sh.saga.ui.settings.SettingsScreen
import io.github.yggdrasil_sh.saga.ui.tasks.TasksScreen
import io.github.yggdrasil_sh.saga.ui.today.TodayScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.platform.LocalAccessibilityManager

/**
 * Wurzel: Hauptansicht, darueber der Anmeldebildschirm, solange keine Anmeldung besteht.
 * Die Hauptansicht bleibt dabei erhalten (ungespeicherte Eingaben gehen nach erneuter Anmeldung nicht verloren)
 * und wird bei einem anderen Server neu aufgebaut. Ganz oben: Kopplungs-Bestaetigung und Meldungen (Snackbar).
 */
@Composable
fun SagaRoot(repo: SagaRepository, appScope: CoroutineScope) {
    val ready by repo.ready.collectAsState()
    val needsLogin by repo.needsLogin.collectAsState()
    val server by repo.server.collectAsState()
    val pendingPair by repo.pendingPair.collectAsState()
    val pendingShare by repo.pendingShare.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val res = LocalResources.current
    val a11y = LocalAccessibilityManager.current
    LaunchedEffect(Unit) {
        // collectLatest: eine neue Meldung ersetzt die angezeigte (statt bis zu 8 s in der Schlange zu warten)
        repo.messages.collectLatest { m ->
            val show = suspend {
                snackbar.showSnackbar(
                    message = m.text.asString(res), actionLabel = m.actionLabel?.asString(res), withDismissAction = m.action == null,
                    duration = if (m.action != null) SnackbarDuration.Indefinite else SnackbarDuration.Short,
                )
            }
            // Meldungen mit Aktion ("Aufgabe erledigt · Rueckgaengig") 8 s (Nachtest N6; laenger, wenn die
            // Bedienungshilfen mehr Zeit verlangen); der Abbruch nach Ablauf blendet die Snackbar aus.
            // Ohne Aktion: kurz wie bisher.
            val ms = a11y?.calculateRecommendedTimeoutMillis(ACTION_SNACKBAR_MS, containsIcons = false, containsText = true, containsControls = true)
                ?: ACTION_SNACKBAR_MS
            val r = if (m.action != null) withTimeoutOrNull(ms) { show() } else show()
            if (r == SnackbarResult.ActionPerformed) m.action?.invoke()
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (ready) {                                             // erst nach dem Laden der Einstellungen (im Hintergrund)
            var everLoggedIn by remember { mutableStateOf(!needsLogin) }
            LaunchedEffect(needsLogin) { if (!needsLogin) everLoggedIn = true }
            if (everLoggedIn) key(server) { MainScreen(repo, appScope) }
            if (needsLogin) LoginScreen(repo)
            pendingPair?.let { PairConfirmDialog(repo, it, appScope) }
            pendingShare?.let { sc ->
                if (needsLogin) LaunchedEffect(sc) { repo.notify(UiText.Res(R.string.share_sign_in_first)) }   // nach Anmeldung erscheint der Dialog
                else ShareDialog(repo, sc) { id -> repo.openPageRequest.value = id }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
    }
}

/** Wie lange eine Meldung mit Aktion (z. B. "Rueckgaengig") stehen bleibt */
const val ACTION_SNACKBAR_MS = 8_000L

private data class MainItem(val dest: Dest, @StringRes val label: Int, val icon: ImageVector)

private val mainItems = listOf(
    MainItem(Dest.Today, R.string.nav_today, Icons.Filled.Home),
    MainItem(Dest.Tasks, R.string.nav_tasks, Icons.Filled.CheckCircle),
    MainItem(Dest.Events, R.string.nav_events, Icons.Filled.DateRange),
    MainItem(Dest.Services, R.string.nav_services, Icons.Filled.Build),
    MainItem(Dest.Search, R.string.nav_search, Icons.Filled.Search),
)

/** Adaptiv: Handy = Seitenleiste als Schublade; Tablet hochkant = Navigation Rail + Schublade; breit = feste Seitenleiste */
@Composable
fun MainScreen(repo: SagaRepository, appScope: CoroutineScope) {
    val nav = rememberSaveable(saver = AppNavigator.Saver) { AppNavigator() }   // ueberlebt Prozess-Tod
    val openRequest by repo.openPageRequest.collectAsState()
    LaunchedEffect(openRequest) {
        openRequest?.let { nav.top(Dest.Page(it)); repo.openPageRequest.value = null }
    }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {      // alle 60 s auffrischen, solange die App vorne ist
            while (true) {
                delay(60_000)
                repo.refresh()
            }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val medium = maxWidth >= 600.dp
        val chrome = Chrome(showMenu = !wide && !medium, onMenu = { scope.launch { drawerState.open() } })
        val closeDrawer: () -> Unit = { if (!wide) scope.launch { drawerState.close() } }

        val showShared by repo.showSharedRequest.collectAsState()
        var seenShared by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(repo.showSharedRequest.value) }
        LaunchedEffect(showShared) {
            if (showShared != seenShared) {
                seenShared = showShared
                if (!wide) drawerState.open()
            }
        }
        BackHandler(enabled = drawerState.isOpen && !wide) { scope.launch { drawerState.close() } }
        BackHandler(enabled = nav.canGoBack && !drawerState.isOpen) { nav.pop() }

        if (wide) {
            PermanentNavigationDrawer(drawerContent = {
                PermanentDrawerSheet(Modifier.width(300.dp)) { Sidebar(repo, nav, onNavigate = {}) }
            }) {
                Content(repo, nav, chrome, appScope)
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { ModalDrawerSheet(Modifier.width(320.dp)) { Sidebar(repo, nav, onNavigate = closeDrawer) } },
            ) {
                if (medium) {
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail(Modifier.statusBarsPadding(), header = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, stringResource(R.string.pages)) }
                        }) {
                            mainItems.forEach { it ->
                                NavigationRailItem(
                                    selected = nav.root == it.dest, onClick = { nav.top(it.dest) },
                                    icon = { Icon(it.icon, null) }, label = { Text(stringResource(it.label)) },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            NavigationRailItem(
                                selected = nav.root == Dest.Settings, onClick = { nav.top(Dest.Settings) },
                                icon = { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.nav_settings)) },
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                        Box(Modifier.weight(1f)) { Content(repo, nav, chrome, appScope) }
                    }
                } else {
                    Content(repo, nav, chrome, appScope)
                }
            }
        }
    }
}

@Composable
private fun Content(repo: SagaRepository, nav: AppNavigator, chrome: Chrome, appScope: CoroutineScope) {
    val minApp by repo.minApp.collectAsState()
    Column(Modifier.fillMaxSize()) {
        if (minApp != null && repo.appTooOld) {                 // Server ist neuer, als diese App kann
            Text(
                stringResource(R.string.app_too_old_banner, minApp ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer)
                    .statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            when (val d = nav.current) {
                Dest.Today -> TodayScreen(repo, nav, chrome)
                Dest.Tasks -> TasksScreen(repo, nav, chrome)
                Dest.Events -> EventsScreen(repo, nav, chrome)
                Dest.Services -> ServicesScreen(repo, nav, chrome)
                Dest.Search -> SearchScreen(repo, nav, chrome)
                Dest.Settings -> SettingsScreen(repo, nav, chrome)
                is Dest.Page -> key(d.id) { PageScreen(d.id, repo, nav, chrome, appScope) }
            }
        }
    }
}
