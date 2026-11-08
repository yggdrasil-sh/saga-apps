package io.github.yggdrasil_sh.saga.live

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Serializable
data class NotifActor(val name: String = "", val username: String? = null, val avatar: String? = null)

@Serializable
data class NotifNote(val id: Long = 0, val title: String = "", val icon: String = "", val host: String? = null)

@Serializable
data class NotifChat(val id: Long = 0, val kind: String = "")

/** Eine Benachrichtigung (Glocke, Server 1.4) - text ist ein fertiger Satz in der Konto-Sprache */
@Serializable
data class NotifItem(
    val id: Long,
    val kind: String = "",
    val created: Double? = null,
    val read: Boolean = false,
    val actor: NotifActor? = null,
    val count: Int = 1,
    val access: Boolean = true,
    val note: NotifNote? = null,
    val title: String = "",
    val text: String = "",
    val url: String? = null,
    val role: String? = null,
    val excerpt: String? = null,
    @SerialName("comment_id") val commentId: Long? = null,
    @SerialName("thread_id") val threadId: Long? = null,
    @SerialName("block_id") val blockId: String? = null,
    val remote: Boolean = false,
    val chat: NotifChat? = null,
    /** 2.3: Systemmeldung (Backups) - Text und Titel kommen fertig vom Server */
    val system: Boolean = false,
    // Design-Runde 4 (NAV-03): gebuendelte Zeile (?group=1) - alle ids/counts der Einheit (neueste zuerst), Zeit der
    // aeltesten (first), Namen (actors, nur bei mehreren), Erwaehnung dabei, Gespraechseinheit (chat:/call:/page:)
    val ids: List<Long> = emptyList(),
    val counts: List<Int> = emptyList(),
    val first: Double? = null,
    val actors: List<String> = emptyList(),
    val mention: Boolean = false,
    @SerialName("group_key") val groupKey: String? = null,
    val media: String? = null,
)

@Serializable
data class NotifList(
    val items: List<NotifItem> = emptyList(), val unread: Int = 0, val more: Boolean = false, val next: Long? = null,
    /** Design-Runde 4: nur Server mit ?group=1 - ungelesene Zeilen (Gespraechseinheiten); null = alter Server */
    @SerialName("unread_groups") val unreadGroups: Int? = null,
)

@Serializable
data class NotifKind(val kind: String, val on: Boolean = true)

@Serializable
data class NotifSettings(val kinds: List<NotifKind> = emptyList())

/** Ereignis aus dem Live-Kanal fuer offene Ansichten (Seite, Chat ...) */
data class LiveEvent(val type: String, val data: JsonObject) {
    fun str(k: String): String? = (data[k] as? JsonPrimitive)?.contentOrNull
    fun long(k: String): Long? = (data[k] as? JsonPrimitive)?.longOrNull
    fun int(k: String): Int? = (data[k] as? JsonPrimitive)?.intOrNull
    fun bool(k: String): Boolean = (data[k] as? JsonPrimitive)?.booleanOrNull ?: false
    fun obj(k: String): JsonObject? = data[k] as? JsonObject
}

/** Reine Regeln fuer die Benachrichtigungsliste (Tests) */
object NotifLogic {
    /** Neue Benachrichtigung: gebuendelte ersetzt die alte (replaces), neueste oben, keine doppelten IDs */
    fun insert(list: List<NotifItem>, item: NotifItem, replaces: Long?): List<NotifItem> =
        listOf(item) + list.filter { it.id != item.id && it.id != replaces }

    /** Anderswo gelesen: ids oder alle */
    fun markRead(list: List<NotifItem>, ids: Set<Long>?, all: Boolean): List<NotifItem> =
        list.map { if (!it.read && (all || (ids != null && it.id in ids))) it.copy(read = true) else it }

    /** Antippbar nur mit Zugriff; 2.3: Systemmeldungen (Backups) mit Ziel in den Einstellungen bzw. im Admin-Bereich */
    fun tappable(n: NotifItem): Boolean = n.access && (n.note != null || n.chat != null || target(n.url) is Target.Settings || adminLink(n) != null)

    /**
     * 2.3: Admin-Meldung "Server-Backup fehlgeschlagen" verweist in den Admin-Bereich (eigene Adresse, ADMIN_URL) -
     * nur fuer diese Art und nur https; sonst werden absolute Links nie geoeffnet.
     */
    fun adminLink(n: NotifItem): String? = n.url?.takeIf { n.kind == "backup.failed" && Regex("^https://[^\\s\"<>]+$").matches(it) }

    /** Systemmeldung ohne Person/Seite (2.3: backup.failed, userbackup.failed) */
    fun isSystem(n: NotifItem): Boolean = n.system || n.kind == "backup.failed" || n.kind == "userbackup.failed"

    /** Ziel einer Benachrichtigung bzw. Mitteilung: "/notes#12[:block]" oder "/chats#c5" */
    sealed interface Target {
        data class Page(val id: Long, val block: String?) : Target
        data class Chat(val id: Long) : Target
        /** "/?settings=backup" -> Einstellungen -> Mein Backup (2.3) */
        data class Settings(val section: String) : Target
    }

    /** 4.11.: Mitteilung zu einer Chat-Nachricht nicht anzeigen, wenn genau dieser Chat offen und die App vorne ist */
    fun hideWhileOpen(kind: String, url: String?, openChat: Long?): Boolean {
        if (openChat == null || (kind != "chat.message" && kind != "chat.mention")) return false
        return (target(url) as? Target.Chat)?.id == openChat
    }

    fun target(url: String?): Target? {
        if (url == null) return null
        Regex("^/notes#(-?\\d{1,19})(?::([A-Za-z0-9_-]{1,64}))?$").find(url)?.let { m ->
            return m.groupValues[1].toLongOrNull()?.let { Target.Page(it, m.groupValues[2].ifEmpty { null }) }
        }
        Regex("^/chats#c?(\\d{1,19})$").find(url)?.let { m -> return m.groupValues[1].toLongOrNull()?.let { Target.Chat(it) } }
        Regex("^/\\?settings=([a-z]{1,20})$").find(url)?.let { m -> return Target.Settings(m.groupValues[1]) }
        return null
    }
}

/**
 * Live-Kanal und Benachrichtigungen (Server 1.4): Zaehler, Liste, Einstellungen; verteilt Ereignisse an offene Ansichten.
 * Verbindung nur im Vordergrund (foreground()).
 */
class LiveCenter(private val repo: SagaRepository, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    /** Server kennt Benachrichtigungen (GET /api/notifications; 404 = aelter als 1.4) */
    val supported = MutableStateFlow(false)
    val items = MutableStateFlow<List<NotifItem>>(emptyList())
    val unread = MutableStateFlow(0)
    /** Design-Runde 4 (NAV-03): ungelesene Zeilen der gebuendelten Glocke (alter Server: = unread) */
    val unreadGroups = MutableStateFlow(0)
    /** Server buendelt (?group=1 bekannt) */
    @Volatile private var grouped = false
    val more = MutableStateFlow(false)
    val loading = MutableStateFlow(false)
    private var next: Long? = null

    /** Ungelesene Chat-Nachrichten (nicht stummgeschaltete Chats); chatEnabled = Chats auf dem Server an */
    val chatUnread = MutableStateFlow(0)
    val chatEnabled = MutableStateFlow(false)
    val connected = MutableStateFlow(false)

    /**
     * In dieser Anmeldung schon ein notes.tree empfangen? Erst dann reicht mit Live-Kanal der 5-min-Takt
     * (RefreshPolicy) - die Serverversion allein sagt das nicht sicher. Abmelden/Kontowechsel/anderer Server: vergessen.
     */
    @Volatile var treeSeen = false
        private set

    /** Design-Runde 4 (CH-04): Zeilen anderswo gelesen -> System-Mitteilungen dieser Einheiten entfernen (PushService) */
    @Volatile var onRowsRead: ((keys: Set<String>, all: Boolean) -> Unit)? = null

    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveEvent> = _events

    private var foreground = false

    val client = LiveClient(
        baseClient = { repo.api.client },
        url = {
            try {
                repo.api.url("api/events")
            } catch (_: Exception) {
                null
            }
        },
        scope = scope,
        onEvent = { t, d -> handle(t, d) },
        onConnected = {
            if (it != connected.value) io.github.yggdrasil_sh.saga.diag.ClientLogger.conn("sse", if (it) "open" else "closed")
            connected.value = it
        },
    )

    /** App im Vordergrund / Hintergrund (ProcessLifecycleOwner) */
    fun foreground(on: Boolean) {
        foreground = on
        if (on) restart() else client.stop()
    }

    /** Nach Anmeldung/Kontowechsel: neu verbinden und Zaehler laden */
    fun restart() {
        if (!foreground || repo.needsLogin.value || !repo.ready.value) {
            client.stop()
            return
        }
        client.start()
        scope.launch {
            loadNotifications()
            loadChatUnread()
        }
    }

    /** Abmelden/Kontowechsel: alles leeren */
    fun clear() {
        client.stop()
        client.forget()
        items.value = emptyList()
        unread.value = 0
        setGroups(0)
        grouped = false
        more.value = false
        next = null
        supported.value = false
        chatUnread.value = 0
        chatEnabled.value = false
        treeSeen = false
    }

    // ---------------------------------------------------------------- Benachrichtigungen

    suspend fun loadNotifications() {
        if (repo.needsLogin.value) return
        loading.value = true
        try {
            val r = json.decodeFromString<NotifList>(repo.api.get("api/notifications?limit=30&group=1"))
            supported.value = true
            items.value = io.github.yggdrasil_sh.saga.ui.notifications.NotifGroups.regroup(r.items)
            counts(r.unread, r.unreadGroups)
            more.value = r.more
            next = r.next
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404) supported.value = false
        } catch (_: Exception) {
        } finally {
            loading.value = false
        }
    }

    /**
     * Minutentakt: nur die Zahl (limit=0) - die Liste wird nur neu geladen, wenn sich die Zahl geaendert hat (sonst
     * wuerden nachgeladene Seiten der Liste jede Minute verworfen).
     */
    suspend fun loadNotificationCount() {
        if (repo.needsLogin.value) return
        try {
            val o = json.parseToJsonElement(repo.api.get("api/notifications?limit=0&group=1")) as? JsonObject ?: return
            val n = (o["unread"] as? JsonPrimitive)?.intOrNull ?: return
            val g = (o["unread_groups"] as? JsonPrimitive)?.intOrNull
            supported.value = true
            if (n != unread.value || (g != null && g != unreadGroups.value)) loadNotifications()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404) supported.value = false
        } catch (_: Exception) {
        }
    }

    suspend fun loadMore() {
        val before = next ?: return
        val r = json.decodeFromString<NotifList>(repo.api.get("api/notifications?limit=30&group=1&before=$before"))
        // aeltere Mitglieder schon gezeigter Gruppen kommen mit der naechsten Seite: in die vorhandene Zeile (wie Web regroup)
        items.value = io.github.yggdrasil_sh.saga.ui.notifications.NotifGroups.regroup(items.value + r.items)
        counts(r.unread, r.unreadGroups)
        more.value = r.more
        next = r.next
    }

    suspend fun markRead(ids: List<Long>, groups: List<String> = emptyList()) {
        if (ids.isEmpty() && groups.isEmpty()) return
        val m = io.github.yggdrasil_sh.saga.ui.notifications.NotifGroups.markRead(items.value, ids.toSet(), groups.toSet(), false)
        items.value = m.list
        // Design-Runde 4: {groups} markiert die ganze Gespraechseinheit (auch aeltere, nicht geladene); alte Server ignorieren es
        val r = repo.api.post("api/notifications/read", buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
            if (groups.isNotEmpty()) putJsonArray("groups") { groups.take(100).forEach { add(JsonPrimitive(it)) } }
        })
        readCounts(r)
        if (m.partial) loadNotifications()                            // nur ein Teil einer Zeile gelesen: frisch buendeln
    }

    /** Eine (gebuendelte) Zeile gelesen: alle ids und - falls bekannt - die ganze Einheit */
    suspend fun markRead(row: NotifItem) {
        if (row.read) return
        markRead(io.github.yggdrasil_sh.saga.ui.notifications.NotifGroups.idsOf(row), listOfNotNull(row.groupKey))
    }

    suspend fun markAllRead() {
        items.value = io.github.yggdrasil_sh.saga.ui.notifications.NotifGroups.markRead(items.value, emptySet(), emptySet(), true).list
        val r = repo.api.post("api/notifications/read", buildJsonObject { put("all", true) })
        if (!readCounts(r)) counts(0, 0)
    }

    /** Antwort von POST /api/notifications/read -> Zaehler; false = ohne Zahlen */
    private fun readCounts(r: String): Boolean {
        val o = try { json.parseToJsonElement(r) as? JsonObject } catch (_: Exception) { null } ?: return false
        val n = (o["unread"] as? JsonPrimitive)?.intOrNull ?: return false
        counts(n, (o["unread_groups"] as? JsonPrimitive)?.intOrNull)
        return true
    }

    /** Zaehler setzen; ohne unread_groups (alter Server) zaehlt die Glocke einzelne Mitteilungen */
    private fun counts(n: Int, groups: Int?) {
        unread.value = n
        if (groups != null) grouped = true
        setGroups(groups ?: if (grouped) unreadGroups.value.coerceAtMost(n) else n)
    }

    private fun setGroups(n: Int) {
        unreadGroups.value = n
        io.github.yggdrasil_sh.saga.ui.notifications.NotificationCounts.publish(n)
    }

    suspend fun settings(): NotifSettings = json.decodeFromString(repo.api.get("api/notifications/settings"))

    suspend fun setKind(kind: String, on: Boolean): NotifSettings =
        json.decodeFromString(repo.api.request("PUT", "api/notifications/settings", buildJsonObject { put(kind, on) }))

    /** Passende ungelesene Benachrichtigung zu einer angetippten Mitteilung (die traegt bewusst keine id) */
    suspend fun markReadForUrl(url: String) {
        try {
            val r = json.decodeFromString<NotifList>(repo.api.get("api/notifications?unread=1&limit=50"))
            val t = NotifLogic.target(url)
            val ids = r.items.filter { n ->
                n.url == url || (t is NotifLogic.Target.Chat && n.chat?.id == t.id) || (t is NotifLogic.Target.Page && n.note?.id == t.id)
            }.map { it.id }
            markRead(ids)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun loadChatUnread() {
        if (repo.needsLogin.value) return
        try {
            val o = json.parseToJsonElement(repo.api.get("api/chats/unread")) as? JsonObject ?: return
            chatEnabled.value = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false
            chatUnread.value = (o["unread"] as? JsonPrimitive)?.intOrNull ?: 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404) chatEnabled.value = false
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- Ereignisse

    private suspend fun handle(type: String, data: JsonObject) {
        val ev = LiveEvent(type, data)
        when (type) {
            "notification" -> {
                val it = data["item"] as? JsonObject
                ev.int("unread")?.let { n -> counts(n, ev.int("unread_groups")) }
                val g = io.github.yggdrasil_sh.saga.ui.notifications.NotifGroups
                if (it != null) {
                    try {
                        val item = json.decodeFromJsonElement(NotifItem.serializer(), it)
                        // Design-Runde 4: in die Zeile seiner Gespraechseinheit (neuer Kopf), ersetzte vorher heraus
                        items.value = g.insert(items.value, item, ev.long("replaces"))
                        supported.value = true
                    } catch (_: Exception) {
                    }
                } else {
                    val r = data["read"]
                    val all = (r as? JsonPrimitive)?.contentOrNull == "all"
                    val ids = (r as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }?.toSet().orEmpty()
                    val m = g.markRead(items.value, ids, emptySet(), all)
                    items.value = m.list
                    if (m.partial) scope.launch { loadNotifications() }
                    // anderswo gelesen (Web, Chat geoeffnet): System-Mitteilungen dieser Einheiten wegnehmen
                    onRowsRead?.invoke(m.readKeys, all)
                }
            }
            "reset" -> scope.launch {
                repo.dropTreeCursor()                                     // Verpasstes nicht nachholbar: Baum ganz laden
                repo.refresh()
                repo.loadFedShared()
                loadNotifications()
                loadChatUnread()
            }
            io.github.yggdrasil_sh.saga.data.TreeEvents.EVENT -> {        // (2.2) Seiten im Baum anderswo geaendert
                treeSeen = true                                           // Server meldet Baum-Aenderungen: seltener Takt
                val e = io.github.yggdrasil_sh.saga.data.TreeEvents.parse(data)
                repo.onTreeEvent(e.ids, e.ops)
            }
            "shared.changed" -> scope.launch {
                repo.loadShared()
                repo.loadFedShared()
            }
            "comments.changed" -> {
                val nid = ev.long("note_id")
                val n = (ev.obj("counts")?.get("unread") as? JsonPrimitive)?.intOrNull
                if (nid != null && n != null) repo.markUnread(nid, n > 0)
            }
            "chat.message", "chat.read", "chat.deleted", "chat.changed" -> scope.launch { loadChatUnread() }
        }
        _events.tryEmit(ev)
    }
}
