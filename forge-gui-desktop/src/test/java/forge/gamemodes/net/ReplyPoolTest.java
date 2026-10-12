package forge.gamemodes.net;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ReplyPoolTest {
    @Test
    public void targetedFailureIsRetainedThroughCloseAndCannotPoisonAnotherWait() {
        final ReplyPool pool = new ReplyPool();
        final RuntimeException reason = new IllegalStateException("targeted failure");
        pool.initialize(1);
        pool.initialize(2);
        Assert.assertTrue(pool.fail(1, reason));
        Assert.assertFalse(pool.fail(1, new IllegalArgumentException("late failure")));
        pool.complete(1, "late answer");
        pool.complete(2, "unrelated answer");
        pool.close();
        Assert.assertSame(Assert.expectThrows(RuntimeException.class, () -> pool.get(1)), reason);
        Assert.assertEquals(pool.get(2), "unrelated answer");
        Assert.assertNull(pool.get(1));
    }

    @Test
    public void failureCannotReplaceEarlyNullAnswerAndUnknownIdsStayAbsent() {
        final ReplyPool pool = new ReplyPool();
        final RuntimeException reason = new IllegalStateException("late");
        Assert.assertFalse(pool.fail(999, reason));
        Assert.assertNull(pool.get(999));
        pool.initialize(1);
        pool.complete(1, null);
        Assert.assertFalse(pool.fail(1, reason));
        Assert.assertNull(pool.get(1));
        pool.initialize(2);
        pool.complete(2, "early");
        Assert.assertFalse(pool.fail(2, reason));
        Assert.assertEquals(pool.get(2), "early");
    }

    @Test(timeOut = 5_000)
    public void retiredPoolRejectsLateWaitAndReportsOnlyPendingCancellations() {
        ReplyPool pool = new ReplyPool();
        pool.initialize(1);
        pool.initialize(2);
        pool.complete(1, "answered");
        Assert.assertEquals(pool.cancelAll(), java.util.List.of(2));
        Assert.assertEquals(pool.get(1), "answered", "Accepted answer must survive cancellation before get()");
        pool.initialize(3);
        pool.close();
        pool.initialize(4);
        Assert.assertNull(pool.get(4));
        Assert.assertTrue(pool.cancelAll().isEmpty());
    }

    @Test
    public void closingConnectionPreservesAlreadyAcceptedReply() {
        ReplyPool pool = new ReplyPool();
        pool.initialize(1);
        pool.complete(1, "accepted before disconnect");
        pool.close();
        Assert.assertEquals(pool.get(1), "accepted before disconnect");
        Assert.assertNull(pool.get(1));
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void duplicateIdCannotOrphanOriginalWaiter() {
        ReplyPool pool = new ReplyPool();
        pool.initialize(1);
        pool.initialize(1);
    }

    @Test
    public void lateReplyAfterCancellationIsIgnored() {
        ReplyPool pool = new ReplyPool();
        pool.initialize(7);
        pool.cancelAll();
        pool.complete(7, "stale");
        Assert.assertNull(pool.get(7));
        pool.initialize(8);
        pool.complete(8, "current");
        Assert.assertEquals(pool.get(8), "current");
        Assert.assertNull(pool.get(8), "Completed entries must be removed");
    }

    @Test(timeOut = 5_000)
    public void disconnectReleasesWaitingQuery() throws Exception {
        ReplyPool pool = new ReplyPool();
        pool.initialize(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var waiting = executor.submit(() -> pool.get(1));
            pool.cancelAll();
            Assert.assertNull(waiting.get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test(timeOut = 5_000)
    public void informationalQueryHasBoundedWaitAndIgnoresLateReply() {
        ReplyPool pool = new ReplyPool();
        pool.initialize(1);
        Assert.assertNull(pool.get(1, 20));
        pool.complete(1, "late");
        Assert.assertNull(pool.get(1));
    }
}
