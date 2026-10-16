package io.github.yggdrasil_sh.saga.ui.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.util.ServerUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Profilbilder (Server 2.1): Personen-Objekte tragen "avatar" = "/api/avatar/<schluessel>?v=<version>" (relativ zum
 * eigenen Server) oder null. Bilder kommen NUR vom eigenen Server (nie Gravatar/Anmeldedienst/andere Instanzen); die
 * Adresse wird nie selbst gebaut. Laden ueber Coil mit der App-Sitzung (ApiClient.imageCallFactory: Cookie bzw.
 * stille Erneuerung); Zwischenspeicher je Adresse inkl. v (Speicher + Platte), 404 = kein Bild (bis sich v aendert).
 */
object AvatarLogic {
    /** Groessenstufen des Servers */
    private val TIERS = intArrayOf(64, 128, 256)

    /** Adressen mit 404/Fehler in diesem Prozess - nicht erneut laden, bis sich die Adresse aendert */
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Gewuenschte Kantenlaenge in Pixeln -> naechstgroessere Stufe (gleiche Adresse fuer aehnliche Groessen) */
    fun tier(px: Int): Int = TIERS.firstOrNull { it >= px } ?: TIERS.last()

    /**
     * Volle Adresse fuer GET (mit &s=<stufe>&fmt=webp) oder null: kein Bild, kein Server oder fremde/ungueltige Adresse.
     * Nur Pfade unter /api/avatar/ des eingestellten Servers - nie eine volle Adresse aus der Antwort uebernehmen.
     */
    fun url(server: String, avatar: String?, px: Int): String? {
        val a = avatar?.trim().orEmpty()
        if (server.isEmpty() || !a.startsWith("/api/avatar/") || a.contains("..") || a.contains("://")) return null
        val u = ServerUrl.absolute(server, a) + (if (a.contains('?')) "&" else "?") + "s=" + tier(px) + "&fmt=webp"
        return u.takeIf { it !in failed }
    }

    fun markFailed(url: String) {
        failed.add(url)
    }

    /** Abmelden/Serverwechsel */
    fun reset() = failed.clear()

    /** Server fuer die Adressen (SagaApplication setzt ihn) */
    @Volatile
    var server: () -> String = { "" }
}

/**
 * Rundes Profilbild ueber den Initialen: liegt passgenau auf dem Initialen-Kreis des Aufrufers (Platzhalter bis zum
 * Laden; bei null/404/Fehler bleibt nur der Platzhalter sichtbar). size = Anzeigegroesse.
 */
@Composable
fun AvatarOverlay(avatar: String?, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val url = remember(avatar, px) { AvatarLogic.url(AvatarLogic.server(), avatar, px) } ?: return
    var gone by remember(url) { mutableStateOf(false) }
    if (gone) return
    AsyncImage(
        model = url,
        contentDescription = null,                          // Name steht daneben; Bild ist Schmuck
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().clip(CircleShape),
        onError = { st ->
            // 404 = kein Bild: in diesem Prozess nicht wiederholen; Netzfehler: beim naechsten Anzeigen erneut versuchen
            val code = (st.result.throwable as? coil3.network.HttpException)?.response?.code
            if (code == 404 || code == 403) AvatarLogic.markFailed(url)
            gone = true
        },
    )
}
