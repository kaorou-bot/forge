package forge.screens.match.layout;

import com.google.gson.JsonObject;
import java.util.Set;

/** Player overrides are kept outside the author's package. */
public record MatchSkinSettings(double fontScale, int handWidth, double panelOpacity, double decorationOpacity, String layoutMode) {
    public static final MatchSkinSettings DEFAULT = new MatchSkinSettings(1,300,1,1,"AUTO");
    public MatchSkinSettings {
        if (!Double.isFinite(fontScale) || fontScale < .75 || fontScale > 1.75 || handWidth < 40 || handWidth > 300
                || !Double.isFinite(panelOpacity) || panelOpacity < 0 || panelOpacity > 1
                || !Double.isFinite(decorationOpacity) || decorationOpacity < 0 || decorationOpacity > 1
                || !Set.of("AUTO","COMPACT").contains(layoutMode)) { throw new IllegalArgumentException("Invalid skin preferences"); }
    }
    static MatchSkinSettings read(JsonObject json) {
        MatchUiLayout.keys(json,Set.of("fontScale","handWidth","panelOpacity","decorationOpacity","layoutMode"));
        return new MatchSkinSettings(MatchSkinTheme.optionalNumber(json,"fontScale",1,.75,1.75,false),
                (int)MatchSkinTheme.optionalNumber(json,"handWidth",300,40,300,true),
                MatchSkinTheme.optionalNumber(json,"panelOpacity",1,0,1,false), MatchSkinTheme.optionalNumber(json,"decorationOpacity",1,0,1,false),
                json.has("layoutMode") ? json.get("layoutMode").getAsString() : "AUTO");
    }
}
