import Foundation

// Bugs-DB 2187: Mitteilung antippen -> Ziel. URLs genau wie der Server sie schickt (saga/push.py message(), saga/notify.py
// item(), saga/chat.py _notif, saga/tasksrc.py _render) und der Delegat ohne async-Varianten (Absturz beim Antippen).
func notifTapTests() {
    // Chat: Nachricht, Erwaehnung, verpasster Anruf (alle "/chats#c<id>")
    check(NotifLogic.target(url: "/chats#c12", kind: "chat.message") == .chat(id: 12), "2187: chat.message -> Chat")
    check(NotifLogic.target(url: "/chats#c7", kind: "chat.mention") == .chat(id: 7), "2187: chat.mention -> Chat")
    check(NotifLogic.target(url: "/chats#c3", kind: "call.missed") == .chat(id: 3), "2187: call.missed -> Chat")
    check(CallLogic.tapRoute(kind: "call.missed", url: "/chats#c3", call: nil, phase: "idle", minimized: false, callKit: false) == .open(minimizeFirst: false),
          "2187: verpasster Anruf oeffnet den Chat (kein Klingeln)")
    // Anruf-Mitteilungen bleiben beim Anruf-Weg
    check(CallLogic.tapRoute(kind: "call", url: "/chats#call-ab12cd34ef56ab12cd34ef56ab12cd34", call: "ab12cd34ef56ab12cd34ef56ab12cd34",
                             phase: "idle", minimized: false, callKit: false) == .call("ab12cd34ef56ab12cd34ef56ab12cd34"), "2187: Anruf bleibt Anruf")
    // Seite, Kommentar mit Block, Erwaehnung mit Block, Seite einer anderen Instanz (negative ID)
    check(NotifLogic.target(url: "/notes#5", kind: "share.added") == .page(id: 5, block: nil), "2187: share.added -> Seite")
    check(NotifLogic.target(url: "/notes#2:p30", kind: "comment.new") == .page(id: 2, block: "p30"), "2187: comment.new -> Seite + Block")
    check(NotifLogic.target(url: "/notes#9:x1-y", kind: "page.mention") == .page(id: 9, block: "x1-y"), "2187: page.mention -> Block")
    check(NotifLogic.target(url: "/notes#-2049:b3", kind: "comment.reply") == .page(id: -2049, block: "b3"), "2187: foederierte Seite")
    // Aufgaben-Erinnerung: Server schickt nur "/" -> Aufgaben
    check(NotifLogic.target(url: "/", kind: "task.due") == .tasks, "2187: task.due -> Aufgaben")
    let due = NotifItem(["id": 1, "kind": "task.due", "title": "Aufgaben", "text": "Heute faellig: 2 Aufgaben", "url": "/", "access": true])
    check(due?.openable == true && due.map(NotifLogic.target) == .tasks, "2187: Glocke task.due antippbar -> Aufgaben")
    // ohne Ziel: App nur oeffnen
    check(NotifLogic.target(url: "/", kind: "test") == .none && NotifLogic.target(url: "/security", kind: "login.locked") == .none
          && NotifLogic.target(url: nil, kind: "chat.message") == .none, "2187: ohne Ziel -> nur oeffnen")
    // Absolute Adressen (fremder Server) nie als Seite/Chat dieses Kontos
    check(NotifLogic.target(url: "https://example.org/x", kind: "comment.new") == .none, "2187: absolute Adresse -> kein Ziel")

    // Abholen: userInfo einer APNs-Mitteilung (Zahlen als NSNumber, verschachtelt "aps") -> nur einfache Werte bleiben
    let info: [AnyHashable: Any] = ["aps": ["alert": ["title": "Saga"]], "url": "/chats#c12", "kind": "chat.message", "chat": NSNumber(value: 12), "n": 2]
    let p = PushLogic.plainInfo(info)
    check(p["aps"] == nil && p["url"] as? String == "/chats#c12" && (p["chat"] as? NSNumber)?.intValue == 12 && p.count == 4, "2187: userInfo vereinfacht")
    check(PushLogic.tapTarget(p) == .chat(id: 12), "2187: Antippen Chat-Mitteilung -> Chat 12")
    check(PushLogic.tapTarget(["url": "/notes#2:p30", "kind": "comment.new"]) == .page(id: 2, block: "p30"), "2187: Antippen Kommentar -> Seite")
    check(PushLogic.tapTarget(["kind": "chat.message"]) == .none, "2187: ohne url -> nur oeffnen")

    // Regression: keine async-Varianten der Mitteilungs-Delegaten (UIKit beendet die App, wenn der completionHandler
    // nicht auf dem Haupt-Faden kommt - genau das tat die async-Variante)
    if let dir = ProcessInfo.processInfo.environment["SAGA_APP_SRC"] {
        for f in ["/Push.swift", "/../Shell/ShellPush.swift"] {
            guard let src = try? String(contentsOfFile: dir + f, encoding: .utf8) else { check(false, "2187: \(f) fehlt"); continue }
            let lines = src.split(separator: "\n").filter { $0.contains("func userNotificationCenter(") }
            check(!lines.isEmpty && lines.allSatisfy { !$0.contains(") async") }, "2187: \(f) ohne async-Delegat")
            check(src.contains("didReceive") && src.contains("withCompletionHandler"), "2187: \(f) mit completionHandler")
        }
    } else { check(false, "SAGA_APP_SRC fehlt") }
}
