package io.github.yggdrasil_sh.saga.blocks

/**
 * Fix #14 (Spalten-Leisten): Die Bearbeiten-Bedienung eines Spalten-Blocks ("Spalten 2 3 4", "Spalte n", "+ Block")
 * erscheint nur, wenn man darin gerade schreibt (Fokus in einem Feld innerhalb des Blocks) - sonst liest sich die Seite
 * ruhig wie im Web. Eine leere Spalte behaelt "+ Block", damit man sie ueberhaupt fuellen kann. Ohne Bearbeiten-Recht nie.
 */
object ColumnsChrome {
    /** liegt das aktive Feld (Pfad) innerhalb des Blocks mit diesem Pfad? */
    fun inside(active: JsonPath?, block: JsonPath): Boolean =
        active != null && active.size > block.size && active.subList(0, block.size) == block

    /** Leiste "Spalten 2 3 4" */
    fun showBar(editable: Boolean, active: JsonPath?, block: JsonPath): Boolean = editable && inside(active, block)

    /** "Spalte n" und "+ Block" einer Spalte */
    fun showColumnControls(editable: Boolean, active: JsonPath?, block: JsonPath, columnEmpty: Boolean): Boolean =
        editable && (columnEmpty || inside(active, block))
}
