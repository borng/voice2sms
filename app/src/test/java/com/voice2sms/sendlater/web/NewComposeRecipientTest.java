package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.Test;

/**
 * LIVE-F14 / R10a (D46, OD36, D38): the pairing rule behind the WebView edge tab on GV's
 * new-message page. voice2sms opened by an SMS intent composes there (recipient chip, no thread
 * URL), so the R10 thread gate never fires. The page reports the single chip's E.164; the live
 * activity confirms it against its own intent recipient, not page text alone.
 *
 * <p>Interface asserted (reflection, so this file compiles before it exists):
 * {@code public static String NewComposeRecipient.confirm(String mainFrameUrl,
 * String intentRecipient, int chipCount, String chipE164)} in {@code com.voice2sms.sendlater.web}:
 * the confirmed E.164, or null. Rule:
 * <ul>
 *   <li>the main frame is a trusted GV page ({@link OriginGuard#isTrustedGvUrl}) that is NOT a
 *       1:1 thread (a thread page is R10's: no second tab);</li>
 *   <li>exactly one recipient chip;</li>
 *   <li>the chip's number passes the one E.164 rule (D38: no "+0", 7-15 digits);</li>
 *   <li>with an intent recipient, the chip's E.164 equals that recipient normalized
 *       ({@code PhoneNumbers.toE164(raw, "US")}); with none (null or empty), the single chip's
 *       number is accepted.</li>
 * </ul>
 * Numbers are fictional (555-01xx, Ofcom drama range).
 */
public class NewComposeRecipientTest {

    private static final String NEW_MESSAGE = "https://voice.google.com/u/0/messages";
    private static final String CHIP = "+12025550123";
    private static final String OTHER = "+12025550124";
    private static final String UK = "+447700900123";

    private static Method confirm() {
        try {
            Class<?> c = Class.forName("com.voice2sms.sendlater.web.NewComposeRecipient");
            Method m = c.getDeclaredMethod("confirm", String.class, String.class, int.class, String.class);
            m.setAccessible(true);
            assertTrue("NewComposeRecipient.confirm must be static", Modifier.isStatic(m.getModifiers()));
            assertEquals(String.class, m.getReturnType());
            return m;
        } catch (ClassNotFoundException e) {
            fail("missing com.voice2sms.sendlater.web.NewComposeRecipient (R10a)");
        } catch (NoSuchMethodException e) {
            fail("missing NewComposeRecipient.confirm(String, String, int, String) (R10a)");
        }
        return null;
    }

    private static String confirm(String url, String intentRecipient, int chips, String chipE164) {
        try {
            return (String) confirm().invoke(null, url, intentRecipient, chips, chipE164);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("confirm must not throw", e.getCause());
        }
    }

    // ------------------------------------------------------------------ accepts

    @Test
    public void singleChipEqualToTheIntentRecipient_isConfirmed() {
        assertEquals(CHIP, confirm(NEW_MESSAGE, CHIP, 1, CHIP));
    }

    @Test
    public void intentRecipientIsNormalizedBeforeTheCompare() {
        // SmsHandlerActivity hands the raw sms: URI part on; GV shows the chip as E.164 digits.
        assertEquals(CHIP, confirm(NEW_MESSAGE, "(202) 555-0123", 1, CHIP));
        assertEquals(CHIP, confirm(NEW_MESSAGE, "202-555-0123", 1, CHIP));
        assertEquals(CHIP, confirm(NEW_MESSAGE, "+1 202 555 0123", 1, CHIP));
        assertEquals(UK, confirm(NEW_MESSAGE, UK, 1, UK));
    }

    @Test
    public void noIntentRecipient_acceptsTheSingleChipsNumber() {
        assertEquals(CHIP, confirm(NEW_MESSAGE, null, 1, CHIP));
        assertEquals(CHIP, confirm(NEW_MESSAGE, "", 1, CHIP));
    }

    // ------------------------------------------------------------------ rejects

    @Test
    public void chipThatIsNotTheIntentRecipient_isRejected() {
        assertNull(confirm(NEW_MESSAGE, OTHER, 1, CHIP));
        assertNull(confirm(NEW_MESSAGE, "(202) 555-0124", 1, CHIP));
    }

    @Test
    public void zeroOrSeveralChips_areRejected() {
        assertNull("two recipients: no tab (AC14)", confirm(NEW_MESSAGE, CHIP, 2, CHIP));
        assertNull(confirm(NEW_MESSAGE, null, 2, CHIP));
        assertNull(confirm(NEW_MESSAGE, CHIP, 0, CHIP));
        assertNull(confirm(NEW_MESSAGE, CHIP, -1, CHIP));
    }

    @Test
    public void invalidOrPlusZeroChipNumbers_areRejected() {
        // D38: the one E.164 rule; "+0" is never a country code.
        assertNull(confirm(NEW_MESSAGE, null, 1, "+02025550123"));
        assertNull(confirm(NEW_MESSAGE, "+02025550123", 1, "+02025550123"));
        assertNull(confirm(NEW_MESSAGE, null, 1, "+1202"));
        assertNull(confirm(NEW_MESSAGE, null, 1, "+1202555012345678"));
        assertNull(confirm(NEW_MESSAGE, null, 1, "not a number"));
        assertNull(confirm(NEW_MESSAGE, null, 1, ""));
        assertNull(confirm(NEW_MESSAGE, null, 1, null));
        assertNull("a chip with separators is not a page-derived E.164",
                confirm(NEW_MESSAGE, null, 1, "+1 202 555 0123"));
    }

    @Test
    public void untrustedOrMissingMainFrame_isRejected() {
        assertNull(confirm("https://evil.example/u/0/messages", CHIP, 1, CHIP));
        assertNull(confirm("http://voice.google.com/u/0/messages", CHIP, 1, CHIP));
        assertNull(confirm(null, CHIP, 1, CHIP));
    }

    // ------------------------------------------------------------------ R10 unchanged

    @Test
    public void aOneToOneThreadPage_isLeftToR10_noSecondTab() {
        String sameThread = ThreadUrl.threadUrlOf(CHIP);
        String otherThread = ThreadUrl.threadUrlOf(OTHER);
        assertNull("after GV turns the page into a thread, R10 applies",
                confirm(sameThread, CHIP, 1, CHIP));
        assertNull(confirm(otherThread, CHIP, 1, CHIP));
        assertNull(confirm(otherThread, null, 1, CHIP));
    }

    @Test
    public void threadUrlRuleItselfIsUnchanged() {
        // R10: the thread gate still needs itemId=t.+E164; the new-message page is not a thread.
        assertNull(ThreadUrl.oneToOneE164(NEW_MESSAGE));
        assertTrue(ThreadUrl.isThreadOf(ThreadUrl.threadUrlOf(CHIP), CHIP));
        assertNull(ThreadUrl.oneToOneE164("https://voice.google.com/u/0/messages?itemId=t.%2B02025550123"));
        // The rule class must exist for this node (keeps this suite red until it lands).
        confirm();
    }
}
