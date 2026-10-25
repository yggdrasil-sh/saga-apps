import SwiftUI
import WebRTC

// Meeting-Ansicht (MEETING-SPEC): Bruecke vom Anrufzustand (Mesh-Verbindungen bzw. Knoten) zur reinen Logik
// (MeetingLogic.swift) - Geraete je Person, Bilder je Geraet, Pegel fuer die Sprecher-Hysterese, Anheften.

/// Zustand der Ansicht nur fuer diesen Anruf (nicht gespeichert): Pin, Seite, Hinweise, Sprecher, Zoom, Vollbild
struct MeetState: Equatable {
    var pin: String?
    var page = 0
    /// "Anzeigen" im Hinweis (Galerie): fuer diesen Anruf Praesentation
    var override: Meeting.Layout?
    /// Bildschirm-Hinweise, die fuer diese Freigabe weggeklickt sind
    var hintGone = Set<String>()
    /// Person auf der Buehne (Sprecher-Hysterese) und wer gerade spricht (Sprechring)
    var stage: String?
    var speaking = Set<String>()
    var recent: [String] = []
    var zoom = 1.0
    /// iPad/iPhone: Leisten aus und System-Overlays verborgen (Doppeltippen auf den Bildschirm)
    var immersive = false
    /// Teilnehmerliste offen (Mesh/1:1; Knoten: HornSession.plist)
    var people = false
    #if DEBUG
    /// nur Bilder (HornPreview "meet-*"): erfundene Geraete ohne Medien
    var demo: [Meeting.Device]?
    #endif
}

extension CallManager {

    /// Geraete im Anruf fuer die Ansicht (eigenes zuerst)
    func meetingDevices() -> [Meeting.Device] {
        #if DEBUG
        if let d = meet.demo { return d }
        #endif
        let now = Date().timeIntervalSince1970
        let here = myDevice ?? horn?.meDevice ?? "here"
        let hereLeg = roster.leg(here)
        let myWho = hereLeg?.who ?? (me.isEmpty ? "me" : me)
        var out = [Meeting.Device(device: here, who: myWho, name: hereLeg?.name ?? String(localized: "du"), devName: hereLeg?.devName ?? "",
                                  avatar: hereLeg?.avatar, companion: companion, own: true, here: true, mic: mic,
                                  cam: cam && localVideo != nil, screen: sharing)]
        if let H = horn {
            for x in roster.legs where x.device != here {
                let s = H.st[x.device]
                let scr = H.share?.dev == x.device
                let wants = s?.screen == true
                out.append(Meeting.Device(device: x.device, who: x.who, name: x.name, devName: x.devName, avatar: x.avatar, companion: x.companion,
                                          own: x.who == myWho, mic: s?.mic ?? true, cam: s?.cam != false && H.vid[x.device] != nil,
                                          screen: scr || wants, screenSince: meetSince(x.device, on: scr || wants, now: now),
                                          screenMissing: wants && !scr))
            }
        } else {
            for L in links where !L.gone {
                let dev = L.dev ?? "peer"
                let x = roster.leg(L.dev)
                let scr = L.remote.screen && L.remoteVideo != nil
                out.append(Meeting.Device(device: dev, who: x?.who ?? L.who, name: x?.name ?? L.name, devName: x?.devName ?? L.devName,
                                          avatar: L.avatar ?? x?.avatar, companion: x?.companion ?? false, own: L.own,
                                          mic: L.remote.mic, cam: L.remote.cam && camTrack(L) != nil, screen: scr,
                                          screenSince: meetSince(dev, on: scr, now: now)))
            }
        }
        return out
    }
    private func meetSince(_ dev: String, on: Bool, now: Double) -> Double {
        if !on { meetShareAt[dev] = nil; return 0 }
        if let t = meetShareAt[dev] { return t }
        meetShareAt[dev] = now
        return now
    }
    /// Kamera einer Verbindung (teilt sie, ist remoteVideo der Bildschirm und remoteSide die Kamera)
    func camTrack(_ L: CallLink) -> RTCVideoTrack? { L.remote.screen ? L.remoteSide : (L.remote.cam ? L.remoteVideo : nil) }
    /// Bild eines Geraets: Kamera bzw. Bildschirm
    func meetTrack(_ dev: String, screen: Bool) -> RTCVideoTrack? {
        let here = myDevice ?? horn?.meDevice ?? "here"
        if dev == here { return screen ? nil : (cam ? localVideo : nil) }
        if let H = horn { return screen ? (H.share?.dev == dev ? H.share?.track : nil) : H.vid[dev] }
        guard let L = dev == "peer" ? links.first(where: { $0.dev == nil && !$0.gone }) : link(dev) else { return nil }
        return screen ? (L.remote.screen ? L.remoteVideo : nil) : camTrack(L)
    }

    /// Pegel je Geraet -> je Person (Maximum) in die Sprecher-Hysterese; nur veroeffentlichen, wenn sich etwas aendert
    func meetLevels(_ byDevice: [String: Double], selfLevel: Double) {
        let devs = meetingDevices()
        var who: [String: String] = [:]
        for d in devs { who[d.device] = d.who }
        let myWho = devs.first(where: \.here)?.who
        var lv: [String: Double] = [:]
        for (d, l) in byDevice { if let w = who[d], w != myWho || d == devs.first(where: \.here)?.device { lv[w] = max(lv[w] ?? 0, l) } }
        if let m = myWho { lv[m] = max(lv[m] ?? 0, mic ? selfLevel : 0) }
        let now = Date().timeIntervalSince1970
        meetSpk.update(lv, me: myWho, now: now)
        var s = meet
        s.stage = meetSpk.stage; s.speaking = meetSpk.speaking; s.recent = meetSpk.recent(now: now)
        if s != meet { meet = s }
    }

    // MARK: Anheften

    func meetPin(_ id: String?) {
        var s = meet
        s.pin = s.pin == id ? nil : id
        if s.pin == nil { s.zoom = 1 }
        meet = s
    }
    /// Quelle des Pins ist weg (Person gegangen, Teilen beendet) -> Pin faellt weg, kurzer Hinweis
    func meetCheckPin(_ tiles: [Meeting.Tile]) {
        guard let p = meet.pin, !tiles.contains(where: { $0.id == p }) else { return }
        meet.pin = nil; meet.zoom = 1
        toast = String(localized: "Angeheftete Ansicht beendet")
    }
    /// Eigenes Begleitgeraet trennen (verlaesst den Anruf)
    func meetDropOwn(_ dev: String) {
        guard let id, let store else { return }
        Task { _ = try? await store.apiResponse("api/calls/\(id)/leave", method: "POST", body: ["device": dev]) }
    }
}
