/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Nate
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.view.arcane;

import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;

import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.match.CMatchUI;
import forge.toolbox.FScrollPane;
import forge.toolbox.MouseTriggerEvent;

/**
 * <p>
 * HandArea class.
 * </p>
 * 
 * @author Forge
 * @version $Id: HandArea.java 24769 2014-02-09 13:56:04Z Hellfish $
 */
public class HandArea extends CardArea {
    /** Constant <code>serialVersionUID=7488132628637407745L</code>. */
    private static final long serialVersionUID = 7488132628637407745L;

    /**
     * <p>
     * Constructor for HandArea.
     * </p>
     * TODO Make compatible with WindowBuilder
     * 
     * @param scrollPane
     */
    public HandArea(final CMatchUI matchUI, final FScrollPane scrollPane) {
        super(matchUI, scrollPane);

        this.setDragEnabled(true);
        this.setVertical(true);
        this.setMaxCardsPerRow(FModel.getPreferences().getPrefInt(FPref.UI_HAND_MAX_CARDS_PER_ROW));
        this.setNoOverlap(FModel.getPreferences().getPrefBoolean(FPref.UI_HAND_NO_OVERLAP));
    }

    @Override
    public void doLayout() {
        final var strategy = getMatchUI().getCardPresentation().hand();
        if (strategy == null) {
            for (CardPanel panel : getCardPanels()) { panel.setPresentationAngle(0); panel.setPresentationHitBounds(null); }
            super.doLayout();
            return;
        }
        final var extent = getScrollPane().getViewport().getExtentSize();
        if (extent.width <= 0 || extent.height <= 0) { return; }
        final var placements = strategy.arrange(getCardPanels().size(), extent.width, extent.height, getCardWidthMax());
        if (placements.size() != getCardPanels().size()) {
            throw new IllegalArgumentException("Hand layout must return one placement per card");
        }
        for (int i = 0; i < placements.size(); i++) {
            final CardPanel panel = getCardPanels().get(i);
            final var p = placements.get(i);
            panel.setPresentationHitBounds(panel.isSelected() && strategy.hoverLift() > 0 ? p : null);
            panel.setPresentationAngle(p.angle());
            final int lift = panel.isSelected() ? (int) (p.height() * strategy.hoverLift()) : 0;
            if (panel != getMouseDragPanel()) { panel.setCardBounds(p.x(), p.y() - lift, p.width(), p.height()); }
            if (panel.getParent() == this) { setComponentZOrder(panel, 0); }
        }
        for (CardPanel panel : getCardPanels()) { if (panel.isSelected() && panel.getParent() == this && strategy.hoverLift() > 0) { setComponentZOrder(panel, 0); } }
        if (!extent.equals(getPreferredSize())) { setPreferredSize(extent); revalidate(); }
    }

    @Override
    protected boolean cardPanelDraggable(final CardPanel panel) {
        return panel.getCard() != null;
    }

    @Override
    protected Runnable getDropAction(final CardPanel panel, final MouseEvent evt) {
        if (!SwingUtilities.isLeftMouseButton(evt) || !getMatchUI().isCurrentScreen()
                || getMatchUI().getGameController() == null || !getCardPanels().contains(panel)
                || panel.getCard() == null || panel.getCard().getZone() != ZoneType.Hand
                || !getMatchUI().mayView(panel.getCard())) {
            return null;
        }
        // Use the visible viewport, not the full (possibly scrolled) battlefield contents.
        // This also accepts empty battlefields and adapts to any desktop layout.
        final boolean battlefield = getMatchUI().getFieldViews().stream().anyMatch(field ->
                CardDropTarget.contains(evt, field.getTabletop().getScrollPane().getViewport()));
        if (!battlefield) { return null; }
        return () -> {
            getMatchUI().setLastClickedCardPanel(panel);
            // Match a click on the source card, including popup positioning and modifiers.
            final MouseEvent click = new MouseEvent(this, MouseEvent.MOUSE_RELEASED, evt.getWhen(),
                    evt.getModifiersEx(), panel.getCardX() + panel.getCardWidth() / 2,
                    panel.getCardY() + panel.getCardHeight() / 2, 1, false, MouseEvent.BUTTON1);
            mouseLeftClicked(panel, click);
        };
    }

    /** {@inheritDoc} */
    @Override
    public final void mouseOver(final CardPanel panel, final MouseEvent evt) {
        getMatchUI().setCard(panel.getCard(), evt.isShiftDown());
        super.mouseOver(panel, evt);
    }

    /** {@inheritDoc} */
    @Override
    public final void mouseLeftClicked(final CardPanel panel, final MouseEvent evt) {
        getMatchUI().getGameController().selectCard(panel.getCard(), null, new MouseTriggerEvent(evt));
        super.mouseLeftClicked(panel, evt);
    }

    /** {@inheritDoc} */
    @Override
    public final void mouseRightClicked(final CardPanel panel, final MouseEvent evt) {
        getMatchUI().getGameController().selectCard(panel.getCard(), null, new MouseTriggerEvent(evt));
        super.mouseRightClicked(panel, evt);
    }
}
