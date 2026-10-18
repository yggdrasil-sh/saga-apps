package io.github.yggdrasil_sh.saga.data

import androidx.annotation.StringRes
import io.github.yggdrasil_sh.saga.R

/**
 * Standard-Kategorien der Dienste-Kacheln. Der Server liefert sie in der Sprache des Kontos ("Lesezeichen",
 * "Bookmarks", "Marcadores" ...); die App zeigt sie in ihrer eigenen Sprache (Nachtest K3). Erkannt werden genau
 * die Fassungen, die der Server selbst erzeugt (saga/core.py std_name, i18n de/en/es/ru/th) - selbst vergebene
 * Kategorien bleiben unveraendert. ru/th: Fassungen wie in values-ru/values-th; mit saga/i18n ru.json/th.json abgleichen.
 */
enum class StdCategory(@StringRes val label: Int, vararg names: String) {
    SERVICES(R.string.category_services, "Dienste", "Services", "Servicios", "Сервисы", "บริการ"),
    BOOKMARKS(R.string.category_bookmarks, "Lesezeichen", "Bookmarks", "Marcadores", "Закладки", "บุ๊กมาร์ก");

    private val known = names.toSet()

    companion object {
        /** Standard-Kategorie zu diesem Namen vom Server; null = selbst vergeben (unveraendert anzeigen) */
        fun of(name: String): StdCategory? {
            val n = name.trim()
            return entries.firstOrNull { n in it.known }
        }

        /** Anzeigename: Standard-Kategorien ueber label(), alles andere wie geliefert */
        fun display(name: String, label: (Int) -> String): String = of(name)?.let { label(it.label) } ?: name
    }
}
