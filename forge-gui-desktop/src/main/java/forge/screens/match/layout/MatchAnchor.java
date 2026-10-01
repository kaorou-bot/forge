package forge.screens.match.layout;

import com.google.gson.JsonObject;
import java.awt.Rectangle;
import java.util.Set;

/** Fits a widget inside its reserved slot; never expands into neighbouring controls. Logical pixels. */
public record MatchAnchor(String horizontal, String vertical, int width, int height,
        int minWidth, int minHeight, int maxWidth, int maxHeight, double aspectRatio) {
    static MatchAnchor read(JsonObject json) {
        MatchUiLayout.keys(json, Set.of("horizontal", "vertical", "width", "height", "minWidth", "minHeight", "maxWidth", "maxHeight", "aspectRatio"));
        final var anchor = new MatchAnchor(json.has("horizontal") ? json.get("horizontal").getAsString() : "CENTER",
                json.has("vertical") ? json.get("vertical").getAsString() : "CENTER", size(json,"width",0), size(json,"height",0),
                size(json,"minWidth",0), size(json,"minHeight",0), size(json,"maxWidth",8192), size(json,"maxHeight",8192),
                MatchSkinTheme.optionalNumber(json,"aspectRatio",0,0,10,false));
        if (!Set.of("START","CENTER","END").contains(anchor.horizontal) || !Set.of("START","CENTER","END").contains(anchor.vertical)
                || anchor.minWidth > anchor.maxWidth || anchor.minHeight > anchor.maxHeight) { throw new IllegalArgumentException("Invalid widget anchor"); }
        return anchor;
    }
    private static int size(JsonObject json, String name, int fallback) { return (int) MatchSkinTheme.optionalNumber(json,name,fallback,0,8192,true); }
    public Rectangle fit(Rectangle slot) {
        int w = Math.min(Math.max(0,slot.width), Math.max(minWidth, Math.min(maxWidth, width == 0 ? slot.width : width)));
        int h = Math.min(Math.max(0,slot.height), Math.max(minHeight, Math.min(maxHeight, height == 0 ? slot.height : height)));
        if (aspectRatio > 0) {
            if (w > h * aspectRatio) { w = (int) Math.floor(h * aspectRatio); }
            else { h = (int) Math.floor(w / aspectRatio); }
        }
        return new Rectangle(slot.x + offset(horizontal,slot.width-w), slot.y + offset(vertical,slot.height-h),w,h);
    }
    private static int offset(String alignment, int space) { return alignment.equals("START") ? 0 : alignment.equals("END") ? space : space/2; }
}
