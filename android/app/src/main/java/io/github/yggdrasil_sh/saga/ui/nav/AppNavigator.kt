package io.github.yggdrasil_sh.saga.ui.nav

import androidx.compose.runtime.mutableStateListOf

/** Ziele der App */
sealed interface Dest {
    data object Today : Dest
    data object Tasks : Dest
    data object Events : Dest
    data object Services : Dest
    data object Search : Dest
    data object Settings : Dest
    data class Page(val id: Int) : Dest
}

/**
 * Einfacher Navigationsstapel: Auswahl in der Seitenleiste ersetzt den Stapel (wie die Seitenleiste in iOS),
 * Verweise in Seiten legen eine Seite oben drauf; "Zurueck" nimmt sie wieder herunter.
 */
class AppNavigator {
    val stack = mutableStateListOf<Dest>(Dest.Today)
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

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }
}
