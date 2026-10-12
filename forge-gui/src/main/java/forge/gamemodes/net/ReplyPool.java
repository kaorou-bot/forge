package forge.gamemodes.net;

import com.google.common.collect.Maps;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ReplyPool {

    private final Map<Integer, CompletableFuture> pool = Maps.newHashMap();
    private boolean closed;

    public ReplyPool() {
    }

    public void initialize(final int index) {
        initialize(index, () -> { });
    }

    public void initialize(final int index, final Runnable beforeAnswer) {
        synchronized (pool) {
            if (closed) return; // A late request must not begin waiting on a dead connection.
            if (pool.containsKey(index)) throw new IllegalStateException("Duplicate reply index: " + index);
            pool.put(index, new CompletableFuture(beforeAnswer));
        }
    }

    public void complete(final int index, final Object value) {
        final CompletableFuture future;
        synchronized (pool) {
            future = pool.get(index);
        }
        if (future != null && !future.isDone()) {
            // Deadline callbacks may cancel UI/other waits. Never run them under pool's
            // monitor (the transport enqueue lock uses the opposite acquisition order).
            try { future.beforeAnswer.run(); }
            catch (final RuntimeException failure) { future.fail(failure); }
            future.set(value); // A deadline callback may have failed this future instead.
        }
    }

    public Object get(final int index) {
        return get(index, 0);
    }

    /** Fail just this wait. Unlike legacy cancelAll, this never fabricates a null answer. */
    public boolean fail(final int index, final RuntimeException reason) {
        java.util.Objects.requireNonNull(reason);
        synchronized (pool) {
            final CompletableFuture future = pool.get(index);
            if (future == null || future.isDone()) return false;
            future.fail(reason);
            // Retain the terminal result until get(), including cancellation before get starts.
            return true;
        }
    }

    public boolean isPending(final int index) {
        synchronized (pool) {
            final CompletableFuture future = pool.get(index);
            return future != null && !future.isDone();
        }
    }

    /** A timeout is appropriate for informational queries, never for human choices. */
    public Object get(final int index, final long timeoutMillis) {
        final CompletableFuture future;
        synchronized (pool) {
            future = pool.get(index);
        }
        if (future == null) return null;
        try {
            return timeoutMillis > 0 ? future.get(timeoutMillis, TimeUnit.MILLISECONDS) : future.get();
        } catch (final TimeoutException e) {
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            throw new RuntimeException(e);
        } finally {
            synchronized (pool) {
                pool.remove(index, future);
            }
        }
    }

    /**
     * Cancel all pending replies by completing them with null.
     * This is used when a player is converted to AI to unblock any waiting game threads.
     */
    public java.util.List<Integer> cancelAll() {
        synchronized (pool) {
            final java.util.List<Integer> cancelled = new java.util.ArrayList<>();
            final java.util.Iterator<Map.Entry<Integer, CompletableFuture>> entries = pool.entrySet().iterator();
            while (entries.hasNext()) {
                final Map.Entry<Integer, CompletableFuture> entry = entries.next();
                final CompletableFuture future = entry.getValue();
                // A completed answer has already won the race, even if get() hasn't run yet.
                if (future.isDone()) continue;
                cancelled.add(entry.getKey());
                // Complete with null to unblock waiting threads
                future.set(null);
                entries.remove();
            }
            return cancelled;
        }
    }

    /** Permanently retire this connection's pool; cancelAll alone permits later requests. */
    public void close() {
        synchronized (pool) {
            closed = true;
            cancelAll();
        }
    }

    private static final class CompletableFuture extends FutureTask<Object> {
        private final Runnable beforeAnswer;
        public CompletableFuture(final Runnable beforeAnswer) {
            super(() -> null);
            this.beforeAnswer = java.util.Objects.requireNonNull(beforeAnswer);
        }

        @Override
        public void set(final Object v) {
            super.set(v);
        }

        private void fail(final RuntimeException reason) { super.setException(reason); }
    }
}
