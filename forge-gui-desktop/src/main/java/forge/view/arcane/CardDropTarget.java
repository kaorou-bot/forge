package forge.view.arcane;

import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** Hit testing against visible content, excluding clipped areas and other windows. */
final class CardDropTarget {
    private CardDropTarget() { }

    static boolean contains(MouseEvent event, JComponent target) {
        if (!event.getComponent().isShowing() || !target.isShowing()
                || SwingUtilities.getRootPane(target) == null
                || SwingUtilities.getRootPane(event.getComponent()) != SwingUtilities.getRootPane(target)) {
            return false;
        }
        final var point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), target);
        return target.getVisibleRect().contains(point);
    }
}
