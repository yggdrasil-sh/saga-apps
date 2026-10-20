import XCTest
import Foundation

// Ogg-Kapselung fuer Opus (VoiceOgg.swift): CRC, Kopf, Round-Trip Muxer/Demuxer (auch blockweise und ueber Seitengrenzen),
// Dauer aus der Granule, Spulen. Reine Logik mit erfundenen Paketen (TOC-Byte gesetzt) - ohne Codec und ohne Geraet.

final class OggOpusTests: XCTestCase {
    /// Opus-Paket: TOC 0x78 = SILK/Hybrid FB 20 ms, ein Rahmen (960 Samples); Rest Fuellbytes
    private func pkt(_ n: Int, toc: UInt8 = 0x78, fill: UInt8 = 0) -> Data {
        var d = Data([toc]); d.append(Data((1..<max(1, n)).map { UInt8(truncatingIfNeeded: Int($0) &+ Int(fill)) })); return d
    }

    func testCRCKnownValue() {
        // CRC-32 der Ogg-Spezifikation (Polynom 0x04c11db7, Start 0, ohne Spiegelung, ohne Abschluss-XOR)
        XCTAssertEqual(OggOpus.crc(Data("123456789".utf8)), 0x89A1_897F)
        XCTAssertEqual(OggOpus.crc(Data()), 0)
    }

    func testSamplesFromTOC() {
        XCTAssertEqual(OggOpus.samples(Data([0x78])), 960)               // Hybrid FB 20 ms
        XCTAssertEqual(OggOpus.samples(Data([0x7B, 0x03])), 2880)         // Code 3, drei Rahmen
        XCTAssertEqual(OggOpus.samples(Data([0x0B])), 0)                  // Code 3 ohne Zaehlbyte
        XCTAssertEqual(OggOpus.samples(Data([0xF8])), 960)               // CELT FB 20 ms
        XCTAssertEqual(OggOpus.samples(Data([0xE0])), 120)               // CELT 2,5 ms
        XCTAssertEqual(OggOpus.samples(Data([0x18])), 2880)              // SILK 60 ms
        XCTAssertEqual(OggOpus.samples(Data([0x79])), 1920)              // zwei Rahmen
        XCTAssertEqual(OggOpus.samples(Data()), 0)
    }

    func testHead() {
        let h = OggOpus.head(preSkip: 312)
        XCTAssertEqual(h.count, 19)
        XCTAssertEqual(h.prefix(8), Data("OpusHead".utf8))
        XCTAssertEqual(h[8], 1); XCTAssertEqual(h[9], 1)
        XCTAssertEqual(OggOpus.readLE(h, 10, 2), 312)
        XCTAssertEqual(OggOpus.readLE(h, 12, 4), 48_000)
    }

    func testRoundTripAndPages() throws {
        let pkts = (0..<180).map { pkt(40 + $0 % 70, fill: UInt8($0 % 251)) }
        let ogg = OggOpus.mux(pkts, preSkip: 312, serial: 0xABCD_0001)
        let f = try OggOpus.demux(ogg)
        XCTAssertEqual(f.preSkip, 312)
        XCTAssertEqual(f.channels, 1)
        XCTAssertEqual(f.packets.map(\.data), pkts)
        XCTAssertEqual(f.packets.last?.end, 312 + 180 * 960)
        XCTAssertEqual(f.lastGranule, 312 + 180 * 960)
        XCTAssertEqual(f.duration, 3.6, accuracy: 1e-9)
        // Seiten: BOS (Kopf), Tags, je hoechstens 50 Pakete, letzte mit EOS; Granule wachsend
        let pages = parsePages(ogg)
        XCTAssertEqual(pages.first?.flags, 2)
        XCTAssertEqual(pages.last?.flags, 4)
        XCTAssertEqual(pages.count, 2 + 4)                                 // 50 + 50 + 50 + 30
        XCTAssertEqual(pages.map(\.seq), Array(0..<UInt32(pages.count)))
        XCTAssertTrue(pages.dropFirst(2).allSatisfy { $0.serial == 0xABCD_0001 })
        let g = pages.dropFirst(2).map(\.granule)
        XCTAssertEqual(g, g.sorted())
        XCTAssertEqual(g.first, 312 + 50 * 960)
    }

    func testLargePacketsLacing() throws {
        // Pakete ueber 255 Bytes (mehrere Lacing-Werte, auch genau 255/510) - Seite bleibt unter 255 Segmenten
        let sizes = [255, 510, 256, 1000, 3000, 1, 254, 4000, 765]
        let pkts = sizes.enumerated().map { pkt($0.element, fill: UInt8($0.offset)) }
        let ogg = OggOpus.mux(Array(repeating: pkts, count: 8).flatMap { $0 })
        let f = try OggOpus.demux(ogg)
        XCTAssertEqual(f.packets.count, 72)
        XCTAssertEqual(f.packets.map(\.data.count), Array(repeating: sizes, count: 8).flatMap { $0 })
        XCTAssertTrue(parsePages(ogg).allSatisfy { $0.segments <= 255 })
    }

    func testIncrementalFeed() throws {
        let pkts = (0..<120).map { pkt(30 + $0 % 90, fill: UInt8($0)) }
        let ogg = OggOpus.mux(pkts)
        for chunk in [1, 7, 27, 300, 4096] {
            var d = OggOpus.Demuxer(), got: [OggOpus.Packet] = []
            var i = 0
            while i < ogg.count { got += try d.feed(ogg.subdata(in: i..<min(ogg.count, i + chunk))); i += chunk }
            XCTAssertEqual(got.map(\.data), pkts, "Stueckgroesse \(chunk)")
            XCTAssertTrue(d.eos)
        }
    }

    func testTrimmedLength() throws {
        // Echte Laenge kuerzer als die Pakete (Polster am Ende): letzte Granule = pre-skip + Samples
        var w = OggOpus.Writer(preSkip: 312, serial: 7)
        for _ in 0..<126 { w.add(pkt(60)) }
        let f = try OggOpus.demux(w.finish(samples: 120_000))
        XCTAssertEqual(f.lastGranule, 312 + 120_000)
        XCTAssertEqual(f.duration, 2.5, accuracy: 1e-9)
    }

    func testCorruptionDetected() {
        var ogg = OggOpus.mux((0..<10).map { pkt(50, fill: UInt8($0)) })
        ogg[ogg.count - 5] ^= 0x40                                          // ein Bit im letzten Paket
        XCTAssertThrowsError(try OggOpus.demux(ogg)) { XCTAssertEqual($0 as? OggOpus.DemuxError, .crc) }
        XCTAssertThrowsError(try OggOpus.demux(Data("RIFF....WAVEfmt ".utf8) + Data(count: 40)))
        var bad = OggOpus.mux([pkt(10)]); bad.replaceSubrange(28..<36, with: Data("OpusXXXX".utf8))   // falscher Kopf
        XCTAssertThrowsError(try OggOpus.demux(bad))
    }

    func testSeekIndex() throws {
        let f = try OggOpus.demux(OggOpus.mux((0..<500).map { _ in pkt(40) }, preSkip: 312))
        let a = OggOpus.seekIndex(f.packets, preSkip: f.preSkip, sec: 0)
        XCTAssertEqual(a.index, 0); XCTAssertEqual(a.skip, 312)
        let b = OggOpus.seekIndex(f.packets, preSkip: f.preSkip, sec: 5)          // 5 s = Paket 250, 4 Pakete Vorlauf
        XCTAssertEqual(b.index, 246)
        XCTAssertEqual(Int64(b.skip), Int64(312 + 240_000) - f.packets[245].end)
        XCTAssertEqual(OggOpus.seekIndex(f.packets, preSkip: f.preSkip, sec: 99).index, 500)
    }

    func testFixtureFile() throws {
        // Testdatei aus Synthese (2,5 s Sinus, Opus 24 kbit/s des Systems, eigener Muxer) - nie vom Mikrofon
        let d = try loadVector("voice_sine", ext: "ogg")
        let f = try OggOpus.demux(d)
        XCTAssertEqual(f.channels, 1)
        XCTAssertEqual(f.duration, 2.5, accuracy: 0.001)
        XCTAssertTrue(f.packets.allSatisfy { OggOpus.samples($0.data) == 960 }, "20-ms-Rahmen")
        let kbps = Double(f.packets.reduce(0) { $0 + $1.data.count }) * 8 / f.duration / 1000
        XCTAssertEqual(kbps, 24, accuracy: 6, "etwa 24 kbit/s")
    }

    // MARK: Hilfen

    struct Page { var flags: UInt8; var granule: Int64; var serial: UInt32; var seq: UInt32; var segments: Int }
    private func parsePages(_ d: Data) -> [Page] {
        var out: [Page] = [], i = 0
        while i + 27 <= d.count {
            let ns = Int(d[i + 26]), body = d[(i + 27)..<(i + 27 + ns)].reduce(0) { $0 + Int($1) }
            out.append(Page(flags: d[i + 5], granule: Int64(bitPattern: OggOpus.readLE(d, i + 6, 8)), serial: UInt32(OggOpus.readLE(d, i + 14, 4)),
                            seq: UInt32(OggOpus.readLE(d, i + 18, 4)), segments: ns))
            i += 27 + ns + body
        }
        return out
    }
}
