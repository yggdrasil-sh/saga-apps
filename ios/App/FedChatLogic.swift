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
    /// 2.5: aus dem v1-Stand (trust:<benutzer>) uebernommen - Hinweis "Neue Darstellung, gleicher Schluessel"
    var migrated: Bool? = nil
}

enum FedChatLogic {
    // MARK: Handles

    /// '~' + 16 Zeichen a-z2-7 + '@host' (Host ggf. mit Port)
    static func isHandle(_ h: String) -> Bool {
        h.range(of: #"^~[a-z2-7]{16}@[^@\s/]+$"#, options: .regularExpression) != nil
    }
    /// Geraete-Liste eines Schluessel-Eintrags: jede Geraete-ID hoechstens einmal (sonst Eintrag verwerfen)
    static func uniqueDeviceIDs(_ v: Any?) -> Bool {
        let ids = (v as? [Any] ?? []).compactMap { ($0 as? [String: Any])?["id"] as? String }
        return Set(ids).count == ids.count
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
        let same = stored?.ident == ident
        return FedTrust(ident: ident, verified: verified, dver: same ? (stored?.dver ?? 0) : 0, migrated: same ? stored?.migrated : nil)
    }
    /// v1-Vertrauensstand (trust:<benutzer>): {ident, verified, changed, old?}
    struct V1Trust: Equatable { var ident: String; var verified: Bool; var changed: Bool; var old: String? = nil }
    /// 2.5 Vertrauens-Uebernahme v1 -> v2 (nur solange es keinen Eintrag trust2:<handle> gibt), wie tofu_migrate in
    /// saga/chat_v3.py bzw. static/chat-crypto.js (Vektoren tests/vectors/chat_v3.json "tofu_migrate"): gleicher
    /// Identitaetsschluessel -> verified und changed gehen mit; anderer Schluessel und ueberprueft -> Blockade; sonst Warnung.
    static func tofuMigrate(_ v1: V1Trust?, ident: String, ver: Int) -> (result: Verdict, store: FedTrust?) {
        guard let v1 else { return (.trust, FedTrust(ident: ident, verified: false, dver: ver)) }
        if v1.ident == ident {
            var st = FedTrust(ident: ident, verified: v1.verified, dver: ver)
            if v1.changed { st.changed = true; st.old = v1.old; return (.warn, st) }
            return (.trust, st)
        }
        if v1.verified { return (.block, nil) }
        return (.warn, FedTrust(ident: ident, verified: false, changed: true, old: v1.ident, dver: ver))
    }
    /// "✓ ueberprueft" im Kopf: als ueberprueft markiert, gleicher Schluessel, keine Warnung
    static func isVerified(_ stored: FedTrust?, ident: String?, verdict: Verdict?) -> Bool {
        guard let s = stored, let ident else { return false }
        return s.verified && s.ident == ident && verdict == .trust
    }
    /// Senden/Anrufen gesperrt?
    static func blocksSending(_ v: Verdict?) -> Bool { v == .warn || v == .block || v == .reject }

    // MARK: Systemzeilen, Zustellstatus, Senden

    /// Person in einer Systemzeile ({name, host, handle}) - Herkunft wird immer gezeigt (ausser beim eigenen Server)
    struct SysWho: Equatable {
        var name: String; var host: String; var handle: String
        init(name: String, host: String, handle: String) { self.name = name; self.host = host; self.handle = handle }
        init?(_ v: Any?) {
            guard let d = v as? [String: Any] else { return nil }
            name = ShareJSON.str(d["name"]) ?? "?"; handle = ShareJSON.str(d["handle"]) ?? ""
            let hh = FedChatLogic.host(of: handle)                     // Herkunft aus dem Handle (nicht aus freiem Text)
            host = hh.isEmpty ? (ShareJSON.str(d["host"]) ?? "") : hh
        }
    }
    /// Mitglieder geaendert (E5): hinzugefuegt/entfernt, von wem (nil = ohne Person, z. B. getrennt), Grund
    struct MembersLine: Equatable {
        var added: [SysWho]; var removed: [SysWho]; var by: SysWho?; var reason: String?
    }
    /// Systemzeile (kind 'sys', Klartext-Metadaten in body_ct): KI ein/aus {"type":"ai",…}, Mitglieder {"type":"members",…},
    /// ab jetzt ueber Servergrenzen {"type":"fed","on":true,"ai_off":true?}
    /// 2.5: {"type":"crypto","level":3,"verify"?:true} - Chat auf Stufe 3 umgestellt (Handles statt Anmeldenamen)
    enum SysLine: Equatable { case ai(on: Bool, byMe: Bool, name: String), members(MembersLine), fed(aiOff: Bool), crypto(verify: Bool), other }
    static func sysLine(_ bodyCT: String?, myHandle: String?) -> SysLine {
        guard let j = bodyCT.flatMap({ ShareJSON.obj(Data($0.utf8)) }) else { return .other }
        switch j["type"] as? String {
        case "ai":
            let by = ShareJSON.str(j["by"])
            return .ai(on: ShareJSON.bool(j["on"]), byMe: by != nil && by == myHandle, name: ShareJSON.str(j["name"]) ?? "?")
        case "members":
            let list = { (k: String) in (j[k] as? [Any] ?? []).compactMap(SysWho.init) }
            return .members(MembersLine(added: list("added"), removed: list("removed"), by: SysWho(j["by"]), reason: ShareJSON.str(j["reason"])))
        case "fed": return .fed(aiOff: ShareJSON.bool(j["ai_off"]))
        case "crypto": return .crypto(verify: ShareJSON.bool(j["verify"]))
        default: return .other
        }
    }
    /// Text einer Systemzeile (wie static/chat.js sysText). `myHandle`: eigener Handle ("Du", "dich", Herkunft weglassen)
    static func sysText(_ line: SysLine, myHandle: String?) -> String {
        switch line {
        case .ai(let on, let byMe, let name):
            if byMe { return on ? String(localized: "Du hast die KI in diesem Chat eingeschaltet.") : String(localized: "Du hast die KI in diesem Chat ausgeschaltet.") }
            return on ? String(localized: "\(name) hat die KI in diesem Chat eingeschaltet.") : String(localized: "\(name) hat die KI in diesem Chat ausgeschaltet.")
        case .fed(let aiOff):
            let t = String(localized: "Ab jetzt mit Personen anderer Saga-Server (Ende-zu-Ende verschlüsselt).")
            return aiOff ? t + " " + String(localized: "Die KI ist hier jetzt aus.") : t
        case .members(let m): return membersText(m, myHandle: myHandle)
        case .crypto(let verify):
            let t = String(localized: "Dieser Chat ist jetzt an die stabilen Saga-Adressen gebunden (neue Verschlüsselungsstufe).")
            return verify ? t + " " + String(localized: "Bitte die Sicherheitsnummern neu vergleichen – sie werden jetzt anders angezeigt.") : t
        case .other: return String(localized: "Hinweis")
        }
    }
    private static func membersText(_ k: MembersLine, myHandle: String?) -> String {
        let mine = myHandle.map { host(of: $0) } ?? ""
        func who(_ x: SysWho, acc: Bool) -> String {
            if acc, let mh = myHandle, !x.handle.isEmpty, x.handle == mh { return String(localized: "dich") }   // "… hat dich hinzugefuegt"
            return x.host.isEmpty || x.host == mine ? x.name : x.name + " (" + x.host + ")"
        }
        let meBy = k.by.map { !$0.handle.isEmpty && $0.handle == myHandle } ?? false
        let by = k.by.flatMap { meBy ? nil : who($0, acc: false) }
        let add = k.added.map { who($0, acc: k.by != nil) }.joined(separator: ", ")
        let rm = k.removed.map { who($0, acc: k.by != nil) }.joined(separator: ", ")
        let rmNom = k.removed.map { who($0, acc: false) }.joined(separator: ", ")
        var out: [String] = []
        if !add.isEmpty {
            if meBy { out.append(String(localized: "Du hast \(add) hinzugefügt.")) }
            else if let by { out.append(String(localized: "\(by) hat \(add) hinzugefügt.")) }
            else { out.append(String(localized: "\(add) ist jetzt dabei.")) }
        }
        if !rm.isEmpty {
            let left = k.by != nil && k.removed.count == 1 && k.removed[0].handle == k.by?.handle
            if k.reason == "disconnected" { out.append(String(localized: "\(rmNom) ist nicht mehr dabei – die Verbindung zu diesem Saga-Server wurde getrennt.")) }
            else if k.reason == "gone" { out.append(String(localized: "\(rmNom) ist nicht mehr dabei (Konto gelöscht).")) }
            else if left { out.append(meBy ? String(localized: "Du hast die Gruppe verlassen.") : String(localized: "\(rmNom) hat die Gruppe verlassen.")) }
            else if meBy { out.append(String(localized: "Du hast \(rm) entfernt.")) }
            else if let by { out.append(String(localized: "\(by) hat \(rm) entfernt.")) }
            else { out.append(String(localized: "\(rmNom) ist nicht mehr dabei.")) }
        }
        return out.isEmpty ? String(localized: "Hinweis") : out.joined(separator: " ")
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

    // MARK: Schluessel, alte Nachrichten

    /// Abfrage fuer GET /api/chat/v2/keys (hoechstens 20, sortiert, ohne Doppelte)
    static func keysPath(_ handles: [String], fresh: Bool) -> String? {
        let hs = Array(Set(handles)).sorted().prefix(20)
        guard !hs.isEmpty else { return nil }
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "~.-_:")
        return "api/chat/v2/keys?handles=" + hs.map { $0.addingPercentEncoding(withAllowedCharacters: allowed) ?? $0 }.joined(separator: ",") + (fresh ? "&fresh=1" : "")
    }
    /// Mehr als 20 Handles (grosse Gruppen): mehrere Abfragen zu je hoechstens 20
    static func keysPaths(_ handles: [String], fresh: Bool) -> [String] {
        let hs = Array(Set(handles)).sorted()
        return stride(from: 0, to: hs.count, by: 20).compactMap { keysPath(Array(hs[$0..<min($0 + 20, hs.count)]), fresh: fresh) }
    }
    /// Nachricht eines auf v2 umgestellten Seiten-Chats aus der Zeit davor (ohne sender_handle, E5): mit v1 oeffnen.
    /// 2.5: ebenso Nachrichten eines auf Stufe 3 umgestellten Chats bis v3.seq (die haben nie einen sender_handle, A4.4)
    static func isLegacyV1(_ m: [String: Any]) -> Bool {
        (ShareJSON.str(m["sender_handle"]) ?? "").isEmpty && (m["kind"] as? String ?? "msg") != "sys"
    }

    // MARK: Suche

    /// Personensuche: Server ab 2.3 liefert bei 1-2 Zeichen nur genaue Treffer - dann ab 1 Zeichen, sonst wie bisher ab 3
    static func searchReady(_ q: String, server: String?) -> Bool { ShareLogic.searchReady(q, server: server) }
    static func people(_ v: Any?) -> [FedPerson] { (v as? [Any] ?? []).compactMap(FedPerson.init) }
}

/// Mitglied eines foederierten Chats (members[] mit handle/host/remote/label)
struct FedMember: Equatable, Identifiable {
    var username: String; var handle: String; var name: String; var host: String; var remote: Bool; var me: Bool; var label: String
    var id: String { handle }
}

/// Einladende Person einer Gruppe eines anderen Servers (invited_by)
struct FedInviter: Equatable { var name: String; var host: String; var label: String }

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
    /// Gruppe eines anderen Servers (E5): wer eingeladen hat ({name, host, label}), nur bei Anfragen
    var invitedBy: FedInviter?
    /// 2.5: Stufe des Chats - 2 = ueber Servergrenzen (2.3), 3 = lokal im Format v2 (Handles statt Anmeldenamen)
    var level = 2
    /// wirklich ueber Servergrenzen (Herkunft zeigen, Anfragen, Heim); Stufe 3 ist ein lokaler Chat
    var remote: Bool { level == 2 }
    /// 2.5: auf Stufe 3 umgestellt (v3 {seq, at, forced}): Nachrichten bis seq im Format v1
    var v3Seq: Int?
    var v3Forced = false
    /// 2.5: Reaktionen, Bearbeiten, Sprachnachrichten moeglich (Server meldet x3_ok)
    var x3OK = false
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let lv = ShareJSON.int(d["v"]), lv == 2 || lv == 3, let ref = ShareJSON.str(d["ref"]) else { return nil }
        self.ref = ref
        level = lv
        if let x = d["v3"] as? [String: Any] { v3Seq = ShareJSON.int(x["seq"]); v3Forced = ShareJSON.bool(x["forced"]) }
        x3OK = (d["x3_ok"] as? Bool) == true
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
        if let i = d["invited_by"] as? [String: Any] {
            let name = ShareJSON.str(i["name"]) ?? "?", host = ShareJSON.str(i["host"]) ?? home
            invitedBy = FedInviter(name: name, host: host, label: ShareJSON.str(i["label"]) ?? FedChatLogic.label(name: name, host: host))
        }
    }
    /// Wer bittet um Annahme: im Direkt-Chat das Gegenueber, in Gruppen die einladende Person (Rueckfall: Heim)
    var requester: FedInviter {
        if let p = peer { return FedInviter(name: p.name, host: p.host, label: p.label) }
        return invitedBy ?? FedInviter(name: "?", host: home, label: home)
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
