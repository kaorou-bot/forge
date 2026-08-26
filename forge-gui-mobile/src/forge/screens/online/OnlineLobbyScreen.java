package forge.screens.online;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.IOnlineChatInterface;
import forge.gamemodes.net.IOnlineLobby;
import forge.gamemodes.net.NetConnectUtil;
import forge.gamemodes.net.OfflineLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.server.FServerManager;
import forge.gui.FThreads;
import forge.gui.interfaces.ILobbyView;
import forge.gui.util.SOptionPane;
import forge.localinstance.properties.ForgeConstants;
import forge.screens.LoadingOverlay;
import forge.screens.constructed.LobbyScreen;
import forge.screens.match.MatchController;
import forge.screens.online.OnlineMenu.OnlineScreen;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FOverlay;
import forge.util.Utils;
import forge.relay.RelayProtocol;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class OnlineLobbyScreen extends LobbyScreen implements IOnlineLobby {

    private final FLabel lblTitle;
    private final FLabel lblWarning;
    private final FLabel lblGuideText;
    private final FLabel lblGuideLink;
    private final FButton btnHost;
    private final FButton btnJoin;

    public OnlineLobbyScreen() {
        super(null, OnlineMenu.getMenu(), new OfflineLobby());

        lblTitle = new FLabel.Builder()
                .text("- = *  H E R E   B E   E L D R A Z I  * = -")
                .font(FSkinFont.get(18)).align(Align.center).build();
        add(lblTitle);

        lblWarning = new FLabel.Builder()
                .text(Forge.getLocalizer().getMessage("lblOnlineWarning"))
                .font(FSkinFont.get(14)).align(Align.center).build();
        add(lblWarning);

        lblGuideText = new FLabel.Builder()
                .text(Forge.getLocalizer().getMessage("lblOnlineGuideText"))
                .font(FSkinFont.get(14)).align(Align.center).build();
        add(lblGuideText);

        lblGuideLink = new FLabel.Builder()
                .text(Forge.getLocalizer().getMessage("lblNetworkPlayGuide"))
                .font(FSkinFont.get(14)).align(Align.center)
                .textColor(FSkinColor.get(FSkinColor.Colors.CLR_ACTIVE))
                .command(e -> Gdx.net.openURI(ForgeConstants.NETWORK_PLAY_WIKI_URL)).build();
        add(lblGuideLink);

        btnHost = new FButton(Forge.getLocalizer().getMessageorUseDefault(
                "lblCreateRelayRoom", "Create Lobby Room"));
        btnHost.setCommand(e -> activateHost());
        add(btnHost);

        btnJoin = new FButton(Forge.getLocalizer().getMessageorUseDefault(
                "lblBrowseRelayRooms", "Browse Lobby"));
        btnJoin.setCommand(e -> activateJoin());
        add(btnJoin);
    }

    private static GameLobby gameLobby;

    public static GameLobby getGameLobby() {
        return gameLobby;
    }

    public static void clearGameLobby() {
        gameLobby = null;
    }

    public static void setGameLobby(GameLobby gameLobby) {
        OnlineLobbyScreen.gameLobby = gameLobby;
    }

    private static volatile FGameClient fGameClient;
    private static final AtomicBoolean intentionalDisconnect = new AtomicBoolean(false);

    public static FGameClient getfGameClient() {
        return fGameClient;
    }

    public static void closeClient() {
        final FGameClient client = fGameClient;
        fGameClient = null;
        if (client != null) {
            client.close();
        }
    }

    /**
     * Mark a user-requested disconnect before closing any network channel. The
     * channelInactive callback is asynchronous and must not navigate back a
     * second time after the UI has already left the lobby.
     */
    public static void beginIntentionalDisconnect() {
        intentionalDisconnect.set(true);
        clearGameLobby();
    }

    /**
     * Network shutdown may wait for Netty event loops and the relay transport,
     * so callers must run this method on a background thread.
     */
    public static void shutdownConnection() {
        final FServerManager server = FServerManager.getInstance();
        try {
            if (server != null && server.isHosting()) {
                server.unsetReady();
                server.stopServer();
            }
        } finally {
            closeClient();
        }
    }

    @Override
    public void closeConn(String msg) {
        clearGameLobby();
        fGameClient = null;
        if (intentionalDisconnect.get()) {
            return;
        }

        // A host can disappear while this client is still on the match screen
        // or while a win/lose/loading overlay is active. Those overlays keep
        // intercepting touches after a blind Forge.back(), making the lobby
        // menu appear dead. Reset the transient UI first, then put the client
        // on the known online-lobby screen.
        FOverlay.hideAll();
        if (Forge.getCurrentScreen() == MatchController.getView()) {
            Forge.back(true);
        }
        if (Forge.getCurrentScreen() != this) {
            Forge.openScreen(this, true);
        }
        revalidate();
        OnlineScreen.Lobby.update();

        if (msg != null && !msg.isEmpty()) {
            FOptionPane.showMessageDialog(msg,
                    Forge.getLocalizer().getMessage("lblError"),
                    FOptionPane.WARNING_ICON);
        }
    }

    @Override
    public ILobbyView setLobby(GameLobby lobby0) {
        initLobby(lobby0);
        return this;
    }

    @Override
    public void setClient(FGameClient client) {
        fGameClient = client;
        if (client != null) {
            intentionalDisconnect.set(false);
        }
    }

    @Override
    public void onActivate() {
        if (getGameLobby() == null) {
            revalidate();
        } else {
            super.onActivate();
        }
    }

    @Override
    protected void doLayoutAboveBtnStart(float startY, float width, float height) {
        if (getGameLobby() == null) {
            btnStart.setVisible(false);
            setLobbyControlsVisible(false);

            float padding = Utils.scale(10);
            float y = startY + height * 0.15f;

            float labelHeight = lblTitle.getAutoSizeBounds().height + padding;
            lblTitle.setBounds(padding, y, width - 2 * padding, labelHeight);
            lblTitle.setVisible(true);
            y += labelHeight + padding * 2;

            labelHeight = lblWarning.getAutoSizeBounds().height + padding;
            lblWarning.setBounds(padding, y, width - 2 * padding, labelHeight);
            lblWarning.setVisible(true);
            y += labelHeight + padding;

            labelHeight = lblGuideText.getAutoSizeBounds().height + padding;
            lblGuideText.setBounds(padding, y, width - 2 * padding, labelHeight);
            lblGuideText.setVisible(true);
            y += labelHeight;

            labelHeight = lblGuideLink.getAutoSizeBounds().height + padding;
            lblGuideLink.setBounds(padding, y, width - 2 * padding, labelHeight);
            lblGuideLink.setVisible(true);
            y += labelHeight + padding * 4;

            float buttonGap = padding * 2;
            float buttonWidth = width * 0.35f;
            float totalButtonWidth = buttonWidth * 2 + buttonGap;
            float buttonX = (width - totalButtonWidth) / 2;
            float buttonHeight = Utils.AVG_FINGER_HEIGHT;
            btnHost.setBounds(buttonX, y, buttonWidth, buttonHeight);
            btnHost.setVisible(true);
            btnJoin.setBounds(buttonX + buttonWidth + buttonGap, y, buttonWidth, buttonHeight);
            btnJoin.setVisible(true);
        } else {
            lblTitle.setVisible(false);
            lblWarning.setVisible(false);
            lblGuideText.setVisible(false);
            lblGuideLink.setVisible(false);
            btnHost.setVisible(false);
            btnJoin.setVisible(false);
            setLobbyControlsVisible(true);
            btnStart.setVisible(true);

            super.doLayoutAboveBtnStart(startY, width, height);
        }
    }

    private void activateHost() {
        NetConnectUtil.ensurePlayerName();
        FThreads.invokeInBackgroundThread(() -> {
            final String roomName = SOptionPane.showInputDialog(
                    Forge.getLocalizer().getMessageorUseDefault(
                            "lblRelayRoomNamePrompt", "Enter a room name"),
                    Forge.getLocalizer().getMessageorUseDefault(
                            "lblCreateRelayRoom", "Create Lobby Room"));
            if (roomName == null || roomName.isBlank()) {
                return;
            }
            final List<String> playerLimits = List.of("2", "3", "4", "5", "6", "7", "8");
            final String selectedPlayerLimit = SOptionPane.showInputDialog(
                    Forge.getLocalizer().getMessageorUseDefault(
                            "lblRelayPlayerLimitPrompt", "Maximum players (2-8)"),
                    Forge.getLocalizer().getMessageorUseDefault(
                            "lblCreateRelayRoom", "Create Lobby Room"),
                    null, playerLimits.get(0), playerLimits, false);
            if (selectedPlayerLimit == null) {
                return;
            }
            final int maxPlayers = Integer.parseInt(selectedPlayerLimit);
            final String password = SOptionPane.showInputDialog(
                    Forge.getLocalizer().getMessageorUseDefault(
                            "lblRelayPasswordOptionalPrompt", "Password (optional)"),
                    Forge.getLocalizer().getMessageorUseDefault(
                            "lblCreateRelayRoom", "Create Lobby Room"));
            if (password == null) {
                return;
            }
            FThreads.invokeInEdtLater(() ->
                    startRelayHost(roomName.trim(), password, maxPlayers));
        });
    }

    private void activateJoin() {
        final LoadingOverlay loader = new LoadingOverlay(
                Forge.getLocalizer().getMessageorUseDefault(
                        "lblLoadingRelayRooms", "Loading lobby rooms..."), true);
        loader.show();
        FThreads.invokeInBackgroundThread(() -> {
            try {
                final List<RelayProtocol.RoomSnapshot> rooms = NetConnectUtil.listRelayRooms();
                FThreads.invokeInEdtAndWait(loader::hide);
                showRelayRoomPicker(rooms);
            } catch (IOException e) {
                FThreads.invokeInEdtLater(() -> {
                    loader.hide();
                    showRelayError("lblRelayUnavailable", "Unable to reach the lobby server: %s", e);
                });
            }
        });
    }

    private void startRelayHost(final String roomName, final String password,
                                final int maxPlayers) {
        setGameLobby(getLobby());
        revalidate();
        final IOnlineChatInterface chatInterface =
                (IOnlineChatInterface) OnlineScreen.Chat.getScreen();
        // TLS registration runs in the background; NetConnectUtil marshals only
        // libGDX lobby/view initialization back to the UI thread.
        LoadingOverlay.runBackgroundTask(
                Forge.getLocalizer().getMessageorUseDefault(
                        "lblCreatingRelayRoom", "Creating lobby room..."), () -> {
                    try {
                        final ChatMessage result = NetConnectUtil.hostRelay(
                                OnlineLobbyScreen.this, chatInterface, roomName,
                                "Constructed", password, maxPlayers);
                        FThreads.invokeInEdtLater(() -> {
                            chatInterface.addMessage(result);
                            OnlineScreen.Lobby.update();
                        });
                    } catch (IOException e) {
                        FThreads.invokeInEdtLater(() -> {
                            clearGameLobby();
                            revalidate();
                            showRelayError("lblRelayRoomCreateFailed",
                                    "Unable to create lobby room: %s", e);
                        });
                    }
                });
    }

    private void showRelayRoomPicker(final List<RelayProtocol.RoomSnapshot> rooms) {
        if (rooms.isEmpty()) {
            SOptionPane.showMessageDialog(
                    Forge.getLocalizer().getMessageorUseDefault(
                            "lblNoRelayRooms", "There are no compatible rooms right now."));
            return;
        }
        final List<String> labels = rooms.stream()
                .map(OnlineLobbyScreen::relayRoomLabel).toList();
        final String selected = SOptionPane.showInputDialog(
                Forge.getLocalizer().getMessageorUseDefault(
                        "lblSelectRelayRoom", "Select a room to join"),
                Forge.getLocalizer().getMessageorUseDefault(
                        "lblBrowseRelayRooms", "Browse Lobby"),
                null, labels.get(0), labels, false);
        if (selected == null) {
            return;
        }
        final int index = labels.indexOf(selected);
        if (index < 0) {
            return;
        }
        final RelayProtocol.RoomSnapshot room = rooms.get(index);
        String password = "";
        if (room.passwordProtected()) {
            password = SOptionPane.showInputDialog(
                    Forge.getLocalizer().getMessageorUseDefault(
                            "lblRelayPasswordPrompt", "Enter the room password"),
                    Forge.getLocalizer().getMessageorUseDefault(
                            "lblJoinRelayRoom", "Join Lobby Room"));
            if (password == null) {
                return;
            }
        }
        final String finalPassword = password;
        FThreads.invokeInEdtLater(() -> startRelayJoin(room, finalPassword));
    }

    private void startRelayJoin(final RelayProtocol.RoomSnapshot room, final String password) {
        setGameLobby(getLobby());
        revalidate();
        final IOnlineChatInterface chatInterface =
                (IOnlineChatInterface) OnlineScreen.Chat.getScreen();
        // Tunnel establishment and the Netty loopback connect are blocking; only
        // lobby/view changes are dispatched to the libGDX UI thread.
        LoadingOverlay.runBackgroundTask(
                Forge.getLocalizer().getMessage("lblConnectingToServer"), () -> {
                    try {
                        final ChatMessage result = NetConnectUtil.joinRelay(
                                room, password, OnlineLobbyScreen.this, chatInterface);
                        final String message = result.getMessage();
                        if (Objects.equals(message, ForgeConstants.CLOSE_CONN_COMMAND)
                                || Objects.equals(message, ForgeConstants.INVALID_HOST_COMMAND)
                                || (message != null && message.startsWith(
                                        ForgeConstants.CONN_ERROR_PREFIX))) {
                            final String detail = message != null && message.startsWith(
                                    ForgeConstants.CONN_ERROR_PREFIX)
                                    ? message.substring(ForgeConstants.CONN_ERROR_PREFIX.length())
                                    : Forge.getLocalizer().getMessageorUseDefault(
                                            "lblRelayJoinFailedShort",
                                            "Unable to join room %s", room.name());
                            FThreads.invokeInEdtLater(() -> resetAfterRelayFailure(detail));
                            return;
                        }
                        FThreads.invokeInEdtLater(() -> {
                            chatInterface.addMessage(result);
                            OnlineScreen.Lobby.update();
                        });
                    } catch (IOException e) {
                        final String detail = Forge.getLocalizer().getMessageorUseDefault(
                                "lblRelayJoinFailed", "Unable to join lobby room: %s",
                                e.getMessage());
                        FThreads.invokeInEdtLater(() -> resetAfterRelayFailure(detail));
                    }
                });
    }

    private void resetAfterRelayFailure(final String detail) {
        clearGameLobby();
        revalidate();
        FThreads.invokeInBackgroundThread(() -> SOptionPane.showErrorDialog(detail,
                Forge.getLocalizer().getMessage("lblConnectionError")));
    }

    private void showRelayError(final String key, final String fallback, final IOException error) {
        FThreads.invokeInBackgroundThread(() -> SOptionPane.showErrorDialog(
                Forge.getLocalizer().getMessageorUseDefault(key, fallback, error.getMessage()),
                Forge.getLocalizer().getMessage("lblConnectionError")));
    }

    private static String relayRoomLabel(final RelayProtocol.RoomSnapshot room) {
        final String lock = room.passwordProtected() ? "[Password] " : "";
        final String format = room.format().isBlank() ? "" : " · " + room.format();
        return String.format("%s%s · %s · %d/%d%s", lock, room.name(), room.ownerName(),
                room.players(), room.maxPlayers(), format);
    }
}
