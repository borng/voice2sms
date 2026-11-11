package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * LIVE-F13: the stable V2SBridge forwards to the Activity attached here. Attach in onCreate,
 * detach in onDestroy; an old Activity's late onDestroy must not cut off the new one.
 */
public class LiveDelegateTest {

    private static final class Target {}

    @Test
    public void nothingAttached_currentIsNull() {
        assertNull(new LiveDelegate<Target>().current());
    }

    @Test
    public void attach_makesTheTargetLive() {
        LiveDelegate<Target> d = new LiveDelegate<>();
        Target a = new Target();
        d.attach(a);
        assertSame(a, d.current());
    }

    @Test
    public void detachOfTheLiveTarget_clearsIt() {
        LiveDelegate<Target> d = new LiveDelegate<>();
        Target a = new Target();
        d.attach(a);
        assertTrue(d.detach(a));
        assertNull(d.current());
    }

    /** Reopen: new onCreate runs, then the old Activity's onDestroy. */
    @Test
    public void oldDetachAfterNewAttach_keepsTheNewTarget() {
        LiveDelegate<Target> d = new LiveDelegate<>();
        Target oldActivity = new Target();
        Target newActivity = new Target();
        d.attach(oldActivity);
        d.attach(newActivity);
        assertFalse(d.detach(oldActivity));
        assertSame(newActivity, d.current());
        assertTrue(d.detach(newActivity));
        assertNull(d.current());
    }

    /** Back out then reopen: old onDestroy first, then the new onCreate. */
    @Test
    public void detachThenAttach_switchesToTheNewTarget() {
        LiveDelegate<Target> d = new LiveDelegate<>();
        Target oldActivity = new Target();
        Target newActivity = new Target();
        d.attach(oldActivity);
        assertTrue(d.detach(oldActivity));
        assertNull(d.current());
        d.attach(newActivity);
        assertSame(newActivity, d.current());
    }

    @Test
    public void detachTwice_orOfNull_isHarmless() {
        LiveDelegate<Target> d = new LiveDelegate<>();
        Target a = new Target();
        assertFalse(d.detach(a));
        assertFalse(d.detach(null));
        d.attach(a);
        assertTrue(d.detach(a));
        assertFalse(d.detach(a));
        assertNull(d.current());
    }

    @Test
    public void attachNull_isIgnored() {
        LiveDelegate<Target> d = new LiveDelegate<>();
        Target a = new Target();
        d.attach(a);
        d.attach(null);
        assertSame(a, d.current());
    }

    @Test
    public void identityNotEquals() {
        LiveDelegate<String> d = new LiveDelegate<>();
        String a = new String("same");
        String b = new String("same");
        d.attach(a);
        assertFalse("an equal but different target is not the live one", d.detach(b));
        assertSame(a, d.current());
    }
}
