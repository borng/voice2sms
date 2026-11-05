package com.voice2sms.sendlater.a11y;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** LIVE-F5: reading enabled_accessibility_services and the warn decision. */
public class A11yHealthTest {

    private static final String PKG = "com.voice2sms";
    private static final String CLS = "com.voice2sms.GeminiSmsInterceptService";

    private static boolean listed(String v) {
        return A11yHealth.isServiceListed(v, PKG, CLS);
    }

    @Test public void fullAndShortFormsAreListed() {
        assertTrue(listed("com.voice2sms/com.voice2sms.GeminiSmsInterceptService"));
        assertTrue(listed("com.voice2sms/.GeminiSmsInterceptService"));
    }

    @Test public void listedAmongOtherServices() {
        assertTrue(listed("com.other.app/.Reader:com.voice2sms/.GeminiSmsInterceptService:x.y/x.y.Z"));
        assertTrue(listed(" com.voice2sms/.GeminiSmsInterceptService "));
    }

    @Test public void matchingIgnoresCase() {
        assertTrue(listed("COM.VOICE2SMS/.geminismsinterceptservice"));
    }

    @Test public void absentWhenNotListed() {
        assertFalse(listed(null));
        assertFalse(listed(""));
        assertFalse(listed("com.other.app/.Reader"));
        assertFalse(listed(":::"));
    }

    @Test public void otherPackageOrOtherClassIsNotUs() {
        assertFalse("same class name in another package",
                listed("com.voice2sms.debug/com.voice2sms.GeminiSmsInterceptService"));
        assertFalse("prefix of our package", listed("com.voice2/.GeminiSmsInterceptService"));
        assertFalse("another class of ours", listed("com.voice2sms/.OtherService"));
        assertFalse("class name prefix", listed("com.voice2sms/.GeminiSmsIntercept"));
        assertFalse("malformed entries", listed("com.voice2sms:/.GeminiSmsInterceptService:com.voice2sms/"));
    }

    @Test public void nullArgumentsAreNotListed() {
        assertFalse(A11yHealth.isServiceListed("com.voice2sms/.GeminiSmsInterceptService", null, CLS));
        assertFalse(A11yHealth.isServiceListed("com.voice2sms/.GeminiSmsInterceptService", PKG, null));
    }

    @Test public void decideWarnsOnlyWhenOnPendingAndOff() {
        assertEquals(A11yHealth.State.WARN, A11yHealth.decide(true, 1, false));
        assertEquals(A11yHealth.State.WARN, A11yHealth.decide(true, 5, false));
    }

    @Test public void decideIsOkWheneverTheServiceIsOn() {
        assertEquals(A11yHealth.State.OK, A11yHealth.decide(true, 3, true));
        assertEquals(A11yHealth.State.OK, A11yHealth.decide(false, 0, true));
    }

    @Test public void decideStaysQuietWhenOffOrNothingPending() {
        assertEquals(A11yHealth.State.IDLE, A11yHealth.decide(false, 3, false));
        assertEquals(A11yHealth.State.IDLE, A11yHealth.decide(true, 0, false));
        assertEquals(A11yHealth.State.IDLE, A11yHealth.decide(true, -1, false));
    }
}
