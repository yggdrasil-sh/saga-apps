import SwiftUI

// Teilen (Saga 1.3) wie im Web: Kopfzeile fremder Seiten, Teilen-Dialog (nur Eigentuemer), Platzhalter fuer Bloecke des
// Eigentuemers, "Kein Zugriff" und Kopier-Dialog (Konflikt, Zugriff verloren). Logik: ShareLogic.swift

// MARK: - Rollen-Texte (Wortlaut wie im Web; "##Rolle": im Englischen "Can edit" statt der Aktion "Edit")

enum RoleText {
    static func name(_ r: ShareRole) -> String {
        switch r {
        case .owner: return String(localized: "Eigentümer")
        case .edit: return String(localized: "Bearbeiten##Rolle")
        case .comment: return String(localized: "Kommentieren##Rolle")
        case .view: return String(localized: "Lesen##Rolle")
        }
    }
    /// Etikett auf fremden Seiten: "Du kannst lesen" ...
    static func you(_ r: ShareRole) -> String {
        switch r {
        case .owner: return ""
        case .edit: return String(localized: "Du kannst bearbeiten")
        case .comment: return String(localized: "Du kannst lesen und kommentieren")
        case .view: return String(localized: "Du kannst lesen")
        }
    }
    /// Erklaerzeile unter der Rollenauswahl
    static func help(_ r: ShareRole) -> String {
        switch r {
        case .view, .owner: return String(localized: "Lesen: Seite, Unterseiten und Einträge ansehen und exportieren.")
        case .comment: return String(localized: "Kommentieren: lesen und Kommentare schreiben.")
        case .edit: return String(localized: "Bearbeiten: Inhalte ändern und Unterseiten anlegen. Löschen, Verschieben und Teilen bleibt bei dir.")
        }
    }
    /// "über team" bzw. "über „Projektplan“"
    static func via(_ v: ShareVia) -> String { v.isGroup ? v.name : String(localized: "„\(v.title ?? String(localized: "Unbenannt"))“") }
}

// MARK: - Kopfzeile fremder Seiten

/// "Geteilt von {Name} · Du kannst lesen" unter dem Titel - Etikett, kein Knopf. Gleiche Groesse/Farbe wie die
/// Zeile "Zuletzt geaendert von ..." darunter (Sekundaertext, gut lesbar); Symbol einfarbig.
struct SharedByLabel: View {
    let owner: String
    let role: ShareRole
    var body: some View {
        Label {
            Text(verbatim: String(localized: "Geteilt von \(owner)") + " · " + RoleText.you(role))
        } icon: { Image(systemName: "person.2") }
        .font(.subheadline).foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
    }
}

/// "Angelegt von {name} · Zuletzt geaendert von {name} · vor 5 Minuten" - nur, wenn es nicht man selbst war
struct PageMetaLine: View {
    let access: PageAccess
    let updated: Double?
    var body: some View {
        let parts = Self.parts(access, updated)
        if !parts.isEmpty {
            Text(verbatim: parts.joined(separator: " · ")).font(.subheadline).foregroundStyle(.secondary)
        }
    }
    static func parts(_ a: PageAccess, _ updated: Double?) -> [String] {
        var p: [String] = []
        if let c = a.createdByOther { p.append(String(localized: "Angelegt von \(c)")) }
        if let u = a.updatedByOther, let t = updated {
            let when = Date(timeIntervalSince1970: t).formatted(.relative(presentation: .named))
            p.append(String(localized: "Zuletzt geändert von \(u) · \(when)"))
        }
        return p
    }
}

/// Aufgaben-, Kalender-, Immich-Block auf einer fremden Seite: neutraler Platzhalter (Inhalt bleibt beim Speichern unveraendert)
struct OwnerOnlyBlock: View {
    let type: String
    var body: some View {
        let what: String = type == "tasks" ? "✓ " + String(localized: "Aufgaben") : type == "calendar" ? "🗓 " + String(localized: "Kalender") : "📷 Immich"
        Text(verbatim: what + " · " + String(localized: "Nur für den Eigentümer sichtbar"))
            .font(.subheadline).foregroundStyle(.secondary)
            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(.secondary.opacity(0.35), style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
    }
}

/// Seite nicht (mehr) erreichbar (404/403 beim Laden): Systemansicht (ueberall gleich mittig ausgerichtet),
/// "Erneut versuchen" und "Zu „Mit mir geteilt“". Die Kopfzeile zeigt dann weder Titel noch Symbol der Seite.
struct NoAccessView: View {
    var retry: () -> Void
    var toShared: () -> Void
    var body: some View {
        ContentUnavailableView {
            Label("Kein Zugriff auf diese Seite", systemImage: "lock.fill")
        } description: {
            Text("Die Seite gibt es nicht (mehr), oder sie ist nicht (mehr) mit dir geteilt.")
        } actions: {
            ViewThatFits(in: .horizontal) {                     // schmal/grosse Schrift: untereinander
                HStack(spacing: 10) { retryButton; sharedButton }
                VStack(spacing: 10) { sharedButton; retryButton }
            }
            .controlSize(.regular)
        }
        .frame(maxWidth: .infinity).padding(.top, 40)
    }
    private var retryButton: some View {
        Button { retry() } label: { Label("Erneut versuchen", systemImage: "arrow.clockwise") }.buttonStyle(.bordered).fixedSize()
    }
    private var sharedButton: some View {
        Button { toShared() } label: { Text("Zu „Mit mir geteilt“") }.prominentGold().fixedSize()
    }
}

/// Text zum Kopieren: Entwurf nach "Neue Version laden", Zugriff verloren / nur noch lesen
struct CopyTextDialog: Identifiable, Equatable {
    enum Kind: Equatable { case draft, lostAccess, readOnlyNow }
    let id = UUID()
    let kind: Kind
    let text: String
    var title: String {
        switch kind {
        case .draft: return String(localized: "Dein Entwurf")
        case .lostAccess: return String(localized: "Kein Zugriff mehr")
        case .readOnlyNow: return String(localized: "Nur noch lesen")
        }
    }
    var message: String {
        switch kind {
        case .draft: return String(localized: "Die neue Version ist geladen. Dein nicht gespeicherter Text steht unten – kopiere, was du behalten willst.")
        case .lostAccess: return String(localized: "Du hast keinen Zugriff mehr auf diese Seite. Dein letzter Text ist unten – kopiere ihn, wenn du ihn behalten willst.")
        case .readOnlyNow: return String(localized: "Du darfst diese Seite nicht mehr bearbeiten. Dein letzter Text ist unten – kopiere ihn, wenn du ihn behalten willst.")
        }
    }
}

/// Zustandsdialog (Konflikt-Entwurf, Kein Zugriff mehr, Nur noch lesen) - ueberall gleich aufgebaut: Titel, kurzer
/// Text, Textfeld (nur so hoch wie noetig, hoechstens ~40 % der Bildschirmhoehe), "Kopieren" und "Schliessen".
/// Das Blatt ist nur so hoch wie sein Inhalt (iPhone/iPad: eigene Hoehe statt ganzer Bildschirm).
struct CopyTextSheet: View {
    let dialog: CopyTextDialog
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var textH: CGFloat = 0
    @State private var totalH: CGFloat = 0
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(dialog.title).font(.headline).accessibilityAddTraits(.isHeader)
            Text(dialog.message).fixedSize(horizontal: false, vertical: true)
            ScrollView {
                Text(verbatim: dialog.text).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).padding(10)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { textH = $0 }
            }
            .scrollBounceBehavior(.basedOnSize)
            .frame(height: min(max(textH, 44), Self.maxTextHeight))
            .background(.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
            .accessibilityLabel(Text("Dein Text"))
            HStack {
                Button { copyText(dialog.text); copied = true } label: { Label(copied ? String(localized: "Text kopiert") : String(localized: "Kopieren"), systemImage: copied ? "checkmark" : "doc.on.doc") }
                    .prominentGold()
                Button("Schließen") { dismiss() }.buttonStyle(.bordered).keyboardShortcut(.cancelAction)
            }
            .controlSize(.large)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { totalH = $0 }
        #if os(iOS)
        .presentationDetents([.height(max(totalH, 220))])                   // iPhone
        .presentationSizing(.form.fitted(horizontal: false, vertical: true)) // iPad
        .presentationBackground(.background)
        #endif
        .macFrame(minWidth: 460, idealWidth: 520)
        .interactiveDismissDisabled()
    }
    /// ~40 % der Bildschirmhoehe
    private static var maxTextHeight: CGFloat {
        #if os(iOS)
        let h = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.screen.bounds.height }.first ?? 800
        #else
        let h = NSScreen.main?.visibleFrame.height ?? 800
        #endif
        return (h * 0.4).rounded()
    }
}

// MARK: - Teilen-Dialog (nur Eigentuemer)

extension Store {
    func shareState(_ id: Int) async throws -> ShareState {
        let d = try await api("api/notes/\(id)/shares")
        guard let s = ShareState(data: d) else { throw APIError.other(String(localized: "Keine Antwort vom Server")) }
        return s
    }
}

/// Kreis mit Anfangsbuchstabe bzw. Gruppen-Symbol
struct ShareAvatar: View {
    let name: String
    let group: Bool
    var body: some View {
        ZStack {
            Circle().fill(Color.accentColor.opacity(0.16))
            if group { Image(systemName: "person.2.fill").font(.caption) } else { Text(verbatim: String(name.prefix(1)).uppercased()).font(.subheadline.weight(.semibold)) }
        }
        .frame(width: 32, height: 32).accessibilityHidden(true)
    }
}

struct ShareSheet: View {
    let id: Int
    /// Anzeigename des Eigentuemers (ich)
    let ownerName: String?
    /// Seite hat (keine) Freigaben mehr -> Seitenkopf/Seitenleiste nachfuehren
    var onShared: (Bool) -> Void = { _ in }
    /// "ueber „Elternseite“" -> dort oeffnen
    var openPage: (Int) -> Void = { _ in }
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var st: ShareState?
    @State private var loadError: String?
    @State private var q = ""
    @State private var role: ShareRole = .view
    @State private var hits: [ShareTarget] = []
    @State private var searchError: String?
    @State private var status: PubStatus?
    @State private var itemError: [String: String] = [:]
    @State private var askRemove: ShareEntry?

    private var link: String { AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/notes#\(id)" }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                #if os(macOS)
                Text("Teilen").font(.headline).padding(.top, 14)
                #endif
                if let st { form(st) }
                else if let e = loadError {
                    VStack(spacing: 12) {
                        Label(e, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                        Button("Erneut versuchen") { loadError = nil; Task { await load() } }
                    }.padding().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
            }
            #if os(iOS)
            .navigationTitle("Teilen")
            .navigationBarTitleDisplayMode(.inline)
            #endif
            // Mac: Ueberschrift steht oben im Blatt - die Leiste zeigte sie sonst ein zweites Mal
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
        .macFrame(minWidth: 480, idealWidth: 540, minHeight: 520, idealHeight: 660)
        .task {
            await load()
            #if DEBUG
            if let v = UserDefaults.standard.string(forKey: "hkShareQuery") { q = v }      // nur fuer Tests: Suche vortippen
            #endif
        }
        .task(id: q) { await search() }
        .alert("Freigabe entfernen?", isPresented: Binding(get: { askRemove != nil }, set: { if !$0 { askRemove = nil } }), presenting: askRemove) { e in
            Button("Entfernen", role: .destructive) { Task { await remove(e) } }
            Button("Abbrechen", role: .cancel) {}
        } message: { e in Text(verbatim: Self.removeText(ShareLogic.removeEffect(e))) }
    }

    static func removeText(_ r: RemoveEffect) -> String {
        switch r {
        case .keeps(let name, let via): return String(localized: "\(name) behält über \(RoleText.via(via)) die Rolle \(RoleText.name(via.role)).")
        case .groupLoses(let name): return String(localized: "Die Gruppe \(name) verliert sofort den Zugriff auf diese Seite und ihre Unterseiten.")
        case .userLoses(let name): return String(localized: "\(name) verliert sofort den Zugriff auf diese Seite und ihre Unterseiten.")
        }
    }

    private func form(_ s: ShareState) -> some View {
        Form {
            if !s.enabled {
                Label("Der Admin hat das Teilen auf diesem Server abgeschaltet – Freigaben wirken zurzeit nicht.", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
            if !s.shareable {
                Label("Archivierte Seiten und Vorlagen lassen sich nicht teilen.", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
            Section {
                TextField("Person oder Gruppe suchen", text: $q)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .disabled(!s.canAdd)
                    .fieldError(searchError != nil)
                // Fehler bzw. "Mindestens 3 Zeichen." direkt am Feld - der Hinweis nur bei 1-2 Zeichen
                if let e = searchError {
                    Label(e, systemImage: "exclamationmark.circle.fill").font(.footnote).foregroundStyle(.red)
                } else if ShareLogic.minHint(q) {
                    Text("Mindestens 3 Zeichen.").font(.footnote).foregroundStyle(.secondary)
                }
                Picker("Rolle für neue Freigabe", selection: $role) {
                    ForEach(ShareRole.grantable, id: \.self) { r in Text(RoleText.name(r)).tag(r) }
                }
                .disabled(!s.canAdd)
                if ShareLogic.showGroupChips(s, query: q) { groupChips(s) }
                ForEach(hits) { h in hitRow(h, s) }
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    if s.scope { Text("Nur Personen aus deinen Gruppen.") }
                    if s.federation { Text("Andere Saga-Instanzen: name@server eingeben.") }
                    Text(RoleText.help(role))
                }
            }
            Section {
                Text("Unterseiten und Einträge kommen mit.").font(.footnote).foregroundStyle(.secondary)
                HStack(spacing: 10) {
                    ShareAvatar(name: ownerName ?? String(localized: "Du"), group: false)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(ownerName.map { String(localized: "\($0) (du)") } ?? String(localized: "Du"))
                        Text("Eigentümer").font(.caption).foregroundStyle(.secondary)
                    }
                }
                ForEach(s.shares) { e in entryRow(e, s) }
                if s.shares.isEmpty {
                    Text("Noch mit niemandem geteilt. Suche oben nach einer Person oder Gruppe.").font(.callout).foregroundStyle(.secondary)
                }
            } header: { Text("Zugriff") }
            if !s.inherited.isEmpty {
                Section {
                    ForEach(s.inherited) { x in inheritedRow(x) }
                } header: { Text("Über übergeordnete Seiten") } footer: { Text("Diese Freigaben gelten auch hier. Ändern kannst du sie auf der übergeordneten Seite.") }
            }
            Section {
                Text(verbatim: PublishLogic.breakable(ShotDisplay.server(link))).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                Button { copyText(link); status = .ok(String(localized: "Link kopiert")) } label: { Label("Link kopieren", systemImage: "link") }
            } footer: { Text("Der Link öffnet die Seite für alle, die Zugriff haben.") }
            if let status { PubStatusLine(status: status) }
        }
        .formStyle(.grouped)
        #if os(iOS)
        .contentMargins(.top, 4, for: .scrollContent)              // kein grosser Leerraum oben im Blatt
        #endif
    }

    private func groupChips(_ s: ShareState) -> some View {
        let taken = s.taken
        return VStack(alignment: .leading, spacing: 6) {
            Text("Deine Gruppen:").font(.footnote).foregroundStyle(.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(s.groups, id: \.self) { g in
                        let has = taken["group:" + g]
                        Button { Task { await put("group", g, role, done: String(localized: "✓ Geteilt mit \(g) (\(RoleText.name(role)))")) } } label: {
                            HStack(spacing: 6) {                   // einfarbiges Gruppen-Symbol; hat schon Zugriff -> ✓ im Chip
                                Image(systemName: "person.2").foregroundStyle(.secondary)
                                Text(verbatim: g)
                                if has != nil { Image(systemName: "checkmark").font(.caption.weight(.semibold)).foregroundStyle(Color.accentColor) }
                            }
                            .font(.subheadline)
                            .padding(.horizontal, 12).frame(minHeight: 44)
                            .background(Capsule().strokeBorder(has != nil ? Color.accentColor : Color.secondary.opacity(0.4)))
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint(has.map { String(localized: "hat Zugriff: \(RoleText.name($0))") } ?? String(localized: "Hinzufügen (\(RoleText.name(role)))"))
                    }
                }
            }
        }
    }

    private func hitRow(_ h: ShareTarget, _ s: ShareState) -> some View {
        let has = s.taken[h.id] != nil
        return Button {
            Task { q = ""; hits = []; await put(h.type, h.target, role, done: String(localized: "✓ Geteilt mit \(h.name) (\(RoleText.name(role)))")) }
        } label: {
            HStack(spacing: 10) {
                ShareAvatar(name: h.name, group: h.isGroup)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: h.name).lineLimit(3)          // umbrechen: der Server-Teil (an wen man teilt) muss lesbar bleiben
                    Text(h.isRemote ? (h.isGroup ? String(localized: "Gruppe auf einer anderen Saga-Instanz") : String(localized: "Andere Saga-Instanz"))
                         : h.username.map { String($0) } ?? String(localized: "Gruppe")).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Text(has ? String(localized: "hat Zugriff") : String(localized: "Hinzufügen (\(RoleText.name(role)))")).font(.caption).foregroundStyle(.secondary)
            }
            .frame(minHeight: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func entryRow(_ e: ShareEntry, _ s: ShareState) -> some View {
        let who = HStack(spacing: 10) {
            ShareAvatar(name: e.name, group: e.isGroup)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: e.name)
                Text(sub(e)).font(.caption).foregroundStyle(.secondary)
                if let v = e.stronger {                                  // eigene Zeile, Sekundaerfarbe (nicht Akzent, nicht kursiv)
                    Text("(über \(RoleText.via(v)): \(RoleText.name(v.role)))").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        let picker = Picker("Rolle von \(e.name)", selection: Binding(get: { e.role }, set: { r in
            Task { await put(e.type, e.target, r, done: String(localized: "✓ Rolle geändert"), item: e.id) }
        })) {
            ForEach(ShareRole.grantable, id: \.self) { r in Text(RoleText.name(r)).tag(r) }
        }
        .labelsHidden().fixedSize()
        let remove = Button { askRemove = e } label: { Image(systemName: "xmark").frame(width: 44, height: 44).contentShape(Rectangle()) }
            .buttonStyle(.plain).foregroundStyle(.secondary)
            .help("Freigabe entfernen").accessibilityLabel(Text("Freigabe für \(e.name) entfernen"))
        return VStack(alignment: .leading, spacing: 4) {
            // Rolle und × immer rechts in der Zeile; der Namensblock nimmt den Rest und darf umbrechen
            HStack(spacing: 6) { who.frame(maxWidth: .infinity, alignment: .leading); picker; remove }
            if let err = itemError[e.id] { Label(err, systemImage: "exclamationmark.circle.fill").font(.caption).foregroundStyle(.red) }
        }
    }

    private func sub(_ e: ShareEntry) -> String {
        if e.isRemote { return e.isGroup ? String(localized: "Gruppe auf einer anderen Saga-Instanz") : String(localized: "Andere Saga-Instanz") }
        guard e.isGroup else { return e.target }
        guard let m = e.members else { return String(localized: "Gruppe") }
        return m == 1 ? String(localized: "Gruppe · 1 Person") : String(localized: "Gruppe · \(m) Personen")
    }

    private func inheritedRow(_ x: InheritedShare) -> some View {
        HStack(spacing: 10) {
            ShareAvatar(name: x.name, group: x.isGroup)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: x.name)
                Text(x.isGroup ? String(localized: "Gruppe") : x.target).font(.caption).foregroundStyle(.secondary)
                Button { dismiss(); openPage(x.noteID) } label: { Text("über „\(x.title)“").font(.caption).underline() }
                    .buttonStyle(.plain).foregroundStyle(.secondary).help("Zur übergeordneten Seite")
            }
            Spacer()
            Text(RoleText.name(x.role)).font(.caption.weight(.medium))
                .padding(.horizontal, 8).padding(.vertical, 3).background(Color.secondary.opacity(0.14), in: RoundedRectangle(cornerRadius: 6))
        }
        .frame(minHeight: 44)
    }

    // MARK: Server

    private func load() async {
        do { st = try await store.shareState(id) } catch { loadError = error.localizedDescription }
    }
    private func apply(_ d: Data) {
        guard let ns = ShareState(data: d) else { return }
        st = ns; onShared(!ns.shares.isEmpty)
    }
    private func put(_ type: String, _ target: String, _ r: ShareRole, done: String, item: String? = nil) async {
        status = nil; searchError = nil
        if let item { itemError[item] = nil }
        do {
            let (d, code) = try await store.apiResponse("api/notes/\(id)/shares", method: "PUT", body: ["type": type, "target": target, "role": r.rawValue])
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            apply(d); status = .ok(done)
        } catch {
            if let item { itemError[item] = error.localizedDescription } else { searchError = error.localizedDescription }
        }
    }
    private func remove(_ e: ShareEntry) async {
        status = nil; itemError[e.id] = nil
        let t = e.target.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? e.target
        do {
            let (d, code) = try await store.apiResponse("api/notes/\(id)/shares?type=\(e.type)&target=\(t)", method: "DELETE")
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            apply(d); status = .ok(String(localized: "✓ Freigabe entfernt"))
        } catch { itemError[e.id] = error.localizedDescription }
    }
    private func search() async {
        searchError = nil
        let query = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard ShareLogic.searchReady(query) else { hits = []; return }
        try? await Task.sleep(for: .milliseconds(200))
        guard !Task.isCancelled else { return }
        let enc = query.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? query
        do {
            let d = try await store.api("api/share/targets?q=\(enc)")
            guard !Task.isCancelled else { return }
            hits = ShareLogic.parseTargets(d)
            if hits.isEmpty { searchError = String(localized: "Niemand gefunden.") }
        } catch { if !Task.isCancelled { searchError = error.localizedDescription } }
    }
}
