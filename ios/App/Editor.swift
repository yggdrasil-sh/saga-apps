import SwiftUI
import PhotosUI

// MARK: - Inline: gespeichertes HTML <-> Bearbeitungs-Text (markdown-artig, verlustfrei fuer unsere Tags)

/// Einmal uebersetzte Muster (htmlToMD/mdToHTML laufen bei jedem Bearbeiten)
private let reCacheLock = NSLock()
nonisolated(unsafe) private var reCache: [String: NSRegularExpression] = [:]
private func reReplace(_ s: String, _ pattern: String, _ tmpl: String) -> String {
    reCacheLock.lock()
    let re: NSRegularExpression
    if let c = reCache[pattern] { re = c } else { re = try! NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators, .caseInsensitive]); reCache[pattern] = re }
    reCacheLock.unlock()
    return re.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: tmpl)
}

/// <b>x</b> -> **x**, <i> -> _x_, <u> -> ++x++, <s> -> ~~x~~, <mark> -> ==x==, <code> -> `x`, <a href> -> [x](url), <br> -> \n
func htmlToMD(_ html: String) -> String {
    var s = html
    s = reReplace(s, #"<br\s*/?>"#, "\n")
    s = reReplace(s, #"<a\s[^>]*href="([^"]*)"[^>]*>(.*?)</a>"#, "[$2]($1)")
    for _ in 0..<3 {                                                   // verschachtelte Tags
        s = reReplace(s, #"<(b|strong)(?:\s[^>]*)?>(.*?)</\1>"#, "**$2**")
        s = reReplace(s, #"<(i|em)(?:\s[^>]*)?>(.*?)</\1>"#, "_$2_")
        s = reReplace(s, #"<u(?:\s[^>]*)?>(.*?)</u>"#, "++$1++")
        s = reReplace(s, #"<(s|strike|del)(?:\s[^>]*)?>(.*?)</\1>"#, "~~$2~~")
        s = reReplace(s, #"<mark(?:\s[^>]*)?>(.*?)</mark>"#, "==$1==")
        s = reReplace(s, #"<code(?:\s[^>]*)?>(.*?)</code>"#, "`$1`")
    }
    s = reReplace(s, #"<[^>]+>"#, "")                                  // uebrige Tags entfernen
    return decodeEntities(s)
}

func mdToHTML(_ md: String) -> String {
    var s = md.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
    s = reReplace(s, #"\[([^\]\n]+)\]\(((?:https?://|mailto:|/notes#)[^)\s"]+)\)"#, #"<a href="$2">$1</a>"#)
    s = reReplace(s, #"`([^`\n]+)`"#, "<code>$1</code>")
    s = reReplace(s, #"\*\*(.+?)\*\*"#, "<b>$1</b>")
    s = reReplace(s, #"~~(.+?)~~"#, "<s>$1</s>")
    s = reReplace(s, #"==(.+?)=="#, "<mark>$1</mark>")
    s = reReplace(s, #"\+\+(.+?)\+\+"#, "<u>$1</u>")
    s = reReplace(s, #"(?<![\w])_(.+?)_(?![\w])"#, "<i>$1</i>")
    return s.replacingOccurrences(of: "\n", with: "<br>")
}

func newBlockID() -> String { String((0..<10).map { _ in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".randomElement()! }) }

// MARK: - Editor: Fokus, Blockoperationen, verzoegertes Speichern

@MainActor
final class PageEditor: ObservableObject {
    @Published var focus: String? {                // "blockID" oder "blockID/0/2" (Listenpunkt)
        didSet { if focus != nil && selected != nil { selected = nil } }      // Schreiben hebt die Bild-Auswahl auf
    }
    /// ausgewaehlter Bildblock (2.1-c): Kopieren, Ausschneiden, Loeschen ueber Tastatur/Menue (BlockClipboard)
    @Published var selected: String?
    @Published var locked = false                    // Seite gesperrt: nur lesen (wie im Web)
    let hover = HoverModel()                         // eigenes Modell: nur die Griffleiste hoert darauf
    @Published var pickImage = false                 // "/Bild" -> Fotos-Auswahl
    @Published var pickImmich = false                // "/Bild aus Immich" -> Immich-Auswahl
    @Published var linkDBFor: String?                // "/Verknuepfte Datenbank" -> Auswahl fuer diesen Block
    @Published var openPage: Int?                    // "/Seite" -> neue Unterseite oeffnen
    @Published var configButtons: String?            // Schaltflaechen-Block einrichten
    @Published var embedFor: String?                 // "/Einbetten" -> Adresse abfragen
    @Published var fileFor: String?                  // "/Datei" -> Datei waehlen und hochladen
    @Published var picked: [PhotosPickerItem] = []
    // Kommentare (1.3): Zahl am Block (offene Unterhaltungen), ungelesen, Kommentieren erlaubt, Block zum Kommentieren
    @Published var commentCounts: [String: Int] = [:]
    @Published var commentUnread: Set<String> = []
    @Published var canComment = false
    /// Rolle Kommentieren: Tippen auf einen Absatz oeffnet die Kommentare dazu (Editor ist schreibgeschuetzt)
    @Published var tapToComment = false
    /// Fremde Seite: Aufgaben/Kalender/Immich nur als Platzhalter
    @Published var foreign = false
    @Published var commentBlock: String?
    /// Block, nach dem Bilder aus einer Auswahl (Fotos/Immich) landen: beim Oeffnen gemerkt, weil das Sheet den
    /// Tastaturfokus nimmt (ed.focus -> nil); nach jedem Bild das Bild selbst (mehrere Fotos in Reihenfolge)
    var imageAnchor: String?
    let doc: PageDoc
    weak var store: Store?
    private var saveTask: Task<Void, Never>?
    init(doc: PageDoc) { self.doc = doc; SaveRegistry.add(self) }

    /// Strukturaenderung (Block einfuegen/loeschen/verschieben/Haken): Seite neu zeichnen + speichern
    func changed() { doc.objectWillChange.send(); textChanged() }
    /// Tippen: NUR speichern - das Textfeld zeigt den Text selbst, die Seite muss nicht neu gezeichnet werden
    func textChanged() {
        doc.markEdited()
        if let store { doc.noteInput(store) }       // Entwurf sofort, Senden nach 700 ms (BUG-100)
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(700))
            guard let self, !Task.isCancelled, let store = self.store else { return }
            await self.doc.save(store)
        }
    }
    /// Rolle/Zaehler der Seite uebernehmen (nur Geaendertes setzen: jede Aenderung zeichnet die Bloecke neu)
    func applyAccess(_ a: PageAccess) {
        if commentCounts != a.commentCounts { commentCounts = a.commentCounts }
        if canComment != (a.commentsSupported && a.canComment) { canComment = a.commentsSupported && a.canComment }
        if tapToComment != (a.commentsSupported && a.role == .comment) { tapToComment = a.commentsSupported && a.role == .comment }
        if foreign != a.foreign { foreign = a.foreign }
    }
    /// Sofort speichern (Verlassen, Hintergrund, Beenden) und warten, bis es durch ist
    func flush() async { saveTask?.cancel(); if let store { await doc.save(store) } }

    var focusedBlock: String? { focus?.split(separator: "/").first.map(String.init) }

    /// Block im Baum finden (oberste Ebene, Toggles, Spalten)
    func locate(_ id: String, in arr: NSMutableArray? = nil) -> (NSMutableArray, Int)? {
        let a = arr ?? doc.root
        for (i, raw) in a.enumerated() {
            guard let b = raw as? NSMutableDictionary else { continue }
            if b["id"] as? String == id { return (a, i) }
            let d = b["data"] as? NSMutableDictionary
            if let inner = d?["blocks"] as? NSMutableArray, let r = locate(id, in: inner) { return r }
            if let cols = d?["cols"] as? NSArray {
                for c in cols { if let inner = (c as? NSDictionary)?["blocks"] as? NSMutableArray, let r = locate(id, in: inner) { return r } }
            }
        }
        return nil
    }

    static func make(_ type: String, text: String = "", level: Int = 2) -> NSMutableDictionary {
        let data: NSMutableDictionary
        switch type {
        case "header": data = ["text": text, "level": level]
        case "checklist", "unordered", "ordered":
            data = ["style": type, "meta": NSMutableDictionary(), "items": NSMutableArray(array: [NSMutableDictionary(dictionary: ["content": text, "meta": NSMutableDictionary(dictionary: type == "checklist" ? ["checked": false] : [:]), "items": NSMutableArray()])])]
        case "quote": data = ["text": text, "caption": "", "alignment": "left"]
        case "callout": data = ["emoji": "💡", "text": text]
        case "toggle": data = ["title": text, "open": true, "blocks": NSMutableArray(array: [make("paragraph")])]
        case "code": data = ["code": text]
        case "delimiter": data = [:]
        default: data = ["text": text]
        }
        return NSMutableDictionary(dictionary: ["id": newBlockID(), "type": ["checklist", "unordered", "ordered"].contains(type) ? "list" : type, "data": data])
    }

    /// Neuen Block nach `after` (oder am Ende) einfuegen und fokussieren
    @discardableResult
    func insert(_ block: NSMutableDictionary, after: String?) -> String {
        if let after, let (arr, i) = locate(after) { arr.insert(block, at: i + 1) } else { doc.root.add(block) }
        let id = block["id"] as! String
        if block["type"] as? String == "list" { focus = id + "/0" } else if block["type"] as? String != "delimiter" { focus = id }
        changed(); return id
    }

    // MARK: Spalten (wie Notion: Bloecke in Spalten hinein/heraus, gestapelte Spalten-Bloecke zusammenfuehren)

    /// Liegt der Block in einer Spalte? -> (Spalten-Block, dessen Liste + Index, Spaltennummer)
    func columnOf(_ id: String, in arr: NSMutableArray? = nil) -> (cols: NSMutableDictionary, parent: NSMutableArray, index: Int, col: Int)? {
        let a = arr ?? doc.root
        for (i, raw) in a.enumerated() {
            guard let b = raw as? NSMutableDictionary, let d = b["data"] as? NSMutableDictionary else { continue }
            if b["type"] as? String == "columns", let cols = d["cols"] as? NSArray {
                for (ci, c) in cols.enumerated() {
                    guard let inner = (c as? NSDictionary)?["blocks"] as? NSMutableArray else { continue }
                    if inner.contains(where: { ($0 as? NSMutableDictionary)?.blockID == id }) { return (b, a, i, ci) }
                    if let r = columnOf(id, in: inner) { return r }
                }
            }
            if let inner = d["blocks"] as? NSMutableArray, let r = columnOf(id, in: inner) { return r }
        }
        return nil
    }
    func colArray(_ cols: NSMutableDictionary, _ ci: Int) -> NSMutableArray? {
        ((cols["data"] as? NSMutableDictionary)?["cols"] as? NSArray)?[safe: ci].flatMap { ($0 as? NSMutableDictionary)?["blocks"] as? NSMutableArray }
    }
    func colCount(_ cols: NSMutableDictionary) -> Int { ((cols["data"] as? NSDictionary)?["cols"] as? NSArray)?.count ?? 0 }

    /// Spalten-Block direkt UEBER diesem Block (gleiche Ebene)?
    func columnsAbove(_ id: String) -> NSMutableDictionary? {
        guard let (arr, i) = locate(id), i > 0, let prev = arr[i - 1] as? NSMutableDictionary, prev["type"] as? String == "columns" else { return nil }
        return prev
    }
    /// Spalten-Block direkt DARUNTER (fuer "zusammenfuehren")?
    func columnsBelow(_ id: String) -> NSMutableDictionary? {
        guard let (arr, i) = locate(id), i + 1 < arr.count, let next = arr[i + 1] as? NSMutableDictionary, next["type"] as? String == "columns" else { return nil }
        return next
    }
    func isColumns(_ id: String) -> Bool { locate(id).map { ($0.0[$0.1] as? NSDictionary)?["type"] as? String == "columns" } ?? false }
    /// Spalten-Block mit dem Spalten-Block DARUEBER zusammenfuehren
    func mergeColumnsInto(above: NSMutableDictionary, _ id: String) {
        guard let (arr, _) = locate(id), let ai = Optional(arr.index(of: above)), ai != NSNotFound, let aboveID = (above["id"] as? String) else { return }
        _ = ai; mergeColumns(aboveID)
    }
    /// Block ans Ende einer Spalte verschieben (aus einer Spalte oder von unterhalb der Spalten)
    func moveIntoColumn(_ id: String, cols: NSMutableDictionary, col: Int) {
        if isColumns(id) { return }                      // Spalten in Spalten kann der Web-Editor nicht darstellen
        guard let (arr, i) = locate(id), let target = colArray(cols, col) else { return }
        let b = arr[i]; arr.removeObject(at: i); target.add(b)
        if let c = columnOf(id), c.cols === cols {} ; changed()
    }
    /// Block aus den Spalten herausnehmen -> direkt unter den Spalten-Block
    func moveOutOfColumns(_ id: String) {
        guard let c = columnOf(id), let (arr, i) = locate(id) else { return }
        let b = arr[i]; arr.removeObject(at: i)
        let idx = c.parent.index(of: c.cols); c.parent.insert(b, at: idx == NSNotFound ? c.parent.count : idx + 1); changed()
    }
    /// Zwei uebereinanderliegende Spalten-Bloecke zusammenfuehren: Spalte 1 + Spalte 1, ...
    func mergeColumns(_ id: String) {
        guard let (arr, i) = locate(id), let a = arr[i] as? NSMutableDictionary, let b = columnsBelow(id) else { return }
        let n = max(colCount(a), colCount(b))
        let ad = a["data"] as? NSMutableDictionary ?? NSMutableDictionary()
        let merged = NSMutableArray()
        for ci in 0..<min(4, n) {
            let blocks = NSMutableArray()
            if let x = colArray(a, ci) { blocks.addObjects(from: x as! [Any]) }
            if let y = colArray(b, ci) { blocks.addObjects(from: y as! [Any]) }
            merged.add(NSMutableDictionary(dictionary: ["blocks": blocks]))
        }
        ad["cols"] = merged; a["data"] = ad
        arr.removeObject(at: i + 1); changed()
    }
    /// Unter einer Spalte weiterschreiben
    func continueInColumn(_ blocks: NSMutableArray) {
        if let last = blocks.lastObject as? NSMutableDictionary, last["type"] as? String == "paragraph",
           ((last["data"] as? NSDictionary)?["text"] as? String ?? "").isEmpty { focus = last.blockID; return }
        let p = Self.make("paragraph"); blocks.add(p); focus = p.blockID; changed()
    }

    /// Return im Seitentitel (wie im Web): in den ersten Textblock springen, sonst oben einen Absatz anlegen
    func enterFromTitle() {
        guard !locked, doc.fetched, !doc.isDatabase else { return }
        if let first = doc.root.firstObject as? NSMutableDictionary, ["paragraph", "header"].contains(first["type"] as? String ?? "") {
            focus = first.blockID; return
        }
        let b = Self.make("paragraph"); doc.root.insert(b, at: 0)
        focus = b["id"] as? String; changed()
    }
    /// Klick unter den letzten Block: leeren Absatz am Ende fokussieren oder anlegen (wie Notion)
    func continueWriting() {
        if let last = doc.root.lastObject as? NSMutableDictionary, last["type"] as? String == "paragraph",
           ((last["data"] as? NSDictionary)?["text"] as? String ?? "").isEmpty { focus = last.blockID; return }
        insert(Self.make("paragraph"), after: nil)
    }

    /// Block kopieren (mit neuen IDs) und direkt darunter einfuegen
    func duplicate(_ id: String) {
        guard let (arr, i) = locate(id), let d = try? JSONSerialization.data(withJSONObject: arr[i]),
              let copy = try? JSONSerialization.jsonObject(with: d, options: .mutableContainers) as? NSMutableDictionary else { return }
        func renew(_ b: NSMutableDictionary) {
            b["id"] = newBlockID()
            let dd = b["data"] as? NSMutableDictionary
            ((dd?["blocks"] as? NSArray) ?? []).forEach { if let x = $0 as? NSMutableDictionary { renew(x) } }
            ((dd?["cols"] as? NSArray) ?? []).forEach { (($0 as? NSDictionary)?["blocks"] as? NSArray)?.forEach { if let x = $0 as? NSMutableDictionary { renew(x) } } }
        }
        renew(copy); arr.insert(copy, at: i + 1); changed()
    }

    func delete(_ id: String) {
        guard let (arr, i) = locate(id) else { return }
        let prev = i > 0 ? (arr[i - 1] as? NSMutableDictionary)?["id"] as? String : nil
        arr.removeObject(at: i)
        if arr === doc.root && arr.count == 0 { arr.add(Self.make("paragraph")) }
        focus = prev; changed()
    }

    func move(_ id: String, by d: Int) {
        guard let (arr, i) = locate(id), i + d >= 0, i + d < arr.count else { return }
        let b = arr[i]; arr.removeObject(at: i); arr.insert(b, at: i + d); changed()
    }

    /// Blocktyp aendern; Text bleibt erhalten (Liste -> einzelne Absaetze)
    func convert(_ id: String, to type: String, level: Int = 2) {
        guard let (arr, i) = locate(id), let b = arr[i] as? NSMutableDictionary else { return }
        let d = b["data"] as? NSMutableDictionary ?? [:]
        var texts: [String]
        if b["type"] as? String == "list" {
            texts = ((d["items"] as? NSArray) ?? []).compactMap { ($0 as? NSDictionary)?["content"] as? String }
        } else { texts = [(d["text"] as? String) ?? (d["title"] as? String) ?? ""] }
        if texts.isEmpty { texts = [""] }
        let nb: NSMutableDictionary
        if ["checklist", "unordered", "ordered"].contains(type) {
            nb = Self.make(type)
            let items = NSMutableArray(array: texts.map { NSMutableDictionary(dictionary: ["content": $0, "meta": NSMutableDictionary(dictionary: type == "checklist" ? ["checked": false] : [:]), "items": NSMutableArray()]) })
            (nb["data"] as! NSMutableDictionary)["items"] = items
            arr.replaceObject(at: i, with: nb); arr.removeObject(at: i); arr.insert(nb, at: i)
            nb["id"] = id; focus = id + "/0"
        } else {
            nb = Self.make(type, text: texts[0], level: level); nb["id"] = id
            arr.replaceObject(at: i, with: nb)
            for (k, t) in texts.dropFirst().enumerated() { arr.insert(Self.make(type, text: t, level: level), at: i + 1 + k) }
            focus = id
        }
        changed()
    }
}

// MARK: - Bearbeitbares Textfeld: formatiert bearbeiten (ohne Zeichen), sonst gerendert

/// Eintraege des "/"-Menues: (Name, Typ, Ebene, Symbol, Suchwoerter)
let slashItems: [(String, String, Int, String, String)] = [
    (String(localized: "Text"), "paragraph", 0, "text.alignleft", "text absatz paragraph texto parrafo"),
    (String(localized: "Überschrift 1"), "header", 1, "textformat.size.larger", "h1 ueberschrift heading titel titulo encabezado"),
    (String(localized: "Überschrift 2"), "header", 2, "textformat.size", "h2 ueberschrift heading titulo encabezado"),
    (String(localized: "Überschrift 3"), "header", 3, "textformat.size.smaller", "h3 ueberschrift heading titulo encabezado"),
    (String(localized: "Überschrift 4"), "header", 4, "textformat", "h4 ueberschrift heading titulo encabezado"),
    (String(localized: "Checkliste"), "checklist", 0, "checklist", "todo checkliste aufgabe check casilla tareas"),
    (String(localized: "Aufzählung"), "unordered", 0, "list.bullet", "liste aufzaehlung bullet punkt lista vinetas"),
    (String(localized: "Nummeriert"), "ordered", 0, "list.number", "nummeriert liste zahl ordered numerada"),
    (String(localized: "Zitat"), "quote", 0, "text.quote", "zitat quote cita"),
    (String(localized: "Hinweis"), "callout", 0, "lightbulb", "hinweis callout info aviso nota"),
    (String(localized: "Toggle"), "toggle", 0, "chevron.right.circle", "toggle aufklappen desplegable"),
    (String(localized: "Code"), "code", 0, "chevron.left.forwardslash.chevron.right", "code codigo"),
    (String(localized: "Trenner"), "delimiter", 0, "minus", "trenner linie divider separador linea"),
    (String(localized: "Bild"), "image", 0, "photo", "bild foto image screenshot imagen"),
    (String(localized: "Bild aus Immich"), "immich_image", 0, "photo.on.rectangle.angled", "immich bild foto album imagen"),
    (String(localized: "Seite"), "page", 0, "doc.badge.plus", "seite unterseite page pagina subpagina"),
    (String(localized: "Datenbank"), "db_new", 0, "tablecells", "datenbank database tabelle board galerie kalender inline base de datos tabla"),
    (String(localized: "Verknüpfte Datenbank"), "db_link", 0, "link", "verknuepfte datenbank linked view ansicht base de datos vinculada vista"),
    (String(localized: "Aufgaben"), "tasks", 0, "checklist.checked", "aufgaben tasks todo tareas"),
    (String(localized: "Kalender"), "calendar", 0, "calendar", "kalender termine calendar calendario eventos"),
    (String(localized: "Tabelle"), "table", 0, "tablecells.badge.ellipsis", "tabelle table raster zellen tabla celdas"),
    (String(localized: "Spalten"), "columns", 0, "rectangle.split.2x1", "spalten columns nebeneinander columnas"),
    (String(localized: "Schaltfläche"), "buttons", 0, "button.horizontal.top.press", "schaltflaeche button knopf aktion boton accion"),
    (String(localized: "Einbetten"), "embed", 0, "play.rectangle", "einbetten embed video youtube link karte insertar incrustar"),
    (String(localized: "Datei"), "file", 0, "paperclip", "datei anhang file pdf upload archivo adjunto"),
]

struct EditText: View {
    let key: String
    let html: String
    var font: Font = .body
    var placeholder = String(localized: "Tippe etwas …")
    var splitOnReturn = true
    var raw = false                                    // Code: Text unveraendert, ohne Formatierung
    var muted = false
    var boldBase = false                               // Ueberschriften/Toggle-Titel: Grundschrift ist fett
    var slash = false                                  // "/"-Menue erlaubt (Absaetze)
    let setHTML: (String) -> Void
    var onReturn: ((String, String) -> Void)? = nil    // (vorher, nachher) als HTML
    @EnvironmentObject var ed: PageEditor
    @Environment(\.fontResolutionContext) private var fctx
    @FocusState private var focused: Bool
    @State private var text = ""                       // Code
    @State private var sel: TextSelection?
    @State private var rich = AttributedString()       // formatierter Text
    @State private var rsel = AttributedTextSelection()
    @State private var mSel = 0                        // Auswahl in der @/:-Liste
    @State private var mClosed: String?                // Esc: diese Eingabe nicht mehr vorschlagen
    @State private var linkAsk = false
    @State private var linkText = ""
    @EnvironmentObject private var store: Store

    /// Cursor als Zeichen-Offset (nil bei markiertem Text)
    private var cursor: Int? {
        switch rsel.indices(in: rich) {
        case .insertionPoint(let i):
            guard i >= rich.startIndex, i <= rich.endIndex else { return nil }
            return rich.characters.distance(from: rich.startIndex, to: i)
        case .ranges(let r): return r.isEmpty ? rich.characters.count : nil
        }
    }
    /// Schluessel fuer Esc: diese Eingabe an dieser Stelle nicht mehr vorschlagen
    private var mKey: String { "\(cursor ?? -1)|" + String(rich.characters) }
    private var trigger: (trigger: HKTrigger, start: Int)? {
        guard !raw, let c = cursor else { return nil }
        guard let m = hkTrigger(String(rich.characters), cursor: c), mClosed != mKey else { return nil }
        return m
    }
    private var mentions: [MentionItem] { trigger.map { mentionItems($0.trigger, store: store, font: font) } ?? [] }
    private func pickMention(_ it: MentionItem) {
        guard let m = trigger else { return }
        let c = replaceTrigger(&rich, m.trigger, at: m.start, with: it.insert, font: font); mSel = 0
        rsel = AttributedTextSelection(insertionPoint: rich.characters.index(rich.startIndex, offsetBy: min(c, rich.characters.count)))
    }

    private var slashQuery: String? {
        guard slash else { return nil }
        let s = String(rich.characters)
        return s.hasPrefix("/") && !s.contains(" ") && !s.contains("\n") && s.count <= 20 ? String(s.dropFirst()).lowercased() : nil
    }

    var body: some View {
        if ed.focus == key {
            if raw {
                TextField(placeholder, text: $text, selection: $sel, axis: .vertical)
                    .font(font).focused($focused)
                    .onAppear { text = html; sel = TextSelection(insertionPoint: text.endIndex); DispatchQueue.main.async { focused = true } }
                    .onChange(of: text) { _, new in if new != html { setHTML(new) } }
                    .onChange(of: focused) { _, f in if !f && ed.focus == key { ed.focus = nil } }
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    TextEditor(text: $rich, selection: $rsel)
                        .font(font)
                        .scrollDisabled(true)
                        .scrollContentBackground(.hidden)
                        #if os(iOS)
                        .padding(.horizontal, -5).padding(.vertical, -8)          // Innenabstand des Editors ausgleichen
                        #endif
                        .frame(minHeight: 22)
                        .focused($focused)
                        .overlay(alignment: .topLeading) {
                            if rich.characters.isEmpty { Text(placeholder).font(font).foregroundStyle(.tertiary).allowsHitTesting(false) }
                        }
                        .onKeyPress(keys: [.upArrow, .downArrow, .return, .tab, .escape]) { kp in   // @/:-Liste mit Tastatur
                            let items = mentions
                            guard !items.isEmpty else { return .ignored }
                            switch kp.key {
                            case .upArrow: mSel = (mSel + items.count - 1) % items.count
                            case .downArrow: mSel = (mSel + 1) % items.count
                            case .escape: mClosed = mKey
                            default: pickMention(items[min(mSel, items.count - 1)])
                            }
                            return .handled
                        }
                    if let q = slashQuery { SlashMenu(query: q) { type, level in ed.slashApply(blockOf(key), type: type, level: level) } }
                    else if !mentions.isEmpty { MentionMenu(items: mentions, selected: min(mSel, mentions.count - 1)) { pickMention($0) } }
                }
                .onAppear {
                    rich = richText(html, base: font)
                    rsel = AttributedTextSelection(insertionPoint: rich.endIndex)
                    DispatchQueue.main.async { focused = true }
                }
                .onChange(of: rich) { old, new in
                    let o = String(old.characters), n = String(new.characters)
                    if splitOnReturn, slashQuery == nil, let onReturn, n.filter({ $0 == "\n" }).count > o.filter({ $0 == "\n" }).count {
                        let p = o.commonPrefix(with: n).count
                        let idx = new.characters.index(new.startIndex, offsetBy: min(p, new.characters.count))
                        let before = AttributedString(new[new.startIndex..<idx])
                        let after = idx < new.endIndex ? AttributedString(new[new.characters.index(after: idx)..<new.endIndex]) : AttributedString()
                        let bh = richToHTML(before, ctx: fctx, baseBold: boldBase), ah = richToHTML(after, ctx: fctx, baseBold: boldBase)
                        setHTML(bh); onReturn(bh, ah)
                    } else {
                        let h = richToHTML(new, ctx: fctx, baseBold: boldBase)
                        if h != html { setHTML(h) }                              // nur echte Aenderungen speichern
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: .hkFormat)) { n in   // Leiste: fett, kursiv, ...
                    guard ed.focus == key, let a = n.userInfo?["action"] as? String else { return }
                    rich.transformAttributes(in: &rsel) { c in
                        let f = c.font ?? font
                        switch a {
                        case "bold": c.font = f.bold(!f.resolve(in: fctx).isBold)
                        case "italic": c.font = f.italic(!f.resolve(in: fctx).isItalic)
                        case "underline": c.underlineStyle = c.underlineStyle == nil ? .single : nil
                        case "strike": c.strikethroughStyle = c.strikethroughStyle == nil ? .single : nil
                        case "mark": c.backgroundColor = c.backgroundColor == hkMarkColor ? nil : hkMarkColor
                        case "code":
                            if c.backgroundColor == hkCodeColor { c.backgroundColor = nil; c.font = font } else { c.backgroundColor = hkCodeColor; c.font = font.monospaced() }
                        default: break
                        }
                    }
                    if a == "link" { linkText = ""; linkAsk = true }
                }
                .alert("Link", isPresented: $linkAsk) {
                    TextField("https://… oder /notes#12", text: $linkText)
                    Button("Setzen") {
                        let u = linkText.trimmingCharacters(in: .whitespaces)
                        rich.transformAttributes(in: &rsel) { c in c.link = u.isEmpty ? nil : linkURL(u) }
                    }
                    Button("Link entfernen", role: .destructive) { rich.transformAttributes(in: &rsel) { c in c.link = nil } }
                    Button("Abbrechen", role: .cancel) {}
                }
                .onChange(of: focused) { _, f in if !f && ed.focus == key { ed.focus = nil } }
            }
        } else {
            Group {
                if html.isEmpty { Text(placeholder).foregroundStyle(.tertiary) }
                else if raw { Text(html) }
                else { Text(inlineText(html)).foregroundStyle(muted ? .secondary : .primary) }
            }
            .font(font)
            .frame(maxWidth: .infinity, minHeight: 22, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { if !ed.locked { ed.focus = key } else if ed.tapToComment { ed.commentBlock = blockOf(key) } }
        }
    }
    private func blockOf(_ k: String) -> String { String(k.split(separator: "/").first ?? "") }
}

/// "/"-Menue: gefilterte Blocktypen unter dem Absatz
struct SlashMenu: View {
    let query: String
    let pick: (String, Int) -> Void
    var body: some View {
        let items = slashItems.filter { query.isEmpty || $0.0.lowercased().contains(query) || $0.4.contains(query) }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if items.isEmpty { Text("Kein Blocktyp für „\(query)“").font(.subheadline).foregroundStyle(.secondary).padding(10) }
                ForEach(items, id: \.0) { it in
                    Button { pick(it.1, it.2) } label: {
                        Label(it.0, systemImage: it.3).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 8).padding(.horizontal, 12).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            }
        }
        .frame(maxHeight: 320)
        .fixedSize(horizontal: false, vertical: items.count <= 7)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(.secondary.opacity(0.25)))
        .frame(maxWidth: 300)
    }
}

extension PageEditor {
    /// "/"-Auswahl: Absatz leeren und in den Typ verwandeln
    func slashApply(_ id: String, type: String, level: Int) {
        guard let (arr, i) = locate(id), let b = arr[i] as? NSMutableDictionary else { return }
        (b["data"] as? NSMutableDictionary)?["text"] = ""
        if type == "paragraph" { focus = nil; changed(); DispatchQueue.main.async { self.focus = id }; return }
        if type == "image" { focus = id; changed(); openPhotos(); return }      // Bild kommt nach dem (leeren) Absatz
        if type == "immich_image" { focus = id; changed(); openImmich(); return }
        if type == "table" {
            arr.replaceObject(at: i, with: NSMutableDictionary(dictionary: ["id": id, "type": "table", "data": NSMutableDictionary(dictionary: ["withHeadings": true, "content": [["", ""], ["", ""]]])]))
            focus = nil; changed(); return
        }
        if type == "columns" {
            let cols = NSMutableArray(array: [0, 1].map { _ in NSMutableDictionary(dictionary: ["blocks": NSMutableArray(array: [Self.make("paragraph")])]) })
            arr.replaceObject(at: i, with: NSMutableDictionary(dictionary: ["id": id, "type": "columns", "data": NSMutableDictionary(dictionary: ["cols": cols])]))
            focus = nil; changed(); return
        }
        if type == "buttons" {
            arr.replaceObject(at: i, with: NSMutableDictionary(dictionary: ["id": id, "type": "buttons", "data": NSMutableDictionary(dictionary: ["buttons": [["label": String(localized: "Neu"), "icon": "➕", "action": ""]]])]))
            focus = nil; changed(); configButtons = id; return
        }
        if type == "embed" { focus = nil; embedFor = id; return }
        if type == "file" { focus = nil; fileFor = id; return }
        if ["tasks", "calendar"].contains(type) {
            arr.replaceObject(at: i, with: NSMutableDictionary(dictionary: ["id": id, "type": type, "data": NSMutableDictionary()])); focus = nil; changed(); return
        }
        if type == "db_link" { focus = nil; linkDBFor = id; return }
        if type == "db_new" || type == "page" {
            focus = nil
            Task { @MainActor in
                guard let store = self.store else { return }
                struct R: Decodable { let id: Int }
                let body: [String: Any] = type == "db_new" ? ["kind": "database", "title": String(localized: "Neue Datenbank"), "icon": "🗃️", "parent_id": self.doc.id] : ["title": "", "parent_id": self.doc.id]
                guard let d = try? await store.api("api/notes", method: "POST", body: body), let r = try? JSONDecoder().decode(R.self, from: d),
                      let (a, j) = self.locate(id) else { self.doc.error = String(localized: "Konnte nicht angelegt werden"); return }
                if type == "db_new" {
                    a.replaceObject(at: j, with: NSMutableDictionary(dictionary: ["id": id, "type": "database", "data": NSMutableDictionary(dictionary: ["db": r.id])]))
                } else {   // Unterseite: Verweis-Absatz wie im Web, dann oeffnen
                    (a[j] as? NSMutableDictionary)?["data"] = NSMutableDictionary(dictionary: ["text": "📄 <a href=\"/notes#\(r.id)\">\(String(localized: "Unbenannt"))</a>"])
                }
                self.changed(); await self.flush(); await store.refresh()
                if type == "page" { self.openPage = r.id }
            }
            return
        }
        if type == "delimiter" { arr.replaceObject(at: i, with: Self.make("delimiter")); insert(Self.make("paragraph"), after: (arr[i] as? NSMutableDictionary)?.blockID); return }
        convert(id, to: type, level: level == 0 ? 2 : level)
    }
    /// Vorhandene Datenbank als Block einsetzen (eigene Ansichten legt das Web beim ersten Oeffnen an)
    func linkDatabase(_ db: Int, into id: String) {
        guard let (arr, i) = locate(id) else { return }
        arr.replaceObject(at: i, with: NSMutableDictionary(dictionary: ["id": id, "type": "database", "data": NSMutableDictionary(dictionary: ["db": db])]))
        linkDBFor = nil; changed()
    }
    func format(_ action: String) { NotificationCenter.default.post(name: .hkFormat, object: nil, userInfo: ["action": action]) }
}
extension Notification.Name { static let hkFormat = Notification.Name("hkFormat") }

// MARK: - Leiste ueber der Tastatur

struct BlockTypeMenu: View {
    let title: LocalizedStringKey
    let icon: String
    /// Tastaturleiste "Neu": Bilder (Fotos, Immich) gleich mit anbieten
    var images: PageEditor? = nil
    let action: (String, Int) -> Void
    var body: some View {
        Menu {
            if let ed = images {
                Button { ed.openPhotos() } label: { Label("Fotos", systemImage: "photo") }
                Button { ed.openImmich() } label: { Label("Bild aus Immich", systemImage: "photo.on.rectangle.angled") }
                #if os(iOS)
                if UIPasteboard.general.hasImages {                 // statt eigenem Knopf (Platz in der Leiste)
                    Button { ed.insertImages(from: UIPasteboard.general.itemProviders) } label: { Label("Bild einsetzen", systemImage: "doc.on.clipboard") }
                }
                #endif
                Divider()
            }
            Button { action("paragraph", 0) } label: { Label("Text", systemImage: "text.alignleft") }
            Button { action("header", 1) } label: { Label("Überschrift 1", systemImage: "textformat.size.larger") }
            Button { action("header", 2) } label: { Label("Überschrift 2", systemImage: "textformat.size") }
            Button { action("header", 3) } label: { Label("Überschrift 3", systemImage: "textformat.size.smaller") }
            Button { action("checklist", 0) } label: { Label("Checkliste", systemImage: "checklist") }
            Button { action("unordered", 0) } label: { Label("Aufzählung", systemImage: "list.bullet") }
            Button { action("ordered", 0) } label: { Label("Nummeriert", systemImage: "list.number") }
            Button { action("quote", 0) } label: { Label("Zitat", systemImage: "text.quote") }
            Button { action("callout", 0) } label: { Label("Hinweis", systemImage: "lightbulb") }
            Button { action("toggle", 0) } label: { Label("Toggle", systemImage: "chevron.right.circle") }
            Button { action("code", 0) } label: { Label("Code", systemImage: "chevron.left.forwardslash.chevron.right") }
            Button { action("delimiter", 0) } label: { Label("Trenner", systemImage: "minus") }
        } label: { Label(title, systemImage: icon) }
    }
}



// MARK: - Bilder einfuegen (Zwischenablage, Fotos)

import UniformTypeIdentifiers
import PhotosUI

extension PageEditor {
    // MARK: Bildblock auswaehlen, kopieren, ausschneiden, einsetzen (2.1-c)

    func select(_ id: String?) {
        if id != nil { focus = nil }
        if selected != id { selected = id }
    }
    /// Bildblock in die Zwischenablage (Bild + Block); Ausschneiden loescht ihn erst, wenn das Kopieren geklappt hat
    func copyBlock(_ id: String, cut: Bool) {
        guard let store, let (arr, i) = locate(id), let b = arr[i] as? NSDictionary, let plain = BlockClipboard.plain(b),
              BlockClipLogic.isImage(plain) else { return }
        let note = doc.id
        Task {
            guard await BlockClipboard.copy(plain, note: note, store: store) else { doc.error = String(localized: "Bild konnte nicht kopiert werden."); return }
            if cut, !locked, doc.fetched, locate(id) != nil { delete(id); selected = nil }
        }
    }
    func deleteSelected() {
        guard !locked, let s = selected, locate(s) != nil else { return }
        selected = nil; delete(s)
    }
    /// Block aus der Zwischenablage: gleiche Seite -> Block uebernehmen (Verschieben per Ausschneiden/Einsetzen),
    /// andere Seite -> Bild neu hochladen (der Server laesst nur Dateien des Seiten-Eigentuemers zu). false = nicht fuer mich
    func pasteBlocks(_ p: BlockClipLogic.Payload) -> Bool {
        guard !locked, doc.fetched, p.blocks.allSatisfy(BlockClipLogic.isImage) else { return false }
        let anchor = focusedBlock ?? selected ?? imageAnchor
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "hkBlockCopyTest") { BlockClipboard.log("einsetzen plan=\(BlockClipLogic.plan(p, server: AppConfig.serverString, note: doc.id)) anker=\(anchor ?? "-")") }
        #endif
        switch BlockClipLogic.plan(p, server: AppConfig.serverString, note: doc.id) {
        case .reuse:
            var after = anchor
            for b in p.blocks {
                let x = BlockClipLogic.renewed(b, newID: newBlockID)
                guard let d = try? JSONSerialization.data(withJSONObject: x),
                      let m = try? JSONSerialization.jsonObject(with: d, options: .mutableContainers) as? NSMutableDictionary else { continue }
                if let a = after, let (arr, i) = locate(a) { arr.insert(m, at: i + 1) } else { doc.root.add(m) }
                after = m.blockID
            }
            imageAnchor = after; focus = nil; selected = after; changed()
            return true
        case .upload:
            guard let data = BlockClipboard.image(mime: p.mime) else { return false }
            let cap = p.blocks.first.map(BlockClipLogic.caption) ?? ""
            imageAnchor = anchor
            Task { await insertImage(data, caption: cap) }
            return true
        }
    }

    /// Bilddaten -> PNG/JPEG (Server nimmt PNG, JPEG, GIF, WebP) -> hochladen -> Bild-Block nach dem aktuellen Block
    func insertImage(_ raw: Data, caption: String = "") async {
        guard let store else { return }
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "hkBlockCopyTest") { BlockClipboard.log("insertImage bytes=\(raw.count)") }
        #endif
        var data = raw, name = "bild.png"
        let head = [UInt8](raw.prefix(4))
        let known = head.starts(with: [0x89, 0x50, 0x4E, 0x47]) || head.starts(with: [0xFF, 0xD8]) || head.starts(with: [0x47, 0x49, 0x46]) || (raw.count > 12 && raw[8..<12] == Data("WEBP".utf8))
        if head.starts(with: [0xFF, 0xD8]) { name = "bild.jpg" } else if head.starts(with: [0x47, 0x49, 0x46]) { name = "bild.gif" }
        if !known {                                                   // z. B. HEIC -> JPEG
            #if os(iOS)
            guard let j = UIImage(data: raw)?.jpegData(compressionQuality: 0.85) else { doc.error = String(localized: "Bildformat nicht unterstützt"); return }
            #else
            guard let img = NSImage(data: raw), let tiff = img.tiffRepresentation, let j = NSBitmapImageRep(data: tiff)?.representation(using: .jpeg, properties: [.compressionFactor: 0.85]) else { doc.error = String(localized: "Bildformat nicht unterstützt"); return }
            #endif
            data = j; name = "bild.jpg"
        }
        do {
            let url = try await store.upload(data, name: name, note: doc.uploadNote)
            insertImageURL(url, caption: caption)
        } catch { doc.error = String(localized: "Bild nicht hochgeladen: \(error.localizedDescription)") }
    }
    /// Bild-Block mit einer schon hochgeladenen Datei (/files/...) nach dem aktuellen Block (z. B. aus Immich)
    func insertImageURL(_ url: String, caption: String = "") {
        let b = NSMutableDictionary(dictionary: ["id": newBlockID(), "type": "image",
            "data": NSMutableDictionary(dictionary: ["file": NSMutableDictionary(dictionary: ["url": url]), "caption": caption, "withBorder": false, "stretched": false, "withBackground": false])])
        imageAnchor = insert(b, after: focusedBlock ?? imageAnchor)    // ohne Fokus (Sheet war offen): gemerkter Block; fehlt er, ans Ende
        focus = nil
    }
    /// Auswahl oeffnen und dabei die Einfuegestelle merken (vor dem Fokusverlust durch das Sheet)
    func openPhotos() { imageAnchor = focusedBlock; pickImage = true }
    func openImmich() { imageAnchor = focusedBlock; if !pickImmich { pickImmich = true } }

    func insertImages(from providers: [NSItemProvider]) {
        for p in providers where p.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            // konkreten Bildtyp laden (public.png, public.jpeg, ...): "public.image" liefert nicht jede Quelle
            let t = p.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .image) == true } ?? UTType.image.identifier
            p.loadDataRepresentation(forTypeIdentifier: t) { data, _ in
                guard let data else { return }
                Task { @MainActor in await self.insertImage(data) }
            }
        }
    }
}

#if os(iOS)
/// Tastaturleiste des Seiten-Editors: wenige Knoepfe, der Rest in Menues. SwiftUI schiebt bei Platzmangel nur Elemente
/// HINTER dem Spacer ins Ueberlaufmenue, alles davor fiel weg - auf dem iPhone fehlten so Fett/Kursiv/... und die
/// Block-Befehle, "Fertig" lag versteckt (BUG-018). Bilder (Fotos, Immich, Zwischenablage) stehen im Menue "Neu".
/// Die Leiste liegt AUSSERHALB von .environmentObject(ed) der Seite, deshalb wird der Editor ausdruecklich uebergeben
/// (als @EnvironmentObject stuerzte der Tipp ab, 1.2.0). Fotos und Immich laufen ueber die Auswahl der Seite.
struct EditorKeyboardItems: View {
    @ObservedObject var ed: PageEditor
    var body: some View {
        BlockTypeMenu(title: "Neu", icon: "plus", images: ed) { t, l in ed.insert(PageEditor.make(t, level: l == 0 ? 2 : l), after: ed.focusedBlock) }
        BlockTypeMenu(title: "Typ", icon: "textformat") { t, l in if let b = ed.focusedBlock { ed.convert(b, to: t, level: l == 0 ? 2 : l) } }
        Menu {
            Button { ed.format("bold") } label: { Label("Fett", systemImage: "bold") }
            Button { ed.format("italic") } label: { Label("Kursiv", systemImage: "italic") }
            Button { ed.format("underline") } label: { Label("Unterstrichen", systemImage: "underline") }
            Button { ed.format("strike") } label: { Label("Durchgestrichen", systemImage: "strikethrough") }
            Button { ed.format("mark") } label: { Label("Markieren", systemImage: "highlighter") }
        } label: { Label("Format", systemImage: "bold.italic.underline") }
        Menu {
            Button { if let b = ed.focusedBlock { ed.move(b, by: -1) } } label: { Label("Block nach oben", systemImage: "arrow.up") }
            Button { if let b = ed.focusedBlock { ed.move(b, by: 1) } } label: { Label("Block nach unten", systemImage: "arrow.down") }
            Button(role: .destructive) { if let b = ed.focusedBlock { ed.delete(b) } } label: { Label("Block löschen", systemImage: "trash") }
        } label: { Label("Block", systemImage: "square.stack") }
    }
}
#endif


/// Mac: Block unter der Maus (innerster gewinnt). Eigenes Modell, damit Mausbewegungen NICHT die ganze Seite neu zeichnen.
@MainActor
final class HoverModel: ObservableObject {
    @Published var hovered: String?
    var overHandle = false
    private var depth: [String: Int] = [:]
    func hover(_ id: String, depth d: Int, inside: Bool) {
        if inside { depth[id] = d; pick() } else { depth[id] = nil; scheduleClear() }
    }
    private func pick() { if let best = depth.max(by: { $0.value < $1.value })?.key, best != hovered { hovered = best } }
    /// kurz warten (Weg zur Griffleiste), dann ausblenden - ausser die Maus ist auf den Griffen oder einem Block
    func scheduleClear() {
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(450))
            guard let self, !self.overHandle else { return }
            if self.depth.isEmpty { if self.hovered != nil { self.hovered = nil } } else { self.pick() }
        }
    }
}
