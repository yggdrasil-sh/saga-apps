import SwiftUI

// Saga 2.5 Vorlagengalerie, einfache Auswahl (SPEC-galerie Abschnitt 5): "Neu" -> "Aus Vorlage …" -> Liste
// (gruppiert nach Kategorie) -> Bestaetigung mit "Mit Beispielen" -> POST /api/gallery/{id}/use -> Root-Seite oeffnen.
// Logik in GalleryLogic.swift. Keine Vorschau (die gibt es im Web).

extension Store {
    /// Bietet der Server die Galerie an? Leise, einmal je Server (aelterer Server 404 / abgeschaltet -> Eintrag weg)
    func probeGallery() async {
        guard let o = AppConfig.serverOrigin,
              let (_, code) = try? await apiResponse(GalleryLogic.listPath(lang: AppConfig.appLanguage)),
              AppConfig.serverOrigin == o, let v = GalleryLogic.offered(code: code) else { return }   // offline/5xx: spaeter wieder
        galleryOrigin = o
        if galleryOffered != v { galleryOffered = v }
    }

    /// Ergebnis einer Galerie-Anfrage: Wert, eingeordneter Fehler oder Netzfehler (Text)
    enum GalleryResult<T> { case ok(T), failure(GalleryLogic.Failure), offline(String) }

    func loadGallery() async -> GalleryResult<[GalleryLogic.Item]> {
        do {
            let (d, code) = try await apiResponse(GalleryLogic.listPath(lang: AppConfig.appLanguage))
            if let v = GalleryLogic.offered(code: code) { galleryOrigin = AppConfig.serverOrigin; if galleryOffered != v { galleryOffered = v } }
            guard (200..<300).contains(code) else { return .failure(GalleryLogic.failure(code: code, data: d, list: true)) }
            guard let items = GalleryLogic.parseList(d) else { return .failure(.badResponse) }
            return .ok(items)
        } catch { return .offline(error.localizedDescription) }
    }

    /// 2.5: Vorschau lesen - fragt die Vorlage nach dem Ort fuers Wetter? (Fehler/aelterer Server: nein)
    func galleryAsksWeather(_ id: String) async -> Bool {
        guard let path = GalleryLogic.previewPath(id, lang: AppConfig.appLanguage),
              let (d, code) = try? await apiResponse(path), (200..<300).contains(code) else { return false }
        return GalleryLogic.askWeather(d)
    }
    /// Ortssuche des Servers (wie der Widget-Editor im Web)
    func geocode(_ q: String) async -> Result<[GalleryLogic.Place], GalleryLogic.GeoFailure> {
        guard let path = GalleryLogic.geocodePath(q) else { return .success([]) }
        guard let (d, code) = try? await apiResponse(path) else { return .failure(.unavailable) }
        let f = GalleryLogic.geoFailure(code: code)
        guard f == .none, let p = GalleryLogic.places(d) else { return .failure(f == .none ? .unavailable : f) }
        return .success(p)
    }

    func useGallery(_ id: String, parent: Int?, examples: Bool, place: GalleryLogic.Place? = nil) async -> GalleryResult<GalleryLogic.Created> {
        guard let path = GalleryLogic.usePath(id) else { return .failure(.invalid) }
        do {
            let (d, code) = try await apiResponse(path, method: "POST", body: GalleryLogic.useBody(parent: parent, examples: examples, place: place), timeout: 60)
            guard (200..<300).contains(code) else {
                let f = GalleryLogic.failure(code: code, data: d)
                if f == .gone { galleryOrigin = nil; await probeGallery() }   // Galerie inzwischen abgeschaltet? neu fragen
                return .failure(f)
            }
            guard let c = GalleryLogic.parseCreated(d) else { return .failure(.badResponse) }
            await refresh()                                       // Seitenliste neu: das Bundle kann viele Seiten anlegen
            return .ok(c)
        } catch { return .offline(error.localizedDescription) }
    }
}

/// Freundlicher Text zu einem Galerie-Fehler
func galleryFailureText(_ f: GalleryLogic.Failure) -> String {
    switch f {
    case .unsupported: return String(localized: "Dieser Server bietet keine Vorlagengalerie an.")
    case .gone: return String(localized: "Die Vorlage oder die Elternseite gibt es nicht mehr.")
    case .rateLimited: return String(localized: "Zu viele Vorlagen in kurzer Zeit – bitte einen Moment warten.")
    case .forbidden: return String(localized: "Hier darfst du keine Seiten anlegen.")
    case .invalid: return String(localized: "Die Vorlage lässt sich hier nicht anlegen.")
    case .message(let m): return m
    case .failed, .badResponse: return String(localized: "Die Vorlage konnte nicht angelegt werden. Bitte später erneut versuchen.")
    }
}

/// Aufruf der Galerie: unter welcher Seite angelegt wird (nil = oberste Ebene)
struct GalleryRequest: Identifiable, Equatable {
    var parent: Int?
    let id = UUID()
}

extension View {
    /// Blatt "Aus Vorlage …"; `onCreated` bekommt die Root-Seite (Seitenliste ist dann schon neu geladen)
    func galleryPicker(_ request: Binding<GalleryRequest?>, store: Store, onCreated: @escaping (Int) -> Void) -> some View {
        sheet(item: request) { r in
            GallerySheet(parent: r.parent, onCreated: onCreated).environmentObject(store)
        }
    }
}

struct GallerySheet: View {
    let parent: Int?
    let onCreated: (Int) -> Void
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var groups: [GalleryLogic.Group]?
    @State private var loadError: String?
    @State private var chosen: GalleryLogic.Item?

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(Text("Aus Vorlage"))
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
                .navigationDestination(item: $chosen) { item in
                    GalleryConfirm(item: item, parent: parent) { root in dismiss(); onCreated(root) }
                }
        }
        .task { await load() }
        .macFrame(minWidth: 440, idealWidth: 500, minHeight: 460, idealHeight: 560)
        #if os(iOS)
        .presentationSizing(.form)                                // iPad: Formular-Fenster, iPhone: volles Blatt
        #endif
    }

    @ViewBuilder private var content: some View {
        if let groups {
            if groups.isEmpty {
                ContentUnavailableView { Label("Keine Vorlagen verfügbar", systemImage: "square.grid.2x2") }
            } else {
                List {
                    ForEach(groups) { g in
                        Section {
                            ForEach(g.items) { item in
                                Button { chosen = item } label: { GalleryRow(item: item) }
                                    .buttonStyle(.plain)
                            }
                        } header: { Text(Self.categoryTitle(g.category)) }
                    }
                }
                #if os(macOS)
                .listStyle(.inset)
                #endif
            }
        } else if let loadError {
            ContentUnavailableView {
                Label("Vorlagen konnten nicht geladen werden", systemImage: "exclamationmark.triangle")
            } description: { Text(verbatim: loadError) } actions: {
                Button("Erneut versuchen") { Task { await load() } }
            }
        } else {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    static func categoryTitle(_ c: GalleryLogic.Category) -> String {
        switch c {
        case .personal: return String(localized: "Persönlich")
        case .work: return String(localized: "Arbeit")
        case .team: return String(localized: "Team")
        case .other: return String(localized: "Weitere")
        }
    }

    private func load() async {
        loadError = nil
        switch await store.loadGallery() {
        case .ok(let items): groups = GalleryLogic.groups(items)
        case .failure(let f): groups = nil; loadError = galleryFailureText(f)
        case .offline(let m): groups = nil; loadError = m
        }
    }
}

extension GalleryLogic.Item: Hashable {
    func hash(into h: inout Hasher) { h.combine(id) }
}

private struct GalleryIcon: View {
    let icon: String
    var size: CGFloat = 22
    var body: some View {
        Group {
            if icon.isEmpty { Image(systemName: "doc.text").foregroundStyle(.secondary) } else { Text(verbatim: icon) }
        }
        .font(.system(size: size)).frame(width: size * 1.5, height: size * 1.5)
        .accessibilityHidden(true)
    }
}

private struct GalleryRow: View {
    let item: GalleryLogic.Item
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            GalleryIcon(icon: item.icon)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: item.title).font(.body.weight(.medium))
                if !item.desc.isEmpty { Text(verbatim: item.desc).font(.subheadline).foregroundStyle(.secondary).lineLimit(3) }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary).accessibilityHidden(true)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// Kurze Bestaetigung: was angelegt wird, Schalter "Mit Beispielen", Knopf "Verwenden"
private struct GalleryConfirm: View {
    let item: GalleryLogic.Item
    let parent: Int?
    let done: (Int) -> Void
    @EnvironmentObject private var store: Store
    @State private var examples = true
    @State private var busy = false
    @State private var error: String?
    /// 2.5: Wetter-Widget ohne Ort - vor dem Anlegen nach dem Ort fragen (nil = noch unbekannt)
    @State private var askWeather: Bool?
    @State private var asking = false
    @State private var query = ""
    @State private var hits: [GalleryLogic.Place]?
    @State private var geoNote: String?
    @State private var searching = false

    var body: some View {
        Form {
            Section {
                HStack(alignment: .top, spacing: 14) {
                    GalleryIcon(icon: item.icon, size: 34)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: item.title).font(.title3.weight(.semibold))
                        if !item.desc.isEmpty { Text(verbatim: item.desc).foregroundStyle(.secondary) }
                    }
                }
                .padding(.vertical, 4)
                .accessibilityElement(children: .combine)
            }
            if !item.contents.isEmpty {
                Section("Enthält") {
                    ForEach(Array(item.contents.enumerated()), id: \.offset) { _, c in
                        Label {
                            Text(verbatim: c.title)
                        } icon: {
                            if c.icon.isEmpty { Image(systemName: c.kind == "database" ? "tablecells" : "doc.text") } else { Text(verbatim: c.icon) }
                        }
                    }
                }
            }
            Section {
                Toggle("Mit Beispielen", isOn: $examples).disabled(busy)
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Legt Beispieleinträge an, die du jederzeit löschen kannst.")
                    if let p = parent, let t = store.page(p)?.title, !t.isEmpty { Text("Wird unter „\(t)“ angelegt.") }
                }
            }
            if let error {
                Section { Label { Text(verbatim: error) } icon: { Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange) } }
            }
            if asking {
                Section {
                    Text("Die Vorlage enthält ein Wetter-Widget. Für welchen Ort?")
                    HStack {
                        TextField(String(localized: "Ort suchen …"), text: $query)
                            .textFieldStyle(.roundedBorder)
                            .onSubmit { Task { await search() } }
                            .disabled(busy)
                            .accessibilityLabel(Text("Ort suchen …"))
                        Button(String(localized: "Suchen")) { Task { await search() } }
                            .disabled(busy || searching || GalleryLogic.geocodePath(query) == nil)
                    }
                    if searching { ProgressView().controlSize(.small) }
                    if let geoNote { Text(verbatim: geoNote).foregroundStyle(.secondary) }
                    ForEach(hits ?? []) { p in
                        Button { Task { await use(place: p) } } label: { Label(p.label, systemImage: "mappin.and.ellipse") }
                            .disabled(busy)
                    }
                    Button(String(localized: "Überspringen")) { Task { await use(place: nil) } }.disabled(busy)
                } header: { Text("Ort fürs Wetter") }
            }
            Section {
                Button { Task { await go() } } label: {
                    HStack {
                        Spacer()
                        if busy { ProgressView().controlSize(.small); Text("Wird angelegt …") } else { Text("Verwenden").bold() }
                        Spacer()
                    }
                }
                .disabled(busy || asking)
                .keyboardShortcut(.defaultAction)
            }
        }
        .formStyle(.grouped)
        .task { if askWeather == nil { askWeather = await store.galleryAsksWeather(item.id) } }
        .navigationTitle(Text(verbatim: item.title))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .interactiveDismissDisabled(busy)
    }

    /// "Verwenden": fragt die Vorlage nach dem Ort (ask_weather), erst fragen; sonst gleich anlegen
    private func go() async {
        let a: Bool
        if let w = askWeather { a = w } else { busy = true; a = await store.galleryAsksWeather(item.id); busy = false; askWeather = a }
        if a && !asking { asking = true } else { await use(place: nil) }
    }

    private func search() async {
        guard GalleryLogic.geocodePath(query) != nil, !searching else { return }
        searching = true; geoNote = nil
        defer { searching = false }
        switch await store.geocode(query) {
        case .success(let p): hits = p; geoNote = p.isEmpty ? String(localized: "Kein Ort gefunden") : nil
        case .failure(let f): hits = nil; geoNote = f == .rateLimited ? String(localized: "Zu viele Anfragen – bitte kurz warten.") : String(localized: "Ortssuche nicht erreichbar")
        }
    }

    private func use(place: GalleryLogic.Place?) async {
        busy = true; error = nil
        defer { busy = false }
        switch await store.useGallery(item.id, parent: parent, examples: examples, place: place) {
        case .ok(let c): done(c.root)
        case .failure(let f): error = galleryFailureText(f)
        case .offline(let m): error = m
        }
    }
}
