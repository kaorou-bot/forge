package forge.screens.match.layout;

import com.google.gson.JsonObject;
import forge.toolbox.FPanel;
import forge.view.arcane.CardPanel;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;

/** Container decoration is independent of its contents. Does not alter card or button rendering. */
public record MatchSurfaceStyle(boolean border, boolean background, boolean title) {
    public static final MatchSurfaceStyle CLEAR = new MatchSurfaceStyle(false, false, false);

    static MatchSurfaceStyle read(JsonObject json) {
        MatchUiLayout.keys(json, Set.of("border", "background", "title"));
        return new MatchSurfaceStyle(flag(json, "border"), flag(json, "background"), flag(json, "title"));
    }
    private static boolean flag(JsonObject json, String name) {
        if (!json.has(name)) { return false; }
        if (!json.get(name).isJsonPrimitive() || !json.getAsJsonPrimitive(name).isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return json.get(name).getAsBoolean();
    }

    /** Returns a restoration action for reusing the same components in a different presentation. */
    public Runnable apply(JComponent root) {
        final List<Runnable> restore = new ArrayList<>();
        apply(root, restore);
        return () -> { for (int i = restore.size() - 1; i >= 0; i--) { restore.get(i).run(); } };
    }

    private void apply(JComponent component, List<Runnable> restore) {
        if (component instanceof CardPanel) { return; }
        final boolean container = component instanceof JPanel || component instanceof JScrollPane || component instanceof JViewport;
        if (!container) { return; }
        final var oldBorder = component.getBorder();
        final boolean oldOpaque = component.isOpaque();
        restore.add(() -> { component.setBorder(oldBorder); component.setOpaque(oldOpaque); });
        if (!border) { component.setBorder(null); }
        if (!background) { component.setOpaque(false); }
        if (component instanceof FPanel panel) {
            final boolean oldFrame = panel.isBorderToggle(), oldFill = panel.isBackgroundToggle();
            restore.add(() -> { panel.setBorderToggle(oldFrame); panel.setBackgroundToggle(oldFill); });
            panel.setBorderToggle(border && oldFrame);
            panel.setBackgroundToggle(background && oldFill);
        }
        if (component instanceof JScrollPane scroll) {
            final var viewportBorder = scroll.getViewportBorder();
            restore.add(() -> scroll.setViewportBorder(viewportBorder));
            if (!border) { scroll.setViewportBorder(null); }
        }
        if (container) {
            for (Component child : component.getComponents()) {
                if (child instanceof JComponent jc) { apply(jc, restore); }
            }
        }
    }
}
