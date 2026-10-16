import Foundation
#if os(macOS)
import AppKit

/// Nur fuer Tests (Debug-Build, Startparameter -hkSnapshot <pfad.png>): Fenster unsichtbar (Alpha 0, ohne Dock-Symbol
/// und Menueleisten-Eintrag) und nach -hkSnapshotDelay Sekunden als Bild sichern - stoert den Bildschirm nicht.
/// -hkSnapshotSize 1280x800 (Fenstergroesse), -hkAppearance dark|light, -hkSnapshotQuit YES (danach beenden).
enum SnapshotHelper {
    /// Anker eines Popovers im Fenster (oben links = 0,0): das Fenster liegt fuer das Bild ausserhalb des Bildschirms,
    /// das System schiebt das Popover dann an den Bildschirmrand - im Bild wird es wieder an den Anker gesetzt
    @MainActor static var anchor: CGRect?
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
        activeLook()
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
                    if w.className.contains("Popover") { w.alphaValue = 0.01; w.ignoresMouseEvents = true; continue }   // Lage relativ zum Fenster behalten
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
                    for n in [NSWindow.didBecomeKeyNotification, NSWindow.didBecomeMainNotification] { NotificationCenter.default.post(name: n, object: w) }
                    for sel in ["acquireKeyAppearance", "acquireMainAppearance"] where w.responds(to: NSSelectorFromString(sel)) { w.perform(NSSelectorFromString(sel)) }
                    if let tf = w.contentView?.superview, tf.responds(to: NSSelectorFromString("_windowChangedKeyState")) { tf.perform(NSSelectorFromString("_windowChangedKeyState")) }
                    w.contentView?.superview?.needsDisplay = true
                    // Seitenleiste in ueblicher Breite (gespeicherte Breite aus frueheren Laeufen ignorieren)
                    func split(_ x: NSView) { if let sv = x as? NSSplitView, sv.arrangedSubviews.count > 1, sv.isVertical { sv.setPosition(250, ofDividerAt: 0) } else { x.subviews.forEach(split) } }
                    if let r = w.contentView { split(r) }
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
    /// Fenster sehen fuer das Bild "aktiv" aus (Schluesselfenster), ohne wirklich den Fokus zu nehmen
    @MainActor private static func activeLook() {
        func swap(_ c: AnyClass, _ a: Selector, _ b: Selector) {
            if let m1 = class_getInstanceMethod(c, a), let m2 = class_getInstanceMethod(c, b) { method_exchangeImplementations(m1, m2) }
        }
        swap(NSWindow.self, #selector(getter: NSWindow.isKeyWindow), #selector(getter: NSWindow.hkSnapKey))
        swap(NSWindow.self, #selector(getter: NSWindow.isMainWindow), #selector(getter: NSWindow.hkSnapKey))
        swap(NSApplication.self, #selector(getter: NSApplication.isActive), #selector(getter: NSApplication.hkSnapActive))
        // AppKit zeichnet Titelleiste und Ampel nach dem (internen) "aktiven Erscheinungsbild"
        // Ampel und Titelleiste: interne Abfragen des Erscheinungsbilds immer "aktiv"
        let yes0: @convention(block) (AnyObject) -> Bool = { _ in true }
        let yes1: @convention(block) (AnyObject, AnyObject?) -> Bool = { _, _ in true }
        for (cls, sel, arg) in [("_NSThemeWidgetCell", "_hasKeyAppearanceInView:", true), ("NSThemeFrame", "_currentThemeStateIsActive", false)] {
            if let c = NSClassFromString(cls), let m = class_getInstanceMethod(c, NSSelectorFromString(sel)) {
                class_replaceMethod(c, NSSelectorFromString(sel), arg ? imp_implementationWithBlock(yes1) : imp_implementationWithBlock(yes0), method_getTypeEncoding(m))
            }
        }
        if let m1 = class_getInstanceMethod(NSWindow.self, NSSelectorFromString("_hasActiveAppearanceForStandardWindowButton:")),
           let m2 = class_getInstanceMethod(NSWindow.self, #selector(NSWindow.hkSnapButton(_:))) { method_exchangeImplementations(m1, m2) }
        for name in ["_hasActiveAppearance", "_hasActiveAppearanceIgnoringKeyFocus", "_hasKeyAppearance", "_hasMainAppearance", "hasKeyAppearance", "hasMainAppearance"] {
            let sel = NSSelectorFromString(name)
            if NSWindow.instancesRespond(to: sel) { swap(NSWindow.self, sel, #selector(getter: NSWindow.hkSnapKey)) }
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
        // Hintergrund-Unschaerfe zeichnet das Bild nicht mit -> feste Flaechenfarbe wie die Seitenleiste
        let dark = w.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        let fill = dark ? NSColor(srgbRed: 0.165, green: 0.161, blue: 0.173, alpha: 1) : NSColor(srgbRed: 0.918, green: 0.914, blue: 0.925, alpha: 1)
        func calm(_ x: NSView) {
            if let e = x as? NSVisualEffectView {
                e.blendingMode = .withinWindow; e.state = .active
                e.wantsLayer = true; e.layer?.backgroundColor = fill.cgColor
            }
            x.subviews.forEach(calm)
        }
        for n in [NSWindow.didBecomeKeyNotification, NSWindow.didBecomeMainNotification] { NotificationCenter.default.post(name: n, object: w) }
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
        if UserDefaults.standard.bool(forKey: "hkSnapDump") {
            func dump(_ x: NSView, _ d: Int) {
                print(String(repeating: "  ", count: d) + String(describing: type(of: x)) + " " + NSStringFromRect(x.frame) + (x.isHidden ? " HIDDEN" : "") + " layer=" + String(describing: x.layer.map { type(of: $0) }))
                if d < 14 { x.subviews.forEach { dump($0, d + 1) } }
            }
            dump(v, 0)
        }
        guard let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds) else { return nil }
        v.cacheDisplay(in: v.bounds, to: rep)
        return rep
    }
    /// Seitenleiste in macOS 26+: liegt in einer Glas-Ebene, die das Bild nicht mitzeichnet -> Flaeche + Inhalt einzeln zeichnen
    @MainActor static func drawGlass(_ w: NSWindow, rep: NSBitmapImageRep, scale: CGFloat) {
        guard let root = w.contentView?.superview ?? w.contentView else { return }
        var glass: [NSView] = []
        func find(_ x: NSView) { if String(describing: type(of: x)) == "NSGlassEffectView" { glass.append(x) } else { x.subviews.forEach(find) } }
        find(root)
        let dark = w.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        for g in glass where !g.isHidden && g.frame.width > 20 {
            let r = g.convert(g.bounds, to: root)
            let px = NSRect(x: r.minX * scale, y: r.minY * scale, width: r.width * scale, height: r.height * scale)
            let panel = NSBezierPath(roundedRect: px.insetBy(dx: 8 * scale, dy: 8 * scale), xRadius: 18 * scale, yRadius: 18 * scale)
            (dark ? NSColor(srgbRed: 0.18, green: 0.18, blue: 0.19, alpha: 1) : NSColor(srgbRed: 0.945, green: 0.941, blue: 0.95, alpha: 1)).setFill(); panel.fill()
            (dark ? NSColor.white.withAlphaComponent(0.08) : NSColor.black.withAlphaComponent(0.08)).setStroke(); panel.lineWidth = scale; panel.stroke()
            // Inhalt: erste gewoehnliche Ansicht unterhalb der Glas-Ebene
            var content: NSView? = g.subviews.first?.subviews.first
            if content == nil { content = g.subviews.first }
            guard let c = content, let cr = c.bitmapImageRepForCachingDisplay(in: c.bounds) else { continue }
            c.layoutSubtreeIfNeeded(); c.display(); c.cacheDisplay(in: c.bounds, to: cr)
            let fr = c.convert(c.bounds, to: root)
            NSGraphicsContext.saveGraphicsState(); panel.addClip()
            cr.draw(in: NSRect(x: fr.minX * scale, y: fr.minY * scale, width: fr.width * scale, height: fr.height * scale))
            NSGraphicsContext.restoreGraphicsState()
        }
    }
    /// Bild vom Fensterserver (mit Glas und Unschaerfe). Kurz: ganz aus dem Bild, deckend, aufnehmen, wieder verstecken.
    @MainActor static func serverImage(_ w: NSWindow, move: Bool = true) -> NSBitmapImageRep? {
        typealias F = @convention(c) (CGRect, UInt32, UInt32, UInt32) -> Unmanaged<CGImage>?
        guard let sym = dlsym(dlopen(nil, RTLD_NOW), "CGWindowListCreateImage") else { return nil }
        let f = unsafeBitCast(sym, to: F.self)
        let o = w.frame.origin
        if move, let s = NSScreen.screens.first?.frame { w.setFrameOrigin(NSPoint(x: s.minX - w.frame.width - 40, y: s.minY - w.frame.height - 40)) }
        w.alphaValue = 1
        CATransaction.flush(); w.displayIfNeeded()
        RunLoop.current.run(until: Date().addingTimeInterval(0.15))
        let img = f(.null, 8, UInt32(w.windowNumber), 1 | 8)?.takeRetainedValue()      // nur dieses Fenster, ohne Rand, volle Aufloesung
        w.alphaValue = 0.01; if move { w.setFrameOrigin(o) }
        return img.map { NSBitmapImageRep(cgImage: $0) }
    }
    /// Hauptfenster + ggf. Blatt (mittig oben darueber) als PNG
    @MainActor static func shoot(to path: String) {
        let all = NSApp.windows.filter({ $0.contentViewController != nil && $0.sheetParent == nil && $0.className != "NSStatusBarWindow" })
        let want = UserDefaults.standard.string(forKey: "hkSnapWindow")         // bestimmtes Fenster (z. B. "call")
        guard let main = all.first(where: { w in want.map { w.identifier?.rawValue.contains($0) == true } ?? false })
                ?? all.max(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }) else { return }
        let mainFrame = main.frame
        let pops = NSApp.windows.filter { $0.isVisible && $0.className.contains("Popover") }.map { ($0, $0.frame) }
        if UserDefaults.standard.bool(forKey: "hkSnapDump") { for w in NSApp.windows { print("FENSTER", w.className, w.isVisible, NSStringFromRect(w.frame)) } }
        guard let base = (UserDefaults.standard.bool(forKey: "hkSnapServer") ? serverImage(main) : nil) ?? image(main) else { return }
        let W = base.pixelsWide, H = base.pixelsHigh
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: W, pixelsHigh: H, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                                         isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
              let gc = NSGraphicsContext(bitmapImageRep: rep) else { return }
        NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = gc
        gc.imageInterpolation = .high
        base.draw(in: NSRect(x: 0, y: 0, width: W, height: H))
        if !UserDefaults.standard.bool(forKey: "hkSnapServer") { drawGlass(main, rep: rep, scale: CGFloat(W) / main.frame.width) }
        // Blatt liegt oben mittig im (geparkten) Fenster, also ausserhalb des Bildschirms: ohne Verschieben aufnehmen
        let server = UserDefaults.standard.bool(forKey: "hkSnapServer")
        _ = server
        if let sheet = main.attachedSheet, let s = image(sheet) {           // Blatt ohne Fensterserver (dort hat es weisse Ecken)
            let scale = CGFloat(W) / main.frame.width
            let sw = CGFloat(s.pixelsWide), sh = CGFloat(s.pixelsHigh)
            let r = NSRect(x: (CGFloat(W) - sw) / 2, y: CGFloat(H) - sh - 28 * scale, width: sw, height: sh)
            NSColor.black.withAlphaComponent(0.18).setFill(); NSRect(x: 0, y: 0, width: W, height: H).fill(using: .sourceOver)
            s.draw(in: r)
        }
        // Popover (z. B. Erklaerung "Echtheit nicht bestaetigt") an seiner Stelle relativ zum Fenster
        let scale = CGFloat(W) / mainFrame.width
        // Popover: eigene Flaeche (die echte ist "vibrant" und liegt am Bildschirmrand) + Inhalt, unter den Anker gesetzt
        let dark = UserDefaults.standard.string(forKey: "hkAppearance") == "dark"
        for (p, f) in pops {
            func hosting(_ x: NSView) -> NSView? {
                if String(describing: type(of: x)).contains("HostingView") { return x }
                for v in x.subviews { if let h = hosting(v) { return h } }
                return nil
            }
            guard let root = p.contentView, let c = hosting(root), let cr = c.bitmapImageRepForCachingDisplay(in: c.bounds) else { continue }
            let cf = c.convert(c.bounds, to: nil)                                    // Lage des Inhalts im Popover-Fenster
            c.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            func clear(_ x: NSView) { x.layer?.backgroundColor = nil; if let e = x as? NSVisualEffectView { e.isHidden = true }; x.subviews.forEach(clear) }
            clear(c); var sup = c.superview; while let v = sup { v.layer?.backgroundColor = nil; sup = v.superview }
            c.layoutSubtreeIfNeeded(); c.display(); c.cacheDisplay(in: c.bounds, to: cr)
            if UserDefaults.standard.bool(forKey: "hkSnapDump") {
                var x: NSView? = c; while let v = x { print("POP", type(of: v), v.layer?.backgroundColor as Any, (v as? NSVisualEffectView) != nil); x = v.superview }
            }
            var at = NSPoint(x: f.minX - mainFrame.minX, y: f.minY - mainFrame.minY)
            if let a = anchor { at = NSPoint(x: max(8, a.minX - 12), y: mainFrame.height - a.maxY - f.height - 6) }
            let r = NSRect(x: at.x * scale, y: at.y * scale, width: f.width * scale, height: f.height * scale)
            let box = NSBezierPath(roundedRect: r.insetBy(dx: 8 * scale, dy: 8 * scale), xRadius: 14 * scale, yRadius: 14 * scale)
            NSGraphicsContext.saveGraphicsState()
            let sh = NSShadow(); sh.shadowBlurRadius = 18 * scale; sh.shadowOffset = NSSize(width: 0, height: -4 * scale)
            sh.shadowColor = NSColor.black.withAlphaComponent(dark ? 0.6 : 0.25); sh.set()
            (dark ? NSColor(srgbRed: 0.17, green: 0.17, blue: 0.18, alpha: 1) : NSColor.white).setFill(); box.fill()
            NSGraphicsContext.restoreGraphicsState()
            NSGraphicsContext.saveGraphicsState(); box.addClip()
            cr.draw(in: NSRect(x: r.minX + cf.minX * scale, y: r.minY + cf.minY * scale, width: cf.width * scale, height: cf.height * scale))
            NSGraphicsContext.restoreGraphicsState()
            (dark ? NSColor.white.withAlphaComponent(0.12) : NSColor.black.withAlphaComponent(0.12)).setStroke(); box.lineWidth = scale; box.stroke()
        }
        NSGraphicsContext.restoreGraphicsState()
        guard let png = rep.representation(using: .png, properties: [:]) else { return }
        try? png.write(to: URL(fileURLWithPath: path))
    }
    #endif
}
#if DEBUG
extension NSWindow { @objc var hkSnapKey: Bool { true }; @objc func hkSnapButton(_ b: Int) -> Bool { true } }
extension NSApplication { @objc var hkSnapActive: Bool { true } }
#endif
#else
enum SnapshotHelper { static var active: Bool { false }; @MainActor static var anchor: CGRect? }
#endif
