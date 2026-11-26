import Foundation

// 2.6 (Bugs-DB 2027b, Teil W2): "Zu Kontakten hinzufuegen" - ein Kontakt des Saga-Adressbuchs einzeln ins Adressbuch des
// Geraets. Die App oeffnet den System-Dialog vorausgefuellt (ContactExport.swift); die Person entscheidet dort, ob sie
// einen neuen Kontakt anlegt oder einen vorhandenen ergaenzt. Keine Kontakte-Berechtigung, kein Abgleich.
// Uebertragen wird nur, was die Person im Detail selbst sieht und angelegt hat: Name (Name fuer mich bzw. Name der
// Person), Telefonnummern (E.164) und E-Mail-Adressen mit Typ. Nie: Saga-Adresse (interner Handle "~…" bzw. "~x@host"),
// Anrede/Grussform, Chatinhalte. Reine Logik (Tests: Tests/Logic/ContactExportTests.swift).

enum ContactExportLogic {
    typealias L = ContactBookLogic
    /// Art im System-Adressbuch (ContactExport bildet sie auf CNLabel… ab)
    enum Label: String, Equatable { case mobile, home, work, other }
    struct Value: Equatable { var value: String; var label: Label }
    struct Prefill: Equatable {
        var name: String?
        var phones: [Value] = []
        var mails: [Value] = []
    }
    /// je Art hoechstens so viele wie der Server zulaesst
    static let maxPerKind = L.maxPerKind
    static let mailMax = 254

    /// Vorbefuellung - nil, wenn nichts Uebertragbares da ist (dann kein Knopf)
    static func prefill(_ c: L.Contact) -> Prefill? {
        let phones = values(c.addrs.filter { $0.kind == "e164" && !$0.fixed }, valid: validPhone, label: phoneLabel)
        let mails = values(c.addrs.filter { $0.kind == "email" && !$0.fixed }, valid: validMail, label: mailLabel)
        guard !phones.isEmpty || !mails.isEmpty else { return nil }
        return Prefill(name: name(c), phones: phones, mails: mails)
    }
    static func canExport(_ c: L.Contact) -> Bool { prefill(c) != nil }

    /// Name fuer mich, sonst Name der Person - nie der Rueckfall des Servers (erste Nummer/Mail, "–") und nie ein Saga-Wert
    static func name(_ c: L.Contact) -> String? {
        let cand = [c.ownName, c.name].compactMap { $0.map(clean) }.first { !$0.isEmpty }
        guard let n = cand, n != "–" else { return nil }
        var inner = Set(c.addrs.flatMap { [$0.value, L.phoneShown($0.value)] })
        if let s = c.saga { inner.insert(s.ref) }
        if inner.contains(n) || n.hasPrefix("~") { return nil }
        return n
    }

    /// Steuerzeichen raus, Leerraum zusammengefasst, hoechstens 120 Zeichen
    static func clean(_ s: String) -> String {
        L.clamp(L.clamp(s, max: 10_000).split(whereSeparator: \.isWhitespace).joined(separator: " "), max: L.ownNameMax)
    }

    /// Typ der Nummer: ohne bzw. unbekannter Typ -> "Andere"
    static func phoneLabel(_ t: String?) -> Label {
        switch t {
        case "mobile": return .mobile
        case "home": return .home
        case "work": return .work
        default: return .other
        }
    }
    /// Typ der Mail: iOS kennt fuer Mails kein "Mobil" -> "Andere"
    static func mailLabel(_ t: String?) -> Label {
        switch t {
        case "home": return .home
        case "work": return .work
        default: return .other
        }
    }

    /// nur normalisierte E.164-Werte (wie der Server sie speichert)
    static func validPhone(_ v: String) -> Bool { v.range(of: #"^\+[1-9]\d{7,14}$"#, options: .regularExpression) != nil }
    /// genau ein Empfaenger (Regel des Servers, Bugs-DB 2399)
    static func validMail(_ v: String) -> Bool {
        guard v.count <= mailMax, v.filter({ $0 == "@" }).count == 1, !v.hasPrefix("@"), !v.hasSuffix("@") else { return false }
        let bad = CharacterSet.whitespacesAndNewlines.union(.controlCharacters).union(CharacterSet(charactersIn: ",;?&#<>\"\u{200B}\u{200C}\u{200D}\u{2060}\u{FEFF}"))
        return v.unicodeScalars.allSatisfy { !bad.contains($0) }
    }

    /// Vor-/Nachname fuer das System: nur wenn die Zerlegung jedes Zeichen behaelt (sonst alles als Vorname)
    struct NameParts: Equatable { var given: String; var middle = ""; var family = "" }
    static func parts(_ name: String) -> NameParts {
        let words = name.split(whereSeparator: \.isWhitespace)
        let joined = words.joined(separator: " ")
        guard words.count >= 2, let p = PersonNameComponentsFormatter().personNameComponents(from: joined),
              p.namePrefix == nil, p.nameSuffix == nil, p.nickname == nil,
              let given = p.givenName, !given.isEmpty, let family = p.familyName, !family.isEmpty else { return NameParts(given: name) }
        let middle = p.middleName ?? ""
        guard [given, middle, family].filter({ !$0.isEmpty }).joined(separator: " ") == joined else { return NameParts(given: name) }
        return NameParts(given: given, middle: middle, family: family)
    }

    private static func values(_ addrs: [L.Addr], valid: (String) -> Bool, label: (String?) -> Label) -> [Value] {
        var seen = Set<String>()
        var out: [Value] = []
        for a in addrs where out.count < maxPerKind {
            let v = a.value                                                 // wie gespeichert - nicht trimmen (Foundation zaehlt U+200B zu Leerraum)
            guard valid(v), seen.insert(v.lowercased()).inserted else { continue }
            out.append(Value(value: v, label: label(a.type)))
        }
        return out
    }
}
