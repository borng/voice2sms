package com.voice2sms.sendlater.voice.overlay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Test double for the repository checks the flow needs: "is this record persisted?" and cancel. */
final class FakeRecords implements VoiceDraftFlow.Records {

    final Set<Long> persisted = new HashSet<>();
    final List<Long> canceled = new ArrayList<>();
    boolean cancelResult = true;
    final List<String> log;

    FakeRecords(List<String> log) {
        this.log = log;
    }

    @Override public boolean isPersisted(long messageId) {
        log.add("isPersisted");
        return persisted.contains(messageId);
    }

    @Override public boolean cancel(long messageId) {
        log.add("cancel");
        canceled.add(messageId);
        if (cancelResult) persisted.remove(messageId);
        return cancelResult;
    }
}
