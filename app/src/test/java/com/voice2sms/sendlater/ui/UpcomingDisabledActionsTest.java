package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.ui.UpcomingSections.RowAction;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.regex.Pattern;

/** OD24: while Send Later is off, Upcoming is read-only except Cancel. */
public class UpcomingDisabledActionsTest {

    @Test public void disabledShowsOnlyCancel() {
        for (Status s : new Status[] {Status.SCHEDULED, Status.HELD}) {
            assertEquals(s.name(), EnumSet.of(RowAction.CANCEL), UpcomingActivity.visibleActions(s, false));
            assertEquals(s.name(), EnumSet.allOf(RowAction.class), UpcomingActivity.visibleActions(s, true));
        }
        for (Status s : new Status[] {Status.IN_FLIGHT, Status.SENT_VERIFIED, Status.CANCELED,
                Status.EXPIRED, Status.SELF_PREEMPTED, Status.FAILED}) {
            assertTrue(s.name(), UpcomingActivity.visibleActions(s, false).isEmpty());
        }
    }

    @Test public void rowsUseTheGatedActions() throws Exception {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/ui/UpcomingActivity.java");
        if (!Files.exists(p)) p = Paths.get("app").resolve(p);
        String c = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        assertTrue("rows bind visibleActions(status, enabled)",
                Pattern.compile("visibleActions\\s*\\(\\s*m\\.getStatus\\(\\)\\s*,\\s*enabled\\s*\\)").matcher(c).find());
        assertEquals("actionsFor is only called inside visibleActions", 1,
                c.split("UpcomingSections\\.actionsFor\\(", -1).length - 1);
    }
}
