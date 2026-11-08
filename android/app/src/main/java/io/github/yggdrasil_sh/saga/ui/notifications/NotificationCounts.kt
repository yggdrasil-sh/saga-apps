package io.github.yggdrasil_sh.saga.ui.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Design-Runde 4 (NAV-03/08): Zaehler der Glocke = ungelesene ZEILEN (Gespraechseinheiten wie im Web), nicht einzelne
 * Mitteilungen. Gelesen von der Glocke im Kopf und der unteren Leiste (r4-nav). Quelle ist LiveCenter: Laden, Minutentakt,
 * Live-Ereignisse ("notification", auch "gelesen" von anderen Geraeten), Push (laedt die Zahl nach) und eigenes Lesen.
 * Alter Server ohne ?group=1: zaehlt einzelne ungelesene Mitteilungen (Rueckfall).
 */
object NotificationCounts {
    private val groups = MutableStateFlow(0)

    val unreadGroups: StateFlow<Int> = groups.asStateFlow()

    /** Nur LiveCenter schreibt (Abmelden/Kontowechsel: 0) */
    fun publish(n: Int) {
        groups.value = n.coerceAtLeast(0)
    }
}
