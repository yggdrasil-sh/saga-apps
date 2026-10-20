package io.github.yggdrasil_sh.saga.chat.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatFiles
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.chat.FileV2
import io.github.yggdrasil_sh.saga.chat.FileV2Reader
import io.github.yggdrasil_sh.saga.data.ApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Ciphertext-Cache fuer Videos (2.4): Media3 SimpleCache auf der Platte, LRU, 512 MB, getrennt vom Bildcache
 * (ChatBlobCache). Enthaelt nur Ciphertext - die Schluessel stehen nur in den verschluesselten Nachrichten.
 */
@OptIn(UnstableApi::class)
object VideoCache {
    const val MAX_BYTES = 512L * 1024 * 1024
    const val DIR = "chat-video"

    @Volatile
    private var cache: SimpleCache? = null

    fun evictor(maxBytes: Long = MAX_BYTES) = LeastRecentlyUsedCacheEvictor(maxBytes)

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(File(context.cacheDir, DIR), evictor(), StandaloneDatabaseProvider(context.applicationContext)).also { cache = it }
    }

    /** Abmelden, "Neu beginnen", Geraet entfernt: alles weg (nur im Hintergrund aufrufen) */
    fun clear(context: Context) {
        try {
            val c = get(context)
            c.keys.toList().forEach { c.removeResource(it) }
        } catch (_: Exception) {
            File(context.cacheDir, DIR).deleteRecursively()
        }
    }
}

/**
 * Medien-Token je Datei (POST /api/chat/files/{fid}/token): gilt 15 min; kurz vor Ablauf bzw. nach 401 neu holen.
 * Abruf blockierend (laeuft im Lade-Thread des Players).
 */
class MediaToken(private val api: ApiClient, private val fid: String) {
    @Volatile private var token: String? = null
    @Volatile private var expires = 0L

    @Synchronized
    fun get(force: Boolean = false): String {
        val now = System.currentTimeMillis() / 1000
        token?.takeIf { !force && expires - now > 60 }?.let { return it }
        val text = try {
            runBlocking { api.post("api/chat/files/" + Uri.encode(fid) + "/token") }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e)
        }
        val o = Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("token")
        val t = (o["token"] as? JsonPrimitive)?.content ?: throw IOException("token")
        token = t
        expires = (o["expires"] as? JsonPrimitive)?.longOrNull ?: (now + ((o["ttl"] as? JsonPrimitive)?.longOrNull ?: 900))
        return t
    }
}

/** HTTP-Abruf des Ciphertexts mit X-Saga-Media-Token (kein Cookie); 401 -> neues Token, einmal wiederholen */
@OptIn(UnstableApi::class)
class TokenDataSource(private val http: HttpDataSource, private val token: MediaToken) : DataSource by http {
    override fun open(dataSpec: DataSpec): Long {
        http.setRequestProperty(HEADER, token.get())
        return try {
            http.open(dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 401) throw e
            http.close()
            http.setRequestProperty(HEADER, token.get(force = true))
            http.open(dataSpec)
        }
    }

    companion object {
        const val HEADER = "X-Saga-Media-Token"
    }
}

/** Zugang zu einem Video-Anhang: Wiedergabe (DataSource), Speichern (Strom) - v2 blockweise, v1 ganz im Speicher (<= 10 MB) */
@OptIn(UnstableApi::class)
class VideoSources(private val context: Context, private val api: ApiClient, private val crypto: ChatCrypto) {
    fun cipherUri(f: ChatFile): Uri = Uri.parse(api.url("api/chat/files/" + Uri.encode(f.id)))

    /** ein Token je Datei, geteilt von allen DataSources dieser Wiedergabe */
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, MediaToken>()

    private fun cached(f: ChatFile): DataSource {
        val token = tokens.getOrPut(f.id) { MediaToken(api, f.id) }
        return CacheDataSource.Factory()
            .setCache(VideoCache.get(context))
            .setUpstreamDataSourceFactory { TokenDataSource(OkHttpDataSource.Factory(api.mediaClient).createDataSource(), token) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
    }

    fun reader(f: ChatFile): FileV2Reader {
        val key = unb64(f.k, 32) ?: throw IOException("key")
        return FileV2Reader(crypto, key, f.s, f.cs ?: FileV2.CS)
    }

    /** DataSource-Fabrik fuer ExoPlayer (Klartext-Positionen) */
    fun factory(f: ChatFile): DataSource.Factory = DataSource.Factory {
        val uri = cipherUri(f)
        SagaFileDataSource(reader(f), cached(f), { pos, len -> DataSpec.Builder().setUri(uri).setPosition(pos).setLength(len).build() })
    }

    /** v1-Video (bis file_max): entschluesselt im Speicher */
    fun factoryV1(plain: ByteArray): DataSource.Factory = DataSource.Factory { ByteArrayDataSource(plain) }

    /** v2 als Klartext-Strom nach out (blockweise, Fortschritt 0..1) */
    fun decryptTo(f: ChatFile, out: OutputStream, onProgress: (Float) -> Unit) {
        val r = reader(f)
        val src = cached(f)
        val spec = DataSpec.Builder().setUri(cipherUri(f)).setPosition(0).setLength(r.blobSize).build()
        DataSourceInputStream(src, spec).use { input ->
            r.decryptStream(input, out) { i, n -> if (i % 16 == 0L || i == n - 1) onProgress((i + 1).toFloat() / n) }
        }
    }
}

/** Video in der Galerie speichern (MediaStore, ab Android 10 ohne Berechtigung; Movies/Saga) bzw. in ein gewaehltes Ziel */
object VideoSave {
    /** Android 10+: MediaStore.Video mit IS_PENDING; write schreibt den Klartext als Strom. -> Uri */
    @androidx.annotation.RequiresApi(29)
    fun toGallery(context: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, ChatFiles.safeName(name))
            put(MediaStore.Video.Media.MIME_TYPE, mime.ifEmpty { "video/mp4" })
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Saga")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: throw IOException("insert")
        try {
            (cr.openOutputStream(uri, "w") ?: throw IOException("open")).use { write(it) }
            cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            try {
                cr.delete(uri, null, null)                 // halbe Datei nicht in der Galerie lassen
            } catch (_: Exception) {
            }
            throw e
        }
    }

    /** Android 8/9: in ein mit "Speichern unter" gewaehltes Ziel (SAF, ohne Speicherrecht) */
    fun toUri(context: Context, target: Uri, write: (OutputStream) -> Unit) {
        (context.contentResolver.openOutputStream(target, "w") ?: throw IOException("open")).use { write(it) }
    }
}
