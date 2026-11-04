# Voice2SMS — Project Instructions

## Current State: v1.6.0 — Send Later

Adds scheduled send (`com.voice2sms.sendlater`): capture a draft in the native
Google Voice app or the GV WebView, pick a time, and the app sends it through
the GV WebView at the due time unless facts at that moment (a reply, lateness,
no session, no confirmation) say hold or remind. On by default (D26). See
ARCHITECTURE.md → Send Later. Gemini, Wear, RespondViaMessage and review mode
are unchanged.

Gemini SMS interception paths (confirmed on device):
1. **Send button tap** — resource ID match → auto-send via GV
2. **Modify/Edit button** — SENDTO intent → review mode (no auto-send)
3. **Voice "Yes" confirm** — watchdog timer → auto-send via GV

Other capture/send paths:
4. **Wearable Data Layer receive** (v1.5.0) — `WearSmsListenerService` on path
   `/voice2sms/requests/*` → `SmsHandlerActivity` trampoline with
   `EXTRA_FORCE_AUTO_SEND=true`. Companion APK in `wear/`.
5. **Native Voice overlay capture** (v1.6.0) — `GeminiSmsInterceptService`
   delegates Google Voice events to `VoiceOverlayController` (edge tab + chip)
   → `SendLaterSheetActivity`.
6. **WebView capture** (v1.6.0) — inject.js Send Later module → `V2SBridge`
   → `SendLaterSheetActivity`.
7. **Scheduled send** (v1.6.0) — alarm → `SendLaterEngine` ladder →
   `WebViewScheduledSender` → scheduled branch of `GVoiceWebViewActivity`
   (`ScheduledSendController`).

### Key Files

| File | Purpose |
|------|---------|
| `app/src/main/assets/inject.js` | Main WebView injection script |
| `app/src/main/java/com/voice2sms/GVoiceWebViewActivity.java` | WebView activity |
| `app/src/main/java/com/voice2sms/GeminiSmsInterceptService.java` | AccessibilityService for Gemini SMS interception; delegates Google Voice events to the Send Later overlay |
| `app/src/main/java/com/voice2sms/SmsHandlerActivity.java` | SMS intent router; exports `EXTRA_FORCE_AUTO_SEND` constant |
| `app/src/main/java/com/voice2sms/SetupActivity.java` | First-install setup wizard (steps 4 Send Later, 5 Battery; statuses refreshed in onResume) |
| `app/src/main/java/com/voice2sms/SettingsActivity.java` | Preferences + Gemini toggle + Send Later category + build stamp |
| `app/src/main/java/com/voice2sms/wear/WearSmsListenerService.java` | Receives `{phone,body,requestId}` from watch; trampolines to `SmsHandlerActivity` |
| `app/src/main/java/com/voice2sms/wear/WearPayloadValidator.java` | Pure-JVM payload validator (tested by `WearPayloadValidatorTest`) |
| `app/src/main/assets/fingerprint-mask.js` | Anti-detection + dark mode |
| `app/src/main/res/xml/gemini_accessibility_config.xml` | AccessibilityService config |
| `app/src/main/assets/inject.js` (Send Later module, after `sendImageToGV`) | Edge tab, chip, capture, Undo bar, thread read, thread-list scrape; installed only while Send Later is on |
| `app/src/main/java/com/voice2sms/sendlater/SendLaterIntents.java`, `SendLaterSettings.java` | Pinned action/extra names; `sendlater_*` preference keys and defaults (enabled=false) |
| `app/src/main/java/com/voice2sms/sendlater/domain/` | Pure JVM: `ScheduledMessage`, `Watermark`, `ThreadEvent`, `ThreadEventClassifier`, `LadderEvaluator`, `LatenessBudget`, `PresetSlots`, `ChipLabel` |
| `app/src/main/java/com/voice2sms/sendlater/store/` | Room `SendLaterDatabase` (v2), `SendLaterRepository`, `ThreadDirectory`, `ScheduledSendQueue`, `DataRetention` |
| `app/src/main/java/com/voice2sms/sendlater/engine/` | `SendLaterEngine` (only writer of status + notifications), `SendLaterScheduler` (alarms), receivers, `SendLaterNotifications`, `SendLaterUnlockActivity` |
| `app/src/main/java/com/voice2sms/sendlater/send/` | `WebViewScheduledSender`, `LaunchPlanner`, `LaunchRecheck`, `ScheduledSendController`, `LockCover`, `SendVerifier` |
| `app/src/main/java/com/voice2sms/sendlater/listener/` | `SendLaterNotificationListener`, `NotificationParser`, `LiveConversationKeys`, `NotificationThreadEventSource` |
| `app/src/main/java/com/voice2sms/sendlater/voice/logic/`, `voice/overlay/` | Native Voice screen signature, bubble parser, recipient resolver; overlay tab/chip/Undo, `OnScreenThreadSource`, `VoiceOverlayIds` (R8 allowlist) |
| `app/src/main/java/com/voice2sms/sendlater/web/` | `SendLaterWebHooks` (bridge side), `OriginGuard`, `ThreadUrl`, `CapturePayload`, `ChipState` |
| `app/src/main/java/com/voice2sms/sendlater/ui/` | `SendLaterSheetActivity`, `UpcomingActivity`, `SendLaterSettingsController` |
| `app/src/main/java/com/voice2sms/sendlater/battery/` | `BatteryHealth`, `BatteryAdvice`, `BatteryWarning` (once-a-day warning), `BatteryIndicator` (green OK / amber WARN icon for the wizard and the Settings row) |
| `app/src/debug/java/com/voice2sms/sendlater/debug/` | adb-only `SendLaterDebugReceiver` (debug APK only) |

### Building & Testing

- Build: `ANDROID_HOME=<path to your Android SDK> ./gradlew assembleDebug`
- Release: `ANDROID_HOME=<path to your Android SDK> ./gradlew assembleRelease`
- JVM tests: `./gradlew test` — no device. Regression guards that must stay green:
  - `SourceSafetyTest` — original guards (WebView `isActivityAlive()`, no `prefetchUrlAsync`, Wear contracts)
  - `SendLaterSourceSafetyTest` — G1 WebView only through guarded hosts; G2 no `force_auto_send` / `EXTRA_FORCE_AUTO_SEND` under `sendlater/` or the scheduled branch; G3 no text/draft/name in `Log` args; G4 Voice id allowlist (`VoiceOverlayIds.R8_IDS`); G5 scheduled branch uses `ScheduledSendQueue`, not `AutoSendRateLimiter`; G6 `ACTION_SCHEDULED` only after a verified `repository.schedule`; G7 all baseline `SourceSafetyTest` guards present
  - `PrivacyLoggingSourceTest` — no unguarded `Log` of `getText()`, content descriptions, raw URLs or message text in `GeminiSmsInterceptService` / `GVoiceWebViewActivity` (URLs go through `withoutQuery`)
  - Feature tests live under `app/src/test/java/com/voice2sms/sendlater/`; debug-hook tests under `app/src/testDebug/` (run by `testDebugUnitTest` only)
- On-device crash canary: `tests/smoke/test-sms-intent.sh` — fires SMS intents, fails on FATAL EXCEPTION (requires connected device)
- On-device Send Later smoke: `tests/smoke/sendlater-smoke.sh` — debug APK only. Needs a test recipient in `SENDLATER_TEST_RECIPIENT` (or `RECIPIENT`, or gitignored `tests/smoke/.recipient`); exits 2 without one. Real sends only with `SEND=1` and the recipient equal to `SENDLATER_ALLOWED_RECIPIENT` (or gitignored `tests/smoke/.allowed-recipient`). Also `SEND_WAIT_SECS`, `ADB`, `SERIAL`, `INSTALL`, `APK`, `ADB_WINDOWS_PATHS`. It turns Send Later on for the run and restores the previous value. Never commit a real number.
- Debug hooks: `SendLaterDebugReceiver` (`DEBUG_SEED`, `DEBUG_DUMP`, `DEBUG_EVALUATE`, `DEBUG_FIRE`, `DEBUG_INBOUND`, `DEBUG_LAST_CALL`, `DEBUG_CANCEL`, `DEBUG_ENABLE`) exists only in the debug build: code in `app/src/debug/java`, registered in `app/src/debug/AndroidManifest.xml`, protected by `android.permission.DUMP`, returns early unless `BuildConfig.DEBUG`. Release APKs contain no `sendlater.debug` classes. Details in `tests/smoke/README.md`.
- **Pre-deploy gate**: run `./gradlew test`, `tests/smoke/test-sms-intent.sh` AND `tests/smoke/sendlater-smoke.sh` before `assembleRelease` / tagging. All must pass. See `tests/smoke/README.md`.
- **CI**: `.github/workflows/ci.yml` runs `./gradlew test` + `assembleDebug` on every PR and `main` push. `.github/workflows/release.yml` triggers on `v*` tags — tests gate the build/sign/release job (`needs: test`), so a failing `SourceSafetyTest` blocks a release. No manual build push needed; just `git tag -a vX.Y.Z && git push origin vX.Y.Z`.
- **Branch-protection status check names** (Settings → Branches): GitHub uses the job's display `name:`, not the YAML job id. Add `JVM source-safety tests` and `Debug APK builds` — NOT `unit-tests` / `build-debug`.
- See [ARCHITECTURE.md](ARCHITECTURE.md) for technical details

### Singleton WebView Guard Pattern

The WebView lives in `Voice2SmsApplication` and outlives Activity instances. Any
code that touches `webView` after a post (`runOnUiThread`, `postDelayed`, background
Thread → UI) MUST short-circuit via `isActivityAlive()` — webView is nulled by
`onRenderProcessGone`. `SourceSafetyTest` enforces this in V2SBridge, typing
paths, and the auth callback — don't hide it behind a wrapper.

### Send Later Rules

- **Defaults ON** (`SendLaterSettings.DEFAULT_ENABLED`, D26; an install that never set the switch reads on). Every auto-send trigger checks the master switch; switch changes go through `SendLaterEngine.applySwitch` (never `SendLaterScheduler.reregisterAll` from UI). Off cancels alarms and keeps records.
- **No Wear/Gemini auto-send machinery.** The scheduled path never uses `EXTRA_FORCE_AUTO_SEND` / `force_auto_send` or `AutoSendRateLimiter`; it has its own `ScheduledSendQueue` (2 s spacing, 50/day). Enforced by `SendLaterSourceSafetyTest`.
- **Only `ScheduledSendController` produces SENT_VERIFIED** (a new outbound bubble with the body hash that settles, or a server time, during the job). The engine never verifies or retries from a thread read; unconfirmed / IN_FLIGHT-at-restart → HELD(UNVERIFIED) + "may have already sent". The only retry is the user's Send anyway.
- **Cover and window flags are one decision.** Every scheduled job shows the opaque `LockCover`; show-when-locked / turn-screen-on / keep-screen-on are set only while it is up. Never dismiss the keyguard.
- **`SendLaterEngine` is the only writer of status and Send Later notifications.** Anything that moves a row into or out of SCHEDULED/HELD/IN_FLIGHT broadcasts `ACTION_STATE_CHANGED` (setPackage'd), or the chip and listener gate go stale.
- **New Send Later `V2SBridge` methods** start with `OriginGuard.isTrustedGvUrl(mainFrameUrl)`.
- **Voice ids:** reading a new Google Voice id means editing `VoiceOverlayIds.R8_IDS` deliberately.
- **Room migrations:** `SendLaterDatabase` is at v2. Every schema change adds an explicit `Migration` for each step (next: `MIGRATION_2_3`); never `fallbackToDestructiveMigration`.
- **New `sendlater_*` SharedPreferences files** must be added to both backup-rule XMLs (`BackupRulesTest`).
- **New debug-only code** goes under `app/src/debug/java`, its tests under `app/src/testDebug`.
- Repository calls are synchronous; run them off the main thread.

### Known Issues / Future Work

- **Do not call `Profile.prefetchUrlAsync(GV_MESSAGES_URL, ...)`** in `Voice2SmsApplication` — races the Activity's live `loadUrl()` on the same URL, NPEs inside chromium's `WV.h22.run` (WebView 148 + androidx.webkit 1.15.0). Enforced by `SourceSafetyTest`.
- `SHOW_FORCED` is deprecated on API 33+ — consider `SHOW_IMPLICIT` or `WindowInsetsController`
- Dead `V2SBridge` methods (`requestType`, `requestTapAndType`, `requestFocusAndKeyboard`) — audit and remove if unused
- Dead `resetComposeState()` function in inject.js — remove if no longer needed
- Hardcoded User-Agent string will age — consider deriving from `WebSettings.getDefaultUserAgent()`
- `SmsHandlerActivity` uses `android.util.Log` inline — normalize to imported `Log` with `TAG`
- Accessibility service monitors all packages (`packageNames = null`) — it now drives both Gemini interception and the Google Voice overlay; narrowing to the Gemini + Google Voice packages is still possible
- **Send Later iframe residual** — `@JavascriptInterface` is visible to subframes; the main-frame `OriginGuard` check does not stop a cross-origin iframe inside a voice.google.com page calling the bridge (including `onThreadRead`, which feeds send verification). Fix: `WebViewCompat.addWebMessageListener` limited to `https://voice.google.com`.
- **Stricter navigation allowlist** — `OriginGuard.isAllowedNavigation` (https + Google domains + `accounts.youtube.com`) replaced the old `contains("google.com")` check; Google sign-in through it still needs a device re-check.
- **WebView DOM selectors unchecked** — the Send Later bubble, thread-title and thread-list selectors in inject.js and the `itemId=t.+E164` thread URL shape have not been verified against live GV.
- **One-shot composer probe** — the launch-time `ForegroundUse` composer probe was deferred (needs `WebViewScheduledSender` to go async).
- Shizuku integration to replace ADB commands with in-app API calls
- `SmsDeliverService`/`SendStatusReceiver` for deeper SMS routing control

### Shipped (was backlog)

- **GBoard sticker support in WebView** — shipped in v1.3.0 (`RichContentWebView` + `OnReceiveContentListener` + JS file-input injection). The `feature/gboard-sticker-bridge` branch is fully merged into `main`; the branch name carries nothing extra.
- **Scheduled messaging with conditional send** — shipped in v1.6.0 as Send Later (`com.voice2sms.sendlater`).

### Feature Backlog (Researched)

- **API Replay hybrid (protobuf-over-HTTP)** — Send SMS via GV's internal API using WebView session cookies. Faster, less brittle for auto-send. Keep WebView for auth + review mode.
- **Send Later follow-ups:**
  - Invisible RPC send (the API replay above) instead of the full-screen WebView, plus the RPC `ThreadEventSource` (`RpcThreadEventSource` is a stub today)
  - Group threads (tab and chip only appear on 1:1 threads)
  - Watch mirroring of the last call
  - Capture from Gemini, `@time` in the draft, and the share sheet
  - Optional server-side sender for when the phone is off or force-stopped
  - Read-only chip while Send Later is off (today the tab and chip disappear)
  - Detect GV's failed-send bubble in the DOM so a failed send never verifies
- **Emulator smoke test in CI** — run `tests/smoke/test-sms-intent.sh` against a `reactivecircus/android-emulator-runner@v2` Pixel on API 34 as a third job in `ci.yml` / `release.yml`. Adds ~4-5 min/run; skipped for now to keep PR feedback fast. Trade-off: real on-device smoke test stays manual until added.
