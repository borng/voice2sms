package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** simplify-send (5): inject.js is read from the APK once per process, and read as before. */
public class AssetScriptsTest {

    private static final class CountingOpener implements AssetScripts.Opener {
        int opens;
        String text = "a();\r\nb();";
        boolean fail;

        @Override
        public InputStream open(String name) throws IOException {
            opens++;
            if (fail) throw new IOException("missing");
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Before @After public void reset() {
        AssetScripts.clear();
    }

    @Test public void readsEachLineFollowedByANewlineAsTheActivityDid() {
        CountingOpener o = new CountingOpener();
        assertEquals("a();\nb();\n", AssetScripts.read(o, "inject.js"));
    }

    @Test public void readsTheAssetOncePerProcess() {
        CountingOpener o = new CountingOpener();
        String first = AssetScripts.read(o, "inject.js");
        String second = AssetScripts.read(o, "inject.js");
        assertSame(first, second);
        assertEquals(1, o.opens);
        assertSame("another caller (the Send Later module) shares the copy", first,
                AssetScripts.read(new CountingOpener(), "inject.js"));
    }

    @Test public void aFailedReadIsNullAndNotCached() {
        CountingOpener o = new CountingOpener();
        o.fail = true;
        assertNull(AssetScripts.read(o, "inject.js"));
        o.fail = false;
        assertEquals("a();\nb();\n", AssetScripts.read(o, "inject.js"));
        assertEquals(2, o.opens);
    }

    @Test public void namesAreCachedSeparatelyAndNullsAreSafe() {
        CountingOpener o = new CountingOpener();
        AssetScripts.read(o, "inject.js");
        o.text = "x();";
        assertEquals("x();\n", AssetScripts.read(o, "other.js"));
        assertNull(AssetScripts.read(o, null));
        assertNull("nothing cached and no opener", AssetScripts.read(null, "third.js"));
    }
}
