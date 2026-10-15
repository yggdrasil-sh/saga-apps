import SwiftUI
import ImageIO
import UniformTypeIdentifiers
#if os(macOS)
import AppKit
typealias PlatformView = NSView
#else
import UIKit
typealias PlatformView = UIView
#endif

// Bilder aus der Zwischenablage einsetzen (2.1): Cmd-V und Bearbeiten -> Einsetzen im Seiten-Editor (Bildblock) und in
// der Chat-Eingabe (verschluesselter Anhang). Die Textfelder (NSTextView/UITextView) setzen Bilder nicht selbst ein -
// deshalb faengt "paste:" hier ab: liegt NUR ein Bild (oder eine Bilddatei aus dem Finder) in der Zwischenablage und
// steht das Textfeld in einer Seite oder einem Chat, geht das Bild dorthin. Alles andere (Text!) laeuft unveraendert
// an das Textfeld weiter. Ohne Textfeld im Fokus (Mac): Cmd-V ueber einen Tastatur-Beobachter.

/// Ein eingesetztes Bild (PNG/JPEG/GIF/WebP; TIFF/HEIC/BMP schon umgewandelt)
struct PastedImage: Equatable { var data: Data; var name: String; var mime: String }

@MainActor
enum ImagePaste {
    /// Ziel (Seite oder Chat) mit seiner Flaeche im Fenster: das Textfeld gehoert zum kleinsten Ziel, das es enthaelt
    final class Target {
        weak var anchor: PlatformView?
        var enabled: () -> Bool
        var maxBytes: () -> Int?
        var accept: ([PastedImage]) -> Void
        init(enabled: @escaping () -> Bool, maxBytes: @escaping () -> Int?, accept: @escaping ([PastedImage]) -> Void) {
            self.enabled = enabled; self.maxBytes = maxBytes; self.accept = accept
        }
    }
    private(set) static var targets: [Target] = []
    static func add(_ t: Target) { targets.removeAll { $0.anchor == nil }; targets.append(t) }
    static func remove(_ t: Target) { targets.removeAll { $0 === t || $0.anchor == nil } }

    // MARK: Ziel zum Textfeld

    static func target(for v: PlatformView) -> Target? {
        guard let w = v.window else { return nil }
        #if os(macOS)
        let r = v.visibleRect.isEmpty ? v.bounds : v.visibleRect
        let c = v.convert(NSPoint(x: r.midX, y: r.midY), to: nil)
        #else
        let c = v.convert(CGPoint(x: v.bounds.midX, y: v.bounds.midY), to: nil)
        let lvl = level(v)
        #endif
        return targets.filter { t in
            guard let a = t.anchor, a.window === w, t.enabled() else { return false }
            #if os(iOS)
            guard level(a) == lvl else { return false }        // Textfeld in einem Blatt ueber der Seite: nicht die Seite
            #endif
            return a.convert(a.bounds, to: nil).contains(c)
        }.min { area($0) < area($1) }
    }
    private static func area(_ t: Target) -> CGFloat { t.anchor.map { $0.bounds.width * $0.bounds.height } ?? .infinity }
    #if os(iOS)
    /// Blatt (presented view controller), in dem die Ansicht liegt - nil = Hauptansicht
    private static func level(_ v: UIView) -> ObjectIdentifier? {
        var r: UIResponder? = v
        while let x = r {
            if let vc = x as? UIViewController, let p = vc.presentingViewController, let top = p.presentedViewController { return ObjectIdentifier(top) }
            r = x.next
        }
        return nil
    }
    #endif

    // MARK: Einsetzen

    /// Bild(er) aus der Zwischenablage an das Ziel des Textfelds geben. false = nichts getan (Text einsetzen wie bisher)
    static func handle(from v: PlatformView) -> Bool {
        guard looksLikeImage(), let t = target(for: v), let imgs = read(maxBytes: t.maxBytes()), !imgs.isEmpty else { return false }
        t.accept(imgs); return true
    }
    /// Schnelle Pruefung ohne Inhalt zu lesen (Menue "Einsetzen" freigeben)
    static func canHandle(from v: PlatformView) -> Bool { looksLikeImage() && target(for: v) != nil }

    #if os(macOS)
    static var board: NSPasteboard { .general }
    static func looksLikeImage() -> Bool {
        let types = board.types?.map(\.rawValue) ?? []
        return types.contains("public.file-url") || types.contains { MediaLogic.imageTypes.contains($0) }
    }
    static func read(maxBytes: Int?) -> [PastedImage]? {
        let pb = board
        let types = pb.types?.map(\.rawValue) ?? []
        let urls = (pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        switch MediaLogic.pasteKind(types: types, files: urls.map(\.lastPathComponent), text: pb.string(forType: .string)) {
        case .text: return nil
        case .imageFiles(let idx):
            return idx.compactMap { i in (try? Data(contentsOf: urls[i])).flatMap { normalize($0, name: urls[i].lastPathComponent, maxBytes: maxBytes) } }
        case .imageData(let t):
            let items = pb.pasteboardItems ?? []
            var out: [PastedImage] = []
            for it in items {
                guard let best = MediaLogic.imageTypes.first(where: { it.types.map(\.rawValue).contains($0) }),
                      let d = it.data(forType: NSPasteboard.PasteboardType(best)), let p = normalize(d, name: "bild", maxBytes: maxBytes) else { continue }
                out.append(p)
            }
            if out.isEmpty, let d = pb.data(forType: NSPasteboard.PasteboardType(t)), let p = normalize(d, name: "bild", maxBytes: maxBytes) { out.append(p) }
            return out
        }
    }
    #else
    static func looksLikeImage() -> Bool { UIPasteboard.general.hasImages || UIPasteboard.general.hasURLs }
    static func read(maxBytes: Int?) -> [PastedImage]? {
        let pb = UIPasteboard.general
        let types = Array(Set(pb.types(forItemSet: nil)?.flatMap { $0 } ?? pb.types))
        let urls = (pb.hasURLs ? pb.urls ?? [] : []).filter(\.isFileURL)
        switch MediaLogic.pasteKind(types: types, files: urls.map(\.lastPathComponent), text: pb.hasStrings ? pb.string : nil) {
        case .text: return nil
        case .imageFiles(let idx):
            return idx.compactMap { i in (try? Data(contentsOf: urls[i])).flatMap { normalize($0, name: urls[i].lastPathComponent, maxBytes: maxBytes) } }
        case .imageData:
            var out: [PastedImage] = []
            for i in 0..<pb.numberOfItems {
                let set = IndexSet(integer: i)
                let have = pb.types(forItemSet: set)?.first ?? []
                guard let best = MediaLogic.imageTypes.first(where: { have.contains($0) }),
                      let d = pb.data(forPasteboardType: best, inItemSet: set)?.first, let p = normalize(d, name: "bild", maxBytes: maxBytes) else { continue }
                out.append(p)
            }
            return out
        }
    }
    #endif

    // MARK: Bilddaten: Format und Groesse (ImageIO - gleich auf allen Plattformen)

    /// PNG/JPEG/GIF/WebP bleiben, TIFF/BMP -> PNG (Bildschirmfotos, Transparenz), HEIC u. a. -> JPEG.
    /// Groesser als maxBytes (Chat-Grenze): verkleinert als JPEG (GIF bleibt - sonst ginge die Animation verloren).
    nonisolated static func normalize(_ raw: Data, name: String, maxBytes: Int?) -> PastedImage? {
        var data = raw
        var fmt = MediaLogic.sniff(raw)
        if fmt == nil {
            guard let src = CGImageSourceCreateWithData(raw as CFData, nil), let type = CGImageSourceGetType(src) as String? else { return nil }
            let png = type == UTType.tiff.identifier || type == UTType.bmp.identifier
            guard let d = encode(raw, type: png ? UTType.png : UTType.jpeg, maxPixel: nil) else { return nil }
            data = d; fmt = png ? ("image/png", "png") : ("image/jpeg", "jpg")
        }
        if let maxBytes, data.count > maxBytes, fmt?.mime != "image/gif" {
            var side = Double(longSide(data) ?? 4000)
            var f = MediaLogic.shrinkFactor(bytes: data.count, limit: maxBytes)
            for _ in 0..<5 {
                guard let d = encode(data, type: .jpeg, maxPixel: max(64, Int(side * f))) else { break }
                if d.count <= maxBytes { data = d; fmt = ("image/jpeg", "jpg"); break }
                side *= f; f = 0.75
            }
        }
        guard let fmt else { return nil }
        return PastedImage(data: data, name: MediaLogic.rename(name, ext: fmt.ext), mime: fmt.mime)
    }
    nonisolated static func longSide(_ d: Data) -> Int? {
        guard let src = CGImageSourceCreateWithData(d as CFData, nil),
              let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = p[kCGImagePropertyPixelWidth] as? Int, let h = p[kCGImagePropertyPixelHeight] as? Int else { return nil }
        return max(w, h)
    }
    /// Bild neu schreiben (Ausrichtung wird angewendet); maxPixel = lange Kante
    nonisolated static func encode(_ d: Data, type: UTType, maxPixel: Int?) -> Data? {
        guard let src = CGImageSourceCreateWithData(d as CFData, nil) else { return nil }
        let side = maxPixel ?? longSide(d) ?? 8192
        let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                     kCGImageSourceThumbnailMaxPixelSize: side]
        guard let img = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        let out = NSMutableData()
        guard let dst = CGImageDestinationCreateWithData(out, type.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dst, img, (type == .jpeg ? [kCGImageDestinationLossyCompressionQuality: 0.85] : [:]) as CFDictionary)
        return CGImageDestinationFinalize(dst) ? out as Data : nil
    }
}

// MARK: - Einhaken in "paste:" der Textfelder (einmal beim Start)

extension ImagePaste {
    private static var installed = false
    /// Methode ersetzen; fehlt sie in der Klasse selbst (geerbt), wird sie dort angelegt - die Oberklasse bleibt unberuehrt
    private static func swap(_ cls: AnyClass, _ orig: Selector, _ mine: Selector) {
        guard let m1 = class_getInstanceMethod(cls, orig), let mm = class_getInstanceMethod(cls, mine) else { return }
        class_addMethod(cls, mine, method_getImplementation(mm), method_getTypeEncoding(mm))   // eigene Kopie (sonst traefe der Tausch die Oberklasse)
        guard let m2 = class_getInstanceMethod(cls, mine) else { return }
        if class_addMethod(cls, orig, method_getImplementation(m2), method_getTypeEncoding(m2)) {
            class_replaceMethod(cls, mine, method_getImplementation(m1), method_getTypeEncoding(m1))
        } else { method_exchangeImplementations(m1, m2) }
    }
    static func install() {
        guard !installed else { return }
        installed = true
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "hkPasteNoHook") { debugTest(); return }   // nur fuer Tests: Verhalten ohne Einhaken
        #endif
        #if os(macOS)
        swap(NSTextView.self, #selector(NSText.paste(_:)), #selector(NSTextView.hkPaste(_:)))
        swap(NSTextView.self, NSSelectorFromString("validateMenuItem:"), #selector(NSTextView.hkValidateMenuItem(_:)))
        swap(NSTextView.self, NSSelectorFromString("validateUserInterfaceItem:"), #selector(NSTextView.hkValidateUserInterfaceItem(_:)))
        // Cmd-V ohne Textfeld im Fokus (z. B. Chat offen, nichts angeklickt): niemand nimmt "paste:" -> Bild ans Ziel unter der Maus
        NSEvent.addLocalMonitorForEvents(matching: .keyDown) { e in
            guard e.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command, e.charactersIgnoringModifiers?.lowercased() == "v",
                  let w = e.window ?? NSApp.keyWindow, !(w.firstResponder is NSText),
                  NSApp.target(forAction: #selector(NSText.paste(_:))) == nil, looksLikeImage() else { return e }
            let mouse = w.mouseLocationOutsideOfEventStream
            let mine = targets.filter { $0.anchor?.window === w && $0.enabled() }
            guard let t = mine.filter({ $0.anchor.map { $0.convert($0.bounds, to: nil).contains(mouse) } ?? false }).min(by: { area($0) < area($1) })
                    ?? mine.max(by: { area($0) < area($1) }),
                  let imgs = read(maxBytes: t.maxBytes()), !imgs.isEmpty else { return e }
            t.accept(imgs); return nil
        }
        #else
        for c in [UITextView.self, UITextField.self] as [AnyClass] {
            swap(c, #selector(UIResponder.paste(_:)), #selector(UIResponder.hkPaste(_:)))
            swap(c, #selector(UIResponder.canPerformAction(_:withSender:)), #selector(UIResponder.hkCanPerformAction(_:withSender:)))
        }
        #endif
        #if DEBUG
        debugTest()
        #endif
    }
}

#if os(macOS)
extension NSTextView {
    @objc func hkPaste(_ sender: Any?) {
        if isEditable, ImagePaste.handle(from: self) { return }
        hkPaste(sender)                                              // = urspruengliches paste:
    }
    @objc func hkValidateMenuItem(_ item: NSMenuItem) -> Bool {
        if item.action == #selector(NSText.paste(_:)), isEditable, ImagePaste.canHandle(from: self) { return true }
        return hkValidateMenuItem(item)
    }
    @objc func hkValidateUserInterfaceItem(_ item: NSValidatedUserInterfaceItem) -> Bool {
        if item.action == #selector(NSText.paste(_:)), isEditable, ImagePaste.canHandle(from: self) { return true }
        return hkValidateUserInterfaceItem(item)
    }
}
#else
extension UIResponder {
    /// nur fuer UITextView/UITextField eingehakt (siehe install)
    @objc func hkPaste(_ sender: Any?) {
        if let v = self as? UIView, Self.hkEditable(v), ImagePaste.handle(from: v) { return }
        hkPaste(sender)
    }
    @objc func hkCanPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(UIResponder.paste(_:)), let v = self as? UIView, Self.hkEditable(v), ImagePaste.canHandle(from: v) { return true }
        return hkCanPerformAction(action, withSender: sender)
    }
    private static func hkEditable(_ v: UIView) -> Bool {
        if let t = v as? UITextView { return t.isEditable }
        if let f = v as? UITextField { return f.isEnabled }
        return false
    }
}
#endif

// MARK: - Ziel in SwiftUI: unsichtbare Ansicht im Hintergrund (liefert Fenster und Flaeche)

struct ImagePasteAnchor {
    var enabled: () -> Bool
    var maxBytes: () -> Int?
    var accept: ([PastedImage]) -> Void
    final class Coordinator { var target: ImagePaste.Target? }
    func makeCoordinator() -> Coordinator { Coordinator() }
    @MainActor fileprivate func make(_ c: Coordinator) -> PlatformView {
        let v = AnchorView()
        let t = ImagePaste.Target(enabled: enabled, maxBytes: maxBytes, accept: accept)
        t.anchor = v; c.target = t; ImagePaste.add(t)
        return v
    }
    @MainActor fileprivate func update(_ c: Coordinator) {
        c.target?.enabled = enabled; c.target?.maxBytes = maxBytes; c.target?.accept = accept
    }
    #if os(macOS)
    final class AnchorView: NSView { override func hitTest(_ point: NSPoint) -> NSView? { nil } }
    #else
    final class AnchorView: UIView {
        override init(frame: CGRect) { super.init(frame: frame); isUserInteractionEnabled = false; isAccessibilityElement = false }
        required init?(coder: NSCoder) { fatalError() }
    }
    #endif
}
#if os(macOS)
extension ImagePasteAnchor: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { make(context.coordinator) }
    func updateNSView(_ v: NSView, context: Context) { update(context.coordinator) }
    static func dismantleNSView(_ v: NSView, coordinator: Coordinator) { if let t = coordinator.target { ImagePaste.remove(t) } }
}
#else
extension ImagePasteAnchor: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView { make(context.coordinator) }
    func updateUIView(_ v: UIView, context: Context) { update(context.coordinator) }
    static func dismantleUIView(_ v: UIView, coordinator: Coordinator) { if let t = coordinator.target { ImagePaste.remove(t) } }
}
#endif

extension View {
    /// Bilder aus der Zwischenablage hierher (Textfelder in dieser Ansicht, Cmd-V/Einsetzen)
    func imagePasteTarget(enabled: @escaping () -> Bool = { true }, maxBytes: @escaping () -> Int? = { nil },
                          accept: @escaping ([PastedImage]) -> Void) -> some View {
        background(ImagePasteAnchor(enabled: enabled, maxBytes: maxBytes, accept: accept).allowsHitTesting(false).accessibilityHidden(true))
    }
}

#if DEBUG
// MARK: - Nur fuer Tests: -hkPasteTest <sekunden> loest im Textfeld mit Fokus "paste:" aus (wie Cmd-V/Einsetzen),
// ohne Tastatur/Maus. iOS: -hkPasteImage YES legt vorher ein kleines PNG in die (Simulator-)Zwischenablage.
extension ImagePaste {
    static func debugTest() {
        guard let sec = Double(UserDefaults.standard.string(forKey: "hkPasteTest") ?? "") else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + sec) { MainActor.assumeIsolated { runDebugTest() } }
    }
    private static func log(_ s: String) { print("PASTE-TEST: " + s); fflush(stdout) }
    private static func runDebugTest() {
        #if os(macOS)
        let tv = NSApp.windows.compactMap { $0.firstResponder as? NSTextView }.first
        if UserDefaults.standard.bool(forKey: "hkPasteKey"), tv == nil, let w = targets.first?.anchor?.window,    // Cmd-V ohne Textfeld im Fokus
           let e = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: .command, timestamp: ProcessInfo.processInfo.systemUptime,
                                    windowNumber: w.windowNumber, context: nil, characters: "v", charactersIgnoringModifiers: "v", isARepeat: false, keyCode: 9) {
            log("Cmd-V ohne Fokus: ziele=\(targets.count) bild=\(looksLikeImage())"); NSApp.postEvent(e, atStart: false); return
        }
        guard let tv else { log("kein Textfeld im Fokus; Ziele=\(targets.count)"); return }
        let item = NSMenuItem(title: "", action: #selector(NSText.paste(_:)), keyEquivalent: "")
        let before = tv.string
        log("textfeld=\(type(of: tv)) editierbar=\(tv.isEditable) ziele=\(targets.count) ziel=\(target(for: tv) != nil) bild=\(looksLikeImage()) menue=\(tv.validateMenuItem(item))")
        if UserDefaults.standard.bool(forKey: "hkPasteInsert") { tv.insertText("Getippt", replacementRange: tv.selectedRange()) }   // Vergleich: Tippen
        else { NSApp.sendAction(#selector(NSText.paste(_:)), to: tv, from: nil) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { log("text vorher=\(before.count) nachher=\(tv.string.count) eingefuegt=\(tv.string.count - before.count)") }
        #else
        if UserDefaults.standard.bool(forKey: "hkPasteImage") {
            let w = 320, h = 200
            if let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
                ctx.setFillColor(CGColor(red: 0.2, green: 0.45, blue: 0.8, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
                ctx.setFillColor(CGColor(red: 0.95, green: 0.8, blue: 0.3, alpha: 1)); ctx.fillEllipse(in: CGRect(x: 110, y: 50, width: 100, height: 100))
                if let cg = ctx.makeImage(), let png = UIImage(cgImage: cg).pngData() { UIPasteboard.general.setData(png, forPasteboardType: "public.png") }
            }
        }
        if let t = UserDefaults.standard.string(forKey: "hkPasteText") { UIPasteboard.general.string = t }   // Text aus derselben App: ohne Rueckfrage
        func find(_ v: UIView) -> UIView? {
            if v.isFirstResponder, v is UITextView || v is UITextField { return v }
            for s in v.subviews { if let r = find(s) { return r } }
            return nil
        }
        let wins = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        guard let tv = wins.lazy.compactMap({ find($0) }).first else { log("kein Textfeld im Fokus; Ziele=\(targets.count)"); return }
        let txt = { (tv as? UITextView)?.text ?? (tv as? UITextField)?.text ?? "" }
        let before = txt()
        log("textfeld=\(type(of: tv)) ziele=\(targets.count) ziel=\(target(for: tv) != nil) bild=\(looksLikeImage()) menue=\(tv.canPerformAction(#selector(UIResponder.paste(_:)), withSender: nil))")
        tv.paste(nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { log("text vorher=\(before.count) nachher=\(txt().count) eingefuegt=\(txt().count - before.count)") }
        #endif
    }
}
#endif
