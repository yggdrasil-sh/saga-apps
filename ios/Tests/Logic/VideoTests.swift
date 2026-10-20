import Foundation
import Darwin

// Videos im Chat (2.4, E2): saga-file/v2 ohne Krypto (Kopf, AD/Nonce, Bereiche aus tests/vectors/file_v2.json),
// Block-/Range-Zuordnung fuer den Player, Upload-Teile und -Fortsetzung, Antworten des Servers, Grenzen beim Senden,
// Cache-Grenze (512 MB LRU), MP4-faststart, Drehung/Dauer, Anhang-Felder - und ein 500-MB-Datenstrom mit konstantem Speicher.
func videoTests() {
    // --- Testvektoren (der krypto-freie Teil; Ver-/Entschluesseln prueft ios/Tests/ChatVectors) ---
    let vpath = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../vectors/file_v2.json").standardized.path
    guard let raw = FileManager.default.contents(atPath: vpath), let v = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any],
          let cases = v["cases"] as? [String: [String: Any]] else { check(false, "file_v2.json lesbar"); return }
    func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    func unhex(_ s: String) -> Data { var d = Data(); var i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2); d.append(UInt8(s[i..<j], radix: 16) ?? 0); i = j }; return d }
    func n(_ d: [String: Any], _ k: String) -> Int64 { (d[k] as? NSNumber)?.int64Value ?? -1 }
    let lpv = v["lp_example"] as? [String: Any] ?? [:]
    check(hex(FileV2.lp((lpv["fields_hex"] as? [String] ?? []).map(unhex))) == lpv["hex"] as? String, "file_v2 lp")
    for (name, c) in cases {
        let size = n(c, "size"), blocks = n(c, "blocks"), prefix = unhex(c["prefix_hex"] as? String ?? "")
        check(FileV2.blocks(size) == blocks && FileV2.cipherSize(size) == n(c, "blob_size"), "file_v2 \(name): Groessen")
        check(hex(FileV2.makeHeader(size: size, prefix: prefix)) == c["header_hex"] as? String, "file_v2 \(name): Kopf")
        check(FileV2.parseHeader(unhex(c["header_hex"] as? String ?? "")) == FileV2.Header(cs: FileV2.cs, size: size, n: blocks, prefix: prefix), "file_v2 \(name): Kopf lesen")
        check(hex(FileV2.ad(i: 0, n: blocks, cs: FileV2.cs, size: size)) == c["ad0_hex"] as? String
              && hex(FileV2.ad(i: blocks - 1, n: blocks, cs: FileV2.cs, size: size)) == c["ad_last_hex"] as? String, "file_v2 \(name): AD")
        check(hex(FileV2.nonce(prefix: prefix, i: 0)) == c["nonce0_hex"] as? String
              && hex(FileV2.nonce(prefix: prefix, i: blocks - 1)) == c["nonce_last_hex"] as? String, "file_v2 \(name): Nonce")
        check(FileV2.cipherLen(0, size: size) == Int(n(c, "block0_len")) && FileV2.cipherLen(blocks - 1, size: size) == Int(n(c, "block_last_len")), "file_v2 \(name): Blocklaengen")
    }
    for r in v["ranges"] as? [[String: Any]] ?? [] {
        check(FileV2.span(size: n(r, "size"), a: n(r, "a"), b: n(r, "b"))
              == FileV2.Span(firstBlock: n(r, "first_block"), lastBlock: n(r, "last_block"), cipherFrom: n(r, "cipher_from"), cipherTo: n(r, "cipher_to")),
              "file_v2 Bereich \(n(r, "a"))-\(n(r, "b"))")
    }
    // Kopf-Pruefung wie der Server: falsche Kennung/Version/Blockgroesse/Reserve
    let h = FileV2.makeHeader(size: 100, prefix: Data(repeating: 7, count: 16))
    var bad = h; bad[0] = 0x58; check(FileV2.parseHeader(bad) == nil, "Kopf: falsche Kennung")
    bad = h; bad[4] = 3; check(FileV2.parseHeader(bad) == nil, "Kopf: falsche Version")
    bad = h; bad.replaceSubrange(8..<12, with: FileV2.be(100_000, 4)); check(FileV2.parseHeader(bad) == nil, "Kopf: cs keine Zweierpotenz")
    bad = h; bad.replaceSubrange(8..<12, with: FileV2.be(32_768, 4)); check(FileV2.parseHeader(bad) == nil, "Kopf: cs zu klein")
    bad = h; bad[45] = 1; check(FileV2.parseHeader(bad) == nil, "Kopf: Reserve nicht 0")
    check(FileV2.parseHeader(h.prefix(47)) == nil, "Kopf: zu kurz")

    // --- Block- und Range-Zuordnung (Player) ---
    let cs = Int64(FileV2.cs), size: Int64 = 3 * cs + 10
    check(FileV2.requested(offset: 0, length: 2, toEnd: false, size: size)! == (0, 1), "requested: erste 2 Bytes (Probe)")
    check(FileV2.requested(offset: 100, length: 0, toEnd: true, size: size)! == (100, size - 1), "requested: bis zum Ende")
    check(FileV2.requested(offset: size - 5, length: 100, toEnd: false, size: size)! == (size - 5, size - 1), "requested: hinter dem Ende gekappt")
    check(FileV2.requested(offset: size, length: 1, toEnd: false, size: size) == nil, "requested: ausserhalb")
    check(FileV2.nextChunk(from: 5, end: size - 1, size: size, maxSpan: Int(2 * cs))! == (5, 2 * cs - 1), "nextChunk: hoechstens 2 Bloecke, an Blockgrenze")
    check(FileV2.nextChunk(from: 2 * cs, end: size - 1, size: size, maxSpan: Int(2 * cs))! == (2 * cs, size - 1), "nextChunk: Rest")
    check(FileV2.nextChunk(from: 10, end: 20, size: size)! == (10, 20), "nextChunk: kleiner Bereich")
    check(FileV2.nextChunk(from: size, end: size, size: size) == nil, "nextChunk: Ende")
    check(FileV2.blocksFor(cipherFrom: 0, cipherTo: 47, size: size) == nil, "blocksFor: nur Kopf")
    check(FileV2.blocksFor(cipherFrom: 0, cipherTo: 48, size: size)! == (0, 0), "blocksFor: Kopf + 1 Byte")
    check(FileV2.blocksFor(cipherFrom: FileV2.blockStart(1) - 1, cipherTo: FileV2.blockStart(1), size: size)! == (0, 1), "blocksFor: ueber Blockgrenze")
    check(FileV2.blocksFor(cipherFrom: FileV2.cipherSize(size) - 1, cipherTo: FileV2.cipherSize(size) - 1, size: size)! == (3, 3), "blocksFor: letztes Byte")
    // Jeder Klartext-Bereich -> die Bloecke enthalten genau diese Bytes
    var rangesOK = true
    for (a, b) in [(Int64(0), Int64(0)), (cs - 1, cs), (cs, 3 * cs + 9), (3 * cs, 3 * cs + 9), (17, 2 * cs + 5)] {
        guard let sp = FileV2.span(size: size, a: a, b: b) else { rangesOK = false; continue }
        rangesOK = rangesOK && sp.firstBlock * cs <= a && (sp.lastBlock + 1) * cs > b && sp.cipherFrom == FileV2.blockStart(sp.firstBlock)
            && sp.cipherTo == min(FileV2.blockStart(sp.lastBlock + 1), FileV2.cipherSize(size)) - 1
    }
    check(rangesOK, "span: Bloecke decken den Bereich")

    // --- Datenstrom mit Ersatz-Verschluesselung (Block + 16 Bytes): Teile, Fortsetzung, Wieder-Zusammensetzen ---
    func gen(_ o: Int64, _ l: Int) -> Data { Data((0..<l).map { UInt8(truncatingIfNeeded: (o + Int64($0)) % 251) }) }
    func fakeSeal(_ p: Data, _ i: Int64) -> Data? { var d = p; d.append(Data(repeating: UInt8(truncatingIfNeeded: i), count: 16)); return d }
    func fakeOpen(_ c: Data, _ i: Int64) -> Data? { c.suffix(16).allSatisfy { $0 == UInt8(truncatingIfNeeded: i) } ? Data(c.dropLast(16)) : nil }
    let small: Int64 = 2 * cs + 1234, pre = Data(repeating: 9, count: 16)
    var whole = Data()
    try? FileV2.produce(from: 0, to: FileV2.cipherSize(small) - 1, size: small, prefix: pre, read: gen, seal: fakeSeal, sink: { whole.append($0) })
    check(Int64(whole.count) == FileV2.cipherSize(small), "produce: ganze Groesse")
    // Fortsetzen an beliebigen Stellen (mitten im Kopf, mitten im Block) ergibt dieselben Bytes
    var resumed = Data(), off: Int64 = 0, parts = 0
    for cut in [Int64(20), 100, 262_200, 400_000] {
        try? FileV2.produce(from: off, to: cut - 1, size: small, prefix: pre, read: gen, seal: fakeSeal, sink: { resumed.append($0) }); off = cut; parts += 1
    }
    try? FileV2.produce(from: off, to: FileV2.cipherSize(small) - 1, size: small, prefix: pre, read: gen, seal: fakeSeal, sink: { resumed.append($0) })
    check(resumed == whole, "produce: Fortsetzung mitten im Block deterministisch")
    var back = Data()
    try? FileV2.openBlocks(whole.subdata(in: 48..<whole.count), first: 0, size: small, open: fakeOpen, emit: { _, p in back.append(p) })
    check(back == gen(0, Int(small)), "openBlocks: Klartext zurueck")
    var refused = false
    var tampered = whole; tampered[tampered.count - 1] ^= 1
    do { try FileV2.openBlocks(tampered.subdata(in: 48..<tampered.count), first: 0, size: small, open: fakeOpen, emit: { _, _ in }) } catch { refused = true }
    check(refused, "openBlocks: falscher Block -> Fehler")
    refused = false
    do { try FileV2.openBlocks(whole.subdata(in: 48..<whole.count - 3), first: 0, size: small, open: fakeOpen, emit: { _, _ in }) } catch { refused = true }
    check(refused, "openBlocks: abgeschnitten -> Fehler")
    refused = false
    do { try FileV2.produce(from: 0, to: FileV2.cipherSize(small), size: small, prefix: pre, read: gen, seal: fakeSeal, sink: { _ in }) } catch { refused = true }
    check(refused, "produce: hinter dem Ende -> Fehler")

    // --- 500 MB als Datenstrom: Speicher bleibt konstant (Teile zu 8 MiB, Wiedergabe in 4-MiB-Abschnitten) ---
    let big: Int64 = 500 * 1_048_576 + 777
    let total = FileV2.cipherSize(big)
    let m0 = footprintLT()
    var peak: Int64 = 0, sent: Int64 = 0, o: Int64 = 0, partsBig = 0
    while let p = UploadLogic.part(offset: o, total: total, partMax: 8 * 1_048_576) {
        try? FileV2.produce(from: p.from, to: p.to, size: big, prefix: pre, read: { o, l in Data(count: l) }, seal: fakeSeal, sink: { sent += Int64($0.count) })
        o = p.to + 1; partsBig += 1
        if partsBig % 8 == 0 { peak = max(peak, footprintLT() - m0) }
    }
    check(sent == total && partsBig == Int((total + 8 * 1_048_576 - 1) / (8 * 1_048_576)), "500 MB: alle Teile, \(partsBig) PUTs")
    var played: Int64 = 0, pos: Int64 = 0
    while let c = FileV2.nextChunk(from: pos, end: big - 1, size: big), let sp = FileV2.span(size: big, a: c.a, b: c.b) {
        var ct = Data(capacity: Int(sp.cipherTo - sp.cipherFrom + 1))
        try? FileV2.produce(from: sp.cipherFrom, to: sp.cipherTo, size: big, prefix: pre, read: { o, l in Data(count: l) }, seal: fakeSeal, sink: { ct.append($0) })
        try? FileV2.openBlocks(ct, first: sp.firstBlock, size: big, open: fakeOpen, emit: { i, p in
            let base = i * cs, lo = max(c.a, base) - base, hi = min(c.b, base + Int64(p.count) - 1) - base
            played += hi - lo + 1
        })
        pos = c.b + 1
        if (pos / cs) % 64 == 0 { peak = max(peak, footprintLT() - m0) }
    }
    check(played == big, "500 MB: Wiedergabe lueckenlos")
    check(peak < 48 * 1_048_576, "500 MB: Speicher konstant (+\(peak / 1_048_576) MB)")

    // --- Upload: Teile, Antworten, Fortsetzen ---
    check(UploadLogic.part(offset: 0, total: 20, partMax: 8)! == (0, 7), "part: erster Teil")
    check(UploadLogic.part(offset: 16, total: 20, partMax: 8)! == (16, 19), "part: letzter Teil")
    check(UploadLogic.part(offset: 20, total: 20, partMax: 8) == nil, "part: fertig")
    check(UploadLogic.part(offset: 0, total: 100_000_000, partMax: 64 * 1_048_576)! == (0, 8 * 1_048_576 - 1), "part: nie mehr als 8 MiB")
    check(UploadLogic.contentRange(from: 0, to: 8_388_607, total: 187_700_000) == "bytes 0-8388607/187700000", "Content-Range")
    check(UploadLogic.afterPut(status: 200, body: ["offset": 8_388_608, "done": false], headerOffset: nil) == .next(8_388_608), "PUT 200 -> weiter")
    check(UploadLogic.afterPut(status: 200, body: ["id": "Q1", "done": true, "offset": 5], headerOffset: nil) == .done("Q1"), "PUT 200 done")
    check(UploadLogic.afterPut(status: 200, body: nil, headerOffset: 4096) == .next(4096), "PUT 200 ohne JSON: Upload-Offset")
    check(UploadLogic.afterPut(status: 409, body: ["error": "x", "offset": 777], headerOffset: nil) == .next(777), "PUT 409 -> ab offset")
    check(UploadLogic.afterPut(status: 409, body: ["error": "x"], headerOffset: nil) == .retry, "PUT 409 ohne offset -> HEAD")
    check(UploadLogic.afterPut(status: 404, body: nil, headerOffset: nil) == .gone, "PUT 404 -> weg")
    check(UploadLogic.afterPut(status: 401, body: nil, headerOffset: nil) == .reauth, "PUT 401 -> Sitzung erneuern")
    check(UploadLogic.afterPut(status: 413, body: ["error": "zu gross"], headerOffset: nil) == .fail("zu gross"), "PUT 413 -> Fehler")
    check(UploadLogic.afterPut(status: 503, body: nil, headerOffset: nil) == .retry && UploadLogic.afterPut(status: 0, body: nil, headerOffset: nil) == .retry, "PUT 5xx/Netz -> erneut")
    check(UploadLogic.afterHead(status: 204, offset: "1048576", length: "2000000", done: nil) == .resume(1_048_576), "HEAD -> fortsetzen")
    check(UploadLogic.afterHead(status: 204, offset: "2000000", length: "2000000", done: "1") == .done, "HEAD -> schon fertig (Antwort verpasst)")
    check(UploadLogic.afterHead(status: 404, offset: nil, length: nil, done: nil) == .gone, "HEAD 404")
    check(UploadLogic.backoff(0) == 0.5 && UploadLogic.backoff(10) == 15, "backoff")

    // --- Senden: welches Format ---
    let MB = 1_048_576
    check(VideoSendLogic.plan(size: Int64(10 * MB), fileMax: 10 * MB, file2: true, videoMax: 500 * MB, fed: false, bigFiles: nil) == .v1, "plan: bis file_max v1")
    check(VideoSendLogic.plan(size: Int64(10 * MB + 1), fileMax: 10 * MB, file2: true, videoMax: 500 * MB, fed: false, bigFiles: nil) == .v2, "plan: groesser v2")
    check(VideoSendLogic.plan(size: Int64(11 * MB), fileMax: 10 * MB, file2: false, videoMax: nil, fed: false, bigFiles: nil) == .serverOld(10), "plan: Server ohne file2")
    check(VideoSendLogic.plan(size: Int64(11 * MB), fileMax: 10 * MB, file2: true, videoMax: 500 * MB, fed: true, bigFiles: false) == .peerOld(10), "plan: 2.3-Partnerserver")
    check(VideoSendLogic.plan(size: Int64(11 * MB), fileMax: 10 * MB, file2: true, videoMax: 500 * MB, fed: true, bigFiles: true) == .v2, "plan: foederiert mit 2.4")
    check(VideoSendLogic.plan(size: Int64(501 * MB), fileMax: 10 * MB, file2: true, videoMax: 500 * MB, fed: false, bigFiles: nil) == .tooBig(500), "plan: ueber video_max")
    check(VideoSendLogic.pickMax(fileMax: 10 * MB, file2: false, videoMax: 500 * MB) == 10 * MB, "pickMax ohne file2")
    check(VideoSendLogic.known(nil) && VideoSendLogic.known(2) && !VideoSendLogic.known(3), "Format bekannt")
    check(VideoSendLogic.posterTime(83.4) == 1 && abs(VideoSendLogic.posterTime(4) - 0.4) < 1e-9, "Poster bei 1 s bzw. 10 %")
    check(VideoSendLogic.durText(83.4) == "1:23" && VideoSendLogic.durText(3725) == "1:02:05" && VideoSendLogic.durText(.nan) == "0:00", "Dauer")
    check(VideoSendLogic.roundDur(83.44) == 83.4, "Dauer eine Nachkommastelle")
    check(VideoSendLogic.rotation(a: 0, b: 1) == 90 && VideoSendLogic.rotation(a: -1, b: 0) == 180 && VideoSendLogic.rotation(a: 0, b: -1) == 270 && VideoSendLogic.rotation(a: 1, b: 0) == 0, "Drehung")
    check(VideoSendLogic.display(w: 1920, h: 1080, rot: 90) == (1080, 1920), "Anzeige-Abmessungen gedreht")
    check(VideoSendLogic.shouldOptimize(w: 3840, h: 2160, bytes: 1, dur: 1) && !VideoSendLogic.shouldOptimize(w: 1280, h: 720, bytes: 2_000_000, dur: 10), "optimieren lohnt")

    // --- MP4 faststart ---
    func box(_ t: String, _ len: Int) -> Data { FileV2.be(UInt64(len), 4) + Data(t.utf8) + Data(count: len - 8) }
    let fast = box("ftyp", 24) + box("moov", 100) + box("mdat", 1000), slow = box("ftyp", 24) + box("mdat", 1000) + box("moov", 100)
    func rd(_ d: Data) -> (Int64, Int) -> Data? { { o, l in o < Int64(d.count) ? d.subdata(in: Int(o)..<min(d.count, Int(o) + l)) : nil } }
    check(MP4Logic.faststart(size: Int64(fast.count), read: rd(fast)) == true, "faststart: moov vorn")
    check(MP4Logic.faststart(size: Int64(slow.count), read: rd(slow)) == false, "faststart: mdat vorn")
    check(MP4Logic.faststart(size: 4, read: rd(Data(count: 4))) == nil, "faststart: unbekannt")

    // --- Cache-Grenze (512 MB, LRU) ---
    let now = Date()
    var entries: [ChatMediaLogic.DiskEntry] = []
    for i in 0..<140 { entries.append(.init(name: "v\(i).0", size: 4 * MB + 300, used: now.addingTimeInterval(Double(i)))) }   // ~560 MB
    let gone = VideoCacheLogic.evict(entries)
    let left = entries.filter { !gone.contains($0.name) }.reduce(0) { $0 + $1.size }
    check(!gone.isEmpty && left <= VideoCacheLogic.target && gone.contains("v0.0") && !gone.contains("v139.0"), "Cache: ueber 512 MB -> aelteste weg, \(gone.count) Dateien")
    check(VideoCacheLogic.evict(Array(entries.prefix(100))).isEmpty, "Cache: unter der Grenze bleibt alles")
    check(VideoCacheLogic.name("Q1w2E3r4", chunk: 3) == "Q1w2E3r4.3" && VideoCacheLogic.name("Q1w2E3r4", chunk: nil) == "Q1w2E3r4.h"
          && VideoCacheLogic.name("../x", chunk: 0) == nil, "Cache: Dateinamen nur aus Kennungen")
    check(VideoCacheLogic.chunk(of: 15) == 0 && VideoCacheLogic.chunk(of: 16) == 1 && VideoCacheLogic.chunkBlocks(1, n: 20) == (16, 19), "Cache: Abschnitte")

    // --- Anhang-Felder (body.files[]) ---
    let j: [String: Any] = ["id": "Q1w2", "k": "a2V5", "n": "Urlaub.mp4", "m": "video/mp4", "s": 187_654_321, "x": 2, "cs": 262_144, "dur": 83.4,
                            "w": 1280, "h": 720, "rot": 0, "fs": true, "th": ["id": "Zx9", "k": "dGg=", "m": "image/jpeg", "w": 320, "h": 180, "s": 21_877]]
    let f = ChatFileRef(j)
    check(f?.x == 2 && f?.cs == 262_144 && f?.dur == 83.4 && f?.fs == true && f?.isVideo == true && f?.isV2 == true && f?.thumb?.w == 320, "files[]: v2-Felder lesen")
    check(f.flatMap { ChatFileRef($0.dict) } == f, "files[]: Rundreise")
    check(ChatFileRef(["id": "a", "k": "b", "m": "video/mp4", "s": 5])?.isVideo == true && ChatFileRef(["id": "a", "k": "b", "m": "video/mp4", "s": 5, "x": 3])?.isVideo == false,
          "files[]: v1-Video bzw. unbekanntes Format")
    check(ChatFileRef(["id": "a", "k": "b", "m": "image/png", "s": 5])?.dict["x"] == nil, "files[]: v1 ohne x")
}

func footprintLT() -> Int64 {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let r = withUnsafeMutablePointer(to: &info) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) } }
    return r == KERN_SUCCESS ? Int64(info.phys_footprint) : 0
}
