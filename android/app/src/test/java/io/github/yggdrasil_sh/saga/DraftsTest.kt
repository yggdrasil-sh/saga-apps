package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.AccountDrafts
import io.github.yggdrasil_sh.saga.data.AccountKey
import io.github.yggdrasil_sh.saga.data.Draft
import io.github.yggdrasil_sh.saga.data.FileDrafts
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N3: Entwuerfe je stabiler Konto-Kennung; alte Ordner (je Kontoname) werden vollstaendig uebernommen */
class DraftsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val s = "https://s.example.org"
    private fun draft(id: Long, text: String, stamp: Long) = Draft(id, "T$id", """{"blocks":[{"id":"a","type":"paragraph","data":{"text":"$text"}}]}""", 1, stamp)

    @Test
    fun folderDependsOnServerAndUsernameNotOnDisplayName() {
        val root = tmp.root
        val k = AccountKey.uid("mia")
        assertEquals(FileDrafts.dirFor(root, s, k, ""), FileDrafts.dirFor(root, s, k, "Mia Muster"))
        assertNotEquals(FileDrafts.dirFor(root, s, k, ""), FileDrafts.dirFor(root, s, AccountKey.uid("max"), ""))
        assertNotEquals(FileDrafts.dirFor(root, s, k, ""), FileDrafts.dirFor(root, "https://b.example.org", k, ""))
        // keine Adressen oder Namen im Dateinamen
        assertFalse(FileDrafts.dirFor(root, s, k, "").name.contains("mia"))
    }

    @Test
    fun migrationMovesEverythingAndLeavesNoOrphans() {
        val root = tmp.root
        val empty = FileDrafts(FileDrafts.legacyDir(root, s, ""))          // aelterer Server: kein Name
        val named = FileDrafts(FileDrafts.legacyDir(root, s, "Mia"))       // spaeter mit Namen
        empty.write(draft(1, "ohne Namen", 10))
        named.write(draft(2, "mit Namen", 20))
        empty.write(draft(3, "alt", 5))
        named.write(draft(3, "neuer", 30))                                   // gleiche Seite: der neuere gewinnt

        val key = AccountKey.legacy("Mia")                                   // beim Update eingefroren
        val handle = AccountDrafts(FileDrafts.dirFor(root, s, key, "Mia"))
        val target = FileDrafts.dirFor(root, s, AccountKey.uid("mia"), "")
        val names = AccountKey.migrationNames(key, listOf("Mia", "Mia Muster", "mia"))
        handle.moveTo(target, names.map { FileDrafts.legacyDir(root, s, it) })

        assertEquals(target, handle.dir)
        assertEquals(listOf(1L, 2L, 3L), handle.all().map { it.id })
        assertTrue(handle.read(3)!!.body!!.contains("neuer"))
        assertFalse("alter Ordner weg", FileDrafts.legacyDir(root, s, "").exists())
        assertFalse("alter Ordner weg", FileDrafts.legacyDir(root, s, "Mia").exists())
        // offene Editoren halten dasselbe Objekt und schreiben jetzt in den neuen Ordner
        handle.write(draft(4, "danach", 40))
        assertEquals(listOf(1L, 2L, 3L, 4L), FileDrafts(target).all().map { it.id })
        assertTrue(handle.covers(FileDrafts.legacyDir(root, s, "Mia")))
    }

    @Test
    fun freshSignInOnlyTakesItsOwnFrozenName() {
        // Beim Anmelden eingefrorener Name: fremde Ordner (z. B. "" eines frueheren Kontos) bleiben unberuehrt
        assertEquals(setOf("neu"), AccountKey.migrationNames(AccountKey.name("neu"), listOf("x", "y")))
        assertEquals(setOf("Mia", "mia", ""), AccountKey.migrationNames(AccountKey.legacy("Mia"), listOf("Mia", "mia", null)))
    }

    @Test
    fun draftMatchesServerContentRegardlessOfTimestamp() {
        val d = Draft(1, "Titel", """{"time":1,"blocks":[{"id":"a","type":"paragraph","data":{"text":"Hallo"}}]}""", 1, 0)
        fun page(title: String, text: String) = buildJsonObject {
            put("title", title)
            put("format", "blocks")
            put("rev", 7)
            put("body", """{"time":99,"version":"2.30","blocks":[{"id":"a","type":"paragraph","data":{"text":"$text"}}]}""")
        }
        assertTrue(d.matchesPage(page("Titel", "Hallo")))
        assertFalse(d.matchesPage(page("Titel", "Anders")))
        assertFalse(d.matchesPage(page("Anderer Titel", "Hallo")))
        // leere Seite == nur der leere Start-Absatz der App
        val empty = Draft(2, "", """{"blocks":[{"id":"x","type":"paragraph","data":{"text":""}}]}""", 1, 0)
        assertTrue(empty.matchesPage(buildJsonObject { put("title", ""); put("format", "blocks"); put("body", "") }))
    }
}
