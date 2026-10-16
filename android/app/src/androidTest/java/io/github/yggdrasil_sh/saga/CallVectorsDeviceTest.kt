package io.github.yggdrasil_sh.saga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import io.github.yggdrasil_sh.saga.call.CallCrypto
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unhex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * tests/call_vectors.json mit der libsodium der App (lazysodium-android) auf Geraet/Emulator - Gegenstueck zum
 * Unit-Test CallVectorsTest (JVM-libsodium). Eine Zeile je Punkt von "call_vectors.py check".
 */
@RunWith(AndroidJUnit4::class)
class CallVectorsDeviceTest {
    private val chat = ChatCrypto(LazySodiumAndroid(SodiumAndroid()))
    private val cc = CallCrypto(chat)

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun allVectors() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val v = Json.parseToJsonElement(ctx.assets.open("call_vectors.json").bufferedReader().readText()).jsonObject
        val ca = v["caller"]!!.jsonObject
        val ce = v["callee"]!!.jsonObject
        val cid = v.s("call_id")
        val da = chat.device(ca.s("device"), unhex(ca.s("enc_seed_hex")), unhex(ca.s("sign_seed_hex")))
        val db = chat.device(ce.s("device"), unhex(ce.s("enc_seed_hex")), unhex(ce.s("sign_seed_hex")))
        val o = v["offer"]!!.jsonObject
        val res = LinkedHashMap<String, Boolean>()
        res["call device keys"] = b64(da.signPk) == ca.s("sign_pk") && b64(db.encPk) == ce.s("enc_pk")
        res["call signal_msg"] = ChatFormat.hex(CallCrypto.signalMsg(cid, ca.s("uid"), ca.s("device"), ce.s("uid"), ce.s("device"), o.s("body"))) == o.s("signal_msg_hex")
        res["call sig"] = b64(cc.sign(cid, ca.s("uid"), da, ce.s("uid"), ce.s("device"), o.s("body"))) == o.s("sig")
        res["call inner"] = CallCrypto.signalInner(o.s("body"), ChatFormat.unb64(o.s("sig"))!!) == o.s("inner")
        val op = cc.openSignal(o.s("sealed"), db.encPk, db.encSk)
        res["call open+verify offer"] = op.body == o.s("body") &&
            cc.verifySignal(cid, ca.s("uid"), ca.s("device"), ce.s("uid"), ce.s("device"), op.body, op.sig, da.signPk)
        val oi = cc.openSignal(v["ice"]!!.jsonObject.s("sealed"), db.encPk, db.encSk)
        res["call open+verify ice"] = cc.verifySignal(cid, ca.s("uid"), ca.s("device"), ce.s("uid"), ce.s("device"), oi.body, oi.sig, da.signPk)
        val of = cc.openSignal(v["forged"]!!.jsonObject.s("sealed"), db.encPk, db.encSk)
        res["call forged rejected"] = !cc.verifySignal(cid, ca.s("uid"), ca.s("device"), ce.s("uid"), ce.s("device"), of.body, of.sig, da.signPk)
        for ((k, good) in res) android.util.Log.i("CallVectors", (if (good) "OK   " else "FAIL ") + k)
        android.util.Log.i("CallVectors", "VEKTOREN ${res.count { it.value }}/${res.size}")
        assertEquals("Fehlgeschlagen: " + res.filter { !it.value }.keys, res.size, res.count { it.value })
    }
}
