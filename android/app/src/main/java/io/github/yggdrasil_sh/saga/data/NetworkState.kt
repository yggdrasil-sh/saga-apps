package io.github.yggdrasil_sh.saga.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * Design-Runde Android 1 (NAV-02, NAV-06): Netz vorhanden? Bewusst nur NET_CAPABILITY_INTERNET, nicht VALIDATED -
 * ein Saga-Server im Heimnetz ohne Internet-Zugang ist trotzdem erreichbar.
 */
object NetworkState {
    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        return try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: SecurityException) {
            true                                                    // ohne Berechtigung: nicht als offline melden
        }
    }

    /** Laufend: true = Netz da (Standardnetz mit Internet-Faehigkeit) */
    fun flow(ctx: Context): Flow<Boolean> = callbackFlow {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(online(ctx)) }
            override fun onLost(network: Network) { trySend(online(ctx)) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(online(ctx)) }
        }
        trySend(online(ctx))
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (_: Exception) {
        }
        awaitClose {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
    }.distinctUntilChanged()
}

/** Offline-Leiste (NAV-02): wann gilt die App als offline, wie lange wird entprellt */
object OfflineLogic {
    /** erst nach dieser Zeit ohne Verbindung zeigen (kurze Funkloecher, Aufbau des Live-Kanals) */
    const val DEBOUNCE_MS = 3_000L

    /** Kein Netz, oder der Server kennt den Live-Kanal und der steht nicht */
    fun offline(netOnline: Boolean, liveSupported: Boolean, liveConnected: Boolean): Boolean =
        !netOnline || (liveSupported && !liveConnected)

    /**
     * Uhrzeit des gezeigten Stands: der letzte Moment mit Verbindung (Wanduhr, ms); null = in dieser Sitzung nie
     * verbunden (dann Text ohne Uhrzeit). online = jetzt verbunden -> Stempel wandert mit.
     */
    fun stamp(previous: Long?, online: Boolean, nowMs: Long): Long? = if (online) nowMs else previous

    /** Gleicher Kalendertag? Sonst zeigt die Leiste Datum und Uhrzeit */
    fun sameDay(a: Long, b: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean =
        java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == java.time.Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}
