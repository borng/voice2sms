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

For detailed technical architecture, component descriptions, inject.js flow, and Gemini interception internals, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Requirements

- Android 7.0+ (API 24)
- Google account signed into Google Voice
- Set as default SMS app (prompted on first launch)

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

## Disclaimer

This project relies on internal implementation details of Google Voice's web UI (Angular component structure, Zone.js listener internals, DOM selectors) and Gemini's accessibility tree (resource IDs, FloatyActivity layout). **Google may change any of these at any time without notice**, which could break functionality. This is a personal-use tool, not a supported product. If something stops working after a Google Voice or Gemini update, the relevant selectors and resource IDs will need to be re-discovered and updated.

## License

MIT License. See [LICENSE](LICENSE).

## Version History

### v1.1.0 (2026-03-03)

Gemini Accessibility Auto-Send Support.

- AccessibilityService monitors Gemini's SMS compose overlay (FloatyActivity)
- Three interception paths: Send button, Modify/Edit, voice "Yes" confirm
- Auto-send toggle in Settings (Gemini Integration category)
- First-install setup wizard (default SMS, ADB commands, accessibility service)
- Watchdog timer catches voice-confirm path

### v1.0.0 (2026-03-03)

Initial release.

- SMS intent handling (`SENDTO`, `VIEW`, `SEND`) with recipient + body parsing
- Google Voice WebView with singleton lifecycle and warm start support
- Direct Angular chip creation via `matChipInputTokenEnd` callback
- Auto-send, dark mode, anti-fingerprinting, async WebView startup
- Settings: default SMS app role, Google account switching
