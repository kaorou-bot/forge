package forge.gui;

import forge.game.card.CardView;
import forge.gui.interfaces.IGuiBase;
import forge.screens.match.CMatchUI;
import forge.screens.match.VAssignCombatDamage;
import forge.screens.match.VAssignGenericAmount;
import org.mockito.MockedConstruction;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/** Public desktop entrypoints with construction substitutes; no native FDialog/rendering claim. */
@Test(singleThreaded = true, timeOut = 10000)
public class TimedDesktopChoiceTest {
    public void combatDamageEntryCreatesWaitAndDoesNotReturnDismissalMap() {
        try (Harness h = new Harness()) {
            final CMatchUI gui = mock(CMatchUI.class, CALLS_REAL_METHODS);
            final CardView blocker = mock(CardView.class);
            try (MockedConstruction<VAssignCombatDamage> dialogs = mockConstruction(VAssignCombatDamage.class, (dialog, context) -> {
                Assert.assertEquals(h.started.get(), 1, "Desktop assignment must establish a wait before constructing its modal");
                when(dialog.getDamageMap()).thenAnswer(call -> {
                    h.scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
                    return Map.of(blocker, 3);
                });
            })) {
                h.assertCancelled(() -> gui.assignCombatDamage(mock(CardView.class), List.of(blocker), 3, null, true, false));
                Assert.assertEquals(dialogs.constructed().size(), 1);
                Assert.assertEquals(h.finished.get(), 1);
            }
        }
    }

    public void genericAmountEntryCreatesWaitAndDoesNotReturnDismissalMap() {
        try (Harness h = new Harness()) {
            final CMatchUI gui = mock(CMatchUI.class, CALLS_REAL_METHODS);
            try (MockedConstruction<VAssignGenericAmount> dialogs = mockConstruction(VAssignGenericAmount.class, (dialog, context) -> {
                Assert.assertEquals(h.started.get(), 1);
                when(dialog.getAssignedMap()).thenAnswer(call -> {
                    h.scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
                    return Map.of("target", 3);
                });
            })) {
                h.assertCancelled(() -> gui.assignGenericAmount(null, Map.of("target", 3), 3, false, "damage"));
                Assert.assertEquals(dialogs.constructed().size(), 1);
                Assert.assertEquals(h.finished.get(), 1);
            }
        }
    }

    public void confirmationPropagatesTypedCancellationInsteadOfFalse() {
        try (Harness h = new Harness()) {
            final CMatchUI gui = mock(CMatchUI.class);
            final CardView card = mock(CardView.class);
            // Cancel from the callable before native FDialog initialization. This still exercises
            // GuiDialog's real FutureTask/exception boundary; the actual dialog needs visible testing.
            doAnswer(call -> {
                Assert.assertEquals(h.started.get(), 1);
                h.scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
                throw h.scope.cancellation();
            }).when(gui).setCard(card);
            h.assertCancelled(() -> GuiDialog.confirm(card, "Confirm", true, List.of("Yes", "No"), gui));
            Assert.assertEquals(h.finished.get(), 1);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final IGuiBase previous = GuiBase.getInterface();
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();
        private final GuiRequestScope scope = new GuiRequestScope(new GuiRequestScope.Activity() {
            @Override public void waiting() { started.incrementAndGet(); }
            @Override public void answered() { finished.incrementAndGet(); }
        });
        private Harness() {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[] { IGuiBase.class }, (proxy, method, arguments) -> {
                        if (method.getName().startsWith("invokeInEdt")) { ((Runnable) arguments[0]).run(); return null; }
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                    }));
        }
        private void assertCancelled(final Runnable work) {
            final GuiRequestScope.Cancelled failure = Assert.expectThrows(GuiRequestScope.Cancelled.class,
                    GuiRequestScope.bind(scope, work)::run);
            Assert.assertEquals(failure.reason(), GuiRequestScope.Reason.TIMED_OUT);
        }
        @Override public void close() { scope.close(); GuiBase.setInterface(previous); }
    }
}
