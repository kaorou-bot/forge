package forge.screens.match.layout;

import forge.screens.match.views.VDock;
import forge.screens.match.views.VDock.DockButtonId;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.util.Localizer;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.util.EnumMap;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** A modeless, skin-aware window over the existing dock commands. Owned by one match scene. */
final class MatchActionsWindow {
    private final MatchWidgetRegistry.Context context;
    private final VDock dock;
    private final FLabel launcher;
    private final EnumMap<DockButtonId, FButton> buttons = new EnumMap<>(DockButtonId.class);
    private JDialog window;
    private Runnable restore = () -> { };

    MatchActionsWindow(MatchWidgetRegistry.Context context) {
        this.context = context;
        dock = context.match().getCDock().getView();
        launcher = new FLabel.ButtonBuilder().text(Localizer.getInstance().getMessage("lblDesktopMatchUiActions")).build();
        launcher.setCommand((Runnable) this::show);
        launcher.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !launcher.isShowing()) {
                dispose();
            }
        });
    }

    MatchWidgetRegistry.Widget widget() {
        return new MatchWidgetRegistry.Widget(launcher, this::refresh, this::dispose);
    }

    private void show() {
        if (window == null) {
            window = new JDialog(SwingUtilities.getWindowAncestor(launcher), launcher.getText(), Dialog.ModalityType.MODELESS);
            window.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
            final var theme = context.theme();
            final var body = new MatchSkinPanel(theme == null ? null : theme.style("actions"));
            body.setLayout(new GridLayout(0, 2, 10, 10));
            body.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16));
            // Opaque base prevents translucent theme fills from exposing stale pixels in a native window.
            body.setOpaque(true);
            body.setBackground(theme == null ? java.awt.Color.DARK_GRAY
                    : theme.style("actions").fill() == null ? new java.awt.Color(16, 29, 41)
                    : new java.awt.Color(theme.style("actions").fill().getRGB()));
            for (DockButtonId id : DockButtonId.values()) {
                final var button = new FButton(dock.getActionLabel(id));
                button.setPreferredSize(new Dimension(185, 42));
                button.addActionListener(e -> {
                    if (!context.match().isCurrentScreen() || !dock.getButton(id).isEnabled()) { refresh(); return; }
                    // Restore match focus before commands that open dialogs or request card selection.
                    window.setVisible(false);
                    dock.performAction(id);
                });
                buttons.put(id, button);
                body.add(button);
            }
            if (theme != null) { restore = theme.apply(body, "document"); }
            window.setContentPane(body);
            window.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(KeyStroke.getKeyStroke("ESCAPE"), "hideActions");
            window.getRootPane().getActionMap().put("hideActions", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { window.setVisible(false); }
            });
            window.pack();
            window.setMinimumSize(window.getSize());
            window.setLocationRelativeTo(SwingUtilities.getWindowAncestor(launcher));
        }
        refresh();
        window.setVisible(true);
        window.toFront();
    }

    private void refresh() {
        buttons.forEach((id, button) -> {
            button.setEnabled(dock.getButton(id).isEnabled());
            button.setToolTipText(dock.getButton(id).getToolTipText());
        });
    }

    private void dispose() {
        if (window != null) { window.dispose(); window = null; }
        restore.run();
        restore = () -> { };
        buttons.clear();
    }
}
