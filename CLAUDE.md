# Voice2SMS — Project Instructions

## Current State: v1.5.0 — Wearable Listener + Build Stamp

Adds phone-side support for a future Voice2SMS Wear OS companion APK. Existing
Gemini interception paths unchanged. See `GALAXY-WATCH-SMS.md` for the Wear
protocol contract and the rationale for each design decision.

All core features working. Three Gemini SMS interception paths confirmed on device:
1. **Send button tap** — resource ID match → auto-send via GV
2. **Modify/Edit button** — SENDTO intent → review mode (no auto-send)
3. **Voice "Yes" confirm** — watchdog timer → auto-send via GV

New in v1.5.0:
4. **Wearable Data Layer receive** — `WearSmsListenerService` on path
   `/voice2sms/requests/*` → `SmsHandlerActivity` trampoline with
   `EXTRA_FORCE_AUTO_SEND=true`. Watch APK not yet implemented.
5. **Settings build stamp** — `SettingsActivity` now shows
   `v<name> · <code> · <git-sha> · <date>` (long-press to copy).

### Key Files

| File | Purpose |
|------|---------|
| `app/src/main/assets/inject.js` | Main WebView injection script |
| `app/src/main/java/com/voice2sms/GVoiceWebViewActivity.java` | WebView activity |
| `app/src/main/java/com/voice2sms/GeminiSmsInterceptService.java` | AccessibilityService for Gemini SMS interception |
| `app/src/main/java/com/voice2sms/SmsHandlerActivity.java` | SMS intent router; exports `EXTRA_FORCE_AUTO_SEND` constant |
| `app/src/main/java/com/voice2sms/SetupActivity.java` | First-install setup wizard |
| `app/src/main/java/com/voice2sms/SettingsActivity.java` | Preferences + Gemini toggle + build stamp |
| `app/src/main/java/com/voice2sms/wear/WearSmsListenerService.java` | Receives `{phone,body,requestId}` from watch; trampolines to `SmsHandlerActivity` |
| `app/src/main/java/com/voice2sms/wear/WearPayloadValidator.java` | Pure-JVM payload validator (tested by `WearPayloadValidatorTest`) |
| `app/src/main/assets/fingerprint-mask.js` | Anti-detection + dark mode |
| `app/src/main/res/xml/gemini_accessibility_config.xml` | AccessibilityService config |

### Building & Testing

- Build: `ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug`
- Release: `ANDROID_HOME=/opt/android-sdk ./gradlew assembleRelease`
- JVM source-safety tests: `./gradlew test` — runs `app/src/test/java/com/voice2sms/SourceSafetyTest.java` (regression guards, ~5s, no device)
- On-device crash canary: `tests/smoke/test-sms-intent.sh` — fires SMS intents, fails on FATAL EXCEPTION (requires connected device)
- **Pre-deploy gate**: run `./gradlew test` AND `tests/smoke/test-sms-intent.sh` before `assembleRelease` / tagging. Both must pass. See `tests/smoke/README.md`.
- **CI**: `.github/workflows/ci.yml` runs `./gradlew test` + `assembleDebug` on every PR and `main` push. `.github/workflows/release.yml` triggers on `v*` tags — tests gate the build/sign/release job (`needs: test`), so a failing `SourceSafetyTest` blocks a release. No manual build push needed; just `git tag -a vX.Y.Z && git push origin vX.Y.Z`.
- **Branch-protection status check names** (Settings → Branches): GitHub uses the job's display `name:`, not the YAML job id. Add `JVM source-safety tests` and `Debug APK builds` — NOT `unit-tests` / `build-debug`.
- See [ARCHITECTURE.md](ARCHITECTURE.md) for technical details

### Singleton WebView Guard Pattern

The WebView lives in `Voice2SmsApplication` and outlives Activity instances. Any
code that touches `webView` after a post (`runOnUiThread`, `postDelayed`, background
Thread → UI) MUST short-circuit via `isActivityAlive()` — webView is nulled by
`onRenderProcessGone`. `SourceSafetyTest` enforces this in V2SBridge, typing
paths, and the auth callback — don't hide it behind a wrapper.

### Known Issues / Future Work

- **Do not call `Profile.prefetchUrlAsync(GV_MESSAGES_URL, ...)`** in `Voice2SmsApplication` — races the Activity's live `loadUrl()` on the same URL, NPEs inside chromium's `WV.h22.run` (WebView 148 + androidx.webkit 1.15.0). Enforced by `SourceSafetyTest`.
- `SHOW_FORCED` is deprecated on API 33+ — consider `SHOW_IMPLICIT` or `WindowInsetsController`
- Dead `V2SBridge` methods (`requestType`, `requestTapAndType`, `requestFocusAndKeyboard`) — audit and remove if unused
- Dead `resetComposeState()` function in inject.js — remove if no longer needed
- Hardcoded User-Agent string will age — consider deriving from `WebSettings.getDefaultUserAgent()`
- `SmsHandlerActivity` uses `android.util.Log` inline — normalize to imported `Log` with `TAG`
- Accessibility service monitors all packages (`packageNames = null`) — consider narrowing to Gemini packages only
- Shizuku integration to replace ADB commands with in-app API calls
- `SmsDeliverService`/`SendStatusReceiver` for deeper SMS routing control

### Feature Backlog (Researched)

- **Scheduled messaging with conditional send** — `AlarmManager.setAlarmClock()` + Room DB + three-layer change detection. Novel "auto-hold if conversation changed" feature.
- **GBoard sticker support in WebView** — Implemented on `feature/gboard-sticker-bridge`. RichContentWebView subclass + commitContent interception + JS file input injection.
- **API Replay hybrid (protobuf-over-HTTP)** — Send SMS via GV's internal API using WebView session cookies. Faster, less brittle for auto-send. Keep WebView for auth + review mode.
- **Emulator smoke test in CI** — run `tests/smoke/test-sms-intent.sh` against a `reactivecircus/android-emulator-runner@v2` Pixel on API 34 as a third job in `ci.yml` / `release.yml`. Adds ~4-5 min/run; skipped for now to keep PR feedback fast. Trade-off: real on-device smoke test stays manual until added.
