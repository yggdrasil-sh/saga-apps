package io.github.yggdrasil_sh.saga

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unhex
import io.github.yggdrasil_sh.saga.chat.ChatStore
import io.github.yggdrasil_sh.saga.chat.ChatX3
import io.github.yggdrasil_sh.saga.chat.DeviceKeys
import io.github.yggdrasil_sh.saga.chat.EditReject
import io.github.yggdrasil_sh.saga.chat.FedCrypto
import io.github.yggdrasil_sh.saga.chat.FedFormat
import io.github.yggdrasil_sh.saga.chat.ReactCrypto
import io.github.yggdrasil_sh.saga.chat.ReactOpen
import io.github.yggdrasil_sh.saga.chat.Reaction
import io.github.yggdrasil_sh.saga.chat.TofuV2
import io.github.yggdrasil_sh.saga.chat.Trust2
import io.github.yggdrasil_sh.saga.chat.V1Trust
import io.github.yggdrasil_sh.saga.data.MemorySecretStore
import io.github.yggdrasil_sh.saga.data.SecureStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
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
 * Pflicht (Konzept Chat A6, docs/APP-API.md "Chat-Stufe 3"): tests/vectors/chat_v3.json mit der eigenen Umsetzung
 * (ReactCrypto, FedCrypto, ChatX3, FedFormat.tofuMigrate) und der echten libsodium der JVM (lazysodium-java) -
 * Reaktionen (Schluessel, AD, Verschluesselung, Signatur, Oeffnen), Bearbeitungen (v2-Nachricht, editCheck), alle
 * Ablehnungsfaelle und die Vertrauens-Uebernahme v1 -> v2.
 */
class ChatV3VectorsTest {
    private val chat = ChatCrypto(LazySodiumJava(SodiumJava()))
    private val fed = FedCrypto(chat)
    private val rc = ReactCrypto(chat)
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("chat_v3.json")!!.bufferedReader().readText(),
    ).jsonObject

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject
    private fun JsonObject.sn(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.longOrNull!!
    private fun JsonObject.d(k: String) = this[k]!!.jsonPrimitive.doubleOrNull!!

    private val devs: Map<String, DeviceKeys> = v.o("devices").entries.associate { (_, d) ->
        val o = d.jsonObject
        o.s("id") to chat.device(o.s("id"), unhex(o.s("enc_seed_hex")), unhex(o.s("sign_seed_hex")))
    }
    private val devByName: Map<String, DeviceKeys> = v.o("devices").entries.associate { (k, d) -> k to devs[d.jsonObject.s("id")]!! }

    private fun reaction(o: JsonObject) = Reaction(o.s("by"), o.s("dev"), o.l("ts"), o.s("ct"), o.s("sig"))

    /** alle Nachrichten (Original, Bearbeitung e1, schlechte Bearbeitungen) */
    private val msgs: Map<String, JsonObject> = LinkedHashMap<String, JsonObject>().apply {
        v.o("messages").forEach { (k, m) -> put(k, m.jsonObject) }
        put("e1", v.o("edit").o("message"))
        v.o("edits_bad").forEach { (k, m) -> put(k, m.jsonObject) }
    }

    @Test
    fun keys() {
        assertEquals("saga-chat/v3", v.s("format"))
        v.o("devices").values.forEach { d -> assertEquals(d.jsonObject.s("sign_pk"), b64(devs[d.jsonObject.s("id")]!!.signPk)) }
        v.o("identities").values.forEach { i ->
            val o = i.jsonObject
            assertEquals(o.s("ident_pk"), b64(chat.identity(unhex(o.s("ident_seed_hex")), unhex(o.s("backup_seed_hex"))).identPk))
        }
    }

    @Test
    fun messagesAndEditsAreV2() {
        for ((name, m) in msgs) {
            val d = devs[m.s("sender_device")]!!
            val e = fed.encrypt(
                m.s("chat_ref"), m.s("sender_handle"), d, m.s("body_json"), emptyMap(), m.sn("reply_to"), m.sn("ai"),
                id = m.s("id"), ts = m.l("ts"), key = unb64(m.s("key"))!!, nonce = unb64(m.s("nonce"))!!,
            )
            assertEquals("$name body_ct", m.s("body_ct"), e.bodyCt)
            assertEquals("$name sig", m.s("sig"), e.sig)
            assertTrue("$name verify", fed.verifyMessage(m.s("chat_ref"), m.s("id"), m.s("sender_handle"), m.s("sender_device"), m.l("ts"),
                m.sn("reply_to"), m.sn("ai"), m.s("body_ct"), m.s("sig"), d.signPk))
        }
    }

    @Test
    fun reactions() {
        for ((name, x) in v.o("reactions")) {
            val r = x.jsonObject
            val k = unb64(r.s("msg_key"))!!
            val ref = r.s("chat_ref")
            val mid = r.s("msg_id")
            val d = devs[r.s("dev")]!!
            assertEquals("$name key", r.s("react_key_hex"), ChatFormat.hex(rc.reactKey(k, ref, mid)))
            assertEquals("$name ad", r.s("ad_hex"), ChatFormat.hex(rc.reactAd(ref, mid, r.s("by"), r.s("dev"))))
            assertEquals("$name body", r.s("body_json"), ChatX3.reactBodyJson(r.s("emoji"), r.l("ts")))
            val e = rc.encrypt(k, ref, mid, r.s("by"), r.s("dev"), d.signSk, r.s("emoji"), r.l("ts"), unb64(r.s("nonce"))!!)
            assertEquals("$name ct", r.s("ct"), e.ct)
            assertEquals("$name sig", r.s("sig"), e.sig)
            assertEquals("$name sigmsg", r.s("sig_msg_hex"), ChatFormat.hex(rc.reactSigMsg(ref, mid, r.s("by"), r.s("dev"), r.l("ts"), r.s("ct"))))
            assertTrue("$name verify", rc.verify(reaction(r), ref, mid, d.signPk))
            assertEquals("$name open", r.s("emoji"), rc.open(reaction(r), k, ref, mid, d.signPk))
        }
    }

    @Test
    fun edit() {
        val ed = v.o("edit").o("message")
        val om = v.o("messages").o(v.o("edit").s("original"))
        val plain = fed.decrypt(ed.s("chat_ref"), ed.s("id"), ed.s("sender_handle"), ed.s("sender_device"), ed.l("ts"), null, null, ed.s("body_ct"), unb64(ed.s("key"))!!)
        val body = Json.parseToJsonElement(plain).jsonObject
        assertEquals(ed.s("body_json"), plain)
        assertEquals(v.o("edit").s("text"), ChatX3.editText(body))
        assertEquals("edit", ed.s("kind"))
        assertNull(ChatX3.editCheck(om.s("id"), om.s("sender_handle"), om.sn("ai") != null, om.d("created"), ed.s("edit_of"), ed.s("sender_handle"), ed.d("created"), body))
        assertEquals(ed.s("body_json"), ChatX3.editBody(om.s("id"), v.o("edit").s("text")))
    }

    @Test
    fun rejects() {
        var n = 0
        for (x in v["reject"]!!.jsonArray) {
            val r = x.jsonObject
            val case = r.s("case")
            when (r.s("what")) {
                "reaction" -> {
                    val base = v.o("reactions").o(r.s("reaction"))
                    val re = Reaction(r.sn("by") ?: base.s("by"), r.sn("dev") ?: base.s("dev"), (r["ts"] as? JsonPrimitive)?.longOrNull ?: base.l("ts"), base.s("ct"), base.s("sig"))
                    val pk = r.sn("sign_pk")?.let { devByName[it]!!.signPk } ?: devs[re.dev]!!.signPk
                    assertFalse(case, rc.verify(re, r.sn("chat_ref") ?: base.s("chat_ref"), r.sn("msg_id") ?: base.s("msg_id"), pk))
                    // auch der Weg ueber ReactOpen (Hintergrund) oeffnet sie nicht
                    val job = ReactOpen.Job(r.sn("msg_id") ?: base.s("msg_id"), unb64(base.s("msg_key"))!!, listOf(re))
                    val out = ReactOpen.run(rc, listOf(job), mapOf(re.by + "|" + re.dev to pk), r.sn("chat_ref") ?: base.s("chat_ref"))
                    assertTrue(case, out.values.all { it.isEmpty() })
                }
                "reaction_open", "reaction_body" -> {
                    val m = v.o("messages").o(r.s("msg"))
                    val re = reaction(r.o("reaction"))
                    val pk = devs[re.dev]!!.signPk
                    assertNull(case, rc.open(re, unb64(m.s("key"))!!, m.s("chat_ref"), m.s("id"), pk))
                }
                "edit" -> {
                    val e = msgs[r.s("edit")]!!
                    val o = v.o("messages").o(r.s("original"))
                    val plain = fed.decrypt(e.s("chat_ref"), e.s("id"), e.s("sender_handle"), e.s("sender_device"), e.l("ts"), null, null, e.s("body_ct"), unb64(e.s("key"))!!)
                    val why = ChatX3.editCheck(o.s("id"), o.s("sender_handle"), o.sn("ai") != null, o.d("created"), e.s("edit_of"), e.s("sender_handle"),
                        e.d("created"), Json.parseToJsonElement(plain).jsonObject, v.o("limits").d("edit_window_s"))
                    assertEquals(case, r.s("reason"), why?.code)
                }
                "edit_sig" -> {
                    val e = msgs[r.s("edit")]!!
                    assertFalse(case, fed.verifyMessage(r.s("chat_ref"), e.s("id"), e.s("sender_handle"), e.s("sender_device"), e.l("ts"), null, null,
                        e.s("body_ct"), e.s("sig"), devs[e.s("sender_device")]!!.signPk))
                }
                else -> error("unbekannt: $case")
            }
            n++
        }
        assertEquals(14, n)
    }

    @Test
    fun reactOpenNewestPerPerson() {
        val all = v.o("reactions").values.map { it.jsonObject }
        val first = all.first()
        val pk = devs[first.s("dev")]!!.signPk
        // r1 (👍), r2 (👩🏽‍💻), r0 (zurueckgenommen) von derselben Person: nur die neueste zaehlt -> nichts mehr da
        val out = ReactOpen.run(rc, listOf(ReactOpen.Job(first.s("msg_id"), unb64(first.s("msg_key"))!!, all.map { reaction(it) })),
            mapOf(first.s("by") + "|" + first.s("dev") to pk), first.s("chat_ref"))
        val list = out[first.s("msg_id")]!!
        assertEquals(1, list.size)
        assertEquals("", list[0].e)
        assertTrue(ChatX3.chips(list, "~me").isEmpty())
        // ohne Geraeteschluessel: nichts
        assertTrue(ReactOpen.run(rc, listOf(ReactOpen.Job(first.s("msg_id"), unb64(first.s("msg_key"))!!, all.map { reaction(it) })), emptyMap(), first.s("chat_ref"))
            .values.all { it.isEmpty() })
    }

    @Test
    fun tofuMigrate() {
        val ids = mapOf("ident_a" to "A", "ident_b" to "B")
        val rules = v.o("tofu_migrate")["rules"]!!.jsonArray
        assertEquals(6, rules.size)
        for (x in rules) {
            val r = x.jsonObject
            val v1 = (r["v1"] as? JsonObject)?.let { o ->
                V1Trust(ids[o.s("ident")]!!, o["verified"]!!.jsonPrimitive.booleanOrNull!!, o["changed"]!!.jsonPrimitive.booleanOrNull!!, o.sn("old")?.let { ids[it] })
            }
            val t = FedFormat.tofuMigrate(v1, ids[r.s("seen")]!!, r.l("ver"))
            val want = when (r.s("result")) {
                "trust" -> TofuV2.TRUST
                "warn" -> TofuV2.WARN
                else -> TofuV2.BLOCK
            }
            assertEquals(r.s("step"), want, t.result)
            val st = r["store"] as? JsonObject
            if (st == null) assertNull(r.s("step"), t.store) else {
                val got = t.store!!
                assertEquals(r.s("step"), ids[st.s("ident")], got.ident)
                assertEquals(r.s("step"), st["verified"]!!.jsonPrimitive.booleanOrNull, got.verified)
                assertEquals(r.s("step"), st.l("dver"), got.dver)
                assertEquals(r.s("step"), (st["changed"] as? JsonPrimitive)?.booleanOrNull ?: false, got.changed)
                assertEquals(r.s("step"), st.sn("old")?.let { ids[it] }, got.old)
            }
        }
    }

    /** Ablage: Handle -> Benutzer aus Chats v1, Uebernahme nur ohne trust2-Eintrag und nur bei gleichem Schluessel */
    @Test
    fun storeMigratesOnlySameIdentity() {
        val st = ChatStore(SecureStore(MemorySecretStore())) { "srv|me" }
        val h = "~abcdefghijklmnop@a.example"
        val h2 = "~qrstuvwxyz234567@a.example"
        assertTrue(st.noteHandles(mapOf(h to "bea", h2 to "carl", "kein-handle" to "x")))
        assertEquals(mapOf(h to "bea", h2 to "carl"), st.handleUsers())
        st.observe("bea", "A")
        st.acknowledge("bea", true)                          // v1: ueberprueft
        st.observe("carl", "C")
        st.acknowledge("carl", true)
        // gleicher Schluessel: "ueberprueft" uebernommen, Hinweis "Neue Darstellung, gleicher Schluessel"
        val (t1, s1) = st.observe2(h, "A", 2)
        assertEquals(TofuV2.TRUST, t1)
        assertEquals(Trust2("A", verified = true, dver = 2, migrated = true), s1)
        // spaeter: normaler v2-Weg (kein erneutes Uebernehmen)
        assertEquals(TofuV2.TRUST, st.observe2(h, "A", 2).first)
        // ein Server, der fuer carl einen anderen Schluessel unterschiebt: Blockade (war ueberprueft), nichts gespeichert
        val (t2, s2) = st.observe2(h2, "EVIL", 1)
        assertEquals(TofuV2.BLOCK, t2)
        assertNull(s2)
        assertNull(st.trust2()[h2])
        // "Verstanden" behaelt den Hinweis nur bei gleichem Schluessel
        st.setTrust2(h, "A", true)
        assertTrue(st.trust2()[h]!!.migrated)
        st.setTrust2(h2, "EVIL", false)
        assertFalse(st.trust2()[h2]!!.migrated)
        assertNotNull(st.v1Trust(h))
    }
}
