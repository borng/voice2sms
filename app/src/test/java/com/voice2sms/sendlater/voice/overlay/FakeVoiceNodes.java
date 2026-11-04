package com.voice2sms.sendlater.voice.overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Test double for the live Voice thread screen. Each read returns the current field value, as the
 * real accessor re-acquires the nodes on every call (R2). A null title or draft means the node is
 * missing. {@link #setDraft} records the call in the shared {@link #log} and, when it succeeds,
 * updates the field like ACTION_SET_TEXT would.
 */
final class FakeVoiceNodes implements VoiceNodeAccessor {

    String title;
    String draft;
    List<String> bubbles = new ArrayList<>();
    boolean setTextResult = true;
    final List<String> setDraftCalls = new ArrayList<>();
    final List<String> log;

    FakeVoiceNodes(List<String> log, String title, String draft) {
        this.log = log;
        this.title = title;
        this.draft = draft;
    }

    @Override public String readTitle() {
        log.add("readTitle");
        return title;
    }

    @Override public String readDraft() {
        log.add("readDraft");
        return draft;
    }

    @Override public List<String> readBubbleDescriptions() {
        log.add("readBubbles");
        return new ArrayList<>(bubbles);
    }

    @Override public boolean setDraft(String text) {
        log.add("setDraft");
        setDraftCalls.add(text);
        if (setTextResult) draft = text;
        return setTextResult;
    }
}
