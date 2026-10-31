import Foundation

// Saga 2.5 App-Runde Gruppeninfo + Kontakte (Server: docs/APP-API-CHAT.md Abschnitt 6 und 7, Web static/chat.js
// groupInfo()/addSearch()/drawContacts()): reine Logik ohne SwiftUI (mit swiftc pruefbar, Tests/Logic/GroupInfoTests.swift).
// Rechte aus can_*, Aktionen je Mitglied, Reihenfolge und Unterzeile der Mitglieder (nie der Anmeldename), Kontaktliste.

/// Was ich in der Gruppeninfo darf (aus can_manage/can_admin/can_rename; aeltere Server: aus role)
struct GroupRights: Equatable {
    /// Name setzen (Nachricht kind 'title')
    var rename = false
    /// Mitglieder hinzufuegen/entfernen
    var manage = false
    /// Admin vergeben/entziehen (nur mit chatadm1)
    var admin = false
    /// Mitglieder ergeben sich aus Gruppe bzw. Seite (nur lesend)
    var readOnlyMembers = false
}

/// Mitglied, wie es die Gruppeninfo braucht (ChatMember erfuellt es; Tests eigene Werte)
protocol GroupMemberLike {
    var username: String { get }
    var name: String { get }
    var me: Bool { get }
    var admin: Bool { get }
    var ref: String? { get }
    var sub: String? { get }
    var handle: String? { get }
}

enum GroupInfoLogic {
    /// Server meldet Gruppeninfo/Admins
    static let feature = "chatadm1"

    /// Rechte: mit `chatadm1` und den Feldern can_* genau die (nur freie Gruppen); fehlen sie (aeltere Server):
    /// wie bisher - Admin der freien Gruppe verwaltet, jedes Mitglied benennt um (schreibgeschuetzt: nichts), keine Admin-Vergabe
    static func rights(kind: String, role: String, canManage: Bool?, canAdmin: Bool?, canRename: Bool?, readonly: Bool, feature: Bool) -> GroupRights {
        var r = GroupRights()
        r.readOnlyMembers = kind == "group" || kind == "page"
        guard kind == "free" else { return r }
        if feature, let m = canManage {
            r.manage = m
            r.admin = canAdmin ?? false
            r.rename = canRename ?? !readonly
        } else {
            r.manage = role == "admin" && !readonly
            r.rename = !readonly
        }
        return r
    }

    enum MemberAction: Equatable { case safety, makeAdmin, revokeAdmin, remove }
    /// Menue je Mitglied (wie Web): Sicherheitsnummer (nicht ich), Admin vergeben/entziehen (bei can_admin; mich selbst nur
    /// entziehen), entfernen (bei can_manage, nicht mich - dafuer gibt es "Gruppe verlassen")
    static func actions(me: Bool, admin: Bool, kind: String, rights: GroupRights) -> [MemberAction] {
        var out: [MemberAction] = []
        if !me { out.append(.safety) }
        if kind == "free" && rights.admin && (!me || admin) { out.append(admin ? .revokeAdmin : .makeAdmin) }
        if kind == "free" && rights.manage && !me { out.append(.remove) }
        return out
    }

    /// Reihenfolge: ich zuerst, dann Admins, dann nach Name
    static func sorted<M: GroupMemberLike>(_ ms: [M]) -> [M] {
        ms.enumerated().sorted { a, b in
            if a.element.me != b.element.me { return a.element.me }
            if a.element.admin != b.element.admin { return a.element.admin }
            let c = a.element.name.localizedStandardCompare(b.element.name)
            return c == .orderedSame ? a.offset < b.offset : c == .orderedAscending
        }.map(\.element)
    }

    /// Handle '~x@host' bzw. '~x' (ref in Chats v1) - nie der Anmeldename
    static func handle(_ m: some GroupMemberLike) -> String? {
        var h = m.handle ?? ((m.ref ?? "").hasPrefix("~") ? m.ref : nil)
        if h?.hasPrefix("@") == true { h = String(h!.dropFirst()) }
        return (h ?? "").isEmpty ? nil : h
    }
    /// Unterzeile unter dem Namen: Handle (eigener Server ohne '@host'); Personen anderer Server (Chat ueber
    /// Servergrenzen) "auf host · ~x"; ohne Handle die Unterzeile des Servers (sub)
    static func subline(_ m: some GroupMemberLike, myHandle: String?, remoteChat: Bool) -> String? {
        let mine = myHandle.map { FedChatLogic.host(of: $0) } ?? ""
        let h = handle(m)
        var out: String?
        if let h {
            let host = FedChatLogic.host(of: h)
            out = !host.isEmpty && host == mine ? String(h.split(separator: "@", maxSplits: 1)[0]) : h
            if remoteChat, !host.isEmpty, host != mine, m.handle != nil {
                out = String(localized: "auf \(host)") + " · " + String(h.split(separator: "@", maxSplits: 1)[0])
            }
        } else {
            out = m.sub
        }
        return (out ?? "").isEmpty ? nil : out
    }

    /// Kennung fuer PATCH/DELETE …/members/{who}: members[].username (v1 Benutzername, Stufe 3 Handle) URL-kodiert
    static func memberPath(_ chatID: Int, _ who: String) -> String {
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "~.-_")
        return "api/chats/\(chatID)/members/" + (who.addingPercentEncoding(withAllowedCharacters: allowed) ?? who)
    }
}

// MARK: - Kontakte (contacts1)

/// Eintrag aus GET /api/chat/contacts - lokal ref = Such-Handle '~…', fremd '~x@host'
struct ChatContact: Equatable, Identifiable {
    var ref: String
    var name: String
    var sub: String?
    var host: String?
    var avatar: String?
    var remote: Bool
    var ready: Bool
    var gone: Bool
    var lastAt: Double
    var id: String { ref }
    init(ref: String, name: String, sub: String? = nil, host: String? = nil, avatar: String? = nil, remote: Bool = false,
         ready: Bool = true, gone: Bool = false, lastAt: Double = 0) {
        self.ref = ref; self.name = name; self.sub = sub; self.host = host; self.avatar = avatar; self.remote = remote
        self.ready = ready; self.gone = gone; self.lastAt = lastAt
    }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let ref = ShareJSON.str(d["ref"]) else { return nil }
        let remote = ShareJSON.bool(d["remote"])
        self.init(ref: ref, name: PersonName.shown(ShareJSON.str(d["name"]), id: ref), sub: PersonRef.sub(d["sub"]),
                  host: ShareJSON.str(d["host"]) ?? (remote ? FedChatLogic.host(of: ref) : nil), avatar: ShareJSON.str(d["avatar"]),
                  remote: remote, ready: d["ready"] == nil ? true : ShareJSON.bool(d["ready"]), gone: ShareJSON.bool(d["gone"]),
                  lastAt: ShareJSON.num(d["last_at"]) ?? 0)
    }
    /// als Treffer anderer Server (Neue Unterhaltung: Gruppe mit Personen anderer Server)
    var fedPerson: FedPerson {
        FedPerson(name: name, host: host ?? FedChatLogic.host(of: ref), handle: ref, target: "@" + ref, avatar: avatar)
    }
}

enum ContactsLogic {
    static let feature = "contacts1"
    /// so viele zeigt die Liste ohne Eingabe (wie Web)
    static let limit = 30
    static var listPath: String { "api/chat/contacts?limit=\(limit)" }

    /// Antwort lesen: zuletzt zuerst (last_at absteigend; Gleichstand in Server-Reihenfolge)
    static func parse(_ j: [String: Any]?) -> [ChatContact] {
        let ks = (j?["contacts"] as? [Any] ?? []).compactMap(ChatContact.init)
        return ks.enumerated().sorted { a, b in
            a.element.lastAt == b.element.lastAt ? a.offset < b.offset : a.element.lastAt > b.element.lastAt
        }.map(\.element)
    }
    /// waehlbar? (gone: Konto deaktiviert bzw. Server getrennt - ausgegraut)
    static func selectable(_ k: ChatContact) -> Bool { !k.gone }
    /// Unterzeile (wie Web contactItem)
    static func subline(_ k: ChatContact) -> String? {
        let host = k.host ?? k.sub ?? ""
        if k.gone { return k.remote ? String(localized: "Server \(host) nicht verbunden") : String(localized: "Konto nicht mehr aktiv") }
        if k.remote { return String(localized: "Person auf \(host)") }
        return PersonRef.subline(k.sub, k.ready ? nil : String(localized: "hat Chats noch nicht eingerichtet"))
    }
    /// "Mitglied hinzufuegen": ohne die schon dabei sind; fremde nur in Gruppen ueber Servergrenzen (v2/Stufe 3)
    static func forAdd<M: GroupMemberLike>(_ ks: [ChatContact], members: [M], fedChat: Bool) -> [ChatContact] {
        ks.filter { k in
            !members.contains { $0.username == k.ref || $0.ref == k.ref || $0.handle == k.ref } && (!k.remote || fedChat)
        }
    }
    /// POST /api/chats (Direkt-Chat)
    static func dmBody(_ k: ChatContact) -> [String: Any] { k.remote ? ["kind": "dm", "handle": k.ref] : ["kind": "dm", "user": k.ref] }
    /// POST /api/chats/{cid}/members
    static func memberBody(_ k: ChatContact) -> [String: Any] { k.remote ? ["handle": k.ref] : ["user": k.ref] }
    /// DELETE /api/chat/contacts/{ref} ('~x@host' darf '@' enthalten)
    static func deletePath(_ ref: String) -> String {
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "~.-_@")
        return "api/chat/contacts/" + (ref.addingPercentEncoding(withAllowedCharacters: allowed) ?? ref)
    }
}
