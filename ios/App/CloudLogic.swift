import Foundation

// Saga 2.3, Apps Teil 3: persoenliches Backup ("Mein Backup") und Nextcloud-Dateien in Seiten - reine Logik
// (ohne SwiftUI, mit swiftc pruefbar: Tests/Logic/CloudTests.swift). Vertrag: docs/APP-API.md im Server-Repo,
// Abschnitte "Persoenliches Backup (2.3)" und "Nextcloud-Dateien in Seiten (2.3)". Aeltere Server (2.1/2.2) kennen die
// Endpunkte nicht (404) -> die Apps blenden die Abschnitte aus; Bloecke vom Typ "nextcloud" bleiben beim Speichern
// unveraendert (wie jeder unbekannte Block).

/// Login Flow v2 (Nextcloud) ueber den Saga-Server: {state: wait|ok|err, login, message}
struct LoginFlow: Equatable {
    var state = ""
    var login: String?
    var message: String?
    init() {}
    init(_ v: Any?) {
        let d = v as? [String: Any] ?? [:]
        state = d["state"] as? String ?? ""
        login = CloudLogic.httpURL(d["login"] as? String)?.absoluteString
        message = ShareJSON.str(d["message"])
    }
    /// Anmeldung in Nextcloud laeuft (Link im Browser offen, Server fragt ab)
    var waiting: Bool { state == "wait" && login != nil }
}

// MARK: - Mein Backup

struct BackupRun: Equatable, Identifiable {
    var started: Double
    var finished: Double?
    var trigger: String
    var name: String
    var ok: Bool
    var size: Int?
    var step: String?
    var error: String?
    var id: String { "\(started)|\(name)" }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let s = ShareJSON.num(d["started"]) else { return nil }
        started = s; finished = ShareJSON.num(d["finished"]); trigger = d["trigger"] as? String ?? ""; name = d["name"] as? String ?? ""
        ok = ShareJSON.bool(d["ok"]); size = ShareJSON.int(d["size"]); step = ShareJSON.str(d["step"]); error = ShareJSON.str(d["error"])
    }
    var failed: Bool { finished != nil && !ok }
}

struct BackupTarget: Equatable {
    var kind: String
    var address: String
    var user: String?
    var folder: String
    var flow: Bool
    var allowed: Bool
    init?(_ v: Any?) {
        guard let d = v as? [String: Any] else { return nil }
        kind = d["kind"] as? String ?? "webdav"; address = d["address"] as? String ?? ""; user = ShareJSON.str(d["user"])
        folder = d["folder"] as? String ?? ""; flow = ShareJSON.bool(d["flow"]); allowed = d["allowed"].map(ShareJSON.bool) ?? true
    }
    var isNextcloud: Bool { kind == "nextcloud" }
}

struct BackupKey: Equatable, Identifiable {
    var short: String
    var fp: String
    var id: String { fp }
}

/// Eigene Seite fuer die Ausschluss-Auswahl (GET ?pages=1)
struct BackupPage: Equatable, Identifiable {
    var id: Int
    var title: String
    var icon: String?
    var parent: Int?
    var label: String { (icon.map { $0.hasPrefix("/") ? "" : $0 + " " } ?? "") + (title.isEmpty ? String(localized: "Unbenannt") : title) }
}

struct BackupTreeEntry: Equatable, Identifiable {
    var page: BackupPage
    var depth: Int
    var id: Int { page.id }
}

struct BackupState: Equatable {
    var allowed = false
    var ready = false
    var running = false
    var lastOK: Double?
    var lastAttempt: Double?
    var next: Double?
    var target: BackupTarget?
    var keys: [BackupKey] = []
    var schedule = "off"
    var effectiveSchedule = "off"
    var time = "03:00"
    var weekday = 0                      // 0 = Montag
    var keep = 7
    /// Auswahl der Kategorien bzw. ausgeschlossene Seitenbaeume - nil: Server kennt das nicht (dann ausblenden)
    var cats: [String: Bool]?
    var exclude: [Int]?
    var runs: [BackupRun] = []
    var maxMB: Int?
    var maxSchedule: String?
    var flow = LoginFlow()
    var ageOK = true
    var pages: [BackupPage] = []
    init() {}
    init?(data: Data) {
        guard let j = ShareJSON.obj(data), j["allowed"] != nil else { return nil }
        allowed = ShareJSON.bool(j["allowed"]); ready = ShareJSON.bool(j["ready"]); running = ShareJSON.bool(j["running"])
        lastOK = ShareJSON.num(j["last_ok"]); lastAttempt = ShareJSON.num(j["last_attempt"]); next = ShareJSON.num(j["next"])
        target = BackupTarget(j["target"])
        keys = (j["keys"] as? [Any] ?? []).compactMap { v in
            guard let d = v as? [String: Any], let fp = ShareJSON.str(d["fp"]) else { return nil }
            return BackupKey(short: d["short"] as? String ?? String(fp.prefix(12)), fp: fp)
        }
        schedule = j["schedule"] as? String ?? "off"; effectiveSchedule = j["effective_schedule"] as? String ?? schedule
        time = ShareJSON.str(j["time"]) ?? "03:00"; weekday = ShareJSON.int(j["weekday"]) ?? 0; keep = ShareJSON.int(j["keep"]) ?? 7
        if let c = j["cats"] as? [String: Any] { cats = c.mapValues(ShareJSON.bool) }
        if let e = j["exclude"] as? [Any] { exclude = e.compactMap(ShareJSON.int) }
        runs = (j["runs"] as? [Any] ?? []).compactMap(BackupRun.init)
        let l = j["limits"] as? [String: Any] ?? [:]
        maxMB = ShareJSON.int(l["max_mb"]); maxSchedule = ShareJSON.str(l["max_schedule"])
        flow = LoginFlow(j["flow"]); ageOK = j["age_ok"].map(ShareJSON.bool) ?? true
        pages = (j["pages"] as? [Any] ?? []).compactMap { v in
            guard let d = v as? [String: Any], let id = ShareJSON.int(d["id"]) else { return nil }
            return BackupPage(id: id, title: d["title"] as? String ?? "", icon: ShareJSON.str(d["icon"]), parent: ShareJSON.int(d["parent"]))
        }
    }
    /// Ausschluesse (Kategorien, Seitenbaeume) nur, wenn der Server sie meldet
    var canSelectContent: Bool { cats != nil && exclude != nil }
    /// Fehler des letzten (fertigen) Laufs
    var lastError: String? { runs.first.flatMap { $0.failed ? ($0.error ?? String(localized: "Fehler")) : nil } }
    /// Fortschritt des laufenden Backups
    var runningStep: String? { running ? (runs.first?.step ?? "…") : nil }
    /// Status neu abfragen (Lauf oder Nextcloud-Anmeldung offen)
    var needsPolling: Bool { running || flow.waiting }
}

enum CloudLogic {
    /// Kategorien in der Reihenfolge des Webs (Schluessel, Text)
    static var backupCats: [(String, String)] {
        [("pages", String(localized: "Seiten und Unterseiten (inkl. Datenbanken mit Einträgen)")), ("templates", String(localized: "Vorlagen")),
         ("archive", String(localized: "Archiv")), ("trash", String(localized: "Papierkorb")), ("versions", String(localized: "Versionen")),
         ("files", String(localized: "Eigene Dateien und Bilder")), ("comments", String(localized: "Eigene Kommentare auf eigenen Seiten")),
         ("comments_others", String(localized: "Kommentare anderer Personen auf meinen Seiten")), ("tasks", String(localized: "Aufgaben")),
         ("bookmarks", String(localized: "Lesezeichen")), ("settings", String(localized: "Profil und Einstellungen"))]
    }
    static let schedules = ["off", "weekly", "daily"]
    static func scheduleLabel(_ s: String) -> String {
        switch s {
        case "weekly": return String(localized: "wöchentlich")
        case "daily": return String(localized: "täglich")
        default: return String(localized: "aus")
        }
    }
    /// "taeglich" nur, wenn der Admin es erlaubt (limits.max_schedule)
    static func scheduleAllowed(_ s: String, max: String?) -> Bool {
        if max == "off" { return s == "off" }
        return s != "daily" || max != "weekly"
    }
    /// Wochentage ab Montag (Server: 0 = Montag)
    static func weekdayNames(_ cal: Calendar = Calendar(identifier: .gregorian), locale: Locale = .current) -> [String] {
        var c = cal; c.locale = locale
        let s = c.standaloneWeekdaySymbols                       // [Sonntag, Montag, ...]
        return Array(s[1...]) + [s[0]]
    }
    static func clampKeep(_ n: Int) -> Int { min(60, max(1, n)) }
    /// "HH:MM" pruefen (Server erwartet genau das)
    static func validTime(_ s: String) -> Bool {
        let p = s.split(separator: ":")
        guard p.count == 2, p[0].count == 2, p[1].count == 2, let h = Int(p[0]), let m = Int(p[1]) else { return false }
        return (0..<24).contains(h) && (0..<60).contains(m)
    }
    static func planBody(schedule: String, time: String, weekday: Int, keep: Int) -> [String: Any] {
        ["schedule": schedule, "time": time, "weekday": min(6, max(0, weekday)), "keep": clampKeep(keep)]
    }
    static func contentBody(cats: [String: Bool], exclude: [Int]) -> [String: Any] { ["cats": cats, "exclude": exclude] }

    /// Eigene Seiten als Baum (fuer die Auswahl "Seiten nicht sichern"), Tiefe wie im Web hoechstens 12
    static func pageTree(_ pages: [BackupPage]) -> [BackupTreeEntry] {
        let ids = Set(pages.map(\.id))
        var kids: [Int: [BackupPage]] = [:]
        for p in pages { kids[p.parent.flatMap { ids.contains($0) ? $0 : nil } ?? 0, default: []].append(p) }
        var out: [BackupTreeEntry] = []
        var seen = Set<Int>()
        func walk(_ k: Int, _ d: Int) {
            for p in kids[k] ?? [] where !seen.contains(p.id) {
                seen.insert(p.id); out.append(BackupTreeEntry(page: p, depth: d))
                if d < 12 { walk(p.id, d + 1) }
            }
        }
        walk(0, 0)
        return out
    }

    /// Zum Bestaetigen des erzeugten Schluessels: die letzten 6 Zeichen
    /// (age-keygen: Kommentarzeilen "# created/# public key", dann der Schluessel - zaehlt die letzte Zeile, wie der Server)
    static func last6(_ privateKey: String) -> String {
        let line = privateKey.split(whereSeparator: \.isNewline).last.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
        return String(line.suffix(6)).uppercased()
    }
    /// Oeffentlicher age-Schluessel? (privater wird nie eingetragen - der Server lehnt ihn ab, die App warnt vorher)
    static func looksPrivate(_ s: String) -> Bool { s.uppercased().contains("AGE-SECRET-KEY-") }

    /// "Jetzt sichern" abgelehnt: 409 (kein Ziel/Schluessel, laeuft schon), 429 (Mindestabstand, retry_at)
    static func runError(code: Int, data: Data) -> String {
        let j = ShareJSON.obj(data)
        let msg = j.flatMap { ShareJSON.str($0["error"]) }
        if code == 429, msg == nil, let at = j.flatMap({ ShareJSON.num($0["retry_at"]) }) {   // Server nennt den Zeitpunkt sonst selbst
            let t = Date(timeIntervalSince1970: at).formatted(date: .abbreviated, time: .shortened)
            return String(localized: "Wieder möglich ab \(t).")
        }
        return msg ?? String(localized: "Server-Fehler \(code)")
    }

    static func size(_ n: Int?) -> String {
        guard let n else { return "" }
        return ByteCountFormatter.string(fromByteCount: Int64(n), countStyle: .file)
    }
    /// Nur http(s)-Adressen (kein javascript: o. ae.)
    static func httpURL(_ s: String?) -> URL? {
        guard let s, let u = URL(string: s.trimmingCharacters(in: .whitespaces)), let sc = u.scheme?.lowercased(),
              sc == "http" || sc == "https", u.host != nil else { return nil }
        return u
    }
    static func q(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}

// MARK: - Nextcloud: Verknuepfung

struct NCSettings: Equatable {
    var enabled = false
    var connected = false
    var me: String?
    var server: String?
    var user: String?
    var since: Double?
    var used: Double?
    var suggest: String?
    var flow = LoginFlow()
    init() {}
    init?(data: Data) {
        guard let j = ShareJSON.obj(data), j["enabled"] != nil || j["connected"] != nil else { return nil }
        enabled = ShareJSON.bool(j["enabled"]); connected = ShareJSON.bool(j["connected"]); me = ShareJSON.str(j["me"])
        server = ShareJSON.str(j["server"]); user = ShareJSON.str(j["user"]); since = ShareJSON.num(j["since"]); used = ShareJSON.num(j["used"])
        suggest = ShareJSON.str(j["suggest"]); flow = LoginFlow(j["flow"])
    }
    /// Adresse ohne Schema (Anzeige)
    var host: String { (server ?? "").replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "") }
}

// MARK: - Nextcloud: Block in Seiten

/// Block "nextcloud" (Editor.js-Daten). author und sig setzt nur der Server; die App schreibt bestehende Bloecke
/// unveraendert zurueck und legt neue nur aus der eigenen verknuepften Nextcloud an.
struct NCBlock: Equatable {
    var inst: String
    var fileid: String
    var kind: String
    var name: String
    var mime: String
    var size: Int?
    var mtime: Double?
    var author: String
    var signed: Bool
    init?(_ data: [String: Any]) {
        guard let f = data["fileid"].flatMap({ v -> String? in (v as? String) ?? ShareJSON.int(v).map(String.init) }), !f.isEmpty else { return nil }
        fileid = f
        inst = data["inst"] as? String ?? ""; kind = data["kind"] as? String ?? "file"
        name = CloudLogic.decodeEntities(data["name"] as? String ?? ""); mime = data["mime"] as? String ?? ""
        size = ShareJSON.int(data["size"]); mtime = ShareJSON.num(data["mtime"]); author = data["author"] as? String ?? ""
        signed = ShareJSON.str(data["sig"]) != nil
    }
    var isFolder: Bool { kind == "folder" }
    var isImage: Bool { kind == "image" }
    var displayName: String { name.isEmpty ? String(localized: "Nextcloud-Datei") : name }
    /// "In Nextcloud oeffnen": {inst}/f/{fileid} - nur http(s), nicht auf Seiten anderer Saga-Server
    func openURL(remotePage: Bool) -> URL? {
        guard !remotePage, let base = CloudLogic.httpURL(inst) else { return nil }
        return URL(string: base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/f/" + CloudLogic.q(fileid))
    }
    /// Vorschau/Datei ueber den Saga-Proxy (Konto der Person, die den Block eingefuegt hat). `v`: Stand der Datei
    /// (mtime) - nur fuer den Zwischenspeicher der App, der Server ignoriert ihn.
    func proxyPath(note: Int, w: Int? = nil, download: Bool = false, v: Double? = nil) -> String {
        var p = "api/notes/\(note)/nc/\(CloudLogic.q(fileid))?a=\(CloudLogic.q(author))"
        if let w { p += "&w=\(min(2000, max(16, w)))" }
        if download { p += "&dl=1" }
        if let v, !download { p += "&v=\(Int(v))" }
        return p
    }
    /// Schluessel wie im Web (Autor|Nextcloud|Datei)
    var key: String { [author, inst, fileid].joined(separator: "|") }
}

struct NCEntry: Equatable { var name: String; var dir: Bool; var size: Int? }

/// Status eines Blocks (GET /api/notes/{id}/nc)
struct NCStatusItem: Equatable {
    var fileid: String
    var author: String
    var ok: Bool
    var name: String?
    var size: Int?
    var mtime: Double?
    var mime: String?
    var dir: Bool
    var preview: Bool
    var entries: [NCEntry]
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let f = d["fileid"].flatMap({ v -> String? in (v as? String) ?? ShareJSON.int(v).map(String.init) }) else { return nil }
        fileid = f; author = d["author"] as? String ?? ""; ok = ShareJSON.bool(d["ok"]); name = ShareJSON.str(d["name"])
        size = ShareJSON.int(d["size"]); mtime = ShareJSON.num(d["mtime"]); mime = ShareJSON.str(d["mime"])
        dir = ShareJSON.bool(d["dir"]); preview = ShareJSON.bool(d["preview"])
        entries = (d["entries"] as? [Any] ?? []).compactMap { e in
            guard let x = e as? [String: Any], let n = x["name"] as? String else { return nil }
            return NCEntry(name: n, dir: ShareJSON.bool(x["dir"]), size: ShareJSON.int(x["size"]))
        }
    }
}

struct NCStatus: Equatable {
    var enabled = true
    var items: [NCStatusItem] = []
    var failed = false
    init() {}
    init(failed: Bool) { self.failed = failed }
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        enabled = j["enabled"].map(ShareJSON.bool) ?? true
        items = (j["items"] as? [Any] ?? []).compactMap(NCStatusItem.init)
    }
    /// Eintrag zum Block (auf Seiten anderer Server steht in author ein Platzhalter -> nur die Datei-ID zaehlt)
    func item(for b: NCBlock, remotePage: Bool) -> NCStatusItem? {
        items.first { $0.fileid == b.fileid && ($0.author == b.author || remotePage) }
    }
}

/// Eintrag der Dateiauswahl (GET /api/nextcloud/list|search)
struct NCItem: Equatable, Identifiable {
    var fileid: String
    var name: String
    var path: String
    var dir: Bool
    var mime: String
    var size: Int?
    var mtime: Double?
    var preview: Bool
    var kind: String
    var inst: String
    var id: String { fileid + "|" + path }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let f = d["fileid"].flatMap({ v -> String? in (v as? String) ?? ShareJSON.int(v).map(String.init) }) else { return nil }
        fileid = f; name = d["name"] as? String ?? ""; path = d["path"] as? String ?? ""; dir = ShareJSON.bool(d["dir"])
        mime = d["mime"] as? String ?? ""; size = ShareJSON.int(d["size"]); mtime = ShareJSON.num(d["mtime"]); preview = ShareJSON.bool(d["preview"])
        kind = ShareJSON.str(d["kind"]) ?? (dir ? "folder" : (mime.hasPrefix("image/") ? "image" : "file")); inst = d["inst"] as? String ?? ""
    }
    /// Ordner, in dem der Eintrag liegt ("/" fuer die oberste Ebene)
    var folder: String {
        guard let r = path.range(of: "/", options: .backwards) else { return "/" }
        let f = String(path[..<r.lowerBound]); return f.isEmpty ? "/" : f
    }
}

extension CloudLogic {
    /// SF-Symbol je Art (wie die Emojis im Web: Ordner, Bild, PDF, Video, Audio, Tabelle, Praesentation, Archiv, Datei)
    static func symbol(mime: String, kind: String, dir: Bool = false) -> String {
        if dir || kind == "folder" { return "folder" }
        let m = mime.lowercased()
        if m.hasPrefix("image/") || kind == "image" { return "photo" }
        if m == "application/pdf" { return "doc.richtext" }
        if m.hasPrefix("video/") { return "film" }
        if m.hasPrefix("audio/") { return "music.note" }
        if m.contains("spreadsheet") || m.contains("excel") || m.contains("csv") { return "tablecells" }
        if m.contains("presentation") || m.contains("powerpoint") { return "rectangle.on.rectangle.angled" }
        if m.contains("zip") || m.contains("compressed") || m.contains("tar") { return "doc.zipper" }
        return "doc"
    }
    /// Neuer Block aus der Auswahl ("Als Verweis einfuegen"): author = eigenes Konto (wie im Web), ohne sig;
    /// Freigaben (veroeffentlicht, verbundene Server) aus - aendern nur im Web
    static func newBlockData(_ it: NCItem, me: String?) -> [String: Any] {
        var d: [String: Any] = ["inst": it.inst, "fileid": it.fileid, "kind": it.kind, "name": it.name, "mime": it.mime, "public": false, "fed": false]
        if let s = it.size { d["size"] = s }
        if let m = it.mtime { d["mtime"] = m }
        if let me { d["author"] = me }
        return d
    }
    /// Kopie ("Als Kopie uebernehmen", POST /api/nextcloud/import -> {file}): Bild- bzw. Datei-Block wie im Web
    static func copyBlock(file f: [String: Any], id: String) -> [String: Any]? {
        guard let url = f["url"] as? String, !url.isEmpty else { return nil }
        if (f["mime"] as? String ?? "").hasPrefix("image/") {
            return ["id": id, "type": "image", "data": ["file": ["url": url], "caption": "", "withBorder": false, "withBackground": false, "stretched": false]]
        }
        var file: [String: Any] = ["url": url, "name": f["name"] ?? ""]
        if let s = f["size"] { file["size"] = s }
        if let e = f["extension"] { file["extension"] = e }
        return ["id": id, "type": "attaches", "data": ["file": file, "title": f["name"] ?? ""]]
    }
    /// Alle Nextcloud-Bloecke (auch in Ausklappbar/Spalten) als Schluessel
    static func ncKeys(_ blocks: Any?) -> Set<String> {
        var out = Set<String>()
        func walk(_ v: Any?) {
            for case let b as [String: Any] in (v as? [Any] ?? []) {
                let d = b["data"] as? [String: Any] ?? [:]
                if b["type"] as? String == "nextcloud", let nb = NCBlock(d) { out.insert(nb.key) }
                walk(d["blocks"])
                for case let c as [String: Any] in (d["cols"] as? [Any] ?? []) { walk(c["blocks"]) }
            }
        }
        walk(blocks)
        return out
    }
    /// Speichern abgelehnt, weil ein neuer Nextcloud-Block nicht eingefuegt werden darf (fremde Datei kopiert: 403,
    /// nicht verbunden: 409, falsche Nextcloud: 400)? -> verstaendliche Meldung, sonst nil (normale Fehlerbehandlung).
    /// Ein 409 mit {"error": "conflict"} ist immer der Revisionskonflikt.
    static func saveRejection(code: Int, data: Data?, saved: Set<String>, now: Set<String>) -> String? {
        guard [400, 403, 409].contains(code), !now.subtracting(saved).isEmpty else { return nil }
        let msg = data.flatMap(ShareJSON.obj).flatMap { ShareJSON.str($0["error"]) }
        if msg == "conflict" { return nil }
        if code == 409 && msg == nil { return nil }
        let reason: String
        switch code {
        case 403: reason = msg ?? String(localized: "Nextcloud-Dateien anderer Personen lassen sich nicht in andere Seiten kopieren.")
        case 409: reason = msg ?? String(localized: "Nextcloud nicht verbunden (Einstellungen → Nextcloud).")
        default: reason = msg ?? String(localized: "Diese Datei liegt nicht in deinem verbundenen Nextcloud.")
        }
        return String(localized: "Nicht gespeichert: \(reason) Entferne die Nextcloud-Datei aus der Seite, dann wird wieder gespeichert. Deine Änderungen bleiben auf diesem Gerät als Entwurf erhalten.")
    }
    /// &amp; usw. (der Server speichert Texte HTML-kodiert)
    static func decodeEntities(_ s: String) -> String {
        guard s.contains("&") else { return s }
        return s.replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&#39;", with: "'").replacingOccurrences(of: "&amp;", with: "&")
    }

    // MARK: Benachrichtigungen

    /// Systemmeldungen ohne Person/Seite (Backups): eigenes Symbol, Titel statt Seitenzeile
    static func isSystemKind(_ kind: String) -> Bool { kind == "backup.failed" || kind == "userbackup.failed" }
    /// Titel einer Systemmeldung, falls der Server (aelter) keinen mitschickt
    static func systemTitle(_ kind: String) -> String {
        kind == "backup.failed" ? String(localized: "Backups") : String(localized: "Mein Backup")
    }
}
