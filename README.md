# Voice2SMS

Route SMS intents through Google Voice instead of your carrier. When set as the default SMS app, Voice2SMS intercepts outgoing SMS intents (from Gemini, contacts, share sheets, etc.) and composes the message in Google Voice's web UI via an embedded WebView.

## How It Works

```
SMS Intent (sms:/smsto:)          Share Intent (text/plain)
        |                                   |
        v                                   v
  SmsHandlerActivity ────── parses recipient + body ──────> GVoiceWebViewActivity
                                                                    |
                                                    loads voice.google.com/u/0/messages
                                                                    |
                                                        injects inject.js into WebView
                                                                    |
                                                    creates recipient chip via Angular
                                                    internal matChipInputTokenEnd callback
                                                                    |
                                                        fills message body textarea
                                                                    |
                                                    shows keyboard / auto-sends


Gemini AI ("send a text to...")
        |
        v
  GeminiSmsInterceptService (AccessibilityService)
        |
        +-- Send button tap --> resource ID match --> auto-send via GV
        +-- Modify/Edit btn --> SENDTO intent ------> review mode (no auto-send)
        +-- Voice "Yes" ------> watchdog timer ------> auto-send via GV
```

### Key Design Decisions

- **WebView singleton** — The WebView lives in `Voice2SmsApplication` (Application subclass), surviving Activity lifecycle. Warm starts reuse the already-loaded SPA.
- **Direct Angular chip creation** — Instead of simulating keyboard events (which fail `isTrusted` checks), inject.js calls Angular Material's `matChipInputTokenEnd` callback directly via Zone.js listener introspection. This creates recipient chips without any autocomplete dropdown interaction.
- **Warm start = page reload** — On subsequent intents (`onNewIntent`), the page is always reloaded to get a clean messages-list state. This avoids stale SPA navigation bugs at a cost of ~1s.
- **Default SMS app stubs** — Android requires `SMS_DELIVER` receiver, `WAP_PUSH_DELIVER` receiver, and `RESPOND_VIA_MESSAGE` service to appear in the SMS app picker. These are stub implementations.

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

## Requirements

- Android 7.0+ (API 24)
- Google account signed into Google Voice
- Set as default SMS app (prompted on first launch)

## Building

```bash
# Debug build
ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug

# Release build (signed)
ANDROID_HOME=/opt/android-sdk ./gradlew assembleRelease
```

Release APK: `app/build/outputs/apk/release/app-release.apk`

### Signing

Release builds are signed with the keystore at `keystore/release.jks`. This keystore is gitignored. To create a new one:

```bash
keytool -genkey -v -keystore keystore/release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias voice2sms -storepass voice2sms -keypass voice2sms
```

## Install

```bash
adb install release/voice2sms-v1.1.0.apk
```

Then open the app and:
1. Grant "Default SMS app" role when prompted
2. Sign into your Google account (one-time; cookies persist)
3. Test by sending an SMS from Gemini, contacts, or any app that fires `sms:` intents

### Gemini Integration Setup

To route Gemini AI SMS through Google Voice, three additional steps are required (guided by the in-app setup wizard on first launch):

**Step 1: Set as default SMS app** (done above)

**Step 2: Block Gemini's direct carrier SMS** (one-time ADB commands):
```bash
adb shell pm revoke com.google.android.googlequicksearchbox android.permission.SEND_SMS
adb shell pm set-permission-flags com.google.android.googlequicksearchbox android.permission.SEND_SMS user-fixed
adb shell appops set com.google.android.googlequicksearchbox SEND_SMS ignore
```

**Step 3: Enable the AccessibilityService**
- Settings > Accessibility > Voice2SMS Gemini Intercept > Enable

The triple ADB command combo (`pm revoke` + `user-fixed` + `appops ignore`) persists across reboots. Future versions will use Shizuku to automate this step.

## Version History

### v1.1.0 (2026-03-03)

Gemini Accessibility Auto-Send Support.

- AccessibilityService monitors Gemini's SMS compose overlay (FloatyActivity)
- Three interception paths:
  - Send button tap (resource ID match) -> auto-send via Google Voice
  - Modify/Edit button (SENDTO intent) -> review mode, no auto-send
  - Voice "Yes" confirm (watchdog timer detects card gone) -> auto-send via Google Voice
- SMS card data caching with 60s TTL and 500ms scan throttle
- SENDTO timestamp dedup prevents double-fire on Edit button path
- Auto-send toggle in Settings (Gemini Integration category)
- First-install setup wizard (default SMS, ADB commands, accessibility service)
- Broadened accessibility event monitoring (notifications, window state changes)

### v1.0.0 (2026-03-03)

Initial release.

- SMS intent handling (`SENDTO`, `VIEW`, `SEND`) with recipient + body parsing
- Google Voice WebView with singleton lifecycle and warm start support
- Direct Angular chip creation via `matChipInputTokenEnd` callback
- Count-based chip verification (handles contact name display)
- Generation counter for cancelling stale async operations
- Opaque URI body parsing (`sms:+1234&body=Hello`)
- Dark mode via CSS filter injection
- Async WebView startup + renderer warm-up + URL prefetch
- Anti-fingerprinting mask for WebView detection evasion
- Settings: default SMS app role, Google account switching
