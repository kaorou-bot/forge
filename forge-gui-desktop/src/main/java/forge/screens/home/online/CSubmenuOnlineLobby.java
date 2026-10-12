package forge.screens.home.online;

import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import com.google.common.collect.ImmutableList;
import net.miginfocom.swing.MigLayout;

import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.NetConnectUtil;
import forge.gui.FNetOverlay;
import forge.gui.FThreads;
import forge.gui.framework.EDocID;
import forge.gui.framework.ICDoc;
import forge.gui.util.SOptionPane;
import forge.localinstance.properties.ForgeConstants;
import forge.menus.IMenuProvider;
import forge.menus.MenuUtil;
import forge.screens.home.CHomeUI;
import forge.screens.home.CLobby;
import forge.screens.home.VLobby;
import forge.screens.home.sanctioned.ConstructedGameMenu;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FSkin;
import forge.util.Localizer;
import forge.relay.RelayProtocol;

public enum CSubmenuOnlineLobby implements ICDoc, IMenuProvider {
    SINGLETON_INSTANCE;

    private CLobby lobby;

    void setLobby(final VLobby lobbyView) {
        lobby = lobbyView.getController();
        initialize();
    }

    void hostGame() {
        NetConnectUtil.ensurePlayerName();
        host();
    }

    void joinGame() {
        final String url = NetConnectUtil.getJoinServerUrl();
        if (url == null) { return; }

        FThreads.invokeInBackgroundThread(() -> join(url));
    }

    void hostRelayGame() {
        NetConnectUtil.ensurePlayerName();
        final Localizer localizer = Localizer.getInstance();
        final String roomName = SOptionPane.showInputDialog(
                localizer.getMessageorUseDefault("lblRelayRoomNamePrompt", "请输入大厅房间名称"),
                localizer.getMessageorUseDefault("lblCreateRelayRoom", "创建大厅房间"));
        if (roomName == null || roomName.isBlank()) {
            return;
        }
        final List<String> playerLimits = List.of("2", "3", "4", "5", "6", "7", "8");
        final String selectedPlayerLimit = SOptionPane.showInputDialog(
                localizer.getMessageorUseDefault("lblRelayPlayerLimitPrompt", "Maximum players (2-8)"),
                localizer.getMessageorUseDefault("lblCreateRelayRoom", "Create Lobby Room"),
                null, playerLimits.get(0), playerLimits, false);
        if (selectedPlayerLimit == null) {
            return;
        }
        final int maxPlayers = Integer.parseInt(selectedPlayerLimit);
        final List<String> formatLabels = forge.gamemodes.net.RelayRoomPresentation.FORMATS.stream()
                .map(forge.gamemodes.net.RelayRoomPresentation::formatLabel).toList();
        final String selectedFormat = SOptionPane.showInputDialog(
                localizer.getMessage("lblRoomTagNotice"),
                localizer.getMessage("lblRoomFormat"), null, formatLabels.get(0), formatLabels, false);
        if (selectedFormat == null) return;
        final String format = forge.gamemodes.net.RelayRoomPresentation.FORMATS.get(formatLabels.indexOf(selectedFormat));
        final String password = SOptionPane.showInputDialog(
                localizer.getMessageorUseDefault("lblRelayPasswordOptionalPrompt", "房间密码（可留空）"),
                localizer.getMessageorUseDefault("lblCreateRelayRoom", "创建大厅房间"));
        if (password == null) {
            return;
        }

        final Long operationMillis = forge.gamemodes.net.OperationClockSettings.prompt();
        if (operationMillis == null) return;
        FThreads.invokeInBackgroundThread(() -> hostRelay(roomName.trim(), format, password, maxPlayers, operationMillis));
    }

    void browseRelayRooms() {
        NetworkOperationDialog.run(Localizer.getInstance().getMessage("lblLoadingRelayRooms"),
                NetConnectUtil::listRelayRooms, this::showRelayRoomPicker,
                this::connectionFailed, () -> { });
    }

    void joinByRoomCode() {
        NetConnectUtil.ensurePlayerName();
        final String code = SOptionPane.showInputDialog(
                Localizer.getInstance().getMessage("lblNetworkRoomCodePrompt"),
                Localizer.getInstance().getMessage("lblNetworkJoinCode"));
        if (code == null || code.isBlank()) return;
        NetworkOperationDialog.run(Localizer.getInstance().getMessage("lblLoadingRelayRooms"),
                () -> NetConnectUtil.findRelayRoom(code), this::showRelayRoomPicker,
                this::connectionFailed, () -> { });
    }

    private void hostRelay(final String roomName, final String format, final String password, final int maxPlayers, final long operationMillis) {
        NetworkOperationDialog.run(Localizer.getInstance().getMessage("lblCreatingRelayRoom"),
                () -> NetConnectUtil.hostRelay(VSubmenuOnlineLobby.SINGLETON_INSTANCE,
                        FNetOverlay.SINGLETON_INSTANCE, roomName, format, password, maxPlayers, operationMillis),
                result -> handleRelayJoinResult(result, roomName),
                this::connectionFailed, this::connectionCancelled);
    }

    private void showRelayRoomPicker(final List<RelayProtocol.RoomSnapshot> rooms) {
        RelayRoomBrowser.show(rooms, this::promptRelayJoin, this::browseRelayRooms);
    }

    private void promptRelayJoin(final RelayProtocol.RoomSnapshot room) {
        final Localizer localizer = Localizer.getInstance();
        String password = "";
        if (room.passwordProtected()) {
            password = SOptionPane.showInputDialog(
                    localizer.getMessageorUseDefault("lblRelayPasswordPrompt", "请输入房间密码"),
                    localizer.getMessageorUseDefault("lblJoinRelayRoom", "加入大厅房间"));
            if (password == null) {
                return;
            }
        }
        final String finalPassword = password;
        FThreads.invokeInBackgroundThread(() -> joinRelay(room, finalPassword));
    }

    private void joinRelay(final RelayProtocol.RoomSnapshot room, final String password) {
        if (room.players() >= room.maxPlayers()) {
            FThreads.invokeInEdtLater(() -> FOptionPane.showMessageDialog(
                    Localizer.getInstance().getMessageorUseDefault("lblNetworkRoomFull",
                            "This room is full. Refresh the room list.")));
            return;
        }
        NetworkOperationDialog.run(Localizer.getInstance().getMessage("lblConnectingToServer"),
                () -> NetConnectUtil.joinRelay(room, password,
                        VSubmenuOnlineLobby.SINGLETON_INSTANCE, FNetOverlay.SINGLETON_INSTANCE),
                result -> handleRelayJoinResult(result, room.name()),
                this::connectionFailed, this::connectionCancelled);
    }

    private void connectionCancelled() {
        VSubmenuOnlineLobby.SINGLETON_INSTANCE.setClient(null);
        FNetOverlay.SINGLETON_INSTANCE.setGameClient(null);
        VSubmenuOnlineLobby.SINGLETON_INSTANCE.closeConn("");
    }

    private void connectionFailed(final Exception error) {
        connectionCancelled();
        FOptionPane.showErrorDialog(error.getMessage() == null ? error.toString() : error.getMessage(),
                Localizer.getInstance().getMessage("lblConnectionError"));
    }

    private void handleRelayJoinResult(final ChatMessage result, final String roomName) {
        final String message = result.getMessage();
        if (Objects.equals(message, ForgeConstants.CLOSE_CONN_COMMAND)
                || Objects.equals(message, ForgeConstants.INVALID_HOST_COMMAND)
                || (message != null && message.startsWith(ForgeConstants.CONN_ERROR_PREFIX))) {
            final String detail = message != null && message.startsWith(ForgeConstants.CONN_ERROR_PREFIX)
                    ? message.substring(ForgeConstants.CONN_ERROR_PREFIX.length())
                    : Localizer.getInstance().getMessageorUseDefault(
                            "lblRelayJoinFailedShort", "无法加入房间 %s", roomName);
            FOptionPane.showErrorDialog(detail, Localizer.getInstance().getMessage("lblConnectionError"));
            return;
        }
        FNetOverlay.SINGLETON_INSTANCE.show(result);
        if (CHomeUI.SINGLETON_INSTANCE.getCurrentDocID() == EDocID.HOME_NETWORK) {
            VSubmenuOnlineLobby.SINGLETON_INSTANCE.populate();
        }
    }


    private void host() {
        final Long operationMillis = forge.gamemodes.net.OperationClockSettings.prompt();
        if (operationMillis == null) return;
        NetworkOperationDialog.run(Localizer.getInstance().getMessage("lblStartingServer"),
                () -> NetConnectUtil.host(VSubmenuOnlineLobby.SINGLETON_INSTANCE, FNetOverlay.SINGLETON_INSTANCE, operationMillis),
                result -> {
                    handleRelayJoinResult(result, "");
                    showServerAddressesDialog();
                }, this::connectionFailed, this::connectionCancelled);
    }

    static void showServerAddressesDialog() {
        if (FThreads.isGuiThread()) {
            // Collecting the addresses queries the external IP over the network, so it can't run on the EDT.
            FThreads.invokeInBackgroundThread(CSubmenuOnlineLobby::showServerAddressesDialog);
            return;
        }
        final NetConnectUtil.ServerAddressList addresses = NetConnectUtil.collectHostedServerAddresses();
        SwingUtilities.invokeLater(() -> showServerAddressesDialog(addresses));
    }

    private static void showServerAddressesDialog(final NetConnectUtil.ServerAddressList addresses) {
        final Localizer localizer = Localizer.getInstance();

        if (addresses.starIndex >= 0) {
            copyToClipboard(addresses.urls.get(addresses.starIndex));
        }

        final JPanel panel = new JPanel(new MigLayout("insets 0, gap 4 6, wrap 3", "[pref]30[pref]30[pref]"));
        panel.setOpaque(false);

        panel.add(new FLabel.Builder()
                .text(localizer.getMessage("lblChooseAddressToCopy"))
                .fontSize(12).fontAlign(SwingConstants.LEFT).build(),
                "span 3, growx, gapbottom 10");

        panel.add(new FLabel.Builder().text(localizer.getMessage("lblInterface")).fontStyle(Font.BOLD).fontSize(12).fontAlign(SwingConstants.LEFT).build(), "growx");
        panel.add(new FLabel.Builder().text(localizer.getMessage("lblAddress")).fontStyle(Font.BOLD).fontSize(12).fontAlign(SwingConstants.LEFT).build(), "growx");
        panel.add(new FLabel.Builder().text("").build());

        final FOptionPane[] holder = new FOptionPane[1];
        for (int i = 0; i < addresses.urls.size(); i++) {
            final String url = addresses.urls.get(i);
            final String label = (i == addresses.starIndex) ? addresses.labels.get(i) + " \u2605" : addresses.labels.get(i);
            panel.add(new FLabel.Builder().text(label).fontSize(12).fontAlign(SwingConstants.LEFT).build(), "growx");
            panel.add(new FLabel.Builder().text(url).fontSize(12).fontAlign(SwingConstants.LEFT).build(), "growx");
            final FButton btnCopy = new FButton(localizer.getMessage("lblCopy"));
            btnCopy.setFont(FSkin.getFont(11));
            btnCopy.addActionListener(e -> {
                copyToClipboard(url);
                NetConnectUtil.rememberCopiedServerUrl(url);
                holder[0].setVisible(false);
            });
            panel.add(btnCopy, "w 70!, h 24!");
        }

        if (addresses.starIndex >= 0) {
            panel.add(new FLabel.Builder()
                    .text(localizer.getMessage("lblServerUrlCopiedToClipboard", addresses.urls.get(addresses.starIndex)))
                    .fontSize(11).fontStyle(Font.ITALIC).fontAlign(SwingConstants.LEFT).build(),
                    "span 3, growx, gaptop 10");
        }

        // Pass null as the prompt message so dialog width is driven by the panel's
        // actual content width rather than the much-wider localised instruction line.
        holder[0] = new FOptionPane(
                null,
                localizer.getMessage("lblServerURL"),
                FOptionPane.INFORMATION_ICON,
                panel,
                ImmutableList.of(localizer.getMessage("lblOK")),
                0);
        holder[0].setVisible(true);
        holder[0].dispose();
    }

    private static void copyToClipboard(final String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void join(final String url) {
        NetworkOperationDialog.run(Localizer.getInstance().getMessage("lblConnectingToServer"),
                () -> NetConnectUtil.join(url, VSubmenuOnlineLobby.SINGLETON_INSTANCE, FNetOverlay.SINGLETON_INSTANCE),
                result -> handleRelayJoinResult(result, url),
                this::connectionFailed, this::connectionCancelled);
    }

    @Override
    public void register() {
    }

    /* (non-Javadoc)
     * @see forge.gui.home.ICSubmenu#update()
     */
    @Override
    public void update() {
        MenuUtil.setMenuProvider(this);
        if (lobby != null) {
            lobby.update();
        }
    }

    /* (non-Javadoc)
     * @see forge.gui.home.ICSubmenu#initialize()
     */
    @Override
    public void initialize() {
        if (lobby != null) {
            lobby.initialize();
        }
    }

    /* (non-Javadoc)
     * @see forge.gui.menubar.IMenuProvider#getMenus()
     */
    @Override
    public List<JMenu> getMenus() {
        final List<JMenu> menus = new ArrayList<>();
        menus.add(ConstructedGameMenu.getMenu());
        return menus;
    }
}
