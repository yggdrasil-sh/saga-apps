import SwiftUI

// 2.5 (L8 "Personen hinzufuegen", L9 "1:1 -> Konferenz", Vertrag docs/APP-API.md): Knopf "Person hinzufuegen" in der
// Teilnehmerliste, Auswahl aus Kontakten/Personensuche, POST /api/calls/{id}/add (409 page_confirm -> Rueckfrage ->
// confirm_page), danach Gruppenname fuer Neue erneut senden. Im 1:1 ueber Knoten zieht derselbe Aufruf in eine neue
// freie Gruppe um (Antwort moved/call bzw. Ereignis call.move): der Anruf laeuft OHNE Neuaufbau als Gruppenanruf weiter
// (dmRoom aus, Titel/CallKit-Name, Chat-Knopf -> neue Gruppe, "Nicht dabei"). Logik in CallAddLogic.swift.

/// Rueckfrage im Seiten-Chat ("Seite teilen?") fuer genau diese Person
struct CallAddAsk: Identifiable, Equatable {
    let id = UUID()
    var text: String
    var person: CallAdd.Person
}

extension CallManager {
    /// Was der Knopf "Person hinzufuegen" gerade tut (none = nicht zeigen)
    var addMode: CallAdd.Mode {
        let live: Bool
        if kind == "group" { live = [.ringingOut, .connecting, .active].contains(phase) && (inRoom || ready) }
        else { live = phase == .active }
        return CallAdd.mode(features: serverFeatures, kind: kind, dmRoom: dmRoom, caps: addCaps, live: live && !ending, companion: companion)
    }
    /// Plaetze nach der Teilnehmerliste (jedes Geraet zaehlt)
    var addSeats: CallAdd.Seats { CallAdd.seats(legs: roster.legs.count, limit: limit) }

    /// Knopf gedrueckt: Auswahl oeffnen bzw. nur den Hinweis zeigen (direktes/foederiertes 1:1)
    func addTapped() {
        switch addMode {
        case .none: return
        case .add, .move: addNote = nil; addOpen = true
        case .hint(let t):
            inCallNote(t ?? String(localized: "Dieser Anruf läuft direkt zwischen euren Geräten – jemanden dazuholen geht nur über einen Anruf-Knoten. Legt dafür auf und startet einen Gruppenanruf in einer neuen Gruppe."), seconds: 9)
        }
    }

    /// Hinweis im Anruffenster (und fuer VoiceOver)
    func inCallNote(_ t: String, seconds: Double = 5) {
        callNote = t
        AccessibilityNotification.Announcement(t).post()
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            if self?.callNote == t { self?.callNote = nil }
        }
    }
    /// Hinweis in der Auswahl (bleibt kurz stehen)
    private func addSay(_ t: String) {
        addNote = t
        AccessibilityNotification.Announcement(t).post()
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(8))
            if self?.addNote == t { self?.addNote = nil }
        }
    }

    /// Person waehlen: POST /add; Seiten-Chat ohne Zustimmung -> Rueckfrage (addAsk), danach mit confirm_page erneut
    func addPick(_ p: CallAdd.Person, confirm: Bool = false) {
        guard let store, let cid = id, !ending, addBusy == nil, addMode == .add || addMode == .move else { return }
        addBusy = p.ref; addAsk = nil
        let wasDM = kind == "group" && dmRoom
        let chat = chatID
        CallEnv.log("add \(wasDM ? "move" : "group")\(confirm ? " confirm" : "")")
        Task { [weak self] in
            let r = try? await store.apiResponse("api/calls/\(cid)/add", method: "POST", body: CallAdd.body(p.ref, confirmPage: confirm))
            guard let self, self.id == cid else { return }
            self.addBusy = nil
            let body = r.flatMap { ShareJSON.obj($0.0) }
            switch CallAdd.outcome(code: r?.1, body: body, confirmed: confirm) {
            case .pageConfirm(let t):
                self.addAsk = CallAddAsk(text: t, person: p)
            case .tooOften:
                self.addSay(String(localized: "Gerade zu oft geklingelt – bitte etwa eine Minute warten."))
            case .server(let t):
                self.addSay(t)
            case .generic:
                self.addSay(String(localized: "Hinzufügen hat nicht geklappt."))
            case .done(let d):
                if let m = d.moved, !self.ending { self.moveIn(m, call: body?["call"] as? [String: Any]) }
                else if let b = body { self.invitesIn(b) }
                switch CallAdd.note(d) {
                case .failed(let t): self.addSay(t)
                case .pending: self.addSay(String(localized: "\(p.name) kommt dazu, sobald der andere Server zugestimmt hat."))
                case .request: self.addSay(String(localized: "\(p.name) ist eingeladen – es klingelt, sobald die Einladung in den Chat angenommen ist."))
                case .already: self.addSay(String(localized: "\(p.name) ist schon im Anruf oder klingelt gerade."))
                case .ringing: self.addSay(String(localized: "\(p.name) wird angerufen …"))
                }
                if !d.added.isEmpty {
                    let target = d.moved?.to ?? chat
                    Task { await store.chat.afterCallAdd(chatID: target, added: d.added, moved: d.moved != nil) }
                }
            }
        }
    }

    // MARK: 1:1 -> Konferenz (L9)

    /// Anruf haengt jetzt am neuen Chat: dieselben Verbindungen, Beine und Schluessel - nur Anzeige und Zuordnung wechseln
    func moveIn(_ m: CallAdd.Moved, call c: [String: Any]?) {
        guard let cid = id, !ending, movedTo != m.to else { return }
        movedTo = m.to
        let from = m.from != 0 ? m.from : chatID
        CallEnv.log("moved \(cid.prefix(8)) -> group")
        kind = "group"; dmRoom = false; chatID = m.to
        title = ""; peerAvatar = nil
        if let c, (c["id"] as? String) == cid { adoptCall(c) }
        else {
            peerName = String(localized: "Gruppenanruf")
            Task { [weak self] in                                          // Anruf-Objekt der neuen Gruppe (can_add, invites)
                guard let self, let store = self.store, let dev = self.myDevice else { return }
                let r = try? await store.apiResponse("api/calls/\(cid)?device=\(dev)")
                guard self.id == cid, !self.ending, r?.1 == 200, let c = r.flatMap({ ShareJSON.obj($0.0)?["call"] as? [String: Any] }) else { return }
                self.adoptCall(c); self.moveTitle()
            }
        }
        moveTitle()
        inCallNote(String(localized: "Neue Gruppe – der Anruf läuft dort ohne Unterbrechung weiter."))
        movedElsewhere(CallAdd.Moved(from: from, to: m.to))
        // Name der neuen Gruppe aus der Chatliste, sobald sie geladen ist (freie Gruppe ohne Namen: Namen der Mitglieder)
        Task { [weak self] in
            for ms in [0, 1500, 4000] {
                if ms > 0 { try? await Task.sleep(for: .milliseconds(ms)) }
                guard let self, self.id == cid, !self.ending, self.chatID == m.to else { return }
                if ms == 0 { await self.store?.chat.loadList() }
                if self.moveTitle() { return }
            }
        }
    }
    /// Titel aus der Chatliste nachziehen, CallKit-Name und Kopf aktualisieren -> Titel gefunden?
    @discardableResult
    func moveTitle() -> Bool {
        if let t = store?.chat.chats.first(where: { $0.id == chatID })?.title, !t.isEmpty, t != title { title = t; peerName = t }
        if title.isEmpty { peerName = String(localized: "Gruppenanruf") }
        CallSystem.shared.groupInfo(self, count: roster.legs.count)
        horn?.objectWillChange.send()
        return !title.isEmpty
    }
    /// Chat-Ansicht: ist das alte 1:1 offen, den neuen Chat oeffnen; ist der neue offen, neu laden
    func movedElsewhere(_ m: CallAdd.Moved) {
        guard let chat = store?.chat else { return }
        if CallAdd.openNew(open: chat.open?.chatID, move: m) { chat.pendingOpen = m.to }
        else if chat.open?.chatID == m.to, let o = chat.open { Task { await o.load() } }
        Task { await chat.loadList() }
    }
}

extension ChatModel {
    /// 2.5 (L8): nach "Person hinzufuegen" im Anruf wie die Gruppeninfo den Gruppennamen erneut verschluesselt senden,
    /// damit Neue ihn lesen koennen (freie Gruppe mit Namen); ohne offenen Chat ueber ein eigenes Modell
    func afterCallAdd(chatID: Int, added: [String], moved: Bool) async {
        let conv = open?.chatID == chatID ? open! : ConversationModel(chatID: chatID, model: self)
        if conv !== open { await conv.loadInfo() } else { await conv.load() }
        if CallAdd.resendTitle(added: added, kind: conv.info?.kind, title: conv.info?.decryptedTitle, moved: moved), let t = conv.info?.decryptedTitle {
            _ = await conv.send(text: "", replyTo: nil, files: [], kind: "title", title: t)
        }
        await loadList()
    }
}

extension ConversationModel {
    /// Nur Kopfdaten und Empfaenger (ohne Verlauf, ohne "gelesen") - fuer Aufgaben im Hintergrund wie afterCallAdd
    func loadInfo() async {
        guard let store, let (d, code) = try? await store.apiResponse("api/chats/\(chatID)?\(model.deviceParam)"), code == 200,
              let c = ShareJSON.obj(d)?["chat"] else { return }
        var s = ChatSummary(c)
        if s?.kind == "free", let x = s { s?.decryptedTitle = model.decryptTitle(x) }
        info = s
        if s?.isFed == true { await fedLoad() }
        if s?.isFed == true, s?.kind == "free", let x = s { var l = [x]; await model.decryptTitlesV2(&l); info = l[0] }
        if s?.isFed != true { await loadRecipients() }
    }
}
