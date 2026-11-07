package io.github.yggdrasil_sh.saga.ui.page

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.blocks.SlashLogic
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Android-Design-Runde 3 (Editor): reine Logik fuer "/"-Menue, Werkzeugleiste und Seitenkopf - ohne Compose,
 * damit Unit-Tests sie pruefen koennen.
 */
object EditorChromeLogic {
    /** Gruppen wie im Web-"/"-Menue (pm-editor.js, pm-tools-medien.js, pm-struktur.js) */
    enum class SlashGroup { BASIC, MEDIA, SAGA, DATABASE }

    fun group(id: String): SlashGroup = when (id) {
        "image", "immich_image", "file", "embed", "nextcloud" -> SlashGroup.MEDIA
        "link", "page" -> SlashGroup.SAGA
        "db_new", "db_link", "tasks", "calendar" -> SlashGroup.DATABASE
        else -> SlashGroup.BASIC
    }

    /** Liniensymbol-Name (SagaIcons) je Eintrag - die Zuordnung in Compose liegt in BlocksExtra.slashIcon */
    fun iconName(item: SlashLogic.Item): String = when (item.id) {
        "paragraph" -> "pilcrow"
        "header" -> if (item.level <= 2) "type" else "typeSmall"
        "checklist" -> "checkbox"
        "unordered" -> "list"
        "ordered" -> "listOrdered"
        "quote" -> "quote"
        "callout" -> "bulb"
        "toggle" -> "chevronRight"
        "code" -> "code"
        "delimiter" -> "minus"
        "image" -> "image"
        "immich_image" -> "camera"
        "table" -> "table"
        "columns" -> "columns"
        "file" -> "paperclip"
        "embed" -> "play"
        "link" -> "link"
        "page" -> "filePlus"
        "db_new" -> "database"
        "db_link" -> "relation"
        "tasks" -> "tasks"
        "calendar" -> "calendar"
        "nextcloud" -> "cloud"
        else -> "plus"
    }

    /**
     * Reihenfolge wie im Web (slashItems): mit Suchbegriff zuerst Treffer am Namensanfang, dann im Namen, dann in
     * den Suchwoertern; danach bleiben die Gruppen zusammen (Reihenfolge des besten Treffers je Gruppe).
     * Der erste Eintrag der Liste ist der, den Return nimmt.
     */
    fun arrange(items: List<SlashLogic.Item>, query: String, label: (SlashLogic.Item) -> String): List<SlashLogic.Item> {
        val q = SlashLogic.fold(query.trim())
        val ranked = if (q.isEmpty()) items else items.withIndex()
            .sortedWith(compareBy({ rank(SlashLogic.fold(label(it.value)), q) }, { it.index })).map { it.value }
        val groups = LinkedHashMap<SlashGroup, MutableList<SlashLogic.Item>>()
        ranked.forEach { groups.getOrPut(group(it.id)) { ArrayList() }.add(it) }
        return groups.values.flatten()
    }

    private fun rank(foldedLabel: String, q: String): Int = when {
        foldedLabel.startsWith(q) -> 0
        foldedLabel.contains(q) -> 1
        else -> 2
    }

    /** Wo das "/"-Menue erscheint: ueber der Zeile, wenn darunter weniger als minBelow frei ist und oben mehr Platz ist */
    data class SlashPlacement(val above: Boolean, val maxHeight: Int)

    /**
     * Alle Werte in Fensterpixeln. viewTop/viewBottom = sichtbarer Inhaltsbereich (zwischen Kopfzeile und
     * Werkzeugleiste) - das Menue reicht nie darueber hinaus und wird so nie von der Werkzeugleiste verdeckt.
     */
    fun slashPlacement(anchorTop: Int, anchorBottom: Int, viewTop: Int, viewBottom: Int, minBelow: Int, cap: Int, gap: Int): SlashPlacement {
        val below = viewBottom - anchorBottom - gap
        val above = anchorTop - viewTop - gap
        val up = below < minBelow && above > below
        return SlashPlacement(up, (if (up) above else below).coerceIn(0, cap))
    }

    /** Senkrechte Lage des Menues (Fensterpixel), immer innerhalb des Inhaltsbereichs */
    fun slashY(p: SlashPlacement, anchorTop: Int, anchorBottom: Int, popupHeight: Int, viewTop: Int, viewBottom: Int, gap: Int): Int {
        val y = if (p.above) anchorTop - gap - popupHeight else anchorBottom + gap
        return y.coerceIn(viewTop, maxOf(viewTop, viewBottom - popupHeight))
    }

    /** Waagerecht: an der Zeile ausrichten, rechts nicht aus dem Fenster */
    fun slashX(anchorLeft: Int, popupWidth: Int, windowWidth: Int): Int = anchorLeft.coerceIn(0, maxOf(0, windowWidth - popupWidth))

    /** Leerer Seiteninhalt (keine Bloecke oder nur ein leerer Absatz): Platzhalter auch ohne Fokus zeigen */
    fun isEmptyBody(blocks: List<JsonElement>): Boolean {
        if (blocks.isEmpty()) return true
        if (blocks.size != 1) return false
        val b = blocks[0] as? JsonObject ?: return false
        return BlockDoc.type(b) == "paragraph" && InlineHtml.plain(BlockDoc.str(BlockDoc.data(b), "text")).isBlank()
    }

    /** Erster Absatz der Seite (oberste Ebene, Index 0) - nur dort steht der Seiten-Platzhalter */
    fun isFirstTopLevel(path: List<Any>): Boolean = path.size == 2 && path[0] == "blocks" && path[1] == 0

    /** Kopf: "Symbol/Titelbild hinzufuegen" und "+ Tag" nur bei Fokus auf den Titel, angetipptem Kopf oder kurzem Hinweis */
    fun headerAddsVisible(titleFocused: Boolean, headTapped: Boolean, hint: Boolean): Boolean = titleFocused || headTapped || hint

    /** Farbmenue: Abschnitt "Markierter Text" nur mit Auswahl, "Ganzer Block" nur fuer gestaltbare Bloecke */
    data class ColorSections(val text: Boolean, val block: Boolean) {
        val any: Boolean get() = text || block
    }

    fun colorSections(hasSelection: Boolean, inlineColor: Boolean, blockStylable: Boolean): ColorSections =
        ColorSections(text = hasSelection && inlineColor, block = blockStylable)
}
