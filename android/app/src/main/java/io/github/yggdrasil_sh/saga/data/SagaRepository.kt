package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.BuildConfig
import io.github.yggdrasil_sh.saga.auth.PairTarget
import io.github.yggdrasil_sh.saga.auth.Pkce
import io.github.yggdrasil_sh.saga.util.ServerUrl
import io.github.yggdrasil_sh.saga.util.Versions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Zentrale Daten der App (wie Store.swift in iOS): Seitenliste, Aufgaben, heutige Termine, Anmeldung.
 * Alle Funktionen laufen auf dem Haupt-Thread (Netzwerk asynchron ueber OkHttp).
 */
class SagaRepository(
    val settings: AppSettings,
    val secure: SecureStore,
    private val scope: CoroutineScope,
    private val deviceName: String,
    userAgent: String,
    val appVersion: String,
) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    val api = ApiClient({ settings.server.value }, secure, { needsLogin.value = true }, userAgent)

    val needsLogin = MutableStateFlow(settings.server.value.isEmpty() || secure.deviceToken == null)
    val pages = MutableStateFlow<List<PageMeta>>(emptyList())
    val tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val doneTasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val todayEvents = MutableStateFlow<List<CalEvent>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val serverVersion = MutableStateFlow<String?>(null)
    val minApp = MutableStateFlow<String?>(null)
    val loginError = MutableStateFlow<String?>(null)
    val loginBusy = MutableStateFlow(false)

    val server: StateFlow<String> get() = settings.server
    val appTooOld: Boolean get() = minApp.value?.let { Versions.isLess(appVersion, it) } ?: false

    // ---------------------------------------------------------------- Hilfen

    fun friendly(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Fehler"
        is UnknownHostException, is ConnectException -> "Server nicht erreichbar"
        is SocketTimeoutException -> "Zeitüberschreitung – Server antwortet nicht"
        is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Netzwerkfehler"
        else -> e.message ?: "Fehler"
    }

    private fun today(): String = LocalDate.now().toString()

    // ---------------------------------------------------------------- Laden

    suspend fun refresh() {
        if (needsLogin.value) return
        loading.value = true
        try {
            setPages(json.decodeFromString<List<PageMeta>>(api.get("api/notes")))
            error.value = null
            if (serverVersion.value == null) checkVersion()
            loadTasks()
            loadTodayEvents()
        } catch (e: Exception) {
            if (!needsLogin.value) error.value = friendly(e)
        } finally {
            loading.value = false
        }
    }

    private fun setPages(all: List<PageMeta>) {
        pages.value = all.filter { !it.archived }
    }

    /** Server-Version (oeffentlich, ohne Anmeldung); aeltere Server ohne /api/version: nichts anzeigen */
    suspend fun checkVersion() {
        try {
            val v = json.decodeFromString<VersionInfo>(api.request("GET", "api/version", auth = true))
            serverVersion.value = v.version
            minApp.value = v.minApp
        } catch (_: Exception) {
            serverVersion.value = null
            minApp.value = null
        }
    }

    fun page(id: Int): PageMeta? = pages.value.firstOrNull { it.id == id }

    /** Unterseiten (Zeilen von Datenbanken erscheinen nicht im Baum, wie im Web) */
    fun children(of: Int?, all: List<PageMeta> = pages.value): List<PageMeta> {
        val dbs = all.filter { it.isDatabase }.map { it.id }.toSet()
        return all.filter { it.parentId == of && (it.parentId == null || it.parentId !in dbs) }
    }

    suspend fun search(q: String): List<PageMeta> {
        if (q.trim().length < 2) return emptyList()
        return json.decodeFromString(api.post("api/notes/search", buildJsonObject { put("q", q.trim()) }))
    }

    suspend fun newPage(parent: Int? = null): Int {
        val b = buildJsonObject {
            put("title", "")
            if (parent != null) put("parent_id", parent)
        }
        val id = json.decodeFromString<NewId>(api.post("api/notes", b)).id
        refresh()
        return id
    }

    suspend fun setPinned(id: Int, pinned: Boolean) {
        api.patch("api/notes/$id", buildJsonObject { put("pinned", pinned) })
        pages.value = pages.value.map { if (it.id == id) it.copy(pinned = pinned) else it }
    }

    suspend fun setLocked(id: Int, locked: Boolean) {
        api.patch("api/notes/$id", buildJsonObject { put("locked", locked) })
        pages.value = pages.value.map { if (it.id == id) it.copy(locked = locked) else it }
    }

    fun updateTitleLocal(id: Int, title: String) {
        pages.value = pages.value.map { if (it.id == id && it.title != title) it.copy(title = title) else it }
    }

    fun visited(id: Int) {
        scope.launch { settings.visited(id) }
    }

    // ---------------------------------------------------------------- Seite laden/speichern

    private val pageLocks = ConcurrentHashMap<Int, Mutex>()

    /** Laden und Speichern derselben Seite nacheinander (nie alten Stand laden, waehrend gespeichert wird) */
    suspend fun <T> withPageLock(id: Int, block: suspend () -> T): T = pageLocks.getOrPut(id) { Mutex() }.withLock { block() }

    suspend fun loadPage(id: Int): JsonObject = json.decodeFromString(JsonObject.serializer(), api.get("api/notes/$id"))

    /** Titel + Inhalt mit Revision speichern -> neue Revision. 409 = woanders geaendert, 423 = gesperrt */
    suspend fun savePage(id: Int, title: String, body: String?, rev: Int?): Int? {
        val b = buildJsonObject {
            put("title", title)
            if (body != null) {
                put("body", body)
                put("format", "blocks")
            }
            if (rev != null) put("rev", rev)
        }
        return json.decodeFromString<SaveResult>(api.patch("api/notes/$id", b)).rev
    }

    // ---------------------------------------------------------------- Aufgaben

    suspend fun loadTasks() {
        try {
            val t = json.decodeFromString<TasksResponse>(api.get("api/tasks"))
            tasks.value = t.open
            doneTasks.value = t.done
        } catch (e: Exception) {
            if (!needsLogin.value) error.value = friendly(e)
        }
    }

    suspend fun addTask(text: String, due: String?) {
        api.post("api/tasks", buildJsonObject {
            put("text", text)
            put("prio", 2)
            if (due != null) put("due", due)
            put("today", today())
        })
        loadTasks()
    }

    suspend fun setTaskDone(t: TaskItem, done: Boolean) {
        if (done) tasks.value = tasks.value.filter { it.id != t.id }
        api.patch("api/tasks/${t.id}", buildJsonObject {
            put("done", done)
            put("today", today())
        })
        loadTasks()
    }

    suspend fun updateTask(id: Int, text: String, due: String?, prio: Int) {
        api.patch("api/tasks/$id", buildJsonObject {
            put("text", text)
            if (due != null) put("due", due) else put("due", JsonNull)
            put("prio", prio)
            put("today", today())
        })
        loadTasks()
    }

    suspend fun deleteTask(id: Int) {
        tasks.value = tasks.value.filter { it.id != id }
        doneTasks.value = doneTasks.value.filter { it.id != id }
        api.delete("api/tasks/$id")
        loadTasks()
    }

    // ---------------------------------------------------------------- Termine

    /** Termine ab heute fuer days Tage; null = Fehler (dann bisherigen Stand behalten) */
    suspend fun events(days: Int, fresh: Boolean = false): List<CalEvent>? = try {
        val t0 = LocalDate.now()
        val t1 = t0.plusDays(days.toLong())
        json.decodeFromString<EventsResponse>(api.get("api/calendar/events?start=$t0&end=$t1" + if (fresh) "&fresh=1" else "")).events
    } catch (e: Exception) {
        null
    }

    suspend fun loadTodayEvents() {
        events(1)?.let { todayEvents.value = it }
    }

    // ---------------------------------------------------------------- Dienste, Geraete

    suspend fun services(): List<ServiceItem> = json.decodeFromString(api.get("api/services"))

    suspend fun apps(): AppsResponse = json.decodeFromString(api.get("api/settings/apps"))

    suspend fun deleteDevice(id: Int) {
        api.delete("api/settings/devices/$id")
    }

    // ---------------------------------------------------------------- Anmeldung

    /** Schritt 1 der Browser-Anmeldung: Server pruefen/merken, PKCE erzeugen -> Adresse fuer den Custom Tab */
    suspend fun prepareBrowserLogin(serverInput: String): String {
        val server = ServerUrl.normalize(serverInput, BuildConfig.DEBUG)
            ?: throw IllegalArgumentException("Bitte eine https-Adresse eingeben, z. B. https://saga.example.org")
        if (server != settings.server.value) {
            api.cookies.clear()
            serverVersion.value = null
            minApp.value = null
        }
        settings.setServer(server)
        val p = Pkce.create()
        secure.pkceVerifier = p.verifier
        return "$server/app/login?challenge=${p.challenge}&scheme=saga"
    }

    /** Schritt 2: saga://auth?code=... -> Code + Geheimnis gegen Sitzung und Geraete-Schluessel tauschen */
    suspend fun completeBrowserLogin(code: String) {
        val verifier = secure.pkceVerifier
        if (verifier == null) {
            loginError.value = "Anmeldung bitte in der App neu starten."
            return
        }
        loginBusy.value = true
        loginError.value = null
        try {
            val r = json.decodeFromString<LoginResult>(api.request("POST", "app/exchange", buildJsonObject {
                put("code", code)
                put("verifier", verifier)
                put("device", deviceName)
                put("app_key", secure.appKey == null)
            }, auth = true))
            secure.pkceVerifier = null
            adopt(r)
        } catch (e: Exception) {
            loginError.value = if (e is ApiException && e.code == 401) "Code ungültig oder abgelaufen – bitte erneut anmelden." else friendly(e)
        } finally {
            loginBusy.value = false
        }
    }

    /** Anmeldung per QR-Code: Server-Adresse uebernehmen, Kopplungs-Code gegen Geraete-Anmeldung tauschen */
    suspend fun pair(target: PairTarget) {
        loginBusy.value = true
        loginError.value = null
        try {
            if (!target.server.startsWith("https://") && !(BuildConfig.DEBUG && ServerUrl.isLocalHttp(target.server))) {
                throw IllegalArgumentException("Der Server muss per https erreichbar sein.")
            }
            if (target.server != settings.server.value) {
                api.cookies.clear()
                serverVersion.value = null
                minApp.value = null
            }
            settings.setServer(target.server)
            val r = json.decodeFromString<LoginResult>(api.request("POST", "app/pair", buildJsonObject {
                put("code", target.code)
                put("device", deviceName)
                put("app_key", secure.appKey == null)
            }, auth = true))
            adopt(r)
        } catch (e: Exception) {
            loginError.value = friendly(e)
        } finally {
            loginBusy.value = false
        }
    }

    private suspend fun adopt(r: LoginResult) {
        r.deviceToken?.takeIf { it.startsWith("hkd_") }?.let { secure.deviceToken = it }
        r.appKey?.takeIf { it.startsWith("hk_") }?.let { if (secure.appKey == null) secure.appKey = it }
        needsLogin.value = false
        loginError.value = null
        refresh()
    }

    /** Auf diesem Geraet abmelden: Geraete-Anmeldung am Server loeschen, Sitzung + Daten lokal entfernen */
    suspend fun logout() {
        val tok = secure.deviceToken
        if (tok != null && settings.server.value.isNotEmpty()) {
            try {
                api.request("POST", "app/forget", buildJsonObject { put("device_token", tok) }, auth = true)
            } catch (_: Exception) {
            }
        }
        secure.deviceToken = null
        secure.appKey = null
        secure.pkceVerifier = null
        api.cookies.clear()
        pages.value = emptyList()
        tasks.value = emptyList()
        doneTasks.value = emptyList()
        todayEvents.value = emptyList()
        error.value = null
        settings.clearRecent()
        needsLogin.value = true
    }

    /** Bild-/Datei-Adresse (/files/...) auf dem Server */
    fun absoluteUrl(path: String): String = ServerUrl.absolute(settings.server.value, path)
}
