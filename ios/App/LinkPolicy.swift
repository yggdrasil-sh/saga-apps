import Foundation

/// Welche Links die App an das System weitergibt (pt2 A2) - reine Logik, Logic-Tests. Links aus Nachrichten,
/// Linkvorschauen und Server-Antworten sind fremde Daten: nur http(s) mit Host und mailto gehen an `.systemAction`.
/// Eigene Ziele (hkpage, hkchatnote, hkaisrc ...) laufen ueber die Navigation der App; alles andere (tel:, sms:,
/// facetime:, hlidskjalf://, saga://pair, file:, x-apple ...) wird verworfen.
enum LinkPolicy {
    static func external(_ url: URL) -> Bool {
        let s = url.absoluteString
        guard !s.isEmpty, s.count <= 8192, let scheme = url.scheme?.lowercased() else { return false }
        switch scheme {
        case "http", "https":
            guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false), let h = c.host, !h.isEmpty else { return false }
            return s.lowercased().hasPrefix(scheme + "://")
        case "mailto": return s.count > 7
        default: return false
        }
    }
    static func external(_ s: String) -> Bool { URL(string: s).map(external) ?? false }

    /// 2.5: Seitenlink auf eine Datei (/files/<24 hex>, Trilium-Import) -> Adresse am eigenen Server (Oeffnen wie ein
    /// Anhang: Vorschau/Download im Browser). Nur genau diese Form; alles andere nil.
    static func fileLink(_ href: String, server: URL) -> URL? {
        guard href.range(of: #"^/files/[0-9a-fA-F]{24}$"#, options: .regularExpression) != nil else { return nil }
        let base = server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let u = URL(string: base + href.lowercased()), external(u) else { return nil }
        return u
    }
    /// Rueckweg beim Speichern: Adresse am eigenen Server mit /files/<24 hex> -> wieder "/files/<id>" (Inhalt bleibt gleich)
    static func fileHref(_ url: URL, server: URL) -> String? {
        let base = server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let s = url.absoluteString
        guard s.hasPrefix(base + "/files/") else { return nil }
        let rest = String(s.dropFirst(base.count))
        return rest.range(of: #"^/files/[0-9a-f]{24}$"#, options: .regularExpression) != nil ? rest : nil
    }
}

/// ICE-Server aus der Serverantwort pruefen (pt2 A4), Regel wie Android `IceFilter`: nur stun/stuns/turn/turns in
/// strenger Form; nie Loopback, Link-Local, unspezifiziert, Multicast, "localhost" oder Zahlen-Kurzformen. Private
/// Adressen (RFC 1918, 100.64/10, fc00::/7, *.local/.lan/.internal/.home.arpa) nur, wenn der Saga-Server selbst im
/// lokalen Netz liegt (Heimnetz-Betrieb mit TURN im LAN). Namen werden nicht aufgeloest; sie gelten als oeffentlich.
enum IceFilter {
    enum Kind { case publicNet, privateNet, blocked }

    /// Host einer ICE-Adresse oder nil (Form ungueltig)
    static func host(_ url: String) -> String? {
        let u = url.trimmingCharacters(in: .whitespaces)
        guard let r = u.range(of: #"^(?i)(stuns?|turns?):(\[[0-9A-Fa-f:.%]{2,60}\]|[A-Za-z0-9.-]{1,253})(:[0-9]{1,5})?(\?transport=(udp|tcp))?$"#,
                              options: .regularExpression), r == u.startIndex..<u.endIndex else { return nil }
        var rest = Substring(u[u.index(after: u.firstIndex(of: ":")!)...])
        if let q = rest.firstIndex(of: "?") { rest = rest[..<q] }
        let h: Substring, port: Substring?
        if rest.hasPrefix("[") {
            guard let e = rest.firstIndex(of: "]") else { return nil }
            h = rest[rest.index(after: rest.startIndex)..<e]
            let after = rest[rest.index(after: e)...]
            port = after.isEmpty ? nil : after.dropFirst()
        } else if let c = rest.firstIndex(of: ":") {
            h = rest[..<c]; port = rest[rest.index(after: c)...]
        } else { h = rest; port = nil }
        if let p = port { guard let n = Int(p), (1...65535).contains(n) else { return nil } }
        return String(h)
    }

    static func classify(_ host: String) -> Kind {
        var h = host.lowercased()
        while h.hasSuffix(".") { h.removeLast() }
        if h.isEmpty { return .blocked }
        if h == "localhost" || h.hasSuffix(".localhost") || h == "localhost.localdomain" || h == "ip6-localhost" { return .blocked }
        if h.contains(":") { return v6(h) }
        if let k = v4(h) { return k }
        if h.allSatisfy({ $0.isASCII && ($0.isNumber || $0 == ".") }) { return .blocked }               // 127.1, 2130706433
        if h.split(separator: ".").contains(where: { $0.hasPrefix("0x") && $0.dropFirst(2).allSatisfy(\.isHexDigit) }) { return .blocked }
        if [".local", ".internal", ".lan", ".home.arpa"].contains(where: { h.hasSuffix($0) }) { return .privateNet }
        return .publicNet
    }

    private static func v4(_ h: String) -> Kind? {
        let p = h.split(separator: ".", omittingEmptySubsequences: false)
        guard p.count == 4 else { return nil }
        var n: [Int] = []
        for s in p {
            guard !s.isEmpty, s.count <= 3, s.allSatisfy({ $0.isASCII && $0.isNumber }), !(s.count > 1 && s.first == "0"), let v = Int(s), v <= 255 else { return nil }
            n.append(v)
        }
        return v4kind(n[0], n[1])
    }

    private static func v4kind(_ a: Int, _ b: Int) -> Kind {
        if a == 0 || a == 127 || (a == 169 && b == 254) || a >= 224 { return .blocked }
        if a == 10 || (a == 172 && (16...31).contains(b)) || (a == 192 && b == 168) || (a == 100 && (64...127).contains(b)) { return .privateNet }
        return .publicNet
    }

    private static func v6(_ h: String) -> Kind {
        let a = String(h.split(separator: "%", maxSplits: 1, omittingEmptySubsequences: false)[0])
        guard a.allSatisfy({ $0.isASCII && ($0.isHexDigit || $0 == ":" || $0 == ".") }), let g = expand(a) else { return .blocked }
        if g.allSatisfy({ $0 == 0 }) { return .blocked }
        if g.prefix(7).allSatisfy({ $0 == 0 }) && g[7] == 1 { return .blocked }
        if g.prefix(5).allSatisfy({ $0 == 0 }) && (g[5] == 0xffff || g[5] == 0) { return v4kind(g[6] >> 8, g[6] & 0xff) }
        let f = g[0]
        if f & 0xffc0 == 0xfe80 || f & 0xff00 == 0xff00 { return .blocked }
        if f & 0xfe00 == 0xfc00 || f & 0xffc0 == 0xfec0 { return .privateNet }
        return .publicNet
    }

    private static func expand(_ a: String) -> [Int]? {
        var s = a
        var tail: [Int]?
        guard let lc = s.lastIndex(of: ":") else { return nil }
        let last = s[s.index(after: lc)...]
        if last.contains(".") {
            let q = last.split(separator: ".", omittingEmptySubsequences: false)
            guard q.count == 4 else { return nil }
            var n: [Int] = []
            for x in q { guard (1...3).contains(x.count), let v = Int(x), v <= 255 else { return nil }; n.append(v) }
            tail = [(n[0] << 8) | n[1], (n[2] << 8) | n[3]]
            s = String(s[...lc]) + "0:0"
        }
        let parts = s.components(separatedBy: "::")
        guard parts.count <= 2 else { return nil }
        func groups(_ x: String) -> [Int]? {
            if x.isEmpty { return [] }
            var out: [Int] = []
            for g in x.split(separator: ":", omittingEmptySubsequences: false) {
                guard (1...4).contains(g.count), let v = Int(g, radix: 16) else { return nil }
                out.append(v)
            }
            return out
        }
        guard let head = groups(parts[0]) else { return nil }
        var all = head
        if parts.count == 2 {
            guard let rest = groups(parts[1]) else { return nil }
            let fill = 8 - head.count - rest.count
            guard fill >= 1 else { return nil }
            all = head + Array(repeating: 0, count: fill) + rest
        }
        guard all.count == 8 else { return nil }
        if let tail { return Array(all.prefix(6)) + tail }
        return all
    }

    static func allowed(_ url: String, serverLocal: Bool) -> Bool {
        guard let h = host(url) else { return false }
        switch classify(h) {
        case .publicNet: return true
        case .privateNet: return serverLocal
        case .blocked: return false
        }
    }

    static func filter(_ urls: [String], serverLocal: Bool) -> [String] { Array(urls.filter { allowed($0, serverLocal: serverLocal) }.prefix(8)) }

    /// Liegt der Saga-Server im lokalen Netz? Privat/.local direkt, sonst Name aufloesen (Split-DNS im Heimnetz).
    /// Blockiert (DNS) - nur im Hintergrund. Unbekannt = false (dann gelten nur oeffentliche ICE-Adressen).
    static func serverLocal(host raw: String, resolve: (String) -> [String] = IceFilter.resolve) -> Bool {
        let h = raw.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if h.isEmpty { return false }
        if classify(h) == .privateNet || PrivateNet.isPrivate(host: h) { return true }
        return resolve(h).contains { classify($0) == .privateNet }
    }

    static func resolve(_ host: String) -> [String] {
        var hints = addrinfo(); hints.ai_socktype = SOCK_STREAM
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &res) == 0, let first = res else { return [] }
        defer { freeaddrinfo(first) }
        var out: [String] = []
        var p: UnsafeMutablePointer<addrinfo>? = first
        while let a = p, out.count < 16 {
            var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(a.pointee.ai_addr, a.pointee.ai_addrlen, &buf, socklen_t(buf.count), nil, 0, NI_NUMERICHOST) == 0 { out.append(String(cString: buf)) }
            p = a.pointee.ai_next
        }
        return out
    }

    /// Host fuer eine turns:-Adresse (IPv6 in Klammern)
    static func turnsURL(host: String, port: Int) -> String { "turns:\(host.contains(":") ? "[\(host)]" : host):\(port)?transport=tcp" }
}
