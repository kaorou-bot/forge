package forge.screens.match.layout;

import forge.screens.match.CMatchUI;
import forge.screens.match.views.VField;
import forge.view.FView;
import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** Per-match scene adapter. The transparent root intercepts input only inside live widgets. */
final class MatchSceneView {
    private final CMatchUI match;
    private final MatchSceneLayout definition;
    private final Map<String, MatchWidgetRegistry.Widget> widgets = new LinkedHashMap<>();
    private final java.util.List<Runnable> restorers = new java.util.ArrayList<>();
    private final JPanel phases = new JPanel(new java.awt.BorderLayout());
    private final JPanel layer = new JPanel(null) {
        @Override public boolean contains(int x, int y) {
            for (Component child : getComponents()) {
                if (child.isVisible() && child.contains(x - child.getX(), y - child.getY())) { return true; }
            }
            return false;
        }
    };

    MatchSceneView(CMatchUI match, MatchSceneLayout definition) {
        this.match = match;
        this.definition = definition;
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
                            new MatchWidgetRegistry.Context(match, field, type));
            widgets.put(id, widget);
            layer.add(widget.component());
            restorers.add(definition.styleFor(id).apply(widget.component()));
        }
    }

    void refresh() {
        if (!match.isCurrentScreen()) { return; }
        widgets.values().forEach(widget -> widget.refresh().run());
        final var game = match.getGameView();
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
        if (layer.getParent() != host) { host.add(layer); }
        host.setComponentZOrder(layer, 0);
        layer.setBounds(0, 0, host.getWidth(), host.getHeight());
        definition.widgets().forEach((id, bounds) -> {
            final int x = (int) Math.round(bounds.x() * host.getWidth());
            final int y = (int) Math.round(bounds.y() * host.getHeight());
            widgets.get(id).component().setBounds(x, y, (int) Math.round((bounds.x() + bounds.w()) * host.getWidth()) - x,
                    (int) Math.round((bounds.y() + bounds.h()) * host.getHeight()) - y);
        });
        layer.validate();
    }

    void dispose() {
        if (layer.getParent() != null) { layer.getParent().remove(layer); }
        restorers.forEach(Runnable::run);
        restorers.clear();
        widgets.values().forEach(widget -> widget.dispose().run());
        phases.removeAll();
        layer.removeAll();
        widgets.clear();
        for (VField field : match.getFieldViews()) {
            field.getPhaseIndicator().setHorizontal(false);
            field.getDetailsPanel().restoreDefaultComposition();
            field.setSceneAvatar(false);
        }
    }
}
