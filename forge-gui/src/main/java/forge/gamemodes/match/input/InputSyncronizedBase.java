package forge.gamemodes.match.input;

import forge.util.IHasForgeLog;
import forge.gui.FThreads;
import forge.gui.GuiRequestScope;
import forge.player.PlayerControllerHuman;

import java.util.concurrent.CountDownLatch;

public abstract class InputSyncronizedBase extends InputBase implements InputSynchronized, IHasForgeLog {

    private static final long serialVersionUID = 8756177361251703052L;
    private final CountDownLatch cdlDone;
    private final CountDownLatch cleanupDone = new CountDownLatch(1);
    private transient volatile GuiRequestScope scope;
    private transient volatile GuiRequestScope.WaitRegistration requestWait = GuiRequestScope.WaitRegistration.NONE;
    private boolean stopping;
    private transient volatile RuntimeException cleanupFailure;

    @Override
    protected final GuiRequestScope requestScope() { return scope; }

    public InputSyncronizedBase(final PlayerControllerHuman controller) {
        super(controller);
        cdlDone = new CountDownLatch(1);
    }

    @Override
    public void awaitLatchRelease() {
        FThreads.assertExecutedByEdt(false);
        netLog.trace("awaitLatchRelease() starting on {}, thread = {}", this.getClass().getSimpleName(), Thread.currentThread().getName());
        try {
            getController().getGui().awaitInput(cdlDone);
            // stop() can run inside an action. Its latch is not proof the action has returned.
            awaitActions();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting a player action", e);
        }
        netLog.trace("awaitLatchRelease() UNBLOCKED on {}, thread = {}", this.getClass().getSimpleName(), Thread.currentThread().getName());
    }

    @Override
    public final void relaseLatchWhenGameIsOver() {
        cdlDone.countDown();
    }

    public void showAndWait() {
        scope = GuiRequestScope.current();
        final GuiRequestScope.Registration cancellation = scope == null ? GuiRequestScope.Registration.NONE
                : scope.onCancel(() -> {
                    // No rules mutation, onStop(), observer update, or EDT wait on this thread.
                    closeActions();
                    cdlDone.countDown();
                });
        try (cancellation) {
            if (scope != null) scope.checkActive();
            if (scope != null) requestWait = scope.beginWait();
            getController().getInputQueue().setInput(this);
            awaitLatchRelease();
            if (scope != null) scope.checkActive();
            if (cleanupFailure != null) throw cleanupFailure;
        } catch (final GuiRequestScope.Cancelled cancelled) {
            // Also clean up constructor-owned resources when cancelled before setInput().
            closeActions();
            try {
                awaitActions();
                finishInput();
                cleanupDone.await();
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancelled.addSuppressed(interrupted);
            } catch (final RuntimeException cleanup) {
                cancelled.addSuppressed(cleanup);
            }
            if (cleanupFailure != null && !java.util.Arrays.asList(cancelled.getSuppressed()).contains(cleanupFailure)) {
                cancelled.addSuppressed(cleanupFailure);
            }
            throw cancelled;
        } finally {
            requestWait.close();
        }
    }

    @Override
    public final void stop() {
        if (scope != null) {
            requestWait.close();
            if (scope.isCancelled()) {
                closeActions();
                cdlDone.countDown();
                return; // The original waiter owns rules-side cancellation cleanup.
            }
        }
        finishInput();
    }

    private void finishInput() {
        synchronized (this) {
            if (stopping) return;
            stopping = true;
        }
        closeActions();
        netLog.trace("stop() called on {}, latch count before = {}", this.getClass().getSimpleName(), cdlDone.getCount());
        try {
            onStop();
        } catch (final RuntimeException failure) {
            cleanupFailure = failure;
            throw failure;
        } finally {
            try {
                FThreads.invokeInEdtNowOrLater(this::setFinished);
            } finally {
                try { getController().getInputQueue().removeInput(this); }
                finally {
                    cleanupDone.countDown();
                    cdlDone.countDown();
                }
            }
        }
        netLog.trace("stop() done, latch count after = {}", cdlDone.getCount());
    }

    protected void onStop() { }
}
