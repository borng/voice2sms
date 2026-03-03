# Voice2SMS — Project Instructions

## Current State: v1.1.0 — Gemini Accessibility Auto-Send

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
| `TEST-PLAN-GEMINI-SMS.md` | Gemini integration architecture + test plan |
| `PLAN.md` | Architecture plan & debug log |
| `MEMORY.md` (in memory dir) | Persistent memory across sessions |

### Credentials

- **Prompt the user for a fresh cookie string** if you need to authenticate with Google Voice in the Playwright MCP browser
- Cookies go on `.google.com` domain, `__Secure-*` cookies need `sameSite: 'None'`
- Reference: `test-pw-local.js` has the cookie parsing logic and last-known cookies

### Testing

- Device: Pixel 10 Pro Fold, ADB over WiFi
- ADB binary: `/opt/android-sdk/platform-tools/adb`
- Build: `./gradlew assembleDebug` or `./gradlew assembleRelease`
- Wireless debugging port changes on reconnect — check device settings

### Reference Docs

- See `MEMORY.md` for verified selectors, mobile vs desktop differences, and all prior findings
- See `TEST-PLAN-GEMINI-SMS.md` for Gemini interception architecture and UI resource IDs
- See `DEBUG-SESSION-2026-02-08.md` for the inject.js debug session log

### Future Work

- Shizuku integration to replace ADB commands with in-app API calls
- `SmsDeliverService`/`SendStatusReceiver` for deeper SMS routing control
