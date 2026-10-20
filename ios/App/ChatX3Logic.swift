import Foundation

// Chat-Grundlagen (Saga 2.5, Konzept Chat B1-B3; Server saga/chat_x3.py, docs/APP-API.md "Chat-Stufe 3"): reine Logik ohne
// Krypto und UI (mit swift/XCTest pruefbar) - Faehigkeiten und Grenzen des Servers, Reaktionen (Form, je Person die neueste,
// Chips), Bearbeiten (wer, wann, wie oft; Pruefung gegen das Original), Sprachnachrichten (Wellenform, Zeitgrenzen, Gesten).

enum ChatX3 {
    // MARK: Faehigkeiten und Grenzen (GET /api/chat/me: features, x3_limits)

    struct Limits: Equatable {
        var editWindow: Double = 86_400
        var editMax = 20
        var emojiMax = 32
        var reactRate = 120
        var voiceMaxS: Double = 900
        var voiceKbps = 24
        var voiceMaxBytes = 4 * 1_048_576
    }
    struct Caps: Equatable {
        var react = false, edit = false, voice = false, chat3 = false
        var limits = Limits()
    }
    static func caps(_ me: [String: Any]?) -> Caps {
        guard let me else { return Caps() }
        let f = Set((me["features"] as? [Any] ?? []).compactMap { $0 as? String })
        var c = Caps(react: f.contains("react"), edit: f.contains("edit"), voice: f.contains("voice"), chat3: f.contains("chat3"))
        if let l = me["x3_limits"] as? [String: Any] {
            c.limits.editWindow = ShareJSON.num(l["edit_window"]) ?? c.limits.editWindow
            c.limits.editMax = ShareJSON.int(l["edit_max"]) ?? c.limits.editMax
            c.limits.emojiMax = ShareJSON.int(l["emoji_max"]) ?? c.limits.emojiMax
            c.limits.reactRate = ShareJSON.int(l["react_rate"]) ?? c.limits.reactRate
            c.limits.voiceMaxS = ShareJSON.num(l["voice_max_s"]) ?? c.limits.voiceMaxS
            c.limits.voiceKbps = ShareJSON.int(l["voice_kbps"]) ?? c.limits.voiceKbps
            c.limits.voiceMaxBytes = ShareJSON.int(l["voice_max_bytes"]) ?? c.limits.voiceMaxBytes
        }
        return c
    }

    // MARK: Reaktionen

    static let quick = ["👍", "❤️", "😂", "😮", "😢", "🙏"]
    static let reactCTMax = 400
    /// Entprellen: nur die letzte Wahl innerhalb von 500 ms geht raus
    static let reactDebounce = 0.5

    /// {by (Handle), dev, ts (ms), ct, sig} wie in GET .../messages (rx), chat.react und PUT .../reaction
    struct Reaction: Equatable {
        var by: String; var dev: String; var ts: Int64; var ct: String; var sig: String
        init(by: String, dev: String, ts: Int64, ct: String, sig: String) { self.by = by; self.dev = dev; self.ts = ts; self.ct = ct; self.sig = sig }
        init?(_ v: Any?) {
            guard let d = v as? [String: Any], let by = d["by"] as? String, let dev = d["dev"] as? String, let ts = (d["ts"] as? NSNumber)?.int64Value,
                  let ct = d["ct"] as? String, let sig = d["sig"] as? String else { return nil }
            self.init(by: by, dev: dev, ts: ts, ct: ct, sig: sig)
        }
        var body: [String: Any] { ["dev": dev, "ts": ts, "ct": ct, "sig": sig] }
    }
    /// Je Person nur die neueste (ts) - der Server haelt ohnehin nur eine
    static func newest(_ items: [Reaction]) -> [Reaction] {
        var by: [String: Reaction] = [:]
        for r in items where (by[r.by]?.ts ?? Int64.min) <= r.ts { by[r.by] = r }
        return by.values.sorted { $0.ts != $1.ts ? $0.ts < $1.ts : $0.by < $1.by }
    }
    /// Live/Nachladen in den Bestand einmischen (neuere ts ersetzt)
    static func merge(_ old: [Reaction], _ new: [Reaction]) -> [Reaction] { newest(old + new) }

    /// Inhalt kompakt wie JSON.stringify({e, ts})
    static func reactBodyJSON(emoji: String, ts: Int64) -> String { "{\"e\":" + jsonString(emoji) + ",\"ts\":\(ts)}" }
    static func jsonString(_ s: String) -> String {
        var o = "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": o += "\\\""
            case "\\": o += "\\\\"
            case "\n": o += "\\n"
            case "\r": o += "\\r"
            case "\t": o += "\\t"
            case "\u{08}": o += "\\b"
            case "\u{0C}": o += "\\f"
            default:
                if u.value < 0x20 { o += String(format: "\\u%04x", u.value) } else { o.unicodeScalars.append(u) }
            }
        }
        return o + "\""
    }
    /// Entschluesselter Inhalt -> Emoji ("" = zurueckgenommen) oder nil; wie react_body_ok in saga/chat_v3.py
    static func reactBody(_ pt: Data, ts: Int64) -> String? {
        guard let d = (try? JSONSerialization.jsonObject(with: pt)) as? [String: Any], Set(d.keys) == ["e", "ts"],
              let t = d["ts"] as? NSNumber, !CFNumberIsFloatType(t), t.int64Value == ts, let e = d["e"] as? String else { return nil }
        guard e.utf8.count <= 32, !e.unicodeScalars.contains(where: { $0.value < 0x20 || (0x7F..<0xA0).contains($0.value) }) else { return nil }
        return e
    }
    /// Auswahl gueltig? genau ein Graphem, hoechstens 32 Bytes, keine Steuerzeichen
    static func emojiOK(_ e: String) -> Bool {
        e.count == 1 && e.utf8.count <= 32 && !e.unicodeScalars.contains { $0.value < 0x20 || (0x7F..<0xA0).contains($0.value) }
    }
    /// Erstes Graphem einer Eingabe (System-Emoji-Auswahl) -> Emoji oder nil (Buchstaben, Ziffern, Leerzeichen zaehlen nicht)
    static func pickedEmoji(_ s: String) -> String? {
        for ch in s.reversed() where !ch.isWhitespace {
            let e = String(ch)
            if emojiOK(e) && ch.unicodeScalars.contains(where: { $0.properties.isEmojiPresentation || $0.properties.isEmoji && $0.value > 0xFF }) { return e }
        }
        return nil
    }

    /// Geoeffnete Reaktion einer Person
    struct Opened: Equatable { var by: String; var e: String; var ts: Int64 }
    /// Chip unter der Blase: Emoji, Zahl, eigene dabei, wer (Handles, in Reihenfolge der Reaktion)
    struct Chip: Equatable, Identifiable { var e: String; var n: Int; var mine: Bool; var who: [String]; var id: String { e } }
    /// Chips in der Reihenfolge der ersten Reaktion je Emoji (wie im Web); zurueckgenommene ("") zaehlen nicht
    static func chips(_ list: [Opened], me: String) -> [Chip] {
        var order: [String] = [], who: [String: [String]] = [:]
        for x in list.sorted(by: { $0.ts != $1.ts ? $0.ts < $1.ts : $0.by < $1.by }) where !x.e.isEmpty {
            if who[x.e] == nil { order.append(x.e) }
            who[x.e, default: []].append(x.by)
        }
        return order.map { e in Chip(e: e, n: who[e]!.count, mine: who[e]!.contains(me), who: who[e]!) }
    }
    /// Meine aktuelle Reaktion
    static func mine(_ list: [Opened], me: String) -> String? { list.first { $0.by == me && !$0.e.isEmpty }?.e }
    /// Tippen auf ein Emoji (Leiste oder Chip): gleiches nochmal = zuruecknehmen
    static func toggle(current: String?, picked: String) -> String { current == picked ? "" : picked }
    /// Eigene Wahl sofort zeigen (vor der Antwort des Servers)
    static func applyLocal(_ list: [Opened], me: String, e: String, ts: Int64) -> [Opened] {
        var out = list.filter { $0.by != me }
        if !e.isEmpty { out.append(Opened(by: me, e: e, ts: ts)) }
        return out
    }

    // MARK: Bearbeiten

    /// Darf ich diese Nachricht bearbeiten? Nur eigene, mit Text (bzw. Bildunterschrift), keine KI-Antwort, keine
    /// Sprachnachricht, zugestellt, im Fenster (Serverzeit created) und hoechstens editMax-mal
    static func canEdit(mine: Bool, hasText: Bool, ai: Bool, voice: Bool, pending: Bool, created: Double, editN: Int, now: Double, limits: Limits) -> Bool {
        mine && hasText && !ai && !voice && !pending && now - created < limits.editWindow && editN < limits.editMax
    }
    enum EditReject: String, Equatable { case editOfMismatch = "edit_of_mismatch", otherSender = "other_sender", aiOriginal = "ai_original", window }
    /// Bearbeitung gegen das Original pruefen (wie edit_check in saga/chat_v3.py): body = entschluesselter Inhalt {edit, t}
    static func editCheck(origID: String, origSender: String?, origAI: Bool, origCreated: Double,
                          editOf: String?, editSender: String?, editCreated: Double, body: [String: Any]?, window: Double = 86_400) -> EditReject? {
        guard let b = body, b["t"] is String, let be = b["edit"] as? String, be == editOf else { return .editOfMismatch }
        guard editOf == origID else { return .editOfMismatch }
        guard let es = editSender, !es.isEmpty, es == origSender else { return .otherSender }
        if origAI { return .aiOriginal }
        if editCreated - origCreated > window { return .window }
        return nil
    }
    /// Inhalt einer Bearbeitung (kompakt): {"edit": <msg_id>, "t": <text>}
    static func editBody(msgID: String, text: String) -> Data { Data(("{\"edit\":" + jsonString(msgID) + ",\"t\":" + jsonString(text) + "}").utf8) }
}

// MARK: - Sprachnachrichten (B3)

enum VoiceLogic {
    static let waveN = 64
    static let minS = 1.0
    /// Hinweis eine Minute vor dem Ende
    static func warnAt(maxS: Double) -> Double { max(0, maxS - 60) }
    static let mime = "audio/ogg; codecs=opus"
    static let fileName = "voice.ogg"
    static let speeds: [Double] = [1, 1.5, 2]
    static func nextSpeed(_ s: Double) -> Double { speeds[((speeds.firstIndex(of: s) ?? 0) + 1) % speeds.count] }
    static func speedLabel(_ s: Double) -> String { s == 1.5 ? "1,5×" : String(format: "%.0f×", s) }

    /// Wellenform wie static/chat-voice.js: Spitzen je Abschnitt aus den Pegeln (RMS je 100 ms), 0-255, Wurzel-normalisiert
    static func waveform(_ levels: [Float]) -> [UInt8] {
        let n = levels.count
        guard n > 0 else { return [UInt8](repeating: 0, count: waveN) }
        var v = [Double](repeating: 0, count: waveN), mx: Double = 0      // in Double wie im Browser (gleiche Rundung)
        for i in 0..<waveN {
            let a = i * n / waveN, z = max(a + 1, (i + 1) * n / waveN)
            var m: Double = 0
            for k in a..<min(z, n) { m = max(m, Double(levels[k])) }
            v[i] = m; mx = max(mx, m)
        }
        return v.map { mx > 0 ? UInt8(min(255, (255 * ($0 / mx).squareRoot()).rounded())) : 0 }
    }
    /// wf aus der Nachricht (B64, 64 Bytes) -> Balken; falsche Laenge: flach
    static func bars(_ b64: String?) -> [UInt8] {
        guard let s = b64, let d = Data(base64Encoded: s), !d.isEmpty else { return [UInt8](repeating: 0, count: waveN) }
        let a = [UInt8](d.prefix(waveN))
        return a + [UInt8](repeating: 0, count: waveN - a.count)
    }
    /// Wie viele Balken sind "gehoert" (eingefaerbt)
    static func played(position: Double, duration: Double) -> Int {
        guard duration > 0 else { return 0 }
        return Int((min(1, max(0, position / duration)) * Double(waveN)).rounded())
    }
    /// Tippen auf die Wellenform (0...1 der Breite) -> Sekunde
    static func seek(fraction: Double, duration: Double) -> Double { max(0, min(duration - 0.05, fraction * duration)) }
    /// m:ss
    static func fmt(_ s: Double) -> String {
        let t = Int(max(0, s.isFinite ? s : 0).rounded())
        return String(format: "%d:%02d", t / 60, t % 60)
    }

    /// Ablauf der Aufnahme je Takt: weiter, Hinweis (einmal), Stopp an der Grenze
    enum Tick: Equatable { case run, warn, stop }
    static func tick(secs: Double, maxS: Double, warned: Bool) -> Tick {
        if secs >= maxS { return .stop }
        if !warned && secs >= warnAt(maxS: maxS) { return .warn }
        return .run
    }
    /// Ergebnis nach dem Stopp: zu kurz -> verwerfen
    static func keep(duration: Double) -> Bool { duration >= minS }

    /// Halten zum Aufnehmen (iPhone/iPad): nach oben schieben sperrt, nach links verwirft
    enum Gesture: Equatable { case hold, lock, cancel }
    static let lockDistance = 70.0
    static let cancelDistance = 110.0
    static func gesture(dx: Double, dy: Double) -> Gesture {
        if dx <= -cancelDistance && abs(dx) > abs(dy) { return .cancel }
        if dy <= -lockDistance && abs(dy) >= abs(dx) { return .lock }
        return .hold
    }
    /// Anhang im verschluesselten Inhalt (saga-file/v2 bzw. v1) mit voice/dur/wf
    static func fileDict(id: String, key: String, size: Int, v2: Bool, cs: Int, dur: Double, wf: [UInt8]) -> [String: Any] {
        var d: [String: Any] = ["id": id, "k": key, "n": fileName, "m": mime, "s": size, "voice": true,
                                "dur": (dur * 10).rounded() / 10, "wf": Data(wf).base64EncodedString()]
        if v2 { d["x"] = 2; d["cs"] = cs }
        return d
    }
}
