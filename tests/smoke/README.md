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
```

Environment variables:

| var          | default          | purpose                                  |
|--------------|------------------|------------------------------------------|
| `ITERATIONS` | 3                | how many cold-start intent cycles to run |
| `WAIT_SECS`  | 5                | time to wait for crash to appear         |
| `RECIPIENT`  | `+15551234567`   | destination number (not sent, just URI)  |
| `BODY`       | `smoke-test`     | body text (URL-encoded automatically)    |

### `sendlater-smoke.sh` (Send Later, debug build only)

Drives the adb-only `SendLaterDebugReceiver` that exists only in the debug APK
(`app/src/debug/AndroidManifest.xml`, protected by `android.permission.DUMP`, which the
adb shell holds and other apps cannot). The flow:

1. installs the debug APK when `INSTALL=1` or the app is missing (otherwise uses the installed one;
   it must be a debuggable build so `run-as` works);
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
| `DEBUG_SEED` | `--es e164`, `--es text`, `--ei offset_min` (3), `--ez hold` (true), `--es source` (`NATIVE_GV`/`WEBVIEW`), `--ez allow_send`, `--es allowed_recipient` | stores a SCHEDULED record with a DEVICE_ONLY watermark and registers its alarms; rung R1 only when `allow_send` is true and `e164` equals `allowed_recipient`, otherwise R2 (never auto-sent); result `id=N` |
| `DEBUG_DUMP` | none | all records with watermarks, plus stored events, as one JSON line in logcat tag `SendLaterDump` and in `cache/sendlater-dump.json` (`adb shell run-as com.voice2sms cat cache/sendlater-dump.json`) |
| `DEBUG_EVALUATE` | `--el id` | runs the ladder and returns the Decision (stored rung and as R1); applies nothing |
| `DEBUG_FIRE` | `--el id`, `--ez allow_send`, `--es allowed_recipient` | only with `allow_send true` AND the record's recipient equal to `allowed_recipient`: moves the time to now and calls `SendLaterEngine.onDue` (may send). Otherwise downgrades to HOLD(UNVERIFIED), cancels the alarms and never calls the engine |
| `DEBUG_INBOUND` | `--es e164`, `--es text` | records a fake inbound SMS event and calls `SendLaterEngine.onInboundEvent` |
| `DEBUG_LAST_CALL` | `--el id` | `SendLaterEngine.onLastCall` (the T-5 alarm) |
| `DEBUG_CANCEL` | `--el id` | `SendLaterEngine.onCancel` |

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
"every `V2SBridge.runOnUiThread` checks `isActivityAlive()`"). These run in
~2 seconds, no device needed, and catch most regressions at compile time.

Suggested pre-release workflow:

```bash
./gradlew test                              # source-level guards
./gradlew installDebug                      # push to connected device
./tests/smoke/test-sms-intent.sh            # runtime crash canary
./tests/smoke/sendlater-smoke.sh            # Send Later hooks (debug APK, recipient required)
```
