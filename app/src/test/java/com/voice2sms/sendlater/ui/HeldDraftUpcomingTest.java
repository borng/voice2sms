package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.R;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D42 / R35: a message held on the user's draft shows in Upcoming under Held, labelled
 * "Held: draft in the conversation", and its row offers Send now (the D32 draft check) like any
 * other held row. Fictional data only.
 */
public class HeldDraftUpcomingTest {

    private static final String LABEL = "Held: draft in the conversation";
    private static final long NOW = 1_793_800_000_000L;

    private static ScheduledMessage heldDraft(long id) {
        return new ScheduledMessage.Builder().id(id).createdAt(NOW - 3_600_000L).scheduledFor(NOW - 60_000L)
                .text("t").recipientE164("+15555550100").recipientDisplayName("Test Contact")
                .status(ScheduledMessage.Status.HELD).holdReason(HoldReason.DRAFT).build();
    }

    /** The name of the sendlater_*.xml string whose value is exactly {@code value}, or null. */
    private static String stringNamed(String value) throws IOException {
        File dir = new File("src/main/res/values");
        File[] files = dir.listFiles((d, n) -> n.startsWith("sendlater_") && n.endsWith(".xml"));
        assertNotNull("res/values readable from the module dir", files);
        Pattern p = Pattern.compile("<string\\s+name=\"([^\"]+)\"[^>]*>" + Pattern.quote(value) + "</string>");
        for (File f : files) {
            String xml = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = p.matcher(xml);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    @Test
    public void heldDraftRowIsLabelledDraftInTheConversation() throws Exception {
        String name = stringNamed(LABEL);
        assertNotNull("a sendlater_*.xml string reads '" + LABEL + "'", name);
        int expected = R.string.class.getField(name).getInt(null);
        assertEquals(expected, UpcomingActivity.reasonRes(HoldReason.DRAFT));
        assertNotEquals(R.string.sendlater_upcoming_reason_unknown, UpcomingActivity.reasonRes(HoldReason.DRAFT));
    }

    @Test
    public void otherHoldReasonsKeepTheirLabels() {
        assertEquals(R.string.sendlater_upcoming_reason_replied, UpcomingActivity.reasonRes(HoldReason.REPLIED));
        assertEquals(R.string.sendlater_upcoming_reason_unverified, UpcomingActivity.reasonRes(HoldReason.UNVERIFIED));
        assertNotEquals(UpcomingActivity.reasonRes(HoldReason.UNVERIFIED), UpcomingActivity.reasonRes(HoldReason.DRAFT));
    }

    @Test
    public void heldDraftAppearsUnderHeldNotPending() {
        ScheduledMessage h = heldDraft(5L);
        UpcomingSections s = UpcomingSections.build(Collections.singletonList(h), null, NOW);
        assertEquals(1, s.getHeld().size());
        assertEquals(HoldReason.DRAFT, s.getHeld().get(0).getHoldReason());
        assertTrue(s.getPending().isEmpty());
        assertFalse("a held row shows its reason, not the reminder line",
                UpcomingSections.isWaitingForUser(h, new java.util.HashSet<>(Arrays.asList(5L))));
    }
}
