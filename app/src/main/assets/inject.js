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

/**
 * deliver sec-M1: the per-document bridge nonce. Java sets it (BridgeNonce.pageStatement) with
 * the Send Later module and the scheduled composer, only on a trusted GV page; every Send Later
 * and scheduled V2SBridge call passes it first and Java ignores a call without the current value.
 * Declared without an initializer so re-evaluating this script keeps the value. Never logged.
 */
var _v2sBridgeNonce;

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

// ── Scheduled send entry (Send Later R30; LIVE-F6, LIVE-F8, LIVE-F10) ──────

/** The box holds our scheduled text (surrounding whitespace aside). */
function _v2sScheduledHolds(el, text) {
    if (!el || typeof text !== 'string' || !text) return false;
    var v = el.isContentEditable ? el.textContent : el.value;
    return typeof v === 'string' && (v === text || v.trim() === text.trim());
}

/** Puts value into the message box, Angular-safe (native setter + InputEvent in the zone). */
function _v2sScheduledSet(el, value, focus) {
    if (el.isContentEditable) {
        if (focus) el.focus();
        el.textContent = value;
        el.dispatchEvent(new Event('input', { bubbles: true }));
    } else {
        _v2sSlSetInputValue(el, value, focus);
    }
}

/**
 * LIVE-F10: a scheduled job ended unsent after typing (pre-send hold, timeout, Stop). Empties the
 * message box only when it still holds our scheduled text, so none is left in GV's box to be sent
 * by hand by mistake; any other text is left alone. Never logs the text.
 */
function v2sScheduledClear(text) {
    var el = document.querySelector(V2S_COMPOSER_SELECTOR);
    if (!_v2sScheduledHolds(el, text)) return false;
    _v2sScheduledSet(el, '', false);
    console.log('[Voice2SMS] Scheduled send: cleared the message box');
    return true;
}

/**
 * D32 Send both: the scheduled job ended; puts the user's draft back into the message box when the
 * box is empty or still holds exactly our scheduled text (anything else is left alone). Returns
 * true when the box then holds the draft. Never logs either text.
 */
function v2sScheduledRestoreDraft(draft, text) {
    var el = document.querySelector(V2S_COMPOSER_SELECTOR);
    if (!el || typeof draft !== 'string' || !draft) return false;
    var v = el.isContentEditable ? el.textContent : el.value;
    if (typeof v === 'string' && v.length > 0 && !_v2sScheduledHolds(el, text)) {
        console.log('[Voice2SMS] Scheduled send: the message box is not empty, nothing put back');
        return false;
    }
    _v2sScheduledSet(el, draft, false);
    var after = el.isContentEditable ? el.textContent : el.value;
    var ok = after === draft;
    console.log(ok ? '[Voice2SMS] Scheduled send: message box restored'
        : '[Voice2SMS] Scheduled send: message box could not be restored');
    return ok;
}

/**
 * Scheduled compose. Java sets window._v2sScheduledOpts first: {mode, settleMs, pollMs, waitMs}.
 *
 * mode 'thread' (LIVE-F10): Java loaded the job's own thread URL by a full page load. Once GV
 * shows that conversation (gv-message-list, and the location is the job's 1:1 thread) the thread's
 * own box is filled, unless it already holds text that is not our body (OD34: the user's draft is
 * never overwritten; V2SBridge.onScheduledDraftPresent and the job stops unsent); when GV goes
 * elsewhere (LIVE-F11) Java is asked to load the job's thread again (V2SBridge.onScheduledWrongPage,
 * bounded) while the script waits; when the thread shows no conversation within settleMs, Java is
 * told (V2SBridge.onScheduledNoThread), which reloads the thread page once (full load, one more
 * settle) and only then loads the messages list for mode 'new'.
 * mode 'new' (deliver H1): the scheduled new-conversation flow (_v2sScheduledNewMessage): the job's
 * conversation is opened only by its own thread id, else a new message gets the job's number as
 * its recipient chip; the D32 draft step runs before typing into a box that holds text. Never
 * voice2sms() (its list match on text can open another person's conversation), never its auto-send.
 *
 * Either way it reports "typed" only once the box really holds our text
 * (V2SBridge.onScheduledTyped) and taps Send only when Java says yes: the tri-state ask
 * (V2SBridge.scheduledClickState) answers 'wait' while the pre-send check is pending, so the
 * script asks again every pollMs (re-checking the box each time, bounded by waitMs) and taps only
 * on 'yes' (once per job, current token only, never after Stop or a failure). On 'no' it clears
 * our text. Java may inject this again after a main-frame reload wiped the page; the generation
 * counter cancels an older run in the same document. Never logs the number or the text.
 */
function v2sScheduledCompose(phone, body, autoSendDelay, token) {
    'use strict';
    var opts = window._v2sScheduledOpts || {};
    window._v2sScheduledOpts = null;
    var threadMode = opts.mode === 'thread';
    var SETTLE_MS = opts.settleMs > 0 ? opts.settleMs : 15000;   // D35
    var CLICK_POLL_MS = opts.pollMs > 0 ? opts.pollMs : 500;
    var WAIT_MS = opts.waitMs > 0 ? opts.waitMs : 30000;
    var jobE164 = String(phone || '').replace(/[^\d+]/g, '');
    // As voice2sms() does: a newer run cancels an older one in this document. deliver H1: the
    // new-conversation flow is the scheduled one (_v2sScheduledNewMessage), never voice2sms(),
    // whose list match on text can open another person's conversation.
    window._v2sGeneration = (window._v2sGeneration || 0) + 1;
    var myGeneration = window._v2sGeneration;
    var POLL_MS = 250;
    var MAX_POLLS = 160;   // 40 s, the Java compose timeout
    var startedAt = Date.now();

    function cancelled() {
        return window._v2sGeneration !== myGeneration;
    }

    function composer() {
        return document.querySelector(V2S_COMPOSER_SELECTOR);
    }

    function holdsBody(el) {
        return _v2sScheduledHolds(el, body);
    }

    var wrongPageReported = false;

    /**
     * D32: the Java step for a draft in the box ('replace' | 'both' | 'stop'), or null when the
     * bridge has no such method. The draft goes to Java only; never logged here.
     */
    function draftStep(text) {
        if (typeof V2SBridge === 'undefined' || !V2SBridge.scheduledDraftStep) return null;
        return String(V2SBridge.scheduledDraftStep(_v2sBridgeNonce, token, String(text)));
    }

    /**
     * Mirrors ScheduledCompose.pageState: 'thread' | 'wait' | 'wrong' | 'fallback'. LIVE-F11: a
     * page that is not the job's thread (the messages list, or GV rewrote the URL while booting)
     * is 'wrong', never 'fallback': the script waits and Java loads the job's thread again.
     */
    function threadPageState() {
        var pageE164 = _v2sSlThreadE164();
        if (!pageE164 || pageE164 !== jobE164) return 'wrong';
        if (document.querySelector('gv-message-list')) return 'thread';
        return (Date.now() - startedAt) >= SETTLE_MS ? 'fallback' : 'wait';
    }

    function fillThread(polls) {
        if (cancelled() || polls <= 0) return;
        var state = threadPageState();
        if (state === 'wrong') {
            // LIVE-F11: once per run; Java reloads the thread (bounded) or decides the fallback.
            if (!wrongPageReported) {
                wrongPageReported = true;
                console.log('[Voice2SMS] Scheduled send: not the conversation page, asking for it again');
                if (typeof V2SBridge !== 'undefined' && V2SBridge.onScheduledWrongPage) V2SBridge.onScheduledWrongPage(_v2sBridgeNonce, token);
            }
            state = 'wait';
        }
        if (state === 'fallback') {
            console.log('[Voice2SMS] Scheduled send: no conversation shown, new-conversation flow next');
            if (typeof V2SBridge !== 'undefined' && V2SBridge.onScheduledNoThread) V2SBridge.onScheduledNoThread(_v2sBridgeNonce, token);
            return;
        }
        var ta = state === 'thread' ? composer() : null;
        if (!ta) {
            setTimeout(function() { fillThread(polls - 1); }, POLL_MS);
            return;
        }
        if (!holdsBody(ta)) {
            // OD34: never overwrite or delete a draft the user left in this thread's box: any
            // text that is not our body stops the job unsent (Java: draft_present, a reminder).
            // D32: Java decides what happens to it: 'stop' (the user gets the choice notification
            // with the draft), 'replace' (it is our text after all, or the user chose Replace and
            // send) or 'both' (the user chose Send both: Java keeps the draft and puts it back).
            var current = ta.isContentEditable ? ta.textContent : ta.value;
            if (typeof current === 'string' && current.trim().length > 0) {
                var step = draftStep(current);
                if (step !== 'replace' && step !== 'both') {
                    console.log('[Voice2SMS] Scheduled send: a draft is in the message box, not typing');
                    // An older bridge without the D32 step: the OD34 report (generic reminder).
                    if (step === null && typeof V2SBridge !== 'undefined' && V2SBridge.onScheduledDraftPresent) {
                        V2SBridge.onScheduledDraftPresent(_v2sBridgeNonce, token);
                    }
                    return;
                }
                console.log('[Voice2SMS] Scheduled send: replacing the draft in the message box');
            }
            _v2sScheduledSet(ta, body, true);
        }
        waitTyped(polls);
    }

    function waitTyped(polls) {
        if (cancelled() || polls <= 0) return;
        var box = composer();
        if (!holdsBody(box)) {
            // Thread mode: GV may re-render the box empty after we filled it; fill an EMPTY box again.
            if (threadMode && box && !(box.isContentEditable ? box.textContent : box.value)) {
                _v2sScheduledSet(box, body, true);
            }
            setTimeout(function() { waitTyped(polls - 1); }, POLL_MS);
            return;
        }
        console.log('[Voice2SMS] Scheduled send: message box filled');
        if (typeof V2SBridge !== 'undefined' && V2SBridge.onScheduledTyped) V2SBridge.onScheduledTyped(_v2sBridgeNonce, token);
        var jittered = Math.max(0, autoSendDelay + Math.floor(Math.random() * 801) - 300);
        setTimeout(function() { clickWhenGranted(10, 0); }, jittered);
    }

    /**
     * deliver H1: the D32 draft step of fillThread for the new-conversation flow: true when our
     * body may go into box ta. A box with text that is not our body asks Java ('stop': the user
     * gets the choice notification with the draft; 'replace': it is our text after all, or the
     * user chose Replace and send; 'both': the user chose Send both, Java keeps the draft and puts
     * it back). Never touches or logs the draft.
     */
    function draftGate(ta) {
        if (cancelled() || !ta) return false;
        if (holdsBody(ta)) return true;
        var current = ta.isContentEditable ? ta.textContent : ta.value;
        if (typeof current !== 'string' || current.trim().length === 0) return true;
        var step = draftStep(current);
        if (step !== 'replace' && step !== 'both') {
            console.log('[Voice2SMS] Scheduled send: a draft is in the message box, not typing');
            // An older bridge without the D32 step: the OD34 report (generic reminder).
            if (step === null && typeof V2SBridge !== 'undefined' && V2SBridge.onScheduledDraftPresent) {
                V2SBridge.onScheduledDraftPresent(_v2sBridgeNonce, token);
            }
            return false;
        }
        console.log('[Voice2SMS] Scheduled send: replacing the draft in the message box');
        return true;
    }

    function expired() {
        return (Date.now() - startedAt) >= MAX_POLLS * POLL_MS;
    }

    /** The draft step, then our body into the box (Angular-safe), then the typed check. */
    function fillNew() {
        if (cancelled() || expired()) return;
        var ta = composer();
        if (!ta) {
            setTimeout(fillNew, 100);
            return;
        }
        if (!holdsBody(ta)) {
            if (!draftGate(ta)) return;
            _v2sScheduledSet(ta, body, true);
        }
        waitTyped(MAX_POLLS);
    }

    /**
     * deliver H1/L2: the recipient chips of a new-message view, for the Java gate: their count and
     * the single chip's digit runs (text, aria-label, title; digits only, never a name).
     */
    function recipientChips() {
        var input = document.querySelector(V2S_SCHEDULED_RECIPIENT_SELECTOR);
        var scope = (input && input.closest && input.closest('mat-chip-grid, mat-chip-list, mat-form-field')) || document;
        var chips = scope.querySelectorAll(V2S_SCHEDULED_CHIP_SELECTOR);
        var digits = '';
        if (chips.length === 1) {
            var c = chips[0];
            digits = [c.textContent || '', c.getAttribute('aria-label') || '', c.getAttribute('title') || '']
                .map(function(v) { return String(v).replace(/\D/g, ''); }).join(',');
        }
        return { count: chips.length, digits: digits };
    }

    /** The Java Send gate: 'yes' | 'wait' | 'no'. */
    function askClick() {
        if (typeof V2SBridge === 'undefined') return 'no';
        // deliver H1/L2: with this page's location and recipient chips; Java says 'no' unless
        // this is the job's own thread or a new message addressed to the job's number alone.
        if (V2SBridge.scheduledClickStateIn) {
            var chips = recipientChips();
            return String(V2SBridge.scheduledClickStateIn(_v2sBridgeNonce, token, String(location.href), chips.count, chips.digits));
        }
        // LIVE-F10 F1: with this page's location, so a clearance given on an empty (new)
        // conversation is dropped once GV shows the number's thread here.
        if (V2SBridge.scheduledClickStateAt) return String(V2SBridge.scheduledClickStateAt(_v2sBridgeNonce, token, String(location.href)));
        if (V2SBridge.scheduledClickState) return String(V2SBridge.scheduledClickState(_v2sBridgeNonce, token));
        return 'no';
    }

    function clickWhenGranted(retries, waitUntil) {
        if (cancelled()) return;
        // Before every ask: the box must still hold exactly our text: never send anything else.
        if (!holdsBody(composer())) {
            console.log('[Voice2SMS] Scheduled send: message box changed, not sending');
            return;
        }
        var sendBtn = document.querySelector('button[aria-label*="Send"]');
        if (!sendBtn || sendBtn.disabled || sendBtn.getAttribute('aria-disabled') === 'true') {
            if (retries <= 0) return;
            setTimeout(function() { clickWhenGranted(retries - 1, waitUntil); }, 500);
            return;
        }
        var state = askClick();
        if (state === 'wait') {
            // The pre-send check is still reading the thread: ask again shortly.
            if (!waitUntil) waitUntil = Date.now() + WAIT_MS;
            if (Date.now() < waitUntil) {
                setTimeout(function() { clickWhenGranted(retries, waitUntil); }, CLICK_POLL_MS);
            } else {
                console.log('[Voice2SMS] Scheduled send: stopped waiting for the check');
            }
            return;
        }
        if (state !== 'yes') {
            console.log('[Voice2SMS] Scheduled send: Send not granted');
            v2sScheduledClear(body);
            return;
        }
        console.log('[Voice2SMS] Scheduled send: clicking send');
        sendBtn.click();
    }

    if (threadMode) fillThread(MAX_POLLS);
    else _v2sScheduledNewMessage(jobE164, function() { return cancelled() || expired(); }, fillNew);
}

var V2S_SCHEDULED_RECIPIENT_SELECTOR = 'input[placeholder="Type a name or phone number"], ' +
    'input[placeholder*="name or phone"], ' +
    'input[placeholder*="number"], ' +
    'input[aria-label*="To"], ' +
    'input[aria-label*="recipient"]';
var V2S_SCHEDULED_CHIP_SELECTOR = 'mat-chip-row, .mdc-evolution-chip';

/**
 * deliver H1, scheduled mode 'new' only: the list item whose OWN thread id (itemId t.+E.164) is
 * e164, else null. Never a text match: a preview or a name holding the digits is someone else's.
 */
function _v2sScheduledOwnThreadItem(e164) {
    var items = document.querySelectorAll(
        'ol[gv-test-id="list"] li.list-item, gv-message-thread-list-item, gv-thread-list-item');
    for (var i = 0; i < items.length; i++) {
        if (_v2sSlItemE164(items[i]) === e164) {
            return items[i].querySelector('div[role="button"]') || items[i];
        }
    }
    return null;
}

/**
 * deliver H1, scheduled mode 'new' only: on the messages list, opens e164's conversation only by
 * its own thread id, else starts a new message with e164 as its recipient chip (voice2sms()'s
 * selectors and direct matChipInputTokenEnd call); then calls ready() (the draft step and our
 * body). stop() true ends it. No chip: nothing typed (fail closed, Java's compose timeout). Types
 * nothing into the message box and never taps Send; voice2sms() itself is unchanged for every
 * regular send. Never logs the number.
 */
function _v2sScheduledNewMessage(e164, stop, ready) {
    var POLL_MS = 250;

    function dismissBackdrop() {
        var backdrop = document.querySelector('.cdk-overlay-backdrop.cdk-overlay-backdrop-showing');
        if (backdrop) backdrop.click();
    }

    function open() {
        if (stop()) return;
        var rendered = document.querySelector('gv-side-nav, gv-thread-list, [gv-test-id="sidenav"]');
        var fab = document.querySelector('button[aria-label*="new"]');
        if (!rendered || !fab) {
            setTimeout(open, POLL_MS);
            return;
        }
        dismissBackdrop();
        var own = _v2sScheduledOwnThreadItem(e164);
        if (own) {
            console.log('[Voice2SMS] Scheduled send: opening the conversation by its thread id');
            own.click();
            setTimeout(ready, 100);
            return;
        }
        var btn = document.querySelector('button[aria-label*="new"]')
            || document.querySelector('[aria-label="Send new message"]')
            || document.querySelector('button[aria-label*="compose"]');
        if (!btn) {
            setTimeout(open, POLL_MS);
            return;
        }
        btn.click();
        setTimeout(addRecipient, 500);
    }

    function addRecipient() {
        if (stop()) return;
        var inp = document.querySelector(V2S_SCHEDULED_RECIPIENT_SELECTOR);
        if (!inp) {
            setTimeout(addRecipient, 100);
            return;
        }
        var before = document.querySelectorAll(V2S_SCHEDULED_CHIP_SELECTOR).length;
        if (!makeChip(inp)) {
            console.log('[Voice2SMS] Scheduled send: could not add the recipient, not typing');
            return;
        }
        waitChip(before, 15);
    }

    function makeChip(inp) {
        var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
        function setValue(v) {
            if (setter && setter.set) setter.set.call(inp, v);
            else inp.value = v;
        }
        var listeners = inp['__zone_symbol__matChipInputTokenEndfalse'];
        var task = listeners && listeners.length ? listeners[0] : null;
        if (!task || typeof task.callback !== 'function') return false;
        setValue(e164);
        inp.focus();
        var zone = _v2sGetZoneFromTask(task);
        if (!zone || zone.name === '<root>') {
            var elZone = _v2sGetAngularZone(inp);
            if (elZone && elZone.name !== '<root>') zone = elZone;
        }
        if (!zone || zone.name === '<root>') zone = _v2sFindAngularZoneGlobal() || zone;
        var ev = {
            value: e164,
            chipInput: { inputElement: inp, clear: function() { setValue(''); }, focused: true },
            preventDefault: function() {}
        };
        if (zone) zone.run(function() { task.callback(ev); });
        else task.callback(ev);
        setValue('');
        inp.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'deleteContent' }));
        return true;
    }

    function waitChip(before, tries) {
        if (stop()) return;
        if (document.querySelectorAll(V2S_SCHEDULED_CHIP_SELECTOR).length > before) {
            dismissBackdrop();
            ready();
            return;
        }
        if (tries <= 0) {
            console.log('[Voice2SMS] Scheduled send: no recipient chip, not typing');
            return;
        }
        setTimeout(function() { waitChip(before, tries - 1); }, 50);
    }

    open();
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

// ── Enter inserts a newline in the composer (R44, D29) ────────────────────
//
// GV web sends the message when Enter is pressed in the message box. In the
// voice2sms WebView, Enter (hardware key, soft-keyboard Enter, Shift+Enter)
// inserts a newline instead; only the Send button sends. Installed once per page
// load by GVoiceWebViewActivity (onPageFinished), independent of Send Later and of
// voice2sms(). Clicks are never intercepted, so the Send button and voice2sms's
// own clickSend() auto-send are unchanged. IME composition (isComposing, keyCode
// 229) is left alone. Never logs the composer text.

var V2S_COMPOSER_SELECTOR =
    'textarea[placeholder="Type a message"], ' +
    'textarea[placeholder*="message"], ' +
    'textarea[placeholder*="text"], ' +
    'textarea[aria-label*="message"], ' +
    '[contenteditable="true"][aria-label*="message"]';

/** True when el is the GV message composer (the selectors fillBody uses). */
function _v2sIsComposer(el) {
    if (!el || el.nodeType !== 1 || typeof el.matches !== 'function') return false;
    try {
        return el.matches(V2S_COMPOSER_SELECTOR);
    } catch (e) {
        return false;
    }
}

/** True for an Enter key event that is not part of an IME composition. */
function _v2sIsPlainEnter(e) {
    if (e.isComposing || e.keyCode === 229) return false;
    return e.key === 'Enter' || e.keyCode === 13;
}

/**
 * Insert a newline at the caret of the composer through the Angular-safe setter:
 * native value setter with "\n" replacing selectionStart..selectionEnd, caret after
 * it, then an input event dispatched in the Angular zone.
 */
function _v2sInsertNewline(el) {
    if (el.tagName !== 'TEXTAREA') {
        // contenteditable fallback: insertText fires beforeinput 'insertText', which
        // the beforeinput handler leaves alone.
        try { document.execCommand('insertText', false, '\n'); } catch (e) { /* ignore */ }
        return;
    }
    var value = typeof el.value === 'string' ? el.value : '';
    var start = typeof el.selectionStart === 'number' ? el.selectionStart : value.length;
    var end = typeof el.selectionEnd === 'number' ? el.selectionEnd : start;
    if (end < start) { var t = start; start = end; end = t; }
    var next = value.slice(0, start) + '\n' + value.slice(end);
    var caret = start + 1;

    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value');
    if (nativeSetter && nativeSetter.set) {
        nativeSetter.set.call(el, next);
    } else {
        el.value = next;
    }
    try { el.setSelectionRange(caret, caret); } catch (e) { /* ignore */ }

    var inputEvent = new InputEvent('input', {
        bubbles: true, inputType: 'insertLineBreak', data: null
    });
    var angularZone = _v2sGetAngularZone(el);
    if (angularZone) {
        angularZone.run(function() { el.dispatchEvent(inputEvent); });
    } else {
        el.dispatchEvent(inputEvent);
    }
    // Angular may write the model back into the box; keep the caret after the newline.
    try {
        if (el.value === next) el.setSelectionRange(caret, caret);
    } catch (e) { /* ignore */ }
}

/**
 * Install the Enter-inserts-newline listeners once per document (R44). Capture phase on
 * document, so GV's own Enter handlers on the textarea never see the key.
 */
function v2sInstallEnterNewline() {
    if (window._v2sEnterNewline) return;
    window._v2sEnterNewline = true;

    document.addEventListener('keydown', function(e) {
        if (!_v2sIsComposer(e.target) || !_v2sIsPlainEnter(e)) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        _v2sInsertNewline(e.target);
    }, true);

    // keypress / keyup Enter on the composer: swallow only (the newline was inserted on
    // keydown) so a GV handler bound to either cannot send.
    var swallow = function(e) {
        if (!_v2sIsComposer(e.target) || !_v2sIsPlainEnter(e)) return;
        e.preventDefault();
        e.stopImmediatePropagation();
    };
    document.addEventListener('keypress', swallow, true);
    document.addEventListener('keyup', swallow, true);

    // Soft keyboard Enter that arrives as an input edit rather than a key event.
    document.addEventListener('beforeinput', function(e) {
        if (!_v2sIsComposer(e.target)) return;
        if (e.isComposing) return;
        if (e.inputType !== 'insertLineBreak' && e.inputType !== 'insertParagraph') return;
        e.preventDefault();
        e.stopImmediatePropagation();
        _v2sInsertNewline(e.target);
    }, true);
}

// ── Send Later module (R10, R11, R17, R18, R19) ───────────────────────────
//
// Independent of voice2sms(): installed once per page load by v2sSendLaterInit(),
// which GVoiceWebViewActivity calls from onPageFinished. Shows a light-blue edge
// tab on a 1:1 thread with a non-empty composer, a chip above the compose row
// when the thread has pending/held messages, and the "Scheduled for ... · Undo"
// bar after the native sheet scheduled the draft. Never logs drafts, names or
// numbers (console lines with [Voice2SMS] reach logcat).
//
// Thread URL shape is UNVERIFIED on device (F7): only itemId=t.+<7-15 digits> is
// a 1:1 thread; anything else (t.g.* groups, no itemId) shows no tab and no chip.

var V2S_SL_ACCENT = '#8AB4F8';      // shared Send Later accent (A4 / OD9)
var V2S_SL_ON_ACCENT = '#0B1D3A';   // text/icon colour on the accent
var V2S_SL_Z = 2147483000;
// R40: no fast polling. Ticks come from input, resize and Java's v2sSendLaterRefresh
// (SPA navigation, state changes, resume); this timer runs only while Send Later is on
// and a 1:1 thread is open (it keeps the tab/chip placed and the chip fresh).
var V2S_SL_TICK_MS = 2000;
/** Page events (input, resize) coalesce into one trailing tick this long after the first. */
var V2S_SL_COALESCE_MS = 250;

function v2sSendLaterInit() {
    if (typeof V2SBridge === 'undefined') return;
    if (window._v2sSl && window._v2sSl.installed) return;
    window._v2sSl = {
        installed: true,
        e164: null,          // 1:1 thread on screen, or null
        enabled: false,      // Send Later on for this page (chip state, or switchOn off-thread)
        switchOn: true,      // Java installs the module only while on; v2sSendLaterRefresh(on) updates it
        chip: null,          // last V2SBridge.sendLaterChipState answer
        chipAt: 0,
        capture: null,       // {e164, href} of the last tab tap
        captureBusyUntil: 0, // a tab tap is in flight until then (UI M2: no second sheet)
        undo: null,          // {messageId, draft, href, e164, until}
        barTimer: null,
        title: null,         // {e164, title, at, confirmed} header pairing for R4
        prevTitle: '',       // header text of the previous thread (stale-header guard)
        pairs: {},           // e164 -> confirmed header title
        directorySig: '',
        timer: null,         // V2S_SL_TICK_MS interval, only while on and a 1:1 thread is open
        followUps: [],       // bounded one-shot ticks after a forced tick (thread list renders late)
        tickSoon: null       // pending coalesced tick of page events (_v2sSlTickSoon)
    };
    document.addEventListener('input', _v2sSlTickSoon, true);
    window.addEventListener('resize', _v2sSlTickSoon);
    _v2sSlTick(true);
}

/**
 * Java -> page on ACTION_STATE_CHANGED, SPA navigation and resume: re-read the chip for
 * the open thread (R17). on = the Send Later switch, pushed by Java: the bridge answers the
 * chip state only for the open 1:1 thread (L3), so with no thread open this is how the page
 * learns it is on or off. A refresh also ends a tab tap in flight (the sheet has closed).
 */
window.v2sSendLaterRefresh = function(on) {
    var st = window._v2sSl;
    if (!st) return;
    if (typeof on === 'boolean') st.switchOn = on;
    st.captureBusyUntil = 0;
    _v2sSlTick(true);
};

/**
 * Java -> page after the sheet persisted the message (R11, R7 for the WebView):
 * clears the composer only when it still holds expectedDraft on the same thread URL,
 * then offers Undo for 10 s. Otherwise leaves the draft untouched.
 */
window.v2sSendLaterClear = function(messageId, expectedDraft, scheduledLabel) {
    var st = window._v2sSl;
    if (st) st.captureBusyUntil = 0;
    var ta = _v2sSlComposer();
    var e164 = _v2sSlThreadE164();
    var sameThread = !!(st && st.capture && e164 && e164 === st.capture.e164
        && location.href === st.capture.href);
    if (!ta || !sameThread || typeof expectedDraft !== 'string' || ta.value !== expectedDraft) {
        _v2sSlShowBar('Saved to Upcoming — draft left as is', null);
        return false;
    }
    _v2sSlSetInputValue(ta, '', false);
    _v2sSlShowBar('Scheduled for ' + (scheduledLabel || 'later'), {
        messageId: messageId,
        draft: expectedDraft,
        href: location.href,
        e164: e164,
        until: Date.now() + 10000
    });
    _v2sSlRefreshChip(st);
    return true;
};

/**
 * Java -> page (send path, watermarks): reports the open thread's newest bubbles as
 * {e164, bubbles: [{direction, timeMs|null, text, label|null, id|null, failed}] | null}.
 * bubbles is null when the page is not showing that 1:1 thread or the bubbles cannot be read
 * reliably; [] when the thread is shown and empty.
 */
window.v2sReadThread = function(e164, limit) {
    var bubbles = null;
    try {
        if (e164 && _v2sSlThreadE164() === e164) bubbles = _v2sSlReadBubbles(limit);
    } catch (e) {
        bubbles = null;
    }
    V2SBridge.onThreadRead(_v2sBridgeNonce, JSON.stringify({ e164: e164, bubbles: bubbles }));
};

// ── Send Later helpers ────────────────────────────────────────────────────

/** E.164 of a GV item id when it is a 1:1 thread id ("t.+<1-9><digits>", 7-15 digits), else null. */
function _v2sSlE164FromItemId(id) {
    if (typeof id !== 'string' || !id) return null;
    // A literal '+' in the query decodes to a space; read it back as the E.164 plus.
    id = id.replace(/^t\. /, 't.+');
    if (!/^t\.\+\d{7,15}$/.test(id)) return null;
    // D38: the one E.164 rule (PhoneNumbers.isE164): no country code starts with 0.
    if (id.charAt(3) === '0') return null;
    return id.substring(2);
}

/** E.164 of the open 1:1 thread from location (itemId via URLSearchParams), else null. */
function _v2sSlThreadE164() {
    var id = null;
    try {
        id = new URLSearchParams(location.search).get('itemId');
    } catch (e) {
        return null;
    }
    return _v2sSlE164FromItemId(id);
}

function _v2sSlComposer() {
    return document.querySelector(
        'textarea[placeholder="Type a message"], ' +
        'textarea[placeholder*="message"], ' +
        'textarea[aria-label*="message"]'
    );
}

function _v2sSlHasDraft(ta) {
    return !!ta && typeof ta.value === 'string' && ta.value.trim().length > 0;
}

/**
 * Angular-safe setter for the Send Later module (copy of voice2sms()'s setInputValue,
 * which is private to that function): native value setter + InputEvent in the zone.
 */
function _v2sSlSetInputValue(el, value, focus) {
    if (focus) el.focus();
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
        bubbles: true,
        inputType: value ? 'insertText' : 'deleteContentBackward',
        data: value || null
    });
    if (angularZone) {
        angularZone.run(function() { el.dispatchEvent(inputEvent); });
    } else {
        el.dispatchEvent(inputEvent);
    }
}

/**
 * One pass: thread change, chip freshness, R4 pairing/scrape, layout. While Send Later
 * is off only a forced tick (from Java) runs; page events then cost nothing.
 */
function _v2sSlTick(force) {
    var st = window._v2sSl;
    if (!st) return;
    if (!force && (!st.enabled || document.hidden)) return;
    var e164 = _v2sSlThreadE164();
    if (e164 !== st.e164) {
        st.prevTitle = st.title ? st.title.title : '';
        st.e164 = e164;
        st.title = null;
        _v2sSlRefreshChip(st);
    } else if (force || Date.now() - st.chipAt > 15000) {
        _v2sSlRefreshChip(st);
    }
    if (st.enabled) {
        // Pairing and the thread-list scrape only while Send Later is on (R4): no contact
        // data is collected for a feature that is switched off.
        _v2sSlNoteThreadTitle(st);
        _v2sSlScrapeDirectory(st);
    }
    _v2sSlLayout(st);
    if (force) _v2sSlFollowUp(st);
}

/**
 * Page events (typing, resize): one trailing tick per V2S_SL_COALESCE_MS burst instead of a
 * full tick per keystroke. Nothing is scheduled while Send Later is off or the page is hidden
 * (the tick would return at once). Forced ticks and the interval are unaffected.
 */
function _v2sSlTickSoon() {
    var st = window._v2sSl;
    if (!st || st.tickSoon || !st.enabled || document.hidden) return;
    st.tickSoon = setTimeout(function() {
        st.tickSoon = null;
        _v2sSlTick(false);
    }, V2S_SL_COALESCE_MS);
}

/**
 * After a forced tick (page load, SPA navigation, state change) the GV thread list may
 * still be rendering: two one-shot ticks catch it for the R4 scrape. Bounded, not a poll;
 * only while on and no 1:1 thread is open (the timer covers that case).
 */
function _v2sSlFollowUp(st) {
    for (var i = 0; i < st.followUps.length; i++) clearTimeout(st.followUps[i]);
    st.followUps = [];
    if (!st.enabled || st.e164) return;
    var delays = [V2S_SL_TICK_MS, 3 * V2S_SL_TICK_MS];
    for (var j = 0; j < delays.length; j++) {
        st.followUps.push(setTimeout(function() { _v2sSlTick(false); }, delays[j]));
    }
}

/** Re-reads V2SBridge.sendLaterChipState for the open thread (R17). */
function _v2sSlRefreshChip(st) {
    if (!st) return;
    st.chipAt = Date.now();
    var state = null;
    if (st.e164) {
        try {
            // The bridge answers only for the 1:1 thread the main frame shows (L3).
            state = JSON.parse(V2SBridge.sendLaterChipState(_v2sBridgeNonce, st.e164));
        } catch (e) {
            state = null;
        }
        st.chip = state;
        st.enabled = !!(state && state.enabled === true);
    } else {
        // No thread open: the switch as Java last pushed it (v2sSendLaterRefresh(on)).
        st.chip = null;
        st.enabled = st.switchOn === true;
    }
    if (!st.enabled) {
        st.title = null;
        st.pairs = {};
        st.directorySig = '';
    }
    _v2sSlSchedule(st);
    _v2sSlLayout(st);
}

/** Runs the V2S_SL_TICK_MS timer only while Send Later is on and a 1:1 thread is open (R40). */
function _v2sSlSchedule(st) {
    var want = !!(st && st.enabled && st.e164);
    if (want && !st.timer) {
        st.timer = setInterval(function() { _v2sSlTick(false); }, V2S_SL_TICK_MS);
    } else if (!want && st.timer) {
        clearInterval(st.timer);
        st.timer = null;
    }
}

/** Outermost ancestor (up to 5 levels) of the composer that still looks like one row. */
function _v2sSlComposeRow(ta) {
    var row = ta;
    var el = ta.parentElement;
    for (var i = 0; el && i < 5; i++, el = el.parentElement) {
        var r = el.getBoundingClientRect();
        if (r.height <= 0 || r.height > 160) break;
        row = el;
    }
    return row;
}

function _v2sSlSendButton(row) {
    var scope = row && row.parentElement ? row.parentElement : document;
    return scope.querySelector('button[aria-label*="Send"]');
}

function _v2sSlOverlaps(a, b) {
    return !!a && !!b && a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
}

/** Positions (or hides) the edge tab, the chip and the bar around the compose row (R19). */
function _v2sSlLayout(st) {
    var ta = st && st.e164 ? _v2sSlComposer() : null;
    var rect = null;
    if (ta) {
        var rr = _v2sSlComposeRow(ta).getBoundingClientRect();
        if (rr.width > 0 && rr.height > 0) rect = rr;
    }
    var tab = document.getElementById('v2s-sl-tab');
    var chip = document.getElementById('v2s-sl-chip');
    var bar = document.getElementById('v2s-sl-bar');
    var barShown = !!(bar && bar.style.display !== 'none');
    var showTab = !!(rect && st.enabled && _v2sSlHasDraft(ta));
    var label = st && st.chip && st.enabled && typeof st.chip.label === 'string' ? st.chip.label : '';
    var showChip = !!(rect && label && !barShown);

    if (showTab) {
        tab = tab || _v2sSlCreateTab();
        var h = 48;
        var w = 22;
        var top = rect.top + rect.height / 2 - h / 2;
        var tabRect = { left: window.innerWidth - w, right: window.innerWidth, top: top, bottom: top + h };
        var send = _v2sSlSendButton(_v2sSlComposeRow(ta));
        if (send && _v2sSlOverlaps(tabRect, send.getBoundingClientRect())) {
            top = rect.top - h - 8;   // never cover Send: sit just above the row instead
        }
        tab.style.top = Math.max(0, Math.round(top)) + 'px';
        tab.style.display = 'flex';
    } else if (tab) {
        tab.style.display = 'none';
    }

    if (showChip) {
        chip = chip || _v2sSlCreateChip();
        chip.textContent = label;
        chip.style.left = Math.max(8, Math.round(rect.left + 8)) + 'px';
        chip.style.bottom = Math.max(0, Math.round(window.innerHeight - rect.top + 6)) + 'px';
        chip.style.display = 'block';
    } else if (chip) {
        chip.style.display = 'none';
    }

    if (barShown && rect) {
        bar.style.bottom = Math.max(0, Math.round(window.innerHeight - rect.top + 6)) + 'px';
    }
}

function _v2sSlCreateTab() {
    var tab = document.createElement('div');
    tab.id = 'v2s-sl-tab';
    tab.setAttribute('role', 'button');
    tab.setAttribute('aria-label', 'Send later');
    tab.style.cssText = 'position:fixed;right:0;width:22px;height:48px;display:none;' +
        'align-items:center;justify-content:center;border-radius:12px 0 0 12px;' +
        'background:' + V2S_SL_ACCENT + ';color:' + V2S_SL_ON_ACCENT + ';' +
        'box-shadow:0 1px 4px rgba(0,0,0,.4);z-index:' + V2S_SL_Z + ';cursor:pointer;';
    tab.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">' +
        '<circle cx="12" cy="12" r="9" fill="none" stroke="' + V2S_SL_ON_ACCENT + '" stroke-width="2.2"/>' +
        '<path d="M12 7v5.2l3.4 2" fill="none" stroke="' + V2S_SL_ON_ACCENT +
        '" stroke-width="2.2" stroke-linecap="round"/></svg>';
    // Keep the textarea focused (and the IME up) while the tab is pressed.
    tab.addEventListener('mousedown', function(e) { e.preventDefault(); });
    tab.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        _v2sSlCapture();
    });
    document.body.appendChild(tab);
    return tab;
}

function _v2sSlCreateChip() {
    var chip = document.createElement('div');
    chip.id = 'v2s-sl-chip';
    chip.setAttribute('role', 'button');
    chip.style.cssText = 'position:fixed;display:none;max-width:70vw;padding:4px 12px;' +
        'border-radius:14px;font:500 13px/20px Roboto,Arial,sans-serif;white-space:nowrap;' +
        'overflow:hidden;text-overflow:ellipsis;background:' + V2S_SL_ACCENT + ';' +
        'color:' + V2S_SL_ON_ACCENT + ';box-shadow:0 1px 3px rgba(0,0,0,.35);' +
        'z-index:' + V2S_SL_Z + ';cursor:pointer;';
    chip.addEventListener('mousedown', function(e) { e.preventDefault(); });
    chip.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        _v2sSlChipTap();
    });
    document.body.appendChild(chip);
    return chip;
}

/** Chip tap (R18): Java opens edit mode for one message, Upcoming for several. */
function _v2sSlChipTap() {
    var st = window._v2sSl;
    if (!st || !st.e164 || st.e164 !== _v2sSlThreadE164()) return;
    V2SBridge.requestSendLaterChipTap(_v2sBridgeNonce, st.e164);
}

/** Edge-tab tap (R10): hand the draft and the newest-message watermark to the sheet. */
function _v2sSlCapture() {
    var st = window._v2sSl;
    if (!st || !st.enabled) return;
    var e164 = _v2sSlThreadE164();
    var ta = _v2sSlComposer();
    if (!e164 || e164 !== st.e164 || !_v2sSlHasDraft(ta)) return;
    // UI M2: a second tap while the first capture is in flight opens no second sheet.
    if (st.captureBusyUntil && Date.now() < st.captureBusyUntil) return;
    st.captureBusyUntil = Date.now() + 1500;
    var bubbles = null;
    try {
        bubbles = _v2sSlReadBubbles(20);
    } catch (e) {
        bubbles = null;
    }
    var newest = bubbles && bubbles.length ? bubbles[bubbles.length - 1] : null;
    var newestId = newest && newest.id ? newest.id : null;
    var newestTime = newest && typeof newest.timeMs === 'number' ? newest.timeMs : null;
    // LIVE-F9: GV web has no bubble time; Java parses the newest bubble's label instead.
    var newestLabel = newest && !newestTime && newest.label ? newest.label : null;
    var quality = (newestId && newestTime) ? 'EXACT' : (newestTime ? 'TIMESTAMP' : 'DEVICE_ONLY');
    st.capture = { e164: e164, href: location.href };
    V2SBridge.requestSendLater(_v2sBridgeNonce, JSON.stringify({
        e164: e164,
        displayName: _v2sSlConfirmedName(st, e164),
        draftText: ta.value,
        newestMessageId: newestId,
        newestMessageTimeMs: newestTime,
        newestMessageLabel: newestLabel,
        watermarkQuality: quality
    }));
}

/** In-page confirmation bar; with undo set it offers Undo for 10 s (R11). */
function _v2sSlShowBar(text, undo) {
    var st = window._v2sSl;
    var bar = document.getElementById('v2s-sl-bar');
    if (!bar) {
        bar = document.createElement('div');
        bar.id = 'v2s-sl-bar';
        bar.style.cssText = 'position:fixed;left:8px;right:8px;bottom:96px;display:none;' +
            'align-items:center;gap:12px;padding:10px 14px;border-radius:10px;' +
            'font:14px/20px Roboto,Arial,sans-serif;background:#1E2A3A;color:#E8EAED;' +
            'box-shadow:0 2px 8px rgba(0,0,0,.5);z-index:' + V2S_SL_Z + ';';
        document.body.appendChild(bar);
    }
    while (bar.firstChild) bar.removeChild(bar.firstChild);
    var msg = document.createElement('span');
    msg.style.flex = '1';
    msg.textContent = text;
    bar.appendChild(msg);
    if (st) st.undo = undo;
    if (undo) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.textContent = 'Undo';
        btn.style.cssText = 'border:0;background:transparent;font:500 14px Roboto,Arial,sans-serif;' +
            'color:' + V2S_SL_ACCENT + ';padding:4px 8px;cursor:pointer;';
        btn.addEventListener('mousedown', function(e) { e.preventDefault(); });
        btn.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            _v2sSlOnUndoTap();
        });
        bar.appendChild(btn);
    }
    bar.style.display = 'flex';
    if (st && st.barTimer) clearTimeout(st.barTimer);
    var timer = setTimeout(function() { _v2sSlHideBar(); }, 10000);
    if (st) {
        st.barTimer = timer;
        _v2sSlLayout(st);
    }
}

function _v2sSlHideBar() {
    var st = window._v2sSl;
    var bar = document.getElementById('v2s-sl-bar');
    if (bar) bar.style.display = 'none';
    if (st) {
        st.undo = null;
        if (st.barTimer) clearTimeout(st.barTimer);
        st.barTimer = null;
        _v2sSlLayout(st);
    }
}

/**
 * Undo (R11, I4, OD31 PQ1), like the native Undo: always cancels the message, then
 * restores the draft only while the composer is still empty on the same thread URL.
 * Otherwise the text is left alone and stays readable in Upcoming under Recent.
 */
function _v2sSlOnUndoTap() {
    var st = window._v2sSl;
    var u = st ? st.undo : null;
    _v2sSlHideBar();
    if (!u || Date.now() > u.until) return;
    if (u.messageId) {
        var undone = V2SBridge.requestSendLaterUndo(_v2sBridgeNonce, u.messageId);
        if (!(undone === true || undone === 'true')) {
            _v2sSlShowBar('Couldn’t undo — the message is no longer scheduled', null);
            _v2sSlRefreshChip(st);
            return;
        }
        var ta = _v2sSlComposer();
        if (ta && ta.value === '' && location.href === u.href && _v2sSlThreadE164() === u.e164) {
            _v2sSlSetInputValue(ta, u.draft, true);
            _v2sSlShowBar('Canceled — draft restored', null);
        } else {
            _v2sSlShowBar('Canceled — the composer changed, so your text is in Upcoming under Recent', null);
        }
        _v2sSlRefreshChip(st);
    }
}

/** Header title of the open thread, or '' (best effort; selectors unverified). */
function _v2sSlThreadTitle() {
    var el = document.querySelector(
        '[gv-test-id="conversation-title"], [gv-test-id="thread-title"], ' +
        'gv-thread-details-header .title, gv-message-thread-header .title'
    );
    var t = el ? (el.textContent || '').trim() : '';
    return t.length > 0 && t.length <= 80 ? t : '';
}

/**
 * R4 pairing from the open thread: the header title counts for this number only after it
 * has been stable for 1.5 s on the same thread and differs from the previous thread's
 * header (a stale header during navigation must never pair a name with the wrong number).
 */
function _v2sSlNoteThreadTitle(st) {
    var t = st.e164 ? _v2sSlThreadTitle() : '';
    if (!t) {
        st.title = null;
        return;
    }
    if (!st.title || st.title.e164 !== st.e164 || st.title.title !== t) {
        st.title = { e164: st.e164, title: t, at: Date.now(), confirmed: false };
        return;
    }
    if (!st.title.confirmed && Date.now() - st.title.at >= 1500 && t !== st.prevTitle) {
        st.title.confirmed = true;
        st.pairs[st.e164] = t;
    }
}

/**
 * Display name for the capture payload: only a pairing that passed the R4 check (stable
 * header, different from the previous thread's), never the raw header, else null.
 */
function _v2sSlConfirmedName(st, e164) {
    if (st.title && st.title.confirmed && st.title.e164 === e164 && st.title.title) {
        return st.title.title;
    }
    if (Object.prototype.hasOwnProperty.call(st.pairs, e164) && st.pairs[e164]) {
        return st.pairs[e164];
    }
    return null;
}

function _v2sSlHasLetter(s) {
    return /[A-Za-zÀ-￿]/.test(s) && s.indexOf(',') < 0;
}

/** Thread-list scrape (R4): {displayName, e164} pairs -> V2SBridge.onThreadDirectory. */
function _v2sSlScrapeDirectory(st) {
    if (!st || !st.enabled) return;
    var out = [];
    var seen = {};
    var items = document.querySelectorAll(
        'ol[gv-test-id="list"] li.list-item, gv-message-thread-list-item, gv-thread-list-item'
    );
    for (var i = 0; i < items.length && out.length < 300; i++) {
        var e164 = _v2sSlItemE164(items[i]);
        var nameEl = e164 ? items[i].querySelector(
            '[gv-test-id="item-contact"], .contact-name, .participants') : null;
        var name = nameEl ? (nameEl.textContent || '').trim() : '';
        if (!e164 || !name || name.length > 80 || !_v2sSlHasLetter(name) || seen[e164]) continue;
        seen[e164] = true;
        out.push({ displayName: name, e164: e164 });
    }
    for (var k in st.pairs) {
        if (!Object.prototype.hasOwnProperty.call(st.pairs, k) || seen[k]) continue;
        if (!_v2sSlHasLetter(st.pairs[k])) continue;
        seen[k] = true;
        out.push({ displayName: st.pairs[k], e164: k });
    }
    if (!out.length) return;
    var sig = JSON.stringify(out);
    if (sig === st.directorySig) return;
    st.directorySig = sig;
    V2SBridge.onThreadDirectory(_v2sBridgeNonce, sig);
}

/** 1:1 E.164 of a thread-list item from an itemId link inside it, else null. */
function _v2sSlItemE164(item) {
    var a = item.querySelector('a[href*="itemId="]');
    var href = a ? a.getAttribute('href') : null;
    if (!href) return null;
    try {
        return _v2sSlE164FromItemId(new URL(href, location.href).searchParams.get('itemId'));
    } catch (e) {
        return null;
    }
}

/**
 * Newest bubbles of the open thread, oldest first:
 * [{direction, timeMs|null, text, label|null, id|null, failed}] (LIVE-F9).
 *
 * Real GV web DOM (S22, 2026-11-05): gv-message-list > ... > li > gv-message-item >
 * div.container > div.full-container.incoming|.outgoing > [div.cdk-visually-hidden (the
 * screen-reader label, which carries the time: Java parses it) and div.message-row >
 * div.subject-content-container.bubble > div > gv-annotation.content (the text)]. There is no
 * time element, timestamp or id, and the direction sits on a DESCENDANT of gv-message-item.
 * An item whose direction is unclear is skipped, never guessed. Returns [] when the thread view
 * is rendered with no items, null when it is not rendered or no item could be read. Never logs
 * the text or the label (both hold message content).
 */
function _v2sSlReadBubbles(limit) {
    var sel = 'gv-message-item, gv-text-message-item, [gv-test-id="message-item"], [data-message-id]';
    var list = document.querySelector('gv-message-list');
    var nodes = (list || document).querySelectorAll(sel);
    var out = [];
    for (var i = 0; i < nodes.length; i++) {
        var n = nodes[i];
        if (n.parentElement && n.parentElement.closest(sel)) continue;   // nested match
        var full = n.querySelector('.full-container');
        var direction = _v2sSlBubbleDirection(n, full);
        if (!direction) continue;   // never guess a direction
        var textEl = n.querySelector('gv-annotation.content')
            || n.querySelector('.bubble')
            || n.querySelector('[gv-test-id="message-text"], .message-text, .content');
        var text = ((textEl || n).textContent || '').trim();
        var labelEl = n.querySelector('.cdk-visually-hidden');
        var label = labelEl ? (labelEl.textContent || '').trim() : '';
        out.push({
            direction: direction,
            timeMs: _v2sSlBubbleTime(n),
            text: text,
            label: label || null,
            id: n.getAttribute('data-message-id') || n.getAttribute('data-id') || null,
            failed: _v2sSlBubbleFailed(n, full)
        });
    }
    if (!out.length) return (list && !nodes.length) ? [] : null;
    var max = (typeof limit === 'number' && limit > 0) ? Math.floor(limit) : 20;
    return out.slice(Math.max(0, out.length - max));
}

/** The class/test-id words of one element, for direction and status matching. */
function _v2sSlClassWords(el) {
    if (!el || !el.getAttribute) return '';
    return (el.getAttribute('class') || '') + ' ' + (el.getAttribute('gv-test-id') || '');
}

/** 'OUT' / 'IN' from the item's .full-container (real DOM), else the old item/ancestor classes. */
function _v2sSlBubbleDirection(n, full) {
    if (full) {
        var fc = _v2sSlClassWords(full);
        var o = /(^|\s)outgoing(\s|$)/.test(fc), inc = /(^|\s)incoming(\s|$)/.test(fc);
        if (o !== inc) return o ? 'OUT' : 'IN';
        return null;
    }
    return _v2sSlLegacyDirection(n);
}

/** OD28 L1: GV marks a bubble that did not go out; such a bubble never verifies a send. */
function _v2sSlBubbleFailed(n, full) {
    return /fail|error|not-sent/i.test(_v2sSlClassWords(full) + ' ' + _v2sSlClassWords(n));
}

function _v2sSlLegacyDirection(n) {
    var el = n;
    for (var i = 0; el && i < 3; i++, el = el.parentElement) {
        var c = (el.getAttribute('class') || '') + ' ' + (el.getAttribute('gv-test-id') || '');
        if (/outgoing|from-me|sent-by-me/i.test(c)) return 'OUT';
        if (/incoming|from-them|received/i.test(c)) return 'IN';
    }
    return null;
}

function _v2sSlBubbleTime(n) {
    var t = n.querySelector('time[datetime]');
    if (t) {
        var p = Date.parse(t.getAttribute('datetime'));
        if (!isNaN(p) && p > 0) return p;
    }
    var raw = n.getAttribute('data-timestamp');
    if (raw && /^\d{9,13}$/.test(raw)) {
        var v = parseInt(raw, 10);
        return raw.length <= 10 ? v * 1000 : v;
    }
    return null;
}
