package forge.screens.match.layout;

import forge.gui.framework.DragCell;
import forge.gui.framework.EDocID;
import forge.util.Localizer;
import java.awt.BorderLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Independent in-window document host with bounded header dragging. */
final class MatchFloatingPanel extends JPanel {
    private final MatchFloatingSpec spec;
    private final DragCell cell = new DragCell();
    private final JLabel title = new JLabel();
    private final Runnable restore;
    private final String id;
    private Point position;
    private List<Rectangle> protectedAreas = List.of();

    MatchFloatingPanel(String id, MatchFloatingSpec spec, MatchSceneLayout scene) {
        super(new BorderLayout(0, 5));
        this.id = id; this.spec = spec;
        setOpaque(false);
        final var theme = scene.appearance();
        final var shell = new MatchSkinPanel(theme == null ? new MatchSkinTheme.Style(
                new java.awt.Color(22, 25, 30, 245), java.awt.Color.GRAY, java.awt.Color.WHITE, 10, 8, 15, null)
                : theme.style("floating"));
        add(shell);
        title.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 5, 7, 5));
        title.setForeground(java.awt.Color.WHITE);
        shell.add(title, BorderLayout.NORTH);
        cell.setSceneMode(true);
        cell.setSceneSurface(scene.styleFor(id));
        cell.setSceneTheme(theme);
        cell.addDoc(EDocID.valueOf(id).getDoc());
        shell.add(cell, BorderLayout.CENTER);
        restore = theme == null ? () -> { } : theme.apply(title, "floating");
        final MouseAdapter drag = new MouseAdapter() {
            private Point origin;
            @Override public void mousePressed(MouseEvent e) {
                if (spec.draggable() && SwingUtilities.isLeftMouseButton(e)) {
                    origin = SwingUtilities.convertPoint(title, e.getPoint(), getParent());
                }
            }
            @Override public void mouseReleased(MouseEvent e) { origin = null; }
            @Override public void mouseDragged(MouseEvent e) {
                if (origin == null) { return; }
                final Point next = SwingUtilities.convertPoint(title, e.getPoint(), getParent());
                final Rectangle candidate = constrained(new Rectangle(getX() + next.x - origin.x,
                        getY() + next.y - origin.y, getWidth(), getHeight()), getParent().getWidth(), getParent().getHeight());
                if (protectedAreas.stream().noneMatch(candidate::intersects)) {
                    setBounds(candidate);
                    position = candidate.getLocation();
                    origin = next;
                }
            }
        };
        title.addMouseListener(drag);
        title.addMouseMotionListener(drag);
    }
    static Rectangle constrained(Rectangle r, int width, int height) {
        return new Rectangle(Math.max(0, Math.min(r.x, width - r.width)),
                Math.max(0, Math.min(r.y, height - r.height)), Math.min(width, r.width), Math.min(height, r.height));
    }
    void place(int width, int height, List<Rectangle> protectedAreas) {
        this.protectedAreas = protectedAreas;
        final var b = spec.bounds();
        final var initial = new Rectangle((int) (b.x() * width), (int) (b.y() * height),
                Math.max(1, (int) (b.w() * width)), Math.max(1, (int) (b.h() * height)));
        final Rectangle wanted = position == null ? initial : constrained(new Rectangle(position.x, position.y,
                initial.width, initial.height), width, height);
        if (protectedAreas.stream().anyMatch(wanted::intersects)) { position = null; setBounds(initial); }
        else { setBounds(wanted); }
        validate();
    }
    void refresh(forge.game.GameView game) {
        setVisible(spec.visibleWhen().test(game, null));
        title.setText(id.equals("REPORT_STACK") ? Localizer.getInstance().getMessage("lblStack") + " · "
                + (game == null ? 0 : game.getStack().size()) : EDocID.valueOf(id).getDoc().getTabLabel().getText());
    }
    void dispose() {
        restore.run();
        cell.releaseSceneSurface();
        EDocID.valueOf(id).getDoc().setParentCell(null);
        cell.removeDoc(EDocID.valueOf(id).getDoc());
        cell.getBody().removeAll();
    }
}
