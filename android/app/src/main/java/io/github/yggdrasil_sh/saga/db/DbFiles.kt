package io.github.yggdrasil_sh.saga.db

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 2.5 file1: Datei-Werte [{url, name, size, mime, image}] - gespeichert werden nur url und name */
object DbFiles {
    data class File(val url: String, val name: String, val size: Long? = null, val mime: String? = null, val image: Boolean = false)

    fun parse(v: JsonElement?): List<File> = (v as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val url = (o["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        File(
            url, (o["name"] as? JsonPrimitive)?.content.orEmpty(), (o["size"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong(),
            (o["mime"] as? JsonPrimitive)?.takeIf { it.isString }?.content, (o["image"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    /** PATCH: die ganze Liste, nur url + name; leer = Wert entfernen */
    fun toValue(files: List<File>): JsonElement =
        if (files.isEmpty()) JsonNull else JsonArray(files.map { JsonObject(mapOf("url" to JsonPrimitive(it.url), "name" to JsonPrimitive(it.name))) })

    /** Antwort von POST /api/files -> {file: {url, name?}} */
    fun fromUpload(text: String, fallbackName: String, mime: String?): File? {
        val o = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val f = o?.get("file") as? JsonObject ?: return null
        val url = (f["url"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val name = (f["name"] as? JsonPrimitive)?.content?.ifBlank { null } ?: fallbackName
        return File(url, name, mime = mime, image = mime?.startsWith("image/") == true)
    }
}
