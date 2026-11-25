package io.github.yggdrasil_sh.saga

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.github.yggdrasil_sh.saga.call.CallCrypto
import io.github.yggdrasil_sh.saga.call.CallV3
import io.github.yggdrasil_sh.saga.call.HornE2E
import io.github.yggdrasil_sh.saga.call.HornLogic
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unhex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2263-7: reservierte Signal-Typen `dtmf`/`svc-join` (Vertrag APP-API "Reservierte Typen dtmf und svc-join"), Vektoren
 * tests/call_signal_reserved.json (Kopie aus dem Server-Repo). Jeder Fall wird mit CallCrypto (echte libsodium der JVM,
 * Seeds aus call_vectors.json: me = caller, peer = callee) versiegelt, geoeffnet und geprueft und dann durch dieselbe
 * Typ-Erkennung geschickt wie in der App: Mesh CallManager.handle -> CallV3.kind (+ pcMatches), Knoten
 * HornSession.signal -> HornLogic.sigKind, Uebernahme im Knoten-Anruf HornLogic.handoverReady.
 * "ignore" = Kind.UNKNOWN bzw. "unknown" (beide Wege tun dann nichts: kein Fehler, kein Zustand, kein Schluessel, kein bye).
 */
class CallSignalReservedTest {
    private val chat = ChatCrypto(LazySodiumJava(SodiumJava()))
    private val cc = CallCrypto(chat)

    private fun res(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()).jsonObject

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun reservierteTypenWerdenStillIgnoriert() {
        val rv = res("call_signal_reserved.json")
        assertEquals("saga-call-reserved/v1", rv.s("format"))
        val cv = res("call_vectors.json")
        val ca = cv["caller"]!!.jsonObject
        val ce = cv["callee"]!!.jsonObject
        val cid = cv.s("call_id")
        val me = chat.device(ca.s("device"), unhex(ca.s("enc_seed_hex")), unhex(ca.s("sign_seed_hex")))
        val peer = chat.device(ce.s("device"), unhex(ce.s("enc_seed_hex")), unhex(ce.s("sign_seed_hex")))
        val devs = rv["devices"]!!.jsonObject
        assertEquals(devs.s("me"), me.id)
        assertEquals(devs.s("peer"), peer.id)
        val meshPc = rv.s("mesh_pc")
        val cases = rv["cases"]!!.jsonArray.map { it.jsonObject }
        var ignored = 0
        var handled = 0
        for (c in cases) {
            val name = c.s("name")
            val text = c["body"]!!.jsonObject.toString()
            // versiegeln (Absender peer -> Empfaenger me), oeffnen, Signatur pruefen
            val sealed = cc.sealSignal(cid, ce.s("uid"), peer, ca.s("uid"), me.id, me.encPk, text)
            val op = cc.openSignal(sealed, me.encPk, me.encSk)
            assertEquals(name, text, op.body)
            assertTrue(name, cc.verifySignal(cid, ce.s("uid"), peer.id, ca.s("uid"), me.id, op.body, op.sig, peer.signPk))
            val b = Json.parseToJsonElement(op.body).jsonObject
            val want = c.s("kind")
            val ignore = c.s("expect") == "ignore"
            when (c.s("ctx")) {
                "mesh" -> {
                    assertTrue(name, CallV3.pcMatches((b["pc"] as? JsonPrimitive)?.content, meshPc))
                    assertEquals(name, want, CallV3.kind((b["t"] as? JsonPrimitive)?.content).name.lowercase())
                    assertEquals(name, ignore, CallV3.kind((b["t"] as? JsonPrimitive)?.content) == CallV3.Kind.UNKNOWN)
                }
                "horn" -> {
                    assertEquals(name, want, HornLogic.sigKind(b))
                    assertEquals(name, ignore, HornLogic.sigKind(b) == HornLogic.SIG_UNKNOWN)
                    // Felder wie fkey/state duerfen weder Schluessel noch Uebernahme ausloesen
                    if (ignore) {
                        assertNull(name, HornE2E.parseFKey(b))
                        assertFalse(name, HornLogic.handoverReady(b, me.id))
                    }
                }
                else -> error("ctx $name")
            }
            if (ignore) ignored++ else handled++
        }
        assertEquals(8, ignored)
        assertEquals(4, handled)
    }

    @Test
    fun uebernahmeNurMitState() {
        val me = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
        fun o(s: String) = Json.parseToJsonElement(s).jsonObject
        // Kontrolle: dieselben Felder wie horn-svc-join-zusatzfeld, aber als state -> Uebernahme
        assertTrue(HornLogic.handoverReady(o("""{"t":"state","mic":true,"ready":true,"replaces":"$me"}"""), me))
        assertFalse(HornLogic.handoverReady(o("""{"t":"svc-join","svc":"bridge","ready":true,"replaces":"$me"}"""), me))
        assertFalse(HornLogic.handoverReady(o("""{"t":"state","ready":true,"replaces":"ffff"}"""), me))
        assertFalse(HornLogic.handoverReady(o("""{"t":"state","ready":false,"replaces":"$me"}"""), me))
        assertFalse(HornLogic.handoverReady(o("""{"t":"state","ready":true}"""), ""))
        assertEquals(HornLogic.SIG_UNKNOWN, HornLogic.sigKind(null))
        assertEquals(HornLogic.SIG_UNKNOWN, HornLogic.sigKind(o("""{"t":5}""")))
        assertEquals(HornLogic.SIG_UNKNOWN, HornLogic.sigKind(o("""{"d":"5"}""")))
        assertEquals("fkey", HornLogic.sigKind(o("""{"t":"fkey"}""")))
    }
}
