import Foundation

// 2.5: VoIP-Push fuer Knoten-Anrufe - 1:1 ueber Knoten ("call.group" mit dm) und echte Gruppenanrufe
// (Felder wie saga/push.py _send_apns: kind, title, body, url, call, chat, media, caller, group, dm)
func voipGroupTests() {
    let id = String(repeating: "c", count: 32)
    let dmInfo: [AnyHashable: Any] = ["aps": [:] as [String: Any], "kind": "call.group", "title": "Saga", "body": "Videoanruf von Anna",
                                      "url": "/chats#call-" + id, "call": id, "chat": 12, "media": "video", "caller": "Anna", "group": true, "dm": true]
    let dm = CallLogic.push(dmInfo)
    check(dm?.call == id && dm?.chat == 12 && dm?.media == "video" && dm?.caller == "Anna", "VoIP call.group dm: Kennung, Chat, Video, Person")
    check(dm?.group == true && dm?.dm == true && dm?.groupChat == false, "dm: Raum-Anruf, aber kein Gruppenanruf")
    check(dm.map { CallLogic.systemName($0, chatTitle: "Familie", title: "Saga") } == "Anna", "CallKit bei dm: Name der Person (nicht Chat)")

    var grpInfo = dmInfo; grpInfo["dm"] = nil; grpInfo["media"] = "audio"; grpInfo["body"] = "Gruppenanruf von Anna"
    let g = CallLogic.push(grpInfo)
    check(g?.groupChat == true && g?.dm == false && g?.media == "audio", "Gruppenanruf: groupChat, nur Ton")
    check(g.map { CallLogic.systemName($0, chatTitle: "Familie", title: "Saga") } == "Anna · Familie", "CallKit Gruppe: Person · Chatname (wie beim Klingeln)")
    check(g.map { CallLogic.systemName($0, chatTitle: nil, title: "Saga") } == "Gruppenanruf von Anna", "Gruppe ohne Chatnamen: Mitteilungstext statt Person")
    check(g.map { CallLogic.systemName($0, chatTitle: "  ", title: "Saga") } == "Gruppenanruf von Anna", "leerer Chatname zaehlt nicht")

    // Server-Varianten (Strings, fehlende Felder)
    let s = CallLogic.push(["kind": "call.group", "call": id, "chat": "7", "dm": "1", "caller": ""])
    check(s?.chat == 7 && s?.dm == true && s?.caller == nil && s?.media == "audio", "chat/dm als Text, leerer caller, Ton als Vorgabe")
    check(CallLogic.push(["kind": "call", "call": id, "dm": true])?.dm == false, "dm nur bei Raum-Anrufen")
    check(CallLogic.push(["kind": "call.group", "call": "kaputt"]) == nil, "ungueltige Kennung: kein Anruf (CallKit meldet und beendet sofort)")
    check(CallLogic.push(["kind": "chat.message", "call": id]) == nil, "andere Art: kein Anruf")
    let one = CallLogic.push(["kind": "call", "call": id, "caller": "Bert", "media": "video"])
    check(one.map { CallLogic.systemName($0, chatTitle: "x", title: "Saga") } == "Bert" && one?.groupChat == false, "1:1 klassisch: Person")
    var anon = grpInfo; anon["caller"] = nil
    check(CallLogic.push(anon).map { CallLogic.systemName($0, chatTitle: "Familie", title: "Saga") } == "Familie", "Gruppe ohne Person: Chatname")
    check(CallLogic.push(["kind": "call", "call": id, "body": "Eingehender Anruf"]).map { CallLogic.systemName($0, chatTitle: nil, title: "Saga") } == "Eingehender Anruf", "1:1 ohne Namen: Text")
    check(CallLogic.push(["kind": "call", "call": id]).map { CallLogic.systemName($0, chatTitle: nil, title: "Saga") } == "Saga", "ohne Namen: Titel")

    // Name beim Klingeln (App)
    check(CallLogic.ringName(who: "Anna", chat: "Familie", dm: true, body: nil) == "Anna", "Klingeln dm: nur Person")
    check(CallLogic.ringName(who: "Anna", chat: "Familie", dm: false, body: nil) == "Anna · Familie", "Klingeln Gruppe: Person · Chat")
    check(CallLogic.ringName(who: "Anna", chat: nil, dm: false, body: "Gruppenanruf von Anna") == "Gruppenanruf von Anna", "Gruppe ohne Chatnamen: Text")
    check(CallLogic.ringName(who: "", chat: "Familie", dm: false, body: nil) == "Familie", "ohne Person: Chat")

    // Klingeln beobachten (Abfrage /api/calls/<id>)
    check(CallLogic.ringPoll(group: true, state: "ringing", mine: 0, invite: "ringing", age: 3, ringS: 45) == .keep, "Gruppe klingelt weiter")
    check(CallLogic.ringPoll(group: true, state: "ended", mine: 0, invite: "missed", age: 50, ringS: 45) == .ended, "Anrufer weg/abgelaufen: beendet")
    check(CallLogic.ringPoll(group: true, state: "active", mine: 1, invite: "joined", age: 5, ringS: 45) == .elsewhere, "eigenes Geraet beigetreten")
    check(CallLogic.ringPoll(group: true, state: "active", mine: 0, invite: "joined", age: 5, ringS: 45) == .elsewhere, "Einladung anderswo erledigt")
    check(CallLogic.ringPoll(group: true, state: "ringing", mine: 0, invite: "declined", age: 5, ringS: 45) == .declined, "anderswo abgelehnt")
    check(CallLogic.ringPoll(group: true, state: "active", mine: 0, invite: "ringing", age: 46, ringS: 45) == .expired, "Gruppe laeuft weiter, Klingeln abgelaufen")
    check(CallLogic.ringPoll(group: false, state: "ringing", mine: 0, invite: nil, age: 46, ringS: 45) == .keep, "1:1: Server entscheidet ueber Ablauf")
    check(CallLogic.ringPoll(group: false, state: "active", mine: 0, invite: nil, age: 5, ringS: 45) == .elsewhere, "1:1 anderswo angenommen")
    check(CallLogic.ringPoll(group: false, state: "ended", mine: 0, invite: nil, age: 5, ringS: 45) == .ended, "1:1 aufgelegt")

    // Kein doppeltes Klingeln, Schutz gegen haengendes CallKit
    check(CallLogic.hideWhileRunning(kind: "call.group", remote: true) && CallLogic.hideWhileRunning(kind: "call", remote: true), "Anruf-Mitteilung im Vordergrund nicht zeigen")
    check(!CallLogic.hideWhileRunning(kind: "chat.message", remote: true) && !CallLogic.hideWhileRunning(kind: "call", remote: false), "Chat bzw. eigene (Mac-)Mitteilung zeigen")
    check(CallLogic.voipStale(reported: true, ringing: false, current: false), "gemeldet, klingelt nicht: beenden")
    check(!CallLogic.voipStale(reported: true, ringing: true, current: false) && !CallLogic.voipStale(reported: true, ringing: false, current: true), "klingelt bzw. angenommen: lassen")

    // Annehmen ueber CallKit: Raum-Anruf geht in den Knoten-Weg (Beitreten via /join, ohne Angebote)
    check(CallRoom.ringGroup(invite: "ringing", mine: [], silent: false, busy: false, age: 2, ringS: 45), "dm-Raum klingelt")
    check(!CallRoom.ringGroup(invite: "missed", mine: [], silent: false, busy: false, age: 2, ringS: 45), "nicht beitretbar: CallKit sofort beenden")
}
