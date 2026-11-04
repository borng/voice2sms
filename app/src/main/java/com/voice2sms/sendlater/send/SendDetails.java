package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.engine.RemindCause;

/**
 * The detail string the send path hands {@code SendLaterEngine.onSendOutcome} (engine-followups-3
 * contract, OD23(3)). Pure JVM.
 *
 * <p>With CANNOT_SHOW the engine words the reminder by {@link RemindCause#fromDetail}. The send
 * path's own pre-typing causes map to:
 * <ul>
 *   <li>{@code no_session}, {@code auth}: {@code no_session} (no web session, or a sign-in
 *       page; the reminder offers Sign in);</li>
 *   <li>{@code daily_cap}, {@code user_busy}: unchanged;</li>
 *   <li>{@code locked} (the planner's CANNOT_SHOW while the keyguard is locked): {@code locked},
 *       "Unlock to send";</li>
 *   <li>{@code late} (the launch came after the lateness deadline, F4/I2): {@code late}, the
 *       LATE wording;</li>
 *   <li>{@code stopped} (the user tapped Stop on the cover before typing, OD31 PQ3):
 *       {@code stopped}, "You stopped the send";</li>
 *   <li>null: null, the LOCKED default;</li>
 *   <li>everything else ({@code compose_timeout}: GV was only slow to open the thread, not a
 *       sign-in problem (OD27); {@code timeout}, {@code inject_failed}, {@code paused},
 *       {@code launch_timeout}, {@code cannot_show} (a lit, unlocked screen), {@code activity_gone},
 *       {@code other_intent}, {@code no_recipient} (a corrupt row, OD28), ...):
 *       {@code could_not_send}, "Couldn't send automatically" (F6).</li>
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

    /** CANNOT_SHOW cause -> one of the engine's detail strings ({@link RemindCause}), or null. */
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
            case RemindCause.DETAIL_LOCKED:
                return RemindCause.DETAIL_LOCKED;
            case RemindCause.DETAIL_LATE:
                return RemindCause.DETAIL_LATE;
            case RemindCause.DETAIL_STOPPED:
                return RemindCause.DETAIL_STOPPED;
            default:
                return RemindCause.DETAIL_COULD_NOT_SEND;
        }
    }

    /**
     * The planner's CANNOT_SHOW cause: {@code locked} while the keyguard is locked ("Unlock to
     * send"), else {@code cannot_show} (a lit, unlocked screen with no accessibility service:
     * "Couldn't send automatically", F6).
     */
    static String plannerCause(boolean keyguardLocked) {
        return keyguardLocked ? RemindCause.DETAIL_LOCKED : "cannot_show";
    }
}
