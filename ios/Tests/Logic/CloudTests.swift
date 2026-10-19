import Foundation

// Apps 2.3 Teil 3: Mein Backup (Status, Zeitplan, Seitenbaum, Fehler), Nextcloud (Verknuepfung, Block, Proxy-Adressen,
// Status, Auswahl, neue Bloecke, Ablehnung beim Speichern) und Benachrichtigungen (Systemmeldungen, Ziele).
func cloudTests() {
    func data(_ s: String) -> Data { Data(s.utf8) }

    // --- Mein Backup: Status
    let st = BackupState(data: data(#"""
    {"allowed": true, "ready": true, "running": false, "last_ok": 1792400000, "last_attempt": 1792400100, "next": 1792486400,
     "target": {"kind": "nextcloud", "address": "https://cloud.example.org", "user": "anna", "folder": "Saga-Backup", "flow": true, "allowed": true},
     "keys": [{"short": "age1abc…xyz", "fp": "f00d"}], "schedule": "weekly", "effective_schedule": "weekly", "time": "04:30", "weekday": 6, "keep": 9,
     "cats": {"pages": true, "comments_others": false}, "exclude": [12, "x", 14],
     "runs": [{"started": 1792400100, "finished": 1792400200, "trigger": "manual", "name": "saga-1.zip.age", "ok": false, "size": null, "step": "upload", "error": "Ziel voll"}],
     "limits": {"max_mb": 512, "min_h": 1, "max_schedule": "weekly"}, "flow": {"state": "", "login": null, "message": null}, "age_ok": true,
     "pages": [{"id": 12, "title": "Projekte", "icon": "📁", "parent": null}, {"id": 14, "title": "", "icon": null, "parent": 12}, {"id": 15, "title": "Waise", "parent": 999}]}
    """#))
    check(st != nil && st!.allowed && st!.ready && st!.target?.isNextcloud == true && st!.target?.flow == true, "Backup-Status gelesen")
    check(st?.keys.first?.fp == "f00d" && st?.schedule == "weekly" && st?.time == "04:30" && st?.weekday == 6 && st?.keep == 9, "Schluessel, Zeitplan")
    check(st?.canSelectContent == true && st?.exclude == [12, 14] && st?.cats?["pages"] == true, "Ausschluesse (nur Zahlen)")
    check(st?.lastError == "Ziel voll" && st?.runningStep == nil && st?.needsPolling == false, "letzter Fehler")
    check(st?.maxMB == 512 && st?.maxSchedule == "weekly", "Grenzen")
    let old = BackupState(data: data(#"{"allowed": true, "ready": false, "running": true, "runs": [{"started": 5, "step": "Packe …"}], "flow": {"state": "wait", "login": "javascript:alert(1)"}}"#))
    check(old?.canSelectContent == false && old?.runningStep == "Packe …" && old?.needsPolling == true, "ohne cats/exclude: Auswahl ausblenden; laeuft")
    check(old?.flow.waiting == false && old?.flow.login == nil, "Login-Link nur http(s)")
    check(BackupState(data: data(#"{"error": "x"}"#)) == nil && BackupState(data: data("kaputt")) == nil, "keine Status-Antwort")
    let off = BackupState(data: data(#"{"allowed": false}"#))
    check(off?.allowed == false && off?.target == nil, "abgeschaltet")

    // Seitenbaum: Waise (Elternseite fremd) oben, Kind eingerueckt; Zyklen brechen ab
    let tree = CloudLogic.pageTree(st?.pages ?? [])
    check(tree.map(\.page.id) == [12, 14, 15] && tree.map(\.depth) == [0, 1, 0], "Seitenbaum fuer die Auswahl")
    check(tree[1].page.label == "Unbenannt" && tree[0].page.label == "📁 Projekte", "Seitennamen")
    let cyc = CloudLogic.pageTree([BackupPage(id: 1, title: "a", parent: 2), BackupPage(id: 2, title: "b", parent: 1)])
    check(cyc.isEmpty, "Zyklus ohne Wurzel: keine Endlosschleife")

    // Zeitplan
    check(CloudLogic.scheduleAllowed("daily", max: "weekly") == false && CloudLogic.scheduleAllowed("weekly", max: "weekly")
          && CloudLogic.scheduleAllowed("daily", max: "daily") && CloudLogic.scheduleAllowed("daily", max: nil), "taeglich nur, wenn erlaubt")
    check(CloudLogic.clampKeep(0) == 1 && CloudLogic.clampKeep(99) == 60, "Aufbewahrung 1..60")
    let pb = CloudLogic.planBody(schedule: "daily", time: "03:05", weekday: 9, keep: 0)
    check(pb["weekday"] as? Int == 6 && pb["keep"] as? Int == 1 && pb["time"] as? String == "03:05", "Zeitplan-Anfrage")
    check(CloudLogic.validTime("03:05") && !CloudLogic.validTime("3:5") && !CloudLogic.validTime("24:00"), "Uhrzeit HH:MM")
    let wd = CloudLogic.weekdayNames(locale: Locale(identifier: "de_DE"))
    check(wd.count == 7 && wd.first == "Montag" && wd.last == "Sonntag", "Wochentage ab Montag (\(wd))")

    // Schluessel, Fehler
    check(CloudLogic.last6("AGE-SECRET-KEY-1ABCDEF123456\n") == "123456", "letzte 6 Zeichen")
    check(CloudLogic.last6("# created: 2026\n# public key: age1xyz\nAGE-SECRET-KEY-1QWERTZabc123\n") == "ABC123", "letzte Zeile (age-keygen mit Kommentaren)")
    check(CloudLogic.looksPrivate("age-secret-key-1xyz") && !CloudLogic.looksPrivate("age1qqq"), "privaten Schluessel erkennen")
    check(CloudLogic.runError(code: 409, data: data(#"{"error": "Kein Ziel"}"#)) == "Kein Ziel", "409: Meldung des Servers")
    check(CloudLogic.runError(code: 429, data: data(#"{"error": "Zu oft (ab 19.10. 19:39).", "retry_at": 1792400000}"#)) == "Zu oft (ab 19.10. 19:39).", "429: Meldung des Servers")
    check(CloudLogic.runError(code: 429, data: data(#"{"retry_at": 1792400000}"#)).hasPrefix("Wieder möglich ab "), "429 ohne Meldung: Zeitpunkt")
    check(CloudLogic.runError(code: 500, data: Data()).contains("500"), "ohne Meldung")

    // --- Nextcloud: Verknuepfung
    let ns = NCSettings(data: data(#"{"enabled": true, "connected": true, "me": "anna", "server": "https://cloud.example.org", "user": "anna@x", "since": 1, "used": null, "suggest": "", "flow": {"state": "ok"}}"#))
    check(ns?.connected == true && ns?.me == "anna" && ns?.host == "cloud.example.org" && ns?.suggest == nil && ns?.flow.waiting == false, "NC-Verknuepfung")
    let nw = NCSettings(data: data(#"{"enabled": true, "connected": false, "flow": {"state": "wait", "login": "https://cloud.example.org/login/v2/flow/abc"}}"#))
    check(nw?.flow.waiting == true, "Login Flow wartet")
    check(NCSettings(data: data(#"{"detail": "Not Found"}"#)) == nil, "keine NC-Antwort")

    // --- Block
    let bd: [String: Any] = ["inst": "https://cloud.example.org/", "fileid": 4711, "kind": "image", "name": "Bild &amp; Text.png", "mime": "image/png",
                             "size": 52311, "mtime": 1792400000, "author": "anna b", "public": false, "fed": false, "sig": "s1"]
    let b = NCBlock(bd)
    check(b?.fileid == "4711" && b?.isImage == true && b?.name == "Bild & Text.png" && b?.signed == true, "Block gelesen (Datei-ID als Zahl)")
    check(b?.openURL(remotePage: false)?.absoluteString == "https://cloud.example.org/f/4711" && b?.openURL(remotePage: true) == nil, "In Nextcloud oeffnen")
    check(NCBlock(["fileid": "1", "inst": "javascript:alert(1)"])?.openURL(remotePage: false) == nil, "nur http(s)")
    check(b?.proxyPath(note: 12, w: 5000, v: 1792400000.4) == "api/notes/12/nc/4711?a=anna%20b&w=2000&v=1792400000", "Vorschau ueber den Proxy (w <= 2000)")
    check(b?.proxyPath(note: -2049, download: true) == "api/notes/-2049/nc/4711?a=anna%20b&dl=1", "Download (auch fremde Instanz)")
    check(NCBlock(["inst": "x"]) == nil && NCBlock(["fileid": ""]) == nil, "ohne Datei-ID kein Block")
    check(b?.key == "anna b|https://cloud.example.org/|4711", "Schluessel")

    // Status
    let ss = NCStatus(data: data(#"""
    {"enabled": true, "items": [{"fileid": "4711", "author": "anna b", "ok": true, "name": "neu.png", "preview": true},
        {"fileid": "9", "author": "~x1", "ok": true, "dir": true, "entries": [{"name": "a", "dir": true}, {"name": "b.txt", "dir": false, "size": 3}]},
        {"fileid": "10", "author": "otto", "ok": false}]}
    """#))
    check(ss?.items.count == 3 && ss?.item(for: b!, remotePage: false)?.name == "neu.png", "Status je Block")
    let rb = NCBlock(["fileid": "9", "author": "anna", "kind": "folder"])!
    check(ss?.item(for: rb, remotePage: false) == nil && ss?.item(for: rb, remotePage: true)?.entries.count == 2, "fremde Instanz: Autor als Platzhalter")
    check(ss?.item(for: NCBlock(["fileid": "10", "author": "otto"])!, remotePage: false)?.ok == false, "ok: false = Karte ohne Inhalt")
    check(NCStatus(failed: true).failed && NCStatus(failed: true).items.isEmpty, "Status nicht geladen")

    // Auswahl + neuer Block
    let it = NCItem(["fileid": 77, "name": "Bericht.pdf", "path": "/Dokumente/Bericht.pdf", "dir": false, "mime": "application/pdf", "size": 10,
                     "mtime": 5, "preview": false, "kind": "file", "inst": "https://cloud.example.org"])
    check(it?.fileid == "77" && it?.folder == "/Dokumente" && NCItem(["fileid": "1", "path": "/a.txt"])?.folder == "/", "Auswahl-Eintrag")
    check(NCItem(["fileid": "2", "mime": "image/jpeg"])?.kind == "image" && NCItem(["fileid": "3", "dir": true])?.kind == "folder", "Art ohne kind")
    let nb = CloudLogic.newBlockData(it!, me: "anna")
    check(nb["author"] as? String == "anna" && nb["sig"] == nil && nb["public"] as? Bool == false && nb["fed"] as? Bool == false
          && nb["fileid"] as? String == "77" && nb["size"] as? Int == 10, "neuer Block: eigenes Konto, ohne Signatur, Freigaben aus")
    check(CloudLogic.symbol(mime: "application/pdf", kind: "file") == "doc.richtext" && CloudLogic.symbol(mime: "", kind: "folder") == "folder"
          && CloudLogic.symbol(mime: "image/png", kind: "file") == "photo" && CloudLogic.symbol(mime: "application/zip", kind: "file") == "doc.zipper", "Symbole")
    let cpI = CloudLogic.copyBlock(file: ["url": "/files/ab.png", "mime": "image/png", "name": "a.png"], id: "x")
    let cpF = CloudLogic.copyBlock(file: ["url": "/files/cd", "mime": "application/pdf", "name": "b.pdf", "size": 3, "extension": "pdf"], id: "y")
    check(cpI?["type"] as? String == "image" && cpF?["type"] as? String == "attaches" && ((cpF?["data"] as? [String: Any])?["file"] as? [String: Any])?["extension"] as? String == "pdf", "Kopie: Bild- bzw. Datei-Block")
    check(CloudLogic.copyBlock(file: [:], id: "z") == nil, "Kopie ohne Adresse")

    // Schluessel aller Bloecke (auch in Ausklappbar/Spalten) und Ablehnung beim Speichern
    let blocks: [Any] = [["type": "nextcloud", "data": ["fileid": "1", "author": "a", "inst": "i"]],
                         ["type": "toggle", "data": ["blocks": [["type": "nextcloud", "data": ["fileid": "2", "author": "b", "inst": "i"]]]]],
                         ["type": "columns", "data": ["cols": [["blocks": [["type": "nextcloud", "data": ["fileid": "3", "author": "a", "inst": "i"]]]]]]],
                         ["type": "paragraph", "data": ["text": "x"]]]
    let keys = CloudLogic.ncKeys(blocks)
    check(keys == ["a|i|1", "b|i|2", "a|i|3"], "Nextcloud-Bloecke ueberall gefunden")
    check(CloudLogic.ncKeys(NSMutableArray(array: blocks)).count == 3, "auch aus dem Editor (NSMutableArray)")
    let rej = data(#"{"error": "Nextcloud-Dateien anderer Personen lassen sich nicht einfügen oder ändern."}"#)
    check(CloudLogic.saveRejection(code: 403, data: rej, saved: ["a|i|1"], now: keys)?.contains("anderer Personen") == true, "403 mit neuem Block: Meldung")
    check(CloudLogic.saveRejection(code: 403, data: rej, saved: keys, now: keys) == nil, "403 ohne neuen Block: normale Behandlung (Rolle)")
    check(CloudLogic.saveRejection(code: 409, data: data(#"{"error": "conflict", "rev": 3}"#), saved: [], now: keys) == nil, "409 conflict bleibt Konflikt")
    check(CloudLogic.saveRejection(code: 409, data: data(#"{"error": "Nextcloud nicht verbunden."}"#), saved: [], now: keys) != nil, "409 nicht verbunden")
    check(CloudLogic.saveRejection(code: 409, data: Data(), saved: [], now: keys) == nil, "409 ohne Meldung: Konflikt")
    check(CloudLogic.saveRejection(code: 500, data: rej, saved: [], now: keys) == nil, "500: normale Behandlung")

    // --- Benachrichtigungen
    let ub = NotifItem(["id": 5, "kind": "userbackup.failed", "created": 1, "read": false, "access": true, "system": true,
                        "title": "Mein Backup", "text": "Backup fehlgeschlagen: Ziel voll", "url": "/?settings=backup"])
    check(ub?.system == true && ub?.openable == true && NotifLogic.target(ub!) == .settings("backup"), "userbackup.failed -> Einstellungen")
    let ab = NotifItem(["id": 6, "kind": "backup.failed", "created": 1, "access": true, "system": true, "title": "Backups", "text": "x", "url": "http://127.0.0.1:8081/backups"])
    check(ab?.openable == true && NotifLogic.target(ab!) == .web("http://127.0.0.1:8081/backups"), "backup.failed -> Admin-Bereich")
    let an = NotifItem(["id": 7, "kind": "backup.failed", "created": 1, "access": true])
    check(an?.system == true && an?.openable == false && NotifLogic.target(an!) == .none, "ohne Adresse: nicht antippbar")
    check(NotifLogic.target(url: "/") == .none && NotifLogic.target(url: "/notes#12") == .page(id: 12, block: nil)
          && NotifLogic.target(url: "/?settings=x;y") == .none && NotifLogic.target(url: "javascript:alert(1)") == .none, "Ziele unveraendert/sicher")
    let share = NotifItem(["id": 8, "kind": "share.added", "created": 1, "access": true, "note": ["id": 3]])
    check(share?.system == false && share?.openable == true, "andere Arten unveraendert")
    check(CloudLogic.systemTitle("backup.failed") == "Backups" && CloudLogic.systemTitle("userbackup.failed") == "Mein Backup", "Titel")

    // --- Aeltere Server (2.1/2.2): Abschnitte ausblenden statt Fehler (Server-Wege mit nachgemachter Antwort)
    func run<T>(_ op: @escaping () async -> T) -> T {
        let sem = DispatchSemaphore(value: 0)
        let box = CloudTestBox<T>()
        Task.detached { box.v = await op(); sem.signal() }
        sem.wait()
        return box.v!
    }
    let old404: CloudAPI = { _, _, _ in (Data(#"{"detail": "Not Found"}"#.utf8), 404) }
    let oldHTML: CloudAPI = { _, _, _ in (Data("<html>Saga</html>".utf8), 200) }
    check(run { (try? await CloudFlow.backupStatus(old404)) ?? nil } == nil, "alter Server (404): Mein Backup ausblenden")
    check(run { (try? await CloudFlow.backupStatus(oldHTML)) ?? nil } == nil, "alter Server (keine JSON-Antwort): ausblenden")
    check(run { (try? await CloudFlow.ncSettings(old404)) ?? nil } == nil, "alter Server: Nextcloud ausblenden")
    check(run { await CloudFlow.noteStatus(old404, note: 1).failed }, "alter Server: Karte ohne Status")
    let fail: CloudAPI = { _, _, _ in (Data(#"{"error": "Abgeschaltet."}"#.utf8), 403) }
    let msg = run { () async -> String in
        do { try await CloudFlow.backupRun(fail); return "" } catch { return (error as? CloudFailure)?.message ?? "" }
    }
    check(msg == "Abgeschaltet.", "Fehlermeldung des Servers durchgereicht")
}

final class CloudTestBox<T>: @unchecked Sendable { var v: T?; init() {} }
