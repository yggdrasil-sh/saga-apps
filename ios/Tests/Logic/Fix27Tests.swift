import Foundation

// fix27 (Galerie-Testrunde 27.10.): Formate (#10), Relationsfilter
func fix27Tests() {
    let de = Locale(identifier: "de_DE"), en = Locale(identifier: "en_US")

    // MARK: #10 Formate
    check(NumFormat.kind("percent") == "pct" && NumFormat.kind("pct") == "pct" && NumFormat.kind("euro") == "eur"
          && NumFormat.kind("eur") == "eur" && NumFormat.kind(nil) == "num" && NumFormat.kind("bitcoin") == "num", "Format-Kennungen")
    let p41 = NumFormat.text(41.0377, fmt: "percent", locale: de)
    check(p41.hasPrefix("41") && p41.hasSuffix("%") && !p41.contains(","), "Prozent ganzzahlig (\(p41))")
    let p119 = NumFormat.text(119.2, fmt: "pct", locale: de)
    check(p119.hasPrefix("119") && !p119.contains(","), "ueber 100 % echt (\(p119))")
    check(NumFormat.text(0.5, fmt: "pct", locale: en).hasPrefix("1"), "Prozent kaufmaennisch gerundet")
    let e36 = NumFormat.text(36, fmt: "euro", locale: de)
    check(e36.contains("36,00") && e36.contains("€"), "Euro immer 2 Nachkommastellen (\(e36))")
    let e485 = NumFormat.text(48.5, fmt: "euro", locale: de)
    check(e485.contains("48,50"), "Euro 48,50 (\(e485))")
    check(NumFormat.text(1430.5, fmt: "eur", locale: de).contains("1.430,50"), "Euro mit Tausenderpunkt")
    check(NumFormat.text(9.5, fmt: "euro", locale: en).contains("€9.50"), "Euro in der Sprache (en)")
    check(NumFormat.text(2.0 / 3, fmt: nil, locale: en) == "0.67" && NumFormat.text(1234.5, fmt: nil, locale: de) == "1.234,5", "Zahl bis 2 Nachkommastellen")
    check(NumFormat.text(nil, fmt: "euro") == "–" && NumFormat.text(.infinity, fmt: "pct") == "–", "kein Wert")
    check(NumFormat.editText(1430.5, locale: de) == "1430,5" && NumFormat.editText(36, locale: en) == "36", "Eingabefeld ohne Einheit/Gruppierung")
    check(WidgetLogic.format(42.4, fmt: "pct", locale: de).hasPrefix("42"), "Widget nutzt dasselbe Format")
    let blk = WidgetLogic.Block(kind: "progress", cfg: ["src": "db"])
    var v = WidgetLogic.Value(); v.fmt = "eur"
    check(WidgetLogic.progressFmt(blk, value: v) == "eur" && WidgetLogic.progressFmt(blk, value: nil) == "num", "Fortschritt: Format vom Server")
    v.fmt = "pct"
    check(WidgetLogic.progressFmt(blk, value: v) == "num", "Fortschritt: Prozent nicht doppelt")

    // MARK: Relationsfilter (wie saga/dbquery.py seit 82e26d6)
    let json = """
    {"schema": {"props": [{"id": "projekt", "name": "Projekt", "type": "relation"}, {"id": "tag", "name": "Tag", "type": "text"}],
      "views": [{"id": "v1", "filters": [{"pid": "projekt", "op": "contains", "value": 7}]},
                {"id": "v2", "filters": [{"pid": "projekt", "op": "not_contains", "value": "7"}]},
                {"id": "v3", "filters": [{"pid": "tag", "op": "contains", "value": "Ab"}]}]},
     "rows": [{"id": 1, "title": "mit", "props": {"projekt": [7, 9]}},
              {"id": 2, "title": "ohne", "props": {}},
              {"id": 3, "title": "leer", "props": {"projekt": []}},
              {"id": 4, "title": "andere", "props": {"projekt": [70], "tag": "xaby"}},
              {"id": 5, "title": "kaputt", "props": {"projekt": "7"}}]}
    """
    if let q = DBQuery.parse(Data(json.utf8)) {
        check(q.rows(for: q.view("v1")).map(\.id) == [1], "enthaelt: nur Zeile mit Verknuepfung, keine Teiltreffer (70), kein Absturz ohne Relation")
        check(q.rows(for: q.view("v2")).map(\.id) == [2, 3, 4, 5], "enthaelt nicht: fehlende/leere Relation = leere Liste")
        check(q.rows(for: q.view("v3")).map(\.id) == [4], "Text enthaelt weiter ohne Gross/klein")
    } else { check(false, "Relations-JSON parsen") }
    check(DBQuery.relID(7.0) == 7 && DBQuery.relID("7") == 7 && DBQuery.relID(7.5) == nil && DBQuery.relID("x") == nil, "Relations-ID")

    // MARK: #17 Datumsbereich auf Karten
    check(CardChipLogic.groups(types: ["date", "date", "select"], filled: [true, true, true]) == [[0, 1], [2]], "zwei Daten -> Bereich")
    check(CardChipLogic.groups(types: ["date", "date"], filled: [true, false]) == [[0], [1]], "leeres Datum -> einzeln")
    check(CardChipLogic.groups(types: ["date", "date", "date"], filled: [true, true, true]) == [[0, 1], [2]], "drei Daten: ein Paar")
    check(CardChipLogic.groups(types: ["select", "date", "number", "date"], filled: [true, true, true, true]) == [[0], [1], [2], [3]], "nicht benachbart")
    check(CardChipLogic.range("18. Juli", "25. Juli") == "18. Juli – 25. Juli" && CardChipLogic.range("1. Mai", "1. Mai") == "1. Mai", "Bereichstext")

    // MARK: #11 Schaltflaechen beim Lesen/Kommentieren
    check(!ButtonLogic.enabled(action: "db", readOnlyRole: true) && ButtonLogic.enabled(action: "db", readOnlyRole: false), "Eintrag anlegen nur mit Bearbeiten")
    check(ButtonLogic.enabled(action: "page", readOnlyRole: true) && ButtonLogic.enabled(action: "task", readOnlyRole: true), "Seite oeffnen/eigene Aufgabe auch beim Lesen")
    check(ButtonLogic.showsReadOnlyHint(actions: ["page", "db"], readOnlyRole: true) && !ButtonLogic.showsReadOnlyHint(actions: ["page"], readOnlyRole: true)
          && !ButtonLogic.showsReadOnlyHint(actions: ["db"], readOnlyRole: false), "Hinweis nur bei ausgegrauter Schaltflaeche")
}
