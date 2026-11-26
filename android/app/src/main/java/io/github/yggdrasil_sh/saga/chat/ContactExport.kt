package io.github.yggdrasil_sh.saga.chat

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone

// Saga 2.6 (Bugs-DB 2027b, W2): "Zu Kontakten hinzufuegen" - ein Kontakt des Saga-Adressbuchs einzeln ins Adressbuch
// des Geraets. Die App oeffnet den System-Dialog vorausgefuellt (ui/settings/ContactExportUi.kt: ACTION_INSERT_OR_EDIT
// mit Insert.NAME und Insert.DATA); die Person waehlt dort neu oder vorhanden und sichert selbst. Keine READ/WRITE_CONTACTS,
// kein Abgleich. Uebertragen wird nur, was die Person im Detail selbst sieht und angelegt hat: Name (Name fuer mich bzw.
// Name der Person), Telefonnummern (E.164) und Mails mit Typ. Nie: Saga-Adresse (interner Handle "~…" bzw. "~x@host"),
// Anrede/Grussform, Chatinhalte. Reine Regeln, Unit-Test ContactExportTest.

object ContactExportLogic {
    const val PHONE_MIME = Phone.CONTENT_ITEM_TYPE
    const val MAIL_MIME = Email.CONTENT_ITEM_TYPE
    const val MAIL_MAX = 254

    /** Eine Datenzeile fuer Insert.DATA (mimetype, Wert, Typ ContactsContract) */
    data class Row(val mime: String, val value: String, val type: Int)

    data class Prefill(val name: String?, val phones: List<Row>, val mails: List<Row>) {
        val rows: List<Row> get() = phones + mails
    }

    /** Vorbefuellung - null, wenn nichts Uebertragbares da ist (dann kein Knopf) */
    fun prefill(c: BookContact): Prefill? {
        val phones = rows(c.addrs.filter { it.kind == ContactBookLogic.PHONE && !it.fixed }, PHONE_MIME, ::validPhone, ::phoneType)
        val mails = rows(c.addrs.filter { it.kind == ContactBookLogic.MAIL && !it.fixed }, MAIL_MIME, ::validMail, ::mailType)
        if (phones.isEmpty() && mails.isEmpty()) return null
        return Prefill(name(c), phones, mails)
    }

    fun canExport(c: BookContact): Boolean = prefill(c) != null

    /** Name fuer mich, sonst Name der Person - nie der Rueckfall des Servers (erste Nummer/Mail) und nie ein Saga-Wert */
    fun name(c: BookContact): String? {
        val n = listOf(c.ownName, c.name).firstNotNullOfOrNull { s -> s?.let(::clean)?.takeIf { it.isNotEmpty() } } ?: return null
        if (n == "–" || n.startsWith("~")) return null
        val inner = c.addrs.map { it.value.trim() }.toMutableSet()
        c.saga?.ref?.let { inner += it }
        return n.takeUnless { it in inner || it.filter { ch -> !ch.isWhitespace() } in inner }
    }

    /** Steuer-, Format- (Bidi, Nullbreite) und unsichtbare Zeichen raus, Leerraum zusammengefasst, hoechstens 120 Zeichen
     *  (Codepunkte wie der Server - kein halbes Ersatzzeichenpaar am Ende) */
    fun clean(s: String): String = takeCodePoints(
        s.map { if (hidden(it)) ' ' else it }.joinToString("")
            .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" "),
        ContactBookLogic.OWN_NAME_MAX,
    ).trim()

    /** Steuerzeichen (Cc) und Formatzeichen (Cf: U+200B-200F, U+202A-202E, U+2060-2069, U+FEFF …) wie iOS controlCharacters */
    private fun hidden(ch: Char): Boolean = ch.isISOControl() || ch in INVISIBLE || Character.getType(ch) == Character.FORMAT.toInt()

    private fun takeCodePoints(s: String, n: Int): String =
        if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    /** Typ der Nummer: ohne bzw. unbekannter Typ -> Andere */
    fun phoneType(t: String?): Int = when (t) {
        "mobile" -> Phone.TYPE_MOBILE
        "home" -> Phone.TYPE_HOME
        "work" -> Phone.TYPE_WORK
        else -> Phone.TYPE_OTHER
    }

    /** Typ der Mail: Android kennt auch "Mobil" */
    fun mailType(t: String?): Int = when (t) {
        "mobile" -> Email.TYPE_MOBILE
        "home" -> Email.TYPE_HOME
        "work" -> Email.TYPE_WORK
        else -> Email.TYPE_OTHER
    }

    private val E164 = Regex("\\+[1-9]\\d{7,14}")
    private const val INVISIBLE = "​‌‍⁠﻿"
    private const val MAIL_BAD = ",;?&#<>\"$INVISIBLE"

    /** nur normalisierte E.164-Werte (wie der Server sie speichert) */
    fun validPhone(v: String): Boolean = E164.matches(v)

    /** genau ein Empfaenger (Regel des Servers, Bugs-DB 2399) */
    fun validMail(v: String): Boolean = v.length <= MAIL_MAX && v.count { it == '@' } == 1 && !v.startsWith("@") && !v.endsWith("@") &&
        v.none { it.isWhitespace() || hidden(it) || it in MAIL_BAD }

    private fun rows(addrs: List<BookAddr>, mime: String, valid: (String) -> Boolean, type: (String?) -> Int): List<Row> {
        val seen = HashSet<String>()
        val out = ArrayList<Row>()
        for (a in addrs) {
            if (out.size >= ContactBookLogic.MAX_PER_KIND) break
            val v = a.value // wie gespeichert (normalisiert vom Server) - nicht trimmen
            if (!valid(v) || !seen.add(v.lowercase())) continue
            out += Row(mime, v, type(a.type))
        }
        return out
    }
}
