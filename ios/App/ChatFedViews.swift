import SwiftUI

// Chats ueber Servergrenzen (Saga 2.3): Herkunft und TOFU-Hinweise im Kopf, Nachrichtenanfrage (Annehmen/Ablehnen/Sperren),
// schreibgeschuetzt, Systemzeilen, Zustellstatus, Sicherheitsnummer v2, Sperrliste. Logik: FedChatLogic.swift, ChatFed.swift.

/// Untertitel des Chats (macOS): Art, Mitglieder bzw. Server der Person
enum ChatSubtitle {
    static func text(_ c: ChatSummary) -> String {
        if let f = c.fed, c.kind == "dm" {
            var s = c.kindLabel + " · " + String(localized: "auf \(f.peer?.host ?? f.home)")
            if f.request { s += " · " + String(localized: "Anfrage") }
            if f.readonly { s += " · " + String(localized: "schreibgeschützt") }
            return s
        }
        var s = c.kind == "dm" ? c.kindLabel : "\(c.kindLabel) · \(String(localized: "\(c.memberCount) Personen"))"
        if let f = c.fed {                                              // 2.3: Gruppe/Seiten-Chat ueber Servergrenzen
            let hosts = Set(f.members.map(\.host)).count
            if hosts > 1 { s += " · " + String(localized: "\(hosts) Server") }
            if f.request { s += " · " + String(localized: "Anfrage") }
            if f.readonly { s += " · " + String(localized: "schreibgeschützt") }
        }
        return s
    }
}

/// Kopf eines Chats ueber Servergrenzen: Person + Server, "ueberprueft"; Warnung/Blockade/ungueltige Schluessel
struct FedChatBanners: View {
    @ObservedObject var conv: ConversationModel
    var safety: (FedMember) -> Void
    var body: some View {
        if let f = conv.fed {
            if let p = f.peer {
                HStack(spacing: 6) {
                    Image(systemName: "globe").foregroundStyle(.secondary).accessibilityHidden(true)
                    Text("\(p.name) ist auf \(p.host)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    if conv.fk[p.handle]?.verified == true {
                        Label("überprüft", systemImage: "checkmark.seal.fill").font(.caption.weight(.semibold)).foregroundStyle(.green)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 12).padding(.vertical, 6).frame(maxWidth: .infinity, alignment: .leading)
                .background(.bar)
                .accessibilityElement(children: .combine)
                Divider()
            } else if let hosts = Self.otherHosts(f) {                           // Gruppe/Seiten-Chat (E5): Herkunft der anderen
                HStack(spacing: 6) {
                    Image(systemName: "globe").foregroundStyle(.secondary).accessibilityHidden(true)
                    Text("Über Servergrenzen: \(hosts)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 12).padding(.vertical, 6).frame(maxWidth: .infinity, alignment: .leading)
                .background(.bar)
                .accessibilityElement(children: .combine)
                Divider()
            }
            ForEach(conv.fedProblems, id: \.member.handle) { x in
                VStack(alignment: .leading, spacing: 8) {
                    Label(Self.text(x.verdict, name: x.member.name), systemImage: x.verdict == .warn ? "exclamationmark.shield.fill" : "xmark.shield.fill")
                        .font(.callout).fixedSize(horizontal: false, vertical: true)
                    if x.verdict != .reject {
                        HStack {
                            Button("Sicherheitsnummer") { safety(x.member) }.buttonStyle(.bordered)
                            if x.verdict == .warn { Button("Verstanden") { Task { await conv.fedConfirm(x.member.handle, verified: false) } }.buttonStyle(.bordered) }
                        }.controlSize(.small)
                    }
                }
                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background((x.verdict == .warn ? Color.orange : Color.red).opacity(0.14))
            }
        }
    }
    /// Server der anderen Mitglieder (ohne den eigenen), sortiert; nil = nur der eigene
    static func otherHosts(_ f: FedChatInfo) -> String? {
        let mine = FedChatLogic.host(of: f.myHandle)
        let hs = Set(f.members.map(\.host)).subtracting([mine, ""]).sorted()
        return hs.isEmpty ? nil : hs.joined(separator: ", ")
    }
    static func text(_ v: FedChatLogic.Verdict, name: String) -> String {
        switch v {
        case .block: return String(localized: "Du hattest \(name) als überprüft markiert – jetzt meldet der Server einen anderen Schlüssel. Senden ist gesperrt, bis du die Sicherheitsnummer erneut verglichen hast.")
        case .reject: return String(localized: "Die Schlüssel von \(name) sind ungültig (Zertifikat oder Geräte-Liste) – Senden ist gesperrt.")
        default: return String(localized: "Die Sicherheitsnummer von \(name) hat sich geändert – z. B. nach „Neu beginnen“ oder bei einem neuen Konto. Prüfe sie, bevor du Vertrauliches schreibst.")
        }
    }
}

/// Statt der Eingabe: Nachrichtenanfrage (Annehmen/Ablehnen/Sperren) bzw. schreibgeschuetzt
struct FedStateBar: View {
    @ObservedObject var conv: ConversationModel
    let fed: FedChatInfo
    var closed: () -> Void
    @State private var busy = false
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if fed.request {
                let p = fed.requester
                Group {
                    if fed.peer != nil {
                        Text("\(p.name) (\(p.host)) möchte dir schreiben. Bis du annimmst, bekommst du keine Benachrichtigungen und keine Anrufe – und \(p.name) erfährt nicht, ob du die Nachrichten gelesen hast.")
                    } else {                                                     // Gruppe eines anderen Servers (E5)
                        Text("\(p.name) (\(p.host)) hat dich in diese Gruppe eingeladen. Bis du annimmst, bekommst du keine Benachrichtigungen.")
                    }
                }
                .font(.callout).fixedSize(horizontal: false, vertical: true)
                if let e = conv.sendError { Text(verbatim: e).font(.footnote).foregroundStyle(.red) }
                ViewThatFits(in: .horizontal) {
                    HStack { buttons }
                    VStack(alignment: .leading, spacing: 8) { buttons }
                }
                .disabled(busy)
            } else {
                Label(fed.readonlyReason ?? String(localized: "Diese Unterhaltung ist schreibgeschützt."), systemImage: "lock.fill")
                    .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(.bar)
    }
    @ViewBuilder private var buttons: some View {
        Button("Annehmen") { act("accept") }.prominentGold()
        Button("Ablehnen") { act("decline") }.buttonStyle(.bordered)
        Menu {
            Button(role: .destructive) { act("block", "person") } label: { Label("\(fed.requester.name) sperren", systemImage: "hand.raised.fill") }
            Button(role: .destructive) { act("block", "instance") } label: { Label("Ganzen Server \(fed.requester.host) sperren", systemImage: "server.rack") }
        } label: { Label("Sperren …", systemImage: "hand.raised") }
        .menuIndicator(.hidden).fixedSize().foregroundStyle(.red)
    }
    private func act(_ a: String, _ scope: String = "person") {
        busy = true
        Task { if await conv.respond(a, scope: scope) { closed() }; busy = false }
    }
}

/// Systemzeile (mittig): z. B. "Anna hat die KI in diesem Chat eingeschaltet."
struct FedSysRow: View {
    let line: FedChatLogic.SysLine
    let date: Date
    /// eigener Handle: "Du", "dich", eigener Server ohne Herkunft
    var myHandle: String?
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "info.circle").foregroundStyle(.secondary).accessibilityHidden(true)
            Text(verbatim: text).font(.caption.weight(.medium)).multilineTextAlignment(.center)
            Text(date, format: .dateTime.hour().minute()).font(.caption2).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(Color.secondary.opacity(0.1), in: Capsule())
        .frame(maxWidth: .infinity).padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
    private var text: String { FedChatLogic.sysText(line, myHandle: myHandle) }
}

/// Zustellstatus unter der Blase (nur Chats ueber Servergrenzen)
struct FedDeliveryMark: View {
    let delivery: FedChatLogic.Delivery
    var body: some View {
        switch delivery {
        case .pending:
            Image(systemName: "hourglass").help(Text("Wird zugestellt, sobald der andere Server erreichbar ist."))
                .accessibilityLabel(Text("Wird zugestellt, sobald der andere Server erreichbar ist."))
        case .failed:
            Label("nicht zugestellt", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(Color.adaptive(ChatLogic.Palette.warn.light, ChatLogic.Palette.warn.dark))
        case .delivered: EmptyView()
        }
    }
}

/// Sicherheitsnummer v2 (an die Handles gebunden) + "Als ueberprueft markieren"
struct FedSafetySheet: View {
    @ObservedObject var conv: ConversationModel
    let member: FedMember
    @Environment(\.dismiss) private var dismiss
    @State private var number: String?
    @State private var state: FedKeyState?
    @State private var loaded = false
    @State private var scanning = false
    @State private var scanResult: Bool?
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let n = number {
                        let rows = ChatLogic.safetyRows(n)
                        Grid(horizontalSpacing: 14, verticalSpacing: 8) {
                            ForEach(rows.indices, id: \.self) { r in
                                GridRow { ForEach(rows[r], id: \.self) { Text(verbatim: $0).font(.title3.monospaced()).fixedSize() } }
                            }
                        }
                        .frame(maxWidth: .infinity).padding().background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                        .textSelection(.enabled)
                        if let img = qrImage(ChatLogic.safetyQR(n)) {
                            img.resizable().scaledToFit().frame(width: 180, height: 180).padding(10).background(.white, in: RoundedRectangle(cornerRadius: 10))
                                .frame(maxWidth: .infinity).accessibilityLabel(Text("Sicherheitsnummer"))
                        }
                        Text("Vergleiche diese Nummer mit \(member.name) – persönlich, per Anruf oder auf deren Gerät. Stimmt sie überein, liest niemand mit. Sie ist auf beiden Seiten gleich.")
                            .foregroundStyle(.secondary)
                        Text("\(member.name) ist auf \(member.host) – die Nummer gehört zu diesem Server-übergreifenden Chat.").font(.footnote).foregroundStyle(.secondary)
                        if let ok = scanResult {
                            Label(ok ? String(localized: "Stimmt überein.") : String(localized: "Stimmt nicht überein!"), systemImage: ok ? "checkmark.seal.fill" : "xmark.octagon.fill")
                                .foregroundStyle(ok ? .green : .red)
                        }
                        ViewThatFits(in: .horizontal) {
                            HStack { buttons }
                            VStack(alignment: .leading, spacing: 10) { buttons }
                        }
                    } else if loaded {
                        Text("\(member.name) hat Chats noch nicht eingerichtet.").foregroundStyle(.secondary)
                    } else { ProgressView().frame(maxWidth: .infinity) }
                }.padding()
            }
            .navigationTitle(Text("Sicherheitsnummer mit \(member.name)"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
        .macFrame(minWidth: 460, idealWidth: 520, minHeight: 520)
        .task { await load() }
        #if os(iOS)
        .sheet(isPresented: $scanning) {
            QRScannerSheet { text in scanning = false; scanResult = ChatLogic.safetyFromQR(text).map { $0 == ChatLogic.digitsOnly(number ?? "") } ?? false }
        }
        #endif
    }
    @ViewBuilder private var buttons: some View {
        if state?.verified == true { Label("Als überprüft markiert.", systemImage: "checkmark.seal.fill").foregroundStyle(.green) }
        else { Button("Als überprüft markieren") { Task { await conv.fedConfirm(member.handle, verified: true); await load() } }.prominentGold() }
        #if os(iOS)
        Button { scanning = true } label: { Label("QR-Code scannen", systemImage: "qrcode.viewfinder") }.buttonStyle(.bordered)
        #endif
    }
    private func load() async {
        let r = await conv.safetyV2(member.handle)
        number = r?.number; state = r?.state; loaded = true
    }
}

/// Gesperrte Personen und Server (Chats ueber Servergrenzen): GET/DELETE /api/chat/blocks
struct FedBlocksView: View {
    @EnvironmentObject var store: Store
    @State private var blocks: [(target: String, kind: String, label: String)] = []
    @State private var loaded = false
    @State private var supported = true
    @State private var error: String?
    var body: some View {
        Group {
            if !supported { EmptyView() }
            else {
                Section {
                    if blocks.isEmpty && loaded { Text("Niemand gesperrt.").foregroundStyle(.secondary) }
                    ForEach(blocks, id: \.target) { b in
                        HStack {
                            Image(systemName: b.kind == "instance" ? "server.rack" : "person.fill.xmark").foregroundStyle(.secondary).accessibilityHidden(true)
                            VStack(alignment: .leading) {
                                Text(verbatim: b.label)
                                Text(b.kind == "instance" ? String(localized: "Ganzer Server") : String(localized: "Person")).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Entsperren") { Task { await unblock(b.target) } }.buttonStyle(.bordered).controlSize(.small)
                        }
                    }
                    if let error { Text(verbatim: error).font(.footnote).foregroundStyle(.red) }
                } header: { Text("Gesperrt (andere Saga-Server)") } footer: {
                    Text("Von gesperrten Personen oder Servern kommen keine Nachrichtenanfragen mehr. Sie erfahren davon nichts.")
                }
            }
        }
        .task { await load() }
    }
    private func load() async {
        guard let (d, code) = try? await store.apiResponse("api/chat/blocks") else { return }
        guard code == 200, let j = ShareJSON.obj(d) else { supported = code != 404 && code != 405; loaded = true; return }   // 2.1/2.2: ohne Sperrliste
        blocks = (j["blocks"] as? [Any] ?? []).compactMap { v in
            guard let b = v as? [String: Any], let t = b["target"] as? String else { return nil }
            return (t, b["kind"] as? String ?? "person", ShareJSON.str(b["label"]) ?? t)
        }
        supported = true; loaded = true
    }
    private func unblock(_ t: String) async {
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "~.-_")
        do {
            _ = try await store.api("api/chat/blocks?target=" + (t.addingPercentEncoding(withAllowedCharacters: allowed) ?? t), method: "DELETE")
            error = nil; await load()
        } catch { self.error = error.localizedDescription }
    }
}

/// Treffer der Adressbuch-Suche: Profilbild, "Name · host"
struct FedPersonRow: View {
    let p: FedPerson
    var body: some View {
        HStack {
            ShareAvatar(name: p.name, group: false, avatar: p.avatar)
            VStack(alignment: .leading) {
                Text(verbatim: p.name)
                Text(verbatim: p.label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Image(systemName: "globe").foregroundStyle(.secondary).accessibilityLabel(Text("Anderer Saga-Server"))
        }.contentShape(Rectangle())
    }
}
