import Foundation

// Saga 2.3, Apps Teil 3: Server-Wege fuer "Mein Backup" und Nextcloud (ohne SwiftUI). Die Ansichten (CloudSettings.swift,
// NextcloudBlock.swift) rufen sie mit Store.apiResponse auf, der E2E-Test (Tests/CloudE2E) mit einer eigenen Sitzung.
// Alle Anfragen wirken nur auf das eigene Konto (keine Konto-Kennung in der Anfrage).

/// (Methode, Pfad ohne fuehrenden "/", JSON-Koerper) -> (Daten, HTTP-Status)
typealias CloudAPI = (_ method: String, _ path: String, _ body: [String: Any]?) async throws -> (Data, Int)

struct CloudFailure: LocalizedError, Equatable {
    let code: Int
    let message: String
    var errorDescription: String? { message }
}

/// Backup im eigenen Ziel (GET /api/account/backup/remote)
struct RemoteBackup: Equatable, Identifiable {
    var name: String
    var time: Double?
    var size: Int?
    var complete: Bool
    var id: String { name }
}

/// Einspielen ins eigene Konto: Optionen und Ergebnis
struct RestoreOptions: Equatable {
    var folder = true
    var trash = false
    var versions = true
    var tasks = false
    var bookmarks = false
}

enum CloudFlow {
    static func message(_ code: Int, _ d: Data) -> String {
        ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? String(localized: "Server-Fehler \(code)")
    }
    @discardableResult
    static func check(_ r: (Data, Int)) throws -> Data {
        guard (200..<300).contains(r.1) else { throw CloudFailure(code: r.1, message: message(r.1, r.0)) }
        return r.0
    }

    // MARK: Mein Backup

    /// Status - nil: Server kennt persoenliche Backups nicht (vor 2.3)
    static func backupStatus(_ api: CloudAPI, pages: Bool = false) async throws -> BackupState? {
        let r = try await api("GET", "api/account/backup" + (pages ? "?pages=1" : ""), nil)
        if r.1 == 404 { return nil }
        return BackupState(data: try check(r))                // keine Status-Antwort (aelterer Server): wie 404
    }
    static func backupRun(_ api: CloudAPI) async throws {
        let r = try await api("POST", "api/account/backup/run", [:])
        guard (200..<300).contains(r.1) else { throw CloudFailure(code: r.1, message: CloudLogic.runError(code: r.1, data: r.0)) }
    }
    /// Verbinden: Nextcloud ohne Benutzer -> Login-Link (im Browser oeffnen, dann Status abfragen), sonst nil (verbunden)
    static func backupConnect(_ api: CloudAPI, url: String, user: String, password: String, folder: String) async throws -> URL? {
        var b: [String: Any] = ["url": url.trimmingCharacters(in: .whitespaces)]
        let u = user.trimmingCharacters(in: .whitespaces), f = folder.trimmingCharacters(in: .whitespaces)
        if !u.isEmpty { b["user"] = u; b["password"] = password }
        if !f.isEmpty { b["folder"] = f }
        let d = try check(try await api("POST", "api/account/backup/connect", b))
        return CloudLogic.httpURL(ShareJSON.obj(d)?["flow"] as? String)
    }
    static func backupFlowCancel(_ api: CloudAPI) async throws { try check(try await api("POST", "api/account/backup/flow/cancel", [:])) }
    /// Verbindung pruefen -> freier Platz im Ziel (nil = unbekannt)
    static func backupTest(_ api: CloudAPI) async throws -> Int? {
        let d = try check(try await api("POST", "api/account/backup/test", [:]))
        let free = ShareJSON.obj(d).flatMap { ShareJSON.int($0["free"]) }
        return (free ?? -1) < 0 ? nil : free
    }
    static func backupDisconnect(_ api: CloudAPI) async throws { try check(try await api("DELETE", "api/account/backup/target", nil)) }
    /// Schluesselpaar erzeugen: privater Schluessel NUR in dieser Antwort (nie speichern)
    static func keyGenerate(_ api: CloudAPI) async throws -> (priv: String, pub: String, filename: String) {
        let d = try check(try await api("POST", "api/account/backup/key/generate", [:]))
        let j = ShareJSON.obj(d) ?? [:]
        guard let p = ShareJSON.str(j["private"]) else { throw CloudFailure(code: 200, message: String(localized: "Unerwartete Antwort des Servers")) }
        return (p, j["public"] as? String ?? "", ShareJSON.str(j["filename"]) ?? "saga-backup.key")
    }
    static func keyConfirm(_ api: CloudAPI, last6: String) async throws { try check(try await api("POST", "api/account/backup/key/confirm", ["last6": last6])) }
    static func keyDrop(_ api: CloudAPI) async throws { try check(try await api("POST", "api/account/backup/key/confirm", ["drop": true])) }
    static func keyAdd(_ api: CloudAPI, key: String) async throws {
        try check(try await api("POST", "api/account/backup/key", ["key": key.trimmingCharacters(in: .whitespacesAndNewlines)]))
    }
    static func keyRemove(_ api: CloudAPI, fp: String) async throws { try check(try await api("DELETE", "api/account/backup/key/" + CloudLogic.q(fp), nil)) }
    /// Zeitplan/Aufbewahrung bzw. Auswahl speichern -> neuer Status (ohne Seitenliste)
    static func backupSettings(_ api: CloudAPI, _ body: [String: Any]) async throws -> BackupState? {
        BackupState(data: try check(try await api("PUT", "api/account/backup/settings", body)))
    }
    static func backupRemote(_ api: CloudAPI) async throws -> [RemoteBackup] {
        let d = try check(try await api("GET", "api/account/backup/remote", nil))
        return (ShareJSON.obj(d)?["items"] as? [Any] ?? []).compactMap { v in
            guard let x = v as? [String: Any], let n = ShareJSON.str(x["name"]) else { return nil }
            return RemoteBackup(name: n, time: ShareJSON.num(x["time"]), size: ShareJSON.int(x["size"]), complete: ShareJSON.bool(x["complete"]))
        }
    }
    /// Backup aus dem eigenen Ziel ins eigene Konto einspielen; der private Schluessel geht nur fuer diesen Vorgang mit
    static func restore(_ api: CloudAPI, remote: String, key: String, options o: RestoreOptions) async throws -> (pages: Int, files: Int) {
        var b: [String: Any] = ["remote": remote, "folder": o.folder, "trash": o.trash, "versions": o.versions, "tasks": o.tasks, "bookmarks": o.bookmarks]
        let k = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if !k.isEmpty { b["key"] = k }
        let d = try check(try await api("POST", "api/account/backup/restore", b))
        let j = ShareJSON.obj(d) ?? [:]
        return (ShareJSON.int(j["pages"]) ?? 0, ShareJSON.int(j["files"]) ?? 0)
    }

    // MARK: Nextcloud

    /// Verknuepfung - nil: Server kennt Nextcloud-Dateien nicht (vor 2.3)
    static func ncSettings(_ api: CloudAPI) async throws -> NCSettings? {
        let r = try await api("GET", "api/settings/nextcloud", nil)
        if r.1 == 404 { return nil }
        return NCSettings(data: try check(r))
    }
    /// Verknuepfen: Login-Link fuer den Browser (danach Status abfragen)
    static func ncConnect(_ api: CloudAPI, url: String) async throws -> URL {
        let d = try check(try await api("POST", "api/settings/nextcloud", ["url": url.trimmingCharacters(in: .whitespaces)]))
        guard let u = CloudLogic.httpURL(ShareJSON.obj(d)?["flow"] as? String) else {
            throw CloudFailure(code: 200, message: String(localized: "Unerwartete Antwort des Servers"))
        }
        return u
    }
    static func ncCancel(_ api: CloudAPI) async throws { try check(try await api("POST", "api/settings/nextcloud/cancel", [:])) }
    static func ncDisconnect(_ api: CloudAPI) async throws { try check(try await api("DELETE", "api/settings/nextcloud", nil)) }
    static func ncList(_ api: CloudAPI, path: String) async throws -> [NCItem] {
        items(try check(try await api("GET", "api/nextcloud/list?path=" + CloudLogic.q(path.isEmpty ? "/" : path), nil)))
    }
    /// Suche (leer = zuletzt geaendert)
    static func ncSearch(_ api: CloudAPI, query: String) async throws -> [NCItem] {
        items(try check(try await api("GET", "api/nextcloud/search?q=" + CloudLogic.q(query.trimmingCharacters(in: .whitespaces)), nil)))
    }
    /// Datei/Ordner per ID -> Eintrag (fuer "Diesen Ordner einfuegen")
    static func ncResolve(_ api: CloudAPI, fileid: String) async throws -> NCItem? {
        let d = try check(try await api("POST", "api/nextcloud/resolve", ["fileid": fileid]))
        return NCItem(ShareJSON.obj(d)?["item"])
    }
    /// "Als Kopie uebernehmen": Datei nach Saga kopieren -> {url, name, size, mime, extension}
    static func ncImport(_ api: CloudAPI, fileid: String, note: Int?) async throws -> [String: Any] {
        var b: [String: Any] = ["fileid": fileid]
        if let note { b["note"] = note }
        let d = try check(try await api("POST", "api/nextcloud/import", b))
        guard let f = ShareJSON.obj(d)?["file"] as? [String: Any] else { throw CloudFailure(code: 200, message: String(localized: "Unerwartete Antwort des Servers")) }
        return f
    }
    /// Status aller Nextcloud-Bloecke einer Seite (ab Rolle Lesen)
    static func noteStatus(_ api: CloudAPI, note: Int) async -> NCStatus {
        guard let r = try? await api("GET", "api/notes/\(note)/nc", nil), (200..<300).contains(r.1), let s = NCStatus(data: r.0) else { return NCStatus(failed: true) }
        return s
    }
    private static func items(_ d: Data) -> [NCItem] { (ShareJSON.obj(d)?["items"] as? [Any] ?? []).compactMap(NCItem.init) }
}
