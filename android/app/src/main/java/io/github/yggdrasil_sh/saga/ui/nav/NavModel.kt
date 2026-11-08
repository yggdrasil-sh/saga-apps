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

    /** Gleiches Ziel fuer die Markierung (Graph mit/ohne Fokus zaehlt als Graph) */
    fun same(root: Dest, d: Dest): Boolean = when (d) {
        is Dest.Graph -> root is Dest.Graph
        Dest.Chats -> root == Dest.Chats || root is Dest.Chat
        Dest.Settings -> root == Dest.Settings || root == Dest.SettingsAccount || root == Dest.SettingsGeneral || root == Dest.SettingsInfo ||
            root == Dest.SettingsNotifications || root == Dest.SettingsChats
        else -> root == d
    }

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
