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

    // Normalize phone: strip everything except digits and leading +
    var normalizedPhone = phone.replace(/[^\d+]/g, '');
    // Also create a digits-only version for matching
    var digitsOnly = phone.replace(/\D/g, '');

    console.log('[Voice2SMS] Starting compose for: ' + normalizedPhone);

    /**
     * Simulate realistic input on an element (dispatches input/change events).
     */
    function setInputValue(el, value) {
        // Focus the element
        el.focus();
        el.click();

        // Use native setter to bypass React/Angular controlled input
        var nativeSetter = Object.getOwnPropertyDescriptor(
            window.HTMLInputElement.prototype, 'value'
        ) || Object.getOwnPropertyDescriptor(
            window.HTMLTextAreaElement.prototype, 'value'
        );

        if (nativeSetter && nativeSetter.set) {
            nativeSetter.set.call(el, value);
        } else {
            el.value = value;
        }

        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        el.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true }));
        el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
    }

    /**
     * Try to find an existing conversation with this phone number.
     * GV uses itemId attributes like "t.+15551234567" on conversation items.
     */
    function findExistingConversation() {
        // Try itemId selector (confirmed from GV DOM)
        var selectors = [
            '[itemId="t.' + normalizedPhone + '"]',
            '[itemId="t.+' + digitsOnly + '"]',
            '[itemId="t.' + digitsOnly + '"]'
        ];

        for (var i = 0; i < selectors.length; i++) {
            var el = document.querySelector(selectors[i]);
            if (el) {
                console.log('[Voice2SMS] Found existing conversation via: ' + selectors[i]);
                return el;
            }
        }

        // Fallback: search by text content
        var items = document.querySelectorAll('[role="listitem"], gv-thread-item');
        for (var j = 0; j < items.length; j++) {
            var text = items[j].textContent || '';
            if (text.indexOf(digitsOnly) !== -1 ||
                text.indexOf(normalizedPhone) !== -1) {
                console.log('[Voice2SMS] Found conversation by text content');
                return items[j];
            }
        }

        return null;
    }

    /**
     * Click the "new conversation" / compose button.
     */
    function clickNewConversation() {
        // Try the send-new-message button (floating action button)
        var selectors = [
            '[gv-test-id="send-new-message"]',
            'a[aria-label*="new"]',
            'a[aria-label*="Send"]',
            'button[aria-label*="new"]',
            'button[aria-label*="compose"]',
            '[data-tooltip*="message"]'
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

        // Look for the "To" / recipient input
        var recipientInput = document.querySelector(
            'input[placeholder*="name"], input[placeholder*="number"], ' +
            'input[aria-label*="To"], input[aria-label*="recipient"], ' +
            '[gv-test-id="recipient-input"] input, ' +
            'gv-recipient-picker input'
        );

        if (!recipientInput) {
            setTimeout(function() { fillRecipient(retries - 1); }, POLL_INTERVAL);
            return;
        }

        console.log('[Voice2SMS] Found recipient input, filling: ' + normalizedPhone);
        setInputValue(recipientInput, normalizedPhone);

        // Wait, then press Enter to confirm the recipient
        setTimeout(function() {
            recipientInput.dispatchEvent(new KeyboardEvent('keydown', {
                key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
            }));
            recipientInput.dispatchEvent(new KeyboardEvent('keyup', {
                key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
            }));

            // After recipient is set, wait for suggestion/chip, then fill body
            setTimeout(function() {
                // Try clicking the first suggestion if a dropdown appeared
                var suggestion = document.querySelector(
                    '[role="option"], [role="listbox"] [role="option"], ' +
                    'gv-contact-suggestion, .suggestion'
                );
                if (suggestion) {
                    console.log('[Voice2SMS] Clicking recipient suggestion');
                    suggestion.click();
                }

                setTimeout(function() { fillBody(MAX_RETRIES); }, 500);
            }, 500);
        }, 300);
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
            'textarea[aria-label*="message"], textarea[aria-label*="text"], ' +
            'textarea[placeholder*="message"], textarea[placeholder*="text"], ' +
            '[gv-test-id="message-input"] textarea, ' +
            'gv-message-compose textarea, ' +
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
            console.log('[Voice2SMS] Auto-send enabled, will send in ' + autoSendDelay + 'ms');
            setTimeout(function() { clickSend(5); }, autoSendDelay);
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
            '[gv-test-id="send-message"], ' +
            'button[aria-label*="Send"], ' +
            '[aria-label="Send message"], ' +
            'gv-icon-button[icon="send"], ' +
            'button[data-tooltip*="Send"]'
        );

        if (!sendBtn) {
            setTimeout(function() { clickSend(retries - 1); }, POLL_INTERVAL);
            return;
        }

        // Only send if the button is enabled
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

        // Wait for any indication that the GV SPA has rendered
        var rendered = document.querySelector(
            'gv-side-nav, [gv-test-id], gv-thread-list, ' +
            '[role="navigation"], [aria-label*="conversation"]'
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
            // Wait for conversation to open, then fill body
            setTimeout(function() { fillBody(MAX_RETRIES); }, 1000);
        } else {
            // Start a new conversation
            if (clickNewConversation()) {
                setTimeout(function() { fillRecipient(MAX_RETRIES); }, 500);
            } else {
                // Retry — SPA might still be loading
                setTimeout(function() { start(retries - 1); }, POLL_INTERVAL);
            }
        }
    }

    // Kick off
    start(MAX_RETRIES);
}
