package forge.relay.client;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Owns resources until a background connection operation succeeds or is cancelled. */
public final class ConnectionAttempt {
    public enum Stage { CONNECTING, SECURING, LISTING, CREATING, JOINING, CONNECTING_GAME, CANCELLING }

    private static final ThreadLocal<ConnectionAttempt> CURRENT = new ThreadLocal<>();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final Consumer<Stage> progress;
    private boolean cancelled;
    private boolean finished;

    public ConnectionAttempt(final Consumer<Stage> progress) {
        this.progress = progress;
    }

    public <T> T run(final Callable<T> task) throws Exception {
        CURRENT.set(this);
        try {
            check();
            T result = task.call();
            synchronized (this) {
                check();
                finished = true;
                resources.clear();
            }
            return result;
        } catch (Exception e) {
            final List<AutoCloseable> pending;
            synchronized (this) {
                finished = true;
                pending = new ArrayList<>(resources);
                resources.clear();
            }
            for (int i = pending.size() - 1; i >= 0; i--) closeQuietly(pending.get(i));
            throw e;
        } finally {
            CURRENT.remove();
        }
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        final List<AutoCloseable> pending;
        synchronized (this) {
            if (finished || cancelled) return;
            cancelled = true;
            pending = new ArrayList<>(resources);
            resources.clear();
        }
        // Close newest resources first: the owning client/session before its sockets.
        for (int i = pending.size() - 1; i >= 0; i--) {
            closeQuietly(pending.get(i));
        }
    }

    public static <T extends AutoCloseable> T track(final T resource) throws InterruptedIOException {
        ConnectionAttempt attempt = CURRENT.get();
        if (attempt != null) {
            synchronized (attempt) {
                if (attempt.cancelled) {
                    closeQuietly(resource);
                    throw new InterruptedIOException("Connection cancelled");
                }
                attempt.resources.add(resource);
            }
        }
        return resource;
    }

    public static void report(final Stage stage) throws InterruptedIOException {
        ConnectionAttempt attempt = CURRENT.get();
        if (attempt != null) {
            attempt.check();
            attempt.progress.accept(stage);
        }
    }

    private synchronized void check() throws InterruptedIOException {
        if (cancelled) throw new InterruptedIOException("Connection cancelled");
    }

    private static void closeQuietly(final AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // Cancellation must still close the other resources.
        }
    }
}
