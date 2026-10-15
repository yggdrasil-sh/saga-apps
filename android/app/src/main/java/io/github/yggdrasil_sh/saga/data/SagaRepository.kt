package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.BuildConfig
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.auth.BrowserReturn
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.auth.PairTarget
import io.github.yggdrasil_sh.saga.auth.Pkce
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.ui.publish.PublishBackend
import io.github.yggdrasil_sh.saga.ui.comments.CommentBackend
import io.github.yggdrasil_sh.saga.ui.share.ShareBackend
import io.github.yggdrasil_sh.saga.util.ServerUrl
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import io.github.yggdrasil_sh.saga.util.Versions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** Geteilter Inhalt (Text/Link aus einer anderen App) */
data class SharedContent(val title: String, val text: String, val url: String?) {
    companion object {
        private val URL_RE = Regex("https?://[^\\s<>\"]+")

        /** Wie iOS: ist der ganze Text ein Link -> url, sonst Text */
        fun from(subject: String?, text: String?): SharedContent? {
            val t = text?.trim().orEmpty()
            val s = subject?.trim().orEmpty()
            if (t.isEmpty() && s.isEmpty()) return null
            if (URL_RE.matches(t)) return SharedContent(s, "", t)
            return SharedContent(s, t, null)
        }
    }
}

@kotlinx.serialization.Serializable
data class CaptureResult(val ok: Boolean = false, val kind: String? = null, val id: Long? = null)

/** Kurze Meldung unten (Snackbar), optional mit Aktion (z. B. "Rueckgaengig") */
class UserMessage(val text: UiText, val actionLabel: UiText? = null, val action: (() -> Unit)? = null)

/** Seitenbaum: Kinder je Eltern-ID einmal gruppiert (statt fuer jeden Knoten alle Seiten zu filtern) */
class PageTree(all: List<PageMeta>) {
    private val dbs = all.filter { it.isDatabase }.map { it.id }.toSet()
    private val byParent: Map<Long?, List<PageMeta>> = all.groupBy { it.parentId }

    /** Unterseiten (Zeilen von Datenbanken erscheinen nicht im Baum, wie im Web) */
    fun children(of: Long?): List<PageMeta> = if (of != null && of in dbs) emptyList() else byParent[of].orEmpty()
}

/**
 * Zentrale Daten der App (wie Store.swift in iOS): Seitenliste, Aufgaben, heutige Termine, Anmeldung.
 * Zustand wird auf dem Haupt-Thread geaendert; Netzwerk asynchron ueber OkHttp, JSON auf Dispatchers.Default,
 * Dateien/Keystore auf Dispatchers.IO.
 */
class SagaRepository(
    val settings: AppSettings,
    val secure: SecureStore,
    private val scope: CoroutineScope,
    private val deviceName: String,
    userAgent: String,
    val appVersion: String,
    private val filesDir: File,
    /** Sprache der App-Oberflaeche fuer Accept-Language */
    language: () -> String = { "" },
) : PageBackend, PublishBackend, ShareBackend, CommentBackend {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    val api = ApiClient(
        serverProvider = { settings.server.value },
        secure = secure,
        onSessionLost = { needsLogin.value = true },
        userAgent = userAgent,
        onAccount = { name -> scope.launch { settings.setAccount(name) } },
        language = language,
    )

    /** Einstellungen/Schluessel geladen (vorher zeigt die App nichts an) */
    val ready = MutableStateFlow(false)
    val needsLogin = MutableStateFlow(true)
    val pages = MutableStateFlow<List<PageMeta>>(emptyList())
    val tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val doneTasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val todayEvents = MutableStateFlow<List<CalEvent>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<UiText?>(null)
    val serverVersion = MutableStateFlow<String?>(null)
    val minApp = MutableStateFlow<String?>(null)
    val loginError = MutableStateFlow<UiText?>(null)
    val loginBusy = MutableStateFlow(false)

    /** Neutraler Hinweis auf dem Startbildschirm (z. B. "Anmeldung abgebrochen") - mit "Erneut versuchen" */
    val loginHint = MutableStateFlow<UiText?>(null)

    /** Browser-Anmeldung laeuft (Custom Tab offen); kommt die App ohne saga://auth zurueck, wurde abgebrochen */
    @Volatile
    private var browserLoginPending = false

    /** Zaehlt erfolgreiche Anmeldungen/Kopplungen - Ansichten laden danach neu (z. B. Geraeteliste) */
    val signInCount = MutableStateFlow(0)

    /** Kopplungslink (Deep-Link oder QR), der noch bestaetigt werden muss */
    val pendingPair = MutableStateFlow<PairTarget?>(null)
    val pairError = MutableStateFlow<UiText?>(null)

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UserMessage> = _messages

    val server: StateFlow<String> get() = settings.server
    val account: StateFlow<String> get() = settings.account
    val appTooOld: Boolean get() = minApp.value?.let { Versions.isLess(appVersion, it) } ?: false

    private val authApi = object : AuthApi {
        override suspend fun pair(server: String, code: String, device: String, wantAppKey: Boolean): LoginResult =
            decode(api.request("POST", "app/pair", buildJsonObject {
                put("code", code)
                put("device", device)
                put("app_key", wantAppKey)
            }, base = server))

        override suspend fun exchange(server: String, code: String, verifier: String, device: String, wantAppKey: Boolean): LoginResult =
            decode(api.request("POST", "app/exchange", buildJsonObject {
                put("code", code)
                put("verifier", verifier)
                put("device", device)
                put("app_key", wantAppKey)
            }, base = server))

        override suspend fun forget(server: String, deviceToken: String) {
            api.request("POST", "app/forget", buildJsonObject { put("device_token", deviceToken) }, base = server)
        }

        override suspend fun username(server: String): String? =
            decode<AccountInfo>(api.request("GET", "api/account", base = server)).username
    }

    val auth = AuthSession(settings, secure, authApi, deviceName, BuildConfig.DEBUG, clearLocal = { clearLocalData() })

    init {
        scope.launch {
            withContext(Dispatchers.IO) {
                settings.load()
                // Umstieg von 1.0: Schluessel ohne gemerkten Server gehoeren zum bisher eingestellten Server
                if (secure.deviceToken != null && secure.tokenServer == null && settings.server.value.isNotEmpty()) {
                    secure.tokenServer = settings.server.value
                }
                secure.appKey                                // Keystore einmal im Hintergrund oeffnen (Cache)
            }
            // Umstieg auf Entwuerfe je Konto-Kennung: bisherigen Ordner (Kontoname) einfrieren, bevor eine
            // Erneuerung den Namen aendert; ensureAccountKey() holt die Kennung und verschiebt die Entwuerfe
            if (auth.signedIn && settings.accountKey.value.isEmpty()) settings.setAccountKey(AccountKey.legacy(settings.account.value))
            needsLogin.value = !auth.signedIn
            ready.value = true
            refresh()                                        // erster Abgleich (auch liegengebliebene Entwuerfe speichern)
        }
    }

    suspend fun awaitReady() {
        ready.first { it }
    }

    fun notify(text: UiText, actionLabel: UiText? = null, action: (() -> Unit)? = null) {
        _messages.tryEmit(UserMessage(text, actionLabel, action))
    }

    // ---------------------------------------------------------------- Hilfen

    private suspend inline fun <reified T> decode(s: String): T = withContext(Dispatchers.Default) { json.decodeFromString<T>(s) }

    /** Fehler als Text fuer die Oberflaeche (uebersetzt; Meldungen des Servers/Systems unveraendert) */
    override fun friendly(e: Throwable): UiText = when {
        e is ApiException -> e.text
        e is UiTextException -> e.text
        isCertificateProblem(e) -> UiText.Res(R.string.error_certificate)
        e is SSLException -> UiText.Res(R.string.error_no_https)
        e is UnknownHostException || e is ConnectException || e is ServerUnreachableException -> UiText.Res(R.string.error_server_unreachable)
        e is SocketTimeoutException -> UiText.Res(R.string.error_timeout)
        e is IOException -> e.message?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: UiText.Res(R.string.error_network)
        else -> e.message?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: UiText.Res(R.string.error_generic)
    }

    private fun isCertificateProblem(e: Throwable): Boolean {
        var c: Throwable? = e
        var n = 0
        while (c != null && n++ < 8) {
            if (c is CertificateException || c is CertPathValidatorException || c is SSLPeerUnverifiedException) return true
            c = c.cause
        }
        return false
    }

    private fun today(): String = LocalDate.now().toString()

    // ---------------------------------------------------------------- Laden

    private val refreshLock = Mutex()

    suspend fun refresh() {
        if (!ready.value || needsLogin.value) return
        if (!refreshLock.tryLock()) return                   // laeuft schon (Start, Timer, Ziehen) - nicht doppelt laden
        loading.value = true
        try {
            setPages(decode<List<PageMeta>>(api.get("api/notes")))
            error.value = null
            loadShared()
            if (serverVersion.value == null) checkVersion()
            loadTasks()
            loadTodayEvents()
            ensureAccountKey()
            syncDrafts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!needsLogin.value) error.value = friendly(e)
        } finally {
            loading.value = false
            refreshLock.unlock()
        }
    }

    private fun setPages(all: List<PageMeta>) {
        pages.value = all.filter { !it.archived }
        ownIds = all.map { it.id }.toSet()
    }

    /** IDs aller eigenen Seiten (auch archivierte; GET api/notes) - null = noch nicht geladen */
    @Volatile private var ownIds: Set<Long>? = null

    /** Server-Version (oeffentlich, ohne Anmeldung); aeltere Server ohne /api/version: nichts anzeigen */
    suspend fun checkVersion() {
        try {
            val v = decode<VersionInfo>(api.request("GET", "api/version", auth = true))
            serverVersion.value = v.version
            minApp.value = v.minApp
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            serverVersion.value = null
            minApp.value = null
        }
    }

    fun page(id: Long): PageMeta? = pages.value.firstOrNull { it.id == id }

    suspend fun search(q: String): List<PageMeta> {
        if (q.trim().length < 2) return emptyList()
        return decode(api.post("api/notes/search", buildJsonObject { put("q", q.trim()) }))
    }

    suspend fun newPage(parent: Long? = null, title: String = ""): Long {
        val b = buildJsonObject {
            put("title", title)
            if (parent != null) put("parent_id", parent)
        }
        val id = decode<NewId>(api.post("api/notes", b)).id
        refresh()
        return id
    }

    override suspend fun setPinned(id: Long, pinned: Boolean) {
        api.patch("api/notes/$id", buildJsonObject { put("pinned", pinned) })
        pages.value = pages.value.map { if (it.id == id) it.copy(pinned = pinned) else it }
    }

    override suspend fun setLocked(id: Long, locked: Boolean) {
        api.patch("api/notes/$id", buildJsonObject { put("locked", locked) })
        pages.value = pages.value.map { if (it.id == id) it.copy(locked = locked) else it }
    }

    /** Seite (samt Unterseiten) in den Papierkorb; Rueckgaengig ueber den Papierkorb */
    suspend fun trashPage(id: Long) {
        api.delete("api/notes/$id")
        pages.value = pages.value.filter { it.id != id }
        refresh()
    }

    suspend fun restorePage(id: Long) {
        api.post("api/trash/$id/restore")
        refresh()
    }

    suspend fun archivePage(id: Long, archived: Boolean) {
        api.post("api/notes/$id/archive", buildJsonObject { put("archived", archived) })
        if (archived) pages.value = pages.value.filter { it.id != id }
        refresh()
    }

    override fun updateTitleLocal(id: Long, title: String) {
        pages.value = pages.value.map { if (it.id == id && it.title != title) it.copy(title = title) else it }
    }

    override fun visited(id: Long) {
        scope.launch { settings.visited(id) }
    }

    // ---------------------------------------------------------------- Seite laden/speichern

    private val pageLocks = ConcurrentHashMap<Long, Mutex>()

    /** Laden und Speichern derselben Seite nacheinander (nie alten Stand laden, waehrend gespeichert wird) */
    override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = pageLocks.getOrPut(id) { Mutex() }.withLock { block() }

    override suspend fun loadPage(id: Long): JsonObject {
        val text = api.get("api/notes/$id")
        return withContext(Dispatchers.Default) { json.decodeFromString(JsonObject.serializer(), text) }
    }

    /** Titel + Inhalt mit Revision speichern -> neue Revision. 409 = woanders geaendert, 423 = gesperrt */
    override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? = savePage(id, title, body, rev, false)

    /** overwrite: "Meine Version behalten" nach einem Konflikt - der Server sichert die ueberschriebene Fassung als Version */
    override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?, overwrite: Boolean): Int? {
        val b = buildJsonObject {
            put("title", title)
            if (body != null) {
                put("body", body)
                put("format", "blocks")
            }
            if (rev != null) put("rev", rev)
            if (overwrite) put("overwrite", true)
        }
        return decode<SaveResult>(api.patch("api/notes/$id", b)).rev
    }

    // ---------------------------------------------------------------- Teilen und Kommentare (Server 1.3)

    /** Mit mir geteilt: fremde Seiten (oberste mit root=true, darunter ihre Unterseiten) */
    val sharedPages = MutableStateFlow<List<PageMeta>>(emptyList())

    /** Server kennt Teilen (GET /api/shared; 404 = aelterer Server -> alles Neue ausblenden) */
    val shareSupported = MutableStateFlow(false)

    /** Teilen abgeschaltet, aber es gibt Freigaben an mich -> Hinweis in der Seitenleiste */
    val shareOff = MutableStateFlow(false)

    /** Teilen-Dialog nach dem Oeffnen dieser Seite zeigen ("über „Elternseite“" im Dialog) */
    val openShareFor = MutableStateFlow<Long?>(null)

    /** "Zu „Mit mir geteilt“" (Seite ohne Zugriff): Zaehler - Seitenleiste oeffnen und zum Bereich scrollen */
    val showSharedRequest = MutableStateFlow(0)

    suspend fun loadShared() {
        try {
            val list = decode<List<PageMeta>>(api.get("api/shared"))
            shareSupported.value = true
            sharedPages.value = list.filter { !it.archived }
            if (list.isEmpty()) {
                val info = decode<ShareInfo>(api.get("api/share/info"))
                shareOff.value = !info.enabled && info.received > 0
            } else {
                shareOff.value = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404) {
                shareSupported.value = false
                sharedPages.value = emptyList()
                shareOff.value = false
            }
        } catch (_: Exception) {
            // offline: bisherigen Stand behalten
        }
    }

    /** Eigene Seite ist (nicht mehr) freigegeben -> Personen-Symbol im Baum */
    fun markShared(id: Long, shared: Boolean) {
        pages.value = pages.value.map { if (it.id == id && it.shared != shared) it.copy(shared = shared) else it }
    }

    /** Kommentare gelesen/geschrieben -> Punkt im Baum nachziehen */
    fun markUnread(id: Long, unread: Boolean) {
        pages.value = pages.value.map { if (it.id == id && it.unread != unread) it.copy(unread = unread) else it }
        sharedPages.value = sharedPages.value.map { if (it.id == id && it.unread != unread) it.copy(unread = unread) else it }
    }

    private fun q(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    override suspend fun shares(id: Long): ShareState = decode(api.get("api/notes/$id/shares"))

    override suspend fun putShare(id: Long, type: String, target: String, role: String): ShareState =
        decode(api.request("PUT", "api/notes/$id/shares", buildJsonObject {
            put("type", type)
            put("target", target)
            put("role", role)
        }))

    override suspend fun deleteShare(id: Long, type: String, target: String): ShareState =
        decode(api.delete("api/notes/$id/shares?type=${q(type)}&target=${q(target)}"))

    override suspend fun shareTargets(query: String): ShareTargets = decode(api.get("api/share/targets?q=${q(query)}"))

    override fun shareChanged(id: Long, shared: Boolean) = markShared(id, shared)

    override suspend fun comments(id: Long, resolved: Boolean): CommentsResponse =
        decode(api.get("api/notes/$id/comments" + if (resolved) "?resolved=1" else ""))

    override suspend fun addComment(id: Long, blockId: String?, threadId: Long?, body: String): CommentPosted =
        decode(api.post("api/notes/$id/comments", buildJsonObject {
            if (threadId != null) put("thread_id", threadId) else put("block_id", blockId.orEmpty())
            put("body", body)
        }))

    override suspend fun editComment(cid: Long, body: String) {
        api.patch("api/comments/$cid", buildJsonObject { put("body", body) })
    }

    override suspend fun deleteComment(cid: Long, all: Boolean) {
        api.delete("api/comments/$cid" + if (all) "?all=1" else "")
    }

    override suspend fun resolveComment(cid: Long, resolved: Boolean) {
        api.post("api/comments/$cid/resolve", buildJsonObject { put("resolved", resolved) })
    }

    override suspend fun commentSeen(cid: Long) {
        api.post("api/comments/$cid/seen")
    }

    override fun unreadChanged(id: Long, unread: Boolean) = markUnread(id, unread)

    // ---------------------------------------------------------------- Veroeffentlichen (Server 1.2)

    override suspend fun publishState(id: Long): PublishState = decode(api.get("api/notes/$id/publish"))

    /** PUT nur mit Content-Type application/json (sonst 415) - request() schickt JSON */
    override suspend fun publish(id: Long, body: JsonObject): PublishState = decode(api.request("PUT", "api/notes/$id/publish", body))

    override suspend fun unpublish(id: Long): PublishState = decode(api.delete("api/notes/$id/publish"))

    override suspend fun publicName(): PublicNameInfo = decode(api.get("api/account/public-name"))

    override suspend fun checkPublicName(name: String): NameCheck =
        decode(api.request("PUT", "api/account/public-name", buildJsonObject { put("name", name); put("check", true) }))

    override suspend fun setPublicName(name: String): NameSaved =
        decode(api.request("PUT", "api/account/public-name", buildJsonObject { put("name", name) }))

    override suspend fun createPageWith(title: String, body: String): Long {
        val id = decode<NewId>(api.post("api/notes", buildJsonObject { put("title", title) })).id
        savePage(id, title, body, null)
        refresh()
        return id
    }

    override val currentServer: String get() = settings.server.value
    override val signedOut: Boolean get() = needsLogin.value
    override val currentAccountKey: String get() = settings.accountKey.value

    private val draftsLock = Any()
    private var draftsHandle: AccountDrafts? = null

    /**
     * Entwuerfe des angemeldeten Kontos (Server + stabile Konto-Kennung). Dasselbe Objekt, solange Server und Konto
     * gleich bleiben - auch ueber die Migration auf die Kennung hinweg (offene Editoren schreiben dann mit um).
     */
    override fun draftsForCurrent(): Drafts = currentDrafts()

    private fun currentDrafts(): AccountDrafts = synchronized(draftsLock) {
        val dir = FileDrafts.dirFor(filesDir, settings.server.value, settings.accountKey.value, settings.account.value)
        draftsHandle?.takeIf { it.covers(dir) } ?: AccountDrafts(dir).also { draftsHandle = it }
    }

    /** server|key, fuer den /api/account in diesem Prozess schon erfolglos gefragt wurde (aelterer Server) */
    @Volatile
    private var accountKeyTried = ""

    /**
     * Stabile Konto-Kennung (Benutzername) fuer die Entwuerfe sicherstellen. Solange sie fehlt (Update, aelterer
     * Server), liegen die Entwuerfe unter einem eingefrorenen Namen; sobald sie bekannt ist, werden sie samt allen
     * alten Ordnern dieses Kontos in den Ordner der Kennung verschoben - nichts bleibt verwaist.
     */
    suspend fun ensureAccountKey() {
        if (needsLogin.value) return
        val server = settings.server.value
        val key = settings.accountKey.value.ifEmpty { AccountKey.legacy(settings.account.value).also { settings.setAccountKey(it) } }
        if (AccountKey.isStable(key) || accountKeyTried == "$server|$key") return
        val info = try {
            decode<AccountInfo>(api.get("api/account"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code != 401) accountKeyTried = "$server|$key"     // Server kennt /api/account nicht: nicht bei jedem Abgleich fragen
            return
        } catch (_: Exception) {
            return                                                  // offline: beim naechsten Abgleich
        }
        val user = info.username?.takeIf { it.isNotBlank() }
        if (user == null) {
            accountKeyTried = "$server|$key"
            return
        }
        if (settings.server.value != server || settings.accountKey.value != key || needsLogin.value) return   // inzwischen gewechselt
        val target = AccountKey.uid(user)
        val names = AccountKey.migrationNames(key, listOf(settings.account.value, info.name, user))
        val handle = currentDrafts()
        try {
            withContext(Dispatchers.IO) {
                handle.moveTo(FileDrafts.dirFor(filesDir, server, target, ""), names.map { FileDrafts.legacyDir(filesDir, server, it) })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return                                                  // Platte voll o. ae.: beim naechsten Abgleich erneut
        }
        settings.setAccountKey(target)
    }

    /** Nach einer Anmeldung mit bekannter Kennung: Entwuerfe aus dem alten Ordner (Kontoname) dieses Kontos uebernehmen */
    private suspend fun adoptNamedDrafts() {
        if (!AccountKey.isStable(settings.accountKey.value)) return
        val old = FileDrafts.legacyDir(filesDir, settings.server.value, settings.account.value)
        val handle = currentDrafts()
        try {
            withContext(Dispatchers.IO) { if (old.isDirectory) handle.moveTo(handle.dir, listOf(old)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private val editors = LinkedHashSet<PageEditor>()

    override fun register(editor: PageEditor) {
        editors.add(editor)
    }

    override fun unregister(editor: PageEditor) {
        editors.remove(editor)
    }

    /** Alle offenen Seiten speichern und warten (vor Serverwechsel/Abmelden) - hoechstens timeoutMs */
    suspend fun flushEditors(timeoutMs: Long = 8_000) {
        withTimeoutOrNull(timeoutMs) {
            for (e in editors.toList()) e.saveAndWait()
        }
    }

    /** Anzahl ungespeicherter Entwuerfe des aktuellen Kontos (bleiben bei einem Wechsel auf dem Geraet) */
    suspend fun unsavedDraftCount(): Int = withContext(Dispatchers.IO) { draftsForCurrent().all().size }

    private val syncLock = Mutex()

    /** Entwuerfe nicht geoeffneter Seiten nachtraeglich speichern (z. B. nach Prozess-Tod oder offline) */
    suspend fun syncDrafts() {
        if (needsLogin.value || !syncLock.tryLock()) return
        try {
            val store = draftsForCurrent()
            val open = editors.map { it.id }.toSet()
            val list = withContext(Dispatchers.IO) { store.all() }
            val own = ownIds
            for (d in list) {
                if (d.id in open) continue
                // Entwuerfe fremder (geteilter) Seiten nie still im Hintergrund hochladen: die Freigabe kann inzwischen
                // entzogen oder auf Lesen gesetzt sein (und spaeter wiederkommen). Beim Oeffnen entscheidet der Nutzer.
                if (!DraftSync.mayUpload(d.id, own)) continue
                try {
                    withPageLock(d.id) { savePage(d.id, d.title, d.body, d.rev) }
                    withContext(Dispatchers.IO) { if (store.read(d.id)?.stamp == d.stamp) store.delete(d.id) }
                    updateTitleLocal(d.id, d.title)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    // 409: schon gespeichert (Prozess vor dem Loeschen des Entwurfs beendet)? Dann ist er erledigt.
                    // Sonst bleibt er; beim Oeffnen der Seite entscheidet der Nutzer.
                    if (e.code == 409) dropIfSaved(store, d)
                } catch (_: Exception) {
                    // offline: Entwurf bleibt
                }
            }
        } finally {
            syncLock.unlock()
        }
    }

    /** Entwurf entfernen, wenn der Server inhaltlich schon genau diesen Stand hat (nur die Revision ist neuer) */
    private suspend fun dropIfSaved(store: Drafts, d: Draft) {
        try {
            val p = withPageLock(d.id) { loadPage(d.id) }
            val same = withContext(Dispatchers.Default) { d.matchesPage(p) }
            if (same) withContext(Dispatchers.IO) { if (store.read(d.id)?.stamp == d.stamp) store.delete(d.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- Aufgaben

    suspend fun loadTasks() {
        try {
            val t = decode<TasksResponse>(api.get("api/tasks"))
            tasks.value = t.open
            doneTasks.value = t.done
        } catch (e: CancellationException) {
            throw e
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
        else doneTasks.value = doneTasks.value.filter { it.id != t.id }
        try {
            api.patch("api/tasks/${t.id}", buildJsonObject {
                put("done", done)
                put("today", today())
            })
        } finally {
            loadTasks()
        }
    }

    /**
     * Haken setzen/entfernen - laeuft im App-Scope, NICHT im Scope der Zeile: die Zeile verschwindet sofort aus
     * "Heute", und ihr Scope wuerde den Aufruf abbrechen (Datenverlust, Review P1). Mit "Rueckgaengig".
     */
    fun toggleTask(t: TaskItem, done: Boolean) {
        scope.launch {
            try {
                setTaskDone(t, done)
                if (done) notify(UiText.Res(R.string.task_done_message), UiText.Res(R.string.action_undo)) { toggleTask(t, false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(UiText.Res(R.string.task_not_saved, friendly(e)))
            }
        }
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
        try {
            api.delete("api/tasks/$id")
        } finally {
            loadTasks()
        }
    }

    /** Im App-Scope ausfuehren (ueberlebt das Schliessen von Dialog/Zeile); Fehler als Snackbar */
    fun launchAction(onError: (Throwable) -> Unit = {}, onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                notify(friendly(e))
            }
        }
    }

    // ---------------------------------------------------------------- Teilen

    /** Von einer anderen App geteilt (ACTION_SEND), wartet auf "Speichern als Seite/Aufgabe" */
    val pendingShare = MutableStateFlow<SharedContent?>(null)

    /** Seite oeffnen (aus Meldungen ausserhalb der Navigation, z. B. "Geteilt - Oeffnen") */
    val openPageRequest = MutableStateFlow<Long?>(null)

    /**
     * Geteiltes ablegen wie die iOS-Teilen-Erweiterung: POST api/app/capture mit dem App-Schluessel
     * (Seite landet im Eingang, Aufgabe in den Aufgaben). Ohne App-Schluessel ueber die normale Sitzung.
     * -> ID der neuen Seite bzw. null bei Aufgabe
     */
    suspend fun capture(kind: String, title: String, text: String, url: String?): Long? {
        val key = withContext(Dispatchers.IO) { secure.appKeyFor(settings.server.value) }
        if (key != null) {
            val r = api.request("POST", "api/app/capture", buildJsonObject {
                put("kind", kind)
                put("title", title)
                put("text", if (kind == "page") text else "")
                if (!url.isNullOrEmpty()) put("url", url)
            }, bearer = key)
            val id = decode<CaptureResult>(r).id
            if (kind == "task") loadTasks() else refresh()
            return if (kind == "page") id else null
        }
        if (kind == "task") {
            addTask(listOf(title.ifBlank { text.lineSequence().firstOrNull().orEmpty() }, url.orEmpty()).filter { it.isNotBlank() }.joinToString(" "), null)
            return null
        }
        val blocks = buildList {
            if (!url.isNullOrEmpty()) add(BlockDoc.newBlock("paragraph", "🔗 <a href=\"${InlineHtml.escapeAttr(url)}\">${InlineHtml.escape(url)}</a>"))
            text.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }.take(200).forEach {
                add(BlockDoc.newBlock("paragraph", InlineHtml.escape(it.trim()).replace("\n", "<br>")))
            }
        }
        val body = BlockDoc.serialize(JsonObject(mapOf("blocks" to JsonArray(blocks))))
        return createPageWith(title.ifBlank { (url ?: text).take(80) }, body)
    }

    // ---------------------------------------------------------------- Termine

    /** Termine ab heute fuer days Tage; null = Fehler (dann bisherigen Stand behalten) */
    suspend fun events(days: Int, fresh: Boolean = false): List<CalEvent>? = try {
        val t0 = LocalDate.now()
        val t1 = t0.plusDays(days.toLong())
        decode<EventsResponse>(api.get("api/calendar/events?start=$t0&end=$t1" + if (fresh) "&fresh=1" else "")).events
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    suspend fun loadTodayEvents() {
        events(1)?.let { todayEvents.value = it }
    }

    // ---------------------------------------------------------------- Dienste, Geraete

    suspend fun services(): List<ServiceItem> = decode(api.get("api/services"))

    suspend fun apps(): AppsResponse = decode(api.get("api/settings/apps"))

    suspend fun deleteDevice(id: Int) {
        api.delete("api/settings/devices/$id")
    }

    // ---------------------------------------------------------------- Anmeldung

    /**
     * Schritt 1 der Browser-Anmeldung: Adresse pruefen (ohne Schema -> https), Server testen, PKCE erzeugen
     * -> Adresse fuer den Custom Tab. Der Server wird erst nach erfolgreicher Anmeldung uebernommen.
     */
    suspend fun prepareBrowserLogin(serverInput: String): String {
        val server = ServerUrl.normalize(serverInput, BuildConfig.DEBUG)
            ?: throw UiTextException(UiText.Res(R.string.login_https_required))
        probe(server)
        val p = Pkce.create()
        withContext(Dispatchers.IO) {
            secure.pkceVerifier = p.verifier
            secure.pkceServer = server
        }
        return "$server/app/login?challenge=${p.challenge}&scheme=saga"
    }

    /** Laeuft dort ein Saga-Server? Klare Meldung statt Browser-Fehler (kein https, Zertifikat, falsche Adresse) */
    private suspend fun probe(server: String) {
        val text = try {
            api.request("GET", "api/version", base = server)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw UiTextException(UiText.Res(R.string.login_not_saga))
        } catch (e: Exception) {
            throw UiTextException(UiText.Res(R.string.login_probe_failed, friendly(e)))
        }
        val v = try {
            decode<VersionInfo>(text)
        } catch (_: Exception) {
            null
        }
        if (v?.version == null) throw UiTextException(UiText.Res(R.string.login_not_saga))
    }

    /** Der Browser fuer die Anmeldung wird gleich geoeffnet (Rueckkehr ohne saga://auth = abgebrochen) */
    fun browserLoginStarted() {
        loginHint.value = null
        loginError.value = null
        browserLoginPending = true
    }

    /** App wieder im Vordergrund (onResume, nach einem evtl. Deep-Link): Browser-Tab ohne Ergebnis geschlossen? */
    fun onAppResumed() {
        if (!browserLoginPending) return
        browserLoginPending = false
        if (needsLogin.value && !loginBusy.value) loginHint.value = UiText.Res(R.string.login_cancelled)
    }

    /** Rueckkehr aus dem Browser: saga://auth?code=... bzw. ?error=cancelled (Nachtest P7) */
    fun onBrowserReturn(r: BrowserReturn) {
        browserLoginPending = false
        loginHint.value = null
        when (r) {
            is BrowserReturn.Code -> scope.launch { completeBrowserLogin(r.code) }
            BrowserReturn.Cancelled -> loginHint.value = UiText.Res(R.string.login_cancelled)
            BrowserReturn.NoCode -> loginHint.value = UiText.Res(R.string.login_no_code)
            is BrowserReturn.Failed -> loginHint.value = UiText.Res(R.string.login_failed_reason, r.error)
        }
    }

    /** Schritt 2: saga://auth?code=... -> Code + Geheimnis gegen Sitzung und Geraete-Schluessel tauschen */
    suspend fun completeBrowserLogin(code: String) {
        awaitReady()
        val (verifier, server) = withContext(Dispatchers.IO) { secure.pkceVerifier to secure.pkceServer }
        if (verifier == null || server.isNullOrEmpty()) {
            loginError.value = UiText.Res(R.string.login_restart)
            return
        }
        loginBusy.value = true
        loginError.value = null
        loginHint.value = null
        try {
            flushEditors()
            auth.exchange(server, code, verifier)
            onSignedIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loginError.value = if (e is ApiException && e.code == 401) UiText.Res(R.string.login_code_invalid) else friendly(e)
        } finally {
            withContext(Dispatchers.IO) {
                secure.pkceVerifier = null
                secure.pkceServer = null
            }
            loginBusy.value = false
        }
    }

    /** Kopplungslink angekommen (Deep-Link/QR): erst pruefen, dann bestaetigen lassen - nie still koppeln */
    fun offerPair(target: PairTarget) {
        val t = auth.check(target)
        if (t == null) {
            val msg = UiText.Res(R.string.login_server_https)
            if (needsLogin.value) loginError.value = msg else notify(msg)
            return
        }
        pairError.value = null
        pendingPair.value = t
    }

    fun cancelPair() {
        pendingPair.value = null
        pairError.value = null
    }

    /** Bestaetigte Kopplung: offene Seiten speichern, koppeln, erst bei Erfolg wechseln */
    suspend fun confirmPair() {
        val t = pendingPair.value ?: return
        loginBusy.value = true
        pairError.value = null
        loginError.value = null
        try {
            flushEditors()
            auth.pair(t)
            pendingPair.value = null
            // Rueckmeldung (Nachtest N2): mit welchem Konto jetzt gekoppelt
            val name = settings.account.value.trim()
            onSignedIn(
                if (name.isNotEmpty()) UiText.Res(R.string.pair_success, name)
                else UiText.Res(R.string.pair_success_host, PairLink.displayHost(t.server))
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pairError.value = UiText.Res(R.string.pair_failed, friendly(e))
        } finally {
            loginBusy.value = false
        }
    }

    private suspend fun onSignedIn(message: UiText? = null) {
        adoptNamedDrafts()
        serverVersion.value = null
        minApp.value = null
        needsLogin.value = false
        loginError.value = null
        loginHint.value = null
        signInCount.value++                                  // Geraeteliste, Konto usw. neu laden
        message?.let { notify(it) }
        refresh()
    }

    /** Lokale Daten des bisherigen Kontos leeren (Entwuerfe bleiben je Server/Konto auf dem Geraet) */
    private suspend fun clearLocalData() {
        api.cookies.clear()
        pages.value = emptyList()
        ownIds = null
        sharedPages.value = emptyList()
        shareSupported.value = false
        shareOff.value = false
        tasks.value = emptyList()
        doneTasks.value = emptyList()
        todayEvents.value = emptyList()
        error.value = null
        serverVersion.value = null
        minApp.value = null
    }

    /** Auf diesem Geraet abmelden: Geraete-Anmeldung am Server loeschen, Sitzung + Daten lokal entfernen */
    suspend fun logout() {
        flushEditors()
        auth.signOutLocal(forgetRemote = true)
        needsLogin.value = true
    }

    /** Bild-/Datei-Adresse (/files/...) auf dem Server */
    fun absoluteUrl(path: String): String = ServerUrl.absolute(settings.server.value, path)
}
