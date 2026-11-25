import AppIntents
import Foundation

// 2.6 (Bugs-DB 2029-6): Fokus-Filter "Saga-Status" (Einstellungen > Fokus > <Fokus> > Fokus-Filter > Saga). Solange der
// Fokus an ist, gilt der gewaehlte Status; endet er, ruft das System perform() mit den Standardwerten auf (status = nil)
// -> {state: "available", source: "focus"} (hebt einen selbst gesetzten Status nicht auf - der Server antwortet kept).
// Gesendet wird NUR {state, source: "focus"} (UserStatusLogic.focusBody) - nie der Name des Fokus oder ein Text (RT-29).
// Ohne laufende Sitzung (App im Hintergrund gestartet) wird der Zustand vorgemerkt und beim naechsten Laden gesendet.

enum FocusStatusChoice: String, AppEnum {
    case busy, dnd
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Saga-Status"
    static var caseDisplayRepresentations: [FocusStatusChoice: DisplayRepresentation] = [
        .busy: DisplayRepresentation(title: "Beschäftigt"),
        .dnd: DisplayRepresentation(title: "Nicht stören"),
    ]
}

struct StatusFocusFilter: SetFocusFilterIntent {
    static var title: LocalizedStringResource = "Saga-Status"
    static var description: IntentDescription? = IntentDescription("Setzt deinen Status in Saga, solange dieser Fokus an ist. Andere sehen nur den Status – nie den Namen des Fokus.")

    @Parameter(title: "Status") var status: FocusStatusChoice?

    var displayRepresentation: DisplayRepresentation {
        switch status {
        case .busy: return DisplayRepresentation(title: "Saga: Beschäftigt")
        case .dnd: return DisplayRepresentation(title: "Saga: Nicht stören")
        case nil: return DisplayRepresentation(title: "Saga-Status")
        }
    }

    func perform() async throws -> some IntentResult {
        await UserStatusModel.focusFromSystem(status?.rawValue ?? "available")
        return .result()
    }
}

extension UserStatusModel {
    /// Fokus-Filter: an die laufende Sitzung, sonst vormerken
    static func focusFromSystem(_ state: String) async {
        if let s = Store.current { _ = await s.ustatus.focus(state) }
        else { UserDefaults.standard.set(state, forKey: pendingKey) }
    }
}
