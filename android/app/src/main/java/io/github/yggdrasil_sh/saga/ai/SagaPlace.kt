package io.github.yggdrasil_sh.saga.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * 2.6 Ort "Saga" in der Android-App (Bugs-DB 2269 K-I Apps + 2267 K-B "Rueckmeldung in der App"; Vertrag
 * docs/APP-API-KI.md Abschnitte 9-11 im Server-Repo). Hier nur Datenformen und reine Regeln (Unit-Tests: SagaPlaceTest);
 * Netz in SagaService, Oberflaeche in ui/saga. Alle Saetze zu Anbieter/Host, Zustandswoerter und Klassen kommen fertig
 * vom Server - die App baut nie selbst "im Heimnetz", "lokal" oder ein Zustandswort.
 */

/** status.style (K-H 2268): Kennzeichnung "{Persona} · KI" in der Sprache der Anfrage */
@Serializable
data class AiStyle(val label: String = "", val form: String? = null, val lively: String? = null, val salutation: String? = null)

/** status.consent bzw. Antwort von /api/ai/consent: none | later | granted | withdrawn | stale */
@Serializable
data class AiConsent(
    val state: String = "none",
    val at: Double? = null,
    val version: String? = null,
    val ideas: String? = null,
    val host: String? = null,
)

/** status.endpoint (M4): Host des Modell-Endpunkts mit fertigem Hinweis (label) - nie selbst bilden */
@Serializable
data class AiEndpoint(
    val host: String = "",
    val net: String? = null,
    val label: String = "",
    val note: String? = null,
    val provider: String? = null,
)

/** status.intro (K19): Kennenlernen-Karte - alle Texte vom Server */
@Serializable
data class AiIntro(
    val version: String = "",
    val host: String = "",
    val lines: List<String> = emptyList(),
    val note: String? = null,
    val question: String? = null,
    @SerialName("ideas_allowed") val ideasAllowed: Boolean = false,
    val details: List<String> = emptyList(),
    val bar: String = "",
)

// ---------------------------------------------------------------------------------------------------- Gespraech

@Serializable
data class ThreadTurn(
    val n: Long = 0,
    val role: String = "ai",
    val zug: String? = null,
    val text: String = "",
    val fields: JsonObject? = null,
    val created: Double? = null,
    @SerialName("job_id") val jobId: Long? = null,
) {
    val me: Boolean get() = role == "me"
}

data class ThreadState(val turns: List<ThreadTurn>, val history: Boolean, val keep: Long?)

// ---------------------------------------------------------------------------------------------------- Auftraege

@Serializable
data class JobItem(
    val id: Long = 0,
    val title: String = "",
    val state: String = "",
    @SerialName("state_text") val stateText: String = "",
    val sub: String? = null,
    val group: String? = null,
    val line: String? = null,
    val created: Double? = null,
    val updated: Double? = null,
    @SerialName("plan_v") val planV: Int? = null,
)

@Serializable
data class JobGroup(val key: String = "", val label: String = "", val items: List<JobItem> = emptyList())

@Serializable
data class JobList(val groups: List<JobGroup> = emptyList(), val counts: Map<String, Int> = emptyMap())

@Serializable
data class PlanStep(
    val n: Int = 0,
    val tool: String = "",
    val klass: String = "",
    @SerialName("kind_text") val kindText: String = "",
    @SerialName("undo_class") val undoClass: String? = null,
    @SerialName("class_text") val classText: String = "",
    val text: String = "",
    val wording: String? = null,
    val edit: List<String> = emptyList(),
    val fields: JsonObject? = null,
    val state: String? = null,
)

@Serializable
data class PlanRead(val ref: String? = null, val label: String = "")

@Serializable
data class JobPlan(
    val job: Long = 0,
    @SerialName("plan_v") val planV: Int = 0,
    @SerialName("plan_hash") val planHash: String = "",
    val title: String = "",
    val state: String? = null,
    val steps: List<PlanStep> = emptyList(),
    val read: List<PlanRead> = emptyList(),
    @SerialName("read_text") val readText: String = "",
    val say: String? = null,
    val recipe: String? = null,
    val edited: Boolean = false,
)

@Serializable
data class ReportLine(val change: Long = 0, val text: String = "", val undone: Boolean = false, @SerialName("can_undo") val canUndo: Boolean = false)

@Serializable
data class JobReport(val text: String = "", val lines: List<ReportLine> = emptyList())

@Serializable
data class JobChange(
    val id: Long = 0,
    val step: Int? = null,
    val target: String = "",
    val label: String = "",
    @SerialName("undo_class") val undoClass: String? = null,
    @SerialName("class_text") val classText: String? = null,
    val undone: Boolean = false,
    @SerialName("can_undo") val canUndo: Boolean = false,
    val why: String? = null,
    @SerialName("version_url") val versionUrl: String? = null,
)

@Serializable
data class JobEventRow(val at: Double = 0.0, val kind: String = "", val text: String = "")

@Serializable
data class JobApproved(val at: Double = 0.0, val device: String? = null)

@Serializable
data class JobDetail(
    val id: Long = 0,
    val title: String = "",
    val state: String = "",
    @SerialName("state_text") val stateText: String = "",
    val group: String? = null,
    @SerialName("group_text") val groupText: String? = null,
    @SerialName("reason_text") val reasonText: String? = null,
    val plan: JobPlan? = null,
    val approved: JobApproved? = null,
    val changes: List<JobChange> = emptyList(),
    val report: JobReport? = null,
    val events: List<JobEventRow> = emptyList(),
    val actions: List<String> = emptyList(),
)

/** "Alte Fassung" (GET …/changes/{c}/before) */
@Serializable
data class JobBefore(val target: String = "", val title: String = "", val text: String = "")

/** refuse.alternative: Rezept direkt ("Als Aufgabe anlegen") */
data class RefuseAlternative(val recipe: String, val fields: JsonObject?, val label: String?)

/** Ereignisse von POST /api/ai/jobs (SSE): status zuerst, dann genau eines von plan | ask | refuse | error */
sealed interface JobStreamEvent {
    data class Status(val text: String) : JobStreamEvent
    data class Plan(val plan: JobPlan) : JobStreamEvent
    data class Ask(val question: String, val choices: List<String>) : JobStreamEvent
    data class Refuse(val text: String, val alternative: RefuseAlternative?) : JobStreamEvent
    /** status = HTTP-Code (null = Ereignis "error" im Strom); consentNeeded = 403 {consent: "needed"} */
    data class Failed(val error: String, val status: Int?, val consentNeeded: Boolean = false) : JobStreamEvent
}

/** 409 bzw. Fehlerantwort: code stale | state | changed, version_url fuer "Alte Fassung ansehen" */
data class SagaConflict(val code: String?, val error: String?, val versionUrl: String?, val planV: Int?, val state: String?)

// ---------------------------------------------------------------------------------------------------- Einstellungen

@Serializable
data class PrefPair(val salutation: String? = null, val lively: String? = null)

@Serializable
data class PrefChoices(val salutation: List<String> = emptyList(), val lively: List<String> = emptyList())

/** GET/PUT /api/ai/prefs (Abschnitt 9 + 11.5); KI aus -> {enabled: false} */
@Serializable
data class SagaPrefs(
    val enabled: Boolean? = null,
    val salutation: String? = null,
    val lively: String? = null,
    val effective: PrefPair? = null,
    val default: PrefPair? = null,
    val choices: PrefChoices? = null,
    val ideas: String? = null,
    val history: Boolean? = null,
    @SerialName("history_allowed") val historyAllowed: Boolean = false,
    @SerialName("ideas_allowed") val ideasAllowed: Boolean = false,
    val consent: AiConsent? = null,
)

/** Reine Regeln fuer den Ort "Saga" (ohne Android, Tests: SagaPlaceTest) */
object SagaLogic {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    /** Die vier Gruppen nach aussen (K2), immer in dieser Reihenfolge */
    val GROUPS = listOf("wartet_auf_dich", "laeuft", "fertig", "nicht_geklappt")

    // ------------------------------------------------------------------ Faehigkeit / Sichtbarkeit

    /**
     * Bereich "Saga" sichtbar? Nur, wenn der Server den Ort kennt (K-I: consent + intro in /api/ai/status) und die
     * Person die Faehigkeit "ai" hat. Aeltere Server ohne diese Felder, Assistent aus oder unbekannter Stand: unsichtbar.
     */
    fun available(st: AiStatus?): Boolean =
        st != null && st.enabled && st.consent != null && st.intro != null && (st.caps == null || "ai" in st.caps)

    /** Reiter "Auftraege" nur, wenn der Server Auftraege anbietet (jobs, Stufe nicht "answers") */
    fun jobsOn(st: AiStatus?): Boolean = available(st) && st?.jobs == true && st.level != "answers"

    fun consentOk(st: AiStatus?): Boolean = st?.consent?.state == "granted"

    /** Kennenlernen-Karte auf "Heute": einmal bis zur Antwort (none) bzw. nach Wechsel des Endpunkts (stale) */
    fun introOnToday(st: AiStatus?): Boolean = available(st) && st?.consent?.state in setOf("none", "stale")

    /** Kennzeichnung B1/K11: style.label vom Server; null = Rueckfall "{name} · KI" in der App */
    fun label(st: AiStatus?): String? = st?.style?.label?.trim()?.takeIf { it.isNotEmpty() }

    /** Knoepfe der Kennenlernen-Karte (K19): mit ai_ideas "Ja, mit Ideen" · "Ja, ohne Ideen", sonst "Ja, einverstanden" */
    enum class IntroAnswer(val wire: String) { IDEAS("ideas"), NO_IDEAS("no_ideas"), AGREE("no_ideas"), LATER("later") }

    fun introAnswers(intro: AiIntro): List<IntroAnswer> =
        if (intro.ideasAllowed) listOf(IntroAnswer.IDEAS, IntroAnswer.NO_IDEAS, IntroAnswer.LATER)
        else listOf(IntroAnswer.AGREE, IntroAnswer.LATER)

    /** POST /api/ai/consent: Fassung und Host genau der gezeigten Karte (409, wenn der Server inzwischen anders ist) */
    fun consentBody(answer: IntroAnswer, intro: AiIntro): JsonObject = buildJsonObject {
        put("answer", answer.wire)
        put("version", intro.version)
        put("host", intro.host)
    }

    /** Anrede du/Sie: im Englischen ohne Unterschied - Auswahl entfaellt (Abschnitt 9) */
    fun salutationShown(lang: String): Boolean = !lang.lowercase().startsWith("en")

    // ------------------------------------------------------------------ Auftraege: Zustaende und Gruppen

    /** Zustand (intern 7) -> Gruppe nach aussen (4); unbekannt = null (nicht zeigen) */
    fun groupOf(state: String): String? = when (state) {
        "wartet_ok", "wartet_dich" -> "wartet_auf_dich"
        "laeuft" -> "laeuft"
        "erledigt", "abgebrochen", "zurueckgenommen" -> "fertig"
        "fehlgeschlagen" -> "nicht_geklappt"
        else -> null
    }

    /**
     * Liste vom Server -> immer die 4 Gruppen in fester Reihenfolge. Fehlt eine Gruppe (Filter, aelterer Server), steht
     * sie leer da; Eintraege mit unbekannter Gruppe sortiert der Zustand ein, ganz Unbekanntes faellt weg.
     */
    fun groups(list: JobList): List<JobGroup> {
        val byKey = LinkedHashMap<String, MutableList<JobItem>>()
        GROUPS.forEach { byKey[it] = ArrayList() }
        val labels = HashMap<String, String>()
        for (g in list.groups) {
            if (g.label.isNotBlank()) labels[g.key] = g.label
            for (it in g.items) {
                val k = g.key.takeIf { it in byKey } ?: it.group?.takeIf { x -> x in byKey } ?: groupOf(it.state) ?: continue
                byKey[k]!!.add(it)
            }
        }
        return GROUPS.map { k -> JobGroup(k, labels[k].orEmpty(), byKey[k]!!) }
    }

    fun total(groups: List<JobGroup>): Int = groups.sumOf { it.items.size }

    /** Symbol der Auftrags-Zeile (B5) je Gruppe - die Bedeutung steht immer zusaetzlich als Wort da */
    fun groupSymbol(key: String): String = when (key) {
        "nicht_geklappt" -> "!"
        "laeuft" -> "◷"
        "fertig" -> "✓"
        else -> "●"
    }

    /** Art-Symbol eines Schritts (B3): ◎ Lesen · ✎ Eigenes · ↗ Nach aussen */
    fun kindSymbol(klass: String): String = when (klass) {
        "read" -> "◎"
        "own" -> "✎"
        "out" -> "↗"
        else -> "•"
    }

    /** Nebenzeile: Zustandswort + Grund (ohne doppeltes Wort) - wie das Web */
    fun lineText(it: JobItem): String {
        val l = it.line?.trim().orEmpty()
        return when {
            l.isEmpty() || l == it.stateText -> it.stateText
            l.startsWith(it.stateText) -> l
            it.stateText.isEmpty() -> l
            else -> it.stateText + " · " + l
        }
    }

    /** Darf der Plan geaendert werden ("Aendern" nur mit Feldern aus steps[].edit) */
    fun canEdit(plan: JobPlan): Boolean = plan.steps.any { it.edit.isNotEmpty() }

    /** "Passt, los": Freigabe nur mit genau diesem plan_v + plan_hash (der Plan ist ein Vertrag, K3) */
    fun approveBody(plan: JobPlan): JsonObject = buildJsonObject {
        put("plan_v", plan.planV)
        put("plan_hash", plan.planHash)
    }

    /** Freigabe moeglich? Hash muss da sein (64 Zeichen), sonst nie senden */
    fun approvable(plan: JobPlan): Boolean = plan.job > 0 && plan.planHash.length == 64 && plan.steps.isNotEmpty()

    /** Wert eines Feldes zum Bearbeiten (fields[k], Wortlaut als Vorbelegung fuer markdown) */
    fun fieldValue(step: PlanStep, key: String): String {
        val v = step.fields?.get(key) as? JsonPrimitive
        val s = v?.contentOrNull
        if (s != null && v !is JsonNull) return s
        return if (key == "markdown") step.wording.orEmpty() else ""
    }

    /** PUT …/plan: nur die bearbeitbaren Felder; n = Stelle des Schritts im Plan */
    fun editBody(plan: JobPlan, values: Map<Int, Map<String, String>>): JsonObject = buildJsonObject {
        put("plan_v", plan.planV)
        put("edits", JsonArray(values.toSortedMap().mapNotNull { (i, m) ->
            val step = plan.steps.getOrNull(i) ?: return@mapNotNull null
            val args = m.filterKeys { it in step.edit }
            if (args.isEmpty()) return@mapNotNull null
            buildJsonObject {
                put("n", i)
                put("args", buildJsonObject {
                    args.forEach { (k, v) -> if (k == "allday") put(k, v == "true") else put(k, v.trim()) }
                })
            }
        }))
    }

    /** Fehlerantwort lesen (409 stale/state/changed, 403 consent) - kaputt = alles null */
    fun conflict(body: String): SagaConflict {
        val o = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        }
        fun s(k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        return SagaConflict(s("code"), s("error"), s("version_url"), (o?.get("plan_v") as? JsonPrimitive)?.intOrNull, s("state"))
    }

    /** 403 {consent: "needed"}: Status neu laden, Karte zeigen - nichts ging ans Modell */
    fun consentNeeded(code: Int, body: String): Boolean {
        if (code != 403) return false
        val o = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        }
        return (o?.get("consent") as? JsonPrimitive)?.contentOrNull == "needed"
    }

    /** "Alte Fassung": nur Pfade dieses Auftrags auf dem eigenen Server (nie eine fremde Adresse aus der Antwort) */
    fun beforePathOk(url: String?, job: Long): Boolean {
        val u = url?.trim().orEmpty()
        return u.startsWith("/api/ai/jobs/$job/changes/") && u.endsWith("/before") && !u.contains("..") && !u.contains("://") &&
            !u.contains('?') && !u.contains('#')
    }

    /** Ein Ereignis von POST /api/ai/jobs lesen; unbekannte/kaputte Ereignisse -> null (ignorieren) */
    fun jobEvent(event: String, data: String): JobStreamEvent? {
        val o = try {
            json.parseToJsonElement(data) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return if (event in setOf("plan", "ask", "refuse", "error")) JobStreamEvent.Failed("", null) else null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        return when (event) {
            "status" -> JobStreamEvent.Status(s("text").orEmpty())
            "plan" -> try {
                JobStreamEvent.Plan(json.decodeFromJsonElement(JobPlan.serializer(), o))
            } catch (_: Exception) {
                JobStreamEvent.Failed("", null)
            }
            "ask" -> JobStreamEvent.Ask(s("question").orEmpty(), (o["choices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            "refuse" -> {
                val a = o["alternative"] as? JsonObject
                val recipe = (a?.get("recipe") as? JsonPrimitive)?.contentOrNull
                JobStreamEvent.Refuse(
                    s("text").orEmpty(),
                    if (a != null && recipe != null) RefuseAlternative(recipe, a["fields"] as? JsonObject, (a["label"] as? JsonPrimitive)?.contentOrNull) else null,
                )
            }
            "error" -> JobStreamEvent.Failed(s("error").orEmpty(), (o["status"] as? JsonPrimitive)?.intOrNull)
            else -> null
        }
    }

    /** Body fuer "Als Aufgabe anlegen" (Rezept direkt, ohne Modell): nur die Kennung, nie ein Rezept-Objekt (RT-21) */
    fun recipeBody(text: String, alt: RefuseAlternative): JsonObject = buildJsonObject {
        put("text", text.ifBlank { " " })
        put("recipe", alt.recipe)
        alt.fields?.let { put("fields", it) }
    }

    /** Quellen eines KI-Zugs (fields.sources [{n, id, title}]) fuer [n] und "aus:" */
    fun turnSources(t: ThreadTurn): List<AiSource> = (t.fields?.get("sources") as? JsonArray).orEmpty().mapNotNull {
        try {
            json.decodeFromJsonElement(AiSource.serializer(), it)
        } catch (_: Exception) {
            null
        }
    }

    /** GET /api/ai/thread lesen */
    fun thread(body: String): ThreadState {
        val o = json.parseToJsonElement(body) as? JsonObject ?: return ThreadState(emptyList(), false, null)
        val turns = (o["turns"] as? JsonArray).orEmpty().mapNotNull {
            try {
                json.decodeFromJsonElement(ThreadTurn.serializer(), it)
            } catch (_: Exception) {
                null
            }
        }
        val hist = (o["history"] as? JsonPrimitive)?.contentOrNull == "true"
        val keep = (o["keep"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
        return ThreadState(turns, hist, keep)
    }

    /** Einstellung setzen: null = Standard des Servers (JSON null) */
    fun prefBody(key: String, value: String?): JsonObject = buildJsonObject { put(key, value?.let { JsonPrimitive(it) } ?: JsonNull) }

    fun prefBody(key: String, value: Boolean): JsonObject = buildJsonObject { put(key, value) }

    /** Dateiname fuer den Export aus Content-Disposition; sonst saga-ki-<tag>.<format>; nur sichere Zeichen */
    fun exportName(contentDisposition: String?, format: String, day: String): String {
        val m = Regex("filename=\"([^\"]+)\"").find(contentDisposition.orEmpty())?.groupValues?.get(1)
        val clean = m?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.trimStart('.')?.takeIf { it.isNotBlank() && it.length <= 80 }
        return clean ?: "saga-ki-$day.$format"
    }

    fun mimeOf(format: String): String = if (format == "md") "text/markdown" else "application/json"

    /** Eingabe in einen Wert fuer JSON (kurze Hilfe fuer Tests) */
    fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}
