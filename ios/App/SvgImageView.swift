import SwiftUI
import WebKit

/// SVG-Bild anzeigen (SwiftUI/UIImage koennen kein SVG): nicht-interaktive WKWebView ohne JavaScript, ohne Speicher,
/// nur mit dem SVG als data:-<img> (Regeln in SvgLogic). SVG im <img> fuehrt kein Skript aus und laedt nichts nach;
/// zusaetzlich CSP default-src 'none' und jede Navigation ausser der eigenen leeren Seite wird abgelehnt.
struct SvgImageView {
    let data: Data
    var cover = false

    final class Web: WKWebView {
        // nicht-interaktiv: Antippen/Ziehen gehen an die Seite darunter (Auswahl des Blocks, Blaettern)
        #if os(iOS)
        override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }
        #else
        override func hitTest(_ point: NSPoint) -> NSView? { nil }
        #endif
    }
    @MainActor final class Guard: NSObject, WKNavigationDelegate {
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
            SvgLogic.allowNavigation(action.request.url) ? .allow : .cancel
        }
    }
    @MainActor func makeCoordinator() -> Guard { Guard() }

    @MainActor func make(_ g: Guard) -> Web {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .nonPersistent()
        cfg.defaultWebpagePreferences.allowsContentJavaScript = false
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        #if os(iOS)
        cfg.dataDetectorTypes = []
        cfg.allowsInlineMediaPlayback = false
        #endif
        let w = Web(frame: .zero, configuration: cfg)
        w.navigationDelegate = g
        w.allowsBackForwardNavigationGestures = false
        w.allowsLinkPreview = false
        #if os(iOS)
        w.isOpaque = false; w.backgroundColor = .clear; w.scrollView.backgroundColor = .clear
        w.scrollView.isScrollEnabled = false; w.scrollView.bounces = false
        w.isUserInteractionEnabled = false
        #else
        w.setValue(false, forKey: "drawsBackground")
        #endif
        w.loadHTMLString(SvgLogic.html(data, cover: cover), baseURL: nil)
        return w
    }
}

#if os(iOS)
extension SvgImageView: UIViewRepresentable {
    func makeUIView(context: Context) -> Web { make(context.coordinator) }
    func updateUIView(_ v: Web, context: Context) {}
    static func dismantleUIView(_ v: Web, coordinator: Guard) { v.stopLoading(); v.navigationDelegate = nil }
}
#else
extension SvgImageView: NSViewRepresentable {
    func makeNSView(context: Context) -> Web { make(context.coordinator) }
    func updateNSView(_ v: Web, context: Context) {}
    static func dismantleNSView(_ v: Web, coordinator: Guard) { v.stopLoading(); v.navigationDelegate = nil }
}
#endif
