package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Saga 2.5 "Chat-Standard" (docs/APP-API-CHAT.md 6a, 8, 9) - reine Logik fuer Unit-Tests: welche Funktion der Server
 * kann (features in /api/version), Rechte aus can_*, Stummschalten, Erwaehnungen, Linkvorschau, Systemzeile "perm".
 * Ohne das jeweilige Feature bleibt alles wie bisher.
 */
object ChatStd {
    /** Funktionen nach Feature (Server meldet sie in /api/version -> features) */
    data class Features(
        val pageMembers: Boolean = false,
        val groupInfo2: Boolean = false,
        val modDel: Boolean = false,
        val msgPin: Boolean = false,
        val block: Boolean = false,
        val report: Boolean = false,
        val mute2: Boolean = false,
        val chatPin: Boolean = false,
        val markUnread: Boolean = false,
        val hide: Boolean = false,
        val mention: Boolean = false,
        val fmt: Boolean = false,
        val unfurl: Boolean = false,
        val media: Boolean = false,
    ) {
        companion object {
            fun of(f: Set<String>) = Features(
                pageMembers = "pagechatmembers1" in f, groupInfo2 = "groupinfo2" in f, modDel = "moddel1" in f,
                msgPin = "msgpin1" in f, block = "block2" in f, report = "report1" in f, mute2 = "mute2" in f,
                chatPin = "chatpin1" in f, markUnread = "markunread1" in f, hide = "chathide1" in f,
                mention = "chatmention1" in f, fmt = "chatfmt1" in f, unfurl = "unfurl1" in f, media = "chatmedia1" in f,
            )
        }
    }

    // ------------------------------------------------------------------ Rechte (Paket B, 6a)

    data class Rights(
        /** Eingabefeld zeigen (false: Hinweis "Nur Admins…" bzw. gesperrte Person) */
        val send: Boolean,
        /** Bild + Beschreibung aendern (can_info2) */
        val info2: Boolean,
        /** Rechte-Schalter bedienen */
        val perms: Boolean,
        /** Rechte-Bereich zeigen (Admin oder eingeschraenkte Gruppe) */
        val showPerms: Boolean,
        val pin: Boolean,
        val moderate: Boolean,
    )

    fun rights(c: ChatSummary, f: Features): Rights {
        val free = c.kind == "free"
        val gi = f.groupInfo2
        return Rights(
            send = c.canSend != false,
            info2 = gi && free && c.canInfo2 == true && !c.readonly,
            perms = gi && free && c.canPerms == true,
            showPerms = gi && free && (c.canPerms == true || c.permInfo == "admins" || c.permSend == "admins"),
            pin = f.msgPin && c.canPin == true,
            moderate = f.modDel && c.canModerate == true,
        )
    }

    /** Warum das Eingabefeld fehlt: null = darf schreiben */
    fun sendBlockedText(c: ChatSummary): UiText? = when {
        c.canSend != false -> null
        c.blocked -> UiText.Res(R.string.std_send_blocked_person)
        else -> UiText.Res(R.string.std_send_admins_only)
    }

    // ------------------------------------------------------------------ Seiten-Chat-Mitglieder (6a)

    /** "Mitglied hinzufuegen" nach Recht statt nach Chat-Art (Seiten-Chat nur mit pagechatmembers1) */
    fun canAdd(c: ChatSummary, f: Features, groupInfo: GroupInfo.Rights): Boolean = when (c.kind) {
        "free" -> groupInfo.manage
        "page" -> f.pageMembers && c.canManage == true
        else -> false
    }

    /** Entfernen im Seiten-Chat: erlaubt (removable) bzw. Grund, warum nicht (Text wie im Web) */
    sealed interface PageRemove {
        data object None : PageRemove
        data object Ok : PageRemove
        data class No(val why: UiText) : PageRemove
    }

    fun pageRemove(c: ChatSummary, m: ChatPerson, f: Features): PageRemove {
        if (c.kind != "page" || !f.pageMembers || c.canManage != true || m.me) return PageRemove.None
        if (m.removable == true) return PageRemove.Ok
        return PageRemove.No(
            when (m.via) {
                "group" -> UiText.Res(R.string.std_via_group)
                "parent" -> UiText.Res(R.string.std_via_parent)
                "owner" -> UiText.Res(R.string.std_via_owner)
                else -> UiText.Res(R.string.std_via_other)
            },
        )
    }

    /** Hinweis in der Gruppeninfo (page/group) */
    fun membersHint(c: ChatSummary, f: Features): UiText? = when {
        c.kind == "group" -> UiText.Res(R.string.std_hint_group)
        c.kind == "page" && f.pageMembers -> UiText.Res(if (c.canManage == true) R.string.std_hint_page_owner else R.string.std_hint_page_other)
        else -> null
    }

    val PAGE_ROLES = listOf("view", "comment", "edit")

    fun addPageBody(ref: String, remote: Boolean, role: String): JsonObject = buildJsonObject {
        put(if (remote) "handle" else "user", ref)
        put("role", role.takeIf { it in PAGE_ROLES } ?: "view")
    }

    // ------------------------------------------------------------------ Gruppenbild / Beschreibung (8a)

    const val DESC_MAX = 500
    const val AVATAR_PX = 256

    fun descContent(desc: String): String = buildJsonObject {
        put("v", 1); put("type", "desc"); put("desc", desc.trim().take(DESC_MAX))
    }.toString()

    fun avatarContent(f: ChatFile?): String = buildJsonObject {
        put("v", 1); put("type", "avatar")
        if (f == null) put("file", kotlinx.serialization.json.JsonNull)
        else putJsonObject("file") {
            put("id", f.id); put("k", f.k); put("m", f.m); put("n", f.n); put("s", f.s)
            f.w?.let { put("w", it) }
            f.h?.let { put("h", it) }
        }
    }.toString()

    /** quadratischer Ausschnitt (Mitte) eines Bildes: (x, y, Kante) */
    fun squareCrop(w: Int, h: Int): Triple<Int, Int, Int> {
        val s = minOf(w, h)
        return Triple((w - s) / 2, (h - s) / 2, s)
    }

    // ------------------------------------------------------------------ Anheften von Nachrichten (8d)

    const val PINS_MAX = 3

    /** Leiste oben: neueste zuerst, i-te (umlaufend) */
    fun pinAt(pins: List<String>, i: Int): String? = if (pins.isEmpty()) null else pins[pins.size - 1 - (i.coerceAtLeast(0) % pins.size)]

    /** Nachricht faellt heraus (geloescht, Grabstein) */
    fun dropPin(pins: List<String>, mid: String): List<String> = pins.filter { it != mid }

    // ------------------------------------------------------------------ Sperren / Melden (8e, 8f)

    /** Handle der Person (nie der Anmeldename): ~x@host bzw. Such-Handle */
    fun blockTarget(m: ChatPerson?): String? = m?.handle?.ifEmpty { null } ?: m?.ref?.takeIf { it.startsWith("~") }

    /** Absender einer Nachricht in den Mitgliedern finden (v1: username, v2: Handle) */
    fun senderOf(c: ChatSummary?, sender: ChatPerson?, senderHandle: String?): ChatPerson? {
        if (c == null) return null
        val u = sender?.username
        return c.members.firstOrNull { x -> (u != null && (x.username == u || x.handle == u)) || (!senderHandle.isNullOrEmpty() && x.handle == senderHandle) }
    }

    /** Nachricht einer gesperrten Person in einer Gruppe (einklappen) */
    fun blockedInGroup(c: ChatSummary?, sender: ChatPerson?, senderHandle: String?): Boolean =
        c != null && c.kind != "dm" && senderOf(c, sender, senderHandle)?.blocked == true

    val REPORT_REASONS = listOf("spam", "abuse", "illegal", "other")
    const val REPORT_TEXT_MAX = 2000
    const val REPORT_MSGS_MAX = 20

    fun reportBody(cid: Long, mids: List<String>, reason: String, text: String?): JsonObject = buildJsonObject {
        put("chat", cid)
        put("msgs", buildJsonArray { mids.distinct().take(REPORT_MSGS_MAX).forEach { add(JsonPrimitive(it)) } })
        put("reason", reason.takeIf { it in REPORT_REASONS } ?: "other")
        put("text", text?.trim()?.take(REPORT_TEXT_MAX).orEmpty())
    }

    /** Vorbelegung des mitgeschickten Texts: "Name: Text" je Nachricht */
    fun reportPrefill(lines: List<Pair<String, String>>): String =
        lines.joinToString("\n") { (n, t) -> (if (n.isNotEmpty()) "$n: " else "") + t }.take(REPORT_TEXT_MAX)

    // ------------------------------------------------------------------ Systemzeile "perm" (8b)

    fun permSysText(k: ChatSys, myHandle: String?): UiText {
        val by = k.byPerson?.takeIf { !it.handle.isNullOrEmpty() || !it.name.isNullOrEmpty() }
        val meBy = by != null && !by.handle.isNullOrEmpty() && by.handle == myHandle
        val head = when {
            meBy -> UiText.Res(R.string.std_sys_perm_you)
            by != null -> UiText.Res(R.string.std_sys_perm_by, FedSysText.who(by, myHandle, false))
            else -> UiText.Res(R.string.std_sys_perm_admin)
        }
        val parts = ArrayList<UiText>()
        when (k.permSend) {
            "admins" -> parts.add(UiText.Res(R.string.std_sys_send_admins))
            "all" -> parts.add(UiText.Res(R.string.std_sys_send_all))
        }
        when (k.permInfo) {
            "admins" -> parts.add(UiText.Res(R.string.std_sys_info_admins))
            "all" -> parts.add(UiText.Res(R.string.std_sys_info_all))
        }
        return parts.fold(head) { a, b -> UiText.Res(R.string.std_join_sentences, a, b) }
    }

    // ------------------------------------------------------------------ Stummschalten (9.2)

    /** Dauer im Menue -> muted_until (Unix-Sekunden, -1 = immer, 0 = aus) */
    enum class MuteFor(val seconds: Long) { HOUR(3600), HOURS8(8 * 3600), WEEK(7 * 86400), ALWAYS(-1), OFF(0) }

    fun mutedUntil(d: MuteFor, nowSec: Long): Long = when (d) {
        MuteFor.ALWAYS -> -1
        MuteFor.OFF -> 0
        else -> nowSec + d.seconds
    }

    /** jetzt stumm? (muted_until aktiv; aelterer Server: muted) */
    fun mutedNow(c: ChatSummary, nowSec: Double): Boolean {
        val u = c.mutedUntil ?: return c.muted
        return u == -1.0 || u > nowSec
    }

    /** Anzeige: Immer / bis Zeitpunkt / null (nicht stumm) */
    sealed interface MuteState {
        data object Off : MuteState
        data object Always : MuteState
        data class Until(val epochSec: Long) : MuteState
    }

    fun muteState(c: ChatSummary, nowSec: Double): MuteState {
        if (!mutedNow(c, nowSec)) return MuteState.Off
        val u = c.mutedUntil
        return if (u == null || u < 0) MuteState.Always else MuteState.Until(u.toLong())
    }

    val NOTIFY = listOf("all", "mentions", "none")

    // ------------------------------------------------------------------ Chatliste (9.1)

    /** angeheftete oben (nach pinned_at), dann neueste zuerst - wie der Server; stabil fuer lokale Aenderungen */
    fun sortList(cs: List<ChatSummary>): List<ChatSummary> =
        cs.sortedWith(compareByDescending<ChatSummary> { it.pinned }.thenBy { if (it.pinned) it.pinnedAt ?: 0.0 else 0.0 }.thenByDescending { it.lastAt ?: 0.0 })

    const val CHAT_PINS_MAX = 5

    /** Punkt "ungelesen" (markiert) ohne Zahl */
    fun unreadDot(c: ChatSummary): Boolean = c.unreadMark

    // ------------------------------------------------------------------ Erwaehnungen (9.3)

    /** Token <@kennung>: Such-Handle ~x (16 Zeichen base32) optional mit @host - wie TOKEN in static/chat.js */
    val MENTION = Regex("""<@(~[a-z2-7]{16}(?:@[a-z0-9.:\-]{1,260})?)>""")
    const val MENTIONS_MAX = 50

    /** Kennung eines Mitglieds in Erwaehnungen: handle (Stufe 3/foederiert) bzw. ref (v1) */
    fun memberRef(m: ChatPerson): String = m.handle?.ifEmpty { null } ?: m.ref.orEmpty()

    /** Auswahl bei "@abc": Mitglieder ohne mich, Anzeigename enthaelt abc (hoechstens 6) */
    fun mentionCandidates(c: ChatSummary?, q: String): List<ChatPerson> {
        if (c == null) return emptyList()
        val ql = q.lowercase()
        return c.members.filter { !it.me && memberRef(it).isNotEmpty() && it.name.lowercase().contains(ql) }.take(6)
    }

    /** Name zu einer Kennung (ich -> eigener Name); null = unbekannt */
    fun mentionName(c: ChatSummary?, ref: String, myName: String?): String? {
        if (c == null) return null
        if (ref == c.meRef || ref == c.myHandle) return myName ?: c.members.firstOrNull { it.me }?.name
        return c.members.firstOrNull { x -> x.handle == ref || x.ref == ref || (x.handle != null && x.handle.substringBefore('@') == ref) }?.name
    }

    fun isMe(c: ChatSummary?, ref: String): Boolean = c != null && (ref == c.meRef || (c.myHandle != null && ref == c.myHandle))

    /** Nachricht erwaehnt mich (hervorheben) */
    fun mentionsMe(c: ChatSummary?, text: String?): Boolean {
        if (c == null || text.isNullOrEmpty()) return false
        return (c.meRef != null && text.contains("<@" + c.meRef + ">")) || (c.myHandle != null && text.contains("<@" + c.myHandle + ">"))
    }

    /** Text fuer Vorschau/Kopieren: Token -> @Name bzw. @Unbekannt */
    fun plainMentions(text: String, c: ChatSummary?, myName: String?, unknown: String): String =
        MENTION.replace(text) { "@" + (mentionName(c, it.groupValues[1], myName) ?: unknown) }

    /**
     * Beim Senden: gewaehlte Labels "@Name" -> <@kennung> (laengste zuerst, wie applyPeople im Web); Kennungen fuer
     * `mentions` (hoechstens 50, keine doppelt).
     */
    fun applyPeople(text: String, people: Map<String, String>): Pair<String, List<String>> {
        var t = text
        val refs = ArrayList<String>()
        for (label in people.keys.sortedByDescending { it.length }) {
            if (!t.contains(label)) continue
            val r = people.getValue(label)
            t = t.replace(label, "<@$r>")
            if (r !in refs) refs.add(r)
        }
        return t to refs.take(MENTIONS_MAX)
    }

    /** "@abc" vor dem Cursor (Wortanfang) -> (Start von '@', Suchtext); wie picker() im Web */
    fun atQuery(text: String, cursor: Int): Pair<Int, String>? {
        val v = text.substring(0, cursor.coerceIn(0, text.length))
        val m = Regex("""(^|\s)@([^\s@]{0,30})$""").find(v) ?: return null
        return (v.length - m.groupValues[2].length - 1) to m.groupValues[2]
    }

    fun mentionLabel(name: String): String = "@" + name.replace(Regex("\\s+"), " ").take(60)

    /** Sendekoerper: mentions neben dem Chiffrat (nur wenn welche) */
    fun mentionsJson(refs: List<String>): JsonArray? = if (refs.isEmpty()) null else buildJsonArray { refs.take(MENTIONS_MAX).forEach { add(JsonPrimitive(it)) } }

    // ------------------------------------------------------------------ Linkvorschau (9.5)

    /** erste http(s)-Adresse im Text - wie firstUrl() im Web */
    private val FIRST_URL = Regex("""(https?://[^\s<>"'`]+[^\s<>"'`.,;:!?)\]])""")

    fun firstUrl(text: String?): String? = text?.let { FIRST_URL.find(it)?.groupValues?.get(1) }

    /** brauchbare Vorschau? (Titel oder Beschreibung) */
    fun usable(u: UnfurlResult?): Boolean = u != null && (u.title.isNotBlank() || u.desc.isNotBlank())

    /** Vorschau fuer den Inhalt (ohne Bild; das kommt verschluesselt dazu) */
    fun previewOf(u: UnfurlResult, img: ChatThumb?): ChatPreview = ChatPreview(
        url = u.url.take(2000), title = u.title.take(200), desc = u.desc.take(300), site = u.site.take(80), img = img,
    )

    /** Empfaenger: nur http(s)-Karten zeigen (sonst nichts) */
    fun showPreview(p: ChatPreview?): Boolean = p != null && (p.url.startsWith("https://") || p.url.startsWith("http://")) && (p.title.isNotBlank() || p.desc.isNotBlank())

    fun previewJson(p: ChatPreview): JsonObject = buildJsonObject {
        put("url", p.url); put("title", p.title); put("desc", p.desc); put("site", p.site)
        p.img?.let { i -> putJsonObject("img") { put("id", i.id); put("k", i.k); put("m", i.m); put("w", i.w); put("h", i.h); put("s", i.s) } }
    }

    /** alle Datei-Kennungen einer Nachricht (Anhaenge + Vorschaubilder + Vorschau-Bild) fuer `files` */
    fun fileIds(files: List<ChatFile>, preview: ChatPreview?): List<String> =
        ChatLogic.sendFileIds(files) + listOfNotNull(preview?.img?.id?.takeIf { it.isNotEmpty() })

    // ------------------------------------------------------------------ Medienuebersicht (9.6)

    enum class MediaTab { MEDIA, FILES, LINKS }

    private val LINKS = Regex("""(https?://[^\s<>"'`]+[^\s<>"'`.,;:!?)\]])""")

    fun linksIn(text: String?): List<String> = text?.let { t -> LINKS.findAll(t).map { it.groupValues[1] }.toList() }.orEmpty()

    fun isMedia(f: ChatFile): Boolean = (f.isImage || f.isVideo) && !f.isVoice

    fun inTab(f: ChatFile, tab: MediaTab): Boolean = when (tab) {
        MediaTab.MEDIA -> isMedia(f)
        MediaTab.FILES -> !isMedia(f) && f.voice != true
        MediaTab.LINKS -> false
    }
}
