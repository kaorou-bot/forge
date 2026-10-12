package forge.gamemodes.net;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Host-side per-game accounting. No scheduler, GUI calls or game mutation happens under this lock.
 * A caller must explicitly mark a request ready when the player can actually act.
 * Disconnecting does not stop a ready request. Pending requests may be marked ready on disconnect
 * to prevent avoiding the clock by disconnecting before the prompt acknowledgement.
 */
public final class PlayerOperationClock {
    public enum Completion { ACCEPTED, TIMED_OUT, INVALIDATED }
    public record Snapshot(long remainingMillis, boolean running, boolean expired) { }

    private final LongSupplier nanoTime;
    private final Map<Integer, Budget> budgets = new LinkedHashMap<>();
    private final Map<Long, Request> requests = new HashMap<>();
    private long generation;
    private long nextRequestId;

    public PlayerOperationClock() {
        this(System::nanoTime);
    }

    public PlayerOperationClock(final LongSupplier nanoTime) {
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
    }

    /** Reset must be called only at a new game's boundary, never when reconnecting. */
    public synchronized void reset(final Collection<Integer> playerIds, final long allowanceMillis) {
        if (allowanceMillis <= 0 || allowanceMillis > TimeUnit.DAYS.toMillis(1)) {
            throw new IllegalArgumentException("Operation allowance must be between 1 ms and 24 hours");
        }
        final Map<Integer, Budget> replacement = new LinkedHashMap<>();
        for (final Integer playerId : playerIds) {
            if (playerId == null || replacement.putIfAbsent(playerId,
                    new Budget(TimeUnit.MILLISECONDS.toNanos(allowanceMillis))) != null) {
                throw new IllegalArgumentException("Player IDs must be non-null and unique");
            }
        }
        if (replacement.isEmpty()) throw new IllegalArgumentException("At least one player is required");
        generation++;
        budgets.clear();
        budgets.putAll(replacement);
        requests.clear();
    }

    public synchronized Request begin(final int playerId) {
        final Budget budget = budget(playerId);
        settle(budget, nanoTime.getAsLong());
        final Request request = new Request(++nextRequestId, generation, playerId);
        requests.put(request.id, request);
        return request;
    }

    /** Waiting through disconnection is chargeable; this is not a fresh allowance. */
    public synchronized void continueWhileDisconnected(final int playerId) {
        budget(playerId);
        for (final Request request : requests.values()) {
            if (request.playerId == playerId) request.ready();
        }
    }

    public synchronized Snapshot snapshot(final int playerId) {
        final Budget budget = budget(playerId);
        settle(budget, nanoTime.getAsLong());
        // Round up only for display. All charging uses nanoseconds without per-request rounding.
        final long millis = (budget.remainingNanos + 999_999L) / 1_000_000L;
        return new Snapshot(millis, budget.active > 0 && budget.remainingNanos > 0,
                budget.remainingNanos == 0);
    }

    /** Each player is returned at most once per game. The caller processes losses outside the lock. */
    public synchronized List<Integer> drainExpiredPlayers() {
        final List<Integer> result = new ArrayList<>();
        final long now = nanoTime.getAsLong();
        for (final Map.Entry<Integer, Budget> entry : budgets.entrySet()) {
            final Budget budget = entry.getValue();
            settle(budget, now);
            if (budget.remainingNanos == 0 && !budget.reported) {
                budget.reported = true;
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** End-of-game/room teardown invalidates all late replies and does not announce more losses. */
    public synchronized void clear() {
        generation++;
        requests.clear();
        budgets.clear();
    }

    private Budget budget(final int playerId) {
        final Budget value = budgets.get(playerId);
        if (value == null) throw new IllegalArgumentException("Unknown clock player: " + playerId);
        return value;
    }

    private static void settle(final Budget budget, final long now) {
        if (budget.active > 0) {
            final long elapsed = Math.max(0, now - budget.startedAt);
            budget.remainingNanos = Math.max(0, budget.remainingNanos - elapsed);
        }
        budget.startedAt = now;
    }

    private static final class Budget {
        private long remainingNanos;
        private long startedAt;
        private int active;
        private boolean reported;
        private Budget(final long remainingNanos) { this.remainingNanos = remainingNanos; }
    }

    public final class Request implements AutoCloseable {
        private final long id;
        private final long gameGeneration;
        private final int playerId;
        private boolean ready;

        private Request(final long id, final long gameGeneration, final int playerId) {
            this.id = id;
            this.gameGeneration = gameGeneration;
            this.playerId = playerId;
        }

        /** Duplicate ready acknowledgements cannot restart or double-charge a request. */
        public boolean ready() {
            synchronized (PlayerOperationClock.this) {
                if (!isCurrent()) return false;
                final Budget budget = budget(playerId);
                settle(budget, nanoTime.getAsLong());
                if (budget.remainingNanos == 0) return false;
                if (!ready) {
                    ready = true;
                    budget.active++;
                }
                return true;
            }
        }

        /** Atomically stop charging and decide whether this answer arrived before expiry. */
        public Completion complete() {
            synchronized (PlayerOperationClock.this) {
                if (!isCurrent()) return Completion.INVALIDATED;
                final Budget budget = budget(playerId);
                settle(budget, nanoTime.getAsLong());
                requests.remove(id);
                if (ready) budget.active--;
                return budget.remainingNanos > 0 ? Completion.ACCEPTED : Completion.TIMED_OUT;
            }
        }

        private boolean isCurrent() {
            return gameGeneration == generation && requests.get(id) == this;
        }

        @Override public void close() {
            complete();
        }
    }
}
