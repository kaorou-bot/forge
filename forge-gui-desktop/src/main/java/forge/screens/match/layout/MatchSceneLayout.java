package forge.screens.match.layout;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent live widgets and reversible surface decoration in screen coordinates. */
public record MatchSceneLayout(Map<String, MatchUiLayout.Bounds> widgets, MatchSurfaceStyle surface,
        Map<String, MatchSurfaceStyle> styles, Map<String, String> renderers) {
    public MatchSceneLayout(Map<String, MatchUiLayout.Bounds> widgets) {
        this(widgets, MatchSurfaceStyle.CLEAR, Map.of(), Map.of());
    }
    public MatchSceneLayout {
        widgets = Map.copyOf(widgets);
        styles = Map.copyOf(styles);
        renderers = Map.copyOf(renderers);
        if (!widgets.containsKey("PHASES_ACTIVE")) { throw new IllegalArgumentException("scene needs PHASES_ACTIVE"); }
        for (String id : widgets.keySet()) {
            if (id.equals("PHASES_ACTIVE")) { continue; }
            final boolean player = id.matches("FIELD_[0-7]\\.[A-Z][A-Z0-9_]*");
            final String type = player ? id.substring(id.indexOf('.') + 1) : id;
            final boolean global = type.equals("STACK_STATUS") || type.equals("ACTIONS_MENU") || type.startsWith("ACTION_");
            if (!MatchWidgetRegistry.contains(type) || (!type.startsWith("CUSTOM_") && player == global)
                    || type.equals("ZONE_PILE") || type.equals("ZONE_BUTTON")) {
                throw new IllegalArgumentException("Unknown or incorrectly scoped scene widget: " + id);
            }
        }
        for (int i = 0; i < 8; i++) {
            final String prefix = "FIELD_" + i + ".";
            final var types = widgets.keySet().stream().filter(k -> k.startsWith(prefix)).map(k -> k.substring(prefix.length())).toList();
            if ((types.contains("AVATAR") && (types.contains("AVATAR_IMAGE") || types.contains("LIFE") || types.contains("STATUS")))
                    || (types.contains("DETAILS") && types.stream().anyMatch(t -> t.startsWith("MANA") || t.startsWith("ZONE") || t.equals("OTHER_ZONES")))
                    || (types.contains("ZONES") && types.stream().anyMatch(t -> t.startsWith("ZONE_")))
                    || (types.contains("MANA") && types.stream().anyMatch(t -> t.startsWith("MANA_")))) {
                throw new IllegalArgumentException("Composite and individual widgets duplicate ownership: " + prefix);
            }
        }
        for (var entry : renderers.entrySet()) {
            final String type = entry.getKey().substring(entry.getKey().indexOf('.') + 1);
            final String renderer = entry.getValue();
            if (entry.getKey().equals("PHASES_ACTIVE") || !widgets.containsKey(entry.getKey()) || !MatchWidgetRegistry.contains(renderer)
                    || !(renderer.equals(type) || (type.startsWith("ZONE_") && Set.of("ZONE_PILE", "ZONE_BUTTON").contains(renderer))
                    || renderer.startsWith("CUSTOM_"))) {
                throw new IllegalArgumentException("Incompatible widget renderer: " + entry);
            }
        }
        for (String id : styles.keySet()) {
            if (!widgets.containsKey(id) && !MatchUiLayout.isSelector(id)) { throw new IllegalArgumentException("Unknown surface: " + id); }
        }
    }
    public MatchSurfaceStyle styleFor(String id) {
        if (styles.containsKey(id)) { return styles.get(id); }
        if (id.startsWith("FIELD_") && !id.startsWith("FIELD_0") && styles.containsKey("opponents")) {
            return styles.get("opponents");
        }
        return styles.getOrDefault(id.startsWith("HAND_") ? "hands" : id.startsWith("FIELD_") ? "fields" : "remaining", surface);
    }
    public boolean replacesDocument(String id) { return id.equals("BUTTON_DOCK") && widgets.containsKey("ACTIONS_MENU"); }
    void validate(List<MatchUiLayout.Region> regions) {
        if (regions.isEmpty()) { throw new IllegalArgumentException("scene needs document regions"); }
        final var all = new java.util.ArrayList<>(widgets.values());
        for (var region : regions) { all.add(region.bounds()); }
        for (int i = 0; i < all.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (all.get(i).overlaps(all.get(j))) { throw new IllegalArgumentException("Interactive scene widgets and documents must not overlap"); }
            }
        }
    }
    public boolean supports(List<String> documents) {
        if (documents.stream().filter(id -> id.startsWith("HAND_")).count() > 1) { return false; }
        final Set<String> fields = new java.util.HashSet<>();
        for (String id : documents) {
            if (!id.startsWith("FIELD_")) { continue; }
            fields.add(id);
            final java.util.function.Predicate<String> has = type -> widgets.containsKey(id + "." + type);
            if (!has.test("AVATAR") && !(has.test("AVATAR_IMAGE") && has.test("LIFE") && has.test("STATUS"))) { return false; }
            final boolean mana = has.test("MANA") || List.of("W", "U", "B", "R", "G", "C").stream().allMatch(c -> has.test("MANA_" + c));
            if (!has.test("DETAILS") && !(mana && has.test("OTHER_ZONES") && (has.test("ZONES")
                    || List.of("LIBRARY", "GRAVEYARD", "EXILE").stream().allMatch(z -> has.test("ZONE_" + z))))) { return false; }
        }
        return widgets.keySet().stream().filter(id -> id.startsWith("FIELD_"))
                .allMatch(id -> fields.contains(id.substring(0, id.indexOf('.'))));
    }
    static MatchSceneLayout read(JsonObject json) {
        MatchUiLayout.keys(json, Set.of("widgets", "surface", "styles", "renderers"));
        final Map<String, MatchUiLayout.Bounds> widgets = new LinkedHashMap<>();
        final Map<String, MatchSurfaceStyle> styles = new LinkedHashMap<>();
        final Map<String, String> renderers = new LinkedHashMap<>();
        json.getAsJsonObject("widgets").entrySet().forEach(e -> widgets.put(e.getKey(), MatchUiLayout.bounds(e.getValue())));
        if (json.has("styles")) { json.getAsJsonObject("styles").entrySet().forEach(e -> styles.put(e.getKey(), MatchSurfaceStyle.read(e.getValue().getAsJsonObject()))); }
        if (json.has("renderers")) { json.getAsJsonObject("renderers").entrySet().forEach(e -> renderers.put(e.getKey(), e.getValue().getAsString())); }
        return new MatchSceneLayout(widgets, json.has("surface") ? MatchSurfaceStyle.read(json.getAsJsonObject("surface")) : MatchSurfaceStyle.CLEAR, styles, renderers);
    }
}
