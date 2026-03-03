# Voice2SMS

## The Problem

If you use Google Voice as your primary phone number, texting is broken on Android. Tapping a phone number in your contacts, sharing text from an app, or asking Gemini to "send a text" all fire standard Android SMS intents — which go straight to your carrier number, not Google Voice. There's no way to set Google Voice as the default SMS handler because it doesn't register as an SMS app. You're stuck copying numbers, switching to the GV app, pasting, and typing your message manually.

This means:
- **Tapping a phone number** to text goes to your carrier, not Google Voice
- **Share > SMS** from any app sends via carrier
- **"Hey Google, text Rachel"** sends via carrier (or Gemini tries `SmsManager` directly)
- **Quick-reply from notifications** — carrier, not GV

If your real number is your Google Voice number, none of the standard Android SMS workflows work.

## The Solution

Voice2SMS registers as the default SMS app and intercepts all SMS intents. Instead of sending via carrier, it opens Google Voice's web UI in an embedded WebView and programmatically composes the message — creating the recipient chip, filling the body, and optionally auto-sending.

**Without the AccessibilityService** (default), Voice2SMS handles all standard SMS intents — tapping a phone number, sharing text, or clicking "Modify/Edit" in Gemini will open Voice2SMS with the recipient and body pre-populated, ready for you to review and tap Send. However, if you tap Gemini's "Send" button directly, Gemini bypasses intents and uses your default carrier SMS provider.

**With the optional AccessibilityService enabled**, Voice2SMS can also intercept Gemini's "Send" button and voice confirmations ("Yes"), routing those through Google Voice automatically. This is the only way to get true hands-free auto-send when using Gemini as a voice assistant.

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

Download the latest APK from [GitHub Releases](../../releases/latest), then:

```bash
adb install voice2sms-v*.apk
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

### GitHub Actions (CI)

Pushing a tag (`v*`) triggers a release build via GitHub Actions. The workflow builds the APK, signs it, and creates a GitHub Release with the artifact attached.

After creating the GitHub repo, configure 4 secrets for APK signing:

```bash
# Encode keystore as base64 and set secrets
gh secret set KEYSTORE_BASE64 --repo owner/voice2sms --body "$(base64 -w0 keystore/release.jks)"
gh secret set KEYSTORE_PASSWORD --repo owner/voice2sms --body "your-store-password"
gh secret set KEY_ALIAS --repo owner/voice2sms --body "voice2sms"
gh secret set KEY_PASSWORD --repo owner/voice2sms --body "your-key-password"
```

To trigger a release:
```bash
git tag -a v1.2.0 -m "Description of release"
git push origin v1.2.0
```

## Acceptable Use

This tool is intended for **personal use only** — routing your own SMS through your own Google Voice number. It is not designed for and must not be used for sending bulk or mass text messages. The app sends one message at a time through Google Voice's standard web compose UI, so it shouldn't be capable of bulk sending regardless, but just to be clear: don't use it that way. Use of this app must comply with the [Google Voice Acceptable Use Policy](https://support.google.com/voice/answer/9230450) and [Google Terms of Service](https://policies.google.com/terms).

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
