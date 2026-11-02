import Foundation
import WebRTC

// 2.5 Anruf-Diagnose an den Server (nur wenn /api/version features "calldiag1" meldet): POST /api/calls/diag alle 30 s
// waehrend eines Anrufs und einmal beim Auflegen, hoechstens einer je 15 s, Fehler still, nie blockierend. Inhalt:
// Video-Proben aus den vorhandenen getStats-Laeufen (Knoten: hornStats, P2P: statsOn) und Ereignisse (Schichten/Waerme,
// Wechsel, Weg, Qualitaetsanzeige). Quellen nur als Kuerzel, kein Name, keine Adresse. Logik: CallDiagLogic.swift.
extension CallManager {
    private var diagOn: Bool { store?.features.has(CallDiag.feature) == true }
    /// Puffer anlegen (beim ersten Eintrag eines verbundenen Anrufs) und Sendeschleife starten
    private func diagEnsure() -> Bool {
        guard diagOn, let id, !ending else { return false }
        if diag?.call != id {
            #if os(macOS)
            let plat = "mac"
            #else
            let plat = "ios"
            #endif
            let t0 = (connectedAt ?? Date()).timeIntervalSince1970 * 1000
            diag = CallDiag.Buffer(call: id, plat: plat, app: Store.appVersion, t0: t0)
            diagTask?.cancel()
            diagTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(5))
                    guard let self, !self.ending, self.diag != nil else { return }
                    if self.diag?.due(now: Self.hornNow()) == true { self.diagSend() }
                }
            }
        }
        return true
    }
    func diagAlias(_ dev: String?) -> String { diagEnsure() ? (diag?.alias(dev) ?? "") : "" }
    func diagSample(type: String, values: [String: Any], src: String, key: String, rtt: Double?, report: RTCStatisticsReport? = nil) {
        guard diagEnsure() else { return }
        let now = Self.hornNow()
        guard diag?.sampleDue(key, now: now, every: 2500) == true else { return }      // je Spur hoechstens alle ~3 s
        // 2.5: Rahmen der Kamera (media-source.frames) fuer fdrop beim Senden
        let frames = type == "outbound-rtp" ? (values["mediaSourceId"] as? String).flatMap { report?.statistics[$0]?.values["frames"] as? NSNumber }?.doubleValue : nil
        if let s = diag?.sample(type: type, values: values, src: src, key: key, now: now, rtt: rtt, srcFrames: frames) { diag?.add(s) }
    }
    func diagEvent(_ k: String, _ d: String) {
        guard diagEnsure() else { return }
        diag?.event(k, d, now: Self.hornNow())
    }
    /// senden, wenn erlaubt (hoechstens 1 je 15 s); Fehler still
    private func diagSend() {
        guard let store, var b = diag, let body = b.take(now: Self.hornNow()) else { return }
        diag = b
        Task { _ = try? await store.apiResponse(CallDiag.path, method: "POST", body: body, timeout: 10) }
    }
    /// beim Auflegen/Ende: letzter POST (sofern die 15-s-Grenze es erlaubt, sonst kurz danach), dann Puffer weg
    func diagFinal() {
        diagTask?.cancel(); diagTask = nil
        guard diagOn, let store, var b = diag, !b.isEmpty else { diag = nil; return }
        diag = nil
        let wait = b.wait(now: Self.hornNow())
        Task {
            if wait > 0 { try? await Task.sleep(for: .milliseconds(Int(wait) + 1500)) }   // Puffer: die vorige Meldung kam ggf. spaeter an
            guard let body = b.take(now: Self.hornNow()) else { return }
            _ = try? await store.apiResponse(CallDiag.path, method: "POST", body: body, timeout: 10)
        }
    }
}
