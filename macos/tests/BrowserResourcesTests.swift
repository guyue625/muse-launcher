import AppKit
import WebKit

private struct ResourceTestFailure: Error, CustomStringConvertible {
    let description: String
}

@main
@MainActor
enum BrowserResourcesTests {
    static func main() async throws {
        NSApplication.shared.setActivationPolicy(.prohibited)
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.userContentController.addUserScript(WKUserScript(
            source: "window.fetch = () => new Promise(resolve => { window.finishFetch = resolve; });",
            injectionTime: .atDocumentStart, forMainFrameOnly: true
        ))
        configuration.userContentController.addUserScript(WKUserScript(
            source: BrowserResources.script, injectionTime: .atDocumentStart, forMainFrameOnly: true
        ))
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.loadHTMLString("<!doctype html><html><body></body></html>", baseURL: URL(string: "https://muse.ai/"))
        for _ in 0..<200 {
            if (try? await view.evaluateJavaScript("typeof window.__museCanDiscardPage === 'function'") as? Bool) == true { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        try await expect(view, BrowserResources.canDiscardScript, "An idle page should be reclaimable")
        try await setBody(view, "<textarea>unsent message</textarea>")
        try await expect(view, "!window.__museCanDiscardPage()", "Unsent messages must survive")
        try await setBody(view, "<div contenteditable='true'>draft</div>")
        try await expect(view, "!window.__museCanDiscardPage()", "Rich text drafts must survive")
        try await setBody(view, "<input type='file'>")
        _ = try await view.evaluateJavaScript("const files = new DataTransfer(); files.items.add(new File(['video'], 'test.mp4')); document.querySelector('input').files = files.files; true")
        try await expect(view, "!window.__museCanDiscardPage()", "Selected attachments must survive")
        try await setBody(view, "<div aria-busy='true'></div>")
        try await expect(view, "!window.__museCanDiscardPage()", "Busy pages must survive")
        try await setBody(view, "<video preload='auto'></video><video preload='none'></video><video autoplay preload='auto'></video>")
        try await expect(view, "document.querySelectorAll('video')[0].preload === 'metadata'", "Unplayed videos should not preload the full file")
        try await expect(view, "document.querySelectorAll('video')[1].preload === 'none'", "Explicit lazy media must remain lazy")
        try await expect(view, "document.querySelectorAll('video')[2].preload === 'auto'", "Autoplay media must retain its playback behavior")
        _ = try await view.evaluateJavaScript("document.querySelector('video').preload = 'auto'; true")
        try await expect(view, "document.querySelector('video').preload === 'metadata'", "Later preload changes should also be limited")
        _ = try await view.evaluateJavaScript("Object.defineProperties(document.querySelector('video'), { paused: {value: false}, ended: {value: false} }); true")
        try await expect(view, "!window.__museCanDiscardPage()", "Playing videos must survive")
        try await setBody(view, "")
        _ = try await view.evaluateJavaScript("window.pendingFetch = fetch('/test'); true")
        try await expect(view, "!window.__museCanDiscardPage()", "In-flight requests must survive")
        _ = try await view.evaluateJavaScript("window.finishFetch(new Response('ok')); true")
        try await expect(view, BrowserResources.canDiscardScript, "Finished requests should no longer prevent reclamation")
        _ = try await view.evaluateJavaScript("window.pendingFetch = fetch('/stream'); true")
        _ = try await view.evaluateJavaScript("window.finishFetch(new Response(new ReadableStream({start(controller) { window.streamController = controller; }}), {headers: {'content-type': 'text/event-stream'}})); true")
        try await expect(view, "!window.__museCanDiscardPage()", "Streaming replies must survive after receiving headers")
        _ = try await view.evaluateJavaScript("window.pendingFetch.then(response => { window.streamReader = response.body.getReader(); }); true")
        _ = try await view.evaluateJavaScript("window.streamController.close(); true")
        try await expect(view, BrowserResources.canDiscardScript, "Finished streaming replies should allow reclamation again")
        _ = try await view.evaluateJavaScript("window.pendingFetch = fetch('/failed').catch(() => {}); window.finishFetch(new Response('error', {status: 500})); true")
        try await expect(view, BrowserResources.canDiscardScript, "HTTP errors must not permanently pin a page")
        view.stopLoading()
        print("PASS: WebKit video preload, drafts, attachments, playback, requests, and streaming reclamation guards")
    }

    private static func setBody(_ view: WKWebView, _ html: String) async throws {
        let encoded = String(data: try JSONEncoder().encode(html), encoding: .utf8)!
        _ = try await view.evaluateJavaScript("document.body.innerHTML = \(encoded); true")
    }

    private static func expect(_ view: WKWebView, _ script: String, _ message: String) async throws {
        guard try await view.evaluateJavaScript(script) as? Bool == true else {
            throw ResourceTestFailure(description: message)
        }
    }
}
