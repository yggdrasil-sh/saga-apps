#if os(iOS)
import Contacts
import ContactsUI
import UIKit

// 2.6 (Bugs-DB 2027b, Teil W2): "Zu Kontakten hinzufuegen" im Kontakt-Detail (ContactBook.swift). Zeigt die
// System-Karte fuer einen unbekannten Kontakt (CNContactViewController forUnknownContact) vorausgefuellt; dort waehlt
// die Person "Neuen Kontakt erstellen" oder "Zu vorhandenem Kontakt hinzufuegen". Die Karte und ihre Dialoge laufen
// im System - Saga liest das Adressbuch nicht und fragt keine Kontakte-Berechtigung an (kein NSContactsUsageDescription).
// Was uebertragen wird: ContactExportLogic.prefill.

@MainActor
enum ContactExport {
    typealias X = ContactExportLogic

    static func contact(_ p: X.Prefill) -> CNMutableContact {
        let c = CNMutableContact()
        if let n = p.name {
            let parts = X.parts(n)
            c.givenName = parts.given; c.middleName = parts.middle; c.familyName = parts.family
        }
        c.phoneNumbers = p.phones.map { CNLabeledValue(label: phoneLabel($0.label), value: CNPhoneNumber(stringValue: $0.value)) }
        c.emailAddresses = p.mails.map { CNLabeledValue(label: mailLabel($0.label), value: $0.value as NSString) }
        return c
    }
    static func phoneLabel(_ l: X.Label) -> String {
        switch l {
        case .mobile: return CNLabelPhoneNumberMobile
        case .home: return CNLabelHome
        case .work: return CNLabelWork
        case .other: return CNLabelOther
        }
    }
    static func mailLabel(_ l: X.Label) -> String {
        switch l {
        case .home: return CNLabelHome
        case .work: return CNLabelWork
        case .mobile, .other: return CNLabelOther
        }
    }

    /// System-Karte zeigen (iPhone: Blatt, iPad: Formular-Blatt in der Mitte)
    static func present(_ p: X.Prefill) {
        let vc = CNContactViewController(forUnknownContact: contact(p))
        vc.contactStore = CNContactStore()                                   // "Neuen Kontakt …"/"Zu vorhandenem …" anbieten
        vc.allowsEditing = true
        vc.allowsActions = false                                             // keine Anruf-/Nachricht-Knoepfe - es geht nur ums Sichern
        let nav = UINavigationController(rootViewController: vc)
        let box = Closer(nav)
        vc.delegate = box
        vc.navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .close, primaryAction: UIAction { _ in box.close() })
        nav.modalPresentationStyle = UIDevice.current.userInterfaceIdiom == .pad ? .formSheet : .pageSheet
        objc_setAssociatedObject(nav, &Closer.key, box, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        top()?.present(nav, animated: true)
    }

    private static func top() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let win = scenes.first { $0.activationState == .foregroundActive }?.keyWindow ?? scenes.compactMap(\.keyWindow).first
        var t = win?.rootViewController
        while let p = t?.presentedViewController, !p.isBeingDismissed { t = p }
        return t
    }

    /// Schliesst die Karte, sobald ein neuer bzw. ergaenzter Kontakt gesichert ist
    private final class Closer: NSObject, CNContactViewControllerDelegate {
        static var key = 0
        weak var nav: UINavigationController?
        init(_ nav: UINavigationController) { self.nav = nav }
        func close() { nav?.dismiss(animated: true) }
        func contactViewController(_ viewController: CNContactViewController, didCompleteWith contact: CNContact?) {
            if contact != nil { close() }
        }
        func contactViewController(_ viewController: CNContactViewController, shouldPerformDefaultActionFor property: CNContactProperty) -> Bool { false }
    }
}
#endif
