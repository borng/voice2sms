# Voice2SMS — Architecture & Technical Details

## Components

| File | Purpose |
|------|---------|
| `SmsHandlerActivity` | Transparent launcher + SMS/share intent router (exported). Parses `sms:`/`smsto:` URIs (including opaque variants with `&body=`). Honours `force_auto_send` only with a valid `AutoSendAuth` token; anything else opens review mode. |
| `AutoSendAuth` | Per-process random token proving a `force_auto_send` request came from this app. See [Auto-send authorization](#auto-send-authorization). |
| `AutoSendRateLimiter`, `AutoSendNotifier` | Global auto-send rate limit (10 sends / 60 s) and the "Sent via Google Voice" notification for the Gemini, Wear and RESPOND_VIA_MESSAGE paths. Never used by Send Later. |
| `GVoiceWebViewActivity` | Hosts the WebView, manages auth flow, injects JS on page load. `singleTask` launch mode for intent reuse. |
| `Voice2SmsApplication` | Application subclass holding singleton WebView. Configures async WebView startup and renderer warm-up (no URL prefetch: it races `loadUrl`). Registers the Send Later sender and runs `SendLaterEngine.onProcessStart`. |
| `GeminiSmsInterceptService` | AccessibilityService monitoring Gemini's SMS compose overlay. Caches phone+body, intercepts Send button (resource ID match), null-source clicks (with SENDTO dedup), and voice "Yes" confirm (watchdog timer). Also hosts the Send Later Google Voice overlay. |
| `SetupActivity` | First-install wizard: default SMS role, ADB commands for blocking Gemini carrier SMS, accessibility service enablement, then step 4 Send Later (notification access, exact alarms, notifications: button + live status each) and step 5 Battery (allow background use, `BatteryIndicator`, Samsung Never sleeping apps advice). Statuses refresh in onResume. |
| `SettingsActivity` | Preferences: set default SMS app, switch Google account, Gemini interception toggle, auto-send switch, Send Later category. |
| `inject.js` | Core JS injection — SPA detection, compose flow, chip creation, body fill, auto-send; Send Later module. |
| `fingerprint-mask.js` | Anti-fingerprinting overrides + dark mode CSS filter. |
| `SmsReceiver` | `SMS_DELIVER` receiver: reassembles multipart SMS, writes it to `content://sms/inbox` and posts the incoming-SMS notification (setting "Incoming SMS notifications"). |
| `MmsReceiver` | Stub `WAP_PUSH_DELIVER` receiver (required for default SMS app role); MMS is not handled. |
| `RespondViaMessageService` | `RESPOND_VIA_MESSAGE` (decline a call with a text): launches `GVoiceWebViewActivity` with `force_auto_send` and the `AutoSendAuth` token. |
| `wear.WearSmsListenerService` | Wearable Data Layer receiver for the watch companion; validates the payload and trampolines to `SmsHandlerActivity` with `force_auto_send` and the `AutoSendAuth` token. See GALAXY-WATCH-SMS.md. |

## Key Design Decisions

- **WebView singleton** — The WebView lives in `Voice2SmsApplication` (Application subclass), surviving Activity lifecycle. Warm starts reuse the already-loaded SPA.
- **Direct Angular chip creation** — Instead of simulating keyboard events (which fail `isTrusted` checks), inject.js calls Angular Material's `matChipInputTokenEnd` callback directly via Zone.js listener introspection. This creates recipient chips without any autocomplete dropdown interaction.
- **Warm start = page reload** — On subsequent intents (`onNewIntent`), the page is always reloaded to get a clean messages-list state. This avoids stale SPA navigation bugs at a cost of ~1s.
- **Default SMS app components** — Android requires an `SMS_DELIVER` receiver, a `WAP_PUSH_DELIVER` receiver and a `RESPOND_VIA_MESSAGE` service to appear in the SMS app picker. `SmsReceiver` and `RespondViaMessageService` are real implementations; `MmsReceiver` is still a stub.

## Auto-send authorization

`SmsHandlerActivity` is exported (it is the SENDTO / VIEW / SEND target), so any
app can put `force_auto_send=true` on an intent. Since 2.0.0 the flag counts only
when the intent also carries this process's `AutoSendAuth` token
(`com.voice2sms.extra.AUTO_SEND_TOKEN`, 32 random bytes as hex, made once per process):

- The three internal origins attach it next to the flag:
  `GeminiSmsInterceptService.fireSendIntent` (only when **Auto-send Gemini
  messages** is on; the SENDTO is package-scoped with `setPackage`),
  `WearSmsListenerService` and `RespondViaMessageService`.
- The two consumers, `SmsHandlerActivity` and
  `GVoiceWebViewActivity.wantsAutoSend`, check it with
  `AutoSendAuth.isAuthorized` (constant-time compare) and strip it from the
  intent after reading. Without it the message opens in review mode and no
  "Sent via Google Voice" notification is posted.
- The token is never logged and leaves the process only in explicit or
  package-scoped intents and `FLAG_IMMUTABLE` PendingIntents. After a process
  restart an old token stops matching, so an old notification tap opens review
  mode, never a send.
- Send Later never uses `force_auto_send` or the token
  (`SendLaterSourceSafetyTest` G2).

## inject.js Flow

1. **Poll for SPA** — Wait for `gv-side-nav` or `gv-thread-list` to appear.
2. **Check for existing conversation** — Scan the messages list for a matching phone number.
3. **New conversation** — Click the FAB (`button[aria-label*="new"]`), wait for compose view.
4. **Create recipient chip** — Primary: call `matChipInputTokenEnd` callback found at `input['__zone_symbol__matChipInputTokenEndfalse']`. Fallback: type char-by-char + click autocomplete dropdown.
5. **Fill body** — Set textarea value via native setter + InputEvent in Angular zone.
6. **Focus + keyboard** — JS `.focus()` + `.click()` + synthetic MouseEvent, then `V2SBridge.requestShowKeyboard()` calls `IMM.showSoftInput(SHOW_FORCED)` on the native side.
7. **Auto-send** — If `force_auto_send` is set and authorized (`AutoSendAuth`), click the send button after a jittered delay.

### Generation Counter

Each `voice2sms()` call increments `window._v2sGeneration`. All async callbacks check `isCancelled()` to bail out if a newer invocation has started, preventing race conditions from rapid-fire intents.

### Send Later Module

Appended after `sendImageToGV`. `SendLaterWebHooks.moduleScript()` returns it only while Send Later is on, so with the feature off no Send Later JS runs. `v2sSendLaterInit()` runs once per page load.

- **Tick** — `_v2sSlTick` handles thread changes, chip freshness, the thread-list scrape and layout. A 2 s timer (`V2S_SL_TICK_MS`) runs only while on with a 1:1 thread open (`itemId=t.+E164`), plus one-shot follow-ups at 2 s and 6 s.
- **Tab and chip** — tab shows when the composer has text; chip from `V2SBridge.sendLaterChipState(e164)`; placed so neither covers Send.
- **Capture** — `_v2sSlCapture` → `V2SBridge.requestSendLater(json)` with `{e164, displayName?, draftText, newest id/time}` (watermark EXACT with id + time, TIMESTAMP with time only). Repeat taps within 1.5 s are dropped.
- **Clear + Undo** — `window.v2sSendLaterClear(messageId, expectedDraft, label)` clears through the Angular-safe setter only if the composer still holds that draft on the same thread; Undo bar 10 s → `V2SBridge.requestSendLaterUndo(id)` (engine cancel; text restored only into an empty composer).
- **Thread read** — `window.v2sReadThread(e164, limit)` → `V2SBridge.onThreadRead(json)`; used by the send controller to verify.
- **Directory** — thread-list `{displayName, e164}` pairs → `V2SBridge.onThreadDirectory(json)` (stored only while on).
- **Refresh** — `window.v2sSendLaterRefresh(on)`, pushed from Java on `ACTION_STATE_CHANGED`.
- **Scheduled composer** — `v2sScheduledCompose(phone, body, autoSendDelay, token)` is the scheduled send's own flow (see Send Later → Due-time flow). An existing thread is opened by its URL; a new conversation uses `_v2sScheduledNewMessage`, never `voice2sms()`, whose list match on text could open another person's thread. It reports progress (`onScheduledTyped`, `onScheduledNoThread`, `onScheduledWrongPage`, `onScheduledDraftPresent`), asks before typing over a draft (`scheduledDraftStep`) and taps Send only on a `yes` from `scheduledClickState` / `scheduledClickStateAt` / `scheduledClickStateIn`, which also check that the page is still the job's thread. `v2sScheduledClear` removes our text after an unsent end; `v2sScheduledRestoreDraft` puts the user's draft back after Send both.

**Bridge trust.** Every Send Later and scheduled `V2SBridge` method:

1. takes a per-document **nonce** as its first argument (`web.BridgeNonce`).
   Java creates it in `onPageFinished` only for a trusted GV URL, injects it
   with the Send Later module and the scheduled composer into a top-frame
   global (`_v2sBridgeNonce`), and drops it in `onPageStarted` and when the
   renderer dies. A call without the current nonce is ignored. A
   `@JavascriptInterface` is reachable from every frame, and the main-frame
   URL Java sees can lag the committed document; the nonce closes both gaps,
   because no other document or cross-origin frame can read it;
2. checks `OriginGuard.isTrustedGvUrl(mainFrameUrl)`: https, exactly
   `voice.google.com`, port 443 or default, no user info. No
   `startsWith("https://voice.google.com")` checks remain.

Bridge methods: `requestSendLater`, `sendLaterChipState`, `requestSendLaterChipTap`, `requestSendLaterUndo`, `onThreadRead`, `onThreadDirectory`, plus the scheduled-composer methods above. Chip calls are also bound to the open thread (`ThreadUrl.isThreadOf`). Thread-directory pairs from `onThreadDirectory` help name matching but never resolve a recipient on their own (see Send Later → Components → Sheet).

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
2. Contact name (e.g., "Jane Doe")
3. Phone line (e.g., "Mobile . +12025550123") — matched by `\+\d{10,}` regex
4. Message body (e.g., "Hello") — first node after phone match

### Known Title Variants

Gemini uses different titles depending on the flow:
- "Text Message To [Contact]"
- "Sending a Text Message"
- "Sending Text Message To [Contact]"
- "Confirm Message to [Contact]"
- "Contact Not Found for Text"

All matched by checking for "Text", "SMS", or "Message" substring.

## Send Later

Scheduled send, in `com.voice2sms.sendlater` (v2.0.0). On by default on a new
install (`SendLaterSettings.DEFAULT_ENABLED`). The master switch
(`sendlater_enabled`) lives in its own SharedPreferences file,
`sendlater_switch`, which is excluded from backup and device transfer; always
read and write it through `SendLaterSettings.isEnabled` / `setEnabled`. A
`sendlater_switch_seen` marker in the backed-up default prefs without a local
switch value means the prefs came from a backup or transfer, so Send Later
starts **off** there; a fresh install has neither and starts on. The rule: judgment is collected when the message is
scheduled; at the due time only facts can make the app more careful, and only
a tap from the user makes it less careful.

### Components

| Component | Package / class | Role |
|---|---|---|
| Voice overlay | `voice.overlay.VoiceOverlayController`, `voice.logic.*` | Hosted by `GeminiSmsInterceptService`. On a 1:1 native Voice thread (screen signature HIGH: Voice package + `toolbar_title` + `send_message_text` + `send_message_button`) with a non-empty draft, shows the edge tab; shows the chip when the thread has pending/held messages. Positions from the `input_message_container` rect. Reads only the ids in `VoiceOverlayIds.R8_IDS`, re-acquired on every event, 250 ms debounce. Clears the draft with `ACTION_SET_TEXT("")` only after the record is verified persisted; Undo cancels and restores only into an empty box with the same title. |
| WebView hooks | `web.SendLaterWebHooks`, `web.BridgeNonce`, `web.OriginGuard`, inject.js module | Same tab and chip in the GV WebView. Capture → `V2SBridge.requestSendLater`. Every bridge method needs the per-document nonce and passes `OriginGuard.isTrustedGvUrl(mainFrameUrl)` (see inject.js Flow → Bridge trust); chip calls are bound to the open thread via `ThreadUrl.isThreadOf`. `SendLaterHost.evaluate` refuses on an untrusted page. |
| Sheet | `ui.SendLaterSheetActivity`, `ui.SheetViewModelLogic` | One sheet for both surfaces: recipient card, rolling presets (`domain.PresetSlots`), date/time pickers, "Hold if they text first", "If late". Schedule is disabled until an E.164 is chosen (`voice.logic.RecipientResolver`). A name that matches only in the scraped thread directory (no contact match) is `NEEDS_CHOICE`, never resolved silently, and the sheet opens unconfirmed. Persist → `SendLaterScheduler.registerAlarms` → `ACTION_SCHEDULED` (the capturing surface clears its draft). Edit mode: Update / Cancel message; edits go through the engine lock (`SendLaterEngine`), and a stale edit shows the not-editable state instead of finishing. Without a GV web session the record is saved at R3 (reminder only). |
| Store | `store.SendLaterDatabase` (Room `sendlater.db`, v2) | Tables `scheduled_message`, `watermark` (FK cascade; `orig*` columns keep the self-preemption baseline across snooze/edit), `thread_event`, `thread_directory`. `MIGRATION_1_2` is explicit and additive; no destructive fallback. `SendLaterRepository.schedule` is transactional and read back before returning, and dedupes an identical capture within 10 s. A record saved without a display name gets one from contacts (`store.RecipientNames.fill`, Android side `listener.AndroidContactNames`; never replaces a captured name, needs `READ_CONTACTS`). Every send start uses the guarded `markInFlight` (row version checked), so an edit between evaluate and send start makes the engine re-evaluate the stored row once. |
| Engine and alarms | `engine.SendLaterEngine`, `engine.SendLaterScheduler` | The only writer of status and Send Later notifications; all entry points serialized on one lock and one executor. Exact alarms (`setExactAndAllowWhileIdle`, inexact without permission) at T−5 (last call) and T (due), plus RETRY (network wait) and EXPIRE. `SendLaterBootReceiver` re-arms on boot / time / time-zone change / exact-alarm permission change. `onProcessStart` resolves IN_FLIGHT rows first, then the expiry sweep, data purge and re-arm. |
| Listener | `listener.SendLaterNotificationListener` | Reads Google Voice (and voice2sms incoming-SMS) notifications. `LiveConversationKeys` drops anything not matching a SCHEDULED/HELD/IN_FLIGHT conversation before any DB call. Stores `{conversationKey, direction, kind, time, textHash}` (no text) and triggers early hold. `NotificationThreadEventSource` is the engine's reply source. While `READ_CONTACTS` is granted, `ContactsChangeWatcher` observes contacts and rebuilds the key gate 2 s after the last change (names on records come from contacts, and GV reply notifications from saved contacts carry only the name). |
| Send controller and cover | `send.WebViewScheduledSender`, `send.ScheduledSendController`, `send.ScheduledCompose`, `send.LockCover`, `send.RegularSend`, `send.ClaimWindow` | Sender: queue slot (`ScheduledSendQueue`), mark IN_FLIGHT with a body hash, launch `GVoiceWebViewActivity` with `EXTRA_SCHEDULED_MESSAGE_ID` and a one-shot token. Controller: runs the scheduled branch under the cover, drives the scheduled composer (`v2sScheduledCompose`), verifies, reports `SendLaterEngine.onSendOutcome`. `RegularSend` marks a running regular compose / auto-send (Gemini, Wear, RESPOND_VIA_MESSAGE, a SENDTO review; at most 60 s): a due launch waits for it (polling, up to 20 s), then ends as a user-busy reminder; it never loads a page over it. |
| Upcoming and Settings | `ui.UpcomingActivity`, `ui.SendLaterSettingsController` | Upcoming: Pending / Held / Recent (30 d) with Change time, Cancel, Send now (Cancel only while off). Settings: one "Send Later" category with live status rows (switch, slots, policies, exact alarm, notifications, accessibility, notification access, contacts, battery, Upcoming). |
| Battery guard | `battery.BatteryHealth`, `BatteryWarning` | Detects background restriction / battery optimization; Settings row opens the right screen and shows `BatteryIndicator` (green check / amber warning) as its icon, as does the wizard's Battery step; Samsung "Never sleeping apps" is advice only (no API). A notification at most once a day when scheduling while limited, or when a due alarm fires more than 2 min late. |

Efficiency (R40): no polling, no foreground service while idle, no wakelocks
beyond the send itself, two exact alarms per pending message. The overlay only
does work for Google Voice events; the WebView module is not installed and the
2 s page tick does not run while Send Later is off.

### Due-time flow

1. **Alarm.** DUE → `SendLaterEngine.onDueAlarm` (reports lateness to the
   battery guard) → nothing if Send Later is off. An R3 row whose GV session now
   exists is upgraded to R1.
2. **Thread check.** Merge the available `ThreadEventSource`s (listener store;
   the on-screen native thread while it is open) and read events since the
   watermark. No available source ⇒ `UNVERIFIED`.
3. **Ladder.** `domain.LadderEvaluator.evaluate` (pure) →
   SEND / HOLD / REMIND / CANCEL / WAIT / EXPIRED:
   - `REPLIED` / `REACHED_OUT` ⇒ HOLD if "Hold if they text first", else SEND;
   - `SELF_PREEMPTED` (similar outbound text) ⇒ CANCEL;
   - `OUTBOUND_ONLY` ⇒ SEND or HOLD per "If I text them first";
   - `UNCHANGED` ⇒ SEND; `UNVERIFIED` ⇒ HOLD;
   - past the lateness budget ⇒ REMIND; no session ⇒ REMIND (Sign in);
     no network ⇒ WAIT, then REMIND; still SCHEDULED or HELD 24 h past due ⇒ EXPIRED.
   Lateness budget (`LatenessBudget`): automatic = 10 min (scheduled < 1 h
   ahead), 30 min (≤ 1 day), 2 h (longer); or 30 min / never / 24 h by choice.
4. **Send.** SEND ⇒ `WebViewScheduledSender.start`: `ScheduledSendQueue` slot
   (2 s spacing, 50/day; refused ⇒ reminder), `markInFlight(bodyHash)`, then
   `LaunchPlanner`: DIRECT `startActivity` when the accessibility service is
   bound, else a full-screen intent when locked or dark, else CANNOT_SHOW.
   `LaunchRecheck` re-reads the row before a queued launch (late beyond budget
   + 10 s slack ⇒ CANNOT_SHOW late).
5. **Compose.** `ScheduledSendController` drives `v2sScheduledCompose`
   (`ScheduledCompose`): an existing thread is loaded by its URL (at most 2
   loads) and given up to 15 s to settle (`THREAD_SETTLE_MS`); a new
   conversation uses the scheduled new-message flow, never `voice2sms()`.
   - **Only the job's thread.** Any page other than the job's own thread (or
     the single matching chip of a new conversation) ends the job unsent
     (`could_not_send`, detail `wrong_thread`). The click ask re-checks the
     page, so the script never types or taps Send in another conversation.
   - **Draft in the way (D32).** If the box holds text that is not ours
     (`domain.DraftChoice`; a draft equal to ours after normalization counts
     as ours), nothing is typed: the engine reminds with the draft-choice
     notification (`SendLaterNotifications.draftInTheWay`, `DraftNotice`):
     **Replace and send** (a user-tap send that clears the box and types ours),
     **Keep draft** (no send; the row stays in Upcoming, reminded) and **Send
     both** (sends ours, then `v2sScheduledRestoreDraft` puts the draft back;
     if that fails a "draft was not put back" notice carries it). The draft
     lives only in memory (`DraftStash`) and in that notification; it is
     never stored or logged. Send now / Send anyway taps use the same ask.
6. **Verify.** The controller polls `readThread` plus a composer probe
   (`SendWatch`). The first read with the text still unsent is the baseline;
   only a new outbound bubble with the body hash beyond it gives
   `SENT_VERIFIED`, and only once GV no longer shows it as sending and it has
   settled 3 s with the composer empty (or carries a server time). A failed
   bubble never matches. For 30 s after the verdict the controller keeps
   reading that thread (`SentFollowUp`); a later "Not sent" moves the row back
   to HELD(UNVERIFIED) (allowed up to 90 s after verification). Nothing within
   the 45 s verify window ⇒ `SENT_UNVERIFIED`. A failure before Send was
   tapped, including after typing, ⇒ `CANNOT_SHOW` with a detail
   (`no_session`, `daily_cap`, `user_busy`, `locked`, `late`, `stopped`,
   `wrong_thread`, else `could_not_send`). An inbound between the check and
   the send adds "replied just as your message sent".
7. **Outcome.** The engine applies it: SENT_VERIFIED ⇒ Sent notification;
   SENT_UNVERIFIED, or IN_FLIGHT found at process start ⇒ HELD(UNVERIFIED) +
   "may have already sent". **No automatic retry**: Send anyway (only a
   self-preemption check) is the only retry. CANNOT_SHOW ⇒ reminder that opens
   review mode with the text pre-filled (no auto-send).

Early hold: a recorded inbound event for a pending conversation (listener or
on-screen bubbles) holds the message at once when "Hold if they text first" is on, cancels its alarms and posts the
hold notification.

### Lock-screen model

- Every claimed scheduled job shows the opaque `LockCover` ("Sending scheduled
  message…", masked number) for its whole run, locked or not.
  `showWhenLocked` / `turnScreenOn` / keep-screen-on are set only while the
  cover is up and cleared before it comes down; the keyguard is never
  dismissed. The full-screen notification carries no text or name.
- While covered: touch, key and back events are consumed, no keyboard shows,
  and navigation is limited to the target thread. 60 s hard timeout and "a
  pause ends the job" (1 s grace) for every job.
- On an unlocked phone the cover offers **Stop** before typing starts; Stop
  ends the current and queued jobs with a "You stopped the send" reminder.
- An unclaimed launch (stale intent, renderer-crash relaunch) clears the flags
  and, while locked, finishes without loading the page.
- Every Send Later notification action needs an unlock. The sending ones
  (Send now, Send anyway, Replace and send, Send both, and the review-and-send
  body tap) are `PendingIntent.getActivity` to the not-exported, no-UI
  `SendLaterUnlockActivity` on all API levels (plus
  `setAuthenticationRequired(true)` on 31+; from the lock screen it requests
  keyguard dismissal first; not show-when-locked; refuses while locked on
  API 24–25). Cancel, Snooze 1 h and Keep draft are
  authentication-required broadcasts to `SendLaterActionReceiver` on API 31+,
  and go through `SendLaterUnlockActivity` below that. All PendingIntents are
  `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT` with per-record + action request
  codes. Notifications are `VISIBILITY_PRIVATE`; the draft-choice one has a
  public version that says only "Draft in a conversation".
- A user tap grants that message a short `UserTapGrant` (10 s), which lets
  `LaunchPlanner` start the send directly on an unlocked, lit phone even
  without the accessibility service.
- An exported SENDTO / VIEW to `SmsHandlerActivity` that arrives while a
  scheduled job runs ends that job (`ScheduledSendController.onOtherIntent`);
  it becomes a reminder, never a send (accepted residual, see CLAUDE.md).
- If the job cannot be shown (screen lit and unlocked without the
  accessibility service, the user was using voice2sms in the last 60 s or has
  text in the composer), the result is a reminder, not a send.

### Data at rest

- `sendlater.db` (app-private) holds the scheduled text, number, display name
  and watermark. Terminal rows and their watermarks are deleted 30 days after
  they finish; `thread_directory` name/number pairs after 90 days unseen
  (`DataRetention`, at most daily on process start, whether or not Send Later
  is on). Listener events keep only a text hash, for live conversations, 7 days.
- `sendlater.db` (+ `-wal`, `-shm`, `-journal`) and the `sendlater_*`
  SharedPreferences files, including the master switch (`sendlater_switch`),
  are excluded from cloud backup and device transfer
  (`sendlater_backup_rules.xml`, `sendlater_data_extraction_rules.xml`,
  checked by `BackupRulesTest`). Slot times and the hold / outbound / clear-draft
  defaults stay in the default prefs and are backed up.
- Recipients are shown as the name, else the number formatted as
  `(NXX) NXX-XXXX` for NANP numbers (`ChipLabel.recipient` / `formatNumber`),
  in notifications, the sheet and Upcoming; logic always keeps the E.164.
- No message text, draft or name in release-build logs (`SendLaterSourceSafetyTest`
  G3, `PrivacyLoggingSourceTest`); URL logs drop the query (`withoutQuery`).
  Debug dumps carry a text hash and length and the last 4 digits only.
- Every app-internal broadcast is `setPackage`'d, runtime receivers are
  `RECEIVER_NOT_EXPORTED`, and all PendingIntents are `FLAG_IMMUTABLE`.
