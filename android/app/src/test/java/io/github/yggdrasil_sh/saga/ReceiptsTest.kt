package io.github.yggdrasil_sh.saga

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unhex
import io.github.yggdrasil_sh.saga.chat.OpenedReceipt
import io.github.yggdrasil_sh.saga.chat.ReceiptCrypto
import io.github.yggdrasil_sh.saga.chat.ReceiptLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pflicht (docs/APP-API.md, Gelesen-Bestaetigungen 2.4): tests/vectors/receipts.json mit der eigenen Umsetzung
 * (ReceiptCrypto ueber ChatCrypto/libsodium) - echte libsodium der JVM (lazysodium-java). Nachricht (LP), Signatur,
 * Oeffnen des versiegelten Vektors, eigenes Versiegeln (Rundlauf) und alle Ablehnungsfaelle.
 */
class ReceiptsTest {
    private val chat = ChatCrypto(LazySodiumJava(SodiumJava()))
    private val rc = ReceiptCrypto(chat)
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("receipts.json")!!.bufferedReader().readText(),
    ).jsonObject

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject

    private val dev = v.o("device").let { d -> chat.device(d.s("id"), signSeed = unhex(d.s("sign_seed_hex"))) }
    private val viewer = chat.identity(backupSeed = unhex(v.o("viewer").s("backup_seed_hex")))

    @Test
    fun keys() {
        assertEquals("saga-receipt/v1", v.s("format"))
        assertEquals(v.o("device").s("sign_pk"), b64(dev.signPk))
        assertEquals(v.o("viewer").s("backup_pk"), b64(viewer.backupPk))
    }

    @Test
    fun casesMessageSignatureOpen() {
        val cases = v["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, cases.size)
        for (c in cases) {
            val v2 = c["v2"]!!.jsonPrimitive.boolean
            val body = c.s("body")
            assertEquals(c.s("name"), c.s("msg_hex"), ChatFormat.hex(rc.msg(c.s("chat"), c.s("reader"), dev.id, body, v2)))
            // Ed25519 ist deterministisch: gleiche Signatur wie der Vektor
            assertEquals(c.s("name"), c.s("sig"), b64(rc.sign(c.s("chat"), c.s("reader"), dev, body, v2)))
            // versiegelten Vektor mit dem Backup-Schluessel oeffnen und pruefen
            val o = rc.open(c.s("sealed"), viewer.backupPk, viewer.backupSk)
            assertEquals(dev.id, o.dev)
            assertEquals(body, o.body)
            assertArrayEquals(unb64(c.s("sig"), 64), o.sig)
            assertTrue(c.s("name"), rc.verify(c.s("chat"), c.s("reader"), o, dev.signPk, v2))
            // body wie die App ihn baut (genau dieser Text wird signiert)
            val mark = ReceiptLogic.parseBody(body)
            assertNotNull(mark)
            assertEquals(body, ReceiptLogic.body(mark!!.id, mark.n, mark.ts))
            // eigener Rundlauf: versiegeln -> oeffnen -> pruefen
            val mine = rc.seal(c.s("chat"), c.s("reader"), dev, body, v2, viewer.backupPk)
            val back = rc.open(mine, viewer.backupPk, viewer.backupSk)
            assertEquals(body, back.body)
            assertTrue(rc.verify(c.s("chat"), c.s("reader"), back, dev.signPk, v2))
        }
    }

    @Test
    fun rejectCases() {
        val rej = v["reject"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, rej.size)
        for (r in rej) {
            val o = OpenedReceipt(dev.id, r.s("body"), unb64(r.s("sig"), 64)!!)
            assertFalse(r.s("why"), rc.verify(r.s("chat"), r.s("reader"), o, dev.signPk, r["v2"]!!.jsonPrimitive.boolean))
        }
    }

    @Test
    fun otherDeviceAndKeyRejected() {
        val c = v["cases"]!!.jsonArray[0].jsonObject
        val o = rc.open(c.s("sealed"), viewer.backupPk, viewer.backupSk)
        val other = chat.device()
        assertFalse(rc.verify(c.s("chat"), c.s("reader"), o, other.signPk, false))
        assertFalse(rc.verify(c.s("chat"), c.s("reader"), o, null, false))
        // falscher Backup-Schluessel: nicht zu oeffnen
        val wrong = chat.identity()
        assertTrue(runCatching { rc.open(c.s("sealed"), wrong.backupPk, wrong.backupSk) }.isFailure)
    }

    @Test
    fun badBodies() {
        assertEquals(null, ReceiptLogic.parseBody("{\"id\":\"a\",\"n\":\"3\",\"ts\":1}"))
        assertEquals(null, ReceiptLogic.parseBody("{\"n\":3}"))
        assertEquals(null, ReceiptLogic.parseBody("kaputt"))
        assertEquals(null, ReceiptLogic.parseBody("{\"id\":\"a\",\"n\":0,\"ts\":1}"))
    }
}
