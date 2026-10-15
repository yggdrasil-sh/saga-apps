package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.util.ImmichChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ImmichStatus(val available: Boolean = true, val connected: Boolean = false)

@Serializable
data class ImmichAsset(val id: String = "", val name: String = "", val mime: String? = null, val date: String? = null) {
    val isGif: Boolean get() = mime == "image/gif"
}

@Serializable
data class ImmichAssets(val items: List<ImmichAsset> = emptyList(), val next: Int? = null)

@Serializable
data class ImmichAlbum(val id: String = "", val name: String = "", val count: Int = 0, val thumb: String? = null)

@Serializable
data class ImmichAlbums(val albums: List<ImmichAlbum> = emptyList())

/**
 * Immich ueber die bestehenden Server-Endpunkte (wie die Bild-Auswahl der Seiten im Web/iOS): Status, Alben,
 * Zeitleiste/Inhaltssuche, Vorschaubilder. Der Server reicht nur durch; Svens Immich-Schluessel bleibt auf dem Server.
 */
class ImmichApi(private val api: ApiClient, private val json: Json) {
    /** Fuer diese Person eingerichtet (Server kennt Immich und ein Schluessel ist hinterlegt)? */
    suspend fun connected(): Boolean {
        val s = json.decodeFromString<ImmichStatus>(api.get("api/settings/immich"))
        return s.available && s.connected
    }

    suspend fun albums(): List<ImmichAlbum> = json.decodeFromString<ImmichAlbums>(api.get("api/immich/albums")).albums

    /** page ab 1; ohne q = neueste zuerst; q = Inhaltssuche */
    suspend fun assets(page: Int, album: String?, q: String?): ImmichAssets {
        val body = buildJsonObject {
            put("page", page)
            if (!album.isNullOrEmpty()) put("album", album)
            if (!q.isNullOrBlank()) put("q", q.trim())
        }
        return json.decodeFromString(api.post("api/immich/assets", body))
    }

    fun thumbPath(id: String): String = "/api/immich/thumb/$id"

    /**
     * Bild fuer einen Chat-Anhang: Original, sonst grosse Vorschau (Fallback bei 404/413/415 oder groesser als max).
     * Wird NICHT auf dem Server gespeichert - danach wie jeder Anhang Ende-zu-Ende verschluesselt hochladen.
     */
    suspend fun chatImage(a: ImmichAsset, max: Long): ByteArray =
        ImmichChat.firstAvailable(ImmichChat.sizes(a.mime), { (it as? ApiException)?.code }) { size ->
            val b = withContext(Dispatchers.IO) { api.getBytes(ImmichChat.path(a.id, size)) }
            if (b.size > max && size != ImmichChat.sizes(a.mime).last()) throw ApiException(413, io.github.yggdrasil_sh.saga.util.UiText.Raw("413"))
            b
        }
}
