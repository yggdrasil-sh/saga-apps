import Foundation

// Anrufe 2.3.1: Anruf verkleinern ("zurueck zur App"), Leiste oben, Hinweis im Chat, Mitteilungen waehrend eines Anrufs
func callUITests() {
    typealias L = CallLogic
    let cid = String(repeating: "ab", count: 16)

    // Vollbild: laeuft und nicht verkleinert -> ja; verkleinert -> nein (Anruf laeuft weiter)
    for p in ["preparing", "ringingOut", "connecting", "active"] {
        check(L.isOngoing(p), "\(p): laufender Anruf")
        check(L.showsFullScreen(phase: p, minimized: false, callKit: true), "\(p): Vollbild")
        check(!L.showsFullScreen(phase: p, minimized: true, callKit: true), "\(p) verkleinert: kein Vollbild")
        check(L.showsCallBar(phase: p, minimized: true, separateWindow: false), "\(p) verkleinert: Leiste")
        check(!L.showsCallBar(phase: p, minimized: false, separateWindow: false), "\(p) Vollbild: keine Leiste")
        check(L.showsCallBar(phase: p, minimized: false, separateWindow: true), "\(p) Mac: Leiste immer")
    }
    check(!L.isOngoing("idle") && !L.isOngoing("ended") && !L.isOngoing("incoming"), "idle/ended/incoming: kein laufender Anruf")
    check(!L.showsFullScreen(phase: "idle", minimized: false, callKit: true), "idle: nichts")
    check(!L.showsFullScreen(phase: "incoming", minimized: false, callKit: true), "Klingeln mit CallKit: System-Oberflaeche")
    check(L.showsFullScreen(phase: "incoming", minimized: true, callKit: false), "Klingeln ohne CallKit: immer zeigen")
    check(L.showsFullScreen(phase: "ended", minimized: false, callKit: true), "Ende im Vollbild: Hinweis im Vollbild")
    check(!L.showsFullScreen(phase: "ended", minimized: true, callKit: true), "Ende verkleinert: Vollbild springt nicht auf")
    check(!L.showsCallBar(phase: "ended", minimized: true, separateWindow: false), "Ende: Leiste weg")
    check(!L.showsCallBar(phase: "idle", minimized: true, separateWindow: true), "idle: keine Leiste")

    // 2.5: Knopfreihe - iPhone (378 pt, 5 Knoepfe) 72 pt je Knopf, Mac breit 84 pt, sehr schmal mindestens 60 pt
    check(L.controlFit(count: 5, width: 378) == (4, 72), "iPhone: fuenf Knoepfe je 72 pt")
    check(L.controlFit(count: 4, width: 1000) == (18, 84), "Mac: breit, 84 pt")
    check(L.controlFit(count: 5, width: 300).width == 60, "sehr schmal: mindestens 60 pt")
    check(L.controlFit(count: 5, width: 0) == (18, 72), "noch nicht gemessen: Vorgabe")
    for n in 3...5 { let f = L.controlFit(count: n, width: 420); check(f.width * Double(n) + f.spacing * Double(n - 1) <= 420, "\(n) Knoepfe passen in 420 pt") }

    // Hinweis im Chat: nur im Chat des laufenden Anrufs
    check(L.chatHasCall(phase: "active", callChat: 7, chatID: 7), "Chat des Anrufs: Hinweis")
    check(!L.chatHasCall(phase: "active", callChat: 7, chatID: 8), "anderer Chat: kein Hinweis")
    check(!L.chatHasCall(phase: "ended", callChat: 7, chatID: 7), "beendet: kein Hinweis")
    check(!L.chatHasCall(phase: "active", callChat: 0, chatID: 0), "ohne Chat: kein Hinweis")

    // Mitteilung angetippt
    check(L.tapRoute(kind: "call", url: nil, call: cid, phase: "idle", minimized: false, callKit: true) == .call(cid), "Anruf-Mitteilung: Anruf")
    check(L.tapRoute(kind: nil, url: "/chats#call-\(cid)", call: nil, phase: "idle", minimized: false, callKit: true) == .call(cid), "Anruf per Adresse")
    check(L.tapRoute(kind: "call", url: nil, call: "kaputt", phase: "idle", minimized: false, callKit: true) == .open(minimizeFirst: false), "Anruf ohne gueltige Kennung: kein Anruf")
    // Chat-Nachricht WAEHREND eines Anrufs: nie den Anruf anfassen; im Vollbild erst verkleinern, dann navigieren
    check(L.tapRoute(kind: "chat.message", url: "/chats#c12", call: nil, phase: "active", minimized: false, callKit: true) == .open(minimizeFirst: true),
          "Chat-Mitteilung im Anruf (Vollbild): erst verkleinern")
    check(L.tapRoute(kind: "chat.message", url: "/chats#c12", call: nil, phase: "active", minimized: true, callKit: true) == .open(minimizeFirst: false),
          "Chat-Mitteilung im Anruf (verkleinert): gleich oeffnen")
    check(L.tapRoute(kind: "chat.message", url: "/chats#c12", call: nil, phase: "connecting", minimized: false, callKit: true) == .open(minimizeFirst: true),
          "Chat-Mitteilung beim Verbinden: erst verkleinern")
    check(L.tapRoute(kind: "chat.message", url: "/chats#c12", call: nil, phase: "ended", minimized: false, callKit: true) == .open(minimizeFirst: true),
          "Chat-Mitteilung waehrend Endhinweis: erst schliessen")
    check(L.tapRoute(kind: "chat.message", url: "/chats#c12", call: nil, phase: "idle", minimized: false, callKit: true) == .open(minimizeFirst: false),
          "ohne Anruf: gleich oeffnen")
    check(L.tapRoute(kind: "comment.new", url: "/notes#5", call: nil, phase: "active", minimized: false, callKit: true) == .open(minimizeFirst: true),
          "Kommentar im Anruf: erst verkleinern")
    check(NotifLogic.target(url: "/chats#c12", kind: "chat.message") == .chat(id: 12), "Chat-Mitteilung -> Chat 12")

    // Chat oeffnen: Bereich wechseln und Chat nicht im selben Schritt
    check(L.chatOpenNeedsTwoSteps(chatsVisible: false), "Chats nicht vorne: zwei Schritte")
    check(!L.chatOpenNeedsTwoSteps(chatsVisible: true), "Chats vorne: direkt")
}
