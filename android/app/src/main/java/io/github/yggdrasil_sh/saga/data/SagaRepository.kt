package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.BuildConfig
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.auth.BrowserReturn
import io.github.yggdrasil_sh.saga.auth.LoginFlow
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.auth.PairTarget
import io.github.yggdrasil_sh.saga.auth.Pkce
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.ui.publish.PublishBackend
import io.github.yggdrasil_sh.saga.ui.comments.CommentBackend
import io.github.yggdrasil_sh.saga.ui.share.ShareBackend
import io.github.yggdrasil_sh.saga.util.LocalNetwork
import io.github.yggdrasil_sh.saga.util.ServerUrl
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import io.github.yggdrasil_sh.saga.util.Versions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

/** GET /api/fed/shared */
@kotlinx.serialization.Serializable
data class FedShared(val items: List<PageMeta> = emptyList(), val unreachable: List<String> = emptyList())

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
    /** Name dieses Geraets (Apps & Geraete, Chat-Geraete, Mitteilungen) */
    val deviceName: String,
    userAgent: String,
    val appVersion: String,
    private val filesDir: File,
    /** Sprache der App-Oberflaeche fuer Accept-Language */
    private val language: () -> String = { "" },
    /** App-Kontext (Mitteilungen: UnifiedPush-Verteiler, Benachrichtigungen des Systems) */
    private val appContext: android.content.Context? = null,
) : PageBackend, PublishBackend, ShareBackend, CommentBackend {
    /** Cache-Ordner (Chat-Anhaenge als Ciphertext); ohne App-Kontext (Tests) unter filesDir */
    val chatCacheDir: File get() = appContext?.cacheDir ?: File(filesDir, "cache")

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
    /** abgewiesener Kopplungslink: Grund als Dialog (nie still ignorieren, TT 2.4 P-07) */
    val pairRejected = MutableStateFlow<UiText?>(null)

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

    /** Live-Kanal und Benachrichtigungen (Server 1.4) */
    val live = io.github.yggdrasil_sh.saga.live.LiveCenter(this, scope)

    /** Chats mit Ende-zu-Ende-Verschluesselung (Server 1.5) */
    val chat = io.github.yggdrasil_sh.saga.chat.ChatService(this, scope)

    /** Mitteilungen aufs Geraet (Server 1.7, UnifiedPush) */
    val push: io.github.yggdrasil_sh.saga.push.PushService by lazy {
        io.github.yggdrasil_sh.saga.push.PushService(this, requireNotNull(appContext), scope)
    }

    /** Anrufe 1:1 (Server 2.1, WebRTC) - braucht den App-Kontext; SagaApplication legt es beim Start an */
    val calls: io.github.yggdrasil_sh.saga.call.CallManager by lazy {
        io.github.yggdrasil_sh.saga.call.CallManager(this, requireNotNull(appContext), scope)
    }

    /** KI-Assistent (Server 1.6) */
    val ai = io.github.yggdrasil_sh.saga.ai.AiService(this, scope)

    @Volatile private var pushChecked = false

    /** Chat oeffnen (aus einer Mitteilung) */
    val openChatRequest = MutableStateFlow<Long?>(null)

    /** 2.3: Einstellungs-Bereich oeffnen ("backup") bzw. Link im Browser (Admin-Bereich) - aus Mitteilung/Benachrichtigung */
    val openSettingsRequest = MutableStateFlow<String?>(null)
    val openExternalRequest = MutableStateFlow<String?>(null)

    /** Link aus einer Mitteilung/Benachrichtigung ("/notes#12:block", "/chats#c5") oeffnen und als gelesen markieren */
    fun openUrl(url: String) {
        when (val t = io.github.yggdrasil_sh.saga.live.NotifLogic.target(url)) {
            is io.github.yggdrasil_sh.saga.live.NotifLogic.Target.Page -> {
                if (t.block != null) openCommentsFor.value = t.id to t.block
                openPageRequest.value = t.id
            }
            is io.github.yggdrasil_sh.saga.live.NotifLogic.Target.Chat -> openChatRequest.value = t.id
            is io.github.yggdrasil_sh.saga.live.NotifLogic.Target.Settings -> openSettingsRequest.value = t.section
            null -> {}
        }
        scope.launch {
            awaitReady()
            if (!needsLogin.value) live.markReadForUrl(url)
        }
    }

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
            if (auth.signedIn) restoreTreeCache()            // Seitenbaum aus dem Zwischenspeicher (gleicher Server/Konto)
            if (auth.signedIn) checkLocalNetwork()           // Android 17: Server im Heimnetz -> vorher fragen
            ready.value = true
            live.restart()
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

    /**
     * Fehler als Text fuer die Oberflaeche (uebersetzt; Meldungen des Servers/Systems unveraendert). Android 17 sperrt
     * den Server im lokalen Netz (Berechtigung fehlt): Netzwerkfehler dann als genau diesen Hinweis statt "Zeitueberschreitung".
     */
    /** 2.3: eigenes Konto laut GET /api/settings/nextcloud (me) - Autor eigener Nextcloud-Bloecke */
    @Volatile private var ncMeValue: Pair<String, String>? = null      // (Konto-Schluessel, me)
    var ncMeCache: String
        get() = ncMeValue?.takeIf { it.first == settings.accountKey.value + "@" + settings.server.value }?.second.orEmpty()
        set(v) { ncMeValue = (settings.accountKey.value + "@" + settings.server.value) to v }

    override suspend fun ncMe(): String = ncMeCache.ifEmpty {
        try {
            ncSettings()?.me.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }

    override fun friendly(e: Throwable): UiText =
        if (localBlocked && isNetworkFailure(e)) UiText.Res(R.string.error_local_network) else friendlyText(e)

    // ---------------------------------------------------------------- Lokales Netz (Android 17)

    /** Server liegt im lokalen Netz und die Berechtigung fehlt: Host fuer die Rueckfrage (null = nichts zu tun) */
    val localNetworkNeeded = MutableStateFlow<String?>(null)

    /** zuletzt geprueft: Server im lokalen Netz, Berechtigung fehlt */
    @Volatile private var localBlocked = false

    /** Rueckfrage in dieser Sitzung abgelehnt ("Spaeter") - nicht bei jedem Neuladen wieder fragen */
    @Volatile private var localNetworkDeclined = false

    /** Anmelden wartet auf die Antwort der Rueckfrage (statt sofort "kein Zugriff" zu melden) */
    private val localGate = io.github.yggdrasil_sh.saga.util.LocalNetworkGate()

    /** Chat geloescht/geleert (2.1): seine System-Mitteilungen (Tag "chat-<id>") und geoeffnete Anhaenge entfernen */
    fun dropChatLocal(chatId: Long) {
        val ctx = appContext ?: return
        try {
            androidx.core.app.NotificationManagerCompat.from(ctx).cancel(io.github.yggdrasil_sh.saga.chat.ChatDeleteLogic.notificationTag(chatId), 1)
        } catch (_: Exception) {
        }
        // entschluesselte Kopien zum Ansehen/Teilen (nur kurz gueltig) - keinem Chat zugeordnet, daher alle
        io.github.yggdrasil_sh.saga.chat.ChatFiles.purge(ctx.cacheDir)
    }

    /**
     * Braucht der Server (Standard: der angemeldete) die Berechtigung "lokales Netzwerk"? true = ja und sie fehlt;
     * dann erscheint die Rueckfrage (force: auch nach "Spaeter", z. B. beim ausdruecklichen Anmelden).
     */
    suspend fun checkLocalNetwork(server: String? = settings.server.value, force: Boolean = false): Boolean {
        val ctx = appContext ?: return false
        if (server.isNullOrEmpty() || LocalNetwork.granted(ctx)) {
            localBlocked = false
            return false
        }
        val local = withContext(Dispatchers.IO) { LocalNetwork.isLocalServer(server) }
        localBlocked = local
        if (local && (force || !localNetworkDeclined)) {
            localGate.ask()
            localNetworkNeeded.value = LocalNetwork.hostOf(server) ?: server
        }
        return local
    }

    /** Antwort auf die Rueckfrage: erlaubt -> sofort neu laden; abgelehnt/spaeter -> in dieser Sitzung nicht mehr fragen */
    fun localNetworkAnswered(granted: Boolean) {
        localNetworkNeeded.value = null
        localGate.answer(granted)
        if (granted) {
            localBlocked = false
            if (!needsLogin.value) scope.launch {
                refresh(join = false)
                live.restart()
            }
        } else {
            localNetworkDeclined = true
        }
    }

    fun isNetworkFailure(e: Throwable): Boolean =
        e !is ApiException && e !is UiTextException && (e is IOException || e.cause is IOException)

    companion object {
        /**
         * Fehlertext fuer die Oberflaeche. Sonstige Netzfehler (OkHttp: "unexpected end of stream on http://...")
         * nie als rohen englischen Text mit interner Adresse, sondern "Netzwerkfehler" (BUG-071).
         */
        fun friendlyText(e: Throwable): UiText = when {
            e is ApiException -> e.text
            e is UiTextException -> e.text
            isCertificateProblem(e) -> UiText.Res(R.string.error_certificate)
            e is SSLException -> UiText.Res(R.string.error_no_https)
            e is UnknownHostException || e is ConnectException || e is ServerUnreachableException -> UiText.Res(R.string.error_server_unreachable)
            e is SocketTimeoutException -> UiText.Res(R.string.error_timeout)
            e is IOException -> UiText.Res(R.string.error_network)
            else -> e.message?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: UiText.Res(R.string.error_generic)
        }

        /** Fehler beim Pruefen der Server-Adresse, dessen Text schon "Server nicht erreichbar" ist (ohne Vorsatz zeigen) */
        fun probeSelfExplaining(e: Throwable): Boolean = e is UnknownHostException || e is ConnectException || e is ServerUnreachableException

        private fun isCertificateProblem(e: Throwable): Boolean {
        var c: Throwable? = e
        var n = 0
        while (c != null && n++ < 8) {
            if (c is CertificateException || c is CertPathValidatorException || c is SSLPeerUnverifiedException) return true
            c = c.cause
        }
        return false
    }

        /**
         * Inhalt fuer POST api/app/capture. Text immer mitsenden: bei einer Aufgabe ohne Titel nimmt der Server die
         * erste Zeile (vorher leer -> "nothing to save", BUG-024).
         */
        fun captureBody(kind: String, title: String, text: String, url: String?): JsonObject = buildJsonObject {
            put("kind", kind)
            put("title", title)
            put("text", text)
            if (!url.isNullOrEmpty()) put("url", url)
        }
    }

    private fun today(): String = LocalDate.now().toString()

    // ---------------------------------------------------------------- Laden

    private val refreshLock = Mutex()

    /** Abgeschlossene Abgleiche (Zaehler) - wer waehrend eines laufenden Abgleichs fragt, nutzt dessen Ergebnis mit */
    @Volatile private var refreshDone = 0L

    /** Ende des letzten Abgleichs (monotone Uhr, ms; 0 = noch keiner) - Grundlage fuer den Takt (RefreshPolicy) */
    @Volatile var lastRefreshMs = 0L
        private set

    private fun sinceLastRefresh(): Long = if (lastRefreshMs == 0L) Long.MAX_VALUE else RefreshPolicy.now() - lastRefreshMs

    /**
     * Seitenliste und Zubehoer neu laden. Laeuft schon ein Abgleich (Start, Takt, Ziehen, Live "reset"), wartet der
     * Aufruf auf dessen Ende und uebernimmt das Ergebnis, statt die (grosse) Liste ein zweites Mal zu laden.
     */
    suspend fun refresh(join: Boolean = true) {
        if (!ready.value || needsLogin.value) return
        val seen = refreshDone
        refreshLock.withLock {
            if (join && refreshDone != seen) return          // waehrend des Wartens fertig geworden: mitbenutzen
            if (!ready.value || needsLogin.value) return
            try {
                refreshNow()
            } finally {
                refreshDone++
                lastRefreshMs = RefreshPolicy.now()
            }
        }
    }

    /** Ende des letzten kleinen Abgleichs (geteilt, Glocke, Chats, Heute; monotone Uhr, ms) */
    @Volatile private var lastLightMs = 0L

    /**
     * Takt im Vordergrund (MainScreen): ohne verbundenen Live-Kanal wie bisher alle 60 s. Mit Live-Kanal erst seltener
     * (5 min), wenn in dieser Anmeldung schon ein notes.tree kam (RefreshPolicy). Die kleinen Listen (Mit mir geteilt,
     * andere Server, Glocke, Chats, Heute, Entwuerfe) bleiben dabei im Minutentakt (refreshLight). Kommt der Live-Kanal
     * nach einer Unterbrechung wieder, wird sofort abgeglichen, wenn der letzte Abgleich laenger als 60 s her ist.
     */
    suspend fun pollWhileForeground(): Unit = coroutineScope {
        launch {
            var was = live.connected.value
            live.connected.collect { on ->
                if (on && !was && RefreshPolicy.staleAfterReconnect(sinceLastRefresh())) refresh()
                was = on
            }
        }
        while (true) {
            delay(RefreshPolicy.TICK_MS)
            if (RefreshPolicy.due(sinceLastRefresh(), live.connected.value, live.treeSeen)) {
                refresh()
                scope.launch { live.loadNotificationCount(); live.loadChatUnread() }
            } else {
                val sinceLight = if (lastLightMs == 0L) sinceLastRefresh() else minOf(sinceLastRefresh(), RefreshPolicy.now() - lastLightMs)
                if (RefreshPolicy.lightDue(sinceLight)) refreshLight()
            }
        }
    }

    /**
     * Kleiner Abgleich im Minutentakt, solange der Seitenbaum seltener abgeglichen wird: Mit mir geteilt (Gruppenwechsel,
     * Verschieben aus geteilten Baeumen loesen nicht immer Ereignisse aus), andere Server, Glocke, Chats, Heute, Entwuerfe.
     */
    private suspend fun refreshLight() {
        lastLightMs = RefreshPolicy.now()
        if (!ready.value || needsLogin.value) return
        try {
            loadShared()
            scope.launch { loadFedShared() }
            scope.launch { live.loadNotificationCount(); live.loadChatUnread() }
            loadTasks()
            loadTodayEvents()
            syncDrafts()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun refreshNow() {
        loading.value = true
        try {
            loadPages()
            error.value = null
            loadShared()
            scope.launch { loadFedShared() }                 // fremde Server koennen dauern: nicht darauf warten
            if (ai.status.value == null) scope.launch { ai.loadStatus() }
            // Mitteilungen: Abo nach erneuter Anmeldung/Passwortwechsel wieder anmelden (einmal je Start)
            if (appContext != null && !pushChecked) {
                pushChecked = true
                // Nr. 39: nach Kontowechsel/neuer Anmeldung Mitteilungen wieder einschalten (Berechtigung da, nicht bewusst aus)
                scope.launch { push.load(); push.autoEnable() }
            }
            if (chat.state.value == io.github.yggdrasil_sh.saga.chat.ChatSetup.LOADING) scope.launch { chat.refresh() }
            if (serverVersion.value == null) checkVersion()
            loadTasks()
            loadTodayEvents()
            ensureAccountKey()
            syncDrafts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!needsLogin.value) {
                if (isNetworkFailure(e)) checkLocalNetwork()     // Zeitueberschreitung im Heimnetz: Berechtigung fehlt?
                error.value = friendly(e)
            }
        } finally {
            loading.value = false
        }
    }

    /**
     * GET api/notes -> Seitenliste. Aufbereiten (Filtern, Mengen) im Hintergrund. Hat sich der Baum waehrend der
     * Anfrage lokal geaendert (neue Seite, Papierkorb ...), kann die Antwort davon noch nichts wissen: dann einmal
     * nachladen, statt die lokale Aenderung zu ueberschreiben.
     */
    private suspend fun loadPages() {
        repeat(2) { attempt ->
            val epoch = treeEpoch
            val all = loadTree()
            val split = withContext(Dispatchers.Default) { PageList.split(all) }
            if (treeEpoch != epoch && attempt == 0) return@repeat
            pages.value = split.active
            archivedOwn = split.archived
            ownIds = split.ids
            return
        }
    }

    /**
     * Seitenbaum-Abgleich (2.2): mit Stand nur Geaendertes (GET api/notes?since=), sonst ganz mit Stand (?delta=1);
     * aeltere Server liefern wie bisher die Liste. Grundlage und Stand je Server und Konto, Zwischenspeicher unter
     * filesDir/notes-cache (eingearbeitete Liste im Format von GET api/notes + Stand-Datei).
     */
    val treeSync = TreeSync(json, File(filesDir, "notes-cache")) { path -> api.get(path) }

    /** Konto-Kennung fuer den Stand (stabile Kennung, sonst Name) */
    private fun treeAccount(): String = settings.accountKey.value.ifEmpty { settings.account.value }

    private suspend fun loadTree(): List<PageMeta> {
        val srv = settings.server.value
        val acct = treeAccount()
        // Lesen/Einarbeiten der (grossen) Antwort und Schreiben des Zwischenspeichers nicht auf dem Main-Thread
        // (bisher wurde die Liste auf Dispatchers.Default dekodiert)
        return withContext(Dispatchers.IO) { treeSync.load(srv, acct) { settings.server.value to treeAccount() } }
    }

    private suspend fun restoreTreeCache() {
        val l = withContext(Dispatchers.IO) { treeSync.restore(settings.server.value, treeAccount()) } ?: return
        val split = withContext(Dispatchers.Default) { PageList.split(l) }
        if (pages.value.isEmpty()) {
            pages.value = split.active
            archivedOwn = split.archived
            ownIds = split.ids
        }
    }

    /** Stand verwerfen (Live "reset"): naechster Abgleich holt den ganzen Baum */
    fun dropTreeCursor() {
        treeSync.dropCursor()
    }

    /** Zaehler lokaler Baum-Aenderungen (loadPages erkennt daran veraltete Antworten) */
    @Volatile private var treeEpoch = 0L

    /** Eigene archivierte Seiten (fehlen in pages) - nur, um Unterseiten beim Archivieren/Loeschen lokal zu finden */
    @Volatile private var archivedOwn: List<PageMeta> = emptyList()

    /** IDs aller eigenen Seiten (auch archivierte; GET api/notes) - null = noch nicht geladen */
    @Volatile private var ownIds: Set<Long>? = null

    /**
     * Neue eigene Seite lokal einfuegen - mit genau den Feldern, die GET api/notes liefern wuerde (aus GET
     * api/notes/{id}, wenige KB statt der ganzen Liste). Neue Seiten bekommen sort = Anlegezeit -> am Ende.
     */
    private suspend fun insertOwnPage(id: Long) {
        val text = api.get("api/notes/$id")
        val meta = withContext(Dispatchers.Default) { PageList.metaFromDetail(json, text) }
        treeEpoch++
        if (!meta.archived) pages.value = pages.value.filter { it.id != id } + meta
        ownIds = ownIds?.plus(id)
    }

    /** Seite samt Unterseiten lokal entfernen (Papierkorb/Archiv - der Server nimmt den ganzen aktiven Teilbaum mit) */
    private suspend fun removeSubtreeLocal(id: Long, trashed: Boolean) {
        val cur = pages.value
        val arch = archivedOwn
        val gone = withContext(Dispatchers.Default) { PageList.subtree(cur + arch, id) }
        treeEpoch++
        pages.value = pages.value.filter { it.id !in gone }
        if (trashed) {
            archivedOwn = archivedOwn.filter { it.id !in gone }
            ownIds = ownIds?.minus(gone)
        } else {
            archivedOwn = archivedOwn + cur.filter { it.id in gone }.map { it.copy(archived = true) }
        }
    }

    // ---------------------------------------------------------------- Live: notes.tree (Server 2.2)

    private val treeLock = Any()
    private val treeIds = LinkedHashSet<Long>()
    private val treeOps = HashSet<String>()
    private var treeJob: kotlinx.coroutines.Job? = null

    /**
     * Live-Ereignis notes.tree {ids, op}: Seiten anderswo (Web, anderes Geraet, Freigabe) angelegt/geaendert/
     * verschoben/geloescht. Kurz sammeln (entprellt), dann gezielt nachladen oder voll abgleichen (TreeEvents.plan;
     * full: true steckt als TreeEvents.FULL in ops).
     */
    fun onTreeEvent(ids: List<Long>, ops: Collection<String>) {
        synchronized(treeLock) {
            treeIds.addAll(ids)
            treeOps.addAll(ops)
            if (ids.isEmpty()) treeOps.add("")                // ohne IDs: voller Abgleich
            if (treeJob?.isActive == true) return
            treeJob = scope.launch {
                delay(TreeEvents.DEBOUNCE_MS)
                val (i, o) = synchronized(treeLock) {
                    (treeIds.toList() to treeOps.toList()).also { treeIds.clear(); treeOps.clear(); treeJob = null }
                }
                applyTree(i, o)
            }
        }
    }

    private suspend fun applyTree(ids: List<Long>, ops: List<String>) {
        if (!ready.value || needsLogin.value) return
        when (val p = TreeEvents.plan(ids, ops)) {
            TreeEvents.Plan.Full -> {
                if (TreeEvents.FULL in ops) treeSync.dropCursor()   // full: true -> ganzen Baum neu
                refresh(join = false)                        // ein laufender Abgleich kennt die Aenderung evtl. noch nicht
            }
            is TreeEvents.Plan.Targeted -> if (treeSync.cursor != null) syncTree(p.ids) else {
                var shared = false
                var full = false
                for (id in p.ids) {
                    try {
                        val text = api.get("api/notes/$id")
                        val o = withContext(Dispatchers.Default) { json.parseToJsonElement(text) as? JsonObject }
                        val role = (o?.get("role") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        if (o != null && (role == null || role == "owner") && id > 0) {
                            upsertOwnPage(withContext(Dispatchers.Default) { PageList.metaFromDetail(json, text) })
                        } else {
                            shared = true                    // fremde (geteilte) Seite: "Mit mir geteilt" neu
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        if (e.code == 404 || e.code == 403) {
                            if (ownIds?.contains(id) == true) removeSubtreeLocal(id, trashed = true) else shared = true
                        } else {
                            full = true
                        }
                    } catch (_: Exception) {
                        full = true                          // offline o. ae.: beim naechsten Abgleich
                    }
                }
                if (full) refresh(join = false) else {
                    if (shared) loadShared()
                    afterTreeChange()
                }
            }
        }
    }

    /**
     * notes.tree created/updated mit Abgleich: ein kleines GET api/notes?since= statt je Seite GET api/notes/{id} (mit
     * Inhalt). Ist eine ID danach keine eigene Seite (geteilte Seite eines anderen), wie bisher "Mit mir geteilt" neu.
     */
    private suspend fun syncTree(ids: Set<Long>) {
        try {
            refreshLock.withLock {
                if (!ready.value || needsLogin.value) return
                loadPages()
            }
            if (ids.any { ownIds?.contains(it) != true }) loadShared()
            afterTreeChange()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            refresh(join = false)                            // offline o. ae.: wie bisher voller Abgleich
        }
    }

    /** Eigene Seite aus GET api/notes/{id} einpflegen: ersetzen (Stelle bleibt) bzw. ans Ende; archivierte ins Archiv */
    private fun upsertOwnPage(meta: PageMeta) {
        treeEpoch++
        if (meta.archived) {
            pages.value = pages.value.filter { it.id != meta.id }
            archivedOwn = PageList.upsert(archivedOwn, meta)
        } else {
            pages.value = PageList.upsert(pages.value, meta)
            archivedOwn = archivedOwn.filter { it.id != meta.id }
        }
        ownIds = ownIds?.plus(meta.id)
    }

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
        pageAdded(id, parent)
        return id
    }

    // ---------------------------------------------------------------- 2.4: Datenbanken (Ansichten, Werte, Eintraege)

    /** Zeilen laden, Werte setzen (mit Warteschlange fuer offline), Schema speichern - siehe db/DbStore.kt */
    val dbStore: io.github.yggdrasil_sh.saga.db.DbStore by lazy {
        io.github.yggdrasil_sh.saga.db.DbStore(File(filesDir, "databases"), { settings.server.value + "|" + treeAccount() }) { m, p, b -> api.request(m, p, b) }
    }

    /** Gemerkte Ansicht je Datenbank (nur Oberflaeche) */
    val dbPrefs: android.content.SharedPreferences? get() = appContext?.getSharedPreferences("saga_ui", android.content.Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 2.5: Vorlagen einer Datenbank verwalten
    private suspend fun tplDone() { refresh(join = false); dbStore.changed() }
    suspend fun templateRename(id: Long, title: String) { api.patch("api/notes/$id", buildJsonObject { put("title", title.trim()) }); tplDone() }
    suspend fun templateDuplicate(id: Long, dbId: Long, title: String) {
        api.post("api/notes/$id/duplicate", buildJsonObject { put("parent_id", dbId); put("template", true); put("title", title) }); tplDone()
    }
    /** false: "Keine Vorlage mehr" (wird ein normaler Eintrag) */
    suspend fun templateSet(id: Long, on: Boolean) { api.patch("api/notes/$id", buildJsonObject { put("template", on) }); tplDone() }
    suspend fun templateTrash(id: Long) { api.delete("api/notes/$id"); tplDone() }
    suspend fun templateNew(dbId: Long, title: String): Long {
        val id = decode<NewId>(api.post("api/notes", buildJsonObject { put("parent_id", dbId); put("title", title) })).id
        api.patch("api/notes/$id", buildJsonObject { put("template", true) })
        tplDone()
        return id
    }

    /** Neuer Eintrag in einer Datenbank (Werte z. B. aus dem Filter der Ansicht oder der Board-Spalte) */
    suspend fun newRow(dbId: Long, props: JsonObject?): Long {
        val b = buildJsonObject {
            put("parent_id", dbId)
            put("title", "")
            if (props != null && props.isNotEmpty()) put("props", props)
        }
        val id = decode<NewId>(api.post("api/notes", b)).id
        pageAdded(id, dbId)
        dbStore.changed()
        return id
    }

    /**
     * Schaltflaeche "Neuer Eintrag" (wie Web/iOS): leer oder als Kopie einer Vorlage (Inhalt, Symbol, Werte, Unterseiten).
     * Ohne Vorlage wird immer ein Titel mitgeschickt (auch leer), mit Vorlage nur ein gesetzter - sonst bleibt deren Titel.
     */
    suspend fun buttonEntry(dbId: Long, title: String, tpl: Long?, via: Long? = null): Long {
        val id = decode<NewId>(api.post(if (tpl != null) "api/notes/$tpl/duplicate" else "api/notes", buttonEntryBody(dbId, title, tpl, via))).id
        if (tpl == null) pageAdded(id, dbId) else refresh(join = false)
        dbStore.changed()
        return id
    }

    /**
     * Nach dem Anlegen: eigene Seite lokal einfuegen statt die ganze Seitenliste neu zu laden. Unter einer fremden
     * (geteilten) Elternseite gehoert die neue Seite deren Eigentuemer -> wie bisher vollstaendig abgleichen
     * ("Mit mir geteilt"). Faellt das gezielte Laden aus, ebenfalls der volle Abgleich.
     */
    private suspend fun pageAdded(id: Long, parent: Long?) {
        // 2.3: Unterseite einer Seite eines anderen Servers (negative IDs) - nur "Mit mir geteilt" dieses Servers neu holen
        if (parent != null && parent < 0) {
            loadFedShared()
            return
        }
        val own = parent == null || ownIds?.contains(parent) == true
        if (own) {
            try {
                insertOwnPage(id)
                afterTreeChange()                            // Aufgaben/Termine (klein) wie bisher mit auffrischen
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        refresh(join = false)
    }

    override suspend fun setPinned(id: Long, pinned: Boolean) {
        api.patch("api/notes/$id", buildJsonObject { put("pinned", pinned) })
        pages.value = pages.value.map { if (it.id == id) it.copy(pinned = pinned) else it }
    }

    override suspend fun patchPage(id: Long, body: JsonObject) {
        api.patch("api/notes/$id", body)
        (body["icon"] as? kotlinx.serialization.json.JsonPrimitive)?.let { ic ->
            val v = if (ic is kotlinx.serialization.json.JsonNull) "" else ic.content
            pages.value = pages.value.map { if (it.id == id) it.copy(icon = v) else it }
        }
        (body["tags"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
            val t = arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            pages.value = pages.value.map { if (it.id == id) it.copy(tags = t) else it }
        }
    }

    /** Seiten, die auf diese Seite verweisen ("Verlinkt von", wie Web/iOS) */
    suspend fun backlinks(id: Long): List<PageMeta> = decode(api.get("api/notes/$id/backlinks"))

    override suspend fun setLocked(id: Long, locked: Boolean) {
        api.patch("api/notes/$id", buildJsonObject { put("locked", locked) })
        pages.value = pages.value.map { if (it.id == id) it.copy(locked = locked) else it }
    }

    /** Seite (samt Unterseiten) in den Papierkorb; Rueckgaengig ueber den Papierkorb */
    suspend fun trashPage(id: Long) {
        api.delete("api/notes/$id")
        removeSubtreeLocal(id, trashed = true)
        afterTreeChange()
    }

    /** Zurueckholen: Daten der zurueckgeholten Seiten kennt nur der Server -> voller Abgleich (selten) */
    suspend fun restorePage(id: Long) {
        api.post("api/trash/$id/restore")
        refresh(join = false)
    }

    suspend fun archivePage(id: Long, archived: Boolean) {
        api.post("api/notes/$id/archive", buildJsonObject { put("archived", archived) })
        if (archived) {
            removeSubtreeLocal(id, trashed = false)
            afterTreeChange()
        } else {
            refresh(join = false)                            // aus dem Archiv holen: Daten nur vom Server
        }
    }

    /** Was ausser dem Baum an Seiten haengt (Aufgaben, Termine aus Seiten) - klein, daher wie bisher nachladen */
    private suspend fun afterTreeChange() {
        loadTasks()
        loadTodayEvents()
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

    /** Kommentare beim Oeffnen dieser Seite zeigen (Benachrichtigung/Mitteilung): Seite + Block (null = alle) */
    val openCommentsFor = MutableStateFlow<Pair<Long, String?>?>(null)

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

    // ---------------------------------------------------------------- Foederation (Server 2.0)

    /** "Mit mir geteilt" von anderen Saga-Instanzen (GET /api/fed/shared, negative IDs) */
    val fedShared = MutableStateFlow<List<PageMeta>>(emptyList())

    /** Andere Saga-Instanzen, die gerade nicht erreichbar sind (dezent anzeigen) */
    val fedUnreachable = MutableStateFlow<List<String>>(emptyList())

    private val fedLock = Mutex()

    suspend fun loadFedShared() {
        if (needsLogin.value || !fedLock.tryLock()) return
        try {
            val r = decode<FedShared>(api.get("api/fed/shared"))
            fedShared.value = r.items.filter { !it.archived }
            fedUnreachable.value = r.unreachable
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404) {                             // Server ohne Foederation
                fedShared.value = emptyList()
                fedUnreachable.value = emptyList()
            }
        } catch (_: Exception) {
            // offline: bisherigen Stand behalten
        } finally {
            fedLock.unlock()
        }
    }

    /** Alle fremden Seiten (dieser und anderer Instanzen) fuer "Mit mir geteilt" */
    fun sharedAll(): List<PageMeta> = sharedPages.value + fedShared.value

    /** Eigene Seite ist (nicht mehr) freigegeben -> Personen-Symbol im Baum */
    fun markShared(id: Long, shared: Boolean) {
        pages.value = pages.value.map { if (it.id == id && it.shared != shared) it.copy(shared = shared) else it }
    }

    /** Kommentare gelesen/geschrieben -> Punkt im Baum nachziehen */
    fun markUnread(id: Long, unread: Boolean) {
        pages.value = pages.value.map { if (it.id == id && it.unread != unread) it.copy(unread = unread) else it }
        sharedPages.value = sharedPages.value.map { if (it.id == id && it.unread != unread) it.copy(unread = unread) else it }
        fedShared.value = fedShared.value.map { if (it.id == id && it.unread != unread) it.copy(unread = unread) else it }
    }

    private fun q(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    override suspend fun shares(id: Long): ShareState = decode(api.get("api/notes/$id/shares"))

    /** Server ab 2.3: Personensuche ab 1 Zeichen (Version unbekannt bzw. aelter: wie bisher ab 3) */
    override val shortSearch: Boolean get() = io.github.yggdrasil_sh.saga.util.Versions.atLeast(serverVersion.value, "2.3.0")

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
        pageAdded(id, null)
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
            // 2.4: offline geaenderte Datenbank-Werte nachholen (wie Entwuerfe)
            try {
                dbStore.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
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

    /** Homescreen-Widget angetippt: Ziel ("today", "tasks", "events", "page:12", "new-task") - MainScreen navigiert */
    val openDestRequest = MutableStateFlow<String?>(null)

    /** "Neue Aufgabe" aus dem Widget: Eingabefeld in "Heute" fokussieren */
    val focusQuickAdd = MutableStateFlow(false)

    /**
     * Geteiltes ablegen wie die iOS-Teilen-Erweiterung: POST api/app/capture mit dem App-Schluessel
     * (Seite landet im Eingang, Aufgabe in den Aufgaben). Ohne App-Schluessel ueber die normale Sitzung.
     * -> ID der neuen Seite bzw. null bei Aufgabe
     */
    suspend fun capture(kind: String, title: String, text: String, url: String?): Long? {
        val key = withContext(Dispatchers.IO) { secure.appKeyFor(settings.server.value) }
        if (key != null) {
            val r = api.request("POST", "api/app/capture", captureBody(kind, title, text, url), bearer = key)
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
        // Android 17: Rueckfrage zeigen und auf die Antwort warten - nach "Erlauben" geht es direkt weiter,
        // die Meldung "kein Zugriff" erst nach Ablehnung/"Spaeter"
        if (checkLocalNetwork(server, force = true)) {
            val ok = localGate.await()
            if (!ok && appContext?.let { LocalNetwork.granted(it) } != true) throw UiTextException(UiText.Res(R.string.error_local_network))
            localBlocked = false
        }
        probe(server)
        val p = Pkce.create()
        withContext(Dispatchers.IO) {
            secure.pkceVerifier = p.verifier
            secure.pkceServer = server
        }
        return LoginFlow.loginUrl(server, p.challenge, fresh = freshLogin, lang = language())
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
            // N-09: "Server nicht erreichbar: Server nicht erreichbar" - der Grund sagt es dann schon allein
            val f = friendly(e)
            throw UiTextException(if (probeSelfExplaining(e)) f else UiText.Res(R.string.login_probe_failed, f))
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
            val msg = UiText.Res(PairLink.rejectReason(target, BuildConfig.DEBUG))
            if (needsLogin.value) loginError.value = msg else pairRejected.value = msg
            return
        }
        pairError.value = null
        pendingPair.value = t
    }

    fun cancelPair() {
        pendingPair.value = null
        pairError.value = null
        // Abgebrochen: derselbe Link darf spaeter (auch nach einem Neustart der App) wieder die Rueckfrage zeigen
        appContext?.getSharedPreferences(io.github.yggdrasil_sh.saga.auth.LinkGate.PREFS, android.content.Context.MODE_PRIVATE)
            ?.edit()?.remove(io.github.yggdrasil_sh.saga.auth.LinkGate.KEY)?.apply()
    }

    /** Bestaetigte Kopplung: offene Seiten speichern, koppeln, erst bei Erfolg wechseln */
    suspend fun confirmPair() {
        val t = pendingPair.value ?: return
        loginBusy.value = true
        pairError.value = null
        loginError.value = null
        try {
            // Android 17: Server im lokalen Netz ohne Berechtigung -> erst fragen (Kopplung bleibt offen, danach erneut)
            if (checkLocalNetwork(t.server, force = true)) {
                pairError.value = UiText.Res(R.string.error_local_network)
                return
            }
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
        freshLogin = false
        treeSync.clear()                                     // Stand gilt je Konto: neu angemeldet = ganz laden
        adoptNamedDrafts()
        serverVersion.value = null
        minApp.value = null
        needsLogin.value = false
        loginError.value = null
        loginHint.value = null
        signInCount.value++                                  // Geraeteliste, Konto usw. neu laden
        message?.let { notify(it) }
        live.clear()
        live.restart()
        refresh(join = false)
    }

    /** Lokale Daten des bisherigen Kontos leeren (Entwuerfe bleiben je Server/Konto auf dem Geraet) */
    /** 2.4: Ciphertext-Cache der Videos leeren ("Neu beginnen", Geraet entfernt) */
    fun clearVideoCache() {
        val ctx = appContext ?: return
        scope.launch(Dispatchers.IO) { io.github.yggdrasil_sh.saga.chat.video.VideoCache.clear(ctx) }
    }

    private suspend fun clearLocalData() {
        api.cookies.clear()
        withContext(Dispatchers.IO) { treeSync.clear() }     // Seitenbaum-Stand und Zwischenspeicher des Kontos weg
        withContext(Dispatchers.IO) { dbStore.clearCache() }  // 2.4: zwischengespeicherte Datenbank-Zeilen
        pages.value = emptyList()
        ownIds = null
        sharedPages.value = emptyList()
        fedShared.value = emptyList()
        fedUnreachable.value = emptyList()
        live.clear()
        chat.reset()
        // entschluesselte Chat-Anhaenge (Klartext im Cache) beim Abmelden/Kontowechsel loeschen (BUG-023)
        appContext?.cacheDir?.let { c -> withContext(Dispatchers.IO) { io.github.yggdrasil_sh.saga.chat.ChatFiles.purge(c) } }
        appContext?.cacheDir?.let { c -> withContext(Dispatchers.IO) { FaviconLoader.clear(c) } }   // 2.5: direkt geladene Lesezeichen-Favicons
        // Bild-Cache (Coil): private Immich-Vorschaubilder, Profilbilder, Seitenbilder des bisherigen Kontos
        appContext?.let { ctx ->
            withContext(Dispatchers.IO) {
                try {
                    val loader = coil3.SingletonImageLoader.get(ctx)
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                } catch (_: Exception) {
                }
            }
        }
        ai.clear()
        if (appContext != null) push.clear()
        if (appContext != null) calls.reset()
        // 2.4: laufende Video-Uploads verwerfen (Schluessel im geschuetzten Speicher), Zwischendateien und Video-Cache weg
        appContext?.let { ctx -> withContext(Dispatchers.IO) { io.github.yggdrasil_sh.saga.chat.video.Videos.clearAll(ctx, this@SagaRepository) } }
        io.github.yggdrasil_sh.saga.ui.common.AvatarLogic.reset()
        pushChecked = false
        shareSupported.value = false
        shareOff.value = false
        tasks.value = emptyList()
        doneTasks.value = emptyList()
        todayEvents.value = emptyList()
        // Homescreen-Widgets: Aufgaben, Termine und Seitentexte des bisherigen Kontos sofort entfernen
        appContext?.let { ctx ->
            withContext(Dispatchers.IO) { io.github.yggdrasil_sh.saga.widget.WidgetStore(ctx).clearContent(signedOut = true) }
            io.github.yggdrasil_sh.saga.widget.WidgetUpdates.redraw(ctx)
        }
        error.value = null
        serverVersion.value = null
        minApp.value = null
    }

    /** Auf diesem Geraet abmelden: Geraete-Anmeldung am Server loeschen, Sitzung + Daten lokal entfernen */
    suspend fun logout() {
        flushEditors()
        // A10: Chat-Geraet beim Server abmelden (bekommt keine Schluessel mehr), die lokalen Chat-Schluessel loescht
        // signOutLocal - sonst bliebe ein Geraet ohne Schluessel in der Liste. Offline: nur lokal.
        try {
            chat.deviceId?.let { api.delete("api/chat/devices/$it") }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        auth.signOutLocal(forgetRemote = true)
        freshLogin = true
        needsLogin.value = true
    }

    /**
     * Nach dem Abmelden in der App (Kontowechsel): die naechste Browser-Anmeldung startet ohne die Sitzung des
     * bisherigen Kontos (privater Custom Tab, prompt=login) - sonst bietet die Seite nur "Anmelden als <alt>".
     */
    var freshLogin: Boolean
        get() = appContext?.getSharedPreferences("saga_ui", android.content.Context.MODE_PRIVATE)?.getBoolean("fresh_login", false) == true
        set(v) {
            appContext?.getSharedPreferences("saga_ui", android.content.Context.MODE_PRIVATE)?.edit()?.putBoolean("fresh_login", v)?.apply()
        }

    /** Bild-/Datei-Adresse (/files/...) auf dem Server */
    fun absoluteUrl(path: String): String = ServerUrl.absolute(settings.server.value, path)

    /** Immich (Bild-Auswahl im Chat) ueber die Server-Endpunkte */
    val immich: ImmichApi by lazy { ImmichApi(api, json) }

    /**
     * Bild fuer eine Seite hochladen (POST api/files, Feld "image") -> Adresse /files/...
     * note = fremde Seite mit Rolle Bearbeiten: die Datei gehoert deren Eigentuemer.
     */
    suspend fun uploadImage(data: ByteArray, name: String, mime: String, note: Long?): String {
        val r = api.postMultipart("api/files", "image", name, mime, data, note)
        val o = json.parseToJsonElement(r) as? JsonObject
        val url = ((o?.get("file") as? JsonObject)?.get("url") as? kotlinx.serialization.json.JsonPrimitive)?.content
        return url?.takeIf { it.isNotBlank() } ?: throw ApiException(500, UiText.Res(R.string.image_upload_failed))
    }
}

/**
 * Anfrage fuer die Schaltflaeche "Neuer Eintrag". via = Seite mit der Schaltflaeche: Bei einer geteilten Seite prueft
 * der Server die Schaltflaeche dort (Vorlage des Eigentuemers, Server 2.5) - auch bei einer Seite eines anderen Servers
 * (negative ID; der eigene Server reicht es an den Eigentuemer-Server weiter, Faehigkeit tplbtn).
 */
internal fun buttonEntryBody(dbId: Long, title: String, tpl: Long?, via: Long?): JsonObject = buildJsonObject {
    put("parent_id", dbId)
    if (title.isNotEmpty() || tpl == null) put("title", title)
    if (tpl != null && via != null && via != 0L) put("via", via)
}
