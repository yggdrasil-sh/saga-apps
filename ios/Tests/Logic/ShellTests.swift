import Foundation

// Desktop-Huelle P2 (Shell/ShellLogic.swift): Deep-Links wie static/host.js, Origin-Pruefung, /login-Erkennung,
// openExternal-Regeln, Dock-Zahl, App-Schluessel, Faehigkeiten-Skript
func shellTests() {
    func r(_ s: String) -> ShellLogic.Link { ShellLogic.route(URL(string: s)!) }
    check(r("saga://today") == .path("/") && r("saga://") == .path("/") && r("saga-test://today") == .path("/"), "Heute")
    check(r("saga://page/42") == .path("/notes#42") && r("hlidskjalf://page/7/") == .path("/notes#7"), "Seite")
    check(r("saga://page/abc") == .none && r("saga://page/1234567890123") == .none, "Seite ohne gueltige ID")
    check(r("saga://tasks") == .path("/notes?v=tasks") && r("saga://events") == .path("/notes?v=events"), "Aufgaben/Termine")
    check(r("saga://new-task?src=3") == .path("/notes?v=tasks&new=task") && r("saga://new-page") == .path("/notes?new=blank"), "Neu")
    check(r("saga://new?db=5&view=t&k=x") == .create(.init(db: 5, view: "t", parent: nil, key: "x"))
          && r("saga://new?parent=9") == .create(.init(db: nil, view: nil, parent: 9, key: nil))
          && r("saga://new?db=x") == .path("/notes?new=blank") && r("saga://new?db=5&view=a%22b") == .create(.init(db: 5, view: nil, parent: nil, key: nil)),
          "Neu in Datenbank/Seite (nativ)")
    let ne = ShellLogic.NewEntry(db: 5, view: nil, parent: nil, key: "geheim-geheim-123")
    check(ne.trusted(secret: "geheim-geheim-123") && !ne.trusted(secret: "anders") && !ne.trusted(secret: "")
          && !ShellLogic.NewEntry(db: nil, view: nil, parent: 3, key: "s").trusted(secret: "s") && ne.fallbackPath == "/notes#5", "Neu: Geheimnis")
    check(r("saga://settings/security") == .path("/notes?settings=security") && r("saga://settings") == .path("/notes?settings=account")
          && r("saga://settings/evil") == .path("/notes?settings=account"), "Einstellungen")
    check(r("saga://chats") == .path("/chats"), "Chats")
    check(r("saga://auth?code=abc") == .auth(code: "abc") && r("saga://auth") == .auth(code: nil), "Anmeldung")
    if case .pair = r("saga://pair?u=x&c=y") { check(true, "Kopplung") } else { check(false, "Kopplung") }
    check(r("https://example.org/page/1") == .none && r("saga://unbekannt") == .none && r("file:///etc") == .none, "fremd/unbekannt")

    let srv = URL(string: "https://saga.example.org")!, srvP = URL(string: "http://127.0.0.1:8099")!
    check(ShellLogic.isOwn(URL(string: "https://saga.example.org/notes#3"), server: srv), "eigene Origin")
    check(ShellLogic.isOwn(URL(string: "https://SAGA.example.org:443/x"), server: srv), "Standard-Port und Grossschreibung")
    check(!ShellLogic.isOwn(URL(string: "http://saga.example.org/x"), server: srv), "anderes Schema")
    check(!ShellLogic.isOwn(URL(string: "https://saga.example.org:8443/x"), server: srv), "anderer Port")
    check(!ShellLogic.isOwn(URL(string: "https://saga.example.org.evil.test/x"), server: srv), "aehnlicher Host")
    check(ShellLogic.isOwn(URL(string: "http://127.0.0.1:8099/"), server: srvP) && !ShellLogic.isOwn(URL(string: "http://127.0.0.1/"), server: srvP), "Port")
    check(ShellLogic.origin(scheme: "https", host: "saga.example.org", port: nil) == ShellLogic.origin(srv), "Frame-Origin ohne Port")
    check(!ShellLogic.isOwn(nil, server: srv), "keine Adresse")

    func ln(_ s: String) -> ShellLogic.LoginNav { ShellLogic.loginNav(URL(string: s)!, server: srv) }
    check(ln("https://saga.example.org/login?next=/notes%234") == .expired(next: "/notes#4"), "Sitzung abgelaufen mit next")
    check(ln("https://saga.example.org/login") == .expired(next: "/"), "Sitzung abgelaufen")
    check(ln("https://saga.example.org/login?bye=1") == .loggedOut, "abgemeldet")
    check(ln("https://saga.example.org/login?next=//evil.test") == .expired(next: "/")
          && ln("https://saga.example.org/login?next=https://evil.test") == .expired(next: "/"), "next nur eigene Pfade")
    check(ln("https://idp.example.org/login") == .none && ln("https://saga.example.org/logins") == .none
          && ln("https://saga.example.org/notes") == .none, "keine Anmeldeseite")

    check(ShellLogic.externalURL("https://example.com/a", server: srv)?.host == "example.com", "fremder Link")
    check(ShellLogic.externalURL("mailto:a@example.com", server: srv) != nil, "mailto")
    check(ShellLogic.externalURL("https://saga.example.org/x", server: srv) == nil, "eigene Origin nicht extern")
    check(ShellLogic.externalURL("file:///etc/passwd", server: srv) == nil && ShellLogic.externalURL("javascript:alert(1)", server: srv) == nil
          && ShellLogic.externalURL("saga://pair?u=x", server: srv) == nil && ShellLogic.externalURL(5, server: srv) == nil
          && ShellLogic.externalURL("https://" + String(repeating: "a", count: 4000), server: srv) == nil, "verbotene Schemata/Laenge")

    check(ShellLogic.badgeLabel(0) == "" && ShellLogic.badgeLabel(3) == "3" && ShellLogic.badgeLabel(-1) == ""
          && ShellLogic.badgeLabel(123456) == "99999" && ShellLogic.badgeLabel(NSNumber(value: 7)) == "7" && ShellLogic.badgeLabel("x") == "", "Dock-Zahl")

    check(ShellLogic.appKey("hk_abc-DEF_123") == "hk_abc-DEF_123", "App-Schluessel")
    check(ShellLogic.appKey("hkd_x") == nil && ShellLogic.appKey("hk_a'b") == nil && ShellLogic.appKey("hk_" + String(repeating: "a", count: 200)) == nil
          && ShellLogic.appKey(nil) == nil, "App-Schluessel ungueltig")

    let js = ShellLogic.hostInfoScript(version: "2.5.0'; alert(1)//")
    check(js.contains("kind: 'mac'") && js.contains("version: '2.5.0alert1'") && !js.contains("notify")
          && js.contains("'badge'") && js.contains("'widgets'") && js.contains("'token'"), "Faehigkeiten ohne notify, Version gesaeubert")
    check(ShellLogic.userAgent(version: "2.5.0") == "SagaApp/2.5.0 (mac-shell)", "User-Agent")
    check(ShellLogic.eventJS(["type": "open", "path": "/notes#1"])?.hasPrefix("window.sagaHost && window.sagaHost.receive({") == true, "Ereignis")
    check(ShellLogic.pathOf(URL(string: "https://saga.example.org/notes?v=tasks#3")!) == "/notes?v=tasks#3", "Pfad einer Adresse")
    shellTests2(srv)
}

// Etappe 2: Kopplung, Datenbank-Vorgaben, Spotlight, Mitteilungen
func shellTests2(_ srv: URL) {
    let code = String(repeating: "A", count: 24)
    let https: (URL) -> Bool = { $0.scheme == "https" }
    let a = ShellLogic.pairLink("https://saga.example.org/app/pair?c=\(code)", allowed: https)
    check(a?.server.absoluteString == "https://saga.example.org" && a?.code == code, "Kopplung https")
    let b = ShellLogic.pairLink("saga://pair?u=https%3A%2F%2Fsaga.example.org%3A8443%2Fx%3Fy%3D1&c=\(code)", allowed: https)
    check(b?.server.absoluteString == "https://saga.example.org:8443", "Kopplung saga:// mit Port, Pfad weg")
    check(ShellLogic.pairLink("saga-test://pair?u=https://s.example.org&c=\(code)", allowed: https) != nil, "Kopplung Test-Schema")
    check(ShellLogic.pairLink("HTTPS://Saga.Example.org/app/pair?c=\(code)", allowed: https)?.server.absoluteString == "https://saga.example.org"
          && ShellLogic.pairLink("saga://pair?u=HTTPS://S.example.org&c=\(code)", allowed: https)?.server.absoluteString == "https://s.example.org",
          "Kopplung Schema/Host klein")
    check(ShellLogic.pairLink("http://saga.example.org/app/pair?c=\(code)", allowed: https) == nil, "Kopplung nur https")
    check(ShellLogic.pairLink("https://saga.example.org/app/pair?c=kurz", allowed: https) == nil
          && ShellLogic.pairLink("https://saga.example.org/app/pair?c=\(code)%27", allowed: https) == nil, "Kopplung Code pruefen")
    check(ShellLogic.pairLink("https://saga.example.org/anders?c=\(code)", allowed: https) == nil
          && ShellLogic.pairLink("evil://pair?u=https://x.example.org&c=\(code)", allowed: https) == nil
          && ShellLogic.pairLink("saga://pair?u=javascript:alert(1)&c=\(code)", allowed: https) == nil, "Kopplung fremd")
    let o = URL(string: "https://other.example.org")!
    check(ShellLogic.pairNotice(target: srv, current: nil, signedIn: false) == .none
          && ShellLogic.pairNotice(target: srv, current: srv, signedIn: false) == .none
          && ShellLogic.pairNotice(target: srv, current: URL(string: "https://saga.example.org:443")!, signedIn: true) == .sameServer
          && ShellLogic.pairNotice(target: srv, current: o, signedIn: true) == .otherServer("other.example.org"), "Kopplung Hinweis")

    let schema: [String: Any] = ["props": [["id": "s", "type": "select"], ["id": "m", "type": "multi_select"], ["id": "c", "type": "checkbox"]],
                                 "views": [["id": "v1", "filters": [["pid": "s", "op": "is", "value": "offen"]]],
                                           ["id": "v2", "filters": [["pid": "m", "op": "is", "value": "x"], ["pid": "c", "op": "checked"],
                                                                    ["pid": "s", "op": "is_not", "value": "z"], ["pid": "title", "op": "eq", "value": "t"],
                                                                    ["pid": "weg", "op": "is", "value": "q"], ["pid": "s", "op": "is", "value": ""]]]]]
    let d1 = ShellLogic.rowDefaults(schema: schema, viewID: nil), d2 = ShellLogic.rowDefaults(schema: schema, viewID: "v2")
    check(d1.count == 1 && d1["s"] as? String == "offen", "Vorgaben erste Ansicht")
    check(d2.count == 2 && (d2["m"] as? [String]) == ["x"] && d2["c"] as? Bool == true, "Vorgaben aus Filtern")
    check(ShellLogic.rowDefaults(schema: schema, viewID: "fehlt").count == 1 && ShellLogic.rowDefaults(schema: nil, viewID: nil).isEmpty, "Vorgaben ohne Ansicht")

    let pg: [ShellLogic.IndexPage] = [.init(id: 1, title: "Projekte", icon: "📁", kind: "page", parent: nil),
                                      .init(id: 2, title: "Aufgaben", icon: "", kind: "database", parent: 1),
                                      .init(id: 3, title: " ", icon: "/f/1.png", kind: "page", parent: 2),
                                      .init(id: 4, title: "Allein", icon: "", kind: "page", parent: 99),
                                      .init(id: 1, title: "doppelt", icon: "", kind: "page", parent: nil),
                                      .init(id: -5, title: "fremd", icon: "", kind: "page", parent: nil)]
    let ie = ShellLogic.indexEntries(pg, untitled: "U", page: "Seite", database: "DB")
    check(ie.map(\.id) == ["page-1", "page-2", "page-4", "page-3"], "Spotlight Reihenfolge, doppelt/fremd weg")
    check(ie[0].display == "📁 Projekte" && ie[0].desc == "Seite" && ie[1].desc == "Projekte" && ie[1].display == nil
          && ie[3].title == "U" && ie[3].desc == "Projekte / Aufgaben" && ie[2].desc == "Seite", "Spotlight Inhalt")
    check(ShellLogic.indexEntries(pg, untitled: "U", page: "S", database: "D", limit: 2).count == 2, "Spotlight Grenze")
    check(ShellLogic.spotlightPath("page-12") == "/notes#12" && ShellLogic.spotlightPath("page-x") == nil
          && ShellLogic.spotlightPath("row-1") == nil && ShellLogic.spotlightPath(nil) == nil, "Spotlight Treffer")

    check(ShellLogic.notificationPath("/chats#c3", server: srv) == "/chats#c3" && ShellLogic.notificationPath("?settings=backup", server: srv) == "/?settings=backup"
          && ShellLogic.notificationPath("https://saga.example.org/notes#4", server: srv) == "/notes#4", "Mitteilung eigene Ziele")
    check(ShellLogic.notificationPath("https://evil.test/x", server: srv) == "/" && ShellLogic.notificationPath("//evil.test", server: srv) == "/"
          && ShellLogic.notificationPath(nil, server: srv) == "/" && ShellLogic.notificationPath(3, server: srv) == "/", "Mitteilung fremde Ziele")
    check(ShellLogic.isRinging(kind: "call") && ShellLogic.isRinging(kind: "call.group") && !ShellLogic.isRinging(kind: "chat.message")
          && !ShellLogic.isRinging(kind: nil), "Anruf klingelt")
}
