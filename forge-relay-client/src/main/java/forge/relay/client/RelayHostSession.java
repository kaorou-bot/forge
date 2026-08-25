package forge.relay.client;

import forge.relay.RelayProtocol;
import forge.relay.RelayWire;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Maintains a room-owner control connection and opens reverse tunnels on demand. */
public final class RelayHostSession implements AutoCloseable {
    private static final int HEARTBEAT_SECONDS = 30;
    private static final int HEARTBEAT_TIMEOUT_MILLIS = HEARTBEAT_SECONDS * 1_000;

    private final RelayEndpoint endpoint;
    private final int localForgePort;
    private final String roomId;
    private final String ownerToken;
    private final Socket controlSocket;
    private final ExecutorService tunnelExecutor;
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final Set<SocketBridge> bridges = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<Throwable> failureListener;

    private RelayHostSession(RelayEndpoint endpoint, int localForgePort,
                             String roomId, String ownerToken, Socket controlSocket,
                             Consumer<Throwable> failureListener) {
        this.endpoint = endpoint;
        this.localForgePort = localForgePort;
        this.roomId = roomId;
        this.ownerToken = ownerToken;
        this.controlSocket = controlSocket;
        this.failureListener = failureListener == null ? ignored -> { } : failureListener;
        this.tunnelExecutor = Executors.newCachedThreadPool(
                RelayGuestProxy.daemonFactory("forge-relay-host-tunnel"));
        tunnelExecutor.execute(this::readControlLoop);
    }

    public static RelayHostSession open(RelayEndpoint endpoint, int localForgePort,
                                        String compatibilityVersion, String roomName,
                                        String ownerName, String format, String password,
                                        int maxPlayers, Consumer<Throwable> failureListener)
            throws IOException {
        Socket control = RelayLobbyClient.connect(endpoint);
        boolean success = false;
        try {
            RelayWire.writeFrame(control.getOutputStream(), RelayProtocol.REGISTER_ROOM, out -> {
                out.writeInt(RelayProtocol.VERSION);
                RelayWire.writeString(out, compatibilityVersion);
                RelayWire.writeString(out, roomName);
                RelayWire.writeString(out, ownerName);
                RelayWire.writeString(out, format);
                RelayWire.writeString(out, password);
                out.writeInt(maxPlayers);
            });
            RelayWire.Frame registered = RelayWire.readFrame(control.getInputStream());
            RelayWire.requireType(registered, RelayProtocol.ROOM_REGISTERED);
            String roomId = RelayWire.readString(registered.body());
            String ownerToken = RelayWire.readString(registered.body());
            // On some Android TLS providers, a blocking read can prevent a second
            // thread from writing to the same SSLSocket. Drive the heartbeat from
            // the control-reader timeout so all TLS I/O stays on one thread.
            control.setSoTimeout(HEARTBEAT_TIMEOUT_MILLIS);
            RelayHostSession session = new RelayHostSession(endpoint, localForgePort,
                    roomId, ownerToken, control, failureListener);
            success = true;
            return session;
        } finally {
            if (!success) {
                control.close();
            }
        }
    }

    public String roomId() {
        return roomId;
    }

    private void readControlLoop() {
        try {
            while (!closed.get()) {
                final RelayWire.Frame frame;
                try {
                    frame = RelayWire.readFrame(controlSocket.getInputStream());
                } catch (SocketTimeoutException e) {
                    sendHeartbeat();
                    continue;
                }
                if (frame.type() == RelayProtocol.OPEN_TUNNEL) {
                    String requestedRoom = RelayWire.readString(frame.body());
                    String tunnelId = RelayWire.readString(frame.body());
                    if (!roomId.equals(requestedRoom)) {
                        throw new IOException("Relay requested a tunnel for the wrong room");
                    }
                    tunnelExecutor.execute(() -> openTunnel(tunnelId));
                } else if (frame.type() == RelayProtocol.HEARTBEAT) {
                    // Heartbeat acknowledgement.
                } else if (frame.type() == RelayProtocol.ERROR) {
                    throw new RelayWire.RelayException(
                            RelayWire.readString(frame.body()), RelayWire.readString(frame.body()));
                } else {
                    throw new IOException("Unexpected owner control message " + frame.type());
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                failureListener.accept(e);
                close();
            }
        }
    }

    private void openTunnel(String tunnelId) {
        Socket local = new Socket();
        Socket relay = null;
        try {
            local.connect(new InetSocketAddress("127.0.0.1", localForgePort),
                    RelayLobbyClient.CONNECT_TIMEOUT_MILLIS);
            local.setTcpNoDelay(true);
            openSockets.add(local);

            relay = RelayLobbyClient.connect(endpoint);
            openSockets.add(relay);
            RelayWire.writeFrame(relay.getOutputStream(), RelayProtocol.HOST_TUNNEL, out -> {
                out.writeInt(RelayProtocol.VERSION);
                RelayWire.writeString(out, roomId);
                RelayWire.writeString(out, ownerToken);
                RelayWire.writeString(out, tunnelId);
            });
            RelayWire.Frame ready = RelayWire.readFrame(relay.getInputStream());
            RelayWire.requireType(ready, RelayProtocol.TUNNEL_READY);
            relay.setSoTimeout(0);

            SocketBridge bridge = new SocketBridge(local, relay, tunnelExecutor);
            bridges.add(bridge);
        } catch (IOException e) {
            closeQuietly(local);
            closeQuietly(relay);
            if (!closed.get()) {
                failureListener.accept(e);
            }
        }
    }

    private void sendHeartbeat() {
        if (closed.get()) {
            return;
        }
        synchronized (controlSocket) {
            try {
                RelayWire.writeFrame(controlSocket.getOutputStream(), RelayProtocol.HEARTBEAT, out -> { });
            } catch (IOException e) {
                if (!closed.get()) {
                    failureListener.accept(e);
                    close();
                }
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(controlSocket);
        for (SocketBridge bridge : bridges) {
            bridge.close();
        }
        bridges.clear();
        for (Socket socket : openSockets) {
            closeQuietly(socket);
        }
        openSockets.clear();
        tunnelExecutor.shutdownNow();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
