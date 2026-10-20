import Foundation

// Videos im Chat (Saga 2.4, Etappe E2): reine Logik ohne Krypto und UI (mit swiftc pruefbar).
// Format saga-file/v2 (Server saga/file_v2.py, docs/APP-API.md "Videos im Chat"): Kopf, Bloecke, AD/Nonce,
// Bereiche (Klartext -> Ciphertext), Teile des fortsetzbaren Uploads, Antworten des Servers, Grenzen beim Senden,
// Platten-Cache (Ciphertext, 512 MB LRU), MP4-faststart, Drehung, Dauer. Ver-/Entschluesseln je Block:
// FileV2Crypto.swift (libsodium); Strom-Werkzeuge hier sind ueber das Verschluesseln parametrisiert (Tests ohne libsodium).

enum FileV2 {
    static let magic = Data("SGF2".utf8)
    static let version: UInt8 = 2
    static let headerLen = 48
    static let tagLen = 16
    /// Blockgroesse in 2.4 (Apps senden immer diese)
    static let cs = 262_144
    /// was ein Kopf haben darf (wie der Server: Zweierpotenzen 64 KiB ... 1 MiB)
    static let csMin = 65_536, csMax = 1_048_576
    static let label = "saga-file/v2"

    // MARK: Groessen

    /// Anzahl Bloecke N = max(1, ceil(s / cs))
    static func blocks(_ size: Int64, cs: Int = cs) -> Int64 { max(1, (size + Int64(cs) - 1) / Int64(cs)) }
    /// Blob-Groesse = 48 + s + 16 * N
    static func cipherSize(_ size: Int64, cs: Int = cs) -> Int64 { Int64(headerLen) + size + Int64(tagLen) * blocks(size, cs: cs) }
    /// Beginn von Block i im Blob
    static func blockStart(_ i: Int64, cs: Int = cs) -> Int64 { Int64(headerLen) + i * Int64(cs + tagLen) }
    /// Klartext-Laenge von Block i (der letzte kuerzer; leere Datei: 0)
    static func plainLen(_ i: Int64, size: Int64, cs: Int = cs) -> Int {
        let a = i * Int64(cs)
        return Int(max(0, min(Int64(cs), size - a)))
    }
    /// Ciphertext-Laenge von Block i
    static func cipherLen(_ i: Int64, size: Int64, cs: Int = cs) -> Int { plainLen(i, size: size, cs: cs) + tagLen }

    // MARK: Kopf, Nonce, AD

    struct Header: Equatable { var cs: Int; var size: Int64; var n: Int64; var prefix: Data }

    static func be(_ v: UInt64, _ n: Int) -> Data { Data((0..<n).map { UInt8(truncatingIfNeeded: v >> (8 * UInt64(n - 1 - $0))) }) }
    static func readBE(_ d: Data, _ at: Int, _ n: Int) -> UInt64 {
        let s = d.startIndex + at
        return (0..<n).reduce(UInt64(0)) { $0 << 8 | UInt64(d[s + $1]) }
    }

    /// Kopf = "SGF2" || u8 2 || u8 0 || u16 0 || u32 cs || u32 0 || u64 s || prefix(16) || 8 x 0
    static func makeHeader(size: Int64, prefix: Data, cs: Int = cs) -> Data {
        var h = magic
        h.append(contentsOf: [version, 0, 0, 0])
        h.append(be(UInt64(cs), 4)); h.append(be(0, 4)); h.append(be(UInt64(size), 8))
        h.append(prefix.prefix(16)); h.append(Data(repeating: 0, count: 8))
        return h
    }
    /// 48 Bytes -> Kopf oder nil (falsche Kennung/Version/Felder) - wie parse_header des Servers
    static func parseHeader(_ raw: Data) -> Header? {
        guard raw.count >= headerLen else { return nil }
        let d = Data(raw.prefix(headerLen))
        guard d.prefix(4) == magic, d[4] == version, d[5] == 0, d[6] == 0, d[7] == 0, readBE(d, 12, 4) == 0,
              d[40..<48].allSatisfy({ $0 == 0 }) else { return nil }
        let c = Int(readBE(d, 8, 4)), s = readBE(d, 16, 8)
        guard c >= csMin, c <= csMax, c & (c - 1) == 0, s < (1 << 50) else { return nil }
        return Header(cs: c, size: Int64(s), n: blocks(Int64(s), cs: c), prefix: Data(d[24..<40]))
    }
    /// nonce_i = prefix(16) || u64_be(i)
    static func nonce(prefix: Data, i: Int64) -> Data { Data(prefix.prefix(16)) + be(UInt64(i), 8) }
    /// LP(f1..fn) = je Feld u32_be(Laenge) || Bytes
    static func lp(_ fields: [Data]) -> Data {
        var out = Data()
        for f in fields { out.append(be(UInt64(f.count), 4)); out.append(f) }
        return out
    }
    /// AD_i = LP("saga-file/v2", u64(i), u64(N), u32(cs), u64(s), u8(letzter))
    static func ad(i: Int64, n: Int64, cs: Int, size: Int64) -> Data {
        lp([Data(label.utf8), be(UInt64(i), 8), be(UInt64(n), 8), be(UInt64(cs), 4), be(UInt64(size), 8), Data([i == n - 1 ? 1 : 0])])
    }

    // MARK: Bereiche

    struct Span: Equatable { var firstBlock: Int64; var lastBlock: Int64; var cipherFrom: Int64; var cipherTo: Int64 }
    /// Klartext a...b (einschliesslich) -> Bloecke und Ciphertext-Bytes (einschliesslich); nil = ausserhalb
    static func span(size: Int64, a: Int64, b: Int64, cs: Int = cs) -> Span? {
        guard a >= 0, b >= a, b < max(size, 1) else { return nil }
        let i = a / Int64(cs), j = b / Int64(cs), total = cipherSize(size, cs: cs)
        return Span(firstBlock: i, lastBlock: j, cipherFrom: blockStart(i, cs: cs), cipherTo: min(blockStart(j + 1, cs: cs), total) - 1)
    }
    /// Ciphertext-Bytes from...to -> betroffene Bloecke (nil: nur Kopf)
    static func blocksFor(cipherFrom: Int64, cipherTo: Int64, size: Int64, cs: Int = cs) -> (Int64, Int64)? {
        let a = max(cipherFrom, Int64(headerLen))
        guard a <= cipherTo else { return nil }
        let n = blocks(size, cs: cs)
        return (min(n - 1, (a - Int64(headerLen)) / Int64(cs + tagLen)), min(n - 1, (cipherTo - Int64(headerLen)) / Int64(cs + tagLen)))
    }

    /// Naechster Abschnitt fuer den Player: ab `from` hoechstens `maxSpan` Bytes (an Blockgrenzen), bis `end` (einschliesslich)
    static func nextChunk(from: Int64, end: Int64, size: Int64, cs: Int = cs, maxSpan: Int = 4 * 1_048_576) -> (a: Int64, b: Int64)? {
        guard from <= end, from < size else { return nil }
        let i = from / Int64(cs), lastBlock = min((end) / Int64(cs), i + Int64(max(1, maxSpan / cs)) - 1)
        return (from, min(end, min(size, (lastBlock + 1) * Int64(cs)) - 1))
    }
    /// AVAssetResourceLoadingDataRequest -> Klartext-Bereich (einschliesslich)
    static func requested(offset: Int64, length: Int, toEnd: Bool, size: Int64) -> (a: Int64, b: Int64)? {
        guard offset >= 0, offset < size else { return nil }
        let b = toEnd || length <= 0 ? size - 1 : min(size - 1, offset + Int64(length) - 1)
        return (offset, b)
    }

    // MARK: Strom (blockweise, konstanter Speicher)

    /// Ciphertext-Bytes from...to erzeugen: Kopf, dann je Block Klartext lesen -> verschluesseln -> zuschneiden -> `sink`.
    /// Deterministisch (K, Praefix, Blocknummer) - ein Upload kann an jeder Stelle fortgesetzt werden. Nie mehr als ein
    /// Block im Speicher. `read(offset, len)`: Klartext; `seal(block, i)`: verschluesseln (nil = Fehler).
    static func produce(from: Int64, to: Int64, size: Int64, prefix: Data, cs: Int = cs,
                        read: (Int64, Int) throws -> Data, seal: (Data, Int64) -> Data?, sink: (Data) throws -> Void) throws {
        let total = cipherSize(size, cs: cs)
        guard from >= 0, to >= from, to < total else { throw V2Error.range }
        if from < Int64(headerLen) {
            let h = makeHeader(size: size, prefix: prefix, cs: cs)
            try sink(h.subdata(in: Int(from)..<Int(min(Int64(headerLen) - 1, to) + 1)))
        }
        guard let (i, j) = blocksFor(cipherFrom: from, cipherTo: to, size: size, cs: cs) else { return }
        for b in i...j {
            try autoreleasepool {
                let p = try read(b * Int64(cs), plainLen(b, size: size, cs: cs))
                guard p.count == plainLen(b, size: size, cs: cs) else { throw V2Error.short }
                guard let c = seal(p, b), c.count == p.count + tagLen else { throw V2Error.crypto }
                let s = blockStart(b, cs: cs), lo = max(from, s) - s, hi = min(to, s + Int64(c.count) - 1) - s
                try sink(c.subdata(in: Int(lo)..<Int(hi) + 1))
            }
        }
    }

    /// Ciphertext einer Folge ganzer Bloecke (ab Block `first`) entschluesseln; `open(block, i)` (nil = Tag falsch).
    /// Je Block `emit(i, klartext)` - nie Klartext eines Blocks, dessen Tag nicht stimmt.
    static func openBlocks(_ ct: Data, first: Int64, size: Int64, cs: Int = cs,
                           open: (Data, Int64) -> Data?, emit: (Int64, Data) throws -> Void) throws {
        var pos = ct.startIndex, i = first
        let n = blocks(size, cs: cs)
        while pos < ct.endIndex {
            guard i < n else { throw V2Error.range }
            let len = cipherLen(i, size: size, cs: cs)
            guard ct.endIndex - pos >= len else { throw V2Error.short }
            try autoreleasepool {
                guard let p = open(ct.subdata(in: pos..<pos + len), i) else { throw V2Error.crypto }
                try emit(i, p)
            }
            pos += len; i += 1
        }
    }

    enum V2Error: Error, Equatable { case range, short, crypto, header }
}

// MARK: - Fortsetzbarer Upload (POST /api/chats/{cid}/uploads, PUT/HEAD/DELETE /api/chat/uploads/{id})

enum UploadLogic {
    /// hoechstens so viele Bytes je PUT (Server: part_max/upload_part, App: 8 MiB)
    static let partMax = 8 * 1_048_576

    /// Teil ab `offset`: from...to (einschliesslich)
    static func part(offset: Int64, total: Int64, partMax: Int?) -> (from: Int64, to: Int64)? {
        guard offset >= 0, offset < total else { return nil }
        let p = Int64(max(1, min(partMax ?? Self.partMax, Self.partMax)))
        return (offset, min(total, offset + p) - 1)
    }
    static func contentRange(from: Int64, to: Int64, total: Int64) -> String { "bytes \(from)-\(to)/\(total)" }

    /// Was nach einer Antwort auf PUT tun?
    enum Next: Equatable {
        case next(Int64)          // weiter ab offset
        case done(String?)        // fertig (Datei-Kennung, nil = Upload-Kennung)
        case retry                // Netz/Server kurz weg: warten, HEAD, weiter
        case reauth               // 401: Sitzung erneuern, dann HEAD
        case gone                 // 404: Upload unbekannt/abgelaufen/abgebrochen
        case fail(String)         // endgueltig (400, 413, ...)
    }
    static func afterPut(status: Int, body: [String: Any]?, headerOffset: Int64?) -> Next {
        let off = (body?["offset"] as? NSNumber)?.int64Value ?? headerOffset
        switch status {
        case 200..<300:
            if (body?["done"] as? Bool) == true { return .done(body?["id"] as? String) }
            if let off { return .next(off) }
            return .retry
        case 409:
            if let off { return .next(off) }                   // falsche Stelle / anderer PUT laeuft: maßgeblich ist offset
            return .retry
        case 401: return .reauth
        case 404: return .gone
        case 0, 408, 429, 500...599: return .retry
        default: return .fail((body?["error"] as? String) ?? "HTTP \(status)")
        }
    }
    /// HEAD -> (offset, fertig) oder nil (404 = weg)
    enum Head: Equatable { case resume(Int64), done, gone, retry, reauth }
    static func afterHead(status: Int, offset: String?, length: String?, done: String?) -> Head {
        switch status {
        case 200..<300:
            if done == "1" { return .done }
            if let o = offset.flatMap({ Int64($0.trimmingCharacters(in: .whitespaces)) }) {
                if let l = length.flatMap({ Int64($0.trimmingCharacters(in: .whitespaces)) }), o >= l, l > 0 { return .done }
                return .resume(o)
            }
            return .retry
        case 401: return .reauth
        case 404: return .gone
        default: return .retry
        }
    }
    /// Wartezeit vor dem n-ten erneuten Versuch (wie im Web: 0,5 s * 2^n, hoechstens 15 s)
    static func backoff(_ n: Int) -> Double { min(15, 0.5 * pow(2, Double(max(0, n)))) }
    static let maxFails = 8
}

// MARK: - Senden: welches Format, welche Grenze

enum VideoSendLogic {
    enum Plan: Equatable {
        case v1                    // bis file_max: ein Block wie bisher
        case v2                    // saga-file/v2, fortsetzbar
        case tooBig(Int)           // groesser als erlaubt (Grenze in MB)
        case peerOld(Int)          // Chat mit 2.3-Server: nur bis file_max
        case serverOld(Int)        // eigener Server ohne file2: nur bis file_max
    }
    /// `bigFiles`: Chat-Objekt big_files (nil = lokaler Chat oder Feld fehlt -> erlaubt)
    static func plan(size: Int64, fileMax: Int, file2: Bool, videoMax: Int?, fed: Bool, bigFiles: Bool?) -> Plan {
        if size <= Int64(fileMax) { return .v1 }
        let mb = { (b: Int) in max(1, b / 1_048_576) }
        guard file2 else { return .serverOld(mb(fileMax)) }
        if fed && bigFiles == false { return .peerOld(mb(fileMax)) }
        let vmax = max(fileMax, videoMax ?? 500 * 1_048_576)
        return size <= Int64(vmax) ? .v2 : .tooBig(mb(vmax))
    }
    /// groesste Datei, die ueberhaupt angenommen wird (Auswahl vor dem Verkleinern)
    static func pickMax(fileMax: Int, file2: Bool, videoMax: Int?) -> Int { file2 ? max(fileMax, videoMax ?? 500 * 1_048_576) : fileMax }
    /// Video? (MIME video/*)
    static func isVideo(_ mime: String) -> Bool { mime.lowercased().hasPrefix("video/") }
    /// darf diese App das Format anzeigen? (fehlt/1 = v1, 2 = v2; groesser: neuere Version noetig)
    static func known(_ x: Int?) -> Bool { x == nil || x == 1 || x == 2 }

    /// Poster bei 1 s bzw. 10 % der Dauer
    static func posterTime(_ dur: Double) -> Double { dur > 0 ? min(1, dur * 0.1) : 0 }
    static let posterSide = 320
    static let posterBytes = 30 * 1024
    static let posterQualities = [0.7, 0.55, 0.4, 0.3, 0.2]
    /// Dauer mit einer Nachkommastelle
    static func roundDur(_ d: Double) -> Double { d.isFinite && d > 0 ? (d * 10).rounded() / 10 : 0 }
    /// Anzeige "m:ss" bzw. "h:mm:ss"
    static func durText(_ s: Double) -> String {
        let t = Int(max(0, (s.isFinite ? s : 0)).rounded())
        let h = t / 3600, m = t % 3600 / 60, x = t % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, x) : String(format: "%d:%02d", m, x)
    }
    /// Drehung aus der Transformationsmatrix (a, b) -> 0/90/180/270
    static func rotation(a: Double, b: Double) -> Int {
        let deg = Int((atan2(b, a) * 180 / Double.pi).rounded())
        return ((deg % 360) + 360) % 360 / 90 * 90
    }
    /// Abmessungen in Anzeige-Ausrichtung (90/270: Breite und Hoehe getauscht)
    static func display(w: Int, h: Int, rot: Int) -> (w: Int, h: Int) { rot == 90 || rot == 270 ? (h, w) : (w, h) }
    /// lohnt "optimiert 720p"? (groesser als 1280x720 bzw. mehr als ~3 Mbit/s)
    static func shouldOptimize(w: Int, h: Int, bytes: Int64, dur: Double) -> Bool {
        if max(w, h) > 1280 || min(w, h) > 720 { return true }
        guard dur > 0 else { return false }
        return Double(bytes) * 8 / dur > 3_200_000
    }
}

// MARK: - MP4: liegt "moov" vor "mdat" (faststart)?

enum MP4Logic {
    /// Oberste Boxen durchgehen: `read(offset, len)` liefert hoechstens len Bytes. true = moov vor mdat,
    /// false = mdat zuerst, nil = unbekannt
    static func faststart(size: Int64, read: (Int64, Int) -> Data?) -> Bool? {
        var off: Int64 = 0, steps = 0
        while off + 8 <= size, steps < 64 {
            steps += 1
            guard let h = read(off, 16), h.count >= 8 else { return nil }
            var len = Int64(FileV2.readBE(h, 0, 4))
            let type = String(decoding: h[h.startIndex + 4..<h.startIndex + 8], as: UTF8.self)
            if len == 1 { guard h.count >= 16 else { return nil }; len = Int64(FileV2.readBE(h, 8, 8)) }
            else if len == 0 { len = size - off }
            if type == "moov" { return true }
            if type == "mdat" { return false }
            guard len >= 8 else { return nil }
            off += len
        }
        return nil
    }
}

// MARK: - Platten-Cache fuer Video-Ciphertext (getrennt vom Bildcache)

enum VideoCacheLogic {
    static let limit = 512 * 1_048_576
    static let target = 448 * 1_048_576
    /// Bloecke je Cache-Datei (4 MiB Klartext)
    static let blocksPerChunk = 16
    static func chunk(of block: Int64) -> Int64 { block / Int64(blocksPerChunk) }
    static func chunkBlocks(_ c: Int64, n: Int64) -> (Int64, Int64) { (c * Int64(blocksPerChunk), min(n - 1, (c + 1) * Int64(blocksPerChunk) - 1)) }
    /// Dateiname: "<fid>.h" (Kopf) bzw. "<fid>.<chunk>" - nur Kennungen des Servers
    static func name(_ fid: String, chunk: Int64?) -> String? {
        guard let base = ChatMediaLogic.cacheName(fid) else { return nil }
        return chunk.map { base + "." + String($0) } ?? base + ".h"
    }
    static func evict(_ e: [ChatMediaLogic.DiskEntry]) -> [String] { ChatMediaLogic.evict(e, limit: limit, target: target) }
}
