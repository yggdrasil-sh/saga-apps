import Foundation

// Meeting-Ansicht (Saga 2.5, MEETING-SPEC): reine Logik ohne WebRTC/UI (mit swiftc pruefbar), gleiche Regeln und
// Testvektoren wie Web/Android (ios/Tests/vectors/meeting.json). Gilt fuer 1:1, Mesh-Gruppen und Anrufe ueber den Knoten.
// - Personen ueber `who` zusammenfassen: eine Personenkachel je Person (beste Kamera, sonst Profilbild), je teilendem Geraet
//   eine Bildschirmkachel (contain, nie Kamera-Bild-im-Bild), zweite Kamera-Kachel nur fuer ein Begleitgeraet MIT Bild
// - andere eigene Geraete nie als Personen- oder Kamerakachel (Chip im Kopf); teilt ein anderes eigenes Geraet den
//   Bildschirm, kommt er als Bildschirmkachel mit Geraetename (§4.2, beide Richtungen); den Bildschirm dieses Geraets nie
// - Layout Auto/Galerie/Sprecher/Praesentation, Galerie-Raster mit Seiten, Streifen-Lage, Sprecher-Hysterese

enum Meeting {

    // MARK: Eingang: ein Geraet im Anruf

    struct Device: Equatable {
        var device: String
        /// Person (Roster `who`)
        var who: String
        var name: String
        var devName: String = ""
        var avatar: String? = nil
        var companion = false
        /// Geraet der eigenen Person
        var own = false
        /// dieses Geraet
        var here = false
        var mic = true
        var cam = false
        var screen = false
        /// Bildschirm seit (Reihenfolge mehrerer Freigaben: die zuletzt gestartete kommt auf die Buehne)
        var screenSince = 0.0
        /// Bildschirm ueber den Knoten nicht lieferbar (nur eine Freigabe je Knoten-Platz)
        var screenMissing = false
    }

    enum Kind: String, Equatable { case person, camera, screen }

    struct Tile: Equatable, Identifiable {
        /// who (Person), who|dev:cam (zweite Kamera), who|dev:scr (Bildschirm)
        var id: String
        var kind: Kind
        var who: String
        /// Geraet, dessen Bild die Kachel zeigt (Personenkachel ohne Bild: Hauptgeraet)
        var dev: String
        var name: String
        /// Geraetename in Klammern ("Bildschirm von Anna (Android)"), leer = keiner
        var devLabel: String = ""
        var avatar: String? = nil
        var me = false
        var mic = true
        /// zeigt bewegtes Bild (Kamera bzw. Bildschirm)
        var video = false
        var screenSince = 0.0
        var screenMissing = false
        /// alle Geraete der Person (Pegel, Sprechring)
        var devices: [String] = []
    }

    struct Built: Equatable {
        var tiles: [Tile]
        /// eigene andere Geraete (Chip "iPhone, Galaxy verbunden")
        var ownOthers: [Device]
        /// dieses Geraet teilt (rote Leiste, eigener Bildschirm nicht auf der Buehne)
        var selfSharing: Bool
        /// Zahl der Personen (Kopf)
        var people: Int
    }

    /// Kacheln aus den Geraeten wie meeting.js meetingTiles: Bildschirme (aelteste Freigabe zuerst), Personen in Listenfolge
    /// (zweite Kamera direkt hinter der Person), die eigene Kachel zuletzt. Eigene Person: nur "Du" (Kamera dieses Geraets)
    static func tiles(_ devs: [Device]) -> Built {
        var order: [String] = [], by: [String: [Device]] = [:]
        for d in devs where !d.who.isEmpty {
            if by[d.who] == nil { order.append(d.who) }
            by[d.who, default: []].append(d)
        }
        var persons: [Tile] = [], screens: [Tile] = [], ownOthers: [Device] = [], mine: Tile?
        var selfSharing = false
        for who in order {
            let list = by[who] ?? []
            let mic = list.contains { $0.mic }
            if list.contains(where: { $0.own || $0.here }) {
                let here = list.first { $0.here } ?? list.first!
                ownOthers += list.filter { $0.device != here.device }
                selfSharing = here.screen
                mine = Tile(id: who, kind: .person, who: who, dev: here.device, name: here.name, avatar: here.avatar, me: true,
                            mic: here.mic, video: here.cam, devices: [here.device])
                // §4.2 (Bugs-DB 2160): Bildschirm eines ANDEREN eigenen Geraets als Bildschirmkachel mit Geraetename -
                // Begleitgeraet -> Hauptgeraet und Hauptgeraet -> Begleitgeraet, im Mesh wie ueber den Knoten. Den
                // Bildschirm dieses Geraets nie (die eigene Freigabe zeigt die rote Leiste)
                for s in list where s.screen && s.device != here.device {
                    screens.append(Tile(id: who + "|" + s.device + ":scr", kind: .screen, who: who, dev: s.device, name: here.name,
                                        devLabel: s.devName, avatar: here.avatar, mic: false, video: !s.screenMissing,
                                        screenSince: s.screenSince, screenMissing: s.screenMissing, devices: [s.device]))
                }
                continue
            }
            let main = list.first { !$0.companion } ?? list[0]
            // Kameras: Hauptgeraet zuerst, dann Begleitgeraete (Reihenfolge der Liste)
            let cams = list.filter { $0.cam && $0.device == main.device } + list.filter { $0.cam && $0.device != main.device }
            let src = cams.first ?? main
            persons.append(Tile(id: who, kind: .person, who: who, dev: src.device, name: main.name, avatar: main.avatar ?? list.compactMap(\.avatar).first,
                                mic: mic, video: src.cam, devices: list.map(\.device)))
            for c in cams.dropFirst() {
                persons.append(Tile(id: who + "|" + c.device + ":cam", kind: .camera, who: who, dev: c.device, name: main.name,
                                    devLabel: c.devName, avatar: main.avatar, mic: mic, video: true, devices: list.map(\.device)))
            }
            for s in list where s.screen {
                screens.append(Tile(id: who + "|" + s.device + ":scr", kind: .screen, who: who, dev: s.device, name: main.name,
                                    devLabel: list.count > 1 ? s.devName : "", avatar: main.avatar, mic: mic, video: !s.screenMissing,
                                    screenSince: s.screenSince, screenMissing: s.screenMissing, devices: list.map(\.device)))
            }
        }
        // Bildschirme: aelteste Freigabe zuerst (stabil wie Array.sort im Web)
        let scr = screens.enumerated().sorted { $0.element.screenSince != $1.element.screenSince ? $0.element.screenSince < $1.element.screenSince : $0.offset < $1.offset }.map(\.element)
        return Built(tiles: scr + persons + (mine.map { [$0] } ?? []), ownOthers: ownOthers, selfSharing: selfSharing, people: order.count)
    }
    /// Neueste Freigabe (Buehne der Praesentation, Hinweis in der Galerie); bei gleicher Zeit die spaetere in der Liste
    static func newestScreen(_ tiles: [Tile]) -> Tile? {
        tiles.filter { $0.kind == .screen }.reduce(nil) { a, t in a == nil || t.screenSince >= a!.screenSince ? t : a }
    }

    // MARK: Layout

    enum Layout: String, CaseIterable { case auto, gallery, speaker, presentation }
    enum Mode: String, Equatable { case duo, gallery, speaker, presentation }

    /// Gewaehltes Layout -> angezeigtes. tiles = sichtbare Kacheln (Personen, zweite Kameras, eigene) ohne Bildschirme,
    /// screens = fremde Bildschirme. reduceMotion: Auto bleibt bis 8 Kacheln Galerie (kein Buehnenwechsel durch Sprecher)
    /// Wie meeting.js pickLayout (Bugs-DB 2161): gewaehlte Galerie/Sprecher gilt ab zwei Kacheln (1:1 mit Galerie = Galerie
    /// mit zwei Kacheln), 1:1 (duo) nur in Auto bzw. wenn ausser dem eigenen Bild nichts da ist
    static func mode(_ pref: Layout, tiles n: Int, screens: Int, reduceMotion: Bool = false) -> Mode {
        switch pref {
        case .gallery: return n + screens <= 1 ? .duo : .gallery
        case .speaker: return n + screens <= 1 ? .duo : .speaker
        case .presentation: return screens > 0 ? .presentation : n <= 1 ? .duo : .speaker
        case .auto:
            if screens > 0 { return .presentation }
            if n <= 2 { return .duo }
            if n <= 4 { return .gallery }
            if reduceMotion && n <= 8 { return .gallery }
            return .speaker
        }
    }
    /// Layout-Knopf zeigen? Nicht im 1:1 ohne Teilen und ohne zweite Kamera-Kachel
    static func layoutButton(tiles n: Int, screens: Int) -> Bool { screens > 0 || n > 2 }
    /// Wert aus dem Speicher (auch die alte Knoten-Einstellung saga-horn-view: grid -> gallery)
    static func layout(_ raw: String?, legacy: String? = nil) -> Layout {
        if let r = raw, let l = Layout(rawValue: r) { return l }
        if legacy == "grid" { return .gallery }
        if legacy == "speaker" { return .speaker }
        return .auto
    }

    /// Eigenes Bild schwebend (sonst eigene Kachel im Raster bzw. zuletzt im Streifen). people = Personenkacheln (mit der
    /// eigenen). Sprecher mit hoechstens zwei Personen schwebt wie im Web (meeting.js arrange, Bugs-DB 2161)
    static func selfFloats(_ mode: Mode, people: Int) -> Bool {
        switch mode {
        case .duo: return true
        case .presentation, .speaker: return people <= 2
        case .gallery: return false
        }
    }

    /// Wie die Ansicht: Praesentation mit Streifen rechts - eigenes Bild nie ueber dem Bildschirm, sondern im Streifen
    /// (Streifen unten, Handy hochkant: schwebt bis 2 Personen). meeting.js arrange entspricht rightStrip = true
    static func floats(_ mode: Mode, people: Int, rightStrip: Bool) -> Bool {
        selfFloats(mode, people: people) && !(mode == .presentation && rightStrip)
    }

    // MARK: Buehne

    /// Was auf die Buehne kommt: Pin vor allem; Praesentation: neueste Freigabe; Sprecher: Sprecher bzw. erste andere Person
    static func stage(_ mode: Mode, tiles: [Tile], pin: String?, speaker: String?) -> String? {
        if let p = pin, tiles.contains(where: { $0.id == p }) , mode != .gallery { return p }
        switch mode {
        case .presentation:
            return newestScreen(tiles)?.id ?? stage(.speaker, tiles: tiles, pin: nil, speaker: speaker)
        case .speaker, .duo:
            if let s = speaker, let t = tiles.first(where: { $0.kind == .person && $0.who == s && !$0.me }) { return t.id }
            return tiles.first { $0.kind != .screen && !$0.me }?.id ?? tiles.first { $0.kind == .screen }?.id
        case .gallery: return nil
        }
    }
    /// Streifen: andere Bildschirme vorn (die neueste zuerst), dann Personen, eigene Kachel zuletzt (wenn sie nicht schwebt)
    static func strip(_ tiles: [Tile], stage: String?, selfFloats: Bool) -> [Tile] {
        let rest = tiles.filter { $0.id != stage && !(selfFloats && $0.me) }
        let scr = rest.enumerated().filter { $0.element.kind == .screen }
            .sorted { $0.element.screenSince != $1.element.screenSince ? $0.element.screenSince > $1.element.screenSince : $0.offset < $1.offset }.map(\.element)
        return scr + rest.filter { $0.kind != .screen && !$0.me } + rest.filter(\.me)
    }

    // MARK: Galerie

    struct Grid: Equatable { var cols: Int; var perPage: Int; var videoMax: Int }
    /// Spalten (n = Kacheln dieser Seite), Kacheln je Seite und davon hoechstens mit Video (Tabelle §2.2); Mindestmass
    /// Kachel 112x63 - passt es nicht, weniger je Seite
    static func grid(n: Int, width w: Double, height h: Double) -> Grid {
        var g: Grid
        if w < 600 && h >= w { g = Grid(cols: n <= 2 ? 1 : 2, perPage: 6, videoMax: 4) }
        else if h < 500 && w > h { g = Grid(cols: n <= 2 ? 2 : 3, perPage: 6, videoMax: 4) }
        else if w < 1024 { g = Grid(cols: n <= 4 ? 2 : 3, perPage: 9, videoMax: 6) }
        else if w < 1920 { g = Grid(cols: n <= 4 ? 2 : n <= 9 ? 3 : 4, perPage: 16, videoMax: 9) }
        else { g = Grid(cols: n <= 4 ? 2 : n <= 9 ? 3 : n <= 16 ? 4 : 5, perPage: 25, videoMax: 16) }
        if w > 0 && h > 0 {
            let sp = 8.0
            let maxCols = max(1, Int((w + sp) / (112 + sp)))
            if g.cols > maxCols { g.cols = maxCols }
            let maxRows = max(1, Int((h + sp) / (63 + sp)))
            g.perPage = max(1, min(g.perPage, g.cols * maxRows))
        }
        // wie meeting.js: nie mehr Spalten als Kacheln (nach der Seitengroesse - eine Kachel aendert sie nicht)
        g.cols = min(g.cols, max(1, n))
        return g
    }
    /// Seitenzahl fuer count Kacheln
    static func pages(_ count: Int, perPage: Int) -> Int { count <= 0 ? 1 : (count + max(1, perPage) - 1) / max(1, perPage) }
    /// Kacheln einer Seite (Seite wird in den gueltigen Bereich gezogen)
    static func page<T>(_ list: [T], page p: Int, perPage: Int) -> [T] {
        let n = pages(list.count, perPage: perPage), q = min(max(0, p), n - 1), k = max(1, perPage)
        return Array(list.dropFirst(q * k).prefix(k))
    }
    /// Reihenfolge der Galerie wie meeting.js galleryOrder: Pin -> Bildschirme -> Personen mit Video -> Sprecher der letzten
    /// 30 s -> Rest alphabetisch; sonst Listenfolge; eigene zuletzt (Bugs-DB 2161: Bildschirme vor den Video-Kacheln)
    static func galleryOrder(_ tiles: [Tile], pin: String?, recent: Set<String>) -> [Tile] {
        func key(_ t: Tile) -> Int { t.id == pin ? 0 : t.kind == .screen ? 1 : t.video ? 2 : recent.contains(t.who) ? 3 : 4 }
        let others = tiles.filter { !$0.me }.enumerated().sorted {
            let a = key($0.element), b = key($1.element)
            if a != b { return a < b }
            if a == 4 {
                let c = $0.element.name.localizedCaseInsensitiveCompare($1.element.name)
                if c != .orderedSame { return c == .orderedAscending }
            }
            return $0.offset < $1.offset
        }.map(\.element)
        return others + tiles.filter(\.me)
    }

    // MARK: Kacheln ohne Video (Einstellung aus)

    /// Nachlauf: eine Person ohne Video bleibt waehrend des Sprechens und bis 5 s danach sichtbar (wie meeting.js NOVIDEO_TAIL)
    static let noVideoTail = 5.0
    /// Bleibt diese Kachel, wenn "Kacheln ohne Video zeigen" aus ist? Mit Video, die eigene, Bildschirme/zweite Kameras
    /// und wer in den letzten 5 s gesprochen hat (talked) - in allen Layouts, nicht nur in der Galerie (Bugs-DB 2161)
    static func keepNoVideo(_ t: Tile, talked: Set<String>) -> Bool {
        t.video || t.me || t.kind != .person || talked.contains(t.who)
    }
    /// Kacheln nach der Einstellung; show = Kacheln ohne Video zeigen
    static func visible(_ tiles: [Tile], show: Bool, talked: Set<String>) -> [Tile] {
        show ? tiles : tiles.filter { keepNoVideo($0, talked: talked) }
    }
    /// Stabile Galerie-Reihenfolge: neu sortieren nur, wenn sich Kacheln, Video oder Pin aendern - ein Sprecherwechsel
    /// allein laesst das Raster stehen (sonst springt es)
    struct Order: Equatable {
        private(set) var ids: [String] = []
        private var sig = ""
        mutating func update(_ tiles: [Tile], pin: String?, recent: Set<String>) -> [Tile] {
            let s = (pin ?? "-") + "#" + tiles.map { $0.id + ($0.video ? "+" : "-") }.sorted().joined(separator: ",")
            if s != sig {
                sig = s
                ids = galleryOrder(tiles, pin: pin, recent: recent).map(\.id)
            }
            let by = Dictionary(tiles.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            return ids.compactMap { by[$0] }
        }
    }
    /// Welche Kacheln Video zeigen duerfen (hoechstens max; Bildschirme zaehlen extra): Pin -> Sprecher -> zuletzt
    /// gesprochen -> Reihenfolge
    static func videoAllowed(_ tiles: [Tile], max m: Int, pin: String?, speaker: String?, recent: [String]) -> Set<String> {
        var out = Set<String>()
        for t in tiles where t.kind == .screen && t.video { out.insert(t.id) }
        var n = 0
        func take(_ t: Tile) { if n < m && t.video && t.kind != .screen && !out.contains(t.id) { out.insert(t.id); n += 1 } }
        if let p = pin, let t = tiles.first(where: { $0.id == p }) { take(t) }
        if let s = speaker { for t in tiles where t.who == s && t.kind == .person { take(t) } }
        for w in recent { for t in tiles where t.who == w && t.kind == .person { take(t) } }
        for t in tiles { take(t) }
        return out
    }

    // MARK: Streifen, Fensterklassen

    enum Side: String, Equatable { case right, bottom }
    struct StripPlace: Equatable { var side: Side; var size: Double }
    /// Lage und Breite/Hoehe des Streifens (§2.3/§2.4/§8)
    static func strip(width w: Double, height h: Double, presentation: Bool) -> StripPlace {
        if h < 500 && w > h { return StripPlace(side: .right, size: 120) }       // Handy quer
        // schmal bzw. hochkant (auch iPad hochkant ueber 900 pt): Streifen unten, die Buehne behaelt die volle Breite
        if w < 900 || h > w { return StripPlace(side: .bottom, size: w < 600 ? 84 : 96) }
        if w >= 1920 { return StripPlace(side: .right, size: 240) }
        // wie meeting.js stripSide: unter 1100 schmal, Praesentation bis 1440 schmal (der Bildschirm bekommt die Breite)
        if w < 1100 || (presentation && w < 1440) { return StripPlace(side: .right, size: 160) }
        return StripPlace(side: .right, size: 200)
    }
    /// Hoechstzahl Videos im Streifen (Handy 3, sonst 6)
    static func stripVideos(phone: Bool) -> Int { phone ? 3 : 6 }

    // MARK: Sprecher (Hysterese, §2.3)

    /// Pegel je Person alle ~330 ms. "spricht" = Pegel > 0,03 fuer >= 300 ms (+ 600 ms Nachlauf). Buehnenwechsel nur, wenn
    /// die neue Person >= 1,5 s am Stueck lauter ist als die aktuelle UND seit dem letzten Wechsel >= 3 s vergangen sind.
    /// Niemand spricht -> Buehne bleibt. Eigene Stimme bewegt die Buehne nie.
    struct Speakers: Equatable {
        static let threshold = 0.03, talkMin = 0.3, tail = 0.6, lead = 1.5, gap = 3.0, recentFor = 30.0
        private(set) var stage: String?
        private(set) var speaking = Set<String>()
        private(set) var switches = 0
        private var since: [String: Double] = [:]
        private var lastTalk: [String: Double] = [:]
        private var leadSince: [String: Double] = [:]
        private var switchedAt = -1e9
        /// Wer in den letzten s Sekunden gesprochen hat (Kacheln ohne Video: 5 s Nachlauf)
        func talked(within s: Double, now: Double) -> Set<String> {
            Set(lastTalk.filter { now - $0.value <= s + 1e-9 }.map(\.key))
        }
        /// Sprecher der letzten 30 s (neueste zuerst)
        func recent(now: Double) -> [String] {
            lastTalk.filter { now - $0.value <= Self.recentFor }.sorted { $0.value > $1.value }.map(\.key)
        }
        /// true = Buehne gewechselt
        @discardableResult
        mutating func update(_ levels: [String: Double], me: String?, now: Double) -> Bool {
            for (w, l) in levels {
                if l > Self.threshold { if since[w] == nil { since[w] = now } } else { since[w] = nil }
            }
            for w in Array(since.keys) where levels[w] == nil { since[w] = nil }
            var sp = Set<String>()
            for (w, t) in since where now - t >= Self.talkMin - 1e-9 { lastTalk[w] = now; sp.insert(w) }
            for (w, t) in lastTalk where now - t <= Self.tail { sp.insert(w) }
            speaking = sp
            // Kandidat: lautester Sprecher (nicht ich)
            let cands = levels.filter { $0.key != me && sp.contains($0.key) && since[$0.key] != nil }
            guard let best = cands.max(by: { $0.value != $1.value ? $0.value < $1.value : $0.key > $1.key }) else {
                leadSince = [:]; return false
            }
            guard let cur = stage else { stage = best.key; switchedAt = now; switches += 1; leadSince = [:]; return true }
            if best.key == cur { leadSince = [:]; return false }
            let curLevel = levels[cur] ?? 0
            if best.value > curLevel {
                let t0 = leadSince[best.key] ?? now
                leadSince = [best.key: t0]
                if now - t0 >= Self.lead - 1e-9 && now - switchedAt >= Self.gap - 1e-9 {
                    stage = best.key; switchedAt = now; switches += 1; leadSince = [:]
                    return true
                }
            } else { leadSince = [:] }
            return false
        }
        /// Person verlaesst den Anruf
        mutating func drop(_ who: String) {
            if stage == who { stage = nil }
            since[who] = nil; lastTalk[who] = nil; leadSince[who] = nil; speaking.remove(who)
        }
    }

    // MARK: Eigenes Bild: Groesse S/M/L

    enum SelfSize: String, CaseIterable { case s, m, l
        var factor: Double { self == .s ? 0.7 : self == .l ? 1.4 : 1.0 }
        var next: SelfSize { self == .s ? .m : self == .m ? .l : .s }
    }

    // MARK: Zoom (Bildschirm)

    static let zoomMin = 1.0, zoomMax = 4.0
    static func zoom(_ z: Double) -> Double { min(zoomMax, max(zoomMin, z.isFinite ? z : 1)) }
    /// Verschieben begrenzen: Inhalt (size*z) darf die Flaeche nicht freigeben
    static func pan(_ off: (x: Double, y: Double), zoom z: Double, size: (w: Double, h: Double)) -> (x: Double, y: Double) {
        let mx = max(0, (size.w * z - size.w) / 2), my = max(0, (size.h * z - size.h) / 2)
        return (min(mx, max(-mx, off.x)), min(my, max(-my, off.y)))
    }
}
