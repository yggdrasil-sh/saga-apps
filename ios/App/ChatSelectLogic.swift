import Foundation

// Chat-Mehrfachauswahl (2.5, CHAT-AUSWAHL-SPEC): was auswaehlbar ist, Bereich (Umschalt-Klick), Kopiertext,
// "Fuer alle loeschen" erlaubt?, Weiterleiten (Grenze, Anhaenge), Seite aus Nachrichten (Bloecke), Rueckfall bei
// aelteren Servern (404/405) und der Hinweis, warum es (noch) keine Reaktionen gibt (x3_why). Reine Logik, ohne SwiftUI.

enum ChatSelectLogic {
    /// Grenzen (Server: delete hoechstens 100, hide hoechstens 500; Weiterleiten hoechstens 50)
    static let forwardMax = 50
    static let deleteMax = 100
    static let hideMax = 500

    /// Anhang einer Nachricht, so weit fuer Kopieren/Seite/Weiterleiten noetig
    enum Att: Equatable {
        case image(String)
        case file(String)
        case voice
        case video(String)
        /// laesst sich ueber den normalen Sendeweg neu hochladen (Bild, Datei; nicht Sprachnachricht, nicht Video)
        var forwardable: Bool {
            switch self { case .image, .file: return true; case .voice, .video: return false }
        }
    }

    /// eine ausgewaehlte Nachricht (Reihenfolge = seq)
    struct Item: Equatable {
        var id: String
        var seq: Int
        var mine: Bool
        var name: String
        var date: Date
        var text: String
        var atts: [Att] = []
    }

    /// Texte (lokalisiert von der App, Vorgabe deutsch fuer Tests)
    struct Labels {
        var image = "[Bild]"
        var file: (String) -> String = { "[Datei: \($0)]" }
        var voice = "[Sprachnachricht]"
        var video = "[Video]"
        var notForwarded = "[Anhang nicht weitergeleitet]"
    }

    // MARK: Auswahl

    /// Systemzeilen, Anruf-Eintraege und nicht entschluesselte Nachrichten sind nicht auswaehlbar
    static func selectable(call: Bool, sys: Bool, decrypted: Bool, pending: Bool = false) -> Bool {
        !call && !sys && decrypted && !pending
    }

    /// antippen/klicken: an- bzw. abwaehlen; leere Auswahl beendet den Modus (Rueckgabe false)
    static func toggle(_ sel: inout Set<String>, _ id: String) -> Bool {
        if sel.contains(id) { sel.remove(id) } else { sel.insert(id) }
        return !sel.isEmpty
    }

    /// Umschalt-Klick: Bereich vom Anker bis zum Ziel (nur auswaehlbare), in Reihenfolge der Liste
    static func range(_ order: [String], selectable: Set<String>, from anchor: String?, to id: String) -> [String] {
        guard let a = anchor, let i = order.firstIndex(of: a), let j = order.firstIndex(of: id) else { return selectable.contains(id) ? [id] : [] }
        return order[min(i, j)...max(i, j)].filter { selectable.contains($0) }
    }

    /// Auswahl in Verlaufsreihenfolge (seq, bei Gleichstand Kennung)
    static func ordered(_ items: [Item]) -> [Item] {
        items.sorted { $0.seq != $1.seq ? $0.seq < $1.seq : $0.id < $1.id }
    }

    // MARK: Kopieren

    static func attText(_ a: Att, _ l: Labels) -> String {
        switch a {
        case .image: return l.image
        case .file(let n): return l.file(n)
        case .voice: return l.voice
        case .video: return l.video
        }
    }
    /// Inhalt einer Nachricht als eine Zeile: Text, dahinter die Anhaenge als Hinweis
    static func body(_ it: Item, _ l: Labels) -> String {
        let parts = [it.text.trimmingCharacters(in: .whitespacesAndNewlines)].filter { !$0.isEmpty } + it.atts.map { attText($0, l) }
        return parts.joined(separator: " ")
    }
    /// Kopiertext: eine Nachricht -> nur ihr Inhalt; mehrere -> je Zeile "Name, Zeit: Text" in Reihenfolge
    static func copyText(_ items: [Item], time: (Date) -> String, labels: Labels = Labels()) -> String {
        let list = ordered(items)
        if list.count == 1 { return body(list[0], labels) }
        return list.map { "\($0.name), \(time($0.date)): \(body($0, labels))" }.joined(separator: "\n")
    }

    // MARK: Loeschen

    /// "Fuer alle loeschen" nur, wenn ALLE ausgewaehlten eigene sind (und Loeschen erlaubt ist)
    static func canDeleteForAll(_ items: [Item], allowed: Bool = true) -> Bool {
        allowed && !items.isEmpty && items.allSatisfy(\.mine)
    }
    /// in Teile zu hoechstens n Kennungen (Server-Grenzen)
    static func chunks(_ ids: [String], _ n: Int) -> [[String]] {
        guard n > 0 else { return [ids] }
        return stride(from: 0, to: ids.count, by: n).map { Array(ids[$0..<min($0 + n, ids.count)]) }
    }

    enum BulkResult: Equatable {
        /// Server kann es: geloescht bzw. ausgeblendet, fehlgeschlagen (Kennung -> Grund)
        case ok(done: [String], failed: [String: String])
        /// Endpunkt fehlt (aelterer Server): Rueckfall (Einzel-DELETE bzw. nur lokal ausblenden)
        case fallback
        case error(String)
    }
    /// Antwort von POST .../messages/delete {ids} -> {deleted: [mid], failed: {mid: grund}}
    static func deleteResult(code: Int, body: [String: Any]?, asked: [String]) -> BulkResult {
        if code == 404 || code == 405 { return .fallback }              // aelterer Server ohne den Endpunkt (dann Einzel-DELETE)
        guard (200..<300).contains(code) else { return .error((body?["error"] as? String) ?? "HTTP \(code)") }
        let done = (body?["deleted"] as? [Any] ?? []).compactMap { $0 as? String }
        var failed: [String: String] = [:]
        for (k, v) in body?["failed"] as? [String: Any] ?? [:] { failed[k] = (v as? String) ?? "" }
        return .ok(done: done.filter(asked.contains), failed: failed)
    }
    /// Antwort von POST .../hide {ids} -> {hidden: n}
    static func hideResult(code: Int, body: [String: Any]?) -> BulkResult {
        if code == 404 || code == 405 { return .fallback }              // aelterer Server: nur auf diesem Geraet ausblenden
        guard (200..<300).contains(code) else { return .error((body?["error"] as? String) ?? "HTTP \(code)") }
        return .ok(done: [], failed: [:])
    }
    /// Live-Ereignis chat.hidden {chat_id, ids}
    static func hiddenIDs(_ d: [String: Any], chatID: Int) -> [String]? {
        guard let c = d["chat_id"], (c as? Int) == chatID || (c as? NSNumber)?.intValue == chatID || (c as? String) == String(chatID) else { return nil }
        return (d["ids"] as? [Any] ?? []).compactMap { $0 as? String }
    }

    // MARK: Weiterleiten

    struct Forward: Equatable {
        /// Text der neuen Nachricht (mit Hinweisen fuer nicht weitergeleitete Anhaenge)
        var text: String
        /// Indizes der Anhaenge, die neu hochgeladen werden
        var upload: [Int]
        var skipped: Int
    }
    /// je Nachricht: Text bleibt, Bilder/Dateien neu hochladen, Rest als Hinweis
    static func forward(_ it: Item, labels: Labels = Labels()) -> Forward {
        var up: [Int] = [], skipped = 0
        for (i, a) in it.atts.enumerated() { if a.forwardable { up.append(i) } else { skipped += 1 } }
        var t = it.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if skipped > 0 { t += (t.isEmpty ? "" : "\n") + labels.notForwarded }
        return Forward(text: t, upload: up, skipped: skipped)
    }
    static func forwardAllowed(_ n: Int) -> Bool { n > 0 && n <= forwardMax }

    // MARK: In Seite uebernehmen

    /// Titel einer neuen Seite: "Chat <Name> – <Datum>"
    static func pageTitle(chat: String, date: String) -> String { "Chat \(chat) – \(date)" }

    static func html(_ s: String) -> String {
        var o = ""
        for c in s {
            switch c {
            case "&": o += "&amp;"
            case "<": o += "&lt;"
            case ">": o += "&gt;"
            case "\"": o += "&quot;"
            case "\n": o += "<br>"
            default: o.append(c)
            }
        }
        return o
    }
    /// je Nachricht ein Absatz "<b>Name</b> · Zeit" + Text (HTML wie im Editor), Anhaenge als Hinweis
    static func pageBlocks(_ items: [Item], time: (Date) -> String, labels: Labels = Labels(), newID: () -> String) -> [[String: Any]] {
        ordered(items).map { it in
            let b = body(it, labels)
            let t = "<b>" + html(it.name) + "</b> · " + html(time(it.date)) + (b.isEmpty ? "" : "<br>" + html(b))
            return ["id": newID(), "type": "paragraph", "data": ["text": t]]
        }
    }
    /// Seiteninhalt (JSON {"blocks": [...]}) um Bloecke ergaenzen; nil = Inhalt nicht lesbar (dann nie speichern)
    static func appended(body: String, format: String?, blocks: [[String: Any]]) -> String? {
        var doc: [String: Any]
        if format == "blocks" {
            guard let d = body.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], o["blocks"] is [Any] else {
                if body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { doc = ["blocks": []] } else { return nil }
                return finish(&doc, blocks)
            }
            doc = o
        } else if body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            doc = ["blocks": []]
        } else {
            return nil                                                      // alte Textnotiz: nicht umbauen
        }
        return finish(&doc, blocks)
    }
    private static func finish(_ doc: inout [String: Any], _ blocks: [[String: Any]]) -> String? {
        var arr = doc["blocks"] as? [Any] ?? []
        // leeren Platzhalter-Absatz einer neuen Seite ersetzen
        if arr.count == 1, let p = arr[0] as? [String: Any], p["type"] as? String == "paragraph",
           ((p["data"] as? [String: Any])?["text"] as? String ?? "").isEmpty { arr = [] }
        arr += blocks as [Any]
        doc["blocks"] = arr
        doc["time"] = Int(Date().timeIntervalSince1970 * 1000)
        guard let d = try? JSONSerialization.data(withJSONObject: doc) else { return nil }
        return String(data: d, encoding: .utf8)
    }

    // MARK: Warum keine Reaktionen (x3_why)

    enum Why: Equatable {
        case setting
        case devices(mine: [String], others: Int, until: Double?)
        case server(hosts: [String])
        case readonly
        case pending
        case other
    }
    /// Chat-Objekt -> Grund (nil = Feld fehlt oder geht)
    static func why(_ d: [String: Any]) -> Why? {
        if (d["x3_ok"] as? Bool) == true { return nil }
        guard let w = d["x3_why"] as? String else { return nil }
        switch w {
        case "setting": return .setting
        case "devices":
            let x = d["x3_wait"] as? [String: Any] ?? [:]
            let mine = (x["mine"] as? [Any] ?? []).compactMap { $0 as? String }.filter { !$0.isEmpty }
            let others = (x["others"] as? Int) ?? (x["others"] as? NSNumber)?.intValue ?? 0
            let until = (x["until"] as? Double) ?? (x["until"] as? NSNumber)?.doubleValue
            return .devices(mine: mine, others: max(0, others), until: until.flatMap { $0 > 0 ? $0 : nil })
        case "server": return .server(hosts: (d["x3_hosts"] as? [Any] ?? []).compactMap { $0 as? String }.filter { !$0.isEmpty })
        case "readonly": return .readonly
        case "pending": return .pending
        default: return .other
        }
    }
    /// Link "Geraete verwalten" nur bei eigenen Geraeten
    static func offerDevices(_ w: Why) -> Bool {
        if case .devices(let m, _, _) = w { return !m.isEmpty }
        return false
    }
}
