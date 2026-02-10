/**
 * Voice2SMS — Google Voice WebView JavaScript injection.
 *
 * Composes a message in the Google Voice web UI by:
 * 1. Finding an existing conversation by phone number, OR
 * 2. Starting a new conversation via the compose button.
 * 3. Creating a recipient chip (directly via Angular's internal callback).
 * 4. Populating the message body.
 * 5. Optionally clicking send after a delay.
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
     *
     * Zone.js stores event listener tasks on elements as __zone_symbol__{event}{capture}.
     * Each task object has the Angular zone stored in an obfuscated property.
     * We find it by scanning for an object with a .run() method and .name property.
     */
    function getAngularZone(el) {
        var zoneKeys = Object.keys(el).filter(function(k) {
            return k.indexOf('__zone_symbol__') === 0;
        });
        for (var i = 0; i < zoneKeys.length; i++) {
            var listeners = el[zoneKeys[i]];
            if (!listeners || !listeners.length) continue;
            var task = listeners[0];
            // Check legacy .zone property first
            if (task.zone && typeof task.zone.run === 'function') {
                return task.zone;
            }
            // Scan task properties for the zone object (obfuscated name)
            var props = Object.getOwnPropertyNames(task);
            for (var j = 0; j < props.length; j++) {
                try {
                    var v = task[props[j]];
                    if (v && typeof v === 'object' && typeof v.run === 'function' && v.name) {
                        return v;
                    }
                } catch(e) {}
            }
        }
        return null;
    }

    /**
     * Set value on an input/textarea and trigger Angular change detection.
     * Uses native setter + InputEvent dispatched inside Angular's zone.
     */
    function setInputValue(el, value) {
        el.focus();
        el.click();

        var proto = (el.tagName === 'TEXTAREA')
            ? window.HTMLTextAreaElement.prototype
            : window.HTMLInputElement.prototype;
        var nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value');
        if (nativeSetter && nativeSetter.set) {
            nativeSetter.set.call(el, value);
        } else {
            el.value = value;
        }

        var angularZone = getAngularZone(el);
        var inputEvent = new InputEvent('input', {
            bubbles: true, inputType: 'insertText', data: value
        });

        if (angularZone) {
            console.log('[Voice2SMS] Dispatching in Angular zone: ' + angularZone.name);
            angularZone.run(function() {
                el.dispatchEvent(inputEvent);
            });
        } else {
            el.dispatchEvent(inputEvent);
        }
    }

    /**
     * Try to find an existing conversation with this phone number.
     */
    function findExistingConversation() {
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
            'button[aria-label*="new"]',       // Mobile: FAB button
            '[aria-label="Send new message"]',  // Desktop: div role=button
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
     * Create a recipient chip by directly calling Angular's matChipInputTokenEnd
     * callback. This bypasses the autocomplete dropdown entirely — no need for
     * isTrusted keyboard events or dropdown interaction.
     *
     * How it works:
     * - Angular Material's MatChipInput registers a (matChipInputTokenEnd) handler
     *   via Zone.js patched addEventListener on the input element
     * - The listener task is stored at input['__zone_symbol__matChipInputTokenEndfalse']
     * - We set the input value, then call the callback with a mock event matching
     *   MatChipInputTokenEndEvent: { value, chipInput: { inputElement, clear } }
     * - Angular processes it as if the user pressed Enter, creating the chip
     */
    function createChipDirectly(inp) {
        var nativeSetter = Object.getOwnPropertyDescriptor(
            window.HTMLInputElement.prototype, 'value'
        );

        // Set the input value
        if (nativeSetter && nativeSetter.set) {
            nativeSetter.set.call(inp, normalizedPhone);
        } else {
            inp.value = normalizedPhone;
        }
        inp.focus();
        console.log('[Voice2SMS] Input value set: ' + inp.value);

        // Find the matChipInputTokenEnd listener
        var tokenListeners = inp['__zone_symbol__matChipInputTokenEndfalse'];
        if (!tokenListeners || !tokenListeners.length) {
            console.log('[Voice2SMS] No matChipInputTokenEnd listener found');
            return false;
        }

        var task = tokenListeners[0];
        var callback = task.callback;
        if (typeof callback !== 'function') {
            console.log('[Voice2SMS] matChipInputTokenEnd callback is not a function');
            return false;
        }

        // Find Angular zone from the task
        var zone = getAngularZone(inp);

        // Build mock MatChipInputTokenEndEvent
        var setter = nativeSetter;
        var mockEvent = {
            value: normalizedPhone,
            chipInput: {
                inputElement: inp,
                clear: function() {
                    if (setter && setter.set) {
                        setter.set.call(inp, '');
                    } else {
                        inp.value = '';
                    }
                },
                focused: true
            },
            preventDefault: function() {}
        };

        // Call the callback inside Angular's zone
        if (zone) {
            console.log('[Voice2SMS] Calling matChipInputTokenEnd in zone: ' + zone.name);
            zone.run(function() { callback(mockEvent); });
        } else {
            console.log('[Voice2SMS] Calling matChipInputTokenEnd (no zone found)');
            callback(mockEvent);
        }

        // Explicitly clear the input value — Angular's clear() may not fire
        if (nativeSetter && nativeSetter.set) {
            nativeSetter.set.call(inp, '');
        } else {
            inp.value = '';
        }
        inp.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'deleteContent' }));

        return true;
    }

    /**
     * Fill in the recipient field for a new conversation.
     * Primary: create chip directly via matChipInputTokenEnd callback.
     * Fallback: type char-by-char and click the dropdown suggestion.
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

        console.log('[Voice2SMS] Found recipient input');

        // Primary: create chip directly (bypasses autocomplete dropdown)
        var created = createChipDirectly(recipientInput);
        if (created) {
            // Verify chip was actually created
            setTimeout(function() { verifyChipAndProceed(recipientInput, 10); }, randDelay(500, 1000));
        } else {
            // Fallback: type and use dropdown
            console.log('[Voice2SMS] Direct chip creation unavailable, falling back to typing');
            fillRecipientViaTyping(recipientInput);
        }
    }

    /**
     * Dismiss any CDK overlay backdrops that may be blocking interaction.
     * Tries multiple approaches since synthetic events may not work.
     */
    function dismissOverlays() {
        // Approach 1: Click the backdrop directly
        var backdrop = document.querySelector('.cdk-overlay-backdrop.cdk-overlay-backdrop-showing');
        if (backdrop) {
            console.log('[Voice2SMS] Clicking CDK backdrop to dismiss');
            backdrop.click();
        }

        // Approach 2: Remove backdrop elements entirely as a fallback
        setTimeout(function() {
            var backdrops = document.querySelectorAll('.cdk-overlay-backdrop');
            for (var i = 0; i < backdrops.length; i++) {
                backdrops[i].style.pointerEvents = 'none';
                backdrops[i].classList.remove('cdk-overlay-backdrop-showing');
            }
            // Also hide any open overlay panes (autocomplete dropdowns)
            var panes = document.querySelectorAll('.cdk-overlay-pane');
            for (var j = 0; j < panes.length; j++) {
                if (panes[j].querySelector('.send-to-button, .autocomplete-panel, mat-autocomplete')) {
                    panes[j].style.display = 'none';
                }
            }
        }, 200);
    }

    /**
     * Verify that a chip was created, dismiss any overlays, and proceed to body.
     */
    function verifyChipAndProceed(inp, retries) {
        var chips = document.querySelectorAll('mat-chip-row, .mdc-evolution-chip');
        console.log('[Voice2SMS] Recipient chips: ' + chips.length);

        if (chips.length > 0) {
            dismissOverlays();
            setTimeout(function() { fillBody(MAX_RETRIES); }, randDelay(500, 900));
        } else if (retries > 0) {
            // Chip might need a moment to render
            setTimeout(function() { verifyChipAndProceed(inp, retries - 1); }, POLL_INTERVAL);
        } else {
            console.log('[Voice2SMS] Chip not created, falling back to typing');
            fillRecipientViaTyping(inp);
        }
    }

    /**
     * Fallback: type recipient char-by-char and click the autocomplete suggestion.
     * Used when direct chip creation is not available.
     */
    function fillRecipientViaTyping(inp) {
        console.log('[Voice2SMS] Typing recipient: ' + normalizedPhone);

        // Clear any value from the direct chip attempt
        var nativeSetter = Object.getOwnPropertyDescriptor(
            window.HTMLInputElement.prototype, 'value'
        );
        if (nativeSetter && nativeSetter.set) {
            nativeSetter.set.call(inp, '');
        }

        var angularZone = getAngularZone(inp);
        var i = 0;

        function typeNext() {
            if (i >= normalizedPhone.length) {
                console.log('[Voice2SMS] Finished typing, value=' + inp.value);
                setTimeout(function() {
                    waitForDropdownAndClick(inp, MAX_RETRIES);
                }, randDelay(400, 800));
                return;
            }

            var char = normalizedPhone[i];
            inp.focus();
            var inserted = document.execCommand('insertText', false, char);

            if (!inserted) {
                var currentValue = normalizedPhone.substring(0, i + 1);
                if (nativeSetter && nativeSetter.set) {
                    nativeSetter.set.call(inp, currentValue);
                }
                var inputEvent = new InputEvent('input', {
                    bubbles: true, inputType: 'insertText', data: char
                });
                if (angularZone) {
                    angularZone.run(function() { inp.dispatchEvent(inputEvent); });
                } else {
                    inp.dispatchEvent(inputEvent);
                }
            }

            i++;
            setTimeout(typeNext, randDelay(40, 80));
        }

        inp.focus();
        inp.click();
        typeNext();
    }

    /**
     * Wait for the autocomplete dropdown and click the first suggestion.
     */
    function waitForDropdownAndClick(inp, retries) {
        if (retries <= 0) {
            console.log('[Voice2SMS] Dropdown never appeared, proceeding to body');
            setTimeout(function() { fillBody(MAX_RETRIES); }, randDelay(300, 700));
            return;
        }

        var overlay = document.querySelector('.cdk-overlay-container');
        var sendToBtn = overlay && overlay.querySelector('.send-to-button');
        if (!sendToBtn) {
            var contactBtns = overlay && overlay.querySelectorAll('button.container.row');
            if (contactBtns && contactBtns.length > 0) sendToBtn = contactBtns[0];
        }

        if (!sendToBtn) {
            if (retries % 5 === 0) {
                console.log('[Voice2SMS] Dropdown poll: retries=' + retries +
                    ', inputValue=' + inp.value);
            }
            setTimeout(function() { waitForDropdownAndClick(inp, retries - 1); }, POLL_INTERVAL);
            return;
        }

        var label = sendToBtn.textContent.replace(/\s+/g, ' ').trim().substring(0, 60);
        console.log('[Voice2SMS] Clicking: ' + label);
        sendToBtn.click();

        // Dismiss backdrop and verify chip
        setTimeout(function() {
            dismissOverlays();
            setTimeout(function() {
                var chips = document.querySelectorAll('mat-chip-row, .mdc-evolution-chip');
                console.log('[Voice2SMS] Recipient chips: ' + chips.length);
                setTimeout(function() { fillBody(MAX_RETRIES); }, randDelay(300, 700));
            }, randDelay(300, 500));
        }, randDelay(400, 800));
    }

    /**
     * Focus the message textarea to bring up the keyboard.
     * In Android WebView, programmatic .focus()/.click() don't open the
     * soft keyboard — a trusted touch event is required. Use the native
     * bridge to tap at the textarea's coordinates.
     */
    function focusTextarea(textarea) {
        // Focus the textarea via JS and request keyboard from native side.
        // We do NOT use dispatchTouchEvent for coordinate-based tapping because
        // coordinate mapping between CSS and WebView view coords is unreliable
        // and often hits the wrong element.
        //
        // Instead: JS .focus() sets DOM focus correctly on the textarea, and
        // requestShowKeyboard() calls IMM.showSoftInput(SHOW_FORCED) which
        // shows the keyboard. The WebView routes keystrokes to the JS-focused
        // element via its InputConnection.
        textarea.focus();
        textarea.click();

        // Dispatch a synthetic mousedown/mouseup on the textarea — this helps
        // WebView's Blink engine establish the editing context for this element
        var rect = textarea.getBoundingClientRect();
        var cx = rect.left + rect.width / 2;
        var cy = rect.top + rect.height / 2;
        textarea.dispatchEvent(new MouseEvent('mousedown', {
            bubbles: true, clientX: cx, clientY: cy
        }));
        textarea.dispatchEvent(new MouseEvent('mouseup', {
            bubbles: true, clientX: cx, clientY: cy
        }));

        console.log('[Voice2SMS] Textarea focused, active=' +
            (document.activeElement === textarea) +
            ', tag=' + document.activeElement.tagName);

        if (typeof V2SBridge !== 'undefined' && V2SBridge.requestShowKeyboard) {
            setTimeout(function() {
                console.log('[Voice2SMS] Requesting keyboard show');
                V2SBridge.requestShowKeyboard();
            }, 400);
        }
    }

    /**
     * Fill in the message body textarea.
     */
    function fillBody(retries) {
        if (retries <= 0) {
            console.log('[Voice2SMS] Gave up waiting for message textarea');
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

        // Dismiss any remaining overlays before interacting with textarea
        dismissOverlays();

        if (!body || body.length === 0) {
            console.log('[Voice2SMS] No body to fill, focusing textarea for keyboard');
            setTimeout(function() { focusTextarea(textarea); }, 300);
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

        // Focus after a brief delay to ensure Angular has processed the value
        setTimeout(function() { focusTextarea(textarea); }, 200);

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
