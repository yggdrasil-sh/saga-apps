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
    check(r("saga://new?db=5&view=t&k=x") == .path("/notes#5") && r("saga://new?parent=9") == .path("/notes#9")
          && r("saga://new?db=x") == .path("/notes?new=blank"), "Neu in Datenbank/Seite")
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
}
