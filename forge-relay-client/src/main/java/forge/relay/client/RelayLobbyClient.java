package forge.relay.client;

import forge.relay.RelayProtocol;
import forge.relay.RelayWire;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Blocking lobby operations intended to run on Forge's background thread. */
public final class RelayLobbyClient {
    static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    static final int CONTROL_TIMEOUT_MILLIS = 35_000;
    private static final int MAX_ROOM_LIST_SIZE = 1_000;

    private RelayLobbyClient() {
    }

    public static List<RelayProtocol.RoomSnapshot> listRooms(
            RelayEndpoint endpoint, String compatibilityVersion) throws IOException {
        try (Socket socket = connect(endpoint)) {
            RelayWire.writeFrame(socket.getOutputStream(), RelayProtocol.LIST_ROOMS, out -> {
                out.writeInt(RelayProtocol.VERSION);
                RelayWire.writeString(out, compatibilityVersion);
            });
            RelayWire.Frame frame = RelayWire.readFrame(socket.getInputStream());
            RelayWire.requireType(frame, RelayProtocol.ROOM_LIST);
            DataInputStream body = frame.body();
            int count = body.readInt();
            if (count < 0 || count > MAX_ROOM_LIST_SIZE) {
                throw new IOException("Invalid room count " + count);
            }
            List<RelayProtocol.RoomSnapshot> rooms = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                rooms.add(new RelayProtocol.RoomSnapshot(
                        RelayWire.readString(body),
                        RelayWire.readString(body),
                        RelayWire.readString(body),
                        RelayWire.readString(body),
                        RelayWire.readString(body),
                        body.readInt(),
                        body.readInt(),
                        body.readBoolean()));
            }
            return List.copyOf(rooms);
        }
    }

    static Socket connect(RelayEndpoint endpoint) throws IOException {
        Socket socket = new Socket();
        boolean success = false;
        try {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), CONNECT_TIMEOUT_MILLIS);
            if (endpoint.tls()) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket tlsSocket = (SSLSocket) factory.createSocket(
                        socket, endpoint.host(), endpoint.port(), true);
                socket = tlsSocket;
                // Android 14's Conscrypt TLS 1.3 client can leave small responses
                // from a persistent Nginx stream connection unread until the peer
                // closes the connection. Lobby list requests appeared to work only
                // because those connections are intentionally one-shot, while room
                // registration and join control connections timed out. TLS 1.2 is
                // still fully encrypted and avoids that persistent-stream issue.
                if (isAndroidRuntime()) {
                    tlsSocket.setEnabledProtocols(new String[] { "TLSv1.2" });
                }
                SSLParameters parameters = tlsSocket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tlsSocket.setSSLParameters(parameters);
                tlsSocket.startHandshake();
            }
            socket.setSoTimeout(CONTROL_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            success = true;
            return socket;
        } finally {
            if (!success) {
                socket.close();
            }
        }
    }

    private static boolean isAndroidRuntime() {
        String runtimeName = System.getProperty("java.runtime.name", "");
        String vmName = System.getProperty("java.vm.name", "");
        return runtimeName.contains("Android") || vmName.contains("Dalvik");
    }
}
