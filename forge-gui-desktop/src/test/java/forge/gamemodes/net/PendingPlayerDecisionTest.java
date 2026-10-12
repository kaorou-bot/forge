package forge.gamemodes.net;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PendingPlayerDecisionTest {
    @Test
    public void rejectedCleanupDoesNotChangeAcceptedAnswer() throws Exception {
        final PlayerOperationClock clock = new PlayerOperationClock();
        clock.reset(List.of(1), 60_000);
        final var rejection = new java.util.concurrent.RejectedExecutionException("UI disposed");
        final PendingPlayerDecision<Integer> request = new PendingPlayerDecision<>(clock.begin(1), work -> {
            throw rejection;
        });
        final AtomicInteger closed = new AtomicInteger();
        request.shown(closed::incrementAndGet);
        Assert.assertTrue(request.answer(42));
        Assert.assertEquals(request.await().answer(), Integer.valueOf(42));
        Assert.assertEquals(request.await().status(), PendingPlayerDecision.Status.ANSWERED);
        Assert.assertSame(request.dismissalFailure().orElseThrow(), rejection);
        Assert.assertEquals(closed.get(), 0, "Never execute rejected UI work on the caller's thread");
        Assert.assertFalse(request.cancel(PendingPlayerDecision.Status.CANCELLED));
    }

    @Test
    public void lateAsynchronousCleanupFailureIsRecordedWithoutChangingCancellation() throws Exception {
        final PlayerOperationClock clock = new PlayerOperationClock();
        clock.reset(List.of(1), 60_000);
        final List<Runnable> queue = new java.util.ArrayList<>();
        final PendingPlayerDecision<Integer> request = new PendingPlayerDecision<>(clock.begin(1), queue::add);
        request.cancel(PendingPlayerDecision.Status.CANCELLED);
        final var failure = new IllegalStateException("Disposed dialog");
        request.shown(() -> { throw failure; });
        Assert.assertTrue(request.dismissalFailure().isEmpty());
        Assert.assertEquals(queue.size(), 1);
        queue.get(0).run();
        Assert.assertSame(request.dismissalFailure().orElseThrow(), failure);
        Assert.assertEquals(request.await().status(), PendingPlayerDecision.Status.CANCELLED);
        Assert.assertFalse(request.answer(42));
    }

    @Test
    public void competingAnswerAndCancellationHaveOneTerminalResult() throws Exception {
        final var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 40; i++) {
                final PlayerOperationClock clock = new PlayerOperationClock();
                clock.reset(List.of(1), 60_000);
                final PendingPlayerDecision<Integer> request = new PendingPlayerDecision<>(clock.begin(1), Runnable::run);
                final AtomicInteger closed = new AtomicInteger();
                request.shown(closed::incrementAndGet);
                final var start = new java.util.concurrent.CountDownLatch(1);
                final var answer = workers.submit(() -> { start.await(); return request.answer(42); });
                final var cancel = workers.submit(() -> {
                    start.await();
                    return request.cancel(PendingPlayerDecision.Status.CANCELLED);
                });
                start.countDown();
                final boolean answered = answer.get(2, TimeUnit.SECONDS);
                final boolean cancelled = cancel.get(2, TimeUnit.SECONDS);
                Assert.assertNotEquals(answered, cancelled);
                Assert.assertEquals(request.await().status(), answered ? PendingPlayerDecision.Status.ANSWERED
                        : PendingPlayerDecision.Status.CANCELLED);
                Assert.assertEquals(closed.get(), 1);
                Assert.assertFalse(clock.snapshot(1).running());
            }
        } finally {
            workers.shutdownNow();
            Assert.assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void validNullIsDifferentFromCancellation() throws Exception {
        final PlayerOperationClock clock = new PlayerOperationClock();
        clock.reset(List.of(1), 60_000);
        final PendingPlayerDecision<String> answered = new PendingPlayerDecision<>(clock.begin(1), Runnable::run);
        answered.shown(() -> { });
        Assert.assertTrue(answered.answer(null));
        Assert.assertEquals(answered.await().status(), PendingPlayerDecision.Status.ANSWERED);
        final PendingPlayerDecision<String> cancelled = new PendingPlayerDecision<>(clock.begin(1), Runnable::run);
        cancelled.cancel(PendingPlayerDecision.Status.CANCELLED);
        Assert.assertEquals(cancelled.await().status(), PendingPlayerDecision.Status.CANCELLED);
    }

    @Test
    public void expiryReleasesWaitAndDismissesOnlyItsOwnDialogOnce() throws Exception {
        final AtomicLong time = new AtomicLong();
        final PlayerOperationClock clock = new PlayerOperationClock(time::get);
        clock.reset(List.of(1, 2), 100);
        final AtomicInteger dismissed = new AtomicInteger();
        final PendingPlayerDecision<Integer> expired = new PendingPlayerDecision<>(clock.begin(1), Runnable::run);
        final PendingPlayerDecision<Integer> other = new PendingPlayerDecision<>(clock.begin(2), Runnable::run);
        expired.shown(dismissed::incrementAndGet);
        final FutureTask<PendingPlayerDecision.Result<Integer>> waiting = new FutureTask<>(expired::await);
        final Thread thread = new Thread(waiting, "decision-test");
        thread.setDaemon(true);
        thread.start();
        time.set(TimeUnit.MILLISECONDS.toNanos(100));
        Assert.assertEquals(clock.drainExpiredPlayers(), List.of(1));
        expired.cancel(PendingPlayerDecision.Status.TIMED_OUT);
        Assert.assertEquals(waiting.get(2, TimeUnit.SECONDS).status(), PendingPlayerDecision.Status.TIMED_OUT);
        Assert.assertFalse(expired.answer(42));
        Assert.assertFalse(expired.cancel(PendingPlayerDecision.Status.TIMED_OUT));
        Assert.assertEquals(dismissed.get(), 1);
        Assert.assertTrue(other.isPending());
        other.cancel(PendingPlayerDecision.Status.CANCELLED);
        thread.join(2000);
    }

    @Test
    public void cancellationBeforeUiCreationAlsoClosesLateDialog() throws Exception {
        final PlayerOperationClock clock = new PlayerOperationClock();
        clock.reset(List.of(1), 60_000);
        final AtomicInteger dismissed = new AtomicInteger();
        final PendingPlayerDecision<String> request = new PendingPlayerDecision<>(clock.begin(1), Runnable::run);
        request.cancel(PendingPlayerDecision.Status.CANCELLED);
        request.shown(dismissed::incrementAndGet);
        Assert.assertEquals(dismissed.get(), 1);
        Assert.assertFalse(clock.snapshot(1).running());
        Assert.assertEquals(request.await().status(), PendingPlayerDecision.Status.CANCELLED);
    }

    @Test
    public void deadlineIsCheckedEvenBeforeSchedulerTick() throws Exception {
        final AtomicLong time = new AtomicLong();
        final PlayerOperationClock clock = new PlayerOperationClock(time::get);
        clock.reset(List.of(1), 100);
        final PendingPlayerDecision<Integer> request = new PendingPlayerDecision<>(clock.begin(1), Runnable::run);
        request.shown(() -> { });
        time.set(TimeUnit.MILLISECONDS.toNanos(100));
        Assert.assertFalse(request.answer(7));
        Assert.assertEquals(request.await().status(), PendingPlayerDecision.Status.TIMED_OUT);
    }

    @Test
    public void oldGameAnswerIsCancelledNotANewGameTimeout() throws Exception {
        final PlayerOperationClock clock = new PlayerOperationClock();
        clock.reset(List.of(1), 60_000);
        final PendingPlayerDecision<Integer> request = new PendingPlayerDecision<>(clock.begin(1), Runnable::run);
        clock.reset(List.of(1), 60_000);
        Assert.assertFalse(request.answer(7));
        Assert.assertEquals(request.await().status(), PendingPlayerDecision.Status.CANCELLED);
        Assert.assertFalse(clock.snapshot(1).expired());
    }
}
