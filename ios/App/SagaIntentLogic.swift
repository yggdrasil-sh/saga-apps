import Foundation

// 2.6 K-G (Bugs-DB 2275): Siri/App Shortcuts "Frag Saga", "Gib Saga einen Auftrag", "Was steht heute an" - reine Logik
// ohne AppIntents/SwiftUI (Tests/Logic/SagaIntentTests.swift). Bauplan K-G §2.3, Entscheidungen vom 28.11.
// F2 (a) Antwort in Siri, nach ~12 s weiter in der App - der Strom laeuft im SagaModel weiter (ein Abbruch stoppte
// serverseitig das Modell) · F3 Auftrag gleich bis zur Plan-Karte planen, NIE approve/act aus Siri · Siri-Saetze nennen
// immer "Saga" (App-Name), nie den Persona-Namen · F7 kein ControlWidget.
// Texte zum Vorlesen kommen fertig vom Server (Antwort, `sentence`, `waiting.text`) - hier wird nur gekuerzt.

enum SagaIntentLogic {
    /// So lange wartet "Frag Saga" in Siri auf `done`; danach weiter in der App (F2)
    static let answerWait: TimeInterval = 12
    /// So lange wartet "Gib Saga einen Auftrag" auf den Plan; danach weiter in der App (Planen laeuft weiter)
    static let planWait: TimeInterval = 12                       // wie Ask: Siri bricht laenger Wartendes ggf. selbst ab (Pruefer A2), danach App
    /// Hoechstens so viele Saetze liest Siri vor
    static let maxSentences = 3
    /// ... und hoechstens so viele Zeichen (Thai u. a. ohne Satzzeichen)
    static let maxChars = 420

    enum Intent { case ask, give, today }

    /// Ob ein Intent laufen darf - sonst ein fester Hinweis bzw. die App (Kennenlernen)
    enum Gate: Equatable {
        case ok
        /// nicht angemeldet: "Melde dich zuerst in der Saga-App an."
        case signIn
        /// keine KI fuer diese Person / aelterer Server / keine Auftraege: kurzer Hinweis, nichts geht ans Modell
        case off
        /// KI da, aber noch keine Einwilligung (bzw. widerrufen/stale): App mit der Kennenlernen-Karte oeffnen
        case intro
    }

    /// "Frag Saga" und "Auftrag": Ort "Saga" (Faehigkeit + Server mit Einwilligung) und erteilte Einwilligung;
    /// Auftraege zusaetzlich `status.jobs` (Stufe nicht "answers"). Ohne Einwilligung nie ans Modell (K19).
    static func gate(_ i: Intent, signedIn: Bool, status: SagaStatus?) -> Gate {
        guard signedIn else { return .signIn }
        guard let s = status, s.hasPlace else { return .off }
        if i == .give && !s.jobsOn { return .off }
        return s.consentOK ? .ok : .intro
    }

    /// Laeuft gerade eine Frage bzw. ein Planen? Eine liegengebliebene Fehler- oder Abbruch-Karte zaehlt nicht (sonst
    /// sagte Siri bis zum Wegwischen der Karte immer "beschaeftigt"); `send`/`giveInThread` raeumen sie selbst weg.
    static func busy(asking: Bool, askFailed: Bool, planning: Bool, planEnded: Bool) -> Bool {
        (asking && !askFailed) || (planning && !planEnded)
    }

    /// "Was steht heute an": Server-Merkmal `aitoday1` und Faehigkeit `ai` (sonst `{enabled:false}`); keine Einwilligung
    /// noetig (kein Modell). `state` nil = noch nicht gefragt (nur Merkmal pruefen)
    static func todayGate(signedIn: Bool, features: Set<String>, state: TodayLineState?) -> Gate {
        guard signedIn else { return .signIn }
        guard TodayLineLogic.shouldFetch(features: features) else { return .off }
        if let state, !state.enabled { return .off }
        return .ok
    }

    // MARK: Vorlesen

    /// Antwort fuer Siri: Quellen-Marker [1] / [1, 2] raus, Markdown-Zeichen raus, Zeilen zu Saetzen, hoechstens
    /// 3 Saetze und `maxChars` Zeichen. `cut` = gekuerzt (Siri sagt dann "Mehr in der App")
    static func speech(_ raw: String) -> (text: String, cut: Bool) {
        var t = raw.replacingOccurrences(of: #"\s*\[\d+(?:\s*[,;]\s*\d+)*\]"#, with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: #"(?m)^\s*(?:#{1,6}\s+|[-*•]\s+|\d+[.)]\s+|>\s*)"#, with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: #"\*\*|__|`"#, with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: #"\s*\n+\s*"#, with: "\n", options: .regularExpression)
        // Zeilen ohne Satzzeichen am Ende (Listenpunkte) als eigene Saetze lesen
        let lines = t.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        t = lines.map { l in l.last.map { ".!?…:;。".contains($0) } == true ? l : l + "." }.joined(separator: " ")
        t = t.replacingOccurrences(of: #"\s{2,}"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return ("", false) }

        var sentences = Self.sentences(t)
        if sentences.isEmpty { sentences = [t] }
        var out = sentences.prefix(maxSentences).joined(separator: " ")
        var cut = sentences.count > maxSentences
        if out.count > maxChars {
            let head = String(out.prefix(maxChars))
            // an einer Wortgrenze kuerzen (ohne Leerzeichen, z. B. Thai: hart)
            let cutAt = head.lastIndex(where: { $0 == " " }).map { String(head[..<$0]) } ?? head
            out = cutAt.trimmingCharacters(in: CharacterSet(charactersIn: " ,;:–-")) + "…"
            cut = true
        }
        return (out, cut)
    }

    /// Abkuerzungen, nach denen ein Punkt kein Satzende ist (Kleinschreibung, ohne Punkt)
    private static let abbreviations: Set<String> = ["dr", "nr", "st", "str", "hr", "fr", "frau", "prof", "ca", "bzw", "usw", "vgl", "ggf",
                                                     "evtl", "inkl", "zzgl", "tel", "mr", "mrs", "ms", "jr", "sr", "etc", "vs", "sra", "sr", "dra", "ул", "г", "д"]
    /// Saetze ohne Sprachmodell: Ende bei . ! ? … mit folgendem Leerraum; ein Punkt nur vor Grossbuchstaben, Ziffer oder
    /// Anfuehrungszeichen und nicht hinter Zahlen ("am 7. Juli"), Einzelbuchstaben ("z. B.") oder bekannten Abkuerzungen
    static func sentences(_ t: String) -> [String] {
        let chars = Array(t)
        var out: [String] = [], cur = ""
        func flush() { let x = cur.trimmingCharacters(in: .whitespacesAndNewlines); if !x.isEmpty { out.append(x) }; cur = "" }
        var i = 0
        while i < chars.count {
            let c = chars[i]; cur.append(c)
            if ".!?…。".contains(c) {
                while i + 1 < chars.count, "\"“”»«')]".contains(chars[i + 1]) { i += 1; cur.append(chars[i]) }
                if i + 1 >= chars.count || chars[i + 1].isWhitespace {
                    var j = i + 1
                    while j < chars.count, chars[j].isWhitespace { j += 1 }
                    var split = true
                    if c == ".", j < chars.count {
                        let next = chars[j]
                        split = next.isUppercase || next.isNumber || "„“\"«(".contains(next)
                        let word = String(cur.dropLast().reversed().prefix(while: { $0.isLetter || $0.isNumber }).reversed())
                        if word.isEmpty || word.allSatisfy(\.isNumber) || (word.count == 1 && word.first?.isLetter == true)
                            || abbreviations.contains(word.lowercased()) { split = false }
                    }
                    if split { flush() }
                }
            }
            i += 1
        }
        flush()
        return out
    }

    /// "Was steht heute an": `sentence` (Gruss + Tag, Persona-Form vom Server) und bei n > 0 `waiting.text` (ohne
    /// das Zeichen "›" des Links); ohne `sentence` die Vorlesezeile der Tageszeile. nil = nichts vom Server
    static func todaySpeech(_ s: TodayLineState?) -> String? {
        guard let s, s.enabled else { return nil }
        var parts: [String] = []
        let sentence = (s.sentence ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !sentence.isEmpty { parts.append(sentence) }
        else if s.on != false, let l = s.line {
            let a = (l.a11y?.isEmpty == false ? l.a11y! : l.text).trimmingCharacters(in: .whitespacesAndNewlines)
            if !a.isEmpty { parts.append(a) }
        }
        if let w = s.waiting, (w.n ?? s.counts?.waiting ?? 0) > 0 {
            let x = w.text.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "›>")))
            if !x.isEmpty { parts.append(x.last.map { ".!?…".contains($0) } == true ? x : x + ".") }
        }
        return parts.isEmpty ? nil : parts.joined(separator: " ")
    }

    // MARK: Auftrag (F3) - endet an der Plan-Karte

    /// Der einzige Serverweg von "Gib Saga einen Auftrag": POST /api/ai/jobs mit `thread: true` (Ort "Saga"), Quelle
    /// "saga". Freigabe (`/approve`) und Aktionen (`/act`) gibt es von hier nicht (RT-32).
    static func giveRequest(_ text: String) -> (path: String, method: String, body: [String: Any]) {
        var b = SagaLogic.giveBody(text: text)
        b["thread"] = true
        return ("api/ai/jobs", "POST", b)
    }

    /// Wie der Auftrag aus Siri endet
    enum GiveEnd: Equatable {
        /// Plan liegt bereit (Plan-Karte in der App, wartet auf das OK der Person): "Der Plan liegt in Saga bereit."
        case plan(job: Int)
        /// Rueckfrage: in der App beantworten
        case ask
        /// Absage (Text vom Server, gekuerzt vorlesen), Alternative in der App
        case refuse(String)
        /// plant noch: weiter in der App (das Planen laeuft im Modell weiter)
        case planning
        /// nichts passiert (Fehler vom Server bzw. Netz)
        case failed(String)
    }

    /// Ergebnis nach dem Warten: laeuft noch, Fehler, oder der neueste KI-Zug nach der Bitte - ein Server-Zug mit
    /// n > n0 (hoechste Nummer vorher) bzw. bei aelteren Servern ein lokal angehaengter Zug hinter `count`
    static func giveEnd(running: Bool, error: String?, turns: [SagaTurn], n0: Int, count: Int) -> GiveEnd {
        if running { return .planning }
        if let error { return .failed(error) }
        guard let i = turns.lastIndex(where: { !$0.mine }) else { return .failed("") }
        let ai = turns[i]
        guard (ai.n ?? 0) > n0 || (ai.n == nil && i >= count) else { return .failed("") }
        switch ai.kind(jobsOn: true) {
        case .plan(let j): return .plan(job: j)
        case .ask: return .ask
        case .refuse: return .refuse(ai.text)
        default: return .failed("")
        }
    }

    /// Wie "Frag Saga" endet
    enum AskEnd: Equatable {
        case answer(String, cut: Bool)
        /// laeuft noch (> 12 s) oder eine Frage laeuft schon: weiter in der App, Strom NICHT abbrechen
        case foreground
        case failed(String)
    }
    static func askEnd(running: Bool, error: String?, answer: String?) -> AskEnd {
        if running { return .foreground }
        if let error { return .failed(error) }
        let s = speech(answer ?? "")
        return s.text.isEmpty ? .foreground : .answer(s.text, cut: s.cut)
    }
}
