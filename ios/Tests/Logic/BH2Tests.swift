import Foundation
import CoreGraphics

// Fehlerrunde nach Bughunt 2 (Bericht apple.md)
func bh2Tests() {
    typealias L = CallLogic
    // [S2] Auflegen waehrend "Verbinde ...": nach der POST-Antwort nicht mehr klingeln lassen
    check(L.dialStillWanted(ending: false, preparing: true, sameCall: true), "Anruf normal: weiter klingeln")
    check(!L.dialStillWanted(ending: true, preparing: false, sameCall: true), "waehrend Verbinde aufgelegt: kein Klingeln")
    check(!L.dialStillWanted(ending: true, preparing: true, sameCall: true), "ending allein reicht")
    check(!L.dialStillWanted(ending: false, preparing: false, sameCall: true), "Phase schon idle (nach Ende): kein Klingeln")
    check(!L.dialStillWanted(ending: false, preparing: true, sameCall: false), "inzwischen neuer Anruf: alten nicht klingeln lassen")

    // [S4] CallKit: auf anderem Geraet angenommen -> answeredElsewhere (nicht remoteEnded = "verpasst")
    check(L.systemEnd(.elsewhere, failed: false) == .answeredElsewhere, "anderswo angenommen")
    check(L.systemEnd(L.endNote(reason: "timeout", role: "callee", connectedFor: nil), failed: false) == .remoteEnded, "verpasst bleibt remoteEnded")
    check(L.systemEnd(.ended(dur: 12), failed: false) == .remoteEnded, "normal beendet")
    check(L.systemEnd(nil, failed: true) == .failed && L.systemEnd(.elsewhere, failed: true) == .failed, "Fehler hat Vorrang")

    // [S3] Mac: Anruf-Fenster nicht bei jedem Zustandswechsel nach vorn
    check(L.macOpensCallWindow(from: "idle", to: "preparing"), "eigener Anruf: Fenster auf")
    check(L.macOpensCallWindow(from: "idle", to: "incoming"), "es klingelt: Fenster auf")
    check(L.macOpensCallWindow(from: "ended", to: "incoming"), "klingelt kurz nach dem Ende: Fenster auf")
    check(L.macOpensCallWindow(from: "incoming", to: "connecting"), "angenommen: Fenster nach vorn")
    for (a, b) in [("preparing", "ringingOut"), ("ringingOut", "connecting"), ("connecting", "active"), ("active", "ended"),
                   ("ended", "idle"), ("incoming", "ended"), ("incoming", "idle"), ("idle", "idle")] {
        check(!L.macOpensCallWindow(from: a, to: b), "kein Fenster bei \(a) -> \(b)")
    }
}

// [S4] Profilbild aus transparentem PNG: transparente Flaechen weiss statt schwarz
func bh2AvatarTests() {
    let w = 8
    guard let ctx = CGContext(data: nil, width: w, height: w, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { check(false, "Testbild"); return }
    ctx.clear(CGRect(x: 0, y: 0, width: w, height: w))                                   // ganz transparent
    ctx.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: w / 2, height: w))   // linke Haelfte rot
    guard let src = ctx.makeImage(), let out = AvatarLogic.flatten(src, side: 16), let px = pixels(out) else { check(false, "flatten"); return }
    func at(_ x: Int, _ y: Int) -> (UInt8, UInt8, UInt8) { let i = (y * 16 + x) * 4; return (px[i], px[i + 1], px[i + 2]) }
    check(at(14, 8) == (255, 255, 255), "transparent -> weiss (war schwarz)")
    let red = at(2, 8)
    check(red.0 > 230 && red.2 < 30, "deckende Flaeche bleibt rot \(red)")      // Farbraum-Umrechnung erlaubt kleine Abweichung
    check(out.width == 16 && out.height == 16, "Groesse")
}
private func pixels(_ img: CGImage) -> [UInt8]? {
    var buf = [UInt8](repeating: 0, count: img.width * img.height * 4)
    guard let c = CGContext(data: &buf, width: img.width, height: img.height, bitsPerComponent: 8, bytesPerRow: img.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    c.draw(img, in: CGRect(x: 0, y: 0, width: img.width, height: img.height))
    return buf
}
