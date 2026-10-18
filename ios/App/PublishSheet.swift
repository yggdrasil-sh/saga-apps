import SwiftUI

// Veroeffentlichen (wie der Web-Dialog): erst der oeffentliche Name (einmal je Konto, vorgeschlagen und live
// geprueft), dann die Einstellungen der Seite. iPhone/iPad: Blatt mit Form; Mac: Blatt mit gruppierter Form.
// Logik (Zustand -> Anzeige, Fehlerfaelle): PublishLogic.swift

/// Rueckmeldung im Dialog
enum PubStatus: Equatable { case ok(String), err(String) }

struct PubStatusLine: View {
    let status: PubStatus
    var body: some View {
        switch status {
        case .ok(let t): Label(t, systemImage: "checkmark.circle.fill").foregroundStyle(.green).font(.footnote)
        case .err(let t): Label(t, systemImage: "exclamationmark.circle.fill").foregroundStyle(.red).font(.footnote)
        }
    }
}

/// Ergebnis der Live-Pruefung des oeffentlichen Namens
struct NameCheckLine: View {
    let check: NameCheck
    var body: some View {
        switch check {
        case .idle: EmptyView()
        case .checking: ProgressView().controlSize(.small).accessibilityLabel(Text("Prüfe …"))
        case .ok: Label("Frei", systemImage: "checkmark.circle.fill").foregroundStyle(.green).font(.footnote)
        case .bad(let m): Label(m ?? String(localized: "Server nicht erreichbar"), systemImage: "xmark.circle.fill").foregroundStyle(.red).font(.footnote)
        }
    }
}

extension Store {
    /// Oeffentlichen Namen pruefen (gueltig und frei), ohne zu speichern
    func checkPublicName(_ name: String) async -> NameCheck {
        guard let r = try? await apiResponse("api/account/public-name", method: "PUT", body: ["name": name, "check": true]) else { return .bad(nil) }
        return NameCheck.parse(code: r.1, data: r.0)
    }
}

/// Fehler direkt am Feld: Rahmen rot (Text darunter steht beim Feld selbst)
struct FieldErrorMark: ViewModifier {
    let on: Bool
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 6).padding(.vertical, 3)
            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(on ? Color.red : Color.clear, lineWidth: 1))
    }
}
extension View {
    func fieldError(_ on: Bool) -> some View { modifier(FieldErrorMark(on: on)) }
}

struct PublishSheet: View {
    let id: Int
    /// Zustand geaendert (Seitenkopf nachfuehren)
    var onChange: (PublishState) -> Void = { _ in }
    /// Elternseite oeffnen ("Oeffentlich ueber ...")
    var openPage: (Int) -> Void = { _ in }
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var st: PublishState?
    @State private var loadError: String?
    @State private var naming = false
    @State private var nameInput = ""
    @State private var nameCheck: NameCheck = .idle
    @State private var slugInput = ""
    /// Rueckmeldung und das Feld, an dem sie steht
    @State private var status: PubStatus?
    @State private var statusField: PubField = .main
    @State private var busy = false
    @State private var askStop = false
    @State private var askSlug = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                #if os(macOS)
                Text("Veröffentlichen").font(.headline).padding(.top, 14)   // Mac-Blaetter zeigen keinen Navigationstitel
                #endif
                content
            }
            .navigationTitle("Veröffentlichen")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                if naming {                                       // Namensschritt: Abbrechen / Weiter
                    ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Weiter") { Task { await saveName() } }
                            .disabled(!nameCheck.isOK || busy)
                            .keyboardShortcut(.defaultAction)
                    }
                } else {
                    ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
                }
            }
        }
        .macFrame(minWidth: 480, idealWidth: 540, minHeight: 520, idealHeight: 680)
        .task { await load() }
    }

    @ViewBuilder private var content: some View {
        if naming {
            nameStep(st)                                          // auch ohne Zustand (Server meldet need_name)
        } else if let st {
            mainForm(st)
        } else if let e = loadError {
            VStack(spacing: 12) {
                Label(e, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                Button("Erneut versuchen") { loadError = nil; Task { await load() } }
            }
            .padding().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func say(_ s: PubStatus?, at f: PubField = .main) { status = s; statusField = f }
    /// Rueckmeldung an genau diesem Feld
    @ViewBuilder private func feedback(_ f: PubField) -> some View {
        if let status, statusField == f { PubStatusLine(status: status) }
    }
    private func hasError(_ f: PubField) -> Bool {
        if case .err? = status, statusField == f { return true }
        return false
    }

    // MARK: Schritt 1: oeffentlicher Name

    /// Fehler im Namensschritt: Pruefung beim Tippen oder Speichern - ersetzt den Hilfetext
    private var nameError: String? {
        if case .bad(let m) = nameCheck { return m ?? String(localized: "Server nicht erreichbar") }
        if case .err(let t)? = status { return t }
        return nil
    }

    private func nameStep(_ s: PublishState?) -> some View {
        Form {
            Section {
                Text("Wähle zuerst deinen öffentlichen Namen. Er steht in jeder Adresse deiner öffentlichen Seiten und ist für alle sichtbar.")
                LabeledContent("Öffentlicher Name") {
                    TextField("Öffentlicher Name", text: $nameInput, prompt: Text(verbatim: s?.suggestedName ?? ""))
                        .labelsHidden().autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never).keyboardType(.asciiCapable)
                        #endif
                        .onSubmit { if nameCheck.isOK && !busy { Task { await saveName() } } }
                        .fieldError(nameError != nil)
                }
                Text(verbatim: PublishLogic.breakable(PublishLogic.namePreview(PublishLogic.normalize(nameInput), server: AppConfig.serverString)))
                    .font(.callout.monospaced()).foregroundStyle(.secondary)
                if nameCheck == .checking || nameCheck == .ok { NameCheckLine(check: nameCheck) }
            } footer: {
                if let e = nameError {
                    Label(e, systemImage: "exclamationmark.circle.fill").foregroundStyle(.red)
                } else {
                    Text("Nicht dein Anmeldename: wähle etwas, das alle sehen dürfen. 2–32 Zeichen, a–z, 0–9, - und _.")
                }
            }
        }
        .formStyle(.grouped)
        .task(id: PublishLogic.normalize(nameInput)) { await runNameCheck() }
    }

    private func runNameCheck() async {
        let v = PublishLogic.normalize(nameInput)
        status = nil                                              // alte Meldung verschwindet beim Weitertippen
        guard !v.isEmpty else { nameCheck = .idle; return }
        nameCheck = .checking
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled else { return }
        let r = await store.checkPublicName(v)
        guard !Task.isCancelled, PublishLogic.normalize(nameInput) == v else { return }
        nameCheck = r
    }

    private func saveName() async {
        guard nameCheck.isOK, !busy else { return }
        let v = PublishLogic.normalize(nameInput)
        busy = true; defer { busy = false }
        do {
            let (d, c) = try await store.apiResponse("api/account/public-name", method: "PUT", body: ["name": v])
            guard (200..<300).contains(c) else { nameCheck = NameCheck.parse(code: c, data: d); return }
            await load()                                          // jetzt mit Namen -> Einstellungen der Seite
        } catch { say(.err(error.localizedDescription)) }
    }

    // MARK: Schritt 2: Einstellungen der Seite
    // Reihenfolge: 1 Schalter -> 2 Link-Karte -> 3 Adresse -> 4 Optionen -> 5 "Veroeffentlichung beenden"

    private func mainForm(_ s: PublishState) -> some View {
        let d = PublishDisplay(s, slugInput: slugInput, server: AppConfig.serverString)
        return Form {
            if d.serverDisabled {
                Section {
                    Label("Der Admin hat das Veröffentlichen auf diesem Server abgeschaltet – öffentliche Adressen liefern zurzeit „Nicht gefunden“.", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            }
            Section {
                Toggle(isOn: Binding(get: { s.published }, set: { on in if on { Task { await publish() } } else { askStop = true } })) {
                    VStack(alignment: .leading, spacing: 2) {
                        if d.mainTitle == .alsoOwnAddress { Text("Zusätzlich unter eigener Adresse veröffentlichen") } else { Text("Öffentlich") }
                        Text("Jeder mit dem Link kann diese Seite ohne Anmeldung lesen.").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .toggleStyle(.switch)
                .disabled(d.toggleDisabled || busy)
                if d.isHome { Label("Diese Seite ist die Startseite für Besucher.", systemImage: "house").font(.footnote) }
                if let w = d.withdrawn { withdrawnLabel(w).font(.footnote) }
                feedback(.main)
            }
            if let v = d.via { viaSection(v) }
            else if let card = d.linkCard { linkSection(card, profile: d.profileLink) }
            if d.showAddress { addressSection(s, d) }
            if d.showOptions {
                Section {
                    optionToggle("Unterseiten mit veröffentlichen", hint: Text("Unterseiten (und Einträge von Datenbanken) dieser Seite sind dann ebenfalls öffentlich."),
                                 key: "children", value: s.children)
                    optionToggle("Suchmaschinen erlauben", hint: Text("Sonst bitten wir Suchmaschinen, die Seite nicht aufzunehmen."),
                                 key: "indexable", value: s.indexable)
                    optionToggle("Als Profilseite (/@name)",
                                 hint: d.profileReplaces.map { Text("Ersetzt die bisherige Profilseite „\($0)“.") } ?? Text("Erscheint unter deiner Profiladresse – höchstens eine Seite."),
                                 key: "profile", value: s.profile)
                } header: {
                    if d.optionsPending { Text("Gilt, sobald die Seite öffentlich ist") }
                }
            }
            Section {
                if d.showStop {
                    Button("Veröffentlichung beenden", role: .destructive) { askStop = true }.disabled(busy)
                }
            } footer: {
                Text("Öffentlicher Name: @\(s.name ?? "") – ändern unter Einstellungen → Konto.")
            }
        }
        .formStyle(.grouped)
        .alert("Veröffentlichung beenden?", isPresented: $askStop) {
            Button("Beenden", role: .destructive) { Task { await stop() } }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Der Link funktioniert danach nicht mehr.")
        }
        .alert("Adresse ändern?", isPresented: $askSlug) {
            Button("Adresse ändern") {
                Task { await put(["slug": PublishLogic.normalize(slugInput)], done: String(localized: "✓ Neue Adresse gespeichert"), at: .slug) }
            }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Der bisherige Link \(PublishLogic.breakable(s.url ?? "")) funktioniert danach nicht mehr.")
        }
        #if DEBUG
        .task {                                                   // nur fuer Tests: Adresse vortippen (-hkPublishSlug x), Rueckfrage zeigen (-hkPublishAsk stop|slug)
            let ud = UserDefaults.standard
            if let v = ud.string(forKey: "hkPublishSlug") { slugInput = v }
            guard s.published, let ask = ud.string(forKey: "hkPublishAsk") else { return }
            try? await Task.sleep(for: .seconds(1))
            if ask == "stop" { askStop = true }
            if ask == "slug" { askSlug = PublishDisplay(s, slugInput: slugInput).changeSlugEnabled }
        }
        #endif
    }

    /// Link-Karte: aktiver Link mit Kopieren/Oeffnen/Teilen - solange privat die kuenftige Adresse, gedaempft (kein Link)
    private func linkSection(_ card: PublishDisplay.LinkCard, profile: String?) -> some View {
        Section {
            Text(verbatim: PublishLogic.breakable(card.url))
                .font(.callout.monospaced())
                .foregroundStyle(card.isLive ? Color.primary : Color.secondary)
                .accessibilityLabel(Text(verbatim: card.url))
            if card.isLive { linkButtons(card.url) }
            if let p = profile {
                HStack(alignment: .center, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Profiladresse").font(.footnote).foregroundStyle(.secondary)
                        Text(verbatim: PublishLogic.breakable(p)).font(.callout.monospaced())
                            .foregroundStyle(card.isLive ? Color.primary : Color.secondary)
                            .accessibilityLabel(Text(verbatim: p))
                    }
                    Spacer(minLength: 0)
                    if card.isLive {
                        Button { copyText(p); say(.ok(String(localized: "Link kopiert")), at: .link) } label: { Image(systemName: "doc.on.doc") }
                            .buttonStyle(.borderless)
                            .help(Text("Link kopieren"))
                            .accessibilityLabel(Text("Link kopieren"))
                    }
                }
            }
            feedback(.link)
        } header: { Text("Link") }
    }

    private func addressSection(_ s: PublishState, _ d: PublishDisplay) -> some View {
        Section {
            TextField("Adresse", text: $slugInput, prompt: Text(verbatim: s.slug))
                .labelsHidden().autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never).keyboardType(.asciiCapable)
                #endif
                .onSubmit { if d.changeSlugEnabled { askSlug = true } }
                .onChange(of: slugInput) { _, _ in if statusField == .slug { status = nil } }
                .fieldError(hasError(.slug))
            feedback(.slug)
            if d.showChangeSlug {
                Button("Adresse ändern") { askSlug = true }.disabled(!d.changeSlugEnabled || busy)
            }
        } header: { Text("Adresse") } footer: {
            if d.showChangeSlug { Text("Wird erst mit „Adresse ändern“ übernommen.") }
        }
    }

    private func optionToggle(_ title: LocalizedStringKey, hint: Text, key: String, value: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Toggle(isOn: Binding(get: { value }, set: { setOption(key, $0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                    hint.font(.footnote).foregroundStyle(.secondary)
                }
            }
            .toggleStyle(.switch)
            .disabled(busy)
            feedback(.option(key))
        }
    }

    @ViewBuilder private func linkButtons(_ link: String) -> some View {
        Button { copyText(link); say(.ok(String(localized: "Link kopiert")), at: .link) } label: { Label("Link kopieren", systemImage: "doc.on.doc") }
        if let u = URL(string: link) {
            Button { openURL(u) } label: { Label("Öffnen", systemImage: "safari") }
            ShareLink(item: u) { Label("Link teilen", systemImage: "square.and.arrow.up") }
        }
    }

    /// Nur ueber die Elternseite oeffentlich: deren Link steht an Stelle der eigenen Link-Karte
    private func viaSection(_ v: PubLink) -> some View {
        Section {
            Label { Text("Diese Seite ist bereits öffentlich: „\(v.title)“ ist mit Unterseiten veröffentlicht.") } icon: { Image(systemName: "globe") }
            if let u = v.url {
                Text(verbatim: PublishLogic.breakable(u)).font(.callout.monospaced()).accessibilityLabel(Text(verbatim: u))
                linkButtons(u)
            }
            Button { dismiss(); openPage(v.noteID) } label: { Label("„\(v.title)“ öffnen", systemImage: "arrow.up.forward.square") }
            feedback(.link)
        } header: { Text("Link") } footer: {
            Text("Privat wird sie erst, wenn dort „Unterseiten mit veröffentlichen“ aus ist oder die Seite verschoben wird.")
        }
    }

    @ViewBuilder private func withdrawnLabel(_ w: PublishDisplay.Withdrawn) -> some View {
        switch w {
        case .byAdmin(let t):
            Label("Vom Admin zurückgezogen am \(when(t)). Adresse und Einstellungen sind gemerkt – du kannst die Seite wieder veröffentlichen.", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        case .byUser(let t):
            Label("Veröffentlichung beendet am \(when(t)). Adresse und Einstellungen sind gemerkt und gelten beim erneuten Veröffentlichen wieder.", systemImage: "info.circle")
        }
    }
    private func when(_ t: Double) -> String { Date(timeIntervalSince1970: t).sagaFormatted(date: .abbreviated, time: .shortened) }

    // MARK: Server

    private func apply(_ s: PublishState, resetSlug: Bool = true) {
        st = s
        if resetSlug { slugInput = s.slug }
        onChange(s)
    }

    private func load() async {
        do {
            let (d, c) = try await store.apiResponse("api/notes/\(id)/publish")
            switch PublishOutcome.parse(code: c, data: d) {
            case .state(let s):
                apply(s)
                naming = s.name == nil
                if naming && nameInput.isEmpty { nameInput = s.suggestedName ?? "" }
            case .needName(let sug, _):
                naming = true; if nameInput.isEmpty { nameInput = sug ?? "" }
            case .failed(let m, let code):
                loadError = m ?? String(localized: "Server-Fehler \(code)")
            }
            #if DEBUG
            if naming, let v = UserDefaults.standard.string(forKey: "hkPublicName") { nameInput = v }   // nur fuer Tests: Namen vortippen
            #endif
        } catch { loadError = error.localizedDescription }
    }

    private func publish() async {
        guard let s = st else { return }
        guard s.enabled else { say(.err(String(localized: "Veröffentlichen ist auf diesem Server abgeschaltet."))); return }
        await put(PublishLogic.publishBody(s, slugInput: slugInput))
    }

    /// Einstellung umschalten: veroeffentlicht -> sofort speichern; sonst nur merken (gilt beim Veroeffentlichen)
    private func setOption(_ key: String, _ v: Bool) {
        guard let old = st else { return }
        var s = old
        switch key {
        case "children": s.children = v
        case "indexable": s.indexable = v
        default: s.profile = v
        }
        st = s
        if s.published { Task { await put([key: v], resetSlug: false, revert: old, at: .option(key)) } }
    }

    /// PUT /publish; Erfolg wird an `at` gemeldet, ein Fehler an dem Feld, zu dem er gehoert (PubField.forError)
    private func put(_ body: [String: Any], done: String? = nil, resetSlug: Bool = true, revert: PublishState? = nil, at: PubField = .main) async {
        busy = true; defer { busy = false }
        do {
            let (d, c) = try await store.apiResponse("api/notes/\(id)/publish", method: "PUT", body: body)
            switch PublishOutcome.parse(code: c, data: d) {
            case .state(let ns):
                apply(ns, resetSlug: resetSlug)
                say(.ok(ns.replacedProfile.map { String(localized: "✓ Gespeichert – ersetzt die bisherige Profilseite „\($0.title)“.") }
                        ?? done ?? String(localized: "✓ Gespeichert")), at: at)
            case .needName(let sug, let m):                       // Name fehlt (z. B. in einem anderen Fenster entfernt)
                if let revert { st = revert }
                if let sug { nameInput = sug }
                naming = true; say(m.map { .err($0) })
            case .failed(let m, let code):
                if let revert { st = revert }
                say(.err(m ?? String(localized: "Server-Fehler \(code)")), at: PubField.forError(body: body, code: code))
            }
        } catch {
            if let revert { st = revert }
            say(.err(error.localizedDescription), at: PubField.forError(body: body, code: 0))
        }
    }

    /// Beenden: der Hinweis "Veroeffentlichung beendet am ..." am Schalter genuegt (kein zweites "beendet")
    private func stop() async {
        busy = true; defer { busy = false }
        do {
            let (d, c) = try await store.apiResponse("api/notes/\(id)/publish", method: "DELETE")
            switch PublishOutcome.parse(code: c, data: d) {
            case .state(let ns): apply(ns); say(nil)
            case .needName(_, let m), .failed(let m, _): say(.err(m ?? String(localized: "Server-Fehler \(c)")))
            }
        } catch { say(.err(error.localizedDescription)) }
    }
}

/// Seitenkopf: "Oeffentlich" (bzw. "Oeffentlich (ueber ...)") - antippen oeffnet den Dialog
struct PublicBadgeButton: View {
    let badge: PublicBadge
    let action: () -> Void
    @Environment(\.openURL) private var openURL
    var body: some View {
        Button(action: action) {
            switch badge {
            case .own: Label("Öffentlich", systemImage: "globe")
            case .via(let t, _): Label("Öffentlich (über „\(PublishLogic.shortTitle(t))“)", systemImage: "globe")
            }
        }
        .font(.caption).buttonStyle(.bordered).controlSize(.small)
        .help(helpText)
        .accessibilityHint(Text("Öffnet die Einstellungen zum Veröffentlichen."))
        .contextMenu {
            if let s = badge.url, let u = URL(string: s) {
                Button { copyText(s) } label: { Label("Link kopieren", systemImage: "doc.on.doc") }
                Button { openURL(u) } label: { Label("Öffentliche Seite öffnen", systemImage: "safari") }
            }
        }
    }
    private var helpText: Text {
        switch badge {
        case .own: return Text("Diese Seite ist öffentlich – klicken für Einstellungen")
        case .via(let t, _): return Text("Öffentlich als Unterseite von „\(t)“ – klicken für Einzelheiten")
        }
    }
}
