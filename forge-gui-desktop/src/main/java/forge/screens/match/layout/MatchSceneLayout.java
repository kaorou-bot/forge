package forge.screens.match.layout;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent live widgets in screen coordinates. Fields retain their battlefield document. */
public record MatchSceneLayout(Map<String, MatchUiLayout.Bounds> widgets) {
    public MatchSceneLayout {
        widgets = Map.copyOf(widgets);
        if (!widgets.containsKey("PHASES_ACTIVE")) {
            throw new IllegalArgumentException("scene needs PHASES_ACTIVE");
        }
        for (String id : widgets.keySet()) {
            if (!id.equals("PHASES_ACTIVE") && !id.matches("FIELD_[0-7]\\.(AVATAR|DETAILS|ZONES|HAND_BACKS)")) {
                throw new IllegalArgumentException("Unknown scene widget: " + id);
            }
        }
    }

    void validate(List<MatchUiLayout.Region> regions) {
        if (regions.isEmpty()) { throw new IllegalArgumentException("scene needs document regions"); }
        final var all = new java.util.ArrayList<>(widgets.values());
        for (var region : regions) { all.add(region.bounds()); }
        for (int i = 0; i < all.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (all.get(i).overlaps(all.get(j))) {
                    throw new IllegalArgumentException("Interactive scene widgets and documents must not overlap");
                }
            }
        }
    }

    /** A scene must explicitly accommodate every player, including spectators and control changes. */
    public boolean supports(List<String> documents) {
        if (documents.stream().filter(id -> id.startsWith("HAND_")).count() > 1) { return false; }
        final Set<String> fields = new java.util.HashSet<>();
        for (String id : documents) {
            if (id.startsWith("FIELD_")) {
                fields.add(id);
                if (!widgets.containsKey(id + ".AVATAR") || !widgets.containsKey(id + ".DETAILS")) { return false; }
            }
        }
        return widgets.keySet().stream().filter(id -> id.startsWith("FIELD_"))
                .allMatch(id -> fields.contains(id.substring(0, id.indexOf('.'))));
    }

    static MatchSceneLayout read(JsonObject json) {
        MatchUiLayout.keys(json, Set.of("widgets"));
        final Map<String, MatchUiLayout.Bounds> widgets = new LinkedHashMap<>();
        json.getAsJsonObject("widgets").entrySet().forEach(e -> widgets.put(e.getKey(), MatchUiLayout.bounds(e.getValue())));
        return new MatchSceneLayout(widgets);
    }
}
