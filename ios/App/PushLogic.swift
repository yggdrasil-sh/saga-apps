import Foundation

// Mitteilungen aufs Geraet (Saga 1.7, APNs): reine Logik (mit swiftc pruefbar). Server: GET /api/push,
// PUT /api/push/settings, POST/DELETE /api/push/subscriptions[/{id}[/test]]. "Mitteilungen" = Push,
// "Benachrichtigungen" = Glocke in Saga - nicht vermischen.

struct PushDevice: Identifiable, Equatable {
    var id: Int; var type: String; var name: String; var whereText: String; var app: Bool; var thisDevice: Bool
    var created: Double?; var lastOK: Double?; var lastError: String?; var fails: Int; var encrypted: Bool
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let i = ShareJSON.int(d["id"]) else { return nil }
        id = i; type = d["type"] as? String ?? ""; name = d["name"] as? String ?? ""; whereText = d["where"] as? String ?? ""
        app = ShareJSON.bool(d["app"]); thisDevice = ShareJSON.bool(d["this_device"])
        created = ShareJSON.num(d["created"]); lastOK = ShareJSON.num(d["last_ok"]); lastError = ShareJSON.str(d["last_error"])
        fails = ShareJSON.int(d["fails"]) ?? 0; encrypted = d["encrypted"].map(ShareJSON.bool) ?? true
    }
}

struct PushState: Equatable {
    var enabled = false
    var apns = false
    var cats: [String: Bool] = [:]
    var categories: [String] = []
    var names = false
    var quietOn = false
    var quietFrom = "22:00"
    var quietTo = "07:00"
    var quietTZ = ""
    var devices: [PushDevice] = []
    var limit = 20
    init() {}
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        let s = j["server"] as? [String: Any] ?? [:]
        enabled = ShareJSON.bool(s["enabled"]); apns = ShareJSON.bool(s["apns"])
        let st = j["settings"] as? [String: Any] ?? [:]
        for (k, v) in st["cats"] as? [String: Any] ?? [:] { cats[k] = ShareJSON.bool(v) }
        categories = (j["categories"] as? [Any] ?? []).compactMap { $0 as? String }
        if categories.isEmpty { categories = PushLogic.order.filter { cats[$0] != nil } }
        names = ShareJSON.bool(st["names"])
        let q = st["quiet"] as? [String: Any] ?? [:]
        quietOn = ShareJSON.bool(q["on"]); quietFrom = ShareJSON.str(q["from"]) ?? "22:00"; quietTo = ShareJSON.str(q["to"]) ?? "07:00"
        quietTZ = q["tz"] as? String ?? ""
        devices = (j["devices"] as? [Any] ?? []).compactMap(PushDevice.init)
        limit = ShareJSON.int(j["limit"]) ?? 20
    }
    /// Abo dieses Geraets (gehoert zur Geraete-Anmeldung der App)
    var thisDevice: PushDevice? { devices.first { $0.thisDevice && $0.type == "apns" } }
}

enum PushLogic {
    /// Reihenfolge der Kategorien ("Worueber?") wie im Web
    static let order = ["share", "comment", "chat", "publish"]

    /// Geraetetoken -> Hex (klein)
    static func hex(_ token: Data) -> String { token.map { String(format: "%02x", $0) }.joined() }
    /// Debug-Builds: sandbox, sonst production
    static func env(debug: Bool) -> String { debug ? "sandbox" : "production" }
    static func subscription(token: String, env: String, name: String) -> [String: Any] {
        ["type": "apns", "token": token, "env": env, "name": String(name.prefix(80))]
    }
    /// Schalter: bedienbar oder warum nicht
    enum Availability: Equatable { case ok, serverOff, appleMissing, unsupported }
    static func availability(_ s: PushState?) -> Availability {
        guard let s else { return .unsupported }
        if !s.enabled { return .serverOff }
        if !s.apns { return .appleMissing }
        return .ok
    }
    /// Ruhezeiten: "HH:MM"
    static func validTime(_ t: String) -> Bool { t.range(of: #"^([01]\d|2[0-3]):[0-5]\d$"#, options: .regularExpression) != nil }
    /// Ergebnis von "Test senden"
    enum TestResult: Equatable { case ok, fail(String), tooFast }
    static func testResult(code: Int, json: [String: Any]?) -> TestResult {
        if code == 429 { return .tooFast }
        let r = json?["result"] as? String
        if r == "ok" { return .ok }
        return .fail(ShareJSON.str(json?["error"]) ?? r ?? "\(code)")
    }

    /// Bezeichnung des Dienstes in der Geraeteliste: Apple-Abos menschlich ("Saga-App", Entwicklungsbuild als Testversion)
    enum Service: Equatable { case sagaApp(test: Bool), other(String) }
    static func service(type: String, whereText: String) -> Service {
        guard type == "apns" else { return .other(whereText) }
        return .sagaApp(test: whereText.lowercased().contains("sandbox"))
    }
    /// Doppelte Abos desselben App-Geraets (z. B. nach neuem Token): alle ausser dem aktuellen entfernen
    static func duplicates(_ devices: [PushDevice], keep: Int?) -> [Int] {
        devices.filter { $0.thisDevice && $0.type == "apns" && $0.id != keep }.map(\.id)
    }
}
