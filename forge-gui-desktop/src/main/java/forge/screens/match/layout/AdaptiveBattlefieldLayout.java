package forge.screens.match.layout;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Creatures face the opposing battlefield; lands and other permanents share a split rear band. */
final class AdaptiveBattlefieldLayout implements BattlefieldLayoutStrategy {
    private final boolean centered;
    private final int rowGap;
    private final int groupGap;
    private final double partition;
    private final boolean landsRight;

    AdaptiveBattlefieldLayout(boolean centered, int rowGap, int groupGap) {
        this(centered, rowGap, groupGap, .5, false);
    }
    AdaptiveBattlefieldLayout(boolean centered, int rowGap, int groupGap, double partition, boolean landsRight) {
        if (!Double.isFinite(partition) || partition < .3 || partition > .7) { throw new IllegalArgumentException("Partition must be 0.3..0.7"); }
        this.partition = partition; this.landsRight = landsRight;
        if (rowGap < 0 || rowGap > 80 || groupGap < 0 || groupGap > 80) { throw new IllegalArgumentException("Invalid battlefield gaps"); }
        this.centered = centered;
        this.rowGap = rowGap;
        this.groupGap = groupGap;
    }

    private record Band(int left, int width) { }

    private Band band(Kind kind, int width) {
        final int canvas = Math.max(2, width);
        final int margin = Math.min(4, (canvas - 2) / 4);
        final int middle = Math.max(1, Math.min(canvas - 1, (int) (canvas * partition)));
        final int halfGap = Math.min(Math.max(4, groupGap / 2), Math.max(0, Math.min(middle, canvas - middle) - margin - 1));
        final Kind effective = landsRight ? kind == Kind.LAND ? Kind.OTHER : kind == Kind.OTHER ? Kind.LAND : kind : kind;
        return switch (effective) {
            case CREATURE -> new Band(margin, canvas - 2 * margin);
            case LAND -> new Band(margin, middle - halfGap - margin);
            case OTHER -> new Band(middle + halfGap, canvas - margin - middle - halfGap);
        };
    }

    @Override public int maximumGroupWidth(Kind kind, int width) { return band(kind, width).width(); }
    @Override public boolean fitRotatedBounds() { return true; }

    @Override public int maximumCardWidth(int width, int height, int maximum, int minimum) {
        // CardPanel reserves the whole tapping-rotation envelope (~1.75 * face width).
        // Budgeting only the upright 1.4 aspect ratio makes even two sparse rows overflow.
        return Math.max(minimum, Math.min(maximum, (int) ((height - 8 - rowGap) / 3.5)));
    }

    @Override public List<Placement> placements(List<Group> groups, int width, int height, boolean opponent) {
        final var original = BattlefieldLayoutStrategy.super.placements(groups, width, height, opponent);
        if (fitsHeight(original, height)) { return original; }
        // Allow modest shrinking for attachments/wrapped rows, but keep a crowded board
        // scrollable rather than shrinking arbitrarily many permanents into unreadable dots.
        double low = .65, high = 1;
        var best = scaled(groups, original, width, height, opponent, low);
        if (!fitsHeight(best, height)) { return best; }
        for (int i = 0; i < 20; i++) {
            final double scale = (low + high) / 2;
            final var candidate = scaled(groups, original, width, height, opponent, scale);
            if (fitsHeight(candidate, height)) { low = scale; best = candidate; }
            else { high = scale; }
        }
        return best;
    }

    private static boolean fitsHeight(List<Placement> placements, int height) {
        return placements.stream().allMatch(p -> p.y() + p.height() <= height - 4);
    }

    private List<Placement> scaled(List<Group> source, List<Placement> original, int width, int height, boolean opponent, double scale) {
        // Keep the original kind/order, including creature-lands and attachment stacks.
        final var groups = new ArrayList<Group>();
        for (int i = 0; i < original.size(); i++) {
            final var p = original.get(i);
            groups.add(new Group(Math.max(1, (int) Math.ceil(p.width() * scale)),
                    Math.max(1, (int) Math.ceil(p.height() * scale)), source.get(i).kind()));
        }
        final var points = arrange(groups, width, height, opponent);
        final var result = new ArrayList<Placement>();
        for (int i = 0; i < original.size(); i++) {
            result.add(new Placement(points.get(i).x, points.get(i).y, groups.get(i).width(),
                    groups.get(i).height(), original.get(i).scale() * scale));
        }
        return List.copyOf(result);
    }

    @Override public List<Point> arrange(List<Group> groups, int width, int height, boolean opponent) {
        final var result = new ArrayList<Point>(Collections.nCopies(groups.size(), null));
        if (opponent) {
            final int rearBottom = rear(groups, result, width, 4);
            rows(groups, result, Kind.CREATURE, band(Kind.CREATURE, width), rearBottom + (rearBottom > 4 ? rowGap : 0));
            // Face the opposing battlefield without inserting empty space BETWEEN rows.
            int bottom = 4;
            for (int i = 0; i < groups.size(); i++) { bottom = Math.max(bottom, result.get(i).y + groups.get(i).height()); }
            final int offset = Math.max(0, height - 4 - bottom);
            result.forEach(p -> p.translate(0, offset));
        } else {
            final int creaturesBottom = rows(groups, result, Kind.CREATURE, band(Kind.CREATURE, width), 4);
            rear(groups, result, width, creaturesBottom + (creaturesBottom > 4 ? rowGap : 0));
        }
        return List.copyOf(result);
    }

    private int rear(List<Group> groups, List<Point> result, int width, int top) {
        final int landsBottom = rows(groups, result, Kind.LAND, band(Kind.LAND, width), top);
        final int othersBottom = rows(groups, result, Kind.OTHER, band(Kind.OTHER, width), top);
        return Math.max(landsBottom, othersBottom);
    }

    private int rows(List<Group> groups, List<Point> result, Kind kind, Band band, int top) {
        final var row = new ArrayList<Integer>();
        int used = 0, rowHeight = 0, y = top;
        for (int i = 0; i <= groups.size(); i++) {
            if (i < groups.size() && groups.get(i).kind() != kind) { continue; }
            if (!row.isEmpty() && (i == groups.size() || used + groupGap + groups.get(i).width() > band.width())) {
                final int spare = Math.max(0, band.width() - used);
                int x = band.left() + (kind == Kind.OTHER ? spare : kind == Kind.CREATURE && centered ? spare / 2 : 0);
                for (int index : row) {
                    result.set(index, new Point(x, y));
                    x += groups.get(index).width() + groupGap;
                }
                y += rowHeight + rowGap;
                row.clear(); used = 0; rowHeight = 0;
            }
            if (i < groups.size()) {
                used += (row.isEmpty() ? 0 : groupGap) + groups.get(i).width();
                row.add(i);
                rowHeight = Math.max(rowHeight, groups.get(i).height());
            }
        }
        return y == top ? top : y - rowGap;
    }
}
