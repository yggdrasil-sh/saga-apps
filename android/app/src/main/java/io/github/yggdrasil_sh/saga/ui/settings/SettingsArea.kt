package io.github.yggdrasil_sh.saga.ui.settings

/**
 * Einstellungen in drei Bereichen (2.5, wie ios/App/SettingsLogic.swift): Konto · Einstellungen · Info - Spruenge aus
 * anderen Teilen der App (Mitteilung "Backup fehlgeschlagen" -> ?settings=backup, Chat-Hinweis, Glocke). Reine Regeln,
 * Tests: UpdateHintTest.
 */
enum class SettingsArea {
    KONTO, EINSTELLUNGEN, INFO;

    /** Unterseite, die nach dem Bereich geoeffnet wird */
    enum class Sub { BACKUP, NEXTCLOUD }

    companion object {
        private val kontoRows = setOf("konto", "name", "avatar", "security", "public", "federation", "backup", "apps")
        private val settingsRows = setOf("einstellungen", "sprache", "notifs", "push", "calls", "chats", "privacy", "nextcloud")
        private val infoRows = setOf("info", "about", "update")

        /** Bereich zu einem Sprungziel; null: nur die Startliste */
        fun of(section: String?): SettingsArea? {
            val s = section?.trim()?.lowercase() ?: return null
            return when (s) {
                in kontoRows -> KONTO
                in settingsRows -> EINSTELLUNGEN
                in infoRows -> INFO
                else -> null
            }
        }

        /** Zeile (Schluessel in der Liste), zu der im Bereich gescrollt wird (null: oben bleiben) */
        fun scrollTarget(section: String?): String? {
            val s = section?.trim()?.lowercase() ?: return null
            if (of(s) == null || s in setOf("konto", "einstellungen", "info", "about")) return null
            if (sub(s) != null) return null
            return s
        }

        /** Eigene Unterseite (Mein Backup, Nextcloud) - wird zusaetzlich geoeffnet */
        fun sub(section: String?): Sub? = when (section?.trim()?.lowercase()) {
            "backup" -> Sub.BACKUP
            "nextcloud" -> Sub.NEXTCLOUD
            else -> null
        }
    }
}
