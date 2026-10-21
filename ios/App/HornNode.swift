import Foundation
import Network

// Gjallarhorn (Saga 2.5, P11 Stufe 2): Gespraech mit einem Knoten - ohne WebRTC, ohne UI (auch im Integrationstest
// gegen einen lokalen Knoten benutzt, Tests/Horn/HornNodeTests.swift). Vertrag: docs/APP-API.md "Am Knoten" und
// gjallarhorn/NODE.md:
// - Messung (§2.6): TLS-Aufbau + 5 Pings ueber wss://{knoten}/probe (Echo), bei udp_ok ein STUN-Binding an den Knoten;
//   alles zusammen hoechstens 800 ms
// - POST /api/sfu/leg, POST /api/sfu/turn, DELETE /api/sfu/leg/{id} - jede Anfrage mit Zeitlimit (15 s)
// - Ereigniskanal wss://{knoten}/api/sfu/leg/{id}/events (Text-Nachrichten, Ping alle 2 s fuer die RTT)
// Keine Konto- oder Geraetenamen gehen an den Knoten: nur Medien-Token (von Saga), SDP und Zahlen.

enum HornNet {
    /// URLSession fuer Knoten (eigene Sitzung, ohne Cookies/Cache). Tests setzen eine eigene (lokaler Knoten mit
    /// selbst signiertem Zertifikat); die App prueft Zertifikate immer wie das System.
    nonisolated(unsafe) static var session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = HornLogic.fetchMs / 1000
        c.httpCookieStorage = nil; c.urlCache = nil; c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()
    /// nur Tests: STUN-Binding an einen anderen Port als die URL (lokaler Knoten)
    nonisolated(unsafe) static var stunPortOverride: Int?

    struct HTTPError: Error, LocalizedError {
        let status: Int
        let message: String
        var errorDescription: String? { message.isEmpty ? "HTTP \(status)" : message }
    }

    /// JSON an den Knoten; Fehler mit Status (401 Token, 404 Raum unbekannt, 429 voll/Leeren)
    static func json(_ url: String, body: [String: Any]?, method: String? = nil) async throws -> [String: Any] {
        guard let u = URL(string: url) else { throw HTTPError(status: 0, message: "url") }
        var r = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: HornLogic.fetchMs / 1000)
        r.httpMethod = method ?? (body == nil ? "GET" : "POST")
        if let body {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (d, resp): (Data, URLResponse)
        do { (d, resp) = try await session.data(for: r) }
        catch { throw HTTPError(status: 0, message: String(localized: "Verbindung nicht aufgebaut: \(error.localizedDescription)")) }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] ?? [:]
        guard (200..<300).contains(code) else { throw HTTPError(status: code, message: (j["error"] as? String) ?? "HTTP \(code)") }
        return j
    }

    /// POST /api/sfu/leg
    static func leg(_ base: String, _ body: [String: Any]) async throws -> HornLogic.LegAnswer {
        let j = try await json(base + "/api/sfu/leg", body: body)
        guard let a = HornLogic.LegAnswer(j) else { throw HTTPError(status: 502, message: "leg") }
        return a
    }
    /// POST /api/sfu/turn -> Zugang fuer TURN-TLS auf demselben Port
    static func turn(_ base: String, token: String) async throws -> (user: String, pass: String) {
        let j = try await json(base + "/api/sfu/turn", body: ["token": token])
        guard let u = j["username"] as? String, let p = j["credential"] as? String else { throw HTTPError(status: 502, message: "turn") }
        return (u, p)
    }
    static func deleteLeg(_ base: String, id: String) {
        guard let e = id.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { return }
        Task.detached { _ = try? await json(base + "/api/sfu/leg/" + e, body: nil, method: "DELETE") }
    }

    // MARK: Messung

    /// TLS-Aufbau + 5 Pings ueber /probe, parallel dazu (udp_ok) ein STUN-Binding; hoechstens ms Millisekunden
    static func probe(_ c: HornLogic.Candidate, ms: Double = HornLogic.probeMs) async -> HornLogic.Measure {
        async let w = probeWSS(c.wsURL + "/probe", ms: ms)
        async let u: Double? = c.udp ? stun(c, ms: ms) : nil
        var out = await w
        out.udp = await u
        return out
    }
    /// Oeffnen des WebSockets abwarten (TLS-Aufbau): true = offen, false = Fehler/Frist
    private final class OpenWatch: NSObject, URLSessionWebSocketDelegate, @unchecked Sendable {
        private let lock = NSLock()
        private var result: Bool?
        private var k: CheckedContinuation<Bool, Never>?
        func urlSession(_ s: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol p: String?) { finish(true) }
        func urlSession(_ s: URLSession, task: URLSessionTask, didCompleteWithError e: Error?) { finish(false) }
        func finish(_ ok: Bool) {
            lock.lock()
            guard result == nil else { lock.unlock(); return }
            result = ok
            let c = k; k = nil
            lock.unlock()
            c?.resume(returning: ok)
        }
        func wait() async -> Bool {
            await withCheckedContinuation { c in
                lock.lock()
                if let r = result { lock.unlock(); c.resume(returning: r); return }
                k = c
                lock.unlock()
            }
        }
    }
    static func probeWSS(_ url: String, ms: Double) async -> HornLogic.Measure {
        var out = HornLogic.Measure()
        guard let u = URL(string: url) else { return out }
        let t0 = DispatchTime.now().uptimeNanoseconds
        let ws = session.webSocketTask(with: u)
        let watch = OpenWatch()
        ws.delegate = watch
        ws.resume()
        let deadline = Task { try? await Task.sleep(nanoseconds: UInt64(ms * 1e6)); ws.cancel(with: .goingAway, reason: nil); watch.finish(false) }
        defer { deadline.cancel(); ws.cancel(with: .normalClosure, reason: nil) }
        guard await watch.wait() else { return out }
        out.tls = Double(DispatchTime.now().uptimeNanoseconds - t0) / 1e6
        for n in 1...HornLogic.pings {
            let s = DispatchTime.now().uptimeNanoseconds
            do {
                try await ws.send(.string("p\(n)"))
                var got = false
                while !got {
                    let m = try await ws.receive()
                    if case .string(let t) = m, t == "p\(n)" { got = true }
                }
            } catch { break }
            out.rtts.append((Double(DispatchTime.now().uptimeNanoseconds - s) / 1e5).rounded() / 10)
            out.ok = true
        }
        return out
    }

    /// STUN Binding Request (RFC 5389, ohne Attribute) an den Knoten; Antwort = UDP erreichbar (Zeit in ms)
    static func stun(_ c: HornLogic.Candidate, ms: Double) async -> Double? {
        guard let hp = c.hostPort, let port = NWEndpoint.Port(rawValue: UInt16(stunPortOverride ?? hp.port)) else { return nil }
        let conn = NWConnection(host: NWEndpoint.Host(hp.host), port: port, using: .udp)
        let tid = (0..<12).map { _ in UInt8.random(in: 0...255) }
        var head = Data([0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4, 0x42]); head.append(contentsOf: tid)
        let req = head
        let q = DispatchQueue(label: "saga.horn.stun")
        let t0 = DispatchTime.now().uptimeNanoseconds
        let result: Double? = await withCheckedContinuation { (k: CheckedContinuation<Double?, Never>) in
            let lock = NSLock(); var done = false
            func fin(_ v: Double?) { lock.lock(); let first = !done; done = true; lock.unlock(); if first { conn.cancel(); k.resume(returning: v) } }
            conn.stateUpdateHandler = { st in
                switch st {
                case .ready:
                    conn.send(content: req, completion: .contentProcessed { e in if e != nil { fin(nil) } })
                    conn.receiveMessage { d, _, _, e in
                        guard e == nil, let d, d.count >= 20, d[d.startIndex] == 0x01, d[d.startIndex + 1] == 0x01, Array(d[(d.startIndex + 8)..<(d.startIndex + 20)]) == tid
                        else { fin(nil); return }
                        fin(Double(DispatchTime.now().uptimeNanoseconds - t0) / 1e6)
                    }
                case .failed, .cancelled: fin(nil)
                default: break
                }
            }
            conn.start(queue: q)
            q.asyncAfter(deadline: .now() + .milliseconds(Int(ms))) { fin(nil) }
        }
        return result
    }
}

// MARK: - Ereigniskanal je Bein

/// wss://{knoten}/api/sfu/leg/{id}/events - Nachrichten kommen auf dem MainActor an
@MainActor
final class HornEvents {
    private var ws: URLSessionWebSocketTask?
    private(set) var opened = false
    private var loop: Task<Void, Never>?
    var onEvent: ((HornLogic.Event) -> Void)?
    /// Kanal zu (nach dem Oeffnen: again = erneuter Versuch; nicht geoeffnet = Knoten nimmt nichts mehr an)
    var onClose: ((_ opened: Bool) -> Void)?
    let url: URL

    init?(base: String, leg: String) {
        guard let e = leg.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let u = URL(string: HornLogic.Candidate(node: "", url: base).wsURL + "/api/sfu/leg/" + e + "/events") else { return nil }
        url = u
    }
    func start() {
        let t = HornNet.session.webSocketTask(with: url)
        t.maximumMessageSize = 256 * 1024
        ws = t
        t.resume()
        loop = Task { [weak self] in
            while !Task.isCancelled {
                guard let msg = try? await t.receive() else { break }
                guard let self else { return }
                self.opened = true
                if case .string(let s) = msg, let ev = HornLogic.event(s) { self.onEvent?(ev) }
            }
            guard let self, !Task.isCancelled else { return }
            let o = self.opened
            self.ws = nil
            self.onClose?(o)
        }
    }
    var isOpen: Bool { ws != nil }
    func send(_ text: String) { ws?.send(.string(text)) { _ in } }
    func close() {
        loop?.cancel(); loop = nil
        ws?.cancel(with: .normalClosure, reason: nil); ws = nil
        onEvent = nil; onClose = nil
    }
}
