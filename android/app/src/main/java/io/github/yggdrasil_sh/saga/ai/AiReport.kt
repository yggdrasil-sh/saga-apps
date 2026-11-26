package io.github.yggdrasil_sh.saga.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * KI-Ausgaben melden (2.6, Bugs-DB 2507 / 2258 Teil a, docs/APP-API-KI.md Abschnitt 6; Web static/ai.js reportBtn):
 * POST /api/ai/report {where, reason, text?, page?}. where = "ask" (Frag deine Seiten, Gespraech im Ort "Saga"), "page"
 * (Seiten-Hilfen, page = Seiten-ID) oder "job" (Plan-Karte, Rueckmeldung, page = Auftrags-ID). KI im Chat meldet ueber
 * POST /api/chat/report (eigene KI-Antwort mit passendem Beleg). Reine Regeln, Tests: AiReportTest.
 */
object AiReportLogic {
    /** Gruende wie im Chat (chat_grp.REASONS) - dieselbe Liste im Admin-Bereich */
    val REASONS = listOf("spam", "abuse", "illegal", "other")

    /** hoechstens so viele Zeichen Text (Server: chat_grp.REPORT_TEXT_MAX) */
    const val TEXT_MAX = 2000

    /** Wo die KI-Ausgabe stand */
    enum class Where(val wire: String, val needsId: Boolean) { ASK("ask", false), PAGE("page", true), JOB("job", true) }

    /** Ziel einer Meldung: Ort + ID (Seite bzw. Auftrag), vorbelegter Text */
    data class Target(val where: Where, val id: Long? = null, val text: String)

    fun ask(text: String) = Target(Where.ASK, null, text)

    fun page(id: Long, text: String) = Target(Where.PAGE, id, text)

    fun job(id: Long, text: String) = Target(Where.JOB, id, text)

    /** Meldbar? page/job brauchen eine gueltige ID (Seiten anderer Server: negative IDs -> nein) */
    fun ok(t: Target): Boolean = !t.where.needsId || (t.id != null && t.id > 0)

    /** Vorbelegung: Text der KI, auf die Grenze gekuerzt */
    fun prefill(text: String): String = text.trim().take(TEXT_MAX)

    /** Anfrage: Grund aus der festen Liste (sonst "other"), Text nur, wenn mitgeschickt; page nur fuer page/job */
    fun body(t: Target, reason: String, text: String?): JsonObject = buildJsonObject {
        put("where", t.where.wire)
        put("reason", reason.takeIf { it in REASONS } ?: "other")
        text?.trim()?.take(TEXT_MAX)?.takeIf { it.isNotEmpty() }?.let { put("text", it) }
        if (t.where.needsId && t.id != null) put("page", t.id)
    }

    /** Text einer Plan-Karte fuer die Meldung (Schritte + Wortlaut, wie das Web) */
    fun planText(plan: JobPlan): String = plan.steps.joinToString("\n") { s -> s.text + (s.wording?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "") }
}
