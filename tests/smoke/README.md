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
```
