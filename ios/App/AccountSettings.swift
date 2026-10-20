import SwiftUI

// Konto-Einstellungen wie im Web (👤 → Einstellungen): Immich, Kalender (CalDAV), App-Schluessel.
// Passwoerter/Schluessel tippt die Person selbst ein; sie gehen nur an den eigenen Server.

private func json(_ d: Data?) -> Any? { d.flatMap { try? JSONSerialization.jsonObject(with: $0) } }

// MARK: - Konto: Oeffentlicher Name (Veroeffentlichen)

/// Steht in jeder oeffentlichen Adresse /@name/..., nie der Anmeldename. Pruefung (gueltig + frei) schon beim Tippen,
/// Rueckfrage nur beim Aendern, wenn schon Seiten oeffentlich sind (alte Links brechen). Aeltere Server: ausgeblendet.
struct PublicNameSection: View {
    @EnvironmentObject var store: Store
    @State private var info: [String: Any]?
    @State private var supported = true
    @State private var input = ""
    @State private var check: NameCheck = .idle
    @State private var status: PubStatus?
    @State private var busy = false
    @State private var askChange = false
    private var current: String? { PubJSON.str(info?["name"]) }
    /// Fehler direkt am Feld: Pruefung oder Speichern fehlgeschlagen
    private var hasError: Bool {
        if case .bad = check { return true }
        if case .err? = status { return true }
        return false
    }

    var body: some View {
        Section {
            if let info {
                LabeledContent("Öffentlicher Name") {
                    TextField("Öffentlicher Name", text: $input, prompt: Text(verbatim: PubJSON.str(info["suggested"]) ?? ""))
                        .labelsHidden().autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never).keyboardType(.asciiCapable)
                        #endif
                        .onSubmit { trySave() }
                        .fieldError(hasError)
                }
                .id("konto")
                // Status direkt unter dem Feld, dann live die neue Adresse (alte Links brechen)
                NameCheckLine(check: check)
                if let status { PubStatusLine(status: status) }
                if let p = PublishLogic.nameChangePreview(input: input, current: current, currentURL: PubJSON.str(info["url"]), server: AppConfig.serverString) {
                    Text("Neue Adresse: \(PublishLogic.breakable(p.new)) (bisher \(PublishLogic.breakable(p.old)))")
                        .font(.footnote.monospaced())
                }
                Group {
                    if let u = PubJSON.str(info["url"]) {
                        Text("Steht in den Adressen deiner öffentlichen Seiten (\(PublishLogic.breakable(u))). Beim Ändern funktionieren alte Links nicht mehr.")
                    } else {
                        Text("Steht in den Adressen deiner öffentlichen Seiten (…/@name/…) und ist für alle sichtbar – nicht dein Anmeldename. Wird beim ersten Veröffentlichen festgelegt.")
                    }
                }
                .font(.footnote).foregroundStyle(.secondary)
                if !(info["enabled"].map(PubJSON.bool) ?? true) {
                    Label("Veröffentlichen ist auf diesem Server abgeschaltet.", systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.orange)
                }
                Button("Namen speichern") { trySave() }
                    .disabled(!PublishLogic.canSaveName(input: input, current: current, check: check) || busy)
            } else if supported {
                ProgressView()
            }
        } header: { if supported { Text("Konto") } }
        .task { await load() }
        .task(id: PublishLogic.normalize(input)) { await runCheck() }
        .alert("Öffentlichen Namen ändern?", isPresented: $askChange) {
            Button("Ändern") { Task { await save() } }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Links auf deine veröffentlichten Seiten ändern sich. Alte Links funktionieren nicht mehr.")
        }
    }

    private func load() async {
        guard let r = try? await store.apiResponse("api/account/public-name"), (200..<300).contains(r.1),
              let j = json(r.0) as? [String: Any] else { if info == nil { supported = false }; return }
        info = j; supported = true
        if input.isEmpty { input = PubJSON.str(j["name"]) ?? "" }
        #if DEBUG
        if let v = UserDefaults.standard.string(forKey: "hkPublicName") { input = v }    // nur fuer Tests: neuen Namen vortippen
        if UserDefaults.standard.string(forKey: "hkPublishAsk") == "name" {                // nur fuer Tests: Rueckfrage zeigen
            try? await Task.sleep(for: .seconds(2)); trySave()
        }
        #endif
    }

    private func runCheck() async {
        let v = PublishLogic.normalize(input)
        guard !v.isEmpty, v != current else { check = .idle; return }
        status = nil                                              // alte Meldung verschwindet beim Weitertippen
        check = .checking
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled else { return }
        let r = await store.checkPublicName(v)
        guard !Task.isCancelled, PublishLogic.normalize(input) == v else { return }
        check = r
    }

    private func trySave() {
        guard !busy, PublishLogic.canSaveName(input: input, current: current, check: check) else { return }
        if PublishLogic.needsNameChangeConfirm(input: input, current: current, publishedCount: PubJSON.int(info?["published"]) ?? 0) {
            askChange = true
        } else { Task { await save() } }
    }

    private func save() async {
        let v = PublishLogic.normalize(input)
        busy = true; defer { busy = false }
        do {
            let (d, c) = try await store.apiResponse("api/account/public-name", method: "PUT", body: ["name": v])
            let j = json(d) as? [String: Any]
            guard (200..<300).contains(c), let n = PubJSON.str(j?["name"]) else {
                status = .err(PubJSON.str(j?["error"]) ?? String(localized: "Server-Fehler \(c)")); return
            }
            info?["name"] = n; info?["url"] = j?["url"]
            input = n; check = .idle
            status = .ok(String(localized: "✓ Gespeichert"))
        } catch { status = .err(error.localizedDescription) }
    }
}

// MARK: - Immich

struct ImmichSettingsSection: View {
    @EnvironmentObject var store: Store
    @State private var info: [String: Any]?
    @State private var url = ""
    @State private var key = ""
    @State private var msg = ""
    @State private var busy = false
    var body: some View {
        Section("📷 Immich") {
            if let info, info["connected"] as? Bool == true {
                let host = (info["url"] as? String ?? "").replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "")
                Text(((info["name"] as? String).map { String(localized: "✅ Verbunden als \($0)") } ?? String(localized: "✅ Verbunden")) + (host.isEmpty ? "" : " (\(host))"))
                Button("Trennen", role: .destructive) { Task { _ = try? await store.api("api/settings/immich", method: "DELETE"); await load() } }
            } else {
                Text("In Immich unter Kontoeinstellungen → API-Schlüssel einen Schlüssel mit album.read, asset.read, asset.view, asset.download und user.read anlegen und hier einfügen.")
                    .font(.footnote).foregroundStyle(.secondary)
                TextField("Immich-Adresse", text: $url, prompt: Text("https://immich.example.de")).autocorrectionDisabled()
                SecureField("API-Schlüssel", text: $key)
                Button(busy ? "Prüfe …" : "Verbinden") {
                    Task {
                        busy = true; defer { busy = false }
                        do { _ = try await store.api("api/settings/immich", method: "POST", body: ["url": url.trimmingCharacters(in: .whitespaces), "apikey": key.trimmingCharacters(in: .whitespaces)]); key = ""; msg = ""; await load() }
                        catch { msg = error.localizedDescription }
                    }
                }.disabled(busy || url.isEmpty || key.isEmpty)
                if !msg.isEmpty { Text(msg).font(.footnote).foregroundStyle(.red) }
            }
        }
        .task { await load() }
    }
    private func load() async {
        info = json(try? await store.api("api/settings/immich")) as? [String: Any]
        if url.isEmpty { url = info?["suggest"] as? String ?? "" }
    }
}

// MARK: - Kalender (CalDAV)

struct CalEntry: Identifiable, Equatable {
    var id: String { href }
    let href: String
    var name: String
    var color: Color
    var show: Bool
    let writable: Bool
    init(_ d: [String: Any]) {
        href = d["href"] as? String ?? ""; name = d["name"] as? String ?? ""; show = d["show"] as? Bool ?? true; writable = d["writable"] as? Bool ?? true
        color = Color(hexString: d["color"] as? String ?? "#4184e4")
    }
    var dict: [String: Any] { ["href": href, "name": name.trimmingCharacters(in: .whitespaces), "color": color.hexString, "show": show] }
}

struct CalPicker: View {
    @Binding var cals: [CalEntry]
    var body: some View {
        ForEach($cals) { $c in
            HStack {
                Toggle("", isOn: $c.show).labelsHidden()
                ColorPicker("", selection: $c.color, supportsOpacity: false).labelsHidden()
                TextField("Name", text: $c.name)
                if !c.writable { Text("nur lesen").font(.caption).foregroundStyle(.secondary) }
            }
        }
    }
}

struct CalendarSettingsSection: View {
    @EnvironmentObject var store: Store
    @State private var accounts: [[String: Any]] = []
    @State private var allowed: [String] = []
    @State private var adding = false
    @State private var err = ""
    var body: some View {
        Section {
            Text("Externe Kalender (CalDAV) wie mailcow oder Nextcloud verbinden. Tipp: In mailcow ein eigenes App-Passwort nur für „DAV“ anlegen.")
                .font(.footnote).foregroundStyle(.secondary)
            if accounts.isEmpty { Text("Noch kein Kalender verbunden.").foregroundStyle(.secondary) }
            ForEach(accounts.indices, id: \.self) { i in CalAccountView(account: accounts[i]) { await load() } }
            if adding { NewCalAccount(allowed: allowed) { adding = false; Task { await load() } } }
            else { Button("+ Kalender verbinden") { adding = true } }
            if !err.isEmpty { Text(err).font(.footnote).foregroundStyle(.red) }
        } header: { Text("📅 Kalender") }
        .task { await load() }
    }
    private func load() async {
        do {
            let d = json(try await store.api("api/settings/calendars")) as? [String: Any]
            accounts = d?["accounts"] as? [[String: Any]] ?? []; allowed = d?["allowed"] as? [String] ?? []; err = ""
        } catch { err = error.localizedDescription }
    }
}

struct CalAccountView: View {
    let account: [String: Any]
    let reload: () async -> Void
    @EnvironmentObject var store: Store
    @State private var cals: [CalEntry] = []
    @State private var status = ""
    @State private var newPW = ""
    @State private var changingPW = false
    @State private var confirmDelete = false
    private var aid: Int { account["id"] as? Int ?? 0 }
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(account["label"] as? String ?? account["username"] as? String ?? String(localized: "Kalender")).font(.headline)
            Text("\(account["username"] as? String ?? "") · \(URL(string: account["base_url"] as? String ?? "")?.host ?? "") · Passwort gespeichert ✓")
                .font(.caption).foregroundStyle(.secondary)
            CalPicker(cals: $cals)
            HStack {
                Button("Auswahl speichern") { Task { await run { _ = try await store.api("api/settings/calendars/\(aid)", method: "PATCH", body: ["calendars": cals.map(\.dict)]); return String(localized: "✓ Gespeichert") } } }
                Button("Neu einlesen") {
                    Task {
                        await run {
                            let d = json(try await store.api("api/settings/calendars/test", method: "POST", body: ["id": aid])) as? [String: Any]
                            let found = (d?["calendars"] as? [[String: Any]] ?? []).map(CalEntry.init)
                            let by = Dictionary(cals.map { ($0.href, $0) }, uniquingKeysWith: { a, _ in a })   // gleiche href (z. B. leer) darf nicht abstuerzen
                            cals = found.map { f in by[f.href] ?? { var x = f; x.show = false; return x }() }
                            return String(localized: "\(found.count) Kalender – Auswahl speichern nicht vergessen.")
                        }
                    }
                }
                Button("Passwort ändern") { changingPW.toggle() }
                Button("Entfernen", role: .destructive) { confirmDelete = true }
            }.buttonStyle(.bordered).controlSize(.small)
            if changingPW {
                HStack {
                    SecureField("Neues App-Passwort", text: $newPW)
                    Button("Prüfen & speichern") { Task { await run { _ = try await store.api("api/settings/calendars/\(aid)", method: "PATCH", body: ["password": newPW]); newPW = ""; changingPW = false; return String(localized: "✓ Neues Passwort gespeichert") } } }
                        .disabled(newPW.isEmpty)
                }
            }
            if !status.isEmpty { Text(status).font(.footnote).foregroundStyle(status.hasPrefix("✓") ? .green : .secondary) }
        }
        .padding(.vertical, 4)
        .onAppear { cals = (account["calendars"] as? [[String: Any]] ?? []).map(CalEntry.init) }
        .confirmationDialog("Kalender-Zugang entfernen? Die Termine im Kalender selbst bleiben erhalten.", isPresented: $confirmDelete) {
            Button("Entfernen", role: .destructive) { Task { _ = try? await store.api("api/settings/calendars/\(aid)", method: "DELETE"); await reload() } }
        }
    }
    private func run(_ f: () async throws -> String) async {
        status = "…"
        do { status = try await f() } catch { status = error.localizedDescription }
    }
}

struct NewCalAccount: View {
    let allowed: [String]
    let done: () -> Void
    @EnvironmentObject var store: Store
    @State private var kind = "mailcow"
    @State private var url = ""
    @State private var user = ""
    @State private var pw = ""
    @State private var label = ""
    @State private var cals: [CalEntry] = []
    @State private var status = ""
    @State private var busy = false
    /// SOGo-Adresse: erlaubter mail.*-Server, sonst Beispiel
    private var mailcowURL: String { "https://" + (allowed.first(where: { $0.hasPrefix("mail.") }) ?? "mail.example.org") + "/SOGo/dav/" }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("Anbieter", selection: $kind) { Text("mailcow (SOGo)").tag("mailcow"); Text("Nextcloud").tag("nextcloud"); Text("Andere CalDAV-Adresse").tag("other") }
                .onChange(of: kind) { _, k in
                    let nc = allowed.first { !$0.hasPrefix("mail.") } ?? "HOST"
                    url = k == "mailcow" ? mailcowURL : k == "nextcloud" ? "https://\(nc)/remote.php/dav/" : "https://"
                }
            TextField("CalDAV-Adresse", text: $url).autocorrectionDisabled()
            TextField("Benutzer", text: $user, prompt: Text("z. B. name@example.org")).autocorrectionDisabled()
            SecureField("App-Passwort", text: $pw)
            TextField("Bezeichnung", text: $label, prompt: Text("optional, z. B. „Privat“"))
            Text("Erlaubte Server: \(allowed.isEmpty ? "–" : allowed.joined(separator: ", "))").font(.caption).foregroundStyle(.secondary)
            HStack {
                Button(busy ? "Verbinde …" : "Verbindung testen") {
                    Task {
                        busy = true; defer { busy = false }
                        do {
                            let d = json(try await store.api("api/settings/calendars/test", method: "POST", body: ["base_url": url.trimmingCharacters(in: .whitespaces), "username": user.trimmingCharacters(in: .whitespaces), "password": pw])) as? [String: Any]
                            cals = (d?["calendars"] as? [[String: Any]] ?? []).map(CalEntry.init)
                            status = String(localized: "✓ Verbunden – \(cals.count) Kalender gefunden. Auswählen und speichern:")
                        } catch { status = error.localizedDescription }
                    }
                }.disabled(busy || user.isEmpty || pw.isEmpty)
                Button("Abbrechen", action: done)
            }
            if !status.isEmpty { Text(status).font(.footnote) }
            if !cals.isEmpty {
                CalPicker(cals: $cals)
                Button("Verbinden & speichern") {
                    Task {
                        do {
                            _ = try await store.api("api/settings/calendars", method: "POST", body: ["base_url": url.trimmingCharacters(in: .whitespaces), "username": user.trimmingCharacters(in: .whitespaces),
                                                                                                   "password": pw, "label": label.trimmingCharacters(in: .whitespaces), "calendars": cals.map(\.dict)])
                            pw = ""; store.refreshEvents(); done()
                        } catch { status = error.localizedDescription }
                    }
                }.buttonStyle(.borderedProminent)
            }
        }
        .onAppear { if url.isEmpty && kind == "mailcow" { url = mailcowURL } }
    }
}

// MARK: - Apps & Geraete: EINE Liste (Geraet + sein Widget-/Teilen-Schluessel), von Hand erzeugte Schluessel eingeklappt

struct AppsDevicesSection: View {
    @EnvironmentObject var store: Store
    @State private var devices: [[String: Any]] = []
    @State private var keys: [[String: Any]] = []
    @State private var loaded = false
    @State private var showKeys = false
    @State private var name = ""
    @State private var fresh: (name: String, token: String)?
    @State private var drop: [String: Any]?
    @State private var revoke: [String: Any]?
    var body: some View {
        Section {
            Text("Angemeldete Apps bleiben angemeldet. Zu jeder gehört ein kleiner Schlüssel für Widget, Teilen und Kurzbefehle. Verlorenes Gerät → abmelden, dann ist beides weg.")
                .font(.footnote).foregroundStyle(.secondary)
            if loaded && devices.isEmpty { Text("Keine App angemeldet.").foregroundStyle(.secondary) }
            ForEach(devices.indices, id: \.self) { i in
                let k = devices[i]
                HStack {
                    VStack(alignment: .leading) {
                        Text(k["name"] as? String ?? "").bold()
                        Text(String(localized: "angemeldet \(day(k["created"])) · zuletzt \(day(k["last_used"]))") + (k["key"] is [String: Any] ? " · " + String(localized: "mit Widget-Schlüssel") : ""))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Abmelden", role: .destructive) { drop = k }
                }
            }
            DisclosureGroup(isExpanded: $showKeys) {
                Text("Von Hand erzeugte Schlüssel, z. B. für Kurzbefehle oder Skripte.").font(.footnote).foregroundStyle(.secondary)
                if keys.isEmpty { Text("Keine.").foregroundStyle(.secondary) }
                ForEach(keys.indices, id: \.self) { i in
                    let k = keys[i]
                    HStack {
                        VStack(alignment: .leading) {
                            Text(k["name"] as? String ?? "").bold()
                            Text(String(localized: "\(k["prefix"] as? String ?? "")… · erstellt \(day(k["created"]))") + " · " + ((k["last_used"] as? Double).map { String(localized: "zuletzt \(day($0))") } ?? String(localized: "noch nie benutzt")))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Widerrufen", role: .destructive) { revoke = k }
                    }
                }
                HStack {
                    TextField("Name, z. B. „Kurzbefehle“", text: $name)
                    Button("+ Schlüssel erzeugen") {
                        Task {
                            if let d = json(try? await store.api("api/settings/tokens", method: "POST", body: ["name": name.trimmingCharacters(in: .whitespaces)])) as? [String: Any],
                               let t = d["token"] as? String { fresh = (d["name"] as? String ?? name, t); name = "" }
                            await load()
                        }
                    }
                }
                if let f = fresh {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("🔑 Neuer Schlüssel für „\(f.name)“ – nur jetzt sichtbar!").font(.footnote)
                        Text(f.token).font(.caption.monospaced()).textSelection(.enabled)
                        HStack {
                            Button("Kopieren") { copyText(f.token) }
                            Button("In dieser App verwenden") { AppConfig.token = f.token; fresh = nil }
                        }
                        .rowButtons()
                    }
                }
            } label: { Text(keys.isEmpty ? "Weitere Schlüssel" : "Weitere Schlüssel (\(keys.count))") }
        } header: { Text("📱 Apps & Geräte") }
        .task { await load() }
        .confirmationDialog("Dieses Gerät abmelden? Die App muss sich neu anmelden; Widget und Teilen verlieren den Zugang.",
                            isPresented: Binding(get: { drop != nil }, set: { if !$0 { drop = nil } })) {
            Button("Abmelden", role: .destructive) {
                if let id = drop?["id"] as? Int { Task { _ = try? await store.api("api/settings/devices/\(id)", method: "DELETE"); await load() } }
                drop = nil
            }
        }
        .confirmationDialog("Schlüssel widerrufen?", isPresented: Binding(get: { revoke != nil }, set: { if !$0 { revoke = nil } })) {
            Button("Widerrufen", role: .destructive) {
                if let id = revoke?["id"] as? Int { Task { _ = try? await store.api("api/settings/tokens/\(id)", method: "DELETE"); await load() } }
                revoke = nil
            }
        }
    }
    private func load() async {
        let d = json(try? await store.api("api/settings/apps")) as? [String: Any]
        devices = d?["devices"] as? [[String: Any]] ?? []
        keys = d?["keys"] as? [[String: Any]] ?? []
        loaded = true
    }
    private func day(_ v: Any?) -> String {
        guard let t = v as? Double else { return "–" }
        return Date(timeIntervalSince1970: t).sagaFormatted(date: .numeric, time: .omitted)
    }
}

func copyText(_ s: String) {
    #if os(macOS)
    NSPasteboard.general.clearContents(); NSPasteboard.general.setString(s, forType: .string)
    #else
    UIPasteboard.general.string = s
    #endif
}

extension Color {
    init(hexString: String) {
        let h = hexString.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        let v = UInt64(h, radix: 16) ?? 0x4184e4
        self.init(red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255, blue: Double(v & 0xff) / 255)
    }
    var hexString: String {
        #if os(macOS)
        let c = NSColor(self).usingColorSpace(.sRGB) ?? .systemBlue
        let r = c.redComponent, g = c.greenComponent, b = c.blueComponent
        #else
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(self).getRed(&r, green: &g, blue: &b, alpha: &a)
        #endif
        return String(format: "#%02x%02x%02x", Int(max(0, min(1, r)) * 255), Int(max(0, min(1, g)) * 255), Int(max(0, min(1, b)) * 255))
    }
}
