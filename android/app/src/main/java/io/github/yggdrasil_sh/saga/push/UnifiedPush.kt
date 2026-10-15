package io.github.yggdrasil_sh.saga.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Ein installierter UnifiedPush-Verteiler (z. B. ntfy) */
data class Distributor(val pkg: String, val label: String)

/**
 * UnifiedPush-Protokoll (Android, Spezifikation AND_2/AND_3) ohne Google-Dienste: Anmeldung per Broadcast an die
 * Verteiler-App, Antworten kommen als Broadcast an PushReceiver (NEW_ENDPOINT, MESSAGE, UNREGISTERED,
 * REGISTRATION_FAILED). Kein FCM. Inhalte sind Ende-zu-Ende verschluesselt (Web Push, RFC 8291).
 */
object UnifiedPush {
    const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_APPLICATION = "application"
    const val EXTRA_ENDPOINT = "endpoint"
    const val EXTRA_BYTES = "bytesMessage"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_ID = "id"
    const val EXTRA_PI = "pi"
    const val EXTRA_VAPID = "vapid"
    const val EXTRA_FEATURES = "features"
    const val EXTRA_DESCRIPTION = "description"
    const val FEATURE_BYTES = "org.unifiedpush.android.distributor.feature.BYTES_MESSAGE"

    /** Installierte Verteiler (Apps mit Empfaenger fuer REGISTER), ohne diese App selbst */
    fun distributors(context: Context): List<Distributor> {
        val pm = context.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryBroadcastReceivers(Intent(ACTION_REGISTER), PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryBroadcastReceivers(Intent(ACTION_REGISTER), PackageManager.GET_RESOLVED_FILTER)
        }
        return list.mapNotNull { it.activityInfo?.packageName }.distinct().filter { it != context.packageName }.map { p ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
            } catch (_: Exception) {
                p
            }
            Distributor(p, label)
        }
    }

    /** Zuordnung der Anfrage zu dieser App (AND_3): PendingIntent, an dem der Verteiler das Paket erkennt */
    private fun pi(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent("org.unifiedpush.dummy_app_identifier"), PendingIntent.FLAG_IMMUTABLE)

    fun register(context: Context, distributor: String, token: String, vapid: String?, description: String) {
        val i = Intent(ACTION_REGISTER).setPackage(distributor)
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_APPLICATION, context.packageName)
            .putExtra(EXTRA_PI, pi(context))
            .putExtra(EXTRA_FEATURES, arrayOf(FEATURE_BYTES))
            .putExtra(EXTRA_DESCRIPTION, description)
            .putExtra(EXTRA_MESSAGE, description)
        if (!vapid.isNullOrEmpty()) i.putExtra(EXTRA_VAPID, vapid)
        context.sendBroadcast(i)
    }

    fun unregister(context: Context, distributor: String, token: String) {
        context.sendBroadcast(
            Intent(ACTION_UNREGISTER).setPackage(distributor)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_APPLICATION, context.packageName)
                .putExtra(EXTRA_PI, pi(context)),
        )
    }

    fun ack(context: Context, distributor: String, token: String, id: String?) {
        if (id.isNullOrEmpty()) return
        context.sendBroadcast(Intent(ACTION_MESSAGE_ACK).setPackage(distributor).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_ID, id))
    }
}
