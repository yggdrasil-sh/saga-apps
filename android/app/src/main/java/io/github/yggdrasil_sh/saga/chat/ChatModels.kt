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
    /** 2.4 Videos im Chat (saga-file/v2): fehlt (Server 2.3) -> nur v1 bis file_max */
    val file2: Boolean = false,
    /** groesste Klartext-Groesse eines v2-Videos */
    @SerialName("video_max") val videoMax: Long = 0,
    /** hoechstens so viele Bytes je PUT */
    @SerialName("upload_part") val uploadPart: Long = 8L * 1048576,
    @SerialName("file2_cs") val file2Cs: Int = 262144,
    @SerialName("uploads_max") val uploadsMax: Int = 2,
    /** Chat-Speicher des Kontos (Bytes); 0 = unbekannt */
    val quota: Long = 0,
    val used: Long = 0,
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
    /** 2.4: Gelesen-Bestaetigungen/Online-Status - eigene Einstellung, Faehigkeiten ("receipts", "online"), Grenzen; aeltere Server: fehlen */
    val privacy: ChatPrivacy? = null,
    val features: List<String> = emptyList(),
    @SerialName("status_limits") val statusLimits: StatusLimits? = null,
    /** 2.5: Grenzen fuer Reaktionen, Bearbeiten und Sprachnachrichten (features "react", "edit", "voice"); aeltere Server: fehlt */
    @SerialName("x3_limits") val x3Limits: X3Limits? = null,
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
    /**
     * 2.4 (W4): Kennung wie in der Personensuche (pseudonymer Handle '~…'). `username` bleibt fuer die Krypto (Chats v1),
     * wird aber nie gezeigt; Treffer der Personensuche tragen in `username` den Handle.
     */
    val ref: String? = null,
    /** 2.4: Unterzeile fuer Gleichnamige (Personensuche und members[]: oeffentlicher Name bzw. Handle, nie der Anmeldename) */
    val sub: String? = null,
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
    /** 2.5 (x3_ok): Reaktionen (hoechstens 50, je Person die neueste), neueste Bearbeitung (vollstaendige Nachricht mit key) */
    val rx: List<Reaction> = emptyList(),
    val edit: ApiMessage? = null,
    @SerialName("edited_at") val editedAt: Double? = null,
    @SerialName("edit_n") val editN: Int = 0,
    @SerialName("edit_of") val editOf: String? = null,
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
    /** 2.3 (E5): Einladung in eine Gruppe eines anderen Servers - wer eingeladen hat ({name, host, label}) */
    @SerialName("invited_by") val invitedBy: ChatInviter? = null,
    /** 2.4 (nur v2): der andere Server kann Gelesen-Bestaetigungen bzw. Online-Status (false = Saga 2.3); lokal fehlt das Feld */
    @SerialName("receipts_ok") val receiptsOk: Boolean? = null,
    @SerialName("online_ok") val onlineOk: Boolean? = null,
    /** 2.4: alle beteiligten Server koennen grosse Videos (chatfile2); false = Gegenueber hat Saga 2.3; lokal fehlt das Feld */
    @SerialName("big_files") val bigFiles: Boolean? = null,
    /** 2.4 E5: Gruppenanruf moeglich / laeuft (nur GET /api/chats/{id}); fehlt bei aelteren Servern */
    val call: io.github.yggdrasil_sh.saga.call.ChatCallInfo? = null,
    /** 2.5: Reaktionen, Bearbeiten, Sprachnachrichten moeglich (Format v2, angenommen, beschreibbar, alle Server chat3) */
    @SerialName("x3_ok") val x3Ok: Boolean? = null,
    /** 2.5: bestehender Chat auf Stufe 3 umgestellt - Nachrichten bis seq (bzw. ohne sender_handle) im Format v1 */
    val v3: ChatV3Info? = null,
) {
    /**
     * Krypto saga-chat/v2 (an Handles und chat_ref gebunden): Chats ueber Servergrenzen (v 2) und seit 2.5 auch lokale
     * Chats der Stufe 3 (v 3). Nur die Krypto - ob andere Server beteiligt sind, sagt [remote].
     */
    val isFed: Boolean get() = (v == 2 || v == 3) && !ref.isNullOrEmpty()

    /** Chat ueber Servergrenzen (Hinweise auf Server, Heim, Zustellung) - nicht bei lokalen Chats der Stufe 3 */
    val remote: Boolean get() = isFed && v != 3

    /** 2.5: Nachrichten bis hierher (seq) stammen aus der Zeit vor der Umstellung auf Stufe 3 (v1 pruefen) */
    val v3Seq: Long get() = v3?.seq ?: 0

    /** Kennung fuer die Verschluesselung (AD von Nachrichten/Anhaengen): v1 chat_id, v2 chat_ref */
    val cryptoId: String get() = if (isFed) ref!! else id.toString()
}

/** 2.5: Umstellpunkt eines bestehenden Chats auf Stufe 3 */
@Serializable
data class ChatV3Info(val seq: Long = 0, val at: Double? = null, val forced: Boolean = false)

/** 2.3: wer in eine Gruppe ueber Servergrenzen eingeladen hat */
@Serializable
data class ChatInviter(val name: String = "", val host: String? = null, val label: String? = null)

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

/** 2.3: Person in einer Systemzeile (Mitglieder): Anzeigename, Herkunft (Server), Handle */
@Serializable
data class SysPerson(val name: String? = null, val host: String? = null, val handle: String? = null)

/**
 * 2.3: Systemzeile (kind 'sys', Klartext-Metadaten vom Heim), z. B. {"type":"ai","on":true,"by":handle,"name":…},
 * {"type":"members","added":[{name,host,handle}],"removed":[…],"by":{…}|null,"reason":"disconnected"|"gone"|"access"?},
 * {"type":"fed","on":true,"ai_off":true?}. "by" ist bei "ai" der Handle (Text), bei "members" eine Person (roh gelesen).
 */
@Serializable
data class ChatSys(
    val type: String = "",
    val on: Boolean = false,
    val by: kotlinx.serialization.json.JsonElement? = null,
    val name: String? = null,
    val added: List<SysPerson> = emptyList(),
    val removed: List<SysPerson> = emptyList(),
    val reason: String? = null,
    @SerialName("ai_off") val aiOff: Boolean = false,
    /** 2.5 {"type":"crypto","level":3,"verify"?:true}: Chat auf Stufe 3 umgestellt; verify = Sicherheitsnummern neu vergleichen */
    val level: Int = 0,
    val verify: Boolean = false,
) {
    /** "by" als Handle (Systemzeile "ai") */
    val byHandle: String?
        get() = (by as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (by as? kotlinx.serialization.json.JsonObject)?.get("handle")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

    /** "by" als Person (Systemzeile "members"); null = vom Heim selbst (z. B. Trennen) */
    val byPerson: SysPerson?
        get() = (by as? kotlinx.serialization.json.JsonObject)?.let { o ->
            fun f(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            SysPerson(f("name"), f("host"), f("handle"))
        }
}

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
    /** 2.4: Format (fehlt/1 = v1 ein Block, 2 = saga-file/v2, groesser = neuere Version), Blockgroesse bei v2 */
    val x: Int? = null, val cs: Int? = null,
    /** 2.4 Videos: Dauer (s), Drehung (0/90/180/270), moov vorn (faststart, nur Hinweis) */
    val dur: Double? = null, val rot: Int? = null, val fs: Boolean? = null,
    /** 2.5 Sprachnachricht: Ogg/Opus, Wellenform (64 Werte 0-255, B64), Dauer in dur */
    val voice: Boolean? = null, val wf: String? = null,
) {
    /** Sprachnachricht (nur fuer Audio-Anhaenge) */
    val isVoice: Boolean get() = voice == true && m.startsWith("audio/")

    val isImage: Boolean get() = m.startsWith("image/")
    val isVideo: Boolean get() = m.startsWith("video/")
    /** Format: 1 (v1) oder 2 (saga-file/v2); andere Werte kann diese Version nicht oeffnen */
    val format: Int get() = x ?: 1
    val isV2: Boolean get() = format == 2
    val unsupported: Boolean get() = format != 1 && format != 2
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
