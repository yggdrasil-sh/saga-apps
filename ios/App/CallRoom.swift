import SwiftUI
import AVFoundation
import WebRTC

// Anrufe 2.4 (E4/E5, Vertrag docs/APP-API.md "Anrufe: Mehrgeraete und Gruppen"): Raum mit Endpunkten je Geraet.
// - Begleitgeraet ("Hinzufuegen"): join role companion - Mikrofon aus (nicht danach fragen), Wiedergabe stumm, Kamera aus.
// - "Hierher uebernehmen": join role handover (aus der Leiste) bzw. als Begleitgeraet ohne neuen join; sobald alle
//   Verbindungen zu ANDEREN Personen stehen, Ton an und state.ready + replaces an das alte Geraet - das schaltet sofort ab,
//   schickt bye (reason handover) und verlaesst den Anruf. Klappt es nicht in handover_s, verlaesst das neue Geraet.
// - Gruppenanrufe als Mesh: wer hinzukommt, bietet allen an (je Geraetepaar eine PeerConnection); die anderen nehmen ohne
//   Klingeln an, nachdem sie das Geraet gegen die SIGNIERTE Geraeteliste seiner Person geprueft haben (Geister-Geraet).
// - Teilnehmerliste mit Stand (rv): eine aeltere Liste ueberschreibt keine neuere. Leiste "Anruf laeuft" aus
//   GET /api/calls/active. Faehigkeiten per PUT /api/calls/caps.
// Signale gehen wie bisher an den eigenen Server; foederiert reicht der fuehrende Server sie sternfoermig weiter.

extension CallManager {

    // MARK: Anruf-Objekt uebernehmen

    /// Felder aus GET /api/calls/{id}, POST /api/calls, /join: Art, Grenze, Personen ohne 2.4, Raum ueber Servergrenzen
    func adoptCall(_ c: [String: Any]) {
        if let k = c["kind"] as? String { kind = k == "group" ? "group" : "dm" }
        if let t = c["topo"] as? String { topo = t }                        // 2.5: "horn" = ueber einen Gjallarhorn-Knoten
        if let h = c["host"] as? Bool { callHost = h }
        if let m = c["media"] as? String, kind == "group" || !joinRole.isEmpty { media = m == "video" ? "video" : "audio" }
        if chatID == 0, let ch = ShareJSON.int(c["chat_id"]) { chatID = ch }
        limit = CallRoom.limit(media: media, server: ShareJSON.int(c["limit"]), horn: topo == "horn")
        oldNames = (c["old"] as? [Any] ?? []).compactMap { ($0 as? [String: Any]).flatMap { ShareJSON.str($0["name"]) ?? ShareJSON.str($0["who"]) } }
        if let d = c["dm"] as? Bool { dmRoom = d }
        if kind == "group", dmRoom {                                        // 2.5: 1:1 ueber Knoten - peer = Gegenueber
            let p = c["peer"] as? [String: Any] ?? [:]
            peerName = ShareJSON.str(p["name"]).map { PersonName.shown($0, id: ShareJSON.str(p["handle"]), host: ShareJSON.str(p["host"])) } ?? peerName; peerAvatar = ShareJSON.str(p["avatar"]) ?? peerAvatar
        } else if kind == "group" {
            let from = c["from"] as? [String: Any] ?? c["peer"] as? [String: Any] ?? [:]
            if title.isEmpty, let t = ShareJSON.str(from["chat"]) { title = t }
            // Freie Gruppen: der Name ist Ende-zu-Ende-verschluesselt, der Server kennt ihn nicht - aus der Chatliste
            if title.isEmpty, chatID != 0, let t = store?.chat.chats.first(where: { $0.id == chatID })?.title, !t.isEmpty { title = t }
            peerName = title.isEmpty ? String(localized: "Gruppenanruf") : title
        } else if !joinRole.isEmpty, let p = c["peer"] as? [String: Any] {
            peerName = ShareJSON.str(p["name"]).map { PersonName.shown($0, id: ShareJSON.str(p["handle"]), host: ShareJSON.str(p["host"])) } ?? peerName; peerAvatar = ShareJSON.str(p["avatar"]) ?? peerAvatar
        }
    }
    /// Raum ueber Servergrenzen: eigener Handle (Signale sind daran gebunden)
    func adoptV2(_ c: [String: Any]) async {
        guard ShareJSON.int(c["v"]) == 2 else { roomHandle = nil; return }
        if roomHandle == nil { roomHandle = await store?.chat.fedMyHandle() }
    }
    /// 2.5 Server meldet handover2 (gen, bedingtes /leave, promote, Wiedereintritt)
    var ho2: Bool { serverFeatures.contains(CallRoom.featureHandover2) }
    /// /leave-Body: mit handover2 immer mit gen (aus der Teilnehmerliste), Rueckfall zusaetzlich mit by
    func leaveBody(_ dev: String, reason: String? = nil, by: String? = nil) -> [String: Any] {
        CallRoom.leaveBody(device: dev, reason: reason, by: by, legs: roster.legs, v2: ho2)
    }
    /// bedingtes /leave senden (Ergebnis nur fuers Protokoll: stale = das Bein gilt nicht mehr, nichts passiert)
    func postLeave(_ cid: String, _ body: [String: Any], after s: Double = 0) {
        let store = self.store
        Task {
            if s > 0 { try? await Task.sleep(for: .seconds(s)) }
            let r = try? await store?.apiResponse("api/calls/\(cid)/leave", method: "POST", body: body)
            let res = CallRoom.leaveResult(code: r?.1, body: r.flatMap { ShareJSON.obj($0.0) })
            CallEnv.log("leave \((body["device"] as? String)?.prefix(6) ?? "-") \(res)")
        }
    }
    func blockText(_ b: CallRoom.Block?, limit n: Int) -> String {
        switch b {
        case .handoverBusy: return String(localized: "Ein anderes eigenes Gerät übernimmt den Anruf gerade – bitte kurz warten.")
        case .old: return String(localized: "Dafür braucht jemand im Anruf Saga 2.4.")
        case .full: return String(localized: "Der Anruf ist voll (höchstens \(n) Geräte).")
        case .ended: return String(localized: "Der Anruf ist schon beendet.")
        case .none?: return String(localized: "Der Anruf ist noch nicht angenommen.")
        default: return String(localized: "Beitreten geht gerade nicht.")
        }
    }

    // MARK: Gruppenanruf klingelt

    func ringGroup(_ c: [String: Any], hint: CallLogic.Push?, silent: Bool) async {
        guard let cid = c["id"] as? String else { return }
        let mine = (c["mine"] as? [Any] ?? []).count
        let created = ShareJSON.num(c["created"]) ?? Date().timeIntervalSince1970
        let ringS = ShareJSON.num(c["ring_s"]) ?? 45
        let st = c["state"] as? String
        guard st != "ended", CallRoom.ringGroup(invite: c["invite"] as? String, mine: Array(repeating: "", count: mine), silent: silent, busy: busy,
                                                 age: Date().timeIntervalSince1970 - created, ringS: ringS) else {
            CallSystem.shared.endReported(cid, reason: mine > 0 ? .answeredElsewhere : .remoteEnded)   // VoIP-Push: sofort beenden
            runSoon()
            return
        }
        if incoming?.id == cid || id == cid || done.contains(cid) { return }
        let from = c["from"] as? [String: Any] ?? c["peer"] as? [String: Any] ?? [:]
        let who = ShareJSON.str(from["name"]) ?? hint?.caller ?? ""
        let dm = (c["dm"] as? Bool) == true || hint?.dm == true              // 2.5: 1:1 ueber Knoten - klingelt wie 1:1
        let chID = ShareJSON.int(c["chat_id"]) ?? 0
        // Freie Gruppen: Name Ende-zu-Ende-verschluesselt (Server kennt ihn nicht) - aus der Chatliste
        let listed = chID != 0 ? store?.chat.chats.first(where: { $0.id == chID })?.title : nil
        let chatName = dm ? nil : (ShareJSON.str(from["chat"]) ?? listed.flatMap { $0.isEmpty ? nil : $0 })
        let media = (c["media"] as? String) == "video" ? "video" : "audio"
        let inc = IncomingCall(id: cid, chatID: chID, media: media,
                               name: CallLogic.ringName(who: who, chat: chatName, dm: dm, body: hint?.body), username: from["username"] as? String ?? "", avatar: ShareJSON.str(from["avatar"]),
                               group: true, chatName: chatName)
        reset()
        incoming = inc; phase = .incoming; role = "callee"; kind = "group"; dmRoom = dm; self.media = media; peerName = inc.name; peerAvatar = inc.avatar
        chatID = inc.chatID; title = chatName ?? ""
        status = dm ? (media == "video" ? String(localized: "Eingehender Videoanruf") : String(localized: "Eingehender Anruf"))
            : media == "video" ? String(localized: "Gruppen-Videoanruf") : String(localized: "Gruppenanruf")
        CallEnv.log("incoming group \(cid.prefix(8)) \(media)")
        CallSystem.shared.reportIncoming(self, inc)
        aliveRinging(cid)
        #if DEBUG
        if let a = UserDefaults.standard.string(forKey: "hkCallAutoAnswer") {      // nur fuer Tests: annehmen/ablehnen
            Task { try? await Task.sleep(for: .seconds(4)); if incoming?.id == cid { if a == "decline" { decline() } else { answer(video: a == "video") } } }
        }
        #endif
    }
    /// Klingeln beenden: ein eigenes Geraet ist beigetreten bzw. die Einladung wurde (auf einem anderen Geraet) abgelehnt
    func stopGroupRing(_ cid: String, declined: Bool, expired: Bool = false) {
        guard let inc = incoming, inc.id == cid else { return }
        CallEnv.log("group ring stop \(declined ? "declined" : expired ? "expired" : "joined elsewhere")")
        CallSystem.shared.endReported(cid, reason: declined ? .declinedLocally : expired ? .unanswered : .answeredElsewhere)
        incoming = nil; aliveTask?.cancel(); CallTones.shared.stop()
        phase = .idle; status = ""
        runSoon()
    }
    /// Gruppenanruf annehmen = beitreten (role member)
    func answerGroup(_ inc: IncomingCall, video: Bool) {
        incoming = nil; aliveTask?.cancel(); CallTones.shared.stop()
        CallSystem.shared.answering(inc.id, video: video)
        title = inc.chatName ?? ""
        join(inc.id, role: "member", video: video, answered: true)
    }

    // MARK: Beitreten (Begleitgeraet, Uebernahme, Gruppe)

    /// Mit diesem Geraet einem laufenden Anruf beitreten. role: "member" (Gruppe), "companion" (Begleitgeraet: ohne
    /// Mikrofon und Ton), "handover" (Hierher uebernehmen: das eigene Hauptgeraet laut Liste wird ersetzt)
    func join(_ cid: String, role r: String, video: Bool, answered fromRing: Bool = false) {
        if busy && id != cid && !fromRing { toast = String(localized: "Du telefonierst gerade schon."); return }
        if busy && id == cid { return }
        let keepTitle = title
        reset()
        title = keepTitle
        id = cid; role = "caller"; joinRole = r
        let st = CallRoom.start(role: r, video: video)
        mic = st.mic; sound = st.sound; companion = r == "companion"; speaker = video
        phase = fromRing ? .connecting : .preparing
        status = String(localized: "Verbinde …")
        CallEnv.log("join \(cid.prefix(8)) \(r)")
        Task {
            do {
                guard let store, await ensureChat(), let dev = myDevice else { throw APIError.other(String(localized: "Chats sind auf diesem Gerät noch nicht bereit.")) }
                guard await loadConfig() else { throw APIError.other(String(localized: "Anrufe sind auf diesem Server abgeschaltet.")) }
                guard multi else { throw APIError.other(String(localized: "Dafür braucht dieser Server Saga 2.4.")) }
                let asked = Date().timeIntervalSince1970
                let (d, code) = try await store.apiResponse("api/calls/\(cid)?device=\(dev)")
                guard code == 200, let j = ShareJSON.obj(d), let c = j["call"] as? [String: Any] else { throw APIError.http(code, "") }
                for s in j["signals"] as? [[String: Any]] ?? [] { if let q = ShareJSON.int(s["seq"]) { _ = CallLogic.fresh(q, seen: &seen) } }
                if (c["state"] as? String) == "ended" { throw APIError.other(String(localized: "Der Anruf ist schon beendet.")) }
                adoptCall(c)
                await adoptV2(c)
                if !CallRoom.joinAllowed(role: r, joinable: c["joinable"] as? Bool, takeover: c["takeover"] as? Bool) {   // L4: voll - uebernehmen geht
                    throw APIError.other(blockText(CallRoom.block(c["join_block"] as? String), limit: limit))
                }
                roster.apply(CallRoom.legs(c["legs"]), rv: asked)
                var rep: String?
                if r == "handover" {
                    rep = CallRoom.mainDevice(roster.legs, me: me, here: dev)?.device
                    if rep == nil { joinRole = "member" }                    // kein eigenes Geraet mehr dabei: normal beitreten
                }
                if kind == "dm", let p = c["peer"] as? [String: Any] { peerName = ShareJSON.str(p["name"]).map { PersonName.shown($0, id: ShareJSON.str(p["handle"]), host: ShareJSON.str(p["host"])) } ?? peerName }
                if !fromRing { CallSystem.shared.startOutgoing(self, id: cid, name: peerName, video: media == "video") }
                try await prepareMedia(video: st.cam && media == "video", askMic: st.askMic)
                if !st.mic { audioTrack?.isEnabled = false; CallSystem.shared.muted(self, true) }
                guard !ending, id == cid else { return }
                try await offerAll(role: joinRole, rep: rep, again: false)
            } catch {
                if inRoom, let dev = myDevice { postLeave(cid, leaveBody(dev)) }
                // 2.5 handover_busy: nur ein Hinweis (kein Fehler, nicht automatisch wiederholen)
                failed = !(error is CallHint)
                finish(nil, message: error.localizedDescription)
            }
        }
    }
    /// Verbindung zu einem Geraet der Liste anlegen (Person, Handles, geprueft Schluessel)
    func makeLink(_ x: CallRoom.Leg) async throws -> CallLink {
        let own = x.who == me
        let L = CallLink(mgr: self, dev: x.device, who: x.who, name: x.name)
        L.own = own; L.devName = x.devName
        if let mh = roomHandle { L.v2 = (mh, own ? mh : (x.handle ?? x.who)) }
        try await linkKeys(L, host: x.host)
        // 2.4 (Testrunde W9): waehrend des Anrufs gekoppeltes Geraet (Hinzufuegen/Hierher uebernehmen) fehlt in den
        // zwischengespeicherten Schluesseln der Person - einmal frisch holen und die signierte Liste neu pruefen
        if CallRoom.needsFreshKeys(device: x.device, signed: Set(L.devs.keys)) { try await linkKeys(L, host: x.host, fresh: true) }
        return L
    }
    /// Je anderem Endpunkt eine PeerConnection mit Angebot, alles in einem POST /join. 409 mit neuer Liste: einmal neu.
    func offerAll(role r: String, rep: String?, again: Bool) async throws {
        guard let store, let id, let dev = myDevice else { return }
        if topo == "horn" { try await hornJoin(role: r, rep: rep); return }   // 2.5: Knoten - join ohne Angebote
        for L in links { L.close() }
        links = []
        var sig: [String: String] = [:]
        for x in CallRoom.offerTargets(roster.legs, here: dev).compactMap({ roster.leg($0) }) {
            let L = try await makeLink(x)
            guard L.devs[x.device] != nil else {                             // Geister-Geraet: nicht in der signierten Liste der Person
                throw APIError.other(String(localized: "Unbekanntes Gerät von \(x.name) im Anruf – Beitritt abgebrochen."))
            }
            L.role = "caller"; L.pcID = CallV3.pairPC(dev, x.device)       // wer hinzukommt, legt pc fest
            L.neg.polite = CallV3.polite(me: dev, peer: x.device)
            links.append(L)
            makePC(L); addLocal(L, fromOffer: false)
            guard let pc = L.pc else { continue }
            let o = try await pc.offerAsync(CallRTC.constraints)
            try await pc.setLocalAsync(o)
            let n = L.neg.initialOffer()
            guard let sdp = pc.localDescription?.sdp,
                  let s = seal(L, x.device, CallV3.offerBody(sdp: sdp, media: media, pc: L.pcID!, n: n, caps: myCaps, roles: myRoles(L))) else { continue }
            sig[x.device] = s
        }
        var body: [String: Any] = ["device": dev, "role": r, "caps": myCaps, "signals": sig]
        if r == "handover", let rep { body["replaces"] = rep; hoArm(rep) }
        let asked = Date().timeIntervalSince1970
        let (d, code) = try await store.apiResponse("api/calls/\(id)/join", method: "POST", body: body)
        let j = ShareJSON.obj(d)
        switch CallRoom.joinOutcome(code: code, body: j) {
        case .ok: break
        case .reoffer where !again:                                         // Liste veraltet: einmal neu anbieten
            roster.apply(CallRoom.legs(j?["legs"]), rv: asked)
            try await offerAll(role: r, rep: rep, again: true); return
        default: try joinThrow(code: code, j)
        }
        guard !ending, self.id == id else {
            postLeave(id, CallRoom.leaveBody(device: dev, reason: nil, by: nil, legs: CallRoom.legs((j?["call"] as? [String: Any])?["legs"]), v2: ho2))
            return
        }
        ready = true; inRoom = true
        if let c = j?["call"] as? [String: Any] { adoptCall(c); rosterIn(CallRoom.legs(c["legs"]), rv: asked, extra: c) }
        status = links.isEmpty ? String(localized: "Warte auf die anderen …") : String(localized: "Verbinde …")
        if phase != .active { phase = .connecting }
        aliveOn()
        for L in links { flushIce(L); connTimer(L) }
        if r == "handover", let rep {
            ho.begin(rep: rep, now: Date().timeIntervalSince1970, timeout: handoverSeconds)
            handingOver = true; hoTimer()
        }
        let pending = early; early = []
        for s in pending { await handleRaw(s) }
        syncPrimary()
    }

    /// Uebernahme aus der Leiste: schon waehrend des join als laufend markieren (Wiedereintritt: eine Liste ohne dieses
    /// Geraet beendet den Anruf hier nicht)
    func hoArm(_ rep: String) {
        if !ho.active { ho.begin(rep: rep, now: Date().timeIntervalSince1970, timeout: handoverSeconds) }
    }
    /// Fehlerantwort eines join als Fehler (handover_busy nur als Hinweis)
    func joinThrow(code: Int, _ j: [String: Any]?) throws -> Never {
        switch CallRoom.joinOutcome(code: code, body: j) {
        case .busy(let e): throw CallHint(text: e ?? blockText(.handoverBusy, limit: limit))
        case .blocked(let b, let e): throw APIError.other(e ?? hornBlockText(j?["join_block"] as? String) ?? blockText(b, limit: ShareJSON.int(j?["limit"]) ?? limit))
        case .failed(let c, let e): throw APIError.http(c, e ?? "")
        default: throw APIError.http(code, ShareJSON.str(j?["error"]) ?? "")
        }
    }

    // MARK: Angebote neuer Geraete (die anderen im Raum)

    /// Signal eines Geraets ohne Verbindung: gehoert es zum Anruf (Liste des Servers) und zur Person (signierte
    /// Geraeteliste, TOFU)? Dann Verbindung anlegen und das Angebot ohne Klingeln beantworten; sonst ablehnen.
    func admit(_ dev: String) {
        guard ready, !ending, !admitting.contains(dev), !badDevs.contains(dev), let id else { return }
        admitting.insert(dev)
        Task {
            defer { admitting.remove(dev) }
            var x = roster.leg(dev)
            if x == nil, let store, let me = myDevice {
                let asked = Date().timeIntervalSince1970
                if let (d, code) = try? await store.apiResponse("api/calls/\(id)?device=\(me)"), code == 200, let c = ShareJSON.obj(d)?["call"] as? [String: Any] {
                    rosterIn(CallRoom.legs(c["legs"]), rv: asked, extra: c)
                }
                x = roster.leg(dev)
            }
            guard let x, !ending, self.id == id, link(dev) == nil else { return }
            let L: CallLink
            do { L = try await makeLink(x) } catch { toast = error.localizedDescription; return }
            guard CallRoom.admit(device: dev, legs: roster.legs, signed: Set(L.devs.keys)) == .accept else {
                badDevs.insert(dev); pend[dev] = nil
                toast = String(localized: "Unbekanntes Gerät von \(x.name) abgelehnt.")
                CallEnv.log("admit: abgelehnt \(dev.prefix(6))")
                return
            }
            L.role = "callee"
            let prev = chain
            chain = Task {                                                  // in der Reihenfolge der Signale
                await prev?.value
                guard !self.ending, self.link(dev) == nil else { return }
                self.links.append(L)
                let list = self.pend.removeValue(forKey: dev) ?? []
                for s in list { await self.handleRaw(s) }
                self.syncPrimary()
            }
        }
    }
    /// Angebot eines hinzukommenden Geraets beantworten (ohne Klingeln: die Person ist schon im Anruf bzw. tritt bei)
    func acceptOffer(_ L: CallLink, sdp: String, fp: [String], meta: CallV3.Meta) async {
        guard let dev = myDevice, let from = L.dev else { return }
        guard CallLogic.fingerprintsMatch(sdp: sdp, fp: fp) else { securityFail(L); return }
        L.fps = CallLogic.sdpFingerprints(sdp)
        L.pcID = meta.pc ?? CallV3.pairPC(dev, from)
        adoptPeer(L, meta, device: from)
        peerTracksIn(L, meta)
        L.neg.initialRemote(meta.n)
        makePC(L)
        guard let pc = L.pc else { return }
        do {
            try await setRemote(L, RTCSessionDescription(type: .offer, sdp: sdp))
            addLocal(L, fromOffer: true)
            findRemoteVideo(L)
            let ans = try await pc.answerAsync(CallRTC.constraints)
            try await pc.setLocalAsync(ans)
            guard let out = pc.localDescription?.sdp, !L.gone else { return }
            await sendSignal(L, CallV3.answerBody(sdp: out, media: media, pc: L.pcFor(dev, from), n: meta.n ?? 1, caps: myCaps, roles: myRoles(L)))
            L.established = true
            applyRemoteIce(L); flushIce(L); stateOut(L)
            connTimer(L)
            relimitAll()
            syncPrimary()
        } catch { dropLink(L, String(localized: "Verbindung nicht aufgebaut: \(error.localizedDescription)")) }
    }

    // MARK: Teilnehmerliste

    /// Liste uebernehmen (GET /api/calls/{id}, /join, call.roster) - nur, wenn sie nicht aelter ist (rv). Hinweise
    /// "ist hinzugekommen" / "hat verlassen", Verbindungen zu Geraeten trennen, die nicht mehr dabei sind
    func rosterIn(_ legs: [CallRoom.Leg], rv: Double, extra: [String: Any]? = nil) {
        guard !ending else { return }
        let old: [CallRoom.Leg]? = roster.known ? roster.legs : nil
        guard roster.apply(legs, rv: rv) else { CallEnv.log("roster: aeltere Liste verworfen"); return }
        let here = myDevice ?? ""
        if let e = extra {
            if let n = ShareJSON.int(e["limit"]) { limit = CallRoom.limit(media: media, server: n, horn: topo == "horn") }
            // 2.5 P10: Gruppenanruf ueber Servergrenzen ohne Knoten (ein Server kann keinen nutzen) - einmal sagen
            if let m = CallRoom.meshNote(e), !meshNoted { meshNoted = true; toast = m }
        }
        if let mine = roster.leg(here) {
            inRoom = true
            if !ho.active { companion = mine.companion }
        }
        // Dieses Geraet ist nicht mehr in der Liste, ein anderes eigenes aber schon: es hat uebernommen und dieses
        // abgemeldet - hier beenden (sonst lief der Anruf weiter, Knoten-Anruf "Hierher uebernehmen" iPhone -> Mac)
        // R09: nur wenn das andere Geraet gerade die Fuehrung uebernommen hat (neu, vorher Begleitgeraet bzw. 2.5 vorher
        // handover - dessen Rueckfall hat dieses Geraet abgemeldet, weil state.ready nicht ankam)
        if (inRoom || ready), !ho.active, let other = CallRoom.takenElsewhere(old: old, new: legs, here: here, me: me) {
            audioTrack?.isEnabled = false; mic = false; sound = false; applyPlayback()
            finish(.elsewhere, message: String(localized: "Anruf auf \(other.devName.isEmpty ? String(localized: "anderes Gerät") : other.devName) übernommen."))
            return
        }
        signalRetry()                                                       // 2.5 (9.11.): abgelehnte Buendel erneut
        let ch = CallRoom.diff(old: old, new: legs, here: here)
        if !ch.added.isEmpty || !ch.removed.isEmpty { sigRounds = 0 }       // neue Lage: wieder bis zu retryMax Runden
        if inRoom || ready {
            for x in ch.added where x.who != me || x.device != here {
                toast = String(localized: "\(x.name) (\(x.devName.isEmpty ? String(localized: "Gerät") : x.devName)) ist hinzugekommen")
            }
            for x in ch.removed {
                toast = String(localized: "\(x.name) (\(x.devName.isEmpty ? String(localized: "Gerät") : x.devName)) hat den Anruf verlassen")
            }
        }
        let now = Set(legs.map(\.device))
        if horn != nil { hornRoster(added: ch.added, removed: ch.removed) }   // 2.5: Schluessel, Siegel, Kacheln
        for L in links where L.dev != nil && !now.contains(L.dev!) && L.established && (roomMode || kind == "group") { dropLink(L, nil) }
        if !ch.added.isEmpty || !ch.removed.isEmpty { relimitAll() }
        for x in legs where pend[x.device] != nil && link(x.device) == nil { admit(x.device) }   // Angebot kam vor der Liste
        syncPrimary()
    }
    /// Verbindung zu einem Geraet trennen (hat den Anruf verlassen bzw. Fehler nur auf dieser Verbindung)
    func dropLink(_ L: CallLink, _ msg: String?) {
        guard !L.gone else { return }
        CallEnv.log("drop \(L.dev?.prefix(6) ?? "-")")
        L.close()
        links.removeAll { $0 === L }
        if let d = L.dev { speakerState.drop(d); if speakerDev == d { speakerDev = nil } }
        if let msg { toast = msg }
        if links.isEmpty && !ending {
            status = kind == "group" ? String(localized: "Warte auf die anderen …") : String(localized: "Verbinde …")
        }
        relimitAll()
        hoCheck()
        syncPrimary()
        pipRefresh()
    }

    // MARK: Uebernahme

    func hoTimer() {
        hoTask?.cancel()
        let s = handoverSeconds
        hoTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(s + 0.2))
            guard let self, !Task.isCancelled else { return }
            self.hoCheck()
        }
    }
    /// Neues Geraet: sobald alle Verbindungen zu anderen Personen stehen, Ton und Mikrofon an und dem alten Geraet
    /// Bescheid geben (state.ready + replaces) - das schaltet im selben Moment ab und verlaesst den Anruf
    func hoCheck() {
        guard ho.active, ready, !ending else { return }
        let others = horn != nil ? [horn?.cur != nil] : links.filter { !$0.own && !$0.gone }.map(\.connected)   // 2.5: Knoten verbunden
        switch ho.check(others: others, now: Date().timeIntervalSince1970) {
        case .takeOver(let rep):
            hoTask?.cancel()
            handingOver = false; companion = false
            CallEnv.log("handover: uebernehme von \(rep.prefix(6))")
            sound = true; applyPlayback()
            Task { await micOn() }
            // 2.5 (L2): Rueckfall nur fuer genau dieses Bein (gen) und solange dieses Geraet noch dabei ist (by) - ein schneller
            // Rueckwechsel wirft sonst das zurueckkehrende Geraet hinaus. gen/by jetzt festhalten (nicht erst beim Senden)
            let lb = leaveBody(rep, reason: "handover", by: myDevice)
            if horn != nil {
                // 2.5: ueber einen Knoten gibt es keine Direktverbindung zum alten Geraet - Bescheid ueber den Signalweg,
                // und zur Sicherheit (aeltere App) dessen Platz kurz danach beim Server abmelden
                let body = HornLogic.stateBody(mic: true, cam: cam && camera != nil && !camPaused, screen: sharing, hp: hornMyRefs(), replaces: rep)
                let cid = id, wait = CallRoom.fallbackDelay(horn: true, handoverS: handoverSeconds)
                Task {
                    await hornSend([rep: body])
                    if let cid { postLeave(cid, lb, after: wait) }
                }
            } else if let A = link(rep), A.established {
                stateOne(A, replaces: rep)
                // L2 (B5): auch im Mesh ein Rueckfall, falls state.ready verloren geht (nach handover_s/2, bedingt)
                if ho2, let id { postLeave(id, lb, after: CallRoom.fallbackDelay(horn: false, handoverS: handoverSeconds)) }
            }
            else if let id { postLeave(id, lb) }                            // eigenes Geraet ohne Verbindung
            syncPrimary()
        case .fail:
            handingOver = false
            if let u = ho.undo { promoteUndo(u) }                           // L3: Befoerderung zuruecknehmen
            if joinRole == "handover" {                                      // aus der Leiste: verlassen, das alte bleibt
                if let id, let dev = myDevice { postLeave(id, leaveBody(dev)) }
                finish(nil, message: String(localized: "Übernahme hat nicht geklappt – der Anruf läuft auf dem anderen Gerät weiter."))
            } else {
                toast = String(localized: "Übernahme hat nicht geklappt – der Anruf läuft auf dem anderen Gerät weiter.")
            }
        case .wait, .none: break
        }
    }
    /// Begleitgeraet -> Hauptgeraet ("Hierher übernehmen" im Anruf)
    func takeOver() {
        guard ready, !ending, !ho.active, let dev = myDevice else { return }
        guard let main = CallRoom.mainDevice(roster.legs, me: me, here: dev) else {   // kein anderes eigenes Geraet: selbst Hauptgeraet
            companion = false; sound = true; applyPlayback(); Task { await micOn() }
            return
        }
        // 2.5 (L3) ueber einen Knoten: erst befoerdern (Token mit Ton-Recht), Ton-Bein neu, dann state.ready wie immer
        let promo = CallRoom.needsPromote(horn: horn != nil, v2: ho2)
        ho.begin(rep: main.device, now: Date().timeIntervalSince1970, timeout: handoverSeconds, promote: promo)
        handingOver = true
        if promo { hoTimer(); promoteHere(attempt: ho.attempt); return }
        hoCheck()
        if ho.active { hoTimer() }
    }
    /// L3: POST /promote fuer dieses Geraet, dann Ton-Bein mit den neuen Token neu (gleiche leg_ref ersetzt das alte am
    /// Knoten); klappt etwas nicht: Befoerderung zuruecknehmen, das alte Geraet bleibt Hauptgeraet
    private func promoteHere(attempt n: Int) {
        guard let store, let id, let dev = myDevice else { return }
        let cls = hornDevClass
        Task {
            do {
                let asked = Date().timeIntervalSince1970
                let r = try? await store.apiResponse("api/calls/\(id)/promote", method: "POST", body: CallRoom.promoteBody(device: dev, devClass: cls))
                let j = r.flatMap { ShareJSON.obj($0.0) }
                guard ho.attempt == n, ho.active, !ending, self.id == id else {
                    // Frist (handover_s) lief ab, waehrend promote unterwegs war: spaet eingetragene Befoerderung zuruecknehmen
                    // (sonst ist dieses Geraet still Hauptgeraet und das alte stummes Begleitgeraet)
                    if ho.attempt == n, ho.phase == .failed, !ending, self.id == id, let rep = ho.rep,
                       case .ok(false) = CallRoom.promoteOutcome(code: r?.1, body: j) { promoteUndo(rep) }
                    return
                }
                switch CallRoom.promoteOutcome(code: r?.1, body: j) {
                case .old:                                                  // foederiert ohne promote: wie bisher
                    CallEnv.log("promote: Server kann es nicht - ohne Befoerderung")
                case .failed(let e): throw APIError.other(e ?? String(localized: "Das ging gerade nicht."))
                case .ok(let already):
                    ho.promotedNow(already: already)
                    if let c = j?["call"] as? [String: Any] { rosterIn(CallRoom.legs(c["legs"]), rv: asked, extra: c) }
                    try await hornReAudio(j?["horn"])
                }
                guard ho.attempt == n, ho.active, !ending else { return }
                ho.promoReady()
                hoCheck()
            } catch {
                guard ho.attempt == n, ho.active, !ending else { return }
                hoTask?.cancel()
                if let u = ho.cancel() { promoteUndo(u) }
                handingOver = false
                CallEnv.log("promote: \(error.localizedDescription)")
                toast = error.localizedDescription + " – " + String(localized: "Übernahme hat nicht geklappt – der Anruf läuft auf dem anderen Gerät weiter.")
            }
        }
    }
    /// Befoerderung zuruecknehmen: das bisherige Hauptgeraet wieder befoerdern
    func promoteUndo(_ rep: String) {
        guard let store, let id else { return }
        CallEnv.log("promote: zurueck an \(rep.prefix(6))")
        Task { _ = try? await store.apiResponse("api/calls/\(id)/promote", method: "POST", body: CallRoom.promoteBody(device: rep, devClass: nil)) }
    }
    /// call.mod {action: mute, why: handover}: ein anderes eigenes Geraet fuehrt den Anruf - dieses bleibt still als
    /// Begleitgeraet dabei (kein "hat dich stummgeschaltet", laufende Uebernahme abbrechen)
    func handoverMuted() {
        hoTask?.cancel()
        if let u = ho.cancel() { promoteUndo(u) }
        handingOver = false; companion = true
        if mic { mic = false; audioTrack?.isEnabled = false; CallSystem.shared.muted(self, true); stateOut(); hornMic() }
        sound = false; applyPlayback(); hornAudioRoute()
        toast = String(localized: "Ein anderes eigenes Gerät führt den Anruf – dieses bleibt als Begleitgerät dabei.")
    }
    /// Dieses Geraet wurde von einem anderen eigenen Geraet uebernommen: still schalten, Bescheid geben, verlassen
    func handoverOut(from: String) {
        guard !ending, let id, let dev = myDevice else { return }
        CallEnv.log("handover: abgegeben an \(from.prefix(6))")
        audioTrack?.isEnabled = false; mic = false
        sound = false; applyPlayback()
        // erst "bye" an die anderen (danach nimmt der Server keine Signale dieses Geraets mehr an), dann verlassen
        let bye = byes(reason: "handover", except: from)
        let store = self.store
        let lb = leaveBody(dev, reason: "handover")                        // 2.5 (L5): mit gen - ist dieses Geraet inzwischen wieder beigetreten, gilt es nicht
        Task {
            if !bye.isEmpty { _ = try? await store?.apiResponse("api/calls/\(id)/signal", method: "POST", body: ["device": dev, "signals": bye]) }
            _ = try? await store?.apiResponse("api/calls/\(id)/leave", method: "POST", body: lb)
        }
        let x = roster.leg(from)
        finish(.elsewhere, message: String(localized: "Anruf auf \(x?.devName.isEmpty == false ? x!.devName : String(localized: "anderes Gerät")) übernommen."))
    }

    // MARK: Gruppenanruf beginnen

    /// Gruppen-, freie und Seiten-Chats: erst allein, alle anderen klingeln (call.invite); laeuft schon einer: beitreten
    func startGroup(_ chat: ChatSummary, video: Bool) {
        if busy { toast = String(localized: "Du telefonierst gerade schon."); return }
        if let run = chat.callRunning { title = chat.title; join(run, role: "member", video: video); return }
        if chat.callGroup == false { toast = chat.callGroupBlock ?? String(localized: "Gruppenanrufe gehen in diesem Chat nicht."); return }
        reset()
        kind = "group"; chatID = chat.id; title = chat.title; peerName = chat.title; role = "caller"; media = video ? "video" : "audio"
        joinRole = "member"; speaker = video
        phase = .preparing; status = String(localized: "Kamera und Mikrofon …")
        CallEnv.log("start group \(media)")
        Task {
            do {
                guard let store, await ensureChat(), let dev = myDevice else { throw APIError.other(String(localized: "Chats sind auf diesem Gerät noch nicht bereit.")) }
                guard await loadConfig() else { throw APIError.other(String(localized: "Anrufe sind auf diesem Server abgeschaltet.")) }
                guard multi else { throw APIError.other(String(localized: "Gruppenanrufe brauchen Saga 2.4 auf diesem Server.")) }
                if hornMeshHint(members: max(chat.memberCount, chat.members.count), video: video) {   // 2.5: kein Knoten bereit
                    toast = String(localized: "Kein Knoten für große Gruppenanrufe bereit – es gilt die Grenze für direkte Anrufe (höchstens 4 Geräte mit Video bzw. 6 nur mit Ton).")
                }
                try await prepareMedia(video: video)
                guard phase == .preparing, !ending else { return }
                let cid = ChatCrypto.hexID()
                id = cid
                CallSystem.shared.startOutgoing(self, id: cid, name: title, video: media == "video")
                let asked = Date().timeIntervalSince1970
                let (d, code) = try await store.apiResponse("api/calls", method: "POST", body: ["id": cid, "chat_id": chatID, "media": media, "device": dev, "caps": myCaps,
                                                                                                "dev_class": hornDevClass])
                let j = ShareJSON.obj(d)
                guard !ending, id == cid else {
                    if (200..<300).contains(code) { _ = try? await store.apiResponse("api/calls/\(cid)/end", method: "POST", body: ["reason": "hangup", "device": dev]) }
                    return
                }
                if code == 409, let run = (j?["running"] as? String) ?? ((j?["running"] as? [String: Any])?["id"] as? String) {
                    CallSystem.shared.endReported(cid, reason: .remoteEnded, local: true)
                    let t = title
                    reset(); phase = .idle; title = t
                    join(run, role: "member", video: video); return
                }
                guard (200..<300).contains(code), let c = j?["call"] as? [String: Any] else { throw APIError.http(code, ShareJSON.str(j?["error"]) ?? "") }
                adoptCall(c)
                await adoptV2(c)
                ready = true; inRoom = true
                rosterIn(CallRoom.legs(c["legs"]), rv: asked, extra: c)
                if topo == "horn" { hornGo(c["horn"]) }                       // 2.5: ueber einen Knoten
                phase = .ringingOut; status = String(localized: "Klingelt bei den anderen …")
                CallTones.shared.play(.ringback)
                aliveOn()
                let pending = early; early = []
                for s in pending { await handleRaw(s) }
            } catch { failed = true; finish(nil, message: error.localizedDescription) }
        }
    }

    // MARK: Leiste "Anruf laeuft" (andere eigene Geraete, Gruppenanrufe in eigenen Chats)

    /// Bald neu holen (nach call.roster/call.state/call.invite; mehrere Ereignisse zusammenfassen)
    func runSoon() {
        runTask?.cancel()
        runTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard let self, !Task.isCancelled else { return }
            await self.refreshRunning(ringDM: false)
        }
    }
    /// GET /api/calls/active: klingelnde 1:1-Anrufe (nach dem Start), Gruppen-Einladungen, Leiste
    func refreshRunning(ringDM: Bool) async {
        guard let store, AppConfig.hasServer, let (d, code) = try? await store.apiResponse("api/calls/active"), code == 200 else { return }
        let rows = ShareJSON.obj(d)?["calls"] as? [[String: Any]] ?? []
        var bars: [CallRoom.Running] = []
        for c in rows {
            guard let x = CallRoom.running(c) else { continue }
            if ringDM, !busy, x.kind == "dm", x.state == "ringing", x.role == "callee" { await ringing(callID: x.id); continue }
            if x.kind == "group", !x.mine.isEmpty, incoming?.id == x.id { stopGroupRing(x.id, declined: false) }
            else if x.kind == "group", x.invite == "ringing", x.mine.isEmpty, !busy, incoming == nil, !done.contains(x.id),
                    Date().timeIntervalSince1970 - x.created < x.ringS {
                await ringing(callID: x.id)
            }
            if bar(x) != .none { bars.append(x) }
        }
        if runBars != bars { runBars = bars }
    }
    func bar(_ x: CallRoom.Running) -> CallRoom.Bar {
        CallRoom.bar(x, here: myDevice ?? "", current: busy ? id : nil, ringing: incoming?.id == x.id, rejoin: ho2)
    }

    // MARK: Faehigkeiten melden

    /// PUT /api/calls/caps - zu Gruppenanrufen eingeladen wird nur, wer ein Geraet mit "multi" hat. Einmal je Geraet und
    /// Faehigkeiten, sobald die Chat-Schluessel bereit sind (und nach einem Update)
    func reportCaps() async {
        guard let store, let dev = myDevice else { return }
        guard serverFeatures.contains("multi") else { if !serverFeatures.isEmpty || !cfg.isEmpty { capsSent = "-" }; return }   // Server ohne Raum
        let key = CallRoom.capsKey(device: dev, caps: myCaps)
        guard key != capsSent else { return }
        capsSent = key
        let ok = (try? await store.apiResponse("api/calls/caps", method: "PUT", body: ["device": dev, "caps": myCaps]))
            .map { (200..<300).contains($0.1) } ?? false
        if !ok { capsSent = "" }
    }
}

/// Hinweis statt Fehler (z. B. 409 handover_busy): Anruf hier nicht aufbauen, aber nicht als fehlgeschlagen zeigen
struct CallHint: LocalizedError {
    let text: String
    var errorDescription: String? { text }
}
