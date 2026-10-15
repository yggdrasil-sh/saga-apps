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
data class PushSubscribed(val id: Long = 0, val active: Boolean = false)

@Serializable
data class PushTest(val ok: Boolean = false, val result: String = "", val error: String? = null)

/** Inhalt einer Mitteilung (nie Seitentitel/Inhalte - nur ein Satz und der Link) */
@Serializable
data class PushMessage(val title: String = "", val body: String = "", val url: String = "/", val tag: String = "", val kind: String = "", val ts: Long = 0)

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
)

enum class PushState { OFF, REGISTERING, ON, ERROR }

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
            if (l?.endpoint != null && i.server.enabled && i.devices.none { it.id == l.subId }) subscribe(l.endpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404) supported.value = false else error.value = repo.friendly(e)
        } catch (e: Exception) {
            error.value = repo.friendly(e)
        }
    }

    fun clear() {
        info.value = null
        state.value = PushState.OFF
        error.value = null
    }

    // ---------------------------------------------------------------- Ein/Aus

    /** Einschalten: Schluessel erzeugen, beim Verteiler anmelden; die Adresse kommt per NEW_ENDPOINT (PushReceiver) */
    suspend fun enable(d: Distributor) {
        error.value = null
        val l = withContext(Dispatchers.Default) {
            val k = WebPushCrypto.generate()
            PushLocal(d.pkg, UUID.randomUUID().toString(), null, null, WebPushCrypto.b64u(k.privatePkcs8), k.p256dh, k.authB64)
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
            UnifiedPush.unregister(context, l.distributor, l.token)
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
            withContext(Dispatchers.IO) { saveLocal(l.copy(endpoint = endpoint, subId = r.id)) }
            state.value = PushState.ON
            error.value = null
            info.value = json.decodeFromString(PushInfo.serializer(), repo.api.get("api/push"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = PushState.ERROR
            error.value = repo.friendly(e)
        }
    }

    fun onFailed(token: String) {
        if (local()?.token != token) return
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

    /** Mitteilung vom Verteiler: entschluesseln (aes128gcm) bzw. Klartext-JSON, dann anzeigen */
    fun onMessage(token: String, bytes: ByteArray) {
        val l = local() ?: return
        if (l.token != token) return
        val msg = PushParse.parse(bytes, keysOf(l)) ?: return
        Notifier.show(context, msg)
    }

    // ---------------------------------------------------------------- Einstellungen, Geraete, Test

    suspend fun putSettings(body: JsonObject) {
        info.value = json.decodeFromString(PushInfo.serializer(), repo.api.request("PUT", "api/push/settings", body))
    }

    suspend fun test(id: Long): PushTest = json.decodeFromString(PushTest.serializer(), repo.api.post("api/push/subscriptions/$id/test"))

    suspend fun removeDevice(id: Long) {
        repo.api.delete("api/push/subscriptions/$id")
        if (local()?.subId == id) {
            local()?.let { UnifiedPush.unregister(context, it.distributor, it.token) }
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

/** Anzeige in der Mitteilungszentrale: gleicher tag = gleiche Unterhaltung/Seite/Chat (ersetzt); Tippen oeffnet url */
object Notifier {
    const val CHANNEL = "saga_push"
    const val EXTRA_URL = "saga_url"

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
            .putExtra(EXTRA_URL, m.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
                push.onMessage(token, bytes)
                push.local()?.let { UnifiedPush.ack(context, it.distributor, token, intent.getStringExtra(UnifiedPush.EXTRA_ID)) }
            }
            UnifiedPush.ACTION_REGISTRATION_FAILED -> push.onFailed(token)
            UnifiedPush.ACTION_UNREGISTERED -> push.onUnregistered(token)
        }
    }
}
