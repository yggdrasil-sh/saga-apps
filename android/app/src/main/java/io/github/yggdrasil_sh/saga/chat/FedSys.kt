package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText

/**
 * Saga 2.3 (E5): Texte der Systemzeilen in Chats ueber Servergrenzen (wie static/chat.js sysText) - KI ein/aus, "ab jetzt
 * mit Personen anderer Server", Mitglieder hinzugefuegt/entfernt/verlassen/getrennt mit Herkunft. Reine Logik (Unit-Tests).
 */
object FedSysText {
    /** Server eines Handles ('~x@host' -> host) */
    fun hostOf(handle: String?): String = handle?.substringAfter('@', "").orEmpty()

    /** Person: Name, bei einem anderen Server als dem eigenen mit " (host)"; acc = Objekt ("dich") statt Name fuer mich */
    fun who(p: SysPerson, myHandle: String?, acc: Boolean): UiText {
        if (acc && !p.handle.isNullOrEmpty() && p.handle == myHandle) return UiText.Res(R.string.fed_sys_you_acc)
        val n = p.name?.trim()?.ifEmpty { null } ?: "?"
        val mine = hostOf(myHandle)
        val host = hostOf(p.handle).ifEmpty { p.host.orEmpty() }       // Herkunft aus dem Handle (nicht aus freiem Text)
        return UiText.Raw(if (host.isNotEmpty() && host != mine) "$n ($host)" else n)
    }

    /** Liste "A, B, C" (Teile duerfen uebersetzt sein, z. B. "dich") */
    fun list(xs: List<UiText>): UiText = xs.drop(1).fold(xs.firstOrNull() ?: UiText.Raw("")) { a, b -> UiText.Res(R.string.fed_list_join, a, b) }

    fun text(k: ChatSys, myHandle: String?): UiText = when (k.type) {
        "ai" -> {
            val who: UiText = if (k.byHandle != null && k.byHandle == myHandle) UiText.Res(R.string.fed_sys_you) else UiText.Raw(k.name?.ifBlank { null } ?: "?")
            UiText.Res(if (k.on) R.string.fed_sys_ai_on else R.string.fed_sys_ai_off, who)
        }
        "fed" -> if (k.aiOff) UiText.Res(R.string.fed_sys_fed_ai_off) else UiText.Res(R.string.fed_sys_fed)
        "members" -> members(k, myHandle)
        // 2.5: Chat auf Stufe 3 umgestellt (ohne Namen); verify: Sicherheitsnummern neu vergleichen
        "crypto" -> if (k.verify) UiText.Res(R.string.x3_sys_crypto_verify) else UiText.Res(R.string.x3_sys_crypto)
        else -> UiText.Res(R.string.fed_sys_note)
    }

    private fun members(k: ChatSys, myHandle: String?): UiText {
        val by = k.byPerson
        val meBy = by != null && !by.handle.isNullOrEmpty() && by.handle == myHandle
        val byName = if (by != null && !meBy) who(by, myHandle, false) else null
        val out = ArrayList<UiText>()
        if (k.added.isNotEmpty()) {
            val names = list(k.added.map { who(it, myHandle, by != null) })
            out.add(
                when {
                    meBy -> UiText.Res(R.string.fed_sys_you_added, names)
                    byName != null -> UiText.Res(R.string.fed_sys_added_by, byName, names)
                    else -> UiText.Res(R.string.fed_sys_joined, list(k.added.map { who(it, myHandle, false) }))
                },
            )
        }
        if (k.removed.isNotEmpty()) {
            val plain = list(k.removed.map { who(it, myHandle, false) })
            val left = by != null && k.removed.size == 1 && !k.removed[0].handle.isNullOrEmpty() && k.removed[0].handle == by.handle
            out.add(
                when {
                    k.reason == "disconnected" -> UiText.Res(R.string.fed_sys_disconnected, plain)
                    k.reason == "gone" -> UiText.Res(R.string.fed_sys_gone, plain)
                    left -> if (meBy) UiText.Res(R.string.fed_sys_you_left) else UiText.Res(R.string.fed_sys_left, plain)
                    meBy -> UiText.Res(R.string.fed_sys_you_removed, list(k.removed.map { who(it, myHandle, true) }))
                    byName != null -> UiText.Res(R.string.fed_sys_removed_by, byName, list(k.removed.map { who(it, myHandle, true) }))
                    else -> UiText.Res(R.string.fed_sys_not_anymore, plain)
                },
            )
        }
        if (out.isEmpty()) return UiText.Res(R.string.fed_sys_note)
        return out.drop(1).fold(out[0]) { a, b -> UiText.Res(R.string.fed_sys_two, a, b) }
    }
}
