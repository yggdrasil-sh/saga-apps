import Foundation
import WebRTC

// Horn-Fix (Saga 2.5, Vertrag horn_direct1): laufender Knoten-Anruf -> direkt (Mesh), wenn der Knoten nach 30 s
// Wiederaufbau nicht erreichbar ist. Regeln in App/HornRecover.swift; Ablauf wie Android HornDirect / Web horn.js.
//   POST /api/calls/{cid}/direct {device, avoid}  -> 200 {call (topo mesh), offer_to} | 409 horn_block | 404 | 429
//   Ereignis call.topo {call_id, topo: "mesh", why, by} an alle -> Knoten zu, Mesh: Angebote nur an groessere IDs
extension CallManager {
    /// Direkt-Weg beim Server anfragen (avoid = in den letzten 30 s nicht erreichte Knoten)
    func hornDirectAsk(_ H: HornSession) async -> HornRecover.DirectAnswer {
        guard let store, let id, let dev = myDevice else { return .error }
        let avoid = HornRecover.unreached(H.bad, now: Self.hornNow(), badMs: HornLogic.badMs)
        guard let (d, code) = try? await store.apiResponse("api/calls/\(id)/direct", method: "POST", body: ["device": dev, "avoid": avoid]) else { return .error }
        let j = ShareJSON.obj(d)
        let a = HornRecover.directAnswer(status: code, body: j)
        if case .switched(let to) = a, hornLive(H) { hornToMesh(j?["call"] as? [String: Any], offerTo: to) }
        if case .nodeReady = a, let blk = j?["horn"] { let b = HornLogic.candidates(blk); if !b.isEmpty { H.blk = b; H.known.formUnion(b.map(\.node)) } }
        return a
    }

    /// Ereignis call.topo: der Server hat den Anruf auf direkt umgestellt (auch ohne eigenen Verlust)
    func hornTopoIn(_ d: [String: Any]) {
        guard let cid = d["call_id"] as? String, cid == id, (d["topo"] as? String) == "mesh", horn != nil else { return }
        CallEnv.log("call.topo mesh \((d["why"] as? String) ?? "-")")
        hornToMesh(nil, offerTo: nil)
    }

    /// Signal waehrend des Knoten-Anrufs merken (Angebote kleinerer IDs koennen vor call.topo ankommen)
    func hornMeshKeep(_ s: [String: Any]) {
        guard hornCanDirect, let H = horn else { return }
        let now = Self.hornNow()
        H.meshQ.append((now, s))
        H.meshQ.removeAll { now - $0.t > 30_000 }
        if H.meshQ.count > 80 { H.meshQ.removeFirst(H.meshQ.count - 80) }
    }

    /// Wechsel ausfuehren: Knoten-Verbindungen zu, Mesh wie Gruppenanruf 2.4. Kein Rueckweg auf den Knoten.
    func hornToMesh(_ c: [String: Any]?, offerTo: [String]?) {
        guard horn != nil, let dev = myDevice else { return }
        let q = horn?.meshQ.map(\.s) ?? []
        hornStop(); horn = nil
        if let c { adoptCall(c) }
        topo = "mesh"
        // Signale kleinerer IDs von vor dem Wechsel (deren Angebote + ICE an uns) SOFORT in der Reihenfolge des Eingangs
        // puffern und annehmen - vor den eigenen Angeboten; spaetere Signale (ICE) haengen sich hinten an (pend)
        var froms: [String] = []
        for s in q {
            guard let f = s["from_device"] as? String, f < dev else { continue }
            var l = pend[f] ?? []; l.append(s); if l.count > 80 { l.removeFirst() }; pend[f] = l
            if !froms.contains(f) { froms.append(f) }
        }
        for f in froms { admit(f) }
        let note = String(localized: "Anruf-Knoten nicht erreichbar – direkt verbunden")
        directNote = note; toast = note
        diagEvent("switch", "direct")
        CallEnv.log("horn -> direkt")
        Task {
            await hornRosterFetch()
            guard !ending, horn == nil, topo == "mesh" else { return }
            let others = roster.legs.map(\.device).filter { $0 != dev }
            let targets = offerTo ?? HornRecover.offerTargets(others, me: dev)
            if targets.isEmpty && others.isEmpty { status = String(localized: "Warte auf die anderen …") }
            for d in targets where link(d) == nil {
                guard let x = roster.leg(d) else { continue }
                do {
                    let L = try await makeLink(x)
                    guard L.devs[x.device] != nil, !ending, link(d) == nil else { continue }
                    L.role = "caller"; L.pcID = CallV3.pairPC(dev, x.device)
                    L.neg.polite = CallV3.polite(me: dev, peer: x.device)
                    links.append(L)
                    makePC(L); addLocal(L, fromOffer: false)
                    guard let pc = L.pc else { continue }
                    let o = try await pc.offerAsync(CallRTC.constraints)
                    try await pc.setLocalAsync(o)
                    let n = L.neg.initialOffer()
                    guard let sdp = pc.localDescription?.sdp else { continue }
                    await sendSignal(L, CallV3.offerBody(sdp: sdp, media: media, pc: L.pcID!, n: n, caps: myCaps, roles: myRoles(L)))
                    flushIce(L); connTimer(L)
                } catch { CallEnv.log("direkt: Angebot \(d.prefix(6)) \(error.localizedDescription)") }
            }
            syncPrimary()
        }
    }

    /// Hinweis beim Anrufstart (409 fallback direct)
    func hornStartNote(_ block: String?) {
        switch HornRecover.startNote(block) {
        case .nodeDown?: directNote = String(localized: "Anruf-Knoten nicht erreichbar – direkt verbunden")
        case .peerOld?: directNote = String(localized: "Gegenstelle veraltet – direkt verbunden")
        case nil: return
        }
        toast = directNote
    }
}
