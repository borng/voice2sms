# Changelog

All notable changes to Voice2SMS. Versions match the `v*` git tags; each tag
publishes the phone APK (and, from 1.5.0, the Wear OS companion APK) as a
GitHub Release.

## 2.0.0

Send Later: schedule a text and have it sent through Google Voice. (Developed
as 1.6.0; released as 2.0.0.)

### Added

- **Send Later.** Schedule from the Google Voice app (an edge tab added by the
  Voice2SMS accessibility service) or from Voice2SMS's Google Voice screen. One
  sheet with rolling time slots, **Pick date & time**, **Hold if they text
  first** and an **If late** choice. A chip in the conversation and an
  **Upcoming** screen (Pending, Held, Recent) to change the time, cancel or
  send now.
- Exact alarms with a five-minute **last call** notification (Cancel, Edit,
  Send now); alarms are re-armed after a reboot, a time change or a time-zone
  change.
- At the send time only facts make it more careful: it holds when they replied
  or texted first, cancels when you already sent a similar text, and reminds
  you when it is too late, you are signed out of Google Voice, or there is no
  connection.
- Every scheduled send runs under an opaque "Sending scheduled message…" cover,
  also over the lock screen (the phone stays locked). Only a send seen in the
  conversation counts as **Sent**. There is no automatic retry: an unconfirmed
  send is held as "may have already sent" until you choose **Send anyway**.
- **A draft in the way.** If the conversation already holds a draft you typed,
  nothing is typed over it. A notification shows both texts and offers
  **Replace and send**, **Keep draft** or **Send both** (send the scheduled
  text, then put your draft back).
- **Names and numbers.** A message scheduled from a number gets the contact's
  name (with the optional Contacts permission); a number without a name is shown
  formatted, like (202) 555-0123.
- Optional notification access, so a reply holds a scheduled text as soon as it
  arrives.
- Setup wizard steps for Send Later and Battery; Settings → **Send Later** with
  live status rows; a battery warning at most once a day and an accessibility-off
  warning.
- New permissions, all for Send Later: `SCHEDULE_EXACT_ALARM`,
  `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE`, `READ_CONTACTS` (optional),
  `USE_FULL_SCREEN_INTENT`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

### Changed

- **Auto-send only from Voice2SMS's own paths.** Gemini, the Wear companion and
  call quick-reply (`RESPOND_VIA_MESSAGE`) still auto-send. Another app that sets
  `force_auto_send` on an SMS intent now gets review mode: the message opens
  filled in and waits for you to tap Send.
- Gemini's hand-off to Voice2SMS is addressed to Voice2SMS only, and asks for
  an auto-send only while **Auto-send Gemini messages** is on.
- The Google Voice screen allows only `https://voice.google.com` to talk to the
  app, and only the page the app itself trusted (per-page secret).

### Security and privacy

- Scheduled messages are stored only on the phone (`sendlater.db`) and deleted
  30 days after they finish; Send Later's data and its on/off switch are
  excluded from Android backup and device transfer, so a restored phone starts
  with Send Later **off**.
- Every Send Later notification action (Send now, Send anyway, Cancel,
  Snooze 1 h, Keep draft, Replace and send, Send both) needs an unlock on the
  lock screen. Send Later notifications are marked private.
- A scheduled send types only into the conversation it was scheduled for, and
  never takes over the screen from a Gemini, Wear or quick-reply send that is
  still running.
- Release builds log no message text, names or numbers.

## 1.5.1

- Incoming carrier SMS are saved to the system message store and shown as a
  heads-up notification (previously they were dropped while Voice2SMS was the
  default SMS app). New **Incoming SMS notifications** setting.
- The manifest declares the SMS permissions so the default-SMS role can grant
  them.
- Global auto-send rate limit (10 sends per minute) across Gemini, Wear and
  `RESPOND_VIA_MESSAGE`.

## 1.5.0

- Wear OS companion APK: "Hey Google, text …" on the watch sends through Google
  Voice on the phone via the Wearable Data Layer.
- "Sent via Google Voice" notification on auto-sends.
- Settings build stamp (version, build code, git SHA, date).

## 1.4.0

- GitHub Actions CI and a release pipeline gated on tests; Dependabot.
- Fix an SMS-intent crash on malformed URIs; WebView race guards.
- JVM source-safety test suite.

## 1.3.0

- GBoard sticker and image input in the WebView; Enter inserts a newline;
  foldable screen handling.

## 1.2.0

- Static-analysis fixes (singleTask, UI thread safety, a memory leak) and
  hardening for a public repository.

## 1.1.0

- Gemini integration: an AccessibilityService routes Gemini's SMS through
  Google Voice (Send button, Modify/Edit, voice "Yes"); setup wizard.

## 1.0.0

- Initial release: SMS intent handling through the Google Voice web UI in a
  WebView, direct Angular chip creation, auto-send, dark mode.
