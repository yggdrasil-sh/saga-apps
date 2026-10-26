import Foundation

// Gjallarhorn (2.5): Simulcast-Schichten der Kamera am Knoten - reine Logik (Logic-Runner, HornE2ETests).
// Fehler 26.10. (1:1 ueber Knoten ohne Bild in beide Richtungen): libwebrtc (webrtc-sdk M150, VP8/libvpx) kodiert ein
// 720p-Bild GAR NICHT, sobald die Schichten verschiedene maxFramerate oder numTemporalLayers haben (l 20 fps/T2,
// m 30 fps/T3) - beim Anlegen gesetzt kommt kein Rahmen, spaeter gesetzt bleibt der Kodierer nach wenigen Sekunden
// stehen. Mit 320x240 lief nur noch "l", und die schaltet hornTune zu zweit ab. Daher: alle Schichten mit denselben
// Werten (T3 fuer die Bildrate der Kacheln am Knoten), Bildrate nur gemeinsam begrenzen (Energiesparen 15 fps).
// Geprueft im Simulator gegen einen lokalen Knoten (HornNodeTests.testOneToOneVideo*).
enum HornCamPlan {
    struct Layer: Equatable {
        var rid: String
        var scale: Double
        var kbit: Int
    }
    /// zeitliche Schichten je Kamera-Schicht (gleich fuer alle, sonst kodiert libwebrtc nichts)
    static let temporalLayers = 3
    /// Simulcast (NODE.md): TCP-Wege l = 1/4, m = voll; UDP zusaetzlich h (m dann halbe Groesse)
    static func layers(path: String) -> [Layer] {
        if path == "udp" { return [Layer(rid: "l", scale: 4, kbit: 150), Layer(rid: "m", scale: 2, kbit: 500), Layer(rid: "h", scale: 1, kbit: 1500)] }
        return [Layer(rid: "l", scale: 4, kbit: 150), Layer(rid: "m", scale: 1, kbit: 700)]
    }
    /// Gemeinsame Hoechstrate aller Schichten: nil = keine Grenze (Kamera liefert 30 fps), Energiesparen 15
    static func maxFps(_ fps: Int?) -> Int? { fps.map { max(1, min(30, $0)) } }
    /// Gleiche Werte in allen Schichten? (Bildrate und zeitliche Schichten)
    static func uniform(fps: [Int?], temporal: [Int?]) -> Bool { Set(fps.map { $0 ?? 0 }).count <= 1 && Set(temporal.map { $0 ?? 0 }).count <= 1 }
}
