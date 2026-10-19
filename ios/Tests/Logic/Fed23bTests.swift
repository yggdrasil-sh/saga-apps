import Foundation

// Foederation 2.3, Teil 2 (E4-E6): Faehigkeiten fremder Seiten (remote.caps), Rechte je Faehigkeit und Rolle, Versionen/
// Verschieben ab Rolle Bearbeiten (Server ab 2.3), Kurzsuche je Serverversion, Systemzeilen der Gruppen (members/fed),
// Einladung, Gruppenname v2, alte v1-Nachrichten umgestellter Seiten-Chats, Schluessel in Teilen, Geraete-IDs eindeutig.
func fed23bTests() {
    // remote.caps
    let all = ["db", "subpages", "upload", "versions", "export", "presence", "live", "chatgroups"]
    let full = PageAccess(page: ["id": -2049, "role": "edit", "remote": ["host": "b.example", "peer": 1, "caps": all]])
    check(full.remote && full.remoteCaps.db && full.remoteCaps.upload && full.remoteCaps.presence && full.remoteCaps.chatgroups, "caps gelesen")
    check(full.allows(.addSubpage) && full.allows(.export) && full.allows(.versions) && full.allows(.wide) && full.allows(.move)
          && full.allows(.editContent) && full.remoteExtras && full.canUpload && full.presence, "fremde Seite 2.3 (Bearbeiten): alles, was der Server kann")
    check(!full.allows(.share) && !full.allows(.delete) && !full.allows(.publish) && !full.allows(.archive) && !full.allows(.duplicate)
          && !full.allows(.template) && !full.allows(.lock) && !full.allows(.favorite), "fremde Seite: Eigentuemer-Aktionen nie")
    let view = PageAccess(page: ["id": -2049, "role": "view", "remote": ["host": "b.example", "caps": all]])
    check(!view.allows(.addSubpage) && !view.allows(.versions) && !view.allows(.wide) && !view.allows(.move) && view.allows(.export)
          && view.readOnly, "fremde Seite (Lesen): nur Export")
    let some = PageAccess(page: ["id": -2049, "role": "edit", "remote": ["host": "b.example", "caps": ["export", "live"]]])
    check(!some.allows(.addSubpage) && some.allows(.export) && !some.allows(.versions) && !some.allows(.wide) && !some.remoteExtras
          && !some.canUpload && some.presence, "fremde Seite: nur gemeldete Faehigkeiten (live reicht fuer Anwesenheit)")
    let old = PageAccess(page: ["id": -2049, "role": "edit", "remote": ["host": "b.example"]])
    check(!old.allows(.addSubpage) && !old.allows(.export) && !old.allows(.versions) && !old.allows(.move) && old.allows(.editContent)
          && !old.remoteExtras && !old.canUpload && !old.presence, "Server 2.1/2.2 (ohne caps): Lesen, Kommentieren, Titel/Inhalt")
    let rr = RemoteRef(["host": "b.example", "peer": 3, "caps": ["db", 5, "upload"]])
    check(rr?.caps == ["db", "upload"] && FedCaps(rr?.caps).db && !FedCaps(rr?.caps).versions, "RemoteRef: caps (nur Texte)")
    let pg = try? JSONDecoder().decode(Page.self, from: Data(#"{"id":-4097,"title":"x","remote":{"host":"b.example","peer":1,"caps":["db"]}}"#.utf8))
    check(pg?.remote?.caps == ["db"], "Page (Mit mir geteilt): caps dekodiert")
    let pg2 = try? JSONDecoder().decode(Page.self, from: Data(#"{"id":-4097,"title":"x","remote":{"host":"b.example","peer":1}}"#.utf8))
    check(pg2 != nil && pg2?.remote?.caps == nil, "Page ohne caps (alter Server) dekodiert")

    // Versionen/Verschieben ab Rolle Bearbeiten nur mit Server ab 2.3; lokale Eigentuemer-Seiten unveraendert
    let ed23 = PageAccess(page: ["id": 5, "role": "edit"], serverVersion: "2.3.0")
    let ed22 = PageAccess(page: ["id": 5, "role": "edit"], serverVersion: "2.2.4")
    check(ed23.allows(.versions) && ed23.allows(.move) && !ed22.allows(.versions) && !ed22.allows(.move)
          && !PageAccess(page: ["id": 5, "role": "comment"], serverVersion: "2.3.0").allows(.versions), "Versionen/Verschieben ab Bearbeiten (2.3)")
    check(PageAccess(page: ["id": 5], serverVersion: "2.2.0").allows(.versions) && PageAccess(page: ["id": 5]).allows(.move), "Eigentuemer wie bisher")
    check(!PageAccess(page: ["id": 5, "role": "edit"], serverVersion: "2.3.0").allows(.delete), "Bearbeiten: Loeschen bleibt beim Eigentuemer")

    // Kurzsuche: ab 1 Zeichen nur bei Servern ab 2.3, sonst ab 3 mit Hinweis
    check(ShareLogic.minQuery(server: "2.3.0") == 1 && ShareLogic.minQuery(server: "2.4.1") == 1 && ShareLogic.minQuery(server: "2.3.0-beta") == 1
          && ShareLogic.minQuery(server: "2.2.9") == 3 && ShareLogic.minQuery(server: nil) == 3, "minQuery je Serverversion")
    check(ShareLogic.searchReady("h", server: "2.3.0") && !ShareLogic.searchReady("hd", server: "2.2.0") && ShareLogic.searchReady("hdx", server: "2.2.0")
          && !ShareLogic.searchReady(" ", server: "2.3.0") && FedChatLogic.searchReady("h", server: "2.3.0"), "searchReady")
    check(ShareLogic.minHint("hd", server: "2.2.0") && !ShareLogic.minHint("hd", server: "2.3.0") && !ShareLogic.minHint("", server: "2.2.0")
          && !ShareLogic.minHint("hdx", server: "2.2.0"), "Hinweis 'Mindestens 3 Zeichen.' nur vor 2.3")
    check(ChatFedHint.show("bert@b.example", hits: 0) && !ChatFedHint.show("bert@b.example", hits: 1) && !ChatFedHint.show("be", hits: 0), "Adress-Hinweis")
    check(ShareLogic.versionLess("2.2.9", "2.3.0") && !ShareLogic.versionLess("2.3.0", "2.3.0") && ShareLogic.versionLess("1.2.0", "1.10.0"), "versionLess")

    // Systemzeilen (E5)
    let me = "~abcdefghijklmnop@a.example"
    func sys(_ j: [String: Any]) -> FedChatLogic.SysLine {
        FedChatLogic.sysLine(String(data: try! JSONSerialization.data(withJSONObject: j), encoding: .utf8), myHandle: me)
    }
    let anna = ["name": "Anna", "host": "a.example", "handle": me], bert = ["name": "Bert", "host": "b.example", "handle": "~qrstuvwxyz234567@b.example"]
    let dora = ["name": "Dora", "host": "c.example", "handle": "~ddddddddddddddd2@c.example"]
    let add = sys(["type": "members", "added": [bert, dora], "removed": [], "by": anna])
    guard case .members(let ml) = add else { check(false, "members geparst"); return }
    check(ml.added.count == 2 && ml.added[0].host == "b.example" && ml.by?.handle == me && ml.removed.isEmpty, "members geparst")
    check(FedChatLogic.sysText(add, myHandle: me) == "Du hast Bert (b.example), Dora (c.example) hinzugefügt.", "Text: ich habe hinzugefuegt")
    check(FedChatLogic.sysText(sys(["type": "members", "added": [anna], "by": bert]), myHandle: me) == "Bert (b.example) hat dich hinzugefügt.",
          "Text: hat dich hinzugefuegt")
    check(FedChatLogic.sysText(sys(["type": "members", "added": [dora], "by": NSNull()]), myHandle: me) == "Dora (c.example) ist jetzt dabei.", "Text: ohne by")
    check(FedChatLogic.sysText(sys(["type": "members", "removed": [bert], "by": bert]), myHandle: me) == "Bert (b.example) hat die Gruppe verlassen.",
          "Text: verlassen (by = removed[0])")
    check(FedChatLogic.sysText(sys(["type": "members", "removed": [anna], "by": anna]), myHandle: me) == "Du hast die Gruppe verlassen.", "Text: ich verlasse")
    check(FedChatLogic.sysText(sys(["type": "members", "removed": [bert], "by": anna]), myHandle: me) == "Du hast Bert (b.example) entfernt.", "Text: entfernt")
    check(FedChatLogic.sysText(sys(["type": "members", "removed": [dora], "by": NSNull(), "reason": "disconnected"]), myHandle: me)
          == "Dora (c.example) ist nicht mehr dabei – die Verbindung zu diesem Saga-Server wurde getrennt.", "Text: getrennt")
    check(FedChatLogic.sysText(sys(["type": "members", "removed": [dora], "reason": "gone"]), myHandle: me) == "Dora (c.example) ist nicht mehr dabei (Konto gelöscht).",
          "Text: Konto geloescht")
    check(FedChatLogic.sysText(sys(["type": "members", "removed": [dora], "reason": "access"]), myHandle: me) == "Dora (c.example) ist nicht mehr dabei.",
          "Text: Zugriff entzogen")
    check(FedChatLogic.sysText(sys(["type": "members", "added": [["name": "Carl", "host": "a.example", "handle": "~cccccccccccccccc@a.example"]], "by": bert]), myHandle: me)
          == "Bert (b.example) hat Carl hinzugefügt.", "Text: eigener Server ohne Herkunft")
    check(sys(["type": "fed", "on": true]) == .fed(aiOff: false) && sys(["type": "fed", "on": true, "ai_off": true]) == .fed(aiOff: true), "fed geparst")
    check(FedChatLogic.sysText(.fed(aiOff: true), myHandle: me) == "Ab jetzt mit Personen anderer Saga-Server (Ende-zu-Ende verschlüsselt). Die KI ist hier jetzt aus.",
          "Text: fed")
    check(FedChatLogic.sysText(.ai(on: true, byMe: true, name: "x"), myHandle: me) == "Du hast die KI in diesem Chat eingeschaltet."
          && FedChatLogic.sysText(.ai(on: false, byMe: false, name: "Bert"), myHandle: me) == "Bert hat die KI in diesem Chat ausgeschaltet.", "Text: KI")
    check(FedChatLogic.sysText(sys(["type": "members"]), myHandle: me) == "Hinweis", "leere Mitgliederzeile")
    check(FedChatLogic.SysWho(["name": "Eva", "host": "a.example", "handle": "~eeeeeeeeeeeeeeee@e.example"])?.host == "e.example"
          && FedChatLogic.SysWho(["name": "Eva", "host": "e.example"])?.host == "e.example", "Herkunft aus dem Handle (Feld host nur ohne Handle)")

    // Einladung zu einer Gruppe eines anderen Servers
    let grp = FedChatInfo(["id": 9, "kind": "free", "v": 2, "ref": "00112233445566778899aabbccddeeff@b.example", "home": "b.example", "home_self": false,
                           "my_handle": me, "request": true, "invited_by": ["name": "Bert", "host": "b.example", "label": "Bert · b.example"],
                           "members": [["username": "@~qrstuvwxyz234567@b.example", "handle": "~qrstuvwxyz234567@b.example", "name": "Bert", "host": "b.example", "remote": true],
                                       ["username": "anna", "handle": me, "name": "Anna", "me": true]]])
    check(grp?.request == true && grp?.peer == nil && grp?.invitedBy?.host == "b.example" && grp?.requester.name == "Bert"
          && grp?.handles.count == 2, "Gruppen-Anfrage: einladende Person")
    let noInv = FedChatInfo(["v": 2, "ref": "00112233445566778899aabbccddeeff@b.example", "request": true])
    check(noInv?.requester.host == "b.example" && noInv?.requester.name == "?", "Anfrage ohne invited_by: Heim")

    // Gruppenname v2, alte v1-Nachrichten, Schluessel in Teilen, Geraete-IDs
    check(FedChatLogic.isLegacyV1(["id": "x", "kind": "msg", "sender": ["username": "anna"]]) && FedChatLogic.isLegacyV1(["kind": "title", "sender_handle": NSNull()])
          && !FedChatLogic.isLegacyV1(["kind": "msg", "sender_handle": me]) && !FedChatLogic.isLegacyV1(["kind": "sys"]), "isLegacyV1")
    let many = (0..<45).map { String(format: "~%016d@b.example", $0) }
    let paths = FedChatLogic.keysPaths(many + many.prefix(3), fresh: true)
    check(paths.count == 3 && paths.allSatisfy { $0.hasSuffix("&fresh=1") } && paths[0].components(separatedBy: ",").count == 20
          && paths[2].components(separatedBy: ",").count == 5 && FedChatLogic.keysPaths([], fresh: false).isEmpty, "Schluessel in Teilen zu je 20")
    check(FedChatLogic.uniqueDeviceIDs([["id": "a"], ["id": "b"]]) && !FedChatLogic.uniqueDeviceIDs([["id": "a"], ["id": "a"]])
          && FedChatLogic.uniqueDeviceIDs(nil) && FedChatLogic.uniqueDeviceIDs([["x": 1], ["x": 2]]), "Geraete-IDs eindeutig")

    // Verschieben im geteilten Bereich: gleicher Eigentuemer/Server, bearbeitbar, keine Datenbank/Zeile, nicht in sich selbst
    func P(_ id: Int, _ parent: Int?, kind: String = "page", role: String = "edit", peer: Int? = 1) -> Page {
        var p = Page.created(id: id, parent: parent, title: "p\(id)", kind: kind, updated: 0)
        p.role = role; if let peer { p.remote = RemoteRef(peer: peer, host: "b.example") }
        return p
    }
    let shared = [P(-1025, nil), P(-2049, -1025), P(-3073, -2049), P(-4097, -1025, kind: "database"), P(-5121, -4097), P(-6145, -1025, role: "view"),
                  P(-7169, nil, peer: 2), P(-8193, -1025)]
    let t = Set(FedLogic.sharedMoveTargets(-2049, in: shared).map(\.id))
    check(t == [-8193], "Verschiebe-Ziele: \(t.sorted())")
    check(FedLogic.sharedMoveTargets(-99, in: shared).isEmpty, "unbekannte Seite: keine Ziele")
}
