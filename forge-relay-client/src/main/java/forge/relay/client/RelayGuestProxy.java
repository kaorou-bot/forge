package forge.relay.client;

import forge.relay.RelayProtocol;
import forge.relay.RelayWire;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Presents a loopback TCP port to the existing {@code FGameClient} and relays it
 * through a selected central-lobby room.
 */
public final class RelayGuestProxy implements AutoCloseable {
    private final Socket relaySocket;
    private final ServerSocket localListener;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Socket localSocket;
    private volatile SocketBridge bridge;

    private RelayGuestProxy(Socket relaySocket, ServerSocket localListener) {
        this.relaySocket = relaySocket;
        this.localListener = localListener;
        this.executor = Executors.newFixedThreadPool(3, daemonFactory("forge-relay-guest"));
        executor.execute(this::acceptLocalClient);
    }

    public static RelayGuestProxy open(RelayEndpoint endpoint, String compatibilityVersion,
                                       String roomId, String password) throws IOException {
        Socket relay = RelayLobbyClient.connect(endpoint);
        boolean success = false;
        try {
            RelayWire.writeFrame(relay.getOutputStream(), RelayProtocol.JOIN_ROOM, out -> {
                out.writeInt(RelayProtocol.VERSION);
                RelayWire.writeString(out, compatibilityVersion);
                RelayWire.writeString(out, roomId);
                RelayWire.writeString(out, password);
            });
            RelayWire.Frame pending = RelayWire.readFrame(relay.getInputStream());
            RelayWire.requireType(pending, RelayProtocol.JOIN_PENDING);
            RelayWire.readString(pending.body());

            RelayWire.Frame ready = RelayWire.readFrame(relay.getInputStream());
            RelayWire.requireType(ready, RelayProtocol.TUNNEL_READY);
            relay.setSoTimeout(0);

            ServerSocket listener = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
            RelayGuestProxy proxy = new RelayGuestProxy(relay, listener);
            success = true;
            return proxy;
        } finally {
            if (!success) {
                relay.close();
            }
        }
    }

    public int localPort() {
        return localListener.getLocalPort();
    }

    private void acceptLocalClient() {
        try {
            Socket accepted = localListener.accept();
            accepted.setTcpNoDelay(true);
            localSocket = accepted;
            localListener.close();
            if (closed.get()) {
                accepted.close();
                return;
            }
            bridge = new SocketBridge(accepted, relaySocket, executor);
        } catch (IOException e) {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (bridge != null) {
            bridge.close();
        }
        closeQuietly(localSocket);
        closeQuietly(relaySocket);
        try {
            localListener.close();
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
        executor.shutdownNow();
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

    static ThreadFactory daemonFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }
}
