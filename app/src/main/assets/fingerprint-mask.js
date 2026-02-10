/**
 * Voice2SMS — WebView fingerprint masking.
 *
 * Injected early (onPageStarted) to override browser globals that reveal
 * this is an Android WebView rather than a real Chrome browser.
 *
 * Detection vectors masked:
 *   - navigator.webdriver (Automation flag)
 *   - navigator.plugins / mimeTypes (empty in WebView)
 *   - chrome.runtime (missing in WebView)
 *   - window.__gCrWeb (iOS WebView marker, sometimes checked generically)
 *   - navigator.connection.rtt (WebView may report 0)
 */
(function() {
    'use strict';

    // 1. navigator.webdriver — automation detection
    Object.defineProperty(navigator, 'webdriver', {
        get: function() { return false; },
        configurable: true
    });

    // 2. navigator.plugins — WebViews have an empty PluginArray
    //    Real Chrome has at least PDF Viewer, Chrome PDF Viewer, etc.
    var fakePlugins = {
        0: { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format', length: 1 },
        1: { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: '', length: 1 },
        2: { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: '', length: 1 },
        length: 3,
        item: function(i) { return this[i] || null; },
        namedItem: function(n) {
            for (var i = 0; i < this.length; i++) {
                if (this[i].name === n) return this[i];
            }
            return null;
        },
        refresh: function() {}
    };
    Object.defineProperty(navigator, 'plugins', {
        get: function() { return fakePlugins; },
        configurable: true
    });

    // 3. navigator.mimeTypes — match plugin count
    var fakeMimeTypes = {
        0: { type: 'application/pdf', suffixes: 'pdf', description: 'Portable Document Format', enabledPlugin: fakePlugins[0] },
        length: 1,
        item: function(i) { return this[i] || null; },
        namedItem: function(n) {
            for (var i = 0; i < this.length; i++) {
                if (this[i].type === n) return this[i];
            }
            return null;
        }
    };
    Object.defineProperty(navigator, 'mimeTypes', {
        get: function() { return fakeMimeTypes; },
        configurable: true
    });

    // 4. chrome.runtime — real Chrome exposes this, WebView does not
    if (typeof window.chrome === 'undefined') {
        window.chrome = {};
    }
    if (!window.chrome.runtime) {
        window.chrome.runtime = {
            connect: function() {},
            sendMessage: function() {}
        };
    }

    // 5. Remove iOS/WebView markers if present
    try { delete window.__gCrWeb; } catch(e) {}
    try { delete window.__crWeb; } catch(e) {}

    // 6. navigator.connection.rtt — WebView sometimes reports 0
    if (navigator.connection && navigator.connection.rtt === 0) {
        try {
            Object.defineProperty(navigator.connection, 'rtt', {
                get: function() { return 50; },
                configurable: true
            });
        } catch(e) {}
    }

    // 7. Permissions API — WebView may throw on query
    if (navigator.permissions) {
        var originalQuery = navigator.permissions.query;
        navigator.permissions.query = function(desc) {
            if (desc && desc.name === 'notifications') {
                return Promise.resolve({ state: 'prompt', onchange: null });
            }
            return originalQuery.call(navigator.permissions, desc);
        };
    }

    // 8. Dark mode — inject CSS to invert colors for a dark theme.
    //    Uses invert + hue-rotate on html, then re-inverts images/videos
    //    so they look normal. Applied via a <style> tag for reliability.
    var darkStyle = document.createElement('style');
    darkStyle.id = 'v2s-dark-mode';
    darkStyle.textContent =
        'html { filter: invert(0.9) hue-rotate(180deg) !important; background: #111 !important; }' +
        'img, video, svg image, [style*="background-image"] { filter: invert(1) hue-rotate(180deg) !important; }' +
        'img, video { opacity: 0.9; }';
    // Inject as early as possible, re-inject on DOMContentLoaded if needed
    if (document.head) {
        document.head.appendChild(darkStyle);
    } else {
        document.addEventListener('DOMContentLoaded', function() {
            document.head.appendChild(darkStyle);
        });
    }
})();
