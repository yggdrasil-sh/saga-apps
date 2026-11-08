package io.github.yggdrasil_sh.saga.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/** Ziele der App */
sealed interface Dest {
    data object Today : Dest
    data object Tasks : Dest
    data object Events : Dest
    data object Services : Dest
    data object Search : Dest
    data object Settings : Dest
    data class Page(val id: Long) : Dest
    /** Benachrichtigungen (Glocke, Server 1.4) */
    data object Notifications : Dest
    /** Chats (Server 1.5): Liste; Chat(id) = eine Unterhaltung */
    data object Chats : Dest
    data class Chat(val id: Long) : Dest
    /** Einstellungen -> Mitteilungen (Push, 1.7) */
    data object Push : Dest
    /** Einstellungen -> Chats & Geraete */
    data object ChatDevices : Dest
    /** 2.3: Einstellungen -> Mein Backup bzw. Nextcloud */
    data object Backup : Dest
    data object Nextcloud : Dest
    /** 2.5: Einstellungen -> Konto / Einstellungen / Info */
    data object SettingsAccount : Dest
    data object SettingsGeneral : Dest
    data object SettingsInfo : Dest
    /** Design-Runde 4 (NAV-20): eigene Unterseiten Benachrichtigungen und Chats (statt geteiltem SettingsGeneral) */
    data object SettingsNotifications : Dest
    data object SettingsChats : Dest
    /** 2.5: Graph der Seiten; focus = Seite fuer "Lokal" (Seitenmenue "Lokaler Graph"), null = alle Seiten */
    data class Graph(val focus: Long? = null) : Dest
    /** Design-Runde 4 (NAV-17): Papierkorb als eigenes Ziel statt Vollbild-Dialog */
    data object Trash : Dest

    companion object {
        /** Als Text (zum Sichern nach Prozess-Tod) */
        fun encode(d: Dest): String = when (d) {
            Today -> "today"
            Tasks -> "tasks"
            Events -> "events"
            Services -> "services"
            Search -> "search"
            Settings -> "settings"
            is Page -> "page:${d.id}"
            Notifications -> "notifications"
            Chats -> "chats"
            is Chat -> "chat:${d.id}"
            Push -> "push"
            ChatDevices -> "chat-devices"
            Backup -> "backup"
            Nextcloud -> "nextcloud"
            SettingsAccount -> "settings-account"
            SettingsGeneral -> "settings-general"
            SettingsInfo -> "settings-info"
            SettingsNotifications -> "settings-notifications"
            SettingsChats -> "settings-chats"
            is Graph -> if (d.focus != null) "graph:${d.focus}" else "graph"
            Trash -> "trash"
        }

        fun decode(s: String): Dest? = when {
            s == "today" -> Today
            s == "tasks" -> Tasks
            s == "events" -> Events
            s == "services" -> Services
            s == "search" -> Search
            s == "settings" -> Settings
            s.startsWith("page:") -> s.removePrefix("page:").toLongOrNull()?.let { Page(it) }
            s == "notifications" -> Notifications
            s == "chats" -> Chats
            s.startsWith("chat:") -> s.removePrefix("chat:").toLongOrNull()?.let { Chat(it) }
            s == "push" -> Push
            s == "chat-devices" -> ChatDevices
            s == "backup" -> Backup
            s == "nextcloud" -> Nextcloud
            s == "settings-account" -> SettingsAccount
            s == "settings-general" -> SettingsGeneral
            s == "settings-info" -> SettingsInfo
            s == "settings-notifications" -> SettingsNotifications
            s == "settings-chats" -> SettingsChats
            s == "graph" -> Graph()
            s == "trash" -> Trash
            s.startsWith("graph:") -> s.removePrefix("graph:").toLongOrNull()?.let { Graph(it) }
            else -> null
        }
    }
}

/**
 * Einfacher Navigationsstapel: Auswahl in der Seitenleiste ersetzt den Stapel (wie die Seitenleiste in iOS),
 * Verweise in Seiten legen eine Seite oben drauf; "Zurueck" nimmt sie wieder herunter.
 * Ueberlebt Prozess-Tod ueber rememberSaveable(saver = AppNavigator.Saver).
 */
class AppNavigator(initial: List<Dest> = listOf(Dest.Today)) {
    val stack = mutableStateListOf<Dest>().apply { addAll(initial.ifEmpty { listOf(Dest.Today) }) }
    val current: Dest get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    /** Auswahl der obersten Ebene (fuer die Markierung in der Seitenleiste) */
    val root: Dest get() = stack.first()

    fun top(d: Dest) {
        stack.clear()
        stack.add(d)
    }

    fun push(d: Dest) {
        if (stack.last() != d) stack.add(d)
    }

    /**
     * R4-Nachbesserung 22: Ziel ueber der aktuellen Wurzel oeffnen (Stapel = [Wurzel, d]). Mit der unteren Leiste ist die
     * Wurzel das Leistenziel (Heute, Suchen, Chats …); eine aus der Schublade ("Seiten") geoeffnete Seite fuehrt mit
     * Zurueck dorthin statt nach Heute. Ist die Wurzel selbst eine Seite (oder schon d), wird wie bisher ersetzt.
     */
    fun openOver(d: Dest) {
        val r = root
        if (r == d || r is Dest.Page) {
            top(d)
            return
        }
        stack.clear()
        stack.add(r)
        stack.add(d)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    companion object {
        val Saver: Saver<AppNavigator, Any> = listSaver(
            save = { nav -> nav.stack.map(Dest::encode) },
            restore = { list -> AppNavigator(list.mapNotNull(Dest::decode).takeLast(20)) },
        )
    }
}
