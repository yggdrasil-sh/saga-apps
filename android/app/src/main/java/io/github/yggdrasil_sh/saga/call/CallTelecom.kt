package io.github.yggdrasil_sh.saga.call

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import io.github.yggdrasil_sh.saga.SagaApplication

/**
 * 2.5 Gjallarhorn (P12): Gruppenanruf ueber einen Knoten beim System als selbstverwalteter Anruf melden (Telecom,
 * MANAGE_OWN_CALLS): andere Telefon-Apps und Bluetooth/Auto sehen den laufenden Anruf, "Auflegen" am Headset beendet
 * ihn, ein eingehender GSM-Anruf haelt ihn nicht still an. Ton-Weg und Klingeln bleiben wie bisher (CallManager,
 * Push-Klingeln ueber CallNotify); scheitert Telecom (Geraet ohne Telecom, keine Erlaubnis), laeuft der Anruf genauso -
 * nur ohne Meldung ans System. 1:1- und Mesh-Anrufe bleiben unveraendert.
 */
@Suppress("DEPRECATION")
object CallTelecom {
    private const val ACCOUNT = "saga-group"
    private const val EXTRA_TITLE = "saga_title"

    @Volatile var conn: SagaConnection? = null
    @Volatile private var wantActive = false
    @Volatile private var title = ""

    private fun handle(ctx: Context) = PhoneAccountHandle(ComponentName(ctx, SagaConnectionService::class.java), ACCOUNT)

    private fun tm(ctx: Context): TelecomManager? =
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM) &&
            !ctx.packageManager.hasSystemFeature("android.software.telecom")
        ) null else ctx.getSystemService(TelecomManager::class.java)

    /** Laufenden Gruppenanruf melden (ausgehend, selbstverwaltet) */
    fun start(ctx: Context, name: String, callId: String, video: Boolean) {
        end()
        wantActive = false
        title = name
        try {
            val t = tm(ctx) ?: return
            val h = handle(ctx)
            t.registerPhoneAccount(
                PhoneAccount.builder(h, "Saga").setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED or PhoneAccount.CAPABILITY_VIDEO_CALLING).build(),
            )
            if (!t.isOutgoingCallPermitted(h)) return
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h)
                putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, if (video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
                putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_TITLE, name) })
            }
            t.placeCall(Uri.fromParts("saga", callId, null), extras)
        } catch (e: Exception) {
            Log.w("SagaCall", "telecom start: ${e.message}")
        }
    }

    /** Verbunden (erste Verbindung zum Knoten steht) */
    fun active() {
        wantActive = true
        try {
            conn?.setActive()
        } catch (_: Exception) {
        }
    }

    /** Anruf vorbei */
    fun end() {
        val c = conn ?: return
        conn = null
        c.close()
    }

    internal fun created(c: SagaConnection, req: ConnectionRequest?) {
        conn = c
        val t = req?.extras?.getString(EXTRA_TITLE) ?: title
        if (t.isNotEmpty()) c.setCallerDisplayName(t, TelecomManager.PRESENTATION_ALLOWED)
        if (wantActive) c.setActive() else c.setDialing()
    }
}

/** Selbstverwalteter Anruf: Auflegen vom System (Headset, Auto, Uhr) beendet den Anruf in Saga */
class SagaConnection(private val app: Context) : Connection() {
    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_MUTE
        audioModeIsVoip = true
    }

    private var closed = false

    internal fun close() {
        if (closed) return
        closed = true
        try {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
        } catch (_: Exception) {
        }
    }

    private fun hangup() {
        if (closed) return
        (app as? SagaApplication)?.repo?.calls?.hangup()      // beendet ueber CallTelecom.end() auch diese Verbindung
        if (CallTelecom.conn === this) CallTelecom.conn = null
        close()
    }

    override fun onDisconnect() = hangup()
    override fun onAbort() = hangup()
}

class SagaConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(acct: PhoneAccountHandle?, req: ConnectionRequest?): Connection {
        val c = SagaConnection(applicationContext)
        req?.address?.let { c.setAddress(it, TelecomManager.PRESENTATION_ALLOWED) }
        CallTelecom.created(c, req)
        return c
    }

    override fun onCreateOutgoingConnectionFailed(acct: PhoneAccountHandle?, req: ConnectionRequest?) {
        Log.w("SagaCall", "telecom: Anruf nicht gemeldet")
    }
}
