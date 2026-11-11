#!/usr/bin/env bash
# On-device smoke test: fires SMS SENDTO intents at Voice2SMS and fails if
# the process crashes (FATAL EXCEPTION) within the observation window.
#
# Regression coverage for: WV.h22.run NPE (pre-v1.3.0 prefetch race) and any
# future main-thread crash during the intent -> WebView load path.
#
# Each intent types BODY into the Google Voice message box. Force-stopping the
# app is not a cleanup (the WebView profile and any draft Google Voice keeps
# survive it), so after the last iteration the script deletes the text with
# real key events and reads the screen's accessibility dump back to confirm
# the box is empty (CLEANUP=0 skips this).
#
# Requires: a connected device, the APK installed. adb comes from ADB, else
# $ANDROID_HOME/platform-tools/adb, else adb on PATH, else Windows
# platform-tools/adb.exe under /mnt/c/Users/* (WSL). CR is stripped from all
# adb output, so plain Windows adb.exe works without a wrapper.
#
# Exit 0 pass, 1 crash detected, 2 setup problem, 3 no crash but the
# smoke-test text is still in the message box after cleanup.

set -euo pipefail

PKG="com.voice2sms"
ITERATIONS="${ITERATIONS:-3}"
WAIT_SECS="${WAIT_SECS:-5}"
RECIPIENT="${RECIPIENT:-+12025550123}"
BODY="${BODY:-smoke-test}"
SERIAL="${SERIAL:-}"
CLEANUP="${CLEANUP:-1}"
CLEANUP_WAIT_SECS="${CLEANUP_WAIT_SECS:-30}"

if [ -z "${ADB:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -x "${ANDROID_HOME}/platform-tools/adb" ]; then
    ADB="${ANDROID_HOME}/platform-tools/adb"
  elif [ -x /opt/android-sdk/platform-tools/adb ]; then
    ADB=/opt/android-sdk/platform-tools/adb
  elif command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
  else
    # WSL: Windows platform-tools through interop.
    for cand in /mnt/c/Users/*/platform-tools/adb.exe; do
      if [ -x "$cand" ]; then ADB="$cand"; break; fi
    done
  fi
fi

if [ -z "${ADB:-}" ] || ! command -v "$ADB" >/dev/null 2>&1; then
  echo "FAIL: adb not found (set ADB, ANDROID_HOME, or put adb on PATH)" >&2
  exit 2
fi

# adb with the optional serial; CR stripped (Windows adb.exe prints CRLF).
adbq() {
  if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi | tr -d '\r'
}

state="$(adbq get-state 2>/dev/null || true)"
if [ "$state" != "device" ]; then
  echo "FAIL: no device connected (run 'adb connect HOST:PORT' first; set SERIAL when several are attached)" >&2
  exit 2
fi

# Output is captured, then grepped from a here-string: piping a capture into
# `grep -q` lets the writer die of SIGPIPE, and pipefail turns a match into a
# failure. --user 0: a Secure Folder / Private space user is not shell-accessible.
pkgs="$(adbq shell pm list packages --user 0 "$PKG" 2>/dev/null || true)"
if ! grep -qx "package:${PKG}" <<<"$pkgs"; then
  echo "FAIL: ${PKG} not installed on device" >&2
  exit 2
fi

echo "Smoke test: ${ITERATIONS} iterations, ${WAIT_SECS}s window, recipient=${RECIPIENT}"

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

fail=0
for i in $(seq 1 "$ITERATIONS"); do
  echo "[$i/$ITERATIONS] force-stopping ${PKG} for cold start"
  adbq shell am force-stop "$PKG" >/dev/null

  # Device clock, so a host/device time-zone difference cannot hide a crash.
  start_marker="$(adbq shell date "'+%m-%d %H:%M:%S.000'" 2>/dev/null || true)"
  if ! grep -qE '^[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.000$' <<<"$start_marker"; then
    start_marker="$(date '+%m-%d %H:%M:%S.000')"
  fi

  echo "[$i/$ITERATIONS] firing intent: $uri"
  adbq shell am start -a android.intent.action.SENDTO -d "$uri" >/dev/null

  sleep "$WAIT_SECS"

  log="$(adbq logcat -d -v threadtime -T "$start_marker" AndroidRuntime:E "*:S" 2>/dev/null || true)"
  crash_lines="$(grep -E "FATAL EXCEPTION|WV\.h22\.run|NullPointerException" <<<"$log" || true)"

  if [ -n "$crash_lines" ]; then
    echo "[$i/$ITERATIONS] FAIL — crash detected:"
    sed 's/^/    /' <<<"$crash_lines"
    fail=$((fail + 1))
  else
    echo "[$i/$ITERATIONS] OK — no crash in ${WAIT_SECS}s window"
  fi
done

# ------------------------------------------------------------------ cleanup
#
# The dump is the screen's accessibility tree (WebView text fields included).
# It can hold private screen content: it stays in a variable, is only grepped,
# and is deleted from the device. Nothing from it is printed.

DUMP_PATH=/data/local/tmp/v2s-smoke-dump.xml

# XML-escape BODY the way uiautomator writes attribute values.
xml_escape() {
  local s="$1"
  s="${s//&/&amp;}"; s="${s//</&lt;}"; s="${s//>/&gt;}"; s="${s//\"/&quot;}"; s="${s//\'/&apos;}"
  printf '%s' "$s"
}
body_xml="$(xml_escape "$BODY")"

# Prints the dump, or nothing when uiautomator could not take one.
screen_dump() {
  local out
  adbq shell rm -f "$DUMP_PATH" >/dev/null 2>&1 || true
  out="$(adbq shell uiautomator dump "$DUMP_PATH" 2>/dev/null || true)"
  if grep -q "dumped to" <<<"$out"; then
    adbq exec-out cat "$DUMP_PATH" 2>/dev/null || true
  fi
  adbq shell rm -f "$DUMP_PATH" >/dev/null 2>&1 || true
}

# 0 = Voice2SMS is on screen and a node's text holds BODY, 1 = it is on screen
# without it, 2 = no usable dump (locked screen, another app, dump failed).
body_on_screen() {
  local dump texts
  dump="$(screen_dump)"
  grep -qF "package=\"${PKG}\"" <<<"$dump" || return 2
  texts="$(grep -oE 'text="[^"]*"' <<<"$dump" || true)"
  if grep -qF "$body_xml" <<<"$texts"; then return 0; fi
  return 1
}

app_has_focus() {
  local win focus
  win="$(adbq shell dumpsys window 2>/dev/null || true)"
  focus="$(grep -E 'mCurrentFocus=' <<<"$win" || true)"
  grep -qF "$PKG" <<<"$focus"
}

# Deletes the box's text with real key events, so the GV page (and any draft
# it saves) sees an ordinary user edit. The caller checks focus first: keys
# must never land in another app.
clear_composer() {
  local n dels
  n=$(( ${#BODY} + 8 ))
  dels="$(printf ' 67%.0s' $(seq 1 "$n"))"   # KEYCODE_DEL x n
  # shellcheck disable=SC2086
  adbq shell input keyevent 123 $dels >/dev/null   # KEYCODE_MOVE_END first
}

cleanup_rc=0
if [ "$CLEANUP" = "1" ]; then
  echo ""
  echo "Cleanup: removing the '${BODY}' text from the message box"
  seen=1
  rc=2
  deadline=$(( $(date +%s) + CLEANUP_WAIT_SECS ))
  # The compose flow can finish after WAIT_SECS; wait for the text to appear first.
  while :; do
    rc=0; body_on_screen || rc=$?
    if [ "$rc" -eq 0 ]; then seen=0; break; fi
    [ "$(date +%s)" -ge "$deadline" ] && break
    sleep 2
  done

  if [ "$seen" -ne 0 ]; then
    if [ "$rc" -eq 2 ]; then
      echo "Cleanup: WARNING — could not read the screen (locked, another app in front, or dump failed)."
      echo "         Check by hand: open Voice2SMS and empty the message box if it shows '${BODY}'."
    else
      echo "Cleanup: OK — no '${BODY}' text in the message box"
    fi
  else
    cleared=1
    for pass in 1 2 3; do
      if ! app_has_focus; then
        echo "Cleanup: WARNING — ${PKG} does not have focus; no keys sent."
        rc=2
        break
      fi
      clear_composer
      sleep 2   # let the page process the edit (and save the now-empty draft)
      rc=0; body_on_screen || rc=$?
      if [ "$rc" -eq 1 ]; then cleared=0; break; fi
      if [ "$rc" -eq 2 ]; then break; fi
      echo "Cleanup: pass $pass left text behind, retrying"
    done
    if [ "$cleared" -eq 0 ]; then
      echo "Cleanup: OK — message box emptied"
    elif [ "$rc" -eq 2 ]; then
      echo "Cleanup: FAIL — could not clear or read back the message box."
      echo "         Open Voice2SMS and delete '${BODY}' from the message box by hand."
      cleanup_rc=3
    else
      echo "Cleanup: FAIL — '${BODY}' is still in the message box. Open Voice2SMS and delete it by hand."
      cleanup_rc=3
    fi
  fi
fi

if [ "$fail" -gt 0 ]; then
  echo ""
  echo "SMOKE TEST FAILED: $fail/$ITERATIONS iterations crashed"
  exit 1
fi

if [ "$cleanup_rc" -ne 0 ]; then
  echo ""
  echo "SMOKE TEST: 0/$ITERATIONS crashes, but the '${BODY}' text was left in the message box"
  exit "$cleanup_rc"
fi

echo ""
echo "SMOKE TEST PASSED: 0/$ITERATIONS crashes"
