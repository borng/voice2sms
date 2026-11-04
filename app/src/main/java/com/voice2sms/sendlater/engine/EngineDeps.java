package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.ThreadEventSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/** Everything the engine talks to. Production: {@link AndroidEngineDeps#create}. */
public final class EngineDeps {

    final EngineStore store;
    final List<ThreadEventSource> sources;
    final EngineNotifier notifier;
    final StateBroadcaster broadcaster;
    final AlarmSink alarms;
    final EngineConditions conditions;
    final NetworkWaiter networkWaiter;
    final Executor executor;
    /** Engine-owned per-message marks (reminded, free releases); in-memory unless set. */
    final EngineMarks marks;
    /** True for the Android wiring: entry points called on the main thread hop to the executor. */
    final boolean hopOffMainThread;

    private EngineDeps(Builder b) {
        this.store = req(b.store, "store");
        this.sources = b.sources == null
                ? Collections.<ThreadEventSource>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(b.sources));
        this.notifier = req(b.notifier, "notifier");
        this.broadcaster = req(b.broadcaster, "broadcaster");
        this.alarms = req(b.alarms, "alarms");
        this.conditions = req(b.conditions, "conditions");
        this.networkWaiter = req(b.networkWaiter, "networkWaiter");
        this.executor = req(b.executor, "executor");
        this.marks = b.marks != null ? b.marks : new EngineMarks.InMemory();
        this.hopOffMainThread = b.hopOffMainThread;
    }

    private static <T> T req(T t, String name) {
        if (t == null) throw new IllegalArgumentException(name);
        return t;
    }

    public static final class Builder {
        private EngineStore store;
        private List<ThreadEventSource> sources;
        private EngineNotifier notifier;
        private StateBroadcaster broadcaster;
        private AlarmSink alarms;
        private EngineConditions conditions;
        private NetworkWaiter networkWaiter;
        private Executor executor;
        private EngineMarks marks;
        private boolean hopOffMainThread;

        public Builder store(EngineStore v) { store = v; return this; }
        public Builder sources(List<ThreadEventSource> v) { sources = v; return this; }
        public Builder notifier(EngineNotifier v) { notifier = v; return this; }
        public Builder broadcaster(StateBroadcaster v) { broadcaster = v; return this; }
        public Builder alarms(AlarmSink v) { alarms = v; return this; }
        public Builder conditions(EngineConditions v) { conditions = v; return this; }
        public Builder networkWaiter(NetworkWaiter v) { networkWaiter = v; return this; }
        public Builder executor(Executor v) { executor = v; return this; }
        public Builder marks(EngineMarks v) { marks = v; return this; }
        Builder hopOffMainThread(boolean v) { hopOffMainThread = v; return this; }

        public EngineDeps build() { return new EngineDeps(this); }
    }
}
