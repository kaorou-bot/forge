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

    public record Style(Color fill, Color border, Color highlight, int radius, int padding,
            float fontSize, BufferedImage image) {
        public void paint(Graphics2D source, int width, int height, boolean active, boolean pressed) {
            final Graphics2D g = (Graphics2D) source.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
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
        final String category = type.equals("actions") ? "floating"
                : type.equals("tab") ? "button"
                : type.startsWith("ZONE_") ? "zone" : type.startsWith("AVATAR") ? "avatar"
                : type.equals("LIFE") ? "life" : type.equals("PHASES_ACTIVE") ? "phase"
                : type.startsWith("ACTION") || type.equals("OTHER_ZONES")
                        || type.equals("PROMPT_OK") || type.equals("PROMPT_CANCEL") ? "button" : "default";
        return styles.getOrDefault(category, styles.get("default"));
    }
    public Font font(float size) { return font.deriveFont(size); }

    static MatchSkinTheme read(JsonObject json, Path base) {
        MatchUiLayout.keys(json, Set.of("background", "font", "text", "styles"));
        try {
            final Map<Path, BufferedImage> images = new LinkedHashMap<>();
            final BufferedImage background = json.has("background") ? image(base, json.get("background").getAsString(), images) : null;
            final Font font = json.has("font")
                    ? readFont(asset(base, json.get("font").getAsString()))
                    : new Font(Font.SANS_SERIF, Font.PLAIN, 14);
            final Map<String, Style> styles = new LinkedHashMap<>();
            styles.put("default", new Style(null, null, null, 8, 2, 15, null));
            if (json.has("styles")) {
                for (var entry : json.getAsJsonObject("styles").entrySet()) {
                    final JsonObject s = entry.getValue().getAsJsonObject();
                    MatchUiLayout.keys(s, Set.of("fill", "border", "highlight", "radius", "padding", "fontSize", "image"));
                    styles.put(entry.getKey(), new Style(color(s, "fill"), color(s, "border"), color(s, "highlight"),
                            integer(s, "radius", 8, 0, 80), integer(s, "padding", 2, 0, 32),
                            integer(s, "fontSize", 15, 8, 64),
                            s.has("image") ? image(base, s.get("image").getAsString(), images) : null));
                }
            }
            return new MatchSkinTheme(background, font, json.has("text") ? color(json, "text") : Color.WHITE, styles);
        } catch (IOException | java.awt.FontFormatException e) {
            throw new IllegalArgumentException("Cannot load match skin asset: " + e.getMessage(), e);
        }
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
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
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
                final Style controlStyle = id.equals("document")
                        ? style(c instanceof javax.swing.text.JTextComponent ? "text"
                                : c instanceof FButton || c instanceof FLabel label && label.getCommand() != null
                                        ? "button" : "default") : style;
                c.setFont(font(controlStyle.fontSize()));
                c.setForeground(text);
                if (c instanceof javax.swing.text.JTextComponent && controlStyle.fill() != null) {
                    c.setBackground(controlStyle.fill());
                }
                final Object old = c instanceof JComponent jc ? jc.getClientProperty(STYLE_PROPERTY) : null;
                restorers.put(c, () -> {
                    c.setFont(oldFont); c.setForeground(oldText); c.setBackground(oldBackground); restoreBindings.run();
                    if (c instanceof JComponent jc) { jc.putClientProperty(STYLE_PROPERTY, old); }
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
