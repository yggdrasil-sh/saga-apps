package io.github.yggdrasil_sh.saga.widget

import io.github.yggdrasil_sh.saga.data.PageMeta
import java.time.LocalDate
import java.time.ZoneId

/**
 * Neutrale Beispieldaten fuer Bildschirmfotos und Vorschau (nur Debug-Builds im Bildschirmfoto-Modus:
 * `adb shell settings put global saga_screenshot_mode 1`). Keine echten Namen oder Adressen.
 */
object WidgetDemo {
    val pages = listOf(
        PageMeta(id = 9001, title = "Projektplan", icon = "🗺️"),
        PageMeta(id = 9002, title = "Einkaufsliste", icon = "🛒"),
        PageMeta(id = 9003, title = "Leseliste", icon = "📚"),
        PageMeta(id = 9004, title = "Ideen", icon = "💡"),
    )

    fun today(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): TodaySnapshot {
        val d = LocalDate.now(zone)
        fun at(h: Int, m: Int) = d.atTime(h, m).atZone(zone).toOffsetDateTime().toString()
        return TodaySnapshot(
            day = d.toString(),
            fetchedAt = now,
            tasks = listOf(
                WTask(1, "Steuerunterlagen sortieren", d.minusDays(2).toString(), 1),
                WTask(2, "Blumen gießen", d.toString(), 2),
                WTask(3, "Paket zur Post bringen", d.toString(), 1),
                WTask(4, "Rückruf Werkstatt", d.minusDays(1).toString(), 2),
                WTask(5, "Wochenplan schreiben", d.toString(), 3),
            ),
            events = listOf(
                WEvent("Feiertag im Team", d.toString(), d.plusDays(1).toString(), allday = true, color = "#3A6A8C"),
                WEvent("Stand-up", at(9, 30), at(9, 45), color = "#C9A45C"),
                WEvent("Zahnarzt", at(14, 0), at(15, 0), color = "#B5525C", location = "Praxis"),
                WEvent("Sport", at(18, 30), at(19, 30), color = "#4C8C5A"),
            ),
        )
    }

    fun page(id: Long, now: Long = System.currentTimeMillis()): PageSnapshot {
        val p = pages.firstOrNull { it.id == id } ?: pages.first()
        return PageSnapshot(
            id = id,
            title = p.title,
            icon = p.icon,
            excerpt = "Ziel: bis Ende des Monats die erste Fassung fertig haben.\n• Entwurf abstimmen\n• Termine festlegen\n" +
                "• Material sammeln\nOffene Fragen sammeln wir unten und besprechen sie beim nächsten Treffen.",
            fetchedAt = now,
        )
    }

    fun fill(store: WidgetStore) {
        store.signedOut = false
        store.setToday(today())
        store.configuredPages().forEach { store.setPage(page(it)) }
    }
}
