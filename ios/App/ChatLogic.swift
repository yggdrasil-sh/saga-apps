import Foundation

// Chat (Saga 2.0): reine Logik ohne Krypto und UI (mit swiftc pruefbar) - Inhalt der Nachrichten, Seitenverweise,
// Aufbewahrung, Bestaetigung des Wiederherstellungsschluessels, TOFU der Identitaetsschluessel.

/// Anhang im verschluesselten Inhalt: {"id","k":B64(Kf),"n":name,"m":mime,"s":groesse}
struct ChatFileRef: Equatable, Identifiable {
    var id: String; var key: String; var name: String; var mime: String; var size: Int
    var isImage: Bool { ["image/jpeg", "image/png", "image/gif", "image/webp", "image/heic"].contains(mime.lowercased()) }
    var dict: [String: Any] { ["id": id, "k": key, "n": name, "m": mime, "s": size] }
    init(id: String, key: String, name: String, mime: String, size: Int) { self.id = id; self.key = key; self.name = name; self.mime = mime; self.size = size }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let i = d["id"] as? String, let k = d["k"] as? String else { return nil }
        self.init(id: i, key: k, name: (d["n"] as? String) ?? "", mime: (d["m"] as? String) ?? "application/octet-stream", size: ShareJSON.int(d["s"]) ?? 0)
    }
}

/// Entschluesselter Inhalt (unbekannte Felder werden ignoriert)
struct ChatContent: Equatable {
    var type = "text"
    var text = ""
    var title: String?
    var files: [ChatFileRef] = []
    var notes: [Int] = []
    init(type: String = "text", text: String = "", title: String? = nil, files: [ChatFileRef] = [], notes: [Int] = []) {
        self.type = type; self.text = text; self.title = title; self.files = files; self.notes = notes
    }
    init?(json d: Data) {
        guard let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return nil }
        type = j["type"] as? String ?? "text"
        text = j["text"] as? String ?? ""
        title = j["title"] as? String
        files = (j["files"] as? [Any] ?? []).compactMap(ChatFileRef.init)
        notes = (j["notes"] as? [Any] ?? []).compactMap(ShareJSON.int)
    }
    func json() -> Data {
        var j: [String: Any] = ["v": 1, "type": type]
        if type == "title" { j["title"] = title ?? "" } else { j["text"] = text }
        if !files.isEmpty { j["files"] = files.map(\.dict) }
        if !notes.isEmpty { j["notes"] = notes }
        return (try? JSONSerialization.data(withJSONObject: j, options: [.sortedKeys])) ?? Data()
    }
}

enum ChatLogic {
    /// Seitenverweise [[note:<id>]] im Text (Reihenfolge, ohne Doppelte)
    static func noteRefs(_ text: String) -> [Int] {
        var out: [Int] = []
        for p in pieces(text) { if case .note(let id) = p, !out.contains(id) { out.append(id) } }
        return out
    }
    enum Piece: Equatable { case text(String), note(Int) }
    static func pieces(_ text: String) -> [Piece] {
        guard let re = try? NSRegularExpression(pattern: #"\[\[note:(\d{1,18})\]\]"#) else { return [.text(text)] }
        var out: [Piece] = [], pos = text.startIndex
        for m in re.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let r = Range(m.range, in: text), let nr = Range(m.range(at: 1), in: text), let id = Int(text[nr]) else { continue }
            if pos < r.lowerBound { out.append(.text(String(text[pos..<r.lowerBound]))) }
            out.append(.note(id)); pos = r.upperBound
        }
        if pos < text.endIndex { out.append(.text(String(text[pos...]))) }
        return out
    }

    /// Aufbewahrung: Sekunden -> Anzeige
    enum Retention: Equatable { case off, hours(Int), days(Int), years(Int) }
    static func retention(_ s: Int) -> Retention {
        if s <= 0 { return .off }
        if s % 31_536_000 == 0 { return .years(s / 31_536_000) }
        if s % 86_400 == 0 { return .days(s / 86_400) }
        return .hours(max(1, s / 3600))
    }
    /// erlaubte Werte (limits.retentions), beschraenkt auf retention_max (0 = ohne Grenze; dann ist "Aus" nicht erlaubt)
    static func retentionChoices(_ all: [Int], max: Int?) -> [Int] {
        guard let m = max, m > 0 else { return all }
        return all.filter { $0 > 0 && $0 <= m }
    }

    /// Einrichtung: letzte vier Zeichen des Wiederherstellungsschluessels bestaetigen (normalisiert wie die Eingabe)
    static func confirmLast4(_ input: String, key: String) -> Bool {
        let n = String(input.uppercased().filter { !$0.isWhitespace && $0 != "-" }
            .map { $0 == "0" ? "O" : $0 == "1" ? "I" : $0 == "8" ? "B" : $0 })
        let k = key.replacingOccurrences(of: "-", with: "")
        return n.count == 4 && k.hasSuffix(n)
    }

    /// TOFU: Identitaetsschluessel eines Gegenuebers zum ersten Mal gesehen, gleich oder geaendert
    enum Trust: Equatable { case new, same, changed }
    static func trust(known: String?, current: String) -> Trust {
        guard let k = known else { return .new }
        return k == current ? .same : .changed
    }

    /// Nachricht wird am Absender-Ende angezeigt (eigene rechts)
    static func isMine(sender: String?, me: String) -> Bool { sender == me }

    /// "tippt …": Namen der letzten 5 s (ohne mich)
    static func typing(_ seen: [String: (name: String, at: Date)], now: Date = Date()) -> [String] {
        seen.values.filter { now.timeIntervalSince($0.at) < 5 }.map(\.name).sorted()
    }

    /// Suche nur lokal (in den geladenen, entschluesselten Nachrichten)
    static func matches(_ text: String, _ q: String) -> Bool {
        let t = q.trimmingCharacters(in: .whitespaces)
        return !t.isEmpty && text.range(of: t, options: [.caseInsensitive, .diacriticInsensitive]) != nil
    }

    /// Code (Kopplung, Sicherheitsnummer) zum Vergleichen: nur Ziffern
    static func digitsOnly(_ s: String) -> String { s.filter(\.isNumber) }
    /// QR-Text der Sicherheitsnummer: saga-safety:1:<60 Ziffern>
    static func safetyQR(_ number: String) -> String { "saga-safety:1:" + digitsOnly(number) }
    static func safetyFromQR(_ s: String) -> String? {
        let p = s.trimmingCharacters(in: .whitespacesAndNewlines)
        guard p.hasPrefix("saga-safety:1:") else { return nil }
        let d = String(p.dropFirst("saga-safety:1:".count))
        return d.count == 60 && d.allSatisfy(\.isNumber) ? d : nil
    }
}
