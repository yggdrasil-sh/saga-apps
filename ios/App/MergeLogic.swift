import Foundation

// Saga 2.5 (P3 Stufe 1, App-Etappe 2): Gleichzeitig bearbeiten - Buchfuehrung im Client ohne eigene Merge-Logik.
// Der Server fuehrt zusammen (merge: 1), die App merkt sich den eingereichten Stand und uebernimmt die Antwort
// blockweise. Reine Logik ohne SwiftUI (Tests: Tests/Logic/MergeTests.swift).

/// Was die App beim Speichern meldet (Kopf X-Saga-Caps). Nur Werte, die die App wirklich erfuellt.
enum SagaCaps {
    /// merge1: sendet merge: 1 und uebernimmt "merged"; sync1: schreibt Kopien synchronisierter Bloecke zurueck;
    /// inline2: erhaelt Inline-Formen (Farben, Erwaehnungen, Inline-Mathe, unbekannte Tags) beim Bearbeiten;
    /// cols6: bearbeitet bis 6 Spalten.
    static let all = ["merge1"]
    static var header: String { all.joined(separator: ",") }
    static var headers: [String: String] { ["X-Saga-Caps": header] }
}

/// Antwort `merged` eines zusammengefuehrten Speicherns
struct MergeInfo: Equatable {
    var from: Int?
    var conflicts: [String] = []
    var restored: [String] = []
    var lost: [String] = []
    var titleLost = false
    init?(_ v: Any?) {
        guard let d = v as? [String: Any] else { return nil }
        func ids(_ k: String) -> [String] { (d[k] as? [Any] ?? []).compactMap { $0 as? String } }
        from = (d["from"] as? Int) ?? (d["from"] as? NSNumber)?.intValue
        conflicts = ids("conflicts"); restored = ids("restored"); lost = ids("lost")
        titleLost = (d["title_lost"] as? Bool) ?? false
    }
    /// Dezenter Hinweis noetig? (restored: Bloecke blieben; lost/title_lost: andere Fassung liegt in den Versionen)
    var hint: MergeHint? {
        if !lost.isEmpty || titleLost { return .lost }
        if !restored.isEmpty { return .restored }
        return nil
    }
}

enum MergeHint: Equatable { case restored, lost }

enum MergeLogic {
    // MARK: Wann zusammenfuehren?

    /// merge: 1 senden? Eigene Seiten immer (alte Server ignorieren das Feld -> 409 wie bisher); Seiten anderer Server
    /// nur, wenn deren Server merge1 kann - sonst ohne merge und bei 409 der Konfliktdialog (APP-API-FED Abschnitt 8)
    static func useMerge(remote: Bool, caps: Set<String>) -> Bool { !remote || caps.contains("merge1") }

    /// Rumpf des PATCH: Revision, merge und ggf. base: "sent" (Rebase nach "weitergeschrieben")
    static func patchFields(rev: Int, merge: Bool, baseSent: Bool) -> [String: Any] {
        var b: [String: Any] = ["rev": rev]
        if merge { b["merge"] = 1; if baseSent { b["base"] = "sent" } }
        return b
    }

    /// Antwort auf ein Speichern mit merge: 1
    enum Outcome: Equatable {
        /// normal gespeichert (rev uebernehmen)
        case saved
        /// zusammengefuehrt und der Editor ist seit dem Senden unveraendert: Inhalt der Antwort blockweise uebernehmen
        case adopt
        /// zusammengefuehrt, inzwischen weitergeschrieben: lokal behalten, rev uebernehmen, naechstes Mal base: "sent"
        case keepLocal
    }
    /// `editsAtSend`/`editsNow`: Aenderungszaehler beim Senden und bei Ankunft der Antwort
    static func outcome(response: [String: Any], editsAtSend: Int, editsNow: Int) -> Outcome {
        guard response["merged"] is [String: Any] else { return .saved }
        guard response["body"] is String else { return .keepLocal }       // ohne Inhalt nichts zu uebernehmen
        return editsNow == editsAtSend ? .adopt : .keepLocal
    }

    // MARK: Bloecke uebernehmen

    /// Neue Blockliste aus `server`; Bloecke, die lokal gleich sind, bleiben dieselben Objekte (gleiche Ansicht, kein
    /// Neuzeichnen), alles andere kommt aus der Antwort. Rekursiv nicht noetig: ein geaenderter innerer Block macht
    /// den aeusseren ungleich, der dann ganz ersetzt wird.
    static func adopt(local: NSArray, server: NSArray) -> NSMutableArray {
        var byID: [String: NSMutableDictionary] = [:]
        for case let b as NSMutableDictionary in local { if let id = b["id"] as? String { byID[id] = b } }
        let out = NSMutableArray()
        for raw in server {
            guard let s = raw as? NSDictionary else { continue }
            if let id = s["id"] as? String, let l = byID[id], l.isEqual(s) { out.add(l); continue }
            out.add(deepMutable(s))
        }
        return out
    }

    /// Delta (GET ?since=): `blocks` = aktuelle Reihenfolge der obersten Ebene, unveraenderte nur als {"id"} -> eigenen
    /// Stand nehmen. nil, wenn ein unveraenderter Block lokal fehlt (dann ganz normal laden).
    static func applyDelta(_ delta: [String: Any], local: NSArray) -> NSMutableArray? {
        guard let blocks = delta["blocks"] as? [Any] else { return nil }
        var byID: [String: NSMutableDictionary] = [:]
        for case let b as NSMutableDictionary in local { if let id = b["id"] as? String { byID[id] = b } }
        let out = NSMutableArray()
        for raw in blocks {
            guard let d = raw as? [String: Any], let id = d["id"] as? String else { return nil }
            if d.count == 1 {
                guard let l = byID[id] else { return nil }
                out.add(l)
            } else if let l = byID[id], l.isEqual(d as NSDictionary) {
                out.add(l)
            } else {
                out.add(deepMutable(d as NSDictionary))
            }
        }
        return out
    }

    /// Tiefe veraenderbare Kopie (Bloecke werden im Editor direkt geaendert)
    static func deepMutable(_ d: NSDictionary) -> NSMutableDictionary {
        guard let data = try? JSONSerialization.data(withJSONObject: d),
              let m = try? JSONSerialization.jsonObject(with: data, options: .mutableContainers) as? NSMutableDictionary else {
            return NSMutableDictionary(dictionary: d)
        }
        return m
    }

    /// Bloecke aus einem gespeicherten Inhalt ({"blocks": [...]}) bzw. nil
    static func blocks(ofBody body: String) -> NSMutableArray? {
        guard let d = body.data(using: .utf8),
              let m = try? JSONSerialization.jsonObject(with: d, options: .mutableContainers) as? NSMutableDictionary else { return nil }
        return m["blocks"] as? NSMutableArray
    }

    // MARK: Block finden (oberste Ebene, Spalten, Toggles, synchronisierte Bloecke)

    static func locate(_ id: String, in arr: NSMutableArray) -> (NSMutableArray, Int)? {
        for (i, raw) in arr.enumerated() {
            guard let b = raw as? NSMutableDictionary else { continue }
            if b["id"] as? String == id { return (arr, i) }
            let d = b["data"] as? NSMutableDictionary
            if let inner = d?["blocks"] as? NSMutableArray, let r = locate(id, in: inner) { return r }
            if let cols = d?["cols"] as? NSArray {
                for c in cols { if let inner = (c as? NSDictionary)?["blocks"] as? NSMutableArray, let r = locate(id, in: inner) { return r } }
            }
        }
        return nil
    }

    // MARK: Konfliktblock (tunes.hkconflict {by, name, at, of})

    struct Conflict: Equatable { var by: String; var name: String; var at: Double?; var of: String }
    static func conflict(_ block: NSDictionary?) -> Conflict? {
        guard let c = (block?["tunes"] as? NSDictionary)?["hkconflict"] as? NSDictionary, let of = c["of"] as? String else { return nil }
        let by = c["by"] as? String ?? ""
        let name = (c["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? by
        return Conflict(by: by, name: name, at: (c["at"] as? Double) ?? (c["at"] as? NSNumber)?.doubleValue, of: of)
    }
    /// Uebernehmen: Original (`of`) loeschen und hkconflict entfernen (der Konfliktblock wird die gueltige Fassung)
    @discardableResult
    static func accept(_ id: String, in root: NSMutableArray) -> Bool {
        guard let (arr, i) = locate(id, in: root), let b = arr[i] as? NSMutableDictionary, let c = conflict(b) else { return false }
        if let tunes = b["tunes"] as? NSMutableDictionary {
            tunes.removeObject(forKey: "hkconflict")
            if tunes.count == 0 { b.removeObject(forKey: "tunes") }
        }
        if let (oa, oi) = locate(c.of, in: root) { oa.removeObject(at: oi) }
        return true
    }
    /// Verwerfen: Konfliktblock loeschen (die Fassung des Servers bleibt)
    @discardableResult
    static func discard(_ id: String, in root: NSMutableArray) -> Bool {
        guard let (arr, i) = locate(id, in: root), conflict(arr[i] as? NSDictionary) != nil else { return false }
        arr.removeObject(at: i)
        return true
    }
}

// MARK: - Anwesenheit je Block (presence mit block)

/// Drossel fuer Blockwechsel: hoechstens 1 Meldung je `interval` Sekunden; schnellere Wechsel melden spaeter nur den
/// zuletzt fokussierten Block (der Server ignoriert schnellere ohnehin bis auf den Block).
struct PresenceThrottle: Equatable {
    var interval: Double = 1
    private(set) var lastSent: Date?
    private(set) var sentBlock: String??            // .none = noch nichts gemeldet
    /// Block gewechselt: jetzt senden (0) oder nach Wartezeit (> 0); nil = nichts zu tun (gleicher Block)
    func delay(for block: String?, now: Date) -> Double? {
        if let s = sentBlock, s == block { return nil }
        guard let l = lastSent else { return 0 }
        return max(0, interval - now.timeIntervalSince(l))
    }
    mutating func sent(_ block: String?, at now: Date) { lastSent = now; sentBlock = .some(block) }
}

/// Markierung eines Blocks durch eine andere Person ("{name} bearbeitet")
struct PresenceMark: Equatable, Hashable { var name: String; var color: String }

enum PresenceBlocks {
    /// presence.users -> Block-ID -> Personen (nur andere, nur beim Bearbeiten, nur mit Block)
    static func marks(_ users: [Any]?) -> [String: [PresenceMark]] {
        var out: [String: [PresenceMark]] = [:]
        for raw in users ?? [] {
            guard let u = raw as? [String: Any], (u["me"] as? Bool) != true, (u["editing"] as? Bool) == true,
                  let b = u["block"] as? String, !b.isEmpty else { continue }
            let name = (u["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? String(localized: "Jemand")   // nie Anmeldenamen
            out[b, default: []].append(PresenceMark(name: name, color: u["color"] as? String ?? "blue"))
        }
        return out
    }
    /// Rumpf fuer POST /api/presence: block nur mit editing: true (sonst ohne Feld)
    static func body(note: Int, editing: Bool, block: String?, sendBlock: Bool) -> [String: Any] {
        var b: [String: Any] = ["note_id": note, "editing": editing]
        if sendBlock && editing { b["block"] = block ?? NSNull() }
        return b
    }
}
