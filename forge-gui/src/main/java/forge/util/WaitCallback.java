package forge.util;

import forge.gui.FThreads;

import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

public abstract class WaitCallback<T> implements Consumer<T>, Runnable {
    private final CountDownLatch finished = new CountDownLatch(1);

    private T result;

    @Override
    public final void accept(T result0) {
        result = result0;
        finished.countDown();
    }

    public final T invokeAndWait() {
        FThreads.assertExecutedByEdt(false); //not supported if on UI thread
        FThreads.invokeInEdtLater(this);
        try {
            finished.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the UI callback", e);
        }
        return result;
    }
}
