package forge.screens.match.layout;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Places complete stacks, preserving equipment, auras, grouping and existing input handlers. */
@FunctionalInterface
public interface BattlefieldLayoutStrategy {
    enum Kind { CREATURE, LAND, OTHER }
    record Group(int width, int height, Kind kind) { }
    List<Point> arrange(List<Group> groups, int width, int height, boolean opponent);

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
