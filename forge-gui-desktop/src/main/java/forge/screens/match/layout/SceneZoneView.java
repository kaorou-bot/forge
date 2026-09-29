package forge.screens.match.layout;

import forge.ImageCache;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.screens.match.CMatchUI;
import forge.screens.match.ZoneAction;
import forge.toolbox.FSkin;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;

/** Public zone summaries. Hidden hands use only their count and a generic sleeve. */
final class SceneZoneView extends JComponent {
    private static final ZoneType[] ZONES = {ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile};
    private final CMatchUI match;
    private final PlayerView player;
    private final boolean hand;

    SceneZoneView(CMatchUI match, PlayerView player, boolean hand) {
        this.match = match;
        this.player = player;
        this.hand = hand;
        setToolTipText(player.getName());
        if (!hand) {
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) { return; }
                    final int index = Math.min(2, e.getX() * 3 / Math.max(1, getWidth()));
                    if (index > 0) { new ZoneAction(match, player, ZONES[index]).run(); }
                    else if (match.getLocalPlayers().contains(player)) { match.viewDeckList(); }
                }
            });
        }
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            if (hand) {
                // A real, permitted hand always wins over a decorative back-count widget.
                if (match.getHandViews().stream().anyMatch(view -> view.getPlayer().equals(player))) { return; }
                // Never query the hand's cards, even if a debug or spectator mode can see them.
                final int count = player.getZoneSize(ZoneType.Hand);
                for (var p : HandLayoutStrategy.fan(28).arrange(Math.min(count, 30), getWidth(), getHeight(), 180)) {
                    final Graphics2D card = (Graphics2D) g.create();
                    card.rotate(p.angle(), p.x() + p.width() / 2.0, p.y() + p.height() / 2.0);
                    card.drawImage(FSkin.getSleeveImage(0), p.x(), p.y(), p.width(), p.height(), null);
                    card.dispose();
                }
                FSkin.setGraphicsColor(g, FSkin.getColor(FSkin.Colors.CLR_TEXT));
                g.drawString(ZoneType.Hand.getTranslatedName() + ": " + count, 4, 14);
                return;
            }
            final int slot = getWidth() / 3;
            final int height = Math.max(1, getHeight() - 22);
            final int width = Math.max(1, Math.min(slot - 8, (int) (height / 1.4)));
            for (int i = 0; i < ZONES.length; i++) {
                final ZoneType zone = ZONES[i];
                final int x = i * slot + 4;
                java.awt.Image image = FSkin.getSleeveImage(0);
                if (zone != ZoneType.Library) {
                    final var cards = player.getCards(zone);
                    if (cards != null && !cards.isEmpty()) {
                        final var top = cards.get(cards.size() - 1);
                        if (match.mayView(top)) { image = ImageCache.getImage(top, match.getLocalPlayers(), width, height); }
                    }
                }
                if (player.getZoneSize(zone) > 0) { g.drawImage(image, x, 21, width, height, null); }
                g.setColor(new Color(0, 0, 0, 160));
                g.fillRect(x, 0, Math.max(1, slot - 8), 21);
                g.setColor(Color.WHITE);
                g.drawString(zone.getTranslatedName() + ":" + player.getZoneSize(zone), x + 2, 15);
            }
        } finally { g.dispose(); }
    }
}
