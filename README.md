# Voice2SMS

Route all Android SMS through Google Voice — from any app, Gemini, or your Wear OS watch — and schedule texts to send later.

## New in 2.0

- **Send Later.** Schedule a text from the Google Voice app or from Voice2SMS's
  Google Voice screen, and it goes out through Google Voice at the time you pick.
  It holds the text if they reply first, and it never sends late without asking.
  See [Send Later](#send-later).
- **A draft in the way.** If the conversation already holds a draft you typed when a
  scheduled text is due, nothing is typed over it. A notification asks you:
  **Replace and send**, **Keep draft** or **Send both**.
- **Names and numbers.** When you schedule from a number, the name is filled in
  from your contacts (with the Contacts permission). A number without a name
  is shown formatted, like (202) 555-0123.
- **A restored phone starts with Send Later off.** The Send Later switch is kept
  out of Android backup and device transfer, so a phone restored from backup
  never starts sending texts by itself.
- **Lock-screen actions need an unlock.** On the lock screen, every Send Later
  notification action (Send now, Send anyway, Cancel, Snooze 1 h, Keep draft,
  Replace and send, Send both) asks you to unlock first.
- **Auto-send only from Voice2SMS's own paths.** Gemini, the Wear companion and
  quick reply from a call (RESPOND_VIA_MESSAGE) still auto-send. Any other app that
  asks for an auto-send gets review mode: the message opens filled in, and
  nothing is sent until you tap Send.

See [CHANGELOG.md](CHANGELOG.md) for every release.

## The Problem

If you use Google Voice as your primary number, texting is broken on Android. Tapping a phone number, sharing text, asking Gemini to "send a text," or using Google Assistant on your watch — all fire standard SMS intents that go to your carrier number, not Google Voice. There's no way to set Google Voice as the default SMS handler.

## The Solution

Voice2SMS registers as the default SMS app and intercepts all SMS intents. Instead of sending via carrier, it opens Google Voice's web UI in an embedded WebView and programmatically composes the message — creating the recipient chip, filling the body, and optionally auto-sending.

### What it intercepts

- **Tapping a phone number** in any app
- **Share > SMS** from any app
- **"Hey Google, text Rachel"** from Gemini on your phone
- **"Hey Google, text Rachel"** from Google Assistant on your watch (via Wear OS companion)
- **Quick-reply text when you decline a call** (`RESPOND_VIA_MESSAGE`)
- **Gemini's Send button and voice confirmations** (with optional AccessibilityService)

Gemini (with **Auto-send Gemini messages** on), the watch and call quick-reply
send on their own. Every other app's message opens in review mode, filled in,
and waits for you to tap Send — even if that app asks for an automatic send.

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

Opening Voice2SMS from the launcher shows its home page. At the top, a
**Scheduled messages** card lists the next three scheduled or held messages
(time, name, first line; held ones are marked) and whether Send Later is on.
Tap a scheduled message to edit it, a held one to open Upcoming, or
**See all (N)** for the full Upcoming list. Below the card, settings are grouped
into rows, each with a one-line live summary; tap a row to open that group:

- **Send Later** — the on/off switch, **Upcoming**, the **Morning / Afternoon / Evening / Night time** slots, **If I text them first** (Still send or Hold and ask), **Hold if they text first** (the default for new messages), **Clear draft after scheduling**, live permission rows and the Battery row. See [Send Later](#send-later).
- **Default SMS app** — **Set as default SMS app**
- **Google account** — **Switch Google account**
- **Gemini** — **Enable Gemini interception** (opens Accessibility settings); **Auto-send Gemini messages** (send intercepted Gemini SMS automatically instead of opening them for review; default on); **Run setup wizard** (reopen the first-install guide)
- **Notifications** — **Auto-send confirmation** (a "Sent via Google Voice" notification on auto-sends); **Incoming SMS notifications** (a heads-up notification for every received SMS; default on, useful for 2FA codes. Turning it off only suppresses the live notification; messages are still saved to the inbox.)
- **About** — version and build (long-press to copy)

## Send Later

Schedule a text from where you already write it, and Voice2SMS sends it through
Google Voice at the time you pick. Send Later is **on by default** on a new
install; you can turn it off in Settings → Send Later. The switch is not
included in Android backup or device transfer, so a phone set up from a backup
of a phone that used Voice2SMS starts with Send Later **off** until you turn it on.

### Scheduling a message

- **In the Google Voice app:** open a one-to-one conversation and type your
  message. A small light-blue tab appears at the right edge, next to the
  message box. Tap it to open the **Send later** sheet.
- **In the Voice2SMS Google Voice screen** (the WebView): the same tab appears
  next to the message box of a one-to-one conversation.

The sheet shows who the message goes to, the text, and quick times ("In 1 hour",
then the next morning, afternoon, evening and night slots; the slot times are
set in Settings), or **Pick date & time**. It also has a **Hold if they text
first** checkbox (on by default) and an **If late** choice. When a name matches
several numbers, the sheet asks **Which number?** and won't schedule until you
pick one. A display name alone is never used as a recipient.

After **Schedule**, the message box is cleared and a confirmation with **Undo**
shows for about 10 seconds. Undo cancels the scheduled message, and puts the
text back only if the box is still empty in the same conversation.

A light-blue **chip** above the message box shows what is scheduled in that
conversation ("Scheduled · Sat 9:00 AM", "2 scheduled", "Held · …"). Tap it to
edit a single message, or to see that conversation in **Upcoming**. Upcoming
(the home page's **See all**, or Settings → Send Later → Upcoming) lists Pending, Held and Recent (last 30 days)
messages, with Change time, Cancel and Send now.

Group conversations are not supported.

### Setup

The first-install setup wizard has two steps for Send Later, after the
Gemini steps:

- **Send Later**: buttons for notification access, exact alarms and
  notifications, each with its own live status line, and a plain-words note on
  why the accessibility service also watches Google Voice and why notification
  access helps.
- **Battery**: a button that opens the right screen to allow background use,
  with a green check (background use allowed) or an amber warning (limited)
  next to its status. On Samsung it also shows the Never sleeping apps advice.

The statuses refresh each time you come back to the wizard. Later, the same
settings are in Settings → **Send Later**. Each row shows its live status; tap
a row to fix it. The Battery row shows the same green check or amber warning.

1. **Send Later switch** (on by default). While it is off, nothing is sent
   automatically and no tab or chip appears. Your saved messages are kept and
   wait for you.
2. **Accessibility** ("Read the Google Voice screen"). Required to schedule
   from the Google Voice app. It is the same Voice2SMS accessibility service
   that handles Gemini; it now also watches the Google Voice app, to add the tab
   and chip and to read the messages on screen. Without it you can still
   schedule from the Voice2SMS Google Voice screen. With it on, a scheduled
   send can also start straight from the background.
3. **Notification access** ("Notice replies"). Optional but recommended. Send
   Later reads Google Voice's message notifications so it can hold a scheduled
   text as soon as the other person replies. Without it, Send Later usually
   can't confirm the conversation at the send time, so the message is held
   for you to decide.
4. **Exact alarms** ("Send at the exact time"), **Notifications**, and
   **Battery**. Without exact alarms, messages may go out a few minutes late.
   Without notifications you won't see the last-call and hold notices. For
   battery, set Voice2SMS's battery use to **Unrestricted**. On Samsung, also
   add it to Battery → Background usage limits → **Never sleeping apps** (no
   app can read that list, so the row shows advice for it, not a status).
   If you schedule while the app is battery-limited, Voice2SMS warns you, at
   most once a day.
5. **Contacts** (optional) helps match a name to the right number.

If Voice2SMS is **force-stopped**, Android keeps it stopped, even after a
reboot, until you open it again. Scheduled messages cannot go out until then.

On Samsung, if the accessibility or notification-access toggle is greyed out,
open App info for Voice2SMS, tap ⋮ and choose **Allow restricted settings**.

### What happens at the send time

- **Last call.** Five minutes before, a notification counts down
  ("Sending to … in 5 min") with **Cancel**, **Edit** and **Send now**.
- **The send.** Voice2SMS opens its Google Voice screen, types the message and
  sends it. An opaque "Sending scheduled message…" cover hides the screen for
  the whole send, locked or not. On a locked phone, the screen turns on and the
  cover shows over the lock screen; the phone stays locked. On an unlocked
  phone, the cover has a **Stop** button. A send is given at most 75 seconds.
- **Only that conversation.** The message is typed only into the conversation
  with the number you scheduled for. If Google Voice shows any other
  conversation, nothing is typed and you get a reminder instead.
- **A draft in the way.** If that conversation's message box already holds a
  draft you typed, nothing is typed over it. A notification shows your draft and
  the scheduled text and asks: **Replace and send** (clear the draft and send the
  scheduled text), **Keep draft** (send nothing; the message waits in Upcoming
  for you) or **Send both** (send the scheduled text, then put your draft back).
  A draft that matches the scheduled text is treated as the scheduled text.
- **Your own sends come first.** If Voice2SMS is in the middle of another
  send (Gemini, the watch, call quick-reply or a message you are reviewing), the
  scheduled send waits up to about 20 seconds, then gives you a reminder instead
  of taking over the screen.
- **Sent** is shown only when Voice2SMS sees the new message in the
  conversation during that send, and Google Voice no longer shows it as sending.
  If Google Voice marks it "Not sent" within the next half minute, the message
  is moved back to "may have already sent" (below).
- **Hold if they text first.** If they texted or called since you scheduled,
  the message is **held**, not sent, and a notification asks you: **Send
  anyway**, **Snooze 1 h** or **Cancel** (tap the notification to edit). With notification access, this
  happens as soon as their reply arrives, not only at the send time. If you
  already sent a similar text yourself, the scheduled one is cancelled.
- **Lateness budget.** A message is never sent automatically later than its
  budget. The default budget depends on how far ahead you scheduled: 10 minutes
  (under 1 hour ahead), 30 minutes (up to a day), 2 hours (more than a day).
  The **If late** choice can change it to 30 minutes, never late, or up to
  24 hours.
- **Reminders.** When a message can't go by itself (too late, not signed in to
  Google Voice in Voice2SMS, no connection for too long, the daily limit, you
  were using Voice2SMS, or the phone could not show the send), you get a
  reminder instead. Tapping it opens the message ready to review; nothing is
  sent until you tap Send.
- **"May have already sent."** If Voice2SMS cannot confirm the send (for
  example the app was stopped mid-send), the message is held. There is no
  automatic retry. Check the conversation; **Send anyway (may be a duplicate)**
  sends it again only if you tap it.
- A held or reminded message that you leave for 24 hours expires and stays
  listed under Recent.
- **Every action needs the phone unlocked.** On the lock screen, Send now,
  Send anyway, Cancel, Snooze 1 h, Keep draft, Replace and send and Send both
  ask you to unlock first. Send Later notifications are marked private, so the
  lock screen hides their text, names and numbers unless Android is set to show
  all notification content there.
- **Names and numbers.** Notifications, the sheet and Upcoming show the
  person's name. If you scheduled from a number and Contacts is allowed, the
  name is filled in from your contacts. A number without a name is shown
  formatted, like (202) 555-0123.

### Permissions

2.0.0 adds these permissions, all for Send Later:

| Permission | Why |
|---|---|
| `SCHEDULE_EXACT_ALARM` | Wake up at the scheduled time and five minutes before it |
| `RECEIVE_BOOT_COMPLETED` | Re-arm the alarms after a reboot |
| `ACCESS_NETWORK_STATE` | Wait for a connection instead of failing a send |
| `READ_CONTACTS` (optional) | Match a conversation name to its number |
| `USE_FULL_SCREEN_INTENT` | Show the send over the lock screen when the accessibility service is off |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Open the system prompt that lets alarms fire on time |

Notification access is granted in system settings, not as a permission.

### Privacy

- Scheduled messages are stored only on the phone, in the app's private
  database (`sendlater.db`). This includes the text and the number.
- Sent, cancelled and expired messages are deleted 30 days after they finish.
  Name and number pairs read from the Google Voice conversation list are
  deleted after 90 days unseen.
- From notifications, Send Later keeps only a fingerprint (hash) and time of
  each message, only for conversations with a scheduled message, for 7 days.
- The Send Later database and the Send Later switch are excluded from Android
  backup and device transfer, so a restored phone starts with Send Later off.
  The other Send Later preferences (slot times, hold defaults) are backed up
  with the rest of the app's settings.
- Release builds log no message text, names or numbers.

## Tested On

| Device | Role | OS |
|--------|------|-----|
| Pixel 10 Pro Fold | Phone | Android 16 |
| Galaxy S22 Ultra | Phone (Send Later test device) | Android 16 |
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
git tag -a v2.0.0 -m "Send Later"
git push origin v2.0.0
```

See `.github/workflows/release.yml` for details. Requires four repository secrets for APK signing (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

## Architecture

For technical details — inject.js flow, Angular chip creation, Gemini interception internals, Send Later, Wearable Data Layer protocol, singleton WebView guards — see [ARCHITECTURE.md](ARCHITECTURE.md).

## Acceptable Use

This tool is for **personal use** — routing your own SMS through your own Google Voice number. It is not designed for bulk or mass messaging. Use must comply with the [Google Voice Acceptable Use Policy](https://support.google.com/voice/answer/9230450) and [Google Terms of Service](https://policies.google.com/terms).

## Disclaimer

This project relies on internal details of Google Voice's web UI (Angular component structure, Zone.js internals, DOM selectors) and Gemini's accessibility tree (resource IDs, FloatyActivity layout). **Google may change any of these at any time**, which could break functionality. This is a personal-use tool, not a supported product.

## License

MIT License. See [LICENSE](LICENSE).

## Version History

The full list is in [CHANGELOG.md](CHANGELOG.md).

### v2.0.0

Send Later: schedule a text and have it sent through Google Voice. (Send Later
was developed as v1.6.0; it ships as 2.0.0.)

- Schedule from the Google Voice app (accessibility overlay tab) or from the
  Voice2SMS Google Voice screen; one sheet with rolling time slots, "Hold if
  they text first" and an "If late" choice
- In-thread chip and an Upcoming screen to change, cancel or send now
- Exact alarms with a five-minute last call; re-armed after reboot
- At the send time, facts only make it more careful: hold on a reply, cancel
  when you already sent it, remind when late, signed out or offline
- Every scheduled send runs under an opaque cover, also over the lock screen;
  only a send seen in the conversation counts as sent, and there is no
  automatic retry ("may have already sent" → your Send anyway)
- Optional notification access for reply detection; battery health row and warning
- On by default; switching it off cancels alarms and keeps your messages
- Setup wizard steps for Send Later and Battery, with a battery indicator
- Local-only storage with 30-day deletion, excluded from backup; the Send Later
  switch is excluded too, so a restored phone starts with it off
- A draft already in the conversation is never typed over: Replace and send,
  Keep draft or Send both
- Names filled from contacts; numbers without a name shown formatted
- Typed only into the scheduled conversation; never takes over a running
  Gemini, Wear or quick-reply send
- Every Send Later notification action needs an unlock on the lock screen
- Auto-send now only from Voice2SMS's own paths (Gemini, Wear,
  RESPOND_VIA_MESSAGE); another app's auto-send request opens review mode
- New permissions: `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`,
  `ACCESS_NETWORK_STATE`, `READ_CONTACTS` (optional), `USE_FULL_SCREEN_INTENT`,
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

### v1.5.1

Incoming SMS no longer black-holed + global auto-send rate limit.

- Default SMS app now persists incoming carrier SMS to `content://sms` and posts
  a heads-up notification (BigTextStyle) — previously `SmsReceiver` was a no-op
  stub that silently dropped every inbound text while Voice2SMS was default
- Manifest now declares the SMS permissions (`RECEIVE_SMS`, `RECEIVE_MMS`,
  `RECEIVE_WAP_PUSH`, `READ_SMS`, `SEND_SMS`) so the SMS role can auto-grant
  them; without this the corresponding appops stayed at the system default
  `ignore`, dropping `SMS_DELIVER` before the receiver could run
- New "Incoming SMS notifications" setting (default on)
- Global auto-send rate limiter (10 sends / 60s) covers every path — Gemini
  interception, Wear companion, RESPOND_VIA_MESSAGE. On rate-limit hit the
  composer still opens prefilled but skips the auto-send; a toast tells the
  user to review and send manually. Wear's per-node limit (10/min/node) stays
  as a separate layer.
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
