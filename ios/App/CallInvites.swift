import SwiftUI

// 2.5 (L7, Vertrag docs/APP-API.md "Anrufe: Erneut klingeln und Nicht dabei"): Abschnitt "Nicht dabei" in der
// Teilnehmerliste eines Gruppenanrufs (direkt/Mesh und ueber Knoten) und POST /api/calls/{id}/invite. Logik in
// InviteLogic.swift (Invites), Ansicht InviteViews.swift.

extension CallManager {
    /// Anruf-Objekt, Ereignis call.invites bzw. /invite-Antwort: Liste uebernehmen (nur mit `invites`, nicht aelter als
    /// die zuletzt uebernommene - eigener Zaehler rv)
    func invitesIn(_ d: [String: Any]) {
        guard d["invites"] is [Any], !(kind == "group" && dmRoom) else { return }
        var l = invites
        guard l.apply(d, device: Date().timeIntervalSince1970) else { return }
        if l != invites { invites = l }
        invSchedule()
    }
    /// Neu zeichnen, sobald ein "klingelt …" ablaeuft (Server-Ereignis kann ausbleiben) bzw. ein Klick ohne Antwort verfaellt
    func invSchedule() {
        invTask?.cancel(); invTask = nil
        guard let t = invites.nextChange(device: Date().timeIntervalSince1970) else { return }
        invTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(t + 0.3))
            guard let self, !Task.isCancelled else { return }
            self.invTick &+= 1
            self.invSchedule()
        }
    }
    /// Abschnitt "Nicht dabei" zeigen? Gruppenanruf (nicht 1:1 ueber Knoten), Server liefert `invites`
    var invitesShown: Bool { kind == "group" && !dmRoom && invites.visible }

    /// Erneut klingeln lassen (Knopf je Person bzw. "Alle erneut klingeln"): sofort "klingelt …" (vorlaeufig), Antwort
    /// uebernehmen; Fehler zuruecknehmen + Hinweis (403 Text des Servers, 429 "zu oft")
    func inviteRing(_ entries: [Invites.Entry]) {
        guard let store, let cid = id, !ending, !entries.isEmpty else { return }
        let who = Array(entries.map(\.who).prefix(Invites.ringMax))
        var l = invites
        let until = l.ringStart(who, device: Date().timeIntervalSince1970)
        invites = l; invNote = nil
        invSchedule()
        CallEnv.log("invite \(who.count)")
        Task { [weak self] in
            let r = try? await store.apiResponse("api/calls/\(cid)/invite", method: "POST", body: ["uids": who])
            guard let self, self.id == cid, !self.ending else { return }
            let body = r.flatMap { ShareJSON.obj($0.0) }
            var l = self.invites
            l.ringEnd(who, until: until)
            self.invites = l
            if let f = Invites.failure(code: r?.1, body: body) {
                let t: String
                switch f {
                case .tooOften: t = String(localized: "Gerade zu oft geklingelt – bitte etwa eine Minute warten.")
                case .server(let s): t = s
                case .generic: t = String(localized: "Klingeln hat nicht geklappt.")
                }
                self.invNote = t
                AccessibilityNotification.Announcement(t).post()
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(8))
                    if self?.invNote == t { self?.invNote = nil }
                }
            } else {
                if let b = body { self.invitesIn(b) }
                AccessibilityNotification.Announcement(entries.count == 1 ? String(localized: "\(entries[0].name) wird angerufen …")
                                                       : String(localized: "\(entries.count) Personen werden angerufen …")).post()
            }
            self.invSchedule()
        }
    }
}
