package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * 2.5: Vorlagengalerie (einfache Auswahl in der App, SPEC-galerie §5). Liste aus GET api/gallery?lang=, Anlegen mit
 * POST api/gallery/{id}/use {"parent_id", "examples"} -> {"root", "created"}. Rein Kotlin (ohne Android), damit
 * Parsing und Fehlerfaelle per Unit-Test geprueft werden koennen. Der Server ist tolerant zu lesen: fehlende oder
 * falsch getypte Felder fuehren nicht zum Abbruch, nur Eintraege ohne id fallen weg.
 */
data class GalleryContent(val kind: String, val title: String, val icon: String)

data class GalleryItem(
    val id: String,
    val title: String,
    val desc: String,
    val icon: String,
    /** personal | work | team (unbekannte Werte landen unter "other") */
    val category: String,
    val order: Int,
    val needs: List<String> = emptyList(),
    val contents: List<GalleryContent> = emptyList(),
)

data class GalleryGroup(val category: String, val items: List<GalleryItem>)

data class GalleryUseResult(val root: Long, val created: List<Long>)

/** Ergebnis beim Laden der Liste */
sealed interface GalleryLoad {
    data class Ok(val items: List<GalleryItem>) : GalleryLoad

    /** Server ohne Galerie (aelter, 404) oder Galerie abgeschaltet - Eintrag ausblenden */
    data object Unsupported : GalleryLoad

    data class Failed(val text: UiText) : GalleryLoad
}

object Gallery {
    /** Reihenfolge der Gruppen; alles andere danach unter "other" */
    val CATEGORIES = listOf("personal", "work", "team")
    const val OTHER = "other"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------ Pfade und Inhalt

    /** lang: App-Sprache ("de", "en", ...); leer -> ohne Parameter (der Server nimmt dann Accept-Language) */
    fun listPath(lang: String): String {
        val l = lang.trim().substringBefore('-').substringBefore('_').lowercase()
        return if (l.isEmpty() || !l.all { it in 'a'..'z' }) "api/gallery" else "api/gallery?lang=$l"
    }

    fun usePath(id: String): String = "api/gallery/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20") + "/use"

    fun useBody(parentId: Long?, examples: Boolean): JsonObject = buildJsonObject {
        if (parentId != null) put("parent_id", parentId) else put("parent_id", JsonNull)
        put("examples", examples)
    }

    /**
     * Elternseite fuer "Aus Vorlage ...": nur eigene bzw. mit "Bearbeiten" geteilte Seiten dieses Servers, keine
     * Datenbanken, keine Seiten anderer Saga-Instanzen (Foederation: Etappe 1 nicht angeboten).
     */
    fun canBeParent(p: PageMeta): Boolean {
        if (p.isDatabase || p.isRemote || p.id <= 0) return false
        val foreign = p.owner != null && !p.owner.me
        return !foreign || p.role == "edit"
    }

    // ------------------------------------------------------------ Parsing

    /** {"items": [...]} oder notfalls eine blanke Liste; Eintraege ohne id werden uebersprungen */
    fun parseList(text: String): List<GalleryItem> {
        val root = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw UiTextException(UiText.Res(R.string.gal_error_bad_answer))
        }
        val arr = when (root) {
            is JsonObject -> root["items"] as? JsonArray ?: JsonArray(emptyList())
            is JsonArray -> root
            else -> throw UiTextException(UiText.Res(R.string.gal_error_bad_answer))
        }
        val seen = HashSet<String>()
        return arr.mapNotNull { (it as? JsonObject)?.let(::item) }.filter { seen.add(it.id) }
    }

    private fun item(o: JsonObject): GalleryItem? {
        val id = str(o["id"]).trim()
        if (id.isEmpty()) return null
        val cat = str(o["category"]).trim().lowercase().let { if (it in CATEGORIES) it else OTHER }
        val needs = (o["needs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
        val contents = (o["contents"] as? JsonArray)?.mapNotNull { e ->
            (e as? JsonObject)?.let { GalleryContent(str(it["kind"]).ifEmpty { "page" }, str(it["title"]), str(it["icon"])) }
        }.orEmpty()
        return GalleryItem(
            id = id,
            title = str(o["title"]).ifBlank { id },
            desc = str(o["desc"]),
            icon = str(o["icon"]),
            category = cat,
            order = num(o["order"])?.toInt() ?: Int.MAX_VALUE,
            needs = needs,
            contents = contents,
        )
    }

    /** {"root": id, "created": [...]}; root fehlt -> erste angelegte Seite; IDs auch als Zeichenkette */
    fun parseUse(text: String): GalleryUseResult {
        val o = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw UiTextException(UiText.Res(R.string.gal_error_bad_answer))
        val created = (o["created"] as? JsonArray)?.mapNotNull { num(it) }.orEmpty()
        val root = num(o["root"]) ?: created.firstOrNull() ?: throw UiTextException(UiText.Res(R.string.gal_error_bad_answer))
        return GalleryUseResult(root, created)
    }

    /** Gruppen in fester Reihenfolge (Persoenlich, Arbeit, Team, Sonstige), innen nach order, dann Titel */
    fun groups(items: List<GalleryItem>): List<GalleryGroup> {
        val by = items.groupBy { it.category }
        return (CATEGORIES + OTHER).mapNotNull { c ->
            by[c]?.sortedWith(compareBy<GalleryItem> { it.order }.thenBy { it.title.lowercase() })?.let { GalleryGroup(c, it) }
        }
    }

    private fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

    private fun num(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.booleanOrNull != null && !p.isString) return null
        return p.content.trim().toLongOrNull() ?: p.content.trim().toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toLong()
    }

    // ------------------------------------------------------------ Fehler

    /** Laden der Liste: 404 -> Server ohne Galerie (Eintrag ausblenden), sonst freundliche Meldung */
    fun classifyList(e: Throwable, friendly: (Throwable) -> UiText): GalleryLoad =
        if (e is ApiException && e.code == 404) GalleryLoad.Unsupported else GalleryLoad.Failed(friendlyList(e, friendly))

    private fun friendlyList(e: Throwable, friendly: (Throwable) -> UiText): UiText =
        if (e is ApiException) useError(e.code, e.body) ?: friendly(e) else friendly(e)

    /** Anlegen: Meldung fuer die Oberflaeche (Netzfehler usw. ueber friendly) */
    fun friendlyUse(e: Throwable, friendly: (Throwable) -> UiText): UiText =
        if (e is ApiException) useError(e.code, e.body) ?: friendly(e) else friendly(e)

    /** Meldung des Servers aus {"error": "..."} (leer, null oder keine Zeichenkette -> null) */
    fun serverMessage(body: String): String? = try {
        ((json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** Technische Kurztexte des Servers, die nichts erklaeren - dann lieber der eigene Text */
    private val GENERIC = setOf("not found", "forbidden", "bad request", "too many requests", "rate limited", "unauthorized", "error", "internal server error")

    /**
     * Fehler beim Anlegen (bzw. Laden) nach Code. 400/403: Meldung des Servers, wenn sie etwas sagt (z. B. "unter
     * foederiert geteilten Seiten nicht moeglich"), sonst eigener Text; 404: Vorlage nicht (mehr) da; 429: kurz warten;
     * 5xx: Serverfehler. null = allgemeiner Fall (401 usw.) -> uebliche Fehlertexte der App.
     */
    fun useError(code: Int, body: String): UiText? {
        val msg = serverMessage(body)?.takeIf { it.lowercase() !in GENERIC }
        return when {
            code == 429 -> UiText.Res(R.string.gal_error_rate)
            code == 404 -> UiText.Res(R.string.gal_error_gone)
            code == 403 -> msg?.let { UiText.Raw(it) } ?: UiText.Res(R.string.gal_error_forbidden)
            code == 400 || code == 409 || code == 413 || code == 422 -> msg?.let { UiText.Raw(it) } ?: UiText.Res(R.string.gal_error_invalid)
            code >= 500 -> UiText.Res(R.string.gal_error_server, code)
            else -> null
        }
    }
}

/**
 * Merkt sich je Server, ob er die Galerie anbietet (null = unbekannt, wird beim ersten Anzeigen des Menues geprueft)
 * und die zuletzt geladene Liste je Sprache (kleine Antwort, Oeffnen ohne Warten).
 */
class GalleryState {
    /** Server, fuer die die Galerie nicht angeboten wird (404) */
    val unsupported = MutableStateFlow<Set<String>>(emptySet())
    /** geladene Listen je "server|lang" (StateFlow: Menue erscheint, sobald die Pruefung fertig ist) */
    val lists = MutableStateFlow<Map<String, List<GalleryItem>>>(emptyMap())

    fun available(server: String): Boolean = server.isNotEmpty() && server !in unsupported.value

    fun key(server: String, lang: String) = "$server|$lang"

    fun cached(server: String, lang: String): List<GalleryItem>? = lists.value[key(server, lang)]

    /** Ergebnis eines Abrufs uebernehmen */
    @Synchronized fun record(server: String, lang: String, load: GalleryLoad) {
        when (load) {
            is GalleryLoad.Ok -> {
                lists.value = lists.value + (key(server, lang) to load.items)
                if (server in unsupported.value) unsupported.value = unsupported.value - server
            }
            GalleryLoad.Unsupported -> {
                lists.value = lists.value.filterKeys { !it.startsWith("$server|") }
                unsupported.value = unsupported.value + server
            }
            is GalleryLoad.Failed -> {}
        }
    }

    @Synchronized fun clear() {
        lists.value = emptyMap()
        unsupported.value = emptySet()
    }
}
