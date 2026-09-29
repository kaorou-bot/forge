package forge.view.arcane;

import forge.game.card.CardView;
import forge.game.zone.ZoneType;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.screens.match.CMatchUI;
import forge.screens.match.layout.MatchCardPresentation;
import forge.screens.match.views.VField;
import forge.toolbox.FScrollPane;
import forge.util.ITriggerEvent;
import forge.view.arcane.util.CardPanelMouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import org.mockito.ArgumentCaptor;
import org.testng.Assert;
import org.testng.annotations.Test;
import static org.mockito.Mockito.*;

public class HandDropTest {
    @Test public void fastDragKeepsPressedCardWhenFirstMotionLeavesItsBounds() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (forge.gui.GuiBase.getInterface() == null) { forge.gui.GuiBase.setInterface(new forge.GuiDesktop()); }
            try (var model = mockStatic(FModel.class);
                    var skin = mockStatic(forge.toolbox.FSkin.class, CALLS_REAL_METHODS)) {
                model.when(FModel::getPreferences).thenReturn(mock(ForgePreferences.class));
                skin.when(() -> forge.toolbox.FSkin.getIcon(forge.localinstance.skin.FSkinProp.ICO_FLIPCARD))
                        .thenReturn(mock(forge.toolbox.FSkin.SkinIcon.class));
                final var match = mock(CMatchUI.class);
                when(match.getCardPresentation()).thenReturn(MatchCardPresentation.CLASSIC);
                final var card = mock(CardPanel.class);
                final var starts = new AtomicInteger();
                final var ends = new AtomicInteger();
                final var hand = new CardPanelContainer(match, mock(FScrollPane.class)) {
                    @Override public void doLayout() { }
                    @Override public CardPanel getCardPanel(int x, int y) {
                        return x >= 0 && x < 100 && y >= 0 && y < 140 ? card : null;
                    }
                    @Override protected boolean cardPanelDraggable(CardPanel p) { return true; }
                    @Override public void mouseDragStart(CardPanel p, MouseEvent e) {
                        Assert.assertSame(p, card);
                        starts.incrementAndGet();
                    }
                    @Override public void mouseDragEnd(CardPanel p, MouseEvent e) {
                        Assert.assertSame(p, card);
                        ends.incrementAndGet();
                    }
                };
                hand.setDragEnabled(true);
                hand.dispatchEvent(new MouseEvent(hand, MouseEvent.MOUSE_PRESSED, 1,
                        MouseEvent.BUTTON1_DOWN_MASK, 50, 70, 1, false, MouseEvent.BUTTON1));
                hand.dispatchEvent(new MouseEvent(hand, MouseEvent.MOUSE_DRAGGED, 2,
                        MouseEvent.BUTTON1_DOWN_MASK, 400, -200, 0, false, MouseEvent.NOBUTTON));
                hand.dispatchEvent(release(hand, 400, -200, MouseEvent.BUTTON1));
                Assert.assertEquals(starts.get(), 1);
                Assert.assertEquals(ends.get(), 1);
                verify(match, never()).setLastClickedCardPanel(any());
            }
        });
    }

    private static JPanel visiblePanel() {
        return new JPanel(null) { @Override public boolean isShowing() { return true; } };
    }
    private static JRootPane root(JPanel content) {
        final var root = new JRootPane();
        root.setSize(900, 700);
        root.setContentPane(content);
        root.doLayout();
        return root;
    }
    private static MouseEvent release(java.awt.Component source, int x, int y, int button) {
        return new MouseEvent(source, MouseEvent.MOUSE_RELEASED, 1, 0, x, y, 1, false, button);
    }

    @Test public void dropHitTestingUsesVisibleClippingAndSameWindow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var host = visiblePanel();
            root(host);
            final var source = visiblePanel();
            source.setBounds(100, 500, 600, 180);
            host.add(source);
            final var clip = visiblePanel();
            clip.setBounds(100, 100, 300, 200);
            host.add(clip);
            final var target = visiblePanel();
            target.setBounds(0, -100, 300, 400);
            clip.add(target);
            Assert.assertTrue(CardDropTarget.contains(release(source, 20, -350, 1), target));
            Assert.assertFalse(CardDropTarget.contains(release(source, 20, -450, 1), target), "Clipped content is not a drop target");
            Assert.assertFalse(CardDropTarget.contains(release(source, 20, 20, 1), target), "Hand sorting is not a battlefield drop");
            final var another = visiblePanel();
            root(another);
            another.add(target);
            Assert.assertFalse(CardDropTarget.contains(release(source, 20, -350, 1), target), "Other windows are excluded");
            final var hidden = new JPanel();
            host.add(hidden);
            hidden.setBounds(100, 100, 300, 200);
            Assert.assertFalse(CardDropTarget.contains(release(source, 20, -350, 1), hidden));
        });
    }

    @Test public void battlefieldDropUsesClickControllerOnceAfterCleaningDragAndSkipsReorder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (forge.gui.GuiBase.getInterface() == null) { forge.gui.GuiBase.setInterface(new forge.GuiDesktop()); }
            try (var model = mockStatic(FModel.class)) {
                model.when(FModel::getPreferences).thenReturn(mock(ForgePreferences.class));
                final CMatchUI match = mock(CMatchUI.class);
                final IGameController controller = mock(IGameController.class);
                when(match.getGameController()).thenReturn(controller);
                when(match.isCurrentScreen()).thenReturn(true);
                when(match.getCardPresentation()).thenReturn(MatchCardPresentation.CLASSIC);
                final var hand = new HandArea(match, mock(FScrollPane.class)) {
                    @Override public boolean isShowing() { return true; }
                    @Override public void doLayout() { }
                };
                final var host = visiblePanel();
                root(host);
                host.add(hand);
                hand.setBounds(100, 500, 600, 180);
                final var viewport = new javax.swing.JViewport() {
                    @Override public boolean isShowing() { return true; }
                };
                host.add(viewport);
                viewport.setBounds(100, 100, 600, 350);
                final var scroll = mock(FScrollPane.class);
                when(scroll.getViewport()).thenReturn(viewport);
                final var play = mock(PlayArea.class);
                when(play.getScrollPane()).thenReturn(scroll);
                final var field = mock(VField.class);
                when(field.getTabletop()).thenReturn(play);
                when(match.getFieldViews()).thenReturn(List.of(field));
                final var card = mock(CardView.class);
                when(card.getZone()).thenReturn(ZoneType.Hand);
                when(match.mayView(card)).thenReturn(true);
                final var panel = mock(CardPanel.class);
                when(panel.getCard()).thenReturn(card);
                when(panel.getCardWidth()).thenReturn(100);
                when(panel.getCardHeight()).thenReturn(140);
                hand.getCardPanels().add(panel);
                final var reorder = new AtomicInteger();
                hand.addCardPanelMouseListener(new CardPanelMouseAdapter() {
                    @Override public void mouseDragEnd(CardPanel p, MouseEvent e) { reorder.incrementAndGet(); }
                });
                final var ghost = mock(CardPanel.class);
                CardPanel.setDragAnimationPanel(ghost);
                doAnswer(invocation -> {
                    Assert.assertNull(CardPanel.getDragAnimationPanel(), "Clean drag before selecting/asking for payment");
                    Assert.assertFalse(hand.isDragged());
                    return false; // An illegal play is still routed to the normal input controller.
                }).when(controller).selectCard(eq(card), isNull(), any(ITriggerEvent.class));
                try {
                    Assert.assertNull(hand.getDropAction(panel, release(hand, 20, 20, 1)));
                    Assert.assertNull(hand.getDropAction(panel, release(hand, 20, -300, 3)));
                    hand.setDragged(true);
                    hand.mouseDragEnd(panel, release(hand, 20, -300, 1));
                    verify(ghost).dispose();
                    verify(panel).setDisplayEnabled(true);
                    verify(match).setLastClickedCardPanel(panel);
                    final var trigger = ArgumentCaptor.forClass(ITriggerEvent.class);
                    verify(controller, times(1)).selectCard(eq(card), isNull(), trigger.capture());
                    Assert.assertEquals(trigger.getValue().getButton(), MouseEvent.BUTTON1);
                    Assert.assertEquals(trigger.getValue().getX(), 50);
                    Assert.assertEquals(trigger.getValue().getY(), 70);
                    Assert.assertEquals(reorder.get(), 0);
                    Assert.assertTrue(hand.getCardPanels().contains(panel), "Controller rejection leaves the card in hand");
                    when(card.getZone()).thenReturn(ZoneType.Graveyard);
                    Assert.assertNull(hand.getDropAction(panel, release(hand, 20, -300, 1)));
                    when(card.getZone()).thenReturn(ZoneType.Hand);
                    when(match.mayView(card)).thenReturn(false);
                    Assert.assertNull(hand.getDropAction(panel, release(hand, 20, -300, 1)));
                    when(match.mayView(card)).thenReturn(true);
                    when(match.isCurrentScreen()).thenReturn(false);
                    Assert.assertNull(hand.getDropAction(panel, release(hand, 20, -300, 1)));
                } finally { CardPanel.setDragAnimationPanel(null); }
            }
        });
    }
}
