package io.github.yggdrasil_sh.saga.ui.page

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.InlineStyle
import io.github.yggdrasil_sh.saga.blocks.JsonPath
import io.github.yggdrasil_sh.saga.blocks.JsonTree
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * Zustand einer Seite im Editor. REGELN (aus echten Fehlern gelernt):
 *  - nie speichern, wenn die Seite nicht erfolgreich geladen wurde (fetched)
 *  - nur speichern, wenn wirklich etwas geaendert wurde (dirty)
 *  - gesperrte Seiten (locked) nicht bearbeiten
 *  - unbekannte Bloecke bleiben unveraendert im JSON (es werden nur einzelne Werte ersetzt)
 *  - Speichern mit Revision: 409 = woanders geaendert -> neu laden, 423 = gesperrt
 */
@Stable
class PageEditor(val id: Int, private val repo: SagaRepository, private val scope: CoroutineScope) {
    /** Server, von dem die Seite stammt - nie auf einen anderen Server speichern */
    private val server = repo.server.value

    var doc by mutableStateOf(JsonObject(emptyMap()))
        private set
    var title by mutableStateOf("")
        private set
    var icon by mutableStateOf("")
        private set
    var kind by mutableStateOf("page")
        private set
    var locked by mutableStateOf(false)
        private set
    var pinned by mutableStateOf(false)
    var loading by mutableStateOf(true)
        private set
    var fetched by mutableStateOf(false)
        private set
    var converted by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiText?>(null)
    var info by mutableStateOf<UiText?>(null)

    /** Feld, das den Fokus bekommen soll (Schluessel) - das Feld setzt es nach dem Fokussieren zurueck */
    var focusRequest by mutableStateOf<String?>(null)
    var focusAtEnd = false
    var active by mutableStateOf<ActiveField?>(null)

    private var rev: Int? = null
    private var version = 0
    private var savedVersion = 0
    val dirty: Boolean get() = version != savedVersion
    val isDatabase: Boolean get() = kind == "database"
    val editable: Boolean get() = fetched && !locked && !isDatabase
    private var saveJob: Job? = null

    // ---------------------------------------------------------------- Laden

    suspend fun load() {
        loading = true
        try {
            val p = repo.withPageLock(id) { repo.loadPage(id) }
            if (dirty && fetched) return                     // eigene, noch nicht gespeicherte Eingaben schuetzen
            apply(p)
            repo.visited(id)
        } catch (_: BlockDoc.UnreadableException) {
            fetched = false
            error = UiText.Res(R.string.page_unreadable)
        } catch (e: Exception) {
            if (!fetched) error = repo.friendly(e) else info = UiText.Res(R.string.page_reload_failed, repo.friendly(e))
        } finally {
            loading = false
        }
    }

    private fun apply(p: JsonObject) {
        val bodyEl = p["body"] as? JsonPrimitive
        val body = if (bodyEl != null && bodyEl.isString) bodyEl.content else null
        val parsed = BlockDoc.parse(body, BlockDoc.str(p, "format").ifEmpty { "text" })
        title = BlockDoc.str(p, "title")
        icon = BlockDoc.str(p, "icon")
        kind = BlockDoc.str(p, "kind").ifEmpty { "page" }
        locked = BlockDoc.bool(p, "locked")
        pinned = BlockDoc.bool(p, "pinned")
        rev = BlockDoc.int(p, "rev")
        converted = parsed.converted && !body.isNullOrBlank()
        var d = parsed.doc
        if (BlockDoc.blocks(d).isEmpty() && kind != "database") {      // leere Seite: ein Absatz zum Losschreiben (erst gespeichert, wenn etwas getippt wird)
            d = JsonTree.set(d, listOf("blocks"), JsonArray(listOf(BlockDoc.newBlock("paragraph")))) as JsonObject
        }
        doc = d
        version = 0
        savedVersion = 0
        fetched = true
        error = null
    }

    // ---------------------------------------------------------------- Aendern

    private fun touched() {
        version++
        scheduleSave()
    }

    private fun change(newDoc: JsonObject) {
        if (!editable || newDoc == doc) return
        doc = newDoc
        touched()
    }

    fun editTitle(v: String) {
        if (!fetched || locked) return
        val t = v.replace('\n', ' ')
        if (t == title) return
        title = t
        touched()
    }

    fun setString(path: JsonPath, value: String) {
        val cur = JsonTree.get(doc, path) as? JsonPrimitive
        if (cur != null && cur.isString && cur.content == value) return
        change(JsonTree.set(doc, path, JsonPrimitive(value)) as JsonObject)
    }

    fun block(path: JsonPath): JsonObject? = JsonTree.get(doc, path) as? JsonObject

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
        focusRequest = key
    }

    private fun arrayAndIndex(path: JsonPath): Pair<JsonPath, Int> = path.dropLast(1) to (path.last() as Int)

    fun insertBlockAfter(blockPath: JsonPath?, block: JsonObject, focus: Boolean = true) {
        if (!editable) return
        val (arr, idx) = if (blockPath.isNullOrEmpty()) listOf<Any>("blocks") to BlockDoc.blocks(doc).size - 1 else arrayAndIndex(blockPath)
        change(JsonTree.insert(doc, arr, idx + 1, block) as JsonObject)
        if (focus) requestFocus(fieldKeyFor(block, arr + (idx + 1)))
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
    fun endList(blockPath: JsonPath, itemPath: JsonPath) {
        if (!editable) return
        val (arr, idx) = arrayAndIndex(itemPath)
        var d = JsonTree.remove(doc, arr, idx) as JsonObject
        val topItems = JsonTree.get(d, blockPath + listOf("data", "items")) as? JsonArray
        if (topItems == null || topItems.isEmpty()) {
            val p = BlockDoc.newBlock("paragraph", id = BlockDoc.id(block(blockPath)) ?: BlockDoc.newId())
            d = JsonTree.set(d, blockPath, p) as JsonObject
            change(d)
            requestFocus(fieldKeyFor(p, blockPath))
        } else {
            change(d)
            insertBlockAfter(blockPath, BlockDoc.newBlock("paragraph"))
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
        change(JsonTree.set(doc, itemPath + "meta", nm) as JsonObject)
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

    // ---------------------------------------------------------------- Speichern

    fun scheduleSave(delayMs: Long = 1200) {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(delayMs)
            save()
        }
    }

    /** Beim Verlassen der Seite / App: sofort speichern (laeuft im App-Scope weiter) */
    fun flush() {
        saveJob?.cancel()
        if (dirty) scope.launch { save() }
    }

    suspend fun save() {
        val conflict = repo.withPageLock(id) { doSave() }
        if (conflict) {
            savedVersion = version                          // lokale Aenderung verwerfen, Stand vom Server holen
            load()
        }
    }

    /** -> true bei Konflikt (409) */
    private suspend fun doSave(): Boolean {
        if (!fetched || !dirty || locked || repo.server.value != server) return false
        if (repo.needsLogin.value) {
            error = UiText.Res(R.string.save_sign_in)
            return false
        }
        val v = version
        val t = title
        val d = doc
        saving = true
        try {
            val body = if (isDatabase) null else BlockDoc.serialize(BlockDoc.withTime(d, System.currentTimeMillis()))
            repo.savePage(id, t, body, rev)?.let { rev = it }
            savedVersion = v
            error = null
            repo.updateTitleLocal(id, t)
        } catch (e: ApiException) {
            when (e.code) {
                409 -> {
                    info = UiText.Res(R.string.save_conflict)
                    return true
                }
                423 -> {
                    locked = true
                    error = UiText.Res(R.string.save_locked)
                }
                401 -> error = UiText.Res(R.string.save_sign_in)
                else -> error = UiText.Res(R.string.save_failed, e.text)
            }
        } catch (e: Exception) {
            error = UiText.Res(R.string.save_failed_retry, repo.friendly(e))
            scheduleSave(10_000)
        } finally {
            saving = false
        }
        if (dirty && error == null) scheduleSave()
        return false
    }

    // ---------------------------------------------------------------- Seiten-Aktionen

    suspend fun setPinned(on: Boolean) {
        repo.setPinned(id, on)
        pinned = on
    }

    suspend fun setLocked(on: Boolean) {
        if (on) {
            saveJob?.cancel()
            save()                                           // erst speichern, dann sperren (wie im Web)
        }
        repo.setLocked(id, on)
        locked = on
        if (on) active = null
        error = null
    }
}
