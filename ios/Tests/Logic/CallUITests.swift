import Foundation

// Anrufe 2.3.1: Anruf verkleinern ("zurueck zur App"), Leiste oben, Hinweis im Chat, Mitteilungen waehrend eines Anrufs
func callUITests() {
    typealias L = CallLogic
    controlsHideTests()
    selfPipSizeTests()
    videoFillTests()
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
    // 4.11.: Mitteilung des offenen Chats im Vordergrund nicht zeigen - andere Chats und andere Arten schon
    check(NotifLogic.hideWhileOpen(kind: "chat.message", url: "/chats#c12", openChat: 12), "offener Chat: kein Banner")
    check(NotifLogic.hideWhileOpen(kind: "chat.mention", url: "/chats#12", openChat: 12), "Erwaehnung im offenen Chat: kein Banner")
    check(!NotifLogic.hideWhileOpen(kind: "chat.message", url: "/chats#c13", openChat: 12), "anderer Chat: Banner")
    check(!NotifLogic.hideWhileOpen(kind: "chat.message", url: "/chats#c12", openChat: nil), "kein Chat offen / App hinten: Banner")
    check(!NotifLogic.hideWhileOpen(kind: "call", url: "/chats#call-ab12", openChat: 12), "Anruf: nie unterdruecken")
    // 4.11.: Zoomen in der Bildansicht
    check(ZoomLogic.clamp(0.5) == 1 && ZoomLogic.clamp(3) == 3 && ZoomLogic.clamp(20) == ZoomLogic.maxScale, "Zoom 1- bis 6-fach")
    let zb = ZoomLogic.bounded(CGSize(width: 500, height: -500), scale: 2, size: CGSize(width: 400, height: 300))
    check(zb.width == 200 && zb.height == -150, "Verschieben bleibt im Bild (\(zb))")
    check(ZoomLogic.bounded(CGSize(width: 50, height: 50), scale: 1, size: CGSize(width: 400, height: 300)) == .zero, "ohne Zoom kein Verschieben")

    // Chat oeffnen: Bereich wechseln und Chat nicht im selben Schritt
    check(L.chatOpenNeedsTwoSteps(chatsVisible: false), "Chats nicht vorne: zwei Schritte")
    check(!L.chatOpenNeedsTwoSteps(chatsVisible: true), "Chats vorne: direkt")
}

// 2.5: Kopf und Knoepfe automatisch ausblenden (blieben bisher den ganzen Anruf sichtbar)
private func controlsHideTests() {
    typealias L = CallLogic
    func h(_ idle: Double, phase: String = "active", video: Bool = true, vo: Bool = false, menu: Bool = false, notice: Bool = false) -> Bool {
        L.hideControls(idle: idle, phase: phase, video: video, voiceOver: vo, menuOpen: menu, notice: notice)
    }
    check(!h(0) && !h(L.controlsIdle - 0.1), "kurz nach Bedienung: sichtbar")
    check(h(L.controlsIdle) && h(60), "ab controlsIdle ohne Bedienung: ausblenden")
    check(!h(60, video: false), "nur Ton: nie ausblenden")
    check(!h(60, vo: true), "VoiceOver: nie ausblenden")
    check(!h(60, menu: true), "offenes Menue/Hinweis: nicht ausblenden")
    check(!h(60, notice: true), "wichtige Meldung: nicht ausblenden")
    for p in ["preparing", "ringingOut", "connecting", "incoming", "ended", "idle"] { check(!h(60, phase: p), "\(p): nicht ausblenden") }
}

// 2.5: Rahmen des eigenen Bilds folgt dem Seitenverhaeltnis des eigenen Videos (Mac quer, iPhone hoch)
private func selfPipSizeTests() {
    typealias L = CallLogic
    func r(_ x: (w: Double, h: Double)) -> Double { x.w / x.h }
    let mac = L.selfPipSize(aspect: 16.0 / 9, fallback: 16.0 / 9, width: 900, height: 700)
    check(mac.w > mac.h && abs(r(mac) - 16.0 / 9) < 0.02, "Mac-Kamera quer: breiter Rahmen im Verhaeltnis 16:9")
    check(mac.w == 198, "Mac 900 breit: 22 % = 198")
    check(L.selfPipSize(aspect: 16.0 / 9, fallback: 1, width: 3000, height: 2000).w == 280, "sehr breites Fenster: hoechstens 280")
    check(L.selfPipSize(aspect: 16.0 / 9, fallback: 1, width: 300, height: 2000).w == 120, "schmales Fenster: mindestens 120")
    let phone = L.selfPipSize(aspect: 9.0 / 16, fallback: 9.0 / 16, width: 393, height: 600)
    check(phone.h > phone.w && abs(r(phone) - 9.0 / 16) < 0.02, "iPhone hoch: hoher Rahmen 9:16")
    check(phone.w >= 90 && phone.w <= 160, "iPhone: Breite in den Grenzen")
    let pad = L.selfPipSize(aspect: 4.0 / 3, fallback: 3.0 / 4, width: 1180, height: 700)
    check(pad.w > pad.h, "iPad quer gedreht: Rahmen wird breit")
    let none = L.selfPipSize(aspect: nil, fallback: 16.0 / 9, width: 900, height: 700)
    check(none == mac, "noch kein Bild (Kamera aus/startet): Rueckfall-Verhaeltnis")
    check(r(L.selfPipSize(aspect: 10, fallback: 1, width: 900, height: 700)) <= 2.01, "unsinniges Verhaeltnis begrenzt")
    check(r(L.selfPipSize(aspect: .nan, fallback: 16.0 / 9, width: 900, height: 700)) > 1.7, "NaN: Rueckfall")
    let low = L.selfPipSize(aspect: 9.0 / 16, fallback: 1, width: 393, height: 200)
    check(low.h <= 100 && abs(r(low) - 9.0 / 16) < 0.03, "wenig Hoehe (quer): kleiner im gleichen Verhaeltnis (halbe Hoehe)")
    // Bugs-DB 2161: Breitenregel nach der Kamera, nicht nach der Flaeche
    let turned = L.selfPipSize(aspect: 4.0 / 3, fallback: 9.0 / 16, width: 390, height: 844)
    check(turned.w == 120 && turned.h == 90, "iPhone hochkant, Kamera quer (gedreht): Regel quer 120x90 statt 101x76")
    let portraitCam = L.selfPipSize(aspect: 9.0 / 16, fallback: 16.0 / 9, width: 1280, height: 720)
    check(portraitCam.w == 160 && portraitCam.h == 284, "Mac-Fenster, Kamera hochkant: Regel hochkant 160x284")
    check(L.selfPipSize(aspect: 16.0 / 9, fallback: 1, width: 900, height: 700, factor: 1.4).w == 277, "Faktor L vor dem Einpassen")
    check(L.videoAspect(width: 1280, height: 720) == 1280.0 / 720 && L.videoAspect(width: 0, height: 720) == nil, "Verhaeltnis aus Videogroesse")
}

// 2.5 Feldfall Drehen: Fernbild auf der Buehne - gleiche Ausrichtung fuellt, gedrehte Seite zeigt das ganze Bild
private func videoFillTests() {
    typealias L = CallLogic
    // iPhone hoch (393x852), Android hoch per CVO (1280x720 rotation 90 -> gemeldet 720x1280)
    check(L.videoFill(video: 720, 1280, box: 393, 852), "beide hoch: fuellen (kaum Zuschnitt)")
    // Android quer gedreht, iPhone hoch: frueher nur ~26 % der Breite sichtbar
    check(!L.videoFill(video: 1280, 720, box: 393, 852), "Android quer, iPhone hoch: ganz zeigen")
    // iPhone quer gedreht (852x393), Android hoch
    check(!L.videoFill(video: 720, 1280, box: 852, 393), "iPhone quer, Android hoch: ganz zeigen")
    check(L.videoFill(video: 1280, 720, box: 852, 393), "beide quer: fuellen")
    // Bandbreiten-Stufen (540p, 405p, 360p, 270p, 203p) aendern das Verhaeltnis nicht
    for h in [540.0, 405, 360, 270, 203] {
        let w = (h * 16 / 9).rounded()
        check(L.videoFill(video: h, w, box: 393, 852), "\(Int(h))p hoch auf iPhone hoch: fuellen")
        check(!L.videoFill(video: w, h, box: 393, 852), "\(Int(h))p quer auf iPhone hoch: ganz zeigen")
    }
    // iPad quer (1180x820) mit Mac-Kamera 16:9: fuellen; iPad hoch mit 16:9: ganz zeigen
    check(L.videoFill(video: 1280, 720, box: 1180, 820), "iPad quer + 16:9: fuellen")
    check(!L.videoFill(video: 1280, 720, box: 820, 1180), "iPad hoch + 16:9 quer: ganz zeigen")
    // 4:3 hoch auf iPhone hoch: 0,61 bleibt -> fuellen
    check(L.videoFill(video: 480, 640, box: 393, 852), "4:3 hoch auf iPhone hoch: fuellen")
    // Pruefer pruef-apps-abend: Schwelle nach unten festhalten (0,52 bzw. 0,46 -> ganz zeigen)
    check(!L.videoFill(video: 640, 480, box: 820, 1180), "4:3 quer auf iPad hoch: ganz zeigen")
    check(!L.videoFill(video: 720, 720, box: 393, 852), "quadratisch auf iPhone hoch: ganz zeigen")
    check(L.videoFill(video: 720, 720, box: 820, 1180), "quadratisch auf iPad hoch: fuellen")
    // unbekannt (noch kein Bild) bzw. Unsinn: fuellen wie bisher
    check(L.videoFill(video: 0, 0, box: 393, 852), "noch keine Groesse: fuellen")
    check(L.videoFill(video: .nan, 720, box: 393, 852), "NaN: fuellen")
    check(L.videoFill(video: 720, 1280, box: 0, 0), "Kachel ohne Groesse: fuellen")
}
