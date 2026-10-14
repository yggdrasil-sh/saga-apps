import Foundation

/// Inhalt eines Tabellen-Blocks (Editor.js "table": data.content = [[Zelle]], Zellen als Inline-HTML).
/// Reine Logik, mit `swift` pruefbar. Zellen bleiben unveraendertes HTML (Fett, Links, Markierungen bleiben erhalten);
/// Zeilen und Spalten haben feste IDs, damit Einfuegen/Loeschen nie ueber Indizes stolpert.
struct TableModel: Equatable {
    struct Row: Identifiable, Equatable { let id: Int; var cells: [String] }
    private(set) var cols: [Int]                 // Spalten-IDs (Reihenfolge = Spalten)
    private(set) var rows: [Row]
    private var next: Int

    /// Robust lesen: fehlende/kaputte Daten -> leere 2x2-Tabelle; Zahlen/null in Zellen -> Text; ungleich lange Zeilen auffuellen
    init(content: Any?) {
        var grid: [[String]] = (content as? [Any] ?? []).compactMap { r in
            guard let r = r as? [Any] else { return nil }
            return r.map { TableModel.cellHTML($0) }
        }
        if grid.isEmpty { grid = [["", ""], ["", ""]] }
        let w = max(1, grid.map(\.count).max() ?? 1)
        grid = grid.map { $0 + Array(repeating: "", count: w - $0.count) }
        cols = Array(0..<w)
        rows = grid.enumerated().map { Row(id: w + $0.offset, cells: $0.element) }
        next = w + grid.count
    }

    static func cellHTML(_ v: Any) -> String {
        switch v {
        case let s as String: return s
        case is NSNull: return ""
        case let n as NSNumber: return n.stringValue
        default: return "\(v)".replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
        }
    }

    /// So wird gespeichert
    var content: [[String]] { rows.map(\.cells) }
    var width: Int { cols.count }

    func cell(_ rowID: Int, _ col: Int) -> String {
        guard let r = rows.first(where: { $0.id == rowID }), r.cells.indices.contains(col) else { return "" }
        return r.cells[col]
    }
    mutating func set(_ rowID: Int, _ colID: Int, _ html: String) {
        guard let ri = rows.firstIndex(where: { $0.id == rowID }), let ci = cols.firstIndex(of: colID), rows[ri].cells.indices.contains(ci) else { return }
        rows[ri].cells[ci] = html
    }
    /// Neue Zeile vor/nach `rowID` (nil = am Ende)
    mutating func insertRow(near rowID: Int?, after: Bool = true) {
        let r = Row(id: next, cells: Array(repeating: "", count: width)); next += 1
        guard let rowID, let i = rows.firstIndex(where: { $0.id == rowID }) else { rows.append(r); return }
        rows.insert(r, at: after ? i + 1 : i)
    }
    /// Neue Spalte links/rechts von `colID` (nil = am Ende)
    mutating func insertCol(near colID: Int?, after: Bool = true) {
        let at: Int
        if let colID, let i = cols.firstIndex(of: colID) { at = after ? i + 1 : i } else { at = cols.count }
        cols.insert(next, at: at); next += 1
        for i in rows.indices { rows[i].cells.insert("", at: min(at, rows[i].cells.count)) }
    }
    mutating func removeRow(_ rowID: Int) {
        guard rows.count > 1 else { return }
        rows.removeAll { $0.id == rowID }
    }
    mutating func removeCol(_ colID: Int) {
        guard cols.count > 1, let i = cols.firstIndex(of: colID) else { return }
        cols.remove(at: i)
        for r in rows.indices where rows[r].cells.indices.contains(i) { rows[r].cells.remove(at: i) }
    }
}
