package forge.gamemodes.match.input;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@Test(timeOut = 10000)
public class InputActionGateTest {
    public void closeRejectsNewActionsButWaiterDrainsEveryAdmittedAction() throws Exception {
        final InputActionGate gate = new InputActionGate();
        Assert.assertTrue(gate.enter());
        Assert.assertTrue(gate.enter());
        final FutureTask<Void> waiting = new FutureTask<>(() -> { gate.awaitIdle(); return null; });
        final Thread waiter = new Thread(waiting, "input-gate-drain-test");
        waiter.setDaemon(true);
        waiter.start();
        try {
            awaitGateWait(waiter);
            gate.close();
            gate.close();
            Assert.assertFalse(gate.enter());
            gate.leave();
            awaitGateWait(waiter);
            Assert.assertFalse(waiting.isDone(), "One nested action is still active");
            gate.leave();
            waiting.get(3, TimeUnit.SECONDS);
            Assert.assertFalse(gate.enter(), "Draining must not reopen a closed gate");
        } finally {
            waiting.cancel(true);
            waiter.join(1000);
        }
    }

    public void actionMayWaitForAnotherThreadToCloseGate() throws Exception {
        final InputActionGate gate = new InputActionGate();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final FutureTask<Void> action = new FutureTask<>(() -> {
            Assert.assertTrue(gate.enter());
            try {
                entered.countDown();
                Assert.assertTrue(closed.await(3, TimeUnit.SECONDS), "close blocked behind running action");
            } finally { gate.leave(); }
            return null;
        });
        final Thread actor = new Thread(action, "input-gate-action-test");
        actor.setDaemon(true);
        actor.start();
        try {
            Assert.assertTrue(entered.await(3, TimeUnit.SECONDS));
            gate.close();
            closed.countDown();
            action.get(3, TimeUnit.SECONDS);
            gate.awaitIdle();
        } finally {
            closed.countDown();
            action.cancel(true);
            actor.join(1000);
        }
    }

    public void interruptedDrainDoesNotConsumeTheActiveAction() throws Exception {
        final InputActionGate gate = new InputActionGate();
        Assert.assertTrue(gate.enter());
        final FutureTask<Boolean> waiting = new FutureTask<>(() -> {
            try { gate.awaitIdle(); return false; }
            catch (final InterruptedException expected) { return true; }
        });
        final Thread waiter = new Thread(waiting, "input-gate-interrupt-test");
        waiter.setDaemon(true);
        waiter.start();
        try {
            awaitGateWait(waiter);
            waiter.interrupt();
            Assert.assertTrue(waiting.get(3, TimeUnit.SECONDS));
            gate.close();
            gate.leave();
            gate.awaitIdle();
            Assert.assertFalse(gate.enter());
        } finally {
            waiting.cancel(true);
            waiter.join(1000);
        }
    }

    /** Observe the actual blocking point instead of relying on a sleep/isDone race. */
    static void awaitGateWait(final Thread thread) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            if (thread.getState() == Thread.State.WAITING) {
                for (final StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getClassName().equals(InputActionGate.class.getName())
                            && frame.getMethodName().equals("awaitIdle")) return;
                }
            }
            if (!thread.isAlive()) break;
            Thread.yield();
        } while (System.nanoTime() < deadline);
        Assert.fail("Waiter did not block in InputActionGate.awaitIdle; state=" + thread.getState());
    }
}
