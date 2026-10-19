package io.github.yggdrasil_sh.saga.auth

import java.security.MessageDigest

/**
 * Wann ein Einmal-Link (saga://pair, saga://auth) verarbeitet wird.
 *
 * Android stellt nach einem App-Update oder Prozess-Neustart den Start-Intent der Aufgabe erneut zu - derselbe Link
 * darf dann nicht noch einmal wirken. Deshalb merkt sich die App den zuletzt verarbeiteten Link (als Hash).
 * Ein Kopplungslink, der bei LAUFENDER App ankommt (onNewIntent), hat der Nutzer aber gerade selbst geoeffnet: er
 * zeigt immer die Rueckfrage - auch wenn es derselbe Link wie vorhin ist (z. B. nach "Abbrechen" erneut angetippt).
 * Frueher passierte in diesem Fall angemeldet einfach nichts.
 */
object LinkGate {
    /** Einstellungen ("saga_ui") und Schluessel fuer den zuletzt verarbeiteten Link */
    const val PREFS = "saga_ui"
    const val KEY = "last_link"

    fun hash(link: String): String =
        MessageDigest.getInstance("SHA-256").digest(link.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * host = Ziel des Links ("pair", "auth"), last = Hash des zuletzt verarbeiteten Links,
     * newIntent = kam bei laufender App an (onNewIntent) statt als Start-Intent (onCreate).
     */
    fun shouldHandle(host: String?, hash: String, last: String?, newIntent: Boolean): Boolean = when {
        host == "pair" && newIntent -> true          // fragt ohnehin nach - nie still verschlucken
        else -> hash != last
    }
}
