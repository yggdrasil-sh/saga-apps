package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ai.AiChunk
import io.github.yggdrasil_sh.saga.ai.AiLogic
import io.github.yggdrasil_sh.saga.ai.AiSource
import io.github.yggdrasil_sh.saga.ai.AiStatus
import io.github.yggdrasil_sh.saga.chat.ChatContent
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatStore
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.Recipient
import io.github.yggdrasil_sh.saga.chat.RecipientDevice
import io.github.yggdrasil_sh.saga.chat.TofuResult
import io.github.yggdrasil_sh.saga.data.FedShared
import io.github.yggdrasil_sh.saga.data.MemorySecretStore
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SecureStore
import io.github.yggdrasil_sh.saga.data.ShareState
import io.github.yggdrasil_sh.saga.data.ShareTargets
import io.github.yggdrasil_sh.saga.data.SharedTree
import io.github.yggdrasil_sh.saga.push.PushInfo
import io.github.yggdrasil_sh.saga.push.PushParse
import io.github.yggdrasil_sh.saga.push.WebPushCrypto
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.share.ShareLogic
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KI-Assistent, Mitteilungen (Web Push), Chat-Logik ohne libsodium, Foederation */
class V2FeaturesTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    // ---------------------------------------------------------------- KI-Assistent

    @Test
    fun aiCitationsOnlyForKnownSources() {
        val src = listOf(AiSource(1, 10, "Plan"), AiSource(2, null, "Seite ohne Zugriff"))
        val segs = AiLogic.segments("Siehe [1] und [2], aber nicht [7].", src)
        assertEquals(
            listOf(AiLogic.Seg.Text("Siehe "), AiLogic.Seg.Ref(1), AiLogic.Seg.Text(" und "), AiLogic.Seg.Ref(2), AiLogic.Seg.Text(", aber nicht [7]."))
            , segs,
        )
        assertEquals(listOf(AiLogic.Seg.Text("ohne Verweise")), AiLogic.segments("ohne Verweise", emptyList()))
    }

    @Test
    fun aiStreamChunks() {
        val s = AiLogic.chunk("sources", """{"sources":[{"n":1,"id":5,"title":"A","owner":"Bert"}]}""", json) as AiChunk.Sources
        assertEquals("Bert", s.sources[0].owner)
        assertEquals("Hal", (AiLogic.chunk("delta", """{"t":"Hal"}""", json) as AiChunk.Delta).text)
        val d = (AiLogic.chunk("done", """{"quota_left":4,"tasks":["a","b"],"blocks":[{"id":"x","type":"paragraph","replaceable":true}],"segments":["neu"],"truncated":true}""", json) as AiChunk.Done).done
        assertEquals(4, d.quotaLeft)
        assertEquals(listOf("a", "b"), d.tasks)
        assertTrue(d.blocks!![0].replaceable)
        assertEquals(listOf("neu"), d.segments)
        assertTrue(d.truncated)
        val e = AiLogic.chunk("error", """{"error":"Kontingent aufgebraucht","partial":true}""", json) as AiChunk.Failed
        assertEquals(UiText.Raw("Kontingent aufgebraucht"), e.error)
        assertTrue(e.partial)
        assertNull(AiLogic.chunk("unknown", "{}", json))
        val st = json.decodeFromString<AiStatus>("""{"enabled":true,"host":"ollama.lan:11434","quota":20,"used":3,"quota_left":17,"history_allowed":true,"notice":"x","targets":["en","de"]}""")
        assertEquals(17, st.quotaLeft)
        assertEquals(1000, st.qMax)
        assertTrue(!json.decodeFromString<AiStatus>("""{"enabled":false}""").enabled)
    }

    // ---------------------------------------------------------------- Mitteilungen

    /** RFC 8291 Anhang A (derselbe Testvektor wie tests/push.py im Server) */
    @Test
    fun webPushDecryptRfc8291() {
        val keys = WebPushCrypto.fromScalar(
            WebPushCrypto.unb64u("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"),
            WebPushCrypto.unb64u("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
            WebPushCrypto.unb64u("BTBZMqHH6r4Tts7J_aSIgg"),
        )
        val body = WebPushCrypto.unb64u(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_" +
                "yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
        )
        assertEquals("When I grow up, I want to be a watermelon", String(WebPushCrypto.decrypt(body, keys)))
        // eigenes Schluesselpaar: oeffentlicher Schluessel 65 Byte, Auth 16 Byte (base64url ohne Padding)
        val k = WebPushCrypto.generate()
        assertEquals(65, k.publicRaw.size)
        assertEquals(4.toByte(), k.publicRaw[0])
        assertEquals(16, WebPushCrypto.unb64u(k.authB64).size)
        assertTrue(!k.p256dh.contains('='))
    }

    @Test
    fun pushPayloads() {
        val plain = """{"title":"Saga","body":"Neue Nachricht von Bert","url":"/chats#c5","tag":"chat-5","kind":"chat.message","ts":1760000000}"""
        val m = PushParse.parse(plain.toByteArray(), null)!!
        assertEquals("chat-5", m.tag)
        assertEquals("/chats#c5", m.url)
        assertNull(PushParse.parse("kaputt".toByteArray(), null))
        // mit Schluesseln angemeldet: unverschluesselte (untergeschobene) Mitteilungen verwerfen
        assertNull(PushParse.parse(plain.toByteArray(), WebPushCrypto.generate()))
        val info = json.decodeFromString<PushInfo>(
            """{"server":{"enabled":true,"webpush":true,"vapid_public":"BD","ntfy":true,"unifiedpush":false,"apns":false},
               "settings":{"cats":{"share":true,"comment":false,"chat":true,"publish":true},"names":true,"quiet":{"on":true,"from":"22:00","to":"07:00","tz":"Europe/Berlin"}},
               "categories":["share","comment","chat","publish"],"devices":[{"id":3,"type":"unifiedpush","name":"Pixel","where":"ntfy.sh","app":true,"this_device":true,"encrypted":true}],"limit":20}""",
        )
        assertTrue(!info.server.unifiedpush)
        assertEquals(false, info.settings.cats["comment"])
        assertTrue(info.devices[0].thisDevice)
    }

    // ---------------------------------------------------------------- Chat (ohne libsodium)

    @Test
    fun chatNamesAndContent() {
        val me = ChatPerson("anna", "Anna", me = true)
        val bert = ChatPerson("bert", "Bert")
        val cara = ChatPerson("cara", "Cara")
        assertEquals("Bert", ChatLogic.displayName(ChatSummary(1, "dm", name = "Bert", members = listOf(me, bert)), null))
        assertEquals("Bert, Cara", ChatLogic.displayName(ChatSummary(2, "free", members = listOf(me, bert, cara)), null))
        assertEquals("Ausflug", ChatLogic.displayName(ChatSummary(2, "free", members = listOf(me, bert, cara)), "Ausflug"))
        val c = json.decodeFromString<ChatContent>(ChatLogic.content("Hallo [[note:42]]", emptyList(), listOf(42)))
        assertEquals("text", c.type)
        assertEquals(listOf(42L), c.notes)
        assertEquals("Ausflug", json.decodeFromString<ChatContent>(ChatLogic.titleContent("Ausflug")).title)
        // unbekannte Felder werden ignoriert
        assertEquals("x", json.decodeFromString<ChatContent>("""{"v":1,"type":"text","text":"x","neu":{"a":1}}""").text)
    }

    @Test
    fun recipientsAreVerifiedBeforeSending() {
        val pk = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val rc = listOf(
            Recipient("anna", ready = true, identPub = pk, backupPub = pk, backupSig = "s", devices = listOf(RecipientDevice("d1", pk, pk, "s"))),
            Recipient("bert", ready = true, identPub = pk, backupPub = pk, backupSig = "s", devices = listOf(RecipientDevice("d2", pk, pk, "s"), RecipientDevice("d3", pk, pk, "s"))),
            Recipient("cara", ready = false),
        )
        val ok = ChatLogic.recipientKeys(rc, { _, _ -> true }, { true })!!
        assertEquals(setOf("backup:anna", "d1", "backup:bert", "d2", "d3"), ok.keys)
        // ein ungueltiges Geraete-Zertifikat oder ein ungueltiger Backup-Schluessel -> nicht senden
        assertNull(ChatLogic.recipientKeys(rc, { _, d -> d.id != "d3" }, { true }))
        assertNull(ChatLogic.recipientKeys(rc, { _, _ -> true }, { it.username != "bert" }))
    }

    @Test
    fun tofuRemembersIdentities() {
        val st = ChatStore(SecureStore(MemorySecretStore())) { "srv|uid:anna" }
        assertEquals(TofuResult.NEW, st.observe("bert", "K1"))
        assertEquals(TofuResult.SAME, st.observe("bert", "K1"))
        assertEquals(TofuResult.CHANGED, st.observe("bert", "K2"))   // Warnung "Sicherheitsnummer geändert"
        assertTrue(st.known()["bert"]!!.changed)
        st.acknowledge("bert", verified = true)
        assertTrue(st.known()["bert"]!!.verified && !st.known()["bert"]!!.changed)
        assertNull(st.keys())
    }

    // ---------------------------------------------------------------- Foederation

    @Test
    fun federationIdsAndShared() {
        val r = json.decodeFromString<FedShared>(
            """{"items":[{"id":-2049,"title":"Fremd","icon":"/api/fed/files/-2049/abc","parent_id":null,"kind":"page","role":"edit","root":true,
               "unread":false,"updated":1760000000,"tags":[],"shared":true,"pinned":false,
               "owner":{"name":"Anna Eigner (anna@b.example)","username":"@anna@b.example","me":false,"remote":true,"handle":"anna@b.example"},
               "remote":{"peer":1,"host":"b.example","name":"B"}},
               {"id":-3073,"title":"Kind","parent_id":-2049,"role":"edit","owner":{"name":"Anna Eigner (anna@b.example)","remote":true}}],
               "unreachable":["c.example"]}""",
        )
        val p = r.items[0]
        assertTrue(p.isRemote && p.foreign)
        assertEquals("b.example", p.remote?.host)
        assertEquals("anna@b.example", p.owner?.handle)
        assertEquals(listOf("c.example"), r.unreachable)
        // grosse IDs (64 Bit) bleiben erhalten
        val big = json.decodeFromString<PageMeta>("""{"id":-3221225473025,"title":"x"}""")
        assertEquals(-3221225473025L, big.id)
        // Baum "Mit mir geteilt" mit negativen IDs
        val rows = SharedTree.rows(r.items, setOf(-2049L))
        assertEquals(listOf(-2049L, -3073L), rows.map { it.page.id })
        // Navigation: negative Seiten-IDs und Chats ueberleben das Sichern
        assertEquals(Dest.Page(-2049), Dest.decode(Dest.encode(Dest.Page(-2049))))
        assertEquals(Dest.Chat(7), Dest.decode(Dest.encode(Dest.Chat(7))))
        assertEquals(Dest.Notifications, Dest.decode("notifications"))
        assertNotNull(Dest.decode("push"))
    }

    @Test
    fun federationSharing() {
        val st = json.decodeFromString<ShareState>(
            """{"enabled":true,"federation":true,"shares":[{"type":"remote","target":"@bert@b.example","name":"Bert (bert@b.example)","role":"edit","effective":"edit","remote":true},
               {"type":"remote_group","target":"team@b.example","name":"team@b.example","role":"view"}]}""",
        )
        assertTrue(st.federation)
        assertEquals(UiText.Res(R.string.fed_other_instance), ShareLogic.entrySub("remote", "@bert@b.example", null))
        assertEquals(UiText.Res(R.string.fed_other_group), ShareLogic.entrySub("remote_group", "team@b.example", null))
        assertTrue(ShareLogic.taken(st).containsKey("remote:@bert@b.example"))
        val t = json.decodeFromString<ShareTargets>("""{"users":[],"groups":[],"remote":[{"type":"remote","target":"@bert@b.example","name":"Bert (bert@b.example)","host":"b.example"}]}""")
        assertEquals("b.example", t.remote[0].host)
        assertArrayEquals(byteArrayOf(), byteArrayOf())
    }
}
