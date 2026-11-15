import Foundation

// Saga 2.5 A3 "ICE-TCP im WebSocket-Tunnel" (Vertrag vertrag-a3.md; Konzept ws-apps §4, §6.2, §6.4, §9, §11): Anrufe
// ueber die normale Saga-Adresse auch hinter einem Proxy, der TLS selbst beendet (z. B. Nginx Proxy Manager mit
// "Websockets Support"). Je Leitung (Ton bzw. Bild) ein Tunnel:
// 1. connect(): WebSocket wss://<Saga>/api/sfu/ws mit Unterprotokoll saga-tunnel.1, Zugang (TURN-Zugangsdaten des
//    Medien-Tokens) nur in der ersten Textnachricht, Antwort abwarten - VOR dem Setzen der Antwort (sonst wartet
//    libwebrtc ~1,2 s auf den WS-Aufbau, gemessen 1,5-3,7 s statt 0,27/1,7 s).
// 2. listen(): Lauscher auf 127.0.0.1 (Port vom System) erst unmittelbar vor setRemoteDescription; das Antwort-SDP
//    biegt HornTunnelLogic.bend auf diesen Port. Die erste lokale Verbindung (libwebrtc, ICE-TCP aktiv) bekommt den WS,
//    danach schliesst der Lauscher sofort (keine weitere App auf dem Geraet kann sich nachtraeglich verbinden).
// 3. Durchreichen: Daten in Binaerrahmen (HornWire). Die App quittiert, was sie empfaengt (der Knoten fuehrt damit sein
//    Fenster fuer den Downlink); selbst sendet sie OHNE Fenster (Messung: ein App-Fenster bremst den Uplink, libwebrtc
//    verwirft dann intern auf dem TCP-Weg) - nur URLSession wird nicht ueberladen.
// libwebrtc, Jitter-Puffer, AEC, GCC und FrameCryptor bleiben unveraendert; der Tunnel sieht nur DTLS/SRTP-Pakete.
// Die Tunnel-Adresse erzeugt die App selbst: Sie geht NICHT durch IceFilter (der schuetzt vor Server-Angaben auf
// Loopback). BSD-Sockets statt NWListener: im Simulator lieferte NWListener angenommene Verbindungen nicht aus
// (SO_NECP_LISTENUUID, libwebrtc bekam ECONNRESET); so bleiben auch SO_RCVBUF/SO_SNDBUF klein (kein versteckter Stau).
final class HornTunnel: @unchecked Sendable {
    /// Aufbau gescheitert. upgrade(426) = Proxy reicht WebSocket nicht durch ("Websockets Support" aus)
    enum Fail: Error, LocalizedError, Equatable {
        case upgrade(Int), denied(String), timeout, listen, closed
        var errorDescription: String? {
            switch self {
            case .upgrade(let s): return HornWire.failText(status: s)
            case .denied(let c): return HornWire.failText(status: 0, code: c)
            case .timeout: return "ws Frist"
            case .listen: return "ws Lauscher"
            case .closed: return "ws zu"
            }
        }
        var status: Int { if case .upgrade(let s) = self { return s }; return 0 }
    }
    struct Stats: Equatable {
        var ready = false, readyMs = 0.0, accepted = 0, refused = 0, txBytes: UInt64 = 0, rxBytes: UInt64 = 0
        var acksOut = 0, acksIn = 0, held = 0, closed = ""
        var line: String { "ws ready=\(ready ? 1 : 0) \(Int(readyMs))ms acc=\(accepted) ref=\(refused) tx=\(txBytes) rx=\(rxBytes) ack=\(acksOut)/\(acksIn) held=\(held)\(closed.isEmpty ? "" : " zu=" + closed)" }
    }

    let url: URL
    private let hello: String
    private let session: URLSession
    private let q = DispatchQueue(label: "saga.horn.tunnel")
    private var ws: URLSessionWebSocketTask?
    private var lsrc: DispatchSourceRead?
    private var local: HornLocalSock?
    private var st = Stats()
    private var dead = false
    private(set) var port: UInt16 = 0
    /// Tunnel nach dem Aufbau zu (WS weg oder lokale Verbindung zu) - Grund fuer das Protokoll; auf q
    var onClose: ((String) -> Void)?

    /// base: Kandidaten-URL (BASE_URL im Tunnel-Betrieb); user/pass: POST /api/sfu/turn mit dem Ton-Token; role: Leitung
    init?(base: String, user: String, pass: String, role: String? = nil, session: URLSession = HornNet.session) {
        guard let u = HornWire.url(base) else { return nil }
        url = u; hello = HornWire.hello(user: user, pass: pass, role: role); self.session = session
    }
    deinit { lsrc?.cancel() }

    var stats: Stats { q.sync { st } }

    // MARK: 1. WebSocket vorab

    /// WS aufbauen und Zugang pruefen lassen; wirft Fail (426 = Proxy ohne WebSocket)
    func connect() async throws {
        let t0 = ProcessInfo.processInfo.systemUptime
        let t = session.webSocketTask(with: url, protocols: [HornWire.proto])
        t.maximumMessageSize = 1 << 20
        q.sync { ws = t }
        t.resume()
        let r: Result<URLSessionWebSocketTask.Message, Error> = await withCheckedContinuation { k in
            let once = HornLockOnce()
            t.send(.string(hello)) { e in if let e, once.take() { k.resume(returning: .failure(e)) } }
            t.receive { res in if once.take() { k.resume(returning: res) } }
            q.asyncAfter(deadline: .now() + .milliseconds(Int(HornWire.readyMs))) {
                if once.take() { k.resume(returning: .failure(Fail.timeout)) }
            }
        }
        do {
            switch r {
            case .failure(let e):
                if let f = e as? Fail { throw f }
                let s = (t.response as? HTTPURLResponse)?.statusCode ?? 0
                throw Fail.upgrade(s == 101 ? 0 : s)
            case .success(.string(let s)):
                switch HornWire.reply(s) {
                case .ok(let a): q.sync { acking = a }
                case .denied(let c): throw Fail.denied(c)
                case .bad: throw Fail.denied("antwort")
                }
            case .success: throw Fail.denied("antwort")
            }
        } catch {
            t.cancel(with: .normalClosure, reason: nil)
            q.sync { ws = nil; st.closed = (error as? Fail)?.errorDescription ?? "ws" }
            throw error
        }
        q.sync {
            st.ready = true; st.readyMs = (ProcessInfo.processInfo.systemUptime - t0) * 1000
            if dead { t.cancel(with: .normalClosure, reason: nil); ws = nil }
        }
    }

    // MARK: 2. Lauscher (kurz vor setRemoteDescription)

    /// Lauscher auf 127.0.0.1 oeffnen; nimmt genau eine Verbindung an und schliesst sich dann
    func listen() throws -> UInt16 {
        try q.sync {
            guard !dead, ws != nil else { throw Fail.closed }
            let fd = socket(AF_INET, SOCK_STREAM, 0)
            guard fd >= 0 else { throw Fail.listen }
            var one: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
            var a = sockaddr_in()
            a.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); a.sin_family = sa_family_t(AF_INET)
            a.sin_addr.s_addr = inet_addr("127.0.0.1"); a.sin_port = 0
            let ok = withUnsafePointer(to: &a) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
            guard ok == 0, Darwin.listen(fd, 4) == 0 else { Darwin.close(fd); throw Fail.listen }
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            _ = withUnsafeMutablePointer(to: &a) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) } }
            port = UInt16(bigEndian: a.sin_port)
            _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)
            let src = DispatchSource.makeReadSource(fileDescriptor: fd, queue: q)
            src.setEventHandler { [weak self] in
                while true {
                    let c = accept(fd, nil, nil)
                    if c < 0 { break }
                    guard let self, !self.dead, self.local == nil, self.ws != nil else { self?.st.refused += 1; Darwin.close(c); continue }
                    self.accepted(c)
                }
                self?.stopListening()                                       // genau eine Verbindung je Leitung
            }
            src.setCancelHandler { Darwin.close(fd) }
            src.resume()
            lsrc = src
            q.asyncAfter(deadline: .now() + .milliseconds(HornWire.listenMs)) { [weak self] in   // Pruefung F8: Frist fuer die erste Verbindung
                guard let self, !self.dead, self.local == nil else { return }
                self.fail("lauscher frist")
            }
            return port
        }
    }
    /// auf q
    private func stopListening() { lsrc?.cancel(); lsrc = nil }

    func close() {
        q.async {
            guard !self.dead else { return }
            self.dead = true
            if self.st.closed.isEmpty { self.st.closed = "zu" }
            self.onClose = nil
            self.stopListening()
            self.ws?.cancel(with: .normalClosure, reason: nil); self.ws = nil
            self.local?.cancel(); self.local = nil
        }
    }

    // MARK: 3. Durchreichen (alles auf q)

    private var sent: UInt64 = 0, rcvd: UInt64 = 0, ackedOut: UInt64 = 0, ackPending = false
    private var acking = HornWire.Acking()
    private var wsBusy = 0, held: Data?

    private func accepted(_ c: Int32) {
        st.accepted += 1
        let l = HornLocalSock(fd: c, q: q)
        local = l
        l.onClose = { [weak self] in self?.fail("lokal", code: .normalClosure) }   // libwebrtc schloss: 1000
        readWS()
        readLocal()
    }
    private func fail(_ why: String, code: URLSessionWebSocketTask.CloseCode = .goingAway) {
        guard !dead else { return }
        dead = true
        st.closed = why
        stopListening()
        ws?.cancel(with: code, reason: nil); ws = nil
        local?.cancel(); local = nil
        let k = onClose; onClose = nil
        k?(why)
    }

    /// libwebrtc -> Knoten: ohne Fenster, nur URLSession nicht ueberladen (hoechstens 64 offene Sendungen)
    private func readLocal() {
        guard !dead, held == nil, let l = local else { return }
        l.receive { [weak self] d in
            guard let self, !self.dead else { return }
            guard let d else { self.fail("lokal", code: .normalClosure); return }
            if self.wsBusy > 64 { self.held = d; self.st.held += 1; return }
            self.send(d)
            self.readLocal()
        }
    }
    private func send(_ d: Data) {
        guard let ws else { return }
        sent &+= UInt64(d.count); st.txBytes = sent
        wsBusy += 1
        ws.send(.data(HornWire.data(d))) { [weak self] e in
            guard let self else { return }
            self.q.async {
                self.wsBusy -= 1
                if e != nil { self.fail("ws senden"); return }
                if let h = self.held, self.wsBusy <= 64 { self.held = nil; self.send(h); self.readLocal() }
            }
        }
    }

    /// Knoten -> libwebrtc, Quittungen zurueck
    private func readWS() {
        guard let ws else { return }
        ws.receive { [weak self] r in
            guard let self else { return }
            self.q.async {
                guard !self.dead else { return }
                switch r {
                case .failure: self.fail("ws")
                case .success(.data(let b)):
                    switch HornWire.frame(b) {
                    case .data(let p): self.deliver(p)
                    case .ack: self.st.acksIn += 1
                    case .unknown: break
                    }
                    self.readWS()
                case .success: self.readWS()                                // Text nach dem Zugang: nicht vorgesehen, ignorieren
                }
            }
        }
    }
    private func deliver(_ d: Data) {
        local?.send(d)
        rcvd &+= UInt64(d.count); st.rxBytes = rcvd
        if rcvd &- ackedOut >= acking.bytes { ack() }
        else if !ackPending {
            ackPending = true
            q.asyncAfter(deadline: .now() + .milliseconds(acking.ms)) { [weak self] in self?.ack() }
        }
    }
    private func ack() {
        ackPending = false
        guard !dead, rcvd != ackedOut, let ws else { return }
        ackedOut = rcvd
        st.acksOut += 1
        ws.send(.data(HornWire.ack(rcvd))) { _ in }
    }
}

/// einmal ausloesen, von beliebigen Threads (URLSession-Rueckrufe, Frist)
final class HornLockOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func take() -> Bool { lock.lock(); defer { lock.unlock() }; if done { return false }; done = true; return true }
}

/// Nicht blockierender lokaler Socket (auf q): ein Lesewunsch zur Zeit (Rueckstau bis libwebrtc), Schreiben mit Puffer
final class HornLocalSock {
    let fd: Int32, q: DispatchQueue
    var onClose: (() -> Void)?
    private var rsrc: DispatchSourceRead?, wsrc: DispatchSourceWrite?
    private var want: ((Data?) -> Void)?
    private var out = Data(), dead = false, rActive = false

    init(fd: Int32, q: DispatchQueue, buf: Int32 = 64 << 10) {
        self.fd = fd; self.q = q
        var b = buf, one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &b, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &b, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)
        let r = DispatchSource.makeReadSource(fileDescriptor: fd, queue: q)
        r.setEventHandler { [weak self] in self?.readable() }
        rsrc = r                                                        // ruht, bis jemand lesen will
    }

    /// naechstes Stueck (nil = Ende/Fehler)
    func receive(_ k: @escaping (Data?) -> Void) {
        guard !dead else { k(nil); return }
        want = k
        if !rActive { rActive = true; rsrc?.resume() }
    }
    private func readable() {
        guard let k = want else { return }
        var buf = [UInt8](repeating: 0, count: 16 << 10)
        let n = Darwin.read(fd, &buf, buf.count)
        if n < 0 && (errno == EAGAIN || errno == EINTR) { return }
        want = nil
        if rActive { rActive = false; rsrc?.suspend() }
        if n <= 0 { fail(); k(nil); return }
        k(Data(buf[0..<n]))
    }
    func send(_ d: Data) {
        guard !dead else { return }
        out.append(d)
        flush()
    }
    private func flush() {
        while !out.isEmpty {
            let n = out.withUnsafeBytes { Darwin.write(fd, $0.baseAddress, $0.count) }
            if n > 0 { out.removeFirst(n); continue }
            if n < 0 && (errno == EAGAIN || errno == EINTR) {
                if wsrc == nil {
                    let w = DispatchSource.makeWriteSource(fileDescriptor: fd, queue: q)
                    w.setEventHandler { [weak self] in self?.flush() }
                    wsrc = w; w.resume()
                }
                return
            }
            fail(); return
        }
        wsrc?.cancel(); wsrc = nil
    }
    private func fail() {
        guard !dead else { return }
        dead = true
        let k = onClose; onClose = nil
        k?()
    }
    func cancel() {
        dead = true
        onClose = nil; want = nil
        wsrc?.cancel(); wsrc = nil
        if let r = rsrc {
            if !rActive { r.resume() }                                  // ruhende Quelle erst wecken, sonst Absturz beim Freigeben
            r.setCancelHandler { [fd] in Darwin.close(fd) }
            r.cancel()
        } else { Darwin.close(fd) }
        rsrc = nil
    }
}
