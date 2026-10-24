package io.github.yggdrasil_sh.saga.ui.database

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.db.DbFilter
import io.github.yggdrasil_sh.saga.db.DbGroup
import io.github.yggdrasil_sh.saga.db.DbPage
import io.github.yggdrasil_sh.saga.db.DbParams
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbQuery
import io.github.yggdrasil_sh.saga.db.DbRow
import io.github.yggdrasil_sh.saga.db.DbSchema
import io.github.yggdrasil_sh.saga.db.DbSort
import io.github.yggdrasil_sh.saga.db.DbStore
import io.github.yggdrasil_sh.saga.db.DbValues
import io.github.yggdrasil_sh.saga.db.DbView
import io.github.yggdrasil_sh.saga.db.DbViews
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId

/** Eine Board-Spalte: eigene Abfrage (Filter der Ansicht + "Spalte = Option"), eigene Seiten */
data class DbColumn(val option: String?, val rows: List<DbRow> = emptyList(), val matched: Int = 0, val loading: Boolean = false)

/**
 * Zustand einer Datenbank-Ansicht. Server 2.2+: Ansicht/Filter/Sortierung/Suche wendet der Server an und liefert Seiten
 * (limit/offset) - die App laedt nie alle Zeilen auf einmal. Aeltere Server/Gegenstellen liefern alle Zeilen; dann
 * filtert und sortiert die App selbst genau wie das Web (DbQuery) und zeigt sie ebenfalls stueckweise (LazyColumn).
 */
@Stable
class DbViewState(
    val dbId: Long,
    private val repo: SagaRepository,
    private val scope: CoroutineScope,
    initialView: String?,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val store: DbStore get() = repo.dbStore

    var schema by mutableStateOf<DbSchema?>(null)
        private set
    var role by mutableStateOf("owner")
        private set
    var viewId by mutableStateOf(initialView)
        private set
    var search by mutableStateOf("")
        private set

    /** Tabelle/Liste/Galerie: geladene Zeilen der Ansicht (in Reihenfolge der Ansicht) */
    var rows by mutableStateOf<List<DbRow>>(emptyList())
        private set
    var groups by mutableStateOf<List<DbGroup>?>(null)
        private set
    var matched by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var total by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var loaded by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var noAccess by mutableStateOf(false)
        private set
    /** 2.5: Vorlagen der Datenbank ("Neu ▾", Vorlagen verwalten) - nur der Eigentuemer bekommt sie vom Server */
    var templates by mutableStateOf<List<io.github.yggdrasil_sh.saga.db.DbTemplate>>(emptyList())
        private set
    /** Vorlagen-Verwaltung offen */
    var managing by mutableStateOf(false)
    var offline by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiText?>(null)

    /** Board: Spalten (Option-ID, null = "Ohne") */
    val columns = mutableStateMapOf<String?, DbColumn>()

    /** Kalender: angezeigter Monat und seine Eintraege */
    var month by mutableStateOf(LocalDate.now(zone).withDayOfMonth(1))
        private set
    var monthRows by mutableStateOf<List<DbRow>>(emptyList())
        private set

    // Oberflaeche (Dialoge liegen ausserhalb der LazyColumn, damit sie beim Scrollen nicht verschwinden)
    /** Wert, der gerade bearbeitet wird (Zeile, Eigenschaft) */
    var editing by mutableStateOf<Pair<DbRow, DbProp>?>(null)
    /** offenes Blatt: "filter", "sort", "view", "new_view" */
    var sheet by mutableStateOf<String?>(null)
    /** Tabelle: alle Zeilen scrollen gemeinsam waagrecht */
    val hScroll = androidx.compose.foundation.ScrollState(0)

    /** Server wendet die Ansicht an (2.2+); sonst alle Zeilen lokal */
    private var serverPaged = true
    private var allRows: List<DbRow>? = null
    private var gen = 0
    private var loadJob: Job? = null
    private var ownChange = -1
    /** eigene Schema-Aenderung, die der Server noch nicht bestaetigt hat - Laden ueberschreibt sie nicht */
    private var pendingSchema: JsonObject? = null
    /** Schema-Speichern nacheinander (immer der neueste Stand, mit der srev der vorigen Antwort) */
    private val saveLock = kotlinx.coroutines.sync.Mutex()

    val view: DbView? get() = schema?.view(viewId)
    val readOnly: Boolean get() = role == "view" || role == "comment"
    /** Ansichten/Filter/Sortierung aendern (Eigentuemer/Bearbeiten, nicht gesperrt) */
    val canEditViews: Boolean get() = !readOnly && schema?.locked != true && dbId > 0
    val canAdd: Boolean get() = !readOnly
    val hasMore: Boolean get() = rows.size < matched

    private val tz: String get() = zone.id

    private fun query(v: DbView?) = DbQuery(schema ?: DbSchema(JsonObject(emptyMap())), LocalDate.now(zone), zone) { id -> repo.page(id)?.title }

    // ------------------------------------------------------------------ Laden

    fun selectView(id: String) {
        if (id == viewId) return
        viewId = id
        repo.appContextPrefs()?.edit { putString("db_view_$dbId", id) }
        reload()
    }

    fun updateSearch(q: String) {
        search = q
        reload(debounce = 300)
    }

    fun showMonth(m: LocalDate) {
        month = m.withDayOfMonth(1)
        reload()
    }

    /** Alles der aktuellen Ansicht neu laden (Live-Ereignis, Aenderung, Ansicht gewechselt) */
    fun reload(debounce: Long = 0) {
        val g = ++gen
        loadJob?.cancel()
        loadJob = scope.launch {
            if (debounce > 0) delay(debounce)
            try {
                load(g)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (g != gen) return@launch
                if (e.code == 404 || e.code == 403) {
                    noAccess = true
                    rows = emptyList()
                } else error = e.text
            } catch (e: Exception) {
                if (g == gen) error = repo.friendly(e)
            } finally {
                if (g == gen) loaded = true
            }
        }
    }

    private suspend fun fetch(q: String): DbPage {
        val r = store.rows(dbId, q)
        offline = r is DbStore.Loaded.Cached
        val p = when (r) {
            is DbStore.Loaded.Fresh -> r.page
            is DbStore.Loaded.Cached -> r.page
        }
        noAccess = false
        role = p.role
        templates = p.templates
        if (pendingSchema == null) schema = p.schema
        if (viewId != null && p.schema.views.none { it.id == viewId }) viewId = p.schema.views.first().id
        return p
    }

    private suspend fun load(g: Int) {
        val s = schema
        val v = s?.view(viewId)
        val first = if (s == null || v == null || !serverPaged) null else baseQuery(v, s)
        // Schema noch unbekannt (erstes Oeffnen) oder alter Server: erste Seite holt Schema und zeigt, ob der Server pagt
        val p = fetch(first ?: DbParams.query(view = viewId, limit = PAGE, tz = tz, today = LocalDate.now(zone), q = search))
        if (g != gen) return
        serverPaged = p.paged
        if (!serverPaged) {
            allRows = p.rows
            total = p.rows.size
            applyLocal()
            return
        }
        allRows = null
        total = p.total ?: p.rows.size
        val sc = schema ?: p.schema
        val cur = sc.view(viewId)
        when (cur.type) {
            "board" -> loadBoard(g, sc, cur)
            "calendar" -> loadMonth(g, sc, cur)
            else -> {
                // Antwort passt zur Abfrage der Ansicht (sonst war das Schema neu: richtig abfragen)
                val page = if (first != null || sc.groupProp(cur) == null) p else fetch(baseQuery(cur, sc))
                if (g != gen) return
                rows = page.rows
                matched = page.matched ?: page.rows.size
                groups = page.groups
            }
        }
    }

    /** Abfrage der Ansicht: Filter/Sortierung aus der Ansicht (lokale Aenderung gilt sofort), Seite 1 */
    private fun baseQuery(v: DbView, s: DbSchema, offset: Int = 0): String = DbParams.query(
        view = v.id, filters = v.filters, sorts = v.sorts, q = search,
        group = if (v.type == "table" || v.type == "list") s.groupProp(v)?.id ?: "" else null,
        limit = PAGE, offset = offset, tz = tz, today = LocalDate.now(zone),
    )

    /** Naechste Seite (Ende der Liste erreicht) */
    fun loadMore() {
        val s = schema ?: return
        val v = view ?: return
        if (loadingMore || !hasMore || !serverPaged) return
        val g = gen
        loadingMore = true
        scope.launch {
            try {
                val p = store.rows(dbId, baseQuery(v, s, rows.size))
                val page = (p as? DbStore.Loaded.Fresh)?.page ?: return@launch
                if (g != gen) return@launch
                val have = rows.mapTo(HashSet()) { it.id }
                rows = rows + page.rows.filter { it.id !in have }
                matched = page.matched ?: matched
                groups = page.groups ?: groups
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                loadingMore = false
            }
        }
    }

    private suspend fun loadBoard(g: Int, s: DbSchema, v: DbView) {
        val gp = s.groupProp(v)
        if (gp == null) {
            columns.clear()
            rows = fetch(baseQuery(v, s)).rows
            return
        }
        val keys = listOf<String?>(null) + gp.options.map { it.id }
        val result = kotlinx.coroutines.coroutineScope {
            keys.map { k -> async { k to fetch(columnQuery(v, gp, k, 0)) } }.awaitAll()
        }
        if (g != gen) return
        columns.keys.retainAll(keys.toSet())
        for ((k, p) in result) columns[k] = DbColumn(k, p.rows, p.matched ?: p.rows.size)
        matched = columns.values.sumOf { it.matched }
    }

    private fun columnQuery(v: DbView, gp: DbProp, option: String?, offset: Int): String = DbParams.query(
        view = v.id, filters = v.filters + DbParams.columnFilter(gp.id, option), sorts = v.sorts, q = search, group = "",
        limit = COLUMN_PAGE, offset = offset, tz = tz, today = LocalDate.now(zone),
    )

    fun loadMoreColumn(option: String?) {
        val s = schema ?: return
        val v = view ?: return
        val gp = s.groupProp(v) ?: return
        val c = columns[option] ?: return
        if (c.loading || c.rows.size >= c.matched || !serverPaged) return
        columns[option] = c.copy(loading = true)
        val g = gen
        scope.launch {
            try {
                val p = (store.rows(dbId, columnQuery(v, gp, option, c.rows.size)) as? DbStore.Loaded.Fresh)?.page
                if (g != gen || p == null) return@launch
                val cur = columns[option] ?: return@launch
                val have = cur.rows.mapTo(HashSet()) { it.id }
                columns[option] = cur.copy(rows = cur.rows + p.rows.filter { it.id !in have }, matched = p.matched ?: cur.matched)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                columns[option]?.let { columns[option] = it.copy(loading = false) }
            }
        }
    }

    private suspend fun loadMonth(g: Int, s: DbSchema, v: DbView) {
        val dp = s.dateProp(v)
        if (dp == null) {
            monthRows = emptyList()
            return
        }
        val q = DbParams.query(
            view = v.id, filters = v.filters + DbParams.monthFilters(dp.id, month), sorts = v.sorts, q = search, group = "",
            limit = 5000, tz = tz, today = LocalDate.now(zone),
        )
        val p = fetch(q)
        if (g != gen) return
        monthRows = p.rows
        matched = p.matched ?: p.rows.size
    }

    /** Alter Server: alles lokal wie im Web */
    private fun applyLocal() {
        val s = schema ?: return
        val v = s.view(viewId)
        val all = store.overlay(allRows ?: return)
        val q = query(v)
        val shown = q.apply(all, v, search)
        when (v.type) {
            "board" -> {
                columns.clear()
                s.groupProp(v)?.let { gp -> q.groups(shown, gp).forEach { (grp, rs) -> columns[grp.id] = DbColumn(grp.id, rs, rs.size) } }
                rows = shown
            }
            "calendar" -> {
                val dp = s.dateProp(v)
                monthRows = if (dp == null) emptyList() else {
                    val fs = DbParams.monthFilters(dp.id, month)
                    shown.filter { r -> fs.all { q.matches(r, it) } }
                }
            }
            else -> {
                val gp = s.groupProp(v)
                if (gp != null) {
                    val gs = q.groups(shown, gp)
                    groups = gs.map { it.first }
                    rows = gs.flatMap { it.second }
                } else {
                    groups = null
                    rows = shown
                }
            }
        }
        matched = shown.size
    }

    // ------------------------------------------------------------------ Werte und Eintraege

    /** Wert setzen: sofort sichtbar, dann senden (offline: Warteschlange) */
    fun setProp(row: DbRow, prop: DbProp, value: JsonElement?) {
        if (readOnly || !prop.editable) return
        val upd = DbValues.apply(row, prop.id, value)
        rows = rows.map { if (it.id == row.id) upd else it }
        monthRows = monthRows.map { if (it.id == row.id) upd else it }
        allRows = allRows?.map { if (it.id == row.id) upd else it }
        moveOnBoard(upd, prop)
        scope.launch {
            try {
                ownChange = store.changes.value + 1
                val r = store.setProp(row.id, prop.id, value)
                if (r == DbStore.Saved.QUEUED) repo.notify(UiText.Res(R.string.dbv_saved_offline))
                else reload(debounce = 700)                              // Filter/Sortierung des Servers
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(UiText.Res(R.string.dbv_not_saved, if (e is ApiException) e.text else repo.friendly(e)))
                reload()
            }
        }
    }

    /** Board: Karte sofort in die neue Spalte (Spalten-Eigenschaft geaendert) */
    private fun moveOnBoard(r: DbRow, prop: DbProp) {
        val s = schema ?: return
        val v = view ?: return
        if (v.type != "board" || s.groupProp(v)?.id != prop.id) return
        val target = (r.props[prop.id] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { id -> prop.option(id) != null }
        for ((k, c) in columns.toMap()) {
            val has = c.rows.any { it.id == r.id }
            if (k == target && !has) columns[k] = c.copy(rows = listOf(r) + c.rows, matched = c.matched + 1)
            else if (k != target && has) columns[k] = c.copy(rows = c.rows.filter { it.id != r.id }, matched = (c.matched - 1).coerceAtLeast(0))
        }
    }

    /** 2.5: Standardvorlage fuer "Neu" (Schema default_tpl), nur wenn es sie noch gibt */
    val defaultTemplate: io.github.yggdrasil_sh.saga.db.DbTemplate?
        get() {
            val id = (schema?.raw?.get("default_tpl") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: return null
            return templates.firstOrNull { it.id == id }
        }
    val canManageTemplates: Boolean get() = role == "owner" && dbId > 0

    /** Neuer Eintrag aus einer Vorlage -> ID */
    suspend fun createFrom(t: io.github.yggdrasil_sh.saga.db.DbTemplate): Long? = try {
        ownChange = store.changes.value + 1
        val id = repo.buttonEntry(dbId, "", t.id)
        reload()
        id
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        repo.notify(UiText.Res(R.string.dbv_not_created, if (e is ApiException) e.text else repo.friendly(e)))
        null
    }

    /** "Neu": Standardvorlage, wenn gesetzt, sonst leer mit den Werten des Filters */
    suspend fun createDefault(): Long? = defaultTemplate?.let { createFrom(it) } ?: create()

    /** Vorlagen-Aktion ausfuehren, danach neu laden; Fehler als Hinweis */
    fun tplOp(op: suspend () -> Unit) {
        scope.launch {
            try {
                op(); reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(UiText.Res(R.string.dbv_not_saved, if (e is ApiException) e.text else repo.friendly(e)))
            }
        }
    }
    fun tplRename(t: io.github.yggdrasil_sh.saga.db.DbTemplate, n: String) { if (n.trim() != t.title) tplOp { repo.templateRename(t.id, n) } }
    fun tplDuplicate(t: io.github.yggdrasil_sh.saga.db.DbTemplate, title: String) = tplOp { repo.templateDuplicate(t.id, dbId, title) }
    fun tplUntemplate(t: io.github.yggdrasil_sh.saga.db.DbTemplate) { if (defaultTemplate?.id == t.id) setDefaultTemplate(null); tplOp { repo.templateSet(t.id, false) } }
    fun tplTrash(t: io.github.yggdrasil_sh.saga.db.DbTemplate) { if (defaultTemplate?.id == t.id) setDefaultTemplate(null); tplOp { repo.templateTrash(t.id) } }
    suspend fun tplNew(title: String): Long? = try {
        repo.templateNew(dbId, title).also { reload() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        repo.notify(UiText.Res(R.string.dbv_not_created, if (e is ApiException) e.text else repo.friendly(e))); null
    }
    fun setDefaultTemplate(id: Long?) {
        if (!canEditViews) return
        editSchema { raw -> JsonObject(LinkedHashMap(raw).apply { put("default_tpl", if (id != null) JsonPrimitive(id) else kotlinx.serialization.json.JsonNull) }) }
    }

    /** Neuer Eintrag mit Werten aus dem Filter der Ansicht (+ extra, z. B. Board-Spalte/Kalendertag) -> ID */
    suspend fun create(extra: Map<String, JsonElement> = emptyMap()): Long? {
        if (!canAdd) return null
        val v = view ?: return null
        val props = JsonObject(query(v).defaults(v) + extra)
        return try {
            ownChange = store.changes.value + 1
            val id = repo.newRow(dbId, props)
            reload()
            id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.notify(UiText.Res(R.string.dbv_not_created, if (e is ApiException) e.text else repo.friendly(e)))
            null
        }
    }

    // ------------------------------------------------------------------ Ansichten (Schema)

    /** Schema aendern (Ansicht anlegen/umbenennen, Filter, Sortierung ...): sofort anwenden, dann speichern */
    fun editSchema(change: (JsonObject) -> JsonObject) {
        if (!canEditViews) return
        val s = schema ?: return
        val raw = change(s.raw)
        if (raw == s.raw) return
        schema = DbSchema(raw)
        pendingSchema = raw
        if (viewId != null && schema!!.views.none { it.id == viewId }) viewId = schema!!.views.first().id
        reload()
        scope.launch {
            try {
                saveLock.lock()
                try {
                    val send = pendingSchema ?: return@launch          // schon mit einer spaeteren Aenderung gesendet
                    ownChange = store.changes.value + 1
                    val saved = store.saveSchema(dbId, send)
                    if (pendingSchema == send) {                        // srev uebernehmen (keine neuere Aenderung dazwischen)
                        pendingSchema = null
                        schema = DbSchema(saved)
                    } else pendingSchema = pendingSchema?.let { JsonObject(LinkedHashMap(it).apply { saved["srev"]?.let { r -> put("srev", r) } }) }
                } finally {
                    saveLock.unlock()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(UiText.Res(R.string.dbv_not_saved, if (e is ApiException) e.text else repo.friendly(e)))
                pendingSchema = null
                schema = null                                           // Stand des Servers neu holen
                reload()
            }
        }
    }

    fun addView(type: String, name: String) {
        val id = DbViews.newId()
        editSchema { DbViews.add(it, type, name, id) }
        viewId = id
        repo.appContextPrefs()?.edit { putString("db_view_$dbId", id) }
    }

    fun setFilters(fs: List<DbFilter>) = view?.let { v -> editSchema { DbViews.setFilters(it, v.id, fs) } }
    fun setSorts(ss: List<DbSort>) = view?.let { v -> editSchema { DbViews.setSorts(it, v.id, ss) } }

    /** Neue Option fuer eine Auswahl anlegen und gleich setzen */
    fun addOption(row: DbRow?, prop: DbProp, name: String, multi: Boolean, onRow: ((JsonElement) -> Unit)? = null) {
        val s = schema ?: return
        if (readOnly || name.isBlank()) return
        val (raw, oid) = DbViews.addOption(s.raw, prop.id, name)
        schema = DbSchema(raw)
        pendingSchema = raw
        scope.launch {
            try {
                val saved = store.saveSchema(dbId, raw)
                if (pendingSchema == raw) pendingSchema = null
                schema = DbSchema(saved)
                val p = schema?.prop(prop.id) ?: return@launch
                val v = if (multi) DbValues.toggle(row?.props?.get(prop.id), oid) else JsonPrimitive(oid)
                if (row != null) setProp(row, p, v) else onRow?.invoke(v)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(UiText.Res(R.string.dbv_not_saved, if (e is ApiException) e.text else repo.friendly(e)))
                pendingSchema = null
                schema = null
                reload()
            }
        }
    }

    /** Aenderungen anderswo (andere Ansicht, Eintrag als Seite, Live): neu laden - eigene nicht doppelt */
    fun onStoreChange(tick: Int) {
        if (tick == ownChange) return
        if (!serverPaged) applyLocal()
        reload(debounce = 400)
    }

    companion object {
        const val PAGE = 100
        const val COLUMN_PAGE = 50
    }
}

/** Gemerkte Ansicht je Datenbank (wie hk-db-view im Web) */
fun SagaRepository.appContextPrefs(): android.content.SharedPreferences? = dbPrefs
