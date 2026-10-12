package forge.gui;

import forge.util.WaitCallback;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Test(singleThreaded = true)
public class WaitCallbackCancellationTest {
    private static <T> FutureTask<T> waitAsync(final GuiRequestScope scope, final WaitCallback<T> callback) {
        final FutureTask<T> future = new FutureTask<>(() -> {
            final AtomicReference<T> result = new AtomicReference<>();
            GuiRequestScope.bind(scope, () -> result.set(callback.invokeAndWait())).run();
            return result.get();
        });
        final Thread worker = new Thread(future, "request-cancellation-test");
        worker.setDaemon(true);
        worker.start();
        return future;
    }

    public void cancelBeforeUiRunsNeverShowsTheOldDialog() throws Exception {
        try (RequestTestGui gui = new RequestTestGui(); GuiRequestScope scope = new GuiRequestScope()) {
            final AtomicInteger shown = new AtomicInteger();
            final WaitCallback<String> callback = new WaitCallback<>() {
                @Override public void run() { shown.incrementAndGet(); }
            };
            final var waiting = waitAsync(scope, callback);
            final Runnable queued = gui.next();
            scope.close();
            final ExecutionException failure = Assert.expectThrows(ExecutionException.class,
                    () -> waiting.get(2, TimeUnit.SECONDS));
            Assert.assertTrue(failure.getCause() instanceof GuiRequestScope.Cancelled);
            queued.run();
            callback.accept("late");
            Assert.assertEquals(shown.get(), 0);
        }
    }

    public void cancelVisibleWaitClosesOnlyItsOwnUiAndReleasesWait() throws Exception {
        try (RequestTestGui gui = new RequestTestGui(); GuiRequestScope scope = new GuiRequestScope()) {
            final AtomicInteger closed = new AtomicInteger();
            final WaitCallback<String> callback = new WaitCallback<>() {
                @Override public void run() {
                    Assert.assertSame(GuiRequestScope.current(), scope);
                    scope.onCancel(() -> FThreads.invokeInEdtLater(closed::incrementAndGet));
                }
            };
            final var waiting = waitAsync(scope, callback);
            gui.next().run();
            scope.close();
            final ExecutionException failure = Assert.expectThrows(ExecutionException.class,
                    () -> waiting.get(2, TimeUnit.SECONDS));
            Assert.assertTrue(failure.getCause() instanceof GuiRequestScope.Cancelled);
            gui.next().run();
            Assert.assertEquals(closed.get(), 1);
        }
    }

    public void normalNullAnswerAndFirstAnswerArePreserved() throws Exception {
        try (RequestTestGui gui = new RequestTestGui()) {
            final WaitCallback<String> callback = new WaitCallback<>() {
                @Override public void run() { accept(null); accept("duplicate"); }
            };
            final var waiting = waitAsync(null, callback);
            gui.next().run();
            Assert.assertNull(waiting.get(2, TimeUnit.SECONDS));
        }
    }

    public void uiFailureUnblocksInsteadOfHangingForever() throws Exception {
        try (RequestTestGui gui = new RequestTestGui()) {
            final var error = new IllegalStateException("Cannot build UI");
            final WaitCallback<String> callback = new WaitCallback<>() {
                @Override public void run() { throw error; }
            };
            final var waiting = waitAsync(null, callback);
            gui.next().run();
            final ExecutionException failure = Assert.expectThrows(ExecutionException.class,
                    () -> waiting.get(2, TimeUnit.SECONDS));
            Assert.assertSame(failure.getCause(), error);
        }
    }
}
