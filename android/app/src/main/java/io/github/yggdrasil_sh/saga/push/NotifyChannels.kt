package io.github.yggdrasil_sh.saga.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.live.NotifLogic

/**
 * Design-Runde 4 (CH-04): getrennte System-Kanaele, damit Chats getrennt von Seiten-Mitteilungen eingestellt werden koennen.
 *
 * Migration: Der bisherige Einzelkanal "saga_push" bleibt mit derselben ID bestehen (Einstellungen der Nutzer bleiben
 * daran haengen) und heisst jetzt "Erwähnungen & Kommentare" (dazu Freigaben und Hinweise). Die neuen Kanaele
 * uebernehmen beim ersten Anlegen Wichtigkeit, Ton, Vibration, Punkt und Sperrbildschirm des alten Kanals - wer
 * Saga-Mitteilungen bisher stumm oder blockiert hatte, bekommt das auch fuer Chats/Aufgaben/Anrufe. Ohne alten Kanal
 * (Neuinstallation): Chats wichtig (Banner), die anderen normal. Klingeln hat weiter eigene Kanaele (call/CallService).
 */
object NotifyChannels {
    /** bisheriger Einzelkanal - bleibt (nicht loeschen: sonst gingen die Einstellungen der Nutzer verloren) */
    const val MENTIONS = "saga_push"
    const val CHATS = "saga_chats"
    const val TASKS = "saga_tasks"
    const val CALLS = "saga_calls_missed"

    val ALL = listOf(CHATS, MENTIONS, TASKS, CALLS)

    /** System-Gruppe je Kanal (Sammel-Mitteilung je Gruppe); "saga-chat" und "saga" wie bisher */
    fun group(channel: String): String = when (channel) {
        CHATS -> "saga-chat"
        TASKS -> "saga-tasks"
        CALLS -> "saga-calls"
        else -> "saga"
    }

    /** Art der Mitteilung (saga/push.py CATS) -> Kanal. Rein (Tests). */
    fun forKind(kind: String): String = when (kind) {
        "chat.message", "chat.mention" -> CHATS
        "call", "call.group", "call.missed" -> CALLS
        "task.due", "db.assigned" -> TASKS
        else -> MENTIONS            // comment.*, page.mention, share.*, publish.withdrawn, backup.failed, chat.report, test ...
    }

    /** Wichtigkeit eines neuen Kanals: die des alten (vom Nutzer eingestellt) oder die Vorgabe. Rein (Tests). */
    fun importance(old: Int?, channel: String): Int = old ?: if (channel == CHATS) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT

    @Volatile private var done = false

    /** Kanaele anlegen bzw. umbenennen (Sprache), einmal je Prozess */
    fun ensure(context: Context) {
        if (done) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val old = nm.getNotificationChannel(MENTIONS)
        for (id in ALL) {
            val (name, desc) = texts(context, id)
            val cur = nm.getNotificationChannel(id)
            if (cur != null) {
                // nur Name/Beschreibung erneuern (Sprache, alter Name "Saga") - Wichtigkeit bleibt die des Nutzers
                cur.name = name
                cur.description = desc
                nm.createNotificationChannel(cur)
                continue
            }
            val ch = NotificationChannel(id, name, importance(old?.importance, id))
            ch.description = desc
            if (old != null) {
                ch.setSound(old.sound, old.audioAttributes)
                ch.enableVibration(old.shouldVibrate())
                old.vibrationPattern?.let { ch.vibrationPattern = it }
                ch.setShowBadge(old.canShowBadge())
                ch.lockscreenVisibility = old.lockscreenVisibility
                ch.enableLights(old.shouldShowLights())
            }
            try {
                nm.createNotificationChannel(ch)
            } catch (_: IllegalArgumentException) {               // z. B. ungueltige Wichtigkeit: mit Vorgabe anlegen
                nm.createNotificationChannel(NotificationChannel(id, name, importance(null, id)).also { it.description = desc })
            }
        }
        done = true
    }

    private fun texts(context: Context, id: String): Pair<String, String> = when (id) {
        CHATS -> context.getString(R.string.r4n_ch_chats) to context.getString(R.string.r4n_ch_chats_desc)
        TASKS -> context.getString(R.string.r4n_ch_tasks) to context.getString(R.string.r4n_ch_tasks_desc)
        CALLS -> context.getString(R.string.r4n_ch_calls) to context.getString(R.string.r4n_ch_calls_desc)
        else -> context.getString(R.string.r4n_ch_mentions) to context.getString(R.string.r4n_ch_mentions_desc)
    }
}

/** Zuordnung einer Push-Mitteilung zur Gespraechseinheit der Glocke (saga/notify.py group_key). Rein (Tests). */
object NotifyRoute {
    /**
     * chat.message -> "chat:<id>" (url /chats#c<id>), call.missed -> "call:<chat>" (tag missed-<chat>),
     * comment.* -> "page:<seite>" (url /notes#<id>[:block]); sonst null (wird nicht gebuendelt). chat.mention buendelt der
     * Server nicht - gelesen wird es ueber die url (LiveCenter.markReadForUrl).
     */
    fun groupKey(kind: String, tag: String, url: String): String? = when (kind) {
        "chat.message" -> (NotifLogic.target(url) as? NotifLogic.Target.Chat)?.let { "chat:${it.id}" }
        "call.missed" -> tag.removePrefix("missed-").takeIf { tag.startsWith("missed-") && it.isNotEmpty() && it.all(Char::isDigit) }?.let { "call:$it" }
        "comment.new", "comment.reply", "comment.mention" -> (NotifLogic.target(url) as? NotifLogic.Target.Page)?.let { "page:${it.id}" }
        else -> null
    }

    /** Chat einer Mitteilung (url /chats#c<id>) */
    fun chatId(url: String): Long? = (NotifLogic.target(url) as? NotifLogic.Target.Chat)?.id

    /** "Als gelesen markieren" nur, wenn es etwas zum Markieren gibt (Gruppe oder Ziel-url) */
    fun canMarkRead(kind: String, url: String): Boolean =
        kind != "test" && kind != "call" && kind != "call.group" && (url.startsWith("/notes#") || url.startsWith("/chats#") || url.startsWith("/?"))
}
