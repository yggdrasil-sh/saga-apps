import Foundation

// Bildblock kopieren/ausschneiden (2.1-c): reine Logik (mit swiftc pruefbar). In die Zwischenablage kommen das Bild
// selbst (fuer andere Programme und als Rueckfall) und der Block als eigener Typ (Bildunterschrift, Herkunft).
// Einsetzen in DERSELBEN Seite (Ausschneiden + Einsetzen = Verschieben) uebernimmt den Block samt Datei-Verweis;
// in einer ANDEREN Seite wird das Bild neu hochgeladen - der Server laesst in einer Seite nur Dateien ihres
// Eigentuemers zu (fremde Verweise: "unbekannte Datei").

enum BlockClipLogic {
    /// eigener Typ in der Zwischenablage (nur diese App liest ihn)
    static let type = "io.github.yggdrasil-sh.saga.blocks"
    static let maxBytes = 512 * 1024
    static let maxBlocks = 50

    struct Payload {
        var server: String; var note: Int; var blocks: [[String: Any]]
        /// MIME des mitgelieferten Bildes (fuer das Einsetzen in einer anderen Seite)
        var mime: String?
    }

    static func encode(server: String, note: Int, blocks: [[String: Any]], mime: String?) -> Data? {
        guard !blocks.isEmpty, blocks.count <= maxBlocks else { return nil }
        var j: [String: Any] = ["v": 1, "server": normServer(server), "note": note, "blocks": blocks]
        if let mime { j["mime"] = mime }
        guard let d = try? JSONSerialization.data(withJSONObject: j, options: [.sortedKeys]), d.count <= maxBytes else { return nil }
        return d
    }
    static func decode(_ d: Data?) -> Payload? {
        guard let d, d.count <= maxBytes, let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
              (j["v"] as? NSNumber)?.intValue == 1, let s = j["server"] as? String, let n = (j["note"] as? NSNumber)?.intValue,
              let bl = j["blocks"] as? [Any], !bl.isEmpty, bl.count <= maxBlocks else { return nil }
        let blocks = bl.compactMap { $0 as? [String: Any] }
        guard blocks.count == bl.count, blocks.allSatisfy({ ($0["type"] as? String).map { !$0.isEmpty } ?? false }) else { return nil }
        return Payload(server: s, note: n, blocks: blocks, mime: j["mime"] as? String)
    }
    static func normServer(_ s: String) -> String { s.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased() }

    enum Plan: Equatable { case reuse, upload }
    /// Block uebernehmen (gleiche Seite, gleicher Server) oder Bild neu hochladen
    static func plan(_ p: Payload, server: String, note: Int) -> Plan {
        normServer(p.server) == normServer(server) && p.note == note ? .reuse : .upload
    }

    /// Bildblock? (nur sie lassen sich bisher kopieren)
    static func isImage(_ b: [String: Any]) -> Bool { b["type"] as? String == "image" && imageURL(b) != nil }
    static func imageURL(_ b: [String: Any]) -> String? {
        guard let u = ((b["data"] as? [String: Any])?["file"] as? [String: Any])?["url"] as? String, u.hasPrefix("/files/") else { return nil }
        return u
    }
    static func caption(_ b: [String: Any]) -> String { (b["data"] as? [String: Any])?["caption"] as? String ?? "" }

    /// Kopie mit neuen Block-IDs (auch in Toggles und Spalten)
    static func renewed(_ b: [String: Any], newID: () -> String) -> [String: Any] {
        var x = b
        x["id"] = newID()
        if var d = x["data"] as? [String: Any] {
            if let inner = d["blocks"] as? [[String: Any]] { d["blocks"] = inner.map { renewed($0, newID: newID) } }
            if let cols = d["cols"] as? [[String: Any]] {
                d["cols"] = cols.map { c in var c = c; if let inner = c["blocks"] as? [[String: Any]] { c["blocks"] = inner.map { renewed($0, newID: newID) } }; return c }
            }
            x["data"] = d
        }
        return x
    }

    /// Zwischenablage-Typ (UTI) zum MIME des Bildes
    static func uti(_ mime: String) -> String? {
        switch mime.lowercased() {
        case "image/png": return "public.png"
        case "image/jpeg": return "public.jpeg"
        case "image/gif": return "com.compuserve.gif"
        case "image/webp": return "org.webmproject.webp"
        default: return nil
        }
    }
}
