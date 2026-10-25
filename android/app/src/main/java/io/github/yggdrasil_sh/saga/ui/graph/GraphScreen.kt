package io.github.yggdrasil_sh.saga.ui.graph

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.graph.Camera
import io.github.yggdrasil_sh.saga.graph.GraphBuild
import io.github.yggdrasil_sh.saga.graph.GraphFilter
import io.github.yggdrasil_sh.saga.graph.GraphFrame
import io.github.yggdrasil_sh.saga.graph.GraphHit
import io.github.yggdrasil_sh.saga.graph.GraphLoad
import io.github.yggdrasil_sh.saga.graph.GraphLoader
import io.github.yggdrasil_sh.saga.graph.GraphModel
import io.github.yggdrasil_sh.saga.graph.GraphRunner
import io.github.yggdrasil_sh.saga.graph.NodeKind
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.theme.LocalReducedMotion
import io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.sqrt

/** Server ohne /api/graph (404) merken: Eintrag "Lokaler Graph" im Seitenmenue dann ausblenden (je Server) */
object GraphSupport {
    val unsupportedServer = MutableStateFlow<String?>(null)

    /** Einstieg zeigen? Version unbekannt -> ja (die Ansicht meldet dann "Braucht Server 2.5"); 2.5.0-rc.N zaehlt als 2.5 */
    fun available(serverVersion: String?, server: String): Boolean =
        unsupportedServer.value != server &&
            (serverVersion.isNullOrBlank() || io.github.yggdrasil_sh.saga.util.Versions.atLeast(serverVersion, "2.4.99"))
}

/** Einstieg aus der Navigation: Seitenleiste "Graph" (focus = null) bzw. Seitenmenue "Lokaler Graph" (focus = Seite) */
@Composable
fun GraphScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome, focus: Long?) {
    val server by repo.server.collectAsState()
    GraphScreenContent(
        nav, chrome, focus,
        load = {
            GraphLoader.load { repo.api.get("api/graph") }.also {
                if (it is GraphLoad.Unsupported) GraphSupport.unsupportedServer.value = server
                else if (it is GraphLoad.Ok && GraphSupport.unsupportedServer.value == server) GraphSupport.unsupportedServer.value = null
            }
        },
        titleOf = { id -> repo.pages.value.firstOrNull { it.id == id }?.title },
        onOpenPage = { id -> nav.push(Dest.Page(id)) },
    )
}

/** Farben des Graphen (wie --gr-* im Web-Stylesheet), hell/dunkel */
data class GraphPalette(
    val page: Color, val db: Color, val row: Color, val tag: Color, val line: Color, val lineA: Float,
    val accent: Color, val accentText: Color, val fg: Color, val bg: Color,
)

@Composable
fun graphPalette(): GraphPalette {
    val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    val acc = LocalSagaAccent.current
    val cs = MaterialTheme.colorScheme
    return if (dark) GraphPalette(Color(0xFF9AA6B8), Color(0xFFB39DDB), Color(0xFF7FB0E0), Color(0xFF6CC48A), Color(0xFF8B97A8), 0.32f, acc.gold, acc.goldText, cs.onBackground, cs.background)
    else GraphPalette(Color(0xFF6A7383), Color(0xFF7B5CC0), Color(0xFF3D72AD), Color(0xFF2E8049), Color(0xFF5D6878), 0.30f, acc.gold, acc.goldText, cs.onBackground, cs.background)
}

private enum class Mode { ALL, LOCAL }

/**
 * Die Ansicht ohne Repository (fuer Bildschirmfotos und Geraetetests mit Beispieldaten): load liefert die Daten,
 * onOpenPage oeffnet eine Seite. initialSelect = Knoten-ID, die gleich ausgewaehlt ist (Karte; nur fuer Tests).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreenContent(
    nav: AppNavigator, chrome: Chrome, focus: Long?,
    load: suspend () -> GraphLoad,
    titleOf: (Long) -> String? = { null },
    onOpenPage: (Long) -> Unit,
    initialSelect: Long? = null,
    onRunner: (GraphRunner) -> Unit = {},
) {
    val res = LocalResources.current
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val focusMgr = LocalFocusManager.current
    // TalkBack/Schalterzugriff: zusaetzlich (und zuerst) die Liste der Knoten - ein Canvas allein reicht nicht
    val a11y = remember(ctx) { (ctx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true }

    var mode by rememberSaveable { mutableStateOf(if (focus != null) Mode.LOCAL else Mode.ALL) }
    var depth by rememberSaveable { mutableIntStateOf(1) }
    var fLinks by rememberSaveable { mutableStateOf(true) }
    var fChildren by rememberSaveable { mutableStateOf(true) }
    var fRel by rememberSaveable { mutableStateOf(true) }
    var fTags by rememberSaveable { mutableStateOf(false) }
    var fOrphans by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var asList by rememberSaveable { mutableStateOf(a11y) }
    var showFilters by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf<GraphLoad?>(null) }
    LaunchedEffect(attempt) { loaded = null; loaded = load() }

    val filter = GraphFilter(fLinks, fChildren, fRel, fTags, fOrphans)
    val data = (loaded as? GraphLoad.Ok)?.data
    val untitled = stringResource(R.string.graph_untitled)
    val local = mode == Mode.LOCAL && focus != null
    // Aufbau im Hintergrund (5000 Knoten / 30000 Kanten)
    var model by remember { mutableStateOf<GraphModel?>(null) }
    var relayout by remember { mutableIntStateOf(0) }
    LaunchedEffect(data, filter, local, depth) {
        val d = data ?: run { model = null; return@LaunchedEffect }
        model = withContext(Dispatchers.Default) { GraphBuild.build(d, filter, if (local) focus else null, depth, untitled) }
    }

    val runner = remember { GraphRunner(scope) }
    LaunchedEffect(runner) { onRunner(runner) }
    DisposableEffect(runner) { onDispose { runner.stop() } }
    val frameState = runner.frames.collectAsState()
    var cam by remember { mutableStateOf(Camera()) }
    val camState = remember { object : State<Camera> { override val value get() = cam } }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var autoFit by remember { mutableStateOf(true) }
    var sel by remember { mutableIntStateOf(-1) }
    var shown by remember { mutableStateOf<GraphModel?>(null) }       // Modell, zu dem die Simulation gerade rechnet
    var lastMode by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    val handledRelayout = remember { intArrayOf(0) }
    var pendingZoom by remember { mutableStateOf(initialSelect != null) }
    var zoomReq by remember { mutableIntStateOf(0) }

    LaunchedEffect(model, relayout) {
        val g = model ?: return@LaunchedEffect
        val old = shown
        // Filter umschalten: bekannte Knoten behalten ihre Lage; Ansicht/Tiefe wechseln oder "Neu anordnen": frisch
        val keep = old != null && lastMode == (local to depth) && relayout == handledRelayout[0]
        val prev = if (keep) runner.positions(old!!) else null
        lastMode = local to depth
        handledRelayout[0] = relayout
        val selId = if (old == null) initialSelect else if (sel in 0 until old.n) old.ids[sel] else null
        sel = selId?.let { g.indexOf(it) } ?: -1
        shown = g
        if (!keep) autoFit = true
        runner.start(g, prev, reduced)
    }
    val g = shown
    // Kamera folgt beim Einpendeln dem Graph (weich), zum Schluss genau eingepasst; die Person bewegt -> aus.
    // Liest die Bilder als Fluss (nicht in der Komposition), damit der Bildschirm nicht je Bild neu aufgebaut wird.
    fun follow(f: GraphFrame?, smooth: Boolean) {
        val m = shown
        if (f != null && !f.moving && pendingZoom && m != null && f.x.size == m.n && size.width > 0) {
            pendingZoom = false                                       // vorausgewaehlt (Test): wie Antippen einzoomen
            if (sel in 0 until m.n) {
                cam = Camera.fit(f.x, f.y, m.n, size.width.toFloat(), size.height.toFloat(), with(density) { 48.dp.toPx() }, with(density) { 2.dp.toPx() })
                zoomReq++
                return
            }
        }
        if (f == null || !autoFit || size.width == 0 || m == null || f.x.size != m.n) return
        val pad = with(density) { 48.dp.toPx() }
        val t = Camera.fit(f.x, f.y, m.n, size.width.toFloat(), size.height.toFloat(), pad, with(density) { 2.dp.toPx() })
        val c = cam
        cam = if (!smooth || !f.moving || reduced || (c.k == 1f && c.tx == 0f && c.ty == 0f)) t
        else Camera(c.k + (t.k - c.k) * 0.25f, c.tx + (t.tx - c.tx) * 0.25f, c.ty + (t.ty - c.ty) * 0.25f)
    }
    LaunchedEffect(runner) { runner.frames.collect { follow(it, true) } }
    LaunchedEffect(size, autoFit) { follow(runner.frames.value, false) }
    val radii = remember(g) { g?.let { m -> FloatArray(m.n) { GraphHit.radius(m.deg[it], m.isTag(it)) } } ?: FloatArray(0) }
    val matches = remember(g, query) { g?.let { GraphBuild.search(it, query) } ?: IntArray(0) }
    val matchSet = remember(matches) { matches.toHashSet() }
    // Suche: auf den ersten Treffer zentrieren
    LaunchedEffect(matches) {
        val f = frameState.value
        if (matches.isNotEmpty() && f != null && g != null && f.x.size == g.n && size.width > 0) {
            autoFit = false
            val k = maxOf(cam.k, with(density) { 1.dp.toPx() })
            val i = matches[0]
            cam = Camera(k, size.width / 2f - f.x[i] * k, size.height / 2f - f.y[i] * k)
        }
    }
    val dimTarget = if (sel >= 0 || (query.isNotBlank() && matches.isNotEmpty())) 1f else 0f
    val dim by animateFloatAsState(dimTarget, if (reduced) snap() else tween(180), label = "graph-dim")
    val dimState = remember { object : State<Float> { override val value get() = dim } }
    val pal = graphPalette()

    fun selectZoom(i: Int) {
        val f = frameState.value
        if (i < 0 || g == null || f == null || f.x.size != g.n || size.width == 0) return
        // Knoten + Nachbarn einpassen - nur hineinzoomen, nie heraus (Ueberblick bleibt erreichbar)
        var x0 = f.x[i]; var y0 = f.y[i]; var x1 = x0; var y1 = y0
        for (j in g.adj[i]) { x0 = minOf(x0, f.x[j]); y0 = minOf(y0, f.y[j]); x1 = maxOf(x1, f.x[j]); y1 = maxOf(y1, f.y[j]) }
        val pad = with(density) { 70.dp.toPx() }
        val cardH = with(density) { 130.dp.toPx() }
        val k = minOf((size.width - 2 * pad) / (x1 - x0 + 1f), (size.height - 2 * pad - cardH) / (y1 - y0 + 1f), with(density) { 1.6.dp.toPx() })
        if (k > cam.k * 1.1f) {
            autoFit = false
            cam = Camera(k, size.width / 2f - (x0 + x1) / 2f * k, (size.height - cardH) / 2f - (y0 + y1) / 2f * k)
        }
    }
    LaunchedEffect(zoomReq) { if (zoomReq > 0 && sel >= 0) selectZoom(sel) }
    fun select(i: Int) { sel = i; selectZoom(i) }
    fun open(i: Int) {
        val m = g ?: return
        if (i !in 0 until m.n) return
        if (m.isTag(i)) { query = m.titles[i]; sel = -1; return }
        onOpenPage(m.ids[i])
    }

    val pagesTxt = g?.let { pluralStringResource(R.plurals.graph_pages, it.n, it.n) } ?: ""
    val linksTxt = g?.let { pluralStringResource(R.plurals.graph_links, it.m, it.m) } ?: ""
    val focusTitle = focus?.let { id -> data?.nodes?.firstOrNull { it.id == id }?.title?.ifBlank { untitled } ?: titleOf(id) } ?: ""

    Column(Modifier.fillMaxSize()) {
        SagaTopBar(stringResource(R.string.graph_title), nav, chrome) {
            IconButton(onClick = { asList = !asList }) {
                if (asList) Icon(painterResource(R.drawable.ic_graph), stringResource(R.string.graph_show_canvas))
                else Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.graph_show_list))
            }
            IconButton(onClick = { showFilters = true }) { Icon(painterResource(R.drawable.ic_filter), stringResource(R.string.graph_filters)) }
        }
        // Kopf: Alle Seiten | Lokal, Tiefe, Suche, Zaehler
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f).semantics { contentDescription = res.getString(R.string.graph_mode) }) {
                    SegmentedButton(mode == Mode.ALL, { mode = Mode.ALL; sel = -1 }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.graph_mode_all), maxLines = 1) }
                    SegmentedButton(mode == Mode.LOCAL, { mode = Mode.LOCAL; sel = -1 }, SegmentedButtonDefaults.itemShape(1, 2), enabled = focus != null) {
                        Text(stringResource(R.string.graph_mode_local), maxLines = 1)
                    }
                }
                if (local) {
                    SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 120.dp).semantics { contentDescription = res.getString(R.string.graph_depth) }) {
                        listOf(1, 2).forEachIndexed { i, d ->
                            SegmentedButton(depth == d, { depth = d }, SegmentedButtonDefaults.itemShape(i, 2), icon = {}) { Text("$d") }
                        }
                    }
                }
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp),
                placeholder = { Text(stringResource(R.string.graph_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, stringResource(R.string.graph_search_clear)) } },
                singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusMgr.clearFocus(); if (matches.isNotEmpty() && !asList) select(matches[0]) }),
            )
            val stat = buildString {
                if (g != null && loaded is GraphLoad.Ok) {
                    if (local) append(focusTitle).append(" · ")
                    append(pagesTxt).append(" · ").append(linksTxt)
                    if (data?.truncated == true && !local) append(" · ").append(res.getString(R.string.graph_truncated))
                    if (query.isNotBlank()) append(" · ").append(if (matches.isEmpty()) res.getString(R.string.graph_no_hits) else res.getQuantityString(R.plurals.graph_hits, matches.size, matches.size))
                }
            }
            Text(
                stat, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { size = it }) {
            val st = loaded
            when {
                st == null -> Message(loading = true, title = stringResource(R.string.graph_loading))
                st is GraphLoad.Unsupported -> Message(title = stringResource(R.string.graph_needs_server), sub = stringResource(R.string.graph_needs_server_hint))
                st is GraphLoad.Offline -> Message(title = stringResource(R.string.graph_offline), sub = stringResource(R.string.graph_offline_hint), action = stringResource(R.string.graph_retry)) { attempt++ }
                st is GraphLoad.Failed -> Message(title = stringResource(R.string.graph_error), sub = st.text.asString(res), action = stringResource(R.string.graph_retry)) { attempt++ }
                data != null && data.nodes.isEmpty() -> Message(title = stringResource(R.string.graph_empty), sub = stringResource(R.string.graph_empty_hint))
                g == null -> Message(loading = true, title = stringResource(R.string.graph_loading))
                local && g.missing -> Message(title = stringResource(R.string.graph_local_missing), sub = stringResource(R.string.graph_local_missing_hint), action = stringResource(R.string.graph_mode_all)) { mode = Mode.ALL }
                g.n == 0 -> Message(title = stringResource(R.string.graph_filter_empty), action = stringResource(R.string.graph_filter_reset)) {
                    fLinks = true; fChildren = true; fRel = true; fTags = false; fOrphans = true
                }
                asList -> NodeList(g, matches.takeIf { query.isNotBlank() }, pal) { open(it) }
                else -> {
                    val desc = if (local) res.getString(R.string.graph_local_desc, focusTitle, linksTxt) else res.getString(R.string.graph_canvas_desc, pagesTxt, linksTxt)
                    GraphCanvas(
                        g, frameState, camState, radii, dimState, matchSet.takeIf { query.isNotBlank() && matches.isNotEmpty() }, sel, pal,
                        onCamera = { autoFit = false; cam = it }, onTap = { i -> if (i >= 0 && i == sel) open(i) else select(i) },
                        runner = runner, desc = desc,
                    )
                    if (local && g.n == 1) {
                        Text(
                            stringResource(R.string.graph_local_alone, focusTitle), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp),
                        )
                    }
                    Legend(g, pal, Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 4.dp))
                    SmallFloatingActionButton(
                        onClick = {
                            val f = frameState.value ?: return@SmallFloatingActionButton
                            if (f.x.size == g.n) cam = Camera.fit(f.x, f.y, g.n, size.width.toFloat(), size.height.toFloat(), with(density) { 48.dp.toPx() }, with(density) { 2.dp.toPx() })
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) { Icon(painterResource(R.drawable.ic_fullscreen), stringResource(R.string.graph_fit)) }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = sel in 0 until g.n, modifier = Modifier.align(Alignment.BottomCenter),
                        enter = if (reduced) fadeIn(snap()) else slideInVertically { it / 2 } + fadeIn(), exit = if (reduced) fadeOut(snap()) else slideOutVertically { it / 2 } + fadeOut(),
                    ) {
                        val last = remember { intArrayOf(0) }                 // beim Ausblenden die letzte Karte zeigen
                        if (sel in 0 until g.n) last[0] = sel
                        val i = last[0].coerceIn(0, g.n - 1)
                        NodeCard(g, i, pal, onOpen = { open(i) }, onClose = { sel = -1 })
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).navigationBarsPadding()) {
                Text(stringResource(R.string.graph_filters), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                FilterRow(stringResource(R.string.graph_f_links), fLinks) { fLinks = it }
                FilterRow(stringResource(R.string.graph_f_children), fChildren) { fChildren = it }
                FilterRow(stringResource(R.string.graph_f_relations), fRel) { fRel = it }
                FilterRow(stringResource(R.string.graph_f_tags), fTags) { fTags = it }
                FilterRow(stringResource(R.string.graph_f_orphans), fOrphans, enabled = !local) { fOrphans = it }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { relayout = 1; showFilters = false }) { Text(stringResource(R.string.graph_relayout)) }
                    TextButton(onClick = { fLinks = true; fChildren = true; fRel = true; fTags = false; fOrphans = true }) { Text(stringResource(R.string.graph_filter_reset)) }
                }
            }
        }
    }
}


@Composable
private fun FilterRow(label: String, on: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(on, set, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { set(!on) },
    )
}

@Composable
private fun Message(loading: Boolean = false, title: String, sub: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (loading) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)) }
        else Icon(painterResource(R.drawable.ic_graph), null, Modifier.size(40.dp).padding(bottom = 4.dp), tint = LocalSagaAccent.current.gold)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        if (action != null) FilledTonalButton(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(action) }
    }
}

private fun kindColor(g: GraphModel, i: Int, p: GraphPalette): Color = when (g.kinds[i]) {
    NodeKind.TAG -> p.tag
    NodeKind.DATABASE -> p.db
    NodeKind.ROW -> p.row
    NodeKind.PAGE -> p.page
}

@Composable
private fun kindLabel(k: NodeKind): String = stringResource(
    when (k) { NodeKind.PAGE -> R.string.graph_kind_page; NodeKind.DATABASE -> R.string.graph_kind_database; NodeKind.ROW -> R.string.graph_kind_row; NodeKind.TAG -> R.string.graph_kind_tag },
)

@Composable
private fun Legend(g: GraphModel, p: GraphPalette, modifier: Modifier) {
    val has = remember(g) { g.kinds.toSet() to g.own.any { !it } }
    val items = buildList {
        if (NodeKind.PAGE in has.first) add(Triple(p.page, kindLabel(NodeKind.PAGE), false))
        if (NodeKind.DATABASE in has.first) add(Triple(p.db, kindLabel(NodeKind.DATABASE), false))
        if (NodeKind.ROW in has.first) add(Triple(p.row, kindLabel(NodeKind.ROW), false))
        if (NodeKind.TAG in has.first) add(Triple(p.tag, kindLabel(NodeKind.TAG), false))
        if (has.second) add(Triple(p.page, stringResource(R.string.graph_shared), true))
        if (g.focus >= 0) add(Triple(p.accent, stringResource(R.string.graph_current), false))
    }
    if (items.size < 2) return
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { (c, l, ring) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(9.dp).background(if (ring) c.copy(alpha = 0.35f) else c, CircleShape)
                        .then(if (ring) Modifier.border(1.5.dp, c, CircleShape) else Modifier),
                )
                Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun NodeCard(g: GraphModel, i: Int, p: GraphPalette, onOpen: () -> Unit, onClose: () -> Unit) {
    val acc = LocalSagaAccent.current
    Surface(
        Modifier.navigationBarsPadding().padding(12.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("graph-card"),
        shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(if (i == g.focus) p.accent else kindColor(g, i, p), CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                val icon = g.icons[i].takeIf { it.isNotEmpty() && it.length <= 4 }?.let { "$it " } ?: ""
                Text(icon + g.titles[i], style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val c = g.adj[i].size
                val sub = buildString {
                    append(kindLabel(g.kinds[i]))
                    if (!g.own[i]) append(" · ").append(stringResource(R.string.graph_shared))
                    append(" · ").append(pluralStringResource(R.plurals.graph_links, c, c))
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = acc.goldSoft, contentColor = acc.onGoldSoft),
                modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(if (g.isTag(i)) R.string.graph_filter_tag else R.string.graph_open), fontWeight = FontWeight.SemiBold) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.graph_card_close)) }
        }
    }
}

/** Listenansicht der Knoten (TalkBack): Titel, Art und Anzahl der Verbindungen; Antippen oeffnet */
@Composable
private fun NodeList(g: GraphModel, hits: IntArray?, p: GraphPalette, onOpen: (Int) -> Unit) {
    val order = remember(g, hits) { if (hits != null) hits.sortedByDescending { g.deg[it] }.toIntArray() else GraphBuild.listOrder(g) }
    LazyColumn(Modifier.fillMaxSize().testTag("graph-list")) {
        items(order.toList(), key = { g.ids[it] }) { i ->
            val c = g.adj[i].size
            ListItem(
                leadingContent = { Box(Modifier.size(12.dp).background(if (i == g.focus) p.accent else kindColor(g, i, p).copy(alpha = if (g.own[i]) 1f else 0.5f), CircleShape)) },
                headlineContent = { Text(g.titles[i], maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(buildString {
                        append(kindLabel(g.kinds[i]))
                        if (!g.own[i]) append(" · ").append(stringResource(R.string.graph_shared))
                        if (i == g.focus) append(" · ").append(stringResource(R.string.graph_current))
                    })
                },
                trailingContent = { Text(pluralStringResource(R.plurals.graph_links, c, c), style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.clickable { onOpen(i) },
            )
            HorizontalDivider()
        }
    }
}

/**
 * Zeichenflaeche: liest Positionen, Kamera und Abblenden nur in der Zeichenphase (kein Neuaufbau je Bild).
 * Gesten: ein Finger zieht Hintergrund (verschieben) oder Knoten, zwei Finger zoomen, Antippen waehlt (Karte),
 * nochmals Antippen oeffnet; Mausrad/Trackpad zoomt.
 */
@Composable
private fun GraphCanvas(
    g: GraphModel, frame: State<GraphFrame?>, cam: State<Camera>, radii: FloatArray, dim: State<Float>, matches: Set<Int>?, sel: Int,
    pal: GraphPalette, onCamera: (Camera) -> Unit, onTap: (Int) -> Unit, runner: GraphRunner, desc: String,
) {
    val density = LocalDensity.current
    val dens = density.density
    val painter = remember { GraphPainter() }
    val touchSlopHit = with(density) { 22.dp.toPx() }
    val mouseSlopHit = with(density) { 10.dp.toPx() }
    val minK = 0.04f * dens
    val maxK = 8f * dens
    val latestOnCamera by androidx.compose.runtime.rememberUpdatedState(onCamera)
    val latestOnTap by androidx.compose.runtime.rememberUpdatedState(onTap)
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxSize().testTag("graph-canvas")
            .semantics { contentDescription = desc }
            .pointerInput(g) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Scroll) {
                            val ch = ev.changes.first()
                            val dy = ch.scrollDelta.y
                            if (dy != 0f) { latestOnCamera(cam.value.zoomAt(ch.position.x, ch.position.y, exp(-dy * 0.15f), minK, maxK)); ch.consume() }
                        }
                    }
                }
            }
            .pointerInput(g, radii) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val f = frame.value
                    val mouse = down.type == PointerType.Mouse
                    val hit = if (f != null && f.x.size == g.n) GraphHit.at(f.x, f.y, radii, g.n, cam.value, down.position.x, down.position.y, if (mouse) mouseSlopHit else touchSlopHit) else -1
                    var moved = false
                    var pinch = false
                    var dragging = false
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (dragging) { runner.unpin(); dragging = false }
                            pinch = true; moved = true
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val c = ev.calculateCentroid()
                            var nc = cam.value.pan(pan.x, pan.y)
                            if (c != Offset.Unspecified) nc = nc.zoomAt(c.x, c.y, zoom, minK, maxK)
                            latestOnCamera(nc)
                            ev.changes.forEach { it.consume() }
                        } else if (!pinch) {
                            val ch = pressed[0]
                            if (!moved && (ch.position - down.position).getDistance() > slop) moved = true
                            if (moved) {
                                if (hit >= 0) {
                                    dragging = true
                                    val c = cam.value
                                    runner.pin(hit, c.toWorldX(ch.position.x), c.toWorldY(ch.position.y))
                                } else {
                                    val d = ch.positionChange()
                                    latestOnCamera(cam.value.pan(d.x, d.y))
                                }
                                ch.consume()
                            }
                        }
                    }
                    if (dragging) runner.unpin()
                    if (!moved && !pinch) latestOnTap(hit)
                }
            },
    ) {
        val f = frame.value ?: return@Canvas
        if (f.x.size != g.n) return@Canvas
        drawIntoCanvas { c -> painter.draw(c.nativeCanvas, g, f, cam.value, radii, dim.value, matches, sel, pal, size.width, size.height, dens) }
    }
}

/** Zeichnet mit android.graphics (gebuendelt: Kanten je Art in einem drawLines, Knoten je Farbe) - schnell fuer 2000+ */
class GraphPainter {
    private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
    private val dash = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }
    private val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; style = android.graphics.Paint.Style.STROKE; strokeJoin = android.graphics.Paint.Join.ROUND }
    private val point = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
    private val batches = HashMap<Int, PointBatch>()
    private val dashFx = HashMap<Float, android.graphics.DashPathEffect>()
    private var buf = FloatArray(0)
    private val path = android.graphics.Path()
    private var hl = BooleanArray(0)
    /** Dauer des letzten Bildes (ms, Messung) */
    @Volatile var lastDrawMs = 0.0
        private set
    var drawCount = 0
        private set

    private fun argb(c: Color, a: Float) = c.copy(alpha = (c.alpha * a).coerceIn(0f, 1f)).toArgb()
    private fun ease(x: Float) = x * x * (3 - 2 * x)

    fun draw(
        cv: android.graphics.Canvas, g: GraphModel, f: GraphFrame, cam: Camera, radii: FloatArray, dimRaw: Float, matches: Set<Int>?, sel: Int,
        p: GraphPalette, w: Float, h: Float, dens: Float,
    ) {
        val t0 = System.nanoTime()
        val x = f.x; val y = f.y; val n = g.n
        val k = cam.k
        val kd = k / dens                                             // Zoom in dp je Welteinheit
        val D = ease(dimRaw)
        if (hl.size < n) hl = BooleanArray(n) else java.util.Arrays.fill(hl, 0, n, false)
        var anyHl = false
        if (sel in 0 until n) { hl[sel] = true; for (j in g.adj[sel]) hl[j] = true; anyHl = true }
        else if (matches != null) { for (i in matches) if (i < n) hl[i] = true; anyHl = true }
        val vx0 = -cam.tx / k; val vy0 = -cam.ty / k; val vx1 = (w - cam.tx) / k; val vy1 = (h - cam.ty) / k
        val pad = 40f / kd
        cv.save()
        cv.translate(cam.tx, cam.ty)
        cv.scale(k, k)
        // --- Kanten: Grundschicht je Art gebuendelt, hervorgehobene obendrauf in Gold
        val lw = (0.9f * sqrt(kd) * dens) / k
        val base = p.lineA * (1 - 0.8f * D)
        if (buf.size < g.m * 4) buf = FloatArray(g.m * 4)
        fun edgeVis(a: Int, b: Int) = !((x[a] < vx0 && x[b] < vx0) || (x[a] > vx1 && x[b] > vx1) || (y[a] < vy0 && y[b] < vy0) || (y[a] > vy1 && y[b] > vy1))
        for (pass in 0..1) {                                          // 0: Verweise/Unterseiten, 1: Tags (blasser)
            var c = 0
            for (e in 0 until g.m) {
                val kk = g.ek[e]
                if (kk == 'r' || (pass == 0) == (kk == 't')) continue
                val a = g.es[e]; val b = g.et[e]
                if (sel >= 0 && (a == sel || b == sel)) continue
                if (!edgeVis(a, b)) continue
                buf[c++] = x[a]; buf[c++] = y[a]; buf[c++] = x[b]; buf[c++] = y[b]
            }
            if (c > 0) {
                line.strokeWidth = lw; line.color = argb(p.line, base * (if (pass == 1) 0.6f else 1f))
                cv.drawLines(buf, 0, c, line)
            }
        }
        // Relationen gestrichelt
        path.rewind()
        var rel = false
        for (e in 0 until g.m) {
            if (g.ek[e] != 'r') continue
            val a = g.es[e]; val b = g.et[e]
            if (sel >= 0 && (a == sel || b == sel)) continue
            if (!edgeVis(a, b)) continue
            path.moveTo(x[a], y[a]); path.lineTo(x[b], y[b]); rel = true
        }
        if (rel) {
            dash.strokeWidth = lw; dash.color = argb(p.line, base)
            val dl = 3f * dens / k
            if (dashFx.size > 64) dashFx.clear()                      // je Zoomstufe ein Muster, nicht je Bild neu
            dash.pathEffect = dashFx.getOrPut(dl) { android.graphics.DashPathEffect(floatArrayOf(dl, dl), 0f) }
            cv.drawPath(path, dash)
        }
        if (sel in 0 until n) {
            var c = 0
            for (e in 0 until g.m) {
                val a = g.es[e]; val b = g.et[e]
                if (a != sel && b != sel) continue
                buf[c++] = x[a]; buf[c++] = y[a]; buf[c++] = x[b]; buf[c++] = y[b]
            }
            if (c > 0) {
                line.strokeWidth = lw * (1 + 0.8f * D)
                line.color = argb(p.line, base * (1 - D)); cv.drawLines(buf, 0, c, line)
                line.color = argb(p.accent, 0.9f * D); cv.drawLines(buf, 0, c, line)
            }
        }
        // --- Knoten: unbeteiligte zuerst, hervorgehobene obendrauf
        val dimA = 1 - 0.85f * D
        val minR = 2.5f * dens / k
        // eigene, nicht hervorgehobene Knoten gebuendelt: je Farbe und Radius-Stufe (0.5) ein drawPoints mit runder Kappe
        batches.values.forEach { it.size = 0 }
        for (i in 0 until n) {
            if (hl[i] || i == g.focus || !g.own[i]) continue
            val r = maxOf(radii[i], minR)
            if (x[i] + r < vx0 - pad || x[i] - r > vx1 + pad || y[i] + r < vy0 - pad || y[i] - r > vy1 + pad) continue
            val key = g.kinds[i].ordinal * 100_000 + (r * 2f + 0.5f).toInt()
            batches.getOrPut(key) { PointBatch() }.add(x[i], y[i])
        }
        point.alpha = 255
        for ((key, b) in batches) {
            if (b.size == 0) continue
            val kind = NodeKind.entries[key / 100_000]
            point.color = argb(when (kind) { NodeKind.TAG -> p.tag; NodeKind.DATABASE -> p.db; NodeKind.ROW -> p.row; NodeKind.PAGE -> p.page }, dimA)
            point.strokeWidth = (key % 100_000).toFloat()        // Durchmesser = 2 r
            cv.drawPoints(b.pts, 0, b.size, point)
        }
        for (layer in 0..1) {
            for (i in 0 until n) {
                val isHl = hl[i] || i == g.focus
                if ((layer == 1) != isHl) continue
                if (layer == 0 && g.own[i]) continue                  // schon gebuendelt gezeichnet
                var r = maxOf(radii[i], minR)
                if (x[i] + r < vx0 - pad || x[i] - r > vx1 + pad || y[i] + r < vy0 - pad || y[i] - r > vy1 + pad) continue
                val col = kindColor(g, i, p)
                val a = if (isHl && anyHl) 1f else if (isHl) 1f else dimA
                if (i == sel) r = maxOf(r * (1 + 0.35f * D), (2.5f + 2.5f * D) * dens / k)
                val cur = i == g.focus
                if (g.own[i] || cur) {
                    fill.color = argb(if (cur || i == sel) p.accent else col, a)
                    cv.drawCircle(x[i], y[i], r, fill)
                } else {                                              // freigegeben: zart gefuellt mit Ring
                    fill.color = argb(if (i == sel) p.accent else col, a * 0.35f)
                    cv.drawCircle(x[i], y[i], r, fill)
                    ring.strokeWidth = maxOf(r * 0.28f, 1.2f * dens / k); ring.color = argb(if (i == sel) p.accent else col, a)
                    cv.drawCircle(x[i], y[i], r - ring.strokeWidth / 2, ring)
                }
                if (cur) {                                            // offene Seite: Ring mit Abstand ("du bist hier")
                    ring.strokeWidth = 1.6f * dens / k; ring.color = argb(p.accent, maxOf(dimA, if (isHl) 1f else 0f))
                    cv.drawCircle(x[i], y[i], r + 3.5f * dens / k, ring)
                }
            }
        }
        cv.restore()
        // --- Beschriftungen in Bildschirmkoordinaten (feste Groesse, Rand in Hintergrundfarbe)
        val few = n <= 40
        val thr = 1.1f * 1.6f
        val fs = 12f * dens
        text.textSize = fs; halo.textSize = fs; halo.strokeWidth = 3f * dens; halo.color = p.bg.toArgb()
        var drawn = 0
        for (layer in 0..1) {
            for (i in 0 until n) {
                if (drawn >= 450) break
                val isHl = hl[i] && anyHl
                if ((layer == 1) != isHl) continue
                val sx = cam.tx + x[i] * k; val sy = cam.ty + y[i] * k
                if (sx < -150 || sx > w + 150 || sy < -20 || sy > h + 20) continue
                val z = kd * (1 + sqrt(g.deg[i].toFloat()) * 0.3f)
                val za = if (few) 1f else ((z - thr * 0.72f) / (thr * 0.28f)).coerceIn(0f, 1f)
                var la = maxOf(za * dimA, if (isHl) 1f else 0f)
                if (matches != null && i !in matches && sel < 0) la = minOf(la, za * dimA)
                if (la <= 0.03f) continue
                val big = i == sel
                text.isFakeBoldText = big
                val t = g.titles[i].let { if (it.length > 40 && !big) it.take(39) + "…" else it }
                val rr = maxOf(radii[i], minR) * k * (if (big) 1.35f else 1f)
                val ty = sy + rr + 4f * dens + fs * 0.9f
                text.color = argb(p.fg, la); halo.alpha = (255 * la).toInt()
                cv.drawText(t, sx, ty, halo)
                cv.drawText(t, sx, ty, text)
                drawn++
            }
        }
        lastDrawMs = (System.nanoTime() - t0) / 1e6
        drawCount++
        GraphDrawStats.add(lastDrawMs)
    }
}

/** Messung (Leistungstest): Dauer des Zeichnens je Bild auf dem UI-Thread */
object GraphDrawStats {
    @Volatile var count = 0
        private set
    @Volatile var sumMs = 0.0
        private set
    @Volatile var maxMs = 0.0
        private set
    fun add(ms: Double) { count++; sumMs += ms; if (ms > maxMs) maxMs = ms }
    fun reset() { count = 0; sumMs = 0.0; maxMs = 0.0 }
}

/** wiederverwendbarer Punktpuffer (x, y, x, y, ...) */
internal class PointBatch {
    var pts = FloatArray(64)
    var size = 0
    fun add(x: Float, y: Float) {
        if (size + 2 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[size++] = x; pts[size++] = y
    }
}
