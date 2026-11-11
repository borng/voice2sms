# Smoke tests (on-device)

These scripts reproduce real crashes we've hit in production and fail if the
crash comes back. Run them before every release.

## Prerequisites

- Device connected: `$ANDROID_HOME/platform-tools/adb devices` shows a device
- Debug APK installed: `./gradlew installDebug`

## Tests

### `test-sms-intent.sh`

Fires SMS SENDTO intents and asserts the process does not crash within the
observation window. Covers the pre-v1.3.0 `WV.h22.run` NPE caused by
`prefetchUrlAsync` racing with the Activity's live `loadUrl`.

```bash
./tests/smoke/test-sms-intent.sh                       # default: 3 iterations
ITERATIONS=10 WAIT_SECS=8 ./tests/smoke/test-sms-intent.sh
SERIAL=<device-serial> ./tests/smoke/test-sms-intent.sh   # several devices attached
```

Environment variables:

| var          | default          | purpose                                  |
|--------------|------------------|------------------------------------------|
| `ITERATIONS` | 3                | how many cold-start intent cycles to run |
| `WAIT_SECS`  | 5                | time to wait for crash to appear         |
| `RECIPIENT`  | `+12025550123`   | destination number (fictional 555-01xx; never sent, just URI) |
| `BODY`       | `smoke-test`     | body text (URL-encoded automatically; keep it one line) |
| `ADB`        | `$ANDROID_HOME/platform-tools/adb`, then `/opt/android-sdk/platform-tools/adb`, then `adb` on PATH, then Windows `platform-tools/adb.exe` under `/mnt/c/Users/*` (WSL) | adb binary or a wrapper |
| `SERIAL`     | none             | passed as `adb -s`                       |
| `CLEANUP`    | `1`              | `0` = leave the typed body in the message box |
| `CLEANUP_WAIT_SECS` | 30        | how long cleanup waits for the body to show up in the box |

Exit codes: 0 pass, 1 crash detected, 2 setup problem, 3 no crash but the body text is
still in the message box after cleanup.

**Windows adb.** Every adb output is passed through `tr -d '\r'`, so plain `adb.exe` (CRLF
output) works from WSL with no wrapper. The installed-package check uses `pm list packages
--user 0` (Samsung Secure Folder / Pixel Private space users are not shell-accessible). The
logcat start marker is taken from the device clock, so a WSL/phone time-zone difference does
not hide a crash.

**Draft cleanup.** Each intent types `BODY` into the Google Voice message box for
`RECIPIENT`. `am force-stop` does not remove it: the WebView profile survives a force-stop,
and Google Voice may keep the text as a draft for that conversation. So after the last
iteration the script:

1. waits up to `CLEANUP_WAIT_SECS` for the body to appear in the screen's accessibility dump
   (`uiautomator dump`; the WebView's text fields are in it);
2. only while `com.voice2sms` has window focus, sends `KEYCODE_MOVE_END` and
   `KEYCODE_DEL` x (body length + 8) with `adb shell input keyevent` (real key events, so the
   page sees an ordinary edit and saves the now-empty draft), up to 3 passes;
3. dumps the screen again and requires the body text to be gone (exit 3 otherwise).

If the screen cannot be read (locked phone, another app in front) it prints a warning and the
manual step: open Voice2SMS and delete the body text from the message box. The dump can
contain private screen content: it stays in a shell variable, is only grepped, is deleted from
the device (`/data/local/tmp/v2s-smoke-dump.xml`) and is never printed. Keep the phone
unlocked during the run.

### `sendlater-smoke.sh` (Send Later, debug build only)

Drives the adb-only `SendLaterDebugReceiver` that exists only in the debug APK (code in
`app/src/debug/java`, registered in `app/src/debug/AndroidManifest.xml`, protected by
`android.permission.DUMP`, which the adb shell holds and other apps cannot). Release APKs contain
none of these classes. The flow:

1. installs the debug APK when `INSTALL=1` or the app is missing (otherwise uses the installed one;
   it must be a debuggable build so `run-as` works);
   then reads the Send Later master switch from `DEBUG_DUMP`'s leading `"enabled"` field and turns
   it on with `DEBUG_ENABLE --ez on true` (the engine does nothing while it is off). On every exit
   path the previous value is restored; when the dump has no `"enabled"` field the switch is left
   off;
2. seeds a record 3 minutes ahead to the test recipient (stored at rung R2: the engine never
   auto-sends it);
3. fires the T-5 last-call alarm through the debug broadcast (`adb shell cmd alarm` cannot fire one
   specific alarm) and asserts the `sendlater_last_call` notification in `dumpsys notification`;
4. runs `DEBUG_EVALUATE`, which logs the ladder's Decision and applies nothing;
5. injects a fake inbound reply and asserts `HELD` in the dump;
6. cancels and asserts `CANCELED`;
7. checks logcat for a `FATAL EXCEPTION` in `com.voice2sms`.

```bash
SENDLATER_TEST_RECIPIENT=+1XXXXXXXXXX ./tests/smoke/sendlater-smoke.sh
SERIAL=<device-serial> ADB=/path/to/adb-wrapper ./tests/smoke/sendlater-smoke.sh
```

**Recipients (no default, public repo).** The recipient comes from `SENDLATER_TEST_RECIPIENT`
(or `RECIPIENT`), else the first line of `tests/smoke/.recipient`. Both files below are gitignored;
never commit a real number. Without a recipient the script exits 2.

**Real sends are opt-in and guarded (I9).** Nothing is sent unless `SEND=1`. With `SEND=1` the
recipient must also equal `SENDLATER_ALLOWED_RECIPIENT` (else the first line of
`tests/smoke/.allowed-recipient`), or the script exits 2. `SEND=1` first seeds a second record with
`allow_send`, fires it through `DEBUG_FIRE`, waits up to `SEND_WAIT_SECS` and requires
`SENT_VERIFIED`; then the normal flow runs.

| var | default | purpose |
|-----|---------|---------|
| `SENDLATER_TEST_RECIPIENT` / `RECIPIENT` | none (required) | test recipient, E.164 or 10-digit NANP |
| `SENDLATER_ALLOWED_RECIPIENT` | none | the only number `SEND=1` may send to |
| `SEND` | `0` | `1` = also run one guarded real send |
| `SEND_WAIT_SECS` | `90` | how long `SEND=1` waits for `SENT_VERIFIED` |
| `ADB` | `$ANDROID_HOME/platform-tools/adb`, then `adb` on PATH, then Windows `platform-tools/adb.exe` under `/mnt/c/Users/*` (WSL) | adb binary or a wrapper |
| `SERIAL` | none | passed as `adb -s` |
| `INSTALL` | `0` | `1` = `adb install -r -t` the debug APK first |
| `APK` | `app/build/outputs/apk/debug/*.apk` | APK to install |
| `ADB_WINDOWS_PATHS` | `0` | `1` = pass the APK as a Windows path (wrapper around `adb.exe`) |

Exit codes: 0 pass, 1 a check failed, 2 setup problem or refused.

#### Debug broadcasts (debug APK only)

All go to `-n com.voice2sms/com.voice2sms.sendlater.debug.SendLaterDebugReceiver`; add
`-f 32` (include stopped packages). The one-line result is the broadcast's `data="..."` and is
logged under tag `SendLaterDebug`. Results and dumps never contain message text (hash and length
only) and mask numbers to the last 4 digits.

| action (`com.voice2sms.sendlater.…`) | extras | effect |
|---|---|---|
| `DEBUG_SEED` | `--es e164`, `--es text`, `--ei offset_min` (3), `--ez hold` (true), `--es source` (`NATIVE_GV`/`WEBVIEW`), `--ez allow_send`, `--es allowed_recipient` | stores a SCHEDULED record with a DEVICE_ONLY watermark, registers its alarms and broadcasts `ACTION_STATE_CHANGED` (app-internal) so the chip and Upcoming tab reload; rung R1 only when `allow_send` is true and `e164` equals `allowed_recipient`, otherwise R2 (never auto-sent); result `id=N` |
| `DEBUG_DUMP` | none | `{"enabled":B,"now":..}`: the master switch, then all records with watermarks, plus stored events, as one JSON line in logcat tag `SendLaterDump` and in `cache/sendlater-dump.json` (`adb shell run-as com.voice2sms cat cache/sendlater-dump.json`) |
| `DEBUG_EVALUATE` | `--el id` | runs the ladder and returns the Decision (stored rung and as R1); applies nothing |
| `DEBUG_FIRE` | `--el id`, `--ez allow_send`, `--es allowed_recipient` | only with `allow_send true` AND the record's recipient equal to `allowed_recipient`: moves the time to now and calls `SendLaterEngine.onDue` (may send). Otherwise downgrades to HOLD(UNVERIFIED), cancels the alarms and never calls the engine |
| `DEBUG_INBOUND` | `--es e164`, `--es text` | records a fake inbound SMS event and calls `SendLaterEngine.onInboundEvent` |
| `DEBUG_LAST_CALL` | `--el id` | `SendLaterEngine.onLastCall` (the T-5 alarm) |
| `DEBUG_CANCEL` | `--el id` | `SendLaterEngine.onCancel` |
| `DEBUG_ENABLE` | `--ez on true\|false` | writes the master switch (`sendlater_enabled` in the non-backed-up `sendlater_switch` prefs file) and calls `SendLaterEngine.applySwitch` (off: alarms and network waiters cancelled, records kept; on: expiry sweep and re-arm), then broadcasts `ACTION_STATE_CHANGED`; result `enabled=B previous=B`. Without `--ez on` it changes nothing (`error=no_on`) |

#### Device setup checklist (Samsung One UI, e.g. Galaxy S22)

- [ ] Install the debug APK with adb: `adb install -r -t app/build/outputs/apk/debug/app-debug.apk`
      (or `INSTALL=1`). The debug receiver is not in release builds.
- [ ] Settings → Battery → Background usage limits → **Never sleeping apps**: add voice2sms.
- [ ] Settings → Apps → voice2sms → Battery → **Unrestricted**.
- [ ] If a toggle is greyed out ("Restricted setting"): Settings → Apps → voice2sms → ⋮ →
      **Allow restricted settings**, then retry.
- [ ] Grant **Alarms & reminders** (exact alarms): Settings → Apps → voice2sms → Alarms & reminders,
      or `adb shell appops set com.voice2sms SCHEDULE_EXACT_ALARM allow`.
- [ ] Grant **notifications**: `adb shell pm grant com.voice2sms android.permission.POST_NOTIFICATIONS`.
- [ ] Turn off Samsung **Auto Blocker** (Security and privacy) if adb commands fail.
- [ ] Put the test number in `tests/smoke/.recipient` (and, only for real sends,
      `tests/smoke/.allowed-recipient`). Both are gitignored.

## Complement: JVM unit tests

`./gradlew test` runs `SourceSafetyTest` which enforces source-level guards
(e.g., "`prefetchUrlAsync` never reappears in `Voice2SmsApplication`",
"every `V2SBridge.runOnUiThread` checks `isActivityAlive()`"). The whole JVM
suite needs no device (under a minute) and catches most regressions before a build reaches a phone.

Send Later adds two more guard classes: `SendLaterSourceSafetyTest` (no Wear/Gemini auto-send
extra or `AutoSendRateLimiter` in the scheduled path, guarded WebView access, Voice id allowlist,
no message text in logs, `ACTION_SCHEDULED` only after a verified persist) and
`PrivacyLoggingSourceTest` (no unguarded logging of notification text, click text or raw URLs).
The debug-hook tests live in `app/src/testDebug` and run only in `testDebugUnitTest`.

Suggested pre-release workflow:

```bash
./gradlew test                              # source-level guards
./gradlew installDebug                      # push to connected device
./tests/smoke/test-sms-intent.sh            # runtime crash canary
./tests/smoke/sendlater-smoke.sh            # Send Later hooks (debug APK, recipient required)
```
