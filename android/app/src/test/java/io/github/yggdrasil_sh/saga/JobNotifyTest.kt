package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.live.NotifLogic
import io.github.yggdrasil_sh.saga.push.JobNotifyLogic
import io.github.yggdrasil_sh.saga.push.NotifyChannels
import io.github.yggdrasil_sh.saga.push.PushParse
import io.github.yggdrasil_sh.saga.push.PushServerInfo
import io.github.yggdrasil_sh.saga.push.PushSubLogic
import io.github.yggdrasil_sh.saga.push.WebPushCrypto
import io.github.yggdrasil_sh.saga.ui.saga.SagaNotifyPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.6 (Bugs-DB 2270, K-C, jobpush1): Mitteilungen der Kollegin zu Auftraegen gegen die Server-Vektoren
 * (app/src/test/resources/push_kollegin.json = tests/vectors/push_kollegin.json, tools/kollegin_push_vectors.py):
 * Entschluesseln mit den App-Schluesseln je Weg, Kategorie, Kanal, Texte, Knoepfe nach K15, Klartext ohne Kennung,
 * removed, App-Reaktion je Code, Kanal-IDs konstant, keine MessagingStyle/Shortcut/Bubble, Texte in 5 Sprachen.
 */
class JobNotifyTest {
    private val v: JsonObject = Json.parseToJsonElement(javaClass.classLoader!!.getResource("push_kollegin.json")!!.readText()).jsonObject
    private fun s(o: JsonObject?, k: String): String? = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private val keys = v["keys"]!!.jsonObject
    private val pk = WebPushCrypto.fromScalar(
        WebPushCrypto.unb64u(s(keys, "ua_priv")!!), WebPushCrypto.unb64u(s(keys, "ua_pub")!!), WebPushCrypto.unb64u(s(keys, "auth")!!),
    )
    private val cases = v["cases"]!!.jsonArray.map { it.jsonObject }
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test
    fun format() {
        assertEquals("saga-push-kollegin/1", s(v, "format"))
        val cats = v["categories"]!!.jsonArray.map { it.jsonObject }
        assertEquals(cats.map { s(it, "id") }, JobNotifyLogic.CATEGORIES.keys.toList())
        for (c in cats) assertEquals(c["buttons"]!!.jsonArray.map { it.jsonPrimitive.content }, JobNotifyLogic.CATEGORIES[s(c, "id")])
        assertEquals(v["actions"]!!.jsonArray.map { s(it.jsonObject, "a") }, JobNotifyLogic.ACTIONS)
        for (a in v["actions"]!!.jsonArray.map { it.jsonObject }) assertEquals(a["auth"]!!.jsonPrimitive.boolean, s(a, "a") in JobNotifyLogic.AUTH)
        for (r in v["reserved_actions"]!!.jsonArray) assertFalse(r.jsonPrimitive.content in JobNotifyLogic.ACTIONS)
    }

    @Test
    fun casesDecryptAndShow() {
        var n = 0
        for (c in cases) {
            val name = s(c, "name")!!
            val way = s(c, "way")
            val expect = c["expect"]?.jsonObject
            val body = s(c, "body")
            val msg = when {
                body != null -> {
                    val plain = WebPushCrypto.decrypt(WebPushCrypto.unb64u(body), pk)
                    assertEquals("$name Laenge", c["plain_len"]!!.jsonPrimitive.int, plain.size)
                    assertEquals("$name Klartext", s(c, "plain"), String(plain, Charsets.UTF_8))
                    PushParse.parse(WebPushCrypto.unb64u(body), pk)
                }
                way == "unifiedpush-plain" -> PushParse.parse(c["request"]!!.toString().toByteArray(), null)
                else -> continue                                                   // ntfy (App ntfy zeigt) / APNs (iOS)
            }
            assertNotNull(name, msg)
            n++
            if (expect?.containsKey("remove_tag") == true) {
                assertEquals(name, s(expect, "remove_tag"), JobNotifyLogic.removeTag(msg!!))
                assertNull(JobNotifyLogic.plan(msg))
                continue
            }
            val p = JobNotifyLogic.plan(msg!!, "phone")!!
            assertEquals("$name Kategorie", s(expect, "category"), p.category)
            assertEquals("$name Kanal", s(expect, "android_channel"), p.channel)
            assertEquals("$name tag", s(expect, "notification_tag"), p.tag)
            val t = expect!!["text"]!!.jsonObject
            assertEquals("$name Titel", s(t, "title"), p.title)
            assertEquals("$name Text", s(t, "body"), p.body)
            assertEquals("$name Knoepfe", expect["buttons_unlocked_phone"]!!.jsonArray.map { it.jsonPrimitive.content }, p.buttons)
            assertEquals("$name Deep-Link", s(expect, "deeplink"), p.deeplink)
            assertTrue("$name Uhr nur Ruhe-Knoepfe", p.wear.all { it in JobNotifyLogic.QUIET })
            val silent = c["input"]?.jsonObject?.get("silent")?.jsonPrimitive?.boolean ?: false
            assertEquals("$name silent", silent, p.silent)
            // Android < 12 bzw. ohne Sperre: nie approve/undo
            val old = JobNotifyLogic.plan(msg, JobNotifyLogic.device(30, true))!!
            assertTrue("$name API 30", old.buttons.none { it in JobNotifyLogic.AUTH })
            assertTrue("$name ohne Sperre", JobNotifyLogic.plan(msg, JobNotifyLogic.device(34, false))!!.buttons.none { it in JobNotifyLogic.AUTH })
            if (way in v["params"]!!.jsonObject["plain_ways"]!!.jsonArray.map { it.jsonPrimitive.content }) {
                assertNull("$name ohne Kennung", p.job)
                assertTrue(JobNotifyLogic.tokens(msg).isEmpty() && !p.detail && p.url == "/saga?tab=jobs")
            }
        }
        assertEquals(15, n)
    }

    @Test
    fun k15Matrix() {
        val rows = v["k15"]!!.jsonArray.map { it.jsonObject }
        assertEquals(100, rows.size)
        for (r in rows) {
            val b = JobNotifyLogic.k15(s(r, "cat")!!, s(r, "device")!!, s(r, "content") == "on")
            val want = r["buttons"]!!.jsonArray.map { it.jsonObject }
            assertEquals(r.toString(), want.map { s(it, "a") }, b.map { it.a })
            assertEquals(r.toString(), want.map { it["auth"]!!.jsonPrimitive.boolean }, b.map { it.auth })
            assertEquals(r.toString(), r["direct"]!!.jsonArray.map { it.jsonPrimitive.content }, JobNotifyLogic.direct(b, r["locked"]!!.jsonPrimitive.boolean))
        }
        assertEquals("phone", JobNotifyLogic.device(31, true))
        assertEquals("phone_noauth", JobNotifyLogic.device(30, true))
        assertEquals("phone_noauth", JobNotifyLogic.device(35, false))
    }

    @Test
    fun channelsFixed() {
        val ch = v["android_channels"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("saga.jobs.needs.v1", "saga.jobs.feedback.v1"), ch.map { s(it, "id") })
        assertEquals("saga.jobs.needs.v1", NotifyChannels.JOBS_NEEDS)
        assertEquals("saga.jobs.feedback.v1", NotifyChannels.JOBS_FEEDBACK)
        for (c in ch) {
            val id = s(c, "id")!!
            assertEquals(NotifyChannels.G_NOTIFY, NotifyChannels.channelGroup(id))
            assertEquals("G_NOTIFY", s(c, "group"))
            assertEquals(if (s(c, "importance") == "high") 4 else 3, NotifyChannels.importance(1, id))   // fest, nicht vom alten Kanal
            assertTrue(id in NotifyChannels.ALL)
            for (st in c["states"]!!.jsonArray) assertEquals(id, JobNotifyLogic.channel(st.jsonPrimitive.content))
        }
        assertEquals(NotifyChannels.JOBS_NEEDS, NotifyChannels.forKind("job.needs_you"))
        assertEquals(NotifyChannels.JOBS_FEEDBACK, NotifyChannels.forKind("job.done"))
        assertEquals(NotifyChannels.JOBS_FEEDBACK, NotifyChannels.forKind("job.failed"))
        assertEquals("saga-jobs", NotifyChannels.group(NotifyChannels.JOBS_NEEDS))
        // Kanal-IDs stehen nur als Konstante im Code (nie umbenennen)
        val src = File(main, "java/io/github/yggdrasil_sh/saga/push/NotifyChannels.kt").readText()
        assertTrue(src.contains("const val JOBS_NEEDS = \"saga.jobs.needs.v1\"") && src.contains("const val JOBS_FEEDBACK = \"saga.jobs.feedback.v1\""))
    }

    @Test
    fun noMessagingShortcutBubble() {
        val src = File(main, "java/io/github/yggdrasil_sh/saga/push/JobNotify.kt").readText() +
            File(main, "java/io/github/yggdrasil_sh/saga/push/JobActReceiver.kt").readText()
        for (bad in listOf("MessagingStyle", "setShortcutId", "ShortcutInfo", "BubbleMetadata", "setBubble", "RemoteInput", "Person.Builder", "setLocusId")) {
            assertFalse("kein $bad", src.contains(bad))
        }
        assertTrue(src.contains("BigTextStyle") && src.contains("setPublicVersion") && src.contains("VISIBILITY_PRIVATE"))
        assertTrue(src.contains("setAuthenticationRequired(true)") && src.contains("isDeviceLocked") && src.contains("WearableExtender"))
        val mf = File(main, "AndroidManifest.xml").readText()
        assertTrue(Regex("\\.push\\.JobActReceiver\"\\s+android:exported=\"false\"").containsMatchIn(mf))
    }

    @Test
    fun actOutcomes() {
        val tok = v["tokens"]!!.jsonObject
        for ((code, want) in tok["client"]!!.jsonObject) {
            assertEquals(code, want.jsonPrimitive.content, JobNotifyLogic.outcome(if (code == "net") null else code.toInt()).id)
        }
        for (tc in tok["cases"]!!.jsonArray.map { it.jsonObject }) {
            val st = tc["status"]!!.jsonPrimitive.int
            assertEquals(s(tc, "name"), s(tok["client"]!!.jsonObject, st.toString()), JobNotifyLogic.outcome(st).id)
        }
        assertTrue(JobNotifyLogic.retry(null, 0) && !JobNotifyLogic.retry(null, 1) && !JobNotifyLogic.retry(409, 0))
        val req = tok["request"]!!.jsonObject
        assertEquals(req, JobNotifyLogic.actBody("undo", s(req, "atok"), req["pv"]!!.jsonPrimitive.int))
        assertNull(JobNotifyLogic.actBody("open", "x", 1))
        assertNull(JobNotifyLogic.actBody("undo", null, 1))
        assertNull(JobNotifyLogic.actBody("retry", "x", 1))
        // Token aus der Nutzlast (done_undo_webpush) passen zum Beispiel
        val m = PushParse.parse(WebPushCrypto.unb64u(s(cases.first { s(it, "name") == "done_undo_webpush" }, "body")!!), pk)!!
        assertEquals(s(req, "atok"), JobNotifyLogic.tokens(m)["undo"])
        assertEquals(2, m.pv)
        assertEquals(42L, m.job)
    }

    @Test
    fun deeplinksAndTargets() {
        for (d in v["deeplinks"]!!.jsonArray.map { it.jsonObject }) {
            val link = s(d, "link")!!
            val route = s(d, "route")!!
            if (link.startsWith("saga://job")) {
                val id = link.removePrefix("saga://job/").toLongOrNull()
                assertEquals(link, JobNotifyLogic.deeplink(id))
                assertEquals(route, JobNotifyLogic.route(id))
                assertEquals(NotifLogic.Target.Job(id ?: 0L), NotifLogic.target(route))
            }
        }
        assertNull(JobNotifyLogic.jobOf("/saga"))
        assertEquals(0L, JobNotifyLogic.jobOf("/saga?tab=jobs&job=4x"))
        assertNull(NotifLogic.target("/notes?v=jobs"))
    }

    @Test
    fun capsAndPrefs() {
        val sc = v["params"]!!.jsonObject["sub_caps"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("update", "widgets", "detail", "actions"), PushSubLogic.caps(PushServerInfo(payload = 3, subCaps = sc)))
        assertEquals(listOf("update", "widgets"), PushSubLogic.caps(PushServerInfo(payload = 2, subCaps = listOf("update", "widgets"))))
        assertTrue(PushSubLogic.jobCaps(PushSubLogic.caps(PushServerInfo(payload = 3, subCaps = sc))))
        val p = SagaNotifyPrefs.of(Json.parseToJsonElement("""{"notify_content":"never","notify_quiet_until":1795759200}""").jsonObject)!!
        assertFalse(p.content)
        assertTrue(p.quietActive(1795725000.0) && !p.quietActive(1795759200.0))
        assertTrue(SagaNotifyPrefs.of(Json.parseToJsonElement("{}").jsonObject)!!.content)
        assertNull(SagaNotifyPrefs.of(Json.parseToJsonElement("""{"enabled":false}""").jsonObject))
    }

    @Test
    fun textsFiveLanguages() {
        val texts = v["texts"]!!.jsonObject
        val dirs = mapOf("en" to "values", "de" to "values-de", "es" to "values-es", "ru" to "values-ru", "th" to "values-th")
        for ((l, d) in dirs) {
            val xml = File(main, "res/$d/strings_jobpush.xml").readText()
            fun str(n: String) = Regex("<string name=\"$n\">(.*?)</string>").find(xml)?.groupValues?.get(1)?.replace("\\'", "'")
            val t = texts[l]!!.jsonObject
            for (a in JobNotifyLogic.ACTIONS) assertEquals("$l $a", s(t["buttons"]!!.jsonObject, a), str("jp_btn_$a"))
            assertEquals("$l Hinweis", s(t, "hint"), str("jp_hint"))
            assertEquals("$l Kanal", s(t["channels"]!!.jsonObject, "saga.jobs.needs.v1"), str("jp_ch_needs"))
            assertEquals("$l Kanal", s(t["channels"]!!.jsonObject, "saga.jobs.feedback.v1"), str("jp_ch_feedback"))
        }
        assertTrue(texts.keys.containsAll(dirs.keys))
        assertTrue((v["digest"]!!.jsonObject["msg"]!!.jsonObject["digest"] as JsonObject).containsKey("jobs"))
        assertTrue(v["tokens"]!!.jsonObject["order"] is JsonArray)
    }
}
