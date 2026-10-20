package forge.gamemodes.match.input;

import forge.game.Game;
import forge.game.GameView;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.gui.GuiRequestScope;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollection;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real input queue/proxy and lifecycle, with a manually pumped UI; not a device test. */
@Test(singleThreaded = true, timeOut = 10000)
public class InputCancellationLifecycleTest {
    public void alreadyCancelledScopeCleansConstructedInputWithoutShowingIt() throws Exception {
        try (Harness h = new Harness()) {
            final GuiRequestScope scope = new GuiRequestScope();
            final ProbeInput input = h.input();
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            assertTimeout(h.start(scope, input).result());
            Assert.assertEquals(input.shown.get(), 0);
            Assert.assertEquals(input.cleaned.get(), 1, "Construction may already own resources");
            Assert.assertNull(h.queue.getInput());
            input.stop();
            Assert.assertEquals(input.cleaned.get(), 1);
        }
    }

    public void cancellationBeforeQueuedShowDoesNotReopenInput() throws Exception {
        try (Harness h = new Harness()) {
            final GuiRequestScope scope = new GuiRequestScope();
            final ProbeInput input = h.input();
            final Worker waiting = h.start(scope, input);
            h.awaitInput();
            final Runnable oldShow = h.ui.next();
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            assertTimeout(waiting.result());
            h.ui.run(oldShow);
            input.showMessageInitial();
            Assert.assertEquals(input.shown.get(), 0);
            Assert.assertEquals(input.cleaned.get(), 1);
            Assert.assertNull(h.queue.getInput());
        }
    }

    public void visibleMandatoryInputThrowsInsteadOfReturningAnEmptyAnswer() throws Exception {
        try (Harness h = new Harness()) {
            final GuiRequestScope scope = new GuiRequestScope();
            final ProbeInput input = h.input();
            final AtomicInteger consumedAnswer = new AtomicInteger();
            final AtomicReference<Thread> rulesThread = new AtomicReference<>();
            final Worker waiting = h.worker(() -> GuiRequestScope.bind(scope, () -> {
                rulesThread.set(Thread.currentThread());
                input.showAndWait();
                consumedAnswer.incrementAndGet();
            }).run());
            h.scopes.add(scope);
            h.awaitInput();
            h.ui.run(h.ui.next());
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            scope.close(); // Closing again must not downgrade the first reason.
            assertTimeout(waiting.result());
            Assert.assertEquals(consumedAnswer.get(), 0);
            Assert.assertEquals(input.shown.get(), 1);
            Assert.assertSame(input.cleanupThread.get(), rulesThread.get());
            Assert.assertEquals(input.cleaned.get(), 1);
        }
    }

    public void lateButtonsCardsPlayersAbilitiesAndCustomActionsAreIgnored() throws Exception {
        try (Harness h = new Harness()) {
            final GuiRequestScope scope = new GuiRequestScope();
            final ProbeInput input = h.input();
            final Worker waiting = h.start(scope, input);
            h.awaitInput();
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            assertTimeout(waiting.result());
            input.selectButtonOK();
            input.selectButtonCancel();
            input.selectPlayer(null, null);
            Assert.assertFalse(input.selectCard(null, null, null));
            Assert.assertFalse(input.selectAbility(null));
            input.runInputAction(input.actions::incrementAndGet);
            input.showMessageInitial();
            Assert.assertEquals(input.actions.get(), 0);
            Assert.assertEquals(input.shown.get(), 0);
            Assert.assertEquals(input.cleaned.get(), 1);
        }
    }

    public void actualMandatorySelectionNeverReturnsItsDefaultEmptyCollectionOnTimeout() throws Exception {
        try (Harness h = new Harness()) {
            final GuiRequestScope scope = new GuiRequestScope();
            final FCollection<Card> choices = new FCollection<>();
            choices.add(mock(Card.class));
            final InputSelectEntitiesFromList<Card> input = new InputSelectEntitiesFromList<>(
                    h.controller, 1, 1, choices);
            input.setCancelAllowed(false);
            h.inputs.add(input);
            h.scopes.add(scope);
            final AtomicInteger answersConsumed = new AtomicInteger();
            final Worker waiting = h.worker(GuiRequestScope.bind(scope, () -> {
                input.showAndWait();
                answersConsumed.addAndGet(input.getSelected().size() + 1);
            }));
            h.awaitInput();
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            assertTimeout(waiting.result());
            Assert.assertEquals(answersConsumed.get(), 0);
            verify(h.gui).setSelection(any());
            Assert.assertNull(h.queue.getInput());
        }
    }

    public void removingOuterInputPreservesNestedInputAndSkipsItsStaleShow() throws Exception {
        try (Harness h = new Harness()) {
            final GuiRequestScope outerScope = new GuiRequestScope();
            final GuiRequestScope innerScope = new GuiRequestScope();
            final ProbeInput outer = h.input();
            final ProbeInput inner = h.input();
            final Worker outerWait = h.start(outerScope, outer);
            h.awaitInput();
            final Runnable outerShow = h.ui.next();
            final Worker innerWait = h.start(innerScope, inner);
            h.awaitInput();
            final Runnable innerShow = h.ui.next();
            Assert.assertSame(h.queue.getInput(), inner);
            h.ui.run(outerShow); // Actual InputProxy must reject an old queued identity.
            Assert.assertEquals(outer.shown.get(), 0);
            outerScope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            assertTimeout(outerWait.result());
            Assert.assertSame(h.queue.getInput(), inner);
            Assert.assertFalse(innerScope.isCancelled());
            Assert.assertFalse(innerWait.future.isDone());
            h.ui.run(innerShow);
            Assert.assertEquals(inner.shown.get(), 1);
            h.proxy.selectButtonOK();
            Assert.assertNull(innerWait.result());
            Assert.assertNull(h.queue.getInput());
            Assert.assertEquals(outer.cleaned.get(), 1);
            Assert.assertEquals(inner.cleaned.get(), 1);
        }
    }

    public void repeatedStopCleansOnceAndUnboundInputStillCompletesNormally() throws Exception {
        try (Harness h = new Harness()) {
            final ProbeInput input = h.input();
            final Worker waiting = h.start(null, input);
            h.awaitInput();
            h.ui.run(h.ui.next());
            Assert.assertTrue(input.selectAbility(null));
            input.selectButtonOK();
            input.stop();
            input.stop();
            Assert.assertNull(waiting.result());
            Assert.assertEquals(input.cleaned.get(), 1);
            Assert.assertEquals(input.actions.get(), 2);
            Assert.assertNull(h.queue.getInput());
        }
    }

    public void onStopFailureStillReleasesNormalWaitAndRemovesInput() throws Exception {
        try (Harness h = new Harness()) {
            final ProbeInput input = h.input();
            final RuntimeException failure = new IllegalStateException("Injected onStop failure");
            input.cleanup = () -> { throw failure; };
            final Worker waiting = h.start(null, input);
            h.awaitInput();
            final RuntimeException thrown = Assert.expectThrows(RuntimeException.class, input::stop);
            Assert.assertSame(thrown, failure);
            Assert.assertSame(waiting.result(), failure, "Cleanup failure must reach the released rules wait");
            Assert.assertNull(h.queue.getInput());
            input.stop();
            Assert.assertEquals(input.cleaned.get(), 1);
        }
    }

    public void cleanupFailureCannotReplaceTimeoutWithAnUnrelatedException() throws Exception {
        try (Harness h = new Harness()) {
            final GuiRequestScope scope = new GuiRequestScope();
            final ProbeInput input = h.input();
            final RuntimeException failure = new IllegalStateException("Injected cancellation cleanup failure");
            input.cleanup = () -> { throw failure; };
            final Worker waiting = h.start(scope, input);
            h.awaitInput();
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            final Throwable outcome = waiting.result();
            assertTimeout(outcome);
            Assert.assertTrue(List.of(outcome.getSuppressed()).contains(failure), "Cleanup error must remain diagnosable");
            Assert.assertNull(h.queue.getInput());
            Assert.assertEquals(input.cleaned.get(), 1);
        }
    }

    public void cancellationDrainsAnAdmittedActionBeforeCleanupOrRulesContinuation() throws Exception {
        final CountDownLatch releaseAction = new CountDownLatch(1);
        try (Harness h = new Harness()) {
            final GuiRequestScope scope = new GuiRequestScope();
            final ProbeInput input = h.input();
            final Worker waiting = h.start(scope, input);
            h.awaitInput();
            final CountDownLatch actionEntered = new CountDownLatch(1);
            final AtomicInteger order = new AtomicInteger();
            final AtomicInteger lastActionWrite = new AtomicInteger();
            input.cleanup = () -> Assert.assertEquals(lastActionWrite.get(), 1,
                    "onStop ran concurrently with an admitted action");
            final Worker action = h.worker(() -> input.runInputAction(() -> {
                actionEntered.countDown();
                await(releaseAction);
                lastActionWrite.set(order.incrementAndGet());
            }));
            await(actionEntered);
            final Worker timer = h.worker(() -> scope.cancel(GuiRequestScope.Reason.TIMED_OUT));
            Assert.assertNull(timer.result(), "Timer must not wait for the active action");
            InputActionGateTest.awaitGateWait(waiting.thread);
            Assert.assertFalse(waiting.future.isDone());
            Assert.assertEquals(input.cleaned.get(), 0);
            input.selectButtonOK();
            Assert.assertEquals(input.actions.get(), 0, "Gate must already reject late actions");
            releaseAction.countDown();
            Assert.assertNull(action.result());
            assertTimeout(waiting.result());
            Assert.assertEquals(input.cleaned.get(), 1);
        } finally {
            releaseAction.countDown();
        }
    }

    public void actionCanWaitForUiThatEntersTheSameGateAndCancels() throws Exception {
        try (Harness h = new Harness()) {
            final GuiRequestScope scope = new GuiRequestScope();
            final ProbeInput input = h.input();
            final Worker waiting = h.start(scope, input);
            h.awaitInput();
            h.ui.run(h.ui.next());
            final AtomicInteger nested = new AtomicInteger();
            final Worker action = h.worker(() -> input.runInputAction(() ->
                    FThreads.invokeInEdtAndWait(() -> {
                        input.runInputAction(nested::incrementAndGet);
                        scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
                    })));
            final Runnable uiCall = h.ui.next();
            final Worker edt = h.worker(() -> h.ui.run(uiCall));
            Assert.assertNull(edt.result(), "An action must not hold the gate monitor while waiting for EDT");
            Assert.assertNull(action.result());
            assertTimeout(waiting.result());
            Assert.assertEquals(nested.get(), 1);
            Assert.assertEquals(input.cleaned.get(), 1);
        }
    }

    public void preCancelledManaInputReleasesConstructorPaymentStateExactlyOnce() throws Exception {
        try (Harness h = new Harness()) {
            final Player payer = mock(Player.class);
            final ManaPool pool = mock(ManaPool.class);
            when(payer.getGame()).thenReturn(mock(Game.class));
            when(payer.getManaPool()).thenReturn(pool);
            when(pool.isEmpty()).thenReturn(true);
            final SpellAbility ability = mock(SpellAbility.class);
            when(ability.getPayCosts()).thenReturn(mock(Cost.class));
            final ManaCostBeingPaid cost = mock(ManaCostBeingPaid.class);
            final InputPayManaOfCostPayment input = new InputPayManaOfCostPayment(
                    h.controller, cost, ability, payer, null, false);
            h.inputs.add(input);
            verify(payer).pushPaidForSA(ability);
            verify(ability).setManaCostBeingPaid(cost);
            final GuiRequestScope scope = new GuiRequestScope();
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            assertTimeout(h.start(scope, input).result());
            verify(payer, times(1)).popPaidForSA();
            verify(ability, times(1)).setManaCostBeingPaid(null);
            input.stop();
            verify(payer, times(1)).popPaidForSA();
            Assert.assertNull(h.queue.getInput());
        }
    }

    public void failingActionableHighlightCleanupDoesNotLeakPaymentResources() throws Exception {
        try (Harness h = new Harness()) {
            final Player payer = mock(Player.class);
            final ManaPool pool = mock(ManaPool.class);
            when(payer.getGame()).thenReturn(mock(Game.class));
            when(payer.getManaPool()).thenReturn(pool);
            when(pool.isEmpty()).thenReturn(true);
            final SpellAbility ability = mock(SpellAbility.class);
            when(ability.getPayCosts()).thenReturn(mock(Cost.class));
            final ManaCostBeingPaid cost = mock(ManaCostBeingPaid.class);
            final InputPayManaOfCostPayment input = new InputPayManaOfCostPayment(
                    h.controller, cost, ability, payer, null, false);
            h.inputs.add(input);
            final RuntimeException failure = new IllegalStateException("Cannot clear actionable highlights");
            doThrow(failure).when(h.controller).clearActionableCards();
            final GuiRequestScope scope = new GuiRequestScope();
            final Worker waiting = h.start(scope, input);
            h.awaitInput();
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            final Throwable outcome = waiting.result();
            assertTimeout(outcome);
            Assert.assertTrue(List.of(outcome.getSuppressed()).contains(failure));
            verify(ability).setManaCostBeingPaid(null);
            verify(payer).popPaidForSA();
            Assert.assertNull(h.queue.getInput());
        }
    }

    private static void assertTimeout(final Throwable outcome) {
        Assert.assertTrue(outcome instanceof GuiRequestScope.Cancelled,
                "Expected cancellation rather than normal/empty answer or cleanup error, got " + outcome);
        Assert.assertEquals(((GuiRequestScope.Cancelled) outcome).reason(), GuiRequestScope.Reason.TIMED_OUT);
    }

    private static void await(final CountDownLatch latch) {
        try { Assert.assertTrue(latch.await(3, TimeUnit.SECONDS), "Test barrier was not released"); }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted test barrier", e);
        }
    }

    private static final class ProbeInput extends InputSyncronizedBase {
        private static final long serialVersionUID = 1L;
        private final AtomicInteger shown = new AtomicInteger();
        private final AtomicInteger cleaned = new AtomicInteger();
        private final AtomicInteger actions = new AtomicInteger();
        private final AtomicReference<Thread> cleanupThread = new AtomicReference<>();
        private Runnable cleanup = () -> { };

        private ProbeInput(final PlayerControllerHuman controller) { super(controller); }
        @Override protected void showMessage() { shown.incrementAndGet(); }
        @Override public String getActivateAction(final Card card) { return null; }
        @Override protected void onOk() { actions.incrementAndGet(); stop(); }
        @Override protected void onCancel() { actions.incrementAndGet(); stop(); }
        @Override protected void onPlayerSelected(final Player p, final ITriggerEvent event) { actions.incrementAndGet(); }
        @Override protected boolean onCardSelected(final Card c, final List<Card> others, final ITriggerEvent event) {
            actions.incrementAndGet();
            return true;
        }
        @Override protected boolean onAbilitySelected(final SpellAbility ability) { actions.incrementAndGet(); return true; }
        @Override protected void onStop() {
            cleanupThread.set(Thread.currentThread());
            cleaned.incrementAndGet();
            cleanup.run();
        }
    }

    private static final class Worker {
        private final FutureTask<Throwable> future;
        private final Thread thread;
        private Worker(final Runnable work) {
            future = new FutureTask<>(() -> {
                try { work.run(); return null; }
                catch (final Throwable failure) { return failure; }
            });
            thread = new Thread(future, "input-lifecycle-test");
            thread.setDaemon(true);
            thread.start();
        }
        private Throwable result() throws Exception { return future.get(3, TimeUnit.SECONDS); }
    }

    private static final class Harness implements AutoCloseable {
        private final QueuedUi ui = new QueuedUi();
        private final PlayerControllerHuman controller = mock(PlayerControllerHuman.class);
        private final IGuiGame gui = mock(IGuiGame.class);
        private final InputProxy proxy = new InputProxy(controller);
        private final InputQueue queue = new InputQueue(mock(GameView.class), proxy);
        private final BlockingQueue<CountDownLatch> waits = new LinkedBlockingQueue<>();
        private final List<InputSyncronizedBase> inputs = new ArrayList<>();
        private final List<GuiRequestScope> scopes = new ArrayList<>();
        private final List<Worker> workers = new ArrayList<>();

        private Harness() throws InterruptedException {
            when(controller.getGui()).thenReturn(gui);
            when(controller.getInputQueue()).thenReturn(queue);
            when(controller.getInputProxy()).thenReturn(proxy);
            doAnswer(invocation -> {
                final CountDownLatch done = invocation.getArgument(0);
                waits.add(done);
                done.await();
                return null;
            }).when(gui).awaitInput(any(CountDownLatch.class));
        }
        private ProbeInput input() {
            final ProbeInput input = new ProbeInput(controller);
            inputs.add(input);
            return input;
        }
        private Worker worker(final Runnable task) {
            final Worker worker = new Worker(task);
            workers.add(worker);
            return worker;
        }
        private Worker start(final GuiRequestScope scope, final InputSyncronizedBase input) {
            if (scope != null) scopes.add(scope);
            return worker(GuiRequestScope.bind(scope, input::showAndWait));
        }
        private void awaitInput() throws InterruptedException {
            Assert.assertNotNull(waits.poll(3, TimeUnit.SECONDS), "Rules thread did not reach actual input wait");
        }
        @Override public void close() throws InterruptedException {
            try {
                for (final GuiRequestScope scope : scopes) scope.close();
                for (final Worker worker : workers) worker.future.cancel(true);
                for (final Worker worker : workers) worker.thread.join(1000);
                for (final InputSyncronizedBase input : inputs) {
                    try { input.stop(); } catch (final RuntimeException ignored) { /* injected cleanup failure */ }
                }
            } finally { ui.close(); }
        }
    }

    private static final class QueuedUi implements AutoCloseable {
        private final IGuiBase previous = GuiBase.getInterface();
        private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        private final ThreadLocal<Boolean> onUi = ThreadLocal.withInitial(() -> false);
        private QueuedUi() {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[] { IGuiBase.class }, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "invokeInEdtLater": queue.add((Runnable) args[0]); return null;
                            case "invokeInEdtNow": ((Runnable) args[0]).run(); return null;
                            case "invokeInEdtAndWait":
                                if (onUi.get()) { ((Runnable) args[0]).run(); return null; }
                                final FutureTask<Void> call = new FutureTask<>((Runnable) args[0], null);
                                queue.add(call);
                                call.get(3, TimeUnit.SECONDS);
                                return null;
                            case "isGuiThread": return onUi.get();
                            case "isLibgdxPort": return true;
                            default:
                                if (method.getReturnType() == boolean.class) return false;
                                if (method.getReturnType() == int.class) return 0;
                                return null;
                        }
                    }));
        }
        private Runnable next() throws InterruptedException {
            final Runnable task = queue.poll(3, TimeUnit.SECONDS);
            Assert.assertNotNull(task, "Expected queued UI task");
            return task;
        }
        private void run(final Runnable task) {
            final boolean before = onUi.get();
            onUi.set(true);
            try { task.run(); } finally { onUi.set(before); }
        }
        @Override public void close() { GuiBase.setInterface(previous); }
    }
}
