package forge.toolbox.special;

import java.util.EnumMap;
import java.util.Map;

import javax.swing.JPanel;

import forge.game.phase.PhaseType;
import forge.util.Localizer;
import net.miginfocom.swing.MigLayout;

public class PhaseIndicator extends JPanel {
    private static final long serialVersionUID = -863730022835609252L;

    private static final String CONSTRAINTS = "w 94%!, h 7.2%, gaptop 1%, gapleft 3%";

    private final Map<PhaseType, PhaseLabel> phaseLabels = new EnumMap<>(PhaseType.class);
    private final Map<PhaseType, String> shortNames = new EnumMap<>(PhaseType.class);
    private boolean horizontal;

    /** Reuses the labels so stop/yield listeners and state survive layout switches. */
    public void setHorizontal(boolean value) {
        if (horizontal == value) { return; }
        horizontal = value;
        removeAll();
        setLayout(value ? new java.awt.GridLayout(1, phaseLabels.size(), 3, 0)
                : new MigLayout("insets 0 0 1% 0, gap 0, wrap"));
        phaseLabels.forEach((phase, label) -> {
            label.setText(value ? phase.nameForUi : shortNames.get(phase));
            if (value) { add(label); } else { add(label, CONSTRAINTS); }
        });
        revalidate();
        repaint();
    }

    public PhaseIndicator() {
        this.setOpaque(false);
        this.setLayout(new MigLayout("insets 0 0 1% 0, gap 0, wrap"));
        addPhaseLabel("UP", PhaseType.UPKEEP);
        addPhaseLabel("DR", PhaseType.DRAW);
        addPhaseLabel("M1", PhaseType.MAIN1);
        addPhaseLabel("BC", PhaseType.COMBAT_BEGIN);
        addPhaseLabel("DA", PhaseType.COMBAT_DECLARE_ATTACKERS);
        addPhaseLabel("DB", PhaseType.COMBAT_DECLARE_BLOCKERS);
        addPhaseLabel("FS", PhaseType.COMBAT_FIRST_STRIKE_DAMAGE);
        addPhaseLabel("CD", PhaseType.COMBAT_DAMAGE);
        addPhaseLabel("EC", PhaseType.COMBAT_END);
        addPhaseLabel("M2", PhaseType.MAIN2);
        addPhaseLabel("ET", PhaseType.END_OF_TURN);
        addPhaseLabel("CL", PhaseType.CLEANUP);
    }

    private void addPhaseLabel(String caption, PhaseType phaseType) {
        PhaseLabel lbl = new PhaseLabel(caption);
        lbl.setToolTipText(Localizer.getInstance().getMessage("htmlPhaseTooltipFmt", phaseType.nameForUi));
        phaseLabels.put(phaseType, lbl);
        shortNames.put(phaseType, caption);
        add(lbl, CONSTRAINTS);
    }

    public PhaseLabel getLabelFor(final PhaseType phaseType) {
        return phaseLabels.get(phaseType);
    }

    public Iterable<PhaseLabel> allLabels() {
        return phaseLabels.values();
    }

    /** Resets all phase buttons to "inactive". "Enabled" state is preserved. */
    public void resetPhaseButtons() {
        for (PhaseLabel lbl : phaseLabels.values()) {
            lbl.setActive(false);
        }
    }
}
