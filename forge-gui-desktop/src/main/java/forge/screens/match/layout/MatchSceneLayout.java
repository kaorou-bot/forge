package forge.screens.match.layout;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent live widgets and reversible surface decoration in screen coordinates. */
public record MatchSceneLayout(Map<String, MatchUiLayout.Bounds> widgets, MatchSurfaceStyle surface,
        Map<String, MatchSurfaceStyle> styles, Map<String, String> renderers,
        Map<String, MatchVisibility> visibility, Map<String, MatchFloatingSpec> floating, MatchSkinTheme appearance, Map<String, MatchAnchor> anchors) {
    public MatchSceneLayout(Map<String, MatchUiLayout.Bounds> widgets, MatchSurfaceStyle surface, Map<String, MatchSurfaceStyle> styles,
            Map<String,String> renderers, Map<String,MatchVisibility> visibility, Map<String,MatchFloatingSpec> floating, MatchSkinTheme appearance) {
        this(widgets,surface,styles,renderers,visibility,floating,appearance,Map.of());
    }
    public MatchSceneLayout withAppearance(MatchSkinTheme theme) { return new MatchSceneLayout(widgets,surface,styles,renderers,visibility,floating,theme,anchors); }
    public MatchSceneLayout(Map<String, MatchUiLayout.Bounds> widgets, MatchSurfaceStyle surface,
            Map<String, MatchSurfaceStyle> styles, Map<String, String> renderers) {
        this(widgets, surface, styles, renderers, Map.of(), Map.of(), null);
    }
    public MatchSceneLayout(Map<String, MatchUiLayout.Bounds> widgets) {
        this(widgets, MatchSurfaceStyle.CLEAR, Map.of(), Map.of());
    }
    public MatchSceneLayout {
        widgets = Map.copyOf(widgets);
        styles = Map.copyOf(styles);
        renderers = Map.copyOf(renderers);
        visibility = Map.copyOf(visibility);
        floating = Map.copyOf(floating);
        anchors = Map.copyOf(anchors);
        if (!widgets.keySet().containsAll(anchors.keySet())) { throw new IllegalArgumentException("Anchor target not in widgets"); }
        for (var entry : visibility.entrySet()) {
            if (!widgets.containsKey(entry.getKey())) { throw new IllegalArgumentException("Unknown conditional widget: " + entry.getKey()); }
            final String type = entry.getKey().substring(entry.getKey().indexOf('.') + 1);
            // Required action, player-selection and phase controls must remain reachable.
            if (entry.getValue() != MatchVisibility.ALWAYS
                    && !(entry.getValue() == MatchVisibility.MANA_NONEMPTY && (type.equals("MANA") || type.startsWith("MANA_")))
                    && !(entry.getValue() == MatchVisibility.STATUS_NONEMPTY && type.equals("STATUS"))
                    && !(entry.getValue() == MatchVisibility.STACK_NONEMPTY && type.equals("STACK_STATUS"))) {
                throw new IllegalArgumentException("Unsupported visibility condition for " + entry.getKey());
            }
        }
        for (String id : floating.keySet()) {
            if (!MatchFloatingSpec.DOCUMENTS.contains(id)) { throw new IllegalArgumentException("Document cannot float: " + id); }
        }
        if (floating.containsKey("REPORT_STACK") && widgets.containsKey("STACK_STATUS")) {
            throw new IllegalArgumentException("Floating stack owns its title; omit STACK_STATUS");
        }
        if (!widgets.containsKey("PHASES_ACTIVE")) { throw new IllegalArgumentException("scene needs PHASES_ACTIVE"); }
        if (widgets.keySet().stream().anyMatch(id -> id.startsWith("PROMPT_"))
                && !widgets.keySet().containsAll(Set.of("PROMPT_MESSAGE", "PROMPT_OK", "PROMPT_CANCEL"))) {
            throw new IllegalArgumentException("Independent prompt needs message, OK and cancel controls");
        }
        for (String id : widgets.keySet()) {
            if (id.equals("PHASES_ACTIVE")) { continue; }
            final boolean player = id.matches("FIELD_[0-7]\\.[A-Z][A-Z0-9_]*");
            final String type = player ? id.substring(id.indexOf('.') + 1) : id;
            final boolean global = type.equals("STACK_STATUS") || type.equals("ACTIONS_MENU") || type.startsWith("ACTION_")
                    || type.startsWith("PROMPT_");
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
            if (entry.getKey().equals("PHASES_ACTIVE") && Set.of("PHASES_SPLIT", "PHASES_OVERVIEW").contains(renderer)) { continue; }
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
    public boolean replacesDocument(String id) {
        return floating.containsKey(id) || id.equals("BUTTON_DOCK") && widgets.containsKey("ACTIONS_MENU")
                || id.equals("REPORT_MESSAGE") && widgets.containsKey("PROMPT_MESSAGE");
    }
    List<MatchUiLayout.Bounds> protectedAreas(List<MatchUiLayout.Region> regions) {
        return protectedAreas(regions, true);
    }
    private List<MatchUiLayout.Bounds> protectedAreas(List<MatchUiLayout.Region> regions, boolean includePreview) {
        final var protectedBounds = new java.util.ArrayList<MatchUiLayout.Bounds>();
        regions.stream().filter(r -> r.documents().stream().anyMatch(s ->
                s.equals("hands") || s.startsWith("HAND_") || s.equals("REPORT_MESSAGE")
                || includePreview && (s.equals("CARD_PICTURE") || s.equals("CARD_DETAIL"))))
                .map(MatchUiLayout.Region::bounds).forEach(protectedBounds::add);
        widgets.entrySet().stream().filter(e -> e.getKey().startsWith("PROMPT_"))
                .map(Map.Entry::getValue).forEach(protectedBounds::add);
        return List.copyOf(protectedBounds);
    }
    void validate(List<MatchUiLayout.Region> regions) {
        if (regions.isEmpty()) { throw new IllegalArgumentException("scene needs document regions"); }
        final var all = new java.util.ArrayList<>(widgets.values());
        for (var region : regions) { all.add(region.bounds()); }
        for (var region : regions) {
            if (region.documents().stream().anyMatch(this::replacesDocument)) {
                throw new IllegalArgumentException("Replaced document also assigned a fixed region");
            }
        }
        // Earlier v3 packages may place the stack over previews. Relocate them at runtime
        // instead of making previously importable packages invalid.
        for (var area : protectedAreas(regions, false)) {
            for (var panel : floating.values()) {
                if (panel.bounds().overlaps(area)) {
                    throw new IllegalArgumentException("Floating panels must not cover hands or response controls");
                }
            }
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (all.get(i).overlaps(all.get(j))) { throw new IllegalArgumentException("Interactive scene widgets and documents must not overlap"); }
            }
        }
    }
    public boolean supports(List<String> documents) {
        if (!overviewPhases() && widgets.keySet().stream().anyMatch(k -> k.endsWith(".PHASES"))) { return false; }
        if (documents.stream().filter(id -> id.startsWith("HAND_")).count() > 1) { return false; }
        if (splitPhases() && !documents.stream().filter(id -> id.startsWith("FIELD_")).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("FIELD_0", "FIELD_1"))) { return false; }
        final Set<String> fields = new java.util.HashSet<>();
        for (String id : documents) {
            if (!id.startsWith("FIELD_")) { continue; }
            fields.add(id);
            if (overviewPhases() && !widgets.containsKey(id + ".PHASES")) { return false; }
            final java.util.function.Predicate<String> has = type -> widgets.containsKey(id + "." + type);
            if (!has.test("AVATAR") && !(has.test("AVATAR_IMAGE") && has.test("LIFE") && has.test("STATUS"))) { return false; }
            final boolean mana = has.test("MANA") || List.of("W", "U", "B", "R", "G", "C").stream().allMatch(c -> has.test("MANA_" + c));
            if (!has.test("DETAILS") && !(mana && has.test("OTHER_ZONES") && (has.test("ZONES")
                    || List.of("LIBRARY", "GRAVEYARD", "EXILE").stream().allMatch(z -> has.test("ZONE_" + z))))) { return false; }
        }
        return widgets.keySet().stream().filter(id -> id.startsWith("FIELD_"))
                .allMatch(id -> fields.contains(id.substring(0, id.indexOf('.'))));
    }
    static MatchSceneLayout read(JsonObject json) { return read(json, null, false); }
    public boolean splitPhases() { return "PHASES_SPLIT".equals(renderers.get("PHASES_ACTIVE")); }
    public boolean overviewPhases() { return "PHASES_OVERVIEW".equals(renderers.get("PHASES_ACTIVE")); }
    static MatchSceneLayout read(JsonObject json, java.nio.file.Path assets, boolean version3) {
        return read(json, assets, version3, false);
    }
    static MatchSceneLayout read(JsonObject json, java.nio.file.Path assets, boolean version3, boolean version4) {
        MatchUiLayout.keys(json, version3 ? Set.of("widgets", "surface", "styles", "renderers", "visibility", "floating", "appearance", "anchors")
                : Set.of("widgets", "surface", "styles", "renderers"));
        final Map<String, MatchUiLayout.Bounds> widgets = new LinkedHashMap<>();
        final Map<String, MatchSurfaceStyle> styles = new LinkedHashMap<>();
        final Map<String, String> renderers = new LinkedHashMap<>();
        final Map<String, MatchAnchor> anchors = new LinkedHashMap<>();
        if (json.has("anchors")) {
            if (!version4) { throw new IllegalArgumentException("Anchors require an enhanced scene client"); }
            json.getAsJsonObject("anchors").entrySet().forEach(e -> anchors.put(e.getKey(),MatchAnchor.read(e.getValue().getAsJsonObject())));
        }
        json.getAsJsonObject("widgets").entrySet().forEach(e -> widgets.put(e.getKey(), MatchUiLayout.bounds(e.getValue())));
        if (json.has("styles")) { json.getAsJsonObject("styles").entrySet().forEach(e -> styles.put(e.getKey(), MatchSurfaceStyle.read(e.getValue().getAsJsonObject()))); }
        if (json.has("renderers")) { json.getAsJsonObject("renderers").entrySet().forEach(e -> renderers.put(e.getKey(), e.getValue().getAsString())); }
        if (!version4 && "PHASES_SPLIT".equals(renderers.get("PHASES_ACTIVE"))) {
            throw new IllegalArgumentException("PHASES_SPLIT requires version 4");
        }
        final Map<String, MatchVisibility> visibility = new LinkedHashMap<>();
        final Map<String, MatchFloatingSpec> floating = new LinkedHashMap<>();
        if (json.has("visibility")) { json.getAsJsonObject("visibility").entrySet().forEach(e -> visibility.put(e.getKey(), MatchVisibility.valueOf(e.getValue().getAsString()))); }
        if (!version4 && visibility.containsValue(MatchVisibility.STATUS_NONEMPTY)) {
            throw new IllegalArgumentException("scene.visibility: STATUS_NONEMPTY requires match-ui v4 and skin capabilities 2026-10-01.1");
        }
        if (json.has("floating")) { json.getAsJsonObject("floating").entrySet().forEach(e -> floating.put(e.getKey(), MatchFloatingSpec.read(e.getValue().getAsJsonObject()))); }
        return new MatchSceneLayout(widgets, json.has("surface") ? MatchSurfaceStyle.read(json.getAsJsonObject("surface")) : MatchSurfaceStyle.CLEAR,
                styles, renderers, visibility, floating,
                json.has("appearance") ? MatchSkinTheme.read(json.getAsJsonObject("appearance"), assets, version4) : null, anchors);
    }
}
