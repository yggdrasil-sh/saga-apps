import SwiftUI
#if DEBUG
import Darwin

// Nur fuer Tests (-hkChatPerf YES mit -hkChat <id>): Messung im geoeffneten Chat - Zeit bis zum ersten Bildschirm (alle
// sichtbaren Bilder da), ganzen Verlauf laden, automatisch hoch- und wieder runterscrollen; dabei Haenger des UI-Threads
// (Abstand eines 10-ms-Takts) und Speicher (phys_footprint, Spitze). Ausgabe: Zeilen "CHAT-PERF ..." auf stdout.
@MainActor
enum ChatPerf {
    static let on = UserDefaults.standard.bool(forKey: "hkChatPerf")
    private static var t0 = Date(), phase0 = Date()
    private static var started = 0, done = 0, failed = 0, cancelled = 0, pending = 0, lastChange = Date()
    private static var tick: Timer?, last = CACurrentMediaTime(), maxGap = 0.0, stalls50 = 0, stalls100 = 0
    private static var peak: UInt64 = 0
    private static var sampler: DispatchSourceTimer?

    static func footprint() -> UInt64 { footprintNonisolated() }
    static func begin() {
        guard on else { return }
        t0 = Date(); phase0 = t0
        tick = Timer(timeInterval: 0.01, repeats: true) { _ in
            MainActor.assumeIsolated {
                let now = CACurrentMediaTime(), g = now - last
                last = now
                if g > maxGap { maxGap = g }
                if g > 0.05 { stalls50 += 1 }
                if g > 0.1 { stalls100 += 1 }
            }
        }
        RunLoop.main.add(tick!, forMode: .common)
        let s = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        s.schedule(deadline: .now(), repeating: .milliseconds(50))
        s.setEventHandler { let f = ChatPerf.footprintNonisolated(); DispatchQueue.main.async { MainActor.assumeIsolated { if f > peak { peak = f } } } }
        s.resume(); sampler = s
        log("start fussabdruck=\(mb(footprint()))")
    }
    nonisolated static func footprintNonisolated() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) } }
        return kr == KERN_SUCCESS ? info.phys_footprint : 0
    }
    static func imgStart() { guard on else { return }; started += 1; pending += 1; lastChange = Date() }
    static func imgDone(ok: Bool = true) { guard on else { return }; if ok { done += 1 } else { failed += 1 }; pending -= 1; lastChange = Date() }
    static func imgCancel() { guard on else { return }; cancelled += 1; pending -= 1; lastChange = Date() }
    static func phase() { phase0 = Date(); maxGap = 0; stalls50 = 0; stalls100 = 0; started = 0; done = 0; failed = 0; cancelled = 0; peak = footprint() }
    /// warten, bis alle angefangenen Bilder fertig sind und 0,7 s nichts Neues kam (hoechstens `limit` s)
    static func settle(limit: Double = 180) async {
        let s = Date()
        while Date().timeIntervalSince(s) < limit {
            try? await Task.sleep(for: .milliseconds(100))
            // alle laufenden Ladevorgaenge (auch aus der vorigen Phase) fertig und 0,7 s Ruhe; nichts angefangen: 2 s
            if pending <= 0, Date().timeIntervalSince(lastChange) > 0.7, started > 0 || Date().timeIntervalSince(s) > 2 { return }
        }
        log("settle: Zeitgrenze erreicht")
    }
    private static func mb(_ b: UInt64) -> String { String(format: "%.0fMB", Double(b) / 1_048_576) }
    static func report(_ name: String, extra: String = "") {
        log("\(name) dauer=\(Int(Date().timeIntervalSince(phase0) * 1000))ms bis_letztes_bild=\(Int(lastChange.timeIntervalSince(phase0) * 1000))ms seit_start=\(Int(Date().timeIntervalSince(t0) * 1000))ms "
            + "bilder=\(done)/\(started) fehler=\(failed) abgebrochen=\(cancelled) haenger>50ms=\(stalls50) haenger>100ms=\(stalls100) max_haenger=\(Int(maxGap * 1000))ms "
            + "offen=\(pending) speicher=\(mb(footprint())) spitze=\(mb(max(peak, footprint()))) \(extra)")
    }
    /// Zeitpunkt fuer "nach dem Scrollen bis alle sichtbaren Bilder da"
    static var mark = Date()
    static func log(_ s: String) { print("CHAT-PERF " + s); fflush(stdout) }
}
#endif
