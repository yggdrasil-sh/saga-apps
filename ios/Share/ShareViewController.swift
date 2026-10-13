import UIKit
import SwiftUI

final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        Task { @MainActor [weak self] in
            guard let self else { return }
            let c = await readShared(extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            let host = UIHostingController(rootView: ShareView(content: c) { [weak self] ok in self?.finish(ok) })
            addChild(host); host.view.frame = view.bounds; host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(host.view); host.didMove(toParent: self)
        }
    }
    private func finish(_ ok: Bool) {
        if ok { extensionContext?.completeRequest(returningItems: nil) }
        else { extensionContext?.cancelRequest(withError: NSError(domain: "Hlidskjalf", code: 0)) }
    }
}
