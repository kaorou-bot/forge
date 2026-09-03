package forge.util;

import forge.gui.FThreads;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public abstract class WaitRunnable implements Runnable {
    public final void invokeAndWait() {
        FThreads.assertExecutedByEdt(false); //not supported if on UI thread
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        FThreads.invokeInEdtLater(() -> {
            try {
                WaitRunnable.this.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                finished.countDown();
            }
        });
        try {
            finished.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the UI thread", e);
        }

        final Throwable throwable = failure.get();
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        if (throwable != null) {
            throw new IllegalStateException("UI task failed", throwable);
        }
    }
}
