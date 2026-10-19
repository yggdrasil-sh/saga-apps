package io.github.yggdrasil_sh.saga

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unhex
import io.github.yggdrasil_sh.saga.chat.FedCrypto
import io.github.yggdrasil_sh.saga.chat.FedDeviceSet
import io.github.yggdrasil_sh.saga.chat.FedFormat
import io.github.yggdrasil_sh.saga.chat.FedKeyDevice
import io.github.yggdrasil_sh.saga.chat.FedKeyEntry
import io.github.yggdrasil_sh.saga.chat.FedKeys
import io.github.yggdrasil_sh.saga.chat.TofuV2
import io.github.yggdrasil_sh.saga.chat.Trust2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pflicht (docs/APP-API-FED.md 3.4): tests/vectors/chat_v2.json muss mit der eigenen Umsetzung (FedCrypto ueber
 * ChatCrypto/libsodium) vollstaendig durchlaufen - hier mit der echten libsodium der JVM (lazysodium-java). Auch die
 * Ablehnungsfaelle (reject) und die TOFU-Regeln (tofu) werden geprueft.
 */
class FedVectorsTest {
    private val chat = ChatCrypto(LazySodiumJava(SodiumJava()))
    private val fed = FedCrypto(chat)
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("chat_v2.json")!!.bufferedReader().readText(),
    ).jsonObject

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject
    private fun JsonObject.sn(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private val ident = v.o("identity").let { i -> chat.identity(unhex(i.s("ident_seed_hex")), unhex(i.s("backup_seed_hex"))) }
    private val dev = v.o("device").let { d -> chat.device(d.s("id"), unhex(d.s("enc_seed_hex")), unhex(d.s("sign_seed_hex"))) }
    private val handle = v.o("identity").s("handle")

    @Test
    fun lpAndKeys() {
        assertEquals("saga-chat/v2", v.s("format"))
        val lp = v.o("lp")
        val f = lp["fields"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(lp.s("hex"), ChatFormat.hex(ChatFormat.lp(*f.toTypedArray())))
        val i = v.o("identity")
        assertEquals(i.s("ident_pk"), b64(ident.identPk))
        assertEquals(i.s("ident_sk"), b64(ident.identSk))
        assertEquals(i.s("backup_pk"), b64(ident.backupPk))
        assertEquals(i.s("backup_sk"), b64(ident.backupSk))
        val d = v.o("device")
        assertEquals(d.s("enc_pk"), b64(dev.encPk))
        assertEquals(d.s("enc_sk"), b64(dev.encSk))
        assertEquals(d.s("sign_pk"), b64(dev.signPk))
        assertEquals(d.s("sign_sk"), b64(dev.signSk))
    }

    @Test
    fun certificates() {
        val hc = v.o("handle_cert")
        assertEquals(hc.s("msg_hex"), ChatFormat.hex(FedFormat.handleMsg(handle, ident.identPk, ident.backupPk)))
        assertEquals(hc.s("sig"), b64(fed.handleSig(handle, ident)))
        assertTrue(fed.verifyHandle(handle, ident.identPk, ident.backupPk, hc.s("sig")))
        val d = v.o("device")
        assertEquals(d.s("msg_hex"), ChatFormat.hex(FedFormat.deviceMsg(handle, dev.id, dev.encPk, dev.signPk)))
        assertEquals(d.s("fed_sig"), b64(fed.deviceSig(handle, ident.identSk, dev.id, dev.encPk, dev.signPk)))
        assertTrue(fed.verifyDevice(handle, ident.identPk, FedKeyDevice(dev.id, b64(dev.encPk), b64(dev.signPk), d.s("fed_sig"))))
        val ds = v.o("device_set")
        val ids = ds["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        val ver = ds["ver"]!!.jsonPrimitive.longOrNull!!
        assertEquals(ds.s("msg_hex"), ChatFormat.hex(FedFormat.devicesMsg(ds.s("handle"), ver, ids)))
        val signed = fed.deviceSetSig(ds.s("handle"), ident.identSk, ver, ids)
        assertEquals(ds.s("sig"), signed.sig)
        assertTrue(fed.verifyDeviceSet(ds.s("handle"), ident.identPk, FedDeviceSet(ver, ids, ds.s("sig"))))
    }

    @Test
    fun checkKeysWithDeviceSet() {
        val d = v.o("device")
        val ds = v.o("device_set")
        val ids = ds["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        val good = FedKeyDevice(dev.id, b64(dev.encPk), b64(dev.signPk), d.s("fed_sig"))
        val forged = FedKeyDevice("ffffffffffffffffffffffffffffffff", b64(dev.encPk), b64(dev.signPk), d.s("fed_sig"))  // falsches Zertifikat
        val e = FedKeyEntry(2, handle, b64(ident.identPk), b64(ident.backupPk), v.o("handle_cert").s("sig"),
            FedDeviceSet(ds["ver"]!!.jsonPrimitive.longOrNull!!, ids, ds.s("sig")), listOf(good, forged))
        val k = fed.checkKeys(handle, e)!!
        assertEquals(3L, k.ver)
        assertTrue(k.signed)
        assertEquals(listOf(dev.id), k.active.map { it.id })                        // gefaelschtes Geraet verworfen
        // Server verschweigt Widerruf nicht: Geraet nicht in der signierten Liste -> widerrufen
        val other = fed.checkKeys(handle, e.copy(dset = fed.deviceSetSig(handle, ident.identSk, 4, listOf("ffffffffffffffffffffffffffffffff"))))!!
        assertTrue(other.active.isEmpty())
        // ungueltige Liste: Altbestand (ver 0), revoked vom Server
        val bad = fed.checkKeys(handle, e.copy(dset = FedDeviceSet(9, ids, ds.s("sig"))))!!
        assertEquals(0L, bad.ver)
        assertEquals(1, bad.active.size)
        assertNull(fed.checkKeys("~qrstuvwxyz234567@b.example:8443", e))            // anderer Handle angefragt
        assertNull(fed.checkKeys(handle, e.copy(sig = v.o("device").s("fed_sig"))))  // Handle-Zertifikat ungueltig
        assertNull(fed.checkKeys(handle, e.copy(devices = listOf(good, good))))       // doppelte Geraete-IDs (wie iOS/Linux)
        assertNull(fed.checkKeys("bert@b.example", e.copy(handle = "bert@b.example"))) // kein gueltiges Handle-Format
    }

    @Test
    fun recipientsAndSealed() {
        val r = v.o("recipient")
        val ri = r.o("identity")
        val rid = chat.identity(unhex(ri.s("ident_seed_hex")), unhex(ri.s("backup_seed_hex")))
        assertEquals(ri.s("ident_pk"), b64(rid.identPk))
        assertEquals(ri.s("backup_pk"), b64(rid.backupPk))
        val rd = r.o("device")
        val rdev = chat.device(rd.s("id"), unhex(rd.s("enc_seed_hex")), unhex(rd.s("sign_seed_hex")))
        assertEquals(rd.s("enc_pk"), b64(rdev.encPk))
        val rh = ri.s("handle")
        val keys = FedKeys(rh, rid.identPk, rid.backupPk, b64(rid.identPk), 1, true, listOf(FedKeyDevice(rdev.id, b64(rdev.encPk), b64(rdev.signPk), "")))
        val ids = FedFormat.recipients(listOf(keys)).keys.sorted()
        assertEquals(r["ids"]!!.jsonArray.map { it.jsonPrimitive.content }, ids)
        val msgKey = v.o("messages").o(r.s("message")).s("key")
        val sealed = r.o("sealed")
        assertEquals(msgKey, b64(chat.openKey(sealed.s("dev:${rdev.id}@b.example:8443"), rdev.encPk, rdev.encSk)))
        assertEquals(msgKey, b64(chat.openKey(sealed.s("backup:$rh"), rid.backupPk, rid.backupSk)))
        // widerrufene Geraete bekommen keinen Schluessel
        val rev = FedKeys(rh, rid.identPk, rid.backupPk, "", 1, true, listOf(FedKeyDevice(rdev.id, b64(rdev.encPk), "", "", revoked = true)))
        assertEquals(listOf("backup:$rh"), FedFormat.recipients(listOf(rev)).keys.toList())
    }

    @Test
    fun messages() {
        for ((name, el) in v.o("messages")) {
            val m = el.jsonObject
            val ref = m.s("chat_ref")
            val reply = m.sn("reply_to")
            val ai = m.sn("ai")
            val ts = m["ts"]!!.jsonPrimitive.longOrNull!!
            assertEquals(name, m.s("ad_hex"), ChatFormat.hex(FedFormat.msgAd(ref, m.s("id"), m.s("sender_handle"), m.s("sender_device"), ts, reply, ai)))
            val enc = fed.encrypt(ref, m.s("sender_handle"), dev, m.s("body_json"), emptyMap(), reply, ai, m.s("id"), ts, unb64(m.s("key"))!!, unb64(m.s("nonce"))!!)
            assertEquals(name, m.s("body_ct"), enc.bodyCt)
            assertEquals(name, m.s("sig_msg_hex"), ChatFormat.hex(fed.sigMsg(ref, m.s("id"), m.s("sender_handle"), m.s("sender_device"), ts, reply, ai, m.s("body_ct"))))
            assertEquals(name, m.s("sig"), enc.sig)
            assertEquals(name, m.s("body_json"), fed.decrypt(ref, m.s("id"), m.s("sender_handle"), m.s("sender_device"), ts, reply, ai, m.s("body_ct"), unb64(m.s("key"))!!))
            assertTrue(name, fed.verifyMessage(ref, m.s("id"), m.s("sender_handle"), m.s("sender_device"), ts, reply, ai, m.s("body_ct"), m.s("sig"), dev.signPk))
        }
        // KI-Hash = SHA-256 des Antworttextes
        val m2 = v.o("messages").o("m2")
        val text = Json.parseToJsonElement(m2.s("body_json")).jsonObject.s("text")
        assertEquals(m2.s("ai"), FedFormat.aiHash(text))
    }

    @Test
    fun fileAndSafety() {
        val f = v.o("file")
        assertEquals(f.s("ad_hex"), ChatFormat.hex(FedFormat.fileAd(f.s("chat_ref"))))
        val (blob, _) = fed.encryptFile(f.s("chat_ref"), unb64(f.s("plain_b64"))!!, unb64(f.s("key"))!!, unb64(f.s("nonce"))!!)
        assertEquals(f.s("blob"), b64(blob))
        assertEquals(f.s("plain_b64"), b64(fed.decryptFile(f.s("chat_ref"), unb64(f.s("blob"))!!, unb64(f.s("key"))!!)))
        val s = v.o("safety")
        val want = s.s("number")
        assertEquals(want, fed.safetyNumber(s.s("handle_a"), unb64(s.s("ident_a"))!!, s.s("handle_b"), unb64(s.s("ident_b"))!!))
        assertEquals(want, fed.safetyNumber(s.s("handle_b"), unb64(s.s("ident_b"))!!, s.s("handle_a"), unb64(s.s("ident_a"))!!))
    }

    @Test
    fun rejectCases() {
        val msgs = v.o("messages")
        val d = v.o("device")
        var n = 0
        for (el in v["reject"]!!.jsonArray) {
            val r = el.jsonObject
            val case = r.s("case")
            val ok = when (r.s("what")) {
                "handle_cert" -> fed.verifyHandle(r.s("handle"), ident.identPk, ident.backupPk, r.s("sig"))
                "device_cert" -> fed.verifyDevice(r.s("handle"), ident.identPk, FedKeyDevice(d.s("id"), d.s("enc_pk"), d.s("sign_pk"), r.s("fed_sig")))
                "device_set" -> fed.verifyDeviceSet(r.s("handle"), ident.identPk,
                    FedDeviceSet(r["ver"]!!.jsonPrimitive.longOrNull!!, r["ids"]!!.jsonArray.map { it.jsonPrimitive.content }, r.s("sig")))
                "message" -> {
                    val m = msgs.o(r.s("message"))
                    val ref = if ("chat_ref" in r) r.s("chat_ref") else m.s("chat_ref")
                    val sender = if ("sender_handle" in r) r.s("sender_handle") else m.s("sender_handle")
                    val ai = if ("ai" in r) r.sn("ai") else m.sn("ai")
                    val ts = (r["ts"] ?: m["ts"])!!.jsonPrimitive.longOrNull!!
                    val sig = fed.verifyMessage(ref, m.s("id"), sender, m.s("sender_device"), ts, m.sn("reply_to"), ai, m.s("body_ct"), m.s("sig"), dev.signPk)
                    val dec = try {
                        fed.decrypt(ref, m.s("id"), sender, m.s("sender_device"), ts, m.sn("reply_to"), ai, m.s("body_ct"), unb64(m.s("key"))!!)
                        true
                    } catch (_: Exception) {
                        false
                    }
                    sig || dec
                }
                else -> error("unbekannt: $case")
            }
            assertFalse(case, ok)
            n++
        }
        assertEquals(10, n)
        // Gegenprobe: die Originale gelten
        assertTrue(fed.verifyHandle(handle, ident.identPk, ident.backupPk, v.o("handle_cert").s("sig")))
    }

    @Test
    fun tofuRules() {
        val ids = mapOf("ident_a" to "A", "ident_b" to "B")
        fun stored(o: kotlinx.serialization.json.JsonElement?): Trust2? = (o as? JsonObject)?.let { s ->
            Trust2(ids[s.s("ident")]!!, s["verified"]!!.jsonPrimitive.content == "true", s["changed"]?.jsonPrimitive?.content == "true",
                s.sn("old")?.let { ids[it] }, s["dver"]?.jsonPrimitive?.longOrNull ?: 0)
        }
        var n = 0
        for (el in v.o("tofu")["rules"]!!.jsonArray) {
            val r = el.jsonObject
            val step = r.s("step")
            val seen = ids[r.s("seen")]
            val want = TofuV2.valueOf(r.s("result").uppercase())
            if (seen == null) {                                     // ungueltiges Zertifikat: checkKeys liefert null -> ablehnen
                assertEquals(step, TofuV2.REJECT, want)
                assertNull(fed.checkKeys(handle, FedKeyEntry(handle = handle, identPub = b64(ident.identPk), backupPub = b64(ident.backupPk), sig = "x")))
                n++
                continue
            }
            val got = FedFormat.tofu(stored(r["stored"]), seen, r["ver"]?.jsonPrimitive?.longOrNull)
            assertEquals(step, want, got.result)
            r["store"]?.let { assertEquals(step, stored(it), got.store) }
            n++
        }
        assertEquals(9, n)
    }

    @Test
    fun callSignalV2RoundTrip() {
        val r = v.o("recipient")
        val rd = r.o("device")
        val rdev = chat.device(rd.s("id"), unhex(rd.s("enc_seed_hex")), unhex(rd.s("sign_seed_hex")))
        val to = r.o("identity").s("handle")
        val body = "{\"t\":\"offer\",\"sdp\":\"v=0 ü\"}"
        // Gegenprobe mit der Python-Referenz (tests/chatlib.py signal_msg_v2, PyNaCl): Ed25519 ist deterministisch
        assertEquals("7XnaqFN9jU+4kGGNz7BUQHNXe3Tn1k+orQc/Xhkzm9b87sDTI1EpK6u83W15TzjnnpJPvetmP6T9w2QhjW0NAA==",
            b64(fed.signSignal("c0ffee00c0ffee00c0ffee00c0ffee00", handle, dev, to, rdev.id, body)))
        assertEquals("{\"v\":2,\"body\":\"{\\\"t\\\":\\\"offer\\\",\\\"sdp\\\":\\\"v=0 \\u00fc\\\"}\",\"sig\":\"AAAA\"}", FedFormat.signalInner(body, ByteArray(3)))
        val sealed = fed.sealSignal("c0ffee00c0ffee00c0ffee00c0ffee00", handle, dev, to, rdev.id, rdev.encPk, body)
        val o = fed.openSignal(sealed, rdev.encPk, rdev.encSk)
        assertEquals(body, o.body)
        assertTrue(fed.verifySignal("c0ffee00c0ffee00c0ffee00c0ffee00", handle, dev.id, to, rdev.id, o.body, o.sig, dev.signPk))
        // an ein anderes Ziel umgeleitet bzw. als v1 gelesen: abgelehnt
        assertFalse(fed.verifySignal("c0ffee00c0ffee00c0ffee00c0ffee00", handle, dev.id, handle, rdev.id, o.body, o.sig, dev.signPk))
        assertNotNull(FedFormat.signalMsg("a", "b", "c", "d", "e", "f"))
        assertEquals("{\"v\":2,\"body\":\"x\\u00fc\",\"sig\":\"AAAA\"}", FedFormat.signalInner("xü", ByteArray(3)))
    }
}
