import Foundation

/// Saga 2.5: Lesezeichen-Favicon direkt von der Seite laden (Rueckfall, wenn der eigene Server es nicht liefert -
/// z. B. Bot-Schutz 503). Eigene, fluechtige Sitzung: keine Cookies, keine gespeicherten Anmeldedaten, kein
/// Anmeldedialog, keine Weiterleitung von https auf http; Zeitlimit und Groessenlimit aus FaviconLogic. Ergebnis
/// (auch Fehlversuch) liegt im App-Zwischenspeicher (Caches/hk/favicons, wird beim Abmelden mit geloescht).
/// Nichts davon geht zum Server.
final class FaviconLoader: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    static let shared = FaviconLoader()

    private let lock = NSLock()
    private var memory: [String: Data] = [:]
    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.httpCookieStorage = nil; c.httpShouldSetCookies = false; c.httpCookieAcceptPolicy = .never
        c.urlCredentialStorage = nil; c.urlCache = nil; c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.timeoutIntervalForRequest = FaviconLogic.timeout; c.timeoutIntervalForResource = FaviconLogic.timeout
        c.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }()
    private var dir: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("hk/favicons", isDirectory: true)
    }

    /// Erstes brauchbare Bild der Kandidaten (Reihenfolge wie geliefert) - oder nil (dann Anfangsbuchstabe)
    func load(_ urls: [URL], decodes: (Data) -> Bool) async -> Data? {
        guard !urls.isEmpty else { return nil }
        let key = FaviconLogic.cacheKey(urls)
        if let m = lock.withLock({ memory[key] }) { return m }
        let img = dir.appendingPathComponent(key + ".img"), miss = dir.appendingPathComponent(key + ".miss")
        let now = Date()
        if let at = modified(img), FaviconLogic.cacheDecision(ok: true, savedAt: at, now: now) == .use,
           let d = try? Data(contentsOf: img), decodes(d) {
            lock.withLock { memory[key] = d }; return d
        }
        if let at = modified(miss), FaviconLogic.cacheDecision(ok: false, savedAt: at, now: now) == .knownMissing { return nil }
        for u in urls {
            if Task.isCancelled { return nil }                            // Kachel verschwunden: nichts als Fehlversuch merken
            if let d = await fetch(u), decodes(d) {
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                try? d.write(to: img, options: .atomic); try? FileManager.default.removeItem(at: miss)
                lock.withLock { memory[key] = d }
                return d
            }
        }
        if Task.isCancelled { return nil }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? Data().write(to: miss, options: .atomic); try? FileManager.default.removeItem(at: img)
        return nil
    }

    /// Abmelden: Speicher leeren (die Dateien loescht Store mit dem ganzen Zwischenspeicher)
    func reset() { lock.withLock { memory.removeAll() } }

    private func modified(_ u: URL) -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: u.path))?[.modificationDate] as? Date
    }

    private func fetch(_ url: URL) async -> Data? {
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: FaviconLogic.timeout)
        req.httpShouldHandleCookies = false
        req.setValue("image/png,image/x-icon,image/vnd.microsoft.icon,image/jpeg,image/gif,image/webp,*/*;q=0.1", forHTTPHeaderField: "Accept")
        do {
            let (bytes, resp) = try await session.bytes(for: req)
            guard let h = resp as? HTTPURLResponse else { bytes.task.cancel(); return nil }
            let declared = h.expectedContentLength >= 0 ? h.expectedContentLength : nil
            let ct = h.value(forHTTPHeaderField: "Content-Type")
            if FaviconLogic.rejectEarly(status: h.statusCode, contentType: ct, declaredLength: declared) { bytes.task.cancel(); return nil }
            var d = Data(); d.reserveCapacity(Int(min(declared ?? 16_384, Int64(FaviconLogic.maxBytes))))
            for try await b in bytes {
                d.append(b)
                if d.count > FaviconLogic.maxBytes { bytes.task.cancel(); return nil }
            }
            return FaviconLogic.accept(status: h.statusCode, contentType: ct, declaredLength: declared, data: d) ? d : nil
        } catch { return nil }
    }

    // Keine Anmeldedaten: Server-Zertifikat normal pruefen, jede andere Anfrage (Basic, Client-Zertifikat ...) abbrechen
    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust {
            completionHandler(.performDefaultHandling, nil)
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
    // Weiterleitungen wie im Browser, aber nur http/https und nie von https auf http
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        let from = task.originalRequest?.url?.scheme?.lowercased(), to = request.url?.scheme?.lowercased()
        let ok = (to == "https" || to == "http") && !(from == "https" && to == "http")
        completionHandler(ok ? request : nil)
    }
}
