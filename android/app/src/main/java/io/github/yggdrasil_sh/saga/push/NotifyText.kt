package io.github.yggdrasil_sh.saga.push

/**
 * Design-Runde 4, Nachbesserung (Mitteilungstexte in der App-Sprache): Der Server schickt in der Push-Nutzlast nur den
 * fertigen Satz in der Sprache des KONTOS (saga/push.py message(): account_lang), keine Parameter (Name, Zahl). Die App
 * kann eine eigene Sprache haben (per-App-Sprache) - dann stand z. B. "Bruno mentioned you in a comment" auf einem
 * deutschen Geraet. Hier erkennt die App die bekannten Saetze des Servers (Vorlagen aus saga/i18n/<de|en|es|ru|th>.json,
 * nur die zur Art passenden), loest Name und Zahl heraus und baut den Satz mit eigenen Texten neu
 * (Notifier.localize). Unbekannte Saetze (andere Server-Version, Freigaben mit Rolle, Hinweise) bleiben unveraendert.
 * Rein (Tests).
 */
object NotifyText {
    /** Satzarten; FROM = mit Name ("Namen zeigen" an), N = mit Zahl */
    enum class Sentence {
        COMMENT_FROM, COMMENTS_N, REPLY_FROM, REPLIES_N, COMMENT_MENTION_FROM, PAGE_MENTION_FROM,
        CHAT_FROM, CHAT_MENTION_FROM, MISSED_FROM, MISSED_N_FROM, MISSED_N, DB_FROM, DB_N_FROM,
        COMMENT, REPLY, MENTION, DB, TASKS, MISSED, CHAT, CHAT_MENTION, SHARE, ROLE, TEST,
    }

    data class Parsed(val sentence: Sentence, val name: String? = null, val n: Int? = null)

    private class Tpl(val sentence: Sentence, val kinds: Set<String>, val texts: List<String>)

    private val COMMENT_KINDS = setOf("comment.new")
    private val MENTION_KINDS = setOf("comment.mention", "page.mention")

    /** Vorlagen des Servers je Sprache (de = Schluessel, en, es, ru, th); {n:a|b|c} = russische Mehrzahlform */
    private val TPLS = listOf(
        Tpl(Sentence.COMMENT_FROM, COMMENT_KINDS, listOf("{name} hat deine Seite kommentiert", "{name} commented on your page",
            "{name} ha comentado tu página", "{name} прокомментировал(а) твою страницу", "{name} แสดงความคิดเห็นในหน้าของคุณ")),
        Tpl(Sentence.COMMENTS_N, COMMENT_KINDS, listOf("{n} neue Kommentare auf deiner Seite", "{n} new comments on your page",
            "{n} comentarios nuevos en tu página", "{n} {n:новый комментарий|новых комментария|новых комментариев} на твоей странице",
            "{n} ความคิดเห็นใหม่ในหน้าของคุณ")),
        Tpl(Sentence.REPLY_FROM, setOf("comment.reply"), listOf("{name} hat in einer Unterhaltung geantwortet", "{name} replied in a thread",
            "{name} ha respondido en una conversación", "{name} ответил(а) в обсуждении", "{name} ตอบกลับในการสนทนา")),
        Tpl(Sentence.REPLIES_N, setOf("comment.reply"), listOf("{n} neue Antworten in einer Unterhaltung", "{n} new replies in a thread",
            "{n} respuestas nuevas en una conversación", "{n} {n:новый ответ|новых ответа|новых ответов} в обсуждении",
            "{n} การตอบกลับใหม่ในการสนทนา")),
        Tpl(Sentence.COMMENT_MENTION_FROM, setOf("comment.mention"), listOf("{name} hat dich in einem Kommentar erwähnt",
            "{name} mentioned you in a comment", "{name} te mencionó en un comentario", "{name} упомянул(а) вас в комментарии",
            "{name} กล่าวถึงคุณในความคิดเห็น")),
        Tpl(Sentence.PAGE_MENTION_FROM, setOf("page.mention"), listOf("{name} hat dich erwähnt", "{name} mentioned you",
            "{name} te ha mencionado", "{name} упомянул(а) тебя", "{name} กล่าวถึงคุณ")),
        Tpl(Sentence.CHAT_FROM, setOf("chat.message"), listOf("Neue Nachricht von {name}", "New message from {name}",
            "Mensaje nuevo de {name}", "Новое сообщение от {name}", "ข้อความใหม่จาก {name}")),
        Tpl(Sentence.CHAT_MENTION_FROM, setOf("chat.mention"), listOf("{name} hat dich in einem Chat erwähnt", "{name} mentioned you in a chat",
            "{name} te ha mencionado en un chat", "{name} упомянул(а) вас в чате", "{name} กล่าวถึงคุณในแชท")),
        Tpl(Sentence.MISSED_N_FROM, setOf("call.missed"), listOf("{n} verpasste Anrufe von {name}", "{n} missed calls from {name}",
            "{n} llamadas perdidas de {name}", "{n} {n:пропущенный звонок|пропущенных звонка|пропущенных звонков} от {name}",
            "สายที่ไม่ได้รับจาก {name} {n} สาย")),
        Tpl(Sentence.MISSED_N, setOf("call.missed"), listOf("{n} verpasste Anrufe", "{n} missed calls", "{n} llamadas perdidas",
            "{n} {n:пропущенный звонок|пропущенных звонка|пропущенных звонков}", "สายที่ไม่ได้รับ {n} สาย")),
        Tpl(Sentence.MISSED_FROM, setOf("call.missed"), listOf("Verpasster Anruf von {name}", "Missed call from {name}",
            "Llamada perdida de {name}", "Пропущенный звонок от {name}", "สายที่ไม่ได้รับจาก {name}")),
        Tpl(Sentence.DB_N_FROM, setOf("db.assigned"), listOf("{name} hat dich {n}-mal eingetragen", "{name} assigned you {n} times",
            "{name} te ha asignado {n} veces", "{name} назначил(а) вас {n} раз(а)", "{name} ได้มอบหมายคุณ {n} ครั้ง")),
        Tpl(Sentence.DB_FROM, setOf("db.assigned"), listOf("{name} hat dich eingetragen", "{name} assigned you", "{name} te ha asignado",
            "{name} назначил(а) вас", "{name} ได้มอบหมายคุณ")),
        // ohne Namen ("Namen zeigen" aus bzw. Weg ohne Ende-zu-Ende-Verschluesselung)
        Tpl(Sentence.COMMENT, COMMENT_KINDS, listOf("Neuer Kommentar auf deiner Seite", "New comment on your page",
            "Comentario nuevo en tu página", "Новый комментарий на твоей странице", "ความคิดเห็นใหม่ในหน้าของคุณ")),
        Tpl(Sentence.REPLY, setOf("comment.reply"), listOf("Neue Antwort in einer Unterhaltung", "New reply in a thread",
            "Respuesta nueva en una conversación", "Новый ответ в обсуждении", "การตอบกลับใหม่ในการสนทนา")),
        Tpl(Sentence.MENTION, MENTION_KINDS, listOf("Du wurdest erwähnt", "You were mentioned", "Te han mencionado", "Тебя упомянули",
            "มีคนกล่าวถึงคุณ")),
        Tpl(Sentence.DB, setOf("db.assigned"), listOf("Du wurdest in einer Datenbank eingetragen", "You were assigned in a database",
            "Te han asignado en una base de datos", "Вас назначили в базе данных", "คุณได้รับมอบหมายในฐานข้อมูล")),
        Tpl(Sentence.TASKS, setOf("task.due"), listOf("Aufgaben heute fällig oder überfällig", "Tasks due today or overdue",
            "Tareas para hoy o vencidas", "Задачи на сегодня или просроченные", "งานที่ครบกำหนดวันนี้หรือเลยกำหนด")),
        Tpl(Sentence.MISSED, setOf("call.missed"), listOf("Verpasster Anruf", "Missed call", "Llamada perdida", "Пропущенный звонок",
            "สายที่ไม่ได้รับ")),
        Tpl(Sentence.CHAT, setOf("chat.message"), listOf("Neue Nachricht", "New message", "Mensaje nuevo", "Новое сообщение", "ข้อความใหม่")),
        Tpl(Sentence.CHAT_MENTION, setOf("chat.mention"), listOf("Du wurdest in einem Chat erwähnt", "You were mentioned in a chat",
            "Te han mencionado en un chat", "Вас упомянули в чате", "คุณถูกกล่าวถึงในแชท")),
        Tpl(Sentence.SHARE, setOf("share.added"), listOf("Jemand hat eine Seite mit dir geteilt", "Someone shared a page with you",
            "Alguien ha compartido una página contigo", "С тобой поделились страницей", "มีคนแชร์หน้ากับคุณ")),
        Tpl(Sentence.ROLE, setOf("share.role"), listOf("Deine Rolle auf einer geteilten Seite hat sich geändert",
            "Your role on a shared page has changed", "Tu rol en una página compartida ha cambiado", "Твоя роль на общей странице изменилась",
            "บทบาทในหน้าที่แชร์เปลี่ยนไปแล้ว")),
        Tpl(Sentence.TEST, setOf("test"), listOf("Testmitteilung – Mitteilungen kommen an.", "Test notification – push notifications are working.",
            "Notificación de prueba: las notificaciones push funcionan.", "Тестовое уведомление – push-уведомления приходят.",
            "การแจ้งเตือนทดสอบ – การแจ้งเตือนใช้งานได้")),
    )

    private class Rx(val tpl: Tpl, val re: Regex, val slots: List<String>)

    private val TOKEN = Regex("""\{(name|n)(?::([^}]*))?\}""")

    /** Vorlage -> regulaerer Ausdruck; slots = Reihenfolge der Gruppen (name / n) */
    private fun compile(t: String): Pair<Regex, List<String>> {
        val sb = StringBuilder("^")
        val slots = ArrayList<String>()
        var at = 0
        for (m in TOKEN.findAll(t)) {
            if (m.range.first > at) sb.append(Regex.escape(t.substring(at, m.range.first)))
            val alt = m.groupValues[2]
            when {
                alt.isNotEmpty() -> sb.append("(?:").append(alt.split('|').joinToString("|") { Regex.escape(it) }).append(")")
                m.groupValues[1] == "n" -> { sb.append("(\\d+)"); slots += "n" }
                else -> { sb.append("(.+)"); slots += "name" }
            }
            at = m.range.last + 1
        }
        if (at < t.length) sb.append(Regex.escape(t.substring(at)))
        sb.append("$")
        return Regex(sb.toString()) to slots
    }

    private val COMPILED: List<Rx> by lazy {
        TPLS.flatMap { tpl -> tpl.texts.map { s -> compile(s).let { (re, slots) -> Rx(tpl, re, slots) } } }
    }

    /** Satz des Servers zu einer Art erkennen; null = unbekannt (Satz bleibt, wie er ist) */
    fun parse(kind: String, body: String): Parsed? {
        val b = body.trim()
        if (b.isEmpty()) return null
        for (rx in COMPILED) {
            if (kind !in rx.tpl.kinds) continue
            val m = rx.re.matchEntire(b) ?: continue
            var name: String? = null
            var n: Int? = null
            rx.slots.forEachIndexed { i, s ->
                val v = m.groupValues[i + 1]
                if (s == "n") n = v.toIntOrNull() else name = v.trim().ifEmpty { null }
            }
            if (rx.slots.contains("name") && name == null) continue
            return Parsed(rx.tpl.sentence, name, n)
        }
        return null
    }

    /** Hat der Server einen Namen genannt? Dann ist "Namen zeigen" fuer diesen Weg an (saga/push.py with_names). */
    fun named(p: Parsed?): Boolean = p?.name != null

    /**
     * 2.5 Push-Nutzlast 2 (v >= 2, docs/APP-API.md "Push-Nutzlast 2"): Satz aus den Feldern statt aus dem fertigen Satz.
     * actor steht nur in der Nutzlast, wenn der Server den Namen ohnehin zeigt ("Namen zeigen" an, verschluesselter Weg) -
     * ohne actor also nie ein Name. n = Anzahl (Chat: ungelesene des Chats, Kommentare/Eintragungen: Buendel, verpasste
     * Anrufe: ungelesene des Chats). null = Art/Satz unbekannt (z. B. Freigabe mit Rolle) -> Satz des Servers bleibt.
     * Rein (Tests).
     */
    fun fromV2(kind: String, actor: String?, n: Int?): Parsed? {
        val a = actor?.trim()?.ifEmpty { null }
        val c = n?.takeIf { it > 1 }
        return when (kind) {
            "chat.message" -> if (a != null) Parsed(Sentence.CHAT_FROM, a) else Parsed(Sentence.CHAT, n = n)
            "chat.mention" -> if (a != null) Parsed(Sentence.CHAT_MENTION_FROM, a) else Parsed(Sentence.CHAT_MENTION)
            "call.missed" -> when {
                a != null && c != null -> Parsed(Sentence.MISSED_N_FROM, a, c)
                a != null -> Parsed(Sentence.MISSED_FROM, a)
                c != null -> Parsed(Sentence.MISSED_N, null, c)
                else -> Parsed(Sentence.MISSED)
            }
            // Buendel: der Server nennt bei mehreren Kommentaren/Antworten keinen Namen (Satz der Glocke)
            "comment.new" -> when {
                c != null -> Parsed(Sentence.COMMENTS_N, null, c)
                a != null -> Parsed(Sentence.COMMENT_FROM, a)
                else -> Parsed(Sentence.COMMENT)
            }
            "comment.reply" -> when {
                c != null -> Parsed(Sentence.REPLIES_N, null, c)
                a != null -> Parsed(Sentence.REPLY_FROM, a)
                else -> Parsed(Sentence.REPLY)
            }
            "comment.mention" -> if (a != null) Parsed(Sentence.COMMENT_MENTION_FROM, a) else Parsed(Sentence.MENTION)
            "page.mention" -> if (a != null) Parsed(Sentence.PAGE_MENTION_FROM, a) else Parsed(Sentence.MENTION)
            "db.assigned" -> when {
                a != null && c != null -> Parsed(Sentence.DB_N_FROM, a, c)
                a != null -> Parsed(Sentence.DB_FROM, a)
                else -> Parsed(Sentence.DB)
            }
            "task.due" -> Parsed(Sentence.TASKS)
            // mit Namen nennt der Server die Rolle mit - die steht nicht in der Nutzlast: Satz des Servers bleibt
            "share.added" -> if (a != null) null else Parsed(Sentence.SHARE)
            "share.role" -> if (a != null) null else Parsed(Sentence.ROLE)
            "test" -> Parsed(Sentence.TEST)
            else -> null
        }
    }

    /**
     * Satz fuer diese Mitteilung: Nutzlast 2 aus den Feldern, aeltere Server per Satzerkennung ([parse], Rueckfall).
     */
    fun of(v: Int, kind: String, body: String, actor: String?, n: Int?): Parsed? =
        if (v >= 2) fromV2(kind, actor, n) else parse(kind, body)
}
