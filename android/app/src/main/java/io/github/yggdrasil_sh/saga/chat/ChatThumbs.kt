package io.github.yggdrasil_sh.saga.chat

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plattencache fuer Chat-Anhaenge - NUR Ciphertext (Blobs wie vom Server bzw. selbst verschluesselte Vorschaubilder);
 * die Schluessel stehen nur in der verschluesselten Nachricht ("encrypted at rest"). LRU nach Zugriffszeit,
 * Obergrenze maxBytes. Leeren beim Abmelden, "Neu beginnen" und wenn dieses Geraet entfernt wurde.
 */
class ChatBlobCache(private val dir: File, private val maxBytes: Long = MAX_BYTES) {
    companion object {
        const val MAX_BYTES = 200L * 1024 * 1024
        const val DIR = "chat-blobs"
        private val SAFE = Regex("^[A-Za-z0-9_-]{1,120}$")
        /** Schluessel im Format ab 2.1: c<Chat>_<Datei> (ChatDeleteLogic.blobPrefix) */
        private val CHAT_KEY = Regex("^c[0-9]+_")

        /** Cache-Schluessel einer Datei eines Chats */
        fun key(chatId: Long, id: String) = ChatDeleteLogic.blobPrefix(chatId) + id
    }

    private var total = -1L

    private fun file(key: String): File? = if (SAFE.matches(key)) File(dir, key) else null

    @Synchronized
    fun get(key: String): ByteArray? {
        val f = file(key)?.takeIf { it.isFile } ?: return null
        return try {
            f.readBytes().also { f.setLastModified(System.currentTimeMillis()) }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun put(key: String, blob: ByteArray) {
        val f = file(key) ?: return
        if (blob.size > maxBytes / 4) return                 // einzelne Riesen-Dateien nicht cachen
        try {
            dir.mkdirs()
            val old = if (f.isFile) f.length() else 0L
            val tmp = File(dir, "$key.tmp")
            tmp.writeBytes(blob)
            if (!tmp.renameTo(f)) { tmp.delete(); return }
            if (total >= 0) total += blob.size - old
            trim()
        } catch (_: Exception) {
        }
    }

    /** Aelteste Eintraege loeschen, bis die Obergrenze eingehalten ist */
    @Synchronized
    fun trim(limit: Long = maxBytes) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (total < 0) total = files.sumOf { it.length() }
        if (total <= limit) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= limit * 9 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    @Synchronized
    fun clear() {
        dir.deleteRecursively()
        total = 0
    }

    /**
     * Eintraege eines Chats loeschen (Chat/Verlauf geloescht, 2.1): alles mit dem Praefix des Chats und alle Eintraege
     * im alten Format ohne Chat-Praefix (vor 2.1 geschrieben, keinem Chat mehr zuzuordnen - nur Cache).
     */
    @Synchronized
    fun clearChat(prefix: String) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        for (f in files) {
            val legacy = !CHAT_KEY.containsMatchIn(f.name)
            if (f.name.startsWith(prefix) || legacy) f.delete()
        }
        total = -1
    }

    @Synchronized
    fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}

/**
 * Vorschaubilder im Chat-Verlauf (2.1-c): Speicher-LRU (entschluesselte Bitmaps) + Plattencache (Ciphertext),
 * hoechstens PARALLEL Ladevorgaenge gleichzeitig; Abbruch beim Wegscrollen (Coroutine wird mit dem Eintrag beendet).
 * Entschluesseln/Dekodieren auf Dispatchers.Default, nie im UI-Thread.
 */
class ChatThumbs(
    private val disk: ChatBlobCache,
    private val fetch: suspend (String) -> ByteArray,
    private val decrypt: (chatId: String, blob: ByteArray, key: ByteArray) -> ByteArray,
    private val encrypt: (chatId: String, data: ByteArray, key: ByteArray) -> ByteArray,
    memBytes: Int = MEM_BYTES,
) {
    companion object {
        const val PARALLEL = 4
        const val MEM_BYTES = 32 * 1024 * 1024
    }

    private val mem = object : LruCache<String, ImageBitmap>(memBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val gate = Semaphore(PARALLEL)

    /** je Chat hochgezaehlt, wenn er geleert wird: laufende Ladevorgaenge legen danach nichts mehr ab */
    private val epochs = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    private fun memKey(chatId: Long, id: String) = "$chatId:$id"

    fun cached(chatId: Long, f: ChatFile): ImageBitmap? = mem.get(memKey(chatId, f.id))

    /** Vorschaubild laden: th (verschluesselt, Platte/Server) bzw. ohne th selbst erzeugen und verschluesselt cachen */
    suspend fun load(chatId: Long, f: ChatFile, maxOriginal: Long, cryptoId: String = chatId.toString()): ImageBitmap? {
        val mk = memKey(chatId, f.id)
        mem.get(mk)?.let { return it }
        val epoch = epochs[chatId] ?: 0
        fun current() = (epochs[chatId] ?: 0) == epoch
        return gate.withPermit {
            mem.get(mk)?.let { return@withPermit it }
            val cid = cryptoId                                      // v1 chat_id, v2 (2.3) chat_ref
            val th = f.th
            val bmp = if (th != null && th.id.isNotEmpty()) {
                val key = unb64(th.k, 32) ?: return@withPermit null
                val dk = ChatBlobCache.key(chatId, th.id)
                val blob = withContext(Dispatchers.IO) { disk.get(dk) }
                    ?: fetch(th.id).also { b -> withContext(Dispatchers.IO) { if (current()) disk.put(dk, b) } }
                withContext(Dispatchers.Default) { ChatImages.decodeSmall(decrypt(cid, blob, key)) }
            } else {
                val key = unb64(f.k, 32) ?: return@withPermit null
                val selfKey = ChatBlobCache.key(chatId, "self-" + f.id)
                val mine = withContext(Dispatchers.IO) { disk.get(selfKey) }
                if (mine != null) {
                    withContext(Dispatchers.Default) { ChatImages.decodeSmall(decrypt(cid, mine, key)) }
                } else {
                    if (f.s > maxOriginal) return@withPermit null
                    val blob = fetch(f.id)
                    withContext(Dispatchers.Default) {
                        val plain = decrypt(cid, blob, key)
                        val t = ChatImages.makeThumb(plain)
                        if (t != null) {
                            // eigenes Vorschaubild mit dem Dateischluessel des Originals verschluesseln (neue Nonce)
                            val enc = encrypt(cid, t.jpeg, key)
                            withContext(Dispatchers.IO) { if (current()) disk.put(selfKey, enc) }
                            ChatImages.decodeSmall(t.jpeg)
                        } else {
                            null
                        }
                    }
                }
            }
            bmp?.asImageBitmap()?.also { if (current()) mem.put(mk, it) }
        }
    }

    /**
     * Chat geloescht/geleert (2.1): entschluesselte Vorschaubilder im Speicher weg; laufende Ladevorgaenge legen nichts
     * mehr ab. Den Plattencache leert der Aufrufer (ChatBlobCache.clearChat, abseits des Haupt-Threads).
     */
    fun clearMemory(chatId: Long) {
        epochs.merge(chatId, 1, Int::plus)
        val prefix = "$chatId:"
        mem.snapshot().keys.filter { it.startsWith(prefix) }.forEach { mem.remove(it) }
    }

    fun clearMemory() = mem.evictAll()
}
