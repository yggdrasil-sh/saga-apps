import AppKit
import SwiftUI
import UniformTypeIdentifiers
import WebKit

// Web-Ansicht der Mac-Huelle: Navigation (fremde Links -> Systembrowser, /login -> Sitzung erneuern), Fenster aus
// window.open, Dialoge (alert/confirm/prompt), Oeffnen-Dialog, Downloads, Kamera/Mikrofon nur fuer den eigenen Server.

extension ShellModel: WKNavigationDelegate {
    func webView(_ wv: WKWebView, decidePolicyFor action: WKNavigationAction, preferences: WKWebpagePreferences,
                 decisionHandler: @escaping @MainActor (WKNavigationActionPolicy, WKWebpagePreferences) -> Void) {
        guard let url = action.request.url, let scheme = url.scheme?.lowercased() else { return decisionHandler(.allow, preferences) }
        let main = action.targetFrame?.isMainFrame ?? true
        if ["mailto", "tel", "sms", "facetime"].contains(scheme) { NSWorkspace.shared.open(url); return decisionHandler(.cancel, preferences) }
        if ShellLogic.linkSchemes.contains(scheme) { handle(link: url); return decisionHandler(.cancel, preferences) }
        if action.shouldPerformDownload { return decisionHandler(.download, preferences) }
        if main, ["http", "https"].contains(scheme) {
            let nav = ShellLogic.loginNav(url, server: server)
            if nav != .none { loginNavigation(nav); return decisionHandler(.cancel, preferences) }
            // angeklickte Links zu fremden Seiten -> Systembrowser (Umleitungen, z. B. Abmelden beim SSO, bleiben hier)
            if action.navigationType == .linkActivated, !ShellLogic.isOwn(url, server: server) {
                NSWorkspace.shared.open(url); return decisionHandler(.cancel, preferences)
            }
        }
        decisionHandler(.allow, preferences)
    }

    func webView(_ wv: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping @MainActor (WKNavigationResponsePolicy) -> Void) {
        let disp = (response.response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Disposition")?.lowercased() ?? ""
        if !response.canShowMIMEType || disp.hasPrefix("attachment") { return decisionHandler(.download) }
        decisionHandler(.allow)
    }

    func webView(_ wv: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) { download.delegate = self }
    func webView(_ wv: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) { download.delegate = self }

    func webView(_ wv: WKWebView, didFinish nav: WKNavigation!) {
        // `ready` kommt aus host.js; Seiten ohne host.js (Fehlerseiten, Anmelde-Rueckfragen) zeigen sich trotzdem
        if !offline { loading = false }
    }

    func webView(_ wv: WKWebView, didFailProvisionalNavigation nav: WKNavigation!, withError error: Error) { failed(error, wv) }
    func webView(_ wv: WKWebView, didFail nav: WKNavigation!, withError error: Error) { failed(error, wv) }

    private func failed(_ error: Error, _ wv: WKWebView) {
        let e = error as NSError
        loading = false
        guard e.domain == NSURLErrorDomain, !offline else { return }
        let net: Set<Int> = [NSURLErrorNotConnectedToInternet, NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost,
                             NSURLErrorTimedOut, NSURLErrorNetworkConnectionLost, NSURLErrorDNSLookupFailed,
                             NSURLErrorInternationalRoamingOff, NSURLErrorDataNotAllowed]
        if net.contains(e.code) {
            let failing = (e.userInfo[NSURLErrorFailingURLErrorKey] as? URL) ?? wv.url
            showOffline(for: failing)
        }
    }

    func webViewWebContentProcessDidTerminate(_ wv: WKWebView) { wv.reload() }   // abgestuerzter Inhaltsprozess
}

extension ShellModel: WKUIDelegate {
    // target=_blank / window.open: eigene Adressen im selben Fenster, fremde im Systembrowser; leeres Fenster
    // (window.open('', '_blank'), z. B. Nextcloud-Verbindung) -> unsichtbarer Faenger, der die spaetere Adresse weitergibt
    func webView(_ wv: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url, url.scheme != "about", !url.absoluteString.isEmpty {
            if ShellLogic.isOwn(url, server: server) { wv.load(action.request) } else if ["http", "https"].contains(url.scheme?.lowercased() ?? "") { NSWorkspace.shared.open(url) }
            return nil
        }
        let p = PopupCatcher(configuration: configuration, model: self)
        popups.append(p)
        return p.webView
    }

    func webView(_ wv: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping @MainActor () -> Void) {
        let a = NSAlert(); a.messageText = message; a.addButton(withTitle: String(localized: "OK"))
        present(a) { _ in completionHandler() }
    }

    func webView(_ wv: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping @MainActor (Bool) -> Void) {
        let a = NSAlert(); a.messageText = message
        a.addButton(withTitle: String(localized: "OK")); a.addButton(withTitle: String(localized: "Abbrechen"))
        present(a) { completionHandler($0 == .alertFirstButtonReturn) }
    }

    func webView(_ wv: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor (String?) -> Void) {
        let a = NSAlert(); a.messageText = prompt
        let f = NSTextField(frame: NSRect(x: 0, y: 0, width: 300, height: 24)); f.stringValue = defaultText ?? ""
        a.accessoryView = f
        a.addButton(withTitle: String(localized: "OK")); a.addButton(withTitle: String(localized: "Abbrechen"))
        a.window.initialFirstResponder = f
        present(a) { completionHandler($0 == .alertFirstButtonReturn ? f.stringValue : nil) }
    }

    private func present(_ a: NSAlert, _ done: @escaping (NSApplication.ModalResponse) -> Void) {
        if let w = webView.window { a.beginSheetModal(for: w, completionHandler: done) } else { done(a.runModal()) }
    }

    // <input type=file>
    func webView(_ wv: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping @MainActor ([URL]?) -> Void) {
        let p = NSOpenPanel()
        p.allowsMultipleSelection = parameters.allowsMultipleSelection
        p.canChooseDirectories = parameters.allowsDirectories
        p.canChooseFiles = true
        if let w = wv.window { p.beginSheetModal(for: w) { completionHandler($0 == .OK ? p.urls : nil) } }
        else { completionHandler(p.runModal() == .OK ? p.urls : nil) }
    }

    // Kamera/Mikrofon (Anrufe, Sprachnachrichten): nur fuer den eigenen Server; die Systemfreigabe fragt macOS selbst
    func webView(_ wv: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo,
                 type: WKMediaCaptureType, decisionHandler: @escaping @MainActor (WKPermissionDecision) -> Void) {
        let o = ShellLogic.origin(scheme: origin.protocol, host: origin.host, port: origin.port == 0 ? nil : origin.port)
        decisionHandler(o != nil && o == ShellLogic.origin(server) ? .grant : .deny)
    }

    func webViewDidClose(_ wv: WKWebView) { popups.removeAll { $0.webView === wv } }
}

// MARK: Downloads -> Ordner "Downloads" (eindeutiger Name), Dock-Stapel springt an

extension ShellModel: WKDownloadDelegate {
    func download(_ d: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String,
                  completionHandler: @escaping @MainActor (URL?) -> Void) {
        guard let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first else { return completionHandler(nil) }
        let clean = suggestedFilename.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        let name = clean.isEmpty || clean.hasPrefix(".") ? "download" + clean : clean
        var target = dir.appendingPathComponent(name)
        let base = target.deletingPathExtension().lastPathComponent, ext = target.pathExtension
        var n = 2
        while FileManager.default.fileExists(atPath: target.path), n < 1000 {
            target = dir.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)"); n += 1
        }
        completionHandler(target)
    }

    func downloadDidFinish(_ d: WKDownload) {
        guard let p = d.progress.fileURL else { show(toast: String(localized: "Heruntergeladen")); return }
        DistributedNotificationCenter.default().post(name: .init("com.apple.DownloadFileFinished"), object: p.path)
        show(toast: String(localized: "Gespeichert in „Downloads“: \(p.lastPathComponent)"))
    }

    func download(_ d: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        show(toast: String(localized: "Download fehlgeschlagen"))
    }
}

/// Unsichtbares Fenster fuer window.open('', '_blank'): wartet auf die erste echte Adresse und gibt sie weiter
/// (fremd -> Systembrowser, eigen -> Hauptfenster), danach weg. Hoechstens 2 Minuten.
@MainActor
final class PopupCatcher: NSObject, WKNavigationDelegate {
    let webView: WKWebView
    private weak var model: ShellModel?

    init(configuration: WKWebViewConfiguration, model: ShellModel) {
        webView = WKWebView(frame: .zero, configuration: configuration)
        self.model = model
        super.init()
        webView.navigationDelegate = self
        Task { [weak self] in try? await Task.sleep(for: .seconds(120)); self?.done() }
    }

    func webView(_ wv: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url, let s = url.scheme?.lowercased(), s == "http" || s == "https", let m = model else {
            return decisionHandler(action.request.url?.scheme == "about" ? .allow : .cancel)
        }
        if ShellLogic.isOwn(url, server: m.server) { m.webView.load(URLRequest(url: url)) } else { NSWorkspace.shared.open(url) }
        decisionHandler(.cancel)
        done()
    }

    private func done() {
        webView.stopLoading()
        model?.popups.removeAll { $0 === self }
    }
}

struct ShellWebView: NSViewRepresentable {
    let webView: WKWebView
    func makeNSView(context: Context) -> WKWebView { webView }
    func updateNSView(_ v: WKWebView, context: Context) {}
}
