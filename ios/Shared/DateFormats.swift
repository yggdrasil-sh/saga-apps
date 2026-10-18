import Foundation

/// Datumsformate der App - immer gregorianischer Kalender.
/// Grund: In Thailand ist der buddhistische Kalender Vorgabe (2026 = 2569). Saga zeigt Daten wie Web und Server
/// gregorianisch an; Sprache, Monatsnamen und Reihenfolge folgen weiter der App-Sprache/Region.
/// Reine Foundation-Logik - geprueft in Tests/Logic (DateTests.swift).
enum SagaDate {
    /// Kalender fuer Anzeigen: gregorianisch, sonst wie das System (Wochenbeginn, Zeitzone, Sprache)
    static var calendar: Calendar { calendar(base: .autoupdatingCurrent) }
    static func calendar(base: Calendar) -> Calendar {
        guard base.identifier != .gregorian else { return base }
        var c = Calendar(identifier: .gregorian)
        c.locale = base.locale; c.timeZone = base.timeZone
        c.firstWeekday = base.firstWeekday; c.minimumDaysInFirstWeek = base.minimumDaysInFirstWeek
        return c
    }

    /// Sprache/Region der Anzeige mit gregorianischem Kalender (z. B. "th_TH@calendar=gregorian")
    static func locale(_ base: Locale = .autoupdatingCurrent) -> Locale {
        var c = Locale.Components(locale: base); c.calendar = .gregorian
        return Locale(components: c)
    }

    /// Anzeigeformat wie Date.formatted(date:time:), aber gregorianisch
    static func style(date: Date.FormatStyle.DateStyle, time: Date.FormatStyle.TimeStyle, locale: Locale = .autoupdatingCurrent) -> Date.FormatStyle {
        Date.FormatStyle(date: date, time: time, locale: Self.locale(locale), calendar: calendar(base: Calendar.autoupdatingCurrent), timeZone: .autoupdatingCurrent)
    }
    /// Baukasten wie Date.FormatStyle.dateTime (Felder per .day().month() usw.), gregorianisch
    static func dateTime(locale: Locale = .autoupdatingCurrent) -> Date.FormatStyle {
        var s = Date.FormatStyle.dateTime; s.locale = Self.locale(locale); s.calendar = calendar
        return s
    }
    /// Relativ ("vor 2 Stunden"), gregorianisch
    static var relative: Date.RelativeFormatStyle {
        var s = Date.RelativeFormatStyle(presentation: .named); s.calendar = calendar; s.locale = locale()
        return s
    }
}

extension Date {
    /// Wie formatted(date:time:), immer gregorianisch (Thai: 2026, nicht 2569)
    func sagaFormatted(date: Date.FormatStyle.DateStyle, time: Date.FormatStyle.TimeStyle) -> String {
        formatted(SagaDate.style(date: date, time: time))
    }
    /// Relative Angabe ("gestern", "vor 5 Minuten")
    var sagaRelative: String { formatted(SagaDate.relative) }
}

extension DateFormatter {
    /// Technisches Tagesformat yyyy-MM-dd (Server/Datenbank): fest gregorianisch und POSIX - unabhaengig von
    /// Sprache und Kalender des Geraets (sonst z. B. "2569-10-18" mit buddhistischem Kalender)
    static func isoDay() -> DateFormatter {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian); f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }
    /// Anzeige aus einer Vorlage (z. B. "LLLLyyyy", "jmm") in der App-Sprache, gregorianisch
    static func saga(template: String) -> DateFormatter {
        let f = DateFormatter()
        f.calendar = SagaDate.calendar; f.locale = SagaDate.locale()
        f.setLocalizedDateFormatFromTemplate(template)
        return f
    }
}
