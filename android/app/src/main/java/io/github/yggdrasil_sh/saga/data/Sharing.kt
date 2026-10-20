package io.github.yggdrasil_sh.saga.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull

// Teilen + Kommentare (Server 1.3) - Antwortformen wie saga/sharing.py, saga/comments.py, saga/pages.py

/** Eigentuemer, Verfasser, "zuletzt geaendert von": {name, username, me}; username null = geloeschtes Konto */
@Serializable
data class Person(
    val name: String = "",
    val username: String? = null,
    val me: Boolean = false,
    /** Foederation (2.0): Person einer anderen Saga-Instanz; name enthaelt schon die Herkunft, handle = "name@server" */
    val remote: Boolean = false,
    val handle: String? = null,
    /** 2.1: Profilbild (relative Adresse /api/avatar/...) oder null = Initialen */
    val avatar: String? = null,
)

/**
 * Foederation (2.0): Seite liegt auf einer anderen Saga-Instanz. caps (2.3): was mit dem Server der Seite geht
 * (db subpages upload versions export presence live chatgroups) - fehlt das Feld (Server 2.1/2.2), bleibt es bei Lesen,
 * Kommentieren und Titel/Inhalt bearbeiten.
 */
@Serializable
data class RemoteRef(val host: String = "", val name: String? = null, val caps: List<String> = emptyList())

/** 2.3: Faehigkeiten fremder Seiten (remote.caps, docs/APP-API-FED.md Abschnitt 8) - eigene Seiten koennen alles */
object RemoteCaps {
    const val DB = "db"
    const val SUBPAGES = "subpages"
    const val UPLOAD = "upload"
    const val VERSIONS = "versions"
    const val EXPORT = "export"
    const val PRESENCE = "presence"
    const val LIVE = "live"
    const val CHATGROUPS = "chatgroups"

    /** Seite kann das: eigene Seite (nicht remote) immer, fremde nur, wenn ihr Server die Faehigkeit meldet */
    fun has(remote: Boolean, caps: Collection<String>, cap: String): Boolean = !remote || cap in caps

    /** remote.caps aus einem Seiten-Objekt (GET /api/notes/{id}); defensiv: nur Texte */
    fun of(p: JsonObject): Set<String> =
        ((p["remote"] as? JsonObject)?.get("caps") as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { x -> x.isString }?.content }.toSet()
}

/** GET /api/share/info - fuer Empfaenger: Teilen an? Wie viele Freigaben an mich? */
@Serializable
data class ShareInfo(val enabled: Boolean = true, val received: Int = 0)

/** Weiterer Weg, auf dem eine Person die Seite sieht (Gruppe hier/darueber, eigene Freigabe darueber) */
@Serializable
data class ShareVia(
    val type: String = "user",
    val name: String = "",
    val role: String = "view",
    @SerialName("note_id") val noteId: Long? = null,
    val title: String? = null,
)

/** Eine Freigabe dieser Seite (GET/PUT/DELETE /api/notes/{id}/shares) */
@Serializable
data class ShareEntry(
    val type: String = "user",
    val target: String = "",
    val name: String = "",
    val role: String = "view",
    /** tatsaechliche Rolle (hoechste aus direkter Freigabe, Gruppen und Seiten darueber) */
    val effective: String? = null,
    val via: List<ShareVia> = emptyList(),
    /** Gruppen: Anzahl Mitglieder */
    val members: Int? = null,
    /** 2.1: Profilbild (Personen) */
    val avatar: String? = null,
)

/** Freigabe einer Elternseite - gilt hier mit, aendern nur dort */
@Serializable
data class InheritedShare(
    @SerialName("note_id") val noteId: Long = 0,
    val title: String = "",
    val type: String = "user",
    val target: String = "",
    val name: String = "",
    val role: String = "view",
)

@Serializable
data class ShareState(
    val enabled: Boolean = true,
    /** "Teilen nur innerhalb gemeinsamer Gruppen" */
    val scope: Boolean = false,
    val max: Int = 200,
    /** archivierte Seiten und Vorlagen lassen sich nicht teilen */
    val shareable: Boolean = true,
    val shares: List<ShareEntry> = emptyList(),
    val inherited: List<InheritedShare> = emptyList(),
    /** eigene Gruppen (Chips bei leerem Suchfeld) */
    val groups: List<String> = emptyList(),
    /** Foederation (2.0): Personen/Gruppen anderer Saga-Instanzen als name@server moeglich */
    val federation: Boolean = false,
)

@Serializable
data class TargetUser(
    val username: String = "",
    val name: String = "",
    val avatar: String? = null,
    /** 2.4: Unterzeile fuer Gleichnamige (oeffentlicher Name bzw. Handle, nie der Anmeldename); aeltere Server: null */
    val sub: String? = null,
)

@Serializable
data class TargetGroup(val name: String = "")

/** Foederation: Person ("remote") oder Gruppe ("remote_group") auf einer anderen Saga-Instanz */
@Serializable
data class TargetRemote(
    val type: String = "remote",
    val target: String = "",
    val name: String = "",
    val host: String = "",
    /** 2.3 Adressbuch: "Anna Berg · b.example" (bei gleichen Namen mehr), Profilbild, oeffentlicher Name (oder null) */
    val label: String? = null,
    val avatar: String? = null,
    val pub: String? = null,
)

/** GET /api/share/targets?q= (ab 1 Zeichen; Server 2.3: bei 1-2 Zeichen nur genaue Treffer, aeltere erst ab 3) */
@Serializable
data class ShareTargets(
    val users: List<TargetUser> = emptyList(),
    val groups: List<TargetGroup> = emptyList(),
    /** nur genaue Adressen verbundener Server (name@server) */
    val remote: List<TargetRemote> = emptyList(),
    /** ohne Treffer bei name@server: warum nichts gefunden wurde (Server 2.2); aeltere Server ohne Feld -> null */
    @SerialName("remote_hint") val remoteHint: String? = null,
)

@Serializable
data class Comment(
    val id: Long = 0,
    @SerialName("thread_id") val threadId: Long? = null,
    @SerialName("block_id") val blockId: String = "",
    val author: Person = Person(),
    val body: String = "",
    val created: Double = 0.0,
    val edited: Double? = null,
    val mine: Boolean = false,
    /** erster Kommentar geloescht, Antworten anderer bleiben ("Kommentar gelöscht") */
    val deleted: Boolean = false,
)

/** Unterhaltung = erster Kommentar + Antworten */
@Serializable
data class CommentThread(
    val id: Long = 0,
    @SerialName("block_id") val blockId: String = "",
    @SerialName("block_exists") val blockExists: Boolean = true,
    @SerialName("resolved_at") val resolvedAt: Double? = null,
    @SerialName("resolved_by") val resolvedBy: Person? = null,
    val unread: Boolean = false,
    val comments: List<Comment> = emptyList(),
) {
    val resolved: Boolean get() = resolvedAt != null
}

@Serializable
data class CommentsResponse(
    val role: String? = null,
    @SerialName("can_comment") val canComment: Boolean = true,
    @SerialName("is_owner") val isOwner: Boolean = true,
    val threads: List<CommentThread> = emptyList(),
    @SerialName("resolved_count") val resolvedCount: Int = 0,
    val max: Int = 2000,
)

@Serializable
data class CommentPosted(val comment: Comment? = null, @SerialName("thread_id") val threadId: Long? = null)

/** Rolle eines Kontos auf einer Seite: owner > edit > comment > view */
enum class Role(val key: String, val rank: Int) {
    VIEW("view", 1), COMMENT("comment", 2), EDIT("edit", 3), OWNER("owner", 4);

    val canEdit: Boolean get() = rank >= EDIT.rank
    val canComment: Boolean get() = rank >= COMMENT.rank
    val isOwner: Boolean get() = this == OWNER

    companion object {
        /** Ohne Angabe (Server vor 1.3): Eigentuemer. Unbekannter Wert: vorsichtshalber nur lesen. */
        fun of(s: String?): Role = if (s == null) OWNER else entries.firstOrNull { it.key == s } ?: VIEW
    }
}

/**
 * Was auf einer Seite erlaubt ist - aus der Rolle abgeleitet. Nur der Eigentuemer darf loeschen, archivieren,
 * veroeffentlichen, teilen, sperren und Favoriten setzen (Favoriten sind die des Eigentuemers).
 */
data class PageCaps(val role: Role, val shareSupported: Boolean) {
    val foreign: Boolean get() = !role.isOwner
    val canEditContent: Boolean get() = role.canEdit
    val canComment: Boolean get() = shareSupported && role.canComment
    val canFavorite: Boolean get() = role.isOwner
    val canLock: Boolean get() = role.isOwner
    val canPublish: Boolean get() = role.isOwner
    val canShare: Boolean get() = role.isOwner && shareSupported
    val canArchive: Boolean get() = role.isOwner
    val canTrash: Boolean get() = role.isOwner
    val showComments: Boolean get() = shareSupported
    /** Aufgaben-, Kalender- und Immich-Bloecke zeigen Nicht-Eigentuemern nur einen Platzhalter */
    fun ownerOnlyBlock(type: String): Boolean = foreign && type in OWNER_ONLY_BLOCKS

    companion object {
        val OWNER_ONLY_BLOCKS = setOf("tasks", "calendar", "immich")
    }
}

/** Seitenangaben fuer Teilen/Kommentare aus GET /api/notes/{id} */
data class PageShareInfo(
    /** Server kennt Teilen (Antwort enthaelt "role") */
    val supported: Boolean = false,
    val role: Role = Role.OWNER,
    val owner: Person? = null,
    val shared: Boolean = false,
    val commentCounts: Map<String, Int> = emptyMap(),
    val commentsOpen: Int = 0,
    val commentsUnread: Int = 0,
    val commentsResolved: Int = 0,
    val updatedBy: Person? = null,
    val createdBy: Person? = null,
    val updated: Double? = null,
) {
    companion object {
        private fun person(p: JsonObject, key: String): Person? {
            val o = p[key] as? JsonObject ?: return null
            return Person(
                name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                username = (o["username"] as? JsonPrimitive)?.contentOrNull,
                me = (o["me"] as? JsonPrimitive)?.booleanOrNull ?: false,
                remote = (o["remote"] as? JsonPrimitive)?.booleanOrNull ?: false,
                handle = (o["handle"] as? JsonPrimitive)?.contentOrNull,
                avatar = (o["avatar"] as? JsonPrimitive)?.contentOrNull,
            )
        }

        private fun int(p: JsonObject, key: String): Int = (p[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0

        fun of(p: JsonObject): PageShareInfo {
            if (!p.containsKey("role")) return PageShareInfo()
            val counts = (p["comment_counts"] as? JsonObject)?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }?.let { k to it }
            }?.toMap().orEmpty()
            return PageShareInfo(
                supported = true,
                role = Role.of((p["role"] as? JsonPrimitive)?.contentOrNull),
                owner = person(p, "owner"),
                shared = (p["shared"] as? JsonPrimitive)?.booleanOrNull ?: false,
                commentCounts = counts,
                commentsOpen = int(p, "comments_open"),
                commentsUnread = int(p, "comments_unread"),
                commentsResolved = int(p, "comments_resolved"),
                updatedBy = person(p, "updated_by"),
                createdBy = person(p, "created_by"),
                updated = (p["updated"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
            )
        }
    }
}

/**
 * Verweise auf Seiten ohne Zugriff: der Server ersetzt bei Nicht-Eigentuemern den Text durch "Seite ohne Zugriff"
 * (in der Sprache der Anfrage). Die App zeigt sie gedaempft mit 🔒 und ohne Link; gespeichert wird der Inhalt
 * unveraendert (der Server setzt die Originaltexte wieder ein).
 */
object Masking {
    val PLACEHOLDERS = setOf("Seite ohne Zugriff", "Page without access", "Página sin acceso")
    private val PAGE_HREF = Regex("^/notes#[1-9][0-9]{0,17}$")
    private val LINK = Regex("<a\\s[^>]*?href=\"/notes#[1-9][0-9]{0,17}\"[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun isMasked(href: String?, text: String): Boolean = href != null && PAGE_HREF.matches(href) && text.trim() in PLACEHOLDERS

    /** Nur zur Anzeige (nie speichern): 🔒 vor verdeckte Verweise */
    fun forDisplay(html: String): String {
        if (!html.contains("/notes#")) return html
        return LINK.replace(html) { m -> if (m.groupValues[1].trim() in PLACEHOLDERS) "🔒 " + m.value else m.value }
    }
}

/** 409 beim Speichern: wer zuletzt geaendert hat ({"error":"conflict","by":"Name"}), sonst null */
fun conflictAuthor(body: String): String? = try {
    ((kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject)?.get("by") as? JsonPrimitive)
        ?.contentOrNull?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/** Zugriff beim Speichern verloren: 403 = nur noch lesen, 404 = kein Zugriff mehr; text = letzter eigener Stand */
data class LostAccess(val code: Int, val text: String) {
    val readOnly: Boolean get() = code == 403
}

/**
 * Seite als Klartext (Titel + Bloecke, wie sie gerade im Editor stehen) - fuer "Kopieren" nach Konflikt oder
 * verlorenem Zugriff. Texte verschachtelter Bloecke (Listen, Ausklappbar, Spalten) kommen in Reihenfolge mit.
 */
object DocText {
    private val TEXT_KEYS = listOf("text", "title", "code", "caption")

    fun plain(title: String, doc: JsonObject): String {
        val out = ArrayList<String>()
        blocks(doc["blocks"], out)
        val body = out.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        return (if (title.isNotBlank()) title.trim() + "\n\n" else "").plus(body).trim()
    }

    private fun blocks(el: kotlinx.serialization.json.JsonElement?, out: MutableList<String>) {
        val arr = el as? kotlinx.serialization.json.JsonArray ?: return
        for (b in arr) {
            val o = b as? JsonObject ?: continue
            val d = o["data"] as? JsonObject ?: continue
            for (k in TEXT_KEYS) {
                val s = (d[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                out.add(if (k == "code") s else io.github.yggdrasil_sh.saga.blocks.InlineHtml.plain(s))
            }
            items(d["items"], out, "")
            blocks(d["blocks"], out)
            (d["cols"] as? kotlinx.serialization.json.JsonArray)?.forEach { c -> blocks((c as? JsonObject)?.get("blocks"), out) }
        }
    }

    private fun items(el: kotlinx.serialization.json.JsonElement?, out: MutableList<String>, indent: String) {
        val arr = el as? kotlinx.serialization.json.JsonArray ?: return
        val lines = ArrayList<String>()
        fun walk(a: kotlinx.serialization.json.JsonArray, ind: String) {
            for (it in a) {
                val o = it as? JsonObject
                val html = if (o != null) (o["content"] as? JsonPrimitive)?.content.orEmpty() else (it as? JsonPrimitive)?.content.orEmpty()
                val t = io.github.yggdrasil_sh.saga.blocks.InlineHtml.plain(html).trim()
                if (t.isNotEmpty()) lines.add("$ind• $t")
                (o?.get("items") as? kotlinx.serialization.json.JsonArray)?.let { walk(it, "$ind  ") }
            }
        }
        walk(arr, indent)
        if (lines.isNotEmpty()) out.add(lines.joinToString("\n"))
    }
}

/**
 * Hintergrund-Abgleich der Entwuerfe (SagaRepository.syncDrafts): nur eigene Seiten. Entwuerfe fremder Seiten
 * bleiben liegen, bis die Seite geoeffnet wird (dort Rolle pruefen, Wiederherstellen nur mit "Bearbeiten";
 * 403/404 -> Text zum Kopieren). own = IDs aus GET api/notes, null = noch nicht geladen -> nichts hochladen.
 */
object DraftSync {
    fun mayUpload(id: Long, own: Set<Long>?): Boolean = own != null && id in own
}

/** Zeile im Bereich "Mit mir geteilt" */
data class SharedRow(val page: PageMeta, val depth: Int, val hasChildren: Boolean)

/**
 * "Mit mir geteilt" als Baum: oberste geteilte Seiten (root=true; ihre Eltern sind fuer mich unsichtbar), darunter
 * die Unterseiten (aufklappbar). Zeilen von Datenbanken erscheinen nicht im Baum (wie bei eigenen Seiten).
 */
object SharedTree {
    fun rows(shared: List<PageMeta>, expanded: Set<Long>): List<SharedRow> {
        val dbs = shared.filter { it.isDatabase }.map { it.id }.toSet()
        val kids = shared.filter { !it.root }.groupBy { it.parentId }
        fun children(id: Long): List<PageMeta> = if (id in dbs) emptyList() else kids[id].orEmpty()
        val out = ArrayList<SharedRow>()
        fun walk(list: List<PageMeta>, depth: Int) {
            for (p in list) {
                val c = children(p.id)
                out.add(SharedRow(p, depth, c.isNotEmpty()))
                if (p.id in expanded && c.isNotEmpty() && depth < 12) walk(c, depth + 1)
            }
        }
        walk(shared.filter { it.root }, 0)
        return out
    }
}
