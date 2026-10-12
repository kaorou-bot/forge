package forge.gamemodes.net;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Request ownership and per-game lifetime for an authoritative operation clock.
 * Does not run a scheduler or mutate rules; the integration applies Expiry on its safe game boundary.
 * The UI executor must enqueue work without waiting for the UI thread.
 */
public final class PlayerDecisionSession {
    public record Decision<T>(long game, long id, int player, PendingPlayerDecision<T> pending) { }
    public record Expiry(long game, List<Integer> players) {
        public Expiry { players = List.copyOf(players); }
    }

    private final PlayerOperationClock clock;
    private final Consumer<Runnable> uiExecutor;
    private final Map<Long, Decision<?>> pending = new HashMap<>();
    private final Set<Integer> disconnected = new HashSet<>();
    private long game;
    private long nextId;

    public PlayerDecisionSession(final Consumer<Runnable> uiExecutor) {
        this(System::nanoTime, uiExecutor);
    }

    public PlayerDecisionSession(final LongSupplier nanoTime, final Consumer<Runnable> uiExecutor) {
        clock = new PlayerOperationClock(nanoTime);
        this.uiExecutor = java.util.Objects.requireNonNull(uiExecutor);
    }

    public long startGame(final Collection<Integer> players, final long allowanceMillis) {
        final List<Decision<?>> old;
        final long current;
        synchronized (this) {
            // Validation happens before altering the existing registry.
            clock.reset(players, allowanceMillis);
            current = ++game;
            old = new ArrayList<>(pending.values());
            pending.clear();
            disconnected.clear();
        }
        cancel(old, PendingPlayerDecision.Status.CANCELLED);
        return current;
    }

    public <T> Decision<T> begin(final int player) {
        final Decision<T> decision;
        final boolean expired;
        synchronized (this) {
            final PlayerOperationClock.Request request = clock.begin(player);
            decision = new Decision<>(game, ++nextId, player, new PendingPlayerDecision<>(request, uiExecutor));
            expired = clock.snapshot(player).expired();
            if (!expired) {
                pending.put(decision.id(), decision);
                if (disconnected.contains(player)) request.ready();
            }
        }
        if (expired) decision.pending().cancel(PendingPlayerDecision.Status.TIMED_OUT);
        return decision;
    }

    /** player is derived from the authenticated connection, never trusted from the packet. */
    public boolean answer(final long gameId, final int player, final long requestId, final Object answer) {
        final Decision<?> decision;
        synchronized (this) {
            decision = pending.get(requestId);
            if (gameId != game || decision == null || decision.game() != gameId || decision.player() != player) {
                return false;
            }
            pending.remove(requestId);
        }
        return answerUnchecked(decision, answer);
    }

    @SuppressWarnings("unchecked")
    private static <T> boolean answerUnchecked(final Decision<T> decision, final Object value) {
        // The protocol-specific caller validates the expected answer type/shape before this call.
        return decision.pending().answer((T) value);
    }

    public synchronized void setConnected(final int player, final boolean connected) {
        // Validate even the reconnect path, without resetting a budget.
        clock.snapshot(player);
        if (connected) disconnected.remove(player);
        else {
            disconnected.add(player);
            clock.continueWhileDisconnected(player);
        }
    }

    public Expiry tick() {
        final List<Decision<?>> cancelled = new ArrayList<>();
        final Expiry expiry;
        synchronized (this) {
            expiry = new Expiry(game, clock.drainExpiredPlayers());
            pending.values().removeIf(decision -> {
                if (!expiry.players().contains(decision.player())) return false;
                cancelled.add(decision);
                return true;
            });
        }
        // Unblock waiters and dismiss only these requests, without holding the registry lock.
        cancel(cancelled, PendingPlayerDecision.Status.TIMED_OUT);
        return expiry;
    }

    /** A queued expiry from the previous game must never be applied to a new game. */
    public synchronized boolean isCurrent(final Expiry expiry) {
        return expiry.game() == game;
    }

    public synchronized PlayerOperationClock.Snapshot snapshot(final int player) {
        return clock.snapshot(player);
    }

    public void closeGame() {
        final List<Decision<?>> old;
        synchronized (this) {
            ++game;
            clock.clear();
            old = new ArrayList<>(pending.values());
            pending.clear();
            disconnected.clear();
        }
        cancel(old, PendingPlayerDecision.Status.CANCELLED);
    }

    private static void cancel(final List<Decision<?>> decisions, final PendingPlayerDecision.Status reason) {
        for (final Decision<?> decision : decisions) decision.pending().cancel(reason);
    }
}
