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
        didSet {
            if focus != nil && selected != nil { selected = nil }      // Schreiben hebt die Bild-Auswahl auf
            if focus == nil { carry = [:] }
        }
    }
    /// Eingaben waehrend des Fokuswechsels (Return): Ziel-Schluessel -> Text, den das neue Feld uebernimmt (EditorFlow)
    var carry: [String: String] = [:]
    /// Felder, deren Cursor beim Erscheinen am Anfang steht (neuer Block nach Return: der Rest steht hinter dem Cursor)
    var caretStart = Set<String>()
    /// Aktionen, die warten, bis das neue Feld den Fokus hat (z. B. leeren Listenpunkt erst dann entfernen - sonst
    /// verschwindet das Feld mit der Tastatur, bevor das neue sie hat, F4/N2)
    private var afterFocus: [(key: String, run: () -> Void)] = []

    /// Eingaben an das Ziel-Feld weiterreichen (Reihenfolge bleibt: alles landet hinten an carry[ziel])
    func forward(_ text: String, to target: String) {
        guard !text.isEmpty else { return }
        carry[target, default: ""] += text
        NotificationCenter.default.post(name: .hkCarry, object: target)
    }
    /// `run` ausfuehren, sobald `key` den Fokus hat - spaetestens nach 0,7 s
    func whenFocused(_ key: String, _ run: @escaping () -> Void) {
        afterFocus.append((key, run))
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(700))
            self?.focusAcquired(key)
        }
    }
    func focusAcquired(_ key: String) {
        guard afterFocus.contains(where: { $0.key == key }) else { return }
        let due = afterFocus.filter { $0.key == key }
        afterFocus.removeAll { $0.key == key }
        due.forEach { $0.run() }
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
    @Published var ncFor: String?                    // "/Nextcloud-Datei" (2.3) -> Auswahl aus der eigenen Nextcloud
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
    /// iPhone/iPad: "/"-Menue des fokussierten Absatzes, ueber der Tastatur angedockt (inline lag es unter der
    /// Tastaturleiste und war ab der Bildschirmmitte nicht waehlbar)
    @Published var slashDock: SlashDock?
    /// Umwandeln mit Verlust (Unterpunkte, Inhalt eines Aufklapp-Blocks): erst nach Rueckfrage
    struct ConvertAsk: Identifiable, Equatable { let id: String; let type: String; let level: Int }
    @Published var askConvert: ConvertAsk?
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
        for ci in 0..<min(ColumnsLogic.maxColumns, n) {        // 2.5 cols6: bis 6 Spalten (frueher 4 - Inhalt ging verloren)
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

    /// "/"-Menue -> Liste aus einem (leeren) Absatz: neue Liste NACH dem Absatz einfuegen und fokussieren, den Absatz
    /// erst entfernen, wenn der Listenpunkt den Fokus hat. Ersetzt man den Absatz sofort, verschwindet sein Feld mit der
    /// Tastatur, bevor das neue sie hat - schnell weiter Getipptes ("/Checkliste⏎eins") ging verloren (Tipptest int2).
    /// Bis dahin reicht das alte Feld Tasten weiter (Handover) wie nach Return.
    func convertToListKeepingFocus(_ id: String, to type: String) {
        guard let (arr, i) = locate(id), let b = arr[i] as? NSMutableDictionary, b["type"] as? String == "paragraph" else {
            convert(id, to: type); return
        }
        let nb = Self.make(type, text: ((b["data"] as? NSDictionary)?["text"] as? String) ?? "")
        let item = nb.blockID + "/0"
        arr.insert(nb, at: i + 1)
        focus = item
        whenFocused(item) { [weak self] in
            guard let self, let (a, j) = self.locate(id) else { return }
            a.removeObject(at: j); self.changed()
        }
        changed()
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
            GestaltenLogic.carryTunes(from: b, to: nb)
            arr.replaceObject(at: i, with: nb); arr.removeObject(at: i); arr.insert(nb, at: i)
            nb["id"] = id; focus = id + "/0"
        } else {
            nb = Self.make(type, text: texts[0], level: level); nb["id"] = id
            GestaltenLogic.carryTunes(from: b, to: nb)                 // Farbe (tunes.hkstyle) bleibt beim Umwandeln
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
    (String(localized: "Nextcloud-Datei"), "nextcloud", 0, "cloud", "nextcloud cloud datei ordner file folder archivo carpeta"),
]

/// "/"-Eingabe eines Absatzes (Schluessel des Textfelds + Suchtext)
struct SlashDock: Equatable { var key: String; var query: String }

/// Zustand eines Textfelds, der sofort (nicht erst beim naechsten Zeichnen) gelten muss: Klasse statt @State-Werten
@MainActor
final class EditFieldBox {
    /// zuletzt verbuchter Text (Zeichen): Aenderungen werden dagegen gerechnet (EditorFlow)
    var shadow = ""
    /// Fokus abgegeben, Tasten kommen aber noch hier an
    var handover: EditorFlow.Handover?
    /// bis dahin gilt ein Fokusverlust als "noch im Aufbau" (erneut holen statt Bearbeiten zu beenden)
    var acquireUntil = Date.distantPast
}

#if os(macOS)
/// Oberlaenge der Schrift (Abstand Oberkante -> Grundlinie der ersten Zeile) fuer die Ausrichtung neben dem Mac-Textfeld
enum MacTextMetrics {
    static func ascent(_ font: Font) -> CGFloat {
        let style: NSFont.TextStyle
        switch font {
        case .title: style = .title1
        case .title2: style = .title2
        case .title3: style = .title3
        case .headline: style = .headline
        case .subheadline: style = .subheadline
        case .callout: style = .callout
        case .footnote: style = .footnote
        case .caption: style = .caption1
        default: style = .body
        }
        return NSFont.preferredFont(forTextStyle: style).ascender.rounded()
    }
}
#endif

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
    @State private var box = EditFieldBox()
    @EnvironmentObject private var store: Store
    @Environment(\.blockTint) private var tint             // Textfarbe des Blocks (2.5, tunes.hkstyle)

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

    private var slashQuery: String? { slash ? SlashFilter.query(String(rich.characters)) : nil }
    private var nextcloudOK: Bool { store.serverVersion.map { !ShareLogic.versionLess($0, "2.3.0") } ?? false }
    /// Trennt Return dieses Feld (neuer Block darunter)?
    private var canSplit: Bool { splitOnReturn && onReturn != nil }
    /// Fokus schon abgegeben, Tastatur aber noch hier: nur gespeicherten Text zeigen, Editor unsichtbar weiterfuehren
    private var handingOver: Bool { ed.focus != key && focused }

    /// Return/Weitergereichtes trennt: vorher bleibt hier, nachher kommt in den neuen Block (Cursor dort am Anfang)
    private func doReturn(_ before: AttributedString, _ after: AttributedString, rest: String) {
        let bh = richToHTML(before, ctx: fctx, baseBold: boldBase), ah = richToHTML(after, ctx: fctx, baseBold: boldBase)
        setHTML(bh); onReturn?(bh, ah)
        if let t = ed.focus, t != key {
            ed.caretStart.insert(t)
            ed.forward(rest, to: t)
        } else {                                                       // Fokus blieb hier: Feld zeigt den Stand vorher
            rich = before; box.shadow = String(before.characters); box.handover = nil
        }
    }

    /// Jede Aenderung des Editors: verbuchen (Ziel), trennen (Return) oder weiterreichen (Feld gibt gerade ab)
    private func handle(_ new: AttributedString) {
        let n = String(new.characters)
        if ed.focus != key, ed.focus != nil, focused || box.handover != nil {
            var h = box.handover ?? EditorFlow.Handover(base: box.shadow)
            let out = EditorFlow.handover(&h, new: n)
            box.handover = h
            if let t = ed.focus { ed.forward(out, to: t) }
            return
        }
        switch EditorFlow.targetStep(shadow: box.shadow, new: n, canSplit: canSplit, slashPick: slashPickable) {
        case .none:
            // nur Formatierung geaendert (gleiche Zeichen): trotzdem speichern
            let h = richToHTML(new, ctx: fctx, baseBold: boldBase)
            if h != html && h != PageLinkTitles.apply(html) { setHTML(h) }
        case .text:
            box.shadow = n
            let h = richToHTML(new, ctx: fctx, baseBold: boldBase)
            // nur echte Aenderungen speichern (aktualisierte Seitentitel in Verweisen allein sind keine)
            if h != html && h != PageLinkTitles.apply(html) { setHTML(h) }
        case .split(let at, let from, let rest):
            box.shadow = n
            box.handover = EditorFlow.Handover(base: n)                // weitere Tasten hier gehen ans neue Feld
            let a = new.characters.index(new.startIndex, offsetBy: at), f = new.characters.index(new.startIndex, offsetBy: from)
            doReturn(AttributedString(new[new.startIndex..<a]), AttributedString(new[f..<new.endIndex]), rest: rest)
        case .slashPick(let line, let rest):                           // Return im "/"-Menue: erster Treffer (F3)
            box.shadow = n; box.handover = EditorFlow.Handover(base: n)
            slashPick(line, rest: rest)
        }
    }

    /// Zeile ist ein "/"-Befehl mit Treffer (nur Felder mit "/"-Menue)
    private func slashPickable(_ line: String) -> Bool {
        slash && SlashFilter.query(line).map { !slashMatches($0, nextcloud: nextcloudOK).isEmpty } == true
    }

    /// Ersten Treffer zu `line` anwenden; danach Getipptes an das umgewandelte Feld
    private func slashPick(_ line: String, rest: String) {
        guard let q = SlashFilter.query(line), let first = slashMatches(q, nextcloud: nextcloudOK).first else { return }
        ed.slashApply(ed.slashTarget(key), type: first.1, level: first.2)
        if let t = ed.focus, t != key || first.1 != "paragraph" { ed.forward(rest, to: t) }
    }

    /// Weitergereichte Eingaben uebernehmen (beim Erscheinen, beim Fokus und sobald neue bereitliegen)
    private func takeCarry() {
        guard !raw, ed.focus == key, let c = ed.carry.removeValue(forKey: key), !c.isEmpty else { return }
        let count = rich.characters.count
        let caret = min(cursor ?? count, count)
        let a = EditorFlow.apply(carry: c, caret: caret, canSplit: canSplit)
        // Weitergereichtes "/Seite⏎": wie Return im Menue waehlen statt "/Seite" als Absatz abzutrennen
        if let line = EditorFlow.carrySlashLine(text: String(rich.characters), caret: caret, a, slashPick: slashPickable) {
            var r = AttributedString(line); r.font = font
            rich = r; box.shadow = line; box.handover = EditorFlow.Handover(base: line)
            slashPick(line, rest: a.rest)
            return
        }
        let start = caret - min(a.removedBefore, caret)
        var ins = AttributedString(a.inserted); ins.font = font
        rich.replaceSubrange(rich.characters.index(rich.startIndex, offsetBy: start)..<rich.characters.index(rich.startIndex, offsetBy: caret), with: ins)
        let end = rich.characters.index(rich.startIndex, offsetBy: start + a.inserted.count)
        box.shadow = String(rich.characters)
        if a.split {
            let before = AttributedString(rich[rich.startIndex..<end]), after = AttributedString(rich[end..<rich.endIndex])
            rich = before; box.shadow = String(before.characters)
            doReturn(before, after, rest: a.rest)
        } else {
            rsel = AttributedTextSelection(insertionPoint: end)
            let h = richToHTML(rich, ctx: fctx, baseBold: boldBase)
            if h != html { setHTML(h) }
        }
    }

    /// Fokus holen - mehrfach: ein eben eingefuegtes Feld (neuer Absatz nach der Liste, "Typ → Text") ist beim ersten
    /// Versuch oft noch nicht im Fenster; der Fehlschlag beendete bisher das Bearbeiten (Tastatur zu, N2)
    private func acquireFocus() {
        box.acquireUntil = Date().addingTimeInterval(0.6)
        for d in [0.0, 0.08, 0.25, 0.5] {
            DispatchQueue.main.asyncAfter(deadline: .now() + d) { if ed.focus == key && !focused { focused = true } }
        }
    }

    @ViewBuilder private var staticText: some View {
        Group {
            if html.isEmpty { Text(placeholder).foregroundStyle(.tertiary) }
            else if raw { Text(html) }
            else { Text(inlineText(html)).foregroundStyle(muted ? .secondary : (tint ?? .primary)) }
        }
        .font(font)
        .frame(maxWidth: .infinity, minHeight: 22, alignment: .leading)
    }

    var body: some View {
        // Nach Return bleibt das alte Feld im Fokus, bis das neue ihn hat: Tastatur und Eingaben gehen nicht verloren
        if ed.focus == key || focused {
            if raw {
                TextField(placeholder, text: $text, selection: $sel, axis: .vertical)
                    .font(font).focused($focused)
                    .onAppear { text = html; sel = TextSelection(insertionPoint: text.endIndex); DispatchQueue.main.async { focused = true } }
                    .onChange(of: text) { _, new in if new != html { setHTML(new) } }
                    .onChange(of: focused) { _, f in if !f && ed.focus == key { ed.focus = nil } }
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    ZStack(alignment: .topLeading) {
                        TextEditor(text: $rich, selection: $rsel)
                            .font(font)
                            .foregroundStyle(tint ?? .primary)
                            .scrollDisabled(true)
                            .scrollContentBackground(.hidden)
                            #if os(iOS)
                            .padding(.horizontal, -5).padding(.vertical, -8)          // Innenabstand des Editors ausgleichen
                            #else
                            // Mac: Zeilenrand des Textfelds (5 pt) ausgleichen - sonst steht der Cursor neben Platzhalter/Text
                            .padding(.horizontal, -5)
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
                            // Abgeben: Editor behaelt die Tastatur unsichtbar, sichtbar ist der gespeicherte Text
                            .opacity(handingOver ? 0 : 1)
                            .frame(height: handingOver ? 0 : nil, alignment: .top)
                            .allowsHitTesting(!handingOver)
                            .accessibilityHidden(handingOver)
                        if handingOver { staticText }
                    }
                    if !handingOver {
                        #if os(macOS)
                        if let q = slashQuery { SlashMenu(query: q, nextcloud: nextcloudOK) { type, level in ed.slashApply(ed.slashTarget(key), type: type, level: level) } }
                        else if !mentions.isEmpty { MentionMenu(items: mentions, selected: min(mSel, mentions.count - 1)) { pickMention($0) } }
                        #else
                        if slashQuery == nil && !mentions.isEmpty { MentionMenu(items: mentions, selected: min(mSel, mentions.count - 1)) { pickMention($0) } }
                        #endif
                    }
                }
                #if os(macOS)
                // Das Textfeld meldet keine Text-Grundlinie: ohne diese Angabe richten sich Kaestchen/Aufzaehlungszeichen
                // davor (HStack .firstTextBaseline) an der Unterkante aus und der Text steht versetzt daneben
                .alignmentGuide(.firstTextBaseline) { d in d[.top] + MacTextMetrics.ascent(font) }
                #endif
                #if os(iOS)
                .onChange(of: handingOver ? nil : slashQuery, initial: true) { _, q in       // Menue an der Seite andocken (ueber der Tastatur)
                    let v = q.map { SlashDock(key: key, query: $0) }
                    if ed.slashDock != v && (v != nil || ed.slashDock?.key == key) { ed.slashDock = v }
                }
                .onDisappear { if ed.slashDock?.key == key { ed.slashDock = nil } }
                #endif
                .onAppear {
                    rich = richText(html, base: font)
                    box.shadow = String(rich.characters); box.handover = nil
                    let atStart = ed.caretStart.remove(key) != nil
                    rsel = AttributedTextSelection(insertionPoint: atStart ? rich.startIndex : rich.endIndex)
                    takeCarry()
                    if ed.focus == key { acquireFocus() }
                }
                .onChange(of: rich) { _, new in handle(new) }
                .onReceive(NotificationCenter.default.publisher(for: .hkCarry)) { n in if n.object as? String == key { takeCarry() } }
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
                .onChange(of: focused) { _, f in
                    if f {
                        if ed.focus == key { takeCarry(); ed.focusAcquired(key) }
                    } else {
                        box.handover = nil
                        guard ed.focus == key else { return }
                        if Date() < box.acquireUntil {                     // Fokus beim Aufbau verloren: erneut holen
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { if ed.focus == key && !focused { focused = true } }
                        } else { ed.focus = nil }
                    }
                }
                .onChange(of: ed.focus) { _, f in
                    if f == key {                                          // wieder Ziel, waehrend noch abgegeben wurde
                        if box.handover != nil {
                            rich = richText(html, base: font); box.shadow = String(rich.characters); box.handover = nil
                            rsel = AttributedTextSelection(insertionPoint: rich.endIndex)
                        }
                        return
                    }
                    guard focused else { return }                          // Fokus ging an etwas ohne Textfeld: freigeben
                    if f == nil { focused = false; return }
                    Task { @MainActor in try? await Task.sleep(for: .milliseconds(900)); if ed.focus != key && focused { focused = false } }
                }
            }
        } else {
            staticText
                .contentShape(Rectangle())
                .onTapGesture { if !ed.locked { ed.focus = key } else if ed.tapToComment { ed.commentBlock = blockOf(key) } }
        }
    }
    private func blockOf(_ k: String) -> String { String(k.split(separator: "/").first ?? "") }
}

/// "/"-Menue: gefilterte Blocktypen unter dem Absatz
struct SlashMenu: View {
    let query: String
    /// "Nextcloud-Datei" nur bei Servern ab 2.3 (aeltere kennen den Block nicht)
    var nextcloud = true
    /// iPhone/iPad: ueber der Tastatur angedockt, volle Breite, niedriger
    var docked = false
    let pick: (String, Int) -> Void
    /// Zeilenhoehe waechst mit der Schrift; daraus die Hoehe des Menues (fixedSize an der ScrollView meldete bei einem
    /// Treffer eine falsche Hoehe - der Eintrag lag halb unter der Tastaturleiste, F3)
    @ScaledMetric(relativeTo: .body) private var rowH: CGFloat = 40
    var body: some View {
        let items = slashMatches(query, nextcloud: nextcloud)
        let maxH: CGFloat = docked ? 230 : 320
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if items.isEmpty { Text("Kein Blocktyp für „\(query)“").font(.subheadline).foregroundStyle(.secondary).padding(10).frame(minHeight: rowH) }
                ForEach(Array(items.enumerated()), id: \.element.0) { n, it in
                    Button { pick(it.1, it.2) } label: {
                        HStack {
                            Label(it.0, systemImage: it.3)
                            Spacer(minLength: 4)
                            if n == 0 { Image(systemName: "return").font(.caption).foregroundStyle(.tertiary).accessibilityHidden(true) }   // Return waehlt den ersten
                        }
                        .frame(maxWidth: .infinity, minHeight: rowH, alignment: .leading).padding(.horizontal, 12).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            }
        }
        .frame(height: min(maxH, rowH * CGFloat(max(items.count, 1)) + 4))
        .scrollBounceBehavior(.basedOnSize)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(.secondary.opacity(0.25)))
        .frame(maxWidth: docked ? 520 : 300)
    }
}

extension PageEditor {
    /// "/"-Auswahl: Absatz leeren und in den Typ verwandeln
    /// Ziel des "/"-Menues zu einem Feld: Absatz = sein Block. Letzter (oberster) Listenpunkt (Nr. 11): Punkt entfernen,
    /// Absatz hinter der Liste anlegen (bzw. statt der Liste, wenn sie dann leer ist) und dessen ID liefern.
    func slashTarget(_ key: String) -> String {
        let parts = key.split(separator: "/").map(String.init)
        let bid = parts.first ?? ""
        guard parts.count == 2, let i = Int(parts[1]), let (arr, bi) = locate(bid), let b = arr[bi] as? NSMutableDictionary,
              b["type"] as? String == "list", let items = (b["data"] as? NSMutableDictionary)?["items"] as? NSMutableArray,
              i == items.count - 1 else { return bid }
        items.removeObject(at: i)
        let p = Self.make("paragraph")
        if items.count == 0 { arr.replaceObject(at: bi, with: p) } else { arr.insert(p, at: bi + 1) }
        changed()
        return p["id"] as? String ?? bid
    }

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
        if type == "nextcloud" { focus = nil; ncFor = id; return }
        if ["tasks", "calendar"].contains(type) {
            arr.replaceObject(at: i, with: NSMutableDictionary(dictionary: ["id": id, "type": type, "data": NSMutableDictionary()])); focus = nil; changed(); return
        }
        if type == "db_link" { focus = nil; linkDBFor = id; return }
        if type == "db_new" || type == "page" {
            focus = nil
            Task { @MainActor in
                guard let store = self.store else { return }
                struct R: Decodable { let id: Int; let updated: Double? }
                let body: [String: Any] = type == "db_new" ? ["kind": "database", "title": String(localized: "Neue Datenbank"), "icon": "🗃️", "parent_id": self.doc.id] : ["title": "", "parent_id": self.doc.id]
                guard let d = try? await store.api("api/notes", method: "POST", body: body), let r = try? JSONDecoder().decode(R.self, from: d),
                      let (a, j) = self.locate(id) else { self.doc.error = String(localized: "Konnte nicht angelegt werden"); return }
                if type == "db_new" {
                    a.replaceObject(at: j, with: NSMutableDictionary(dictionary: ["id": id, "type": "database", "data": NSMutableDictionary(dictionary: ["db": r.id])]))
                } else {   // Unterseite: Verweis-Absatz wie im Web, dann oeffnen
                    (a[j] as? NSMutableDictionary)?["data"] = NSMutableDictionary(dictionary: ["text": "📄 <a href=\"/notes#\(r.id)\">\(String(localized: "Unbenannt"))</a>"])
                }
                self.changed(); await self.flush()
                // neue Unterseite/Datenbank lokal in die Seitenliste statt alles neu zu laden (fremder Baum: wie bisher)
                let up = r.updated ?? Date().timeIntervalSince1970
                await store.insertCreated(type == "db_new" ? .created(id: r.id, parent: self.doc.id, title: String(localized: "Neue Datenbank"), icon: "🗃️", kind: "database", updated: up)
                                                           : .created(id: r.id, parent: self.doc.id, updated: up))
                if type == "page" { self.openPage = r.id }
            }
            return
        }
        if type == "delimiter" { arr.replaceObject(at: i, with: Self.make("delimiter")); insert(Self.make("paragraph"), after: (arr[i] as? NSMutableDictionary)?.blockID); return }
        if ["checklist", "unordered", "ordered"].contains(type) { convertToListKeepingFocus(id, to: type); return }
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
extension Notification.Name {
    static let hkFormat = Notification.Name("hkFormat")
    /// Weitergereichte Eingaben fuer ein Feld liegen bereit (object: Schluessel des Felds)
    static let hkCarry = Notification.Name("hkCarry")
}

/// Treffer des "/"-Menues (Menue und Return = erster Treffer)
func slashMatches(_ query: String, nextcloud: Bool) -> [(String, String, Int, String, String)] {
    slashItems.filter { (nextcloud || $0.1 != "nextcloud") && SlashFilter.matches(name: $0.0, keywords: $0.4, query: query) }
}

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
        if let e = doc.uploadBlocked { doc.error = e; return }
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
