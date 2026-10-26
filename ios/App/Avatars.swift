import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import ImageIO
#if os(iOS)
import UIKit
#else
import AppKit
#endif

// Profilbilder (Saga 2.1): Personen-Objekte haben ein Feld `avatar` (relative Adresse auf dem EIGENEN Server oder nil).
// Bild nur von Saga laden (nie Gravatar/Anmeldedienst/andere Instanzen), mit Sitzung, WebP bevorzugt; 404 = Initialen.
// Zwischenspeicher: Speicher je Adresse (inkl. ?v=) + URLCache (ETag/Cache-Control vom Server). Logik: AccountLogic.swift.

extension Image {
    init(platformImage i: PlatformImage) {
        #if os(iOS)
        self.init(uiImage: i)
        #else
        self.init(nsImage: i)
        #endif
    }
}

@MainActor
final class AvatarCache: ObservableObject {
    static let shared = AvatarCache()
    /// Live-Ereignis avatar.changed: fester Pfad -> neue Adresse (nil = Bild entfernt); AvatarView zeigt sofort das neue Bild
    @Published private(set) var changes: [String: String?] = [:]
    weak var store: Store?
    private let mem = NSCache<NSString, PlatformImage>()
    /// 404/kein Bild: nicht erneut versuchen, bis sich die Adresse (v) aendert oder revalidiert wird
    private var missing = Set<String>()
    /// letzter Abgleich mit dem Server bzw. naechster Versuch nach einem voruebergehenden Fehler (je Adresse)
    private var checked: [String: Date] = [:]
    private var retry: [String: Date] = [:]
    private var inflight: [String: Task<PlatformImage?, Never>] = [:]

    func cached(_ avatar: String?, px: Int) -> PlatformImage? {
        guard let u = AvatarLogic.url(server: AppConfig.serverString, avatar: avatar, px: px) else { return nil }
        return mem.object(forKey: u.absoluteString as NSString)
    }
    /// Frisch (Bild oder "fehlt" juenger als 10 min)? Sonst beim Anzeigen still beim Server nachfragen.
    func isFresh(_ avatar: String?, px: Int) -> Bool {
        guard let u = AvatarLogic.url(server: AppConfig.serverString, avatar: avatar, px: px) else { return true }
        let k = u.absoluteString
        return AvatarLogic.fresh(checked: checked[k], retry: retry[k], now: Date())
    }
    func image(_ avatar: String?, px: Int) async -> PlatformImage? {
        guard AppConfig.hasServer, let store, let u = AvatarLogic.url(server: AppConfig.serverString, avatar: avatar, px: px) else { return nil }
        let key = u.absoluteString
        let old = mem.object(forKey: key as NSString)
        // Frisch heisst nur "nicht beim Server nachfragen" - NICHT "kein Bild": iOS leert den NSCache von selbst (Hintergrund,
        // Speicherdruck). Ist das Bild dort weg, wird es neu gelesen (meist ohne Netz aus dem URLCache); sonst blieben bis
        // zum Neustart Initialen stehen.
        let revalidate: Bool
        switch AvatarLogic.plan(fresh: AvatarLogic.fresh(checked: checked[key], retry: retry[key], now: Date()),
                                inMemory: old != nil, missing: missing.contains(key), checkedBefore: checked[key] != nil) {
        case .none: return nil
        case .memory: return old
        case .load(let rv): revalidate = rv
        }
        if let t = inflight[key] { return await t.value }
        let t = Task { () -> PlatformImage? in
            var r = await store.avatarData(u, revalidate: revalidate)
            var img = r.flatMap { PlatformImage(data: $0.0) }
            var res = AvatarLogic.fetchResult(code: r?.1, decoded: img != nil)
            if res == .notModified, old == nil {                       // 304 ohne Bild im Speicher: einmal ganz neu laden
                r = await store.avatarData(u, reload: true)
                img = r.flatMap { PlatformImage(data: $0.0) }
                res = AvatarLogic.fetchResult(code: r?.1, decoded: img != nil)
            }
            switch res {
            case .notModified:
                guard let old else {                                   // immer noch nichts: spaeter erneut, kein "fehlt"
                    self.retry[key] = Date().addingTimeInterval(AvatarLogic.retryAfterError)
                    return nil
                }
                self.missing.remove(key); self.checked[key] = Date(); self.retry[key] = nil
                return old
            case .image:
                self.mem.setObject(img!, forKey: key as NSString); self.missing.remove(key)
                self.checked[key] = Date(); self.retry[key] = nil
                return img
            case .missing:
                self.mem.removeObject(forKey: key as NSString); self.missing.insert(key)
                self.checked[key] = Date(); self.retry[key] = nil
                return nil
            case .transient:                                               // offline/Serverfehler: altes Bild behalten, spaeter erneut
                self.retry[key] = Date().addingTimeInterval(AvatarLogic.retryAfterError)
                return old
            }
        }
        inflight[key] = t
        let r = await t.value
        inflight[key] = nil
        return r
    }
    /// avatar.changed: neue Adresse merken, alles zum alten Bild dieser Person verwerfen
    func changed(path: String, avatar: String?) {
        changes[path] = .some(avatar)
        for k in Set(checked.keys).union(retry.keys).union(missing) where URL(string: k)?.path == path {
            mem.removeObject(forKey: k as NSString); missing.remove(k); checked[k] = nil; retry[k] = nil
        }
    }
    /// eigenes Bild geaendert/abgemeldet
    func reset() { mem.removeAllObjects(); missing = []; checked = [:]; retry = [:]; inflight = [:]; changes = [:] }
}

/// Rundes Profilbild mit Initialen (bzw. Gruppen-Symbol) als Platzhalter
struct AvatarView: View {
    let name: String
    var avatar: String?
    var size: CGFloat = 32
    var group = false
    @Environment(\.displayScale) private var scale
    @State private var img: PlatformImage?
    @ObservedObject private var cache = AvatarCache.shared
    /// Adresse nach Live-Aenderungen (avatar.changed) - sonst die aus dem Personen-Objekt
    private var shown: String? { AvatarLogic.current(avatar, changes: cache.changes) }
    var body: some View {
        ZStack {
            Circle().fill(Color.accentColor.opacity(0.16))
            if group { Image(systemName: "person.2.fill").font(.system(size: size * 0.36)) }
            else { Text(verbatim: LiveLogic.initials(name)).font(.system(size: size * 0.4, weight: .semibold)).minimumScaleFactor(0.5) }
            if let img { Image(platformImage: img).resizable().scaledToFill() }
        }
        .frame(width: size, height: size).clipShape(Circle())
        .accessibilityHidden(true)
        .task(id: group ? nil : shown) {
            let px = AvatarLogic.step(points: Double(size), scale: Double(scale)), avatar = shown
            img = group ? nil : AvatarCache.shared.cached(avatar, px: px)
            // fehlt oder aelter als 10 min (z. B. beim Seitenwechsel wieder angezeigt): beim Server nachfragen (ETag -> 304)
            if !group, img == nil || !AvatarCache.shared.isFresh(avatar, px: px) { img = await AvatarCache.shared.image(avatar, px: px) }
        }
    }
}

extension Store {
    /// Profilbild laden (Sitzung bzw. still erneuert); nil = offline
    /// revalidate: am URLCache vorbei beim Server nachfragen (If-None-Match -> 304, sonst neues Bild)
    /// reload: am Zwischenspeicher ganz vorbei (nach einem leeren 304)
    func avatarData(_ url: URL, revalidate: Bool = false, reload: Bool = false) async -> (Data, Int)? {
        let policy: URLRequest.CachePolicy = reload ? .reloadIgnoringLocalCacheData : revalidate ? .reloadRevalidatingCacheData : .useProtocolCachePolicy
        var req = URLRequest(url: url, cachePolicy: policy, timeoutInterval: 20)
        req.httpShouldHandleCookies = false
        req.setValue("image/webp,image/*;q=0.8", forHTTPHeaderField: "Accept")
        return try? await sendQuiet(req)
    }
}

// MARK: - Einstellungen → Konto: eigenes Profilbild

struct AvatarInfo: Equatable {
    var url: String?; var source: AvatarLogic.Source; var gravatar: Bool; var gravatarEnabled: Bool; var hasEmail: Bool; var isPublic: Bool; var maxMB: Int
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        url = ShareJSON.str(j["url"]); source = AvatarLogic.source(ShareJSON.str(j["source"]))
        gravatar = ShareJSON.bool(j["gravatar"]); gravatarEnabled = ShareJSON.bool(j["gravatar_enabled"]); hasEmail = ShareJSON.bool(j["has_email"])
        isPublic = ShareJSON.bool(j["public"]); maxMB = ShareJSON.int(j["max_mb"]) ?? 5
    }
}

struct AvatarSettingsSection: View {
    @EnvironmentObject var store: Store
    /// Zuschnitt: Blatt haengt am Formular (SettingsView) - Blaetter an Zeilen eines Formulars sind unzuverlaessig
    @Binding var crop: CropSource?
    /// Ergebnis des Zuschnitts (vom Formular gesetzt) -> hochladen
    @Binding var cropped: Data?
    @State private var info: AvatarInfo?
    @State private var supported = true
    @State private var pick: PhotosPickerItem?
    @State private var pickFile = false
    @State private var busy = false
    @State private var msg: String?
    @State private var askRemove = false
    var body: some View {
        if supported {
            Section {
                if let info {
                    HStack(spacing: 14) {
                        AvatarView(name: store.accountName ?? "?", avatar: info.url, size: 72).id(info.url ?? "-")
                        Text(sourceText(info)).font(.callout).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                    #if os(iOS)
                    PhotosPicker(selection: $pick, matching: .images) { Label("Bild hochladen …", systemImage: "person.crop.circle.badge.plus") }
                        .disabled(busy)
                    #else
                    Button { pickFile = true } label: { Label("Bild hochladen …", systemImage: "person.crop.circle.badge.plus") }.disabled(busy)
                    #endif
                    if info.source == .upload {
                        Button(role: .destructive) { askRemove = true } label: { Label("Eigenes Bild entfernen", systemImage: "trash").foregroundStyle(.red) }.disabled(busy)
                    }
                    if info.gravatarEnabled {
                        Toggle("Gravatar verwenden, wenn ich kein eigenes Bild habe", isOn: Binding(get: { info.gravatar }, set: { v in Task { await patch(["gravatar": v]) } }))
                    }
                    Toggle("Auch auf meinen veröffentlichten Seiten zeigen", isOn: Binding(get: { info.isPublic }, set: { v in Task { await patch(["public": v]) } }))
                    if let msg { Text(verbatim: msg).font(.footnote).foregroundStyle(.secondary) }
                } else { ProgressView() }
            } header: { Text("Profilbild").id("avatar") } footer: {
                Text("Andere sehen dein Bild bei Kommentaren, im Chat und beim Teilen. Saga lädt Bilder nur vom eigenen Server.")
            }
            .task { await load() }
            .onChange(of: pick) { _, it in
                guard let it else { return }
                Task { if let d = try? await it.loadTransferable(type: Data.self), let c = CropSource(data: d) { crop = c }; pick = nil }
            }
            .fileImporter(isPresented: $pickFile, allowedContentTypes: [.image]) { r in
                guard let u = try? r.get() else { return }
                let ok = u.startAccessingSecurityScopedResource(); defer { if ok { u.stopAccessingSecurityScopedResource() } }
                if let d = try? Data(contentsOf: u), let c = CropSource(data: d) { crop = c }
            }
            .onChange(of: cropped) { _, d in if let d { cropped = nil; Task { await upload(d) } } }
            .alert("Eigenes Bild entfernen?", isPresented: $askRemove) {
                Button("Entfernen", role: .destructive) { Task { await remove() } }
                Button("Abbrechen", role: .cancel) {}
            }
        }
    }
    private func sourceText(_ i: AvatarInfo) -> String {
        switch i.source {
        case .upload: return String(localized: "Angezeigt wird: Eigenes Bild")
        case .sso: return String(localized: "Angezeigt wird: Bild vom Anmeldedienst")
        case .gravatar: return String(localized: "Angezeigt wird: Gravatar")
        case .none: return String(localized: "Kein Bild – es werden deine Initialen angezeigt.")
        }
    }
    private func apply(_ d: Data) {
        guard let i = AvatarInfo(data: d) else { return }
        info = i; store.myAvatar = i.url; AvatarCache.shared.reset()
    }
    private func load() async {
        guard let (d, code) = try? await store.apiResponse("api/account/avatar") else { return }
        if code == 404 || code == 405 { supported = false; return }
        if code == 200 { apply(d) }
        #if DEBUG
        if let p = UserDefaults.standard.string(forKey: "hkAvatarCropTest"), let d = FileManager.default.contents(atPath: p) {   // nur fuer Tests
            try? await Task.sleep(for: .seconds(1.5)); crop = CropSource(data: d)
        }
        #endif
    }
    private func patch(_ body: [String: Any]) async {
        busy = true; defer { busy = false }
        do { apply(try await store.api("api/account/avatar", method: "PATCH", body: body)); msg = nil } catch { msg = error.localizedDescription }
    }
    private func upload(_ jpeg: Data) async {
        busy = true; msg = String(localized: "Wird hochgeladen …"); defer { busy = false }
        do {
            if jpeg.count > (info?.maxMB ?? 5) * 1_048_576 { throw APIError.other(String(localized: "Das Bild ist zu groß.")) }
            apply(try await store.apiRaw("api/account/avatar", method: "PUT", data: jpeg, contentType: "image/jpeg"))
            msg = String(localized: "✓ Gespeichert")
        } catch { msg = error.localizedDescription }
    }
    private func remove() async {
        busy = true; defer { busy = false }
        do { apply(try await store.api("api/account/avatar", method: "DELETE")); msg = nil } catch { msg = error.localizedDescription }
    }
}

/// Bild fuer den Zuschnitt: EXIF-Ausrichtung angewendet, hoechstens 3000 px (ImageIO)
struct CropSource: Identifiable {
    let id = UUID()
    let image: CGImage
    init?(data: Data) {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                                                    kCGImageSourceThumbnailMaxPixelSize: 3000] as CFDictionary) else { return nil }
        image = cg
    }
}

/// Quadratischer Zuschnitt mit Kreis-Vorschau: ziehen = verschieben, Regler/Pinch = zoomen
struct AvatarCropSheet: View {
    let source: CropSource
    let done: (Data) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var zoom: Double = 1
    @State private var offset: (x: Double, y: Double) = (0, 0)
    @State private var dragStart: (x: Double, y: Double)?
    @State private var zoomStart: Double?
    private let side: Double = 280
    private var pixelSize: (w: Double, h: Double) { (Double(source.image.width), Double(source.image.height)) }
    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                let p = pixelSize, s = AvatarLogic.coverScale(image: p.w, p.h, side: side) * zoom
                let o = AvatarLogic.clamp(offset: offset, image: p.w, p.h, side: side, zoom: zoom)
                ZStack {
                    Image(decorative: source.image, scale: 1).resizable().interpolation(.high)
                        .frame(width: p.w * s, height: p.h * s)
                        .offset(x: o.x, y: o.y)
                    Rectangle().fill(.black.opacity(0.45)).reverseMask { Circle().frame(width: side, height: side) }
                    Circle().strokeBorder(.white.opacity(0.9), lineWidth: 2).frame(width: side, height: side)
                }
                .frame(width: side, height: side).clipped().contentShape(Rectangle())
                .gesture(DragGesture()
                    .onChanged { g in let b = dragStart ?? o; if dragStart == nil { dragStart = o }; offset = (b.x + g.translation.width, b.y + g.translation.height) }
                    .onEnded { _ in dragStart = nil; offset = AvatarLogic.clamp(offset: offset, image: p.w, p.h, side: side, zoom: zoom) })
                .simultaneousGesture(MagnifyGesture()
                    .onChanged { g in let b = zoomStart ?? zoom; if zoomStart == nil { zoomStart = zoom }; zoom = min(4, max(1, b * g.magnification)) }
                    .onEnded { _ in zoomStart = nil })
                .accessibilityLabel(Text("Bildausschnitt"))
                HStack {
                    Image(systemName: "minus.magnifyingglass").accessibilityHidden(true)
                    Slider(value: $zoom, in: 1...4) { Text("Zoom") }
                    Image(systemName: "plus.magnifyingglass").accessibilityHidden(true)
                }.frame(maxWidth: 320)
                Text("Ziehen zum Verschieben, Zoom zum Vergrößern.").font(.footnote).foregroundStyle(.secondary)
            }
            .padding()
            .navigationTitle("Ausschnitt")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Übernehmen") { if let j = render() { done(j) }; dismiss() } }
            }
            #if DEBUG
            .task { if UserDefaults.standard.bool(forKey: "hkAvatarCropAuto") { try? await Task.sleep(for: .seconds(3)); zoom = 1.6; try? await Task.sleep(for: .seconds(2)); if let j = render() { done(j) }; dismiss() } }
            #endif
        }
        .macFrame(minWidth: 420, idealWidth: 460, minHeight: 500)
    }
    /// Ausschnitt -> JPEG 512...1024 px (EXIF entfernt der Server)
    private func render() -> Data? {
        let p = pixelSize
        let r = AvatarLogic.cropRect(image: p.w, p.h, side: side, zoom: zoom, offset: offset)
        let out = AvatarLogic.outputSide(r.size)
        let cg = source.image
        guard let piece = cg.cropping(to: CGRect(x: r.x, y: r.y, width: r.size, height: r.size).integral),
              let img = AvatarLogic.flatten(piece, side: out) else { return nil }          // transparent -> weiss
        #if os(iOS)
        return UIImage(cgImage: img).jpegData(compressionQuality: 0.88)
        #else
        return NSBitmapImageRep(cgImage: img).representation(using: .jpeg, properties: [.compressionFactor: 0.88])
        #endif
    }
}

private extension View {
    /// Abdunkeln ausserhalb des Kreises
    func reverseMask<M: View>(@ViewBuilder _ mask: () -> M) -> some View {
        self.mask { Rectangle().overlay { mask().blendMode(.destinationOut) }.compositingGroup() }
    }
}
