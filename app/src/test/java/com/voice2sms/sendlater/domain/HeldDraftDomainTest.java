package com.voice2sms.sendlater.domain;

import static com.voice2sms.sendlater.domain.Fixtures.HOUR;
import static com.voice2sms.sendlater.domain.Fixtures.T0;
import static com.voice2sms.sendlater.domain.Fixtures.message;
import static com.voice2sms.sendlater.domain.Fixtures.none;
import static org.junit.Assert.assertEquals;

import com.voice2sms.sendlater.store.ScheduledMessageEntity;
import com.voice2sms.sendlater.store.StoreMapping;
import com.voice2sms.sendlater.store.WatermarkEntity;

import org.junit.Test;

import java.util.TimeZone;

/**
 * D42: {@link HoldReason#DRAFT} is an ordinary hold: the ladder keeps it held (never SEND by
 * itself), it expires like other holds, the chip shows it neutrally, and the store keeps it.
 */
public class HeldDraftDomainTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private static final long DUE = T0 + 3 * HOUR;

    private static ScheduledMessage heldDraft() {
        return message(3 * HOUR).status(ScheduledMessage.Status.HELD).holdReason(HoldReason.DRAFT).build();
    }

    private static Decision eval(ScheduledMessage m, long now) {
        return LadderEvaluator.evaluate(m, Rung.R1, none(), now, SessionState.ready(), NetworkState.CONNECTED, true);
    }

    @Test
    public void ladder_heldDraftStaysHeldAndNeverSendsItself() {
        for (long now : new long[] {DUE, DUE + HOUR, DUE + 23 * HOUR}) {
            Decision d = eval(heldDraft(), now);
            assertEquals("at +" + (now - DUE), Decision.Kind.HOLD, d.getKind());
            assertEquals(HoldReason.DRAFT, d.getHoldReason());
        }
    }

    @Test
    public void ladder_heldDraftExpires24hPastItsTime() {
        assertEquals(Decision.Kind.EXPIRED, eval(heldDraft(), DUE + LadderEvaluator.EXPIRY_MS).getKind());
    }

    @Test
    public void chip_heldDraftIsNeutral() {
        assertEquals("Held · Test Contact",
                ChipLabel.format(1, 1, DUE, "Test Contact", null, HoldReason.DRAFT, ZONE));
    }

    @Test
    public void store_heldDraftRoundTripsByName() {
        ScheduledMessage m = heldDraft();
        ScheduledMessageEntity e = StoreMapping.toEntity(m, T0 + 5);
        assertEquals("DRAFT", e.holdReason);
        WatermarkEntity w = StoreMapping.toEntity(m.getId(), m.getWatermark());
        ScheduledMessage back = StoreMapping.toDomain(e, w);
        assertEquals(ScheduledMessage.Status.HELD, back.getStatus());
        assertEquals(HoldReason.DRAFT, back.getHoldReason());
    }
}
