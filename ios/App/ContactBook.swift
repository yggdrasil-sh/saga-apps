import SwiftUI

// 2.6 (Bugs-DB 2027a-5): Adressbuch in der App - Einstellungen › Kontakte (Liste mit Suche, Detail mit tel:/mailto: und
// "Nachricht", Bearbeiten, Neuer Kontakt, Loeschen, Landesvorwahl) und "Kontakt bearbeiten …" im Kontakt-Menue der
// Personensuche/Gruppeninfo. Vertrag docs/APP-API.md "Kontakte (2.6, Bugs-DB 2027a, contacts2)". Logik/Tests:
// ContactBookLogic.swift. Nur mit Server-Feature contacts2 - sonst ist nichts davon zu sehen.

@MainActor
final class ContactBookModel: ObservableObject {
    typealias L = ContactBookLogic
    weak var store: Store?
    @Published private(set) var contacts: [L.Contact] = []
    @Published private(set) var total = 0
    @Published private(set) var loaded = false
    @Published private(set) var error: String?
    /// Landesvorwahl des Kontos (GET /api/contacts/settings) - nil: nicht gesetzt
    @Published private(set) var cc: String?
    @Published private(set) var ccLoaded = false
    /// Live contacts.changed (Detail laedt neu bzw. zeigt "geloescht")
    struct Change: Equatable { var id: Int; var deleted: Bool; var n: Int }
    @Published private(set) var change: Change?
    /// "Kontakt bearbeiten …" aus der Personensuche/Gruppeninfo (ContactEditPresenter an der jeweiligen Liste)
    struct EditTarget: Identifiable, Equatable { var id: Int }
    @Published var editing: EditTarget?
    private var query = ""
    private var reload: Task<Void, Never>?
    /// steigt mit clear() (Abmelden/Kontowechsel): spaete Antworten des alten Kontos verwerfen
    private var epoch = 0

    var on: Bool { store?.features.has(L.feature) == true }
    /// Vorschlag aus der Region des Geraets (Plan 2027a Frage 2)
    var suggestion: String? { L.suggestedCC(region: Locale.current.region?.identifier) }

    // MARK: Laden

    func load(q: String? = nil) async {
        if let q { query = q }
        guard on, let store else { clear(); return }
        let asked = query, ep = epoch
        guard let (d, code) = try? await store.apiResponse(L.listPath(q: asked)) else {
            if !loaded, ep == epoch { error = String(localized: "Kontakte konnten nicht geladen werden.") }
            return
        }
        guard asked == query, ep == epoch else { return }                   // Suche geaendert bzw. inzwischen anderes Konto
        let j = try? JSONSerialization.jsonObject(with: d)
        guard code == 200 else { error = L.str((j as? [String: Any])?["error"]) ?? String(localized: "Kontakte konnten nicht geladen werden."); return }
        let r = L.list(j)
        if r.contacts != contacts { contacts = r.contacts }
        total = r.total; loaded = true; error = nil
    }
    enum Fetch: Equatable { case ok(L.Contact), gone, failed(String) }
    func fetch(_ id: Int) async -> Fetch {
        guard let store else { return .failed("–") }
        do {
            let (d, code) = try await store.apiResponse(L.path(id))
            if code == 404 { return .gone }
            let j = try? JSONSerialization.jsonObject(with: d)
            if code == 200, let c = L.contact(j) { replace(c); return .ok(c) }
            return .failed(L.str((j as? [String: Any])?["error"]) ?? String(localized: "Kontakt konnte nicht geladen werden."))
        } catch { return .failed(error.localizedDescription) }
    }
    func loadSettings() async {
        let ep = epoch
        guard on, let store, let (d, code) = try? await store.apiResponse(L.settingsPath), code == 200, ep == epoch else { return }
        cc = L.cc(try? JSONSerialization.jsonObject(with: d)); ccLoaded = true
    }

    // MARK: Aendern

    /// POST (neu) bzw. PATCH mit ver. Landesvorwahl der Region nur, wenn das Konto keine hat und eine 0-Nummer dabei ist
    func save(_ d: L.Draft, base: L.Contact?) async -> L.SaveResult {
        guard let store else { return .failed(nil) }
        if !ccLoaded { await loadSettings() }
        let cc = L.hasNational(d) ? L.requestCC(setting: cc, suggestion: suggestion) : nil
        let path = base.map { L.path($0.id) } ?? "api/contacts"
        let body = base.map { L.patchBody(d, base: $0, cc: cc) } ?? L.createBody(d, cc: cc)
        do {
            let (data, status) = try await store.apiResponse(path, method: base == nil ? "POST" : "PATCH", body: body)
            let r = L.saveResult(status: status, data: data)
            switch r {
            case .ok(let c), .conflict(let c, _): replace(c)
            default: break
            }
            return r
        } catch { return .failed(error.localizedDescription) }
    }
    func delete(_ id: Int) async -> String? {
        guard let store else { return "–" }
        do {
            let (d, code) = try await store.apiResponse(L.path(id), method: "DELETE")
            guard (200..<300).contains(code) || code == 404 else {
                return L.str(ShareJSON.obj(d)?["error"]) ?? String(localized: "Kontakt konnte nicht gelöscht werden.")
            }
            contacts.removeAll { $0.id == id }; total = max(0, total - 1)
            Task { await store.chat.loadContacts() }                         // Saga-Adresse ist auch aus den Chat-Vorschlaegen weg
            return nil
        } catch { return error.localizedDescription }
    }
    /// PUT /api/contacts/settings {cc} - nil entfernt
    func setCC(_ v: String?) async -> String? {
        guard let store else { return "–" }
        let ep = epoch
        do {
            let (d, code) = try await store.apiResponse(L.settingsPath, method: "PUT", body: ["cc": v.map { $0 as Any } ?? NSNull()])
            guard ep == epoch else { return nil }                           // inzwischen abgemeldet/anderes Konto
            let j = try? JSONSerialization.jsonObject(with: d)
            guard code == 200 else { return L.str((j as? [String: Any])?["error"]) ?? String(localized: "Konnte nicht gesichert werden.") }
            cc = L.cc(j); ccLoaded = true
            return nil
        } catch { return error.localizedDescription }
    }
    /// "Nachricht": Direkt-Chat anlegen bzw. den vorhandenen bekommen -> Chat-ID
    func message(_ s: L.Saga) async -> Result<Int, MessageError> {
        guard let store else { return .failure(.init(text: "–")) }
        do {
            let (d, code) = try await store.apiResponse("api/chats", method: "POST", body: L.dmBody(s, device: store.chat.deviceID ?? ""))
            let j = ShareJSON.obj(d)
            guard (200..<300).contains(code), let id = ShareJSON.int((j?["chat"] as? [String: Any])?["id"]) else {
                return .failure(.init(text: L.str(j?["error"]) ?? String(localized: "Chat konnte nicht geöffnet werden.")))
            }
            await store.chat.loadList()
            return .success(id)
        } catch { return .failure(.init(text: error.localizedDescription)) }
    }
    struct MessageError: Error, Equatable { var text: String }

    private func replace(_ c: L.Contact) {
        if let i = contacts.firstIndex(where: { $0.id == c.id }), contacts[i] != c { contacts[i] = c }
    }

    // MARK: Live

    func live(_ type: String, _ d: [String: Any]) {
        guard type == "contacts.changed", on, let ch = L.changed(d) else { return }
        change = Change(id: ch.id, deleted: ch.deleted, n: (change?.n ?? 0) + 1)
        if ch.deleted { contacts.removeAll { $0.id == ch.id } }
        guard loaded else { return }
        reload?.cancel()
        reload = Task { [weak self] in                                      // mehrere Ereignisse kurz hintereinander: einmal laden
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            await self?.load()
        }
    }
    func clear() {
        epoch += 1; reload?.cancel(); reload = nil
        if !contacts.isEmpty { contacts = [] }
        total = 0; loaded = false; error = nil; cc = nil; ccLoaded = false; change = nil; query = ""; editing = nil
    }
}

// MARK: - Texte

enum ContactBookText {
    typealias L = ContactBookLogic
    static func type(_ t: String?) -> String {
        switch t {
        case "mobile": return String(localized: "Mobil")
        case "home": return String(localized: "Privat")
        case "work": return String(localized: "Arbeit")
        case "other": return String(localized: "Andere (Typ)")
        default: return String(localized: "Ohne Typ")
        }
    }
    static func form(_ f: String?) -> String {
        switch f {
        case "informal": return String(localized: "du (Anrede)")
        case "formal": return String(localized: "Sie (Anrede)")
        default: return "—"
        }
    }
    static func sub(_ s: L.Sub) -> String? {
        switch s {
        case .gone(let remote, let host): return remote ? String(localized: "Server \(host) nicht verbunden") : String(localized: "Konto nicht mehr aktiv")
        case .remote(let host): return String(localized: "Person auf \(host)")
        case .local(let sub): return sub
        case .notReady(let sub): return PersonRef.subline(sub, String(localized: "hat Chats noch nicht eingerichtet"))
        case .addr(let v): return v
        case .none: return nil
        }
    }
}

// MARK: - Einstellungen › Kontakte

/// Zeile auf der Startliste der Einstellungen (nur mit contacts2)
struct ContactsSettingsLink: View {
    @ObservedObject var model: ContactBookModel
    @EnvironmentObject private var store: Store
    var body: some View {
        if store.features.has(ContactBookLogic.feature) {
            NavigationLink(value: SettingsArea.kontakte) { Label("Kontakte", systemImage: "person.crop.rectangle.stack") }
        }
    }
}

private struct OpenContact: Identifiable, Hashable { var id: Int }

struct ContactBookView: View {
    typealias L = ContactBookLogic
    @ObservedObject var model: ContactBookModel
    /// Einstellungen schliessen ("Nachricht" oeffnet danach den Chat)
    var close: () -> Void
    @State private var q = ""
    @State private var creating = false
    @State private var open: OpenContact?
    @State private var askDelete: L.Contact?
    @State private var err: String?

    var body: some View {
        List {
            if let e = model.error, model.contacts.isEmpty {
                Section { Text(verbatim: e).foregroundStyle(.red) }
            }
            if model.loaded && model.contacts.isEmpty && model.error == nil {
                Section {
                    if q.trimmingCharacters(in: .whitespaces).isEmpty {
                        ContentUnavailableView {
                            Label("Noch keine Kontakte", systemImage: "person.crop.rectangle.stack")
                        } description: {
                            Text("Wer mit dir chattet, steht hier automatisch. Eigene Kontakte mit Nummer oder E-Mail legst du mit „+“ an.")
                        }
                    } else {
                        ContentUnavailableView.search(text: q)
                    }
                }
            }
            if !model.contacts.isEmpty {
                Section {
                    ForEach(model.contacts) { c in
                        NavigationLink { ContactDetailView(id: c.id, initial: c, model: model, close: close) } label: { ContactBookRow(c: c) }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) { askDelete = c } label: { Label("Löschen", systemImage: "trash") }
                            }
                    }
                } footer: {
                    if model.total > model.contacts.count {
                        Text("Weitere Kontakte findest du über die Suche.")
                    } else {
                        Text("Nur du siehst deine Kontakte. Nummern, E-Mail-Adressen und Anrede verlassen den Server nicht.")
                    }
                }
            }
            if let err { Section { Text(verbatim: err).foregroundStyle(.red) } }
            ContactsCCSection(model: model)
        }
        .navigationTitle("Kontakte")
        .navigationBarTitleDisplayMode(.inline)                              // wie die anderen Bereiche der Einstellungen
        .searchable(text: $q, prompt: Text("Name, Nummer oder E-Mail"))
        .task(id: q) {
            if !q.isEmpty { try? await Task.sleep(for: .milliseconds(350)) }   // entprellt (Rate 60/min)
            guard !Task.isCancelled else { return }
            await model.load(q: q)
        }
        .task { await model.loadSettings() }
        .refreshable { await model.load() }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { creating = true } label: { Label("Neuer Kontakt", systemImage: "plus") }
            }
        }
        .sheet(isPresented: $creating) {
            ContactEditSheet(model: model, base: nil, saved: { c in open = OpenContact(id: c.id) }, openOther: { id in open = OpenContact(id: id) })
        }
        .navigationDestination(item: $open) { o in ContactDetailView(id: o.id, initial: nil, model: model, close: close) }
        .confirmationDialog("Kontakt löschen?", isPresented: Binding(get: { askDelete != nil }, set: { if !$0 { askDelete = nil } }),
                            titleVisibility: .visible, presenting: askDelete) { c in
            Button("Löschen", role: .destructive) { Task { err = await model.delete(c.id) } }
            Button("Abbrechen", role: .cancel) {}
        } message: { c in Text(ContactDetailView.deleteMessage(c)) }
        #if DEBUG
        .task {                                                               // nur fuer Bilder: Detail/Bearbeiten direkt
            if let s = UserDefaults.standard.string(forKey: "hkContact"), let id = Int(s) {
                try? await Task.sleep(for: .seconds(1)); open = OpenContact(id: id)
            }
            if UserDefaults.standard.string(forKey: "hkContactNew") != nil { try? await Task.sleep(for: .seconds(1)); creating = true }
        }
        #endif
    }
}

struct ContactBookRow: View {
    let c: ContactBookLogic.Contact
    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: c.name, avatar: c.saga?.avatar, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: c.name).lineLimit(1)
                if let s = ContactBookText.sub(ContactBookLogic.sub(c)) {
                    Text(verbatim: s).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if c.saga?.remote == true { Image(systemName: "globe").foregroundStyle(.secondary).accessibilityLabel(Text("Anderer Saga-Server")) }
            else if c.saga == nil { Image(systemName: "person.crop.circle.badge.plus").foregroundStyle(.secondary).accessibilityLabel(Text("Eigener Kontakt")) }
        }
        .opacity(c.saga?.gone == true ? 0.45 : 1)
        .accessibilityElement(children: .combine)
    }
}

/// Landesvorwahl fuer Nummern mit 0 am Anfang - Vorschlag aus der Region des Geraets
struct ContactsCCSection: View {
    @ObservedObject var model: ContactBookModel
    @State private var input = ""
    @State private var err: String?
    @State private var busy = false
    var body: some View {
        Section {
            HStack(spacing: 2) {
                Text("Landesvorwahl")
                Spacer()
                Text(verbatim: "+").foregroundStyle(input.isEmpty ? .secondary : .primary)
                TextField(text: $input, prompt: Text(verbatim: model.suggestion ?? "49")) { Text("Landesvorwahl") }
                    .keyboardType(.numberPad).frame(width: 44)
                    .accessibilityLabel(Text("Landesvorwahl"))
            }
            if let v = ContactBookLogic.normCC(input), v != model.cc {
                Button("Übernehmen") { set(v) }.disabled(busy)
            } else if model.cc == nil, input.isEmpty, let s = model.suggestion {
                Button("+\(s) übernehmen (Region dieses Geräts)") { set(s) }.disabled(busy)
            }
            if model.cc != nil, !busy { Button("Landesvorwahl entfernen", role: .destructive) { set(nil) } }
            if let err { Text(verbatim: err).font(.footnote).foregroundStyle(.red) }
        } header: { Text("Landesvorwahl") } footer: {
            Text("Für Nummern ohne Landesvorwahl, also mit 0 am Anfang (z. B. 0170 …). Gilt für dein Konto auf allen Geräten.")
        }
        .onAppear { input = model.cc ?? "" }
        .onChange(of: model.cc) { _, v in input = v ?? "" }
    }
    private func set(_ v: String?) {
        busy = true
        Task { err = await model.setCC(v); busy = false; input = model.cc ?? "" }
    }
}

// MARK: - Detail

struct ContactDetailView: View {
    typealias L = ContactBookLogic
    let id: Int
    var initial: L.Contact?
    @ObservedObject var model: ContactBookModel
    var close: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var c: L.Contact?
    @State private var gone = false
    @State private var err: String?
    @State private var editing = false
    @State private var askDelete = false
    @State private var busy = false

    static func deleteMessage(_ c: L.Contact) -> String {
        c.saga != nil
            ? String(localized: "„\(c.name)“ wird mit allen Nummern und E-Mail-Adressen gelöscht und verschwindet aus den Chat-Vorschlägen. Ein neuer gemeinsamer Chat legt den Kontakt wieder an.")
            : String(localized: "„\(c.name)“ wird mit allen Nummern und E-Mail-Adressen gelöscht.")
    }

    var body: some View {
        Group {
            if gone {
                ContentUnavailableView("Kontakt gelöscht", systemImage: "person.crop.circle.badge.xmark", description: Text("Diesen Kontakt gibt es nicht mehr."))
            } else if let c = c ?? initial {
                form(c)
            } else if let err {
                ContentUnavailableView("Kontakt", systemImage: "exclamationmark.triangle", description: Text(verbatim: err))
            } else {
                ProgressView()
            }
        }
        .navigationTitle(c?.name ?? initial?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !gone, c != nil {
                ToolbarItem(placement: .primaryAction) { Button("Bearbeiten") { editing = true } }
            }
        }
        .task { await reload() }
        .onChange(of: model.change) { _, ch in
            guard let ch, ch.id == id else { return }
            if ch.deleted { gone = true } else { Task { await reload() } }
        }
        .sheet(isPresented: $editing) {
            if let c { ContactEditSheet(model: model, base: c, saved: { n in self.c = n }) }
        }
        .confirmationDialog("Kontakt löschen?", isPresented: $askDelete, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) {
                Task {
                    busy = true; defer { busy = false }
                    if let e = await model.delete(id) { err = e } else { dismiss() }
                }
            }
            Button("Abbrechen", role: .cancel) {}
        } message: { if let c = c ?? initial { Text(Self.deleteMessage(c)) } }
        #if DEBUG
        .task { if UserDefaults.standard.string(forKey: "hkContactEdit") != nil { try? await Task.sleep(for: .seconds(1.5)); if c != nil { editing = true } } }
        #endif
    }

    private func reload() async {
        switch await model.fetch(id) {
        case .ok(let n): c = n; err = nil
        case .gone: gone = true
        case .failed(let e): err = e
        }
    }

    @ViewBuilder private func form(_ c: L.Contact) -> some View {
        Form {
            Section {
                VStack(spacing: 8) {
                    AvatarView(name: c.name, avatar: c.saga?.avatar, size: 72)
                    Text(verbatim: c.name).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                    if c.ownName != nil, c.saga != nil {
                        Text("Name für mich – nur du siehst ihn").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity).padding(.vertical, 6)
                .listRowBackground(Color.clear)
                .accessibilityElement(children: .combine)
            }
            if let s = c.saga {
                Section {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.remote ? LocalizedStringKey("Saga-Konto auf einem anderen Server") : LocalizedStringKey("Saga-Konto"))
                            if let t = ContactBookText.sub(L.sub(c)) { Text(verbatim: t).font(.caption).foregroundStyle(.secondary) }
                        }
                        Spacer()
                        if L.canMessage(c) {
                            Button { Task { await message(s) } } label: { Label("Nachricht", systemImage: "bubble.left.fill").labelStyle(.titleAndIcon) }
                                .buttonStyle(.borderedProminent).fixedSize().disabled(busy)
                        }
                    }
                } header: { Text("Saga") } footer: { Text("Aus einem gemeinsamen Chat – lässt sich nicht ändern.") }
            }
            if !c.phones.isEmpty {
                Section("Telefonnummern") {
                    ForEach(c.phones) { a in addrRow(a, url: L.telURL(a.value), shown: L.phoneShown(a.value), icon: "phone") }
                }
            }
            if !c.mails.isEmpty {
                Section("E-Mail-Adressen") {
                    ForEach(c.mails) { a in addrRow(a, url: L.mailURL(a.value), shown: a.value, icon: "envelope") }
                }
            }
            if c.form != nil || c.salutation != nil {
                Section("Anrede") {
                    if c.form != nil { LabeledContent("Anrede", value: ContactBookText.form(c.form)) }
                    if let s = c.salutation { LabeledContent("Grußform") { Text(verbatim: s) } }
                }
            }
            if c.saga == nil && c.phones.isEmpty && c.mails.isEmpty {
                Section { Text("Keine Nummern oder E-Mail-Adressen.").foregroundStyle(.secondary) }
            }
            if let err { Section { Text(verbatim: err).foregroundStyle(.red) } }
            Section {
                Button("Kontakt löschen", role: .destructive) { askDelete = true }.disabled(busy)
            }
        }
    }

    private func addrRow(_ a: L.Addr, url: URL?, shown: String, icon: String) -> some View {
        Button { if let url { openURL(url) } } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: ContactBookText.type(a.type)).font(.caption).foregroundStyle(.secondary)
                    Text(verbatim: shown).foregroundStyle(url == nil ? Color.primary : Color.accentColor)
                }
                Spacer()
                Image(systemName: icon).foregroundStyle(Color.accentColor).accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(url == nil)
        .contextMenu {
            Button { UIPasteboard.general.string = a.value } label: { Label("Kopieren", systemImage: "doc.on.doc") }
        }
    }

    private func message(_ s: L.Saga) async {
        busy = true; defer { busy = false }
        switch await model.message(s) {
        case .success(let cid):
            close()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                NotificationCenter.default.post(name: .hkOpenTarget, object: NotifTarget.chat(id: cid))
            }
        case .failure(let e): err = e.text
        }
    }
}

// MARK: - Bearbeiten / Neu

struct ContactEditSheet: View {
    typealias L = ContactBookLogic
    @ObservedObject var model: ContactBookModel
    let base: L.Contact?
    var saved: (L.Contact) -> Void = { _ in }
    /// 409 "gibt es schon bei einem anderen Kontakt": zu diesem springen (nil: nur Hinweis)
    var openOther: ((Int) -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var d = L.Draft.new()
    @State private var cur: L.Contact?
    @State private var started = false
    @State private var err: String?
    @State private var dup: Int?
    @State private var notice: String?
    @State private var busy = false

    private var manual: Bool { cur?.manual ?? true }
    private var dirty: Bool { L.dirty(d, base: cur) }

    var body: some View {
        NavigationStack {
            Form {
                if let c = cur, let s = c.saga {
                    Section {
                        HStack(spacing: 12) {
                            AvatarView(name: c.name, avatar: s.avatar, size: 36)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.remote ? LocalizedStringKey("Saga-Konto auf einem anderen Server") : LocalizedStringKey("Saga-Konto"))
                                if let t = ContactBookText.sub(L.sub(c)) { Text(verbatim: t).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    } footer: { Text("Aus einem gemeinsamen Chat – lässt sich nicht ändern.") }
                }
                Section {
                    TextField(text: $d.ownName, prompt: Text(verbatim: namePrompt)) { Text("Name für mich") }
                        .textContentType(.name)
                } header: { Text("Name für mich") } footer: {
                    Text(manual ? LocalizedStringKey("Nur für dich sichtbar.") : LocalizedStringKey("Nur für dich sichtbar – in Chats steht weiter der Name der Person."))
                }
                Section {
                    Picker("Anrede", selection: $d.form) {
                        Text(verbatim: "—").tag(String?.none)
                        Text("du (Anrede)").tag(String?.some("informal"))
                        Text("Sie (Anrede)").tag(String?.some("formal"))
                    }
                    .pickerStyle(.segmented)
                    TextField(text: $d.salutation, prompt: Text("z. B. „Liebe Anna“")) { Text("Grußform") }
                } header: { Text("Anrede") } footer: { Text("Nur für dich – Saga leitet die Anrede nie aus Chats ab.") }
                Section {
                    ForEach($d.phones) { $a in AddrEditRow(a: $a) }
                        .onDelete { d.phones.remove(atOffsets: $0) }
                    if L.canAdd(d.phones) {
                        Button { d.phones.append(.init(kind: "e164", value: "", type: "mobile")) } label: { Label("Nummer hinzufügen", systemImage: "plus.circle.fill") }
                    }
                } header: { Text("Telefonnummern") } footer: { Text(verbatim: ccHint) }
                Section {
                    ForEach($d.mails) { $a in AddrEditRow(a: $a) }
                        .onDelete { d.mails.remove(atOffsets: $0) }
                    if L.canAdd(d.mails) {
                        Button { d.mails.append(.init(kind: "email", value: "", type: "home")) } label: { Label("E-Mail hinzufügen", systemImage: "plus.circle.fill") }
                    }
                } header: { Text("E-Mail-Adressen") }
                if let notice { Section { Label { Text(verbatim: notice) } icon: { Image(systemName: "arrow.triangle.2.circlepath") }.foregroundStyle(.orange) } }
                if let err {
                    Section {
                        Text(verbatim: err).foregroundStyle(.red)
                        if let dup, let openOther { Button("Zum anderen Kontakt") { dismiss(); openOther(dup) } }
                    }
                }
            }
            .navigationTitle(base == nil ? LocalizedStringKey("Neuer Kontakt") : LocalizedStringKey("Bearbeiten"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Sichern") { Task { await save() } }
                        .disabled(busy || !L.canSave(d, manual: manual) || (cur != nil && !dirty))
                }
            }
            .interactiveDismissDisabled(dirty)
            .onAppear {
                guard !started else { return }
                started = true
                cur = base
                if let base { d = L.Draft(base) }
            }
            .task { if !model.ccLoaded { await model.loadSettings() } }
        }
    }

    private var namePrompt: String {
        if let c = cur, !c.manual, c.ownName == nil { return c.name }       // Name der Person als Vorgabe
        return String(localized: "Name")
    }
    private var ccHint: String {
        if let cc = L.effectiveCC(setting: model.cc, suggestion: model.suggestion) {
            return String(localized: "Nummern mit 0 am Anfang gelten für +\(cc).")
        }
        return String(localized: "Nummern bitte mit Landesvorwahl, z. B. +49 170 1234567.")
    }

    private func save() async {
        busy = true; defer { busy = false }
        err = nil; dup = nil
        switch await model.save(d, base: cur) {
        case .ok(let c):
            saved(c); dismiss()
            Task { await model.load() }
        case .conflict(let c, _):
            cur = c                                                          // neuer Stand; die Eingabe bleibt und wird erneut angeboten
            notice = String(localized: "Der Kontakt wurde inzwischen anderswo geändert. Deine Eingabe ist noch da – bitte prüfen und erneut sichern.")
        case .duplicate(let id, let e):
            dup = id; err = e ?? String(localized: "Diese Nummer bzw. E-Mail-Adresse steht schon bei einem anderen Kontakt.")
        case .failed(let e):
            err = e ?? String(localized: "Konnte nicht gesichert werden.")
        }
    }
}

private struct AddrEditRow: View {
    @Binding var a: ContactBookLogic.DraftAddr
    var body: some View {
        HStack(spacing: 10) {
            Menu {
                Picker("Typ", selection: $a.type) {
                    ForEach(ContactBookLogic.types, id: \.self) { t in Text(verbatim: ContactBookText.type(t)).tag(String?.some(t)) }
                }
            } label: {
                HStack(spacing: 2) {
                    Text(verbatim: ContactBookText.type(a.type)).lineLimit(1)
                    Image(systemName: "chevron.up.chevron.down").font(.caption2)
                }
                .frame(minWidth: 76, alignment: .leading)
            }
            .accessibilityLabel(Text("Typ"))
            .accessibilityValue(Text(verbatim: ContactBookText.type(a.type)))
            Divider()
            if a.kind == "e164" {
                TextField(text: $a.value, prompt: Text(verbatim: "+49 170 1234567")) { Text("Telefonnummer") }
                    .keyboardType(.phonePad).textContentType(.telephoneNumber)
            } else {
                TextField(text: $a.value, prompt: Text(verbatim: "name@example.org")) { Text("E-Mail-Adresse") }
                    .keyboardType(.emailAddress).textContentType(.emailAddress)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
            }
        }
    }
}

// MARK: - "Kontakt bearbeiten …" aus der Personensuche/Gruppeninfo

/// Einmal an der Liste (Form) mit ContactsSection - nie am Section (Bugs-DB 2200)
struct ContactEditPresenter: ViewModifier {
    @ObservedObject var model: ContactBookModel
    func body(content: Content) -> some View {
        content.sheet(item: $model.editing) { e in ContactEditLoader(id: e.id, model: model) }
    }
}

struct ContactEditLoader: View {
    let id: Int
    @ObservedObject var model: ContactBookModel
    @Environment(\.dismiss) private var dismiss
    @State private var c: ContactBookLogic.Contact?
    @State private var err: String?
    var body: some View {
        if let c {
            ContactEditSheet(model: model, base: c)
        } else {
            NavigationStack {
                Group {
                    if let err { ContentUnavailableView("Kontakt", systemImage: "exclamationmark.triangle", description: Text(verbatim: err)) }
                    else { ProgressView() }
                }
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            }
            .task {
                switch await model.fetch(id) {
                case .ok(let n): c = n
                case .gone: err = String(localized: "Diesen Kontakt gibt es nicht mehr.")
                case .failed(let e): err = e
                }
            }
        }
    }
}
