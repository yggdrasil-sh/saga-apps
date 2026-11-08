package io.github.yggdrasil_sh.saga.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.yggdrasil_sh.saga.MainActivity
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.SagaApplication
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

@Serializable
data class PushServerInfo(
    val enabled: Boolean = false,
    val webpush: Boolean = false,
    @SerialName("vapid_public") val vapidPublic: String? = null,
    val ntfy: Boolean = false,
    val unifiedpush: Boolean = false,
    val apns: Boolean = false,
    /** 2.5: eigene Verbindung ohne Verteiler (GET /api/push/stream) */
    val stream: Boolean = false,
    @SerialName("stream_path") val streamPath: String? = null,
)

@Serializable
data class PushQuiet(val on: Boolean = false, val from: String = "22:00", val to: String = "07:00", val tz: String = "")

@Serializable
data class PushSettings(val cats: Map<String, Boolean> = emptyMap(), val names: Boolean = false, val quiet: PushQuiet = PushQuiet())

@Serializable
data class PushDevice(
    val id: Long,
    val type: String = "",
    val name: String = "",
    val where: String = "",
    val app: Boolean = false,
    @SerialName("this_device") val thisDevice: Boolean = false,
    val created: Double? = null,
    @SerialName("last_ok") val lastOk: Double? = null,
    @SerialName("last_error") val lastError: String? = null,
    val fails: Int = 0,
    val encrypted: Boolean = false,
    /** 2.5, nur Abo-Art "stream": gerade verbunden */
    val connected: Boolean? = null,
)

@Serializable
data class PushInfo(
    val server: PushServerInfo = PushServerInfo(),
    val settings: PushSettings = PushSettings(),
    val categories: List<String> = emptyList(),
    val devices: List<PushDevice> = emptyList(),
    val limit: Int = 20,
)

@Serializable
data class PushSubscribed(val id: Long = 0, val active: Boolean = false, @SerialName("stream_path") val streamPath: String? = null)

@Serializable
data class PushTest(val ok: Boolean = false, val result: String = "", val error: String? = null)

/** Inhalt einer Mitteilung (nie Seitentitel/Inhalte - nur ein Satz und der Link) */
@Serializable
data class PushMessage(
    val title: String = "",
    val body: String = "",
    val url: String = "/",
    val tag: String = "",
    val kind: String = "",
    val ts: Long = 0,
    /** 2.1, Art "call": Kennungen des klingelnden Anrufs (nie Inhalte) */
    val call: String? = null,
    val chat: Long? = null,
    val media: String? = null,
)

/** Anmeldung dieses Geraets beim Verteiler (je Server + Konto) */
@Serializable
data class PushLocal(
    val distributor: String = "",
    val token: String = "",
    val endpoint: String? = null,
    val subId: Long? = null,
    val priv: String = "",
    val pub: String = "",
    val auth: String = "",
    /** 2.5: Pfad der eigenen Verbindung (distributor == STREAM) */
    val path: String? = null,
    /** 2.5: Umstieg auf einen Verteiler - dieses alte "stream"-Abo nach erfolgreicher Anmeldung entfernen */
    val replaces: Long? = null,
)

enum class PushState { OFF, REGISTERING, ON, ERROR }

object PushLogic {
    /** "Verteiler" der eigenen Verbindung (Server 2.5) - kein Paketname */
    const val STREAM = "@saga-stream"

    /** Im Hintergrund erreichbar: Anmeldung mit Adresse und der gewaehlte Verteiler ist noch installiert (oder eigene Verbindung) */
    fun backgroundReady(local: PushLocal?, installed: List<String>, streamAllowed: Boolean = true): Boolean =
        local != null && local.endpoint != null &&
            (local.distributor in installed || (local.distributor == STREAM && streamAllowed && installed.isEmpty()))

    /** Was tun, damit dieses Geraet erreichbar bleibt? (rein, getestet) */
    enum class Step { NONE, START_STREAM, STOP_STREAM, TO_DISTRIBUTOR, TO_STREAM, DROP }

    /**
     * @param local Anmeldung dieses Geraets (null = Mitteilungen hier aus)
     * @param installed installierte Verteiler
     * @param allowed Schalter "Eigene Verbindung"
     * @param serverStream Server bietet die eigene Verbindung an (null = unbekannt, z. B. offline/beim Start)
     */
    fun step(local: PushLocal?, installed: List<String>, allowed: Boolean, serverStream: Boolean?): Step = when {
        local == null -> Step.NONE
        local.distributor == STREAM && installed.isNotEmpty() -> Step.TO_DISTRIBUTOR       // Verteiler neu da: umschalten
        local.distributor == STREAM && (!allowed || serverStream == false) -> Step.DROP
        local.distributor == STREAM -> Step.START_STREAM
        local.distributor !in installed && installed.isEmpty() && allowed && serverStream == true -> Step.TO_STREAM   // Verteiler deinstalliert
        else -> Step.STOP_STREAM
    }
}

/**
 * Mitteilungen aufs Geraet (Server 1.7) ueber UnifiedPush (z. B. ntfy als Verteiler) mit Web-Push-Verschluesselung.
 * Kein Google FCM. Einstellungen und Geraeteliste wie im Web.
 */
class PushService(val repo: SagaRepository, private val context: Context, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    val info = MutableStateFlow<PushInfo?>(null)
    /** Server kennt Mitteilungen (GET /api/push; 404 = aelter als 1.7) */
    val supported = MutableStateFlow(true)
    val state = MutableStateFlow(PushState.OFF)
    val error = MutableStateFlow<UiText?>(null)

    init {
        // Design-Runde 4 (CH-04): Glocken-Zeilen anderswo gelesen -> passende System-Mitteilungen entfernen
        repo.live.onRowsRead = { keys, all -> Notifier.cancelRead(context, keys, all) }
        // Nachbesserung: Chat entfernt/verlassen -> Unterhaltungs-Shortcut und Mitteilung weg
        repo.live.onChatRemoved = { cid ->
            ChatShortcuts.remove(context, accountTag(), cid)
            Notifier.cancel(context, "chat-$cid")
        }
        // "Namen zeigen" merken: im Hintergrund (Prozess vom Mitteilungsdienst gestartet) ist info noch nicht geladen
        scope.launch { info.collect { i -> if (i != null) uiPrefs.edit().putBoolean(namesKey(), i.settings.names).apply() } }
    }

    /** Kurze Kennung von Server + Konto (Mitteilungs-Extras, Shortcuts) - kein Geheimnis */
    fun accountTag(): String = Account.tag(repo.settings.server.value, repo.settings.accountKey.value)

    private fun namesKey() = "push_names:" + key()

    private fun key() = "push_local:" + repo.settings.server.value + "|" + repo.settings.accountKey.value

    fun local(): PushLocal? = try {
        repo.secure.get(key())?.let { json.decodeFromString(PushLocal.serializer(), it) }
    } catch (_: Exception) {
        null
    }

    private fun saveLocal(l: PushLocal?) = repo.secure.put(key(), l?.let { json.encodeToString(PushLocal.serializer(), it) })

    private fun keysOf(l: PushLocal): PushKeys = PushKeys(WebPushCrypto.unb64u(l.priv), WebPushCrypto.unb64u(l.pub), WebPushCrypto.unb64u(l.auth))

    fun distributors(): List<Distributor> = UnifiedPush.distributors(context)

    /**
     * Kann Saga im Hintergrund klingeln/benachrichtigen? Nur mit eingerichtetem UnifiedPush (Adresse vorhanden, Verteiler
     * noch installiert) - sonst laufen Anrufe nur ueber den Live-Kanal der geoeffneten App.
     */
    fun backgroundReady(): Boolean = PushLogic.backgroundReady(local(), distributors().map { it.pkg }, streamAllowed())

    // ---------------------------------------------------------------- 2.5: eigene Verbindung ohne Verteiler

    /** Schalter "Eigene Verbindung" (je Installation, Standard an) */
    fun streamAllowed(): Boolean = uiPrefs.getBoolean("push_stream_allowed", true)

    val streamAllowedFlow by lazy { MutableStateFlow(streamAllowed()) }

    /** Laeuft die eigene Verbindung fuer dieses Geraet? */
    fun usesStream(): Boolean = local()?.distributor == PushLogic.STREAM

    class StreamTarget(val server: String, val path: String, val token: String)

    /** Fuer PushStreamService: wohin verbinden (null = nicht mehr verbinden) */
    fun streamTarget(): StreamTarget? {
        val l = local() ?: return null
        if (l.distributor != PushLogic.STREAM || l.subId == null || !streamAllowed()) return null
        val server = repo.settings.server.value.ifEmpty { return null }
        val tok = repo.secure.deviceTokenFor(server) ?: return null
        return StreamTarget(server, l.path ?: "/api/push/stream", tok)
    }

    /**
     * Abgleich (App-Start, Einstellungen, Paket installiert, Dienst): Verteiler neu installiert -> umschalten und Dienst
     * beenden; Verteiler weg -> eigene Verbindung; sonst Dienst passend starten/beenden.
     */
    suspend fun ensure() {
        if (PushStreamService.debugTarget != null) return            // nur Debug-Messung (src/debug)
        val l = withContext(Dispatchers.IO) { local() }
        val ds = distributors()
        when (PushLogic.step(l, ds.map { it.pkg }, streamAllowed(), info.value?.server?.stream)) {
            PushLogic.Step.NONE, PushLogic.Step.STOP_STREAM -> PushStreamService.stop(context)
            PushLogic.Step.START_STREAM -> if (!repo.needsLogin.value) PushStreamService.start(context) else PushStreamService.stop(context)
            PushLogic.Step.TO_DISTRIBUTOR -> {
                // Dienst laeuft weiter, bis die Adresse des Verteilers angemeldet ist (keine Luecke); der Server bevorzugt
                // ohnehin das UnifiedPush-Abo desselben Geraets
                enable(ds[0], replaces = l?.subId)
            }
            PushLogic.Step.TO_STREAM -> {
                withContext(Dispatchers.IO) { saveLocal(null) }
                enableStream()
            }
            PushLogic.Step.DROP -> {
                PushStreamService.stop(context)
                l?.subId?.let { id -> quietDelete(id) }
                withContext(Dispatchers.IO) { saveLocal(null) }
                state.value = PushState.OFF
            }
        }
    }

    private suspend fun quietDelete(id: Long) {
        try {
            repo.api.delete("api/push/subscriptions/$id")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Ohne Verteiler einschalten: Schluessel erzeugen, Abo "stream" anmelden, Dienst starten */
    suspend fun enableStream() {
        error.value = null
        setUserOff(false)
        val k = withContext(Dispatchers.Default) { WebPushCrypto.generate() }
        try {
            val r = json.decodeFromString(PushSubscribed.serializer(), repo.api.post("api/push/subscriptions", buildJsonObject {
                put("type", "stream")
                putJsonObject("keys") {
                    put("p256dh", k.p256dh)
                    put("auth", k.authB64)
                }
                put("name", repo.deviceName)
            }))
            val l = PushLocal(PushLogic.STREAM, UUID.randomUUID().toString(), "stream", r.id, WebPushCrypto.b64u(k.privatePkcs8), k.p256dh, k.authB64,
                path = r.streamPath ?: info.value?.server?.streamPath ?: "/api/push/stream")
            withContext(Dispatchers.IO) { saveLocal(l) }
            state.value = PushState.ON
            PushStreamService.start(context)
            info.value = json.decodeFromString(PushInfo.serializer(), repo.api.get("api/push"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = PushState.ERROR
            error.value = repo.friendly(e)
        }
    }

    /** Schalter "Eigene Verbindung" */
    suspend fun setStreamAllowed(on: Boolean) {
        uiPrefs.edit().putBoolean("push_stream_allowed", on).apply()
        streamAllowedFlow.value = on
        if (!on && usesStream()) {
            ensure()                          // -> DROP: Abo weg, Dienst aus
        } else if (on && local() == null && distributors().isEmpty() && info.value?.server?.stream == true) {
            enableStream()
        }
    }

    /** Dienst meldet: Server hat geschlossen (4401 abgemeldet, 4404 Abo weg, 4409 anderes Geraet hat uebernommen) */
    suspend fun onStreamStopped(code: Int) {
        when (code) {
            4404 -> load()                    // Abo verschwunden -> load() meldet neu an; Weg abgeschaltet -> ensure() -> DROP
            4401 -> PushStreamService.stop(context)       // Sitzung erneuern passiert beim naechsten Start der App
            else -> PushStreamService.stop(context)
        }
    }

    /** Mitteilung ueber die eigene Verbindung: derselbe Weg wie UnifiedPush */
    fun onStreamMessage(bytes: ByteArray): kotlinx.coroutines.Job? {
        val l = local() ?: return null
        if (l.distributor != PushLogic.STREAM) return null
        return onMessage(l.token, bytes)
    }

    // ---------------------------------------------------------------- Hinweis "ohne Verteiler"

    private val uiPrefs get() = context.getSharedPreferences("saga_ui", Context.MODE_PRIVATE)

    /** Hinweis beim ersten Anruf ohne Verteiler (einmal je Installation) */
    val callHint = MutableStateFlow(false)

    fun maybeCallHint() {
        if (uiPrefs.getBoolean("call_push_hint_shown", false) || backgroundReady()) return
        callHint.value = true
    }

    fun callHintDone() {
        callHint.value = false
        uiPrefs.edit().putBoolean("call_push_hint_shown", true).apply()
    }

    // ---------------------------------------------------------------- Stand

    suspend fun load() {
        if (repo.needsLogin.value) return
        try {
            val i = json.decodeFromString(PushInfo.serializer(), repo.api.get("api/push"))
            info.value = i
            supported.value = true
            val l = withContext(Dispatchers.IO) { local() }
            state.value = when {
                l == null -> PushState.OFF
                l.endpoint == null -> PushState.REGISTERING
                else -> PushState.ON
            }
            // Abo verschwunden (abgemeldet, Passwortwechsel, anderes Geraet hat es entfernt): neu anmelden
            if (l?.distributor == PushLogic.STREAM) {
                if (i.server.enabled && i.server.stream && i.devices.none { it.id == l.subId }) {
                    withContext(Dispatchers.IO) { saveLocal(null) }
                    if (streamAllowed() && distributors().isEmpty()) enableStream() else state.value = PushState.OFF
                }
            } else if (l?.endpoint != null && i.server.enabled && i.devices.none { it.id == l.subId }) subscribe(l.endpoint)
            ensure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404) supported.value = false else error.value = repo.friendly(e)
        } catch (e: Exception) {
            error.value = repo.friendly(e)
        }
    }

    fun clear() {
        PushStreamService.stop(context)
        // Nachbesserung (CH-04): Abmelden/Kontowechsel - Unterhaltungs-Shortcuts und Mitteilungen des alten Kontos weg
        ChatShortcuts.removeAll(context)
        Notifier.cancelRead(context, emptySet(), all = true)
        info.value = null
        state.value = PushState.OFF
        error.value = null
    }

    // ---------------------------------------------------------------- Ein/Aus

    /** Einschalten: Schluessel erzeugen, beim Verteiler anmelden; die Adresse kommt per NEW_ENDPOINT (PushReceiver) */
    suspend fun enable(d: Distributor, replaces: Long? = null) {
        error.value = null
        setUserOff(false)
        val l = withContext(Dispatchers.Default) {
            val k = WebPushCrypto.generate()
            PushLocal(d.pkg, UUID.randomUUID().toString(), null, null, WebPushCrypto.b64u(k.privatePkcs8), k.p256dh, k.authB64, replaces = replaces)
        }
        withContext(Dispatchers.IO) { saveLocal(l) }
        state.value = PushState.REGISTERING
        UnifiedPush.register(context, d.pkg, l.token, info.value?.server?.vapidPublic, context.getString(R.string.app_name))
    }

    /** vom Nutzer ausgeschaltet (Einstellungen > Mitteilungen) - dann nie von selbst wieder einschalten */
    private fun offKey() = "push_user_off:" + key()
    fun userTurnedOff(): Boolean = uiPrefs.getBoolean(offKey(), false)
    private fun setUserOff(on: Boolean) = uiPrefs.edit().putBoolean(offKey(), on).apply()

    /**
     * Wie iOS: Wer Mitteilungen erlaubt hat, bekommt sie auch bei geschlossener App. Bisher erteilte die Abfrage nur
     * die Android-Berechtigung - Mitteilungen auf dem Geraet musste man zusaetzlich in den Einstellungen einschalten,
     * sonst kam bei geschlossener App nichts an. Jetzt: Berechtigung da, noch keine Anmeldung, nicht bewusst
     * ausgeschaltet -> einschalten (eigene Verbindung bzw. der einzige installierte Verteiler; mehrere: Einstellungen).
     */
    suspend fun autoEnable() {
        if (repo.needsLogin.value || userTurnedOff()) return
        if (!io.github.yggdrasil_sh.saga.ui.common.NotifPrompt.granted(context)) return
        if (withContext(Dispatchers.IO) { local() } != null) return
        if (info.value == null) load()
        if (withContext(Dispatchers.IO) { local() } != null) return
        val i = info.value ?: return
        if (!i.server.enabled) return
        val ds = distributors()
        when {
            ds.isEmpty() && i.server.stream && streamAllowed() -> enableStream()
            ds.size == 1 -> enable(ds[0])
        }
    }

    suspend fun disable() {
        setUserOff(true)
        val l = withContext(Dispatchers.IO) { local() }
        if (l != null) {
            l.subId?.let { id ->
                try {
                    repo.api.delete("api/push/subscriptions/$id")
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            if (l.distributor == PushLogic.STREAM) PushStreamService.stop(context)
            else UnifiedPush.unregister(context, l.distributor, l.token)
            l.replaces?.let { quietDelete(it) }
        }
        withContext(Dispatchers.IO) { saveLocal(null) }
        state.value = PushState.OFF
        load()
    }

    /** Neue Adresse vom Verteiler -> beim Server anmelden */
    suspend fun onNewEndpoint(token: String, endpoint: String) {
        val l = withContext(Dispatchers.IO) { local() } ?: return
        if (l.token != token) return
        withContext(Dispatchers.IO) { saveLocal(l.copy(endpoint = endpoint)) }
        subscribe(endpoint)
    }

    private suspend fun subscribe(endpoint: String) {
        val l = withContext(Dispatchers.IO) { local() } ?: return
        try {
            val r = json.decodeFromString(PushSubscribed.serializer(), repo.api.post("api/push/subscriptions", buildJsonObject {
                put("type", "unifiedpush")
                put("endpoint", endpoint)
                putJsonObject("keys") {
                    put("p256dh", l.pub)
                    put("auth", l.auth)
                }
                put("name", repo.deviceName)
            }))
            withContext(Dispatchers.IO) { saveLocal(l.copy(endpoint = endpoint, subId = r.id, replaces = null)) }
            state.value = PushState.ON
            error.value = null
            // 2.5: Umstieg von der eigenen Verbindung - altes Abo weg, Dienst aus
            l.replaces?.let { quietDelete(it) }
            PushStreamService.stop(context)
            info.value = json.decodeFromString(PushInfo.serializer(), repo.api.get("api/push"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = PushState.ERROR
            error.value = repo.friendly(e)
        }
    }

    fun onFailed(token: String) {
        val l = local() ?: return
        if (l.token != token) return
        if (l.replaces != null) {
            // Umstieg auf den Verteiler gescheitert: bei der eigenen Verbindung bleiben (Abo besteht noch)
            scope.launch {
                withContext(Dispatchers.IO) { saveLocal(null) }
                quietDelete(l.replaces)
                enableStream()
            }
            return
        }
        state.value = PushState.ERROR
        error.value = UiText.Res(R.string.push_register_failed)
    }

    fun onUnregistered(token: String) {
        val l = local() ?: return
        if (l.token != token) return
        saveLocal(null)
        state.value = PushState.OFF
        scope.launch {
            l.subId?.let {
                try {
                    repo.api.delete("api/push/subscriptions/$it")
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            load()
        }
    }

    /**
     * Mitteilung vom Verteiler: entschluesseln (aes128gcm) bzw. Klartext-JSON, dann anzeigen.
     * -> laufende Arbeit (Anruf: Zustand holen), auf die der Empfaenger warten soll, sonst null
     */
    fun onMessage(token: String, bytes: ByteArray): kotlinx.coroutines.Job? {
        val l = local() ?: return null
        if (l.token != token) return null
        val msg = PushParse.parse(bytes, keysOf(l)) ?: return null
        // Anruf: nicht als normale Mitteilung - Zustand holen und als Vollbild-Anruf klingeln (nur solange er klingelt)
        // 2.4 E5: "call.group" (Gruppenanruf) ebenso - Zustand holen, klingeln nur, solange die eigene Einladung klingelt
        if ((msg.kind == "call" || msg.kind == "call.group") && msg.call != null) return repo.calls.onRing(msg.call)
        // 4.11.: Nachricht aus dem Chat, der gerade offen ist (App vorne) - kein Banner/Ton, sie steht ja schon da
        val fg = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (io.github.yggdrasil_sh.saga.live.NotifLogic.hideWhileOpen(msg.kind, msg.url, if (fg) repo.chat.visibleChat else null)) return null
        // Design-Runde 4: Glocken-Zaehler auch ohne Live-Kanal (App im Hintergrund) nachziehen
        if (msg.kind != "test") scope.launch { repo.live.loadNotificationCount() }
        // Nachbesserung: Satz in der App-Sprache (NotifyText), Chat-Name/Seitentitel aus dem lokalen Stand - im Hintergrund
        // ggf. erst nachladen (hoechstens ~4 s; der Empfaenger wartet auf diese Arbeit)
        return scope.launch {
            val p = NotifyText.parse(msg.kind, msg.body)
            val names = namesOn(p)
            val look = if (names) {
                try {
                    withTimeoutOrNull(4_000) { look(msg, p) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else null
            Notifier.show(context, msg, look, p, if (names) pageTitle(msg, p) else null, accountTag())
        }
    }

    /**
     * Darf die Mitteilung Namen tragen? Ja, wenn der Server einen Namen genannt hat (dann ist "Namen zeigen" fuer diesen
     * Weg an, saga/push.py with_names) oder die Einstellung "Namen zeigen" (Einstellungen > Mitteilungen; Server-Standard
     * an) zuletzt an war. Unbekannt -> aus (neutral).
     */
    private fun namesOn(p: NotifyText.Parsed?): Boolean =
        NotifyText.named(p) || (info.value?.settings?.names ?: uiPrefs.getBoolean(namesKey(), false))

    /** Seitentitel (lokal bekannt) bzw. Person fuer Kommentar-/Erwaehnungs-Mitteilungen; null = Titel des Servers */
    private fun pageTitle(m: PushMessage, p: NotifyText.Parsed?): String? {
        if (!m.kind.startsWith("comment.") && m.kind != "page.mention") return null
        val id = (io.github.yggdrasil_sh.saga.live.NotifLogic.target(m.url) as? io.github.yggdrasil_sh.saga.live.NotifLogic.Target.Page)?.id
        val t = id?.let { repo.page(it)?.title }?.trim()?.takeIf { it.isNotEmpty() }
        return t ?: p?.name
    }

    /**
     * Design-Runde 4 (CH-04): Chat-Name/Gegenueber aus dem lokalen Stand - nur wenn Namen erlaubt sind (namesOn); nie
     * Nachrichteninhalte und nie der Ende-zu-Ende-verschluesselte Titel freier Gruppen. Nachbesserung: Chat-Liste im
     * Hintergrund noch nicht geladen -> GET api/chats (ohne Entschluesseln); Person = Absender (1:1: Gegenueber, Gruppe:
     * Name aus dem Satz des Servers), Konversationstitel nur bei Gruppen.
     */
    private suspend fun look(m: PushMessage, p: NotifyText.Parsed?): ChatLook? {
        if (!m.kind.startsWith("chat.")) return null
        val id = NotifyRoute.chatId(m.url) ?: return null
        val c = repo.chat.chats.value.firstOrNull { it.id == id } ?: fetchChats()?.firstOrNull { it.id == id } ?: return null
        val title = try { io.github.yggdrasil_sh.saga.chat.ChatLogic.displayName(c, null) } catch (_: Exception) { return null }
        if (title.isBlank()) return null
        val dm = c.kind == "dm"
        return ChatLook(title, !dm, if (dm) title else p?.name, id)
    }

    /** Chat-Liste ohne den Chat-Dienst (Hintergrund): nur Kennung, Art, Name, Mitglieder; raeumt alte Shortcuts weg */
    private suspend fun fetchChats(): List<io.github.yggdrasil_sh.saga.chat.ChatSummary>? = try {
        val r = withContext(Dispatchers.Default) {
            json.decodeFromString(io.github.yggdrasil_sh.saga.chat.ChatList.serializer(), repo.api.get("api/chats"))
        }
        ChatShortcuts.prune(context, accountTag(), r.chats.map { it.id }.toSet())
        r.chats
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- Einstellungen, Geraete, Test

    suspend fun putSettings(body: JsonObject) {
        info.value = json.decodeFromString(PushInfo.serializer(), repo.api.request("PUT", "api/push/settings", body))
    }

    suspend fun test(id: Long): PushTest = json.decodeFromString(PushTest.serializer(), repo.api.post("api/push/subscriptions/$id/test"))

    suspend fun removeDevice(id: Long) {
        repo.api.delete("api/push/subscriptions/$id")
        if (local()?.subId == id) {
            local()?.let { if (it.distributor == PushLogic.STREAM) PushStreamService.stop(context) else UnifiedPush.unregister(context, it.distributor, it.token) }
            saveLocal(null)
            state.value = PushState.OFF
        }
        load()
    }
}

/** Mitteilung entschluesseln bzw. lesen (rein, fuer Tests) */
object PushParse {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    fun parse(bytes: ByteArray, keys: PushKeys?): PushMessage? {
        // ohne Schluessel angemeldet: Klartext-JSON (nie mit Namen). Mit Schluesseln nur Entschluesseltes annehmen -
        // sonst koennte jeder, der die Verteiler-Adresse kennt, beliebige Mitteilungen unterschieben.
        val plain = try {
            if (keys != null) WebPushCrypto.decrypt(bytes, keys) else bytes
        } catch (_: Exception) {
            return null
        }
        return try {
            json.decodeFromString(PushMessage.serializer(), String(plain, Charsets.UTF_8)).takeIf { it.body.isNotEmpty() || it.title.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}

/** HMAC-Signatur fuer Mitteilungs-Links (BUG-110), rein (Tests) */
object NotifySig {
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun sign(key: ByteArray, url: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(url.toByteArray(Charsets.UTF_8)))
    }

    fun check(key: ByteArray, url: String, sig: String?): Boolean =
        sig != null && java.security.MessageDigest.isEqual(sign(key, url).toByteArray(), sig.toByteArray())
}

/** Anzeige in der Mitteilungszentrale: gleicher tag = gleiche Unterhaltung/Seite/Chat (ersetzt); Tippen oeffnet url */
object Notifier {
    const val CHANNEL = "saga_push"
    const val EXTRA_URL = "saga_url"
    const val EXTRA_SIG = "saga_sig"

    /**
     * MainActivity ist exportiert: saga_url nur aus eigenen Mitteilungen annehmen (BUG-110). Signatur = HMAC der url
     * mit einem zufaelligen Schluessel dieser Installation (private Einstellungen, fuer andere Apps unlesbar).
     */
    private fun key(context: Context): ByteArray {
        val p = context.getSharedPreferences("saga_ui", Context.MODE_PRIVATE)
        p.getString("notify_key", null)?.let { return NotifySig.unhex(it) }
        val k = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        p.edit().putString("notify_key", NotifySig.hex(k)).apply()
        return k
    }

    fun sign(context: Context, url: String): String = NotifySig.sign(key(context), url)

    fun verify(context: Context, url: String, sig: String?): Boolean = NotifySig.check(key(context), url, sig)

    /** Bisheriger Einstieg (andere Aufrufer): legt alle Kanaele an (Design-Runde 4: getrennt, siehe NotifyChannels) */
    fun channel(context: Context) = NotifyChannels.ensure(context)

    /** Gespraechseinheit (group_key) einer gezeigten Mitteilung - fuer "anderswo gelesen" */
    const val EXTRA_GROUP = "saga_group"
    private const val ID = 1
    private const val ID_SUMMARY = 0
    private const val MAX_LINES = 6

    /** Zahl der zusammengefassten Nachrichten derselben Person (Extras der aktiven Mitteilung) */
    private const val EXTRA_N = "saga_n"
    private const val EXTRA_SENDER = "saga_sender"

    /**
     * Design-Runde 4 (CH-04): Chat-Mitteilungen als MessagingStyle (Person = Absender mit Initialen; Konversationstitel nur
     * bei Gruppen; Namen nur aus dem lokalen Stand und nur, wenn Namen erlaubt sind - look). Ohne Namen neutral ("Neue
     * Nachricht", ohne Person). Je Kanal eine System-Gruppe mit Sammel-Mitteilung; Aktion "Als gelesen markieren"
     * (NotifyReadReceiver, ohne App-Start). Keine "Antworten"-Aktion (Entscheidung offen).
     *
     * Nachbesserung: Saetze in der App-Sprache (NotifyText/localize; unbekannte Saetze wie vom Server), Titel bei
     * Kommentaren = Seitentitel bzw. Person ([title]), Unterhaltungs-Shortcut je Chat (ChatShortcuts), Wegwischen zaehlt
     * die Sammel-Mitteilung neu (deleteIntent), Konto-Kennung in den Aktionen ([account]).
     */
    fun show(context: Context, m: PushMessage, look: ChatLook? = null, parsed: NotifyText.Parsed? = null, title: String? = null, account: String = "") {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotifyChannels.ensure(context)
        val channel = NotifyChannels.forKind(m.kind)
        val group = NotifyChannels.group(channel)
        val tag = m.tag.ifEmpty { "saga" }
        val key = NotifyRoute.groupKey(m.kind, tag, m.url)
        val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_URL, m.url).putExtra(EXTRA_SIG, sign(context, m.url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, tag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val whenMs = if (m.ts > 0) m.ts * 1000 else System.currentTimeMillis()
        val chat = channel == NotifyChannels.CHATS
        val text = localize(context, m.kind, parsed) ?: m.body
        val appName = context.getString(R.string.app_name)
        val extras = android.os.Bundle().apply { if (key != null) putString(EXTRA_GROUP, key) }
        val prev = active(context, tag)
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NotifyDesign.ACCENT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setGroup(group)
            .setCategory(
                when (channel) {
                    NotifyChannels.CHATS -> NotificationCompat.CATEGORY_MESSAGE
                    NotifyChannels.CALLS -> NotificationCompat.CATEGORY_MISSED_CALL
                    NotifyChannels.TASKS -> NotificationCompat.CATEGORY_REMINDER
                    else -> NotificationCompat.CATEGORY_SOCIAL
                },
            )
            .setWhen(whenMs).setShowWhen(true)
        var sensitive = title != null
        if (chat && look != null) {
            val mention = m.kind == "chat.mention"
            val senderName = look.sender ?: look.title
            val icon = androidx.core.graphics.drawable.IconCompat.createWithBitmap(NotifyDesign.initials(senderName))
            val sender = androidx.core.app.Person.Builder().setName(senderName).setKey("u:$senderName").setIcon(icon).build()
            // gleiche Person wie zuletzt -> eine Zeile mit Zahl statt fuenfmal "Neue Nachricht"
            val sameSender = !mention && prev?.extras?.getString(EXTRA_SENDER) == senderName
            val n = if (sameSender) (prev?.extras?.getInt(EXTRA_N, 1) ?: 1) + 1 else 1
            val line = when {
                mention -> context.getString(R.string.r4n_msg_mention)
                n > 1 -> context.resources.getQuantityString(R.plurals.r4n_g_messages, n, n)
                else -> context.getString(R.string.r4n_msg_new)
            }
            b.setStyle(messaging(context, prev, sameSender, look, sender, line, whenMs))
            b.setContentTitle(if (look.group) look.title else senderName).setContentText(line)
            extras.putString(EXTRA_SENDER, if (mention) null else senderName)
            extras.putInt(EXTRA_N, n)
            if (look.chat > 0 && account.isNotEmpty()) {
                ChatShortcuts.push(context, account, look.chat, m.url, look.title, sender, if (look.group) null else icon)?.let { sid ->
                    b.setShortcutId(sid).setLocusId(androidx.core.content.LocusIdCompat(sid))
                }
            }
            sensitive = true
        } else if (chat) {
            // ohne Namen: neutral - keine Person, kein "Chats" als Absender; mehrere Nachrichten zaehlen
            val n = if (m.kind == "chat.message") (prev?.extras?.getInt(EXTRA_N, 0) ?: 0) + 1 else 1
            val line = when {
                m.kind == "chat.mention" -> text
                n > 1 -> context.resources.getQuantityString(R.plurals.r4n_g_messages, n, n)
                else -> context.getString(R.string.r4n_msg_new)
            }
            extras.putInt(EXTRA_N, n)
            b.setContentTitle(line)
        } else {
            b.setContentTitle(title ?: m.title.ifEmpty { appName })
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        b.addExtras(extras)
        if (sensitive) {
            // Sperrbildschirm: lokal ergaenzte Namen/Seitentitel nur in der privaten Fassung
            b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
                NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setColor(NotifyDesign.ACCENT)
                    .setContentTitle(appName).setContentText(context.resources.getQuantityString(R.plurals.r4n_summary, 1, 1))
                    .setWhen(whenMs).setShowWhen(true).build(),
            )
        }
        if (NotifyRoute.canMarkRead(m.kind, m.url)) {
            val read = Intent(context, NotifyReadReceiver::class.java).setAction(NotifyReadReceiver.ACTION)
                .putExtra(NotifyReadReceiver.EXTRA_TAG, tag).putExtra(NotifyReadReceiver.EXTRA_URL, m.url)
                .putExtra(NotifyReadReceiver.EXTRA_ACCOUNT, account)
                .apply { if (key != null) putExtra(NotifyReadReceiver.EXTRA_GROUP, key) }
            val rpi = PendingIntent.getBroadcast(context, ("read-" + tag).hashCode(), read, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(
                NotificationCompat.Action.Builder(R.drawable.si_check, context.getString(R.string.r4n_mark_read), rpi)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        // Wegwischen: Sammel-Mitteilung neu zaehlen (Pruefer offen #5)
        val del = Intent(context, NotifyReadReceiver::class.java).setAction(NotifyReadReceiver.ACTION_DISMISSED)
        b.setDeleteIntent(PendingIntent.getBroadcast(context, 0x5a6a, del, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        try {
            NotificationManagerCompat.from(context).notify(tag, ID, b.build())
        } catch (_: SecurityException) {
        }
        refreshSummaries(context)
    }

    /** Aktive Mitteilung dieser Unterhaltung (fuer Zusammenfassen), sonst null */
    private fun active(context: Context, tag: String): android.app.Notification? = try {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications?.firstOrNull { it.tag == tag && it.id == ID }?.notification
    } catch (_: Exception) {
        null
    }

    /**
     * Satz in der App-Sprache aus dem erkannten Satz des Servers (NotifyText); null = unbekannt (Satz des Servers bleibt)
     */
    fun localize(context: Context, kind: String, p: NotifyText.Parsed?): String? {
        if (p == null) return null
        val r = context.resources
        val name = p.name ?: ""
        val n = p.n ?: 1
        return when (p.sentence) {
            NotifyText.Sentence.COMMENT_FROM -> context.getString(R.string.r4n_t_comment_from, name)
            NotifyText.Sentence.COMMENTS_N -> r.getQuantityString(R.plurals.r4n_t_comments_n, n, n)
            NotifyText.Sentence.REPLY_FROM -> context.getString(R.string.r4n_t_reply_from, name)
            NotifyText.Sentence.REPLIES_N -> r.getQuantityString(R.plurals.r4n_t_replies_n, n, n)
            NotifyText.Sentence.COMMENT_MENTION_FROM -> context.getString(R.string.r4n_t_comment_mention_from, name)
            NotifyText.Sentence.PAGE_MENTION_FROM -> context.getString(R.string.r4n_t_page_mention_from, name)
            NotifyText.Sentence.CHAT_FROM -> context.getString(R.string.r4n_t_chat_from, name)
            NotifyText.Sentence.CHAT_MENTION_FROM -> context.getString(R.string.r4n_t_chat_mention_from, name)
            NotifyText.Sentence.MISSED_FROM -> context.getString(R.string.r4n_t_missed_from, name)
            NotifyText.Sentence.MISSED_N_FROM -> r.getQuantityString(R.plurals.r4n_t_missed_n_from, n, n, name)
            NotifyText.Sentence.MISSED_N -> r.getQuantityString(R.plurals.r4n_g_calls, n, n)
            NotifyText.Sentence.DB_FROM -> context.getString(R.string.r4n_t_db_from, name)
            NotifyText.Sentence.DB_N_FROM -> r.getQuantityString(R.plurals.r4n_t_db_n_from, n, n, name)
            NotifyText.Sentence.COMMENT -> context.getString(R.string.r4n_t_comment)
            NotifyText.Sentence.REPLY -> context.getString(R.string.r4n_t_reply)
            NotifyText.Sentence.MENTION -> context.getString(R.string.r4n_t_mention)
            NotifyText.Sentence.DB -> context.getString(R.string.r4n_t_db)
            NotifyText.Sentence.TASKS -> context.getString(R.string.r4n_t_tasks)
            NotifyText.Sentence.MISSED -> context.getString(R.string.r4n_t_missed)
            NotifyText.Sentence.CHAT -> context.getString(R.string.r4n_msg_new)
            NotifyText.Sentence.CHAT_MENTION -> context.getString(R.string.r4n_t_chat_mention)
            NotifyText.Sentence.SHARE -> context.getString(R.string.r4n_t_share)
            NotifyText.Sentence.ROLE -> context.getString(R.string.r4n_t_role)
            NotifyText.Sentence.TEST -> context.getString(R.string.r4n_t_test)
        }
    }

    /** Bisherige Nachrichten dieser Unterhaltung (aktive Mitteilung) plus die neue; gleiche Person: letzte Zeile ersetzt */
    private fun messaging(
        context: Context, prevN: android.app.Notification?, replaceLast: Boolean, look: ChatLook,
        sender: androidx.core.app.Person, line: String, whenMs: Long,
    ): NotificationCompat.MessagingStyle {
        val me = androidx.core.app.Person.Builder().setName(context.getString(R.string.r4n_you)).setKey("me").build()
        val prev = try {
            prevN?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
        } catch (_: Exception) {
            null
        }
        val style = NotificationCompat.MessagingStyle(me)
        if (look.group) style.setConversationTitle(look.title)
        style.setGroupConversation(look.group)
        val old = prev?.messages.orEmpty().let { if (replaceLast) it.dropLast(1) else it }
        old.takeLast(MAX_LINES - 1).forEach { style.addMessage(it) }
        style.addMessage(NotificationCompat.MessagingStyle.Message(line, whenMs, sender))
        return style
    }

    /** Sammel-Mitteilung je System-Gruppe (Zahl der Mitteilungen); ohne Mitglieder weg */
    private fun summary(context: Context, group: String, channel: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = try {
            nm.activeNotifications.count { it.id == ID && it.notification.group == group }
        } catch (_: Exception) {
            return
        }
        val stag = "summary-$group"
        if (n == 0) {
            nm.cancel(stag, ID_SUMMARY)
            return
        }
        val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, stag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.resources.getQuantityString(R.plurals.r4n_summary, n, n)
        val s = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NotifyDesign.ACCENT)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(text))
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(stag, ID_SUMMARY, s)
        } catch (_: SecurityException) {
        }
    }

    /** Eine Mitteilung entfernen (Aktion "Als gelesen markieren") und die Sammel-Mitteilungen nachziehen */
    fun cancel(context: Context, tag: String) {
        NotificationManagerCompat.from(context).cancel(tag, ID)
        refreshSummaries(context)
    }

    /** Glocken-Zeilen anderswo gelesen (Web, anderes Geraet, Chat geoeffnet): passende System-Mitteilungen entfernen */
    fun cancelRead(context: Context, keys: Set<String>, all: Boolean) {
        if (keys.isEmpty() && !all) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.activeNotifications.filter { it.id == ID && it.notification.channelId in NotifyChannels.ALL }
                .filter { all || it.notification.extras?.getString(EXTRA_GROUP) in keys }
                .forEach { nm.cancel(it.tag, ID) }
        } catch (_: Exception) {
        }
        refreshSummaries(context)
    }

    /** Sammel-Mitteilungen aller Kanaele nachziehen (auch nach Wegwischen, NotifyReadReceiver) */
    fun refreshSummaries(context: Context) {
        for (ch in NotifyChannels.ALL) summary(context, NotifyChannels.group(ch), ch)
    }
}

/** Lokaler Stand zu einem Chat fuer die Mitteilung (nur mit "Namen zeigen"): Anzeigename, Gruppe, Absender, Chat */
data class ChatLook(val title: String, val group: Boolean, val sender: String?, val chat: Long = 0)

/** Antworten des Verteilers (UnifiedPush-Connector) */
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SagaApplication ?: return
        val push = app.repo.push
        val token = intent.getStringExtra(UnifiedPush.EXTRA_TOKEN) ?: return
        when (intent.action) {
            UnifiedPush.ACTION_NEW_ENDPOINT -> {
                val ep = intent.getStringExtra(UnifiedPush.EXTRA_ENDPOINT) ?: return
                val pending = goAsync()
                app.appScope.launch {
                    try {
                        push.onNewEndpoint(token, ep)
                    } finally {
                        pending.finish()
                    }
                }
            }
            UnifiedPush.ACTION_MESSAGE -> {
                val bytes = intent.getByteArrayExtra(UnifiedPush.EXTRA_BYTES) ?: intent.getStringExtra(UnifiedPush.EXTRA_MESSAGE)?.toByteArray() ?: return
                val work = push.onMessage(token, bytes)
                push.local()?.let { UnifiedPush.ack(context, it.distributor, token, intent.getStringExtra(UnifiedPush.EXTRA_ID)) }
                if (work != null) {
                    // Anruf: Prozess wach halten, bis der Zustand geholt ist und es klingelt (hoechstens ~9 s); 2.5: durfte der
                    // Klingel-Dienst aus dem Hintergrund nicht starten, weiter offen halten, solange es klingelt (<= 55 s)
                    val pending = goAsync()
                    val at = System.currentTimeMillis()
                    val fg = (intent.flags and Intent.FLAG_RECEIVER_FOREGROUND) != 0       // 10 s Frist statt 60 s
                    app.appScope.launch {
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(if (fg) 8_000 else 9_000) { work.join() }
                            app.repo.calls.holdWhileRinging(at, fg)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
            UnifiedPush.ACTION_REGISTRATION_FAILED -> push.onFailed(token)
            UnifiedPush.ACTION_UNREGISTERED -> push.onUnregistered(token)
        }
    }
}
