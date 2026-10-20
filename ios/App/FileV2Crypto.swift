import Foundation

// saga-file/v2 (2.4): Ver-/Entschluesseln je Block mit XChaCha20-Poly1305 (libsodium ueber ChatCrypto) und die
// Datenstroeme darauf - Datei -> Ciphertext-Teile (Upload) und Ciphertext -> Klartext-Datei (Speichern). Nie mehr als ein
// Block (256 KiB) bzw. ein Abschnitt im Speicher. Geprueft gegen tests/vectors/file_v2.json (ios/Tests/ChatVectors).

struct FileV2Key: Equatable, Codable {
    var key: Data; var prefix: Data; var size: Int64; var cs: Int = FileV2.cs
    var n: Int64 { FileV2.blocks(size, cs: cs) }
    var total: Int64 { FileV2.cipherSize(size, cs: cs) }
    var header: Data { FileV2.makeHeader(size: size, prefix: prefix, cs: cs) }
    /// neuer Schluessel K und zufaelliges Praefix je Datei
    static func fresh(size: Int64) -> FileV2Key { FileV2Key(key: ChatCrypto.aeadKey(), prefix: ChatCrypto.random(16), size: size) }
}

enum FileV2Crypto {
    static func seal(_ plain: Data, i: Int64, k: FileV2Key) -> Data? {
        ChatCrypto.aeadEncrypt(plain, ad: FileV2.ad(i: i, n: k.n, cs: k.cs, size: k.size), nonce: FileV2.nonce(prefix: k.prefix, i: i), key: k.key)
    }
    static func open(_ ct: Data, i: Int64, k: FileV2Key) -> Data? {
        guard i >= 0, i < k.n, ct.count == FileV2.cipherLen(i, size: k.size, cs: k.cs) else { return nil }
        return ChatCrypto.aeadDecrypt(ct, ad: FileV2.ad(i: i, n: k.n, cs: k.cs, size: k.size), nonce: FileV2.nonce(prefix: k.prefix, i: i), key: k.key)
    }
    /// Kopf vom Server + Schluessel aus der Nachricht -> Entschluessel-Angaben; Kopf muss zu s/cs der Nachricht passen
    static func key(header: Data, fileKey: Data, size: Int64?, cs: Int?) -> FileV2Key? {
        guard let h = FileV2.parseHeader(header), fileKey.count == 32 else { return nil }
        if let size, size != h.size { return nil }
        if let cs, cs != h.cs { return nil }
        return FileV2Key(key: fileKey, prefix: h.prefix, size: h.size, cs: h.cs)
    }

    /// Ganzer Blob im Speicher (nur Tests/kleine Dateien) - prueft Kopf, Groesse, jeden Block
    static func decryptAll(_ blob: Data, fileKey: Data) -> Data? {
        guard let k = key(header: blob.prefix(FileV2.headerLen), fileKey: fileKey, size: nil, cs: nil), Int64(blob.count) == k.total else { return nil }
        var out = Data(capacity: Int(k.size))
        do {
            try FileV2.openBlocks(blob.subdata(in: blob.startIndex + FileV2.headerLen..<blob.endIndex), first: 0, size: k.size, cs: k.cs,
                                  open: { open($0, i: $1, k: k) }, emit: { _, p in out.append(p) })
        } catch { return nil }
        return out
    }
    static func encryptAll(_ plain: Data, k: FileV2Key) -> Data? {
        var out = Data(capacity: Int(k.total))
        do {
            try FileV2.produce(from: 0, to: k.total - 1, size: k.size, prefix: k.prefix, cs: k.cs,
                               read: { o, l in plain.subdata(in: plain.startIndex + Int(o)..<plain.startIndex + Int(o) + l) },
                               seal: { seal($0, i: $1, k: k) }, sink: { out.append($0) })
        } catch { return nil }
        return out
    }

    /// Ciphertext from...to aus einer Klartext-Datei in eine Datei schreiben (ein Upload-Teil), blockweise
    static func writePart(source: URL, k: FileV2Key, from: Int64, to: Int64, into dst: URL) throws {
        let src = try FileHandle(forReadingFrom: source); defer { try? src.close() }
        FileManager.default.createFile(atPath: dst.path, contents: nil)
        let out = try FileHandle(forWritingTo: dst); defer { try? out.close() }
        try FileV2.produce(from: from, to: to, size: k.size, prefix: k.prefix, cs: k.cs,
                           read: { o, l in try src.seek(toOffset: UInt64(o)); return try src.read(upToCount: l) ?? Data() },
                           seal: { seal($0, i: $1, k: k) }, sink: { try out.write(contentsOf: $0) })
    }
}
