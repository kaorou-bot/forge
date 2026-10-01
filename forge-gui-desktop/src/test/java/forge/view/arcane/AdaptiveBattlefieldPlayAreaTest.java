package forge.view.arcane;

import forge.game.card.CardView;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.screens.match.CMatchUI;
import forge.screens.match.layout.BattlefieldLayoutStrategy;
import forge.screens.match.layout.CardOverlayPainter;
import forge.screens.match.layout.MatchCardPresentation;
import forge.toolbox.FScrollPane;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.testng.Assert;
import org.testng.annotations.Test;
import static org.mockito.Mockito.*;

/** Exercises the real PlayArea adapter, CardPanel bounds calculation and battlefield hit testing. */
public class AdaptiveBattlefieldPlayAreaTest {
    @Test public void realRotationEnvelopesFitShortBattlefields() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (forge.gui.GuiBase.getInterface() == null) { forge.gui.GuiBase.setInterface(new forge.GuiDesktop()); }
            try (var model = mockStatic(FModel.class)) {
                model.when(FModel::getPreferences).thenReturn(mock(ForgePreferences.class));
                final var match = mock(CMatchUI.class);
                final var strategy = BattlefieldLayoutStrategy.adaptive(true, 8, 6);
                when(match.getCardPresentation()).thenReturn(new MatchCardPresentation(null, CardOverlayPainter.NONE, strategy));
                for (int height : new int[]{140, 180, 240, 265, 400}) {
                    for (boolean opponent : List.of(false, true)) {
                        final int width = 1218;
                        final var area = new PlayArea(match, mock(FScrollPane.class), opponent, null, ZoneType.Battlefield);
                        final var row = innerList(area, "CardStackRow");
                        final var all = new ArrayList<CardPanel>();
                        for (int kind = 0; kind < 3; kind++) {
                            final var stack = innerList(area, "CardStack");
                            final var cards = panels(kind == 1, kind == 2).subList(0, kind == 1 ? 3 : 1);
                            final int faceWidth = strategy.maximumCardWidth(width, height, 150, 50);
                            for (int i = 0; i < cards.size(); i++) {
                                final var card = cards.get(i);
                                when(card.getCard().getCurrentState().isCreature()).thenReturn(kind == 0);
                                card.setCardBounds(10 + i * faceWidth / 8, 10 + i * faceWidth / 6, faceWidth, Math.round(faceWidth * 1.4f));
                            }
                            stack.addAll(cards); row.add(stack); all.addAll(cards);
                        }
                        final var template = List.of(row);
                        set(area, "rows", template); set(area, "playAreaWidth", width); set(area, "playAreaHeight", height);
                        final var apply = PlayArea.class.getDeclaredMethod("applyBattlefieldLayout", List.class);
                        apply.setAccessible(true); apply.invoke(area, template);
                        for (var card : all) {
                            final var bounds = card.getBounds();
                            Assert.assertTrue(bounds.y >= 0 && bounds.y + bounds.height <= height,
                                    "Complete tapped/stacked card envelope clipped at height " + height + ": " + bounds);
                        }
                        Assert.assertEquals(area.getPreferredSize().height, height, "Sparse board must not need vertical scrolling");
                    }
                }
            } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
        });
    }

    @Test public void completeRotatedStacksStayInTheirHalfAndRemainClickable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (forge.gui.GuiBase.getInterface() == null) { forge.gui.GuiBase.setInterface(new forge.GuiDesktop()); }
            try (var model = mockStatic(FModel.class)) {
                model.when(FModel::getPreferences).thenReturn(mock(ForgePreferences.class));
                final var match = mock(CMatchUI.class);
                when(match.getCardPresentation()).thenReturn(new MatchCardPresentation(null, CardOverlayPainter.NONE,
                        BattlefieldLayoutStrategy.adaptive(true, 8, 6)));
                for (boolean opponent : List.of(false, true)) {
                    for (int width : new int[]{150, 241, 500, 900}) {
                        final var area = new PlayArea(match, mock(FScrollPane.class), opponent, null, ZoneType.Battlefield);
                        final var lands = panels(true, false);
                        final var others = panels(false, true);
                        final var row = innerList(area, "CardStackRow");
                        for (var panels : List.of(lands, others)) {
                            final var stack = innerList(area, "CardStack");
                            stack.addAll(panels);
                            row.add(stack);
                        }
                        final var template = List.of(row);
                        set(area, "rows", template);
                        set(area, "playAreaWidth", width);
                        set(area, "playAreaHeight", 400);
                        final var apply = PlayArea.class.getDeclaredMethod("applyBattlefieldLayout", List.class);
                        apply.setAccessible(true);
                        apply.invoke(area, template);
                        for (var panels : List.of(lands, others)) {
                            for (var panel : panels) {
                                final var bounds = panel.getBounds();
                                Assert.assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= width);
                                if (panels == lands) { Assert.assertTrue(bounds.x + bounds.width <= width / 2, "Land rotation envelope crosses middle"); }
                                else { Assert.assertTrue(bounds.x >= width / 2, "Other rotation envelope crosses middle"); }
                            }
                            final var front = panels.get(0);
                            final int hitWidth = front.isTapped() ? front.getCardHeight() : front.getCardWidth();
                            final int hitHeight = front.isTapped() ? front.getCardWidth() : front.getCardHeight();
                            final int hitTop = front.getCardY() + (front.isTapped() ? hitWidth - hitHeight : 0);
                            Assert.assertSame(area.getCardPanel(front.getCardX() + hitWidth / 2, hitTop + hitHeight / 2), front);
                        }
                        Assert.assertEquals(area.getPreferredSize().width, width, "No horizontal overflow should shift the divider");
                    }
                }
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }

    private static List<CardPanel> panels(boolean land, boolean tapped) {
        final var result = new ArrayList<CardPanel>();
        for (int i = 0; i < 32; i++) {
            // Keep CardPanel's real geometry without starting card image downloads or a live match.
            final var panel = mock(CardPanel.class, CALLS_REAL_METHODS);
            final var rectangle = new AtomicReference<>(new Rectangle());
            doAnswer(call -> { rectangle.set(new Rectangle(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3))); return null; })
                    .when(panel).setBounds(anyInt(), anyInt(), anyInt(), anyInt());
            doAnswer(call -> new Rectangle(rectangle.get())).when(panel).getBounds();
            doAnswer(call -> rectangle.get().x).when(panel).getX();
            doAnswer(call -> rectangle.get().y).when(panel).getY();
            doReturn(true).when(panel).isDisplayEnabled();
            final var card = mock(CardView.class);
            final var state = mock(CardView.CardStateView.class);
            when(card.getCurrentState()).thenReturn(state);
            when(state.isLand()).thenReturn(land);
            doReturn(card).when(panel).getCard();
            doReturn(List.of()).when(panel).getAttachedPanels();
            panel.setTapped(tapped);
            panel.setCardBounds(10 + i * 12, 10 + i * 17, 100, 140);
            result.add(panel);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> innerList(PlayArea area, String name) throws ReflectiveOperationException {
        final var constructor = Class.forName(PlayArea.class.getName() + "$" + name).getDeclaredConstructor(PlayArea.class);
        constructor.setAccessible(true);
        return (List<Object>) constructor.newInstance(area);
    }
    private static void set(PlayArea area, String name, Object value) throws ReflectiveOperationException {
        final var field = PlayArea.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(area, value);
    }
}
