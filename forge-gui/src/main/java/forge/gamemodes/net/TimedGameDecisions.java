package forge.gamemodes.net;

import forge.game.Game;
import forge.game.player.DecisionGameEnded;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.PlayerDecisionFrame;
import forge.game.spellability.SpellAbility;
import forge.gui.GuiRequestScope;
import forge.player.PlayerControllerHuman;
import forge.util.collect.FCollection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Per-game host authority. Scheduler callbacks only finish waits; they never change Game. */
public final class TimedGameDecisions implements AutoCloseable {
    public enum Purpose { CHOICE, PRIORITY, DECLARATION, TRIGGER, STACK_ORDER, NOTICE }
    public record Display(int player, long remainingMillis, boolean running) { }
    private static final Map<Game, TimedGameDecisions> GAMES = new IdentityHashMap<>();
    private static final java.util.concurrent.ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "player-operation-clock");
        t.setDaemon(true);
        return t;
    });

    private final Game game;
    private final PlayerOperationClock clock = new PlayerOperationClock();
    private final Set<Activity> activities = new HashSet<>();
    private final Set<Integer> disconnected = new HashSet<>();
    private final Map<Integer, Player> players = new HashMap<>();
    private final BiConsumer<PlayerControllerHuman, GuiRequestScope> inputReady;
    private final Consumer<List<Display>> display;
    private final ScheduledFuture<?> timer;
    private volatile boolean closed;
    private List<Display> lastDisplay = List.of();

    private TimedGameDecisions(final Game game, final long millis,
                               final BiConsumer<PlayerControllerHuman, GuiRequestScope> inputReady,
                               final Consumer<List<Display>> display) {
        this.game = game;
        this.inputReady = inputReady;
        this.display = display;
        for (final Player p : game.getPlayers()) players.put(p.getId(), p);
        clock.reset(players.keySet(), millis);
        timer = TIMER.scheduleAtFixedRate(this::tick, 100, 100, TimeUnit.MILLISECONDS);
    }

    public static TimedGameDecisions start(final Game game, final long millis,
                                          final BiConsumer<PlayerControllerHuman, GuiRequestScope> inputReady,
                                          final Consumer<List<Display>> display) {
        synchronized (GAMES) {
            if (GAMES.containsKey(game)) throw new IllegalStateException("This game already has an operation clock");
            final TimedGameDecisions session = new TimedGameDecisions(game, millis, inputReady, display);
            GAMES.put(game, session);
            return session;
        }
    }

    public static TimedGameDecisions forGame(final Game game) {
        synchronized (GAMES) { return GAMES.get(game); }
    }

    public void connected(final int player, final boolean value) {
        final List<Activity> pending;
        synchronized (this) {
            if (closed || !players.containsKey(player)) return;
            if (value) disconnected.remove(player); else disconnected.add(player);
            pending = activities.stream().filter(a -> a.player.getId() == player).toList();
        }
        if (!value) for (final Activity a : pending) a.continueDisconnected();
    }

    public static <T> T choose(final PlayerControllerHuman controller, final SpellAbility source,
                               final Purpose purpose, final Supplier<T> local,
                               final Function<PlayerController, T> delegate) {
        final TimedGameDecisions session = forGame(controller.getGame());
        if (session == null) return local.get();
        return session.decide(controller, source, purpose, local, delegate);
    }

    public static boolean pay(final PlayerControllerHuman controller, final SpellAbility source,
                              final Supplier<Boolean> local, final Function<PlayerController, Boolean> delegate) {
        if (forGame(controller.getGame()) == null) return local.get();
        final PlayerDecisionFrame frame = PlayerDecisionFrame.enter(PlayerDecisionFrame.Kind.COST, controller.getPlayer(), source);
        try (frame) {
            return choose(controller, source, Purpose.CHOICE, local, delegate);
        } catch (final PlayerDecisionFrame.Aborted aborted) {
            if (!frame.owns(aborted)) throw aborted;
            return false;
        }
    }

    private <T> T decide(final PlayerControllerHuman controller, final SpellAbility source,
                         final Purpose purpose, final Supplier<T> local,
                         final Function<PlayerController, T> delegate) {
        final Player actual = controller.getOperationClockPlayer();
        final GuiRequestScope ambient = GuiRequestScope.current();
        final Activity parent = ambient != null && ambient.activity() instanceof Activity a && a.session() == this ? a : null;
        final Activity activity = new Activity(controller, actual);
        synchronized (this) {
            if (closed) throw new IllegalStateException("Operation clock already closed");
            activities.add(activity);
        }
        final GuiRequestScope.Registration parentCancellation = parent == null ? GuiRequestScope.Registration.NONE
                : parent.scope.onCancel(() -> activity.scope.cancel(parent.scope.cancellation().reason()));
        if (parent != null) parent.pause();
        try (parentCancellation) {
            if (!actual.isInGame() || clock.snapshot(actual.getId()).expired()) {
                if (parent != null && parent.player == actual) {
                    parent.scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
                    throw parent.scope.cancellation();
                }
                return continueAfterDeparture(controller, source, purpose, delegate);
            }
            final java.util.concurrent.atomic.AtomicReference<T> answer = new java.util.concurrent.atomic.AtomicReference<>();
            try {
                activity.scope.checkActive();
                GuiRequestScope.bind(activity.scope, () -> answer.set(local.get())).run();
                activity.answered();
                activity.scope.checkActive();
                return answer.get();
            } catch (final GuiRequestScope.Cancelled cancelled) {
                if (cancelled.reason() != GuiRequestScope.Reason.TIMED_OUT) {
                    if (game.isGameOver()) throw new DecisionGameEnded(game);
                    throw cancelled;
                }
                // UI/mana actions run on another stack while their parent rules input waits.
                // Unwind those actions first; only the outer waiter performs the departure.
                if (parent != null && parent.player == actual) {
                    parent.scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
                    throw parent.scope.cancellation();
                }
                return continueAfterDeparture(controller, source, purpose, delegate);
            }
        } finally {
            activity.answered();
            activity.scope.close();
            synchronized (this) { activities.remove(activity); }
            if (parent != null) parent.resume();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T continueAfterDeparture(final PlayerControllerHuman original, final SpellAbility source,
                                        final Purpose purpose, final Function<PlayerController, T> delegate) {
        final Player departed = original.getOperationClockPlayer();
        // We are on the thread whose synchronous choice was released. Input waits drain
        // their admitted actions first; timer/network threads never execute this method.
        GuiRequestScope.runWithoutContext(() -> {
            // Legacy chooser bodies release temporary visibility only on normal return.
            // Do this at the outer departure boundary, after admitted input actions drain,
            // never from the timer or a nested action while its parent still owns the cards.
            original.endTempShowCards();
            if (departed.isInGame()) {
                departed.concede();
                game.getAction().checkGameOverCondition();
            }
        });
        if (game.isGameOver()) throw new DecisionGameEnded(game);
        final PlayerDecisionFrame frame = PlayerDecisionFrame.current();
        if (frame != null && frame.isAbandoned()) frame.abort();
        // An acknowledgement is not a rules choice; nobody must acknowledge for a departed player.
        if (purpose == Purpose.NOTICE) return null;

        // A mind-control effect ended with its controller's departure; ask the restored controller.
        if (original.getPlayer().isInGame() && original.getPlayer().getController() != original) {
            return outsideScope(() -> delegate.apply(original.getPlayer().getController()));
        }
        if (!original.getPlayer().isInGame()) {
            if (purpose == Purpose.PRIORITY || purpose == Purpose.DECLARATION) return null;
            if (purpose == Purpose.TRIGGER) return (T) Boolean.FALSE;
            if (purpose == Purpose.STACK_ORDER) return (T) List.of();
        }

        final SpellAbility object = source != null ? source : frame == null ? null : frame.ability();
        final Player replacement = outsideScope(() -> replacementFor(departed, object));
        return outsideScope(() -> delegate.apply(replacement.getController()));
    }

    private Player replacementFor(final Player departed, final SpellAbility object) {
        if (object == null) return game.getNextPlayerAfter(departed); // CR 800.4h.
        Player owner = object.getActivatingPlayer();
        final var instance = game.getStack().getInstanceMatchingSpellAbilityID(object.getRootAbility());
        if (instance != null) owner = instance.getActivatingPlayer();
        if (owner == null || !owner.isInGame()) {
            final PlayerDecisionFrame frame = PlayerDecisionFrame.current();
            if (frame != null && frame.isAbandoned()) frame.abort();
            throw new IllegalStateException("A removed object's decision has no enclosing cancellation boundary");
        }
        final FCollection<Player> eligible = new FCollection<>(game.getPlayers());
        if (departed.isOpponentOf(owner)) {
            final FCollection<Player> opponents = new FCollection<>();
            for (final Player p : eligible) if (p.isOpponentOf(owner)) opponents.add(p);
            if (!opponents.isEmpty()) { eligible.clear(); eligible.addAll(opponents); }
        }
        if (eligible.size() == 1) return eligible.getFirst();
        // CR 800.4g: the object's controller designates the replacement; no random/AI answer.
        final Player chosen = owner.getController().chooseSingleEntityForEffect(eligible, object,
                forge.util.Localizer.getInstance().getMessage("lblClockChooseReplacement"), false, null);
        if (chosen == null || !eligible.contains(chosen) || !chosen.isInGame()) {
            throw new IllegalStateException("Invalid replacement decision maker");
        }
        return chosen;
    }

    private static <T> T outsideScope(final Supplier<T> body) {
        final java.util.concurrent.atomic.AtomicReference<T> value = new java.util.concurrent.atomic.AtomicReference<>();
        GuiRequestScope.runWithoutContext(() -> value.set(body.get()));
        return value.get();
    }

    private void tick() {
        try {
            final List<Activity> expired;
            final List<Display> state = new ArrayList<>();
            synchronized (this) {
                if (closed) return;
                final List<Integer> lost = clock.drainExpiredPlayers();
                expired = activities.stream().filter(a -> lost.contains(a.player.getId())).toList();
                for (final int id : players.keySet()) {
                    final var snapshot = clock.snapshot(id);
                    state.add(new Display(id, ((snapshot.remainingMillis() + 999) / 1000) * 1000, snapshot.running()));
                }
            }
            for (final Activity a : expired) a.scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            if (!closed && !state.equals(lastDisplay)) {
                lastDisplay = List.copyOf(state);
                display.accept(lastDisplay);
            }
        } catch (final RuntimeException failure) {
            forge.util.IHasForgeLog.netLog.error(failure, "Operation clock tick failed");
        }
    }

    @Override public void close() {
        final List<Activity> old;
        synchronized (this) {
            if (closed) return;
            closed = true;
            timer.cancel(false);
            old = new ArrayList<>(activities);
            activities.clear();
        }
        for (final Activity a : old) a.scope.cancel(GuiRequestScope.Reason.GAME_CLOSED);
        synchronized (GAMES) { GAMES.remove(game, this); }
        // All requests are invalidated through their scopes. Keep the tiny accounting map
        // until this closed session is collected, so late ready/answer callbacks are harmless.
    }

    public final class Activity implements GuiRequestScope.Activity {
        private final PlayerControllerHuman controller;
        private final Player player;
        private final GuiRequestScope scope = new GuiRequestScope(this);
        private final List<WaitState> waits = new ArrayList<>();
        private int paused;
        private int processingDepth;
        private volatile boolean deadlineExpired;

        private Activity(final PlayerControllerHuman controller, final Player player) {
            this.controller = controller;
            this.player = player;
        }
        private TimedGameDecisions session() { return TimedGameDecisions.this; }

        @Override public GuiRequestScope.WaitRegistration beginWait() {
            final WaitState wait;
            final boolean offline;
            synchronized (TimedGameDecisions.this) { offline = disconnected.contains(player.getId()); }
            synchronized (this) {
                wait = new WaitState(processingDepth);
                wait.shown = offline;
                waits.add(wait);
                refresh();
            }
            cancelExpired();
            return wait;
        }

        @Override public void inputReady() { inputReady.accept(controller, scope); }

        @Override public void ready() { captureReadiness().run(); }

        @Override public synchronized Runnable captureReadiness() {
            return waits.isEmpty() ? () -> { } : waits.get(waits.size() - 1)::ready;
        }

        @Override public void answered() {
            synchronized (this) {
                for (final WaitState wait : waits) { stopCharging(wait); wait.done = true; }
                waits.clear();
            }
            cancelExpired();
        }

        @Override public void processingStarted() {
            synchronized (this) { processingDepth++; refresh(); }
            cancelExpired();
        }

        @Override public void processingFinished() {
            synchronized (this) {
                if (--processingDepth < 0) throw new IllegalStateException("Unbalanced input processing");
                refresh();
            }
            cancelExpired();
        }

        private void pause() {
            synchronized (this) { paused++; refresh(); }
            cancelExpired();
        }

        private void resume() {
            synchronized (this) {
                if (--paused < 0) throw new IllegalStateException("Unbalanced decision pause");
                refresh();
            }
            cancelExpired();
        }

        /** Caller owns this Activity's monitor. Never cancel a scope while holding it. */
        private void refresh() {
            final WaitState top = waits.isEmpty() ? null : waits.get(waits.size() - 1);
            final boolean chargeable = !closed && !scope.isCancelled() && top != null
                    && paused == 0 && processingDepth == top.processingDepth && top.shown;
            for (final WaitState wait : waits) {
                if (wait != top || !chargeable) stopCharging(wait);
            }
            if (chargeable && top.request == null) {
                top.request = clock.begin(player.getId());
                if (!top.request.ready() && clock.snapshot(player.getId()).expired()) deadlineExpired = true;
            }
        }

        private void stopCharging(final WaitState wait) {
            if (wait.request == null) return;
            if (wait.request.complete() == PlayerOperationClock.Completion.TIMED_OUT) deadlineExpired = true;
            wait.request = null;
        }

        private void cancelExpired() {
            if (deadlineExpired) scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
        }

        private void continueDisconnected() { ready(); }

        private final class WaitState implements GuiRequestScope.WaitRegistration {
            private final int processingDepth;
            private PlayerOperationClock.Request request;
            private boolean shown;
            private boolean done;
            private WaitState(final int processingDepth) { this.processingDepth = processingDepth; }

            @Override public void ready() {
                synchronized (Activity.this) {
                    if (done) return;
                    shown = true;
                    refresh();
                }
                cancelExpired();
            }

            @Override public void close() {
                synchronized (Activity.this) {
                    if (done) return;
                    stopCharging(this);
                    done = true;
                    waits.remove(this);
                    refresh();
                }
                cancelExpired();
            }
        }
    }
}
