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
        private val SAFE = Regex("^[A-Za-z0-9_-]{1,80}$")
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

    fun cached(f: ChatFile): ImageBitmap? = mem.get(f.id)

    /** Vorschaubild laden: th (verschluesselt, Platte/Server) bzw. ohne th selbst erzeugen und verschluesselt cachen */
    suspend fun load(chatId: Long, f: ChatFile, maxOriginal: Long): ImageBitmap? {
        mem.get(f.id)?.let { return it }
        return gate.withPermit {
            mem.get(f.id)?.let { return@withPermit it }
            val cid = chatId.toString()
            val th = f.th
            val bmp = if (th != null && th.id.isNotEmpty()) {
                val key = unb64(th.k, 32) ?: return@withPermit null
                val blob = withContext(Dispatchers.IO) { disk.get(th.id) } ?: fetch(th.id).also { b -> withContext(Dispatchers.IO) { disk.put(th.id, b) } }
                withContext(Dispatchers.Default) { ChatImages.decodeSmall(decrypt(cid, blob, key)) }
            } else {
                val key = unb64(f.k, 32) ?: return@withPermit null
                val selfKey = "self-" + f.id
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
                            withContext(Dispatchers.IO) { disk.put(selfKey, enc) }
                            ChatImages.decodeSmall(t.jpeg)
                        } else {
                            null
                        }
                    }
                }
            }
            bmp?.asImageBitmap()?.also { mem.put(f.id, it) }
        }
    }

    fun clearMemory() = mem.evictAll()
}
