import Foundation
#if os(macOS)
import AppKit

/// Nur fuer Tests (Debug-Build, Startparameter -hkSnapshot <pfad.png>): Fenster unsichtbar (Alpha 0, ohne Dock-Symbol
/// und Menueleisten-Eintrag) und nach -hkSnapshotDelay Sekunden als Bild sichern - stoert den Bildschirm nicht.
/// -hkSnapshotSize 1280x800 (Fenstergroesse), -hkAppearance dark|light, -hkSnapshotQuit YES (danach beenden).
enum SnapshotHelper {
    static var active: Bool {
        #if DEBUG
        return UserDefaults.standard.string(forKey: "hkSnapshot") != nil
        #else
        return false
        #endif
    }

    #if DEBUG
    @MainActor static func setUp() {
        guard let out = UserDefaults.standard.string(forKey: "hkSnapshot") else { return }
        NSApp.setActivationPolicy(.accessory)
        if let a = UserDefaults.standard.string(forKey: "hkAppearance") { NSApp.appearance = NSAppearance(named: a == "dark" ? .darkAqua : .aqua) }
        let size: NSSize = {
            let p = (UserDefaults.standard.string(forKey: "hkSnapshotSize") ?? "1280x820").split(separator: "x").compactMap { Double($0) }
            return p.count == 2 ? NSSize(width: p[0], height: p[1]) : NSSize(width: 1280, height: 820)
        }()
        var seen = Set<ObjectIdentifier>()
        Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { _ in
            MainActor.assumeIsolated {
                for w in NSApp.windows where !seen.contains(ObjectIdentifier(w)) && w.className != "NSStatusBarWindow" {
                    seen.insert(ObjectIdentifier(w))
                    hide(w)
                    if w.isVisible && w.contentViewController != nil && w.attachedSheet == nil && w.sheetParent == nil { w.setContentSize(size); park(w) }
                }
            }
        }
        let delay = max(2, UserDefaults.standard.double(forKey: "hkSnapshotDelay"))
        DispatchQueue.main.asyncAfter(deadline: .now() + delay - 1) {        // Groesse kurz vor dem Bild noch einmal festlegen
            MainActor.assumeIsolated {
                for w in NSApp.windows where w.contentViewController != nil && w.sheetParent == nil && w.className != "NSStatusBarWindow" {
                    if w.styleMask.contains(.fullScreen) { w.toggleFullScreen(nil) }
                    w.setFrame(w.frameRect(forContentRect: NSRect(origin: w.frame.origin, size: size)), display: true); park(w)
                }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            MainActor.assumeIsolated {
                shoot(to: out)
                if UserDefaults.standard.bool(forKey: "hkSnapshotQuit") { NSApp.terminate(nil) }
            }
        }
    }
    @MainActor private static func hide(_ w: NSWindow) {
        // Verdeckte/unsichtbare Fenster zeichnen keine Aenderungen mehr. Deshalb: fast durchsichtig (1 %), nicht anklickbar,
        // und so weit links unten aus dem Bild geschoben, dass nur eine 2-pt-Ecke auf dem Bildschirm liegt.
        w.alphaValue = 0.01; w.ignoresMouseEvents = true; w.hasShadow = false; w.level = .statusBar
        w.collectionBehavior.insert([.transient, .ignoresCycle, .canJoinAllSpaces])
        park(w)
    }
    @MainActor static func park(_ w: NSWindow) {
        guard let s = NSScreen.screens.first?.frame else { return }
        w.setFrameOrigin(NSPoint(x: s.minX - w.frame.width + 2, y: s.minY - w.frame.height + 2))
    }
    /// Bild eines Fensters (samt Titelleiste/Werkzeugleiste). Vibrante Flaechen (Seitenleiste) zeichnet cacheDisplay
    /// nicht - fuer das Bild werden sie auf "innerhalb des Fensters, inaktiv" gestellt.
    @MainActor static func image(_ w: NSWindow) -> NSBitmapImageRep? {
        guard let v = w.contentView?.superview ?? w.contentView else { return nil }
        func calm(_ x: NSView) {
            if let e = x as? NSVisualEffectView { e.blendingMode = .withinWindow; e.state = .active; e.material = .sidebar }
            x.subviews.forEach(calm)
        }
        // verdeckte Fenster zeichnen Tabellenzeilen nicht von selbst -> Zeilen anlegen und zeichnen lassen
        func rows(_ x: NSView) {
            if let t = x as? NSTableView, t.numberOfRows > 0 {
                let vis = t.rows(in: t.visibleRect.isEmpty ? t.bounds : t.visibleRect)
                for r in vis.location..<min(t.numberOfRows, vis.location + max(vis.length, 1)) {
                    _ = t.rowView(atRow: r, makeIfNecessary: true)
                    for c in 0..<t.numberOfColumns { _ = t.view(atColumn: c, row: r, makeIfNecessary: true) }
                }
            }
            x.subviews.forEach(rows)
        }
        calm(v); rows(v); v.layoutSubtreeIfNeeded(); v.display()
        guard let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds) else { return nil }
        v.cacheDisplay(in: v.bounds, to: rep)
        return rep
    }
    /// Hauptfenster + ggf. Blatt (mittig oben darueber) als PNG
    @MainActor static func shoot(to path: String) {
        guard let main = NSApp.windows.filter({ $0.contentViewController != nil && $0.sheetParent == nil && $0.className != "NSStatusBarWindow" })
                .max(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }),
              let base = image(main) else { return }
        let W = base.pixelsWide, H = base.pixelsHigh
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: W, pixelsHigh: H, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                                         isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
              let gc = NSGraphicsContext(bitmapImageRep: rep) else { return }
        NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = gc
        gc.imageInterpolation = .high
        base.draw(in: NSRect(x: 0, y: 0, width: W, height: H))
        if let sheet = main.attachedSheet, let s = image(sheet) {
            let scale = CGFloat(W) / main.frame.width
            let sw = CGFloat(s.pixelsWide), sh = CGFloat(s.pixelsHigh)
            let r = NSRect(x: (CGFloat(W) - sw) / 2, y: CGFloat(H) - sh - 28 * scale, width: sw, height: sh)
            NSColor.black.withAlphaComponent(0.18).setFill(); NSRect(x: 0, y: 0, width: W, height: H).fill(using: .sourceOver)
            s.draw(in: r)
        }
        NSGraphicsContext.restoreGraphicsState()
        guard let png = rep.representation(using: .png, properties: [:]) else { return }
        try? png.write(to: URL(fileURLWithPath: path))
    }
    #endif
}
#else
enum SnapshotHelper { static var active: Bool { false } }
#endif
