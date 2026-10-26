import SwiftUI
import AVFoundation
import UserNotifications
import WebRTC
#if os(iOS)
import CallKit
import PushKit
#else
import AppKit
#endif

// Anrufe (Saga 2.1) und das System: iOS/iPadOS ueber CallKit (Klingeln, Sperrbildschirm, Anrufliste, Audio-Session) und
// PushKit-VoIP (Abo-Typ "apns-voip"; jede VoIP-Mitteilung wird SOFORT ein CallKit-Anruf, sonst sperrt iOS VoIP-Pushes).
// macOS: kein CallKit/PushKit-VoIP -> Klingelton, eigenes Anruf-Fenster (CallViews.swift) und Mitteilung mit
// "Annehmen"/"Ablehnen", solange die App laeuft; sonst normale APNs-Mitteilung (Art "call").

@MainActor
final class CallSystem: NSObject {
    static let shared = CallSystem()
    weak var manager: CallManager? { didSet { if manager != nil { drainPending() } } }
    enum EndReason { case failed, remoteEnded, unanswered, answeredElsewhere, declinedLocally }
    nonisolated static let category = "SAGA_CALL"
    nonisolated static let answerAction = "SAGA_CALL_ANSWER"
    nonisolated static let declineAction = "SAGA_CALL_DECLINE"
    /// Push/Antwort kam, bevor die App ihr Modell hatte (Start im Hintergrund)
    private var pendingRings: [CallLogic.Push] = []
    private var pendingAnswer: (call: String, video: Bool)?
    /// Medienart aus dem VoIP-Push (CallKit-Annehmen, bevor die Abfrage des Anrufs fertig ist)
    private var pushMedia: [String: String] = [:]

    /// App-Start: Mitteilungs-Kategorie (Annehmen/Ablehnen), iOS: PushKit-Anmeldung
    func setup() {
        let answer = String(localized: "Annehmen"), decline = String(localized: "Ablehnen")
        Task.detached {
            let ans = UNNotificationAction(identifier: CallSystem.answerAction, title: answer, options: [.foreground])
            let dec = UNNotificationAction(identifier: CallSystem.declineAction, title: decline, options: [.destructive])
            let cat = UNNotificationCategory(identifier: CallSystem.category, actions: [ans, dec], intentIdentifiers: [], options: [])
            let c = UNUserNotificationCenter.current()
            let have = await c.notificationCategories()
            c.setNotificationCategories(have.filter { $0.identifier != CallSystem.category }.union([cat]))
        }
        #if os(iOS)
        CallAudio.prepare()                                                  // vor jedem CallKit-Anruf (VoIP-Kaltstart)
        if CallEnv.callKit { registerVoIP() }
        #endif
    }
    private func drainPending() {
        guard let m = manager else { return }
        let rings = pendingRings; pendingRings = []
        for p in rings { Task { await m.ringing(callID: p.call, hint: p) } }
        if let a = pendingAnswer {
            pendingAnswer = nil
            Task { for _ in 0..<40 { if m.incoming?.id == a.call { m.answer(video: a.video); return }; try? await Task.sleep(for: .milliseconds(250)) } }
        }
    }
    /// Antippen/Aktion einer Anruf-Mitteilung
    func notificationAction(_ action: String, info: [AnyHashable: Any]) {
        guard let cid = (info["call"] as? String) ?? CallLogic.callFromURL(info["url"] as? String) else { return }
        let video = (info["media"] as? String) == "video"
        guard let m = manager else { if action == Self.answerAction { pendingAnswer = (cid, video) }; return }
        switch action {
        case Self.declineAction: if m.incoming?.id == cid { m.decline() }
        case Self.answerAction:
            #if os(iOS)
            if requestAnswer(cid) { return }                                 // CallKit-Anruf: ueber CallKit annehmen (Audio-Session!)
            #endif
            if m.incoming?.id == cid { m.answer(video: video) }
            else { pendingAnswer = (cid, video); Task { await m.ringing(callID: cid); drainPending() } }
        default: Task { await m.ringing(callID: cid) }                   // Mitteilung angetippt: klingeln lassen (Fenster)
        }
    }

    /// Anruf-Mitteilung im Vordergrund (nicht gezeigt): klingeln lassen, wenn die App den Anruf noch nicht kennt
    func ringFromNotice(_ info: [AnyHashable: Any]) {
        guard let m = manager, let p = CallLogic.push(info), !m.busy, m.incoming == nil, !m.done.contains(p.call) else { return }
        Task { await m.ringing(callID: p.call, hint: p) }
    }

    #if os(iOS)
    // MARK: CallKit

    private lazy var provider: CXProvider = {
        let c = CXProviderConfiguration()
        c.supportsVideo = true; c.maximumCallGroups = 1; c.maximumCallsPerCallGroup = 1
        c.supportedHandleTypes = [.generic]; c.includesCallsInRecents = true
        let p = CXProvider(configuration: c)
        p.setDelegate(self, queue: nil)
        return p
    }()
    private let controller = CXCallController()
    private var reported = Set<UUID>()
    private var outgoing = Set<UUID>()
    private var registry: PKPushRegistry?
    private(set) var voipToken: String? = UserDefaults.standard.string(forKey: "hk.push.voip")

    private func registerVoIP() {
        let r = PKPushRegistry(queue: .main)
        r.delegate = self
        r.desiredPushTypes = [.voIP]
        registry = r
    }
    private func update(name: String, video: Bool, chat: Int?) -> CXCallUpdate {
        let u = CXCallUpdate()
        u.remoteHandle = CXHandle(type: .generic, value: "saga-chat-\(chat ?? 0)")
        u.localizedCallerName = name.isEmpty ? String(localized: "Saga-Anruf") : name
        u.hasVideo = video; u.supportsHolding = false; u.supportsGrouping = false; u.supportsUngrouping = false; u.supportsDTMF = false
        return u
    }
    private func configureAudio(video: Bool) { CallAudio.configure(video: video) }
    /// Bei CallKit gemeldeter Anruf: Annehmen als CXAnswerCallAction anfordern (nur so aktiviert CallKit die
    /// Audio-Session); true = angefordert, CallManager.answer kommt dann aus provider(_:perform:)
    func requestAnswer(_ id: String) -> Bool {
        guard CallEnv.callKit, let u = CallLogic.uuid(id), reported.contains(u) else { return false }
        controller.request(CXTransaction(action: CXAnswerCallAction(call: u))) { [weak self] e in
            guard e != nil else { return }
            Task { @MainActor in                                             // abgelehnt: selbst annehmen, Ton per Rueckfall
                guard let self, let m = self.manager, m.incoming?.id == id else { return }
                m.answer(video: m.media == "video")
            }
        }
        return true
    }
    #endif

    // MARK: Aufrufe aus CallManager

    func startOutgoing(_ m: CallManager, id: String, name: String, video: Bool) {
        #if os(iOS)
        guard CallEnv.callKit, let u = CallLogic.uuid(id) else { return }
        let a = CXStartCallAction(call: u, handle: CXHandle(type: .generic, value: "saga-call"))
        a.isVideo = video; a.contactIdentifier = name
        outgoing.insert(u); reported.insert(u)
        configureAudio(video: video)
        controller.request(CXTransaction(action: a)) { [weak self] e in
            Task { @MainActor in
                if e != nil { CallAudio.ensureActive(); return }               // CallKit lehnt ab: Ton selbst einschalten
                self?.provider.reportCall(with: u, updated: self?.update(name: name, video: video, chat: nil) ?? CXCallUpdate())
            }
        }
        #endif
    }
    /// 2.5: Gruppenanruf (auch ueber einen Knoten) - Name in CallKit mit der Zahl der Geraete nachfuehren
    func groupInfo(_ m: CallManager, count: Int) {
        #if os(iOS)
        guard CallEnv.callKit, m.kind == "group", let id = m.id, let u = CallLogic.uuid(id), reported.contains(u) else { return }
        let name = m.dmRoom || m.title.isEmpty ? m.peerName : m.title          // 1:1 ueber Knoten: Name der Person, ohne Zahl
        provider.reportCall(with: u, updated: update(name: count > 1 && !m.dmRoom ? "\(name) · \(count)" : name, video: m.media == "video", chat: m.chatID))
        #endif
    }
    func reportIncoming(_ m: CallManager, _ inc: IncomingCall) {
        #if os(iOS)
        if CallEnv.callKit, let u = CallLogic.uuid(inc.id) {
            let up = update(name: inc.name, video: inc.media == "video", chat: inc.chatID)
            if reported.contains(u) { provider.reportCall(with: u, updated: up) }
            else { reported.insert(u); provider.reportNewIncomingCall(with: u, update: up) { _ in } }
            // in CallKit angenommen, waehrend die App den Anruf noch abfragte (Kaltstart per VoIP-Push): jetzt annehmen
            if let a = pendingAnswer, a.call == inc.id {
                pendingAnswer = nil
                Task { @MainActor in if m.incoming?.id == inc.id { m.answer(video: a.video) } }
            }
            return
        }
        #endif
        CallTones.shared.play(.ring)
        #if os(macOS)
        if !NSApp.isActive {
            NSApp.requestUserAttention(.informationalRequest)               // dezent: Dock einmal, dazu die Mitteilung
            let c = UNMutableNotificationContent()
            c.title = inc.media == "video" ? String(localized: "Videoanruf") : String(localized: "Anruf")
            c.body = inc.name; c.categoryIdentifier = Self.category; c.interruptionLevel = .timeSensitive
            c.userInfo = ["kind": "call", "call": inc.id, "media": inc.media]
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "call-" + inc.id, content: c, trigger: nil))
        }
        #endif
    }
    func answering(_ id: String, video: Bool) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call-" + id])
        #if os(iOS)
        if CallEnv.callKit { configureAudio(video: video) }
        #endif
    }
    func connected(_ m: CallManager) {
        #if os(iOS)
        if CallEnv.callKit {                                                 // Rueckfall: CallKit hat die Session nicht aktiviert
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(1500))
                if m.busy, !CallAudio.enabled { CallEnv.log("audio fallback"); CallAudio.ensureActive() }
            }
        }
        if CallEnv.callKit, let id = m.callID, let u = CallLogic.uuid(id), outgoing.contains(u) { provider.reportOutgoingCall(with: u, connectedAt: Date()) }
        #endif
    }
    func muted(_ m: CallManager, _ on: Bool) {
        #if os(iOS)
        if CallEnv.callKit, let id = m.callID, let u = CallLogic.uuid(id), reported.contains(u) {
            controller.request(CXTransaction(action: CXSetMutedCallAction(call: u, muted: on))) { _ in }
        }
        #endif
    }
    func endReported(_ id: String, reason: EndReason, local: Bool = false) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call-" + id])
        #if os(iOS)
        guard CallEnv.callKit, let u = CallLogic.uuid(id), reported.contains(u) else { return }
        reported.remove(u); outgoing.remove(u); pushMedia[id] = nil
        if pendingAnswer?.call == id { pendingAnswer = nil }
        let r: CXCallEndedReason
        switch reason {
        case .failed: r = .failed
        case .remoteEnded: r = .remoteEnded
        case .unanswered: r = .unanswered
        case .answeredElsewhere: r = .answeredElsewhere
        case .declinedLocally: r = .declinedElsewhere
        }
        provider.reportCall(with: u, endedAt: Date(), reason: r)
        #endif
    }
}

#if os(iOS)
extension CallSystem: CXProviderDelegate {
    nonisolated func providerDidReset(_ provider: CXProvider) {
        MainActor.assumeIsolated { manager?.hangup() }
    }
    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        MainActor.assumeIsolated { configureAudio(video: action.isVideo) }
        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: nil)
        action.fulfill()
    }
    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        let cid = CallLogic.callID(action.callUUID)
        MainActor.assumeIsolated {
            let video = (manager?.incoming?.id == cid ? manager?.incoming?.media : pushMedia[cid]) == "video"
            configureAudio(video: video)
            if let m = manager, m.incoming?.id == cid { m.answer(video: video) } else { pendingAnswer = (cid, video) }
        }
        action.fulfill()
    }
    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        let cid = CallLogic.callID(action.callUUID)
        MainActor.assumeIsolated {
            reported.remove(action.callUUID); outgoing.remove(action.callUUID); pushMedia[cid] = nil
            if pendingAnswer?.call == cid { pendingAnswer = nil }
            if let m = manager, m.callID == cid || m.incoming?.id == cid { m.hangup() }
            else if let m = manager { Task { _ = try? await m.store?.apiResponse("api/calls/\(cid)/decline", method: "POST", body: ["reason": "declined"]) } }
        }
        action.fulfill()
    }
    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        let on = action.isMuted
        MainActor.assumeIsolated { manager?.setMuted(on) }
        action.fulfill()
    }
    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        CallAudio.activated(audioSession)
    }
    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        CallAudio.deactivated(audioSession)
    }
}

extension CallSystem: PKPushRegistryDelegate {
    nonisolated func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        let hex = PushLogic.hex(pushCredentials.token)
        MainActor.assumeIsolated {
            voipToken = hex; UserDefaults.standard.set(hex, forKey: "hk.push.voip")
            PushModel.current?.voipTokenChanged(hex)
        }
    }
    nonisolated func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        MainActor.assumeIsolated { voipToken = nil; UserDefaults.standard.removeObject(forKey: "hk.push.voip") }
    }
    /// Pflicht: im selben Durchlauf einen CallKit-Anruf melden - auch wenn der Anruf schon vorbei ist (dann gleich beenden)
    nonisolated func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType,
                                  completion: @escaping () -> Void) {
        let info = payload.dictionaryPayload
        MainActor.assumeIsolated {
            let p = CallLogic.push(info)
            let title = (info["title"] as? String) ?? ""
            guard let p, let u = CallLogic.uuid(p.call) else {
                let u = UUID()
                provider.reportNewIncomingCall(with: u, update: update(name: title, video: false, chat: nil)) { [weak self] _ in
                    Task { @MainActor in self?.provider.reportCall(with: u, endedAt: Date(), reason: .failed) }
                    completion()
                }
                return
            }
            // 1:1 (auch "call.group" mit dm ueber einen Knoten): Name der Person; Gruppenanruf: Chatname bzw. Mitteilungstext
            let chatTitle = p.chat.flatMap { ch in manager?.store?.chat.chats.first(where: { $0.id == ch })?.title }
            let up = update(name: CallLogic.systemName(p, chatTitle: chatTitle, title: title), video: p.media == "video", chat: p.chat)
            let known = reported.contains(u)                                   // klingelt schon (Live-Kanal): trotzdem melden,
            reported.insert(u)                                                 // CallKit antwortet dann "existiert schon"
            pushMedia[p.call] = p.media
            provider.reportNewIncomingCall(with: u, update: up) { _ in completion() }
            if known { return }
            if let m = manager { Task { await m.ringing(callID: p.call, hint: p) } } else { pendingRings.append(p) }
            // Schutz: klingelt der Anruf nach voipGrace s in der App nicht (Abfrage haengt, Modell fehlt), CallKit beenden
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(CallLogic.voipGrace))
                if self?.manager == nil { try? await Task.sleep(for: .seconds(CallLogic.voipGraceNoModel - CallLogic.voipGrace)) }
                guard let self, self.reported.contains(u) else { return }
                let m = self.manager
                if CallLogic.voipStale(reported: true, ringing: m?.incoming?.id == p.call, current: m?.callID == p.call) {
                    CallEnv.log("voip stale \(p.call.prefix(8))")
                    self.endReported(p.call, reason: .failed)
                }
            }
        }
    }
}
#endif
