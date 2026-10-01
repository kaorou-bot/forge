package forge.toolbox;

import java.awt.Component;
import java.awt.event.HierarchyEvent;
import java.lang.reflect.Array;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;
import org.testng.Assert;
import org.testng.annotations.Test;

public class FScrollPaneOverlayTest {
    @Test public void hiddenAncestorAndDetachedPaneRemoveGlobalScrollArrows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (forge.gui.GuiBase.getInterface() == null) { forge.gui.GuiBase.setInterface(new forge.GuiDesktop()); }
            try {
                final var overlayField = FAbsolutePositioner.class.getDeclaredField("panel");
                overlayField.setAccessible(true);
                final var overlay = (JPanel) overlayField.get(FAbsolutePositioner.SINGLETON_INSTANCE);
                for (boolean remove : new boolean[]{false, true}) {
                    final var pane = new FScrollPane(new JPanel(), false, true);
                    final var constructor = Class.forName(FScrollPane.class.getName() + "$BottomArrowButton")
                            .getDeclaredConstructor(FScrollPane.class, JScrollBar.class);
                    constructor.setAccessible(true);
                    final var arrow = (Component) constructor.newInstance(pane, pane.getVerticalScrollBar());
                    final var buttonsField = FScrollPane.class.getDeclaredField("arrowButtons");
                    buttonsField.setAccessible(true);
                    Array.set(buttonsField.get(pane), 3, arrow);
                    overlay.add(arrow);
                    arrow.setVisible(true);
                    if (remove) { pane.removeNotify(); }
                    else {
                        final var event = new HierarchyEvent(pane, HierarchyEvent.HIERARCHY_CHANGED, pane, null, HierarchyEvent.SHOWING_CHANGED);
                        for (var listener : pane.getHierarchyListeners()) { listener.hierarchyChanged(event); }
                    }
                    Assert.assertNull(arrow.getParent(), "Arrow must not survive its hidden/detached scroll pane");
                    Assert.assertFalse(arrow.isVisible());
                }
            } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
        });
    }
}
