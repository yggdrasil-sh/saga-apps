import Foundation

// Chats archivieren und loeschen (2.1, App/ChatLogic.swift ChatDeleteLogic, App/ChatMediaLogic.swift ChatCacheIndex)
func chatDeleteTests() {
    typealias L = ChatDeleteLogic
    // delete_for_all lesen
    check(L.forAll("chat") == .chat && L.forAll("history") == .history, "delete_for_all chat/history")
    check(L.forAll(NSNull()) == nil && L.forAll(nil) == nil && L.forAll("x") == nil && L.forAll(1) == nil, "delete_for_all null/unbekannt")
    // aeltere Server: Felder fehlen -> ausblenden
    check(L.supported(["archived": false, "delete_for_all": NSNull()]), "neuer Server (null zaehlt)")
    check(!L.supported(["id": 1, "muted": false]), "aelterer Server ohne Felder")
    check(!L.supported(["archived": true]), "nur ein Feld reicht nicht")
    // Adressen
    check(L.path(12, .me) == "api/chats/12?scope=me" && L.path(12, .all) == "api/chats/12?scope=all", "DELETE-Adresse")
    check((L.archiveBody(true)["archived"] as? Bool) == true && (L.archiveBody(false)["archived"] as? Bool) == false, "PATCH-Koerper")
    // Begruendung, wenn "Fuer alle" nicht geht
    check(L.denied(kind: "free", forAll: nil) == .groupAdmin, "freie Gruppe: nur Admins")
    check(L.denied(kind: "group", forAll: nil) == .sagaAdmin, "Gruppen-Chat: Saga-Admins")
    check(L.denied(kind: "page", forAll: nil) == .pageOwner, "Seiten-Chat: Eigentuemer")
    check(L.denied(kind: "dm", forAll: nil) == .other, "Direkt-Chat ohne Recht (sollte nicht vorkommen)")
    check(L.denied(kind: "free", forAll: .chat) == .none && L.denied(kind: "group", forAll: .history) == .none, "erlaubt -> keine Begruendung")
    // Wirkung
    check(L.effect(kind: "dm", scope: .me, forAll: .chat) == .hidden, "DM nur fuer mich: ausgeblendet")
    check(L.effect(kind: "free", scope: .me, forAll: nil) == .archived && L.effect(kind: "page", scope: .me, forAll: nil) == .archived, "andere: Archiv")
    check(L.effect(kind: "dm", scope: .all, forAll: .chat) == .removed, "DM fuer alle: weg")
    check(L.effect(kind: "group", scope: .all, forAll: .history) == .historyCleared, "Gruppen-Chat fuer alle: Verlauf")
    check(L.closes(.hidden) && L.closes(.removed) && !L.closes(.archived) && !L.closes(.historyCleared), "schliessen")
    // Gruppe verlassen nur bei freier Gruppe und "nur fuer mich"
    check(L.offerLeave(kind: "free", scope: .me), "freie Gruppe: verlassen anbieten")
    check(!L.offerLeave(kind: "free", scope: .all) && !L.offerLeave(kind: "group", scope: .me) && !L.offerLeave(kind: "dm", scope: .me), "sonst nicht")
    // Liste teilen, Zaehler
    struct C { var id: Int; var a: Bool; var m: Bool; var u: Int }
    let list = [C(id: 1, a: false, m: false, u: 2), C(id: 2, a: true, m: false, u: 5), C(id: 3, a: false, m: true, u: 4), C(id: 4, a: true, m: true, u: 9), C(id: 5, a: false, m: false, u: 0)]
    let s = L.split(list) { $0.a }
    check(s.active.map(\.id) == [1, 3, 5] && s.archived.map(\.id) == [2, 4], "aktiv/archiviert, Reihenfolge bleibt")
    let n = L.unread(list, archived: { $0.a }, muted: { $0.m }, unread: { $0.u })
    check(n.main == 2 && n.archive == 5, "Ungelesen ohne Archiv und ohne stumm; Archiv-Zaehler")
    // Live-Ereignisse
    let rm = L.live("chat.removed", ["chat_id": 7, "kind": "dm", "by": ["username": "bob", "name": "Bob B."]], me: "alice", kind: "dm")
    check(rm == L.LiveAction(chatID: 7, purgeUpTo: nil, close: true, removeFromList: true, notice: .chatDeleted("Bob B.")), "chat.removed von anderen")
    let rmMe = L.live("chat.removed", ["chat_id": 7, "kind": "free", "by": ["username": "alice", "name": "Alice"]], me: "alice", kind: nil)
    check(rmMe?.notice == nil && rmMe?.close == true, "chat.removed von mir: kein Hinweis")
    let noName = L.live("chat.removed", ["chat_id": 7, "by": ["username": "bob", "name": ""]], me: "alice", kind: nil)
    check(noName?.notice == .chatDeleted("bob"), "ohne Namen: Benutzername")
    let ca = L.live("chat.cleared", ["chat_id": 3, "scope": "all", "by": ["username": "admin", "name": "Admin"]], me: "alice", kind: "group")
    check(ca == L.LiveAction(chatID: 3, purgeUpTo: nil, close: false, removeFromList: false, notice: .historyDeleted("Admin")), "chat.cleared fuer alle")
    let cm = L.live("chat.cleared", ["chat_id": 3, "scope": "me", "cleared_seq": 812], me: "alice", kind: "free")
    check(cm == L.LiveAction(chatID: 3, purgeUpTo: 812, close: false, removeFromList: false, notice: nil), "chat.cleared nur fuer mich (Gruppe)")
    let cdm = L.live("chat.cleared", ["chat_id": 4, "scope": "me", "cleared_seq": 9], me: "alice", kind: "dm")
    check(cdm?.close == true && cdm?.removeFromList == true && cdm?.purgeUpTo == 9, "chat.cleared nur fuer mich (DM schliessen)")
    check(L.live("chat.changed", ["chat_id": 3, "archived": true], me: "alice", kind: nil) == nil, "Archiv: nur Liste neu laden")
    check(L.live("chat.removed", ["by": ["username": "bob"]], me: "alice", kind: nil) == nil, "ohne chat_id")
    // Nachrichten nach dem Loeschen
    check(!L.keep(seq: 5, isTitle: false, upTo: 5) && L.keep(seq: 6, isTitle: false, upTo: 5), "bis einschliesslich cleared_seq")
    check(!L.keep(seq: 99, isTitle: false, upTo: nil), "alles weg")
    check(L.keep(seq: 1, isTitle: true, upTo: 5) && L.keep(seq: 1, isTitle: true, upTo: nil), "Name der freien Gruppe bleibt")
    check(L.showCleared(clearedSeq: 3, moreOlder: false) && !L.showCleared(clearedSeq: 3, moreOlder: true) && !L.showCleared(clearedSeq: 0, moreOlder: false), "Hinweis \"Verlauf geloescht\"")

    // Zuordnung Datei -> Chat fuer den Zwischenspeicher
    var ix = ChatCacheIndex()
    check(ix.add(1, ["aa", "bb"]) && !ix.add(1, ["aa"]) && ix.add(2, ["cc"]), "merken, keine Doppelten")
    check(ix.add(1, ["", "dd"]) && ix.chats["1"] == ["aa", "bb", "dd"], "leere Kennung ignoriert")
    check(ix.take(1) == ["aa", "bb", "dd"] && ix.chats["1"] == nil && ix.chats["2"] == ["cc"], "herausnehmen, anderer Chat bleibt")
    check(ix.take(9).isEmpty, "unbekannter Chat")
    check(ChatCacheIndex.diskNames(["ab", "../x"]) == ["ab", "ab.t"], "Dateinamen: Blob + eigenes Vorschaubild, keine Pfadteile")
    var big = ChatCacheIndex()
    big.add(5, (0..<(ChatCacheIndex.perChat + 10)).map { "f\($0)" })
    check(big.chats["5"]?.count == ChatCacheIndex.perChat && big.chats["5"]?.first == "f10", "Grenze je Chat (aelteste fallen heraus)")
    let rt = try? JSONDecoder().decode(ChatCacheIndex.self, from: (try? JSONEncoder().encode(ix)) ?? Data())
    check(rt == ix, "JSON hin und zurueck")
}
