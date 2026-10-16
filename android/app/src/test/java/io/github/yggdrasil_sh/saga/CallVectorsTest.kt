package io.github.yggdrasil_sh.saga

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.github.yggdrasil_sh.saga.call.CallCrypto
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unhex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pflicht laut Anruf-Spezifikation: tests/call_vectors.json (Server, tools/call_vectors.py check) muss mit der eigenen
 * Umsetzung (CallCrypto ueber ChatCrypto) vollstaendig durchlaufen - hier mit der echten libsodium der JVM
 * (lazysodium-java, nur fuer Tests). Jeder Punkt entspricht einer Zeile von "call_vectors.py check".
 */
class CallVectorsTest {
    private val chat = ChatCrypto(LazySodiumJava(SodiumJava()))
    private val cc = CallCrypto(chat)

    private fun load(): JsonObject {
        val text = javaClass.classLoader!!.getResourceAsStream("call_vectors.json")!!.bufferedReader().readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun allVectors() {
        val v = load()
        val res = LinkedHashMap<String, Boolean>()
        val ca = v["caller"]!!.jsonObject
        val ce = v["callee"]!!.jsonObject
        val cid = v.s("call_id")
        assertEquals("saga-call/v1", v.s("format"))
        val da = chat.device(ca.s("device"), unhex(ca.s("enc_seed_hex")), unhex(ca.s("sign_seed_hex")))
        val db = chat.device(ce.s("device"), unhex(ce.s("enc_seed_hex")), unhex(ce.s("sign_seed_hex")))
        res["call device keys"] = b64(da.signPk) == ca.s("sign_pk") && b64(da.encPk) == ca.s("enc_pk") &&
            b64(db.encPk) == ce.s("enc_pk") && b64(db.signPk) == ce.s("sign_pk")
        val o = v["offer"]!!.jsonObject
        val msg = CallCrypto.signalMsg(cid, ca.s("uid"), ca.s("device"), ce.s("uid"), ce.s("device"), o.s("body"))
        res["call signal_msg"] = ChatFormat.hex(msg) == o.s("signal_msg_hex")
        res["call sig (Ed25519, deterministisch)"] = b64(chat.sign(unhex(o.s("signal_msg_hex")), da.signSk)) == o.s("sig") &&
            b64(cc.sign(cid, ca.s("uid"), da, ce.s("uid"), ce.s("device"), o.s("body"))) == o.s("sig")
        res["call inner"] = CallCrypto.signalInner(o.s("body"), unb64(o.s("sig"))!!) == o.s("inner")
        val offer = Json.parseToJsonElement(o.s("body")).jsonObject
        val fpWant = o["fingerprints"]!!.jsonArray.map { it.jsonPrimitive.content }
        val fpBody = offer["fp"]!!.jsonArray.map { it.jsonPrimitive.content }
        res["call sdp fingerprints"] = CallCrypto.sdpFingerprints(offer.s("sdp")) == fpWant && fpWant == fpBody &&
            CallCrypto.fpMatches(offer.s("sdp"), fpBody)
        val op = cc.openSignal(o.s("sealed"), db.encPk, db.encSk)
        res["call open offer"] = op.body == o.s("body") && b64(op.sig) == o.s("sig")
        res["call verify offer"] = cc.verifySignal(cid, ca.s("uid"), ca.s("device"), ce.s("uid"), ce.s("device"), op.body, op.sig, da.signPk)
        res["call verify fails for other call"] = !cc.verifySignal("0".repeat(32), ca.s("uid"), ca.s("device"), ce.s("uid"), ce.s("device"), op.body, op.sig, da.signPk)
        val ice = v["ice"]!!.jsonObject
        val oi = cc.openSignal(ice.s("sealed"), db.encPk, db.encSk)
        res["call open+verify ice"] = oi.body == ice.s("body") && b64(oi.sig) == ice.s("sig") &&
            cc.verifySignal(cid, ca.s("uid"), ca.s("device"), ce.s("uid"), ce.s("device"), oi.body, oi.sig, da.signPk)
        val fg = v["forged"]!!.jsonObject
        val of = cc.openSignal(fg.s("sealed"), db.encPk, db.encSk)
        res["call forged (other target device) rejected"] =
            !cc.verifySignal(cid, ca.s("uid"), ca.s("device"), ce.s("uid"), ce.s("device"), of.body, of.sig, da.signPk) &&
                cc.verifySignal(cid, ca.s("uid"), ca.s("device"), ce.s("uid"), fg.s("to_device_signed"), of.body, of.sig, da.signPk)

        // Gegenprobe: selbst versiegeln -> oeffnen -> pruefen; falscher Absenderschluessel / anderes Ziel scheitern
        val body = """{"t":"state","mic":false,"cam":true,"screen":false,"x":"ä€😀"}"""
        val sealed = cc.sealSignal(cid, "bert", db, "anna", da.id, da.encPk, body)
        val back = cc.openSignal(sealed, da.encPk, da.encSk)
        res["roundtrip (non-ASCII)"] = back.body == body &&
            cc.verifySignal(cid, "bert", db.id, "anna", da.id, back.body, back.sig, db.signPk) &&
            !cc.verifySignal(cid, "bert", db.id, "anna", da.id, back.body, back.sig, da.signPk) &&
            !cc.verifySignal(cid, "bert", db.id, "anna", "f".repeat(32), back.body, back.sig, db.signPk)

        for ((k, good) in res) println((if (good) "OK   " else "FAIL ") + k)
        val ok = res.count { it.value }
        println("VEKTOREN $ok/${res.size}")
        assertEquals("Fehlgeschlagen: " + res.filter { !it.value }.keys, res.size, ok)
        assertTrue(res.size >= 11)
    }

    @Test
    fun wrongKeyCannotOpen() {
        val v = load()
        val ca = v["caller"]!!.jsonObject
        val da = chat.device(ca.s("device"), unhex(ca.s("enc_seed_hex")), unhex(ca.s("sign_seed_hex")))
        val failed = try {
            cc.openSignal(v["offer"]!!.jsonObject.s("sealed"), da.encPk, da.encSk)       // fuer bert versiegelt
            false
        } catch (_: Exception) {
            true
        }
        assertTrue(failed)
    }

    @Test
    fun fingerprints() {
        val sdp = "v=0\r\na=fingerprint:SHA-256 ab:cd\r\nm=audio 9\r\na=fingerprint:sha-256 AB:CD\r\na=fingerprint:sha-1 01:02 \r\n"
        assertEquals(listOf("sha-1 01:02", "sha-256 AB:CD"), CallCrypto.sdpFingerprints(sdp))
        assertTrue(CallCrypto.fpMatches(sdp, listOf("sha-256 AB:CD", "sha-1 01:02")))
        assertFalse(CallCrypto.fpMatches(sdp, listOf("sha-256 AB:CD")))
        assertFalse(CallCrypto.fpMatches("v=0\r\n", emptyList()))
        assertEquals(true, CallCrypto.certMatches("SHA-256", "ab:cd", listOf("sha-256 AB:CD")))
        assertEquals(false, CallCrypto.certMatches("sha-256", "AB:CE", listOf("sha-256 AB:CD")))
        assertEquals(null, CallCrypto.certMatches(null, "AB", listOf("sha-256 AB:CD")))
        assertEquals("\"a\\u00e4\\ud83d\\ude00\\n\"", CallCrypto.jsonString("aä😀\n"))
    }
}
