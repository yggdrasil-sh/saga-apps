package io.github.yggdrasil_sh.saga.ui.share

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.Role
import io.github.yggdrasil_sh.saga.data.ShareEntry
import io.github.yggdrasil_sh.saga.data.ShareState
import io.github.yggdrasil_sh.saga.data.ShareTargets
import io.github.yggdrasil_sh.saga.data.ShareVia
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException

/** Freigaben einer Seite verwalten (SagaRepository; in Tests eine Attrappe) */
interface ShareBackend {
    suspend fun shares(id: Int): ShareState
    suspend fun putShare(id: Int, type: String, target: String, role: String): ShareState
    suspend fun deleteShare(id: Int, type: String, target: String): ShareState
    suspend fun shareTargets(query: String): ShareTargets
    /** Personen-Symbol im Seitenbaum nachziehen */
    fun shareChanged(id: Int, shared: Boolean) {}
    fun friendly(e: Throwable): UiText
}

/** Reine Regeln des Teilen-Dialogs (wie static/pages.js shareMain) - Unit-Tests */
object ShareLogic {
    /** waehlbare Rollen fuer Freigaben */
    val ROLES = listOf(Role.VIEW, Role.COMMENT, Role.EDIT)
    const val MIN_QUERY = 3

    /** Rollenname ("Lesen", "Kommentieren", "Bearbeiten", "Eigentümer") */
    @StringRes
    fun roleName(r: Role): Int = when (r) {
        Role.VIEW -> R.string.share_role_view
        Role.COMMENT -> R.string.share_role_comment
        Role.EDIT -> R.string.share_role_edit
        Role.OWNER -> R.string.share_role_owner
    }

    /** Etikett auf fremden Seiten: "Du kannst lesen / lesen und kommentieren / bearbeiten" */
    @StringRes
    fun roleYou(r: Role): Int = when (r) {
        Role.VIEW -> R.string.share_you_view
        Role.COMMENT -> R.string.share_you_comment
        else -> R.string.share_you_edit
    }

    /** Erklaerzeile zur gewaehlten Rolle */
    @StringRes
    fun roleHelp(r: Role): Int = when (r) {
        Role.VIEW -> R.string.share_help_view
        Role.COMMENT -> R.string.share_help_comment
        else -> R.string.share_help_edit
    }

    fun canSearch(q: String): Boolean = q.trim().length >= MIN_QUERY

    /** Hilfetext am Suchfeld */
    @StringRes
    fun searchHint(st: ShareState): Int = if (st.scope) R.string.share_search_hint_scope else R.string.share_search_hint

    /** Eigene Gruppen als Chips - nur bei leerem Suchfeld */
    fun chipsVisible(st: ShareState, query: String): Boolean = st.groups.isNotEmpty() && query.isBlank() && st.shareable

    fun key(type: String, target: String): String = "$type:$target"

    /** Wer hat schon Zugriff ("type:target" -> Rolle) */
    fun taken(st: ShareState): Map<String, Role> = st.shares.associate { key(it.type, it.target) to Role.of(it.role) }

    /** Ueber eine Gruppe/Seite darueber mehr Rechte als die direkte Freigabe -> "(über team: Bearbeiten)" */
    fun topVia(e: ShareEntry): ShareVia? {
        if (e.type != "user") return null
        val top = e.via.firstOrNull() ?: return null
        return if (Role.of(top.role).rank > Role.of(e.role).rank) top else null
    }

    /** Gruppe: ihr Name; Seite darueber: „Titel“ */
    fun viaLabel(v: ShareVia): UiText =
        if (v.type == "group") UiText.Raw(v.name) else UiText.Res(R.string.share_quoted, v.title?.ifBlank { null } ?: "…")

    /** Zweite Zeile: Benutzername bzw. "Gruppe · n Personen" */
    fun entrySub(type: String, target: String, members: Int?): UiText = when {
        type != "group" -> UiText.Raw(target)
        members == null -> UiText.Res(R.string.share_group)
        else -> UiText.Plural(R.plurals.share_group_members, members)
    }

    /** Rueckfrage "Freigabe entfernen?": nennt verbleibenden Zugriff ueber Gruppen/Seiten darueber */
    fun removeText(e: ShareEntry): UiText {
        val top = if (e.type == "user") e.via.firstOrNull() else null      // bleibt nach dem Entfernen (hoechste zuerst)
        return when {
            top != null -> UiText.Res(R.string.share_remove_keeps, e.name, viaLabel(top), UiText.Res(roleName(Role.of(top.role))))
            e.type == "group" -> UiText.Res(R.string.share_remove_group, e.name)
            else -> UiText.Res(R.string.share_remove_user, e.name)
        }
    }
}

/**
 * Dialog "Teilen" einer eigenen Seite: Suche ab 3 Zeichen (Personen + eigene Gruppen), Rolle waehlen, Liste der
 * Freigaben mit tatsaechlicher Rolle, Rolle aendern, entfernen; geerbte Freigaben nur lesend.
 */
class ShareModel(val id: Int, private val backend: ShareBackend) {
    var state by mutableStateOf<ShareState?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<UiText?>(null)
        private set
    var query by mutableStateOf("")
        private set
    var newRole by mutableStateOf(Role.VIEW)
    var hits by mutableStateOf<ShareTargets?>(null)
        private set
    /** Fehler/Hinweis am Suchfeld (ersetzt den Hilfetext) */
    var searchError by mutableStateOf<UiText?>(null)
        private set
    /** Rueckmeldung "✓ …" unter der Liste */
    var status by mutableStateOf<UiText?>(null)
        private set
    /** Fehler direkt an einem Eintrag ("type:target" -> Text) */
    var itemErrors by mutableStateOf<Map<String, UiText>>(emptyMap())
        private set
    var busy by mutableStateOf(false)
        private set
    private var seq = 0

    suspend fun load() {
        loading = true
        try {
            state = backend.shares(id)
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = backend.friendly(e)
        } finally {
            loading = false
        }
    }

    fun editQuery(q: String) {
        query = q.take(60)
        searchError = null
        status = null
        if (!ShareLogic.canSearch(query)) hits = null
    }

    /** Personen/Gruppen suchen (die Oberflaeche entprellt) */
    suspend fun search() {
        val q = query.trim()
        if (!ShareLogic.canSearch(q)) {
            hits = null
            return
        }
        val n = ++seq
        try {
            val r = backend.shareTargets(q)
            if (n != seq || query.trim() != q) return
            hits = r
            searchError = if (r.users.isEmpty() && r.groups.isEmpty()) UiText.Res(R.string.share_nobody) else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (n == seq) searchError = backend.friendly(e)
        }
    }

    private fun sync(ns: ShareState) {
        state = ns
        backend.shareChanged(id, ns.shares.isNotEmpty())
    }

    /** Person/Gruppe mit der gewaehlten Rolle hinzufuegen (Treffer oder Gruppen-Chip) */
    suspend fun add(type: String, target: String, name: String) {
        if (busy) return
        busy = true
        val role = newRole
        try {
            sync(backend.putShare(id, type, target, role.key))
            query = ""
            hits = null
            searchError = null
            status = UiText.Res(R.string.share_added, name, UiText.Res(ShareLogic.roleName(role)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = null
            searchError = backend.friendly(e)
        } finally {
            busy = false
        }
    }

    suspend fun changeRole(e: ShareEntry, role: Role) {
        val k = ShareLogic.key(e.type, e.target)
        try {
            sync(backend.putShare(id, e.type, e.target, role.key))
            itemErrors = itemErrors - k
            status = UiText.Res(R.string.share_role_changed)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            itemErrors = itemErrors + (k to backend.friendly(ex))
        }
    }

    suspend fun remove(e: ShareEntry) {
        val k = ShareLogic.key(e.type, e.target)
        try {
            sync(backend.deleteShare(id, e.type, e.target))
            itemErrors = itemErrors - k
            status = UiText.Res(R.string.share_removed)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: ApiException) {
            itemErrors = itemErrors + (k to ex.text)
        } catch (ex: Exception) {
            itemErrors = itemErrors + (k to backend.friendly(ex))
        }
    }
}
