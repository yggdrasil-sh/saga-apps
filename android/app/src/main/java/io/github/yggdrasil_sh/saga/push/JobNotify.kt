package io.github.yggdrasil_sh.saga.push

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.yggdrasil_sh.saga.MainActivity
import io.github.yggdrasil_sh.saga.R
import kotlinx.serialization.json.JsonPrimitive

/**
 * 2.6 (Bugs-DB 2270, K-C, jobpush1): Mitteilungen der Kollegin zu Auftraegen - Push-Nutzlast 3. Vertrag im Server-Repo:
 * docs/APP-API.md "Push-Nutzlast 3 und Auftraege", docs/APP-API-KI.md Abschnitt 12, Vektoren tests/vectors/push_kollegin.json
 * (Kopie: app/src/test/resources/push_kollegin.json, JobNotifyTest). Rein (ohne Android), damit JUnit alles pruefen kann.
 *
 * Anzeige: mit `detail` Titel = t1, Text = t2 (Server, Abo-Sprache), sonst neutral title/body. Kategorie aus `state` und den
 * mitgeschickten Token; Knoepfe nach K15 und nur mit Token (`open` immer). approve/undo nur ab Android 12
 * (setAuthenticationRequired) und nur mit Geraete-Sperre; Uhr (Wear) nur later/quiet; kein Textfeld.
 */
object JobNotifyLogic {
    val KINDS = setOf("job.needs_you", "job.done", "job.failed")
    const val UPDATE_KIND = "job.changed"
    const val cat = "jobs"
    val ACTIONS = listOf("approve", "undo", "later", "quiet", "open")
    val AUTH = setOf("approve", "undo")
    val QUIET = setOf("later", "quiet")

    /** Kategorie -> Knoepfe in Reihenfolge (alle Plattformen gleich) */
    val CATEGORIES = linkedMapOf(
        "saga.job.needs" to listOf("open", "later"),
        "saga.job.needs.ok" to listOf("approve", "open", "later"),
        "saga.job.done" to listOf("undo", "quiet"),
        "saga.job.done.plain" to listOf("quiet"),
        "saga.job.failed" to listOf("open"),
    )

    fun isJob(kind: String?): Boolean = kind in KINDS

    fun group(state: String?, kind: String?): String? = when {
        state == "needs" || state == "done" || state == "failed" -> state
        kind == "job.needs_you" -> "needs"
        kind == "job.done" -> "done"
        kind == "job.failed" -> "failed"
        else -> null
    }

    fun category(group: String, tokens: Set<String>): String = when (group) {
        "needs" -> if ("approve" in tokens) "saga.job.needs.ok" else "saga.job.needs"
        "done" -> if ("undo" in tokens) "saga.job.done" else "saga.job.done.plain"
        else -> "saga.job.failed"
    }

    /** Kanal je Gruppe (fest) */
    fun channel(group: String): String = if (group == "needs") NotifyChannels.JOBS_NEEDS else NotifyChannels.JOBS_FEEDBACK

    data class Button(val a: String, val auth: Boolean)

    /** K15 (Vektoren `k15`): Knoepfe je Kategorie, Geraet (phone, phone_noauth, watch, car, web) und sichtbarem Inhalt */
    fun k15(category: String, device: String, content: Boolean): List<Button> {
        val all = CATEGORIES[category].orEmpty()
        val out = when (device) {
            "phone" -> if (content) all else all.filter { it !in AUTH }
            "watch", "car" -> all.filter { it in QUIET }
            "web" -> all.filter { it in QUIET || it == "open" }.let { if ("open" in it) it else it + "open" }
            else -> all.filter { it !in AUTH }                                   // phone_noauth
        }
        return out.map { Button(it, it in AUTH) }
    }

    fun direct(b: List<Button>, locked: Boolean): List<String> = b.filter { !locked || !it.auth }.map { it.a }

    /** Geraeteklasse: approve/undo nur ab API 31 (Entsperren je Knopf) und mit Geraete-Sperre */
    fun device(sdk: Int, secure: Boolean): String = if (sdk >= 31 && secure) "phone" else "phone_noauth"

    fun tokens(m: PushMessage): Map<String, String> =
        m.actions.filter { it.a in ACTIONS && it.a != "open" && it.t.isNotEmpty() && it.t.length <= 64 }.associate { it.a to it.t }

    data class Plan(
        val group: String,
        val category: String,
        val channel: String,
        val title: String,
        val body: String,
        val tag: String,
        val thread: String,
        val buttons: List<String>,
        val wear: List<String>,
        val job: Long?,
        val url: String,
        val deeplink: String,
        val detail: Boolean,
        val silent: Boolean,
    )

    private fun str(o: kotlinx.serialization.json.JsonObject?, k: String): String =
        ((o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").trim()

    /** Anzeige einer job.*-Mitteilung; null = keine */
    fun plan(m: PushMessage, device: String = "phone"): Plan? {
        if (!isJob(m.kind)) return null
        val g = group(m.state, m.kind) ?: return null
        val t1 = str(m.detail, "t1")
        val t2 = str(m.detail, "t2")
        val content = t1.isNotEmpty()
        val tok = tokens(m).keys
        val cat = category(g, tok)
        val shown = k15(cat, device, content).map { it.a }.filter { it == "open" || it in tok }
        val wear = k15(cat, "watch", content).map { it.a }.filter { it in tok }
        val tag = m.tag.ifEmpty { "saga-job" }
        val job = m.job?.takeIf { it > 0 }
        return Plan(
            g, cat, channel(g), if (content) t1 else m.title, if (content) t2 else m.body, tag, m.thread?.ifEmpty { null } ?: tag,
            shown, wear, job, route(job), deeplink(job), content, m.silent,
        )
    }

    /**
     * Knoepfe fuer Wear OS. Ohne eigene Uhr-Knoepfe (WearableExtender) spiegelt Wear die des Telefons - dann waeren
     * approve/undo auf der Uhr (z. B. "Passt, los" ohne Token "later" nach zweimal "Spaeter"). Darum in diesem Fall nur
     * "Ansehen"; null = Mitteilung gar nicht auf die Uhr (setLocalOnly), falls auch "Ansehen" fehlt.
     */
    fun wearActions(p: Plan): List<String>? = when {
        p.wear.isNotEmpty() || p.buttons.none { it in AUTH } -> p.wear
        "open" in p.buttons -> listOf("open")
        else -> null
    }

    /** job.changed mit update + removed -> tag der Mitteilung, die zu schliessen ist */
    fun removeTag(m: PushMessage): String? = m.tag.takeIf { m.kind == UPDATE_KIND && m.update && m.removed && it.isNotEmpty() }

    fun deeplink(job: Long?): String = job?.let { "saga://job/$it" } ?: "saga://jobs"
    fun route(job: Long?): String = job?.let { "/saga?tab=jobs&job=$it" } ?: "/saga?tab=jobs"

    /** "/saga?tab=jobs&job=42" -> 42, "/saga?tab=jobs" -> 0 (nur der Bereich), sonst null */
    fun jobOf(url: String?): Long? {
        if (url == null) return null
        Regex("^/saga\\?tab=jobs&job=(\\d{1,12})$").find(url)?.let { return it.groupValues[1].toLongOrNull() }
        return if (Regex("^/saga\\?tab=jobs(&job=[^&]*)?$").matches(url)) 0L else null
    }

    /** Reaktion auf POST /api/ai/jobs/{id}/act (Vektoren tokens.client); status null = Netzfehler nach einer Wiederholung */
    enum class Outcome(val id: String) { REMOVE("remove"), HINT("hint_open"), OPEN_APP("open_app") }

    fun outcome(status: Int?): Outcome = when (status) {
        200, 404 -> Outcome.REMOVE
        401, 403 -> Outcome.OPEN_APP
        else -> Outcome.HINT
    }

    fun retry(status: Int?, attempt: Int): Boolean = status == null && attempt == 0

    /** Koerper fuer act ({action, atok, pv}); null fuer open bzw. ohne Token */
    fun actBody(action: String, atok: String?, pv: Int?): kotlinx.serialization.json.JsonObject? {
        if (action == "open" || action !in ACTIONS || atok.isNullOrEmpty()) return null
        return kotlinx.serialization.json.buildJsonObject {
            put("action", JsonPrimitive(action))
            put("atok", JsonPrimitive(atok))
            if (pv != null) put("pv", JsonPrimitive(pv))
        }
    }
}

/** Anzeige der Auftrags-Mitteilungen (BigTextStyle, neutrale publicVersion, Knoepfe an JobActReceiver) */
object JobNotify {
    const val EXTRA_JOB = "saga_job"

    /** Geraet mit Sperre (sonst nie approve/undo) */
    fun deviceClass(context: Context): String {
        val km = context.getSystemService(KeyguardManager::class.java)
        return JobNotifyLogic.device(Build.VERSION.SDK_INT, km?.isDeviceSecure == true)
    }

    /** Beide Auftrags-Kanaele in den System-Einstellungen aus? */
    fun channelsBlocked(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return NotifyChannels.JOBS.all { nm.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
    }

    fun openIntent(context: Context, url: String, tag: String): PendingIntent {
        val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .putExtra(Notifier.EXTRA_URL, url).putExtra(Notifier.EXTRA_SIG, Notifier.sign(context, url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, ("job-open-" + tag).hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun label(context: Context, a: String): String = context.getString(
        when (a) {
            "approve" -> R.string.jp_btn_approve
            "undo" -> R.string.jp_btn_undo
            "later" -> R.string.jp_btn_later
            "quiet" -> R.string.jp_btn_quiet
            else -> R.string.jp_btn_open
        },
    )

    private fun icon(a: String): Int = when (a) {
        "approve" -> R.drawable.si_check
        "undo" -> R.drawable.si_arrow_left
        "later" -> R.drawable.si_clock
        "quiet" -> R.drawable.si_moon
        else -> R.drawable.si_eye
    }

    fun show(context: Context, m: PushMessage, account: String) {
        if (!allowed(context)) return
        val p = JobNotifyLogic.plan(m, deviceClass(context)) ?: return
        NotifyChannels.ensure(context)
        val tokens = JobNotifyLogic.tokens(m)
        val whenMs = if (m.ts > 0) m.ts * 1000 else System.currentTimeMillis()
        val pi = openIntent(context, p.url, p.tag)
        // Sperrbildschirm/ohne Entsperren: nur der neutrale Satz des Servers (title/body), nie Titel des Auftrags
        val pub = NotificationCompat.Builder(context, p.channel).setSmallIcon(R.drawable.ic_notification).setColor(NotifyDesign.ACCENT)
            .setContentTitle(m.title.ifEmpty { context.getString(R.string.app_name) }).setContentText(m.body)
            .setWhen(whenMs).setShowWhen(true).build()
        val b = NotificationCompat.Builder(context, p.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NotifyDesign.ACCENT)
            .setLargeIcon(NotifyDesign.persona(context))
            .setContentTitle(p.title.ifEmpty { context.getString(R.string.app_name) })
            .setContentText(p.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(p.body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setGroup(NotifyChannels.group(p.channel))
            .setCategory(if (p.group == "needs") NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(pub)
            .setWhen(whenMs).setShowWhen(true)
            .setOnlyAlertOnce(false)
        if (p.silent) b.setSilent(true)
        b.addExtras(android.os.Bundle().apply { m.groupKey?.let { putString(Notifier.EXTRA_GROUP, it) }; p.job?.let { putLong(EXTRA_JOB, it) } })
        for (a in p.buttons) b.addAction(action(context, a, p, tokens[a], m.pv, account, pi))
        // Wear OS: nur Ruhe-Knoepfe (approve/undo nie auf der Uhr) - eigene Liste ersetzt die des Telefons
        val wearActs = JobNotifyLogic.wearActions(p)
        if (wearActs == null) b.setLocalOnly(true)
        else {
            val wear = NotificationCompat.WearableExtender()
            for (a in wearActs) wear.addAction(action(context, a, p, tokens[a], m.pv, account, pi))
            b.extend(wear)
        }
        val del = Intent(context, NotifyReadReceiver::class.java).setAction(NotifyReadReceiver.ACTION_DISMISSED)
        b.setDeleteIntent(PendingIntent.getBroadcast(context, 0x5a6a, del, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        try {
            NotificationManagerCompat.from(context).notify(p.tag, Notifier.ID, b.build())
        } catch (_: SecurityException) {
        }
        Notifier.refreshSummaries(context)
    }

    private fun action(context: Context, a: String, p: JobNotifyLogic.Plan, atok: String?, pv: Int?, account: String, open: PendingIntent): NotificationCompat.Action {
        val pi = if (a == "open") open else {
            val i = Intent(context, JobActReceiver::class.java).setAction(JobActReceiver.ACTION)
                .putExtra(JobActReceiver.EXTRA_ACTION, a).putExtra(JobActReceiver.EXTRA_TAG, p.tag)
                .putExtra(JobActReceiver.EXTRA_ACCOUNT, account).putExtra(JobActReceiver.EXTRA_CHANNEL, p.channel)
                .putExtra(JobActReceiver.EXTRA_TOKEN, atok)
                .apply { p.job?.let { putExtra(JobActReceiver.EXTRA_JOB, it) }; pv?.let { putExtra(JobActReceiver.EXTRA_PV, it) } }
            PendingIntent.getBroadcast(context, ("job-$a-" + p.tag).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val ab = NotificationCompat.Action.Builder(icon(a), label(context, a), pi).setShowsUserInterface(a == "open")
        if (a in JobNotifyLogic.AUTH) ab.setAuthenticationRequired(true)       // ab Android 12: erst entsperren
        return ab.build()
    }

    /** Lokaler, neutraler Hinweis "Bitte in Saga ansehen" (ersetzt die Mitteilung, ohne Knoepfe, ohne Ton) */
    fun hint(context: Context, tag: String, channel: String, job: Long?) {
        if (!allowed(context)) return
        val url = JobNotifyLogic.route(job)
        val text = context.getString(R.string.jp_hint)
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification).setColor(NotifyDesign.ACCENT)
            .setContentTitle(context.getString(R.string.app_name)).setContentText(text)
            .setContentIntent(openIntent(context, url, tag)).setAutoCancel(true)
            .setGroup(NotifyChannels.group(channel)).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        try {
            NotificationManagerCompat.from(context).notify(tag, Notifier.ID, b.build())
        } catch (_: SecurityException) {
        }
        Notifier.refreshSummaries(context)
    }
}
