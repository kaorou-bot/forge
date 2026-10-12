package forge.gamemodes.net;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A cancellable choice with an explicit terminal reason, not a fabricated null answer.
 * Integration must handle non-ANSWERED results at a rule-safe decision boundary.
 * This primitive deliberately does not concede a player or synthesize a mandatory choice.
 */
public final class PendingPlayerDecision<T> {
    public enum Status { ANSWERED, TIMED_OUT, CANCELLED }
    public record Result<T>(Status status, T answer) { }

    private final PlayerOperationClock.Request clock;
    private final Consumer<Runnable> uiExecutor;
    private final CompletableFuture<Result<T>> result = new CompletableFuture<>();
    private final AtomicReference<RuntimeException> dismissalFailure = new AtomicReference<>();
    private Runnable dismiss;
    private boolean dismissalClaimed;

    public PendingPlayerDecision(final PlayerOperationClock.Request clock,
                                 final Consumer<Runnable> uiExecutor) {
        this.clock = Objects.requireNonNull(clock);
        this.uiExecutor = Objects.requireNonNull(uiExecutor);
    }

    /** Call on the UI thread after showing this request's own dialog; register even if cancelled. */
    public void shown(final Runnable dismissDialog) {
        final Runnable cleanup;
        synchronized (this) {
            if (dismiss != null) throw new IllegalStateException("A decision can own only one dialog");
            dismiss = Objects.requireNonNull(dismissDialog);
            if (!result.isDone() && !clock.ready()) {
                final PlayerOperationClock.Completion completion = clock.complete();
                result.complete(new Result<>(completion == PlayerOperationClock.Completion.TIMED_OUT
                        ? Status.TIMED_OUT : Status.CANCELLED, null));
            }
            cleanup = claimDismissal();
        }
        dispatch(cleanup);
    }

    public boolean answer(final T answer) {
        final Runnable cleanup;
        final boolean accepted;
        synchronized (this) {
            if (result.isDone()) return false;
            final PlayerOperationClock.Completion completion = clock.complete();
            accepted = completion == PlayerOperationClock.Completion.ACCEPTED;
            final Status status = switch (completion) {
                case ACCEPTED -> Status.ANSWERED;
                case TIMED_OUT -> Status.TIMED_OUT;
                case INVALIDATED -> Status.CANCELLED;
            };
            result.complete(new Result<>(status, accepted ? answer : null));
            cleanup = claimDismissal();
        }
        dispatch(cleanup);
        return accepted;
    }

    public boolean cancel(final Status reason) {
        if (reason == Status.ANSWERED) throw new IllegalArgumentException("Use answer for normal completion");
        Objects.requireNonNull(reason);
        final Runnable cleanup;
        synchronized (this) {
            if (result.isDone()) return false;
            clock.close();
            result.complete(new Result<>(reason, null));
            cleanup = claimDismissal();
        }
        dispatch(cleanup);
        return true;
    }

    public synchronized boolean isPending() {
        return !result.isDone();
    }

    /**
     * UI cleanup is best-effort, independent of completing the game request. The UI adapter
     * should report this failure through its diagnostics. Never retry on the rules/timer thread:
     * an executor may reject work because its UI has already been disposed.
     */
    public Optional<RuntimeException> dismissalFailure() {
        return Optional.ofNullable(dismissalFailure.get());
    }

    public Result<T> await() throws InterruptedException {
        try {
            return result.get();
        } catch (final ExecutionException e) {
            throw new IllegalStateException("Unexpected choice completion failure", e.getCause());
        }
    }

    private Runnable claimDismissal() {
        if (!result.isDone() || dismiss == null || dismissalClaimed) return null;
        dismissalClaimed = true;
        return dismiss;
    }

    private void dispatch(final Runnable cleanup) {
        if (cleanup == null) return;
        try {
            uiExecutor.accept(() -> {
                try {
                    cleanup.run();
                } catch (final RuntimeException e) {
                    dismissalFailure.compareAndSet(null, e);
                }
            });
        } catch (final RuntimeException e) {
            // A stopped UI must not strand other waiters or consume an undelivered expiry.
            dismissalFailure.compareAndSet(null, e);
        }
    }
}
