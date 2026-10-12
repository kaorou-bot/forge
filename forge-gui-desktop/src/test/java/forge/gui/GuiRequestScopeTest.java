package forge.gui;

import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Test(singleThreaded = true)
public class GuiRequestScopeTest {
    public void firstCancellationReasonSurvivesRepeatedCloseAndLateListeners() {
        final GuiRequestScope scope = new GuiRequestScope();
        final AtomicInteger listeners = new AtomicInteger();
        scope.onCancel(listeners::incrementAndGet);
        scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
        scope.cancel(GuiRequestScope.Reason.GAME_CLOSED);
        scope.close();
        scope.onCancel(listeners::incrementAndGet);
        Assert.assertEquals(listeners.get(), 2);
        final GuiRequestScope.Cancelled cancelled = Assert.expectThrows(GuiRequestScope.Cancelled.class, scope::checkActive);
        Assert.assertEquals(cancelled.reason(), GuiRequestScope.Reason.TIMED_OUT);
        final GuiRequestScope ordinary = new GuiRequestScope();
        ordinary.close();
        ordinary.cancel(GuiRequestScope.Reason.TIMED_OUT);
        Assert.assertEquals(ordinary.cancellation().reason(), GuiRequestScope.Reason.CANCELLED);
    }

    public void realSwingModalBoundaryDoesNotLendScopeToRawEvents() throws Exception {
        final GuiRequestScope outer = new GuiRequestScope();
        final GuiRequestScope explicit = new GuiRequestScope();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger unrelatedClosed = new AtomicInteger();
        javax.swing.SwingUtilities.invokeAndWait(() -> GuiRequestScope.bind(outer, () -> {
            final var loop = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    final GuiRequestScope rawEventOwner = GuiRequestScope.current();
                    if (rawEventOwner != null) rawEventOwner.onCancel(unrelatedClosed::incrementAndGet);
                    Assert.assertNull(rawEventOwner);
                    GuiRequestScope.bind(explicit, () -> Assert.assertSame(GuiRequestScope.current(), explicit)).run();
                    Assert.assertNull(GuiRequestScope.current());
                } catch (final Throwable error) {
                    failure.set(error);
                } finally {
                    loop.exit();
                }
            });
            GuiRequestScope.runWithoutContext(() -> Assert.assertTrue(loop.enter()));
            Assert.assertSame(GuiRequestScope.current(), outer);
        }).run());
        outer.close();
        Assert.assertNull(failure.get());
        Assert.assertEquals(unrelatedClosed.get(), 0);
        Assert.assertNull(GuiRequestScope.current());
    }

    public void delayCapturesBeforeSchedulerThreadAndDeferredActionsUseExplicitOwner() throws Exception {
        try (RequestTestGui gui = new RequestTestGui(); GuiRequestScope scope = new GuiRequestScope()) {
            final AtomicReference<GuiRequestScope> seen = new AtomicReference<>();
            GuiRequestScope.bind(scope, () -> FThreads.delayInEDT(1, () -> seen.set(GuiRequestScope.current()))).run();
            gui.next().run();
            Assert.assertSame(seen.get(), scope);
            GuiRequestScope.runAction(scope, () -> Assert.assertSame(GuiRequestScope.current(), scope));
            scope.close();
            GuiRequestScope.runAction(scope, () -> Assert.fail("Stale UI action executed"));
            Assert.assertNull(GuiRequestScope.current());
        }
    }

    public void callbacksAreIsolatedAndLateRegistrationIsCancelledOnce() {
        final GuiRequestScope first = new GuiRequestScope();
        final GuiRequestScope second = new GuiRequestScope();
        final AtomicInteger closed = new AtomicInteger();
        first.onCancel(closed::incrementAndGet);
        first.onCancel(() -> { throw new IllegalStateException("test rejection"); });
        first.onCancel(closed::incrementAndGet);
        second.onCancel(() -> Assert.fail("Unrelated request was cancelled"));
        first.close();
        first.close();
        Assert.assertEquals(closed.get(), 2);
        first.onCancel(closed::incrementAndGet);
        Assert.assertEquals(closed.get(), 3);
        Assert.assertNotNull(first.cleanupFailure());
        Assert.assertFalse(second.isCancelled());
    }

    public void unregisterDisarmsCleanupAndNestedContextsRestoreEvenAfterFailure() {
        final GuiRequestScope outer = new GuiRequestScope();
        final GuiRequestScope inner = new GuiRequestScope();
        outer.onCancel(() -> Assert.fail("Unregistered cleanup fired")).close();
        GuiRequestScope.bind(outer, () -> {
            Assert.assertSame(GuiRequestScope.current(), outer);
            Assert.expectThrows(IllegalStateException.class, () -> GuiRequestScope.bind(inner, () -> {
                Assert.assertSame(GuiRequestScope.current(), inner);
                throw new IllegalStateException("nested");
            }).run());
            GuiRequestScope.bind(null, () -> Assert.assertNull(GuiRequestScope.current())).run();
            Assert.assertSame(GuiRequestScope.current(), outer);
        }).run();
        Assert.assertNull(GuiRequestScope.current());
        outer.close();
    }

    public void dispatcherCapturesContextButUnscopedTaskDoesNotInheritModalContext() throws Exception {
        try (RequestTestGui gui = new RequestTestGui()) {
            final GuiRequestScope scope = new GuiRequestScope();
            final AtomicReference<GuiRequestScope> seen = new AtomicReference<>();
            GuiRequestScope.bind(scope, () -> FThreads.invokeInEdtLater(() -> seen.set(GuiRequestScope.current()))).run();
            gui.next().run();
            Assert.assertSame(seen.get(), scope);
            FThreads.invokeInEdtLater(() -> Assert.assertNull(GuiRequestScope.current()));
            GuiRequestScope.bind(scope, gui.next()).run();
            Assert.assertNull(GuiRequestScope.current());
        }
    }
}
