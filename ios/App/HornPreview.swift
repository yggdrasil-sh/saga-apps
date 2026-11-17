#if DEBUG
import SwiftUI

// Nur fuer Bilder (Debug-Build, ohne Server, ohne Kamera/Mikrofon): Gruppenanruf ueber einen Knoten mit erfundenem
// Zustand. Start: -hkHornPreview grid|speaker|share|people|runes|away|meet-away|dazu-… [-hkHornDark YES] [-hkHornPath udp|tcp|turn] [-hkLandscape YES]
// (away/meet-away: Teilnehmerliste eines kleinen Gruppenanrufs ueber Knoten bzw. direkt, offen - Bilder "Nicht dabei")
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
        let few: [(String, String, String)] = [("Eva Beispiel", "iPhone", "member"), ("Jonas Beispiel", "Pixel", "member")]
        let small = mode == "away" || mode.hasPrefix("dazu")               // 2.5 (L8/L9): Bilder "Person hinzufuegen"
        let pick = ["dazu-dm", "dazu-moved", "dazu-moved-ev", "dazu-dmpick"].contains(mode) ? Array(few.prefix(1)) : few
        for (i, p) in (small ? pick : people).enumerated() {
            legs.append(["device": String(format: "d%031d", i + 1), "who": p.0.lowercased(), "name": p.0, "dev_name": p.1, "role": p.2, "hp": "p\(i + 1)"])
        }
        c.mockHorn(title: small ? "Projekt Nordlicht" : "Sommerfest", legs: CallRoom.legs(legs), mode: mode, path: UserDefaults.standard.string(forKey: "hkHornPath") ?? "tcp")
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
        case "dazu-direct":                                                  // 2.5 (L9): direktes 1:1 - Knopf zeigt nur den Hinweis
            kind = "dm"; peerName = "Anna"
            list.append(d("a1", "anna", "Anna", "MacBook", cam: true))
            serverFeatures = [CallAdd.feature, CallAdd.featureMove]
            Task { [weak self] in try? await Task.sleep(for: .seconds(2)); self?.addTapped() }
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
        case "away":                                                         // kleiner Gruppenanruf, Teilnehmerliste offen
            kind = "group"; peerName = "Projekt Nordlicht"; cam = false
            list = [Meeting.Device(device: "me-dev", who: "me", name: "Max", devName: "iPhone", own: true, here: true, mic: true, cam: false)]
            list.append(d("e1", "eva", "Eva Beispiel", "MacBook"))
            meet.people = true
            invitesIn(Self.mockInvites())
        default:                                                             // gallery
            kind = "group"; peerName = "Projekt Nordlicht"
            for (i, n) in names.prefix(4).enumerated() { list.append(d("p\(i)", n.lowercased(), n, i == 1 ? "Pixel" : "Mac", mic: i != 3, cam: i != 2)) }
            meet.speaking = ["ben"]
        }
        UserDefaults.standard.set(m == "gallery" || m == "pages" ? "gallery" : "auto", forKey: "call.layout")
        meet.demo = list
    }

    /// nur fuer Bilder (away/meet-away): "Nicht dabei" wie vom Server - nicht angenommen, abgelehnt, klingelt, App zu alt
    static func mockInvites() -> [String: Any] {
        let now = Date().timeIntervalSince1970
        return ["rv": now, "now": now, "ring_s": 45, "invites_more": 0, "invites": [
            ["who": "anna", "name": "Anna Beispiel", "state": "missed", "since": now - 80],
            ["who": "bert", "name": "Bert Beispiel", "state": "declined", "since": now - 30],
            ["who": "cora", "name": "Cora Beispiel", "state": "ringing", "since": now - 3],
            ["who": "dora", "name": "Dora Beispiel", "state": "old", "since": now - 200]]]
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
        H.plist = mode == "people" || mode == "remove" || mode == "away"
        if mode == "away" { invitesIn(Self.mockInvites()) }
        H.rpop = mode == "runes"
        if mode == "path" { Task { try? await Task.sleep(for: .seconds(1.5)); H.ppop = true } }   // Feld erst, wenn die Ansicht steht
        if mode == "remove" { H.rmSure = others.dropFirst().first?.device }      // zweite Stufe "Wirklich entfernen?"
        if mode.hasPrefix("dazu") { mockAdd(mode, H) }
    }

    /// nur fuer Bilder (2.5 L8/L9, -hkHornPreview dazu-list|dazu-pick|dazu-page|dazu-full|dazu-dm|dazu-dmpick|dazu-moved|dazu-moved-ev,
    /// meet-dazu-direct):
    /// Teilnehmerliste mit "Person hinzufuegen", Auswahl mit erfundenen Kontakten, Seiten-Rueckfrage, voll; 1:1 ueber
    /// Knoten vor und nach dem Umzug (moveIn mit erfundenem Anruf-Objekt - derselbe Weg wie die /add-Antwort)
    func mockAdd(_ mode: String, _ H: HornSession) {
        serverFeatures = [CallAdd.feature, CallAdd.featureMove]
        store?.chat.contacts = [
            ChatContact(ref: "~jonasbeispiel000", name: "Jonas Beispiel", sub: "jonas"),
            ChatContact(ref: "~lenabeispiel0000", name: "Lena Beispiel", sub: "lena"),
            ChatContact(ref: "~oleeispiel000000", name: "Ole Beispiel", sub: "ole", ready: false),
            ChatContact(ref: "~ugeispiel@peer.example", name: "Uma Beispiel", host: "peer.example", remote: true)]
        let now = Date().timeIntervalSince1970
        H.plist = true
        if ["dazu-dm", "dazu-dmpick", "dazu-moved", "dazu-moved-ev"].contains(mode) {
            dmRoom = true; title = ""; peerName = "Eva Beispiel"; callHost = false
            addCaps = CallAdd.Caps(canMove: true)
            invites = Invites.List()
            addOpen = mode == "dazu-dmpick"
            if mode == "dazu-moved-ev" {                                    // Weg ueber das Ereignis call.move (ohne Server: Abfrage scheitert)
                H.plist = false
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(2.5))
                    guard let self, let cid = self.id else { return }
                    self.live("call.move", ["call_id": cid, "from_chat": 12, "chat_id": 37, "kind": "group", "media": "video", "by": "eva"])
                }
            }
            if mode == "dazu-moved" {
                H.plist = false
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(2.5))
                    guard let self else { return }
                    let legs: [[String: Any]] = [["device": "me000000000000000000000000000000", "who": "ich", "name": "Max", "dev_name": "Mac", "hp": "p0"],
                                                 ["device": String(format: "d%031d", 1), "who": "eva beispiel", "name": "Eva Beispiel", "dev_name": "iPhone", "hp": "p1"]]
                    let c: [String: Any] = ["id": self.id ?? "", "chat_id": 37, "kind": "group", "media": "video", "topo": "horn", "limit": 12,
                                            "legs": legs, "host": true, "can_add": true, "rv": now + 5, "now": now + 5, "ring_s": 45,
                                            "from": ["chat": "Eva Beispiel, Jonas Beispiel"],
                                            "invites": [["who": "jonas", "ref": "~jonasbeispiel000", "name": "Jonas Beispiel", "state": "ringing", "since": now + 4]]]
                    self.moveIn(CallAdd.Moved(from: 12, to: 37), call: c)
                    try? await Task.sleep(for: .seconds(1))
                    H.plist = true
                }
            }
            return
        }
        addCaps = CallAdd.Caps(canAdd: true, addPage: mode == "dazu-page" ? "Projektplan 2027" : nil)
        invitesIn(Self.mockInvites())
        addOpen = mode != "dazu-list"
        if mode == "dazu-full" { limit = roster.legs.count }
        if mode == "dazu-page" {
            addAsk = CallAddAsk(text: "Lena Beispiel bekommt Zugriff auf die Seite „Projektplan 2027“.",
                                person: CallAdd.Person(ref: "~lenabeispiel0000", name: "Lena Beispiel"))
        }
    }
}
#endif
