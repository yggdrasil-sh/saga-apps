import Foundation

// Hinweis bei name@server/E-Mail in der Chat-Personensuche und remote_hint im Teilen-Dialog
// (App/ChatLogic.swift ChatFedHint, App/ShareLogic.swift remoteHint)
func fedHintTests() {
    typealias F = ChatFedHint
    // Erkennung wie im Web (static/chat.js fedAddr)
    for q in ["bob@saga.example.org", "@bob@saga.example.org", " vorname.name@firma.de ", "bob@host:8163", "@sc-fed@b.test:8163"] {
        check(F.isAddress(q), "Adresse erkannt: \(q)")
    }
    for q in ["bob", "bob@", "@bob", "bob@localhost", "bob@@x.de", "bob @x.de", "a@b@c.de", "Bob Bauer", ""] {
        check(!F.isAddress(q), "keine Adresse: \(q)")
    }
    // nur ohne lokale Treffer und ab 3 Zeichen
    check(F.show("bob@saga.example.org", hits: 0), "0 Treffer -> Hinweis")
    check(!F.show("bob@saga.example.org", hits: 1), "lokale Treffer haben Vorrang")
    check(!F.show("bob", hits: 0), "kein name@server -> \"Keine Treffer.\"")
    // Anleitung je Sprache
    check(F.guideURL(lang: "de-DE").absoluteString.hasSuffix("docs/ANLEITUNG.md#mit-anderen-saga-instanzen-teilen-f%C3%B6deration"), "Anleitung de")
    check(F.guideURL(lang: "es").absoluteString.hasSuffix("docs/es/GUIA.md#compartir-con-otras-instancias-de-saga-federaci%C3%B3n"), "Guia es")
    for l in ["en", "ru", "th", "fr"] {
        check(F.guideURL(lang: l).absoluteString.hasSuffix("docs/en/GUIDE.md#sharing-with-other-saga-instances-federation"), "Guide \(l)")
    }
    // Teilen: remote_hint (neuer Server) statt "Niemand gefunden.", aeltere Server ohne Feld -> nil
    let neu = #"{"users":[],"groups":[],"remote":[],"remote_hint":"Mit x.de ist dieser Server nicht verbunden."}"#.data(using: .utf8)!
    check(ShareLogic.remoteHint(neu) == "Mit x.de ist dieser Server nicht verbunden.", "remote_hint gelesen")
    check(ShareLogic.parseTargets(neu).isEmpty, "keine Treffer")
    check(ShareLogic.remoteHint(#"{"users":[],"groups":[]}"#.data(using: .utf8)!) == nil, "aelterer Server: kein Feld")
    check(ShareLogic.remoteHint(#"{"users":[],"remote_hint":null}"#.data(using: .utf8)!) == nil, "null")
    check(ShareLogic.remoteHint(#"{"users":[],"remote_hint":"  "}"#.data(using: .utf8)!) == nil, "leer")
}
