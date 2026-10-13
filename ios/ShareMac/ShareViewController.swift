import AppKit
import SwiftUI

final class ShareViewController: NSViewController {
    override func loadView() {
        view = NSView(frame: NSRect(x: 0, y: 0, width: 420, height: 280))
        preferredContentSize = NSSize(width: 420, height: 280)
        Task { @MainActor [weak self] in
            guard let self else { return }
            let c = await readShared(extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            let host = NSHostingView(rootView: ShareView(content: c) { [weak self] ok in self?.finish(ok) })
            host.frame = view.bounds; host.autoresizingMask = [.width, .height]
            view.addSubview(host)
        }
    }
    private func finish(_ ok: Bool) {
        if ok { extensionContext?.completeRequest(returningItems: nil) }
        else { extensionContext?.cancelRequest(withError: NSError(domain: "Hlidskjalf", code: 0)) }
    }
}
