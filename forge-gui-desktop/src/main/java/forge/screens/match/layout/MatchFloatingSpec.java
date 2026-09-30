package forge.screens.match.layout;

import com.google.gson.JsonObject;
import java.util.Set;

/** A document in the in-window overlay, not a second OS window. */
public record MatchFloatingSpec(MatchUiLayout.Bounds bounds, MatchVisibility visibleWhen, boolean draggable) {
    static final Set<String> DOCUMENTS = Set.of("REPORT_STACK", "CARD_PICTURE", "CARD_DETAIL",
            "REPORT_LOG", "REPORT_COMBAT", "REPORT_DEPENDENCIES", "DEV_MODE");

    static MatchFloatingSpec read(JsonObject json) {
        MatchUiLayout.keys(json, Set.of("bounds", "visibleWhen", "draggable"));
        if (json.has("draggable") && (!json.get("draggable").isJsonPrimitive()
                || !json.getAsJsonPrimitive("draggable").isBoolean())) {
            throw new IllegalArgumentException("draggable must be boolean");
        }
        final MatchVisibility visibility = json.has("visibleWhen")
                ? MatchVisibility.valueOf(json.get("visibleWhen").getAsString()) : MatchVisibility.ALWAYS;
        if (visibility != MatchVisibility.ALWAYS && visibility != MatchVisibility.STACK_NONEMPTY) {
            throw new IllegalArgumentException("Floating documents need a global visibility condition");
        }
        return new MatchFloatingSpec(MatchUiLayout.bounds(json.get("bounds")), visibility,
                !json.has("draggable") || json.get("draggable").getAsBoolean());
    }
}
