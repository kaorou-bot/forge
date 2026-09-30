package forge.screens.match.layout;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.Map;

/** Receives only display data for a visible battlefield card, in card-local coordinates. */
@FunctionalInterface
public interface CardOverlayPainter {
    record Data(String powerToughness, Map<String, Integer> counters, int damage) {
        public Data { counters = Map.copyOf(counters); }
    }
    void paint(Graphics2D graphics, int width, int height, Data data);
    CardOverlayPainter NONE = (g, w, h, data) -> { };
    CardOverlayPainter BADGES = (g, w, h, data) -> {
        g.setFont(g.getFont().deriveFont(Math.max(11f, w * .15f)));
        final var metrics = g.getFontMetrics();
        if (!data.powerToughness().isEmpty()) {
            final int tw = metrics.stringWidth(data.powerToughness()) + 8;
            g.setColor(new Color(75, 75, 75, 230));
            g.fillRect(w - tw, h - metrics.getHeight(), tw, metrics.getHeight());
            g.setColor(Color.WHITE);
            g.drawString(data.powerToughness(), w - tw + 4, h - metrics.getDescent());
        }
        int y = 3;
        for (var entry : new java.util.TreeMap<>(data.counters()).entrySet()) {
            final String text = entry.getKey() + " " + entry.getValue();
            final int tw = metrics.stringWidth(text) + 8;
            g.setColor(new Color(30, 30, 30, 220));
            g.fillRoundRect(Math.max(0, w - tw), y, Math.min(w, tw), metrics.getHeight(), 8, 8);
            g.setColor(Color.WHITE);
            g.drawString(text, Math.max(2, w - tw + 4), y + metrics.getAscent());
            y += metrics.getHeight() + 2;
        }
    };
}
