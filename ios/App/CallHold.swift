import SwiftUI
import UserNotifications

// 2.6 (Bugs-DB 2029-6): Anklopfen und zurueckgehaltene Anrufe (Vertrag docs/APP-API.md "Anrufe und eigener Status",
// Vorbild static/calls.js holdBar/holdTake). Das Geraet meldet "waiting1" in PUT /api/calls/caps erst, wenn der Server
// es kann (CallV3.myCaps). Dann kommt ein zweiter Anruf waehrend eines Gespraechs als call.ring/call.invite mit
// waiting: true - KEIN Klingeln und KEIN zweiter CallKit-Anruf, sondern eine Leiste in der App ("{name} klopft an":
// Ablehnen = decline busy, "Beenden und annehmen" erst nach bestaetigtem /end). Waehrend eines CallKit-Gespraechs
// ist die App ohnehin aktiv bzw. per Audio-Hintergrund am Leben; ein zweiter CallKit-Anruf wuerde das System-Halten
// anbieten, das Saga nicht kann (nur ein Gespraech je Geraet). call.quiet (eigenes Nicht stoeren/Ruhezeit) -> stille
// Leiste "Anruf von {name} - stumm" mit Annehmen (nicht, wenn das eigene Gespraech auf einem anderen Geraet laeuft).
// Zurueckgehaltene Anrufe kommen nie als VoIP-Push und werden nie an CallKit gemeldet. Logik/Tests: UserStatusLogic.swift.

struct CallHold: Identifiable, Equatable {
    let id: String
    var mode: CallHoldLogic.Mode
    var chatID: Int
    var media: String
    var name: String
    var avatar: String?
    /// 1:1 ueber Knoten (Raum)
    var room = false
    /// Grund bei quiet: dnd | quiet (Ruhezeit)
    var why = "dnd"
    /// laufendes Gespraech auf diesem Geraet (Leiste mit "Beenden und annehmen")
    var here = false
    /// eigenes Gespraech laeuft auf einem anderen Geraet (kein Annehmen)
    var elsewhere = false
    var shownAt: Double
    var expiresIn: Double?
    var ringS: Double?
    var video: Bool { media == "video" }
    var label: String {
        mode == .wait ? String(localized: "\(name) klopft an")
            : why == "quiet" ? String(localized: "Anruf von \(name) – stumm (Ruhezeit)") : String(localized: "Anruf von \(name) – stumm (Nicht stören)")
    }
}

extension CallManager {
    /// Anruf-Objekt pruefen: klopft an bzw. zurueckgehalten -> Leiste statt Klingeln (true = erledigt)
    func holdCheck(_ c: [String: Any], cid: String, freed: Bool) async -> Bool {
        let room = (c["kind"] as? String) == "group"
        let ringingNow = room ? (c["invite"] as? String) == "ringing" && (c["state"] as? String) != "ended"
                              : (c["state"] as? String) == "ringing" && (c["role"] as? String) == "callee"
        guard ringingNow else { return false }
        switch CallHoldLogic.ring(sameCallActive: incoming?.id == cid || id == cid, busy: busy, waiting: ShareJSON.bool(c["waiting"]),
                                  quiet: ShareJSON.bool(c["quiet"]), freed: freed) {
        case .knock:
            CallSystem.shared.endReported(cid, reason: .unanswered)        // falls doch gemeldet: sofort weg
            await holdIn(cid, mode: .wait, call: c); return true
        case .quiet:
            CallSystem.shared.endReported(cid, reason: .unanswered)
            await holdIn(cid, mode: .quiet, call: c); return true
        default: return false
        }
    }

    /// Leiste zeigen (call.ring/call.invite mit waiting, call.quiet, Abfrage). event: Live-Daten; call: Anruf-Objekt
    func holdIn(_ cid: String, mode: CallHoldLogic.Mode, event: [String: Any] = [:], call: [String: Any]? = nil, keep: CallHold? = nil) async {
        guard CallLogic.isCallID(cid), !holds.contains(where: { $0.id == cid }), !holdGone.contains(cid), incoming?.id != cid, id != cid, let store else { return }
        var c = call
        if c == nil, let (d, code) = try? await store.apiResponse("api/calls/\(cid)"), code == 200 { c = ShareJSON.obj(d)?["call"] as? [String: Any] }
        if let c, CallHoldLogic.pollGone(state: c["state"] as? String), (c["kind"] as? String) != "group" { return }
        guard !holds.contains(where: { $0.id == cid }), !holdGone.contains(cid), incoming?.id != cid, id != cid else { return }
        let x = c ?? [:]
        let from = x["peer"] as? [String: Any] ?? x["from"] as? [String: Any] ?? event["from"] as? [String: Any] ?? [:]
        let who = ShareJSON.str(from["name"]) ?? keep?.name ?? ""
        var h = CallHold(id: cid, mode: mode, chatID: ShareJSON.int(x["chat_id"]) ?? ShareJSON.int(event["chat_id"]) ?? keep?.chatID ?? 0,
                         media: ((x["media"] ?? event["media"]) as? String) == "video" ? "video" : (keep?.media ?? "audio"),
                         name: PersonName.shown(who, id: ShareJSON.str(from["handle"]) ?? from["username"] as? String),
                         avatar: ShareJSON.str(from["avatar"]) ?? keep?.avatar,
                         room: (x["kind"] as? String) == "group" || ShareJSON.bool(event["dm"]),
                         why: ShareJSON.str(event["why"]) ?? keep?.why ?? "dnd",
                         shownAt: Date().timeIntervalSince1970,
                         expiresIn: keep.map { CallHoldLogic.left(expiresIn: $0.expiresIn, ringS: $0.ringS, shownAt: $0.shownAt, now: Date().timeIntervalSince1970) }
                            ?? ShareJSON.num(event["expires_in"]) ?? ShareJSON.num(x["expires_in"]),
                         ringS: ShareJSON.num(event["ring_s"]) ?? ShareJSON.num(x["ring_s"]))
        h.here = busy && CallLogic.isOngoing(phase.rawValue)
        if mode == .wait { h.elsewhere = !h.here }
        else if !h.here, let (d, code) = try? await store.apiResponse("api/calls/active"), code == 200 {
            h.elsewhere = CallHoldLogic.busyElsewhere(ShareJSON.obj(d)?["calls"] as? [[String: Any]] ?? [], except: cid, current: id)
        }
        guard !holds.contains(where: { $0.id == cid }), !holdGone.contains(cid) else { return }
        holds.append(h)
        CallEnv.log("hold \(mode.rawValue) \(cid.prefix(8)) here=\(h.here)")
        if mode == .wait && h.here { CallTones.shared.knock() }
        #if os(iOS)
        if h.here { UIAccessibility.post(notification: .announcement, argument: h.label) }   // das Gespraech behaelt den Fokus
        if h.here, UIApplication.shared.applicationState != .active { Self.holdNotice(h) }  // Bildschirm gesperrt / CallKit vorne
        #endif
        holdWatch(h)
    }

    /// 2.6: Live-Kanal im Hintergrund der Regel CallHoldLogic.liveWanted nachfuehren (bei jedem Wechsel der Phase und
    /// beim Wechsel in den Hintergrund): Gespraech verbunden (auch CallKit-Annehmen bei gesperrtem Geraet) -> offen,
    /// Gespraech vorbei -> zu. Im Vordergrund steuert RootView den Kanal wie bisher. Mac: unveraendert.
    func liveFollow(background: Bool? = nil) {
        #if os(iOS)
        guard let store, background ?? (UIApplication.shared.applicationState == .background) else { return }
        if !store.needsLogin, CallHoldLogic.liveWanted(foreground: false, phase: phase.rawValue) { store.live.start() } else { store.live.stop() }
        #endif
    }

    #if os(iOS)
    /// Gespraech laeuft im Hintergrund (CallKit vorne, Bildschirm gesperrt): stille Mitteilung "{name} klopft an" - Tippen
    /// holt die App mit der Leiste nach vorn (kein zweiter CallKit-Anruf, der Ton kam schon)
    static func holdNotice(_ h: CallHold) {
        let c = UNMutableNotificationContent()
        c.title = h.label
        c.body = h.mode == .wait ? String(localized: "Anruf – dein Gespräch läuft weiter") : (h.why == "quiet" ? String(localized: "stumm (Ruhezeit)") : String(localized: "stumm (Nicht stören)"))
        c.interruptionLevel = .active
        c.userInfo = ["kind": "call.hold", "call": h.id]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "hold-" + h.id, content: c, trigger: nil))
    }
    #endif
    /// Ende-Ereignis kann fehlen: alle 5 s nachsehen, Zeitgrenze wie beim Klingeln
    private func holdWatch(_ h: CallHold) {
        holdTasks[h.id]?.cancel()
        let limit = CallHoldLogic.lifetime(expiresIn: h.expiresIn, ringS: h.ringS)
        holdTasks[h.id] = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard let self, !Task.isCancelled, self.holds.contains(where: { $0.id == h.id }) else { return }
                if Date().timeIntervalSince1970 - h.shownAt > limit { self.holdDrop(h.id); return }
                guard let r = try? await self.store?.apiResponse("api/calls/\(h.id)") else { continue }
                if r.1 == 404 { self.holdDrop(h.id); return }
                guard r.1 == 200, let c = ShareJSON.obj(r.0)?["call"] as? [String: Any] else { continue }
                let st = c["state"] as? String
                let gone = h.room ? (st == "ended" || ((c["invite"] as? String).map { $0 != "ringing" } ?? false)) : CallHoldLogic.pollGone(state: st)
                if gone { self.holdDrop(h.id); return }
            }
        }
    }
    func holdDrop(_ cid: String) {
        holdTasks[cid]?.cancel(); holdTasks[cid] = nil
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["hold-" + cid])
        holdGone.insert(cid)
        if holds.contains(where: { $0.id == cid }) { holds.removeAll { $0.id == cid } }
    }
    /// Gespraech hier zu Ende: Anklopfen klingelt jetzt normal, stille Leiste bleibt still (Restzeit)
    func holdFree() {
        let mine = holds.filter(\.here)
        guard !mine.isEmpty else { return }
        for h in mine {
            holdTasks[h.id]?.cancel(); holdTasks[h.id] = nil
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["hold-" + h.id])   // "klopft an" gehoert zum Gespraech
            holds.removeAll { $0.id == h.id }
            switch CallHoldLogic.free(mode: h.mode, left: CallHoldLogic.left(expiresIn: h.expiresIn, ringS: h.ringS, shownAt: h.shownAt, now: Date().timeIntervalSince1970)) {
            case .drop: holdGone.insert(h.id)
            case .ring: Task { await ringing(callID: h.id, freed: true) }
            case .quiet: Task { await holdIn(h.id, mode: .quiet, keep: h) }
            }
        }
    }

    // MARK: Bedienung

    /// Anklopfen ablehnen = besetzt
    func holdDecline(_ h: CallHold) {
        holdDrop(h.id)
        Task { _ = try? await store?.apiResponse("api/calls/\(h.id)/decline", method: "POST", body: ["reason": "busy"]) }
    }
    /// Stille Leiste nur hier ausblenden (kein Ablehnen)
    func holdHide(_ h: CallHold) { holdDrop(h.id) }
    /// Stille Leiste: annehmen (nichts laeuft hier, nicht woanders)
    func holdAccept(_ h: CallHold) {
        holdDrop(h.id)
        Task { await ringing(callID: h.id, take: true) }
    }
    /// "Beenden und annehmen": laufendes Gespraech auflegen, ERST nach bestaetigtem Ende annehmen - sonst liefen zwei
    /// Gespraeche; nicht bestaetigt: nicht annehmen, der wartende Anruf klingelt wieder normal
    func holdTake(_ h: CallHold) {
        holdDrop(h.id)
        Task {
            if await hangupConfirmed() { await ringing(callID: h.id, take: true); return }
            toast = String(localized: "Das Gespräch ließ sich nicht sicher beenden – der Anruf wurde nicht angenommen. Bitte erneut versuchen.")
            holdGone.remove(h.id)
            await ringing(callID: h.id, freed: true)
        }
    }
    /// Auflegen und auf die Antwort des Servers warten (2xx bzw. schon vorbei = bestaetigt)
    func hangupConfirmed() async -> Bool {
        guard busy || phase == .preparing else { return true }
        guard let id, !ending else { hangup(); return true }
        let c = connected, bye = ready ? byes() : [:], dev = myDevice, store = self.store
        finish(.ended(dur: connectedAt.map { Int(Date().timeIntervalSince($0)) }))   // Fenster sofort zu
        if !bye.isEmpty, let dev { _ = try? await store?.apiResponse("api/calls/\(id)/signal", method: "POST", body: ["device": dev, "signals": bye]) }
        var b: [String: Any] = ["reason": "hangup", "connected": c]
        if let dev { b["device"] = dev }
        let r = try? await store?.apiResponse("api/calls/\(id)/end", method: "POST", body: b)
        return CallHoldLogic.endConfirmed(r?.1)
    }
    /// Aus der Leiste angenommen: klingelt nicht, sofort annehmen (iPhone/iPad ueber CallKit - nur so wird die
    /// Audio-Session aktiv)
    func answerTaken(_ cid: String) {
        guard incoming?.id == cid else { return }
        CallTones.shared.stop()
        #if os(iOS)
        if CallEnv.callKit {
            Task {
                try? await Task.sleep(for: .milliseconds(300))           // CallKit hat den Anruf gerade erst aufgenommen
                guard incoming?.id == cid else { return }
                if !CallSystem.shared.requestAnswer(cid) { answer(video: media == "video") }
            }
            return
        }
        #endif
        answer(video: media == "video")
    }

    #if DEBUG
    /// Nur fuer Bilder: -hkStatusPreview knock|knock-elsewhere|quiet|quiet-elsewhere (erfundene Leiste, kein Server)
    func statusPreview() {
        guard let m = UserDefaults.standard.string(forKey: "hkStatusPreview"), holds.isEmpty else { return }
        let here = m == "knock" || (m == "quiet" && busy)
        holds = [CallHold(id: String(repeating: "a", count: 32), mode: m.hasPrefix("knock") ? .wait : .quiet, chatID: 1, media: "audio",
                          name: "Bruno Beispiel", avatar: nil, why: "dnd", here: here, elsewhere: m.hasSuffix("elsewhere"),
                          shownAt: Date().timeIntervalSince1970 + 3600, expiresIn: 45, ringS: 45)]
    }
    #endif
}

// MARK: - Leisten

/// Anklopf- und stille Leisten: im Anruf-Vollbild (oben) bzw. ueber der App (bei verkleinertem Anruf oder ohne Gespraech)
struct CallHoldBars: View {
    @ObservedObject var calls: CallManager
    /// true: im Anruf-Vollbild (dunkler Grund)
    var onCall = false
    var body: some View {
        if !calls.holds.isEmpty {
            VStack(spacing: 8) {
                ForEach(calls.holds) { h in CallHoldBar(h: h, calls: calls, onCall: onCall) }
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }
}

struct CallHoldBar: View {
    let h: CallHold
    @ObservedObject var calls: CallManager
    var onCall = false
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) { avatar; texts; Spacer(minLength: 4); buttons }
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) { avatar; texts }
                HStack { Spacer(minLength: 0); buttons }
            }
        }
        .padding(12)
        // im Anruf deckend (liegt ueber Buehne und Teilnehmerliste - durchscheinend war sie dort unlesbar)
        .background(onCall ? AnyShapeStyle(Color(red: 0.17, green: 0.16, blue: 0.15)) : AnyShapeStyle(.regularMaterial), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(h.mode == .wait ? Color.gold : StatusDot.color("dnd"), lineWidth: 1.5))
        .environment(\.colorScheme, onCall ? .dark : colorScheme)
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)                 // sehr grosse Schrift: Leiste bleibt ueberschaubar
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(verbatim: h.label))
    }
    @Environment(\.colorScheme) private var colorScheme
    private var avatar: some View {
        AvatarView(name: h.name, avatar: h.avatar, size: 40).accessibilityHidden(true)
            .overlay(alignment: .bottomTrailing) { if h.mode == .quiet { StatusDot(state: "dnd", size: 12) } }
    }
    @ViewBuilder private var texts: some View {
        VStack(alignment: .leading, spacing: 2) {
            if h.mode == .wait {
                Text(verbatim: h.label).font(.subheadline.weight(.semibold)).lineLimit(2)
                Text(verbatim: h.here ? (h.video ? String(localized: "Videoanruf – dein Gespräch läuft weiter") : String(localized: "Anruf – dein Gespräch läuft weiter"))
                                      : String(localized: "Du telefonierst gerade auf einem anderen Gerät."))
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            } else {
                Text(verbatim: h.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(verbatim: (h.video ? String(localized: "Videoanruf") : String(localized: "Anruf")) + " · "
                     + (h.why == "quiet" ? String(localized: "stumm (Ruhezeit)") : String(localized: "stumm (Nicht stören)")))
                    .font(.caption).foregroundStyle(.secondary)
                if !h.here && h.elsewhere {
                    Text("Du telefonierst gerade auf einem anderen Gerät.").font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
    @ViewBuilder private var buttons: some View {
        HStack(spacing: 8) {
            if h.mode == .wait {
                Button(String(localized: "Ablehnen")) { calls.holdDecline(h) }
                    .buttonStyle(.bordered).tint(.red).frame(minHeight: 44)
            } else {
                Button { calls.holdHide(h) } label: { Image(systemName: "xmark").frame(width: 28, height: 28) }
                    .buttonStyle(.bordered).frame(minHeight: 44)
                    .accessibilityLabel(Text("Hinweis ausblenden"))
            }
            if h.here {
                Button(String(localized: "Beenden und annehmen")) { calls.holdTake(h) }
                    .buttonStyle(.borderedProminent).tint(CallBar.green).frame(minHeight: 44)
                    .accessibilityLabel(Text("Aktuelles Gespräch beenden und den Anruf von \(h.name) annehmen"))
            } else if h.mode == .quiet && !h.elsewhere {
                Button(String(localized: "Annehmen")) { calls.holdAccept(h) }
                    .buttonStyle(.borderedProminent).tint(CallBar.green).frame(minHeight: 44)
                    .accessibilityLabel(Text("Anruf von \(h.name) annehmen"))
            }
        }
        .controlSize(.small)
    }
}
