import SwiftUI
import WebKit
import WidgetKit
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// Steuert die Web-Ansicht: Navigation, externe Links, Nachrichten der Web-Oberflaeche (nur vom eigenen Server).
final class WebModel: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    @Published var loadError: String?
    @Published var showSettings = false
    @Published var currentURL: URL?
    let webView: WKWebView

    init(path: String = "/notes", padTop: Bool = true) {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()                         // Anmeldung (SSO-Cookies) bleibt erhalten
        cfg.applicationNameForUserAgent = "HlidskjalfApp/1.0"      // Web-Oberflaeche erkennt die App
        #if os(iOS)
        if padTop {
        // Seite unter die Statusleiste laufen lassen und oben passend einruecken. Per CSSOM (Stil-Eigenschaften),
        // weil die CSP der Seite eingeschleuste <style>-Elemente zu Recht blockiert.
        let js = """
        (function(){var m=document.querySelector('meta[name=viewport]');
          if(m&&m.content.indexOf('viewport-fit')<0)m.content+=',viewport-fit=cover';
          document.documentElement.style.setProperty('padding-top','env(safe-area-inset-top)');
          document.documentElement.style.setProperty('background-color',getComputedStyle(document.body).backgroundColor);})();
        """
        cfg.userContentController.addUserScript(WKUserScript(source: js, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        }
        #endif
        webView = WKWebView(frame: .zero, configuration: cfg)
        super.init()
        cfg.userContentController.add(WeakHandler(self), name: "hk")
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        #if os(iOS)
        webView.isOpaque = false; webView.backgroundColor = .clear
        #endif
        if #available(iOS 16.4, macOS 13.3, *) { webView.isInspectable = true }
        #if os(iOS)
        let rc = UIRefreshControl()
        rc.addTarget(self, action: #selector(pullRefresh(_:)), for: .valueChanged)
        webView.scrollView.refreshControl = rc
        #endif
        open(path: path)
    }

    func open(path: String) {
        var p = path.hasPrefix("/") ? path : "/" + path
        if !(p.hasPrefix("/notes") || p == "/") { p = "/notes" }        // nur eigene Bereiche oeffnen
        if let u = URL(string: AppConfig.server.absoluteString + p) { loadError = nil; webView.load(URLRequest(url: u)) }
    }
    func reload() { loadError = nil; if webView.url == nil { open(path: "/notes") } else { webView.reload() } }

    #if os(iOS)
    @objc private func pullRefresh(_ rc: UIRefreshControl) { webView.reload(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { rc.endRefreshing() } }
    #endif

    private func isOwn(_ url: URL?) -> Bool { url?.host == AppConfig.server.host }
    private func openExternal(_ url: URL) {
        #if os(iOS)
        UIApplication.shared.open(url)
        #else
        NSWorkspace.shared.open(url)
        #endif
    }

    // Nachrichten der Web-Oberflaeche: NUR Hauptframe des eigenen Servers
    func userContentController(_ uc: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame, message.frameInfo.securityOrigin.host == AppConfig.server.host,
              message.frameInfo.securityOrigin.protocol == "https",
              let body = message.body as? [String: Any], let action = body["action"] as? String else { return }
        switch action {
        case "token":
            if let t = body["token"] as? String, t.hasPrefix("hk_"), t.count < 120 {
                AppConfig.token = t
                WidgetCenter.shared.reloadAllTimelines()
                webView.evaluateJavaScript("window.HK && HK.toast('In dieser App gespeichert ✓')")
            }
        case "settings":
            showSettings = true
        default: break
        }
    }

    func webView(_ wv: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url, let scheme = url.scheme?.lowercased() else { return decisionHandler(.allow) }
        if ["mailto", "tel", "sms", "facetime"].contains(scheme) { openExternal(url); return decisionHandler(.cancel) }
        // Vom Benutzer angeklickte Links zu fremden Seiten -> Systembrowser (Anmelde-Umleitungen bleiben in der App)
        if action.navigationType == .linkActivated, ["http", "https"].contains(scheme), !isOwn(url) {
            openExternal(url); return decisionHandler(.cancel)
        }
        decisionHandler(.allow)
    }

    // target=_blank: eigene Seiten in derselben Ansicht, fremde im Systembrowser
    func webView(_ wv: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url { if isOwn(url) { wv.load(action.request) } else { openExternal(url) } }
        return nil
    }

    func webView(_ wv: WKWebView, didFinish nav: WKNavigation!) { currentURL = wv.url }

    func webView(_ wv: WKWebView, didFailProvisionalNavigation nav: WKNavigation!, withError error: Error) { fail(error) }
    func webView(_ wv: WKWebView, didFail nav: WKNavigation!, withError error: Error) { fail(error) }
    private func fail(_ error: Error) {
        let e = error as NSError
        if e.code == NSURLErrorCancelled { return }
        loadError = e.localizedDescription
    }
}

/// Verhindert einen Haltezyklus (WKUserContentController haelt seine Handler stark).
final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ t: WKScriptMessageHandler) { target = t }
    func userContentController(_ uc: WKUserContentController, didReceive m: WKScriptMessage) { target?.userContentController(uc, didReceive: m) }
}

#if os(iOS)
struct WebView: UIViewRepresentable {
    let model: WebModel
    func makeUIView(context: Context) -> WKWebView { model.webView }
    func updateUIView(_ v: WKWebView, context: Context) {}
}
#else
struct WebView: NSViewRepresentable {
    let model: WebModel
    func makeNSView(context: Context) -> WKWebView { model.webView }
    func updateNSView(_ v: WKWebView, context: Context) {}
}
#endif
