# Voice2SMS — Architecture & Technical Details

## Components

| File | Purpose |
|------|---------|
| `SmsHandlerActivity` | Transparent launcher + SMS/share intent router. Parses `sms:`/`smsto:` URIs (including opaque variants with `&body=`). |
| `GVoiceWebViewActivity` | Hosts the WebView, manages auth flow, injects JS on page load. `singleTask` launch mode for intent reuse. |
| `Voice2SmsApplication` | Application subclass holding singleton WebView. Configures async WebView startup, renderer warm-up, and URL prefetch. |
| `GeminiSmsInterceptService` | AccessibilityService monitoring Gemini's SMS compose overlay. Caches phone+body, intercepts Send button (resource ID match), null-source clicks (with SENDTO dedup), and voice "Yes" confirm (watchdog timer). |
| `SetupActivity` | First-install wizard: default SMS role, ADB commands for blocking Gemini carrier SMS, accessibility service enablement. |
| `SettingsActivity` | Preferences: set default SMS app, switch Google account, Gemini interception toggle, auto-send switch. |
| `inject.js` | Core JS injection — SPA detection, compose flow, chip creation, body fill, auto-send. |
| `fingerprint-mask.js` | Anti-fingerprinting overrides + dark mode CSS filter. |
| `SmsReceiver` | Stub `SMS_DELIVER` receiver (required for default SMS app role). |
| `MmsReceiver` | Stub `WAP_PUSH_DELIVER` receiver (required for default SMS app role). |
| `RespondViaMessageService` | Stub respond-via-message service (required for default SMS app role). |

## Key Design Decisions

- **WebView singleton** — The WebView lives in `Voice2SmsApplication` (Application subclass), surviving Activity lifecycle. Warm starts reuse the already-loaded SPA.
- **Direct Angular chip creation** — Instead of simulating keyboard events (which fail `isTrusted` checks), inject.js calls Angular Material's `matChipInputTokenEnd` callback directly via Zone.js listener introspection. This creates recipient chips without any autocomplete dropdown interaction.
- **Warm start = page reload** — On subsequent intents (`onNewIntent`), the page is always reloaded to get a clean messages-list state. This avoids stale SPA navigation bugs at a cost of ~1s.
- **Default SMS app stubs** — Android requires `SMS_DELIVER` receiver, `WAP_PUSH_DELIVER` receiver, and `RESPOND_VIA_MESSAGE` service to appear in the SMS app picker. These are stub implementations.

## inject.js Flow

1. **Poll for SPA** — Wait for `gv-side-nav` or `gv-thread-list` to appear.
2. **Check for existing conversation** — Scan the messages list for a matching phone number.
3. **New conversation** — Click the FAB (`button[aria-label*="new"]`), wait for compose view.
4. **Create recipient chip** — Primary: call `matChipInputTokenEnd` callback found at `input['__zone_symbol__matChipInputTokenEndfalse']`. Fallback: type char-by-char + click autocomplete dropdown.
5. **Fill body** — Set textarea value via native setter + InputEvent in Angular zone.
6. **Focus + keyboard** — JS `.focus()` + `.click()` + synthetic MouseEvent, then `V2SBridge.requestShowKeyboard()` calls `IMM.showSoftInput(SHOW_FORCED)` on the native side.
7. **Auto-send** — If `force_auto_send` is set, click the send button after a jittered delay.

### Generation Counter

Each `voice2sms()` call increments `window._v2sGeneration`. All async callbacks check `isCancelled()` to bail out if a newer invocation has started, preventing race conditions from rapid-fire intents.

### Zone Discovery

Google Voice uses Angular + Zone.js. The Angular zone is found by scanning `__zone_symbol__*` properties on DOM elements for listener task objects, then inspecting each task's properties for an object with `.run()` method and `.name` property. The zone name is obfuscated in production builds, so property scanning (not name lookup) is required.

## Gemini SMS Interception

### DUAL Architecture

Two-pronged approach to route Gemini SMS through Google Voice:

1. **AppOps Block** — ADB commands prevent Gemini from sending carrier SMS directly:
   - `pm revoke` removes the SMS permission
   - `user-fixed` flag prevents Android from auto-re-granting it
   - `appops ignore` blocks SmsManager at the AppOps layer
   - Triple combo persists across reboots

2. **AccessibilityService** — Monitors Gemini's FloatyActivity overlay:
   - Caches phone number + message body when SMS compose card appears
   - Fires SENDTO intent to Voice2SMS when Send is detected

### Three Interception Paths

| Path | Trigger | Detection Method | Auto-Send |
|------|---------|-----------------|-----------|
| Send button tap | User taps Send on SMS card | Resource ID match (`assistant_robin_lockscreen_compatible_action_card_button_element`) | Yes |
| Modify/Edit button | User taps Modify/Edit | Gemini fires SENDTO intent directly to Voice2SMS | No (review mode) |
| Voice "Yes" confirm | User says "Yes" to voice prompt | Watchdog timer — 4s after last cache update, checks if card is gone | Yes |

### Why the Watchdog?

When the user voice-confirms "Yes", Gemini calls `SmsManager.sendTextMessage()` internally — no UI click event fires, no window state changes, no notifications. The AppOps block prevents the carrier SMS (user sees a toast), but the AccessibilityService receives zero events.

The watchdog solves this: each time the SMS card data is cached, a 4-second delayed check is scheduled. If the cache hasn't been consumed by a click handler and the SMS card is no longer visible, it means the voice confirm path was taken. The watchdog fires the SENDTO intent.

### SENDTO Timestamp Dedup

The Edit/Modify button causes Gemini to fire a SENDTO intent AND the AccessibilityService may also detect a null-source Button click. To prevent double-fire, `SmsHandlerActivity.lastSendtoTimestamp` records when a SENDTO arrives. The AccessibilityService skips null-source clicks if a SENDTO arrived within the last 2 seconds.

### Gemini UI Resource IDs (FloatyActivity)

Discovered via `uiautomator dump`:

| Element | Resource ID |
|---------|-------------|
| Send button | `assistant_robin_lockscreen_compatible_action_card_button_element` |
| Modify button | `assistant_robin_action_card_outlined_button_element` |
| Title text | `assistant_robin_floaty_title_text` |
| Card text nodes | `assistant_robin_action_card_text` |
| Chat Send button | `assistant_robin_send_icon_button` (NOT the SMS card Send) |

### Card Text Node Order

`findAccessibilityNodeInfosByViewId(RES_ACTION_CARD_TEXT)` returns:
1. App name (e.g., "Voice2SMS")
2. Contact name (e.g., "Rachel")
3. Phone line (e.g., "Mobile . +15551234567") — matched by `\+\d{10,}` regex
4. Message body (e.g., "Hello") — first node after phone match

### Known Title Variants

Gemini uses different titles depending on the flow:
- "Text Message To [Contact]"
- "Sending a Text Message"
- "Sending Text Message To [Contact]"
- "Confirm Message to [Contact]"
- "Contact Not Found for Text"

All matched by checking for "Text", "SMS", or "Message" substring.
