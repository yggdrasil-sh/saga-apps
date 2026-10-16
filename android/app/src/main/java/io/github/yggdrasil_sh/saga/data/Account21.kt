package io.github.yggdrasil_sh.saga.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Saga 2.1: "Anmeldung & Sicherheit" (Status, verwaltet wird im Browser) und eigenes Profilbild

/** GET /api/account/security - nur die Felder fuer die Statuszeile */
@Serializable
data class SecurityStatus(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val required: Boolean = false,
    val passkeys: List<JsonObject> = emptyList(),
    val totp: Boolean = false,
    @SerialName("recovery_left") val recoveryLeft: Int = 0,
)

/** GET/PUT/PATCH /api/account/avatar */
@Serializable
data class AvatarState(
    val url: String? = null,
    /** "upload" | "sso" | "gravatar" | null (Initialen) */
    val source: String? = null,
    val upload: Boolean = false,
    val gravatar: Boolean = true,
    @SerialName("gravatar_enabled") val gravatarEnabled: Boolean = false,
    @SerialName("has_email") val hasEmail: Boolean = false,
    val public: Boolean = false,
    val sso: String? = null,
    @SerialName("max_mb") val maxMb: Int = 5,
)

/** Reine Regeln (Tests) */
object SecurityLogic {
    /** Wiederherstellungscodes knapp (Warnung) */
    fun recoveryLow(s: SecurityStatus): Boolean = s.enabled && s.recoveryLeft <= 3

    /** Hinweis "Dein Admin verlangt einen zweiten Faktor" */
    fun mustSetUp(s: SecurityStatus): Boolean = s.required && !s.enabled
}

suspend fun SagaRepository.securityStatus(): SecurityStatus = json.decodeFromString(SecurityStatus.serializer(), api.get("api/account/security"))

suspend fun SagaRepository.avatarState(): AvatarState = json.decodeFromString(AvatarState.serializer(), api.get("api/account/avatar"))

/** Rohinhalt hochladen (JPEG aus dem Zuschnitt); 413 zu gross, 415 kein Bild, 429 zu oft */
suspend fun SagaRepository.uploadAvatar(jpeg: ByteArray): AvatarState =
    json.decodeFromString(AvatarState.serializer(), api.putBytes("api/account/avatar", jpeg, "image/jpeg"))

/** Antwort wie GET - sonst (z. B. nur {ok}) den Stand neu holen */
private suspend fun SagaRepository.stateFrom(text: String): AvatarState =
    try {
        json.decodeFromString(AvatarState.serializer(), text).takeIf { text.contains("\"source\"") }
    } catch (_: Exception) {
        null
    } ?: avatarState()

suspend fun SagaRepository.deleteAvatar(): AvatarState = stateFrom(api.delete("api/account/avatar"))

suspend fun SagaRepository.patchAvatar(gravatar: Boolean? = null, public: Boolean? = null): AvatarState =
    stateFrom(api.patch("api/account/avatar", buildJsonObject {
        gravatar?.let { put("gravatar", it) }
        public?.let { put("public", it) }
    }))
