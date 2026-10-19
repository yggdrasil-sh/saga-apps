import SwiftUI
import PhotosUI
#if os(macOS)
import AppKit
#endif

// MARK: - Titelbild wie Notion: volle Breite; Aendern / Neu positionieren (ziehen) / Entfernen

struct CoverView: View {
    @ObservedObject var doc: PageDoc
    var extendTop: CGFloat = 0                            // iPhone: um Statusleiste + Navigation nach oben verlaengern
    var readOnly = false                                  // fremde Seite mit Lesen/Kommentieren: nichts aendern
    var locked = false                                    // gesperrte Seite: Antippen zeigt einen Hinweis
    /// Breite, in der das Titelbild geladen wird (?w=)
    static let loadWidth = 2000
    @EnvironmentObject var store: Store
    @State private var hover = false
    @State private var repositioning = false
    @State private var pos: Double = 50
    @State private var dragStart: Double?
    @State private var picking = false
    @State private var pick: PhotosPickerItem?
    @State private var immich = false
    @State private var showMenu = false
    #if os(macOS)
    private var showHeightOpen: Bool { showHeight }
    #else
    private let showHeightOpen = false
    #endif
    #if os(iOS)
    private let coverHeight: CGFloat = 190                 // sichtbarer Teil unter der Navigation
    #else
    @AppStorage("hk.coverHeight") private var coverHeight: Double = 200      // Mac: einstellbar (ziehen oder Einstellungen)
    @State private var dragBase: Double?
    @State private var showHeight = false
    #endif

    /// Eben gewaehltes Bild (noch beim Hochladen) oder gespeichertes Titelbild
    @ViewBuilder private var image: some View {
        let v = repositioning ? pos : HeaderLogic.coverPos(doc.page["cover_pos"])
        if let p = doc.coverPreview {
            CoverFill(image: p, ratio: p.size.height > 0 ? p.size.width / p.size.height : 1, vAlign: v)
        } else if let c = doc.cover {
            RemoteImage(path: c, fill: true, vAlign: v, w: Self.loadWidth, spinner: true)
        }
    }

    var body: some View {
        if doc.hasCover {
            image
                .frame(maxWidth: .infinity).frame(height: CGFloat(coverHeight) + extendTop)
                .padding(.top, -extendTop)
                .overlay {
                    if doc.coverBusy {
                        ProgressView().padding(10).background(.regularMaterial, in: Circle())
                            .accessibilityLabel("Wird hochgeladen …")
                    }
                }
                #if os(macOS)
                .overlay(alignment: .bottom) {                    // sichtbarer Griff unten in der Mitte: ziehen = Hoehe aendern
                    if hover || dragBase != nil {
                        Capsule().fill(.regularMaterial).frame(width: 56, height: 8)
                            .overlay(Capsule().stroke(.secondary.opacity(0.5)))
                            .padding(.vertical, 10).padding(.horizontal, 30)          // grosse Trefferflaeche
                            .contentShape(Rectangle())
                            .onHover { inside in if inside { NSCursor.resizeUpDown.push() } else { NSCursor.pop() } }
                            .gesture(DragGesture(minimumDistance: 1)
                                .onChanged { g in
                                    let base = dragBase ?? coverHeight; if dragBase == nil { dragBase = coverHeight }
                                    coverHeight = min(600, max(100, base + Double(g.translation.height)))
                                }
                                .onEnded { _ in dragBase = nil })
                            .help("Ziehen: Höhe des Titelbilds ändern")
                    }
                }
                #endif
                .contentShape(Rectangle())
                // Neu positionieren: Bild ziehen. Nur waehrend des Positionierens - sonst gehoert das Ziehen der Seite (Scrollen)
                .gesture(DragGesture(minimumDistance: 2).onChanged { g in
                    let base = dragStart ?? pos; if dragStart == nil { dragStart = pos }
                    pos = min(100, max(0, base - Double(g.translation.height) / 2))
                }.onEnded { _ in dragStart = nil }, including: repositioning ? .all : .subviews)
                .overlay(alignment: .topTrailing) {
                    if readOnly {
                        EmptyView()
                    } else if repositioning {
                        HStack(spacing: 8) {
                            Text("Bild ziehen zum Positionieren").font(.caption)
                            Button("Speichern") {
                                let p = pos; repositioning = false; showMenu = false       // sofort an der neuen Stelle
                                Task { await doc.setHeader(["cover_pos": p], store) }
                            }.buttonStyle(.borderedProminent)
                            Button("Abbrechen") { repositioning = false }
                        }
                        .padding(8).background(.regularMaterial, in: Capsule()).padding(10)
                    } else if (hover || showMenu || showHeightOpen) && !doc.coverBusy {
                        HStack(spacing: 0) {
                            Menu("Ändern") {
                                Button { picking = true } label: { Label("Fotos …", systemImage: "photo") }
                                Button { immich = true } label: { Label("Aus Immich …", systemImage: "photo.on.rectangle.angled") }
                            }.menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize().padding(.horizontal, 10)
                            Divider().frame(height: 14)
                            Button("Neu positionieren") { pos = HeaderLogic.coverPos(doc.page["cover_pos"]); repositioning = true; showMenu = false }.padding(.horizontal, 10)
                            Divider().frame(height: 14)
                            #if os(macOS)
                            Button("Höhe") { showHeight = true }.padding(.horizontal, 10)
                                .popover(isPresented: $showHeight, arrowEdge: .bottom) {
                                    VStack(alignment: .leading, spacing: 10) {
                                        Text("Höhe des Titelbilds").font(.headline)
                                        HStack {
                                            Slider(value: $coverHeight, in: 100...600, step: 10).frame(width: 220)
                                            Text("\(Int(coverHeight)) pt").monospacedDigit().frame(width: 52, alignment: .trailing)
                                        }
                                        HStack { Text("Gilt für alle Seiten auf diesem Mac.").font(.caption).foregroundStyle(.secondary); Spacer(); Button("Standard") { coverHeight = 200 } }
                                    }.padding(14)
                                }
                            Divider().frame(height: 14)
                            #endif
                            Button("Entfernen") { showMenu = false; Task { await doc.setHeader(["cover": NSNull()], store) } }.padding(.horizontal, 10)
                        }
                        .buttonStyle(.plain).font(.caption.weight(.medium))
                        .padding(.vertical, 6).background(.regularMaterial, in: Capsule()).padding(10)
                    }
                }
                #if os(macOS)
                .onHover { hover = $0 }
                .onTapGesture { if locked { doc.lockHint += 1 } }
                #else
                .onTapGesture {
                    if locked { doc.lockHint += 1 }
                    else if !readOnly && !repositioning { withAnimation { showMenu.toggle() } }
                }
                #endif
                .photosPicker(isPresented: $picking, selection: $pick, matching: .images)
                .onChange(of: pick) { _, it in
                    guard let it else { return }
                    showMenu = false
                    Task { await upload(it) }
                }
                .sheet(isPresented: $immich) {
                    ImmichPicker(note: doc.uploadNote) { url in showMenu = false; Task { await doc.setHeader(["cover": url, "cover_pos": 50.0], store) } }
                        .environmentObject(store)
                }
        }
    }

    private func upload(_ it: PhotosPickerItem) async {
        defer { pick = nil }
        guard let d = try? await it.loadTransferable(type: Data.self) else { return }
        await doc.setCover(data: d, store)
    }
}

// MARK: - Symbol und Titelbild setzen (sofort sichtbar, dann speichern)

extension PageDoc {
    /// Symbol/Titelbild/Position sofort uebernehmen und speichern; klappt das Speichern nicht, den alten Stand zurueck
    @discardableResult
    func setHeader(_ fields: [String: Any], _ store: Store) async -> Bool {
        let undo = HeaderLogic.apply(&page, fields)
        do { try await store.patch(id, fields); return true }
        catch { HeaderLogic.apply(&page, undo); self.error = error.localizedDescription; return false }
    }

    /// Titelbild aus eigenen Bilddaten (Fotos): das Bild steht sofort da, Hochladen + Speichern laufen dahinter
    func setCover(data: Data, _ store: Store) async {
        guard !coverBusy else { return }
        if let e = uploadBlocked { self.error = e; return }
        coverBusy = true
        let img = await Task.detached(priority: .userInitiated) { ImageStore.decode(data) }.value
        coverPreview = img
        do {
            let up = await Task.detached(priority: .userInitiated) { HeaderImage.prepare(data, maxPixel: HeaderImage.coverMaxPixel) }.value ?? data
            let url = try await store.uploadImage(up, note: uploadNote)
            if let img { ImageStore.prime(url, w: CoverView.loadWidth, img) }      // ohne erneuten Abruf, ohne Aufblitzen
            await setHeader(["cover": url, "cover_pos": 50.0], store)
        } catch { self.error = error.localizedDescription }
        coverPreview = nil; coverBusy = false
    }

    /// Bild als Symbol: hochladen, unter der neuen Adresse vormerken, speichern
    @discardableResult
    func setIconImage(data: Data, _ store: Store) async -> Bool {
        if let e = uploadBlocked { self.error = e; return false }
        do {
            let up = await Task.detached(priority: .userInitiated) { HeaderImage.prepare(data, maxPixel: HeaderImage.iconMaxPixel) }.value ?? data
            let url = try await store.uploadImage(up, note: uploadNote)
            if let img = await Task.detached(priority: .userInitiated, operation: { ImageStore.decode(up) }).value {
                ImageStore.prime(url, w: IconRow.loadWidth, img)
            }
            return await setHeader(["icon": url], store)
        } catch { self.error = error.localizedDescription; return false }
    }
}

// MARK: - Icon (halb ueber dem Titelbild) + "Symbol/Titelbild hinzufuegen"

struct IconRow: View {
    @ObservedObject var doc: PageDoc
    var readOnly = false                                  // fremde Seite mit Lesen/Kommentieren, gesperrt
    var locked = false                                    // gesperrte Seite: Antippen zeigt einen Hinweis
    /// Breite, in der ein Bild-Symbol geladen wird (?w=)
    static let loadWidth = 320
    @EnvironmentObject var store: Store
    @State private var picker = false
    @State private var coverPick: PhotosPickerItem?
    @State private var coverImmich = false
    @State private var choosingCover = false
    @State private var hover = false
    @State private var hint = false
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let ic = doc.icon, !ic.isEmpty {
                Button { tapIcon() } label: {
                    Group {
                        if FedLogic.isImagePath(ic) { RemoteImage(path: ic, w: Self.loadWidth).frame(width: 78, height: 78).clipShape(RoundedRectangle(cornerRadius: 10)) }
                        else { Text(ic).font(.system(size: 64)) }
                    }
                    .contentShape(Rectangle())                // ganze Flaeche des Symbols, auch der Teil ueber dem Titelbild
                }
                .buttonStyle(.plain)
                .allowsHitTesting(!readOnly || locked)      // nur lesen: nicht abblenden, nur nichts tun
                .accessibilityLabel(Text("Symbol"))
                .contextMenu {
                    if !readOnly {
                        Button { picker = true } label: { Label("Ändern", systemImage: "face.smiling") }
                        Button { Task { await doc.setHeader(["icon": IconPicker.randomEmoji()], store) } } label: { Label("Zufällig", systemImage: "shuffle") }
                        Button(role: .destructive) { Task { await doc.setHeader(["icon": ""], store) } } label: { Label("Symbol entfernen", systemImage: "trash") }
                    }
                }
                // halb ueber dem Titelbild. Die Trefferflaeche darf dabei nicht abgeschnitten werden: deshalb hat die
                // Zeile selbst KEINE eigene contentShape mehr (die schnitt den oberen Teil ab - Tippen landete im Titelbild)
                .padding(.top, doc.hasCover ? -44 : 10)
            }
            if hint {
                Label("Die Seite ist gesperrt – Symbol und Titelbild lassen sich erst nach dem Entsperren ändern.", systemImage: "lock.fill")
                    .font(.caption).foregroundStyle(.secondary).transition(.opacity)
            }
            // Fehlt Icon oder Titelbild: dezent anbieten (Mac beim Ueberfahren, iPhone immer leicht sichtbar)
            if !readOnly && (doc.icon?.isEmpty != false || !doc.hasCover) {
                // Bedienungshilfen-Schriftgroessen: untereinander und ohne feste Breite - sonst wurde die Zeile breiter als
                // der Bildschirm und zog die ganze Seitenspalte mit (Text links/rechts abgeschnitten, BUG-019)
                let ax = typeSize.isAccessibilitySize
                let layout = ax ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 14))
                layout {
                    if doc.icon?.isEmpty != false { Button { picker = true } label: { Label("Symbol hinzufügen", systemImage: "face.smiling") } }
                    if !doc.hasCover {
                        Menu {
                            Button { choosingCover = true } label: { Label("Fotos …", systemImage: "photo") }
                            Button { coverImmich = true } label: { Label("Aus Immich …", systemImage: "photo.on.rectangle.angled") }
                        } label: { Label("Titelbild hinzufügen", systemImage: "photo") }.menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden)
                            .fixedSize(horizontal: !ax, vertical: true)
                    }
                }
                .buttonStyle(.plain).font(.subheadline).foregroundStyle(.secondary)
                #if os(macOS)
                .opacity(hover ? 1 : 0)
                #else
                .opacity(0.7)
                #endif
                .padding(.top, !doc.hasCover && doc.icon?.isEmpty != false ? 14 : 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        #if os(macOS)
        .background(Color.clear.contentShape(Rectangle()))   // Ueberfahren auch im leeren Teil der Zeile erkennen
        .onHover { hover = $0 }
        #endif
        .onChange(of: doc.lockHint) { _, _ in showHint() }
        .sheet(isPresented: $picker) { IconPicker(doc: doc) }
        .sheet(isPresented: $coverImmich) {
            ImmichPicker(note: doc.uploadNote) { url in Task { await doc.setHeader(["cover": url, "cover_pos": 50.0], store) } }
                .environmentObject(store)
        }
        .photosPicker(isPresented: $choosingCover, selection: $coverPick, matching: .images)
        .onChange(of: coverPick) { _, it in
            guard let it else { return }
            Task {
                defer { coverPick = nil }
                guard let d = try? await it.loadTransferable(type: Data.self) else { return }
                await doc.setCover(data: d, store)
            }
        }
    }

    private func tapIcon() {
        if locked { showHint() } else if !readOnly { picker = true }
    }
    private func showHint() {
        withAnimation { hint = true }
        let n = doc.lockHint
        Task {
            try? await Task.sleep(for: .seconds(3))
            if doc.lockHint == n { withAnimation { hint = false } }
        }
    }
}

struct IconPicker: View {
    @ObservedObject var doc: PageDoc
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var custom = ""
    @State private var pick: PhotosPickerItem?
    @State private var immich = false
    @State private var uploading = false
    static let emojis = "📄📝📚📖✅☑️🗓️📅⏰💡⭐️🔥🎯📌📎🗂️🗃️📥📤📦🧾📊📈💰🛒🏠🏡💼🧠🛠️🔧🔒🔑🧪🎨✏️🖊️🎮👾🕹️🎵🎬📷🎧✈️🚗🚀🏋️⚽️❤️💜💙💚😀😎🤓🤖👻🐱🐶🦊🐉🌙☀️🌈🍀⚡️❄️🌍🍕☕️🍺🎁🏁🧩💻🖥️📱⌚️🗝️🧭⚙️"
    static func randomEmoji() -> String { Array(emojis).randomElement().map(String.init) ?? "📄" }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        TextField("Beliebiges Emoji eingeben", text: $custom).textFieldStyle(.roundedBorder)
                            .onChange(of: custom) { _, v in if let f = v.first, f.unicodeScalars.contains(where: { $0.properties.isEmojiPresentation || $0.properties.isEmoji && $0.value > 0x238C }) { set(String(f)) } }
                        PhotosPicker(selection: $pick, matching: .images) { Label("Bild", systemImage: "photo") }
                        Button { immich = true } label: { Label("Immich", systemImage: "photo.on.rectangle.angled") }
                    }
                    .disabled(uploading)
                    if uploading { ProgressView("Wird hochgeladen …").frame(maxWidth: .infinity) }
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 8), spacing: 6) {
                        ForEach(Array(Self.emojis).map(String.init), id: \.self) { e in
                            Button { set(e) } label: { Text(e).font(.system(size: 28)).frame(maxWidth: .infinity, minHeight: 40) }.buttonStyle(.plain)
                        }
                    }
                    HStack {
                        Button { set(Self.randomEmoji()) } label: { Label("Zufällig", systemImage: "shuffle") }
                        Spacer()
                        if doc.icon?.isEmpty == false { Button("Symbol entfernen", role: .destructive) { set("") } }
                    }
                }
                .padding()
            }
            .navigationTitle("Symbol")
            .sheet(isPresented: $immich) { ImmichPicker(note: doc.uploadNote) { url in set(url) }.environmentObject(store) }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Fertig") { dismiss() } } }
            .onChange(of: pick) { _, it in
                guard let it else { return }
                Task {
                    uploading = true; defer { uploading = false; pick = nil }
                    guard let d = try? await it.loadTransferable(type: Data.self) else { return }
                    if await doc.setIconImage(data: d, store) { dismiss() }
                }
            }
        }
        .macFrame(minWidth: 380, minHeight: 440)
    }
    /// Sofort uebernehmen und schliessen; gespeichert wird dahinter (Fehler: alter Stand + Meldung auf der Seite)
    private func set(_ v: String) {
        dismiss()
        Task { await doc.setHeader(["icon": v], store) }
    }
}

// MARK: - Verschieben nach ...

struct MoveSheet: View {
    let id: Int
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var q = ""
    @State private var error = ""
    var body: some View {
        NavigationStack {
            let current = store.page(id)?.parent_id
            // geteilte Seite (Rolle Bearbeiten, 2.3): nur innerhalb des geteilten Bereichs, nie auf die oberste Ebene
            let shared = store.page(id).map { $0.isRemote || ($0.role != nil && $0.role != "owner") } ?? FedLogic.isRemote(id)
            let targets = (shared ? FedLogic.sharedMoveTargets(id, in: store.shared + store.fedShared) : ownTargets(current))
                .filter { q.isEmpty || $0.label.localizedCaseInsensitiveContains(q) }
            List {
                if shared && targets.isEmpty && q.isEmpty { Text("Kein anderes Ziel im geteilten Bereich.").foregroundStyle(.secondary) }
                if current != nil && q.isEmpty && !shared {
                    Button { Task { await go(nil) } } label: { Label("Oberste Ebene", systemImage: "arrow.up.to.line") }
                }
                ForEach(targets) { p in
                    Button { Task { await go(p.id) } } label: {
                        HStack {
                            PageIcon(icon: p.icon, store: store)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(p.label)
                                let path = store.path(p.id).map(\.label).joined(separator: " / ")
                                if !path.isEmpty { Text(path).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                            }
                        }
                    }.buttonStyle(.plain)
                }
                if !error.isEmpty { Text(error).foregroundStyle(.red).font(.footnote) }
            }
            .searchable(text: $q, prompt: "Seite suchen")
            .navigationTitle("Verschieben nach")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
        }
        .macFrame(minWidth: 380, minHeight: 460)
    }
    private func ownTargets(_ current: Int?) -> [Page] {
        let blocked = store.descendants(id)
        return store.pages.filter { !blocked.contains($0.id) && !$0.isDatabase && $0.id != current &&
            !($0.parent_id.map { store.page($0)?.isDatabase ?? false } ?? false) }
    }
    private func go(_ parent: Int?) async {
        do { try await store.move(id, to: parent); dismiss() } catch { self.error = error.localizedDescription }
    }
}

/// "Bearbeitet am ..." wie Notion
func editedText(_ ts: Double?) -> String {
    guard let ts else { return "" }
    let d = Date(timeIntervalSince1970: ts)
    let when = Calendar.current.isDateInToday(d) ? String(localized: "heute, \(d.sagaFormatted(date: .omitted, time: .shortened))")
                                                 : d.sagaFormatted(date: .abbreviated, time: .shortened)
    return String(localized: "Bearbeitet \(when)")
}
