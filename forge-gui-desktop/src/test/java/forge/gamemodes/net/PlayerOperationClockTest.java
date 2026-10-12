package forge.gamemodes.net;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public class PlayerOperationClockTest {
    private static final class Fixture {
        private final AtomicLong time = new AtomicLong();
        private final PlayerOperationClock clock = new PlayerOperationClock(time::get);
        private Fixture() { clock.reset(List.of(1, 2), 1000); }
        private void advance(final long millis) { time.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)); }
    }

    @Test
    public void countsOnlyReadyWaitsAndRetainsCumulativeBalance() {
        final Fixture f = new Fixture();
        final var first = f.clock.begin(1);
        f.advance(700);
        Assert.assertEquals(f.clock.snapshot(1).remainingMillis(), 1000L);
        Assert.assertTrue(first.ready());
        f.advance(250);
        Assert.assertEquals(first.complete(), PlayerOperationClock.Completion.ACCEPTED);
        f.advance(500);
        final var next = f.clock.begin(1);
        next.ready();
        f.advance(100);
        next.close();
        Assert.assertEquals(f.clock.snapshot(1).remainingMillis(), 650L);
        Assert.assertEquals(f.clock.snapshot(2).remainingMillis(), 1000L);
    }

    @Test
    public void nestedWaitsAndRepeatedAcknowledgementsDoNotDoubleCharge() {
        final Fixture f = new Fixture();
        final var outer = f.clock.begin(1);
        outer.ready();
        outer.ready();
        f.advance(100);
        final var inner = f.clock.begin(1);
        inner.ready();
        f.advance(200);
        inner.close();
        inner.close();
        f.advance(100);
        outer.close();
        Assert.assertEquals(f.clock.snapshot(1).remainingMillis(), 600L);
        Assert.assertFalse(f.clock.snapshot(1).running());
    }

    @DataProvider
    public Object[][] counts() { return new Object[][] {{2}, {3}, {4}, {8}}; }

    @Test(dataProvider = "counts")
    public void budgetsAreIndependentForAllRoomSizes(final int count) {
        final Fixture f = new Fixture();
        f.clock.reset(IntStream.range(0, count).boxed().toList(), 1000);
        final var requests = IntStream.range(0, count).mapToObj(f.clock::begin).toList();
        requests.get(0).ready();
        f.advance(400);
        for (int i = 1; i < count; i++) Assert.assertEquals(f.clock.snapshot(i).remainingMillis(), 1000L);
        requests.get(1).ready();
        f.advance(600);
        Assert.assertEquals(f.clock.drainExpiredPlayers(), List.of(0));
        Assert.assertTrue(f.clock.drainExpiredPlayers().isEmpty());
        Assert.assertEquals(f.clock.snapshot(1).remainingMillis(), 400L);
        for (final var request : requests) request.close();
    }

    @Test
    public void disconnectionDoesNotResetOrPauseWaitingPlayer() {
        final Fixture f = new Fixture();
        final var request = f.clock.begin(1);
        f.clock.continueWhileDisconnected(1);
        f.advance(600);
        // The ready acknowledgement after reconnect is a duplicate, not a new allowance.
        request.ready();
        f.advance(400);
        Assert.assertEquals(request.complete(), PlayerOperationClock.Completion.TIMED_OUT);
        Assert.assertEquals(f.clock.drainExpiredPlayers(), List.of(1));
        Assert.assertFalse(request.ready());
    }

    @Test
    public void deadlineWinsAndExpiredAnswersAreNeverAccepted() {
        final Fixture f = new Fixture();
        final var request = f.clock.begin(1);
        request.ready();
        f.advance(1000);
        Assert.assertEquals(request.complete(), PlayerOperationClock.Completion.TIMED_OUT);
        Assert.assertEquals(request.complete(), PlayerOperationClock.Completion.INVALIDATED);
        Assert.assertEquals(f.clock.drainExpiredPlayers(), List.of(1));
    }

    @Test
    public void newGameAndTeardownInvalidateOldHandles() {
        final Fixture f = new Fixture();
        final var stale = f.clock.begin(1);
        stale.ready();
        f.advance(600);
        f.clock.reset(List.of(1, 2), 2000);
        Assert.assertEquals(stale.complete(), PlayerOperationClock.Completion.INVALIDATED);
        Assert.assertEquals(f.clock.snapshot(1).remainingMillis(), 2000L);
        final var current = f.clock.begin(1);
        current.ready();
        f.clock.clear();
        Assert.assertFalse(current.ready());
        Assert.assertEquals(current.complete(), PlayerOperationClock.Completion.INVALIDATED);
        Assert.assertTrue(f.clock.drainExpiredPlayers().isEmpty());
    }

    @Test
    public void subMillisecondWaitsAccumulateWithoutRoundingLoss() {
        final Fixture f = new Fixture();
        for (int i = 0; i < 10; i++) {
            final var request = f.clock.begin(1);
            request.ready();
            f.time.addAndGet(100_000);
            request.close();
        }
        Assert.assertEquals(f.clock.snapshot(1).remainingMillis(), 999L);
    }

    @Test
    public void invalidResetDoesNotDestroyCurrentGame() {
        final Fixture f = new Fixture();
        Assert.expectThrows(IllegalArgumentException.class, () -> f.clock.reset(List.of(1, 1), 100));
        Assert.expectThrows(IllegalArgumentException.class, () -> f.clock.reset(List.of(1), 0));
        Assert.expectThrows(IllegalArgumentException.class, () -> f.clock.reset(List.of(1), 86_400_001));
        Assert.expectThrows(IllegalArgumentException.class, () -> f.clock.begin(99));
        Assert.assertEquals(f.clock.snapshot(1).remainingMillis(), 1000L);
    }
}
