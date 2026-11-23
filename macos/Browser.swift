import AppKit
import WebKit

@MainActor
final class Browser: NSObject, WKNavigationDelegate, WKUIDelegate {
    static let home = URL(string: "https://muse.ai/")!

    let view: WKWebView
    var didChange: (() -> Void)?
    private(set) var failure: String?
    private(set) var isLoading = false
    private var lastRequest = URLRequest(url: home)
    private var timeoutWork: DispatchWorkItem?

    init(account: Account) throws {
        let configuration = WKWebViewConfiguration()
        let languageScript = WKUserScript(
            source: "Object.defineProperty(Navigator.prototype, 'language', { get: () => 'en-US' }); Object.defineProperty(Navigator.prototype, 'languages', { get: () => ['en-US', 'en'] });",
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false
        )
        configuration.userContentController.addUserScript(languageScript)
        let dataStore = WKWebsiteDataStore(forIdentifier: account.id)
        if let proxy = try AccountProxy(account.proxy) {
            dataStore.proxyConfigurations = [proxy.configuration]
        }
        configuration.websiteDataStore = dataStore
        configuration.preferences.isElementFullscreenEnabled = true
        view = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        view.allowsBackForwardNavigationGestures = true
        view.navigationDelegate = self
        view.uiDelegate = self
        goHome()
    }

    func goHome() {
        var request = URLRequest(url: URL(string: "https://muse.ai/?locale=en_US")!)
        request.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        lastRequest = request
        view.load(request)
    }

    func reload() {
        if failure != nil || view.url == nil { view.load(lastRequest) }
        else { view.reload() }
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let scheme = action.request.url?.scheme?.lowercased()
        if action.shouldPerformDownload, ["https", "http", "blob", "data"].contains(scheme) {
            decisionHandler(.download)
        } else if action.targetFrame?.isMainFrame == false {
            decisionHandler(scheme == "file" ? .cancel : .allow)
        } else if scheme == "https" || scheme == "http" {
            if action.targetFrame?.isMainFrame == true { lastRequest = action.request }
            decisionHandler(.allow)
        } else {
            decisionHandler(.cancel)
        }
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if action.targetFrame == nil, let scheme = action.request.url?.scheme, ["https", "http"].contains(scheme) {
            webView.load(action.request)
        }
        return nil
    }

    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        let attachment = (response.response as? HTTPURLResponse)?
            .value(forHTTPHeaderField: "Content-Disposition")?
            .split(separator: ";").first?.trimmingCharacters(in: .whitespaces).lowercased() == "attachment"
        decisionHandler(attachment || !response.canShowMIMEType ? .download : .allow)
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        BrowserDownloads.shared.start(download)
    }

    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        BrowserDownloads.shared.start(download)
    }

    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping ([URL]?) -> Void) {
        let panel = NSOpenPanel()
        panel.canChooseFiles = !parameters.allowsDirectories
        panel.canChooseDirectories = parameters.allowsDirectories
        panel.allowsMultipleSelection = parameters.allowsMultipleSelection
        let finish: (NSApplication.ModalResponse) -> Void = { result in
            completionHandler(result == .OK ? panel.urls : nil)
        }
        if let window = webView.window { panel.beginSheetModal(for: window, completionHandler: finish) }
        else { completionHandler(nil) }
    }

    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                 initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType,
                 decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        let host = origin.host.lowercased()
        let isMuse = host == "muse.ai" || host.hasSuffix(".muse.ai")
        decisionHandler(isMuse && frame.isMainFrame && origin.protocol == "https" ? .prompt : .deny)
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        timeoutWork?.cancel()
        isLoading = true
        failure = nil
        didChange?()
        let timeout = DispatchWorkItem { [weak self] in
            guard let self, self.isLoading else { return }
            self.isLoading = false
            self.failure = "连接超时。检查网络，或在账号设置中配置代理后重试。"
            self.didChange?()
        }
        timeoutWork = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: timeout)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { finishLoading() }
    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) { didChange?() }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        navigationFailed(error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        navigationFailed(error)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        failure = "网页进程已退出，请重新加载。"
        didChange?()
    }

    private func navigationFailed(_ error: Error) {
        let reported = error as NSError
        guard !(reported.domain == NSURLErrorDomain && reported.code == NSURLErrorCancelled) else { return }
        finishLoading()
        guard !(reported.domain == "WebKitErrorDomain" && [101, 102].contains(reported.code)) else { return }
        failure = "\(error.localizedDescription)\n\n检查网络，或在账号设置中配置代理后点重新加载。"
        didChange?()
    }

    private func finishLoading() {
        timeoutWork?.cancel()
        timeoutWork = nil
        isLoading = false
        didChange?()
    }
}

@MainActor
final class BrowserDownloads: NSObject, WKDownloadDelegate {
    static let shared = BrowserDownloads()

    private struct Destination {
        let temporary: URL
        let final: URL
    }

    private var downloads: [ObjectIdentifier: WKDownload] = [:]
    private var destinations: [ObjectIdentifier: Destination] = [:]

    func start(_ download: WKDownload) {
        downloads[ObjectIdentifier(download)] = download
        download.delegate = self
    }

    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse,
                  suggestedFilename: String, completionHandler: @escaping (URL?) -> Void) {
        let id = ObjectIdentifier(download)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = (suggestedFilename as NSString).lastPathComponent
        panel.begin { result in
            guard result == .OK, let final = panel.url else {
                self.downloads.removeValue(forKey: id)
                completionHandler(nil)
                return
            }
            let temporary = final.deletingLastPathComponent().appendingPathComponent(".muse-\(UUID().uuidString)")
            self.destinations[id] = Destination(temporary: temporary, final: final)
            completionHandler(temporary)
        }
    }

    func downloadDidFinish(_ download: WKDownload) {
        let id = ObjectIdentifier(download)
        downloads.removeValue(forKey: id)
        guard let destination = destinations.removeValue(forKey: id) else { return }
        let manager = FileManager.default
        do {
            if manager.fileExists(atPath: destination.final.path) {
                _ = try manager.replaceItemAt(destination.final, withItemAt: destination.temporary)
            } else {
                try manager.moveItem(at: destination.temporary, to: destination.final)
            }
        } catch {
            showFailure(error, detail: "临时文件：\(destination.temporary.path)")
        }
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        let id = ObjectIdentifier(download)
        downloads.removeValue(forKey: id)
        if let destination = destinations.removeValue(forKey: id) {
            try? FileManager.default.removeItem(at: destination.temporary)
        }
        let reported = error as NSError
        if reported.domain == NSURLErrorDomain && reported.code == NSURLErrorCancelled { return }
        showFailure(error)
    }

    private func showFailure(_ error: Error, detail: String? = nil) {
        let alert = NSAlert(error: error)
        alert.messageText = "下载失败"
        if let detail { alert.informativeText += "\n" + detail }
        if let window = NSApp.mainWindow { alert.beginSheetModal(for: window) }
        else { alert.runModal() }
    }
}
