import SwiftUI

// Saga 2.5 Gruppeninfo (chatadm1) und Kontakte (contacts1) - Server docs/APP-API-CHAT.md Abschnitt 6 und 7, Web
// static/chat.js groupInfo()/addSearch()/drawContacts(). Logik in GroupInfoLogic.swift (Tests/Logic/GroupInfoTests.swift).

// MARK: - Modell

extension ChatModel {
    /// Kontakte laden (zuletzt zuerst, hoechstens ContactsLogic.limit); Fehler: Liste bleibt
    func loadContacts() async {
        guard let store, store.features.has(ContactsLogic.feature),
              let d = try? await store.api(ContactsLogic.listPath) else { return }
        contacts = ContactsLogic.parse(ShareJSON.obj(d))
    }
    /// "Aus Kontakten entfernen" (ausgeblendet bis zu einem neuen gemeinsamen Chat); 404 = schon weg
    func hideContact(_ k: ChatContact) async {
        guard let store else { return }
        do {
            let (d, code) = try await store.apiResponse(ContactsLogic.deletePath(k.ref), method: "DELETE")
            guard (200..<300).contains(code) || code == 404 else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            contacts.removeAll { $0.ref == k.ref }
            toast = String(localized: "\(k.name) aus den Kontakten entfernt.")
        } catch { toast = error.localizedDescription }
    }
}

extension ConversationModel {
    /// Admin vergeben/entziehen: PATCH …/members/{who} {"admin": bool} -> volles Chat-Objekt; Fehler (409 letzter Admin,
    /// 403, 502) mit der Meldung des Servers
    func setAdmin(_ m: ChatMember, _ on: Bool) async -> String? {
        guard let store else { return nil }
        do {
            let (d, code) = try await store.apiResponse(GroupInfoLogic.memberPath(chatID, m.username), method: "PATCH", body: ["admin": on])
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            if let c = ShareJSON.obj(d)?["chat"], var s = ChatSummary(c) {
                if s.kind == "free" { s.decryptedTitle = info?.decryptedTitle ?? model.decryptTitle(s) }
                info = s
            }
            sendError = nil
            await model.loadList()
            return on ? String(localized: "\(m.me ? String(localized: "Du") : m.name) ist jetzt Admin.") : String(localized: "Admin-Rolle entzogen.")
        } catch { sendError = error.localizedDescription; return nil }
    }
    /// Kontakt hinzufuegen (lokal user, fremd handle)
    func addMember(contact k: ChatContact) async {
        if k.remote { await addMember(handle: k.ref) } else { await addMember(k.ref) }
    }
}

// MARK: - Kontaktliste (ohne Eingabe in "Neue Unterhaltung" und "Mitglied hinzufuegen")

struct ContactRow: View {
    let k: ChatContact
    var picked = false
    var body: some View {
        HStack {
            ShareAvatar(name: k.name, group: false, avatar: k.avatar)
            VStack(alignment: .leading) {
                Text(verbatim: k.name)
                if let s = ContactsLogic.subline(k) { Text(verbatim: s).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            }
            Spacer()
            if picked { Image(systemName: "checkmark").foregroundStyle(Color.gold) }
            if k.remote { Image(systemName: "globe").foregroundStyle(.secondary).accessibilityLabel(Text("Anderer Saga-Server")) }
        }
        .contentShape(Rectangle())
        .opacity(k.gone ? 0.45 : 1)
        .help(k.gone ? Text("Gerade nicht erreichbar") : Text(verbatim: ""))
    }
}

/// Abschnitt "Kontakte": waehlbar (ausser gone), Wischen bzw. Kontextmenue "Aus Kontakten entfernen"
struct ContactsSection: View {
    @ObservedObject var chat: ChatModel
    let list: [ChatContact]
    var picked: (ChatContact) -> Bool = { _ in false }
    var disabled = false
    let pick: (ChatContact) -> Void
    var body: some View {
        if !list.isEmpty {
            Section("Kontakte") {
                ForEach(list) { k in
                    Button { pick(k) } label: { ContactRow(k: k, picked: picked(k)) }
                        .buttonStyle(.plain)
                        .disabled(disabled || !ContactsLogic.selectable(k))
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) { Task { await chat.hideContact(k) } } label: { Label("Aus Kontakten entfernen", systemImage: "person.crop.circle.badge.minus") }
                        }
                        .contextMenu {
                            Button(role: .destructive) { Task { await chat.hideContact(k) } } label: { Label("Aus Kontakten entfernen", systemImage: "person.crop.circle.badge.minus") }
                        }
                }
            }
        }
    }
}

// MARK: - Gruppeninfo

struct GroupInfoSheet: View {
    @ObservedObject var conv: ConversationModel
    var safety: (ChatMember) -> Void
    /// nach "Gruppe verlassen" (Chat schliessen)
    var left: () -> Void
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var editing = false
    @State private var newName = ""
    @State private var adding = false
    @State private var q = ""
    @State private var hits: [(String, String, String?, Bool)] = []    // ref, Name, sub, Kontakt
    @State private var remoteHits: [FedPerson] = []
    @State private var remoteInLocal = false
    @State private var removing: ChatMember?
    @State private var askLeave = false
    @State private var notice: String?
    @State private var busy = false

    private var feature: Bool { store.features.has(GroupInfoLogic.feature) }
    private var contactsOn: Bool { store.features.has(ContactsLogic.feature) }

    var body: some View {
        NavigationStack {
            Form {
                if let i = conv.info {
                    let r = i.rights(feature: feature)
                    head(i, r)
                    membersSection(i, r)
                    if i.kind == "free" && r.manage && adding { addSection(i) }
                    if let e = conv.sendError { Text(verbatim: e).font(.footnote).foregroundStyle(.red) }
                    if let n = notice { Text(verbatim: n).font(.footnote).foregroundStyle(.secondary) }
                    footer(i)
                } else {
                    ProgressView()
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Gruppeninfo")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
            .task(id: q) { await search() }
            .task { conv.sendError = nil; if contactsOn { await conv.model.loadContacts() } }
            .alert(Text("\(removing?.name ?? "") aus der Gruppe entfernen?"), isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                   presenting: removing) { m in
                Button("Entfernen", role: .destructive) {
                    Task { await conv.removeMember(m.username); if conv.sendError == nil { notice = String(localized: "\(m.name) wurde entfernt.") } }
                }
                Button("Abbrechen", role: .cancel) {}
            } message: { m in Text("\(m.name) bekommt keine neuen Nachrichten dieser Gruppe mehr.") }
            .alert("Gruppe verlassen?", isPresented: $askLeave) {
                Button("Verlassen", role: .destructive) { Task { if await conv.leave() { dismiss(); left() } } }
                Button("Abbrechen", role: .cancel) {}
            } message: { Text("Du bekommst keine neuen Nachrichten mehr.") }
        }
        .macFrame(minWidth: 440, idealWidth: 480, minHeight: 480, idealHeight: 600)
    }

    // Kopf: Bild, Name (bearbeitbar bei can_rename), Art und Anzahl, Hinweise
    @ViewBuilder private func head(_ i: ChatSummary, _ r: GroupRights) -> some View {
        Section {
            HStack(spacing: 12) {
                ShareAvatar(name: i.title, group: true)
                VStack(alignment: .leading, spacing: 2) {
                    if editing {
                        TextField("Name der Gruppe", text: $newName).onSubmit { saveName(i) }
                            .textFieldStyle(.roundedBorder)
                    } else {
                        Text(verbatim: i.title).font(.headline)
                    }
                    Text(verbatim: kindLine(i)).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if i.kind == "free" && r.rename {
                    if editing {
                        Button("Speichern") { saveName(i) }.buttonStyle(.borderless).disabled(busy || newName.trimmingCharacters(in: .whitespaces).isEmpty)
                        Button("Abbrechen") { editing = false }.buttonStyle(.borderless)
                    } else {
                        Button { newName = i.decryptedTitle ?? ""; editing = true } label: { Image(systemName: "pencil") }
                            .buttonStyle(.borderless).help(Text("Gruppe umbenennen")).accessibilityLabel(Text("Gruppe umbenennen"))
                    }
                }
            }
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if i.kind == "free" && r.rename { Text("Der Name wird wie eine Nachricht verschlüsselt.") }
                if r.readOnlyMembers { Text("Mitglieder ergeben sich hier aus der Gruppe bzw. den Freigaben der Seite.") }
                if let f = i.fed, f.remote {
                    if f.homeSelf { Text("Dieser Chat liegt auf diesem Server; Personen anderer Saga-Server sind über ihren Server dabei.") }
                    else { Text("Dieser Chat liegt auf \(f.home). Mitglieder ändert man dort (über diesen Server).") }
                }
                if let f = i.fed, f.readonly { Text(verbatim: "🔒 " + (f.readonlyReason ?? String(localized: "schreibgeschützt"))) }
            }
        }
    }
    private func kindLine(_ i: ChatSummary) -> String {
        let n = String(localized: "\(i.memberCount) Mitglieder")
        switch i.kind {
        case "group": return String(localized: "Gruppen-Chat") + " „" + (i.group ?? "") + "“ · " + n
        case "page": return String(localized: "Seiten-Chat") + " · " + n
        default: return String(localized: "Freie Gruppe") + " · " + n
        }
    }
    private func saveName(_ i: ChatSummary) {
        let n = newName.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty, n != i.decryptedTitle else { editing = false; return }
        busy = true
        Task {
            if await conv.send(text: "", replyTo: nil, files: [], kind: "title", title: n) { editing = false }
            busy = false
        }
    }

    // Mitglieder: ich, Admins, Name; Kennzeichen "Du"/"Admin", Handle (nie Anmeldename), Menue je Mitglied
    @ViewBuilder private func membersSection(_ i: ChatSummary, _ r: GroupRights) -> some View {
        Section {
            if i.kind == "free" && r.manage {
                Button { adding.toggle(); if !adding { q = "" } } label: {
                    Label(adding ? String(localized: "Abbrechen") : String(localized: "Mitglied hinzufügen"), systemImage: adding ? "xmark" : "person.badge.plus")
                }
            }
            ForEach(GroupInfoLogic.sorted(i.members)) { m in memberRow(m, i, r) }
        } header: { Text("\(i.memberCount) Mitglieder") }
    }
    private func memberRow(_ m: ChatMember, _ i: ChatSummary, _ r: GroupRights) -> some View {
        HStack {
            ShareAvatar(name: m.name, group: false, avatar: m.avatar)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(verbatim: m.name)
                    if m.me { badge(String(localized: "Du")) }
                    if m.admin { badge(String(localized: "Admin")) }
                }
                if let s = GroupInfoLogic.subline(m, myHandle: i.fed?.myHandle, remoteChat: i.fed?.remote == true) {
                    Text(verbatim: s).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
            if !m.me, verified(m) { Image(systemName: "checkmark.seal.fill").foregroundStyle(.green).accessibilityLabel(Text("überprüft")) }
            let acts = GroupInfoLogic.actions(me: m.me, admin: m.admin, kind: i.kind, rights: r)
            if !acts.isEmpty {
                Menu {
                    ForEach(acts, id: \.self) { a in actionButton(a, m) }
                } label: { Image(systemName: "ellipsis.circle") }
                .menuStyle(.borderlessButton).fixedSize()
                .accessibilityLabel(Text("Aktionen für \(m.name)")).help(Text("Aktionen für \(m.name)"))
            }
        }
    }
    @ViewBuilder private func actionButton(_ a: GroupInfoLogic.MemberAction, _ m: ChatMember) -> some View {
        switch a {
        case .safety: Button { safety(m) } label: { Label("Sicherheitsnummer", systemImage: "checkmark.shield") }
        case .makeAdmin: Button { admin(m, true) } label: { Label("Zum Admin machen", systemImage: "star") }
        case .revokeAdmin: Button { admin(m, false) } label: { Label("Admin entziehen", systemImage: "star.slash") }
        case .remove: Button(role: .destructive) { removing = m } label: { Label("Aus Gruppe entfernen", systemImage: "person.badge.minus") }
        }
    }
    private func admin(_ m: ChatMember, _ on: Bool) {
        Task { notice = await conv.setAdmin(m, on) }
    }
    private func badge(_ t: String) -> some View {
        Text(verbatim: t).font(.caption2.weight(.semibold))
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(Color.secondary.opacity(0.15), in: Capsule())
    }

    // Mitglied hinzufuegen: ohne Eingabe Kontakte (ohne Mitglieder; fremde nur in Gruppen ueber Servergrenzen), sonst Suche
    @ViewBuilder private func addSection(_ i: ChatSummary) -> some View {
        Section {
            TextField("Person hinzufügen", text: $q).autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                #endif
            if !q.trimmingCharacters(in: .whitespaces).isEmpty {
                if remoteInLocal {
                    Text("Personen anderer Saga-Server lassen sich nur zu neuen Gruppen hinzufügen – bitte eine neue Gruppe anlegen.")
                        .font(.footnote).foregroundStyle(.secondary)
                } else if ChatFedHint.show(q, hits: hits.count + remoteHits.count) { FedChatHint(federated: conv.model.myHandle != nil) }
                ForEach(hits, id: \.0) { h in
                    Button { add { await conv.addMember(h.0) } } label: {
                        VStack(alignment: .leading) {
                            Text(verbatim: h.1)
                            if let s = h.2 { Text(verbatim: s).font(.caption).foregroundStyle(.secondary) }
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain).disabled(busy)
                }
                ForEach(remoteHits) { p in
                    Button { if let h = p.handle { add { await conv.addMember(handle: h) } } } label: { FedPersonRow(p: p) }
                        .buttonStyle(.plain).disabled(busy)
                }
            }
        } header: { Text("Mitglied hinzufügen") }
        if q.trimmingCharacters(in: .whitespaces).isEmpty && contactsOn {
            ContactsSection(chat: conv.model, list: ContactsLogic.forAdd(conv.model.contacts, members: i.members, fedChat: i.isFed), disabled: busy) { k in
                add { await conv.addMember(contact: k) }
            }
        }
    }
    private func add(_ op: @escaping () async -> Void) {
        busy = true
        Task { await op(); busy = false; q = ""; if conv.sendError == nil { await conv.model.loadContacts() } }
    }
    private func search() async {
        guard ShareLogic.searchReady(q, server: store.serverVersion), let e = q.trimmingCharacters(in: .whitespaces).addingPercentEncoding(withAllowedCharacters: .alphanumerics) else {
            hits = []; remoteHits = []; remoteInLocal = false; return
        }
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled, let d = try? await store.api("api/chat/people?q=\(e)"), let j = ShareJSON.obj(d) else { return }
        let have = (conv.info?.members ?? []).map { (username: $0.username, ref: $0.ref) }
        // Kontakte stehen beim Server schon vorne (contact: true) - keine zweite Abfrage
        hits = (j["users"] as? [Any] ?? []).compactMap { v in (v as? [String: Any]).flatMap { u in
            (u["username"] as? String).map { ($0, u["name"] as? String ?? $0, PersonRef.sub(u["sub"]), ShareJSON.bool(u["contact"])) } } }
            .filter { !PersonRef.isMember($0.0, members: have) }
        let handles = Set(conv.fed?.members.map(\.handle) ?? [])
        let rem = FedChatLogic.people(j["remote"]).filter { $0.handle != nil && !handles.contains($0.handle ?? "") }
        remoteHits = conv.fed == nil ? [] : rem
        remoteInLocal = conv.fed == nil && !rem.isEmpty && hits.isEmpty
    }

    // unten: Stummschalten, Archivieren, Gruppe verlassen (freie Gruppe)
    @ViewBuilder private func footer(_ i: ChatSummary) -> some View {
        Section {
            Button { Task { await conv.patch(["muted": !i.muted]) } } label: {
                Label(i.muted ? "Benachrichtigungen an" : "Stummschalten", systemImage: i.muted ? "bell" : "bell.slash")
            }
            if i.canArchive {
                Button { let on = !i.archived; conv.info?.archived = on; Task { await conv.model.setArchived(i.id, on) } } label: {
                    Label(i.archived ? "Aus Archiv holen" : "Archivieren", systemImage: i.archived ? "tray.and.arrow.up" : "archivebox")
                }
            }
            if i.kind == "free" {
                Button(role: .destructive) { askLeave = true } label: { Label("Gruppe verlassen", systemImage: "rectangle.portrait.and.arrow.right") }
            }
        }
    }
    private func fedMember(_ m: ChatMember) -> FedMember? { conv.fed?.members.first { $0.username == m.username } }
    private func verified(_ m: ChatMember) -> Bool {
        if let f = fedMember(m) { return conv.fk[f.handle]?.verified == true }
        return ChatTrustStore.known(conv.model.me.uid, m.username)?.verified == true
    }
}
