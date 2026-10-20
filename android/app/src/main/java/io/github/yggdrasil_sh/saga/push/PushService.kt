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
        info.value = null
        state.value = PushState.OFF
        error.value = null
    }

    // ---------------------------------------------------------------- Ein/Aus

    /** Einschalten: Schluessel erzeugen, beim Verteiler anmelden; die Adresse kommt per NEW_ENDPOINT (PushReceiver) */
    suspend fun enable(d: Distributor, replaces: Long? = null) {
        error.value = null
        val l = withContext(Dispatchers.Default) {
            val k = WebPushCrypto.generate()
            PushLocal(d.pkg, UUID.randomUUID().toString(), null, null, WebPushCrypto.b64u(k.privatePkcs8), k.p256dh, k.authB64, replaces = replaces)
        }
        withContext(Dispatchers.IO) { saveLocal(l) }
        state.value = PushState.REGISTERING
        UnifiedPush.register(context, d.pkg, l.token, info.value?.server?.vapidPublic, context.getString(R.string.app_name))
    }

    suspend fun disable() {
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
        Notifier.show(context, msg)
        return null
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

    fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.push_title), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun show(context: Context, m: PushMessage) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        channel(context)
        val tag = m.tag.ifEmpty { "saga" }
        val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_URL, m.url).putExtra(EXTRA_SIG, sign(context, m.url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, tag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(m.title.ifEmpty { context.getString(R.string.app_name) })
            .setContentText(m.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setGroup(if (m.kind.startsWith("chat")) "saga-chat" else "saga")
            .setCategory(if (m.kind.startsWith("chat")) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .apply { if (m.ts > 0) setWhen(m.ts * 1000).setShowWhen(true) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(tag, 1, n)
        } catch (_: SecurityException) {
        }
    }
}

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
                    // Anruf: Prozess wach halten, bis der Zustand geholt ist und es klingelt (hoechstens ~9 s)
                    val pending = goAsync()
                    app.appScope.launch {
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(9_000) { work.join() }
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
