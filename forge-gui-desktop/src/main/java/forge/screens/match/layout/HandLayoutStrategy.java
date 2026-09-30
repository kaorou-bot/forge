package forge.screens.match.layout;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/** Pure geometry hook: receives a count, never hidden card identities. Angles are in radians. */
@FunctionalInterface
public interface HandLayoutStrategy {
    List<Placement> arrange(int count, int width, int height, int maximumCardWidth);

    record Placement(int x, int y, int width, int height, double angle) {
        public boolean contains(double px, double py) {
            final double dx = px - x - width / 2.0, dy = py - y - height / 2.0;
            final double rx = dx * Math.cos(angle) + dy * Math.sin(angle);
            final double ry = -dx * Math.sin(angle) + dy * Math.cos(angle);
            return Math.abs(rx) < width / 2.0 && Math.abs(ry) < height / 2.0;
        }
        public Point2D corner(double dx, double dy) {
            return new Point2D.Double(x + width / 2.0 + dx * Math.cos(angle) - dy * Math.sin(angle),
                    y + height / 2.0 + dx * Math.sin(angle) + dy * Math.cos(angle));
        }
    }

    static HandLayoutStrategy fan(double spreadDegrees) {
        if (!Double.isFinite(spreadDegrees) || spreadDegrees < 0 || spreadDegrees > 60) {
            throw new IllegalArgumentException("fanDegrees must be between 0 and 60");
        }
        return (count, width, height, maxWidth) -> {
            if (count <= 0 || width <= 0 || height <= 0) { return List.of(); }
            // A diagonal-sized margin fits every rotation. Arc rise uses the remaining height.
            final double ratio = 1.4;
            final int cw = Math.max(1, Math.min(maxWidth, (int) Math.min(width / Math.hypot(1, ratio), height / 2.1)));
            final int ch = Math.max(1, (int) Math.round(cw * ratio));
            final double diagonal = Math.hypot(cw, ch);
            final double span = Math.max(0, Math.min(width - diagonal - 4, (count - 1.0) * cw * .8));
            final double rise = Math.max(0, height - diagonal - 4);
            final List<Placement> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final double t = count == 1 ? 0 : 2.0 * i / (count - 1) - 1;
                result.add(new Placement((int) Math.round(width / 2.0 + t * span / 2 - cw / 2.0),
                        (int) Math.round(diagonal / 2 + 2 + rise * t * t - ch / 2.0), cw, ch,
                        Math.toRadians(spreadDegrees * t / 2)));
            }
            return List.copyOf(result);
        };
    }
}
