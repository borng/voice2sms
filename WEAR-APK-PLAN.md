# Voice2SMS Wear OS Companion — Implementation Plan

**Status:** Not yet implemented. Phone-side listener ready (commit `4a9c231`).
**Target device:** Galaxy Watch 6 (SM-R960), Wear OS 5 / Android 14. Also supports Wear OS 3+.
**Pre-reads:** `GALAXY-WATCH-SMS.md` for diagnosis + phone-side contract.

## Goal

Catch Google Assistant's `ACTION_SENDTO smsto:…` on the watch and route it through the phone's Voice2SMS → Google Voice pipeline instead of letting it silently fail via Samsung Messages.

## Why this is the only viable path

From `GALAXY-WATCH-SMS.md`:

- Wear OS 5 does NOT expose `ROLE_SMS` (`mRoleManager.isRoleAvailable is FALSE`). We cannot be default SMS on the watch.
- Google Voice on the watch works standalone but declares **no `smsto:` intent-filter** — Assistant can never route to it.
- Samsung Messages wins the SENDTO resolver by default and either (a) sends via carrier bypassing Voice2SMS, or (b) silently drops the send.
- Only a new Wear APK that declares a `SENDTO smsto:` intent-filter can intercept the Assistant intent and route it to Voice2SMS on the phone.

## Architecture

```
Watch                                     Phone
─────                                     ─────
Google Assistant                          WearSmsListenerService
  │ "Hey Google, text Alice hi"           (already shipped v1.5.0)
  ▼                                         │
ACTION_SENDTO smsto:+1…                     │
(user picks Voice2SMS in resolver,          │
 or Voice2SMS set as default)               │
  │                                         │
  ▼                                         │
SendToReceiverActivity                      │
  (Theme.NoDisplay, ~30 LOC)                │
  • parse smsto: URI + EXTRA_TEXT           │
  • validate + sanitize (shared validator)  │
  • UUID requestId                          │
  • Wearable.DataClient.putDataItem(…)      │
  • finish() immediately                    │
  │                                         │
  ▼ /voice2sms/requests/<requestId>         │
  ═══════════════(BT/Wi-Fi)════════════════►│
                                             ▼
                                          onDataChanged
                                            • validate
                                            • dedup / rate-limit
                                            • post notification
                                            • startActivity(SmsHandlerActivity)
                                                → GVoiceWebViewActivity
                                                → auto-send via GV
  Watch                                      │
  ◄═════════════(ack)═══════════════════════ │
                                          /voice2sms/ack
  AckListenerService                        {status: dispatched|failed|...}
  (optional v1.1 — show toast/notification)
```

## Scope v1 (minimum to ship)

Fire-and-forget send from watch. Phone does the heavy lifting.

- **Wear module** (new Gradle module `wear/` in the same repo, shared `applicationId=com.voice2sms`)
- **`SendToReceiverActivity`** — `Theme.NoDisplay` trampoline
- **Shared validator** — reuse `WearPayloadValidator` from `app/` via a tiny shared module (or copy it; one-file duplication is acceptable for v1)
- **DataClient send** — writes to `/voice2sms/requests/<uuid>`
- **Minimal manifest** — declares `SENDTO smsto:` + `SENDTO sms:` intent-filter, no launcher activity needed (optional stub activity showing "Installed; tap a recipient with Voice Assistant to use")

## Scope v1.1 (polish, can follow)

- **`WearAckListenerService`** — listens on `/voice2sms/ack`, shows a watch notification on `invalid_payload` / `rate_limited` / `failed`
- **Capability detection** — use `CapabilityClient` to detect whether the paired phone actually has a Voice2SMS build installed; if not, show "Voice2SMS on phone required"
- **Tiny launcher activity** — explains what the app does (one screen, mostly text)
- **Phone-side capability declaration** — phone advertises `voice2sms_sms_receiver` capability (one-line change to phone manifest)

## Scope v2 (later)

- Per-contact Wear tiles / shortcuts (bypass the resolver friction entirely)
- Retry queue on watch for dropped acks
- Settings toggle to set Voice2SMS as default SENDTO handler automatically (Wear OS equivalent of the phone's ROLE_SMS flow; verify if API exists)

---

## Concrete deliverables (v1)

### 1. `settings.gradle` (root) — register the new module

```groovy
include ':app', ':wear'
```

### 2. `wear/build.gradle` — new file

```groovy
plugins {
    id 'com.android.application'
}

// Reuse the git-sha / build-date helpers from app/build.gradle.
// Either duplicate them here or factor into a shared buildSrc convention.
def computeGitSha() { /* same as app/ */ }
def computeBuildDate() { /* same as app/ */ }

android {
    namespace 'com.voice2sms.wear'   // sub-namespace for R / BuildConfig
    compileSdk 35

    buildFeatures { buildConfig true }

    defaultConfig {
        applicationId "com.voice2sms"    // MUST match phone — GMS scope rule
        minSdk 30                        // Wear OS 3+
        targetSdk 34
        versionCode project.hasProperty('VERSION_CODE') ? project.property('VERSION_CODE').toInteger() : 1
        versionName project.hasProperty('VERSION_NAME') ? project.property('VERSION_NAME') : "1.5.0"
        buildConfigField 'String', 'GIT_SHA', "\"${computeGitSha()}\""
        buildConfigField 'String', 'BUILD_DATE', "\"${computeBuildDate()}\""
    }

    signingConfigs {
        // CRITICAL: same keystore as app/. GMS DataLayer trust depends on
        // matching application signature. Reference app/keystore.properties.
        release { /* same shape as app/build.gradle */ }
    }

    buildTypes {
        release { signingConfig signingConfigs.release }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation 'androidx.wear:wear:1.3.0'
    implementation 'com.google.android.gms:play-services-wearable:18.2.0'
    // No androidx.appcompat — we don't need AppCompat themes on Wear.

    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.json:json:20231013'
}
```

### 3. `wear/src/main/AndroidManifest.xml` — new file

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Wear OS standalone app — doesn't require phone app to be installed
         for the Wear APK to run. (It DOES require the phone app to actually
         send; we detect that via CapabilityClient in v1.1.) -->
    <uses-feature android:name="android.hardware.type.watch" />

    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:icon="@android:drawable/sym_action_chat"
        android:label="Voice2SMS"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault">

        <!-- Standalone flag — required for Wear apps distributed independently
             of the phone app. Without it the watch expects the phone app to
             also be installed and reachable at install time. -->
        <meta-data
            android:name="com.google.android.wearable.standalone"
            android:value="true" />

        <!-- The intercept target. No launcher, no UI.
             Appears in the SENDTO resolver next to Samsung Messages and
             Google Messages Wear. User sets as default on first use. -->
        <activity
            android:name=".SendToReceiverActivity"
            android:exported="true"
            android:theme="@android:style/Theme.NoDisplay">
            <intent-filter>
                <action android:name="android.intent.action.SENDTO" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="smsto" />
                <data android:scheme="sms" />
            </intent-filter>
        </activity>

        <!-- v1.1: ack listener for user-visible failure notifications.
        <service
            android:name=".WearAckListenerService"
            android:exported="true">
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
                <data android:scheme="wear" android:host="*" android:pathPrefix="/voice2sms/ack" />
            </intent-filter>
        </service>
        -->

    </application>
</manifest>
```

### 4. `wear/src/main/java/com/voice2sms/wear/SendToReceiverActivity.java` — new file (~80 LOC)

```java
package com.voice2sms.wear;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.json.JSONObject;

public class SendToReceiverActivity extends Activity {

    private static final String TAG = "V2SWear";
    private static final String PATH_PREFIX = "/voice2sms/requests/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
        finish(); // no UI
    }

    private void handleIntent(Intent intent) {
        Uri data = intent.getData();
        String phone = null;
        if (data != null) {
            String ssp = data.getSchemeSpecificPart();
            if (ssp != null) {
                int cut = indexOfAny(ssp, "?&");
                phone = (cut >= 0 ? ssp.substring(0, cut) : ssp).trim();
            }
        }

        String body = intent.getStringExtra("sms_body");
        if (body == null) body = intent.getStringExtra(Intent.EXTRA_TEXT);
        // URI query fallback — some callers encode body in opaque smsto:+1?body=…
        if (body == null && data != null && !data.isHierarchical()) {
            String ssp = data.getSchemeSpecificPart();
            int bIdx = ssp == null ? -1 : ssp.indexOf("body=");
            if (bIdx >= 0) {
                String v = ssp.substring(bIdx + 5);
                int amp = v.indexOf('&');
                if (amp >= 0) v = v.substring(0, amp);
                try {
                    body = java.net.URLDecoder.decode(v, "UTF-8");
                } catch (Exception e) {
                    body = v;
                }
            }
        }

        // Validate with the shared validator (copy of phone-side; see scope note).
        WearPayloadValidator.Request req;
        try {
            req = WearPayloadValidator.fromFields(phone, body, UUID.randomUUID().toString());
        } catch (WearPayloadValidator.ValidationException e) {
            Log.w(TAG, "reject local: " + e.getMessage());
            // Keep toast user-friendly — raw reason codes are for logs only.
            Toast.makeText(this, "Voice2SMS: " + friendly(e.getMessage()),
                    Toast.LENGTH_LONG).show();
            return;
        }

        sendToPhone(req);
        Toast.makeText(this, "Sending via Voice2SMS…", Toast.LENGTH_SHORT).show();
    }

    private void sendToPhone(WearPayloadValidator.Request req) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("phone", req.phone);
            obj.put("body", req.body);
            obj.put("requestId", req.requestId);
            byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);

            PutDataMapRequest put = PutDataMapRequest.create(PATH_PREFIX + req.requestId);
            put.getDataMap().putByteArray("payload", bytes);
            put.getDataMap().putLong("ts", System.currentTimeMillis());
            // setUrgent() forces prompt sync; default is batched/deferred.
            Wearable.getDataClient(this)
                    .putDataItem(put.asPutDataRequest().setUrgent())
                    .addOnFailureListener(e -> Log.w(TAG, "DataClient putDataItem failed", e));
        } catch (Exception e) {
            Log.w(TAG, "sendToPhone threw", e);
            Toast.makeText(this, "Voice2SMS: couldn't reach phone",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int idx = s.indexOf(chars.charAt(i));
            if (idx >= 0 && (best < 0 || idx < best)) best = idx;
        }
        return best;
    }

    private static String friendly(String reason) {
        switch (reason == null ? "" : reason) {
            case "invalid_phone": return "unrecognized phone number";
            case "empty_body":    return "empty message";
            case "body_too_long": return "message too long";
            case "empty_payload": return "empty request";
            default:              return "invalid request";
        }
    }
}
```

### 5. Shared payload validator

**Preferred:** factor `WearPayloadValidator` + the phone's JSON read path into a new `shared/` Gradle module consumed by both `app` and `wear`. But for v1 one-file duplication is fine — the validator is pure JVM with no dependencies, and we already have JUnit coverage on it.

**Duplication path (v1):** copy `app/src/main/java/com/voice2sms/wear/WearPayloadValidator.java` to `wear/src/main/java/com/voice2sms/wear/WearPayloadValidator.java`. Add a new static factory:

```java
/** Builds a Request from already-separated fields (watch side — skips JSON parse). */
public static Request fromFields(String phone, String body, String requestId)
        throws ValidationException {
    // Same validation rules as parse(byte[]), just skips the JSON decode step.
    if (phone == null || phone.trim().isEmpty()) throw new ValidationException("missing_phone");
    phone = phone.trim();
    if (!PHONE_RE.matcher(phone).matches()) throw new ValidationException("invalid_phone");
    if (body == null) throw new ValidationException("missing_body");
    String sanitized = sanitizeBody(body);
    if (sanitized.isEmpty()) throw new ValidationException("empty_body");
    if (sanitized.length() > MAX_BODY_LEN) throw new ValidationException("body_too_long");
    if (requestId == null || !REQUEST_ID_RE.matcher(requestId).matches())
        throw new ValidationException("invalid_requestId");
    return new Request(phone, sanitized, requestId);
}
```

Also port the test file. If we later extract to shared module, deletion is easy.

### 6. Unit tests — `wear/src/test/java/com/voice2sms/wear/SendToIntentParseTest.java`

Covers intent parsing edge cases specifically (complement to `WearPayloadValidatorTest` which covers JSON path):

- Standard `smsto:+15551234567` with `EXTRA_TEXT` body
- Opaque URI with embedded body `smsto:+15551234567?body=hi%20there`
- `sms:` scheme (treated identically to `smsto:`)
- URI with extra whitespace around number
- Missing body → validation rejects
- Non-numeric in phone → validation rejects

No Android runtime required — the parse logic should be a pure function testable on JVM. Extract intent-parsing to a static helper method `parseSmsToIntent(String ssp, String bodyExtra)` for testability.

### 7. Source-safety regression guards — add to `app/src/test/java/com/voice2sms/SourceSafetyTest.java`

Even though the Wear module is separate, the phone's safety test reads source files; add guards that read `wear/src/main/…` too:

```java
@Test public void wear_sendToReceiver_isNoDisplayTrampoline() { … }
@Test public void wear_manifest_declaresStandalone() { … }
@Test public void wear_manifest_declaresSendtoFilter() { … }
@Test public void wear_gradle_matchesPhoneApplicationId() { … }
```

These can read both modules' sources via adjusted paths (e.g., `../../../../wear/src/main/...`). Prevents accidental divergence of the contract.

### 8. Phone-side: advertise capability (v1.1, one-line addition)

In the existing phone `AndroidManifest.xml`, declare a Wearable capability so the Wear APK can discover whether Voice2SMS is installed on the paired phone:

```xml
<!-- Inside <application> -->
<meta-data
    android:name="com.google.android.gms.wearable.capability"
    android:resource="@xml/wearable_capabilities" />
```

Plus `app/src/main/res/xml/wearable_capabilities.xml`:

```xml
<wearable-capability android:name="voice2sms_sms_receiver" />
```

Zero code; GMS exposes this to the watch via `CapabilityClient.getCapability("voice2sms_sms_receiver")`. Watch uses it to gate the UI: "paired phone needs Voice2SMS installed".

---

## Distribution

### Local development
```
ANDROID_HOME=/opt/android-sdk ./gradlew :wear:assembleDebug
adb -s <watch-serial> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Confirmed working path — we're already ADB-connected to the watch in this session.

### CI — `.github/workflows/release.yml`
Extend to also build `:wear:assembleRelease` and publish the Wear APK alongside the phone APK in the GitHub Release assets.

### Play Console (later)
Wear APK distribution via Play requires matching `applicationId` + same keystore between phone and watch variants. This is already the plan.

---

## Testing flow (end-to-end, on-device)

1. Install phone APK v1.5.0+ (done)
2. Install `wear-debug.apk` via `adb install -r`
3. On watch: say "Hey Google, text [contact-with-phone] hello"
4. Assistant fires `SENDTO smsto:+1…` → resolver chooser appears (first time) → pick **Voice2SMS**, tap "Always"
5. Watch briefly shows "Sending via Voice2SMS…" toast, activity finishes
6. Phone (~1-2s later): Voice2SMS notification "Send via Google Voice — +1… — hello" AND/OR `GVoiceWebViewActivity` opens, auto-sends via GV
7. Recipient receives SMS from the user's GV number (not carrier)

### Phone-side adb log to watch for success
```
adb -s 192.168.1.154:41801 logcat -s V2SWear Voice2SMS ActivityManager \
    | grep -E "DATA_CHANGED|dispatched|GVoiceWebViewActivity"
```

Should see:
```
V2SWear: ...dispatched
ActivityManager: START u0 {cmp=com.voice2sms/.GVoiceWebViewActivity ...}
Voice2SMS: ...voice2sms(phone, body, autoSend=true, ...)
```

### Watch-side adb log to watch
```
adb -s 192.168.1.157:<port> logcat -s V2SWear \
    | grep -E "SendToReceiver|DataClient"
```

---

## Edge cases & how v1 handles each

| Case | Phone-side behavior | Watch-side behavior |
|---|---|---|
| Phone not paired / BT off | DataClient buffers, syncs on reconnect | User sees "Sending via Voice2SMS…" immediately; if they care, they notice the SMS was delayed when it arrives |
| Paired phone doesn't have Voice2SMS | Nothing happens; DataItem orphans | v1: user sees "Sending via Voice2SMS…" but no delivery; v1.1: CapabilityClient pre-check catches this |
| User ignores resolver and picks Samsung Messages | Existing failure mode (unchanged) | N/A |
| Double-tap Send on watch | Phone dedups by requestId, acks `duplicate` | v1: user sees toast twice, only one SMS sent; acceptable |
| Phone BAL blocks startActivity | Notification fires on phone; user taps it | N/A |
| Invalid phone/body at watch-side | N/A (never leaves watch) | Toast with friendly error |
| Invalid phone/body caught by phone validator (unexpected) | `invalid_payload` ack | v1: ack dropped silently; v1.1: `WearAckListenerService` surfaces as watch notification |

---

## Open decisions (decide during implementation)

1. **Shared module vs duplication for `WearPayloadValidator`** — recommend duplication for v1 (80 LOC file, isolated). Factor to shared module only if a third consumer appears.
2. **Tiny launcher activity** — ship one? Helps users find the app in the launcher; otherwise it's invisible. Recommend: yes, minimal, one screen of explanatory text.
3. **`setUrgent()` on DataClient** — yes by default; watch user just spoke and hit send, latency matters. Falls back to normal sync if link is flaky.
4. **Wear OS 3 vs 4 vs 5 as min SDK** — recommend `minSdk 30` (Wear OS 3). Covers all supported Galaxy Watch generations with current Google support.

---

## Sequencing

**Hour 1:** Module setup, manifest, empty SendToReceiverActivity, build passes
**Hour 2:** Intent parsing + WearPayloadValidator port + unit tests
**Hour 3:** DataClient send, manual test on physical watch
**Hour 4:** Polish — toasts, friendly errors, docs, commit
**Hour 5 (optional v1.1):** CapabilityClient detection, ack listener, tiny launcher activity

Total for v1: ~4 focused hours given the phone-side contract is already locked.

---

## What does NOT change on the phone

Everything in commit `4a9c231` (v1.5.0) is complete and won't need revisiting for v1. The Wear APK builds against the contract as documented. The only phone-side addition anticipated is the **capability declaration for v1.1** (two lines: one `<meta-data>` + one XML resource file).
