package forge.screens.match.layout;

import com.google.gson.JsonObject;
import java.util.Set;

/** Trusted Java providers can replace geometry and overlays; JSON selects built-in implementations. */
public record MatchCardPresentation(HandLayoutStrategy hand, CardOverlayPainter overlay, BattlefieldLayoutStrategy battlefield) {
    public static final MatchCardPresentation CLASSIC = new MatchCardPresentation(null, CardOverlayPainter.NONE, null);
    public MatchCardPresentation {
        if (overlay == null) { throw new IllegalArgumentException("overlay is required"); }
    }
    static MatchCardPresentation read(JsonObject json) {
        return read(json, false);
    }
    static MatchCardPresentation read(JsonObject json, boolean enhanced) {
        MatchUiLayout.keys(json, enhanced ? Set.of("hand", "fanDegrees", "overlay", "battlefield", "handSpacing", "handArc", "hoverLift",
                "battlefieldAlign", "battlefieldRowGap", "battlefieldGap", "badges", "handCardWidthMax", "battlefieldPartition", "landsSide") : Set.of("hand", "fanDegrees", "overlay", "battlefield"));
        final String hand = json.has("hand") ? json.get("hand").getAsString() : "classic";
        final String overlay = json.has("overlay") ? json.get("overlay").getAsString() : "classic";
        final String battlefield = json.has("battlefield") ? json.get("battlefield").getAsString() : "classic";
        choice("hand", hand, Set.of("classic", "fan"));
        choice("overlay", overlay, Set.of("classic", "badges"));
        choice("battlefield", battlefield, enhanced ? Set.of("classic", "lanes", "adaptive") : Set.of("classic", "lanes"));
        if (json.has("handCardWidthMax") && !hand.equals("fan")) {
            throw new IllegalArgumentException("cards.handCardWidthMax requires cards.hand=fan (match-ui v4)");
        }
        final double degrees = json.has("fanDegrees") ? json.get("fanDegrees").getAsDouble() : 28;
        final HandLayoutStrategy fan = HandLayoutStrategy.fan(degrees,
                MatchSkinTheme.optionalNumber(json, "handSpacing", .8, .1, 1.5, false),
                MatchSkinTheme.optionalNumber(json, "handArc", 1, 0, 1, false), MatchSkinTheme.optionalNumber(json, "hoverLift", 0, 0, .4, false),
                (int) MatchSkinTheme.optionalNumber(json, "handCardWidthMax", 300, 16, 300, true));
        final String align = json.has("battlefieldAlign") ? json.get("battlefieldAlign").getAsString() : "CENTER";
        if (!Set.of("CENTER", "START").contains(align)) { throw new IllegalArgumentException("Unknown battlefieldAlign"); }
        final int rowGap = (int) MatchSkinTheme.optionalNumber(json, "battlefieldRowGap", 8, 0, 80, true);
        final int groupGap = (int) MatchSkinTheme.optionalNumber(json, "battlefieldGap", 6, 0, 80, true);
        final double partition = MatchSkinTheme.optionalNumber(json, "battlefieldPartition", .5, .3, .7, false);
        final String landsSide = json.has("landsSide") ? json.get("landsSide").getAsString() : "LEFT";
        choice("landsSide", landsSide, Set.of("LEFT", "RIGHT"));
        if ((json.has("landsSide") || json.has("battlefieldPartition")) && !battlefield.equals("adaptive")) { throw new IllegalArgumentException("Partition requires adaptive battlefield"); }
        final var rows = battlefield.equals("adaptive") ? new AdaptiveBattlefieldLayout(align.equals("CENTER"), rowGap, groupGap, partition, landsSide.equals("RIGHT"))
                : enhanced ? BattlefieldLayoutStrategy.rows(align.equals("CENTER"), rowGap, groupGap) : BattlefieldLayoutStrategy.LANES;
        final var badges = json.has("badges") ? ConfigurableCardBadges.read(json.getAsJsonObject("badges")) : CardOverlayPainter.BADGES;
        return new MatchCardPresentation(hand.equals("fan") ? fan : null,
                overlay.equals("badges") ? badges : CardOverlayPainter.NONE,
                battlefield.equals("classic") ? null : rows);
    }
    private static void choice(String field, String value, Set<String> supported) {
        if (!supported.contains(value)) {
            throw new IllegalArgumentException("cards." + field + "='" + value + "'; supported: "
                    + new java.util.TreeSet<>(supported) + ". Check the target client's skin capabilities.");
        }
    }
}
