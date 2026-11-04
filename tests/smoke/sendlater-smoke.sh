#!/usr/bin/env bash
# On-device smoke test for Send Later (debug build only).
#
# Drives the adb-only SendLaterDebugReceiver (app/src/debug/AndroidManifest.xml):
#   seed a record 3 min ahead -> fire the T-5 (last-call) alarm via the debug broadcast ->
#   assert the last-call notification -> EVALUATE the ladder (applies nothing) -> inject a fake
#   inbound reply -> assert HELD -> cancel -> assert CANCELED -> no FATAL EXCEPTION in logcat.
#
# SAFETY (public repo, I9): there is NO default recipient. The recipient comes from
# SENDLATER_TEST_RECIPIENT (or RECIPIENT), else the gitignored file tests/smoke/.recipient.
# Nothing is ever sent unless SEND=1, and SEND=1 additionally requires the recipient to equal
# SENDLATER_ALLOWED_RECIPIENT (env, else the gitignored file tests/smoke/.allowed-recipient).
# Without SEND=1 the seeded record is stored at rung R2, which the engine never auto-sends.
#
# Env: ADB (adb binary or wrapper), SERIAL (device serial), INSTALL=1 (install the debug APK),
#      APK (APK path), SEND=1 (also run one real guarded send), SEND_WAIT_SECS (default 90).
# Exit: 0 pass, 1 assertion failed, 2 setup problem or refused (no recipient, no device, guard).

set -euo pipefail

PKG="com.voice2sms"
RECEIVER="${PKG}/com.voice2sms.sendlater.debug.SendLaterDebugReceiver"
ACTION_PREFIX="com.voice2sms.sendlater.DEBUG_"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SEND="${SEND:-0}"
SEND_WAIT_SECS="${SEND_WAIT_SECS:-90}"
SERIAL="${SERIAL:-}"
OFFSET_MIN=3

die_setup() { echo "SETUP: $*" >&2; exit 2; }

fail_count=0
fail() { echo "FAIL: $*" >&2; fail_count=$((fail_count + 1)); }
ok() { echo "OK:   $*"; }

# ------------------------------------------------------------------ recipient (no default)

# First non-empty, non-comment line of a file, CR stripped.
read_first_line() {
  [ -f "$1" ] || return 0
  tr -d '\r' < "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' |
    grep -v -e '^#' -e '^$' | head -n 1 || true
}

# Normalizes a number to E.164 (+digits). 10-digit numbers without '+' are taken as NANP.
to_e164() {
  local raw="$1" d
  d="$(printf '%s' "$raw" | tr -cd '0-9')"
  case "$raw" in
    +*) ;;
    *) if [ "${#d}" -eq 10 ]; then d="1${d}"; fi ;;
  esac
  if [ "${#d}" -lt 8 ] || [ "${#d}" -gt 15 ]; then return 1; fi
  printf '+%s' "$d"
}

mask() { local d; d="$(printf '%s' "$1" | tr -cd '0-9')"; printf '+…%s' "${d: -4}"; }

recipient_raw="${SENDLATER_TEST_RECIPIENT:-${RECIPIENT:-}}"
if [ -z "$recipient_raw" ]; then
  recipient_raw="$(read_first_line "${SCRIPT_DIR}/.recipient")"
fi
if [ -z "$recipient_raw" ]; then
  echo "SETUP: no test recipient. Set SENDLATER_TEST_RECIPIENT or write the number to" >&2
  echo "       tests/smoke/.recipient (gitignored). There is deliberately no default." >&2
  exit 2
fi
RECIPIENT_E164="$(to_e164 "$recipient_raw")" || die_setup "test recipient is not a phone number"

ALLOWED_E164=""
allowed_raw="${SENDLATER_ALLOWED_RECIPIENT:-}"
if [ -z "$allowed_raw" ]; then
  allowed_raw="$(read_first_line "${SCRIPT_DIR}/.allowed-recipient")"
fi
if [ -n "$allowed_raw" ]; then
  ALLOWED_E164="$(to_e164 "$allowed_raw")" || die_setup "allowed recipient is not a phone number"
fi

if [ "$SEND" = "1" ]; then
  [ -n "$ALLOWED_E164" ] || die_setup "SEND=1 needs SENDLATER_ALLOWED_RECIPIENT (or tests/smoke/.allowed-recipient)"
  [ "$RECIPIENT_E164" = "$ALLOWED_E164" ] ||
    die_setup "SEND=1 refused: the test recipient is not the allowed recipient (I9)"
elif [ "$SEND" != "0" ]; then
  die_setup "SEND must be 0 or 1"
fi

# ------------------------------------------------------------------ adb

if [ -z "${ADB:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -x "${ANDROID_HOME}/platform-tools/adb" ]; then
    ADB="${ANDROID_HOME}/platform-tools/adb"
  elif command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
  else
    # WSL: Windows platform-tools through interop.
    for cand in /mnt/c/Users/*/platform-tools/adb.exe; do
      if [ -x "$cand" ]; then ADB="$cand"; break; fi
    done
  fi
fi
[ -n "${ADB:-}" ] || die_setup "adb not found: set ADB, ANDROID_HOME, or put adb on PATH"

# adb with the optional serial; CR stripped (Windows adb.exe and wrappers).
adbq() {
  if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi | tr -d '\r'
}

state="$(adbq get-state 2>/dev/null || true)"
[ "$state" = "device" ] || die_setup "no device (set SERIAL when several are attached)"

# Output is captured before grepping: `grep -q` closing a pipe early trips pipefail.
installed() {
  local pkgs
  pkgs="$(adbq shell pm list packages --user 0 "$PKG" 2>/dev/null || true)"
  printf '%s\n' "$pkgs" | grep -qx "package:${PKG}"
}

host_path_for_adb() {
  # Windows adb.exe needs a Windows path for files on the WSL side.
  case "$ADB" in
    *.exe) if command -v wslpath >/dev/null 2>&1; then wslpath -w "$1"; return; fi ;;
  esac
  if [ "${ADB_WINDOWS_PATHS:-0}" = "1" ] && command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$1"; return
  fi
  printf '%s' "$1"
}

if [ "${INSTALL:-0}" = "1" ] || ! installed; then
  apk="${APK:-}"
  if [ -z "$apk" ]; then
    for cand in "${REPO_DIR}"/app/build/outputs/apk/debug/*.apk; do
      if [ -f "$cand" ]; then apk="$cand"; break; fi
    done
  fi
  { [ -n "$apk" ] && [ -f "$apk" ]; } || die_setup "${PKG} not installed and no debug APK found (./gradlew :app:assembleDebug)"
  echo "Installing $(basename "$apk")"
  adbq install -r -t "$(host_path_for_adb "$apk")" | tail -n 1
  installed || die_setup "install failed"
fi

adbq shell run-as "$PKG" true >/dev/null 2>&1 ||
  die_setup "${PKG} is not a debuggable build: the debug receiver exists only in the debug APK"

# ------------------------------------------------------------------ helpers

# Sends a debug broadcast; prints the receiver's result data.
bcast() {
  local action="$1"; shift
  local out
  out="$(adbq shell am broadcast --user 0 -f 32 -n "$RECEIVER" -a "${ACTION_PREFIX}${action}" "$@" 2>&1 || true)"
  printf '%s\n' "$out" | sed -n 's/.*data="\(.*\)".*/\1/p' | tail -n 1
}

dump_json() {
  bcast DUMP >/dev/null
  local json
  json="$(adbq shell run-as "$PKG" cat "cache/sendlater-dump.json" 2>/dev/null || true)"
  if [ -z "$json" ]; then
    json="$(adbq logcat -d -s SendLaterDump:I 2>/dev/null | tail -n 1 | sed 's/^[^{]*//')"
  fi
  printf '%s' "$json"
}

status_of() {
  { printf '%s' "$2" | grep -o "\"id\":$1,\"status\":\"[A-Z_]*\"" || true; } |
    sed 's/.*"status":"\([A-Z_]*\)"/\1/' | head -n 1
}

# expect_status id wanted actual label
expect_status() {
  if [ "$3" = "$2" ]; then ok "$4: $2"; else fail "$4: status '${3:-missing}', expected $2"; fi
}

wait_status() { # id, wanted regex, seconds -> prints final status
  local id="$1" want="$2" secs="$3" s=""
  for _ in $(seq 1 "$secs"); do
    s="$(status_of "$id" "$(dump_json)")"
    if printf '%s' "$s" | grep -Eq "^(${want})$"; then break; fi
    sleep 1
  done
  printf '%s' "$s"
}

id_of() { printf '%s' "$1" | sed -n 's/^id=\([0-9][0-9]*\).*/\1/p'; }

start_marker="$(adbq shell date "'+%m-%d %H:%M:%S.000'" | tail -n 1)"
stamp="$(date +%s)"

echo "Send Later smoke: recipient $(mask "$RECIPIENT_E164"), SEND=${SEND}, device ${SERIAL:-default}"

# ------------------------------------------------------------------ optional guarded real send

if [ "$SEND" = "1" ]; then
  r="$(bcast SEED --es e164 "$RECIPIENT_E164" --es text "'sendlater-smoke-send-${stamp}'" \
        --ei offset_min "$OFFSET_MIN" --ez hold true --ez allow_send true --es allowed_recipient "$ALLOWED_E164")"
  send_id="$(id_of "$r")"
  if [ -z "$send_id" ]; then
    fail "SEND seed failed: ${r:-no result}"
  else
    case "$r" in *rung=R1*) ;; *) fail "SEND seed not at rung R1: $r" ;; esac
    r="$(bcast FIRE --el id "$send_id" --ez allow_send true --es allowed_recipient "$ALLOWED_E164")"
    echo "      FIRE: $r"
    s="$(wait_status "$send_id" 'SENT_VERIFIED|FAILED|HELD|CANCELED|EXPIRED|SELF_PREEMPTED' "$SEND_WAIT_SECS")"
    if [ "$s" = "SENT_VERIFIED" ]; then ok "guarded send: SENT_VERIFIED (id $send_id)"
    else fail "guarded send ended as '${s:-unknown}' (id $send_id), expected SENT_VERIFIED"; fi
    if [ "$s" = "SCHEDULED" ] || [ "$s" = "HELD" ]; then bcast CANCEL --el id "$send_id" >/dev/null; fi
  fi
fi

# ------------------------------------------------------------------ main flow (never sends)

r="$(bcast SEED --es e164 "$RECIPIENT_E164" --es text "'sendlater-smoke-${stamp}'" \
      --ei offset_min "$OFFSET_MIN" --ez hold true)"
id="$(id_of "$r")"
[ -n "$id" ] || { echo "FAIL: seed returned '${r:-nothing}' (is the debug APK installed?)" >&2; exit 1; }
ok "seeded id $id ($r)"
case "$r" in *rung=R2*) ;; *) fail "default seed must be rung R2 (never auto-sent): $r" ;; esac

expect_status "$id" SCHEDULED "$(status_of "$id" "$(dump_json)")" "after seed"

alarms="$(adbq shell dumpsys alarm 2>/dev/null || true)"
if printf '%s\n' "$alarms" | grep -q "$PKG"; then ok "alarms registered"
else echo "WARN: no ${PKG} alarm in dumpsys alarm (exact-alarm permission?)"; fi

# T-5: `cmd alarm` cannot fire one specific alarm, so the debug broadcast calls onLastCall.
r="$(bcast LAST_CALL --el id "$id")"
echo "      LAST_CALL: $r"
found=0
for _ in 1 2 3 4 5; do
  notes="$(adbq shell dumpsys notification --noredact 2>/dev/null || true)"
  if printf '%s\n' "$notes" | grep -q 'channel=sendlater_last_call'; then
    found=1; break
  fi
  sleep 1
done
if [ "$found" = 1 ]; then ok "last-call notification posted"
else fail "no last-call notification (channel sendlater_last_call); notifications granted?"; fi

r="$(bcast EVALUATE --el id "$id")"
echo "      EVALUATE: $r"
case "$r" in *applied=none*) ;; *) fail "EVALUATE did not report applied=none: $r" ;; esac
expect_status "$id" SCHEDULED "$(status_of "$id" "$(dump_json)")" "after EVALUATE (applies nothing)"

r="$(bcast INBOUND --es e164 "$RECIPIENT_E164" --es text "'smoke-reply-${stamp}'")"
echo "      INBOUND: $r"
expect_status "$id" HELD "$(wait_status "$id" 'HELD' 10)" "after inbound reply"

r="$(bcast CANCEL --el id "$id")"
echo "      CANCEL: $r"
expect_status "$id" CANCELED "$(wait_status "$id" 'CANCELED' 10)" "after cancel"

log="$(adbq logcat -d -v threadtime -T "$start_marker" AndroidRuntime:E '*:S' 2>/dev/null || true)"
crash="$(printf '%s\n' "$log" | grep -A 2 'FATAL EXCEPTION' | grep "Process: ${PKG}" || true)"
if [ -z "$crash" ]; then ok "no FATAL EXCEPTION for ${PKG}"; else fail "crash in logcat: $crash"; fi

echo ""
if [ "$fail_count" -gt 0 ]; then
  echo "SENDLATER SMOKE FAILED: ${fail_count} check(s)"
  exit 1
fi
echo "SENDLATER SMOKE PASSED"
