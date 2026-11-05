package com.voice2sms.sendlater.engine;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.NetworkState;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.RpcThreadEventSource;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.ThreadEventSource;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.listener.NotificationThreadEventSource;
import com.voice2sms.sendlater.listener.SendLaterListenerStatus;
import com.voice2sms.sendlater.store.SendLaterRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The engine's Android wiring. */
final class AndroidEngineDeps {

    private AndroidEngineDeps() {}

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sendlater-engine");
        t.setDaemon(true);
        return t;
    });

    static EngineDeps create(Context app, List<ThreadEventSource> extra) {
        List<ThreadEventSource> sources = new ArrayList<>();
        // OD22(7): the reply-listener node's source is the only listener-backed source.
        sources.add(NotificationThreadEventSource.create(app));
        sources.add(new RpcThreadEventSource());
        if (extra != null) sources.addAll(extra);
        return new EngineDeps.Builder()
                .store(new RepositoryStore(app))
                .sources(sources)
                .notifier(new Notifier(app))
                .broadcaster(new Broadcaster(app))
                .alarms(new AndroidAlarmSink(app))
                .conditions(new Conditions(app))
                .networkWaiter(new Waiter(app))
                .executor(EXECUTOR)
                .marks(new PrefsMarks(app))
                .hopOffMainThread(true)
                .build();
    }

    // ------------------------------------------------------------------ store

    static final class RepositoryStore implements EngineStore {
        private final Context app;

        RepositoryStore(Context app) { this.app = app; }

        private SendLaterRepository r() { return SendLaterRepository.get(app); }

        @Override public ScheduledMessage get(long id) { return r().get(id); }
        @Override public boolean markInFlight(long id, String bodyHash) { return r().markInFlight(id, bodyHash); }
        @Override public boolean markSentVerified(long id) { return r().markSentVerified(id); }
        @Override public boolean hold(long id, HoldReason reason) { return r().hold(id, reason); }
        @Override public boolean cancel(long id) { return r().cancel(id); }
        @Override public boolean selfPreempt(long id) { return r().selfPreempt(id); }
        @Override public boolean expire(long id) { return r().expire(id); }
        @Override public boolean fail(long id, String lastError) { return r().fail(id, lastError); }
        @Override public boolean releaseForRetry(long id, String lastError) { return r().releaseForRetry(id, lastError); }
        @Override public boolean reschedule(long id, long scheduledFor, boolean holdIfReplied,
                                            LatenessPolicy policy, Watermark newWm) {
            return r().reschedule(id, scheduledFor, holdIfReplied, policy, newWm);
        }
        @Override public List<ScheduledMessage> allPending() { return r().allPending(); }
        @Override public List<ScheduledMessage> allHeld() { return r().allHeld(); }
        @Override public List<ScheduledMessage> inFlight() { return r().inFlight(); }
        @Override public List<ScheduledMessage> pendingForThread(String e164) { return r().pendingForThread(e164); }
        @Override public boolean upgradeRung(long id, Rung from, Rung to) { return r().upgradeRung(id, from, to); }
    }

    // ------------------------------------------------------------------ engine marks

    /** {@link EngineMarks} in SharedPreferences {@code sendlater_engine}; survives process death. */
    static final class PrefsMarks implements EngineMarks {
        private static final String PREFS = "sendlater_engine";
        private final Context app;
        /** Fallback when prefs cannot be opened, so marks still hold for this process. */
        private final EngineMarks.InMemory memory = new EngineMarks.InMemory();

        PrefsMarks(Context app) { this.app = app; }

        private SharedPreferences prefs() {
            try {
                return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static String remindedKey(long id) { return "reminded_" + id; }

        private static String freeKey(long id) { return "free_releases_" + id; }

        private static String lastCallKey(long id) { return "last_call_" + id; }

        @Override public boolean isReminded(long id) {
            SharedPreferences p = prefs();
            if (p == null) return memory.isReminded(id);
            return p.getBoolean(remindedKey(id), false) || memory.isReminded(id);
        }

        @Override public void setReminded(long id, boolean reminded) {
            memory.setReminded(id, reminded);
            SharedPreferences p = prefs();
            if (p == null) return;
            if (reminded) {
                p.edit().putBoolean(remindedKey(id), true).commit();
            } else if (p.contains(remindedKey(id))) {
                p.edit().remove(remindedKey(id)).commit();
            }
        }

        @Override public int freeReleases(long id) {
            SharedPreferences p = prefs();
            if (p == null) return memory.freeReleases(id);
            return Math.max(p.getInt(freeKey(id), 0), memory.freeReleases(id));
        }

        @Override public void addFreeRelease(long id) {
            int next = freeReleases(id) + 1;
            memory.addFreeRelease(id);
            SharedPreferences p = prefs();
            if (p != null) p.edit().putInt(freeKey(id), next).commit();
        }

        @Override public boolean isLastCallPosted(long id, long scheduledFor) {
            if (memory.isLastCallPosted(id, scheduledFor)) return true;
            SharedPreferences p = prefs();
            return p != null && p.contains(lastCallKey(id)) && p.getLong(lastCallKey(id), -1L) == scheduledFor;
        }

        @Override public void setLastCallPosted(long id, long scheduledFor) {
            memory.setLastCallPosted(id, scheduledFor);
            SharedPreferences p = prefs();
            if (p != null) p.edit().putLong(lastCallKey(id), scheduledFor).commit();
        }

        @Override public void clearLastCall(long id) {
            memory.clearLastCall(id);
            SharedPreferences p = prefs();
            if (p != null && p.contains(lastCallKey(id))) p.edit().remove(lastCallKey(id)).commit();
        }

        @Override public void clear(long id) {
            memory.clear(id);
            SharedPreferences p = prefs();
            if (p != null) p.edit().remove(remindedKey(id)).remove(freeKey(id)).remove(lastCallKey(id)).commit();
        }
    }

    // ------------------------------------------------------------------ notifier

    static final class Notifier implements EngineNotifier {
        private final Context app;

        Notifier(Context app) { this.app = app; }

        @Override public void lastCall(ScheduledMessage m) { SendLaterNotifications.lastCall(app, m); }
        @Override public void hold(ScheduledMessage m, HoldReason r, long nowMs) { SendLaterNotifications.hold(app, m, r, nowMs); }
        @Override public void remind(ScheduledMessage m, RemindReason r) { SendLaterNotifications.remind(app, m, r); }
        @Override public void remindCannotSend(ScheduledMessage m, RemindCause cause) {
            SendLaterNotifications.remindCannotSend(app, m, cause);
        }
        @Override public void sent(ScheduledMessage m) { SendLaterNotifications.sent(app, m); }
        @Override public void repliedDuringSend(ScheduledMessage m) { SendLaterNotifications.repliedDuringSend(app, m); }
        @Override public void selfPreempted(ScheduledMessage m) { SendLaterNotifications.selfPreempted(app, m); }
        @Override public void expired(ScheduledMessage m) { SendLaterNotifications.expired(app, m); }
        @Override public void mayHaveSent(ScheduledMessage m) { SendLaterNotifications.mayHaveSent(app, m); }
        @Override public void mayHaveSent(ScheduledMessage m, boolean duplicateRisk) {
            SendLaterNotifications.mayHaveSent(app, m, duplicateRisk);
        }
        @Override public void sendFailed(ScheduledMessage m) { SendLaterNotifications.sendFailed(app, m); }
        @Override public void dismiss(long id) { SendLaterNotifications.dismiss(app, id); }
        @Override public void sending(ScheduledMessage m) { SendLaterNotifications.sending(app, m); }
        @Override public void staleTap(long id, ScheduledMessage m, TapResult r, boolean cancelTap) {
            SendLaterNotifications.staleTap(app, id, m, r, cancelTap);
        }
    }

    // ------------------------------------------------------------------ broadcaster

    static final class Broadcaster implements StateBroadcaster {
        private final Context app;

        Broadcaster(Context app) { this.app = app; }

        @Override public void stateChanged(long id, String e164) {
            Intent i = new Intent(SendLaterIntents.ACTION_STATE_CHANGED)
                    .setPackage(app.getPackageName())
                    .putExtra(SendLaterIntents.EXTRA_MESSAGE_ID, id);
            if (e164 != null) i.putExtra(SendLaterIntents.EXTRA_E164, e164);
            app.sendBroadcast(i);
        }
    }

    // ------------------------------------------------------------------ conditions

    static final class Conditions implements EngineConditions {
        private final Context app;

        Conditions(Context app) { this.app = app; }

        @Override public long nowMs() { return System.currentTimeMillis(); }

        @Override public SessionState session() {
            if (!GvSession.hasWebSession(app)) return SessionState.MISSING;
            if (lockedAndUnshowable()) return SessionState.LOCKED_UNSHOWABLE;
            return SessionState.READY;
        }

        /** D15: locked phone and no full-screen-intent grant to show the send activity over it. */
        private boolean lockedAndUnshowable() {
            try {
                KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
                if (km == null || !km.isKeyguardLocked()) return false;
                if (Build.VERSION.SDK_INT >= 34) {
                    NotificationManager nm = app.getSystemService(NotificationManager.class);
                    return nm == null || !nm.canUseFullScreenIntent();
                }
                return ContextCompat.checkSelfPermission(app, Manifest.permission.USE_FULL_SCREEN_INTENT)
                        != PackageManager.PERMISSION_GRANTED;
            } catch (RuntimeException e) {
                return true;
            }
        }

        @Override public NetworkState network() {
            try {
                ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return NetworkState.NONE;
                Network n = cm.getActiveNetwork();
                if (n == null) return NetworkState.NONE;
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        ? NetworkState.CONNECTED : NetworkState.NONE;
            } catch (RuntimeException e) {
                return NetworkState.NONE;
            }
        }

        /** Same rule as the listener source's availability: enabled and bound recently (OD22(7)). */
        @Override public boolean listenerEnabled() {
            try {
                return SendLaterListenerStatus.isAvailable(app);
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** Fails closed: unreadable preferences count as off (nothing sends automatically). */
        @Override public boolean sendLaterEnabled() {
            try {
                return SendLaterSettings.isEnabled(app);
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override public OutboundPolicy outboundPolicy() {
            try {
                return SendLaterSettings.outboundPolicy(app);
            } catch (RuntimeException e) {
                return SendLaterSettings.DEFAULT_OUTBOUND_POLICY;
            }
        }
    }

    // ------------------------------------------------------------------ network wait (WAIT, R27)

    /**
     * registerDefaultNetworkCallback per waiting message, gated by {@link NetworkWaitGate}: only an
     * onCapabilitiesChanged with INTERNET + VALIDATED re-runs the ladder, once, then unregisters.
     */
    static final class Waiter implements NetworkWaiter {
        private final NetworkWaitGate gate;

        Waiter(final Context app) {
            this.gate = new NetworkWaitGate(new NetworkWaitGate.Registrar() {
                @Override public Object register(final long id, final NetworkWaitGate g) {
                    ConnectivityManager cm =
                            (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm == null) return null;
                    ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                            if (caps == null) return;
                            g.onCapabilities(id,
                                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                                    Build.VERSION.SDK_INT);
                        }
                    };
                    try {
                        cm.registerDefaultNetworkCallback(cb);
                        return cb;
                    } catch (RuntimeException e) {
                        // Too many callbacks or no permission: the RETRY alarm at the deadline still fires.
                        return null;
                    }
                }

                @Override public void unregister(Object handle) {
                    try {
                        ConnectivityManager cm =
                                (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
                        if (cm != null) cm.unregisterNetworkCallback((ConnectivityManager.NetworkCallback) handle);
                    } catch (RuntimeException ignored) {
                        // already unregistered
                    }
                }
            }, (id, done) -> SendLaterEngine.runInBackground(app, () -> SendLaterEngine.onDue(app, id), done));
        }

        @Override public void awaitNetwork(long id) {
            gate.await(id);
        }

        @Override public void cancel(long id) {
            gate.cancel(id);
        }
    }
}
