import Foundation

/// Eingabefluss des Seiten-Editors als reine Logik (F4, Testrunde 2.4): kein Tastendruck darf verloren gehen - auch
/// nicht, wenn schnell getippt wird, waehrend nach einem Return der Fokus noch vom alten zum neuen Feld wandert.
///
/// Ursachen der Verluste bis 2.4.0-rc (gefunden beim Nachstellen der Tippfolgen):
/// 1. Mehrere Tastendruecke kommen in EINEM onChange an (z. B. ":⏎"). Getrennt wurde am Anfang der Aenderung und
///    das erste Zeichen als Zeilenumbruch verworfen -> "Mittel:⏎" wurde "Mitte" + Absatz mit Umbruch.
/// 2. Das alte Feld wurde nach dem Weiterreichen sofort zurueckgesetzt und das naechste onChange pauschal
///    verschluckt ("reverting"). Kam dazwischen schon der naechste Tastendruck an, ging er verloren oder landete
///    doppelt - je nachdem, ob UIKit das Zuruecksetzen schon angewendet hatte.
/// 3. Weitergereichtes kam nur beim Erscheinen/Fokussieren des neuen Felds an; was danach noch eintraf, blieb liegen.
/// 4. Eigene, programmatische Aenderungen (Weitergereichtes einspielen) hielt onChange fuer Eingaben und reichte sie
///    noch einmal weiter.
/// 5. Leerer Listenpunkt + Return entfernte das fokussierte Feld sofort: die Tastatur ging zu, und das neue Feld bekam
///    den Fokus nicht (verlor ihn gleich wieder) -> ed.focus = nil, weiterer Text weg.
///
/// Neu: Jedes Feld fuehrt einen "Schatten" (zuletzt verbuchter Text). Aenderungen werden gegen den Schatten
/// verbucht; ein Feld, das nicht mehr Ziel ist, aber noch Tasten bekommt, rechnet gegen seinen festen Stand bei der
/// Abgabe (Handover) und reicht nur noch nicht Weitergereichtes weiter - ohne sich selbst zurueckzusetzen.
enum EditorFlow {
    /// Zeichen fuer "Rueckschritt" in weitergereichten Eingaben
    static let backspace: Character = "\u{8}"

    struct Delta: Equatable {
        var prefix: Int          // unveraenderter Anfang (Zeichen)
        var removed: Int         // entfernte Zeichen ab prefix
        var inserted: String     // eingefuegter Text ab prefix
        var suffix: Int          // unveraendertes Ende (Zeichen)
    }

    /// Kleinste Aenderung zwischen zwei Staenden (gemeinsamer Anfang, gemeinsames Ende)
    static func delta(_ old: String, _ new: String) -> Delta {
        let o = Array(old), n = Array(new)
        var p = 0
        while p < o.count && p < n.count && o[p] == n[p] { p += 1 }
        var s = 0
        while s < o.count - p && s < n.count - p && o[o.count - 1 - s] == n[n.count - 1 - s] { s += 1 }
        return Delta(prefix: p, removed: o.count - p - s, inserted: String(n[p..<(n.count - s)]), suffix: s)
    }

    /// Was das Ziel-Feld mit einer Aenderung tut
    enum Step: Equatable {
        case none                                            // nichts Neues (eigenes Echo)
        case text                                            // Text verbuchen (speichern, wenn sich das HTML aendert)
        /// Return: vorher = neu[..<at], nachher = neu[afterFrom...]; `rest` = nach dem Return getippt -> neues Feld
        case split(at: Int, afterFrom: Int, rest: String)
        /// Return im "/"-Menue: ersten Treffer waehlen; `rest` geht an das umgewandelte Feld
        case slashPick(rest: String)
    }

    /// Ziel-Feld: Aenderung gegenueber dem Schatten einordnen
    static func targetStep(shadow: String, new: String, canSplit: Bool, slashPick: Bool) -> Step {
        guard shadow != new else { return .none }
        let d = delta(shadow, new)
        guard canSplit, let k = d.inserted.firstIndex(of: "\n") else { return .text }
        let nl = d.inserted.distance(from: d.inserted.startIndex, to: k)
        let rest = String(d.inserted[d.inserted.index(after: k)...])
        if slashPick && nl == 0 && d.removed == 0 && d.suffix == 0 { return .slashPick(rest: rest) }
        return .split(at: d.prefix + nl, afterFrom: d.prefix + d.inserted.count, rest: rest)
    }

    /// Feld hat den Fokus abgegeben (Return), bekommt aber noch Tasten, bis das neue Feld ihn hat
    struct Handover: Equatable {
        var base: String                                     // Stand bei der Abgabe (bleibt so gespeichert)
        var sentRemoved = 0
        var sentInserted = ""
        init(base: String) { self.base = base }
    }

    /// Neuer Stand des abgebenden Felds -> was (noch) an das Ziel geht. Rueckschritt als `backspace`.
    static func handover(_ h: inout Handover, new: String) -> String {
        let d = delta(h.base, new)
        var out = ""
        if d.removed == h.sentRemoved {
            // gemeinsamen Anfang behalten, Rest zuruecknehmen und neu senden (Rueckschritt = ein Zeichen)
            let common = d.inserted.commonPrefix(with: h.sentInserted).count
            out = String(repeating: backspace, count: h.sentInserted.count - common) + String(d.inserted.dropFirst(common))
        } else {
            // geloescht/ersetzt: Bisheriges zuruecknehmen, dann den ganzen neuen Stand senden
            out = String(repeating: backspace, count: h.sentInserted.count + max(0, d.removed - h.sentRemoved)) + d.inserted
        }
        h.sentRemoved = d.removed; h.sentInserted = d.inserted
        return out
    }

    /// Weitergereichtes in ein Feld einspielen (am Cursor). Stoppt beim ersten Return, wenn das Feld trennen kann.
    struct Applied: Equatable {
        var removedBefore: Int   // vor dem Cursor geloeschte Zeichen
        var inserted: String     // dann am Cursor eingefuegt
        var split: Bool          // danach Return: vorher = Text bis Cursor, nachher = Rest
        var rest: String         // nach dem Return -> naechstes Feld
    }
    static func apply(carry: String, caret: Int, canSplit: Bool) -> Applied {
        var removed = 0, ins: [Character] = []
        var it = carry.makeIterator()
        var consumed = 0
        while let ch = it.next() {
            consumed += 1
            if ch == backspace {
                if !ins.isEmpty { ins.removeLast() } else if removed < caret { removed += 1 }
            } else if ch == "\n" && canSplit {
                return Applied(removedBefore: removed, inserted: String(ins), split: true, rest: String(carry.dropFirst(consumed)))
            } else { ins.append(ch) }
        }
        return Applied(removedBefore: removed, inserted: String(ins), split: false, rest: "")
    }

    /// Text eines Felds nach `apply` (fuer Tests und als Pruefung in der Ansicht)
    static func result(_ text: String, caret: Int, _ a: Applied) -> (text: String, caret: Int) {
        let t = Array(text), c = min(max(caret, 0), t.count)
        let start = c - min(a.removedBefore, c)
        let out = Array(t[..<start]) + Array(a.inserted) + Array(t[c...])
        return (String(out), start + a.inserted.count)
    }
}

/// "/"-Menue: Treffer filtern (gemeinsam fuer Menue und Return = erster Treffer, F3)
enum SlashFilter {
    /// Suchtext eines Absatzes ("/seite" -> "seite"), nil wenn kein "/"-Befehl
    static func query(_ s: String) -> String? {
        s.hasPrefix("/") && !s.contains(" ") && !s.contains("\n") && s.count <= 20 ? String(s.dropFirst()).lowercased() : nil
    }
    static func matches(name: String, keywords: String, query: String) -> Bool {
        query.isEmpty || name.lowercased().contains(query) || keywords.contains(query)
    }
}

/// Aktive Filter einer Datenbank-Ansicht zaehlen (N6: Markierung am Regler). Leere Werte filtern nicht.
enum DBFilterBadge {
    static let noValue: Set<String> = ["is_empty", "not_empty", "checked", "unchecked", "today", "this_week", "this_month"]
    static func count(_ view: [String: Any]?) -> Int {
        let fs = view?["filters"] as? [[String: Any]] ?? []
        return fs.filter { f in
            if let op = f["op"] as? String, noValue.contains(op) { return true }
            switch f["value"] {
            case let s as String: return !s.trimmingCharacters(in: .whitespaces).isEmpty
            case let a as [Any]: return !a.isEmpty
            case nil, is NSNull: return false
            default: return true
            }
        }.count
    }
}

/// Personensuche beim Teilen (N7): 1-2 Zeichen finden nur exakte Treffer -> Hinweis statt "Niemand gefunden."
enum ShareSearchHint {
    static func tooShort(_ q: String) -> Bool { q.trimmingCharacters(in: .whitespacesAndNewlines).count < 3 }
}

/// Chatliste neben der Unterhaltung (iPad quer, Mac): waechst mit dem Fenster (N15)
enum ChatLayout {
    static func listWidth(total: Double) -> Double { min(420, max(320, (total * 0.36).rounded())) }
}
