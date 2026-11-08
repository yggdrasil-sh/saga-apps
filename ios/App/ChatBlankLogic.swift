import Foundation

// 2.5 (leerchat): Waechter gegen eine leere Nachrichtenflaeche. Fehlerbild (iPhone, langer Chat mit vielen flachen
// Anruf-Zeilen und Bildern): nach dem Laden bzw. nach der Rueckkehr aus dem Hintergrund steht der Inhalts-Offset der
// ScrollView ausserhalb des tatsaechlich aufgebauten Bereichs der LazyVStack - nichts sichtbar, nicht einmal der
// E2E-Hinweis; erst eine Groessenaenderung (Suche oeffnen) baut die Zeilen wieder auf.
// Reine Entscheidung ohne SwiftUI (Tests/Logic/ChatBlankTests.swift):
// - blank: Nachrichten da, aber keine Zeile sichtbar bzw. sichtbarer Bereich ausserhalb des Inhalts
// - Guard: nach dem Scharfschalten (~1 s) pruefen; leer -> 1. ans Ende springen, 2. Liste neu aufbauen; danach melden
// - message: Meldung fuer clientlog (k "warn"), nur Programmwoerter und Groessenklassen - keine Inhalte, keine IDs
enum ChatBlankLogic {
    static let checkAfterMs = 1000

    /// sichtbarer Bereich (Inhaltskoordinaten) liegt ganz ausserhalb des Inhalts bzw. Inhalt ohne Hoehe
    static func outside(offsetY: Double, contentH: Double, containerH: Double) -> Bool {
        guard containerH > 1 else { return false }                          // noch nicht ausgelegt
        if contentH < 1 { return true }
        return offsetY >= contentH - 1 || offsetY + containerH <= 1
    }

    /// leer: Nachrichten vorhanden, aber nichts davon (und auch kein Kopf/Ende) sichtbar
    static func blank(count: Int, visible: Int, outside: Bool) -> Bool {
        count > 0 && (visible == 0 || outside)
    }

    enum Trigger: String { case load, resume, change }
    enum Action: Equatable {
        case none
        case scroll                                                         // ans Ende springen, danach erneut pruefen
        case rebuild                                                        // ScrollView neu aufbauen, danach erneut pruefen
        case report(healed: Bool, step: Int)                                // fertig, clientlog-Meldung
    }

    struct Guard: Equatable {
        private(set) var armed = false
        private(set) var step = 0
        private(set) var trigger = Trigger.load
        private(set) var why = ""                                           // vis | geo (erster Befund)

        /// nach Laden/Rueckkehr/Aenderung der Nachrichtenmenge: (erneut) pruefen
        mutating func arm(_ t: Trigger) {
            if armed && step > 0 { return }                                 // Heilung laeuft schon
            armed = true; step = 0; trigger = t; why = ""
        }
        mutating func disarm() { armed = false; step = 0 }

        /// Pruefung (~1 s nach arm bzw. nach der letzten Massnahme)
        mutating func check(count: Int, visible: Int, outside: Bool, active: Bool) -> Action {
            guard armed else { return .none }
            guard active else { disarm(); return .none }                   // im Hintergrund nichts tun (Rueckkehr schaltet neu scharf)
            let isBlank = ChatBlankLogic.blank(count: count, visible: visible, outside: outside)
            if !isBlank {
                let s = step
                disarm()
                return s == 0 ? .none : .report(healed: true, step: s)
            }
            if step == 0 { why = visible == 0 ? "vis" : "geo" }
            switch step {
            case 0: step = 1; return .scroll
            case 1: step = 2; return .rebuild
            default:
                let s = step
                disarm()
                return .report(healed: false, step: s)
            }
        }
    }

    /// Groessenklasse statt genauer Zahl (Zahlen bereinigt clientlog ohnehin zu <n>)
    static func bucket(_ n: Int) -> String {
        switch n {
        case ..<10: return "n_tiny"                                     // unter 10
        case ..<50: return "n_small"                                    // 10-49
        case ..<100: return "n_mid"                                     // 50-99
        case ..<200: return "n_large"                                   // 100-199
        default: return "n_huge"
        }
    }

    /// z. B. "chat_blank healed_scroll on_load why_vis n_mid calls_many"
    static func message(count: Int, calls: Int, healed: Bool, step: Int, trigger: Trigger, why: String) -> String {
        let how = healed ? (step <= 1 ? "healed_scroll" : "healed_rebuild") : "not_healed"
        let share = count > 0 && calls * 2 >= count ? "calls_many" : "calls_few"
        return "chat_blank \(how) on_\(trigger.rawValue) why_\(why.isEmpty ? "vis" : why) \(bucket(count)) \(share)"
    }
}
