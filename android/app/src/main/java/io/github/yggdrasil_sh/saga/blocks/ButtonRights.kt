package io.github.yggdrasil_sh.saga.blocks

/**
 * Fix #11: Schaltflaechen eines Schaltflaechen-Blocks bei Rolle Lesen/Kommentieren. "Seite oeffnen" bleibt bedienbar,
 * Anlege-Schaltflaechen ("Neuer Eintrag", "Aufgabe") sind ausgegraut, darunter steht ein Hinweis.
 */
object ButtonRights {
    val CREATE = setOf("db", "task")

    fun usable(action: String, hasTarget: Boolean, canEdit: Boolean): Boolean = when (action) {
        "page" -> hasTarget
        "db" -> hasTarget && canEdit
        "task" -> canEdit
        else -> false
    }

    /** Hinweis zeigen, wenn mindestens eine sonst bedienbare Anlege-Schaltflaeche nur wegen fehlender Rechte gesperrt ist */
    fun readOnlyHint(buttons: List<Pair<String, Boolean>>, canEdit: Boolean): Boolean =
        !canEdit && buttons.any { (action, hasTarget) -> action in CREATE && (action == "task" || hasTarget) }
}
