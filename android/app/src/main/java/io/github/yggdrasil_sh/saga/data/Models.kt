package io.github.yggdrasil_sh.saga.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Seite in der Liste (GET /api/notes, Suche) - ohne Inhalt */
@Serializable
data class PageMeta(
    val id: Long,
    val title: String = "",
    val icon: String? = null,
    @SerialName("parent_id") val parentId: Long? = null,
    val kind: String? = null,
    val pinned: Boolean = false,
    val updated: Double? = null,
    val snippet: String? = null,
    val archived: Boolean = false,
    val template: Boolean = false,
    val tags: List<String> = emptyList(),
    val locked: Boolean = false,
    /** Teilen (1.3): eigene Seite ist freigegeben (Personen-Symbol) bzw. fremde Seite */
    val shared: Boolean = false,
    /** neue Kommentare von anderen (Punkt) */
    val unread: Boolean = false,
    /** nur fremde Seiten (GET /api/shared, Suche): Rolle, Eigentuemer, oberste geteilte Seite */
    val role: String? = null,
    val owner: Person? = null,
    val root: Boolean = false,
    /** Foederation (2.0): Seite einer anderen Saga-Instanz (id < 0) */
    val remote: RemoteRef? = null,
) {
    val isDatabase: Boolean get() = kind == "database"
    /** Seite einer anderen Saga-Instanz (negative ID oder Feld remote) */
    val isRemote: Boolean get() = id < 0 || remote != null
    val foreign: Boolean get() = owner != null && !owner.me
}

@Serializable
data class TaskItem(
    val id: Int,
    val text: String = "",
    val done: Boolean = false,
    val due: String? = null,
    val prio: Int? = null,
    @SerialName("repeat") val rep: String? = null,
)

@Serializable
data class TasksResponse(val open: List<TaskItem> = emptyList(), val done: List<TaskItem> = emptyList())

@Serializable
data class CalEvent(
    val summary: String = "",
    val start: String = "",
    val end: String = "",
    val allday: Boolean = false,
    val location: String? = null,
    val description: String? = null,
    val color: String? = null,
    val calendar: String? = null,
    val href: String? = null,
    val etag: String? = null,
    val uid: String? = null,
    val series: Boolean? = null,
    val writable: Boolean? = null,
    val account: Int? = null,
    val cal: String? = null,
) {
    val key: String get() = (href ?: "") + start + summary
}

@Serializable
data class EventsResponse(val events: List<CalEvent> = emptyList())

@Serializable
data class ServiceItem(
    val name: String = "",
    val url: String = "",
    val host: String = "",
    val category: String = "",
    val icon: String = "",
    val own: Boolean = false,
    val key: String = "",
)

@Serializable
data class VersionInfo(
    val name: String? = null,
    val version: String? = null,
    val api: Int? = null,
    @SerialName("min_app") val minApp: String? = null,
)

@Serializable
data class KeyInfo(
    val id: Int,
    val name: String = "",
    val prefix: String = "",
    val created: Double? = null,
    @SerialName("last_used") val lastUsed: Double? = null,
)

@Serializable
data class DeviceInfo(
    val id: Int,
    val name: String = "",
    val created: Double? = null,
    @SerialName("last_used") val lastUsed: Double? = null,
    val key: KeyInfo? = null,
)

@Serializable
data class AppsResponse(val devices: List<DeviceInfo> = emptyList(), val keys: List<KeyInfo> = emptyList())

/** Antwort von /app/exchange und /app/pair */
@Serializable
data class LoginResult(
    val ok: Boolean = false,
    val name: String? = null,
    val server: String? = null,
    @SerialName("device_token") val deviceToken: String? = null,
    @SerialName("app_key") val appKey: String? = null,
)

/** Ausschnitt aus GET /api/account: username ist die stabile Kennung, name der (aenderbare) Anzeigename */
@Serializable
data class AccountInfo(val username: String? = null, val name: String? = null)

@Serializable
data class NewId(val id: Long)

@Serializable
data class SaveResult(val ok: Boolean = false, val rev: Int? = null, val updated: Double? = null)
