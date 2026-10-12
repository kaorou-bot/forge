package forge.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns only the UI and waits created by one remote choice, never the entire screen. */
public final class GuiRequestScope implements AutoCloseable {
    public enum Reason { CANCELLED, TIMED_OUT, GAME_CLOSED }
    public interface WaitRegistration extends Registration {
        WaitRegistration NONE = new WaitRegistration() {
            @Override public void ready() { }
            @Override public void close() { }
        };
        void ready();
    }
    public interface Activity {
        Activity NONE = new Activity() { };
        default void waiting() { }
        default void ready() { }
        default void inputReady() { ready(); }
        default void answered() { }
        default void processingStarted() { }
        default void processingFinished() { }
        default WaitRegistration beginWait() {
            waiting();
            final Activity owner = this;
            return new WaitRegistration() {
                private final AtomicBoolean completed = new AtomicBoolean();
                @Override public void ready() { if (!completed.get()) owner.ready(); }
                @Override public void close() { if (completed.compareAndSet(false, true)) owner.answered(); }
            };
        }
        default Runnable captureReadiness() { return this::ready; }
    }
    public interface Registration extends AutoCloseable {
        Registration NONE = () -> { };
        @Override void close();
    }

    public static final class Cancelled extends RuntimeException implements forge.game.player.DecisionWaitCancelled {
        private static final long serialVersionUID = 1L;
        private final Reason reason;
        private Cancelled(final Reason reason) {
            super("The UI request is no longer active: " + reason);
            this.reason = reason;
        }
        public Reason reason() { return reason; }
    }

    private static final ThreadLocal<GuiRequestScope> CURRENT = new ThreadLocal<>();
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private final AtomicReference<RuntimeException> cleanupFailure = new AtomicReference<>();
    private volatile boolean cancelled;
    private Reason reason = Reason.CANCELLED;
    private final Activity activity;

    public GuiRequestScope() { this(Activity.NONE); }
    public GuiRequestScope(final Activity activity) { this.activity = java.util.Objects.requireNonNull(activity); }
    public Activity activity() { return activity; }
    public void ready() { if (!cancelled) activity.ready(); }
    public void inputReady() { if (!cancelled) activity.inputReady(); }
    public WaitRegistration beginWait() {
        checkActive();
        return activity.beginWait();
    }
    public Runnable captureReadiness() {
        final Runnable ready = activity.captureReadiness();
        return () -> { if (!cancelled) ready.run(); };
    }

    public static GuiRequestScope current() { return CURRENT.get(); }
    public static boolean isCurrentCancelled() {
        final GuiRequestScope scope = current();
        return scope != null && scope.isCancelled();
    }
    public boolean isCancelled() { return cancelled; }
    public RuntimeException cleanupFailure() { return cleanupFailure.get(); }
    public void checkActive() { if (cancelled) throw cancellation(); }
    public synchronized Cancelled cancellation() { return new Cancelled(reason); }

    /** Explicitly restore even an empty context; Swing modal event pumps can nest tasks. */
    public static Runnable bind(final GuiRequestScope scope, final Runnable task) {
        return () -> {
            final GuiRequestScope previous = current();
            if (scope == null) CURRENT.remove(); else CURRENT.set(scope);
            try { task.run(); }
            finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        };
    }

    public static Runnable capture(final Runnable task) { return bind(current(), task); }

    /** A synchronous modal must suspend ambient identity while pumping unrelated UI events. */
    public static void runWithoutContext(final Runnable modalEventPump) {
        bind(null, modalEventPump).run();
    }

    /** Deferred UI actions use the explicit owner, not whichever request last used the UI thread. */
    public static void runAction(final GuiRequestScope owner, final Runnable action) {
        if (owner == null || !owner.isCancelled()) bind(owner, action).run();
    }

    /** Listeners must be nonblocking; UI listeners enqueue their own instance's cleanup. */
    public Registration onCancel(final Runnable action) {
        final Listener listener = new Listener(java.util.Objects.requireNonNull(action));
        synchronized (this) {
            if (!cancelled) {
                listeners.add(listener);
                return listener;
            }
        }
        listener.fire();
        return listener;
    }

    @Override
    public void close() {
        cancel(Reason.CANCELLED);
    }

    /** First terminal reason wins; cleanup never downgrades a timeout to an ordinary cancel. */
    public void cancel(final Reason cause) {
        java.util.Objects.requireNonNull(cause);
        final ArrayList<Listener> pending;
        synchronized (this) {
            if (cancelled) return;
            reason = cause;
            cancelled = true;
            pending = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (final Listener listener : pending) listener.fire();
    }

    /** Record asynchronous cleanup failures too, without abandoning other waits. */
    public Runnable guardCleanup(final Runnable cleanup) {
        return () -> {
            try { cleanup.run(); }
            catch (final RuntimeException e) {
                cleanupFailure.compareAndSet(null, e);
                // The scope may finish before this asynchronous callback, so log at the source.
                forge.util.IHasForgeLog.netLog.error(e, "Unable to clean up a cancelled UI request");
            }
        };
    }

    private final class Listener implements Registration {
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final Runnable action;
        private Listener(final Runnable action) { this.action = action; }
        private void fire() {
            if (active.compareAndSet(true, false)) guardCleanup(action).run();
        }
        @Override public void close() {
            active.set(false);
            synchronized (GuiRequestScope.this) { listeners.remove(this); }
        }
    }
}
