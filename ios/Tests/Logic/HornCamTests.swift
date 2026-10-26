import Foundation

// 2.5 Fehler 26.10.: 1:1-Videoanruf ueber den Knoten ohne Bild - Kamera-Schichten muessen gleiche Bildrate und gleiche
// zeitliche Schichten haben (libwebrtc M150 kodierte 720p sonst nicht); Energiesparen begrenzt alle gemeinsam
func hornCamTests() {
    for path in ["tcp", "turn", "udp"] {
        let l = HornCamPlan.layers(path: path)
        check(l.map(\.rid) == (path == "udp" ? ["l", "m", "h"] : ["l", "m"]), "Schichten \(path)")
        check(l.first?.scale == 4 && l.last?.scale == 1, "kleinste 1/4, groesste voll (\(path))")
        check(HornCamPlan.uniform(fps: l.map { _ in HornCamPlan.maxFps(nil) }, temporal: l.map { _ in HornCamPlan.temporalLayers }),
              "gleiche Werte in allen Schichten (\(path))")
    }
    check(HornCamPlan.layers(path: "tcp").map(\.kbit) == [150, 700] && HornCamPlan.layers(path: "udp").map(\.kbit) == [150, 500, 1500], "Raten wie NODE.md")
    check(HornCamPlan.temporalLayers == 3, "T3 in allen Schichten (Knoten nimmt fuer Kacheln die Bildrate ueber tid)")
    check(HornCamPlan.maxFps(nil) == nil, "ohne Energiesparen: keine Bildratengrenze")
    check(HornCamPlan.maxFps(15) == 15 && HornCamPlan.maxFps(99) == 30 && HornCamPlan.maxFps(0) == 1, "gemeinsame Grenze")
    // der alte Plan (l 20 fps/T2, m 30 fps/T3) gilt als ungleich - genau der kodierte nichts
    check(!HornCamPlan.uniform(fps: [20, 30], temporal: [2, 3]), "alter Plan: ungleich")
    check(!HornCamPlan.uniform(fps: [30, 30], temporal: [2, 3]), "nur zeitliche Schichten ungleich")
    check(!HornCamPlan.uniform(fps: [20, 30], temporal: [3, 3]), "nur Bildrate ungleich")
    check(HornCamPlan.uniform(fps: [15, 15, 15], temporal: [3, 3, 3]), "Energiesparen: gleich")
}
