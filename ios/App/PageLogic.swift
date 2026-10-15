import Foundation

// Reine Logik aus der Fehlerbehebung nach dem Bughunt 2.0 (ohne SwiftUI) - mit Tests/Logic/run.sh pruefbar.

/// Seitentitel ist einzeilig (BUG-062): Return springt in den Inhalt, eingefuegte Umbrueche werden zu Leerzeichen.
enum TitleInput {
    static func isBreak(_ c: Character) -> Bool { c.isNewline }
    /// `old`: bisheriger Titel, `new`: Inhalt des Textfelds. Rueckgabe: zu speichernder Titel und ob Return gedrueckt wurde.
    static func apply(old: String, new: String) -> (title: String, enter: Bool) {
        guard new.contains(where: isBreak) else { return (new, false) }
        // nur Umbrueche dazugekommen (Return getippt): Titel bleibt, Sprung in den ersten Block
        if new.filter({ !isBreak($0) }) == old.filter({ !isBreak($0) }) { return (old.filter { !isBreak($0) }, true) }
        // eingefuegter Text mit Zeilen: zu einer Zeile zusammenfassen
        var out = "", lastSpace = false
        for c in new {
            if isBreak(c) { if !lastSpace && !out.isEmpty { out.append(" "); lastSpace = true } }
            else { out.append(c); lastSpace = c == " " }
        }
        return (out.trimmingCharacters(in: .whitespaces), false)
    }
}

/// Entwuerfe nachsenden (BUG-020): Was eine Speicherung eines liegengebliebenen Entwurfs bedeutet.
enum DraftResend {
    enum Outcome: Equatable { case sent, hold, retry }
    /// 2xx: gespeichert -> Entwurf weg. 409 (andere Fassung) und dauerhafte Fehler: nicht wieder automatisch senden,
    /// sondern beim Oeffnen der Seite anbieten. Sonst (5xx, 429, offline): beim naechsten Abgleich erneut.
    static func outcome(_ code: Int) -> Outcome {
        if (200..<300).contains(code) { return .sent }
        if code == 409 || [400, 403, 404, 413, 423].contains(code) { return .hold }
        return .retry
    }
    /// Seiten-ID aus dem Dateinamen eines Entwurfs ("12.json", Foederation "-2049.json")
    static func pageID(_ file: String) -> Int? {
        guard file.hasSuffix(".json") else { return nil }
        return Int(file.dropLast(5))
    }
    /// Kandidaten: nicht als Konfliktkopie markiert und nicht gerade in einem offenen Editor (der sendet selbst)
    static func shouldSend(_ d: PageDraft, id: Int, open: Set<Int>) -> Bool { !d.conflict && !open.contains(id) }
}

/// Anmelden (BUG-063): vor dem System-Anmeldefenster pruefen, ob unter der Adresse ein Saga-Server antwortet.
enum ServerProbe {
    enum Result: Equatable { case ok, unreachable, notSaga }
    /// `versionCode`/`versionIsSaga`: Antwort von /api/version (nil = keine Verbindung);
    /// `loginCode`: Antwort von /app/login ohne Challenge (nur gefragt, wenn /api/version nichts Brauchbares liefert)
    static func classify(versionCode: Int?, versionIsSaga: Bool, loginCode: Int?) -> Result {
        guard let v = versionCode else { return .unreachable }
        if v == 200 && versionIsSaga { return .ok }
        // aeltere Server ohne /api/version: /app/login beantwortet eine Anfrage ohne Challenge mit 400
        if loginCode == 400 { return .ok }
        return .notSaga
    }
    /// /api/version-Antwort eines Saga-Servers: JSON mit "version"
    static func isSagaVersion(_ d: Data) -> Bool {
        ((try? JSONSerialization.jsonObject(with: d)) as? [String: Any])?["version"] is String
    }
}

/// Startseite gehoert zu Server + Konto (BUG-060): nach einem Kontowechsel nicht die Seite des alten Kontos oeffnen.
enum HomeOwner {
    static func key(origin: String, account: String) -> String { origin + "\n" + account }
    /// Gemerkter Besitzer passt nicht zum angemeldeten Konto -> vergessen. Ohne gemerkten Besitzer (alte Fassung): behalten.
    static func mustForget(stored: String?, origin: String, account: String) -> Bool {
        guard let stored, !stored.isEmpty else { return false }
        return stored != key(origin: origin, account: account)
    }
}
