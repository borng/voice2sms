/**
 * Voice2SMS — Google Voice WebView JavaScript injection.
 *
 * Composes a message in the Google Voice web UI by:
 * 1. Finding an existing conversation by phone number, OR
 * 2. Starting a new conversation via the compose button.
 * 3. Populating the message body.
 * 4. Optionally clicking send after a delay.
 *
 * Usage: voice2sms('+15551234567', 'Hello world', false, 2000)
 */
function voice2sms(phone, body, autoSend, autoSendDelay) {
    'use strict';

    var MAX_RETRIES = 30;
    var POLL_INTERVAL = 500;

    /** Return a random integer between min and max (inclusive). */
    function randDelay(min, max) {
        return Math.floor(Math.random() * (max - min + 1)) + min;
    }

    // Normalize phone: strip everything except digits and leading +
    var normalizedPhone = phone.replace(/[^\d+]/g, '');
    // Also create a digits-only version for matching
    var digitsOnly = phone.replace(/\D/g, '');

    console.log('[Voice2SMS] Starting compose for: ' + normalizedPhone);

    /**
     * Get Angular's zone reference from an element's Zone.js symbol properties.
     * Angular registers event listeners inside its own zone fork (named 'angular'),
     * NOT the root zone. Zone.current in console/setTimeout is always <root>.
     * We must extract Angular's zone from __zone_symbol__ to dispatch events
     * that Angular's change detection actually picks up.
     */
    function getAngularZone(el) {
        // Try common zone symbol properties — format: __zone_symbol__{event}{capture}
        var symbolKeys = [
            '__zone_symbol__inputfalse',
            '__zone_symbol__focusfalse',
            '__zone_symbol__blurfalse',
            '__zone_symbol__keydownfalse'
        ];
        for (var i = 0; i < symbolKeys.length; i++) {
            var listeners = el[symbolKeys[i]];
            if (listeners && listeners.length > 0 && listeners[0].zone) {
                return listeners[0].zone;
            }
        }
        return null;
    }

    /**
     * Set value on an input/textarea and trigger Angular change detection.
     * Uses native setter + InputEvent dispatched inside Angular's zone so that
     * NgZone picks up the change and updates form controls / autocomplete.
     */
    function setInputValue(el, value) {
        el.focus();
        el.click();

        // Use native setter to set the value directly
        var proto = (el.tagName === 'TEXTAREA')
            ? window.HTMLTextAreaElement.prototype
            : window.HTMLInputElement.prototype;
        var nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value');
        if (nativeSetter && nativeSetter.set) {
            nativeSetter.set.call(el, value);
        } else {
            el.value = value;
        }

        // Dispatch InputEvent inside Angular's zone (NOT Zone.current which is <root>)
        var angularZone = getAngularZone(el);
        var inputEvent = new InputEvent('input', {
            bubbles: true, inputType: 'insertText', data: value
        });

        if (angularZone) {
            console.log('[Voice2SMS] Dispatching in Angular zone: ' + angularZone.name);
            angularZone.run(function() {
                el.dispatchEvent(inputEvent);
            });
        } else if (typeof Zone !== 'undefined') {
            console.log('[Voice2SMS] Angular zone not found, falling back to Zone.current');
            Zone.current.run(function() {
                el.dispatchEvent(inputEvent);
            });
        } else {
            el.dispatchEvent(inputEvent);
        }
    }

    /**
     * Try to find an existing conversation with this phone number.
     * GV lists conversations as <li> items inside <ol gv-test-id="list">,
     * each containing <gv-message-thread-list-item> / <gv-thread-list-item>.
     * We search by text content since itemId attributes are not present.
     */
    function findExistingConversation() {
        // Search conversation list items by text content
        var items = document.querySelectorAll(
            'ol[gv-test-id="list"] li.list-item, ' +
            'gv-message-thread-list-item, ' +
            'gv-thread-list-item'
        );

        for (var i = 0; i < items.length; i++) {
            var text = items[i].textContent || '';
            if (text.indexOf(digitsOnly) !== -1 ||
                text.indexOf(normalizedPhone) !== -1) {
                console.log('[Voice2SMS] Found conversation by text content match');
                // Find the clickable div[role="button"] inside
                var clickable = items[i].querySelector('div[role="button"]') || items[i];
                return clickable;
            }
        }

        return null;
    }

    /**
     * Click the "new conversation" / compose button.
     */
    function clickNewConversation() {
        var selectors = [
            'button[aria-label*="new"]',
            'button[aria-label*="compose"]'
        ];

        for (var i = 0; i < selectors.length; i++) {
            var btn = document.querySelector(selectors[i]);
            if (btn) {
                console.log('[Voice2SMS] Clicking new conversation: ' + selectors[i]);
                btn.click();
                return true;
            }
        }

        console.log('[Voice2SMS] Could not find new conversation button');
        return false;
    }

    /**
     * Fill in the recipient field for a new conversation.
     */
    function fillRecipient(retries) {
        if (retries <= 0) {
            console.log('[Voice2SMS] Gave up waiting for recipient field');
            return;
        }

        var recipientInput = document.querySelector(
            'input[placeholder="Type a name or phone number"], ' +
            'input[placeholder*="name or phone"], ' +
            'input[placeholder*="number"], ' +
            'input[aria-label*="To"], ' +
            'input[aria-label*="recipient"]'
        );

        if (!recipientInput) {
            setTimeout(function() { fillRecipient(retries - 1); }, POLL_INTERVAL);
            return;
        }

        console.log('[Voice2SMS] Found recipient input, filling: ' + normalizedPhone);
        setInputValue(recipientInput, normalizedPhone);

        // Wait for dropdown to appear, then click suggestion
        setTimeout(function() { selectRecipientFromDropdown(recipientInput, MAX_RETRIES); }, randDelay(400, 800));
    }

    /**
     * Select the first suggestion from the recipient dropdown.
     * Uses direct click on the CDK overlay suggestion elements.
     * (Dispatched KeyboardEvents are isTrusted:false and ignored by CDK.)
     */
    function selectRecipientFromDropdown(inp, retries) {
        if (retries <= 0) {
            console.log('[Voice2SMS] Gave up waiting for recipient dropdown');
            setTimeout(function() { fillBody(MAX_RETRIES); }, 500);
            return;
        }

        // Check if dropdown is visible in the CDK overlay
        var overlay = document.querySelector('.cdk-overlay-container');
        var hasDropdown = overlay && overlay.innerHTML.trim().length > 50;

        if (!hasDropdown) {
            setTimeout(function() { selectRecipientFromDropdown(inp, retries - 1); }, POLL_INTERVAL);
            return;
        }

        console.log('[Voice2SMS] Dropdown detected, clicking suggestion');

        // Primary: click "Send to <number>" button
        var sendToBtn = overlay.querySelector('.send-to-button');
        // Fallback: click first contact row
        if (!sendToBtn) {
            var contactBtns = overlay.querySelectorAll('button.container.row');
            if (contactBtns.length > 0) sendToBtn = contactBtns[0];
        }

        if (sendToBtn) {
            var label = sendToBtn.textContent.replace(/\s+/g, ' ').trim().substring(0, 60);
            console.log('[Voice2SMS] Clicking: ' + label);
            sendToBtn.click();
        } else {
            console.log('[Voice2SMS] No clickable suggestion found in overlay');
        }

        // Verify chip and proceed to body
        setTimeout(function() {
            var chips = document.querySelectorAll('mat-chip-row, .mdc-evolution-chip');
            console.log('[Voice2SMS] Recipient chips: ' + chips.length);
            setTimeout(function() { fillBody(MAX_RETRIES); }, randDelay(300, 700));
        }, randDelay(400, 800));
    }

    /**
     * Fill in the message body textarea.
     */
    function fillBody(retries) {
        if (retries <= 0) {
            console.log('[Voice2SMS] Gave up waiting for message textarea');
            return;
        }

        if (!body || body.length === 0) {
            console.log('[Voice2SMS] No body to fill');
            return;
        }

        var textarea = document.querySelector(
            'textarea[placeholder="Type a message"], ' +
            'textarea[placeholder*="message"], ' +
            'textarea[placeholder*="text"], ' +
            'textarea[aria-label*="message"], ' +
            '[contenteditable="true"][aria-label*="message"]'
        );

        if (!textarea) {
            setTimeout(function() { fillBody(retries - 1); }, POLL_INTERVAL);
            return;
        }

        console.log('[Voice2SMS] Found message textarea, filling body');

        if (textarea.contentEditable === 'true') {
            textarea.focus();
            textarea.textContent = body;
            textarea.dispatchEvent(new Event('input', { bubbles: true }));
        } else {
            setInputValue(textarea, body);
        }

        if (autoSend) {
            var jitteredDelay = autoSendDelay + randDelay(-300, 500);
            console.log('[Voice2SMS] Auto-send enabled, will send in ' + jitteredDelay + 'ms');
            setTimeout(function() { clickSend(5); }, jitteredDelay);
        }
    }

    /**
     * Click the send button.
     */
    function clickSend(retries) {
        if (retries <= 0) {
            console.log('[Voice2SMS] Could not find send button');
            return;
        }

        var sendBtn = document.querySelector(
            'button[aria-label*="Send"]'
        );

        if (!sendBtn) {
            setTimeout(function() { clickSend(retries - 1); }, POLL_INTERVAL);
            return;
        }

        if (sendBtn.disabled || sendBtn.getAttribute('aria-disabled') === 'true') {
            console.log('[Voice2SMS] Send button disabled, retrying...');
            setTimeout(function() { clickSend(retries - 1); }, POLL_INTERVAL);
            return;
        }

        console.log('[Voice2SMS] Clicking send');
        sendBtn.click();
    }

    /**
     * Main entry: poll until the page is ready, then compose.
     */
    function start(retries) {
        if (retries <= 0) {
            console.log('[Voice2SMS] Page did not become ready in time');
            return;
        }

        // Wait for the GV Angular SPA to render
        var rendered = document.querySelector(
            'gv-side-nav, gv-thread-list, [gv-test-id="sidenav"]'
        );

        if (!rendered) {
            setTimeout(function() { start(retries - 1); }, POLL_INTERVAL);
            return;
        }

        console.log('[Voice2SMS] GV SPA detected as rendered');

        // Try to find an existing conversation first
        var existing = findExistingConversation();
        if (existing) {
            existing.click();
            setTimeout(function() { fillBody(MAX_RETRIES); }, randDelay(800, 1500));
        } else {
            // Start a new conversation
            if (clickNewConversation()) {
                setTimeout(function() { fillRecipient(MAX_RETRIES); }, randDelay(400, 900));
            } else {
                setTimeout(function() { start(retries - 1); }, POLL_INTERVAL);
            }
        }
    }

    // Kick off
    start(MAX_RETRIES);
}
