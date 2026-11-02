package io.github.yggdrasil_sh.saga.diag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * 2.5 Fehlerberichte und "Diagnose senden" (Server-Feature clientlog1, Vertrag docs/APP-API.md "Fehlerberichte und
 * Diagnose"). Reine Logik ohne Android (Unit-Test ClientLogTest), Laufzeit in [ClientLogger]:
 * - [scrub]: Fehlertext ohne Inhalte (wie static/clientlog.js scrub): URLs, E-Mail, Schluessel, Zitate, Pfade, Handles,
 *   UUIDs, Hex, Zahlen -> Platzhalter, jedes Wort, das nicht nach Programm aussieht oder in englischen Fehlermeldungen
 *   steht -> <w>. Der Server bereinigt noch einmal.
 * - [stack]: nur Klasse.Methode(Datei:Zeile), hoechstens 20 Zeilen / 2000 Zeichen
 * - [pathPattern]: Endpunkt-Muster (/api/notes/:id/comments) - nur feste Saga-Pfadteile bleiben, sonst :id/:x
 * - [Pending]: Fehler sammeln und zaehlen (gleiche -> n), hoechstens 20 je Bericht, hoechstens ein Bericht je 30 s,
 *   429 -> fruehestens nach retry_s, 404 -> bis zum naechsten Start aus
 * - [Ring]: die letzten 200 Ereignisse im Speicher (verlassen das Geraet nur ueber "Diagnose senden")
 * - [crashLine]/[parseCrash]: Absturz-Notiz (eine JSON-Zeile je Absturz) fuer den naechsten Start
 */
object ClientLog {
    const val FEATURE = "clientlog1"
    const val PATH_LOG = "api/client/log"
    const val PATH_DIAG = "api/client/diag"
    const val RATE = 30_000L
    const val ITEMS = 20
    const val RING = 200
    const val LOG_MAX = 16 * 1024
    const val DIAG_MAX = 64 * 1024
    const val NOTE_MAX = 1000
    const val MSG_MAX = 300
    const val STACK_LINES = 20
    const val STACK_MAX = 2000
    const val T_MAX = 7L * 24 * 3600 * 1000
    /** hoechstens so viele Absturz-Notizen aufheben (der Rest ist dieselbe Schleife) */
    const val CRASH_KEEP = 5

    /** ApplicationExitInfo.REASON_* (API 30) - hier als Zahl, damit die Logik ohne Android testbar bleibt */
    const val EXIT_CRASH = 4
    const val EXIT_CRASH_NATIVE = 5
    const val EXIT_ANR = 6

    val KINDS = setOf("error", "unhandled", "crash", "anr", "warn")
    val EV_KINDS = setOf("err", "net", "view", "conn", "save", "life")
    val CONN_CH = setOf("sse", "poll", "ws", "collab", "push", "call")
    val CONN_ST = setOf("open", "closed", "error", "poll", "retry")
    val SAVE_ST = setOf("ok", "conflict", "offline", "error", "merged")
    val LIFE_ST = setOf("start", "hidden", "visible", "online", "offline", "resume")
    val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    val SCREEN = Regex("[a-z][a-z0-9-]{0,23}")
    val DIAG_ID = Regex("D-[23456789ABCDEFGHJKMNPQRSTVWXYZ]{5}")

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------------------------------------------- Bereinigung

    private val CTRL = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]")
    private val URL = Regex("""\b[a-z][a-z0-9+.\-]{1,15}://[^\s"'<>()]*|\bwww\.[^\s"'<>()]+|\b(?:blob|data|javascript|mailto|tel|content|file):[^\s"'<>()]*""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""[\w.+\-]+@[\w\-]+(?:\.[\w\-]+)+""")
    private val SECRET = Regex("""\b(?:hkd?|sga)_[A-Za-z0-9_\-]{6,}|\bBearer\s+\S+""", RegexOption.IGNORE_CASE)
    private val QUOTE = Regex("'([^'\\n]{0,200})'|\"([^\"\\n]{0,200})\"|`([^`\\n]{0,200})`|\u201e([^\u201c\u201d\\n]{0,200})[\u201c\u201d]|\u201c([^\u201d\\n]{0,200})\u201d|\u00ab([^\u00bb\\n]{0,200})\u00bb")
    private val IDENT = Regex("""[A-Za-z_$][\w$.]{0,39}""")
    private val PATH = Regex("""(^|[^\w.:<])/[^\s"'<>()]+""")
    private val HANDLE = Regex("""[~@][\w.\-]{2,}""")
    private val UUID = Regex("""\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""", RegexOption.IGNORE_CASE)
    private val HEX = Regex("""\b(?:0x)?[0-9a-f]{8,}\b""", RegexOption.IGNORE_CASE)
    /** Hostnamen ohne Schema ("Unable to resolve host example.org", "Hostname a.b.net not verified") */
    private val HOST = Regex("""(?<![\w.$/])(?:[a-z0-9][a-z0-9\-]*\.)+([a-z]{2,24})(?![\w.$(])""", RegexOption.IGNORE_CASE)
    private val FILE_EXT = setOf("kt", "java", "swift", "py", "js", "so", "xml", "json", "png", "jpg", "jpeg", "webp", "gif", "mp4", "pdf", "txt")
    private val IPV6 = Regex("""\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){3,7}\b""")
    private val TOKEN = Regex("""(?<![\w~])(?=[A-Za-z_\-+=]*\d)[A-Za-z0-9_\-+=]{20,}""")
    private val NUM = Regex("""(^|[^\w$<])\d+(?:[.,]\d+)*""")
    private val WORD = Regex("""<[a-z]+>|'[a-z_$][\w$.]{0,39}'|[^\W\d_][\w$]*(?:\.[\w$]+)*""")
    private val PROGRAM = Regex("""[.$_\d]|[a-z][A-Z]|(?:Error|Exception)$""")
    private val ALLOW = Regex("""[^\w\s.,:;()\[\]{}<>#'"=+\-*/!?&|%$~^]""")
    private val SPACES = Regex("""\s{2,}""")

    /** Woerter englischer Fehlermeldungen (wie _ERR_WORDS in saga/clientlog.py) - alles andere wird <w> */
    val ERR_WORDS: Set<String> = ("a abort aborted access again allowed already an anr append are argument arguments array assignment at " +
        "attempting audio be been blocked bool boolean by call called can cannot canvas child children chunk class " +
        "clipboard close closed code codec completed connection constant constructor contains context could count " +
        "crash critical cross crypto database decode decoding decrypt defined denied device devices did disabled do " +
        "document does double dynamically element elements enabled encode encoding encrypt end enum error errors " +
        "eventsource exceeded exception execute executing exit expected export fail failed failure false fatal fetch " +
        "fetching float for from function functions gesture get had has have http https image import imported in " +
        "index input insert instance instanceof int interaction internal into invalid is it iterable iterator its " +
        "json key keys killed later length limit load loaded loading loop lost main map maximum media memory message " +
        "method methods missing module must name nan navigator network new nil no node nodes none not notifications " +
        "null number object observer of offline offset ok on online only oom open opened operation optional origin " +
        "out parent parse parsing paused permission play please promise properties property protocol queue quota " +
        "range read reading reference rejected rejection remove removed request required resolved resource response " +
        "script selector self service set setting should signal signature size socket stack state status still " +
        "storage stream streams string struct support supported syntax that the this thread timed timeout to token " +
        "track tracks transaction true try type types uncaught undefined undelivered unexpected unhandled unknown " +
        "upgrade user value values variable verification verify version video warning was webgl websocket were when " +
        "which while window with without worker write yet").split(' ').toSet()

    /** Fehlertext ohne Inhalte, eine Zeile, hoechstens [max] Zeichen */
    fun scrub(s0: String?, max: Int = MSG_MAX): String {
        var s = (s0 ?: "").take(4000).replace(CTRL, " ")
        s = s.replace(URL, "<url>")
        s = s.replace(EMAIL, "<email>")
        s = s.replace(SECRET, "<secret>")
        // Rechnernamen ohne Schema ("Unable to resolve host x.example"), nicht Paketnamen (java.lang.X) oder Dateien (A.kt)
        s = HOST.replace(s) { m -> if (m.groupValues[1].lowercase() in FILE_EXT || m.value.any { it.isUpperCase() }) m.value else "<host>" }
        s = QUOTE.replace(s) { m ->
            val x = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
            val q = m.value[0]
            val ascii = q == '\'' || q == '"' || q == '`'
            if (x.isNotEmpty() && IDENT.matches(x)) (if (ascii) m.value else "'$x'") else if (ascii) "$q<str>$q" else "\"<str>\""
        }
        s = PATH.replace(s) { it.groupValues[1] + "<path>" }
        s = s.replace(HANDLE, "<id>")
        s = s.replace(IPV6, "<n>")
        s = s.replace(UUID, "<uuid>")
        s = s.replace(HEX, "<hex>")
        s = s.replace(TOKEN, "<tok>")
        s = NUM.replace(s) { it.groupValues[1] + "<n>" }
        s = WORD.replace(s) { m ->
            val w = m.value
            if (w[0] == '<' || w[0] == '\'' || w.length == 1 || PROGRAM.containsMatchIn(w) || w.lowercase() in ERR_WORDS) w else "<w>"
        }
        s = s.replace(ALLOW, "?")
        return s.replace(SPACES, " ").trim().take(max)
    }

    /**
     * HTTP-Status als Wort mit Unterstrich (bleibt bei der Bereinigung stehen - Zahlen werden dort <n>), z. B.
     * 409 -> http_conflict, 502 -> http_bad_gateway
     */
    fun httpName(code: Int): String = "http_" + when (code) {
        0 -> "no_response"
        400 -> "bad_request"
        401 -> "unauthorized"
        403 -> "forbidden"
        404 -> "not_found"
        409 -> "conflict"
        410 -> "gone"
        413 -> "payload_too_large"
        423 -> "locked"
        429 -> "too_many_requests"
        500 -> "internal_server_error"
        502 -> "bad_gateway"
        503 -> "service_unavailable"
        504 -> "gateway_timeout"
        in 500..599 -> "server_error"
        in 400..499 -> "client_error"
        else -> "other"
    }

    /** Endpunkt-Muster als ein Bezeichner (/api/notes/:id/comments -> api_notes_id_comments) - bleibt beim Server stehen */
    fun pathIdent(p: String): String = pathPattern(p).trim('/').split('/')
        .joinToString("_") { it.removePrefix(":").replace('-', '_').replace('.', '_').ifEmpty { "x" } }.take(100)

    /** Fehler-Text fuer einen HTTP-Status an einem Endpunkt (nur Muster): "ApiException: http_bad_gateway method_post api_notes_id" */
    fun httpMessage(method: String, path: String, code: Int): String =
        "ApiException: ${httpName(code)} method_${method.lowercase().filter { it in 'a'..'z' }} ${pathIdent(path)}".take(MSG_MAX)

    /** grober Ort aus einem App-Ziel (Dest.encode): nie Titel oder ID */
    fun screenOf(dest: String): String = when (dest.substringBefore(':')) {
        "today" -> "dashboard"
        "tasks" -> "tasks"
        "events" -> "events"
        "services" -> "services"
        "search" -> "search"
        "page" -> "pages"
        "notifications" -> "notifications"
        "chats", "chat" -> "chat"
        "graph" -> "graph"
        "settings", "settings-account", "settings-general", "settings-info", "push", "chat-devices", "backup", "nextcloud" -> "settings"
        else -> "other"
    }

    /** Meldung einer Ausnahme: Klasse + bereinigter Text; ohne Text nur die Klasse */
    fun message(e: Throwable): String {
        if (e is io.github.yggdrasil_sh.saga.data.ApiException) return "ApiException: ${httpName(e.code)}"
        val cls = e.javaClass.simpleName.ifEmpty { "Throwable" }
        // Meldungen mit Oberflaechen-Texten bzw. Server-Antworten (Namen, Titel) nie mitschicken: nur die Klasse
        val m = if (e.javaClass.name.startsWith("io.github.yggdrasil_sh.saga.")) null else e.message
        val t = scrub(m)
        return (if (t.isEmpty()) cls else "$cls: $t").take(MSG_MAX)
    }

    /** Ein Stack-Eintrag (Klasse, Methode, Datei, Zeile) - ohne Android/JVM-Typen testbar */
    data class Frame(val cls: String, val method: String, val file: String?, val line: Int)

    fun frames(e: Throwable): List<Frame> = e.stackTrace.map { Frame(it.className, it.methodName, it.fileName, it.lineNumber) }

    private val SYM_BAD = Regex("""[^\w$.<>\-]""")
    private val FILE_OK = Regex("""[\w.\-]{1,60}""")

    fun frameLine(f: Frame): String {
        val sym = (SYM_BAD.replace(f.cls, "") + "." + SYM_BAD.replace(f.method, "")).take(150)
        val file = f.file?.takeIf { FILE_OK.matches(it) }
        val loc = when {
            file != null && f.line >= 0 -> "$file:${f.line}"
            file != null -> file
            else -> "Unknown Source"
        }
        return "at $sym($loc)"
    }

    /** Stack: nur Klasse.Methode(Datei:Zeile), Ursachen nur als Klasse; <= 20 Zeilen / 2000 Zeichen */
    fun stack(e: Throwable): String {
        val out = ArrayList<String>()
        var n = 0
        fun add(x: String): Boolean {
            if (out.size >= STACK_LINES || n + x.length + 1 > STACK_MAX) return false
            out.add(x)
            n += x.length + 1
            return true
        }
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 4) {
            if (depth > 0 && !add("Caused by: " + cur.javaClass.simpleName.ifEmpty { "Throwable" })) break
            val fs = frames(cur)
            for (f in fs.take(if (depth == 0) STACK_LINES else 4)) if (!add(frameLine(f))) break
            cur = cur.cause?.takeIf { it !== cur }
            depth++
        }
        return out.joinToString("\n")
    }

    /** Quelle und Zeile: erster Eintrag aus dem App-Paket (sonst der erste ueberhaupt) */
    fun origin(fs: List<Frame>, pkg: String = "io.github.yggdrasil_sh.saga"): Pair<String, Int>? {
        val f = fs.firstOrNull { it.cls.startsWith(pkg) && it.file != null } ?: fs.firstOrNull { it.file != null } ?: return null
        val file = f.file?.takeIf { FILE_OK.matches(it) } ?: return null
        return file to f.line
    }

    // ------------------------------------------------------------------------------------------------- Endpunkt-Muster

    /** feste Bestandteile der Saga-Pfade (Server 2.5); alles andere wird :x (Zahlen :id) - im Zweifel weniger */
    val ROUTE_WORDS: Set<String> = ("account active ai albums alive analyze answer api api-tokens app apps archive ask assets auth " +
        "authz avatar backlinks backup blocks bookmarks bundle calendar calendars call callback calls cancel caps capture " +
        "categories cert chart chat chats check checkpoint clear-done client collab comments conf config confirm connect " +
        "contacts copy ctl dashboard databases dbs decline delete deliveries device devices diag digest directory discard " +
        "disconnect done duplicate editor empty end events exchange export export.csv f fav favicon fed fed-got fed-need " +
        "fed-serve federation files flow forget formula gallery generate geocode graph habit handoff healthz hello hide " +
        "history horn i18n icon identity immich import info internal invite join key keys lang leave link list live log " +
        "login logout manifest.webmanifest mcp me media members messages moderate move msgs nc nextcloud notes notifications " +
        "options page page-image pages pair passkey passkeys password people persona pins presence preview privacy " +
        "public-name publish push query reaction reactions read reauth receipts recipients recovery register remote report " +
        "request resolve restore resume rotate rows run runs saga search security seen services settings setup share shared " +
        "shares sfu signal sources src sso status subscriptions sw.js target targets tasks tasksource test thumb today token " +
        "tokens totp trash trust typing unfurl unread unsubscribe upload upload-done uploads use v1 v2 version versions " +
        "webfinger webhooks whoami widgets").split(' ').toSet()

    private val INT = Regex("-?\\d+")

    /** "api/notes/12/comments?x=1" bzw. "/api/notes/12" -> "/api/notes/:id/comments"; nie Host, Query oder Fragment */
    fun pathPattern(p0: String): String {
        var p = p0.substringBefore('?').substringBefore('#')
        if ("://" in p) p = p.substringAfter("://").let { r -> r.indexOf('/').let { if (it < 0) "/" else r.substring(it) } }
        if (!p.startsWith("/")) p = "/$p"
        val segs = p.split('/').drop(1).take(11).map { s ->
            when {
                s.isEmpty() -> ""
                INT.matches(s) -> ":id"
                s in ROUTE_WORDS -> s
                else -> ":x"
            }
        }
        return ("/" + segs.joinToString("/")).take(100)
    }

    // ---------------------------------------------------------------------------------------------------- Kopf, Eintrag

    private const val SID_ABC = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Zufallskennung je App-Start (nie aus Geraete-/Werbe-IDs) */
    fun newSid(rnd: java.util.Random = SecureRandom()): String = buildString { repeat(16) { append(SID_ABC[rnd.nextInt(SID_ABC.length)]) } }

    private val VER_BAD = Regex("[^A-Za-z0-9._+\\-]")
    private val OS_BAD = Regex("[^A-Za-z0-9._ ()+;/\\-]")

    fun ver(v: String): String = VER_BAD.replace(v, "").take(40)

    /** "Android 15" - nur System und Hauptversion */
    fun os(release: String?): String {
        val major = release.orEmpty().trim().substringBefore('.').let { OS_BAD.replace(it, "") }.take(10)
        return (if (major.isEmpty()) "Android" else "Android $major").take(60)
    }

    fun dev(smallestWidthDp: Int): String = if (smallestWidthDp >= 600) "tablet" else "phone"

    data class Head(val ver: String, val os: String, val dev: String, val sid: String) {
        fun into(b: kotlinx.serialization.json.JsonObjectBuilder) {
            b.put("v", 1); b.put("app", "android"); b.put("ver", ver(ver)); b.put("os", os)
            if (dev == "phone" || dev == "tablet" || dev == "desktop") b.put("dev", dev)
            b.put("sid", sid)
        }
    }

    data class Item(
        val k: String, val msg: String, val stack: String? = null, val src: String? = null, val line: Int? = null,
        val screen: String? = null, val n: Int = 1, val t: Long,
    ) {
        val key: String get() = "$k|$msg|${src.orEmpty()}|${line ?: 0}"

        fun json(): JsonObject = buildJsonObject {
            put("k", k); put("msg", msg)
            stack?.takeIf { it.isNotEmpty() }?.let { put("stack", it) }
            src?.takeIf { it.isNotEmpty() }?.let { put("src", it) }
            line?.takeIf { it >= 0 }?.let { put("line", it) }
            screen?.takeIf { SCREEN.matches(it) }?.let { put("screen", it) }
            if (n > 1) put("n", n)
            put("t", t.coerceIn(0, T_MAX))
        }

        /** derselbe Eintrag als Ringpuffer-Ereignis (k=err, Art in kind) */
        fun event(): JsonObject = buildJsonObject {
            put("t", t.coerceIn(0, T_MAX)); put("k", "err"); put("kind", k); put("msg", msg)
            src?.takeIf { it.isNotEmpty() }?.let { put("src", it) }
            line?.takeIf { it >= 0 }?.let { put("line", it) }
            screen?.takeIf { SCREEN.matches(it) }?.let { put("screen", it) }
        }
    }

    /** Eintrag aus einer Ausnahme (Bereinigung, Stack, Quelle) */
    fun item(k: String, e: Throwable, t: Long, screen: String? = null, msg: String? = null): Item {
        val o = origin(frames(e))
        return Item(
            k = if (k in KINDS) k else "error", msg = (msg?.let { scrub(it) } ?: message(e)).ifEmpty { "Error" },
            stack = stack(e).ifEmpty { null }, src = o?.first, line = o?.second?.takeIf { it >= 0 }, screen = screen, t = t,
        )
    }

    /** Bericht fuer POST api/client/log (<= 16 KB, <= 20 Eintraege; zu grosse Eintraege hinten weg) */
    fun logBody(head: Head, items: List<Item>): JsonObject? {
        if (items.isEmpty()) return null
        var its = items.take(ITEMS)
        fun body() = buildJsonObject { head.into(this); put("items", JsonArray(its.map { it.json() })) }
        var b = body()
        while (b.toString().toByteArray().size > LOG_MAX - 1024 && its.size > 1) {
            its = its.dropLast(1)
            b = body()
        }
        if (b.toString().toByteArray().size > LOG_MAX) {           // ein einzelner zu grosser Eintrag: ohne Stack
            its = listOf(its[0].copy(stack = null))
            b = body()
        }
        return b
    }

    /** Diagnose fuer POST api/client/diag (<= 64 KB, aelteste Ereignisse zuerst weg); note nur hier */
    fun diagBody(head: Head, events: List<JsonObject>, note: String?): JsonObject {
        val n = note?.trim()?.take(NOTE_MAX).orEmpty()
        var ev = events.takeLast(RING)
        fun body() = buildJsonObject {
            head.into(this)
            put("ev", JsonArray(ev))
            if (n.isNotEmpty()) put("note", n)
        }
        var b = body()
        while (b.toString().toByteArray().size > DIAG_MAX - 2048 && ev.isNotEmpty()) {
            ev = ev.drop(maxOf(1, ev.size / 20))
            b = body()
        }
        return b
    }

    // --------------------------------------------------------------------------------------------------- Ereignisse

    fun net(t: Long, method: String, path: String, status: Int, ms: Long?): JsonObject? {
        val m = method.uppercase()
        if (m !in METHODS) return null
        return buildJsonObject {
            put("t", t.coerceIn(0, T_MAX)); put("k", "net"); put("m", m); put("p", pathPattern(path)); put("s", status.coerceIn(0, 999))
            ms?.let { put("ms", it.coerceIn(0, 3_600_000)) }
        }
    }

    fun view(t: Long, screen: String): JsonObject? =
        if (!SCREEN.matches(screen)) null else buildJsonObject { put("t", t.coerceIn(0, T_MAX)); put("k", "view"); put("screen", screen) }

    fun conn(t: Long, ch: String, st: String): JsonObject? =
        if (ch !in CONN_CH || st !in CONN_ST) null
        else buildJsonObject { put("t", t.coerceIn(0, T_MAX)); put("k", "conn"); put("ch", ch); put("st", st) }

    fun save(t: Long, st: String, status: Int? = null): JsonObject? =
        if (st !in SAVE_ST) null
        else buildJsonObject { put("t", t.coerceIn(0, T_MAX)); put("k", "save"); put("st", st); status?.let { put("s", it.coerceIn(0, 999)) } }

    fun life(t: Long, st: String): JsonObject? =
        if (st !in LIFE_ST) null else buildJsonObject { put("t", t.coerceIn(0, T_MAX)); put("k", "life"); put("st", st) }

    /** Ringpuffer der letzten [cap] Ereignisse (aeltere zuerst) */
    class Ring(private val cap: Int = RING) {
        private val buf = ArrayDeque<JsonObject>()

        @Synchronized
        fun add(e: JsonObject?) {
            if (e == null) return
            buf.addLast(e)
            while (buf.size > cap) buf.removeFirst()
        }

        @Synchronized
        fun snapshot(): List<JsonObject> = buf.toList()

        @Synchronized
        fun clear() = buf.clear()

        val size: Int @Synchronized get() = buf.size
    }

    // ------------------------------------------------------------------------------------------- Sammeln und Drosseln

    /**
     * Wartende Fehler: gleiche zaehlen (n), hoechstens [ITEMS] verschiedene; hoechstens ein Bericht je [RATE] ms.
     * 429 -> fruehestens nach retry_s (nicht wiederholen), 404 -> aus bis zum naechsten Start.
     */
    class Pending(private val rate: Long = RATE) {
        private val items = LinkedHashMap<String, Item>()
        var lastSent: Long? = null
            private set
        private var notBefore = 0L
        var off = false
            private set

        val size: Int @Synchronized get() = items.size

        @Synchronized
        fun add(it: Item): Boolean {
            if (off) return false
            val old = items[it.key]
            if (old != null) {
                items[it.key] = old.copy(n = minOf(100_000, old.n + it.n))
                return true
            }
            if (items.size >= ITEMS) return false
            items[it.key] = it
            return true
        }

        /** ms bis zum naechsten erlaubten Bericht (0 = jetzt) */
        @Synchronized
        fun wait(now: Long): Long {
            val a = lastSent?.let { it + rate - now } ?: 0L
            return maxOf(0L, a, notBefore - now)
        }

        /** Eintraege fuer einen Bericht entnehmen (oder null: nichts da / noch gesperrt) */
        @Synchronized
        fun take(now: Long): List<Item>? {
            if (off || items.isEmpty() || wait(now) > 0) return null
            val out = items.values.toList()
            items.clear()
            lastSent = now
            return out
        }

        /** Antwort des Servers auswerten */
        @Synchronized
        fun result(now: Long, code: Int, body: String = "") {
            when (code) {
                404 -> { off = true; items.clear() }
                429 -> notBefore = now + 1000L * (retryS(body) ?: 60).coerceIn(1, 86_400)
            }
        }

        /** ein Bericht ging an diesem Wartespeicher vorbei hinaus (Absturz-Notizen beim Start) - zaehlt fuer die 30 s */
        @Synchronized
        fun markSent(now: Long) { lastSent = now }

        @Synchronized
        fun clear() = items.clear()

        @Synchronized
        fun disable() { off = true; items.clear() }
    }

    fun retryS(body: String): Int? = try {
        ((json.parseToJsonElement(body) as? JsonObject)?.get("retry_s") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
    } catch (_: Exception) {
        null
    }

    /** Kennung aus der Antwort der Diagnose ({"ok": true, "id": "D-7K3FM"}) */
    fun diagId(body: String): String? = try {
        ((json.parseToJsonElement(body) as? JsonObject)?.get("id") as? JsonPrimitive)?.content?.takeIf { DIAG_ID.matches(it) }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------------------------------------ Absturz-Notiz

    /** eine Zeile der Absturz-Notiz (nur bereinigter Text, kein Speicherabbild) */
    fun crashLine(e: Throwable, t: Long, screen: String?): String = json.encodeToString(JsonElement.serializer(), item("crash", e, t, screen).json())

    /** Notiz-Zeilen -> Eintraege (kaputte Zeilen verwerfen, Felder wie im Vertrag pruefen) */
    fun parseCrash(text: String): List<Item> = text.lineSequence().mapNotNull { ln ->
        if (ln.isBlank()) return@mapNotNull null
        try {
            val o = json.parseToJsonElement(ln) as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun i(k: String) = (o[k] as? JsonPrimitive)?.content?.toLongOrNull()
            val k = s("k")?.takeIf { it == "crash" || it == "anr" } ?: return@mapNotNull null
            val msg = s("msg")?.take(MSG_MAX)?.ifEmpty { null } ?: return@mapNotNull null
            Item(
                k = k, msg = msg, stack = s("stack")?.take(STACK_MAX), src = s("src")?.takeIf { FILE_OK.matches(it) },
                line = i("line")?.toInt()?.takeIf { it >= 0 }, screen = s("screen")?.takeIf { SCREEN.matches(it) },
                n = (i("n") ?: 1).toInt().coerceIn(1, 100_000), t = (i("t") ?: 0).coerceIn(0, T_MAX),
            )
        } catch (_: Exception) {
            null
        }
    }.toList().let { all -> dedupe(all) }

    /** gleiche Abstuerze zusammenfassen (n), hoechstens [ITEMS] */
    fun dedupe(list: List<Item>): List<Item> {
        val m = LinkedHashMap<String, Item>()
        for (it in list) {
            val o = m[it.key]
            if (o != null) m[it.key] = o.copy(n = minOf(100_000, o.n + it.n)) else if (m.size < ITEMS) m[it.key] = it
        }
        return m.values.toList()
    }

    /** beendeter Prozess (ApplicationExitInfo): nur Grund und Zeit; REASON_CRASH meldet schon die eigene Notiz */
    data class Exit(val reason: Int, val timestamp: Long)

    fun exitItems(exits: List<Exit>, after: Long, prevStart: Long?): List<Item> = exits
        .filter { it.timestamp > after }
        .sortedBy { it.timestamp }
        .mapNotNull { e ->
            val t = prevStart?.let { (e.timestamp - it).coerceIn(0, T_MAX) } ?: 0L
            when (e.reason) {
                EXIT_ANR -> Item(k = "anr", msg = "ANR: main thread blocked", t = t)
                EXIT_CRASH_NATIVE -> Item(k = "crash", msg = "NativeCrash: exit signal", t = t)
                else -> null
            }
        }
        .let { dedupe(it) }
}
