package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.listener.ContactDisplayName;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** D33: a record saved without a display name gets the first contact name for its E.164. */
public class RecipientNamesFillTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String E164 = "+15555550123";
    private static final String CONTACT = "Test Contact";
    private static final String SUPPLIED = "Typed Name";

    private static ScheduledMessage message(String e164, String name) {
        return new ScheduledMessage.Builder()
                .createdAt(NOW).scheduledFor(NOW + 3_600_000L).text("see you soon").recipientE164(e164)
                .recipientDisplayName(name).source(ScheduledMessage.Source.NATIVE_GV).rung(Rung.R1)
                .holdIfReplied(true).latenessPolicy(LatenessPolicy.DEFAULT)
                .status(ScheduledMessage.Status.SCHEDULED).attemptCount(0).build();
    }

    /** A fake contacts provider behind the real lookup rules. */
    private static final class FakeResolver implements ContactDisplayName.Resolver {
        boolean granted = true;
        List<String> rows = new ArrayList<>(Arrays.asList(CONTACT, "Other Contact"));
        boolean fail;
        int queries;

        @Override public boolean readContactsGranted() { return granted; }

        @Override public List<String> displayNames(String e164) {
            queries++;
            if (fail) throw new IllegalStateException("provider down");
            return rows;
        }
    }

    private static RecipientNames over(FakeResolver r) {
        return e164 -> ContactDisplayName.lookup(r, e164);
    }

    @Test public void nameFilledWhenMissing() {
        FakeResolver r = new FakeResolver();
        ScheduledMessage out = RecipientNames.fill(message(E164, null), over(r));
        assertEquals(CONTACT, out.getRecipientDisplayName());
        assertEquals(E164, out.getRecipientE164());
        assertEquals("see you soon", out.getText());
        assertEquals(NOW, out.getCreatedAt());
    }

    @Test public void blankNameCountsAsMissing() {
        ScheduledMessage out = RecipientNames.fill(message(E164, "  "), over(new FakeResolver()));
        assertEquals(CONTACT, out.getRecipientDisplayName());
    }

    @Test public void suppliedNameKeptAndNoLookup() {
        FakeResolver r = new FakeResolver();
        ScheduledMessage in = message(E164, SUPPLIED);
        assertSame(in, RecipientNames.fill(in, over(r)));
        assertEquals(0, r.queries);
    }

    @Test public void blankWhenDenied() {
        FakeResolver r = new FakeResolver();
        r.granted = false;
        ScheduledMessage in = message(E164, null);
        ScheduledMessage out = RecipientNames.fill(in, over(r));
        assertSame(in, out);
        assertNull(out.getRecipientDisplayName());
        assertEquals("no provider query without READ_CONTACTS", 0, r.queries);
    }

    @Test public void blankWhenLookupFailsOrFindsNothing() {
        FakeResolver r = new FakeResolver();
        r.fail = true;
        assertNull(RecipientNames.fill(message(E164, null), over(r)).getRecipientDisplayName());
        r.fail = false;
        r.rows = new ArrayList<>();
        assertNull(RecipientNames.fill(message(E164, null), over(r)).getRecipientDisplayName());
        r.rows = null;
        assertNull(RecipientNames.fill(message(E164, null), over(r)).getRecipientDisplayName());
        RecipientNames throwing = e164 -> { throw new RuntimeException("boom"); };
        assertNull(RecipientNames.fill(message(E164, null), throwing).getRecipientDisplayName());
    }

    @Test public void noNumberOrNoSourceLeavesMessageAlone() {
        FakeResolver r = new FakeResolver();
        ScheduledMessage noNumber = message(null, null);
        assertSame(noNumber, RecipientNames.fill(noNumber, over(r)));
        assertEquals(0, r.queries);
        ScheduledMessage in = message(E164, null);
        assertSame(in, RecipientNames.fill(in, null));
        assertNull(RecipientNames.fill(null, over(r)));
    }

    @Test public void firstNonBlankDisplayNameWins() {
        FakeResolver r = new FakeResolver();
        r.rows = Arrays.asList(null, " ", CONTACT, "Other Contact");
        assertEquals(CONTACT, ContactDisplayName.lookup(r, E164));
        assertNull(ContactDisplayName.lookup(r, null));
        assertNull(ContactDisplayName.lookup(r, " "));
        assertNull(ContactDisplayName.lookup(null, E164));
    }

    @Test public void scheduleFillsTheNameBeforeTheTransactionAndGetWiresContacts() throws IOException {
        String repo = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/store/SendLaterRepository.java")), StandardCharsets.UTF_8);
        int sched = repo.indexOf("public long schedule(");
        int fill = repo.indexOf("RecipientNames.fill(message, names)", sched);
        int toInsert = repo.indexOf("final ScheduledMessage toInsert = named.", sched);
        int tx = repo.indexOf("db.runInTransaction(", sched);
        assertTrue(sched > 0 && fill > sched && toInsert > fill && tx > toInsert);
        int get = repo.indexOf("public static SendLaterRepository get(");
        assertTrue(repo.substring(get, repo.indexOf("return r;", get)).contains("new AndroidContactNames(context)"));
        String contacts = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/listener/AndroidContactNames.java")), StandardCharsets.UTF_8);
        assertTrue(contacts.contains("implements ContactNames, RecipientNames"));
        assertTrue(contacts.contains("ContactDisplayName.lookup("));
        assertTrue(contacts.contains("new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}"));
    }
}
