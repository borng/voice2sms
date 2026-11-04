package com.voice2sms.sendlater.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * gate-residuals (debug hooks): DEBUG_ENABLE sets the master switch and applies it through
 * SendLaterEngine.applySwitch; DEBUG_SEED tells the UI (ACTION_STATE_CHANGED) after persisting;
 * DEBUG_DUMP leads with the switch so the smoke script can restore it. Fictional numbers only.
 */
public class DebugEnableTest {

    private static final String E164 = "+1202" + "555" + "0142";
    private static final Path PKG = Paths.get("src/main/java/com/voice2sms/sendlater/debug");
    private static final Path DEBUG_MANIFEST = Paths.get("src/debug/AndroidManifest.xml");

    private SendLaterDebugCommandsTest.FakeBackend b;
    private SendLaterDebugCommands c;

    @Before public void setUp() {
        b = new SendLaterDebugCommandsTest.FakeBackend();
        c = new SendLaterDebugCommands(b);
    }

    @Test public void enableOnSetsThePreferenceThenAppliesTheSwitch() {
        assertEquals("enabled=true previous=false", c.enable(Boolean.TRUE));
        assertTrue(b.enabled);
        assertEquals(Arrays.asList("read", "set:true", "applySwitch:true"), b.calls);
    }

    @Test public void enableOffReportsThePreviousValue() {
        b.enabled = true;
        assertEquals("enabled=false previous=true", c.enable(Boolean.FALSE));
        assertFalse(b.enabled);
        assertEquals(Arrays.asList("read", "set:false", "applySwitch:false"), b.calls);
    }

    @Test public void enableWithoutOnChangesNothing() {
        b.enabled = true;
        assertEquals("error=no_on", c.enable(null));
        assertTrue(b.enabled);
        assertTrue(b.calls.toString(), b.calls.isEmpty());
    }

    @Test public void enableNeverReachesTheSender() {
        c.enable(Boolean.TRUE);
        assertEquals(0, b.onDueCalls);
        assertEquals(0, b.mutations());
    }

    @Test public void seedBroadcastsStateChangedAfterPersisting() {
        String r = c.seed(E164, "see you at five", 3, true, null, false, null);
        assertTrue(r, r.startsWith("id="));
        assertEquals(1, b.records.size());
        assertEquals(1, b.alarmsRegistered);
        assertEquals(Arrays.asList("stateChanged"), b.calls);
    }

    @Test public void aRefusedSeedBroadcastsNothing() {
        assertEquals("error=bad_e164", c.seed("nope", "x", 3, true, null, false, null));
        assertEquals("error=no_text", c.seed(E164, " ", 3, true, null, false, null));
        assertTrue(b.calls.isEmpty());
    }

    @Test public void dumpLeadsWithTheSwitch() throws Exception {
        b.enabled = true;
        String json = c.dump();
        assertTrue(json, json.startsWith("{\"enabled\":true,\"now\":"));
        JSONObject o = new JSONObject(json);
        assertTrue(o.getBoolean("enabled"));
        b.enabled = false;
        assertTrue(c.dump().startsWith("{\"enabled\":false,"));
    }

    // ------------------------------------------------------------------ wiring

    @Test public void receiverWiresEnableAndTheBackendUsesTheSwitchPath() throws IOException {
        String src = read(PKG.resolve("SendLaterDebugReceiver.java"));
        assertTrue(src.contains("SendLaterDebugCommands.ACTION_ENABLE.equals(action)"));
        assertTrue(src.contains("c.enable("));
        assertTrue("the preference is written synchronously before the switch re-reads it",
                src.contains(".putBoolean(SendLaterSettings.KEY_ENABLED, on).commit()"));
        assertTrue(src.contains("SendLaterEngine.applySwitch(app, this::broadcastStateChanged)"));
        int bc = src.indexOf("public void broadcastStateChanged()");
        assertTrue(bc > 0);
        String body = src.substring(bc, src.indexOf("}", bc));
        assertTrue(body, body.contains("SendLaterIntents.ACTION_STATE_CHANGED") && body.contains(".setPackage(app.getPackageName())"));
        assertEquals("com.voice2sms.sendlater.DEBUG_ENABLE", SendLaterDebugCommands.ACTION_ENABLE);
        assertEquals("on", SendLaterDebugCommands.EXTRA_ON);
    }

    @Test public void debugManifestDeclaresEnable() throws IOException {
        String m = read(DEBUG_MANIFEST);
        assertTrue(m.contains("<action android:name=\"com.voice2sms.sendlater.DEBUG_ENABLE\" />"));
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), "UTF-8");
    }
}
