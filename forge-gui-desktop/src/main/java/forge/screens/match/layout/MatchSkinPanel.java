package forge.screens.match.layout;

import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.BorderFactory;
import javax.swing.JPanel;

/** Decorative shell; the original live component owns every input event. */
final class MatchSkinPanel extends JPanel {
    private final MatchSkinTheme.Style style;
    private boolean active;
    private boolean paintSurface = true;
    void setPaintSurface(boolean value) { paintSurface = value; }
    MatchSkinPanel(MatchSkinTheme.Style style) {
        super(new BorderLayout());
        this.style = style;
        setOpaque(false);
        if (style != null) { setBorder(BorderFactory.createEmptyBorder(style.padding(), style.padding(), style.padding(), style.padding())); }
    }
    void setActive(boolean value) { if (value != active) { active = value; repaint(); } }
    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (style != null && paintSurface) { style.state(isEnabled(), false, false, active).paint((Graphics2D) g, getWidth(), getHeight(), active, false); }
    }
    @Override protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        if (style != null && paintSurface) { style.state(isEnabled(), false, false, active).paintFrame((Graphics2D) g, getWidth(), getHeight()); }
    }
}
