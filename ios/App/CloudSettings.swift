import SwiftUI

// Saga 2.3, Apps Teil 3: Einstellungen -> "Mein Backup" (persoenliches Backup ins eigene Nextcloud/WebDAV-Ziel) und
// "Nextcloud" (eigenes Konto fuer Dateien in Seiten verknuepfen). Wie im Web (static/backup.js, static/nextcloud.js);
// Server-Wege: CloudFlow.swift, Logik: CloudLogic.swift. Aeltere Server (404): Abschnitte ausgeblendet.
// Der private Backup-Schluessel wird nur einmal gezeigt (Erzeugen) bzw. nur fuer das Einspielen mitgeschickt - nie gespeichert.

extension Store {
    /// Server-Wege fuer CloudFlow (Sitzung wie alle App-Anfragen, 401 -> still erneuern)
    var cloudAPI: CloudAPI { { [weak self] m, p, b in
        guard let self else { throw CancellationError() }
        return try await self.apiResponse(p, method: m, body: b, timeout: 60)
    } }
}

/// Einstellungen gezielt oeffnen (Benachrichtigung "Mein Backup" -> /?settings=backup)
enum CloudNav {
    @MainActor static var pendingSection: String?
}
extension Notification.Name { static let hkOpenBackup = Notification.Name("hk.openBackup") }

func cloudWhen(_ ts: Double?) -> String {
    guard let ts, ts > 0 else { return "–" }
    return Date(timeIntervalSince1970: ts).formatted(date: .abbreviated, time: .shortened)
}

// MARK: - Modell

@MainActor
final class BackupModel: ObservableObject {
    @Published var state: BackupState?
    @Published var supported = true
    @Published var loadError: String?
    @Published var busy = false
    weak var store: Store?
    private var polling = false

    func load(pages: Bool = false) async {
        guard let store else { return }
        do {
            let s = try await CloudFlow.backupStatus(store.cloudAPI, pages: pages)
            if let s {
                var n = s
                if !pages, let old = state { n.pages = old.pages }        // Seitenliste nur beim Oeffnen der Auswahl
                state = n; supported = true; loadError = nil
            } else { supported = false }
        } catch { loadError = error.localizedDescription }
        if state?.needsPolling == true { poll() }
    }
    /// Solange ein Lauf oder die Nextcloud-Anmeldung offen ist: alle 3 s neu abfragen
    func poll() {
        guard !polling else { return }
        polling = true
        Task { [weak self] in
            while let self, self.state?.needsPolling == true {
                try? await Task.sleep(for: .seconds(3))
                await self.load()
            }
            self?.polling = false
        }
    }
    /// Server-Weg ausfuehren, danach neu laden; Fehler als Text
    func run(_ op: (CloudAPI) async throws -> Void) async -> String? {
        guard let store else { return nil }
        busy = true; defer { busy = false }
        do { try await op(store.cloudAPI); await load(); return nil }
        catch { await load(); return error.localizedDescription }
    }
}

// MARK: - Einstellungen: Kurzfassung + "Verwalten"

struct MyBackupSection: View {
    @EnvironmentObject var store: Store
    @StateObject private var model = BackupModel()
    @State private var open = false
    @State private var msg: String?
    var body: some View {
        if model.supported {
            Section {
                content
            } header: { Text("Mein Backup") } footer: {
                Text("Sichere deinen eigenen Workspace verschlüsselt in deine eigene Nextcloud oder ein anderes WebDAV-Ziel.")
            }
            .id("backup")
            .task { model.store = store; await model.load(); openIfPending() }
            .onReceive(NotificationCenter.default.publisher(for: .hkOpenBackup)) { _ in openIfPending() }
            .sheet(isPresented: $open) { MyBackupView(model: model).environmentObject(store) }
        }
    }
    @ViewBuilder private var content: some View {
        if let s = model.state {
            if !s.allowed {
                Text("Persönliche Backups sind auf diesem Server abgeschaltet.").foregroundStyle(.secondary)
            } else {
                BackupStatusRows(state: s)
                HStack {
                    Button("Jetzt sichern") { Task { msg = await model.run { try await CloudFlow.backupRun($0) } } }
                        .disabled(!s.ready || s.running || model.busy)
                    Spacer()
                    Button("Verwalten …") { open = true }
                }
                if let msg { Text(msg).font(.footnote).foregroundStyle(.red) }
            }
        } else if let e = model.loadError {
            Text("Nicht geladen: \(e)").font(.footnote).foregroundStyle(.orange)
        } else {
            ProgressView()
        }
    }
    private func openIfPending() {
        guard CloudNav.pendingSection == "backup", model.state != nil else { return }
        CloudNav.pendingSection = nil; open = true
    }
}

/// Letzter Erfolg, naechster Lauf, laufender Schritt bzw. letzter Fehler
struct BackupStatusRows: View {
    let state: BackupState
    var body: some View {
        LabeledContent("Letztes erfolgreiches Backup", value: state.lastOK.map(cloudWhen) ?? String(localized: "noch keines"))
        LabeledContent("Nächster Lauf", value: state.next.map(cloudWhen) ?? String(localized: "Zeitplan aus"))
        if let step = state.runningStep {
            Label { Text("Backup läuft: \(step)") } icon: { ProgressView().controlSize(.small) }
        } else if let e = state.lastError {
            Label { Text(verbatim: e) } icon: { Image(systemName: "xmark.octagon").foregroundStyle(.red) }.foregroundStyle(.red)
        }
        if !state.ready {
            Text("Zum Sichern fehlt noch: ein Ziel und mindestens ein öffentlicher Schlüssel.").font(.footnote).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Mein Backup (Blatt)

struct MyBackupView: View {
    @ObservedObject var model: BackupModel
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                if let s = model.state, s.allowed {
                    BackupOverviewPart(model: model, state: s)
                    BackupTargetPart(model: model, state: s)
                    BackupKeyPart(model: model, state: s)
                    if s.canSelectContent { BackupContentPart(model: model, state: s) }
                    BackupPlanPart(model: model, state: s)
                    BackupRestorePart(model: model, state: s)
                } else if model.state?.allowed == false {
                    Text("Persönliche Backups sind auf diesem Server abgeschaltet.").foregroundStyle(.secondary)
                } else {
                    ProgressView()
                }
            }
            #if os(macOS)
            .formStyle(.grouped)
            #endif
            .navigationTitle("Mein Backup")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
            .refreshable { await model.load(pages: true) }
        }
        .macFrame(minWidth: 560, idealWidth: 620, minHeight: 560, idealHeight: 720)
        .task { await model.load(pages: true) }
    }
}

struct BackupOverviewPart: View {
    @ObservedObject var model: BackupModel
    let state: BackupState
    @State private var msg: String?
    @State private var remote: [RemoteBackup]?
    var body: some View {
        Section("Status") {
            BackupStatusRows(state: state)
            HStack {
                Button("Jetzt sichern") { Task { msg = await model.run { try await CloudFlow.backupRun($0) } } }
                    .disabled(!state.ready || state.running || model.busy)
                Button("Backups im Ziel anzeigen") { Task { await loadRemote() } }.disabled(state.target == nil)
            }
            if let msg { Text(msg).font(.footnote).foregroundStyle(.red) }
            ForEach(state.runs.prefix(5)) { r in BackupRunRow(run: r) }
            if let remote {
                if remote.isEmpty { Text("Noch keine Backups im Ziel.").font(.footnote).foregroundStyle(.secondary) }
                ForEach(remote) { x in RemoteBackupRow(item: x) }
            }
        }
    }
    private func loadRemote() async {
        guard let store = model.store else { return }
        do { remote = try await CloudFlow.backupRemote(store.cloudAPI); msg = nil } catch { msg = error.localizedDescription }
    }
}

struct BackupRunRow: View {
    let run: BackupRun
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Image(systemName: run.ok ? "checkmark.circle" : (run.failed ? "xmark.circle" : "clock")).foregroundStyle(run.ok ? .green : (run.failed ? .red : .secondary))
            Text(verbatim: cloudWhen(run.started))
            Spacer()
            Text(verbatim: detail).foregroundStyle(.secondary).lineLimit(2)
        }
        .font(.footnote)
    }
    private var detail: String { run.ok ? CloudLogic.size(run.size) : (run.failed ? (run.error ?? "") : (run.step ?? "…")) }
}

struct RemoteBackupRow: View {
    let item: RemoteBackup
    @EnvironmentObject var store: Store
    @State private var err: String?
    var body: some View {
        HStack {
            Text(verbatim: item.name).font(.footnote.monospaced()).lineLimit(1).truncationMode(.middle)
            Spacer()
            Text(verbatim: CloudLogic.size(item.size) + (item.complete ? "" : " · " + String(localized: "unvollständig"))).font(.footnote).foregroundStyle(.secondary)
            if item.complete {
                Button { Task { err = await downloadToUser(store, path: "api/account/backup/remote/" + CloudLogic.q(item.name), name: item.name) } } label: {
                    Image(systemName: "arrow.down.circle")
                }.buttonStyle(.borderless).help(Text("Herunterladen")).accessibilityLabel(Text("Herunterladen"))
            }
        }
        if let err { Text(err).font(.footnote).foregroundStyle(.red) }
    }
}

// MARK: Ziel

struct BackupTargetPart: View {
    @ObservedObject var model: BackupModel
    let state: BackupState
    @Environment(\.openURL) private var openURL
    @State private var url = ""
    @State private var user = ""
    @State private var password = ""
    @State private var folder = "Saga-Backup"
    @State private var msg: String?
    @State private var ok: String?
    @State private var askOff = false
    var body: some View {
        Section {
            if state.flow.waiting, let l = state.flow.login.flatMap(URL.init(string:)) {
                Text("Nextcloud gefunden. Melde dich dort an und erlaube den Zugriff – Saga bekommt ein eigenes App-Passwort (dein Passwort sieht Saga nie).")
                HStack {
                    Button("In Nextcloud anmelden ↗") { openURL(l) }.buttonStyle(.borderedProminent)
                    Button("Abbrechen") { Task { msg = await model.run { try await CloudFlow.backupFlowCancel($0) } } }
                }
            } else if let t = state.target {
                connected(t)
            } else {
                form
            }
            if let m = state.flow.message, !state.flow.waiting { Text(verbatim: m).font(.footnote).foregroundStyle(.red) }
            if let ok { Text(ok).font(.footnote).foregroundStyle(.green) }
            if let msg { Text(msg).font(.footnote).foregroundStyle(.red) }
        } header: { Text("Ziel") }
        .confirmationDialog("Ziel trennen?", isPresented: $askOff, titleVisibility: .visible) {
            Button("Trennen", role: .destructive) { Task { ok = nil; msg = await model.run { try await CloudFlow.backupDisconnect($0) } } }
            Button("Abbrechen", role: .cancel) {}
        } message: { Text("Vorhandene Backups im Ziel bleiben erhalten. Ein App-Passwort aus dem Nextcloud-Login wird widerrufen.") }
    }
    @ViewBuilder private func connected(_ t: BackupTarget) -> some View {
        LabeledContent(t.isNextcloud ? "Nextcloud" : "WebDAV", value: t.address)
        if let u = t.user { LabeledContent("Benutzer", value: u) }
        LabeledContent("Ordner", value: t.folder)
        if t.flow { Text("App-Passwort (Login Flow)").font(.footnote).foregroundStyle(.secondary) }
        if !t.allowed { Text("Das Ziel ist nicht (mehr) erlaubt – bitte neu verbinden.").font(.footnote).foregroundStyle(.red) }
        HStack {
            Button("Verbindung prüfen") { Task { await test() } }.disabled(model.busy)
            Button("Trennen", role: .destructive) { askOff = true }.disabled(model.busy)
        }
    }
    @ViewBuilder private var form: some View {
        Text("Nextcloud: nur die Adresse eingeben – die Anmeldung läuft dann in Nextcloud. Anderes WebDAV: Adresse des Ordners plus Benutzer und Passwort.")
            .font(.footnote).foregroundStyle(.secondary)
        TextField("Adresse", text: $url, prompt: Text(verbatim: "https://cloud.example.org"))
            .autocorrectionDisabled()
            #if os(iOS)
            .keyboardType(.URL).textInputAutocapitalization(.never)
            #endif
        TextField("Benutzer", text: $user).autocorrectionDisabled()
            #if os(iOS)
            .textInputAutocapitalization(.never)
            #endif
        SecureField("Passwort", text: $password)
        TextField("Ordner", text: $folder).autocorrectionDisabled()
        Button("Verbinden") { Task { await connect() } }.disabled(model.busy || url.trimmingCharacters(in: .whitespaces).isEmpty)
    }
    private func connect() async {
        guard let store = model.store else { return }
        msg = nil; ok = nil; model.busy = true; defer { model.busy = false }
        do {
            let flow = try await CloudFlow.backupConnect(store.cloudAPI, url: url, user: user, password: password, folder: folder)
            password = ""
            if let flow { openURL(flow) }
            await model.load()
        } catch { msg = error.localizedDescription }
    }
    private func test() async {
        guard let store = model.store else { return }
        msg = nil; ok = nil
        do {
            let free = try await CloudFlow.backupTest(store.cloudAPI)
            ok = String(localized: "Verbindung in Ordnung. Frei im Ziel: \(free.map { CloudLogic.size($0) } ?? String(localized: "unbekannt")).")
        } catch { msg = error.localizedDescription }
    }
}

// MARK: Verschluesselung

struct BackupKeyPart: View {
    @ObservedObject var model: BackupModel
    let state: BackupState
    @State private var pub = ""
    @State private var msg: String?
    @State private var newKey: (priv: String, filename: String)?
    @State private var last = ""
    @State private var removeFP: String?
    var body: some View {
        Section {
            if state.keys.isEmpty { Text("Noch kein Schlüssel – ohne Schlüssel sichert Saga nicht.").font(.footnote).foregroundStyle(.secondary) }
            ForEach(state.keys) { k in
                HStack {
                    Text(verbatim: k.short).font(.footnote.monospaced())
                    Text(verbatim: k.fp).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
                    Spacer()
                    Button("Entfernen", role: .destructive) { removeFP = k.fp }.buttonStyle(.borderless)
                }
            }
            if let k = newKey { newKeyBox(k) }
            else { Button("Schlüsselpaar erzeugen") { Task { await generate() } }.disabled(!state.ageOK || model.busy) }
            TextField("Eigenen öffentlichen Schlüssel eintragen", text: $pub, prompt: Text(verbatim: "age1…"), axis: .vertical)
                .font(.footnote.monospaced()).autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                #endif
            Button("Hinzufügen") { Task { await add() } }.disabled(pub.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.busy)
            if let msg { Text(msg).font(.footnote).foregroundStyle(.red) }
        } header: { Text("Verschlüsselung") }
        .confirmationDialog("Schlüssel entfernen?", isPresented: Binding(get: { removeFP != nil }, set: { if !$0 { removeFP = nil } }), titleVisibility: .visible) {
            Button("Entfernen", role: .destructive) {
                if let fp = removeFP { Task { msg = await model.run { try await CloudFlow.keyRemove($0, fp: fp) } } }
                removeFP = nil
            }
            Button("Abbrechen", role: .cancel) { removeFP = nil }
        } message: { Text("Neue Backups sind dann nicht mehr mit ihm lesbar. Ältere Backups bleiben mit ihm lesbar.") }
        .onDisappear { newKey = nil; last = "" }                          // privaten Schluessel nicht im Speicher behalten
    }
    @ViewBuilder private func newKeyBox(_ k: (priv: String, filename: String)) -> some View {
        Text("Neuer privater Schlüssel – wird nur jetzt angezeigt und von Saga nicht gespeichert:")
        Text(verbatim: k.priv).font(.footnote.monospaced()).textSelection(.enabled)
            .padding(8).frame(maxWidth: .infinity, alignment: .leading).background(.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
        HStack {
            Button { copyText(k.priv) } label: { Label("Kopieren", systemImage: "doc.on.doc") }
            ShareLink(item: k.priv) { Label("Teilen", systemImage: "square.and.arrow.up") }
            #if os(macOS)
            Button { saveTextFile(k.priv, name: k.filename) } label: { Label("Als Datei sichern …", systemImage: "square.and.arrow.down") }
            #endif
        }.buttonStyle(.bordered)
        Text("Lege ihn an einem sicheren Ort ab (Passwort-Manager, Ausdruck) – nicht auf diesem Server. Ohne ihn lassen sich deine Backups nicht wiederherstellen.")
            .font(.footnote).foregroundStyle(.secondary)
        TextField("Zur Bestätigung: die letzten 6 Zeichen des privaten Schlüssels", text: $last).font(.body.monospaced()).autocorrectionDisabled()
            #if os(iOS)
            .textInputAutocapitalization(.never)
            #endif
        HStack {
            Button("Schlüssel ist sicher abgelegt – eintragen") { Task { await confirm() } }.buttonStyle(.borderedProminent).disabled(last.count < 6)
            Button("Verwerfen") { Task { newKey = nil; last = ""; msg = await model.run { try await CloudFlow.keyDrop($0) } } }
        }
    }
    private func generate() async {
        guard let store = model.store else { return }
        do { let r = try await CloudFlow.keyGenerate(store.cloudAPI); newKey = (r.priv, r.filename); msg = nil }
        catch { msg = error.localizedDescription }
    }
    private func confirm() async {
        let l = last.trimmingCharacters(in: .whitespaces)
        msg = await model.run { try await CloudFlow.keyConfirm($0, last6: l) }
        if msg == nil { newKey = nil; last = "" }
    }
    private func add() async {
        if CloudLogic.looksPrivate(pub) { msg = String(localized: "Das ist ein privater Schlüssel – hier nur den öffentlichen (age1…) eintragen."); return }
        let k = pub
        msg = await model.run { try await CloudFlow.keyAdd($0, key: k) }
        if msg == nil { pub = "" }
    }
}

// MARK: Was gesichert wird (nur wenn der Server Kategorien/Ausschluesse meldet)

struct BackupContentPart: View {
    @ObservedObject var model: BackupModel
    let state: BackupState
    @State private var cats: [String: Bool] = [:]
    @State private var exclude: [Int] = []
    @State private var pick = 0
    @State private var msg: String?
    @State private var saved = false
    var body: some View {
        Section {
            ForEach(CloudLogic.backupCats, id: \.0) { c in catToggle(c.0, c.1) }
            Text("Nie enthalten: Seiten und Dateien anderer Personen (auch „Mit mir geteilt“) und Chats – sie sind Ende-zu-Ende-verschlüsselt und gehören allen Beteiligten.")
                .font(.footnote).foregroundStyle(.secondary)
            excludeList
            Picker("Ausschließen", selection: $pick) {
                Text("– Seite wählen –").tag(0)
                ForEach(CloudLogic.pageTree(state.pages)) { e in
                    Text(verbatim: String(repeating: "  ", count: e.depth) + e.page.label).tag(e.page.id)
                }
            }
            .onChange(of: pick) { _, v in if v != 0 { if !exclude.contains(v) { exclude.append(v) }; pick = 0; saved = false } }
            Button("Speichern") { Task { await save() } }.disabled(model.busy)
            if saved { Text("Gespeichert.").font(.footnote).foregroundStyle(.green) }
            if let msg { Text(msg).font(.footnote).foregroundStyle(.red) }
        } header: { Text("Was gesichert wird") }
        .onAppear { cats = state.cats ?? [:]; exclude = state.exclude ?? [] }
    }
    private func catToggle(_ k: String, _ label: String) -> some View {
        Toggle(isOn: Binding(get: { cats[k] ?? false }, set: { cats[k] = $0; saved = false })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: label)
                if k == "comments_others" {
                    Text("Standard aus – die Kommentare gehören den Personen, die sie geschrieben haben.").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
    @ViewBuilder private var excludeList: some View {
        Text("Seiten nicht sichern").font(.subheadline.weight(.semibold))
        if exclude.isEmpty { Text("Keine – alle eigenen Seiten werden gesichert.").font(.footnote).foregroundStyle(.secondary) }
        ForEach(exclude, id: \.self) { id in
            HStack {
                Text(verbatim: (state.pages.first { $0.id == id }?.label ?? "#\(id)") + " " + String(localized: "(mit Unterseiten)"))
                Spacer()
                Button { exclude.removeAll { $0 == id }; saved = false } label: { Image(systemName: "xmark.circle") }
                    .buttonStyle(.borderless).accessibilityLabel(Text("Entfernen"))
            }
        }
    }
    private func save() async {
        let body = CloudLogic.contentBody(cats: cats, exclude: exclude)
        msg = await model.run { _ = try await CloudFlow.backupSettings($0, body) }
        saved = msg == nil
    }
}

// MARK: Zeitplan und Aufbewahrung

struct BackupPlanPart: View {
    @ObservedObject var model: BackupModel
    let state: BackupState
    @State private var schedule = "off"
    @State private var time = Date()
    @State private var weekday = 0
    @State private var keep = 7
    @State private var msg: String?
    @State private var saved = false
    var body: some View {
        Section {
            Picker("Automatisch sichern", selection: $schedule) {
                ForEach(CloudLogic.schedules.filter { CloudLogic.scheduleAllowed($0, max: state.maxSchedule) || $0 == schedule }, id: \.self) { s in
                    Text(verbatim: CloudLogic.scheduleLabel(s)).tag(s)
                }
            }
            DatePicker("Uhrzeit", selection: $time, displayedComponents: .hourAndMinute)
            if schedule == "weekly" {
                Picker("Wochentag", selection: $weekday) {
                    ForEach(Array(CloudLogic.weekdayNames().enumerated()), id: \.offset) { i, n in Text(verbatim: n).tag(i) }
                }
            }
            Stepper(value: $keep, in: 1...60) { LabeledContent("Backups behalten", value: "\(keep)") }
            if let mb = state.maxMB {
                Text("Die neuesten Backups bleiben im Ziel, ältere löscht Saga. Höchstens \(mb) MB je Backup.").font(.footnote).foregroundStyle(.secondary)
            }
            Button("Speichern") { Task { await save() } }.disabled(model.busy)
            if saved { Text("Gespeichert.").font(.footnote).foregroundStyle(.green) }
            if let msg { Text(msg).font(.footnote).foregroundStyle(.red) }
        } header: { Text("Zeitplan und Aufbewahrung") }
        .onAppear { schedule = state.schedule; weekday = state.weekday; keep = CloudLogic.clampKeep(state.keep); time = Self.date(state.time) }
    }
    static func date(_ hm: String) -> Date {
        let p = hm.split(separator: ":").compactMap { Int($0) }
        var c = DateComponents(); c.hour = p.first ?? 3; c.minute = p.count > 1 ? p[1] : 0
        return Calendar.current.date(from: c) ?? Date()
    }
    static func hm(_ d: Date) -> String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: d)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }
    private func save() async {
        let body = CloudLogic.planBody(schedule: schedule, time: Self.hm(time), weekday: weekday, keep: keep)
        msg = await model.run { _ = try await CloudFlow.backupSettings($0, body) }
        saved = msg == nil
    }
}

// MARK: Einspielen (aus dem eigenen Ziel; Datei-Upload im Web)

struct BackupRestorePart: View {
    @ObservedObject var model: BackupModel
    let state: BackupState
    @Environment(\.openURL) private var openURL
    @State private var remote: [RemoteBackup] = []
    @State private var sel = ""
    @State private var key = ""
    @State private var opts = RestoreOptions()
    @State private var msg: String?
    @State private var ok: String?
    @State private var running = false
    var body: some View {
        Section {
            Text("Spielt ein persönliches Backup in DEIN Konto ein – als neue Seiten (Verweise, Datenbanken und Bilder werden angepasst). Bestehende Seiten bleiben unverändert.")
                .font(.footnote).foregroundStyle(.secondary)
            if state.target != nil { fromTarget }
            Button { if let u = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/?settings=backup") { openURL(u) } } label: {
                Label("Backup-Datei im Web einspielen ↗", systemImage: "safari")
            }
            if let ok { Text(ok).font(.footnote).foregroundStyle(.green) }
            if let msg { Text(msg).font(.footnote).foregroundStyle(.red) }
        } header: { Text("Backup einspielen") }
        .task(id: state.target?.address) { await loadRemote() }
        .onDisappear { key = "" }                                    // privaten Schluessel nicht behalten
    }
    @ViewBuilder private var fromTarget: some View {
        Picker("Aus dem Ziel", selection: $sel) {
            Text("– Backup wählen –").tag("")
            ForEach(remote.filter(\.complete)) { x in Text(verbatim: x.name).tag(x.name) }
        }
        SecureField("Privater Schlüssel", text: $key, prompt: Text(verbatim: "AGE-SECRET-KEY-1…"))
        Text("Der private Schlüssel geht nur für diesen Vorgang an den Server und wird nicht gespeichert.").font(.footnote).foregroundStyle(.secondary)
        Toggle("In eine neue Seite „Wiederhergestellt <Datum>“", isOn: $opts.folder)
        Toggle("Papierkorb mit einspielen", isOn: $opts.trash)
        Toggle("Aufgaben übernehmen", isOn: $opts.tasks)
        Toggle("Lesezeichen übernehmen", isOn: $opts.bookmarks)
        Button(running ? "Spiele ein …" : "Einspielen") { Task { await restore() } }.disabled(sel.isEmpty || running)
    }
    private func loadRemote() async {
        guard state.target != nil, let store = model.store else { return }
        remote = (try? await CloudFlow.backupRemote(store.cloudAPI)) ?? []
    }
    private func restore() async {
        guard let store = model.store else { return }
        running = true; msg = nil; ok = nil; defer { running = false }
        do {
            let r = try await CloudFlow.restore(store.cloudAPI, remote: sel, key: key, options: opts)
            key = ""
            ok = String(localized: "Eingespielt: \(r.pages) Seiten, \(r.files) Dateien.")
            await store.refresh(quiet: true)
        } catch { msg = error.localizedDescription }
    }
}

// MARK: - Einstellungen: Nextcloud (eigenes Konto fuer Dateien in Seiten)

struct NextcloudSettingsSection: View {
    @EnvironmentObject var store: Store
    @Environment(\.openURL) private var openURL
    @State private var s: NCSettings?
    @State private var supported = true
    @State private var url = ""
    @State private var msg: String?
    @State private var busy = false
    @State private var askOff = false
    var body: some View {
        if supported {
            Section {
                Text("Verknüpfe deine eigene Nextcloud, um Dateien daraus in Seiten einzufügen. Saga bekommt ein eigenes App-Passwort – dein Passwort sieht Saga nie.")
                    .font(.footnote).foregroundStyle(.secondary)
                content
                if let msg { Text(msg).font(.footnote).foregroundStyle(.red) }
            } header: { Text(verbatim: "Nextcloud") }
            .id("nextcloud")
            .task { await load() }
            .confirmationDialog("Nextcloud trennen?", isPresented: $askOff, titleVisibility: .visible) {
                Button("Trennen", role: .destructive) { Task { await run { try await CloudFlow.ncDisconnect($0) } } }
                Button("Abbrechen", role: .cancel) {}
            } message: { Text("Saga widerruft das App-Passwort; deine eingefügten Dateien zeigen dann keinen Inhalt mehr.") }
        }
    }
    @ViewBuilder private var content: some View {
        if let s {
            if !s.enabled { Text("Nextcloud-Dateien sind auf diesem Server abgeschaltet.").foregroundStyle(.secondary) }
            if s.flow.waiting, let l = s.flow.login.flatMap(URL.init(string:)) {
                Text("Nextcloud gefunden. Melde dich dort an und erlaube den Zugriff – Saga bekommt ein eigenes App-Passwort (dein Passwort sieht Saga nie).")
                HStack {
                    Button("In Nextcloud anmelden ↗") { openURL(l) }.buttonStyle(.borderedProminent)
                    Button("Abbrechen") { Task { await run { try await CloudFlow.ncCancel($0) } } }
                }
            } else if s.connected {
                Text(verbatim: String(localized: "✅ Verbunden als \(s.user ?? "")") + (s.host.isEmpty ? "" : " (\(s.host))"))
                LabeledContent("Verknüpft seit", value: cloudWhen(s.since))
                LabeledContent("Zuletzt genutzt", value: cloudWhen(s.used))
                Text("Alle, die eine Seite sehen, sehen auch die Nextcloud-Dateien, die du dort eingefügt hast. Trennst du die Verknüpfung, zeigen deine eingefügten Dateien nur noch eine Karte ohne Inhalt.")
                    .font(.footnote).foregroundStyle(.secondary)
                Button("Trennen", role: .destructive) { askOff = true }.disabled(busy)
            } else if s.enabled {
                if let m = s.flow.message { Text(verbatim: m).font(.footnote).foregroundStyle(.red) }
                TextField("Nextcloud-Adresse", text: $url, prompt: Text(verbatim: "https://cloud.example.org"))
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .keyboardType(.URL).textInputAutocapitalization(.never)
                    #endif
                Button(busy ? "Prüfe …" : "Verbinden") { Task { await connect() } }.disabled(busy || url.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        } else {
            ProgressView()
        }
    }
    private func load() async {
        do {
            guard let n = try await CloudFlow.ncSettings(store.cloudAPI) else { supported = false; return }
            s = n
            if url.isEmpty { url = n.suggest ?? "" }
            if n.flow.waiting { try? await Task.sleep(for: .seconds(2.5)); await load() }   // Anmeldung in Nextcloud laeuft
        } catch { msg = error.localizedDescription }
    }
    private func run(_ op: (CloudAPI) async throws -> Void) async {
        busy = true; defer { busy = false }
        do { try await op(store.cloudAPI); msg = nil } catch { msg = error.localizedDescription }
        await load()
    }
    private func connect() async {
        busy = true; msg = nil
        do { let flow = try await CloudFlow.ncConnect(store.cloudAPI, url: url); openURL(flow) }
        catch { msg = error.localizedDescription }
        busy = false
        await load()
    }
}

// MARK: - Datei speichern (Download): Mac Sichern-Dialog, iPhone/iPad Teilen-Blatt

@MainActor
func downloadToUser(_ store: Store, path: String, name: String) async -> String? {
    do {
        let data = try await store.api(path, timeout: 180)
        saveDataFile(data, name: name)
        return nil
    } catch { return String(localized: "Nicht geladen: \(error.localizedDescription)") }
}

@MainActor
func saveDataFile(_ data: Data, name: String) {
    let safe = name.replacingOccurrences(of: "/", with: "-").isEmpty ? "Datei" : name.replacingOccurrences(of: "/", with: "-")
    #if os(macOS)
    let panel = NSSavePanel()
    panel.nameFieldStringValue = safe
    panel.canCreateDirectories = true
    guard panel.runModal() == .OK, let url = panel.url else { return }
    try? data.write(to: url)
    #else
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("saga-dl", isDirectory: true)
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let url = dir.appendingPathComponent(safe)
    guard (try? data.write(to: url)) != nil else { return }
    let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
    vc.completionWithItemsHandler = { _, _, _, _ in try? FileManager.default.removeItem(at: url) }
    let root = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController
    var top = root
    while let p = top?.presentedViewController { top = p }
    if let pop = vc.popoverPresentationController, let v = top?.view {
        pop.sourceView = v; pop.sourceRect = CGRect(x: v.bounds.midX, y: v.bounds.midY, width: 1, height: 1); pop.permittedArrowDirections = []
    }
    top?.present(vc, animated: true)
    #endif
}

#if os(macOS)
/// Text (privater Schluessel) an einen selbst gewaehlten Ort schreiben - Saga legt ihn nirgends ab
@MainActor
func saveTextFile(_ text: String, name: String) { saveDataFile(Data(text.utf8), name: name) }
#endif
