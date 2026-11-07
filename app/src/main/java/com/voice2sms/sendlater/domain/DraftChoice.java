package com.voice2sms.sendlater.domain;

import java.text.Normalizer;

/**
 * D32 (amends OD34): what a scheduled compose does when the conversation's message box already
 * holds text that is not exactly our scheduled body. Pure JVM.
 *
 * <p>A job runs in one {@link Mode}: {@link Mode#ASK} for every scheduled send and for Send now /
 * Send anyway; {@link Mode#REPLACE} or {@link Mode#BOTH} only for the one job started by the
 * user's tap on "Replace and send" / "Send both" in the draft notification. {@link #onBox} turns
 * the mode and the box's text into a {@link Step}:
 * <ul>
 *   <li>an empty (blank) box: {@link Step#TYPE};</li>
 *   <li>the box holds our text, normalized ({@link #sameText}): {@link Step#REPLACE}, it is ours
 *       (no question asked);</li>
 *   <li>otherwise per mode: ASK -> {@link Step#STOP} (the job ends unsent and the user gets the
 *       choice notification), REPLACE -> {@link Step#REPLACE}, BOTH -> {@link Step#BOTH}.</li>
 * </ul>
 * Never logs or stores the text it is given.
 */
public final class DraftChoice {

    private DraftChoice() {}

    /** How the job treats a draft it finds in the box. */
    public enum Mode {
        /** Never touch a draft: stop unsent and ask the user (default for every job). */
        ASK,
        /** The user chose "Replace and send": clear the draft, type and send ours. */
        REPLACE,
        /** The user chose "Send both": keep the draft in memory, send ours, put the draft back. */
        BOTH
    }

    /** What the compose script does with the box. */
    public enum Step {
        /** The box is empty: type ours. */
        TYPE("type"),
        /** Clear the box and type ours (the draft is dropped). */
        REPLACE("replace"),
        /** Clear the box and type ours; the draft is put back after the send. */
        BOTH("both"),
        /** Leave the box alone and end the job unsent (the choice notification follows). */
        STOP("stop");

        private final String wire;

        Step(String wire) {
            this.wire = wire;
        }

        /** The string inject.js receives. */
        public String wire() {
            return wire;
        }
    }

    /** The step for a box holding {@code boxText} while the job sends {@code body} in {@code mode}. */
    public static Step onBox(Mode mode, String boxText, String body) {
        if (boxText == null || boxText.trim().isEmpty()) return Step.TYPE;
        if (sameText(boxText, body)) return Step.REPLACE;
        if (mode == Mode.REPLACE) return Step.REPLACE;
        if (mode == Mode.BOTH) return Step.BOTH;
        return Step.STOP;
    }

    /**
     * The draft is our scheduled text: equal after Unicode NFC, collapsing whitespace runs to one
     * space and trimming. Case and punctuation count (a draft that differs in more than spacing
     * is the user's, and is never dropped without asking).
     */
    public static boolean sameText(String draft, String body) {
        if (draft == null || body == null) return false;
        String a = normalize(draft);
        return !a.isEmpty() && a.equals(normalize(body));
    }

    static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).replaceAll("\\s+", " ").trim();
    }
}
