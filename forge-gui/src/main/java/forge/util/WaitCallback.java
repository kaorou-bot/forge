package forge.util;

import forge.gui.FThreads;
import forge.gui.GuiRequestScope;

import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

public abstract class WaitCallback<T> implements Consumer<T>, Runnable {
    private final CountDownLatch finished = new CountDownLatch(1);

    private T result;
    private boolean completed;
    private Throwable failure;
    private volatile GuiRequestScope.WaitRegistration requestWait = GuiRequestScope.WaitRegistration.NONE;

    private synchronized void fail(final Throwable error) {
        if (completed) return;
        completed = true;
        failure = error;
        finished.countDown();
    }

    @Override
    public final synchronized void accept(T result0) {
        if (completed) return;
        requestWait.close();
        completed = true;
        result = result0;
        finished.countDown();
    }

    public final T invokeAndWait() {
        FThreads.assertExecutedByEdt(false); //not supported if on UI thread
        final GuiRequestScope scope = GuiRequestScope.current();
        final GuiRequestScope.Registration cancellation = scope == null ? GuiRequestScope.Registration.NONE
                : scope.onCancel(finished::countDown);
        try (cancellation) {
            if (scope != null) scope.checkActive();
            if (scope != null) requestWait = scope.beginWait();
            FThreads.invokeInEdtLater(() -> {
                if (scope != null && scope.isCancelled()) return;
                try { WaitCallback.this.run(); }
                catch (final Throwable error) {
                    fail(error);
                    if (error instanceof Error fatal) throw fatal;
                }
            });
            finished.await();
            if (scope != null) scope.checkActive();
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("UI callback failed", failure);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the UI callback", e);
        } finally {
            requestWait.close();
        }
        return result;
    }
}
