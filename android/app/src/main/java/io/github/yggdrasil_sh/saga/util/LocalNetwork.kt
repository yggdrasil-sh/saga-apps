package io.github.yggdrasil_sh.saga.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.flow.first

/**
 * Android 17 "Local network protection": Apps mit targetSdk 37 erreichen Adressen im lokalen Netz (192.168.x.x,
 * 10.x, fd00::/8, *.local ...) nur mit der Laufzeit-Berechtigung ACCESS_LOCAL_NETWORK (Gruppe "Geraete in der
 * Naehe"). Ohne sie laufen Verbindungen still in eine Zeitueberschreitung - typisch fuer selbst gehostete Server,
 * deren Name im Heimnetz auf eine private Adresse zeigt (Split-DNS). DNS-Anfragen selbst sind erlaubt.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    /** Android 17 = API 37 */
    const val FIRST_SDK = 37

    fun applies(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= FIRST_SDK

    fun granted(context: Context): Boolean =
        !applies() || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Private/lokale Adresse (nicht Loopback: 127.0.0.1/::1 bleibt ohne Berechtigung erreichbar) */
    fun isLocalAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isAnyLocalAddress) return false
        if (a.isSiteLocalAddress || a.isLinkLocalAddress) return true            // 10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10, fec0::/10
        val b = a.address
        return when (a) {
            is Inet4Address -> {
                val o0 = b[0].toInt() and 0xff
                val o1 = b[1].toInt() and 0xff
                o0 == 100 && o1 in 64..127                                          // 100.64/10 (CGNAT, z. B. Tailscale)
            }
            is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc                      // fc00::/7 (Unique Local Address)
            else -> false
        }
    }

    /** Host aus einer Server-Adresse ("https://saga.example.org:8443") */
    fun hostOf(server: String): String? = try {
        URI(server.trim()).host?.trim('[', ']')?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    /**
     * Liegt der Server im lokalen Netz? Blockiert (DNS) - nur im Hintergrund aufrufen. Unbekannt (DNS-Fehler) = false:
     * dann zeigt die normale Fehlermeldung, was los ist.
     */
    fun isLocalServer(server: String, resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }): Boolean {
        val host = hostOf(server) ?: return false
        if (host.lowercase().endsWith(".local")) return true
        return try {
            resolve(host).any { isLocalAddress(it) }
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Anmelden mit Server im lokalen Netz: auf die Antwort der Rueckfrage warten, statt sofort einen Fehler zu zeigen.
 * ask() vor dem Zeigen der Rueckfrage, answer() aus der Rueckfrage; await() liefert true = erlaubt.
 */
class LocalNetworkGate {
    private val answer = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)

    fun ask() {
        answer.value = null
    }

    fun answer(granted: Boolean) {
        answer.value = granted
    }

    suspend fun await(): Boolean = answer.first { it != null } == true
}
