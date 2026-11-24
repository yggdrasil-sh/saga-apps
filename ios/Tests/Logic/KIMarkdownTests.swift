import Foundation

// KI-Darstellung (Bugs-DB 2248): AILogic.lines/spans gegen die Server-Vektoren (Tests/vectors/ki_md.json, Kopie von
// docs/ki-markdown-vektoren.json aus dem Repo saga, erzeugt mit tools/ki_md_vectors.py)
func kiMarkdownTests() {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("vectors/ki_md.json")
    guard let d = try? Data(contentsOf: url), let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
          let vs = j["assistant"] as? [[String: Any]] else { check(false, "ki_md.json lesen"); return }
    let cites = Set((j["cites"] as? [Int]) ?? [])
    func want(_ lines: [[String: Any]]) -> [AILogic.MDLine] {
        lines.map { l in
            let spans: [AILogic.Span] = (l["s"] as? [[Any]] ?? []).map { sp in
                let k = sp[0] as? String ?? ""
                switch k {
                case "b": return .bold(sp[1] as? String ?? "")
                case "c": return .code(sp[1] as? String ?? "")
                case "n": return .cite(sp[1] as? Int ?? -1)
                default: return .text(sp[1] as? String ?? "")
                }
            }
            return AILogic.MDLine(kind: AILogic.MDKind(rawValue: l["k"] as? String ?? "") ?? .p, n: l["n"] as? Int, spans: spans)
        }
    }
    var bad = 0
    for v in vs {
        let inp = v["in"] as? String ?? ""
        let got = AILogic.lines(inp, cites: cites), exp = want(v["lines"] as? [[String: Any]] ?? [])
        if got != exp { bad += 1; print("  ki_md abweichend: \(inp.debugDescription) -> \(got)") }
    }
    check(vs.count > 20 && bad == 0, "KI-Darstellung wie der Server (\(vs.count) Vektoren, \(bad) abweichend)")
}
