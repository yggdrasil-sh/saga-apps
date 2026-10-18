import Foundation

// String-Kataloge: alle Schluessel in allen App-Sprachen, Platzhalter gleich, Russisch mit Pluralformen
private let catalogLangs = ["de", "en", "es", "th", "ru"]
private let ruPlural = ["one", "few", "many", "other"]

/// Platzhalter eines Textes (Format-Argumente, ${...} der Kurzbefehle, {datum}) - sortiert.
/// Argumente ohne Position zaehlen der Reihe nach (%lld %lld == %1$lld %2$lld).
func catalogPlaceholders(_ s: String) -> [String] {
    let re = try! NSRegularExpression(pattern: #"%%|%(\d\$)?(lld|ld|llu|lu|d|@|f|\.\df)|\$\{\w+\}|\{datum\}"#)
    var next = 0
    return re.matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap { m -> String? in
        let all = String(s[Range(m.range, in: s)!])
        if all == "%%" { return nil }
        guard let tr = Range(m.range(at: 2), in: s) else { return all }
        if let pr = Range(m.range(at: 1), in: s) { return "%" + s[pr] + s[tr] }
        next += 1
        return "%\(next)$" + s[tr]
    }.sorted()
}

private func loadCatalog(_ rel: String) -> [String: [String: Any]] {
    let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../..")
    guard let data = try? Data(contentsOf: dir.appendingPathComponent(rel)),
          let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let strings = root["strings"] as? [String: [String: Any]] else { check(false, "Katalog \(rel) nicht lesbar"); return [:] }
    return strings
}

/// Alle Texte einer Lokalisierung (einfach, Pluralformen oder Satz von Varianten)
private func values(_ loc: [String: Any]) -> [String]? {
    if let u = loc["stringUnit"] as? [String: Any] { return (u["value"] as? String).map { [$0] } }
    if let p = (loc["variations"] as? [String: Any])?["plural"] as? [String: [String: Any]] {
        return p.values.compactMap { ($0["stringUnit"] as? [String: Any])?["value"] as? String }
    }
    if let s = loc["stringSet"] as? [String: Any] { return s["values"] as? [String] }
    return nil
}

func catalogTests() {
    check(catalogPlaceholders("%1$@ hat %2$lld ${x} {datum} %%") == ["${x}", "%1$@", "%2$lld", "{datum}"], "Platzhalter-Erkennung")
    check(catalogPlaceholders("%lld von %lld") == catalogPlaceholders("%1$lld of %2$lld"), "Positionen gleichwertig")
    check(catalogPlaceholders("%2$@ vor %1$@") == catalogPlaceholders("%@ before %@"), "Reihenfolge darf wechseln")
    let strings = loadCatalog("Shared/Localizable.xcstrings")
    check(strings.count > 1000, "Katalog geladen (\(strings.count))")
    var missing = 0, bad = 0, plural = 0
    for (key, entry) in strings where entry["shouldTranslate"] as? Bool != false {
        let locs = entry["localizations"] as? [String: [String: Any]] ?? [:]
        let ref = catalogPlaceholders(key)
        for l in catalogLangs {
            guard let loc = locs[l], let vs = values(loc), !vs.isEmpty, !vs.contains(where: \.isEmpty) else {
                missing += 1; if missing <= 5 { print("  fehlt [\(l)] \(key)") }; continue
            }
            for v in vs where catalogPlaceholders(v) != ref {
                bad += 1; if bad <= 5 { print("  Platzhalter [\(l)] \(key) -> \(v)") }
            }
        }
        // Russisch: Texte mit genau einer Zahl brauchen alle vier Pluralformen
        let ints = ref.filter { $0.hasSuffix("d") }
        if ints.count == 1 && ref.count == 1 {
            let forms = ((locs["ru"]?["variations"] as? [String: Any])?["plural"] as? [String: Any])?.keys.sorted() ?? []
            if forms != ruPlural.sorted() { plural += 1; if plural <= 5 { print("  ru-Plural fehlt: \(key)") } }
        }
    }
    check(missing == 0, "alle Schluessel in \(catalogLangs) (\(missing) fehlen)")
    check(bad == 0, "Platzhalter gleich (\(bad) abweichend)")
    check(plural == 0, "Russisch one/few/many/other (\(plural) fehlen)")

    for rel in ["App/InfoPlist.xcstrings", "App/AppShortcuts.xcstrings"] {
        for (key, entry) in loadCatalog(rel) {
            let locs = entry["localizations"] as? [String: [String: Any]] ?? [:]
            for l in catalogLangs {
                let vs = locs[l].flatMap(values) ?? []
                check(!vs.isEmpty, "\(rel) [\(l)] \(key)")
                // Kurzbefehle: jede Phrase nennt den App-Namen
                if rel.hasSuffix("AppShortcuts.xcstrings") { check(vs.allSatisfy { $0.contains("${applicationName}") }, "\(rel) [\(l)] App-Name in \(key)") }
            }
        }
    }
}
