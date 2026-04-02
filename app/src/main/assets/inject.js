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

// ── Angular Zone Helpers (shared by voice2sms and sendImageToGV) ──────────

/**
 * Extract the zone object from a single Zone.js listener task.
 */
function _v2sGetZoneFromTask(task) {
    if (task.zone && typeof task.zone.run === 'function') {
        return task.zone;
    }
    var props = Object.getOwnPropertyNames(task);
    var fallback = null;
    for (var j = 0; j < props.length; j++) {
        try {
            var v = task[props[j]];
            if (v && typeof v === 'object' && typeof v.run === 'function' && v.name) {
                if (v.name !== '<root>') return v;
                if (!fallback) fallback = v;
            }
        } catch(e) {}
    }
    return fallback;
}

/**
 * Get Angular's zone reference from an element's Zone.js symbol properties.
 */
function _v2sGetAngularZone(el) {
    var zoneKeys = Object.keys(el).filter(function(k) {
        return k.indexOf('__zone_symbol__') === 0;
    });
    var fallbackZone = null;
    for (var i = 0; i < zoneKeys.length; i++) {
        var listeners = el[zoneKeys[i]];
        if (!listeners || !listeners.length) continue;
        for (var li = 0; li < listeners.length; li++) {
            var zone = _v2sGetZoneFromTask(listeners[li]);
            if (zone) {
                if (zone.name !== '<root>') return zone;
                if (!fallbackZone) fallbackZone = zone;
            }
        }
    }
    return fallbackZone;
}

/**
 * Search page elements for Angular zone (broader than a single element).
 */
function _v2sFindAngularZoneGlobal() {
    var candidates = document.querySelectorAll(
        'button[aria-label], input, textarea, mat-chip-row button, a[href]'
    );
    for (var i = 0; i < Math.min(candidates.length, 30); i++) {
        var zone = _v2sGetAngularZone(candidates[i]);
        if (zone && zone.name !== '<root>') return zone;
    }
    return null;
}

// ── Main composer function ────────────────────────────────────────────────

function voice2sms(phone, body, autoSend, autoSendDelay) {
    'use strict';

    var MAX_RETRIES = 50;
    var POLL_INTERVAL = 500;

    // Generation counter: cancels timers from previous injections so only
    // the latest voice2sms() call drives the DOM.
    window._v2sGeneration = (window._v2sGeneration || 0) + 1;
    var myGeneration = window._v2sGeneration;

    function isCancelled() {
        return window._v2sGeneration !== myGeneration;
    }

    /** Return a random integer between min and max (inclusive). */
    function randDelay(min, max) {
        return Math.floor(Math.random() * (max - min + 1)) + min;
    }

    // Normalize phone: strip everything except digits and leading +
    var normalizedPhone = phone.replace(/[^\d+]/g, '');
    // Also create a digits-only version for matching
    var digitsOnly = phone.replace(/\D/g, '');
    // Last 10 digits for matching formatted numbers like "(555) 123-4567"
    var last10 = digitsOnly.length > 10 ? digitsOnly.slice(-10) : digitsOnly;

    console.log('[Voice2SMS] Starting compose for: ' + normalizedPhone);


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

        var angularZone = _v2sGetAngularZone(el);
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

        // Find Angular zone — try specific task first, then element, then global
        var zone = _v2sGetZoneFromTask(task);
        var zoneSource = 'task';
        if (!zone || zone.name === '<root>') {
            var elZone = _v2sGetAngularZone(inp);
            if (elZone && elZone.name !== '<root>') {
                zone = elZone;
                zoneSource = 'element';
            }
        }
        if (!zone || zone.name === '<root>') {
            var globalZone = _v2sFindAngularZoneGlobal();
            if (globalZone) {
                zone = globalZone;
                zoneSource = 'global';
            }
        }
        console.log('[Voice2SMS] Zone for chip creation: ' +
            (zone ? zone.name : 'none') + ' (source: ' + zoneSource + ')');

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
        if (isCancelled()) return;
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
            setTimeout(function() { fillRecipient(retries - 1); }, 100);
            return;
        }

        console.log('[Voice2SMS] Found recipient input');

        // Record chip count before creation for count-based verification
        var chipsBefore = document.querySelectorAll('mat-chip-row, .mdc-evolution-chip').length;
        console.log('[Voice2SMS] Chips before creation: ' + chipsBefore);

        // Primary: create chip directly (bypasses autocomplete dropdown)
        var created = createChipDirectly(recipientInput);
        if (created) {
            // Verify chip was actually created — use tight poll, no artificial delay
            setTimeout(function() { verifyChipAndProceed(recipientInput, 15, chipsBefore); }, 50);
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

        // Approach 2: Temporarily disable backdrops, restore after 3s so
        // subsequent manual interactions (e.g. adding another recipient) still work
        setTimeout(function() {
            var backdrops = document.querySelectorAll('.cdk-overlay-backdrop');
            for (var i = 0; i < backdrops.length; i++) {
                backdrops[i].style.pointerEvents = 'none';
                backdrops[i].classList.remove('cdk-overlay-backdrop-showing');
            }
            // Also hide any open overlay panes (autocomplete dropdowns)
            var panes = document.querySelectorAll('.cdk-overlay-pane');
            var hiddenPanes = [];
            for (var j = 0; j < panes.length; j++) {
                if (panes[j].querySelector('.send-to-button, .autocomplete-panel, mat-autocomplete')) {
                    panes[j].style.display = 'none';
                    hiddenPanes.push(panes[j]);
                }
            }
            // Restore after 3s so the UI isn't permanently broken
            setTimeout(function() {
                for (var k = 0; k < backdrops.length; k++) {
                    backdrops[k].style.pointerEvents = '';
                }
                for (var l = 0; l < hiddenPanes.length; l++) {
                    hiddenPanes[l].style.display = '';
                }
            }, 3000);
        }, 200);
    }

    /**
     * Verify that a chip was created, dismiss any overlays, and proceed to body.
     * Uses count-based verification: chip count must increase from chipsBefore.
     * This handles cases where the chip shows a contact name instead of digits.
     */
    function verifyChipAndProceed(inp, retries, chipsBefore) {
        if (isCancelled()) return;
        var chips = document.querySelectorAll('mat-chip-row, .mdc-evolution-chip');
        var hasNewChip = chips.length > chipsBefore;

        // Log chip details for diagnostics
        if (retries === 15 || retries === 10 || retries === 0) {
            for (var c = 0; c < chips.length; c++) {
                var ct = (chips[c].textContent || '').replace(/\s+/g, ' ').trim();
                console.log('[Voice2SMS]   chip[' + c + ']: "' + ct.substring(0, 60) + '"');
            }
        }
        console.log('[Voice2SMS] Chips: ' + chips.length + ' (was ' + chipsBefore + '), new=' + hasNewChip);

        if (hasNewChip) {
            dismissOverlays();
            fillBody(MAX_RETRIES);
        } else if (retries > 0) {
            // Chip might need a moment to render — tight poll
            setTimeout(function() { verifyChipAndProceed(inp, retries - 1, chipsBefore); }, 50);
        } else {
            console.log('[Voice2SMS] Chip not created after polling, falling back to typing');
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

        var angularZone = _v2sGetAngularZone(inp);
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
        if (isCancelled()) return;
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
            console.log('[Voice2SMS] Requesting keyboard show');
            V2SBridge.requestShowKeyboard();
        }
    }

    /**
     * Fill in the message body textarea.
     */
    function fillBody(retries) {
        if (isCancelled()) return;
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
            setTimeout(function() { fillBody(retries - 1); }, 100);
            return;
        }

        if (!body || body.length === 0) {
            console.log('[Voice2SMS] No body to fill, focusing textarea for keyboard');
            focusTextarea(textarea);
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
        focusTextarea(textarea);

        if (autoSend) {
            var jitteredDelay = Math.max(0, autoSendDelay + randDelay(-300, 500));
            console.log('[Voice2SMS] Auto-send enabled, will send in ' + jitteredDelay + 'ms');
            setTimeout(function() { clickSend(5); }, jitteredDelay);
        }
    }

    /**
     * Click the send button.
     */
    function clickSend(retries) {
        if (isCancelled()) return;
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
     * Reset compose view for warm starts — remove existing chips and clear body.
     * Returns true if we were on a compose view and reset it.
     */
    function resetComposeState() {
        var chips = document.querySelectorAll('mat-chip-row, .mdc-evolution-chip');
        if (chips.length === 0) return false;

        console.log('[Voice2SMS] Warm start: clearing ' + chips.length + ' existing chips');

        // Click remove buttons on each chip
        for (var i = 0; i < chips.length; i++) {
            var removeBtn = chips[i].querySelector(
                'button[aria-label*="remove"], ' +
                'button[matchipremovedisable], ' +
                '.mdc-evolution-chip__action--trailing'
            );
            if (removeBtn) removeBtn.click();
        }

        // Clear body textarea
        var ta = document.querySelector('textarea[placeholder*="message"]');
        if (ta) {
            var nativeSetter = Object.getOwnPropertyDescriptor(
                window.HTMLTextAreaElement.prototype, 'value'
            );
            if (nativeSetter && nativeSetter.set) {
                nativeSetter.set.call(ta, '');
            } else {
                ta.value = '';
            }
            ta.dispatchEvent(new Event('input', { bubbles: true }));
        }

        return true;
    }

    /**
     * Main entry: poll until the page is ready, then compose.
     */
    function start(retries) {
        if (isCancelled()) return;
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

        // Check current view state for warm start handling
        var recipientInput = document.querySelector(
            'input[placeholder="Type a name or phone number"], ' +
            'input[placeholder*="name or phone"]'
        );
        var existingChips = document.querySelectorAll('mat-chip-row, .mdc-evolution-chip');
        var fabButton = document.querySelector('button[aria-label*="new"]');

        console.log('[Voice2SMS] View state: recipientInput=' + !!recipientInput +
            ', chips=' + existingChips.length + ', fab=' + !!fabButton +
            ', url=' + window.location.pathname.substring(0, 40));

        if (!fabButton) {
            // Not on messages list — stale compose or conversation thread.
            // Java's onNewIntent always reloads the page, so this only happens
            // if injection ran before the reload completed. Just keep polling.
            console.log('[Voice2SMS] Waiting for messages list (no FAB yet)');
            setTimeout(function() { start(retries - 1); }, POLL_INTERVAL);
            return;
        }

        // Messages list (FAB visible): normal flow — dismiss any stale overlays first
        dismissOverlays();

        var existing = findExistingConversation();
        if (existing) {
            existing.click();
            setTimeout(function() { fillBody(MAX_RETRIES); }, 100);
        } else {
            // Start a new conversation
            if (clickNewConversation()) {
                // Give GV compose animation time to render before polling
                setTimeout(function() { fillRecipient(MAX_RETRIES); }, 500);
            } else {
                setTimeout(function() { start(retries - 1); }, POLL_INTERVAL);
            }
        }
    }

    // Kick off
    start(MAX_RETRIES);
}

// ── Sticker/Image Bridge ──────────────────────────────────────────────────

/**
 * Send an image to Google Voice by injecting it into the file upload input.
 * Called from Java when GBoard delivers a sticker via commitContent.
 *
 * Flow: decode base64 → open attachment menu → click upload → inject File
 * into the hidden <input type="file"> → dispatch change event.
 *
 * @param {string} base64Data - Base64-encoded image bytes
 * @param {string} mimeType  - MIME type (image/png, image/gif, image/jpeg)
 * @param {string} fileName  - File name for the attachment
 */
function sendImageToGV(base64Data, mimeType, fileName) {
    'use strict';

    var MAX_POLLS = 25;
    var POLL_MS = 120;

    console.log('[Voice2SMS] sendImageToGV: ' + mimeType + ', ' + fileName);

    // 1. Decode base64 to File object
    var byteChars = atob(base64Data);
    var byteArray = new Uint8Array(byteChars.length);
    for (var i = 0; i < byteChars.length; i++) {
        byteArray[i] = byteChars.charCodeAt(i);
    }
    var blob = new Blob([byteArray], { type: mimeType });
    var file = new File([blob], fileName, { type: mimeType });

    // 2. Find and click the attachment menu button
    function findAttachButton() {
        // Try multiple selectors — mobile and desktop variants
        var selectors = [
            'button[aria-label*="ttach"]',
            'button[aria-label*="photo"]',
            'button[aria-label*="image"]',
            'button[aria-haspopup="menu"]'
        ];
        for (var s = 0; s < selectors.length; s++) {
            var btns = document.querySelectorAll(selectors[s]);
            for (var b = 0; b < btns.length; b++) {
                // Filter to buttons near the compose area (avoid nav menus)
                var rect = btns[b].getBoundingClientRect();
                if (rect.bottom > window.innerHeight * 0.5) {
                    return btns[b];
                }
            }
        }
        return null;
    }

    function openAttachMenu(retries) {
        if (retries <= 0) {
            console.log('[Voice2SMS] sendImageToGV: could not find attach button');
            return;
        }
        var btn = findAttachButton();
        if (!btn) {
            setTimeout(function() { openAttachMenu(retries - 1); }, POLL_MS);
            return;
        }
        console.log('[Voice2SMS] sendImageToGV: clicking attach button');
        btn.click();
        setTimeout(function() { clickUploadItem(MAX_POLLS); }, 200);
    }

    // 3. Click the "Upload" menu item — this triggers onShowFileChooser
    //    on the Java side, which auto-supplies the sticker file (no picker shown).
    //    We do NOT inject via DataTransfer — that would trigger a second file chooser.
    function clickUploadItem(retries) {
        if (retries <= 0) {
            console.log('[Voice2SMS] sendImageToGV: could not find upload menu item');
            return;
        }
        var items = document.querySelectorAll(
            'button.mat-mdc-menu-item, .mat-menu-item, [role="menuitem"]'
        );
        var uploadItem = null;
        for (var i = 0; i < items.length; i++) {
            var text = (items[i].textContent || '').toLowerCase();
            if (text.indexOf('upload') !== -1 || text.indexOf('photo') !== -1 ||
                text.indexOf('image') !== -1 || text.indexOf('file') !== -1) {
                uploadItem = items[i];
                break;
            }
        }
        if (!uploadItem && items.length > 0) {
            uploadItem = items[0];
        }
        if (!uploadItem) {
            setTimeout(function() { clickUploadItem(retries - 1); }, POLL_MS);
            return;
        }
        console.log('[Voice2SMS] sendImageToGV: clicking upload item');
        uploadItem.click();
        // Java side suppresses the Google Photos picker via onShowFileChooser.
        // We inject the file via DataTransfer after the input appears.
        setTimeout(function() { injectFile(MAX_POLLS); }, 200);
    }

    // 4. Find the file input and inject our image via DataTransfer
    function injectFile(retries) {
        if (retries <= 0) {
            console.log('[Voice2SMS] sendImageToGV: could not find file input');
            return;
        }
        var fileInput = document.querySelector('input[type="file"]');
        if (!fileInput) {
            setTimeout(function() { injectFile(retries - 1); }, POLL_MS);
            return;
        }

        console.log('[Voice2SMS] sendImageToGV: injecting file via DataTransfer');
        var dt = new DataTransfer();
        dt.items.add(file);
        fileInput.files = dt.files;

        // Dispatch events in Angular zone for change detection
        var zone = _v2sFindAngularZoneGlobal();
        var dispatchEvents = function() {
            fileInput.dispatchEvent(new Event('change', { bubbles: true }));
            fileInput.dispatchEvent(new Event('input', { bubbles: true }));
            console.log('[Voice2SMS] sendImageToGV: done, image should appear in compose');
        };

        if (zone && zone.name !== '<root>') {
            zone.run(dispatchEvents);
        } else {
            dispatchEvents();
        }
    }

    // Try direct file input injection first (no menu click = no picker).
    // Fall back to menu-based approach if no file input exists in DOM.
    function tryDirectInject() {
        var fileInput = document.querySelector('input[type="file"]');
        if (fileInput) {
            console.log('[Voice2SMS] sendImageToGV: found existing file input, injecting directly');
            injectFile(1); // already found, inject immediately
        } else {
            console.log('[Voice2SMS] sendImageToGV: no file input in DOM, using menu approach');
            openAttachMenu(MAX_POLLS);
        }
    }

    tryDirectInject();
}
