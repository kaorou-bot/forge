package forge.screens.match.layout;

import forge.screens.match.CMatchUI;
import forge.screens.match.views.VField;
import forge.view.FView;
import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JPanel;

/** Per-match scene adapter. The transparent root intercepts input only inside live widgets. */
final class MatchSceneView {
    private final CMatchUI match;
    private final MatchSceneLayout definition;
    private final Map<String, MatchWidgetRegistry.Widget> widgets = new LinkedHashMap<>();
    private final Map<String, MatchSkinPanel> shells = new LinkedHashMap<>();
    private final Map<String, VField> owners = new LinkedHashMap<>();
    private final Map<String, MatchFloatingPanel> floating = new LinkedHashMap<>();
    private final java.util.List<MatchUiLayout.Bounds> protectedAreas;
    private final JPanel background = new JPanel() {
        private java.awt.image.BufferedImage scaled;
        @Override public boolean contains(int x, int y) { return false; }
        @Override protected void paintComponent(java.awt.Graphics g) {
            if (definition.appearance() != null && definition.appearance().background() != null) {
                if (getWidth() <= 0 || getHeight() <= 0) { return; }
                if (scaled == null || scaled.getWidth() != getWidth() || scaled.getHeight() != getHeight()) {
                    scaled = new java.awt.image.BufferedImage(getWidth(), getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    final var graphics = scaled.createGraphics();
                    try { graphics.drawImage(definition.appearance().background(), 0, 0, getWidth(), getHeight(), null); }
                    finally { graphics.dispose(); }
                }
                g.drawImage(scaled, 0, 0, null);
            }
        }
    };
    private final java.util.List<Runnable> restorers = new java.util.ArrayList<>();
    private final JPanel phases = new JPanel(new java.awt.BorderLayout());
    private final JPanel layer = new JPanel(null) {
        @Override public boolean isOptimizedDrawingEnabled() { return false; }
        @Override public boolean contains(int x, int y) {
            for (Component child : getComponents()) {
                if (child.isVisible() && child.contains(x - child.getX(), y - child.getY())) { return true; }
            }
            return false;
        }
    };

    MatchSceneView(CMatchUI match, MatchUiLayout layout) {
        this.match = match;
        this.definition = layout.scene();
        protectedAreas = definition.protectedAreas(layout.regions());
        background.setOpaque(false);
        layer.setOpaque(false);
        phases.setOpaque(false);
        for (VField field : match.getFieldViews()) {
            final String id = field.getDocumentID().name();
            field.setSceneAvatar(definition.widgets().containsKey(id + ".AVATAR_IMAGE"));
            field.getPhaseIndicator().setHorizontal(true);
        }
        for (String id : definition.widgets().keySet()) {
            final int dot = id.indexOf('.');
            final String type = dot < 0 ? id : id.substring(dot + 1);
            final VField field = dot < 0 ? null : match.getFieldViews().stream()
                    .filter(f -> f.getDocumentID().name().equals(id.substring(0, dot))).findFirst().orElseThrow();
            final var widget = id.equals("PHASES_ACTIVE") ? new MatchWidgetRegistry.Widget(phases)
                    : MatchWidgetRegistry.create(definition.renderers().getOrDefault(id, type),
                            new MatchWidgetRegistry.Context(match, field, type, definition.appearance()));
            widgets.put(id, widget);
            owners.put(id, field);
            final var shell = new MatchSkinPanel(definition.appearance() == null ? null : definition.appearance().style(id));
            shells.put(id, shell);
            shell.add(widget.component());
            layer.add(shell);
            restorers.add(definition.styleFor(id).apply(widget.component()));
            if (definition.appearance() != null) { restorers.add(definition.appearance().apply(widget.component(), id)); }
        }
        definition.floating().forEach((id, spec) -> {
            final var panel = new MatchFloatingPanel(id, spec, definition);
            floating.put(id, panel);
            layer.add(panel);
            layer.setComponentZOrder(panel, 0);
        });
    }

    void refresh() {
        if (!match.isCurrentScreen()) { return; }
        widgets.values().forEach(widget -> widget.refresh().run());
        final var game = match.getGameView();
        shells.forEach((id, shell) -> {
            final var owner = owners.get(id);
            final var player = owner == null ? null : owner.getPlayer();
            shell.setVisible(definition.visibility().getOrDefault(id, MatchVisibility.ALWAYS).test(game, player));
            shell.setActive(MatchVisibility.PLAYER_ACTIVE.test(game, player)
                    && (id.endsWith(".AVATAR_IMAGE") || id.endsWith(".LIFE")));
        });
        floating.values().forEach(panel -> panel.refresh(game));
        VField active = game == null || game.getPlayerTurn() == null ? null : match.getFieldViewFor(game.getPlayerTurn());
        if (active == null && !match.getFieldViews().isEmpty()) { active = match.getFieldViews().get(0); }
        if (active != null && active.getPhaseIndicator().getParent() != phases) {
            phases.removeAll();
            phases.add(active.getPhaseIndicator());
            phases.revalidate();
            phases.setToolTipText(active.getPlayer().getName());
        }
        resize();
        layer.repaint();
    }

    void resize() {
        if (!match.isCurrentScreen()) { return; }
        final JPanel host = FView.SINGLETON_INSTANCE.getPnlContent();
        if (layer.getParent() == host && layer.getWidth() == host.getWidth() && layer.getHeight() == host.getHeight()
                && host.getComponentZOrder(layer) == 0) { return; }
        if (definition.appearance() != null && definition.appearance().background() != null) {
            if (background.getParent() != host) { host.add(background); }
            host.setComponentZOrder(background, host.getComponentCount() - 1);
            background.setBounds(0, 0, host.getWidth(), host.getHeight());
        }
        if (layer.getParent() != host) { host.add(layer); }
        host.setComponentZOrder(layer, 0);
        layer.setBounds(0, 0, host.getWidth(), host.getHeight());
        definition.widgets().forEach((id, bounds) -> {
            final int x = (int) Math.round(bounds.x() * host.getWidth());
            final int y = (int) Math.round(bounds.y() * host.getHeight());
            shells.get(id).setBounds(x, y, (int) Math.round((bounds.x() + bounds.w()) * host.getWidth()) - x,
                    (int) Math.round((bounds.y() + bounds.h()) * host.getHeight()) - y);
        });
        final var protectedPixels = protectedAreas.stream().map(b -> new java.awt.Rectangle(
                (int) (b.x() * host.getWidth()), (int) (b.y() * host.getHeight()),
                (int) (b.w() * host.getWidth()), (int) (b.h() * host.getHeight()))).toList();
        floating.values().forEach(panel -> panel.place(host.getWidth(), host.getHeight(), protectedPixels));
        layer.validate();
    }

    void dispose() {
        if (layer.getParent() != null) { layer.getParent().remove(layer); }
        if (background.getParent() != null) { background.getParent().remove(background); }
        floating.values().forEach(MatchFloatingPanel::dispose);
        floating.clear();
        for (int i = restorers.size() - 1; i >= 0; i--) { restorers.get(i).run(); }
        restorers.clear();
        widgets.values().forEach(widget -> widget.dispose().run());
        phases.removeAll();
        layer.removeAll();
        widgets.clear();
        shells.clear();
        owners.clear();
        for (VField field : match.getFieldViews()) {
            field.getPhaseIndicator().setHorizontal(false);
            field.getDetailsPanel().restoreDefaultComposition();
            field.setSceneAvatar(false);
        }
    }
}
