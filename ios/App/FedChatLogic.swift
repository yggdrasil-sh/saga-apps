import Foundation

// Chats ueber Servergrenzen (Saga 2.3, docs/APP-API-FED.md im Server): reine Logik ohne Krypto und UI (mit swiftc pruefbar) -
// Handles, TOFU v2 (Regeln: tests/vectors/chat_v2.json "tofu"), Adressbuch-Treffer, Zusatzfelder foederierter Chats,
// Systemzeilen, Zustellstatus.

/// Person eines anderen Servers aus der Adressbuch-Suche (GET /api/fed/people, /api/chat/people "remote", /api/share/targets "remote")
struct FedPerson: Equatable, Identifiable {
    var name: String
    var host: String
    /// fertig zum Anzeigen: "Anna Berg · b.example" (bei gleichen Namen mit Zusatz)
    var label: String
    /// foederierter Handle '~x@host' (Chat); aeltere Server (2.2): nil
    var handle: String?
    /// Ziel zum Teilen: '@name@host' bzw. '@~x@host'
    var target: String
    var pub: String?
    var avatar: String?
    var id: String { handle ?? target }
    init(name: String, host: String, label: String? = nil, handle: String? = nil, target: String, pub: String? = nil, avatar: String? = nil) {
        self.name = name; self.host = host; self.label = label ?? FedChatLogic.label(name: name, host: host)
        self.handle = handle; self.target = target; self.pub = pub; self.avatar = avatar
    }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], (d["type"] as? String ?? "remote") == "remote", let host = ShareJSON.str(d["host"]) else { return nil }
        let handle = ShareJSON.str(d["handle"]).flatMap { FedChatLogic.isHandle($0) ? $0 : nil }
        guard let target = ShareJSON.str(d["target"]) ?? handle.map({ "@" + $0 }) else { return nil }
        let name = ShareJSON.str(d["name"]) ?? target
        self.init(name: name, host: host, label: ShareJSON.str(d["label"]), handle: handle, target: target, pub: ShareJSON.str(d["pub"]),
                  avatar: ShareJSON.str(d["avatar"]))
    }
}

/// Gemerkter Vertrauensstand je Handle (TOFU v2): {ident, verified, changed?, old?, dver}
struct FedTrust: Equatable, Codable {
    var ident: String
    var verified: Bool
    var changed: Bool = false
    var old: String? = nil
    var dver: Int = 0
}

enum FedChatLogic {
    // MARK: Handles

    /// '~' + 16 Zeichen a-z2-7 + '@host' (Host ggf. mit Port)
    static func isHandle(_ h: String) -> Bool {
        h.range(of: #"^~[a-z2-7]{16}@[^@\s/]+$"#, options: .regularExpression) != nil
    }
    /// Host aus Handle bzw. chat_ref (alles nach dem ERSTEN '@')
    static func host(of h: String) -> String {
        guard let i = h.firstIndex(of: "@") else { return "" }
        return String(h[h.index(after: i)...])
    }
    static func label(name: String, host: String) -> String { host.isEmpty ? name : name + " · " + host }

    // MARK: TOFU v2

    enum Verdict: String, Equatable { case trust, warn, block, reject, missing }
    /// Entscheidung + neuer Eintrag (nil = nichts speichern), genau wie tofuV2 in static/chat-crypto.js
    static func tofu(_ stored: FedTrust?, ident: String, ver: Int) -> (result: Verdict, store: FedTrust?) {
        guard let s = stored else { return (.trust, FedTrust(ident: ident, verified: false, dver: ver)) }
        if s.ident == ident {
            if ver < s.dver { return (.reject, nil) }
            var up: FedTrust?
            if ver > s.dver { up = s; up?.dver = ver }
            return (s.changed ? .warn : .trust, up)
        }
        if s.verified { return (.block, nil) }
        return (.warn, FedTrust(ident: ident, verified: false, changed: true, old: s.ident, dver: ver))
    }
    /// "Verstanden" (verified false) bzw. "Als ueberprueft markieren" (true): Warnung weg, Stand der Geraete-Liste bleibt
    static func confirm(_ stored: FedTrust?, ident: String, verified: Bool) -> FedTrust {
        FedTrust(ident: ident, verified: verified, dver: stored?.ident == ident ? (stored?.dver ?? 0) : 0)
    }
    /// "✓ ueberprueft" im Kopf: als ueberprueft markiert, gleicher Schluessel, keine Warnung
    static func isVerified(_ stored: FedTrust?, ident: String?, verdict: Verdict?) -> Bool {
        guard let s = stored, let ident else { return false }
        return s.verified && s.ident == ident && verdict == .trust
    }
    /// Senden/Anrufen gesperrt?
    static func blocksSending(_ v: Verdict?) -> Bool { v == .warn || v == .block || v == .reject }

    // MARK: Systemzeilen, Zustellstatus, Senden

    /// Systemzeile (kind 'sys', Klartext-Metadaten in body_ct), z. B. {"type":"ai","on":true,"by":handle,"name":…}
    enum SysLine: Equatable { case ai(on: Bool, byMe: Bool, name: String), other }
    static func sysLine(_ bodyCT: String?, myHandle: String?) -> SysLine {
        guard let j = bodyCT.flatMap({ ShareJSON.obj(Data($0.utf8)) }) else { return .other }
        if j["type"] as? String == "ai" {
            let by = ShareJSON.str(j["by"])
            return .ai(on: ShareJSON.bool(j["on"]), byMe: by != nil && by == myHandle, name: ShareJSON.str(j["name"]) ?? "?")
        }
        return .other
    }
    /// Zustellstatus einer Nachricht in v2-Chats (fstate)
    enum Delivery: String, Equatable { case delivered, pending, failed }
    static func delivery(_ v: Any?) -> Delivery {
        switch v as? String { case "pending": return .pending; case "failed": return .failed; default: return .delivered }
    }
    /// Live-Ereignis chat.message zu einer eigenen wartenden Nachricht: jetzt zugestellt bzw. abgewiesen
    static func liveDelivery(_ d: [String: Any]) -> Delivery { ShareJSON.bool(d["failed"]) ? .failed : .delivered }
    /// 409 beim Senden: Mitglieder/Geraete geaendert (Feld handles) -> Schluessel frisch holen und einmal neu versiegeln
    static func needsFreshKeys(code: Int, body: [String: Any]?) -> Bool { code == 409 && body?["handles"] != nil }

    /// Senden pruefen: Warnung/Blockade/ungueltig bei einer anderen Person -> nicht senden (Namen), eigene Schluessel noetig
    enum SendCheck: Equatable { case ok, blocked([String]), ownKeys }
    static func sendCheck(members: [FedMember], verdicts: [String: Verdict], myHandle: String) -> SendCheck {
        let bad = members.filter { !$0.me && blocksSending(verdicts[$0.handle]) }.map(\.name)
        if !bad.isEmpty { return .blocked(bad) }
        return verdicts[myHandle] == .trust ? .ok : .ownKeys
    }
    /// KI-Antwort in v2: Hash ist mitsigniert (Feld ai) - Text vergleichen statt Server-Beleg
    static func aiMeta(_ v: Any?, bodyRef: String?) -> AIReplyMeta? {
        guard let h = v as? String, !h.isEmpty else { return AIReplyMeta(v) }
        return AIReplyMeta(ref: bodyRef ?? "", sha256: h)
    }

    // MARK: Suche

    /// Personensuche: Server liefert bei 1-2 Zeichen nur genaue Treffer (ab 2.3) - die App fragt ab 1 Zeichen
    static let minQuery = 1
    static func searchReady(_ q: String) -> Bool { q.trimmingCharacters(in: .whitespacesAndNewlines).count >= minQuery }
    static func people(_ v: Any?) -> [FedPerson] { (v as? [Any] ?? []).compactMap(FedPerson.init) }
}

/// Mitglied eines foederierten Chats (members[] mit handle/host/remote/label)
struct FedMember: Equatable, Identifiable {
    var username: String; var handle: String; var name: String; var host: String; var remote: Bool; var me: Bool; var label: String
    var id: String { handle }
}

/// Zusatzfelder eines Chats ueber Servergrenzen (v: 2)
struct FedChatInfo: Equatable {
    var ref: String
    var home: String
    var homeSelf: Bool
    var myHandle: String
    var request: Bool
    var readonly: Bool
    var readonlyReason: String?
    var peer: FedMember?
    var peerAvatar: String?
    var members: [FedMember]
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], ShareJSON.int(d["v"]) == 2, let ref = ShareJSON.str(d["ref"]) else { return nil }
        self.ref = ref
        home = ShareJSON.str(d["home"]) ?? FedChatLogic.host(of: ref)
        homeSelf = ShareJSON.bool(d["home_self"])
        myHandle = ShareJSON.str(d["my_handle"]) ?? ""
        request = ShareJSON.bool(d["request"]) || (d["state"] as? String) == "request"
        readonly = ShareJSON.bool(d["readonly"]); readonlyReason = ShareJSON.str(d["readonly_reason"])
        members = (d["members"] as? [Any] ?? []).compactMap { v in
            guard let m = v as? [String: Any], let u = m["username"] as? String, let h = ShareJSON.str(m["handle"]) else { return nil }
            let name = m["name"] as? String ?? u, host = ShareJSON.str(m["host"]) ?? FedChatLogic.host(of: h)
            return FedMember(username: u, handle: h, name: name, host: host, remote: ShareJSON.bool(m["remote"]), me: ShareJSON.bool(m["me"]),
                             label: ShareJSON.str(m["label"]) ?? FedChatLogic.label(name: name, host: host))
        }
        if let p = d["peer"] as? [String: Any], let h = ShareJSON.str(p["handle"]) {
            let name = ShareJSON.str(p["name"]) ?? "?", host = ShareJSON.str(p["host"]) ?? FedChatLogic.host(of: h)
            peer = FedMember(username: ShareJSON.str(p["username"]) ?? "", handle: h, name: name, host: host, remote: true, me: false,
                             label: ShareJSON.str(p["label"]) ?? FedChatLogic.label(name: name, host: host))
            peerAvatar = ShareJSON.str(p["avatar"])
        }
    }
    /// Handles aller Mitglieder (auch der eigene) - fuer die Schluessel
    var handles: [String] {
        var out = members.map(\.handle)
        if !myHandle.isEmpty && !out.contains(myHandle) { out.append(myHandle) }
        return out
    }
    /// Anrufe nur aus angenommenen, beschreibbaren Direkt-Chats
    var callable: Bool { !request && !readonly }
}
