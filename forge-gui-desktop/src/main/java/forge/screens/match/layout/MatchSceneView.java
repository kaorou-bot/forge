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
    private final Map<String, JComponent> widgets = new LinkedHashMap<>();
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
        widgets.put("PHASES_ACTIVE", phases);
        for (VField field : match.getFieldViews()) {
            final String id = field.getDocumentID().name();
            widgets.put(id + ".AVATAR", field.getAvatarArea());
            widgets.put(id + ".DETAILS", field.getDetailsPanel());
            if (definition.widgets().containsKey(id + ".ZONES")) {
                widgets.put(id + ".ZONES", new SceneZoneView(match, field.getPlayer(), false));
            }
            if (definition.widgets().containsKey(id + ".HAND_BACKS")) {
                widgets.put(id + ".HAND_BACKS", new SceneZoneView(match, field.getPlayer(), true));
            }
            field.getPhaseIndicator().setHorizontal(true);
        }
        definition.widgets().keySet().forEach(id -> layer.add(widgets.get(id)));
    }

    void refresh() {
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
        final JPanel host = FView.SINGLETON_INSTANCE.getPnlContent();
        if (layer.getParent() != host) { host.add(layer); }
        host.setComponentZOrder(layer, 0);
        layer.setBounds(0, 0, host.getWidth(), host.getHeight());
        definition.widgets().forEach((id, bounds) -> {
            final int x = (int) Math.round(bounds.x() * host.getWidth());
            final int y = (int) Math.round(bounds.y() * host.getHeight());
            widgets.get(id).setBounds(x, y, (int) Math.round((bounds.x() + bounds.w()) * host.getWidth()) - x,
                    (int) Math.round((bounds.y() + bounds.h()) * host.getHeight()) - y);
        });
        layer.validate();
    }

    void dispose() {
        if (layer.getParent() != null) { layer.getParent().remove(layer); }
        for (VField field : match.getFieldViews()) { field.getPhaseIndicator().setHorizontal(false); }
        phases.removeAll();
        layer.removeAll();
        widgets.clear();
    }
}
