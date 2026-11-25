package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.data.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Saga 2.6 (Bugs-DB 2027a-6): Adressbuch nach docs/APP-API.md "Kontakte (2.6, Bugs-DB 2027a, contacts2)" -
// GET/POST/PATCH/DELETE /api/contacts, GET/PUT /api/contacts/settings, Live contacts.changed. Nur mit Feature contacts2.
// Ein Kontakt vergibt keine Rechte; Saga-Adressen (fest) entstehen nur aus gemeinsamen Chats und sind hier nie
// aenderbar. Reine Regeln in ContactBookLogic (Unit-Test ContactBookTest), Netz in ContactBook.

/** Live-Angaben der festen Saga-Adresse (wie /api/chat/contacts) */
@Serializable
data class BookSaga(
    val ref: String = "",
    val remote: Boolean = false,
    val host: String? = null,
    val sub: String = "",
    val avatar: String? = null,
    val ready: Boolean = false,
    val gone: Boolean = false,
)

/** Adresse: kind saga | fed | e164 | email; type mobile | home | work | other | null; fixed = aus einem Chat */
@Serializable
data class BookAddr(
    val id: Long? = null,
    val kind: String = "",
    val value: String = "",
    val type: String? = null,
    val fixed: Boolean = false,
)

/** Kontakt-Objekt (GET /api/contacts/{id}) */
@Serializable
data class BookContact(
    val id: Long = 0,
    val ver: Long = 0,
    /** chat | manual */
    val origin: String = "manual",
    val name: String = "",
    @SerialName("own_name") val ownName: String? = null,
    val saga: BookSaga? = null,
    /** informal | formal | null */
    val form: String? = null,
    val salutation: String? = null,
    val addrs: List<BookAddr> = emptyList(),
    @SerialName("last_at") val lastAt: Double? = null,
    val created: Double = 0.0,
    val updated: Double = 0.0,
) {
    val manual: Boolean get() = origin != "chat"
    val phones: List<BookAddr> get() = addrs.filter { it.kind == ContactBookLogic.PHONE && !it.fixed }
    val mails: List<BookAddr> get() = addrs.filter { it.kind == ContactBookLogic.MAIL && !it.fixed }
}

@Serializable
data class BookList(val contacts: List<BookContact> = emptyList(), val total: Int = 0)

object ContactBookLogic {
    const val FEATURE = "contacts2"
    const val PHONE = "e164"
    const val MAIL = "email"
    const val LIMIT = 200
    const val MAX_PER_KIND = 10
    const val OWN_NAME_MAX = 120
    const val SALUTATION_MAX = 80
    const val EMAIL_MAX = 254
    val TYPES = listOf("mobile", "home", "work", "other")

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    fun has(features: Collection<String>?): Boolean = features?.contains(FEATURE) == true

    fun parse(text: String?): BookContact? = try {
        text?.let { json.decodeFromString<BookContact>(it) }?.takeIf { it.id > 0 }
    } catch (_: Exception) {
        null
    }

    fun parse(o: JsonObject?): BookContact? = try {
        o?.let { json.decodeFromJsonElement(BookContact.serializer(), it) }?.takeIf { it.id > 0 }
    } catch (_: Exception) {
        null
    }

    fun parseList(text: String): BookList = json.decodeFromString(text)

    /** 409 bei veraltetem ver: {error, contact} -> aktueller Stand (sonst null) */
    fun conflictContact(body: String?): BookContact? = try {
        (body?.let { Json.parseToJsonElement(it) } as? JsonObject)?.get("contact")?.let { parse(it as? JsonObject) }
    } catch (_: Exception) {
        null
    }

    /** 409 bei doppeltem Wert: {error, contact_id} (nie der Wert) */
    fun duplicateId(body: String?): Long? = try {
        ((body?.let { Json.parseToJsonElement(it) } as? JsonObject)?.get("contact_id") as? JsonPrimitive)?.longOrNull
    } catch (_: Exception) {
        null
    }

    /** Pfad der Liste; q URL-kodiert (Teilwort in Name, Unterzeile, Mail; ab 3 Ziffern auch Nummern) */
    fun listPath(q: String = "", limit: Int = LIMIT, offset: Int = 0): String = buildString {
        append("api/contacts?limit=").append(limit.coerceIn(1, LIMIT))
        if (offset > 0) append("&offset=").append(offset)
        val s = q.trim()
        if (s.isNotEmpty()) append("&q=").append(java.net.URLEncoder.encode(s, "UTF-8"))
    }

    // ------------------------------------------------------------------------------------------------ Bearbeiten

    /** Eingabezeile Nummer/Mail; type null = ohne Typ */
    data class AddrDraft(val value: String = "", val type: String? = null)

    /** Bearbeitungsstand (Name fuer mich, Anrede, Grussform, Nummern, Mails) */
    data class Draft(
        val ownName: String = "",
        val form: String? = null,
        val salutation: String = "",
        val phones: List<AddrDraft> = emptyList(),
        val mails: List<AddrDraft> = emptyList(),
    ) {
        fun cleanPhones(): List<AddrDraft> = phones.map { it.copy(value = it.value.trim()) }.filter { it.value.isNotEmpty() }
        fun cleanMails(): List<AddrDraft> = mails.map { it.copy(value = it.value.trim()) }.filter { it.value.isNotEmpty() }
    }

    /** Draft aus einem Kontakt (null = neuer Kontakt: je eine leere Nummer und Mail) */
    fun draftOf(c: BookContact?): Draft {
        if (c == null) return Draft(phones = listOf(AddrDraft(type = "mobile")), mails = listOf(AddrDraft()))
        return Draft(
            ownName = c.ownName.orEmpty(),
            form = c.form?.takeIf { it == "informal" || it == "formal" },
            salutation = c.salutation.orEmpty(),
            phones = c.phones.map { AddrDraft(it.value, it.type?.takeIf { t -> t in TYPES }) },
            mails = c.mails.map { AddrDraft(it.value, it.type?.takeIf { t -> t in TYPES }) },
        )
    }

    /** Warum (noch) nicht speichern? null = ok. Neu bzw. manuell: Name fuer mich Pflicht und >= 1 Nummer/Mail */
    enum class Problem { NAME_NEEDED, ADDR_NEEDED, NAME_LONG, SALUTATION_LONG, TOO_MANY_PHONES, TOO_MANY_MAILS, BAD_PHONE, BAD_MAIL }

    fun problem(d: Draft, manual: Boolean): Problem? {
        val p = d.cleanPhones()
        val m = d.cleanMails()
        return when {
            manual && d.ownName.isBlank() -> Problem.NAME_NEEDED
            manual && p.isEmpty() && m.isEmpty() -> Problem.ADDR_NEEDED
            count(d.ownName.trim()) > OWN_NAME_MAX -> Problem.NAME_LONG
            count(d.salutation.trim()) > SALUTATION_MAX -> Problem.SALUTATION_LONG
            p.distinctBy { phoneKey(it.value) }.size > MAX_PER_KIND -> Problem.TOO_MANY_PHONES
            m.distinctBy { mailKey(it.value) }.size > MAX_PER_KIND -> Problem.TOO_MANY_MAILS
            p.any { !phoneLooksOk(it.value) } -> Problem.BAD_PHONE
            m.any { !mailLooksOk(it.value) } -> Problem.BAD_MAIL
            else -> null
        }
    }

    fun count(s: String): Int = s.codePointCount(0, s.length)

    private val PHONE_SEP = Regex("[\\s\\-/.()]")

    /** Wie der Server: Leerzeichen, - / . Klammern weg (fuer Vergleich und Vorpruefung) */
    fun phoneKey(v: String): String = v.trim().replace("(0)", "").replace(PHONE_SEP, "")

    /** Vorpruefung (der Server entscheidet): + oder 0 am Anfang, sonst nur Ziffern, 7 bis 16 Ziffern */
    fun phoneLooksOk(v: String): Boolean {
        val k = phoneKey(v)
        if (!Regex("\\+?\\d+").matches(k)) return false
        val digits = k.trimStart('+')
        return (k.startsWith("+") || k.startsWith("0")) && digits.length in 7..17
    }

    /** Nationale Nummer (0…, nicht 00…) - braucht eine Landesvorwahl */
    fun national(v: String): Boolean {
        val k = phoneKey(v)
        return k.startsWith("0") && !k.startsWith("00")
    }

    /** Wie der Server: Domain klein, Teil vor @ wie eingegeben */
    fun mailKey(v: String): String {
        val s = v.trim()
        val i = s.lastIndexOf('@')
        return if (i < 0) s else s.substring(0, i) + "@" + s.substring(i + 1).lowercase()
    }

    /** Vorpruefung Mail: genau ein @, beide Teile, keine Leerzeichen, <= 254 */
    fun mailLooksOk(v: String): Boolean {
        val s = v.trim()
        if (s.length > EMAIL_MAX || s.any { it.isWhitespace() }) return false
        val at = s.split('@')
        return at.size == 2 && at[0].isNotEmpty() && at[1].isNotEmpty()
    }

    private fun addrsJson(d: Draft): JsonArray = buildJsonArray {
        val seen = HashSet<String>()
        d.cleanPhones().forEach { a ->
            if (seen.add("p:" + phoneKey(a.value))) add(buildJsonObject {
                put("kind", PHONE); put("value", a.value); a.type?.let { put("type", it) }
            })
        }
        d.cleanMails().forEach { a ->
            if (seen.add("m:" + mailKey(a.value))) add(buildJsonObject {
                put("kind", MAIL); put("value", a.value); a.type?.let { put("type", it) }
            })
        }
    }

    /** Braucht der Draft eine Landesvorwahl (eine nationale Nummer)? */
    fun needsCc(d: Draft): Boolean = d.cleanPhones().any { national(it.value) }

    private fun text(s: String): JsonPrimitive? = s.trim().takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }

    /** POST /api/contacts {own_name, form?, salutation?, addrs, cc?} */
    fun createBody(d: Draft, cc: String?): JsonObject = buildJsonObject {
        put("own_name", d.ownName.trim())
        d.form?.let { put("form", it) }
        text(d.salutation)?.let { put("salutation", it) }
        put("addrs", addrsJson(d))
        if (cc != null && needsCc(d)) put("cc", cc)
    }

    /**
     * PATCH /api/contacts/{id} {ver, ...}: nur geaenderte Felder ("" bzw. null leert); addrs nur, wenn sich die privaten
     * Adressen (Wert, Typ, Reihenfolge) geaendert haben - ersetzt sie, feste bleiben beim Server.
     */
    fun patchBody(base: BookContact, d: Draft, cc: String?): JsonObject {
        val old = draftOf(base)
        return buildJsonObject {
            put("ver", base.ver)
            if (d.ownName.trim() != old.ownName.trim()) put("own_name", text(d.ownName) ?: JsonNull)
            if (d.form != old.form) put("form", d.form?.let { JsonPrimitive(it) } ?: JsonNull)
            if (d.salutation.trim() != old.salutation.trim()) put("salutation", text(d.salutation) ?: JsonNull)
            if (d.cleanPhones() != old.cleanPhones() || d.cleanMails() != old.cleanMails()) {
                put("addrs", addrsJson(d))
                if (cc != null && needsCc(d)) put("cc", cc)
            }
        }
    }

    /** Hat der Draft etwas geaendert (Verwerfen-Rueckfrage)? */
    fun changed(base: BookContact?, d: Draft): Boolean {
        val o = draftOf(base)
        return d.ownName.trim() != o.ownName.trim() || d.form != o.form || d.salutation.trim() != o.salutation.trim() ||
            d.cleanPhones() != o.cleanPhones() || d.cleanMails() != o.cleanMails()
    }

    fun ccBody(cc: String?): JsonObject = buildJsonObject { put("cc", cc?.let { JsonPrimitive(it) } ?: JsonNull) }

    /** Landesvorwahl aus der Eingabe: "49", "+49", " 0049"? -> nur 1-3 Ziffern, erste nicht 0; sonst null */
    fun ccOf(s: String?): String? {
        val t = s?.trim()?.removePrefix("+")?.trim() ?: return null
        return t.takeIf { Regex("[1-9]\\d{0,2}").matches(it) }
    }

    /** GET /api/contacts/settings -> cc oder null */
    fun ccFrom(text: String?): String? = try {
        ((text?.let { Json.parseToJsonElement(it) } as? JsonObject)?.get("cc") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { ccOf(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Vorschlag aus der Region des Geraets (ISO 3166-1, z. B. "DE" -> "49"). Kleine feste Tabelle statt einer
     * Telefonnummern-Bibliothek; unbekannt -> null (dann fragt die App nicht).
     */
    fun ccForRegion(region: String?): String? {
        val r = region?.trim()?.uppercase()?.takeIf { it.length == 2 } ?: return null
        return CC[r]
    }

    private val CC: Map<String, String> by lazy {
        ("DE49 AT43 CH41 LI423 LU352 BE32 NL31 FR33 MC377 IT39 SM378 VA39 ES34 AD376 PT351 GB44 IE353 IS354 DK45 NO47 SE46 FI358 " +
            "EE372 LV371 LT370 PL48 CZ420 SK421 HU36 SI386 HR385 BA387 RS381 ME382 MK389 AL355 XK383 GR30 CY357 MT356 BG359 RO40 " +
            "MD373 UA380 BY375 RU7 KZ7 TR90 GE995 AM374 AZ994 IL972 PS970 LB961 JO962 SY963 IQ964 IR98 SA966 AE971 QA974 KW965 " +
            "BH973 OM968 YE967 EG20 MA212 DZ213 TN216 LY218 SD249 ET251 KE254 TZ255 UG256 RW250 NG234 GH233 SN221 CI225 CM237 " +
            "ZA27 NA264 BW267 ZW263 ZM260 MZ258 AO244 CD243 MG261 MU230 US1 CA1 PR1 MX52 GT502 SV503 HN504 NI505 CR506 PA507 " +
            "CU53 DO1 JM1 HT509 CO57 VE58 EC593 PE51 BO591 PY595 UY598 AR54 CL56 BR55 IN91 PK92 BD880 LK94 NP977 AF93 CN86 HK852 " +
            "MO853 TW886 JP81 KR82 KP850 MN976 VN84 TH66 LA856 KH855 MM95 MY60 SG65 ID62 PH63 BN673 TL670 AU61 NZ64 FJ679 PG675")
            .split(' ').filter { it.length >= 3 }.associate { it.substring(0, 2) to it.substring(2) }
    }

    // ------------------------------------------------------------------------------------------------ Anzeige

    /** tel:-Adresse fuer ACTION_DIAL (Wert ist E.164 vom Server) */
    fun telUri(value: String): String = "tel:" + value.filter { it.isDigit() || it == '+' }

    fun mailUri(value: String): String = "mailto:" + value.trim()

    /** Unterzeile in der Liste: Saga-Person (Unterzeile bzw. Server), sonst erste Nummer bzw. Mail */
    fun listSub(c: BookContact, phone: (String) -> String = { it }): String? {
        val s = c.saga
        val first = c.phones.firstOrNull()?.value?.let(phone) ?: c.mails.firstOrNull()?.value
        if (s != null) {
            val where = if (s.remote) (s.host ?: s.sub).takeIf { it.isNotBlank() } else s.sub.takeIf { it.isNotBlank() && !ChatLogic.opaque(it) }
            return listOfNotNull(where, first).joinToString(" · ").ifEmpty { null }
        }
        return first
    }

    /** "Nachricht" aus dem Detail: lokal {user: ref}, fremd {handle: ref}; nur anschreibbar */
    fun canMessage(c: BookContact): Boolean = c.saga?.let { !it.gone && it.ref.isNotEmpty() } == true

    /** Live contacts.changed {id, deleted?} -> (id, geloescht) */
    fun changedEvent(data: JsonObject): Pair<Long, Boolean>? {
        val id = (data["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val del = (data["deleted"] as? JsonPrimitive)?.content == "true"
        return id to del
    }

    /** Stand in der Liste ersetzen bzw. entfernen (ohne Neuladen) */
    fun applied(list: List<BookContact>, c: BookContact?, removedId: Long? = null): List<BookContact> {
        if (removedId != null) return list.filterNot { it.id == removedId }
        if (c == null) return list
        return if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it } else listOf(c) + list
    }
}

/**
 * Netz des Adressbuchs (ein Objekt je ChatService). tick zaehlt bei Live contacts.changed und eigenen Aenderungen hoch
 * - offene Listen/Details laden neu; lastChanged = zuletzt geaenderte ID (geloescht: negativ).
 */
class ContactBook(
    private val get: suspend (String) -> String,
    private val post: suspend (String, JsonObject) -> String,
    private val patch: suspend (String, JsonObject) -> String,
    private val put: suspend (String, JsonObject) -> String,
    private val delete: suspend (String) -> String,
    /** Server kann es (contacts2) */
    private val supported: () -> Boolean,
) {
    val tick = MutableStateFlow(0)
    @Volatile var lastChanged: Long = 0

    val on: Boolean get() = supported()

    /** Ergebnis von Anlegen/Speichern */
    sealed interface Saved {
        data class Ok(val contact: BookContact) : Saved
        /** ver veraltet: aktueller Stand (Eingabe erneut anbieten) */
        data class Conflict(val current: BookContact) : Saved
        /** Wert steht schon bei einem anderen Kontakt */
        data class Duplicate(val contactId: Long, val error: ApiException) : Saved
    }

    suspend fun list(q: String = "", offset: Int = 0): BookList =
        if (!on) BookList() else ContactBookLogic.parseList(get(ContactBookLogic.listPath(q, offset = offset)))

    /** null = nicht (mehr) da (404) */
    suspend fun one(id: Long): BookContact? = try {
        ContactBookLogic.parse(get("api/contacts/$id"))
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    private fun bump(id: Long) {
        lastChanged = id
        tick.value++
    }

    private suspend fun saved(block: suspend () -> String): Saved = try {
        val c = ContactBookLogic.parse(block()) ?: throw IllegalStateException("contact")
        bump(c.id)
        Saved.Ok(c)
    } catch (e: ApiException) {
        if (e.code != 409) throw e
        val cur = ContactBookLogic.conflictContact(e.body)
        val dup = ContactBookLogic.duplicateId(e.body)
        when {
            cur != null -> Saved.Conflict(cur)
            dup != null -> Saved.Duplicate(dup, e)
            else -> throw e
        }
    }

    suspend fun create(d: ContactBookLogic.Draft, cc: String?): Saved = saved { post("api/contacts", ContactBookLogic.createBody(d, cc)) }

    suspend fun update(base: BookContact, d: ContactBookLogic.Draft, cc: String?): Saved {
        val body = ContactBookLogic.patchBody(base, d, cc)
        if (body.size <= 1) return Saved.Ok(base)          // nur ver: nichts geaendert
        return saved { patch("api/contacts/${base.id}", body) }
    }

    suspend fun remove(id: Long) {
        delete("api/contacts/$id")
        bump(-id)
    }

    suspend fun cc(): String? = ContactBookLogic.ccFrom(get("api/contacts/settings"))

    suspend fun setCc(cc: String?): String? = ContactBookLogic.ccFrom(put("api/contacts/settings", ContactBookLogic.ccBody(cc))).also { tick.value++ }

    /** Live contacts.changed */
    fun onLive(data: JsonObject) {
        val (id, del) = ContactBookLogic.changedEvent(data) ?: return
        bump(if (del) -id else id)
    }
}
