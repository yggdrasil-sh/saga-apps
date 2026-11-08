package io.github.yggdrasil_sh.saga.ui.nav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Android-Design-Runde 4 (Navigation): reine Regeln fuer untere Leiste, Kopf-Aktionen, Seitenleiste und Kontomenue -
 * ohne Compose, damit sie sich mit Unit-Tests pruefen lassen.
 */
object NavModel {
    /** Ziele der unteren Leiste (Phone, wie Web am Handy) */
    enum class Tab { TODAY, PAGES, SEARCH, CHATS }

    /** Untere Leiste: Heute · Seiten · Suchen · Chats (Chats nur, wenn der Server sie anbietet) */
    fun tabs(chatOn: Boolean): List<Tab> = buildList {
        add(Tab.TODAY); add(Tab.PAGES); add(Tab.SEARCH)
        if (chatOn) add(Tab.CHATS)
    }

    /**
     * Leiste sichtbar? Nur bei kompakter Breite (< 600 dp), nicht im Editor (Seite offen), nicht in einer
     * Chat-Unterhaltung, nicht bei offener Tastatur.
     */
    fun showBottomBar(compact: Boolean, current: Dest, imeVisible: Boolean): Boolean =
        compact && !imeVisible && current !is Dest.Page && current !is Dest.Chat

    /** Markierter Tab: Schublade offen = Seiten; sonst nach der obersten Ebene; andere Ziele = keiner */
    fun selectedTab(root: Dest, drawerOpen: Boolean): Tab? = when {
        drawerOpen -> Tab.PAGES
        root == Dest.Today -> Tab.TODAY
        root == Dest.Search -> Tab.SEARCH
        root == Dest.Chats || root is Dest.Chat -> Tab.CHATS
        root is Dest.Page -> Tab.PAGES
        else -> null
    }

    /** Kopf-Aktionen der Hauptziele: Glocke (Benachrichtigungen) und Lupe (Suchen) */
    data class Head(val bell: Boolean, val search: Boolean)

    private fun isMain(d: Dest): Boolean = when (d) {
        Dest.Today, Dest.Tasks, Dest.Events, Dest.Services, Dest.Search, Dest.Chats, Dest.Trash -> true
        is Dest.Graph -> true
        else -> false
    }

    /**
     * Glocke auf den Hauptzielen der obersten Ebene (nicht im Editor, nicht auf dem Benachrichtigungs-Bildschirm,
     * nicht in Unterseiten mit "Zurueck"). Die Lupe nur ohne untere Leiste (dort gibt es schon "Suchen") und nicht auf
     * der Suche selbst.
     */
    fun head(current: Dest, canGoBack: Boolean, bottomBar: Boolean, notifOn: Boolean): Head {
        if (canGoBack || !isMain(current)) return Head(bell = false, search = false)
        return Head(bell = notifOn, search = !bottomBar && current != Dest.Search)
    }

    /** Hauptpunkte der Seitenleiste: nur, was nicht unten liegt (wie Web: Heute, Chats, Aufgaben, Termine) */
    fun sidebarMain(bottomBar: Boolean, chatOn: Boolean): List<Dest> =
        if (bottomBar) listOf(Dest.Tasks, Dest.Events)
        else buildList {
            add(Dest.Today)
            if (chatOn) add(Dest.Chats)
            add(Dest.Tasks); add(Dest.Events)
        }

    /** Fusszeile der Seitenleiste: Dienste & Links, Graph (falls vorhanden), Papierkorb, Einstellungen */
    fun sidebarFooter(graphOn: Boolean): List<Dest> = buildList {
        add(Dest.Services)
        if (graphOn) add(Dest.Graph())
        add(Dest.Trash); add(Dest.Settings)
    }

    /** R4-Nachbesserung 68: Ziele der Fusszeile, die als Unterziel (push, mit Zurueck-Pfeil) oeffnen */
    fun footerPush(d: Dest): Boolean = d == Dest.Trash

    /** Fusszeile in Reihen zu je zwei Zielen (R4-Nachbesserung 39/79b: genug Breite fuer die Beschriftung) */
    fun footerRows(dests: List<Dest>): List<List<Dest>> = dests.chunked(2)

    /** Gleiches Ziel fuer die Markierung (Graph mit/ohne Fokus zaehlt als Graph) */
    fun same(root: Dest, d: Dest): Boolean = when (d) {
        is Dest.Graph -> root is Dest.Graph
        Dest.Chats -> root == Dest.Chats || root is Dest.Chat
        Dest.Settings -> root == Dest.Settings || root == Dest.SettingsAccount || root == Dest.SettingsGeneral || root == Dest.SettingsInfo ||
            root == Dest.SettingsNotifications || root == Dest.SettingsChats
        else -> root == d
    }

    /** Chats-Badge: Zahl der Gespraeche noch unbekannt (Liste nicht geladen) - nur ein Punkt, keine Zahl */
    const val CHATS_UNKNOWN = -1

    /**
     * R4-Nachbesserung 46b: Badge an "Chats" zaehlt ungelesene Gespraeche, nicht Nachrichten. Der Server liefert nur
     * die Nachrichtensumme (GET /api/chats/unread, `totalMessages`; als ungelesen markierte Chats zaehlen dort je 1);
     * die Gespraeche kommen aus der Chat-Liste (je Chat `unread`/`unread_mark`, dieselben Regeln wie der Server-Zaehler:
     * nicht stumm, nicht archiviert, keine offene Anfrage). 0 = nichts ungelesen (Server-Summe ist massgeblich),
     * [CHATS_UNKNOWN] = es gibt Ungelesenes, die Liste ist aber noch nicht geladen.
     */
    fun unreadChats(chats: List<io.github.yggdrasil_sh.saga.chat.ChatSummary>?, totalMessages: Int): Int {
        if (totalMessages <= 0) return 0
        if (chats == null) return CHATS_UNKNOWN
        val n = chats.count { !it.muted && it.archived != true && !it.request && !it.hidden && (it.unread > 0 || it.unreadMark) }
        // Liste kurz hinter der Summe (Live-Ereignis noch unterwegs): wenigstens 1, nie mehr als Nachrichten
        return n.coerceIn(1, totalMessages)
    }

    /**
     * R4-Nachbesserung 17: ueberfaellige offene Aufgaben (Teil des Seitenleisten-Badges, [TaskCounts] als Quelle) -
     * nur sie werden rot und mit Wort gezeigt.
     */
    fun overdueCount(
        merged: io.github.yggdrasil_sh.saga.data.TasksWithSources?,
        tasks: List<io.github.yggdrasil_sh.saga.data.TaskItem>,
        today: String,
    ): Int = merged?.items?.count { io.github.yggdrasil_sh.saga.data.TaskCounts.overdue(it, today) }
        ?: tasks.count { io.github.yggdrasil_sh.saga.data.TaskCounts.overdue(it, today) }

    /** Zaehler wie in Badges: 0 = keiner, ab 100 "99+" */
    fun badgeText(count: Int): String? = when {
        count <= 0 -> null
        count > 99 -> "99+"
        else -> "$count"
    }

    /**
     * Index von "Mit mir geteilt" in der Seitenleiste (fuer das Scrollen "Zu „Mit mir geteilt“"): Kopf, Suchfeld,
     * Hauptpunkte, Favoriten (Abschnitt + Eintraege), "Seiten" + Baum (bzw. "Noch keine Seiten"), "Neue Seite".
     */
    fun sharedIndex(mainCount: Int, favorites: Int, treeRows: Int): Int {
        var i = 2 + mainCount
        if (favorites > 0) i += 1 + favorites
        i += 1 + (if (treeRows == 0) 1 else treeRows)
        return i + 1
    }

    /** Admin-Bereich aus GET /api/account ("admin_url" nur fuer Admins gesetzt); sonst null */
    fun adminUrl(accountJson: String): String? = try {
        val o = Json.parseToJsonElement(accountJson) as? JsonObject
        (o?.get("admin_url") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    } catch (_: Exception) {
        null
    }
}
