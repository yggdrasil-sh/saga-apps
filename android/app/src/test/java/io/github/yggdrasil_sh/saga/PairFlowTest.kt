package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.auth.PairTarget
import io.github.yggdrasil_sh.saga.data.AccountKey
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.FileDrafts
import io.github.yggdrasil_sh.saga.data.AuthApi
import io.github.yggdrasil_sh.saga.data.AuthSession
import io.github.yggdrasil_sh.saga.data.LoginResult
import io.github.yggdrasil_sh.saga.data.MemorySecretStore
import io.github.yggdrasil_sh.saga.data.SecureStore
import io.github.yggdrasil_sh.saga.data.ServerSettings
import io.github.yggdrasil_sh.saga.data.sameServer
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeSettings(server: String = "", account: String = "") : ServerSettings {
    override val server = MutableStateFlow(server)
    override val account = MutableStateFlow(account)
    override val accountKey = MutableStateFlow(if (account.isEmpty()) "" else AccountKey.uid(account))
    var recentCleared = 0
    override suspend fun setServer(value: String) {
        server.value = value
    }
    override suspend fun setAccount(value: String) {
        account.value = value
    }
    override suspend fun setAccountKey(value: String) {
        accountKey.value = value
    }
    override suspend fun clearRecent() {
        recentCleared++
    }
}

/** Attrappe der Anmelde-Aufrufe: merkt sich, welcher Server was bekommen hat */
private class FakeAuthApi : AuthApi {
    val calls = ArrayList<String>()
    var failPair: Exception? = null
    var name = "neu"
    /** Benutzername aus /api/account; null = aelterer Server ohne Kennung */
    var username: String? = "u-neu"
    var usernameServer: String? = null

    override suspend fun pair(server: String, code: String, device: String, wantAppKey: Boolean): LoginResult {
        calls += "pair $server"
        failPair?.let { throw it }
        return LoginResult(ok = true, name = name, deviceToken = "hkd_NEU", appKey = if (wantAppKey) "hk_NEU" else null)
    }

    override suspend fun exchange(server: String, code: String, verifier: String, device: String, wantAppKey: Boolean): LoginResult {
        calls += "exchange $server"
        return LoginResult(ok = true, name = name, deviceToken = "hkd_EX", appKey = "hk_EX")
    }

    override suspend fun forget(server: String, deviceToken: String) {
        calls += "forget $server $deviceToken"
    }

    override suspend fun username(server: String): String? {
        usernameServer = server
        return username ?: throw ApiException(404, UiText.Raw("not found"))
    }
}

class PairFlowTest {
    private val code = "AbCdEfGhIjKlMnOpQrStUvWx_-12"
    private val a = "https://alt.example.org"
    private val b = "https://neu.example.org"

    private fun signedInAtA(): Triple<FakeSettings, SecureStore, FakeAuthApi> {
        val s = FakeSettings(a, "mia")
        val sec = SecureStore(MemorySecretStore())
        sec.setCredentials(a, "hkd_ALT", "hk_ALT")
        return Triple(s, sec, FakeAuthApi())
    }

    private fun session(s: ServerSettings, sec: SecureStore, api: AuthApi, allowLocal: Boolean = false, cleared: MutableList<String> = ArrayList()) =
        AuthSession(s, sec, api, "Testgeraet", allowLocal, io = Dispatchers.Unconfined, clearLocal = { cleared += it })

    @Test
    fun switchSignsOutOldServerOnlyAndBindsNewKeys() = runTest {
        val (s, sec, api) = signedInAtA()
        val cleared = ArrayList<String>()
        val auth = session(s, sec, api, cleared = cleared)
        assertTrue(auth.signedIn)
        auth.pair(PairTarget(b, code))
        // erst koppeln (beim neuen Server), dann alten Geraete-Schluessel NUR beim alten Server abmelden
        assertEquals(listOf("pair $b", "forget $a hkd_ALT"), api.calls)
        assertEquals(b, s.server.value)
        assertEquals("neu", s.account.value)
        assertEquals(b, sec.tokenServer)
        assertEquals("hkd_NEU", sec.deviceTokenFor(b))
        assertEquals("hk_NEU", sec.appKeyFor(b))
        assertNull("Schluessel gehoert nicht mehr zum alten Server", sec.deviceTokenFor(a))
        assertEquals(listOf(a), cleared)                  // lokale Daten des alten Kontos geleert
        assertEquals(1, s.recentCleared)
        assertEquals(AccountKey.uid("u-neu"), s.accountKey.value)   // Entwuerfe je stabiler Kennung, nicht je Name
        assertEquals(b, api.usernameServer)               // Kennung beim NEUEN Server erfragt
    }

    @Test
    fun accountKeyStaysWhenDisplayNameChanges() = runTest {
        val s = FakeSettings()
        val sec = SecureStore(MemorySecretStore())
        val api = FakeAuthApi().apply { name = "" }      // Kopplung meldet (noch) keinen Namen
        session(s, sec, api).pair(PairTarget(b, code))
        val key = s.accountKey.value
        val root = java.io.File("/tmp/x")
        val before = FileDrafts.dirFor(root, b, key, s.account.value)
        s.setAccount("Mia Muster")                        // Name kommt spaeter (Erneuerung) oder wird geaendert
        assertEquals(before, FileDrafts.dirFor(root, b, s.accountKey.value, s.account.value))
        assertEquals(AccountKey.uid("u-neu"), key)
    }

    @Test
    fun olderServerWithoutUsernameFreezesTheName() = runTest {
        val s = FakeSettings()
        val sec = SecureStore(MemorySecretStore())
        val api = FakeAuthApi().apply { username = null }
        session(s, sec, api).exchange(b, "code", "verifier")
        assertEquals(AccountKey.name("neu"), s.accountKey.value)
        val root = java.io.File("/tmp/x")
        val dir = FileDrafts.dirFor(root, b, s.accountKey.value, s.account.value)
        s.setAccount("Neuer Name")
        assertEquals("eingefroren: Name-Aenderung verschiebt die Entwuerfe nicht", dir, FileDrafts.dirFor(root, b, s.accountKey.value, s.account.value))
        assertEquals(FileDrafts.legacyDir(root, b, "neu"), dir)   // derselbe Ordner wie bisher -> nichts zu verschieben
    }

    @Test
    fun signOutForgetsAccountKey() = runTest {
        val (s, sec, api) = signedInAtA()
        session(s, sec, api).signOutLocal(forgetRemote = true)
        assertEquals("", s.accountKey.value)
    }

    @Test
    fun failedPairChangesNothing() = runTest {
        val (s, sec, api) = signedInAtA()
        api.failPair = ApiException(401, UiText.Raw("abgelaufen"))
        val cleared = ArrayList<String>()
        val auth = session(s, sec, api, cleared = cleared)
        try {
            auth.pair(PairTarget(b, code))
            fail("Fehler erwartet")
        } catch (_: ApiException) {
        }
        assertEquals(listOf("pair $b"), api.calls)        // kein forget, nichts an den alten Server
        assertEquals(a, s.server.value)                   // Server erst nach Erfolg uebernehmen
        assertEquals("mia", s.account.value)
        assertEquals("hkd_ALT", sec.deviceTokenFor(a))
        assertEquals("hk_ALT", sec.appKeyFor(a))
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun rejectsPlainHttpAndBrokenTargets() = runTest {
        val (s, sec, api) = signedInAtA()
        val auth = session(s, sec, api, allowLocal = false)
        for (t in listOf("http://evil.example.org", "http://127.0.0.1:18090", "evil.example.org", "https://", "https://x.org?a=1")) {
            try {
                auth.pair(PairTarget(t, code))
                fail("abgelehnt erwartet: $t")
            } catch (_: UiTextException) {
            }
        }
        assertTrue(api.calls.isEmpty())                   // nie ein Request an ein abgelehntes Ziel
        assertEquals(a, s.server.value)
    }

    @Test
    fun localHttpOnlyInDebug() {
        assertNull(PairLink.check(PairTarget("http://10.0.2.2:18090", code), allowLocalHttp = false))
        assertEquals("http://10.0.2.2:18090", PairLink.check(PairTarget("http://10.0.2.2:18090", code), allowLocalHttp = true)?.server)
        assertEquals("http://127.0.0.1:18090", PairLink.check(PairTarget("http://127.0.0.1:18090/", code), allowLocalHttp = true)?.server)
        assertNull(PairLink.check(PairTarget("http://evil.example.org", code), allowLocalHttp = true))
        assertNull(PairLink.check(PairTarget("https://ok.example.org", "kurz"), allowLocalHttp = false))
        assertEquals("ok.example.org:8443/saga", PairLink.displayHost("https://ok.example.org:8443/saga"))
    }

    @Test
    fun firstLoginWithoutOldAccountForgetsNothing() = runTest {
        val s = FakeSettings()
        val sec = SecureStore(MemorySecretStore())
        val api = FakeAuthApi()
        val auth = session(s, sec, api)
        assertFalse(auth.signedIn)
        auth.exchange(b, "code", "verifier")
        assertEquals(listOf("exchange $b"), api.calls)
        assertEquals(b, s.server.value)
        assertEquals("hkd_EX", sec.deviceTokenFor(b))
    }

    @Test
    fun keysAreBoundToTheirServer() {
        val sec = SecureStore(MemorySecretStore())
        sec.setCredentials(a, "hkd_X", "hk_X")
        assertEquals("hkd_X", sec.deviceTokenFor(a))
        assertNull(sec.deviceTokenFor(b))
        assertNull(sec.appKeyFor(b))
        assertNull(sec.deviceTokenFor(""))
    }

    @Test
    fun ownServerDetection() {
        assertTrue(sameServer(a, "$a/api/notes".toHttpUrl()))
        assertFalse(sameServer(a, "https://alt.example.org.evil.com/api".toHttpUrl()))
        assertFalse(sameServer(a, "http://alt.example.org/api".toHttpUrl()))       // anderes Schema
        assertFalse(sameServer(a, "https://alt.example.org:8443/api".toHttpUrl())) // anderer Port
        assertFalse(sameServer(a, "https://bilder.example.com/x.png".toHttpUrl()))
        assertTrue(sameServer("https://h.example.org/saga", "https://h.example.org/saga/files/1".toHttpUrl()))
        assertFalse(sameServer("https://h.example.org/saga", "https://h.example.org/sagax/files/1".toHttpUrl()))
        assertFalse(sameServer("https://h.example.org/saga", "https://h.example.org/andere/app".toHttpUrl()))
        assertFalse(sameServer("", "https://h.example.org/".toHttpUrl()))
    }

    // ------------------------------------------------------------------ A10 Abmelden loescht Chat-Daten des Kontos

    private fun chatEntries(sec: SecureStore, scope: String) {
        for (n in listOf("keys", "tofu", "rs_8", "rh_8", "pending", "link")) sec.put("chat_$n:$scope", "x")
    }

    @Test
    fun a10SignOutDeletesChatEntriesOfThisAccountOnly() = runTest {
        val (s, sec, api) = signedInAtA()
        val mine = "$a|${AccountKey.uid("mia")}"
        val other = "$a|${AccountKey.uid("tom")}"
        chatEntries(sec, mine)
        chatEntries(sec, other)
        sec.put("draft_x", "bleibt")
        session(s, sec, api).signOutLocal(forgetRemote = true)
        assertNull(sec.get("chat_keys:$mine"))
        assertNull(sec.get("chat_tofu:$mine"))
        assertNull(sec.get("chat_rs_8:$mine"))
        assertNull(sec.get("chat_pending:$mine"))
        assertEquals("x", sec.get("chat_keys:$other"))          // anderes Konto auf dem Geraet: unberuehrt
        assertEquals("bleibt", sec.get("draft_x"))
    }

    @Test
    fun a10SwitchingAccountDeletesOldChatKeys() = runTest {
        val (s, sec, api) = signedInAtA()
        val mine = "$a|${AccountKey.uid("mia")}"
        chatEntries(sec, mine)
        session(s, sec, api).pair(PairTarget(b, code))
        assertNull(sec.get("chat_keys:$mine"))
    }

    @Test
    fun a10RepairingSameAccountKeepsChatKeys() = runTest {
        val (s, sec, api) = signedInAtA()
        s.setAccountKey(AccountKey.uid("u-neu"))                // gleiches Konto, wie es der Server meldet
        val mine = "$a|${AccountKey.uid("u-neu")}"
        chatEntries(sec, mine)
        session(s, sec, api).pair(PairTarget(a, code))
        assertEquals("x", sec.get("chat_keys:$mine"))
    }
}
