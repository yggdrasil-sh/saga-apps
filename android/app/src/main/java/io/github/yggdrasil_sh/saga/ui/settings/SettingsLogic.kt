package io.github.yggdrasil_sh.saga.ui.settings

/**
 * Design-Runde 4 (NAV-20): Einstellungen als Kategorien-Hauptliste statt einer langen flachen Liste. Reine Regeln
 * (Tests: SettingsLogicTest) - welche Kategorien die Hauptliste zeigt und wie die Benachrichtigungsarten des Servers
 * auf der Unterseite "Benachrichtigungen" gruppiert werden.
 */
object SettingsLogic {
    /** Kategorien der Hauptliste "Einstellungen" (Reihenfolge = Anzeige) */
    enum class Category { NOTIFICATIONS, CHATS, BACKUP, NEXTCLOUD, BOOKORBIT, ACCOUNT, INFO }

    /**
     * Sichtbare Kategorien: Benachrichtigungen, Konto und Info immer; Chats nur, wenn der Server Chats kann
     * (eingerichtet oder einrichtbar); Sicherung ("Mein Backup"), Nextcloud und BookOrbit (2.5, bookorbit1) nur, wenn
     * der Server sie anbietet.
     */
    fun categories(chats: Boolean, backup: Boolean, nextcloud: Boolean, bookorbit: Boolean = false): List<Category> = buildList {
        add(Category.NOTIFICATIONS)
        if (chats) add(Category.CHATS)
        if (backup) add(Category.BACKUP)
        if (nextcloud) add(Category.NEXTCLOUD)
        if (bookorbit) add(Category.BOOKORBIT)
        add(Category.ACCOUNT)
        add(Category.INFO)
    }

    /** Gruppen der Benachrichtigungsarten (Server notify.py KINDS) auf der Unterseite "Benachrichtigungen" */
    enum class NotifGroup { PAGES, MENTIONS, CHATS, OTHER }

    /** Art -> Gruppe; unbekannte (neuere Server) landen unter "Weitere", statt zu verschwinden */
    fun groupOf(kind: String): NotifGroup = when (kind) {
        "share.added", "share.role", "comment.new", "comment.reply", "publish.withdrawn", "db.assigned" -> NotifGroup.PAGES
        "page.mention", "comment.mention", "chat.mention" -> NotifGroup.MENTIONS
        "chat.message", "call", "call.missed" -> NotifGroup.CHATS
        else -> NotifGroup.OTHER
    }

    /** Arten gruppiert in fester Gruppenreihenfolge; leere Gruppen fallen weg, Reihenfolge innerhalb bleibt die des Servers */
    fun <T> grouped(items: List<T>, kind: (T) -> String): List<Pair<NotifGroup, List<T>>> {
        val by = items.groupBy { groupOf(kind(it)) }
        return NotifGroup.entries.mapNotNull { g -> by[g]?.takeIf { it.isNotEmpty() }?.let { g to it } }
    }
}
