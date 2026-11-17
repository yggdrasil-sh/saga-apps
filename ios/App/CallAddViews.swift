import SwiftUI

// 2.5 (L8/L9): Knopf "Person hinzufuegen" (Kopf der Teilnehmerliste; direktes 1:1 nur Hinweis) und Auswahl als Karte an
// Stelle der Liste (wie Web static/calls.js addOpen: eigenes Feld, die Liste zeichnet sich beim Sprechen oft neu).
// Suchfeld mit Fokus, ohne Eingabe die Kontakte, sonst die Personensuche; wer im Anruf ist, fehlt; Mitglieder ohne
// Geraet "im Chat - klingelt erneut". Zeilen >= 44 pt, VoiceOver "{Name} hinzufuegen und anrufen". Voll: Hinweis,
// Zeilen deaktiviert. Seiten-Chat: Rueckfrage "Seite teilen?". Escape/Schliessen -> zurueck zur Liste.

/// Kleiner Knopf im Kopf der Teilnehmerliste (nur mit passendem addMode)
struct CallAddButton: View {
    @ObservedObject var calls: CallManager
    var body: some View {
        if calls.addMode != .none {
            Button { calls.addTapped() } label: {
                Image(systemName: "person.badge.plus").frame(width: 44, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(.plain).padding(-7)
            .accessibilityLabel(Text("Person hinzufügen")).help(Text("Person hinzufügen"))
        }
    }
}

/// Runder Knopf im Anruffenster fuer das direkte 1:1 (zeigt nur den Hinweis des Servers)
struct CallAddHintButton: View {
    @ObservedObject var calls: CallManager
    var body: some View {
        if case .hint = calls.addMode, calls.horn == nil {
            Button { calls.addTapped() } label: {
                Image(systemName: "person.badge.plus").frame(width: 36, height: 36)
                    .background(Color.black.opacity(0.45), in: Circle())
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(.plain).foregroundStyle(.white)
            .accessibilityLabel(Text("Person hinzufügen")).help(Text("Person hinzufügen"))
        }
    }
}

/// Hinweis im Anruffenster (Umzug in die neue Gruppe, direktes 1:1)
struct CallNoteChip: View {
    let text: String
    var body: some View {
        Label { Text(verbatim: text).fixedSize(horizontal: false, vertical: true) } icon: { Image(systemName: "person.3.fill") }
            .font(.footnote.weight(.medium)).multilineTextAlignment(.leading)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .frame(maxWidth: 440)
            .foregroundStyle(.white)
            .accessibilityElement(children: .combine)
    }
}

/// Auswahl "Person hinzufuegen" (Karte an Stelle der Teilnehmerliste)
struct CallAddPanel: View {
    @ObservedObject var calls: CallManager
    @Environment(\.dynamicTypeSize) private var dts
    /// Kontakte (einmal geladen - das Anruffenster haengt am Mac in einem eigenen Fenster ohne Umgebungs-Objekte)
    @State private var contacts: [CallAdd.Person] = []
    @State private var q = ""
    @State private var hits: [CallAdd.Person] = []
    @State private var searched = ""
    @FocusState private var field: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Person hinzufügen").font(.headline).accessibilityAddTraits(.isHeader)
                Spacer()
                Button { close() } label: { Image(systemName: "xmark").frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .buttonStyle(.plain).accessibilityLabel(Text("Schließen")).padding(-7)
                    .keyboardShortcut(.cancelAction)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            Divider()
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: hint).font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                TextField(String(localized: "Name suchen"), text: $q)
                    .textFieldStyle(.roundedBorder).autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .focused($field)
                    .accessibilityLabel(Text("Person suchen"))
                    .onSubmit { if let p = rows.first, CallAdd.selectable(p, seats: calls.addSeats) { calls.addPick(p) } }
                if let n = calls.addNote {
                    Label { Text(verbatim: n).fixedSize(horizontal: false, vertical: true) } icon: { Image(systemName: "info.circle.fill") }
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            ViewThatFits(in: .vertical) {
                list
                ScrollView { list }
            }
        }
        .frame(maxWidth: 440)
        .background(HornCardBackground(), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(radius: 12)
        .padding(16)
        .accessibilityAddTraits(.isModal)
        .onAppear { field = true }
        .task {
            guard let chat = calls.store?.chat else { return }
            if chat.contacts.isEmpty { await chat.loadContacts() }
            contacts = CallAdd.people(contacts: chat.contacts)
        }
        .onDisappear { calls.addOpen = false; calls.addAsk = nil }
        .task(id: q) { await search() }
        .alert(Text("Seite teilen?"), isPresented: Binding(get: { calls.addAsk != nil }, set: { if !$0 { calls.addAsk = nil } }), presenting: calls.addAsk) { a in
            Button(String(localized: "Abbrechen"), role: .cancel) { calls.addAsk = nil; field = true }
            Button(String(localized: "Teilen und anrufen")) { calls.addPick(a.person, confirm: true) }
        } message: { a in
            Text(verbatim: a.text + "\n\n" + String(localized: "Die Seite wird dafür mit Leserecht geteilt."))
        }
    }

    private var hint: String {
        if calls.addSeats.full { return String(localized: "Der Anruf ist voll – erst wenn jemand auflegt, kann noch jemand dazukommen.") }
        if calls.addMode == .move { return String(localized: "Es entsteht eine neue Gruppe mit euch beiden – der Anruf läuft ohne Unterbrechung weiter.") }
        if let t = calls.addCaps.addPage { return String(localized: "Hinzufügen teilt die Seite „\(t)“ (Lesen).") }
        return String(localized: "Mitglieder klingeln, andere Personen kommen dazu und klingeln dann.")
    }
    private var typed: Bool { !q.trimmingCharacters(in: .whitespaces).isEmpty }
    /// Zeilen: Kontakte bzw. Treffer, ohne Personen im Anruf
    private var rows: [CallAdd.Person] {
        let src = typed ? (searched == q ? hits : []) : contacts
        return CallAdd.visible(src, inCall: CallAdd.inCall(calls.roster.legs), away: CallAdd.away(calls.invites.entries ?? []))
    }
    private var list: some View {
        let r = rows, seats = calls.addSeats
        return VStack(alignment: .leading, spacing: 0) {
            if !typed && !r.isEmpty {
                Text("Kontakte").font(.caption.weight(.semibold)).foregroundStyle(.secondary).textCase(.uppercase)
                    .padding(.horizontal, 14).padding(.top, 4).padding(.bottom, 4).accessibilityAddTraits(.isHeader)
            }
            if r.isEmpty {
                Text(typed ? String(localized: "Niemand gefunden.") : String(localized: "Noch keine Kontakte – suche nach einem Namen."))
                    .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 14).padding(.vertical, 12)
            }
            ForEach(r) { p in row(p, seats: seats); Divider().padding(.leading, 56) }
        }
        .padding(.bottom, 6)
    }
    private func row(_ p: CallAdd.Person, seats: CallAdd.Seats) -> some View {
        let on = CallAdd.selectable(p, seats: seats) && calls.addBusy == nil
        let sub = p.away ? String(localized: "im Chat – klingelt erneut") : p.sub
        return Button { calls.addPick(p) } label: {
            HStack(spacing: 10) {
                AvatarView(name: p.name, avatar: p.avatar, size: 32).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: p.name).font(.subheadline.weight(.semibold)).lineLimit(dts.isAccessibilitySize ? 3 : 1)
                    if let s = sub { Text(verbatim: s).font(.caption).foregroundStyle(.secondary).lineLimit(dts.isAccessibilitySize ? 3 : 1) }
                }
                Spacer(minLength: 4)
                if calls.addBusy == p.ref { ProgressView().controlSize(.small) }
                else if p.remote { Image(systemName: "globe").foregroundStyle(.secondary).accessibilityHidden(true) }
                Image(systemName: "phone.badge.plus").foregroundStyle(on ? Color.green : Color.secondary).accessibilityHidden(true)
            }
            .padding(.horizontal, 14).padding(.vertical, 6).frame(minHeight: 52, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!on)
        .opacity(on || calls.addBusy == p.ref ? 1 : 0.45)
        .accessibilityLabel(Text("\(p.name) hinzufügen und anrufen"))
        .accessibilityHint(sub.map { Text(verbatim: $0) } ?? Text(verbatim: ""))
    }
    private func close() { calls.addOpen = false; calls.addNote = nil; calls.addAsk = nil }
    private func search() async {
        let v = q.trimmingCharacters(in: .whitespaces)
        guard !v.isEmpty else { hits = []; searched = ""; return }
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled, let store = calls.store, let e = v.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let d = try? await store.api("api/chat/people?q=\(e)") else { return }
        guard !Task.isCancelled else { return }
        hits = CallAdd.people(search: ShareJSON.obj(d)); searched = q
    }
}
