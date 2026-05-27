# Voice2SMS

Route all Android SMS through Google Voice — from any app, Gemini, or your Wear OS watch.

## The Problem

If you use Google Voice as your primary number, texting is broken on Android. Tapping a phone number, sharing text, asking Gemini to "send a text," or using Google Assistant on your watch — all fire standard SMS intents that go to your carrier number, not Google Voice. There's no way to set Google Voice as the default SMS handler.

## The Solution

Voice2SMS registers as the default SMS app and intercepts all SMS intents. Instead of sending via carrier, it opens Google Voice's web UI in an embedded WebView and programmatically composes the message — creating the recipient chip, filling the body, and optionally auto-sending.

### What it intercepts

- **Tapping a phone number** in any app
- **Share > SMS** from any app
- **"Hey Google, text Rachel"** from Gemini on your phone
- **"Hey Google, text Rachel"** from Google Assistant on your watch (via Wear OS companion)
- **Quick-reply from notifications**
- **Gemini's Send button and voice confirmations** (with optional AccessibilityService)

### What it handles inbound

While Voice2SMS is the default SMS app, **incoming carrier SMS** are persisted to
the system message store (`content://sms`) and surfaced as a heads-up notification
(BigTextStyle, useful for copying 2FA codes). Persisting to the system store means
your texts survive a future switch back to Google Messages — Messages reads from
the same provider. Toggle the notification off in Settings if you don't want it.
**MMS is currently not handled** — picture/group/long-MMS messages are dropped
while Voice2SMS is default.

## How It Works

```
Phone                                          Watch (Wear OS)
─────                                          ──────────────
SMS Intent (sms:/smsto:)                       Google Assistant
Share Intent (text/plain)                        "Hey Google, text Alice hi"
        |                                              |
        v                                              v
  SmsHandlerActivity                           SendToReceiverActivity
  parses recipient + body                      validates + sends via
        |                                      Wearable Data Layer
        v                                              |
  GVoiceWebViewActivity ◄════════════(BT/Wi-Fi)════════╝
        |                              WearSmsListenerService
  loads voice.google.com               receives + trampolines
        |                              to SmsHandlerActivity
  inject.js
  creates recipient chip
  fills body textarea
  auto-sends or shows keyboard


Gemini AI ("send a text to...")
        |
        v
  GeminiSmsInterceptService (AccessibilityService)
        |
        +── Send button tap ──> resource ID match ──> auto-send via GV
        +── Modify/Edit btn ──> SENDTO intent ──────> review mode
        +── Voice "Yes" ──────> watchdog timer ──────> auto-send via GV
```

## Requirements

- **Phone**: Android 7.0+ (API 24)
- **Watch** (optional): Wear OS 3+ (Galaxy Watch 4/5/6/7, Pixel Watch, etc.)
- Google account signed into Google Voice

## Install

### Phone

Download the latest `voice2sms-v*.apk` from [GitHub Releases](../../releases/latest):

```bash
adb install voice2sms-v*.apk
```

Open the app and:
1. Grant "Default SMS app" role when prompted — this also auto-grants the SMS
   permissions Voice2SMS needs to persist incoming texts and post notifications
2. Sign into your Google account (one-time; cookies persist)
3. Test by sending an SMS from contacts or any app that fires `sms:` intents

> **Upgrading from v1.5.0 or earlier?** Earlier builds did not declare the SMS
> permissions in the manifest, so incoming SMS were silently dropped. After
> updating, if you still don't see inbound notifications, briefly switch the
> default SMS app to something else and back to Voice2SMS — this refreshes the
> role grant and clears the cached "permission ignored" appop state.

### Watch (optional)

Download `voice2sms-wear-v*.apk` from the same release:

```bash
adb -s <watch-serial> install voice2sms-wear-v*.apk
```

Then say "Hey Google, text [contact] hello" on your watch. The first time, pick **Voice2SMS** in the app chooser and tap "Always." Messages route through Google Voice on your phone automatically.

### Gemini Integration Setup

To also route Gemini AI SMS through Google Voice, three additional steps are needed (guided by the in-app setup wizard):

**Step 1: Set as default SMS app** (done above)

**Step 2: Block Gemini's direct carrier SMS** (one-time ADB commands):
```bash
adb shell pm revoke com.google.android.googlequicksearchbox android.permission.SEND_SMS
adb shell pm set-permission-flags com.google.android.googlequicksearchbox android.permission.SEND_SMS user-fixed
adb shell appops set com.google.android.googlequicksearchbox SEND_SMS ignore
```

**Step 3: Enable the AccessibilityService**
Settings > Accessibility > Voice2SMS Gemini Intercept > Enable

The ADB commands persist across reboots.

## Settings

- **Gemini auto-send** — Automatically send intercepted Gemini SMS (vs. opening for review)
- **Auto-send confirmation** — Show a "Sent via Google Voice" notification on auto-sends
- **Incoming SMS notifications** — Show a heads-up notification for every received SMS (default on; useful for 2FA codes). Toggling off leaves the inbox-persistence path intact — only the live notification is suppressed.

## Tested On

| Device | Role | OS |
|--------|------|-----|
| Pixel 10 Pro Fold | Phone | Android 16 |
| Galaxy Watch 6 (SM-R960) | Watch | Wear OS 5 |

## Building

```bash
# Phone debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Watch debug APK
./gradlew :wear:assembleDebug
# → wear/build/outputs/apk/debug/wear-debug.apk

# Release (both)
./gradlew assembleRelease

# Tests (JVM, no device needed)
./gradlew test
```

### CI/CD

Pushing a `v*` tag triggers GitHub Actions to build, sign, and publish both APKs as a GitHub Release:

```bash
git tag -a v1.5.0 -m "Wear OS companion + auto-send notification"
git push origin v1.5.0
```

See `.github/workflows/release.yml` for details. Requires four repository secrets for APK signing (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

## Architecture

For technical details — inject.js flow, Angular chip creation, Gemini interception internals, Wearable Data Layer protocol, singleton WebView guards — see [ARCHITECTURE.md](ARCHITECTURE.md).

## Acceptable Use

This tool is for **personal use** — routing your own SMS through your own Google Voice number. It is not designed for bulk or mass messaging. Use must comply with the [Google Voice Acceptable Use Policy](https://support.google.com/voice/answer/9230450) and [Google Terms of Service](https://policies.google.com/terms).

## Disclaimer

This project relies on internal details of Google Voice's web UI (Angular component structure, Zone.js internals, DOM selectors) and Gemini's accessibility tree (resource IDs, FloatyActivity layout). **Google may change any of these at any time**, which could break functionality. This is a personal-use tool, not a supported product.

## License

MIT License. See [LICENSE](LICENSE).

## Version History

### v1.5.1

Incoming SMS no longer black-holed.

- Default SMS app now persists incoming carrier SMS to `content://sms` and posts
  a heads-up notification (BigTextStyle) — previously `SmsReceiver` was a no-op
  stub that silently dropped every inbound text while Voice2SMS was default
- Manifest now declares the SMS permissions (`RECEIVE_SMS`, `RECEIVE_MMS`,
  `RECEIVE_WAP_PUSH`, `READ_SMS`, `SEND_SMS`) so the SMS role can auto-grant
  them; without this the corresponding appops stayed at the system default
  `ignore`, dropping `SMS_DELIVER` before the receiver could run
- New "Incoming SMS notifications" setting (default on)
- MMS receive is still a known gap — `MmsReceiver` stays a no-op

### v1.5.0

Wear OS companion APK + auto-send notifications.

- Wear OS companion APK (`wear/`) — catches `ACTION_SENDTO` from Google Assistant on the watch and routes through Google Voice on the phone via the Wearable Data Layer
- "Sent via Google Voice" notification on all auto-send paths (watch, Gemini, respond-via-message), gated by a settings toggle
- Phone-side `WearSmsListenerService` for receiving watch requests with dedup, rate limiting, and BAL-fallback notification
- `SourceSafetyTest` guards for cross-module contract drift (applicationId, signing, protocol paths, validator parity)
- Settings build stamp (version, build code, git SHA, date)

### v1.4.0

CI/CD + crash fixes.

- GitHub Actions: PR CI, release pipeline gated on tests, Dependabot
- Fix SMS-intent crash on malformed URIs
- WebView race condition guards (`isActivityAlive()` on all async paths)
- JVM source-safety test suite (no device required)

### v1.3.0

GBoard sticker support + foldable screen handling.

- `RichContentWebView` subclass for GBoard sticker/image input
- Enter key intercepted to insert newline (not send)
- Foldable screen configuration change handling

### v1.2.0

Security hardening.

- Static analysis fixes (singleTask, UI thread safety, memory leak)
- Security hardening for public repository

### v1.1.0

Gemini integration.

- `GeminiSmsInterceptService` (AccessibilityService) monitors Gemini's SMS compose overlay
- Three interception paths: Send button, Modify/Edit, voice "Yes" confirm
- Auto-send toggle, first-install setup wizard, watchdog timer

### v1.0.0

Initial release.

- SMS intent handling (`SENDTO`, `VIEW`, `SEND`) with recipient + body parsing
- Google Voice WebView with singleton lifecycle
- Direct Angular chip creation via `matChipInputTokenEnd` callback
- Auto-send, dark mode, anti-fingerprinting
