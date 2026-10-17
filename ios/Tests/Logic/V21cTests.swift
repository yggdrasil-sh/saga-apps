import Foundation

// 2.1-c: Chat-Bilder (files[]-Eintrag mit w/h/th, Groessen, Kennungen der Sende-Anfrage, Platten-LRU) und
// Bildblock kopieren/ausschneiden (Inhalt der Zwischenablage, gleiche/andere Seite, neue Block-IDs)
func v21cTests() {
    // --- files[]-Eintrag (Format chat-thumbs-spec.md)
    let j = #"{"v":1,"type":"text","text":"","files":[{"id":"OrigFileId0123456789abcd","k":"a2V5","n":"foto.jpg","m":"image/jpeg","s":3145728,"w":4000,"h":3000,"th":{"id":"ThumbFileId0123456789abc","k":"dGg=","m":"image/jpeg","w":320,"h":240,"s":694}}]}"#
    let c = ChatContent(json: Data(j.utf8))
    let f = c?.files.first
    check(f?.w == 4000 && f?.h == 3000 && f?.thumb?.id == "ThumbFileId0123456789abc" && f?.thumb?.w == 320 && f?.thumb?.h == 240 && f?.thumb?.size == 694 && f?.thumb?.key == "dGg=", "files[] mit w/h/th gelesen")
    if let c { check(ChatContent(json: c.json()) == c, "files[] mit th: Rundreise") }
    let old = ChatContent(json: Data(#"{"v":1,"type":"text","text":"x","files":[{"id":"A","k":"a2V5","n":"b.png","m":"image/png","s":5}]}"#.utf8))
    check(old?.files.first?.thumb == nil && old?.files.first?.w == nil && old?.files.first?.isImage == true, "alte Nachricht ohne th")
    check(old.map { !String(decoding: $0.json(), as: UTF8.self).contains("\"th\"") && !String(decoding: $0.json(), as: UTF8.self).contains("\"w\"") } == true, "ohne th/w/h nichts Neues im JSON")
    let bad = ChatContent(json: Data(#"{"files":[{"id":"A","k":"a2V5","n":"b","m":"image/png","s":5,"w":0,"h":10,"th":{"id":"T"}}]}"#.utf8))
    check(bad?.files.first?.thumb == nil && bad?.files.first?.w == nil, "th ohne Schluessel / w=0 ignoriert")
    check(ChatFileRef(["id": "A", "k": "x", "m": "image/heif"])?.isImage == true, "HEIF ist ein Bild")

    // --- Groessen
    typealias M = ChatMediaLogic
    check(M.thumbSize(w: 4000, h: 3000) == (320, 240) && M.thumbSize(w: 3024, h: 4032) == (240, 320), "Vorschaubild 320 px")
    check(M.thumbSize(w: 200, h: 100) == (200, 100) && M.thumbSize(w: 320, h: 320) == (320, 320), "nie hochskalieren")
    check(M.thumbSize(w: 10000, h: 10) == (320, 1) && M.thumbSize(w: 0, h: 5) == (0, 0), "Streifen mind. 1 px, 0 bleibt 0")
    check(M.displaySize(w: nil, h: nil) == (280, 210) && M.displaySize(w: 4000, h: 3000) == (280, 210) && M.displaySize(w: 3000, h: 4000) == (210, 280), "Anzeigegroesse aus w/h")
    check(M.displaySize(w: 1000, h: 1000) == (280, 280) && M.displaySize(w: 9000, h: 100) == (280, 93) && M.displaySize(w: 100, h: 9000) == (93, 280), "extreme Formate 3:1")
    check(M.canThumb("image/jpeg") && M.canThumb("IMAGE/HEIC") && !M.canThumb("image/svg+xml") && !M.canThumb("application/pdf"), "Vorschaubild nur fuer Bilder")

    // --- Vorschaubild ohne Metadaten (EXIF/XMP, ICC, IPTC, Kommentar weg; JFIF, Tabellen, Bilddaten bleiben)
    let app0: [UInt8] = [0xFF, 0xE0, 0x00, 0x06, 0x4A, 0x46, 0x49, 0x46]
    let exif: [UInt8] = [0xFF, 0xE1, 0x00, 0x08, 0x45, 0x78, 0x69, 0x66, 0x00, 0x00]
    let icc: [UInt8] = [0xFF, 0xE2, 0x00, 0x04, 0x01, 0x02], com: [UInt8] = [0xFF, 0xFE, 0x00, 0x05, 0x61, 0x62, 0x63]
    let dqt: [UInt8] = [0xFF, 0xDB, 0x00, 0x04, 0x07, 0x08], sos: [UInt8] = [0xFF, 0xDA, 0x00, 0x02, 0x11, 0xFF, 0x00, 0x22, 0xFF, 0xD9]
    let raw = Data([0xFF, 0xD8] + app0 + exif + icc + com + dqt + sos)
    check(M.stripJPEG(raw) == Data([0xFF, 0xD8] + app0 + dqt + sos), "JPEG ohne EXIF/ICC/Kommentar")
    check(M.stripJPEG(Data([0x89, 0x50, 0x4E, 0x47])) == nil && M.stripJPEG(Data([0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x40])) == nil, "kein/kaputtes JPEG -> nil")

    // --- Kennungen der Sende-Anfrage: Original + Vorschaubild, Grenze aelterer Server
    func ref(_ i: Int, th: Bool = true) -> ChatFileRef {
        ChatFileRef(id: "o\(i)", key: "k", name: "n", mime: "image/jpeg", size: 1, w: 4, h: 3, thumb: th ? ChatThumbRef(id: "t\(i)", key: "k", w: 4, h: 3, size: 1) : nil)
    }
    let s2 = M.sendIDs([ref(1), ref(2, th: false)], maxIDs: 20)
    check(s2.ids == ["o1", "t1", "o2"] && s2.refs[0].thumb != nil, "send_files = [orig, thumb, ...]")
    let s6 = M.sendIDs((1...6).map { ref($0) }, maxIDs: 10)
    check(s6.ids.count == 10 && s6.refs[3].thumb != nil && s6.refs[4].thumb == nil && s6.refs[5].thumb == nil && s6.ids.last == "o6", "alter Server (10): letzte Bilder ohne th")
    check(M.sendIDs((1...10).map { ref($0) }, maxIDs: 10).ids.count == 10 && M.sendIDs((1...10).map { ref($0) }, maxIDs: 20).ids.count == 20, "10 Bilder: 10 bzw. 20 Kennungen")

    // --- Platten-Cache LRU
    let now = Date()
    let es = (0..<5).map { M.DiskEntry(name: "f\($0)", size: 60, used: now.addingTimeInterval(Double($0))) }
    check(M.evict(es, limit: 400, target: 300).isEmpty, "unter der Grenze: nichts loeschen")
    check(M.evict(es, limit: 250, target: 200) == ["f0", "f1"], "ueber der Grenze: die aeltesten bis zum Ziel")
    check(M.cacheName("AbC-_09") == "AbC-_09" && M.cacheName("x", local: true) == "x.t" && M.cacheName("../etc") == nil && M.cacheName("a/b") == nil && M.cacheName("") == nil, "Cache-Namen nur aus Kennungen")

    // --- Bildblock kopieren/ausschneiden
    typealias B = BlockClipLogic
    let img: [String: Any] = ["id": "b1", "type": "image", "data": ["file": ["url": "/files/0123456789abcdef01234567"], "caption": "Garten"]]
    let pd = B.encode(server: "https://s.example/", note: 7, blocks: [img], mime: "image/png")
    let p = B.decode(pd)
    check(p?.note == 7 && p?.server == "https://s.example" && p?.mime == "image/png" && p.map { B.caption($0.blocks[0]) } == "Garten", "Block in die Zwischenablage und zurueck")
    if let p {
        check(B.plan(p, server: "https://S.example", note: 7) == .reuse, "gleiche Seite: Block uebernehmen")
        check(B.plan(p, server: "https://s.example", note: 8) == .upload && B.plan(p, server: "https://t.example", note: 7) == .upload, "andere Seite/anderer Server: neu hochladen")
    }
    check(B.decode(Data("kein json".utf8)) == nil && B.decode(Data(#"{"v":2,"server":"x","note":1,"blocks":[{"type":"image"}]}"#.utf8)) == nil, "fremder Inhalt abgelehnt")
    check(B.decode(Data(#"{"v":1,"server":"x","note":1,"blocks":[]}"#.utf8)) == nil && B.decode(Data(#"{"v":1,"server":"x","note":1,"blocks":[{"id":"a"}]}"#.utf8)) == nil, "leer / ohne Typ abgelehnt")
    check(B.encode(server: "x", note: 1, blocks: Array(repeating: img, count: 51), mime: nil) == nil, "hoechstens 50 Bloecke")
    check(B.isImage(img) && !B.isImage(["type": "image", "data": ["file": ["url": "https://fremd.example/x.png"]]]) && !B.isImage(["type": "paragraph"]), "nur Saga-Bildbloecke")
    var n = 0
    let tog: [String: Any] = ["id": "t", "type": "toggle", "data": ["blocks": [img], "cols": [["blocks": [img]]]]]
    let r = B.renewed(tog, newID: { n += 1; return "n\(n)" })
    let inner = ((r["data"] as? [String: Any])?["blocks"] as? [[String: Any]])?.first?["id"] as? String
    let col = (((r["data"] as? [String: Any])?["cols"] as? [[String: Any]])?.first?["blocks"] as? [[String: Any]])?.first?["id"] as? String
    check(r["id"] as? String == "n1" && inner == "n2" && col == "n3", "neue Block-IDs auch innen")
    check(B.uti("image/jpeg") == "public.jpeg" && B.uti("image/png") == "public.png" && B.uti("image/heic") == nil, "Zwischenablage-Typen")
}
