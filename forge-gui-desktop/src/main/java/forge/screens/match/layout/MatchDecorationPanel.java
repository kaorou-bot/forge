package forge.screens.match.layout;

import java.awt.Graphics;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JPanel;

/** Cached artwork only: never owns mouse input or consults the game model. */
final class MatchDecorationPanel extends JPanel {
    private final MatchSkinTheme theme;
    private final boolean foreground;
    private final List<MatchUiLayout.Bounds> protectedAreas;
    private BufferedImage cache;
    MatchDecorationPanel(MatchSkinTheme theme, boolean foreground, List<MatchUiLayout.Bounds> protectedAreas) {
        this.theme = theme; this.foreground = foreground; this.protectedAreas = List.copyOf(protectedAreas); setOpaque(false);
    }
    @Override public boolean contains(int x, int y) { return false; }
    @Override protected void paintComponent(Graphics graphics) {
        if (theme == null || getWidth() <= 0 || getHeight() <= 0) { return; }
        if (cache == null || cache.getWidth() != getWidth() || cache.getHeight() != getHeight()) {
            final int width = getWidth(), height = getHeight();
            cache = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            final var g = cache.createGraphics();
            try {
                if (!foreground && theme.backgroundTexture() != null) { theme.backgroundTexture().paint(g, 0, 0, width, height); }
                if (foreground) {
                    final var clip = new Area(new java.awt.Rectangle(0, 0, width, height));
                    for (var b : protectedAreas) { clip.subtract(new Area(new java.awt.geom.Rectangle2D.Double(b.x() * width, b.y() * height, b.w() * width, b.h() * height))); }
                    g.clip(clip);
                }
                for (var decoration : theme.decorations()) {
                    if (decoration.foreground() != foreground) { continue; }
                    final var b = decoration.bounds();
                    final int x = (int) (b.x() * width), y = (int) (b.y() * height), w = (int) (b.w() * width), h = (int) (b.h() * height);
                    final var item = (java.awt.Graphics2D) g.create();
                    try {
                        item.clipRect(x, y, w, h);
                        item.setComposite(java.awt.AlphaComposite.SrcOver.derive(decoration.opacity()));
                        item.rotate(Math.toRadians(decoration.rotation()), x + w / 2.0, y + h / 2.0);
                        decoration.texture().paint(item, x, y, w, h);
                    } finally { item.dispose(); }
                }
            } finally { g.dispose(); }
        }
        graphics.drawImage(cache, 0, 0, null);
    }
}
