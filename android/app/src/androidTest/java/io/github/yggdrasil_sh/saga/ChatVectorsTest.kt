package io.github.yggdrasil_sh.saga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unhex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pflicht laut Chat-Spezifikation: tests/chat_vectors.json (Server, tools/chat_vectors.py check) muss mit der eigenen
 * Umsetzung vollstaendig durchlaufen. Laeuft mit der echten libsodium (lazysodium-android) auf Geraet/Emulator.
 * Jeder Punkt entspricht einer Zeile von "chat_vectors.py check".
 */
@RunWith(AndroidJUnit4::class)
class ChatVectorsTest {
    private val c = ChatCrypto(LazySodiumAndroid(SodiumAndroid()))

    private fun load(): JsonObject {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val text = ctx.assets.open("chat_vectors.json").bufferedReader().readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun allVectors() {
        val v = load()
        val res = LinkedHashMap<String, Boolean>()
        val i = v["identity"]!!.jsonObject
        val d = v["device"]!!.jsonObject
        val r = v["recovery"]!!.jsonObject
        val m = v["message"]!!.jsonObject
        val f = v["file"]!!.jsonObject
        val lk = v["link"]!!.jsonObject
        val s = v["safety"]!!.jsonObject
        val uid = i.s("uid")
        val lpv = v["lp"]!!.jsonObject
        res["lp"] = ChatFormat.hex(ChatFormat.lp(*(lpv["fields"] as JsonArray).map { it.jsonPrimitive.content }.toTypedArray())) == lpv.s("hex")
        val b32 = v["base32"]!!.jsonObject
        res["base32"] = ChatFormat.base32(unhex(b32.s("bytes_hex"))) == b32.s("text")
        val ident = c.identity(unhex(i.s("ident_seed_hex")), unhex(i.s("backup_seed_hex")))
        res["identity keys"] = b64(ident.identPk) == i.s("ident_pk") && b64(ident.identSk) == i.s("ident_sk") &&
            b64(ident.backupPk) == i.s("backup_pk") && b64(ident.backupSk) == i.s("backup_sk")
        res["backup_sig"] = b64(c.sign(unhex(i.s("backup_msg_hex")), ident.identSk)) == i.s("backup_sig") &&
            ChatFormat.hex(c.backupMsg(uid, ident.backupPk)) == i.s("backup_msg_hex") &&
            c.verifyBackup(uid, ident.identPk, i.s("backup_pk"), i.s("backup_sig"))
        val dev = c.device(d.s("id"), unhex(d.s("enc_seed_hex")), unhex(d.s("sign_seed_hex")))
        res["device keys"] = b64(dev.encPk) == d.s("enc_pk") && b64(dev.signPk) == d.s("sign_pk") && b64(dev.signSk) == d.s("sign_sk")
        res["device cert"] = ChatFormat.hex(c.deviceMsg(uid, d.s("id"), dev.encPk, dev.signPk)) == d.s("msg_hex") &&
            b64(c.sign(unhex(d.s("msg_hex")), ident.identSk)) == d.s("sig") &&
            c.verifyDevice(uid, ident.identPk, d.s("id"), d.s("enc_pk"), d.s("sign_pk"), d.s("sig"))
        res["recovery normalize"] = ChatFormat.normRecovery(r.s("key")) == r.s("normalized") && ChatFormat.normRecovery(r.s("input_variant")) == r.s("normalized")
        val ops = r["ops"]!!.jsonPrimitive.long
        val mem = r["mem"]!!.jsonPrimitive.long
        res["recovery kdf (argon2id13)"] = ChatFormat.hex(c.recoveryKdf(r.s("key"), unb64(r.s("salt"))!!, ops, mem)) == r.s("kdf_hex")
        res["bundle seal"] = c.sealBundle(uid, r.s("key"), ident, unb64(r.s("salt"))!!, unb64(r.s("nonce"))!!, ops, mem, r.s("plain")) == r.s("bundle")
        val ob = c.openBundle(uid, r.s("input_variant"), r.s("bundle"))
        res["bundle open"] = ob.identPk.contentEquals(ident.identPk) && ob.backupPk.contentEquals(ident.backupPk)

        val enc = c.encrypt(
            m.s("chat_id"), m.s("sender"), dev, m.s("body_json"), emptyMap(), replyTo = m.s("reply_to"), id = m.s("id"),
            ts = m["ts"]!!.jsonPrimitive.long, key = unb64(m.s("key"))!!, nonce = unb64(m.s("nonce"))!!,
        )
        val ts = m["ts"]!!.jsonPrimitive.long
        res["message ad"] = ChatFormat.hex(c.msgAd(m.s("chat_id"), m.s("id"), m.s("sender"), m.s("device"), ts, m.s("reply_to"))) == m.s("ad_hex")
        res["message encrypt"] = enc.bodyCt == m.s("body_ct")
        res["message sig"] = enc.sig == m.s("sig") &&
            ChatFormat.hex(c.sigMsg(m.s("chat_id"), m.s("id"), m.s("sender"), m.s("device"), ts, m.s("reply_to"), m.s("body_ct"))) == m.s("sig_msg_hex") &&
            c.verifyMessage(m.s("chat_id"), m.s("id"), m.s("sender"), m.s("device"), ts, m.s("reply_to"), m.s("body_ct"), m.s("sig"), dev.signPk)
        val want = Json.parseToJsonElement(m.s("body_json"))
        val kd = c.openKey(m.s("sealed_device"), dev.encPk, dev.encSk)
        res["message open (device)"] = Json.parseToJsonElement(
            c.decrypt(m.s("chat_id"), m.s("id"), m.s("sender"), m.s("device"), ts, m.s("reply_to"), m.s("body_ct"), kd),
        ) == want
        val kb = c.openKey(m.s("sealed_backup"), ident.backupPk, ident.backupSk)
        res["message open (backup)"] = Json.parseToJsonElement(
            c.decrypt(m.s("chat_id"), m.s("id"), m.s("sender"), m.s("device"), ts, m.s("reply_to"), m.s("body_ct"), kb),
        ) == want
        val (blob, _) = c.encryptFile(f.s("chat_id"), unb64(f.s("plain_b64"))!!, unb64(f.s("key"))!!, unb64(f.s("nonce"))!!)
        res["file"] = b64(blob) == f.s("blob") &&
            c.decryptFile(f.s("chat_id"), unb64(f.s("blob"))!!, unb64(f.s("key"))!!).contentEquals(unb64(f.s("plain_b64"))!!)
        res["link code"] = c.linkCode(lk.s("uid"), lk.s("device_id"), unb64(lk.s("enc_pk"))!!, unb64(lk.s("sign_pk"))!!) == lk.s("code")
        res["link sealed bundle"] = String(c.sealOpen(unb64(lk.s("sealed_bundle"))!!, dev.encPk, dev.encSk), Charsets.UTF_8) == r.s("plain")
        val n1 = c.safetyNumber(s.s("uid_a"), unb64(s.s("ident_a"))!!, s.s("uid_b"), unb64(s.s("ident_b"))!!)
        val n2 = c.safetyNumber(s.s("uid_b"), unb64(s.s("ident_b"))!!, s.s("uid_a"), unb64(s.s("ident_a"))!!)
        res["safety number"] = n1 == s.s("number") && n2 == n1

        // Gegenprobe mit frischen Zufallsschluesseln: verschluesseln -> versiegeln -> oeffnen -> pruefen
        val dev2 = c.device()
        val e2 = c.encrypt("9", "bert", dev2, """{"v":1,"type":"text","text":"x"}""", mapOf(dev2.id to dev2.encPk))
        val k2 = c.openKey(e2.keys[dev2.id], dev2.encPk, dev2.encSk)
        res["roundtrip"] = c.decrypt("9", e2.id, "bert", dev2.id, e2.ts, null, e2.bodyCt, k2).contains("\"x\"") &&
            c.verifyMessage("9", e2.id, "bert", dev2.id, e2.ts, null, e2.bodyCt, e2.sig, dev2.signPk) &&
            !c.verifyMessage("9", e2.id, "anna", dev2.id, e2.ts, null, e2.bodyCt, e2.sig, dev2.signPk)

        val ok = res.count { it.value }
        for ((k, good) in res) android.util.Log.i("ChatVectors", (if (good) "OK   " else "FAIL ") + k)
        android.util.Log.i("ChatVectors", "VEKTOREN $ok/${res.size}")
        assertEquals("Fehlgeschlagen: " + res.filter { !it.value }.keys, res.size, ok)
        assertTrue(res.size >= 20)
    }
}
