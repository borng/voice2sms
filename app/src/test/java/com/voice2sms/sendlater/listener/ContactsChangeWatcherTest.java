package com.voice2sms.sendlater.listener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** D37: a contacts change rebuilds the live-key gate once per debounce window while connected. */
public class ContactsChangeWatcherTest {

    private static final class FakeHost implements ContactsChangeWatcher.Host {
        boolean granted = true;
        boolean registerOk = true;
        int registers;
        int unregisters;
        int rebuilds;
        final List<Runnable> pending = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override public boolean readContactsGranted() { return granted; }
        @Override public boolean register() { registers++; return registerOk; }
        @Override public void unregister() { unregisters++; }
        @Override public void schedule(Runnable task, long delayMs) { pending.add(task); delays.add(delayMs); }
        @Override public void rebuildGate() { rebuilds++; }

        void runPending() {
            List<Runnable> now = new ArrayList<>(pending);
            pending.clear();
            for (Runnable r : now) r.run();
        }
    }

    @Test public void observerChangeTriggersOneDebouncedRebuild() {
        FakeHost h = new FakeHost();
        ContactsChangeWatcher w = new ContactsChangeWatcher(h);
        w.start();
        assertTrue(w.isRegistered());
        assertEquals(1, h.registers);

        w.onContactsChanged();
        w.onContactsChanged();
        w.onContactsChanged();
        assertEquals("nothing rebuilt before the window ends", 0, h.rebuilds);
        for (long d : h.delays) assertEquals(ContactsChangeWatcher.DEBOUNCE_MS, d);
        h.runPending();
        assertEquals("a burst costs one rebuild", 1, h.rebuilds);

        w.onContactsChanged();
        h.runPending();
        assertEquals(2, h.rebuilds);
    }

    @Test public void debounceIsAboutTwoSeconds() {
        assertEquals(2_000L, ContactsChangeWatcher.DEBOUNCE_MS);
    }

    @Test public void stopUnregistersAndDropsThePendingRebuild() {
        FakeHost h = new FakeHost();
        ContactsChangeWatcher w = new ContactsChangeWatcher(h);
        w.start();
        w.onContactsChanged();
        w.stop();
        assertEquals(1, h.unregisters);
        assertFalse(w.isRegistered());
        h.runPending();
        assertEquals(0, h.rebuilds);
        w.onContactsChanged();
        assertTrue("no scheduling while disconnected", h.pending.isEmpty());
        w.stop();
        assertEquals("unregister once", 1, h.unregisters);
    }

    @Test public void notRegisteredWithoutReadContactsUntilGranted() {
        FakeHost h = new FakeHost();
        h.granted = false;
        ContactsChangeWatcher w = new ContactsChangeWatcher(h);
        w.start();
        assertFalse(w.isRegistered());
        assertEquals(0, h.registers);
        w.stop();
        assertEquals(0, h.unregisters);

        w.start();
        h.granted = true; // the user grants READ_CONTACTS while connected
        w.ensureRegistered(); // next state broadcast
        assertTrue(w.isRegistered());
        w.ensureRegistered();
        assertEquals("registered once", 1, h.registers);
    }

    @Test public void refusedRegistrationRetriesLaterAndNothingWhenDisconnected() {
        FakeHost h = new FakeHost();
        h.registerOk = false;
        ContactsChangeWatcher w = new ContactsChangeWatcher(h);
        w.ensureRegistered();
        assertEquals("not before connect", 0, h.registers);
        w.start();
        assertFalse(w.isRegistered());
        h.registerOk = true;
        w.ensureRegistered();
        assertTrue(w.isRegistered());
        assertEquals(2, h.registers);
    }

    @Test public void listenerWiresTheObserverFromConnectToDisconnect() throws IOException {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/listener/SendLaterNotificationListener.java")),
                StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ")
                .replaceAll("\\s+", " ");
        assertTrue(src.contains("registerContentObserver( ContactsContract.Contacts.CONTENT_URI, true, contactsObserver)"));
        assertTrue(src.contains("unregisterContentObserver(contactsObserver)"));
        assertTrue(src.contains("WORKER.schedule(task, delayMs, TimeUnit.MILLISECONDS)"));
        assertTrue(src.contains("SendLaterNotificationListener.rebuildGate(app, false)"));
        int con = src.indexOf("public void onListenerConnected()");
        assertTrue(src.substring(con, src.indexOf("scheduleRebuild(app, true)", con)).contains("contactsWatcher().start()"));
        int dis = src.indexOf("public void onListenerDisconnected()");
        assertTrue(src.substring(dis, src.indexOf("super.onListenerDisconnected()", dis)).contains("stopContactsWatcher()"));
        int des = src.indexOf("public void onDestroy()");
        assertTrue(src.substring(des, src.indexOf("super.onDestroy()", des)).contains("stopContactsWatcher()"));
        int rec = src.indexOf("public void onReceive(");
        assertTrue("a grant is picked up at the next state broadcast",
                src.substring(rec, src.indexOf("scheduleRebuild(", rec)).contains("w.ensureRegistered()"));
    }
}
