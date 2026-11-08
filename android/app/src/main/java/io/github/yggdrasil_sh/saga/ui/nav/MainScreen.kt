package io.github.yggdrasil_sh.saga.ui.nav

import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.theme.SagaColors
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
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
import io.github.yggdrasil_sh.saga.ui.notifications.NotificationsScreen
import io.github.yggdrasil_sh.saga.ui.chat.ChatsScreen
import io.github.yggdrasil_sh.saga.ui.chat.ChatDevicesScreen
import io.github.yggdrasil_sh.saga.ui.chat.ChatLinkApproval
import io.github.yggdrasil_sh.saga.ui.push.PushScreen
import kotlinx.coroutines.CoroutineScope
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
    // TT 8: anderes Konto (gleicher Server) -> frische Navigation, nicht der letzte Bildschirm des alten Kontos
    val account by repo.account.collectAsState()
    val pendingPair by repo.pendingPair.collectAsState()
    val pendingShare by repo.pendingShare.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    // Design-Runde 4: Hoehe der unteren Leiste (Phone), damit Meldungen darueber stehen statt sie zu verdecken
    val bottomBarHeight = remember { mutableStateOf(0.dp) }
    val res = LocalResources.current
    val a11y = LocalAccessibilityManager.current
    LaunchedEffect(Unit) {
        // Eine neue Meldung ersetzt die angezeigte (statt bis zu 8 s in der Schlange zu warten) - AUSSER die angezeigte
        // hat eine Aktion ("Block gelöscht · Rückgängig") und die neue nicht: dann kommt die neue danach. Sonst war
        // "Rückgängig" schon nach ~3 s weg, weil gleich darauf eine andere Meldung kam (TT 2.4 N-12).
        var job: kotlinx.coroutines.Job? = null
        var jobHasAction = false
        repo.messages.collect { m ->
            val prev = job
            val keep = SnackPolicy.keepCurrent(prev?.isActive == true, jobHasAction, m.action != null)
            if (!keep) prev?.cancel()
            jobHasAction = m.action != null
            job = launch {
                if (keep) prev?.join()
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
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (ready) {                                             // erst nach dem Laden der Einstellungen (im Hintergrund)
            var everLoggedIn by remember { mutableStateOf(!needsLogin) }
            LaunchedEffect(needsLogin) { if (!needsLogin) everLoggedIn = true }
            // NAV-02: Offline-Zustand hier, damit Leiste und Live-Laempchen denselben (entprellten) Stand sehen
            val offline = key(server, account) { io.github.yggdrasil_sh.saga.ui.common.rememberOfflineState(repo) }
            if (everLoggedIn) key(server, account) { MainScreen(repo, appScope, offline, bottomBarHeight) }
            // R1-Nachbesserung: solange die Offline-Leiste steht, sagt sie den Zustand - das Laempchen wuerde direkt
            // auf ihrem Wolken-Symbol sitzen und entfaellt dann
            if (everLoggedIn && !needsLogin && !offline.visible) LiveLamp(repo, Modifier.align(Alignment.TopStart))   // 4.11.: gruen live, rot gespeicherter Stand
            if (needsLogin) LoginScreen(repo)
            pendingPair?.let { PairConfirmDialog(repo, it, appScope) }
            io.github.yggdrasil_sh.saga.ui.login.PairRejectedDialog(repo)
            io.github.yggdrasil_sh.saga.ui.common.LocalNetworkPrompt(repo)     // Android 17: Server im lokalen Netz
            pendingShare?.let { sc ->
                if (needsLogin) LaunchedEffect(sc) { repo.notify(UiText.Res(R.string.share_sign_in_first)) }   // nach Anmeldung erscheint der Dialog
                else ShareDialog(repo, sc) { id -> repo.openPageRequest.value = id }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter)
                .then(if (bottomBarHeight.value > 0.dp && !needsLogin) Modifier.padding(bottom = bottomBarHeight.value) else Modifier.navigationBarsPadding())
                .imePadding(),
        )
    }
}

/** Wie lange eine Meldung mit Aktion (z. B. "Rueckgaengig") stehen bleibt */
const val ACTION_SNACKBAR_MS = 8_000L

/** Reihenfolge der Meldungen (N-12) */
object SnackPolicy {
    /** Laufende Meldung mit Aktion bleibt stehen, wenn eine Meldung ohne Aktion kommt (die kommt danach) */
    fun keepCurrent(currentActive: Boolean, currentHasAction: Boolean, nextHasAction: Boolean): Boolean =
        currentActive && currentHasAction && !nextHasAction
}

/**
 * Adaptiv: Handy = untere Leiste (Heute · Seiten · Suchen · Chats) + Seitenleiste als Schublade; Tablet hochkant =
 * Navigation Rail mit denselben Zielen + Schublade; breit = feste Seitenleiste, in den Chats zur Rail eingeklappt
 * (Design-Runde 6, NAV-19/CH-14)
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    repo: SagaRepository, appScope: CoroutineScope,
    offline: io.github.yggdrasil_sh.saga.ui.common.OfflineState = io.github.yggdrasil_sh.saga.ui.common.rememberOfflineState(repo),
    bottomBarHeight: androidx.compose.runtime.MutableState<androidx.compose.ui.unit.Dp> = remember { mutableStateOf(0.dp) },
) {
    val nav = rememberSaveable(saver = AppNavigator.Saver) { AppNavigator() }   // ueberlebt Prozess-Tod
    // 2.5 clientlog1: grober Ort fuer Fehlerberichte/Diagnose (nur die Art der Ansicht, nie Titel oder ID)
    val curDest = nav.current
    LaunchedEffect(curDest) { io.github.yggdrasil_sh.saga.diag.ClientLogger.view(Dest.encode(curDest)) }
    val openRequest by repo.openPageRequest.collectAsState()
    LaunchedEffect(openRequest) {
        openRequest?.let { nav.top(Dest.Page(it)); repo.openPageRequest.value = null }
    }
    // Homescreen-Widget: Heute / Aufgaben / Termine / Seite / neue Aufgabe
    val openDest by repo.openDestRequest.collectAsState()
    LaunchedEffect(openDest) {
        openDest?.let { s ->
            if (s == "new-task" || s.startsWith("new-task:")) {
                // 2.5 E2: optionales Ziel "new-task:<db>" (Aufgabenquelle), sonst Inbox
                repo.newTaskTarget.value = io.github.yggdrasil_sh.saga.widget.WidgetLinks.newTaskTarget(s)
                nav.top(Dest.Today)
                repo.focusQuickAdd.value = true
            } else {
                Dest.decode(s)?.let { nav.top(it) }
            }
            repo.openDestRequest.value = null
        }
    }
    val openChat by repo.openChatRequest.collectAsState()
    LaunchedEffect(openChat) {
        openChat?.let {
            nav.top(Dest.Chats)
            nav.push(Dest.Chat(it))
            repo.openChatRequest.value = null
        }
    }
    // 2.3: Mitteilung "Backup fehlgeschlagen" -> Einstellungen -> Mein Backup; Admin-Meldung -> Admin-Bereich im Browser
    val openSettings by repo.openSettingsRequest.collectAsState()
    LaunchedEffect(openSettings) {
        openSettings?.let { sec ->
            io.github.yggdrasil_sh.saga.ui.settings.SettingsNav.open(nav, sec, replace = true)   // 2.5: Bereich + Unterseite
            repo.openSettingsRequest.value = null
        }
    }
    val openExternal by repo.openExternalRequest.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(openExternal) {
        openExternal?.let { u -> io.github.yggdrasil_sh.saga.ui.common.openUrl(ctx, u); repo.openExternalRequest.value = null }
    }
    // Nach dem ersten Anruf ohne UnifiedPush: Hinweis "klingelt nur bei offener App" + Einrichten
    val callPushHint by repo.push.callHint.collectAsState()
    val inCall by repo.calls.ui.collectAsState()
    if (callPushHint && inCall == null) {
        io.github.yggdrasil_sh.saga.ui.settings.CallPushHintDialog(
            onSetup = { repo.push.callHintDone(); nav.push(Dest.Push) },
            onDismiss = { repo.push.callHintDone() },
        )
    }
    // fix27 E2: beim Kaltstart die eigene Startseite oeffnen (Heute bleibt darunter, "Zurueck" fuehrt dorthin)
    var startChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val sp = try {
            repo.loadStartPage()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null                                               // offline/alter Server: wie ohne Startseite
        }
        val onlyHome = nav.stack.size == 1 && nav.stack.first() == Dest.Today
        if (io.github.yggdrasil_sh.saga.data.StartPage.openOnColdStart(sp, startChecked, onlyHome)) nav.push(Dest.Page(sp!!))
        startChecked = true
    }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // NAV-04: Schublade geht auf (Knopf, Wischen, "Mit mir geteilt") -> Fokus loesen und Tastatur schliessen,
    // sonst verdeckt sie die halbe Leiste (z. B. aus der Suche)
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) {
        // solange die App vorne ist: alle 60 s auffrischen, mit verbundenem Live-Kanal seltener (RefreshPolicy)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { repo.pollWhileForeground() }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        val wide = widthDp >= NavModel.WIDE_DP
        // Design-Runde 6: Leiste (Phone), Rail (hochkant; quer in den Chats) oder feste Seitenleiste (quer)
        val layout = NavModel.layout(widthDp, nav.root)
        val compact = layout == NavModel.Layout.BAR
        val tabsShown = NavModel.tabsShown(layout)
        // Design-Runde 4 (NAV-09): untere Leiste nur bei kompakter Breite; aus im Editor, im Chat und bei Tastatur
        val imeVisible = WindowInsets.isImeVisible
        val bottomBar = NavModel.showBottomBar(compact, nav.current, imeVisible)
        val openDrawer: () -> Unit = { focusManager.clearFocus(); keyboard?.hide(); scope.launch { drawerState.open() } }
        // NAV-08: Glocke (Blatt von unten, r4-notify) und Lupe als Kopf-Aktionen der Hauptziele; NAV-19: mit Rail wie am
        // Phone nur die Glocke (Suchen steht in der Rail, die Rail hat keine eigene Glocke mehr)
        var bellOpen by rememberSaveable { mutableStateOf(false) }
        val notifOn by repo.live.supported.collectAsState()
        val unreadGroups by io.github.yggdrasil_sh.saga.ui.notifications.NotificationCounts.unreadGroups.collectAsState()
        val head = NavModel.head(nav.current, nav.canGoBack, tabsShown, notifOn)
        val headActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            if (head.search) {
                IconButton(onClick = { nav.top(Dest.Search) }) { SagaIcon(SagaIcons.search, stringResource(R.string.r4nav_search)) }
            }
            if (head.bell) BellButton(unreadGroups) { focusManager.clearFocus(); keyboard?.hide(); bellOpen = true }
        }
        val chrome = Chrome(showMenu = compact, onMenu = openDrawer, headActions = headActions)
        LaunchedEffect(bottomBar) { if (!bottomBar) bottomBarHeight.value = 0.dp }
        val modal = layout != NavModel.Layout.SIDEBAR
        val closeDrawer: () -> Unit = { if (modal) scope.launch { drawerState.close() } }
        // Wechsel auf die feste Seitenleiste (Drehen, Chats verlassen): eine offene Schublade schliesst sofort
        // R6-Bilder: auch beim Wechsel in die Rail (Chats quer, Drehen) geschlossen beginnen
        LaunchedEffect(modal) { if (drawerState.currentValue == DrawerValue.Open) drawerState.snapTo(DrawerValue.Closed) }

        val showShared by repo.showSharedRequest.collectAsState()
        var seenShared by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(repo.showSharedRequest.value) }
        LaunchedEffect(showShared) {
            if (showShared != seenShared) {
                seenShared = showShared
                if (modal) drawerState.open()
            }
        }
        BackHandler(enabled = drawerState.isOpen && modal) { scope.launch { drawerState.close() } }
        BackHandler(enabled = nav.canGoBack && !drawerState.isOpen) { nav.pop() }
        // oberste Ebene ausser "Heute": Zurueck fuehrt zum Startziel, erst dort beendet es die App (BUG-106)
        BackHandler(enabled = !nav.canGoBack && nav.root != Dest.Today && !drawerState.isOpen) { nav.top(Dest.Today) }

        // Inhalt wandert beim Drehen/Wechsel Kompakt <-> Breit in einen anderen Zweig (feste Seitenleiste, Rail,
        // Schublade). movableContentOf nimmt dabei den Zustand mit (Chat-Entwurf, offene Blaetter, Editor) - BUG-021
        val content = remember { movableContentOf { c: Chrome -> Content(repo, nav, c, appScope, offline) } }
        val drawerOpen = drawerState.targetValue == DrawerValue.Open
        val rail: @Composable () -> Unit = { SagaRail(repo, nav, drawerOpen, onPages = openDrawer) }
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = modal,
            drawerContent = {
                // mit fester Seitenleiste keine zweite (verborgene) Seitenleiste in der Schublade
                // leer, aber mit fester Breite: ohne Inhalt (0 dp) fallen die Anker fuer offen/zu zusammen und die
                // Schublade gilt nach dem Wechsel in die Rail als offen (R6-Bilder 43x/92x)
                ModalDrawerSheet(Modifier.width(320.dp)) {
                    if (modal) Sidebar(repo, nav, onNavigate = closeDrawer, bottomBar = tabsShown, openOver = NavModel.drawerOpensOver(layout, widthDp))
                }
            },
        ) {
            if (!compact) {
                Row(Modifier.fillMaxSize()) {
                    if (wide) WideSide(railMode = layout == NavModel.Layout.RAIL, rail = rail) { Sidebar(repo, nav, onNavigate = {}) }
                    else rail()
                    Box(Modifier.weight(1f)) { content(chrome) }
                }
            } else {
                // Phone: Inhalt + untere Leiste (Heute · Seiten · Suchen · Chats); solange sie steht, nimmt sie die
                // Navigationsleiste des Systems ein - der Bildschirm darueber rechnet sie nicht noch einmal ein
                val density = androidx.compose.ui.platform.LocalDensity.current
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f)
                            .then(if (bottomBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                    ) { content(chrome) }
                    if (bottomBar) {
                        SagaBottomBar(
                            repo, nav, drawerOpen = drawerOpen,
                            onPages = openDrawer,
                            modifier = Modifier.onSizeChanged { bottomBarHeight.value = with(density) { it.height.toDp() } },
                        )
                    }
                }
            }
        }
        if (bellOpen) {
            io.github.yggdrasil_sh.saga.ui.notifications.NotificationsSheet(
                onDismiss = { bellOpen = false },
                onOpen = { item ->
                    bellOpen = false
                    io.github.yggdrasil_sh.saga.ui.notifications.open(repo, nav, item)
                },
                onShowAll = { bellOpen = false; nav.push(Dest.Notifications) },
            )
        }
    }
}

/** NAV-08: Glocke mit Zaehler (ungelesene Gruppen); TalkBack sagt den Zaehler als Text an */
@Composable
private fun BellButton(count: Int, onClick: () -> Unit) {
    val res = LocalResources.current
    val label = stringResource(R.string.notifications)
    val desc = if (count > 0) label + ", " + res.getQuantityString(R.plurals.r4nav_unread, count, count) else label
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = desc }) {
        val text = NavModel.badgeText(count)
        Box(Modifier.clearAndSetSemantics {}) {
            if (text == null) SagaIcon(SagaIcons.bell, null)
            else androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge { Text(text) } }) { SagaIcon(SagaIcons.bell, null) }
        }
    }
}

/**
 * NAV-09 (Entscheidung 1): untere Leiste am Phone wie Web am Handy - Heute · Seiten · Suchen · Chats.
 * "Seiten" oeffnet die Schublade mit dem Seitenbaum; Chats mit Zaehler ungelesener Gespraeche.
 */
@Composable
private fun SagaBottomBar(repo: SagaRepository, nav: AppNavigator, drawerOpen: Boolean, onPages: () -> Unit, modifier: Modifier = Modifier) {
    val chatOn by repo.live.chatEnabled.collectAsState()
    val chatUnread = rememberUnreadChats(repo)
    val accent = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current
    val selected = NavModel.selectedTab(nav.root, drawerOpen)
    androidx.compose.material3.NavigationBar(modifier, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        NavModel.tabs(chatOn).forEach { tab ->
            val (label, icon) = tabLook(tab)
            val badge = if (tab == NavModel.Tab.CHATS) chatUnread else 0
            val text = stringResource(label)
            val count = if (tab == NavModel.Tab.CHATS) unreadChatsText(badge) else null
            NavigationBarItem(
                selected = selected == tab,
                onClick = {
                    when (tab) {
                        NavModel.Tab.TODAY -> nav.top(Dest.Today)
                        NavModel.Tab.PAGES -> onPages()
                        NavModel.Tab.SEARCH -> nav.top(Dest.Search)
                        NavModel.Tab.CHATS -> nav.top(Dest.Chats)
                    }
                },
                // Zaehler als Text angesagt ("1 ungelesenes Gespraech"), nicht als nackte Zahl; Beschriftung liest die Leiste selbst
                icon = { Box(Modifier.clearAndSetSemantics { if (count != null) contentDescription = count }) { BadgeIcon(badge) { SagaIcon(icon, null) } } },
                label = { NavLabel(text) },
                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                    indicatorColor = accent.goldSoft, selectedIconColor = accent.onGoldSoft, selectedTextColor = accent.onGoldSoft,
                ),
            )
        }
    }
}

/** Ziel eines Leisten-/Rail-Eintrags: Beschriftung und Symbol (gleich in Phone-Leiste und Rail) */
private fun tabLook(tab: NavModel.Tab): Pair<Int, Int> = when (tab) {
    NavModel.Tab.TODAY -> R.string.nav_today to SagaIcons.today
    NavModel.Tab.PAGES -> R.string.pages to SagaIcons.file
    NavModel.Tab.SEARCH -> R.string.r4nav_search to SagaIcons.search
    NavModel.Tab.CHATS -> R.string.chats to SagaIcons.chats
}

/**
 * Design-Runde 6 (NAV-19): Navigation Rail mit den Zielen der Phone-Leiste - Heute · Seiten · Suchen · Chats (mit Zaehler),
 * unten Einstellungen. "Seiten" oeffnet den Seitenbaum als Schublade; Aufgaben, Termine, Dienste stehen dort,
 * Benachrichtigungen ueber die Glocke im Kopf.
 */
@Composable
private fun SagaRail(repo: SagaRepository, nav: AppNavigator, drawerOpen: Boolean, onPages: () -> Unit) {
    val chatOn by repo.live.chatEnabled.collectAsState()
    val chatUnread = rememberUnreadChats(repo)
    val accent = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current
    val selected = NavModel.selectedTab(nav.root, drawerOpen, nav.current)
    val colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
        indicatorColor = accent.goldSoft, selectedIconColor = accent.onGoldSoft, selectedTextColor = accent.onGoldSoft,
    )
    NavigationRail(Modifier.statusBarsPadding(), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Spacer(Modifier.size(8.dp))
        NavModel.railTabs(chatOn).forEach { tab ->
            val (label, icon) = tabLook(tab)
            val badge = if (tab == NavModel.Tab.CHATS) chatUnread else 0
            val count = if (tab == NavModel.Tab.CHATS) unreadChatsText(badge) else null
            NavigationRailItem(
                selected = selected == tab,
                onClick = {
                    when (tab) {
                        NavModel.Tab.TODAY -> nav.top(Dest.Today)
                        NavModel.Tab.PAGES -> onPages()
                        NavModel.Tab.SEARCH -> nav.top(Dest.Search)
                        NavModel.Tab.CHATS -> nav.top(Dest.Chats)
                    }
                },
                icon = { Box(Modifier.clearAndSetSemantics { if (count != null) contentDescription = count }) { BadgeIcon(badge) { SagaIcon(icon, null) } } },
                label = { NavLabel(stringResource(label)) },
                colors = colors,
            )
        }
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = NavModel.railSettingsSelected(nav.root, drawerOpen), onClick = { nav.top(Dest.Settings) },
            icon = { SagaIcon(SagaIcons.settings, null) }, label = { NavLabel(stringResource(R.string.nav_settings)) },
            colors = colors, modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

/**
 * CH-14: Seite links im Querformat - feste Seitenleiste (300 dp), in den Chats zur Rail (80 dp) eingeklappt. Breite
 * gleitet dezent, Inhalte blenden ueber; "Animationen entfernen" = sofort. Der Zustand der Seitenleiste (aufgeklappte
 * Zweige, Scrollstand) bleibt ueber den Wechsel erhalten.
 */
@Composable
private fun WideSide(railMode: Boolean, rail: @Composable () -> Unit, sidebar: @Composable () -> Unit) {
    val reduced = io.github.yggdrasil_sh.saga.ui.theme.LocalReducedMotion.current
    val motion = io.github.yggdrasil_sh.saga.ui.theme.SagaMotion.current
    val target = (if (railMode) NavModel.RAIL_DP else NavModel.SIDEBAR_DP).dp
    val width by androidx.compose.animation.core.animateDpAsState(
        target, if (reduced) androidx.compose.animation.core.snap() else motion.defaultSpatial(), label = "side-width",
    )
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Box(Modifier.width(width).fillMaxHeight().clipToBounds()) {
        androidx.compose.animation.Crossfade(
            railMode, animationSpec = if (reduced) androidx.compose.animation.core.snap() else motion.fastEffects(), label = "side",
        ) { r ->
            if (r) rail()
            else holder.SaveableStateProvider("sidebar") {
                // feste Breite, beim Einklappen abgeschnitten statt gestaucht
                Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(NavModel.SIDEBAR_DP.dp).fillMaxHeight()) {
                    PermanentDrawerSheet(Modifier.width(NavModel.SIDEBAR_DP.dp)) { sidebar() }
                }
            }
        }
    }
}

@Composable
private fun Content(repo: SagaRepository, nav: AppNavigator, chrome: Chrome, appScope: CoroutineScope, offline: io.github.yggdrasil_sh.saga.ui.common.OfflineState) {
    val minApp by repo.minApp.collectAsState()
    // Laufender Anruf: Leiste "Anruf laeuft" ueber allen Bildschirmen; sie nimmt die Statusleiste ein, der Inhalt
    // darunter rechnet sie daher nicht noch einmal ein (consumeWindowInsets)
    val call by repo.calls.ui.collectAsState()
    // 2.4 E4/E5: Anruf laeuft auf einem anderen eigenen Geraet bzw. Gruppenanruf ohne mich -> Leiste mit Beitreten
    val runBar by repo.calls.run.collectAsState()
    val ongoing = io.github.yggdrasil_sh.saga.call.CallReturn.active(call)
    val callBar = ongoing || runBar != null
    Column(Modifier.fillMaxSize()) {
        io.github.yggdrasil_sh.saga.call.OngoingCallBar(call)
        if (!ongoing) io.github.yggdrasil_sh.saga.call.RunningCallBar(repo.calls, call, statusBar = true)
        // R5-Fix: die gruene Leiste ist sichtbar - das Band "Zum Anruf zurueckkehren" im Chat entfaellt dann
        androidx.compose.runtime.CompositionLocalProvider(io.github.yggdrasil_sh.saga.call.LocalCallBarShown provides ongoing) {
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .then(if (callBar) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
        ) {
            val tooOld = minApp != null && repo.appTooOld
            if (tooOld) {                                           // Server ist neuer, als diese App kann
                Text(
                    stringResource(R.string.app_too_old_banner, minApp ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer)
                        .statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            // NAV-02: globale Offline-Leiste unter der Anruf-Leiste (entprellt); sie nimmt die Statusleiste ein, wenn
            // nichts darueber liegt - der Bildschirm darunter rechnet sie dann nicht noch einmal ein
            io.github.yggdrasil_sh.saga.ui.common.OfflineBar(
                offline, if (tooOld) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier,
            )
            Box(
                Modifier.weight(1f)
                    .then(if (tooOld || offline.visible) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            ) {
                // Wechsel zwischen Bildschirmen: vor = leicht von rechts, zurueck = von links, oberste Ebene = Ueberblenden.
                // contentKey = Ziel: gleicher Bildschirm (z. B. andere Stapeltiefe) bleibt ohne Animation.
                val reduced = io.github.yggdrasil_sh.saga.ui.theme.LocalReducedMotion.current
                val spatial = io.github.yggdrasil_sh.saga.ui.theme.SagaMotion.current.defaultSpatial<androidx.compose.ui.unit.IntOffset>()
                val fadeIn = io.github.yggdrasil_sh.saga.ui.theme.SagaMotion.current.defaultEffects<Float>()
                val fadeOut = io.github.yggdrasil_sh.saga.ui.theme.SagaMotion.current.fastEffects<Float>()
                androidx.compose.animation.AnimatedContent(
                    targetState = nav.current to nav.stack.size,
                    contentKey = { it.first },
                    transitionSpec = { screenTransition(initialState.second, targetState.second, reduced, spatial, fadeIn, fadeOut) },
                    label = "screen",
                ) { (d, _) ->
                when (d) {
                    Dest.Today -> TodayScreen(repo, nav, chrome)
                    Dest.Tasks -> TasksScreen(repo, nav, chrome)
                    Dest.Events -> EventsScreen(repo, nav, chrome)
                    Dest.Services -> ServicesScreen(repo, nav, chrome)
                    Dest.Search -> SearchScreen(repo, nav, chrome)
                    Dest.Settings -> SettingsScreen(repo, nav, chrome)
                    Dest.SettingsAccount -> io.github.yggdrasil_sh.saga.ui.settings.SettingsAccountScreen(repo, nav, chrome)
                    Dest.SettingsGeneral -> io.github.yggdrasil_sh.saga.ui.settings.SettingsGeneralScreen(repo, nav, chrome)
                    Dest.SettingsInfo -> io.github.yggdrasil_sh.saga.ui.settings.SettingsInfoScreen(repo, nav, chrome)
                    Dest.SettingsNotifications -> io.github.yggdrasil_sh.saga.ui.settings.SettingsNotificationsScreen(repo, nav, chrome)
                    Dest.SettingsChats -> io.github.yggdrasil_sh.saga.ui.settings.SettingsChatsScreen(repo, nav, chrome)
                    is Dest.Page -> key(d.id) { PageScreen(d.id, repo, nav, chrome, appScope) }
                    Dest.Notifications -> NotificationsScreen(repo, nav, chrome)
                    Dest.Chats -> ChatsScreen(repo, nav, chrome, null)
                    is Dest.Chat -> ChatsScreen(repo, nav, chrome, d.id)
                    Dest.Push -> PushScreen(repo, nav, chrome)
                    Dest.ChatDevices -> ChatDevicesScreen(repo, nav, chrome)
                    Dest.Backup -> io.github.yggdrasil_sh.saga.ui.settings.BackupScreen(repo, nav, chrome)
                    Dest.Nextcloud -> io.github.yggdrasil_sh.saga.ui.settings.NextcloudScreen(repo, nav, chrome)
                    is Dest.Graph -> io.github.yggdrasil_sh.saga.ui.graph.GraphScreen(repo, nav, chrome, d.focus)
                    Dest.Trash -> io.github.yggdrasil_sh.saga.ui.pages.TrashScreen(repo, nav, chrome)
                }
                }
            }
            // Kopplungsanfrage eines neuen Chat-Geraets (Live chat.link): hier bestaetigen (Code vergleichen)
                ChatLinkApproval(repo) { nav.push(Dest.ChatDevices) }
        }
        }
    }
}

/** Bildschirmwechsel (dezent): Tiefe waechst = vor, schrumpft = zurueck, gleich = Ueberblenden; reduziert = ohne */
private fun screenTransition(
    from: Int, to: Int, reduced: Boolean,
    spatial: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntOffset>,
    fadeIn: androidx.compose.animation.core.FiniteAnimationSpec<Float>,
    fadeOut: androidx.compose.animation.core.FiniteAnimationSpec<Float>,
): androidx.compose.animation.ContentTransform {
    if (reduced) return androidx.compose.animation.ContentTransform(androidx.compose.animation.EnterTransition.None, androidx.compose.animation.ExitTransition.None)
    val dir = to.compareTo(from)
    val enter = androidx.compose.animation.fadeIn(fadeIn) + when {
        dir > 0 -> androidx.compose.animation.slideInHorizontally(spatial) { it / 8 }
        dir < 0 -> androidx.compose.animation.slideInHorizontally(spatial) { -it / 8 }
        else -> androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(220), initialScale = 0.98f)
    }
    return androidx.compose.animation.ContentTransform(enter, androidx.compose.animation.fadeOut(fadeOut))
}

/** Symbol mit Zaehler (Badge), 0 = ohne */
@Composable
fun BadgeIcon(count: Int, icon: @Composable () -> Unit) {
    when {
        // NavModel.CHATS_UNKNOWN: es gibt Ungelesenes, Zahl der Gespraeche noch nicht bekannt -> Punkt ohne Zahl
        count < 0 -> androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge() }) { icon() }
        count == 0 -> icon()
        else -> androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge { Text(if (count > 99) "99+" else "$count") } }) { icon() }
    }
}

/**
 * R4-Nachbesserung 46b: ungelesene Gespraeche fuer das Chats-Badge (Leiste, Rail, Seitenleiste). Ist die Chat-Liste noch
 * nicht geladen, wird sie einmal still nachgeladen (ohne eingerichteten Chat bleibt es beim Punkt ohne Zahl).
 */
@Composable
internal fun rememberUnreadChats(repo: SagaRepository): Int {
    val total by repo.live.chatUnread.collectAsState()
    val chats by repo.chat.chats.collectAsState()
    val loaded by repo.chat.chatsLoaded.collectAsState()
    LaunchedEffect(total > 0, loaded) { if (total > 0 && !loaded) repo.chat.loadChats() }
    return NavModel.unreadChats(if (loaded) chats else null, total)
}

/** TalkBack-Text zum Chats-Badge ("1 ungelesenes Gespraech"); null = nichts ungelesen */
@Composable
internal fun unreadChatsText(count: Int): String? {
    val res = LocalResources.current
    return when {
        count < 0 -> res.getString(R.string.r4nav_chats_unread_some)
        count == 0 -> null
        else -> res.getQuantityString(R.plurals.r4nav_unread_chats, count, count)
    }
}

/**
 * 4.11.: Live-Laempchen oben links (wie iOS/Mac) - live, solange der Live-Kanal steht (Inhalte kommen frisch vom
 * Server), sonst gespeicherter Stand. Nur Anzeige, keine Tippflaeche (liegt neben der Statusleiste).
 * Server ohne Live-Kanal: kein Laempchen. A11Y-13: Zustand auch ueber die Form (live = gefuellter Punkt, offline =
 * hohler Ring) und Signalfarben wie im Web (live = SagaColors --ok, offline = --down).
 */
@Composable
private fun LiveLamp(repo: SagaRepository, modifier: Modifier = Modifier) {
    val supported by repo.live.supported.collectAsState()
    val connected by repo.live.connected.collectAsState()
    if (!supported) return
    val desc = stringResource(if (connected) R.string.live_lamp_on else R.string.live_lamp_off)
    val signals = SagaColors.current
    val color by androidx.compose.animation.animateColorAsState(if (connected) signals.ok else signals.down, label = "lamp")
    val shape = androidx.compose.foundation.shape.CircleShape
    Box(
        modifier.statusBarsPadding().padding(start = 4.dp, top = 2.dp).size(8.dp)
            .then(
                if (connected) Modifier.background(color, shape)
                else Modifier.border(1.5.dp, color, shape)
            )
            .semantics { contentDescription = desc },
    )
}
