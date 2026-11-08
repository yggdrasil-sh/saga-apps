package io.github.yggdrasil_sh.saga.ui.settings

/**
 * Unterseiten der Einstellungen (Design-Runde 4, NAV-20): Hauptliste "Einstellungen" mit Allgemein (Sprache,
 * Darstellung) und Kategorien Benachrichtigungen › · Chats › · Sicherung › · Nextcloud › · Konto › · Info ›.
 * Spruenge aus anderen Teilen der App (Mitteilung "Backup fehlgeschlagen" -> ?settings=backup, Chat-Hinweis, Glocke).
 * Reine Regeln, Tests: UpdateHintTest, SettingsLogicTest.
 */
enum class SettingsArea {
    KONTO, BENACHRICHTIGUNGEN, CHATS, INFO;

    /** Unterseite, die zusaetzlich geoeffnet wird (Sicherung, Nextcloud - beide direkt aus der Hauptliste) */
    enum class Sub { BACKUP, NEXTCLOUD }

    companion object {
        private val kontoRows = setOf("konto", "name", "avatar", "security", "public", "federation", "apps", "logout")
        private val notifRows = setOf("notifs", "push", "calls", "system", "digest")
        private val chatRows = setOf("chats", "privacy", "blocked", "unfurl")
        private val infoRows = setOf("info", "about", "update")
        /** Unterseite selbst (oben bleiben) */
        private val pageNames = setOf("konto", "notifs", "chats", "info", "about")

        /** Bereich zu einem Sprungziel; null: nur die Hauptliste (auch "einstellungen", "sprache", "darstellung") */
        fun of(section: String?): SettingsArea? {
            val s = section?.trim()?.lowercase() ?: return null
            return when (s) {
                in kontoRows -> KONTO
                in notifRows -> BENACHRICHTIGUNGEN
                in chatRows -> CHATS
                in infoRows -> INFO
                else -> null
            }
        }

        /** Zeile (Schluessel in der Liste), zu der im Bereich gescrollt wird (null: oben bleiben) */
        fun scrollTarget(section: String?): String? {
            val s = section?.trim()?.lowercase() ?: return null
            if (of(s) == null || s in pageNames) return null
            return s
        }

        /** Eigene Unterseite (Mein Backup, Nextcloud) - wird aus der Hauptliste geoeffnet */
        fun sub(section: String?): Sub? = when (section?.trim()?.lowercase()) {
            "backup" -> Sub.BACKUP
            "nextcloud" -> Sub.NEXTCLOUD
            else -> null
        }
    }
}
