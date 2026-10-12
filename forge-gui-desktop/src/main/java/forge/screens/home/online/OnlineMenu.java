package forge.screens.home.online;

import java.awt.Desktop;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JSeparator;

import forge.gamemodes.net.NetworkLogConfig;
import forge.gamemodes.net.NetworkConnectionSettings;
import forge.model.FModel;
import forge.gamemodes.net.server.FServerManager;
import forge.gui.FDraftOverlay;
import forge.gui.FNetOverlay;
import forge.localinstance.properties.ForgeConstants;
import forge.util.Localizer;

/**
 * Returns a JMenu containing options for online play.
 */
public final class OnlineMenu {

    public static JMenu getMenu() {
        JMenu menu = new JMenu(Localizer.getInstance().getMessage("lblOnline"));
        menu.setMnemonic(KeyEvent.VK_O);
        populateMenu(menu);
        menu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                // The parent Forge menu is cached across room creation/disconnection.
                menu.removeAll();
                populateMenu(menu);
            }

            @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });
        return menu;
    }

    private static void populateMenu(final JMenu menu) {
        menu.add(getMenuItem_HostGame());
        menu.add(getMenuItem_JoinGame());
        final JMenuItem joinCode = new JMenuItem(Localizer.getInstance().getMessage("lblNetworkJoinCode"));
        joinCode.setEnabled(!FServerManager.getInstance().isHosting()
                && VSubmenuOnlineLobby.SINGLETON_INSTANCE.getClient() == null);
        joinCode.addActionListener(e -> CSubmenuOnlineLobby.SINGLETON_INSTANCE.joinByRoomCode());
        menu.add(joinCode);
        final String roomCode = forge.gamemodes.net.NetConnectUtil.getHostedRoomCode();
        if (!roomCode.isEmpty()) {
            final JMenuItem copyCode = new JMenuItem(Localizer.getInstance().getMessage("lblNetworkCopyRoomCode"));
            copyCode.addActionListener(e -> forge.gui.GuiBase.getInterface().copyToClipboard(roomCode));
            menu.add(copyCode);
        }
        final JMenuItem diagnostics = new JMenuItem(Localizer.getInstance().getMessage("lblNetworkDiagnostics"));
        diagnostics.setEnabled(FServerManager.getInstance().isHosting()
                || VSubmenuOnlineLobby.SINGLETON_INSTANCE.getClient() != null);
        diagnostics.addActionListener(e -> forge.gamemodes.net.NetworkDiagnostics.show(
                VSubmenuOnlineLobby.SINGLETON_INSTANCE.getClient(), null));
        menu.add(diagnostics);
        menu.add(new JSeparator());
        menu.add(getMenuItem_OpenNetworkLog());
        menu.add(getMenuItem_OpenNetworkLogs());
        menu.add(new JSeparator());
        menu.add(chatItem);
        menu.add(draftItem);
    }

    public static final JCheckBoxMenuItem chatItem;
    public static final JCheckBoxMenuItem draftItem;

    static {
        chatItem = new JCheckBoxMenuItem(Localizer.getInstance().getMessage("lblShowChatPanel"));
        chatItem.addActionListener(e -> {
            if (((JMenuItem)e.getSource()).isSelected()) {
                FNetOverlay.SINGLETON_INSTANCE.show();
            }
            else {
                FNetOverlay.SINGLETON_INSTANCE.hide();
            }
        });
        draftItem = new JCheckBoxMenuItem(Localizer.getInstance().getMessage("lblShowDraftPanel"));
        draftItem.addActionListener(e -> {
            if (((JMenuItem)e.getSource()).isSelected()) {
                FDraftOverlay.SINGLETON_INSTANCE.show();
            }
            else {
                FDraftOverlay.SINGLETON_INSTANCE.hide();
            }
        });
    }

    private static JMenuItem getMenuItem_HostGame() {
        JMenuItem menuItem = new JMenuItem(Localizer.getInstance().getMessage("lblHostGame"));
        menuItem.setEnabled(!FServerManager.getInstance().isHosting() && VSubmenuOnlineLobby.SINGLETON_INSTANCE.getClient() == null);
        menuItem.addActionListener(e -> {
            if (NetworkConnectionSettings.mode(FModel.getNetPreferences()) == NetworkConnectionSettings.Mode.SERVER)
                CSubmenuOnlineLobby.SINGLETON_INSTANCE.hostRelayGame();
            else CSubmenuOnlineLobby.SINGLETON_INSTANCE.hostGame();
        });
        return menuItem;
    }

    private static JMenuItem getMenuItem_JoinGame() {
        JMenuItem menuItem = new JMenuItem(Localizer.getInstance().getMessage("lblJoinGame"));
        menuItem.setEnabled(!FServerManager.getInstance().isHosting() && VSubmenuOnlineLobby.SINGLETON_INSTANCE.getClient() == null);
        menuItem.addActionListener(e -> {
            if (NetworkConnectionSettings.mode(FModel.getNetPreferences()) == NetworkConnectionSettings.Mode.SERVER)
                CSubmenuOnlineLobby.SINGLETON_INSTANCE.browseRelayRooms();
            else CSubmenuOnlineLobby.SINGLETON_INSTANCE.joinGame();
        });
        return menuItem;
    }

    private static JMenuItem getMenuItem_OpenNetworkLog() {
        JMenuItem menuItem = new JMenuItem(Localizer.getInstance().getMessage("lblOpenNetworkLog"));
        menuItem.addActionListener(e -> openInOS(NetworkLogConfig.getCurrentLogFile()));
        return menuItem;
    }

    private static JMenuItem getMenuItem_OpenNetworkLogs() {
        JMenuItem menuItem = new JMenuItem(Localizer.getInstance().getMessage("lblOpenNetworkLogs"));
        menuItem.addActionListener(e -> {
            File dir = new File(ForgeConstants.NETWORK_LOGS_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            openInOS(dir);
        });
        return menuItem;
    }

    private static void openInOS(File file) {
        if (file == null) {
            return;
        }
        try {
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler " + file.getCanonicalPath());
            } else {
                Desktop.getDesktop().open(file);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
