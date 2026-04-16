# Galaxy Watch SMS Interception — Research & Plan

**Status:** Research complete. No code yet.
**Date:** 2026-04-16
**Test device:** Galaxy Watch 6 (SM-R960), Wear OS 5 / Android 14, **BT-only (no eSIM)**, paired to Pixel 10 Pro Fold running Voice2SMS v1.4.0

## The Problem

When the user sends an SMS from the Galaxy Watch via Google Wearable Assistant ("Hey Google, text Alice …"), **the message silently fails to send.** That is the actual bug Voice2SMS needs to solve for this flow.

This is separate from the earlier hypothesis of a Pixel-Watch-with-Gemini path — confirmed via diagnostics that the device is a Samsung Galaxy Watch + Google Wearable Assistant + Samsung Messages.

## Packages on the Watch (empirically verified)

```
com.google.android.wearable.assistant   ← handles voice command
com.google.android.apps.googlevoice      ← GV app is installed on watch (important)
com.samsung.android.messaging            ← de-facto default SMS app on watch
com.geminiman.wearosmanager              ← third-party; unrelated
```

## Diagnostic Method

Four captures total. First three were phone-only; fourth paired phone + watch logcat.

| Capture | Device | Watch action | Outcome |
|---|---|---|---|
| 1 | phone | "Hey Google, text …" → sent | Full Samsung WMP plugin flow → carrier SMS **went out** |
| 2 | phone | prepopulated draft, not sent | No phone-side activity |
| 3 | phone | prepopulated draft → Send | Full Samsung WMP flow → carrier SMS **went out** |
| 4 | phone + watch | voice cmd → Send (×2) | **NO phone-side WMP activity, NO carrier SMS, NO GV send call. Message silently fails.** |

Captures 1 and 3 used the watch's Samsung Messages path that successfully bypassed Voice2SMS. Capture 4 used a different path (or no path) and failed outright.

## What Actually Happens in the Failure Case (Capture 4)

### Watch-side flow (from `/tmp/dual-watch.log`)

```
11:44:29 com.google.android.wearable.assistant launches
         ← VOICE_ASSIST intent from uid 1000 (system)

11:44:57 Google Assistant fires:
         act=android.intent.action.SENDTO
         dat=smsto:+1…
         → com.samsung.android.messaging/.ui.SendToIntentReceiver

11:44:57 Samsung Messages receives SENDTO → opens Composer
         AWM/DefaultMessageManager:
           mRoleManager.isRoleAvailable is FALSE         ← Wear OS doesn't expose ROLE_SMS
           defaultSmsApplication is null, but has SMS, return true
           isWatchDefaultSmsApp : true                   ← Samsung Messages self-elects
         ORC/TelephonyUtils: isLteDevice: false          ← BT-only watch confirmed

11:44:58 ComposerActivity.onCreate() loads conversation
11:44:58 AWM/ComposerPresenter: Feature Rcs is Disabled
         [user hits Send on watch — no new log event]

… silence …

No SmsManager call. No WMP channel opened (no w_apps_message_byte_from_watch).
No BluetoothMap outbound. No Google Voice send attempt on the watch.
The send just dies.
```

### Phone-side flow (from `/tmp/dual-phone.log`)

```
(no WMP/, no SendMessageReq, no SmsSingleRecipientSender, no carrier radio)

11:45:07  Google Voice on phone started by FCM push:
          code:PUSH_MESSAGING, c2dm.intent.RECEIVE
          ← this is an UNRELATED inbound sync from GV servers, not our send
11:45:09  GV posts a notification; Samsung's Notification4Watch6_NB forwards to watch
```

**The GV activity on the phone was incoming/sync traffic coincidentally timed around the user's send attempt — not a response to the send itself.**

## Why It Fails

Samsung Messages on Wear OS has three conditions it tries to satisfy, and all three fail in this configuration:

1. **`RoleManager.isRoleAvailable(ROLE_SMS)` returns FALSE on Wear OS.** There is no standard Android default-SMS-app role on Wear OS. This kills Codex Idea #2 entirely — we cannot install Voice2SMS (or any app) as a default SMS handler on the watch.

2. **`isLteDevice: false`.** The watch has no eSIM/cellular radio, so direct carrier send is impossible.

3. **Samsung's WMP companion channel did not fire.** In captures 1 and 3 (same watch, same phone), the WMP `w_apps_message_byte_from_watch` channel fired and the Samsung plugin on the phone sent via carrier. In capture 4 it didn't. Reason unknown — possibly a state difference (BT connection quality, screen-on state, time since last sync) or possibly Samsung's logic routes differently when it sees the phone's default SMS app is *not* Samsung Messages (`default sms is not samsung's`, from capture 3).

Net: **Samsung Messages composer shows a Send button on the watch, the user taps it, the message is silently dropped.**

## Google Voice on the Watch — Works Standalone, But Unreachable from Assistant

The Google Voice app **is installed on the watch** (`com.google.android.apps.googlevoice`) and works fine when launched directly — open it, pick a contact, compose, send. GV handles its own network path over the BT-tethered internet connection. That part is not broken.

**But GV is unreachable from the Assistant's voice flow.** Verified via `dumpsys package com.google.android.apps.googlevoice` and `cmd package query-activities` on the watch:

| Intent | Handlers on watch |
|---|---|
| `ACTION_SEND` + text (share sheet) | Google Voice ✓ (but no phone-number context) |
| `ACTION_VIEW` + `tel:` | Google Voice ✓ (for calling, not SMS) |
| `ACTION_DIAL` + `tel:` | Google Voice ✓ |
| `ACTION_VIEW` + `https://voice.google.com/calls` | Google Voice ✓ (deep link) |
| **`ACTION_SENDTO` + `smsto:` / `sms:`** | **Google Voice ✗ — no intent-filter** |

The only `SENDTO smsto:` handlers on the watch are:
1. `com.samsung.android.messaging/.ui.SendToIntentReceiver` — Samsung Messages, **wins the resolver** (system priv-app at `/system/priv-app/SamsungMessagesWatch/`)
2. `com.google.android.apps.messaging/.send.SendToProxyActivity` — Google Messages Wear (`BugleWearRelease`)

**Google Voice doesn't advertise itself as an SMS compose target anywhere.** So when Google Assistant fires `SENDTO smsto:+1…`, the system resolves to Samsung Messages first, Google Messages Wear second — never to GV. Nothing in the Wear OS SDK or system lets us redirect that.

This is the missing architectural piece: **the watch has a working GV send path, and a broken Samsung Messages path, with no glue between them.** Voice2SMS needs to be that glue.

## Captures 1+3 ≠ Capture 4

| | Captures 1 & 3 (worked) | Capture 4 (silently failed) |
|---|---|---|
| Watch voice flow | Same (VOICE_ASSIST → SENDTO) | Same |
| Samsung Messages composer opens | Yes | Yes |
| WMP channel fires phone-side | **Yes — JSON sent to phone** | **No** |
| Samsung plugin sends via carrier | Yes | No |
| Voice2SMS involved | No (bypassed) | No (never reached) |
| Actual SMS sent | Yes (carrier, bypassing Voice2SMS) | **No** |

Both outcomes are broken from Voice2SMS's point of view. Capture 4's failure is the one the user is experiencing in practice.

## Critical Finding

The earlier capture 3 (`WMP/MessageDefaultChecker: default sms is not samsung's, return false`) remains true: **Samsung's plugin explicitly notes that the phone's default SMS app isn't Samsung's, then either sends via its privileged bypass (captures 1/3) or gives up entirely (capture 4).** Which branch fires appears non-deterministic from our observations.

The Samsung plugin uid on phone: `10378`, `SEND_SMS=allow`. The plugin uses a privileged IMS send path that bypasses `ROLE_SMS` enforcement.

## Interception Options (re-ranked based on capture 4)

### 🥇 Option 1 — Wear OS Voice2SMS companion app  **(now the architectural requirement)**

This isn't a "nice" option — it's the only thing that works, because no existing app on the watch bridges Assistant → Google Voice for the SMS case.

Ship a minimal Wear OS APK that:

1. **Declares the intent-filter no one else covers:**
   ```xml
   <activity android:name=".WearSmsRouterActivity" android:exported="true">
     <intent-filter>
       <action android:name="android.intent.action.SENDTO" />
       <category android:name="android.intent.category.DEFAULT" />
       <data android:scheme="smsto" />
       <data android:scheme="sms" />
     </intent-filter>
   </activity>
   ```
   This makes Voice2SMS show up in the watch's `SENDTO smsto:` picker alongside Samsung Messages and Google Messages Wear. The user sets it as default once and the Assistant's intent routes here from then on.

2. **Parses `{phone, body}` from the intent** (same logic as `SmsHandlerActivity` on phone).

3. **Forwards to phone via Wearable Data Layer (`MessageClient`)** on path `/voice2sms/send` with JSON payload. No local send attempt on the watch — the watch can't auth to GV anyway in a lightweight way, and the phone already has a working send path.

4. **Phone-side `WearableListenerService` receives the message**, invokes the existing GV send pipeline. Use the existing `SmsHandlerActivity` → `GVoiceWebViewActivity` chain, or the future API Replay path if/when that ships.

5. **Watch acks the result** — show a quick "Sent" / "Failed" toast on the watch, possibly via a complication or tile.

Pointers:
- Watch Gradle module: `wear/` (new), targetSdk for Wear OS 5 (API 34)
- Shared core: factor the SMS parse logic out of `SmsHandlerActivity` into a reusable `SmsIntentParser`
- Phone-side listener: new `WearSmsListenerService extends WearableListenerService` in `com.voice2sms`; needs a `<service>` entry in manifest with `<intent-filter>` on `com.google.android.gms.wearable.BIND_LISTENER`
- Status channel: use `MessageClient.sendMessage` back on path `/voice2sms/ack`

**Pros:** Only path that actually fixes the silent-failure case. Works regardless of BT link state or Samsung's WMP mood. Doesn't require root/Shizuku. Reuses the existing phone-side send pipeline unchanged.
**Cons:** Requires a second APK (Wear). Must either ship through Play Console Wear or sideload. First-time UX: user must pick Voice2SMS in the SENDTO resolver and set "always." Onboarding flow should guide this the same way `SetupActivity` already guides the phone-side setup.

### Sub-question for Option 1 — probed and resolved

**Can we launch GV-on-watch's compose UI with pre-filled recipient+body, skipping the phone round-trip?**

Partial yes, but not worth chasing. Probes:

| Intent | Result |
|---|---|
| `am start -a SENDTO -d smsto:… com.google.android.apps.googlevoice` | `Activity not started, unable to resolve` — no handler |
| `am start -a VIEW -d sms:…?body=… com.google.android.apps.googlevoice` | `Activity not started, unable to resolve` — no handler |
| `am start -a SEND -t text/plain --es android.intent.extra.TEXT … com.google.android.apps.googlevoice` | Accepted → GV opens `HomeActivity` with internal action `compose_inbound_intent` |
| `am start -a compose_inbound_intent -n …/com.google.android.apps.voice.home.HomeActivity --es phone_number …` | Accepted, delivered to running instance → GV then showed `ExpressSignInActivity` |

**Findings:**

- **`compose_inbound_intent`** is GV's private action for triggering compose. Our Wear APK could fire it with an explicit component.
- **GV requires sign-in on the watch**, and the session isn't always live. Our probe triggered the sign-in flow instead of opening compose. User's intuition that "GV works standalone" was likely from when the watch had a fresh session; it's not reliable as a critical path.
- **Extras schema is undocumented.** We tried `phone_number`, `address`, `android.intent.extra.TEXT`. No way to know which ones GV actually reads without decompiling the watch APK at `/data/app/~~…/com.google.android.apps.googlevoice-…/base.apk`.
- **Even if it works, it's not headless.** GV's compose UI would show on the watch; user still taps Send. That's acceptable UX but doesn't remove any friction vs. the Data Layer path.

**Decision: skip the GV-invocation optimization.** The Wear APK forwards `{phone, body}` to the phone via Wearable Data Layer, and the phone drives the existing GV WebView send. That path is already known to work end-to-end and reuses all existing code. The GV-on-watch route is a speculative optimization with auth fragility and reverse-engineering cost; not worth prioritizing.

### 🥈 Option 2 — Phone-side WearableListenerService on the WMP channel

Register a `WearableListenerService` that listens on path prefix `w_apps_message_byte_from_watch/*` — same channel Samsung's plugin uses. Race-parse the JSON payload before Samsung's plugin consumes it.

**Unknown:** Whether a third-party app can receive a channel the watch opens targeted at Samsung's node. Needs empirical PoC. If it works, this fixes captures 1 and 3 cleanly.

**Doesn't fix capture 4** — the channel never opens in that case.

### 🥉 Option 3 — Block Samsung plugin + notification-based recovery

```bash
pm revoke com.samsung.wearable.watch6plugin android.permission.SEND_SMS
appops set com.samsung.wearable.watch6plugin SEND_SMS ignore
```

Then observe `content://com.samsung.wearable.watch6plugin.messagecompanion/sms` via `ContentObserver`. When a failed row appears, extract phone+body, reroute via Voice2SMS → GV.

**Caveat:** Samsung's plugin may be platform-signed and able to bypass `pm revoke`. Needs verification:
```bash
dumpsys package com.samsung.wearable.watch6plugin | grep -iE "flag|signer|sharedUserId"
```

**Doesn't fix capture 4** — nothing to recover if the plugin never fires.

### 🪦 Option 4 (dead) — Voice2SMS as default SMS app on watch

Confirmed impossible: `RoleManager.isRoleAvailable(ROLE_SMS)` returns **FALSE** on Wear OS 5 (Android 14, SM-R960).

## Recommended Path

Option 1 is the only one that reliably fixes capture 4 (the user's actual failure mode). Options 2 and 3 are worth pursuing as belt-and-suspenders for captures 1/3 (where Samsung sends via carrier and we'd rather route it through GV).

Implementation order:
1. **Probe Samsung plugin privilege** (5 min): confirms whether Option 3 is viable.
2. **Build minimal Wear companion** (Option 1): empty APK with SENDTO intent filter + MessageClient ping to phone. Validate that the watch's SENDTO picker shows Voice2SMS.
3. **Wire up the phone-side receiver**: new `WearableListenerService`, reuse existing GV send pipeline.
4. **Add the ChannelClient probe** (Option 2 PoC): listen on `w_apps_message_byte_from_watch/*` — if it receives data, we get a bonus interception point.

## Open Questions

1. **Why did captures 1/3 succeed via WMP but capture 4 silently fail?** Some state difference we haven't pinned down — BT link state, Samsung Messages version, recipient-in-contacts vs not, etc.
2. **Is the Samsung plugin platform-signed?** Determines Option 3 feasibility.
3. **Can a third-party app listen on a Samsung-targeted WearableChannel path?** Determines Option 2 feasibility.
4. **Does Wear OS actually show third-party apps in the SENDTO smsto: picker?** Determines Option 1 UX.
5. ~~**What does Google Voice app on the watch do when you launch it directly and compose a message?**~~ ✅ **Resolved:** GV-on-watch sends fine standalone, but declares no `smsto:` intent-filter, so Assistant's `SENDTO` can never route to it. This is precisely why Option 1 is needed.

## Files Referenced

- Existing Gemini block pattern: `app/src/main/java/com/voice2sms/GeminiSmsInterceptService.java`
- Default SMS flow: `app/src/main/java/com/voice2sms/SmsHandlerActivity.java`
- Setup wizard (would add Wear companion install step): `app/src/main/java/com/voice2sms/SetupActivity.java`

## Concurrent Small Item — Settings build-stamp

Fold into the phone-side Develop phase alongside the listener work. Supports bug-report triage when users send logs.

- **`app/build.gradle`** — add `buildConfigField` entries: `GIT_SHA` (from `git rev-parse --short HEAD`, fallback `"unknown"`) and `BUILD_DATE` (ISO `yyyy-MM-dd`).
- **`SettingsActivity.java`** lines 123-124 — extend the existing `versionText` to append `· <GIT_SHA> · <BUILD_DATE>`. Stays in the preference summary row (subtle gray text, existing long-press-to-copy pattern preserved).
- **Optional** `SourceSafetyTest` guard: `GIT_SHA` and `BUILD_DATE` must remain in `BuildConfig`.
- ~15 LOC total. No change to the listener scope.

## Phone-side Listener — Implemented Protocol (v1)

The phone-side `WearSmsListenerService` is in the codebase at
`app/src/main/java/com/voice2sms/wear/`. This is the contract the Wear APK
must match.

### Wire protocol

**Watch → Phone (request)**
- Primary (durable): `DataClient.putDataItem(...)` at path
  `/voice2sms/requests/<requestId>`
- Hot-path (low latency): `MessageClient.sendMessage(nodeId, path, bytes)` at
  the same path prefix. Optional; DataClient alone is sufficient.
- Payload: UTF-8 JSON
  ```json
  {"phone":"+15551234567","body":"hello","requestId":"uuid-or-similar"}
  ```
- `phone` required — regex `^\+?[0-9]{7,15}$`
- `body` required — ≤ 1600 chars after sanitization (phone strips ASCII
  control chars except `\t \n \r`, DEL, and Unicode bidi overrides
  `U+202A-202E`, `U+2066-2069`)
- `requestId` required, **non-optional** — regex `^[A-Za-z0-9_-]{1,64}$`.
  Used for dedup (5-minute seen-set) and ack correlation.

**Phone → Watch (ack)**
- `MessageClient.sendMessage(sourceNodeId, "/voice2sms/ack", bytes)`
- Payload: UTF-8 JSON
  ```json
  {"requestId":"...","status":"dispatched|invalid_payload|duplicate|rate_limited","reason":"..."}
  ```
- **Ack semantics (important for watch UX):**
  - `dispatched` = handed to GV WebView pipeline with `force_auto_send=true`.
    Does NOT mean "delivered to carrier". Watch should show "Sending…" and
    rely on GV conversation sync for final confirmation.
  - `invalid_payload` = validator rejected; `reason` contains a short code
    (e.g. `invalid_phone`, `body_too_long`). Watch should surface this.
  - `duplicate` = `requestId` already seen within 5 min. Likely a safe retry;
    no action needed.
  - `rate_limited` = per-node cap exceeded (10 requests / 60 s). Watch should
    back off.
- Missed acks are recoverable: DataClient items persist until the phone
  consumes them, so the watch can resend after timeout with a **new**
  `requestId` to force re-dispatch, or reuse the same `requestId` to probe
  for a duplicate ack.

### Lifecycle & consumption

- Phone deletes the DataClient item after handling it, using a **wildcard
  authority** URI (`wear://*/voice2sms/requests/<id>` with
  `FILTER_LITERAL`). This propagates the delete to the watch so the item
  doesn't re-sync on reconnect.
- Dedup seen-set is process-lifetime only. After phone process death the
  window resets; idempotent `requestId` handles the rare duplicate that
  creates.

### Watch APK must handle

- **Ack timeout**: if no ack within ~5 s, treat as transient; retry via
  DataClient (it's durable). Don't spam MessageClient.
- **`invalid_payload` response**: show the `reason` or a localized
  equivalent. Don't retry the same payload.
- **`rate_limited`**: cooldown for ≥ 60 s before next attempt.
- **Post-ack cleanup** (optional): watch can call
  `DataClient.deleteDataItem` on its own replica after receiving any ack,
  though the phone-side wildcard delete already handles this.

### Phone-side behavior notes (Codex debate findings, resolved)

- Watch-origin intents carry `force_auto_send=true` via the trampoline
  intent, so `SmsHandlerActivity → GVoiceWebViewActivity` will auto-send
  through GV without another user confirmation.
- On Android 14+ a background `startActivity()` from the listener can be
  blocked by BAL restrictions. The listener always posts a
  `CATEGORY_MESSAGE` / `PRIORITY_HIGH` notification as a fallback — user
  taps it to recover. Requires `POST_NOTIFICATIONS` runtime permission on
  API 33+ (declared in manifest; `SetupActivity` should request it the
  first time the user enables watch integration).
- Security relies on GMS application-id scoping: only a Voice2SMS build
  signed with the same keystore on the watch can write to
  `/voice2sms/requests/*` on the phone. Cross-app forgery is not possible
  via the public Wearable Data Layer API.

### Source-safety guards enforcing the above

- `wearListener_isIntentTrampoline_noWebViewCoupling` — prevents
  `wear/*.java` from referencing `GVoiceWebViewActivity`, `V2SBridge`,
  `Voice2SmsApplication`, `setWebView`, `getWebView`.
- `wearListener_requestId_mandatoryWithDedup` — `REQUEST_ID_RE`,
  `missing_requestId`, `STATUS_DUPLICATE`, `SEEN` + `putIfAbsent` all
  required.
- `wearListener_alwaysPostsNotificationFallback` — notification channel,
  `PendingIntent.getActivity`, `POST_NOTIFICATIONS`, `checkSelfPermission`
  all required.
- `wearListener_setsForceAutoSend` — `force_auto_send` must be set.
- `wearListener_consumeDataItem_wildcardAuthority` — wildcard
  `authority("*")` + `FILTER_LITERAL` required.
- `settings_versionStamp_includesShaAndDate` — `BuildConfig.GIT_SHA` and
  `BUILD_DATE` must be referenced in `SettingsActivity`.

## Diagnostic Logs (transient, not checked in)

Phone-side captures: `/tmp/watch-sms-phone{,2,3}.log`, `/tmp/dual-phone.log`
Watch-side capture: `/tmp/dual-watch.log`

Critical log lines:
- **Successful WMP path** (capture 3, phone): line 2366 — full JSON payload `{"action":"SendMessageReq",...,"type":"sms","recipients":"+1..."}`
- **`MessageDefaultChecker` bypass** (capture 3, phone): line 2397
- **Radio IMS send** (capture 3, phone): line 2442 — `RILC_REQ_AIMS_SEND_SMS`
- **SENDTO intent on watch** (capture 4, watch): line 4983 — `act=android.intent.action.SENDTO dat=smsto:...`
- **ROLE_SMS unavailable on Wear OS** (capture 4, watch): line 5034 — `mRoleManager.isRoleAvailable is FALSE`
- **BT-only confirmation** (capture 4, watch): line 5021 — `ORC/TelephonyUtils: isLteDevice: false`
- **Silent failure** (capture 4): no SmsManager call, no WMP channel, no GV send — composer opens, Send tapped, message dies
