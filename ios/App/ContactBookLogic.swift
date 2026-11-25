import Foundation

// 2.6 (Bugs-DB 2027a-5): Adressbuch - Personen aus gemeinsamen Chats und eigene Kontakte mit "Name fuer mich", Anrede,
// Grussform, Telefonnummern und E-Mail-Adressen. Vertrag docs/APP-API.md "Kontakte (2.6, Bugs-DB 2027a, contacts2)".
// Reine Logik ohne SwiftUI (Tests: Tests/Logic/ContactBookTests.swift); Ansichten/Ablauf: ContactBook.swift.
// Vorbild Web: static/contacts.js. Nur mit Server-Feature contacts2 - sonst bleibt alles wie in 2.5 (contacts1).

enum ContactBookLogic {
    /// Server kann es: /api/version bzw. /api/chat/me features
    static let feature = "contacts2"
    /// fest vorgegebene Arten von Nummern/Mails (Plan 2027a Frage 8)
    static let types = ["mobile", "home", "work", "other"]
    /// Anrede: nicht gesetzt / du / Sie
    static let forms: [String?] = [nil, "informal", "formal"]
    static let pageSize = 200
    static let maxPerKind = 10
    static let ownNameMax = 120
    static let salutationMax = 80

    // MARK: Ansicht

    struct Addr: Equatable, Hashable, Identifiable {
        var id: Int?
        var kind: String                // saga | fed | e164 | email
        var value: String
        var type: String?               // mobile | home | work | other | nil
        var fixed: Bool
        var key: String { kind + ":" + value }
        var isPrivate: Bool { kind == "e164" || kind == "email" }
    }
    /// Saga-Adresse mit Live-Angaben (wie /api/chat/contacts) - ref fuer POST /api/chats (lokal user, fremd handle)
    struct Saga: Equatable, Hashable {
        var ref: String
        var remote = false
        var host: String?
        var sub: String?
        var avatar: String?
        var ready = true
        var gone = false
    }
    struct Contact: Equatable, Identifiable {
        var id: Int
        var ver: Int
        var origin = "chat"             // chat | manual
        var name: String
        var ownName: String?
        var saga: Saga?
        var form: String?
        var salutation: String?
        var addrs: [Addr] = []
        var lastAt: Double?
        var created: Double = 0
        var updated: Double = 0
        var manual: Bool { origin == "manual" }
        var phones: [Addr] { addrs.filter { $0.kind == "e164" } }
        var mails: [Addr] { addrs.filter { $0.kind == "email" } }
        var fixedAddrs: [Addr] { addrs.filter(\.fixed) }
    }

    /// Kontakt-Objekt lesen - ohne gueltige id/ver: nil. Unbekannte Felder und Arten werden uebergangen.
    static func contact(_ any: Any?) -> Contact? {
        guard let d = any as? [String: Any], let id = int(d["id"]), let ver = int(d["ver"]) else { return nil }
        let addrs = (d["addrs"] as? [Any] ?? []).compactMap(addr)
        let own = str(d["own_name"])
        let first = addrs.first(where: \.isPrivate)?.value
        let name = str(d["name"]) ?? own ?? first ?? "–"
        var c = Contact(id: id, ver: ver, name: name, ownName: own)
        c.origin = (d["origin"] as? String) == "manual" ? "manual" : "chat"
        c.saga = saga(d["saga"])
        c.form = (d["form"] as? String).flatMap { forms.contains($0) ? $0 : nil }
        c.salutation = str(d["salutation"])
        c.addrs = addrs.filter(\.fixed) + addrs.filter { !$0.fixed }          // feste zuerst
        c.lastAt = num(d["last_at"])
        c.created = num(d["created"]) ?? 0
        c.updated = num(d["updated"]) ?? 0
        return c
    }
    static func addr(_ any: Any?) -> Addr? {
        guard let a = any as? [String: Any], let k = a["kind"] as? String, ["saga", "fed", "e164", "email"].contains(k),
              let v = str(a["value"]) else { return nil }
        let t = (a["type"] as? String).flatMap { types.contains($0) ? $0 : nil }
        let fixed = (a["fixed"] as? Bool) ?? (k == "saga" || k == "fed")
        return Addr(id: int(a["id"]), kind: k, value: v, type: t, fixed: fixed)
    }
    static func saga(_ any: Any?) -> Saga? {
        guard let s = any as? [String: Any], let r = str(s["ref"]) else { return nil }
        let remote = (s["remote"] as? Bool) == true
        return Saga(ref: r, remote: remote, host: str(s["host"]) ?? (remote ? hostOf(r) : nil), sub: str(s["sub"]), avatar: str(s["avatar"]),
                    ready: (s["ready"] as? Bool) ?? true, gone: (s["gone"] as? Bool) == true)
    }
    /// '~x@host' -> host
    static func hostOf(_ ref: String) -> String? {
        guard let at = ref.lastIndex(of: "@") else { return nil }
        let h = String(ref[ref.index(after: at)...])
        return h.isEmpty ? nil : h
    }
    /// GET /api/contacts -> {contacts, total}; Reihenfolge wie vom Server (zuletzt zuerst)
    static func list(_ any: Any?) -> (contacts: [Contact], total: Int) {
        let d = any as? [String: Any]
        let cs = (d?["contacts"] as? [Any] ?? []).compactMap(contact)
        return (cs, int(d?["total"]) ?? cs.count)
    }
    static func listPath(q: String, limit: Int = pageSize, offset: Int = 0) -> String {
        var p = "api/contacts?limit=\(limit)&offset=\(offset)"
        let t = q.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty, let e = t.addingPercentEncoding(withAllowedCharacters: .alphanumerics) { p += "&q=" + e }
        return p
    }
    static func path(_ id: Int) -> String { "api/contacts/\(id)" }
    static let settingsPath = "api/contacts/settings"

    // MARK: Anzeige

    /// Unterzeile in der Liste: Saga-Person wie Chat-Kontakte, sonst erste Nummer bzw. Mail
    enum Sub: Equatable { case gone(remote: Bool, host: String), remote(String), local(String?), notReady(String?), addr(String), none }
    static func sub(_ c: Contact) -> Sub {
        if let s = c.saga {
            let host = s.host ?? s.sub ?? ""
            if s.gone { return .gone(remote: s.remote, host: host) }
            if s.remote { return .remote(host) }
            if !s.ready { return .notReady(s.sub) }
            return .local(s.sub)
        }
        if let p = c.phones.first { return .addr(phoneShown(p.value)) }
        if let m = c.mails.first { return .addr(m.value) }
        return .none
    }
    /// "Nachricht" anbieten? Nur mit Saga-Adresse, die anschreibbar ist
    static func canMessage(_ c: Contact) -> Bool { c.saga.map { !$0.gone && $0.ready } ?? false }
    /// POST /api/chats (Direkt-Chat): lokal user = Handle, fremd handle
    static func dmBody(_ s: Saga, device: String) -> [String: Any] {
        s.remote ? ["kind": "dm", "handle": s.ref, "device": device] : ["kind": "dm", "user": s.ref, "device": device]
    }

    /// E.164 lesbar: Landesvorwahl abgesetzt ("+49 1701234567"); unbekannte Vorwahl: wie gespeichert
    static func phoneShown(_ e164: String) -> String {
        guard e164.hasPrefix("+") else { return e164 }
        let digits = String(e164.dropFirst())
        for n in 1...3 where digits.count > n {
            let cc = String(digits.prefix(n))
            if callingCodes.contains(cc) { return "+" + cc + " " + digits.dropFirst(n) }
        }
        return e164
    }
    /// tel:/mailto: - nur fuer gespeicherte, normalisierte Werte
    static func telURL(_ e164: String) -> URL? {
        guard e164.range(of: #"^\+[1-9]\d{7,14}$"#, options: .regularExpression) != nil else { return nil }
        return URL(string: "tel:" + e164)
    }
    static func mailURL(_ mail: String) -> URL? {
        guard mail.filter({ $0 == "@" }).count == 1, !mail.contains(where: \.isWhitespace),
              let e = mail.addingPercentEncoding(withAllowedCharacters: .urlUserAllowed.union(["@"])) else { return nil }
        return URL(string: "mailto:" + e)
    }

    // MARK: Bearbeiten

    struct DraftAddr: Equatable, Identifiable {
        var id = UUID()
        var kind: String                // e164 | email
        var value: String
        var type: String?
    }
    struct Draft: Equatable {
        var ownName = ""
        var form: String?
        var salutation = ""
        var phones: [DraftAddr] = []
        var mails: [DraftAddr] = []
        /// Neuer Kontakt: eine leere Nummernzeile zum Losschreiben
        static func new() -> Draft { Draft(phones: [DraftAddr(kind: "e164", value: "", type: "mobile")]) }
        init(ownName: String = "", form: String? = nil, salutation: String = "", phones: [DraftAddr] = [], mails: [DraftAddr] = []) {
            self.ownName = ownName; self.form = form; self.salutation = salutation; self.phones = phones; self.mails = mails
        }
        init(_ c: Contact) {
            ownName = c.ownName ?? ""; form = c.form; salutation = c.salutation ?? ""
            phones = c.phones.map { DraftAddr(kind: "e164", value: phoneShown($0.value), type: $0.type) }
            mails = c.mails.map { DraftAddr(kind: "email", value: $0.value, type: $0.type) }
        }
        /// nicht leere Nummern/Mails (getrimmt), doppelte Werte einmal
        var addrs: [[String: Any]] {
            var seen = Set<String>()
            return (phones + mails).compactMap { a -> [String: Any]? in
                let v = a.value.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !v.isEmpty, seen.insert(a.kind + ":" + v).inserted else { return nil }
                var o: [String: Any] = ["kind": a.kind, "value": v]
                if let t = a.type, types.contains(t) { o["type"] = t }
                return o
            }
        }
        var trimmedName: String { clamp(ownName, max: ownNameMax) }
    }
    /// Sichern erlaubt? Neu bzw. eigener Kontakt: Name und mindestens eine Nummer/Mail (wie der Server); aus einem Chat: immer
    static func canSave(_ d: Draft, manual: Bool) -> Bool {
        guard manual else { return true }
        return !d.trimmedName.isEmpty && !d.addrs.isEmpty
    }
    static func canAdd(_ list: [DraftAddr]) -> Bool { list.count < maxPerKind }

    /// POST /api/contacts
    static func createBody(_ d: Draft, cc: String?) -> [String: Any] {
        var b: [String: Any] = ["own_name": d.trimmedName, "addrs": d.addrs]
        if let f = d.form { b["form"] = f }
        let s = clamp(d.salutation, max: salutationMax)
        if !s.isEmpty { b["salutation"] = s }
        if let cc { b["cc"] = cc }
        return b
    }
    /// PATCH /api/contacts/{id}: nur Geaendertes (leer = null), addrs ersetzt die privaten Adressen; feste bleiben (nicht senden)
    static func patchBody(_ d: Draft, base: Contact, cc: String?) -> [String: Any] {
        var b: [String: Any] = ["ver": base.ver]
        let own = d.trimmedName
        if own != (base.ownName ?? "") { b["own_name"] = own.isEmpty ? NSNull() : own }
        if d.form != base.form { b["form"] = d.form ?? NSNull() }
        let s = clamp(d.salutation, max: salutationMax)
        if s != (base.salutation ?? "") { b["salutation"] = s.isEmpty ? NSNull() : s }
        if !sameAddrs(d, base) { b["addrs"] = d.addrs }
        if let cc, b["addrs"] != nil { b["cc"] = cc }
        return b
    }
    /// gleiche private Adressen (Wert nach Anzeige, Typ, Reihenfolge)?
    static func sameAddrs(_ d: Draft, _ c: Contact) -> Bool {
        let now = d.addrs.map { "\($0["kind"] as? String ?? ""):\($0["value"] as? String ?? ""):\($0["type"] as? String ?? "")" }
        let was = (c.phones.map { ("e164", phoneShown($0.value), $0.type) } + c.mails.map { ("email", $0.value, $0.type) })
            .map { "\($0.0):\($0.1):\($0.2 ?? "")" }
        return now == was
    }
    static func dirty(_ d: Draft, base: Contact?) -> Bool {
        guard let base else { return !d.trimmedName.isEmpty || !d.addrs.isEmpty || d.form != nil || !clamp(d.salutation, max: salutationMax).isEmpty }
        return patchBody(d, base: base, cc: nil).count > 1
    }

    /// Antwort auf POST/PATCH: 2xx Kontakt; 409 mit contact = anderswo geaendert; 409 mit contact_id = Wert gibt es schon
    enum SaveResult: Equatable { case ok(Contact), conflict(Contact, String?), duplicate(Int, String?), failed(String?) }
    static func saveResult(status: Int, data: Data) -> SaveResult {
        let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let err = j.flatMap { str($0["error"]) }
        if (200..<300).contains(status), let c = contact(j) { return .ok(c) }
        if status == 409, let c = contact(j?["contact"]) { return .conflict(c, err) }
        if status == 409, let id = int(j?["contact_id"]) { return .duplicate(id, err) }
        return .failed(err)
    }

    // MARK: Landesvorwahl (GET/PUT /api/contacts/settings)

    /// {cc} -> "49" bzw. nil
    static func cc(_ any: Any?) -> String? { normCC((any as? [String: Any])?["cc"]) }
    /// "49", 49, "+49", " 0049"? -> "49" (1-3 Ziffern, keine fuehrende 0), sonst nil
    static func normCC(_ v: Any?) -> String? {
        var s: String
        if let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() { s = n.stringValue }
        else if let t = v as? String { s = t } else { return nil }
        s = s.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("+") { s.removeFirst() }
        guard s.range(of: #"^[1-9]\d{0,2}$"#, options: .regularExpression) != nil else { return nil }
        return s
    }
    /// Vorschlag aus der Region des Geraets ("DE" -> "49")
    static func suggestedCC(region: String?) -> String? {
        guard let r = region?.uppercased(), r.count == 2 else { return nil }
        return regionCodes[r]
    }
    /// Landesvorwahl fuer eine Anfrage: die Konto-Einstellung gilt (nichts mitsenden), sonst der Vorschlag der Region
    static func requestCC(setting: String?, suggestion: String?) -> String? { setting == nil ? suggestion : nil }
    /// Was fuer Nummern mit 0 gilt (Hinweis im Formular)
    static func effectiveCC(setting: String?, suggestion: String?) -> String? { setting ?? suggestion }
    /// Steht im Entwurf eine nationale Nummer (eine 0 vorn, nicht 00)?
    static func hasNational(_ d: Draft) -> Bool {
        d.phones.contains { p in
            let v = p.value.filter { !" -/.()".contains($0) }
            return v.hasPrefix("0") && !v.hasPrefix("00")
        }
    }

    // MARK: Live

    /// contacts.changed {id, deleted?} -> (id, geloescht)
    static func changed(_ d: [String: Any]) -> (id: Int, deleted: Bool)? {
        guard let id = int(d["id"]) else { return nil }
        return (id, (d["deleted"] as? Bool) == true)
    }

    // MARK: Hilfen

    static func clamp(_ s: String, max: Int) -> String {
        let clean = String(s.unicodeScalars.map { CharacterSet.controlCharacters.contains($0) ? " " : Character($0) })
        return String(clean.trimmingCharacters(in: .whitespacesAndNewlines).prefix(max))
    }
    static func str(_ v: Any?) -> String? { (v as? String).flatMap { $0.isEmpty ? nil : $0 } }
    static func num(_ v: Any?) -> Double? {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else { return nil }
        return n.doubleValue
    }
    static func int(_ v: Any?) -> Int? { num(v).flatMap { $0 == $0.rounded() && abs($0) < 1e15 ? Int($0) : nil } }

    /// Region (ISO 3166-1) -> Landesvorwahl (ITU-T E.164)
    static let regionCodes: [String: String] = {
        var m: [String: String] = [:]
        for part in regionTable.split(separator: " ") {
            let kv = part.split(separator: ":")
            guard kv.count == 2 else { continue }
            for r in kv[1].split(separator: ",") { m[String(r)] = String(kv[0]) }
        }
        return m
    }()
    static let callingCodes: Set<String> = Set(regionCodes.values)
    private static let regionTable = """
    1:AG,AI,AS,BB,BM,BS,CA,DM,DO,GD,GU,JM,KN,KY,LC,MP,MS,PR,SX,TC,TT,US,VC,VG,VI 7:KZ,RU 20:EG 27:ZA 30:GR 31:NL 32:BE \
    33:FR 34:ES 36:HU 39:IT,VA 40:RO 41:CH 43:AT 44:GB,GG,IM,JE 45:DK 46:SE 47:NO,SJ 48:PL 49:DE 51:PE 52:MX 53:CU 54:AR \
    55:BR 56:CL 57:CO 58:VE 60:MY 61:AU,CC,CX 62:ID 63:PH 64:NZ 65:SG 66:TH 81:JP 82:KR 84:VN 86:CN 90:TR 91:IN 92:PK 93:AF \
    94:LK 95:MM 98:IR 211:SS 212:EH,MA 213:DZ 216:TN 218:LY 220:GM 221:SN 222:MR 223:ML 224:GN 225:CI 226:BF 227:NE 228:TG \
    229:BJ 230:MU 231:LR 232:SL 233:GH 234:NG 235:TD 236:CF 237:CM 238:CV 239:ST 240:GQ 241:GA 242:CG 243:CD 244:AO 245:GW \
    246:IO 247:AC 248:SC 249:SD 250:RW 251:ET 252:SO 253:DJ 254:KE 255:TZ 256:UG 257:BI 258:MZ 260:ZM 261:MG 262:RE,YT \
    263:ZW 264:NA 265:MW 266:LS 267:BW 268:SZ 269:KM 290:SH,TA 291:ER 297:AW 298:FO 299:GL 350:GI 351:PT 352:LU 353:IE \
    354:IS 355:AL 356:MT 357:CY 358:AX,FI 359:BG 370:LT 371:LV 372:EE 373:MD 374:AM 375:BY 376:AD 377:MC 378:SM 380:UA \
    381:RS 382:ME 383:XK 385:HR 386:SI 387:BA 389:MK 420:CZ 421:SK 423:LI 500:FK 501:BZ 502:GT 503:SV 504:HN 505:NI 506:CR \
    507:PA 508:PM 509:HT 590:BL,GP,MF 591:BO 592:GY 593:EC 594:GF 595:PY 596:MQ 597:SR 598:UY 599:BQ,CW 670:TL 672:NF \
    673:BN 674:NR 675:PG 676:TO 677:SB 678:VU 679:FJ 680:PW 681:WF 682:CK 683:NU 685:WS 686:KI 687:NC 688:TV 689:PF 690:TK \
    691:FM 692:MH 850:KP 852:HK 853:MO 855:KH 856:LA 880:BD 886:TW 960:MV 961:LB 962:JO 963:SY 964:IQ 965:KW 966:SA 967:YE \
    968:OM 970:PS 971:AE 972:IL 973:BH 974:QA 975:BT 976:MN 977:NP 992:TJ 993:TM 994:AZ 995:GE 996:KG 998:UZ
    """
}
