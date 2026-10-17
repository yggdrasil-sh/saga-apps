import SwiftUI
import ImageIO
import UniformTypeIdentifiers
#if os(macOS)
import AppKit
#else
import UIKit
#endif

// Bildblock auswaehlen, kopieren, ausschneiden, loeschen (2.1-c). Ein angeklickter/angetippter Bildblock wird ausgewaehlt
// (Rahmen) und bekommt einen unsichtbaren Ersthelfer (first responder): Cmd-C/Cmd-X/Entf und Bearbeiten -> Kopieren/
// Ausschneiden/Einsetzen landen dort - am Mac ueber die Menueleiste, am iPad ueber die Hardware-Tastatur. iPhone:
// Kontextmenue (langes Druecken). Vorher gab es fuer Bildbloecke keinen Empfaenger: die Textfelder nehmen nur Text.
// Inhalt der Zwischenablage: das Bild (fuer andere Programme) + der Block (BlockClipLogic.type).

@MainActor
enum BlockClipboard {
    /// Block als JSON-Woerterbuch (Kopie, unabhaengig vom Editor)
    static func plain(_ b: NSDictionary) -> [String: Any]? {
        guard let d = try? JSONSerialization.data(withJSONObject: b) else { return nil }
        return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
    }
    /// Bildblock in die Zwischenablage. false = Bild nicht ladbar
    static func copy(_ block: [String: Any], note: Int, store: Store) async -> Bool {
        guard let url = BlockClipLogic.imageURL(block), let data = await store.imageData(url) else { return false }
        let fmt = MediaLogic.sniff(data)
        let payload = BlockClipLogic.encode(server: AppConfig.serverString, note: note, blocks: [block], mime: fmt?.mime)
        var reps: [(String, Data)] = []
        if let fmt, let uti = BlockClipLogic.uti(fmt.mime) { reps.append((uti, data)) }
        if fmt?.mime != "image/png" && fmt?.mime != "image/jpeg" && fmt?.mime != "image/gif" {
            // WebP u. a.: zusaetzlich PNG, das jedes Programm einsetzen kann
            if let png = await Task.detached(priority: .userInitiated, operation: { ImagePaste.encode(data, type: .png, maxPixel: nil) }).value { reps.append(("public.png", png)) }
        }
        guard !reps.isEmpty else { return false }
        if let payload { reps.append((BlockClipLogic.type, payload)) }
        write(reps)
        return true
    }
    static func write(_ reps: [(String, Data)]) {
        #if os(macOS)
        let pb = NSPasteboard.general
        pb.clearContents()
        let it = NSPasteboardItem()
        for (t, d) in reps { it.setData(d, forType: NSPasteboard.PasteboardType(t)) }
        pb.writeObjects([it])
        #else
        var item: [String: Any] = [:]
        for (t, d) in reps { item[t] = d }
        UIPasteboard.general.setItems([item], options: [:])
        #endif
    }
    /// Block aus der Zwischenablage (nur der eigene Typ - ohne Inhalt zu lesen gibt es keine Rueckfrage)
    static func hasPayload() -> Bool {
        #if os(macOS)
        return NSPasteboard.general.types?.contains(NSPasteboard.PasteboardType(BlockClipLogic.type)) ?? false
        #else
        return UIPasteboard.general.contains(pasteboardTypes: [BlockClipLogic.type])
        #endif
    }
    static func payload() -> BlockClipLogic.Payload? {
        guard hasPayload() else { return nil }
        #if os(macOS)
        return BlockClipLogic.decode(NSPasteboard.general.data(forType: NSPasteboard.PasteboardType(BlockClipLogic.type)))
        #else
        return BlockClipLogic.decode(UIPasteboard.general.data(forPasteboardType: BlockClipLogic.type))
        #endif
    }
    /// Bilddaten aus der Zwischenablage (bevorzugt im Format des kopierten Blocks)
    static func image(mime: String?) -> Data? {
        let prefer = (mime.flatMap(BlockClipLogic.uti).map { [$0] } ?? []) + MediaLogic.imageTypes
        #if os(macOS)
        for t in prefer { if let d = NSPasteboard.general.data(forType: NSPasteboard.PasteboardType(t)) { return d } }
        #else
        for t in prefer { if let d = UIPasteboard.general.data(forPasteboardType: t) { return d } }
        #endif
        return nil
    }
}

// MARK: - Unsichtbarer Ersthelfer des ausgewaehlten Bildblocks

struct BlockClipResponder {
    let id: String
    let active: Bool
    let ed: PageEditor
    @MainActor fileprivate func configure(_ v: ClipView) {
        v.ed = ed; v.blockID = id
        guard let w = v.window else { return }
        #if os(macOS)
        let first = w.firstResponder === v
        if active && !first { DispatchQueue.main.async { if v.window != nil, v.ed?.selected == v.blockID { w.makeFirstResponder(v) } } }
        if !active && first { w.makeFirstResponder(nil) }
        #else
        _ = w
        if active && !v.isFirstResponder { DispatchQueue.main.async { if v.window != nil, v.ed?.selected == v.blockID { v.becomeFirstResponder() } } }
        if !active && v.isFirstResponder { v.resignFirstResponder() }
        #endif
    }
}

#if os(macOS)
final class ClipView: NSView, NSMenuItemValidation {
    weak var ed: PageEditor?
    var blockID = ""
    override var acceptsFirstResponder: Bool { true }
    override func hitTest(_ point: NSPoint) -> NSView? { nil }                // Klicks gehen an die Seite (Auswahl)
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if let w = window, ed?.selected == blockID, w.firstResponder !== self { w.makeFirstResponder(self) }
    }
    @objc func copy(_ sender: Any?) { ed?.copyBlock(blockID, cut: false) }
    @objc func cut(_ sender: Any?) { ed?.copyBlock(blockID, cut: true) }
    @objc func paste(_ sender: Any?) { _ = ImagePaste.handle(from: self) }
    @objc func delete(_ sender: Any?) { ed?.deleteSelected() }
    override func keyDown(with e: NSEvent) {
        switch e.keyCode {
        case 51, 117: ed?.deleteSelected()                                    // Rueckschritt, Entf
        case 53: ed?.select(nil)                                              // Esc
        default: super.keyDown(with: e)
        }
    }
    func validateMenuItem(_ item: NSMenuItem) -> Bool {
        let editable = ed.map { !$0.locked } ?? false
        switch item.action {
        case #selector(copy(_:)): return ed != nil
        case #selector(cut(_:)), #selector(delete(_:)): return editable
        case #selector(paste(_:)): return editable && ImagePaste.canHandle(from: self)
        default: return false
        }
    }
}
extension BlockClipResponder: NSViewRepresentable {
    func makeNSView(context: Context) -> ClipView { let v = ClipView(); configure(v); return v }
    func updateNSView(_ v: ClipView, context: Context) { configure(v) }
}
#else
final class ClipView: UIView {
    weak var ed: PageEditor?
    var blockID = ""
    override init(frame: CGRect) { super.init(frame: frame); isAccessibilityElement = false; backgroundColor = .clear }
    required init?(coder: NSCoder) { fatalError() }
    override var canBecomeFirstResponder: Bool { true }
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }   // Tippen geht an die Seite (Auswahl)
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil, ed?.selected == blockID, !isFirstResponder { becomeFirstResponder() }
    }
    override func copy(_ sender: Any?) { ed?.copyBlock(blockID, cut: false) }
    override func cut(_ sender: Any?) { ed?.copyBlock(blockID, cut: true) }
    override func paste(_ sender: Any?) { _ = ImagePaste.handle(from: self) }
    override func delete(_ sender: Any?) { ed?.deleteSelected() }
    @objc func deleteKey() { ed?.deleteSelected() }
    @objc func escapeKey() { ed?.select(nil) }
    override var keyCommands: [UIKeyCommand]? {
        [UIKeyCommand(input: "\u{8}", modifierFlags: [], action: #selector(deleteKey)),
         UIKeyCommand(input: UIKeyCommand.inputDelete, modifierFlags: [], action: #selector(deleteKey)),
         UIKeyCommand(input: UIKeyCommand.inputEscape, modifierFlags: [], action: #selector(escapeKey))]
    }
    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        let editable = ed.map { !$0.locked } ?? false
        switch action {
        case #selector(copy(_:)): return ed != nil
        case #selector(cut(_:)), #selector(delete(_:)): return editable
        case #selector(paste(_:)): return editable && ImagePaste.canHandle(from: self)
        default: return false
        }
    }
}
extension BlockClipResponder: UIViewRepresentable {
    func makeUIView(context: Context) -> ClipView { let v = ClipView(); configure(v); return v }
    func updateUIView(_ v: ClipView, context: Context) { configure(v) }
}
#endif

#if DEBUG
// MARK: - Nur fuer Tests: -hkBlockCopyTest YES (mit -hkOpen <seite>): ersten Bildblock auswaehlen, Cmd-C (bzw. mit
// -hkBlockCut YES Cmd-X) wie von der Tastatur, Zwischenablage pruefen; -hkBlockPasteBack YES: danach in den letzten
// Absatz einsetzen (Cmd-V). Ausgabe: Zeilen "BLOCK-TEST ..." auf stdout.
extension BlockClipboard {
    static func log(_ s: String) { print("BLOCK-TEST " + s); fflush(stdout) }
    static func images(_ ed: PageEditor) -> [String] {
        ed.doc.root.compactMap { $0 as? NSDictionary }.filter { $0["type"] as? String == "image" }
            .compactMap { ((($0["data"] as? NSDictionary)?["file"] as? NSDictionary)?["url"] as? String) }
    }
    static func debugTest(_ ed: PageEditor) async {
        let d = UserDefaults.standard
        guard d.bool(forKey: "hkBlockCopyTest"), ed.doc.fetched else { return }
        try? await Task.sleep(for: .seconds(1.5))
        guard let b = ed.doc.root.compactMap({ $0 as? NSMutableDictionary }).first(where: { $0["type"] as? String == "image" }) else { log("kein Bildblock"); return }
        let cut = d.bool(forKey: "hkBlockCut")
        log("vorher bilder=\(images(ed).count)")
        ed.select(b.blockID)                                                   // wie Klick/Tippen auf das Bild
        try? await Task.sleep(for: .seconds(1))
        #if os(macOS)
        let w = NSApp.keyWindow ?? NSApp.windows.first { $0.isVisible }
        let fr = w?.firstResponder
        let item = NSMenuItem(title: "", action: cut ? #selector(ClipView.cut(_:)) : #selector(ClipView.copy(_:)), keyEquivalent: "")
        log("ausgewaehlt=\(ed.selected == b.blockID) ersthelfer=\(fr.map { String(describing: type(of: $0)) } ?? "-") menue=\((fr as? ClipView)?.validateMenuItem(item) ?? false)")
        // Cmd-C/Cmd-X: Eintrag der Menueleiste mit diesem Kurzbefehl suchen und seine Aktion so ausfuehren, wie AppKit es
        // fuer das Schluesselfenster tut (Ersthelfer-Kette). Das Testfenster ist unsichtbar und nie Schluesselfenster.
        let key = cut ? "x" : "c"
        func items(_ m: NSMenu?) -> [NSMenuItem] { (m?.items ?? []).flatMap { [$0] + items($0.submenu) } }
        let mi = items(NSApp.mainMenu).first { $0.keyEquivalent == key && $0.keyEquivalentModifierMask == .command }
        log("menue_eintrag=\(mi?.title ?? "-") aktion=\(mi?.action.map(NSStringFromSelector) ?? "-")")
        if let a = mi?.action { log("ausgefuehrt=\(fr?.tryToPerform(a, with: mi) ?? false)") }
        #else
        func find(_ v: UIView) -> UIView? { if v.isFirstResponder { return v }; for s in v.subviews { if let r = find(s) { return r } }; return nil }
        let wins = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        let fr = wins.lazy.compactMap { find($0) }.first
        let act = cut ? #selector(UIResponderStandardEditActions.cut(_:)) : #selector(UIResponderStandardEditActions.copy(_:))
        log("ausgewaehlt=\(ed.selected == b.blockID) ersthelfer=\(fr.map { String(describing: type(of: $0)) } ?? "-") menue=\(fr?.canPerformAction(act, withSender: nil) ?? false)")
        log("aktion=\(cut ? "cut" : "copy") gesendet=\(UIApplication.shared.sendAction(act, to: nil, from: nil, for: nil))")   // wie Cmd-C/X der Hardware-Tastatur
        #endif
        try? await Task.sleep(for: .seconds(2))
        #if os(macOS)
        let types = NSPasteboard.general.types?.map(\.rawValue) ?? []
        let asImage = NSImage(pasteboard: NSPasteboard.general).map { "\(Int($0.size.width))x\(Int($0.size.height))" } ?? "-"
        #else
        let types = UIPasteboard.general.types
        let asImage = UIPasteboard.general.image.map { "\(Int($0.size.width * $0.scale))x\(Int($0.size.height * $0.scale))" } ?? "-"
        #endif
        let p = payload()
        log("zwischenablage typen=\(types.sorted().joined(separator: ",")) als_bild=\(asImage) block=\(p != nil) seite=\(p?.note == ed.doc.id) bilder_jetzt=\(images(ed).count)")
        guard d.bool(forKey: "hkBlockPasteBack") else { return }
        // in den letzten Absatz einsetzen (wie Klick in den Text + Cmd-V)
        guard let last = ed.doc.root.compactMap({ $0 as? NSMutableDictionary }).last(where: { $0["type"] as? String == "paragraph" }) else { log("kein Absatz"); return }
        ed.focus = last.blockID
        try? await Task.sleep(for: .seconds(1.2))
        #if os(macOS)
        let tv = NSApp.windows.compactMap { $0.firstResponder as? NSTextView }.first
        log("einsetzen textfeld=\(tv != nil)")
        if let tv { NSApp.sendAction(#selector(NSText.paste(_:)), to: tv, from: nil) }
        #else
        let tv = wins.lazy.compactMap { find($0) }.first
        log("einsetzen textfeld=\(tv.map { String(describing: type(of: $0)) } ?? "-")")
        tv?.paste(nil)
        #endif
        try? await Task.sleep(for: .seconds(1.5))
        let after = images(ed)
        let order = ed.doc.root.compactMap { ($0 as? NSDictionary)?["type"] as? String }
        log("nach_einsetzen bilder=\(after.count) gleiche_datei=\(after.contains(BlockClipLogic.imageURL(plain(b) ?? [:]) ?? "?")) reihenfolge=\(order.joined(separator: ","))")
    }
}
#endif
