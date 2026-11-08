package io.github.yggdrasil_sh.saga.push

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.yggdrasil_sh.saga.MainActivity

/**
 * Design-Runde 4, Nachbesserung (CH-04): ein langlebiger dynamischer Shortcut je Chat, damit Android die Chat-Mitteilungen
 * als Unterhaltung fuehrt (Bereich "Unterhaltungen" ab Android 11, Prioritaet, Bubbles). Nur mit demselben Datenschutz
 * wie die Mitteilung selbst: nur wenn "Namen zeigen" gilt und der Name aus dem lokalen Stand ohne Entschluesselung kommt
 * (nie der Ende-zu-Ende-verschluesselte Titel freier Gruppen). Weg beim Entfernen des Chats (Live "chat.removed"),
 * beim Abmelden/Kontowechsel (PushService.clear) und fuer Chats, die nicht mehr in der Liste stehen (prune).
 */
object ChatShortcuts {
    private const val PREFIX = "chat-"

    /** Kennung je Konto und Chat ("chat-<konto>-<id>"); konto = kurzer Hash aus Server und Konto (Account.tag) */
    fun id(account: String, chat: Long): String = "$PREFIX$account-$chat"

    /** Chat-Kennung aus einer Shortcut-Kennung dieses Kontos, sonst null. Rein (Tests). */
    fun chatOf(account: String, id: String): Long? =
        id.takeIf { it.startsWith("$PREFIX$account-") }?.removePrefix("$PREFIX$account-")?.toLongOrNull()

    /** Shortcut anlegen bzw. erneuern -> Kennung (fuer setShortcutId) oder null */
    fun push(context: Context, account: String, chat: Long, url: String, title: String, person: Person, icon: IconCompat?): String? {
        val id = id(account, chat)
        val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .putExtra(Notifier.EXTRA_URL, url).putExtra(Notifier.EXTRA_SIG, Notifier.sign(context, url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            val s = ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(title)
                .setLongLabel(title)
                .setLongLived(true)
                .setPerson(person)
                .setLocusId(LocusIdCompat(id))
                .setCategories(setOf("android.shortcut.conversation"))      // ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION (API 30)
                .setIntent(open)
                .apply { if (icon != null) setIcon(icon) }
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, s)
            id
        } catch (_: Exception) {                         // Ratenbegrenzung, Launcher ohne Shortcuts: Mitteilung ohne Unterhaltung
            null
        }
    }

    /** Shortcut eines Chats entfernen (Chat geloescht/verlassen) */
    fun remove(context: Context, account: String, chat: Long) = removeIds(context, listOf(id(account, chat)))

    /** Shortcuts zu Chats dieses Kontos, die nicht mehr in [keep] stehen, entfernen */
    fun prune(context: Context, account: String, keep: Set<Long>) {
        val gone = mine(context).filter { s -> chatOf(account, s)?.let { it !in keep } == true }
        if (gone.isNotEmpty()) removeIds(context, gone)
    }

    /** Alle Chat-Shortcuts entfernen (Abmelden, Kontowechsel) */
    fun removeAll(context: Context) {
        val ids = mine(context)
        if (ids.isNotEmpty()) removeIds(context, ids)
    }

    private fun mine(context: Context): List<String> = try {
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED)
            .map { it.id }.filter { it.startsWith(PREFIX) }.distinct()
    } catch (_: Exception) {
        emptyList()
    }

    private fun removeIds(context: Context, ids: List<String>) {
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(context, ids)
            ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
        } catch (_: Exception) {
        }
    }
}

/** Kurze Kennung des angemeldeten Kontos fuer Mitteilungs-Extras und Shortcuts (kein Geheimnis: Hash aus Server und Konto) */
object Account {
    fun tag(server: String, account: String): String {
        if (server.isEmpty() && account.isEmpty()) return ""
        val d = java.security.MessageDigest.getInstance("SHA-256").digest("$server|$account".toByteArray(Charsets.UTF_8))
        return d.take(6).joinToString("") { "%02x".format(it) }
    }
}
