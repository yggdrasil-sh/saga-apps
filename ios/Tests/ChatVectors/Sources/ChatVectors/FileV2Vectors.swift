import Foundation
import CryptoKit
import Darwin

// Videos im Chat (2.4): tests/vectors/file_v2.json (Kopie: ios/Tests/vectors/file_v2.json) gegen App/FileV2Logic.swift
// und App/FileV2Crypto.swift - dieselben Punkte wie tools/file_vectors_v2.py check (Kopf, AD/Nonce, Bloecke, SHA-256,
// Entschluesseln, reject, ranges). Dazu ein Datenstrom-Test mit 500 MB (echte Krypto): Speicher bleibt konstant.
func fileV2Vectors(_ path: String) -> [(String, Bool)] {
    let C = ChatCrypto.self
    var res: [(String, Bool)] = []
    func ok(_ n: String, _ c: Bool) { res.append(("file_v2 " + n, c)) }
    guard let raw = FileManager.default.contents(atPath: path), let v = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] else {
        return [("file_v2 Vektoren lesbar (\(path))", false)]
    }
    func s(_ d: [String: Any], _ k: String) -> String { d[k] as? String ?? "" }
    func i(_ d: [String: Any], _ k: String) -> Int64 { (d[k] as? NSNumber)?.int64Value ?? -1 }
    func plain(_ n: Int) -> Data { Data((0..<n).map { UInt8($0 % 251) }) }
    func sha(_ d: Data) -> String { SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined() }
    ok("format", s(v, "format") == "saga-file/v2" && (v["cs"] as? Int) == FileV2.cs && (v["header_len"] as? Int) == 48 && (v["tag_len"] as? Int) == 16)
    let lpv = v["lp_example"] as? [String: Any] ?? [:]
    ok("lp", C.hex(FileV2.lp((lpv["fields_hex"] as? [String] ?? []).compactMap(C.unhex))) == s(lpv, "hex"))
    var blobs: [String: Data] = [:]
    let cases = v["cases"] as? [String: [String: Any]] ?? [:]
    ok("cases vorhanden", cases.count >= 8)
    for (name, c) in cases.sorted(by: { $0.key < $1.key }) {
        let size = i(c, "size"), n = i(c, "blocks"), prefix = C.unhex(s(c, "prefix_hex")) ?? Data(), key = C.unb64(s(c, "key"), 32) ?? Data()
        let k = FileV2Key(key: key, prefix: prefix, size: size)
        guard let blob = FileV2Crypto.encryptAll(plain(Int(size)), k: k) else { ok("\(name): verschluesseln", false); continue }
        blobs[name] = blob
        ok("\(name): Kopf", C.hex(blob.prefix(48)) == s(c, "header_hex") && C.hex(k.header) == s(c, "header_hex")
           && FileV2.parseHeader(blob.prefix(48)) == FileV2.Header(cs: FileV2.cs, size: size, n: n, prefix: prefix))
        ok("\(name): AD/Nonce", C.hex(FileV2.ad(i: 0, n: n, cs: FileV2.cs, size: size)) == s(c, "ad0_hex")
           && C.hex(FileV2.nonce(prefix: prefix, i: 0)) == s(c, "nonce0_hex")
           && C.hex(FileV2.ad(i: n - 1, n: n, cs: FileV2.cs, size: size)) == s(c, "ad_last_hex")
           && C.hex(FileV2.nonce(prefix: prefix, i: n - 1)) == s(c, "nonce_last_hex") && FileV2.blocks(size) == n)
        let b0 = blob.subdata(in: 48..<min(48 + FileV2.cs + 16, blob.count)), last = blob.subdata(in: Int(FileV2.blockStart(n - 1))..<blob.count)
        ok("\(name): Bloecke", b0.count == Int(i(c, "block0_len")) && C.hex(b0.prefix(64)) == s(c, "block0_head_hex") && C.hex(b0.suffix(64)) == s(c, "block0_tail_hex")
           && last.count == Int(i(c, "block_last_len")) && C.hex(last.prefix(64)) == s(c, "block_last_head_hex") && C.hex(last.suffix(64)) == s(c, "block_last_tail_hex")
           && FileV2.cipherLen(n - 1, size: size) == last.count)
        ok("\(name): SHA-256 + Groesse", sha(blob) == s(c, "blob_sha256") && Int64(blob.count) == i(c, "blob_size") && FileV2.cipherSize(size) == Int64(blob.count))
        ok("\(name): entschluesseln", FileV2Crypto.decryptAll(blob, fileKey: key) == plain(Int(size)))
        // jeder Teil einzeln erzeugt (Upload-Fortsetzung mitten im Block) ergibt denselben Blob
        var parts = Data(), off: Int64 = 0
        while let p = UploadLogic.part(offset: off, total: k.total, partMax: 100_003) {
            try? FileV2.produce(from: p.from, to: p.to, size: size, prefix: prefix, read: { o, l in plain(Int(size)).subdata(in: Int(o)..<Int(o) + l) },
                                seal: { FileV2Crypto.seal($0, i: $1, k: k) }, sink: { parts.append($0) })
            off = p.to + 1
        }
        ok("\(name): Teile fortsetzbar", parts == blob)
    }
    func mutate(_ blob: Data, _ r: [String: Any]) -> Data {
        var b = [UInt8](blob)
        let cs = FileV2.cs
        switch s(r, "op") {
        case "swap_blocks":
            let bi = Int(FileV2.blockStart(i(r, "i"))), bj = Int(FileV2.blockStart(i(r, "j")))
            let x = Array(b[bi..<bi + cs + 16]), y = Array(b[bj..<bj + cs + 16])
            b.replaceSubrange(bi..<bi + cs + 16, with: y); b.replaceSubrange(bj..<bj + cs + 16, with: x)
        case "drop_last_block":
            b.removeSubrange(Int(FileV2.blockStart(FileV2.blocks(i(r, "size")) - 1))...)
        case "drop_last_block_fix_size":
            let n = FileV2.blocks(i(r, "size"))
            b.removeSubrange(Int(FileV2.blockStart(n - 1))...)
            b.replaceSubrange(16..<24, with: [UInt8](FileV2.be(UInt64((n - 1) * Int64(cs)), 8)))
        case "set_size": b.replaceSubrange(16..<24, with: [UInt8](FileV2.be(UInt64(i(r, "value")), 8)))
        case "set_cs": b.replaceSubrange(8..<12, with: [UInt8](FileV2.be(UInt64(i(r, "value")), 4)))
        case "set_byte": b[Int(i(r, "offset"))] = UInt8(i(r, "value"))
        case "flip": b[Int(i(r, "offset"))] ^= 1
        case "truncate": b.removeSubrange(Int(i(r, "length"))...)
        default: break
        }
        return Data(b)
    }
    for r in v["reject"] as? [[String: Any]] ?? [] {
        guard let blob = blobs[s(r, "case")], let key = C.unb64(s(cases[s(r, "case")] ?? [:], "key"), 32) else { ok("abgelehnt: \(s(r, "name")) (Fall fehlt)", false); continue }
        ok("abgelehnt: \(s(r, "name"))", FileV2Crypto.decryptAll(mutate(blob, r), fileKey: key) == nil)
    }
    for r in v["ranges"] as? [[String: Any]] ?? [] {
        let sp = FileV2.span(size: i(r, "size"), a: i(r, "a"), b: i(r, "b"))
        ok("Bereich \(i(r, "a"))-\(i(r, "b"))", sp == FileV2.Span(firstBlock: i(r, "first_block"), lastBlock: i(r, "last_block"), cipherFrom: i(r, "cipher_from"), cipherTo: i(r, "cipher_to")))
    }
    // Wie beim Streaming: nur Kopf + Bloecke eines Bereichs, entschluesseln und zuschneiden
    if let blob = blobs["rest_block"], let c = cases["rest_block"], let key = C.unb64(s(c, "key"), 32), let k = FileV2Crypto.key(header: blob.prefix(48), fileKey: key, size: i(c, "size"), cs: FileV2.cs) {
        let all = plain(Int(k.size))
        var good = true
        for (a, b) in [(Int64(0), Int64(0)), (262_143, 262_144), (100, 524_292), (524_290, 524_292)] {
            guard let sp = FileV2.span(size: k.size, a: a, b: b) else { good = false; continue }
            var out = Data()
            try? FileV2.openBlocks(blob.subdata(in: Int(sp.cipherFrom)..<Int(sp.cipherTo) + 1), first: sp.firstBlock, size: k.size,
                                   open: { FileV2Crypto.open($0, i: $1, k: k) }, emit: { _, p in out.append(p) })
            let base = sp.firstBlock * Int64(FileV2.cs)
            good = good && out.count > Int(b - base) && out.subdata(in: Int(a - base)..<Int(b - base) + 1) == all.subdata(in: Int(a)..<Int(b) + 1)
        }
        ok("Bereich entschluesseln (Streaming)", good)
        ok("Kopf passt nicht zur Nachricht", FileV2Crypto.key(header: blob.prefix(48), fileKey: key, size: k.size - 1, cs: FileV2.cs) == nil
           && FileV2Crypto.key(header: blob.prefix(48), fileKey: key, size: k.size, cs: 131_072) == nil)
    }
    res += fileV2Stream()
    return res
}

/// Speicher des Prozesses (phys_footprint)
func footprint() -> Int64 {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let r = withUnsafeMutablePointer(to: &info) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) } }
    return r == KERN_SUCCESS ? Int64(info.phys_footprint) : 0
}

/// 500 MB als Datenstrom: Teile zu 8 MiB erzeugen (Upload) und blockweise wieder entschluesseln (Wiedergabe/Speichern),
/// Klartext nur ueber SHA-256 verglichen. Der Speicher darf dabei nicht mit der Dateigroesse wachsen.
func fileV2Stream() -> [(String, Bool)] {
    let size: Int64 = 500 * 1_048_576 + 12_345
    let k = FileV2Key.fresh(size: size)
    func gen(_ o: Int64, _ l: Int) -> Data { Data((0..<l).map { UInt8(truncatingIfNeeded: (o + Int64($0)) % 251) }) }
    var hIn = SHA256(), hOut = SHA256()
    var pending = Data(), next: Int64 = 0, headerDone = false, failed = false, peak: Int64 = 0, nIn: Int64 = 0, nOut: Int64 = 0
    let start = footprint()
    let t0 = Date()
    var off: Int64 = 0
    while let p = UploadLogic.part(offset: off, total: k.total, partMax: nil), !failed {
        do {
            try FileV2.produce(from: p.from, to: p.to, size: size, prefix: k.prefix, read: { o, l in let d = gen(o, l)
                if o == nIn { hIn.update(data: d); nIn += Int64(d.count) }     // Block an einer Teilgrenze wird zweimal erzeugt
                return d },
                               seal: { FileV2Crypto.seal($0, i: $1, k: k) }, sink: { chunk in
                pending.append(chunk)
                if !headerDone, pending.count >= 48 {
                    if FileV2Crypto.key(header: pending.prefix(48), fileKey: k.key, size: size, cs: FileV2.cs) == nil { failed = true }
                    pending.removeFirst(48); headerDone = true
                }
                while headerDone, next < k.n, pending.count >= FileV2.cipherLen(next, size: size) {
                    let len = FileV2.cipherLen(next, size: size)
                    guard let pl = FileV2Crypto.open(pending.prefix(len), i: next, k: k) else { failed = true; return }
                    hOut.update(data: pl); nOut += Int64(pl.count); pending.removeFirst(len); next += 1
                }
                pending = Data(pending)                                  // Puffer verdichten (Rest < 1 Block)
            })
        } catch { failed = true }
        off = p.to + 1
        peak = max(peak, footprint() - start)
    }
    let secs = Date().timeIntervalSince(t0)
    print(String(format: "file_v2 Datenstrom 500 MB: %.1f s, Speicher +%.1f MB (Spitze), %lld/%lld Bytes", secs, Double(peak) / 1_048_576, nIn, nOut))
    // Datei-Weg (Upload-Teil aus einer Datei) einmal mit 20 MB
    var fileOK = false
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("fv2-\(UUID().uuidString)")
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: dir) }
    let src = dir.appendingPathComponent("plain"), part = dir.appendingPathComponent("part")
    let small: Int64 = 20 * 1_048_576 + 7
    if (try? gen(0, Int(small)).write(to: src)) != nil {
        let ks = FileV2Key.fresh(size: small)
        var blob = Data(), o: Int64 = 0
        while let p = UploadLogic.part(offset: o, total: ks.total, partMax: 3_000_000) {
            guard (try? FileV2Crypto.writePart(source: src, k: ks, from: p.from, to: p.to, into: part)) != nil, let d = try? Data(contentsOf: part) else { break }
            blob.append(d); o = p.to + 1
        }
        fileOK = FileV2Crypto.decryptAll(blob, fileKey: ks.key) == gen(0, Int(small))
    }
    return [("file_v2 Datenstrom 500 MB: alle Bloecke", !failed && next == k.n && pending.isEmpty),
            ("file_v2 Datenstrom 500 MB: Klartext gleich (SHA-256)", Data(hIn.finalize()) == Data(hOut.finalize())),
            ("file_v2 Datenstrom 500 MB: Speicher konstant (< 48 MB)", peak < 48 * 1_048_576),
            ("file_v2 Upload-Teile aus Datei (20 MB)", fileOK)]
}
