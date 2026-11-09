import SwiftUI

// Saga 2.5 BookOrbit (bookorbit1): Widget "Lesen" in Seiten, Karte "Lesen" unter Heute, Einstellungen -> Dienste -> BookOrbit.
// Logik/Dekodierung in BookOrbitLogic.swift (mit Tests). Cover ueber den Saga-Server (RemoteImage: Sitzung, Zwischenspeicher).

/// Inhalt des Widgets bzw. der Heute-Karte
struct ReadingContentView: View {
    let reading: BookOrbitLogic.Reading
    var compact = false
    var limit: Int? = nil
    @Environment(\.locale) private var envLocale

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch BookOrbitLogic.content(reading) {
            case .hint(let s): ReadingHint(state: s)
            case .books(let books, let more):
                let shown = limit.map { Array(books.prefix($0)) } ?? books
                ForEach(shown) { b in ReadingBookRow(book: b, compact: compact) }
                let rest = more + (books.count - shown.count)
                if rest > 0 { Text("und \(rest) weitere").font(.caption).foregroundStyle(.secondary) }
            case .noBooks: Text("Gerade kein Buch").font(.subheadline).foregroundStyle(.secondary)
            case .goal(let g): ReadingGoalView(goal: g)
            case .noGoal: Text("Kein Leseziel verfügbar").font(.subheadline).foregroundStyle(.secondary)
            case .streak(let s): ReadingStreakView(streak: s)
            case .noStreak: Text("Keine Lesesträhne verfügbar").font(.subheadline).foregroundStyle(.secondary)
            }
            if reading.stale && reading.state == .ok { Text("Stand von vorhin").font(.caption2).foregroundStyle(.orange) }
        }
    }
}

/// Ohne Verbindung / abgelaufen / nicht erreichbar: freundlicher Hinweis, ggf. mit Weg zu den Einstellungen
struct ReadingHint: View {
    let state: BookOrbitLogic.State
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label { Text(text).font(.footnote).fixedSize(horizontal: false, vertical: true) } icon: { Image(systemName: icon) }
                .foregroundStyle(.secondary)
            if BookOrbitLogic.offersSettings(state) {
                Button("Einstellungen öffnen") {
                    CloudNav.pendingSection = "bookorbit"
                    NotificationCenter.default.post(name: .hkOpenSettings, object: "bookorbit")
                }
                .font(.footnote).buttonStyle(.borderless)
                .accessibilityHint(Text("Einstellungen → Dienste → BookOrbit"))
            }
        }
    }
    private var icon: String { state == .unavailable ? "wifi.exclamationmark" : state == .expired ? "person.badge.key" : "books.vertical" }
    private var text: String {
        switch state {
        case .expired: return String(localized: "BookOrbit-Anmeldung abgelaufen – bitte neu anmelden.")
        case .unavailable: return String(localized: "BookOrbit gerade nicht erreichbar.")
        default: return String(localized: "Noch nicht mit BookOrbit verbunden.")
        }
    }
}

/// Ein Buch: Cover (oder Platzhalter), Titel, Autor:innen, Fortschritt
struct ReadingBookRow: View {
    let book: BookOrbitLogic.Book
    var compact = false
    @ScaledMetric(relativeTo: .body) private var coverW: CGFloat = 40
    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 4).fill(Color.secondary.opacity(0.15))
                if let c = book.cover {
                    RemoteImage(path: c, fill: true).clipShape(RoundedRectangle(cornerRadius: 4))
                } else {
                    Image(systemName: "book.closed").foregroundStyle(.secondary)
                }
            }
            .frame(width: compact ? coverW * 0.8 : coverW, height: (compact ? coverW * 0.8 : coverW) * 1.5)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: book.title.isEmpty ? String(localized: "Unbenannt") : book.title)
                    .font(.subheadline.weight(.semibold)).lineLimit(2)
                if !book.authorLine.isEmpty {
                    Text(verbatim: book.authorLine).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                HStack(spacing: 6) {
                    ProgressView(value: book.progress, total: 100).progressViewStyle(.linear)
                    Text(verbatim: "\(book.percent) %").font(.caption2).monospacedDigit().foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: [book.title.isEmpty ? String(localized: "Unbenannt") : book.title, book.authorLine]
            .filter { !$0.isEmpty }.joined(separator: ", ")))
        .accessibilityValue(Text("Fortschritt \(book.percent) Prozent"))
    }
}

struct ReadingGoalView: View {
    let goal: BookOrbitLogic.Goal
    @Environment(\.widgetSize) private var size
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let g = goal.goal, let f = goal.fraction {
                Text(verbatim: "\(goal.done) / \(g)").font(size == .s ? .title3.weight(.semibold) : .largeTitle.weight(.semibold)).monospacedDigit()
                ProgressView(value: f).progressViewStyle(.linear)
                Text("Leseziel \(yearText): \(goal.done) von \(g) Büchern").font(.caption).foregroundStyle(.secondary)
            } else {
                Text(verbatim: "\(goal.done)").font(size == .s ? .title3.weight(.semibold) : .largeTitle.weight(.semibold)).monospacedDigit()
                Text("Gelesen im Jahr \(yearText) – kein Leseziel in BookOrbit gesetzt").font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
    private var yearText: String { goal.year.map { String($0) } ?? "" }
}

struct ReadingStreakView: View {
    let streak: BookOrbitLogic.Streak
    @Environment(\.widgetSize) private var size
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(streak.current) Tage").font(size == .s ? .title3.weight(.semibold) : .largeTitle.weight(.semibold)).monospacedDigit()
            if !streak.last7.isEmpty {
                HStack(spacing: 4) {
                    ForEach(Array(streak.last7.enumerated()), id: \.offset) { _, on in
                        Circle().fill(on ? Color.accentColor : Color.secondary.opacity(0.2))
                            .overlay { Circle().strokeBorder(Color.secondary.opacity(on ? 0 : 0.4), lineWidth: 1) }
                            .frame(width: 12, height: 12)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text("Letzte 7 Tage: an \(streak.daysRead) gelesen"))
            }
            Text("In Folge gelesen · längste Strähne: \(streak.longest) Tage").font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// Widget "Lesen" (kind "reading"): Daten kommen mit den Seiten-Widgets
struct ReadingWidget: View {
    let value: WidgetLogic.Value?
    @Environment(\.widgetSize) private var size
    var body: some View {
        if let r = BookOrbitLogic.reading(value?.dict) {
            ReadingContentView(reading: r, compact: size == .s)
        } else {
            Text(verbatim: "–").foregroundStyle(.secondary)
        }
    }
}

// MARK: - Heute: Karte "Lesen"

/// Nur sichtbar, wenn ein BookOrbit verbunden ist (abgelaufen/nicht erreichbar: kurzer Hinweis).
/// Der Zustand liegt in TodayScreen und wird dort per .task geladen: ein leeres `Group { if … }` hat keine Kinder,
/// ein daran gehaengtes .task liefe nie (die Karte bliebe immer aus).
struct ReadingTodaySection: View {
    @ObservedObject var store: Store
    let reading: BookOrbitLogic.Reading?
    var body: some View {
        if store.features.has(BookOrbitLogic.feature), let r = reading, BookOrbitLogic.todayVisible(r) {
            Section {
                ReadingContentView(reading: r, compact: true, limit: BookOrbitLogic.todayLimit)
                if r.state == .ok, let s = r.streak, s.current > 0 {
                    Label { Text("\(s.current) Tage in Folge gelesen") } icon: { Image(systemName: "flame") }
                        .font(.footnote).foregroundStyle(.secondary)
                }
            } header: { Text("Lesen##BookOrbit") }   // "Lesen" allein ist die Rolle (View)
        }
    }

    /// Leseliste fuer die Heute-Karte; offline/Fehler: bisherigen Stand behalten
    @MainActor static func load(_ store: Store, keeping old: BookOrbitLogic.Reading?) async -> BookOrbitLogic.Reading? {
        guard store.features.has(BookOrbitLogic.feature) else { return nil }
        guard let d = try? await store.api("api/bookorbit/reading"),
              let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return old }
        var r = BookOrbitLogic.reading(j)
        r?.show = .current
        return r
    }
}

// MARK: - Einstellungen -> Dienste -> BookOrbit

struct BookOrbitSettingsSection: View {
    @EnvironmentObject var store: Store
    @State private var status: BookOrbitLogic.Status?
    @State private var url = ""
    @State private var user = ""
    @State private var password = ""
    @State private var msg = ""
    @State private var busy = false
    @State private var askOff = false
    var body: some View {
        if store.features.has(BookOrbitLogic.feature) {
            Section {
                if status?.available == false {
                    Text("BookOrbit ist auf diesem Server nicht verfügbar.").font(.footnote).foregroundStyle(.secondary)
                } else {
                    if let s = status, s.connected {
                        let who = s.name.map { String(localized: "✅ Verbunden als \($0)") } ?? String(localized: "✅ Verbunden")
                        Text(verbatim: who + (s.host.isEmpty ? "" : " (\(s.host))"))
                        if s.state == .expired {
                            Label("BookOrbit-Anmeldung abgelaufen – bitte neu anmelden.", systemImage: "exclamationmark.triangle")
                                .font(.footnote).foregroundStyle(.orange)
                        } else {
                            Text("Deine Leseliste gibt es unter „Heute“ und als Widget „Lesen“ auf Seiten.").font(.footnote).foregroundStyle(.secondary)
                        }
                        Button("Trennen", role: .destructive) { askOff = true }.disabled(busy)
                    }
                    // nicht verbunden oder abgelaufen: (neu) anmelden - wie im Web
                    if status != nil && (status?.connected != true || status?.state == .expired) {
                        Text("Adresse deines BookOrbit (z. B. https://bookorbit.example.org) und dein BookOrbit-Konto. Saga meldet sich einmal an und speichert nur ein Sitzungs-Token (verschlüsselt), nie das Passwort. Saga liest nur: was du gerade liest, dein Leseziel und deine Lesesträhne.")
                            .font(.footnote).foregroundStyle(.secondary)
                        TextField("BookOrbit-Adresse", text: $url, prompt: Text(verbatim: "https://bookorbit.example.org"))
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .keyboardType(.URL).textInputAutocapitalization(.never)
                            #endif
                        TextField("Benutzername", text: $user)
                            .autocorrectionDisabled().textContentType(.username)
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            #endif
                        SecureField("Passwort", text: $password).textContentType(.password)
                        Button(busy ? "Prüfe …" : "Anmelden") { Task { await connect() } }
                            .disabled(busy || !BookOrbitLogic.canConnect(url: url, username: user, password: password))
                    }
                }
                if !msg.isEmpty { Text(verbatim: msg).font(.footnote).foregroundStyle(.red) }
            } header: { Text(verbatim: "📚 BookOrbit") }
            .task { await load() }
            .confirmationDialog("BookOrbit trennen?", isPresented: $askOff, titleVisibility: .visible) {
                Button("Trennen", role: .destructive) { Task { await disconnect() } }
                Button("Abbrechen", role: .cancel) {}
            } message: {
                Text("Saga meldet sich bei BookOrbit ab und zeigt deine Leseliste nicht mehr an.")
            }
        }
    }
    private func load() async {
        guard let d = try? await store.api("api/bookorbit/status") else { return }
        status = BookOrbitLogic.status((try? JSONSerialization.jsonObject(with: d)) as? [String: Any])
        if url.isEmpty { url = status?.url ?? status?.suggest ?? "" }
    }
    private func connect() async {
        busy = true; defer { busy = false }
        msg = ""
        do {
            _ = try await store.api("api/bookorbit/connect", method: "POST", body: BookOrbitLogic.connectBody(url: url, username: user, password: password), timeout: 30)
            password = ""
            await load()
        } catch {
            password = ""
            msg = error.localizedDescription
        }
    }
    private func disconnect() async {
        busy = true; defer { busy = false }
        do { _ = try await store.api("api/bookorbit/disconnect", method: "POST"); msg = "" }
        catch { msg = error.localizedDescription }
        await load()
    }
}
