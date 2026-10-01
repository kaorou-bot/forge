package forge.screens.match.layout;

import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.Set;

/** Configurable display-only badges; no hidden card identities or mutable game objects. */
final class ConfigurableCardBadges implements CardOverlayPainter {
    private record Badge(MatchUiLayout.Bounds bounds, Color fill, Color text, int fontSize, int radius) { }
    private final Badge pt, counters, damage;
    private ConfigurableCardBadges(Badge pt, Badge counters, Badge damage) { this.pt = pt; this.counters = counters; this.damage = damage; }
    static CardOverlayPainter read(JsonObject json) {
        MatchUiLayout.keys(json, Set.of("powerToughness", "counters", "damage"));
        return new ConfigurableCardBadges(read(json, "powerToughness", new MatchUiLayout.Bounds(.5,.82,.5,.18)),
                read(json, "counters", new MatchUiLayout.Bounds(0,0,1,.5)), read(json, "damage", new MatchUiLayout.Bounds(0,.82,.5,.18)));
    }
    private static Badge read(JsonObject root, String name, MatchUiLayout.Bounds fallback) {
        final var s = root.has(name) ? root.getAsJsonObject(name) : new JsonObject();
        MatchUiLayout.keys(s, Set.of("bounds", "fill", "text", "fontSize", "radius"));
        return new Badge(s.has("bounds") ? MatchUiLayout.bounds(s.get("bounds")) : fallback,
                color(s, "fill", new Color(30,30,30,220)), color(s, "text", Color.WHITE),
                (int) MatchSkinTheme.optionalNumber(s, "fontSize", 12, 8, 32, true), (int) MatchSkinTheme.optionalNumber(s, "radius", 6, 0, 32, true));
    }
    private static Color color(JsonObject s, String key, Color fallback) {
        if (!s.has(key)) { return fallback; }
        final String value = s.get(key).getAsString();
        if (!value.matches("#[a-fA-F0-9]{6}([a-fA-F0-9]{2})?")) { throw new IllegalArgumentException("Invalid badge color"); }
        return new Color(Integer.parseInt(value.substring(1,3),16),Integer.parseInt(value.substring(3,5),16),Integer.parseInt(value.substring(5,7),16),
                value.length() == 9 ? Integer.parseInt(value.substring(7,9),16) : 255);
    }
    @Override public void paint(Graphics2D g, int width, int height, Data data) {
        paint(g, width, height, pt, data.powerToughness());
        paint(g, width, height, damage, data.damage() > 0 ? Integer.toString(data.damage()) : "");
        final String text = new java.util.TreeMap<>(data.counters()).entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
        paint(g, width, height, counters, text);
    }
    private static void paint(Graphics2D source, int width, int height, Badge badge, String text) {
        if (text == null || text.isEmpty()) { return; }
        final var b = badge.bounds();
        final int x = (int)(b.x()*width), y=(int)(b.y()*height), w=(int)(b.w()*width), h=(int)(b.h()*height);
        final var g=(Graphics2D)source.create(x,y,w,h);
        try {
            g.setFont(g.getFont().deriveFont((float)badge.fontSize()));
            g.setColor(badge.fill()); g.fillRoundRect(0,0,w,h,badge.radius(),badge.radius()); g.setColor(badge.text());
            int baseline=g.getFontMetrics().getAscent();
            for (String line:text.split("\n")) { g.drawString(line,2,baseline); baseline+=g.getFontMetrics().getHeight(); }
        } finally {g.dispose();}
    }
}
