package forge.screens.match.layout;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.miginfocom.swing.MigLayout;

/** Desktop extension point. Reuse the supplied live components and their input handlers. */
@FunctionalInterface
public interface MatchFieldLayout {
    enum Part { AVATAR, PHASES, BATTLEFIELD, DETAILS }

    void populate(JPanel host, Map<Part, JComponent> parts);

    MatchFieldLayout BATTLEFIELD_ONLY = (host, parts) -> {
        host.setLayout(new java.awt.BorderLayout());
        host.add(parts.get(Part.BATTLEFIELD), java.awt.BorderLayout.CENTER);
    };

    MatchFieldLayout CLASSIC = (host, parts) -> {
        host.setLayout(new MigLayout("insets 0, gap 0"));
        host.add(parts.get(Part.AVATAR), "w 10%!, h 35%!");
        host.add(parts.get(Part.PHASES), "w 5%!, h 100%!, span 1 2");
        host.add(parts.get(Part.BATTLEFIELD), "w 85%!, h 100%!, span 1 2, wrap");
        host.add(parts.get(Part.DETAILS), "w 10%!, h 64%!, gapleft 1px");
    };

    static MatchFieldLayout relative(final Map<Part, MatchUiLayout.Bounds> bounds) {
        final Map<Part, MatchUiLayout.Bounds> positions = Map.copyOf(bounds);
        if (positions.size() != Part.values().length) {
            throw new IllegalArgumentException("field must contain AVATAR, PHASES, BATTLEFIELD and DETAILS");
        }
        return (host, parts) -> {
            host.setLayout(new LayoutManager() {
                @Override public void addLayoutComponent(String name, Component component) { }
                @Override public void removeLayoutComponent(Component component) { }
                @Override public Dimension preferredLayoutSize(Container parent) { return new Dimension(600, 250); }
                @Override public Dimension minimumLayoutSize(Container parent) { return new Dimension(0, 0); }
                @Override public void layoutContainer(Container parent) {
                    final var insets = parent.getInsets();
                    final int width = Math.max(0, parent.getWidth() - insets.left - insets.right);
                    final int height = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
                    positions.forEach((part, b) -> {
                        final int x = (int) Math.round(b.x() * width);
                        final int y = (int) Math.round(b.y() * height);
                        parts.get(part).setBounds(insets.left + x, insets.top + y,
                                (int) Math.round((b.x() + b.w()) * width) - x,
                                (int) Math.round((b.y() + b.h()) * height) - y);
                    });
                }
            });
            for (Part part : Part.values()) {
                host.add(parts.get(part));
            }
        };
    }
}
