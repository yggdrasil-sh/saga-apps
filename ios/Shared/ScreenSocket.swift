import Foundation
import Darwin

// Bildschirm teilen auf iPhone/iPad (2.4 E3): Unix-Socket zwischen ReplayKit-Erweiterung und App (ScreenFrame.swift).
// Ausgelagert, damit der Ablauf mit swiftc pruefbar ist (Tests/Logic/ScreenShareTests.swift).
//
// Wichtig (2.5-Fix): Auf Darwin/BSD erbt ein per accept() angenommener Socket die Dateistatus-Flags des lauschenden,
// also auch O_NONBLOCK (anders als Linux). Die App lauscht nicht blockierend (DispatchSource), der Lese-Thread liest
// aber blockierend - ohne das Zuruecksetzen lieferte das erste read() sofort EAGAIN, die App trennte die Verbindung
// und die Erweiterung beendete die Uebertragung ("Teilen beendet.").

enum ScreenSocket {
    /// sockaddr_un fuer path; nil, wenn der Pfad nicht hineinpasst (104 Byte inklusive Nullbyte)
    static func address(_ path: String) -> sockaddr_un? {
        let bytes = Array(path.utf8)
        var addr = sockaddr_un()
        guard !bytes.isEmpty, bytes.count < MemoryLayout.size(ofValue: addr.sun_path) else { return nil }
        addr.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &addr.sun_path) { p in for (i, c) in bytes.enumerated() { p[i] = c } }
        return addr
    }
    /// App: lauschender Socket (nicht blockierend, fuer die DispatchSource); alte Datei wird entfernt
    static func listen(path: String) -> Int32? {
        guard var addr = address(path) else { return nil }
        unlink(path)
        let s = socket(AF_UNIX, SOCK_STREAM, 0)
        guard s >= 0 else { return nil }
        let ok = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(s, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } } == 0
        guard ok, Darwin.listen(s, 1) == 0 else { Darwin.close(s); return nil }
        _ = fcntl(s, F_SETFL, fcntl(s, F_GETFL) | O_NONBLOCK)
        return s
    }
    /// App: Verbindung annehmen - blockierend fuer den Lese-Thread (O_NONBLOCK NICHT vom lauschenden Socket erben)
    static func accept(_ listenFD: Int32) -> Int32? {
        let c = Darwin.accept(listenFD, nil, nil)
        guard c >= 0 else { return nil }
        let fl = fcntl(c, F_GETFL)
        if fl >= 0 && fl & O_NONBLOCK != 0 { _ = fcntl(c, F_SETFL, fl & ~O_NONBLOCK) }
        var one: Int32 = 1
        setsockopt(c, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        return c
    }
    /// Erweiterung: verbinden (blockierend, Sende-Zeitgrenze sendTimeout s - haengt die App, nicht ewig warten)
    static func connect(path: String, sendTimeout: Int = 2) -> Int32? {
        guard var addr = address(path) else { return nil }
        let s = socket(AF_UNIX, SOCK_STREAM, 0)
        guard s >= 0 else { return nil }
        var one: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: sendTimeout, tv_usec: 0)
        setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        let r = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(s, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard r == 0 else { Darwin.close(s); return nil }
        return s
    }
    static func isBlocking(_ fd: Int32) -> Bool { fcntl(fd, F_GETFL) & O_NONBLOCK == 0 }
    /// genau n Byte lesen (EINTR wiederholen); false = Verbindung zu bzw. Fehler
    static func readAll(_ c: Int32, _ p: UnsafeMutableRawPointer, _ n: Int) -> Bool {
        var off = 0
        while off < n {
            let r = Darwin.read(c, p + off, n - off)
            if r < 0 && errno == EINTR { continue }
            if r <= 0 { return false }
            off += r
        }
        return true
    }
    /// genau n Byte schreiben (EINTR wiederholen); false = Verbindung zu, Fehler bzw. Zeitgrenze
    static func writeAll(_ s: Int32, _ p: UnsafeRawPointer, _ n: Int) -> Bool {
        var off = 0
        while off < n {
            let w = Darwin.write(s, p + off, n - off)
            if w < 0 && errno == EINTR { continue }
            if w <= 0 { return false }
            off += w
        }
        return true
    }
}
