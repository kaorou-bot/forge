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
        MatchUiLayout.keys(json, Set.of("hand", "fanDegrees", "overlay", "battlefield"));
        final String hand = json.has("hand") ? json.get("hand").getAsString() : "classic";
        final String overlay = json.has("overlay") ? json.get("overlay").getAsString() : "classic";
        final String battlefield = json.has("battlefield") ? json.get("battlefield").getAsString() : "classic";
        if (!Set.of("classic", "fan").contains(hand) || !Set.of("classic", "badges").contains(overlay)
                || !Set.of("classic", "lanes").contains(battlefield)) {
            throw new IllegalArgumentException("Unknown hand or overlay style");
        }
        final double degrees = json.has("fanDegrees") ? json.get("fanDegrees").getAsDouble() : 28;
        final HandLayoutStrategy fan = HandLayoutStrategy.fan(degrees);
        return new MatchCardPresentation(hand.equals("fan") ? fan : null,
                overlay.equals("badges") ? CardOverlayPainter.BADGES : CardOverlayPainter.NONE,
                battlefield.equals("lanes") ? BattlefieldLayoutStrategy.LANES : null);
    }
}
