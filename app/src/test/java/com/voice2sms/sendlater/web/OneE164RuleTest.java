package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.store.ThreadDirectory;
import com.voice2sms.sendlater.voice.logic.PhoneNumbers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * D38: one E.164 rule everywhere: "+", a digit 1-9, then digits, 7-15 digits in all
 * ({@link PhoneNumbers#isE164}). ThreadDirectory delegates; CapturePayload, DirectoryReport and
 * ThreadUrl all use it, so "+0..." is rejected by every capture and URL path; inject.js mirrors
 * it. Numbers are fictional (555-01xx).
 */
public class OneE164RuleTest {

    private static final String[] VALID = {
            "+15555550123", "+445555550100", "+1234567", "+123456789012345", "+9555555"};
    private static final String[] INVALID = {
            null, "", "+", "15555550123", "+05555550123", "+0123456", "+123456",
            "+1234567890123456", "+1 5555550123", "+1-555-555-0123", " +15555550123",
            "+15555550123 ", "++15555550123", "+1555555012a", "+\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668"};

    @Test public void phoneNumbersIsE164() {
        for (String s : VALID) assertTrue(s, PhoneNumbers.isE164(s));
        for (String s : INVALID) assertFalse(String.valueOf(s), PhoneNumbers.isE164(s));
    }

    @Test public void threadDirectoryDelegates() {
        for (String s : VALID) assertTrue(s, ThreadDirectory.isE164(s));
        for (String s : INVALID) assertFalse(String.valueOf(s), ThreadDirectory.isE164(s));
    }

    @Test public void toE164OutputPassesTheRule() {
        String[] raw = {"(555) 555-0123", "1 555 555 0123", "+1 555 555 0123", "+44 5555 550100"};
        for (String r : raw) {
            String e = PhoneNumbers.toE164(r, "US");
            assertNotNull(r, e);
            assertTrue(r, PhoneNumbers.isE164(e));
        }
    }

    private static String capture(String e164) throws Exception {
        JSONObject o = new JSONObject();
        o.put("e164", e164);
        o.put("displayName", "Test Contact");
        o.put("draftText", "see you soon");
        return o.toString();
    }

    @Test public void capturePayloadUsesTheRule() throws Exception {
        for (String s : VALID) assertNotNull(s, CapturePayload.parse(capture(s)));
        assertNull("+0 is rejected", CapturePayload.parse(capture("+05555550123")));
        assertNull(CapturePayload.parse(capture("+0123456")));
        assertNull(CapturePayload.parse(capture("+123456")));
        assertNull(CapturePayload.parse(capture("+1234567890123456")));
    }

    @Test public void directoryReportUsesTheRule() throws Exception {
        JSONArray a = new JSONArray();
        a.put(new JSONObject().put("displayName", "Test Contact").put("e164", "+15555550123"));
        a.put(new JSONObject().put("displayName", "Zero Contact").put("e164", "+05555550123"));
        a.put(new JSONObject().put("displayName", "Short Contact").put("e164", "+123456"));
        java.util.List<DirectoryReport.Entry> out = DirectoryReport.parse(a.toString());
        assertEquals(1, out.size());
        assertEquals("+15555550123", out.get(0).e164);
    }

    @Test public void threadUrlKeepsTheItemIdShapeButRejectsPlusZero() {
        assertEquals("+15555550123",
                ThreadUrl.oneToOneE164("https://voice.google.com/u/0/messages?itemId=t.%2B15555550123"));
        assertEquals("+15555550123",
                ThreadUrl.oneToOneE164("https://voice.google.com/u/0/messages?itemId=t.+15555550123"));
        assertNull(ThreadUrl.oneToOneE164("https://voice.google.com/u/0/messages?itemId=t.%2B05555550123"));
        assertNull(ThreadUrl.oneToOneE164("https://voice.google.com/u/0/messages?itemId=t.+05555550123"));
        assertFalse(ThreadUrl.isThreadOf(
                "https://voice.google.com/u/0/messages?itemId=t.%2B05555550123", "+05555550123"));
        assertNull(ThreadUrl.oneToOneE164("https://voice.google.com/u/0/messages?itemId=t.g.abc"));
    }

    @Test public void threadUrlOfUsesTheRule() {
        String u = ThreadUrl.threadUrlOf("+15555550123");
        assertEquals("https://voice.google.com/u/0/messages?itemId=t.%2B15555550123", u);
        assertTrue(ThreadUrl.isThreadOf(u, "+15555550123"));
        assertNull(ThreadUrl.threadUrlOf("+05555550123"));
        assertNull(ThreadUrl.threadUrlOf("+123456"));
        assertNull(ThreadUrl.threadUrlOf(null));
        for (String s : VALID) assertTrue(s, ThreadUrl.isThreadOf(ThreadUrl.threadUrlOf(s), s));
    }

    @Test public void noOtherE164PatternsInTheWebAndStoreSources() throws IOException {
        Path[] files = {
                Paths.get("src/main/java/com/voice2sms/sendlater/web/CapturePayload.java"),
                Paths.get("src/main/java/com/voice2sms/sendlater/web/DirectoryReport.java"),
                Paths.get("src/main/java/com/voice2sms/sendlater/web/ThreadUrl.java"),
                Paths.get("src/main/java/com/voice2sms/sendlater/store/ThreadDirectory.java")};
        for (Path p : files) {
            String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            assertTrue(p + " uses PhoneNumbers.isE164", src.contains("PhoneNumbers.isE164("));
            assertFalse(p + " has no private E164 pattern", src.contains("Pattern E164"));
        }
    }

    @Test public void injectJsItemIdGateRejectsPlusZero() throws IOException {
        JsSource js = JsSource.injectJs();
        String body = js.first("_v2sSlE164FromItemId").body;
        int shape = body.indexOf("^t\\.\\+\\d{7,15}$");
        int zero = body.indexOf("id.charAt(3) === '0'");
        assertTrue("keeps the 1:1 itemId shape", shape > 0);
        assertTrue("rejects a number after 't.+' starting with 0", zero > shape);
        assertTrue(body.substring(zero).startsWith("id.charAt(3) === '0') return null;"));
    }
}
