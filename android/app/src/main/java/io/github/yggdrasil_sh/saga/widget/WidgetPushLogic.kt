package io.github.yggdrasil_sh.saga.widget

/**
 * 2.6 (Bugs-DB 2188): Widgets sofort aktuell. Der Server schickt nach Aenderungen (auch von anderen Geraeten) einen stillen
 * Push ohne Inhalt (kind "widgets.update", Daten-Nachricht ueber UnifiedPush bzw. die eigene Verbindung) - die App laedt
 * dann die Glance-Widgets neu. Welche Seiten hier als Widget liegen, meldet die App (PUT api/app/widgets {pages, today}),
 * nur bei Aenderung. Rein (Tests: WidgetPushLogicTest).
 */
object WidgetPushLogic {
    const val KIND = "widgets.update"
    const val PAGES_MAX = 64

    fun isWidgetPush(kind: String?): Boolean = kind == KIND

    /** Meldung: eigene Seiten-IDs sortiert, ohne doppelte, hoechstens 64; today = Heute-Widget liegt auf dem Homescreen */
    fun pages(configured: Collection<Long>): List<Long> = configured.filter { it > 0 }.distinct().sorted().take(PAGES_MAX)

    /** Was zuletzt gemeldet wurde - je Server und Konto; nur bei Aenderung erneut senden */
    fun fingerprint(account: String, pages: List<Long>, today: Boolean): String =
        account + "|" + pages.joinToString(",") + (if (today) "|t" else "|-")

    /** Antwort des Servers: 404/405 = Server kennt die Meldung nicht (aelter als 2.6) -> eine Weile nicht mehr fragen */
    fun unsupported(code: Int): Boolean = code == 404 || code == 405

    /** Nach "unbekannt" erst nach einem Tag wieder versuchen (Server koennte aktualisiert worden sein) */
    const val RETRY_UNSUPPORTED_MS = 24 * 3600_000L
}
