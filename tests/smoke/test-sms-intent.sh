#!/usr/bin/env bash
# On-device smoke test: fires SMS SENDTO intents at Voice2SMS and fails if
# the process crashes (FATAL EXCEPTION) within the observation window.
#
# Regression coverage for: WV.h22.run NPE (pre-v1.3.0 prefetch race) and any
# future main-thread crash during the intent -> WebView load path.
#
# Requires: ANDROID_HOME exported, a connected device, the debug APK installed.
# Exit 0 on success, non-zero on failure.

set -euo pipefail

ADB="${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb"
PKG="com.voice2sms"
ITERATIONS="${ITERATIONS:-3}"
WAIT_SECS="${WAIT_SECS:-5}"
RECIPIENT="${RECIPIENT:-+15551234567}"
BODY="${BODY:-smoke-test}"

if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "FAIL: adb not found at $ADB" >&2
  exit 2
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "FAIL: no device connected (run 'adb connect HOST:PORT' first)" >&2
  exit 2
fi

if ! "$ADB" shell pm list packages | grep -q "^package:${PKG}$"; then
  echo "FAIL: ${PKG} not installed on device" >&2
  exit 2
fi

echo "Smoke test: ${ITERATIONS} iterations, ${WAIT_SECS}s window, recipient=${RECIPIENT}"

fail=0
for i in $(seq 1 "$ITERATIONS"); do
  echo "[$i/$ITERATIONS] force-stopping ${PKG} for cold start"
  "$ADB" shell am force-stop "$PKG"

  start_marker="$(date '+%m-%d %H:%M:%S.000')"
  # Percent-encode every byte outside [A-Za-z0-9._~-] so arbitrary bodies
  # (including & ? # spaces) round-trip through am start -d safely.
  body_enc="$(printf '%s' "$BODY" | LC_ALL=C awk '
    BEGIN { for (i = 0; i < 256; i++) ord[sprintf("%c", i)] = i }
    {
      for (i = 1; i <= length($0); i++) {
        c = substr($0, i, 1)
        if (c ~ /[A-Za-z0-9._~-]/) printf "%s", c
        else printf "%%%02X", ord[c]
      }
    }')"
  uri="sms:${RECIPIENT}?body=${body_enc}"

  echo "[$i/$ITERATIONS] firing intent: $uri"
  "$ADB" shell am start -a android.intent.action.SENDTO -d "$uri" >/dev/null

  sleep "$WAIT_SECS"

  crash_lines="$(
    "$ADB" logcat -d -v threadtime -T "$start_marker" \
      AndroidRuntime:E "*:S" 2>/dev/null |
      grep -E "FATAL EXCEPTION|WV\.h22\.run|NullPointerException" || true
  )"

  if [ -n "$crash_lines" ]; then
    echo "[$i/$ITERATIONS] FAIL — crash detected:"
    echo "$crash_lines" | sed 's/^/    /'
    fail=$((fail + 1))
  else
    echo "[$i/$ITERATIONS] OK — no crash in ${WAIT_SECS}s window"
  fi
done

if [ "$fail" -gt 0 ]; then
  echo ""
  echo "SMOKE TEST FAILED: $fail/$ITERATIONS iterations crashed"
  exit 1
fi

echo ""
echo "SMOKE TEST PASSED: 0/$ITERATIONS crashes"
