import Foundation

// Pentest Runde 2 (Apps): Link-Allowlist (A2), ICE-Filter (A4), plausible Vorschau-Masse (V2i)
func pt2Tests() {
    // A2: nur http(s) mit Host und mailto ans System
    for u in ["https://example.org", "http://example.org/a?b=c#d", "HTTPS://Example.org", "mailto:anna@example.org", "https://[2001:db8::1]:8443/"] {
        check(LinkPolicy.external(u), "A2 erlaubt: \(u)")
    }
    for u in ["tel:+491234", "sms:123", "facetime:anna@example.org", "hlidskjalf://page/1", "saga://pair?u=x&c=y", "file:///etc/hosts",
              "javascript:alert(1)", "x-apple-reminderkit://x", "https:example.org", "mailto:", "ftp://example.org", "", "hkpage://5", "data:text/html,x"] {
        check(!LinkPolicy.external(u), "A2 verworfen: \(u)")
    }

    // A4: Schema und Form
    for u in ["stun:stun.example.org:3478", "stuns:stun.example.org", "turn:turn.example.org:3478?transport=udp",
              "turns:turn.example.org:443?transport=tcp", "TURNS:Turn.Example.org:443", "turn:[2001:db8::5]:3478", "stun:203.0.113.7:3478"] {
        check(IceFilter.allowed(u, serverLocal: false), "A4 erlaubt: \(u)")
    }
    for u in ["http://turn.example.org", "turn://turn.example.org", "turn:", "turn:host:0", "turn:host:70000", "turn:a b", "turn:host?transport=sctp",
              "turn:user@host", "javascript:alert(1)", "stun:host:3478/x", "", "turns:host:443?transport=tcp&x=1"] {
        check(!IceFilter.allowed(u, serverLocal: true), "A4 Form ungueltig: \(u)")
    }
    // nie Loopback/Link-Local/unspezifiziert, auch nicht im Heimnetz
    for h in ["127.0.0.1", "127.8.9.10", "0.0.0.0", "169.254.169.254", "224.0.0.1", "255.255.255.255", "localhost", "LOCALHOST.", "x.localhost",
              "[::1]", "[::]", "[fe80::1]", "[fe80::1%25en0]", "[::ffff:127.0.0.1]", "[::ffff:169.254.169.254]", "[ff02::1]", "127.1", "2130706433",
              "0x7f.0.0.1", "0177.0.0.1", "[0:0:0:0:0:0:0:1]"] {
        check(!IceFilter.allowed("turn:\(h):3478", serverLocal: false) && !IceFilter.allowed("turn:\(h):3478", serverLocal: true), "A4 gesperrt: \(h)")
    }
    // privat nur bei Server im lokalen Netz
    for h in ["10.0.0.5", "172.16.0.1", "172.31.255.1", "192.168.0.10", "100.64.1.2", "[fd12:3456::1]", "[::ffff:10.1.2.3]", "turn.local", "box.home.arpa"] {
        check(!IceFilter.allowed("turns:\(h):443?transport=tcp", serverLocal: false) && IceFilter.allowed("turns:\(h):443?transport=tcp", serverLocal: true),
              "A4 privat nur im LAN: \(h)")
    }
    check(IceFilter.allowed("turn:172.32.0.1", serverLocal: false), "A4 172.32 ist oeffentlich")
    check(IceFilter.filter(["stun:127.0.0.1", "stun:stun.example.org:443", "turn:10.0.0.1"], serverLocal: false) == ["stun:stun.example.org:443"], "A4 Filter")
    check(IceFilter.filter((0..<20).map { "stun:s\($0).example.org" }, serverLocal: false).count == 8, "A4 hoechstens 8")
    check(IceFilter.turnsURL(host: "2001:db8::5", port: 443) == "turns:[2001:db8::5]:443?transport=tcp" && IceFilter.allowed(IceFilter.turnsURL(host: "2001:db8::5", port: 443), serverLocal: false), "A4 turns IPv6")
    // Server im LAN: privat/.local direkt, Namen ueber (hier vorgegebene) Aufloesung
    check(IceFilter.serverLocal(host: "192.168.0.2", resolve: { _ in [] }) && IceFilter.serverLocal(host: "saga.local", resolve: { _ in [] }), "A4 Server privat")
    check(IceFilter.serverLocal(host: "saga.example.org", resolve: { _ in ["10.1.2.3"] }), "A4 Split-DNS")
    check(!IceFilter.serverLocal(host: "saga.example.org", resolve: { _ in ["203.0.113.9"] }) && !IceFilter.serverLocal(host: "", resolve: { _ in ["10.0.0.1"] }), "A4 Server oeffentlich")

    // V2i: unplausible Masse verwerfen
    check(ChatMediaLogic.plausible(w: 1200, h: 630) && ChatMediaLogic.plausible(w: 16384, h: 16384) && ChatMediaLogic.plausible(w: 500, h: 10), "V2i plausibel")
    check(!ChatMediaLogic.plausible(w: 0, h: 10) && !ChatMediaLogic.plausible(w: -1, h: 5) && !ChatMediaLogic.plausible(w: 20000, h: 20000)
          && !ChatMediaLogic.plausible(w: 5100, h: 100), "V2i unplausibel")
    let big = #"{"url":"https://example.org","title":"T","image":{"b64":"AAAA","w":20000,"h":20000}}"#
    let ok = #"{"url":"https://example.org","title":"T","image":{"b64":"AAAA","w":64,"h":64}}"#
    check(ChatStd.Unfurl(ShareJSON.obj(Data(big.utf8)))?.image == nil && ChatStd.Unfurl(ShareJSON.obj(Data(ok.utf8)))?.image?.w == 64, "V2i Unfurl-Bild mit Unsinns-Massen weg")
}
