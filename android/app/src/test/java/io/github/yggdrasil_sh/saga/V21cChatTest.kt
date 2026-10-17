package io.github.yggdrasil_sh.saga

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.github.yggdrasil_sh.saga.chat.ChatBlobCache
import io.github.yggdrasil_sh.saga.chat.ChatContent
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unhex
import io.github.yggdrasil_sh.saga.chat.ChatImages
import io.github.yggdrasil_sh.saga.chat.ChatLimits
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 2.1-c: Chat-Bilder mit Vorschaubild - Testvektor "image" (tests/chat_vectors.json), Groessen, Plattencache */
class V21cChatTest {
    private val c = ChatCrypto(LazySodiumJava(SodiumJava()))
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    private fun load(): JsonObject =
        Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("chat_image_vectors.json")!!.bufferedReader().readText()).jsonObject

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun imageVector() {
        val v = load()
        val d = v["device"]!!.jsonObject
        val m = v["image"]!!.jsonObject
        val dev = c.device(d.s("id"), unhex(d.s("enc_seed_hex")), unhex(d.s("sign_seed_hex")))
        val ts = m["ts"]!!.jsonPrimitive.long
        val enc = c.encrypt(
            m.s("chat_id"), m.s("sender"), dev, m.s("body_json"), emptyMap(), replyTo = null, id = m.s("id"),
            ts = ts, key = unb64(m.s("key"))!!, nonce = unb64(m.s("nonce"))!!,
        )
        assertEquals(m.s("body_ct"), enc.bodyCt)
        assertEquals(m.s("sig"), enc.sig)
        // Inhalt lesen: Vorschaubild vorhanden, <= 320 px, Kennungen fuer die Sende-Anfrage
        val content = json.decodeFromString(ChatContent.serializer(), m.s("body_json"))
        val f = content.files.single()
        val th = f.th!!
        assertEquals(4000, f.w)
        assertEquals(3000, f.h)
        assertTrue(maxOf(th.w, th.h) <= ChatImages.THUMB_MAX)
        assertEquals(m["send_files"]!!.jsonArray.map { it.jsonPrimitive.content }, ChatLogic.sendFileIds(content.files))
        // eigene Ausgabe ergibt denselben Inhalt (Feldreihenfolge egal)
        assertEquals(Json.parseToJsonElement(m.s("body_json")), Json.parseToJsonElement(ChatLogic.content(content.text, content.files, content.notes)))
        // beide Dateien ver-/entschluesseln
        for (part in listOf("orig", "thumb")) {
            val p = m[part]!!.jsonObject
            val plain = unb64(p.s("plain_b64"))!!
            val (blob, _) = c.encryptFile(m.s("chat_id"), plain, unb64(p.s("key"))!!, unb64(p.s("nonce"))!!)
            assertEquals(part, p.s("blob"), b64(blob))
            assertArrayEquals(plain, c.decryptFile(m.s("chat_id"), unb64(p.s("blob"))!!, unb64(p.s("key"))!!))
        }
        val tp = unb64(m["thumb"]!!.jsonObject.s("plain_b64"))!!
        assertEquals(th.s, tp.size.toLong())
        assertTrue(tp[0] == 0xFF.toByte() && tp[1] == 0xD8.toByte() && tp[2] == 0xFF.toByte())
        assertEquals(th.k, m["thumb"]!!.jsonObject.s("key"))
    }

    @Test fun oldMessagesWithoutThumb() {
        val f = json.decodeFromString(ChatContent.serializer(), """{"v":1,"type":"text","text":"","files":[{"id":"a","k":"x","n":"b.png","m":"image/png","s":5}]}""").files[0]
        assertNull(f.th)
        assertNull(f.w)
        assertEquals(listOf("a"), ChatLogic.sendFileIds(listOf(f)))
        // ohne w/h/th: keine zusaetzlichen Felder im Inhalt (alte Clients sehen dasselbe wie bisher)
        val out = Json.parseToJsonElement(ChatLogic.content("", listOf(f), emptyList())).jsonObject["files"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("id", "k", "n", "m", "s"), out.keys)
        assertTrue(ChatFile(m = "image/webp").isImage)
        // Server ohne file_ids_per_msg: Standard wie bisher
        assertNull(json.decodeFromString(ChatLimits.serializer(), """{"files_per_msg":10}""").fileIdsPerMsg)
        assertEquals(20, json.decodeFromString(ChatLimits.serializer(), """{"files_per_msg":10,"file_ids_per_msg":20}""").fileIdsPerMsg)
    }

    @Test fun sizes() {
        assertEquals(320 to 240, ChatImages.thumbSize(4000, 3000))
        assertEquals(240 to 320, ChatImages.thumbSize(3000, 4000))
        assertEquals(100 to 50, ChatImages.thumbSize(100, 50))           // nie hochskalieren
        assertEquals(320 to 1, ChatImages.thumbSize(10000, 10))
        assertEquals(0 to 0, ChatImages.thumbSize(0, 10))
        assertEquals(8, ChatImages.sampleSize(4000, 3000))                 // 4000/8 = 500 >= 320, /16 = 250 < 320
        assertEquals(1, ChatImages.sampleSize(300, 200))
        assertEquals(280 to 210, ChatImages.displaySize(4000, 3000))
        assertEquals(48 to 280, ChatImages.displaySize(10, 2000))
        assertNull(ChatImages.displaySize(null, 10))
        // Kennungen: neuer Server 20 -> alle Bilder; alter Server 10 -> hoechstens 10 - Anhaenge
        assertEquals(10, ChatImages.thumbBudget(10, 10, 10, 20))
        assertEquals(5, ChatImages.thumbBudget(5, 5, 10, null))
        assertEquals(4, ChatImages.thumbBudget(6, 6, 10, null))
        assertEquals(0, ChatImages.thumbBudget(10, 3, 10, null))
        assertEquals(1, ChatImages.thumbBudget(3, 2, 4, null))
    }

    @Test fun blobCacheLru() {
        val dir = Files.createTempDirectory("blobs").toFile()
        val cache = ChatBlobCache(dir, maxBytes = 1000)
        for (k in listOf("a", "b", "c", "d")) {
            cache.put(k, ByteArray(240))
            Thread.sleep(20)
        }
        assertTrue(cache.get("a") != null)                                 // a zuletzt benutzt -> b ist der aelteste
        Thread.sleep(20)
        cache.put("e", ByteArray(100))                                      // 1060 > 1000 -> aelteste raus bis <= 900
        assertNull(cache.get("b"))
        assertTrue(listOf("a", "c", "d", "e").all { cache.get(it) != null })
        assertTrue(cache.size() <= 1000)
        cache.put("../boese", ByteArray(1))                                 // unsichere Namen nie
        assertNull(cache.get("../boese"))
        cache.put("gross", ByteArray(400))                                  // > maxBytes/4: nicht cachen
        assertNull(cache.get("gross"))
        cache.clear()
        assertNull(cache.get("a"))
        assertEquals(0L, cache.size())
    }
}
