package forge.screens.match.layout;

import com.google.gson.JsonObject;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** v5 responsive presets. Variants replace geometry sections, not arbitrary game behaviour. */
public record MatchSkinExperience(JsonObject source, Path assets, MatchSkinSettings defaults, List<Variant> variants) {
    public static final MatchSkinExperience NONE = new MatchSkinExperience(null,null,MatchSkinSettings.DEFAULT,List.of());
    public record Variant(String id, int maxWidth, int minPlayers, int maxPlayers, MatchUiLayout layout) { }
    public record Choice(String id, MatchUiLayout layout) { }
    public MatchSkinExperience { source = source == null ? null : source.deepCopy(); variants = List.copyOf(variants); }
    @Override public JsonObject source() { return source == null ? null : source.deepCopy(); }
    static MatchSkinExperience read(JsonObject root, Path assets, MatchUiLayout base) {
        if (!root.has("experience")) { return new MatchSkinExperience(root,assets,MatchSkinSettings.DEFAULT,List.of()); }
        final var json = root.getAsJsonObject("experience");
        MatchUiLayout.keys(json,Set.of("defaults","variants"));
        final var defaults = json.has("defaults") ? MatchSkinSettings.read(json.getAsJsonObject("defaults")) : MatchSkinSettings.DEFAULT;
        final var variants = new ArrayList<Variant>(); final var names = new java.util.HashSet<String>();
        if (json.has("variants")) {
            if (json.getAsJsonArray("variants").size() > 16) { throw new IllegalArgumentException("At most 16 responsive variants"); }
            for (var value : json.getAsJsonArray("variants")) {
                final var v = value.getAsJsonObject();
                MatchUiLayout.keys(v,Set.of("id","maxWidth","minPlayers","maxPlayers","regions","widgets","anchors","floating","renderers","visibility","cards"));
                final String id = v.get("id").getAsString();
                if (!id.matches("[a-z][a-z0-9-]{0,31}") || id.equals("base") || !names.add(id)) { throw new IllegalArgumentException("Invalid/duplicate responsive variant: " + id); }
                final int maxWidth = (int)MatchSkinTheme.optionalNumber(v,"maxWidth",16384,320,16384,true);
                final int min = (int)MatchSkinTheme.optionalNumber(v,"minPlayers",2,1,8,true);
                final int max = (int)MatchSkinTheme.optionalNumber(v,"maxPlayers",2,min,8,true);
                final var merged = root.deepCopy(); merged.remove("experience");
                // Decode common images/font once. Every preset shares the immutable theme.
                merged.getAsJsonObject("scene").remove("appearance");
                for (String key : List.of("regions","cards")) { if (v.has(key)) { merged.add(key,v.get(key).deepCopy()); } }
                for (String key : List.of("widgets","anchors","floating","renderers","visibility")) {
                    if (v.has(key)) { merged.getAsJsonObject("scene").add(key,v.get(key).deepCopy()); }
                }
                final var parsed = MatchUiLayout.read(new StringReader(merged.toString()),assets);
                final var layout = new MatchUiLayout(base.id(),parsed.regions(),parsed.fieldLayout(),
                        parsed.scene().withAppearance(base.scene().appearance()),parsed.cards());
                variants.add(new Variant(id,maxWidth,min,max,layout));
            }
        }
        return new MatchSkinExperience(root,assets,defaults,variants);
    }
    public Choice choose(MatchUiLayout base, int width, int players, String mode) {
        if (mode.equals("COMPACT")) {
            for (var v : variants) { if (v.id.equals("compact") && players >= v.minPlayers && players <= v.maxPlayers) { return new Choice(v.id,v.layout); } }
        }
        for (var v : variants) {
            if (width <= v.maxWidth && players >= v.minPlayers && players <= v.maxPlayers) { return new Choice(v.id,v.layout); }
        }
        return new Choice("base",base);
    }
}
