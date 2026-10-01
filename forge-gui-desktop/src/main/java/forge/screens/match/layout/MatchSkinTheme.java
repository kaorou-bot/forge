package forge.screens.match.layout;

import com.google.gson.JsonObject;
import forge.view.arcane.CardPanel;
import forge.toolbox.FSkin;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.JComponent;

/** Data-only paint vocabulary shared by every imported desktop match skin. */
public final class MatchSkinTheme {
    public static final String STYLE_PROPERTY = "forge.matchSkinStyle";
    private final BufferedImage background;
    private final Font font;
    private final Color text;
    private final Map<String, Style> styles;
    private MatchSkinTexture backgroundTexture;
    private java.util.List<Decoration> decorations = java.util.List.of();
    private Map<String, MatchDocumentFormat> documentFormats = Map.of();
    public record Decoration(MatchSkinTexture texture, MatchUiLayout.Bounds bounds, double rotation,
            float opacity, boolean foreground, int order) { }
    public java.util.List<Decoration> decorations() { return decorations; }
    public MatchSkinTexture backgroundTexture() { return backgroundTexture; }

    public record Style(Color fill, Color border, Color highlight, int radius, int padding,
            float fontSize, BufferedImage image, MatchSkinVisual visual) {
        public Style(Color fill, Color border, Color highlight, int radius, int padding, float fontSize, BufferedImage image) {
            this(fill, border, highlight, radius, padding, fontSize, image, null);
        }
        public Style state(boolean enabled, boolean hover, boolean pressed, boolean selected) {
            if (visual == null) { return this; }
            final String key = !enabled ? "disabled" : pressed ? "pressed" : hover ? "hover" : selected ? "selected" : "normal";
            return visual.states().getOrDefault(key, visual.states().getOrDefault("normal", this));
        }
        public void paintFrame(Graphics2D g, int width, int height) {
            if (visual != null && border != null && visual.borderWidth() > 0) {
                final var edge = (Graphics2D) g.create();
                try {
                    final var outline = visual.outline(width, height, radius);
                    edge.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    edge.clip(outline); edge.setColor(border); edge.setStroke(new java.awt.BasicStroke(visual.borderWidth() * 2));
                    edge.setComposite(java.awt.AlphaComposite.SrcOver.derive(visual.opacity())); edge.draw(outline);
                } finally { edge.dispose(); }
            }
            if (visual != null && visual.frame() != null) { visual.frame().paint(g, 0, 0, width, height); }
        }
        public void paint(Graphics2D source, int width, int height, boolean active, boolean pressed) {
            final Graphics2D g = (Graphics2D) source.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (visual != null) {
                    final var shape = visual.outline(width, height, radius);
                    g.setComposite(java.awt.AlphaComposite.SrcOver.derive(visual.opacity()));
                    g.clip(shape);
                    if (fill != null) { g.setColor(active && highlight != null ? highlight : fill); g.fill(shape); }
                    if (visual.texture() != null) { visual.texture().paint(g, 0, 0, width, height); }
                    if (border != null && visual.borderWidth() > 0) {
                        g.setColor(active && highlight != null ? highlight : border);
                        g.setStroke(new java.awt.BasicStroke(visual.borderWidth() * 2)); g.draw(shape);
                    }
                    return;
                }
                if (fill != null) {
                    final Color top = active && highlight != null ? highlight : fill;
                    g.setPaint(new GradientPaint(0, 0, pressed ? fill.darker() : top,
                            0, Math.max(1, height), fill.darker()));
                    g.fillRoundRect(0, 0, width, height, radius, radius);
                }
                if (image != null) { g.drawImage(image, 0, 0, width, height, null); }
                final Color line = active && highlight != null ? highlight : border;
                if (line != null) {
                    g.setColor(line);
                    g.drawRoundRect(0, 0, Math.max(0, width - 1), Math.max(0, height - 1), radius, radius);
                    if (active && width > 6 && height > 6) {
                        g.drawRoundRect(2, 2, width - 5, height - 5, radius, radius);
                    }
                }
            } finally { g.dispose(); }
        }
    }

    private MatchSkinTheme(BufferedImage background, Font font, Color text, Map<String, Style> styles) {
        this.background = background; this.font = font; this.text = text; this.styles = Map.copyOf(styles);
    }
    public BufferedImage background() { return background; }
    public Color text() { return text; }
    public Style style(String id) {
        if (styles.containsKey(id)) { return styles.get(id); }
        final String type = id.substring(id.indexOf('.') + 1);
        final String category = id.startsWith("floating.") || type.equals("actions") ? "floating"
                : type.equals("tab") ? "button"
                : type.startsWith("ZONE_") ? "zone" : type.startsWith("AVATAR") ? "avatar"
                : type.equals("LIFE") ? "life" : type.equals("PHASES_ACTIVE") || id.startsWith("PHASE.") ? "phase"
                : type.startsWith("ACTION") || type.equals("OTHER_ZONES")
                        || type.equals("PROMPT_OK") || type.equals("PROMPT_CANCEL") ? "button" : "default";
        return styles.getOrDefault(category, styles.get("default"));
    }
    public Font font(float size) { return font.deriveFont(size); }

    public MatchSkinTheme withSettings(MatchSkinSettings settings) {
        final Map<String, Style> adjusted = new LinkedHashMap<>();
        styles.forEach((id, style) -> adjusted.put(id, adjust(style, settings,
                id.equals("floating") || id.startsWith("floating.") || id.startsWith("text"))));
        final var theme = new MatchSkinTheme(background, font, text, adjusted);
        theme.backgroundTexture = backgroundTexture;
        theme.documentFormats = documentFormats;
        theme.decorations = decorations.stream().map(d -> new Decoration(d.texture(), d.bounds(), d.rotation(),
                (float) (d.opacity() * settings.decorationOpacity()), d.foreground(), d.order())).toList();
        return theme;
    }
    private static Style adjust(Style style, MatchSkinSettings settings, boolean panel) {
        final double opacity = panel ? settings.panelOpacity() : 1;
        final var v = style.visual();
        final Map<String, Style> states = new LinkedHashMap<>();
        if (v != null) { v.states().forEach((key, value) -> states.put(key, adjust(value, settings, panel))); }
        final var visual = v == null ? null : new MatchSkinVisual(v.shape(), (float) (v.opacity() * opacity), v.borderWidth(),
                v.texture(), v.icon(), v.iconBounds(), v.textBounds(), v.textColor(), v.showText(), v.textAlign(), v.frame(), states, v.polygon(), v.markers());
        final Color fill = style.fill() == null || v != null ? style.fill() : new Color(style.fill().getRed(), style.fill().getGreen(),
                style.fill().getBlue(), (int) Math.round(style.fill().getAlpha() * opacity));
        return new Style(fill, style.border(), style.highlight(), style.radius(), style.padding(),
                (float) (style.fontSize() * settings.fontScale()), style.image(), visual);
    }

    static MatchSkinTheme read(JsonObject json, Path base) {
        return read(json, base, false);
    }
    static MatchSkinTheme read(JsonObject json, Path base, boolean enhanced) {
        MatchUiLayout.keys(json, enhanced ? Set.of("background", "font", "text", "styles", "decorations", "documents") : Set.of("background", "font", "text", "styles"));
        try {
            final Map<Path, BufferedImage> images = new LinkedHashMap<>();
            final java.util.function.Function<String, BufferedImage> loader = name -> {
                try { return image(base, name, images); } catch (IOException e) { throw new IllegalArgumentException("Cannot load skin image: " + name, e); }
            };
            final MatchSkinTexture texture = json.has("background") ? (enhanced ? MatchSkinTexture.read(json.get("background"), loader)
                    : new MatchSkinTexture(loader.apply(json.get("background").getAsString()), MatchSkinTexture.Mode.STRETCH, 0, 0, 0, 0)) : null;
            final BufferedImage background = texture == null ? null : texture.image();
            final Font font = json.has("font")
                    ? readFont(asset(base, json.get("font").getAsString()))
                    : new Font(Font.SANS_SERIF, Font.PLAIN, 14);
            final Map<String, Style> styles = new LinkedHashMap<>();
            styles.put("default", new Style(null, null, null, 8, 2, 15, null));
            if (json.has("styles")) {
                for (var entry : json.getAsJsonObject("styles").entrySet()) {
                    final JsonObject s = entry.getValue().getAsJsonObject();
                    if (enhanced) { styles.put(entry.getKey(), readStyle(s, loader)); continue; }
                    MatchUiLayout.keys(s, Set.of("fill", "border", "highlight", "radius", "padding", "fontSize", "image"));
                    styles.put(entry.getKey(), new Style(color(s, "fill"), color(s, "border"), color(s, "highlight"),
                            integer(s, "radius", 8, 0, 80), integer(s, "padding", 2, 0, 32),
                            integer(s, "fontSize", 15, 8, 64),
                            s.has("image") ? image(base, s.get("image").getAsString(), images) : null));
                }
            }
            final var theme = new MatchSkinTheme(background, font, json.has("text") ? color(json, "text") : Color.WHITE, styles);
            theme.backgroundTexture = texture;
            if (json.has("documents")) {
                final Map<String, MatchDocumentFormat> formats = new LinkedHashMap<>();
                json.getAsJsonObject("documents").entrySet().forEach(e -> {
                    if (!e.getKey().equals("default") && !MatchUiLayout.isSelector(e.getKey()) && !e.getKey().equals("PROMPT_MESSAGE")) { throw new IllegalArgumentException("Invalid document format ID"); }
                    formats.put(e.getKey(), MatchDocumentFormat.read(e.getValue().getAsJsonObject()));
                }); theme.documentFormats = Map.copyOf(formats);
            }
            if (json.has("decorations")) {
                final var list = new java.util.ArrayList<Decoration>();
                if (json.getAsJsonArray("decorations").size() > 64) { throw new IllegalArgumentException("At most 64 decorations"); }
                for (var element : json.getAsJsonArray("decorations")) {
                    final var d = element.getAsJsonObject();
                    MatchUiLayout.keys(d, Set.of("image", "bounds", "rotation", "opacity", "plane", "order"));
                    final String plane = d.has("plane") ? d.get("plane").getAsString() : "BACKGROUND";
                    if (!Set.of("BACKGROUND", "FOREGROUND").contains(plane)) { throw new IllegalArgumentException("Unknown decoration plane"); }
                    list.add(new Decoration(MatchSkinTexture.read(d.get("image"), loader), MatchUiLayout.bounds(d.get("bounds")),
                            optionalNumber(d, "rotation", 0, -360, 360, false), (float) optionalNumber(d, "opacity", 1, 0, 1, false),
                            plane.equals("FOREGROUND"), (int) optionalNumber(d, "order", 0, -1000, 1000, true)));
                }
                list.sort(java.util.Comparator.comparingInt(Decoration::order)); theme.decorations = java.util.List.copyOf(list);
            }
            return theme;
        } catch (IOException | java.awt.FontFormatException e) {
            throw new IllegalArgumentException("Cannot load match skin asset: " + e.getMessage(), e);
        }
    }
    private static Style readStyle(JsonObject s, java.util.function.Function<String, BufferedImage> loader) {
        MatchUiLayout.keys(s, Set.of("fill", "border", "highlight", "radius", "padding", "fontSize", "image", "shape", "opacity", "borderWidth",
                "icon", "iconBounds", "textBounds", "textColor", "showText", "textAlign", "frame", "states", "polygon", "markers"));
        final Map<String, Style> states = new LinkedHashMap<>();
        if (s.has("states")) {
            final var base = s.deepCopy(); base.remove("states");
            for (var entry : s.getAsJsonObject("states").entrySet()) {
                if (!Set.of("normal", "hover", "pressed", "selected", "disabled").contains(entry.getKey()) || entry.getValue().getAsJsonObject().has("states")) {
                    throw new IllegalArgumentException("Invalid or nested visual state");
                }
                final var merged = base.deepCopy();
                merged.remove("highlight"); // Explicit state art must not be replaced by the legacy highlight tint.
                entry.getValue().getAsJsonObject().entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
                states.put(entry.getKey(), readStyle(merged, loader));
            }
        }
        final var texture = s.has("image") ? MatchSkinTexture.read(s.get("image"), loader) : null;
        if (s.has("showText") && (!s.get("showText").isJsonPrimitive() || !s.getAsJsonPrimitive("showText").isBoolean())) {
            throw new IllegalArgumentException("showText must be boolean");
        }
        final var shape = s.has("shape") ? MatchSkinVisual.ShapeKind.valueOf(s.get("shape").getAsString()) : MatchSkinVisual.ShapeKind.ROUNDED;
        final var polygon = new java.util.ArrayList<java.awt.geom.Point2D.Double>();
        if (s.has("polygon")) {
            for (var point : s.getAsJsonArray("polygon")) {
                final var xy = point.getAsJsonArray(); if (xy.size() != 2) { throw new IllegalArgumentException("polygon point needs x,y"); }
                polygon.add(new java.awt.geom.Point2D.Double(number(xy.get(0),0,1,false), number(xy.get(1),0,1,false)));
            }
        }
        if (shape == MatchSkinVisual.ShapeKind.POLYGON && (polygon.size() < 3 || polygon.size() > 32)
                || shape != MatchSkinVisual.ShapeKind.POLYGON && !polygon.isEmpty()) { throw new IllegalArgumentException("POLYGON requires 3..32 normalized points"); }
        final Map<String, MatchSkinTexture> markers = new LinkedHashMap<>();
        if (s.has("markers")) {
            final var m = s.getAsJsonObject("markers"); MatchUiLayout.keys(m, Set.of("stop", "active", "yield"));
            m.entrySet().forEach(e -> markers.put(e.getKey(), MatchSkinTexture.read(e.getValue(), loader)));
        }
        final var visual = new MatchSkinVisual(shape,
                (float) optionalNumber(s, "opacity", 1, 0, 1, false), (float) optionalNumber(s, "borderWidth", 1, 0, 16, false), texture,
                s.has("icon") ? MatchSkinTexture.read(s.get("icon"), loader) : null,
                s.has("iconBounds") ? MatchUiLayout.bounds(s.get("iconBounds")) : null, s.has("textBounds") ? MatchUiLayout.bounds(s.get("textBounds")) : null,
                color(s, "textColor"), !s.has("showText") || s.get("showText").getAsBoolean(),
                s.has("textAlign") ? MatchSkinVisual.Align.valueOf(s.get("textAlign").getAsString()) : MatchSkinVisual.Align.CENTER,
                s.has("frame") ? MatchSkinTexture.read(s.get("frame"), loader) : null, Map.copyOf(states), java.util.List.copyOf(polygon), Map.copyOf(markers));
        return new Style(color(s, "fill"), color(s, "border"), color(s, "highlight"), integer(s, "radius", 8, 0, 80),
                integer(s, "padding", 2, 0, 32), integer(s, "fontSize", 15, 8, 64), texture == null ? null : texture.image(), visual);
    }
    static double optionalNumber(JsonObject json, String key, double fallback, double min, double max, boolean integer) {
        return json.has(key) ? number(json.get(key), min, max, integer) : fallback;
    }
    static double number(com.google.gson.JsonElement element, double min, double max, boolean integer) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) { throw new IllegalArgumentException("Expected a number"); }
        final double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max || integer && value != Math.rint(value)) { throw new IllegalArgumentException("Number out of bounds"); }
        return value;
    }
    private static Font readFont(Path path) throws IOException, java.awt.FontFormatException {
        // File-based fonts can retain a Windows file lock, preventing an imported package's atomic move.
        try (var stream = Files.newInputStream(path)) { return Font.createFont(Font.TRUETYPE_FONT, stream); }
    }
    static Path asset(Path base, String relative) throws IOException {
        if (base == null || relative.isBlank() || relative.contains("\\") || relative.contains(":")) {
            throw new IOException("Skin assets must use relative paths within their package");
        }
        final Path root = base.toRealPath();
        final Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root) || !candidate.toRealPath().startsWith(root) || !Files.isRegularFile(candidate)
                || Files.size(candidate) > 24 * 1024 * 1024) {
            throw new IOException("Invalid or oversized skin asset: " + relative);
        }
        return candidate;
    }
    private static BufferedImage image(Path base, String name, Map<Path, BufferedImage> images) throws IOException {
        final Path path = asset(base, name).toRealPath();
        if (images.containsKey(path)) { return images.get(path); }
        // Check dimensions before allocating the decoded bitmap.
        try (var bytes = Files.newInputStream(path); var input = ImageIO.createImageInputStream(bytes)) {
            final var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) { throw new IOException("Unsupported skin image: " + name); }
            final var reader = readers.next();
            try {
                reader.setInput(input);
                final long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > 16_777_216 || pixels + images.values().stream()
                        .mapToLong(i -> (long) i.getWidth() * i.getHeight()).sum() > 33_554_432) {
                    throw new IOException("Skin images exceed decoded size limit: " + name);
                }
                final BufferedImage decoded = reader.read(0);
                images.put(path, decoded);
                return decoded;
            } finally { reader.dispose(); }
        }
    }
    private static int integer(JsonObject json, String key, int fallback, int min, int max) {
        if (!json.has(key)) { return fallback; }
        final double value = json.get(key).getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < min || value > max) {
            throw new IllegalArgumentException("Invalid " + key);
        }
        return (int) value;
    }
    private static Color color(JsonObject json, String key) {
        if (!json.has(key)) { return null; }
        final String value = json.get(key).getAsString();
        if (!value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
            throw new IllegalArgumentException("Colors must be #RRGGBB or #RRGGBBAA");
        }
        return new Color(Integer.parseInt(value.substring(1, 3), 16), Integer.parseInt(value.substring(3, 5), 16),
                Integer.parseInt(value.substring(5, 7), 16), value.length() == 9 ? Integer.parseInt(value.substring(7, 9), 16) : 255);
    }

    /** Styles existing controls and future descendants, then restores them on layout switch. */
    public Runnable apply(JComponent root, String id) {
        final Style style = style(id);
        final var restorers = new IdentityHashMap<Component, Runnable>();
        final ContainerAdapter listener = new ContainerAdapter() {
            @Override public void componentAdded(ContainerEvent e) { attach(e.getChild()); }
            @Override public void componentRemoved(ContainerEvent e) { detach(e.getChild()); }
            private void detach(Component c) {
                if (c instanceof Container container) {
                    container.removeContainerListener(this);
                    for (Component child : container.getComponents()) { detach(child); }
                }
                final Runnable restore = restorers.remove(c);
                if (restore != null) { restore.run(); }
            }
            private void attach(Component c) {
                if (c instanceof CardPanel || restorers.containsKey(c)) { return; }
                final Font oldFont = c.getFont();
                final Color oldText = c.getForeground();
                final Color oldBackground = c.getBackground();
                final Runnable restoreBindings = c instanceof FSkin.ISkinnedComponent<?> skinned
                        ? skinned.getSkin().preserveAppearance() : () -> { };
                final Object phaseId = c instanceof JComponent jc ? jc.getClientProperty("forge.matchPhase") : null;
                final boolean document = id.equals("document") || id.startsWith("document.");
                final String textId = id.startsWith("document.") ? "text." + id.substring("document.".length()) : "text";
                final Style controlStyle = phaseId instanceof String name ? style("PHASE." + name) : document
                        ? (c instanceof javax.swing.text.JTextComponent && styles.containsKey(textId) ? styles.get(textId) : style(c instanceof javax.swing.text.JTextComponent ? "text"
                                : c instanceof FButton || c instanceof FLabel label && label.getCommand() != null
                                        ? "button" : "default")) : style;
                c.setFont(font(controlStyle.fontSize()));
                c.setForeground(text);
                final boolean oldOpaque = c instanceof JComponent jc && jc.isOpaque();
                final boolean transparentText = c instanceof javax.swing.text.JTextComponent && controlStyle.visual() != null;
                if (c instanceof javax.swing.text.JTextComponent && controlStyle.fill() != null) {
                    c.setBackground(controlStyle.fill());
                }
                if (transparentText) {
                    ((JComponent) c).setOpaque(false); // Floating shell supplies alpha; glyphs keep full opacity.
                    if (controlStyle.visual().textColor() != null) { c.setForeground(controlStyle.visual().textColor()); }
                }
                final Object old = c instanceof JComponent jc ? jc.getClientProperty(STYLE_PROPERTY) : null;
                final Object oldActive = c instanceof JComponent jc ? jc.getClientProperty("forge.matchSkinActive") : null;
                final var format = documentFormats.getOrDefault(id.startsWith("document.") ? id.substring(9) : id, documentFormats.get("default"));
                final Runnable restoreFormat = c instanceof javax.swing.text.JTextComponent tc && format != null ? format.apply(tc) : () -> { };
                final java.awt.event.MouseAdapter stateListener;
                if (c instanceof FLabel label && controlStyle.visual() != null) {
                    stateListener = new java.awt.event.MouseAdapter() {
                        private void update(boolean over, boolean pressed) {
                            label.putClientProperty("forge.matchSkinHover", over);
                            label.putClientProperty("forge.matchSkinPressed", pressed); label.repaint();
                        }
                        @Override public void mouseEntered(java.awt.event.MouseEvent e) { update(true, false); }
                        @Override public void mouseExited(java.awt.event.MouseEvent e) { update(false, false); }
                        @Override public void mousePressed(java.awt.event.MouseEvent e) { update(true, javax.swing.SwingUtilities.isLeftMouseButton(e)); }
                        @Override public void mouseReleased(java.awt.event.MouseEvent e) { update(label.contains(e.getPoint()), false); }
                    };
                    label.addMouseListener(stateListener);
                } else { stateListener = null; }
                restorers.put(c, () -> {
                    restoreFormat.run();
                    if (stateListener != null) {
                        c.removeMouseListener(stateListener);
                        ((JComponent) c).putClientProperty("forge.matchSkinHover", null);
                        ((JComponent) c).putClientProperty("forge.matchSkinPressed", null);
                    }
                    c.setFont(oldFont); c.setForeground(oldText); c.setBackground(oldBackground); restoreBindings.run();
                    if (c instanceof JComponent jc) {
                        jc.putClientProperty(STYLE_PROPERTY, old); jc.putClientProperty("forge.matchSkinActive", oldActive);
                        // Surface restoration independently owns panel/viewport opacity, including dynamic detach.
                        if (transparentText) { jc.setOpaque(oldOpaque); }
                    }
                });
                if (c instanceof JComponent jc) {
                    jc.putClientProperty(STYLE_PROPERTY, controlStyle);
                }
                if (c instanceof Container container) {
                    container.addContainerListener(this);
                    for (Component child : container.getComponents()) { attach(child); }
                }
            }
        };
        // Reuse the same listener traversal for the initial tree.
        listener.componentAdded(new ContainerEvent(root, ContainerEvent.COMPONENT_ADDED, root));
        return () -> listener.componentRemoved(new ContainerEvent(root, ContainerEvent.COMPONENT_REMOVED, root));
    }
}
