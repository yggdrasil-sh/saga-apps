package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatContent
import io.github.yggdrasil_sh.saga.chat.ChatFmt
import io.github.yggdrasil_sh.saga.chat.ChatList
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatPreview
import io.github.yggdrasil_sh.saga.chat.ChatStd
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.ChatSys
import io.github.yggdrasil_sh.saga.chat.ChatBlocks
import io.github.yggdrasil_sh.saga.chat.ChatThumb
import io.github.yggdrasil_sh.saga.chat.ChatWrap
import io.github.yggdrasil_sh.saga.chat.FedSysText
import io.github.yggdrasil_sh.saga.chat.GroupInfo
import io.github.yggdrasil_sh.saga.chat.MessagesResponse
import io.github.yggdrasil_sh.saga.chat.PinsResult
import io.github.yggdrasil_sh.saga.chat.UnfurlResult
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saga 2.5 Chat-Standard (docs/APP-API-CHAT.md 6a, 8, 9): Formatierung gegen die Vektoren aus static/chat-fmt.js
 * (vectors/chat_fmt.json, erzeugt mit tools/chat-fmt-vectors.js), Feature-Gating, Rechte, Stummschalten,
 * Erwaehnungen, Linkvorschau. chat_standard_server.json = echte Antworten eines Testservers (testing, Stufe 3;
 * Anmeldenamen durch uid-a/b/c ersetzt, Host a.test:8191, Linkziel link.test).
 */
class ChatStdTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private fun res(n: String) = javaClass.classLoader!!.getResourceAsStream(n)!!.bufferedReader().readText()
    private val fx: JsonObject = Json.parseToJsonElement(res("chat_standard_server.json")).jsonObject
    private fun chat(k: String): ChatSummary = json.decodeFromJsonElement(ChatSummary.serializer(), fx[k]!!.let { e ->
        (e.jsonObject["chat"] ?: e)
    })
    private val all = ChatStd.Features.of(fx["version_features"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())

    // ------------------------------------------------------------ Formatierung (identisch zu chat-fmt.js)

    @Test
    fun formatVectorsMatchWeb() {
        val v = Json.parseToJsonElement(res("vectors/chat_fmt.json")).jsonObject["vectors"]!!.jsonArray
        assertTrue(v.size >= 60)
        val bad = ArrayList<String>()
        for (e in v) {
            val o = e.jsonObject
            val input = o["in"]!!.jsonPrimitive.content
            val html = ChatFmt.serialize(ChatFmt.render(input))
            if (html != o["html"]!!.jsonPrimitive.content) bad.add("render ${'"'}$input${'"'}: $html != ${o["html"]}")
            val st = ChatFmt.strip(input)
            if (st != o["strip"]!!.jsonPrimitive.content) bad.add("strip ${'"'}$input${'"'}: $st != ${o["strip"]}")
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun formatNeverProducesMarkupFromText() {
        // Injection: HTML/Skript bleibt Text-Knoten; die Elemente sind nur die festen Namen
        val t = "<img src=x onerror=\"alert(1)\"> *<script>alert(2)</script>* [x](javascript:alert(3))\n```\n</code></pre><b>x</b>\n```"
        val root = ChatFmt.render(t)
        val tags = HashSet<String>()
        val texts = StringBuilder()
        fun walk(n: ChatFmt.Node) {
            when (n) {
                is ChatFmt.Text -> texts.append(n.text)
                is ChatFmt.El -> { tags.add(n.tag); n.children.forEach { walk(it) } }
            }
        }
        walk(root)
        assertTrue(tags.toString(), tags.all { it in setOf("box", "strong", "em", "s", "code", "pre", "ul", "li", "blockquote") })
        assertTrue(texts.contains("<img src=x onerror=\"alert(1)\">"))
        assertTrue(texts.contains("<script>alert(2)</script>"))
        assertTrue(texts.contains("[x](javascript:alert(3))"))
        // Code-Inhalt geht nie durch die Link-/Erwaehnungserkennung
        val leafs = ArrayList<String>()
        val p = object : ChatFmt.Sink {
            val sb = StringBuilder()
            override val length get() = sb.length
            override fun text(s: String) { sb.append(s) }
            override fun leaf(s: String) { leafs.add(s); sb.append(s) }
            override fun span(start: Int, end: Int, kind: ChatFmt.Kind) {}
        }
        ChatFmt.flatten(ChatFmt.render("`https://x.org` <@~abcdefghijklmnop>\n```\nhttps://y.org\n```"), p)
        assertTrue(leafs.joinToString("|"), leafs.none { it.contains("x.org") || it.contains("y.org") })
        assertTrue(leafs.any { it.contains("<@~abcdefghijklmnop>") })
    }

    @Test
    fun flattenBlocksOnOwnLines() {
        val p = ChatFmt.Plain()
        ChatFmt.flatten(ChatFmt.render("text\n- eins\n- *zwei*\n> zitat\nende\n```\ncode\n```"), p)
        assertEquals("text\n• eins\n• zwei\nzitat\nende\ncode", p.toString())
        val kinds = p.spans.map { it.third }
        assertTrue(ChatFmt.Kind.BULLET in kinds && ChatFmt.Kind.BOLD in kinds && ChatFmt.Kind.QUOTE in kinds && ChatFmt.Kind.PRE in kinds)
        val bold = p.spans.first { it.third == ChatFmt.Kind.BOLD }
        assertEquals("zwei", p.toString().substring(bold.first, bold.second))
        val pre = p.spans.first { it.third == ChatFmt.Kind.PRE }
        assertEquals("code", p.toString().substring(pre.first, pre.second))
        val q = ChatFmt.Plain()
        ChatFmt.flatten(ChatFmt.render("a\n\nb"), q)
        assertEquals("a\n\nb", q.toString())
    }

    // ------------------------------------------------------------ Feature-Gating

    @Test
    fun featuresFromRealServer() {
        assertTrue(all.pageMembers && all.groupInfo2 && all.modDel && all.msgPin && all.block && all.report)
        assertTrue(all.mute2 && all.chatPin && all.markUnread && all.hide && all.mention && all.fmt && all.unfurl && all.media)
        val none = ChatStd.Features.of(setOf("chatadm1"))
        val c = chat("free_admin")
        val r = ChatStd.rights(c, none)
        assertFalse(r.info2 || r.perms || r.pin || r.moderate || r.showPerms)
        // Senden haengt nur am Feld (aeltere Server: fehlt -> erlaubt)
        assertTrue(ChatStd.rights(ChatSummary(id = 1, kind = "free"), none).send)
        // Seiten-Chat: Hinzufuegen nur mit pagechatmembers1
        val p = chat("page_owner")
        assertFalse(ChatStd.canAdd(p, none, GroupInfo.rights(p, true)))
        assertTrue(ChatStd.canAdd(p, all, GroupInfo.rights(p, true)))
        assertEquals(ChatStd.PageRemove.None, ChatStd.pageRemove(p, p.members.first { !it.me }, none))
    }

    // ------------------------------------------------------------ Rechte (echte Antworten)

    @Test
    fun rightsAdminAndRestrictedMember() {
        val a = chat("free_admin")
        assertEquals("admins", a.permSend)
        assertEquals(2, a.pins.size)
        val ra = ChatStd.rights(a, all)
        assertTrue(ra.send && ra.info2 && ra.perms && ra.showPerms && ra.pin && ra.moderate)
        val b = chat("free_member_restricted")
        val rb = ChatStd.rights(b, all)
        assertFalse(rb.send || rb.info2 || rb.perms || rb.pin || rb.moderate)
        assertTrue("eingeschraenkte Gruppe: Rechte-Bereich lesend sichtbar", rb.showPerms)
        assertEquals(UiText.Res(io.github.yggdrasil_sh.saga.R.string.std_send_admins_only), ChatStd.sendBlockedText(b))
        assertNull(ChatStd.sendBlockedText(a))
        // gesperrter Direkt-Chat
        val dm = chat("dm_blocked")
        assertTrue(dm.blocked && dm.canSend == false)
        assertEquals(UiText.Res(io.github.yggdrasil_sh.saga.R.string.std_send_blocked_person), ChatStd.sendBlockedText(dm))
        assertTrue(dm.members.first { !it.me }.blocked)
        // Desc-Nachricht im Chat-Objekt (verschluesselt, mit Schluessel)
        assertEquals("desc", a.descMsg?.kind)
        assertTrue(!a.descMsg?.key.isNullOrEmpty())
    }

    @Test
    fun pageChatMembersRealServer() {
        val p = chat("page_owner")
        assertEquals(true, p.canManage)
        val me = p.members.first { it.me }
        val other = p.members.first { !it.me }
        assertEquals("owner", me.via)
        assertEquals(ChatStd.PageRemove.None, ChatStd.pageRemove(p, me, all))
        assertEquals(ChatStd.PageRemove.Ok, ChatStd.pageRemove(p, other, all))
        val no = ChatStd.pageRemove(p, other.copy(removable = false, via = "group"), all)
        assertEquals(ChatStd.PageRemove.No(UiText.Res(io.github.yggdrasil_sh.saga.R.string.std_via_group)), no)
        assertEquals(UiText.Res(io.github.yggdrasil_sh.saga.R.string.std_hint_page_owner), ChatStd.membersHint(p, all))
        // Mitglied (nicht Eigentuemer): kein via/removable, nur Hinweis
        val m = chat("page_member")
        assertNull(m.members.first { !it.me }.via)
        assertFalse(ChatStd.canAdd(m, all, GroupInfo.rights(m, true)))
        assertEquals(UiText.Res(io.github.yggdrasil_sh.saga.R.string.std_hint_page_other), ChatStd.membersHint(m, all))
        assertEquals(UiText.Res(io.github.yggdrasil_sh.saga.R.string.std_hint_group), ChatStd.membersHint(ChatSummary(id = 9, kind = "group"), all))
        val body = ChatStd.addPageBody("~abc", false, "comment")
        assertEquals("~abc", body["user"]!!.jsonPrimitive.content)
        assertEquals("comment", body["role"]!!.jsonPrimitive.content)
        assertEquals("view", ChatStd.addPageBody("~x@h", true, "admin")["role"]!!.jsonPrimitive.content)
        assertEquals("~x@h", ChatStd.addPageBody("~x@h", true, "edit")["handle"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------ Grabsteine, Pins, Sperren, Melden

    @Test
    fun tombsPinsBlocks() {
        val ms = json.decodeFromString(MessagesResponse.serializer(), "{\"messages\":" + fx["messages_tombs"].toString() + "}").messages
        val t = ms.first { it.kind == "tomb" }
        assertTrue(t.byAdmin)
        assertNull(t.key)
        assertEquals(listOf(true, true), listOf(fx["moddel"]!!.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean(), fx["moddel"]!!.jsonObject["by_admin"]!!.jsonPrimitive.content.toBoolean()))
        val pins = json.decodeFromJsonElement(PinsResult.serializer(), fx["pin_post"]!!)
        assertEquals(2, pins.pins.size)
        assertEquals(409, fx["pin_409"]!!.jsonObject["status"]!!.jsonPrimitive.int)
        assertEquals(pins.pins[1], ChatStd.pinAt(pins.pins, 0))
        assertEquals(pins.pins[0], ChatStd.pinAt(pins.pins, 1))
        assertEquals(pins.pins[1], ChatStd.pinAt(pins.pins, 2))
        assertNull(ChatStd.pinAt(emptyList(), 0))
        assertEquals(listOf(pins.pins[1]), ChatStd.dropPin(pins.pins, pins.pins[0]))
        // Sperrliste: Konto dieses Servers mit Anzeigename, nie Anmeldename
        val bl = json.decodeFromJsonElement(ChatBlocks.serializer(), fx["blocks_get"]!!).blocks
        assertTrue(bl.single().local)
        assertEquals("Cora Std", bl.single().name)
        assertTrue(bl.single().target.startsWith("~") && '@' in bl.single().target)
        // Gruppe mit gesperrter Person: deren Nachrichten einklappen (Absender v2 = Handle)
        val g = chat("free_with_blocked")
        val cora = g.members.first { it.name == "Cora Std" }
        assertTrue(cora.blocked)
        assertTrue(ChatStd.blockedInGroup(g, ChatPerson(username = cora.handle!!), cora.handle))
        assertFalse(ChatStd.blockedInGroup(g, ChatPerson(username = g.members.first { it.name == "Bert Std" }.handle!!), null))
        assertFalse(ChatStd.blockedInGroup(chat("dm_blocked"), chat("dm_blocked").members.first { !it.me }, null))
        assertEquals(cora.handle, ChatStd.blockTarget(cora))
        assertEquals("~abcdefghijklmnop", ChatStd.blockTarget(ChatPerson(username = "u", ref = "~abcdefghijklmnop")))
        assertNull(ChatStd.blockTarget(ChatPerson(username = "anmeldename", ref = "anmeldename")))
        // Melden
        val rb = ChatStd.reportBody(1, List(25) { "m$it" } + "m1", "spam", " x ".repeat(1000))
        assertEquals(20, rb["msgs"]!!.jsonArray.size)
        assertEquals("spam", rb["reason"]!!.jsonPrimitive.content)
        assertEquals(2000, rb["text"]!!.jsonPrimitive.content.length)
        assertEquals("other", ChatStd.reportBody(1, emptyList(), "boese", null)["reason"]!!.jsonPrimitive.content)
        assertEquals("Bert: hi\nmeins", ChatStd.reportPrefill(listOf("Bert" to "hi", "" to "meins")))
    }

    @Test
    fun permSystemLine() {
        val me = "~me@a.test"
        fun sys(j: String) = ChatLogic.sys(j)
        val k = sys("""{"type":"perm","perm_send":"admins","by":{"name":"Anna","host":"a.test","handle":"~an@a.test"}}""")
        assertEquals("admins", k.permSend)
        assertNull(k.permInfo)
        val t = FedSysText.text(k, me) as UiText.Res
        assertEquals(io.github.yggdrasil_sh.saga.R.string.std_join_sentences, t.id)
        val head = t.args[0] as UiText.Res
        assertEquals(io.github.yggdrasil_sh.saga.R.string.std_sys_perm_by, head.id)
        assertEquals(UiText.Raw("Anna"), head.args[0])
        assertEquals(UiText.Res(io.github.yggdrasil_sh.saga.R.string.std_sys_send_admins), t.args[1])
        // ich selbst, beide Felder; ohne by
        val mine = ChatStd.permSysText(sys("""{"type":"perm","perm_info":"all","perm_send":"all","by":{"name":"Ich","handle":"$me"}}"""), me) as UiText.Res
        val inner = mine.args[0] as UiText.Res
        assertEquals(UiText.Res(io.github.yggdrasil_sh.saga.R.string.std_sys_perm_you), inner.args[0])
        assertEquals(UiText.Res(io.github.yggdrasil_sh.saga.R.string.std_sys_info_all), mine.args[1])
        assertEquals(UiText.Res(io.github.yggdrasil_sh.saga.R.string.std_sys_perm_admin), ChatStd.permSysText(sys("""{"type":"perm","by":null}"""), me))
        // Systemzeile admin bleibt (Regression)
        assertTrue(FedSysText.text(ChatSys(type = "admin", on = true, target = io.github.yggdrasil_sh.saga.chat.SysPerson("B", null, "~b@a.test")), me) is UiText.Res)
    }

    // ------------------------------------------------------------ Alltag

    @Test
    fun muteAndListRealServer() {
        val p = chat("patch_alltag")
        assertEquals("mentions", p.notify)
        assertTrue(p.pinned && p.pinnedAt != null)
        val now = p.mutedUntil!! - 1800
        assertTrue(ChatStd.mutedNow(p, now))
        assertEquals(ChatStd.MuteState.Until((now + 1800).toLong()), ChatStd.muteState(p, now))
        assertEquals(ChatStd.MuteState.Off, ChatStd.muteState(p, now + 1801))
        val h = chat("page_hidden")
        assertTrue(h.hidden && h.canHide)
        assertEquals(ChatStd.MuteState.Always, ChatStd.muteState(h, 0.0))
        // aelterer Server: nur muted
        assertEquals(ChatStd.MuteState.Always, ChatStd.muteState(ChatSummary(id = 1, muted = true), 5.0))
        assertEquals(ChatStd.MuteState.Off, ChatStd.muteState(ChatSummary(id = 1, muted = false), 5.0))
        assertEquals(1000L + 3600, ChatStd.mutedUntil(ChatStd.MuteFor.HOUR, 1000))
        assertEquals(1000L + 8 * 3600, ChatStd.mutedUntil(ChatStd.MuteFor.HOURS8, 1000))
        assertEquals(1000L + 7 * 86400, ChatStd.mutedUntil(ChatStd.MuteFor.WEEK, 1000))
        assertEquals(-1L, ChatStd.mutedUntil(ChatStd.MuteFor.ALWAYS, 1000))
        assertEquals(0L, ChatStd.mutedUntil(ChatStd.MuteFor.OFF, 1000))
        val u = chat("patch_unread")
        assertTrue(u.unreadMark && u.unread >= 1 && ChatStd.unreadDot(u))
        // Liste: ausgeblendete fehlen, hidden_count; ?hidden=1 nur sie
        val lb = json.decodeFromJsonElement(ChatList.serializer(), fx["list_b"]!!)
        val lh = json.decodeFromJsonElement(ChatList.serializer(), fx["list_b_hidden"]!!)
        assertEquals(1, lb.hiddenCount)
        assertTrue(lb.chats.none { it.hidden })
        assertTrue(lh.chats.all { it.hidden } && lh.chats.isNotEmpty())
        assertEquals(400, fx["hide_free_400"]!!.jsonObject["status"]!!.jsonPrimitive.int)
        assertFalse(chat("free_admin").canHide)
        // Sortierung: angeheftete oben (nach pinned_at), dann neueste
        val l = ChatStd.sortList(listOf(
            ChatSummary(id = 1, lastAt = 50.0), ChatSummary(id = 2, lastAt = 10.0, pinned = true, pinnedAt = 2.0),
            ChatSummary(id = 3, lastAt = 99.0), ChatSummary(id = 4, lastAt = 1.0, pinned = true, pinnedAt = 1.0),
        ))
        assertEquals(listOf(4L, 2L, 3L, 1L), l.map { it.id })
    }

    // ------------------------------------------------------------ Erwaehnungen

    @Test
    fun mentions() {
        val c = chat("free_admin")
        val me = c.members.first { it.me }
        assertEquals(c.myHandle, c.meRef)
        val bert = c.members.first { it.name == "Bert Std" }
        assertEquals(bert.handle, ChatStd.memberRef(bert))
        assertEquals("~abc", ChatStd.memberRef(ChatPerson(username = "u", ref = "~abc")))
        // Auswahl: ohne mich, nach Anzeigename
        assertEquals(listOf("Bert Std"), ChatStd.mentionCandidates(c, "ber").map { it.name })
        assertTrue(ChatStd.mentionCandidates(c, "anna").isEmpty())
        // Token -> Name; ich; unbekannt
        val tok = "<@" + bert.handle + "> und <@" + c.meRef + "> und <@~aaaaaaaaaaaaaaaa>"
        assertEquals("@Bert Std und @Anna Std und @Unbekannt", ChatStd.plainMentions(tok, c, null, "Unbekannt"))
        assertTrue(ChatStd.mentionsMe(c, tok))
        assertFalse(ChatStd.mentionsMe(c, "<@" + bert.handle + ">"))
        assertTrue(ChatStd.isMe(c, me.handle!!))
        // kein Token: falsche Form bleibt Text
        assertEquals("<@anmeldename> <@~ABC>", ChatStd.plainMentions("<@anmeldename> <@~ABC>", c, null, "?"))
        // Senden: Labels -> Token (laengste zuerst), Kennungen einmal
        val (t, refs) = ChatStd.applyPeople("Hallo @Bert Std und @Bert und nochmal @Bert Std", mapOf("@Bert Std" to "~b@a", "@Bert" to "~x@a"))
        assertEquals("Hallo <@~b@a> und <@~x@a> und nochmal <@~b@a>", t)
        assertEquals(listOf("~b@a", "~x@a"), refs)
        val many = (0 until 60).associate { "@P$it" + "x" to "~r$it" }
        assertEquals(50, ChatStd.applyPeople(many.keys.joinToString(" "), many).second.size)
        assertNull(ChatStd.mentionsJson(emptyList()))
        // "@" vor dem Cursor
        assertEquals(6 to "Be", ChatStd.atQuery("Hallo @Be", 9))
        assertEquals(0 to "", ChatStd.atQuery("@", 1))
        assertNull(ChatStd.atQuery("mail@x", 6))
        assertEquals("@Bert Std", ChatStd.mentionLabel("Bert   Std"))
        // echter Text mit Token aus dem Testserver (Bert erwaehnt Anna)
        assertTrue(ChatStd.MENTION.containsMatchIn("hallo <@" + me.handle + ">"))
    }

    // ------------------------------------------------------------ Linkvorschau, Medien

    @Test
    fun unfurlAndMedia() {
        val u = json.decodeFromJsonElement(UnfurlResult.serializer(), fx["unfurl_page"]!!)
        assertEquals("Saga & Freunde", u.title)
        assertEquals("Eine Testseite <b>ohne</b> HTML", u.desc)
        assertEquals("image/jpeg", u.image?.mime)
        assertTrue((u.image?.w ?: 999) <= 400)
        assertTrue(ChatStd.usable(u))
        assertFalse(ChatStd.usable(json.decodeFromJsonElement(UnfurlResult.serializer(), fx["unfurl_json"]!!)))
        assertEquals(400, fx["unfurl_bad"]!!.jsonObject["status"]!!.jsonPrimitive.int)
        val img = ChatThumb("fid", "k", "image/jpeg", 400, 267, 1234)
        val p = ChatStd.previewOf(u.copy(title = "t".repeat(300)), img)
        assertEquals(200, p.title.length)
        assertTrue(ChatStd.showPreview(p))
        assertFalse(ChatStd.showPreview(p.copy(url = "javascript:alert(1)")))
        assertFalse(ChatStd.showPreview(p.copy(url = "ftp://x")))
        assertFalse(ChatStd.showPreview(null))
        // Inhalt: preview liest sich zurueck (Empfaenger)
        val c = json.decodeFromString(ChatContent.serializer(), """{"v":1,"type":"text","text":"x","preview":${ChatStd.previewJson(p)}}""")
        assertEquals("fid", c.preview?.img?.id)
        assertEquals(listOf("fid"), ChatStd.fileIds(emptyList(), p))
        assertEquals(listOf("a", "b", "fid"), ChatStd.fileIds(listOf(io.github.yggdrasil_sh.saga.chat.ChatFile(id = "a", th = ChatThumb(id = "b"))), p))
        assertEquals("https://x.org/a", ChatStd.firstUrl("siehe https://x.org/a. und http://y.org"))
        assertNull(ChatStd.firstUrl("ohne link"))
        assertEquals(listOf("https://a.org/x", "http://b.org"), ChatStd.linksIn("(https://a.org/x) und http://b.org!"))
        val img2 = io.github.yggdrasil_sh.saga.chat.ChatFile(m = "image/png")
        val voice = io.github.yggdrasil_sh.saga.chat.ChatFile(m = "audio/ogg", voice = true)
        val pdf = io.github.yggdrasil_sh.saga.chat.ChatFile(m = "application/pdf")
        assertTrue(ChatStd.inTab(img2, ChatStd.MediaTab.MEDIA) && !ChatStd.inTab(img2, ChatStd.MediaTab.FILES))
        assertTrue(ChatStd.inTab(pdf, ChatStd.MediaTab.FILES))
        assertFalse(ChatStd.inTab(voice, ChatStd.MediaTab.FILES) || ChatStd.inTab(voice, ChatStd.MediaTab.MEDIA))
        assertEquals(200, fx["has_file"]!!.jsonObject["status"]!!.jsonPrimitive.int)
    }

    @Test
    fun groupInfoContent() {
        val d = Json.parseToJsonElement(ChatStd.descContent("  " + "x".repeat(600))).jsonObject
        assertEquals("desc", d["type"]!!.jsonPrimitive.content)
        assertEquals(500, d["desc"]!!.jsonPrimitive.content.length)
        val a = Json.parseToJsonElement(ChatStd.avatarContent(null)).jsonObject
        assertEquals("null", a["file"].toString())
        val f = io.github.yggdrasil_sh.saga.chat.ChatFile(id = "i", k = "k", n = "gruppe.jpg", m = "image/jpeg", s = 9, w = 256, h = 256)
        val back = json.decodeFromString(ChatContent.serializer(), ChatStd.avatarContent(f))
        assertEquals("i", back.file?.id)
        assertEquals(256, back.file?.w)
        assertEquals(Triple(100, 0, 300), ChatStd.squareCrop(500, 300))
        assertEquals(Triple(0, 50, 200), ChatStd.squareCrop(200, 300))
        val dd = json.decodeFromString(ChatContent.serializer(), ChatStd.descContent("Hallo"))
        assertEquals("Hallo", dd.desc)
        assertEquals(ChatPreview::class, ChatPreview().javaClass.kotlin)
    }
}
