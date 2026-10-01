package forge.menus;

import forge.gui.GuiChoose;
import forge.gui.GuiUtils;
import forge.screens.match.layout.DesktopMatchUi;
import forge.screens.match.layout.MatchSkinPackages;
import forge.toolbox.FOptionPane;
import forge.util.Localizer;
import java.io.IOException;
import javax.swing.ButtonGroup;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingWorker;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Rebuilds the saved-skin list on opening; imports and removals never block the game EDT. */
final class MatchSkinMenu extends JMenu {
    private static boolean busy;
    private final Localizer localizer = Localizer.getInstance();
    private final Runnable reload;
    private final Runnable detachBeforeDelete;

    MatchSkinMenu(Runnable reload, Runnable detachBeforeDelete) {
        super(Localizer.getInstance().getMessage("lblDesktopMatchUi"));
        this.reload = reload;
        this.detachBeforeDelete = detachBeforeDelete;
        refreshItems();
        addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent e) { refreshItems(); }
            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        });
    }

    private void refreshItems() {
        removeAll();
        if (busy) {
            final var waiting = new JMenuItem(localizer.getMessage("lblDesktopMatchUiBusy"));
            waiting.setEnabled(false); addSized(this, waiting); return;
        }
        final String selection = DesktopMatchUi.selection();
        final String selectedPackage = DesktopMatchUi.selectedPackage();
        final ButtonGroup group = new ButtonGroup();
        for (var provider : DesktopMatchUi.providers()) {
            if (provider.id().equals("package")) { continue; } // Each saved package is a named choice below.
            final var item = new JRadioButtonMenuItem(localizer.getMessage(provider.label()));
            item.setSelected(provider.id().equals(selection));
            item.setActionCommand(provider.id());
            item.addActionListener(e -> change(() -> DesktopMatchUi.select(provider.id())));
            group.add(item); addSized(this, item);
        }
        addSeparator();
        final var saved = new JMenu(localizer.getMessage("lblDesktopMatchUiPackages"));
        saved.setActionCommand("saved-skins");
        final var names = MatchSkinPackages.installed();
        for (String name : names) {
            final var item = new JRadioButtonMenuItem(name);
            item.setSelected(selection.equals("package") && name.equals(selectedPackage));
            item.setActionCommand(name);
            item.setToolTipText(MatchSkinPackages.directory().resolve(name).toString());
            item.addActionListener(e -> change(() -> DesktopMatchUi.selectPackage(name)));
            group.add(item); addSized(saved, item);
        }
        if (names.isEmpty()) {
            final var empty = new JMenuItem(localizer.getMessage("lblDesktopMatchUiNoPackages"));
            empty.setEnabled(false); addSized(saved, empty);
        }
        addSized(this, saved);
        final var importSkin = new JMenuItem(localizer.getMessage("lblDesktopMatchUiImport"));
        importSkin.setActionCommand("import-skin");
        importSkin.addActionListener(e -> importSkin()); addSized(this, importSkin);
        final var remove = new JMenuItem(localizer.getMessage("lblDesktopMatchUiDelete"));
        remove.setActionCommand("delete-skin");
        remove.setEnabled(!names.isEmpty());
        remove.addActionListener(e -> removeSkin()); addSized(this, remove);
        final var refresh = new JMenuItem(localizer.getMessage("lblDesktopMatchUiReload"));
        refresh.setActionCommand("reload-skin");
        refresh.addActionListener(e -> { if (!busy) { reload.run(); } }); addSized(this, refresh);
        final var settings = new JMenuItem("皮肤个人设置…");
        final var current = DesktopMatchUi.current();
        settings.setEnabled(current != null && current.isScene());
        settings.addActionListener(e -> current.showSettings()); addSized(this, settings);
        final var editor = new JMenuItem("皮肤布局制作（几何预览）…");
        editor.setEnabled(current != null && current.isScene());
        editor.addActionListener(e -> forge.screens.match.layout.MatchSkinEditor.show(current)); addSized(this, editor);
    }

    private static void addSized(JMenu parent, JMenuItem item) {
        GuiUtils.setMenuItemSize(item);
        parent.add(item);
    }
    @FunctionalInterface private interface Change { void run() throws IOException; }
    private void change(Change action) {
        if (busy) { return; }
        try { action.run(); reload.run(); }
        catch (IOException ex) { FOptionPane.showErrorDialog(ex.getMessage()); }
        finally { refreshItems(); }
    }

    private void importSkin() {
        if (busy) { return; }
        final var chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Forge match skin (*.zip)", "zip"));
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) { return; }
        final var archive = chooser.getSelectedFile().toPath();
        busy = true; refreshItems();
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return MatchSkinPackages.install(archive); }
            @Override protected void done() {
                try { DesktopMatchUi.selectPackage(get()); reload.run(); }
                catch (Exception ex) { showFailure(ex); }
                finally { busy = false; refreshItems(); }
            }
        }.execute();
    }

    private void removeSkin() {
        if (busy) { return; }
        final String name = GuiChoose.oneOrNone(localizer.getMessage("lblDesktopMatchUiDeleteChoose"), MatchSkinPackages.installed());
        if (name == null) { return; }
        final String active = "package".equals(DesktopMatchUi.selection()) && name.equals(DesktopMatchUi.selectedPackage())
                ? "\n" + localizer.getMessage("lblDesktopMatchUiDeleteActive") : "";
        if (!FOptionPane.showConfirmDialog(localizer.getMessage("lblDesktopMatchUiDeleteConfirm", name) + active,
                localizer.getMessage("lblDesktopMatchUiDelete"), localizer.getMessage("lblDelete"), localizer.getMessage("lblCancel"), false)) { return; }
        busy = true; refreshItems();
        try {
            // Persist fallback first. On any failure, do not remove the active package's files.
            if (DesktopMatchUi.forgetPackage(name)) { detachBeforeDelete.run(); }
        } catch (Exception ex) {
            busy = false; refreshItems(); showFailure(ex); return;
        }
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { MatchSkinPackages.remove(name); return null; }
            @Override protected void done() {
                try {
                    get();
                    FOptionPane.showMessageDialog(localizer.getMessage("lblDesktopMatchUiDeleted", name));
                } catch (Exception ex) { showFailure(ex); }
                finally { busy = false; refreshItems(); }
            }
        }.execute();
    }

    private static void showFailure(Exception error) {
        final Throwable cause = error.getCause() == null ? error : error.getCause();
        FOptionPane.showErrorDialog(cause.getMessage());
    }
}
