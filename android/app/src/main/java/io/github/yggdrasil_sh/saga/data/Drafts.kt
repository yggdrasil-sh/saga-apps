package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Noch nicht gespeicherte Fassung einer Seite (Titel + Inhalt als Block-JSON) und die Revision, auf der sie beruht */
@Serializable
data class Draft(val id: Int, val title: String, val body: String?, val rev: Int?, val stamp: Long) {
    /**
     * Hat der Server (Seite aus GET api/notes/<id>) inhaltlich schon genau diesen Stand? Dann ist der Entwurf
     * gespeichert und erledigt - egal, ob die Revision inzwischen hoeher ist (Prozess vor dem Loeschen beendet).
     */
    fun matchesPage(page: JsonObject): Boolean {
        if (title != BlockDoc.str(page, "title")) return false
        val mine = body ?: return true                              // Datenbank: nur der Titel
        val el = page["body"] as? JsonPrimitive
        val theirsRaw = if (el != null && el.isString) el.content else null
        return try {
            val theirs = BlockDoc.parse(theirsRaw, BlockDoc.str(page, "format").ifEmpty { "text" })
            if (theirs.converted && !theirsRaw.isNullOrBlank()) return false   // alte Textnotiz: nie gleich
            BlockDoc.sameContent(BlockDoc.parse(mine, "blocks").doc, theirs.doc)
        } catch (_: BlockDoc.UnreadableException) {
            false
        }
    }
}

/**
 * Entwuerfe ungespeicherter Seiten - auf Platte, damit Eingaben einen Prozess-Tod (App weggewischt, wenig
 * Speicher), Offline-Zeiten und Serverwechsel ueberstehen. Je Server und Konto getrennt - das Konto ueber seine
 * stabile Kennung vom Server (Benutzername), nicht ueber den Anzeigenamen (der sich aendern kann).
 * Alle Funktionen machen Datei-Zugriffe: nicht auf dem Haupt-Thread aufrufen.
 */
interface Drafts {
    fun read(id: Int): Draft?
    fun write(d: Draft)
    fun delete(id: Int)
    fun all(): List<Draft>
}

class MemoryDrafts : Drafts {
    val map = ConcurrentHashMap<Int, Draft>()
    override fun read(id: Int) = map[id]
    override fun write(d: Draft) {
        map[d.id] = d
    }
    override fun delete(id: Int) {
        map.remove(id)
    }
    override fun all() = map.values.sortedBy { it.id }
}

class FileDrafts(val dir: File) : Drafts {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(id: Int) = File(dir, "$id.json")

    @Synchronized
    override fun read(id: Int): Draft? = try {
        file(id).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(Draft.serializer(), it) }
    } catch (_: Exception) {
        null
    }

    @Synchronized
    override fun write(d: Draft) {
        dir.mkdirs()
        val tmp = File(dir, "${d.id}.json.tmp")
        tmp.writeText(json.encodeToString(Draft.serializer(), d))
        if (!tmp.renameTo(file(d.id))) {                        // atomar ersetzen; sonst kopieren
            file(d.id).writeText(tmp.readText())
            tmp.delete()
        }
    }

    @Synchronized
    override fun delete(id: Int) {
        file(id).delete()
    }

    @Synchronized
    override fun all(): List<Draft> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { it.name.removeSuffix(".json").toIntOrNull()?.let(::read) }
        .sortedBy { it.id }

    /**
     * Alle lesbaren Entwuerfe nach dest verschieben (bei gleicher Seite gewinnt der neuere), danach den leeren
     * Ordner entfernen. -> Anzahl uebernommener Entwuerfe
     */
    fun moveAllTo(dest: FileDrafts): Int {
        if (dest.dir == dir) return 0
        var n = 0
        for (d in all()) {
            val old = dest.read(d.id)
            if (old == null || old.stamp <= d.stamp) {
                dest.write(d)
                n++
            }
            delete(d.id)
        }
        dir.listFiles { f -> f.name.endsWith(".json.tmp") }?.forEach { it.delete() }
        dir.delete()                                               // nur wenn leer (unlesbare Dateien bleiben liegen)
        return n
    }

    companion object {
        private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

        private fun base(root: File) = File(root, "drafts")

        /** Bisheriger Ordner je Server + Kontoname (Name als Hash, keine Adressen im Dateinamen) */
        fun legacyDir(root: File, server: String, name: String) = File(base(root), hash("$server|$name"))

        /**
         * Ordner je Server + Konto. accountKey: "uid:<Benutzername>" (stabil, vom Server) oder "name:<Name>" bzw.
         * "legacy:<Name>" (beim Anmelden eingefroren, solange die Kennung unbekannt ist); leer = Kontoname (alt).
         */
        fun dirFor(root: File, server: String, accountKey: String, accountName: String): File = when {
            AccountKey.isStable(accountKey) -> File(base(root), "u" + hash("$server|$accountKey"))
            else -> legacyDir(root, server, AccountKey.frozenName(accountKey) ?: accountName)
        }
    }
}

/** Schluessel, unter dem die Entwuerfe eines Kontos liegen (siehe FileDrafts.dirFor) */
object AccountKey {
    private const val UID = "uid:"
    private const val NAME = "name:"
    private const val LEGACY = "legacy:"

    fun uid(username: String) = UID + username
    /** Bei der Anmeldung eingefroren (Kennung noch unbekannt, z. B. aelterer Server) */
    fun name(name: String) = NAME + name
    /** Beim Update von einer Fassung ohne Kennung eingefroren (Entwuerfe lagen unter dem Kontonamen) */
    fun legacy(name: String) = LEGACY + name

    fun isStable(key: String) = key.startsWith(UID)
    fun isLegacy(key: String) = key.startsWith(LEGACY)
    fun frozenName(key: String): String? = when {
        key.startsWith(NAME) -> key.removePrefix(NAME)
        key.startsWith(LEGACY) -> key.removePrefix(LEGACY)
        else -> null
    }

    /**
     * Alte Ordner, deren Entwuerfe beim Wechsel auf die stabile Kennung zu diesem Konto gehoeren: der eingefrorene
     * Name, bei Updates zusaetzlich alle bekannten Namen des Kontos und "" (aeltere Server meldeten keinen Namen).
     */
    fun migrationNames(key: String, knownNames: Collection<String?>): Set<String> {
        val frozen = frozenName(key)
        val out = LinkedHashSet<String>()
        if (frozen != null) out += frozen
        if (frozen == null || isLegacy(key)) {
            knownNames.filterNotNullTo(out)
            out += ""
        }
        return out
    }
}

/**
 * Entwuerfe des angemeldeten Kontos. Wird die stabile Konto-Kennung bekannt, wandert der Ordner (moveTo) - auch
 * unter bereits offenen Editoren, die dieses Objekt halten; dabei bleibt nichts im alten Ordner liegen.
 */
class AccountDrafts(dir: File) : Drafts {
    private var files = FileDrafts(dir)
    private val former = HashSet<File>()

    val dir: File @Synchronized get() = files.dir

    /** Gehoert dieser Ordner (aktuell oder vor der Migration) zu diesem Objekt? */
    @Synchronized
    fun covers(d: File) = d == files.dir || d in former

    @Synchronized override fun read(id: Int) = files.read(id)
    @Synchronized override fun write(d: Draft) = files.write(d)
    @Synchronized override fun delete(id: Int) = files.delete(id)
    @Synchronized override fun all() = files.all()

    /** Auf den Ordner target wechseln und alle Entwuerfe aus dem bisherigen Ordner und aus sources dorthin holen */
    @Synchronized
    fun moveTo(target: File, sources: Collection<File>): Int {
        val dest = FileDrafts(target)
        var n = 0
        for (src in (listOf(files.dir) + sources).distinct()) {
            if (src == target || !src.isDirectory) continue
            n += FileDrafts(src).moveAllTo(dest)
            former += src
        }
        former += files.dir
        files = dest
        return n
    }
}
