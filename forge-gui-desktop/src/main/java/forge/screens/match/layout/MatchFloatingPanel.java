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
    private Rectangle pending;
    private final javax.swing.Timer dragTimer = new javax.swing.Timer(16, e -> flushDrag());
    private List<Rectangle> protectedAreas = List.of();
    private final MatchSkinPreferences preferences;
    private final String variant;
    private MatchUiLayout.Bounds userBounds;
    private boolean collapsed, locked;
    private final JLabel grip = new JLabel("◢", javax.swing.SwingConstants.RIGHT);

    MatchFloatingPanel(String id, MatchFloatingSpec spec, MatchSceneLayout scene) {
        this(id, spec, scene, null, "base");
    }
    MatchFloatingPanel(String id, MatchFloatingSpec spec, MatchSceneLayout scene, MatchSkinPreferences preferences, String variant) {
        super(new BorderLayout(0, 5));
        this.id = id; this.spec = spec;
        this.preferences = preferences; this.variant = variant;
        final var saved = preferences == null ? null : preferences.panel(variant, id);
        if (saved != null) { userBounds = saved.bounds(); collapsed = saved.collapsed(); locked = saved.locked(); }
        setOpaque(false);
        final var theme = scene.appearance();
        final var shell = new MatchSkinPanel(theme == null ? new MatchSkinTheme.Style(
                new java.awt.Color(22, 25, 30, 245), java.awt.Color.GRAY, java.awt.Color.WHITE, 10, 8, 15, null)
                : theme.style("floating." + id));
        add(shell);
        title.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 5, 7, 5));
        title.setForeground(java.awt.Color.WHITE);
        shell.add(title, BorderLayout.NORTH);
        cell.setSceneMode(true);
        cell.setSceneSurface(scene.styleFor(id));
        cell.setSceneTheme(theme);
        cell.addDoc(EDocID.valueOf(id).getDoc());
        shell.add(cell, BorderLayout.CENTER);
        grip.setForeground(java.awt.Color.WHITE); grip.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.SE_RESIZE_CURSOR));
        grip.setToolTipText("拖动此角调整窗口大小");
        shell.add(grip, BorderLayout.SOUTH);
        cell.setVisible(!collapsed); grip.setVisible(!collapsed && !locked);
        title.setToolTipText("右键：收起 / 锁定 / 恢复位置；拖动标题移动；右下角调整大小");
        title.setComponentPopupMenu(popup());
        final MouseAdapter resize = new MouseAdapter() {
            private Point origin; private Rectangle start;
            @Override public void mousePressed(MouseEvent e) { if (!locked && SwingUtilities.isLeftMouseButton(e)) { origin = e.getLocationOnScreen(); start = getBounds(); } }
            @Override public void mouseDragged(MouseEvent e) {
                if (origin == null || getParent() == null) { return; }
                final var p = e.getLocationOnScreen();
                final var wanted = new Rectangle(start.x, start.y, Math.max(160, start.width + p.x - origin.x), Math.max(100, start.height + p.y - origin.y));
                final var safe = avoid(wanted, getParent().getWidth(), getParent().getHeight(), protectedAreas);
                if (safe != null) { setBounds(safe); revalidate(); getParent().repaint(); }
            }
            @Override public void mouseReleased(MouseEvent e) { if (origin != null) { mouseDragged(e); remember(); } origin = null; }
        };
        grip.addMouseListener(resize); grip.addMouseMotionListener(resize);
        restore = theme == null ? () -> { } : theme.apply(title, "floating." + id);
        dragTimer.setRepeats(false);
        title.setCursor(java.awt.Cursor.getPredefinedCursor(spec.draggable()
                ? java.awt.Cursor.MOVE_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
        final MouseAdapter drag = new MouseAdapter() {
            private Point origin;
            private Point start;
            @Override public void mousePressed(MouseEvent e) {
                if (spec.draggable() && !locked && SwingUtilities.isLeftMouseButton(e)) {
                    origin = e.getLocationOnScreen();
                    start = getLocation();
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (origin != null) { mouseDragged(e); flushDrag(); remember(); }
                origin = null;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (origin == null) { return; }
                final Point next = e.getLocationOnScreen();
                final var wanted = new Rectangle(start.x + next.x - origin.x, start.y + next.y - origin.y, getWidth(), getHeight());
                final var safe = avoid(wanted, getParent().getWidth(), getParent().getHeight(), protectedAreas);
                pending = safe == null ? getBounds() : safe;
                if (!dragTimer.isRunning()) { dragTimer.start(); }
            }
        };
        title.addMouseListener(drag);
        title.addMouseMotionListener(drag);
    }
    private javax.swing.JPopupMenu popup() {
        final var menu = new javax.swing.JPopupMenu();
        final var fold = new javax.swing.JMenuItem("收起 / 展开");
        fold.addActionListener(e -> {
            remember(); collapsed = !collapsed; cell.setVisible(!collapsed); grip.setVisible(!collapsed && !locked);
            if (getParent() != null) { place(getParent().getWidth(), getParent().getHeight(), protectedAreas); getParent().repaint(); }
            saveState();
        }); menu.add(fold);
        final var lock = new javax.swing.JMenuItem("锁定 / 解锁");
        lock.addActionListener(e -> { locked = !locked; grip.setVisible(!collapsed && !locked); saveState(); }); menu.add(lock);
        final var reset = new javax.swing.JMenuItem("恢复皮肤默认位置与大小");
        reset.addActionListener(e -> {
            userBounds = null; position = null; collapsed = false; locked = false; cell.setVisible(true); grip.setVisible(true);
            try { if (preferences != null) { preferences.panel(variant, id, null); } }
            catch (java.io.IOException ex) { forge.toolbox.FOptionPane.showErrorDialog(ex.getMessage()); }
            if (getParent() != null) { place(getParent().getWidth(), getParent().getHeight(), protectedAreas); getParent().repaint(); }
        }); menu.add(reset); return menu;
    }
    private void remember() {
        if (getParent() == null || getParent().getWidth() <= 0 || getParent().getHeight() <= 0) { return; }
        final double w = getParent().getWidth(), h = getParent().getHeight();
        final double height = collapsed ? (userBounds == null ? spec.bounds().h() : userBounds.h()) : getHeight() / h;
        userBounds = new MatchUiLayout.Bounds(getX() / w, getY() / h, getWidth() / w, Math.min(height, 1 - getY() / h));
        saveState();
    }
    private void saveState() {
        try { if (preferences != null) { preferences.panel(variant, id, new MatchSkinPreferences.Panel(userBounds == null ? spec.bounds() : userBounds, collapsed, locked)); } }
        catch (java.io.IOException ex) { forge.toolbox.FOptionPane.showErrorDialog("无法保存浮窗设置：" + ex.getMessage()); }
    }
    private void flushDrag() {
        dragTimer.stop();
        if (pending == null) { return; }
        final Rectangle old = getBounds();
        final Rectangle next = pending;
        pending = null;
        if (old.equals(next)) { return; }
        setLocation(next.x, next.y);
        position = next.getLocation();
        // Transparent overlapping Swing children require both exposed and covered pixels.
        if (getParent() != null) {
            final Rectangle dirty = old.union(next);
            getParent().repaint(dirty.x, dirty.y, dirty.width, dirty.height);
        }
    }
    static Rectangle constrained(Rectangle r, int width, int height) {
        return new Rectangle(Math.max(0, Math.min(r.x, width - r.width)),
                Math.max(0, Math.min(r.y, height - r.height)), Math.min(width, r.width), Math.min(height, r.height));
    }
    /** Closest valid edge-aligned location; null if the skin leaves no space of this size. */
    static Rectangle avoid(Rectangle wanted, int width, int height, List<Rectangle> obstacles) {
        final Rectangle base = constrained(wanted, width, height);
        if (obstacles.stream().noneMatch(base::intersects)) { return base; }
        final var xs = new java.util.LinkedHashSet<Integer>(List.of(base.x, 0, width - base.width));
        final var ys = new java.util.LinkedHashSet<Integer>(List.of(base.y, 0, height - base.height));
        for (Rectangle obstacle : obstacles) {
            xs.add(obstacle.x - base.width); xs.add(obstacle.x + obstacle.width);
            ys.add(obstacle.y - base.height); ys.add(obstacle.y + obstacle.height);
        }
        Rectangle best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int x : xs) { for (int y : ys) {
            final Rectangle candidate = constrained(new Rectangle(x, y, base.width, base.height), width, height);
            final double d = Point.distanceSq(base.x, base.y, candidate.x, candidate.y);
            if (d < distance && obstacles.stream().noneMatch(candidate::intersects)) {
                best = candidate; distance = d;
            }
        } }
        return best;
    }
    void place(int width, int height, List<Rectangle> protectedAreas) {
        this.protectedAreas = protectedAreas;
        final var b = userBounds == null ? spec.bounds() : userBounds;
        final var initial = new Rectangle((int) (b.x() * width), (int) (b.y() * height),
                Math.max(1, (int) (b.w() * width)), collapsed ? Math.min(height, title.getPreferredSize().height + 24) : Math.max(1, (int) (b.h() * height)));
        final Rectangle wanted = initial;
        final Rectangle placed = avoid(wanted, width, height, protectedAreas);
        // Validated layouts always reserve response/hand space. Keep the requested bounds
        // if a third-party layout leaves no room to also protect its preview.
        final Rectangle next = placed == null ? constrained(initial, width, height) : placed;
        final boolean resized = getWidth() != next.width || getHeight() != next.height;
        if (!getBounds().equals(next)) { setBounds(next); }
        if (resized) { validate(); }
    }
    void refresh(forge.game.GameView game) {
        setVisible(spec.visibleWhen().test(game, null));
        title.setText(id.equals("REPORT_STACK") ? Localizer.getInstance().getMessage("lblStack") + " · "
                + (game == null ? 0 : game.getStack().size()) : EDocID.valueOf(id).getDoc().getTabLabel().getText());
    }
    void dispose() {
        dragTimer.stop();
        pending = null;
        restore.run();
        cell.releaseSceneSurface();
        EDocID.valueOf(id).getDoc().setParentCell(null);
        cell.removeDoc(EDocID.valueOf(id).getDoc());
        cell.getBody().removeAll();
    }
}
