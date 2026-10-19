import Foundation

// E2E Apps 2.3 Teil 3 mit den Server-Wegen und der Logik der App (CloudFlow, CloudLogic, NotifItem/NotifLogic):
// "Mein Backup" (Ziel per Login Flow v2, Schluessel, Zeitplan, Auswahl, Lauf, Backups im Ziel, Einspielen, Fehler ->
// Benachrichtigung, Trennen) und Nextcloud-Dateien (Verknuepfen, Auswahl, Block speichern, Status, Vorschau/Download
// ueber den Proxy, Block bleibt beim erneuten Speichern unveraendert, fremder Block -> 403 mit Meldung, Kopie, Trennen).
// Umgebung:
//   E2E_BASE      Adresse des Servers (z. B. http://host:port), Stand 9009d43 oder neuer
//   E2E_COOKIE_A  Sitzungs-Cookie Konto A ("saga=…"), E2E_COOKIE_B Konto B
//   E2E_CTL       Programm im Testcontainer: <prog> grant <port> | put <port> <pfad> png|text | fake <port> <query> | minh <h>
//   E2E_NC_BK     Adresse der Fake-Nextcloud fuer Backups (vom Server aus), E2E_NC_FILES fuer Dateien
// Aufruf: swift run -c release CloudE2E   (Ende mit "E2E n/m")
let env = ProcessInfo.processInfo.environment
func need(_ k: String) -> String { guard let v = env[k], !v.isEmpty else { print("Umgebung fehlt: \(k)"); exit(2) }; return v }
let base = need("E2E_BASE").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
let cookieA = need("E2E_COOKIE_A"), cookieB = need("E2E_COOKIE_B"), ctlProg = need("E2E_CTL")
let ncBK = need("E2E_NC_BK"), ncFiles = need("E2E_NC_FILES")
let bkPort = URL(string: ncBK)?.port.map(String.init) ?? "", filesPort = URL(string: ncFiles)?.port.map(String.init) ?? ""

var results: [(String, Bool)] = []
func ok(_ n: String, _ c: Bool, _ info: @autoclosure () -> String = "") {
    results.append((n, c)); print(c ? "OK  " : "FAIL", n, c ? "" : "– " + info())
}
func txt(_ d: Data) -> String { String(data: d.prefix(400), encoding: .utf8) ?? "\(d.count) Bytes" }

/// Anfrage wie die App (Store.apiResponse): Sitzung, X-Hlidskjalf, Accept-Language
func makeAPI(_ cookie: String) -> CloudAPI {
    { m, p, b in
        var r = URLRequest(url: URL(string: base + "/" + p)!, timeoutInterval: 90)
        r.httpMethod = m
        r.setValue(cookie, forHTTPHeaderField: "Cookie"); r.setValue("1", forHTTPHeaderField: "X-Hlidskjalf"); r.setValue("de", forHTTPHeaderField: "Accept-Language")
        if let b { r.httpBody = try JSONSerialization.data(withJSONObject: b); r.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (d, resp) = try await URLSession.shared.data(for: r)
        return (d, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }
}
let A = makeAPI(cookieA), B = makeAPI(cookieB)
func ctl(_ args: String...) -> String {
    let p = Process(); p.executableURL = URL(fileURLWithPath: ctlProg); p.arguments = args
    let out = Pipe(); p.standardOutput = out; p.standardError = out
    try? p.run(); let d = out.fileHandleForReading.readDataToEndOfFile(); p.waitUntilExit()
    return (String(data: d, encoding: .utf8) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
}
func sleep(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1e9)) }
func status(_ api: CloudAPI = A, pages: Bool = false) async -> BackupState? { (try? await CloudFlow.backupStatus(api, pages: pages)) ?? nil }
func waitFor(_ secs: Double = 60, _ cond: () async -> Bool) async -> Bool {
    let end = Date().addingTimeInterval(secs)
    while Date() < end { if await cond() { return true }; await sleep(1) }
    return false
}
func errText(_ e: Error) -> String { (e as? CloudFailure)?.message ?? e.localizedDescription }

// MARK: 1. Mein Backup: Ziel per Login Flow v2

_ = ctl("minh", "0")                                                  // wiederholbar: erster Lauf nie durch einen frueheren blockiert
print("   [Testkonten geleert: \(ctl("wipe", "ap3-anna")) / \(ctl("wipe", "ap3-otto"))]")

var s0 = await status()
ok("Status: erlaubt, ohne Ziel, nicht bereit", s0?.allowed == true && s0?.target == nil && s0?.ready == false, "\(String(describing: s0))")
ok("Server meldet Kategorien/Ausschluesse (Auswahl sichtbar)", s0?.canSelectContent == true)
do {
    let flow = try await CloudFlow.backupConnect(A, url: ncBK, user: "", password: "", folder: "Saga-Backup")
    ok("Nextcloud erkannt -> Login-Link fuer den Browser", flow?.absoluteString.contains("/login/v2/flow/") == true, "\(String(describing: flow))")
} catch { ok("Verbinden (Login Flow)", false, errText(error)) }
s0 = await status()
ok("Status: Anmeldung laeuft (App fragt ab)", s0?.flow.waiting == true && s0?.needsPolling == true, "\(String(describing: s0?.flow))")
_ = ctl("grant", bkPort)
ok("Nach der Anmeldung in Nextcloud: Ziel verbunden", await waitFor(30) { await status()?.target?.isNextcloud == true })
s0 = await status()
ok("Ziel: Ordner und App-Passwort", s0?.target?.folder == "Saga-Backup" && s0?.target?.flow == true && s0?.flow.waiting == false, "\(String(describing: s0?.target))")
do { _ = try await CloudFlow.backupTest(A); ok("Verbindung pruefen", true) } catch { ok("Verbindung pruefen", false, errText(error)) }

// MARK: 2. Schluessel

var priv = ""
do {
    let k = try await CloudFlow.keyGenerate(A)
    priv = k.priv
    ok("Schluesselpaar erzeugt (privater nur in der Antwort)", k.priv.contains("AGE-SECRET-KEY-") && k.pub.hasPrefix("age1") && !k.filename.isEmpty,
       "pub=\(k.pub.prefix(8)) file=\(k.filename)")
    ok("Vor dem Bestaetigen noch kein Schluessel", await status()?.keys.isEmpty == true)
    do { try await CloudFlow.keyConfirm(A, last6: "XXXXXX"); ok("falsche Bestaetigung abgelehnt", false) }
    catch { ok("falsche Bestaetigung abgelehnt: \(errText(error))", true) }
    try await CloudFlow.keyConfirm(A, last6: CloudLogic.last6(k.priv))
    let s = await status()
    ok("Bestaetigt: Schluessel eingetragen, bereit", s?.keys.count == 1 && s?.ready == true, "\(String(describing: s?.keys))")
} catch { ok("Schluessel", false, errText(error)) }
do { try await CloudFlow.keyAdd(A, key: priv); ok("privater Schluessel als oeffentlicher abgelehnt", false) }
catch { ok("privater Schluessel als oeffentlicher abgelehnt (\(errText(error)))", (error as? CloudFailure)?.code == 400) }

// MARK: 3. Zeitplan, Auswahl

var pageA = 0, pageX = 0
do {
    for (t, i) in [("E2E-Backup-Seite", 0), ("E2E-Ausgeschlossen", 1)] {
        let (d, c) = try await A("POST", "api/notes", ["title": t])
        let id = ShareJSON.obj(d).flatMap { ShareJSON.int($0["id"]) } ?? 0
        if i == 0 { pageA = id } else { pageX = id }
        ok("Seite \(t) angelegt (\(c))", id > 0)
    }
    let s = try await CloudFlow.backupSettings(A, CloudLogic.planBody(schedule: "weekly", time: "04:30", weekday: 2, keep: 5))
    ok("Zeitplan gespeichert", s?.schedule == "weekly" && s?.time == "04:30" && s?.weekday == 2 && s?.keep == 5 && s?.next != nil, "\(String(describing: s))")
    var cats = (await status())?.cats ?? [:]
    cats["bookmarks"] = false
    let s2 = try await CloudFlow.backupSettings(A, CloudLogic.contentBody(cats: cats, exclude: [pageX]))
    ok("Auswahl gespeichert (Kategorie aus, Seitenbaum ausgeschlossen)", s2?.cats?["bookmarks"] == false && s2?.exclude == [pageX], "\(String(describing: s2?.exclude))")
    let sp = await status(pages: true)
    ok("Seitenliste fuer die Auswahl", CloudLogic.pageTree(sp?.pages ?? []).contains { $0.page.id == pageA })
} catch { ok("Zeitplan/Auswahl", false, errText(error)) }

// MARK: 4. Jetzt sichern, Backups im Ziel, zweiter Lauf zu frueh

let t0 = Date().timeIntervalSince1970 - 2
ok("Ziel erreichbar (\(ctl("fake", bkPort, "down=0")))", true)
do {
    try await CloudFlow.backupRun(A)
    ok("Jetzt sichern gestartet", true)
    ok("Lauf fertig", await waitFor(120) { await status().map { !$0.running && ($0.runs.first?.started ?? 0) >= t0 && $0.runs.first?.finished != nil } ?? false })
    let s = await status()
    ok("Letzter Lauf erfolgreich (Groesse)", s?.runs.first?.ok == true && (s?.runs.first?.size ?? 0) > 0 && s?.lastError == nil, "\(String(describing: s?.runs.first))")
} catch { ok("Jetzt sichern", false, errText(error)) }
var remoteName = ""
do {
    let r = try await CloudFlow.backupRemote(A)
    remoteName = r.filter(\.complete).max { ($0.time ?? 0) < ($1.time ?? 0) }?.name ?? ""     // neuestes (frueherer Lauf: anderer Schluessel)
    ok("Backups im Ziel: \(r.map(\.name))", remoteName.hasSuffix(".age"))
    let (d, c) = try await A("GET", "api/account/backup/remote/" + CloudLogic.q(remoteName), nil)
    ok("Backup herunterladen (verschluesselt)", c == 200 && d.count > 100 && txt(d.prefix(40)).contains("age-encryption.org"), "\(c) \(d.count)")
} catch { ok("Backups im Ziel", false, errText(error)) }
_ = ctl("minh", "1")                                                  // Mindestabstand 1 h (Standard)
do { try await CloudFlow.backupRun(A); ok("zweiter Lauf zu frueh abgelehnt", false) }
catch { ok("zweiter Lauf zu frueh: \(errText(error))", (error as? CloudFailure)?.code == 429 && errText(error).count > 10) }

// MARK: 5. Einspielen aus dem Ziel

func titles() async -> [String] {
    guard let (d, _) = try? await A("GET", "api/notes", nil) else { return [] }
    return (try? JSONSerialization.jsonObject(with: d) as? [[String: Any]])?.compactMap { $0["title"] as? String } ?? []
}
do {
    _ = try? await CloudFlow.restore(A, remote: remoteName, key: "AGE-SECRET-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ", options: RestoreOptions())
    let before = await titles()
    let r = try await CloudFlow.restore(A, remote: remoteName, key: priv, options: RestoreOptions())
    ok("Eingespielt aus dem Ziel: \(r.pages) Seiten", r.pages >= 1)
    let after = await titles()
    func n(_ a: [String], _ t: String) -> Int { a.filter { $0 == t }.count }
    ok("Neuer Ordner „Wiederhergestellt …“ mit der Seite, ausgeschlossene Seite nicht eingespielt",
       after.filter { $0.hasPrefix("Wiederhergestellt") }.count == before.filter { $0.hasPrefix("Wiederhergestellt") }.count + 1
       && n(after, "E2E-Backup-Seite") == n(before, "E2E-Backup-Seite") + 1 && n(after, "E2E-Ausgeschlossen") == n(before, "E2E-Ausgeschlossen"),
       "\(before.count) -> \(after.count)")
} catch { ok("Einspielen", false, errText(error)) }

// MARK: 6. Fehler -> Benachrichtigung userbackup.failed (lesbar, Ziel Einstellungen)

_ = ctl("minh", "0"); _ = ctl("fake", bkPort, "down=1")
do {
    try await CloudFlow.backupRun(A)
    ok("Lauf trotz Ausfall des Ziels gestartet", true)
    ok("Lauf fehlgeschlagen", await waitFor(120) { await status().map { !$0.running && $0.lastError != nil } ?? false })
    ok("Fehlertext im Status: \(await status()?.lastError ?? "-")", (await status()?.lastError?.count ?? 0) > 3)
} catch { ok("Fehllauf", false, errText(error)) }
ok("Ziel wieder erreichbar", ctl("fake", bkPort, "down=0") == "ok")
do {
    let (d, _) = try await A("GET", "api/notifications?limit=30", nil)
    let items = (ShareJSON.obj(d)?["items"] as? [Any] ?? []).compactMap(NotifItem.init)
    let n = items.first { $0.kind == "userbackup.failed" }
    ok("Benachrichtigung userbackup.failed: \(n?.title ?? "-") – \(n?.text ?? "-")", n != nil && n!.system && !n!.text.isEmpty && n!.text != "userbackup.failed" && n!.title == "Mein Backup")
    ok("antippbar -> Einstellungen „Mein Backup“", n.map { $0.openable && NotifLogic.target($0) == .settings("backup") } == true)
} catch { ok("Benachrichtigungen", false, errText(error)) }
do {
    try await CloudFlow.backupDisconnect(A)
    let s = await status()
    ok("Ziel getrennt", s?.target == nil && s?.ready == false)
    if let fp = s?.keys.first?.fp { try await CloudFlow.keyRemove(A, fp: fp) }
    ok("Schluessel entfernt", await status()?.keys.isEmpty == true)
} catch { ok("Trennen", false, errText(error)) }
// WebDAV mit Benutzer/Passwort: falsches Passwort -> Meldung
do { _ = try await CloudFlow.backupConnect(A, url: ncBK.replacingOccurrences(of: bkPort, with: String((Int(bkPort) ?? 0) + 1)) + "/dav", user: "plain-user", password: "falsch", folder: ""); ok("WebDAV falsches Passwort abgelehnt", false) }
catch { ok("WebDAV falsches Passwort abgelehnt: \(errText(error))", true) }
do {
    let f = try await CloudFlow.backupConnect(A, url: ncBK.replacingOccurrences(of: bkPort, with: String((Int(bkPort) ?? 0) + 1)) + "/dav", user: "plain-user", password: "plain-pass-123", folder: "B")
    let s = await status()
    ok("WebDAV mit Benutzer/Passwort verbunden", f == nil && s?.target?.kind == "webdav" && s?.target?.user == "plain-user", "\(String(describing: s?.target))")
    try await CloudFlow.backupDisconnect(A)
} catch { ok("WebDAV verbinden", false, errText(error)) }

// MARK: 7. Nextcloud verknuepfen

var nc = (try? await CloudFlow.ncSettings(A)) ?? nil
ok("Nextcloud: an, nicht verbunden", nc?.enabled == true && nc?.connected == false, "\(String(describing: nc))")
do {
    let u = try await CloudFlow.ncConnect(A, url: ncFiles)
    ok("Login-Link fuer den Browser", u.absoluteString.contains("/login/v2/flow/"))
    ok("Anmeldung laeuft", ((try? await CloudFlow.ncSettings(A)) ?? nil)?.flow.waiting == true)
    _ = ctl("grant", filesPort)
    ok("Verbunden", await waitFor(30) { ((try? await CloudFlow.ncSettings(A)) ?? nil)?.connected == true })
    nc = (try? await CloudFlow.ncSettings(A)) ?? nil
    ok("Konto und Adresse: \(nc?.user ?? "-") @ \(nc?.host ?? "-")", nc?.me != nil && nc?.server != nil && nc?.since != nil)
} catch { ok("Nextcloud verknuepfen", false, errText(error)) }

// MARK: 8. Auswahl, Block speichern, Status, Proxy

let imgID = ctl("put", filesPort, "/Bilder/bild.png", "png"), pdfID = ctl("put", filesPort, "/Dokumente/Bericht.pdf", "text")
ok("Dateien in der Fake-Nextcloud (\(imgID), \(pdfID))", Int(imgID) != nil && Int(pdfID) != nil)
var img: NCItem?, pdf: NCItem?, folder: NCItem?
do {
    let root = try await CloudFlow.ncList(A, path: "/")
    folder = root.first { $0.name == "Bilder" && $0.dir }
    ok("Ordner-Ansicht: \(root.map(\.name))", folder != nil && root.contains { $0.name == "Dokumente" })
    img = try await CloudFlow.ncList(A, path: "/Bilder").first { $0.name == "bild.png" }
    ok("Unterordner: Bild mit Vorschau", img?.kind == "image" && img?.fileid == imgID, "\(String(describing: img))")
    let recent = try await CloudFlow.ncSearch(A, query: "")
    ok("Zuletzt geaendert", recent.contains { $0.fileid == pdfID })
    pdf = try await CloudFlow.ncSearch(A, query: "Bericht").first
    ok("Suche „Bericht“", pdf?.fileid == pdfID && pdf?.folder == "/Dokumente", "\(String(describing: pdf))")
    let r = try await CloudFlow.ncResolve(A, fileid: folder?.fileid ?? "")
    ok("Ordner per ID (Diesen Ordner einfuegen)", r?.dir == true && r?.name == "Bilder")
} catch { ok("Auswahl", false, errText(error)) }

func patchBody(_ api: CloudAPI, _ id: Int, _ blocks: [[String: Any]]) async throws -> (Data, Int) {
    let (d0, _) = try await api("GET", "api/notes/\(id)", nil)
    let rev = ShareJSON.obj(d0).flatMap { ShareJSON.int($0["rev"]) } ?? 0
    let body = String(data: try JSONSerialization.data(withJSONObject: ["time": 1, "blocks": blocks]), encoding: .utf8)!
    return try await api("PATCH", "api/notes/\(id)", ["title": "E2E-Nextcloud", "rev": rev, "body": body, "format": "blocks"])
}
func pageBlocks(_ api: CloudAPI, _ id: Int) async -> [[String: Any]] {
    guard let (d, _) = try? await api("GET", "api/notes/\(id)", nil), let b = ShareJSON.obj(d)?["body"] as? String,
          let j = try? JSONSerialization.jsonObject(with: Data(b.utf8)) as? [String: Any] else { return [] }
    return j["blocks"] as? [[String: Any]] ?? []
}
var stored: [[String: Any]] = []
if let img, let pdf, let folder {
    do {
        let blocks: [[String: Any]] = [["id": "n1", "type": "nextcloud", "data": CloudLogic.newBlockData(img, me: nc?.me)],
                                       ["id": "n2", "type": "nextcloud", "data": CloudLogic.newBlockData(pdf, me: nc?.me)],
                                       ["id": "n3", "type": "nextcloud", "data": CloudLogic.newBlockData(folder, me: nc?.me)],
                                       ["id": "p1", "type": "paragraph", "data": ["text": "Text"]]]
        let (d, c) = try await patchBody(A, pageA, blocks)
        ok("Seite mit drei Nextcloud-Bloecken gespeichert (\(c))", c == 200, txt(d))
        stored = await pageBlocks(A, pageA)
        let nb = stored.compactMap { $0["type"] as? String == "nextcloud" ? NCBlock($0["data"] as? [String: Any] ?? [:]) : nil }
        ok("Server setzt Autor und Signatur", nb.count == 3 && nb.allSatisfy { $0.signed && $0.author == nc?.me }, "\(nb)")
        let st = await CloudFlow.noteStatus(A, note: pageA)
        let ib = nb.first { $0.isImage }!, fb = nb.first { $0.isFolder }!, pb = nb.first { $0.fileid == pdfID }!
        ok("Status: Bild ok", st.item(for: ib, remotePage: false)?.ok == true)
        ok("Status: Ordner mit ersten Eintraegen", st.item(for: fb, remotePage: false)?.entries.contains { $0.name == "bild.png" } == true)
        let (pv, pc) = try await A("GET", ib.proxyPath(note: pageA, w: 320, v: ib.mtime), nil)
        ok("Vorschau ueber den Saga-Proxy (\(pc), \(pv.count) Bytes)", pc == 200 && pv.starts(with: [0x89, 0x50, 0x4E, 0x47]))
        let (dl, dc) = try await A("GET", pb.proxyPath(note: pageA, download: true), nil)
        ok("Herunterladen ueber den Proxy (\(dc))", dc == 200 && txt(dl).hasPrefix("%PDF"))
        ok("In Nextcloud oeffnen: \(pb.openURL(remotePage: false)?.absoluteString ?? "-")", pb.openURL(remotePage: false)?.absoluteString == ncFiles + "/f/" + pdfID)
        // erneut speichern wie die App (Bloecke unveraendert durchgereicht, Text geaendert)
        var again = stored
        if let i = again.firstIndex(where: { $0["type"] as? String == "paragraph" }) { again[i]["data"] = ["text": "Text geändert"] }
        let (d2, c2) = try await patchBody(A, pageA, again)
        let after = await pageBlocks(A, pageA).filter { $0["type"] as? String == "nextcloud" }
        let same = after.count == 3 && zip(after, stored.filter { $0["type"] as? String == "nextcloud" }).allSatisfy { NSDictionary(dictionary: $0.0["data"] as? [String: Any] ?? [:]) == NSDictionary(dictionary: $0.1["data"] as? [String: Any] ?? [:]) }
        ok("Erneut gespeichert: Bloecke unveraendert (\(c2))", c2 == 200 && same, txt(d2))
        // Konto B kopiert den fremden Block in eine eigene Seite -> 403 mit verstaendlicher Meldung
        let (bd, _) = try await B("POST", "api/notes", ["title": "B-Kopie"])
        let bID = ShareJSON.obj(bd).flatMap { ShareJSON.int($0["id"]) } ?? 0
        let copied = stored.filter { $0["type"] as? String == "nextcloud" }.prefix(1).map { $0 }
        let (rd, rc) = try await patchBody(B, bID, copied)
        let msg = CloudLogic.saveRejection(code: rc, data: rd, saved: [], now: CloudLogic.ncKeys(copied))
        ok("Fremder Block in anderer Seite: 403 (\(rc))", rc == 403)
        ok("Meldung statt Rohtext: \(msg ?? "-")", msg?.contains("Nextcloud") == true && msg?.contains("Entwurf") == true)
        ok("Ohne neue Nextcloud-Bloecke: normale Fehlerbehandlung", CloudLogic.saveRejection(code: rc, data: rd, saved: CloudLogic.ncKeys(copied), now: CloudLogic.ncKeys(copied)) == nil)
        // Konto B (nicht verbunden) fuegt eigenen Block ein -> 409 "nicht verbunden", kein Konflikt
        var own = CloudLogic.newBlockData(pdf, me: "ap3-otto"); own["author"] = "ap3-otto"
        let (od, oc) = try await patchBody(B, bID, [["id": "o1", "type": "nextcloud", "data": own]])
        ok("Ohne eigene Verknuepfung: 409 mit Meldung, kein Konflikt (\(oc))", oc == 409 && CloudLogic.saveRejection(code: oc, data: od, saved: [], now: ["ap3-otto|x|1"]) != nil, txt(od))
        // Kopie uebernehmen
        let f = try await CloudFlow.ncImport(A, fileid: pdfID, note: nil)
        let cb = CloudLogic.copyBlock(file: f, id: "c1")
        ok("Als Kopie uebernommen (\(f["url"] as? String ?? "-"))", cb?["type"] as? String == "attaches")
    } catch { ok("Block/Status/Proxy", false, errText(error)) }
}

// MARK: 9. Trennen -> Karte ohne Inhalt

do {
    try await CloudFlow.ncDisconnect(A)
    ok("Nextcloud getrennt", ((try? await CloudFlow.ncSettings(A)) ?? nil)?.connected == false)
    let nb = stored.compactMap { $0["type"] as? String == "nextcloud" ? NCBlock($0["data"] as? [String: Any] ?? [:]) : nil }
    let st = await CloudFlow.noteStatus(A, note: pageA)
    ok("Nach dem Trennen: Karte ohne Inhalt", nb.first.map { st.item(for: $0, remotePage: false)?.ok != true } == true)
} catch { ok("Trennen", false, errText(error)) }

let good = results.filter(\.1).count
print("\nE2E \(good)/\(results.count)" + (good == results.count ? " – ALLES GRUEN" : " – FEHLER"))
exit(good == results.count ? 0 : 1)
