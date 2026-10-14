import Foundation
import CryptoKit

// Reine Speicher-Logik des Seiten-Editors (ohne SwiftUI) - mit `swift` pruefbar (Tests: ~/tmp bzw. Review-Notizen).

/// Aenderungszaehler statt "dirty"-Schalter: gespeichert gilt nur der Stand, der wirklich gesendet wurde.
/// Tippt man waehrend einer laufenden Speicherung weiter, bleibt die Seite ungespeichert.
struct EditTracker: Equatable {
    private(set) var edits = 0
    private(set) var saved = 0
    var dirty: Bool { edits != saved }
    /// jede Eingabe/Strukturaenderung
    mutating func edit() { edits += 1 }
    /// Stand beim Absenden merken
    var snapshot: Int { edits }
    /// Server hat den Stand `sent` bestaetigt - spaetere Eingaben bleiben ungespeichert
    mutating func confirm(_ sent: Int) { saved = max(saved, min(sent, edits)) }
    /// Eigene Aenderungen bewusst verworfen (z. B. Server-Fassung geladen)
    mutating func discard() { saved = edits }
}

/// Speicherungen nacheinander: jede wartet, bis die vorige fertig ist (nie zwei PATCH mit derselben Revision).
@MainActor
final class SerialQueue {
    private var tail: Task<Void, Never>?
    private(set) var pending = 0
    func run(_ op: @escaping @MainActor () async -> Void) async {
        let prev = tail
        pending += 1
        let t = Task { @MainActor in
            await prev?.value
            await op()
        }
        tail = t
        await t.value
        pending -= 1
    }
}

/// Wartezeit bis zum naechsten Speicherversuch nach einem Fehler (5, 10, 20, 40, 60, 60 ... Sekunden)
func saveRetryDelay(_ attempt: Int) -> Double { min(60, 5 * pow(2, Double(max(0, attempt)))) }

/// Lokaler Entwurf einer Seite: ungespeicherte Aenderungen (offline, Fehler) oder Konfliktkopie der eigenen Fassung.
struct PageDraft: Codable, Equatable {
    var title: String
    var body: String                 // Inhalt als JSON wie beim Speichern ({"blocks": [...]})
    var rev: Int                     // Revision, auf der die Aenderung beruht
    var saved: Date
    var conflict: Bool               // true: Server hatte inzwischen eine andere Fassung
}

/// Entwuerfe je Server (Ursprung) und Konto im Programm-Support-Ordner - nicht im Zwischenspeicher, der beim Abmelden
/// geleert wird. Ablage: drafts/<Server>/acct-<Hash des Kontos>/<Seiten-ID>.json. Fruehere Fassungen legten die Dateien
/// direkt in drafts/<Server>/ ab (nur je Server) - die uebernimmt `DraftOwner` einmalig fuer das passende Konto.
struct DraftStore {
    let dir: URL
    /// Nur je Server (alte Ablage)
    init(origin: String, base: URL? = nil) { dir = DraftStore.serverDir(origin, base: base) }
    /// Je Server und Konto
    init(origin: String, account: String, base: URL? = nil) {
        let h = SHA256.hash(data: Data(account.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
        dir = DraftStore.serverDir(origin, base: base).appendingPathComponent("acct-" + h, isDirectory: true)
    }
    static func serverDir(_ origin: String, base: URL?) -> URL {
        let root = base ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let safe = String(origin.map { $0.isLetter || $0.isNumber ? $0 : "_" })
        return root.appendingPathComponent("drafts", isDirectory: true).appendingPathComponent(safe, isDirectory: true)
    }
    /// Entwurfsdateien direkt in diesem Ordner (ohne Unterordner)
    var files: [URL] {
        ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []).filter { $0.pathExtension == "json" }
    }
    private func url(_ id: Int) -> URL { dir.appendingPathComponent("\(id).json") }
    func load(_ id: Int) -> PageDraft? {
        guard let d = try? Data(contentsOf: url(id)) else { return nil }
        let dec = JSONDecoder(); dec.dateDecodingStrategy = .secondsSince1970
        return try? dec.decode(PageDraft.self, from: d)
    }
    func save(_ id: Int, _ draft: PageDraft) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let enc = JSONEncoder(); enc.dateEncodingStrategy = .secondsSince1970
        guard let d = try? enc.encode(draft) else { return }
        #if os(iOS)
        try? d.write(to: url(id), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try? d.write(to: url(id), options: .atomic)
        #endif
    }
    func remove(_ id: Int) { try? FileManager.default.removeItem(at: url(id)) }
}

/// Wem gehoeren die Entwuerfe? Server-Ursprung + Konto (Konto-ID vom Server). Ein anderes Konto sieht fremde Entwuerfe nie.
/// Ist das Konto noch nicht bekannt, gibt es keine Ablage - ausser direkt nach dem Update, solange die alte Anmeldung
/// noch besteht: dann die alte Ablage je Server, die beim Erkennen des Kontos genau diesem Konto uebergeben wird.
struct DraftOwner {
    let defaults: UserDefaults
    var base: URL? = nil
    static let accountKey = "hk.draftAccount"          // "<Ursprung>\n<Konto>"
    static let legacyKey = "hk.draftLegacyOwner"      // Ursprung, dessen alte Entwuerfe der bestehenden Anmeldung gehoeren
    static let migratedKey = "hk.draftAccountMigrated"

    /// Aktuelles Konto, falls es zu diesem Server gehoert
    func account(origin: String) -> String? {
        guard let v = defaults.string(forKey: DraftOwner.accountKey) else { return nil }
        let p = v.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
        return p.count == 2 && p[0] == origin && !p[1].isEmpty ? p[1] : nil
    }
    /// Einmalig nach dem Update: war die App angemeldet, gehoeren die alten Entwuerfe dieses Servers zu genau dieser Anmeldung
    func migrate(origin: String?, signedIn: Bool) {
        guard !defaults.bool(forKey: DraftOwner.migratedKey) else { return }
        defaults.set(true, forKey: DraftOwner.migratedKey)
        if signedIn, let origin, !DraftStore(origin: origin, base: base).files.isEmpty { defaults.set(origin, forKey: DraftOwner.legacyKey) }
    }
    /// Konto erkannt (nach Anmeldung bzw. beim Start): merken; alte Entwuerfe uebernehmen, wenn sie zu dieser Anmeldung gehoeren
    func signedIn(origin: String, account: String) {
        defaults.set(origin + "\n" + account, forKey: DraftOwner.accountKey)
        guard defaults.string(forKey: DraftOwner.legacyKey) == origin else { return }
        defaults.removeObject(forKey: DraftOwner.legacyKey)
        let old = DraftStore(origin: origin, base: base), new = DraftStore(origin: origin, account: account, base: base)
        try? FileManager.default.createDirectory(at: new.dir, withIntermediateDirectories: true)
        for f in old.files {
            let to = new.dir.appendingPathComponent(f.lastPathComponent)
            if FileManager.default.fileExists(atPath: to.path) { continue }      // neuerer Entwurf des Kontos hat Vorrang
            try? FileManager.default.moveItem(at: f, to: to)
        }
    }
    /// Abgemeldet (auch Serverwechsel): Konto vergessen. Die Entwuerfe bleiben liegen, aber nur fuer ihr Konto lesbar.
    /// Alte Entwuerfe ohne Konto bekommt danach niemand mehr (es koennte ein anderes Konto angemeldet werden).
    func signedOut() {
        defaults.removeObject(forKey: DraftOwner.accountKey)
        defaults.removeObject(forKey: DraftOwner.legacyKey)
    }
    /// Ablage fuer den eingetragenen Server (nil: Konto unbekannt -> keine fremden Entwuerfe zeigen)
    func store(origin: String?) -> DraftStore? {
        guard let origin else { return nil }
        if let a = account(origin: origin) { return DraftStore(origin: origin, account: a, base: base) }
        if defaults.string(forKey: DraftOwner.legacyKey) == origin { return DraftStore(origin: origin, base: base) }
        return nil
    }
}

/// Dauerhafte Fehler beim Speichern: kein automatischer neuer Versuch (der wuerde wieder scheitern).
/// 400 ungueltig, 403 kein Recht, 404 Seite weg, 413 zu gross, 423 gesperrt. Erst eine neue Eingabe versucht es erneut.
func isPermanentSaveError(_ code: Int) -> Bool { [400, 403, 404, 413, 423].contains(code) }

/// Gleicher Inhalt? Vergleicht nur die Bloecke (ohne Zeitstempel/Schluessel-Reihenfolge).
func sameBlocks(_ a: String, _ b: String) -> Bool {
    func blocks(_ s: String) -> NSArray? {
        guard let d = s.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: d) as? NSDictionary else { return nil }
        return o["blocks"] as? NSArray
    }
    guard let x = blocks(a), let y = blocks(b) else { return a == b }
    return x.isEqual(y)
}
