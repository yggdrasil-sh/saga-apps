#if DEBUG
import SwiftUI

// Nur fuer Bilder (Debug-Build, ohne Server, ohne Kamera/Mikrofon): Gruppenanruf ueber einen Knoten mit erfundenem
// Zustand. Start: -hkHornPreview grid|speaker|share|people|runes [-hkHornDark YES] [-hkHornPath udp|tcp|turn]
// (Mac zusammen mit -hkSnapshot <pfad.png>; iPhone/iPad: xcrun simctl io <id> screenshot). Bewegte Bilder sind hier
// Farbflaechen (kein WebRTC), Namen und Geraete erfunden.

enum HornPreview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkHornPreview") }
}


struct HornPreviewView: View {
    @ObservedObject var store: Store
    @State private var ready = false
    private let mode = HornPreview.mode ?? "grid"
    var body: some View {
        Group {
            if ready { CallScreen(calls: store.calls) } else { Color.black }
        }
        .environment(\.hornPreviewFill, true)
        .preferredColorScheme(UserDefaults.standard.bool(forKey: "hkHornDark") ? .dark : .light)
        .task { setUp() }
    }

    private func setUp() {
        guard !ready else { return }
        let c = store.calls
        let people: [(String, String, String)] = [("Bea", "iPhone", "member"), ("Tom", "MacBook", "member"), ("Ana", "iPad", "member"), ("Kai", "Pixel", "member"),
                                                  ("Lena", "Firefox", "member"), ("Mo", "iPhone", "member"), ("Ida", "Chrome", "member"), ("Jon", "Galaxy", "member"),
                                                  ("Eva", "Safari", "member"), ("Ole", "iPhone", "member"), ("Uma", "Mac mini", "member")]
        var legs: [[String: Any]] = [["device": "me000000000000000000000000000000", "who": "ich", "name": "Sven", "dev_name": "Mac", "hp": "p0"]]
        for (i, p) in people.enumerated() {
            legs.append(["device": String(format: "d%031d", i + 1), "who": p.0.lowercased(), "name": p.0, "dev_name": p.1, "role": p.2, "hp": "p\(i + 1)"])
        }
        c.mockHorn(title: "Sommerfest", legs: CallRoom.legs(legs), mode: mode, path: UserDefaults.standard.string(forKey: "hkHornPath") ?? "tcp")
        ready = true
    }
}

extension CallManager {
    /// nur fuer Bilder: Zustand eines laufenden Knoten-Anrufs ohne Netz und ohne Medien
    func mockHorn(title t: String, legs: [CallRoom.Leg], mode: String, path: String) {
        reset()
        id = String(repeating: "a", count: 32); kind = "group"; topo = "horn"; title = t; peerName = t; media = "video"
        roster.apply(legs, rv: 1)
        ready = true; inRoom = true; connected = true; connectedAt = Date().addingTimeInterval(-754); phase = .active
        verified = true; limit = 12; mic = true; cam = false; callHost = true
        let H = HornSession(nil)
        H.path = path; H.node = "horn-1.example.org"; H.quality = path == "turn" ? .mid : .good; H.rtt = path == "udp" ? 28 : 41
        let r = HornE2E.runeSeal(callId: id ?? "", signPks: legs.map { Data($0.device.utf8) })
        H.runes = r.runes; H.runeNames = r.idx.map { HornE2E.runeNames[$0] }
        let others = legs.dropFirst()
        let moving = Set(others.prefix(hornMobile ? 3 : 8).map(\.device))
        let speaker = others.dropFirst(2).first?.device
        for (k, x) in others.enumerated() {
            H.st[x.device] = HornLogic.PeerState(mic: k % 4 != 3, cam: moving.contains(x.device), screen: mode == "share" && k == 1)
            H.speaking = speaker.map { [$0] } ?? []
        }
        H.view = mode == "speaker" ? "speaker" : "grid"
        H.spk = speaker
        let lay = HornLogic.tiles(others.map(\.device), moving: moving, speaker: speaker, view: H.view)
        H.tiles = lay.list.compactMap { x in
            guard let l = roster.leg(x.dev) else { return nil }
            let s = H.st[x.dev]!
            return HornTile(id: x.dev, name: l.name, devName: l.devName, avatar: nil, video: nil, moving: x.moving, big: x.big, mic: s.mic, cam: s.cam,
                            speaking: H.speaking.contains(x.dev), screen: s.screen, wait: false)
        }
        if mode == "share", let d = others.dropFirst().first { H.share = HornShare(dev: d.device, name: d.name, track: nil) }
        H.meDevice = legs.first?.device
        horn = H
        hornPeople()
        H.plist = mode == "people"
        H.rpop = mode == "runes"
    }
}
#endif
