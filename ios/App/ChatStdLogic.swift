import Foundation

// Saga 2.5 App-Runde "Chat-Standard" (Server docs/APP-API-CHAT.md 6a, 8, 9; Web static/chat.js): reine Logik ohne
// SwiftUI (mit swiftc pruefbar, Tests/Logic/ChatStdTests.swift). Feature-Gating, Felder des Chat-Objekts, Rechte,
// Seiten-Chat-Mitglieder, Gruppenbild/Beschreibung, Pins, Grabsteine, Sperren/Melden, Stummschalten mit Dauer,
// Erwaehnungen (<@kennung>), Linkvorschau im verschluesselten Inhalt, Entwuerfe je Konto und Chat.

/// Welche Funktionen der Server meldet (je ein Feature aus /api/version)
struct ChatStdFeatures: Equatable {
    var pageMembers = false     // pagechatmembers1
    var groupInfo2 = false      // groupinfo2: Bild, Beschreibung, Rechte
    var modDel = false          // moddel1
    var msgPin = false          // msgpin1
    var block = false           // block2
    var report = false          // report1
    var mute2 = false           // mute2
    var chatPin = false         // chatpin1
    var markUnread = false      // markunread1
    var hide = false            // chathide1
    var mention = false         // chatmention1
    var fmt = false             // chatfmt1
    var unfurl = false          // unfurl1 (Admin-Schalter)
    var media = false           // chatmedia1
    init(_ has: (String) -> Bool) {
        pageMembers = has("pagechatmembers1"); groupInfo2 = has("groupinfo2"); modDel = has("moddel1"); msgPin = has("msgpin1")
        block = has("block2"); report = has("report1"); mute2 = has("mute2"); chatPin = has("chatpin1"); markUnread = has("markunread1")
        hide = has("chathide1"); mention = has("chatmention1"); fmt = has("chatfmt1"); unfurl = has("unfurl1"); media = has("chatmedia1")
    }
    init(list: [String]) { let s = Set(list); self.init { s.contains($0) } }
    init() { self.init { _ in false } }
}

/// Neue Felder des Chat-Objekts (fehlen bei aelteren Servern: alles nil/false -> altes Verhalten)
struct ChatStdInfo: Equatable {
    // 8 (B)
    var permInfo = "all"
    var permSend = "all"
    var canSend: Bool?
    var canEditInfo: Bool?
    var canInfo2 = false
    var canPerms = false
    var canPin = false
    var canModerate = false
    var pins: [String] = []
    var descMsgID: String?
    var avatarMsgID: String?
    var blocked = false
    // 9 (A+C)
    var mutedUntil: Double?
    var notify = "all"
    var pinned = false
    var pinnedAt: Double = 0
    var unreadMark = false
    var hidden = false
    var canHide = false
    var mentionSeq = 0
    var meRef: String?
    init() {}
    init(_ d: [String: Any]) {
        permInfo = (d["perm_info"] as? String) == "admins" ? "admins" : "all"
        permSend = (d["perm_send"] as? String) == "admins" ? "admins" : "all"
        canSend = d["can_send"] as? Bool; canEditInfo = d["can_edit_info"] as? Bool
        canInfo2 = ShareJSON.bool(d["can_info2"]); canPerms = ShareJSON.bool(d["can_perms"]); canPin = ShareJSON.bool(d["can_pin"])
        canModerate = ShareJSON.bool(d["can_moderate"])
        pins = (d["pins"] as? [Any] ?? []).compactMap { ShareJSON.str($0) }.filter(ChatStd.isMsgID)
        descMsgID = ShareJSON.str((d["desc_msg"] as? [String: Any])?["id"]); avatarMsgID = ShareJSON.str((d["avatar_msg"] as? [String: Any])?["id"])
        blocked = ShareJSON.bool(d["blocked"])
        mutedUntil = ShareJSON.num(d["muted_until"])
        notify = ["all", "mentions", "none"].contains(d["notify"] as? String ?? "") ? d["notify"] as! String : "all"
        pinned = ShareJSON.bool(d["pinned"]); pinnedAt = ShareJSON.num(d["pinned_at"]) ?? 0
        unreadMark = ShareJSON.bool(d["unread_mark"]); hidden = ShareJSON.bool(d["hidden"]); canHide = ShareJSON.bool(d["can_hide"])
        mentionSeq = ShareJSON.int(d["mention_seq"]) ?? 0
        meRef = ShareJSON.str(d["me_ref"])
    }
}

/// Seiten-Chat (6a): woher hat ein Mitglied Zugriff (nur bei can_manage), Entfernen moeglich?
struct PageMemberInfo: Equatable {
    var via: String?
    var removable: Bool?
    init(_ m: [String: Any]) { via = ShareJSON.str(m["via"]); removable = m["removable"] as? Bool }
    init(via: String? = nil, removable: Bool? = nil) { self.via = via; self.removable = removable }
}

enum ChatStd {
    static func isMsgID(_ s: String) -> Bool { s.count >= 8 && s.count <= 64 && s.allSatisfy { $0.isHexDigit } }

    // MARK: 6a Seiten-Chat-Mitglieder

    /// "Mitglied hinzufuegen" anbieten: freie Gruppe wie bisher (Rechte), Seiten-Chat bei can_manage (pagechatmembers1)
    static func canAdd(kind: String, rights: GroupRights, canManage: Bool?, f: ChatStdFeatures) -> Bool {
        if kind == "free" { return rights.manage }
        return kind == "page" && f.pageMembers && canManage == true
    }
    enum PageRemove: Equatable { case none, remove, disabled(String) }
    /// "Aus Chat entfernen" im Seiten-Chat: nur removable, sonst ausgegraut mit Erklaerung (Texte wie Web)
    static func pageRemove(me: Bool, member: PageMemberInfo, canManage: Bool?, f: ChatStdFeatures) -> PageRemove {
        guard f.pageMembers, canManage == true, !me else { return .none }
        if member.removable == true { return .remove }
        switch member.via {
        case "group": return .disabled(String(localized: "Zugriff über eine Gruppe – dort entfernen."))
        case "parent": return .disabled(String(localized: "Zugriff über eine übergeordnete Seite – dort die Freigabe entziehen."))
        case "owner": return .disabled(String(localized: "Ihr gehört die Seite."))
        default: return .disabled(String(localized: "Zugriff nicht nur über diese Seite – im Teilen-Dialog prüfen."))
        }
    }
    /// Hinweis unter dem Kopf der Gruppeninfo
    static func membersHint(kind: String, canManage: Bool?, f: ChatStdFeatures) -> String? {
        switch kind {
        case "group": return String(localized: "Mitglieder ergeben sich aus der Benutzergruppe – dort verwalten.")
        case "page":
            guard f.pageMembers else { return String(localized: "Mitglieder ergeben sich hier aus der Gruppe bzw. den Freigaben der Seite.") }
            return canManage == true ? String(localized: "Personen hinzufügen teilt diese Seite mit ihnen. Entfernen entzieht die Freigabe.")
                : String(localized: "Mitglieder sind alle, die die Seite sehen dürfen – das legt fest, wem die Seite gehört.")
        default: return nil
        }
    }
    /// POST …/members im Seiten-Chat: mit Rolle (Standard view)
    static func pageAddBody(_ base: [String: Any], role: ShareRole) -> [String: Any] {
        var b = base; b["role"] = ShareRole.grantable.contains(role) ? role.rawValue : "view"; return b
    }

    // MARK: 8a/8b Gruppenbild, Beschreibung, Rechte

    static let descMax = 500
    static let avatarPx = 256
    /// Steuer-Nachricht kind 'desc' (Client kuerzt auf 500 Zeichen)
    static func descBody(_ text: String) -> [String: Any] {
        ["v": 1, "type": "desc", "desc": String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(descMax))]
    }
    /// Steuer-Nachricht kind 'avatar' (nil = Bild entfernt)
    static func avatarBody(_ f: ChatFileRef?) -> [String: Any] {
        var b: [String: Any] = ["v": 1, "type": "avatar", "file": NSNull()]
        if let f { var d = f.dict; d.removeValue(forKey: "th"); b["file"] = d; b["files"] = [d] }
        return b
    }
    /// Inhalt einer Steuer-Nachricht lesen (nach Entschluesseln + Signaturpruefung)
    enum Control: Equatable { case desc(String), avatar(ChatFileRef?) }
    static func control(_ plain: Data?) -> Control? {
        guard let p = plain, let j = ShareJSON.obj(p) else { return nil }
        switch j["type"] as? String {
        case "desc": return .desc(String((j["desc"] as? String ?? "").prefix(descMax)))
        case "avatar": return .avatar(ChatFileRef(j["file"]))
        default: return nil
        }
    }
    /// Bild/Beschreibung bearbeiten (can_info2; schreibgeschuetzt: nein)
    static func canEditInfo2(kind: String, info: ChatStdInfo, readonly: Bool, f: ChatStdFeatures) -> Bool {
        f.groupInfo2 && kind == "free" && info.canInfo2 && info.canEditInfo != false && !readonly
    }
    /// Abschnitt "Gruppenrechte" zeigen (Admins schalten; sonst nur lesend, wenn etwas eingeschraenkt ist)
    static func showPerms(kind: String, info: ChatStdInfo, f: ChatStdFeatures) -> Bool {
        f.groupInfo2 && kind == "free" && (info.canPerms || info.permInfo == "admins" || info.permSend == "admins")
    }
    /// Eingabefeld ersetzen? nil = schreiben erlaubt
    static func composerBlock(info: ChatStdInfo, f: ChatStdFeatures) -> String? {
        guard info.canSend == false, f.groupInfo2 || f.block else { return nil }
        return info.blocked ? String(localized: "Du hast diese Person gesperrt. Entsperren über das Chat-Menü (⋯).")
            : String(localized: "In dieser Gruppe dürfen nur Admins schreiben.")
    }
    /// Umbenennen: can_rename folgt can_edit_info (groupinfo2)
    static func canRename(base: Bool, info: ChatStdInfo, f: ChatStdFeatures) -> Bool {
        guard f.groupInfo2, let e = info.canEditInfo else { return base }
        return base && e
    }

    /// Systemzeile {"type":"perm","perm_info"?,"perm_send"?,"by"} (Stufe 3) - Text wie static/chat.js
    static func permText(permInfo: String?, permSend: String?, by: FedChatLogic.SysWho?, myHandle: String?) -> String {
        let meBy = by.map { !$0.handle.isEmpty && $0.handle == myHandle } ?? false
        let who: String = meBy ? String(localized: "Du") : by.map { x in
            let mine = myHandle.map { FedChatLogic.host(of: $0) } ?? ""
            return x.host.isEmpty || x.host == mine ? x.name : x.name + " (" + x.host + ")"
        } ?? String(localized: "Ein Admin")
        var parts: [String] = []
        if let s = permSend { parts.append(s == "admins" ? String(localized: "Nur Admins dürfen jetzt schreiben.") : String(localized: "Alle dürfen jetzt schreiben.")) }
        if let i = permInfo { parts.append(i == "admins" ? String(localized: "Nur Admins ändern jetzt Name, Bild und Beschreibung.") : String(localized: "Alle dürfen jetzt Name, Bild und Beschreibung ändern.")) }
        let head = String(localized: "\(who) hat die Gruppenrechte geändert.")
        return parts.isEmpty ? head : head + " " + parts.joined(separator: " ")
    }

    // MARK: 8c Grabstein, 8d Pins

    /// Grabstein (kind 'tomb', by_admin)
    static func isTomb(_ m: [String: Any]) -> Bool { (m["kind"] as? String) == "tomb" }
    static func tombText(sender: String?) -> String {
        if let s = sender, !s.isEmpty { return String(localized: "Nachricht von \(s) – von einem Admin gelöscht") }
        return String(localized: "Von einem Admin gelöscht")
    }
    /// "Loeschen (Admin)" auf fremden normalen Nachrichten
    static func canModDelete(mine: Bool, info: ChatStdInfo, f: ChatStdFeatures) -> Bool { f.modDel && info.canModerate && !mine }
    static let pinMax = 3
    /// Anheften/Loesen anbieten
    static func pinAction(id: String, info: ChatStdInfo, f: ChatStdFeatures, delivered: Bool) -> Bool? {
        guard f.msgPin, info.canPin, delivered else { return nil }
        return !info.pins.contains(id)                                   // true = anheften, false = loesen
    }
    /// Leiste oben: neueste zuerst durchblaettern (wie Web drawPins: Index i von hinten)
    static func pinShown(_ pins: [String], index: Int) -> (id: String, index: Int, count: Int)? {
        guard !pins.isEmpty else { return nil }
        let i = min(max(index, 0), pins.count - 1)
        return (pins[pins.count - 1 - i], i, pins.count)
    }

    // MARK: 8e Sperren, 8f Melden

    /// Kennung fuer POST /api/chat/blocks: Handle (nie der Anmeldename) - senderHandle, sonst members[].handle bzw. ref
    static func blockTarget(senderHandle: String?, member: (handle: String?, ref: String?)?) -> String? {
        if let h = senderHandle, !h.isEmpty { return h }
        if let h = member?.handle, !h.isEmpty { return h }
        if let r = member?.ref, r.hasPrefix("~") { return r }
        return nil
    }
    /// Nachricht einer gesperrten Person in Gruppen einklappen
    static func collapsed(kind: String, senderBlocked: Bool, mine: Bool, f: ChatStdFeatures) -> Bool {
        f.block && kind != "dm" && senderBlocked && !mine
    }
    /// Eintrag der Sperrliste (GET /api/chat/blocks) - nie ein Anmeldename (Name bzw. Server)
    struct BlockEntry: Equatable, Identifiable {
        var target: String; var name: String; var local: Bool; var kind: String
        var id: String { target }
        init?(_ v: Any?) {
            guard let d = v as? [String: Any], let t = ShareJSON.str(d["target"]) else { return nil }
            target = t; local = ShareJSON.bool(d["local"]); kind = ShareJSON.str(d["kind"]) ?? "person"
            name = ShareJSON.str(d["name"]) ?? ShareJSON.str(d["label"]) ?? (kind == "server" ? t : String(localized: "Unbekannt"))
        }
    }
    static func blocks(_ j: [String: Any]?) -> [BlockEntry] { (j?["blocks"] as? [Any] ?? []).compactMap(BlockEntry.init) }
    static func unblockPath(_ target: String) -> String {
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "~.-_")
        return "api/chat/blocks?target=" + (target.addingPercentEncoding(withAllowedCharacters: allowed) ?? target)
    }
    enum ReportReason: String, CaseIterable, Identifiable { case spam, abuse, illegal, other; var id: String { rawValue } }
    static let reportTextMax = 2000
    static let reportMsgsMax = 20
    /// vorgefuellter Text: "Name: Nachricht" je Zeile (hoechstens 2000 Zeichen)
    static func reportText(_ items: [(name: String, text: String)]) -> String {
        String(items.map { ($0.name.isEmpty ? "" : $0.name + ": ") + $0.text }.joined(separator: "\n").prefix(reportTextMax))
    }
    static func reportBody(chat: Int, msgs: [String], reason: ReportReason, text: String?) -> [String: Any] {
        ["chat": chat, "msgs": Array(msgs.prefix(reportMsgsMax)), "reason": reason.rawValue,
         "text": String((text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(reportTextMax))]
    }

    // MARK: 9.1/9.2 Stummschalten, Anheften, ungelesen, ausblenden

    enum MuteChoice: Equatable, CaseIterable { case hour, eight, week, forever, off }
    /// PATCH-Wert fuer muted_until
    static func mutedUntil(_ c: MuteChoice, now: Date) -> Double {
        let t = now.timeIntervalSince1970.rounded(.down)
        switch c {
        case .hour: return t + 3600
        case .eight: return t + 8 * 3600
        case .week: return t + 7 * 86_400
        case .forever: return -1
        case .off: return 0
        }
    }
    /// jetzt stumm? (muted_until: 0 aus, -1 immer, sonst Ende; ohne mute2: muted)
    static func isMuted(mutedUntil: Double?, muted: Bool, now: Date) -> Bool {
        guard let u = mutedUntil else { return muted }
        return u == -1 || u > now.timeIntervalSince1970
    }
    enum MuteState: Equatable { case off(notify: String), until(Date), forever }
    static func muteState(mutedUntil: Double?, muted: Bool, notify: String, now: Date) -> MuteState {
        guard isMuted(mutedUntil: mutedUntil, muted: muted, now: now) else { return .off(notify: notify) }
        if let u = mutedUntil, u > 0 { return .until(Date(timeIntervalSince1970: u)) }
        return .forever
    }
    /// Beschriftung (wie Web muteLabel): "Stumm bis Mo., 03.11., 14:00" / "Stummgeschaltet" / "Nur Erwähnungen" / …
    static func muteLabel(_ s: MuteState, format: (Date) -> String) -> String {
        switch s {
        case .forever: return String(localized: "Stummgeschaltet")
        case .until(let d): return String(localized: "Stumm bis \(format(d))")
        case .off(let n):
            return n == "mentions" ? String(localized: "Nur Erwähnungen") : n == "none" ? String(localized: "Mitteilungen aus") : String(localized: "Stummschalten")
        }
    }
    static let chatPinMax = 5
    /// Liste: angeheftete oben (pinned_at aufsteigend wie der Server), Rest in Server-Reihenfolge
    static func sortPinned<T>(_ list: [T], pinned: (T) -> Bool, at: (T) -> Double) -> [T] {
        let p = list.enumerated().filter { pinned($0.element) }.sorted { at($0.element) == at($1.element) ? $0.offset < $1.offset : at($0.element) < at($1.element) }
        return p.map(\.element) + list.filter { !pinned($0) }
    }
    /// "Als ungelesen markieren" nur, wenn nichts ungelesen ist
    static func offerMarkUnread(unread: Int, mark: Bool, f: ChatStdFeatures) -> Bool { f.markUnread && unread == 0 && !mark }
    /// Punkt in der Liste: markiert und (der Server zaehlt dann mindestens 1)
    static func showUnreadDot(unread: Int, mark: Bool) -> Bool { mark }

    // MARK: 9.3 Erwaehnungen

    static let mentionsMax = 50
    /// Kennung eines Mitglieds im Token: handle (Stufe 3/foederiert) bzw. ref (v1); nie der Anmeldename
    static func mentionRef(handle: String?, ref: String?) -> String? {
        if let h = handle, !h.isEmpty { return h }
        if let r = ref, r.hasPrefix("~") { return r }
        return nil
    }
    /// Auswahl bei "@": Mitglieder (ohne mich) mit Kennung, Namensteil passend, hoechstens 6
    static func mentionCandidates<M: GroupMemberLike>(_ ms: [M], query: String) -> [(member: M, ref: String)] {
        let q = query.lowercased()
        return ms.compactMap { m -> (M, String)? in
            guard !m.me, let r = mentionRef(handle: m.handle, ref: m.ref), q.isEmpty || m.name.lowercased().contains(q) else { return nil }
            return (m, r)
        }.prefix(6).map { (member: $0.0, ref: $0.1) }
    }
    /// Label "@Name" -> Token; laengste Labels zuerst (wie applyPeople); refs fuer mentions (ohne Doppelte, hoechstens 50)
    static func applyMentions(_ text: String, people: [String: String]) -> (text: String, refs: [String]) {
        var t = text, refs: [String] = []
        for label in people.keys.sorted(by: { $0.count > $1.count }) where t.contains(label) {
            guard let r = people[label] else { continue }
            t = t.components(separatedBy: label).joined(separator: "<@" + r + ">")
            if !refs.contains(r) { refs.append(r) }
        }
        return (t, Array(refs.prefix(mentionsMax)))
    }
    /// "@Name" fuer die Eingabe (Leerraum zusammengefasst, hoechstens 60 Zeichen)
    static func mentionLabel(_ name: String) -> String {
        "@" + String(name.split(whereSeparator: \.isWhitespace).joined(separator: " ").prefix(60))
    }
    /// "@teil" am Ende durch "@Name " ersetzen (wie pickApply)
    static func completeMention(_ text: String, label: String) -> String {
        guard let q = AIChatLogic.mentionQuery(text) else { return text + label + " " }
        return String(text.dropLast(q.count + 1)) + label + " "
    }
    /// Anzeigename zu einer Kennung (ich: eigener Name); nil = unbekannt
    static func mentionName<M: GroupMemberLike>(_ r: String, members: [M], meRef: String?, myHandle: String?, myName: String) -> String? {
        if r == meRef || r == myHandle { return myName }
        return members.first { $0.handle == r || $0.ref == r || ($0.handle.map { $0.split(separator: "@").first.map(String.init) == r } ?? false) }?.name
    }
    static func mentionsMe(_ text: String, meRef: String?, myHandle: String?) -> Bool {
        if let r = meRef, text.contains("<@" + r + ">") { return true }
        if let h = myHandle, text.contains("<@" + h + ">") { return true }
        return false
    }
    /// Tokens fuer Vorschauen/Kopieren durch "@Name" ersetzen
    static func plainMentions(_ text: String, name: (String) -> String?) -> String {
        guard let re = try? NSRegularExpression(pattern: #"<@(~[a-z2-7]{16}(?:@[a-z0-9.:\-]{1,260})?)>"#) else { return text }
        let s = text as NSString
        var out = "", last = 0
        for m in re.matches(in: text, range: NSRange(location: 0, length: s.length)) {
            out += s.substring(with: NSRange(location: last, length: m.range.location - last))
            out += "@" + (name(s.substring(with: m.range(at: 1))) ?? String(localized: "Unbekannt"))
            last = m.range.location + m.range.length
        }
        return out + s.substring(from: last)
    }

    // MARK: 9.5 Linkvorschau

    /// erste http(s)-Adresse im Text (wie firstUrl)
    static func firstURL(_ text: String) -> String? {
        for p in ChatFmt.leafPieces(text) { if case .url(let u) = p { return u } }
        return nil
    }
    struct UnfurlImage: Equatable { var mime: String; var w: Int; var h: Int; var data: Data }
    /// Antwort von GET /api/chat/unfurl: nil = nichts Brauchbares (ohne Titel und Beschreibung)
    struct Unfurl: Equatable {
        var url: String; var title: String; var desc: String; var site: String; var image: UnfurlImage?
        init?(_ j: [String: Any]?) {
            guard let j, let u = ShareJSON.str(j["url"]), u.hasPrefix("http://") || u.hasPrefix("https://") else { return nil }
            url = String(u.prefix(2000)); title = String((j["title"] as? String ?? "").prefix(200)); desc = String((j["desc"] as? String ?? "").prefix(300))
            site = String((j["site"] as? String ?? "").prefix(80))
            guard !title.isEmpty || !desc.isEmpty else { return nil }
            if let im = j["image"] as? [String: Any], let b = ShareJSON.str(im["b64"]), let d = Data(base64Encoded: b) {
                image = UnfurlImage(mime: "image/jpeg", w: ShareJSON.int(im["w"]) ?? 0, h: ShareJSON.int(im["h"]) ?? 0, data: d)
            }
        }
    }
    static func unfurlPath(_ url: String) -> String {
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "-._~")
        return "api/chat/unfurl?url=" + (url.addingPercentEncoding(withAllowedCharacters: allowed) ?? url)
    }
    /// Vorschau im verschluesselten Inhalt: {url, title, desc, site, img?: {id, k, m, w, h, s}}
    struct Preview: Equatable {
        var url: String; var title: String; var desc: String; var site: String
        var img: ChatThumbRef?
        init(url: String, title: String, desc: String, site: String, img: ChatThumbRef? = nil) {
            self.url = url; self.title = title; self.desc = desc; self.site = site; self.img = img
        }
        init(_ u: Unfurl, img: ChatThumbRef?) { self.init(url: u.url, title: u.title, desc: u.desc, site: u.site, img: img) }
        /// aus dem Inhalt (nur http/https - sonst keine Karte)
        init?(_ v: Any?) {
            guard let d = v as? [String: Any], let u = ShareJSON.str(d["url"]), u.hasPrefix("http://") || u.hasPrefix("https://") else { return nil }
            url = String(u.prefix(2000)); title = String((d["title"] as? String ?? "").prefix(200)); desc = String((d["desc"] as? String ?? "").prefix(300))
            site = String((d["site"] as? String ?? "").prefix(80)); img = ChatThumbRef(d["img"])
        }
        var dict: [String: Any] {
            var d: [String: Any] = ["url": url, "title": title, "desc": desc, "site": site]
            if let img { d["img"] = img.dict }
            return d
        }
    }
    /// Datei-IDs der Sende-Anfrage: Anhaenge (+ Vorschaubilder) und das Bild der Linkvorschau
    static func fileIDs(_ files: [ChatFileRef], preview: Preview?) -> [String] {
        var a = files.flatMap { [$0.id] + ($0.thumb.map { [$0.id] } ?? []) }
        if let i = preview?.img?.id { a.append(i) }
        return a
    }

    // MARK: 9.6 Medienuebersicht

    static func mediaPath(_ chatID: Int, device: String, before: Int?) -> String {
        "api/chats/\(chatID)/messages?\(device)&has=file&limit=100" + (before.map { "&before=\($0)" } ?? "")
    }
    enum MediaKind: Equatable { case media, file, skip }
    static func mediaKind(_ f: ChatFileRef) -> MediaKind {
        if f.voice { return .skip }
        return f.isImage || f.isVideo ? .media : .file
    }

    // MARK: 9.7 Entwuerfe (nur auf dem Geraet)

    static let draftMax = 20_000
    static func draftKey(account: String, chatID: Int) -> String { "saga-chat-draft:\(account):\(chatID)" }
    static let draftPrefix = "saga-chat-draft:"
    /// Entwurf mit den gewaehlten Erwaehnungen (Label -> Kennung)
    struct Draft: Equatable, Codable { var t: String; var p: [String: String]; var at: Double }
    static func draft(_ text: String, people: [String: String], now: Date) -> Draft? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        return Draft(t: String(text.prefix(draftMax)), p: people.filter { text.contains($0.key) }, at: now.timeIntervalSince1970)
    }
    /// Schluessel anderer Konten (Kontowechsel/Abmelden): verwerfen
    static func foreignDraftKeys(_ keys: [String], account: String?) -> [String] {
        keys.filter { $0.hasPrefix(draftPrefix) && (account == nil || !$0.hasPrefix(draftPrefix + account! + ":")) }
    }
}
