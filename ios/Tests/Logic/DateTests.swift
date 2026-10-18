import Foundation

// Datumsformate (Shared/DateFormats.swift): immer gregorianisch, auch bei Thai/buddhistischem Kalender
func dateTests() {
    var g = Calendar(identifier: .gregorian); g.timeZone = TimeZone(identifier: "Europe/Berlin")!
    let d = g.date(from: DateComponents(year: 2026, month: 10, day: 18, hour: 12))!

    // Gegenprobe: buddhistischer Kalender (Vorgabe in Thailand) zaehlt 543 Jahre mehr
    let buddhist = Date.FormatStyle(date: .long, time: .omitted, locale: Locale(identifier: "th_TH"),
                                    calendar: Calendar(identifier: .buddhist), timeZone: g.timeZone)
    check(d.formatted(buddhist).contains("2569"), "Gegenprobe buddhistisch: \(d.formatted(buddhist))")

    // Kalender: buddhistisch -> gregorianisch, Wochenbeginn/Zeitzone bleiben
    var b = Calendar(identifier: .buddhist); b.firstWeekday = 1; b.timeZone = TimeZone(identifier: "Asia/Bangkok")!
    let c = SagaDate.calendar(base: b)
    check(c.identifier == .gregorian && c.firstWeekday == 1 && c.timeZone.identifier == "Asia/Bangkok", "Kalender gregorianisch, Rest wie System")
    check(SagaDate.calendar(base: g).identifier == .gregorian, "gregorianisch bleibt")

    // Locale mit Kalender-Zusatz
    let thLoc = SagaDate.locale(Locale(identifier: "th_TH@calendar=buddhist"))
    check(thLoc.calendar.identifier == .gregorian && thLoc.language.languageCode?.identifier == "th", "th_TH@calendar=gregorian: \(thLoc.identifier)")

    // Anzeige Thai: Jahr 2026, Thai-Monatsname
    var th = SagaDate.style(date: .long, time: .omitted, locale: Locale(identifier: "th_TH")); th.timeZone = g.timeZone
    let ths = d.formatted(th)
    check(ths.contains("2026") && !ths.contains("2569") && ths.contains("ตุลาคม"), "Thai lang: \(ths)")
    var thTime = SagaDate.style(date: .abbreviated, time: .shortened, locale: Locale(identifier: "th_TH")); thTime.timeZone = g.timeZone
    check(d.formatted(thTime).contains("2026"), "Thai kurz mit Zeit: \(d.formatted(thTime))")
    var thDT = SagaDate.dateTime(locale: Locale(identifier: "th_TH")).day().month(.abbreviated).year(); thDT.timeZone = g.timeZone
    check(d.formatted(thDT).contains("2026"), "Thai Baukasten: \(d.formatted(thDT))")

    // Russisch normal
    var ru = SagaDate.style(date: .long, time: .omitted, locale: Locale(identifier: "ru_RU")); ru.timeZone = g.timeZone
    let rus = d.formatted(ru)
    check(rus.contains("2026") && rus.contains("октября"), "Russisch: \(rus)")

    // Technisches Tagesformat: POSIX + gregorianisch, unabhaengig von der Geraetesprache
    let iso = DateFormatter.isoDay(); iso.timeZone = g.timeZone
    check(iso.calendar.identifier == .gregorian && iso.locale.identifier == "en_US_POSIX", "isoDay fest")
    check(iso.string(from: d) == "2026-10-18", "isoDay schreibt \(iso.string(from: d))")
    check(iso.date(from: "2026-10-18").map { g.component(.year, from: $0) } == 2026, "isoDay liest")
    let month = DateFormatter.saga(template: "LLLLyyyy")
    check(month.calendar.identifier == .gregorian, "Monatsvorlage gregorianisch")
}
