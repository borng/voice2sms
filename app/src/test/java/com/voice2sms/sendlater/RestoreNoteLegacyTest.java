package com.voice2sms.sendlater;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * D44 on the legacy path: the old default-prefs switch key on a fresh install (not updated on this
 * phone) is a restore of an older build. The note shows only when that restore turned an "on"
 * switch off.
 */
public class RestoreNoteLegacyTest {

    @Test public void oldKeyOnRestoredFromAnOlderBuildShowsTheNote() {
        SwitchBackupTest.MapPrefs d = new SwitchBackupTest.MapPrefs(), local = new SwitchBackupTest.MapPrefs();
        d.m.put(SendLaterSettings.KEY_ENABLED, true);
        assertFalse(SendLaterSettings.Switch.migrate(d, local, false));
        assertTrue(SendLaterSettings.Switch.offByRestore(local));
    }

    @Test public void oldKeyOffRestoredFromAnOlderBuildShowsNoNote() {
        SwitchBackupTest.MapPrefs d = new SwitchBackupTest.MapPrefs(), local = new SwitchBackupTest.MapPrefs();
        d.m.put(SendLaterSettings.KEY_ENABLED, false);
        assertFalse(SendLaterSettings.Switch.migrate(d, local, false));
        assertFalse(SendLaterSettings.Switch.offByRestore(local));
    }

    @Test public void unreadableFlagIsNoNote() {
        SwitchBackupTest.MapPrefs local = new SwitchBackupTest.MapPrefs();
        local.m.put(SendLaterSettings.RESTORE_NOTE_FLAG, "garbage");
        assertFalse(SendLaterSettings.Switch.offByRestore(local));
    }
}
