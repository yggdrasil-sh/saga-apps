import Foundation

// Sprachnachrichten (Saga 2.5, Konzept Chat B3): Ogg-Kapselung fuer Opus (RFC 3533/7845), ohne Fremdbibliothek.
// Genau wie static/chat-voice.js (oggOpus): Seite 0 OpusHead (BOS), Seite 1 OpusTags, danach Seiten mit hoechstens 50
// Paketen bzw. 240 Segmenten, Granule = pre-skip + Samples bis zum Ende der Seite (48 kHz), letzte Seite EOS.
// Demuxer: Seiten mit CRC pruefen, Pakete ueber Lacing zusammensetzen (auch ueber Seitengrenzen), Dauer aus der letzten
// Granule, Paket-Index fuer das Spulen. Reine Logik (mit swift/XCTest pruefbar); Codec: VoiceCodec.swift.

enum OggOpus {
    static let rate = 48_000
    static let defaultPreSkip = 312

    // MARK: CRC (Polynom 0x04c11db7, ohne Spiegelung, Startwert 0)

    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var r = UInt32(i) << 24
        for _ in 0..<8 { r = r & 0x8000_0000 != 0 ? (r << 1) ^ 0x04c1_1db7 : r << 1 }
        return r
    }
    static func crc(_ d: Data) -> UInt32 {
        var c: UInt32 = 0
        for b in d { c = (c << 8) ^ table[Int(((c >> 24) ^ UInt32(b)) & 0xFF)] }
        return c
    }

    // MARK: Opus-Pakete

    /// Samples (48 kHz) eines Opus-Pakets aus dem TOC-Byte (RFC 6716, 3.1); 0 = leer/ungueltig
    static func samples(_ p: Data) -> Int {
        guard let toc = p.first else { return 0 }
        let cfg = Int(toc >> 3), c = Int(toc & 3)
        let n: Int
        switch c {
        case 0: n = 1
        case 3: n = p.count > 1 ? Int(p[p.startIndex + 1] & 63) : 0
        default: n = 2
        }
        let us: Int
        if cfg < 12 { us = [10_000, 20_000, 40_000, 60_000][cfg & 3] }
        else if cfg < 16 { us = [10_000, 20_000][cfg & 1] }
        else { us = [2_500, 5_000, 10_000, 20_000][cfg & 3] }
        return n * us * rate / 1_000_000
    }

    /// OpusHead (19 Bytes): Version 1, Kanaele, pre-skip, Rate, Verstaerkung 0, Mapping 0
    static func head(channels: Int = 1, preSkip: Int, inputRate: Int = rate) -> Data {
        var h = Data("OpusHead".utf8)
        h.append(1); h.append(UInt8(channels))
        h.append(le(UInt64(preSkip), 2)); h.append(le(UInt64(inputRate), 4)); h.append(le(0, 2)); h.append(0)
        return h
    }
    static func tags(vendor: String) -> Data {
        let v = Data(vendor.utf8)
        var t = Data("OpusTags".utf8)
        t.append(le(UInt64(v.count), 4)); t.append(v); t.append(le(0, 4))
        return t
    }
    static func le(_ v: UInt64, _ n: Int) -> Data { Data((0..<n).map { UInt8(truncatingIfNeeded: v >> (8 * UInt64($0))) }) }
    static func readLE(_ d: Data, _ at: Int, _ n: Int) -> UInt64 {
        let s = d.startIndex + at
        return (0..<n).reversed().reduce(UInt64(0)) { $0 << 8 | UInt64(d[s + $1]) }
    }

    // MARK: Schreiben

    /// Eine Seite (Pakete vollstaendig, je Paket Lacing mit 255er-Stuecken)
    static func page(_ packets: [Data], granule: Int64, flags: UInt8, serial: UInt32, seq: UInt32) -> Data {
        var lace: [UInt8] = []
        for p in packets {
            var n = p.count
            while n >= 255 { lace.append(255); n -= 255 }
            lace.append(UInt8(n))
        }
        var b = Data("OggS".utf8)
        b.append(0); b.append(flags)
        b.append(le(UInt64(bitPattern: granule), 8)); b.append(le(UInt64(serial), 4)); b.append(le(UInt64(seq), 4))
        b.append(le(0, 4))                                             // CRC (danach eingesetzt)
        b.append(UInt8(lace.count)); b.append(contentsOf: lace)
        for p in packets { b.append(p) }
        let c = crc(b)
        let o = b.startIndex + 22
        for i in 0..<4 { b[o + i] = UInt8(truncatingIfNeeded: c >> (8 * UInt32(i))) }
        return b
    }

    /// Fortlaufend schreiben (Aufnahme): Pakete sammeln, Seiten bis ~1 s; finish() schreibt die letzte Seite mit EOS
    struct Writer {
        let serial: UInt32
        let preSkip: Int
        private(set) var data = Data()
        private(set) var granule: Int64
        private var seq: UInt32 = 0
        private var cur: [Data] = []
        private var segs = 0
        private(set) var packets = 0
        init(preSkip: Int = OggOpus.defaultPreSkip, serial: UInt32 = UInt32.random(in: 1...UInt32.max), vendor: String = "Saga") {
            self.serial = serial; self.preSkip = preSkip; granule = Int64(preSkip)
            data.append(OggOpus.page([OggOpus.head(preSkip: preSkip)], granule: 0, flags: 2, serial: serial, seq: 0))
            data.append(OggOpus.page([OggOpus.tags(vendor: vendor)], granule: 0, flags: 0, serial: serial, seq: 1))
            seq = 2
        }
        /// Samples ohne pre-skip (Dauer der Aufnahme)
        var samples: Int64 { granule - Int64(preSkip) }
        mutating func add(_ p: Data) {
            if !cur.isEmpty && (cur.count >= 50 || segs + p.count / 255 + 1 > 240) { flush(eos: false) }
            granule += Int64(OggOpus.samples(p)); cur.append(p); segs += p.count / 255 + 1; packets += 1
        }                                                              // die letzte Seite bleibt offen bis finish() (EOS, Laenge)
        private mutating func flush(eos: Bool) {
            data.append(OggOpus.page(cur, granule: granule, flags: eos ? 4 : 0, serial: serial, seq: seq))
            seq += 1; cur = []; segs = 0
        }
        /// Letzte Seite (EOS); danach nichts mehr anhaengen. `samples`: echte Laenge (RFC 7845 4.4: die letzte Granule darf
        /// kleiner sein als die Summe der Pakete - so wird das Polster am Ende nicht mitgespielt)
        mutating func finish(samples: Int64? = nil) -> Data {
            if let s = samples, s >= 0 { granule = min(granule, Int64(preSkip) + s) }
            flush(eos: true); return data
        }
    }

    /// Ganze Datei aus fertigen Paketen (Tests, Umpacken)
    static func mux(_ packets: [Data], preSkip: Int = defaultPreSkip, serial: UInt32 = 0x5A6A_0001, vendor: String = "Saga") -> Data {
        var w = Writer(preSkip: preSkip, serial: serial, vendor: vendor)
        for p in packets { w.add(p) }
        return w.finish()
    }

    // MARK: Lesen

    struct Packet: Equatable {
        var data: Data
        /// Granule am Ende dieses Pakets (pre-skip eingerechnet)
        var end: Int64
    }
    enum DemuxError: Error, Equatable { case notOgg, crc, noHead, notOpus, serial }

    /// Fortlaufend lesen (Daten kommen blockweise): feed() liefert die neu vollstaendigen Pakete
    struct Demuxer {
        private var buf = Data()
        private var partial = Data()
        private var serial: UInt32?
        private var headers = 0
        private(set) var channels = 1
        private(set) var preSkip = 0
        private(set) var lastGranule: Int64 = 0
        private(set) var eos = false
        /// Granule am Ende des zuletzt gelieferten Pakets
        private var pos: Int64 = 0
        private var started = false
        init() {}
        var hasHead: Bool { headers >= 1 }

        mutating func feed(_ d: Data) throws -> [Packet] {
            buf.append(d)
            var out: [Packet] = []
            var at = buf.startIndex
            while buf.endIndex - at >= 27 {
                guard buf[at] == 0x4F, buf[at + 1] == 0x67, buf[at + 2] == 0x67, buf[at + 3] == 0x53, buf[at + 4] == 0 else { throw DemuxError.notOgg }
                let nseg = Int(buf[at + 26])
                guard buf.endIndex - at >= 27 + nseg else { break }
                let lace = buf[(at + 27)..<(at + 27 + nseg)]
                let body = lace.reduce(0) { $0 + Int($1) }
                let total = 27 + nseg + body
                guard buf.endIndex - at >= total else { break }
                var pg = Data(buf[at..<(at + total)])
                let want = UInt32(truncatingIfNeeded: OggOpus.readLE(pg, 22, 4))
                for i in 0..<4 { pg[pg.startIndex + 22 + i] = 0 }
                guard OggOpus.crc(pg) == want else { throw DemuxError.crc }
                let flags = buf[at + 5]
                let gran = Int64(bitPattern: OggOpus.readLE(buf, at - buf.startIndex + 6, 8))
                let ser = UInt32(truncatingIfNeeded: OggOpus.readLE(buf, at - buf.startIndex + 14, 4))
                if serial == nil { serial = ser } else if serial != ser { throw DemuxError.serial }
                if flags & 1 == 0 { partial = Data() }                    // keine Fortsetzung: Rest verwerfen
                var o = at + 27 + nseg
                var done: [Data] = []
                for l in lace {
                    partial.append(buf[o..<(o + Int(l))]); o += Int(l)
                    if l < 255 { done.append(partial); partial = Data() }
                }
                for p in done {
                    if headers == 0 {
                        guard p.count >= 19, p.prefix(8) == Data("OpusHead".utf8) else { throw DemuxError.notOpus }
                        channels = Int(p[p.startIndex + 9]); preSkip = Int(OggOpus.readLE(p, 10, 2)); headers = 1
                        pos = Int64(preSkip)
                    } else if headers == 1 {
                        headers = 2                                        // OpusTags
                    } else {
                        pos += Int64(OggOpus.samples(p))
                        out.append(Packet(data: p, end: pos))
                    }
                }
                if gran >= 0 && headers >= 2 && (!done.isEmpty || flags & 4 != 0) && pos > Int64(preSkip) { lastGranule = gran }
                if flags & 4 != 0 { eos = true }
                at += total
            }
            if at > buf.startIndex { buf = Data(buf[at...]) }
            guard headers > 0 || buf.count < 65_536 else { throw DemuxError.noHead }
            return out
        }
    }

    struct File: Equatable {
        var channels: Int
        var preSkip: Int
        var packets: [Packet]
        var lastGranule: Int64
        /// Dauer in Sekunden (letzte Granule minus pre-skip)
        var duration: Double { Double(max(0, lastGranule - Int64(preSkip))) / Double(OggOpus.rate) }
    }
    static func demux(_ d: Data) throws -> File {
        var x = Demuxer()
        let p = try x.feed(d)
        guard x.hasHead else { throw DemuxError.noHead }
        return File(channels: x.channels, preSkip: x.preSkip, packets: p, lastGranule: x.lastGranule)
    }

    /// Spulen: Index des Pakets, ab dem `sec` (ohne pre-skip) hoerbar ist, mit `preroll` Paketen Vorlauf (Opus braucht ~80 ms)
    static func seekIndex(_ packets: [Packet], preSkip: Int, sec: Double, preroll: Int = 4) -> (index: Int, skip: Int) {
        let target = Int64(preSkip) + Int64(max(0, sec) * Double(rate))
        var lo = 0, hi = packets.count
        while lo < hi { let m = (lo + hi) / 2; if packets[m].end <= target { lo = m + 1 } else { hi = m } }
        guard lo < packets.count else { return (packets.count, 0) }
        let start = max(0, lo - preroll)
        let startGranule = start == 0 ? 0 : packets[start - 1].end
        return (start, Int(max(0, target - startGranule)))
    }
}
