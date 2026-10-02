package forge.screens.match.layout;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Places complete stacks, preserving equipment, auras, grouping and existing input handlers. */
@FunctionalInterface
public interface BattlefieldLayoutStrategy {
    enum Kind { CREATURE, LAND, OTHER }
    record Group(int width, int height, Kind kind) { }
    /** Includes space for tapped cards and attachments, not just the upright face. */
    record Placement(int x, int y, int width, int height, double scale) {
        public Rectangle cardBounds(int relativeX, int relativeY, int cardWidth, int cardHeight) {
            return new Rectangle(x + (int) Math.floor(relativeX * scale), y + (int) Math.floor(relativeY * scale),
                    Math.max(1, (int) Math.floor(cardWidth * scale)), Math.max(1, (int) Math.floor(cardHeight * scale)));
        }
    }
    /** Arrange already width-fitted groups. Runtime callers should use placements(). */
    List<Point> arrange(List<Group> groups, int width, int height, boolean opponent);

    default int maximumGroupWidth(Kind kind, int width) { return Integer.MAX_VALUE; }
    default boolean fitRotatedBounds() { return false; }
    /** This strategy fits the actual stacks itself; do not shrink them with the classic row planner first. */
    default boolean sizesFromContent() { return false; }

    /** Shrink only oversized complete stacks; existing strategies retain their exact dimensions. */
    default List<Placement> placements(List<Group> groups, int width, int height, boolean opponent) {
        final var fitted = new ArrayList<Group>();
        final var scales = new ArrayList<Double>();
        for (Group group : groups) {
            final int limit = Math.max(1, maximumGroupWidth(group.kind(), width));
            final double scale = Math.min(1, limit / (double) Math.max(1, group.width()));
            fitted.add(new Group(Math.min(limit, Math.max(1, (int) Math.ceil(group.width() * scale))),
                    Math.max(1, (int) Math.ceil(group.height() * scale)), group.kind()));
            scales.add(scale);
        }
        final var points = arrange(List.copyOf(fitted), width, height, opponent);
        if (points.size() != groups.size()) { throw new IllegalArgumentException("Expected one position per battlefield stack"); }
        final var result = new ArrayList<Placement>();
        for (int i = 0; i < points.size(); i++) {
            result.add(new Placement(points.get(i).x, points.get(i).y, fitted.get(i).width(), fitted.get(i).height(), scales.get(i)));
        }
        return List.copyOf(result);
    }

    static BattlefieldLayoutStrategy adaptive(boolean centered, int rowGap, int groupGap) {
        return new AdaptiveBattlefieldLayout(centered, rowGap, groupGap);
    }

    /** Default budget reserves two rows; providers may override it for a different composition. */
    default int maximumCardWidth(int width, int height, int preferredMaximum, int minimum) {
        return Math.max(minimum, Math.min(preferredMaximum, (int) ((height / 2.0 - 12) / (1.4 * 1.06))));
    }

    BattlefieldLayoutStrategy LANES = (groups, width, height, opponent) -> {
        final List<Point> result = new ArrayList<>(Collections.nCopies(groups.size(), null));
        final int mainWidth = Math.max(Math.max(1, (int) (width * .76)), groups.stream()
                .filter(g -> g.kind() != Kind.OTHER).mapToInt(Group::width).max().orElse(1));
        final Kind first = opponent ? Kind.LAND : Kind.CREATURE;
        final Kind second = opponent ? Kind.CREATURE : Kind.LAND;
        final int firstBottom = flow(groups, result, first, 4, 4, mainWidth);
        flow(groups, result, second, 4, Math.max(height / 2, firstBottom + 4), mainWidth);
        flow(groups, result, Kind.OTHER, mainWidth + 4, 4, Math.max(1, width - mainWidth - 8));
        return List.copyOf(result);
    };

    /** Full-width rows: creatures center around the battlefield, not the edge of a side column. */
    static BattlefieldLayoutStrategy rows(boolean centered, int rowGap, int groupGap) {
        if (rowGap < 0 || rowGap > 80 || groupGap < 0 || groupGap > 80) { throw new IllegalArgumentException("Invalid battlefield gaps"); }
        return (groups, width, height, opponent) -> {
            final var result = new ArrayList<Point>(Collections.nCopies(groups.size(), null));
            final Kind first = opponent ? Kind.LAND : Kind.CREATURE;
            final Kind second = opponent ? Kind.CREATURE : Kind.LAND;
            final int end = rowsOf(groups, result, first, 4, Math.max(1, width - 8), centered && first == Kind.CREATURE, rowGap, groupGap);
            final int bottom = rowsOf(groups, result, second, Math.max(height / 2, end + rowGap), Math.max(1, width - 8), centered && second == Kind.CREATURE, rowGap, groupGap);
            rowsOf(groups, result, Kind.OTHER, bottom + rowGap, Math.max(1, width - 8), false, rowGap, groupGap);
            return List.copyOf(result);
        };
    }
    private static int rowsOf(List<Group> groups, List<Point> result, Kind kind, int top, int width, boolean center, int rowGap, int gap) {
        final var row = new ArrayList<Integer>();
        int used = 0, rowHeight = 0, y = top;
        for (int i = 0; i <= groups.size(); i++) {
            if (i < groups.size() && groups.get(i).kind() != kind) { continue; }
            if (!row.isEmpty() && (i == groups.size() || used + gap + groups.get(i).width() > width)) {
                int x = 4 + (center ? Math.max(0, (width - used) / 2) : 0);
                for (int index : row) { result.set(index, new Point(x, y)); x += groups.get(index).width() + gap; }
                y += rowHeight + rowGap; row.clear(); used = 0; rowHeight = 0;
            }
            if (i < groups.size()) {
                used += (row.isEmpty() ? 0 : gap) + groups.get(i).width(); row.add(i); rowHeight = Math.max(rowHeight, groups.get(i).height());
            }
        }
        return y == top ? top : y - rowGap;
    }

    private static int flow(List<Group> groups, List<Point> result, Kind kind, int left, int top, int width) {
        int x = left, y = top, rowHeight = 0;
        for (int i = 0; i < groups.size(); i++) {
            final Group group = groups.get(i);
            if (group.kind() != kind) { continue; }
            if (x > left && x + group.width() > left + width) { x = left; y += rowHeight; rowHeight = 0; }
            result.set(i, new Point(x, y));
            x += group.width();
            rowHeight = Math.max(rowHeight, group.height());
        }
        return y + rowHeight;
    }
}
