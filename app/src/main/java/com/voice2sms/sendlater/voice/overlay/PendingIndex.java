package com.voice2sms.sendlater.voice.overlay;

import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.voice.logic.PhoneNumbers;
import com.voice2sms.sendlater.voice.logic.RecipientResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * In-memory view of pending and held messages by conversation, rebuilt off the main thread on
 * ACTION_STATE_CHANGED / ACTION_SCHEDULED so the event path never queries the database (R40).
 *
 * <p>Each message is indexed under its E.164 and under the name key of its captured display name.
 * A Voice title that parses as a number looks up the E.164 entry; any other title looks up the
 * name entry. Pure JVM; immutable.
 */
public final class PendingIndex {

    /** A holdIfReplied SCHEDULED message the open thread is watched for (R33). */
    public static final class HoldWatch {
        public final long id;
        public final String e164;
        public final Watermark watermark;
        public final long createdAt;

        HoldWatch(long id, String e164, Watermark watermark, long createdAt) {
            this.id = id;
            this.e164 = e164;
            this.watermark = watermark;
            this.createdAt = createdAt;
        }
    }

    /** Pending and held messages of one conversation. */
    public static final class Entry {
        private int pending;
        private int held;
        private long nextTimeMs = Long.MAX_VALUE;
        private long onlyId = -1;
        private int reminded;
        private String e164;
        private String heldName;
        private String heldE164;
        private HoldReason heldReason;
        private final List<HoldWatch> watches = new ArrayList<>();

        public int total() { return pending + held; }
        public int pendingCount() { return pending; }
        public int heldCount() { return held; }

        /** The message id when the conversation has exactly one message, else -1 (R18). */
        public long singleMessageId() { return total() == 1 ? onlyId : -1; }

        /** E.164 of the conversation (first seen), for the filtered Upcoming list; may be null. */
        public String e164() { return e164; }

        public List<HoldWatch> holdWatches() { return Collections.unmodifiableList(watches); }

        public boolean hasHoldWatch() { return !watches.isEmpty(); }

        /**
         * The message the chip opens in edit mode (R18), else -1. OD31 PQ4: a single HELD message
         * opens Upcoming filtered to the thread instead, which shows the hold reason and Send now.
         */
        public long editMessageId() { return held == 0 ? singleMessageId() : -1; }

        /** Pending messages the engine already reminded about (they will not send themselves). */
        public int remindedCount() { return reminded; }

        /** R17 chip text without the date rule, or null when there is nothing to show. */
        public String chipLabel(TimeZone zone) {
            long next = nextTimeMs == Long.MAX_VALUE ? 0 : nextTimeMs;
            return chipLabel(zone, next);
        }

        /** R17 chip text at {@code nowMs} (date beyond the coming week, UI M5/M6), or null. */
        public String chipLabel(TimeZone zone, long nowMs) {
            return ChipLabel.format(pending, held, nextTimeMs == Long.MAX_VALUE ? 0 : nextTimeMs,
                    heldName, heldE164, heldReason, reminded, nowMs, zone);
        }

        void add(ScheduledMessage m, boolean isHeld, boolean isReminded) {
            if (isHeld) {
                held++;
                if (heldReason == null) {
                    heldName = m.getRecipientDisplayName();
                    heldE164 = m.getRecipientE164();
                    heldReason = m.getHoldReason();
                }
            } else {
                pending++;
                if (isReminded) reminded++;
                nextTimeMs = Math.min(nextTimeMs, m.getScheduledFor());
                if (m.isHoldIfReplied() && m.getRecipientE164() != null) {
                    watches.add(new HoldWatch(m.getId(), m.getRecipientE164(), m.getWatermark(), m.getCreatedAt()));
                }
            }
            onlyId = m.getId();
            if (e164 == null) e164 = m.getRecipientE164();
        }
    }

    private static final PendingIndex EMPTY = new PendingIndex(new HashMap<String, Entry>());

    private final Map<String, Entry> byKey;

    private PendingIndex(Map<String, Entry> byKey) {
        this.byKey = byKey;
    }

    public static PendingIndex empty() { return EMPTY; }

    /** {@code pending} = SCHEDULED rows, {@code held} = HELD rows; either may be null. */
    public static PendingIndex build(List<ScheduledMessage> pending, List<ScheduledMessage> held) {
        return build(pending, held, null);
    }

    /**
     * As {@link #build(List, List)}; {@code remindedIds} are the SCHEDULED ids carrying the
     * engine's "reminded" mark (null = none), shown as "Reminder sent" on the chip (UI M5).
     */
    public static PendingIndex build(List<ScheduledMessage> pending, List<ScheduledMessage> held,
                                     Set<Long> remindedIds) {
        Map<String, Entry> map = new HashMap<>();
        if (pending != null) {
            for (ScheduledMessage m : pending) {
                boolean r = m != null && ChipLabel.isWaitingForUser(m,
                        remindedIds != null && remindedIds.contains(m.getId()));
                put(map, m, false, r);
            }
        }
        if (held != null) for (ScheduledMessage m : held) put(map, m, true, false);
        return map.isEmpty() ? EMPTY : new PendingIndex(map);
    }

    private static void put(Map<String, Entry> map, ScheduledMessage m, boolean isHeld, boolean isReminded) {
        if (m == null) return;
        String e164 = m.getRecipientE164();
        if (e164 != null) entry(map, e164).add(m, isHeld, isReminded);
        String nameKey = ConversationRef.nameKey(m.getRecipientDisplayName());
        if (nameKey != null) entry(map, nameKey).add(m, isHeld, isReminded);
    }

    private static Entry entry(Map<String, Entry> map, String key) {
        Entry e = map.get(key);
        if (e == null) {
            e = new Entry();
            map.put(key, e);
        }
        return e;
    }

    /** Conversation key the Voice title stands for: its E.164 when it is a number, else the name key. */
    public static String keyForTitle(String title) {
        if (title == null || title.trim().isEmpty()) return null;
        String e164 = PhoneNumbers.toE164(title, RecipientResolver.DEFAULT_REGION);
        return e164 != null ? e164 : ConversationRef.nameKey(title);
    }

    /** Entry for the open thread's title, or null. */
    public Entry lookup(String title) {
        String key = keyForTitle(title);
        return key == null ? null : byKey.get(key);
    }

    public boolean isEmpty() { return byKey.isEmpty(); }

    @Override public String toString() { return "PendingIndex{keys=" + byKey.size() + "}"; }
}
