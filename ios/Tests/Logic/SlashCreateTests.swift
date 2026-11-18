import Foundation

// 2.5 (Bug 2151): "/Seite" und "/Datenbank" legen mit "link": false an (Verweis/Block setzt der Editor selbst)
func slashCreateTests() {
    let p = SlashCreate.body(database: false, parent: 7, dbTitle: "Neue Datenbank")
    check((p["link"] as? Bool) == false && (p["parent_id"] as? Int) == 7 && (p["title"] as? String) == "" && p["kind"] == nil, "/Seite: link false")
    let d = SlashCreate.body(database: true, parent: -2049, dbTitle: "Neue Datenbank")
    check((d["link"] as? Bool) == false && (d["kind"] as? String) == "database" && (d["parent_id"] as? Int) == -2049
          && (d["icon"] as? String) == "🗃️" && (d["title"] as? String) == "Neue Datenbank", "/Datenbank: link false")
    let j = (try? JSONSerialization.data(withJSONObject: p)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    check(j.contains("\"link\":false"), "/Seite: als JSON false (kein 0)")
}
