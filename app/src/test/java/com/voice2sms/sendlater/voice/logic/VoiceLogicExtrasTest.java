package com.voice2sms.sendlater.voice.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ThreadEvent;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import org.junit.Test;

/** Implementer's edge cases beyond the acceptance suite. Fictional 555-01xx numbers only. */
public class VoiceLogicExtrasTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private static final List<String> NONE = Collections.<String>emptyList();

    private static long at(int y, int month, int d, int h, int m) {
        Calendar c = Calendar.getInstance(ZONE);
        c.clear();
        c.set(y, month, d, h, m, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void conversationChangedDetectsTitleAndDraft() {
        assertEquals(ConversationChanged.Result.UNCHANGED, ConversationChanged.check("A", "hi", "A", "hi"));
        assertEquals(ConversationChanged.Result.TITLE_CHANGED, ConversationChanged.check("A", "hi", "B", "hi"));
        assertEquals(ConversationChanged.Result.DRAFT_CHANGED, ConversationChanged.check("A", "hi", "A", "hi!"));
        assertEquals(ConversationChanged.Result.BOTH_CHANGED, ConversationChanged.check("A", "hi", "B", ""));
        assertTrue(ConversationChanged.check("A", "hi", null, "hi").isChanged());
        assertTrue(ConversationChanged.check(null, null).isChanged());
        assertFalse(ConversationChanged.check("A", null, "A", "").isChanged());
        // exact Unicode: a trailing space or a different emoji is a change
        assertTrue(ConversationChanged.check("A", "ok", "A", "ok ").isChanged());
    }

    @Test
    public void narrowNoBreakSpaceBeforeMeridiemParses() {
        long now = at(2026, Calendar.NOVEMBER, 4, 20, 0);
        ThreadEvent e = VoiceBubbleParser.parse("Hi from me, 6:46 PM", now, ZONE);
        assertNotNull(e);
        assertEquals(at(2026, Calendar.NOVEMBER, 4, 18, 46), e.getTimeMs());
    }

    @Test
    public void fromInsideTheTextKeepsTheText() {
        long now = at(2026, Calendar.NOVEMBER, 4, 20, 0);
        ThreadEvent e = VoiceBubbleParser.parse("A gift from grandma from me, 6:46 PM", now, ZONE);
        assertNotNull(e);
        assertEquals(ThreadEvent.Direction.OUT, e.getDirection());
        assertEquals(ThreadEvent.hashOf("A gift from grandma"), e.getTextHash());
    }

    @Test
    public void noonAndMidnightAreCorrect() {
        long now = at(2026, Calendar.NOVEMBER, 4, 20, 0);
        assertEquals(at(2026, Calendar.NOVEMBER, 4, 12, 5),
                VoiceBubbleParser.parse("x from me, 12:05 PM", now, ZONE).getTimeMs());
        assertEquals(at(2026, Calendar.NOVEMBER, 4, 0, 5),
                VoiceBubbleParser.parse("x from me, 12:05 AM", now, ZONE).getTimeMs());
    }

    @Test
    public void datedFormInTheFutureMeansLastYear() {
        long now = at(2026, Calendar.JANUARY, 2, 9, 0);
        assertEquals(at(2025, Calendar.DECEMBER, 30, 14, 43),
                VoiceBubbleParser.parse("x from me, Dec 30, 2:43 PM", now, ZONE).getTimeMs());
    }

    @Test
    public void explicitYearAndTwentyFourHourFormsParse() {
        long now = at(2026, Calendar.NOVEMBER, 4, 20, 0);
        assertEquals(at(2025, Calendar.OCTOBER, 28, 14, 43),
                VoiceBubbleParser.parse("x from me, Oct 28, 2025, 2:43 PM", now, ZONE).getTimeMs());
        assertEquals(at(2026, Calendar.NOVEMBER, 4, 18, 46),
                VoiceBubbleParser.parse("x from me, 18:46", now, ZONE).getTimeMs());
    }

    @Test
    public void junkIsNotABubble() {
        long now = at(2026, Calendar.NOVEMBER, 4, 20, 0);
        assertNull(VoiceBubbleParser.parse(null, now, ZONE));
        assertNull(VoiceBubbleParser.parse("", now, ZONE));
        assertNull(VoiceBubbleParser.parse("Send message", now, ZONE));
        assertNull(VoiceBubbleParser.parse("x from me, 13:05 PM", now, ZONE));
        assertNull(VoiceBubbleParser.parse("x from me, Feb 30, 2:43 PM", now, ZONE));
        assertNull(VoiceBubbleParser.parse("x from me, Foo 3, 2:43 PM", now, ZONE));
    }

    @Test
    public void watermarkSkipsUnparsableDescriptionsAndUsesNewestTime() {
        long now = at(2026, Calendar.NOVEMBER, 4, 20, 0);
        List<String> descs = Arrays.asList("x from me, 6:46 PM", "Send message", "y from Test Contact, 9:00 AM");
        assertEquals(Long.valueOf(at(2026, Calendar.NOVEMBER, 4, 18, 46)),
                VoiceBubbleParser.bubbleWatermark(descs, now, ZONE).getNewestTs());
        assertEquals(ThreadEvent.hashOf("x"), VoiceBubbleParser.bubbleWatermark(descs, now, ZONE).getBubbleHash());
        assertEquals(now, VoiceBubbleParser.bubbleWatermark(descs, now, ZONE).getCapturedAtDevice());
        assertNull(VoiceBubbleParser.bubbleWatermark(Arrays.asList("Send message"), now, ZONE).getNewestTs());
        assertNull(VoiceBubbleParser.bubbleWatermark(null, now, ZONE).getNewestTs());
    }

    @Test
    public void phoneNumbersHandleBidiMarksAndRejectBadNanp() {
        assertEquals("+14355550142", PhoneNumbers.toE164("‪(435) 555-0142‬", "US"));
        assertEquals("+14355550142", PhoneNumbers.toE164("435.555.0142", "US"));
        assertNull(PhoneNumbers.toE164("(135) 555-0142", "US"));
        assertNull(PhoneNumbers.toE164("(435) 155-0142", "US"));
        assertNull(PhoneNumbers.toE164("435-555-0142 x12", "US"));
        assertNull(PhoneNumbers.toE164("4+355550142", "US"));
        assertNull(PhoneNumbers.toE164("4355550142", "GB"));
        assertEquals("+442079460123", PhoneNumbers.toE164("+44 20 7946 0123", "US"));
    }

    @Test
    public void resolverEdgeCases() {
        List<String> two = Arrays.asList("+14355550101", "+14355550102");
        // contacts not granted: directory matches alone are the candidates, confirmed by the user
        // (deliver sec-M1: the directory alone never resolves silently)
        assertEquals(RecipientResolver.Kind.NEEDS_CHOICE,
                RecipientResolver.resolve("Test Contact", NONE, Arrays.asList("(435) 555-0101")).getKind());
        // disjoint contacts and directory: never auto-pick, offer all
        RecipientResolver.Resolution r = RecipientResolver.resolve("Test Contact", two, Arrays.asList("+14355550109"));
        assertEquals(RecipientResolver.Kind.NEEDS_CHOICE, r.getKind());
        assertEquals(3, r.getCandidates().size());
        // differently formatted duplicates collapse to one
        assertEquals(RecipientResolver.Kind.RESOLVED, RecipientResolver.resolve("Test Contact",
                Arrays.asList("(435) 555-0101", "+14355550101"), NONE).getKind());
        // non-number contact entries are dropped
        assertEquals(RecipientResolver.Kind.NEEDS_ENTRY,
                RecipientResolver.resolve("Test Contact", Arrays.asList("Test Contact", ""), null).getKind());
        assertNull(RecipientResolver.manual("Test Contact"));
        assertEquals("+14355550142", RecipientResolver.manual("435 555 0142").getE164());
    }
}
