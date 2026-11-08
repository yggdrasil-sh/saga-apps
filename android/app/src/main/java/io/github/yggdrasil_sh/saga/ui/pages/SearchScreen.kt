package io.github.yggdrasil_sh.saga.ui.pages

import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.common.pageTitle
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent
import io.github.yggdrasil_sh.saga.ui.common.SagaLineEmptyState
import io.github.yggdrasil_sh.saga.ui.theme.SagaIconTile
import io.github.yggdrasil_sh.saga.ui.theme.SagaSectionTitle
import io.github.yggdrasil_sh.saga.ui.theme.SagaSpace
import io.github.yggdrasil_sh.saga.ui.theme.sagaAppear
import io.github.yggdrasil_sh.saga.ui.theme.sagaSegment
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Volltextsuche in Titel, Tags und Inhalt (POST /api/notes/search), ab 2 Zeichen, nach kurzer Tipp-Pause */
@Composable
fun SearchScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PageMeta>>(emptyList()) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var searched by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // NAV-15: Fokus und Tastatur sofort beim Oeffnen (nach dem ersten Bild, wenn das Feld in der Liste steht)
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    LaunchedEffect(q) {
        delay(250)
        if (q.trim().length < 2) {
            results = emptyList()
            searched = false
            error = null
            return@LaunchedEffect
        }
        try {
            results = repo.search(q)
            error = null
        } catch (e: CancellationException) {
            throw e                                          // schneller getippt: neuer Suchlauf, kein Fehler
        } catch (e: Exception) {
            error = repo.friendly(e)
        }
        searched = true
    }
    val pages by repo.pages.collectAsState()
    val sharedPages by repo.sharedPages.collectAsState()
    val recentIds by repo.settings.recent.collectAsState()
    val recent = remember(pages, recentIds) { recentIds.mapNotNull { id -> pages.firstOrNull { it.id == id } }.take(5) }
    // NAV-14: Pfad und Typ aus dem bekannten Seitenbaum (eigene + geteilte Seiten); Vorlagen in eigenem Abschnitt
    val byId = remember(pages, sharedPages) { SearchHits.index(pages, sharedPages) }
    val (hits, templates) = remember(results) { SearchHits.split(results) }
    val maxW = SagaSpace.readingWidth
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.r4t_search_title), nav, chrome) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = SagaSpace.xxl),
        ) {
            item(key = "field") {
                // Suchfeld als Pille (wie die Material-Suchleiste), mit Loeschen-Knopf
                TextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.r4t_search_placeholder)) }, leadingIcon = { SagaIcon(SagaIcons.search, null) },
                    trailingIcon = {
                        if (q.isNotEmpty()) IconButton(onClick = { q = ""; runCatching { focus.requestFocus() } }) {
                            SagaIcon(SagaIcons.x, stringResource(R.string.action_clear_search))
                        }
                    },
                    // R2c: Felder 6 dp wie Web (--r-sm)
                    shape = MaterialTheme.shapes.small,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    modifier = Modifier.widthIn(max = maxW).fillMaxWidth().padding(horizontal = SagaSpace.gutter, vertical = SagaSpace.s)
                        .heightIn(min = 56.dp).focusRequester(focus),
                )
            }
            error?.let { item(key = "err") { Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (q.trim().length < 2) {
                item(key = "hint") {
                    SagaLineEmptyState(
                        stringResource(R.string.search_empty_title), SagaIcons.search, text = stringResource(R.string.search_empty_hint),
                        compact = recent.isNotEmpty(), modifier = Modifier.sagaAppear(0),
                    )
                }
                if (recent.isNotEmpty()) {
                    item(key = "rec-head") { SagaSectionTitle(stringResource(R.string.r4t_recent), Modifier.widthIn(max = maxW)) }
                    itemsIndexed(recent, key = { _, p -> "rec-${p.id}" }) { i, p ->
                        ResultTile(p, byId, repo, "", i, recent.size, Modifier.widthIn(max = maxW).sagaAppear(1 + i)) { nav.push(Dest.Page(p.id)) }
                    }
                }
            } else if (searched && results.isEmpty() && error == null) {
                item(key = "none") {
                    SagaLineEmptyState(stringResource(R.string.search_no_hits_title), SagaIcons.search, text = stringResource(R.string.search_no_hits_hint))
                }
            }
            if (q.trim().length >= 2) {
                itemsIndexed(hits, key = { _, p -> p.id }) { i, p ->
                    ResultTile(p, byId, repo, q.trim(), i, hits.size, Modifier.widthIn(max = maxW).animateItem()) { nav.push(Dest.Page(p.id)) }
                }
                if (templates.isNotEmpty()) {
                    item(key = "tpl-head") {
                        SagaSectionTitle(stringResource(R.string.r4t_search_templates), Modifier.widthIn(max = maxW).padding(top = SagaSpace.m), count = templates.size)
                    }
                    itemsIndexed(templates, key = { _, p -> "tpl-${p.id}" }) { i, p ->
                        ResultTile(p, byId, repo, q.trim(), i, templates.size, Modifier.widthIn(max = maxW).animateItem()) { nav.push(Dest.Page(p.id)) }
                    }
                }
            }
        }
    }
}

/** Treffer als Kachel einer segmentierten Liste: Symbol-Kachel, Titel, Ausschnitt mit hervorgehobenem Suchwort */
@Composable
private fun ResultTile(p: PageMeta, byId: Map<Long, PageMeta>, repo: SagaRepository, q: String, index: Int, count: Int, modifier: Modifier, onOpen: () -> Unit) {
    val accent = LocalSagaAccent.current
    val kind = SearchHits.kind(p, byId)
    val untitled = stringResource(R.string.page_untitled)
    val path = remember(p.id, p.parentId, byId) { SearchHits.path(p, byId, untitled) }
    // R4-Nachbesserung 20: Datenbanken ohne Ausschnitt (der Server liefert dort nur Spaltenbeschriftungen)
    val snippet = remember(p.snippet, q, kind) { if (SearchHits.showSnippet(kind)) SearchHits.cleanSnippet(p.snippet, q) else null }
    Row(
        modifier.fillMaxWidth().sagaSegment(index, count).clickable(onClick = onOpen).heightIn(min = 56.dp)
            .padding(horizontal = SagaSpace.m, vertical = SagaSpace.s + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SagaIconTile(size = 40.dp) { PageIcon(p.icon, repo, p.isDatabase) }
        Column(Modifier.padding(start = SagaSpace.m).weight(1f)) {
            // NAV-14: Overline mit Typ-Symbol, Typ und Pfad ("Datenbank · Aufgaben & Projekte › Projekte")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SagaIcon(kind.icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    listOfNotNull(stringResource(kind.label), path).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(highlight(pageTitle(p), q, accent.goldText), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.foreign) {
                Row(verticalAlignment = Alignment.CenterVertically) {         // G9: einfarbiges Symbol statt 👥
                    SagaIcon(
                        SagaIcons.users, null,
                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.shared_by, p.owner?.name.orEmpty()), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            snippet?.let {
                Text(highlight(it, q, accent.goldText), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Suchwort im Text fett und in Gold (ohne Gross-/Kleinschreibung zu beachten) */
private fun highlight(text: String, q: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    if (q.length < 2) return@buildAnnotatedString
    var i = text.indexOf(q, ignoreCase = true)
    var n = 0
    while (i >= 0 && n < 20) {
        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color), i, i + q.length)
        i = text.indexOf(q, i + q.length, ignoreCase = true); n++
    }
}

/** NAV-14: Art eines Treffers - Symbol (SagaIcons) und Bezeichnung */
enum class HitKind(@DrawableRes val icon: Int, @StringRes val label: Int) {
    PAGE(SagaIcons.file, R.string.r4t_kind_page),
    DATABASE(SagaIcons.database, R.string.r4t_kind_database),
    ROW(SagaIcons.rows, R.string.r4t_kind_row),
    TEMPLATE(SagaIcons.template, R.string.r4t_kind_template),
}

/** NAV-14: reine Logik der Suchtreffer (Typ, Pfad, saubere Ausschnitte, Vorlagen getrennt), ohne Compose testbar */
object SearchHits {
    /** Pfad-Ebenen hoechstens (davor "…") */
    const val PATH_DEPTH = 3

    /** Bekannte Seiten nach ID; eigene haben Vorrang vor geteilten */
    fun index(pages: List<PageMeta>, shared: List<PageMeta> = emptyList()): Map<Long, PageMeta> {
        val m = HashMap<Long, PageMeta>(pages.size + shared.size)
        shared.forEach { m[it.id] = it }
        pages.forEach { m[it.id] = it }
        return m
    }

    fun kind(p: PageMeta, byId: Map<Long, PageMeta>): HitKind = when {
        p.template -> HitKind.TEMPLATE
        p.isDatabase -> HitKind.DATABASE
        p.parentId?.let { byId[it] }?.isDatabase == true -> HitKind.ROW
        else -> HitKind.PAGE
    }

    /**
     * Ausschnitt zeigen? Nicht bei Datenbanken: dort ist er eine Kette von Spaltenbeschriftungen ("faellig Erledigt
     * Neue Aufgabe …") ohne Aussage; Typ und Pfad stehen schon in der Overline.
     */
    fun showSnippet(kind: HitKind): Boolean = kind != HitKind.DATABASE

    /** Treffer ohne Vorlagen / Vorlagen (Reihenfolge des Servers bleibt) */
    fun split(results: List<PageMeta>): Pair<List<PageMeta>, List<PageMeta>> = results.partition { !it.template }

    /**
     * Pfad der Elternseiten von oben nach unten ("Aufgaben & Projekte › Projekte"), null bei oberster Ebene oder
     * unbekannter Elternseite. Laengere Pfade zeigen nur die letzten [PATH_DEPTH] Ebenen hinter "…"; Zyklen brechen ab.
     */
    fun path(p: PageMeta, byId: Map<Long, PageMeta>, untitled: String, depth: Int = PATH_DEPTH): String? {
        val names = ArrayList<String>()
        val seen = hashSetOf(p.id)
        var cur = p.parentId
        while (cur != null && seen.add(cur) && names.size < 64) {
            val x = byId[cur] ?: break
            names.add(x.title.trim().ifEmpty { untitled })
            cur = x.parentId
        }
        if (names.isEmpty()) return null
        names.reverse()
        val shown = if (names.size > depth) listOf("…") + names.takeLast(depth) else names
        return shown.joinToString(" › ")
    }

    /** Emoji und Bildzeichen (Datenbank-Zellen wie "➕ Neue Aufgabe 📁"), Varianten-Selektoren, Verbinder, Steuerzeichen */
    fun isNoise(cp: Int): Boolean = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
        cp in 0x2300..0x23FF || cp in 0xFE00..0xFE0F || cp in 0xE0000..0xE007F || cp == 0x200D || cp == 0x20E3 ||
        Character.getType(cp).let { it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt() || it == Character.PRIVATE_USE.toInt() }

    /**
     * Ausschnitt ohne Emoji-Reste und Steuerzeichen, Leerraum zusammengefasst. Vorne kein "…" und kein angeschnittenes
     * Wort (ausser es enthaelt den Suchbegriff); hinten bleibt "…", wenn der Server wirklich gekuerzt hat. Nichts Lesbares -> null.
     */
    fun cleanSnippet(raw: String?, q: String): String? {
        if (raw.isNullOrBlank()) return null
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (isNoise(cp)) sb.append(' ') else sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        var t = sb.toString().replace(Regex("\\s+"), " ").trim()
        val cut = t.startsWith("…") || t.startsWith("...")
        if (cut) {
            t = t.removePrefix("…").removePrefix("...")
            val whole = t.firstOrNull()?.isWhitespace() != false      // "… Wort": an einer Wortgrenze gekuerzt
            t = t.trimStart()
            val sp = if (whole) -1 else t.indexOf(' ')
            if (sp in 1..30 && (q.isBlank() || !t.substring(0, sp).contains(q.trim(), ignoreCase = true))) t = t.substring(sp + 1)
        }
        t = t.trimStart { it.isWhitespace() || it in ".,;:·-–—|/" }
        return t.takeIf { s -> s.removeSuffix("…").any { it.isLetterOrDigit() } }
    }
}
