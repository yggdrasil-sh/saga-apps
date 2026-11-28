package io.github.yggdrasil_sh.saga.ai

import io.github.yggdrasil_sh.saga.data.SharedContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * 2.6 K-G (Bugs-DB 2275): Teilen -> Auftrag, Einstiege in den Ort "Saga" (Kurzbefehle, 2508 aus einer Seite). Vertrag
 * docs/APP-API-KI.md Abschnitt 14 im Server-Repo, Vektoren tests/vectors/share_contract.json (Kopie unter
 * test/resources/vectors). Nur reine Regeln (Unit-Tests: SagaShareTest) - Texte kommen fertig vom Server, die App baut
 * keine Saetze ueber Herkunft oder Warnung. Bewusst ohne Regex (ICU auf dem Geraet != JVM).
 */

/** Geteilter Inhalt fuer POST /api/ai/jobs `shared` (from ist in der App immer "app") */
data class SharedPayload(val text: String = "", val url: String? = null, val title: String? = null) {
    /** Host der Adresse fuer die Zeile "Geteilt: host" (wie der Server: klein, ohne www., Port und Zugangsdaten) */
    val host: String? get() = SagaShare.host(url)
}

/** Eigener Zug mit geteiltem Inhalt (fields.shared {host, from}) bzw. lokal: nur Host/Titel, nie der Text */
data class SharedMark(val host: String?, val title: String? = null)

/** Wohin ein Einstieg fuehrt (Kurzbefehl, Widget): nur oeffnen, nie senden */
enum class SagaEntry { ASK, JOB, JOBS }

object SagaShare {
    /** Server-Merkmal (/api/version features): nur damit gibt es die Option "{Name} soll sich kümmern …" */
    const val FEATURE = "aishare1"
    const val TEXT_MAX = 20_000
    const val URL_MAX = 2_000
    const val TITLE_MAX = 200

    /**
     * F4: Option nur mit aishare1, Auftraegen (jobs, Stufe nicht answers), Faehigkeit ai_act (eingeschraenkte Gruppe:
     * nie, RT-17) und Einwilligung (ohne sie fuehrte der Weg nur zur Kennenlernen-Karte - wie das Web). Aeltere
     * Server ohne caps: nein.
     */
    fun enabled(st: AiStatus?, features: Set<String>): Boolean =
        FEATURE in features && SagaLogic.jobsOn(st) && SagaLogic.consentOk(st) && st?.caps?.contains("ai_act") == true

    /** Adresse brauchbar fuer shared.url? nur http(s), ohne Leerraum, hoechstens 2000 Zeichen */
    fun urlOk(url: String?): Boolean {
        val u = url ?: return false
        if (u.isEmpty() || u.length > URL_MAX || u.any { it.isWhitespace() || it.code < 0x20 }) return false
        val l = u.lowercase()
        val rest = when {
            l.startsWith("https://") -> u.substring(8)
            l.startsWith("http://") -> u.substring(7)
            else -> return false
        }
        return rest.isNotEmpty() && rest[0] != '/'
    }

    /** Host wie der Server (share_host): klein, ohne www., ohne Port und Zugangsdaten; sonst null */
    fun host(url: String?): String? {
        if (!urlOk(url)) return null
        val u = url!!
        var a = u.substring(u.indexOf("://") + 3)
        val end = a.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) a = a.substring(0, end)
        val at = a.lastIndexOf('@')
        if (at >= 0) a = a.substring(at + 1)
        a = if (a.startsWith("[")) {
            val close = a.indexOf(']')
            if (close < 0) return null
            a.substring(1, close)
        } else {
            val colon = a.indexOf(':')
            if (colon >= 0) a.substring(0, colon) else a
        }
        a = a.trim('.').lowercase()
        if (a.startsWith("www.") && a.count { it == '.' } >= 2) a = a.substring(4)        // wie der Server: "www.example" bleibt
        return a.take(253).ifEmpty { null }
    }

    /** Hoechstens n UTF-16-Einheiten, ohne ein Ersatzpaar zu zerschneiden (n UTF-16 >= n Codepunkte beim Server) */
    fun cut(s: String, n: Int): String {
        if (s.length <= n) return s
        var e = n
        if (e > 0 && Character.isHighSurrogate(s[e - 1])) e--
        return s.substring(0, e)
    }

    /** Steuerzeichen ausser Zeilenumbruch/Tab weg (der Server tut es auch - so stimmt die Laenge) */
    private fun clean(s: String): String = buildString(s.length) {
        for (c in s) if (c == '\n' || c == '\t' || c.code >= 0x20 && c.code != 0x7F) append(c)
    }

    /**
     * Aus dem Teilen-Ziel: Text bis 20 000 Zeichen (F9), Titel bis 200, Adresse nur gueltig (sonst als Text, damit nichts
     * verloren geht). Ohne Text und ohne Adresse -> null (der Server lehnte es mit share_empty ab).
     */
    fun of(c: SharedContent): SharedPayload? {
        var text = clean(c.text).trim()
        var url = c.url?.trim()?.takeIf { it.isNotEmpty() }
        if (url != null && !urlOk(url)) {
            text = if (text.isEmpty()) url else "$text\n$url"
            url = null
        }
        text = cut(text, TEXT_MAX)
        val title = cut(clean(c.title).replace('\n', ' ').trim(), TITLE_MAX).trim().ifEmpty { null }
        if (text.isEmpty() && url == null) return null
        return SharedPayload(text, url, title)
    }

    /** Feld shared (14.1): leere Felder weglassen, from immer "app" */
    fun sharedJson(p: SharedPayload): JsonObject = buildJsonObject {
        if (p.text.isNotEmpty()) put("text", p.text)
        p.url?.takeIf { urlOk(it) }?.let { put("url", it) }
        p.title?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
        put("from", "app")
    }

    /** Senden moeglich? Mit geteiltem Inhalt auch ohne eigene Worte (Systembitte "Kümmere dich darum.") */
    fun canSend(draft: String, shared: SharedPayload?): Boolean = draft.isNotBlank() || shared != null

    /**
     * Welcher geteilte Inhalt mit einem Auftrag aus dem Gespraech geht (14.1): nie mit einem Rezept, bei der Antwort auf
     * eine Rueckfrage der Inhalt dieser Rueckfrage (nach einem Neustart null), sonst der aus der Zeile "Geteilt".
     */
    fun sharedFor(recipe: Boolean, choice: String?, askShared: SharedPayload?, line: SharedPayload?): SharedPayload? = when {
        recipe -> null
        choice != null -> askShared
        else -> line
    }

    /** Markierung eines eigenen Zugs aus dem Verlauf (fields.shared {host, from}); ohne shared -> null */
    fun turnMark(t: ThreadTurn): SharedMark? {
        val sh = t.fields?.get("shared") as? JsonObject ?: return null
        if (!t.me) return null
        val h = (sh["host"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
        return SharedMark(h)
    }

    /** "Geteilt: host" | "Geteilt: „Titel“" (nur ohne Host) | "Geteilt" - welche Form (der Satz steht in den Strings) */
    enum class LabelKind { HOST, TITLE, PLAIN }

    fun labelKind(m: SharedMark): LabelKind = when {
        !m.host.isNullOrBlank() -> LabelKind.HOST
        !m.title.isNullOrBlank() -> LabelKind.TITLE
        else -> LabelKind.PLAIN
    }

    fun mark(p: SharedPayload): SharedMark = SharedMark(p.host, p.title)

    /** 2531: "Deine Bitte" nur, wenn sie etwas sagt, das der Titel nicht schon sagt (wie Web/iOS) */
    fun requestShown(d: JobDetail): String? {
        val r = d.request.trim()
        return if (r.isEmpty() || r == d.title.trim()) null else r
    }

    /** Herkunft zeigen? nur mit Text (origin null = Plan ohne geteilten Inhalt) */
    fun originShown(o: PlanOrigin?): Boolean = o != null && o.text.isNotBlank()

    /** Warnzeile nur bei warn mit Text */
    fun warnShown(o: PlanOrigin?): String? = o?.takeIf { it.warn }?.warnText?.trim()?.ifEmpty { null }

    // ------------------------------------------------------------------ Kurzbefehle (statisch, res/xml/shortcuts.xml)

    const val ACTION_ASK = "io.github.yggdrasil_sh.saga.action.SAGA_ASK"
    const val ACTION_JOB = "io.github.yggdrasil_sh.saga.action.SAGA_JOB"

    /**
     * Kurzbefehl -> Ziel. Nur die Aktion zaehlt: Extras, Daten und Text werden ignoriert (14.3) - ein Kurzbefehl oeffnet
     * nur, sendet nie. Aus "Zuletzt" (alter Intent) nichts.
     */
    fun shortcut(action: String?, fromHistory: Boolean): SagaEntry? = if (fromHistory) null else when (action) {
        ACTION_ASK -> SagaEntry.ASK
        ACTION_JOB -> SagaEntry.JOB
        else -> null
    }
}
