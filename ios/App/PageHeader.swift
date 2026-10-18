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

    var body: some View {
        if let c = doc.cover {
            RemoteImage(path: c, fill: true, vAlign: repositioning ? pos : (doc.page["cover_pos"] as? Double ?? 50), w: 2000)
                .frame(maxWidth: .infinity).frame(height: CGFloat(coverHeight) + extendTop)
                .padding(.top, -extendTop)
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
                .gesture(DragGesture(minimumDistance: 2).onChanged { g in               // Neu positionieren: Bild ziehen
                    guard repositioning else { return }
                    let base = dragStart ?? pos; if dragStart == nil { dragStart = pos }
                    pos = min(100, max(0, base - Double(g.translation.height) / 2))
                }.onEnded { _ in dragStart = nil })
                .overlay(alignment: .topTrailing) {
                    if readOnly {
                        EmptyView()
                    } else if repositioning {
                        HStack(spacing: 8) {
                            Text("Bild ziehen zum Positionieren").font(.caption)
                            Button("Speichern") { Task { await save(["cover_pos": pos]); repositioning = false } }.buttonStyle(.borderedProminent)
                            Button("Abbrechen") { repositioning = false }
                        }
                        .padding(8).background(.regularMaterial, in: Capsule()).padding(10)
                    } else if hover || showMenu || showHeightOpen {
                        HStack(spacing: 0) {
                            Menu("Ändern") {
                                Button { picking = true } label: { Label("Fotos …", systemImage: "photo") }
                                Button { immich = true } label: { Label("Aus Immich …", systemImage: "photo.on.rectangle.angled") }
                            }.menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize().padding(.horizontal, 10)
                            Divider().frame(height: 14)
                            Button("Neu positionieren") { pos = doc.page["cover_pos"] as? Double ?? 50; repositioning = true; showMenu = false }.padding(.horizontal, 10)
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
                            Button("Entfernen") { Task { await save(["cover": NSNull()]) } }.padding(.horizontal, 10)
                        }
                        .buttonStyle(.plain).font(.caption.weight(.medium))
                        .padding(.vertical, 6).background(.regularMaterial, in: Capsule()).padding(10)
                    }
                }
                #if os(macOS)
                .onHover { hover = $0 }
                #else
                .onTapGesture { if !repositioning { withAnimation { showMenu.toggle() } } }
                #endif
                .photosPicker(isPresented: $picking, selection: $pick, matching: .images)
                .onChange(of: pick) { _, it in Task { await upload(it) } }
                .sheet(isPresented: $immich) { ImmichPicker(note: doc.uploadNote) { url in Task { await save(["cover": url, "cover_pos": 50]) } }.environmentObject(store) }
        }
    }

    private func upload(_ it: PhotosPickerItem?) async {
        guard let it, let d = try? await it.loadTransferable(type: Data.self) else { return }
        do { let url = try await store.uploadImage(d, note: doc.uploadNote); await save(["cover": url, "cover_pos": 50]) } catch { doc.error = error.localizedDescription }
        pick = nil; showMenu = false
    }
    private func save(_ f: [String: Any]) async {
        do { try await store.patch(doc.id, f); for (k, v) in f { doc.page[k] = v is NSNull ? nil : v } } catch { doc.error = error.localizedDescription }
    }
}

// MARK: - Icon (halb ueber dem Titelbild) + "Symbol/Titelbild hinzufuegen"

struct IconRow: View {
    @ObservedObject var doc: PageDoc
    var readOnly = false                                  // fremde Seite mit Lesen/Kommentieren
    @EnvironmentObject var store: Store
    @State private var picker = false
    @State private var coverPick: PhotosPickerItem?
    @State private var coverImmich = false
    @State private var choosingCover = false
    @State private var hover = false
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let ic = doc.icon, !ic.isEmpty {
                Button { if !readOnly { picker = true } } label: {             // nur lesen: nicht abblenden, nur nichts tun
                    Group {
                        if FedLogic.isImagePath(ic) { RemoteImage(path: ic, w: 320).frame(width: 78, height: 78).clipShape(RoundedRectangle(cornerRadius: 10)) }
                        else { Text(ic).font(.system(size: 64)) }
                    }
                }
                .buttonStyle(.plain)
                .allowsHitTesting(!readOnly)
                .padding(.top, doc.cover == nil ? 10 : -44)
            }
            // Fehlt Icon oder Titelbild: dezent anbieten (Mac beim Ueberfahren, iPhone immer leicht sichtbar)
            if !readOnly && (doc.icon?.isEmpty != false || doc.cover == nil) {
                // Bedienungshilfen-Schriftgroessen: untereinander und ohne feste Breite - sonst wurde die Zeile breiter als
                // der Bildschirm und zog die ganze Seitenspalte mit (Text links/rechts abgeschnitten, BUG-019)
                let ax = typeSize.isAccessibilitySize
                let layout = ax ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 14))
                layout {
                    if doc.icon?.isEmpty != false { Button { picker = true } label: { Label("Symbol hinzufügen", systemImage: "face.smiling") } }
                    if doc.cover == nil {
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
                .padding(.top, doc.cover == nil && doc.icon?.isEmpty != false ? 14 : 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        #if os(macOS)
        .onHover { hover = $0 }
        #endif
        .sheet(isPresented: $picker) { IconPicker(doc: doc) }
        .sheet(isPresented: $coverImmich) {
            ImmichPicker(note: doc.uploadNote) { url in Task { do { try await store.patch(doc.id, ["cover": url, "cover_pos": 50]); doc.page["cover"] = url; doc.page["cover_pos"] = 50.0 } catch { doc.error = error.localizedDescription } } }
                .environmentObject(store)
        }
        .photosPicker(isPresented: $choosingCover, selection: $coverPick, matching: .images)
        .onChange(of: coverPick) { _, it in
            Task {
                guard let it, let d = try? await it.loadTransferable(type: Data.self) else { return }
                do { let url = try await store.uploadImage(d, note: doc.uploadNote); try await store.patch(doc.id, ["cover": url, "cover_pos": 50]); doc.page["cover"] = url; doc.page["cover_pos"] = 50.0 }
                catch { doc.error = error.localizedDescription }
                coverPick = nil
            }
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
    private let emojis = "📄📝📚📖✅☑️🗓️📅⏰💡⭐️🔥🎯📌📎🗂️🗃️📥📤📦🧾📊📈💰🛒🏠🏡💼🧠🛠️🔧🔒🔑🧪🎨✏️🖊️🎮👾🕹️🎵🎬📷🎧✈️🚗🚀🏋️⚽️❤️💜💙💚😀😎🤓🤖👻🐱🐶🦊🐉🌙☀️🌈🍀⚡️❄️🌍🍕☕️🍺🎁🏁🧩💻🖥️📱⌚️🗝️🧭⚙️"
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        TextField("Beliebiges Emoji eingeben", text: $custom).textFieldStyle(.roundedBorder)
                            .onChange(of: custom) { _, v in if let f = v.first, f.unicodeScalars.contains(where: { $0.properties.isEmojiPresentation || $0.properties.isEmoji && $0.value > 0x238C }) { Task { await set(String(f)) } } }
                        PhotosPicker(selection: $pick, matching: .images) { Label("Bild", systemImage: "photo") }
                        Button { immich = true } label: { Label("Immich", systemImage: "photo.on.rectangle.angled") }
                    }
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 8), spacing: 6) {
                        ForEach(Array(emojis).map(String.init), id: \.self) { e in
                            Button { Task { await set(e) } } label: { Text(e).font(.system(size: 28)).frame(maxWidth: .infinity, minHeight: 40) }.buttonStyle(.plain)
                        }
                    }
                    if doc.icon?.isEmpty == false { Button("Symbol entfernen", role: .destructive) { Task { await set("") } } }
                }
                .padding()
            }
            .navigationTitle("Symbol")
            .sheet(isPresented: $immich) { ImmichPicker(note: doc.uploadNote) { url in Task { await set(url) } }.environmentObject(store) }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Fertig") { dismiss() } } }
            .onChange(of: pick) { _, it in
                Task {
                    guard let it, let d = try? await it.loadTransferable(type: Data.self) else { return }
                    do { let url = try await store.uploadImage(d, note: doc.uploadNote); await set(url) } catch { doc.error = error.localizedDescription }
                }
            }
        }
        .macFrame(minWidth: 380, minHeight: 440)
    }
    private func set(_ v: String) async {
        do { try await store.patch(doc.id, ["icon": v]); doc.page["icon"] = v; dismiss() } catch { doc.error = error.localizedDescription }
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
            let blocked = store.descendants(id)
            let current = store.page(id)?.parent_id
            let targets = store.pages.filter { !blocked.contains($0.id) && !$0.isDatabase && $0.id != current &&
                !($0.parent_id.map { store.page($0)?.isDatabase ?? false } ?? false) &&
                (q.isEmpty || $0.label.localizedCaseInsensitiveContains(q)) }
            List {
                if current != nil && q.isEmpty {
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
