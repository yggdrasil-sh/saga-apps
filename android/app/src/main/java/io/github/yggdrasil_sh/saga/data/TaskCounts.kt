package io.github.yggdrasil_sh.saga.data

/*
 * Design-Runde Android 1 (NAV-01, NAV-12): eine Quelle fuer Aufgabenzahlen und Gruppen. Kopf, Abschnittszahl und Liste
 * auf "Heute" zaehlen dieselbe Liste (mit Aufgabenquellen die zusammengefuehrte, sonst die eigenen); das Badge in der
 * Seitenleiste zaehlt wie das Web-Badge nur "faellig heute + ueberfaellig". Datumsangaben als ISO "JJJJ-MM-TT".
 */
object TaskCounts {
    /** Gruppen der Aufgabenliste in Anzeigereihenfolge */
    enum class Group { OVERDUE, TODAY, TOMORROW, LATER, NONE }

    /** Auf "Heute" gezeigt: faellig heute, ueberfaellig oder ohne Datum */
    fun onToday(due: String?, today: String): Boolean = due.isNullOrEmpty() || due <= today

    /** Fuer das Badge: faellig heute oder ueberfaellig (ohne Datum zaehlt nicht) */
    fun dueOrOverdue(due: String?, today: String): Boolean = !due.isNullOrEmpty() && due <= today

    /** Ueberfaellig: Server-Kennzeichen (beruecksichtigt Uhrzeit) oder Ende (bzw. Faelligkeit) vor heute */
    fun overdue(e: TaskEntry, today: String): Boolean {
        if (e.done) return false
        if (e.overdue) return true
        val end = e.dueEnd?.takeIf { it.isNotEmpty() } ?: e.due
        return !end.isNullOrEmpty() && end < today
    }

    fun overdue(t: TaskItem, today: String): Boolean = !t.done && !t.due.isNullOrEmpty() && t.due < today

    /** Gruppe eines Eintrags der zusammengefuehrten Liste (Zeitraum, der heute einschliesst = heute) */
    fun group(e: TaskEntry, today: String, tomorrow: String): Group {
        val d = e.due?.takeIf { it.isNotEmpty() } ?: return Group.NONE
        return when {
            overdue(e, today) -> Group.OVERDUE
            d <= today -> Group.TODAY
            d == tomorrow -> Group.TOMORROW
            else -> Group.LATER
        }
    }

    fun group(t: TaskItem, today: String, tomorrow: String): Group {
        val d = t.due?.takeIf { it.isNotEmpty() } ?: return Group.NONE
        return when {
            d < today -> Group.OVERDUE
            d == today -> Group.TODAY
            d == tomorrow -> Group.TOMORROW
            else -> Group.LATER
        }
    }

    /** Gruppiert (Reihenfolge innerhalb einer Gruppe bleibt die des Servers), leere Gruppen fallen weg */
    fun <T> grouped(items: List<T>, groupOf: (T) -> Group): List<Pair<Group, List<T>>> {
        val by = items.groupBy(groupOf)
        return Group.entries.mapNotNull { g -> by[g]?.takeIf { it.isNotEmpty() }?.let { g to it } }
    }

    /** Liste auf "Heute" (zusammengefuehrt); null = ohne Aufgabenquellen, dann [todayTasks] */
    fun todayEntries(merged: TasksWithSources?, today: String): List<TaskEntry>? =
        merged?.items?.filter { !it.done && onToday(it.due, today) }

    fun todayTasks(tasks: List<TaskItem>, today: String): List<TaskItem> = tasks.filter { !it.done && onToday(it.due, today) }

    /** Zahl fuer Kopf und Abschnitt auf "Heute" - gleich der Laenge der gezeigten Liste */
    fun todayCount(merged: TasksWithSources?, tasks: List<TaskItem>, today: String): Int =
        todayEntries(merged, today)?.size ?: todayTasks(tasks, today).size

    /** Badge in der Seitenleiste: offen und faellig heute oder ueberfaellig */
    fun badge(merged: TasksWithSources?, tasks: List<TaskItem>, today: String): Int =
        merged?.items?.count { !it.done && (dueOrOverdue(it.due, today) || overdue(it, today)) }
            ?: tasks.count { !it.done && dueOrOverdue(it.due, today) }
}
