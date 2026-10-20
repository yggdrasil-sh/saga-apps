import SwiftUI
import UniformTypeIdentifiers

// Editor-Funktionen wie im Web: @-Datum/Erinnerung/Seite, Emoji per ":", Tabelle, Schaltflaechen,
// Einbetten, Datei-Anhang, Format- und Block-Menue (Mac-Menueleiste).

// MARK: - Datum aus Text (wie parseDate in static/keys.js)

struct HKDate: Equatable { let date: String; let time: String? }

func hkNorm(_ s: String) -> String {
    s.lowercased().replacingOccurrences(of: "ä", with: "ae").replacingOccurrences(of: "ö", with: "oe")
        .replacingOccurrences(of: "ü", with: "ue").replacingOccurrences(of: "ß", with: "ss")
        .folding(options: .diacriticInsensitive, locale: nil)            // spanisch: mañana -> manana, miércoles -> miercoles
        .split(whereSeparator: \.isWhitespace).joined(separator: " ")
}
private let hkWD = ["sonntag", "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag"]
private let hkWDE = ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"]
private let hkWDS = ["domingo", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado"]
private let hkMON = ["januar", "februar", "maerz", "april", "mai", "juni", "juli", "august", "september", "oktober", "november", "dezember"]

private func rx(_ s: String, _ p: String) -> [String]? {
    guard let re = try? NSRegularExpression(pattern: p), let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
    return (0..<m.numberOfRanges).map { i in Range(m.range(at: i), in: s).map { String(s[$0]) } ?? "" }
}

/// "heute", "morgen", "freitag", "naechsten montag", "in 3 tagen", "12.10.", "12. oktober", "2026-10-12", optional "15:30" / "um 15 uhr"
func parseHKDate(_ q0: String) -> HKDate? {
    var q = hkNorm(q0)
    var cal = Calendar(identifier: .gregorian); cal.timeZone = .current
    let t0 = cal.startOfDay(for: .now)
    func plus(_ n: Int, _ c: Calendar.Component = .day) -> Date { cal.date(byAdding: c, value: n, to: t0)! }
    var time: String?
    if let m = rx(q, #"(?:^|\s)(?:um\s+)?(\d{1,2})[:.](\d{2})(?:\s*uhr)?$"#) ?? rx(q, #"(?:^|\s)(?:um\s+)?(\d{1,2})\s*uhr$"#),
       q.contains(":") || q.hasSuffix("uhr") {
        let hh = Int(m[1]) ?? 99, mm = m.count > 2 ? Int(m[2]) ?? 0 : 0
        guard hh <= 23, mm <= 59 else { return nil }
        time = String(format: "%02d:%02d", hh, mm)
        if let r = q.range(of: m[0], options: .backwards) { q = String(q[..<r.lowerBound]).trimmingCharacters(in: .whitespaces) }
    }
    var d: Date?
    if q.isEmpty || q == "heute" || q == "today" || q == "hoy" { d = t0 }
    else if q == "morgen" || q == "tomorrow" || q == "manana" { d = plus(1) }
    else if q == "uebermorgen" || q == "pasado manana" { d = plus(2) }
    else if q == "gestern" || q == "yesterday" || q == "ayer" { d = plus(-1) }
    else if let m = rx(q, #"^(?:in|en) (\d{1,3}) (tag|tage|tagen|woche|wochen|monat|monate|monaten|day|days|week|weeks|month|months|dia|dias|semana|semanas|mes|meses)$"#) {
        let n = Int(m[1]) ?? 0
        d = m[2].hasPrefix("w") || m[2].hasPrefix("se") ? plus(7 * n) : m[2].hasPrefix("mo") || m[2].hasPrefix("mes") ? plus(n, .month) : plus(n)
    } else if q == "naechste woche" || q == "next week" || q == "proxima semana" || q == "la proxima semana" { d = plus(7) }
    else if let m = rx(q, #"^(?:(naechsten?|kommenden?|next|el proximo|proximo|el)\s+)?([a-z]+)$"#) {
        guard let wi = hkWD.firstIndex(of: m[2]) ?? hkWDE.firstIndex(of: m[2]) ?? hkWDS.firstIndex(of: m[2]) else { return nil }
        var diff = (wi - (cal.component(.weekday, from: t0) - 1) + 7) % 7; if diff == 0 { diff = 7 }
        d = plus(diff)
    } else if let m = rx(q, #"^(\d{4})-(\d{2})-(\d{2})$"#) {
        d = cal.date(from: DateComponents(year: Int(m[1]), month: Int(m[2]), day: Int(m[3])))
    } else if let m = rx(q, #"^(\d{1,2})\.(\d{1,2})\.(\d{2,4})?$"#) {
        var y = cal.component(.year, from: t0); if !m[3].isEmpty, let yy = Int(m[3]) { y = yy < 100 ? 2000 + yy : yy }
        let mo = Int(m[2]) ?? 0, da = Int(m[1]) ?? 0
        guard let x = cal.date(from: DateComponents(year: y, month: mo, day: da)), cal.component(.month, from: x) == mo else { return nil }
        d = x
    } else if let m = rx(q, #"^(\d{1,2})\.? ([a-z]+)\.?(?: (\d{4}))?$"#) {
        guard m[2].count >= 3, let mi = hkMON.firstIndex(where: { $0.hasPrefix(m[2]) }) else { return nil }
        let y = m[3].isEmpty ? cal.component(.year, from: t0) : (Int(m[3]) ?? 0)
        guard let x = cal.date(from: DateComponents(year: y, month: mi + 1, day: Int(m[1]))), cal.component(.month, from: x) == mi + 1 else { return nil }
        d = x
    } else { return nil }
    guard let d else { return nil }
    let f = DateFormatter.isoDay()
    return HKDate(date: f.string(from: d), time: time)
}

/// Im Text immer das feste Datum (wie im Web: "Fr., 10. Okt." bzw. mit Jahr)
func hkDateText(_ d: HKDate) -> String {
    let p = DateFormatter.isoDay()
    guard let dt = p.date(from: d.date) else { return d.date }
    var style = SagaDate.dateTime().weekday(.abbreviated).day().month(.abbreviated)     // Format der App-Sprache, gregorianisch
    if !Calendar.current.isDate(dt, equalTo: .now, toGranularity: .year) { style = style.year() }
    return dt.formatted(style) + (d.time.map { ", " + $0 } ?? "")
}
func hkRelText(_ d: HKDate) -> String {
    let p = DateFormatter.isoDay()
    guard let dt = p.date(from: d.date) else { return hkDateText(d) }
    let diff = Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: .now), to: dt).day ?? 99
    let r = [0: String(localized: "Heute"), 1: String(localized: "Morgen"), 2: String(localized: "Übermorgen"), -1: String(localized: "Gestern")][diff]
    return (r.map { $0 + " · " } ?? "") + hkDateText(d)
}

// MARK: - "@" und ":" an der Cursor-Position (Erkennung: MentionTrigger.swift)

struct MentionItem: Identifiable {
    let id = UUID()
    let icon: String
    let title: String
    let hint: String
    let insert: AttributedString
}

@MainActor
func mentionItems(_ t: HKTrigger, store: Store, font: Font) -> [MentionItem] {
    func run(_ text: String, _ link: URL?) -> AttributedString { var a = AttributedString(text); a.font = font; a.link = link; return a }
    switch t {
    case .emoji(let q0):
        let q = hkNorm(q0)
        func rank(_ k: String) -> Int {
            let w = k.split(separator: " ").map(String.init)
            if w.first == q { return 0 }; if w.contains(q) { return 1 }
            if (" " + k).contains(" " + q) { return 2 }; if k.contains(q) { return 3 }; return 9
        }
        var hits: [(pos: Int, emoji: String, words: String, rank: Int)] = []
        for (i, e) in emojiList.enumerated() {
            let r = rank(e.1)
            if r < 9 { hits.append((i, e.0, e.1, r)) }
        }
        hits.sort { a, b in a.rank == b.rank ? a.pos < b.pos : a.rank < b.rank }
        var out: [MentionItem] = []
        for h in hits.prefix(8) {
            let word = String(h.words.split(separator: " ").first ?? "")
            out.append(MentionItem(icon: h.emoji, title: word, hint: "", insert: run(h.emoji, nil)))
        }
        return out
    case .at(let q):
        var out: [MentionItem] = []
        func dateItems(_ d: HKDate) {
            let link = URL(string: "hkdate://d/\(d.date)" + (d.time.map { "T" + $0 } ?? ""))
            out.append(MentionItem(icon: "📅", title: hkRelText(d), hint: String(localized: "Datum"), insert: run(hkDateText(d), link)))
            out.append(MentionItem(icon: "⏰", title: String(localized: "Erinnerung: \(hkRelText(d))"), hint: String(localized: "Erinnerung"),
                                   insert: run("⏰ " + hkDateText(d), URL(string: (link?.absoluteString ?? "") + "?r=1"))))
        }
        if q.isEmpty {
            [parseHKDate("heute"), parseHKDate("morgen")].compactMap { $0 }.forEach { d in
                out.append(MentionItem(icon: "📅", title: hkRelText(d), hint: String(localized: "Datum"),
                                       insert: run(hkDateText(d), URL(string: "hkdate://d/\(d.date)"))))
            }
            if let d = parseHKDate("morgen 9:00") {
                out.append(MentionItem(icon: "⏰", title: String(localized: "Erinnerung: \(hkRelText(d))"), hint: String(localized: "Erinnerung"),
                                       insert: run("⏰ " + hkDateText(d), URL(string: "hkdate://d/\(d.date)T09:00?r=1"))))
            }
        } else if let d = parseHKDate(q) { dateItems(d) }
        let nq = hkNorm(q)
        let pages = store.pages.filter { nq.isEmpty || hkNorm($0.label).contains(nq) }.prefix(q.isEmpty ? 4 : 6)
        for p in pages {
            out.append(MentionItem(icon: (p.icon?.count ?? 0) <= 4 && !(p.icon ?? "").isEmpty ? p.icon! : (p.isDatabase ? "🗃️" : "📄"),
                                   title: p.label, hint: String(localized: "Seite"), insert: run(p.label, URL(string: "hkpage://\(p.id)"))))
        }
        return out
    }
}

/// Auswahlliste unter dem Absatz (wie das "/"-Menue)
struct MentionMenu: View {
    let items: [MentionItem]
    let selected: Int
    let pick: (MentionItem) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { i, it in
                Button { pick(it) } label: {
                    HStack(spacing: 8) {
                        Text(it.icon).frame(width: 22)
                        Text(it.title).lineLimit(1)
                        Spacer(minLength: 8)
                        if !it.hint.isEmpty { Text(it.hint).font(.caption).foregroundStyle(.secondary) }
                    }
                    .padding(.vertical, 6).padding(.horizontal, 10).contentShape(Rectangle())
                    .background(i == selected ? Color.accentColor.opacity(0.18) : .clear, in: RoundedRectangle(cornerRadius: 6))
                }.buttonStyle(.plain)
            }
        }
        .padding(4)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(.secondary.opacity(0.25)))
        .frame(maxWidth: 340)
    }
}

/// Ausloeser (ab Zeichen-Offset start) durch die Auswahl ersetzen (+ Leerzeichen dahinter); Rueckgabe: Cursor-Offset danach
@discardableResult
func replaceTrigger(_ rich: inout AttributedString, _ t: HKTrigger, at start: Int, with insert: AttributedString, font: Font) -> Int {
    var sp = AttributedString(" "); sp.font = font
    return hkReplaceChars(&rich, start: start, length: t.length, with: insert, space: sp)
}

// MARK: - Tabelle (bearbeitbar)

/// Tabellen-Block: Inhalt kommt immer aus den Blockdaten (auch nach Neuladen), Zellen behalten ihr Inline-HTML
/// (Bearbeiten mit Formatierung wie Absaetze), feste Zeilen-/Spalten-IDs statt Indizes.
struct EditableTable: View {
    let data: NSMutableDictionary
    let blockID: String
    @EnvironmentObject var ed: PageEditor
    @State private var model = TableModel(content: nil)
    @State private var tick = 0
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var hsc
    private var colWidth: CGFloat { hsc == .compact ? 150 : 200 }
    #else
    private let colWidth: CGFloat = 200
    #endif
    private var headings: Bool { data["withHeadings"] as? Bool ?? false }
    /// aktueller Stand in den Blockdaten (aendert sich beim Neuladen der Seite)
    private var stored: [[String]] { TableModel(content: data["content"]).content }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ScrollView(.horizontal, showsIndicators: true) {
                Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 0) {
                    ForEach(Array(model.rows.enumerated()), id: \.element.id) { ri, row in
                        GridRow {
                            ForEach(Array(model.cols.enumerated()), id: \.element) { ci, col in
                                cellView(row: row.id, ri: ri, col: col, ci: ci)
                            }
                        }
                    }
                }
                .padding(.bottom, 2)
            }
            .accessibilityLabel("Tabelle")
            if !ed.locked {
                HStack(spacing: 12) {
                    Button { model.insertRow(near: nil); structure() } label: { Label("Zeile", systemImage: "plus") }
                    Button { model.insertCol(near: nil); structure() } label: { Label("Spalte", systemImage: "plus") }
                    Button { data["withHeadings"] = !headings; tick += 1; ed.changed() } label: {     // kompakt wie die anderen Knoepfe (kein breiter Schalter)
                        Label("Kopfzeile", systemImage: headings ? "checkmark.square" : "square")
                    }
                    .accessibilityAddTraits(headings ? .isSelected : [])
                }
                .font(.caption).buttonStyle(.plain).foregroundStyle(.secondary)
            }
        }
        // Blockdaten neu (Neuladen, Rueckkehr aus dem Web-Editor, Wiederherstellen) -> Anzeige nachziehen
        .onChange(of: stored, initial: true) { _, new in if new != model.content { model = TableModel(content: data["content"]) } }
    }

    @ViewBuilder private func cellView(row: Int, ri: Int, col: Int, ci: Int) -> some View {
        let head = ri == 0 && headings
        EditText(key: "\(blockID)/t\(row)-\(col)", html: model.cell(row, ci),
                 font: head ? .subheadline.bold() : .subheadline, placeholder: "", splitOnReturn: false, boldBase: head,
                 setHTML: { h in model.set(row, col, h); write() })
            .padding(8)
            .frame(width: colWidth, alignment: .topLeading)
            .frame(maxHeight: .infinity, alignment: .topLeading)
            .background(head ? Color.secondary.opacity(0.1) : .clear)
            .border(.secondary.opacity(0.25), width: 0.5)
            .contextMenu { if !ed.locked { tableMenu(row, col) } }
    }

    @ViewBuilder private func tableMenu(_ row: Int, _ col: Int) -> some View {
        Button("Zeile darüber einfügen") { model.insertRow(near: row, after: false); structure() }
        Button("Zeile darunter einfügen") { model.insertRow(near: row, after: true); structure() }
        Button("Spalte links einfügen") { model.insertCol(near: col, after: false); structure() }
        Button("Spalte rechts einfügen") { model.insertCol(near: col, after: true); structure() }
        Divider()
        if model.rows.count > 1 { Button("Zeile löschen", role: .destructive) { ed.focus = nil; model.removeRow(row); structure() } }
        if model.width > 1 { Button("Spalte löschen", role: .destructive) { ed.focus = nil; model.removeCol(col); structure() } }
    }
    /// Zelltext geaendert: nur speichern (das Textfeld zeigt sich selbst)
    private func write() { data["content"] = model.content; ed.textChanged() }
    /// Zeilen/Spalten geaendert: speichern + Seite neu zeichnen
    private func structure() { data["content"] = model.content; ed.changed() }
}

// MARK: - Schaltflaechen einrichten (wie ⚙ im Web)

struct ButtonsConfig: View {
    let data: NSMutableDictionary
    @EnvironmentObject var store: Store
    @EnvironmentObject var ed: PageEditor
    @Environment(\.dismiss) private var dismiss
    @State private var buttons: [[String: Any]] = []
    var body: some View {
        NavigationStack {
            Form {
                ForEach(buttons.indices, id: \.self) { i in
                    Section("Schaltfläche \(i + 1)") {
                        TextField("Beschriftung", text: bind(i, "label"))
                        TextField("Symbol (Emoji)", text: bind(i, "icon"))
                        Picker("Aktion", selection: bind(i, "action")) {
                            Text("keine").tag(""); Text("Neuer Eintrag in Datenbank").tag("db"); Text("Neue Aufgabe").tag("task"); Text("Seite öffnen").tag("page")
                        }
                        if buttons[i]["action"] as? String == "db" {
                            Picker("Datenbank", selection: bindInt(i, "target")) {
                                Text("– wählen –").tag(0); ForEach(store.pages.filter(\.isDatabase)) { Text($0.label).tag($0.id) }
                            }
                            Picker("Vorlage", selection: bindInt(i, "tpl")) {
                                Text("keine (leere Seite)").tag(0); ForEach(store.pages.filter { $0.template == true }) { Text($0.label).tag($0.id) }
                            }
                            TextField("Titel ({datum} = heute)", text: bind(i, "title"))
                        }
                        if buttons[i]["action"] as? String == "page" {
                            Picker("Seite", selection: bindInt(i, "target")) {
                                Text("– wählen –").tag(0); ForEach(store.pages) { Text($0.label).tag($0.id) }
                            }
                        }
                        if ["db", "page"].contains(buttons[i]["action"] as? String ?? "") {
                            Picker("Öffnen als", selection: bind(i, "open")) { Text("Seite").tag(""); Text("Seitenansicht").tag("side"); Text("Mitte").tag("center") }
                        }
                        Button("Schaltfläche entfernen", role: .destructive) { buttons.remove(at: i) }
                    }
                }
                Button { buttons.append(["label": String(localized: "Neu"), "icon": "➕", "action": ""]) } label: { Label("Schaltfläche hinzufügen", systemImage: "plus") }
            }
            .formStyle(.grouped)
            .navigationTitle("Schaltflächen")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { data["buttons"] = buttons; ed.changed(); dismiss() } }
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
            }
        }
        .macFrame(minWidth: 420, minHeight: 420)
        .onAppear { buttons = (data["buttons"] as? [[String: Any]]) ?? [] }
    }
    private func bind(_ i: Int, _ k: String) -> Binding<String> {
        Binding(get: { i < buttons.count ? buttons[i][k] as? String ?? "" : "" }, set: { if i < buttons.count { buttons[i][k] = $0 } })
    }
    private func bindInt(_ i: Int, _ k: String) -> Binding<Int> {
        Binding(get: { i < buttons.count ? buttons[i][k] as? Int ?? 0 : 0 }, set: { if i < buttons.count { buttons[i][k] = $0 == 0 ? nil : $0 } })
    }
}

// MARK: - Einbetten: Adresse eingeben

struct EmbedPrompt: View {
    let done: (String?) -> Void
    @State private var url = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Einbetten").font(.headline)
            Text("YouTube-Video oder beliebiger Link (wird als Karte gezeigt)").font(.caption).foregroundStyle(.secondary)
            TextField("https://…", text: $url).textFieldStyle(.roundedBorder).onSubmit { ok() }
            HStack { Spacer(); Button("Abbrechen") { done(nil) }; Button("Einbetten") { ok() }.keyboardShortcut(.defaultAction).disabled(!valid) }
        }
        .padding(18).macWidth(380)
    }
    private var valid: Bool { url.hasPrefix("http://") || url.hasPrefix("https://") }
    private func ok() { if valid { done(url.trimmingCharacters(in: .whitespaces)) } }
}

// MARK: - Menueleiste: Format und Block (Mac / iPad mit Tastatur)

struct PageEditorKey: FocusedValueKey { typealias Value = PageEditor }
extension FocusedValues {
    var pageEditor: PageEditor? { get { self[PageEditorKey.self] } set { self[PageEditorKey.self] = newValue } }
}

/// Block, der gerade bearbeitet wird (nil: kein Seitenblock im Fokus) - fuer die Menueleiste
struct EditorBlockKey: FocusedValueKey { typealias Value = String }
extension FocusedValues {
    var editorBlock: String? { get { self[EditorBlockKey.self] } set { self[EditorBlockKey.self] = newValue } }
}

/// Menueleiste Format/Block: nur aktiv, solange ein Seitenblock bearbeitet wird, und nur fuer DIESEN Block - nicht fuer
/// den Block unter der Maus (⌘D/Loeschen trafen sonst einen Block, den man nicht bearbeitet; BUG-103)
struct HKEditorCommands: Commands {
    @FocusedValue(\.pageEditor) private var ed
    @FocusedValue(\.editorBlock) private var block
    private var off: Bool { ed == nil || block == nil }
    var body: some Commands {
        CommandMenu("Format") { formatItems.disabled(off) }
        CommandMenu("Block") { blockItems.disabled(off) }
    }
    @ViewBuilder private var formatItems: some View {
            Button("Fett") { ed?.format("bold") }.keyboardShortcut("b", modifiers: .command)
            Button("Kursiv") { ed?.format("italic") }.keyboardShortcut("i", modifiers: .command)
            Button("Unterstrichen") { ed?.format("underline") }.keyboardShortcut("u", modifiers: .command)
            Button("Durchgestrichen") { ed?.format("strike") }.keyboardShortcut("x", modifiers: [.command, .shift])
            Button("Markieren") { ed?.format("mark") }.keyboardShortcut("h", modifiers: [.command, .shift])
            Button("Code") { ed?.format("code") }.keyboardShortcut("e", modifiers: [.command, .shift])   // ⌘E bleibt "Auswahl suchen" (System)
            Divider()
            Button("Link …") { ed?.format("link") }.keyboardShortcut("k", modifiers: [.command, .shift])
    }
    @ViewBuilder private var blockItems: some View {
            Button("Text") { conv("paragraph", 0) }.keyboardShortcut("0", modifiers: [.command, .option])
            Button("Überschrift 1") { conv("header", 1) }.keyboardShortcut("1", modifiers: [.command, .option])
            Button("Überschrift 2") { conv("header", 2) }.keyboardShortcut("2", modifiers: [.command, .option])
            Button("Überschrift 3") { conv("header", 3) }.keyboardShortcut("3", modifiers: [.command, .option])
            Button("Checkliste") { conv("checklist", 0) }.keyboardShortcut("4", modifiers: [.command, .option])
            Button("Aufzählung") { conv("unordered", 0) }.keyboardShortcut("5", modifiers: [.command, .option])
            Button("Nummerierte Liste") { conv("ordered", 0) }.keyboardShortcut("6", modifiers: [.command, .option])
            Button("Toggle") { conv("toggle", 0) }.keyboardShortcut("7", modifiers: [.command, .option])
            Button("Code") { conv("code", 0) }.keyboardShortcut("8", modifiers: [.command, .option])
            Button("Zitat") { conv("quote", 0) }
            Button("Hinweis") { conv("callout", 0) }
            Divider()
            Button("Nach oben") { if let b = block { ed?.move(b, by: -1) } }.keyboardShortcut(.upArrow, modifiers: [.command, .shift])
            Button("Nach unten") { if let b = block { ed?.move(b, by: 1) } }.keyboardShortcut(.downArrow, modifiers: [.command, .shift])
            Button("Duplizieren") { if let b = block { ed?.duplicate(b) } }.keyboardShortcut("d", modifiers: .command)
            Button("Löschen") { if let b = block { ed?.delete(b) } }
    }
    private func conv(_ t: String, _ l: Int) { if let b = block { ed?.convert(b, to: t, level: l == 0 ? 2 : l) } }
}


// MARK: - Dialoge der Seite: Schaltflaechen, Einbetten, Datei (eigener Modifier - sonst rechnet der Compiler zu lange)

struct PageEditorSheets: ViewModifier {
    @ObservedObject var ed: PageEditor
    @ObservedObject var doc: PageDoc
    @EnvironmentObject var store: Store
    func body(content: Content) -> some View {
        content
    .confirmationDialog("Block umwandeln?", isPresented: Binding(get: { ed.askConvert != nil }, set: { if !$0 { ed.askConvert = nil } }), titleVisibility: .visible, presenting: ed.askConvert) { a in
            Button("Umwandeln", role: .destructive) { ed.convert(a.id, to: a.type, level: a.level); ed.askConvert = nil }
            Button("Abbrechen", role: .cancel) { ed.askConvert = nil }
        } message: { _ in Text("Unterpunkte bzw. der Inhalt des Aufklapp-Blocks gehen dabei verloren.") }
    .sheet(item: Binding(get: { ed.configButtons.map(MoveTarget.init(idString:)) }, set: { if $0 == nil { ed.configButtons = nil } })) { _ in
            if let b = ed.configButtons, let (arr, i) = ed.locate(b), let blk = arr[i] as? NSMutableDictionary, let d = blk["data"] as? NSMutableDictionary {
                ButtonsConfig(data: d).environmentObject(ed).environmentObject(store)
            }
        }
            .sheet(item: Binding(get: { ed.embedFor.map(MoveTarget.init(idString:)) }, set: { if $0 == nil { ed.embedFor = nil } })) { _ in
            EmbedPrompt { url in
                if let url, let b = ed.embedFor, let (arr, i) = ed.locate(b) {
                    arr.replaceObject(at: i, with: NSMutableDictionary(dictionary: ["id": b, "type": "embed", "data": NSMutableDictionary(dictionary: ["url": url])])); ed.changed()
                }
                ed.embedFor = nil
            }
        }
            .fileImporter(isPresented: Binding(get: { ed.fileFor != nil }, set: { if !$0 { ed.fileFor = nil } }), allowedContentTypes: [.item]) { res in
            guard let b = ed.fileFor, case .success(let url) = res else { ed.fileFor = nil; return }
            Task {
                let ok = url.startAccessingSecurityScopedResource(); defer { if ok { url.stopAccessingSecurityScopedResource() } }
                do {
                    if let e = doc.uploadBlocked { throw APIError.other(e) }
                    let data = try Data(contentsOf: url)
                    let f = try await store.uploadFile(data, name: url.lastPathComponent, note: doc.uploadNote)
                    if let (arr, i) = ed.locate(b) {
                        arr.replaceObject(at: i, with: NSMutableDictionary(dictionary: ["id": b, "type": "attaches", "data": NSMutableDictionary(dictionary: ["file": f, "title": f["name"] ?? url.lastPathComponent])])); ed.changed()
                    }
                } catch { doc.error = error.localizedDescription }
                ed.fileFor = nil
            }
        }
            .sheet(item: Binding(get: { ed.ncFor.map(MoveTarget.init(idString:)) }, set: { if $0 == nil { ed.ncFor = nil } })) { _ in
                NextcloudPicker(linkAllowed: !FedLogic.isRemote(doc.id), note: doc.uploadNote) { r in
                    if let b = ed.ncFor { ed.applyNextcloud(r, into: b) } else { ed.ncFor = nil }
                }.environmentObject(store)
            }
            .focusedSceneValue(\.pageEditor, ed)
            .focusedSceneValue(\.editorBlock, ed.locked ? nil : ed.focusedBlock)   // Menueleiste nur fuer den bearbeiteten Block
    }
}
