import Foundation

// Reine Logik zu den Funden des Apple-Tests 2.4 (ohne SwiftUI, mit swiftc pruefbar: Tests/Logic/AppleFixTests.swift)

/// Papierkorb: Eintraege lesen und Rueckfrage vor dem endgueltigen Loeschen
enum TrashLogic {
    struct Item: Equatable, Identifiable {
        var id: Int
        var title: String
        var icon: String?
        var parent: String                        // Titel der Elternseite (leer: oberste Ebene oder unbekannt)
        var subpages: Int
        var purgeAt: Double?
    }
    static func item(_ d: [String: Any], parentLabel: (Int) -> String? = { _ in nil }) -> Item {
        let pid = d["parent_id"] as? Int
        return Item(id: d["id"] as? Int ?? 0,
                    title: (d["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                    icon: (d["icon"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                    parent: pid.flatMap(parentLabel) ?? "",
                    subpages: max(0, d["subpages"] as? Int ?? 0),
                    purgeAt: (d["purge_at"] as? Double) ?? (d["purge_at"] as? Int).map(Double.init))
    }
    /// Text der Rueckfrage; nennt Titel und Unterseiten, damit klar ist, was verschwindet
    static func purgeQuestion(_ it: Item?) -> String {
        guard let it else { return "" }
        let t = it.title.isEmpty ? String(localized: "Unbenannt") : it.title
        if it.subpages > 0 { return String(localized: "„\(t)“ mit Unterseiten (\(it.subpages)) endgültig löschen?") }
        return String(localized: "„\(t)“ endgültig löschen?")
    }
}

/// Quelltext-Pruefung: mehrere Knoepfe in einer List-/Form-Zeile ohne eigenen Stil. iOS loest dann bei einem Tipp
/// alle Knoepfe der Zeile aus (F1 Papierkorb). Erlaubt, wenn jeder Knopf einen Stil hat oder der Container
/// .rowButtons() / .buttonStyle(.borderless|.plain|.bordered...) traegt.
enum RowButtonLint {
    struct Finding: Equatable { let file: String; let line: Int }

    private static func blockEnd(_ s: [Character], _ start: Int) -> Int {
        var d = 1, i = start
        while d > 0 && i < s.count { if s[i] == "{" { d += 1 } else if s[i] == "}" { d -= 1 }; i += 1 }
        return i
    }
    private static func matches(_ pattern: String, _ text: String) -> [NSRange] {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        return re.matches(in: text, range: NSRange(text.startIndex..., in: text)).map(\.range)
    }
    /// Offset in Zeichen (Character) aus einer UTF-16-Position
    private static func charIndex(_ text: String, _ utf16: Int) -> Int {
        text.distance(from: text.startIndex, to: String.Index(utf16Offset: utf16, in: text))
    }

    static func findings(file: String, source text: String) -> [Finding] {
        let s = Array(text)
        // Container: List, Form, Section (jede direkte Zeile darin ist eine Listenzeile)
        var conts: [(Int, Int)] = []
        for r in matches(#"\b(List|Form|Section)\b[^{\n]*\{"#, text) {
            let a = charIndex(text, r.location), b = charIndex(text, r.location + r.length)
            conts.append((a, blockEnd(s, b)))
        }
        guard !conts.isEmpty else { return [] }
        // Alle Stapel; solche mit Stil am Container (.rowButtons() o. ae.) decken ihre Knoepfe ab
        var stacks: [(start: Int, end: Int, styled: Bool)] = []
        for r in matches(#"\b(HStack|VStack|LabeledContent|ViewThatFits)\b[^{\n]*\{"#, text) {
            let a = charIndex(text, r.location), b = charIndex(text, r.location + r.length)
            let e = blockEnd(s, b)
            stacks.append((a, e, containerStyled(String(s[e..<min(s.count, e + 400)]))))
        }
        // Knoepfe mit Position; Menues/Kontextmenues zaehlen nicht (eigene Ebene)
        var skip: [(Int, Int)] = []
        for r in matches(#"\b(Menu|contextMenu)\b[^{\n]*\{"#, text) {
            let b = charIndex(text, r.location + r.length); skip.append((b, blockEnd(s, b)))
        }
        var buttons: [(pos: Int, styled: Bool)] = []
        let all = matches(#"\b(Button|Link)\s*[\({]"#, text).map { charIndex(text, $0.location) }
        for (n, a) in all.enumerated() where !skip.contains(where: { $0.0 <= a && a < $0.1 }) {
            let next = n + 1 < all.count ? all[n + 1] : s.count
            let seg = String(s[a..<next])
            buttons.append((a, seg.contains(".buttonStyle(") || seg.contains(".prominentGold()") || seg.contains(".rowButtons()")))
        }
        var out: [Finding] = []
        for st in stacks where !st.styled && conts.contains(where: { $0.0 < st.start && st.start < $0.1 }) {
            let inside = buttons.filter { b in
                st.start < b.pos && b.pos < st.end && !stacks.contains { $0.styled && $0.start < b.pos && b.pos < $0.end && $0.start > st.start }
            }
            guard inside.count >= 2, inside.contains(where: { !$0.styled }) else { continue }
            let line = s[0..<st.start].filter { $0 == "\n" }.count + 1
            out.append(Finding(file: file, line: line))
        }
        return out
    }
    /// Modifikatoren direkt hinter dem Container (bis zur ersten Zeile, die nicht mit "." beginnt)
    private static func containerStyled(_ tail: String) -> Bool {
        var mods = ""
        for (n, l) in tail.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
            let t = l.trimmingCharacters(in: .whitespaces)
            if n == 0 || t.hasPrefix(".") || t.hasPrefix("//") { mods += t } else { break }
        }
        return mods.contains(".rowButtons()") || mods.contains(".buttonStyle(")
    }
}

/// "Umwandeln in" (Blockmenue): nur Textbloecke; Bilder, Tabellen, Code usw. verloren dabei ihren Inhalt (F5)
enum BlockConvert {
    enum Mode: Equatable { case no, free, confirm }
    /// Darf dieser Block umgewandelt werden - und geht dabei etwas verloren (dann Rueckfrage)?
    static func mode(type: String, data: [String: Any]) -> Mode {
        switch type {
        case "paragraph", "header", "quote", "callout": return .free
        case "list":
            let items = data["items"] as? [[String: Any]] ?? []
            let nested = items.contains { !(($0["items"] as? [Any]) ?? []).isEmpty }
            return nested ? .confirm : .free                       // Unterpunkte gehen beim Umwandeln verloren
        case "toggle":
            return ((data["blocks"] as? [Any]) ?? []).isEmpty ? .free : .confirm   // Inhalt des Aufklapp-Blocks geht verloren
        default: return .no
        }
    }
}

/// Verweise auf Seiten (<a href="/notes#ID">Text</a>) zeigen den aktuellen Titel - wie decorateLinks im Web.
/// Per "/Seite" angelegte Unterseiten standen sonst fuer immer als "Unbenannt" da (F6).
enum PageLinkTitles {
    struct Target: Equatable { var title: String; var icon: String? }
    /// Von Store gesetzt: Titel/Symbol einer bekannten Seite (nil: unbekannt oder kein Zugriff -> Text bleibt)
    nonisolated(unsafe) static var lookup: ((Int) -> Target?)?
    private static let re = try? NSRegularExpression(pattern: #"(<a\s[^>]*href="/notes#(-?[0-9]{1,18})"[^>]*>)([^<]*)(</a>)"#)

    static func label(_ t: Target) -> String {
        let title = t.title.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = title.isEmpty ? String(localized: "Unbenannt") : title
        if let i = t.icon, !i.isEmpty, !i.hasPrefix("/"), i.count <= 4 { return i + " " + name }
        return name
    }
    private static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
    }
    /// Linktexte durch die aktuellen Seitentitel ersetzen (nur reine Textlinks; Links mit Formatierung bleiben)
    static func apply(_ html: String, lookup: ((Int) -> Target?)? = PageLinkTitles.lookup) -> String {
        guard let lookup, html.contains("/notes#"), let re else { return html }
        let ns = html as NSString
        var out = "", pos = 0
        for m in re.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
            guard let id = Int(ns.substring(with: m.range(at: 2))), let t = lookup(id) else { continue }
            let text = escape(label(t))
            out += ns.substring(with: NSRange(location: pos, length: m.range.location - pos))
            out += ns.substring(with: m.range(at: 1)) + text + ns.substring(with: m.range(at: 4))
            pos = m.range.location + m.range.length
        }
        guard pos > 0 else { return html }
        return out + ns.substring(from: pos)
    }
}

/// Abgebrochene Anfragen (Ansicht verlassen, .task neu gestartet) sind kein Fehler fuer die Anzeige (F14 "Abgebrochen")
enum ServiceErrors {
    static func isCancel(_ e: Error) -> Bool {
        if e is CancellationError { return true }
        if let u = e as? URLError, u.code == .cancelled { return true }
        let n = e as NSError
        return n.domain == NSURLErrorDomain && n.code == NSURLErrorCancelled
    }
}

/// Grobe Pruefung einer eingegebenen Nextcloud-/WebDAV-Adresse vor dem Senden (F17): Leerzeichen, kein Host
enum CloudURLCheck {
    static func plausible(_ raw: String) -> Bool {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !t.contains(where: \.isWhitespace) else { return false }
        let full = t.contains("://") ? t : "https://" + t
        guard let u = URL(string: full), let s = u.scheme?.lowercased(), s == "https" || s == "http",
              let h = u.host, !h.isEmpty else { return false }
        return h.allSatisfy { $0.isLetter || $0.isNumber || "-.:[]%".contains($0) }   // auch interne Namen ("nextcloud")
    }
}
