package io.github.yggdrasil_sh.saga.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Antwortformen von saga/chat.py (Server 1.5 / 2.0)

@Serializable
data class ChatIdentityPub(
    @SerialName("ident_pub") val identPub: String = "",
    @SerialName("backup_pub") val backupPub: String = "",
    @SerialName("backup_sig") val backupSig: String = "",
    val bundle: String = "",
)

@Serializable
data class ChatDeviceInfo(
    val id: String = "",
    val name: String = "",
    val created: Double? = null,
    @SerialName("last_seen") val lastSeen: Double? = null,
    val revoked: Boolean = false,
    val current: Boolean = false,
    @SerialName("enc_pub") val encPub: String = "",
    @SerialName("sign_pub") val signPub: String = "",
    val sig: String? = null,
)

@Serializable
data class ChatLimits(
    @SerialName("msg_max") val msgMax: Int = 65536,
    @SerialName("file_max") val fileMax: Long = 10 * 1048576L,
    @SerialName("members_max") val membersMax: Int = 50,
    @SerialName("devices_max") val devicesMax: Int = 10,
    @SerialName("files_per_msg") val filesPerMsg: Int = 4,
    /** 2.1-c: Datei-Kennungen je Nachricht (Original + Vorschaubild); aeltere Server: fehlt -> files_per_msg */
    @SerialName("file_ids_per_msg") val fileIdsPerMsg: Int? = null,
    @SerialName("retention_max") val retentionMax: Long = 0,
    val retentions: List<Long> = emptyList(),
)

/** GET /api/chat/me */
@Serializable
data class ChatMe(
    val enabled: Boolean = false,
    val uid: String = "",
    val name: String = "",
    val identity: ChatIdentityPub? = null,
    val devices: List<ChatDeviceInfo> = emptyList(),
    val links: Int = 0,
    val limits: ChatLimits = ChatLimits(),
    /** null = Sitzung frisch genug fuer "Neu beginnen"; sonst "password" | "login" */
    val reauth: String? = null,
    /** 2.1: womit sich bestaetigen laesst (Teilmenge von password, passkey, totp) */
    @SerialName("reauth_methods") val reauthMethods: List<String> = emptyList(),
)

@Serializable
data class ChatPerson(
    val username: String = "",
    val name: String = "",
    val me: Boolean = false,
    val ready: Boolean = false,
    /** 2.1: Profilbild (relative Adresse /api/avatar/...?v=...) oder null = Initialen */
    val avatar: String? = null,
    /** 2.3 (Chats ueber Servergrenzen): foederierter Handle '~x@host', Server, Anzeige "Name · host", Person eines anderen Servers */
    val handle: String? = null,
    val host: String? = null,
    val label: String? = null,
    val remote: Boolean = false,
)

/** 2.3: Treffer im Adressbuch verbundener Server (GET /api/fed/people, Feld remote in /api/chat/people) */
@Serializable
data class FedPerson(
    val type: String = "remote",
    val name: String = "",
    val host: String = "",
    /** fertig zum Anzeigen: "Anna Berg · b.example" (bei gleichen Namen mehr) */
    val label: String = "",
    val handle: String = "",
    /** Ziel zum Teilen: @name@host bzw. @~x@host */
    val target: String = "",
    val pub: String? = null,
    val avatar: String? = null,
) {
    val shown: String get() = label.ifBlank { if (host.isNotEmpty()) "$name · $host" else name }
}

/** Absender-Geraet (auch entfernte) zur Pruefung der Signaturen */
@Serializable
data class ChatDevicePub(
    val uid: String = "",
    @SerialName("enc_pub") val encPub: String = "",
    @SerialName("sign_pub") val signPub: String = "",
    val sig: String? = null,
    val revoked: Boolean = false,
)

/** Nachricht aus der API (verschluesselt) */
@Serializable
data class ApiMessage(
    val seq: Long = 0,
    val id: String = "",
    val kind: String = "msg",
    val sender: ChatPerson? = null,
    @SerialName("sender_device") val senderDevice: String = "",
    val ts: Long = 0,
    val created: Double? = null,
    @SerialName("expires_at") val expiresAt: Double? = null,
    @SerialName("body_ct") val bodyCt: String = "",
    val sig: String? = null,
    @SerialName("reply_to") val replyTo: String? = null,
    val key: String? = null,
    @SerialName("key_for") val keyFor: String = "device",
    /** nur bei title_msg (Chat-Liste): Absender-Geraet und Identitaet */
    val device: ChatDevicePub? = null,
    @SerialName("ident_pub") val identPub: String? = null,
    /** 2.1: Beleg einer KI-Antwort (nur wenn mit ai_reply gesendet); 2.3 (v2): Hex-SHA-256 als Text, mitsigniert */
    @kotlinx.serialization.Serializable(with = AiRefSerializer::class)
    val ai: ApiAiRef? = null,
    /** 2.3 (Chats ueber Servergrenzen): Reihenfolge des Heims, Absender-Handle, Zustellstand pending|failed|null */
    val fseq: Long? = null,
    @SerialName("sender_handle") val senderHandle: String? = null,
    val fstate: String? = null,
)

/** 2.1: Beleg des Servers zu einer KI-Antwort im Chat; v2: nur der mitsignierte Hash (hashOnly) */
@Serializable
data class ApiAiRef(
    val ref: String = "",
    val sha256: String = "",
    /** Benutzername des Fragenden */
    val by: String? = null,
    @SerialName("q_msg") val qMsg: String? = null,
    @kotlinx.serialization.Transient val hashOnly: Boolean = false,
)

/** "ai" einer Nachricht: Objekt (Beleg v1) oder Text (Hash v2, Teil der Signatur) */
object AiRefSerializer : kotlinx.serialization.KSerializer<ApiAiRef?> {
    override val descriptor = kotlinx.serialization.json.JsonElement.serializer().descriptor

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ApiAiRef? {
        val d = decoder as? kotlinx.serialization.json.JsonDecoder ?: return null
        return when (val e = d.decodeJsonElement()) {
            is kotlinx.serialization.json.JsonObject -> try {
                d.json.decodeFromJsonElement(ApiAiRef.serializer(), e)
            } catch (_: Exception) {
                null
            }
            is kotlinx.serialization.json.JsonPrimitive -> if (e.isString && e.content.isNotEmpty()) ApiAiRef(sha256 = e.content, hashOnly = true) else null
            else -> null
        }
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: ApiAiRef?) {
        val e = encoder as kotlinx.serialization.json.JsonEncoder
        if (value == null) e.encodeJsonElement(kotlinx.serialization.json.JsonNull)
        else if (value.hashOnly) e.encodeJsonElement(kotlinx.serialization.json.JsonPrimitive(value.sha256))
        else e.encodeJsonElement(e.json.encodeToJsonElement(ApiAiRef.serializer(), value))
    }
}

@Serializable
data class ChatSummary(
    val id: Long,
    val kind: String = "dm",
    val name: String? = null,
    val group: String? = null,
    @SerialName("note_id") val noteId: Long? = null,
    val created: Double? = null,
    @SerialName("last_at") val lastAt: Double? = null,
    @SerialName("last_seq") val lastSeq: Long = 0,
    @SerialName("retention_s") val retentionS: Long = 0,
    val muted: Boolean = false,
    val role: String? = null,
    @SerialName("last_read") val lastRead: Long = 0,
    val unread: Int = 0,
    val members: List<ChatPerson> = emptyList(),
    @SerialName("member_count") val memberCount: Int = 0,
    val peer: ChatPerson? = null,
    @SerialName("title_msg") val titleMsg: ApiMessage? = null,
    @SerialName("max_members") val maxMembers: Int? = null,
    /** 2.1: KI ist in diesem Chat erlaubt (nur auf Zuruf) */
    val ai: Boolean = false,
    /** 2.1 Archiv: von mir archiviert; null = Server kennt Archiv/Loeschen nicht (Funktionen ausblenden) */
    val archived: Boolean? = null,
    @SerialName("archived_at") val archivedAt: Double? = null,
    /** 2.1: "Verlauf gelöscht bis" - aeltere Nachrichten liefert der Server nicht mehr aus */
    @SerialName("cleared_seq") val clearedSeq: Long = 0,
    /** 2.1: was ich fuer alle loeschen darf: "chat" | "history" | null (nicht erlaubt) */
    @SerialName("delete_for_all") val deleteForAll: String? = null,
    /** 2.3 Chats ueber Servergrenzen (saga-chat/v2): v 2, globale Kennung ref (<32 hex>@<heim>), Heim-Server */
    val v: Int = 1,
    val ref: String? = null,
    val fed: Boolean = false,
    val home: String? = null,
    @SerialName("home_self") val homeSelf: Boolean = false,
    @SerialName("my_handle") val myHandle: String? = null,
    val state: String? = null,
    /** Nachrichtenanfrage, noch nicht angenommen (keine Benachrichtigung, kein Klingeln) */
    val request: Boolean = false,
    val readonly: Boolean = false,
    @SerialName("readonly_reason") val readonlyReason: String? = null,
    /** aeltere Apps: Hinweis "bitte App aktualisieren" (diese App sendet X-Saga-Chat: 2 und bekommt ihn nicht) */
    @SerialName("fed_hint") val fedHint: String? = null,
) {
    /** Chat ueber Servergrenzen (v2) */
    val isFed: Boolean get() = v == 2 && !ref.isNullOrEmpty()

    /** Kennung fuer die Verschluesselung (AD von Nachrichten/Anhaengen): v1 chat_id, v2 chat_ref */
    val cryptoId: String get() = if (isFed) ref!! else id.toString()
}

@Serializable
data class ChatList(
    val chats: List<ChatSummary> = emptyList(),
    val unread: Int = 0,
    /** 2.3: Anzahl offener Nachrichtenanfragen (zaehlen nicht in unread) */
    val requests: Int = 0,
)

@Serializable
data class ChatWrap(val chat: ChatSummary)

@Serializable
data class MessagesResponse(
    val messages: List<ApiMessage> = emptyList(),
    val more: Boolean = false,
    val devices: Map<String, ChatDevicePub> = emptyMap(),
    val idents: Map<String, String> = emptyMap(),
    @SerialName("last_read") val lastRead: Long = 0,
    @SerialName("last_seq") val lastSeq: Long = 0,
)

@Serializable
data class RecipientDevice(
    val id: String = "",
    @SerialName("enc_pub") val encPub: String = "",
    @SerialName("sign_pub") val signPub: String = "",
    val sig: String? = null,
)

@Serializable
data class Recipient(
    val username: String = "",
    val name: String = "",
    val ready: Boolean = false,
    @SerialName("ident_pub") val identPub: String? = null,
    @SerialName("backup_pub") val backupPub: String? = null,
    @SerialName("backup_sig") val backupSig: String? = null,
    val devices: List<RecipientDevice> = emptyList(),
)

@Serializable
data class RecipientsResponse(val recipients: List<Recipient> = emptyList())

@Serializable
data class LinkCreated(val id: String = "", val expires: Double? = null)

@Serializable
data class LinkState(val state: String = "gone", val sealed: String? = null, val expires: Double? = null)

@Serializable
data class LinkRequest(
    val id: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val name: String = "",
    @SerialName("enc_pub") val encPub: String = "",
    @SerialName("sign_pub") val signPub: String = "",
    val created: Double? = null,
    val expires: Double? = null,
)

@Serializable
data class LinkList(val links: List<LinkRequest> = emptyList())

@Serializable
data class PeopleGroup(val name: String = "")

@Serializable
data class PeopleResult(
    val users: List<ChatPerson> = emptyList(),
    val groups: List<PeopleGroup> = emptyList(),
    /** 2.3: Personen verbundener Server (Adressbuch) - Direkt-Chat ueber Servergrenzen */
    val remote: List<FedPerson> = emptyList(),
)

/** 2.3: GET /api/chat/v2/me (eigener Handle, fehlende Zertifikate v2) */
@Serializable
data class FedMeDevice(val id: String = "", @SerialName("fed_sig") val fedSig: String? = null, val ok: Boolean = false)

@Serializable
data class FedMe(
    val handle: String? = null,
    val host: String? = null,
    @SerialName("cert_ok") val certOk: Boolean = false,
    val devices: List<FedMeDevice> = emptyList(),
    val missing: List<String> = emptyList(),
    val need: Boolean = false,
    val federation: Boolean = false,
    @SerialName("device_set") val deviceSet: FedDeviceSet? = null,
    @SerialName("device_set_ok") val deviceSetOk: Boolean = true,
    @SerialName("device_set_want") val deviceSetWant: List<String> = emptyList(),
)

/** 2.3: GET /api/chat/blocks */
@Serializable
data class ChatBlock(
    val target: String = "",
    val kind: String = "person",
    val name: String? = null,
    val host: String? = null,
    val label: String? = null,
    val created: Double? = null,
)

@Serializable
data class ChatBlocks(val blocks: List<ChatBlock> = emptyList())

/** 2.3: Systemzeile (kind 'sys', Klartext-Metadaten vom Heim), z. B. {"type":"ai","on":true,"by":handle,"name":…} */
@Serializable
data class ChatSys(val type: String = "", val on: Boolean = false, val by: String? = null, val name: String? = null)

@Serializable
data class ChatNoteRef(val id: Long = 0, val title: String = "", val icon: String = "")

@Serializable
data class ChatNotes(val notes: Map<String, ChatNoteRef> = emptyMap())

@Serializable
data class ChatPages(val pages: List<ChatNoteRef> = emptyList())

@Serializable
data class FileUploaded(val id: String = "", val size: Long = 0)

@Serializable
data class SendResult(val ok: Boolean = false, val seq: Long = 0, val created: Double? = null)

/** Vorschaubild eines Bild-Anhangs (2.1-c): eigene Datei mit eigenem Schluessel, JPEG, laengste Seite <= 320 px */
@Serializable
data class ChatThumb(val id: String = "", val k: String = "", val m: String = "image/jpeg", val w: Int = 0, val h: Int = 0, val s: Long = 0)

/**
 * Anhang im (verschluesselten) Inhalt: Schluessel k, Name n, Typ m, Groesse s; Bilder (2.1-c) zusaetzlich Breite w und
 * Hoehe h des Originals und das Vorschaubild th (alte Nachrichten: fehlen)
 */
@Serializable
data class ChatFile(
    val id: String = "", val k: String = "", val n: String = "", val m: String = "", val s: Long = 0,
    val w: Int? = null, val h: Int? = null, val th: ChatThumb? = null,
) {
    val isImage: Boolean get() = m.startsWith("image/")
}

/** Entschluesselter Inhalt einer Nachricht ({"v":1,"type":"text"|"title", ...}); unbekannte Felder werden ignoriert */
@Serializable
data class ChatContent(
    val v: Int = 1,
    val type: String = "text",
    val text: String = "",
    val title: String? = null,
    val files: List<ChatFile> = emptyList(),
    val notes: List<Long> = emptyList(),
    /**
     * 2.1: als KI-Antwort gesendet {"reply", "name"} (nur mit passendem Beleg als solche anzeigen). Roh gelesen: eine
     * kaputte Angabe darf die Nachricht nicht unlesbar machen (sie wird dann nur als "nicht bestaetigt" markiert).
     */
    val ai: kotlinx.serialization.json.JsonElement? = null,
) {
    /** Beleg-Kennung aus ai.reply (null = fehlt/kaputt) */
    val aiReply: String?
        get() = ((ai as? kotlinx.serialization.json.JsonObject)?.get("reply") as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content

    /** Name der KI aus ai.name (nur Anzeige-Rueckfall, wenn der Server keine Persona liefert) */
    val aiName: String?
        get() = ((ai as? kotlinx.serialization.json.JsonObject)?.get("name") as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim()?.take(80)?.ifEmpty { null }
}
