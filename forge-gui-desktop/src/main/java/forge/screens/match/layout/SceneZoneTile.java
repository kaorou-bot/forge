package forge.screens.match.layout;

import forge.ImageCache;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.screens.match.CMatchUI;
import forge.screens.match.ZoneAction;
import forge.toolbox.FSkin;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;

/** One independently placeable zone control. A pile and a text button share the same safe action. */
final class SceneZoneTile extends JComponent {
    private final CMatchUI match;
    private final PlayerView player;
    private final ZoneType zone;
    private final boolean pile;
    private boolean hovered, pressed;
    SceneZoneTile(CMatchUI match, PlayerView player, ZoneType zone, boolean pile) {
        this.match = match; this.player = player; this.zone = zone; this.pile = pile;
        setToolTipText(player.getName() + " · " + zone.getTranslatedName());
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hovered = false; pressed = false; repaint(); }
            @Override public void mousePressed(MouseEvent e) { pressed = javax.swing.SwingUtilities.isLeftMouseButton(e); repaint(); }
            @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
            @Override public void mouseClicked(MouseEvent e) {
                if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    match.getFieldViewFor(player).getLayoutControl().showZoneMenu(zone, e);
                    return;
                }
                if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) { return; }
                if (zone == ZoneType.Library) {
                    if (match.isLocalPlayer(player)) { match.viewDeckList(); }
                } else { new ZoneAction(match, player, zone).run(); }
            }
        });
    }
    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        final int count = player.getZoneSize(zone);
        if (getClientProperty(MatchSkinTheme.STYLE_PROPERTY) instanceof MatchSkinTheme.Style base && base.visual() != null
                && base.state(isEnabled(), hovered, pressed, false).visual().icon() != null) {
            final var style = base.state(isEnabled(), hovered, pressed, false);
            final var graphics = (java.awt.Graphics2D) g.create();
            try {
                graphics.setFont(getFont().deriveFont(style.fontSize())); graphics.setColor(getForeground());
                style.paint(graphics, getWidth(), getHeight(), hovered, pressed);
                style.visual().paintContent(graphics, getWidth(), getHeight(), zone.getTranslatedName() + " · " + count, true);
                style.paintFrame(graphics, getWidth(), getHeight());
            } finally { graphics.dispose(); }
            return; // Custom icon replaces preview, never requests hidden identities.
        }
        if (pile && count > 0) {
            final int height = Math.max(1, getHeight() - 24);
            final int width = Math.max(1, Math.min(getWidth() - 4, (int) (height / 1.4)));
            java.awt.Image image = FSkin.getSleeveImage(0);
            // Do not even request hidden zone card identities to draw a count/back.
            if (zone != ZoneType.Library && zone != ZoneType.Hand) {
                final var cards = player.getCards(zone);
                if (cards != null && !cards.isEmpty()) {
                    final var top = cards.get(cards.size() - 1);
                    if (match.mayView(top)) { image = ImageCache.getImage(top, match.getLocalPlayers(), width, height); }
                }
            }
            g.drawImage(image, (getWidth() - width) / 2, 24, width, height, null);
        }
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRoundRect(0, 0, getWidth(), pile ? 23 : getHeight(), 10, 10);
        g.setColor(getForeground() == null ? Color.WHITE : getForeground());
        if (getFont() != null) { g.setFont(getFont()); }
        g.drawString(zone.getTranslatedName() + " · " + count, 5, pile ? 17 : (getHeight() + g.getFontMetrics().getAscent()) / 2 - 2);
    }
}
