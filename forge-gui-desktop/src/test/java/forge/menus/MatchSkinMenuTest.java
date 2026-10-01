package forge.menus;

import forge.gui.GuiChoose;
import forge.screens.match.layout.DesktopMatchUi;
import forge.screens.match.layout.MatchSkinPackages;
import forge.screens.match.layout.MatchUiLayout;
import forge.toolbox.FOptionPane;
import forge.util.Localizer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.mockito.Mockito.*;

public class MatchSkinMenuTest {
    @BeforeClass public void language() throws Exception {
        new forge.screens.match.layout.MatchSkinV4Test().initializeSkinPalette();
        Localizer.getInstance().initialize("en-US", "../forge-gui/res/languages");
        // FOptionPane captures these icons during class initialization, even when its dialogs are mocked.
        final var setIcon = forge.toolbox.FSkin.SkinIcon.class.getDeclaredMethod("setIcon",
                forge.localinstance.skin.FSkinProp.class, javax.swing.ImageIcon.class);
        setIcon.setAccessible(true);
        final var icon = new javax.swing.ImageIcon(new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB));
        for (var kind : List.of(forge.localinstance.skin.FSkinProp.ICO_QUESTION, forge.localinstance.skin.FSkinProp.ICO_INFORMATION,
                forge.localinstance.skin.FSkinProp.ICO_WARNING, forge.localinstance.skin.FSkinProp.ICO_ERROR)) {
            setIcon.invoke(null, kind, icon);
        }
    }

    @Test public void savedChoicesRefreshAfterImportAndShowWhichSkinIsSelected() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var packages = mockStatic(MatchSkinPackages.class); var desktop = mockStatic(DesktopMatchUi.class)) {
                final var names = new AtomicReference<>(List.of("first-skin"));
                final var selected = new AtomicReference<>("first-skin");
                final var provider = new AtomicReference<>("package");
                packages.when(MatchSkinPackages::installed).thenAnswer(i -> names.get());
                packages.when(MatchSkinPackages::directory).thenReturn(Path.of("C:/skin-test-only"));
                desktop.when(DesktopMatchUi::providers).thenReturn(List.of(
                        new DesktopMatchUi.Provider("classic", "lblDesktopMatchUiClassic", MatchUiLayout::classic),
                        new DesktopMatchUi.Provider("package", "lblDesktopMatchUiPackage", MatchUiLayout::classic)));
                desktop.when(DesktopMatchUi::selection).thenAnswer(i -> provider.get());
                desktop.when(DesktopMatchUi::selectedPackage).thenAnswer(i -> selected.get());
                desktop.when(() -> DesktopMatchUi.selectPackage("second-skin")).thenAnswer(i -> { selected.set("second-skin"); return null; });
                desktop.when(() -> DesktopMatchUi.select("classic")).thenAnswer(i -> { provider.set("classic"); return null; });
                final var reloads = new AtomicInteger();
                final var menu = new MatchSkinMenu(reloads::incrementAndGet, reloads::incrementAndGet);
                Assert.assertNull(find(menu, "package"), "No ambiguous unnamed imported-skin option");
                Assert.assertTrue(((JRadioButtonMenuItem) find(saved(menu), "first-skin")).isSelected());
                names.set(List.of("first-skin", "second-skin"));
                open(menu);
                Assert.assertEquals(saved(menu).getItemCount(), 2);
                find(saved(menu), "second-skin").doClick(0);
                Assert.assertTrue(((JRadioButtonMenuItem) find(saved(menu), "second-skin")).isSelected());
                Assert.assertFalse(((JRadioButtonMenuItem) find(saved(menu), "first-skin")).isSelected());
                Assert.assertEquals(reloads.get(), 1);
                find(menu, "classic").doClick(0);
                Assert.assertFalse(((JRadioButtonMenuItem) find(saved(menu), "second-skin")).isSelected());
                Assert.assertTrue(((JRadioButtonMenuItem) find(menu, "classic")).isSelected());
                names.set(List.of()); open(menu);
                Assert.assertFalse(find(menu, "delete-skin").isEnabled());
                Assert.assertFalse(saved(menu).getItem(0).isEnabled());
            }
        });
    }

    @Test public void cancelledDeletionLeavesFilesSelectionAndCurrentMatchUntouched() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var packages = mockStatic(MatchSkinPackages.class); var desktop = mockStatic(DesktopMatchUi.class);
                    var choose = mockStatic(GuiChoose.class); var prompts = mockStatic(FOptionPane.class)) {
                packages.when(MatchSkinPackages::installed).thenReturn(List.of("current-skin"));
                packages.when(MatchSkinPackages::directory).thenReturn(Path.of("C:/skin-test-only"));
                desktop.when(DesktopMatchUi::providers).thenReturn(List.of());
                desktop.when(DesktopMatchUi::selection).thenReturn("package");
                desktop.when(DesktopMatchUi::selectedPackage).thenReturn("current-skin");
                choose.when(() -> GuiChoose.oneOrNone(anyString(), eq(List.of("current-skin")))).thenReturn("current-skin");
                final var reloads = new AtomicInteger();
                final var menu = new MatchSkinMenu(reloads::incrementAndGet, reloads::incrementAndGet);
                find(menu, "delete-skin").doClick(0);
                prompts.verify(() -> FOptionPane.showConfirmDialog(contains("Classic layout"), anyString(), anyString(), anyString(), eq(false)));
                desktop.verify(() -> DesktopMatchUi.forgetPackage(anyString()), never());
                packages.verify(() -> MatchSkinPackages.remove(anyString()), never());
                Assert.assertEquals(reloads.get(), 0);
                Assert.assertEquals(saved(menu).getItemCount(), 1);
            }
        });
    }

    @Test public void failedActiveSceneDetachNeverStartsFileDeletion() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var packages = mockStatic(MatchSkinPackages.class); var desktop = mockStatic(DesktopMatchUi.class);
                    var choose = mockStatic(GuiChoose.class); var prompts = mockStatic(FOptionPane.class)) {
                packages.when(MatchSkinPackages::installed).thenReturn(List.of("current-skin"));
                packages.when(MatchSkinPackages::directory).thenReturn(Path.of("C:/skin-test-only"));
                desktop.when(DesktopMatchUi::providers).thenReturn(List.of());
                desktop.when(DesktopMatchUi::selection).thenReturn("package");
                desktop.when(DesktopMatchUi::selectedPackage).thenReturn("current-skin");
                desktop.when(() -> DesktopMatchUi.forgetPackage("current-skin")).thenReturn(true);
                choose.when(() -> GuiChoose.oneOrNone(anyString(), eq(List.of("current-skin")))).thenReturn("current-skin");
                prompts.when(() -> FOptionPane.showConfirmDialog(anyString(), anyString(), anyString(), anyString(), eq(false))).thenReturn(true);
                final var detached = new AtomicInteger();
                final var menu = new MatchSkinMenu(() -> { throw new AssertionError("Asynchronous reload must not be used for deletion"); }, () -> {
                    // Fallback is persisted before detaching; files must be intact if detaching fails.
                    desktop.verify(() -> DesktopMatchUi.forgetPackage("current-skin"));
                    detached.incrementAndGet();
                    throw new IllegalStateException("Cannot detach active scene");
                });
                find(menu, "delete-skin").doClick(0);
                Assert.assertEquals(detached.get(), 1);
                prompts.verify(() -> FOptionPane.showErrorDialog("Cannot detach active scene"));
                packages.verify(() -> MatchSkinPackages.remove(anyString()), never());
                Assert.assertTrue(find(menu, "delete-skin").isEnabled(), "Failure must clear the busy state for retry");
            }
        });
    }

    private static JMenu saved(JMenu menu) { return (JMenu) find(menu, "saved-skins"); }
    private static JMenuItem find(JMenu menu, String command) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            final var item = menu.getItem(i);
            if (item != null && command.equals(item.getActionCommand())) { return item; }
        }
        return null;
    }
    private static void open(JMenu menu) {
        for (var listener : menu.getMenuListeners()) { listener.menuSelected(new MenuEvent(menu)); }
    }
}
