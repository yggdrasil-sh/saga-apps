#if DEBUG
import SwiftUI

// Nur fuer Bilder (Debug-Build, ohne Server, ohne Kamera/Mikrofon): Gruppenanruf ueber einen Knoten mit erfundenem
// Zustand. Start: -hkHornPreview grid|speaker|share|people|runes [-hkHornDark YES] [-hkHornPath udp|tcp|turn] [-hkLandscape YES]
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
        if mode.hasPrefix("meet-") {                                         // Meeting-Ansicht mit Beispiel-Anruf
            c.mockMeet(String(mode.dropFirst(5)))
            #if os(iOS)
            if UserDefaults.standard.bool(forKey: "hkLandscape"), let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { _ in }
            }
            #endif
            ready = true
            return
        }
        let people: [(String, String, String)] = [("Bea", "iPhone", "member"), ("Tom", "MacBook", "member"), ("Ana", "iPad", "member"), ("Kai", "Pixel", "member"),
                                                  ("Lena", "Firefox", "member"), ("Mo", "iPhone", "member"), ("Ida", "Chrome", "member"), ("Jon", "Galaxy", "member"),
                                                  ("Eva", "Safari", "member"), ("Ole", "iPhone", "member"), ("Uma", "Mac mini", "member")]
        var legs: [[String: Any]] = [["device": "me000000000000000000000000000000", "who": "ich", "name": "Max", "dev_name": "Mac", "hp": "p0"]]
        for (i, p) in people.enumerated() {
            legs.append(["device": String(format: "d%031d", i + 1), "who": p.0.lowercased(), "name": p.0, "dev_name": p.1, "role": p.2, "hp": "p\(i + 1)"])
        }
        c.mockHorn(title: "Sommerfest", legs: CallRoom.legs(legs), mode: mode, path: UserDefaults.standard.string(forKey: "hkHornPath") ?? "tcp")
        #if os(iOS)
        // -hkLandscape YES: quer starten (Bilder vom iPhone-Querformat ohne Simulator-Bedienung)
        if UserDefaults.standard.bool(forKey: "hkLandscape"), let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { _ in }
        }
        #endif
        ready = true
    }
}

extension CallManager {
    /// nur fuer Bilder (-hkHornPreview meet-duo|present|present-group|gallery|speaker|pages): Meeting-Ansicht mit
    /// erfundenen Geraeten ohne Netz und ohne Medien (bewegte Bilder als Farbflaechen). present = Labor-Befund: Anna mit
    /// Mac (Kamera), iPhone (Begleitgeraet ohne Bild) und Android (Begleitgeraet teilt den Bildschirm)
    func mockMeet(_ m: String) {
        reset()
        id = String(repeating: "b", count: 32); media = "video"; phase = .active; connected = true; ready = true; inRoom = true
        connectedAt = Date().addingTimeInterval(-421); verified = true; mic = true; cam = true; limit = 12
        func d(_ dev: String, _ who: String, _ name: String, _ dn: String, comp: Bool = false, mic: Bool = true, cam: Bool = false, screen: Bool = false) -> Meeting.Device {
            Meeting.Device(device: dev, who: who, name: name, devName: dn, companion: comp, mic: mic, cam: cam, screen: screen, screenSince: screen ? 10 : 0)
        }
        let me = Meeting.Device(device: "me-dev", who: "me", name: "Max", devName: "iPhone", own: true, here: true, mic: true, cam: true)
        var list = [me]
        let names = ["Anna", "Ben", "Cem", "Dora", "Emil", "Fiona", "Gustav", "Hanna", "Ida", "Jonas", "Kira"]
        switch m {
        case "duo":
            kind = "dm"; peerName = "Anna"
            list.append(d("a1", "anna", "Anna", "MacBook", cam: true))
            list.append(d("a2", "anna", "Anna", "iPhone", comp: true, mic: false))
        case "present":
            kind = "dm"; peerName = "Anna"
            list.append(d("a1", "anna", "Anna", "MacBook", cam: true))
            list.append(d("a2", "anna", "Anna", "iPhone", comp: true, mic: false))
            list.append(d("a3", "anna", "Anna", "Android", comp: true, mic: false, screen: true))
        case "present-group":
            kind = "group"; peerName = "Projekt Nordlicht"
            list.append(d("a1", "anna", "Anna", "MacBook", cam: true, screen: true))
            for (i, n) in names.dropFirst().prefix(3).enumerated() { list.append(d("p\(i)", n.lowercased(), n, "iPhone", mic: i != 1, cam: i != 2)) }
        case "speaker":
            kind = "group"; peerName = "Projekt Nordlicht"
            for (i, n) in names.prefix(7).enumerated() { list.append(d("p\(i)", n.lowercased(), n, "Mac", mic: i != 3 && i != 5, cam: i % 4 != 3)) }
            meet.stage = "cem"; meet.speaking = ["cem"]
        case "pages":
            kind = "group"; peerName = "Sommerfest"
            for (i, n) in names.enumerated() { list.append(d("p\(i)", n.lowercased(), n, "iPad", mic: i % 3 != 2, cam: i % 2 == 0)) }
            meet.speaking = ["ben"]
        default:                                                             // gallery
            kind = "group"; peerName = "Projekt Nordlicht"
            for (i, n) in names.prefix(4).enumerated() { list.append(d("p\(i)", n.lowercased(), n, i == 1 ? "Pixel" : "Mac", mic: i != 3, cam: i != 2)) }
            meet.speaking = ["ben"]
        }
        UserDefaults.standard.set(m == "gallery" || m == "pages" ? "gallery" : "auto", forKey: "call.layout")
        meet.demo = list
    }

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
        if let o = hornSelfTile(H.meDevice ?? "") { H.tiles.append(o) }   // eigene Kachel wie im echten Anruf
        hornPeople()
        H.plist = mode == "people" || mode == "remove"
        H.rpop = mode == "runes"
        if mode == "path" { Task { try? await Task.sleep(for: .seconds(1.5)); H.ppop = true } }   // Feld erst, wenn die Ansicht steht
        if mode == "remove" { H.rmSure = others.dropFirst().first?.device }      // zweite Stufe "Wirklich entfernen?"
    }
}
#endif
