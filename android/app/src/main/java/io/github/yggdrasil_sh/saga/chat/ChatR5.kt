package io.github.yggdrasil_sh.saga.chat

/**
 * Android-Design-Runde 5 (Chats & Anrufe, Chat-Oberflaeche): reine Regeln fuer "Neuer Chat" (CH-06) - ohne Android,
 * mit Unit-Tests (DesignR5ChatTest).
 */
object NewChatLogic {
    /** Person (Direkt-Chat), mehrere Personen (neue Gruppe) bzw. Gruppe aus einem eigenen Team */
    enum class Mode { PERSON, GROUP, TEAM }

    /** Was statt Treffern unter dem Suchfeld steht */
    enum class Empty { NONE, TOO_SHORT, NO_HITS }

    /**
     * Leerzustand: ohne Eingabe nichts (Kontakte und feste Eintraege stehen da), zu kurz fuer den Server (aeltere Server
     * erst ab 3 Zeichen) -> Regel als Leerzustand, nach der Antwort ohne Treffer und ohne name@server-Hinweis -> "Keine Treffer".
     */
    fun empty(q: String, min: Int, loadedFor: String?, hits: Int, fedHint: Boolean): Empty {
        val t = q.trim()
        return when {
            t.isEmpty() -> Empty.NONE
            t.length < min -> Empty.TOO_SHORT
            loadedFor != q || fedHint -> Empty.NONE
            hits == 0 -> Empty.NO_HITS
            else -> Empty.NONE
        }
    }

    /** Feste Eintraege oben ("Neue Gruppe", ggf. "Gruppe aus deinen Teams") nur im Personen-Modus ohne Eingabe */
    fun showFixed(mode: Mode, q: String): Boolean = mode == Mode.PERSON && q.isBlank()

    /**
     * Teams fuer "Gruppe aus deinen Teams": ohne Eingabe die Teams, aus denen es schon Gruppen-Chats gibt (lokal bekannt),
     * mit Eingabe dazu die Treffer des Servers (eigene Gruppen) - ohne Doppelte, nach Namen sortiert, gefiltert nach q.
     */
    fun teams(chats: List<ChatSummary>, found: List<PeopleGroup>, q: String): List<String> {
        val needle = q.trim().lowercase()
        val local = chats.filter { it.kind == "group" }.mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }
        return (local + found.map { it.name }.filter { it.isNotBlank() })
            .distinct()
            .filter { needle.isEmpty() || it.lowercase().contains(needle) }
            .sortedBy { it.lowercase() }
    }

    /** "Erstellen" in "Neue Gruppe": mindestens eine Person (dieses oder eines anderen Servers) */
    fun canCreate(mode: Mode, picked: Int, pickedRemote: Int): Boolean = mode == Mode.GROUP && picked + pickedRemote > 0
}
