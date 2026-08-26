package forge.gamemodes.net;

import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.gui.GuiBase;
import forge.gui.FThreads;
import forge.gui.interfaces.IGuiGame;
import forge.gui.interfaces.ILobbyView;
import forge.gui.util.SOptionPane;
import forge.interfaces.ILobbyListener;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.Localizer;
import forge.util.URLValidator;
import forge.relay.RelayProtocol;
import forge.relay.client.RelayEndpoint;
import forge.relay.client.RelayGuestProxy;
import forge.relay.client.RelayHostSession;
import forge.relay.client.RelayLobbyClient;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.List;

public class NetConnectUtil {
    private static final String DEFAULT_RELAY_HOST = "play.mtg-forge-kaorou.vip";
    private static final int DEFAULT_RELAY_PORT = 443;
    private static final String RELAY_COMPATIBILITY_VERSION = "forge-cn-net-1";

    private NetConnectUtil() { }

    /**
     * Prompt for the server address to join. Returns null if cancelled, or the address string.
     */
    public static String getJoinServerUrl() {
        final String url = SOptionPane.showInputDialog(
                Localizer.getInstance().getMessage("lblEnterServerAddress"),
                Localizer.getInstance().getMessage("lblJoinGame"));
        if (url == null || url.isEmpty()) { return null; }

        ensurePlayerName();
        return url;
    }

    /**
     * Ensure the player name is set before connecting.
     */
    public static void ensurePlayerName() {
        if (StringUtils.isBlank(FModel.getPreferences().getPref(FPref.PLAYER_NAME))) {
            GamePlayerUtil.setPlayerName();
        }
    }

    public static ChatMessage host(final IOnlineLobby onlineLobby, final IOnlineChatInterface chatInterface) {
        return host(onlineLobby, chatInterface, false, GameLobby.MIN_PLAYERS);
    }

    private static ChatMessage host(final IOnlineLobby onlineLobby,
                                    final IOnlineChatInterface chatInterface,
                                    final boolean relayOnly, final int playerLimit) {
        final int port = FModel.getNetPreferences().getPrefInt(ForgeNetPreferences.FNetPref.NET_PORT);
        final FServerManager server = FServerManager.getInstance();
        final ServerGameLobby lobby = new ServerGameLobby(playerLimit, relayOnly);
        final ILobbyView view = onlineLobby.setLobby(lobby);

        NetworkLogConfig.activateNetworkLogging();
        if (relayOnly) {
            server.startRelayServer(port);
        } else {
            server.startServer(port);
        }
        server.setLobby(lobby);

        lobby.setListener(new IUpdateable() {
            @Override
            public void update(final boolean fullUpdate) {
                view.update(fullUpdate);
                server.updateLobbyState();
            }
            @Override
            public void update(final int slot, final LobbySlotType type) {}
        });
        // updateSlot already routes through the IUpdateable listener above, which calls
        // updateLobbyState; calling it again here would broadcast a duplicate LobbyUpdateEvent.
        view.setPlayerChangeListener(server::updateSlot);

        server.setLobbyListener(new ILobbyListener() {
            @Override
            public void update(final GameLobbyData state, final int slot) {
                // NO-OP, lobby connected directly
            }
            @Override
            public void message(final String source, final String message, final ChatMessage.MessageType type) {
                FThreads.invokeInEdtLater(() ->
                        chatInterface.addMessage(new ChatMessage(source, message, type)));
            }
            @Override
            public void close() {
                // NO-OP, server can't receive close message
            }
            @Override
            public ClientGameLobby getLobby() {
                return null;
            }
        });
        server.setDraftHandler(view.getDraftHandler());
        chatInterface.setGameClient(new IRemote() {
            @Override
            public void send(final NetEvent event) {
                if (event instanceof MessageEvent message) {
                    if (server.handleCommand(message.getMessage())) {
                        return;
                    }
                    server.broadcast(event);
                }
            }
            @Override
            public Object sendAndWait(final IdentifiableNetEvent event) {
                send(event);
                return null;
            }
        });

        view.update(true);

        server.broadcast(new MessageEvent(server.formatAfkTimeoutMessage()));

        return new ChatMessage(null, Localizer.getInstance().getMessage("lblHostingPortOnN", String.valueOf(port)));
    }

    public static RelayEndpoint getRelayEndpoint() {
        final String host = System.getProperty("forge.relay.host", DEFAULT_RELAY_HOST);
        final int port = Integer.getInteger("forge.relay.port", DEFAULT_RELAY_PORT);
        final boolean tlsDefault = !("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host));
        final boolean tls = Boolean.parseBoolean(System.getProperty(
                "forge.relay.tls", Boolean.toString(tlsDefault)));
        return new RelayEndpoint(host, port, tls);
    }

    public static String getRelayCompatibilityVersion() {
        return RELAY_COMPATIBILITY_VERSION;
    }

    public static List<RelayProtocol.RoomSnapshot> listRelayRooms() throws IOException {
        return RelayLobbyClient.listRooms(getRelayEndpoint(), getRelayCompatibilityVersion());
    }

    public static ChatMessage hostRelay(final IOnlineLobby onlineLobby,
                                        final IOnlineChatInterface chatInterface,
                                        final String roomName, final String format,
                                        final String password, final int maxPlayers) throws IOException {
        ensurePlayerName();
        final FServerManager server = FServerManager.getInstance();
        // Relay setup is invoked from a worker thread because registration and TLS can block.
        // Only the lobby/view initialization belongs on the UI thread.
        try {
            FThreads.invokeInEdtAndWait(() -> host(
                    onlineLobby, chatInterface, true, maxPlayers));
        } catch (RuntimeException e) {
            if (server.isHosting()) {
                server.stopServer();
            }
            throw new IOException("Unable to initialize the local lobby: "
                    + rootCauseMessage(e), e);
        }
        final int localPort = FModel.getNetPreferences().getPrefInt(ForgeNetPreferences.FNetPref.NET_PORT);
        try {
            final RelayHostSession relay = RelayHostSession.open(
                    getRelayEndpoint(), localPort, getRelayCompatibilityVersion(), roomName,
                    FModel.getPreferences().getPref(FPref.PLAYER_NAME), format, password,
                    maxPlayers, error -> FThreads.invokeInEdtLater(() ->
                            onlineLobby.closeConn(Localizer.getInstance().getMessageorUseDefault(
                                    "lblRelayConnectionLost", "与中央大厅的连接已断开：%s", error.getMessage()))));
            server.setExternalTransport(relay);
            return new ChatMessage(null, Localizer.getInstance().getMessageorUseDefault(
                    "lblRelayRoomCreated", "已创建大厅房间：%s（房间号 %s）", roomName, relay.roomId()));
        } catch (IOException e) {
            if (server.isHosting()) {
                server.stopServer();
            }
            throw e;
        }
    }

    public static void copyHostedServerUrl() {
        final Localizer localizer = Localizer.getInstance();
        String internalAddress = FServerManager.getLocalAddress();
        String externalAddress = FServerManager.getExternalAddress();
        String internalUrl = internalAddress + ":" + FModel.getNetPreferences().getPrefInt(ForgeNetPreferences.FNetPref.NET_PORT);
        String externalUrl = null;
        if (externalAddress != null) {
            externalUrl = externalAddress + ":" + FModel.getNetPreferences().getPrefInt(ForgeNetPreferences.FNetPref.NET_PORT);
            GuiBase.getInterface().copyToClipboard(externalUrl);
        } else {
            GuiBase.getInterface().copyToClipboard(internalUrl);
        }

        String message;
        String title = localizer.getMessage("lblServerURL");
        List<String> options;
        int closeIndex;
        int localCopyIndex;

        if (externalUrl != null) {
            message = localizer.getMessage("lblShareURLToMakePlayerJoinServer", externalUrl, internalUrl);
            options = List.of(
                    localizer.getMessage("lblCopyExternalURL"),
                    localizer.getMessage("lblCopyLocalURL"),
                    localizer.getMessage("lblClose"));
            closeIndex = 2;
            localCopyIndex = 1;
        } else {
            message = localizer.getMessage("lblForgeUnableDetermineYourExternalIP", internalUrl);
            options = List.of(
                    localizer.getMessage("lblCopyLocalURL"),
                    localizer.getMessage("lblClose"));
            closeIndex = 1;
            localCopyIndex = 0;
        }

        int result = SOptionPane.showOptionDialog(message, title, SOptionPane.INFORMATION_ICON, options, closeIndex);
        if (externalUrl != null && result == 0) {
            GuiBase.getInterface().copyToClipboard(externalUrl);
        } else if (result == localCopyIndex) {
            GuiBase.getInterface().copyToClipboard(internalUrl);
        }
    }

    public static ChatMessage join(final String url, final IOnlineLobby onlineLobby, final IOnlineChatInterface chatInterface) {
        return join(url, onlineLobby, chatInterface, null);
    }

    private static ChatMessage join(final String url, final IOnlineLobby onlineLobby,
                                    final IOnlineChatInterface chatInterface,
                                    final AutoCloseable externalTransport) {
        final IGuiGame gui = GuiBase.getInterface().getNewGuiGame();
        String hostname;
        int port;

        URLValidator.HostPort hostPort = URLValidator.parseURL(url);
        if (hostPort == null) {
            return new ChatMessage(null, ForgeConstants.INVALID_HOST_COMMAND);
        }

        hostname = hostPort.host();
        port = hostPort.port();
        if (port == -1) port = Integer.valueOf(ForgeNetPreferences.FNetPref.NET_PORT.getDefault());

        final FGameClient client = prepareJoin(hostname, port, gui, onlineLobby,
                chatInterface, externalTransport, url);

        return connectPreparedClient(hostname, port, client, onlineLobby, chatInterface);
    }

    private static FGameClient prepareJoin(final String hostname, final int port,
                                           final IGuiGame gui,
                                           final IOnlineLobby onlineLobby,
                                           final IOnlineChatInterface chatInterface,
                                           final AutoCloseable externalTransport,
                                           final String displayUrl) {
        final FGameClient client = new FGameClient(
                FModel.getPreferences().getPref(FPref.PLAYER_NAME), gui, hostname, port);
        client.setExternalTransport(externalTransport);
        onlineLobby.setClient(client);
        chatInterface.setGameClient(client);
        final ClientGameLobby lobby = new ClientGameLobby();
        final ILobbyView view =  onlineLobby.setLobby(lobby);
        lobby.setListener(view);
        if (gui instanceof AbstractGuiGame agg) {
            agg.setClientLobby(lobby);
        }
        client.addLobbyListener(new ILobbyListener() {
            @Override
            public void message(final String source, final String message, final ChatMessage.MessageType type) {
                FThreads.invokeInEdtLater(() ->
                        chatInterface.addMessage(new ChatMessage(source, message, type)));
            }
            @Override
            public void update(final GameLobbyData state, final int slot) {
                FThreads.invokeInEdtLater(() -> {
                    lobby.setLocalPlayer(slot);
                    lobby.setData(state);
                });
            }
            @Override
            public void close() {
                FThreads.invokeInEdtLater(() -> {
                    onlineLobby.setClient(null);
                    chatInterface.setGameClient(null);
                    onlineLobby.closeConn(Localizer.getInstance().getMessage(
                            "lblYourConnectionToHostWasInterrupted", displayUrl));
                });
            }
            @Override
            public ClientGameLobby getLobby() {
                return lobby;
            }
        });
        client.setDraftHandler(view.getDraftHandler());
        view.setPlayerChangeListener((index, event) -> client.send(event));

        return client;
    }

    private static ChatMessage connectPreparedClient(final String hostname, final int port,
                                                      final FGameClient client,
                                                      final IOnlineLobby onlineLobby,
                                                      final IOnlineChatInterface chatInterface) {
        NetworkLogConfig.activateNetworkLogging();
        try {
            client.connect();
        }
        catch (Exception ex) {
            // Return error with details for GUI display
            String errorDetail = getConnectionErrorMessage(ex, hostname, port);
            client.close();
            FThreads.invokeInEdtAndWait(() -> {
                onlineLobby.setClient(null);
                chatInterface.setGameClient(null);
            });
            return new ChatMessage(null, ForgeConstants.CONN_ERROR_PREFIX + errorDetail);
        }

        return new ChatMessage(null, Localizer.getInstance().getMessage("lblConnectedIPPort", hostname, String.valueOf(port)));
    }

    public static ChatMessage joinRelay(final RelayProtocol.RoomSnapshot room, final String password,
                                        final IOnlineLobby onlineLobby,
                                        final IOnlineChatInterface chatInterface) throws IOException {
        ensurePlayerName();
        final RelayGuestProxy proxy = RelayGuestProxy.open(
                getRelayEndpoint(), getRelayCompatibilityVersion(), room.roomId(), password);
        boolean success = false;
        try {
            final String localUrl = "127.0.0.1:" + proxy.localPort();
            final FGameClient[] client = new FGameClient[1];
            // Build and install libGDX lobby widgets on the UI thread, then perform the
            // blocking loopback Netty connect on this worker thread.
            try {
                FThreads.invokeInEdtAndWait(() -> {
                    final IGuiGame gui = GuiBase.getInterface().getNewGuiGame();
                    client[0] = prepareJoin("127.0.0.1", proxy.localPort(), gui,
                            onlineLobby, chatInterface, proxy, localUrl);
                });
            } catch (RuntimeException e) {
                throw new IOException("Unable to initialize the local lobby: "
                        + rootCauseMessage(e), e);
            }
            final ChatMessage result = connectPreparedClient(
                    "127.0.0.1", proxy.localPort(), client[0],
                    onlineLobby, chatInterface);
            final String message = result.getMessage();
            success = !ForgeConstants.CLOSE_CONN_COMMAND.equals(message)
                    && !ForgeConstants.INVALID_HOST_COMMAND.equals(message)
                    && (message == null || !message.startsWith(ForgeConstants.CONN_ERROR_PREFIX));
            return result;
        } finally {
            if (!success) {
                proxy.close();
            }
        }
    }

    /**
     * Generate a user-friendly error message for connection failures.
     */
    private static String getConnectionErrorMessage(Exception ex, String hostname, int port) {
        Localizer localizer = Localizer.getInstance();
        StringBuilder sb = new StringBuilder();

        // Get the root cause for better error messages
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        String causeName = cause.getClass().getSimpleName();

        sb.append(localizer.getMessage("lblConnectionFailedTo", hostname, port));
        sb.append("\n\n");

        // Provide specific messages for common error types
        if (causeName.contains("ConnectException") || causeName.contains("ConnectionRefused")) {
            sb.append(localizer.getMessage("lblConnectionRefused"));
        } else if (causeName.contains("UnknownHost")) {
            sb.append(localizer.getMessage("lblUnknownHost"));
        } else if (causeName.contains("Timeout") || causeName.contains("TimedOut")) {
            sb.append(localizer.getMessage("lblConnectionTimeout"));
        } else if (causeName.contains("NoRouteToHost")) {
            sb.append(localizer.getMessage("lblNoRouteToHost"));
        } else {
            // Generic error with the exception message
            String msg = cause.getMessage();
            if (msg != null && !msg.isEmpty()) {
                sb.append(msg);
            } else {
                sb.append(causeName);
            }
        }

        return sb.toString();
    }

    private static String rootCauseMessage(final Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message;
    }

}
