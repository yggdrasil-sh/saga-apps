import Foundation

// Bildschirm teilen auf iPhone/iPad (Saga 2.4, E3): Uebergabe der Bilder von der ReplayKit-Erweiterung
// (HlidskjalfBroadcast) an die App ueber einen Unix-Socket im Container der App-Gruppe. Die App lauscht nur waehrend
// eines verbundenen Anrufs. Je Bild ein Kopf (32 Byte, little endian) und dahinter NV12 dicht gepackt (Y: w*h, UV: w*h/2).
// Die Erweiterung (Speichergrenze ca. 50 MB) puffert nicht: ist das vorige Bild noch unterwegs, wird das neue verworfen.

enum ScreenFrame {
    static let magic: UInt32 = 0x3146_4753                                  // "SGF1"
    static let headerSize = 32
    static let socketName = "scr.sock"
    /// sockaddr_un.sun_path: 104 Byte inklusive Nullbyte
    static let maxSocketPath = 103
    /// Erweiterung: lange Kante und Bildrate; bei thermalState >= serious kleiner und langsamer (Konzept §4.5)
    static let maxLongEdge = 1920, hotLongEdge = 1280
    static let fps = 10, hotFps = 5
    static let maxSide = 4096

    struct Header: Equatable {
        var width: Int
        var height: Int
        /// Drehung im Uhrzeigersinn in Grad (0, 90, 180, 270)
        var rotation: Int
        var ptsNs: UInt64
    }
    static func payloadSize(width: Int, height: Int) -> Int { width * height * 3 / 2 }

    static func encode(_ h: Header) -> [UInt8] {
        var b = [UInt8](repeating: 0, count: headerSize)
        func put32(_ v: UInt32, _ at: Int) { for i in 0..<4 { b[at + i] = UInt8((v >> (8 * UInt32(i))) & 0xff) } }
        put32(magic, 0); put32(UInt32(h.width), 4); put32(UInt32(h.height), 8); put32(UInt32(h.rotation), 12)
        for i in 0..<8 { b[16 + i] = UInt8((h.ptsNs >> (8 * UInt64(i))) & 0xff) }
        put32(UInt32(payloadSize(width: h.width, height: h.height)), 24)
        return b
    }
    /// Kopf pruefen: Kennung, gerade Groesse bis 4096, bekannte Drehung, passende Laenge - sonst nil (Verbindung trennen)
    static func decode(_ b: [UInt8]) -> Header? {
        guard b.count >= headerSize else { return nil }
        func get32(_ at: Int) -> UInt32 { (0..<4).reduce(UInt32(0)) { $0 | UInt32(b[at + $1]) << (8 * UInt32($1)) } }
        guard get32(0) == magic else { return nil }
        let w = Int(get32(4)), h = Int(get32(8)), r = Int(get32(12)), len = Int(get32(24))
        let pts = (0..<8).reduce(UInt64(0)) { $0 | UInt64(b[16 + $1]) << (8 * UInt64($1)) }
        guard w >= 2, h >= 2, w <= maxSide, h <= maxSide, w % 2 == 0, h % 2 == 0, [0, 90, 180, 270].contains(r),
              len == payloadSize(width: w, height: h) else { return nil }
        return Header(width: w, height: h, rotation: r, ptsNs: pts)
    }
    /// Zielgroesse in der Erweiterung: lange Kante <= maxLong, gerade, Seitenverhaeltnis bleibt, nie vergroessern
    static func scaled(width: Int, height: Int, maxLong: Int) -> (width: Int, height: Int) {
        func even(_ x: Int) -> Int { max(2, x & ~1) }
        let w = max(width, 2), h = max(height, 2)
        if max(w, h) <= maxLong { return (even(w), even(h)) }
        return w >= h ? (even(maxLong), even(h * maxLong / w)) : (even(w * maxLong / h), even(maxLong))
    }
    /// Bildrate drosseln: naechstes Bild fruehestens 1/fps nach dem vorigen (kleine Toleranz fuer Takt-Schwankungen)
    static func due(lastNs: UInt64?, nowNs: UInt64, fps: Int) -> Bool {
        guard let lastNs, nowNs > lastNs else { return true }
        return nowNs - lastNs >= UInt64(1_000_000_000 / max(fps, 1)) * 9 / 10
    }
    /// App (ScreenSink): letztes Bild wiederholen, solange keine neuen kommen - ReplayKit/ScreenCaptureKit liefern bei
    /// stehendem Bildschirm nichts, libwebrtc kodiert ein angefordertes Schluesselbild aber erst mit dem naechsten
    /// Eingangsbild (Fehler 26.10.). Alle 500 ms, also spaetestens dann.
    static let repeatMs = 500
    static func repeatDue(lastNs: UInt64, nowNs: UInt64, ms: Int) -> Bool {
        guard lastNs > 0 else { return false }                              // noch kein Bild
        return nowNs > lastNs && nowNs - lastNs >= UInt64(max(ms, 1)) * 1_000_000
    }
    /// Zeitstempel der Bilder an die Quelle streng aufsteigend (Aufnahme und Wiederholung kommen von zwei Threads)
    static func nextTimestamp(now: Int64, last: Int64) -> Int64 { max(now, last + 1_000_000) }
    /// ReplayKit-Ausrichtung (RPVideoSampleOrientationKey, CGImagePropertyOrientation) -> Drehung in Grad
    static func rotation(orientation: UInt32) -> Int {
        switch orientation {
        case 3, 4: return 180                                               // down
        case 8, 5: return 90                                                // left
        case 6, 7: return 270                                               // right
        default: return 0                                                   // up
        }
    }
    /// Pfad des Sockets im Container der App-Gruppe; nil, wenn er zu lang fuer sockaddr_un waere
    static func socketPath(container: URL) -> String? {
        let p = container.appendingPathComponent(socketName).path
        return p.utf8.count <= maxSocketPath ? p : nil
    }
}
