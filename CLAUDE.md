# Voice2SMS — Project Instructions

## Current State: v1.2.0 — Static Analysis Fixes

All core features working. Three Gemini SMS interception paths confirmed on device:
1. **Send button tap** — resource ID match → auto-send via GV
2. **Modify/Edit button** — SENDTO intent → review mode (no auto-send)
3. **Voice "Yes" confirm** — watchdog timer → auto-send via GV

### Key Files

| File | Purpose |
|------|---------|
| `app/src/main/assets/inject.js` | Main WebView injection script |
| `app/src/main/java/com/voice2sms/GVoiceWebViewActivity.java` | WebView activity |
| `app/src/main/java/com/voice2sms/GeminiSmsInterceptService.java` | AccessibilityService for Gemini SMS interception |
| `app/src/main/java/com/voice2sms/SmsHandlerActivity.java` | SMS intent router + SENDTO dedup timestamp |
| `app/src/main/java/com/voice2sms/SetupActivity.java` | First-install setup wizard |
| `app/src/main/java/com/voice2sms/SettingsActivity.java` | Preferences + Gemini toggle |
| `app/src/main/assets/fingerprint-mask.js` | Anti-detection + dark mode |
| `app/src/main/res/xml/gemini_accessibility_config.xml` | AccessibilityService config |

### Building & Testing

- Build: `ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug`
- Release: `ANDROID_HOME=/opt/android-sdk ./gradlew assembleRelease`
- See [ARCHITECTURE.md](ARCHITECTURE.md) for technical details

### Known Issues / Future Work

- `SHOW_FORCED` is deprecated on API 33+ — consider `SHOW_IMPLICIT` or `WindowInsetsController`
- Dead `V2SBridge` methods (`requestType`, `requestTapAndType`, `requestFocusAndKeyboard`) — audit and remove if unused
- Dead `resetComposeState()` function in inject.js — remove if no longer needed
- Hardcoded User-Agent string will age — consider deriving from `WebSettings.getDefaultUserAgent()`
- `SmsHandlerActivity` uses `android.util.Log` inline — normalize to imported `Log` with `TAG`
- Accessibility service monitors all packages (`packageNames = null`) — consider narrowing to Gemini packages only
- Shizuku integration to replace ADB commands with in-app API calls
- `SmsDeliverService`/`SendStatusReceiver` for deeper SMS routing control
