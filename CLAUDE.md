# Voice2SMS — Project Instructions

## Current State: v2.0.0 — Send Later

Adds scheduled send (`com.voice2sms.sendlater`): capture a draft in the native
Google Voice app or the GV WebView, pick a time, and the app sends it through
the GV WebView at the due time unless facts at that moment (a reply, lateness,
no session, no confirmation, a draft of the user's in the box) say hold or
remind. On by default on a fresh install; a phone restored from backup
starts with it off. See ARCHITECTURE.md → Send Later and CHANGELOG.md.

Also in 2.0.0: `force_auto_send` counts only with the in-process `AutoSendAuth`
token, so Gemini, Wear and RESPOND_VIA_MESSAGE still auto-send but an external
app's request opens review mode (ARCHITECTURE.md → Auto-send authorization).
Gemini's SENDTO is package-scoped and carries the flag only when
`gemini_auto_send` is on. Review mode itself is unchanged.

Gemini SMS interception paths (confirmed on device):
1. **Send button tap** — resource ID match → auto-send via GV
2. **Modify/Edit button** — SENDTO intent → review mode (no auto-send)
3. **Voice "Yes" confirm** — watchdog timer → auto-send via GV

Other capture/send paths:
4. **Wearable Data Layer receive** (v1.5.0) — `WearSmsListenerService` on path
   `/voice2sms/requests/*` → `SmsHandlerActivity` trampoline with
   `EXTRA_FORCE_AUTO_SEND=true` + `AutoSendAuth` token. Companion APK in `wear/`.
5. **Native Voice overlay capture** (v2.0.0) — `GeminiSmsInterceptService`
   delegates Google Voice events to `VoiceOverlayController` (edge tab + chip)
   → `SendLaterSheetActivity`.
6. **WebView capture** (v2.0.0) — inject.js Send Later module → `V2SBridge`
   (nonce + `OriginGuard`) → `SendLaterSheetActivity`.
7. **Scheduled send** (v2.0.0) — alarm → `SendLaterEngine` ladder →
   `WebViewScheduledSender` → scheduled branch of `GVoiceWebViewActivity`
   (`ScheduledSendController`, `v2sScheduledCompose`).

### Key Files

| File | Purpose |
|------|---------|
| `app/src/main/assets/inject.js` | Main WebView injection script |
| `app/src/main/java/com/voice2sms/GVoiceWebViewActivity.java` | WebView activity |
| `app/src/main/java/com/voice2sms/GeminiSmsInterceptService.java` | AccessibilityService for Gemini SMS interception; delegates Google Voice events to the Send Later overlay |
| `app/src/main/java/com/voice2sms/SmsHandlerActivity.java` | SMS intent router; exports `EXTRA_FORCE_AUTO_SEND` constant; honours it only with an `AutoSendAuth` token |
| `app/src/main/java/com/voice2sms/AutoSendAuth.java` | Per-process auto-send token: `attach` at the three internal origins (Gemini, Wear, RESPOND_VIA_MESSAGE), `isAuthorized` + `strip` at the two consumers |
| `app/src/main/java/com/voice2sms/SetupActivity.java` | First-install setup wizard (steps 4 Send Later, 5 Battery; statuses refreshed in onResume) |
| `app/src/main/java/com/voice2sms/SettingsActivity.java` | Preferences + Gemini toggle + Send Later category + build stamp |
| `app/src/main/java/com/voice2sms/wear/WearSmsListenerService.java` | Receives `{phone,body,requestId}` from watch; trampolines to `SmsHandlerActivity` |
| `app/src/main/java/com/voice2sms/wear/WearPayloadValidator.java` | Pure-JVM payload validator (tested by `WearPayloadValidatorTest`) |
| `app/src/main/assets/fingerprint-mask.js` | Anti-detection + dark mode |
| `app/src/main/res/xml/gemini_accessibility_config.xml` | AccessibilityService config |
| `app/src/main/assets/inject.js` (Send Later module, after `sendImageToGV`) | Edge tab, chip, capture, Undo bar, thread read, thread-list scrape; installed only while Send Later is on |
| `app/src/main/java/com/voice2sms/sendlater/SendLaterIntents.java`, `SendLaterSettings.java` | Pinned action/extra names; `sendlater_*` preference keys and defaults (enabled=true on a fresh install). The master switch lives in the non-backed-up `sendlater_switch` prefs file: use `isEnabled` / `setEnabled`, never `KEY_ENABLED` in the default prefs |
| `app/src/main/java/com/voice2sms/sendlater/domain/` | Pure JVM: `ScheduledMessage`, `Watermark`, `ThreadEvent`, `ThreadEventClassifier`, `LadderEvaluator`, `LatenessBudget`, `PresetSlots`, `ChipLabel` (incl. `recipient` / `formatNumber`), `DraftChoice`, `ConversationRef` |
| `app/src/main/java/com/voice2sms/sendlater/store/` | Room `SendLaterDatabase` (v2), `SendLaterRepository`, `ThreadDirectory`, `ScheduledSendQueue`, `DataRetention`, `RecipientNames` (fill a missing name from contacts) |
| `app/src/main/java/com/voice2sms/sendlater/engine/` | `SendLaterEngine` (only writer of status + notifications), `SendLaterScheduler` (alarms), receivers, `SendLaterNotifications`, `DraftNotice` + `DraftStash` (draft-choice notification; draft in memory only), `SendLaterUnlockActivity`, `UserTapGrant` |
| `app/src/main/java/com/voice2sms/sendlater/send/` | `WebViewScheduledSender`, `LaunchPlanner`, `LaunchRecheck`, `ScheduledSendController`, `ScheduledCompose`, `LockCover`, `SendVerifier`, `SendWatch` + `SentFollowUp` (settle, later "Not sent"), `RegularSend` + `ClaimWindow` (never take over a regular send) |
| `app/src/main/java/com/voice2sms/sendlater/listener/` | `SendLaterNotificationListener`, `NotificationParser`, `LiveConversationKeys`, `NotificationThreadEventSource`, `AndroidContactNames`, `ContactsChangeWatcher` |
| `app/src/main/java/com/voice2sms/sendlater/voice/logic/`, `voice/overlay/` | Native Voice screen signature, bubble parser, recipient resolver (directory-only match ⇒ `NEEDS_CHOICE`); overlay tab/chip/Undo, `OnScreenThreadSource`, `VoiceOverlayIds` (R8 allowlist) |
| `app/src/main/java/com/voice2sms/sendlater/web/` | `SendLaterWebHooks` (bridge side), `BridgeNonce` (per-document nonce), `OriginGuard`, `ThreadUrl`, `ThreadBubble`, `CapturePayload`, `ChipState` |
| `app/src/main/java/com/voice2sms/sendlater/ui/` | `SendLaterSheetActivity`, `UpcomingActivity`, `SendLaterSettingsController` |
| `app/src/main/java/com/voice2sms/sendlater/battery/`, `a11y/` | `BatteryHealth`, `BatteryAdvice`, `BatteryWarning` (once-a-day warning), `BatteryIndicator` (green OK / amber WARN icon for the wizard and the Settings row); `A11yHealth` / `A11yWarning` (accessibility-off warning) |
| `app/src/debug/java/com/voice2sms/sendlater/debug/` | adb-only `SendLaterDebugReceiver` (debug APK only) |
| `CHANGELOG.md` | Release notes, one entry per tag |

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

- **Defaults ON** (`SendLaterSettings.DEFAULT_ENABLED`; a fresh install that never set the switch reads on; a restore/transfer reads off). The switch is only read/written via `SendLaterSettings.isEnabled` / `setEnabled` (own `sendlater_switch` prefs file, never backed up). Every auto-send trigger checks the master switch; switch changes go through `SendLaterEngine.applySwitch` (never `SendLaterScheduler.reregisterAll` from UI). Off cancels alarms and keeps records.
- **No Wear/Gemini auto-send machinery.** The scheduled path never uses `EXTRA_FORCE_AUTO_SEND` / `force_auto_send` or `AutoSendRateLimiter`; it has its own `ScheduledSendQueue` (2 s spacing, 50/day). Enforced by `SendLaterSourceSafetyTest`.
- **Only `ScheduledSendController` produces SENT_VERIFIED** (a new outbound bubble with the body hash that settles, or a server time, during the job). The engine never verifies or retries from a thread read; unconfirmed / IN_FLIGHT-at-restart → HELD(UNVERIFIED) + "may have already sent". The only retry is the user's Send anyway.
- **Cover and window flags are one decision.** Every scheduled job shows the opaque `LockCover`; show-when-locked / turn-screen-on / keep-screen-on are set only while it is up. Never dismiss the keyguard.
- **`SendLaterEngine` is the only writer of status and Send Later notifications.** Anything that moves a row into or out of SCHEDULED/HELD/IN_FLIGHT broadcasts `ACTION_STATE_CHANGED` (setPackage'd), or the chip and listener gate go stale.
- **New Send Later `V2SBridge` methods** take the `BridgeNonce` as their first argument (ignore the call without the current nonce) and check `OriginGuard.isTrustedGvUrl(mainFrameUrl)`. No `startsWith("https://voice.google.com")` checks. A thread-directory match alone never resolves a recipient.
- **Auto-send:** any new auto-send origin must call `AutoSendAuth.attach` next to `force_auto_send`, and any new consumer must check `AutoSendAuth.isAuthorized` and `strip`. Send Later never does either.
- **Scheduled compose never types or taps Send outside the job's thread**, never types over a user draft (draft-choice notification instead) and never loads a page over a running regular send (`RegularSend`).
- **Every Send Later notification action needs an unlock** (`unlockedAction` / `lockedAction` helpers in `SendLaterNotifications`); never add a plain broadcast action.
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
- Accessibility service monitors all packages (`packageNames = null`) — it now drives both Gemini interception and the Google Voice overlay; narrowing to the Gemini + Google Voice packages is still possible
- **Send Later bridge and iframes** — `@JavascriptInterface` is visible to subframes. Since 2.0.0 every Send Later bridge method needs the per-document `BridgeNonce`, which only the trusted top frame holds, so a cross-origin iframe's calls are ignored. Further hardening option: `WebViewCompat.addWebMessageListener` limited to `https://voice.google.com`.
- **Accepted residuals (2.0.0 delivery review):**
  - A reply that arrives in the ~10–200 ms between the sheet's commit and the listener's key-gate rebuild is dropped, so it cannot hold that message (the due-time check still reads the thread when a source is available).
  - **Replace and send** overwrites whatever is in the box at that moment, including a draft edited after the choice notification was posted.
  - Any foreground app can fire an exported SENDTO / VIEW at `SmsHandlerActivity` while a scheduled job runs; that ends the job (`onOtherIntent`) and it becomes a reminder. Denial of service only, never a wrong send.
  - `allowBackup="true"` still backs up the WebView's data (GV cookies) under the default rules; only Send Later's files are excluded.
- **Stricter navigation allowlist** — `OriginGuard.isAllowedNavigation` (https + Google domains + `accounts.youtube.com`) replaced the old `contains("google.com")` check; Google sign-in through it still needs a device re-check.
- **WebView DOM selectors unchecked** — the Send Later bubble, thread-title and thread-list selectors in inject.js and the `itemId=t.+E164` thread URL shape have not been verified against live GV.
- **One-shot composer probe** — the launch-time `ForegroundUse` composer probe was deferred (needs `WebViewScheduledSender` to go async).
- Shizuku integration to replace ADB commands with in-app API calls
- `SmsDeliverService`/`SendStatusReceiver` for deeper SMS routing control

### Shipped (was backlog)

- **GBoard sticker support in WebView** — shipped in v1.3.0 (`RichContentWebView` + `OnReceiveContentListener` + JS file-input injection). The `feature/gboard-sticker-bridge` branch is fully merged into `main`; the branch name carries nothing extra.
- **Scheduled messaging with conditional send** — shipped in v2.0.0 as Send Later (`com.voice2sms.sendlater`), including the "auto-hold if the conversation changed" ladder, the draft-choice notification and GV failed-send ("Not sent") detection.

### Feature Backlog (Researched)

- **API Replay hybrid (protobuf-over-HTTP)** — Send SMS via GV's internal API using WebView session cookies. Faster, less brittle for auto-send. Keep WebView for auth + review mode.
- **Send Later follow-ups:**
  - Invisible RPC send (the API replay above) instead of the full-screen WebView, plus the RPC `ThreadEventSource` (`RpcThreadEventSource` is a stub today)
  - Group threads (tab and chip only appear on 1:1 threads)
  - Watch mirroring of the last call
  - Capture from Gemini, `@time` in the draft, and the share sheet
  - Optional server-side sender for when the phone is off or force-stopped
  - Read-only chip while Send Later is off (today the tab and chip disappear)
- **Emulator smoke test in CI** — run `tests/smoke/test-sms-intent.sh` against a `reactivecircus/android-emulator-runner@v2` Pixel on API 34 as a third job in `ci.yml` / `release.yml`. Adds ~4-5 min/run; skipped for now to keep PR feedback fast. Trade-off: real on-device smoke test stays manual until added.
