import Foundation

/// Private Netze (nur Entwickler-Builds: http zum Testserver im LAN). Release-Builds erlauben weiter nur https.
/// Nur IP-Adressen: fuer sie gilt App Transport Security nicht; Namen (.local, intern) braeuchten eine ATS-Ausnahme.
enum PrivateNet {
    static func isPrivate(host raw: String) -> Bool {
        let h = raw.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if ["localhost", "127.0.0.1", "::1"].contains(h) { return true }
        let p = h.split(separator: ".", omittingEmptySubsequences: false)
        if p.count == 4 {
            let n = p.compactMap { s -> Int? in
                guard !s.isEmpty, s.count <= 3, s.allSatisfy(\.isNumber), let v = Int(s), v <= 255 else { return nil }
                return v
            }
            guard n.count == 4 else { return false }
            switch (n[0], n[1]) {
            case (10, _), (127, _), (192, 168), (169, 254): return true          // RFC 1918, Loopback, Link-Local
            case (172, 16...31): return true
            case (100, 64...127): return true                                    // CGNAT (z. B. Tailscale)
            default: return false
            }
        }
        if h.contains(":") {                                                     // IPv6: ULA fc00::/7, Link-Local fe80::/10
            guard h.allSatisfy({ $0.isHexDigit || $0 == ":" || $0 == "%" || $0 == "." }) else { return false }
            return h.hasPrefix("fc") || h.hasPrefix("fd") || h.hasPrefix("fe8") || h.hasPrefix("fe9") || h.hasPrefix("fea") || h.hasPrefix("feb")
        }
        return false
    }
}
