import SwiftUI
#if os(iOS)
import UIKit
#endif

// Saga 2.5 "Notion-Gefuehl": Schreibflaeche der Seite (Masse aus WritingLogic). Die Seite setzt `writingMetrics`;
// ausserhalb der Seite (und auf dem iPhone) gilt `standard` - dort bleibt alles wie bisher.

private struct WritingMetricsKey: EnvironmentKey { static let defaultValue = WritingLogic.standard }
extension EnvironmentValues {
    var writingMetrics: WritingLogic.Metrics {
        get { self[WritingMetricsKey.self] } set { self[WritingMetricsKey.self] = newValue }
    }
}

extension WritingLogic {
    /// Index der Dynamic-Type-Stufe (Standard bei unbekannt)
    static func step(_ dts: DynamicTypeSize) -> Int { DynamicTypeSize.allCases.firstIndex(of: dts) ?? defaultStep }

    /// Schrift fuer eine Rolle: feste Schreibgroesse (skaliert mit der Stufe) oder - ohne Groesse - `fallback`.
    /// Ohne `design`: Schrift der Seite (Serif/Mono ueber .fontDesign) gilt weiter.
    static func font(_ r: Role, fallback: Font, dts: DynamicTypeSize, in m: Metrics) -> Font {
        guard let s = pointSize(r, step: step(dts), in: m) else { return fallback }
        return .system(size: s, weight: isBold(r) ? .bold : .regular)
    }

    /// Bisherige Textstile (iPhone) fuer Ueberschriften: Ebene 0/1 title, 2 title2, 3 title3, sonst headline
    static func systemHeaderFont(level: Int) -> Font {
        level <= 1 ? .title.bold() : level == 2 ? .title2.bold() : level == 3 ? .title3.bold() : .headline
    }
}

extension WritingLogic {
    /// iPad in normaler Breite. Nur auf dem iPad: grosse iPhones melden quer ebenfalls "regular" und bleiben unveraendert.
    @MainActor static func padRegular(_ hsc: UserInterfaceSizeClass?) -> Bool {
        #if os(iOS)
        return hsc == .regular && UIDevice.current.userInterfaceIdiom == .pad
        #else
        return false
        #endif
    }
}
