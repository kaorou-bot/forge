package forge.screens.match.layout;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;

/** v4 visual vocabulary. Does not change the live component, its text, or its input listeners. */
public record MatchSkinVisual(ShapeKind shape, float opacity, float borderWidth, MatchSkinTexture texture,
        MatchSkinTexture icon, MatchUiLayout.Bounds iconBounds, MatchUiLayout.Bounds textBounds,
        Color textColor, boolean showText, Align textAlign, MatchSkinTexture frame,
        Map<String, MatchSkinTheme.Style> states, java.util.List<java.awt.geom.Point2D.Double> polygon,
        Map<String, MatchSkinTexture> markers) {
    public MatchSkinVisual(ShapeKind shape, float opacity, float borderWidth, MatchSkinTexture texture,
            MatchSkinTexture icon, MatchUiLayout.Bounds iconBounds, MatchUiLayout.Bounds textBounds, Color textColor,
            boolean showText, Align textAlign, MatchSkinTexture frame, Map<String, MatchSkinTheme.Style> states) {
        this(shape, opacity, borderWidth, texture, icon, iconBounds, textBounds, textColor, showText, textAlign, frame, states, java.util.List.of(), Map.of());
    }
    public enum ShapeKind { RECTANGLE, ROUNDED, CIRCLE, ELLIPSE, HEXAGON, DIAMOND, POLYGON }
    public enum Align { LEFT, CENTER, RIGHT }

    public Shape outline(int w, int h, int radius) {
        return switch (shape) {
            case RECTANGLE -> new java.awt.Rectangle(0, 0, w, h);
            case ROUNDED -> new RoundRectangle2D.Double(0, 0, w, h, radius, radius);
            case ELLIPSE -> new Ellipse2D.Double(0, 0, w, h);
            case CIRCLE -> { final int d = Math.min(w, h); yield new Ellipse2D.Double((w - d) / 2.0, (h - d) / 2.0, d, d); }
            case POLYGON -> {
                final Path2D path = new Path2D.Double();
                for (int i = 0; i < polygon.size(); i++) { final var p = polygon.get(i); if (i == 0) { path.moveTo(p.x * w, p.y * h); } else { path.lineTo(p.x * w, p.y * h); } }
                path.closePath(); yield path;
            }
            case HEXAGON, DIAMOND -> {
                final Path2D path = new Path2D.Double();
                final double[][] pts = shape == ShapeKind.DIAMOND ? new double[][]{{.5,0},{1,.5},{.5,1},{0,.5}}
                        : new double[][]{{.25,0},{.75,0},{1,.5},{.75,1},{.25,1},{0,.5}};
                path.moveTo(pts[0][0] * w, pts[0][1] * h);
                for (int i = 1; i < pts.length; i++) { path.lineTo(pts[i][0] * w, pts[i][1] * h); }
                path.closePath(); yield path;
            }
        };
    }

    /** Returns true only when this style draws the label content itself. Numeric text stays live. */
    public boolean paintMarker(Graphics2D g, String id, int width, int height) {
        final var texture = markers.get(id); if (texture == null) { return false; }
        final int size = Math.max(6, Math.min(14, Math.min(width / 3, height / 3)));
        texture.paint(g, id.equals("active") ? 2 : Math.max(0, width - size - 2), id.equals("yield") ? Math.max(0, height - size - 2) : 2, size, size);
        return true;
    }

    public boolean paintContent(Graphics2D source, int w, int h, String text, boolean force) {
        if (!force && icon == null && textBounds == null && textColor == null && showText && textAlign == Align.CENTER) { return false; }
        final var g = (Graphics2D) source.create();
        try {
            if (icon != null) {
                final var b = iconBounds == null ? new MatchUiLayout.Bounds(0, 0, 1, .65) : iconBounds;
                icon.paint(g, (int) (b.x() * w), (int) (b.y() * h), (int) (b.w() * w), (int) (b.h() * h));
            }
            if (showText && text != null && !text.isEmpty()) {
                final var b = textBounds == null ? (icon == null ? new MatchUiLayout.Bounds(0, 0, 1, 1)
                        : new MatchUiLayout.Bounds(0, .65, 1, .35)) : textBounds;
                final int x = (int) (b.x() * w), y = (int) (b.y() * h), width = (int) (b.w() * w), height = (int) (b.h() * h);
                g.clipRect(x, y, width, height);
                if (textColor != null) { g.setColor(textColor); }
                final var fm = g.getFontMetrics();
                final int tx = switch (textAlign) { case LEFT -> x + 2; case RIGHT -> x + width - fm.stringWidth(text) - 2;
                    case CENTER -> x + (width - fm.stringWidth(text)) / 2; };
                g.drawString(text, tx, y + (height - fm.getHeight()) / 2 + fm.getAscent());
            }
            return true;
        } finally { g.dispose(); }
    }
}
