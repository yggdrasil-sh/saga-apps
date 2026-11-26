package io.github.yggdrasil_sh.saga

import android.app.NotificationManager
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.push.JobNotifyLogic
import io.github.yggdrasil_sh.saga.push.NotifyChannels
import io.github.yggdrasil_sh.saga.push.PushLogic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Bugs-DB 2270 (K-C) gegen einen echten Server (Gegenlauf, eigenes Test-AVD, Abo-Art "stream"): Mitteilungen der Kollegin
 * kommen ueber die eigene Verbindung, werden entschluesselt und mit Kanal, Text (t1/t2), Knoepfen nach K15 und neutraler
 * publicVersion gezeigt; der Knopf (echte PendingIntent der Mitteilung) ruft act mit hkd_ - 200 entfernt die Mitteilung,
 * derselbe Token ein zweites Mal (410) ersetzt sie durch den Hinweis "Bitte in Saga ansehen".
 * Ablauf (Mac-Skript, Server-Rig kc_gegen.py): -e step sub  -> Abo anlegen;  Rig "done"/"needs" -> -e step done|needs -e job <id>
 * (Knopf undo bzw. approve, dann zweiter Druck) -> Rig "state <id>". Immer -e server http://127.0.0.1:<port> -e anna <hkd_>.
 * Geraet braucht eine PIN (locksettings set-pin), sonst nie approve/undo (K15 phone_noauth). Bilder: shots/s2270-*.png
 */
@RunWith(AndroidJUnit4::class)
class Saga2270JobPushTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val app get() = ctx.applicationContext as SagaApplication
    private val repo get() = app.repo
    private val args get() = InstrumentationRegistry.getArguments()
    private val dir: File get() = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    private fun login() {
        val server = args.getString("server")
        val token = args.getString("anna")
        assumeTrue("ohne -e server/-e anna", !server.isNullOrBlank() && !token.isNullOrBlank())
        runBlocking {
            repo.awaitReady()
            if (repo.settings.server.value != server || repo.secure.deviceTokenFor(server!!) != token) {
                repo.api.cookies.clear()
                repo.secure.setCredentials(server!!, token!!, null)
                repo.settings.setServer(server)
                repo.settings.setAccount("Konto anna")
                // wie die echte Anmeldung (AuthSession): fester accountKey - sonst migriert der naechste Start ihn
                // (SagaRepository, leerer Schluessel -> legacy) und das gespeicherte Abo (push_local:<server>|<key>) fehlt
                repo.settings.setAccountKey("kca-anna")
            }
            if (repo.settings.accountKey.value.isEmpty()) repo.settings.setAccountKey("kca-anna")
            repo.needsLogin.value = false
            repo.checkVersion()
            repo.push.load()
        }
    }

    private fun shot(name: String) {
        Thread.sleep(900)
        val b: Bitmap = ins.uiAutomation.takeScreenshot() ?: return
        File(dir, "s2270-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(tag: String, ms: Long = 30_000, pred: (StatusBarNotification) -> Boolean = { true }): StatusBarNotification? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            nm.activeNotifications.firstOrNull { it.tag == tag && pred(it) }?.let { return it }
            Thread.sleep(300)
        }
        return null
    }

    private fun gone(tag: String, ms: Long = 20_000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (nm.activeNotifications.none { it.tag == tag }) return true
            Thread.sleep(300)
        }
        return false
    }

    @Test
    fun job() {
        login()
        when (args.getString("step")) {
            "sub" -> subscribe()
            "done" -> press(tagOf(NotifyChannels.JOBS_FEEDBACK), "undo", JobNotifyLogic.CATEGORIES["saga.job.done"]!!, NotifyChannels.JOBS_FEEDBACK)
            "needs" -> press(tagOf(NotifyChannels.JOBS_NEEDS), "approve", JobNotifyLogic.CATEGORIES["saga.job.needs.ok"]!!, NotifyChannels.JOBS_NEEDS)
            "channels" -> channels()
            else -> assumeTrue("ohne -e step", false)
        }
    }

    /** -e job <id> bzw. "auto": die naechste Auftrags-Mitteilung dieses Kanals (der Rig legt den Auftrag an, waehrend der Test wartet) */
    private fun tagOf(channel: String): String {
        val j = args.getString("job")
        if (j != null && j != "auto") return "job-$j"
        val before = nm.activeNotifications.map { it.tag }.toSet()
        println("SAGA2270 WARTE $channel")
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            nm.activeNotifications.firstOrNull { it.tag?.startsWith("job-") == true && it.tag !in before && it.notification.channelId == channel }
                ?.let { println("SAGA2270 TAG ${it.tag}"); return it.tag }
            Thread.sleep(300)
        }
        return "job-?"
    }

    private fun shade() {
        ins.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
        Thread.sleep(2500)
    }

    private fun subscribe() {
        val info = repo.push.info.value
        assertNotNull(info)
        assertEquals("Server-Nutzlast 3", 3, info!!.server.payload)
        assertTrue("sub_caps detail/actions", info.server.subCaps.containsAll(listOf("detail", "actions")))
        assertTrue("Bereich jobs", "jobs" in info.categories)
        if (repo.push.local()?.distributor != PushLogic.STREAM) runBlocking { repo.push.enableStream() }
        val l = repo.push.local()
        assertNotNull("Abo stream", l?.subId)
        assertTrue("caps detail/actions gemeldet", l!!.jcaps)
        // eigene Verbindung muss stehen, bevor der Rig einen Auftrag zustellt
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && repo.push.info.value?.devices?.firstOrNull { it.id == l.subId }?.connected != true) {
            Thread.sleep(500)
            runBlocking { repo.push.load() }
        }
        assertTrue("Verbindung steht", repo.push.info.value?.devices?.firstOrNull { it.id == l.subId }?.connected == true)
        println("SAGA2270 SUB ${l.subId}")
    }

    private fun press(tag: String, act: String, catButtons: List<String>, channel: String) {
        val sbn = waitFor(tag)
        assertNotNull("Mitteilung $tag angekommen", sbn)
        val n = sbn!!.notification
        assertEquals("Kanal", channel, n.channelId)
        val title = n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString().orEmpty()
        assertTrue("t1 statt neutral: $title", title.contains(":") && !title.contains("·"))
        assertTrue("t2 nicht leer", text.isNotBlank())
        assertEquals("BigTextStyle", "android.app.Notification\$BigTextStyle", n.extras.getString(android.app.Notification.EXTRA_TEMPLATE))
        assertTrue("keine MessagingStyle", n.extras.getParcelableArray(NotificationCompat.EXTRA_MESSAGES) == null)
        assertTrue("kein Shortcut", n.shortcutId == null && n.bubbleMetadata == null)
        val pub = n.publicVersion
        assertNotNull("publicVersion", pub)
        val pubTitle = pub!!.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        assertTrue("publicVersion neutral: $pubTitle", pubTitle.contains("·") && !pubTitle.contains(title.substringAfter(": ")))
        assertEquals("Sperrbildschirm privat", android.app.Notification.VISIBILITY_PRIVATE, n.visibility)
        val labels = n.actions.orEmpty().map { it.title.toString() }
        val want = catButtons.map { io.github.yggdrasil_sh.saga.push.JobNotify.label(app, it) }
        assertEquals("Knoepfe nach K15", want, labels)
        val auth = n.actions.orEmpty().map { it.isAuthenticationRequired }
        assertEquals("approve/undo mit Entsperren", catButtons.map { it in JobNotifyLogic.AUTH }, auth)
        // Bild: Mitteilungsleiste mit Knoepfen
        shade()
        shot("${args.getString("step")}-leiste")
        ins.uiAutomation.executeShellCommand("cmd statusbar collapse").close(); Thread.sleep(800)
        // Knopf ueber die echte PendingIntent der Mitteilung
        val pi = n.actions[catButtons.indexOf(act)].actionIntent
        pi.send()
        assertTrue("200 -> Mitteilung weg", gone(tag))
        println("SAGA2270 PRESSED $act")
        // zweiter Druck mit demselben Token -> 410 -> Hinweis "Bitte in Saga ansehen" (ohne Knoepfe)
        pi.send()
        val h = waitFor(tag, 20_000) { it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString() == app.getString(R.string.jp_hint) }
        assertNotNull("410 -> Hinweis", h)
        assertTrue("Hinweis ohne Knoepfe", h!!.notification.actions.isNullOrEmpty())
        shade()
        shot("${args.getString("step")}-hinweis")
        ins.uiAutomation.executeShellCommand("cmd statusbar collapse").close(); Thread.sleep(800)
        nm.cancel(tag, 1)
    }

    private fun channels() {
        io.github.yggdrasil_sh.saga.push.NotifyChannels.ensure(ctx)
        val a = nm.getNotificationChannel(NotifyChannels.JOBS_NEEDS)
        val b = nm.getNotificationChannel(NotifyChannels.JOBS_FEEDBACK)
        assertNotNull(a); assertNotNull(b)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, a!!.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, b!!.importance)
        assertEquals(NotifyChannels.G_NOTIFY, a.group)
        assertEquals(NotifyChannels.G_NOTIFY, b.group)
        assertFalse(a.name.isNullOrBlank())
    }
}
