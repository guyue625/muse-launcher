import Foundation

enum BrowserResources {
    static let hiddenPageDelay: TimeInterval = 180

    static let script = #"""
    (() => {
        if (location.hostname !== 'muse.ai' && !location.hostname.endsWith('.muse.ai')) return;
        let pendingRequests = 0;
        let streamingResponses = 0;
        const watchStream = response => {
            if (!response.body) return;
            streamingResponses++;
            let finished = false;
            const finish = () => {
                if (finished) return;
                finished = true;
                streamingResponses--;
            };
            const originalGetReader = response.body.getReader;
            response.body.getReader = function(...args) {
                const reader = originalGetReader.apply(this, args);
                reader.closed.then(finish, () => {});
                return reader;
            };
            for (const method of ['text', 'json', 'blob', 'arrayBuffer', 'formData', 'bytes']) {
                const original = response[method];
                if (!original) continue;
                response[method] = function(...args) { return original.apply(this, args).finally(finish); };
            }
            for (const method of ['pipeTo', 'cancel']) {
                const original = response.body[method];
                response.body[method] = function(...args) { return original.apply(this, args).finally(finish); };
            }
        };
        const originalFetch = window.fetch;
        window.fetch = function(...args) {
            pendingRequests++;
            let request;
            try { request = originalFetch.apply(this, args); }
            catch (error) { pendingRequests--; throw error; }
            return request.then(response => {
                const type = response.headers.get('content-type') || '';
                if (/event-stream|ndjson/.test(type)) watchStream(response);
                return response;
            }).finally(() => { pendingRequests--; });
        };
        const originalSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.send = function(...args) {
            pendingRequests++;
            let finished = false;
            const finish = () => {
                if (finished) return;
                finished = true;
                pendingRequests--;
            };
            this.addEventListener('loadend', finish, { once: true });
            try { return originalSend.apply(this, args); }
            catch (error) { finish(); throw error; }
        };
        const prepareVideo = video => {
            if (video.srcObject || !video.paused || video.autoplay) return;
            if (!video.getAttribute('preload') || video.preload === 'auto') video.preload = 'metadata';
        };
        const observer = new MutationObserver(records => {
            for (const record of records) {
                if (record.type === 'attributes') prepareVideo(record.target);
                for (const node of record.addedNodes) {
                    if (node.nodeType !== Node.ELEMENT_NODE) continue;
                    if (node.matches('video')) prepareVideo(node);
                    node.querySelectorAll('video').forEach(prepareVideo);
                }
            }
        });
        observer.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['preload'] });
        document.querySelectorAll('video').forEach(prepareVideo);
        window.__museCanDiscardPage = () => {
            if (pendingRequests || streamingResponses || document.querySelector('iframe, [aria-busy="true"], [role="progressbar"]')) return false;
            for (const field of document.querySelectorAll('input, textarea, [contenteditable="true"], [role="textbox"]')) {
                if (field.type === 'file' && field.files.length) return false;
                if (['checkbox', 'radio', 'button', 'submit', 'reset', 'hidden', 'range', 'color'].includes(field.type)) continue;
                if ((field.value || field.textContent || '').trim()) return false;
            }
            for (const media of document.querySelectorAll('video, audio')) {
                if (media.srcObject && media.srcObject.active) return false;
                if (!media.paused && !media.ended && (!media.muted || !media.loop)) return false;
            }
            return true;
        };
    })();
    """#

    static let canDiscardScript = "window.__museCanDiscardPage ? window.__museCanDiscardPage() : false"
}
