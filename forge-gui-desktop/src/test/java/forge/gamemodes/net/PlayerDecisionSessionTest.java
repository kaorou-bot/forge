package forge.gamemodes.net;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerDecisionSessionTest {
    @DataProvider(name = "cleanupFailures")
    public Object[][] cleanupFailures() {
        return new Object[][] {
                {"tick", true}, {"start", true}, {"close", true},
                {"tick", false}, {"start", false}, {"close", false}
        };
    }

    @Test(dataProvider = "cleanupFailures", timeOut = 5000)
    public void cleanupFailureCannotStrandWaitersOrLoseExpiry(final String operation,
                                                            final boolean rejectSubmission) throws Exception {
        final AtomicLong now = new AtomicLong();
        final AtomicInteger dispatched = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final PlayerDecisionSession session = new PlayerDecisionSession(now::get, work -> {
            if (dispatched.incrementAndGet() == 1 && rejectSubmission) {
                throw new java.util.concurrent.RejectedExecutionException("UI disposed");
            }
            work.run();
        });
        final long game = session.startGame(List.of(1, 2), 100);
        final var first = session.begin(1);
        final var second = session.begin(1);
        final var survivor = session.begin(2);
        final Runnable dismiss = () -> {
            if (closed.incrementAndGet() == 1 && !rejectSubmission) {
                throw new IllegalStateException("Disposed dialog");
            }
        };
        first.pending().shown(dismiss);
        second.pending().shown(dismiss);
        final PendingPlayerDecision.Status expected;
        if ("tick".equals(operation)) {
            now.set(TimeUnit.MILLISECONDS.toNanos(100));
            final var expiry = session.tick();
            Assert.assertEquals(expiry.game(), game);
            Assert.assertEquals(expiry.players(), List.of(1));
            Assert.assertTrue(session.tick().players().isEmpty());
            Assert.assertTrue(survivor.pending().isPending());
            expected = PendingPlayerDecision.Status.TIMED_OUT;
        } else {
            if ("start".equals(operation)) session.startGame(List.of(1, 2), 200);
            else session.closeGame();
            expected = PendingPlayerDecision.Status.CANCELLED;
            Assert.assertEquals(survivor.pending().await().status(), expected);
        }
        Assert.assertEquals(first.pending().await().status(), expected);
        Assert.assertEquals(second.pending().await().status(), expected);
        Assert.assertEquals(dispatched.get(), 2);
        Assert.assertEquals(closed.get(), rejectSubmission ? 1 : 2);
        Assert.assertNotEquals(first.pending().dismissalFailure().isPresent(),
                second.pending().dismissalFailure().isPresent());
        Assert.assertFalse(session.answer(game, 1, first.id(), "late"));
        session.closeGame();
        Assert.assertEquals(survivor.pending().await().status(), PendingPlayerDecision.Status.CANCELLED);
    }

    @Test
    public void timeoutCancelsOnlyExpiredPlayersAndReportsLossOnce() throws Exception {
        final AtomicLong now = new AtomicLong();
        final PlayerDecisionSession session = new PlayerDecisionSession(now::get, Runnable::run);
        final long game = session.startGame(List.of(1, 2, 3), 100);
        final var first = session.<String>begin(1);
        final var second = session.<String>begin(2);
        final var third = session.<String>begin(3);
        final AtomicInteger closed = new AtomicInteger();
        first.pending().shown(closed::incrementAndGet);
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
        second.pending().shown(() -> { });
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
        final var expiry = session.tick();
        Assert.assertEquals(expiry.game(), game);
        Assert.assertEquals(expiry.players(), List.of(1));
        Assert.assertEquals(first.pending().await().status(), PendingPlayerDecision.Status.TIMED_OUT);
        Assert.assertEquals(closed.get(), 1);
        Assert.assertFalse(session.answer(game, 1, first.id(), "late"));
        Assert.assertTrue(second.pending().isPending());
        Assert.assertTrue(third.pending().isPending());
        Assert.assertTrue(session.tick().players().isEmpty());
        Assert.assertTrue(session.answer(game, 2, second.id(), "valid"));
        Assert.assertEquals(second.pending().await().answer(), "valid");
        session.closeGame();
        Assert.assertEquals(third.pending().await().status(), PendingPlayerDecision.Status.CANCELLED);
    }

    @Test
    public void disconnectedRequestsChargeAndReconnectDoesNotReset() throws Exception {
        final AtomicLong now = new AtomicLong();
        final PlayerDecisionSession session = new PlayerDecisionSession(now::get, Runnable::run);
        final long game = session.startGame(List.of(1, 2), 100);
        session.setConnected(1, false);
        final var request = session.<Integer>begin(1);
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(75));
        session.setConnected(1, true);
        Assert.assertEquals(session.snapshot(1).remainingMillis(), 25L);
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(25));
        Assert.assertFalse(session.answer(game, 1, request.id(), 42));
        Assert.assertEquals(request.pending().await().status(), PendingPlayerDecision.Status.TIMED_OUT);
        Assert.assertEquals(session.tick().players(), List.of(1));
        final var later = session.begin(1);
        Assert.assertEquals(later.pending().await().status(), PendingPlayerDecision.Status.TIMED_OUT);
        session.closeGame();
    }

    @Test
    public void wrongOwnerAndOldGameCannotAnswerOrApplyLoss() throws Exception {
        final AtomicLong now = new AtomicLong();
        final PlayerDecisionSession session = new PlayerDecisionSession(now::get, Runnable::run);
        final long oldGame = session.startGame(List.of(1, 2), 100);
        final var old = session.<String>begin(1);
        old.pending().shown(() -> { });
        Assert.assertFalse(session.answer(oldGame, 2, old.id(), "forged"));
        Assert.assertTrue(old.pending().isPending());
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        final var expiry = session.tick();
        final long newGame = session.startGame(List.of(1, 2), 200);
        final var current = session.<String>begin(1);
        Assert.assertFalse(session.isCurrent(expiry));
        Assert.assertFalse(session.answer(oldGame, 1, old.id(), "stale"));
        Assert.assertFalse(session.answer(oldGame, 1, current.id(), "wrong generation"));
        Assert.assertEquals(session.snapshot(1).remainingMillis(), 200L);
        Assert.assertTrue(session.answer(newGame, 1, current.id(), "new"));
        session.closeGame();
    }

    @Test
    public void startingNextGameUnblocksPendingChoicesAndClosesLateUi() throws Exception {
        final PlayerDecisionSession session = new PlayerDecisionSession(Runnable::run);
        session.startGame(List.of(1), 60_000);
        final var old = session.begin(1);
        session.startGame(List.of(1), 60_000);
        Assert.assertEquals(old.pending().await().status(), PendingPlayerDecision.Status.CANCELLED);
        final AtomicInteger closed = new AtomicInteger();
        old.pending().shown(closed::incrementAndGet);
        Assert.assertEquals(closed.get(), 1);
        Assert.assertFalse(session.snapshot(1).running());
        session.closeGame();
    }
}
