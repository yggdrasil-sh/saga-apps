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
    /// Bugs-DB 2125: Sprechring erlaubt - erst ab 3 verschiedenen Personen (Meeting.speakRingOn); speaking bleibt davon unberuehrt
    var ring = false
    var recent: [String] = []
    /// in den letzten 5 s gesprochen (Kacheln ohne Video: Nachlauf, Meeting.noVideoTail)
    var talked = Set<String>()
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
    /// Bugs-DB 2125/2459: Sprechring erlaubt (ab 3 Personen)? Ueber den Knoten kommen die Geraete schon aus der
    /// Teilnehmerliste; im Mesh zaehlt ebenfalls die Liste (wie Web/Android), nicht nur die Verbindungen - der Ring
    /// erscheint, sobald die dritte Person im Anruf ist, nicht erst, wenn die Verbindung zu ihr steht
    func meetRingOn(_ devs: [Meeting.Device]) -> Bool {
        #if DEBUG
        if meet.demo != nil { return Meeting.speakRingOn(devs) }
        #endif
        guard horn == nil, let h = devs.first(where: \.here) else { return Meeting.speakRingOn(devs) }
        let legs = roster.legs.map { Meeting.RingMember(device: $0.device, who: $0.who, companion: $0.companion, me: $0.device == h.device || $0.who == h.who) }
        let ls = links.filter { !$0.gone }.map { L in
            Meeting.RingMember(device: L.dev ?? "peer", who: roster.leg(L.dev)?.who ?? L.who, companion: roster.leg(L.dev)?.companion ?? false, me: L.own)
        }
        let ros = Meeting.ringRoster(legs: legs, links: ls, here: Meeting.RingMember(device: h.device, who: h.who, companion: h.companion, me: true))
        return Meeting.speakRingOn(ros, self: h.who)
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
        #if DEBUG
        if let d = meet.demo { return screen || d.first(where: { $0.device == dev })?.own != false ? nil : HornPreview.fakeRemote }   // Bilder: Testbild als Fernbild
        #endif
        let here = myDevice ?? horn?.meDevice ?? "here"
        if dev == here { return screen ? nil : (cam ? localVideo : nil) }
        if let H = horn { return screen ? (H.share?.dev == dev ? H.share?.track : nil) : H.vid[dev] }
        guard let L = dev == "peer" ? links.first(where: { $0.dev == nil && !$0.gone }) : link(dev) else { return nil }
        return screen ? (L.remote.screen ? L.remoteVideo : nil) : camTrack(L)
    }

    /// Pegel je Geraet -> je Person (Maximum) in die Sprecher-Hysterese; nur veroeffentlichen, wenn sich etwas aendert.
    /// Bugs-DB 2472: die eigene Kachel "spricht" nach Meeting.SelfRing (wie Web/Android, Mesh und Knoten gleich) - selfLevel
    /// und selfAt (ms, 0 = nie gemessen) sind der letzte eigene Messwert; die eigene Stimme geht nicht in die Hysterese
    /// (Buehne). Gemessen wird nur mit eigener Kachel (meetSelfTile), sonst ist die eigene Kachel aus.
    func meetLevels(_ byDevice: [String: Double], selfLevel: Double, selfAt: Double) {
        let devs = meetingDevices()
        var who: [String: String] = [:]
        for d in devs { who[d.device] = d.who }
        let myWho = devs.first(where: \.here)?.who
        var lv: [String: Double] = [:]
        for (d, l) in byDevice { if let w = who[d], w != myWho { lv[w] = max(lv[w] ?? 0, l) } }
        let now = Date().timeIntervalSince1970
        meetSpk.update(lv, me: myWho, now: now)
        let selfOn = meetSelfRing.update(now: now * 1000, level: selfLevel, at: selfAt, mic: mic) && meetSelfTile && levelsLive
        var s = meet
        var sp = Meeting.audible(meetSpk.speaking, devs)                     // 2459: stumm sofort aus
        if let m = myWho { if selfOn { sp.insert(m) } else { sp.remove(m) } }
        s.stage = meetSpk.stage; s.speaking = sp; s.recent = meetSpk.recent(now: now)
        s.ring = meetRingOn(devs)                                           // 2125: jede Messung - dritte Person kommt/geht
        s.talked = meetSpk.talked(within: Meeting.noVideoTail, now: now)
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
