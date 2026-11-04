package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.engine.RemindCause;

/**
 * The detail string the send path hands {@code SendLaterEngine.onSendOutcome} (engine-followups-3
 * contract, OD23(3)). Pure JVM.
 *
 * <p>With CANNOT_SHOW the engine words the reminder by {@link RemindCause#fromDetail}, which knows
 * exactly {@code no_session}, {@code daily_cap} and {@code user_busy} (anything else, null
 * included, is the LOCKED default). The send path's own pre-typing causes map to the closest one:
 * <ul>
 *   <li>{@code no_session}, {@code auth}: {@code no_session} (no web session, or a sign-in
 *       page; the reminder offers Sign in);</li>
 *   <li>{@code daily_cap}, {@code user_busy}: unchanged;</li>
 *   <li>everything else ({@code compose_timeout}: GV was only slow to open the thread, so the
 *       generic wording, not Sign in (OD27); {@code timeout}, {@code inject_failed}, {@code paused},
 *       {@code launch_timeout}, {@code cannot_show}, {@code activity_gone},
 *       {@code other_intent}, {@code no_recipient} (a corrupt row, OD28), ...): null, the
 *       LOCKED default.</li>
 * </ul>
 * The other outcomes keep the cause: a verified send passes
 * {@link ScheduledSendController#DETAIL_LATE_REPLY} or null; SENT_UNVERIFIED carries its cause
 * ({@code no_match}, {@code timeout}, ...), which the engine ignores. The send path never reports
 * FAILED (OD28).
 */
public final class SendDetails {

    private SendDetails() {}

    /** The detail for {@code outcome} given the send path's internal {@code cause}. */
    public static String forEngine(SendOutcome outcome, String cause) {
        if (outcome == SendOutcome.CANNOT_SHOW) return cannotShowDetail(cause);
        return cause;
    }

    /** CANNOT_SHOW cause -> one of the engine's three detail strings, or null. */
    static String cannotShowDetail(String cause) {
        if (cause == null) return null;
        switch (cause) {
            case RemindCause.DETAIL_NO_SESSION:
            case "auth":
                return RemindCause.DETAIL_NO_SESSION;
            case RemindCause.DETAIL_DAILY_CAP:
                return RemindCause.DETAIL_DAILY_CAP;
            case RemindCause.DETAIL_USER_BUSY:
                return RemindCause.DETAIL_USER_BUSY;
            default:
                return null;
        }
    }
}
