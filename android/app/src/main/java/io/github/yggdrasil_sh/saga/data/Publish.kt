package io.github.yggdrasil_sh.saga.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Seiten veroeffentlichen (Server 1.2, saga/publish.py): Antworten der Schnittstelle.
 *  GET/PUT/DELETE /api/notes/{id}/publish -> PublishState
 *  GET/PUT /api/account/public-name        -> PublicNameInfo / NameCheck
 */

/** Elternseite, ueber die eine Unterseite oeffentlich ist ("Unterseiten mit veroeffentlichen") */
@Serializable
data class PublishVia(@SerialName("note_id") val noteId: Int, val title: String = "", val url: String = "")

/** Verweis auf eine andere eigene Seite (bisherige Profilseite) */
@Serializable
data class PublishRef(@SerialName("note_id") val noteId: Int, val title: String = "")

@Serializable
data class PublishState(
    val enabled: Boolean = true,
    val name: String? = null,
    val published: Boolean = false,
    val home: Boolean = false,
    val url: String? = null,
    @SerialName("profile_url") val profileUrl: String? = null,
    @SerialName("published_via") val publishedVia: PublishVia? = null,
    @SerialName("withdrawn_at") val withdrawnAt: Double? = null,
    @SerialName("withdrawn_by") val withdrawnBy: String? = null,
    val slug: String = "",
    val children: Boolean = false,
    val indexable: Boolean = false,
    val profile: Boolean = false,
    @SerialName("profile_other") val profileOther: PublishRef? = null,
    @SerialName("suggested_name") val suggestedName: String? = null,
    @SerialName("replaced_profile") val replacedProfile: PublishRef? = null,
)

/** GET /api/account/public-name (published = Anzahl oeffentlicher Seiten) */
@Serializable
data class PublicNameInfo(
    val name: String? = null,
    val url: String? = null,
    val enabled: Boolean = true,
    val published: Int = 0,
    val suggested: String? = null,
)

/** PUT /api/account/public-name {name, check: true} */
@Serializable
data class NameCheck(val ok: Boolean = false, val name: String = "", val error: String? = null)

/** PUT /api/account/public-name {name} */
@Serializable
data class NameSaved(val ok: Boolean = false, val name: String = "", val url: String? = null)

object Publish {
    /** Eingaben wie im Web: ohne Leerraum, klein geschrieben */
    fun norm(s: String): String = s.trim().lowercase()

    /** Server unterstuetzt Veroeffentlichen (GET /api/notes/{id} enthaelt "published") */
    fun supported(note: JsonObject): Boolean = note.containsKey("published")

    /** published_via aus GET /api/notes/{id} (null, wenn nicht vorhanden oder unlesbar) */
    fun via(note: JsonObject): PublishVia? {
        val o = note["published_via"] as? JsonObject ?: return null
        val id = (o["note_id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        return PublishVia(id, s("title"), s("url"))
    }

    /** Adresse der oeffentlichen Seiten ohne "/@name" (aus profile_url, sonst der eingestellte Server) */
    fun publicBase(st: PublishState?, fallback: String): String {
        val p = st?.profileUrl
        val cut = p?.lastIndexOf("/@") ?: -1
        return (if (p != null && cut > 0) p.substring(0, cut) else fallback).trimEnd('/')
    }

    /** Fehler beim Veroeffentlichen: 409/400 mit need_name -> erst den Namensschritt zeigen */
    data class NeedName(val suggested: String?)

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun needName(e: Throwable): NeedName? {
        if (e !is ApiException || e.code !in 400..499) return null
        val o = try {
            lenient.parseToJsonElement(e.body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        if ((o["need_name"] as? JsonPrimitive)?.booleanOrNull != true) return null
        return NeedName((o["suggested_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() })
    }

    /**
     * Fehler gehoert zur Adresse (Feld markieren statt Statuszeile): {"field": "slug"} oder 400/409 auf eine Anfrage
     * mit "slug" (Format falsch bzw. "Diese Adresse benutzt schon eine andere deiner Seiten.").
     */
    fun slugError(e: Throwable, sentSlug: Boolean): Boolean {
        if (e !is ApiException || e.code !in 400..499 || needName(e) != null) return false
        val o = try {
            lenient.parseToJsonElement(e.body) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val field = (o?.get("field") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (field != null) return field == "slug"
        return sentSlug && (e.code == 400 || e.code == 409)
    }

    /**
     * Adresse fuer die Anzeige: Umbruch nur nach "/" (U+200B dahinter, nicht nach "://"), Bindestriche untrennbar
     * (U+2011). Nur anzeigen - kopiert/geteilt wird immer die Original-Adresse.
     */
    fun breakable(url: String): String {
        val start = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
        return (url.substring(0, start) + url.substring(start).replace("/", "/\u200B")).replace("-", "\u2011")
    }

    /** Aeltere Server ohne /publish: 404 ohne {"error": ...} (eine fehlende Seite meldet {"error": "not found"}) */
    fun unsupported(e: Throwable): Boolean = e is ApiException && e.code == 404 && !e.body.contains("\"error\"")
}
