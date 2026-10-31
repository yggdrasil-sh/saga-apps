package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText

/**
 * Saga 2.5: Gruppeninfo und Admins freier Gruppen (Feature `chatadm1`, docs/APP-API-CHAT.md Abschnitt 6) - reine Logik
 * fuer Unit-Tests: Rechte aus can_*, Sortierung, Handle-Zeile, Systemzeile type 'admin'. Ohne `chatadm1` gilt das
 * bisherige Verhalten (Admin = role "admin" in einer freien Gruppe, keine Admin-Vergabe).
 */
object GroupInfo {
    /** Rechte im Chat (aus can_*; aeltere Server: aus role) */
    data class Rights(val manage: Boolean, val admin: Boolean, val rename: Boolean, val readOnly: Boolean)

    fun rights(c: ChatSummary, feature: Boolean): Rights {
        val free = c.kind == "free"
        val ro = c.kind == "group" || c.kind == "page"
        if (!free) return Rights(manage = false, admin = false, rename = false, readOnly = ro)
        if (!feature || c.canManage == null) {
            // aelterer Server: wie bisher - Admin verwaltet, jedes Mitglied darf umbenennen, keine Admin-Vergabe
            val adm = c.role == "admin"
            return Rights(manage = adm, admin = false, rename = c.canRename ?: true, readOnly = false)
        }
        return Rights(manage = c.canManage == true, admin = c.canAdmin == true, rename = c.canRename == true, readOnly = false)
    }

    /** Gruppeninfo statt "Mitglieder": nur fuer Gruppen (Direkt-Chats behalten den bisherigen Dialog) */
    fun usesGroupInfo(c: ChatSummary?, feature: Boolean): Boolean = feature && c != null && c.kind != "dm"

    /** Aktionen je Mitglied (Menue) */
    enum class Act { SAFETY, MAKE_ADMIN, DROP_ADMIN, REMOVE }

    fun actions(c: ChatSummary, m: ChatPerson, r: Rights): List<Act> {
        val out = ArrayList<Act>()
        if (!m.me) out.add(Act.SAFETY)
        if (c.kind == "free" && r.admin && (!m.me || m.admin)) out.add(if (m.admin) Act.DROP_ADMIN else Act.MAKE_ADMIN)
        if (c.kind == "free" && r.manage && ChatLogic.canRemoveMember(m)) out.add(Act.REMOVE)
        return out
    }

    /** Reihenfolge wie im Web: ich zuerst, dann Admins, dann nach Namen */
    fun sorted(ms: List<ChatPerson>): List<ChatPerson> =
        ms.sortedWith(compareByDescending<ChatPerson> { it.me }.thenByDescending { it.admin }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /** Handle '~x@host' bzw. '~x' (ref, Chats v1) - nie der Anmeldename */
    fun handle(m: ChatPerson): String {
        val h = m.handle?.ifEmpty { null } ?: m.ref?.takeIf { it.startsWith("~") }.orEmpty()
        return h.removePrefix("@")
    }

    /** Unterzeile: Handle (beim eigenen Server ohne @host), sonst sub; Personen anderer Server "auf host" (in [onHost]) */
    fun subline(m: ChatPerson, myHandle: String?, remoteChat: Boolean, onHost: (String) -> String): String {
        val mine = myHandle?.substringAfter('@', "").orEmpty()
        val hh = handle(m)
        if (!m.handle.isNullOrEmpty() && !m.host.isNullOrEmpty() && m.host != mine && remoteChat) {
            return onHost(m.host) + (if (hh.isNotEmpty()) " · " + hh.substringBefore('@') else "")
        }
        if (hh.isNotEmpty()) return if (hh.contains('@') && hh.substringAfter('@') == mine) hh.substringBefore('@') else hh
        return m.sub.orEmpty()
    }

    /** `who` fuer PATCH/DELETE …/members/{who}: members[].username (Handle bzw. Benutzername), URL-kodiert ('~', '@' bleiben) */
    fun whoPath(m: ChatPerson): String = java.net.URLEncoder.encode(m.username, "UTF-8").replace("%7E", "~").replace("%40", "@").replace("+", "%20")

    /** Systemzeile {"type":"admin","target","on","by"} (wie static/chat.js sysText) */
    fun sysText(k: ChatSys, myHandle: String?): UiText {
        val by = k.byPerson?.takeIf { !it.handle.isNullOrEmpty() || !it.name.isNullOrEmpty() }
        val tgt = k.target ?: SysPerson()
        val meBy = by != null && !by.handle.isNullOrEmpty() && by.handle == myHandle
        val self = by != null && !by.handle.isNullOrEmpty() && by.handle == tgt.handle
        val byName = if (by != null && !meBy) FedSysText.who(by, myHandle, false) else null
        if (self) return when {
            k.on && meBy -> UiText.Res(R.string.gi_sys_you_now_admin)
            k.on -> UiText.Res(R.string.gi_sys_now_admin, byName!!)
            meBy -> UiText.Res(R.string.gi_sys_you_no_admin)
            else -> UiText.Res(R.string.gi_sys_no_admin, byName!!)
        }
        val tg = FedSysText.who(tgt, myHandle, true)
        return if (k.on) when {
            meBy -> UiText.Res(R.string.gi_sys_you_made_admin, tg)
            byName != null -> UiText.Res(R.string.gi_sys_made_admin, byName, tg)
            else -> UiText.Res(R.string.gi_sys_now_admin, FedSysText.who(tgt, myHandle, false))
        } else when {
            meBy -> UiText.Res(R.string.gi_sys_you_dropped_admin, tg)
            byName != null -> UiText.Res(R.string.gi_sys_dropped_admin, byName, tg)
            else -> UiText.Res(R.string.gi_sys_no_admin, FedSysText.who(tgt, myHandle, false))
        }
    }
}

/**
 * Saga 2.5: Kontakte (Feature `contacts1`, Abschnitt 7) - reine Logik: Kennung beim Auswaehlen, Filter fuer "Mitglied
 * hinzufuegen", gone (ausgegraut, nicht waehlbar), Live-Ereignis chat.contacts.
 */
object Contacts {
    const val LIMIT = 30

    /** Sortierung "zuletzt zuerst" (Server sortiert schon; hier nur zur Sicherheit, stabil) */
    fun sorted(ks: List<ChatContact>): List<ChatContact> = ks.sortedByDescending { it.lastAt }

    /** waehlbar? gone = nicht anschreibbar */
    fun pickable(k: ChatContact): Boolean = !k.gone && k.ref.isNotEmpty()

    /** Auswahl: lokal {user: ref} (POST /api/chats bzw. members[]), fremd {handle: ref} (handles[]) */
    sealed interface Pick {
        data class Local(val user: String) : Pick
        data class Remote(val handle: String) : Pick
    }

    fun pick(k: ChatContact): Pick = if (k.remote) Pick.Remote(k.ref) else Pick.Local(k.ref)

    /** als Person fuer die vorhandenen Auswahl-Listen (lokal: username = Such-Handle) */
    fun asPerson(k: ChatContact): ChatPerson = ChatPerson(username = k.ref, name = k.name, ready = k.ready, avatar = k.avatar, sub = k.sub, ref = k.ref, contact = true)

    fun asFedPerson(k: ChatContact): FedPerson {
        val host = k.host ?: k.sub
        return FedPerson(name = k.name, host = host, label = "", handle = k.ref, avatar = k.avatar, contact = true)
    }

    /** "Mitglied hinzufuegen": ohne die schon dabei sind; fremde nur in Gruppen ueber Servergrenzen (Format v2/v3) */
    fun forAdd(ks: List<ChatContact>, c: ChatSummary): List<ChatContact> = ks.filter { k ->
        c.members.none { m -> m.ref == k.ref || m.handle == k.ref || m.username == k.ref } && (!k.remote || c.isFed)
    }

    /** Unterzeile wie im Web (contactItem): fremd "Person auf host", gone mit Grund, lokal sub + "noch nicht eingerichtet" */
    fun subline(k: ChatContact): UiText? {
        val host = k.host ?: k.sub
        if (k.gone) return if (k.remote) UiText.Res(R.string.ct_server_gone, host) else UiText.Res(R.string.ct_account_gone)
        if (k.remote) return UiText.Res(R.string.ct_person_on, host)
        val sub = k.sub.takeIf { it.isNotBlank() && !ChatLogic.opaque(it) }
        return when {
            sub != null && !k.ready -> UiText.Res(R.string.ct_sub_not_ready, sub)
            sub != null -> UiText.Raw(sub)
            !k.ready -> UiText.Res(R.string.ct_not_ready)
            else -> null
        }
    }

    /** Pfad fuer DELETE /api/chat/contacts/{ref} (URL-kodiert; '~' und '@' duerfen bleiben) */
    fun deletePath(ref: String): String =
        "api/chat/contacts/" + java.net.URLEncoder.encode(ref, "UTF-8").replace("%7E", "~").replace("%40", "@").replace("+", "%20")

    /** Live-Ereignis chat.contacts {ref, hidden:true}: Eintrag lokal entfernen (danach ohnehin neu laden) */
    fun applyHidden(ks: List<ChatContact>, ref: String?, hidden: Boolean): List<ChatContact> =
        if (hidden && !ref.isNullOrEmpty()) ks.filter { it.ref != ref } else ks
}
