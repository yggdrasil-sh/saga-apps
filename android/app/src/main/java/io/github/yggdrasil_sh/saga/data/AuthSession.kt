package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.auth.PairTarget
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Anmelde-Aufrufe an einen ausdruecklich genannten Server (fuer Tests austauschbar) */
interface AuthApi {
    suspend fun pair(server: String, code: String, device: String, wantAppKey: Boolean): LoginResult
    suspend fun exchange(server: String, code: String, verifier: String, device: String, wantAppKey: Boolean): LoginResult
    suspend fun forget(server: String, deviceToken: String)
    /** Stabile Kennung des gerade angemeldeten Kontos (Benutzername aus /api/account); null = nicht ermittelbar */
    suspend fun username(server: String): String?
}

/**
 * Anmeldung, Kopplung und Serverwechsel. Regeln (Sicherheit, siehe Review K1):
 *  - Kopplung nur an https-Server (lokaler Testserver nur im Debug-Build)
 *  - der Server wird erst NACH erfolgreicher Anmeldung uebernommen; schlaegt sie fehl, bleibt alles wie es war
 *  - Geraete-/App-Schluessel werden an den Server gebunden, der sie ausgestellt hat
 *  - war schon jemand angemeldet: dort abmelden (Geraete-Anmeldung loeschen, nur an den alten Server) und
 *    lokale Daten leeren, bevor das neue Konto uebernommen wird
 */
class AuthSession(
    private val settings: ServerSettings,
    private val secure: SecureStore,
    private val api: AuthApi,
    private val deviceName: String,
    private val allowLocalHttp: Boolean,
    /** lokale Daten des bisherigen Kontos leeren (Seiten, Aufgaben, Entwuerfe, Cookies ...) */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clearLocal: suspend (oldServer: String) -> Unit,
) {
    /** Angemeldet = Geraete-Schluessel fuer den eingestellten Server vorhanden */
    val signedIn: Boolean get() = secure.deviceTokenFor(settings.server.value) != null

    /** Kopplungsziel pruefen und vereinheitlichen; null = nicht erlaubt (kein https, kaputte Adresse) */
    fun check(target: PairTarget): PairTarget? = PairLink.check(target, allowLocalHttp)

    suspend fun pair(target: PairTarget) {
        val t = check(target) ?: throw UiTextException(UiText.Res(R.string.login_server_https))
        val r = api.pair(t.server, t.code, deviceName, wantAppKey())
        adopt(t.server, r)
    }

    suspend fun exchange(server: String, code: String, verifier: String) {
        val r = api.exchange(server, code, verifier, deviceName, wantAppKey())
        adopt(server, r)
    }

    private fun wantAppKey(): Boolean = true

    /** Erfolgreiche Anmeldung uebernehmen - erst jetzt wird der Server gewechselt */
    private suspend fun adopt(server: String, r: LoginResult) {
        val token = r.deviceToken?.takeIf { it.startsWith("hkd_") }
            ?: throw UiTextException(UiText.Res(R.string.login_no_device_token))
        // Kennung fuer die Entwuerfe, solange die neue Sitzung (Cookie) noch da ist; der Name kann sich spaeter aendern
        val user = try {
            api.username(server)?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        signOutLocal(forgetRemote = true)
        withContext(io) { secure.setCredentials(server, token, r.appKey?.takeIf { it.startsWith("hk_") }) }
        settings.setServer(server)
        settings.setAccount(r.name.orEmpty())
        settings.setAccountKey(if (user != null) AccountKey.uid(user) else AccountKey.name(r.name.orEmpty()))
    }

    /**
     * Bisherige Anmeldung beenden: Geraete-Anmeldung beim ALTEN Server loeschen (nur dorthin geht der Schluessel),
     * Schluessel und lokale Daten entfernen. Fehler beim Server werden ignoriert (offline).
     */
    suspend fun signOutLocal(forgetRemote: Boolean) {
        val (oldServer, tok) = withContext(io) { secure.tokenServer.orEmpty() to secure.deviceToken }
        if (forgetRemote && tok != null && oldServer.isNotEmpty()) {
            try {
                api.forget(oldServer, tok)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        val had = tok != null || settings.account.value.isNotEmpty()
        withContext(io) { secure.clearCredentials() }
        if (had || oldServer.isNotEmpty()) clearLocal(oldServer.ifEmpty { settings.server.value })
        settings.setAccount("")
        settings.setAccountKey("")
        settings.clearRecent()
    }
}
