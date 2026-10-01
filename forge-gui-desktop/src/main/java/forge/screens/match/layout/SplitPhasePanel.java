package forge.screens.match.layout;

import forge.game.phase.PhaseType;
import forge.toolbox.special.PhaseIndicator;
import forge.toolbox.special.PhaseLabel;
import forge.util.Localizer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/** A single row of two-part chips, backed by the original per-player phase controls. */
final class SplitPhasePanel extends JPanel {
    private final List<Runnable> restorers = new ArrayList<>();

    SplitPhasePanel(PhaseIndicator upper, String upperName, String upperCaption,
            PhaseIndicator lower, String lowerName, String lowerCaption) {
        super(new BorderLayout(4, 0));
        setOpaque(false);
        final var legend = new JPanel(new GridLayout(2, 1));
        legend.setOpaque(false);
        legend.setPreferredSize(new Dimension(38, 0));
        legend.add(ownerLabel(upperCaption, upperName));
        legend.add(ownerLabel(lowerCaption, lowerName));
        add(legend, BorderLayout.WEST);
        final var chips = new JPanel(new GridLayout(1, 0, 3, 0));
        chips.setOpaque(false);
        add(chips, BorderLayout.CENTER);
        final boolean upperHorizontal = upper.isHorizontal(), lowerHorizontal = lower.isHorizontal();
        for (PhaseType phase : PhaseType.values()) {
            final PhaseLabel top = upper.getLabelFor(phase), bottom = lower.getLabelFor(phase);
            if (top == null || bottom == null) { continue; } // Untap has no priority stop.
            final var chip = new JPanel(new GridLayout(2, 1));
            chip.setOpaque(false);
            chips.add(chip);
            attach(chip, top, phase, PhaseLabel.SplitHalf.TOP, upperCaption, upperName);
            attach(chip, bottom, phase, PhaseLabel.SplitHalf.BOTTOM, lowerCaption, lowerName);
        }
        restorers.add(() -> upper.setHorizontal(upperHorizontal));
        restorers.add(() -> lower.setHorizontal(lowerHorizontal));
    }

    static String ownerCaption(boolean local, boolean otherLocal, boolean upper) {
        return Localizer.getInstance().getMessage(local != otherLocal
                ? local ? "lblPhaseOwnerSelf" : "lblPhaseOwnerOpponent"
                : upper ? "lblPhaseOwnerUpper" : "lblPhaseOwnerLower");
    }

    private static JLabel ownerLabel(String caption, String name) {
        final var label = new JLabel(caption, SwingConstants.CENTER);
        label.setToolTipText(caption + ": " + name);
        return label;
    }

    private void attach(JPanel chip, PhaseLabel label, PhaseType phase, PhaseLabel.SplitHalf half,
            String caption, String name) {
        final String oldText = label.getText(), oldTip = label.getToolTipText();
        final Object oldHalf = label.getClientProperty(PhaseLabel.SPLIT_HALF_PROPERTY);
        label.setText(Localizer.getInstance().getMessage("lblPhaseShort" + phase.name()));
        // Plain text avoids interpreting player names as HTML. Includes the full phase name.
        label.setToolTipText(Localizer.getInstance().getMessage("lblPhaseSplitTooltip", caption, name, phase.nameForUi));
        label.putClientProperty(PhaseLabel.SPLIT_HALF_PROPERTY, half);
        chip.add(label);
        restorers.add(() -> {
            label.setText(oldText);
            label.setToolTipText(oldTip);
            label.putClientProperty(PhaseLabel.SPLIT_HALF_PROPERTY, oldHalf);
        });
    }

    void dispose() {
        restorers.forEach(Runnable::run);
        restorers.clear();
        removeAll();
    }
}
