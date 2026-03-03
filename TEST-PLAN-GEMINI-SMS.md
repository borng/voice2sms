# Voice2SMS Gemini SMS Routing — Test Plan

## Goal

Force Gemini's SMS to route through Voice2SMS (Google Voice) instead of going directly to T-Mobile carrier SMS. Test the simplest approaches first (Settings toggles), then escalate to ADB commands.

## Prerequisites

- Device: Pixel 10 Pro Fold (rango), Android 15
- Voice2SMS installed and set as default SMS app
- ADB connected: `/opt/android-sdk/platform-tools/adb connect <device-ip>:5555`
- Gemini accessible (long-press home or Google app)
- Test recipient: any number you can verify received/didn't receive the SMS

## Test Prompt

Use the same prompt for every experiment so results are comparable:

> "Send a text to [recipient] saying Testing Voice2SMS routing"

After Gemini responds, note what buttons appear and tap each one.

---

## Phase 1: Gemini Settings Experiments

Two settings, three configurations to test (plus baseline).

| Setting | Where to find it |
|---------|-----------------|
| **Messages extension** | Gemini app → Settings → Extensions → Messages |
| **SMS permission** | Settings → Apps → Google → Permissions → SMS |

### Experiment 1A — Baseline (both ON)

**Config:** Messages extension ON, SMS permission ON (current state)

**Steps:**
1. Verify Messages extension is ON in Gemini settings
2. Verify SMS permission is granted to Google app
3. Ask Gemini: _"Send a text to [recipient] saying Test 1A baseline"_
4. Note which buttons Gemini shows
5. Tap **"Send"** if present — observe what happens
6. Check: did recipient receive the SMS? (carrier SMS, not GV)
7. Check logcat: `adb logcat -s Voice2SMS SHANNON_IMS -T 1 | tee /tmp/test-1a.log`

**Record:**
```
1A Results:
  Buttons shown:     [ Send | Edit | other: ___ ]
  Tapped "Send":     [ SMS received by carrier? Y/N ]
  Voice2SMS logs:    [ any activity? Y/N ]
  SHANNON_IMS logs:  [ carrier send? Y/N ]
  Notes:
```

---

### Experiment 1B — Messages extension OFF, SMS permission ON

**Config:** Messages extension OFF, SMS permission ON

**Steps:**
1. Gemini app → Settings → Extensions → Messages → **toggle OFF**
2. Verify SMS permission is still granted to Google app
3. Ask Gemini: _"Send a text to [recipient] saying Test 1B messages off"_
4. Note which buttons Gemini shows
5. Tap whatever button is available — observe what happens
6. If Voice2SMS opens: does it have the recipient? the body? did it auto-send?
7. Check logcat: `adb logcat -s Voice2SMS SHANNON_IMS -T 1 | tee /tmp/test-1b.log`

**Record:**
```
1B Results:
  Buttons shown:     [ Send | Edit | other: ___ ]
  Tapped button:     [ which one? ___ ]
  Voice2SMS opened:  [ Y/N ]
  Recipient filled:  [ Y/N ]
  Body filled:       [ Y/N ]
  Auto-sent via GV:  [ Y/N ]
  Carrier SMS sent:  [ Y/N ]
  Notes:
```

---

### Experiment 1C — Messages extension ON, SMS permission OFF

**Config:** Messages extension ON, SMS permission revoked

**Steps:**
1. Gemini app → Settings → Extensions → Messages → **toggle ON** (restore)
2. Settings → Apps → Google → Permissions → SMS → **Don't allow**
3. Ask Gemini: _"Send a text to [recipient] saying Test 1C sms revoked"_
4. Note which buttons Gemini shows
5. Tap **"Send"** if present — does it fail? error message?
6. Tap **"Edit"** if present — does Voice2SMS open?
7. Check logcat: `adb logcat -s Voice2SMS SHANNON_IMS -T 1 | tee /tmp/test-1c.log`

**Record:**
```
1C Results:
  Buttons shown:     [ Send | Edit | other: ___ ]
  Tapped "Send":     [ result: ___ ]
  Tapped "Edit":     [ result: ___ ]
  Voice2SMS opened:  [ Y/N ]
  Recipient filled:  [ Y/N ]
  Body filled:       [ Y/N ]
  Carrier SMS sent:  [ Y/N ]
  Error messages:    [ ___ ]
  Notes:
```

---

### Experiment 1D — Both OFF (belt and suspenders)

**Config:** Messages extension OFF, SMS permission OFF

**Steps:**
1. Gemini app → Settings → Extensions → Messages → **toggle OFF**
2. Settings → Apps → Google → Permissions → SMS → **Don't allow** (keep from 1C)
3. Ask Gemini: _"Send a text to [recipient] saying Test 1D both off"_
4. Note what happens
5. Check logcat: `adb logcat -s Voice2SMS SHANNON_IMS -T 1 | tee /tmp/test-1d.log`

**Record:**
```
1D Results:
  Buttons shown:     [ Send | Edit | other: ___ ]
  Tapped button:     [ which one? ___ ]
  Voice2SMS opened:  [ Y/N ]
  Recipient filled:  [ Y/N ]
  Body filled:       [ Y/N ]
  Auto-sent via GV:  [ Y/N ]
  Carrier SMS sent:  [ Y/N ]
  Notes:
```

---

### Phase 1 Cleanup

After Phase 1, restore both settings to ON before starting Phase 2:
1. Gemini → Settings → Extensions → Messages → **ON**
2. Settings → Apps → Google → Permissions → SMS → **Allow**

---

## Phase 2: ADB AppOps / Permission Blocking

These approaches use ADB commands to block Gemini's direct SMS at the framework level, independent of Gemini's own settings. Test with both Gemini settings restored to ON (baseline) to isolate the ADB effect.

### Experiment 2A — AppOps SEND_SMS ignore

**What it does:** Silently blocks the Google app from sending SMS. The app thinks it sent successfully, but the SMS never reaches the carrier modem.

**Setup:**
```bash
# Apply
adb shell appops set com.google.android.googlequicksearchbox SEND_SMS ignore

# Verify
adb shell appops get com.google.android.googlequicksearchbox SEND_SMS
# Expected: SEND_SMS: ignore; ...
```

**Steps:**
1. Run the ADB commands above
2. Ask Gemini: _"Send a text to [recipient] saying Test 2A appops ignore"_
3. Tap **"Send"** — does Gemini show success? Did recipient actually get SMS?
4. Tap **"Edit"** — does Voice2SMS open and work?
5. Check logcat: `adb logcat -s Voice2SMS SHANNON_IMS -T 1 | tee /tmp/test-2a.log`

**Record:**
```
2A Results (appops ignore):
  Buttons shown:     [ Send | Edit | other: ___ ]
  Tapped "Send":
    Gemini shows:    [ success / error / ___ ]
    Carrier SMS:     [ sent / blocked / ___ ]
    SHANNON_IMS log: [ Y/N ]
  Tapped "Edit":
    Voice2SMS opened: [ Y/N ]
    Recipient filled:  [ Y/N ]
    Body filled:       [ Y/N ]
  Notes:
```

**Rollback:**
```bash
adb shell appops set com.google.android.googlequicksearchbox SEND_SMS allow
```

---

### Experiment 2B — pm revoke SEND_SMS

**What it does:** Revokes the SMS runtime permission entirely. The app gets a SecurityException when trying to send — may show an error or crash.

**Setup:**
```bash
# Apply
adb shell pm revoke com.google.android.googlequicksearchbox android.permission.SEND_SMS

# Verify
adb shell dumpsys package com.google.android.googlequicksearchbox | grep SEND_SMS
```

**Steps:**
1. Run the ADB commands above
2. Ask Gemini: _"Send a text to [recipient] saying Test 2B pm revoke"_
3. Tap **"Send"** — error? crash? Gemini re-requests permission?
4. Tap **"Edit"** — does Voice2SMS open?
5. Check logcat: `adb logcat -s Voice2SMS SHANNON_IMS -T 1 | tee /tmp/test-2b.log`

**Record:**
```
2B Results (pm revoke):
  Buttons shown:      [ Send | Edit | other: ___ ]
  Tapped "Send":
    Gemini shows:     [ success / error / permission dialog / crash / ___ ]
    Carrier SMS:      [ sent / blocked / ___ ]
  Tapped "Edit":
    Voice2SMS opened: [ Y/N ]
  Permission re-request: [ Y/N — did Gemini ask for SMS perm again? ]
  Notes:
```

**Rollback:**
```bash
adb shell pm grant com.google.android.googlequicksearchbox android.permission.SEND_SMS
```

---

### Experiment 2C — AppOps ignore + pm revoke (combined)

**What it does:** Double block. Prevents Android 11+ from auto-resetting the appops setting by ensuring the permission grant state matches the appops state.

**Setup:**
```bash
# Apply both
adb shell appops set com.google.android.googlequicksearchbox SEND_SMS ignore
adb shell pm revoke com.google.android.googlequicksearchbox android.permission.SEND_SMS

# Verify
adb shell appops get com.google.android.googlequicksearchbox SEND_SMS
adb shell dumpsys package com.google.android.googlequicksearchbox | grep SEND_SMS
```

**Steps:**
1. Run the ADB commands above
2. Ask Gemini: _"Send a text to [recipient] saying Test 2C combined"_
3. Test both buttons
4. Check logcat: `adb logcat -s Voice2SMS SHANNON_IMS -T 1 | tee /tmp/test-2c.log`

**Record:**
```
2C Results (appops + revoke):
  Buttons shown:     [ Send | Edit | other: ___ ]
  Behavior:          [ same as 2A / same as 2B / different: ___ ]
  Notes:
```

**Rollback:**
```bash
adb shell pm grant com.google.android.googlequicksearchbox android.permission.SEND_SMS
adb shell appops set com.google.android.googlequicksearchbox SEND_SMS allow
```

---

### Experiment 2D — Standalone Gemini app (com.google.android.apps.bard)

**What it does:** Same as 2A but targeting the standalone Gemini package, in case SMS goes through that process instead of the Google app.

**Setup:**
```bash
adb shell appops get com.google.android.apps.bard SEND_SMS
# If it shows "allow" or has been used, block it:
adb shell appops set com.google.android.apps.bard SEND_SMS ignore
```

**Steps:**
1. Open the Gemini app directly (not via Google app)
2. Ask: _"Send a text to [recipient] saying Test 2D bard package"_
3. Test send behavior
4. Check logcat: `adb logcat -s Voice2SMS SHANNON_IMS -T 1 | tee /tmp/test-2d.log`

**Record:**
```
2D Results (bard package):
  Which app opened Gemini: [ Gemini app / Google app / assistant overlay ]
  Buttons shown:     [ Send | Edit | other: ___ ]
  Carrier SMS:       [ sent / blocked / ___ ]
  Notes:
```

**Rollback:**
```bash
adb shell appops set com.google.android.apps.bard SEND_SMS allow
```

---

## Phase 3: Deploy Auto-Send Code Change

Only run Phase 3 after identifying a working config from Phase 1 or 2 where Gemini's "Edit" button opens Voice2SMS with both recipient and body.

### Code Change (already applied)

`SmsHandlerActivity.java` line 155: when a SENDTO intent arrives with **both** recipient and body, `force_auto_send=true` is set automatically. This makes the "Edit" button behave like "Send" — Voice2SMS auto-sends via Google Voice.

### Build & Deploy

```bash
cd /workspaces/playground/voice2sms
./gradlew assembleDebug && \
/opt/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Experiment 3A — Auto-send with winning config

**Steps:**
1. Apply whichever config won from Phase 1/2
2. Build and install the updated Voice2SMS APK
3. Ask Gemini: _"Send a text to [recipient] saying Test 3A auto send"_
4. Tap "Edit" — Voice2SMS should open AND auto-send
5. Verify recipient got the message via Google Voice (not carrier)
6. Check logcat: `adb logcat -s Voice2SMS -T 1 | tee /tmp/test-3a.log`

**Record:**
```
3A Results (auto-send):
  Config used:           [ 1B / 1C / 2A / ___ ]
  Tapped "Edit":
    Voice2SMS opened:    [ Y/N ]
    Chip created:        [ Y/N ]
    Body filled:         [ Y/N ]
    Auto-sent:           [ Y/N ]
    GV message received: [ Y/N ]
  Time from tap to sent: [ ___s ]
  Notes:
```

### Experiment 3B — Manual compose still works

**Verify the auto-send doesn't fire on manual compose:**

1. Open Contacts, tap a contact, tap "Send message"
2. Voice2SMS should open with recipient but NO body
3. Keyboard should appear — no auto-send
4. Type a message manually, verify it sends normally

**Record:**
```
3B Results (manual compose):
  Auto-send triggered: [ Y/N — should be N ]
  Keyboard appeared:   [ Y/N — should be Y ]
  Normal flow intact:  [ Y/N ]
  Notes:
```

---

## Persistence Check

After finding the winning configuration, reboot the device and re-test:

```bash
adb reboot
# Wait for device to come back
adb logcat -s Voice2SMS SHANNON_IMS -T 1 | tee /tmp/test-persist.log
```

Then repeat the Gemini test prompt. Verify the block is still in effect.

```
Persistence Results:
  Settings survived reboot: [ Y/N ]
  ADB appops survived:      [ Y/N / N/A ]
  Gemini still routes to V2S: [ Y/N ]
  Notes:
```

---

## Decision Matrix

Fill this in after all experiments:

| Experiment | Carrier SMS blocked? | Voice2SMS opened? | Body passed? | Auto-send? | User friction |
|-----------|---------------------|-------------------|-------------|-----------|---------------|
| 1A baseline | | | | | |
| 1B msg ext OFF | | | | | |
| 1C sms perm OFF | | | | | |
| 1D both OFF | | | | | |
| 2A appops ignore | | | | | |
| 2B pm revoke | | | | | |
| 2C combined | | | | | |
| 2D bard package | | | | | |
| 3A auto-send | | | | | |

**Winner:** Phase 2C confirmed — `pm revoke` + `user-fixed` + `appops ignore` blocks carrier SMS.

---

## Phase 4: AccessibilityService Interception (IMPLEMENTED)

### Architecture: DUAL Approach

**Problem solved:** Phase 2C blocks Gemini's direct carrier SMS, but the message is lost — user sees "permission denied" toast and nothing sends.

**Solution:** An AccessibilityService monitors Gemini's UI, detects Send button taps, extracts recipient + body from the structured view tree, and fires a SENDTO intent to Voice2SMS with `force_auto_send=true`.

```
User taps "Send" in Gemini
    |
    +---> Gemini: SmsManager.sendTextMessage() ---> BLOCKED (appops)
    |         (shows "permission denied" toast)
    |
    +---> AccessibilityService: TYPE_VIEW_CLICKED on Send button
              |
              +---> Read view tree: phone + body
              +---> Fire: smsto:{phone}?body={body} + force_auto_send
              +---> Voice2SMS auto-sends via Google Voice
```

### Files Created

| File | Purpose |
|------|---------|
| `GeminiSmsInterceptService.java` | AccessibilityService that monitors Gemini |
| `res/xml/gemini_accessibility_config.xml` | Service configuration |
| `SetupActivity.java` | First-install onboarding wizard |
| `res/layout/activity_setup.xml` | Setup wizard layout |

### Files Modified

| File | Change |
|------|--------|
| `AndroidManifest.xml` | Added service + setup activity declarations |
| `preferences.xml` | Added Gemini Integration settings category |
| `SettingsActivity.java` | Added Gemini toggle + auto-send handlers |
| `strings.xml` | Added accessibility service description |
| `SmsHandlerActivity.java` | First-launch redirect to setup wizard |

### Gemini UI Resource IDs (from uiautomator dump)

| Element | Resource ID | Detection |
|---------|-------------|-----------|
| Send button | `assistant_robin_lockscreen_compatible_action_card_button_element` | content-desc="Send" |
| Modify button | `assistant_robin_action_card_outlined_button_element` | content-desc="Modify" |
| Title | `assistant_robin_floaty_title_text` | text contains "Text Message" |
| All text fields | `assistant_robin_action_card_text` | Traversal order: app name, contact, phone, body |
| Phone number | (within action_card_text) | Regex: `\+\d{10,}` in "Mobile . +15551234567" |
| Message body | (within action_card_text) | First text node after phone number |
| Package | `com.google.android.googlequicksearchbox` | FloatyActivity overlay |

### One-Time ADB Setup (required)

```bash
# Block Gemini's direct SMS — survives reboots
adb shell pm revoke com.google.android.googlequicksearchbox android.permission.SEND_SMS
adb shell pm set-permission-flags com.google.android.googlequicksearchbox android.permission.SEND_SMS user-fixed
adb shell appops set com.google.android.googlequicksearchbox SEND_SMS ignore
```

### User Setup Flow

1. Install Voice2SMS, launch it -> Setup Wizard opens
2. Step 1: Tap "Set Default SMS App" -> system dialog
3. Step 2: Run ADB commands from a computer (copy button provided)
4. Step 3: Tap "Open Accessibility Settings" -> enable Voice2SMS service
5. Tap "Done" -> Settings screen

### Settings

| Preference | Key | Default | Effect |
|-----------|-----|---------|--------|
| Enable Gemini interception | `gemini_intercept_toggle` | - | Opens Accessibility Settings |
| Auto-send Gemini messages | `gemini_auto_send` | true | When ON: intercepted SMS auto-sends via GV. When OFF: opens compose for review. |
| Run setup wizard | `setup_wizard` | - | Reopens the onboarding flow |

### Experiment 4A — Full Intercept E2E

**Prerequisites:**
- Phase 2C ADB commands applied
- Voice2SMS set as default SMS app
- AccessibilityService enabled
- `gemini_auto_send` = true

**Steps:**
1. Build and install: `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Ask Gemini: _"Send a text to [recipient] saying Test 4A intercept"_
3. Tap **"Send"** — expect:
   - Gemini shows "permission denied" toast (carrier blocked)
   - Voice2SMS opens and auto-sends via Google Voice
4. Verify recipient got the message via GV
5. Check logcat: `adb logcat -s Voice2SMS -T 1 | tee /tmp/test-4a.log`

**Record:**
```
4A Results (full intercept):
  Gemini "Send" tapped:     [ Y ]
  Permission denied toast:  [ Y/N ]
  Voice2SMS opened:         [ Y/N ]
  Recipient correct:        [ Y/N ]
  Body correct:             [ Y/N ]
  Auto-sent via GV:         [ Y/N ]
  GV message received:      [ Y/N ]
  Time tap-to-sent:         [ ___s ]
  Notes:
```

### Experiment 4B — Auto-send OFF (review mode)

**Setup:** Settings -> Gemini Integration -> Auto-send OFF

**Steps:**
1. Ask Gemini: _"Send a text to [recipient] saying Test 4B review"_
2. Tap **"Send"** — expect:
   - Voice2SMS opens with recipient + body pre-filled
   - Keyboard visible, NO auto-send
   - User reviews and taps Send manually in GV

**Record:**
```
4B Results (review mode):
  Voice2SMS opened:     [ Y/N ]
  Recipient filled:     [ Y/N ]
  Body filled:          [ Y/N ]
  Auto-send triggered:  [ Y/N — should be N ]
  Manual send works:    [ Y/N ]
  Notes:
```

---

## Future: Shizuku Integration

[Shizuku](https://shizuku.rikka.app/) lets apps call ADB-level APIs without a computer.

### How it would work

1. User installs Shizuku app and starts it once (via ADB or wireless debugging)
2. Voice2SMS requests Shizuku permission
3. Voice2SMS calls `IPackageManager.revokeRuntimePermission()` and `IAppOpsService.setMode()` directly
4. No computer needed after initial Shizuku start

### Implementation plan

- Add dependency: `dev.rikka.shizuku:api:13.1.5` + `dev.rikka.shizuku:provider:13.1.5`
- Create `ShizukuHelper.java`:
  - `isShizukuAvailable()` — checks if Shizuku is installed and running
  - `requestPermission()` — requests Shizuku permission from user
  - `blockGeminiSms()` — runs the three permission/appops commands via Shizuku
  - `unblockGeminiSms()` — reverses the block
- Update SetupActivity Step 2: detect Shizuku, show "Auto-configure" button if available
- Fallback: still show ADB commands for users without Shizuku

### Why wait

- Shizuku adds a dependency and complexity
- ADB one-time setup works fine for power users
- Shizuku itself requires ADB to start (chicken-and-egg, partially)
- Add when user feedback demands easier setup
