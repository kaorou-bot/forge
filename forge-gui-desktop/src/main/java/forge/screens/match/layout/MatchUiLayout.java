package forge.screens.match.layout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated, renderer-independent desktop layout description. Coordinates are relative to the host. */
public record MatchUiLayout(String id, List<Region> regions, MatchFieldLayout fieldLayout) {
    public record Bounds(double x, double y, double w, double h) {
        public Bounds {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(w) || !Double.isFinite(h)
                    || x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > 1.000001 || y + h > 1.000001) {
                throw new IllegalArgumentException("Bounds must be finite, positive and inside [0, 1]");
            }
        }
        boolean overlaps(Bounds b) {
            return Math.min(x + w, b.x + b.w) - Math.max(x, b.x) > 0.000001
                    && Math.min(y + h, b.y + b.h) - Math.max(y, b.y) > 0.000001;
        }
    }
    public enum Split { TABS, COLUMNS, ROWS, GRID }
    public record Region(Bounds bounds, List<String> documents, Split split) {
        public Region {
            documents = List.copyOf(documents);
            if (documents.isEmpty() || bounds == null || split == null) {
                throw new IllegalArgumentException("Each region needs bounds, documents and split");
            }
            for (String selector : documents) {
                if (!isSelector(selector)) {
                    throw new IllegalArgumentException("Unknown match document selector: " + selector);
                }
            }
        }
    }
    public record Cell(Bounds bounds, List<String> documents) {
        public Cell { documents = List.copyOf(documents); }
    }

    public MatchUiLayout {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}") || fieldLayout == null) {
            throw new IllegalArgumentException("Invalid layout id or field layout");
        }
        regions = List.copyOf(regions);
        if (!regions.isEmpty()) {
            validateBounds(regions.stream().map(Region::bounds).toList(), true);
        }
    }

    public static MatchUiLayout classic() {
        return new MatchUiLayout("classic", List.of(), MatchFieldLayout.CLASSIC);
    }

    public boolean isClassic() { return regions.isEmpty(); }

    /** Pure planning step: fails before any live Swing component is detached. */
    public List<Cell> arrange(final List<String> available) {
        if (isClassic()) { return List.of(); }
        final Set<String> used = new HashSet<>();
        final List<Cell> result = new ArrayList<>();
        for (Region region : regions) {
            final Set<String> selected = new LinkedHashSet<>();
            for (String selector : region.documents()) {
                for (String doc : available) {
                    final boolean matches = switch (selector) {
                        case "fields" -> doc.startsWith("FIELD_");
                        case "opponents" -> doc.startsWith("FIELD_") && !doc.equals("FIELD_0");
                        case "hands" -> doc.startsWith("HAND_");
                        case "remaining" -> !used.contains(doc) && !selected.contains(doc);
                        default -> selector.equals(doc);
                    };
                    if (matches && (!selected.add(doc) || used.contains(doc))) {
                        throw new IllegalArgumentException("Document assigned more than once: " + doc);
                    }
                }
            }
            final List<String> docs = List.copyOf(selected);
            used.addAll(docs);
            if (region.split() == Split.TABS || docs.isEmpty()) {
                result.add(new Cell(region.bounds(), docs));
            } else {
                final int columns = switch (region.split()) {
                    case COLUMNS -> docs.size();
                    case GRID -> (int) Math.ceil(Math.sqrt(docs.size()));
                    default -> 1;
                };
                final int rows = (docs.size() + columns - 1) / columns;
                for (int i = 0; i < docs.size(); i++) {
                    final int row = i / columns;
                    final int countInRow = Math.min(columns, docs.size() - row * columns);
                    final Bounds b = region.bounds();
                    result.add(new Cell(new Bounds(b.x() + (i % columns) * b.w() / countInRow,
                            b.y() + row * b.h() / rows, b.w() / countInRow, b.h() / rows), List.of(docs.get(i))));
                }
            }
        }
        if (used.size() != available.size()) {
            final Set<String> missing = new LinkedHashSet<>(available);
            missing.removeAll(used);
            throw new IllegalArgumentException("Layout omits documents: " + missing);
        }
        for (Cell cell : result) {
            if (cell.documents().contains("REPORT_MESSAGE") && cell.documents().size() != 1) {
                throw new IllegalArgumentException("REPORT_MESSAGE must have its own cell so actions remain accessible");
            }
        }
        return List.copyOf(result);
    }

    public static MatchUiLayout read(final Reader reader) {
        final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        keys(root, Set.of("version", "id", "regions", "field"));
        if (!root.has("version") || !root.get("version").getAsString().equals("1")) {
            throw new IllegalArgumentException("Unsupported match UI version (expected 1)");
        }
        final List<Region> regions = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("regions")) {
            final JsonObject region = element.getAsJsonObject();
            keys(region, Set.of("bounds", "documents", "split"));
            final List<String> documents = new ArrayList<>();
            for (JsonElement doc : region.getAsJsonArray("documents")) { documents.add(doc.getAsString()); }
            regions.add(new Region(bounds(region.get("bounds")), documents,
                    region.has("split") ? Split.valueOf(region.get("split").getAsString()) : Split.TABS));
        }
        if (regions.isEmpty() || regions.size() > 64) {
            throw new IllegalArgumentException("Expected 1 to 64 regions");
        }
        MatchFieldLayout field = MatchFieldLayout.CLASSIC;
        if (root.has("field")) {
            final Map<MatchFieldLayout.Part, Bounds> parts = new EnumMap<>(MatchFieldLayout.Part.class);
            for (var entry : root.getAsJsonObject("field").entrySet()) {
                parts.put(MatchFieldLayout.Part.valueOf(entry.getKey()), bounds(entry.getValue()));
            }
            validateBounds(new ArrayList<>(parts.values()), false);
            field = MatchFieldLayout.relative(parts);
        }
        return new MatchUiLayout(root.get("id").getAsString(), regions, field);
    }

    private static Bounds bounds(JsonElement element) {
        final var array = element.getAsJsonArray();
        if (array.size() != 4) { throw new IllegalArgumentException("bounds must be [x,y,width,height]"); }
        return new Bounds(array.get(0).getAsDouble(), array.get(1).getAsDouble(),
                array.get(2).getAsDouble(), array.get(3).getAsDouble());
    }

    private static void validateBounds(List<Bounds> bounds, boolean cover) {
        double area = 0;
        for (int i = 0; i < bounds.size(); i++) {
            area += bounds.get(i).w() * bounds.get(i).h();
            for (int j = 0; j < i; j++) {
                if (bounds.get(i).overlaps(bounds.get(j))) {
                    throw new IllegalArgumentException("Layout regions must not overlap");
                }
            }
        }
        if (cover && Math.abs(area - 1) > 0.00001) {
            throw new IllegalArgumentException("Layout regions must cover the whole screen");
        }
    }

    private static void keys(JsonObject object, Set<String> allowed) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) { throw new IllegalArgumentException("Unknown layout property: " + key); }
        }
    }

    private static boolean isSelector(String value) {
        return Set.of("fields", "opponents", "hands", "remaining", "CARD_PICTURE", "CARD_DETAIL",
                "REPORT_MESSAGE", "REPORT_STACK", "REPORT_COMBAT", "REPORT_DEPENDENCIES", "REPORT_LOG",
                "DEV_MODE", "BUTTON_DOCK").contains(value)
                || value.matches("(?:FIELD|HAND)_[0-7]");
    }
}
