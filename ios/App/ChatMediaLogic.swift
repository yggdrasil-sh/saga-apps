import Foundation

// Chat-Bilder (2.1-c): reine Logik ohne UI/Krypto (mit swiftc pruefbar) - Vorschaubild-Groesse, Anzeigegroesse aus w/h,
// Kennungen der Sende-Anfrage (Original + Vorschaubild), LRU des Platten-Caches. Format: chat-thumbs-spec.md.

enum ChatMediaLogic {
    /// laengste Seite des Vorschaubilds
    static let thumbSide = 320
    /// JPEG-Qualitaet des Vorschaubilds
    static let thumbQuality = 0.7
    /// Platten-Cache (nur Ciphertext): Grenze und Ziel nach dem Aufraeumen
    static let diskLimit = 200 * 1_048_576
    static let diskTarget = 160 * 1_048_576
    /// Speicher: entschluesselte Vorschaubilder
    static let memCount = 300
    static let memBytes = 30 * 1_048_576
    /// gleichzeitige Downloads
    static let parallel = 4

    /// Vorschaubild fuer diesen Typ versuchen? (alles image/*; was ImageIO nicht kennt, faellt spaeter heraus)
    static func canThumb(_ mime: String) -> Bool { mime.lowercased().hasPrefix("image/") && mime.lowercased() != "image/svg+xml" }

    /// Pixelgroesse des Vorschaubilds: laengste Seite <= 320, nie hochskalieren, mindestens 1 px
    static func thumbSize(w: Int, h: Int, side: Int = thumbSide) -> (w: Int, h: Int) {
        guard w > 0, h > 0 else { return (0, 0) }
        let m = max(w, h)
        guard m > side else { return (w, h) }
        let f = Double(side) / Double(m)
        return (max(1, Int((Double(w) * f).rounded())), max(1, Int((Double(h) * f).rounded())))
    }

    /// Anzeigegroesse im Verlauf (pt): Seitenverhaeltnis aus w/h in einem Feld box x box, extreme Formate begrenzt
    /// (hoechstens 3:1), damit Panoramen/Streifen noch antippbar bleiben. Ohne w/h: 4:3-Platzhalter.
    static func displaySize(w: Int?, h: Int?, box: Double = 280) -> (w: Double, h: Double) {
        guard let w, let h, w > 0, h > 0 else { return (box, (box * 0.75).rounded()) }
        let r = min(3, max(1.0 / 3, Double(w) / Double(h)))
        return r >= 1 ? (box, (box / r).rounded()) : ((box * r).rounded(), box)
    }

    /// Kennungen fuer die Sende-Anfrage: je Anhang Original + Vorschaubild. Passt das nicht in die Grenze des Servers
    /// (aeltere Server: files_per_msg), fallen die Vorschaubilder der letzten Bilder weg -> (refs ohne diese th, ids).
    static func sendIDs(_ refs: [ChatFileRef], maxIDs: Int) -> (refs: [ChatFileRef], ids: [String]) {
        var out = refs
        var extra = out.filter { $0.thumb != nil }.count
        var i = out.count - 1
        while out.count + extra > maxIDs, i >= 0 {
            if out[i].thumb != nil { out[i].thumb = nil; extra -= 1 }
            i -= 1
        }
        return (out, out.flatMap { f in [f.id] + (f.thumb.map { [$0.id] } ?? []) })
    }

    /// JPEG ohne Metadaten: APP1 (EXIF/XMP), APP2 (ICC), APP13 (IPTC) und Kommentare entfernen - ImageIO schreibt
    /// immer einen kleinen EXIF-Block. Bilddaten bleiben unveraendert; kein gueltiges JPEG -> nil.
    static func stripJPEG(_ d: Data) -> Data? {
        let b = [UInt8](d)
        guard b.count > 4, b[0] == 0xFF, b[1] == 0xD8 else { return nil }
        var out: [UInt8] = [0xFF, 0xD8], i = 2
        while i + 4 <= b.count {
            guard b[i] == 0xFF else { return nil }
            let m = b[i + 1]
            if m == 0xFF { i += 1; continue }                                   // Fuellbyte
            if m == 0xDA { out += b[i...]; return Data(out) }                   // Start of Scan: Rest unveraendert
            if m == 0xD9 { out += [0xFF, 0xD9]; return Data(out) }
            if (0xD0...0xD7).contains(m) || m == 0x01 { out += b[i..<i + 2]; i += 2; continue }
            let len = Int(b[i + 2]) << 8 | Int(b[i + 3])
            guard len >= 2, i + 2 + len <= b.count else { return nil }
            if !(m == 0xE1 || m == 0xE2 || m == 0xED || m == 0xFE) { out += b[i..<i + 2 + len] }
            i += 2 + len
        }
        return nil
    }

    // MARK: Platten-Cache (LRU nach letzter Benutzung)

    struct DiskEntry: Equatable { var name: String; var size: Int; var used: Date }
    /// Was muss weg? Ueber `limit`: die am laengsten nicht benutzten, bis hoechstens `target` uebrig ist
    static func evict(_ entries: [DiskEntry], limit: Int = diskLimit, target: Int = diskTarget) -> [String] {
        var total = entries.reduce(0) { $0 + $1.size }
        guard total > limit else { return [] }
        var out: [String] = []
        for e in entries.sorted(by: { $0.used == $1.used ? $0.name < $1.name : $0.used < $1.used }) {
            if total <= target { break }
            out.append(e.name); total -= e.size
        }
        return out
    }
    /// Dateiname im Cache: nur Kennungen des Servers (Base64url) - nie Pfadteile. "<id>" = Blob, "<id>.t" = eigenes Vorschaubild
    static func cacheName(_ id: String, local: Bool = false) -> String? {
        guard !id.isEmpty, id.count <= 64, id.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return nil }
        return local ? id + ".t" : id
    }
}

/// Welche Datei-Kennungen (Original + Vorschaubild) zu welchem Chat gehoeren - damit "Chat loeschen" (2.1) den
/// Zwischenspeicher genau dieses Chats leeren kann (Platte und Speicher). Klein, als JSON neben dem Platten-Cache.
struct ChatCacheIndex: Codable, Equatable {
    var chats: [String: [String]] = [:]
    /// hoechstens so viele Kennungen je Chat (aelteste fallen heraus; ihr Ciphertext verdraengt ohnehin das LRU)
    static let perChat = 4000
    /// Kennungen merken -> geaendert?
    @discardableResult mutating func add(_ chatID: Int, _ ids: [String]) -> Bool {
        let k = String(chatID)
        var list = chats[k] ?? []
        var seen = Set(list), changed = false
        for id in ids where !id.isEmpty && !seen.contains(id) { list.append(id); seen.insert(id); changed = true }
        guard changed else { return false }
        if list.count > Self.perChat { list.removeFirst(list.count - Self.perChat) }
        chats[k] = list
        return true
    }
    /// Kennungen eines Chats herausnehmen (zum Loeschen)
    mutating func take(_ chatID: Int) -> [String] { chats.removeValue(forKey: String(chatID)) ?? [] }
    /// Dateinamen im Platten-Cache zu diesen Kennungen (Blob und eigenes Vorschaubild)
    static func diskNames(_ ids: [String]) -> [String] {
        ids.flatMap { id in [ChatMediaLogic.cacheName(id), ChatMediaLogic.cacheName(id, local: true)].compactMap { $0 } }
    }
}
