package io.github.yggdrasil_sh.saga.ui.page

import io.github.yggdrasil_sh.saga.blocks.NoteLinks
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.RemoteCaps
import io.github.yggdrasil_sh.saga.data.NcLogic
import io.github.yggdrasil_sh.saga.util.UiText
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.Gestalten
import io.github.yggdrasil_sh.saga.blocks.BlockOps
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.blocks.InlineStyle
import io.github.yggdrasil_sh.saga.blocks.JsonPath
import io.github.yggdrasil_sh.saga.blocks.JsonTree
import io.github.yggdrasil_sh.saga.data.AccountKey
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.Draft
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.DocText
import io.github.yggdrasil_sh.saga.data.LostAccess
import io.github.yggdrasil_sh.saga.data.PageCaps
import io.github.yggdrasil_sh.saga.data.PageShareInfo
import io.github.yggdrasil_sh.saga.data.conflictAuthor
import io.github.yggdrasil_sh.saga.data.Publish
import io.github.yggdrasil_sh.saga.data.PublishState
import io.github.yggdrasil_sh.saga.data.PublishVia
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Das gerade bearbeitete Feld (fuer die Werkzeugleiste ueber der Tastatur).
 * Bei Bloecken ohne Text (Bild, Trenner, nicht unterstuetzt) sind die Text-Funktionen null.
 */
class ActiveField(
    val key: String,
    val blockPath: () -> JsonPath,
    val itemPath: () -> JsonPath? = { null },
    val toggle: ((InlineStyle) -> Unit)? = null,
    val isActive: ((InlineStyle) -> Boolean)? = null,
    val setLink: ((String) -> Unit)? = null,
    val linkAtCursor: (() -> String?)? = null,
    val hasSelection: () -> Boolean = { false },
)

/**
 * Was der Editor vom Rest der App braucht (SagaRepository; in Tests eine Attrappe).
 * server/drafts gelten fuer den Server + das Konto, mit dem der Editor geoeffnet wurde.
 */
interface PageBackend {
    val currentServer: String
    val signedOut: Boolean
    /** Kennung des angemeldeten Kontos ("uid:<Benutzername>", leer = unbekannt) - Editor speichert nie in ein anderes Konto */
    val currentAccountKey: String get() = ""
    fun draftsForCurrent(): Drafts
    suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T
    suspend fun loadPage(id: Long): JsonObject
    /** -> neue Revision; ApiException 409 = woanders geaendert, 423 = gesperrt */
    suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int?
    /** overwrite = "Meine Version behalten" nach einem Konflikt (Server 1.3 sichert die andere Fassung als Version) */
    suspend fun savePage(id: Long, title: String, body: String?, rev: Int?, overwrite: Boolean): Int? = savePage(id, title, body, rev)
    /** Neue Seite mit diesem Inhalt (Konflikt-Kopie) -> ID */
    suspend fun createPageWith(title: String, body: String): Long
    suspend fun setPinned(id: Long, pinned: Boolean)
    suspend fun setLocked(id: Long, locked: Boolean)
    /** Kopf der Seite (icon, cover, cover_pos) speichern */
    suspend fun patchPage(id: Long, body: JsonObject) {}
    fun updateTitleLocal(id: Long, title: String)
    fun visited(id: Long)
    fun friendly(e: Throwable): UiText
    /** Server nicht erreichbar (Netz weg, Zeitueberschreitung) - kein Zertifikats- oder Serverfehler */
    fun isOffline(e: Throwable): Boolean = e is IOException && e !is SSLException
    /** 2.3: eigenes Konto laut Server (Autor eigener Nextcloud-Bloecke), leer = unbekannt */
    suspend fun ncMe(): String = ""
    fun register(editor: PageEditor) {}
    fun unregister(editor: PageEditor) {}
}

/**
 * Zustand einer Seite im Editor. REGELN (aus echten Fehlern gelernt):
 *  - nie speichern, wenn die Seite nicht erfolgreich geladen wurde (fetched)
 *  - nur speichern, wenn wirklich etwas geaendert wurde (dirty)
 *  - gesperrte Seiten (locked) nicht bearbeiten
 *  - unbekannte Bloecke bleiben unveraendert im JSON (es werden nur einzelne Werte ersetzt)
 *  - Speichern mit Revision: 409 = woanders geaendert -> KONFLIKT (nichts verwerfen, Nutzer entscheidet), 423 = gesperrt
 *  - ein laufendes Speichern wird nie abgebrochen: Tippen verschiebt nur das Entprellen, nicht den Request
 *  - ungespeicherte Eingaben liegen zusaetzlich als Entwurf auf Platte (Prozess-Tod, offline, Serverwechsel)
 */
@Stable
class PageEditor(
    val id: Long,
    private val repo: PageBackend,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Server, von dem die Seite stammt - nie auf einen anderen Server speichern */
    private val server = repo.currentServer
    private val drafts: Drafts = repo.draftsForCurrent()
    /** Konto, mit dem die Seite geoeffnet wurde: nach einem Kontowechsel (gleicher Server) nichts mehr senden (Pruefer 1.3) */
    private val account = repo.currentAccountKey

    var doc by mutableStateOf(JsonObject(emptyMap()))
        private set
    var title by mutableStateOf("")
        private set
    var icon by mutableStateOf("")
        private set
    /** Titelbild (/files/... oder /api/fed/files/...), null = keins; Position 0 (oben) ... 100 (unten) wie Web/iOS */
    var cover by mutableStateOf<String?>(null)
        private set
    var coverPos by mutableStateOf(50.0)
        private set
    /** G9 (2.5): Schrift der Seite ("serif" | "mono", null = Standard) und kleiner Text; styleSupported = Server liefert die Felder */
    var font by mutableStateOf<String?>(null)
        private set
    var small by mutableStateOf(false)
        private set
    var styleSupported by mutableStateOf(false)
        private set
    /** Tags unter dem Titel (wie Web/iOS) */
    var tags by mutableStateOf<List<String>>(emptyList())
        private set
    var kind by mutableStateOf("page")
        private set
    /** Datenbank-Zeile: Werte der Eigenschaften (null = keine) */
    var props by mutableStateOf<JsonObject?>(null)
        private set
    var parentId by mutableStateOf<Long?>(null)
        private set
    var created: Double? = null
        private set
    var updatedAt: Double? = null
        private set
    var locked by mutableStateOf(false)
        private set
    var pinned by mutableStateOf(false)
    /** Vorlage (nicht veroeffentlichbar) */
    var template by mutableStateOf(false)
    /** Veroeffentlichen: Server kennt es (GET /api/notes/{id} liefert "published"); Zustand fuer den Seitenkopf */
    var publishSupported by mutableStateOf(false)
    var published by mutableStateOf(false)
    var publicUrl by mutableStateOf<String?>(null)
    var publishedVia by mutableStateOf<PublishVia?>(null)
    var loading by mutableStateOf(true)
        private set
    var fetched by mutableStateOf(false)
        private set
    var converted by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    /** zuletzt erfolgreich gespeichert (Uhrzeit, ms) - fuer "Gespeichert" in der Titelleiste */
    var savedAt by mutableStateOf<Long?>(null)
        private set
    /** Konflikt (409): die Seite wurde woanders geaendert; automatisches Speichern pausiert, bis entschieden ist */
    var conflict by mutableStateOf(false)
        private set
    /** Server nicht erreichbar: Eingaben liegen als Entwurf auf dem Geraet und werden spaeter gespeichert */
    var offline by mutableStateOf(false)
        private set
    /**
     * Entwurf vom letzten Mal, der vom Server-Stand abweicht (Banner "Nicht gespeicherte Aenderungen vom ...").
     * Bis Wiederherstellen/Verwerfen ist die Seite nur lesbar - sonst wuerden neue Eingaben den Entwurf ueberschreiben.
     */
    var pendingDraft by mutableStateOf<Draft?>(null)
        private set
    private var pendingDoc: JsonObject? = null
    var error by mutableStateOf<UiText?>(null)

    /** 2.3: Nextcloud-Bloecke im gespeicherten Stand (Person|Nextcloud|Datei) - neue erkennt der Server als "eingefuegt" */
    private var ncBase: Set<String> = emptySet()
    var info by mutableStateOf<UiText?>(null)

    /** Teilen (1.3): Rolle, Eigentuemer, Kommentar-Zaehler aus GET /api/notes/{id} */
    var share by mutableStateOf(PageShareInfo())
    val caps: PageCaps get() = PageCaps(share.role, share.supported)
    /** Konflikt mit einer anderen Person: ihr Name (409 {"by": ...}); null = anderes Geraet/Tab */
    var conflictBy by mutableStateOf<String?>(null)
        private set
    /** Freigabe beim Speichern verloren (403 = nur noch lesen, 404 = kein Zugriff): letzter Text zum Kopieren */
    var lost by mutableStateOf<LostAccess?>(null)
        private set
    /** Seite nicht (mehr) erreichbar (404 beim Laden) - "Kein Zugriff auf diese Seite" */
    var noAccess by mutableStateOf(false)
        private set
    /** naechstes Speichern mit overwrite (nach "Meine Version behalten") */
    private var overwriteNext = false

    /** Feld, das den Fokus bekommen soll (Schluessel) - das Feld setzt es nach dem Fokussieren zurueck */
    var focusRequest by mutableStateOf<String?>(null)
    var focusAtEnd = false
    /** Cursor-Position fuer focusRequest (Zeichen im Klartext), sonst Anfang bzw. Ende (focusAtEnd) */
    var focusOffset: Int? = null
    /**
     * TT 10: Zeichen, die nach Return noch im alten Feld ankamen, bevor der Fokus im neuen Block war ("Chritt" statt
     * "Schritt") - das neue Feld uebernimmt sie, sobald es den Fokus hat.
     */
    /** TT 18: aktueller Titel einer Seite (Seitenliste), fuer Verweise im Text; null = unbekannt */
    var titleOf: (Long) -> String? = { null }
    var carry: String = ""
    var carryAt = 0L
    var active by mutableStateOf<ActiveField?>(null)

    private var rev: Int? = null
    /** Revision des geladenen bzw. zuletzt gespeicherten Stands (Live-Kanal: fremde Aenderung erkennen) */
    val currentRev: Int? get() = rev
    /** letzte Eingabe (ms) - Anwesenheit "bearbeitet gerade" in den ersten 30 s danach */
    var lastInputAt = 0L
        private set
    /** Foederation (2.0): Seite liegt auf dieser anderen Saga-Instanz (sonst null) */
    var remoteHost by mutableStateOf<String?>(null)
        private set
    /** 403 bei einer Seite einer anderen Instanz: oeffentlicher Name fehlt (Weg zu den Einstellungen anbieten) */
    var needsPublicName by mutableStateOf(false)
        private set
    /** Seite einer anderen Saga-Instanz (negative ID) */
    val isRemote: Boolean get() = id < 0
    /** 2.3: was der Server einer fremden Seite kann (remote.caps); leer bei eigenen Seiten und aelteren Gegenstellen */
    var remoteCaps by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 2.3: Seite kann das (eigene Seite immer; fremde nur mit Faehigkeit ihres Servers) */
    fun can(cap: String): Boolean = RemoteCaps.has(isRemote, remoteCaps, cap)
    // als State: "Gespeichert"/"Ungespeichert" in der Titelleiste folgt sofort
    private var version by mutableIntStateOf(0)
    private var savedVersion by mutableIntStateOf(0)
    val dirty: Boolean get() = version != savedVersion
    val isDatabase: Boolean get() = kind == "database"
    /** Titel aenderbar: geladen, nicht gesperrt, Rolle mindestens Bearbeiten, Zugriff nicht verloren */
    val titleEditable: Boolean get() = fetched && !locked && share.role.canEdit && lost == null
    val editable: Boolean get() = titleEditable && !isDatabase && pendingDraft == null

    private var debounceJob: Job? = null
    private var retryJob: Job? = null
    private var draftJob: Job? = null
    private val draftLock = Mutex()
    /** laeuft gerade ein Speichervorgang? (nur auf dem Haupt-Thread geaendert) */
    private val running = MutableStateFlow(false)
    private var again = false

    init {
        repo.register(this)
    }

    // ---------------------------------------------------------------- Laden

    suspend fun load() {
        loading = true
        try {
            val p = repo.withPageLock(id) { repo.loadPage(id) }
            if (dirty && fetched) return                     // eigene, noch nicht gespeicherte Eingaben schuetzen
            val parsed = withContext(cpu) { parse(p) }
            apply(p, parsed)
            offline = false
            checkDraft(p)
            repo.visited(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: BlockDoc.UnreadableException) {
            fetched = false
            error = UiText.Res(R.string.page_unreadable)
        } catch (e: Exception) {
            when {
                e is ApiException && e.code == 404 && !dirty -> {   // geloescht oder nicht (mehr) mit mir geteilt
                    noAccess = true
                    fetched = false
                    error = null
                }
                !fetched -> {
                    error = repo.friendly(e)
                    needsPublicName = isRemote && e is ApiException && e.code == 403
                }
                repo.isOffline(e) -> offline = true
                else -> info = UiText.Res(R.string.page_reload_failed, repo.friendly(e))
            }
        } finally {
            loading = false
        }
    }

    private fun parse(p: JsonObject): BlockDoc.Parsed {
        val bodyEl = p["body"] as? JsonPrimitive
        val body = if (bodyEl != null && bodyEl.isString) bodyEl.content else null
        return BlockDoc.parse(body, BlockDoc.str(p, "format").ifEmpty { "text" })
    }

    private fun apply(p: JsonObject, parsed: BlockDoc.Parsed) {
        val bodyEl = p["body"] as? JsonPrimitive
        val body = if (bodyEl != null && bodyEl.isString) bodyEl.content else null
        title = BlockDoc.str(p, "title")
        icon = BlockDoc.str(p, "icon")
        applyHeader(p)
        font = Gestalten.pageFont(p)
        small = Gestalten.pageSmall(p)
        styleSupported = Gestalten.pageStyleSupported(p)
        tags = (p["tags"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { t -> t.isNotBlank() } }
        kind = BlockDoc.str(p, "kind").ifEmpty { "page" }
        locked = BlockDoc.bool(p, "locked")
        pinned = BlockDoc.bool(p, "pinned")
        template = BlockDoc.bool(p, "template")
        publishSupported = Publish.supported(p)
        published = BlockDoc.bool(p, "published")
        publicUrl = BlockDoc.str(p, "public_url").ifEmpty { null }
        publishedVia = Publish.via(p)
        share = PageShareInfo.of(p)
        remoteHost = ((p["remote"] as? JsonObject)?.get("host") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: if (isRemote) "" else null
        remoteCaps = if (isRemote) RemoteCaps.of(p) else emptySet()
        needsPublicName = false
        noAccess = false
        rev = BlockDoc.int(p, "rev")
        // Datenbank-Zeile (Notion-Stil): Eigenschaften + Elternseite. A02: auch leer (neuer Eintrag, Vorlage) - sonst
        // lassen sich Status/Datum auf der Seite nie setzen; ob die Elternseite eine Datenbank ist, klaert RowProperties
        parentId = (p["parent_id"] as? JsonPrimitive)?.content?.toLongOrNull()
        props = rowProps(p, parentId)
        created = (p["created"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        updatedAt = (p["updated"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        converted = parsed.converted && !body.isNullOrBlank()
        ncBase = NcLogic.keys(parsed.doc)
        // TT 18: Seitenverweise mit dem aktuellen Titel (Unterseite "Unbenannt" -> spaeter benannt), wie im Web
        doc = withStarter(NoteLinks.refresh(parsed.doc, titleOf))
        version = 0
        savedVersion = 0
        fetched = true
        error = null
    }

    /** Neuer Stand aus dem Dialog "Veroeffentlichen" -> Hinweis "Oeffentlich" im Seitenkopf */
    fun applyPublish(st: PublishState) {
        published = st.published
        publicUrl = st.url
        publishedVia = st.publishedVia
    }

    /** leere Seite: ein Absatz zum Losschreiben (erst gespeichert, wenn etwas getippt wird) */
    private fun withStarter(d: JsonObject): JsonObject =
        if (BlockDoc.blocks(d).isEmpty() && kind != "database") JsonTree.set(d, listOf("blocks"), JsonArray(listOf(BlockDoc.newBlock("paragraph")))) as JsonObject
        else d

    /**
     * Nach dem Laden: Entwurf von Platte (letzte Sitzung nicht gespeichert)? Hat der Server inhaltlich schon genau
     * diesen Stand (gespeichert, Prozess vor dem Loeschen des Entwurfs beendet), ist er erledigt - auch wenn die
     * Revision inzwischen hoeher ist. Sonst anbieten (Banner): Wiederherstellen oder Verwerfen.
     */
    private suspend fun checkDraft(page: JsonObject) {
        pendingDraft = null
        pendingDoc = null
        if (isDatabase || locked || !share.role.canEdit) return
        val d = withContext(io) { drafts.read(id) } ?: return
        if (withContext(cpu) { d.matchesPage(page) }) {
            draftLock.withLock { withContext(io) { if (drafts.read(id)?.stamp == d.stamp) drafts.delete(id) } }
            return
        }
        pendingDoc = try {
            withContext(cpu) { d.body?.let { BlockDoc.parse(it, "blocks").doc } }
        } catch (_: BlockDoc.UnreadableException) {
            null                                             // unlesbarer Inhalt: nur der Titel laesst sich uebernehmen
        }
        pendingDraft = d
    }

    /**
     * Banner "Wiederherstellen": Entwurf uebernehmen und speichern. Beruht er auf einem aelteren Stand als dem
     * angezeigten (Revision) und weicht inhaltlich ab -> Konflikt (der Nutzer entscheidet, nichts wird ueberschrieben).
     */
    fun restoreDraft() {
        val d = pendingDraft ?: return
        val nd = pendingDoc
        pendingDraft = null
        pendingDoc = null
        title = d.title
        if (nd != null) doc = withStarter(nd)
        version = savedVersion + 1                           // der Entwurf auf Platte bleibt (mit seiner Revision)
        if (d.rev != null && rev != null && d.rev != rev) {
            conflict = true                                  // Server hat inzwischen einen anderen Stand
        } else {
            requestSave()
        }
    }

    /** Banner "Verwerfen": Entwurf loeschen, der Server-Stand bleibt */
    fun discardDraft() {
        val d = pendingDraft ?: return
        pendingDraft = null
        pendingDoc = null
        scope.launch {
            draftLock.withLock { withContext(io) { if (drafts.read(id)?.stamp == d.stamp) drafts.delete(id) } }
        }
    }

    // ---------------------------------------------------------------- Aendern

    private fun touched() {
        lastInputAt = clock()
        version++
        if (error != null && retryJob?.isActive != true) error = null
        scheduleSave()
        writeDraftSoon()
    }

    private fun change(newDoc: JsonObject) {
        if (!editable || newDoc == doc) return
        doc = newDoc
        touched()
    }

    fun editTitle(v: String) {
        if (!titleEditable || pendingDraft != null) return
        val t = v.replace('\n', ' ')
        if (t == title) return
        title = t
        touched()
    }

    fun setString(path: JsonPath, value: String) {
        val cur = JsonTree.get(doc, path) as? JsonPrimitive
        if (cur != null && cur.isString && cur.content == value) return
        val nd = try {
            JsonTree.set(doc, path, JsonPrimitive(value)) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null                                             // unerwartete Daten / veralteter Pfad: nicht abstuerzen
        } ?: return
        change(nd)
    }

    /**
     * Ruecktaste am Anfang eines nicht leeren Textblocks: Text an den vorigen Textblock anhaengen (wie im Web).
     * -> false, wenn davor kein passender Block steht.
     */
    fun mergeWithPrevious(blockPath: JsonPath): Boolean {
        if (!editable) return false
        val (arr, idx) = arrayAndIndex(blockPath)
        if (idx <= 0) return false
        val cur = block(blockPath) ?: return false
        val prevPath = arr + (idx - 1)
        val prev = block(prevPath) ?: return false
        val mergeable = setOf("paragraph", "header", "quote")
        if (BlockDoc.type(cur) !in mergeable || BlockDoc.type(prev) !in mergeable) return false
        val prevHtml = BlockDoc.str(BlockDoc.data(prev), "text")
        val curHtml = BlockDoc.str(BlockDoc.data(cur), "text")
        val joinAt = InlineHtml.plain(prevHtml).length
        val d1 = try {
            JsonTree.set(doc, prevPath + listOf("data", "text"), JsonPrimitive(prevHtml + curHtml)) as JsonObject
        } catch (_: IllegalArgumentException) {
            return false
        }
        val d2 = JsonTree.remove(d1, arr, idx) as JsonObject
        active = null
        change(d2)
        focusOffset = joinAt
        focusAtEnd = false
        focusRequest = blockKey(prev, prevPath)
        return true
    }

    fun block(path: JsonPath): JsonObject? = JsonTree.get(doc, path) as? JsonObject

    // ---------------------------------------------------------------- KI-Assistent: Vorschlaege uebernehmen (nach Klick)

    /** Pfad eines Blocks mit dieser ID (auch verschachtelt, z. B. in Aufklappern) */
    fun pathOfBlock(blockId: String): JsonPath? {
        fun walk(e: kotlinx.serialization.json.JsonElement, path: JsonPath): JsonPath? {
            when (e) {
                is JsonObject -> {
                    if (BlockDoc.id(e) == blockId && e["type"] != null) return path
                    for ((k, v) in e) walk(v, path + k)?.let { return it }
                }
                is JsonArray -> e.forEachIndexed { i, v -> walk(v, path + i)?.let { return it } }
                else -> {}
            }
            return null
        }
        return walk(doc, emptyList())
    }

    /** Bloecke der obersten Ebene mit Text (Auswahl "Markiert: n Blöcke") -> (ID, Klartext) */
    fun textBlocks(): List<Pair<String, String>> = BlockDoc.blocks(doc).mapNotNull { b ->
        val bid = BlockDoc.id(b) ?: return@mapNotNull null
        val t = BlockDoc.mainText(b)?.let { InlineHtml.plain(it) }?.trim().orEmpty()
        if (t.isEmpty()) null else bid to t
    }

    /** "Ersetzen": Text jedes Blocks durch den Vorschlag (Formatierung geht verloren); danach normal speichern */
    fun replaceBlockTexts(texts: Map<String, String>): Boolean {
        if (!editable) return false
        var d: JsonObject = doc
        for ((bid, text) in texts) {
            val p = pathOfBlock(bid) ?: continue
            val b = JsonTree.get(d, p) as? JsonObject ?: continue
            val html = InlineHtml.escape(text.trim()).replace("\n", "<br>")
            val nb: JsonObject = when (BlockDoc.type(b)) {
                "paragraph", "header", "quote", "callout" -> JsonTree.set(b, listOf("data", "text"), JsonPrimitive(html)) as JsonObject
                "toggle" -> JsonTree.set(b, listOf("data", "title"), JsonPrimitive(html)) as JsonObject
                "code" -> JsonTree.set(b, listOf("data", "code"), JsonPrimitive(text)) as JsonObject
                "list" -> {
                    val check = BlockDoc.str(BlockDoc.data(b), "style") == "checklist"
                    val items = text.lines().map { it.trim().removePrefix("- ").removePrefix("• ") }.filter { it.isNotEmpty() }
                        .map { BlockDoc.newListItem(InlineHtml.escape(it), check) }
                    JsonTree.set(b, listOf("data", "items"), JsonArray(items)) as JsonObject
                }
                else -> continue
            }
            d = JsonTree.set(d, p, nb) as JsonObject
        }
        if (d == doc) return false
        change(d)
        return true
    }

    /** "Darunter einfügen": Absaetze nach dem Block (oder am Ende) */
    fun insertParagraphsAfter(blockId: String?, text: String): Boolean {
        if (!editable) return false
        val paras = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paras.isEmpty()) return false
        val p = blockId?.let { pathOfBlock(it) }
        val (arr, idx) = if (p != null && p.last() is Int) p.dropLast(1) to (p.last() as Int) else listOf<Any>("blocks") to BlockDoc.blocks(doc).size - 1
        var d: JsonObject = doc
        paras.forEachIndexed { i, t ->
            d = JsonTree.insert(d, arr, idx + 1 + i, BlockDoc.newBlock("paragraph", InlineHtml.escape(t).replace("\n", "<br>"))) as JsonObject
        }
        change(d)
        return true
    }

    /** "Als Checkliste einfügen": Checkliste ans Seitenende */
    fun appendChecklist(items: List<String>): Boolean {
        if (!editable || items.isEmpty()) return false
        val b = BlockDoc.newBlock("checklist")
        val withItems = JsonTree.set(b, listOf("data", "items"), JsonArray(items.map { BlockDoc.newListItem(InlineHtml.escape(it), true) })) as JsonObject
        change(JsonTree.insert(doc, listOf("blocks"), BlockDoc.blocks(doc).size, withItems) as JsonObject)
        return true
    }

    /** Stabiler Schluessel eines Blocks (ID; ohne ID: Position) */
    fun blockKey(block: JsonObject, path: JsonPath): String = "b:" + (BlockDoc.id(block) ?: path.joinToString("."))

    /** Erstes Textfeld eines Blocks (zum Fokussieren) */
    fun fieldKeyFor(block: JsonObject, path: JsonPath): String? = when (BlockDoc.type(block)) {
        "paragraph", "header", "quote", "code", "callout", "toggle" -> blockKey(block, path)
        "list" -> blockKey(block, path) + "/0"
        else -> null
    }

    fun requestFocus(key: String?, atEnd: Boolean = false) {
        if (key == null) return
        focusAtEnd = atEnd
        focusOffset = null
        focusRequest = key
    }

    private fun arrayAndIndex(path: JsonPath): Pair<JsonPath, Int> = path.dropLast(1) to (path.last() as Int)

    fun insertBlockAfter(blockPath: JsonPath?, block: JsonObject, focus: Boolean = true) {
        if (!editable) return
        val (arr, idx) = if (blockPath.isNullOrEmpty()) listOf<Any>("blocks") to BlockDoc.blocks(doc).size - 1 else arrayAndIndex(blockPath)
        change(JsonTree.insert(doc, arr, idx + 1, block) as JsonObject)
        if (focus) {
            val np = arr + (idx + 1)
            val fk = fieldKeyFor(block, np)
            // Block ohne Text (Trenner, Tabelle ...): auswaehlen, damit das naechste Einfuegen DAHINTER landet (Test P-17)
            if (fk == null) active = ActiveField(blockKey(block, np), { np }) else requestFocus(fk)
        }
    }

    /**
     * Bild-Block (schon hochgeladene Datei /files/...) nach dem Block blockPath (null = ans Ende). Ein leerer Absatz
     * wird zum Bild (wie im Web/Linux), sonst kommt das Bild dahinter. Ohne Bearbeiten-Recht: nichts.
     * -> Pfad des neuen Bild-Blocks (fuer weitere Bilder dahinter) oder null, wenn nicht eingefuegt.
     */
    fun insertImage(blockPath: JsonPath?, url: String): JsonPath? {
        if (!editable || url.isBlank()) return null
        val img = jobj(
            "id" to JsonPrimitive(BlockDoc.newId()), "type" to JsonPrimitive("image"),
            "data" to jobj(
                "file" to jobj("url" to JsonPrimitive(url)), "caption" to JsonPrimitive(""),
                "withBorder" to JsonPrimitive(false), "stretched" to JsonPrimitive(false), "withBackground" to JsonPrimitive(false),
            ),
        )
        val cur = blockPath?.let { block(it) }
        if (blockPath != null && cur != null && BlockDoc.type(cur) == "paragraph" && BlockDoc.str(BlockDoc.data(cur), "text").isEmpty()) {
            active = null
            change(JsonTree.set(doc, blockPath, img) as JsonObject)
            return blockPath
        }
        val (arr, idx) = if (blockPath.isNullOrEmpty() || cur == null) listOf<Any>("blocks") to BlockDoc.blocks(doc).size - 1 else arrayAndIndex(blockPath)
        change(JsonTree.insert(doc, arr, idx + 1, img) as JsonObject)
        return arr + (idx + 1)
    }

    fun insertInto(arrayPath: JsonPath, index: Int, block: JsonObject) {
        if (!editable) return
        change(JsonTree.insert(doc, arrayPath, index, block) as JsonObject)
        requestFocus(fieldKeyFor(block, arrayPath + index))
    }

    fun deleteBlock(blockPath: JsonPath, focusPrevious: Boolean = false) {
        if (!editable) return
        val (arr, idx) = arrayAndIndex(blockPath)
        val prev = if (idx > 0) (JsonTree.get(doc, arr + (idx - 1)) as? JsonObject) else null
        var d = JsonTree.remove(doc, arr, idx) as JsonObject
        if (BlockDoc.blocks(d).isEmpty()) d = JsonTree.set(d, listOf("blocks"), JsonArray(listOf(BlockDoc.newBlock("paragraph")))) as JsonObject
        active = null
        change(d)
        if (focusPrevious && prev != null) requestFocus(previousFieldKey(prev, arr + (idx - 1)), atEnd = true)
    }

    private fun previousFieldKey(block: JsonObject, path: JsonPath): String? {
        if (BlockDoc.type(block) == "list") {
            val n = (BlockDoc.data(block)["items"] as? JsonArray)?.size ?: 0
            return if (n > 0) blockKey(block, path) + "/" + (n - 1) else null
        }
        return fieldKeyFor(block, path)
    }

    fun moveBlock(blockPath: JsonPath, delta: Int) {
        if (!editable) return
        val (arr, idx) = arrayAndIndex(blockPath)
        change(JsonTree.move(doc, arr, idx, delta) as JsonObject)
    }

    fun convertBlock(blockPath: JsonPath, type: String, level: Int = 2) {
        if (!editable) return
        val b = block(blockPath) ?: return
        val nb = BlockDoc.convert(b, type, level) ?: return
        change(JsonTree.set(doc, blockPath, nb) as JsonObject)
        requestFocus(fieldKeyFor(nb, blockPath), atEnd = true)
    }

    // ---------------------------------------------------------------- Listen

    fun insertItemAfter(itemPath: JsonPath, contentHtml: String, checklist: Boolean, newKey: String) {
        if (!editable) return
        val (arr, idx) = arrayAndIndex(itemPath)
        change(JsonTree.insert(doc, arr, idx + 1, BlockDoc.newListItem(contentHtml, checklist)) as JsonObject)
        requestFocus(newKey)
    }

    /** Leerer Punkt + Return: Punkt weg; war es der einzige -> Absatz, sonst neuer Absatz nach der Liste */
    /** Leeren Listenpunkt entfernen und mit einem Absatz hinter der Liste weitermachen -> Pfad des Absatzes */
    fun endList(blockPath: JsonPath, itemPath: JsonPath): JsonPath? {
        if (!editable) return null
        val (arr, idx) = arrayAndIndex(itemPath)
        var d = JsonTree.remove(doc, arr, idx) as JsonObject
        val topItems = JsonTree.get(d, blockPath + listOf("data", "items")) as? JsonArray
        if (topItems == null || topItems.isEmpty()) {
            val p = BlockDoc.newBlock("paragraph", id = BlockDoc.id(block(blockPath)) ?: BlockDoc.newId())
            d = JsonTree.set(d, blockPath, p) as JsonObject
            change(d)
            requestFocus(fieldKeyFor(p, blockPath))
            return blockPath
        } else {
            change(d)
            insertBlockAfter(blockPath, BlockDoc.newBlock("paragraph"))
            val (barr, bidx) = arrayAndIndex(blockPath)
            return barr + (bidx + 1)
        }
    }

    fun removeItem(blockPath: JsonPath, itemPath: JsonPath, focusPrevious: Boolean = false) {
        if (!editable) return
        val (arr, idx) = arrayAndIndex(itemPath)
        val d = JsonTree.remove(doc, arr, idx) as JsonObject
        val topItems = JsonTree.get(d, blockPath + listOf("data", "items")) as? JsonArray
        if (topItems == null || topItems.isEmpty()) {
            deleteBlock(blockPath, focusPrevious)
            return
        }
        active = null
        change(d)
        if (focusPrevious && idx > 0) {
            val b = block(blockPath) ?: return
            val prefix = blockKey(b, blockPath)
            val sub = itemPath.drop(blockPath.size + 2).filterIsInstance<Int>()
            val indices = sub.dropLast(1) + (idx - 1)
            requestFocus(prefix + "/" + indices.joinToString("/"), atEnd = true)
        }
    }

    fun toggleChecked(itemPath: JsonPath) {
        if (!editable) return
        val item = JsonTree.get(doc, itemPath) as? JsonObject ?: return
        val meta = item["meta"] as? JsonObject ?: JsonObject(emptyMap())
        val checked = BlockDoc.bool(meta, "checked")
        val nm = JsonObject(LinkedHashMap(meta).apply { put("checked", JsonPrimitive(!checked)) })
        val nd = try {
            JsonTree.set(doc, itemPath + "meta", nm) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return
        change(nd)
    }

    /** Klick unter den letzten Block: dort weiterschreiben (leerer letzter Absatz) oder neuen Absatz anlegen */
    fun continueWriting() {
        if (!editable) return
        val blocks = BlockDoc.blocks(doc)
        val last = blocks.lastOrNull() as? JsonObject
        val lastPath = listOf<Any>("blocks", blocks.size - 1)
        if (last != null && BlockDoc.type(last) == "paragraph" && BlockDoc.str(BlockDoc.data(last), "text").isEmpty()) {
            requestFocus(blockKey(last, lastPath))
        } else {
            insertBlockAfter(null, BlockDoc.newBlock("paragraph"))
        }
    }

    // ---------------------------------------------------------------- 2.4: "/"-Menue, Tabelle, Spalten, Rueckgaengig

    /** Block an path ersetzen (z. B. "/"-Auswahl); falscher/veralteter Pfad: nichts */
    fun replaceBlock(path: JsonPath, nb: JsonObject) {
        if (!editable) return
        val nd = try {
            JsonTree.set(doc, path, nb) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return
        change(nd)
    }

    /** Block an path aendern (Tabelle, Spalten, Bild-Optionen) - nur, wenn sich wirklich etwas aendert */
    fun updateBlock(path: JsonPath, f: (JsonObject) -> JsonObject) {
        val b = block(path) ?: return
        val nb = f(b)
        if (nb != b) replaceBlock(path, nb)
    }

    /** "/"-Auswahl Textblock: Absatz wird zu diesem Typ (ID bleibt); nach dem Trenner geht es in einem neuen Absatz weiter */
    fun slashConvert(path: JsonPath, type: String, level: Int) {
        val b = block(path) ?: return
        val nb = BlockDoc.newBlock(type, "", level, BlockDoc.id(b) ?: BlockDoc.newId())
        if (type != "paragraph") replaceBlock(path, nb)
        if (type == "delimiter") insertBlockAfter(path, BlockDoc.newBlock("paragraph"))
        else requestFocus(fieldKeyFor(nb, path))
    }

    /** "+": leerer Absatz hinter dem Block -> dessen Pfad (null = nicht bearbeitbar) */
    fun insertParagraphAfter(blockPath: JsonPath?): JsonPath? {
        if (!editable) return null
        val (arr, idx) = if (blockPath.isNullOrEmpty() || block(blockPath) == null) listOf<Any>("blocks") to BlockDoc.blocks(doc).size - 1 else arrayAndIndex(blockPath)
        change(JsonTree.insert(doc, arr, idx + 1, BlockDoc.newBlock("paragraph")) as JsonObject)
        return arr + (idx + 1)
    }

    /**
     * Block loeschen mit Rueckgaengig: -> Funktion, die ihn an derselben Stelle zurueckholt (null = nichts geloescht).
     * War es der letzte Block, steht danach ein leerer Startabsatz - der weicht beim Zurueckholen wieder.
     */
    fun deleteBlockUndoable(blockPath: JsonPath): (() -> Unit)? {
        if (!editable) return null
        val b = block(blockPath) ?: return null
        deleteBlock(blockPath)
        return { restoreBlock(blockPath, b) }
    }

    fun restoreBlock(blockPath: JsonPath, b: JsonObject) {
        if (!editable) return
        val bid = BlockDoc.id(b)
        if (bid != null && pathOfBlock(bid) != null) return                // schon wieder da
        val (arr, idx) = arrayAndIndex(blockPath)
        var d = doc
        val target = if (JsonTree.get(d, arr) is JsonArray) arr else listOf<Any>("blocks")   // Elternblock weg: oberste Ebene
        val cur = JsonTree.get(d, target) as? JsonArray ?: return
        if (target == listOf<Any>("blocks") && cur.size == 1 && BlockOps.isEmptyParagraph(cur[0]) && BlockDoc.id(cur[0]) != bid) {
            d = JsonTree.set(d, target, JsonArray(emptyList())) as JsonObject
        }
        val n = (JsonTree.get(d, target) as? JsonArray)?.size ?: 0
        change(JsonTree.insert(d, target, if (target == arr) idx.coerceIn(0, n) else n, b) as JsonObject)
    }

    // ---------------------------------------------------------------- Speichern

    /** Nach einer Tipp-Pause speichern. Bricht nur das Warten ab, nie ein laufendes Speichern. */
    fun scheduleSave(delayMs: Long = 1200) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMs)
            debounceJob = null
            requestSave()
        }
    }

    /** Speichern anstossen: laeuft schon eins, wird danach mit dem neuesten Stand nochmal gespeichert */
    fun requestSave() {
        debounceJob?.cancel()
        retryJob?.cancel()
        if (running.value) {
            again = true
            return
        }
        if (!dirty || conflict) return
        running.value = true
        scope.launch {
            try {
                do {
                    again = false
                    val isConflict = repo.withPageLock(id) { doSave() }
                    if (isConflict) conflict = true          // Dialog/Banner der Seite (Glossar: "Seite wurde woanders geaendert")
                } while (again && dirty && !conflict && error == null && !offline)
            } finally {
                running.value = false
            }
        }
    }

    /** Beim Verlassen der Seite / App: Entwurf sofort auf Platte und sofort speichern (laeuft im App-Scope weiter) */
    fun flush() {
        debounceJob?.cancel()
        if (dirty) {
            writeDraftSoon(0)
            requestSave()
        }
    }

    /** Speichern und warten, bis nichts mehr laeuft (vor Sperren, Neu laden, Serverwechsel) */
    suspend fun saveAndWait() {
        flush()
        running.first { !it }
        draftJob?.join()
    }

    /** Fuer Aufrufer, die nur "jetzt speichern" wollen */
    suspend fun save() = saveAndWait()

    /** -> true bei Konflikt (409) */
    private suspend fun doSave(): Boolean {
        if (!fetched || !dirty || locked || conflict || lost != null || repo.currentServer != server) return false
        // anderes Konto angemeldet (z. B. Kopplung per QR): Eingaben bleiben als Entwurf des alten Kontos (flush), nie ins neue
        if (AccountKey.switched(account, repo.currentAccountKey)) return false
        if (repo.signedOut) {
            error = UiText.Res(R.string.save_sign_in)
            return false
        }
        val v = version
        val t = title
        val d = doc
        val ow = overwriteNext
        saving = true
        try {
            val body = if (isDatabase) null else withContext(cpu) { BlockDoc.serialize(BlockDoc.withTime(io.github.yggdrasil_sh.saga.blocks.RenderPreview.docForSave(d), clock())) }
            (if (ow) repo.savePage(id, t, body, rev, true) else repo.savePage(id, t, body, rev))?.let { rev = it }
            if (ow) overwriteNext = false
            conflictBy = null
            ncBase = NcLogic.keys(d)
            savedVersion = v
            error = null
            offline = false
            savedAt = clock()
            repo.updateTitleLocal(id, t)
            if (!dirty) deleteDraftSoon()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            offline = false                                  // Server hat geantwortet: kein Offline-Hinweis mehr (BUG-107)
            // 2.3: neu eingefuegte/kopierte Nextcloud-Datei abgelehnt (z. B. aus dem Nextcloud einer anderen Person) -
            // verstaendlich melden, nicht als Konflikt oder verlorenen Zugriff behandeln; der Block muss raus
            if (NcLogic.isRejection(e.code, e.body, NcLogic.newKeys(d, ncBase))) {
                error = UiText.Res(R.string.nc_save_rejected, e.text)
                return false
            }
            when (e.code) {
                409 -> {
                    conflictBy = conflictAuthor(e.body)
                    return true
                }
                403, 404 -> if (!share.role.isOwner) {
                    // Freigabe entzogen / Rolle auf Lesen gefallen: nicht weiter speichern, letzten Text zum Kopieren zeigen
                    lost = LostAccess(e.code, withContext(cpu) { DocText.plain(t, d) })
                    retryJob?.cancel()
                    // G2: Seite sofort im neuen Zustand zeigen (Etikett/Rolle, nur lesen bzw. "Kein Zugriff"), nicht erst
                    // nach dem Dialog. Der Text liegt im Dialog; der Entwurf auf Platte bleibt bis closeLost().
                    savedVersion = version
                    scope.launch { load() }                      // wartet auf die Seitensperre dieses Speicherns
                    return false
                } else {
                    error = UiText.Res(R.string.save_failed, e.text)
                }
                423 -> {
                    locked = true
                    error = UiText.Res(R.string.save_locked)
                }
                401 -> error = UiText.Res(R.string.save_sign_in)
                else -> error = UiText.Res(R.string.save_failed, e.text)
            }
        } catch (e: Exception) {
            if (repo.isOffline(e)) {
                offline = true                               // Glossar "Offline-Hinweis"; Entwurf liegt auf Platte
                error = null
                writeDraftSoon(0)
            } else {
                error = UiText.Res(R.string.save_failed_retry, repo.friendly(e))
            }
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(10_000)
                retryJob = null
                error = null
                requestSave()
            }
        } finally {
            saving = false
        }
        return false
    }

    // ---------------------------------------------------------------- Entwuerfe

    private fun writeDraftSoon(delayMs: Long = 400) {
        if (!fetched || isDatabase) return
        draftJob?.cancel()
        draftJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            writeDraftNow()
        }
    }

    private suspend fun writeDraftNow() {
        if (!dirty) return
        val v = version
        val t = title
        val d = doc
        val r = rev
        val body = withContext(cpu) { BlockDoc.serialize(d) }
        draftLock.withLock {
            withContext(io) {
                drafts.write(Draft(id, t, body, r, clock()))
            }
            if (savedVersion >= v) withContext(io) { drafts.delete(id) }   // inzwischen gespeichert
        }
    }

    private fun deleteDraftSoon() {
        scope.launch {
            draftLock.withLock {
                if (!dirty) withContext(io) { drafts.delete(id) }
            }
        }
    }

    // ---------------------------------------------------------------- Konflikt

    /**
     * Konflikt: meine Fassung behalten - auf den aktuellen Server-Stand (Revision) setzen und mit overwrite
     * ueberschreiben (der Server sichert die andere Fassung als Version, mit Autor).
     */
    suspend fun keepMine() {
        try {
            val p = repo.withPageLock(id) { repo.loadPage(id) }
            rev = BlockDoc.int(p, "rev")
            share = PageShareInfo.of(p)
            conflict = false
            conflictBy = null
            info = null
            overwriteNext = share.supported                  // aeltere Server kennen overwrite nicht
            if (!dirty) version++
            requestSave()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = UiText.Res(R.string.save_failed, repo.friendly(e))
        }
    }

    /** Konflikt: meine Fassung als neue Seite sichern, dann die Server-Fassung laden. -> ID der Kopie oder null */
    suspend fun saveCopyAndLoadServer(copyTitle: String): Long? {
        return try {
            // neue Seite: Nextcloud-Dateien anderer Personen nimmt der Server dort nicht an (403) - weglassen
            val (copyDoc, dropped) = NcLogic.stripForeign(doc, repo.ncMe())
            val body = withContext(cpu) { BlockDoc.serialize(BlockDoc.withTime(copyDoc, clock())) }
            val newId = repo.createPageWith(copyTitle, body)
            savedVersion = version
            conflict = false
            withContext(io) { drafts.delete(id) }
            info = if (dropped > 0) UiText.Res(R.string.nc_copy_dropped) else null
            fetched = false
            load()
            newId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = UiText.Res(R.string.save_failed, repo.friendly(e))
            null
        }
    }

    /**
     * Konflikt mit einer anderen Person: "Neue Version laden" (Hauptknopf, wie im Web). Der eigene, nicht
     * gespeicherte Stand kommt als Klartext zurueck (Kopier-Dialog), danach gilt die Fassung vom Server.
     * -> eigener Text oder null (nichts Eigenes)
     */
    suspend fun loadServerVersion(): String? {
        val mine = if (dirty) withContext(cpu) { DocText.plain(title, doc) }.ifBlank { null } else null
        savedVersion = version
        conflict = false
        conflictBy = null
        overwriteNext = false
        draftLock.withLock { withContext(io) { drafts.delete(id) } }
        info = null
        fetched = false
        load()
        return mine
    }

    /** Dialog "Kein Zugriff mehr"/"Nur noch lesen" geschlossen: Entwurf weg, Seite neu laden (403: nur lesen, 404: kein Zugriff) */
    suspend fun closeLost() {
        lost ?: return
        savedVersion = version
        draftLock.withLock { withContext(io) { drafts.delete(id) } }
        lost = null
        fetched = false
        load()
    }

    /** Editor wird nicht mehr angezeigt */
    fun close() {
        flush()
        repo.unregister(this)
    }

    // ---------------------------------------------------------------- Seiten-Aktionen

    /**
     * Symbol/Titelbild/Position sofort zeigen und speichern (PATCH wie Web/iOS; JsonNull bzw. "" = entfernen).
     * Klappt das Speichern nicht, gilt wieder der alte Stand (Fehler geht an den Aufrufer).
     */
    suspend fun setHeader(fields: JsonObject) {
        val old = Triple(icon, cover, coverPos)
        applyHeader(fields, partial = true)
        try {
            repo.patchPage(id, fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            icon = old.first; cover = old.second; coverPos = old.third
            throw e
        }
    }

    /** G9: Schrift (null = Standard) sofort zeigen und per PATCH speichern; Fehler: alter Stand */
    suspend fun setFont(f: String?) {
        val old = font
        font = f
        try {
            repo.patchPage(id, Gestalten.patchFont(f))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            font = old
            throw e
        }
    }

    /** G9: kleiner Text an/aus (PATCH small) */
    suspend fun setSmall(on: Boolean) {
        val old = small
        small = on
        try {
            repo.patchPage(id, Gestalten.patchSmall(on))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            small = old
            throw e
        }
    }

    /** G3: Farbe eines Blocks (null = Standard) - nur Text-Typen, andere tunes-Schluessel bleiben */
    fun setBlockStyle(path: JsonPath, color: String?, bg: Boolean) {
        if (!editable) return
        val b = block(path) ?: return
        if (!Gestalten.canStyle(b)) return
        updateBlock(path) { Gestalten.setStyle(it, color, bg) }
    }

    /** Tags setzen (sofort sichtbar, dann speichern; Fehler: alter Stand) */
    suspend fun setTags(new: List<String>) {
        val old = tags
        tags = new
        try {
            repo.patchPage(id, JsonObject(mapOf("tags" to kotlinx.serialization.json.JsonArray(new.map { JsonPrimitive(it) }))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tags = old
            throw e
        }
    }

    private fun applyHeader(p: JsonObject, partial: Boolean = false) {
        if (partial) (p["icon"] as? JsonPrimitive)?.let { icon = it.contentOrNull.orEmpty() }
        if (!partial || "cover" in p) cover = (p["cover"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
        if (!partial || "cover_pos" in p) coverPos = ((p["cover_pos"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 50.0)
            .takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 50.0
    }

    suspend fun setPinned(on: Boolean) {
        repo.setPinned(id, on)
        pinned = on
    }

    suspend fun setLocked(on: Boolean) {
        if (on) saveAndWait()                                // erst speichern, dann sperren (wie im Web)
        repo.setLocked(id, on)
        locked = on
        if (on) active = null
        error = null
    }
}

private fun jobj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(linkedMapOf(*pairs))

/**
 * A02: Eigenschaften einer moeglichen Datenbank-Zeile - auch leer, sobald es eine Elternseite gibt (neue Eintraege
 * und Vorlagen haben noch keine Werte). Ohne Elternseite keine Zeile.
 */
internal fun rowProps(p: JsonObject, parentId: Long?): JsonObject? =
    (p["props"] as? JsonObject) ?: if (parentId != null) JsonObject(emptyMap()) else null
